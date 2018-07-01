@file:Suppress("UNUSED_ANONYMOUS_PARAMETER", "DEPRECATION", "UNUSED_DESTRUCTURED_PARAMETER_ENTRY")

package fr.rhaz.minecraft

import com.massivecraft.factions.Factions
import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Logger


class BlockRegeneratorPlugin: JavaPlugin(), Listener{
    override fun onEnable() = BlockRegenerator.init(this)
}

fun CommandSender.msg(msg: String) = sendMessage(msg.replace("&", "§"))

fun Logger.donate(server: Server) {
    val file = File(".noads")
    if(file.exists()) return
    val plugins = server.pluginManager.plugins
            .filter { plugin -> listOf("Hazae41", "RHazDev").intersect(plugin.description.authors).any() }
            .map { plugin -> plugin.description.name }
    this.info("""
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
    """.trimMargin("|"))
    file.createNewFile()
    Thread{ Thread.sleep(1000); file.delete()}.start()
}

object BlockRegenerator{

    lateinit var plugin: BlockRegeneratorPlugin;
    fun init(): Boolean = BlockRegenerator::plugin.isInitialized;
    fun init(plugin: BlockRegeneratorPlugin) {
        this.plugin = plugin;

        config = loadConfig() ?:
            return info("Config could not be loaded");

        loadWorldGuard();
        loadFactions()

        plugin.getCommand("blockregen").let {
            it.executor = cmd
            it.tabCompleter = TabCompleter{
                _, _, _, _ ->
                listOf("force", "disable", "info", "clear", "debug", "reload");
            }
        };

        plugin.server.pluginManager.registerEvents(listener, plugin);

        debug("BlockRegenerator has been Enabled.!");
        plugin.logger.donate(plugin.server)

        schedule()
    }

    val cmd = CommandExecutor { sender, command, s, args ->

        val help = {
            sender.msg("&6BLOCK REGENERATOR &7v${plugin.description.version}&8:")
            listOf("force", "toggle", "info", "clear", "debug", "reload").forEach{
                sender.msg("&7/br &6$it")
            }
            true
        }

        if(args.isEmpty()) return@CommandExecutor help()

        val noperm = {sender.msg(
            "${ChatColor.RED}You don't have permission to do that.")}

        when(args[0].toLowerCase()) {
            "force", "f" -> true.also {

                if (!sender.hasPermission("blockregen.force"))
                    return@also noperm()

                sender.msg("&6Forcing block regeneration...");
                regen.run();
                sender.msg("&6Regeneration Complete!");

            }
            "toggle", "t" -> true.also {

                if (!sender.hasPermission("blockregen.toggle"))
                    return@also noperm()

                when(paused()){
                    true -> paused(false).also {
                        sender.msg("&6Block regeneration enabled.");
                    }
                    false -> paused(true).also {
                        sender.msg("&6Block regeneration disabled.");
                    }
                }
            }
            "info", "i" -> true.also{
                if (!sender.hasPermission("blockregen.info"))
                    return@also noperm()

                val placed = blocks.filter { (k,v) -> v == "placed" }
                val broken = blocks.filter { (k,v) -> v == "broken" }

                ("&6${blocks.size} blocks. " +
                "(${placed.size} placed, ${broken.size} broken)")
                .also { info(it); sender.msg(it) };

            }
            "clear", "c" -> true.also {

                if (!sender.hasPermission("blockregen.clear"))
                    return@also noperm()

                info("&6Blocks forcibly cleared by ${sender.name}");

                blocks.clear();

            }
            "debug", "d" -> true.also {

                if (!sender.hasPermission("blockregen.debug"))
                    return@also noperm()

                when(debugging()){
                    true -> debugging(false).also{
                        sender.msg("&6Runtime debugging disabled.");
                    }
                    false -> debugging(true).also {
                        sender.msg("&6Runtime debugging enabled.")
                    }
                }
            }
            "reload", "r" -> true.also {

                if (!sender.hasPermission("blockregen.reload"))
                    return@also noperm()

                config = loadConfig() ?:
                    return@also sender.msg("&cCould not load config");

                Bukkit.getScheduler().cancelTasks(plugin)
                schedule();
                sender.msg("&6Config reloaded")
            }
            "help", "h" -> help();
            else -> help();
        }
    }

    var blocks = mutableListOf<Pair<BlockState, String>>();

    fun debugging() = config.getBoolean("debugging")
    fun debugging(enabled: Boolean) = config.set("debugging", enabled).also { saveConfig() }
    fun info(msg: String) = plugin.logger.info(msg)
    fun debug(debug: String) {
        if (!debugging()) return;
        info("[BR][D] $debug");
        for (player in Bukkit.getOnlinePlayers())
            if (player.hasPermission("blockregen.rd"))
                player.msg("[BR][D] $debug")
    }

    lateinit var config: YamlConfiguration;
    fun loadConfig(): YamlConfiguration? {
        val name = "config.yml"
        val file = File(plugin.dataFolder, name);
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdir();
        if (!file.exists()) plugin.saveResource(name, false);
        config = YamlConfiguration.loadConfiguration(file) ?: return null;
        return config;
    }
    fun saveConfig() {
        try {
            config.save(File(plugin.dataFolder, "config.yml"));
        } catch (e: IOException) { e.printStackTrace(); }
    }

