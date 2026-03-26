# Calling Functions and Procedures

*The Roman state did not administer its provinces through improvised orders shouted across the forum — it relied on established procedures, standing functions of governance, and known protocols. Octavius treats database functions and procedures with the same respect: call them by name, pass your arguments, and receive what is due.*

Octavius stays true to its SQL-first philosophy. Instead of magic wrappers, you invoke functions and procedures directly using native PostgreSQL syntax.

## Functions (`CREATE FUNCTION`)

Functions are called like any standard table or view. PostgreSQL returns them as a result set, so you use standard terminal methods like `toField`, `toListOf`, or `toSingle`. Since they are treated as standard relations, you can use them seamlessly with Octavius Query Builders.

### Basic Usage
Functions can be invoked directly in the `FROM` clause of a `SelectQueryBuilder`:
```kotlin
// SELECT * FROM calculate_tribute(province, year)
val tribute = dataAccess.select("*").from("calculate_tribute(@province, @year)")
    .toField<Int>("province" to "Aegyptus", "year" to 44)
    .getOrThrow() // 42000
```

### Void Functions (e.g., `pg_notify`)
If a function returns `void`, you can call it and map the result to `Unit`. This is commonly used for system functions like `pg_notify`:
```kotlin
// Using RawQueryBuilder
dataAccess.rawQuery("SELECT pg_notify(@channel, @payload)")
    .toField<Unit>("channel" to "senate_decrees", "payload" to "new_edict")

// Or using SelectQueryBuilder
dataAccess.select("pg_notify(@channel, @payload)")
    .toField<Unit>("channel" to "senate_decrees", "payload" to "new_edict")
```

### Set-Returning Functions (Table Functions)
Functions that return `SETOF type` or `TABLE(...)` behave exactly like tables. You can select specific columns, apply `WHERE` clauses, and map the multiple resulting rows using `toListOf()`:
```kotlin
// CREATE FUNCTION get_active_legionnaires(min_campaigns int)
//   RETURNS TABLE(id int, name text, campaigns_fought int)
val veterans = dataAccess.select("id", "name", "campaigns_fought")
    .from("get_active_legionnaires(@minCampaigns)")
    .where("campaigns_fought > 5")
    .toListOf<Legionnaire>("minCampaigns" to 2)
```

### Multiple OUT Parameters
Functions with multiple OUT parameters return a single row with multiple columns:
```kotlin
// CREATE FUNCTION assess_province(name text, OUT tribute_due int, OUT grain_quota int)
val assessment = dataAccess.rawQuery("SELECT * FROM assess_province(@name)")
    .toSingle("name" to "Britannia")
    .getOrThrow()

assessment!!["tribute_due"]  // 8000
assessment["grain_quota"]    // 3200
```

---

## Procedures (`CREATE PROCEDURE`)

Procedures are invoked using the `CALL` statement. Under the hood, PostgreSQL JDBC driver treats the result of a `CALL` statement with `OUT` or `INOUT` parameters as a standard `ResultSet` containing exactly one row.

Because of this, **you can use any standard terminal method** (`toSingleOf()`, `toSingle()`, `toField()`, `toListOf()`) to extract the results. However, since it always returns exactly one row, `toSingleStrict()` is usually the most semantic and safest choice.

### Basic Usage
For `OUT` parameters, you must explicitly provide a `NULL` placeholder with a type cast in your SQL query:

```kotlin
// CALL conscript_legionnaire(IN legion_id int, OUT new_rank text)
val result = dataAccess.rawQuery("CALL conscript_legionnaire(@legion_id, NULL::text)")
    .toSingleStrict("legion_id" to 7)
    .getOrThrow()

result["new_rank"] // "Miles"
```

### INOUT Parameters
`INOUT` parameters are passed as named parameters and are also returned in the result:
```kotlin
// CREATE PROCEDURE promote_legionnaire(INOUT current_rank text, IN years_served int)
val result = dataAccess.rawQuery("CALL promote_legionnaire(@current_rank, @years_served)")
    .toSingleStrict("current_rank" to "Miles", "years_served" to 5)
    .getOrThrow()

result["current_rank"] // "Optio"
```

### Mapping to Data Objects
Since `CALL` returns a standard row, you can map the `OUT`/`INOUT` parameters directly into a Kotlin Data Class:
```kotlin
data class EnlistmentResult(val assignedLegion: String, val enlistmentBonus: Int)

// CALL enlist_volunteer(OUT assigned_legion text, OUT enlistment_bonus int)
val result = dataAccess.rawQuery("CALL enlist_volunteer(NULL:@text, NULL:@int)")
    .toSingleOf<EnlistmentResult>()
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
