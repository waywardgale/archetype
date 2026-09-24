# Complex abilities without routine code changes

Status: required v1 design coverage, following the user's acceptance of Q81-Q85 and explicit request to author most complex abilities out of the box. These are implementation and acceptance requirements, not claims that the framework already works. Exact additional operation fields below are the documented design contract to carry into schema generation.

## The authoring promise

An ability becoming more involved must not, by itself, require Kotlin. Authors should be able to combine targeting, timing, state, combat rules, areas, projectiles, movement, summons, progression, and temporary changes entirely in manifests. Design ordinary class content, tune it, replace mechanics through talents or forms, and iterate through automatic reload without rebuilding the mod.

Provide both small composable operations and readable built-ins for common difficult patterns. A shield, chain, or persistent area should not require an author to recreate an engine subsystem with dozens of state variables. When a required scenario below needs a one-off Kotlin handler, the framework is missing a reusable capability; completing that capability is part of v1.

Code is still needed for a genuinely new engine-level capability, such as a new renderer, custom navigation algorithm, or integration with another mod's otherwise unexposed system. Arbitrary executable code in YAML, unbounded computation, and unlimited world work remain excluded. Most complex class mechanics should sit inside the supported composition model. We cannot claim a percentage of all imaginable abilities from a finite test set.

## Required composition contracts

### Flow, event correlation, and results

Provide ordered sequences, bounded `for_each`, conditional branches, weighted/chance choices, positive-interval repetition, named phases, event waits with timeouts, and `parallel` branches. A parallel declaration explicitly chooses waiting for all branches or the first successful branch. It is deterministic scheduling on the server, not simultaneous mutation from multiple threads. Ties follow declaration order. A race cancels losing branches through their source scopes; it cannot undo their completed effects.

Each branch has immutable local bindings. Mutable shared mechanic state requires an explicitly scoped state declaration. Limits apply to the whole originating work batch as well as its branches, so splitting work does not multiply its budget. Ordinary target loss skips only the dependent work. A hard execution failure cancels the containing work scope and its continuations, with declared branch failure handling for recoverable results.

An effect may bind its typed result with `as`. Results include actual health lost or restored, amount absorbed, outcome reason, selected handles, and created effect handles where supported. These results are read-only through `result.<name>`. Each operation documents its result type and the outcomes in which a value is present. Optional handles need a presence guard. A result from a repeated invocation is local to that invocation; authors cannot accidentally read a different target's last result.

`wait_for` can match an event belonging to a particular projectile, area, status, summon, or cast handle. It has a finite timeout or an enclosing maintained lifetime, handles exactly one selected match by default, and unregisters on completion or cancellation. A handle keeps a bounded outcome receipt so waiting immediately after creation cannot miss an event that occurred earlier in the same server step. Register matching future events before committing the action when a receipt would not be sufficient. Actors cannot forge these correlations through client payloads.

```yaml
effects:
  - type: damage
    target: target
    amount: 8
    damage_type: minecraft:magic
    as: strike
  - type: heal
    target: actor
    amount: {expr: "result.strike.health_lost * 0.25"}
```

The fragment needs a selected entity target. A blocked hit produces zero health loss, so it provides no lifesteal. Damage already completed remains completed if the healing step later fails. The effect and its immediate reactions finish before the following step under Q71.

### Targeting, geometry, and bounded history

Use the same typed shape definitions for gameplay selection and client target previews. Required shapes include point, segment/beam, cone, sphere, cylinder, ring, and box. Define positions, directions, offsets, rotation, and local/world coordinate frames as typed spatial values; numerical expressions calculate their scalar fields without becoming a general scripting language.

Selectors support relationship, range, line of sight, facing, entity/block tags, health, resources, statuses, event participants, and declared state. Explicit ordering includes nearest, lowest health, highest/lowest exposed stat, and bounded random selection. Use a stable entity ID to break equal deterministic ranks. Range and result limits are mandatory or supplied by documented bounded defaults.

Provide a `chain` operation with maximum targets or hops, hop range, delay, target filters, and an explicit revisiting policy. Its origin advances from the previous selected target or impact. The engine owns a finite visited set, can expose a bounded hit count, and ends when no valid next target exists. Bounces, beam ticks, and area membership also have typed bounded tracking. This does not introduce arbitrary mutable collections into custom state and does not evade reaction reentry rules.

Standard input modes cover immediate aim, ground-position selection, an entity lock, hold/release, and a second activation to confirm or recast an owned effect. Pack authors choose built-in range, obstruction, shape, and timing indicators. The server validates the final intent against the same geometry. The HUD remains a fixed framework interface, not a general-purpose UI language. A target-shape preview does not imply a full block-pattern construction preview.

### Areas and ongoing controllers

Add reusable `area` definitions and a `create_area` effect. An area has a shape, fixed or attached anchor, relationship filters, finite duration or maintained lifetime, sampling interval, and bounded membership. It handles initial occupants once, enter, periodic presence, natural exit, expiry, and cancellation distinctly. Moving an attached area reconciles membership rather than treating every sample as a new entry.

