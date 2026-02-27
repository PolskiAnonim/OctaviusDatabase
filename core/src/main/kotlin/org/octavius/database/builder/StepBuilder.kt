package org.octavius.database.builder

import org.octavius.data.builder.StepBuilderMethods
import org.octavius.data.transaction.TransactionStep
import kotlin.reflect.KType

/**
 * Wrapper that provides the same terminal methods as AbstractQueryBuilder,
 * but instead of executing queries, creates TransactionStep for lazy execution within transactions.
 */
internal class StepBuilder(private val builder: AbstractQueryBuilder<*>) : StepBuilderMethods {

    /** Creates TransactionStep with toList method */
    override fun toList(params: Map<String, Any?>): TransactionStep<List<Map<String, Any?>>> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toList(p) },
            params = params
        )
    }

    /** Creates TransactionStep with toSingle method */
    override fun toSingle(params: Map<String, Any?>): TransactionStep<Map<String, Any?>?> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toSingle(p) },
            params = params
        )
    }

    /** Creates TransactionStep with toSingleNotNull method */
    override fun toSingleNotNull(params: Map<String, Any?>): TransactionStep<Map<String, Any?>> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toSingleNotNull(p) },
            params = params
        )
    }

    /** Creates TransactionStep with toListOf method */
    override fun <T> toListOf(kType: KType, params: Map<String, Any?>): TransactionStep<List<T>> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toListOf(kType, p) },
            params = params
        )
    }

    /** Creates TransactionStep with toSingleOf method */
    override fun <T> toSingleOf(kType: KType, params: Map<String, Any?>): TransactionStep<T> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toSingleOf(kType, p) },
            params = params
        )
    }

    /** Creates TransactionStep with toField method */
    override fun <T> toField(kType: KType, params: Map<String, Any?>): TransactionStep<T> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toField(kType, p) },
            params = params
        )
    }

    /** Creates TransactionStep with toColumn method */
    override fun <T> toColumn(kType: KType, params: Map<String, Any?>): TransactionStep<List<T>> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).toColumn(kType, p) },
            params = params
        )
    }

    /** Creates TransactionStep with execute method */
    override fun execute(params: Map<String, Any?>): TransactionStep<Int> {
        return TransactionStep(
            builder = this.builder,
            executionLogic = { b, p -> (b as AbstractQueryBuilder<*>).execute(p) },
            params = params
        )
    }
}
