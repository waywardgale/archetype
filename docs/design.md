# Archetype design interview

Status: consolidated design. Q1-Q85 and the requirement for broad manifest-only complex-ability coverage are accepted. The design is ready for shared-understanding confirmation before implementation.

The authoring contract is in [manifest-spec.md](manifest-spec.md), the five-module design is in [architecture.md](architecture.md), and [ability-coverage.md](ability-coverage.md) defines the required built-ins and 16 composition scenarios. [design-review.md](design-review.md) records the accepted final round. There is no implementation yet.

## Stated constraints

- Target Minecraft Java 26.2 with Fabric and Kotlin.
- Define classes, abilities, and mechanics declaratively using YAML manifests.
- Make most complex class abilities authorable out of the box through manifests, including structural changes to their mechanics. Complexity alone must not force a Kotlin change. New engine-level capabilities remain extensible through small registered implementations.
- Keep the framework simple, readable, and maintainable, with manifests that humans and AI agents can author easily.
- Do not provide command execution through manifests, including an `execute_command` operation.

## Starting point

The repository initially contains only `LICENSE` and Git metadata. There is no implementation or build configuration to preserve.

Manifests compose structured conditions and effect sequences with small arithmetic expressions. New interactions with Minecraft use Kotlin extensions. Authoring conventions and a shared mechanic registration approach are accepted below; the exact operation catalog, full manifest schema, and expression language remain open. No reference classes have been agreed as requirements.

## Settled decisions

### Product scope

- Archetype ships as a framework without bundled classes. The user designs class content, and subsequent feedback guides framework development. This interview must not impose a class roster or treat invented class examples as required content.
- Require Archetype on player clients and the server. The server controls gameplay outcomes; client code supports controls, feedback, and displays.
- Only players have classes. Ability effects may target mobs and projectiles without giving those entities classes. Manifest-defined projectiles and summon AI are included in v1. Summon behavior and ownership policies are recorded in [summons-and-terrain.md](summons-and-terrain.md); their concrete schema and remaining execution details still need review.
- Manifests configure class ownership and switching policies, and server operators can configure whether class changes are free or conditional. Progression is included in v1 and remains optional for each pack. Its model is recorded in the separate [progression discussion](progression.md).
- Target small co-op. The previously suggested 20–50-player validation target was not accepted and is not a requirement.

### Authoring and player controls

- Q6: Allow structured conditions, effect sequences, and small arithmetic expressions. Exclude embedded scripts and unrestricted loops. Kotlin extensions supply new interactions with Minecraft.
- Q7: Distinguish owned classes from active classes. Manifests configure the active-class limit, which defaults to one.
- Q8: Use bindable ability slots. Manifests assign abilities to slots, players choose their keys, and passive abilities need no slot.
- Q9: Saved manifest edits must apply automatically as soon as they validate, without a reload command, restart, or confirmation. Validate the complete definition set before applying any changes. Invalid edits retain the last working definitions and report the file, field, and problem. This replaces the earlier recommendation for explicit manual reload.
- Q10: Allow both inline abilities and separate reusable ability definitions. References and parameters provide reuse without inheritance chains.

"Immediately" means automatic application of valid saved changes. It does not require applying incomplete file writes or invalid YAML. Resource and cooldown continuity is settled below. Policies for renames, removals, and incompatible changes are recorded under Q70 in [manifest-authoring.md](manifest-authoring.md). Coordinating edits across multiple files and the concrete migration format still need specification.

### Ability behavior, content, and presentation

- Q11: When a definition changes, stop affected running abilities, clean up their ongoing effects, and immediately refresh passive bonuses. Unrelated abilities continue. Completed world changes, such as damage already dealt, remain. The precise classification of ongoing effects versus completed world changes still needs definition for each operation.
- Q12: Support activated, passive, toggled, channeled, and charged abilities using common conditions, targeting, and effects. Simple abilities require only a few fields; involved behavior adds optional sections.
- Q13: Resources and statuses are reusable manifest concepts. A resource is a named bounded value with optional regeneration. A status is a named condition with optional duration, stacks, and effects. Content authors define their names and behavior.
- Q14: Each world contains its own manifest packs. The host edits them, and Archetype synchronizes the information clients need. Players install the mod without manually copying YAML files.
- Q15: Supply a standard HUD for ability slots, cooldowns, and declared resources. Manifests provide labels, icons, and colors; players control positioning and scale. A general-purpose UI layout language is outside the initial scope.

