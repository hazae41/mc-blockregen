@file:Suppress("UNUSED_ANONYMOUS_PARAMETER", "DEPRECATION", "UNUSED_DESTRUCTURED_PARAMETER_ENTRY")

package fr.rhaz.minecraft

import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import me.ryanhamshire.GriefPrevention.GriefPrevention
import org.bukkit.*
import org.bukkit.block.Block
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
import org.bukkit.material.MaterialData
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
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
    Thread{ Thread.sleep(2000); file.delete()}.start()
}

object BlockRegenerator{

    lateinit var plugin: BlockRegeneratorPlugin;
    fun init(): Boolean = BlockRegenerator::plugin.isInitialized;
    fun init(plugin: BlockRegeneratorPlugin) {
        this.plugin = plugin;

        config = loadConfig() ?:
            return info("Config could not be loaded");

        data = loadData() ?:
            return info("Data could not be loaded")

        findDependencies()

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

    val cmd = CommandExecutor { sender, command, s, args -> true.also cmd@{

        val help = {
            sender.msg("&6BLOCK REGENERATOR &7v${plugin.description.version}&8:")
            listOf("force", "toggle", "info", "clear", "debug", "reload")
                .forEach{ sender.msg("&7/br &6$it") }
        }

        if(args.isEmpty()) return@cmd help()

        val noperm = {sender.msg("&cYou don't have permission to do that.")}

        when(args[0].toLowerCase()) {
            "force", "f" -> {

                if (!sender.hasPermission("blockregen.force"))
                    return@cmd noperm()

                sender.msg("&6Forcing block regeneration...");
                regen.run();
                sender.msg("&6Regeneration Complete!");

            }
            "toggle", "t" -> {

                if (!sender.hasPermission("blockregen.toggle"))
                    return@cmd noperm()

                when(paused()){
                    true -> paused(false).also {
                        sender.msg("&6Block regeneration enabled."); }
                    false -> paused(true).also {
                        sender.msg("&6Block regeneration disabled."); }
                }
            }
            "info", "i" -> {

                if (!sender.hasPermission("blockregen.info"))
                    return@cmd noperm()

                val blocks = get()
                val placed = blocks.filter { e -> e.action == "placed" }.size
                val broken = blocks.filter { e -> e.action == "broken" }.size

                "&6${blocks.size} blocks. ($placed placed, $broken broken)"
                    .also { info(it); sender.msg(it) };
            }
            "clear", "c" -> {

                if (!sender.hasPermission("blockregen.clear"))
                    return@cmd noperm()

                info("&6Blocks forcibly cleared by ${sender.name}");

                execute { emptyList() }

            }
            "debug", "d" -> {

                if (!sender.hasPermission("blockregen.debug"))
                    return@cmd noperm()

                when(debugging()){
                    true -> debugging(false).also{
                        sender.msg("&6Runtime debugging disabled."); }
                    false -> debugging(true).also {
                        sender.msg("&6Runtime debugging enabled.") }
                }
            }
            "reload", "r" -> {

                if (!sender.hasPermission("blockregen.reload"))
                    return@cmd noperm()

                config = loadConfig() ?:
                    return@cmd sender.msg("&cCould not load config");

                findDependencies()

                Bukkit.getScheduler().cancelTasks(plugin)
                schedule();
                sender.msg("&6Config reloaded")
            }
            "help", "h" -> help();
            else -> help();
        }
    }}

    fun debugging() = config.getBoolean("debugging")
    fun debugging(enabled: Boolean) = config.apply{ set("debugging", enabled); save(configfile)}
    fun info(msg: String) = plugin.logger.info(msg)
    fun debug(debug: String) {
        if (!debugging()) return;
        info("[BR][D] $debug");
        for (player in Bukkit.getOnlinePlayers())
            if (player.hasPermission("blockregen.rd"))
                player.msg("[BR][D] $debug")
    }

    lateinit var config: YamlConfiguration;
    val configfile by lazy {File(plugin.dataFolder, "config.yml")}
    fun loadConfig(): YamlConfiguration? {
        if (!configfile.parentFile.exists()) configfile.parentFile.mkdir();
        if (!configfile.exists()) plugin.saveResource(configfile.name, false);
        return YamlConfiguration.loadConfiguration(configfile) ?: null;
    }

    lateinit var data: YamlConfiguration;
    val datafile by lazy {File(plugin.dataFolder, "data.yml")}
    fun loadData(): YamlConfiguration? {
        if (!datafile.parentFile.exists()) datafile.parentFile.mkdir();
        if (!datafile.exists()) plugin.saveResource(datafile.name, false);
        return YamlConfiguration.loadConfiguration(datafile) ?: null;
    }

    var dependencies = mutableListOf<String>()
    fun findDependencies(){
        dependencies.clear()
        listOf("WorldGuard", "Factions", "GriefPrevention").forEach c@{

            if (!config.getBoolean("${it.toLowerCase()}.enabled"))
                return@c;

            plugin.server?.pluginManager?.getPlugin(it)
                    ?: return@c info("Could not load $it")

            dependencies.add(it)
        }
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
            if ("WorldGuard" !in dependencies) return@worlguard

            val type = config.getString("worldguard.type")

            val pregions = config.getStringList("worldguard.regions")

            val bregions = WorldGuardPlugin.inst()!!
                    .getRegionManager(block.world)
                    .getApplicableRegions(block.location);

            when(type){
                "whitelist" -> if(pregions.intersect(bregions).isEmpty()) return false
                "blacklist" -> if(pregions.intersect(bregions).any()) return false
                else -> info("worldguard.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run factions@{
            if("Factions" !in dependencies) return@factions

            val type = config.getString("factions.type")

            val factions = config.getStringList("factions.factions")

            val faction = BoardColl.get().getFactionAt(PS.valueOf(block))

            when(type){
                "whitelist" -> if(!factions.contains(faction.name)) return false
                "blacklist" -> if(factions.contains(faction.name)) return false
                else -> info("factions.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run griefprevention@{
            if("GriefPrevention" !in dependencies) return@griefprevention

            val restoreadmins = config.getBoolean("griefprevention.admins-claims")
            val restoreplayers = config.getBoolean("griefprevention.players-claims")

            val claim = GriefPrevention.instance.dataStore.getClaimAt(block.location, false, null)
                ?: return@griefprevention

            when(claim.isAdminClaim){
                true -> if(!restoreadmins) return false
                false -> if(!restoreplayers) return false
            }

        }

    }

    fun unit(unit: String): TimeUnit = when(unit){
        "seconds", "second", "sec", "s" -> TimeUnit.SECONDS
        "minutes", "minute", "min", "m" -> TimeUnit.MINUTES
        "hours", "hour", "h" -> TimeUnit.HOURS
        "days", "day", "d" -> TimeUnit.DAYS
        else -> TimeUnit.MINUTES
    }

    fun schedule(){
        val s = config.getString("regen-delay").split(" ")
        val delay = TimeUnit.SECONDS.convert(s[0].toLongOrNull() ?: return, unit(s[1]))
        regen = Regen();
        regen.runTaskTimer(plugin, 0L, delay * 20L);
    }

    lateinit var regen: Regen;
    class Regen: BukkitRunnable(){
        override fun run() {

            if(paused()) return;

            val max = config.getInt("max-blocks").takeIf { it > 0 } ?: 100

            val s = config.getString("min-time").split(" ")
            val milliseconds = TimeUnit.MILLISECONDS
                    .convert(s[0].toLongOrNull() ?: return, unit(s[1]))

            val blocks = get().take(max)

            debug("Performing block regeneration.");
            val startTime = System.currentTimeMillis();

            val efficiency = config.getInt("percentage-efficiency")

            for(e in blocks) {

                val diff = System.currentTimeMillis() - e.time
                if(diff <= milliseconds) break

                execute { drop(1) }

                debug(
                "MATERIAL: ${e.mat.itemType.name}:${e.mat.data}," +
                "X: ${e.loc.blockX}, " +
                "Y: ${e.loc.blockY}, " +
                "Z: ${e.loc.blockZ}, " +
                "WORLD: ${e.loc.world.name}"
                )

                if(Random().nextInt(100) >= efficiency)
                    continue;

                when(e.action){
                    "placed" -> e.loc.block.type = Material.AIR;
                    "broken" -> e.loc.block.apply{
                        type = e.mat.itemType
                        data = e.mat.data
                    }
                }

            }

            val endTime = System.currentTimeMillis()
            debug("Regeneration complete, took ${startTime - endTime} ms.")

        }
    };

    fun paused(): Boolean = config.getBoolean("paused")
    fun paused(paused: Boolean) = config.apply { set("paused", paused); save(configfile)}

    fun ignorecreative(): Boolean = config.getBoolean("ignore-creative")

    var listener = object: Listener{

        @EventHandler
        fun onBlockBreak(e: BlockBreakEvent) {

            if(paused()) return;
            if (ignorecreative() && e.player.gameMode == GameMode.CREATIVE) return
            if (!config.getBoolean("restore-destroyed")) return;
            if(!restore(e.block)) return

            val ser = ser(entry(e.block, "broken"))
            execute { add(ser); this }
        }

        @EventHandler
        fun onBlockBurn(e: BlockBurnEvent) {

            if(paused()) return;
            if (!config.getBoolean("restore-destroyed")) return;
            if(!restore(e.block)) return

            val ser = ser(entry(e.block, "broken"))
            execute { add(ser); this }
        }

        @EventHandler
        fun onBlockExplode(e: EntityExplodeEvent) {

            if(paused()) return;
            if (!config.getBoolean("restore-destroyed")) return;

            for(block in e.blockList()){
                if(!restore(block)) continue
                val ser = ser(entry(block, "broken"))
                execute { add(ser); this }
            }
        }

        @EventHandler
        fun onBlockPlace(e: BlockPlaceEvent) {

            if(paused()) return;
            if (ignorecreative() && e.player.gameMode == GameMode.CREATIVE) return
            if (!config.getBoolean("restore-placed")) return;
            if(!restore(e.block)) return

            val ser = ser(entry(e.block, "placed"))
            execute { add(ser); this }
        }
    }

    data class Entry(val loc: Location, val time: Long, val action: String, val mat: MaterialData)

    fun entry(block: Block, action: String): Entry {
        val mat = MaterialData(block.type, block.data)
        return Entry(block.location, System.currentTimeMillis(), action, mat)
    }

    fun ser(e: Entry): String {
        val mtype = e.mat.itemType.ordinal
        val mdata = e.mat.data
        return listOf(
            e.loc.world.name,
            e.loc.blockX,
            e.loc.blockY,
            e.loc.blockZ,
            e.time,
            e.action,
            mtype, mdata
        ).joinToString("/")
    }

    fun deser(data: String): Entry {
        val split = data.split("/")

        val world = Bukkit.getWorld(split[0])
        val x = split[1].toDouble()
        val y = split[2].toDouble()
        val z = split[3].toDouble()
        val loc = Location(world, x, y, z)

        val time = split[4].toLong()

        val action = split[5]

        val mat = MaterialData(split[6].toInt(), split[7].toByte())

        return Entry(loc, time = time, action = action, mat = mat)
    }

    fun execute(lambda: MutableList<String>.() -> List<String>) = data.apply {
        val list = getStringList("data")
        set("data", list.let(lambda))
        save(datafile)
    }

    fun get() = data.getStringList("data").map { e -> deser(e) }
}
