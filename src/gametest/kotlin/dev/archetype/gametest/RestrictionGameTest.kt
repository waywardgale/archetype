package dev.archetype.gametest

import dev.archetype.definitions.*
import dev.archetype.minecraft.CombatBridge
import dev.archetype.runtime.*
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.EntityHitResult
import java.lang.reflect.Method
import java.util.UUID

class RestrictionGameTest : CustomTestMethodInvoker {
    override fun invokeTestMethod(helper: GameTestHelper, method: Method) { method.invoke(this, helper) }

    // This helper supplies an embedded server connection so the real packet handler can be exercised.
    @Suppress("DEPRECATION")
    @GameTest
    fun rootedPlayerMovementPacketIsRejected(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val actor = player.uuid
        val world = object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = actor == target
            override fun availableTarget(actor: UUID, target: UUID) = actor == target
            override fun position(entity: UUID) = Position(helper.level.dimension().identifier().toString(), Vec(player.x, player.y, player.z))
            override fun heal(target: UUID, amount: Double) = 0.0
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: guardian\nname: Guardian\nabilities:\n  root: {ref: root}",
            "workshop/root.yaml" to """kind: ability
id: root
name: Root
effects:
  - {type: apply_status, status: rooted, target: actor}""",
            "workshop/rooted.yaml" to "kind: status\nid: rooted\nduration: 5s\nrestrictions: [move, attack]",
            "workshop/practice.yaml" to """kind: progression_track
id: practice
scope: class
levels: [{level: 1, xp: 0}, {level: 2, xp: 100}]
earn:
  gathered: {event: vanilla_xp, amount: 2}""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "native-root"))
        check(compiled is CompileResult.Valid) { "$compiled" }
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        check(runtime.selectClass(actor, "workshop:guardian"))
        check(runtime.cast(actor, "workshop:guardian", "root", null, runtime.generation) == CastResult.Applied)
        check(runtime.isRestricted(actor, ActionRestriction.MOVE))
        check(runtime.isRestricted(actor, ActionRestriction.ATTACK))
        val oldRuntime = CombatBridge.runtime
        CombatBridge.runtime = runtime
        try {
            val x = player.x
            val y = player.y
            val z = player.z
            player.connection.handleMovePlayer(ServerboundMovePlayerPacket.Pos(x + 1.0, y, z, true, false))
            check(player.x == x && player.y == y && player.z == z) { "rooted player movement packet changed server position" }
            val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(1, 1, 1))
            check(AttackEntityCallback.EVENT.invoker().interact(player, helper.level, InteractionHand.MAIN_HAND,
                pig, EntityHitResult(pig)) == InteractionResult.FAIL) { "restricted entity attack was not rejected" }
            check(AttackBlockCallback.EVENT.invoker().interact(player, helper.level, InteractionHand.MAIN_HAND,
                helper.absolutePos(BlockPos(1, 0, 1)), Direction.UP) == InteractionResult.FAIL) {
                "restricted block attack was not rejected"
            }
            player.giveExperiencePoints(7)
            check(runtime.progress(actor, "workshop:practice")?.earnedXp == 14L) {
                "native vanilla XP did not reach the declared progression track"
            }
            runtime.shutdown()
            check(AttackEntityCallback.EVENT.invoker().interact(player, helper.level, InteractionHand.MAIN_HAND,
                pig, EntityHitResult(pig)) == InteractionResult.PASS) { "entity attack remained restricted after cleanup" }
        } finally {
            CombatBridge.runtime = oldRuntime
            runtime.shutdown()
        }
        helper.succeed()
    }
}
