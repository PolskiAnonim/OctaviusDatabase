package io.github.octaviusframework.db.api.model

/**
 * A multiplatform representation of a high-precision decimal number.
 *
 * This class is used to maintain precision when working with PostgreSQL's `numeric` type.
 * - On **JVM/Desktop**, it is a typealias for `java.math.BigDecimal`.
 * - On **JS**, it is a wrapper around a `String` to avoid floating-point inaccuracies.
 */
expect class BigDecimal
