package dev.archetype.mixin;

import dev.archetype.definitions.ActionRestriction;
import dev.archetype.minecraft.CombatBridge;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerMoveRestrictionMixin {
    @Shadow @Final public ServerPlayer player;
    @Shadow public abstract void teleport(double x, double y, double z, float yaw, float pitch);

    // The native thread check has completed; runtime status maps are owned by the server thread.
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void archetype$restrictMove(ServerboundMovePlayerPacket packet, CallbackInfo info) {
        if (!packet.hasPosition()) return;
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        boolean moving = Math.abs(x - player.getX()) > 0.001 || Math.abs(y - player.getY()) > 0.001 ||
            Math.abs(z - player.getZ()) > 0.001;
        boolean jumping = player.onGround() && !packet.isOnGround() && y > player.getY() + 0.001;
        if ((moving && CombatBridge.restricted(player.getUUID(), ActionRestriction.MOVE)) ||
            (jumping && CombatBridge.restricted(player.getUUID(), ActionRestriction.JUMP))) {
            teleport(player.getX(), player.getY(), player.getZ(),
                packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()));
            info.cancel();
        }
    }

    @Inject(method = "handlePlayerInput", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void archetype$restrictInput(ServerboundPlayerInputPacket packet, CallbackInfo info) {
        if (CombatBridge.restricted(player.getUUID(), ActionRestriction.MOVE)) {
            player.setLastClientInput(Input.EMPTY);
            info.cancel();
        } else if (CombatBridge.restricted(player.getUUID(), ActionRestriction.JUMP) && packet.input().jump()) {
            Input input = packet.input();
            player.setLastClientInput(new Input(input.forward(), input.backward(), input.left(), input.right(),
                false, input.shift(), input.sprint()));
            info.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void archetype$restrictVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo info) {
        if (!CombatBridge.restricted(player.getUUID(), ActionRestriction.MOVE)) return;
        Entity vehicle = player.getRootVehicle();
        if (vehicle != player) player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
        info.cancel();
    }
}
