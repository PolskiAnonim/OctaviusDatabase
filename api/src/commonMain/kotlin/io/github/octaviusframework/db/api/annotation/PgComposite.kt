package io.github.octaviusframework.db.api.annotation

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
    fun toDataObject(map: Map<String, Any?>): T

    /**
     * Converts the provided object [obj] to a Map.
     * The map keys should match the PostgreSQL composite attribute names.
     */
    fun toDataMap(obj: T): Map<String, Any?>
}

/**
 * Internal marker to indicate that no explicit mapper is provided.
 */
object DefaultPgCompositeMapper : PgCompositeMapper<Any> {
    override fun toDataObject(map: Map<String, Any?>): Any = throw UnsupportedOperationException()
    override fun toDataMap(obj: Any): Map<String, Any?> = throw UnsupportedOperationException()
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
 * by converting from `CamelCase` to `snake_case` (e.g., `ProvinceRecord` class will be
 * mapped to `province_record` type).
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
 *
 * ### Examples
 * ```kotlin
 * // Example 1: Using default naming convention and reflection
 * // `LegionPost` class will be mapped to `legion_post` composite type in PostgreSQL.
 * @PgComposite
 * data class LegionPost(val name: String, val province: String)
 *
 * // Example 2: Explicit type name and custom mapper
 * @PgComposite(name = "battle_record", mapper = BattleRecordMapper::class)
 * data class BattleRecord(val location: String, val outcome: String)
 *
 * object BattleRecordMapper : PgCompositeMapper<BattleRecord> {
 *     override fun toDataObject(map: Map<String, Any?>) = BattleRecord(
 *         location = map["location"] as String,
 *         outcome = map["outcome"] as String
 *     )
 *     override fun toDataMap(obj: BattleRecord) = mapOf(
 *         "location" to obj.location,
 *         "outcome" to obj.outcome
 *     )
 * }
 * ```
 * @param name Optional, explicit name of the corresponding type in PostgreSQL database.
 *             If left empty, the name will be generated automatically
 *             according to the `CamelCase` -> `snake_case` convention.
 * @param schema Optional, explicit schema name. If left empty, the type will be resolved
 *               based on the database `search_path`, or by searching for an unambiguous
 *               match in all scanned schemas.
 * @param mapper Optional, custom mapper implementation to use instead of reflection.
 *               Must implement [PgCompositeMapper].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgComposite(
    val name: String = "",
    val schema: String = "",
    val mapper: KClass<out PgCompositeMapper<*>> = DefaultPgCompositeMapper::class
)