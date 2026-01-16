package org.octavius.data.annotation



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
 * @param name Optional, explicit name of the corresponding type in PostgreSQL database.
 *             If left empty, the name will be generated automatically
 *             according to the `CamelCase` -> `snake_case` convention.
 *
 * ### Examples
 * ```kotlin
 * // Example 1: Using default naming convention
 * // `UserInfo` class will be mapped to `user_info` type in PostgreSQL.
 * @PgComposite
 * data class UserInfo(val id: Int, val username: String)
 *
 * // Example 2: Explicit type name specification
 * // `AddressDetails` class will be mapped to `address_type` type in PostgreSQL.
 * @PgComposite(name = "address_type")
 * data class AddressDetails(val street: String, val city: String)
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgComposite(
    val name: String = ""
)