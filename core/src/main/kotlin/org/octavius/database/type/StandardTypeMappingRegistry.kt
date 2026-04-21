package org.octavius.database.type

import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.octavius.data.type.DISTANT_FUTURE
import org.octavius.data.type.DISTANT_PAST
import org.octavius.data.type.PgStandardType
import org.postgresql.util.PGInterval
import org.postgresql.util.PGobject
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.time.*
import kotlin.time.Instant
import java.time.LocalDate as JLocalDate
import java.time.LocalDateTime as JLocalDateTime
import java.time.LocalTime as JLocalTime
import java.time.OffsetDateTime as JOffsetDateTime

internal data class StandardTypeHandler<T : Any>(
    val pgTypeName: String,
    val kotlinClass: KClass<T>,
    val fromResultSet: ((ResultSet, Int) -> T?)?,
    val fromString: (String) -> T,
    val toJdbc: (T) -> Any,
    val toPgString: (T) -> String
)

/**
 * Central registry and single source of truth for mappings of standard PostgreSQL types to Kotlin types.
 *
 * Provides bidirectional conversion logic for:
 * - **Reading**: Result Set -> Kotlin, String (Literal) -> Kotlin
 * - **Writing**: Kotlin -> JDBC Parameter, Kotlin -> String (Text Protocol/Literal)
 */
@OptIn(ExperimentalTime::class)
internal object StandardTypeMappingRegistry {

    private const val PG_INFINITY = "infinity"
    private const val PG_PLUS_INFINITY = "+infinity"
    private const val PG_MINUS_INFINITY = "-infinity"

    private val POSTGRES_TIMETZ_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .optionalEnd()
        .appendPattern("[XXX][XX][X]")
        .toFormatter()

    private val mappings: Map<String, StandardTypeHandler<*>> = buildMappings()
    private val oidToHandler: Map<Int, StandardTypeHandler<*>> = buildOidMappings()
    private val kotlinClassToHandler: Map<KClass<*>, StandardTypeHandler<*>> = mappings.values
        .associateBy { it.kotlinClass }

    private fun buildOidMappings(): Map<Int, StandardTypeHandler<*>> {
        val map = mutableMapOf<Int, StandardTypeHandler<*>>()
        PgStandardType.entries.forEach { pgType ->
            val handler = mappings[pgType.typeName]
            if (handler != null) {
                map[pgType.oid] = handler
            }
        }
        return map
    }

