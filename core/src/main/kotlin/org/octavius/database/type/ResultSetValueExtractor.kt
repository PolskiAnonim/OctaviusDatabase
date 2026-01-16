package org.octavius.database.type

import org.octavius.database.type.registry.TypeCategory
import org.octavius.database.type.registry.TypeRegistry
import java.sql.ResultSet

/**
 * Intelligently extracts values from ResultSet.
 * Uses "fast path" (native rs.get*() methods) for standard types
 * and delegates to PostgresToKotlinConverter for complex types (enum, composite, array).
 */
internal class ResultSetValueExtractor(
    private val typeRegistry: TypeRegistry
) {
    private val stringConverter = PostgresToKotlinConverter(typeRegistry)

    fun extract(rs: ResultSet, columnIndex: Int): Any? {
        val pgTypeName = rs.metaData.getColumnTypeName(columnIndex)
        val typeCategory = typeRegistry.getCategory(pgTypeName)

        // Main logic: path distinction
        return when (typeCategory) {
            TypeCategory.STANDARD -> extractStandardType(rs, columnIndex, pgTypeName)
            else -> {
                val rawValue = rs.getString(columnIndex)
                stringConverter.convert(rawValue, pgTypeName)
            }
        }
    }


    /**
     * Fast path for standard types.
     */
    private fun extractStandardType(rs: ResultSet, columnIndex: Int, pgTypeName: String): Any? {
        val handler = StandardTypeMappingRegistry.getHandler(pgTypeName)

        // 1. Try to use dedicated "fast path" if it exists.
        handler?.fromResultSet?.let { fastPath ->
            return fastPath(rs, columnIndex)
        }

        // 2. If there's no fast path (handler is null or fromResultSet is null),
        //    use the universal but slower path based on String conversion.
        val rawValue = rs.getString(columnIndex)
        return stringConverter.convert(rawValue, pgTypeName)
    }
}