    lateinit var worldguard: WorldGuardPlugin;
    fun useWorldGuard(): Boolean =
        ::worldguard.isInitialized &&
        config.getBoolean("worldguard.enabled")
    fun loadWorldGuard(){

        if (!config.getBoolean("world-guard"))
            return;

        val plugin = plugin.server?.pluginManager?.getPlugin("WorldGuard")
                ?: return info("Could not load WorldGuard");

        if (plugin !is WorldGuardPlugin)
            return plugin.logger.info("You're using a bad version of WorldGuard.");

        worldguard = plugin;
    }

    lateinit var factions: Factions;
    fun useFactions(): Boolean =
        ::factions.isInitialized &&
        config.getBoolean("factions.enabled");
    fun loadFactions(){

        if (!config.getBoolean("factions.enabled"))
            return;

        val plugin = plugin.server?.pluginManager?.getPlugin("Factions")
            ?: return info("Could not load Factions")

        if(plugin !is Factions)
            return plugin.logger.info("You're using a bad version of Factions")

        factions = plugin
    }

    fun restore(block: Block): Boolean = true.also{

        run materials@{
            if(!config.getBoolean("materials.enabled"))
                return@materials

            val type = config.getString("materials.type")

            val materials = config.getStringList("materials.materials")

            val material = "${block.type}:${block.data}"

            when(type){
                "whitelist" -> if(!materials.contains(material)) return false
                "blacklist" -> if(materials.contains(material)) return false
                else -> info("materials.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run worlds@{
            if(!config.getBoolean("worlds.enabled"))
                return@worlds

            val type = config.getString("worlds.type")

            val worlds = config.getStringList("worlds.worlds")

            val world = block.world.name

            when(type){
                "whitelist" -> if(!worlds.contains(world)) return false
                "blacklist" -> if(worlds.contains(world)) return false
                else -> info("worlds.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run worlguard@{
            if (!useWorldGuard()) return@worlguard

            val type = config.getString("worldguard.type")

            val pregions = config.getStringList("worldguard.regions")

            val bregions = worldguard
                    .getRegionManager(block.world)
                    .getApplicableRegions(block.location);

            when(type){
                "whitelist" -> if(pregions.intersect(bregions).isEmpty()) return false
                "blacklist" -> if(pregions.intersect(bregions).any()) return false
                else -> info("worldguard.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run factions@{
            if(!useFactions()) return@factions

            val type = config.getString("factions.type")

            val factions = config.getStringList("factions.factions")

            val faction = BoardColl.get().getFactionAt(PS.valueOf(block))

            when(type){
                "whitelist" -> if(!factions.contains(faction.name)) return false
                "blacklist" -> if(factions.contains(faction.name)) return false
                else -> info("factions.type is misconfigured, it should be whitelist or blacklist")
            }
        }

    }

    fun schedule(){
        val delay = config.getLong("regen-delay");
        val unit = TimeUnit.valueOf(config.getString("regen-delay-unit").toUpperCase())
        val seconds = TimeUnit.SECONDS.convert(delay, unit)
        regen = Regen();
        regen.runTaskTimer(plugin, 0L, seconds * 20L);
    }

    lateinit var regen: Regen;
    class Regen: BukkitRunnable(){
        override fun run() {

            if(paused()) return;

            val max = config.getInt("max-blocks").takeIf { it > 0 } ?: Int.MAX_VALUE

            val blocks = blocks.toMutableList().take(max)

            debug("Performing block regeneration.");
            val startTime = System.currentTimeMillis();

            val efficiency = config.getInt("percentage-efficiency")

            for(pair in blocks) {

                BlockRegenerator.blocks.remove(pair)

                val state = pair.first
                val type = pair.second

                debug(
                "MATERIAL: ${state.type}," +
                "DATA: ${state.data}, " +
                "X: ${state.x}, " +
                "Y: ${state.y}, " +
                "Z: ${state.z}, " +
                "WORLD: ${state.world.name}"
                )

                if(Random().nextInt(100) >= efficiency)
                    continue;

                when(type){
                    "placed" -> state.location.block.type = Material.AIR;
                    "broken" -> {
                        state.location.block.type = state.type
                        state.location.block.data = state.data.data
                    }
                }

            }

            val endTime = System.currentTimeMillis()
            debug("Regeneration complete, took ${startTime - endTime} ms.")
        }
    };

    fun paused(): Boolean = config.getBoolean("paused")
    fun paused(paused: Boolean) = config.set("paused", paused).also { saveConfig() }

    fun ignorecreative(): Boolean = config.getBoolean("ignore-creative")

    var listener = object: Listener{

        @EventHandler
        fun onBlockBreak(e: BlockBreakEvent) {

            if(paused()) return;
            if (ignorecreative() && e.player.gameMode == GameMode.CREATIVE) return
            if (!config.getBoolean("restore-destroyed")) return;

            if(restore(e.block)) blocks.add(Pair(e.block.state, "broken"));
        }

        @EventHandler
        fun onBlockBurn(e: BlockBurnEvent) {

            if(paused()) return;
            if (!config.getBoolean("restore-destroyed")) return;

            if(restore(e.block)) blocks.add(Pair(e.block.state, "broken"));
        }

        @EventHandler
        fun onBlockExplode(e: EntityExplodeEvent) {

            if(paused()) return;
            if (!config.getBoolean("restore-destroyed")) return;

            for(block in e.blockList())
                if(restore(block)) blocks.add(Pair(block.state, "broken"));
        }

        @EventHandler
        fun onBlockPlace(e: BlockPlaceEvent) {

            if(paused()) return;
            if (ignorecreative() && e.player.gameMode == GameMode.CREATIVE) return
            if (!config.getBoolean("restore-placed")) return;

            if(restore(e.block)) blocks.add(Pair(e.block.state, "placed"));
        }
    }
}

