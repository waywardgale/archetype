package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.minecraft.PlayerStore
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class SpecializationRuntimeTest {
    private val player = UUID(0, 771)
    private val classId = "workshop:mage"

    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }

    private fun compile(extra: Pair<String, String>? = null): CompileResult {
        val files = mutableListOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  base: {ref: base}",
            "workshop/base.yaml" to "kind: ability\nid: base\nname: Base\neffects: [{type: heal, target: actor, amount: 1}]",
            "workshop/flame.yaml" to "kind: ability\nid: flame\nname: Flame\ncooldown: 2s\neffects: [{type: heal, target: actor, amount: 2}]",
            "workshop/frost.yaml" to "kind: ability\nid: frost\nname: Frost\ncooldown: 2s\neffects: [{type: heal, target: actor, amount: 3}]",
            "workshop/fire-spec.yaml" to "kind: specialization\nid: fire\nname: Fire\nclass: mage\nabilities:\n  spark: {ref: flame}",
            "workshop/ice-spec.yaml" to "kind: specialization\nid: ice\nname: Ice\nclass: mage\nabilities:\n  spark: {ref: frost}",
            "workshop/practice.yaml" to "kind: progression_track\nid: practice\nscope: class\nlevels: [{level: 1, xp: 0}]",
            "workshop/fire-tree.yaml" to """kind: unlock_tree
id: fire_tree
track: practice
specializations: [fire]
nodes:
  bonus:
    selection: automatic
    grants: [{type: ability, ref: base, grant: spec_bonus}]""",
            "workshop/ice-tree.yaml" to """kind: unlock_tree
id: ice_tree
track: practice
specializations: [ice]
nodes:
  bonus:
    selection: automatic
    grants: [{type: ability, ref: frost, grant: spec_bonus}]""",
        )
        if (extra != null) files += extra
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "spec"))
    }

    @Test fun `specializations share class ownership and retain logical cooldown`() {
        val compiled = compile() as CompileResult.Valid
        val runtime = AbilityRuntime(World())
        runtime.publish(compiled.definitions)
        assertTrue(runtime.selectClass(player, classId))
        assertFalse("spark" in runtime.grantsFor(player, classId))
        assertFalse(runtime.selectSpecialization(player, classId, "workshop:unknown"))
        assertTrue(runtime.selectSpecialization(player, classId, "workshop:fire"))
        assertEquals("workshop:flame", runtime.effectiveAbility(player, classId, "spark")?.id)
        assertEquals("workshop:base", runtime.effectiveAbility(player, classId, "spec_bonus")?.id)
        assertEquals(CastResult.Applied, runtime.cast(player, classId, "spark", null, runtime.generation))
        assertTrue(runtime.cooldownRemaining(player, classId, "spark") > 0)
        assertTrue(runtime.selectSpecialization(player, classId, "workshop:ice"))
        assertEquals("workshop:frost", runtime.effectiveAbility(player, classId, "spark")?.id)
        assertEquals("workshop:frost", runtime.effectiveAbility(player, classId, "spec_bonus")?.id)
        assertTrue(runtime.cooldownRemaining(player, classId, "spark") > 0)
        assertTrue("base" in runtime.grantsFor(player, classId))
    }

    @Test fun `chosen specialization survives player save`(@TempDir directory: Path) {
        val compiled = compile() as CompileResult.Valid
        val runtime = AbilityRuntime(World())
        runtime.publish(compiled.definitions)
        runtime.selectClass(player, classId)
        runtime.selectSpecialization(player, classId, "workshop:ice")
        PlayerStore(directory).save(player, runtime.record(player))
        val restored = AbilityRuntime(World())
        restored.publish(compiled.definitions)
        restored.installRecord(player, PlayerStore(directory).load(player)!!)
        assertEquals("workshop:ice", restored.record(player).specializations[classId])
        assertEquals("workshop:frost", restored.effectiveAbility(player, classId, "spark")?.id)
    }

    @Test fun `removed specialization stays dormant and returns without refilling its logical state`() {
        val definitions = (compile() as CompileResult.Valid).definitions
        val runtime = AbilityRuntime(World())
        runtime.publish(definitions)
        runtime.selectClass(player, classId)
        runtime.selectSpecialization(player, classId, "workshop:fire")
        runtime.cast(player, classId, "spark", null, runtime.generation)
        val remaining = runtime.cooldownRemaining(player, classId, "spark")
        runtime.publish(definitions.copy(fingerprint = "removed", specializations = emptyMap(), unlockTrees = emptyMap()))
        assertEquals("", runtime.activeSpecialization(player, classId))
        assertFalse("spark" in runtime.grantsFor(player, classId))
        assertEquals("workshop:fire", runtime.record(player).specializations[classId])
        runtime.publish(definitions.copy(fingerprint = "restored"))
        assertEquals("workshop:fire", runtime.activeSpecialization(player, classId))
        assertEquals(remaining, runtime.cooldownRemaining(player, classId, "spark"))
    }

    @Test fun `specialization cannot replace a base logical grant`() {
        val invalid = compile("workshop/bad-spec.yaml" to
            "kind: specialization\nid: invalid\nname: Invalid\nclass: mage\nabilities:\n  base: {ref: frost}") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "abilities.base" })
    }
}
