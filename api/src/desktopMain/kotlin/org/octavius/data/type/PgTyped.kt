package org.octavius.data.type

/**
 * Wraps a value to explicitly specify the target PostgreSQL type.
 *
 * Causes addition of a type cast (`::pgType`) to the generated SQL fragment.
 * Useful for handling type ambiguities, e.g., with arrays.
 *
 * @param value Value to embed in the query (avoid using with data classes where this is added automatically!).
 * @param pgType PostgreSQL type name to which the value should be cast (e.g., "text[]", "jsonb").
 */
data class PgTyped(val value: Any?, val pgType: String)


/**
 * Wraps a value in PgTyped to explicitly specify the target PostgreSQL type
 * in a type-safe manner.
 */
fun Any?.withPgType(pgType: PgStandardType): PgTyped = PgTyped(this, pgType.typeName)

/**
 * Wraps a value in PgTyped in a more fluid way.
 * Use this method only for custom or rare types that
 * are not defined in `PgStandardType`.
 *
 * @see PgStandardType
 */
fun Any?.withPgType(pgType: String): PgTyped = PgTyped(this, pgType)