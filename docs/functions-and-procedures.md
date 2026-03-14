# Calling Functions and Procedures

Octavius stays true to its SQL-first philosophy. Instead of magic wrappers, you invoke functions and procedures directly using native PostgreSQL syntax.

## Functions (`CREATE FUNCTION`)

Functions are called like any standard table or view. PostgreSQL returns them as a result set, so you use standard terminal methods like `toField`, `toListOf`, or `toSingle`. Since they are treated as standard relations, you can use them seamlessly with Octavius Query Builders.

### Basic Usage
Functions can be invoked directly in the `FROM` clause of a `SelectQueryBuilder`:
```kotlin
// SELECT * FROM add_numbers(a, b)
val result = dataAccess.select("*").from("add_numbers(:a, :b)")
    .toField<Int>("a" to 17, "b" to 25)
    .getOrThrow() // 42
```

### Void Functions (e.g., `pg_notify`)
If a function returns `void`, you can call it and map the result to `Unit`. This is commonly used for system functions like `pg_notify`:
```kotlin
// Using RawQueryBuilder
dataAccess.rawQuery("SELECT pg_notify(:channel, :payload)")
    .toField<Unit>("channel" to "orders", "payload" to "new_order")

// Or using SelectQueryBuilder
dataAccess.select("pg_notify(:channel, :payload)")
    .toField<Unit>("channel" to "orders", "payload" to "new_order")
```

### Set-Returning Functions (Table Functions)
Functions that return `SETOF type` or `TABLE(...)` behave exactly like tables. You can select specific columns, apply `WHERE` clauses, and map the multiple resulting rows using `toListOf()`:
```kotlin
// CREATE FUNCTION get_active_users(min_score int) RETURNS TABLE(id int, name text, score int)
val activeUsers = dataAccess.select("id", "name", "score")
    .from("get_active_users(:minScore)")
    .where("score > 100")
    .toListOf<User>("minScore" to 50)
```

### Multiple OUT Parameters
Functions with multiple OUT parameters return a single row with multiple columns:
```kotlin
// CREATE FUNCTION split_text(input text, OUT left text, OUT right text)
val result = dataAccess.rawQuery("SELECT * FROM split_text(:input)")
    .toSingle("input" to "abcdef")
    .getOrThrow()

result!!["left"]  // "abc"
result["right"]   // "def"
```

---

## Procedures (`CREATE PROCEDURE`)

Procedures are invoked using the `CALL` statement. Under the hood, PostgreSQL JDBC driver treats the result of a `CALL` statement with `OUT` or `INOUT` parameters as a standard `ResultSet` containing exactly one row.

Because of this, **you can use any standard terminal method** (`toSingleOf()`, `toSingle()`, `toField()`, `toListOf()`) to extract the results. However, since it always returns exactly one row, `toSingleStrict()` is usually the most semantic and safest choice.

### Basic Usage
For `OUT` parameters, you must explicitly provide a `NULL` placeholder with a type cast in your SQL query:

```kotlin
// CALL my_proc(IN a int, OUT result text)
val result = dataAccess.rawQuery("CALL my_proc(:a, NULL::text)")
    .toSingleStrict("a" to 42)
    .getOrThrow()

result["result"] // "some value"
```

### INOUT Parameters
`INOUT` parameters are passed as named parameters and are also returned in the result:
```kotlin
// CREATE PROCEDURE increment(INOUT counter int4, IN step int4)
val result = dataAccess.rawQuery("CALL increment(:counter, :step)")
    .toSingleStrict("counter" to 10, "step" to 3)
    .getOrThrow()

result["counter"] // 13
```

### Mapping to Data Objects
Since `CALL` returns a standard row, you can map the `OUT`/`INOUT` parameters directly into a Kotlin Data Class:
```kotlin
data class ProcResult(val resultStr: String, val resultInt: Int)

// CALL complex_proc(OUT result_str text, OUT result_int int)
val result = dataAccess.rawQuery("CALL complex_proc(NULL::text, NULL::int)")
    .toSingleOf<ProcResult>()
```

---

## Comparison: Functions vs Procedures

|                       | Procedure (`CREATE PROCEDURE`)                                                        | Function (`CREATE FUNCTION`)                            |
|-----------------------|---------------------------------------------------------------------------------------|---------------------------------------------------------|
| **SQL syntax**        | `CALL proc(...)`                                                                      | `SELECT * FROM func(...)`                               |
| **OUT params in SQL** | Require placeholder (`NULL::type`)                                                    | Not passed at all — they define the return columns      |
| **Return value**      | Standard terminal methods (`toField`, `toListOf`, etc.) - it should always be one row | Standard terminal methods (`toField`, `toListOf`, etc.) |
| **Void return**       | `execute()`                                                                           | `toField<Unit>()`                                       |

---