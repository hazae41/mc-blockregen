package hazae41.minecraft.blockregen.lands

import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import me.angeschossen.lands.api.landsaddons.LandsAddon
import org.bukkit.block.Block

class Filter(task: Task) : ConfigSection(task.Filters(), "lands") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun Plugin.addFilter() = also { plugin ->
    filters += fun Task.(block: Block) = true.also {
        val filter = Filter(this)
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        LandsAddon(plugin, true).apply {
            val key = initialize()
            val land = getLandChunk(block.location)?.land ?: return true
            val name = land.name.lowerCase
            disable(key)
            when (filter.type) {
                "whitelist" -> if (name !in list) return false
                "blacklist" -> if (name in list) return false
            }
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