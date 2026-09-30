# Activation modes in the current build

An omitted `activation` uses `{type: activated}`: one press validates the target, commits costs and cooldowns, and runs `effects` once. `{type: passive}` runs once for its owning class while active. Passives cannot declare a cast target, costs, cooldown, or charges.

`{type: toggle}` runs `effects` on its first press and retains the activation's ownership until the next press or source cleanup. The first press commits costs, charges, and cooldowns. The second press turns it off without another payment, even during cooldown. A maintained area created by the toggle ends when the toggle ends. The HUD shows ON or OFF.

```yaml
activation: {type: toggle}
effects:
  - {type: create_area, area: maintained_field, anchor: {attached: actor}}
```

`channel` runs `effects` at a successful start and again every declared positive interval while the input remains held. The start commits the ordinary costs, charges, and cooldowns once. Each later pulse has a fresh bounded work budget; `periodic_costs` are checked and committed together before that pulse. If a periodic payment cannot be made, the channel and all of its owned work end. Releasing the key, class switching, source loss, and an optional `max_duration` also end it. The server owns all timing and payment. A client release message is only a request to end its own active channel.

```yaml
activation:
  type: channel
  every: 100ms
  max_duration: 4s
  periodic_costs: [{resource: focus, amount: 1}]
effects:
  - {type: heal, target: actor, amount: 1}
```

`charge` holds input without paying. Release before `min_hold` cancels without a cost or cooldown. A valid release validates current targeting and other requirements, then commits costs, charges, and cooldowns once. Reaching `max_hold` releases automatically on the server. `charge.held_ticks` and `charge.fraction` are read-only numeric bindings available in the release effects; the fraction is capped at one. Death, logout, class switch, and affected reload discard a pending hold without firing release effects.

```yaml
activation: {type: charge, min_hold: 100ms, max_hold: 1s}
effects:
  - {type: heal, target: actor, amount: {expr: 'charge.held_ticks * 0.25'}}
```

`confirm` arms a cast on the first press and commits it on a second press within the declared window. The first press pays nothing. The confirming press rechecks current targeting, restrictions, costs, charges, and cooldowns on the server. An expired, invalidated, or cancelled arm cannot fire. The HUD marks an armed grant as CONFIRM.

```yaml
activation: {type: confirm, window: 5s}
effects:
  - {type: heal, target: actor, amount: 2}
```

`recast` runs its primary `effects` immediately on the first press, paying costs and starting cooldowns once. A second press during the declared window runs `recast_effects` without another payment, then cancels the first activation's remaining owned work. The second body has a fresh bounded work budget, current target validation, and its own source lifetime. Expiry of the window cancels the first activation's remaining owned work. The HUD marks an open window as RECAST.

```yaml
activation: {type: recast, window: 5s}
effects:
  - {type: create_area, area: maintained_field, anchor: {attached: actor}}
recast_effects:
  - {type: heal, target: actor, amount: 3}
```

These modes are checked by the fixture pack and deterministic runtime tests. Running-world input, HUD, and timing behavior still need integration evidence.