    private fun buildMappings(): Map<String, StandardTypeHandler<*>> {
        val map = mutableMapOf<String, StandardTypeHandler<*>>()

        PgStandardType.entries.forEach { pgType ->
            if (pgType.isArray) return@forEach
            val handler = when (pgType) {
                // Integer types
                PgStandardType.INT2 -> primitive(
                    pgType.typeName,
                    Short::class,
                    ResultSet::getShort,
                    parser = String::toShort
                )

                PgStandardType.INT4 -> primitive(
                    pgType.typeName,
                    Int::class,
                    ResultSet::getInt,
                    parser = String::toInt
                )

                PgStandardType.INT8 -> primitive(
                    pgType.typeName,
                    Long::class,
                    ResultSet::getLong,
                    parser = String::toLong
                )

                // Floating-point types
                PgStandardType.FLOAT4 -> primitive(
                    pgType.typeName,
                    Float::class,
                    ResultSet::getFloat,
                    parser = String::toFloat
                )

                PgStandardType.FLOAT8 -> primitive(
                    pgType.typeName,
                    Double::class,
                    ResultSet::getDouble,
                    parser = String::toDouble
                )

                PgStandardType.NUMERIC -> standard(
                    pgType.typeName,
                    BigDecimal::class,
                    ResultSet::getBigDecimal,
                    parser = String::toBigDecimal
                )

                // Text types (with automatic cleaning)
                PgStandardType.TEXT, PgStandardType.VARCHAR, PgStandardType.BPCHAR -> fromStringOnly(
                    pgType.typeName,
                    String::class) { it }

                // Date and time types
                PgStandardType.DATE -> mapped(
                    pgType.typeName,
                    LocalDate::class,
                    { getObject(it, JLocalDate::class.java) },
                    { it.toKotlinLocalDate() },
                    {
                        parseWithInfinity(
                            it,
                            LocalDate.DISTANT_FUTURE,
                            LocalDate.DISTANT_PAST
                        ) { s -> LocalDate.parse(s) }
                    },
                    toJdbc = { v ->
                        when (v) {
                            LocalDate.DISTANT_FUTURE -> pgObject("date", PG_INFINITY)
                            LocalDate.DISTANT_PAST -> pgObject("date", PG_MINUS_INFINITY)
                            else -> v.toJavaLocalDate()
                        }
                    },
                    toPgString = { v ->
                        when (v) {
                            LocalDate.DISTANT_FUTURE -> PG_INFINITY
                            LocalDate.DISTANT_PAST -> PG_MINUS_INFINITY
                            else -> v.toString()
                        }
                    }
                )

                PgStandardType.TIMESTAMP -> mapped(
                    pgType.typeName,
                    LocalDateTime::class,
                    { getObject(it, JLocalDateTime::class.java) },
                    { it.toKotlinLocalDateTime() },
                    {
                        parseWithInfinity(
                            it,
                            LocalDateTime.DISTANT_FUTURE,
                            LocalDateTime.DISTANT_PAST
                        ) { s -> LocalDateTime.parse(s.replace(' ', 'T')) }
                    },
                    toJdbc = { v ->
                        when (v) {
                            LocalDateTime.DISTANT_FUTURE -> pgObject("timestamp", PG_INFINITY)
                            LocalDateTime.DISTANT_PAST -> pgObject("timestamp", PG_MINUS_INFINITY)
                            else -> v.toJavaLocalDateTime()
                        }
                    },
                    toPgString = { v ->
                        when (v) {
                            LocalDateTime.DISTANT_FUTURE -> PG_INFINITY
                            LocalDateTime.DISTANT_PAST -> PG_MINUS_INFINITY
                            else -> v.toString()
                        }
                    }
                )

                PgStandardType.TIMESTAMPTZ -> mapped(
                    pgType.typeName,
                    Instant::class,
                    { getObject(it, JOffsetDateTime::class.java) },
                    { v ->
                        when (v) {
                            JOffsetDateTime.MAX -> Instant.DISTANT_FUTURE
                            JOffsetDateTime.MIN -> Instant.DISTANT_PAST
                            else -> v.toInstant().toKotlinInstant()
                        }
                    },
                    {
                        parseWithInfinity(
                            it,
                            Instant.DISTANT_FUTURE,
                            Instant.DISTANT_PAST
                        ) { s -> Instant.parse(s.replace(' ', 'T')) }
                    },
                    toJdbc = { v ->
                        when (v) {
                            Instant.DISTANT_FUTURE -> pgObject("timestamptz", PG_INFINITY)
                            Instant.DISTANT_PAST -> pgObject("timestamptz", PG_MINUS_INFINITY)
                            else -> v.toJavaInstant().atOffset(ZoneOffset.UTC)
                        }
                    },
                    toPgString = { v ->
                        when (v) {
                            Instant.DISTANT_FUTURE -> PG_INFINITY
                            Instant.DISTANT_PAST -> PG_MINUS_INFINITY
                            else -> v.toString()
                        }
                    }
                )

                PgStandardType.TIME -> mapped(
                    pgType.typeName,
                    LocalTime::class,
                    { getObject(it, JLocalTime::class.java) },
                    { it.toKotlinLocalTime() },
                    { LocalTime.parse(it) },
                    toJdbc = { it.toJavaLocalTime() },
                    toPgString = { it.toString() }
                )

                PgStandardType.TIMETZ -> standard(
                    pgType.typeName,
                    OffsetTime::class,
                    { getObject(it, OffsetTime::class.java) },
                    parser = { s -> OffsetTime.parse(s, POSTGRES_TIMETZ_FORMATTER) }
                )

                PgStandardType.INTERVAL -> fromStringOnly(
                    pgType.typeName,
                    Duration::class,
                    toJdbc = { v ->
                        pgObject(
                            "interval", when (v) {
                                Duration.INFINITE -> PG_INFINITY
                                -Duration.INFINITE -> PG_MINUS_INFINITY
                                else -> v.toIsoString()
                            }
                        )
                    },
                    toPgString = { v ->
                        when (v) {
                            Duration.INFINITE -> PG_INFINITY
                            -Duration.INFINITE -> PG_MINUS_INFINITY
                            else -> v.toIsoString()
                        }
                    }
                ) {
                    parseWithInfinity(it, Duration.INFINITE, -Duration.INFINITE) { s ->
                        pgIntervalToDuration(PGInterval(s))
                    }
                }

                // JSON types (now correctly using Standard Registry)
                PgStandardType.JSON, PgStandardType.JSONB -> fromStringOnly(
                    pgType.typeName,
                    JsonElement::class,
                    toJdbc = { pgObject(pgType.typeName, it.toString()) },
                    toPgString = { it.toString() }
                ) { Json.parseToJsonElement(it) }

                // Boolean type
                PgStandardType.BOOL -> primitive(
                    pgType.typeName,
                    Boolean::class,
                    ResultSet::getBoolean,
                    toPgString = { if (it) "t" else "f" },
                    parser = { it == "t" })

                // UUID type
                PgStandardType.UUID -> standard(
                    pgType.typeName,
                    UUID::class,
                    { getObject(it) as UUID? },
                    parser = UUID::fromString
                )

                // Binary data (bytea hex encoding)
                PgStandardType.BYTEA -> standard(
                    pgType.typeName,
                    ByteArray::class,
                    ResultSet::getBytes,
                    toPgString = { byteArrayToHexString(it) },
                    parser = {
                        if (it.startsWith("\\x")) hexStringToByteArray(it.substring(2))
                        else throw UnsupportedOperationException("Unsupported bytea format. Only hex format (e.g. '\\xDEADBEEF') is supported.")
                    }
                )

                else -> null
            }
            if (handler != null) map[pgType.typeName] = handler
        }
        return map.toMap()
    }

