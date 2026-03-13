# Data Mapping

Octavius provides utilities for converting between Kotlin data classes and `Map<String, Any?>` representations. This enables convenient patterns for database operations while keeping full SQL control.

## Table of Contents

- [toDataObject()](#todataobject---map-to-data-class)
- [Nested Structures & Strict Type Checking](#nested-structures--strict-type-checking)
- [toMap()](#tomap---data-class-to-map)
- [@MapKey Annotation](#mapkey-annotation)
- [Manual Composite Mapping (PgCompositeMapper)](#manual-composite-mapping-pgcompositemapper)
- [Combining with Query Builders](#combining-with-query-builders)

---

## toDataObject() - Map to Data Class

Converts a `Map<String, Any?>` (typically a database row) to a data class instance.

```kotlin
// Basic usage
val row: Map<String, Any?> = mapOf(
    "user_id" to 1,
    "first_name" to "John",
    "created_at" to Instant.now()
)

// snake_case keys are automatically matched to camelCase properties
data class User(val userId: Int, val firstName: String, val createdAt: Instant)

val user: User = row.toDataObject()
```

### Requirements

- Works only with **data classes**
- Uses the **primary constructor** - map keys must match constructor parameters
- Constructor parameters are matched by name (with snake_case → camelCase conversion)

### Features

- Automatic `snake_case` → `camelCase` conversion
- Support for nullable properties and default values
- Cached reflection metadata for performance
- Clear error messages for missing/mismatched properties

### Nested Structures & Strict Type Checking

When dealing with `Map<String, Any?>` results (either from `toSingle()` or when using `toDataObject()`), type conversion happens **at the database layer**, before the Map is constructed. Octavius uses **OID-based resolution** (PostgreSQL Object Identifiers) to precisely identify and map nested composite types, enums, and arrays without relying on fragile string matching.

The Map contains **fully instantiated objects**, not raw values or JSON strings.

**Nested composites are already typed:**

```kotlin
@PgComposite
data class Address(val city: String)

data class User(val name: String, val address: Address)

// Query returns a Map with typed values
val rowMap = dataAccess.select("name", "address")
    .from("users")
    .toSingle()
    .getOrThrow()!!

// rowMap structure is ALREADY typed:
// {
//   "name": "John" (String),
//   "address": Address(city="London") (Instance of Address, NOT a Map!)
// }

// Conversion to Data Object works seamlessly
val user = rowMap.toDataObject<User>()
```

**Strict type checking:**

Because the Map holds specific types, `toDataObject()` performs a **strict mapping**. It does not attempt fuzzy conversion.

```kotlin
// ❌ FAILS: Type Mismatch
// The map contains an Address object, but this class expects a String
data class UserWrong(val name: String, val address: String)

rowMap.toDataObject<UserWrong>()
// Throws ConversionException: Expected String for field 'address' but got Address
```

This ensures type safety: if the structure of your Data Class doesn't match the structure returned by the database (including nested composites, enums, and arrays), the operation fails fast rather than producing incorrect data.

---

## toMap() - Data Class to Map

Converts a data class to a `Map<String, Any?>` with snake_case keys.

```kotlin
val user = User(userId = 1, firstName = "John", createdAt = Instant.now())

val map = user.toMap()
// Result: {"user_id" to 1, "first_name" to "John", "created_at" to ...}

// Exclude specific keys (e.g., auto-generated ID)
val mapWithoutId = user.toMap("user_id")
// Result: {"first_name" to "John", "created_at" to ...}
```

### Requirements

- Works only with **data classes**
- Extracts only **primary constructor** properties (not additional properties defined in class body)
- Property names are converted to snake_case

### Exclusion Strategies

```kotlin
// Exclude by field names (vararg) - RECOMMENDED
entity.toMap("id", "created_at", "updated_at")

// Exclude computed/derived fields
val EXCLUDE_ON_UPDATE = setOf("id", "created_at", "version")
entity.toMap(*EXCLUDE_ON_UPDATE.toTypedArray())

// Exclude based on external configuration
val immutableFields = config.getImmutableFieldsFor("users")
entity.toMap(*immutableFields.toTypedArray())

// Filter after conversion - NOT RECOMMENDED (less efficient, iterates twice)
entity.toMap().filterKeys { it !in setOf("id", "secret") }
```

Prefer excluding keys directly in `toMap()` - it skips excluded fields during conversion rather than iterating the entire map afterwards.

---

## @MapKey Annotation

Override the default naming convention for specific properties.

```kotlin
data class UserProfile(
    val id: Int,
    val userName: String,           // Maps to "user_name" by default
    @MapKey("user")
    val userId: Int,                // Maps to "user" instead of "user_id"
    @MapKey("external_reference")
    val externalRef: String         // Maps to "external_reference" instead of "external_ref"
)

// toMap() uses @MapKey names
val profile = UserProfile(1, "john", 42, "EXT-123")
profile.toMap()
// Result: {"id" to 1, "user_name" to "john", "user" to 42, "external_reference" to "EXT-123"}

// toDataObject() also respects @MapKey
val row = mapOf("id" to 1, "user_name" to "john", "user" to 42, "external_reference" to "EXT-123")
val restored: UserProfile = row.toDataObject()
```

### Use Cases for @MapKey

1. **Legacy databases** with non-standard column names
2. **Foreign key references** where column name differs from property name
3. **Abbreviations** or domain-specific naming

---

## Manual Composite Mapping (PgCompositeMapper)

By default, Octavius uses reflection for mapping between data classes and PostgreSQL composite types. While highly convenient, you can provide a manual mapper to:
1. **Improve performance:** Bypass reflection in high-volume operations.
2. **Transform types:** Change how data is represented in Kotlin vs. Database.
3. **Handle arrays:** Map PostgreSQL arrays to Kotlin `Array<T>` instead of `List<T>`.

### Basic Usage

Define an object or class implementing `PgCompositeMapper<T>` and reference it in the `@PgComposite` annotation.

```kotlin
@PgComposite(name = "user_stats", mapper = StatsMapper::class)
data class Stats(val strength: Int, val agility: Int)

object StatsMapper : PgCompositeMapper<Stats> {
    override fun fromMap(map: Map<String, Any?>) = Stats(
        strength = map["strength"] as Int,
        agility = map["agility"] as Int
    )

    override fun toMap(obj: Stats) = mapOf(
        "strength" to obj.strength,
        "agility" to obj.agility
    )
}
```

### Type Transformation: Collection Conversion

Octavius default mapping always returns collections as `List<T>`. If your data class requires a primitive array (e.g., for high-performance math), use a mapper to perform the conversion.

```kotlin
@PgComposite(name = "signal_data", mapper = SignalMapper::class)
data class Signal(val id: Int, val samples: DoubleArray)

object SignalMapper : PgCompositeMapper<Signal> {
    override fun fromMap(map: Map<String, Any?>) = Signal(
        id = map["id"] as Int,
        // PostgreSQL array comes as List<Double>, convert it to DoubleArray
        samples = (map["samples"] as List<Double>).toDoubleArray()
    )

    override fun toMap(obj: Signal) = mapOf(
        "id" to obj.id,
        "samples" to obj.samples.toList()
    )
}
```

### Type Transformation: Custom Logic

You can also use mappers to handle types that don't have a direct 1:1 mapping or require special logic.

```kotlin
@PgComposite(name = "event_info", mapper = EventMapper::class)
data class Event(val name: String, val timestamp: Instant)

object EventMapper : PgCompositeMapper<Event> {
    override fun fromMap(map: Map<String, Any?>) = Event(
        name = map["name"] as String,
        // Map from raw Long (epoch millis) to Instant
        timestamp = Instant.fromEpochMilliseconds(map["timestamp"] as Long)
    )

    override fun toMap(obj: Event) = mapOf(
        "name" to obj.name,
        "timestamp" to obj.timestamp.toEpochMilliseconds()
    )
}
```

### Implementation Details
- **Singleton Support:** If the mapper is a Kotlin `object`, it will be used as a singleton.
- **Class Support:** If the mapper is a `class`, Octavius will instantiate it once using its public no-arg constructor.
- **Type Safety:** Mappers are called during both reading (from DB to Kotlin) and writing (from Kotlin to DB placeholders).

---

## Combining with Query Builders

The real power comes from combining `toMap()` with auto-placeholder functions.

### Insert from Object

```kotlin
data class CreateUserRequest(val name: String, val email: String, val role: UserRole)

fun createUser(request: CreateUserRequest): DataResult<Int> {
    // Convert once - toMap() uses reflection, so avoid calling it multiple times
    val data = request.toMap()

    return dataAccess.insertInto("users")
        .values(data)
        .returning("id")
        .toField<Int>(data)
}

// Usage
val newUserId = createUser(CreateUserRequest("John", "john@example.com", UserRole.USER))
```

### Update from Object

```kotlin
data class UpdateUserRequest(val id: Int, val name: String, val email: String)

fun updateUser(request: UpdateUserRequest): DataResult<Int> {
    val data = request.toMap("id")  // Exclude ID from SET clause

    return dataAccess.update("users")
        .setValues(data)
        .where("id = :id")
        .execute(data + ("id" to request.id))
}
```

### Partial Updates

```kotlin
// Only update provided fields
data class PatchUserRequest(
    val id: Int,
    val name: String? = null,
    val email: String? = null
)

fun patchUser(request: PatchUserRequest): DataResult<Int> {
    val data = request.toMap("id").filterValues { it != null }

    if (data.isEmpty()) {
        return DataResult.Success(0)
    }

    return dataAccess.update("users")
        .setValues(data)
        .where("id = :id")
        .execute(data + ("id" to request.id))
}
```

### Same Map for Definition and Execution

The pattern `values(map)` + `execute(map)` uses the **same map** for both:
1. Generating column names and placeholders
2. Providing actual values

```kotlin
val data = entity.toMap("id")
dataAccess.insertInto("table")
    .values(data)      // Defines: (col1, col2) VALUES (:col1, :col2)
    .execute(data)     // Provides: col1 -> value1, col2 -> value2
```

---

## See Also

- [Executing Queries](executing-queries.md) - Terminal methods and DataResult
- [ORM-Like Patterns](orm-patterns.md) - CRUD patterns and real-world examples
- [Query Builders](query-builders.md) - Building queries with auto-placeholders
