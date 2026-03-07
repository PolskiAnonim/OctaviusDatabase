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
}

class TypeRegistryException(
    val messageEnum: TypeRegistryExceptionMessage,
    val typeName: String? = null,
    cause: Throwable? = null,
    queryContext: QueryContext? = null
) : CodeExecutionException(
    details = generateDeveloperMessage(messageEnum, typeName),
    queryContext = queryContext,
    cause = cause
) {

    override fun toString(): String {
        val contextStr = queryContext?.toString() ?: ""
        
        return """
$contextStr

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
    }
}