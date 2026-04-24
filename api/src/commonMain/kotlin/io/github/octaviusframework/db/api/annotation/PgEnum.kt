package io.github.octaviusframework.db.api.annotation

import io.github.octaviusframework.db.api.util.CaseConvention

/**
 * Marks an `enum` class as a data type that can be mapped to an `ENUM` type
 * in PostgreSQL database.
 *
 * This annotation is crucial for `TypeRegistry`, which scans the classpath for
 * marked classes to automatically build mapping between Kotlin types
 * and `ENUM` types in PostgreSQL.
 *
 * **Naming convention:**
 * By default, the type name in PostgreSQL is derived from the simple class name
 * by converting from `CamelCase` to `snake_case` (e.g., `LegionStatus` class will be
 * mapped to `legion_status` type). Enum values are by default mapped from PascalCase to
 * `SNAKE_CASE_UPPER` (e.g., `OnMarch` -> `ON_MARCH`).
 *
 * **Explicit name specification:**
 * You can override the default type name by providing it in the [name] parameter.
 *
 * ### Examples
 * ```kotlin
 * // Example 1: Using default naming convention
 * // `LegionStatus` class will be mapped to `legion_status` type in PostgreSQL.
 * // Values (OnMarch, InBattle) will be mapped as 'ON_MARCH', 'IN_BATTLE'.
 * @PgEnum
 * enum class LegionStatus { Garrisoned, OnMarch, InBattle, Victorious }
 *
 * // Example 2: Explicit type name and lowercase value convention
 * // `Magistrature` class will be mapped to `magistrature_rank` type.
 * // Values (Quaestor, Aedile) will be mapped as 'quaestor', 'aedile'.
 * @PgEnum(name = "magistrature_rank", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
 * enum class Magistrature { Quaestor, Aedile, Praetor, Consul }
 * ```
 * @param name Optional, explicit name of the corresponding type in PostgreSQL database.
 *             If left empty, the name will be generated automatically
 *             according to the `CamelCase` -> `snake_case` convention.
 * @param schema Optional, explicit schema name. If left empty, the type will be resolved
 *               based on the database `search_path`, or by searching for an unambiguous
 *               match in all scanned schemas.
 * @param pgConvention Naming convention for enum values in PostgreSQL.
 * @param kotlinConvention Naming convention for enum values in Kotlin.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgEnum(
    val name: String = "",
    val schema: String = "",
    val pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
    val kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
)
