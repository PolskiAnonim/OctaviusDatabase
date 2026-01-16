package org.octavius.database.type.dynamic

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.execute
import org.octavius.data.builder.toField
import org.octavius.data.exception.QueryExecutionException
import org.octavius.data.exception.TypeRegistryException
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.domain.test.dynamic.DynamicProfile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicDtoSerializationTest {

    private lateinit var dataSource: DataSource
    private lateinit var baseConfig: DatabaseConfig

    // Dwa osobne obiekty DataAccess - jeden z włączoną, drugi z wyłączoną funkcją
    private lateinit var dataAccessWithFeature: DataAccess
    private lateinit var dataAccessWithoutFeature: DataAccess

    @BeforeAll
    fun setup() {
        // --- Krok 1: Konfiguracja i zabezpieczenia ---
        baseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = baseConfig.dbUrl
        if (!connectionUrl.contains("octavius_test")) {
            throw IllegalStateException("ABORTING TEST! Attempting to run on a non-test database.")
        }
        println("Safety guard passed for Dynamic DTO Serialization tests.")

        // --- Krok 2: Inicjalizacja bazy ---
        val hikariDataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = baseConfig.dbUrl
            username = baseConfig.dbUsername
            password = baseConfig.dbPassword
        })
        this.dataSource = hikariDataSource
        val jdbcTemplate = NamedParameterJdbcTemplate(hikariDataSource)

        // Używamy nowego skryptu
        jdbcTemplate.jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.jdbcTemplate.execute("CREATE SCHEMA public;")
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-dynamic-serialization-test-db.sql")!!.toURI())))
        jdbcTemplate.jdbcTemplate.execute(initSql)
        println("Dynamic DTO serialization test DB schema initialized.")

        // --- Krok 3: Stworzenie dwóch instancji DAL-a z różnymi konfiguracjami ---
        // Instancja z WŁĄCZONĄ diaboliczną funkcją
        dataAccessWithFeature = OctaviusDatabase.fromDataSource(
            dataSource,
            listOf("org.octavius.domain.test.dynamic"),
            baseConfig.dbSchemas,
            DynamicDtoSerializationStrategy.PREFER_DYNAMIC_DTO,
            disableFlyway = true
        )

        // Instancja z WYŁĄCZONĄ diaboliczną funkcją
        dataAccessWithoutFeature = OctaviusDatabase.fromDataSource(
            dataSource,
            baseConfig.packagesToScan.filter { it != "org.octavius.domain.test.existing" && it != "org.octavius.performance" },
            baseConfig.dbSchemas,
            DynamicDtoSerializationStrategy.EXPLICIT_ONLY,
            disableFlyway = true
        )
    }

    @BeforeEach
    fun cleanup() {
        // Czyścimy tabelę przed każdym testem, żeby zapewnić izolację
        dataAccessWithFeature.rawQuery("TRUNCATE TABLE dynamic_storage RESTART IDENTITY").execute()
    }

    @Test
    fun `should correctly serialize and deserialize DynamicDto when feature is ENABLED`() {
        // --- ARRANGE ---
        // Nasz oryginalny obiekt, który chcemy zapisać
        val originalProfile = DynamicProfile(
            role = "super_admin",
            permissions = listOf("sudo", "all"),
            lastLogin = "2025-11-07T16:07:00"
        )

        val insertSql = """
            INSERT INTO dynamic_storage (description, dynamic_data) 
            VALUES (:desc, :profile) 
            RETURNING id
        """.trimIndent()

        // --- ACT ---
        // Zapisujemy obiekt do bazy, używając DAL-a z włączoną funkcją.
        // Framework powinien automatycznie przekonwertować `originalProfile` na `dynamic_dto`.
        val result = dataAccessWithFeature.rawQuery(insertSql)
            .toField<Int>(
                "desc" to "Test with feature enabled",
                "profile" to originalProfile
            )

        val newId = assertDoesNotThrow { (result as DataResult.Success).value }
        assertThat(newId).isNotNull()

        // Odczytujemy zapisany wiersz, aby zweryfikować, czy dane są poprawne
        val retrievedData = dataAccessWithFeature.select("dynamic_data")
            .from("dynamic_storage")
            .where("id = :id")
            .toField<DynamicProfile>("id" to newId)
            .let { (it as DataResult.Success).value }

        // --- ASSERT ---
        assertThat(retrievedData).isNotNull
        // Najważniejszy assert: obiekt odczytany z bazy musi być identyczny z oryginałem
        assertThat(retrievedData).isEqualTo(originalProfile)
    }

    @Test
    fun `should FAIL to serialize DynamicDto when feature is DISABLED`() {
        // --- ARRANGE ---
        val profile = DynamicProfile("guest", emptyList(), "")
        val insertSql = "INSERT INTO dynamic_storage (description, dynamic_data) VALUES ('should fail', :profile)"

        // --- ACT & ASSERT ---
        // Używamy DAL-a z WYŁĄCZONĄ funkcją.
        // Próba zapisu obiektu @DynamicallyMappable, który nie jest @PgComposite,
        // powinna rzucić wyjątek, bo framework nie wie, jak go zmapować.
        // To jest OCZEKIWANE zachowanie, które dowodzi, że flaga działa.
        assertThrows<TypeRegistryException> {
            val result = dataAccessWithoutFeature.rawQuery(insertSql)
                .execute("profile" to profile)

            // Jeśli doszło do błędu wewnątrz frameworka, będzie on opakowany w DataResult.Failure
            if (result is DataResult.Failure) {
                val queryExecutionError = result.error
                assertThat(queryExecutionError).isInstanceOf(QueryExecutionException::class.java)
                throw queryExecutionError.cause!! //To powinien być TypeRegistryException
            }
        }
    }
}