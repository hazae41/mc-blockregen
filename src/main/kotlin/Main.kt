package hazae41.minecraft.blockregen.lands

import hazae41.minecraft.blockregen.controllers
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigFile
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.lowerCase
import me.angeschossen.lands.api.landsaddons.LandsAddon
import org.bukkit.block.Block

object Config: ConfigFile("config"){
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun Plugin.addController() = also { plugin ->
    controllers += fun(block: Block) = true.also{
        if(!Config.enabled) return true
        val list = Config.list.map { it.lowerCase }
        LandsAddon(plugin, true).apply {
            val key = initialize()
            val land = getLandChunk(block.chunk)?.land ?: return true
            val name = land.name.lowerCase
            disable(key)
            when(Config.type){
                "whitelist" -> if(name !in list) return false
                "blacklist" -> if(name in list) return false
            }
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addController()
    }
}