package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.minecraft.PlayerStore
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class UnlockTreeTest {
    private val actor = UUID(0, 151)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private val track = """kind: progression_track
id: practice
scope: class
levels:
  - {level: 1, xp: 0, awards: [{id: starter, type: talent_points, budget: talents, amount: 3}]}
  - {level: 2, xp: 100}"""
    private fun tree(cost: Int = 1, level: Int = 1): String = """kind: unlock_tree
id: choices
track: practice
nodes:
  wider:
    selection: talent
    requires: {type: level_at_least, track: practice, level: $level}
    cost: {budget: talents, amount: $cost}
    choice_group: offense
    grants: [{type: empowerment, ref: wider_cast}]
  other:
    selection: talent
    cost: {budget: talents, amount: 1}
    choice_group: offense
    grants: [{type: empowerment, ref: stronger_cast}]
  mastery:
    selection: automatic
    requires: {type: level_at_least, track: practice, level: 2}
    grants: [{type: empowerment, ref: stronger_cast}]"""
    private fun compile(fingerprint: String = "v1", tree: String = tree()): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/practice.yaml" to track,
            "workshop/choices.yaml" to tree,
            "workshop/wider.yaml" to "kind: empowerment\nid: wider_cast\nchanges:\n  - {type: replace, target: {ability: primary}, replacement: {ref: alternate}, priority: 10}",
            "workshop/stronger.yaml" to "kind: empowerment\nid: stronger_cast\nchanges:\n  - {type: replace, target: {ability: primary}, replacement: {ref: stronger}, priority: 20}",
            "workshop/primary.yaml" to "kind: ability\nid: primary\nname: Primary\neffects:\n  - {type: heal, target: actor, amount: 1}",
            "workshop/alternate.yaml" to "kind: ability\nid: alternate\nname: Alternate\neffects:\n  - {type: heal, target: actor, amount: 5}",
            "workshop/stronger-ability.yaml" to "kind: ability\nid: stronger\nname: Stronger\neffects:\n  - {type: heal, target: actor, amount: 9}",
            "workshop/bonus.yaml" to "kind: ability\nid: bonus\nname: Bonus\ncooldown: 1s\neffects:\n  - {type: heal, target: actor, amount: 7}",
            "workshop/passive.yaml" to "kind: ability\nid: learned_passive\nname: Learned passive\nactivation: {type: passive}\neffects:\n  - {type: heal, target: actor, amount: 2}",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  primary: {ref: primary}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }

    @Test fun talentChoiceAutomaticGrantAndHistoricalRespecPrice() {
        val world = World()
        val runtime = AbilityRuntime(world)
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        runtime.publish((compiled as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "wider"))
        assertEquals(2, runtime.progress(actor, "workshop:practice")?.points?.get("talents"))
        assertEquals("workshop:alternate", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        assertEquals(TalentResult.Rejected("choice group is already selected"), runtime.selectTalent(actor, "workshop:choices", "other"))
        runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation)
        assertEquals(listOf(5.0), world.heals)
        runtime.awardXp(actor, "workshop:practice", 100)
        assertEquals("workshop:stronger", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        runtime.publish((compile("v2", tree(cost = 3)) as CompileResult.Valid).definitions)
        assertEquals(1, runtime.respecTree(actor, "workshop:choices"))
        assertEquals(3, runtime.progress(actor, "workshop:practice")?.points?.get("talents"))
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "wider"))
        assertEquals(0, runtime.progress(actor, "workshop:practice")?.points?.get("talents"))
    }

    @Test fun editedRequirementRefundsInvalidChoiceAndKeepsEarnedXp(@TempDir directory: Path) {
        val runtime = AbilityRuntime(World())
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.selectTalent(actor, "workshop:choices", "wider")
        val store = PlayerStore(directory)
        store.save(actor, runtime.record(actor))
        val restored = AbilityRuntime(World())
        restored.publish((compile() as CompileResult.Valid).definitions)
        restored.installRecord(actor, store.load(actor)!!)
        assertEquals("workshop:alternate", restored.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        restored.publish((compile("v2", tree(level = 2)) as CompileResult.Valid).definitions)
        assertEquals("workshop:primary", restored.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        assertEquals(3, restored.progress(actor, "workshop:practice")?.points?.get("talents"))
        assertEquals(0, restored.progress(actor, "workshop:practice")?.earnedXp)
    }

    @Test fun prerequisiteCyclesFailValidation() {
        val broken = tree().replace("selection: talent\n    requires", "selection: talent\n    prerequisites: [wider]\n    requires")
        val invalid = compile(tree = broken) as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.problem.contains("cycle") })
    }

    @Test fun rankRefundsRememberEachHistoricalBudgetAndPrice() {
        val firstTree = tree(cost = 1).replace("    choice_group: offense\n    grants: [{type: empowerment, ref: wider_cast}]",
            "    choice_group: offense\n    ranks: 2\n    grants: [{type: empowerment, ref: wider_cast}]")
        val nextTree = tree(cost = 2).replace("    choice_group: offense\n    grants: [{type: empowerment, ref: wider_cast}]",
            "    choice_group: offense\n    ranks: 2\n    grants: [{type: empowerment, ref: wider_cast}]")
        val runtime = AbilityRuntime(World())
        runtime.publish((compile("v1", firstTree) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "wider"))
        runtime.publish((compile("v2", nextTree) as CompileResult.Valid).definitions)
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "wider"))
        assertEquals(0, runtime.progress(actor, "workshop:practice")?.points?.get("talents"))
        assertEquals(2, runtime.respecTree(actor, "workshop:choices"))
        assertEquals(3, runtime.progress(actor, "workshop:practice")?.points?.get("talents"))
    }

    @Test fun nodesGrantCastableAndPassiveAbilitiesWithStableCooldowns() {
        val tree = """kind: unlock_tree
id: choices
track: practice
nodes:
  bonus:
    selection: talent
    grants: [{type: ability, grant: bonus, ref: bonus, slot: extra}]
  passive:
    selection: talent
    grants: [{type: ability, grant: learned_passive, ref: learned_passive}]
  mastery:
    selection: automatic
    requires: {type: level_at_least, track: practice, level: 2}
    grants: [{type: ability, grant: mastery, ref: stronger}]"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(tree = tree) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertFalse("bonus" in runtime.grantsFor(actor, "workshop:mage"))
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "bonus"))
        assertEquals("extra", runtime.grantsFor(actor, "workshop:mage")["bonus"]?.slot)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "bonus", null, runtime.generation))
        assertEquals(listOf(7.0), world.heals)
        assertEquals(1, runtime.respecTree(actor, "workshop:choices"))
        assertFalse("bonus" in runtime.grantsFor(actor, "workshop:mage"))
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "bonus"))
        assertTrue(runtime.cooldownRemaining(actor, "workshop:mage", "bonus") > 0)
        assertEquals(CastResult.Rejected("cooldown is active"), runtime.cast(actor, "workshop:mage", "bonus", null, runtime.generation))
        assertEquals(TalentResult.Applied, runtime.selectTalent(actor, "workshop:choices", "passive"))
        assertEquals(listOf(7.0, 2.0), world.heals)
        runtime.awardXp(actor, "workshop:practice", 100)
        assertTrue("mastery" in runtime.grantsFor(actor, "workshop:mage"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "mastery", null, runtime.generation))
        assertEquals(listOf(7.0, 2.0, 9.0), world.heals)
        assertEquals(2, runtime.respecTree(actor, "workshop:choices"))
        assertFalse("learned_passive" in runtime.grantsFor(actor, "workshop:mage"))
        assertTrue("mastery" in runtime.grantsFor(actor, "workshop:mage"))
    }
}
