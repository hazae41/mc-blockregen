package hazae41.minecraft.blockregen.worldguard

import com.sk89q.worldedit.bukkit.BukkitWorld
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
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
        val regions = WorldGuard.getInstance().platform.run {
            val world = BukkitWorld(block.world)
            val vector = BlockVector3.at(block.x, block.y, block.z)
            regionContainer.get(world)!!.getApplicableRegions(vector).map { it.id }
        }
        when(Config.type){
            "whitelist" -> if(list.intersect(regions).isEmpty()) return false
            "blacklist" -> if(list.intersect(regions).any()) return false
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addController()
    }
}