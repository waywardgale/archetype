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

data class CastPayload(val classId: String, val grant: String, val generation: Long, val target: UUID?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<CastPayload>(packetId("cast"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, CastPayload> = StreamCodec.of(
            { buffer, packet ->
                buffer.writeUtf(packet.classId, 128)
                buffer.writeUtf(packet.grant, 128)
                buffer.writeLong(packet.generation)
                buffer.writeBoolean(packet.target != null)
                packet.target?.let { buffer.writeUUID(it) }
            },
            { buffer ->
                val classId = buffer.readUtf(128)
                val grant = buffer.readUtf(128)
                val generation = buffer.readLong()
                val target = if (buffer.readBoolean()) buffer.readUUID() else null
                CastPayload(classId, grant, generation, target)
            },
        )
    }
}

data class GrantView(val name: String, val slot: String, val cooldownTicks: Int, val availableCharges: Int = 0, val maximumCharges: Int = 0, val rechargeTicks: Int = 0)
data class ResourceView(val name: String, val amount: Double, val maximum: Double)
data class StatePayload(
    val generation: Long,
    val activeClass: String,
    val classes: List<String>,
    val grants: List<GrantView>,
    val resources: List<ResourceView>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<StatePayload>(packetId("state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, StatePayload> = StreamCodec.of(
            { buffer, packet ->
                buffer.writeLong(packet.generation)
                buffer.writeUtf(packet.activeClass, 128)
                buffer.writeVarInt(packet.classes.size)
                packet.classes.forEach { buffer.writeUtf(it, 128) }
                buffer.writeVarInt(packet.grants.size)
                packet.grants.forEach {
                    buffer.writeUtf(it.name, 128)
                    buffer.writeUtf(it.slot, 64)
                    buffer.writeVarInt(it.cooldownTicks)
                    buffer.writeVarInt(it.availableCharges)
                    buffer.writeVarInt(it.maximumCharges)
                    buffer.writeVarInt(it.rechargeTicks)
                }
                buffer.writeVarInt(packet.resources.size)
                packet.resources.forEach {
                    buffer.writeUtf(it.name, 128)
                    buffer.writeDouble(it.amount)
                    buffer.writeDouble(it.maximum)
                }
            },
            { buffer ->
                val generation = buffer.readLong()
                val activeClass = buffer.readUtf(128)
                val classes = List(readCount(buffer, 128)) { buffer.readUtf(128) }
                val grants = List(readCount(buffer, 128)) {
                    val name = buffer.readUtf(128)
                    val slot = buffer.readUtf(64)
                    val cooldown = buffer.readVarInt()
                    val available = buffer.readVarInt()
                    val maximum = buffer.readVarInt()
                    val recharge = buffer.readVarInt()
                    // ASVS 2.2.1: reject malformed client-visible state before storing it in the HUD model.
                    require(cooldown in 0..72_000 && maximum in 0..16 && available in 0..maximum && recharge in 0..72_000)
                    GrantView(name, slot, cooldown, available, maximum, recharge)
                }
                val resources = List(readCount(buffer, 128)) { ResourceView(buffer.readUtf(128), buffer.readDouble(), buffer.readDouble()) }
                StatePayload(generation, activeClass, classes, grants, resources)
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
        PayloadTypeRegistry.serverboundPlay().register(CastPayload.TYPE, CastPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC)
    }
}
