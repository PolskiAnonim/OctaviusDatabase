package org.octavius.database.type.utils

import org.octavius.data.type.DYNAMIC_DTO
import org.octavius.data.type.DynamicDto
import org.octavius.data.type.PgStandardType
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
    val oidCategoryMap = mutableMapOf<Int, TypeCategory>()
    val enumsByOid = mutableMapOf<Int, PgEnumDefinition>()
    val compositesByOid = mutableMapOf<Int, PgCompositeDefinition>()
    val arraysByOid = mutableMapOf<Int, PgArrayDefinition>()
    val classToPgNameMap = mutableMapOf<KClass<*>, String>()
    val pgNameToOidMap = mutableMapOf<String, Int>()

    var nextOid = 50000 // Custom OIDs start here

    // --- Helpery do rejestracji (symulują działanie Loadera) ---

    fun registerStandard(pgType: PgStandardType) {
        oidCategoryMap[pgType.oid] = TypeCategory.STANDARD
        pgNameToOidMap[pgType.typeName] = pgType.oid
    }

    fun registerArray(elementTypeName: String, elementOid: Int, arrayOid: Int? = null) {
        val arrayName = "_$elementTypeName"
        val finalArrayOid = arrayOid ?: nextOid++
        arraysByOid[finalArrayOid] = PgArrayDefinition(finalArrayOid, arrayName, elementOid)
        oidCategoryMap[finalArrayOid] = TypeCategory.ARRAY
        pgNameToOidMap[arrayName] = finalArrayOid
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

        val oid = nextOid++
        enumsByOid[oid] = PgEnumDefinition(
            oid = oid,
            typeName = typeName,
            valueToEnumMap = lookupMap,
            kClass = kClass
        )

        oidCategoryMap[oid] = TypeCategory.ENUM
        classToPgNameMap[kClass] = typeName
        pgNameToOidMap[typeName] = oid
    }

    fun registerComposite(
        typeName: String,
        kClass: KClass<*>,
        attributes: Map<String, Int>
    ) {
        val oid = nextOid++
        compositesByOid[oid] = PgCompositeDefinition(
            oid = oid,
            typeName = typeName,
            attributes = attributes,
            kClass = kClass
        )

        oidCategoryMap[oid] = if (typeName == DYNAMIC_DTO) TypeCategory.DYNAMIC else TypeCategory.COMPOSITE
        classToPgNameMap[kClass] = typeName
        pgNameToOidMap[typeName] = oid
    }

    // ==========================================
    // === REJESTRACJA DANYCH TESTOWYCH ===
    // ==========================================

    // 1. Typy Standardowe
    PgStandardType.entries.filter { !it.isArray }.forEach { registerStandard(it) }

    // 2. Tablice standardowe (używane w testach)
    PgStandardType.entries.filter { it.isArray }.forEach { pgType ->
        val baseName = pgType.typeName.substring(1)
        registerArray(baseName, pgType.oid, pgType.oid) // Standard array OID is already known
    }

    // 3. Enumy
    registerEnum("test_status", TestStatus::class)
    registerEnum("test_priority", TestPriority::class)
    registerEnum("test_category", TestCategory::class)

    // Tablice enumów
    registerArray("test_status", pgNameToOidMap["test_status"]!!)

    // 4. Kompozyty
    registerComposite("test_metadata", TestMetadata::class, mapOf(
        "created_at" to pgNameToOidMap["timestamp"]!!,
        "updated_at" to pgNameToOidMap["timestamp"]!!,
        "version" to pgNameToOidMap["int4"]!!,
        "tags" to pgNameToOidMap["_text"]!!
    ))

    registerComposite("test_person", TestPerson::class, mapOf(
        "name" to pgNameToOidMap["text"]!!,
        "age" to pgNameToOidMap["int4"]!!,
        "email" to pgNameToOidMap["text"]!!,
        "active" to pgNameToOidMap["bool"]!!,
        "roles" to pgNameToOidMap["_text"]!!
    ))

    registerComposite("test_task", TestTask::class, mapOf(
        "id" to pgNameToOidMap["int4"]!!,
        "title" to pgNameToOidMap["text"]!!,
        "description" to pgNameToOidMap["text"]!!,
        "status" to pgNameToOidMap["test_status"]!!,
        "priority" to pgNameToOidMap["test_priority"]!!,
        "category" to pgNameToOidMap["test_category"]!!,
        "assignee" to pgNameToOidMap["test_person"]!!,
        "metadata" to pgNameToOidMap["test_metadata"]!!,
        "subtasks" to pgNameToOidMap["_text"]!!,
        "estimated_hours" to pgNameToOidMap["numeric"]!!
    ))

    registerComposite("test_project", TestProject::class, mapOf(
        "name" to pgNameToOidMap["text"]!!,
        "description" to pgNameToOidMap["text"]!!,
        "status" to pgNameToOidMap["test_status"]!!,
        "team_members" to nextOid + 1, // Pre-calculate array OID (this is bit hacky)
        "tasks" to nextOid + 2,
        "metadata" to pgNameToOidMap["test_metadata"]!!,
        "budget" to pgNameToOidMap["numeric"]!!
    ))

    // 5. Tablice kompozytów
    registerArray("test_person", pgNameToOidMap["test_person"]!!)
    registerArray("test_task", pgNameToOidMap["test_task"]!!)
    registerArray("test_project", pgNameToOidMap["test_project"]!!)


    registerComposite("dynamic_dto", DynamicDto::class, mapOf(
        "type_name" to pgNameToOidMap["text"]!!,
        "data" to pgNameToOidMap["jsonb"]!!
    ))

    // Zwracamy gotowy obiekt
    return TypeRegistry(
        oidCategoryMap = oidCategoryMap,
        enumsByOid = enumsByOid,
        compositesByOid = compositesByOid,
        arraysByOid = arraysByOid,
        procedures = emptyMap(),
        classToPgNameMap = classToPgNameMap,
        dynamicSerializers = emptyMap(),
        classToDynamicNameMap = emptyMap(),
        pgNameToOidMap = pgNameToOidMap
    )
}