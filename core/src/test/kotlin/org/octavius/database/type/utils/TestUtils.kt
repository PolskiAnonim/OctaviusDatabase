package org.octavius.database.type.utils

import org.octavius.data.type.DynamicDto
import org.octavius.data.util.CaseConvention
import org.octavius.data.util.CaseConverter
import org.octavius.database.type.registry.*
import org.octavius.domain.test.pgtype.*
import kotlin.reflect.KClass

/**
 * Tworzy w pełni funkcjonalną instancję TypeRegistry na potrzeby testów jednostkowych.
 */
internal fun createFakeTypeRegistry(): TypeRegistry {

    // --- Kontenery na dane ---
    val enums = mutableMapOf<String, PgEnumDefinition>()
    val composites = mutableMapOf<String, PgCompositeDefinition>()
    val arrays = mutableMapOf<String, PgArrayDefinition>()
    val categoryMap = mutableMapOf<String, TypeCategory>()
    val classToPgNameMap = mutableMapOf<KClass<*>, String>()

    // --- Helpery do rejestracji (symulują działanie Loadera) ---

    fun registerStandard(typeName: String) {
        categoryMap[typeName] = TypeCategory.STANDARD
    }

    fun registerArray(elementTypeName: String) {
        val arrayName = "_$elementTypeName"
        arrays[arrayName] = PgArrayDefinition(arrayName, elementTypeName)
        categoryMap[arrayName] = TypeCategory.ARRAY
    }

    fun <E : Enum<E>> registerEnum(
        typeName: String,
        kClass: KClass<E>,
        // Zakładamy typowe konwencje dla testów:
        pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_LOWER,
        ktConvention: CaseConvention = CaseConvention.PASCAL_CASE
    ) {
        val constants = kClass.java.enumConstants
            ?: throw IllegalArgumentException("$kClass is not an enum")

        // Symulacja pre-kalkulacji mapy (DB Value -> Enum Instance)
        val lookupMap = constants.associateBy { enumConst ->
            CaseConverter.convert(enumConst.name, ktConvention, pgConvention)
        }

        enums[typeName] = PgEnumDefinition(
            typeName = typeName,
            valueToEnumMap = lookupMap,
            kClass = kClass
        )

        categoryMap[typeName] = TypeCategory.ENUM
        classToPgNameMap[kClass] = typeName
    }

    fun registerComposite(
        typeName: String,
        kClass: KClass<*>,
        attributes: Map<String, String>
    ) {
        composites[typeName] = PgCompositeDefinition(
            typeName = typeName,
            attributes = attributes,
            kClass = kClass
        )

        categoryMap[typeName] = TypeCategory.COMPOSITE
        classToPgNameMap[kClass] = typeName
    }

    // ==========================================
    // === REJESTRACJA DANYCH TESTOWYCH ===
    // ==========================================

    // 1. Typy Standardowe
    val stdTypes = listOf("text", "int4", "bool", "timestamp", "timestamptz", "interval", "numeric", "jsonb", "uuid", "date")
    stdTypes.forEach { registerStandard(it) }

    // 2. Tablice standardowe (używane w testach)
    registerArray("text")
    registerArray("int4")

    // 3. Enumy
    registerEnum("test_status", TestStatus::class)
    registerEnum("test_priority", TestPriority::class)
    registerEnum("test_category", TestCategory::class)

    // Tablice enumów
    registerArray("test_status")

    // 4. Kompozyty
    registerComposite("test_metadata", TestMetadata::class, mapOf(
        "created_at" to "timestamp",
        "updated_at" to "timestamp",
        "version" to "int4",
        "tags" to "_text"
    ))

    registerComposite("test_person", TestPerson::class, mapOf(
        "name" to "text",
        "age" to "int4",
        "email" to "text",
        "active" to "bool",
        "roles" to "_text"
    ))

    registerComposite("test_task", TestTask::class, mapOf(
        "id" to "int4",
        "title" to "text",
        "description" to "text",
        "status" to "test_status",
        "priority" to "test_priority",
        "category" to "test_category",
        "assignee" to "test_person",
        "metadata" to "test_metadata",
        "subtasks" to "_text",
        "estimated_hours" to "numeric"
    ))

    registerComposite("test_project", TestProject::class, mapOf(
        "name" to "text",
        "description" to "text",
        "status" to "test_status",
        "team_members" to "_test_person", // Tablica kompozytów
        "tasks" to "_test_task",          // Tablica kompozytów
        "metadata" to "test_metadata",
        "budget" to "numeric"
    ))

    // 5. Tablice kompozytów (muszą być zarejestrowane po kompozytach, by definicje były spójne)
    registerArray("test_person")
    registerArray("test_task")
    registerArray("test_project")


    registerComposite("dynamic_dto", DynamicDto::class, mapOf(
        "type_name" to "text",
        "data" to "jsonb"
    ))

    // NADPISANIE KATEGORII:
    // Loader w prawdziwym kodzie ustawia tu TypeCategory.DYNAMIC zamiast COMPOSITE.
    // Musimy to zasymulować ręcznie
    categoryMap["dynamic_dto"] = TypeCategory.DYNAMIC

    // Zwracamy gotowy obiekt
    return TypeRegistry(
        categoryMap = categoryMap,
        enums = enums,
        composites = composites,
        arrays = arrays,
        classToPgNameMap = classToPgNameMap,
        dynamicSerializers = emptyMap(), // Pusta mapa dla dynamicznych w tym teście
        classToDynamicNameMap = emptyMap()
    )
}