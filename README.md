# Archetype

Archetype is a Minecraft Java 26.2 Fabric mod for manifest-authored classes and abilities. The implementation is in progress. The accepted v1 contract remains in [docs/design.md](docs/design.md) and [docs/ability-coverage.md](docs/ability-coverage.md); [docs/implementation-status.md](docs/implementation-status.md) records what the current build can run.

## Build and validate

JDK 25 is required. Build with `./gradlew build`. The mod jar is `build/libs/archetype-0.1.0.jar`; install it with matching Fabric API and Fabric Language Kotlin on the server and every player client.

The server reads packs from `<world>/archetype/packs/<pack-id>/`. Save a valid manifest file and Archetype applies the captured definition set after roughly 200 ms of stable writes. Invalid edits keep the previous definitions. Operators can use `/archetype diagnostics`; players can use `/archetype list` and `/archetype select <pack:class>`.

Run `./gradlew validatePacks -Ppacks=/absolute/path/to/world/archetype/packs` to check supported manifest structure and references without starting Minecraft. Native registry checks still run on the server.

Run `./gradlew exportCatalog` for the supported editor schema and effect reference in `build/catalog/`. [Spatial authoring](docs/spatial-authoring.md) documents shaped selections, chains, fixed fields, and attached areas. [Status authoring](docs/status-authoring.md) documents timed contributions, stacks, periodic effects, area membership buffs, tags, presence conditions, and dispels. Validate the optional fixture pack with `./gradlew validatePacks -Ppacks=src/test/resources/packs/spatial`; it is not installed into worlds automatically.

With your own agreement to the [Minecraft EULA](https://aka.ms/MinecraftEULA), run `./gradlew runGameTest -PacceptMinecraftEulaForTests=true` for focused native server tests. The flag is opt-in and ordinary builds do not record EULA agreement. [Movement authoring](docs/movement-authoring.md) covers dash, push/pull, and safe teleport.

The client uses B to cycle classes, R for the first ability grant, and the bracket keys to change ability pages. Seven more ability keys are available in Controls and start unbound. The current HUD shows the active class, current grant page, cooldowns, and resource amounts.
The current build also shows grant charges and their next recharge. [Timers and charges](docs/timers-and-charges.md) documents the supported manifest fields and source cleanup rules.
[Flow authoring](docs/flow-authoring.md) covers bounded condition composition, weighted choices, and parallel joins.
[Resource authoring](docs/resource-authoring.md) covers balance edits and reset behavior.
[Barrier authoring](docs/barrier-authoring.md) covers native damage absorption, priority, filtering, and depletion callbacks.
[Passive authoring](docs/passive-authoring.md) covers setup and class-owned cleanup.
[State authoring](docs/state-authoring.md) covers bounded class, player, and activation fields and their lifecycle.
[Projectile authoring](docs/projectile-authoring.md) covers the current first-impact delivery path and callback lifetimes.
[Activation authoring](docs/activation-authoring.md) covers toggles, channels, and charged hold/release input.
[Form authoring](docs/form-authoring.md) covers timed complete ability replacement and restoration.
[Progression authoring](docs/progression-authoring.md) covers the current XP, point-award, talent, and empowerment subset.
[Specialization authoring](docs/specialization-authoring.md) covers class-bound grants, saved choices, and specialization-specific talent trees.
[Terrain authoring](docs/terrain-authoring.md) covers block palettes, filtered region edits, temporary ownership, and permanent breaks.

Operators can run `/archetype inspect [online-player-name]` to see the current server generation, active class, resolved grants, resources, declared state, and active statuses, timers, and barriers. `/archetype diagnostics` shows rejected manifest edits.

This build supports a limited set of definitions and mechanics. Packs using the remaining accepted v1 features fail validation with a located diagnostic; the compiler does not silently ignore them.
