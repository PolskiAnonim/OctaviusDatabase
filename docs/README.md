# Octavius Database Documentation

Detailed documentation for Octavius Database - an SQL-first data access layer for Kotlin & PostgreSQL.

## Guides

| Document                                              | Description                                                                   |
|-------------------------------------------------------|-------------------------------------------------------------------------------|
| [Configuration](configuration.md)                     | Initialization, DatabaseConfig, Flyway, core types, DynamicDto strategy       |
| [Query Builders](query-builders.md)                   | SELECT, INSERT, UPDATE, DELETE, raw queries, CTEs, subqueries, ON CONFLICT    |
| [Functions & Procedures](functions-and-procedures.md) | Calling functions and procedures                                              |
| [Executing Queries](executing-queries.md)             | Terminal methods, DataResult, assertNotNull, async execution, streaming       |
| [Data Mapping](data-mapping.md)                       | toMap(), toDataObject(), @MapKey - converting between objects and maps        |
| [ORM-Like Patterns](orm-patterns.md)                  | CRUD patterns, real-world examples, PostgreSQL composite types                |
| [Transactions](transactions.md)                       | Transaction blocks, TransactionPlan, StepHandle, passing data between steps   |
| [Notifications](notifications.md)                     | PostgreSQL LISTEN/NOTIFY, PgChannelListener, Flow-based receiving             |
| [Error Handling](error-handling.md)                   | Fatal errors vs. DataResult, Exception hierarchy, Statement/Constraint errors |
| [Type System](type-system.md)                         | @PgEnum, @PgComposite, @DynamicallyMappable, PgTyped, standard type mappings  |

## Quick Links

