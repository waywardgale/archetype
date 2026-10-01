# Barriers in the current build

`shield` creates one finite, source-owned capacity on `actor` or `target`:

```yaml
- type: shield
  target: actor
  capacity: 20
  duration: 6s
  priority: 10
  damage_type: minecraft:magic
  depleted:
    - {type: gain_resource, resource: focus, amount: 10}
```

`capacity` accepts bounded arithmetic and must evaluate above zero. `duration` is positive and capped at one hour. `priority` is -100..100, default zero. `damage_type` is an optional exact native damage-type ID; when absent, the barrier accepts every damage type. The running server checks any declared ID against its damage registry. A successful `shield` may bind the created `capacity` through `as`.

Native armor and magic mitigation happen first. Vanilla absorption then has priority over framework barriers. Only the remaining damage that could reach health consumes barrier capacity. Multiple eligible barriers consume capacity in descending priority and then creation order, without exceeding pending health damage. Native invulnerability and a completely prevented hit consume none.

Depletion queues its optional effect body until the native action commits. Natural expiry, dispel, source cancellation, recipient death or logout, class switching, and definition replacement remove capacity without running `depleted`. A depleted body is source-owned and bounded; target-dependent effects still revalidate the recipient. The current implementation caps barriers at 1024 globally and 64 per owner or recipient.

The runtime reduction and callback ordering have deterministic tests. An opt-in Fabric game test now verifies that the native damage mixin consumes a real hit before health loss and lets excess damage through when the barrier depletes. Other native mitigation, prevention, multi-source, and multiplayer cases still need running-world tests. Damage redirection, reflection, healing modifiers, and full before/after reaction policies remain part of the v1 contract.
