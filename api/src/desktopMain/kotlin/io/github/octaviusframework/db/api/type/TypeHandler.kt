package io.github.octaviusframework.db.api.type

import java.sql.ResultSet
import kotlin.reflect.KClass

/**
 * Interface for handling conversion between Kotlin types and PostgreSQL types.
 *
 * Provides methods for both reading (from JDBC ResultSet or String)
 * and writing (to JDBC parameter or PostgreSQL-compatible String).
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
