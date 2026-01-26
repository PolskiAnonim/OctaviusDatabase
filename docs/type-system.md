# Type System

Octavius Database provides automatic bidirectional mapping between PostgreSQL and Kotlin types. This includes support for:

- **Standard types** - primitives, dates, JSON, arrays
- **Custom types** - ENUMs, COMPOSITE types
- **Dynamic types** - polymorphic storage via `dynamic_dto`

## Table of Contents

- [Standard Type Mapping](#standard-type-mapping)
- [@PgEnum](#pgenum)
- [@PgComposite](#pgcomposite)
- [@DynamicallyMappable](#dynamicallymappable)
- [PgTyped - Explicit Type Casts](#pgtyped---explicit-type-casts)
- [@MapKey - Custom Property Mapping](#mapkey---custom-property-mapping)
- [Object Conversion Utilities](#object-conversion-utilities)
- [Enum Serialization in dynamic_dto](#enum-serialization-in-dynamic_dto)
- [Helper Serializers](#helper-serializers)

---

## Standard Type Mapping

Automatic conversion between PostgreSQL and Kotlin types:

| PostgreSQL                | Kotlin          | Notes                           |
|---------------------------|-----------------|---------------------------------|
| `int2`, `smallserial`     | `Short`         |                                 |
| `int4`, `serial`          | `Int`           |                                 |
| `int8`, `bigserial`       | `Long`          |                                 |
| `float4`                  | `Float`         |                                 |
| `float8`                  | `Double`        |                                 |
| `numeric`                 | `BigDecimal`    |                                 |
| `text`, `varchar`, `char` | `String`        |                                 |
| `bool`                    | `Boolean`       |                                 |
| `uuid`                    | `UUID`          | `java.util.UUID`                |
| `bytea`                   | `ByteArray`     |                                 |
| `json`, `jsonb`           | `JsonElement`   | `kotlinx.serialization.json`    |
| `date`                    | `LocalDate`     | `kotlinx.datetime` <sup>1</sup> |
| `time`                    | `LocalTime`     | `kotlinx.datetime`              |
| `timetz`                  | `OffsetTime`    | `java.time`                     |
| `timestamp`               | `LocalDateTime` | `kotlinx.datetime` <sup>1</sup> |
| `timestamptz`             | `Instant`       | `kotlin.time` <sup>1</sup>      |
| `interval`                | `Duration`      | `kotlin.time` <sup>2</sup>      |

### Arrays

Arrays of all standard types are supported and map to `List<T>`:

| PostgreSQL | Kotlin |
|------------|--------|
| `int4[]` | `List<Int>` |
| `text[]` | `List<String>` |
| `uuid[]` | `List<UUID>` |
| etc. | `List<T>` |

### Infinity Values for Date/Time Types

<sup>1</sup> **PostgreSQL special values** (`infinity`, `-infinity`) are supported for date and timestamp types:

| PostgreSQL Type | Special Values          | Kotlin Constants                                             |
|-----------------|-------------------------|--------------------------------------------------------------|
| `date`          | `infinity`, `-infinity` | `LocalDate.DISTANT_FUTURE`, `LocalDate.DISTANT_PAST`         |
| `timestamp`     | `infinity`, `-infinity` | `LocalDateTime.DISTANT_FUTURE`, `LocalDateTime.DISTANT_PAST` |
| `timestamptz`   | `infinity`, `-infinity` | `Instant.DISTANT_FUTURE`, `Instant.DISTANT_PAST`             |

**Usage example:**

```kotlin
data class Contract(val startDate: LocalDate, val endDate: LocalDate)

// Inserting a contract with no end date (infinite)
dataAccess.insertInto("contracts")
    .values(listOf("start_date", "end_date"))
    .execute(
        "start_date" to LocalDate.parse("2024-01-01"),
        "end_date" to LocalDate.DISTANT_FUTURE  // Stored as 'infinity' in PostgreSQL
    )

// Reading back
val contract = dataAccess.select("start_date", "end_date")
    .from("contracts")
    .toSingleOf<Contract>()
    .getOrThrow()!!

// contract.endDate == LocalDate.DISTANT_FUTURE
```

**Constants provided by Octavius:**

```kotlin
import org.octavius.data.type.DISTANT_PAST
import org.octavius.data.type.DISTANT_FUTURE

LocalDate.DISTANT_PAST      // java.time.LocalDate.MIN → '-infinity'
LocalDate.DISTANT_FUTURE    // java.time.LocalDate.MAX → 'infinity'

LocalDateTime.DISTANT_PAST      // java.time.LocalDateTime.MIN → '-infinity'
LocalDateTime.DISTANT_FUTURE    // java.time.LocalDateTime.MAX → 'infinity'

Instant.DISTANT_PAST      // java.time.Instant.MIN → '-infinity'
Instant.DISTANT_FUTURE    // java.time.Instant.MAX → 'infinity'
```

### Duration and Interval Conversion Rules

<sup>2</sup> **PostgreSQL `INTERVAL` type** maps to Kotlin's `Duration` with full support for infinity values and precise conversion rules:

**Infinity values:**

```kotlin
Duration.INFINITE        // Stored as 'infinity' in PostgreSQL
-Duration.INFINITE       // Stored as '-infinity' in PostgreSQL
```

**Conversion rules from PostgreSQL to Kotlin:**

PostgreSQL `INTERVAL` values without specific date context follow these conversion rules:

| Unit    | Conversion Rule                    |
|---------|------------------------------------|
| 1 day   | 86,400 seconds                     |
| 1 month | 30 days (= 2,592,000 seconds)      |
| 1 year  | 365.25 days (= 31,557,600 seconds) |
| 1 year  | 12 months                          |

**Example conversions:**

```sql
-- PostgreSQL INTERVAL
'1 year 2 months 5 days 3 hours 30 minutes 15 seconds'
```

Converts to Kotlin `Duration`:
- Years: `1 * 365.25 * 86400 = 31,557,600 seconds`
- Months: `2 * 30 * 86400 = 5,184,000 seconds`
- Days: `5 * 86400 = 432,000 seconds`
- Hours: `3 * 3600 = 10,800 seconds`
- Minutes: `30 * 60 = 1,800 seconds`
- Seconds: `15 seconds`
- **Total: 37,186,215 seconds** (≈ 1.18 years as a duration)

```kotlin
// Using Duration values
data class Task(val estimatedDuration: Duration, val actualDuration: Duration?)

dataAccess.insertInto("tasks")
    .values(listOf("estimated_duration", "actual_duration"))
    .execute(
        "estimated_duration" to 5.hours + 30.minutes,
        "actual_duration" to null
    )

// Infinite duration example
dataAccess.update("subscriptions")
    .setValue("valid_until")
    .where("id = :id")
    .execute("valid_until" to Duration.INFINITE, "id" to subscriptionId)
```

**Important notes:**
- These conversion rules apply when converting PostgreSQL intervals **without a specific date anchor point**
- For date arithmetic with actual calendar dates, use native PostgreSQL date functions within your SQL queries
- The conversion preserves precision but normalizes to total seconds in Kotlin's `Duration` type

---

## @PgEnum

Maps a Kotlin `enum class` to a PostgreSQL `ENUM` type.

### Annotation Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | `""` | PostgreSQL type name (auto-generated if empty) |
| `pgConvention` | `CaseConvention` | `SNAKE_CASE_UPPER` | How values are stored in PostgreSQL |
| `kotlinConvention` | `CaseConvention` | `PASCAL_CASE` | How values are defined in Kotlin |

### Case Conventions

| Convention | Example |
|------------|---------|
| `SNAKE_CASE_UPPER` | `MY_VALUE` |
| `SNAKE_CASE_LOWER` | `my_value` |
| `PASCAL_CASE` | `MyValue` |
| `CAMEL_CASE` | `myValue` |

### Basic Usage

```kotlin
// Kotlin enum
@PgEnum
enum class OrderStatus { Pending, Processing, Shipped, Delivered }

// PostgreSQL type (create this in your migration)
CREATE TYPE order_status AS ENUM ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED');
```

**Naming rules:**
- Class name `OrderStatus` → PostgreSQL type `order_status` (CamelCase → snake_case)
- Enum value `Pending` → PostgreSQL value `'PENDING'` (PascalCase → SNAKE_CASE_UPPER)

### Custom Naming

```kotlin
// Different PostgreSQL type name
@PgEnum(name = "payment_type_enum")
enum class PaymentMethod { CreditCard, BankTransfer, Cash }

// Different value conventions
@PgEnum(
    pgConvention = CaseConvention.SNAKE_CASE_LOWER,  // stored as 'credit_card'
    kotlinConvention = CaseConvention.PASCAL_CASE    // defined as CreditCard
)
enum class TransactionType { CreditCard, BankTransfer }

// PostgreSQL:
CREATE TYPE transaction_type AS ENUM ('credit_card', 'bank_transfer');
```

### Usage in Queries

```kotlin
// In data classes
data class Order(val id: Int, val status: OrderStatus)

// Automatic conversion
val orders = dataAccess.select("id", "status")
    .from("orders")
    .toListOf<Order>()

// As parameter
dataAccess.update("orders")
    .setValue("status")
    .where("id = :id")
    .execute("status" to OrderStatus.Shipped, "id" to orderId)
```

---

## @PgComposite

Maps a Kotlin `data class` to a PostgreSQL `COMPOSITE` type.

### Annotation Parameters

| Parameter | Type     | Default | Description                                    |
|-----------|----------|---------|------------------------------------------------|
| `name`    | `String` | `""`    | PostgreSQL type name (auto-generated if empty) |

### Basic Usage

```kotlin
// Kotlin data class
@PgComposite
data class Address(
    val street: String,
    val city: String,
    val zipCode: String
)

// PostgreSQL type (create this in your migration)
CREATE TYPE address AS (
    street TEXT,
    city TEXT,
    zip_code TEXT  -- Note: snake_case in PostgreSQL
);
```

### Custom Type Name

```kotlin
@PgComposite(name = "shipping_address_type")
data class ShippingAddress(
    val street: String,
    val city: String,
    val country: String
)
```

### Usage in Queries

```kotlin
// In data classes
data class User(val id: Int, val name: String, val address: Address)

// Reading composite types
val users = dataAccess.select("id", "name", "address")
    .from("users")
    .toListOf<User>()

// Inserting composite types
dataAccess.insertInto("users")
    .values(listOf("name", "address"))
    .execute(
        "name" to "John",
        "address" to Address("123 Main St", "NYC", "10001")
    )
```

### Nested Composites

```kotlin
@PgComposite
data class GeoLocation(val latitude: Double, val longitude: Double)

@PgComposite
data class AddressWithGeo(
    val street: String,
    val city: String,
    val location: GeoLocation  // Nested composite
)
```

### Arrays of Composites

```kotlin
// PostgreSQL: addresses address[]
data class Company(val id: Int, val addresses: List<Address>)
```

---

## @DynamicallyMappable

Enables dynamic type mapping via the `dynamic_dto` PostgreSQL type. This allows:

1. **Polymorphic storage** - Different types in one column/array
2. **Ad-hoc mapping** - Construct Kotlin objects in SQL without defining COMPOSITE types

### Annotation Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `typeName` | `String` | Unique key used in `dynamic_dto('key', ...)` |

### Requirements

- Class must also have `@Serializable` annotation (kotlinx.serialization)
- Works with `data class`, `enum class`, and `value class`

### Polymorphic Storage

Store different types in a single column:

```kotlin
@DynamicallyMappable(typeName = "text_note")
@Serializable
data class TextNote(val content: String)

@DynamicallyMappable(typeName = "image_note")
@Serializable
data class ImageNote(val url: String, val caption: String?)

@DynamicallyMappable(typeName = "checklist_note")
@Serializable
data class ChecklistNote(val items: List<String>, val checked: List<Boolean>)
```

```sql
-- Table with polymorphic column
CREATE TABLE notebooks (
    id SERIAL PRIMARY KEY,
    notes dynamic_dto[]  -- Can contain TextNote, ImageNote, ChecklistNote
);

-- Insert different types
INSERT INTO notebooks (notes) VALUES (
    ARRAY[
        dynamic_dto('text_note', '{"content": "Hello"}'),
        dynamic_dto('image_note', '{"url": "https://...", "caption": null}')
    ]
);
```

```kotlin
// Read back with automatic deserialization
data class Notebook(val id: Int, val notes: List<Any>)

val notebook = dataAccess.select("id", "notes")
    .from("notebooks")
    .where("id = 1")
    .toSingleOf<Notebook>()

// notes contains [TextNote("Hello"), ImageNote("https://...", null)]
```

### Ad-hoc Object Mapping

Construct Kotlin objects directly in SQL without COMPOSITE types:

```kotlin
@DynamicallyMappable(typeName = "user_profile")
@Serializable
data class UserProfile(val role: String, val permissions: List<String>)

data class UserWithProfile(val id: Int, val name: String, val profile: UserProfile)
```

```kotlin
val users = dataAccess.rawQuery("""
    SELECT
        u.id,
        u.name,
        dynamic_dto(
            'user_profile',
            jsonb_build_object(
                'role', p.role,
                'permissions', p.permissions
            )
        ) AS profile
    FROM users u
    JOIN profiles p ON p.user_id = u.id
""").toListOf<UserWithProfile>()
```

### dynamic_dto PostgreSQL Type

Octavius automatically creates this type on startup:

```sql
CREATE TYPE dynamic_dto AS (
    type_name    TEXT,     -- Discriminator key
    data_payload JSONB     -- Serialized data
);

CREATE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
RETURNS dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql;
```

---

## PgTyped - Explicit Type Casts

When PostgreSQL can't infer the type, use `PgTyped` to add explicit casts.

### Usage

```kotlin
// Using PgStandardType enum (type-safe)
val ids = listOf(1, 2, 3).withPgType(PgStandardType.INT4_ARRAY)

// Using string type name (for custom types)
val data = jsonObject.withPgType("jsonb")
```

### When Needed

1. **Arrays with type ambiguity:**

```kotlin
// Without PgTyped - might fail
dataAccess.rawQuery("SELECT * FROM users WHERE id = ANY(:ids)")
    .toListOf<User>("ids" to listOf(1, 2, 3))

// With PgTyped - explicit type
dataAccess.rawQuery("SELECT * FROM users WHERE id = ANY(:ids)")
    .toListOf<User>("ids" to listOf(1, 2, 3).withPgType(PgStandardType.INT4_ARRAY))
```

2. **JSON parameters:**

```kotlin
dataAccess.insertInto("events")
    .values(listOf("data"))
    .execute("data" to jsonElement.withPgType(PgStandardType.JSONB))
```

### PgStandardType Values

```kotlin
enum class PgStandardType(val typeName: String) {
    // Integers
    INT2("int2"), INT4("int4"), INT8("int8"),
    SMALLSERIAL("smallserial"), SERIAL("serial"), BIGSERIAL("bigserial"),

    // Floats
    FLOAT4("float4"), FLOAT8("float8"), NUMERIC("numeric"),

    // Text
    TEXT("text"), VARCHAR("varchar"), CHAR("char"),

    // Date/Time
    DATE("date"), TIME("time"), TIMETZ("timetz"),
    TIMESTAMP("timestamp"), TIMESTAMPTZ("timestamptz"), INTERVAL("interval"),

    // JSON
    JSON("json"), JSONB("jsonb"),

    // Other
    BOOL("bool"), UUID("uuid"), BYTEA("bytea"),

    // Arrays (all standard types)
    INT4_ARRAY("_int4"), TEXT_ARRAY("_text"), UUID_ARRAY("_uuid"), // etc.
}
```

### Type Resolution Priority

When a Kotlin type has multiple annotations (e.g., both `@PgComposite` and `@DynamicallyMappable`), the framework resolves conflicts based on explicit wrappers:

**Priority rules:**

| Wrapper Used | Behavior |
|--------------|----------|
| `value.withPgType("type")` | Forces `@PgComposite` / `@PgEnum` path — converts to `ROW(...)::type` or `PGobject` |
| `DynamicDto.from(value)` | Forces `@DynamicallyMappable` path — converts to `dynamic_dto(...)` |
| None (raw value) | Depends on `DynamicDtoSerializationStrategy` (see [Configuration](configuration.md)) |

**DynamicDtoSerializationStrategy** controls automatic conversion when no explicit wrapper is used:

| Strategy | Behavior |
|----------|----------|
| `EXPLICIT_ONLY` | Only explicit `DynamicDto.from()` wrappers trigger dynamic serialization. Raw values always use `@PgComposite`/`@PgEnum`. |
| `AUTOMATIC_WHEN_UNAMBIGUOUS` (default) | If type is registered as formal PostgreSQL type (`@PgComposite`/`@PgEnum`), uses that. Otherwise, if `@DynamicallyMappable` is present, uses dynamic serialization. |

**Example with dual-annotated type:**

```kotlin
@PgComposite
@DynamicallyMappable(typeName = "profile_dto")
@Serializable
data class Profile(val name: String, val role: String)

// Force PostgreSQL COMPOSITE type path
dataAccess.insertInto("users")
    .values(listOf("profile"))
    .execute("profile" to profile.withPgType("profile"))
// SQL: ROW('John', 'admin')::profile

// Force dynamic_dto path
dataAccess.insertInto("events")
    .values(listOf("payload"))
    .execute("payload" to DynamicDto.from(profile))
// SQL: dynamic_dto('profile_dto', '{"name":"John","role":"admin"}')

// Raw value - uses @PgComposite (formal type has priority with default strategy)
dataAccess.insertInto("users")
    .values(listOf("profile"))
    .execute("profile" to profile)
```

---

## @MapKey - Custom Property Mapping

Override the default snake_case ↔ camelCase mapping for individual properties.

> For practical usage examples with `toMap()` and `toDataObject()`, see [ORM-Like Patterns](orm-patterns.md#mapkey-annotation).

### Usage

```kotlin
data class User(
    val id: Int,
    val userName: String,  // Maps to "user_name" by default

    @MapKey("user")        // Override: maps to "user" instead
    val userId: Int
)
```

### When Needed

- Column name doesn't follow snake_case convention
- Want to map to a different column name than property suggests
- Working with legacy schemas

```kotlin
data class LegacyOrder(
    @MapKey("ORDER_ID")      // Legacy uppercase
    val orderId: Int,

    @MapKey("CUST_NAME")     // Abbreviated column
    val customerName: String,

    val status: String       // Default: maps to "status"
)
```

---

## Object Conversion Utilities

Octavius provides utilities for converting between data classes and maps.

> For detailed patterns including CRUD operations, partial updates, and real-world examples, see [ORM-Like Patterns](orm-patterns.md#object-map-conversion).

### toDataObject()

Convert a map to a data class:

```kotlin
val map = mapOf("id" to 1, "user_name" to "John", "email" to "john@example.com")
val user: User = map.toDataObject<User>()

// Or with explicit class
val user = map.toDataObject(User::class)
```

**Rules:**
- Keys in snake_case match properties in camelCase
- `@MapKey` annotation overrides default mapping
- Missing required properties throw `ConversionException`
- Optional parameters use their default values if key is missing
- Nullable parameters become `null` if key is missing

### toMap()

Convert a data class to a map:

```kotlin
data class User(val id: Int, val userName: String, val email: String)

val user = User(1, "John", "john@example.com")
val map = user.toMap()
// Result: {id=1, user_name=John, email=john@example.com}

// Exclude certain keys
val partial = user.toMap("id", "email")
// Result: {user_name=John}
```

---

## Enum Serialization in dynamic_dto

When using enums inside `@DynamicallyMappable` classes, you need a custom serializer to handle naming conventions.

### DynamicDtoEnumSerializer

```kotlin
// 1. Define your enum with @PgEnum
@Serializable(with = OrderStatusSerializer::class)
@PgEnum(
    pgConvention = CaseConvention.SNAKE_CASE_UPPER,
    kotlinConvention = CaseConvention.PASCAL_CASE
)
enum class OrderStatus { Pending, InProgress, Completed }

// 2. Create a serializer for dynamic_dto usage
object OrderStatusSerializer : DynamicDtoEnumSerializer<OrderStatus>(
    serialName = "OrderStatus",
    entries = OrderStatus.entries,
    pgConvention = CaseConvention.SNAKE_CASE_UPPER,
    kotlinConvention = CaseConvention.PASCAL_CASE
)

// 3. Use in @DynamicallyMappable classes
@DynamicallyMappable(typeName = "order_update")
@Serializable
data class OrderUpdate(
    val orderId: Int,
    val newStatus: OrderStatus  // Uses OrderStatusSerializer
)
```

### Why Is This Necessary?

This serializer is required because of how kotlinx.serialization works. When you have an enum property inside a `@Serializable` class, the compiler plugin generates a serializer for that class at compile time. For enum properties, **it uses the default enum serializer** which simply outputs the Kotlin enum name (e.g., `"InProgress"`).

The library cannot intercept or modify this behavior internally — the serializer is already baked into the generated code. The only way to change how the enum is serialized is to explicitly specify a custom serializer using `@Serializable(with = ...)` on the enum class itself.

**The problem:**

```kotlin
@DynamicallyMappable(typeName = "order_update")
@Serializable
data class OrderUpdate(val status: OrderStatus)

// Generated serializer uses default enum serialization:
// {"status": "InProgress"}  ← Kotlin name, not PostgreSQL convention!
```

**The solution:**

```kotlin
@Serializable(with = OrderStatusSerializer::class)  // Override default
@PgEnum(pgConvention = CaseConvention.SNAKE_CASE_UPPER)
enum class OrderStatus { Pending, InProgress, Completed }

// Now serializes as:
// {"status": "IN_PROGRESS"}  ← Matches PostgreSQL convention
```

Without this, the JSON payload stored in `dynamic_dto` would contain Kotlin enum names, but PostgreSQL ENUM values use different conventions — leading to mismatches when data is read back or queried directly in SQL.

### Parameters

| Parameter | Description |
|-----------|-------------|
| `serialName` | Name for serialization descriptor |
| `entries` | `YourEnum.entries` - list of all enum values |
| `pgConvention` | Must match `@PgEnum.pgConvention` |
| `kotlinConvention` | Must match `@PgEnum.kotlinConvention` |

---

## Helper Serializers

Octavius provides helper serializers for kotlinx.serialization to handle common types that don't have built-in support.

### BigDecimalAsNumberSerializer

Serializes `java.math.BigDecimal` as an unquoted JSON number literal, preserving full numeric precision.

**Why needed?** By default, kotlinx.serialization doesn't support `BigDecimal`. If you need to store `BigDecimal` values in `@DynamicallyMappable` classes (serialized to JSONB), this serializer ensures:

1. The value is stored as a JSON number (not a string)
2. Full precision is preserved (no floating-point rounding)
3. PostgreSQL's JSONB correctly interprets it as a numeric type

**Usage:**

```kotlin
import org.octavius.data.helper.BigDecimalAsNumberSerializer
import java.math.BigDecimal

@DynamicallyMappable(typeName = "price_data")
@Serializable
data class PriceData(
    val productId: Int,

    @Serializable(with = BigDecimalAsNumberSerializer::class)
    val price: BigDecimal,

    @Serializable(with = BigDecimalAsNumberSerializer::class)
    val tax: BigDecimal
)
```

**Result in JSONB:**

```json
{
  "productId": 123,
  "price": 199.99,
  "tax": 15.9992
}
```

Without this serializer, you would get a compilation error or need to convert `BigDecimal` to `String`/`Double` manually (losing precision or type information).