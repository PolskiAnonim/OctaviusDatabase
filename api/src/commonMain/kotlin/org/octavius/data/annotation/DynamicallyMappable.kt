package org.octavius.data.annotation

/**
 * Marks a `data class`, `enum class`, or `value class` as a target for dynamic mapping from `dynamic_dto` type
 * in PostgreSQL.
 *
 * This annotation is used by `TypeRegistryLoader` to build a safe map of
 * keys (conventional names) to Kotlin classes. This allows deserialization of nested
 * structures created on the fly in SQL queries, without the need to define
 * a formal composite type (`CREATE TYPE`) in the database.
 * NOTE: Usage also requires `@Serializable` annotation!
 *
 * @param typeName Conventional identifier (key) that will be used in the SQL function
 *                 `dynamic_dto('typeName', ...)` to indicate which class
 *                 the result should be mapped to.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DynamicallyMappable(val typeName: String)