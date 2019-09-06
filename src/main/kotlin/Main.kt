package hazae41.minecraft.blockregen.towny

import com.palmergames.bukkit.towny.Towny
import com.palmergames.bukkit.towny.`object`.TownBlock
import com.palmergames.bukkit.towny.`object`.TownyWorld
import hazae41.minecraft.blockregen.filters
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

fun addFilter() {
    filters += fun(block: Block) = true.also {
        if (!Config.enabled) return true
        val list = Config.list.map { it.lowerCase }
        val tblock = TownBlock(block.x, block.z, TownyWorld(block.world.name))
        val towns = Towny.getPlugin().townyUniverse.townsMap.values
                .filter { it.hasTownBlock(tblock) }
                .map { it.tag }
        when(Config.type){
            "whitelist" -> if(towns.intersect(list).isEmpty()) return false
            "blacklist" -> if(towns.intersect(list).any()) return false
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addFilter()
    }
}