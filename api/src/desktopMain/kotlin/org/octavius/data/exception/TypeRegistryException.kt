package org.octavius.data.exception

//---------------------------------------------TypeRegistryException----------------------------------------------------

enum class TypeRegistryExceptionMessage {
    // --- Loading / Infrastructure errors ---
    INITIALIZATION_FAILED,       // General fatal error
    CLASSPATH_SCAN_FAILED,       // ClassGraph issue
    DB_QUERY_FAILED,             // JDBC/SQL issue

    // --- Schema Consistency errors (Startup) ---
    TYPE_DEFINITION_MISSING_IN_DB,     // Code has @PgType, Database is missing CREATE TYPE
    DUPLICATE_PG_TYPE_DEFINITION,      // Conflict between @PgEnum and/or @PgComposite names
    DUPLICATE_DYNAMIC_TYPE_DEFINITION, // Conflict between @DynamicallyMappable names

    // --- Runtime Lookup errors (Operations) ---
    WRONG_FIELD_NUMBER_IN_COMPOSITE, // Registry <-> database mismatch
    PG_TYPE_NOT_FOUND,               // Registry lookup failed (e.g. converting DB value -> Kotlin)
    KOTLIN_CLASS_NOT_MAPPED,         // Registry lookup failed (e.g. Kotlin param -> SQL)
    PG_TYPE_NOT_MAPPED,              // Inverse lookup failed (PG name -> KClass)
    DYNAMIC_TYPE_NOT_FOUND           // Dynamic DTO key lookup failed
}

class TypeRegistryException(
    val messageEnum: TypeRegistryExceptionMessage,
    val typeName: String? = null,
    cause: Throwable? = null
) : DatabaseException(messageEnum.name, cause) {

    override fun toString(): String {
        return """
        -------------------------------
        |     TYPE REGISTRY FAILED     
        | Reason: ${messageEnum.name}
        | Details: ${generateDeveloperMessage(this.messageEnum, typeName)}
        | Related Type: ${typeName ?: "N/A"}
        -------------------------------
        """.trimIndent()
    }
}

private fun generateDeveloperMessage(messageEnum: TypeRegistryExceptionMessage, typeName: String?): String {
    return when (messageEnum) {
        TypeRegistryExceptionMessage.INITIALIZATION_FAILED -> "Critical error: Failed to initialize TypeRegistry."
        TypeRegistryExceptionMessage.CLASSPATH_SCAN_FAILED -> "Failed to scan classpath for annotations."
        TypeRegistryExceptionMessage.DB_QUERY_FAILED -> "Failed to fetch type definitions from database."
        TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB ->
            "Startup validation failed. A Kotlin class is annotated with @PgEnum/@PgComposite(name='$typeName'), but the type '$typeName' does not exist in the database schemas. Please check your SQL migrations."
        TypeRegistryExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION ->
            "Configuration error. The PostgreSQL type name '$typeName' is defined more than once in the codebase (detected duplicate or collision between @PgEnum and @PgComposite). Postgres requires unique type names within a schema."
        TypeRegistryExceptionMessage.DUPLICATE_DYNAMIC_TYPE_DEFINITION ->
            "Configuration error. The Dynamic DTO key '$typeName' is defined more than once. Check your @DynamicallyMappable(typeName=...) annotations."
        TypeRegistryExceptionMessage.WRONG_FIELD_NUMBER_IN_COMPOSITE -> "Schema mismatch. Composite type '$typeName' in the database has a different number of fields than defined in the registry."
        TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND -> "Runtime lookup failed. The PostgreSQL type '$typeName' was not found in the loaded registry."
        TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED -> "Runtime lookup failed. Class '$typeName' is not mapped to any PostgreSQL type. Ensure it has @PgEnum/@PgComposite annotation and is scanned."
        TypeRegistryExceptionMessage.PG_TYPE_NOT_MAPPED -> "Runtime lookup failed. No Kotlin class found mapped to PostgreSQL type '$typeName'."
        TypeRegistryExceptionMessage.DYNAMIC_TYPE_NOT_FOUND -> "Runtime lookup failed. No registered @DynamicallyMappable class found for key '$typeName'."
    }
}