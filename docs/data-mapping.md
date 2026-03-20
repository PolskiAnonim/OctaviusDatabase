# Data Mapping

*The Roman scribe did not receive a raw census tablet and hand it to the consul unprocessed — he transcribed it, organized it, and rendered it into the proper form for governance. Data mapping in Octavius follows the same principle: raw database rows are shaped into the Kotlin types your application understands.*

Octavius provides utilities for converting between Kotlin data classes and `Map<String, Any?>` representations. This enables convenient patterns for database operations while keeping full SQL control.

## Table of Contents

- [toDataObject()](#todataobject---map-to-data-class)
- [Nested Structures & Strict Type Checking](#nested-structures--strict-type-checking)
- [toMap()](#tomap---data-class-to-map)
- [@MapKey Annotation](#mapkey-annotation)
- [Combining with Query Builders](#combining-with-query-builders)

---

## toDataObject() - Map to Data Class

Converts a `Map<String, Any?>` (typically a database row) to a data class instance.

```kotlin
// Basic usage
val row: Map<String, Any?> = mapOf(
    "citizen_id" to 1,
    "first_name" to "Marcus",
    "enrolled_at" to Instant.now()
)

// snake_case keys are automatically matched to camelCase properties
data class Citizen(val citizenId: Int, val firstName: String, val enrolledAt: Instant)

val citizen: Citizen = row.toDataObject()
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
data class Province(val name: String, val capital: String)

data class Senator(val name: String, val homeProvince: Province)

// Query returns a Map with typed values
val rowMap = dataAccess.select("name", "home_province")
    .from("senate")
    .toSingle()
    .getOrThrow()!!

// rowMap structure is ALREADY typed:
// {
//   "name": "Cicero" (String),
//   "home_province": Province(name="Arpinum", capital="Arpinum") (Instance of Province, NOT a Map!)
// }

// Conversion to Data Object works seamlessly
val senator = rowMap.toDataObject<Senator>()
```

**Strict type checking:**

Because the Map holds specific types, `toDataObject()` performs a **strict mapping**. It does not attempt fuzzy conversion.

```kotlin
// ❌ FAILS: Type Mismatch
// The map contains a Province object, but this class expects a String
data class SenatorWrong(val name: String, val homeProvince: String)

rowMap.toDataObject<SenatorWrong>()
// Throws ConversionException: Expected String for field 'homeProvince' but got Province
```

This ensures type safety: if the structure of your Data Class doesn't match the structure returned by the database (including nested composites, enums, and arrays), the operation fails fast rather than producing incorrect data.

---

## toMap() - Data Class to Map

Converts a data class to a `Map<String, Any?>` with snake_case keys.

```kotlin
val senator = Senator(senatorId = 1, firstName = "Gaius", enrolledAt = Instant.now())

val map = senator.toMap()
// Result: {"senator_id" to 1, "first_name" to "Gaius", "enrolled_at" to ...}

// Exclude specific keys (e.g., auto-generated ID)
val mapWithoutId = senator.toMap("senator_id")
// Result: {"first_name" to "Gaius", "enrolled_at" to ...}
```

### Requirements

- Works only with **data classes**
- Extracts only **primary constructor** properties (not additional properties defined in class body)
- Property names are converted to snake_case

### Exclusion Strategies

```kotlin
// Exclude by field names (vararg) - RECOMMENDED
entity.toMap("id", "enrolled_at", "last_census_at")

// Exclude computed/derived fields
val EXCLUDE_ON_UPDATE = setOf("id", "enrolled_at", "version")
entity.toMap(*EXCLUDE_ON_UPDATE.toTypedArray())

// Exclude based on external configuration
val immutableFields = config.getImmutableFieldsFor("citizens")
entity.toMap(*immutableFields.toTypedArray())

// Filter after conversion - NOT RECOMMENDED (less efficient, iterates twice)
entity.toMap().filterKeys { it !in setOf("id", "secret") }
```

Prefer excluding keys directly in `toMap()` - it skips excluded fields during conversion rather than iterating the entire map afterwards.

---

## @MapKey Annotation

Override the default naming convention for specific properties.

```kotlin
data class CitizenProfile(
    val id: Int,
    val citizenName: String,        // Maps to "citizen_name" by default
    @MapKey("citizen")
    val citizenId: Int,             // Maps to "citizen" instead of "citizen_id"
    @MapKey("external_registry")
    val externalRef: String         // Maps to "external_registry" instead of "external_ref"
)

// toMap() uses @MapKey names
val profile = CitizenProfile(1, "Marcus Tullius", 42, "REG-123")
profile.toMap()
// Result: {"id" to 1, "citizen_name" to "Marcus Tullius", "citizen" to 42, "external_registry" to "REG-123"}

// toDataObject() also respects @MapKey
val row = mapOf("id" to 1, "citizen_name" to "Marcus Tullius", "citizen" to 42, "external_registry" to "REG-123")
val restored: CitizenProfile = row.toDataObject()
```

### Use Cases for @MapKey

1. **Legacy databases** with non-standard column names
2. **Foreign key references** where column name differs from property name
3. **Abbreviations** or domain-specific naming

---

## Combining with Query Builders

The real power comes from combining `toMap()` with auto-placeholder functions.

### Insert from Object

```kotlin
data class RegisterCitizenRequest(val name: String, val tribe: String, val rank: Magistrature)

fun registerCitizen(request: RegisterCitizenRequest): DataResult<Int> {
    // Convert once - toMap() uses reflection, so avoid calling it multiple times
    val data = request.toMap()

    return dataAccess.insertInto("citizens")
        .values(data)
        .returning("id")
        .toField<Int>(data)
}

// Usage
val newCitizenId = registerCitizen(RegisterCitizenRequest("Gaius Julius", "Cornelia", Magistrature.Quaestor))
```

### Update from Object

```kotlin
data class UpdateCitizenRequest(val id: Int, val name: String, val tribe: String)

fun updateCitizen(request: UpdateCitizenRequest): DataResult<Int> {
    val data = request.toMap("id")  // Exclude ID from SET clause

    return dataAccess.update("citizens")
        .setValues(data)
        .where("id = :id")
        .execute(data + ("id" to request.id))
}
```

### Partial Updates

```kotlin
// Only update provided fields
data class PatchCitizenRequest(
    val id: Int,
    val name: String? = null,
    val tribe: String? = null
)

fun patchCitizen(request: PatchCitizenRequest): DataResult<Int> {
    val data = request.toMap("id").filterValues { it != null }

    if (data.isEmpty()) {
        return DataResult.Success(0)
    }

    return dataAccess.update("citizens")
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
dataAccess.insertInto("citizens")
    .values(data)      // Defines: (col1, col2) VALUES (:col1, :col2)
    .execute(data)     // Provides: col1 -> value1, col2 -> value2
```

---

## See Also

- [Executing Queries](executing-queries.md) - Terminal methods and DataResult
- [ORM-Like Patterns](orm-patterns.md) - CRUD patterns and real-world examples
- [Query Builders](query-builders.md) - Building queries with auto-placeholders
