# Targeting, chains, and areas in the current build

These operations are implemented. The complete v1 catalog and all release gates remain in [ability-coverage.md](ability-coverage.md). The fixture pack at [src/test/resources/packs/spatial](../src/test/resources/packs/spatial) supplies a complete, optional validation setup. It is outside the mod's main resources and is never installed into a world automatically.

## Editor and validator

Run `./gradlew exportCatalog` to generate `build/catalog/manifest.schema.json` and `build/catalog/effects.md`. Use `-PcatalogOutput=/absolute/path` to choose a destination. The effect fields, required fields, results, and lifecycle descriptions come from the registrations used by compilation and execution. The schema describes supported structure; reference resolution, result availability, positive intervals, related bounds, and recursive area creation still require the compiler.

Validate the fixture pack with `./gradlew validatePacks -Ppacks=src/test/resources/packs/spatial`. Native damage registry checks require server validation. The fixture pack contains one test class, six grants, a resource, an area, and two statuses. It demonstrates authoring, not a shipped class roster.

## Input and selection

An ability's optional `target` mapping selects `type: entity` or `type: ground`, with `range` between 0.01 and 32 blocks. Entity targeting is the default, with a 32-block range. The authenticated server validates supplied entity handles and can resolve aim when the client supplies none. Ground placement uses the player's current server eye position and view to find a blocking surface. Invalid starts spend no resource or cooldown. Ground indicators, confirmation input, and target previews remain unfinished.

Entity effects address `actor` or `target`. Spatial origins address `actor`, `target`, or `ground`. At the start of a ground ability, `target` is its captured position. Inside a selection or area member callback, `target` is that member; a spatial read uses its position. `ground` retains the captured ground position, or the area's current anchor in area callbacks.

A `for_each` makes a fresh bounded selection:

```yaml
effects:
  - type: for_each
    origin: actor
    targets:
      type: living_entities
      shape: {type: beam, length: 12, radius: 0.5}
      limit: 8
      filters: [{type: relation, is: enemy}]
    effects:
      - type: damage
        target: target
        amount: 2
        damage_type: minecraft:magic
```

The body has independent result bindings for each target and can read `selection.index`, starting at zero. Delayed descendants retain that target and revalidate its availability, geometry, filters, and obstruction against the captured selection frame. Losing a target skips its effects and result-dependent steps; unrelated steps continue. Results inside the body cannot escape into the surrounding sequence.

Selectors accept `type: living_entities`, `limit`, `include_actor`, `line_of_sight`, `order`, and `filters`. The default result limit is 16, the maximum is 64, the actor is excluded by default, and line of sight is required by default. Ordering supports `nearest`, `lowest_health`, and `highest_health`; health ordering uses the fraction of maximum health. Stable entity IDs break ties. Filters currently support `relation` with `any`, `ally`, `enemy`, or `self`, and `health_fraction` with optional `min` and `max` in 0..1. Players are allies by default, native team allies remain allies, and harmful effects cannot override that policy in this build.

Shapes include point, sphere, cylinder, ring, box, segment with the alias beam, and cone. Dimensions use blocks; cone `angle` is the full angle in degrees. Cylinder and ring heights are centered on their local origin. Box dimensions are full width, height, and depth. A segment extends forward from its origin with a finite radius and rounded ends. A point defaults to a 0.25-block radius. Every shape must fit within a 32-block enclosing radius.

Directional shapes use the actor's look direction and an orthonormal local frame. An attached area uses its anchor's live frame; a fixed area captures the frame at creation. Selection tests entity feet against the exact shape after a bounded native query. Explicit offsets, arbitrary rotations, world frames, block selectors, tags, stat ordering, and random ordering remain required for v1.

A query with more than 512 candidates interrupts its source and reports a diagnostic. It never silently selects the first native iteration prefix. Queries inspect loaded entities, and selection or ray checks do not request unloaded chunks. A ray requires the chunks in its enclosing horizontal rectangle to be loaded; this conservative check may reject a ray near unloaded chunks even when the line itself would miss them.

## Chains

```yaml
effects:
  - type: chain
    max_targets: 4
    hop_range: 6
    delay: 150ms
    revisit: false
    targets:
      type: living_entities
      filters: [{type: relation, is: enemy}]
    effects:
      - type: damage
        target: target
        amount: {expr: '8 / (chain.index + 1)'}
        damage_type: minecraft:magic
        as: hit
      - type: heal
        target: actor
        amount: {expr: 'result.hit.health_lost * 0.25'}
```

