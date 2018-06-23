package fr.rhaz.minecraft.blockregenerator

import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.awt.SystemColor.info
import com.sun.deploy.util.GeneralUtil.getStringList
import java.lang.Byte.parseByte



class BlockRegeneratorPlugin: JavaPlugin(), Listener{
    override fun onEnable() = BlockRegenerator.init(this);
}

object BlockRegenerator{

    var plugin: BlockRegeneratorPlugin? = null;
    fun init(): Boolean = plugin != null;
    fun init(plugin: BlockRegeneratorPlugin) {
        this.plugin = plugin;
        config = loadConfig("config.yml");
        loadWorlds();
        loadWorldGuard();
        if(worldGuard()) loadRegions();
    }

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
    fun loadConfig(name: String): YamlConfiguration {
        if (!plugin!!.dataFolder.exists()) plugin!!.dataFolder.mkdir();
        val file = File(plugin!!.dataFolder, name);
        if (!file.exists()) plugin!!.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
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

    var excludedMaterials = HashMap<Material, Byte>();
    fun isExcluded(block: Block): Boolean {

        val data = excludedMaterials.get(block.getType())
            ?: return false;

        return data == block.getData();
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
                excludedMaterials.put(m, data)
            }

        }
    }
}
