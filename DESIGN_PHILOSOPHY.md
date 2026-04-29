# Design Philosophy

> *The Roman engineer did not hide the aqueduct. He built it in full view — arches marching across the valley, each stone placed with intention, the entire structure legible to anyone who cared to look. He did not abstract the water delivery behind a panel of levers and dials and ask you to trust the magic inside. He trusted that a visible, well-built system inspires more confidence than a hidden, "convenient" one.*
>
> *Octavius is built on the same principle.*

---

If you've come from Hibernate, Exposed, or JOOQ and found the API unexpectedly direct — even blunt — this page is for you. None of the choices described here are accidental.

---

> **A note on context: desktop-first, not server-first**
>
> Many of Octavius's design choices may seem unusual when viewed through the lens of a typical backend web service. That's because they weren't made in that context. Octavius was born from the needs of a stateful, fat-client desktop application built with Compose for Desktop.
>
> In that world, a blocking API is the norm (run it on a background thread), `DataResult` is essential for driving UI state, and direct database access without a web layer in between is common. The choices that might look like omissions in a Spring Boot service — no session abstraction, no transparent lazy loading, explicit thread management — are features in a desktop context, not gaps.
>
> Octavius works perfectly well on the server. But understanding its desktop-first heritage explains why it consistently chooses explicit control over framework magic.

## Table of Contents

