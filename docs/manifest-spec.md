# Manifest specification

Status: accepted design baseline under Q81-Q85, expanded by the complex-ability coverage requirement. [ability-coverage.md](ability-coverage.md) defines the required v1 composition capabilities and mechanic scenarios. Nothing here is a shipped parser or bundled class. Examples are isolated documentation fragments, not a required class roster or a pack installed into a world.

## Common authoring shape

Use a small vocabulary throughout: `kind` identifies a definition, `id` gives its stable identity, `type` selects a registered operation, and `ref` with optional `with` reuses another definition. Ordinary scalar values remain ordinary YAML. Only calculated numbers use `expr`.

Keep optional behavior absent until needed. A healing ability needs no custom resource, progression track, state block, or separate effect file:

```yaml
kind: ability
id: workshop:restore
name: Restore
cooldown: 8s
effects:
  - type: heal
    target: actor
    amount: 6
```

Activated is the default ability mode. Damage and healing use health points, distances use blocks, and durations use explicit units such as `250ms`, `2s`, or `1m`. Durations advance on server simulation time and are rounded up to a supported tick boundary; the engine never promises wall-clock precision under server lag. Optional UI descriptions do not change execution.

### Files and identity

Discover packs under `<world>/archetype/packs/<folder>/`. A folder contains `pack.yaml`, definition files, and optional `assets/`. Recursively discover `.yaml` and `.yml` definitions outside `assets/`; folders and file names do not define IDs. Parse one YAML document per file using a constrained YAML 1.2 data model. Reject duplicate mapping keys, custom tags, aliases, merge keys, unknown fields, and non-finite numbers. References replace YAML-level include and merge mechanisms.

```yaml
format: 1
id: workshop
name: Workshop content
dependencies: []
```

`pack.yaml` owns the namespace and lists required pack IDs. Definitions have a global `pack:name` identity; local references such as `restore` mean the current pack's definition. IDs are unique across definition kinds within a pack. Cross-pack references require a declared dependency. Define namespaced mechanic types too; unqualified built-in operations such as `heal` mean `archetype:heal`.

The definition kinds are grouped below. Each gets its own generated schema; a new Kotlin mechanic contributes its own field description to that schema.

| Group | Definition kinds | Purpose |
| --- | --- | --- |
| Player content | `class`, `specialization`, `ability` | Ownership, grants, slots, and behavior |
| Shared mechanics | `resource`, `state`, `status`, `cooldown_group` | Values, memory, contributions, and timing |
| Reuse | `condition`, `selector`, `sequence` | Typed reusable parts |
| Progression | `progression_track`, `unlock_tree`, `empowerment` | Earnings, choices, and behavior changes |
| World content | `projectile`, `area`, `summon`, `block_pattern` | Delivery, persistent geometry, owned entities, and terrain |

An ability granted to a class has a stable local grant name distinct from the referenced reusable definition. Its stored cooldown and replacement identity follow that grant. For example, this class-body fragment binds the grant `primary_attack` to a reusable ability:

```yaml
abilities:
  primary_attack:
    ref: bolt
    slot: primary
    with:
      power: 3
```

An inline grant uses `definition` instead of `ref`, with the same body as an ability but without a global `kind` or `id`. Its containing class ID plus grant name supplies its logical identity. Moving a definition file or changing the referenced implementation cannot refill the grant's state. Two grants of the same reusable ability are distinct unless they explicitly share a cooldown group or resource.

## Values, conditions, and targets

Parameters declare their types and optional defaults. A reference supplies only declared arguments. Primitive parameter types include Boolean, bounded integer or number, enum, duration, and a reference to a specified definition kind. These are declared values, not textual substitution. Optional gameplay values require an explicit presence guard or fallback.

```yaml
kind: sequence
id: workshop:restore_amount
parameters:
  amount:
    type: number
    min: 0
    default: 6
effects:
  - type: heal
    target: target
    amount: {expr: "params.amount"}
```

