package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ProjectileRuntimeTest {
    private val actor = UUID(0, 61)
    private val victim = UUID(0, 62)

    private class World : WorldOps {
        val contacts = ArrayDeque<ProjectileContact>()
        val damages = mutableListOf<Pair<UUID, Double>>()
        val heals = mutableListOf<Double>()
        val healTargets = mutableListOf<UUID>()
        val steps = mutableListOf<Pair<Position, Position>>()
        val exclusions = mutableListOf<Set<UUID>>()
        var targetPosition = Vec(10.0, 0.0, 0.0)
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also { heals += it; healTargets += target }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount.also { damages += target to it }
        override fun position(entity: UUID) = Position("test", if (entity == UUID(0, 62)) targetPosition else Vec(0.0, 0.0, 0.0))
        override fun direction(entity: UUID) = Vec(0.0, 0.0, 1.0)
        override fun loaded(position: Position) = true
        override fun projectileStep(actor: UUID, from: Position, to: Position, entities: ProjectileEntities, excluded: Set<UUID>): ProjectileContact {
            steps += from to to
            exclusions += excluded.toSet()
            val queued = if (contacts.isEmpty()) ProjectileContact.Miss(to) else contacts.removeFirst()
            return if (queued is ProjectileContact.Entity && queued.target in excluded) ProjectileContact.Miss(to) else queued
        }
    }

    private fun compile(projectile: String = """kind: projectile
id: bolt
speed: 20
gravity: 0
lifetime: 200ms
entity_hit:
  - {type: damage, target: event.target, amount: 3, damage_type: minecraft:magic}
block_hit:
  - {type: gain_resource, resource: focus, amount: 2}
expiry:
  - {type: heal, target: actor, amount: 1}""", fingerprint: String = "v1", direction: String = "actor.aim", projectileCall: String = "bolt",
        abilityEffects: String? = null): CompileResult {
        val effects = abilityEffects ?: "  - {type: launch_projectile, projectile: $projectileCall, direction: $direction, as: shot}"
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 20\ninitial: 0",
            "workshop/bolt.yaml" to projectile,
            "workshop/launch.yaml" to "kind: ability\nid: launch\nname: Launch\neffects:\n$effects",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  launch: {ref: launch}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  launch: {ref: launch}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }

    private fun runtime(world: World): AbilityRuntime = AbilityRuntime(world).also {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        it.publish((compiled as CompileResult.Valid).definitions)
        it.selectClass(actor, "workshop:mage")
    }

    @Test fun entityCollisionFiresOnce() {
        val world = World()
        val runtime = runtime(world)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation))
        assertTrue(world.damages.isEmpty())
        runtime.tick(listOf(actor))
        world.contacts += ProjectileContact.Entity(victim, Position("test", Vec(0.0, 0.0, 2.0)))
        runtime.tick(listOf(actor))
        repeat(5) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(victim to 3.0), world.damages)
        assertTrue(world.heals.isEmpty())
        assertEquals(1.0, world.steps[0].second.value.z)
    }

    @Test fun blockImpactExpiryAndCancellationAreDistinct() {
        val world = World()
        val runtime = runtime(world)
        world.contacts += ProjectileContact.Block(Position("test", Vec(0.0, 0.0, 0.5)))
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        runtime.tick(listOf(actor))
        assertEquals(2.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(world.heals.isEmpty())

        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        repeat(4) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(1.0), world.heals)

        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        runtime.selectClass(actor, "workshop:other")
        repeat(4) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(1.0), world.heals)
    }

    @Test fun projectileReloadCancelsOldFlight() {
        val world = World()
        val runtime = runtime(world)
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        val changed = (compile(fingerprint = "v2") as CompileResult.Valid).definitions
        val bolt = changed.projectiles.getValue("workshop:bolt")
        runtime.publish(changed.copy(projectiles = changed.projectiles + (bolt.id to bolt.copy(speed = 30.0))))
        repeat(4) { runtime.tick(listOf(actor)) }
        assertTrue(world.heals.isEmpty())
        assertTrue(world.damages.isEmpty())
    }

    @Test fun invalidProjectileCallbacksAndCyclesAreRejected() {
        val recursive = compile("""kind: projectile
id: bolt
speed: 20
lifetime: 1s
entity_hit:
  - {type: launch_projectile, projectile: bolt}""") as CompileResult.Invalid
        assertTrue(recursive.diagnostics.any { it.problem.contains("recursive") })
        val wrongContext = compile("""kind: projectile
id: bolt
speed: 20
lifetime: 1s
block_hit:
  - {type: damage, target: target, amount: 1, damage_type: minecraft:magic}""") as CompileResult.Invalid
        assertTrue(wrongContext.diagnostics.any { it.problem.contains("entity target") })
    }

    @Test fun piercingAndRepeatHitLimitsStayBounded() {
        val projectile = """kind: projectile
id: bolt
speed: 20
lifetime: 1s
pierce: {additional_entities: 2}
repeat_hit: {max_per_entity: 2, interval: 100ms}
entity_hit:
  - {type: gain_resource, resource: focus, amount: 1}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(projectile) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        val impact = ProjectileContact.Entity(victim, Position("test", Vec(0.0, 0.0, 0.5)))
        world.contacts += impact
        world.contacts += impact
        world.contacts += impact
        repeat(3) { runtime.tick(listOf(actor)) }
        assertEquals(2.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(victim in world.exclusions[1])
        assertFalse(victim in world.exclusions[2])
        world.contacts += impact
        runtime.tick(listOf(actor))
        assertEquals(2.0, runtime.record(actor).resources["player|workshop:focus"])
    }

    @Test fun blockBounceReflectsVelocityAndFiresEachImpactOnce() {
        val projectile = """kind: projectile
id: bolt
speed: 20
lifetime: 1s
bounce: {blocks: 1}
block_hit:
  - {type: gain_resource, resource: focus, amount: 1}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(projectile) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        val wall = ProjectileContact.Block(Position("test", Vec(0.0, 0.0, 0.5)), Vec(0.0, 0.0, -1.0))
        world.contacts += wall
        world.contacts += wall
        runtime.tick(listOf(actor))
        runtime.tick(listOf(actor))
        assertEquals(2.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(world.steps[1].second.value.z < world.steps[1].first.value.z)
    }

    @Test fun homingRequiresLockedTargetAndTurnsWithinDeclaredAngle() {
        val projectile = """kind: projectile
id: bolt
speed: 20
lifetime: 1s
homing: {turn_degrees_per_tick: 10}"""
        val invalid = compile(projectile) as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.problem.contains("homing projectile requires") })
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(projectile, direction = "actor.to_target") as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "launch", victim, runtime.generation))
        world.targetPosition = Vec(0.0, 0.0, 10.0)
        runtime.tick(listOf(actor))
        val delta = world.steps.single().second.value - world.steps.single().first.value
        assertTrue(delta.x > 0.98 && delta.z > 0.17 && delta.z < 0.18, "$delta")
    }

    @Test fun projectileParametersBindAtLaunchAndValidateReferences() {
        val projectile = """kind: projectile
id: bolt
parameters:
  power: {type: number, min: 0, max: 10}
  bonus: {type: number, min: 0, max: 5, default: 2}
speed: 20
lifetime: 1s
entity_hit:
  - {type: damage, target: event.target, amount: {expr: "params.power"}, damage_type: minecraft:magic}
  - {type: heal, target: actor, amount: {expr: "params.bonus"}}"""
        val call = "{ref: bolt, with: {power: 4}}"
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(projectile, projectileCall = call) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        world.contacts += ProjectileContact.Entity(victim, Position("test", Vec(0.0, 0.0, 0.5)))
        runtime.tick(listOf(actor))
        assertEquals(listOf(victim to 4.0), world.damages)
        assertEquals(listOf(2.0), world.heals)

        val missing = compile(projectile) as CompileResult.Invalid
        assertTrue(missing.diagnostics.any { it.problem.contains("required projectile parameter") })
        val unknown = compile(projectile, projectileCall = "{ref: bolt, with: {power: 4, wrong: 1}}") as CompileResult.Invalid
        assertTrue(unknown.diagnostics.any { it.problem.contains("unknown projectile parameter") })
        val outside = compile(projectile, projectileCall = "{ref: bolt, with: {power: 11}}") as CompileResult.Invalid
        assertTrue(outside.diagnostics.any { it.problem.contains("outside its declared bounds") })
        val badBody = compile(projectile.replace("params.power", "params.missing"), projectileCall = call) as CompileResult.Invalid
        assertTrue(badBody.diagnostics.any { it.problem.contains("params.missing is not available") })
    }

    @Test fun correlatedWaitMatchesImpactAndPassesEventTarget() {
        val effects = """  - {type: launch_projectile, projectile: bolt, as: shot}
  - type: wait_for
    handle: result.shot.handle
    event: projectile.entity_hit
    timeout: 1s
    matched:
      - {type: heal, target: event.target, amount: 2}
    timed_out:
      - {type: gain_resource, resource: focus, amount: 1}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(abilityEffects = effects) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation))
        world.contacts += ProjectileContact.Entity(victim, Position("test", Vec(0.0, 0.0, 0.5)))
        runtime.tick(listOf(actor))
        repeat(21) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(2.0), world.heals)
        assertEquals(listOf(victim), world.healTargets)
        assertNull(runtime.record(actor).resources["player|workshop:focus"])
    }

    @Test fun waitReceiptSurvivesEarlierImpactInSameSource() {
        val effects = """  - {type: launch_projectile, projectile: bolt, as: shot}
  - type: delay
    duration: 100ms
    effects:
      - type: wait_for
        handle: result.shot.handle
        event: projectile.entity_hit
        timeout: 1s
        matched:
          - {type: heal, target: event.target, amount: 4}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(abilityEffects = effects) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        world.contacts += ProjectileContact.Entity(victim, Position("test", Vec(0.0, 0.0, 0.5)))
        runtime.tick(listOf(actor))
        assertTrue(world.heals.isEmpty())
        runtime.tick(listOf(actor))
        assertEquals(listOf(4.0), world.heals)
    }

    @Test fun waitTimeoutAndSourceCancellationStayDistinct() {
        val effects = """  - {type: launch_projectile, projectile: bolt, as: shot}
  - type: wait_for
    handle: result.shot.handle
    event: projectile.entity_hit
    timeout: 100ms
    matched:
      - {type: gain_resource, resource: focus, amount: 10}
    timed_out:
      - {type: gain_resource, resource: focus, amount: 1}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(abilityEffects = effects) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        repeat(2) { runtime.tick(listOf(actor)) }
        assertEquals(1.0, runtime.record(actor).resources["player|workshop:focus"])
        runtime.cast(actor, "workshop:mage", "launch", null, runtime.generation)
        runtime.selectClass(actor, "workshop:other")
        repeat(3) { runtime.tick(listOf(actor)) }
        assertEquals(1.0, runtime.record(actor).resources["player|workshop:focus"])
    }

    @Test fun handleTypesAndMissingBindingsAreRejected() {
        val unknown = compile(abilityEffects = "  - {type: wait_for, handle: result.missing.handle, event: projectile.entity_hit, timeout: 1s}") as CompileResult.Invalid
        assertTrue(unknown.diagnostics.any { it.problem.contains("available projectile handle") })
        val arithmetic = compile(abilityEffects = """  - {type: launch_projectile, projectile: bolt, as: shot}
  - {type: heal, target: actor, amount: {expr: "result.shot.handle + 1"}}""") as CompileResult.Invalid
        assertTrue(arithmetic.diagnostics.any { it.problem.contains("handles are not numeric") })
    }
}
