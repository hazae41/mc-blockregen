package hazae41.minecraft.blockregen.griefprevention

import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import me.ryanhamshire.GriefPrevention.GriefPrevention
import org.bukkit.block.Block

class Filter(task: Task) : ConfigSection(task.Filters(), "griefprevention") {
    val enabled by boolean("enabled")
    val restoreAdmins by boolean("restore-admin-claims")
    val restorePlayers by boolean("restore-player-claims")
}

fun addFilter() {
    filters += fun Task.(block: Block) = true.also {
        val filter = Filter(this)
        if (!filter.enabled) return true
        val dataStore = GriefPrevention.instance.dataStore
        val claim = dataStore.getClaimAt(block.location, false, null) ?: return@also
        when(claim.isAdminClaim){
            true -> if(!filter.restoreAdmins) return false
            false -> if(!filter.restorePlayers) return false
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