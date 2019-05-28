package hazae41.minecraft.blockregen

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.lowerCase
import hazae41.minecraft.kotlin.toTimeWithUnit
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

object Config: ConfigFile("config"){
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
        update(58011)
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
fun Plugin.shouldRestore(block: Block) = controllers.any { it(block) }
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
            else -> return@listen
        }
        for(block in it.blockList()){
            if(Config.forceLog || shouldRestore(block))
            addEntry(block, "broken")
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

    for(i in 0..(maxBlocks-1)){
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
            "reload", "r" -> {
                checkPerm("reload")
                Config.reload()
                server.scheduler.cancelTasks(this@makeCommands)
                makeTimer()
                msg("&bConfig reloaded")
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
                msg("&bReload config: reload, r")
                msg("&bDatabase infos: info, i")
                msg("&bClear database: clear, c")
            }
        }
    }
}

fun Plugin.makeControllers() {

    fun byMaterial(block: Block) = false.also {
        val config = Config.controllers.materials
        if(!config.enabled) return false
        val list = config.list.map{ it.lowerCase }
        val material = block.type.name.lowerCase
        when(config.type){
            "whitelist" -> if(material in list) return true
            "blacklist" -> if(material !in list) return true
        }
    }

    fun byWorld(block: Block) = false.also {
        val config = Config.controllers.worlds
        if(!config.enabled) return false
        val list = config.list.map{ it.lowerCase }
        val world = block.location.world.name.lowerCase
        when(config.type){
            "whitelist" -> if(world in list) return true
            "blacklist" -> if(world !in list) return true
        }
    }

    controllers.addAll(listOf(::byMaterial, ::byWorld))
}