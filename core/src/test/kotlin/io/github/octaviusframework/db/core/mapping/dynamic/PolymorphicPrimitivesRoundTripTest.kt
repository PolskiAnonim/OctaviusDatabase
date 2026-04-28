package io.github.octaviusframework.db.core.mapping.dynamic

import io.github.octaviusframework.db.api.DataResult
import io.github.octaviusframework.db.api.builder.toField
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import io.github.octaviusframework.db.domain.test.polymorphicPrimitives.BooleanWrapper
import io.github.octaviusframework.db.domain.test.polymorphicPrimitives.IntWrapper
import io.github.octaviusframework.db.domain.test.polymorphicPrimitives.StringWrapper
import io.github.octaviusframework.db.domain.test.polymorphicPrimitives.UserAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Test weryfikujący pełny cykl zapisu i odczytu dla polimorficznej listy `List<Any>`,
 * która zawiera zarówno `data class`, jak i `value class` (działające jako wrappery
 * na typy proste).
 *
 * Jest to ostateczny dowód, że mechanizm `dynamic_dto` poprawnie obsługuje
 * heterogeniczne kolekcje, włączając w to "typowe proste" z metadanymi.
 */
class PolymorphicPrimitivesRoundTripTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("io.github.octaviusframework.db.domain.test.polymorphicPrimitives")

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE primitive_payload_storage
        (
            id          SERIAL PRIMARY KEY,
            description TEXT,
            payload     dynamic_dto[]
        );
    """.trimIndent()

    @Test
    fun `should correctly serialize and deserialize a polymorphic list containing value classes`() {
        // --- ARRANGE ---
        // Tworzymy listę-matkę, która zawiera obiekty różnych, silnie typowanych
        // klas - zarówno data class, jak i value class. To jest nasze "źródło prawdy".
        val originalPayload: List<Any> = listOf(
            UserAction("LOGIN", "2025-12-01T12:00:00Z"),
            IntWrapper(12345),
            StringWrapper("xyz-secret-token-a1b2c3"),
            BooleanWrapper(true),
            IntWrapper(98765) // Dodajemy drugi raz ten sam typ, żeby sprawdzić obsługę
        )

        // --- ACT (WRITE) ---
        // Zapisujemy całą polimorficzną listę jako JEDEN parametr.
        // Oczekujemy, że framework rozpozna adnotację @DynamicallyMappable na każdym
        // elemencie i przekonwertuje listę na postgresową tablicę `ARRAY[...]::dynamic_dto[]`.
        val insertResult = dataAccess.insertInto("primitive_payload_storage")
            .values(listOf("description", "payload"))
            .returning("id")
            .toField<Int>(mapOf(
                "description" to "Full round-trip test for value classes in a polymorphic list",
                "payload" to originalPayload
            ))

        // Sprawdzamy, czy zapis się powiódł i pobieramy ID nowego wiersza
        assertThat(insertResult).isInstanceOf(DataResult.Success::class.java)
        val newId = insertResult.getOrThrow()
        assertThat(newId).isNotNull()

        // --- ACT (READ) ---
        // Odczytujemy tę samą wartość z bazy. Oczekujemy, że system mapowania
        // zdeserializuje tablicę `dynamic_dto[]` z powrotem na listę konkretnych obiektów.
        val readResult = dataAccess.select("payload")
            .from("primitive_payload_storage")
            .where("id = @id")
            .toField<List<Any?>>("id" to newId)

        // --- ASSERT ---
        // Krok 1: Sprawdzamy, czy odczyt się powiódł
        assertThat(readResult).isInstanceOf(DataResult.Success::class.java)
        val retrievedPayload = readResult.getOrThrow()
        assertThat(retrievedPayload).isNotNull

        // Krok 2: OSTATECZNY DOWÓD.
        // Odczytana lista musi być strukturalnie identyczna z oryginalną.
        assertThat(retrievedPayload).isEqualTo(originalPayload)

        // Krok 3: Dodatkowe, jawne asercje dla pełnej jasności
        assertThat(retrievedPayload).hasSize(5)
        assertThat(retrievedPayload[0]).isInstanceOf(UserAction::class.java)
        assertThat(retrievedPayload[1]).isInstanceOf(IntWrapper::class.java)
        assertThat(retrievedPayload[2]).isInstanceOf(StringWrapper::class.java)
        assertThat(retrievedPayload[3]).isInstanceOf(BooleanWrapper::class.java)
        assertThat(retrievedPayload[4]).isInstanceOf(IntWrapper::class.java)

        // Sprawdźmy też wartości
        assertThat((retrievedPayload[0] as UserAction).action).isEqualTo("LOGIN")
        assertThat((retrievedPayload[1] as IntWrapper).int).isEqualTo(12345)
        assertThat((retrievedPayload[2] as StringWrapper).string).isEqualTo("xyz-secret-token-a1b2c3")
        assertThat((retrievedPayload[3] as BooleanWrapper).boolean).isTrue()
        assertThat((retrievedPayload[4] as IntWrapper).int).isEqualTo(98765)
    }
}
