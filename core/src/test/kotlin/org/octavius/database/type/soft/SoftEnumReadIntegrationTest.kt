package org.octavius.database.type.soft

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.annotation.DynamicallyMappable
import org.octavius.data.getOrThrow
import org.octavius.database.DatabaseAccess
import org.octavius.database.RowMappers
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.ResultSetValueExtractor
import org.octavius.database.type.registry.TypeRegistryLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager

// Definicja Soft Enuma w kodzie testowym
@DynamicallyMappable(typeName = "feature_flag")
@Serializable
enum class FeatureFlag {
    @SerialName("dark_theme")
    DarkTheme,
    @SerialName("beta_access")
    BetaAccess,
    @SerialName("legacy_support")
    LegacySupport
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoftEnumReadIntegrationTest {

    private lateinit var dataAccess: DataAccess

    @BeforeAll
    fun setup() {
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException("ABORTING TEST! Safety guard failed. Connection URL: '$connectionUrl'")
        }
        println("Safety guard passed. Connected to: $dbName")

        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        })
        val jdbcTemplate = JdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)

        // Upewniamy się, że typ i funkcja istnieją (idempotentnie)
        jdbcTemplate.execute("""
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dynamic_dto') THEN
                    CREATE TYPE dynamic_dto AS (type_name text, data_payload jsonb);
                END IF;
            
            END $$;
            
           -- Ta nazwa jest zawsze prawdziwa. Tworzy DTO z dowolnej wartości, którą da się rzutować na JSONB.
            CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value ANYELEMENT)
            RETURNS dynamic_dto AS $$
            BEGIN
                RETURN ROW(p_type_name, to_jsonb(p_value))::dynamic_dto;
            END;
            $$ LANGUAGE plpgsql;

        -- Przeciążenie dla TEXT
        CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value TEXT)
            RETURNS dynamic_dto AS $$
        BEGIN
            RETURN ROW(p_type_name, to_jsonb(p_value))::dynamic_dto;
        END;
        $$ LANGUAGE plpgsql;
        """.trimIndent())

        val loader = TypeRegistryLoader(
            jdbcTemplate,
            listOf("org.octavius.database.type.soft"),
            databaseConfig.dbSchemas
        )

        val typeRegistry = runBlocking { loader.load() }

        val kotlinToPostgresConverter = KotlinToPostgresConverter(typeRegistry)
        val extractor = ResultSetValueExtractor(typeRegistry)
        val rowMappers = RowMappers(extractor)

        dataAccess = DatabaseAccess(jdbcTemplate, transactionManager, rowMappers, kotlinToPostgresConverter)
    }

    @Test
    fun `should read soft_enum from database via SELECT function`() {
        // Arrange
        val sql = "SELECT to_dynamic_dto('feature_flag', 'dark_theme') AS flag"

        // Act
        val result = dataAccess.rawQuery(sql)
            .toSingle()
            .getOrThrow()

        // Assert
        assertThat(result).isNotNull
        val flagValue = result!!["flag"]

        assertThat(flagValue).isInstanceOf(FeatureFlag::class.java)
        assertThat(flagValue).isEqualTo(FeatureFlag.DarkTheme)
    }

    @Test
    fun `should read soft_enum list (array) from database`() {
        // Arrange
        // Symulujemy tablicę flag: array[soft_enum(...), soft_enum(...)]
        val sql = """
            SELECT ARRAY[
                to_dynamic_dto('feature_flag', 'beta_access'),
                to_dynamic_dto('feature_flag', 'legacy_support')
            ] AS flags
        """.trimIndent()

        // Act
        val result = dataAccess.rawQuery(sql)
            .toSingle()
            .getOrThrow()

        // Assert
        val flags = result!!["flags"] as List<*>
        assertThat(flags).hasSize(2)
        assertThat(flags[0]).isEqualTo(FeatureFlag.BetaAccess)
        assertThat(flags[1]).isEqualTo(FeatureFlag.LegacySupport)
    }
}