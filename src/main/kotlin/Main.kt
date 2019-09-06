package hazae41.minecraft.blockregen.civs

import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.ChatColor.stripColor
import org.bukkit.ChatColor.translateAlternateColorCodes
import org.bukkit.block.Block
import org.redcastlemedia.multitallented.civs.towns.TownManager

object Config : PluginConfigFile("config") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addFilter() {
    filters += fun(block: Block) = true.also {
        if(!Config.enabled) return true
        val list = Config.list.map { it.lowerCase }
        fun colorless(str: String) = stripColor(translateAlternateColorCodes('&', str))
        val town = TownManager.getInstance().getTownAt(block.location).name.lowerCase.let(::colorless)
        when(Config.type){
            "whitelist" -> if(town !in list) return false
            "blacklist" -> if(town in list) return false
        }
    }
}

class Plugin : BukkitPlugin() {
    override fun onEnable() {
        init(Config)
        addFilter()
    }
}