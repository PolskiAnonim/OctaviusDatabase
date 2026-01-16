package org.octavius.data

/**
 * Simple container for an SQL query fragment and its parameters.
 *
 * Serves as a data carrier that safely combines SQL with values,
 * preventing SQL Injection. Does not impose any query building style.
 * This is essentially a (String, Map) pair.
 *
 * @property sql Fragment of SQL code, e.g., `status = :status OR user_id = ANY(:userIds)`.
 * @property params Map of parameters used in [sql].
 */
data class QueryFragment(
    val sql: String,
    val params: Map<String, Any?> = emptyMap()
)

/**
 * Joins a list of fragments into one, working analogously to standard [joinToString],
 * but with parameter merging support.
 *
 * Postfix and prefix are mainly applicable for [org.octavius.data.builder.RawQueryBuilder]. The preferred way to add clauses themselves
 * is to use the appropriate builders.
 *
 * 1.  **Safely merges parameter maps**, throwing an error in case of key conflicts.
 * 2.  Ignores empty fragments. If after filtering the list is empty, returns an empty QueryFragment
 *     (ignoring prefix and postfix to avoid generating e.g., an empty "WHERE ()").
 * 3.  Wraps each *single* fragment in parentheses to preserve operator precedence.
 *
 * @param separator Character sequence separating fragments, e.g., " AND ".
 * @param prefix Character sequence inserted at the beginning of the entire resulting string (e.g., "WHERE ").
 * @param postfix Character sequence inserted at the end of the entire resulting string (e.g., ";").
 * @param addParenthesis Whether to add parentheses around fragments - default true
 *
 * @return New, joined [QueryFragment].
 */
fun List<QueryFragment>.join(separator: String, prefix: String = "", postfix: String = "", addParenthesis: Boolean = true): QueryFragment {
    val significantFragments = this.filter { it.sql.isNotBlank() }
    // If there are no significant fragments, we return an empty object.
    if (significantFragments.isEmpty()) {
        return QueryFragment("")
    }
    val finalSql = significantFragments.joinToString(separator, prefix, postfix) { if (addParenthesis) "(${it.sql})" else it.sql }

    val finalParams = mutableMapOf<String, Any?>()
    significantFragments.forEach { fragment ->
        fragment.params.forEach { (key, value) ->
            require(!finalParams.containsKey(key) || finalParams[key] == value) {
                "Duplicate parameter key '$key' with different values found while joining QueryFragments. " +
                        "Parameter names must be unique across all joined fragments."
            }
            finalParams[key] = value
        }
    }

    return QueryFragment(finalSql, finalParams)
}