# Statuses and membership buffs in the current build

Statuses support timed source contributions, capped stacks, periodic work, lifecycle callbacks, an additive native movement speed bonus, bounded damage reflection, status-owned complete ability replacements, tags, presence conditions, bounded dispels, activation/movement/jump/attack restrictions, control-category immunity, health-loss break rules, per-contribution state inside callbacks, and filtered external reads and writes. This is part of the accepted v1 contract. Other attributes, additional modifier types, and client status feedback remain required.

A status can attach a `scope: status` state definition with `state: {ref: memory}`. Its applied, refresh, periodic, expiry, and break callbacks share one transient field map per source contribution. Refresh keeps those fields; source removal frees them. See [state authoring](state-authoring.md) for field grammar and the `memory_mark` fixture for a per-source pulse counter.

## Timed application

```yaml
kind: status
id: workshop:scorch
duration: 4s
reapply: refresh
stacks: {max: 3, duration: shared}
periodic:
  every: 1s
  effects:
    - type: damage
      target: target
      amount: {expr: '2 * status.stacks'}
      damage_type: minecraft:magic
```

Apply the status from an ability with this effect:

```yaml
effects:
  - type: apply_status
    id: scorch_application
    status: {ref: scorch}
    target: target
```

`target` accepts `actor` or `target`. References accept a local or namespaced string or `{ref: ...}`. Application revalidates the entity using the current execution context. The status then follows the entity without retaining the initial selection's range or geometry. The target must remain alive, loaded, and in its owner's dimension. Actual damage still passes through the native adapter and its relationship policy.

The source key contains the owner, source class, logical grant, enclosing ability/area/status definition, application-effect identity, and recipient. An explicit `id` supplies the effect identity; otherwise its structural field path does. Repeated casts from that key refresh the contribution. Give distinct application sites distinct IDs. Duplicate status application IDs within a definition fail validation. Separate grants and owners retain their own expiry times and periodic schedules.

Timed statuses can outlive a completed cast. Source death, logout, dimension change, class deactivation, affected definition reload, or shutdown removes their contributions. Recipient death, logout, dimension loss, or unload also removes the recipient's contributions. These are transient world effects, with no saved status state. Reload dependencies include referenced areas and statuses even when their creation is still queued. Unrelated definition edits retain existing contributions.

## Cadence, stacks, and callbacks

`duration` must be positive and at most one hour. `reapply` defaults to `refresh`; that is the currently supported policy. Without `stacks`, repeated applications refresh one stack. `stacks.max` must be 1..64.

`stacks.duration: shared` is the default. Each reapplication adds one stack until the cap and refreshes every stack's expiry. `per_stack` gives each stack an independent expiry. At the cap, reapplication refreshes the oldest stack's expiry. Both modes use one periodic schedule per contribution. Refreshing neither grants an immediate pulse nor postpones the next pulse. A periodic body starts after its first positive `every` interval.

Optional `applied`, `refreshed`, `stacks_changed`, and `expired` effect lists each have private result bindings. `applied` runs once on first creation. On reapplication, `refreshed` runs first, followed by `stacks_changed` if the count increased. Partial expiry runs `stacks_changed` before a periodic pulse due that tick. Final expiry removes the bonus and cancels the status's delayed descendants and nested controllers before running `expired` once. Source cancellation and membership loss run no ordinary expiry gameplay actions. A cancellation callback is not supported yet.

These bodies expose `status.stacks`. First application reads one, refresh and partial expiry read the current count, and ordinary final expiry reads zero. Nested delays capture that invocation's bindings, including its stack count. The read cannot escape into an ordinary ability or area callback. Periodic invocations get a fresh bounded execution budget; their descendants share the pulse budget and contribution lifetime.

## Numeric bonus composition

```yaml
kind: status
id: workshop:field_haste
duration: 2s
modifiers:
  - type: attribute
    attribute: minecraft:movement_speed
    amount: 0.02
```

The implemented attribute modifier accepts a constant additive amount in 0..1. `stacking: strongest` is the default and chooses the largest active contribution. To add contributions, specify `stacking: capped_add` and an explicit `cap` in 0..1. An individual amount cannot exceed that cap. All movement speed modifiers in the captured definition set must agree on the combination policy and cap; conflicting policies fail validation.

Each source supplies its bonus once, regardless of stack count. Periodic effects can scale explicitly using `status.stacks`. The native adapter updates only `archetype:status_movement_speed`, a transient additive attribute modifier. It preserves base values and unrelated modifiers. Removing a stronger source restores the remaining weaker bonus, and removing the final source removes Archetype's modifier.

Negative speed changes, live numeric expressions, and other attribute types remain gaps in the accepted v1 implementation.

## Damage reflection

```yaml
kind: status
id: workshop:mirror
duration: 5s
modifiers:
  - {type: reflect, fraction: 0.25, cap: 8, damage_type: minecraft:magic}
```

