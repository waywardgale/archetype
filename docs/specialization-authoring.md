# Specializations in the current build

A `specialization` belongs to one class and adds its own active or passive ability grants. The player keeps one selected specialization for each owned class. Switching classes preserves each choice, the class's progression, and logical grant cooldowns and charges. Switching specialization cancels ongoing work from affected grants and activates the newly selected passive grants.

```yaml
kind: specialization
id: workshop:flame
name: Flame
class: {ref: workshop:mage}
abilities:
  firebolt: {ref: workshop:firebolt, slot: primary}
```

Grant names cannot conflict with base class grants or progression unlocks. Different specializations of the same class may use the same logical grant name; its cooldown identity survives a switch. A player selects a specialization with the `K` key or `/archetype specialize <namespaced-id>` after owning the class. The client receives only the active class's available specializations. The saved choice is restored on login, and a removed specialization stays dormant in saved data until its definition returns.

An unlock tree can set `specializations: [workshop:flame]` to expose its nodes and grants only while that class specialization is selected. Its track must be class-scoped and eligible for the specialization's class. Purchases stay saved when the player switches away, while their grants stop until that specialization is selected again.

Switching costs and an expanded selection screen remain release work; this page describes the implemented subset.
