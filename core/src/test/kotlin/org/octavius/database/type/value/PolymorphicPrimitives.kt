package org.octavius.database.type.value

import kotlinx.serialization.Serializable
import org.octavius.data.annotation.DynamicallyMappable

/**
 * Zestaw klas testowych do weryfikacji, czy framework potrafi
 * obsługiwać polimorficzną listę `List<Any>`, w której znajdują się
 * zarówno standardowe `data class`, jak i `value class` opakowujące typy proste.
 */

// --- Wrappery 'value class' dla typów prostych ---

@Serializable
@DynamicallyMappable("int_wrapper")
@JvmInline
value class IntWrapper(val int: Int)

@Serializable
@DynamicallyMappable("string_wrapper")
@JvmInline
value class StringWrapper(val string: String)

@Serializable
@DynamicallyMappable("boolean_wrapper")
@JvmInline
value class BooleanWrapper(val boolean: Boolean)

// --- Standardowa 'data class' dla porównania ---

@Serializable
@DynamicallyMappable("user_action")
data class UserAction(val action: String, val timestamp: String)