@file:Suppress("UNUSED_ANONYMOUS_PARAMETER", "DEPRECATION")

package fr.rhaz.minecraft.blockregenerator

import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
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
import java.lang.Byte.parseByte
import java.util.LinkedList
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class BlockRegeneratorPlugin: JavaPlugin(), Listener{
    override fun onEnable() = BlockRegenerator.init(this)
}

object BlockRegenerator{

    var plugin: BlockRegeneratorPlugin? = null;
    fun init(): Boolean = plugin != null;
    fun init(plugin: BlockRegeneratorPlugin) {
        this.plugin = plugin;

        val config = loadConfig("config.yml")
            ?: return info("Could not load config");

        loadWorlds();
        loadWorldGuard();
        if(worldGuard()) loadRegions();
        loadExcludedMaterials();

        timer.runTaskTimer(plugin, 0L,config.getLong("regen-delay") * 20L * 60L);
        plugin.getCommand("regen").let {
            it.setExecutor {
                sender, command, s, args ->
                if(args.isEmpty()) true.not();
                val lowerCase = args[0].toLowerCase();
                when(lowerCase){
                    "forceregen", "fr" -> true.also{

                        if (!sender.hasPermission("blockregen.action.forceregen"))
                            return@also sender.sendMessage("${ChatColor.RED}" +
                            "You don't have permission to do that." )

                        sender.sendMessage("${ChatColor.GREEN}" +
                            "Forcing block regeneration...");
                        timer.run();
                        sender.sendMessage("${ChatColor.GREEN}" +
                            "Regeneration Complete!");

                    }
                    "disableregen", "dr" -> true.also{

                        if (!sender.hasPermission("blockregen.action.disableregen"))
                            return@also sender.sendMessage("${ChatColor.RED}" +
                            "You don't have permission to do that." )

                        val paused = paused();
                        paused(!paused);

                        if(!paused) sender.sendMessage("${ChatColor.GREEN}" +
                            "Block regeneration enabled.");
                        else sender.sendMessage("${ChatColor.GREEN}" +
                            "Block regeneration disabled.");

                    }
                    "clearblocks", "cb" -> true.also {

                        if (!sender.hasPermission("blockregen.action.clearblocks"))
                            return@also sender.sendMessage("${ChatColor.RED}" +
                            "You don't have permission to do that.");

                        info("Blocks forcibly cleared by ${sender.name}");

                        "${placedBlocks.size + brokenBlocks.size} blocks lost. " +
                        "(${placedBlocks.size} placed, ${brokenBlocks.size} broken)"
                            .let { info(it); sender.sendMessage(it) };

                        listOf(placedBlocks, brokenBlocks).forEach{it.clear()};

                    }
                    "rd" -> true.also {

                        if (!sender.hasPermission("blockregen.action.rd"))
                            return@also sender.sendMessage("${ChatColor.RED}" +
                            "You don't have permission to do that.");

                        debugging = !debugging;

                        if (debugging) sender.sendMessage("${ChatColor.GREEN}" +
                            "Runtime debugging enabled.");
                        else sender.sendMessage("${ChatColor.RED}" +
                            "Runtime debugging disabled.");

                    }
                    else -> false;
                }
            }
            it.setTabCompleter {
                _, _, _, _ ->
                listOf("forceregen", "disableregen", "clearblocks", "rd");
            }
        };

        plugin.server.pluginManager.registerEvents(listener, plugin);

        debug("BlockRegenerator has been Enabled.!");
    }

    var brokenBlocks = ArrayList<BlockState>();
    var placedBlocks = ArrayList<BlockState>();

    var debugging = true;
    fun debug(debug: String) {
        if (!debugging) return;
        for (player in Bukkit.getOnlinePlayers())
            if (player.hasPermission("blockregen.rd"))
                player.sendMessage("[BR][D] $debug")
        plugin?.logger?.info("[BR][D] $debug");
    }
    fun info(msg: String) = plugin?.logger?.info(msg) ?: Unit;

    var config: YamlConfiguration? = null;
    fun loadConfig(name: String): YamlConfiguration? {
        val plugin = this.plugin ?: return null;
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdir();
        val file = File(plugin.dataFolder, name);
        if (!file.exists()) plugin.saveResource(name, false);
        config = YamlConfiguration.loadConfiguration(file);
        return config;
    }
    fun saveConfig(config: YamlConfiguration, name: String) {
        try {
            config.save(File(plugin!!.dataFolder, name));
        } catch (e: IOException) {
            e.printStackTrace();
        }
    }

    var worlds = ArrayList<String>();
    fun loadWorlds() {

        val config = this.config
                ?: return debug("Config is null");

        for (world in config.getStringList("worlds")) {

            if (Bukkit.getWorld(world) == null)
                info("Unknown world $world.");

            else worlds.add(world);

        }
    }

    var worldguard: WorldGuardPlugin? = null;
    fun worldGuard(): Boolean = worldguard != null;
    fun loadWorldGuard(){
        val config = this.config ?: return debug("Config is null");
        if (!config.getBoolean("world-guard"))
            return;

        val plugin = this.plugin?.server?.pluginManager?.getPlugin("WorldGuard")
                ?: return info("Could not load WorldGuard");

        if (plugin !is WorldGuardPlugin)
            return plugin.logger.info("WorldGuard cannot be found.");

        worldguard = plugin;
    }

