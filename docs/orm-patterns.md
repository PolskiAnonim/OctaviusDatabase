# ORM-Like Patterns

Octavius is an Anti-ORM by design, but it provides utilities that enable convenient ORM-like patterns when you want them. This guide shows practical patterns for CRUD operations using [Data Mapping](data-mapping.md) utilities.

## Table of Contents

- [Key Advantages](#key-advantages)
- [CRUD Patterns](#crud-patterns)
- [Real-World Example](#real-world-example)
- [When to Use These Patterns](#when-to-use-these-patterns)

> **Prerequisites**: This guide assumes familiarity with [Data Mapping](data-mapping.md) (`toMap()`, `toDataObject()`, `@MapKey`) and [Query Builders](query-builders.md) (auto-placeholders).

---

## Key Advantages

### Data Classes Can Have Anything

Unlike traditional ORMs that require specific base classes or annotations everywhere, Octavius works with **any data class**. Your entities can have:

- **PostgreSQL ENUMs** (`@PgEnum`) mapped automatically
- **Composite types** (`@PgComposite`) including nested structures
- **Default values** and nullable properties
- **Interfaces** and inheritance
- **Any kotlinx.serialization types** (JsonObject, etc.)

```kotlin
data class ReportConfiguration(
    val id: Int? = null,                          // Nullable with default
    val name: String,
    val reportName: String,
    val description: String? = null,
    val isDefault: Boolean = false,               // Default value
    val visibleColumns: List<String>,             // PostgreSQL ARRAY
    val columnOrder: List<String>,
    val sortOrder: List<SortConfiguration>,       // Array of COMPOSITE
    val pageSize: Long,
    val filters: List<FilterConfig>               // Complex nested types
)

@PgEnum
enum class SortDirection {
    Ascending,
    Descending
}

@PgComposite
data class SortConfiguration(
    val columnName: String,
    val sortDirection: SortDirection              // Enum inside composite
)

@PgComposite
data class FilterConfig(
    val columnName: String,
    val config: JsonObject                        // JSONB inside composite
)
```

**Enums can implement interfaces:**

```kotlin
@PgEnum
enum class GameStatus : EnumWithFormatter<GameStatus> {
    NotPlaying,
    WithoutTheEnd,
    Played,
    ToPlay,
    Playing;

    override fun toDisplayString(): String {
        return when (this) {
            NotPlaying -> T.get("games.status.notPlaying")
            WithoutTheEnd -> T.get("games.status.endless")
            Played -> T.get("games.status.played")
            ToPlay -> T.get("games.status.toPlay")
            Playing -> T.get("games.status.playing")
        }
    }
}

// Works the same - enum values are mapped to PostgreSQL ENUM
data class Game(
    val id: Int,
    val title: String,
    val status: GameStatus  // Interface methods available on the enum
)
```

The same applies to data classes - they can implement interfaces, have companion objects, additional methods, etc. Only the **primary constructor parameters** are used for mapping.

### Same Map for Definition and Execution

The pattern `values(map)` + `execute(map)` uses the **same map** for both:
1. Generating column names and placeholders
2. Providing actual values

```kotlin
val data = entity.toMap("id")
dataAccess.insertInto("table")
    .values(data)      // Defines: (col1, col2) VALUES (:col1, :col2)
    .execute(data)     // Provides: col1 -> value1, col2 -> value2
```

### PostgreSQL Table = Automatic Composite Type

PostgreSQL automatically creates a composite type with the same name as each table. This enables powerful patterns - you can select entire rows as nested objects:

```kotlin
// Map Kotlin classes to table composite types using table name
@PgComposite(name = "comments")
data class Comment(
    val id: Int,
    val postId: Int,
    val content: String,
    val author: String,
    val post: Post? = null  // Optional reference to parent
)

@PgComposite(name = "posts")
data class Post(
    val id: Int,
    val title: String,
    val content: String,
    val comments: List<Comment> = emptyList()  // Will hold nested comments
)

// Fetch posts with all their comments as nested arrays
dataAccess.select(
    "p.*",
    "ARRAY(SELECT c FROM comments c WHERE c.post_id = p.id) AS comments"
).from("posts p")
 .limit(10)
 .toListOf<Post>()
```

**Different query patterns:**

```kotlin
// Just post data - comments will be empty list (default value)
dataAccess.select("p.*")
    .from("posts p")
    .where("id = :id")
    .toSingleOf<Post>("id" to 1)

// Comment with its parent post as nested composite
dataAccess.select("p", "c.*")
    .from("comments c JOIN posts p ON c.post_id = p.id")
    .where("c.id = :id")
    .toSingleOf<Comment>("id" to 1)

// Just comment data - post will be null
dataAccess.select("c.*")
    .from("comments c")
    .where("c.id = :id")
    .toSingleOf<Comment>("id" to 1)
```

**Key points:**
- `@PgComposite(name = "table_name")` maps class to table's automatic composite type
- `SELECT c FROM table c` returns the whole row as a composite
- `SELECT alias` (without `.*`) returns the row as a composite type
- `ARRAY(SELECT ...)` aggregates rows into a PostgreSQL array
- Octavius deserializes composite arrays back to `List<T>`
- **When sending to PostgreSQL:** fields in your Kotlin class that don't exist in the PostgreSQL composite type are ignored (useful for computed/UI-only fields)

---

## Real-World Example

A complete configuration manager showing the full power of these patterns:

```kotlin
class ReportConfigurationManager(private val dataAccess: DataAccess) {

    fun saveConfiguration(configuration: ReportConfiguration): Boolean {
        // One line: convert to map, excluding auto-generated ID
        val flatValueMap = configuration.toMap("id")

        val result = dataAccess.insertInto("report_configurations")
            .values(flatValueMap)  // Same map defines columns AND provides values
            .onConflict {
                onColumns("name", "report_name")
                doUpdate(
                    "description" to "EXCLUDED.description",
                    "sort_order" to "EXCLUDED.sort_order",
                    "visible_columns" to "EXCLUDED.visible_columns",
                    "column_order" to "EXCLUDED.column_order",
                    "page_size" to "EXCLUDED.page_size",
                    "is_default" to "EXCLUDED.is_default",
                    "filters" to "EXCLUDED.filters"
                )
            }
            .execute(flatValueMap)  // Same map passed to execute

        return result is DataResult.Success
    }

    fun loadDefaultConfiguration(reportName: String): ReportConfiguration? {
        return dataAccess.select("*")
            .from("report_configurations")
            .where("report_name = :report_name AND is_default = true")
            .toSingleOf<ReportConfiguration>("report_name" to reportName)
            .getOrNull()
    }

    fun listConfigurations(reportName: String): List<ReportConfiguration> {
        return dataAccess.select("*")
            .from("report_configurations")
            .where("report_name = :report_name")
            .orderBy("is_default DESC, name ASC")
            .toListOf<ReportConfiguration>("report_name" to reportName)
            .getOrDefault(emptyList())
    }

    fun deleteConfiguration(name: String, reportName: String): Boolean {
        return dataAccess.deleteFrom("report_configurations")
            .where("name = :name AND report_name = :report_name")
            .execute("name" to name, "report_name" to reportName)
            .map { it > 0 }
            .getOrDefault(false)
    }
}
```

**What's happening here:**
- `ReportConfiguration` has 9 fields including arrays, composites, enums, and JSONB
- `toMap("id")` converts it all to a flat map, excluding the ID
- `values(flatValueMap)` generates: `(name, report_name, description, ...) VALUES (:name, :report_name, :description, ...)`
- `execute(flatValueMap)` provides all values with automatic type conversion
- `toSingleOf<ReportConfiguration>()` deserializes back including all nested types

**No boilerplate. No field mapping. No session management. Just SQL.**

---

## CRUD Patterns

### Full CRUD Example

```kotlin
data class Product(
    val id: Int? = null,
    val sku: String,
    val name: String,
    val price: BigDecimal,
    val stock: Int,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

class ProductRepository(private val dataAccess: DataAccess) {

    fun create(product: Product): DataResult<Product> {
        val data = product.toMap("id", "created_at", "updated_at")

        return dataAccess.insertInto("products")
            .values(data)
            .valueExpression("created_at", "NOW()")
            .returning("*")
            .toSingleOf<Product>(data)
    }

    fun findById(id: Int): DataResult<Product?> {
        return dataAccess.select("*")
            .from("products")
            .where("id = :id")
            .toSingleOf<Product>("id" to id)
    }

    fun update(product: Product): DataResult<Product?> {
        val data = product.toMap("id", "created_at", "updated_at")

        return dataAccess.update("products")
            .setValues(data)
            .setExpression("updated_at", "NOW()")
            .where("id = :id")
            .returning("*")
            .toSingleOf<Product>(data + ("id" to product.id))
    }

    fun delete(id: Int): DataResult<Int> {
        return dataAccess.deleteFrom("products")
            .where("id = :id")
            .execute("id" to id)
    }

    fun findAll(page: Long = 0, size: Long = 20): DataResult<List<Product>> {
        return dataAccess.select("*")
            .from("products")
            .orderBy("created_at DESC")
            .page(page, size)
            .toListOf<Product>()
    }
}
```

### Upsert Pattern

```kotlin
fun upsertProduct(product: Product): DataResult<Product?> {
    val data = product.toMap("id", "created_at", "updated_at")

    return dataAccess.insertInto("products")
        .values(data)
        .valueExpression("created_at", "NOW()")
        .onConflict {
            onColumns("sku")
            doUpdate(
                "name" to "EXCLUDED.name",
                "price" to "EXCLUDED.price",
                "stock" to "EXCLUDED.stock",
                "updated_at" to "NOW()"
            )
        }
        .returning("*")
        .toSingleOf<Product>(data)
}
```

---

## When to Use These Patterns

**Use ORM-like patterns when:**
- Mapping straightforward CRUD operations
- Working with flat data structures
- Reducing boilerplate for common operations

**Use explicit SQL when:**
- Queries involve JOINs, CTEs, or complex logic
- You need precise control over column selection
- Performance optimization requires specific SQL constructs
- Business logic is expressed in SQL (calculated fields, aggregations)

The Anti-ORM philosophy means you always **can** drop down to raw SQL - these patterns are conveniences, not constraints.
