package org.octavius.data.annotation

import kotlin.reflect.KClass

/**
 * Interface for manual (non-reflective) mapping of a data class to/from a Map.
 * Used by [PgComposite] to bypass reflection-based mapping and improve performance.
 *
 * This is an alternative to reflection that can be manually implemented or generated
 * by external tools.
 */
interface PgCompositeMapper<T : Any> {
    /**
     * Creates an instance of [T] from the provided [map].
     * The map keys are the attribute names (typically in snake_case as they come from DB).
     */
    fun fromMap(map: Map<String, Any?>): T

    /**
     * Converts the provided object [obj] to a Map.
     * The map keys should match the PostgreSQL composite attribute names.
     */
    fun toMap(obj: T): Map<String, Any?>
}

/**
 * Internal marker to indicate that no explicit mapper is provided.
 */
object DefaultPgCompositeMapper : PgCompositeMapper<Any> {
    override fun fromMap(map: Map<String, Any?>): Any = throw UnsupportedOperationException()
    override fun toMap(obj: Any): Map<String, Any?> = throw UnsupportedOperationException()
}

/**
 * Marks a `data class` as a data type that can be mapped
 * to a composite type in PostgreSQL database.
 *
 * This annotation is crucial for `TypeRegistry`, which scans the classpath for
 * marked classes to automatically build mapping between Kotlin classes
 * and PostgreSQL composite types.
 *
 * **Naming convention:**
 * By default, the type name in PostgreSQL is derived from the simple class name
 * by converting from `CamelCase` to `snake_case` (e.g., `TestPerson` class will be
 * mapped to `test_person` type).
 *
 * **Explicit name specification:**
 * You can override the default name by providing it in the [name] parameter. This is useful
 * when the type name in the database doesn't match the convention.
 *
 * **Non-reflective mapping:**
 * By default, Octavius uses reflection to map data class properties to composite attributes.
 * You can provide a custom [PgCompositeMapper] via the [mapper] parameter to bypass reflection,
 * which can significantly improve performance for high-volume operations.
 *
 * @param name Optional, explicit name of the corresponding type in PostgreSQL database.
 *             If left empty, the name will be generated automatically
 *             according to the `CamelCase` -> `snake_case` convention.
 * @param mapper Optional, custom mapper implementation to use instead of reflection.
 *               Must implement [PgCompositeMapper].
 *
 * ### Examples
 * ```kotlin
 * // Example 1: Using default naming convention and reflection
 * @PgComposite
 * data class UserInfo(val id: Int, val username: String)
 *
 * // Example 2: Explicit type name and custom mapper
 * @PgComposite(name = "stats_type", mapper = StatsMapper::class)
 * data class Stats(val strength: Int, val agility: Int)
 *
 * object StatsMapper : PgCompositeMapper<Stats> {
 *     override fun fromMap(map: Map<String, Any?>) = Stats(
 *         strength = map["strength"] as Int,
 *         agility = map["agility"] as Int
 *     )
 *     override fun toMap(obj: Stats) = mapOf(
 *         "strength" to obj.strength,
 *         "agility" to obj.agility
 *     )
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgComposite(
    val name: String = "",
    val schema: String = "",
    val mapper: KClass<out PgCompositeMapper<*>> = DefaultPgCompositeMapper::class
)