`expr` permits arithmetic, parentheses, declared numerical reads, and a documented pure function set: `min`, `max`, `clamp`, `abs`, `floor`, `ceil`, and `round`. No assignment, loops, arbitrary calls, reflection, file access, or expression interpolation in other strings. Invalid constant calculations fail loading. Invalid runtime calculations stop the affected step with a diagnostic; they do not silently become zero. Random outcomes use explicit bounded chance conditions or effects so their evaluation is visible in traces.

Conditions are either a typed leaf, a reference, or an `all`, `any`, or `not` composition. A sequence of conditions is not a second Boolean expression language.

```yaml
all:
  - type: resource_at_least
    resource: focus
    amount: 10
  - not:
      type: has_status
      target: actor
      status: silenced
```

Targets accept a context binding such as `actor`, `owner`, `target`, or `event.target`, an inline selector, or `{ref: selector_name}`. A selector declares supported target kinds, finite range or count where needed, filters, and ordering. Conditions in `filters` all apply. `target` means the current selected entity or position, not an arbitrary last target. A selector that returns a set runs the target-dependent effect for each valid member; independent effects run once at their declared level.

```yaml
target:
  type: aimed_entity
  range: 24
  filters:
    - type: relation
      is: enemy
```

Ability-level selection binds at activation. Each delayed step rechecks validity. An inline selector on a later effect makes fresh selection explicit. No silent substitution occurs when a binding expires. Each effect declares whether it requires a target; a required target missing from the binding context is an authoring error.

Read-only contexts are `actor`, `owner`, `target`, `origin`, `event`, `params`, `state`, `snapshot`, `result`, and `timer`. Available fields depend on the execution stage. Entity data is read when a step runs. Event result numbers, named effect results, and snapshots retain their captured values. Entity references still require current validity even when their identity was captured earlier. An effect's optional `as` field binds its typed result for subsequent dependent steps. Named timers expose current remaining time through a typed read; expressions cannot mutate them.

## Resources, state, and timing

```yaml
kind: resource
id: workshop:focus
scope: class
min: 0
max: 100
initial: 100
regeneration:
  amount: 5
  every: 1s
```

Class scope means one balance for each player's class, not one balance for every reference. Player scope explicitly shares a balance across granting classes. Summon scope stores a balance on the acting summon. Regeneration runs once per balance, only under its eligibility rules. Typed operations expose gain, spend, set, and reset; spending fails if insufficient, while ordinary gains clamp to declared bounds.

```yaml
kind: state
id: workshop:stance
scope: class
persistent: false
fields:
  mode:
    type: enum
    values: [steady, mobile]
    initial: steady
  successful_hits:
    type: integer
    min: 0
    max: 3
    initial: 0
```

State writes use declared `set_state`, `add_state`, and `reset_state` effects. Numeric fields require bounds. Entity references are temporary and optional. Transient class state resets when its class deactivates; transient player state resets on death or session end; activation and status state end with their instance. Persistent fields survive those events unless a typed reset policy says otherwise. Valid edits preserve compatible fields, initialize new fields, and clamp changed bounds. Renaming or incompatibly changing a saved field requires a declared migration, just as renaming a definition does.

Ability timing can use the simple `cooldown: 8s` form or a structured object when groups are needed. Available uses are optional:

```yaml
cooldown:
  duration: 2s
  groups: [shared_casting]
charges:
  max: 2
  recharge: 12s
  mode: sequential
```

Starting consumes one available use and commits the ability/group cooldowns together. Sequential recharge restores one missing use per interval; parallel recharge creates one timer per spent use. Ability duration edits affect newly started timers. Reducing maximum uses clamps the count; increasing it does not instantly grant new uses. Explicit cooldown operations can change existing timers.

## Abilities, effects, and projectiles

An ability body can declare `name`, `description`, `icon`, `parameters`, `activation`, `target`, `requires`, `costs`, `cooldown`, `charges`, `snapshot`, `effects`, `phases`, and `reactions`. Unknown fields fail validation. `activation.type` supports `activated`, `passive`, `toggle`, `channel`, and `charge`; only fields relevant to that mode are allowed. The concrete mode timing must preserve Q19's cost and cooldown rules. Optional named phases label sequences and finite transitions; they cannot introduce an unbounded cycle around reference validation.

