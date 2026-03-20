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
    .where("enlisted_year > :year")
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
    .where("id = :id")
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

## Dynamic Type System

Octavius uses `dynamic_dto` — a PostgreSQL composite type combining a type discriminator with JSONB payload — to bridge static SQL and Kotlin's type system. This type is automatically initialized in the **`public`** schema on startup.

```sql
-- Created automatically by Octavius in "public" schema
CREATE TYPE public.dynamic_dto AS (
    type_name    TEXT,
    data_payload JSONB
);

-- Helper function for constructing values
CREATE OR REPLACE FUNCTION public.dynamic_dto(p_type_name TEXT, p_data JSONB)
RETURNS public.dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::public.dynamic_dto;
END;
$$ LANGUAGE plpgsql;
```

### 1. Polymorphic Storage

Store different types in a single column or array. The framework deserializes each element to its correct Kotlin class based on `type_name`.

```kotlin
@DynamicallyMappable(typeName = "inscription")
@Serializable
data class Inscription(val text: String, val language: String)

@DynamicallyMappable(typeName = "relief")
@Serializable
data class Relief(val subject: String, val depictedBattle: String?)

// Database: CREATE TABLE monuments (id INT, records dynamic_dto[]);

val records: List<Any> = listOf(
    Inscription("SENATUS POPULUSQUE ROMANUS", "Latin"),
    Relief("Triumph of Trajan", "Dacian Wars")
)

dataAccess.insertInto("monuments")
    .value("records")
    .execute("records" to records)

// Read back — each element deserialized to its correct type
val monument = dataAccess.select("records")
    .from("monuments")
    .where("id = 1")
    .toField<List<Any>>()  // Returns [Inscription(...), Relief(...)]
```

### 2. Ad-hoc Object Mapping

Construct Kotlin objects directly in SQL using `jsonb_build_object` — no need to define PostgreSQL COMPOSITE types. Perfect for JOINs and projections where you want nested results without schema changes.

```kotlin
@DynamicallyMappable(typeName = "citizen_profile")
@Serializable
data class CitizenProfile(val tribe: String, val rights: List<String>)

data class CitizenWithProfile(val id: Int, val name: String, val profile: CitizenProfile)

val citizens = dataAccess.rawQuery("""
    SELECT
        c.id,
        c.name,
        dynamic_dto(
            'citizen_profile',
            jsonb_build_object(
                'tribe', p.tribe,
                'rights', p.rights
            )
        ) AS profile
    FROM citizens c
    JOIN citizen_profiles p ON p.citizen_id = c.id
""").toListOf<CitizenWithProfile>()
```

> **Why use this?** Usually, to get a citizen with their profile in one query, you'd fetch flat columns (`citizen_id`, `citizen_name`, `profile_tribe`...) and manually map them, or create a database VIEW. With ad-hoc mapping, you construct the nested structure directly in SQL. The database does the packaging, Octavius does the unpacking — zero boilerplate.


## Functions and Procedures

Octavius stays true to its SQL-first philosophy. Invoke functions and procedures directly using native PostgreSQL syntax:

```kotlin
// Functions (SELECT * FROM func)
val result = dataAccess.select("*").from("calculate_tribute(:province, :year)")
    .toField<Int>("province" to "Britannia", "year" to 43)

// Procedures (CALL proc)
val result = dataAccess.rawQuery("CALL register_conscript(:legion_id, NULL::text)")
    .toSingleStrict("legion_id" to 7)
```

## Safe Dynamic Filters

Build complex `WHERE` clauses without SQL injection risks:

```kotlin
fun buildFilters(name: String?, minRank: Int?, province: Province?) = listOfNotNull(
    name?.let { "name ILIKE :name" withParam ("name" to "%$it%") },
    minRank?.let { "rank_order >= :minRank" withParam ("minRank" to it) },
    province?.let { "home_province = :province" withParam ("province" to it) }
).join(" AND ")

val filter = buildFilters(name = "Julius", minRank = 3, province = null)
val senators = dataAccess.select("*")
    .from("senate")
    .where(filter.sql)
    .toListOf<Senator>(filter.params)
```

## Transaction Plans

Execute multi-step operations atomically with dependencies between steps:

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

## Configuration

### Using Properties File

Create a `database.properties` file in `src/main/resources`:

```properties
db.url=jdbc:postgresql://localhost:5432/roma
db.username=augustus
db.password=spqr
db.schemas=public,cursus_honorum
db.packagesToScan=com.roma.domain,com.roma.dto

# Optional settings
db.setSearchPath=true
db.dynamicDtoStrategy=AUTOMATIC_WHEN_UNAMBIGUOUS
db.disableFlyway=false
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
        packagesToScan = listOf("com.roma.domain")
    )
)

// From existing DataSource
val dataAccess = OctaviusDatabase.fromDataSource(existingDataSource, ...)
```

## Database Migrations

Octavius Database integrates [Flyway](https://flywaydb.org/) for schema migrations. Migration files are loaded from `src/main/resources/db/migration/` and applied automatically on startup.

- To disable automatic migrations, set `disableFlyway = true` in `DatabaseConfig`.
- To integrate with an existing database, set `flywayBaselineVersion` to the current version. Flyway will treat the existing schema as the baseline.

## Documentation

For detailed guides and examples, see the [full documentation](docs/README.md):

- [Configuration](docs/configuration.md) - Initialization, Flyway, core types, DynamicDto strategy
- [Query Builders](docs/query-builders.md) - SELECT, INSERT, UPDATE, DELETE, CTEs, subqueries, ON CONFLICT
- [Functions & Procedures](docs/functions-and-procedures.md) - CALL, SELECT, IN/OUT, functions vs procedures
- [Executing Queries](docs/executing-queries.md) - Terminal methods, DataResult, async, streaming
- [Data Mapping](docs/data-mapping.md) - toMap(), toDataObject(), @MapKey, nested structures & strict typing
- [ORM-Like Patterns](docs/orm-patterns.md) - CRUD patterns, real-world examples
- [Transactions](docs/transactions.md) - Transaction plans, StepHandle, passing data between steps
- [Notifications](docs/notifications.md) - LISTEN/NOTIFY, PgChannelListener, Flow-based receiving
- [Error Handling](docs/error-handling.md) - Exception hierarchy, debugging
- [Type System](docs/type-system.md) - @PgEnum, @PgComposite, @DynamicallyMappable, helper serializers

## Architecture

| Module | Platform      | Description                                                   |
|--------|---------------|---------------------------------------------------------------|
| `api`  | Multiplatform | Public API, interfaces; annotations with no JVM dependencies. |
| `core` | JVM           | Implementation using Spring JDBC & HikariCP.                  |
