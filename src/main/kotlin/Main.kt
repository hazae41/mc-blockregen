package hazae41.minecraft.blockregen

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.lowerCase
import hazae41.minecraft.kotlin.toTimeWithUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority.MONITOR
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.TimeUnit

object Config: PluginConfigFile("config"){
    override var minDelay = 5000L

    val alertBefore by string("alert.before")
    val alertBeforeDelay by string("alert.before-delay")
    val alertAfter by string("alert.after")

    val regenDelay by string("regen-delay")
    val minTime by string("min-time")
    val maxBlocks by string("max-blocks")
    val efficiency by int("efficiency")

    var paused by boolean("paused")
    val forceLog by boolean("extras.force-log")

    object events: ConfigSection(this, "events"){
        object broken: ConfigSection(this, "broken"){
            val enabled by boolean("enabled")
            val ignoreCreative by boolean("ignore-creative")
        }
        object placed: ConfigSection(this, "placed"){
            val enabled by boolean("enabled")
            val ignoreCreative by boolean("ignore-creative")
        }
        object burnt: ConfigSection(this, "burnt"){
            val enabled by boolean("enabled")
        }
        object exploded: ConfigSection(this, "exploded"){
            val enabled by boolean("enabled")
            val type by string("type")
            val list by stringList("list")
        }
    }

    object controllers: ConfigSection(this, "controllers"){
        object materials: ConfigSection(this, "materials"){
            val enabled by boolean("enabled")
            val type by string("type")
            val list by stringList("list")
        }
        object worlds: ConfigSection(this, "worlds"){
            val enabled by boolean("enabled")
            val type by string("type")
            val list by stringList("list")
        }
    }
}

val controllers = mutableListOf<(Block) -> Boolean>()

class Plugin: BukkitPlugin() {
    override fun onEnable() {
        update(67964)
        init(Config)
        makeDatabase()
        makeTimer()
        makeListeners()
        makeCommands()
        makeControllers()
    }
}

val currentMillis get() = System.currentTimeMillis()
val Player.isCreative get() = gameMode === GameMode.CREATIVE
fun shouldRestore(block: Block) = controllers.all { it(block) }
fun Plugin.alert(msg: String) = server.onlinePlayers.forEach{it.msg(msg)}

fun Plugin.makeTimer(){
    val (regenDelayValue, regenDelayUnit) = Config.regenDelay.toTimeWithUnit()
    val regenDelay = TimeUnit.SECONDS.convert(regenDelayValue, regenDelayUnit)

    val (alertDelayValue, alertDelayUnit) = Config.alertBeforeDelay.toTimeWithUnit()
    val alertDelay = TimeUnit.SECONDS.convert(alertDelayValue, alertDelayUnit)

    Config.alertBefore.also { msg ->
        if(alertDelay > regenDelay) return@also
        if(msg.isEmpty()) return@also
        schedule(
            delay = regenDelay,
            period = regenDelay,
            unit = TimeUnit.SECONDS,
            callback = { alert(msg) }
        )
    }

    schedule(
        delay = regenDelay + alertDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { regen() }
    )
}

fun Plugin.makeListeners(){
    listen<BlockBreakEvent>(MONITOR){
        if(!Config.paused)
        if(Config.events.broken.enabled)
        if(!it.player.isCreative || !Config.events.broken.ignoreCreative)
        if(Config.forceLog || shouldRestore(it.block))
        addEntry(it.block, "broken")
    }

    listen<BlockPlaceEvent>(MONITOR){
        if(!Config.paused)
        if(Config.events.placed.enabled)
        if(!it.player.isCreative || !Config.events.placed.ignoreCreative)
        if(Config.forceLog || shouldRestore(it.block))
        addEntry(it.block, "placed")
    }

    listen<BlockBurnEvent>(MONITOR){
        if(!Config.paused)
        if(Config.events.burnt.enabled)
        if(Config.forceLog || shouldRestore(it.block))
        addEntry(it.block, "broken")
    }

    listen<EntityExplodeEvent>(MONITOR){
        if(Config.paused) return@listen
        val config = Config.events.exploded
        if(!config.enabled) return@listen
        val entity = it.entityType.name.lowerCase
        val list = config.list.map { it.lowerCase }
        when(config.type){
            "whitelist" -> if(entity !in list) return@listen
            "blacklist" -> if(entity in list) return@listen
        }

        val snaps = it.blockList().let {
            if(Config.forceLog) it else it.filter(::shouldRestore)
        }.map(::BlockSnapshot)

        schedule(async = true) {
            for(snap in snaps) addEntry(snap, "broken")
        }
    }
}