    var regions = ArrayList<String>();
    fun loadRegions() {

        debug("Loading regions...");

        val config = this.config
                ?: return debug("Config is null");
        val worldguard = this.worldguard
                ?: return debug("WorldGuard is null");

        for (world in worlds) {

            val regionmanager = worldguard
                    .getRegionManager(Bukkit.getWorld(world));

            for (region in config.getStringList("regions")) {

                debug("Checking if region $region exists in world-guard regions...")

                if (regionmanager.getRegion(region) == null) {
                    info("Undefined region $region.")
                    debug("Nope, continuing to next iteration.")
                }

                else debug("Yep it does, Adding region $region to list.")
                        .also{ regions.add(region) }
            }
        }
    }
    fun protected(block: Block): Boolean {

        val parents = LinkedList<String>()
        val regions = LinkedList<String>()
        val worldguard = this.worldguard ?: return true;

        for (region in worldguard.getRegionManager(block.world).getApplicableRegions(block.location)) {
            regions.add(region.id);
            var parent = region.parent;
            while (parent != null) {
                parents.add(parent.id)
                parent = parent.parent;
            }
        }

        for (parent in parents)
            regions.remove(parent)

        if(regions.isEmpty()) return false;
        return this.regions.contains(regions[0])
    }

    var excludedMaterials = HashMap<Material, Byte>();
    fun excluded(block: Block): Boolean {

        val data = excludedMaterials[block.type]
            ?: return false;

        return data == block.data;
    }
    fun loadExcludedMaterials() {
        debug("Loading excluded materials...")

        val config = this.config
            ?: return debug("Config is null");

        for (block in config.getStringList("excluded-materials")) {

            val array = block.split(":");

            debug("Checking if ${array[0]} is a valid material.")

            val m = Material.getMaterial(array[0])

            if (m == null) {
                info("Unknown material type " + array[0] + ".")
                debug("Nope, continuing to next iteration.")
            }

            else {
                val data: Byte = if (array.size > 1) parseByte(array[1]) else 0;
                debug("Adding material $m with data value $data to list.")
                excludedMaterials[m] = data
            }

        }
    }

    var timer = object: BukkitRunnable(){
        override fun run() {

            val plugin = BlockRegenerator;

            val config = plugin.config
                    ?: return BlockRegenerator.info("Config is null");

            if (!plugin.paused()) {

                val max = config.getInt("max-blocks");
                var current: Int;

                val placed = plugin.placedBlocks.toMutableList();
                val broken = plugin.brokenBlocks.toMutableList();

                plugin.debug("Performing block regeneration.");
                val startTime = System.currentTimeMillis();

                current = 0;
                for(state in placed) {
                    state.location.block.type = Material.AIR;
                    plugin.placedBlocks.removeAt(current);
                    if(current++ >= max) break;
                }

                current = 0;
                for (state in broken) {

                    plugin.debug(
                            "MATERIAL: ${state.type}," +
                                    "DATA: ${state.data}, " +
                                    "X: ${state.x}, " +
                                    "Y: ${state.y}, " +
                                    "Z: ${state.z}, " +
                                    "WORLD: ${state.world.name}"
                    )

                    state.location.block.type = state.type
                    state.location.block.data = state.data.data
                    plugin.brokenBlocks.removeAt(current);
                    if(current++ > max) break;
                }

                val endTime = System.currentTimeMillis()
                plugin.debug("Regeneration complete, took ${startTime - endTime} ms.")
            }
        }
    };
    fun paused(): Boolean = config?.getBoolean("paused") ?: false;
    fun paused(paused: Boolean) = config?.set("paused", paused);

    fun creative(): Boolean = config?.getBoolean("creative-regen-ignored") ?: false;
    fun creative(creative: Boolean) = config?.set("creative-regen-ignored", creative);

    var listener = object: Listener{

        @EventHandler
        fun onBlockBreak(e: BlockBreakEvent) {

            if (creative())
                if(e.player.gameMode == GameMode.CREATIVE)
                    return

            val config = BlockRegenerator.config ?: return debug("Config is null");

            if (!config.getBoolean("restore-destroyed"))
                return;
            if(!protected(e.block))
                return;
            if(!worlds.contains(e.block.world.name))
                return;
            if(excluded(e.block))
                return;
            if(paused())
                return;
            brokenBlocks.add(e.block.state);
        }

        @EventHandler
        fun onBlockBurn(e: BlockBurnEvent) {
            if(!protected(e.block))
                return;
            if(!worlds.contains(e.block.world.name))
                return;
            if(excluded(e.block))
                return;
            if(paused())
                return;
            brokenBlocks.add(e.block.state);
        }

        @EventHandler
        fun onBlockExplode(e: EntityExplodeEvent) {
            val config = BlockRegenerator.config ?: return debug("Config is null");

            if(paused())
                return;
            if(!worlds.contains(e.location.world.name))
                return;
            if (!config.getBoolean("restore-destroyed"))
                return;
            for(block in e.blockList()){
                if(!protected(block))
                    continue;
                if(excluded(block))
                    continue;
                brokenBlocks.add(block.state);
            }
        }

        @EventHandler
        fun onBlockPlace(e: BlockPlaceEvent) {

            if (creative())
                if(e.player.gameMode == GameMode.CREATIVE)
                    return

            val config = BlockRegenerator.config ?: return debug("Config is null");

            if (!config.getBoolean("destroy-placed"))
                return;
            if(!protected(e.block))
                return;
            if(!worlds.contains(e.block.world.name))
                return;
            if(excluded(e.block))
                return;
            if(paused())
                return;
            placedBlocks.add(e.block.state);
        }
    }
}

