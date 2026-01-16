package org.octavius.data.annotation

import org.octavius.data.util.CaseConvention

/**
 * Marks an `enum` class as a data type that can be mapped to an `ENUM` type
 * in PostgreSQL database.
 *
 * This annotation is crucial for `TypeRegistry`, which scans the classpath for
 * marked classes to automatically build mapping between Kotlin types
 * and `ENUM` types in PostgreSQL.
 *
 * **Naming convention:**
 * By default, the type name in PostgreSQL is derived from the simple class name
 * by converting from `CamelCase` to `snake_case` (e.g., `OrderStatus` class will be
 * mapped to `order_status` type). Enum values are by default mapped from PascalCase to
 * `SNAKE_CASE_UPPER` (e.g., `Pending` -> `PENDING`).
 *
 * **Explicit name specification:**
 * You can override the default type name by providing it in the [name] parameter.
 *
 * @param name Optional, explicit name of the corresponding type in PostgreSQL database.
 *             If left empty, the name will be generated automatically
 *             according to the `CamelCase` -> `snake_case` convention.
 * @param pgConvention Naming convention for enum values in Postgres
 * @param kotlinConvention Naming convention for enum values in Kotlin
 *
 * ### Examples
 * ```kotlin
 * // Example 1: Using default naming convention
 * // `OrderStatus` class will be mapped to `order_status` type in PostgreSQL.
 * // Values (Pending, Completed) will be mapped as 'PENDING', 'COMPLETED'.
 * @PgEnum
 * enum class OrderStatus { Pending, Completed }
 *
 * // Example 2: Explicit type name specification and different value convention
 * // `PaymentMethod` class will be mapped to `payment_method_enum` type.
 * // Values (CreditCard) will be mapped as 'credit_card'.
 * @PgEnum(name = "payment_method_enum", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
 * enum class PaymentMethod { CreditCard, BankTransfer }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgEnum(
    val name: String = "",
    // How values are stored in PostgreSQL database (e.g., 'PENDING', 'in_progress')
    val pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
    // How values are stored in Kotlin Enum class (e.g., Pending, IN_PROGRESS)
    val kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
)