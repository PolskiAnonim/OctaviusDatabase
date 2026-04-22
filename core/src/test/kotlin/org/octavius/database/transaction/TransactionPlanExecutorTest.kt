package org.octavius.database.transaction

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataResult
import org.octavius.data.builder.execute
import org.octavius.data.builder.toColumn
import org.octavius.data.builder.toField
import org.octavius.data.exception.BuilderException
import org.octavius.data.exception.ConstraintViolationException
import org.octavius.data.exception.StepDependencyException
import org.octavius.data.getOrThrow
import org.octavius.data.transaction.TransactionPlan
import org.octavius.database.AbstractIntegrationTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionPlanExecutorTest: AbstractIntegrationTest() {

    override val scriptName: String = "init-transaction-test-db.sql"

    @BeforeEach
    fun cleanup() {
        // Czyścimy tabele przed każdym testem, aby zapewnić izolację
        dataAccess.rawQuery("TRUNCATE TABLE users, profiles, logs RESTART IDENTITY").execute()
    }

    @Test
    fun `should execute a simple plan with two independent steps successfully`() {
        val plan = TransactionPlan()
        val insertUserStep = dataAccess.insertInto("users")
            .value("name")
            .returning("id")
            .asStep()
            .toField<Int>(mapOf("name" to "User A"))
        val insertLogStep = dataAccess.insertInto("logs")
            .value("message")
            .asStep()
            .execute(mapOf("message" to "Log entry"))

        val userHandle = plan.add(insertUserStep)
        val logHandle = plan.add(insertLogStep)

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val successResult = (result as DataResult.Success).value
        assertThat(successResult.get(userHandle)).isEqualTo(1)
        assertThat(successResult.get(logHandle)).isEqualTo(1)

        val userCount = dataAccess.rawQuery("SELECT COUNT(*) FROM users").toField<Long>().getOrThrow()
        assertThat(userCount).isEqualTo(1)
    }

    @Test
    fun `should execute a plan with a field dependency`() {
        val plan = TransactionPlan()
        // Krok 1: Wstaw usera i pobierz jego ID
        val insertUserStep = dataAccess.insertInto("users")
            .value("name")
            .returning("id")
            .asStep()
            .toField<Int>(mapOf("name" to "John Doe"))
        val userHandle = plan.add(insertUserStep)

        // Krok 2: Użyj ID z kroku 1, aby wstawić profil
        val insertProfileStep = dataAccess.insertInto("profiles")
            .value("user_id")
            .value("bio")
            .asStep()
            .execute(mapOf("user_id" to userHandle.field(), "bio" to "A bio for John"))
        plan.add(insertProfileStep)

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val profileUserId = dataAccess.rawQuery("SELECT user_id FROM profiles WHERE bio = @bio").toField<Int>("bio" to "A bio for John").getOrThrow()
        assertThat(profileUserId).isEqualTo(1) // Powinno być ID Johna
    }

    @Test
    fun `should execute a plan with a column dependency`() {
        val plan = TransactionPlan()

        // Krok 1: Wstaw kilku userów
        val userNames = listOf("Alice", "Bob", "Charlie")
        userNames.forEach { name ->
            plan.add(dataAccess.insertInto("users").values(mapOf("name" to name)).asStep().execute(mapOf("name" to name)))
        }

        // Krok 2: Pobierz wszystkie ID
        val selectIdsHandle = plan.add(
            dataAccess.select("id").from("users").orderBy("id").asStep().toColumn<Int>()
        )

        // Krok 3: Wstaw logi dla wszystkich pobranych ID
        val insertLogsStep = dataAccess.rawQuery(
            "INSERT INTO logs (message) SELECT 'Log for user ' || u.id FROM UNNEST(@userIds) AS u(id)"
        ).asStep().execute("userIds" to selectIdsHandle.column<Int>())
        plan.add(insertLogsStep)

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val logCount = dataAccess.rawQuery("SELECT COUNT(*) FROM logs").toField<Long>().getOrThrow()
        assertThat(logCount).isEqualTo(3)
    }

    @Test
    fun `should roll back all changes if a step fails`() {
        // Arrange: User "Admin" już istnieje w schemacie (UNIQUE constraint)
        dataAccess.rawQuery("INSERT INTO users (name) VALUES ('Admin')").execute()

        val plan = TransactionPlan()
        // Krok 1: Wstaw log (powinien się udać)
        plan.add(dataAccess.insertInto("logs").value("message").asStep().execute("message" to "This should be rolled back"))
        // Krok 2: Spróbuj wstawić duplikat usera (to się nie uda)
        plan.add(dataAccess.insertInto("users").value("name").asStep().execute("name" to "Admin"))
        // Krok 3: Ten krok nigdy się nie wykona
        plan.add(dataAccess.insertInto("logs").value("message").asStep().execute("message" to "This will not be executed"))

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val failure = (result as DataResult.Failure).error
        assertThat(failure).isInstanceOf(ConstraintViolationException::class.java)
        assertThat(failure.queryContext!!.transactionStepIndex).isEqualTo(1) // Błąd w drugim kroku (indeks 1)

        // Kluczowa asercja: Sprawdzamy, czy Krok 1 został wycofany
        val logCount = dataAccess.rawQuery("SELECT COUNT(*) FROM logs").toField<Long>().getOrThrow()
        assertThat(logCount).isEqualTo(0)
    }

    @Test
    fun `should fail if dependency references a non-existent column`() {
        val plan = TransactionPlan()
        val userHandle = plan.add(
            dataAccess.insertInto("users").value("name").returning("id").asStep().toField<Any?>(mapOf("name" to "Test"))
        )
        plan.add(
            dataAccess.insertInto("profiles").value("user_id").asStep().execute(mapOf("user_id" to userHandle.field<Any>("non_existent_column")))
        )

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error
        assertThat(error).isInstanceOf(StepDependencyException::class.java)
        assertThat(error.message).contains("COLUMN_NOT_FOUND")
    }

    @Test
    fun `should correctly merge plans using addPlan`() {
        // Plan A: Wstaw usera
        val planA = TransactionPlan()
        val userHandle = planA.add(dataAccess.insertInto("users").value("name").returning("id").asStep().toField<Int>("name" to "User From Plan A"))

        // Plan B: Wstaw log
        val planB = TransactionPlan()
        planB.add(dataAccess.insertInto("logs").value("message").asStep().execute("message" to "Log From Plan B"))

        // Plan C: Użyj ID z planu A
        val planC = TransactionPlan()
        planC.add(dataAccess.insertInto("profiles").value("user_id").value("bio").asStep().execute(mapOf("user_id" to userHandle.field(), "bio" to "Bio From Plan C")))

        // Act: Połącz plany
        val finalPlan = TransactionPlan()
        finalPlan.addPlan(planA)
        finalPlan.addPlan(planB)
        finalPlan.addPlan(planC)

        val result = dataAccess.executeTransactionPlan(finalPlan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val userCount = dataAccess.rawQuery("SELECT COUNT(*) FROM users").toField<Long>().getOrThrow()
        val logCount = dataAccess.rawQuery("SELECT COUNT(*) FROM logs").toField<Long>().getOrThrow()
        val profileCount = dataAccess.rawQuery("SELECT COUNT(*) FROM profiles").toField<Long>().getOrThrow()

        assertThat(userCount).isEqualTo(1)
        assertThat(logCount).isEqualTo(1)
        assertThat(profileCount).isEqualTo(1)
    }

    @Test
    fun `should throw BuilderException during validation if a step is invalid`() {
        val plan = TransactionPlan()

        // DELETE without WHERE should throw BuilderException when toSql() is called
        val invalidStep = dataAccess.deleteFrom("users")
            .asStep()
            .execute()

        plan.add(invalidStep)

        // Act & Assert
        assertThatThrownBy {
            dataAccess.executeTransactionPlan(plan)
        }.isInstanceOf(BuilderException::class.java)
            .hasMessageContaining("Error in transaction step 0")
            .hasMessageContaining("Cannot build a DELETE statement without a WHERE clause")
    }

    @Test
    fun `should throw BuilderException for second step being invalid`() {
        val plan = TransactionPlan()

        // Step 0: Valid
        plan.add(dataAccess.insertInto("users").value("name").asStep().execute("name" to "Valid"))

        // Step 1: Invalid (UPDATE without SET)
        val invalidStep = dataAccess.update("users")
            .where("id = 1")
            .asStep()
            .execute()

        plan.add(invalidStep)

        // Act & Assert
        assertThatThrownBy {
            dataAccess.executeTransactionPlan(plan)
        }.isInstanceOf(BuilderException::class.java)
            .hasMessageContaining("Error in transaction step 1")
            .hasMessageContaining("Cannot build an UPDATE statement without a SET clause")
    }
}