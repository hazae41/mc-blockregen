package hazae41.minecraft.blockregen

import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.command
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.bukkit.msg
import hazae41.minecraft.kotlin.bukkit.schedule
import hazae41.minecraft.kotlin.bukkit.update
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.lowerCase
import hazae41.minecraft.kotlin.toTimeWithUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.coreprotect.CoreProtectAPI
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace.DOWN
import org.bukkit.block.BlockFace.EAST
import org.bukkit.block.BlockFace.NORTH
import org.bukkit.block.BlockFace.SELF
import org.bukkit.block.BlockFace.SOUTH
import org.bukkit.block.BlockFace.UP
import org.bukkit.block.BlockFace.WEST
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Config : PluginConfigFile("config") {
    override var minDelay = 5000L

    val debug by boolean("debug")
    var paused by boolean("paused")

    val alertBefore by string("alert.before")
    val alertBeforeDelay by string("alert.before-delay")
    val alertAfter by string("alert.after")

    val regenDelay by string("regen-delay")
    val minTime by string("min-time")

    val amount by int("amount")
    val efficiency by int("efficiency")
    val radius by int("radius")
    val safeRadius by int("safe-radius")

    object Filters : ConfigSection(this, "filters") {
        object Materials : ConfigSection(this, "materials") {
            val enabled by boolean("enabled")
            val type by string("type")
            val list by stringList("list")
        }

        object Worlds : ConfigSection(this, "worlds") {
            val enabled by boolean("enabled")
            val type by string("type")
            val list by stringList("list")
        }
    }
}

val filters = mutableListOf<(Block) -> Boolean>()

class Plugin : BukkitPlugin() {
    override fun onEnable() {
        update(67964)
        init(Config)
        makeDatabase()
        makeTimer()
        makeCommands()
        makeDefaultFilters()
    }
}

val currentMillis get() = System.currentTimeMillis()
fun shouldRestore(block: Block) = filters.all { it(block) }

fun Plugin.alert(msg: String) {
    if (msg.isNotBlank()) server.onlinePlayers.forEach { it.msg(msg) }
}

fun Plugin.makeTimer() {
    val (regenDelayValue, regenDelayUnit) = Config.regenDelay.toTimeWithUnit()
    val regenDelay = TimeUnit.SECONDS.convert(regenDelayValue, regenDelayUnit)

    val (alertDelayValue, alertDelayUnit) = Config.alertBeforeDelay.toTimeWithUnit()
    val alertDelay = TimeUnit.SECONDS.convert(alertDelayValue, alertDelayUnit)

    if (alertDelay < regenDelay) schedule(
        delay = regenDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { if (!Config.paused) alert(Config.alertBefore) }
    )

    schedule(
        delay = regenDelay + alertDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { if (!Config.paused) regen() }
    )
}

fun Plugin.debug(msg: String) {
    if (Config.debug) info(msg)
}

val faces = listOf(
    SELF,
    UP,
    DOWN,
    EAST,
    WEST,
    NORTH,
    SOUTH
)

fun Location.around() = faces.map { block.getRelative(it).location }

fun Plugin.regen() = GlobalScope.launch {

    val api = CoreProtectAPI()

    val (minTimeValue, minTimeUnit) = Config.minTime.toTimeWithUnit()
    val minTime = TimeUnit.SECONDS.convert(minTimeValue, minTimeUnit)

    server.worlds.forEach { world ->

        val lookup = world.players.flatMap { player ->
            api.performLookup(
                Int.MAX_VALUE,
                null,
                null,
                null,
                null,
                null,
                Config.radius,
                player.location
            ) ?: return@forEach
        }

        if (lookup.isEmpty()) return@forEach

        debug("Database size: " + lookup.size)

        val ignored = transaction { Entry.all().toList() }
        val toIgnore = mutableListOf<Location>()

        lookup
            .asSequence()
            .map { api.parseResult(it) }
            .filter { !it.isRolledBack }
            .map { Location(world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
            .distinct()
            .filter {
                it.around().all {
                    api.blockLookup(it.block, minTime.toInt())
                        .asSequence()
                        .map { api.parseResult(it) }
                        .none { !it.isRolledBack }
                }
            }
            .filter { loc -> ignored.all { it.location != loc } }
            .filter { it.chunk.isLoaded }
            .filter { Random.nextInt(100) <= Config.amount }
            .filter { world.players.all { p -> p.location.distance(it) > Config.safeRadius } }
            .filter { shouldRestore(it.block) }
            .forEach { location ->
                if (Random.nextInt(100) >= Config.efficiency)
                    toIgnore += location
                else api.performRollback(
                    Int.MAX_VALUE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    location
                )
            }

        debug("Ignored blocks: " + toIgnore.size)
        toIgnore.forEach { addEntry(it) }
    }

    alert(Config.alertAfter)
}

fun Plugin.makeCommands() = command("blockregen") { args ->
    val noperm = Exception("&cYou do not have permission")
    fun checkPerm(perm: String) {
        if (!hasPermission("blockregen.$perm")) throw noperm
    }

    catch<Exception>(::msg) {
        when (args.getOrNull(0)) {
            "force", "f" -> {
                checkPerm("force")
                val start = currentMillis
                msg("&bForcing block regeneration...")
                regen().invokeOnCompletion {
                    it?.message?.also(::msg)
                    val time = currentMillis - start
                    msg("&bRegeneration done! Took $time ms.")
                }
            }
            "toggle", "t" -> {
                checkPerm("toggle")
                Config.paused = !Config.paused
                when (Config.paused) {
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
                when (Config.paused) {
                    true -> msg("&cRegeneration is disabled")
                    false -> msg("&aRegeneration is enabled")
                }
                msg("&bForce regen: force, f")
                msg("&bToggle regen: toggle, t")
                msg("&bRestart timer: restart, r")
            }
        }
    }
}

fun makeDefaultFilters() {

    fun byMaterial(block: Block) = true.also {
        val config = Config.Filters.Materials
        if (!config.enabled) return true
        val list = config.list.map { it.lowerCase }
        val material = block.type.name.lowerCase
        when (config.type) {
            "whitelist" -> if (material !in list) return false
            "blacklist" -> if (material in list) return false
        }
    }

    fun byWorld(block: Block) = true.also {
        val config = Config.Filters.Worlds
        if (!config.enabled) return true
        val list = config.list.map { it.lowerCase }
        val world = block.location.world.name.lowerCase
        when (config.type) {
            "whitelist" -> if (world !in list) return false
            "blacklist" -> if (world in list) return false
        }
    }

    filters.addAll(listOf(::byMaterial, ::byWorld))
}
