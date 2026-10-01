# Movement operations in the current build

The server resolves movement and applies native entity collision. Manifests cannot supply coordinates through a cast packet. These operations use only loaded chunks and are bounded to 32 blocks per invocation. Their results are available through `as`; an unavailable actor, target, or destination supplies no result and skips dependent expressions.

`dash` moves the caster in its server aim direction, toward its selected entity, or toward the captured ground point:

```yaml
target: {type: entity, range: 16}
effects:
  - {type: dash, direction: actor.aim, distance: 8, as: motion}
  - type: damage
    target: target
    amount: {expr: '2 + result.motion.travelled'}
    damage_type: minecraft:magic
```

`direction` defaults to `actor.aim`; the other values are `actor.to_target` and `actor.to_ground`. `distance` is a constant or numeric expression in 0.01..32. `travelled` is actual progress along the requested direction, and `blocked` is 1 when collision stopped the move before the requested distance, 0 otherwise. A later effect checks its target again from the new position. A delayed follow-up retains the target but still checks it when the delay ends.

`impulse` moves `actor` or a selected `target` away from or toward the caster. Its `direction` is `away` or `toward`, and `distance` has the same bound and result fields as `dash`. Both entities must have live positions in the same dimension. If their positions coincide, no direction or result is available.

```yaml
effects:
  - {type: impulse, target: target, direction: away, distance: 4, as: push}
```

`safe_teleport` moves `actor` or a selected `target` to the live `actor` position, selected entity `target` position, or captured `ground` point. Its result is `arrived`: 1 on success and 0 when the landing is blocked. The destination must be in the same dimension and within 32 blocks of the moved entity. The Minecraft adapter requires loaded destination chunks, a sturdy supporting block, an unobstructed body, and a position inside the world border before teleporting. A missing destination supplies no result.

```yaml
target: {type: ground, range: 16}
effects:
  - {type: safe_teleport, target: actor, destination: ground, as: arrival}
  - type: branch
    when: {type: compare, left: {expr: 'result.arrival.arrived'}, op: eq, right: 1}
    then:
      - {type: heal, target: actor, amount: 2}
```

The optional fixture pack includes [dash](../src/test/resources/packs/spatial/workshop/dash-followup.yaml), [push](../src/test/resources/packs/spatial/workshop/push-followup.yaml), and [safe arrival](../src/test/resources/packs/spatial/workshop/safe-arrival.yaml). Offline tests cover manifest composition, while opt-in Fabric game tests cover native collision and teleport landing in a running server. A complete multiplayer and client input check remains; charge, tether, arc, and orbit motion remain unimplemented.
