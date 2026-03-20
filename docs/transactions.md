# Transactions

*The Roman Twelve Tables established that a contract, once entered into, must be honoured in full — or voided entirely. There is no partial compliance in Roman law. Transactions in Octavius follow the same principle: all steps succeed together, or none of them take effect.*

Octavius Database provides two approaches to transaction management:

1. **Transaction Blocks** - Imperative transactions using `transaction { }`
2. **Transaction Plans** - Declarative, multi-step operations with step dependencies

Both approaches support configurable propagation behavior.

## Table of Contents

- [Transaction Blocks](#transaction-blocks)
- [Transaction Plans](#transaction-plans)
- [StepHandle API](#stephandle-api)
- [TransactionValue](#transactionvalue)
- [Passing Data Between Steps](#passing-data-between-steps)
- [TransactionPlanResult](#transactionplanresult)
- [Null Handling in Transactions](#null-handling-in-transactions)
- [Transaction Propagation](#transaction-propagation)
- [Error Handling](#error-handling)

---

## Transaction Blocks

The simplest way to execute multiple operations atomically.

### Basic Usage

```kotlin
val result = dataAccess.transaction { tx ->
    // All operations use 'tx' context
    val citizenId = tx.insertInto("citizens")
        .values(listOf("name", "tribe"))
        .returning("id")
        .toField<Int>("name" to "Marcus Tullius", "tribe" to "Cornelia")
        .getOrElse { return@transaction DataResult.Failure(it) }

    tx.insertInto("citizen_profiles")
        .values(listOf("citizen_id", "biography"))
        .execute("citizen_id" to citizenId, "biography" to "Born in Arpinum.")
        .getOrElse { return@transaction DataResult.Failure(it) }

    DataResult.Success(citizenId)
}
```

### Behavior

- **Commit**: Transaction is committed when block returns `DataResult.Success`
- **Rollback**: Transaction is rolled back when:
  - Block returns `DataResult.Failure`
  - An exception is thrown inside the block

### With Propagation

```kotlin
dataAccess.transaction(propagation = TransactionPropagation.REQUIRES_NEW) { tx ->
    // Always runs in a new, independent transaction
    // ...
}
```

---

## Transaction Plans

Declarative approach for building multi-step transactions where steps can depend on results from previous steps.

### Why Transaction Plans?

Transaction plans are useful when:
- Steps need to reference results from previous steps (e.g., inserted ID)
- The number of operations is determined at runtime
- You want to build a transaction declaratively before executing

### Basic Usage

```kotlin
val plan = TransactionPlan()

// Step 1: Record the edict, get ID
val edictIdHandle = plan.add(
    dataAccess.insertInto("edicts")
        .values(listOf("issuer_id", "tribute_total"))
        .returning("id")
        .asStep()
        .toField<Int>("issuer_id" to consulId, "tribute_total" to tributeTotal)
)

// Step 2: Assign levy items using the edict ID
for (item in levyItems) {
    plan.add(
        dataAccess.insertInto("levy_items")
            .values(listOf("edict_id", "province_id", "amount"))
            .asStep()
            .execute(
                "edict_id" to edictIdHandle.field(),  // Reference to Step 1 result
                "province_id" to item.provinceId,
                "amount" to item.amount
            )
    )
}

// Execute all steps atomically
val result = dataAccess.executeTransactionPlan(plan)
```

### Creating Steps

Convert any query builder to a step using `.asStep()`:

```kotlin
// Step returning a single value
val insertStep = dataAccess.insertInto("citizens")
    .values(citizenData)
    .returning("id")
    .asStep()           // Convert to step builder
    .toField<Int>(params)  // Terminal method creates TransactionStep

// Step returning rows
val selectStep = dataAccess.select("id", "name")
    .from("legionnaires")
    .where("active = true")
    .asStep()
    .toList()

// Step for modification only
val updateStep = dataAccess.update("citizens")
    .setValue("last_census")
    .where("id = :id")
    .asStep()
    .execute("last_census" to now, "id" to citizenId)
```

### Step Terminal Methods

| Method                    | Result Type               | Use Case                             |
|---------------------------|---------------------------|--------------------------------------|
| `toField<T>(params)`      | `T`                       | Single value (e.g., inserted ID)     |
| `toFieldStrict<T>(params)`| `T`                       | Single value, always fails if no rows|
| `toColumn<T>(params)`     | `List<T>`                 | All values from first column         |
| `toSingle(params)`        | `Map<String, Any?>?`      | Single row as map                    |
| `toSingleStrict(params)`  | `Map<String, Any?>`       | Single row as map (fails if no rows) |
| `toSingleOf<T>(params)`   | `T`                       | Single row as data class             |
| `toList(params)`          | `List<Map<String, Any?>>` | All rows as maps                     |
| `toListOf<T>(params)`     | `List<T>`                 | All rows as data classes             |
| `execute(params)`         | `Int`                     | Affected row count                   |

---

## StepHandle API

When you add a step to a plan, you get a `StepHandle<T>` that can reference that step's result in subsequent steps.

### Methods

All methods have default parameter values where applicable (`rowIndex` defaults to `0`).

| Method                            | Returns           | Description                                                          |
|-----------------------------------|-------------------|----------------------------------------------------------------------|
| `field(rowIndex = 0)`             | `FromStep.Field`  | Reference to scalar value (from `toField()` or `execute()`)          |
| `field(columnName, rowIndex = 0)` | `FromStep.Field`  | Reference to value in specific column (from `toList()`/`toSingle()`) |
| `column()`                        | `FromStep.Column` | Reference to entire column (from `toColumn()`)                       |
| `column(columnName)`              | `FromStep.Column` | Reference to specific column (from `toList()`)                       |
| `row(rowIndex = 0)`               | `FromStep.Row`    | Reference to entire row as `Map<String, Any?>`                       |

### Usage Examples

```kotlin
val plan = TransactionPlan()

// Step returning single value (toField)
val citizenIdHandle = plan.add(
    dataAccess.insertInto("citizens")
        .values(listOf("name"))
        .returning("id")
        .asStep()
        .toField<Int>("name" to "Gaius Julius")
)

// Reference the scalar value
plan.add(
    dataAccess.insertInto("citizen_profiles")
        .values(listOf("citizen_id"))
        .asStep()
        .execute("citizen_id" to citizenIdHandle.field())  // Gets the ID
)

// Step returning rows (toList)
val rowsHandle = plan.add(
    dataAccess.select("id", "name", "tribe")
        .from("citizens")
        .where("active = true")
        .asStep()
        .toList()
)

// Reference specific column from first row
plan.add(
    dataAccess.rawQuery("SELECT notify_censor(:tribe)")
        .asStep()
        .execute("tribe" to rowsHandle.field("tribe", rowIndex = 0))
)

// Step returning column (toColumn)
val idsHandle = plan.add(
    dataAccess.select("id")
        .from("citizens")
        .where("needs_census = true")
        .asStep()
        .toColumn<Int>()
)

// Reference entire column for batch operation
plan.add(
    dataAccess.update("citizens")
        .setExpression("last_census_at", "NOW()")
        .where("id = ANY(:ids)")
        .asStep()
        .execute("ids" to idsHandle.column())
)
```

---

## TransactionValue

`TransactionValue` is a sealed class representing values in transaction step parameters.

### Variants

| Variant                                         | Description                                     |
|-------------------------------------------------|-------------------------------------------------|
| `Value(value)`                                  | Constant, predefined value                      |
| `FromStep.Field(handle, columnName?, rowIndex)` | Single value from a previous step               |
| `FromStep.Column(handle, columnName?)`          | List of values from a column                    |
| `FromStep.Row(handle, rowIndex)`                | Entire row as `Map<String, Any?>`               |
| `Transformed(source, transform)`                | Transformed value from another TransactionValue |

### Extension Functions

```kotlin
// Convert any value to TransactionValue.Value
val idRef = 123.toTransactionValue()
val nullRef = null.toTransactionValue()

// Transform a TransactionValue
val upperName = someHandle.field("name").map { (it as String).uppercase() }
```

---

## Passing Data Between Steps

### Single Value (Field)

Most common case - passing an ID from INSERT to subsequent operations:

```kotlin
val citizenIdHandle = plan.add(
    dataAccess.insertInto("citizens")
        .values(listOf("name"))
        .returning("id")
        .asStep()
        .toField<Int>("name" to citizenName)
)

// Use in next step
plan.add(
    dataAccess.insertInto("citizen_profiles")
        .values(listOf("citizen_id", "biography"))
        .asStep()
        .execute(
            "citizen_id" to citizenIdHandle.field(),  // Reference to the ID
            "biography" to "Enrolled in the year of consul Gaius."
        )
)
```

### Multiple Values from Different Columns

```kotlin
val citizenHandle = plan.add(
    dataAccess.select("id", "name", "tribe")
        .from("citizens")
        .where("id = :id")
        .asStep()
        .toSingle("id" to citizenId)
)

plan.add(
    dataAccess.insertInto("census_notifications")
        .values(listOf("citizen_id", "tribe", "message"))
        .asStep()
        .execute(
            "citizen_id" to citizenHandle.field("id"),
            "tribe" to citizenHandle.field("tribe"),
            "message" to "Census due."
        )
)
```

### Entire Row (Row)

Use `row()` when you want to pass all columns from a previous step. The executor "spreads" the map into individual parameters:

```kotlin
// Fetch a row
val sourceHandle = plan.add(
    dataAccess.select("name", "tribe", "rank")
        .from("citizens")
        .where("id = :id")
        .asStep()
        .toSingle("id" to templateCitizenId)
)

// Copy row to another table - parameters are spread from the row map
plan.add(
    dataAccess.insertInto("citizen_templates")
        .values(listOf("name", "tribe", "rank"))
        .asStep()
        .execute(sourceHandle.row())  // Spreads {name: ..., tribe: ..., rank: ...}
)
```

#### Row with Additional Parameters

You can combine `row()` with additional parameters:

```kotlin
plan.add(
    dataAccess.insertInto("archived_citizens")
        .values(listOf("name", "tribe", "rank", "archived_at", "archived_by"))
        .asStep()
        .execute(
            "row" to sourceHandle.row(),          // Spreads name, tribe, rank - row disappears
            "archived_at" to Instant.now(),
            "archived_by" to currentCensorId
        )
)
```

### Column as Array

Use `column()` for batch operations with `ANY()` or `UNNEST()`:

```kotlin
val conscriptIdsHandle = plan.add(
    dataAccess.select("citizen_id")
        .from("conscription_queue")
        .where("legion_id = :legionId")
        .asStep()
        .toColumn<Int>("legionId" to legionId)
)

// Use in WHERE ... ANY()
plan.add(
    dataAccess.update("citizens")
        .setExpression("status", "'enlisted'")
        .where("id = ANY(:citizenIds)")
        .asStep()
        .execute("citizenIds" to conscriptIdsHandle.column())
)
```

### Transforming Values

Transform values before passing to next step:

```kotlin
val nameHandle = plan.add(
    dataAccess.select("name")
        .from("citizens")
        .where("id = :id")
        .asStep()
        .toField<String>("id" to citizenId)
)

plan.add(
    dataAccess.insertInto("senate_audit")
        .values(listOf("message"))
        .asStep()
        .execute(
            "message" to nameHandle.field().map { name -> "Citizen $name enrolled in the census" }
        )
)
```

---

## TransactionPlanResult

After executing a plan, retrieve results using the step handles:

```kotlin
val plan = TransactionPlan()

val citizenIdHandle = plan.add(/* ... */)
val edictIdHandle = plan.add(/* ... */)

val result = dataAccess.executeTransactionPlan(plan)

result.onSuccess { planResult: TransactionPlanResult ->
    val citizenId: Int = planResult.get(citizenIdHandle)
    val edictId: Int = planResult.get(edictIdHandle)

    println("Enrolled citizen $citizenId under edict $edictId")
}
```

### Combining Plans

Merge multiple plans together:

```kotlin
val mainPlan = TransactionPlan()
val citizenIdHandle = mainPlan.add(/* enroll citizen */)

val levyPlan = buildLevyPlan(citizenIdHandle)  // Returns TransactionPlan
mainPlan.addPlan(levyPlan)

dataAccess.executeTransactionPlan(mainPlan)
```

---

## Null Handling in Transactions

Nullability in terminal methods is controlled by the type parameter. Use non-nullable types when you expect a result, or nullable types when the result may be absent.

### In Transaction Blocks

```kotlin
val result = dataAccess.transaction { tx ->
    // Non-nullable — Failure if citizen not found
    val citizen = tx.select("*")
        .from("citizens")
        .where("id = :id")
        .toSingleOf<Citizen>("id" to citizenId)
        .getOrElse { return@transaction DataResult.Failure(it) }

    // citizen is guaranteed non-null here
    DataResult.Success(citizen)
}
```

### With Transaction Plans

When using `TransactionPlanResult.get()`, the returned value is typed based on the step's terminal method:

```kotlin
val plan = TransactionPlan()

// Non-nullable — step fails if no rows
val citizenHandle = plan.add(
    dataAccess.select("*")
        .from("citizens")
        .where("id = :id")
        .asStep()
        .toSingleOf<Citizen>("id" to citizenId)
)

// Nullable — step succeeds with null if no rows
val maybeCitizenHandle = plan.add(
    dataAccess.select("*")
        .from("citizens")
        .where("id = :id")
        .asStep()
        .toSingleOf<Citizen?>("id" to citizenId)
)

val result = dataAccess.executeTransactionPlan(plan)

result.onSuccess { planResult ->
    val citizen: Citizen = planResult.get(citizenHandle)             // guaranteed non-null
    val maybeCitizen: Citizen? = planResult.get(maybeCitizenHandle)  // may be null
}
```

See [Executing Queries - Null Handling](executing-queries.md#null-handling-via-type-parameter) for more details.

---

## Transaction Propagation

Control how transactions behave when nested:

### TransactionPropagation.REQUIRED (default)

- If a transaction exists, join it
- If not, create a new one

```kotlin
dataAccess.transaction { tx ->
    // Outer transaction

    dataAccess.transaction(TransactionPropagation.REQUIRED) { innerTx ->
        // Joins the outer transaction
        // Rollback here rolls back everything
    }
}
```

### TransactionPropagation.REQUIRES_NEW

- Always create a new, independent transaction
- Outer transaction is suspended during execution

```kotlin
dataAccess.transaction { tx ->
    // Main transaction

    dataAccess.transaction(TransactionPropagation.REQUIRES_NEW) { innerTx ->
        // Independent transaction
        // Commit/rollback here doesn't affect outer transaction
    }

    // Continue with outer transaction
}
```

**Use case**: Senate audit logging that must succeed even if the main operation fails:

```kotlin
dataAccess.transaction { tx ->
    val result = tx.update("aerarium")
        .setExpression("balance", "balance - :amount")
        .where("province = :province")
        .execute("amount" to amount, "province" to province)

    // Log in separate transaction — persists even if main transaction fails
    dataAccess.transaction(TransactionPropagation.REQUIRES_NEW) { auditTx ->
        auditTx.insertInto("aerarium_audit")
            .values(auditData)
            .execute(auditData)
    }

    result
}
```

### TransactionPropagation.NESTED

- Create a nested transaction using SAVEPOINT
- Can rollback independently without affecting outer transaction
- If no outer transaction exists, behaves like REQUIRED

```kotlin
dataAccess.transaction { tx ->
    tx.insertInto("campaigns").values(campaignData).execute(campaignData)

    // Try optional operation with savepoint
    val optionalResult = dataAccess.transaction(TransactionPropagation.NESTED) { nestedTx ->
        nestedTx.insertInto("campaign_insignia")
            .values(insigniaData)
            .execute(insigniaData)
    }

    // Even if nested failed, campaign is still inserted
    DataResult.Success(Unit)
}
```

---

## Error Handling

### Database Errors

If any step in a transaction fails, the entire transaction is rolled back. The error returned is a `DatabaseException` (e.g., `ConstraintViolationException`, `StatementException`) enriched with the `transactionStepIndex`.

```kotlin
val result = dataAccess.executeTransactionPlan(plan)

result.onFailure { error ->
    val stepIndex = error.queryContext?.transactionStepIndex
    println("Transaction failed at step $stepIndex")
    println("Error type: ${error::class.simpleName}")
    println("Details: ${error.message}")
}
```

### Step Dependency Errors

If a step references a previous step's result incorrectly, a `StepDependencyException` is returned:

```kotlin
result.onFailure { error ->
    if (error is StepDependencyException) {
        println("Dependency error: ${error.messageEnum}")
        println("Referenced step: ${error.referencedStepIndex}")
    }
}
```

---

## See Also

- [Executing Queries](executing-queries.md) - DataResult patterns and usage
- [Error Handling](error-handling.md) - Exception hierarchy and debugging
- [Query Builders](query-builders.md) - How to build queries
