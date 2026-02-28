# Executing Queries

Once you've built a query using the [Query Builders](query-builders.md), you need to execute it and handle the results. This guide covers terminal methods, the `DataResult` pattern, async execution, and streaming.

## Table of Contents

- [Terminal Methods](#terminal-methods)
- [DataResult](#dataresult)
- [Working with DataResult](#working-with-dataresult)
- [Async Execution](#async-execution)
- [Streaming](#streaming)

---

## Terminal Methods

All query builders share common terminal methods that execute the query and return results.

### Returning Methods (`TerminalReturningMethods`)

| Method                     | Returns                               | Description                             |
|----------------------------|---------------------------------------|-----------------------------------------|
| `toList(params)`           | `DataResult<List<Map<String, Any?>>>` | All rows as list of maps                |
| `toSingle(params)`         | `DataResult<Map<String, Any?>?>`      | Single row as map (or null)             |
| `toSingleStrict(params)`   | `DataResult<Map<String, Any?>>`       | Single row as map (Failure if no rows)  |
| `toListOf<T>(params)`      | `DataResult<List<T>>`                 | All rows mapped to data class           |
| `toSingleOf<T>(params)`    | `DataResult<T>`                       | Single row mapped to data class         |
| `toField<T>(params)`       | `DataResult<T>`                       | Single value from first column/row      |
| `toFieldStrict<T>(params)` | `DataResult<T>`                       | Single value, always Failure if no rows |
| `toColumn<T>(params)`      | `DataResult<List<T>>`                 | All values from first column            |
| `toSql()`                  | `String`                              | Generated SQL (no execution)            |

Nullability is controlled by the type parameter `T`. Use nullable types when null results are expected:

```kotlin
// Non-nullable — returns Failure if no rows or null value
val id: DataResult<Int> = query.toField<Int>()

// Nullable — returns Success(null) if no rows or null value
val id: DataResult<Int?> = query.toField<Int?>()

// Strict — always Failure if no rows, null value controlled by type
val id: DataResult<Int> = query.toFieldStrict<Int>()      // Failure if no rows OR null value
val id: DataResult<Int?> = query.toFieldStrict<Int?>()     // Failure if no rows, Success(null) if null value
```

> **Single-row guard**: All single-row methods (`toSingle`, `toSingleStrict`, `toSingleOf`, `toField`, `toFieldStrict`) return `Failure(TOO_MANY_ROWS)` if the query returns more than one row. Use `toList`/`toColumn` for multi-row results, or add `LIMIT 1` to your query.

### Modification Methods (`TerminalModificationMethods`)

| Method            | Returns           | Description        |
|-------------------|-------------------|--------------------|
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

## DataResult

All database operations return `DataResult<T>` - a sealed class that forces explicit handling of both success and failure cases.

```kotlin
sealed class DataResult<out T> {
    data class Success<out T>(val value: T) : DataResult<T>()
    data class Failure(val error: DatabaseException) : DataResult<Nothing>()
}
```

### Why DataResult?

- **Explicit error handling** - Compiler forces you to handle failures
- **No surprise exceptions** - Database errors don't crash your app unexpectedly
- **Chainable operations** - Functional-style transformations and callbacks
- **Type safety** - Errors are always `DatabaseException` subtypes

---

## Working with DataResult

### Extension Functions

| Function        | Description                                      |
|-----------------|--------------------------------------------------|
| `map { }`       | Transform success value, leave failure unchanged |
| `onSuccess { }` | Execute action on success, return same result    |
| `onFailure { }` | Execute action on failure, return same result    |
| `getOrElse { }` | Get value or compute default from error          |
| `getOrThrow()`  | Get value or throw exception                     |

### Basic Usage

```kotlin
val result: DataResult<List<User>> = dataAccess.select("*")
    .from("users")
    .toListOf<User>()

// Pattern 1: Callbacks
result
    .onSuccess { users -> println("Found ${users.size} users") }
    .onFailure { error -> println("Error: ${error.message}") }

// Pattern 2: Transform
val names: DataResult<List<String>> = result.map { users ->
    users.map { it.name }
}

// Pattern 3: Get with default
val users: List<User> = result.getOrElse { emptyList() }

// Pattern 4: Get or throw (careful!)
val users: List<User> = result.getOrThrow()  // Throws on failure
```

### Null Handling via Type Parameter

Nullability is determined by the type parameter you pass:

```kotlin
// Non-nullable — Failure if user not found (0 rows)
val user: DataResult<User> = dataAccess.select("*")
    .from("users")
    .where("id = :id")
    .toSingleOf<User>("id" to userId)

// Nullable — Success(null) if user not found
val maybeUser: DataResult<User?> = dataAccess.select("*")
    .from("users")
    .where("id = :id")
    .toSingleOf<User?>("id" to userId)

// Common pattern: non-nullable + getOrThrow
val user: User = dataAccess.select("*")
    .from("users")
    .where("id = :id")
    .toSingleOf<User>("id" to userId)
    .getOrThrow()  // Guaranteed non-null

// For untyped map results, use toSingleStrict
val row: DataResult<Map<String, Any?>> = dataAccess.select("*")
    .from("users")
    .where("id = :id")
    .toSingleStrict("id" to userId)  // Failure if no rows
```

### Terminal Method Behavior Matrix

All failures are `DataResult.Failure(QueryExecutionException)` with `ConversionException` as cause.

| Method               | 0 rows                                       | non-null value   | null value, non-null `T` | null value, nullable `T?` | >1 rows          |
|----------------------|----------------------------------------------|------------------|--------------------------|---------------------------|------------------|
| `toField<T>()`       | `T` → `EMPTY_RESULT`, `T?` → `Success(null)` | `Success(value)` | `UNEXPECTED_NULL_VALUE`  | `Success(null)`           | `TOO_MANY_ROWS`  |
| `toFieldStrict<T>()` | always `EMPTY_RESULT`                        | `Success(value)` | `UNEXPECTED_NULL_VALUE`  | `Success(null)`           | `TOO_MANY_ROWS`  |
| `toSingleOf<T>()`    | `T` → `EMPTY_RESULT`, `T?` → `Success(null)` | `Success(obj)`   | —                        | —                         | `TOO_MANY_ROWS`  |
| `toSingle()`         | `Success(null)`                              | `Success(map)`   | —                        | —                         | `TOO_MANY_ROWS`  |
| `toSingleStrict()`   | `EMPTY_RESULT`                               | `Success(map)`   | —                        | —                         | `TOO_MANY_ROWS`  |
| `toColumn<T>()`      | `Success([])`                                | `Success([...])` | `UNEXPECTED_NULL_VALUE`* | `Success([..., null])`    | `Success([...])` |
| `toListOf<T>()`      | `Success([])`                                | `Success([...])` | —                        | —                         | `Success([...])` |
| `toList()`           | `Success([])`                                | `Success([...])` | —                        | —                         | `Success([...])` |

*`toColumn<T>()` checks **every** element — a single null in any row fails the entire call with `UNEXPECTED_NULL_VALUE`.

Key patterns:
- **Regular** (`toField`, `toSingleOf`, `toSingle`) — empty result follows nullability of `T`
- **Strict** (`toFieldStrict`, `toSingleStrict`) — empty result is always `Failure`, regardless of `T`
- **Multi-row** (`toList`, `toListOf`, `toColumn`) — empty result is always `Success(emptyList())`
- **Single-row guard** — all single-row methods fail with `TOO_MANY_ROWS` if query returns >1 row

### In Transactions

```kotlin
val result = dataAccess.transaction { tx ->
    // Option 1: Early return on error
    val user = tx.select("*")
        .from("users")
        .where("id = :id")
        .toSingleOf<User>("id" to userId)
        .getOrElse { error ->
            return@transaction DataResult.Failure(error)
        }

    // Option 2: Check and handle
    val insertResult = tx.insertInto("logs")
        .values(logData)
        .execute(logData)

    if (insertResult is DataResult.Failure) {
        return@transaction insertResult
    }

    DataResult.Success(user)
}
```

### Chaining Multiple Operations

```kotlin
fun getOrderWithItems(orderId: Int): DataResult<OrderWithItems> {
    val orderResult = dataAccess.select("*")
        .from("orders")
        .where("id = :id")
        .toSingleOf<Order>("id" to orderId)

    return orderResult.map { order ->
        val items = dataAccess.select("*")
            .from("order_items")
            .where("order_id = :orderId")
            .toListOf<OrderItem>("orderId" to orderId)
            .getOrElse { return DataResult.Failure(it) }

        OrderWithItems(order, items)
    }
}
```

### Common Patterns

#### getOrThrow() with Global Exception Handler

When using Spring or other frameworks with global exception handling:

```kotlin
@Service
class UserService(private val dataAccess: DataAccess) {

    fun getUser(id: Int): User {
        return dataAccess.select("*")
            .from("users")
            .where("id = :id")
            .toSingleOf<User>("id" to id)
            .getOrThrow()  // Let global handler deal with errors
    }
}

// Spring global exception handler catches DatabaseException
@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(DatabaseException::class)
    fun handleDatabaseError(e: DatabaseException): ResponseEntity<*> {
        logger.error(e.toString())
        return ResponseEntity.status(500).body(ErrorResponse("Database error"))
    }
}
```

#### getOrElse() for Defaults

```kotlin
// Empty list on error
val users = dataAccess.select("*")
    .from("users")
    .toListOf<User>()
    .getOrElse { emptyList() }

// Null on error
val user = dataAccess.select("*")
    .from("users")
    .where("id = :id")
    .toSingleOf<User>("id" to userId)
    .getOrElse { null }
```

---

## Calling Void-Returning Functions

PostgreSQL functions that return `void` (e.g. `pg_notify`, `pg_advisory_lock`, custom procedures) map to `Unit` in Kotlin. Use `toField<Unit>()` to call them through the query builders:

```kotlin
// Call a void function — result is DataResult<Unit>
dataAccess.rawQuery("SELECT pg_notify(:channel, :payload)")
    .toField<Unit>("channel" to "orders", "payload" to "order_99")

// Also works inside transactions
dataAccess.transaction { tx ->
    tx.rawQuery("SELECT pg_advisory_lock(:key)")
        .toField<Unit>("key" to lockKey)
        .getOrElse { return@transaction DataResult.Failure(it) }

    // ... do work ...
    DataResult.Success(Unit)
}
```

`void` columns are mapped to `Unit` at the JDBC boundary — before the type registry is consulted — so no additional configuration is needed.

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
    fun toSingleStrict(params, onResult: (DataResult<Map<String, Any?>>) -> Unit): Job
    fun <T> toListOf(kType, params, onResult: (DataResult<List<T>>) -> Unit): Job
    fun <T> toSingleOf(kType, params, onResult: (DataResult<T>) -> Unit): Job
    fun <T> toField(kType, params, onResult: (DataResult<T>) -> Unit): Job
    fun <T> toFieldStrict(kType, params, onResult: (DataResult<T>) -> Unit): Job
    fun <T> toColumn(kType, params, onResult: (DataResult<List<T>>) -> Unit): Job
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

## See Also

- [Query Builders](query-builders.md) - How to build queries
- [Error Handling](error-handling.md) - Exception hierarchy and debugging
- [Data Mapping](data-mapping.md) - Converting between objects and maps
- [Transactions](transactions.md) - Transaction blocks and plans
