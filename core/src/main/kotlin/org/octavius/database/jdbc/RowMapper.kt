package org.octavius.database.jdbc

import java.sql.ResultSet
import java.sql.SQLException


/**
 * An interface used by [JdbcTemplate] for mapping rows of a
 * [ResultSet] on a per-row basis. Implementations of this
 * interface perform the actual work of mapping each row to a result object.
 * @param <T> the result type
 * @see JdbcTemplate
 */
internal fun interface RowMapper<T> {
    /**
     * Implementations must implement this method to map each row of data in the
     * `ResultSet`. This method should not call `next()` on the
     * `ResultSet`; it is only supposed to map values of the current row.
     * @param rs the `ResultSet` to map (pre-initialized for the current row)
     * @param rowNum the number of the current row
     * @return the result object for the current row (may be `null`)
     * @throws SQLException if an SQLException is encountered while getting
     * column values (that is, there's no need to catch SQLException)
     */
    @Throws(SQLException::class)
    fun mapRow(rs: ResultSet, rowNum: Int): T
}