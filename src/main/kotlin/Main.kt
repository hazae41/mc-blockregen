package hazae41.minecraft.blockregen

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.lowerCase
import hazae41.minecraft.kotlin.toTimeWithUnit
import jdk.nashorn.internal.objects.Global
import kotlinx.coroutines.*
import net.coreprotect.CoreProtectAPI
import org.bukkit.Location
import org.bukkit.block.Block
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Config: PluginConfigFile("config"){
    override var minDelay = 5000L

    var paused by boolean("paused")

    val alertBefore by string("alert.before")
    val alertBeforeDelay by string("alert.before-delay")
    val alertAfter by string("alert.after")

    val regenDelay by string("regen-delay")
    val minTime by string("min-time")

    val amount by int("amount")
    val efficiency by int("efficiency")
    val radius by int("radius")

    object filters: ConfigSection(this, "filters"){
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

val filters = mutableListOf<(Block) -> Boolean>()
val controllers = mutableListOf<(Block) -> Boolean>()

class Plugin: BukkitPlugin() {
    override fun onEnable() {
        update(67964)
        init(Config)
        makeDatabase()
        makeTimer()
        makeCommands()
        makeFilters()
    }
}

val currentMillis get() = System.currentTimeMillis()
fun shouldRestore(block: Block) = (filters+controllers).all { it(block) }

fun Plugin.alert(msg: String) {
    if(msg.isNotBlank()) server.onlinePlayers.forEach{it.msg(msg)}
}

fun Plugin.makeTimer(){
    val (regenDelayValue, regenDelayUnit) = Config.regenDelay.toTimeWithUnit()
    val regenDelay = TimeUnit.SECONDS.convert(regenDelayValue, regenDelayUnit)

    val (alertDelayValue, alertDelayUnit) = Config.alertBeforeDelay.toTimeWithUnit()
    val alertDelay = TimeUnit.SECONDS.convert(alertDelayValue, alertDelayUnit)

    if(alertDelay < regenDelay) schedule(
        delay = regenDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { alert(Config.alertBefore) }
    )

    schedule(
        delay = regenDelay + alertDelay,
        period = regenDelay,
        unit = TimeUnit.SECONDS,
        callback = { regen() }
    )
}

fun Plugin.regen() = GlobalScope.launch {
    if(Config.paused) return@launch

    val api = CoreProtectAPI()

    val (minTimeValue, minTimeUnit) = Config.minTime.toTimeWithUnit()
    val minTime = TimeUnit.MILLISECONDS.convert(minTimeValue, minTimeUnit)

    val ignored = transaction { Entry.all() }

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

        if(lookup.isEmpty()) return@forEach

        val results = lookup.map { api.parseResult(it) }

        var resultsByBlock = withContext(Dispatchers.Unconfined){
            results.groupBy { world.getBlockAt(it.x, it.y, it.z) }
        }

        resultsByBlock = transaction {
            resultsByBlock.filterKeys { block -> ignored.all { it.location != block.location } }
        }

        val newIgnored = mutableListOf<Location>()

        resultsByBlock.forEach { block, bresults ->
            if(Random.nextInt(100) >= Config.amount) return@forEach
            if(!shouldRestore(block)) return@forEach
            if(bresults.any { it.time < minTime}) return@forEach

            if(Random.nextInt(100) >= Config.efficiency)
                newIgnored += block.location

            else schedule(async = true){
                api.performRollback(
                    Integer.MAX_VALUE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    block.location
                )
            }
        }

        transaction { newIgnored.forEach{ addEntry(it) } }
    }

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
            }
        }
    }
}

fun Plugin.makeFilters() {

    fun byMaterial(block: Block) = true.also {
        val config = Config.filters.materials
        if(!config.enabled) return true
        val list = config.list.map{ it.lowerCase }
        val material = block.type.name.lowerCase
        when(config.type){
            "whitelist" -> if(material !in list) return false
            "blacklist" -> if(material in list) return false
        }
    }

    fun byWorld(block: Block) = true.also {
        val config = Config.filters.worlds
        if(!config.enabled) return true
        val list = config.list.map{ it.lowerCase }
        val world = block.location.world.name.lowerCase
        when(config.type){
            "whitelist" -> if(world !in list) return false
            "blacklist" -> if(world in list) return false
        }
    }

    filters.addAll(listOf(::byMaterial, ::byWorld))
}
