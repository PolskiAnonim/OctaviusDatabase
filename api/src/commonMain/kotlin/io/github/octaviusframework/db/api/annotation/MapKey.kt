package io.github.octaviusframework.db.api.annotation

/**
 * Annotation used to specify a custom key for a property
 * during object to/from map conversion (functions `toDataMap` and `toDataObject`).
 *
 * By default, the property name is used with snake_case <-> camelCase conversion. This annotation
 * allows overriding it, which is useful when map key names should not match property names —
 * e.g., when a foreign key column is named `citizen_id` but the property is simply `citizen`.
 *
 * ```kotlin
 * data class TributeRecord(
 *     val id: Int,
 *     @MapKey("citizen")
 *     val citizenId: Int,         // stored as "citizen" in the map, not "citizen_id"
 *     @MapKey("levy_province")
 *     val originProvince: String  // stored as "levy_province" instead of "origin_province"
 * )
 * ```
 *
 * @property name Key name that will be used in the map.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class MapKey(val name: String)