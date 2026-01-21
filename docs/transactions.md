# Transactions

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
- [Transaction Propagation](#transaction-propagation)
- [Error Handling](#error-handling)

---

## Transaction Blocks

The simplest way to execute multiple operations atomically.

### Basic Usage

```kotlin
val result = dataAccess.transaction { tx ->
    // All operations use 'tx' context
    val userId = tx.insertInto("users")
        .values(listOf("name", "email"))
        .returning("id")
        .toField<Int>("name" to "John", "email" to "john@example.com")
        .getOrElse { return@transaction DataResult.Failure(it) }

    tx.insertInto("profiles")
        .values(listOf("user_id", "bio"))
        .execute("user_id" to userId, "bio" to "Hello!")
        .getOrElse { return@transaction DataResult.Failure(it) }

    DataResult.Success(userId)
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

// Step 1: Insert order, get ID
val orderIdHandle = plan.add(
    dataAccess.insertInto("orders")
        .values(listOf("user_id", "total"))
        .returning("id")
        .asStep()
        .toField<Int>("user_id" to userId, "total" to orderTotal)
)

// Step 2: Insert order items using the order ID
for (item in cartItems) {
    plan.add(
        dataAccess.insertInto("order_items")
            .values(listOf("order_id", "product_id", "quantity"))
            .asStep()
            .execute(
                "order_id" to orderIdHandle.field(),  // Reference to Step 1 result
                "product_id" to item.productId,
                "quantity" to item.quantity
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
val insertStep = dataAccess.insertInto("users")
    .values(userData)
    .returning("id")
    .asStep()           // Convert to step builder
    .toField<Int>(params)  // Terminal method creates TransactionStep

// Step returning rows
val selectStep = dataAccess.select("id", "name")
    .from("users")
    .where("active = true")
    .asStep()
    .toList()

// Step for modification only
val updateStep = dataAccess.update("users")
    .setValue("last_login")
    .where("id = :id")
    .asStep()
    .execute("last_login" to now, "id" to userId)
```

### Step Terminal Methods

| Method | Result Type | Use Case |
|--------|-------------|----------|
| `toField<T>(params)` | `T?` | Single value (e.g., inserted ID) |
| `toColumn<T>(params)` | `List<T?>` | All values from first column |
| `toSingle(params)` | `Map<String, Any?>?` | Single row as map |
| `toSingleOf<T>(params)` | `T?` | Single row as data class |
| `toList(params)` | `List<Map<String, Any?>>` | All rows as maps |
| `toListOf<T>(params)` | `List<T>` | All rows as data classes |
| `execute(params)` | `Int` | Affected row count |

---

## StepHandle API

When you add a step to a plan, you get a `StepHandle<T>` that can reference that step's result in subsequent steps.

### Methods

All methods have default parameter values where applicable (`rowIndex` defaults to `0`).

| Method | Returns | Description |
|--------|---------|-------------|
| `field(rowIndex = 0)` | `FromStep.Field` | Reference to scalar value (from `toField()` or `execute()`) |
| `field(columnName, rowIndex = 0)` | `FromStep.Field` | Reference to value in specific column (from `toList()`/`toSingle()`) |
| `column()` | `FromStep.Column` | Reference to entire column (from `toColumn()`) |
| `column(columnName)` | `FromStep.Column` | Reference to specific column (from `toList()`) |
| `row(rowIndex = 0)` | `FromStep.Row` | Reference to entire row as `Map<String, Any?>` |

### Usage Examples

```kotlin
val plan = TransactionPlan()

// Step returning single value (toField)
val idHandle = plan.add(
    dataAccess.insertInto("users")
        .values(listOf("name"))
        .returning("id")
        .asStep()
        .toField<Int>("name" to "John")
)

// Reference the scalar value
plan.add(
    dataAccess.insertInto("profiles")
        .values(listOf("user_id"))
        .asStep()
        .execute("user_id" to idHandle.field())  // Gets the ID
)

// Step returning rows (toList)
val rowsHandle = plan.add(
    dataAccess.select("id", "name", "email")
        .from("users")
        .where("active = true")
        .asStep()
        .toList()
)

// Reference specific column from first row
plan.add(
    dataAccess.rawQuery("SELECT notify_user(:email)")
        .asStep()
        .execute("email" to rowsHandle.field("email", rowIndex = 0))
)

// Step returning column (toColumn)
val idsHandle = plan.add(
    dataAccess.select("id")
        .from("users")
        .where("needs_update = true")
        .asStep()
        .toColumn<Int>()
)

// Reference entire column for batch operation
plan.add(
    dataAccess.update("users")
        .setExpression("updated_at", "NOW()")
        .where("id = ANY(:ids)")
        .asStep()
        .execute("ids" to idsHandle.column())
)
```

---

## TransactionValue

`TransactionValue` is a sealed class representing values in transaction step parameters.

### Variants

| Variant | Description |
|---------|-------------|
| `Value(value)` | Constant, predefined value |
| `FromStep.Field(handle, columnName?, rowIndex)` | Single value from a previous step |
| `FromStep.Column(handle, columnName?)` | List of values from a column |
| `FromStep.Row(handle, rowIndex)` | Entire row as `Map<String, Any?>` |
| `Transformed(source, transform)` | Transformed value from another TransactionValue |

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
val userIdHandle = plan.add(
    dataAccess.insertInto("users")
        .values(listOf("name"))
        .returning("id")
        .asStep()
        .toField<Int>("name" to userName)
)

// Use in next step
plan.add(
    dataAccess.insertInto("accounts")
        .values(listOf("user_id", "balance"))
        .asStep()
        .execute(
            "user_id" to userIdHandle.field(),  // Reference to the ID
            "balance" to 0
        )
)
```

### Multiple Values from Different Columns

```kotlin
val userHandle = plan.add(
    dataAccess.select("id", "email", "name")
        .from("users")
        .where("id = :id")
        .asStep()
        .toSingle("id" to userId)
)

plan.add(
    dataAccess.insertInto("notifications")
        .values(listOf("user_id", "recipient_email", "message"))
        .asStep()
        .execute(
            "user_id" to userHandle.field("id"),
            "recipient_email" to userHandle.field("email"),
            "message" to "Welcome!"
        )
)
```

### Entire Row (Row)

Use `row()` when you want to pass all columns from a previous step. The executor "spreads" the map into individual parameters:

```kotlin
// Fetch a row
val sourceHandle = plan.add(
    dataAccess.select("name", "email", "role")
        .from("users")
        .where("id = :id")
        .asStep()
        .toSingle("id" to templateUserId)
)

// Copy row to another table - parameters are spread from the row map
plan.add(
    dataAccess.insertInto("user_templates")
        .values(listOf("name", "email", "role"))
        .asStep()
        .execute(sourceHandle.row())  // Spreads {name: ..., email: ..., role: ...}
)
```

#### Row with Additional Parameters

You can combine `row()` with additional parameters:

```kotlin
plan.add(
    dataAccess.insertInto("archived_users")
        .values(listOf("name", "email", "role", "archived_at", "archived_by"))
        .asStep()
        .execute(
            sourceHandle.row(),           // Spreads name, email, role
            "archived_at" to Instant.now(),
            "archived_by" to currentUserId
        )
)
```

### Column as Array

Use `column()` for batch operations with `ANY()` or `UNNEST()`:

```kotlin
val productIdsHandle = plan.add(
    dataAccess.select("product_id")
        .from("cart_items")
        .where("cart_id = :cartId")
        .asStep()
        .toColumn<Int>("cartId" to cartId)
)

// Use in WHERE ... ANY()
plan.add(
    dataAccess.update("products")
        .setExpression("reserved", "reserved + 1")
        .where("id = ANY(:productIds)")
        .asStep()
        .execute("productIds" to productIdsHandle.column())
)
```

### Transforming Values

Transform values before passing to next step:

```kotlin
val nameHandle = plan.add(
    dataAccess.select("name")
        .from("users")
        .where("id = :id")
        .asStep()
        .toField<String>("id" to userId)
)

plan.add(
    dataAccess.insertInto("audit_log")
        .values(listOf("message"))
        .asStep()
        .execute(
            "message" to nameHandle.field().map { name -> "User $name logged in" }
        )
)
```

---

## TransactionPlanResult

After executing a plan, retrieve results using the step handles:

```kotlin
val plan = TransactionPlan()

val userIdHandle = plan.add(/* ... */)
val orderIdHandle = plan.add(/* ... */)

val result = dataAccess.executeTransactionPlan(plan)

result.onSuccess { planResult: TransactionPlanResult ->
    val userId: Int = planResult.get(userIdHandle)!!
    val orderId: Int = planResult.get(orderIdHandle)!!

    println("Created user $userId with order $orderId")
}
```

### Combining Plans

Merge multiple plans together:

```kotlin
val mainPlan = TransactionPlan()
val userIdHandle = mainPlan.add(/* create user */)

val ordersPlan = buildOrdersPlan(userIdHandle)  // Returns TransactionPlan
mainPlan.addPlan(ordersPlan)

dataAccess.executeTransactionPlan(mainPlan)
```

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

**Use case**: Audit logging that must succeed even if main operation fails:

```kotlin
dataAccess.transaction { tx ->
    val result = tx.update("accounts")
        .setExpression("balance", "balance - :amount")
        .where("id = :id")
        .execute("amount" to amount, "id" to accountId)

    // Log in separate transaction - persists even if main transaction fails
    dataAccess.transaction(TransactionPropagation.REQUIRES_NEW) { auditTx ->
        auditTx.insertInto("audit_log")
            .values(logData)
            .execute(logData)
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
    tx.insertInto("orders").values(orderData).execute(orderData)

    // Try optional operation with savepoint
    val optionalResult = dataAccess.transaction(TransactionPropagation.NESTED) { nestedTx ->
        nestedTx.insertInto("optional_feature")
            .values(featureData)
            .execute(featureData)
    }

    // Even if nested failed, order is still inserted
    // Outer transaction can continue

    DataResult.Success(Unit)
}
```

---

## Error Handling

### Transaction Block Errors

```kotlin
val result = dataAccess.transaction { tx ->
    val insertResult = tx.insertInto("users")
        .values(userData)
        .execute(userData)

    // Option 1: Early return on error
    insertResult.getOrElse { error ->
        return@transaction DataResult.Failure(error)
    }

    // Option 2: Use onFailure
    insertResult.onFailure { error ->
        // Log, cleanup, etc.
        return@transaction DataResult.Failure(error)
    }

    DataResult.Success(Unit)
}

// Handle final result
result.onSuccess { /* committed */ }
      .onFailure { error -> /* rolled back */ }
```

### Transaction Plan Errors

If any step fails, the entire plan is rolled back:

```kotlin
val result = dataAccess.executeTransactionPlan(plan)

result.onFailure { error ->
    when (error) {
        is TransactionStepExecutionException -> {
            println("Step ${error.stepIndex} failed: ${error.message}")
            // error.cause contains the original exception
        }
        is TransactionException -> {
            println("Transaction failed: ${error.message}")
        }
        else -> {
            println("Unknown error: ${error.message}")
        }
    }
}
```

### Step Dependency Errors

If a step references an invalid handle:

```kotlin
val planA = TransactionPlan()
val handleA = planA.add(/* ... */)

val planB = TransactionPlan()
planB.add(
    dataAccess.insertInto("table")
        .values(listOf("column"))
        .asStep()
        .execute("column" to handleA.field())  // Wrong! handleA is from planA
)

// This will throw StepDependencyException when executed
```