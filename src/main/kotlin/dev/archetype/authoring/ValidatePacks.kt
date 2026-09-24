package dev.archetype.authoring

import dev.archetype.definitions.CompileResult
import dev.archetype.definitions.ManifestCompiler
import dev.archetype.definitions.PackCapture
import java.nio.file.Path
import kotlin.system.exitProcess

/** Same compiler as hot reload. Native registry checks still require a running server. */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("Usage: validatePacks <world/archetype/packs>")
        exitProcess(2)
    }
    val result = try { ManifestCompiler().compile(PackCapture.capture(Path.of(args[0]))) }
    catch (failure: Exception) {
        System.err.println("Cannot capture packs: ${failure.message}")
        exitProcess(2)
    }
    when (result) {
        is CompileResult.Invalid -> {
            result.diagnostics.forEach(System.err::println)
            exitProcess(1)
        }
        is CompileResult.Valid -> {
            val definitions = result.definitions
            val grants = definitions.classes.values.sumOf { it.grants.size }
            println("Valid: ${definitions.packs.size} packs, ${definitions.classes.size} classes, ${definitions.abilities.size} named abilities, $grants grants, ${definitions.resources.size} resources")
            println("Native registry and world checks require server validation.")
        }
    }
}
