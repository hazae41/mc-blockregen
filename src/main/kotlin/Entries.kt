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
    val dbFile = File(dataFolder, "ignored.db")
    Database.connect("jdbc:sqlite:${dbFile.path}", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction  { SchemaUtils.create(Entries) }
}

fun Plugin.addEntry(loc: Location)
    = transaction{
        val last = Entry.all().lastOrNull()?.id?.value
        Entry.new((last?:0)+1) {
            millis = currentMillis
            location = loc
        }
    }

object Entries: IntIdTable(){
    val millis = long("millis")
    val world = varchar("world",20)
    val x = integer("x")
    val y = integer("y")
    val z = integer("z")
}

class Entry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Entry>(Entries)
    var _world by Entries.world
    var x by Entries.x
    var y by Entries.y
    var z by Entries.z
    var millis by Entries.millis
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