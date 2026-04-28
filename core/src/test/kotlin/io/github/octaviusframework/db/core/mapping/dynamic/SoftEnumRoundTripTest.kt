package io.github.octaviusframework.db.core.mapping.dynamic

import io.github.octaviusframework.db.api.DataResult
import io.github.octaviusframework.db.api.builder.toField
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import io.github.octaviusframework.db.domain.test.softEnum.FeatureFlag
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
class SoftEnumRoundTripTest: AbstractIntegrationTest() {

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE soft_enum_storage
        (
            id          SERIAL PRIMARY KEY,
            description TEXT,
            flags       dynamic_dto[]
        );
    """.trimIndent()

    override val packagesToScan: List<String> = listOf("io.github.octaviusframework.db.domain.test.softEnum")

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
            .where("id = @id")
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
