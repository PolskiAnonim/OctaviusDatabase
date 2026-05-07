package io.github.octaviusframework.db.api.type

import java.sql.ResultSet
import kotlin.reflect.KClass

/**
 * Interface for handling conversion between Kotlin types and PostgreSQL types.
 *
 * Registered converters are automatically used for:
 * - Single values in queries
 * - Elements in PostgreSQL arrays
 * - Fields within composite types
 *
 * @param T The Kotlin type this converter handles.
 */
interface TypeHandler<T : Any> {
    val pgTypeName: String
    val pgSchema: String
    val kotlinClass: KClass<T>
    val fromResultSet: ((ResultSet, Int) -> T?)?
    val fromString: (String) -> T
    val toJdbc: (T) -> Any
    val toPgString: (T) -> String
}
