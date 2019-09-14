package hazae41.minecraft.blockregen.worldguard

import com.sk89q.worldedit.bukkit.BukkitWorld
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.block.Block

class Filter(task: Task) : ConfigSection(task.Filters(), "worldguard") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addFilter() {
    filters += fun Task.(block: Block) = true.also {
        val filter = Filter(this)
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        val regions = WorldGuard.getInstance().platform.run {
            val world = BukkitWorld(block.world)
            val vector = BlockVector3.at(block.x, block.y, block.z)
            regionContainer.get(world)!!.getApplicableRegions(vector).map { it.id }
        }
        when (filter.type) {
            "whitelist" -> if (list.intersect(regions).isEmpty()) return false
            "blacklist" -> if (list.intersect(regions).any()) return false
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