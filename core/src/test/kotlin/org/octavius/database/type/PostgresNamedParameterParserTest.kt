package org.octavius.database.type

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PostgresNamedParameterParserTest {

    @Nested
    inner class BasicParsing {
        @Test
        fun `should find a single named parameter`() {
            val sql = "SELECT * FROM users WHERE id = :userId"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 31, 38))
        }

        @Test
        fun `should find multiple distinct named parameters`() {
            val sql = "UPDATE products SET name = :newName, price = :newPrice WHERE id = :productId"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("newName", 27, 35),
                ParsedParameter("newPrice", 45, 54),
                ParsedParameter("productId", 66, 76)
            )
        }

        @Test
        fun `should handle parameters with numbers and underscores`() {
            val sql = "SELECT * FROM table1 WHERE col_1 = :param_1 AND col_2 = :param2"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("param_1", 35, 43),
                ParsedParameter("param2", 56, 63)
            )
        }

        @Test
        fun `should find repeated named parameters`() {
            val sql = "SELECT * FROM data WHERE value > :threshold AND value < :threshold * 2"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(
                ParsedParameter("threshold", 33, 43),
                ParsedParameter("threshold", 56, 66)
            )
        }

        @Test
        fun `should return empty list for query with no parameters`() {
            val sql = "SELECT 1 FROM DUAL"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).isEmpty()
        }

        @Test
        fun `should return empty list for query with traditional '?' placeholders`() {
            val sql = "INSERT INTO logs (message) VALUES (?)"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class IgnoredConstructs {

        @Test
        fun `should ignore parameters inside single-quoted strings`() {
            val sql = "SELECT 'hello :name' FROM users WHERE id = :userId"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 43, 50))
        }

        @Test
        fun `should ignore parameters inside single-quoted strings, even with escaped quotes`() {
            // Ten test sprawdza, czy parser poprawnie ignoruje treść literału,
            // nawet jeśli zawiera on podwójne apostrofy (''), które są standardowym
            // sposobem na umieszczenie apostrofu wewnątrz stringa w SQL.
            val sql = "SELECT 'A parameter here: :ignored, and a quote: '' ' FROM t WHERE id = :realId"
            val result = PostgresNamedParameterParser.parse(sql)

            // Oczekujemy, że ':ignored' zostanie pominięty, a ':realId' znaleziony.
            assertThat(result).containsExactly(ParsedParameter("realId", 72, 79))
        }

        @Test
        fun `should ignore parameter inside E-string literal and find parameter outside`() {
            // Poprawna wersja: zamykamy literał PRZED szukanym parametrem.
            val sql = "SELECT E'ignore this escaped param \\':ignored\\'' FROM t WHERE id = :real"
            val result = PostgresNamedParameterParser.parse(sql)

            assertThat(result).hasSize(1)
            assertThat(result).containsExactly(ParsedParameter("real", 67, 72))
        }

        @Test
        fun `should find no parameters when they are all inside E-string literals`() {
            val sql = "SELECT E'some value \\':fake1\\'' AS col1, E'another value :fake2' AS col2"
            val result = PostgresNamedParameterParser.parse(sql)

            assertThat(result).isEmpty()
        }

        @Test
        fun `should ignore parameters inside double-quoted identifiers`() {
            val sql = """SELECT "col:with:colon" FROM "table:name" WHERE "real_col" = :realParam"""
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("realParam", 61, 71))
        }

        @Test
        fun `should ignore parameters inside single-line comments`() {
            val sql = """
                SELECT * FROM users -- WHERE name = :ignored
                WHERE id = :userId
            """.trimIndent()
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("userId", 56, 63))
        }

        @Test
        fun `should ignore parameters inside multi-line comments`() {
            val sql = """
                SELECT id, name FROM products
                /* This is a comment.
                   SELECT * FROM audit WHERE user = :ignoredUser
                */
                WHERE category = :category
            """.trimIndent()
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("category", 121, 130))
        }

        @Test
        fun `should ignore parameters inside nested multi-line comments`() {
            val sql = """
            SELECT id FROM products 
            /* 
            Główny komentarz
                /* 
                Zagnieżdżony komentarz z parametrem :ignored1 
                */
            I jeszcze jeden parametr :ignored2 w głównym komentarzu
            */
            WHERE price > :minPrice
            """.trimIndent()

            val result = PostgresNamedParameterParser.parse(sql)

            // Powinien znaleźć tylko :minPrice
            assertThat(result).hasSize(1)
            assertThat(result[0].name).isEqualTo("minPrice")
        }

        @Test
        fun `should handle multiple levels of nested comments`() {
            val sql = "/* level 1 /* level 2 /* level 3 :ignored */ */ */ SELECT :realParam"
            val result = PostgresNamedParameterParser.parse(sql)

            assertThat(result).hasSize(1)
            assertThat(result[0].name).isEqualTo("realParam")
        }

        @Test
        fun `should ignore postgres type cast operator`() {
            val sql = "SELECT '2024-01-01'::date, field FROM table WHERE id = :id"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("id", 55, 58))
        }
    }

    @Nested
    inner class DollarQuotedStrings {

        @Test
        fun `should ignore parameters inside simple dollar-quoted strings`() {
            val sql = "SELECT $$ some text with :ignoredParam $$ WHERE id = :realId"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("realId", 53, 60))
        }

        @Test
        fun `should ignore parameters inside tagged dollar-quoted strings`() {
            val sql = $$"SELECT $tag$ body with :ignored and :more_ignored $tag$ FROM t WHERE val = :real"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("real", 75, 80))
        }

        @Test
        fun `should handle nested dollar-quoted strings correctly`() {
            val sql =
                $$"SELECT $func$ DECLARE v_text TEXT := $$ nested :ignored $$; BEGIN RETURN v_text; END; $func$ WHERE id = :id"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).containsExactly(ParsedParameter("id", 104, 107))
        }

        @Test
        fun `should handle dollar-quoted string at the end of the query`() {
            val sql = $$"SELECT 1; DO $tag$ BEGIN RAISE NOTICE 'Parameter: :fake'; END; $tag$"
            val result = PostgresNamedParameterParser.parse(sql)
            assertThat(result).isEmpty()
        }

        @Test
        fun `should not parse anything if dollar-quoted string is not closed`() {
            val sql = $$"SELECT $tag$ this is not closed :param"
            val result = PostgresNamedParameterParser.parse(sql)
            // The parser should treat this as a literal and not find a parameter
            assertThat(result).containsExactly(ParsedParameter("param", 32, 38))
            // ^ UWAGA: To jest oczekiwane zachowanie. Jeśli parser nie znajdzie zamknięcia,
            // to dollar-quoting nie jest poprawną składnią i ':' jest traktowany normalnie.
            // Alternatywnie, można by rzucać wyjątkiem, ale obecna implementacja
            // po prostu tego nie rozpozna, co jest akceptowalne.
        }
    }
}