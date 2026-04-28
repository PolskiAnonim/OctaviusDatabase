package io.github.octaviusframework.db.core.mapping.dynamic

import io.github.octaviusframework.db.api.DataResult
import io.github.octaviusframework.db.api.builder.toField
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import io.github.octaviusframework.db.domain.test.dynamic.DynamicProfile
import io.github.octaviusframework.db.domain.test.dynamic.UserStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
class PolymorphicArrayRoundTripTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("io.github.octaviusframework.db.domain.test.dynamic")

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE polymorphic_storage
        (
            id          SERIAL PRIMARY KEY,
            description TEXT,
            payload     dynamic_dto[] -- TABLICA OBIEKTÓW POLIMORFICZNYCH
        );
    """.trimIndent()

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
        assertThat(insertResult).isInstanceOf(DataResult.Success::class.java)
        val newId = (insertResult as DataResult.Success).value
        assertThat(newId).isNotNull()

        // --- ACT (READ) ---
        // Odczytujemy tę samą wartość z bazy, używając pobranego ID.
        // Oczekujemy, że system mapowania poprawnie zdeserializuje tablicę `dynamic_dto[]`
        // z powrotem na listę konkretnych, silnie typowanych obiektów.
        val readResult = dataAccess.select("payload")
            .from("polymorphic_storage")
            .where("id = @id")
            .toField<List<Any?>>("id" to newId)

        // --- ASSERT ---
        // Krok 1: Sprawdzamy, czy odczyt się powiódł
        assertThat(readResult).isInstanceOf(DataResult.Success::class.java)
        val retrievedEvents = (readResult as DataResult.Success).value
        assertThat(retrievedEvents).isNotNull

        // Krok 2: OSTATECZNY DOWÓD.
        // Odczytana lista musi być strukturalnie identyczna z oryginalną listą.
        // Ponieważ używamy `data class`, .isEqualTo() wykona głębokie porównanie.
        assertThat(retrievedEvents).isEqualTo(originalEvents)

        // Krok 3: Dodatkowe, jawne asercje dla pełnej jasności
        assertThat(retrievedEvents).hasSize(3)
        assertThat(retrievedEvents[0]).isInstanceOf(DynamicProfile::class.java)
        assertThat(retrievedEvents[1]).isInstanceOf(UserStats::class.java)
        assertThat(retrievedEvents[2]).isInstanceOf(DynamicProfile::class.java)
        assertThat((retrievedEvents[0] as DynamicProfile).role).isEqualTo("editor")
        assertThat((retrievedEvents[1] as UserStats).commentCount).isEqualTo(1337)
    }
}