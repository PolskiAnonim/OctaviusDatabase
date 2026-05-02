# Parameter Handling

*The Roman census left nothing to inference. Every citizen was recorded by name, tribe, property class, and military obligation — each value in its proper column, each column in its proper tablet. A scribe who wrote "approximately equestrian" or "probably Cornelia tribe" would not last long in the tabularium. Octavius parameters follow the same discipline: named, typed, and delivered without ambiguity.*

Octavius Database uses a custom SQL parser to handle named parameters, providing a type-safe and developer-friendly way to pass values to your queries.

## Table of Contents

- [Named Parameters Syntax](#named-parameters-syntax)
- [Parameter Expansion & Conversion](#parameter-expansion--conversion)
- [Type Inference & Safety](#type-inference--safety)
- [Collections & Parameter Flattening](#collections--parameter-flattening)
- [High-Performance Bulk Operations (unnest)](#high-performance-bulk-operations-unnest)
- [Automatic Placeholder Generation](#automatic-placeholder-generation)

---

## Named Parameters Syntax

All named parameters in Octavius Database must start with the `@` prefix.

```kotlin
// Retrieve a census record by citizen ID
val result = dataAccess.rawQuery("SELECT * FROM citizens WHERE id = @id")
    .toSingle(mapOf("id" to 123))
```

### Why `@` and not `:`?

While many libraries use the colon (`:`) prefix for named parameters, Octavius Database uses `@` to avoid ambiguity with PostgreSQL's **array slicing** and **type casting** syntax.

In PostgreSQL, you can slice an array using `[start:end]`. Omitting either bound is valid — `[1:]` means "from index 1 to the end", and `[:n]` means "from the beginning to index n (where n is a column/alias)".

With the colon prefix, consider a query that pages through a legionnaire's service record:
```sql
-- Colon prefix — ambiguous
SELECT service_record[1:10], campaign_log[:page_size]
FROM legionnaires WHERE name = :name;
```

The parser cannot reliably distinguish whether `:page_size` is:
1. A named parameter named `page_size`.
2. A PostgreSQL array slice from the beginning to a column or alias named `page_size`.

With `@`, the intent is unambiguous — and crucially, you can use a parameter *as a slice bound* without any conflict:
```sql
-- @ prefix — unambiguous
SELECT service_record[@offset:@offset+10], campaign_log[:@pageSize]
FROM legionnaires WHERE name = @name;
```

Here `[:@pageSize]` is clearly a PostgreSQL slice from the beginning to the value of `@pageSize`. The `:` belongs to PostgreSQL; the `@` belongs to Octavius.

### What about the `?` operator?

PostgreSQL uses the `?` character as an operator for `jsonb` types (e.g., `?`, `?|`, `?&`). However, JDBC uses `?` as a positional parameter placeholder.

Octavius Database automatically handles this for you. Its SQL parser identifies literal question marks that are not inside strings or comments and escapes them as `??` in the final SQL sent to the database. This allows you to use JSONB operators directly in your queries without manual escaping:

```kotlin
// Find all records where the 'data' JSONB column contains the key 'priority'
val result = dataAccess.rawQuery("SELECT * FROM tasks WHERE data ? 'priority'")
    .toList()
```

The parser is smart enough to **not** escape question marks inside:
- Single-quoted strings (`'Et tu, Brute?'`)
- Dollar-quoted strings (`$$Quid agis?$$`)
- Comments (`-- Quo vadis?`, `/* Veni, vidi, vici? */`)

---

## Parameter Expansion & Conversion

When you pass a map of parameters to a query, Octavius Database performs a multi-step conversion process for each value before sending it to the database.

### 1. Unwrapping `PgTyped`
If a value is wrapped in `PgTyped`, it is first unwrapped to get the raw value and the explicit PostgreSQL type intended for the cast. `PgTyped` layers are processed recursively.

### 2. Dynamic DTO Serialization
If the value is not a standard type and is marked with `@DynamicallyMappable` (and the `DynamicDtoSerializationStrategy` allows it), Octavius will attempt to serialize it as a `dynamic_dto` (a composite type containing the type name and JSONB payload).

### 3. Standard Type Mapping
If the value matches a standard Kotlin type (e.g., `String`, `Int`, `Long`, `Boolean`, `UUID`, `Instant`, `LocalDate`), it is converted using the `StandardTypeMappingRegistry`. These types are mapped directly to their corresponding PostgreSQL types with appropriate casts (e.g., `?::int4`, `?::text`).

### 4. Specialized Types
If none of the above apply, Octavius handles specialized types:
- **Collections (List):** Serialized into a PostgreSQL array literal.
- **Enums:** Serialized to their string representation (using `@PgEnum` case rules) and cast to the specific PostgreSQL ENUM type.
- **Data Classes (Composites):** Serialized into a PostgreSQL composite literal and cast to the specific composite type.
- **Arrays:** Passed directly to the JDBC driver if the component type is a simple JVM type.

### 5. Type Casting
Octavius automatically appends type casts (`::type`) to the generated positional placeholders (`?`) in the final SQL. This ensures that PostgreSQL correctly identifies the types, which is especially important for:
- Avoiding "could not determine data type of parameter" errors.
- Correct function/procedure resolution (e.g., resolving the correct `calculate_tribute` overload).
- Performance (allowing the optimizer to use indexes correctly).

---

## Type Inference & Safety

### Default Type Resolution

When mapping a Kotlin value to a PostgreSQL type, Octavius defaults to the **first matching entry** in the internal registry:
- `JsonElement` → Defaults to **`jsonb`** (not `json`).
- `String` → Defaults to **`text`** (not `varchar` or `char`).

For `List<T>`, Octavius infers the type by inspecting the **first non-null element**. If the list is empty or contains only nulls, it defaults to `text[]`.

### Explicit Type Casts (PgTyped)

For ambiguous cases (mainly nulls and empty/nullable lists) or to optimize query plans, use `.withPgType()` to force a specific cast.

```kotlin
// Store province census notes as JSON, not the default JSONB
val censusNotes = provinceReport.withPgType("json")

// List with null value of tribute IDs — force int4[] so PostgreSQL doesn't default to text[]
val noExemptions = listOf<Int?>(null).withPgType("int4", isArray = true)

// Fetch legionnaires by a known set of IDs
dataAccess.rawQuery("SELECT * FROM legionnaires WHERE id = ANY(@ids)")
    .toListOf<Legionnaire>("ids" to listOf(3, 7, 14).withPgType(PgStandardType.INT4_ARRAY))
```

### Type Resolution Priority

If a class has multiple annotations, explicit wrappers dictate the serialization path:

| Wrapper Used                                               | Behavior                                                       |
|------------------------------------------------------------|----------------------------------------------------------------|
| `value.withPgType("name", schema = "...", isArray = true)` | Forces explicit path (`?::"schema"."name"[]` using `PGobject`) |
| `DynamicDto.from(value)`                                   | Forces `@DynamicallyMappable` path (`dynamic_dto(...)`)        |
| None (raw value)                                           | Follows `DynamicDtoSerializationStrategy` configuration.       |

---

## Collections & Parameter Flattening

When collections, arrays, or composite types are passed as named parameters (`@param`), Octavius **serializes** them into a single PostgreSQL text-format literal. This is sent as a **single JDBC parameter**.

| Kotlin value                             | SQL fragment     | JDBC params consumed |
|------------------------------------------|------------------|----------------------|
| `"Cornelia"` (scalar)                    | `?::text`        | **1**                |
| `Province("Gallia", "Lugdunum")`         | `?::province`    | **1**                |
| `listOf(1, 2, 3)`                        | `?::int4[]`      | **1**                |
| `listOf(prov1, prov2)` (composite array) | `?::province[]`  | **1**                |
| `arrayOf("a", "b", "c")` (typed array)   | `?`              | **1**                |

### List vs Array

- **`List<T>` (Recommended):** Uses Octavius serialization (text literal like `(value1,value2)` or `{1,2,3}`). Supports **all types**, including custom `@PgComposite` and `@PgEnum`.
- **`Array<T>` (Native):** Uses native PgJDBC array protocol. Slightly faster for large collections of primitive types, but **does not support custom types**.

> **Note on Nesting:** Mixed nesting like `List<Array<T>>` or `Array<List<T>>` is **not supported**. Combining these distinct serialization paths would require exhaustive type checking and complex hybrid logic for minimal benefit. Use consistent nesting (e.g., `List<List<T>>`) for multi-dimensional data.

---

## High-Performance Bulk Operations (unnest)

For inserting or updating a large number of rows, PostgreSQL's `unnest` function is often superior to standard JDBC batching. Octavius supports three main strategies for bulk data delivery, allowing you to choose the best balance between ergonomics and performance.

### Strategy 1: Composite Unnest (Ergonomic)

Pass a list of `@PgComposite` objects (or `dynamic_dto`). This is the cleanest approach as it uses a single parameter.

```kotlin
@PgComposite(name = "census_entry")
data class CensusEntry(val name: String, val age: Int)

dataAccess.rawQuery("""
    INSERT INTO citizens (name, age)
    SELECT (r).name, (r).age
    FROM (SELECT unnest(@data) as r) sub
""").execute("data" to entries)
```

### Strategy 2: Parallel Lists (Efficient)

Pass multiple Kotlin `List<T>` objects. It's faster than composites because PostgreSQL iterates through flat arrays in memory without the overhead of "unpacking" composite fields. Note that lists are serialized into text literals (e.g., `'{1,2,3}'`) as described in [List vs Array](#list-vs-array).

```kotlin
dataAccess.rawQuery("INSERT INTO citizens (name, age) SELECT * FROM unnest(@names, @ages)")
    .execute("names" to entries.map { it.name }, "ages" to entries.map { it.age })
```

### Strategy 3: Parallel Arrays (Absolute Maximum Performance)

By converting Kotlin `List` to native JVM `Array` using `.toTypedArray()`, you bypass text serialization entirely. The JDBC driver passes these directly to the database using the native binary protocol.

```kotlin
// Prepare parallel arrays
val names = entries.map { it.name }.toTypedArray()
val ages = entries.map { it.age }.toTypedArray()

dataAccess.rawQuery("INSERT INTO citizens (name, age) SELECT * FROM unnest(@names, @ages)")
    .execute("names" to names, "ages" to ages)
```

**Technical advantages of this approach:**
- **Zero Parsing:** PostgreSQL iterates through flat arrays in memory without accessing composite fields.
- **Binary Efficiency:** Strategy 3 uses the native JDBC binary protocol, avoiding the overhead of converting and parsing large text-format literals.
- **Consistency:** Unlike standard JDBC batching, this method provides consistent high performance regardless of JDBC driver settings (like `reWriteBatchedInserts`).
- **Low Overhead:** The framework's internal mapping and SQL parsing add negligible latency, staying very close to raw JDBC performance.

---

## Automatic Placeholder Generation

Builders like `InsertQueryBuilder` and `UpdateQueryBuilder` provide helper methods that generate named parameters for you automatically based on column names (e.g., `values(listOf("name"))` generates `@name`).

For detailed examples and usage, see [Query Builders: Auto-Generated Placeholders](query-builders.md#auto-generated-placeholders).
