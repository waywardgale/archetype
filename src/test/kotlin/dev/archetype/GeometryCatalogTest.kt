package dev.archetype

import dev.archetype.authoring.CatalogExport
import dev.archetype.definitions.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GeometryCatalogTest {
    @Test fun `complete spatial authoring fixtures compile without launching Minecraft`() {
        val root = Path.of(javaClass.getResource("/packs/spatial")!!.toURI())
        val result = ManifestCompiler().compile(PackCapture.capture(root))
        assertTrue(result is CompileResult.Valid, "$result")
        val definitions = (result as CompileResult.Valid).definitions
        assertEquals(6, definitions.classes.getValue("workshop:validation_actor").grants.size)
        assertEquals(1, definitions.areas.size)
        assertEquals(2, definitions.statuses.size)
    }
    @Test fun `all combat shapes include boundaries and exclude nearby points outside`() {
        val cases = listOf(
            Triple(Shape.Point(0.25), Vec(0.25, 0.0, 0.0), Vec(0.251, 0.0, 0.0)),
            Triple(Shape.Sphere(2.0), Vec(0.0, 2.0, 0.0), Vec(0.0, 2.01, 0.0)),
            Triple(Shape.Cylinder(2.0, 4.0), Vec(2.0, 2.0, 0.0), Vec(0.0, 2.01, 0.0)),
            Triple(Shape.Ring(1.0, 2.0, 2.0), Vec(2.0, 1.0, 0.0), Vec(0.99, 0.0, 0.0)),
            Triple(Shape.Box(2.0, 4.0, 6.0), Vec(1.0, 2.0, 3.0), Vec(1.01, 0.0, 0.0)),
            Triple(Shape.Segment(4.0, 0.5), Vec(0.0, 0.0, 4.5), Vec(0.0, 0.0, 4.51)),
            Triple(Shape.Cone(4.0, 90.0), Vec(0.0, 0.0, 4.0), Vec(0.0, 0.0, -0.1)),
        )
        for ((shape, inside, outside) in cases) {
            assertTrue(shape.contains(inside), "$shape should contain $inside")
            assertFalse(shape.contains(outside), "$shape should exclude $outside")
            assertTrue(inside.lengthSquared() <= shape.bound * shape.bound + 1e-9, "$shape has an insufficient broad query bound")
        }
    }

    @Test fun `directional shapes use a local frame with stable axes including vertical aim`() {
        val origin = Position("test", Vec(10.0, 5.0, -3.0))
        val forwardX = Frame(origin, Vec(1.0, 0.0, 0.0))
        assertEquals(Vec(0.0, 0.0, 3.0), forwardX.local(Vec(13.0, 5.0, -3.0)))
        assertTrue(Shape.Segment(4.0, 0.25).contains(forwardX.local(Vec(13.0, 5.0, -3.0))))
        val forwardUp = Frame(origin, Vec(0.0, 1.0, 0.0))
        assertEquals(Vec(0.0, 0.0, 3.0), forwardUp.local(Vec(10.0, 8.0, -3.0)))
        assertTrue(forwardUp.local(Vec(11.0, 8.0, -3.0)).finite())
    }

    @Test fun `exported schema and reference contain every registered effect and resolve all local references`() {
        val schema = CatalogExport.schema()
        val definitions = schema["\$defs"] as Map<*, *>
        fun walk(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    value["\$ref"]?.let { assertTrue(it is String && it.startsWith("#/\$defs/") && it.substringAfterLast('/') in definitions, "$it") }
                    value.values.forEach(::walk)
                }
                is Iterable<*> -> value.forEach(::walk)
            }
        }
        walk(schema)
        val effects = (definitions["effect"] as Map<*, *>)["oneOf"] as List<*>
        val types = effects.map { ((it as Map<*, *>)["properties"] as Map<*, *>)["type"] as Map<*, *> }.map { it["const"] ?: (it["enum"] as List<*>).first() }.toSet()
        assertEquals(BuiltinEffects.catalog.effects.keys, types)
        val reference = CatalogExport.reference()
        for (mechanic in BuiltinEffects.catalog.effects.values) {
            assertTrue(reference.contains("## ${mechanic.type}\n"))
            for (field in mechanic.fields.keys) assertTrue(reference.contains("`$field`"))
        }
    }
}
