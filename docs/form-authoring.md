# Status-owned ability replacements in the current build

A timed status can replace a class grant's complete ability definition. The grant name and slot remain stable, so the replacement uses the same cooldown and charge record. Its own costs, cooldown rules, targeting, activation mode, and effects take effect while the status contributes. When the status ends, the base ability returns; switching forms does not refill charges or resources.

```yaml
kind: status
id: alternate_form
duration: 2s
modifiers:
  - type: replace
    target: {ability: form_attack}
    replacement: {ref: alternate_attack}
    priority: 10
```

`target.ability` is the logical grant name, not the base definition ID. `replacement` names an existing global ability. If multiple active statuses replace one grant, the highest priority wins. Two different replacements for one grant at equal priority are rejected at compilation, so declaration or application order cannot decide an ambiguous form. The inspector reports both the effective and base definition IDs.

When a form changes an ongoing toggle, channel, projectile, area, or delayed activation, the previous activation's owned work is cancelled. A timed form applied by that same activation remains active until its own expiry or source cleanup. A normal expiry restores the previous effective ability. Status source cleanup still runs on death, logout, class switch, dimension loss, affected reload, and shutdown.

The `alternate_form` fixture and deterministic tests cover self-applied replacement, old-work cancellation, restoration, charge identity, class cleanup, and unrelated reload. Running-world client input and status feedback remain unverified. Other modifier types and progression empowerments remain required.
