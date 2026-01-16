package org.octavius.data.type

/**
 * Represents standard, built-in PostgreSQL data types.
 * Used for type-safe type specification in the `withPgType` method.
 *
 */
enum class PgStandardType(val typeName: String, val isArray: Boolean = false) {
    // --- Simple types ---
    // Fixed-point types
    INT2("int2"),
    SMALLSERIAL("smallserial"),
    INT4("int4"),
    SERIAL("serial"),
    INT8("int8"),
    BIGSERIAL("bigserial"),
    // Floating-point types
    FLOAT4("float4"),
    FLOAT8("float8"),
    NUMERIC("numeric"),
    // Text types
    TEXT("text"),
    VARCHAR("varchar"),
    CHAR("char"),
    // Date and time
    DATE("date"),
    TIMESTAMP("timestamp"),
    TIMESTAMPTZ("timestamptz"),
    TIME("time"),
    TIMETZ("timetz"),
    INTERVAL("interval"),
    // Json
    JSON("json"),
    JSONB("jsonb"),
    // Other
    BOOL("bool"),
    UUID("uuid"),
    BYTEA("bytea"),

    // --- Array types (generated automatically) ---
    INT2_ARRAY("_int2", true),
    SMALLSERIAL_ARRAY("_smallserial", true),
    INT4_ARRAY("_int4", true),
    SERIAL_ARRAY("_serial", true),
    INT8_ARRAY("_int8", true),
    BIGSERIAL_ARRAY("_bigserial", true),
    FLOAT4_ARRAY("_float4", true),
    FLOAT8_ARRAY("_float8", true),
    NUMERIC_ARRAY("_numeric", true),
    TEXT_ARRAY("_text", true),
    VARCHAR_ARRAY("_varchar", true),
    CHAR_ARRAY("_char", true),
    DATE_ARRAY("_date", true),
    TIMESTAMP_ARRAY("_timestamp", true),
    TIMESTAMPTZ_ARRAY("_timestamptz", true),
    TIME_ARRAY("_time", true),
    TIMETZ_ARRAY("_timetz", true),
    INTERVAL_ARRAY("_interval", true),
    JSON_ARRAY("_json", true),
    JSONB_ARRAY("_jsonb", true),
    BOOL_ARRAY("_bool", true),
    UUID_ARRAY("_uuid", true),
    BYTEA_ARRAY("_bytea", true)
}


