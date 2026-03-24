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

    // --- Array types ---
    INT2_ARRAY("int2", true, oid = 1005),
    INT4_ARRAY("int4", true, oid = 1007),
    INT8_ARRAY("int8", true, oid = 1016),
    FLOAT4_ARRAY("float4", true, oid = 1021),
    FLOAT8_ARRAY("float8", true, oid = 1022),
    NUMERIC_ARRAY("numeric", true, oid = 1231),
    VARCHAR_ARRAY("varchar", true, oid = 1015),
    BPCHAR_ARRAY("bpchar", true, oid = 1014),
    TEXT_ARRAY("text", true, oid = 1009),
    DATE_ARRAY("date", true, oid = 1182),
    TIMESTAMP_ARRAY("timestamp", true, oid = 1115),
    TIMESTAMPTZ_ARRAY("timestamptz", true, oid = 1185),
    TIME_ARRAY("time", true, oid = 1183),
    TIMETZ_ARRAY("timetz", true, oid = 1270),
    INTERVAL_ARRAY("interval", true, oid = 1187),
    JSON_ARRAY("json", true, oid = 199),
    JSONB_ARRAY("jsonb", true, oid = 3807),
    BOOL_ARRAY("bool", true, oid = 1000),
    UUID_ARRAY("uuid", true, oid = 2951),
    BYTEA_ARRAY("bytea", true, oid = 1001)
}


