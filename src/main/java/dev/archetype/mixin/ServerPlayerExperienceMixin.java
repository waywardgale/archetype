package dev.archetype.mixin;

import dev.archetype.minecraft.CombatBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerExperienceMixin {
    @WrapMethod(method = "giveExperiencePoints")
    private void archetype$vanillaExperience(int points, Operation<Void> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        int before = player.totalExperience;
        original.call(points);
        int gained = player.totalExperience - before;
        if (gained > 0) CombatBridge.vanillaExperience(player.getUUID(), gained);
    }
}
