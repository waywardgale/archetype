package dev.archetype.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.archetype.minecraft.CombatBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class LivingEntityDamageMixin {
    @Shadow public abstract float getAbsorptionAmount();

    @WrapMethod(method = "hurtServer")
    private boolean archetype$trackDamage(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        LivingEntity target = (LivingEntity) (Object) this;
        CombatBridge.beginning(target.getUUID(), target.getHealth());
        try {
            return original.call(level, source, amount);
        } finally {
            CombatBridge.finished(target.getUUID());
        }
    }

    // This expression is after native armor/magic mitigation and before vanilla absorption and health loss.
    @ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;getDamageAfterMagicAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float archetype$applyBarrier(float mitigated, ServerLevel level, DamageSource source, float incoming) {
        LivingEntity target = (LivingEntity) (Object) this;
        String damageType = source.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("");
        return CombatBridge.absorb(target.getUUID(), damageType, mitigated, getAbsorptionAmount());
    }
}
