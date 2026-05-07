package io.github.octaviusframework.db.core.type

import io.github.octaviusframework.db.core.type.registry.TypeCategory
import io.github.octaviusframework.db.core.type.registry.TypeRegistry
import org.postgresql.jdbc.PgResultSet
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
        // Unwrap to get PostgreSQL-specific OID directly from ResultSet
        val pgRs = rs.unwrap(PgResultSet::class.java)
        val oid = pgRs.getColumnOID(columnIndex)

        // void (OID: 2278) is a special JDBC-level case — not a real column type.
        // Functions like pg_notify() return void; mapping it to Unit allows
        // SELECT-ing void functions via toField<Unit>() without error.
        if (oid == 2278) return Unit

        val typeCategory = typeRegistry.getCategory(oid)

        // Main logic: path distinction
        return when (typeCategory) {
            TypeCategory.STANDARD -> extractStandardType(rs, columnIndex, oid)
            else -> {
                val rawValue = rs.getString(columnIndex)
                stringConverter.convert(rawValue, oid)
            }
        }
    }


    /**
     * Fast path for standard types.
     */
    private fun extractStandardType(rs: ResultSet, columnIndex: Int, oid: Int): Any? {
        val handler = typeRegistry.getHandlerByOid(oid)

        // 1. Try to use dedicated "fast path" if it exists.
        handler?.fromResultSet?.let { fastPath ->
            return fastPath(rs, columnIndex)
        }

        // 2. If there's no fast path (handler is null or fromResultSet is null),
        //    use the universal but slower path based on String conversion.
        val rawValue = rs.getString(columnIndex)
        return stringConverter.convert(rawValue, oid)
    }
}
