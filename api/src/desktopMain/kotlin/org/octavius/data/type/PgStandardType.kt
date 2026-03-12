package org.octavius.data.type

/**
 * Represents standard, built-in PostgreSQL data types.
 * Used for type-safe type specification in the `withPgType` method.
 *
 */
enum class PgStandardType(val typeName: String, val isArray: Boolean = false, val oid: Int) {
    // --- Simple types ---
    // Fixed-point types

    INT2("int2", oid = 21),
    INT4("int4", oid = 23),
    INT8("int8", oid = 20),

    // Floating-point types
    FLOAT4("float4", oid = 700),
    FLOAT8("float8", oid = 701),
    NUMERIC("numeric", oid = 1700),

    // Text types
    VARCHAR("varchar", oid = 1043),
    BPCHAR("bpchar", oid = 1042),
    TEXT("text", oid = 25),

    // Date and time
    DATE("date", oid = 1082),
    TIMESTAMP("timestamp", oid = 1114),
    TIMESTAMPTZ("timestamptz", oid = 1184),
    TIME("time", oid = 1083),
    TIMETZ("timetz", oid = 1266),
    INTERVAL("interval", oid = 1186),

    // Json
    JSON("json", oid = 114),
    JSONB("jsonb", oid = 3802),

    // Other
    BOOL("bool", oid = 16),
    UUID("uuid", oid = 2950),
    BYTEA("bytea", oid = 17),

    // --- Array types (generated automatically) ---
    INT2_ARRAY("_int2", true, oid = 1005),
    INT4_ARRAY("_int4", true, oid = 1007),
    INT8_ARRAY("_int8", true, oid = 1016),
    FLOAT4_ARRAY("_float4", true, oid = 1021),
    FLOAT8_ARRAY("_float8", true, oid = 1022),
    NUMERIC_ARRAY("_numeric", true, oid = 1231),
    VARCHAR_ARRAY("_varchar", true, oid = 1015),
    BPCHAR_ARRAY("_bpchar", true, oid = 1014),
    TEXT_ARRAY("_text", true, oid = 1009),
    DATE_ARRAY("_date", true, oid = 1182),
    TIMESTAMP_ARRAY("_timestamp", true, oid = 1115),
    TIMESTAMPTZ_ARRAY("_timestamptz", true, oid = 1185),
    TIME_ARRAY("_time", true, oid = 1183),
    TIMETZ_ARRAY("_timetz", true, oid = 1270),
    INTERVAL_ARRAY("_interval", true, oid = 1187),
    JSON_ARRAY("_json", true, oid = 199),
    JSONB_ARRAY("_jsonb", true, oid = 3807),
    BOOL_ARRAY("_bool", true, oid = 1000),
    UUID_ARRAY("_uuid", true, oid = 2951),
    BYTEA_ARRAY("_bytea", true, oid = 1001)
}