- [SQL is not a problem to be solved](#sql-is-not-a-problem-to-be-solved)
- [PostgreSQL is not "just a database"](#postgresql-is-not-just-a-database)
- [The ORM tax](#the-orm-tax)
- [Why `Map<String, Any?>` and not data classes everywhere](#why-mapstring-any-and-not-data-classes-everywhere)
- [Why JSONB always maps to JsonElement](#why-jsonb-always-maps-to-jsonelement)
- [Why blocking and not coroutines-first](#why-blocking-and-not-coroutines-first)
- [Why DataResult and not exceptions](#why-dataresult-and-not-exceptions)
- [Why `@param` and not `:param`](#why-param-and-not-param)
- [Dynamic queries are just strings](#dynamic-queries-are-just-strings)
- [The line Octavius deliberately does not cross](#the-line-octavius-deliberately-does-not-cross)
- [When Octavius is not the right tool](#when-octavius-is-not-the-right-tool)

---

## SQL is not a problem to be solved

The most fundamental assumption behind Octavius is this: **SQL is not boilerplate. It is the domain language of your database, and it deserves to be written, read, and maintained as first-class code.**

Most data access libraries treat SQL as an implementation detail to be hidden. They offer type-safe Kotlin DSLs that compile down to SQL — which sounds appealing until you need a lateral join, a window function, a recursive CTE, `DISTINCT ON`, `FILTER (WHERE ...)`, or any of the dozens of PostgreSQL features that don't fit neatly into a Kotlin method chain. At that point, every type-safe DSL has an escape hatch: a `rawQuery()` or `literal()` method that drops you back to strings anyway. Even SQLDelight — which takes the more honest approach of writing real SQL in `.sq` files — hits the same wall the moment a query's shape needs to vary at runtime. The compile-time safety guarantee evaporates exactly where queries get interesting.

Octavius starts from the escape hatch. Every query is a string — named parameters, builder conveniences for common patterns, but always a string underneath. This means:

- Every PostgreSQL feature works on day one, without waiting for library support.
- Queries are readable by anyone who knows SQL, not just by people who know the library's DSL.
- A query in your Kotlin code looks like the query you'd test in `psql`. There is no translation layer to debug.

The builders (`SelectQueryBuilder`, `InsertQueryBuilder`, etc.) exist to handle the mechanical parts — generating `(@col1, @col2)` from a map, managing the WITH clause, building the RETURNING clause — not to replace SQL.

---

## PostgreSQL is not "just a database"

Most ORMs and data access libraries are designed for portability: write once, run against MySQL, PostgreSQL, SQLite, Oracle. This is a reasonable goal for a general-purpose library. It is not a goal Octavius has.

PostgreSQL is not a row store with a query language bolted on. It is a programmable computation engine: it has a rich type system you can extend, functions and procedures you can call as first-class operations, full-text search and trigram similarity built in, window functions and recursive CTEs for computations that would require multiple round-trips in application code, and a publish-subscribe mechanism for async communication. Used well, PostgreSQL does work that most applications offload to separate services — a search engine, a message broker, a caching layer.

Most ORMs reduce this to a row store anyway. Their abstraction layer sits between your application and the database, intercepting every operation, generating safe-but-generic SQL, and ensuring that the PostgreSQL you deliberately chose behaves roughly like any other database would. You get portability you didn't ask for, at the cost of the capabilities you chose PostgreSQL for.

Octavius is designed exclusively for PostgreSQL, and it treats PostgreSQL as a first-class platform rather than a generic SQL executor:

- **ENUM types** are real database types, not string columns with application-side validation. `@PgEnum` maps Kotlin enums to actual `CREATE TYPE ... AS ENUM` definitions.
- **COMPOSITE types** are real database types, not JSON blobs or separate tables. `@PgComposite` maps Kotlin data classes to actual `CREATE TYPE ... AS (...)` definitions, including nesting and arrays of composites.
- **LISTEN/NOTIFY** is a first-class feature, not an afterthought. `PgChannelListener` provides a dedicated connection and a `Flow<PgNotification>` interface.
- **Extensions and custom operators** work without any library support. Trigram similarity searches with `pg_trgm` (`name % @query`, `name <-> @query`), range containment with custom `@>>` operators, full-text ranking with `ts_rank` — these are things no type-safe DSL can anticipate, because PostgreSQL lets you define your own operators entirely. In Octavius they are just SQL. In every other library they are `rawQuery()`.
- **Array slicing, JSONB operators, lateral joins, advisory locks** — everything PostgreSQL offers is available, because the query layer never stands in the way.

The trade-off is obvious: if you ever need to switch databases, Octavius won't help you. But if you've chosen PostgreSQL deliberately — and for most serious backend applications, it is a deliberate choice — then being database-agnostic means leaving capabilities on the table for a flexibility you'll never use.

---

## The ORM tax

Hibernate and its cousins offer a compelling promise: map your objects to tables, and the framework handles persistence. In exchange, you pay what might be called the ORM tax.

**Session management.** Every operation requires an active session. Sessions have lifecycles, scopes, thread affinity. When something goes wrong — a lazy load outside a session, a detached entity, an unexpected proxy — the error messages are famously cryptic.

**The N+1 problem.** Eager loading fetches too much. Lazy loading silently fires a query per row. The correct solution (a JOIN) requires learning the ORM's JOIN syntax, which is rarely as expressive as SQL.

**Dirty tracking.** The ORM watches your objects for changes and generates UPDATE statements at flush time. This is convenient until it isn't — when the generated SQL is wrong, when the flush order creates constraint violations, when a "read-only" operation triggers an unexpected write.

**Schema and object model coupling.** Your Kotlin classes must mirror your database schema closely enough for the ORM to manage them. Adding a computed column, renaming a field, using a view instead of a table — all of these require ORM configuration that can be more complex than the SQL it replaces.

Octavius pays none of this tax. There is no session. There is no dirty tracking. There is no lazy loading. Every operation is explicit: you write a query, you execute it, you get a result. What you see is what happens.

For multi-step operations that need to be atomic, `TransactionPlan` provides the closest analogue to an ORM's unit-of-work — but explicitly. You describe the steps upfront, each step can reference the result of a previous one via `StepHandle`, and the plan executes them in a single transaction. It's the ORM's "persist and flush" made visible:

```kotlin
val plan = TransactionPlan()

val legionHandle = plan.add(
    dataAccess.insertInto("legions").values(legionData).asStep().toField<Int>()
)
legionnaires.forEach { l ->
    plan.add(
        dataAccess.insertInto("legionnaire_assignments")
            .values(mapOf("legion_id" to legionHandle.field(), "legionnaire_id" to l.id))
            .asStep().execute()
    )
}
dataAccess.executeTransactionPlan(plan)
```

The cost is that Octavius doesn't write queries for you. You write them. This is a feature.

---

## Why `Map<String, Any?>` and not data classes everywhere

Reading from the database in Octavius produces either a typed result (via `toListOf<T>()`, `toSingleOf<T>()`) or a `Map<String, Any?>` (via `toList()`, `toSingle()`). Writing accepts a `Map<String, Any?>` as parameters.

This might look like a step backward — aren't we back to stringly-typed code? No, for two reasons.

**The Map is not stringly typed.** When Octavius reads a row containing a `@PgComposite` value, the Map doesn't contain a string like `"(Gallia,Lugdunum)"` — it contains an actual `Province` instance, resolved via OID-based type lookup. The raw bytes from PostgreSQL are deserialized into Kotlin types before the Map is constructed. `toDataObject<T>()` then performs a strict, type-checked assignment, not a string-parsing operation. The Map holds typed objects; it just hasn't been shaped into a specific class yet.

**Sometimes you don't want a specific class.** The ORM model assumes you always have a target class in mind. But there are legitimate use cases where the shape of the data isn't known at compile time — dynamic data grids, generic query explorers, reporting engines, configuration-driven UI. In these cases, forcing a class is the wrong abstraction. A `Map<String, Any?>` with fully typed values is exactly what you need, and Octavius produces one without complaint.

The `toDataObject<T>()` / `toDataMap()` pair exists for the common case where you do have a class. The Map-based path exists for when you don't. Both are first-class.

---

## Why JSONB always maps to JsonElement

When Octavius reads a `jsonb` column from PostgreSQL, it always deserializes it as `JsonElement` — never as a specific Kotlin class. This is not an oversight.

By the time Octavius constructs a `Map<String, Any?>` from a row, it has already deserialized each column value using OID-based type resolution. For a composite type, it knows exactly which Kotlin class to instantiate — `@PgComposite` provides that mapping. For a `jsonb` column, there is no such information. The column contains arbitrary JSON; nothing in the column definition or the OID says "this should be a `LandGrant`." Without external type information, the only honest representation is `JsonElement`.

This is the problem `dynamic_dto` solves. A `dynamic_dto` is a PostgreSQL composite type containing two fields: a `text` type name and a `jsonb` payload. When Octavius reads a `dynamic_dto` column, it has the type name — it looks up the corresponding `@DynamicallyMappable` class in the registry and deserializes the payload into that class. The result in the Map is a fully instantiated Kotlin object, not a `JsonElement`.

```
jsonb column                       → JsonElement          (no type information available)
dynamic_dto('legion_status', {...}) → LegionStatus instance (type name embedded in the value)
```

If you control the schema and need polymorphic typed objects in a column, `dynamic_dto` is the mechanism. If you're reading an arbitrary `jsonb` column from a schema you don't control, `JsonElement` is the correct result and you deserialize it yourself with `kotlinx.serialization`.

---

## Why blocking and not coroutines-first

Octavius is built on JDBC, and JDBC is fundamentally blocking. Connection acquisition, query execution, result set iteration — all of it blocks the calling thread. This is not something Octavius can change; it's a property of the protocol.

Rather than hiding this behind a coroutine facade, Octavius is honest about it. Regular terminal methods (`toListOf`, `execute`, etc.) block the calling thread. If you want to move that work off the main thread in a coroutine context, you do it explicitly:

```kotlin
val legions = withContext(Dispatchers.IO) {
    dataAccess.select("*").from("legions").toListOf<Legion>().getOrThrow()
}
```

This is more code than an invisible suspension point, but it's also unambiguous. The reader knows exactly where the thread boundary is, why it's there, and what dispatcher is being used. A library that transparently wraps blocking JDBC calls in coroutines doesn't make them non-blocking — it just hides the blocking behind a `suspend` keyword, which can create a false sense of safety and misplaced confidence in scalability.

The `.async()` builder exists for a specific, narrower use case: fire-and-forget operations initiated from UI code (particularly Compose), where you have a `CoroutineScope` and want to launch a query without awaiting the result inline. It is not a general-purpose coroutine integration.

The `TransactionPlan` and the `transaction { }` block both run on the calling thread using `ThreadLocal` to propagate the connection. This is the same model as Spring's `@Transactional` and JDBC's native transaction management — well-understood, debuggable, and compatible with any thread pool. It does mean that streaming operations inside a transaction require the transaction to remain open for the duration, which is why wrapping an `asStream()` call in `transaction { }` is documented as a requirement, not a recommendation.

---

## Why `DataResult` and not exceptions

Database operations fail. Constraints are violated, connections are lost, queries time out. The question is how failure is communicated to the calling code.

In most Java-heritage libraries, failures throw exceptions. The caller either handles them or lets them propagate. This works, but has a subtle problem: **the type signature of a method that can fail looks identical to the type signature of one that can't.** `fun findLegion(id: Int): Legion` could succeed, return null, or throw — you can't tell from the signature.

Octavius uses `DataResult<T>` — a sealed class with `Success<T>` and `Failure` variants — for all database operations:

- The type signature is honest: `DataResult<Legion?>` tells you this operation can fail.
- Failures are handled at the call site, not somewhere up the stack.
- `getOrThrow()`, `getOrElse {}`, `onSuccess {}`, `onFailure {}` give you a full toolkit for handling results without ceremony.

The distinction between fatal errors (`BuilderException`, `InitializationException`) and execution errors (`DataResult.Failure`) is intentional. A `BuilderException` means you've misused the API — that's a programmer error and should crash loudly. A `ConstraintViolationException` wrapped in a `DataResult.Failure` means a unique constraint was violated — that's a runtime condition your application should handle gracefully.

---

## Why `@param` and not `:param`

Named parameters in Octavius use the `@` prefix rather than the `:` prefix common in libraries like Spring's `NamedParameterJdbcTemplate`.

PostgreSQL uses `:` as a separator in array slice syntax: `array[1:5]`, `array[:n]`, `array[m:]`. This creates genuine ambiguity — tools like DataGrip and DBeaver cannot reliably distinguish `:name` inside `[]` from an array slice bound. With `:param` syntax you simply cannot use a named parameter as an array index:

```sql
-- ❌ :param — ambiguous, breaks in IDEs, can't use param as slice bound
SELECT service_record[:index] FROM legionnaires WHERE name = :name;

-- ✅ @param — unambiguous, works everywhere
SELECT service_record[@index] FROM legionnaires WHERE name = @name;
```

`@` was never PostgreSQL's to begin with.

---

## Dynamic queries are just strings

One area where every query abstraction eventually struggles is dynamic query construction — queries where the shape changes at runtime based on user input, filter state, or configuration. A search form with optional filters. A data grid with sortable columns. A report builder where the user picks dimensions.

In Hibernate's Criteria API, this means building a tree of predicate objects — a parallel Java object model for SQL that is verbose, hard to read, and often requires more code than the SQL it replaces. In JOOQ it's more tolerable, but still a DSL within a DSL.

In Octavius, dynamic queries are just string concatenation — because queries are already strings. `QueryFragment` carries both a SQL snippet and its parameters together, and `listOfNotNull` keeps the construction clean:

```kotlin
fun searchLegions(
    province: String?,
    status: LegionStatus?,
    minStrength: Int?,
    sortBy: String = "name",
    page: Long = 0
): DataResult<List<Legion>> {

    val filters = listOfNotNull(
        province?.let    { QueryFragment("province = @province",       "province"     to it) },
        status?.let      { QueryFragment("status = @status",           "status"       to it) },
        minStrength?.let { QueryFragment("strength >= @min_strength",  "min_strength" to it) }
    )

    return dataAccess.select("*")
        .from("legions")
        .where(filters)
        .orderBy(sortBy)
        .page(page, 20)
        .toListOf<Legion>()
}
```

`QueryFragment` instances can be defined once and reused across multiple queries — useful when the same filter logic appears in a search endpoint, a count query, and a CSV export.

---

## The line Octavius deliberately does not cross

Octavius does not generate schema migrations. It does not inspect your data classes and infer what your database schema should look like. Schema management belongs to your migration tool of choice (Flyway, Liquibase, or plain SQL scripts) — Octavius's `:flyway-integration` module is an optional convenience, not a core feature.

This reflects a broader principle: **the database schema is the source of truth, not the Kotlin code.** Your data classes describe how your application sees the data. Your migration files describe what the data actually is. These are related but separate concerns, and conflating them — letting the ORM generate your schema from your objects — leads to schemas that serve the ORM's needs rather than the database's.

---

## When Octavius is not the right tool

**If you need database portability**, Octavius is the wrong choice. It uses PostgreSQL-specific types, syntax, and features throughout. There is no abstraction layer that would make switching to MySQL painless.

**If your team is not comfortable writing SQL**, the lack of a type-safe DSL will feel like a gap rather than a feature. JOOQ might be a better fit — it generates type-safe query builders from your actual schema and catches many errors at compile time.

**If you're building a simple CRUD application** where the data model maps closely to the API model and there's little complex query logic, a full ORM like Hibernate might genuinely reduce boilerplate without the costs described above.

Octavius earns its place in systems where the database is doing real work: complex queries, PostgreSQL-specific types, LISTEN/NOTIFY, composite types as return values from functions, array operations. The more you use PostgreSQL as a platform rather than just a row store, the more this approach pays off.