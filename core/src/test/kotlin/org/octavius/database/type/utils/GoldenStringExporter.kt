package org.octavius.database.type.utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.octavius.database.config.DatabaseConfig
import org.postgresql.jdbc.PgResultSetMetaData
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

/**
 * JEDNORAZOWE NARZĘDZIE DO GENEROWANIA "ZŁOTYCH" STRINGÓW TESTOWYCH.
 *
 * Wykonuje jedno zapytanie do bazy, pobiera wszystkie potrzebne wartości do mapy,
 * a następnie generuje z niej gotowy do wklejenia kod.
 *
 */
class GoldenStringExporter {
    @Test
    @Disabled("Użyj tylko do jednorazowego wygenerowania danych testowych!")
    fun exportAllGoldenStrings() {
        // --- 1. Konfiguracja połączenia ---
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        // Guard bezpieczeństwa
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException("ABORTING! Próba uruchomienia na bazie innej niż testowa: $connectionUrl")
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        val dataSource = HikariDataSource(hikariConfig)
        val jdbcTemplate = NamedParameterJdbcTemplate(dataSource)

        // --- 2. Przygotowanie bazy danych ---
        try {
            jdbcTemplate.jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
            jdbcTemplate.jdbcTemplate.execute("CREATE SCHEMA public;")
            val initSqlUrl = this::class.java.classLoader.getResource("init-complex-test-db.sql")!!
            val initSql = String(Files.readAllBytes(Paths.get(initSqlUrl.toURI())))
            jdbcTemplate.jdbcTemplate.execute(initSql)
            println("Baza testowa zainicjalizowana.")
        } catch (e: Exception) {
            e.printStackTrace(); throw e
        }

        // --- 3. Pobranie wszystkich potrzebnych danych w JEDNYM ZAPYTANIU ---
        val columnsToExport = listOf(
            "id",
            "simple_text",
            "simple_number",
            "simple_bool",
            "simple_json",
            "simple_uuid",
            "simple_date",
            "simple_timestamp",
            "simple_timestamptz",
            "simple_numeric",
            "simple_interval",
            "single_status",
            "status_array",
            "user_email",
            "item_count",
            "text_array",
            "number_array",
            "nested_text_array",
            "single_person",
            "person_array",
            "project_data",
            "project_array"
        )

        val selectClause = columnsToExport.joinToString(", ")
        val sql = "SELECT $selectClause FROM complex_test_data WHERE id = 1"

        // Wynik trafia do mapy Map<String, String>
        val goldenStringsMap = jdbcTemplate.queryForObject(sql, emptyMap<String, Any>()) { rs, _ ->
            val data = mutableMapOf<String, String>()
            val metaData = rs.metaData as PgResultSetMetaData
            for (i in 1..metaData.columnCount) {
                val columnName = metaData.getColumnName(i)
                data[columnName] = rs.getString(i)
            }
            data
        }

        // --- 4. Wygenerowanie kodu z mapy w pętli ---
        val stringBuilder = StringBuilder()
        stringBuilder.appendLine("companion object {")
        goldenStringsMap.forEach { (columnName, stringValue) ->
            val constName = "GOLDEN_STRING_${columnName.uppercase()}"

            // Tworzymy string bezpieczny dla standardowego literału Kotlina ("...")
            val escapedForKotlin = stringValue
                .replace("\\", "\\\\") // 1. Najpierw backslashe
                .replace("\"", "\\\"")  // 2. Potem cudzysłowy
                .replace("$", "\\$")    // 3. Na koniec dolary (na wszelki wypadek)
            // =================================================================

            stringBuilder.appendLine("    const val $constName = \"$escapedForKotlin\"")
        }
        stringBuilder.appendLine("}")

        println("\n\n--- SKOPUJ I WKLEJ TEN BLOK DO KLASY TESTOWEJ ---\n")
        println(stringBuilder.toString())
        println("---------------------------------------------------\n\n")
    }
}