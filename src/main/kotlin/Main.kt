package hazae41.minecraft.blockregen.factions

import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import hazae41.minecraft.blockregen.Config.Filters
import hazae41.minecraft.blockregen.filters
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.severe
import hazae41.minecraft.kotlin.lowerCase
import net.md_5.bungee.api.ChatColor.stripColor
import net.md_5.bungee.api.ChatColor.translateAlternateColorCodes
import org.bukkit.block.Block

object Config : ConfigSection(Filters, "factions") {
    val enabled by boolean("enabled")
    val type by string("type")
    val list by stringList("list")
}

fun addFilter() {
    filters += fun(block: Block) = true.also {
        if (!Config.enabled) return true
        val list = Config.list.map { it.lowerCase }
        fun colorless(str: String) = stripColor(translateAlternateColorCodes('&', str))
        val faction = BoardColl.get().getFactionAt(PS.valueOf(block)).name.lowerCase.let(::colorless)
        when (Config.type) {
            "whitelist" -> if (faction !in list) return false
            "blacklist" -> if (faction in list) return false
        }
    }
}

class Plugin : BukkitPlugin() {
    override fun onEnable() {
        addFilter()
        info("Added filter")
        if (!dataFolder.exists()) return
        severe("Please put your filter in BlockRegen config and remove ${dataFolder.name} folder")
    }
}