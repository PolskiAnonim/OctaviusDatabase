package org.octavius.data.util


/**
 * Simple function cleaning String from typographic apostrophes and quotes
 */
fun String.clean(): String {
    return buildString(this.length) {
        for (c in this@clean) {
            when (c) {
                '‘', '’' -> append('\'')
                '“', '”' -> append('"')
                else    -> append(c)
            }
        }
    }
}
