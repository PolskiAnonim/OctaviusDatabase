# Octavius Database

<div align="center">

**An un-opinionated, SQL-first data access layer for Kotlin & PostgreSQL**

[![KDoc API](https://img.shields.io/badge/KDoc-database--api-7F52FF?logo=kotlin&logoColor=white)](https://polskianonim.github.io/OctaviusFramework/database/api)
[![KDoc Core](https://img.shields.io/badge/KDoc-database--core-7F52FF?logo=kotlin&logoColor=white)](https://polskianonim.github.io/OctaviusFramework/database/core)

*It's not an ORM. It's an* ***Anti-ORM.***

</div>

---

## Philosophy

| Principle | Description |
|-----------|-------------|
| **Query is King** | Your SQL query dictates the shape of data — not the framework |
| **Object is a Vessel** | A `data class` is simply a type-safe container for query results |
| **Explicitness over Magic** | No lazy-loading, no session management, no dirty checking |

## Features

- **Fluent Query Builders** — SELECT, INSERT, UPDATE, DELETE with a clean API
- **Automatic Type Mapping** — PostgreSQL `COMPOSITE`, `ENUM`, `ARRAY` ↔ Kotlin types
- **Transaction Plans** — Multi-step atomic operations with step dependencies
- **Dynamic Filters** — Safe, composable `WHERE` clauses with `QueryFragment`
- **Polymorphic Storage** — Store different types in one column with `dynamic_dto`

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
    .values(mapOf("name" to "John", "email" to "john@example.com"))
    .returning("id")
    .toField<Int>()

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
        .values(mapOf("user_id" to userId, "total" to total))
        .returning("id")
        .asStep()
        .toField<Int>()
)

// Step 2: Create order items using the handle
for (item in cartItems) {
    plan.add(
        dataAccess.insertInto("order_items")
            .values(mapOf(
                "order_id" to orderIdHandle.field(),  // Reference future value
                "product_id" to item.productId,
                "quantity" to item.quantity
            ))
            .asStep()
            .execute()
    )
}

// Execute all steps in single transaction
dataAccess.executeTransactionPlan(plan)
```

## Type Mapping

Automatic conversion between PostgreSQL and Kotlin types:

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

## Polymorphic Storage

Store different object types in a single column with `dynamic_dto`:

```kotlin
@DynamicallyMappable(typeName = "feature_flag")
@Serializable
enum class FeatureFlag {
    @SerialName("dark_mode") DarkMode,
    @SerialName("beta_features") BetaFeatures
}

// Database: CREATE TABLE user_settings (id INT, flags dynamic_dto[]);

// Write — automatically serialized
dataAccess.update("user_settings")
    .setValue("flags")
    .where("id = :id")
    .execute("id" to 1, "flags" to listOf(FeatureFlag.DarkMode, FeatureFlag.BetaFeatures))

// Read — automatically deserialized
val flags = dataAccess.select("flags")
    .from("user_settings")
    .where("id = 1")
    .toField<List<FeatureFlag>>()
```

## Configuration

```kotlin
// From properties file
val dataAccess = OctaviusDatabase.fromConfig(
    DatabaseConfig.loadFromFile("database.properties")
)

// Direct configuration
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

## Architecture

| Module | Platform | Description |
|--------|----------|-------------|
| `database/api` | Multiplatform | Public API, interfaces, annotations — no JVM dependencies |
| `database/core` | JVM | Implementation using Spring JDBC & HikariCP |
