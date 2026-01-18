package org.octavius.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Manages the initialization of core PostgreSQL types and functions required by the Octavius framework.
 *
 * This component runs immediately after connection establishment and before any Flyway migrations,
 * ensuring that the database infrastructure is ready for the framework to operate.
 *
 * ## The `dynamic_dto` Type
 *
 * The central piece of infrastructure is the `dynamic_dto` composite type.
 * It enables polymorphic data handling within PostgreSQL.
 *
 * **Structure:**
 * - `type_name` (text): A discriminator key (e.g., "profile_dto") mapped to a specific Kotlin class.
 * - `data_payload` (jsonb): The actual data serialized as JSON.
 *
 * **Purpose:**
 * Serves as a universal container for transmitting dynamic data structures where the type
 * is determined at runtime rather than by the database schema. It is ideal for:
 * - Aggregating different data types in a single column (polymorphic arrays).
 * - Ad-hoc object mapping in queries (e.g., using `JOIN LATERAL`).
 *
 * **Note:**
 * For static, well-defined data structures, a dedicated PostgreSQL `COMPOSITE TYPE` is preferred.
 */
internal object CoreTypeInitializer {

    private val logger = KotlinLogging.logger {}

    private const val CORE_INIT_SQL = """
        DO $$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dynamic_dto') THEN
                CREATE TYPE dynamic_dto AS (
                    type_name    text,
                    data_payload jsonb
                );
            END IF;
        END$$;

        CREATE OR REPLACE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
            RETURNS dynamic_dto AS
        $$
        BEGIN
            RETURN ROW (p_type_name, p_data)::dynamic_dto;
        END;
        $$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

        CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value ANYELEMENT)
            RETURNS dynamic_dto AS
        $$
        BEGIN
            RETURN ROW (p_type_name, to_jsonb(p_value))::dynamic_dto;
        END;
        $$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

        CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value TEXT)
            RETURNS dynamic_dto AS
        $$
        BEGIN
            RETURN ROW (p_type_name, to_jsonb(p_value))::dynamic_dto;
        END;
        $$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

        CREATE OR REPLACE FUNCTION unwrap_dto_payload(p_dto dynamic_dto)
            RETURNS JSONB AS
        $$
        BEGIN
            RETURN p_dto.data_payload;
        END;
        $$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;
    """

    /**
     * Ensures all required core types and functions exist in the database.
     * Uses `IF NOT EXISTS` / `CREATE OR REPLACE` to be idempotent and safe to run on every startup.
     */
    fun ensureRequiredTypes(jdbcTemplate: JdbcTemplate) {
        logger.debug { "Ensuring core Octavius schema elements exist..." }
        try {
            jdbcTemplate.execute(CORE_INIT_SQL)
            logger.debug { "Core schema elements verified." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize Octavius core schema! dynamic_dto might be missing." }
            throw e
        }
    }
}