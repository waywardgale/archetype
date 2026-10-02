package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TerrainPatternRuntimeTest {
    private val actor = UUID(0, 1)

    private fun compile(pattern: String = "#_", effect: String =
        "{type: place_pattern, pattern: short_wall, at: ground, rotation: 90, mirror: x, duration: 3s, as: terrain}"):
        CompileResult = ManifestCompiler().compile(PackSnapshot(listOf(
            SourceFile("workshop/pack.yaml", "format: 1\nid: workshop\nname: Workshop".toByteArray()),
            SourceFile("workshop/class.yaml", "kind: class\nid: builder\nname: Builder\nabilities:\n  wall: {ref: wall}".toByteArray()),
            SourceFile("workshop/pattern.yaml", """kind: block_pattern
id: short_wall
origin: [0, 0, 0]
palette:
  '#': {block: minecraft:stone}
  '_': {block: minecraft:air}
  '.': {skip: true}
layers:
  - ['$pattern']""".toByteArray()),
            SourceFile("workshop/wall.yaml", """kind: ability
id: wall
name: Wall
target: {type: ground, range: 16}
effects:
  - $effect""".toByteArray()),
        ), "terrain"))

    private class World : WorldOps {
        var placed: BlockPatternDef? = null
        var rotation = -1
        var mirror = PatternMirror.NONE
        var duration: Int? = null
        var terrainEdit: Effect.EditTerrain? = null
        val activeTerrain = mutableSetOf<UUID>()
        val cancelledTerrain = mutableSetOf<UUID>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun availableTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("minecraft:overworld", Vec(0.0, 70.0, 0.0))
        override fun ground(actor: UUID, range: Double) = Position("minecraft:overworld", Vec(1.0, 70.0, 2.0))
        override fun loaded(position: Position) = true
        override fun placePattern(actor: UUID, source: UUID, pattern: BlockPatternDef, at: Position,
            rotation: Int, mirror: PatternMirror, durationTicks: Int?, allowFluid: Boolean,
            allowGravity: Boolean): Int? {
            placed = pattern; this.rotation = rotation; this.mirror = mirror; duration = durationTicks
            if (durationTicks != null) activeTerrain += source
            return pattern.cells.size
        }
        override fun editTerrain(actor: UUID, source: UUID, edit: Effect.EditTerrain, at: Position): Int? {
            terrainEdit = edit
            if (edit.durationTicks != null) activeTerrain += source
            return 2
        }
        override fun terrainActive(source: UUID) = source in activeTerrain
        override fun cancelTerrain(source: UUID) { activeTerrain -= source; cancelledTerrain += source }
    }

    @Test fun `authored pattern reaches the runtime with skip and air distinguished`() {
        val compiled = compile("#._")
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:builder"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:builder", "wall", null, runtime.generation))
        assertEquals(listOf(PatternCell(0, 0, 0, "minecraft:stone"), PatternCell(2, 0, 0, "minecraft:air")),
            world.placed?.cells)
        assertEquals(90, world.rotation)
        assertEquals(PatternMirror.X, world.mirror)
        assertEquals(60, world.duration)
    }

    @Test fun `invalid symbols references and rotations are located compile errors`() {
        for (result in listOf(compile("#?"), compile(effect =
            "{type: place_pattern, pattern: missing, at: ground}"), compile(effect =
            "{type: place_pattern, pattern: short_wall, at: ground, rotation: 45}"))) {
            assertTrue(result is CompileResult.Invalid, "$result")
            assertTrue((result as CompileResult.Invalid).diagnostics.isNotEmpty())
        }
    }

    @Test fun `bounded filtered box edit executes as a typed operation`() {
        val effect = """type: edit_terrain
    operation: replace
    at: ground
    region: {type: box, size: [2, 1, 2]}
    block: minecraft:stone_bricks
    filter: {blocks: [minecraft:dirt], tags: [minecraft:flowers]}
    duration: 4s"""
        val compiled = compile(effect = effect)
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:builder"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:builder", "wall", null, runtime.generation))
        assertEquals(TerrainRegion.Box(2, 1, 2), world.terrainEdit?.region)
        assertEquals(TerrainFilter(setOf("minecraft:dirt"), setOf("minecraft:flowers")), world.terrainEdit?.filter)
        assertEquals(80, world.terrainEdit?.durationTicks)
    }

    @Test fun `invalid terrain geometry loot and missing replace filter are rejected`() {
        val effects = listOf(
            "{type: edit_terrain, operation: set, at: ground, region: {type: box, size: [16, 16, 16]}, block: minecraft:stone}",
            "{type: edit_terrain, operation: replace, at: ground, region: {type: point}, block: minecraft:stone}",
            "{type: edit_terrain, operation: break, at: ground, region: {type: point}, loot: true}",
        )
        for (effect in effects) assertTrue(compile(effect = effect) is CompileResult.Invalid, effect)
    }

    @Test fun `player lifecycle cancels its temporary terrain source`() {
        val compiled = compile() as CompileResult.Valid
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        assertTrue(runtime.selectClass(actor, "workshop:builder"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:builder", "wall", null, runtime.generation))
        assertEquals(1, world.activeTerrain.size)
        runtime.onLogout(actor)
        assertTrue(world.activeTerrain.isEmpty())
        assertEquals(1, world.cancelledTerrain.size)
    }
}