### Player state and execution

- Q16: Match definitions by stable IDs during edits. Preserve resource amounts within new limits and keep remaining cooldowns. Changed cooldown durations apply to the next use; description edits do not refill resources or reset cooldowns.
- Q17: Save owned classes, active classes, resources, and cooldowns across logout and world restart. Player timers pause offline. Death keeps classes and cooldowns but resets resources to their declared initial values, with manifest overrides available. Death and logout cancel ongoing abilities. Temporary effects follow Q27; progression persistence is discussed separately.
- Q18: Grant access to all loaded classes by default and let players select and switch classes through the in-game HUD. Manifests can restrict availability and provide default switching rules. Operators can replace switching conditions and costs or allow free switching; ownership, progression requirements, and active-class limits still apply. Switching removes the outgoing class's ongoing effects while retaining its resource and cooldown state, so switching away and back does not refill resources or reset cooldowns.
- Q19: Consume resources and start cooldowns at ordinary activation, charge release, or channel start. Validate requirements before payment; failed starts cost nothing. Channels can additionally consume resources periodically. Interruptions do not refund committed costs by default; manifests can explicitly configure another policy.
- Q20: Effects may target players, mobs, world positions, and projectiles. Use readable selectors for self, aimed targets, nearby entities, and event participants, with filters such as range and entity type. Each effect declares its supported target types, and invalid combinations produce authoring errors. Projectile selection and manifest-defined projectile delivery are both included.

### Interactions between abilities

- Q21: Manifests can launch custom projectiles with declared speed, gravity, lifetime, and effects on entity hit, block hit, or expiry. Impact effects can refer to the shooter, hit target, and impact position. Collision policies are recorded under Q73 in [runtime-behavior.md](runtime-behavior.md); temporary projectile cleanup follows Q27.
- Q22: Reapplying a status refreshes its duration by default. Additional stacks require an explicit cap. Overlapping numeric bonuses use the strongest contribution unless the manifest requests capped addition. Track sources separately so removing one source's effect does not erase another source's contribution. Per-source duration, periodic effects, and Boolean restrictions follow Q74 in [runtime-behavior.md](runtime-behavior.md).
- Q23: Players and their owned creatures count as allies by default in small co-op. Harmful effects exclude allies unless manifests explicitly enable friendly fire, while respecting server PvP restrictions.
- Q24: Events caused by an ability's effects can trigger other abilities. Prevent the same reaction from re-entering its own causal chain, and cap total generated work so indirect loops terminate. Ordering and work-limit policies follow Q71 and Q75 in [runtime-behavior.md](runtime-behavior.md); concrete source identities and limit values still need specification.
- Q25: Give each active class its own ability page in the HUD and provide a bindable control to cycle pages. Changing pages leaves all active classes and their passives active. Classes can reuse slot names without conflicts.

### Release scope and authoring contracts

- Q26: V1 includes all previously accepted capabilities plus damage, healing, attributes, vanilla effects, movement, area effects, finite timed sequences, particles, and sounds. Summon AI, terrain editing, and progression are also required in v1. The proposal to defer those three systems was rejected. Their scope needs explicit design; they must not silently move to a later release.
- Q27: Channel and toggle effects end with their activation. Timed statuses and projectiles can outlive a completed cast but remain owned by their source class. Class switching, death, logout, dimension changes, affected reloads, and world shutdown clear that source's temporary effects. Passives reapply when appropriate. Completed damage, healing, and movement remain. Summon lifetime and terrain restoration follow the additional policies in [summons-and-terrain.md](summons-and-terrain.md).
- Q28: Use descriptive YAML fields, an explicit type for each operation, durations such as `2s`, distances in blocks, and damage in health points. Arithmetic uses an explicit `expr` field. Reject unknown fields and provide an editor schema for completion. Simple values remain plain YAML values. These conventions are accepted; the full grammar and expression language are not yet specified.
- Q29: Use stable namespaced IDs such as `pack:name`, with short names allowed inside their own pack. Cross-pack references require declared dependencies. Each pack declares a manifest format version. Duplicate IDs and unsupported versions are errors, avoiding behavior that depends on file loading order.
- Q30: Built-in mechanics and add-ons use the same small registration interfaces. Each mechanic declares its fields, supported targets, behavior, and cleanup requirements. These declarations also drive validation and authoring documentation. Keep a new effect's implementation and registration together.