### Query Building
- [SelectQueryBuilder](query-builders.md#selectquerybuilder) - SELECT queries with JOINs, pagination
- [InsertQueryBuilder](query-builders.md#insertquerybuilder) - INSERT with RETURNING
- [RawQueryBuilder](query-builders.md#rawquerybuilder) - Execute arbitrary SQL
- [QueryFragment & Dynamic Queries](query-builders.md#queryfragment--dynamic-queries) - Composable SQL fragments
- [Common Table Expressions (CTE)](query-builders.md#common-table-expressions-cte) - WITH clauses and recursion
- [Subqueries](query-builders.md#subqueries) - Using subqueries in SELECT/FROM/WHERE
- [ON CONFLICT (Upsert)](query-builders.md#on-conflict-upsert) - Insert or update on conflict
- [Row-Level Locking](query-builders.md#row-level-locking-for-update) - FOR UPDATE, FOR SHARE, SKIP LOCKED
- [Auto Placeholders](query-builders.md#auto-generated-placeholders) - `values()`, `setValues()` auto-generation

### Functions and Procedures
- [Functions](functions-and-procedures.md#functions-create-function) - Calling functions via SELECT
- [Procedures](functions-and-procedures.md#procedures-create-procedure) - Calling procedures via CALL
- [Comparison](functions-and-procedures.md#comparison-functions-vs-procedures) - Functions vs Procedures comparison

### Executing Queries
- [Terminal Methods](executing-queries.md#terminal-methods) - `toList()`, `toListOf()`, `toField()`, `execute()`
- [DataResult](executing-queries.md#dataresult) - Success/Failure result pattern
- [Async Execution](executing-queries.md#async-execution) - Coroutine-based async queries
- [Streaming](executing-queries.md#streaming) - Process large datasets via `Flow`

### Data Mapping
- [toDataObject()](data-mapping.md#todataobject---map-to-data-class) - Map rows to data classes
- [toMap()](data-mapping.md#tomap---data-class-to-map) - Data class to snake_case map
- [@MapKey](data-mapping.md#mapkey-annotation) - Custom property-to-column mapping
- [Nested Structures](data-mapping.md#nested-structures--strict-type-checking) - How nested types are handled

### ORM-Like Patterns
- [Key Advantages](orm-patterns.md#key-advantages) - Why this approach works
- [CRUD Patterns](orm-patterns.md#crud-patterns) - Repository patterns
- [Real-World Example](orm-patterns.md#real-world-example) - Complete configuration manager

### Transactions
- [Transaction Blocks](transactions.md#transaction-blocks) - `dataAccess.transaction { }`
- [Transaction Plans](transactions.md#transaction-plans) - Declarative multi-step operations
- [StepHandle API](transactions.md#stephandle-api) - `field()`, `column()`, `row()`
- [Passing Data Between Steps](transactions.md#passing-data-between-steps) - Reference previous step results
- [Null Handling in Transactions](transactions.md#null-handling-in-transactions) - Results nullability
- [Transaction Propagation](transactions.md#transaction-propagation) - REQUIRED, REQUIRES_NEW, NESTED

### Notifications
- [Sending Notifications](notifications.md#sending-notifications--notify) - `notify()` via `pg_notify`
- [Receiving Notifications](notifications.md#receiving-notifications--createchannellistener) - `createChannelListener()` + `Flow<PgNotification>`
- [Multiple Channels](notifications.md#multiple-channels) - Subscribe to several channels at once
- [Transactions and NOTIFY](notifications.md#transactions-and-notify) - Notifications respect transaction commit
- [Connection Management](notifications.md#connection-management) - Dedicated connections and `use { }`

### Error Handling
- [Fatal vs. Execution Errors](error-handling.md#error-handling) - Categorization of error types
- [BuilderException](error-handling.md#builderexception) - Programmer errors thrown during query building
- [InitializationException](error-handling.md#initializationexception) - Setup and configuration failures
- [Exception Hierarchy](error-handling.md#exception-hierarchy) - `DatabaseException` subtypes returned in `DataResult`
- [StatementException](error-handling.md#statementexception) - SQL syntax and permission errors
- [ConstraintViolationException](error-handling.md#constraintviolationexception) - Data integrity (Unique, FK, Check) violations
- [ConversionException](error-handling.md#conversionexception) - Type mapping and serialization errors
- [Logging and Debugging](error-handling.md#logging-and-debugging) - Diagnostics with full `QueryContext`

### Type System
- [Standard Type Mapping](type-system.md#standard-type-mapping) - PostgreSQL ↔ Kotlin conversions
- [Infinity Values](type-system.md#infinity-values-for-datetime) - Support for `infinity` in dates and durations
- [@PgEnum](type-system.md#pgenum) - Map Kotlin enums to PostgreSQL ENUMs
- [@PgComposite](type-system.md#pgcomposite) - Map data classes to COMPOSITE types
- [PgCompositeMapper](type-system.md#manual-composite-mapping-pgcompositemapper) - Manual mapping of composite types
- [Collections & Parameter Flattening](type-system.md#collections--parameter-flattening) - Serialization logic
- [@DynamicallyMappable](type-system.md#dynamicallymappable) - Polymorphic storage with `dynamic_dto`
- [PgTyped](type-system.md#explicit-type-casts-pgtyped) - Explicit type casts and resolution priority
- [Helper Serializers](type-system.md#helper-serializers) - `BigDecimalAsNumberSerializer`, etc.

### Configuration
- [Initialization](configuration.md#initialization) - `fromConfig()` and `fromDataSource()`
- [DatabaseConfig Reference](configuration.md#databaseconfig-reference) - All configuration options
- [Flyway Migrations](configuration.md#flyway-migrations) - Automatic migrations and baselining
- [Core Type Initialization](configuration.md#core-type-initialization) - `dynamic_dto` setup
- [DynamicDto Strategy](configuration.md#dynamicdto-serialization-strategy) - Serialization options

## API Reference

For detailed API documentation, see the KDoc:
- [API Module KDoc](https://polskianonim.github.io/OctaviusDatabase/api)
- [Core Module KDoc](https://polskianonim.github.io/OctaviusDatabase/core)