Area-owned buffs are contribution-scoped to membership and removed when that membership ends. Removing one area's buff must not remove a second area's contribution. Cancellation removes membership effects without running ordinary expiry or exit gameplay callbacks accidentally. An invalid anchor cancels the controller; it never force-loads chunks. A fixed area's anchor chunk unloading also ends its temporary presence.

```yaml
kind: area
id: workshop:restoring_field
shape: {type: sphere, radius: 4}
duration: 8s
sample_every: 100ms
targets:
  type: living_entities
  filters:
    - type: relation
      is: ally
periodic:
  every: 1s
  effects:
    - type: heal
      target: target
      amount: 2
```

```yaml
type: create_area
area: {ref: restoring_field}
anchor: {position: target}
```

The placement fragment requires a selected position. Its periodic body runs once for each current eligible member per interval. Enter/exit operations and membership effects are optional; a simple field needs neither. Attached auras, timed traps, overlapping ground effects, and moving hazards use this same controller contract.

Long-lived toggles, passives, and summons must not eventually fail just because their historical tick count exceeds one cast's work budget. Setup and each registered controller pulse are bounded execution batches, with bounded controller counts and per-owner/per-tick limits. Delayed descendants stay inside their originating pulse's budget and causal ancestry. Only registered positive-interval controllers can start new pulse batches; arbitrary recursion or rescheduling cannot reset a spent budget. Maintained controllers still end with their owner scope.

### Combat policies, barriers, and control

Ship reusable operations for absorption shields, damage/healing modifiers, damage redirection and sharing, reflected damage, and result-based lifesteal or resource gains. Ordinary combat still uses native mitigation and server PvP rules. A before hook can use a registered atomic combat policy such as absorption or redirection; it cannot run an arbitrary unrelated effect sequence while the native action is pending.

A barrier has a finite owned capacity, eligible damage filters, consumption priority, lifetime, and optional depletion callback. Apply framework absorption to damage still pending for health after native defenses; report native prevention, framework absorption, and final health loss separately. A completely prevented attack consumes no framework capacity. Multiple barriers consume capacity deterministically and never absorb more than remains. Depletion callbacks run after the pending action commits. Barriers and their callbacks retain source cleanup and causal protection.

```yaml
type: shield
target: actor
capacity: 20
duration: 6s
depleted:
  - type: gain_resource
    resource: focus
    amount: 10
```

The fragment assumes a granted `focus` resource. Natural depletion can reward resource; expiry, dispel, cancellation, or manifest replacement must not masquerade as depletion. Shield amounts and duration changes can be parameterized or modified through the same composition rules as other effects.

Combat conditions expose requested, prevented, absorbed, and applied amounts plus source tags and credited owner. Redirection checks the destination before commitment, supplies an explicit invalid-destination policy, and reports each native outcome. Custom damage categories can label behavior and conditions without requiring a new registered native damage type for every ability.

Provide status tags, dispel by tag/source/count, cleanses, immunity to declared control categories, and typed restrictions for actions such as moving, jumping, attacking, or activating abilities. Roots, silences, slows, and stuns compose from restrictions and owned modifiers. Restrictions remain while any applicable source supplies them. A break-on-damage rule reacts to the declared damage outcome, not an ambiguous hit notification. Snapshot versus live scaling remains explicit for periodic damage and healing.

### Stateful combos, forms, and cooldown procs

Provide owned named timers with set/refresh, cancel, remaining-time reads, and expiry actions. Refresh changes the timer generation; an older queued expiry cannot reset a newer combo window. Status-local state expresses per-target marks and counters without adding classes to mobs. Consuming a stack, resource, mark, or state transition checks and commits the consumption once.

Temporary status modifiers and maintained forms can use the same typed add/modify/remove/replace declarations as progression empowerments. They can replace a complete ability, change a projectile or area, or alter summon rules. Their source lifetime owns the contribution. Expiry removes only that source and recomputes the remaining effective definition. Ambiguous competing replacements require declared priority or exclusivity. Existing grant identity, slot, resource state, cooldown, and recharge state are preserved.

```yaml
kind: status
id: workshop:alternate_form
duration: 10s
modifiers:
  - type: replace
    target: {ability: primary_attack}
    replacement: {ref: alternate_attack}
```

This fragment needs the author's `primary_attack` grant and `alternate_attack` definition. It supplies no class. Modifier changes cancel affected running activations consistently with the existing replacement rule. A separately timed form contribution remains owned by its status; cancelling an activation must not erase the contribution merely because that activation's grant is the replacement target. Status expiry or source-class cleanup does erase it.

