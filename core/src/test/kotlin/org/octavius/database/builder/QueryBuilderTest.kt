package org.octavius.database.builder

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.octavius.database.RowMappers
import org.octavius.database.type.KotlinToPostgresConverter
import org.springframework.jdbc.core.JdbcTemplate


internal object TestQueryBuilderFactory {
    private val mockJdbcTemplate = mockk<JdbcTemplate>()
    private val mockConverter = mockk<KotlinToPostgresConverter>()
    private val mockMappers = mockk<RowMappers>()

    fun select(columns: String) = DatabaseSelectQueryBuilder(mockJdbcTemplate, mockMappers, mockConverter, columns)
    fun insert(table: String) = DatabaseInsertQueryBuilder(mockJdbcTemplate, mockConverter, mockMappers, table)
    fun update(table: String) = DatabaseUpdateQueryBuilder(mockJdbcTemplate, mockConverter, mockMappers, table)
    fun delete(table: String) = DatabaseDeleteQueryBuilder(mockJdbcTemplate, mockConverter, mockMappers, table)
}

class QueryBuilderTest {

    private fun String.normalizeSql() = this.trim().replace(Regex("\\s+"), " ")

    @Nested
    inner class SelectQueryBuilderTests {
        @Test
        fun `should build a simple select query`() {
            val sql = TestQueryBuilderFactory.select("id, name")
                .from("users")
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("SELECT id, name FROM users")
        }

        @Test
        fun `should build a query with all clauses`() {
            val sql = TestQueryBuilderFactory.select("c.name, COUNT(p.id)")
                .with("active_users", "SELECT id FROM users WHERE status = 'active'")
                .from("categories c JOIN products p ON c.id = p.category_id")
                .where("c.is_active = true AND u.id = p.user_id")
                .groupBy("c.name")
                .having("COUNT(p.id) > 5")
                .orderBy("c.name DESC")
                .limit(10)
                .offset(20)
                .toSql()

            val expected = """
                WITH active_users AS (SELECT id FROM users WHERE status = 'active')
                SELECT c.name, COUNT(p.id)
                FROM categories c JOIN products p ON c.id = p.category_id
                WHERE c.is_active = true AND u.id = p.user_id
                GROUP BY c.name
                HAVING COUNT(p.id) > 5
                ORDER BY c.name DESC
                LIMIT 10 OFFSET 20
            """.normalizeSql()
            assertThat(sql.normalizeSql()).isEqualTo(expected)
        }

        @Test
        fun `should build a paginated query`() {
            val sql = TestQueryBuilderFactory.select("*").from("logs").page(2, 50).toSql()
            assertThat(sql.normalizeSql()).isEqualTo("SELECT * FROM logs LIMIT 50 OFFSET 100")
        }
    }

    @Nested
    inner class InsertQueryBuilderTests {
        @Test
        fun `should build a simple insert query with values`() {
            val sql = TestQueryBuilderFactory.insert("users")
                .values(mapOf("name" to "John", "email" to "john@doe.com"))
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("INSERT INTO users (name, email) VALUES (:name, :email)")
        }

        @Test
        fun `should build an insert query with fromSelect`() {
            val selectQuery = "SELECT name, email FROM temp_users"
            val sql = TestQueryBuilderFactory.insert("users")
                .fromSelect(selectQuery)
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("INSERT INTO users $selectQuery")
        }

        @Test
        fun `should build an insert with onConflict do nothing`() {
            val sql = TestQueryBuilderFactory.insert("users")
                .value("email")
                .onConflict {
                    onColumns("email")
                    doNothing()
                }
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("INSERT INTO users (email) VALUES (:email) ON CONFLICT (email) DO NOTHING")
        }

        @Test
        fun `should build an insert with onConflict do update`() {
            val sql = TestQueryBuilderFactory.insert("products")
                .values(mapOf("code" to "A123", "stock" to 10))
                .onConflict {
                    onConstraint("products_code_key")
                    doUpdate("stock = products.stock + EXCLUDED.stock")
                }
                .returning("id", "stock")
                .toSql()
            val expected = """
                INSERT INTO products (code, stock) VALUES (:code, :stock)
                ON CONFLICT ON CONSTRAINT products_code_key DO UPDATE SET stock = products.stock + EXCLUDED.stock
                RETURNING id, stock
            """.normalizeSql()
            assertThat(sql.normalizeSql()).isEqualTo(expected)
        }
    }

    @Nested
    inner class UpdateQueryBuilderTests {
        @Test
        fun `should build a standard update query`() {
            val sql = TestQueryBuilderFactory.update("users")
                .setExpression("last_login", "NOW()")
                .setValue("status")
                .where("id = :id")
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("UPDATE users SET last_login = NOW(), status = :status WHERE id = :id")
        }

        @Test
        fun `should build an update query with FROM and RETURNING`() {
            val sql = TestQueryBuilderFactory.update("employees e")
                .setExpression("salary", "e.salary * 1.1")
                .from("departments d")
                .where("e.department_id = d.id AND d.name = 'IT'")
                .returning("e.id", "e.name", "e.salary")
                .toSql()
            val expected = """
                UPDATE employees e SET salary = e.salary * 1.1
                FROM departments d
                WHERE e.department_id = d.id AND d.name = 'IT'
                RETURNING e.id, e.name, e.salary
            """.normalizeSql()
            assertThat(sql.normalizeSql()).isEqualTo(expected)
        }

        @Test
        fun `should throw exception if WHERE clause is missing`() {
            assertThatThrownBy {
                TestQueryBuilderFactory.update("users").setValue("name").toSql()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("without a WHERE clause")
        }
    }

    @Nested
    inner class DeleteQueryBuilderTests {
        @Test
        fun `should build a simple delete query`() {
            val sql = TestQueryBuilderFactory.delete("logs")
                .where("created_at < NOW() - INTERVAL '30 days'")
                .toSql()
            assertThat(sql.normalizeSql()).isEqualTo("DELETE FROM logs WHERE created_at < NOW() - INTERVAL '30 days'")
        }

        @Test
        fun `should build a delete query with USING and RETURNING`() {
            val sql = TestQueryBuilderFactory.delete("orders")
                .using("customers c")
                .where("orders.customer_id = c.id AND c.is_banned = true")
                .returning("id")
                .toSql()
            val expected = """
                DELETE FROM orders
                USING customers c
                WHERE orders.customer_id = c.id AND c.is_banned = true
                RETURNING id
            """.normalizeSql()
            assertThat(sql.normalizeSql()).isEqualTo(expected)
        }

        @Test
        fun `should throw exception if WHERE clause is missing`() {
            assertThatThrownBy {
                TestQueryBuilderFactory.delete("users").toSql()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("without a WHERE clause")
        }
    }
}