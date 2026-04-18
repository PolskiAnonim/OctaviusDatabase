# Octavius Database Documentation

*Veni, vidi, quaesivi — I came, I saw, I queried. These pages are the tabularium of Octavius Database: the official record of every feature, every pattern, and every configuration option. Consult them as you would consult the XII Tables — with the confidence that what is written here is law.*

Detailed documentation for Octavius Database - an SQL-first data access layer for Kotlin & PostgreSQL.

## Guides

| Document                                              | Description                                                                                             |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| [Configuration](configuration.md)                     | Initialization, DatabaseConfig, Flyway, core types, DynamicDto strategy                                 |
| [Lifecycle & Shutdown](lifecycle-and-shutdown.md)     | Proper cleanup, .use {} block, common integration patterns                                              |
| [Query Builders](query-builders.md)                   | SELECT, INSERT, UPDATE, DELETE, raw queries, CTEs, subqueries, ON CONFLICT                              |
| [Functions & Procedures](functions-and-procedures.md) | Calling functions and procedures                                                                        |
| [Executing Queries](executing-queries.md)             | Terminal methods, DataResult, getOrThrow, async execution, streaming                                    |
| [Parameter Handling](parameter-handling.md)           | Named parameters (@), JSONB operator escaping (?), collections & flattening, unnest and bulk operations |
| [Data Mapping](data-mapping.md)                       | toDataMap(), toDataObject(), @MapKey - converting between objects and maps                              |
| [ORM-Like Patterns](orm-patterns.md)                  | CRUD patterns, real-world examples, PostgreSQL composite types                                          |
| [Transactions](transactions.md)                       | Transaction blocks, TransactionPlan, StepHandle, passing data between steps                             |
| [Notifications](notifications.md)                     | PostgreSQL LISTEN/NOTIFY, PgChannelListener, Flow-based receiving                                       |
| [Error Handling](error-handling.md)                   | Fatal errors vs. DataResult, Exception hierarchy, Statement/Constraint errors                           |
| [Type System](type-system.md)                         | @PgEnum, @PgComposite, @DynamicallyMappable, dynamic data insertion, standard type mappings             |

## Quick Links

### Query Building
- [SelectQueryBuilder](query-builders.md#selectquerybuilder) - SELECT queries with JOINs, pagination
- [InsertQueryBuilder](query-builders.md#insertquerybuilder) - INSERT with RETURNING
- [RawQueryBuilder](query-builders.md#rawquerybuilder) - Execute arbitrary SQL
- [QueryFragment & Dynamic Queries](query-builders.md#queryfragment--dynamic-queries) - Composable SQL fragments
- [Common Table Expressions (CTE)](query-builders.md#common-table-expressions-cte) - WITH clauses and recursion
- [Subqueries](query-builders.md#subqueries) - Using subqueries in SELECT/FROM/WHERE
- [ON CONFLICT (Upsert)](query-builders.md#on-conflict-upsert) - Insert or update on conflict
- [Row-Level Locking](query-builders.md#row-level-locking-for-update) - FOR UPDATE, NOWAIT, SKIP LOCKED
- [Auto Placeholders](query-builders.md#auto-generated-placeholders) - `values()`, `setValues()` auto-generation

### Parameter Handling
- [Named Parameters Syntax](parameter-handling.md#named-parameters-syntax) - Why `@` is used instead of `:`
- [JSONB Operators & Question Marks](parameter-handling.md#what-about-the-operator) - Automatic escaping of `?` for JSONB
- [Expansion & Conversion](parameter-handling.md#parameter-expansion--conversion) - How Kotlin values become SQL parameters
- [Type Inference & Safety](parameter-handling.md#type-inference--safety) - Default resolution and `PgTyped` casts
- [Collections & Flattening](parameter-handling.md#collections--parameter-flattening) - Handling lists, arrays, and composites
- [Bulk Operations (unnest)](parameter-handling.md#high-performance-bulk-operations-unnest) - Fastest way to insert/update large datasets

### Functions and Procedures
- [Functions](functions-and-procedures.md#functions-create-function) - Calling functions via SELECT
- [Procedures](functions-and-procedures.md#procedures-create-procedure) - Calling procedures via CALL
- [Comparison](functions-and-procedures.md#comparison-functions-vs-procedures) - Functions vs Procedures comparison

### Executing Queries
- [Terminal Methods](executing-queries.md#terminal-methods) - `toList()`, `toListOf()`, `toField()`, `execute()`
- [DataResult](executing-queries.md#dataresult) - Success/Failure result pattern
- [Async Execution](executing-queries.md#async-execution) - Coroutine-based async queries
- [Streaming](executing-queries.md#streaming) - Process large datasets via `asStream`

### Data Mapping
- [toDataObject()](data-mapping.md#todataobject---map-to-data-class) - Map rows to data classes
- [toDataMap()](data-mapping.md#todatamap---data-class-to-map) - Data class to snake_case map
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
- [Manual Composite Mapping](type-system.md#manual-composite-mapping-pgcompositemapper) - Manual mapping of composite types
- [@DynamicallyMappable](type-system.md#dynamicallymappable) - Polymorphic storage with `dynamic_dto`
- [Inserting Dynamic Data](type-system.md#inserting-dynamic-data) - How to persist dynamic_dto and polymorphic lists
- [Helper Serializers](type-system.md#helper-serializers) - `BigDecimalAsNumberSerializer`, etc.

### Configuration
- [Initialization](configuration.md#initialization) - `fromConfig()` and `fromDataSource()`
- [DatabaseConfig Reference](configuration.md#databaseconfig-reference) - All configuration options
- [Properties File Reference](configuration.md#properties-file) - All property keys (including HikariCP)
- [Flyway Migrations](configuration.md#flyway-migrations) - Automatic migrations and baselining
- [Core Type Initialization](configuration.md#core-type-initialization) - `dynamic_dto` setup
- [DynamicDto Strategy](configuration.md#dynamicdto-serialization-strategy) - Serialization options
- [Connection Pool](configuration.md#connection-pool) - HikariCP customization

### Lifecycle & Shutdown
- [AutoCloseable](lifecycle-and-shutdown.md#standard-usage) - Using `.use { }` for automatic cleanup
- [Integration Patterns](lifecycle-and-shutdown.md#integration-patterns) - Ktor, Spring, and Koin integration
- [DataSource Management](lifecycle-and-shutdown.md#behavior-with-existing-datasource) - External vs internal pool management

## API Reference

For detailed API documentation, see the KDoc:
- [API Module KDoc](https://octavius-framework.github.io/octavius-database/api)
- [Core Module KDoc](https://octavius-framework.github.io/octavius-database/core)
