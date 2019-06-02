package hazae41.minecraft.blockregen.factions

import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import hazae41.minecraft.blockregen.controllers
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.lowerCase
import org.bukkit.ChatColor.stripColor
import org.bukkit.ChatColor.translateAlternateColorCodes
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
        fun colorless(str: String) = stripColor(translateAlternateColorCodes('&', str))
        val faction = BoardColl.get().getFactionAt(PS.valueOf(block)).name.lowerCase.let(::colorless)
        when(Config.type){
            "whitelist" -> if(faction !in list) return false
            "blacklist" -> if(faction in list) return false
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addController()
    }
}