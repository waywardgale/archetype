# Declared state in the current implementation

Use a `state` definition for bounded mechanic memory. The current runtime supports `player`, `class`, `activation`, and `status` scopes. Player and class state is transient by default; add `persistent: true` to save it. Activation state belongs to one cast or passive instance. Status state belongs to one source contribution and is available inside that status's callbacks; it survives refresh and ends with the contribution. Activation and status state cannot persist. Class transient fields reset when that class deactivates. Player transient fields reset on death or logout. Persistent fields survive those events.

```yaml
kind: state
id: workshop:stance
scope: class
persistent: true
fields:
  mode: {type: enum, values: [steady, mobile], initial: steady}
  hits: {type: integer, min: 0, max: 3, initial: 0}
```

`boolean`, `enum`, `integer`, and `number` fields are available. Numbers require finite minimum, maximum, and initial values. Integer writes must be integral. Numeric writes clamp to the declared bounds; `add_state` accepts a signed `expr` amount up to one million in magnitude. `set_state`, `add_state`, `reset_state`, and `read_state` take a state reference and field name. `read_state` returns `value` for numeric fields, or zero/one for Boolean fields. `set_state`, `add_state`, and `reset_state` return numeric `previous` and `current` where those fields have numeric representations. Enum values are tested with `state_is` rather than read as numbers.

```yaml
effects:
  - {type: add_state, state: stance, field: hits, amount: 1}
  - type: branch
    when: {type: state_is, state: stance, field: hits, value: 3}
    then:
      - {type: set_state, state: stance, field: mode, value: mobile}
      - {type: reset_state, state: stance, field: hits}
  - {type: heal, target: actor, amount: {expr: "state.workshop.stance.hits * 2"}}
```

Expressions read numeric or Boolean fields using `state.<namespace>.<name>.<field>` when the namespace and local state name contain only lowercase letters, digits, or underscores. `read_state` supports the full namespaced reference grammar. Cost expressions cannot read state in the current runtime because costs are evaluated before a cast scope exists. State values stored in player records are tagged and bounded; compatible numeric bound edits clamp saved values. Removed fields remain dormant in saved records.

To attach state to a status, declare `scope: status` on the state definition and set `state: {ref: memory}` on the status. Its callback effects can read and write those fields. An ordinary ability can use `read_status_state` with `target`, optional `status`/`tags`, and `source: any|actor|grant` to sum a numeric or Boolean field across matching live contributions. The result has `value` and `contributions`; no match returns zeroes. Follow it with `consume_status` and a branch on actual removal to detonate one source's mark exactly once. The fixture uses `memory_mark` and `memory_detonate` for this pattern. External writes to status fields, summon state, temporary entity references, explicit state migrations, and user-facing inspection remain required for v1. The [implementation status](implementation-status.md) tracks these gaps.
