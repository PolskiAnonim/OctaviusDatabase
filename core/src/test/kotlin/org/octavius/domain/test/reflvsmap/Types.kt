package org.octavius.domain.test.reflvsmap

import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgCompositeMapper

@PgComposite(name = "perf_stats_refl")
data class StatsRefl(val strength: Int, val agility: Int, val intelligence: Int)

@PgComposite(name = "perf_char_refl")
data class CharRefl(val id: Int, val name: String, val stats: StatsRefl)

@PgComposite(name = "perf_stats_map", mapper = StatsMapMapper::class)
data class StatsMap(val strength: Int, val agility: Int, val intelligence: Int)

object StatsMapMapper : PgCompositeMapper<StatsMap> {
    override fun toDataObject(map: Map<String, Any?>) = StatsMap(
        strength = map["strength"] as Int,
        agility = map["agility"] as Int,
        intelligence = map["intelligence"] as Int
    )
    override fun toDataMap(obj: StatsMap) = mapOf(
        "strength" to obj.strength,
        "agility" to obj.agility,
        "intelligence" to obj.intelligence
    )
}

@PgComposite(name = "perf_char_map", mapper = CharMapMapper::class)
data class CharMap(val id: Int, val name: String, val stats: StatsMap)

object CharMapMapper : PgCompositeMapper<CharMap> {
    override fun toDataObject(map: Map<String, Any?>) = CharMap(
        id = map["id"] as Int,
        name = map["name"] as String,
        stats = map["stats"] as StatsMap
    )
    override fun toDataMap(obj: CharMap) = mapOf(
        "id" to obj.id,
        "name" to obj.name,
        "stats" to obj.stats
    )
}