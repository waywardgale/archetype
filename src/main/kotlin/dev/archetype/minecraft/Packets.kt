package dev.archetype.minecraft

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

private fun packetId(name: String) = Identifier.fromNamespaceAndPath("archetype", name)

data class SelectClassPayload(val classId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<SelectClassPayload>(packetId("select_class"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SelectClassPayload> = StreamCodec.of(
            { buffer, packet -> buffer.writeUtf(packet.classId, 128) },
            { buffer -> SelectClassPayload(buffer.readUtf(128)) },
        )
    }
}

data class SelectSpecializationPayload(val classId: String, val specializationId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<SelectSpecializationPayload>(packetId("select_specialization"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SelectSpecializationPayload> = StreamCodec.of(
            { buffer, packet -> buffer.writeUtf(packet.classId, 128); buffer.writeUtf(packet.specializationId, 128) },
            { buffer -> SelectSpecializationPayload(buffer.readUtf(128), buffer.readUtf(128)) },
        )
    }
}

data class CastPayload(val classId: String, val specializationId: String, val grant: String, val generation: Long, val target: UUID?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<CastPayload>(packetId("cast"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, CastPayload> = StreamCodec.of(
            { buffer, packet ->
                buffer.writeUtf(packet.classId, 128)
                buffer.writeUtf(packet.specializationId, 128)
                buffer.writeUtf(packet.grant, 128)
                buffer.writeLong(packet.generation)
                buffer.writeBoolean(packet.target != null)
                packet.target?.let { buffer.writeUUID(it) }
            },
            { buffer ->
                val classId = buffer.readUtf(128)
                val specializationId = buffer.readUtf(128)
                val grant = buffer.readUtf(128)
                val generation = buffer.readLong()
                val target = if (buffer.readBoolean()) buffer.readUUID() else null
                CastPayload(classId, specializationId, grant, generation, target)
            },
        )
    }
}

data class ReleasePayload(val classId: String, val specializationId: String, val grant: String, val generation: Long, val target: UUID?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<ReleasePayload>(packetId("release"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ReleasePayload> = StreamCodec.of(
            { buffer, packet ->
                buffer.writeUtf(packet.classId, 128)
                buffer.writeUtf(packet.specializationId, 128)
                buffer.writeUtf(packet.grant, 128)
                buffer.writeLong(packet.generation)
                buffer.writeBoolean(packet.target != null)
                packet.target?.let { buffer.writeUUID(it) }
            },
            { buffer ->
                val classId = buffer.readUtf(128)
                val specializationId = buffer.readUtf(128)
                val grant = buffer.readUtf(128)
                val generation = buffer.readLong()
                ReleasePayload(classId, specializationId, grant, generation, if (buffer.readBoolean()) buffer.readUUID() else null)
            },
        )
    }
}

data class TalentSelectPayload(val tree: String, val node: String, val generation: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<TalentSelectPayload>(packetId("talent_select"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TalentSelectPayload> = StreamCodec.of(
            { buffer, packet -> buffer.writeUtf(packet.tree, 128); buffer.writeUtf(packet.node, 64); buffer.writeLong(packet.generation) },
            { buffer -> TalentSelectPayload(buffer.readUtf(128), buffer.readUtf(64), buffer.readLong()) },
        )
    }
}

data class TalentRespecPayload(val tree: String, val generation: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<TalentRespecPayload>(packetId("talent_respec"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TalentRespecPayload> = StreamCodec.of(
            { buffer, packet -> buffer.writeUtf(packet.tree, 128); buffer.writeLong(packet.generation) },
            { buffer -> TalentRespecPayload(buffer.readUtf(128), buffer.readLong()) },
        )
    }
}

data class GrantView(val name: String, val slot: String, val cooldownTicks: Int, val availableCharges: Int = 0, val maximumCharges: Int = 0, val rechargeTicks: Int = 0, val toggle: Boolean = false, val channel: Boolean = false, val charge: Boolean = false, val confirm: Boolean = false, val recast: Boolean = false, val active: Boolean = false)
data class ResourceView(val name: String, val amount: Double, val maximum: Double)
data class TrackView(val name: String, val level: Int, val xp: Long, val points: Int)
data class TalentNodeView(val tree: String, val node: String, val automatic: Boolean, val rank: Int,
    val maximum: Int, val cost: Int, val points: Int, val requiredLevel: Int, val reason: String)
data class StatePayload(
    val generation: Long,
    val activeClass: String,
    val activeSpecialization: String,
    val classes: List<String>,
    val specializations: List<String>,
    val grants: List<GrantView>,
    val resources: List<ResourceView>,
    val progression: List<TrackView>,
    val talents: List<TalentNodeView>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<StatePayload>(packetId("state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, StatePayload> = StreamCodec.of(
            { buffer, packet ->
                buffer.writeLong(packet.generation)
                buffer.writeUtf(packet.activeClass, 128)
                buffer.writeUtf(packet.activeSpecialization, 128)
                buffer.writeVarInt(packet.classes.size)
                packet.classes.forEach { buffer.writeUtf(it, 128) }
                buffer.writeVarInt(packet.specializations.size)
                packet.specializations.forEach { buffer.writeUtf(it, 128) }
                buffer.writeVarInt(packet.grants.size)
                packet.grants.forEach {
                    buffer.writeUtf(it.name, 128)
                    buffer.writeUtf(it.slot, 64)
                    buffer.writeVarInt(it.cooldownTicks)
                    buffer.writeVarInt(it.availableCharges)
                    buffer.writeVarInt(it.maximumCharges)
                    buffer.writeVarInt(it.rechargeTicks)
                    buffer.writeBoolean(it.toggle)
                    buffer.writeBoolean(it.channel)
                    buffer.writeBoolean(it.charge)
                    buffer.writeBoolean(it.confirm)
                    buffer.writeBoolean(it.recast)
                    buffer.writeBoolean(it.active)
                }
                buffer.writeVarInt(packet.resources.size)
                packet.resources.forEach {
                    buffer.writeUtf(it.name, 128)
                    buffer.writeDouble(it.amount)
                    buffer.writeDouble(it.maximum)
                }
                buffer.writeVarInt(packet.progression.size)
                packet.progression.forEach {
                    buffer.writeUtf(it.name, 128)
                    buffer.writeVarInt(it.level)
                    buffer.writeLong(it.xp)
                    buffer.writeVarInt(it.points)
                }
                buffer.writeVarInt(packet.talents.size)
                packet.talents.forEach {
                    buffer.writeUtf(it.tree, 128)
                    buffer.writeUtf(it.node, 64)
                    buffer.writeBoolean(it.automatic)
                    buffer.writeVarInt(it.rank)
                    buffer.writeVarInt(it.maximum)
                    buffer.writeVarInt(it.cost)
                    buffer.writeVarInt(it.points)
                    buffer.writeVarInt(it.requiredLevel)
                    buffer.writeUtf(it.reason, 32)
                }
            },
            { buffer ->
                val generation = buffer.readLong()
                val activeClass = buffer.readUtf(128)
                val activeSpecialization = buffer.readUtf(128)
                val classes = List(readCount(buffer, 128)) { buffer.readUtf(128) }
                val specializations = List(readCount(buffer, 256)) { buffer.readUtf(128) }
                val grants = List(readCount(buffer, 128)) {
                    val name = buffer.readUtf(128)
                    val slot = buffer.readUtf(64)
                    val cooldown = buffer.readVarInt()
                    val available = buffer.readVarInt()
                    val maximum = buffer.readVarInt()
                    val recharge = buffer.readVarInt()
                    val toggle = buffer.readBoolean()
                    val channel = buffer.readBoolean()
                    val charge = buffer.readBoolean()
                    val confirm = buffer.readBoolean()
                    val recast = buffer.readBoolean()
                    val active = buffer.readBoolean()
                    // ASVS 2.2.1: reject malformed client-visible state before storing it in the HUD model.
                    require(cooldown in 0..72_000 && maximum in 0..16 && available in 0..maximum && recharge in 0..72_000)
                    require(!active || toggle || channel || charge || confirm || recast)
                    GrantView(name, slot, cooldown, available, maximum, recharge, toggle, channel, charge, confirm, recast, active)
                }
                val resources = List(readCount(buffer, 128)) { ResourceView(buffer.readUtf(128), buffer.readDouble(), buffer.readDouble()) }
                val progression = List(readCount(buffer, 32)) {
                    val name = buffer.readUtf(128)
                    val level = buffer.readVarInt()
                    val xp = buffer.readLong()
                    val points = buffer.readVarInt()
                    require(level in 1..128 && xp in 0..1_000_000_000_000L && points in 0..1_000_000)
                    TrackView(name, level, xp, points)
                }
                val talents = List(readCount(buffer, 128)) {
                    val tree = buffer.readUtf(128)
                    val node = buffer.readUtf(64)
                    val automatic = buffer.readBoolean()
                    val rank = buffer.readVarInt()
                    val maximum = buffer.readVarInt()
                    val cost = buffer.readVarInt()
                    val points = buffer.readVarInt()
                    val requiredLevel = buffer.readVarInt()
                    val reason = buffer.readUtf(32)
                    require(maximum in 1..16 && rank in 0..maximum && cost in 0..1000 && points in 0..1_000_000 && requiredLevel in 1..128)
                    TalentNodeView(tree, node, automatic, rank, maximum, cost, points, requiredLevel, reason)
                }
                StatePayload(generation, activeClass, activeSpecialization, classes, specializations, grants, resources, progression, talents)
            },
        )
        private fun readCount(buffer: RegistryFriendlyByteBuf, limit: Int): Int = buffer.readVarInt().also {
            require(it in 0..limit) { "packet list exceeds $limit entries" }
        }
    }
}

object Packets {
    fun register() {
        PayloadTypeRegistry.serverboundPlay().register(SelectClassPayload.TYPE, SelectClassPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SelectSpecializationPayload.TYPE, SelectSpecializationPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(CastPayload.TYPE, CastPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ReleasePayload.TYPE, ReleasePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(TalentSelectPayload.TYPE, TalentSelectPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(TalentRespecPayload.TYPE, TalentRespecPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC)
    }
}
