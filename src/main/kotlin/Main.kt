package hazae41.minecraft.blockregen.factions

import com.bekvon.bukkit.residence.Residence
import hazae41.minecraft.blockregen.controllers
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.block.Block

object Config: PluginConfigFile("config"){
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addController(){
    controllers += fun(block: Block) = true.also{
        if(!Config.enabled) return true
        val list = Config.list.map { it.lowerCase }
        val residence = Residence.getInstance()?.residenceManager?.getByLoc(block.location)
        val name = residence?.name?.lowerCase ?: return true
        when(Config.type){
            "whitelist" -> if(name !in list) return false
            "blacklist" -> if(name in list) return false
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addController()
    }
}