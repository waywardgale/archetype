# Resources in the current build

A `resource` definition supplies `scope: class` or `scope: player`, `min`, `max`, `initial`, and optional positive regeneration. Class balances belong to a player's class. Player balances are shared across that player's classes. Regeneration advances online under the current active-class policy; saved balances and regeneration timers pause offline.

`gain_resource` adds a nonnegative amount and clamps at `max`. `spend_resource` subtracts a nonnegative amount and interrupts the current source if it would fall below `min`. Both report the actual absolute `amount` changed. Ability `costs` are checked before activation and committed with charges and cooldowns on a successful start.

`set_resource` takes `resource` and `value`, clamps the requested value into the declared range, and reports `previous`, `current`, and absolute `changed`. `reset_resource` takes `resource` and restores `initial` with the same result fields. An expression can supply a signed value for `set_resource` when the declared minimum is negative.

```yaml
effects:
  - type: set_resource
    resource: focus
    value: 5
  - type: reset_resource
    resource: focus
    as: reset
  - type: heal
    target: actor
    amount: {expr: 'result.reset.changed'}
```

The optional `resource_reset` fixture validates these fields. Resource amounts are saved per player; a compatible reload clamps them to changed bounds without resetting them. Summon-owned resources, typed state fields, resource modifiers, and migrations remain unimplemented.
