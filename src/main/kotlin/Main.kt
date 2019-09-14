package hazae41.minecraft.blockregen.civs

import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.ChatColor.stripColor
import org.bukkit.ChatColor.translateAlternateColorCodes
import org.bukkit.block.Block
import org.redcastlemedia.multitallented.civs.towns.TownManager

class Filter(task: Task) : ConfigSection(task.Filters(), "civs") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addFilter() {
    filters += fun Task.(block: Block) = true.also {
        val filter = Filter(this)
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        fun colorless(str: String) = stripColor(translateAlternateColorCodes('&', str))
        val town = TownManager.getInstance().getTownAt(block.location).name.lowerCase.let(::colorless)
        when(filter.type){
            "whitelist" -> if(town !in list) return false
            "blacklist" -> if(town in list) return false
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