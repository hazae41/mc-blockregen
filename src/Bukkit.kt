package fr.rhaz.minecraft.blockregenerator

import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

fun CommandSender.msg(msg: String) = sendMessage(msg.replace("&", "§"))
fun CommandSender.msg(text: TextComponent) = spigot().sendMessage(text)

fun JavaPlugin.donate(color: ChatColor) {
    File(".noads").apply { if(exists()) return else createNewFile() }

    val plugins = server.pluginManager.plugins
            .filter {it.description.authors.intersect(listOf("Hazae41", "RHazDev")).any()}
            .map {it.description.name}

    val msg = """
    |
    |    __         _    ____  __   ___
    |   |__) |__|  /_\   ___/ |  \ |__  \  /
    |   |  \ |  | /   \ /___  |__/ |___  \/
    |
    |   It seems you use $plugins
    |
    |   If you like my softwares or you just want to support me, I'd enjoy donations.
    |   By donating, you're going to encourage me to continue developing quality softwares.
    |   And you'll be added to the donators list!
    |
    |   Click here to donate: http://dev.rhaz.fr/donate
    |
    """.trimMargin("|");

    logger.info(color.toString() + msg);
}

fun JavaPlugin.update(id: Int, color: ChatColor) = server.scheduler.runTaskLaterAsynchronously(this, {

    if(!(spiget(id) newerThan description.version)) return@runTaskLaterAsynchronously;

    val message = text("An update is available for ${description.name}!").apply {
        val url = "https://www.spigotmc.org/resources/$id"
        text += "\nDownload it here: $url"
        this.color = color
        clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
    }

    server.pluginManager.registerEvents(object : Listener {
        @EventHandler
        fun onJoin(e: PlayerJoinEvent) {
            if (e.player.hasPermission("rhaz.update"))
                e.player.spigot().sendMessage(message)
        }
    }, this)

    server.consoleSender.spigot().sendMessage(message);

}, 0)!!