Effect lists execute in order. Finite `delay`, `repeat`, `branch`, `sequence`, `for_each`, `parallel`, `wait_for`, and `chain` operations handle composition. Repeats require a finite count or duration and a positive interval. References cannot form a compile-time cycle, and delayed work retains source ownership and causal ancestry. Maintained effects end with their activation; independently timed effects still follow source-class cleanup. [ability-coverage.md](ability-coverage.md) specifies branch results, joins, event correlation, bounded target history, timer generations, and ongoing controllers. These are required built-ins, not suggestions for authors to recreate using scripts.

```yaml
kind: ability
id: workshop:bolt
name: Bolt
parameters:
  power: {type: number, min: 0, default: 3}
costs:
  - resource: focus
    amount: 10
cooldown: 3s
snapshot:
  damage: {expr: "6 + params.power * 2"}
effects:
  - id: launch
    type: launch_projectile
    projectile:
      ref: bolt_projectile
      with:
        damage: {expr: "snapshot.damage"}
    direction: actor.aim
```

An `id` on an effect is optional unless another declaration, such as an empowerment, addresses it. Effect IDs remain local to their containing named definition. Diagnostics can use list positions for unnamed effects; persistent state never uses a list position as its durable identity.

```yaml
kind: projectile
id: workshop:bolt_projectile
parameters:
  damage: {type: number, min: 0}
speed: 24
gravity: 0
lifetime: 3s
collision:
  entities: enemies
  blocks: solid
entity_hit:
  - id: impact
    type: damage
    target: event.target
    amount: {expr: "params.damage"}
    damage_type: minecraft:magic
```

Projectile speed is in blocks per second; acceleration uses blocks per second squared. The default collision behavior consumes the projectile on its first eligible impact. Optional piercing, bouncing, homing, and repeat-hit settings are finite. `entity_hit`, `block_hit`, and `expiry` are distinct callbacks; source cleanup invokes none of them as gameplay events. Presentation uses a supplied renderer with declared resources and a built-in fallback.

Reactions use stable names, an event ID, a phase, optional priority and conditions, and a body. Before-phase bodies contain supported event adjustments; after-phase bodies can produce ordinary gameplay effects. For example, this ability-body fragment defines a damage reduction:

```yaml
reactions:
  reduce_incoming:
    event: damage
    phase: before
    when:
      type: same_entity
      left: event.target
      right: actor
    effects:
      - type: adjust_event
        amount: {multiply: 0.8}
```

The event catalog distinguishes proposed amount, applied health change, prevention, cancellation, and credited owner. A before adjustment cannot claim that damage has already occurred. Numerical adjustments compose in the accepted add, multiply, then bounds order; competing absolute replacements require explicit priority or exclusivity. Q82 routes damage through native combat and reports actual health change to after-reactions and progression.

## Status contributions

```yaml
kind: status
id: workshop:scorch
duration: 4s
reapply: refresh
periodic:
  every: 1s
  effects:
    - type: damage
      target: target
      amount: 2
      damage_type: minecraft:magic
```

The stable source key combines owner, source class or summon, logical ability grant, and application-effect identity. Repeated casts from that source refresh its contribution. Lifetime-bound controller contributions additionally identify their controller and membership, allowing two areas to remove their own buffs independently. Repeated application by the same controller still refreshes rather than creating new sources. Other sources retain independent expiry and periodic work. A status without explicit stacking never acquires stacks from repeated application. Periodic work starts after the first interval and keeps its cadence across refreshes.

An optional `stacks` object declares a maximum and either shared duration or per-stack duration. Shared duration is the default: a successful reapplication refreshes the source contribution's stack lifetime. Per-stack mode expires individual stacks. There is one periodic schedule per source contribution; effects can explicitly read the stack count. Lifecycle callbacks distinguish first application, refresh, a changed stack count, expiry, and cancellation so refreshing does not implicitly replay first-application effects. Boolean restrictions use source accounting; numeric bonuses use strongest contribution or explicit capped addition.

