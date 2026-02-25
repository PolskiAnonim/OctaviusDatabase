# Stored Procedures

Octavius Database supports calling PostgreSQL stored procedures (`CREATE PROCEDURE`) with full type handling for IN, OUT, and INOUT parameters.

## Table of Contents

- [Basic Usage](#basic-usage)
- [Parameter Modes](#parameter-modes)
- [Complex Parameters](#complex-parameters)
- [How It Works](#how-it-works)
- [Functions vs Procedures](#functions-vs-procedures)
- [Limitations](#limitations)

---

## Basic Usage

Call a stored procedure using `dataAccess.call()`:

```kotlin
// Procedure: CREATE PROCEDURE add_numbers(IN a int4, IN b int4, OUT result int4)
val result = dataAccess.call("add_numbers")
    .execute("a" to 17, "b" to 25)

result.getOrThrow() // { "result" to 42 }
```

The `execute()` method accepts parameters as `vararg Pair` or `Map`:

```kotlin
// vararg
dataAccess.call("my_proc").execute("x" to 1, "y" to 2)

// Map
dataAccess.call("my_proc").execute(mapOf("x" to 1, "y" to 2))
```

The return type is always `DataResult<Map<String, Any?>>`, where the map contains OUT and INOUT parameter values keyed by their PostgreSQL parameter names.

---

## Parameter Modes

### IN — Input Only

Standard input parameters. Pass them in the `execute()` call:

```kotlin
// CREATE PROCEDURE void_proc(p_text text)
dataAccess.call("void_proc").execute("p_text" to "hello")
```

Procedures with no OUT parameters return an empty map.

### OUT — Output Only

Not passed by the caller. Values are returned in the result map:

```kotlin
// CREATE PROCEDURE split_text(IN input text, OUT first_half text, OUT second_half text, OUT total_len int4)
val result = dataAccess.call("split_text")
    .execute("input" to "abcdef")
    .getOrThrow()

result["first_half"]  // "abc"
result["second_half"] // "def"
result["total_len"]   // 6
```

### INOUT — Input and Output

Passed as input AND returned as output. The value you pass is the initial value; the procedure may modify it:

```kotlin
// CREATE PROCEDURE increment(INOUT counter int4, IN step int4)
val result = dataAccess.call("increment")
    .execute("counter" to 10, "step" to 3)
    .getOrThrow()

result["counter"] // 13
```

---

## Complex Parameters

All parameter types supported by the [Type System](type-system.md) work with procedures — composites, arrays, enums, and combinations thereof.

### Composite Types (@PgComposite)

```kotlin
// CREATE PROCEDURE greet_person(IN person test_person, OUT greeting text)
val person = TestPerson("Alice", 30, "alice@test.com", true, listOf("admin"))

val result = dataAccess.call("greet_person")
    .execute("person" to person)
    .getOrThrow()

result["greeting"] // "Hello, Alice! Age: 30"
```

Composites are automatically expanded to `ROW(?,?,?,?,?)::type_name` with correct JDBC positional tracking.

### Arrays

```kotlin
// CREATE PROCEDURE sum_array(IN numbers int4[], OUT total int4)
val result = dataAccess.call("sum_array")
    .execute("numbers" to listOf(10, 20, 30))
    .getOrThrow()

result["total"] // 60
```

Arrays are expanded to `ARRAY[?,?,?]` with each element bound individually.

### Enum Types (@PgEnum)

```kotlin
// CREATE PROCEDURE next_status(IN current_status test_status, OUT next test_status)
val result = dataAccess.call("next_status")
    .execute("current_status" to TestStatus.Pending)
    .getOrThrow()

result["next"] // TestStatus.Active
```

### Combining Complex Types

Composites, arrays, and enums can be freely combined. Parameter position tracking handles the expansion correctly:

```kotlin
// CREATE PROCEDURE complex_proc(IN person test_person, IN tags text[], OUT summary text)
// test_person has 5 fields → expands to ROW(?,?,?,?,?)::test_person (5 JDBC params)
// tags list of 2 → expands to ARRAY[?,?] (2 JDBC params)
// summary OUT → NULL::text literal (no JDBC param)
val result = dataAccess.call("complex_proc").execute(
    "person" to TestPerson("Bob", 25, "bob@test.com", true, emptyList()),
    "tags" to listOf("dev", "senior")
)

result.getOrThrow()["summary"] // "Bob [dev, senior]"
```

---

## How It Works

### Procedure Metadata

Octavius scans `pg_proc` at startup to discover procedure signatures (parameter names, types, modes). This metadata is stored in `TypeRegistry` and used to build the CALL statement automatically.

### Execution Strategy

The builder uses `PreparedStatement` — **not** `CallableStatement`. PgJDBC's `CallableStatement` is a thin wrapper that internally does the same thing but with broken type conversion (`getObject(int, Class)` throws for most types). By using `PreparedStatement` directly, OUT parameter values are read from the `ResultSet` using the same `ResultSetValueExtractor` as all other queries — with native JDBC fast-path getters for standard types.

The CALL statement is constructed as follows:

| Parameter Mode | SQL Fragment | JDBC Bind |
|---|---|---|
| **IN** | Expanded placeholder (`?`, `ROW(?,...)::type`, `ARRAY[?,...]`) | `setObject()` for each value |
| **OUT** | `NULL::typeName` literal | No bind — PostgreSQL requires the slot but ignores the value |
| **INOUT** | Expanded placeholder (same as IN) | `setObject()` for each value |

OUT and INOUT values are returned by PostgreSQL as a `ResultSet`, which is read using the standard type extraction pipeline.

### Example: Generated SQL

```
Procedure: complex_proc(IN person test_person, IN tags text[], OUT summary text)

Generated SQL:
  CALL complex_proc(ROW(?, ?, ?, ?, ?)::test_person, ARRAY[?, ?], NULL::text)

JDBC binds (7 total):
  1: "Bob"           -- person.name
  2: 25              -- person.age
  3: "bob@test.com"  -- person.email
  4: true            -- person.active
  5: {}              -- person.roles (empty array)
  6: "dev"           -- tags[0]
  7: "senior"        -- tags[1]

ResultSet (1 row, 1 column):
  summary = "Bob [dev, senior]"
```

---

## Functions vs Procedures

PostgreSQL has two distinct callable types. Octavius handles them differently:

| | Procedure (`CREATE PROCEDURE`) | Function (`CREATE FUNCTION`) |
|---|---|---|
| **SQL syntax** | `CALL proc(...)` | `SELECT * FROM func(...)` |
| **Octavius API** | `dataAccess.call("proc")` | `dataAccess.rawQuery("SELECT * FROM func(:params)")` |
| **OUT params in call** | Require placeholder (`NULL::type`) | Not passed at all — they define the return columns |
| **INOUT params** | Passed as input, returned as output | Passed as input, returned as output |
| **Return value** | `DataResult<Map<String, Any?>>` | Use standard terminal methods (`toField`, `toListOf`, etc.) |
| **Void return** | Empty map | `toField<Unit>()` |

### Calling Functions

Functions are called through the standard query builders, not through `call()`:

```kotlin
// Function: CREATE FUNCTION add(IN a int, IN b int, OUT result int)
// OUT params are NOT passed — they become return columns
val result = dataAccess.rawQuery("SELECT * FROM add(:a, :b)")
    .toField<Int>("a" to 1, "b" to 2)

// Function with multiple OUT params
// CREATE FUNCTION split(IN input text, OUT left text, OUT right text)
val parts = dataAccess.rawQuery("SELECT * FROM split(:input)")
    .toSingle("input" to "abcdef")
    .getOrThrow()

parts!!["left"]  // "abc"
parts["right"]   // "def"

// Void function
dataAccess.rawQuery("SELECT pg_notify(:channel, :payload)")
    .toField<Unit>("channel" to "orders", "payload" to "new_order")
```

---

## Limitations

### Overloaded Procedures

PostgreSQL allows multiple procedures with the same name but different parameter signatures. Octavius **detects overloaded procedures at startup** and excludes them from `dataAccess.call()` with a warning log:

```
Overloaded procedures detected: [my_proc] — these are excluded from dataAccess.call() and can only be invoked via rawQuery()
```

To call an overloaded procedure, use `rawQuery()` instead (see below).

### Calling Procedures via rawQuery()

Any procedure — including overloaded ones — can be called through `rawQuery()`. You write the full `CALL` statement yourself, including `NULL::type` placeholders for OUT parameters:

```kotlin
// Void procedure (no OUT params) — use execute()
dataAccess.rawQuery("CALL void_proc(:p_text)")
    .execute("p_text" to "hello")

// Procedure with OUT params — use toSingle()
val result = dataAccess.rawQuery("CALL add_numbers(:a, :b, NULL::int4)")
    .toSingle("a" to 17, "b" to 25)
    .getOrThrow()

result!!["result"] // 42
```

**Terminal method rules for CALL statements:**

| Has OUT params? | Terminal method | Return type |
|---|---|---|
| No | `execute()` | `DataResult<Int>` (always 0) |
| Yes | `toSingle()` | `DataResult<Map<String, Any?>?>` |

> **Note:** `toList()` also works but procedures always return a single row, so `toSingle()` is the natural fit.

---

## See Also

- [Query Builders](query-builders.md) - SELECT, INSERT, UPDATE, DELETE
- [Executing Queries](executing-queries.md) - Terminal methods, DataResult
- [Type System](type-system.md) - @PgEnum, @PgComposite, type mappings
- [Transactions](transactions.md) - Wrapping procedure calls in transactions
