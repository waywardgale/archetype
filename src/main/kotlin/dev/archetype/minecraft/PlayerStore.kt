package dev.archetype.minecraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.archetype.runtime.PlayerRecord
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Versioned player data. Unknown definition IDs stay dormant in the saved maps. */
class PlayerStore(private val directory: Path) {
    fun load(id: UUID): PlayerRecord? {
        val path = directory.resolve("$id.json")
        if (!Files.exists(path)) return PlayerRecord()
        return try {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1_048_576)
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            require(json.get("version").asInt == 1)
            val record = PlayerRecord()
            json.getAsJsonArray("owned_classes").forEach { record.ownedClasses += checkedId(it.asString) }
            json.getAsJsonArray("active_classes").forEach { record.activeClasses += checkedId(it.asString) }
            json.getAsJsonObject("resources").entrySet().forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                val amount = value.asDouble
                require(amount.isFinite())
                record.resources[checkedKey(key)] = amount
            }
            json.getAsJsonObject("cooldowns").entrySet().forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]+")))
                val ticks = value.asInt
                require(ticks in 0..72_000)
                record.cooldowns[checkedKey(key)] = ticks
            }
            json.getAsJsonObject("regeneration_timers")?.entrySet()?.forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]+")))
                val ticks = value.asInt
                require(ticks in 0..72_000)
                record.regenerationTimers[checkedKey(key)] = ticks
            }
            record
        } catch (_: Exception) { null }
    }

    fun save(id: UUID, record: PlayerRecord) {
        Files.createDirectories(directory)
        val json = JsonObject().apply {
            addProperty("version", 1)
            add("owned_classes", com.google.gson.JsonArray().also { array -> record.ownedClasses.forEach(array::add) })
            add("active_classes", com.google.gson.JsonArray().also { array -> record.activeClasses.forEach(array::add) })
            add("resources", JsonObject().also { obj -> record.resources.forEach { (key, value) -> obj.addProperty(key, value) } })
            add("cooldowns", JsonObject().also { obj -> record.cooldowns.forEach { (key, value) -> obj.addProperty(key, value) } })
            add("regeneration_timers", JsonObject().also { obj -> record.regenerationTimers.forEach { (key, value) -> obj.addProperty(key, value) } })
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576) { "player record is too large" }
        val target = directory.resolve("$id.json")
        val temporary = Files.createTempFile(directory, "$id-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun checkedId(value: String): String {
        require(value.length <= 128 && ID.matches(value))
        return value
    }
    private fun checkedKey(value: String): String {
        require(value.length <= 256 && value.all { it.isLetterOrDigit() || it in "_:|./-#" })
        return value
    }
    private companion object { val ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+") }
}
