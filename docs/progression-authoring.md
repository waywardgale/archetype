# Progression authoring in the current build

Progression tracks are optional pack content. A class-scoped track keeps separate XP, level, point balances, award receipts, and purchases for each owned class. A player-scoped track shares one record across classes. Progress survives death, switching, logout, and restart. Track XP remains separate from Minecraft XP; an earning rule can award track XP when the server grants vanilla XP.

```yaml
kind: progression_track
id: workshop:practice
scope: class
classes: [workshop:mage]
levels:
  - level: 1
    xp: 0
    awards: [{id: starter, type: talent_points, budget: talents, amount: 1}]
  - {level: 2, xp: 100}
cap: {level: 2, overflow: bank}
earn:
  shared_defeat:
    event: entity_death
    phase: after
    when: {type: credited_to_owner}
    amount: 10
    recipients: {type: nearby_allies, range: 24}
    distribution: each
  gathered_xp:
    event: vanilla_xp
    amount: 2
```

`classes` is optional for class-scoped tracks. Without it, the track applies to classes in its pack. When present, only the listed classes earn and spend against the track. `entity_death` supports server-observed deaths credited directly to a player. Recipients can be `actor` (the default) or `nearby_allies` within 0.1–64 blocks of the death. The credited player and allied players must be online, alive, and eligible for the track. `each` gives the full amount to every eligible recipient. `split` shares a fixed total, giving indivisible remainder points in stable player-ID order. The `xp` field remains accepted as a short alias for `amount`.

`vanilla_xp` awards `amount` track XP for each positive vanilla experience point actually added to the eligible player. It uses only the recipient actor, cannot use a kill-source condition or group distribution, and caps one observed grant at 1,000,000 track XP. Multiple rules on a track add their multipliers before that cap. Negative or prevented experience changes award no track XP.

For `entity_death`, `{type: contributors}` selects players credited with positive native health loss to the victim within the last 400 server ticks. A blocked hit gives no credit. Contribution records are bounded, cleared when the victim dies or the contributor logs out, and neither a client claim nor mere proximity adds a contributor. `each` and `split` use the same distribution rules as nearby allies. Direct and owner-attributed native player damage are tracked; framework summons and their credit continuity still require implementation.

Levels use increasing cumulative XP thresholds starting at zero. One-time point awards are recorded by stable award ID. A cap stops new XP by default; `overflow: bank` keeps earning XP above the cap. Editing a curve recalculates derived levels without deleting earned XP or replaying an award receipt.

```yaml
kind: unlock_tree
id: workshop:choices
track: workshop:practice
nodes:
  wider:
    selection: talent
    requires: {type: level_at_least, track: workshop:practice, level: 2}
    cost: {budget: talents, amount: 1}
    choice_group: offense
    grants: [{type: empowerment, ref: workshop:wider_cast}]
```

Automatic nodes activate at their required level. Talent nodes are selected from the client talent overlay (`M`), may have up to 16 ranks, and can require prerequisite nodes or exclude alternatives through a choice group. Respec refunds recorded point payments, including each rank's historical price. A live edit removes selections that no longer qualify and refunds their recorded payments. Nodes can grant an empowerment reference or a new logical ability grant, for example `{type: ability, grant: bonus, ref: bonus_heal, slot: extra}`. The new grant appears in the active ability pages; a passive grant starts while eligible. Removing and restoring the node retains the logical grant's cooldown and charge state. Current empowerments support complete ability replacement. The larger accepted reward and empowerment grammar remains in [progression.md](progression.md) and [manifest-spec.md](manifest-spec.md).

Use `validatePacks` before installing a pack. The editor schema from `exportCatalog` describes this supported subset. The [fixture pack](../src/test/resources/packs/spatial/workshop/practice-track.yaml) is a conformance example, not shipped class content.
