package org.octavius.database.type.utils

import org.octavius.data.type.DynamicDto
import org.octavius.data.type.PgStandardType
import org.octavius.data.type.QualifiedName
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
    val classToPgNameMap = mutableMapOf<KClass<*>, QualifiedName>()
    val pgNameToOidMap = mutableMapOf<QualifiedName, Int>()

    var nextOid = 50000 // Custom OIDs start here

    // --- Helpery do rejestracji (symulują działanie Loadera) ---

    fun registerStandard(pgType: PgStandardType) {
        val qualifiedName = QualifiedName("", pgType.typeName)
        oidCategoryMap[pgType.oid] = TypeCategory.STANDARD
        pgNameToOidMap[qualifiedName] = pgType.oid
    }

    fun registerArray(elementTypeName: String, elementOid: Int, arrayOid: Int? = null) {
        val isStandard = elementTypeName in PgStandardType.entries.map { it.typeName }
        val qualifiedName = if (isStandard) {
            QualifiedName("", elementTypeName, isArray = true)
        } else {
            QualifiedName("public", elementTypeName, isArray = true)
        }
        
        val finalArrayOid = arrayOid ?: nextOid++
        arraysByOid[finalArrayOid] = PgArrayDefinition(finalArrayOid, qualifiedName.toString(), elementOid)
        oidCategoryMap[finalArrayOid] = TypeCategory.ARRAY
        pgNameToOidMap[qualifiedName] = finalArrayOid
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
        val qualifiedName = QualifiedName("public", typeName)
        enumsByOid[oid] = PgEnumDefinition(
            oid = oid,
            typeName = qualifiedName.toString(),
            valueToEnumMap = lookupMap,
            kClass = kClass
        )

        oidCategoryMap[oid] = TypeCategory.ENUM
        classToPgNameMap[kClass] = qualifiedName
        pgNameToOidMap[qualifiedName] = oid
    }

    fun registerComposite(
        typeName: String,
        kClass: KClass<*>,
        attributes: Map<String, Int>
    ) {
        val oid = nextOid++
        val qualifiedName = QualifiedName("public", typeName)
        compositesByOid[oid] = PgCompositeDefinition(
            oid = oid,
            typeName = qualifiedName.toString(),
            attributes = attributes,
            kClass = kClass
        )

        oidCategoryMap[oid] = if (typeName == "dynamic_dto") TypeCategory.DYNAMIC else TypeCategory.COMPOSITE
        classToPgNameMap[kClass] = qualifiedName
        pgNameToOidMap[qualifiedName] = oid
    }

    // Helper for OID lookups in map
    fun oid(name: String, isArray: Boolean = false): Int {
        return pgNameToOidMap[QualifiedName("public", name, isArray)]
            ?: pgNameToOidMap[QualifiedName("", name, isArray)]
            ?: throw IllegalArgumentException("Type $name not registered")
    }

    // ==========================================
    // === REJESTRACJA DANYCH TESTOWYCH ===
    // ==========================================

    // 1. Typy Standardowe
    PgStandardType.entries.filter { !it.isArray }.forEach { registerStandard(it) }

    // 2. Tablice standardowe (używane w testach)
    PgStandardType.entries.filter { it.isArray }.forEach { pgType ->
        val baseName = pgType.typeName.removeSuffix("[]")
        val baseType = PgStandardType.entries.find { !it.isArray && it.typeName == baseName }
            ?: throw IllegalStateException("Base type not found for array: ${pgType.typeName}")
        
        registerArray(baseName, baseType.oid, pgType.oid) // Correctly pass baseType.oid as elementOid
    }

    // 3. Enumy
    registerEnum("test_status", TestStatus::class)
    registerEnum("test_priority", TestPriority::class)
    registerEnum("test_category", TestCategory::class)

    // Tablice enumów
    registerArray("test_status", oid("test_status"))

    // 4. Kompozyty
    registerComposite("test_metadata", TestMetadata::class, mapOf(
        "created_at" to oid("timestamp"),
        "updated_at" to oid("timestamp"),
        "version" to oid("int4"),
        "tags" to oid("text", true)
    ))

    registerComposite("test_person", TestPerson::class, mapOf(
        "name" to oid("text"),
        "age" to oid("int4"),
        "email" to oid("text"),
        "active" to oid("bool"),
        "roles" to oid("text", true)
    ))

    registerComposite("test_task", TestTask::class, mapOf(
        "id" to oid("int4"),
        "title" to oid("text"),
        "description" to oid("text"),
        "status" to oid("test_status"),
        "priority" to oid("test_priority"),
        "category" to oid("test_category"),
        "assignee" to oid("test_person"),
        "metadata" to oid("test_metadata"),
        "subtasks" to oid("text", true),
        "estimated_hours" to oid("numeric")
    ))

    registerComposite("test_project", TestProject::class, mapOf(
        "name" to oid("text"),
        "description" to oid("text"),
        "status" to oid("test_status"),
        "team_members" to nextOid + 1, // HACK: Array of test_person (next available OID)
        "tasks" to nextOid + 2,        // HACK: Array of test_task
        "metadata" to oid("test_metadata"),
        "budget" to oid("numeric")
    ))

    // 5. Tablice kompozytów
    registerArray("test_person", oid("test_person"))
    registerArray("test_task", oid("test_task"))
    registerArray("test_project", oid("test_project"))


    registerComposite("dynamic_dto", DynamicDto::class, mapOf(
        "type_name" to oid("text"),
        "data" to oid("jsonb")
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
        pgNameToOidMap = pgNameToOidMap,
        oidToNameMap = emptyMap(),
    )
}
