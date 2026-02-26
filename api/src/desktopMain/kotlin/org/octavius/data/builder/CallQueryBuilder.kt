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
     * Overrides the PostgreSQL type used in `NULL::type` slots for OUT parameters.
     *
     * Required for procedures whose OUT parameters use pseudo-types (`anyarray`,
     * `anyelement`, etc.) — PostgreSQL rejects casts to pseudo-types, so the
     * caller must supply the concrete type.
     *
     * @param outTypes Map of OUT parameter name to concrete PostgreSQL type name
     *                 (e.g. `"result" to "int4[]"`).
     */
    fun outTypes(outTypes: Map<String, String>): CallQueryBuilder

    /**
     * Executes the procedure with the given named parameters.
     *
     * @param params Named parameters matching the procedure's IN/INOUT arguments.
     * @return [DataResult] containing a map of OUT/INOUT parameter names to their converted values.
     */
    fun execute(params: Map<String, Any?> = emptyMap()): DataResult<Map<String, Any?>>
}

fun CallQueryBuilder.outTypes(vararg outTypes: Pair<String, String>): CallQueryBuilder =
    outTypes(outTypes.toMap())

fun CallQueryBuilder.execute(vararg params: Pair<String, Any?>): DataResult<Map<String, Any?>> =
    execute(params.toMap())
