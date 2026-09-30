# Passive abilities in the current build

An ability can declare `activation: {type: passive}`. Its effects run once when the class becomes active and are owned by that player, class, and logical grant:

```yaml
kind: ability
id: workshop:passive_focus
name: Passive focus
activation: {type: passive}
effects:
  - {type: gain_resource, resource: focus, amount: 1}
```

Re-selecting the same class or applying an unrelated manifest edit does not replay setup. Switching away, death, logout, dimension change, or replacing the passive definition cancels its ongoing areas, statuses, timers, barriers, and scheduled work. Re-entering the class or returning to an available player body starts a fresh passive instance. A changed passive definition restarts that instance under automatic reload. Failed setup is reported once and is retried after a class change, reconnect, death, or valid definition publication.

Passive abilities have no activation target, cost, cooldown, or charges. They can refer to the actor and can establish owned controllers; target-dependent effects need a selector that supplies a target. The compiler rejects unsupported passive fields and target use at the field path. Setup and each controller pulse retain the ordinary work limits.

Areas can declare `lifetime: maintained` to remain attached for the passive's source lifetime. Timed statuses remain finite; an area's `buffs` stay for each membership's lifetime. Event subscriptions and a full passive reaction catalog remain required for v1.
