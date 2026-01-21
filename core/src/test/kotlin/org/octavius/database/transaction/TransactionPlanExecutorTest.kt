package org.octavius.database.transaction

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.execute
import org.octavius.data.builder.toColumn
import org.octavius.data.builder.toField
import org.octavius.data.exception.StepDependencyException
import org.octavius.data.exception.TransactionStepExecutionException
import org.octavius.data.transaction.TransactionPlan
import org.octavius.database.DatabaseAccess
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistryLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionPlanExecutorTest {

    private lateinit var dataAccess: DataAccess
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeAll
    fun setup() {
        // --- Krok 1: Bezpieczna konfiguracja i połączenie ---
        val dbConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val dbUrl = dbConfig.dbUrl
        if (!dbUrl.contains("localhost:5432") || !dbUrl.endsWith("octavius_test")) {
            throw IllegalStateException("ABORTING TEST! Attempting to run on a non-test database: $dbUrl")
        }

        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = dbConfig.dbUrl
            username = dbConfig.dbUsername
            password = dbConfig.dbPassword
        })
        jdbcTemplate = JdbcTemplate(dataSource)

        // --- Krok 2: Inicjalizacja schematu bazy danych ---
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-transaction-test-db.sql")!!.toURI())))
        jdbcTemplate.execute(initSql)

        // --- Krok 3: Stworzenie pełnej instancji DataAccess ---
        val typeRegistry = TypeRegistryLoader(jdbcTemplate, listOf(), dbConfig.dbSchemas).load()
        val kotlinToPg = KotlinToPostgresConverter(typeRegistry)
        val txManager = DataSourceTransactionManager(dataSource)
        dataAccess = DatabaseAccess(jdbcTemplate, txManager, typeRegistry, kotlinToPg)
    }

    @BeforeEach
    fun cleanup() {
        // Czyścimy tabele przed każdym testem, aby zapewnić izolację
        jdbcTemplate.update("TRUNCATE TABLE users, profiles, logs RESTART IDENTITY")
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

        val userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long::class.java)
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
        val profileUserId = jdbcTemplate.queryForObject("SELECT user_id FROM profiles WHERE bio = ?", Int::class.java, "A bio for John")
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
            "INSERT INTO logs (message) SELECT 'Log for user ' || u.id FROM UNNEST(:userIds) AS u(id)"
        ).asStep().execute("userIds" to selectIdsHandle.column<Int>())
        plan.add(insertLogsStep)

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        val logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logs", Long::class.java)
        assertThat(logCount).isEqualTo(3)
    }

    @Test
    fun `should roll back all changes if a step fails`() {
        // Arrange: User "Admin" już istnieje w schemacie (UNIQUE constraint)
        jdbcTemplate.update("INSERT INTO users (name) VALUES ('Admin')")

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
        assertThat(failure).isInstanceOf(TransactionStepExecutionException::class.java)
        assertThat((failure as TransactionStepExecutionException).stepIndex).isEqualTo(1) // Błąd w drugim kroku (indeks 1)

        // Kluczowa asercja: Sprawdzamy, czy Krok 1 został wycofany
        val logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logs", Long::class.java)
        assertThat(logCount).isEqualTo(0)
    }

    @Test
    fun `should fail if dependency references a non-existent column`() {
        val plan = TransactionPlan()
        val userHandle = plan.add(
            dataAccess.insertInto("users").value("name").returning("id").asStep().toField(mapOf("name" to "Test"))
        )
        plan.add(
            dataAccess.insertInto("profiles").value("user_id").asStep().execute(mapOf("user_id" to userHandle.field<Any>("non_existent_column")))
        )

        // Act
        val result = dataAccess.executeTransactionPlan(plan)

        // Assert
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error
        assertThat(error).isInstanceOf(TransactionStepExecutionException::class.java)
        assertThat(error.cause).isInstanceOf(StepDependencyException::class.java)
        assertThat(error.cause?.message).contains("COLUMN_NOT_FOUND")
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
        val userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long::class.java)
        val logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logs", Long::class.java)
        val profileCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM profiles",  Long::class.java)

        assertThat(userCount).isEqualTo(1)
        assertThat(logCount).isEqualTo(1)
        assertThat(profileCount).isEqualTo(1)
    }
}