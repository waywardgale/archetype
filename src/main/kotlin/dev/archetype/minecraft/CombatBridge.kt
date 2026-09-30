package dev.archetype.minecraft

import dev.archetype.runtime.AbilityRuntime
import java.util.UUID

/** Native callbacks run on the server thread. The runtime is replaced only at server lifecycle boundaries. */
object CombatBridge {
    @Volatile var runtime: AbilityRuntime? = null
    private val healthAtStart = mutableMapOf<UUID, ArrayDeque<Float>>()

    @JvmStatic fun beginning(target: UUID, health: Float) {
        // A nested native hit on the same entity keeps its own before value.
        healthAtStart.getOrPut(target) { ArrayDeque() }.addLast(health)
    }

    @JvmStatic fun finished(target: UUID) {
        healthAtStart[target]?.let { values ->
            if (values.isNotEmpty()) values.removeLast()
            if (values.isEmpty()) healthAtStart.remove(target)
        }
    }

    @JvmStatic fun absorb(target: UUID, damageType: String, mitigated: Float, nativeAbsorption: Float): Float {
        if (!mitigated.isFinite() || mitigated <= 0.0f || !nativeAbsorption.isFinite() || nativeAbsorption < 0.0f) return mitigated
        // ASVS 2.3.1, 2.3.4: consume owned capacity from the server's mitigated native amount only.
        val absorbed = runtime?.absorbNativeDamage(target, damageType, mitigated.toDouble(), nativeAbsorption.toDouble()) ?: 0.0
        return (mitigated.toDouble() - absorbed).coerceAtLeast(0.0).toFloat()
    }

    @JvmStatic fun committed(target: UUID, healthAfter: Float) {
        val current = runtime ?: return
        current.nativeDamageCommitted(target)
        val before = healthAtStart[target]?.lastOrNull() ?: return
        val lost = (before - healthAfter).toDouble()
        if (lost.isFinite() && lost > 0.0) current.onNativeHealthLoss(target, lost)
    }
}
