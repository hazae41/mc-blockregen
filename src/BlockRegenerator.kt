@file:Suppress("UNUSED_ANONYMOUS_PARAMETER", "DEPRECATION", "UNUSED_DESTRUCTURED_PARAMETER_ENTRY")

package fr.rhaz.minecraft.blockregenerator

import com.bekvon.bukkit.residence.Residence
import com.massivecraft.factions.Board
import com.massivecraft.factions.FLocation
import com.massivecraft.factions.entity.BoardColl
import com.massivecraft.massivecore.ps.PS
import com.palmergames.bukkit.towny.Towny
import com.palmergames.bukkit.towny.`object`.TownBlock
import com.palmergames.bukkit.towny.`object`.TownyWorld
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import me.angeschossen.lands.Lands
import me.angeschossen.lands.api.LandsAPI
import me.angeschossen.lands.api.objects.Land
import me.ryanhamshire.GriefPrevention.GriefPrevention
import net.md_5.bungee.api.ChatColor.AQUA
import net.md_5.bungee.api.ChatColor.LIGHT_PURPLE
import net.md_5.bungee.api.chat.ClickEvent
import net.redstoneore.legacyfactions.locality.Locality
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Material.AIR
import org.bukkit.Material.getMaterial
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.material.MaterialData
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection
import java.util.Random
import java.util.concurrent.TimeUnit
import net.redstoneore.legacyfactions.entity.Board as LegacyBoard

lateinit var BlockRegenerator: BlockRegeneratorPlugin
class BlockRegeneratorPlugin: JavaPlugin() {

    init {
        BlockRegenerator = this
        File(".noads").delete()
    }

    override fun onEnable() {

        config = load(configfile) ?:
                return info("Config could not be loaded");

        when(config.getString("storage.type")){
            "yaml" -> data = load(datafile) ?:
                    return info("YAML data could not be loaded")
            "sqlite", "mysql" -> db = database() ?:
                    return info("SQL database could not be loaded")
        }

        findDependencies()

        getCommand("blockregen").apply {
            executor = cmd
            tabCompleter = TabCompleter{ _, _, _, _ -> cmds }
        };

        server.pluginManager.registerEvents(listener, this);

        donate(AQUA)
        update(58011, LIGHT_PURPLE)

        schedule()
    }


