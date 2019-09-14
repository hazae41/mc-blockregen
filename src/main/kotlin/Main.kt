package hazae41.minecraft.blockregen

import hazae41.minecraft.blockregen.Config.Tasks.Task
import hazae41.minecraft.kotlin.bukkit.BukkitPlugin
import hazae41.minecraft.kotlin.bukkit.ConfigSection
import hazae41.minecraft.kotlin.bukkit.PluginConfigFile
import hazae41.minecraft.kotlin.bukkit.command
import hazae41.minecraft.kotlin.bukkit.info
import hazae41.minecraft.kotlin.bukkit.init
import hazae41.minecraft.kotlin.bukkit.keys
import hazae41.minecraft.kotlin.bukkit.msg
import hazae41.minecraft.kotlin.bukkit.schedule
import hazae41.minecraft.kotlin.bukkit.update
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.ex
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

    object Tasks : ConfigSection(this, "tasks") {
        val all = config.keys.map(::Task)

        class Task(path: String) : ConfigSection(this, path) {

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

            inner class Filters : ConfigSection(this, "filters") {
                inner class Materials : ConfigSection(this, "materials") {
                    val enabled by boolean("enabled")
                    val type by string("type")
                    val list by stringList("list")
                }

                inner class Worlds : ConfigSection(this, "worlds") {
                    val enabled by boolean("enabled")
                    val type by string("type")
                    val list by stringList("list")
                }
            }
        }
    }
}

val filters = mutableListOf<Task.(Block) -> Boolean>()

class Plugin : BukkitPlugin() {
    override fun onEnable() {
        update(67964)
        init(Config)
        saveResource("example.yml", true)
        makeDatabase()
        makeTimer()
        makeCommands()
        makeDefaultFilters()
    }
}

val currentMillis get() = System.currentTimeMillis()
fun Task.shouldRestore(block: Block) = filters.all { it(block) }

fun Plugin.alert(msg: String) {
    if (msg.isNotBlank()) server.onlinePlayers.forEach { it.msg(msg) }
}

fun Plugin.makeTimer() = Config.Tasks.all.forEach { task ->
    val (regenDelayValue, regenDelayUnit) = task.regenDelay.toTimeWithUnit()
    val regenDelay = TimeUnit.SECONDS.convert(regenDelayValue, regenDelayUnit)

    val (alertDelayValue, alertDelayUnit) = task.alertBeforeDelay.toTimeWithUnit()
    val alertDelay = TimeUnit.SECONDS.convert(alertDelayValue, alertDelayUnit)

    if (alertDelay < regenDelay) schedule(
        delay = regenDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { if (!task.paused) alert(task.alertBefore) }
    )

    schedule(
        delay = regenDelay + alertDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { if (!task.paused) regen(task) }
    )
}

fun Plugin.debug(msg: String) = if (Config.debug) info(msg) else Unit

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

fun Plugin.regen(task: Task) = GlobalScope.launch {

    val api = CoreProtectAPI()

    val (minTimeValue, minTimeUnit) = task.minTime.toTimeWithUnit()
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
                task.radius,
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
            .filter { Random.nextInt(100) <= task.amount }
            .filter { world.players.all { p -> p.location.distance(it) > task.safeRadius } }
            .filter { task.shouldRestore(it.block) }
            .forEach { location ->
                if (Random.nextInt(100) >= task.efficiency)
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

    alert(task.alertAfter)
}

fun Plugin.makeCommands() = command("blockregen") { args ->

    val noTask = ex("&cYou must specify a task name")
    fun checkPerm(perm: String) {
        val noperm = ex("&cYou do not have permission")
        if (!hasPermission("blockregen.$perm")) throw noperm
    }

    catch<Exception>(::msg) {
        when (args.getOrNull(0)) {
            "force", "f" -> {
                checkPerm("force")
                val task = Task(args.getOrNull(1) ?: throw noTask)
                val start = currentMillis
                msg("&bForcing block regeneration...")
                regen(task).invokeOnCompletion {
                    it?.message?.also(::msg)
                    val time = currentMillis - start
                    msg("&bRegeneration done! Took $time ms.")
                }
            }
            "toggle", "t" -> {
                checkPerm("toggle")
                val task = Task(args.getOrNull(1) ?: throw noTask)
                task.paused = !task.paused
                when (task.paused) {
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
                msg("&b~ hazae41's BlockRegen ${description.version} ~")
                msg("&bTasks:")
                Config.Tasks.all.forEach {
                    msg("- ${it.path}: " + if (it.paused) "&cpaused" else "&aactive")
                }
                msg("&bCommands:")
                msg("- Force regen: &bforce, f <task>")
                msg("- Toggle regen: &btoggle, t <task>")
                msg("- Restart timer: &brestart, r")
            }
        }
    }
}

fun makeDefaultFilters() {

    fun Task.byMaterial(block: Block) = true.also {
        val filter = Filters().Materials()
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        val material = block.type.name.lowerCase
        when (filter.type) {
            "whitelist" -> if (material !in list) return false
            "blacklist" -> if (material in list) return false
        }
    }

    fun Task.byWorld(block: Block) = true.also {
        val filter = Filters().Worlds()
        if (!filter.enabled) return true
        val list = filter.list.map { it.lowerCase }
        val world = block.location.world.name.lowerCase
        when (filter.type) {
            "whitelist" -> if (world !in list) return false
            "blacklist" -> if (world in list) return false
        }
    }

    filters.addAll(listOf(Task::byMaterial, Task::byWorld))
}
