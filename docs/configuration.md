# Configuration

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

---

## Initialization

Two ways to initialize Octavius Database:

### From Config Object

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig(
        dbUrl = "jdbc:postgresql://localhost:5432/mydb",
        dbUsername = "postgres",
        dbPassword = "secret",
        dbSchemas = listOf("public", "myschema"),
        setSearchPath = true,
        packagesToScan = listOf("com.myapp.domain", "com.myapp.dto")
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

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `dbUrl` | `String` | Yes | - | JDBC connection URL |
| `dbUsername` | `String` | Yes | - | Database username |
| `dbPassword` | `String` | Yes | - | Database password |
| `dbSchemas` | `List<String>` | Yes | - | Schemas to handle |
| `setSearchPath` | `Boolean` | No | `true` | Set `search_path` on connection init |
| `packagesToScan` | `List<String>` | Yes | - | Packages to scan for type annotations |
| `dynamicDtoStrategy` | `DynamicDtoSerializationStrategy` | No | `AUTOMATIC_WHEN_UNAMBIGUOUS` | How to handle @DynamicallyMappable |
| `flywayBaselineVersion` | `String?` | No | `null` | Baseline version for existing schemas |
| `disableFlyway` | `Boolean` | No | `false` | Disable automatic migrations |
| `disableCoreTypeInitialization` | `Boolean` | No | `false` | Disable `dynamic_dto` type creation |

---

## Properties File

Create `database.properties` in `src/main/resources`:

```properties
# Required
db.url=jdbc:postgresql://localhost:5432/mydb
db.username=postgres
db.password=secret
db.schemas=public,myschema
db.packagesToScan=com.myapp.domain,com.myapp.dto

# Optional
db.setSearchPath=true
db.dynamicDtoStrategy=AUTOMATIC_WHEN_UNAMBIGUOUS
db.flywayBaselineVersion=
db.disableFlyway=false
db.disableCoreTypeInitialization=false
```

### Property Details

**`db.schemas`** - Comma-separated list of schemas:
```properties
db.schemas=public,inventory,sales
```

**`db.packagesToScan`** - Packages containing your `@PgEnum`, `@PgComposite`, `@DynamicallyMappable` classes:
```properties
db.packagesToScan=com.myapp.domain.types,com.myapp.dto
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
├── V1__create_users_table.sql
├── V2__create_orders_table.sql
├── V3__add_order_status_enum.sql
└── V4__create_audit_log.sql
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

On startup, Octavius automatically creates the `dynamic_dto` type and helper functions in PostgreSQL.

### What Gets Created

```sql
-- Composite type for polymorphic storage
CREATE TYPE dynamic_dto AS (
    type_name    TEXT,     -- Discriminator key (e.g., "user_profile")
    data_payload JSONB     -- Serialized data
);

-- Constructor function
CREATE OR REPLACE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
RETURNS dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Convenience function for any type
CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value ANYELEMENT)
RETURNS dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, to_jsonb(p_value))::dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Unwrap function
CREATE OR REPLACE FUNCTION unwrap_dto_payload(p_dto dynamic_dto)
RETURNS JSONB AS $$
BEGIN
    RETURN p_dto.data_payload;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;
```

### Idempotent

The initialization is idempotent - safe to run on every startup:
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

| Strategy | Description |
|----------|-------------|
| `EXPLICIT_ONLY` | Never auto-convert. Requires explicit `DynamicDto.from()` wrapping |
| `AUTOMATIC_WHEN_UNAMBIGUOUS` | Auto-convert only when class has no `@PgComposite`/`@PgEnum`. **Default** |
| `PREFER_DYNAMIC_DTO` | Always prefer `dynamic_dto` even when `@PgComposite` is present |

### When Does Ambiguity Occur?

A class can have both `@DynamicallyMappable` AND `@PgComposite`:

```kotlin
@DynamicallyMappable(typeName = "address")
@PgComposite
@Serializable
data class Address(val street: String, val city: String)
```

This allows:
- Storing in `address` COMPOSITE column (uses `@PgComposite`)
- Storing in `dynamic_dto` column (uses `@DynamicallyMappable`)

### Strategy Behavior

**`EXPLICIT_ONLY`:**
```kotlin
// Must wrap explicitly
dataAccess.insertInto("table")
    .values(listOf("data"))
    .execute("data" to DynamicDto.from(myObject))  // Explicit
```

**`AUTOMATIC_WHEN_UNAMBIGUOUS`:**
```kotlin
// Auto-converts if class ONLY has @DynamicallyMappable
@DynamicallyMappable(typeName = "note")
@Serializable
data class Note(val text: String)

dataAccess.insertInto("table")
    .values(listOf("data"))
    .execute("data" to Note("Hello"))  // Auto-converted to dynamic_dto

// But if class also has @PgComposite, uses COMPOSITE by default
@DynamicallyMappable(typeName = "address")
@PgComposite
@Serializable
data class Address(val street: String, val city: String)

dataAccess.insertInto("table")
    .values(listOf("addr"))
    .execute("addr" to Address("Main St", "NYC"))  // Uses COMPOSITE
```

**`PREFER_DYNAMIC_DTO`:**
```kotlin
// Always uses dynamic_dto for @DynamicallyMappable, even with @PgComposite
dataAccess.insertInto("table")
    .values(listOf("addr"))
    .execute("addr" to Address("Main St", "NYC"))  // Uses dynamic_dto
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
    dbSchemas = listOf("public", "inventory", "sales"),
    setSearchPath = true  // Sets search_path on each connection
)
```

### search_path

When `setSearchPath = true`, each connection executes:

```sql
SET search_path TO public, inventory, sales
```

This allows:
- Referencing tables without schema prefix: `users` instead of `public.users`
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
dataAccess.select("*").from("inventory.products").toListOf<Product>()
```

---

## Using Existing DataSource

When you have an existing `DataSource` (e.g., from Spring Boot, Quarkus):

```kotlin
val dataAccess = OctaviusDatabase.fromDataSource(
    dataSource = existingDataSource,
    packagesToScan = listOf("com.myapp.domain"),
    dbSchemas = listOf("public"),
    dynamicDtoStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
    flywayBaselineVersion = null,
    disableFlyway = true,           // Let the framework manage migrations
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
            packagesToScan = listOf("com.myapp.domain"),
            dbSchemas = listOf("public"),
            disableFlyway = true  // Spring manages Flyway
        )
    }
}
```

### Connection Pool

When using `fromConfig()`, Octavius creates a HikariCP pool with:
- `maximumPoolSize = 10`
- `connectionInitSql` set to `SET search_path TO ...` if enabled

When using `fromDataSource()`, you manage the pool configuration yourself.