## Decision tree

The first round settled the product scope above. The request for reference classes was replaced by user-led content design and iteration, rather than becoming a prerequisite for further design.

The second round was accepted with automatic application of manifest edits replacing manual reload.

The third round was accepted in full and is recorded under ability behavior, content, and presentation.

The fourth round was accepted, with class selection through the HUD and projectiles added as targets.

The fifth round was accepted in full and is recorded under interactions between abilities.

The sixth round was accepted with an expanded v1 scope: summon AI, terrain editing, and progression are required. Q31-Q50 are accepted and recorded in [progression.md](progression.md), including operator-controlled switching, customizable unlock trees, optional specializations, complete empowerment rewrites, and progression accounting. Shared rewards give the full amount to each eligible recipient by default; splitting is optional. WoW: Mists of Pandaria is a reference for abilities and talents.

Q51-Q65 are accepted in [summons-and-terrain.md](summons-and-terrain.md). They cover summon behavior, persistence, control, equipment and navigation, plus terrain operations, restoration, block data, physics, and patterns. Cleanup across chunk unloads and restarts is included.

Q66-Q70 are accepted in [manifest-authoring.md](manifest-authoring.md). They cover file organization, numeric expressions and structured conditions, read-only contexts and snapshots, typed reusable definitions, and dormant state with explicit ID migrations.

Q71-Q75 are accepted in [runtime-behavior.md](runtime-behavior.md). They cover event phases and ordering, missing targets, projectile collisions, per-source status behavior, and execution limits.

Q76-Q80 are accepted in the same document. They cover resource ownership, custom mechanic state, cooldown options, media assets, and author diagnostics.

Q81-Q85 are accepted in [design-review.md](design-review.md), including the concrete YAML grammar, five modules, native combat behavior, companion recall, durable recovery, and automatic reload publication. The user strengthened the v1 requirement: most complex abilities must be expressible without routine Kotlin changes. [ability-coverage.md](ability-coverage.md) adds explicit composition contracts and a release acceptance matrix. The numbered interview is complete; present the consolidated design for shared-understanding confirmation. No classes are bundled.

## Loading implementation guidance

These notes guide later implementation and do not indicate that any code exists. Parse YAML into supported data types without allowing manifests to construct arbitrary JVM objects. Check expected fields, types, ranges, and references on the server before applying definitions. Apply the same interpretation to validation and execution. This follows ASVS 1.5.2, 2.1.1, 2.1.2, 2.2.1–2.2.3, and 15.3.5 from the installed security-guidance references.

Validate and apply the same captured definition set, so a later file write cannot replace validated data during application. Coordinate publication with gameplay processing and keep the previous definitions if loading fails. The loading design must preserve useful author diagnostics without exposing stack traces to ordinary players. This follows ASVS 15.4.1–15.4.2 and 16.5.1–16.5.3. Precise concurrency and player-state reconciliation choices remain open.

The future ability execution design must validate and commit resource costs and cooldowns on the server in a defined order, preventing duplicate or concurrent input from spending the same available resource twice. This follows ASVS 2.3.1 and 2.3.4. The accepted timing and default refund policy are recorded under Q19; completed world effects are not assumed to be reversible transactions.

## Verified platform facts

Minecraft Java 26.2 was released on June 16, 2026. Fabric has a release-specific modding guide. [Minecraft release](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2), [Fabric guide](https://www.fabricmc.net/2026/06/15/262.html).

Fabric's setup documentation specifies JDK 25. Its project generator offers Kotlin support, and Fabric Language Kotlin supplies Kotlin entrypoints and runtime dependencies. Exact dependency versions remain unpinned and have not been build-tested here. [Environment setup](https://docs.fabricmc.net/develop/getting-started/setting-up), [Project creation](https://docs.fabricmc.net/develop/getting-started/creating-a-project), [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin).

Custom key bindings and HUD rendering use client APIs. Requiring installation on clients is therefore a product decision that affects controls and presentation. The implication for the product is an inference from these APIs. [Key mappings](https://docs.fabricmc.net/develop/key-mappings), [HUD](https://docs.fabricmc.net/develop/rendering/hud), [Networking](https://docs.fabricmc.net/develop/networking).
