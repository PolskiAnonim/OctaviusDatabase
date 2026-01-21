package org.octavius.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.toDataObject
import org.octavius.data.validateValue
import org.octavius.database.type.ResultSetValueExtractor
import org.octavius.database.type.registry.TypeRegistry
import org.springframework.jdbc.core.RowMapper
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Factory providing various `RowMapper` implementations for `ResultSet` conversion.
 *
 * Creates [ResultSetValueExtractor] internally from the provided [TypeRegistry].
 *
 * @param typeRegistry Type registry for value extraction
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
     * Mapper mapping to `Map<String, Any?>`.
     * Uses only column name as key. Ideal for reports and simple queries.
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
     * Mapper mapping result from a single column to its value.
     * Used for queries like `SELECT COUNT(*)`, `SELECT id FROM ...` etc.
     */
    fun <T : Any> SingleValueMapper(kType: KType): RowMapper<T?> = RowMapper { rs, _ ->
        val value = valueExtractor.extract(rs, 1) ?: return@RowMapper null

        @Suppress("UNCHECKED_CAST")
        validateValue(value, kType) as T?
    }

    /**
     * Generic mapper that converts a row to a data class object.
     * First maps the row to Map<String, Any?> using ColumnNameMapper,
     * then uses reflection (via `toDataObject`) to create a class instance.
     * @param kClass Target object class.
     */
    fun <T : Any> DataObjectMapper(kClass: KClass<T>): RowMapper<T> {
        val baseMapper = ColumnNameMapper()
        return RowMapper { rs, rowNum ->
            logger.trace { "Mapping row to ${kClass.simpleName} using DataObjectMapper" }
            val map = baseMapper.mapRow(rs, rowNum)

            try {
                // Use existing logic to convert map to object
                val result = map.toDataObject(kClass)
                logger.trace { "Successfully mapped row to ${kClass.simpleName}" }
                result
            } catch (e: Exception) { // This should always be ConversionException
                logger.error(e) { e }
                throw e
            }
        }
    }
}
