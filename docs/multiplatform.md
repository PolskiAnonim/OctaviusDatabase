# Multiplatform Support

The denarius was minted once in Rome, yet it crossed every border in the empire without losing its value. A merchant in Alexandria, a legionnaire in Britannia, a tax collector in Gaul — each accepted it at face value, no conversion required. A data class in commonMain works the same way: defined once, it carries the same meaning to your JVM backend and your JavaScript frontend alike.
While the core execution engine of Octavius is JVM-based (utilizing JDBC and HikariCP), the **data model layer** is **Kotlin Multiplatform (KMP)**. This allows you to share your entity definitions, validation logic, and serialization rules across the entire stack.

---

## Availability Matrix

The `:api` module is divided into a common part and platform-specific implementations.

| Component                         | KMP (Common) | JVM (Desktop) | JS Target |
|-----------------------------------|--------------|---------------|-----------|
| **Annotations** (`@PgEnum`, etc.) | ✅            | ✅             | ✅         |
| **Shared DTOs** (Data Classes)    | ✅            | ✅             | ✅         |
| **Multiplatform BigDecimal**      | ✅            | ✅             | ✅         |
| **Custom Serializers**            | ✅            | ✅             | ✅         |
| **DataAccess & Builders**         | ❌            | ✅             | ❌         |
| **Transaction Plan API**          | ❌            | ✅             | ❌         |
| **LISTEN / NOTIFY Client**        | ❌            | ✅             | ❌         |

---

## Shared Data Models

By placing your data classes in the `commonMain` source set of a KMP project, you can use the same classes for:
1. **Persistence:** Octavius Core on the backend maps them to PostgreSQL types.
2. **Communication:** Serialize them to JSON (via `kotlinx.serialization`) to send to a web frontend.
3. **Frontend Logic:** Use the same classes in your Kotlin/JS apps.

### Example: Shared Entity
```kotlin
// commonMain/src/.../Citizen.kt
@Serializable
@PgComposite(name = "citizen_type")
data class Citizen(
    val id: Int,
    val name: String,
    @Contextual val balance: BigDecimal // Multiplatform BigDecimal!
)
```

---

## Multiplatform BigDecimal

Standard `java.math.BigDecimal` is not available in Multiplatform `commonMain`. Octavius provides its own `io.github.octaviusframework.db.api.model.BigDecimal` wrapper to ensure consistency:

- **JVM:** A `typealias` to `java.math.BigDecimal`. Full performance and integration with the standard library.
- **JS:** A wrapper around `String`. Since JavaScript's `Number` is a 64-bit float (which loses precision for large decimals), Octavius stores the value as a string to preserve the full precision of PostgreSQL's `numeric` type.

This allows you to safely pass high-precision values (like currency or scientific measurements) from your backend to a JS frontend without accidentally rounding them.

---

## Serializers & JSON Configuration

Octavius relies on `kotlinx.serialization` for handling complex data types, especially when using **JSONB** or **`dynamic_dto`**. To ensure consistency across platforms and support for PostgreSQL-specific values (like `infinity`), Octavius provides a pre-configured serialization setup.

### Contextual Serialization
Instead of hardcoding serializers for every field using `@Serializable(with = ...)`, Octavius leverages **Contextual Serialization**. This keeps your DTOs clean and allows the library to automatically handle platform-specific mapping (like the JS `BigDecimal` wrapper).

Octavius provides the following contextual serializers:
- **`BigDecimalAsNumberSerializer`**: Ensures precision is preserved in JSON (stored as numeric literal in PG, string in JS).
- **`LocalDateWithInfinitySerializer`**, **`LocalDateTimeWithInfinitySerializer`**, **`InstantWithInfinitySerializer`**: Handle PostgreSQL `infinity` and `-infinity` values which standard serializers do not support.

You should use `@Contextual` for these types in your shared DTOs:

```kotlin
@Serializable
@PgComposite(name = "citizen_type")
data class Citizen(
    val id: Int,
    val name: String,
    @Contextual val balance: BigDecimal,    // Uses BigDecimalAsNumberSerializer
    @Contextual val birthDate: LocalDate,   // Uses LocalDateWithInfinitySerializer
    @Contextual val updatedAt: Instant      // Uses InstantWithInfinitySerializer
)
```

### Using OctaviusJson
The easiest way to work with these types on both the backend (JVM) and frontend (JS) is to use the provided `OctaviusJson` instance. It is a pre-configured `Json` object that includes all necessary contextual mappings.

```kotlin
import io.github.octaviusframework.db.api.serializer.OctaviusJson

// On the frontend (JS) or backend (JVM)
val json = OctaviusJson.encodeToString(citizen)
val decoded = OctaviusJson.decodeFromString<Citizen>(json)
```

### Custom JSON Configuration
If you need to merge Octavius serializers with your own configuration (e.g., to add your own `SerializersModule` or change formatting), use `createOctaviusSerializersModule()`:

```kotlin
val myCustomJson = Json {
    // Add Octavius support to your custom module
    serializersModule = createOctaviusSerializersModule() + myAppModule
    
    ignoreUnknownKeys = true
    prettyPrint = true
}
```

Using these tools guarantees that your `dynamic_dto` payloads remain compatible with PostgreSQL's native types while staying fully functional in a Kotlin Multiplatform environment.

---

## See Also
- [Type System](type-system.md) - How shared types map to PostgreSQL.
- [Data Mapping](data-mapping.md) - Using shared DTOs with query results.
