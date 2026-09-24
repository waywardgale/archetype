# Archetype

Archetype is a Minecraft Java 26.2 Fabric mod for manifest-authored classes and abilities. The implementation is in progress. The accepted v1 contract remains in [docs/design.md](docs/design.md) and [docs/ability-coverage.md](docs/ability-coverage.md); [docs/implementation-status.md](docs/implementation-status.md) records what the current build can run.

## Build and validate

JDK 25 is required. Build with `./gradlew build`. The mod jar is `build/libs/archetype-0.1.0.jar`; install it with matching Fabric API and Fabric Language Kotlin on the server and every player client.

The server reads packs from `<world>/archetype/packs/<pack-id>/`. Save a valid manifest file and Archetype applies the captured definition set after roughly 200 ms of stable writes. Invalid edits keep the previous definitions. Operators can use `/archetype diagnostics`; players can use `/archetype list` and `/archetype select <pack:class>`.

Run `./gradlew validatePacks -Ppacks=/absolute/path/to/world/archetype/packs` to check supported manifest structure and references without starting Minecraft. Native registry checks still run on the server.

The client uses B to cycle classes and R for the first ability grant. Seven more ability keys are available in Controls and start unbound. The current HUD shows the active class, grants, cooldowns, and resource amounts.

This build supports a limited set of definitions and mechanics. Packs using the remaining accepted v1 features fail validation with a located diagnostic; the compiler does not silently ignore them.