    /**
     * Resolves the base type name for an array type name (e.g., "_int4" -> "int4", "text[]" -> "text").
     */
    fun resolveBaseTypeName(arrayTypeName: String): String {
        return when {
            arrayTypeName.startsWith("_") -> arrayTypeName.substring(1)
            arrayTypeName.endsWith("[]") -> arrayTypeName.removeSuffix("[]")
            else -> arrayTypeName
        }
    }

    private fun pgObject(type: String, value: String) = PGobject().apply {
        this.type = type
        this.value = value
    }



    private fun <T> parseWithInfinity(s: String, plus: T, minus: T, parser: (String) -> T): T = when (s.lowercase()) {
        PG_INFINITY, PG_PLUS_INFINITY -> plus
        PG_MINUS_INFINITY -> minus
        else -> parser(s)
    }

    private fun pgIntervalToDuration(pgInterval: PGInterval): Duration {
        val totalDays = (pgInterval.years * 365.25) + (pgInterval.months * 30.0) + pgInterval.days
        val totalSeconds =
            (totalDays * 86400.0) + (pgInterval.hours * 3600.0) + (pgInterval.minutes * 60.0) + pgInterval.seconds
        return totalSeconds.toDuration(DurationUnit.SECONDS)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have an even number of characters" }
        return ByteArray(len / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2 + 2).append("\\x")
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4]).append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    fun getHandler(pgTypeName: String): StandardTypeHandler<*>? = mappings[pgTypeName]
    fun getHandlerByOid(oid: Int): StandardTypeHandler<*>? = oidToHandler[oid]
    fun getHandlerByClass(kClass: KClass<*>): StandardTypeHandler<*>? {
        kotlinClassToHandler[kClass]?.let { return it }
        // Json Element - it is superclass
        if (kClass.isSubclassOf(JsonElement::class)) {
            return kotlinClassToHandler[JsonElement::class]
        }
        return null
    }
    fun getAllTypeNames(): Set<String> = mappings.keys

    private inline fun <reified T : Any> primitive(
        pgTypeName: String, kClass: KClass<T>, crossinline getter: ResultSet.(Int) -> T,
        noinline toPgString: ((T) -> String)? = null, noinline parser: (String) -> T
    ) = StandardTypeHandler(
        pgTypeName,
        kClass,
        { rs, i -> val v = rs.getter(i); if (rs.wasNull()) null else v },
        parser,
        { it },
        toPgString ?: { it.toString() })

    private inline fun <reified T : Any> standard(
        pgTypeName: String, kClass: KClass<T>, crossinline getter: ResultSet.(Int) -> T?,
        noinline toPgString: ((T) -> String)? = null, noinline parser: (String) -> T
    ) = StandardTypeHandler(
        pgTypeName,
        kClass,
        { rs, i -> rs.getter(i) },
        parser,
        { it },
        toPgString ?: { it.toString() })

    private inline fun <SRC : Any, reified T : Any> mapped(
        pgTypeName: String,
        kClass: KClass<T>,
        crossinline getter: ResultSet.(Int) -> SRC?,
        crossinline mapper: (SRC) -> T,
        noinline parser: (String) -> T,
        noinline toJdbc: (T) -> Any,
        noinline toPgString: (T) -> String
    ) = StandardTypeHandler(pgTypeName, kClass, { rs, i -> rs.getter(i)?.let(mapper) }, parser, toJdbc, toPgString)

    private inline fun <reified T : Any> fromStringOnly(
        pgTypeName: String, kClass: KClass<T>, noinline toJdbc: (T) -> Any = { it },
        noinline toPgString: (T) -> String = { it.toString() }, noinline parser: (String) -> T
    ) = StandardTypeHandler(pgTypeName, kClass, null, parser, toJdbc, toPgString)
}
