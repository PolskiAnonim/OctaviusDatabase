# Error Handling

Octavius Database uses a **Result type pattern** instead of throwing exceptions. All database operations return `DataResult<T>` which forces explicit handling of both success and failure cases.

> **Working with DataResult**: For `DataResult` usage patterns (`map`, `onSuccess`, `getOrElse`, `assertNotNull`, etc.), see [Executing Queries](executing-queries.md#dataresult).

## Table of Contents

- [Builder Validation Errors](#builder-validation-errors)
- [Exception Hierarchy](#exception-hierarchy)
- [QueryExecutionException](#queryexecutionexception)
- [ConversionException](#conversionexception)
- [TransactionStepExecutionException](#transactionstepexecutionexception)
- [TransactionException](#transactionexception)
- [StepDependencyException](#stepdependencyexception)
- [TypeRegistryException](#typeregistryexception)
- [Logging and Debugging](#logging-and-debugging)

---

## Builder Validation Errors

Query builders validate their configuration when building SQL. These errors throw standard Kotlin exceptions (`IllegalArgumentException`, `IllegalStateException`) rather than returning `DataResult.Failure`:

| Error | Exception | Cause |
|-------|-----------|-------|
| `having()` without `groupBy()` | `IllegalArgumentException` | HAVING requires GROUP BY |
| `DELETE`/`UPDATE` without `where()` | `IllegalStateException` | Safety check to prevent accidental mass changes |
| `fromSelect()` after `values()` | `IllegalStateException` | Mutually exclusive operations |
| `values()` after `fromSelect()` | `IllegalStateException` | Mutually exclusive operations |
| No values or select in INSERT | `IllegalStateException` | Nothing to insert |

**Why not DataResult?**

These are **programmer errors** (contract violations), not runtime errors. They should be caught during development and never occur in production. Additionally, `toSql()` returns `String`, not `DataResult`, so builder validation must throw exceptions for consistency.

```kotlin
// This throws IllegalArgumentException, not DataResult.Failure
dataAccess.select("department", "AVG(salary)")
    .from("employees")
    .having("AVG(salary) > 50000")  // Missing groupBy()!
    .toListOf<DeptStats>()
```

---

## Exception Hierarchy

All database exceptions inherit from the sealed class `DatabaseException`:

```
DatabaseException (sealed)
├── QueryExecutionException           - SQL execution errors
├── ConversionException               - Type mapping/conversion errors
├── TransactionException              - Transaction execution failures
├── TransactionStepExecutionException - Transaction plan step failures
├── StepDependencyException           - Invalid step references in plans
└── TypeRegistryException             - Type registry/mapping errors
```

### Exception Nesting (Cause Chain)

Octavius wraps exceptions in a hierarchy. The outer exception provides context, the inner exception (`.cause`) contains the root cause:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ dataAccess.transaction { }                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ TransactionException                                                         │
│   └── cause: QueryExecutionException (query failed inside transaction)       │
│               └── cause: Spring/JDBC exception OR ConversionException        │
│                                                                              │
│   └── cause: Spring TransactionException (timeout, deadlock, etc.)           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ dataAccess.executeTransactionPlan(plan)                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ TransactionStepExecutionException (stepIndex = N)                            │
│   └── cause: QueryExecutionException (SQL error in step N)                   │
│               └── cause: Spring/JDBC exception OR ConversionException        │
│                                                                              │
│   └── cause: StepDependencyException (invalid reference in step N)           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ Single query (outside transaction)                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ QueryExecutionException                                                      │
│   └── cause: Spring/JDBC exception (PSQLException, etc.)                     │
│   └── cause: ConversionException (type mapping failed)                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Unwrapping Exceptions

To get to the root cause:

```kotlin
result.onFailure { error ->
    when (error) {
        is TransactionException -> {
            when (val cause = error.cause) {
                is QueryExecutionException -> {
                    // Query failed inside transaction
                    println("SQL: ${cause.sql}")

                    // Check deeper cause
                    when (val rootCause = cause.cause) {
                        is ConversionException -> println("Conversion: ${rootCause.messageEnum}")
                        else -> println("JDBC error: ${rootCause?.message}")
                    }
                }
                else -> println("Transaction error: ${cause?.message}")
            }
        }

        is TransactionStepExecutionException -> {
            println("Step ${error.stepIndex} failed")
            when (val cause = error.cause) {
                is StepDependencyException -> println("Bad reference: ${cause.messageEnum}")
                is QueryExecutionException -> println("SQL: ${cause.sql}")
                else -> println("Error: ${cause.message}")
            }
        }

        is QueryExecutionException -> {
            // Direct query failure (not in transaction)
            println("SQL: ${error.sql}")
        }

        else -> println("Other error: ${error.message}")
    }
}
```

---

## QueryExecutionException

Thrown when SQL query execution fails. Contains full debugging context.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `sql` | `String` | Original SQL with named parameters |
| `params` | `Map<String, Any?>` | Original parameter map |
| `expandedSql` | `String?` | SQL with positional placeholders (`$1`, `$2`) |
| `expandedParams` | `List<Any?>?` | Converted parameters in positional order |
| `cause` | `Throwable?` | Underlying JDBC exception |

### When Thrown

- SQL syntax errors
- Constraint violations (unique, foreign key, check)
- Connection errors
- Permission errors
- Data type mismatches in database

### Example Output

```
------------------------------------------------------------
|  QUERY EXECUTION FAILED
------------------------------------------------------------
| Message: Error during query execution
|
|---[ Original Query ]---
| SQL: INSERT INTO users (email, name) VALUES (:email, :name)
| Params:
|   email = john@example.com
|   name = John
|
|---[ Execution Details (Low Level) ]---
| expandedSql: INSERT INTO users (email, name) VALUES ($1, $2)
| expandedParams (2):
|    [0] -> john@example.com
|    [1] -> John
------------------------------------------------------------
| Error Cause:
|   org.postgresql.util.PSQLException: ERROR: duplicate key value
|   violates unique constraint "users_email_key"
------------------------------------------------------------
```

### Handling

```kotlin
result.onFailure { error ->
    when (error) {
        is QueryExecutionException -> {
            println("Query failed: ${error.sql}")
            println("With params: ${error.params}")

            // Check for specific PostgreSQL errors
            val pgError = error.cause?.message ?: ""
            when {
                "duplicate key" in pgError -> handleDuplicateKey()
                "foreign key" in pgError -> handleForeignKeyViolation()
                else -> logGenericError(error)
            }
        }
        else -> { /* other error types */ }
    }
}
```

---

## ConversionException

Thrown when converting between PostgreSQL and Kotlin types fails.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `messageEnum` | `ConversionExceptionMessage` | Error type enum |
| `value` | `Any?` | The value that failed to convert |
| `targetType` | `String?` | Target Kotlin type |
| `rowData` | `Map<String, Any?>?` | Full row context (for object mapping) |
| `propertyName` | `String?` | Property name (for object mapping) |

### Error Types

| Enum Value | Description |
|------------|-------------|
| `VALUE_CONVERSION_FAILED` | General type conversion error |
| `ENUM_CONVERSION_FAILED` | Database value doesn't match Kotlin enum |
| `UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY` | Complex types in native JDBC arrays |
| `INVALID_DYNAMIC_DTO_FORMAT` | Malformed `dynamic_dto` value |
| `INCOMPATIBLE_COLLECTION_ELEMENT_TYPE` | Wrong element type in collection |
| `INCOMPATIBLE_TYPE` | General type mismatch |
| `OBJECT_MAPPING_FAILED` | Data class instantiation error |
| `MISSING_REQUIRED_PROPERTY` | Missing field for non-nullable property |
| `JSON_DESERIALIZATION_FAILED` | JSON parsing error in dynamic_dto |
| `JSON_SERIALIZATION_FAILED` | Object to JSON conversion error |

### Where It Appears

`ConversionException` is **nested inside** `QueryExecutionException.cause` - it's never returned directly:

```
QueryExecutionException
  └── cause: ConversionException  ← here
```

### Handling

```kotlin
result.onFailure { error ->
    when (error) {
        is QueryExecutionException -> {
            // Check if the cause is a ConversionException
            val conversionError = error.cause as? ConversionException
            if (conversionError != null) {
                when (conversionError.messageEnum) {
                    ConversionExceptionMessage.MISSING_REQUIRED_PROPERTY -> {
                        println("Missing field '${conversionError.propertyName}' for type ${conversionError.targetType}")
                        println("Available data: ${conversionError.rowData}")
                    }
                    ConversionExceptionMessage.ENUM_CONVERSION_FAILED -> {
                        println("Invalid enum value '${conversionError.value}' for ${conversionError.targetType}")
                    }
                    else -> println("Conversion error: ${conversionError.messageEnum}")
                }
            } else {
                // Other SQL error (constraint violation, syntax, etc.)
                println("SQL error: ${error.cause?.message}")
            }
        }
        else -> { /* other error types */ }
    }
}
```

---

## TransactionStepExecutionException

Thrown when a step in a `TransactionPlan` fails.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `stepIndex` | `Int` | Zero-based index of the failed step |
| `cause` | `Throwable` | Original exception (usually `QueryExecutionException`) |

### Handling

```kotlin
val result = dataAccess.executeTransactionPlan(plan)

result.onFailure { error ->
    when (error) {
        is TransactionStepExecutionException -> {
            println("Step ${error.stepIndex} failed")

            // Get the underlying cause
            when (val cause = error.cause) {
                is QueryExecutionException -> {
                    println("SQL: ${cause.sql}")
                    println("Params: ${cause.params}")
                }
                else -> println("Cause: ${cause.message}")
            }
        }
        else -> { /* other error types */ }
    }
}
```

---

## TransactionException

Thrown when transaction execution fails (outside of step-specific errors).

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `cause` | `Throwable` | Original exception |

### When Thrown

- Transaction timeout
- Deadlock detected
- Connection lost during transaction
- Rollback failure

### Handling

```kotlin
result.onFailure { error ->
    when (error) {
        is TransactionException -> {
            val causeMessage = error.cause?.message ?: "Unknown"

            when {
                "deadlock" in causeMessage.lowercase() -> {
                    // Retry logic
                    retryTransaction()
                }
                "timeout" in causeMessage.lowercase() -> {
                    // Increase timeout or break up transaction
                    handleTimeout()
                }
                else -> logTransactionError(error)
            }
        }
        else -> { /* other error types */ }
    }
}
```

---

## StepDependencyException

Thrown when a `TransactionValue.FromStep` reference is invalid.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `messageEnum` | `StepDependencyExceptionMessage` | Error type |
| `referencedStepIndex` | `Int` | Index of the step being referenced |
| `args` | `Array<Any>` | Additional context (row index, column name, etc.) |

### Error Types

| Enum Value | Description |
|------------|-------------|
| `DEPENDENCY_ON_FUTURE_STEP` | Step references a step that runs later |
| `UNKNOWN_STEP_HANDLE` | Handle doesn't exist in the plan |
| `RESULT_NOT_FOUND` | Referenced step hasn't been executed |
| `NULL_SOURCE_RESULT` | Referenced step returned null |
| `ROW_INDEX_OUT_OF_BOUNDS` | Row index exceeds result size |
| `RESULT_NOT_LIST` | Expected list result, got something else |
| `RESULT_NOT_MAP_LIST` | Expected `List<Map>`, got different structure |
| `INVALID_ROW_ACCESS_ON_NON_LIST` | Using `row(n)` on non-list result |
| `COLUMN_NOT_FOUND` | Referenced column doesn't exist |
| `SCALAR_NOT_FOUND` | Can't extract scalar from result |
| `TRANSFORMATION_FAILED` | `.map {}` transformation threw exception |

### Where It Appears

`StepDependencyException` is **nested inside** `TransactionStepExecutionException.cause` - it's never returned directly:

```
TransactionStepExecutionException (stepIndex = N)
  └── cause: StepDependencyException  ← here
```

### Handling

```kotlin
val result = dataAccess.executeTransactionPlan(plan)

result.onFailure { error ->
    when (error) {
        is TransactionStepExecutionException -> {
            val stepError = error.cause as? StepDependencyException
            if (stepError != null) {
                println("Step ${error.stepIndex} has invalid reference")
                println("Error: ${stepError.messageEnum}")
                println("Referenced step: ${stepError.referencedStepIndex}")
            }
        }
        else -> { /* other error types */ }
    }
}
```

### Common Mistakes

```kotlin
// WRONG: Using handle from different plan
val planA = TransactionPlan()
val handleA = planA.add(/* ... */)

val planB = TransactionPlan()
planB.add(
    dataAccess.insertInto("table")
        .values(listOf("col"))
        .asStep()
        .execute("col" to handleA.field())  // Error: UNKNOWN_STEP_HANDLE
)

// WRONG: Accessing column that doesn't exist
val handle = plan.add(
    dataAccess.select("id", "name")  // Note: no "email" column
        .from("users")
        .asStep()
        .toSingle()
)
plan.add(
    dataAccess.rawQuery("...")
        .asStep()
        .execute("email" to handle.field("email"))  // Error: COLUMN_NOT_FOUND
)

// WRONG: Row index out of bounds
val handle = plan.add(
    dataAccess.select("*")
        .from("users")
        .limit(1)  // Only 1 row
        .asStep()
        .toList()
)
plan.add(
    dataAccess.rawQuery("...")
        .asStep()
        .execute("val" to handle.field("id", rowIndex = 5))  // Error: ROW_INDEX_OUT_OF_BOUNDS
)
```

---

## TypeRegistryException

Thrown during type registry initialization or lookup.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `messageEnum` | `TypeRegistryExceptionMessage` | Error type |
| `typeName` | `String?` | Related type name |

### Error Types

**Initialization Errors (startup):**

| Enum Value | Description |
|------------|-------------|
| `INITIALIZATION_FAILED` | Critical registry initialization failure |
| `CLASSPATH_SCAN_FAILED` | ClassGraph scanning error |
| `DB_QUERY_FAILED` | Failed to query database for types |

**Schema Consistency Errors (startup):**

| Enum Value | Description |
|------------|-------------|
| `TYPE_DEFINITION_MISSING_IN_DB` | `@PgEnum`/`@PgComposite` without matching DB type |
| `DUPLICATE_PG_TYPE_DEFINITION` | Multiple classes with same PostgreSQL type name |
| `DUPLICATE_DYNAMIC_TYPE_DEFINITION` | Multiple `@DynamicallyMappable` with same key |

**Runtime Lookup Errors:**

| Enum Value | Description |
|------------|-------------|
| `WRONG_FIELD_NUMBER_IN_COMPOSITE` | Composite type field count mismatch |
| `PG_TYPE_NOT_FOUND` | PostgreSQL type not in registry |
| `KOTLIN_CLASS_NOT_MAPPED` | Kotlin class has no type mapping |
| `PG_TYPE_NOT_MAPPED` | No Kotlin class for PostgreSQL type |
| `DYNAMIC_TYPE_NOT_FOUND` | Unknown `dynamic_dto` type key |

### Handling

```kotlin
// These usually occur at startup
try {
    val dataAccess = OctaviusDatabase.fromConfig(config)
} catch (e: TypeRegistryException) {
    when (e.messageEnum) {
        TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB -> {
            println("Missing SQL migration for type: ${e.typeName}")
            println("Add: CREATE TYPE ${e.typeName} AS ...")
        }
        TypeRegistryExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION -> {
            println("Duplicate type name: ${e.typeName}")
            println("Check @PgEnum and @PgComposite annotations")
        }
        else -> throw e
    }
}
```

---

## Logging and Debugging

### Just Use toString()

All exceptions have overridden `toString()` that includes the full context (SQL, params, cause chain). No need to manually unwrap:

```kotlin
result.onFailure { error ->
    logger.error(error.toString())  // Full context automatically included
}
```

### Handling Specific PostgreSQL Errors

Unwrap the hierarchy when you need to handle specific cases:

```kotlin
result.onFailure { error ->
    // Check for duplicate key to show user-friendly message
    val pgError = (error as? QueryExecutionException)?.cause?.message ?: ""
    when {
        "duplicate key" in pgError && "users_email_key" in pgError -> {
            throw ValidationException("Email already exists")
        }
        "deadlock" in pgError.lowercase() -> {
            // Retry logic
            retryOperation()
        }
        else -> throw error  // Re-throw for global handler
    }
}
```

---

## See Also

- [Executing Queries](executing-queries.md) - DataResult patterns and usage
- [Transactions](transactions.md) - Transaction error handling