fun Plugin.regen() = transaction {
    if(Config.paused) return@transaction

    val (minTimeValue, minTimeUnit) = Config.minTime.toTimeWithUnit()
    val minTime = TimeUnit.MILLISECONDS.convert(minTimeValue, minTimeUnit)

    val entries = Entry.all().toMutableList()

    val maxBlocks =
        if(!Config.maxBlocks.endsWith("%"))
            Config.maxBlocks.toInt()
        else {
            val percent = Config.maxBlocks.dropLast(1).toInt()
            Math.floorDiv(percent * entries.count(), 100)
        }

    info("Performing block regeneration...")
    val startTime = currentMillis

    var i = 0
    while(i in 0..(maxBlocks-1)){
        // Get an initial action
        val first = entries.firstOrNull() ?: break
        // Do not process futures
        val futures = entries.filter { it.location == first.location }
        entries.removeAll(futures)
        // Get the last action
        val last = futures.last()
        // Check if the block matches the filter
        val block = first.location.block
        if(!shouldRestore(block)) continue
        // Check if the block is old enough
        val diff = currentMillis - last.millis
        if(diff <= minTime) continue
        // Removes history of this block
        futures.forEach{it.delete()}
        // Go to the next block
        i++
        // If no chance, do nothing, keeping the history blank
        if(Random().nextInt(100) >= Config.efficiency) continue
        // Restore
        when(first.action){ // Restore
            "placed" -> block.type = Material.AIR
            "broken" -> block.apply{
                type = first.data.itemType
                data = first.data.data
            }
        }
    }

    val endTime = currentMillis
    info("Regeneration complete, took ${endTime - startTime} ms.")

    alert(Config.alertAfter)
}

fun Plugin.makeCommands() = command("blockregen"){ args ->
    val noperm = Exception("&cYou do not have permission")
    fun checkPerm(perm: String) { if(!hasPermission("blockregen.$perm")) throw noperm }

    catch<Exception>(::msg){
        when(args.getOrNull(0)){
            "force", "f" -> {
                checkPerm("force")
                msg("&bForcing block regeneration...")
                regen()
            }
            "info", "i" -> {
                checkPerm("info")
                transaction{
                    val entries = Entry.all()
                    val placed = entries.filter { it.action == "placed" }.size
                    val broken = entries.filter { it.action == "broken" }.size
                    msg("&bTotal: ${entries.count()}")
                    msg("&bPlaced: $placed")
                    msg("&bBroken: $broken")
                }
            }
            "clear", "c" -> {
                checkPerm("clear")
                transaction {
                    Entry.all().forEach { it.delete() }
                }
                msg("&6Block forcibly cleared")
            }
            "toggle", "t" -> {
                checkPerm("toggle")
                Config.paused = !Config.paused
                when(Config.paused){
                    true -> msg("&cRegeneration is now disabled")
                    false -> msg("&aRegeneration is now enabled")
                }
            }
            "restart", "r" -> {
                checkPerm("restart")
                server.scheduler.cancelTasks(this@makeCommands)
                makeTimer()
                msg("&bTimer restarted")
            }
            else -> {
                checkPerm("help")
                msg("&bhazae41's BlockRegen version ${description.version}")
                when(Config.paused){
                    true -> msg("&cRegeneration is disabled")
                    false -> msg("&aRegeneration is enabled")
                }
                msg("&bForce regen: force, f")
                msg("&bToggle regen: toggle, t")
                msg("&bRestart timer: restart, r")
                msg("&bDatabase infos: info, i")
                msg("&bClear database: clear, c")
            }
        }
    }
}

fun Plugin.makeControllers() {

    fun byMaterial(block: Block) = true.also {
        val config = Config.controllers.materials
        if(!config.enabled) return true
        val list = config.list.map{ it.lowerCase }
        val material = block.type.name.lowerCase
        when(config.type){
            "whitelist" -> if(material !in list) return false
            "blacklist" -> if(material in list) return false
        }
    }

    fun byWorld(block: Block) = true.also {
        val config = Config.controllers.worlds
        if(!config.enabled) return true
        val list = config.list.map{ it.lowerCase }
        val world = block.location.world.name.lowerCase
        when(config.type){
            "whitelist" -> if(world !in list) return false
            "blacklist" -> if(world in list) return false
        }
    }

    controllers.addAll(listOf(::byMaterial, ::byWorld))
}