Each active source contribution reflects the declared fraction of actual health loss from a native hit back to its server-identified attacker, capped per contribution and hit. At most eight contributions run per hit. A fully prevented hit reflects nothing. Reflected damage uses the status owner's native damage attribution and obeys the adapter's ordinary target and PvP checks. A reflected hit cannot recursively reflect. The damage type must exist in the server registry. Reflection ends with the source status and does not scale automatically with stack count. A Fabric game test verifies one capped reaction to a real native hit.

## Area membership buffs

Add this field to an area:

```yaml
buffs: [{ref: field_haste}]
```

The list permits up to 16 distinct status references. Entry creates a contribution owned by that specific controller and membership. It lasts while the membership exists, overriding the status's timed duration. It starts with one stack and has its own periodic schedule. Area sampling does not reapply the status or replay `applied`. Leaving and reentering creates a fresh membership and schedule.

Overlapping areas remain separate even when they come from repeated casts of the same grant. Departure, area expiry, source cancellation, or invalid anchors remove only the affected membership's buff and status-owned work. Membership reconciliation happens before periodic status pulses and delayed work due that tick. A timed `apply_status` authored in `enter` remains an ordinary source-class-owned timed status; use `buffs` for membership-owned presence.

## Tags, presence, dispels, and stack consumption

A status may declare `tags: [harmful, fire]`. Tags are labels, with no built-in polarity or immunity behavior. Local labels gain the pack namespace, so these become `workshop:harmful` and `workshop:fire` in the workshop pack. Namespaced labels use the same declared dependency checks as references. A supplied list must contain 1..16 distinct labels. Labels need no separate definitions.

`restrictions` accepts any distinct subset of `activate`, `move`, `jump`, and `attack`. `activate` blocks new ability activations before costs, charges, or cooldowns commit. `move` rejects player position, vehicle, and directional input packets as well as authored dash/push/pull/teleport movement. `jump` rejects a grounded player's attempted upward jump packet and clears vehicle jump input. `attack` cancels ordinary entity and block attacks through Fabric callbacks. The server checks active contributions; overlapping restrictions remain until every contributing source ends. An opt-in Fabric game test verifies that a rooted player's movement packet leaves its server position unchanged. Vehicle, jump, attack, and connected-client behavior still require running-world tests. The [stun fixture](../src/test/resources/packs/spatial/workshop/stun.yaml) combines all four actions.

`break_on_damage: {minimum_health_loss: 1}` removes a source contribution after a native hit actually lowers its recipient's health by at least the declared amount. The default threshold is zero, but a hit must still cause positive health loss. Armor, vanilla absorption, framework barriers, invulnerability, and cancelled hits do not count as health loss. Each matching contribution breaks once; an optional `broken` effect list runs after removal with the original `status.stacks` binding. Ordinary expiry and source cancellation do not run `broken`. The native outcome bridge compiles and deterministic tests cover this ordering, but running-world behavior still needs verification.

Control categories and immunity are explicit labels. They use the same local/namespaced spelling, dependency rules, and 1..16 distinct-label limit as tags, but tags alone confer no control behavior:

```yaml
kind: status
id: workshop:silence
duration: 3s
control_categories: [silence]
restrictions: [activate]
```

```yaml
kind: status
id: workshop:clarity
duration: 5s
immunities: [silence]
```

While clarity is active on a recipient, applying or refreshing a status with a matching `control_categories` label has no effect. Applying clarity also cancels each existing matching control contribution, including its owned continuations, without running its ordinary `expired` callback. Unrelated source contributions remain. When the last immunity contribution ends, new matching control applications can succeed again; cleansed contributions do not reappear. A status cannot declare immunity to one of its own control categories.

```yaml
effects:
  - type: branch
    when: {type: has_status, target: target, tags: [harmful]}
    then:
      - type: dispel
        target: target
        tags: [harmful]
        count: 4
        as: cleansed
      - type: heal
        target: target
        amount: {expr: 'result.cleansed.stacks_removed'}
```

`has_status`, `read_status`, `dispel`, and `consume_status` require `target: actor` or `target: target`. They accept the same optional filters:

| Field | Matching rule |
| --- | --- |
| `status` | Exact status definition, supplied as a local or namespaced reference |
| `tags` | The contribution's status must contain every listed tag |
| `source: any` | Any owner and grant, the default |
| `source: actor` | Contributions owned by the acting player, across that player's grants |
| `source: grant` | Contributions from the same owner, source class, and logical grant as the executing effect |

All supplied filters must match. Omitting them matches every status contribution on that recipient. Source matching uses logical identities across repeated casts; it does not accept client-supplied owner IDs. Arbitrary source handles remain unimplemented.

`has_status` returns true while at least one matching contribution exists. It reads current contributions at execution time. A valid entity with no matching status selects `else`. Losing the entity or failing a retained selection guard skips the whole branch, including `else`. These operations revalidate targets using the ordinary target-loss rules.

