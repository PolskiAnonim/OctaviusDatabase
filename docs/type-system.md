# Type System

Octavius Database provides automatic bidirectional mapping between PostgreSQL and Kotlin types. This includes support for:

- **Standard types** - primitives, dates, JSON, arrays.
- **Custom types** - statically mapped ENUMs and COMPOSITE types.
- **Dynamic types** - polymorphic storage and JSONB serialization via `dynamic_dto`.
- **High-performance collections** - automatic parameter flattening to bypass JDBC limits.

---

## Table of Contents

1. [Standard Type Mapping](#standard-type-mapping)
    - [Arrays](#arrays)
    - [Infinity Values for Date/Time](#infinity-values-for-datetime)
    - [Duration and Interval Rules](#duration-and-interval-rules)
2. [Collections & Parameter Flattening](#collections--parameter-flattening)
    - [List vs Array](#list-vs-array)
3. [Type Inference & Safety](#type-inference--safety)
    - [Default Type Resolution](#default-type-resolution)
    - [Explicit Type Casts (PgTyped)](#explicit-type-casts-pgtyped)
4. [Static Custom Types](#static-custom-types)
    - [@PgEnum](#pgenum)
    - [@PgComposite](#pgcomposite)
5. [Dynamic Types (dynamic_dto)](#dynamic-types-dynamic_dto)
    - [@DynamicallyMappable](#dynamicallymappable)
    - [Enum Serialization in dynamic_dto](#enum-serialization-in-dynamic_dto)
    - [Helper Serializers](#helper-serializers)
6. [Object Conversion Utilities](#object-conversion-utilities)

---

## Standard Type Mapping

Automatic conversion works out-of-the-box for the following types. Note that if a Kotlin type maps to multiple PostgreSQL types, Octavius uses a **priority-based inference** (see [Type Inference](#type-inference--safety)).

| PostgreSQL                  | Kotlin          | Notes                                            |
|-----------------------------|-----------------|--------------------------------------------------|
| `int2`, `smallserial`       | `Short`         |                                                  |
| `int4`, `serial`            | `Int`           |                                                  |
| `int8`, `bigserial`         | `Long`          |                                                  |
| `float4`                    | `Float`         |                                                  |
| `float8`                    | `Double`        |                                                  |
| `numeric`                   | `BigDecimal`    |                                                  |
| `text`, `varchar`, `bpchar` | `String`        |                                                  |
| `bool`                      | `Boolean`       |                                                  |
| `uuid`                      | `UUID`          | `java.util.UUID`                                 |
| `bytea`                     | `ByteArray`     |                                                  |
| `jsonb`, `json`             | `JsonElement`   | `kotlinx.serialization.json`                     |
| `void`                      | `Unit`          | Return type of void functions (e.g. `pg_notify`) |
| `date`                      | `LocalDate`     | `kotlinx.datetime` <sup>1</sup>                  |
| `time`                      | `LocalTime`     | `kotlinx.datetime`                               |
| `timetz`                    | `OffsetTime`    | `java.time`                                      |
| `timestamp`                 | `LocalDateTime` | `kotlinx.datetime` <sup>1</sup>                  |
| `timestamptz`               | `Instant`       | `kotlin.time` <sup>1</sup>                       |
| `interval`                  | `Duration`      | `kotlin.time` <sup>2</sup>                       |

### Arrays

Arrays of all standard types are supported and naturally map to `List<T>`:

| PostgreSQL | Kotlin         |
|------------|----------------|
| `int4[]`   | `List<Int>`    |
| `text[]`   | `List<String>` |
| `uuid[]`   | `List<UUID>`   |
| etc.       | `List<T>`      |

### Infinity Values for Date/Time

<sup>1</sup> **PostgreSQL special values** (`infinity`, `-infinity`) are fully supported for date and timestamp types using provided constants:

| PostgreSQL Type | Special Values          | Kotlin Constants                                             |
|-----------------|-------------------------|--------------------------------------------------------------|
| `date`          | `infinity`, `-infinity` | `LocalDate.DISTANT_FUTURE`, `LocalDate.DISTANT_PAST`         |
| `timestamp`     | `infinity`, `-infinity` | `LocalDateTime.DISTANT_FUTURE`, `LocalDateTime.DISTANT_PAST` |
| `timestamptz`   | `infinity`, `-infinity` | `Instant.DISTANT_FUTURE`, `Instant.DISTANT_PAST`             |

**Usage Example:**

```kotlin
import org.octavius.data.type.DISTANT_FUTURE

dataAccess.insertInto("contracts")
    .values(listOf("start_date", "end_date"))
    .execute(
        "start_date" to LocalDate.parse("2024-01-01"),
        "end_date" to LocalDate.DISTANT_FUTURE  // Stored as 'infinity'
    )
```

### Duration and Interval Rules

<sup>2</sup> **PostgreSQL `INTERVAL` type** maps to Kotlin's `Duration`.

**Infinity Values:**
- `Duration.INFINITE` → `'infinity'`
- `-Duration.INFINITE` → `'-infinity'`

**Conversion Logic:**
PostgreSQL `INTERVAL` values (without a specific date anchor point) are converted to Kotlin `Duration` (seconds) using these fixed rules:
- 1 year = 365.25 days (= 31,557,600 seconds)
- 1 month = 30 days (= 2,592,000 seconds)
- 1 day = 86,400 seconds

*Example:* `'1 year 2 months 5 days'` is converted to exactly `37,173,600` seconds.

**Important notes:**
- These conversion rules apply when converting PostgreSQL intervals **without a specific date anchor point**
- For date arithmetic with actual calendar dates, use native PostgreSQL date functions within your SQL queries
- The conversion preserves precision but normalizes to total seconds in Kotlin's `Duration` type

---

## Collections & Parameter Flattening

When collections, arrays, or composite types are passed as named parameters (`:param`), Octavius **serializes** them into a single PostgreSQL text-format literal. This is sent as a **single JDBC parameter**.

| Kotlin value                             | SQL fragment    | JDBC params consumed |
|------------------------------------------|-----------------|----------------------|
| `"hello"` (scalar)                       | `?::text`       | **1**                |
| `Address("Main St", "NYC")`              | `?::address`    | **1**                |
| `listOf(1, 2, 3)`                        | `?::int4[]`     | **1**                |
| `listOf(addr1, addr2)` (composite array) | `?::address[]`  | **1**                |
| `arrayOf("a", "b", "c")` (typed array)   | `?`             | **1**                |

### List vs Array

- **`List<T>` (Recommended):** Uses Octavius serialization (text literal like `(value1,value2)` or `{1,2,3}`). Supports **all types**, including custom `@PgComposite` and `@PgEnum`.
- **`Array<T>` (Native):** Uses native PgJDBC array protocol. Slightly faster for large collections of primitive types, but **does not support custom types**.

---

## Type Inference & Safety

### OID-Based Result Mapping

When reading results from the database, Octavius uses PostgreSQL's internal **Object Identifiers (OIDs)** rather than string-based type names. The JDBC `ResultSet` metadata provides the exact OID for each column. Octavius cross-references this OID with its internal `TypeRegistry` to map the incoming data directly to the correct Kotlin class. 

This OID-based resolution:
- **Eliminates Ambiguity:** Bypasses issues with identical type names existing in multiple schemas.
- **Boosts Performance:** OID lookups are extremely fast integer lookups compared to string parsing.
- **Guarantees Type Safety:** Deeply nested composites, arrays, and enums are consistently deserialized to their exact Kotlin representations.

### Default Type Resolution

When mapping a Kotlin value to a PostgreSQL type, Octavius defaults to the **first matching entry** in the internal registry:
- `JsonElement` → Defaults to **`jsonb`** (not `json`).
- `String` → Defaults to **`text`** (not `varchar` or `char`).

For `List<T>`, Octavius infers the type by inspecting the **first non-null element**. If the list is empty or contains only nulls, it defaults to `text[]`.

### Explicit Type Casts (PgTyped)

For ambiguous cases (like empty lists) or to optimize query plans, use `.withPgType()` to force a specific cast.

```kotlin
// Force JSON instead of the default JSONB
val data = jsonElement.withPgType("json")

// Prevent inference issues with empty/null lists
val ids = listOf<Int?>(null).withPgType("int4", isArray = true)

// Safe usage in queries
dataAccess.rawQuery("SELECT * FROM users WHERE id = ANY(:ids)")
    .toListOf<User>("ids" to listOf(1, 2, 3).withPgType(PgStandardType.INT4_ARRAY))
```

### Type Resolution Priority

If a class has multiple annotations, explicit wrappers dictate the serialization path:

| Wrapper Used                                               | Behavior                                                       |
|------------------------------------------------------------|----------------------------------------------------------------|
| `value.withPgType("name", schema = "...", isArray = true)` | Forces explicit path (`?::"schema"."name"[]` using `PGobject`) |
| `DynamicDto.from(value)`                                   | Forces `@DynamicallyMappable` path (`dynamic_dto(...)`)        |
| None (raw value)                                           | Follows `DynamicDtoSerializationStrategy` configuration.       |

---

## Static Custom Types

### @PgEnum

Maps a Kotlin `enum class` to a PostgreSQL `ENUM`.

**Annotation Parameters:**
| Parameter          | Default            | Description                                    |
|--------------------|--------------------|------------------------------------------------|
| `name`             | `""`               | PostgreSQL type name (auto-generated if empty) |
| `schema`           | `""`               | Explicit PostgreSQL schema name                |
| `pgConvention`     | `SNAKE_CASE_UPPER` | How values are stored in PostgreSQL            |
| `kotlinConvention` | `PASCAL_CASE`      | How values are defined in Kotlin               |

**Example:**
```kotlin
@PgEnum(
    schema = "public",                               // Explicit schema
    pgConvention = CaseConvention.SNAKE_CASE_LOWER,  // stored as 'credit_card'
    kotlinConvention = CaseConvention.PASCAL_CASE    // defined as CreditCard
) // name defaults to transaction_type
enum class TransactionType { CreditCard, BankTransfer }

// PostgreSQL Migration: 
// CREATE TYPE public.transaction_type AS ENUM ('credit_card', 'bank_transfer');
```

### @PgComposite

Maps a Kotlin `data class` to a PostgreSQL `COMPOSITE` type.

**Annotation Parameters:**
| Parameter          | Default            | Description                                    |
|--------------------|--------------------|------------------------------------------------|
| `name`             | `""`               | PostgreSQL type name (auto-generated if empty) |
| `schema`           | `""`               | Explicit PostgreSQL schema name                |
| `mapper`           | `Default...`       | Optional custom `PgCompositeMapper`            |

**Example:**
```kotlin
@PgComposite(name = "address_type") // explicit name
data class Address(val street: String, val city: String)

// PostgreSQL Migration: 
// CREATE TYPE address AS (street TEXT, city TEXT);
```

Composites fully support nesting and arrays:
```kotlin
@PgComposite // name defaults to geo_location
data class GeoLocation(val lat: Double, val lng: Double)

@PgComposite(schema = "public") // name defaults to address_wtih_geo
data class AddressWithGeo(val street: String, val location: GeoLocation)

data class Company(val id: Int, val branches: List<AddressWithGeo>)
```

---

## Dynamic Types (dynamic_dto)

Enables dynamic type mapping via the `dynamic_dto` PostgreSQL type. This allows polymorphic storage (different types in one column) and ad-hoc mapping without defining COMPOSITE types in your schema.

### @DynamicallyMappable

Annotated classes must also be `@Serializable` (`kotlinx.serialization`).

```kotlin
@DynamicallyMappable(typeName = "text_note")
@Serializable
data class TextNote(val content: String)

@DynamicallyMappable(typeName = "image_note")
@Serializable
data class ImageNote(val url: String)
```

**Polymorphic Storage Example:**
```sql
-- PostgreSQL Table
CREATE TABLE notebooks (
    id SERIAL PRIMARY KEY,
    notes dynamic_dto[]  -- Can contain BOTH TextNote and ImageNote
);
```
```kotlin
// Read back with automatic deserialization
data class Notebook(val id: Int, val notes: List<Any>)

val notebook = dataAccess.select("id", "notes")
    .from("notebooks")
    .toSingleOf<Notebook>()
// notes contains: [TextNote("Hello"), ImageNote("https://...")]
```

### Enum Serialization in dynamic_dto

When using enums inside `@DynamicallyMappable` classes, `kotlinx.serialization` defaults to outputting the exact Kotlin enum name. To match PostgreSQL conventions inside the JSON payload, you **must** use `DynamicDtoEnumSerializer`.

```kotlin
// 1. Create a serializer
object OrderStatusSerializer : DynamicDtoEnumSerializer<OrderStatus>(
    serialName = "OrderStatus",
    entries = OrderStatus.entries,
    pgConvention = CaseConvention.SNAKE_CASE_UPPER
)

// 2. Attach it to your Enum
@Serializable(with = OrderStatusSerializer::class)
@PgEnum(pgConvention = CaseConvention.SNAKE_CASE_UPPER)
enum class OrderStatus { Pending, InProgress }

// 3. Use in DTO
@DynamicallyMappable(typeName = "order_update")
@Serializable
data class OrderUpdate(val newStatus: OrderStatus)
// JSON Output: {"newStatus": "IN_PROGRESS"} (instead of "InProgress")
```
#### Why Is This Necessary?

This serializer is required because of how kotlinx.serialization works. When you have an enum property inside a `@Serializable` class, the compiler plugin generates a serializer for that class at compile time. For enum properties, **it uses the default enum serializer** which simply outputs the Kotlin enum name (e.g., `"InProgress"`).

The library cannot intercept or modify this behavior internally — the serializer is already baked into the generated code. The only way to change how the enum is serialized is to explicitly specify a custom serializer using `@Serializable(with = ...)` on the enum class itself.

### Helper Serializers

Octavius provides `BigDecimalAsNumberSerializer` to serialize `java.math.BigDecimal` as an unquoted JSON number literal, preserving full numeric precision in JSONB.

```kotlin
import org.octavius.data.helper.BigDecimalAsNumberSerializer

@DynamicallyMappable(typeName = "price_data")
@Serializable
data class PriceData(
    @Serializable(with = BigDecimalAsNumberSerializer::class)
    val price: BigDecimal
)
// JSONB Output: {"price": 199.99} (number, not string)
```

---

## Object Conversion Utilities

For overriding the default `snake_case` ↔ `camelCase` mapping for individual properties, use the `@MapKey` annotation.

Utilities like `toDataObject()` and `toMap()` are available to convert between data classes and maps. See [Data Mapping](data-mapping.md) documentation for full details and CRUD patterns.
