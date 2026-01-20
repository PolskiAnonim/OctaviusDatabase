# Octavius Database

<div align="center">

**An explicit, SQL-first data access layer for Kotlin & PostgreSQL**

[![KDoc API](https://img.shields.io/badge/KDoc-api-7F52FF?logo=kotlin&logoColor=white)](https://polskianonim.github.io/OctaviusDatabase/api)
[![KDoc Core](https://img.shields.io/badge/KDoc-core-7F52FF?logo=kotlin&logoColor=white)](https://polskianonim.github.io/OctaviusDatabase/core)

*It's not an ORM. It's a ROM (Relational-Object Mapper) — an Anti-ORM.*

</div>

---

## Philosophy

| Principle                   | Description                                                       |
|-----------------------------|-------------------------------------------------------------------|
| **Query is King**           | Your SQL query dictates the shape of data — not the framework.    |
| **Object is a Vessel**      | A `data class` is simply a type-safe container for query results. |
| **Explicitness over Magic** | No lazy-loading, no session management, no dirty checking.        |

## Features

- **Fluent Query Builders** — SELECT, INSERT, UPDATE, DELETE with a clean API
- **Automatic Type Mapping** — PostgreSQL `COMPOSITE`, `ENUM`, `ARRAY` ↔ Kotlin types
- **Transaction Plans** — Multi-step atomic operations with step dependencies
- **Dynamic Filters** — Safe, composable `WHERE` clauses with `QueryFragment`
- **Dynamic Type System** — Polymorphic storage & ad-hoc object mapping with `dynamic_dto`

## Quick Start

```kotlin
// Define your data class — it maps directly to query results
data class Book(val id: Int, val title: String, val author: String)

// Query with named parameters
val books = dataAccess.select("id", "title", "author")
    .from("books")
    .where("published_year > :year")
    .orderBy("title")
    .toListOf<Book>("year" to 2020)
```

## Query Builders

```kotlin
// SELECT with pagination
val users = dataAccess.select("id", "name", "email")
    .from("users")
    .where("active = true")
    .orderBy("created_at DESC")
    .limit(10)
    .offset(20)
    .toListOf<User>()

// INSERT with RETURNING
val newId = dataAccess.insertInto("users")
    .value("name")
    .value("email")
    .returning("id")
    .toField<Int>(mapOf("name" to "John", "email" to "john@example.com"))

// UPDATE with expressions
dataAccess.update("products")
    .setExpression("stock", "stock - 1")
    .where("id = :id")
    .execute("id" to productId)

// DELETE
dataAccess.deleteFrom("sessions")
    .where("expires_at < NOW()")
    .execute()
```

## Safe Dynamic Filters

Build complex `WHERE` clauses without SQL injection risks:

```kotlin
fun buildFilters(name: String?, minPrice: Int?, category: Category?): QueryFragment {
    val fragments = mutableListOf<QueryFragment>()

    name?.let { fragments += QueryFragment("name ILIKE :name", mapOf("name" to "%$it%")) }
    minPrice?.let { fragments += QueryFragment("price >= :minPrice", mapOf("minPrice" to it)) }
    category?.let { fragments += QueryFragment("category = :cat", mapOf("cat" to it)) }

    return fragments.join(" AND ")
}

val filter = buildFilters(name = "Pro", minPrice = 100, category = null)
val products = dataAccess.select("*")
    .from("products")
    .where(filter.sql)
    .toListOf<Product>(filter.params)
```

## Transaction Plans

Execute multi-step operations atomically with dependencies between steps:

```kotlin
val plan = TransactionPlan()

// Step 1: Create order, get handle to future ID
val orderIdHandle = plan.add(
    dataAccess.insertInto("orders")
        .values(listOf("user_id", "total"))
        .returning("id")
        .asStep()
        .toField<Int>(mapOf("user_id" to userId, "total" to total))
)

// Step 2: Create order items using the handle
for (item in cartItems) {
    val orderItem: Map<String, Any?> = mapOf(
        "order_id" to orderIdHandle.field(),  // Reference future value
        "product_id" to item.productId,
        "quantity" to item.quantity
    )
    
    plan.add(
        dataAccess.insertInto("order_items")
            .values(orderItem)
            .asStep()
            .execute(orderItem)
    )
}

// Execute all steps in single transaction
dataAccess.executeTransactionPlan(plan)
```

## Type Mapping

Automatic conversion between PostgreSQL and Kotlin types.

### Standard Types

| PostgreSQL                | Kotlin          | Notes                        |
|---------------------------|-----------------|------------------------------|
| `int2`, `smallserial`     | `Short`         |                              |
| `int4`, `serial`          | `Int`           |                              |
| `int8`, `bigserial`       | `Long`          |                              |
| `float4`                  | `Float`         |                              |
| `float8`                  | `Double`        |                              |
| `numeric`                 | `BigDecimal`    |                              |
| `text`, `varchar`, `char` | `String`        |                              |
| `bool`                    | `Boolean`       |                              |
| `uuid`                    | `UUID`          | `java.util.UUID`             |
| `bytea`                   | `ByteArray`     |                              |
| `json`, `jsonb`           | `JsonElement`   | `kotlinx.serialization.json` |
| `date`                    | `LocalDate`     | `kotlinx.datetime`           |
| `time`                    | `LocalTime`     | `kotlinx.datetime`           |
| `timetz`                  | `OffsetTime`    | `org.octavius.data`          |
| `timestamp`               | `LocalDateTime` | `kotlinx.datetime`           |
| `timestamptz`             | `Instant`       | `kotlin.time`                |
| `interval`                | `Duration`      | `kotlin.time`                |

Arrays of all standard types are supported and map to `List<T>`.

### Custom Types

```kotlin
// PostgreSQL COMPOSITE TYPE → Kotlin data class
@PgComposite
data class Address(val street: String, val city: String, val zipCode: String)

// PostgreSQL ENUM → Kotlin enum
@PgEnum
enum class OrderStatus { Pending, Processing, Shipped, Delivered }

// Works seamlessly in queries
data class Order(val id: Int, val status: OrderStatus, val shippingAddress: Address)

val orders = dataAccess.select("id", "status", "shipping_address")
    .from("orders")
    .toListOf<Order>()  // Types converted automatically
```

## Dynamic Type System

Octavius uses `dynamic_dto` — a PostgreSQL composite type combining a type discriminator with JSONB payload — to bridge static SQL and Kotlin's type system. This type is automatically initialized in your database on startup.

```sql
-- Created automatically by Octavius
CREATE TYPE dynamic_dto AS (
    type_name    TEXT,
    data_payload JSONB
);

-- Helper function for constructing values
CREATE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
RETURNS dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql;
```

### 1. Polymorphic Storage

Store different types in a single column or array. The framework deserializes each element to its correct Kotlin class based on `type_name`.

```kotlin
@DynamicallyMappable(typeName = "text_note")
@Serializable
data class TextNote(val content: String)

@DynamicallyMappable(typeName = "image_note")
@Serializable
data class ImageNote(val url: String, val caption: String?)

// Database: CREATE TABLE notebooks (id INT, notes dynamic_dto[]);

val notes: List<Any> = listOf(
    TextNote("Hello world"),
    ImageNote("https://example.com/img.png", "A photo")
)

dataAccess.insertInto("notebooks")
    .values(listOf("notes"))
    .execute("notes" to notes)

// Read back — each element deserialized to its correct type
val notebook = dataAccess.select("notes")
    .from("notebooks")
    .where("id = 1")
    .toField<List<Any>>()  // Returns [TextNote(...), ImageNote(...)]
```

### 2. Ad-hoc Object Mapping

Construct Kotlin objects directly in SQL using `jsonb_build_object` — no need to define PostgreSQL COMPOSITE types. Perfect for JOINs and projections where you want nested results without schema changes.

```kotlin
@DynamicallyMappable(typeName = "user_profile")
@Serializable
data class UserProfile(val role: String, val permissions: List<String>)

data class UserWithProfile(val id: Int, val name: String, val profile: UserProfile)

val users = dataAccess.rawQuery("""
    SELECT
        u.id,
        u.name,
        dynamic_dto(
            'user_profile',
            jsonb_build_object(
                'role', p.role,
                'permissions', p.permissions
            )
        ) AS profile
    FROM users u
    JOIN profiles p ON p.user_id = u.id
""").toListOf<UserWithProfile>()
```

> **Why use this?** Usually, to get a user with their profile in one query, you'd fetch flat columns (`user_id`, `user_name`, `profile_role`...) and manually map them, or create a database VIEW. With ad-hoc mapping, you construct the nested structure directly in SQL. The database does the packaging, Octavius does the unpacking — zero boilerplate.

## Configuration

### Using Properties File

Create a `database.properties` file in `src/main/resources`:

```properties
db.url=jdbc:postgresql://localhost:5432/mydb
db.username=postgres
db.password=secret
db.schemas=public,myschema
db.packagesToScan=com.myapp.domain,com.myapp.dto

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
        dbUrl = "jdbc:postgresql://localhost:5432/mydb",
        dbUsername = "user",
        dbPassword = "pass",
        dbSchemas = listOf("public"),
        packagesToScan = listOf("com.myapp.domain")
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
- [Query Builders](docs/query-builders.md) - CTEs, subqueries, ON CONFLICT, async, streaming
- [Transactions](docs/transactions.md) - Transaction plans, StepHandle, passing data between steps
- [Error Handling](docs/error-handling.md) - DataResult pattern, exception hierarchy
- [Type System](docs/type-system.md) - @PgEnum, @PgComposite, @DynamicallyMappable, helper serializers

## Architecture

| Module | Platform      | Description                                                   |
|--------|---------------|---------------------------------------------------------------|
| `api`  | Multiplatform | Public API, interfaces; annotations with no JVM dependencies. |
| `core` | JVM           | Implementation using Spring JDBC & HikariCP.                  |
