package org.octavius.data.type

/**
 * Represents a qualified PostgreSQL name (schema + object name).
 * Handles quoting correctly even if names contain dots.
 */
data class QualifiedName(
    val schema: String,
    val name: String,
    val isArray: Boolean = false
) {
    override fun toString(): String {
        val base = if (schema.isBlank()) name else "$schema.$name"
        return if (isArray) "$base[]" else base
    }

    /**
     * Escapes a PostgreSQL identifier (e.g. table name, type name) by wrapping it in double quotes
     * and escaping any internal double quotes.
     */
    private fun String.quoteIdentifier(): String {
        return buildString(this.length + 2) {
            append('"')
            for (c in this@quoteIdentifier) {
                if (c == '"') append('"')
                append(c)
            }
            append('"')
        }
    }

    /**
     * Returns a SQL-safe quoted representation (e.g., "my.schema"."my.type"[]).
     */
    fun quote(): String {
        val quotedBase = if (schema.isBlank()) {
            name.quoteIdentifier()
        } else {
            "${schema.quoteIdentifier()}.${name.quoteIdentifier()}"
        }
        return if (isArray) "$quotedBase[]" else quotedBase
    }

    fun asArray(): QualifiedName = copy(isArray = true)

    companion object {
        fun from(fullName: String): QualifiedName {
            val isArray = fullName.endsWith("[]")
            val cleanName = if (isArray) fullName.dropLast(2) else fullName

            if ("." !in cleanName) return QualifiedName("", cleanName, isArray)
            val parts = cleanName.split(".")
            return QualifiedName(parts.dropLast(1).joinToString("."), parts.last(), isArray)
        }
    }
}
