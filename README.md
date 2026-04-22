# Octavius Database

<div align="center">

**An explicit, SQL-first data access layer for Kotlin & PostgreSQL**

[![KDoc API](https://img.shields.io/badge/KDoc-api-7F52FF?logo=kotlin&logoColor=white)](https://octavius-framework.github.io/octavius-database/api)
[![KDoc Core](https://img.shields.io/badge/KDoc-core-7F52FF?logo=kotlin&logoColor=white)](https://octavius-framework.github.io/octavius-database/core)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/database-core.svg?label=Maven%20Central)](https://central.sonatype.com/search?namespace=io.github.octavius-framework)

*It's not an ORM. It's a **ROME** (Relational-Object Mapping Engine). Because all queries lead to ROME.*

</div>

---

## Philosophy (The Pax Romana of Data)

*Just as Augustus brought order to a republic torn apart by the chaos of unchecked power, Octavius brings order to the chaotic republic of database interactions. The Senate of abstraction is dissolved. SQL rules supreme.*

Octavius was built to bring order to the chaotic republic of database interactions. It rejects the unpredictable "magic" of traditional ORMs and returns the power to the rightful ruler: **SQL**.

| Principle                   | Description                                                       |
|-----------------------------|-------------------------------------------------------------------|
| **Query is Imperator**      | Your SQL query dictates the shape of data — not the framework.    |
| **Object is a Vessel**      | A `data class` is simply a type-safe container for query results. |
| **Explicitness over Magic** | No lazy-loading, no session management, no dirty checking.        |

## Features

- **Fluent Query Builders** — SELECT, INSERT, UPDATE, DELETE with a clean API
- **Automatic Type Mapping** — PostgreSQL `COMPOSITE`, `ENUM`, `ARRAY` ↔ Kotlin types
- **Dynamic Type System** — Polymorphic storage & ad-hoc object mapping with `dynamic_dto`
- **Transaction Plans** — Multi-step atomic operations with step dependencies
- **Dynamic Filters** — Safe, composable `WHERE` clauses with `QueryFragment`
- **Stored Procedures** — CALL with automatic IN/OUT/INOUT handling, composite & array expansion
- **LISTEN / NOTIFY** — Flow-based async notifications on a dedicated connection outside the pool

## Quick Start

```kotlin
// Define your data class — it maps directly to query results
data class Legionnaire(val id: Int, val name: String, val rank: String)

// Query with named parameters
val legionnaires = dataAccess.select("id", "name", "rank")
    .from("legions")
    .where("enlisted_year > @year")
    .orderBy("name")
    .toListOf<Legionnaire>("year" to 24)
```

## Query Builders

```kotlin
// SELECT with pagination
val senators = dataAccess.select("id", "name", "province")
    .from("senate")
    .where("active = true")
    .orderBy("appointed_at DESC")
    .limit(10)
    .offset(20)
    .toListOf<Senator>()

// INSERT with RETURNING
val newId = dataAccess.insertInto("citizens")
    .value("name")
    .value("tribe")
    .returning("id")
    .toField<Int>(mapOf("name" to "Marcus Aurelius", "tribe" to "Cornelia"))

// UPDATE with expressions
dataAccess.update("legion_supplies")
    .setExpression("quantity", "quantity - 1")
    .where("id = @id")
    .execute("id" to supplyId)

// DELETE
dataAccess.deleteFrom("expired_mandates")
    .where("expires_at < NOW()")
    .execute()
```


## Type Mapping

Automatic conversion between PostgreSQL and Kotlin types.

### Standard Types

| PostgreSQL                | Kotlin          | Notes                                            |
|---------------------------|-----------------|--------------------------------------------------|
| `int2`, `smallserial`     | `Short`         |                                                  |
| `int4`, `serial`          | `Int`           |                                                  |
| `int8`, `bigserial`       | `Long`          |                                                  |
| `float4`                  | `Float`         |                                                  |
| `float8`                  | `Double`        |                                                  |
| `numeric`                 | `BigDecimal`    |                                                  |
| `text`, `varchar`, `char` | `String`        |                                                  |
| `bool`                    | `Boolean`       |                                                  |
| `uuid`                    | `UUID`          | `java.util.UUID`                                 |
| `bytea`                   | `ByteArray`     |                                                  |
| `json`, `jsonb`           | `JsonElement`   | `kotlinx.serialization.json`                     |
| `void`                    | `Unit`          | Return type of void functions (e.g. `pg_notify`) |
| `date`                    | `LocalDate`     | `kotlinx.datetime` <sup>*</sup>                  |
| `time`                    | `LocalTime`     | `kotlinx.datetime`                               |
| `timetz`                  | `OffsetTime`    | `java.time`                                      |
| `timestamp`               | `LocalDateTime` | `kotlinx.datetime` <sup>*</sup>                  |
| `timestamptz`             | `Instant`       | `kotlin.time` <sup>*</sup>                       |
| `interval`                | `Duration`      | `kotlin.time` <sup>*</sup>                       |

<sup>*</sup> Supports PostgreSQL infinity values (`infinity`, `-infinity`). See [Type System](docs/type-system.md#infinity-values-for-datetime) for details.

Arrays of all standard types are supported and map to `List<T>`.

### Custom Types

```kotlin
// PostgreSQL COMPOSITE TYPE → Kotlin data class
@PgComposite
data class Province(val name: String, val capital: String, val governor: String)

// PostgreSQL ENUM → Kotlin enum
@PgEnum(schema = "cursus_honorum")
enum class Magistrature { Quaestor, Aedile, Praetor, Consul, Censor }

// Works seamlessly in queries
data class Senator(val id: Int, val rank: Magistrature, val homeProvince: Province)

val senators = dataAccess.select("id", "rank", "home_province")
    .from("senate")
    .toListOf<Senator>()  // Types converted automatically
```

## Dynamic Type System (`dynamic_dto`)

Octavius provides a powerful bridge between PostgreSQL and Kotlin's type system using the `dynamic_dto` composite type (`type_name TEXT`, `data_payload JSONB`).
It allows you to map complex, nested, or polymorphic data on the fly without creating strict database schema types for every nested object.
This type is automatically initialized in the **`public`** schema on startup.


### 1. Ad-hoc Object Mapping

Construct Kotlin objects directly in SQL using `jsonb_build_object` — no need to define PostgreSQL COMPOSITE types. Perfect for JOINs and projections where you want nested results without schema changes.

```kotlin
@DynamicallyMappable(typeName = "citizen_profile")
@Serializable
data class CitizenProfile(val tribe: String, val rights: List<String>)

data class CitizenWithProfile(val id: Int, val name: String, val profile: CitizenProfile)

// The database packages the nested object, Octavius unpacks it. Zero boilerplate.
val citizens = dataAccess.rawQuery("""
    SELECT
        c.id,
        c.name,
        dynamic_dto(
            'citizen_profile',
            jsonb_build_object('tribe', p.tribe, 'rights', p.rights)
        ) AS profile
    FROM citizens c
    JOIN citizen_profiles p ON p.citizen_id = c.id
""").toListOf<CitizenWithProfile>()
```

> **Why use this?** Usually, to get a citizen with their profile in one query, you'd fetch flat columns (`citizen_id`, `citizen_name`, `profile_tribe`...) 
> and manually map them, create a database VIEW or COMPOSITE. With ad-hoc mapping, you construct the nested structure directly in SQL. 
> The database does the packaging, Octavius does the unpacking — zero boilerplate.

### 2. Polymorphic Queries (Row-level)
Store different entity types in a single table and query them safely as a list of Kotlin interfaces.

```kotlin
// 1. Define a sealed interface
sealed interface MonumentRecord

@DynamicallyMappable(typeName = "inscription")
@Serializable
data class Inscription(val text: String, val lang: String) : MonumentRecord

@DynamicallyMappable(typeName = "relief")
@Serializable
data class Relief(val subject: String) : MonumentRecord

// Database: CREATE TABLE monument_records (id INT, record dynamic_dto);

// 2. Fetch directly to a list of your interface
val records = dataAccess.select("record")
    .from("monument_records")
    .toColumn<MonumentRecord>()

// Returns: [Inscription(...), Relief(...), Inscription(...)]
```

## Functions and Procedures

Octavius stays true to its SQL-first philosophy. Invoke functions and procedures directly using native PostgreSQL syntax:

```kotlin
// Functions (SELECT * FROM func)
val result = dataAccess.select("*").from("calculate_tribute(@province, @year)")
    .toField<Int>("province" to "Britannia", "year" to 43)

// Procedures (CALL proc)
val result = dataAccess.rawQuery("CALL register_conscript(@legion_id, @new_rank)")
    .toSingleStrict(
        "legion_id" to 7,
        "new_rank" to null.withPgType("text")
    )
```

## Safe Dynamic Filters

Build complex `WHERE` clauses without SQL injection risks:

```kotlin
fun buildFilters(name: String?, minRank: Int?, province: Province?) = listOfNotNull(
    name?.let { "name ILIKE @name" withParam ("name" to "%$it%") },
    minRank?.let { "rank_order >= @minRank" withParam ("minRank" to it) },
    province?.let { "home_province = @province" withParam ("province" to it) }
).join(" AND ")

val filter = buildFilters(name = "Julius", minRank = 3, province = null)
val senators = dataAccess.select("*")
    .from("senate")
    .where(filter.sql)
    .toListOf<Senator>(filter.params)
```

## Transactions

Octavius supports two powerful interaction patterns for atomic operations.

### 1. Transaction Blocks (Imperative)
The simplest way to execute multiple operations. Transactions follow a fail-fast policy: they are automatically rolled back if the block returns `DataResult.Failure` or throws an exception.

```kotlin
val result = dataAccess.transaction { tx ->
    val citizenId = tx.insertInto("citizens")
        .value("name")
        .returning("id")
        .toField<Int>("name" to "Marcus Aurelius")
        .getOrElse { return@transaction DataResult.Failure(it) }

    tx.insertInto("citizen_profiles")
        .values(listOf("citizen_id", "bio"))
        .execute("citizen_id" to citizenId, "bio" to "Stoic philosopher")
        .getOrElse { return@transaction DataResult.Failure(it) }

    DataResult.Success(citizenId)
}
```

### 2. Transaction Plans (Declarative)
Execute multi-step operations with complex dependencies between steps. Results from previous steps can be referenced in subsequent steps without nested callbacks or manual state management.

```kotlin
val plan = TransactionPlan()

// Step 1: Record the edict, get handle to future ID
val edictIdHandle = plan.add(
    dataAccess.insertInto("edicts")
        .values(listOf("issuer_id", "total_tribute"))
        .returning("id")
        .asStep()
        .toField<Int>(mapOf("issuer_id" to consulId, "total_tribute" to tribute))
)

// Step 2: Assign levy items using the handle
for (item in levyItems) {
    val levyItem: Map<String, Any?> = mapOf(
        "edict_id" to edictIdHandle.field(),  // Reference future value
        "province_id" to item.provinceId,
        "amount" to item.amount
    )

    plan.add(
        dataAccess.insertInto("edict_items")
            .values(levyItem)
            .asStep()
            .execute(levyItem)
    )
}

// Execute all steps in single transaction
dataAccess.executeTransactionPlan(plan)
```

## LISTEN / NOTIFY

Subscribe to PostgreSQL channels and receive real-time notifications as a Kotlin `Flow`:

```kotlin
// Send a notification
dataAccess.notify("legion_dispatch", "legion_id:VII")

// Listen on a dedicated connection (outside the HikariCP pool)
dataAccess.createChannelListener().use { listener ->
    listener.listen("legion_dispatch", "senate_decrees")

    listener.notifications()
        .collect { notification ->
            when (notification.channel) {
                "legion_dispatch" -> handleDispatch(notification.payload)
                "senate_decrees"  -> handleDecree(notification.payload)
            }
        }
}
```

Each `PgChannelListener` holds its own dedicated JDBC connection, separate from the query pool. Notifications sent inside a transaction are only delivered after commit.

## Error Handling

Octavius distinguishes between **Database Execution Errors** (returned safely) and **Fatal/Setup Errors** (thrown).

- **Queries Never Throw:** If a query reaches the database, it returns a `DataResult.Failure(error)` instead of throwing. This forces explicit handling of database errors like constraint violations or syntax issues.
- **Rich Context:** Every `DatabaseException` includes a `QueryContext` that provides a clean visualization of the SQL and parameters involved (great for logging!).
- **Structured Exceptions:** Specific types like `ConstraintViolationException` provide direct access to table and constraint names.

```kotlin
val result = dataAccess.insertInto("citizens")
    .value("name")
    .returning("id")
    .toField<Int>("name" to "Marcus Aurelius")

result
    .onSuccess { id -> println("New citizen ID: $id") }
    .onFailure { error ->
        when (error) {
            is ConstraintViolationException -> println("Conflict in: ${error.constraintName}")
            is StatementException -> println("SQL Syntax error: ${error.messageEnum}")
            else -> println("Database error: $error")
        }
    }
```

See [Error Handling](docs/error-handling.md) for the full exception hierarchy and debugging tips.

## Configuration

### Using Properties File

Create a `database.properties` file in `src/main/resources`:

```properties
db.url=jdbc:postgresql://localhost:5432/roma
db.username=augustus
db.password=spqr
db.schemas=public,cursus_honorum
db.packagesToScan=com.roma.domain,com.roma.dto

# Custom HikariCP settings
db.hikari.maximumPoolSize=20
db.hikari.minimumIdle=5

# Optional settings
db.setSearchPath=true
db.dynamicDtoStrategy=AUTOMATIC_WHEN_UNAMBIGUOUS
db.disableCoreTypeInitialization=false
```

Load it in your application:

```kotlin
// From properties file
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig.loadFromFile("database.properties")
)
```

### Direct Configuration

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig(
        dbUrl = "jdbc:postgresql://localhost:5432/roma",
        dbUsername = "augustus",
        dbPassword = "spqr",
        dbSchemas = listOf("public"),
        packagesToScan = listOf("com.roma.domain"),
        hikariProperties = mapOf("maximumPoolSize" to "20")
    )
)

// From existing DataSource
val dataAccess = OctaviusDatabase.fromDataSource(existingDataSource, ...)
```

## Database Migrations

Octavius provides an optional integration with [Flyway](https://flywaydb.org/) for schema migrations via the `:flyway-integration` module.

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(
    config = config,
    migrationRunner = FlywayMigrationRunner.create(
        schemas = config.dbSchemas,
        baselineVersion = "1"
    )
)
```

See [Flyway Migrations](docs/configuration.md#flyway-migrations) in the configuration guide for details.

## Documentation

For detailed guides and examples, see the [full documentation](docs/README.md):

- [Configuration](docs/configuration.md) - Initialization, HikariCP pool, Flyway, core types, DynamicDto strategy
- [Multiplatform Support](docs/multiplatform.md) - Shared DTOs, Multiplatform BigDecimal, and JS serializers
- [Lifecycle & Shutdown](docs/lifecycle-and-shutdown.md) - Proper cleanup, .use {} block, common integration patterns
- [Query Builders](docs/query-builders.md) - SELECT (FOR UPDATE), INSERT (ON CONFLICT), UPDATE, DELETE, fragments
- [Functions & Procedures](docs/functions-and-procedures.md) - CALL, SELECT, IN/OUT, PgTyped resolution
- [Executing Queries](docs/executing-queries.md) - Terminal methods, DataResult matrix, async, streaming
- [Parameter Handling](docs/parameter-handling.md) - Named parameters (@), JSONB operator escaping (?), collections & flattening, unnest and bulk operations
- [Data Mapping](docs/data-mapping.md) - toDataMap(), toDataObject(), @MapKey, nested structures
- [ORM-Like Patterns](docs/orm-patterns.md) - CRUD patterns, real-world examples
- [Transactions](docs/transactions.md) - Transaction blocks, TransactionPlan, StepHandle, passing data between steps , propagation, isolation, read-only, timeouts, errors and Concurrency & Thread Safety
- [Notifications](docs/notifications.md) - LISTEN/NOTIFY, PgChannelListener, Flow-based receiving
- [Error Handling](docs/error-handling.md) - Exception hierarchy, debugging
- [Type System](docs/type-system.md) - @PgEnum, @PgComposite, @DynamicallyMappable, dynamic data insertion, standard type mappings

## Architecture

| Module               | Platform      | Description                                                                            |
|----------------------|---------------|----------------------------------------------------------------------------------------|
| `api`                | Multiplatform | **Common:** Annotations & DTOs (JVM/JS). **JVM-only:** Query & Transaction interfaces. |
| `core`               | JVM           | **Zero-dependency** core engine. Pure JDBC & HikariCP.                                 |
| `spring-integration` | JVM           | Optional integration for Spring Boot (`@Transactional` support).                       |
| `flyway-integration` | JVM           | Optional migration runner integration.                                                 |
