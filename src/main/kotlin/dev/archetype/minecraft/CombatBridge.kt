package dev.archetype.minecraft

import dev.archetype.runtime.AbilityRuntime
import dev.archetype.definitions.ActionRestriction
import java.util.UUID

/** Native callbacks run on the server thread. The runtime is replaced only at server lifecycle boundaries. */
object CombatBridge {
    @Volatile var runtime: AbilityRuntime? = null
    @JvmStatic fun restricted(target: UUID, action: ActionRestriction): Boolean = runtime?.isRestricted(target, action) == true
    @JvmStatic fun vanillaExperience(player: UUID, points: Int) { runtime?.onVanillaExperience(player, points) }
    private data class HitFrame(val before: Float, var committed: Boolean = false)
    private val healthAtStart = mutableMapOf<UUID, ArrayDeque<HitFrame>>()
    private var reflecting = false

    @JvmStatic fun beginning(target: UUID, health: Float) {
        // A nested native hit on the same entity keeps its own before value.
        healthAtStart.getOrPut(target) { ArrayDeque() }.addLast(HitFrame(health))
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

    @JvmStatic fun committed(target: UUID, healthAfter: Float, creditedPlayer: UUID? = null, attacker: UUID? = null) {
        val current = runtime ?: return
        val frame = healthAtStart[target]?.lastOrNull()
        if (frame?.committed == true) return
        if (frame != null) frame.committed = true
        val lost = frame?.let { (it.before - healthAfter).toDouble() } ?: 0.0
        if (lost.isFinite() && lost > 0.0 && creditedPlayer != null)
            current.recordContribution(target, creditedPlayer, lost)
        if (!reflecting && attacker != null && lost.isFinite() && lost > 0.0) {
            reflecting = true
            try { current.reflectNativeDamage(target, attacker, lost) }
            finally { reflecting = false }
        }
        current.nativeDamageCommitted(target)
        if (lost.isFinite() && lost > 0.0)
            current.onNativeHealthLoss(target, lost)
    }
}
