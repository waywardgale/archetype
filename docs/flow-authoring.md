# Conditions and weighted choices in the current build

`branch.when` accepts typed `compare`, `resource_at_least`, `has_status`, and `chance` conditions. Use `all`, `any`, or `not` to compose them. Each list has 1..16 entries; one condition tree may have at most 128 nodes and eight nested levels. Unknown fields and references fail validation. A target-dependent condition with a missing target skips the branch rather than choosing `else`.

```yaml
when:
  all:
    - {type: resource_at_least, resource: focus, amount: 10}
    - not: {type: has_status, target: actor, status: silence}
```

`chance` declares `probability` from 0 to 1. The server samples it once when that condition runs. Repeating an effect evaluates the condition again for each invocation.

`choose` draws once and executes exactly one option body. Each option needs a positive integer `weight` of 1..1000 and a nonempty `effects` list. A choice has 1..16 options and total weight at most 10000. It can bind the selected zero-based `index` with `as`. Work reservations use the largest option, while runtime execution and source cleanup use the ordinary effect rules. A result from inside an option is available after `choose` only when every option provides the same typed result.

`sequence` groups a nonempty effect list and runs it in order within the current scope. A result produced inside it is available to later effects in the sequence and to steps after the sequence. Delayed invocations keep their own result bindings under the existing ownership rules.

`read_health` captures current native `health`, `maximum`, `missing`, and `fraction` for `actor` or `target`. It revalidates the recipient at execution time. A missing recipient or unavailable native view supplies no result, so dependent steps skip rather than treating missing health as zero.

```yaml
- {type: read_health, target: target, as: vital}
- type: branch
  when: {type: compare, left: {expr: 'result.vital.fraction'}, op: lt, right: 0.5}
  then:
    - {type: heal, target: target, amount: {expr: 'result.vital.missing'}}
```

```yaml
- type: choose
  as: picked
  options:
    - weight: 1
      effects: [{type: heal, target: actor, amount: 1}]
    - weight: 3
      effects: [{type: gain_resource, resource: focus, amount: 2}]
```

The optional `random_blessing` fixture validates both forms. Tests use fixed server roll sequences to verify selection order and result availability. Event-driven proc gates and bounded event traces remain unimplemented.
