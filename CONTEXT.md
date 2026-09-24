# Archetype

Archetype defines gameplay classes and their capabilities through authored manifests.

## Language

**Class**:
A player's gameplay identity defined by its abilities and mechanics.
_Avoid_: Character, profession, archetype when referring to an individual class

**Owned class**:
A class that a player has access to activate under the world's class rules.
_Avoid_: Active class, unlocked class when no unlocking mechanic is involved

**Active class**:
An owned class whose abilities and mechanics currently apply to the player.
_Avoid_: Selected class, owned class when describing current gameplay benefits

**Ability**:
A distinct gameplay capability, whether activated directly or applied automatically. Classes and summons can use abilities.
_Avoid_: Skill, spell when referring to abilities in general

**Actor**:
The entity performing an ability, such as a player or their summon.
_Avoid_: Owner when referring to the entity acting rather than the player it belongs to

**Ability owner**:
The player on whose behalf an ability acts. A player acting directly is also their own ability owner.
_Avoid_: Actor when referring to a summon owner's role rather than the summon performing the ability

**Snapshot**:
A gameplay value captured at a chosen moment for later steps to use unchanged.
_Avoid_: Live value when referring to a previously captured value

**Effect result**:
The actual outcome of an effect, such as health lost, health restored, or the identity of an object it created.
_Avoid_: Requested amount when referring to what actually happened

**Ability slot**:
A position for a player-activated ability that the player can bind to an input.
_Avoid_: Ability key, hotkey when referring to the position rather than its chosen input

**Ability page**:
The group of ability slots for one active class presented together in the HUD.
_Avoid_: Active class when referring only to the displayed group of controls

**Reaction**:
An ability's automatic response to a gameplay event.
_Avoid_: Activation when referring specifically to an event-driven response

**Event**:
A pending gameplay action or its reported outcome to which abilities can react.
_Avoid_: Effect when referring to the occurrence rather than a change an ability produces

**Causal chain**:
The path of gameplay actions and reactions that caused a later action or reaction.
_Avoid_: Sequence when referring to causal ancestry rather than an authored list of steps

**Effect**:
A gameplay change or presentation produced by an ability.
_Avoid_: Status when referring to effects in general

**Maintained effect**:
A temporary effect whose lifetime depends on the activation that maintains it.
_Avoid_: Timed effect when describing an effect that ends with its activation

**Timed effect**:
A temporary effect with its own duration that can outlive the activation that produced it.
_Avoid_: Maintained effect when describing an independently timed effect

**Ally**:
A player or owned creature treated as friendly under the world's relationship rules.
_Avoid_: Teammate when a formal team is not required

**Resource**:
A named, bounded gameplay value with optional regeneration.
_Avoid_: Mana when referring to resources in general

**Mechanic state**:
Declared memory used by a mechanic, such as a stance, flag, counter, or remembered target.
_Avoid_: Resource when the value is not a spendable or regenerating gameplay quantity

**Ability charge**:
One available use of an ability whose spent uses recover over time.
_Avoid_: Charged ability when referring to available uses rather than holding input to build an attack

**Cooldown group**:
A named set of abilities that share a waiting period before another use is allowed.
_Avoid_: Ability page when referring to shared timing rather than displayed controls

**Status**:
A named gameplay condition with optional duration, stacks, and effects.
_Avoid_: Potion effect when referring to statuses in general

**Area**:
A shaped region that selects targets and can maintain behavior while entities enter, remain inside, or leave it.
_Avoid_: Terrain edit when referring to a gameplay region that need not change blocks

**Barrier**:
A temporary protection with a finite capacity to absorb eligible damage.
_Avoid_: Shield item when referring to an ability's absorption capacity

**Target**:
A player, mob, projectile, or world position selected for an ability's effects.
_Avoid_: Victim when referring to targets in general

**Projectile**:
A game entity that carries an attack or other effect through the world, such as an arrow or thrown object, and can be selected independently of its owner or hit target.
_Avoid_: Projectile owner, impact target when referring to the projectile itself

**Summon**:
A creature or construct created by an ability to act on behalf of its owning player.
_Avoid_: Class when referring to the summon or its behavior

**Companion**:
A persistent summon that can return with its identity and remembered state after being absent from the world.
_Avoid_: Summon when specifically referring to persistence across absences

**Revival**:
Returning a dead companion to life under its declared rules.
_Avoid_: Restoration when referring to death being reversed rather than a living companion returning to the world

**Recall**:
Bringing an existing living companion back into active presence without changing its identity or restoring its health.
_Avoid_: Revival when the companion is already alive

**Summon order**:
A player's instruction directing one summon or a group, such as following, staying, focusing a target, or dismissing.
_Avoid_: Command when it could imply Minecraft command execution

**Block pattern**:
A reusable arrangement of blocks with a declared origin, including positions to change and positions to leave untouched.
_Avoid_: Structure when referring specifically to an authored pattern rather than a placed construction

**Manifest**:
An authored description of gameplay content that Archetype interprets.
_Avoid_: Script, command sequence

**Manifest pack**:
A collection of related manifests that supplies gameplay content for a world.
_Avoid_: Mod when referring to manifest content

## Progression

**Specialization**:
An optional focus within a class that supplies its abilities and passives while sharing that class's progress.
_Avoid_: Class when referring to a specialization within one class

**Progression track**:
A named course of advancement whose earned progress belongs to a player, usually for one class, and can be shared across classes when explicitly defined that way.
_Avoid_: Resource when referring to saved advancement

**Progression XP**:
The accumulated advancement earned on a progression track, distinct from Minecraft's vanilla experience currency.
_Avoid_: Vanilla XP when referring to a track's earned advancement

**Level**:
A player's stage on a progression track, derived from its cumulative XP thresholds.
_Avoid_: Rank when referring to the player's stage on the track

**Talent points**:
A named spendable progression budget for purchasing unlock nodes or ranks, separate from progression XP.
_Avoid_: XP when referring to the points spent on selections

**Unlock tree**:
An authored collection of unlock nodes and their requirements that describes available advancement.
_Avoid_: Talent tier when referring to the whole tree

**Unlock node**:
An entry in an unlock tree that grants a declared benefit under its requirements and selection rules.
_Avoid_: Ability when the node grants an empowerment or another benefit

**Talent**:
A player-selectable progression option that grants an ability, empowerment, or other declared benefit.
_Avoid_: Ability when referring to talents in general

**Empowerment**:
A progression-granted alteration to an ability or class mechanic, including complete replacement of its behavior.
_Avoid_: Stat bonus when referring to empowerments in general
