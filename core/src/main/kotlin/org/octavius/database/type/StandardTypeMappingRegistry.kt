package org.octavius.database.type

import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.octavius.data.type.DISTANT_FUTURE
import org.octavius.data.type.DISTANT_PAST
import org.octavius.data.type.PgStandardType
import org.postgresql.util.PGInterval
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import java.time.LocalDate as JLocalDate
import java.time.LocalDateTime as JLocalDateTime
import java.time.LocalTime as JLocalTime
import java.time.OffsetTime
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import java.time.OffsetDateTime as JOffsetDateTime

internal data class StandardTypeHandler(
    val kotlinClass: KClass<*>,
    val fromResultSet: ((ResultSet, Int) -> Any?)?,
    val fromString: (String) -> Any
)

/**
 * Central registry and single source of truth for mappings of standard PostgreSQL types to Kotlin types.
 *
 * Replaces scattered `when` blocks in `PostgresToKotlinConverter` and `ResultSetValueExtractor`,
 * ensuring consistency and ease of extension.
 */
@OptIn(ExperimentalTime::class)
internal object StandardTypeMappingRegistry {

    private val POSTGRES_TIMETZ_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalEnd()
        .appendPattern("X")
        .toFormatter()

    private const val PG_INFINITY = "infinity"
    private const val PG_PLUS_INFINITY = "+infinity"
    private const val PG_MINUS_INFINITY = "-infinity"

    private val mappings: Map<String, StandardTypeHandler> = buildMappings()

    private fun buildMappings(): Map<String, StandardTypeHandler> {
        val map = mutableMapOf<String, StandardTypeHandler>()

        PgStandardType.entries.forEach { pgType ->
            if (pgType.isArray) return@forEach
            val handler = when (pgType) {
                // Integer numeric types
                PgStandardType.INT2, PgStandardType.SMALLSERIAL -> primitive(Short::class, ResultSet::getShort, String::toShort)
                PgStandardType.INT4, PgStandardType.SERIAL -> primitive(Int::class, ResultSet::getInt, String::toInt)
                PgStandardType.INT8, PgStandardType.BIGSERIAL -> primitive(Long::class, ResultSet::getLong, String::toLong)
                // Floating-point types
                PgStandardType.FLOAT4 -> primitive(Float::class, ResultSet::getFloat, String::toFloat)
                PgStandardType.FLOAT8 -> primitive(Double::class, ResultSet::getDouble, String::toDouble)

                PgStandardType.NUMERIC -> standard(BigDecimal::class, ResultSet::getBigDecimal, String::toBigDecimal)
                // Text types
                PgStandardType.TEXT, PgStandardType.VARCHAR, PgStandardType.CHAR -> fromStringOnly(String::class) { it }
                // Date and time
                PgStandardType.DATE -> mapped(
                    LocalDate::class,
                    { getObject(it, JLocalDate::class.java) },
                    { it.toKotlinLocalDate() },
                    { parseDateWithInfinity(it) }
                )

                PgStandardType.TIMESTAMP -> mapped(
                    LocalDateTime::class,
                    { getObject(it, JLocalDateTime::class.java) },
                    { it.toKotlinLocalDateTime() },
                    { parseDateTimeWithInfinity(it) }
                )

                PgStandardType.TIMESTAMPTZ -> mapped(
                    Instant::class,
                    { getObject(it, JOffsetDateTime::class.java) },
                    { it.toInstant().toKotlinInstant() }, // Java OffsetDateTime -> Java Instant -> Kotlin Instant
                    { parseInstantWithInfinity(it) }
                )

                PgStandardType.TIME -> mapped(
                    LocalTime::class,
                    { getObject(it, JLocalTime::class.java) },
                    { it.toKotlinLocalTime() },
                    { LocalTime.parse(it) }
                )

                PgStandardType.TIMETZ -> standard(
                    OffsetTime::class,
                    { getObject(it, OffsetTime::class.java) },
                    { s -> OffsetTime.parse(s, POSTGRES_TIMETZ_FORMATTER) }
                )

                PgStandardType.INTERVAL -> fromStringOnly(
                    Duration::class) {
                    parsePostgresIntervalString(it)
                }

                // Json
                PgStandardType.JSON, PgStandardType.JSONB -> fromStringOnly(JsonElement::class) { Json.parseToJsonElement(it) }
                // Other
                PgStandardType.BOOL -> primitive(Boolean::class, ResultSet::getBoolean) { it == "t" }

                PgStandardType.UUID -> standard(UUID::class, { getObject(it) as UUID? }, UUID::fromString)

                PgStandardType.BYTEA -> standard(ByteArray::class, ResultSet::getBytes) {
                    if (it.startsWith("\\x")) {
                        hexStringToByteArray(it.substring(2))
                    } else {
                        throw UnsupportedOperationException("Unsupported bytea format. Only hex format (e.g. '\\xDEADBEEF') is supported.")
                    }
                }
                else -> null
            }
            if (handler != null) {
                map[pgType.typeName] = handler
            }
        }
        return map.toMap()
    }

