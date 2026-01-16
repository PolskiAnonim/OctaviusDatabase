package org.octavius.data.transaction

/**
 * Defines transaction behavior when it is launched in the context of an already existing transaction.
 * Reflects key propagation strategies from Spring Framework.
 */
enum class TransactionPropagation {
    /**
     * Default behavior. If a transaction already exists, join it.
     * If not, create a new one.
     */
    REQUIRED,

    /**
     * Always create a new, independent transaction.
     * The existing transaction will be suspended during the execution of the new one.
     * Useful e.g., for saving audit logs that must succeed
     * even if the main operation is rolled back.
     */
    REQUIRES_NEW,

    /**
     * Execute in a nested transaction if a transaction already exists.
     * Uses the SAVEPOINT mechanism in the database. This allows independent
     * rollback of the nested transaction without affecting the outer transaction.
     * If a parent transaction does not exist, behaves like [REQUIRED].
     */
    NESTED
}