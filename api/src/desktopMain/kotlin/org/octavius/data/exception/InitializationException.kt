package org.octavius.data.exception

//---------------------------------------------TypeRegistryException----------------------------------------------------

enum class InitializationExceptionMessage {
    // --- Loading / Infrastructure errors ---
    INITIALIZATION_FAILED,       // General fatal error
    CLASSPATH_SCAN_FAILED,       // ClassGraph issue
    DB_QUERY_FAILED,             // JDBC/SQL issue
    MIGRATION_FAILED,            // Flyway issue

    // --- Schema Consistency errors ---
    TYPE_DEFINITION_MISSING_IN_DB,     // Code has @PgType, Database is missing CREATE TYPE
    DUPLICATE_PG_TYPE_DEFINITION,      // Conflict between @PgEnum and/or @PgComposite names
    DUPLICATE_DYNAMIC_TYPE_DEFINITION, // Conflict between @DynamicallyMappable names
}

class InitializationException(
    val messageEnum: InitializationExceptionMessage,
    val details: String? = null,
    cause: Throwable? = null,
    queryContext: QueryContext? = null
) : DatabaseException(
    message = generateDeveloperMessage(messageEnum, details),
    queryContext = queryContext,
    cause = cause
) {

    override fun toString(): String {
        val contextStr = queryContext?.toString() ?: ""
        
        return """
$contextStr

        -------------------------------
        |     INITIALIZATION FAILED     
        | Reason: ${messageEnum.name}
        | Details: ${generateDeveloperMessage(this.messageEnum, details)}
        -------------------------------
        """.trimIndent()
    }
}

private fun generateDeveloperMessage(messageEnum: InitializationExceptionMessage, details: String?): String {
    val suffix = details?.let { ": $it" } ?: ""
    return when (messageEnum) {
        InitializationExceptionMessage.INITIALIZATION_FAILED -> "Critical error: Failed to initialize database system$suffix"
        InitializationExceptionMessage.CLASSPATH_SCAN_FAILED -> "Failed to scan classpath for annotations$suffix"
        InitializationExceptionMessage.DB_QUERY_FAILED -> "Failed to fetch metadata from database$suffix"
        InitializationExceptionMessage.MIGRATION_FAILED -> "Database migration failed. Check your SQL migration files$suffix"
        InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB ->
            "Startup validation failed. A Kotlin class is annotated with @PgEnum/@PgComposite(name='$details'), but the type '$details' does not exist in the database schemas. Please check your SQL migrations."
        InitializationExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION ->
            "Configuration error. The PostgreSQL type name '$details' is defined more than once in the codebase (detected duplicate or collision between @PgEnum and @PgComposite). Postgres requires unique type names within a schema."
        InitializationExceptionMessage.DUPLICATE_DYNAMIC_TYPE_DEFINITION ->
            "Configuration error. The Dynamic DTO key '$details' is defined more than once. Check your @DynamicallyMappable(typeName=...) annotations."
    }
}