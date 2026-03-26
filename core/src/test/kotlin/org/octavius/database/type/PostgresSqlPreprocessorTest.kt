package org.octavius.database.type

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PostgresSqlPreprocessorTest {

    @Nested
    inner class BasicParsing {
        @Test
        fun `should find a single named parameter`() {
            val sql = "SELECT * FROM users WHERE id = @userId"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 31, 38))
        }

        @Test
        fun `should find multiple distinct named parameters`() {
            val sql = "UPDATE products SET name = @newName, price = @newPrice WHERE id = @productId"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("newName", 27, 35),
                ParsedParameter("newPrice", 45, 54),
                ParsedParameter("productId", 66, 76)
            )
        }

        @Test
        fun `should handle parameters with numbers and underscores`() {
            val sql = "SELECT * FROM table1 WHERE col_1 = @param_1 AND col_2 = @param2"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("param_1", 35, 43),
                ParsedParameter("param2", 56, 63)
            )
        }

        @Test
        fun `should find repeated named parameters`() {
            val sql = "SELECT * FROM data WHERE value > @threshold AND value < @threshold * 2"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("threshold", 33, 43),
                ParsedParameter("threshold", 56, 66)
            )
        }

        @Test
        fun `should return empty list for query with no parameters`() {
            val sql = "SELECT 1 FROM DUAL"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).isEmpty()
        }

        @Test
        fun `should return empty list for query with traditional '?' placeholders`() {
            val sql = "INSERT INTO logs (message) VALUES (?)"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class IgnoredConstructs {

        @Test
        fun `should ignore parameters inside single-quoted strings`() {
            val sql = "SELECT 'hello @name' FROM users WHERE id = @userId"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 43, 50))
        }

        @Test
        fun `should ignore parameters inside single-quoted strings, even with escaped quotes`() {
            val sql = "SELECT 'A parameter here: @ignored, and a quote: '' ' FROM t WHERE id = @realId"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("realId", 72, 79))
        }

        @Test
        fun `should ignore parameter inside E-string literal and find parameter outside`() {
            val sql = "SELECT E'ignore this escaped param \\'@ignored\\'' FROM t WHERE id = @real"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).hasSize(1)
            assertThat(result).containsExactly(ParsedParameter("real", 67, 72))
        }

        @Test
        fun `should ignore parameters inside double-quoted identifiers`() {
            val sql = """SELECT "col@with@at" FROM "table@name" WHERE "real_col" = @realParam"""
            val result = PostgresSqlPreprocessor.parse(sql)
            val expectedStart = sql.indexOf("@realParam")
            assertThat(result).containsExactly(ParsedParameter("realParam", expectedStart, expectedStart + 10))
        }

        @Test
        fun `should ignore parameters inside single-line comments`() {
            val sql = """
                SELECT * FROM users -- WHERE name = @ignored
                WHERE id = @userId
            """.trimIndent()
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 56, 63))
        }

        @Test
        fun `should ignore parameters inside multi-line comments`() {
            val sql = """
                SELECT id, name FROM products
                /* This is a comment.
                   SELECT * FROM audit WHERE user = @ignoredUser
                */
                WHERE category = @category
            """.trimIndent()
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("category", 121, 130))
        }

        @Test
        fun `should ignore postgres type cast operator`() {
            val sql = "SELECT '2024-01-01'::date, field FROM table WHERE id = @id"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("id", 55, 58))
        }

        @Test
        fun `should ignore postgres operators starting with at symbol`() {
            // @ absolute value, @@ text search, @> jsonb containment
            val sql = "SELECT @ -5, col @@ to_tsquery('test'), data @> '{\"id\": 1}' FROM t WHERE id = @id"
            val result = PostgresSqlPreprocessor.parse(sql)
            val expectedStart = sql.indexOf("@id")
            assertThat(result).containsExactly(ParsedParameter("id", expectedStart, expectedStart + 3))
        }
    }

    @Nested
    inner class DollarQuotedStrings {

        @Test
        fun `should ignore parameters inside simple dollar-quoted strings`() {
            val sql = "SELECT $$ some text with @ignoredParam $$ WHERE id = @realId"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("realId", 53, 60))
        }

        @Test
        fun `should ignore parameters inside tagged dollar-quoted strings`() {
            val sql = $$"SELECT $tag$ body with @ignored and @more_ignored $tag$ FROM t WHERE val = @real"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("real", 75, 80))
        }
    }

    @Nested
    inner class ArrayAndSliceHandling {

        @Test
        fun `should ignore colon in array slice when using at-prefix`() {
            // This is the main reason for the change - [1:5] should not be touched
            val sql = "SELECT arr[1:5] FROM table WHERE id = @id"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("id", 38, 41))
        }

        @Test
        fun `should find parameter used as array index`() {
            val sql = "SELECT arr[@index] FROM table"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("index", 11, 17))
        }

        @Test
        fun `should find parameters in ARRAY constructor`() {
            val sql = "SELECT ARRAY[@val1, @val2, 3] FROM table"
            val result = PostgresSqlPreprocessor.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("val1", 13, 18),
                ParsedParameter("val2", 20, 25)
            )
        }
    }

    @Nested
    inner class QuestionMarkEscaping {

        @Test
        fun `should escape a single question mark`() {
            val sql = "SELECT * FROM t WHERE json_col ? 'key'"
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("SELECT * FROM t WHERE json_col ?? 'key'")
        }

        @Test
        fun `should escape multiple question marks`() {
            val sql = "SELECT * FROM t WHERE json_col ?| array['a', 'b'] AND other ?& array['c']"
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("SELECT * FROM t WHERE json_col ??| array['a', 'b'] AND other ??& array['c']")
        }

        @Test
        fun `should NOT escape question marks inside single-quoted strings`() {
            val sql = "SELECT 'is this a question?' as q FROM t WHERE col ? 'key'"
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("SELECT 'is this a question?' as q FROM t WHERE col ?? 'key'")
        }

        @Test
        fun `should NOT escape question marks inside E-string literals`() {
            val sql = "SELECT E'escaped \\' quote and question?' FROM t WHERE col ? 'key'"
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("SELECT E'escaped \\' quote and question?' FROM t WHERE col ?? 'key'")
        }

        @Test
        fun `should NOT escape question marks inside dollar-quoted strings`() {
            val sql = "SELECT $$ why? $$ FROM t WHERE col ? 'key'"
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("SELECT $$ why? $$ FROM t WHERE col ?? 'key'")
        }

        @Test
        fun `should NOT escape question marks inside comments`() {
            val sql = """
                SELECT * FROM t -- is this ok?
                /* or this? */
                WHERE col ? 'key'
            """.trimIndent()
            val result = PostgresSqlPreprocessor.escapeQuestionMarks(sql)
            assertThat(result).isEqualTo("""
                SELECT * FROM t -- is this ok?
                /* or this? */
                WHERE col ?? 'key'
            """.trimIndent())
        }
    }
}
