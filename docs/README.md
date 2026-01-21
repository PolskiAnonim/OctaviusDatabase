# Octavius Database Documentation

Detailed documentation for Octavius Database - an SQL-first data access layer for Kotlin & PostgreSQL.

## Guides

| Document | Description |
|----------|-------------|
| [Configuration](configuration.md) | Initialization, DatabaseConfig, Flyway, core types, DynamicDto strategy |
| [Query Builders](query-builders.md) | SELECT, INSERT, UPDATE, DELETE, raw queries, CTEs, subqueries, ON CONFLICT, async, streaming |
| [ORM-Like Patterns](orm-patterns.md) | toMap(), toDataObject(), auto-placeholders, CRUD patterns, @MapKey |
| [Transactions](transactions.md) | Transaction blocks, TransactionPlan, StepHandle, passing data between steps, propagation |
| [Error Handling](error-handling.md) | DataResult pattern, exception hierarchy, cause chain, best practices |
| [Type System](type-system.md) | @PgEnum, @PgComposite, @DynamicallyMappable, PgTyped, standard type mappings |

## Quick Links

### Query Building
- [Terminal Methods](query-builders.md#terminal-methods) - `toList()`, `toListOf()`, `toField()`, `execute()`
- [RawQueryBuilder](query-builders.md#rawquerybuilder) - Execute arbitrary SQL
- [CTE (WITH clauses)](query-builders.md#common-table-expressions-cte) - Common Table Expressions
- [ON CONFLICT (Upsert)](query-builders.md#on-conflict-upsert) - Insert or update on conflict
- [Async Execution](query-builders.md#async-execution) - Coroutine-based async queries
- [Streaming](query-builders.md#streaming) - Process large datasets without loading into memory

### ORM-Like Patterns
- [Key Advantages](orm-patterns.md#key-advantages) - Why this approach works
- [toMap() / toDataObject()](orm-patterns.md#object-map-conversion) - Object-Map conversion
- [Auto Placeholders](orm-patterns.md#auto-generated-placeholders) - `values()`, `setValues()` auto-generation
- [Real-World Example](orm-patterns.md#real-world-example) - Complete configuration manager
- [CRUD Patterns](orm-patterns.md#crud-patterns) - Repository patterns
- [@MapKey](orm-patterns.md#mapkey-annotation) - Custom property-to-column mapping

### Transactions
- [Transaction Blocks](transactions.md#transaction-blocks) - `dataAccess.transaction { }`
- [Transaction Plans](transactions.md#transaction-plans) - Declarative multi-step operations
- [StepHandle API](transactions.md#stephandle-api) - `field()`, `column()`, `row()`
- [Passing Data Between Steps](transactions.md#passing-data-between-steps) - Reference previous step results
- [Transaction Propagation](transactions.md#transaction-propagation) - REQUIRED, REQUIRES_NEW, NESTED

### Error Handling
- [DataResult](error-handling.md#dataresult) - Success/Failure pattern
- [Exception Nesting](error-handling.md#exception-nesting-cause-chain) - How exceptions wrap each other
- [QueryExecutionException](error-handling.md#queryexecutionexception) - SQL errors with full context
- [Best Practices](error-handling.md#patterns-and-best-practices) - Recommended patterns

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