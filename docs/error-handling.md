# Error Handling

Octavius Database uses a **Result type pattern** instead of throwing exceptions. All database operations return `DataResult<T>` which forces explicit handling of both success and failure cases.

> **Working with DataResult**: For `DataResult` usage patterns (`map`, `onSuccess`, `getOrElse`, etc.), see [Executing Queries](executing-queries.md#dataresult).

## Table of Contents

- [Builder Validation Errors](#builder-validation-errors)
- [Exception Hierarchy](#exception-hierarchy)
- [StatementException](#statementexception)
- [ConstraintViolationException](#constraintviolationexception)
- [ConcurrencyException](#concurrencyexception)
- [ConnectionException](#connectionexception)
- [ConversionException](#conversionexception)
- [StepDependencyException](#stepdependencyexception)
- [TypeRegistryException](#typeregistryexception)
- [InitializationException](#initializationexception)
- [UnknownDatabaseException](#unknowndatabaseexception)
- [Logging and Debugging](#logging-and-debugging)

---

## Builder Validation Errors

Query builders validate their configuration when building SQL. These errors throw standard Kotlin exceptions (`IllegalArgumentException`, `IllegalStateException`) rather than returning `DataResult.Failure`:

| Error                               | Exception                  | Cause                                           |
|-------------------------------------|----------------------------|-------------------------------------------------|
| `having()` without `groupBy()`      | `IllegalArgumentException` | HAVING requires GROUP BY                        |
| `DELETE`/`UPDATE` without `where()` | `IllegalStateException`    | Safety check to prevent accidental mass changes |
| `fromSelect()` after `values()`     | `IllegalStateException`    | Mutually exclusive operations                   |
| `values()` after `fromSelect()`     | `IllegalStateException`    | Mutually exclusive operations                   |
| No values or select in INSERT       | `IllegalStateException`    | Nothing to insert                               |

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

All database exceptions inherit from the sealed class `DatabaseException`. Octavius prioritizes PostgreSQL-specific error codes (SQLSTATE) to provide precise error types.

```
DatabaseException (sealed)
├── StatementException                - SQL syntax, permissions, and invalid statements
├── ConstraintViolationException      - Data integrity (unique, FK, check) violations
├── ConcurrencyException              - Deadlocks and query timeouts
├── ConnectionException               - Infrastructure and connectivity issues
├── ConversionException               - Type mapping and conversion failures
├── StepDependencyException           - Invalid step references in Transaction Plans
├── TypeRegistryException             - Type registry and mapping errors
├── InitializationException           - Startup and configuration errors
└── UnknownDatabaseException          - Fallback for unrecognized errors
```

### Exception Enrichment

Every `DatabaseException` contains a `QueryContext` when it is available. This object stores the original SQL, parameters, and the low-level SQL actually sent to PostgreSQL. It also tracks the `transactionStepIndex` when running inside a `TransactionPlan`.

The `QueryContext` is automatically printed as part of the exception's `toString()` output, providing a clear, framed visualization of the execution state.

---

## StatementException

Thrown when the SQL statement itself is invalid or fails due to database-level rules.

### Properties

| Property       | Type                        | Description                        |
|----------------|-----------------------------|------------------------------------|
| `messageEnum`  | `StatementExceptionMessage` | Specific error type                |
| `detail`       | `String?`                   | Technical detail from the database |
| `queryContext` | `QueryContext`              | Full context (SQL, parameters)     |

### Error Types (`StatementExceptionMessage`)

| Enum Value              | PostgreSQL Class / State | Description                                     |
|-------------------------|--------------------------|-------------------------------------------------|
| `SYNTAX_ERROR`          | Class 426xx              | Malformed SQL or syntax errors                  |
| `OBJECT_NOT_FOUND`      | Class 42Pxx / 427xx      | Missing table, column, or function              |
| `PERMISSION_DENIED`     | 42501                    | Insufficient privileges for the operation       |
| `INVALID_AUTHORIZATION` | Class 28                 | Failed authentication or invalid role           |
| `DATA_EXCEPTION`        | Class 22                 | Invalid data format, value out of range, etc.   |

---

## ConstraintViolationException

Thrown when an operation violates data integrity rules in the database. Provides structured information about the violation.

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

Thrown during transaction-related conflicts or when query execution takes too long.

### Properties

| Property    | Type                   | Description                               |
|-------------|------------------------|-------------------------------------------|
| `errorType` | `ConcurrencyErrorType` | Either `TIMEOUT` or `DEADLOCK`            |

---

## ConnectionException

Thrown when the library cannot establish or maintain a connection with the database. These are typically infrastructure issues.

### When Thrown

- **Connection Refused**: Wrong host or port in `DatabaseConfig`.
- **Authentication Failure**: Wrong username or password (captured at connection level).
- **Database Shutdown**: Operator intervention or administrative shutdown (SQLSTATE Class 57).
- **Resource Exhaustion**: Disk full, out of memory, or too many connections (SQLSTATE Class 53/54).

---

## ConversionException

Thrown when data mapping between PostgreSQL and Kotlin fails. Unlike other execution errors, this often indicates a mismatch between database schema and Kotlin data classes.

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

Thrown when a `TransactionValue.FromStep` reference is invalid.

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

Thrown during runtime lookups in the internal type registry. This indicates a mismatch between Kotlin classes and database objects.

### Error Types (`TypeRegistryExceptionMessage`)

| Enum Value                        | Description                                      |
|-----------------------------------|--------------------------------------------------|
| `WRONG_FIELD_NUMBER_IN_COMPOSITE` | Composite type schema mismatch                   |
| `PG_TYPE_NOT_FOUND`               | PostgreSQL type missing from the loaded registry |
| `KOTLIN_CLASS_NOT_MAPPED`         | Kotlin class has no @PgType annotation           |
| `DYNAMIC_TYPE_NOT_FOUND`          | Unknown key for `dynamic_dto` polymorphism       |

---

## InitializationException

Thrown during the startup of `OctaviusDatabase` when the system fails to validate its configuration or the database schema.
It is **NOT** wrapped in DataResult.

### Error Types (`InitializationExceptionMessage`)

| Enum Value                          | Description                                              |
|-------------------------------------|----------------------------------------------------------|
| `INITIALIZATION_FAILED`             | General fatal error during system startup                |
| `CLASSPATH_SCAN_FAILED`             | Error scanning project for annotations                   |
| `DB_QUERY_FAILED`                   | Failed to fetch metadata from PostgreSQL                 |
| `MIGRATION_FAILED`                  | Flyway migration failed                                  |
| `TYPE_DEFINITION_MISSING_IN_DB`     | Annotated class exists, but CREATE TYPE is missing in DB |
| `DUPLICATE_PG_TYPE_DEFINITION`      | Conflict between two @PgType names                       |
| `DUPLICATE_DYNAMIC_TYPE_DEFINITION` | Conflict between two @DynamicallyMappable keys           |

---

## UnknownDatabaseException

A generic fallback exception used for errors that do not fit into specific categories or for unrecognized SQLSTATE codes. It includes the full `QueryContext` and the original `cause`.

---

## Logging and Debugging

### Just Use toString()

All `DatabaseException` subclasses have a standardized `toString()` override that prints the full context (via `QueryContext`), error details, and the underlying cause chain.

```kotlin
result.onFailure { error ->
    logger.error(error.toString()) 
}
```

### Typical Output Format

Octavius uses a double-line frame to clearly separate the execution context from the error details.

```text
╔══════════════════════════════════════════════════════════════════════════════╗
║ DATABASE EXECUTION CONTEXT                                                   ║
╠══════════════════════════════════════════════════════════════════════════════╣
║ HIGH-LEVEL SQL:                                                              ║
║   INSERT INTO users (email, name) VALUES (:email, :name)                     ║
╟──────────────────────────────────────────────────────────────────────────────╢
║ PARAMETERS:                                                                  ║
║   email = john@example.com                                                   ║
║   name = John                                                                ║
╟──────────────────────────────────────────────────────────────────────────────╢
║ DATABASE-LEVEL SQL (SENT TO DB):                                             ║
║   INSERT INTO users (email, name) VALUES ($1, $2)                            ║
╟──────────────────────────────────────────────────────────────────────────────╢
║ DATABASE-LEVEL PARAMETERS:                                                   ║
║   john@example.com                                                           ║
║   John                                                                       ║
╚══════════════════════════════════════════════════════════════════════════════╝

------------------------------------------------------------
| ERROR: ConstraintViolationException
| MESSAGE: UNIQUE_CONSTRAINT_VIOLATION
| DETAILS: 
| message: Unique constraint violation in table 'users'.
| table: users
| column: email
| constraint: users_email_key
------------------------------------------------------------
| CAUSE: 
|   org.postgresql.util.PSQLException: ERROR: duplicate key value ...
------------------------------------------------------------
```

---

## See Also

- [Executing Queries](executing-queries.md) - DataResult patterns and usage
- [Transactions](transactions.md) - Transaction execution and rollback logic
- [Type System](type-system.md) - How custom types are mapped and registered
