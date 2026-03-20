# ORM-Like Patterns

*Rome was not built in a day, but its census was updated every five years without fail. The censors - Octavius Database's equivalent being these repository patterns — provided the machinery for keeping records of citizens, properties, and legions in perfect order, without the curia having to intervene in the details of every inscription.*

Octavius is an Anti-ORM by design, but it provides utilities that enable convenient ORM-like patterns when you want them. This guide shows practical patterns for CRUD operations using [Data Mapping](data-mapping.md) utilities.

## Table of Contents

- [Key Advantages](#key-advantages)
- [CRUD Patterns](#crud-patterns)
- [Real-World Example](#real-world-example)
- [When to Use These Patterns](#when-to-use-these-patterns)

> **Prerequisites**: This guide assumes familiarity with [Data Mapping](data-mapping.md) (`toMap()`, `toDataObject()`, `@MapKey`) and [Query Builders](query-builders.md) (auto-placeholders).

---

## Key Advantages

### Data Classes Can Have Anything

Unlike traditional ORMs that require specific base classes or annotations everywhere, Octavius works with **any data class**. Your entities can have:

- **PostgreSQL ENUMs** (`@PgEnum`) mapped automatically
- **Composite types** (`@PgComposite`) including nested structures
- **Default values** and nullable properties
- **Interfaces** and inheritance
- **Any kotlinx.serialization types** (JsonObject, etc.)

```kotlin
data class LegionConfiguration(
    val id: Int? = null,
    val name: String,
    val legionName: String,
    val description: String? = null,
    val isElite: Boolean = false,
    val assignedProvinces: List<String>,      // PostgreSQL ARRAY
    val cohortOrder: List<String>,
    val marchOrder: List<FormationConfig>,    // Array of COMPOSITE
    val standardStrength: Long,
    val supplyRequirements: List<SupplyConfig> // Complex nested types
)

@PgEnum
enum class Formation {
    Testudo,
    Wedge,
    Line,
    Skirmish
}

@PgComposite
data class FormationConfig(
    val cohortName: String,
    val formation: Formation   // Enum inside composite
)

@PgComposite
data class SupplyConfig(
    val itemName: String,
    val requirements: JsonObject   // JSONB inside composite
)
```

**Enums can implement interfaces:**

```kotlin
@PgEnum
enum class LegionStatus : EnumWithFormatter<LegionStatus> {
    Garrisoned,
    OnMarch,
    InBattle,
    Disbanded,
    Victorious;

    override fun toDisplayString(): String {
        return when (this) {
            Garrisoned -> T.get("legion.status.garrisoned")
            OnMarch    -> T.get("legion.status.marching")
            InBattle   -> T.get("legion.status.battle")
            Disbanded  -> T.get("legion.status.disbanded")
            Victorious -> T.get("legion.status.victorious")
        }
    }
}

// Works the same - enum values are mapped to PostgreSQL ENUM
data class Legion(
    val id: Int,
    val name: String,
    val status: LegionStatus  // Interface methods available on the enum
)
```

The same applies to data classes - they can implement interfaces, have companion objects, additional methods, etc. Only the **primary constructor parameters** are used for mapping.

### Same Map for Definition and Execution

The pattern `values(map)` + `execute(map)` uses the **same map** for both:
1. Generating column names and placeholders
2. Providing actual values

```kotlin
val data = entity.toMap("id")
dataAccess.insertInto("legions")
    .values(data)      // Defines: (col1, col2) VALUES (:col1, :col2)
    .execute(data)     // Provides: col1 -> value1, col2 -> value2
```

### PostgreSQL Table = Automatic Composite Type

PostgreSQL automatically creates a composite type with the same name as each table. This enables powerful patterns - you can select entire rows as nested objects:

```kotlin
// Map Kotlin classes to table composite types using table name
@PgComposite(name = "dispatches")
data class Dispatch(
    val id: Int,
    val campaignId: Int,
    val content: String,
    val author: String,
    val campaign: Campaign? = null  // Optional reference to parent
)

@PgComposite(name = "campaigns")
data class Campaign(
    val id: Int,
    val name: String,
    val province: String,
    val dispatches: List<Dispatch> = emptyList()  // Will hold nested dispatches
)

// Fetch campaigns with all their dispatches as nested arrays
dataAccess.select(
    "c.*",
    "ARRAY(SELECT d FROM dispatches d WHERE d.campaign_id = c.id) AS dispatches"
).from("campaigns c")
 .limit(10)
 .toListOf<Campaign>()
```

**Different query patterns:**

```kotlin
// Just campaign data - dispatches will be empty list (default value)
dataAccess.select("c.*")
    .from("campaigns c")
    .where("id = :id")
    .toSingleOf<Campaign>("id" to 1)

// Dispatch with its parent campaign as nested composite
dataAccess.select("c", "d.*")
    .from("dispatches d JOIN campaigns c ON d.campaign_id = c.id")
    .where("d.id = :id")
    .toSingleOf<Dispatch>("id" to 1)

// Just dispatch data - campaign will be null
dataAccess.select("d.*")
    .from("dispatches d")
    .where("d.id = :id")
    .toSingleOf<Dispatch>("id" to 1)
```

**Key points:**
- `@PgComposite(name = "table_name")` maps class to table's automatic composite type
- `SELECT d FROM table d` returns the whole row as a composite
- `SELECT alias` (without `.*`) returns the row as a composite type
- `ARRAY(SELECT ...)` aggregates rows into a PostgreSQL array
- Octavius deserializes composite arrays back to `List<T>`
- **When sending to PostgreSQL:** fields in your Kotlin class that don't exist in the PostgreSQL composite type are ignored (useful for computed/UI-only fields)

---

## Real-World Example

A complete legion configuration manager showing the full power of these patterns:

```kotlin
class LegionConfigurationManager(private val dataAccess: DataAccess) {

    fun saveConfiguration(configuration: LegionConfiguration): Boolean {
        // One line: convert to map, excluding auto-generated ID
        val flatValueMap = configuration.toMap("id")

        val result = dataAccess.insertInto("legion_configurations")
            .values(flatValueMap)  // Same map defines columns AND provides values
            .onConflict {
                onColumns("name", "legion_name")
                doUpdate(
                    "description" to "EXCLUDED.description",
                    "march_order" to "EXCLUDED.march_order",
                    "assigned_provinces" to "EXCLUDED.assigned_provinces",
                    "cohort_order" to "EXCLUDED.cohort_order",
                    "standard_strength" to "EXCLUDED.standard_strength",
                    "is_elite" to "EXCLUDED.is_elite",
                    "supply_requirements" to "EXCLUDED.supply_requirements"
                )
            }
            .execute(flatValueMap)  // Same map passed to execute

        return result is DataResult.Success
    }

    fun loadEliteConfiguration(legionName: String): LegionConfiguration? {
        return dataAccess.select("*")
            .from("legion_configurations")
            .where("legion_name = :legion_name AND is_elite = true")
            .toSingleOf<LegionConfiguration>("legion_name" to legionName)
            .getOrNull()
    }

    fun listConfigurations(legionName: String): List<LegionConfiguration> {
        return dataAccess.select("*")
            .from("legion_configurations")
            .where("legion_name = :legion_name")
            .orderBy("is_elite DESC, name ASC")
            .toListOf<LegionConfiguration>("legion_name" to legionName)
            .getOrDefault(emptyList())
    }

    fun deleteConfiguration(name: String, legionName: String): Boolean {
        return dataAccess.deleteFrom("legion_configurations")
            .where("name = :name AND legion_name = :legion_name")
            .execute("name" to name, "legion_name" to legionName)
            .map { it > 0 }
            .getOrDefault(false)
    }
}
```

**What's happening here:**
- `LegionConfiguration` has 9 fields including arrays, composites, enums, and JSONB
- `toMap("id")` converts it all to a flat map, excluding the ID
- `values(flatValueMap)` generates: `(name, legion_name, description, ...) VALUES (:name, :legion_name, :description, ...)`
- `execute(flatValueMap)` provides all values with automatic type conversion
- `toSingleOf<LegionConfiguration>()` deserializes back including all nested types

**No boilerplate. No field mapping. No session management. Just SQL.**

---

## CRUD Patterns

### Full CRUD Example

```kotlin
data class Tribute(
    val id: Int? = null,
    val province: String,
    val type: String,
    val amount: BigDecimal,
    val quantity: Int,
    val collectedAt: Instant? = null,
    val recordedAt: Instant? = null
)

class TributeRepository(private val dataAccess: DataAccess) {

    fun record(tribute: Tribute): DataResult<Tribute> {
        val data = tribute.toMap("id", "collected_at", "recorded_at")

        return dataAccess.insertInto("tributes")
            .values(data)
            .valueExpression("collected_at", "NOW()")
            .returning("*")
            .toSingleOf<Tribute>(data)
    }

    fun findById(id: Int): DataResult<Tribute?> {
        return dataAccess.select("*")
            .from("tributes")
            .where("id = :id")
            .toSingleOf<Tribute>("id" to id)
    }

    fun update(tribute: Tribute): DataResult<Tribute?> {
        val data = tribute.toMap("id", "collected_at", "recorded_at")

        return dataAccess.update("tributes")
            .setValues(data)
            .setExpression("recorded_at", "NOW()")
            .where("id = :id")
            .returning("*")
            .toSingleOf<Tribute>(data + ("id" to tribute.id))
    }

    fun cancel(id: Int): DataResult<Int> {
        return dataAccess.deleteFrom("tributes")
            .where("id = :id")
            .execute("id" to id)
    }

    fun findAll(page: Long = 0, size: Long = 20): DataResult<List<Tribute>> {
        return dataAccess.select("*")
            .from("tributes")
            .orderBy("collected_at DESC")
            .page(page, size)
            .toListOf<Tribute>()
    }
}
```

### Upsert Pattern

```kotlin
fun upsertTribute(tribute: Tribute): DataResult<Tribute?> {
    val data = tribute.toMap("id", "collected_at", "recorded_at")

    return dataAccess.insertInto("tributes")
        .values(data)
        .valueExpression("collected_at", "NOW()")
        .onConflict {
            onColumns("province", "type")
            doUpdate(
                "amount" to "EXCLUDED.amount",
                "quantity" to "EXCLUDED.quantity",
                "recorded_at" to "NOW()"
            )
        }
        .returning("*")
        .toSingleOf<Tribute>(data)
}
```

---

## When to Use These Patterns

**Use ORM-like patterns when:**
- Mapping straightforward CRUD operations
- Working with flat data structures
- Reducing boilerplate for common operations

**Use explicit SQL when:**
- Queries involve JOINs, CTEs, or complex logic
- You need precise control over column selection
- Performance optimization requires specific SQL constructs
- Business logic is expressed in SQL (calculated fields, aggregations)

The Anti-ORM philosophy means you always **can** drop down to raw SQL - these patterns are conveniences, not constraints.
