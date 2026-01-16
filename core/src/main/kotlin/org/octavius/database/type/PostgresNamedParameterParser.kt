package org.octavius.database.type

/** Represents a named parameter found in SQL with its position. */
internal data class ParsedParameter(val name: String, val startIndex: Int, val endIndex: Int)


/**
 * Parses PostgreSQL SQL queries to find named parameters (e.g., `:param`).
 *
 * Implementation is inspired by `org.springframework.jdbc.core.namedparam.NamedParameterUtils`,
 * but has been adapted and extended to correctly handle PostgreSQL-specific constructs.
 *
 * The parser correctly ignores placeholders found inside:
 * - Single-line comments (`-- ...`)
 * - Multi-line comments (`/* ... */`)
 * - String literals (`'...'`)
 * - Escape string literals (`E'...'` or `e'...'`) with backslash escapes
 * - Quoted identifiers (`"..."`)
 * - Dollar-quoted strings (`$$...$$`, `$tag$...$tag$`)
 * - Type casting operators (`::`)
 *
 * **PostgreSQL-Specific Features**:
 * - **Escape Strings**: `E'text with \n newline'` - properly handles backslash escape sequences
 * - **Dollar Quoting**: `$$text$$` or `$tag$text$tag$` - supports custom tags for avoiding quote escaping
 * - **Type Casting**: `column::integer` - distinguishes `::` operator from `:param` syntax
 *
 * This prevents false parameter detection inside string literals, which is critical for
 * complex SQL queries containing PostgreSQL-specific syntax.
 */
internal object PostgresNamedParameterParser {

    private const val PARAMETER_SEPARATORS = "\"':&,;()|=+-*%/\\<>^[]"
    private val separatorIndex = BooleanArray(128).apply {
        PARAMETER_SEPARATORS.forEach { this[it.code] = true }
    }

    private fun isParameterSeparator(c: Char): Boolean {
        return (c.code < 128 && separatorIndex[c.code]) || c.isWhitespace()
    }

    /**
     * Analyzes the given SQL string and returns a list of found parameters in order of occurrence.
     */
    fun parse(sql: String): List<ParsedParameter> {
        val foundParameters = mutableListOf<ParsedParameter>()
        val statement = sql.toCharArray()
        var i = 0

        while (i < statement.size) {
            val currentChar = statement[i]
            val newIndex = when (currentChar) {
                '\'' -> processSingleQuote(statement, i)
                ':' -> processColon(statement, i, sql, foundParameters)
                '"' -> skipUntil(statement, i, '"')
                '-' -> processDash(statement, i)
                '/' -> processSlash(statement, i)
                '$' -> processDollar(statement, i)
                else -> i
            }
            // Update i. If logic didn't change the index, i just increments at the end of loop.
            // If logic returned a new index (e.g., end of string), we continue from there.
            i = newIndex
            i++
        }
        return foundParameters
    }


    /**
     * Handles string literals, including PostgreSQL Escape strings (E'...')
     * Returns the index of the closing quote.
     */
    private fun processSingleQuote(statement: CharArray, index: Int): Int {
        // Check if this is an E'...' escape string literal
        // We look behind to see if the previous char was 'E' or 'e'
        return if (index > 0 && (statement[index - 1] == 'E' || statement[index - 1] == 'e')) {
            skipBackslashEscapedLiteral(statement, index)
        } else {
            // Regular literal ('...' or U&'...')
            skipUntil(statement, index, '\'')
        }
    }

    /**
     * Handles potential named parameters (:param) or type casts (::int).
     * If a parameter is found, it is added to the list.
     * Returns the index of the last character of the processed token.
     */
    private fun processColon(
        statement: CharArray,
        index: Int,
        sql: String,
        foundParameters: MutableList<ParsedParameter>
    ): Int {
        // Check for type casting operator '::'
        if (index + 1 < statement.size && statement[index + 1] == ':') {
            return index + 1 // Skip the second ':'
        }

        // Parse named parameter
        var j = index + 1
        while (j < statement.size && !isParameterSeparator(statement[j])) {
            j++
        }

        if (j - index > 1) { // Found parameter name (longer than 0 characters)
            val paramName = sql.substring(index + 1, j)
            foundParameters.add(ParsedParameter(paramName, index, j))
            return j - 1 // Return index of the last character of the parameter
        }

        return index
    }

    /**
     * Handles single-line comments (-- ...).
     * Returns index of the newline or original index if not a comment.
     */
    private fun processDash(statement: CharArray, index: Int): Int {
        if (index + 1 < statement.size && statement[index + 1] == '-') {
            return skipUntil(statement, index, '\n')
        }
        return index
    }

    /**
     * Handles multi-line comments.
     * Returns index of the closing slash or original index if not a comment.
     */
    private fun processSlash(statement: CharArray, index: Int): Int {
        if (index + 1 < statement.size && statement[index + 1] == '*') {
            return skipComment(statement, index)
        }
        return index
    }

    /**
     * Handles dollar-quoted strings ($tag$ ... $tag$).
     * Returns index of the closing dollar sign or original index if valid tag not found.
     */
    private fun processDollar(statement: CharArray, index: Int): Int {
        val endPos = findDollarQuoteEnd(statement, index)
        return if (endPos != -1) endPos else index
    }

    /** Skips to the end of a dollar-quoted block. Returns the index of the last character. */
    private fun findDollarQuoteEnd(statement: CharArray, start: Int): Int {
        if (start + 1 >= statement.size) return -1

        // 1. Find the opening tag (e.g., $tag$)
        // Tag must follow unquoted identifier rules: [a-zA-Z_][a-zA-Z0-9_]* (no dollar signs)
        var tagEnd = start
        while (tagEnd + 1 < statement.size && statement[tagEnd + 1] != '$') {
            val char = statement[tagEnd + 1]

            // Validate tag character according to PostgreSQL unquoted identifier rules
            if (!isValidTagCharacter(char, isFirstChar = tagEnd == start)) {
                return -1 // Invalid tag character, not a valid dollar-quote
            }

            tagEnd++
        }

        if (tagEnd + 1 >= statement.size || statement[tagEnd + 1] != '$') {
            return -1 // Complete opening tag not found
        }

        val tagLength = (tagEnd + 1) - start + 1

        // 2. Search for the closing tag
        var searchPos = tagEnd + 2
        while (searchPos + tagLength <= statement.size) {
            if (regionMatches(statement, searchPos, statement, start, tagLength)) {
                return searchPos + tagLength - 1
            }
            searchPos++
        }

        return -1 // Closing tag not found
    }

    /**
     * Checks if a character is valid for a dollar-quote tag.
     * Tags follow PostgreSQL unquoted identifier rules:
     * - First character: [a-zA-Z_]
     * - Subsequent characters: [a-zA-Z0-9_]
     * - Dollar signs are NOT allowed in tags
     */
    private fun isValidTagCharacter(char: Char, isFirstChar: Boolean): Boolean {
        return when {
            char.isLetter() || char == '_' -> true
            !isFirstChar && char in '0'..'9' -> true
            else -> false
        }
    }

    private fun skipBackslashEscapedLiteral(statement: CharArray, start: Int): Int {
        var i = start + 1
        while (i < statement.size) {
            if (statement[i] == '\\') {
                // This is an escape character, ignore the next character
                i++
            } else if (statement[i] == '\'') {
                // This is the end of the literal
                return i
            }
            i++
        }
        return i
    }

    /** Skips to the next occurrence of the specified character. Returns its index. */
    private fun skipUntil(statement: CharArray, start: Int, endChar: Char): Int {
        var i = start + 1
        while (i < statement.size) {
            if (statement[i] == endChar) {
                return i
            }
            i++
        }
        return i
    }

    /** Skips comment. */
    private fun skipComment(statement: CharArray, start: Int): Int {
        var i = start + 2 // skip initial /*
        var depth = 1
        while (i < statement.size && depth > 0) {
            if (i + 1 < statement.size) {
                if (statement[i] == '/' && statement[i + 1] == '*') {
                    depth++
                    i++
                } else if (statement[i] == '*' && statement[i + 1] == '/') {
                    depth--
                    i++
                }
            }
            i++
        }
        return i - 1
    }

    /**
     * Checks whether a region in the `source` array matches a region in the `target` array.
     */
    private fun regionMatches(source: CharArray, sourceOffset: Int, target: CharArray, targetOffset: Int, len: Int): Boolean {
        if (sourceOffset < 0 || targetOffset < 0 || sourceOffset + len > source.size || targetOffset + len > target.size) {
            return false
        }
        for (i in 0 until len) {
            if (source[sourceOffset + i] != target[targetOffset + i]) {
                return false
            }
        }
        return true
    }
}