    // ----------------------- DATETIME FUNCTIONS -----------------------------------

    /**
     * Parses a PostgreSQL DATE string with support for infinity values.
     *
     * PostgreSQL's DATE type supports special values 'infinity' and '-infinity' to represent
     * unbounded dates. These map to [LocalDate.DISTANT_FUTURE] and [LocalDate.DISTANT_PAST].
     *
     * @param s The string representation from PostgreSQL (e.g., "2024-01-26", "infinity", "-infinity")
     * @return A LocalDate value, or DISTANT_FUTURE/DISTANT_PAST for infinity values
     */
    private fun parseDateWithInfinity(s: String): LocalDate {
        return when (s.lowercase()) {
            PG_INFINITY, PG_PLUS_INFINITY -> LocalDate.DISTANT_FUTURE
            PG_MINUS_INFINITY -> LocalDate.DISTANT_PAST
            else -> LocalDate.parse(s)
        }
    }

    /**
     * Parses a PostgreSQL TIMESTAMP string with support for infinity values.
     *
     * PostgreSQL's TIMESTAMP type supports special values 'infinity' and '-infinity' to represent
     * unbounded timestamps. These map to [LocalDateTime.DISTANT_FUTURE] and [LocalDateTime.DISTANT_PAST].
     *
     * @param s The string representation from PostgreSQL (e.g., "2024-01-26 15:30:00", "infinity")
     * @return A LocalDateTime value, or DISTANT_FUTURE/DISTANT_PAST for infinity values
     */
    private fun parseDateTimeWithInfinity(s: String): LocalDateTime {
        val normalized = s.replace(' ', 'T')
        return when (normalized.lowercase()) {
            PG_INFINITY, PG_PLUS_INFINITY -> LocalDateTime.DISTANT_FUTURE
            PG_MINUS_INFINITY -> LocalDateTime.DISTANT_PAST
            else -> LocalDateTime.parse(normalized)
        }
    }

    /**
     * Parses a PostgreSQL TIMESTAMPTZ string with support for infinity values.
     *
     * PostgreSQL's TIMESTAMPTZ type supports special values 'infinity' and '-infinity' to represent
     * unbounded timestamps with timezone. These map to [Instant.DISTANT_FUTURE] and [Instant.DISTANT_PAST].
     *
     * @param s The string representation from PostgreSQL (e.g., "2024-01-26 15:30:00+00", "infinity")
     * @return An Instant value, or DISTANT_FUTURE/DISTANT_PAST for infinity values
     */
    private fun parseInstantWithInfinity(s: String): Instant {
        val normalized = s.replace(' ', 'T')
        return when (normalized.lowercase()) {
            PG_INFINITY, PG_PLUS_INFINITY -> Instant.DISTANT_FUTURE
            PG_MINUS_INFINITY -> Instant.DISTANT_PAST
            else -> Instant.parse(normalized)
        }
    }

    /**
     * Parses a PostgreSQL INTERVAL string with support for infinity values.
     *
     * PostgreSQL's INTERVAL type supports special values 'infinity' and '-infinity' to represent
     * unbounded durations. These map to [Duration.INFINITE] and [-Duration.INFINITE][Duration.INFINITE].
     *
     * For finite intervals, delegates to [pgIntervalToDuration] which applies PostgreSQL's
     * conversion rules for date-based units.
     *
     * @param s The string representation from PostgreSQL (e.g., "5 days 3 hours", "infinity")
     * @return A Duration value, or INFINITE/-INFINITE for infinity values
     * @see pgIntervalToDuration
     */
    private fun parsePostgresIntervalString(s: String): Duration {
        return when (s.lowercase()) {
            PG_INFINITY, PG_PLUS_INFINITY -> Duration.INFINITE
            PG_MINUS_INFINITY -> -Duration.INFINITE
            else -> pgIntervalToDuration(PGInterval(s))
        }
    }

