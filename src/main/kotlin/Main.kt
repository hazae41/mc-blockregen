package hazae41.minecraft.blockregen.factions

import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import net.md_5.bungee.api.ChatColor.stripColor
import net.md_5.bungee.api.ChatColor.translateAlternateColorCodes
import org.bukkit.block.Block

class Filter(task: Task) : ConfigSection(task.Filters(), "factions") {
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
        val faction = BoardColl.get().getFactionAt(PS.valueOf(block)).name.lowerCase.let(::colorless)
        when (filter.type) {
            "whitelist" -> if (faction !in list) return false
            "blacklist" -> if (faction in list) return false
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