The initial selected entity counts toward `max_targets`, which must be 1..64. Each subsequent hop selects from the previous hit's captured position, even if that entity dies after the hit. `hop_range` must be positive and at most 32 blocks. The chain owns a visited set and ends when no eligible target remains. `revisit` defaults to false. With revisiting enabled, the immediately preceding target is still excluded. `delay` defaults to zero; a positive delay schedules hops on simulation ticks.

`targets` uses the selector fields above, with its sphere supplied by `hop_range`; do not provide a second shape. `chain.index` and `chain.hit_count` are local to the invocation and count preceding selected hits, starting at zero. A selected hit does not imply applied damage. Native `health_lost` is the value to use for lifesteal and confirmed-damage conditions. Attribution stays with the original actor. Hops and delayed bodies share the originating cast budget and stop with their source.

Healing now exposes `health_restored` and `overheal`. The runtime calculates overheal from native health metadata and the target's missing health before healing, so healing prevented for another reason does not become overheal. Target loss produces no result. No shield operation is present yet to consume that result.

## Areas

```yaml
kind: area
id: workshop:restoring_field
shape: {type: sphere, radius: 4}
duration: 8s
sample_every: 100ms
targets:
  type: living_entities
  include_actor: true
  limit: 16
  filters: [{type: relation, is: ally}]
periodic:
  every: 1s
  effects:
    - type: heal
      target: target
      amount: 2
```

Create a ground field with the following ability body:

```yaml
target: {type: ground, range: 16}
effects:
  - type: create_area
    area: {ref: restoring_field}
    anchor: {position: target}
```

Use `anchor: {attached: actor}` for a moving aura. A fixed position can also snapshot the actor or selected entity. An attached anchor must be the actor or selected entity. References accept a local or namespaced string, or `{ref: ...}`. `targets` receives its shape from the area definition and must not declare a second shape.

Areas declare exactly one of a finite positive `duration` or `lifetime: maintained`. A maintained area lasts until its source class, passive, owner, anchor, or other owning scope ends; it does not run `expired` on cancellation. Sampling defaults to 100 ms and must use a positive interval. Optional `enter` and `exit` lists run once for each membership transition. Initial occupants enter immediately. An optional `periodic` mapping has a positive `every` and an effect list, invoked separately for each current member after the first interval. `expired` runs only for finite natural expiry and is an optional area-wide effect list with the actor and anchor position; it has no member entity target.

Membership reconciles before periodic pulses and before delayed descendants due on the same tick. A departed member's outstanding membership work is canceled independently of other areas. Expiry cancels membership work before running `expired` once. Cancellation from death, logout, class switch, affected reload, invalid attachment, or anchor chunk unload invokes neither ordinary exit nor expiry gameplay actions. Recursive area creation references fail compilation.

Each registered controller pulse receives a new bounded execution budget. Delayed descendants keep that pulse's budget and membership lifetime. Current limits are 1024 work units per batch, 2048 per owner per tick, 4096 globally per tick, 128 queued continuations per owner, 2048 queued globally, 32 areas per owner, and 512 areas globally. Known excessive setup and pulse work is rejected before payment; dynamic overload cancels the source. Operator configuration for these bounds remains unfinished.

Membership ownership covers delayed effects, nested controllers, and declared `buffs`. An area's optional `buffs` list references statuses that remain active for each membership. Each overlapping area owns its contribution, periodic schedule, and delayed status work. Departure, area expiry, or cancellation removes that contribution before due descendants. Membership loss does not invoke the status's ordinary expiry callback. See [status authoring](status-authoring.md) for syntax, source accounting, and numeric composition. Maintained lifetimes, area handles, recasts, and correlated event waits remain required. Running-world evidence for the ground-field and moving-aura release scenarios remains absent.

## Mechanic extensions

`EffectMechanic` registers a namespaced type, a typed configuration class, field metadata, required fields, input and result contracts, context requirements, reference dependencies, a decoder, and an execution handler. Built-in short names resolve to `archetype:<name>`; fully qualified names work too. `MechanicCatalog` supplies the same registrations to `ManifestCompiler`, `AbilityRuntime`, and `CatalogExport`.

A Fabric add-on registers through `ArchetypeMod.registerMechanic` during initialization, before the first server starts. The catalog then freezes for the process. Offline exports default to built-ins; an extension tool can pass its catalog explicitly.

An extension decodes through `EffectReader` and executes through bounded `EffectExecution` services. It receives no YAML map at execution. The extension test adds a resource mechanic and binds its result without modifying compiler or runtime dispatch. Handlers can now apply a status through `EffectExecution.status`, and `statusReferences` participates in reference checks, cycle checks, and reload dependencies. `EffectReader.identity` supplies the authored effect ID or its structural field path. Additional native operations and general contribution cleanup services still need their engine contracts before world-effect extensions can use them.
