package dev.archetype.gametest

import dev.archetype.definitions.*
import dev.archetype.minecraft.CombatBridge
import dev.archetype.runtime.*
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityTypes
import java.lang.reflect.Method
import java.util.UUID

class CombatGameTest : CustomTestMethodInvoker {
    override fun invokeTestMethod(helper: GameTestHelper, method: Method) { method.invoke(this, helper) }

    @GameTest
    fun nativeHealthLossTriggersBoundedReflection(helper: GameTestHelper) {
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(1, 1, 1))
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(3, 1, 1))
        val actor = UUID(0, 4250)
        val reflected = mutableListOf<Pair<UUID, Double>>()
        val world = object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = true
            override fun availableTarget(actor: UUID, target: UUID) = true
            override fun heal(target: UUID, amount: Double) = 0.0
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
                reflected += target to amount
                return amount
            }
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: guardian\nname: Guardian\nabilities:\n  mirror: {ref: mirror_cast}",
            "workshop/ability.yaml" to "kind: ability\nid: mirror_cast\nname: Mirror\neffects: [{type: apply_status, status: mirror, target: target}]",
            "workshop/status.yaml" to """kind: status
id: mirror
duration: 5s
modifiers:
  - {type: reflect, fraction: 0.5, cap: 2, damage_type: minecraft:magic}""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "native-reflect"))
        check(compiled is CompileResult.Valid) { "$compiled" }
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        check(runtime.selectClass(actor, "workshop:guardian"))
        check(runtime.cast(actor, "workshop:guardian", "mirror", pig.uuid, runtime.generation) == CastResult.Applied)
        val oldRuntime = CombatBridge.runtime
        CombatBridge.runtime = runtime
        try {
            pig.invulnerableTime = 0
            pig.hurtServer(helper.level, helper.level.damageSources().mobAttack(zombie), 6.0f)
            check(reflected.size == 1 && reflected.single().first == zombie.uuid && reflected.single().second == 2.0) {
                "native reflection was not bounded or ran more than once: $reflected"
            }
        } finally {
            CombatBridge.runtime = oldRuntime
            runtime.shutdown()
        }
        helper.succeed()
    }

    @Suppress("DEPRECATION")
    @GameTest
    fun nativePlayerDamageCreditsContributorOnlyAfterHealthLoss(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(1, 1, 1))
        val world = object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = true
            override fun availableTarget(actor: UUID, target: UUID) = true
            override fun heal(target: UUID, amount: Double) = 0.0
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: guardian\nname: Guardian\nabilities: {}",
            "workshop/track.yaml" to """kind: progression_track
id: practice
scope: class
levels: [{level: 1, xp: 0}]
earn:
  assist: {event: entity_death, amount: 9, recipients: {type: contributors}}""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "native-credit"))
        check(compiled is CompileResult.Valid) { "$compiled" }
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        check(runtime.selectClass(player.uuid, "workshop:guardian"))
        val oldRuntime = CombatBridge.runtime
        CombatBridge.runtime = runtime
        try {
            pig.invulnerableTime = 0
            pig.hurtServer(helper.level, helper.level.damageSources().playerAttack(player), 2.0f)
            runtime.onEntityDeath(null, victim = pig.uuid)
            check(runtime.progress(player.uuid, "workshop:practice")?.earnedXp == 9L) {
                "native player hit did not credit its contributor"
            }
        } finally {
            CombatBridge.runtime = oldRuntime
            runtime.shutdown()
        }
        helper.succeed()
    }

    @GameTest
    fun barrierInterceptsRealNativeDamage(helper: GameTestHelper) {
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(1, 1, 1))
        val actor = UUID(0, 4242)
        val nativePosition = Position(helper.level.dimension().identifier().toString(),
            Vec(pig.x, pig.y, pig.z))
        val world = object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = target == pig.uuid
            override fun validTarget(actor: UUID, target: UUID, range: Double) = target == pig.uuid
            override fun availableTarget(actor: UUID, target: UUID) = target == actor || target == pig.uuid
            override fun position(entity: UUID) = nativePosition
            override fun heal(target: UUID, amount: Double) = 0.0
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: guardian\nname: Guardian\nabilities:\n  shield: {ref: shield}",
            "workshop/shield.yaml" to """kind: ability
id: shield
name: Shield
target: {type: entity, range: 16}
effects:
  - {type: shield, target: target, capacity: 10, duration: 5s}""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "native-barrier"))
        check(compiled is CompileResult.Valid) { "$compiled" }
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        check(runtime.selectClass(actor, "workshop:guardian"))
        check(runtime.cast(actor, "workshop:guardian", "shield", pig.uuid, runtime.generation) == CastResult.Applied)
        val oldRuntime = CombatBridge.runtime
        CombatBridge.runtime = runtime
        try {
            val before = pig.health
            pig.invulnerableTime = 0
            pig.hurtServer(helper.level, helper.level.damageSources().magic(), 7.0f)
            check(pig.health == before) { "barrier did not prevent native health loss" }
            check(kotlin.math.abs(runtime.barriers(pig.uuid).single().remaining - 3.0) < 0.01) {
                "barrier consumed an unexpected amount"
            }
            pig.invulnerableTime = 0
            pig.hurtServer(helper.level, helper.level.damageSources().magic(), 5.0f)
            check(runtime.barriers(pig.uuid).isEmpty()) { "depleted barrier remained active" }
            check(kotlin.math.abs((before - pig.health) - 2.0f) < 0.01f) {
                "native health loss did not follow barrier depletion: ${before - pig.health}"
            }
        } finally {
            CombatBridge.runtime = oldRuntime
            runtime.shutdown()
        }
        helper.succeed()
    }
}
