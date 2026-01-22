# Data Mapping

Octavius provides utilities for converting between Kotlin data classes and `Map<String, Any?>` representations. This enables convenient patterns for database operations while keeping full SQL control.

## Table of Contents

- [toDataObject()](#todataobject---map-to-data-class)
- [toMap()](#tomap---data-class-to-map)
- [@MapKey Annotation](#mapkey-annotation)
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
