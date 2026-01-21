# Query Builders

Octavius Database provides fluent query builders for all CRUD operations. Each builder supports:
- **CTE (Common Table Expressions)** via `with()` and `recursive()`
- **Named parameters** using `:param` syntax
- **Multiple terminal methods** for different result types
- **Async execution** via `async()`
- **Streaming** for large datasets via `asStream()`
- **Transaction steps** via `asStep()`

## Table of Contents

- [Terminal Methods](#terminal-methods)
- [SelectQueryBuilder](#selectquerybuilder)
- [InsertQueryBuilder](#insertquerybuilder)
- [UpdateQueryBuilder](#updatequerybuilder)
- [DeleteQueryBuilder](#deletequerybuilder)
- [RawQueryBuilder](#rawquerybuilder)
- [Common Table Expressions (CTE)](#common-table-expressions-cte)
- [Subqueries](#subqueries)
- [ON CONFLICT (Upsert)](#on-conflict-upsert)
- [Async Execution](#async-execution)
- [Streaming](#streaming)
- [Builder Modes](#builder-modes)

---

## Terminal Methods

All query builders share common terminal methods that execute the query and return results.

### Returning Methods (`TerminalReturningMethods`)

| Method | Returns | Description |
|--------|---------|-------------|
| `toList(params)` | `DataResult<List<Map<String, Any?>>>` | All rows as list of maps |
| `toSingle(params)` | `DataResult<Map<String, Any?>?>` | First row as map (or null) |
| `toListOf<T>(params)` | `DataResult<List<T>>` | All rows mapped to data class |
| `toSingleOf<T>(params)` | `DataResult<T?>` | First row mapped to data class |
| `toField<T>(params)` | `DataResult<T?>` | Single value from first column/row |
| `toColumn<T>(params)` | `DataResult<List<T?>>` | All values from first column |
| `toSql()` | `String` | Generated SQL (no execution) |

### Modification Methods (`TerminalModificationMethods`)

| Method | Returns | Description |
|--------|---------|-------------|
| `execute(params)` | `DataResult<Int>` | Affected row count |

### Parameter Passing

Parameters can be passed as `Map` or `vararg Pair`:

```kotlin
// Using Map
dataAccess.select("*").from("users")
    .where("id = :id")
    .toSingleOf<User>(mapOf("id" to 123))

// Using vararg (more concise)
dataAccess.select("*").from("users")
    .where("id = :id")
    .toSingleOf<User>("id" to 123)
```

---

## SelectQueryBuilder

Builds SQL SELECT queries with full support for all standard clauses.

### Methods

| Method | Description |
|--------|-------------|
| `from(source)` | FROM clause - table name, alias, or JOIN expression |
| `fromSubquery(subquery, alias)` | FROM with a subquery (auto-wrapped in parentheses) |
| `where(condition)` | WHERE clause (nullable - pass null to skip) |
| `groupBy(columns)` | GROUP BY clause |
| `having(condition)` | HAVING clause (requires GROUP BY) |
| `orderBy(ordering)` | ORDER BY clause |
| `limit(count)` | LIMIT clause |
| `offset(position)` | OFFSET clause |
| `page(page, size)` | Pagination helper (zero-indexed pages) |

### Examples

```kotlin
// Basic SELECT
val users = dataAccess.select("id", "name", "email")
    .from("users")
    .where("active = true")
    .orderBy("created_at DESC")
    .toListOf<User>()

// With pagination
val page = dataAccess.select("*")
    .from("products")
    .where("category = :category")
    .orderBy("price ASC")
    .page(page = 0, size = 20)
    .toListOf<Product>("category" to "electronics")

// With GROUP BY and HAVING
val stats = dataAccess.select("category", "COUNT(*) as count", "AVG(price) as avg_price")
    .from("products")
    .groupBy("category")
    .having("COUNT(*) > 5")
    .toListOf<CategoryStats>()

// With JOINs (in FROM clause)
val ordersWithUsers = dataAccess.select("o.id", "o.total", "u.name as user_name")
    .from("orders o JOIN users u ON o.user_id = u.id")
    .where("o.status = :status")
    .toListOf<OrderWithUser>("status" to "completed")
```

---

## InsertQueryBuilder

Builds SQL INSERT queries with support for values, expressions, SELECT source, and ON CONFLICT.

### Methods

| Method | Description |
|--------|-------------|
| `value(column)` | Add column with auto-generated `:column` placeholder |
| `values(columns: List)` | Add multiple columns with auto placeholders |
| `values(data: Map)` | Add columns from map keys with auto placeholders |
| `valueExpression(column, expr)` | Add column with custom SQL expression |
| `valuesExpressions(map)` | Add multiple columns with custom expressions |
| `fromSelect(query)` | INSERT ... SELECT (requires columns in `insertInto`) |
| `onConflict { }` | Configure ON CONFLICT clause (upsert) |
| `returning(columns)` | Add RETURNING clause |

### Examples

```kotlin
// Basic INSERT
dataAccess.insertInto("users")
    .values(listOf("name", "email", "created_at"))
    .execute(mapOf(
        "name" to "John",
        "email" to "john@example.com",
        "created_at" to Clock.System.now()
    ))

// INSERT with RETURNING
val newId = dataAccess.insertInto("users")
    .value("name")
    .value("email")
    .returning("id")
    .toField<Int>("name" to "John", "email" to "john@example.com")

// Using expressions (e.g., NOW(), DEFAULT)
dataAccess.insertInto("audit_log")
    .valueExpression("action", ":action")
    .valueExpression("timestamp", "NOW()")
    .valueExpression("user_id", "COALESCE(:user_id, 0)")
    .execute("action" to "login", "user_id" to userId)

// INSERT from SELECT
dataAccess.insertInto("archive_orders", listOf("id", "total", "archived_at"))
    .fromSelect("""
        SELECT id, total, NOW()
        FROM orders
        WHERE created_at < :cutoff
    """)
    .execute("cutoff" to cutoffDate)
```

---

## UpdateQueryBuilder

Builds SQL UPDATE queries. **WHERE clause is mandatory** for safety.

### Methods

| Method | Description |
|--------|-------------|
| `setValue(column)` | SET column with auto `:column` placeholder |
| `setValues(columns: List)` | SET multiple columns with auto placeholders |
| `setValues(data: Map)` | SET columns from map keys |
| `setExpression(column, expr)` | SET column with custom SQL expression |
| `setExpressions(map)` | SET multiple columns with custom expressions |
| `from(tables)` | FROM clause for UPDATE ... FROM |
| `where(condition)` | WHERE clause (**mandatory**) |
| `returning(columns)` | Add RETURNING clause |

### Examples

```kotlin
// Basic UPDATE
dataAccess.update("users")
    .setValues(listOf("name", "email"))
    .where("id = :id")
    .execute(mapOf("name" to "Jane", "email" to "jane@example.com", "id" to 123))

// UPDATE with expression
dataAccess.update("products")
    .setExpression("stock", "stock - :quantity")
    .setExpression("updated_at", "NOW()")
    .where("id = :id")
    .execute("quantity" to 5, "id" to productId)

// UPDATE with FROM (for JOINs)
dataAccess.update("orders")
    .setExpression("status", ":newStatus")
    .from("users u")
    .where("orders.user_id = u.id AND u.banned = true")
    .execute("newStatus" to "cancelled")

// UPDATE with RETURNING
val updated = dataAccess.update("users")
    .setValue("last_login")
    .where("id = :id")
    .returning("id", "name", "last_login")
    .toSingleOf<User>("last_login" to now, "id" to userId)
```

---

## DeleteQueryBuilder

Builds SQL DELETE queries. **WHERE clause is mandatory** for safety.

### Methods

| Method | Description |
|--------|-------------|
| `using(tables)` | USING clause for DELETE with JOINs |
| `where(condition)` | WHERE clause (**mandatory**) |
| `returning(columns)` | Add RETURNING clause |

### Examples

```kotlin
// Basic DELETE
dataAccess.deleteFrom("sessions")
    .where("expires_at < NOW()")
    .execute()

// DELETE with USING (JOIN-like)
dataAccess.deleteFrom("order_items")
    .using("orders o")
    .where("order_items.order_id = o.id AND o.status = :status")
    .execute("status" to "cancelled")

// DELETE with RETURNING
val deleted = dataAccess.deleteFrom("users")
    .where("id = :id")
    .returning("id", "email")
    .toSingleOf<DeletedUser>("id" to userId)
```

---

## RawQueryBuilder

Executes arbitrary SQL queries. Use when the fluent builders don't cover your use case.

### Usage

```kotlin
// Complex query with raw SQL
val results = dataAccess.rawQuery("""
    SELECT
        u.id,
        u.name,
        COUNT(o.id) as order_count,
        COALESCE(SUM(o.total), 0) as total_spent
    FROM users u
    LEFT JOIN orders o ON o.user_id = u.id
    WHERE u.created_at > :since
    GROUP BY u.id, u.name
    HAVING COUNT(o.id) > :minOrders
    ORDER BY total_spent DESC
""").toListOf<UserStats>("since" to startDate, "minOrders" to 5)

// Raw INSERT/UPDATE/DELETE
val affected = dataAccess.rawQuery("""
    UPDATE products
    SET price = price * 1.1
    WHERE category = :cat AND price < :maxPrice
""").execute("cat" to "electronics", "maxPrice" to 100)

// Using dynamic_dto for ad-hoc mapping
val usersWithProfiles = dataAccess.rawQuery("""
    SELECT
        u.id,
        u.name,
        dynamic_dto(
            'profile',
            jsonb_build_object('role', p.role, 'permissions', p.permissions)
        ) AS profile
    FROM users u
    JOIN profiles p ON p.user_id = u.id
""").toListOf<UserWithProfile>()
```

---

## Common Table Expressions (CTE)

All builders support CTEs via `with()` and `recursive()`.

### Basic CTE

```kotlin
val activeUsers = dataAccess.select("*")
    .with("active_users", "SELECT * FROM users WHERE active = true")
    .from("active_users")
    .where("last_login > :since")
    .toListOf<User>("since" to lastWeek)
```

### Multiple CTEs

```kotlin
val report = dataAccess.select("*")
    .with("recent_orders", "SELECT * FROM orders WHERE created_at > :since")
    .with("order_totals", "SELECT user_id, SUM(total) as total FROM recent_orders GROUP BY user_id")
    .from("users u JOIN order_totals ot ON u.id = ot.user_id")
    .orderBy("ot.total DESC")
    .toListOf<UserOrderReport>("since" to lastMonth)
```

### Recursive CTE

```kotlin
// Tree traversal (e.g., category hierarchy)
val hierarchy = dataAccess.select("*")
    .with("category_tree", """
        SELECT id, name, parent_id, 1 as depth
        FROM categories
        WHERE id = :rootId
        UNION ALL
        SELECT c.id, c.name, c.parent_id, ct.depth + 1
        FROM categories c
        JOIN category_tree ct ON c.parent_id = ct.id
    """)
    .recursive()
    .from("category_tree")
    .orderBy("depth, name")
    .toListOf<CategoryNode>("rootId" to rootCategoryId)
```

### CTE with INSERT

```kotlin
val archivedCount = dataAccess.insertInto("archive", listOf("id", "data", "archived_at"))
    .with("to_archive", "SELECT id, data FROM records WHERE status = 'completed'")
    .fromSelect("SELECT id, data, NOW() FROM to_archive")
    .execute()
```

---

## Subqueries

### In FROM clause

```kotlin
val stats = dataAccess.select("category", "avg_price")
    .fromSubquery("""
        SELECT category, AVG(price) as avg_price
        FROM products
        GROUP BY category
    """, alias = "category_stats")
    .where("avg_price > :minAvg")
    .toListOf<CategoryStats>("minAvg" to 50.0)
```

### In WHERE clause (as part of condition string)

```kotlin
val users = dataAccess.select("*")
    .from("users")
    .where("id IN (SELECT user_id FROM orders WHERE total > :minTotal)")
    .toListOf<User>("minTotal" to 1000)
```

---

## ON CONFLICT (Upsert)

The `onConflict` builder allows configuring PostgreSQL's ON CONFLICT clause for upsert operations.

### Configuration Methods

| Method | Description |
|--------|-------------|
| `onColumns(columns)` | Conflict target: columns |
| `onConstraint(name)` | Conflict target: constraint name |
| `doNothing()` | ON CONFLICT DO NOTHING |
| `doUpdate(setExpression, where?)` | DO UPDATE SET with raw expression |
| `doUpdate(vararg pairs, where?)` | DO UPDATE SET with column-value pairs |
| `doUpdate(map, where?)` | DO UPDATE SET from map |

### Examples

```kotlin
// DO NOTHING on conflict
dataAccess.insertInto("users")
    .values(listOf("email", "name"))
    .onConflict {
        onColumns("email")
        doNothing()
    }
    .execute("email" to "john@example.com", "name" to "John")

// DO UPDATE (upsert)
dataAccess.insertInto("user_stats")
    .values(listOf("user_id", "login_count", "last_login"))
    .onConflict {
        onColumns("user_id")
        doUpdate(
            "login_count" to "user_stats.login_count + 1",
            "last_login" to "EXCLUDED.last_login"
        )
    }
    .execute(
        "user_id" to userId,
        "login_count" to 1,
        "last_login" to now
    )

// Using EXCLUDED to reference attempted insert values
dataAccess.insertInto("products")
    .values(listOf("sku", "name", "price", "stock"))
    .onConflict {
        onColumns("sku")
        doUpdate(
            "price" to "EXCLUDED.price",
            "stock" to "products.stock + EXCLUDED.stock",
            whereCondition = "products.price != EXCLUDED.price OR products.stock != EXCLUDED.stock"
        )
    }
    .execute(productData)

// Using constraint name
dataAccess.insertInto("orders")
    .values(orderData)
    .onConflict {
        onConstraint("orders_external_id_key")
        doNothing()
    }
    .execute(orderData)
```

---

## Async Execution

Execute queries asynchronously using coroutines.

### Usage

```kotlin
// Requires a CoroutineScope (e.g., viewModelScope)
val job = dataAccess.select("*")
    .from("users")
    .where("active = true")
    .async(viewModelScope)
    .toListOf<User> { result ->
        result.onSuccess { users ->
            // Handle success on the calling scope
            updateUI(users)
        }.onFailure { error ->
            // Handle error
            showError(error)
        }
    }

// Cancel if needed
job.cancel()
```

### Available Async Methods

All terminal methods have async counterparts accepting callbacks:

```kotlin
interface AsyncTerminalMethods {
    fun toList(params, onResult: (DataResult<List<Map<String, Any?>>>) -> Unit): Job
    fun toSingle(params, onResult: (DataResult<Map<String, Any?>?>) -> Unit): Job
    fun <T> toListOf(kClass, params, onResult: (DataResult<List<T>>) -> Unit): Job
    fun <T> toSingleOf(kClass, params, onResult: (DataResult<T?>) -> Unit): Job
    fun <T> toField(kType, params, onResult: (DataResult<T?>) -> Unit): Job
    fun <T> toColumn(kType, params, onResult: (DataResult<List<T?>>) -> Unit): Job
    fun execute(params, onResult: (DataResult<Int>) -> Unit): Job
}
```

### Custom Dispatcher

```kotlin
dataAccess.select("*")
    .from("users")
    .async(scope, ioDispatcher = Dispatchers.Default)  // Use different dispatcher
    .toListOf<User> { /* ... */ }
```

---

## Streaming

Process large datasets without loading everything into memory.

### Important

> **REQUIRES ACTIVE TRANSACTION.** Streaming must be called inside a `dataAccess.transaction { }` block. Otherwise, PostgreSQL ignores `fetchSize` and loads everything into RAM.

### Usage

Both `forEachRow` and `forEachRowOf` return `DataResult<Unit>` and accept an optional `params` parameter (defaults to `emptyMap()`).

```kotlin
dataAccess.transaction {
    val result = dataAccess.select("*")
        .from("large_table")
        .where("created_at > :since")
        .asStream(fetchSize = 500)  // Fetch 500 rows at a time
        .forEachRow("since" to startDate) { row: Map<String, Any?> ->
            // Process each row individually
            processRow(row)
        }

    // Handle potential errors
    result.onFailure { error ->
        logger.error("Streaming failed: ${error.message}")
    }
}

// With data class mapping (params defaults to emptyMap())
dataAccess.transaction {
    dataAccess.select("*")
        .from("audit_log")
        .asStream(fetchSize = 1000)
        .forEachRowOf<AuditEntry> { entry ->  // No params needed
            archiveEntry(entry)
        }
        .onFailure { error ->
            logger.error("Archive failed: ${error.message}")
        }
}
```

---

## Builder Modes

Each builder can be converted to different execution modes.

### `.asStep()` - Transaction Steps

Convert to a `TransactionStep` for use in `TransactionPlan`:

```kotlin
val plan = TransactionPlan()

val orderIdHandle = plan.add(
    dataAccess.insertInto("orders")
        .values(listOf("user_id", "total"))
        .returning("id")
        .asStep()
        .toField<Int>("user_id" to userId, "total" to total)
)

// Use handle in subsequent steps...
```

See [transactions.md](transactions.md) for full documentation.

### `.async()` - Async Execution

See [Async Execution](#async-execution) above.

### `.asStream()` - Streaming

See [Streaming](#streaming) above.

### `.copy()` - Clone Builder

Create a deep copy of the builder for creating variants:

```kotlin
val baseQuery = dataAccess.select("id", "name")
    .from("users")
    .orderBy("name")

// Create variants without modifying the original
val activeUsers = baseQuery.copy().where("active = true").toListOf<User>()
val inactiveUsers = baseQuery.copy().where("active = false").toListOf<User>()
```