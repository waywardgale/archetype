# Timers and charges in the current build

The compiler, runtime, and exported catalog support grant-owned named timers and ability charges. The optional fixture pack contains `combo_window` and `charged_burst`; it does not install gameplay content.

## Named timers

`set_timer` requires a local `name`, an entity `target` (`actor` or `target`), and a positive `duration` of at most one hour. It can include an `expired` effect list. A second set for the same logical grant, name, and recipient replaces the old due time and expiry body. Only the current timer can expire. `cancel_timer` removes it without running `expired`; its `cancelled` result is one if it removed a timer and zero otherwise. `read_timer` returns numeric `active` and `remaining_ticks`, both zero when absent. Both operations accept `as` for result expressions.

```yaml
effects:
  - type: read_timer
    name: combo
    target: target
    as: window
  - type: branch
    when: {type: compare, left: {expr: 'result.window.active'}, op: eq, right: 1}
    then:
      - type: cancel_timer
        name: combo
        target: target
    else:
      - type: set_timer
        name: combo
        target: target
        duration: 2s
```

Grant timers survive separate casts while their class and definition remain active. A timer created inside a status or area belongs to that controller instance. Cancellation, death, logout, class switching, affected reloads, and shutdown remove timers without expiry callbacks. Timers and expiry work have separate bounded capacities. Player offline time pauses their due clocks. Timers are temporary and are not saved.

## Ability charges

Add `charges` to an activated ability. `max` is 1..16, `recharge` is a positive duration of at most one hour, and `mode` is `sequential` by default or `parallel`. Each successful start consumes one charge after target, work, capacity, cooldown, and resource checks. A rejected start consumes none. A committed start keeps its cost and charge if a later effect is interrupted.

```yaml
charges: {max: 2, recharge: 12s, mode: parallel}
effects:
  - type: damage
    target: target
    amount: 3
    damage_type: minecraft:magic
    as: strike
  - type: branch
    when: {type: compare, left: {expr: 'result.strike.health_lost'}, op: gt, right: 0}
    then: [{type: restore_charge, count: 1}]
```

`restore_charge` restores 1..16 missing uses on a logical grant and reports `restored` and `available_charges`. It removes the corresponding latest parallel recharge timers. For sequential recharge, the current timer stays until no uses are missing; the operation then removes it. No duplicate refill follows. `reduce_cooldown` takes an `amount` duration, clamps a grant's existing cooldown at zero, and reports `remaining_ticks`. It does not grant charges. Both operations accept an optional local `grant` name; omitting it uses `self`.

The simple `cooldown: 2s` form still works. A mapping can add up to eight shared groups and an opt-in global timer. Group labels use the pack namespace and can be shared across classes. The global timer blocks only abilities that opt in. The HUD shows the longest active timer that currently blocks that grant.

```yaml
cooldown:
  duration: 2s
  groups: [shared_casting]
  global: 500ms
```

All applicable timers start in the same server turn as the charge and resource payment. Changing a duration on reload affects new timers; existing remaining time stays in the player record. The compiler checks named grant references in each class that creates the effect. `reduce_group_cooldown` takes `group` and `amount`; `reduce_global_cooldown` takes `amount`. Both report `remaining_ticks` and clamp at zero.

`reduce_recharge` takes an `amount`, optional local `grant`, and `which: earliest`, `latest`, or `all`. It reports `restored`, `available_charges`, and `next_recharge_ticks`. A recharge timer reduced to zero restores one use. Sequential recharge then starts the next missing use at its full declared interval. The default selection is `earliest`. These edits affect the targeted timers; a group or global cooldown edit never grants a charge.

```yaml
- type: reduce_recharge
  grant: primary_attack
  amount: 1s
  which: earliest
- type: reduce_group_cooldown
  group: shared_casting
  amount: 500ms
```

Available charges and remaining recharge timers are saved per player and logical grant. Online timers advance while the class is inactive and pause offline. A valid reload keeps existing timer durations. Reducing capacity clamps available uses and pending timers; increasing capacity starts recharge for the new missing uses. The HUD shows available/maximum uses and the next recharge time.