    /**
     * Converts PostgreSQL INTERVAL to Kotlin Duration using PostgreSQL's conversion rules.
     *
     * PostgreSQL uses fixed conversion rules for INTERVAL values **without a specific date anchor point**.
     * These rules normalize date-based units (years, months, days) into seconds for Duration representation:
     *
     * | Unit | Conversion Rule |
     * |------|----------------|
     * | 1 day | 86,400 seconds |
     * | 1 month | 30 days (= 2,592,000 seconds) |
     * | 1 year | 365.25 days (= 31,557,600 seconds) |
     * | 1 year | 12 months |
     *
     * **Important Notes:**
     * - These are **approximate conversions** used when no specific date context is available
     * - The year uses 365.25 days (accounting for leap years on average)
     * - The month uses exactly 30 days (not calendar month length)
     * - For calendar-accurate date arithmetic, use PostgreSQL's date functions directly in SQL
     *
     * **Example:**
     * ```
     * PostgreSQL: '1 year 2 months 5 days 3:30:15'
     * Converted to:
     *   - Years:   1 * 365.25 * 86400 = 31,557,600 seconds
     *   - Months:  2 * 30 * 86400     =  5,184,000 seconds
     *   - Days:    5 * 86400           =    432,000 seconds
     *   - Hours:   3 * 3600            =     10,800 seconds
     *   - Minutes: 30 * 60             =      1,800 seconds
     *   - Seconds: 15                  =         15 seconds
     *   Total: 37,186,215 seconds (≈ 1.18 years as duration)
     * ```
     *
     * @param pgInterval The PostgreSQL interval value from PGInterval
     * @return A Kotlin Duration representing the total time span in seconds
     * @see PGInterval
     */
    private fun pgIntervalToDuration(pgInterval: PGInterval): Duration {
        val totalDaysFromDate = (pgInterval.years * 365.25) + (pgInterval.months * 30.0) + pgInterval.days

        val totalSeconds = (totalDaysFromDate * 86400.0) +
                (pgInterval.hours * 3600.0) +
                (pgInterval.minutes * 60.0) +
                pgInterval.seconds

        return totalSeconds.toDuration(DurationUnit.SECONDS)
    }

    // ---------------------------------------- Byte array ----------------------------------------------------
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have an even number of characters" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun getHandler(pgTypeName: String): StandardTypeHandler? = mappings[pgTypeName]

    fun getAllTypeNames(): Set<String> = mappings.keys

    // JDBC quirks:
    // 1. For primitive types (int, bool, double).
    // Calls getter, then checks wasNull().
    private inline fun <reified T : Any> primitive(
        kClass: KClass<T>,
        crossinline getter: ResultSet.(Int) -> T,
        noinline parser: (String) -> T
    ) = StandardTypeHandler(
        kotlinClass = kClass,
        fromResultSet = { rs, i ->
            val v = rs.getter(i)
            if (rs.wasNull()) null else v
        },
        fromString = parser
    )
    // 2. For standard types (without conversions returning nulls).
    private inline fun <reified T : Any> standard(
        kClass: KClass<T>,
        crossinline getter: ResultSet.(Int) -> T?,
        noinline parser: (String) -> T
    ) = StandardTypeHandler(
        kotlinClass = kClass,
        fromResultSet = { rs, i -> rs.getter(i) },
        fromString = parser
    )

    // 3. For types requiring conversion (e.g., Timestamp -> Kotlin Instant).
    // Protects against NullPointerException in mapper.
    private inline fun <SRC : Any, reified T : Any> mapped(
        kClass: KClass<T>,
        crossinline getter: ResultSet.(Int) -> SRC?, // JDBC getter
        crossinline mapper: (SRC) -> T,              // Object conversion
        noinline parser: (String) -> T               // String conversion
    ) = StandardTypeHandler(
        kotlinClass = kClass,
        fromResultSet = { rs, i -> rs.getter(i)?.let(mapper) }, // Safe call (?.) handles it
        fromString = parser
    )
    // 4. For types without a faster path than String reading
    private inline fun <reified T : Any> fromStringOnly(
        kClass: KClass<T>,
        noinline parser: (String) -> T
    ) = StandardTypeHandler(
        kotlinClass = kClass,
        fromResultSet = null,
        fromString = parser
    )
}