`read_status` reports `contributions` and `stacks` across current matches. It returns zeroes for a valid recipient with no matches. Reads before and after `consume_status` in one sequence see the updated stack count, which lets a branch choose a finisher from actual state. Target loss supplies no result and skips dependent expressions.

`dispel.count` defaults to 1 and accepts 1..64. It removes whole source contributions, including all their stacks, in first-application order. Refreshing preserves that order. It reports `contributions_removed` and `stacks_removed` for the selected contributions. A valid target with no matches supplies zeroes; target loss supplies no result and skips dependent work. Repeated dispels cannot count an already removed contribution again.

Removal cancels that contribution's periodic and delayed work, chains, and descendant areas, including their membership buffs. It recomputes the remaining speed bonus before the next sequence step. It runs no `expired`, `stacks_changed`, area-exit, or area-expiry actions. The original cast's independent continuations and separately applied timed statuses retain their source lifetimes. The result counts directly selected contributions, excluding any additional descendant buffs removed by cleanup.

`consume_status` takes a required `count` of 1..64 stacks. It consumes the oldest matching contribution first and, within a per-stack contribution, the earliest-expiring stack first. It reports `stacks_removed` and `contributions_removed`. A partial contribution stays active and runs one `stacks_changed` callback with the remaining `status.stacks`; an emptied contribution is cancelled without `expired` or `stacks_changed`. The operation checks the known callback work before consuming any stacks. A valid target with no matches returns zeroes. Its result can drive damage or healing through a later expression:

```yaml
- type: consume_status
  target: target
  status: scorch
  source: actor
  count: 3
  as: consumed
- type: damage
  target: target
  amount: {expr: 'result.consumed.stacks_removed * 4'}
  damage_type: minecraft:magic
```

Dispelling an area membership buff leaves the area and its membership active. Sampling does not recreate that buff during the same membership. Leaving and reentering applies a fresh contribution. A status can inspect or dispel itself; self-removal stops the rest of that status callback while the enclosing ability can continue.

Named status reads participate in reference validation and affected-reload cleanup. They do not create controllers or reserve their callbacks' work. A status reading or dispelling itself is valid; recursive status or area creation still fails validation. Registered effects use `statusReferences` for all named dependencies and `statusCreations` for creation edges. The latter defaults to the former for creation mechanics; query mechanics explicitly declare no creation edges.

`write_status_state` updates a declared field on each matching live contribution attached to that status state. Its `operation` is `set` with a typed `value`, `add` with a numeric `amount`, or `reset` to the field's initial value. The ordinary status filter selects the exact owners and sources; `source: actor` keeps two players' marks separate. Numeric writes clamp to declared bounds. The result reports `contributions` changed and, for numeric or Boolean fields, the sums `previous` and `current`. A missing target supplies no result. A valid recipient with no matches reports zero contributions. Only a status-scoped state attached to at least one status can be targeted.

```yaml
- type: write_status_state
  state: mark_memory
  field: hits
  target: target
  status: mark
  source: actor
  operation: add
  amount: 1
  as: updated
```

## Bounds and verification

Definitions permit up to 256 statuses. Runtime limits are 4096 contributions globally, 256 per owner, and 64 per recipient, with at most 64 stacks per contribution. Static setup reservations include status callbacks, membership buffs, and referenced controllers. Shared reference graphs use memoized estimates. Known excessive work and direct recipient allocation fail before resource/cooldown payment. Dynamic selections and later callbacks can still interrupt a paid source when a limit is reached. Direct refreshes reuse their slots at capacity. Operator configuration for these limits remains required.

Status checks, dispels, and stack consumption reserve 64 candidate inspections per invocation during preflight, in addition to the effect step and any branch work. At runtime they inspect only the recipient's indexed contributions and charge for that candidate count before mutation. A known oversized batch fails before costs or cooldowns. Freed contribution slots are immediately reusable.

Area/status creation cycles, unknown references, invalid callback contexts, and unsupported fields fail the whole definition set. Registered effects declare `statusReferences` through the shared catalog; compiler reference validation and runtime dependency reconciliation use those declarations. Exported schemas cover the supported status structure, while semantic checks remain in `validatePacks` and native registry checks run on the server.

The optional fixture pack includes the timed scorch application, the field's membership speed buff, a cleanse that heals according to removed stacks, a detonation that consumes stacks for damage, a reflection window, and a three-step combo. The combo reads live stacks, advances only after actual health loss, refreshes its timer, and consumes stacks for a finisher. Deterministic tests cover cadence, both stack-duration modes, callback ordering, overlapping memberships, recipient limits, bounded reference expansion, and lifecycle/reload cleanup. Removal tests cover source and tag intersections, ordering, actual result counts, partial callbacks, self-removal, descendant cleanup, membership reentry, target loss, and prepayment bounds. Native movement speed compatibility is established by compilation against cached Minecraft 26.2 binaries. Selected damage, movement, and packet paths pass focused Fabric game tests; connected-client rendering remains unverified.
