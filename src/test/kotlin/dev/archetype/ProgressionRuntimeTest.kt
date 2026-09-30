package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.minecraft.PlayerStore
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ProgressionRuntimeTest {
    private val actor = UUID(0, 141)
    private class World : WorldOps {
        var nearby: List<UUID> = emptyList()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun nearbyAlliedPlayers(actor: UUID, origin: Position, radius: Double, limit: Int) = nearby
    }
    private fun compile(fingerprint: String = "v1", track: String = """kind: progression_track
id: practice
scope: class
levels:
  - {level: 1, xp: 0, awards: [{id: first, type: talent_points, budget: talents, amount: 1}]}
  - {level: 2, xp: 100, awards: [{id: second, type: talent_points, budget: talents, amount: 2}]}
  - {level: 3, xp: 200}
earn:
  credited_defeat: {event: entity_death, xp: 60}"""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/practice.yaml" to track,
            "workshop/ability.yaml" to "kind: ability\nid: heal\nname: Heal\neffects:\n  - {type: heal, target: actor, amount: 1}",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  heal: {ref: heal}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  heal: {ref: heal}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }

    @Test fun classXpAndOneTimeAwardsSurviveSwitchDeathAndCurveEdit() {
        val runtime = AbilityRuntime(World())
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(ProgressView("workshop:practice", "workshop:mage", 0, 1, mapOf("talents" to 1)),
            runtime.progress(actor, "workshop:practice"))
        runtime.onEntityDeath(actor)
        runtime.onEntityDeath(actor)
        assertEquals(ProgressView("workshop:practice", "workshop:mage", 120, 2, mapOf("talents" to 3)),
            runtime.progress(actor, "workshop:practice"))
        runtime.selectClass(actor, "workshop:other")
        assertEquals(0, runtime.progress(actor, "workshop:practice")?.earnedXp)
        runtime.selectClass(actor, "workshop:mage")
        runtime.onDeath(actor)
        assertEquals(120, runtime.progress(actor, "workshop:practice")?.earnedXp)
        val edited = """kind: progression_track
id: practice
scope: class
levels:
  - {level: 1, xp: 0, awards: [{id: first, type: talent_points, budget: talents, amount: 99}]}
  - {level: 2, xp: 150, awards: [{id: second, type: talent_points, budget: talents, amount: 2}]}
  - {level: 3, xp: 250}
earn:
  credited_defeat: {event: entity_death, xp: 60}"""
        runtime.publish((compile("v2", edited) as CompileResult.Valid).definitions)
        assertEquals(ProgressView("workshop:practice", "workshop:mage", 120, 1, mapOf("talents" to 3)),
            runtime.progress(actor, "workshop:practice"))
        runtime.onEntityDeath(actor)
        assertEquals(ProgressView("workshop:practice", "workshop:mage", 180, 2, mapOf("talents" to 3)),
            runtime.progress(actor, "workshop:practice"))
    }

    @Test fun capStopsOrBanksOverflowWithoutDeletingExistingXp() {
        val stopped = """kind: progression_track
id: practice
scope: player
levels: [{level: 1, xp: 0}, {level: 2, xp: 100}]
cap: {level: 2, overflow: stop}"""
        val runtime = AbilityRuntime(World())
        runtime.publish((compile("stop", stopped) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(100, runtime.awardXp(actor, "workshop:practice", 150))
        assertEquals(0, runtime.awardXp(actor, "workshop:practice", 10))
        val banked = stopped.replace("overflow: stop", "overflow: bank")
        runtime.publish((compile("bank", banked) as CompileResult.Valid).definitions)
        assertEquals(50, runtime.awardXp(actor, "workshop:practice", 50))
        assertEquals(150, runtime.progress(actor, "workshop:practice")?.earnedXp)
        runtime.publish((compile("stop-again", stopped) as CompileResult.Valid).definitions)
        assertEquals(150, runtime.progress(actor, "workshop:practice")?.earnedXp)
        assertEquals(0, runtime.awardXp(actor, "workshop:practice", 10))
    }

    @Test fun progressionRoundTripsThroughPlayerStore(@TempDir directory: Path) {
        val runtime = AbilityRuntime(World())
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.onEntityDeath(actor)
        runtime.onEntityDeath(actor)
        val store = PlayerStore(directory)
        store.save(actor, runtime.record(actor))
        val loaded = store.load(actor)
        assertNotNull(loaded)
        val restored = AbilityRuntime(World())
        restored.publish((compile() as CompileResult.Valid).definitions)
        restored.installRecord(actor, loaded!!)
        assertEquals(ProgressView("workshop:practice", "workshop:mage", 120, 2, mapOf("talents" to 3)),
            restored.progress(actor, "workshop:practice"))
    }

    @Test fun invalidThresholdsAndDuplicateAwardsAreRejected() {
        val bad = compile(track = """kind: progression_track
id: practice
scope: class
levels:
  - {level: 1, xp: 0, awards: [{id: same, type: talent_points, budget: talents, amount: 1}]}
  - {level: 2, xp: 0, awards: [{id: same, type: talent_points, budget: talents, amount: 1}]}""") as CompileResult.Invalid
        assertTrue(bad.diagnostics.any { it.problem.contains("thresholds") || it.problem.contains("award ID") })
    }

    @Test fun nearbyAwardsDeduplicateRecipientsAndSplitOnlyAmongEligibleClassOwners() {
        val second = UUID(0, 142)
        val ineligible = UUID(0, 143)
        val track = """kind: progression_track
id: practice
scope: class
classes: [mage]
levels: [{level: 1, xp: 0}]
earn:
  shared: {event: entity_death, phase: after, when: {type: credited_to_owner}, amount: 5, recipients: {type: nearby_allies, range: 24}, distribution: split}
  each: {event: entity_death, amount: 3, recipients: {type: nearby_allies, range: 24}}"""
        val world = World().apply { nearby = listOf(second, actor, second, ineligible) }
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(track = track) as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.selectClass(second, "workshop:mage")
        runtime.selectClass(ineligible, "workshop:other")
        runtime.onEntityDeath(actor, Position("test", Vec(0.0, 0.0, 0.0)))
        assertEquals(6, runtime.progress(actor, "workshop:practice")?.earnedXp)
        assertEquals(5, runtime.progress(second, "workshop:practice")?.earnedXp)
        assertNull(runtime.progress(ineligible, "workshop:practice"))
        assertEquals(0, runtime.awardXp(ineligible, "workshop:practice", 10))
        runtime.onEntityDeath(actor)
        assertEquals(6, runtime.progress(actor, "workshop:practice")?.earnedXp)
    }

    @Test fun malformedEarningPoliciesAreRejectedAtLocatedPaths() {
        val malformed = """kind: progression_track
id: practice
scope: class
levels: [{level: 1, xp: 0}]
earn:
  shared: {event: entity_death, amount: 4, recipients: {type: nearby_allies, range: 100}, distribution: split}"""
        val result = compile(track = malformed) as CompileResult.Invalid
        assertTrue(result.diagnostics.any { it.field.contains("earn.shared.recipients.range") })
    }

    @Test fun defaultClassTrackDoesNotAwardUnrelatedPackClasses() {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/practice.yaml" to "kind: progression_track\nid: practice\nscope: class\nlevels: [{level: 1, xp: 0}]\nearn:\n  defeat: {event: entity_death, amount: 5}",
            "outsider/pack.yaml" to "format: 1\nid: outsider\nname: Outsider",
            "outsider/class.yaml" to "kind: class\nid: rogue\nname: Rogue\nabilities: {}",
        )
        val result = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "isolation"))
        assertTrue(result is CompileResult.Valid, "$result")
        val runtime = AbilityRuntime(World())
        runtime.publish((result as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "outsider:rogue")
        runtime.onEntityDeath(actor)
        assertNull(runtime.progress(actor, "workshop:practice"))
    }
}
