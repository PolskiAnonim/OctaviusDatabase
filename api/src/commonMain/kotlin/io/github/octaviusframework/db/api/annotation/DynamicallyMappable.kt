package io.github.octaviusframework.db.api.annotation

/**
 * Marks a `data class`, `enum class`, or `value class` as a target for dynamic mapping from `dynamic_dto` type
 * in PostgreSQL.
 *
 * This annotation provides a bridge between SQL and Kotlin, allowing the framework
 * to identify which Kotlin class should represent a specific "type" stored in a
 * `dynamic_dto` column or returned from a query.
 *
 * NOTE: Usage also requires `@Serializable` annotation!
 *
 * ### Key Concepts
 * - **Polymorphism**: Allows storing different related types in a single database column.
 * - **No Database Schema Changes**: Unlike PostgreSQL `COMPOSITE` types, `dynamic_dto`
 *   backed by [DynamicallyMappable] doesn't require additional `CREATE TYPE` in the database.
 * - **Type Safety**: The framework uses this annotation to safely deserialize JSONB
 *   payloads into strongly-typed Kotlin objects.
 *
 * ### Usage Example
 * ```kotlin
 * @Serializable
 * @DynamicallyMappable("land_grant")
 * data class LandGrant(val province: String, val areraActa: BigDecimal)
 *
 * @Serializable
 * @DynamicallyMappable("military_pension")
 * data class MilitaryPension(val legionName: String, val annualAmount: BigDecimal)
 * ```
 *
 * In SQL, this can be constructed as:
 * `SELECT dynamic_dto('land_grant', jsonb_build_object('province', 'Gallia', 'arera_acta', 120.5))`
 *
 * @param typeName The unique identifier (key) for this class. It must match the type name
 *                 used in the database (e.g., in the `dynamic_dto(text, jsonb)` constructor).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DynamicallyMappable(val typeName: String)
