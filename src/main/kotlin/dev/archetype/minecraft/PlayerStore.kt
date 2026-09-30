package dev.archetype.minecraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.archetype.runtime.PlayerRecord
import dev.archetype.runtime.ChargeState
import dev.archetype.runtime.TrackProgress
import dev.archetype.runtime.PointPurchase
import dev.archetype.runtime.PointPayment
import dev.archetype.definitions.RechargeMode
import dev.archetype.definitions.StateValue
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
            json.getAsJsonObject("charges")?.entrySet()?.let { entries ->
                // ASVS 1.5.2, 2.2.1: saved state uses a fixed object shape and bounded numeric fields.
                require(entries.size <= 256)
                entries.forEach { (key, value) ->
                    val item = value.asJsonObject
                    require(item.keySet() == setOf("capacity", "available", "mode", "timers"))
                    val capacity = boundedInteger(item.get("capacity"), 1, 16)
                    val available = boundedInteger(item.get("available"), 0, capacity)
                    val mode = when (item.get("mode").asString) {
                        "sequential" -> RechargeMode.SEQUENTIAL
                        "parallel" -> RechargeMode.PARALLEL
                        else -> error("unknown recharge mode")
                    }
                    val timers = item.getAsJsonArray("timers").map { boundedInteger(it, 1, 72_000) }.toMutableList()
                    require(timers.size <= capacity && (mode != RechargeMode.SEQUENTIAL || timers.size <= 1))
                    record.charges[checkedKey(key)] = ChargeState(capacity, available, mode, timers)
                }
            }
            json.getAsJsonObject("states")?.entrySet()?.let { entries ->
                // ASVS 1.5.2, 2.2.1: durable state is bounded and tagged; saved values never choose their own type.
                require(entries.size <= 8192)
                entries.forEach { (key, value) ->
                    val item = value.asJsonObject
                    require(item.keySet() == setOf("type", "value"))
                    val scalar = when (item.get("type").asString) {
                        "number" -> {
                            val number = item.get("value").asDouble
                            require(number.isFinite() && number in -1_000_000_000.0..1_000_000_000.0)
                            StateValue.Number(number)
                        }
                        "boolean" -> {
                            require(item.get("value").isJsonPrimitive && item.get("value").asJsonPrimitive.isBoolean)
                            StateValue.Flag(item.get("value").asBoolean)
                        }
                        "enum" -> StateValue.Mode(item.get("value").asString.also { require(it.length <= 64 && it.matches(LOCAL)) })
                        else -> error("unknown state value type")
                    }
                    record.persistentStates[checkedStateKey(key)] = scalar
                }
            }
            json.getAsJsonObject("progression")?.entrySet()?.let { entries ->
                // ASVS 1.5.2, 2.2.1: saved earnings and one-time award receipts are bounded and server-owned.
                require(entries.size <= 2048)
                entries.forEach { (key, value) ->
                    val item = value.asJsonObject
                    require(item.keySet() == setOf("xp", "awarded", "points") ||
                        item.keySet() == setOf("xp", "awarded", "points", "purchases"))
                    val xpText = item.get("xp").asString
                    require(xpText.matches(Regex("[0-9]+")))
                    val xp = xpText.toLongOrNull() ?: error("XP is out of range")
                    require(xp in 0..1_000_000_000_000L)
                    val awarded = item.getAsJsonArray("awarded").map { receipt -> checkedAward(receipt.asString) }
                    require(awarded.size <= 2048 && awarded.distinct().size == awarded.size)
                    val points = item.getAsJsonObject("points")
                    require(points.size() <= 128)
                    val budgets = linkedMapOf<String, Int>()
                    points.entrySet().forEach { (budget, amount) ->
                        require(budget.matches(LOCAL))
                        budgets[budget] = boundedInteger(amount, 0, 1_000_000)
                    }
                    val purchases = linkedMapOf<String, PointPurchase>()
                    item.getAsJsonObject("purchases")?.entrySet()?.let { entries ->
                        require(entries.size <= 128)
                        entries.forEach { (purchaseKey, raw) ->
                            val purchase = raw.asJsonObject
                            val payments = when (purchase.keySet()) {
                                setOf("budget", "prices") -> {
                                    val budget = purchase.get("budget").asString
                                    require(budget.isEmpty() || budget.matches(LOCAL))
                                    purchase.getAsJsonArray("prices").map { PointPayment(budget, boundedInteger(it, 0, 1000)) }
                                }
                                setOf("payments") -> purchase.getAsJsonArray("payments").map { payment ->
                                    val entry = payment.asJsonObject
                                    require(entry.keySet() == setOf("budget", "amount"))
                                    val budget = entry.get("budget").asString
                                    require(budget.isEmpty() || budget.matches(LOCAL))
                                    PointPayment(budget, boundedInteger(entry.get("amount"), 0, 1000))
                                }
                                else -> error("invalid talent purchase record")
                            }
                            require(payments.size in 1..16)
                            purchases[checkedPurchaseKey(purchaseKey)] = PointPurchase(payments.toMutableList())
                        }
                    }
                    record.progression[checkedProgressKey(key)] = TrackProgress(xp, awarded.toMutableSet(), budgets, purchases)
                }
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
            add("charges", JsonObject().also { obj -> record.charges.forEach { (key, state) ->
                obj.add(key, JsonObject().also { item ->
                    item.addProperty("capacity", state.capacity)
                    item.addProperty("available", state.available)
                    item.addProperty("mode", state.mode.name.lowercase())
                    item.add("timers", com.google.gson.JsonArray().also { array -> state.timers.forEach(array::add) })
                })
            } })
            add("states", JsonObject().also { obj -> record.persistentStates.forEach { (key, value) ->
                obj.add(key, JsonObject().also { item -> when (value) {
                    is StateValue.Number -> { item.addProperty("type", "number"); item.addProperty("value", value.value) }
                    is StateValue.Flag -> { item.addProperty("type", "boolean"); item.addProperty("value", value.value) }
                    is StateValue.Mode -> { item.addProperty("type", "enum"); item.addProperty("value", value.value) }
                } })
            } })
            add("progression", JsonObject().also { obj -> record.progression.forEach { (key, state) ->
                obj.add(key, JsonObject().also { item ->
                    item.addProperty("xp", state.earnedXp)
                    item.add("awarded", com.google.gson.JsonArray().also { array -> state.awarded.forEach(array::add) })
                    item.add("points", JsonObject().also { budgets -> state.points.forEach { (name, amount) -> budgets.addProperty(name, amount) } })
                    item.add("purchases", JsonObject().also { entries -> state.purchases.forEach { (purchaseKey, purchase) ->
                        entries.add(purchaseKey, JsonObject().also { value ->
                            value.add("payments", com.google.gson.JsonArray().also { array -> purchase.payments.forEach { payment ->
                                array.add(JsonObject().also { item ->
                                    item.addProperty("budget", payment.budget)
                                    item.addProperty("amount", payment.amount)
                                })
                            } })
                        })
                    } })
                })
            } })
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
    private fun checkedStateKey(value: String): String {
        val parts = value.split('|')
        require(parts.size == 3 && value.length <= 384)
        require((parts[0] == "player" || ID.matches(parts[0])) && ID.matches(parts[1]) && parts[2].matches(LOCAL))
        return value
    }
    private fun checkedProgressKey(value: String): String {
        val parts = value.split('|')
        require(parts.size == 2 && value.length <= 256)
        require((parts[0] == "player" || ID.matches(parts[0])) && ID.matches(parts[1]))
        return value
    }
    private fun checkedAward(value: String): String {
        require(value.length <= 256 && value.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+/[a-z0-9_./-]+")))
        return value
    }
    private fun checkedPurchaseKey(value: String): String {
        val parts = value.split('|')
        require(parts.size == 2 && value.length <= 256 && ID.matches(parts[0]) && parts[1].matches(LOCAL))
        return value
    }
    private fun boundedInteger(value: com.google.gson.JsonElement, minimum: Int, maximum: Int): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]+")))
        return value.asString.toIntOrNull()?.also { require(it in minimum..maximum) } ?: error("integer is out of range")
    }
    private companion object {
        val ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        val LOCAL = Regex("[a-z0-9_./-]{1,64}")
    }
}
