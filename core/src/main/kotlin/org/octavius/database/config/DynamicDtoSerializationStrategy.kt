package org.octavius.database.config

/**
 * Serialization strategy for classes annotated with @DynamicallyMappable.
 *
 * Note:
 *
 * Explicit use of a class with PgTyped forces treating the class as `@PgComposite/@PgEnum`.
 * Explicit use of DynamicDto forces treating the class as `@DynamicallyMappable`.
 *
 * Provided they have such annotations.
 */
enum class DynamicDtoSerializationStrategy {
    /**
     * NEVER automatically converts to dynamic_dto.
     * Usage requires explicit wrapping in `DynamicDto.from()`.
     * Maximum safety and explicitness.
     */
    EXPLICIT_ONLY,

    /**
     * Automatically converts to dynamic_dto, but ONLY when
     * there is no ambiguity (i.e., the class does NOT have @PgComposite or @PgEnum annotations).
     * In case of conflict, @PgComposite always wins.
     */
    AUTOMATIC_WHEN_UNAMBIGUOUS,

    /**
     * Aggressively converts to dynamic_dto if the class has
     * the @DynamicallyMappable annotation. This strategy has higher priority
     * than @PgComposite and @PgEnum in case of conflict.
     */
    PREFER_DYNAMIC_DTO
}
