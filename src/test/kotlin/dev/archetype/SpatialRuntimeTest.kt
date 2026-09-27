package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SpatialRuntimeTest {
    private val actor = UUID(0, 1)
    private val one = UUID(0, 2)
    private val two = UUID(0, 3)
    private val three = UUID(0, 4)
    private val pack = "format: 1\nid: workshop\nname: Workshop\ndependencies: []"
    private fun compile(effects: String, area: String? = null, targeting: String = "", fingerprint: String = "first", extra: List<Pair<String, String>> = emptyList(), catalog: MechanicCatalog = BuiltinEffects.catalog): CompileResult {
        val ability = "kind: ability\nid: action\nname: Action\n$targeting\neffects:\n" + effects.prependIndent("  ")
        val files = listOf(
            "workshop/pack.yaml" to pack,
            "workshop/action.yaml" to ability,
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  primary: {ref: action, slot: primary}",
            "workshop/resource.yaml" to "kind: resource\nid: focus\nscope: class\nmin: 0\nmax: 1000000\ninitial: 0",
        ) + (area?.let { listOf("workshop/field.yaml" to it) }.orEmpty()) + extra
        return ManifestCompiler(catalog).compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun definitions(result: CompileResult): DefinitionSet {
        assertTrue(result is CompileResult.Valid, "$result")
        return (result as CompileResult.Valid).definitions
    }
    private class FakeWorld : WorldOps {
        val entities = linkedMapOf<UUID, EntityView>()
        val blocked = mutableSetOf<UUID>()
        val hits = mutableListOf<Triple<UUID, UUID, Double>>()
        val heals = mutableListOf<Pair<UUID, Double>>()
        var isLoaded = true
        var groundPosition: Position? = null
        var aimedTarget: UUID? = null
        var damageHook: (() -> Unit)? = null
        fun put(id: UUID, x: Double, ally: Boolean = false, health: Double = 20.0) {
            entities[id] = EntityView(id, Position("test", Vec(x, 0.0, 0.0)), health, 20.0, ally)
        }
        override fun validTarget(actor: UUID, target: UUID) = availableTarget(actor, target) && target !in blocked
        override fun availableTarget(actor: UUID, target: UUID) = entities[actor]?.health?.let { it > 0 } == true && entities[target]?.health?.let { it > 0 } == true
        override fun validTarget(actor: UUID, target: UUID, range: Double) = validTarget(actor, target) && (position(actor)!!.value - position(target)!!.value).lengthSquared() <= range * range
        override fun position(entity: UUID) = entities[entity]?.position
        override fun view(actor: UUID, target: UUID) = entities[target]
        override fun loaded(position: Position) = isLoaded
        override fun ground(actor: UUID, range: Double) = groundPosition
        override fun aim(actor: UUID, range: Double) = aimedTarget
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) = entities.values.filter {
            it.position.dimension == origin.dimension && kotlin.math.abs(it.position.value.x - origin.value.x) <= radius && it.health > 0
        }.take(limit)
        override fun lineOfSight(origin: Position, target: UUID) = target !in blocked
        override fun heal(target: UUID, amount: Double): Double { heals += target to amount; return amount / 2 }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
            hits += Triple(actor, target, amount)
            damageHook?.invoke()
            return amount
        }
    }
    private fun runtime(world: FakeWorld, definitions: DefinitionSet, catalog: MechanicCatalog = BuiltinEffects.catalog) = AbilityRuntime(world, catalog).also {
        it.publish(definitions)
        assertTrue(it.selectClass(actor, "workshop:fighter"))
    }
    private fun cast(runtime: AbilityRuntime, target: UUID? = null) = runtime.cast(actor, "workshop:fighter", "primary", target, runtime.generation)
    private fun world() = FakeWorld().also { it.put(actor, 0.0, ally = true); it.put(one, 1.0); it.put(two, 4.0); it.put(three, 7.0) }
    private val chain = """
        - type: chain
          max_targets: 6
          hop_range: 3.1
          delay: 100ms
          targets:
            type: living_entities
            filters: [{type: relation, is: enemy}]
          effects:
            - type: damage
              target: target
              amount: {expr: "8 / (chain.index + 1)"}
              damage_type: minecraft:magic
    """.trimIndent()
    private val createArea = "- type: create_area\n  area: {ref: field}\n  anchor: {attached: actor}"
    private fun area(duration: String = "2s", enter: String = "", periodic: String = "", exit: String = "", expired: String = "") = """
        kind: area
        id: field
        shape: {type: sphere, radius: 2}
        duration: $duration
        sample_every: 50ms
        targets:
          type: living_entities
          include_actor: false
    """.trimIndent() + listOf("enter" to enter, "periodic" to periodic, "exit" to exit, "expired" to expired).filter { it.second.isNotEmpty() }.joinToString("") { "\n${it.first}:\n${it.second.prependIndent("  ")}" }

    @Test fun `chain advances from each hit with falloff and stops without revisiting`() {
        val world = world()
        val runtime = runtime(world, definitions(compile(chain)))
        assertEquals(CastResult.Applied, cast(runtime, one))
        assertEquals(listOf(one), world.hits.map { it.second })
        repeat(12) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one, two, three), world.hits.map { it.second })
        assertEquals(listOf(8.0, 4.0, 8.0 / 3), world.hits.map { it.third })
        assertTrue(world.hits.all { it.first == actor })
    }

    @Test fun `the server can resolve entity aim without a client supplied handle`() {
        val world = world().apply { aimedTarget = one }
        val runtime = runtime(world, definitions(compile(chain)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(one), world.hits.map { it.second })
    }

    @Test fun `chain can revisit only when declared and always respects finite maximum`() {
        val world = world().apply { entities.remove(three) }
        val runtime = runtime(world, definitions(compile(chain.replace("delay: 100ms", "delay: 0ms\n  revisit: true"))))
        assertEquals(CastResult.Applied, cast(runtime, one))
        assertEquals(listOf(one, two, one, two, one, two), world.hits.map { it.second })
    }

    @Test fun `obstruction and exhausted hops stop a chain and death cancels queued hits`() {
        val world = world().apply { blocked += two }
        val runtime = runtime(world, definitions(compile(chain)))
        cast(runtime, one)
        repeat(4) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one), world.hits.map { it.second })
        world.blocked.clear()
        cast(runtime, one)
        runtime.onDeath(actor)
        repeat(8) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one, one), world.hits.map { it.second })
    }

    @Test fun `selection orders ties by stable id and each target has separate results`() {
        val world = world().apply { put(two, 1.0); blocked += three }
        val effects = """
            - type: for_each
              targets:
                type: living_entities
                shape: {type: sphere, radius: 10}
                limit: 3
                filters: [{type: relation, is: enemy}]
              effects:
                - type: damage
                  target: target
                  amount: {expr: "selection.index + 1"}
                  damage_type: minecraft:magic
                  as: hit
                - type: heal
                  target: actor
                  amount: {expr: "result.hit.health_lost"}
        """.trimIndent()
        val runtime = runtime(world, definitions(compile(effects)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(one, two), world.hits.map { it.second })
        assertEquals(listOf(1.0, 2.0), world.heals.map { it.second })
    }

    @Test fun `result bindings cannot escape a selection or read another invocation`() {
        val invalid = compile("""
            - type: for_each
              targets: {type: living_entities, shape: {type: sphere, radius: 2}}
              effects:
                - type: damage
                  target: target
                  amount: 2
                  damage_type: minecraft:magic
                  as: hit
            - type: heal
              target: actor
              amount: {expr: "result.hit.health_lost"}
        """.trimIndent())
        assertTrue(invalid is CompileResult.Invalid)
        assertTrue((invalid as CompileResult.Invalid).diagnostics.any { it.field == "effects[1].amount" })
    }

    @Test fun `initial area occupants enter once and a moving aura reconciles membership`() {
        val world = world()
        val field = area(enter = "- type: heal\n  target: target\n  amount: 1", periodic = "every: 100ms\neffects:\n  - type: heal\n    target: target\n    amount: 2", exit = "- type: gain_resource\n  resource: focus\n  amount: 1")
        val runtime = runtime(world, definitions(compile(createArea, field)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(one to 1.0), world.heals)
        repeat(2) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one to 1.0, one to 2.0), world.heals)
        world.put(actor, 7.0, ally = true)
        runtime.tick(listOf(actor))
        assertEquals(three to 1.0, world.heals.last())
        assertEquals(1.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `leaving one overlapping area cancels only that membership's delayed work`() {
        val world = world()
        val field = area(enter = "- type: delay\n  duration: 200ms\n  effects:\n    - type: heal\n      target: target\n      amount: 5")
        val effects = "$createArea\n- type: create_area\n  area: {ref: field}\n  anchor: {position: actor}"
        val runtime = runtime(world, definitions(compile(effects, field)))
        cast(runtime)
        world.put(actor, 10.0, ally = true)
        repeat(4) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one to 5.0), world.heals)
    }

    @Test fun `area cancellation never runs ordinary exit or expiry actions`() {
        val field = area(exit = "- type: gain_resource\n  resource: focus\n  amount: 3", expired = "- type: gain_resource\n  resource: focus\n  amount: 7")
        for (cancel in listOf<(AbilityRuntime, FakeWorld) -> Unit>(
            { r, _ -> r.onDeath(actor) }, { r, _ -> r.onLogout(actor) },
            { _, w -> w.isLoaded = false },
            { r, _ -> r.publish(definitions(compile(createArea, field.replace("radius: 2", "radius: 3"), fingerprint = "second"))) },
        )) {
            val world = world()
            val runtime = runtime(world, definitions(compile(createArea, field)))
            cast(runtime)
            cancel(runtime, world)
            repeat(60) { runtime.tick(listOf(actor)) }
            assertEquals(0.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"] ?: 0.0)
        }
    }

    @Test fun `area pulses get fresh budgets over a long lifetime and expiry runs once`() {
        val world = world()
        val field = area("2m", periodic = "every: 50ms\neffects:\n  - type: heal\n    target: target\n    amount: 1", expired = "- type: gain_resource\n  resource: focus\n  amount: 9")
        val runtime = runtime(world, definitions(compile(createArea, field)))
        cast(runtime)
        repeat(2405) { runtime.tick(listOf(actor)) }
        assertEquals(2399, world.heals.size)
        assertEquals(9.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        assertTrue(runtime.drainFailures().isEmpty())
    }

    @Test fun `ground placement is resolved before spending and the fixed field keeps its position`() {
        val world = world()
        val effect = "- type: create_area\n  area: {ref: field}\n  anchor: {position: target}"
        val field = area(enter = "- type: heal\n  target: target\n  amount: 3")
        val targeting = "target: {type: ground, range: 12}\ncooldown: 1s"
        val runtime = runtime(world, definitions(compile(effect, field, targeting)))
        assertEquals(CastResult.Rejected("ground target is unavailable"), cast(runtime))
        assertTrue(runtime.record(actor).cooldowns.isEmpty())
        world.groundPosition = Position("test", Vec(7.0, 0.0, 0.0))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(three to 3.0), world.heals)
    }

    @Test fun `known oversized work rejects before spending cooldown or resources`() {
        val effects = """
            - type: repeat
              count: 64
              every: 50ms
              effects:
                - type: repeat
                  count: 64
                  every: 50ms
                  effects:
                    - type: heal
                      target: actor
                      amount: 1
        """.trimIndent()
        val runtime = runtime(world(), definitions(compile(effects, targeting = "cooldown: 5s")))
        assertEquals(CastResult.Rejected("ability work limit exceeded"), cast(runtime))
        assertTrue(runtime.record(actor).cooldowns.isEmpty())
    }

    @Test fun `a full continuation queue rejects a new start before payment`() {
        val world = world()
        val effects = List(64) { "- type: delay\n  duration: 60m\n  effects:\n    - type: heal\n      target: actor\n      amount: 1" }.joinToString("\n")
        val runtime = runtime(world, definitions(compile(effects)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(CastResult.Rejected("scheduled work limit exceeded"), cast(runtime))
        assertTrue(runtime.record(actor).cooldowns.isEmpty())
        assertTrue(runtime.record(actor).resources.isEmpty())
        assertTrue(world.heals.isEmpty())
    }

    @Test fun `target loss skips dependent results while independent delayed work continues`() {
        val world = world()
        val effects = """
            - type: for_each
              targets: {type: living_entities, shape: {type: sphere, radius: 2}}
              effects:
                - type: delay
                  duration: 100ms
                  effects:
                    - type: damage
                      target: target
                      amount: 3
                      damage_type: minecraft:magic
                      as: hit
                    - type: heal
                      target: actor
                      amount: {expr: "result.hit.health_lost"}
                    - type: gain_resource
                      resource: focus
                      amount: 1
        """.trimIndent()
        val runtime = runtime(world, definitions(compile(effects)))
        cast(runtime)
        world.put(one, 1.0, health = 0.0)
        repeat(2) { runtime.tick(listOf(actor)) }
        assertTrue(world.hits.isEmpty())
        assertTrue(world.heals.isEmpty())
        assertEquals(1.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        assertTrue(runtime.drainFailures().isEmpty())
    }

    @Test fun `area expiry cancels membership continuations due on the same tick`() {
        val world = world()
        val field = area("100ms", enter = "- type: delay\n  duration: 100ms\n  effects:\n    - type: heal\n      target: target\n      amount: 5", expired = "- type: gain_resource\n  resource: focus\n  amount: 9")
        val runtime = runtime(world, definitions(compile(createArea, field)))
        cast(runtime)
        repeat(3) { runtime.tick(listOf(actor)) }
        assertTrue(world.heals.isEmpty())
        assertEquals(9.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `reload cancels queued creation when an area dependency changes and preserves unchanged controllers`() {
        val field = area(enter = "- type: heal\n  target: target\n  amount: 1", periodic = "every: 100ms\neffects:\n  - type: heal\n    target: target\n    amount: 2")
        val effects = "- type: delay\n  duration: 100ms\n  effects:\n" + createArea.prependIndent("    ")
        val world = world()
        val runtime = runtime(world, definitions(compile(effects, field)))
        cast(runtime)
        runtime.publish(definitions(compile(effects, field.replace("radius: 2", "radius: 3"), fingerprint = "changed")))
        repeat(4) { runtime.tick(listOf(actor)) }
        assertTrue(world.heals.isEmpty())

        runtime.publish(definitions(compile(createArea, field, fingerprint = "active")))
        cast(runtime)
        runtime.publish(definitions(compile(createArea, field, fingerprint = "unrelated")))
        repeat(2) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(one to 1.0, one to 2.0), world.heals)
    }

    @Test fun `class switching cancels a controller without exit or expiry callbacks`() {
        val field = area(expired = "- type: gain_resource\n  resource: focus\n  amount: 9")
        val other = "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities: {}"
        val world = world()
        val runtime = runtime(world, definitions(compile(createArea, field, extra = listOf(other))))
        cast(runtime)
        assertTrue(runtime.selectClass(actor, "workshop:other"))
        repeat(60) { runtime.tick(listOf(actor)) }
        assertFalse("workshop:fighter|workshop:focus" in runtime.record(actor).resources)
    }

    @Test fun `overheal is distinguished from requested and actual healing`() {
        val world = world().apply { put(actor, 0.0, true, 15.0) }
        val effects = "- type: archetype:heal\n  target: actor\n  amount: 8\n  as: healing\n- type: gain_resource\n  resource: focus\n  amount: {expr: 'result.healing.health_restored + result.healing.overheal'}"
        val runtime = runtime(world, definitions(compile(effects)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(7.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `an overloaded target query cancels its source with a bounded diagnostic`() {
        val world = world()
        for (index in 10L..530L) world.put(UUID(0, index), 1.0)
        val effects = "- type: for_each\n  targets: {type: living_entities, shape: {type: sphere, radius: 2}}\n  effects:\n    - type: heal\n      target: target\n      amount: 1"
        val runtime = runtime(world, definitions(compile(effects)))
        assertEquals(CastResult.Interrupted("target query exceeds 512 candidates"), cast(runtime))
        assertTrue(world.heals.isEmpty())
        assertEquals(1, runtime.drainFailures().size)
    }

    @Test fun `reentrant source cancellation stops the rest of the immediate sequence`() {
        val world = world()
        val effects = "- type: damage\n  target: target\n  amount: 1\n  damage_type: minecraft:magic\n- type: heal\n  target: actor\n  amount: 5"
        val runtime = runtime(world, definitions(compile(effects)))
        world.damageHook = { runtime.onDeath(actor) }
        cast(runtime, one)
        assertTrue(world.heals.isEmpty())
    }

    @Test fun `recursive area references and invalid bounds reject the complete set`() {
        assertTrue(compile(createArea, area(enter = createArea)) is CompileResult.Invalid)
        assertTrue(compile(chain.replace("hop_range: 3.1", "hop_range: 99")) is CompileResult.Invalid)
        assertTrue(compile(createArea, area().replace("sample_every: 50ms", "sample_every: 0ms")) is CompileResult.Invalid)
        assertTrue(compile(chain.replace("max_targets: 6", "max_targets: 65")) is CompileResult.Invalid)
        assertTrue(compile(createArea, area(expired = "- type: heal\n  target: target\n  amount: 1")) is CompileResult.Invalid)
        assertTrue(compile("- type: heal\n  target: target\n  amount: 1", targeting = "target: {type: ground}") is CompileResult.Invalid)
    }

    private data class ExtensionGain(val amount: Numeric, override val resultName: String?) : Effect
    @Test fun `an extension uses one registration for decoding execution and result checking`() {
        val extension = EffectMechanic("example:gain", ExtensionGain::class.java,
            mapOf("amount" to mapOf("type" to "number")), setOf("amount"), "Immediate resource gain.", setOf("amount"),
            numericInputs = { mapOf("amount" to it.amount) },
            decode = { ExtensionGain(it.numeric("amount"), it.resultName) },
            execute = { e, c -> mapOf("amount" to c.resource("workshop:focus", e.amount, false)) })
        val catalog = MechanicCatalog(BuiltinEffects.catalog.effects.values.toList() + extension)
        val effects = "- type: example:gain\n  amount: 4\n  as: gain\n- type: heal\n  target: actor\n  amount: {expr: 'result.gain.amount'}"
        val world = world()
        val runtime = runtime(world, definitions(compile(effects, catalog = catalog)), catalog)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(actor to 4.0), world.heals)
        assertEquals(4.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }
}
