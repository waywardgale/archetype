package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ManifestRuntimeTest {
    private fun compile(vararg files: Pair<String, String>): CompileResult = ManifestCompiler().compile(
        PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "test-${files.hashCode()}"),
    )

    private val pack = """
        format: 1
        id: workshop
        name: Workshop
        dependencies: []
    """.trimIndent()

    @Test fun `actual damage feeds the next effect and costs commit once`() {
        val result = compile(
            "workshop/pack.yaml" to pack,
            "workshop/focus.yaml" to """
                kind: resource
                id: focus
                scope: class
                min: 0
                max: 20
                initial: 20
            """.trimIndent(),
            "workshop/strike.yaml" to """
                kind: ability
                id: strike
                name: Strike
                cooldown: 2s
                costs:
                  - resource: focus
                    amount: 5
                effects:
                  - type: damage
                    target: target
                    amount: 8
                    damage_type: minecraft:magic
                    as: hit
                  - type: heal
                    target: actor
                    amount: {expr: "result.hit.health_lost * 0.5"}
            """.trimIndent(),
            "workshop/class.yaml" to """
                kind: class
                id: fighter
                name: Fighter
                abilities:
                  primary:
                    ref: strike
                    slot: primary
            """.trimIndent(),
        )
        assertTrue(result is CompileResult.Valid, "$result")
        val actor = UUID.randomUUID()
        val target = UUID.randomUUID()
        var healed = 0.0
        val world = object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = true
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 4.0
            override fun heal(target: UUID, amount: Double): Double { healed = amount; return amount }
        }
        val runtime = AbilityRuntime(world)
        runtime.publish((result as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:fighter"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:fighter", "primary", target, runtime.generation))
        assertEquals(2.0, healed)
        assertEquals(15.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        assertEquals(CastResult.Rejected("cooldown is active"), runtime.cast(actor, "workshop:fighter", "primary", target, runtime.generation))
        assertEquals(15.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `unknown effects and duplicate keys reject the complete set`() {
        val unknown = compile("workshop/pack.yaml" to pack, "workshop/ability.yaml" to """
            kind: ability
            id: a
            name: A
            effects:
              - type: execute_command
                command: say hi
        """.trimIndent())
        assertTrue(unknown is CompileResult.Invalid)
        assertTrue((unknown as CompileResult.Invalid).diagnostics.any { it.field == "effects[0].type" })

        val duplicate = compile("workshop/pack.yaml" to "$pack\nid: second")
        assertTrue(duplicate is CompileResult.Invalid)
    }

    @Test fun `result reads must follow the effect that produced them`() {
        val result = compile("workshop/pack.yaml" to pack, "workshop/ability.yaml" to """
            kind: ability
            id: restore
            name: Restore
            effects:
              - type: heal
                target: actor
                amount: {expr: "result.later.health_lost * 0.5"}
              - type: damage
                target: actor
                amount: 1
                damage_type: minecraft:magic
                as: later
        """.trimIndent())
        assertTrue(result is CompileResult.Invalid)
        assertTrue((result as CompileResult.Invalid).diagnostics.any { it.field == "effects[0].amount" })
    }

    @Test fun `invalid publication never changes active definitions`() {
        val valid = compile("workshop/pack.yaml" to pack)
        val invalid = compile("workshop/pack.yaml" to "$pack\nid: second")
        val runtime = AbilityRuntime(object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = false
            override fun heal(target: UUID, amount: Double) = 0.0
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        })
        runtime.publish((valid as CompileResult.Valid).definitions)
        if (invalid is CompileResult.Valid) runtime.publish(invalid.definitions)
        assertEquals(1, runtime.generation)
        assertEquals("workshop", runtime.definitions.packs.values.single().id)
    }

    @Test fun `reload clamps resources but keeps remaining cooldown and rejects stale input`() {
        fun set(max: Int, fingerprint: String): DefinitionSet {
            val result = ManifestCompiler().compile(PackSnapshot(listOf(
                SourceFile("workshop/pack.yaml", pack.toByteArray()),
                SourceFile("workshop/resource.yaml", """
                    kind: resource
                    id: focus
                    scope: class
                    min: 0
                    max: $max
                    initial: $max
                """.trimIndent().toByteArray()),
                SourceFile("workshop/class.yaml", """
                    kind: class
                    id: fighter
                    name: Fighter
                    abilities:
                      primary:
                        slot: primary
                        definition:
                          name: Strike
                          cooldown: 4s
                          costs:
                            - resource: focus
                              amount: 5
                          effects:
                            - type: heal
                              target: actor
                              amount: 1
                """.trimIndent().toByteArray()),
            ), fingerprint))
            return (result as CompileResult.Valid).definitions
        }
        val runtime = AbilityRuntime(object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = true
            override fun heal(target: UUID, amount: Double) = amount
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        })
        val player = UUID.randomUUID()
        runtime.publish(set(20, "first"))
        runtime.selectClass(player, "workshop:fighter")
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "primary", null, 1))
        runtime.tick(listOf(player))
        val remaining = runtime.record(player).cooldowns["workshop:fighter|primary"]
        runtime.publish(set(10, "second"))
        assertEquals(10.0, runtime.record(player).resources["workshop:fighter|workshop:focus"])
        assertEquals(remaining, runtime.record(player).cooldowns["workshop:fighter|primary"])
        assertEquals(CastResult.Rejected("stale definitions"), runtime.cast(player, "workshop:fighter", "primary", null, 1))
        runtime.onDeath(player)
        assertEquals(10.0, runtime.record(player).resources["workshop:fighter|workshop:focus"])
        assertEquals(remaining, runtime.record(player).cooldowns["workshop:fighter|primary"])
    }

    @Test fun `bounded repeat schedules pulses and death cancels remaining work`() {
        val compiled = compile("workshop/pack.yaml" to pack, "workshop/class.yaml" to """
            kind: class
            id: healer
            name: Healer
            abilities:
              pulse:
                slot: primary
                definition:
                  name: Pulse
                  effects:
                    - type: repeat
                      count: 3
                      every: 100ms
                      effects:
                        - type: branch
                          when: {type: compare, left: 1, op: eq, right: 1}
                          then:
                            - type: heal
                              target: actor
                              amount: 2
        """.trimIndent()) as CompileResult.Valid
        var total = 0.0
        val runtime = AbilityRuntime(object : WorldOps {
            override fun validTarget(actor: UUID, target: UUID) = true
            override fun heal(target: UUID, amount: Double): Double { total += amount; return amount }
            override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = 0.0
        })
        val player = UUID.randomUUID()
        runtime.publish(compiled.definitions)
        runtime.selectClass(player, "workshop:healer")
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:healer", "pulse", null, runtime.generation))
        runtime.tick(listOf(player))
        assertEquals(0.0, total)
        runtime.tick(listOf(player))
        assertEquals(2.0, total)
        runtime.tick(listOf(player))
        runtime.tick(listOf(player))
        assertEquals(4.0, total)
        runtime.onDeath(player)
        repeat(4) { runtime.tick(listOf(player)) }
        assertEquals(4.0, total)
    }
}
