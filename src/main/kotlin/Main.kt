package hazae41.minecraft.blockregen.towny

import com.palmergames.bukkit.towny.Towny
import com.palmergames.bukkit.towny.`object`.TownBlock
import com.palmergames.bukkit.towny.`object`.TownyWorld
import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.block.Block

class Filter(task: Task) : ConfigSection(task.Filters(), "towny") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addFilter() {
    filters += fun Task.(block: Block) = true.also {
        val filter = Filter(this)
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        val tblock = TownBlock(block.x, block.z, TownyWorld(block.world.name))
        val towns = Towny.getPlugin().townyUniverse.townsMap.values
            .filter { it.hasTownBlock(tblock) }
            .map { it.tag }
        when (filter.type) {
            "whitelist" -> if (towns.intersect(list).isEmpty()) return false
            "blacklist" -> if (towns.intersect(list).any()) return false
        }
    }
}

class Plugin : BukkitPlugin() {
    override fun onEnable() {
        if (dataFolder.exists())
            severe("Please put your filter in BlockRegen config and remove ${dataFolder.name} folder")
        addFilter()
        info("Added filter")
    }
}