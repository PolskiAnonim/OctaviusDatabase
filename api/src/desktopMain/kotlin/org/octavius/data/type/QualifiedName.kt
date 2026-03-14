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
     * and escaping any internal double quotes if it contains special characters.
     */
    private fun String.quoteIdentifier(): String {
        if (this.isBlank()) return ""
        // If already quoted, we assume it's correctly escaped and return as is.
        if (this.startsWith('"') && this.endsWith('"')) return this

        // According to PostgreSQL rules, unquoted identifiers must start with a letter or underscore,
        // and can contain letters, underscores, digits, or dollar signs.
        // If it starts with a digit, or contains any other character (dots, spaces, quotes, dashes, etc.),
        // it MUST be quoted to be handled correctly as a single identifier.
        val shouldQuote = this[0].isDigit() || this.any { char ->
            !(char.isLetter() || char == '_' || char == '$' || char.isDigit())
        }

        if (shouldQuote) {
            return buildString(this.length + 2) {
                append('"')
                for (c in this@quoteIdentifier) {
                    if (c == '"') append('"')
                    append(c)
                }
                append('"')
            }
        }
        
        // Otherwise, we don't add quotes "artificially" to stay explicit and allow PG folding.
        return this
    }

    /**
     * Returns a SQL-safe representation. 
     * Respects existing quotes and adds new ones only if necessary (e.g. dots in name).
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
}
