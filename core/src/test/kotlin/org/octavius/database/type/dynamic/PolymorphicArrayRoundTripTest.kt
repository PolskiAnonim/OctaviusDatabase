package org.octavius.database.type.dynamic

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.toField
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.domain.test.dynamic.DynamicProfile
import org.octavius.domain.test.dynamic.UserStats
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

/**
 * Ostateczny test frameworka - "Grand Unification Round-Trip Test".
 *
 * Ta klasa testowa weryfikuje najbardziej zaawansowaną funkcjonalność w pełnym cyklu:
 * 1. Stworzenie w kodzie Kotlina listy zawierającej obiekty RÓŻNYCH typów (@DynamicallyMappable).
 * 2. ZAPISANIE tej polimorficznej listy do pojedynczej kolumny w bazie danych (`dynamic_dto[]`).
 * 3. ODCZYTANIE tej wartości z powrotem.
 * 4. Zweryfikowanie, że odczytana lista jest identyczna z oryginalną.
 *
 * Ten test jest ostatecznym dowodem na spójność i potęgę dwukierunkowego,
 * rekurencyjnego systemu mapowania typów.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolymorphicArrayRoundTripTest {

    private lateinit var dataSource: DataSource
    private lateinit var baseConfig: DatabaseConfig
    private lateinit var dataAccess: DataAccess

    @BeforeAll
    fun setup() {
        // --- Konfiguracja i zabezpieczenia ---
        baseConfig = DatabaseConfig.Companion.loadFromFile("test-database.properties")
        val connectionUrl = baseConfig.dbUrl
        if (!connectionUrl.contains("octavius_test")) {
            throw IllegalStateException("ABORTING TEST! Attempting to run on a non-test database.")
        }
        println("Safety guard passed for Polymorphic Array Round-Trip tests.")

        // --- Inicjalizacja bazy ---
        val hikariDataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = baseConfig.dbUrl
            username = baseConfig.dbUsername
            password = baseConfig.dbPassword
        })
        this.dataSource = hikariDataSource

        val jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate.jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.jdbcTemplate.execute("CREATE SCHEMA public;")
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-polymorphic-array-test-db.sql")!!.toURI())))
        jdbcTemplate.jdbcTemplate.execute(initSql)
        println("Polymorphic Array test DB schema initialized successfully.")

        // --- Stworzenie instancji DAL-a z włączoną "diaboliczną" magią zapisu ---
        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource,
            listOf("org.octavius.domain.test.dynamic"),
            baseConfig.dbSchemas,
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
            disableFlyway = true
        )
    }

    @BeforeEach
    fun cleanup() {
        // Czyścimy tabelę przed każdym testem, żeby zapewnić pełną izolację
        dataAccess.rawQuery("TRUNCATE TABLE polymorphic_storage RESTART IDENTITY").execute()
    }

    @Test
    fun `should correctly serialize a polymorphic list to the database and deserialize it back`() {
        // --- ARRANGE ---
        // Tworzymy w kodzie Kotlina listę-matkę, zawierającą obiekty różnych,
        // silnie typowanych klas. To jest nasz "source of truth".
        val originalEvents: List<Any> = listOf(
            DynamicProfile(
                role = "editor",
                permissions = listOf("write", "publish"),
                lastLogin = "2024-01-01T10:00:00"
            ),
            UserStats(
                postCount = 42,
                commentCount = 1337
            ),
            DynamicProfile(
                role = "commenter",
                permissions = listOf("comment"),
                lastLogin = "2024-02-02T20:00:00"
            )
        )

        // --- ACT (WRITE) ---
        // Zapisujemy całą polimorficzną listę jako JEDEN parametr.
        // Oczekujemy, że framework wykryje każdy obiekt @DynamicallyMappable,
        // opakuje go w `dynamic_dto`, a następnie całą listę zserializuje
        // do postgresowej konstrukcji `ARRAY[...]::dynamic_dto[]`.
        val insertResult = dataAccess.insertInto("polymorphic_storage")
            .values(listOf("description", "payload"))
            .returning("id")
            .toField<Int>(mapOf(
                "description" to "Full round-trip test",
                "payload" to originalEvents
            ))

        // Sprawdzamy, czy zapis się powiódł i pobieramy ID nowego wiersza
        Assertions.assertThat(insertResult).isInstanceOf(DataResult.Success::class.java)
        val newId = (insertResult as DataResult.Success).value
        Assertions.assertThat(newId).isNotNull()

        // --- ACT (READ) ---
        // Odczytujemy tę samą wartość z bazy, używając pobranego ID.
        // Oczekujemy, że system mapowania poprawnie zdeserializuje tablicę `dynamic_dto[]`
        // z powrotem na listę konkretnych, silnie typowanych obiektów.
        val readResult = dataAccess.select("payload")
            .from("polymorphic_storage")
            .where("id = :id")
            .toField<List<Any?>>("id" to newId)

        // --- ASSERT ---
        // Krok 1: Sprawdzamy, czy odczyt się powiódł
        Assertions.assertThat(readResult).isInstanceOf(DataResult.Success::class.java)
        val retrievedEvents = (readResult as DataResult.Success).value
        Assertions.assertThat(retrievedEvents).isNotNull

        // Krok 2: OSTATECZNY DOWÓD.
        // Odczytana lista musi być strukturalnie identyczna z oryginalną listą.
        // Ponieważ używamy `data class`, .isEqualTo() wykona głębokie porównanie.
        Assertions.assertThat(retrievedEvents).isEqualTo(originalEvents)

        // Krok 3: Dodatkowe, jawne asercje dla pełnej jasności
        Assertions.assertThat(retrievedEvents).hasSize(3)
        Assertions.assertThat(retrievedEvents!![0]).isInstanceOf(DynamicProfile::class.java)
        Assertions.assertThat(retrievedEvents[1]).isInstanceOf(UserStats::class.java)
        Assertions.assertThat(retrievedEvents[2]).isInstanceOf(DynamicProfile::class.java)
        Assertions.assertThat((retrievedEvents[0] as DynamicProfile).role).isEqualTo("editor")
        Assertions.assertThat((retrievedEvents[1] as UserStats).commentCount).isEqualTo(1337)
    }
}