    val cmds = listOf("force", "toggle", "info", "clear", "debug", "reload", "donate");
    val cmd = CommandExecutor { sender, command, label, args -> true.also cmd@{

        val noperm = {sender.msg("&cYou don't have permission to do that.")}

        val help = help@{

            if(!sender.hasPermission("blockregen.help"))
                return@help noperm()

            sender.msg("&6BLOCK REGENERATOR &7v${description.version}&8:")
            cmds.forEach{ sender.msg("&7/$label &6$it") }
            val status = when(paused){
                true -> "&cdisabled"
                false -> "&aenabled"
            }
            sender.msg("&6Status: $status")
        }

        if(args.isEmpty()) return@cmd help()

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

                paused = when(paused){
                    true -> false.also {
                        sender.msg("&6Block regeneration enabled."); }
                    false -> true.also {
                        sender.msg("&6Block regeneration disabled."); }
                }
            }
            "info", "i" -> {

                if (!sender.hasPermission("blockregen.info"))
                    return@cmd noperm()

                val entries = get()

                val placed = entries.filter { e -> e.action == "placed" }.size
                val broken = entries.filter { e -> e.action == "broken" }.size

                sender.msg("&6${entries.size} entries. ($placed placed, $broken broken)")
            }
            "clear", "c" -> {

                if (!sender.hasPermission("blockregen.clear"))
                    return@cmd noperm()

                info("&6Blocks forcibly cleared by ${sender.name}");
                sender.msg("&6Block forcibly cleared")
                execute { clear() }

            }
            "debug", "d" -> {

                if (!sender.hasPermission("blockregen.debug"))
                    return@cmd noperm()

                debugging = !debugging.also {
                    sender.msg("&6Runtime debugging " + when(it){
                        false -> "enabled"
                        true -> "disabled"
                    } + ".")
                }
            }
            "reload", "r" -> {

                if (!sender.hasPermission("blockregen.reload"))
                    return@cmd noperm()

                config = load(configfile) ?:
                    return@cmd sender.msg("&cCould not load config");

                when(config.getString("storage.type")){
                    "yaml" -> data = load(datafile) ?:
                            return@cmd sender.msg("&cYAML data could not be loaded")
                    "sqlite", "mysql" -> db = database() ?:
                            return@cmd sender.msg("&cSQL database could not be loaded")
                }

                findDependencies()

                Bukkit.getScheduler().cancelTasks(this)
                schedule();
                sender.msg("&6Config reloaded")
            }
            "donate" -> """
                |  If you like my softwares or you just want to support me,
                |  I'd enjoy donations.
                |  By donating, you're going to encourage me to continue
                |  developing quality softwares.
                |  And you'll be added to the donators list!
                |  Click here to donate: http://dev.rhaz.fr/donate
                """.trimMargin().split("\n").map {
                    text(it).apply {
                        color = LIGHT_PURPLE
                        clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, "http://dev.rhaz.fr/donate")
                    }
                }.forEach { sender.spigot().sendMessage(it)
            }
            else -> help();
        }
    }}



    var debugging
        get() = config.getBoolean("extras.debugging")
        set(value){ config.apply{ set("extras.debugging", value); save(configfile)} }

    fun info(msg: String) = logger.info(msg)
    fun alert(msg: String) = server.onlinePlayers.forEach{it.msg(msg)}

    fun debug(debug: String) {
        if (!debugging) return;

        info("[BR][D] $debug");

        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission("blockregen.rd")}
            .forEach { it.msg("BR][D] $debug") }
    }



    lateinit var config: YamlConfiguration;
    val configfile by lazy{ File(dataFolder, "config.yml") }

    lateinit var data: YamlConfiguration;
    val datafile by lazy{ File(dataFolder, "resources/data.yml") }

    fun load(file: File): YamlConfiguration? {
        if (!file.parentFile.exists()) file.parentFile.mkdir();
        if (!file.exists()) saveResource(file.name, false);
        return YamlConfiguration.loadConfiguration(file) ?: null;
    }



    var dependencies = mutableListOf<String>()
    fun findDependencies() = dependencies.clear().also{
        listOf("WorldGuard", "Factions", "LegacyFactions",
        "GriefPrevention", "Residence", "Towny", "Lands").forEach c@{

            var cname = it.toLowerCase()

            if(it === "LegacyFactions")
                cname = "factions"

            if (!config.getBoolean("$cname.enabled"))
                return@c;

            server?.pluginManager?.getPlugin(it)
                    ?: return@c info("Could not load $it")

            dependencies.add(it)
        }
    }



    fun restore(block: Block): Boolean = true.also{ it ->

        run materials@{
            if(!config.getBoolean("materials.enabled"))
                return@materials

            val type = config.getString("materials.type")

            val materials = config.getStringList("materials.materials")

            val material = block.type.name

            when(type){
                "whitelist" -> if(material !in materials) return false
                "blacklist" -> if(material in materials) return false
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
                    .getApplicableRegions(block.location)
                    .map { it.id }

            when(type){
                "whitelist" -> if(pregions.intersect(bregions).isEmpty()) return false
                "blacklist" -> if(pregions.intersect(bregions).any()) return false
                else -> info("worldguard.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run factions@{

            val type = config.getString("factions.type")

            val factions = config.getStringList("factions.factions")

            val name = config.getString("factions.name").toLowerCase()

            when(name){
                "factions", "savagefactions", "factionsuuid" ->
                    if("Factions" !in dependencies) return@factions
                "legacyfactions" ->
                    if("LegacyFactions" !in dependencies) return@factions
            }

            val faction = when(name){
                "factions" -> BoardColl.get().getFactionAt(PS.valueOf(block)).name
                "savagefactions", "factionsuuid" -> Board.getInstance().getFactionAt(FLocation(block)).tag
                "legacyfactions" -> LegacyBoard.get().getFactionAt(Locality.of(block)).tag
                else -> return@factions info(
                    "factions.name is misconfigured, it should be " +
                    "Factions, SavageFactions, FactionsUUID or LegacyFactions")
            }

            when(type){
                "whitelist" -> if(faction !in factions) return false
                "blacklist" -> if(faction in factions) return false
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

        run residence@{
            if("Residence" !in dependencies) return@residence

            val type = config.getString("residence.type")

            val list = config.getStringList("residence.residences")
            val res = Residence.getInstance()?.residenceManager?.getByLoc(block.location)?.name
                ?: return@residence

            when(type){
                "whitelist" -> if(res !in list) return false
                "blacklist" -> if(res in list) return false
                else -> info("residence.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run towny@{
            if("Towny" !in dependencies) return@towny

            val type = config.getString("towny.type")

            val tblock = TownBlock(block.x, block.z, TownyWorld(block.world.name))

            val list = config.getStringList("towny.towns")

            val towns = Towny.plugin?.townyUniverse?.townsMap?.values
                ?.filter { it.hasTownBlock(tblock) }
                ?.map { it.tag }
                ?: return@towny

            when(type){
                "whitelist" -> if(towns.intersect(list).isEmpty()) return false
                "blacklist" -> if(towns.intersect(list).any()) return false
                else -> info("towny.type is misconfigured, it should be whitelist or blacklist")
            }
        }

        run lands@{
            if("Lands" !in dependencies) return@lands

            val chunk = block.chunk
            val lands = Lands.getLandsAPI()
            if(!lands.isLandsWorld(block.world.name)) return@lands
            val lworld = lands.getLandWorld(block.world.name)
            val lchunk = lworld.getLandChunk(chunk.x, chunk.z) ?: return@lands
            if(lchunk.land != null) return false
        }

    }



    fun unit(unit: String): TimeUnit = when(unit){
        "seconds", "second", "sec", "s" -> TimeUnit.SECONDS
        "minutes", "minute", "min", "m" -> TimeUnit.MINUTES
        "hours", "hour", "h"            -> TimeUnit.HOURS
        "days", "day", "d"              -> TimeUnit.DAYS
        else                            -> TimeUnit.MINUTES
    }

    fun schedule(){
        val (udelay, unit) = config.getString("regen-delay").split(" ")
        val delay = TimeUnit.SECONDS.convert(udelay.toLongOrNull() ?: return, unit(unit))

        val (uadelay, aunit) = config.getString("alert.before-delay").split(" ")
        val adelay = TimeUnit.SECONDS.convert(uadelay.toLongOrNull() ?: return, unit(aunit))

        config.getString("alert.before").also {
            if(adelay > delay) return@also info("Error: alert.before-delay is higher than regen-delay")
            if(it.isNullOrEmpty()) return@also
            server.scheduler.runTaskTimer(this, {alert(it)}, delay * 20L, delay * 20L)
        }

        regen = regen().apply{runTaskTimer(BlockRegenerator, (delay + adelay) * 20L, delay * 20L)}
    }

    lateinit var regen: BukkitRunnable;
    fun regen() = object: BukkitRunnable(){
        override fun run() {

            if(paused) return;

            val (umintime, unit) = config.getString("min-time").split(" ")
            val mintime = TimeUnit.MILLISECONDS
                .convert(umintime.toLongOrNull() ?: return, unit(unit))

            val entries = get().toMutableList()

            val maxblocks = config.getString("max-blocks")
            val max = if(maxblocks.endsWith("%")){
                val percentage = maxblocks.dropLast(1).toIntOrNull()
                        ?.takeIf { it in 0..100 }
                        ?: return info("Err: 0x163634")
                Math.floorDiv(percentage * entries.size, 100)
            } else maxblocks.toIntOrNull()
                    ?: return info("Err: 0x163635")

            debug("Performing block regeneration.");
            val startTime = System.currentTimeMillis();

            val efficiency = config.getInt("percentage-efficiency")

            for(i in 0..max){

                val first = entries.firstOrNull() ?: break
                // Get an initial action
                val futures = entries.filter { it.loc == first.loc }
                // Get its futures actions
                entries.removeAll(futures)
                // Do not process futures
                val last = futures.last()
                // Get the last action
                val block = first.loc.block

                if(!restore(block)) continue
                // Wait for the block to match the filter

                val diff = System.currentTimeMillis() - last.time
                if(diff <= mintime) continue
                // Wait for the block to be old enough

                execute{ removeAll(futures) }
                // Removes history of this block

                debug(
                    "MATERIAL: ${first.mat.name}:${first.mat.data}," +
                    "X: ${first.loc.blockX}, " +
                    "Y: ${first.loc.blockY}, " +
                    "Z: ${first.loc.blockZ}, " +
                    "WORLD: ${first.loc.world.name}"
                )

                if(Random().nextInt(100) >= efficiency) continue;
                // Do nothing, keeping the history blank

                when(first.action){ // Restore
                    "placed" -> block.type = AIR
                    "broken" -> block.apply{
                        type = first.mat
                        blockData = first.data
                    }
                }
            }

            val endTime = System.currentTimeMillis()
            debug("Regeneration complete, took ${startTime - endTime} ms.")
            config.getString("alert.after").also {
                if(!it.isNullOrEmpty()) alert(it)
            }
        }
    };



    var paused
        get() = config.getBoolean("paused")
        set(value){ config.apply {set("paused", value); save(configfile)} }

    val listener = object: Listener{

        fun creative(p: Player) = p.gameMode == GameMode.CREATIVE

        val forcelog
            get() = config.getBoolean("extras.force-log")

        val broken = object {
            val enabled
                get() = config.getBoolean("events.broken.enabled")
            val ignorecreative
                get() = config.getBoolean("events.broken.ignore-creative")
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onBlockBreak(e: BlockBreakEvent) {

            if(paused) return;
            if(!broken.enabled) return
            if(broken.ignorecreative && creative(e.player)) return
            if(!forcelog && !restore(e.block)) return

            execute { add(entry(e.block, "broken")) }
        }

        val placed = object {
            val enabled
                get() = config.getBoolean("events.placed.enabled")
            val ignorecreative
                get() = config.getBoolean("events.placed.ignore-creative")
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onBlockPlace(e: BlockPlaceEvent) {

            if(paused) return;
            if (!placed.enabled) return;
            if(placed.ignorecreative && creative(e.player)) return
            if(!forcelog && !restore(e.block)) return

            execute { add(entry(e.block, "placed"))}
        }

        val burnt = object {
            val enabled
                get() = config.getBoolean("events.burnt.enabled")
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onBlockBurn(e: BlockBurnEvent) {

            if(paused) return;
            if (!burnt.enabled) return;
            if(!forcelog && !restore(e.block)) return

            execute { add(entry(e.block, "broken"))}
        }

        val exploded = object {
            val enabled
                get() = config.getBoolean("events.exploded.enabled")
            val type
                get() = config.getString("events.exploded.type")
            val entities
                get() = config.getStringList("events.exploded.entities")
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onBlockExplode(e: EntityExplodeEvent) {

            if(paused) return;
            if (!exploded.enabled) return;

            val entity = e.entityType.name.toLowerCase()
            when(exploded.type){
                "whitelist" -> if(entity !in exploded.entities) return
                "blacklist" -> if(entity in exploded.entities) return
                else -> info("events.exploded.type is misconfigured, it should be whitelist or blacklist")
            }

            val forcelog = forcelog
            for(block in e.blockList()){
                if(!forcelog && !restore(block)) continue
                execute { add(entry(block, "broken"))}
            }
        }
    }



    data class Entry(
        val loc: Location, val time: Long, val action: String, val mat: Material, val data: BlockData)

    fun entry(block: Block, action: String) =
            Entry(block.location, System.currentTimeMillis(), action, block.type, block.blockData)

    fun ser(e: Entry): String {
        return listOf(
            e.loc.world.name, e.loc.blockX,
            e.loc.blockY, e.loc.blockZ,
            e.time, e.action,
            e.mat.name, e.data.asString
        ).joinToString("/")
    }

    fun deser(data: String): Entry? {
        val split = data.split("/")

        val world = Bukkit.getWorld(split[0])
        val x = split[1].toDouble()
        val y = split[2].toDouble()
        val z = split[3].toDouble()
        val loc = Location(world, x, y, z)

        val time = split[4].toLong()

        val action = split[5]

        lateinit var mat: Material;
        lateinit var bdata: BlockData;
        if(split[6].toIntOrNull() != null) {
            mat = Material.values().first{it.ordinal == split[6].toInt()}
            bdata = Bukkit.createBlockData(mat);
        } else {
            mat = Material.getMaterial(split[6])
            bdata = Bukkit.createBlockData(split[7])
        }

        return Entry(loc, time = time, action = action, mat = mat, data = bdata)
    }

    object Entries: Table(){
        val entry = varchar("entry", length = 50)
    }

    var db: Database? = null

    fun database() = when(config.getString("storage.type")){
        "sqlite" -> Database.connect("jdbc:sqlite:plugins/BlockRegenerator/data.db", "org.sqlite.JDBC")
        "mysql" -> {
            val host = config.getString("storage.mysql.host")
            val db = config.getString("storage.mysql.database")
            val user = config.getString("storage.mysql.user")
            val password = config.getString("storage.mysql.password")
            Database.connect(
                "jdbc:mysql://$host/$db",
                driver = "com.mysql.jdbc.Driver",
                user = user,
                password = password)
        }
        else -> null
    }

    fun get(): MutableList<Entry> = when(config.getString("storage.type")){
        "yaml" -> data.getStringList("data").mapNotNull{e -> deser(e)}
        "mysql", "sqlite" -> try{
            transaction(Connection.TRANSACTION_SERIALIZABLE, 1, db) {
                create (Entries)
                Entries.selectAll().mapNotNull{deser(it[Entries.entry])}
            }
        } catch (e: Exception){info("Could not connect to SQL database!"); emptyList<Entry>()}
        else -> emptyList()
    }.toMutableList()

    fun save(list: List<Entry>) {
        when(config.getString("storage.type")){
            "yaml" -> data.apply{
                set("data", list.map{ser(it)})
                save(datafile)
            }
            "sqlite", "mysql" -> try{
                transaction(Connection.TRANSACTION_SERIALIZABLE, 1, db) {
                    create (Entries)
                    Entries.deleteAll()
                    Entries.batchInsert(list.map{ser(it)})
                    {this[Entries.entry] = it}
                }
            } catch (e: Exception){info("Could not connect to SQL database!"); emptyList<Entry>()}
        }
    }

    fun execute(lambda: MutableList<Entry>.() -> Unit) = save(get().apply(lambda))
}
