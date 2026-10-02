package dev.archetype.mixin;

import dev.archetype.minecraft.TerrainBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelTerrainMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void archetype$beforeBlockWrite(BlockPos pos, BlockState state, int flags, int recursion,
                                             CallbackInfoReturnable<Boolean> callback) {
        TerrainBridge.beforeSetBlock((Level) (Object) this, pos);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void archetype$breakWithoutDrops(BlockPos pos, boolean drop, Entity breaker, int recursion,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (TerrainBridge.breakTemporary((Level) (Object) this, pos)) callback.setReturnValue(true);
    }
}
