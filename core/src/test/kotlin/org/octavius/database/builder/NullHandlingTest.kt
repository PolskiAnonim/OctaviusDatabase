package org.octavius.database.builder

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.octavius.data.DataResult
import org.octavius.data.builder.toColumn
import org.octavius.data.builder.toField
import org.octavius.data.builder.toFieldStrict
import org.octavius.data.builder.toSingleOf
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.database.RowMappers
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.PositionalQuery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * Tests for null-handling behavior in terminal methods.
 * Verifies that nullability is determined by the KType parameter (reified T):
 * - Non-nullable T → Failure(ConversionException(UNEXPECTED_NULL_VALUE))
 * - Nullable T? → Success(null)
 */
class NullHandlingTest {

    private val mockJdbcTemplate = mockk<JdbcTemplate>()
    private val mockConverter = mockk<KotlinToPostgresConverter>()
    private val mockMappers = mockk<RowMappers>()

    private lateinit var builder: DatabaseSelectQueryBuilder

    @BeforeEach
    fun setup() {
        every { mockConverter.toPositionalQuery(any(), any()) } returns PositionalQuery("SELECT 1", emptyList())
        builder = DatabaseSelectQueryBuilder(mockJdbcTemplate, mockMappers, mockConverter, "1")
        builder.from("dual")
    }

    private fun assertConversionFailure(result: DataResult<*>, expectedMessage: ConversionExceptionMessage) {
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val failure = result as DataResult.Failure
        assertThat(failure.error).isInstanceOf(ConversionException::class.java)
        assertThat((failure.error as ConversionException).messageEnum).isEqualTo(expectedMessage)
    }

    private fun assertUnexpectedNullFailure(result: DataResult<*>) {
        assertConversionFailure(result, ConversionExceptionMessage.UNEXPECTED_NULL_VALUE)
    }

    private fun assertEmptyResultFailure(result: DataResult<*>) {
        assertConversionFailure(result, ConversionExceptionMessage.EMPTY_RESULT)
    }

    private fun assertTooManyRowsFailure(result: DataResult<*>) {
        assertConversionFailure(result, ConversionExceptionMessage.TOO_MANY_ROWS)
    }

    @Nested
    inner class ToFieldNullHandling {

        @Test
        fun `toField with non-nullable type and 0 rows should return Failure with EMPTY_RESULT`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns emptyList<Any?>()

            val result: DataResult<Int> = builder.toField<Int>()

            assertEmptyResultFailure(result)
        }

        @Test
        fun `toField with nullable type and 0 rows should return Success(null)`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns emptyList()

