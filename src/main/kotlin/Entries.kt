package hazae41.minecraft.blockregen

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.material.MaterialData
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

fun Plugin.makeDatabase() {
    val dbFile = File(dataFolder, "blocks.db")
    Database.connect("jdbc:sqlite:${dbFile.path}", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction  { SchemaUtils.create(Entries) }
}

class BlockSnapshot(block: Block){
    val millis = currentMillis
    val location = block.location
    val data = block.state.data
}

fun Plugin.addEntry(snap: BlockSnapshot, _action: String)
    = transaction{
        val last = Entry.all().lastOrNull()?.id?.value
        Entry.new((last?:0)+1) {
            millis = snap.millis
            location = snap.location
            action = _action
            data = snap.data
        }
    }

fun Plugin.addEntry(block: Block, _action: String) = addEntry(BlockSnapshot(block), _action)

object Entries: IntIdTable(){
    val millis = long("millis")
    val world = varchar("world",20)
    val x = integer("x")
    val y = integer("y")
    val z = integer("z")
    val action = varchar("action",20)
    val data = varchar("data",200)
}

class Entry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Entry>(Entries)
    var _world by Entries.world
    var x by Entries.x
    var y by Entries.y
    var z by Entries.z
    var _data by Entries.data
    var millis by Entries.millis
    var action by Entries.action
}

var Entry.world: World
    get() = Bukkit.getWorld(_world)
    set(value){ _world = value.name }

var Entry.location
    get() = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    set(value){
        world = value.world
        x = value.blockX
        y = value.blockY
        z = value.blockZ
    }

var Entry.data: MaterialData
    get(){
        val (type, data) = _data.split(":")
        val mat = Material.getMaterial(type)
        return MaterialData(mat, data.toByte())
    }
    set(value){_data = "${value.itemType.name}:${value.data}" }