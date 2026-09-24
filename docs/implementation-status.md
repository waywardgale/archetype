# Implementation status

The accepted design remains the v1 release contract. This page describes the code currently present, not a change to that contract. The current jar is an implementation milestone and is not v1 complete.

| Module | Working in the current build | Still required for v1 |
| --- | --- | --- |
| Definitions | Strict captured YAML input; pack dependencies and IDs; `resource`, `ability`, and `class`; class grants by reference or inline; constant and bounded arithmetic using named effect results; located errors for unknown fields and operations | The remaining definition kinds; parameters; typed selectors and conditions beyond simple branch conditions; migration validation; generated schemas; the full mechanic catalog |
| Runtime | Server-owned class selection, resource costs and regeneration, cooldowns, actual damage/heal results, delayed/repeated/branched effects, bounded work, cancellation on death/logout/class switch/affected reload, resource clamp on reload | Activation modes beyond activated; charges, statuses, events/reactions, areas, chains, named timers, empowerments, progression, summon state, terrain ownership, and the rest of the accepted composition contracts |
| Minecraft integration | Native healing and damage against living entities, registry check for declared damage types, player save files, automatic whole-set reload, client-install check | Projectile and summon adapters, terrain journal and recovery, full combat event phases, richer targeting and native event attribution, running-world integration evidence |
| Client | Class cycling, eight remappable grant keys, simple HUD, versioned state sync | Class/talent screens, configurable HUD position/scale, declared media, target previews, summon orders, ability pages, full slot binding |
| Authoring | Offline `validatePacks` task using the runtime compiler | Generated editor schemas, mechanic reference, effective-definition inspector and traces |

All 16 scenarios in [ability-coverage.md](ability-coverage.md) remain release gates. The current tests demonstrate portions of result-based healing, timed flow, reload continuity, and cancellation. They do not establish completion of any entire scenario. No class content ships in the mod.

## Verification so far

- `./gradlew test build validatePacks` passes on JDK 25.
- A Fabric 26.2 development server loaded the mod and dependencies, then stopped at the Minecraft EULA gate before world startup. Gameplay and client rendering have not been exercised in a running world.
- The pure tests cover a valid pack, rejected unknown effects and duplicate YAML keys, cost/cooldown behavior, actual damage results feeding healing, reload generation and resource continuity, bounded repeated pulses, and death cancellation.
