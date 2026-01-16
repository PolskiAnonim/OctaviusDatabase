package org.octavius.database.type.soft

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.toField
import org.octavius.data.getOrThrow
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

/**
 * Test weryfikujący pełny cykl zapisu i odczytu ("Round-Trip") dla listy Soft Enumów.
 *
 * Ten test sprawdza kluczową funkcjonalność frameworka:
 * 1. Stworzenie w kodzie Kotlina listy `List<FeatureFlag>`.
 * 2. ZAPISANIE tej listy do pojedynczej kolumny w bazie danych typu `dynamic_dto[]`.
 *    Framework musi automatycznie rozpoznać każdy element jako `@DynamicallyMappable`
 *    i przekonwertować go na odpowiednią strukturę `dynamic_dto`.
 * 3. ODCZYTANIE tej wartości z powrotem z bazy.
 * 4. Zweryfikowanie, że odczytana lista jest w pełni identyczna z oryginalną.
 *
 * Jest to dowód na spójne działanie systemu typów dla kolekcji.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoftEnumRoundTripTest {

    private lateinit var dataSource: DataSource
    private lateinit var baseConfig: DatabaseConfig
    private lateinit var dataAccess: DataAccess

    @BeforeAll
    fun setup() {
        // --- Konfiguracja i zabezpieczenia ---
        baseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = baseConfig.dbUrl
        if (!connectionUrl.contains("octavius_test")) {
            throw IllegalStateException("ABORTING TEST! Attempting to run on a non-test database.")
        }
        println("Safety guard passed for Soft Enum Round-Trip tests.")

        // --- Inicjalizacja bazy ---
        val hikariDataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = baseConfig.dbUrl
            username = baseConfig.dbUsername
            password = baseConfig.dbPassword
        })
        this.dataSource = hikariDataSource

        val jdbcTemplate = hikariDataSource.let { org.springframework.jdbc.core.JdbcTemplate(it) }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.execute("CREATE SCHEMA public;")
        // Załaduj dedykowany schemat dla tego testu
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-soft-enum-roundtrip-test-db.sql")!!.toURI())))
        jdbcTemplate.execute(initSql)
        println("Soft Enum test DB schema initialized successfully.")

        // --- Stworzenie instancji DAL z automatyczną serializacją do dynamic_dto ---
        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource,
            // Skanujemy ten sam pakiet, w którym zdefiniowany jest FeatureFlag
            listOf("org.octavius.database.type.soft"),
            baseConfig.dbSchemas,
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
            disableFlyway = true
        )
    }

    @BeforeEach
    fun cleanup() {
        // Czyścimy tabelę przed każdym testem, żeby zapewnić pełną izolację
        dataAccess.rawQuery("TRUNCATE TABLE soft_enum_storage RESTART IDENTITY").execute()
    }

    @Test
    fun `should correctly serialize a soft enum list to the database and deserialize it back`() {
        // --- ARRANGE ---
        // Tworzymy w kodzie Kotlina listę flag, która jest naszym "źródłem prawdy".
        val originalFlags: List<FeatureFlag> = listOf(
            FeatureFlag.DarkTheme,
            FeatureFlag.BetaAccess,
            FeatureFlag.LegacySupport
        )

        // --- ACT (WRITE) ---
        // Zapisujemy całą listę jako JEDEN parametr do zapytania.
        // Oczekujemy, że framework automatycznie przekonwertuje `List<FeatureFlag>`
        // na postgresową tablicę `ARRAY[...]::dynamic_dto[]`.
        val insertResult = dataAccess.insertInto("soft_enum_storage")
            .values(listOf("description", "flags"))
            .returning("id")
            .toField<Int>(mapOf(
                "description" to "Full round-trip test for soft enums",
                "flags" to originalFlags
            ))

        // Sprawdzamy, czy zapis się powiódł i pobieramy ID nowego wiersza
        assertThat(insertResult).isInstanceOf(DataResult.Success::class.java)
        val newId = insertResult.getOrThrow()
        assertThat(newId).isNotNull()

        // --- ACT (READ) ---
        // Odczytujemy tę samą wartość z bazy, używając pobranego ID.
        // Oczekujemy, że system mapowania poprawnie zdeserializuje tablicę `dynamic_dto[]`
        // z powrotem na listę `List<FeatureFlag>`.
        val readResult = dataAccess.select("flags")
            .from("soft_enum_storage")
            .where("id = :id")
            .toField<List<FeatureFlag>>("id" to newId)

        // --- ASSERT ---
        // Krok 1: Sprawdzamy, czy odczyt się powiódł
        assertThat(readResult).isInstanceOf(DataResult.Success::class.java)
        val retrievedFlags = readResult.getOrThrow()
        assertThat(retrievedFlags).isNotNull

        // Krok 2: OSTATECZNY DOWÓD.
        // Odczytana lista musi być identyczna z oryginalną listą.
        assertThat(retrievedFlags).isEqualTo(originalFlags)

        // Krok 3: Dodatkowe, jawne asercje dla pełnej jasności
        assertThat(retrievedFlags).hasSize(3)
        assertThat(retrievedFlags).containsExactly(
            FeatureFlag.DarkTheme,
            FeatureFlag.BetaAccess,
            FeatureFlag.LegacySupport
        )
    }
}