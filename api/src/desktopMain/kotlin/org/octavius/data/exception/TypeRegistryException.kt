package org.octavius.data.exception

enum class TypeRegistryExceptionMessage {
    WRONG_FIELD_NUMBER_IN_COMPOSITE, // Registry <-> database mismatch
    PG_TYPE_NOT_FOUND,               // Registry lookup failed (e.g. converting DB value -> Kotlin)
    KOTLIN_CLASS_NOT_MAPPED,         // Registry lookup failed (e.g. Kotlin param -> SQL)
    PG_TYPE_NOT_MAPPED,              // Inverse lookup failed (PG name -> KClass)
    DYNAMIC_TYPE_NOT_FOUND           // Dynamic DTO key lookup failed
}

class TypeRegistryException(
    val messageEnum: TypeRegistryExceptionMessage,
    val typeName: String,
    cause: Throwable? = null,
    queryContext: QueryContext? = null
) : DatabaseException(
    queryContext = queryContext,
    message = messageEnum.name,
    cause = cause
) {

    override fun getDetailedMessage(): String {
        return """
| message: ${generateDeveloperMessage(this.messageEnum, typeName)}
| Related Type: $typeName
"""
    }
}
private fun generateDeveloperMessage(messageEnum: TypeRegistryExceptionMessage, typeName: String?): String {
    return when (messageEnum) {
        TypeRegistryExceptionMessage.WRONG_FIELD_NUMBER_IN_COMPOSITE -> "Schema mismatch. Composite type '$typeName' in the database has a different number of fields than defined in the registry."
        TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND -> "Runtime lookup failed. The PostgreSQL type '$typeName' was not found in the loaded registry."
        TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED -> "Runtime lookup failed. Class '$typeName' is not mapped to any PostgreSQL type. Ensure it has @PgEnum/@PgComposite annotation and is scanned."
        TypeRegistryExceptionMessage.PG_TYPE_NOT_MAPPED -> "Runtime lookup failed. No Kotlin class found mapped to PostgreSQL type '$typeName'."
        TypeRegistryExceptionMessage.DYNAMIC_TYPE_NOT_FOUND -> "Runtime lookup failed. No registered @DynamicallyMappable class found for key '$typeName'."
    }
}
