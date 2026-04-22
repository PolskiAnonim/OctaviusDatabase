# Error Handling

*The Roman jurist distinguished carefully between errors of fact and errors of law, between fatal defects in a contract and minor irregularities that could be remedied. Octavius makes the same distinction: some errors are fatal and must halt execution immediately, while others are returned safely so the calling code may decide how to proceed.*

Octavius Database divides errors into two simple categories: **Database Execution Errors** (returned safely) and **Fatal/Setup Errors** (thrown immediately).

* **Database Execution (Returned):** If a query goes to the database, it **never throws**. Whether it's a bad SQL syntax, a constraint violation, or a missing table, `DataResult.Failure<DatabaseException>` is always returned. This forces explicit error handling.
* **Fatal Errors (Thrown):** If an obvious setup mistake is made (like using `.execute()` with a `returning()` method) or the database fails to connect on startup, Octavius **throws a standard exception**. It fails fast because the query or application state is fundamentally broken and should not proceed.

> **Working with DataResult**: For `DataResult` usage patterns (`map`, `onSuccess`, `getOrElse`, etc.), see [Executing Queries](executing-queries.md#dataresult).

## Table of Contents

- [BuilderException](#builderexception)
- [InitializationException](#initializationexception)
- [Exception Hierarchy](#exception-hierarchy)
- [StatementException](#statementexception)
- [ConstraintViolationException](#constraintviolationexception)
- [ConcurrencyException](#concurrencyexception)
- [ConnectionException](#connectionexception)
- [ConversionException](#conversionexception)
- [StepDependencyException](#stepdependencyexception)
- [TypeRegistryException](#typeregistryexception)
- [UnknownDatabaseException](#unknowndatabaseexception)
- [Logging and Debugging](#logging-and-debugging)

---

## BuilderException

`BuilderException` is thrown when a query cannot be built correctly due to invalid state or missing mandatory clauses. Unlike other errors, this is a **programmer error** (contract violation) and is thrown immediately, bypassing the `DataResult` pattern.

### Common Validation Errors

| Error                                            | Cause                                           |
|--------------------------------------------------|-------------------------------------------------|
| `DELETE`/`UPDATE` without `where()`              | Safety check to prevent accidental mass changes |
| `execute()` with `RETURNING`                     | Must use `toList()`, `toSingle()`, etc.         |
| `toList()`, `toField()` etc. without `RETURNING` | Modifying queries require `.returning()`        |
| `fromSelect()` after `values()`                  | Mutually exclusive operations                   |
| `values()` after `fromSelect()`                  | Mutually exclusive operations                   |
| No values or select in INSERT                    | Nothing to insert                               |
| `page()` with negative values                    | Invalid pagination parameters                   |

**Why not DataResult?**

These errors should be caught during development and never occur in production. They indicate that the SQL being generated is fundamentally broken. `BuilderException` also propagates through `TransactionPlan` and `DataAccess.transaction` blocks.

```kotlin
// This throws BuilderException immediately
dataAccess.deleteFrom("citizens")
    .execute() // Fails: missing .where()
```

---

## InitializationException

Thrown during the startup of `OctaviusDatabase` when the system fails to validate its configuration or the database schema.
It inherits directly from **`RuntimeException`** and is **NOT** wrapped in `DataResult`.

### Error Types (`InitializationExceptionMessage`)

| Enum Value                          | Description                                              |
|-------------------------------------|----------------------------------------------------------|
| `INITIALIZATION_FAILED`             | General fatal error during system startup                |
| `CONNECTION_FAILED`                 | Failed to establish connection or initialize pool        |
| `CLASSPATH_SCAN_FAILED`             | Error scanning project for annotations                   |
| `DB_QUERY_FAILED`                   | Failed to fetch metadata from PostgreSQL                 |
| `MIGRATION_FAILED`                  | Database migration failed (e.g., via Flyway integration) |
| `TYPE_DEFINITION_MISSING_IN_DB`     | Annotated class exists, but CREATE TYPE is missing in DB |
| `DUPLICATE_PG_TYPE_DEFINITION`      | Conflict between two @PgType names                       |
| `DUPLICATE_DYNAMIC_TYPE_DEFINITION` | Conflict between two @DynamicallyMappable keys           |

---

## Exception Hierarchy

All other exceptions inherit from the sealed class `DatabaseException`. Octavius prioritizes PostgreSQL-specific error codes (SQLSTATE) to provide precise error types.

*Note: Although these classes inherit from standard JVM Exceptions (to capture stack traces and causes), they are **returned** inside `DataResult.Failure(error)`, not thrown (no `try-catch` needed).*

```
DatabaseException (sealed)
├── StatementException                - SQL syntax, permissions, and invalid statements
├── ConstraintViolationException      - Data integrity (unique, FK, check) violations
├── ConcurrencyException              - Deadlocks and query timeouts
├── ConnectionException               - Infrastructure and connectivity issues
├── ConversionException               - Type mapping and conversion failures
├── StepDependencyException           - Invalid step references in Transaction Plans
├── TypeRegistryException             - Type registry and mapping errors
└── UnknownDatabaseException          - Fallback for unrecognized errors
```

### Exception Enrichment

Every `DatabaseException` contains a `QueryContext` when it is available. This object stores the original SQL, parameters, and the low-level SQL actually sent to PostgreSQL. It also tracks the `transactionStepIndex` when running inside a `TransactionPlan`.

The `QueryContext` is automatically printed as part of the exception's `toString()` output, providing a clean visualization of the execution state.

---

## StatementException

Returned when the SQL statement itself is invalid or fails due to database-level rules.

### Properties

| Property       | Type                        | Description                        |
|----------------|-----------------------------|------------------------------------|
| `messageEnum`  | `StatementExceptionMessage` | Specific error type                |
| `detail`       | `String?`                   | Technical detail from the database |
| `queryContext` | `QueryContext`              | Full context (SQL, parameters)     |

### Error Types (`StatementExceptionMessage`)

| Enum Value                  | PostgreSQL Class / State | Description                                   |
|-----------------------------|--------------------------|-----------------------------------------------|
| `SYNTAX_ERROR`              | Class 426xx              | Malformed SQL or syntax errors                |
| `OBJECT_NOT_FOUND`          | Class 42Pxx / 427xx      | Missing table, column, or function            |
| `PERMISSION_DENIED`         | 42501                    | Insufficient privileges for the operation     |
| `INVALID_AUTHORIZATION`     | Class 28                 | Failed authentication or invalid role         |
| `DATA_EXCEPTION`            | Class 22                 | Invalid data format, value out of range, etc. |
| `INVALID_TRANSACTION_STATE` | Class 25                 | Invalid state (e.g. read-only violation)      |

---

## ConstraintViolationException

Returned when an operation violates data integrity rules in the database. Provides structured information about the violation.

### Properties

| Property         | Type                                  | Description                        |
|------------------|---------------------------------------|------------------------------------|
| `messageEnum`    | `ConstraintViolationExceptionMessage` | Violation type                     |
| `tableName`      | `String?`                             | Name of the table                  |
| `columnName`     | `String?`                             | Name of the column (if applicable) |
| `constraintName` | `String?`                             | Name of the constraint violated    |

### Error Types (`ConstraintViolationExceptionMessage`)

| Enum Value                    | PostgreSQL SQLSTATE | Description                            |
|-------------------------------|---------------------|----------------------------------------|
| `UNIQUE_CONSTRAINT_VIOLATION` | 23505               | Duplicate value for a unique field     |
| `FOREIGN_KEY_VIOLATION`       | 23503               | Referenced record does not exist       |
| `NOT_NULL_VIOLATION`          | 23502               | Null value for a non-nullable column   |
| `CHECK_CONSTRAINT_VIOLATION`  | 23514               | Value violates a business rule (CHECK) |
| `DATA_INTEGRITY`              | Other Class 23      | General integrity failure              |

---

## ConcurrencyException

Returned during transaction-related conflicts or when query execution takes too long.

### Properties

| Property    | Type                   | Description                                       |
|-------------|------------------------|---------------------------------------------------|
| `errorType` | `ConcurrencyErrorType` | `TIMEOUT`, `DEADLOCK`, or `SERIALIZATION_FAILURE` |

---

## ConnectionException

Returned when the library cannot establish or maintain a connection with the database during query execution.

*Note: For connection failures during **initialization**, see [InitializationException](#initializationexception).*

### When Thrown
- **Database Shutdown**: Operator intervention or administrative shutdown (SQLSTATE Class 57).
- **Resource Exhaustion**: Disk full, out of memory, or too many connections (SQLSTATE Class 53/54).

---

## ConversionException

Returned when data mapping between PostgreSQL and Kotlin fails. Unlike other execution errors, this often indicates a mismatch between database schema and Kotlin data classes.

### Error Types (`ConversionExceptionMessage`)

| Enum Value                             | Description                                      |
|----------------------------------------|--------------------------------------------------|
| `VALUE_CONVERSION_FAILED`              | General type conversion error                    |
| `ENUM_CONVERSION_FAILED`               | Database value doesn't match Kotlin enum         |
| `UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY`  | Complex types in native JDBC arrays              |
| `INVALID_DYNAMIC_DTO_FORMAT`           | Malformed `dynamic_dto` value                    |
| `INCOMPATIBLE_COLLECTION_ELEMENT_TYPE` | Wrong element type in collection                 |
| `INCOMPATIBLE_TYPE`                    | General type mismatch                            |
| `OBJECT_MAPPING_FAILED`                | Data class instantiation error                   |
| `MISSING_REQUIRED_PROPERTY`            | Missing field for a non-nullable constructor arg |
| `JSON_DESERIALIZATION_FAILED`          | JSON parsing error in dynamic_dto                |
| `JSON_SERIALIZATION_FAILED`            | Object to JSON conversion error                  |
| `UNEXPECTED_NULL_VALUE`                | Null value for non-nullable target type          |
| `EMPTY_RESULT`                         | No rows when at least one was expected           |
| `TOO_MANY_ROWS`                        | Multiple rows returned for a single-row method   |

---

## StepDependencyException

Returned when a `TransactionValue.FromStep` reference is invalid.

| Error Type                       | Description                                   |
|----------------------------------|-----------------------------------------------|
| `DEPENDENCY_ON_FUTURE_STEP`      | Step references a step that runs later        |
| `UNKNOWN_STEP_HANDLE`            | Handle doesn't exist in the plan              |
| `ROW_INDEX_OUT_OF_BOUNDS`        | Row index exceeds result size                 |
| `RESULT_NOT_LIST`                | Expected list result, got something else      |
| `RESULT_NOT_MAP_LIST`            | Expected `List<Map>`, got different structure |
| `INVALID_ROW_ACCESS_ON_NON_LIST` | Using `row(n)` on non-list result             |
| `COLUMN_NOT_FOUND`               | Referenced column doesn't exist               |
| `SCALAR_NOT_FOUND`               | Can't extract scalar from result              |
| `TRANSFORMATION_FAILED`          | `.map {}` transformation threw exception      |

---

## TypeRegistryException

Returned when a runtime lookup in the internal type registry fails. This indicates a mismatch between Kotlin classes and database objects.

### Error Types (`TypeRegistryExceptionMessage`)

| Enum Value                        | Description                                      |
|-----------------------------------|--------------------------------------------------|
| `WRONG_FIELD_NUMBER_IN_COMPOSITE` | Composite type schema mismatch                   |
| `PG_TYPE_NOT_FOUND`               | PostgreSQL type missing from the loaded registry |
| `KOTLIN_CLASS_NOT_MAPPED`         | Kotlin class has no @PgType annotation           |
| `DYNAMIC_TYPE_NOT_FOUND`          | Unknown key for `dynamic_dto` polymorphism       |

---

## UnknownDatabaseException

A generic fallback exception used for errors that do not fit into specific categories or for unrecognized SQLSTATE codes. It includes the full `QueryContext` and the original `cause`.

---

## Logging and Debugging

### Just Use toString()

All Octavius exceptions have a standardized `toString()` override that prints the full context (via `QueryContext`), error details, and the underlying cause chain.

```kotlin
result.onFailure { error ->
    logger.error(error.toString())
}
```

### Typical Output Format

Octavius uses simple line separators to clearly separate the execution context from the error details.

```text
================================================================================
DATABASE EXECUTION CONTEXT
================================================================================
HIGH-LEVEL SQL:
INSERT INTO citizens (name, tribe) VALUES (@name, @tribe)
--------------------------------------------------------------------------------
PARAMETERS:
name = Marcus Tullius
tribe = Cornelia
--------------------------------------------------------------------------------
DATABASE-LEVEL SQL (SENT TO DB):
INSERT INTO citizens (name, tribe) VALUES ($1, $2)
--------------------------------------------------------------------------------
DATABASE-LEVEL PARAMETERS:
Marcus Tullius
Cornelia
================================================================================

------------------------------------------------------------
ERROR: ConstraintViolationException
MESSAGE: UNIQUE_CONSTRAINT_VIOLATION
DETAILS: 
message: Unique constraint violation in table 'citizens'.
table: citizens
column: name
constraint: citizens_name_tribe_key
------------------------------------------------------------
CAUSE: 
------------------------------------------------------------
org.postgresql.util.PSQLException: ERROR: duplicate key value ...
------------------------------------------------------------
```

---

## See Also

- [Executing Queries](executing-queries.md) - DataResult patterns and usage
- [Transactions](transactions.md) - Transaction execution and rollback logic
- [Type System](type-system.md) - How custom types are mapped and registered
