# Octavius Database Documentation

Detailed documentation for Octavius Database - an SQL-first data access layer for Kotlin & PostgreSQL.

## Guides

| Document | Description |
|----------|-------------|
| [Configuration](configuration.md) | Initialization, DatabaseConfig, Flyway, core types, DynamicDto strategy |
| [Query Builders](query-builders.md) | SELECT, INSERT, UPDATE, DELETE, raw queries, CTEs, subqueries, ON CONFLICT |
| [Executing Queries](executing-queries.md) | Terminal methods, DataResult, assertNotNull, async execution, streaming |
| [Data Mapping](data-mapping.md) | toMap(), toDataObject(), @MapKey - converting between objects and maps |
| [ORM-Like Patterns](orm-patterns.md) | CRUD patterns, real-world examples, PostgreSQL composite types |
| [Transactions](transactions.md) | Transaction blocks, TransactionPlan, StepHandle, passing data between steps |
| [Error Handling](error-handling.md) | Exception hierarchy, QueryExecutionException, ConversionException |
| [Type System](type-system.md) | @PgEnum, @PgComposite, @DynamicallyMappable, PgTyped, standard type mappings |

## Quick Links

### Query Building
- [SelectQueryBuilder](query-builders.md#selectquerybuilder) - SELECT queries with JOINs, pagination
- [InsertQueryBuilder](query-builders.md#insertquerybuilder) - INSERT with RETURNING
- [RawQueryBuilder](query-builders.md#rawquerybuilder) - Execute arbitrary SQL
- [CTE (WITH clauses)](query-builders.md#common-table-expressions-cte) - Common Table Expressions
- [ON CONFLICT (Upsert)](query-builders.md#on-conflict-upsert) - Insert or update on conflict
- [Auto Placeholders](query-builders.md#auto-generated-placeholders) - `values()`, `setValues()` auto-generation

### Executing Queries
- [Terminal Methods](executing-queries.md#terminal-methods) - `toList()`, `toListOf()`, `toField()`, `execute()`
- [DataResult](executing-queries.md#dataresult) - Success/Failure pattern
- [assertNotNull](executing-queries.md#assertnotnull) - Handle nullable results
- [Async Execution](executing-queries.md#async-execution) - Coroutine-based async queries
- [Streaming](executing-queries.md#streaming) - Process large datasets without loading into memory

### Data Mapping
- [toDataObject()](data-mapping.md#todataobject---map-to-data-class) - Map to data class
- [Nested Structures & Strict Type Checking](data-mapping.md#nested-structures--strict-type-checking) - How nested types are handled
- [toMap()](data-mapping.md#tomap---data-class-to-map) - Data class to map
- [@MapKey](data-mapping.md#mapkey-annotation) - Custom property-to-column mapping

### ORM-Like Patterns
- [Key Advantages](orm-patterns.md#key-advantages) - Why this approach works
- [CRUD Patterns](orm-patterns.md#crud-patterns) - Repository patterns
- [Real-World Example](orm-patterns.md#real-world-example) - Complete configuration manager

### Transactions
- [Transaction Blocks](transactions.md#transaction-blocks) - `dataAccess.transaction { }`
- [Transaction Plans](transactions.md#transaction-plans) - Declarative multi-step operations
- [StepHandle API](transactions.md#stephandle-api) - `field()`, `column()`, `row()`
- [Passing Data Between Steps](transactions.md#passing-data-between-steps) - Reference previous step results
- [assertNotNull in Transactions](transactions.md#assertnotnull-in-transactions) - Handle nullable results
- [Transaction Propagation](transactions.md#transaction-propagation) - REQUIRED, REQUIRES_NEW, NESTED

### Error Handling
- [Exception Hierarchy](error-handling.md#exception-hierarchy) - DatabaseException subtypes
- [QueryExecutionException](error-handling.md#queryexecutionexception) - SQL errors with full context
- [ConversionException](error-handling.md#conversionexception) - Type mapping errors
- [Logging and Debugging](error-handling.md#logging-and-debugging) - Using toString() for diagnostics

### Type System
- [Standard Type Mapping](type-system.md#standard-type-mapping) - PostgreSQL ↔ Kotlin conversions
- [@PgEnum](type-system.md#pgenum) - Map Kotlin enums to PostgreSQL ENUMs
- [@PgComposite](type-system.md#pgcomposite) - Map data classes to COMPOSITE types
- [@DynamicallyMappable](type-system.md#dynamicallymappable) - Polymorphic storage with dynamic_dto
- [PgTyped](type-system.md#pgtyped---explicit-type-casts) - Explicit type casts, type resolution priority
- [Helper Serializers](type-system.md#helper-serializers) - `BigDecimalAsNumberSerializer`, `DynamicDtoEnumSerializer`

### Configuration
- [Initialization](configuration.md#initialization) - `fromConfig()` and `fromDataSource()`
- [DatabaseConfig Reference](configuration.md#databaseconfig-reference) - All configuration options
- [Flyway Migrations](configuration.md#flyway-migrations) - Enable/disable, baseline existing schemas
- [Core Type Initialization](configuration.md#core-type-initialization) - `dynamic_dto` setup
- [DynamicDto Strategy](configuration.md#dynamicdto-serialization-strategy) - Serialization options

## API Reference

For detailed API documentation, see the KDoc:
- [API Module KDoc](https://polskianonim.github.io/OctaviusDatabase/api)
- [Core Module KDoc](https://polskianonim.github.io/OctaviusDatabase/core)