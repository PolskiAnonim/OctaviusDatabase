package org.octavius.database.type

import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.octavius.data.OffsetTime
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

/**
 * Definition of a single mapping for a standard PostgreSQL type.
 *
 * @param kotlinClass Kotlin KClass equivalent.
 * @param fromResultSet Method to extract value directly from ResultSet (fast path).
 * @param fromString Method to parse value from its textual representation (slow path).
 */
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

    // MAIN MAP - SINGLE SOURCE OF TRUTH
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
                    LocalDate::class, ResultSet::getDate,
                    { it.toLocalDate().toKotlinLocalDate() },
                    { LocalDate.parse(it) }
                )

                PgStandardType.TIMESTAMP -> mapped(
                    LocalDateTime::class, ResultSet::getTimestamp,
                    { it.toLocalDateTime().toKotlinLocalDateTime() },
                    { LocalDateTime.parse(it.replace(' ', 'T')) }
                )

                PgStandardType.TIMESTAMPTZ -> mapped(
                    Instant::class, ResultSet::getTimestamp,
                    { it.toInstant().toKotlinInstant() },
                    { Instant.parse(it.replace(' ', 'T')) }
                )

                PgStandardType.TIME -> mapped(
                    LocalTime::class, ResultSet::getTime,
                    { it.toLocalTime().toKotlinLocalTime() },
                    { LocalTime.parse(it) }
                )

                PgStandardType.TIMETZ -> mapped(
                    OffsetTime::class, { getObject(it, java.time.OffsetTime::class.java) },
                    { javaTime ->
                        OffsetTime(
                            time = javaTime.toLocalTime().toKotlinLocalTime(),
                            offset = UtcOffset(seconds = javaTime.offset.totalSeconds)
                        )
                    },
                    { s ->
                        val javaOffsetTime = java.time.OffsetTime.parse(s, POSTGRES_TIMETZ_FORMATTER)
                        OffsetTime(
                            time = javaOffsetTime.toLocalTime().toKotlinLocalTime(),
                            offset = UtcOffset(seconds = javaOffsetTime.offset.totalSeconds)
                        )
                    }
                )

                PgStandardType.INTERVAL -> mapped(
                    Duration::class,
                    { getObject(it) as? PGInterval },
                    ::pgIntervalToDuration,
                    { s ->
                        // Conversion from PGInterval to kotlin.time.Duration
                        pgIntervalToDuration(PGInterval(s))
                    }
                )
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

    private fun pgIntervalToDuration(pgInterval: PGInterval): Duration {
        return (pgInterval.days.toLong() * 24).hours +
                pgInterval.hours.toLong().hours +
                pgInterval.minutes.toLong().minutes +
                pgInterval.seconds.seconds
    }

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
