package hazae41.minecraft.blockregen.griefprevention

import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.init
import me.ryanhamshire.GriefPrevention.GriefPrevention
import org.bukkit.block.Block

object Config: PluginConfigFile("config"){
    val enabled by boolean("enabled")
    val restoreAdmins by boolean("restore-admin-claims")
    val restorePlayers by boolean("restore-player-claims")
}

fun addFilter() {
    filters += fun(block: Block) = true.also {
        if(!Config.enabled) return true
        val dataStore = GriefPrevention.instance.dataStore
        val claim = dataStore.getClaimAt(block.location, false, null) ?: return@also
        when(claim.isAdminClaim){
            true -> if(!Config.restoreAdmins) return false
            false -> if(!Config.restorePlayers) return false
        }
    }
}

class Plugin: BukkitPlugin(){
    override fun onEnable() {
        init(Config)
        addFilter()
    }
}