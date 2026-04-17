# Configuration

*Before a legion can march, the Legate must issue orders: which roads to follow, which provinces to govern, which formations to hold. Octavius likewise demands its configuration before the first query may be sent forth.*

This guide covers all configuration options for Octavius Database, including initialization, Flyway migrations, and core type setup.

## Table of Contents

- [Initialization](#initialization)
- [DatabaseConfig Reference](#databaseconfig-reference)
- [Properties File](#properties-file)
- [Flyway Migrations](#flyway-migrations)
- [Core Type Initialization](#core-type-initialization)
- [DynamicDto Serialization Strategy](#dynamicdto-serialization-strategy)
- [Schema Configuration](#schema-configuration)
- [Using Existing DataSource](#using-existing-datasource)
- [Listener Connection Factory](#listener-connection-factory)

---

## Initialization

Two ways to initialize Octavius Database:

### From Config Object

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig(
        dbUrl = "jdbc:postgresql://localhost:5432/roma",
        dbUsername = "augustus",
        dbPassword = "spqr",
        dbSchemas = listOf("public", "cursus_honorum"),
        setSearchPath = true,
        packagesToScan = listOf("com.roma.domain", "com.roma.dto")
    )
)
```

### From Properties File

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig.loadFromFile("database.properties")
)
```

---

## DatabaseConfig Reference

| Property                        | Type                              | Required | Default                      | Description                           |
|---------------------------------|-----------------------------------|----------|------------------------------|---------------------------------------|
| `dbUrl`                         | `String`                          | Yes      | -                            | JDBC connection URL                   |
| `dbUsername`                    | `String`                          | Yes      | -                            | Database username                     |
| `dbPassword`                    | `String`                          | Yes      | -                            | Database password                     |
| `dbSchemas`                     | `List<String>`                    | Yes      | -                            | Schemas to handle                     |
| `setSearchPath`                 | `Boolean`                         | No       | `true`                       | Set `search_path` on connection init  |
| `packagesToScan`                | `List<String>`                    | Yes      | -                            | Packages to scan for type annotations |
| `dynamicDtoStrategy`            | `DynamicDtoSerializationStrategy` | No       | `AUTOMATIC_WHEN_UNAMBIGUOUS` | How to handle @DynamicallyMappable    |
| `flywayBaselineVersion`         | `String?`                         | No       | `null`                       | Baseline version for existing schemas |
| `disableFlyway`                 | `Boolean`                         | No       | `false`                      | Disable automatic migrations          |
| `disableCoreTypeInitialization` | `Boolean`                         | No       | `false`                      | Disable `dynamic_dto` type creation   |
| `hikariProperties`              | `Map<String, String>`             | No       | `emptyMap()`                 | HikariCP & Driver properties          |

---

## Properties File

Create `database.properties` in `src/main/resources`:

```properties
# Required
db.url=jdbc:postgresql://localhost:5432/roma
db.username=augustus
db.password=spqr
db.schemas=public,cursus_honorum
db.packagesToScan=com.roma.domain,com.roma.dto

# Optional
db.setSearchPath=true
db.dynamicDtoStrategy=AUTOMATIC_WHEN_UNAMBIGUOUS
db.flywayBaselineVersion=
db.disableFlyway=false
db.disableCoreTypeInitialization=false

# HikariCP Pool Settings (prefixed with db.hikari.)
db.hikari.maximumPoolSize=20
db.hikari.connectionTimeout=30000
db.hikari.poolName=OctaviusPool
db.hikari.leakDetectionThreshold=2000

# PostgreSQL Driver Settings (prefixed with db.hikari.dataSource.)
db.hikari.dataSource.sslmode=require
db.hikari.dataSource.ApplicationName=RomaBackend
db.hikari.dataSource.prepStmtCacheSize=250
```

### Property Details

**`db.schemas`** - Comma-separated list of schemas:
```properties
db.schemas=public,aerarium
```

**`db.packagesToScan`** - Packages containing your `@PgEnum`, `@PgComposite`, `@DynamicallyMappable` classes:
```properties
db.packagesToScan=com.roma.domain.types,com.roma.dto
```

---

## Flyway Migrations

Octavius integrates [Flyway](https://flywaydb.org/) for schema migrations.

### Default Behavior

- Migrations are loaded from `src/main/resources/db/migration/`
- Applied automatically on startup
- Schemas listed in `dbSchemas` are created if they don't exist

### Migration File Naming

```
src/main/resources/db/migration/
├── V1__create_citizens_table.sql
├── V2__create_legions_table.sql
├── V3__add_magistrature_enum.sql
└── V4__create_province_audit_log.sql
```

### Disabling Migrations

When you manage schema externally or in production environments:

```kotlin
DatabaseConfig(
    // ...
    disableFlyway = true
)
```

```properties
db.disableFlyway=true
```

### Integrating with Existing Database

When connecting to a database that already has a schema (not managed by Flyway):

```kotlin
DatabaseConfig(
    // ...
    flywayBaselineVersion = "3"  // Treat existing schema as version 3
)
```

Flyway will:
1. Create `flyway_schema_history` table
2. Mark versions 1-3 as already applied (baseline)
3. Only run migrations starting from V4

```properties
# In properties file
db.flywayBaselineVersion=3
```

---

## Core Type Initialization

On startup, Octavius automatically creates the `dynamic_dto` type and helper functions in the **`public`** PostgreSQL schema.

### What Gets Created

```sql
-- Composite type for polymorphic storage
CREATE TYPE public.dynamic_dto AS (
    type_name    TEXT,     -- Discriminator key (e.g., "citizen_profile")
    data_payload JSONB     -- Serialized data
);

-- Constructor function
CREATE OR REPLACE FUNCTION public.dynamic_dto(p_type_name TEXT, p_data JSONB)
RETURNS public.dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::public.dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Convenience function for any type
CREATE OR REPLACE FUNCTION public.to_dynamic_dto(p_type_name TEXT, p_value ANYELEMENT)
RETURNS public.dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, to_jsonb(p_value))::public.dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Overload for literals ('unknown' type)
CREATE OR REPLACE FUNCTION public.to_dynamic_dto(p_type_name TEXT, p_value TEXT)
    RETURNS public.dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, to_jsonb(p_value))::public.dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;


-- Unwrap function
CREATE OR REPLACE FUNCTION public.unwrap_dto_payload(p_dto public.dynamic_dto)
RETURNS JSONB AS $$
BEGIN
    RETURN p_dto.data_payload;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;
```

### Idempotent

The initialization is idempotent — safe to run on every startup:
- Uses `IF NOT EXISTS` for type creation
- Uses `CREATE OR REPLACE` for functions

### Disabling Core Type Initialization

When the application lacks DDL privileges or the schema is managed externally:

```kotlin
DatabaseConfig(
    // ...
    disableCoreTypeInitialization = true
)
```

```properties
db.disableCoreTypeInitialization=true
```

**Important:** When disabled, you must ensure `dynamic_dto` type and functions exist in the database. You can create them manually using the SQL above, or include them in your Flyway migrations.

### Startup Order

1. **HikariCP** - Connection pool initialized
2. **Core Types** - `dynamic_dto` created (unless disabled)
3. **Flyway** - User migrations applied (unless disabled)
4. **Type Registry** - Scans classpath and database for type mappings

---

## DynamicDto Serialization Strategy

Controls how classes with `@DynamicallyMappable` are serialized.

### Strategy Options

| Strategy                     | Description                                                               |
|------------------------------|---------------------------------------------------------------------------|
| `EXPLICIT_ONLY`              | Never auto-convert. Requires explicit `DynamicDto.from()` wrapping        |
| `AUTOMATIC_WHEN_UNAMBIGUOUS` | Auto-convert only when class has no `@PgComposite`/`@PgEnum`. **Default** |
| `PREFER_DYNAMIC_DTO`         | Always prefer `dynamic_dto` even when `@PgComposite` is present           |

### When Does Ambiguity Occur?

A class can have both `@DynamicallyMappable` AND `@PgComposite`:

```kotlin
@DynamicallyMappable(typeName = "province")
@PgComposite
@Serializable
data class Province(val name: String, val capital: String)
```

This allows:
- Storing in `province` COMPOSITE column (uses `@PgComposite`)
- Storing in `dynamic_dto` column (uses `@DynamicallyMappable`)

### Strategy Behavior

**`EXPLICIT_ONLY`:**
```kotlin
// Must wrap explicitly
dataAccess.insertInto("records")
    .values(listOf("data"))
    .execute("data" to DynamicDto.from(myObject))  // Explicit
```

**`AUTOMATIC_WHEN_UNAMBIGUOUS`:**
```kotlin
// Auto-converts if class ONLY has @DynamicallyMappable
@DynamicallyMappable(typeName = "edict")
@Serializable
data class Edict(val text: String)

dataAccess.insertInto("records")
    .values(listOf("data"))
    .execute("data" to Edict("Let it be known..."))  // Auto-converted to dynamic_dto

// But if class also has @PgComposite, uses COMPOSITE by default
@DynamicallyMappable(typeName = "province")
@PgComposite
@Serializable
data class Province(val name: String, val capital: String)

dataAccess.insertInto("records")
    .values(listOf("prov"))
    .execute("prov" to Province("Gallia", "Lugdunum"))  // Uses COMPOSITE
```

**`PREFER_DYNAMIC_DTO`:**
```kotlin
// Always uses dynamic_dto for @DynamicallyMappable, even with @PgComposite
dataAccess.insertInto("records")
    .values(listOf("prov"))
    .execute("prov" to Province("Gallia", "Lugdunum"))  // Uses dynamic_dto
```

### Configuration

```kotlin
DatabaseConfig(
    // ...
    dynamicDtoStrategy = DynamicDtoSerializationStrategy.EXPLICIT_ONLY
)
```

```properties
db.dynamicDtoStrategy=EXPLICIT_ONLY
```

---

## Schema Configuration

### Multiple Schemas

```kotlin
DatabaseConfig(
    // ...
    dbSchemas = listOf("public", "aerarium", "annona"),
    setSearchPath = true  // Sets search_path on each connection
)
```

### search_path

When `setSearchPath = true`, each connection executes:

```sql
SET search_path TO public, aerarium
```

This allows:
- Referencing tables without schema prefix: `citizens` instead of `public.citizens`
- Type registry finds types across all listed schemas

### Disabling search_path

If you prefer explicit schema references:

```kotlin
DatabaseConfig(
    // ...
    setSearchPath = false
)
```

Then use full paths in queries:
```kotlin
dataAccess.select("*").from("aerarium.tributes").toListOf<Tribute>()
```

---

## Using Existing DataSource

When you have an existing `DataSource` (e.g., from Spring Boot, Quarkus):

```kotlin
val dataAccess = OctaviusDatabase.fromDataSource(
    dataSource = existingDataSource,
    packagesToScan = listOf("com.roma.domain"),
    dbSchemas = listOf("public"),
    dynamicDtoStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
    flywayBaselineVersion = null,
    disableFlyway = true,
    disableCoreTypeInitialization = false
)
```

### Spring Boot Integration

```kotlin
@Configuration
class OctaviusConfig {

    @Bean
    fun dataAccess(dataSource: DataSource): DataAccess {
        return OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = listOf("com.roma.domain"),
            dbSchemas = listOf("public"),
            disableFlyway = true  // Spring manages Flyway
        )
    }
}
```

### Connection Pool

When using `fromConfig()`, Octavius creates a HikariCP pool with the following defaults:
- `maximumPoolSize = 10`
- `connectionInitSql` set to `SET search_path TO ...` if enabled

You can customize the pool by providing additional HikariCP properties in `DatabaseConfig.hikariProperties`. These properties will override the defaults.

#### Programmatic Configuration

```kotlin
val config = DatabaseConfig(
    dbUrl = "jdbc:postgresql://localhost:5432/roma",
    dbUsername = "augustus",
    dbPassword = "spqr",
    // ...
    hikariProperties = mapOf(
        "maximumPoolSize" to "20",
        "minimumIdle" to "5",
        "connectionTimeout" to "30000"
    )
)
val dataAccess = OctaviusDatabase.fromConfig(config)
```

#### Properties File Configuration

When loading from a properties file, use the `db.hikari.` prefix for all HikariCP settings. See the [Properties File](#properties-file) section for more details.

When using `fromDataSource()`, you manage the pool configuration yourself.

---

## Listener Connection Factory

PostgreSQL's `LISTEN`/`NOTIFY` mechanism requires a dedicated, long-lived connection that stays open while waiting for notifications. This connection must not come from the regular HikariCP pool, because holding a pool connection idle would permanently reduce available capacity for queries.

The `listenerConnectionFactory` parameter is a `() -> Connection` lambda invoked each time `dataAccess.createChannelListener()` is called. It should return a fresh, raw JDBC connection - not one borrowed from a pool.

### Behavior by Initialization Method

**`fromConfig()`** — handled automatically. Octavius creates a `DriverManager` factory that opens connections directly using the credentials from `DatabaseConfig`, with `search_path` applied if configured. No action needed.

**`fromDataSource(dataSource = ...)`** — auto-resolved based on the `DataSource` type:

| DataSource type    | Default behavior                                                                       |
|--------------------|----------------------------------------------------------------------------------------|
| `HikariDataSource` | Uses `DriverManager` with the pool's URL/credentials (bypasses pool)                   |
| Any other type     | Falls back to `dataSource.connection` with a warning - should provide a custom factory |

### Providing a Custom Factory

Pass a custom factory when using a non-HikariCP `DataSource`, or when you need specific connection settings for listener connections:

```kotlin
val dataAccess = OctaviusDatabase.fromDataSource(
    dataSource = existingDataSource,
    packagesToScan = listOf("com.roma.domain"),
    dbSchemas = listOf("public"),
    listenerConnectionFactory = {
        DriverManager.getConnection("jdbc:postgresql://localhost:5432/roma", "augustus", "spqr")
    }
)
```

### Spring Boot

Spring Boot's default auto-configured `DataSource` is a `HikariDataSource`, so auto-detection works out of the box - no custom factory needed:

```kotlin
@Bean
fun dataAccess(dataSource: DataSource): DataAccess {
    return OctaviusDatabase.fromDataSource(
        dataSource = dataSource,
        packagesToScan = listOf("com.roma.domain"),
        dbSchemas = listOf("public"),
        disableFlyway = true
        // listenerConnectionFactory auto-resolved from HikariDataSource
    )
}
```

A custom factory is only needed if you explicitly replace the default pool (e.g., Tomcat JDBC, DBCP2):

```kotlin
OctaviusDatabase.fromDataSource(
    dataSource = tomcatDataSource,
    // ...
    listenerConnectionFactory = {
        DriverManager.getConnection(url, username, password)
    }
)
```

### Connection Lifecycle

Each `PgChannelListener` holds exactly one connection opened by the factory. The connection is closed when the listener is closed:

```kotlin
dataAccess.createChannelListener().use { listener ->
    listener.listen("senate_decrees")
    listener.notifications()
        .collect { notification -> handleDecree(notification) }
}
// Connection is closed here
```

> **Note:** Listener connections are intentionally outside HikariCP management. They will not appear in pool metrics and are not subject to pool timeouts or eviction.
