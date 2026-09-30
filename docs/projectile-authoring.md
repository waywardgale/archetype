# Projectile authoring in the current implementation

A `projectile` definition supplies speed in blocks per second, downward gravity in blocks per second squared, finite lifetime, collision filter, and separate callback bodies. The installed runtime traces a source-owned logical projectile against loaded native blocks and living entities once per server tick. It renders a built-in critical-particle trail while in flight. By default the first eligible entity or solid block consumes it; that consuming impact never also runs `expiry`.

```yaml
kind: projectile
id: workshop:bolt
parameters:
  damage: {type: number, min: 0, max: 20}
speed: 24
gravity: 0
lifetime: 3s
collision: {entities: enemies, blocks: solid}
entity_hit:
  - {type: damage, target: event.target, amount: {expr: "params.damage"}, damage_type: minecraft:magic}
block_hit:
  - {type: gain_resource, resource: focus, amount: 1}
expiry:
  - {type: heal, target: actor, amount: 1}
```

Launch from an ability with `launch_projectile` and a reference. `direction` defaults to `actor.aim`; `actor.to_target` needs a selected entity and `actor.to_ground` needs ground targeting. The optional `as` result has `launched: 1` and a typed `handle` on success. The fixture pack uses `workshop:fixture_bolt` and `workshop:launch_fixture_bolt`.

```yaml
effects:
  - {type: launch_projectile, projectile: {ref: bolt, with: {damage: 3}}, direction: actor.aim, as: shot}
  - type: wait_for
    handle: result.shot.handle
    event: projectile.entity_hit
    timeout: 3s
    matched:
      - {type: heal, target: actor, amount: 1}
```

Collision ignores the shooter. `collision.entities` can be `enemies` (default), `allies`, or `any`; native damage still enforces server PvP and ally restrictions. The block policy is currently `solid`. The runtime bounds active projectiles globally and per owner, rejects recursive projectile creation, and validates callback contexts. Source cleanup, class switching, death, logout, dimension change, definition replacement, and unavailable chunks cancel flight without impact or expiry gameplay. A natural lifetime end alone runs `expiry`.

Optional collision and steering policies are finite:

```yaml
pierce: {additional_entities: 2}
bounce: {blocks: 1}
repeat_hit: {max_per_entity: 2, interval: 100ms}
homing: {turn_degrees_per_tick: 10}
```

`pierce` allows that many extra entity impacts after the first. `bounce` reflects flight on the declared number of additional solid-block impacts. Each collision runs its callback once. Without `repeat_hit`, an entity cannot be hit twice by the same projectile. Repeats require piercing and have both a per-entity cap and a positive interval. Homing requires `actor.to_target` at launch; it turns by at most the declared angle each tick toward that locked target, and continues on its current path if the target disappears. A callback can create another projectile only when the definition graph remains acyclic.

Projectile parameters currently support bounded numbers with optional defaults. Required arguments must appear in the launch call, and callback expressions read the launch-time snapshot through `params.<name>`. The compiler rejects unknown arguments, missing required arguments, unavailable callback reads, and literal values outside the declared bounds. Dynamic values are checked at launch.

`wait_for` currently correlates a projectile handle with `projectile.entity_hit`, `projectile.block_hit`, or `projectile.expiry`. It requires a positive finite `timeout`, and can define `matched` and `timed_out` bodies. An entity match makes `event.target` available to the matched body; every impact supplies `event.position`. One wait handles one matching outcome. A bounded receipt lets a delayed wait see an earlier impact from the same cast. If an event and timeout occur on the same tick, the event wins. Source cleanup cancels the wait without either gameplay callback. The handle is a typed binding: use it as `wait_for.handle`, not as an arithmetic value.

The accepted v1 contract also requires handles and event waits for areas, statuses, summons, and casts, more parameter types, returning/catch behavior, supplied renderers, and live-world proof. Those are not present in this implementation slice; see [implementation status](implementation-status.md).
