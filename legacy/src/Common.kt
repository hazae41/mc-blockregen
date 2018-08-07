package fr.rhaz.minecraft.blockregenerator

import com.google.gson.JsonParser
import net.md_5.bungee.api.chat.TextComponent
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL

fun text(string: String) = TextComponent(string.replace("&", "§"))

infix fun String.newerThan(v: String): Boolean = false.also{
    val s1 = split('.');
    val s2 = v.split('.');
    for(i in 0..Math.max(s1.size,s2.size)){
        if(i !in s1.indices) return false; // If there is no digit, v2 is automatically bigger
        if(i !in s2.indices) return true; // if there is no digit, v1 is automatically bigger
        if(s1[i] > s2[i]) return true;
        if(s1[i] < s2[i]) return false;
    }
}

fun spiget(id: Int): String = try {
    val base = "https://api.spiget.org/v2/resources/"
    val conn = URL("$base$id/versions?size=100").openConnection()
    val json = InputStreamReader(conn.inputStream).let{ JsonParser().parse(it).asJsonArray}
    json.last().asJsonObject["name"].asString
} catch(e: IOException) {e.printStackTrace(); "0"}