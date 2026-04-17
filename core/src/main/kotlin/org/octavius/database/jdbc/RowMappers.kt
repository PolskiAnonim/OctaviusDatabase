package org.octavius.database.jdbc

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.toDataObject
import org.octavius.data.validateValue
import org.octavius.database.type.ResultSetValueExtractor
import org.octavius.database.type.registry.TypeRegistry
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Factory providing high-level [RowMapper] implementations for converting PostgreSQL `ResultSet`
 * rows into Kotlin data structures.
 *
 * This class coordinates between JDBC's mapping infrastructure and Octavius's custom
 * type conversion logic provided by [org.octavius.database.type.ResultSetValueExtractor].
 *
 * @param typeRegistry The registry containing OID-to-type mappings and custom type definitions.
 */
@Suppress("FunctionName")
internal class RowMappers(
    typeRegistry: TypeRegistry
) {
    private val valueExtractor = ResultSetValueExtractor(typeRegistry)
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Creates a mapper that converts each row into a `Map<String, Any?>`.
     *
     * Keys in the map correspond to column labels (aliases) from the SQL query.
     * Values are automatically converted to their appropriate Kotlin types.
     *
     * Ideal for:
     * - Dynamic queries where the result structure is not known at compile time.
     * - Reporting and ad-hoc data analysis.
     * - Simple queries where defining a data class is unnecessary.
     */
    fun ColumnNameMapper(): RowMapper<Map<String, Any?>> = RowMapper { rs, _ ->
        val data = mutableMapOf<String, Any?>()
        val metaData = rs.metaData

        logger.trace { "Mapping row with ${metaData.columnCount} columns using ColumnNameMapper" }
        for (i in 1..metaData.columnCount) {
            val columnName = metaData.getColumnLabel(i)
            data[columnName] = valueExtractor.extract(rs, i)
        }
        data
    }

    /**
     * Creates a mapper that extracts a single value from the first column of the result set.
     *
     * This mapper is highly optimized for "scalar" queries.
     *
     * @param kType The expected Kotlin type of the field, used for validation and nullability checks.
     * @return A mapper returning a single value or throwing [org.octavius.data.exception.ConversionException] on type mismatch or unexpected null.
     */
    fun SingleValueMapper(kType: KType): RowMapper<Any?> = RowMapper { rs, _ ->
        val value = valueExtractor.extract(rs, 1)
        if (value == null) {
            if (!kType.isMarkedNullable) {
                throw ConversionException(
                    messageEnum = ConversionExceptionMessage.UNEXPECTED_NULL_VALUE,
                    value = null,
                    targetType = kType.toString()
                )
            }
            return@RowMapper null
        }

        validateValue(value, kType)
    }

    /**
     * Creates a mapper that converts each row into an instance of a specified Kotlin `data class`.
     *
     * The mapping process follows these steps:
     * 1. Extracts the row as a [Map] using [ColumnNameMapper].
     * 2. Uses [toDataObject][toDataObject] (reflection) to instantiate the class.
     *
     * Naming conventions:
     * Column names in SQL (e.g., `user_id`) are automatically matched to class properties (e.g., `userId`)
     * using the standard `snake_case` -> `camelCase` transformation,
     * for custom name mapping use [MapKey][org.octavius.data.MapKey] annotation.
     *
     * @param T The target type.
     * @param kClass The Kotlin class to map into.
     */
    fun <T : Any> DataObjectMapper(kClass: KClass<T>): RowMapper<T> {
        val baseMapper = ColumnNameMapper()
        return RowMapper { rs, rowNum ->
            logger.trace { "Mapping row to ${kClass.simpleName} using DataObjectMapper" }
            val map = baseMapper.mapRow(rs, rowNum)

            val result = map.toDataObject(kClass)
            logger.trace { "Successfully mapped row to ${kClass.simpleName}" }
            result
        }
    }
}