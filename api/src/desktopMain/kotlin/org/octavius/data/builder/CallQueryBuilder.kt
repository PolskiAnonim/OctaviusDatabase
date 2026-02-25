package org.octavius.data.builder

import org.octavius.data.DataResult

/**
 * Builder for executing PostgreSQL stored procedures via `CALL`.
 *
 * OUT/INOUT parameter values are returned as a map. If the procedure
 * has no OUT/INOUT parameters, the result map is empty.
 */
interface CallQueryBuilder {

    /**
     * Executes the procedure with the given named parameters.
     *
     * @param params Named parameters matching the procedure's IN/INOUT arguments.
     * @return [DataResult] containing a map of OUT/INOUT parameter names to their converted values.
     */
    fun execute(params: Map<String, Any?> = emptyMap()): DataResult<Map<String, Any?>>
}

fun CallQueryBuilder.execute(vararg params: Pair<String, Any?>): DataResult<Map<String, Any?>> =
    execute(params.toMap())