## Progression and empowerments

A progression track declares thresholds or a cumulative-XP formula, optional cap behavior, stable earning-rule IDs, point awards, and sharing scope. An unlock tree declares stable node IDs, grants, prerequisites, ranks, optional point costs, choice groups, and optional positions. Automatic grants and player-selected talents remain distinct.

```yaml
kind: progression_track
id: workshop:practice
scope: class
levels:
  - level: 1
    xp: 0
  - level: 2
    xp: 100
    awards:
      - id: first_talent_point
        type: talent_points
        budget: talents
        amount: 1
earn:
  credited_defeat:
    event: entity_death
    phase: after
    when: {type: credited_to_owner}
    amount: 10
    recipients: {type: nearby_allies, range: 24}
    distribution: each
```

`distribution: each` is the accepted default and grants the full amount to every eligible recipient. `split` divides a fixed total. A named event/earning-rule pair awards once to each eligible recipient; separate earning rules may intentionally award distinct tracks. Recipients are deduplicated by player identity. Class-local earning rules default to their class track; pack-level rules must name the destination. Contributions and nearby eligibility must be evaluated on the authoritative event result, not a client claim.

```yaml
kind: empowerment
id: workshop:wider_cast
changes:
  - type: replace
    target: {ability: primary_attack}
    replacement: {ref: burst}
```

This fragment assumes the user supplies a `burst` ability and attaches the empowerment to a class with the `primary_attack` grant. It changes the implementation while preserving the logical grant, slot, cooldown, and compatible state. The example intentionally does not define a class. Named add, modify, remove, and replace targets address declared abilities, effects, resources, or behavior rules. A modify operation accepts only fields exposed by the selected target's schema; it cannot walk arbitrary YAML paths.

```yaml
kind: unlock_tree
id: workshop:choices
track: practice
nodes:
  wider_cast:
    selection: talent
    requires: {type: level_at_least, track: practice, level: 2}
    cost: {budget: talents, amount: 1}
    grants:
      - type: empowerment
        ref: wider_cast
```

This is one isolated node, not a prescribed tree. Authors can add choice groups, prerequisite edges, multiple ranks, automatic nodes, or specialization-specific trees. Rank and prerequisite graphs must be finite and acyclic. Edited curves recalculate levels; invalid selections refund their historical point spend and remove dependent selections in one reconciliation. Restoring a definition does not repurchase refunded choices or replay one-time awards.

## Summons and terrain

A summon declaration chooses an existing supported entity type, abilities, navigation settings, named behavior modes and rules, orders, optional equipment, and persistence. Movement and action channels select rules independently. An ability is executable by a summon only if its target, resource, and event requirements are available in that summon context.

```yaml
kind: summon
id: workshop:companion
entity: minecraft:wolf
persistence: companion
orders: [follow, stay, focus_target, dismiss]
navigation:
  follow_distance: 3
  acquisition_range: 16
  pursuit_limit: 24
behaviors:
  default:
    movement:
      - id: follow_owner
        type: follow
        target: owner
    action: []
```

This shows the declaration shape, not a required companion design. The installed adapter must support controlling the selected entity's behavior without two independent AI controllers fighting over the same channel. Explicitly persistent companions save identity, health, declared state, real inventory, and timer state. Q83 distinguishes automatic suspension from dismissal until recall. Recall reuses the same living companion without refilling its health or state; dead companions need revival. Entity type changes are compatibility-sensitive and cannot silently reinterpret saved entity data.

```yaml
kind: block_pattern
id: workshop:short_wall
origin: [1, 0, 0]
palette:
  "#": {block: "minecraft:stone_bricks"}
  ".": {skip: true}
  "_": {block: "minecraft:air"}
layers:
  - ["###"]
  - ["###"]
```

Layers run bottom to top along Y. Each row runs along X; subsequent rows run along Z. Coordinates are zero-based cell indices relative to the declared origin. Rotation is around Y, and mirroring names its axis. Every symbol requires a palette entry; all layers have matching dimensions. Skip leaves a position untouched; explicit air changes it.