            val result: DataResult<Int?> = builder.toField<Int?>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isNull()
        }

        @Test
        fun `toField with non-nullable type and null value should return Failure with UNEXPECTED_NULL_VALUE`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(null)

            val result: DataResult<Int> = builder.toField<Int>()

            assertUnexpectedNullFailure(result)
        }

        @Test
        fun `toField with nullable type and null value should return Success(null)`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(null)

            val result: DataResult<Int?> = builder.toField<Int?>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isNull()
        }

        @Test
        fun `toField with non-nullable type and non-null result should return Success`() {
            val valueMapper = RowMapper<Any?> { _, _ -> 42 }
            every { mockMappers.SingleValueMapper(any()) } returns valueMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(42)

            val result: DataResult<Int> = builder.toField<Int>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isEqualTo(42)
        }
    }

    @Nested
    inner class ToFieldStrictNullHandling {

        @Test
        fun `toFieldStrict with non-nullable type and 0 rows should return Failure`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns emptyList<Any?>()

            val result: DataResult<Int> = builder.toFieldStrict<Int>()

            assertEmptyResultFailure(result)
        }

        @Test
        fun `toFieldStrict with nullable type and 0 rows should return Failure`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns emptyList<Any?>()

            val result: DataResult<Int?> = builder.toFieldStrict<Int?>()

            assertEmptyResultFailure(result)
        }

        @Test
        fun `toFieldStrict with nullable type and null value should return Success(null)`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(null)

            val result: DataResult<Int?> = builder.toFieldStrict<Int?>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isNull()
        }

        @Test
        fun `toFieldStrict with non-nullable type and null value should return Failure`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(null)

            val result: DataResult<Int> = builder.toFieldStrict<Int>()

            assertUnexpectedNullFailure(result)
        }

        @Test
        fun `toFieldStrict with non-nullable type and non-null result should return Success`() {
            val valueMapper = RowMapper<Any?> { _, _ -> 42 }
            every { mockMappers.SingleValueMapper(any()) } returns valueMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(42)

            val result: DataResult<Int> = builder.toFieldStrict<Int>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isEqualTo(42)
        }
    }

    @Nested
    inner class ToColumnNullHandling {

        @Test
        fun `toColumn with non-nullable type and null element should return Failure`() {
            val throwingMapper = RowMapper<Any?> { _, _ ->
                throw ConversionException(ConversionExceptionMessage.UNEXPECTED_NULL_VALUE, targetType = "kotlin.Int")
            }
            every { mockMappers.SingleValueMapper(any()) } returns throwingMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } answers {
                val mapper = secondArg<RowMapper<Any?>>()
                mapper.mapRow(mockk(), 0) // This will throw
                listOf()
            }

            val result: DataResult<List<Int>> = builder.toColumn<Int>()

            assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        }

        @Test
        fun `toColumn with nullable type and null element should return Success`() {
            val nullMapper = RowMapper<Any?> { _, _ -> null }
            every { mockMappers.SingleValueMapper(any()) } returns nullMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(null)

            val result: DataResult<List<Int?>> = builder.toColumn<Int?>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            val list = (result as DataResult.Success).value
            assertThat(list).containsExactly(null)
        }

        @Test
        fun `toColumn with non-nullable type and all non-null elements should return Success`() {
            val valueMapper = RowMapper<Any?> { _, _ -> 1 }
            every { mockMappers.SingleValueMapper(any()) } returns valueMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(1, 2, 3)

            val result: DataResult<List<Int>> = builder.toColumn<Int>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).hasSize(3)
        }
    }

    @Nested
    inner class ToSingleOfNullHandling {

        @Test
        fun `toSingleOf with non-nullable type and 0 rows should return Failure with EMPTY_RESULT`() {
            every { mockMappers.DataObjectMapper<Any>(any()) } returns RowMapper<Any> { _, _ -> "dummy" }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList<Any>()

            val result: DataResult<String> = builder.toSingleOf<String>()

            assertEmptyResultFailure(result)
        }

        @Test
        fun `toSingleOf with nullable type and 0 rows should return Success(null)`() {
            every { mockMappers.DataObjectMapper<Any>(any()) } returns RowMapper<Any> { _, _ -> "dummy" }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList<Any>()

            val result: DataResult<String?> = builder.toSingleOf<String?>()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isNull()
        }
    }

    @Nested
    inner class ToSingleStrictHandling {

        @Test
        fun `toSingleStrict with 0 rows should return Failure with EMPTY_RESULT`() {
            every { mockMappers.ColumnNameMapper() } returns RowMapper<Map<String, Any?>> { _, _ -> emptyMap() }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Map<String, Any?>>>(), *anyVararg()) } returns emptyList()

            val result: DataResult<Map<String, Any?>> = builder.toSingleStrict()

            assertEmptyResultFailure(result)
        }

        @Test
        fun `toSingleStrict with a row should return Success`() {
            val row = mapOf<String, Any?>("id" to 1, "name" to "Alice")
            every { mockMappers.ColumnNameMapper() } returns RowMapper<Map<String, Any?>> { _, _ -> row }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Map<String, Any?>>>(), *anyVararg()) } returns listOf(row)

            val result: DataResult<Map<String, Any?>> = builder.toSingleStrict()

            assertThat(result).isInstanceOf(DataResult.Success::class.java)
            assertThat((result as DataResult.Success).value).isEqualTo(row)
        }
    }

    @Nested
    inner class TooManyRowsHandling {

        @Test
        fun `toField with multiple rows should return Failure`() {
            val valueMapper = RowMapper<Any?> { _, _ -> 42 }
            every { mockMappers.SingleValueMapper(any()) } returns valueMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(42, 99)

            val result: DataResult<Int> = builder.toField<Int>()

            assertTooManyRowsFailure(result)
        }

        @Test
        fun `toFieldStrict with multiple rows should return Failure`() {
            val valueMapper = RowMapper<Any?> { _, _ -> 42 }
            every { mockMappers.SingleValueMapper(any()) } returns valueMapper
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any?>>(), *anyVararg()) } returns listOf(42, 99)

            val result: DataResult<Int> = builder.toFieldStrict<Int>()

            assertTooManyRowsFailure(result)
        }

        @Test
        fun `toSingle with multiple rows should return Failure`() {
            val row1 = mapOf<String, Any?>("id" to 1)
            val row2 = mapOf<String, Any?>("id" to 2)
            every { mockMappers.ColumnNameMapper() } returns RowMapper<Map<String, Any?>> { _, _ -> row1 }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Map<String, Any?>>>(), *anyVararg()) } returns listOf(row1, row2)

            val result: DataResult<Map<String, Any?>?> = builder.toSingle()

            assertTooManyRowsFailure(result)
        }

        @Test
        fun `toSingleOf with multiple rows should return Failure`() {
            every { mockMappers.DataObjectMapper<Any>(any()) } returns RowMapper<Any> { _, _ -> "value" }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns listOf("value1", "value2")

            val result: DataResult<String> = builder.toSingleOf<String>()

            assertTooManyRowsFailure(result)
        }

        @Test
        fun `toSingleStrict with multiple rows should return Failure`() {
            val row1 = mapOf<String, Any?>("id" to 1)
            val row2 = mapOf<String, Any?>("id" to 2)
            every { mockMappers.ColumnNameMapper() } returns RowMapper<Map<String, Any?>> { _, _ -> row1 }
            every { mockJdbcTemplate.query(any<String>(), any<RowMapper<Map<String, Any?>>>(), *anyVararg()) } returns listOf(row1, row2)

            val result: DataResult<Map<String, Any?>> = builder.toSingleStrict()

            assertTooManyRowsFailure(result)
        }
    }
}
