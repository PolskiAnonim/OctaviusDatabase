# Query Builders

*The Roman surveyor does not lay roads at random — he studies the terrain, selects his instruments, and drives his groma into the earth with purpose. So too should queries be constructed: deliberately, precisely, with full knowledge of what lies ahead.*

Octavius Database provides fluent query builders for all CRUD operations. Each builder supports:
- **CTE (Common Table Expressions)** via `with()` and `recursive()`
- **Named parameters** using `@param` syntax
- **Multiple terminal methods** for different result types
- **Async execution** via `async()`
- **Streaming** for large datasets via `asStream()`
- **Transaction steps** via `asStep()`

## Table of Contents

- [SelectQueryBuilder](#selectquerybuilder)
- [InsertQueryBuilder](#insertquerybuilder)
- [UpdateQueryBuilder](#updatequerybuilder)
- [DeleteQueryBuilder](#deletequerybuilder)
- [RawQueryBuilder](#rawquerybuilder)
- [QueryFragment & Dynamic Queries](#queryfragment--dynamic-queries)
- [Common Table Expressions (CTE)](#common-table-expressions-cte)
- [Subqueries](#subqueries)
- [ON CONFLICT (Upsert)](#on-conflict-upsert)
- [Row-Level Locking (FOR UPDATE)](#row-level-locking-for-update)
- [Auto-Generated Placeholders](#auto-generated-placeholders)
- [Builder Modes](#builder-modes)

> **Executing Queries**: For terminal methods (`toList()`, `toSingleOf()`, `execute()`, etc.), async execution, and streaming, see [Executing Queries](executing-queries.md).

---

## SelectQueryBuilder

Builds SQL SELECT queries with full support for all standard clauses.

### Methods

| Method                          | Description                                                                        |
|---------------------------------|------------------------------------------------------------------------------------|
| `from(source)`                  | FROM clause - table name, alias, or JOIN expression                                |
| `fromSubquery(subquery, alias)` | FROM with a subquery (auto-wrapped in parentheses)                                 |
| `where(condition)`              | WHERE clause (nullable - pass null to skip)                                        |
| `groupBy(columns)`              | GROUP BY clause                                                                    |
| `having(condition)`             | HAVING clause (requires GROUP BY)                                                  |
| `orderBy(ordering)`             | ORDER BY clause                                                                    |
| `limit(count)`                  | LIMIT clause                                                                       |
| `offset(position)`              | OFFSET clause                                                                      |
| `page(page, size)`              | Pagination helper (zero-indexed pages)                                             |
| `forUpdate(of?, mode?)`         | FOR UPDATE locking clause (see [Row-Level Locking](#row-level-locking-for-update)) |

### Examples

```kotlin
// Basic SELECT
val senators = dataAccess.select("id", "name", "province")
    .from("senate")
    .where("active = true")
    .orderBy("appointed_at DESC")
    .toListOf<Senator>()

// With pagination
val page = dataAccess.select("*")
    .from("tributes")
    .where("province = @province")
    .orderBy("amount ASC")
    .page(page = 0, size = 20)
    .toListOf<Tribute>("province" to "Aegyptus")

// With GROUP BY and HAVING
val stats = dataAccess.select("province", "COUNT(*) as legion_count", "AVG(strength) as avg_strength")
    .from("legions")
    .groupBy("province")
    .having("COUNT(*) > 2")
    .toListOf<ProvinceStats>()

// With JOINs (in FROM clause)
val campaignsWithCommanders = dataAccess.select("c.id", "c.name", "l.name as commander_name")
    .from("campaigns c JOIN legionnaires l ON c.commander_id = l.id")
    .where("c.status = @status")
    .toListOf<CampaignWithCommander>("status" to "active")
```

---

## InsertQueryBuilder

Builds SQL INSERT queries with support for values, expressions, SELECT source, and ON CONFLICT.

### Methods

| Method                          | Description                                              |
|---------------------------------|----------------------------------------------------------|
| `columns(vararg)`               | Explicitly define target columns (optional)              |
| `value(column)`                 | Add column with auto-generated `@column` placeholder     |
| `values(columns: List)`         | Add multiple columns with auto placeholders              |
| `values(data: Map)`             | Add columns from map keys with auto placeholders         |
| `valueExpression(column, expr)` | Add column with custom SQL expression                    |
| `valuesExpressions(map)`        | Add multiple columns with custom expressions             |
| `fromSelect(query)`             | INSERT ... SELECT (columns optional, inferred if absent) |
| `onConflict { }`                | Configure ON CONFLICT clause (upsert)                    |
| `returning(columns)`            | Add RETURNING clause                                     |

### Examples

```kotlin
// Basic INSERT
dataAccess.insertInto("citizens")
    .values(listOf("name", "tribe", "enrolled_at"))
    .execute(mapOf(
        "name" to "Gaius Julius",
        "tribe" to "Cornelia",
        "enrolled_at" to Clock.System.now()
    ))

// INSERT with RETURNING
val newId = dataAccess.insertInto("citizens")
    .value("name")
    .value("tribe")
    .returning("id")
    .toField<Int>("name" to "Marcus Tullius", "tribe" to "Cornelia")

// Using expressions (e.g., NOW(), DEFAULT)
dataAccess.insertInto("senate_audit")
    .valueExpression("action", "@action")
    .valueExpression("recorded_at", "NOW()")
    .valueExpression("senator_id", "COALESCE(@senator_id, 0)")
    .execute("action" to "vote_cast", "senator_id" to senatorId)

// INSERT from SELECT (with explicit columns)
dataAccess.insertInto("archived_campaigns")
    .columns("id", "tribute_total", "archived_at")
    .fromSelect("""
        SELECT id, tribute_total, NOW()
        FROM campaigns
        WHERE ended_at < @cutoff
    """)
    .execute("cutoff" to cutoffDate)

// INSERT from SELECT (columns inferred by database)
dataAccess.insertInto("archived_campaigns")
    .fromSelect("SELECT id, tribute_total, NOW() FROM campaigns WHERE ended_at < @cutoff")
    .execute("cutoff" to cutoffDate)
```

---

## UpdateQueryBuilder

Builds SQL UPDATE queries. **WHERE clause is mandatory** for safety.

### Methods

| Method                        | Description                                  |
|-------------------------------|----------------------------------------------|
| `setValue(column)`            | SET column with auto `@column` placeholder   |
| `setValues(columns: List)`    | SET multiple columns with auto placeholders  |
| `setValues(data: Map)`        | SET columns from map keys                    |
| `setExpression(column, expr)` | SET column with custom SQL expression        |
| `setExpressions(map)`         | SET multiple columns with custom expressions |
| `from(tables)`                | FROM clause for UPDATE ... FROM              |
| `where(condition)`            | WHERE clause (**mandatory**)                 |
| `returning(columns)`          | Add RETURNING clause                         |

### Examples

```kotlin
// Basic UPDATE
dataAccess.update("citizens")
    .setValues(listOf("name", "tribe"))
    .where("id = @id")
    .execute(mapOf("name" to "Livia Augusta", "tribe" to "Claudia", "id" to 42))

// UPDATE with expression
dataAccess.update("legion_supplies")
    .setExpression("quantity", "quantity - @consumed")
    .setExpression("last_resupply", "NOW()")
    .where("id = @id")
    .execute("consumed" to 5, "id" to supplyId)

// UPDATE with FROM (for JOINs)
dataAccess.update("campaigns")
    .setExpression("status", "@newStatus")
    .from("citizens c")
    .where("campaigns.commander_id = c.id AND c.exiled = true")
    .execute("newStatus" to "suspended")

// UPDATE with RETURNING
val updated = dataAccess.update("citizens")
    .setValue("last_census")
    .where("id = @id")
    .returning("id", "name", "last_census")
    .toSingleOf<Citizen>("last_census" to now, "id" to citizenId)
```

---

## DeleteQueryBuilder

Builds SQL DELETE queries. **WHERE clause is mandatory** for safety.

### Methods

| Method               | Description                        |
|----------------------|------------------------------------|
| `using(tables)`      | USING clause for DELETE with JOINs |
| `where(condition)`   | WHERE clause (**mandatory**)       |
| `returning(columns)` | Add RETURNING clause               |

### Examples

```kotlin
// Basic DELETE
dataAccess.deleteFrom("expired_mandates")
    .where("expires_at < NOW()")
    .execute()

// DELETE with USING (JOIN-like)
dataAccess.deleteFrom("campaign_legions")
    .using("campaigns c")
    .where("campaign_legions.campaign_id = c.id AND c.status = @status")
    .execute("status" to "disbanded")

// DELETE with RETURNING
val expelled = dataAccess.deleteFrom("senate")
    .where("id = @id")
    .returning("id", "name")
    .toSingleOf<ExpelledSenator>("id" to senatorId)
```

---

## RawQueryBuilder

Executes arbitrary SQL queries. Use when the fluent builders don't cover your use case.

### Usage

```kotlin
// Complex query with raw SQL
val results = dataAccess.rawQuery("""
    SELECT
        c.id,
        c.name,
        COUNT(cam.id) as campaign_count,
        COALESCE(SUM(cam.tribute_collected), 0) as total_tribute
    FROM citizens c
    LEFT JOIN campaigns cam ON cam.commander_id = c.id
    WHERE c.enrolled_at > @since
    GROUP BY c.id, c.name
    HAVING COUNT(cam.id) > @minCampaigns
    ORDER BY total_tribute DESC
""").toListOf<CitizenStats>("since" to startDate, "minCampaigns" to 2)

// Raw UPDATE
val affected = dataAccess.rawQuery("""
    UPDATE tributes
    SET amount = amount * 1.1
    WHERE province = @province AND amount < @maxAmount
""").execute("province" to "Britannia", "maxAmount" to 1000)
```

---

## QueryFragment & Dynamic Queries

Since standard builders construct SQL strings but do not store parameters internally until execution, `QueryFragment` serves as a container for holding a piece of SQL along with its associated parameters. This is essential for building dynamic queries, complex filters, or passing partial query logic between application layers.

### The `QueryFragment` Class

A simple data carrier containing the SQL string and a map of parameters.

```kotlin
data class QueryFragment(
    val sql: String,
    val params: Map<String, Any?> = emptyMap()
)
```

**Syntax Sugar:**
To keep your code readable, the library provides handy `infix` functions for creating fragments:
```kotlin
// Single parameter
val f1 = "rank_order >= @rank" withParam ("rank" to 3)

// Multiple parameters
val f2 = "tribute BETWEEN @min AND @max" withParams mapOf("min" to 100, "max" to 5000)

// No parameters
val f3 = QueryFragment("last_census_at = NOW()")
```

### Joining Fragments

You can combine a list of fragments using the `.join()` extension method. This is powerful because it:
1. **Merges Parameters Safely**: Collects all parameters into a single map, throwing an error if duplicate keys have conflicting values.
2. **Handles Logic Grouping**: By default, wraps each fragment in parentheses (e.g., `(A) AND (B)`) to preserve operator precedence.
3. **Applies Prefix/Postfix**: Useful for constructing specific clauses (like `WHERE ...` or `SET ...`).

### Example: Dynamic WHERE Clause

Using `listOfNotNull` combined with the Elvis operator or `?.let` is the cleanest way to build conditional filters.

```kotlin
fun searchCitizens(name: String?, minAge: Int?, tribe: String?): List<Citizen> {

    // 1. Build list of fragments based on non-null inputs
    val fragments = listOfNotNull(
        name?.let { "name ILIKE @name" withParam ("name" to "%$it%") },
        minAge?.let { "age >= @minAge" withParam ("minAge" to it) },
        tribe?.let { "tribe = @tribe" withParam ("tribe" to it) }
    )

    // 2. Join them
    // If list is empty, returns empty fragment.
    // addParenthesis = true (default) ensures safety: (name ILIKE...) AND (age >=...)
    val whereClause = fragments.join(separator = " AND ")

    // 3. Apply to builder
    // Note: We pass whereClause.sql to the builder and whereClause.params to the executor
    return dataAccess.select("*")
        .from("citizens")
        .where(whereClause.sql)
        .toListOf<Citizen>(whereClause.params)
}
```

### Example: Dynamic Updates (Raw Query)

`join` is particularly useful with `RawQueryBuilder` for dynamic SET clauses. You can use `prefix` and `postfix` to format the final SQL string.

```kotlin
val updates = listOfNotNull(
    newRank?.let { "rank = @rank" withParam ("rank" to it) },
    newTribe?.let { "tribe = @tribe" withParam ("tribe" to it) },
    QueryFragment("last_census_at = NOW()") // Always update census timestamp
)

val setClause = updates.join(
    separator = ", ", 
    prefix = "SET ",        // Prepend "SET " to the result
    addParenthesis = false // Don't wrap SET assignments in ()
)

// Result SQL: "UPDATE citizens SET rank = @rank, tribe = @tribe, last_census_at = NOW() WHERE id = @id"
dataAccess.rawQuery("UPDATE citizens ${setClause.sql} WHERE id = @id")
    .execute(setClause.params + ("id" to citizenId))
```

---

## Common Table Expressions (CTE)

All builders support CTEs via `with()` and `recursive()`.

### Basic CTE

```kotlin
val activeLegionnaires = dataAccess.select("*")
    .with("active_legionnaires", "SELECT * FROM legionnaires WHERE active = true")
    .from("active_legionnaires")
    .where("last_campaign > @since")
    .toListOf<Legionnaire>("since" to lastDecade)
```

### Multiple CTEs

```kotlin
val report = dataAccess.select("*")
    .with("recent_campaigns", "SELECT * FROM campaigns WHERE started_at > @since")
    .with("campaign_totals", "SELECT commander_id, SUM(tribute_collected) as total FROM recent_campaigns GROUP BY commander_id")
    .from("citizens c JOIN campaign_totals ct ON c.id = ct.commander_id")
    .orderBy("ct.total DESC")
    .toListOf<CommanderReport>("since" to lastYear)
```

### Recursive CTE

```kotlin
// Tree traversal (e.g., provincial hierarchy)
val hierarchy = dataAccess.select("*")
    .with("province_tree", """
        SELECT id, name, parent_id, 1 as depth
        FROM provinces
        WHERE id = @rootId
        UNION ALL
        SELECT p.id, p.name, p.parent_id, pt.depth + 1
        FROM provinces p
        JOIN province_tree pt ON p.parent_id = pt.id
    """)
    .recursive()
    .from("province_tree")
    .orderBy("depth, name")
    .toListOf<ProvinceNode>("rootId" to rootProvinceId)
```

### CTE with INSERT

```kotlin
val archivedCount = dataAccess.insertInto("campaign_archive")
    .columns("id", "data", "archived_at")
    .with("to_archive", "SELECT id, data FROM campaigns WHERE status = 'concluded'")
    .fromSelect("SELECT id, data, NOW() FROM to_archive")
    .execute()
```

---

## Subqueries

### In FROM clause

```kotlin
val stats = dataAccess.select("province", "avg_tribute")
    .fromSubquery("""
        SELECT province, AVG(amount) as avg_tribute
        FROM tributes
        GROUP BY province
    """, alias = "province_stats")
    .where("avg_tribute > @minAvg")
    .toListOf<ProvinceStats>("minAvg" to 500.0)
```

### In WHERE clause (as part of condition string)

```kotlin
val commanders = dataAccess.select("*")
    .from("citizens")
    .where("id IN (SELECT commander_id FROM campaigns WHERE tribute_collected > @minTribute)")
    .toListOf<Citizen>("minTribute" to 10000)
```

---

## ON CONFLICT (Upsert)

The `onConflict` builder allows configuring PostgreSQL's ON CONFLICT clause for upsert operations.

### Configuration Methods

| Method                            | Description                           |
|-----------------------------------|---------------------------------------|
| `onColumns(columns)`              | Conflict target: columns              |
| `onConstraint(name)`              | Conflict target: constraint name      |
| `doNothing()`                     | ON CONFLICT DO NOTHING                |
| `doUpdate(setExpression, where?)` | DO UPDATE SET with raw expression     |
| `doUpdate(vararg pairs, where?)`  | DO UPDATE SET with column-value pairs |
| `doUpdate(map, where?)`           | DO UPDATE SET from map                |

### Examples

```kotlin
// DO NOTHING on conflict
dataAccess.insertInto("citizens")
    .values(listOf("name", "tribe"))
    .onConflict {
        onColumns("name")
        doNothing()
    }
    .execute("name" to "Marcus Aurelius", "tribe" to "Aurelia")

// DO UPDATE (upsert)
dataAccess.insertInto("citizen_census")
    .values(listOf("citizen_id", "census_count", "last_recorded"))
    .onConflict {
        onColumns("citizen_id")
        doUpdate(
            "census_count" to "citizen_census.census_count + 1",
            "last_recorded" to "EXCLUDED.last_recorded"
        )
    }
    .execute(
        "citizen_id" to citizenId,
        "census_count" to 1,
        "last_recorded" to now
    )

// Using EXCLUDED to reference attempted insert values
dataAccess.insertInto("tributes")
    .values(listOf("province", "year", "amount", "goods"))
    .onConflict {
        onColumns("province", "year")
        doUpdate(
            "amount" to "EXCLUDED.amount",
            "goods" to "tributes.goods + EXCLUDED.goods",
            whereCondition = "tributes.amount != EXCLUDED.amount OR tributes.goods != EXCLUDED.goods"
        )
    }
    .execute(tributeData)

// Using constraint name
dataAccess.insertInto("senate_records")
    .values(senateData)
    .onConflict {
        onConstraint("senate_records_session_key")
        doNothing()
    }
    .execute(senateData)
```

---

## Row-Level Locking (FOR UPDATE)

The `forUpdate()` method appends a `FOR UPDATE` clause, which locks the selected rows for the duration of the current transaction. This prevents other transactions from modifying or deleting them before your transaction commits.

> **Requires an active transaction.** `FOR UPDATE` is only meaningful inside a `dataAccess.transaction { ... }` block. Outside of one, the lock is released immediately after the query.

### Method Signature

```kotlin
fun forUpdate(of: String? = null, mode: LockWaitMode? = null): SelectQueryBuilder
```

### `LockWaitMode`

Controls what happens if another transaction already holds a lock on one of the selected rows.

| Value         | Behavior                                                       |
|---------------|----------------------------------------------------------------|
| *(none)*      | Wait until the lock is available (default PostgreSQL behavior) |
| `NOWAIT`      | Return `Failure` immediately instead of waiting                |
| `SKIP_LOCKED` | Silently skip rows that are currently locked                   |

### Examples

```kotlin
// Basic FOR UPDATE - lock the campaign, then update it
dataAccess.transaction { tx ->
    val result = tx.select("*")
        .from("campaigns")
        .where("id = @id")
        .forUpdate()
        .toSingleOf<Campaign>("id" to campaignId)

    result.onSuccess { campaign ->
        dataAccess.update("campaigns")
            .setExpression("status", "@status")
            .where("id = @id")
            .execute("status" to "marching", "id" to campaign?.id)
    }
}

// FOR UPDATE OF - lock only the specified table in a JOIN query
dataAccess.transaction { tx ->
    tx.select("c.id", "c.tribute_total", "a.balance")
        .from("campaigns c JOIN aerarium a ON c.province_id = a.province_id")
        .where("c.id = @id")
        .forUpdate(of = "c")  // only lock rows in 'campaigns', not 'aerarium'
        .toSingleOf<CampaignWithBalance>("id" to campaignId)
}

// FOR UPDATE NOWAIT - return Failure immediately if row is already locked
dataAccess.transaction { tx ->
    tx.select("*")
        .from("grain_depots")
        .where("id = @id AND status = 'available'")
        .forUpdate(mode = LockWaitMode.NOWAIT)
        .toSingleOf<GrainDepot>("id" to depotId)
        .onSuccess { depot -> /* requisition it */ }
        .onFailure { error -> /* depot claimed by another legion */ }
}

// FOR UPDATE SKIP LOCKED - levy queue / conscription processing pattern
dataAccess.transaction { tx ->
    tx.select("*")
        .from("conscription_queue")
        .where("status = 'pending'")
        .orderBy("enrolled_at")
        .limit(10)
        .forUpdate(mode = LockWaitMode.SKIP_LOCKED) // skip recruits claimed by other centurions
        .toListOf<Conscript>()
        .onSuccess { recruits -> /* process conscription */ }
}
```

---

## Auto-Generated Placeholders

### InsertQueryBuilder: `values()`

Generates `@key` placeholders automatically for INSERT queries.

```kotlin
// Using List - generates placeholders for each column name
dataAccess.insertInto("citizens")
    .values(listOf("name", "tribe", "enrolled_at"))
    // Generated: INSERT INTO citizens (name, tribe, enrolled_at) VALUES (@name, @tribe, @enrolled_at)
    .execute("name" to "Gaius Octavius", "tribe" to "Iulia", "enrolled_at" to now)

// Using Map - uses map keys as column names
val data = mapOf("name" to "Gaius Octavius", "tribe" to "Iulia")
dataAccess.insertInto("citizens")
    .values(data)
    // Generated: INSERT INTO citizens (name, tribe) VALUES (@name, @tribe)
    .execute(data)
```

### UpdateQueryBuilder: `setValues()`

Generates `@key` placeholders automatically for UPDATE queries.

```kotlin
// Using List
dataAccess.update("citizens")
    .setValues(listOf("name", "tribe"))
    // Generated: UPDATE citizens SET name = @name, tribe = @tribe WHERE ...
    .where("id = @id")
    .execute("name" to "Livia Drusilla", "tribe" to "Claudia", "id" to 1)

// Using Map
val updates = mapOf("name" to "Livia Drusilla", "tribe" to "Claudia")
dataAccess.update("citizens")
    .setValues(updates)
    .where("id = @id")
    .execute(updates + ("id" to 1))
```

### Single-Column Variants: `value()` and `setValue()`

```kotlin
// For INSERT
dataAccess.insertInto("citizens")
    .value("name")
    .value("tribe")
// Generated: INSERT INTO citizens (name, tribe) VALUES (@name, @tribe)

// For UPDATE
dataAccess.update("citizens")
    .setValue("name")
    .setValue("tribe")
// Generated: UPDATE citizens SET name = @name, tribe = @tribe WHERE ...
```

---

## Builder Modes

Each builder can be converted to different execution modes.

### `.asStep()` - Transaction Steps

Convert to a `TransactionStep` for use in `TransactionPlan`:

```kotlin
val plan = TransactionPlan()

val edictIdHandle = plan.add(
    dataAccess.insertInto("edicts")
        .values(listOf("issuer_id", "tribute_total"))
        .returning("id")
        .asStep()
        .toField<Int>("issuer_id" to consulId, "tribute_total" to total)
)

// Use handle in subsequent steps...
```

See [Transactions](transactions.md) for full documentation.

### `.async()` - Async Execution

Execute queries asynchronously using coroutines. See [Executing Queries - Async](executing-queries.md#async-execution).

### `.asStream()` - Streaming

Process large datasets without loading everything into memory. See [Executing Queries - Streaming](executing-queries.md#streaming).

### `.copy()` - Clone Builder

Create a deep copy of the builder for creating variants:

```kotlin
val baseQuery = dataAccess.select("id", "name")
    .from("legionnaires")
    .orderBy("name")

// Create variants without modifying the original
val activeLegionnaires = baseQuery.copy().where("active = true").toListOf<Legionnaire>()
val retiredLegionnaires = baseQuery.copy().where("active = false").toListOf<Legionnaire>()
```
