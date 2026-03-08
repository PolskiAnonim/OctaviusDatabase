package org.octavius.data.exception

/**
 * Context of a database operation execution.
 *
 * Contains all the information needed to reproduce or debug a failed query,
 * including both the high-level query and the low-level SQL sent to the database.
 */
data class QueryContext(
    val sql: String,
    val parameters: Map<String, Any?>,
    val dbSql: String? = null,
    val dbParameters: List<Any?>? = null,
    val transactionStepIndex: Int? = null
) {
    override fun toString(): String {
        val width = 80
        val line = "═".repeat(width)
        val thinLine = "─".repeat(width)

        fun formatLine(content: String): String {
            return content.lines().joinToString("\n") {
                "║ " + it.padEnd(width - 2) + " ║"
            }
        }

        val paramsStr = if (parameters.isEmpty()) "none" else parameters.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        val dbParamsStr = dbParameters?.joinToString("\n") { it.toString() } ?: "none"

        return buildString {
            appendLine("╔$line╗")
            appendLine(formatLine("DATABASE EXECUTION CONTEXT"))
            appendLine("╠$line╣")

            if (transactionStepIndex != null) {
                appendLine(formatLine("Transaction Step Index: $transactionStepIndex"))
                appendLine("╟$thinLine╢")
            }

            appendLine(formatLine("HIGH-LEVEL SQL:"))
            appendLine(formatLine(sql.prependIndent("  ")))
            appendLine("╟$thinLine╢")

            appendLine(formatLine("PARAMETERS:"))
            appendLine(formatLine(paramsStr.prependIndent("  ")))

            if (dbSql != null) {
                appendLine("╟$thinLine╢")
                appendLine(formatLine("DATABASE-LEVEL SQL (SENT TO DB):"))
                appendLine(formatLine(dbSql.prependIndent("  ")))
                appendLine("╟$thinLine╢")

                appendLine(formatLine("DATABASE-LEVEL PARAMETERS:"))
                appendLine(formatLine(dbParamsStr.prependIndent("  ")))
            }

            appendLine("╚$line╝")
        }
    }

    fun withTransactionStep(stepIndex: Int) = this.copy(transactionStepIndex = stepIndex)

}