```yaml
type: place_pattern
pattern: short_wall
at: target
rotation: 0
duration: 6s
```

This effect fragment requires a position target supplied by its containing ability. Duration makes the placement temporary; the structured form can explicitly declare permanent changes. The same terrain operation model supports set, replace, and break over point, line, box, and sphere selections with finite bounds and block/tag filters. Placement validates world bounds, loaded regions, placement rules, permissions, and work budgets before starting. Gameplay changes during a batch are rechecked; temporary partial work restores its owned cells, while completed permanent work remains.

Temporary ownership follows active layers at each position. External changes invalidate older ownership. Original block data and inventory are preserved when explicitly supported. If that data can no longer be restored without overwriting a later change, Q84 requires durable operator recovery. Fluid and falling-block opt-ins do not promise rollback of their indirect physics.

## Media, migrations, and inspection

Assets stay under the pack's namespace. Manifest IDs select media consumed by installed renderers and sound playback; they do not introduce new client classes. Archetype supplies fallback presentation. Media transfer is bounded, content-addressed, and subject to client settings, with resource refresh independent from gameplay publication. Local source paths never become permission to read arbitrary server files or fetch arbitrary URLs.

Explicit identity migration syntax belongs in `pack.yaml`:

```yaml
migrations:
  - id: renamed_focus
    kind: resource
    from: workshop:old_focus
    to: workshop:focus
```

Apply each migration once per saved record. Validate kind compatibility, collision rules, and reference graphs before publication. Keep migrations available for offline player records. Missing definitions leave state dormant; migrations do not merge conflicting balances or duplicate awards. More complex conversions use declared typed conversions supported by the engine, not a script hook.

The inspector shows authored values, parameter bindings, active empowerment contributions, and the effective compiled ability. It also explains eligibility failures and retained dormant state. Offline validation distinguishes checked facts from world-dependent facts that require registry metadata. There is no separate permissive parser for development.

## Built-in mechanic coverage

The examples above are a subset of the required v1 catalog. Concrete type names and field schemas come from the common registration metadata. Implement the following capability groups; an example's absence does not defer an accepted capability.

| Group | Required composition capabilities |
| --- | --- |
| Gameplay | Native damage/healing, typed outcome capture, barriers, redirection/reflection, owned modifiers and control restrictions, dispels, movement, and shaped area selection |
| Flow and reactions | Typed conditions/selectors, sequences, bounded loops/branches, parallel timelines, event waits/timeouts, chains, owned timers, event adjustments, and after-reactions |
| State and progression | Scoped state, per-target marks, status contributions, temporary forms and replacements, resources, cooldown/charge procs, XP/points, unlocks, and empowerments |
| Owned world effects | Projectiles, persistent/attached areas, summon behavior and orders, temporary/permanent terrain, block patterns, and required cleanup |
| Presentation | Built-in targeting modes and shape feedback, particles, sounds, supplied rendering, HUD/talents, client asset refresh, and author inspection |

Reusable conditions cover exposed health/resource/state values, status presence, ownership and progression, relationship, entity/block tags, range, line of sight, facing, typed results, timers, and available event outcomes. Spatial operators cover positions, directions, coordinate frames, offsets, and the combat shapes defined in [ability-coverage.md](ability-coverage.md). World-specific reads or effects are supported only when a registered mechanic describes them. New engine-level interactions, navigation algorithms, or rendering logic require Kotlin extensions. Complexity within the supported catalog remains a manifest-authoring task, and every required coverage scenario must pass without a bespoke handler.

## Review and validation limits

The YAML blocks illustrate the accepted grammar; they are not a complete executable pack. The class-body, condition, timing, reaction, placement, and migration snippets are intentionally fragments. Referenced content supplied by an eventual author is identified in the surrounding text. Syntax checks cannot validate the proposed game semantics before a compiler exists.

The next implementation artifact should be generated schema metadata plus compiler validation, not a second hand-maintained interpretation of this document. [architecture.md](architecture.md) defines the modules that own those responsibilities. [design-review.md](design-review.md) records the final accepted policy round and consolidated confirmation step.