Cooldown operations explicitly address a logical grant or group and a timer kind. `cooldown`, `recharge`, and `available_charges` are different fields. A recharge edit specifies earliest, latest, or all active recharge timers; decreasing a cooldown clamps at zero and never grants charges implicitly. Restoring a charge is a separate bounded operation. Procs can use chance, actual outcomes, internal cooldowns, and once-per-cast or once-per-target gates.

Movement operations include collision-aware dash/charge, push/pull, safe teleport, tethering, and fixed line/arc/orbit motion for supported entities and effects. Motion is bounded by speed, range, lifetime, and ownership. Failure to reach a destination has a typed result. New physics or navigation algorithms still require an extension, but ordinary displacement and ability delivery do not require a class-specific Kotlin implementation.

## Composition acceptance scenarios

These are framework conformance scenarios, not bundled classes or a required content roster. Each must be expressible by YAML plus optional assets using the public catalog. Test setup can grant isolated abilities to test actors. It must not add a bespoke handler named after the scenario.

| Scenario | Required manifest composition | Evidence required |
| --- | --- | --- |
| Chain strike | Initial selection, advancing hop origin, finite visited targets, falloff, delayed hits | No revisits unless enabled, correct stop on exhaustion, and preserved attribution |
| Channelled beam | Hold input, recurring geometry query, resource drain, damage pulses, collision/line of sight | Interrupt and insufficient resource end its owned work; no through-wall hits |
| Ground field or trap | Ground targeting, persistent area, enter/periodic/exit behavior, membership buffs | Initial occupants handled once; departing targets lose only that area's effects |
| Moving aura | Actor attachment, bounded membership, buffs and periodic healing | Membership follows the anchor and cleans up on death, unload, or class switch |
| Absorb and burst | Barrier capacity, depletion result, filtered after-effect | Native prevention consumes no capacity; only natural depletion runs the burst |
| Lifesteal and overheal conversion | Named actual outcomes, arithmetic, healing, optional shield generation | Zero applied damage produces no leech; overheal is distinguished from health restored |
| Three-step combo | Confirmed hit, bounded counter, refreshed timer, branching finisher | Misses do not advance; an old expiry cannot erase a newly refreshed combo |
| Mark and detonate | Per-target status state, explicit selection/consumption, delayed damage | Sources remain separate and marks are consumed only once |
| Temporary transformation | Status-owned complete ability replacement and restoration | Slot, cooldown, charges, and saved resources survive entering/leaving the form |
| Talent rewrite | Unlock choice, parameter changes or full replacement of an ability/area/summon behavior | The effective result is inspectable; respec removes only those contributions |
| Ricochet or returning projectile | Finite impacts, steering, collision filters, return/catch condition | Repeat-hit rules hold; cancellation never emits an accidental impact reward |
| Defensive counter window | Wait for correlated incoming event, timeout, cancellation or absorption, after-reaction | Exactly one winner, no stale listeners, and no recursive counter loop |
| Dash with follow-up | Collision-aware movement, typed outcome, delayed or impact-triggered attack | Invalid paths do not teleport through obstacles; later targeting is revalidated |
| Companion command combo | HUD order, owner-target selection, summon-local ability/resource, recall continuity | Player/summon context stays distinct and reconnect cannot duplicate the companion |
| Cooldown or charge proc | Actual hit filters, chance, internal timer, explicit grant/group/recharge operation | No accidental charge refill or proc recursion; stable behavior across replacements |
| Multi-phase ultimate | Named phases, parallel timing, periodic areas, projectiles, final timeout/expiry | All branches share work limits and source cleanup; bounded traces explain ordering |

For each scenario, change damage, targeting, timing, cost, and one structural behavior through manifests and confirm automatic reload without Kotlin edits or a rebuild. Combine scenarios too: a talent-modified chain cast by a summon, or a form that changes a channelled area. This checks whether operations actually compose instead of working only in isolated demonstrations.

Repeat relevant scenarios with two players and overlapping sources. Cover logout, death, class switching, missing targets, chunk unloading, valid/invalid reloads, and runtime limits. Save and restart scenarios that own persistent state. The full matrix is a release gate, not an optional examples milestone after v1.

## Readability and extension discipline

Document every catalog operation with its fields, defaults, context, typed results, lifecycle, and one short example generated or checked against the same descriptions as validation. Ship reusable reference patterns for authors and agents, with no enabled class content. Keep the simple ability shape in [manifest-spec.md](manifest-spec.md) unchanged.

Prefer a focused built-in when a common mechanic repeatedly needs fragile bookkeeping. Prefer composition when the existing operations already express it clearly. Code additions should supply reusable capabilities and update the coverage matrix; they should not encode an individual class's rotation, talent tree, or named ability. This is the maintenance rule that keeps later content work primarily in YAML.

## Proof still needed

The documents now state what v1 must cover. They do not demonstrate that the planned operations are implemented, balanced, performant, or mutually compatible. Compiler checks, deterministic runtime scenarios, and real Fabric integration tests must supply that evidence during implementation. No quantified claim about all possible class designs is established by this document.
