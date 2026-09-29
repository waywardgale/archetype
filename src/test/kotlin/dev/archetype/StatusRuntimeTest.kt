package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StatusRuntimeTest {
    private val actor = UUID(0, 1)
    private val ally = UUID(0, 2)
    private val other = UUID(0, 3)
    private class World : WorldOps {
        val positions = linkedMapOf<UUID, Position>()
        val speeds = mutableMapOf<UUID, Double>()
        val healing = mutableListOf<Pair<UUID, Double>>()
        val dead = mutableSetOf<UUID>()
        var loaded = true
        var onHeal: (() -> Unit)? = null
        fun put(id: UUID, x: Double, dimension: String = "test") { positions[id] = Position(dimension, Vec(x, 0.0, 0.0)) }
        override fun validTarget(actor: UUID, target: UUID) = availableTarget(actor, target)
        override fun availableTarget(actor: UUID, target: UUID) = actor in positions && target in positions && actor !in dead && target !in dead && positions[actor]?.dimension == positions[target]?.dimension
        override fun position(entity: UUID) = positions[entity]
        override fun loaded(position: Position) = loaded
        override fun view(actor: UUID, target: UUID) = positions[target]?.let { EntityView(target, it, 10.0, 20.0, true) }
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) = positions.keys.filter { availableTarget(actor, it) }.mapNotNull { view(actor, it) }.take(limit)
        override fun lineOfSight(origin: Position, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double { healing += target to amount; onHeal?.invoke(); return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun movementSpeedBonus(target: UUID, amount: Double) { speeds[target] = amount }
    }
    private fun status(id: String = "haste", duration: String = "200ms", extra: String = "", amount: Double = 0.05, policy: String = "") = """
        kind: status
        id: $id
        duration: $duration
        reapply: refresh
        modifiers:
          - type: attribute
            attribute: minecraft:movement_speed
            amount: $amount
    """.trimIndent() + (if (policy.isEmpty()) "" else "\n${policy.prependIndent("    ")}") + "\n$extra"
    private val apply = "- type: apply_status\n  id: haste_application\n  status: {ref: haste}\n  target: target"
    private fun field(id: String = "field", duration: String = "500ms", buffs: String = "[haste]", extra: String = "") = """
        kind: area
        id: $id
        shape: {type: sphere, radius: 2}
        duration: $duration
        sample_every: 50ms
        targets: {type: living_entities, filters: [{type: relation, is: ally}]}
        buffs: $buffs
    """.trimIndent() + "\n$extra"
    private fun compile(effects: String = apply, statuses: List<String> = listOf(status()), areas: List<String> = emptyList(), fingerprint: String = "first", abilityOptions: String = ""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  primary:\n    definition:\n      name: Action\n${abilityOptions.prependIndent("      ")}\n      effects:\n${effects.prependIndent("        ")}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities: {}",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 100\ninitial: 100",
        ) + statuses.mapIndexed { i, text -> "workshop/status$i.yaml" to text } + areas.mapIndexed { i, text -> "workshop/area$i.yaml" to text }
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun definitions(result: CompileResult): DefinitionSet {
        assertTrue(result is CompileResult.Valid, "$result")
        return (result as CompileResult.Valid).definitions
    }
    private fun world() = World().also { it.put(actor, 0.0); it.put(ally, 1.0); it.put(other, 8.0) }
    private fun runtime(world: World, definitions: DefinitionSet = definitions(compile())) = AbilityRuntime(world).also {
        it.publish(definitions)
        it.selectClass(actor, "workshop:fighter")
        it.selectClass(other, "workshop:fighter")
    }
    private fun cast(runtime: AbilityRuntime, owner: UUID = actor, target: UUID = ally) = runtime.cast(owner, "workshop:fighter", "primary", target, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int) { repeat(count) { runtime.tick(listOf(actor, other)) } }
    private val periodic = "periodic:\n  every: 100ms\n  effects:\n    - type: heal\n      target: target\n      amount: {expr: 'status.stacks'}"
    private val create = "- type: create_area\n  area: field\n  anchor: {attached: actor}"

    @Test fun `refresh preserves cadence and does not replay first application`() {
        val callbacks = "applied:\n  - type: heal\n    target: target\n    amount: 7\nrefreshed:\n  - type: heal\n    target: target\n    amount: 3"
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = "$periodic\n$callbacks")))))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(ally to 7.0), world.healing)
        ticks(runtime, 1)
        cast(runtime)
        ticks(runtime, 1)
        assertEquals(listOf(7.0, 3.0, 1.0), world.healing.map { it.second })
        assertEquals(1, runtime.statuses(ally).single().stacks)
        ticks(runtime, 2)
        assertEquals(listOf(7.0, 3.0, 1.0, 1.0), world.healing.map { it.second })
        ticks(runtime, 1)
        assertTrue(runtime.statuses(ally).isEmpty())
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `independent owners retain independent duration and periodic work`() {
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = periodic)))))
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime, other)
        ticks(runtime, 1)
        assertEquals(2, runtime.statuses(ally).size)
        assertEquals(0.05, world.speeds[ally])
        runtime.onLogout(actor)
        assertEquals(other, runtime.statuses(ally).single().owner)
        ticks(runtime, 1)
        assertEquals(2, world.healing.size)
        ticks(runtime, 2)
        assertTrue(runtime.statuses(ally).isEmpty())
    }

    @Test fun `shared stacks cap and refresh as one source with a single cadence`() {
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = "stacks: {max: 3}\n$periodic")))))
        cast(runtime)
        ticks(runtime, 1)
        repeat(4) { cast(runtime) }
        assertEquals(3, runtime.statuses(ally).single().stacks)
        ticks(runtime, 1)
        assertEquals(listOf(ally to 3.0), world.healing)
        ticks(runtime, 3)
        assertTrue(runtime.statuses(ally).isEmpty())
    }

    @Test fun `per stack expiry reduces stacks and refresh at cap replaces the oldest expiry`() {
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = "stacks: {max: 2, duration: per_stack}\n$periodic")))))
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime)
        ticks(runtime, 3)
        assertEquals(1, runtime.statuses(ally).single().stacks)
        assertEquals(listOf(2.0, 2.0), world.healing.map { it.second })
        ticks(runtime, 1)
        assertTrue(runtime.statuses(ally).isEmpty())
    }

    @Test fun `status delayed descendants end before ordinary expiry callback`() {
        val callbacks = "applied:\n  - type: delay\n    duration: 200ms\n    effects:\n      - type: heal\n        target: target\n        amount: 8\nexpired:\n  - type: heal\n    target: target\n    amount: 2"
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = callbacks)))))
        cast(runtime)
        ticks(runtime, 4)
        assertEquals(listOf(ally to 2.0), world.healing)
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `overlapping area memberships outlive the status duration and clean up separately`() {
        val world = world()
        val effects = "$create\n- type: delay\n  duration: 100ms\n  effects:\n${create.prependIndent("    ")}"
        val runtime = runtime(world, definitions(compile(effects, areas = listOf(field()))))
        cast(runtime)
        ticks(runtime, 6)
        assertEquals(2, runtime.statuses(ally).size)
        assertTrue(runtime.statuses(ally).all { it.membership && it.remainingTicks == null })
        assertEquals(0.05, world.speeds[ally])
        ticks(runtime, 4)
        assertEquals(1, runtime.statuses(ally).size)
        assertEquals(0.05, world.speeds[ally])
        ticks(runtime, 2)
        assertTrue(runtime.statuses(ally).isEmpty())
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `departing one moving aura removes its delayed membership work before a due pulse`() {
        val delayed = "applied:\n  - type: delay\n    duration: 100ms\n    effects:\n      - type: heal\n        target: target\n        amount: 8"
        val world = world().apply { put(other, 1.0) }
        val runtime = runtime(world, definitions(compile(create, statuses = listOf(status(extra = "$periodic\n$delayed")), areas = listOf(field()))))
        cast(runtime)
        cast(runtime, other)
        ticks(runtime, 1)
        world.put(actor, 10.0)
        ticks(runtime, 1)
        assertEquals(1, runtime.statuses(ally).size)
        assertEquals(other, runtime.statuses(ally).single().owner)
        assertEquals(listOf(8.0, 1.0), world.healing.filter { it.first == ally }.map { it.second }.sortedDescending())
    }

    @Test fun `strongest speed bonus recomputes when the stronger source ends`() {
        val effects = "$apply\n- type: apply_status\n  status: stronger\n  target: target"
        val world = world()
        val runtime = runtime(world, definitions(compile(effects, listOf(status(duration = "400ms"), status("stronger", "100ms", amount = 0.1)))))
        cast(runtime)
        assertEquals(0.1, world.speeds[ally])
        ticks(runtime, 2)
        assertEquals(0.05, world.speeds[ally])
        ticks(runtime, 6)
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `capped addition sums sources without multiplying by stacks`() {
        val modifier = "kind: status\nid: haste\nduration: 200ms\nstacks: {max: 3}\nmodifiers:\n  - type: attribute\n    attribute: minecraft:movement_speed\n    amount: 0.05\n    stacking: capped_add\n    cap: 0.08"
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(modifier))))
        repeat(3) { cast(runtime) }
        assertEquals(0.05, world.speeds[ally])
        cast(runtime, other)
        assertEquals(0.08, world.speeds[ally])
        runtime.onDeath(actor)
        assertEquals(0.05, world.speeds[ally])
    }

    @Test fun `class switch death logout dimension unload and shutdown cancel without expiry actions`() {
        val callbacks = "expired:\n  - type: heal\n    target: target\n    amount: 9"
        val actions: List<(AbilityRuntime, World) -> Unit> = listOf(
            { r, _ -> r.selectClass(actor, "workshop:other") }, { r, _ -> r.onDeath(actor) }, { r, _ -> r.onLogout(actor) },
            { r, w -> w.put(actor, 0.0, "other"); ticks(r, 1) }, { r, w -> w.loaded = false; ticks(r, 1) }, { r, _ -> r.shutdown() },
            { r, _ -> r.onDeath(ally) }, { r, _ -> r.onLogout(ally) },
        )
        for (action in actions) {
            val world = world()
            val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = callbacks)))))
            cast(runtime)
            action(runtime, world)
            assertTrue(runtime.statuses(ally).isEmpty())
            assertEquals(0.0, world.speeds[ally])
            assertTrue(world.healing.isEmpty())
        }
    }

    @Test fun `affected reload cancels active and queued status dependencies while unrelated edits retain them`() {
        val delayed = "- type: delay\n  duration: 100ms\n  effects:\n${apply.prependIndent("    ")}"
        val world = world()
        val runtime = runtime(world, definitions(compile(delayed)))
        cast(runtime)
        runtime.publish(definitions(compile(delayed, listOf(status(amount = 0.06)), fingerprint = "changed")))
        ticks(runtime, 2)
        assertTrue(runtime.statuses(ally).isEmpty())
        cast(runtime)
        ticks(runtime, 2)
        assertEquals(0.06, world.speeds[ally])
        runtime.publish(definitions(compile(delayed, listOf(status(amount = 0.06)), areas = listOf(field()), fingerprint = "unrelated")))
        assertEquals(1, runtime.statuses(ally).size)
        runtime.publish(definitions(compile(delayed, fingerprint = "replaced")))
        assertTrue(runtime.statuses(ally).isEmpty())
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `two application identities in one grant keep separate contributions`() {
        val world = world()
        val runtime = runtime(world, definitions(compile("$apply\n${apply.replace("haste_application", "second_application")}")))
        cast(runtime)
        assertEquals(2, runtime.statuses(ally).size)
        assertTrue(compile("$apply\n$apply") is CompileResult.Invalid)
    }

    @Test fun `dead recipients cancel status work and native bonuses without waiting for duration`() {
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = periodic)))))
        cast(runtime)
        world.dead += ally
        ticks(runtime, 2)
        assertTrue(runtime.statuses(ally).isEmpty())
        assertTrue(world.healing.isEmpty())
        assertEquals(0.0, world.speeds[ally])
    }

    @Test fun `callback cancellation prevents later callbacks and periodic work`() {
        val callbacks = "applied:\n  - type: heal\n    target: target\n    amount: 1\n  - type: heal\n    target: target\n    amount: 2"
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = "$periodic\n$callbacks")))))
        world.onHeal = { runtime.onDeath(actor) }
        cast(runtime)
        ticks(runtime, 5)
        assertEquals(listOf(ally to 1.0), world.healing)
        assertTrue(runtime.statuses(ally).isEmpty())
    }

    @Test fun `invalid status grammar bindings references and controller cycles reject the whole set`() {
        val invalidStatuses = listOf(
            status(duration = "0ms"), status(extra = "stacks: {max: 65}"), status(extra = "stacks: {max: 2, duration: unknown}"),
            status(extra = periodic.replace("100ms", "0ms")), status(amount = Double.NaN), status(amount = 2.0),
            status(extra = "unexpected: true"), status(extra = "applied:\n${apply.prependIndent("  ")}"),
        )
        invalidStatuses.forEach { assertTrue(compile(statuses = listOf(it)) is CompileResult.Invalid, it) }
        assertTrue(compile(apply.replace("haste}", "missing}")) is CompileResult.Invalid)
        assertTrue(compile(create, areas = listOf(field(buffs = "[missing]"))) is CompileResult.Invalid)
        assertTrue(compile(create, listOf(status(extra = "applied:\n${create.prependIndent("  ")}")), listOf(field())) is CompileResult.Invalid)
        assertTrue(compile("- type: heal\n  target: target\n  amount: {expr: 'status.stacks'}") is CompileResult.Invalid)
        assertTrue(compile(statuses = listOf(status(), status("other", policy = "stacking: capped_add\ncap: 0.1"))) is CompileResult.Invalid)
    }

    @Test fun `known recipient capacity rejects before payment and existing contributions refresh at capacity`() {
        val applications = (1..64).joinToString("\n") { apply.replace("haste_application", "application_$it") }
        val options = "cooldown: 1s\ncosts: [{resource: focus, amount: 3}]"
        val world = world()
        val runtime = runtime(world, definitions(compile(applications, listOf(status(duration = "5s")), abilityOptions = options)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(64, runtime.statuses(ally).size)
        assertEquals(CastResult.Rejected("status contribution limit reached"), cast(runtime, other))
        assertEquals(100.0, runtime.record(other).resources["player|workshop:focus"] ?: 100.0)
        assertFalse("workshop:fighter|primary" in runtime.record(other).cooldowns)
        ticks(runtime, 20)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(64, runtime.statuses(ally).size)
        assertEquals(94.0, runtime.record(actor).resources["player|workshop:focus"])
    }

    @Test fun `shared controller dependency graphs have bounded preflight and reject oversized setup before payment`() {
        val definitions = (0..30).map { index ->
            val children = if (index == 30) "" else "applied:\n" + (1..2).joinToString("\n") {
                "  - type: apply_status\n    id: child_$it\n    status: status${index + 1}\n    target: target"
            }
            status("status$index", extra = children)
        }
        val result = compile(apply.replace("{ref: haste}", "status0"), definitions, abilityOptions = "cooldown: 1s\ncosts: [{resource: focus, amount: 3}]")
        val runtime = runtime(world(), this.definitions(result))
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2)) {
            assertEquals(CastResult.Rejected("ability work limit exceeded"), cast(runtime))
        }
        assertTrue(runtime.record(actor).resources.isEmpty())
        assertTrue(runtime.record(actor).cooldowns.isEmpty())
    }

    @Test fun `status expiry precedes an owned area pulse due on the same tick`() {
        val world = world()
        val status = status(duration = "100ms", extra = "applied:\n${create.prependIndent("  ")}")
        val area = field(buffs = "[]", extra = "periodic:\n  every: 100ms\n  effects:\n    - type: heal\n      target: target\n      amount: 5")
        val runtime = runtime(world, definitions(compile(statuses = listOf(status), areas = listOf(area))))
        cast(runtime)
        ticks(runtime, 2)
        assertTrue(world.healing.isEmpty())
        assertTrue(runtime.statuses(ally).isEmpty())
        ticks(runtime, 10)
        assertTrue(world.healing.isEmpty())
    }

    @Test fun `stack count callbacks precede periodic work and full expiry reads zero stacks`() {
        val callbacks = "stacks_changed:\n  - type: heal\n    target: target\n    amount: {expr: 'status.stacks * 10'}\nexpired:\n  - type: heal\n    target: target\n    amount: {expr: 'status.stacks'}"
        val world = world()
        val runtime = runtime(world, definitions(compile(statuses = listOf(status(extra = "stacks: {max: 2, duration: per_stack}\n$periodic\n$callbacks")))))
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime)
        ticks(runtime, 3)
        assertEquals(listOf(20.0, 2.0, 10.0, 1.0), world.healing.map { it.second })
        ticks(runtime, 1)
        assertEquals(0.0, world.healing.last().second)
    }

    @Test fun `status expiry removes descendant membership bonuses before expiry gameplay actions`() {
        val parent = status(duration = "100ms", extra = "applied:\n${create.prependIndent("  ")}\nexpired:\n  - type: heal\n    target: actor\n    amount: 1")
        val child = status("child")
        val world = world()
        val runtime = runtime(world, definitions(compile(apply.replace("target: target", "target: actor"), listOf(parent, child), listOf(field(buffs = "[child]")))))
        cast(runtime)
        assertEquals(0.05, world.speeds[ally])
        world.onHeal = { assertEquals(0.0, world.speeds[ally]) }
        ticks(runtime, 2)
        assertEquals(listOf(actor to 1.0), world.healing)
        assertTrue(runtime.statuses(ally).isEmpty())
    }

    @Test fun `invalid attached anchor removes membership bonuses before unrelated due work`() {
        val effects = create.replace("attached: actor", "attached: target") + "\n- type: delay\n  duration: 100ms\n  effects:\n    - type: heal\n      target: actor\n      amount: 1"
        val world = world().apply { put(ally, 7.0) }
        val runtime = runtime(world, definitions(compile(effects, areas = listOf(field()))))
        cast(runtime, target = other)
        assertEquals(0.05, world.speeds[ally])
        ticks(runtime, 1)
        world.dead += other
        world.onHeal = { assertEquals(0.0, world.speeds[ally]) }
        ticks(runtime, 1)
        assertEquals(listOf(actor to 1.0), world.healing)
        assertTrue(runtime.statuses(ally).isEmpty())
    }
}
