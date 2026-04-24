package io.github.octaviusframework.db.core.builder

import io.github.octaviusframework.db.api.builder.LockWaitMode
import io.github.octaviusframework.db.api.builder.SelectQueryBuilder
import io.github.octaviusframework.db.api.exception.checkBuilder
import io.github.octaviusframework.db.api.exception.requireBuilder
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import io.github.octaviusframework.db.core.jdbc.RowMappers
import io.github.octaviusframework.db.core.type.KotlinToPostgresConverter

/**
 * Internal implementation of [SelectQueryBuilder] for building SQL SELECT queries.
 * Inherits from [AbstractQueryBuilder] to reuse WITH clause logic and terminal methods.
 */
internal class DatabaseSelectQueryBuilder(
    jdbcTemplate: JdbcTemplate,
    rowMappers: RowMappers,
    kotlinToPostgresConverter: KotlinToPostgresConverter,
    private val selectClause: String
) : AbstractQueryBuilder<SelectQueryBuilder>(jdbcTemplate, kotlinToPostgresConverter, rowMappers, null), SelectQueryBuilder {
    override val canReturnResultsByDefault = true
    //------------------------------------------------------------------------------------------------------------------
    //                                    INTERNAL SELECT CLAUSE STATE
    //------------------------------------------------------------------------------------------------------------------

    private var fromClause: String? = null
    private var whereCondition: String? = null
    private var groupByClause: String? = null
    private var havingClause: String? = null
    private var orderByClause: String? = null
    private var limitValue: Long? = null
    private var offsetValue: Long? = null
    private var forUpdateOf: String? = null
    private var forUpdateMode: LockWaitMode? = null
    private var forUpdate: Boolean = false

    //------------------------------------------------------------------------------------------------------------------
    //                                      BUILDING SELECT CLAUSE
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Sets the FROM clause.
     *
     * The programmer is fully responsible for passing correct SQL syntax.
     * The method does not perform any formatting or wrapping in parentheses.
     *
     * Examples of valid values:
     * - `"legions"`
     * - `"legions l"`
     * - `"legions l JOIN provinces p ON l.province_id = p.id"`
     * - `"(SELECT id FROM active_legions) AS l"`
     * - `"UNNEST(@ids) AS id"`
     */
    override fun from(source: String): SelectQueryBuilder = apply {
        this.fromClause = source
    }

    override fun fromSubquery(subquery: String, alias: String?): SelectQueryBuilder {
        val query = if (alias == null) {
            "($subquery)"
        } else {
            "($subquery) AS $alias"
        }
        return this.from(query)
    }

    override fun where(condition: String?): SelectQueryBuilder = apply {
        this.whereCondition = condition
    }

    override fun groupBy(columns: String?): SelectQueryBuilder = apply {
        this.groupByClause = columns
    }

    override fun having(condition: String?): SelectQueryBuilder = apply {
        this.havingClause = condition
    }

    override fun orderBy(ordering: String?): SelectQueryBuilder = apply {
        this.orderByClause = ordering
    }

    override fun limit(count: Long?): SelectQueryBuilder = apply {
        this.limitValue = count
    }

    override fun offset(position: Long): SelectQueryBuilder = apply {
        this.offsetValue = position
    }

    override fun page(page: Long, size: Long): SelectQueryBuilder = apply {
        requireBuilder(page >= 0) { "Page number cannot be negative." }
        requireBuilder(size > 0) { "Page size must be positive." }
        this.offsetValue = page * size
        this.limitValue = size
    }

    override fun forUpdate(of: String?, mode: LockWaitMode?): SelectQueryBuilder = apply {
        this.forUpdate = true
        this.forUpdateOf = of
        this.forUpdateMode = mode
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                              BUILDING SQL
    //------------------------------------------------------------------------------------------------------------------

    override fun buildSql(): String {
        checkBuilder(!selectClause.isBlank()) { "Cannot build a SELECT query without a SELECT clause." }
        // Condition: FROM must exist OR none of the dependent clauses can exist
        checkBuilder(
            !fromClause.isNullOrBlank() || (whereCondition == null && groupByClause == null && orderByClause == null)
        ) {
            "WHERE, GROUP BY, or ORDER BY clauses require a FROM clause."
        }
        checkBuilder(
            havingClause.isNullOrBlank() || !groupByClause.isNullOrBlank()
        ) {
            "HAVING clause requires a GROUP BY clause."
        }
        val sqlBuilder = StringBuilder(buildWithClause())

        sqlBuilder.append("SELECT ").append(selectClause)
        fromClause?.let { sqlBuilder.append("\nFROM ").append(it) }
        whereCondition?.takeIf { it.isNotBlank() }?.let { sqlBuilder.append("\nWHERE ").append(it) }
        groupByClause?.takeIf { it.isNotBlank() }?.let { sqlBuilder.append("\nGROUP BY ").append(it) }
        havingClause?.takeIf { it.isNotBlank() }?.let { sqlBuilder.append("\nHAVING ").append(it) }
        orderByClause?.takeIf { it.isNotBlank() }?.let { sqlBuilder.append("\nORDER BY ").append(it) }
        limitValue?.takeIf { it > 0 }?.let { sqlBuilder.append("\nLIMIT ").append(it) }
        offsetValue?.takeIf { it >= 0 }?.let { sqlBuilder.append("\nOFFSET ").append(it) }
        if (forUpdate) {
            sqlBuilder.append("\nFOR UPDATE")
            forUpdateOf?.takeIf { it.isNotBlank() }?.let { sqlBuilder.append(" OF ").append(it) }
            forUpdateMode?.let { sqlBuilder.append(" ").append(it.name.replace('_', ' ')) }
        }

        return sqlBuilder.toString()
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                          COPY
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Creates and returns a deep copy of this builder.
     * Enables safe creation of query variants from a shared base without modifying the original.
     *
     * ```kotlin
     * val base = dataAccess.select("*").from("legions").orderBy("name ASC")
     * val onMarch    = base.copy().where("status = 'ON_MARCH'")
     * val garrisoned = base.copy().where("status = 'GARRISONED'")
     * // 'base' is unchanged — both variants are independent
     * ```
     */
    override fun copy(): DatabaseSelectQueryBuilder {
        // 1. Create a new, "clean" instance using the main constructor
        val newBuilder = DatabaseSelectQueryBuilder(
            this.jdbcTemplate,
            this.rowMappers,
            this.kotlinToPostgresConverter,
            this.selectClause
        )

        // 2. Copy state from base class using helper method
        newBuilder.copyBaseStateFrom(this)

        // 3. Copy state specific to THIS class
        newBuilder.fromClause = this.fromClause
        newBuilder.whereCondition = this.whereCondition
        newBuilder.groupByClause = this.groupByClause
        newBuilder.havingClause = this.havingClause
        newBuilder.orderByClause = this.orderByClause
        newBuilder.limitValue = this.limitValue
        newBuilder.offsetValue = this.offsetValue
        newBuilder.forUpdate = this.forUpdate
        newBuilder.forUpdateOf = this.forUpdateOf
        newBuilder.forUpdateMode = this.forUpdateMode

        // 4. Return fully configured copy
        return newBuilder
    }
}
