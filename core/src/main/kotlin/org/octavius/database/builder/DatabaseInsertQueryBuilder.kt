package org.octavius.database.builder

import org.octavius.data.builder.InsertQueryBuilder
import org.octavius.data.builder.OnConflictClauseBuilder
import org.octavius.database.RowMappers
import org.octavius.database.type.KotlinToPostgresConverter
import org.springframework.jdbc.core.JdbcTemplate

/** Internal implementation of [InsertQueryBuilder] for building SQL INSERT statements. */
internal class DatabaseInsertQueryBuilder(
    jdbcTemplate: JdbcTemplate,
    kotlinToPostgresConverter: KotlinToPostgresConverter,
    rowMappers: RowMappers,
    table: String
) : AbstractQueryBuilder<InsertQueryBuilder>(jdbcTemplate, kotlinToPostgresConverter, rowMappers, table), InsertQueryBuilder {
    override val canReturnResultsByDefault = false
    private var explicitColumns: List<String>? = null
    private val valuePlaceholders = mutableMapOf<String, String>()
    private var selectSource: String? = null
    private var onConflictBuilder: DatabaseOnConflictClauseBuilder? = null

    override fun columns(vararg columns: String): InsertQueryBuilder = apply {
        this.explicitColumns = columns.toList()
    }

    override fun valuesExpressions(expressions: Map<String, String>): InsertQueryBuilder = apply {
        check(selectSource == null) { "Cannot use valuesExpressions() when fromSelect() has already been called." }
        expressions.forEach { (key, value) ->
            valuePlaceholders[key] = value
        }
    }

    override fun valueExpression(column: String, expression: String): InsertQueryBuilder = apply {
        check(selectSource == null) { "Cannot use valueExpression() when fromSelect() has already been called." }
        valuePlaceholders[column] = expression
    }

    override fun values(data: Map<String, Any?>): InsertQueryBuilder {
        check(selectSource == null) { "Cannot use values() when fromSelect() has already been called." }
        val placeholders = data.keys.associateWith { key -> ":$key" }
        // Delegate to low-level method
        return this.valuesExpressions(placeholders)
    }

    override fun values(values: List<String>): InsertQueryBuilder {
        check(selectSource == null) { "Cannot use values() when fromSelect() has already been called." }
        val placeholders = values.associateWith { key -> ":$key" }
        // Delegate to low-level method
        return this.valuesExpressions(placeholders)
    }

    override fun value(column: String): InsertQueryBuilder {
        check(selectSource == null) { "Cannot use value() when fromSelect() has already been called." }
        // Delegate to low-level method
        return this.valueExpression(column, ":$column")
    }

    override fun fromSelect(query: String): InsertQueryBuilder = apply {
        check(valuePlaceholders.isEmpty()) { "Cannot use fromSelect() when values() has already been called." }

        this.selectSource = query
    }

    /**
     * Configures the ON CONFLICT clause in a fluent and safe manner.
     * Example:
     * .onConflict {
     *   onColumns("email")
     *   doUpdate("last_login = NOW()")
     * }
     */
    override fun onConflict(config: OnConflictClauseBuilder.() -> Unit): InsertQueryBuilder = apply {
        if (onConflictBuilder == null) {
            onConflictBuilder = DatabaseOnConflictClauseBuilder()
        }
        onConflictBuilder!!.apply(config)
    }

    override fun buildSql(): String {
        val hasValues = valuePlaceholders.isNotEmpty()
        val hasSelect = selectSource != null

        check(hasValues || hasSelect) { "Cannot build an INSERT statement without values or a SELECT source." }

        val sql = StringBuilder(buildWithClause())

        // Determine columns: explicit > from values > none (for fromSelect)
        val targetColumns = explicitColumns
            ?: valuePlaceholders.keys.toList().ifEmpty { null }

        if (targetColumns != null) {
            val columnsSql = targetColumns.joinToString(", ")
            sql.append("INSERT INTO $table ($columnsSql)")
        } else {
            // No columns specified - valid for fromSelect()
            sql.append("INSERT INTO $table")
        }

        if (hasValues) {
            val columnsForValues = targetColumns
                ?: error("Cannot build VALUES clause without columns")
            val placeholders = columnsForValues.joinToString(", ") { key ->
                valuePlaceholders[key]
                    ?: error("No value or expression provided for column '$key'")
            }
            sql.append("\nVALUES ($placeholders)")
        } else {
            sql.append("\n").append(selectSource!!)
        }

        onConflictBuilder?.let { builder ->
            val target = builder.target ?: throw IllegalStateException("ON CONFLICT target (columns or constraint) must be specified.")
            val action = builder.action ?: throw IllegalStateException("ON CONFLICT action (doNothing or doUpdate) must be specified.")

            // onConstraint already contains "ON CONSTRAINT", so we don't add parentheses
            val targetSql = if (target.startsWith("ON CONSTRAINT")) target else "($target)"

            sql.append("\nON CONFLICT $targetSql $action")
        }

        sql.append(buildReturningClause())

        return sql.toString()
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                          COPY
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Creates and returns a deep copy of this builder.
     * Enables safe creation of query variants without modifying the original.
     */
    override fun copy(): DatabaseInsertQueryBuilder {
        // 1. Create a new, "clean" instance using the main constructor
        val newBuilder = DatabaseInsertQueryBuilder(
            this.jdbcTemplate,
            this.kotlinToPostgresConverter,
            this.rowMappers,
            this.table!! // We know table is not null for INSERT
        )

        newBuilder.copyBaseStateFrom(this)

        newBuilder.explicitColumns = this.explicitColumns // List is immutable, safe to share reference
        newBuilder.valuePlaceholders.putAll(this.valuePlaceholders) // Copy map contents, not reference!
        newBuilder.selectSource = this.selectSource
        // Also copy onConflictBuilder if it exists! Use its own copy() method.
        newBuilder.onConflictBuilder = this.onConflictBuilder?.copy()

        // 4. Return fully configured copy
        return newBuilder
    }

}

internal class DatabaseOnConflictClauseBuilder : OnConflictClauseBuilder {
    internal var target: String? = null
    internal var action: String? = null

    /** Defines the conflict target (columns). */
    override fun onColumns(vararg columns: String) {
        target = columns.joinToString(", ")
    }

    /** Defines the conflict target (constraint name). */
    override fun onConstraint(constraintName: String) {
        target = "ON CONSTRAINT $constraintName"
    }

    /** Defines the DO NOTHING action. */
    override fun doNothing() {
        action = "DO NOTHING"
    }

    /**
     * Defines the DO UPDATE action.
     */
    override fun doUpdate(setExpression: String, whereCondition: String?) {
        require(setExpression.isNotBlank()) { "doUpdate cannot be blank." }
        val updateAction = StringBuilder("DO UPDATE SET $setExpression")
        whereCondition?.let {
            updateAction.append(" WHERE $it")
        }
        action = updateAction.toString()
    }

    /**
     * Overload for vararg Pair
     */
    override fun doUpdate(vararg setPairs: Pair<String, String>, whereCondition: String?) {
        val setExpression = setPairs.joinToString(",\n") { (column, expression) ->
            "$column = $expression"
        }

        // Call the original method to avoid duplicating clause building logic
        doUpdate(setExpression, whereCondition)
    }

    /**
     * Overload for Map
     */
    override fun doUpdate(setMap: Map<String, String>, whereCondition: String?) {
        val setExpression = setMap.map { (column, expression) ->
            "$column = $expression"
        }.joinToString(",\n")

        doUpdate(setExpression, whereCondition)
    }

    /**
     * Creates a copy of this ON CONFLICT clause builder.
     */
    fun copy(): DatabaseOnConflictClauseBuilder {
        val newBuilder = DatabaseOnConflictClauseBuilder()
        newBuilder.target = this.target
        newBuilder.action = this.action
        return newBuilder
    }
}
