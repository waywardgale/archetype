# Summon and terrain design discussion

Status: accepted design. Q51-Q65 establish the policies; Q81-Q85 settle the concrete design, companion recall, and retained-item recovery.

## Settled scope

Summon AI and terrain editing are required in v1. Authors define their content through manifests using the framework's conditions, targeting, effects, and registered Kotlin extensions. Only players have classes; a summon does not need its own class.

Summons act for an owning player. The existing ally rules include owned creatures, and progression awards caused by summons credit their owner. Empowerments can change summon behavior. The general temporary-effect cleanup rules are accepted in Q27, with the companion persistence and terrain restoration rules below.

## Settled decisions

### Summon authoring and terrain operations

- Q51: Author summon AI using prioritized rules and named behavior modes. Reuse conditions, targeting, and ability/effect definitions. Include standard follow, guard, attack, heal, and retreat behaviors. Summons can use abilities without having classes.
- Q52: Support timed summons and summons that remain until dismissed, following class cleanup by default. An explicit persistent-companion option retains identity and declared state. Remove its world presence while its owner is unavailable or its source class is inactive, and restore one instance when eligible again. Companion death follows Q56.
- Q53: Provide optional HUD orders for follow, stay, focus target, and dismiss, selectable per summon or group. Authors choose which orders are available. These are gameplay orders, not Minecraft command execution.
- Q54: Support temporary and permanent terrain edits, defaulting to temporary. Temporary edits retain the original block state and restore only positions still owned by the effect, preserving later changes by players or the world. Permanent changes remain after the ability ends. Ownership, not just equality of block states, determines whether restoration is allowed.
- Q55: Support set, replace, and break operations over points, lines, boxes, and spheres, plus reusable authored block patterns and block/tag filters.

### Summon lifecycle and terrain ownership

- Q56: Ordinary summons disappear on death and can be summoned again through their ability. Persistent companions retain a dead state until an explicit revival action succeeds. Authors can instead declare automatic revival with a delay and optional cost. Reloading, reconnecting, and switching classes do not revive dead companions.
- Q57: The summon is the actor, with separate access to its owning player. Costs, cooldowns, resources, and statuses belong to the summon by default. Spending the owner's resources requires an explicit reference. Persistent companions retain declared resources and cooldowns while absent, with timers paused. Owner attribution for progression remains unchanged.
- Q58: Use separate movement and action channels so a summon can move while attacking or healing. The highest-priority eligible rule wins each channel, with declaration order breaking ties. Let active actions finish unless interruption is explicitly allowed. Player orders override the relevant autonomous movement or target choice while normal ability conditions still apply.
- Q59: Keep ordered effect layers at each edited position. When the top layer ends, reveal the newest remaining active layer or the original block. Ending a covered layer must not make it return later. External edits or permanent terrain edits relinquish all older temporary ownership at that position.
- Q60: Temporary edits produce no item or XP drops, including when temporary replacement blocks are broken. Permanent break operations can explicitly enable normal loot. Skip blocks with inventories or other block-entity data by default. Offer explicit support with preserved data and locked interaction while temporarily replaced, so restoration cannot duplicate stored contents.

Restoration after an owner returns means returning an eligible living companion to the world. Revival changes a dead companion back into a living one. These are separate operations.

For Q59, if a stone wall is covered by ice and the wall expires first, the end of the ice restores the original terrain, not the expired wall. A player modification ends the older temporary layers' claim on that position.

Q60 includes explicit inventory and block-data handling within v1. The concrete specification still needs to define supported data operations and how retained contents are recovered if an external edit ends restoration ownership. Preserving later world changes must not silently delete retained items.

### Equipment, navigation, and world behavior

- Q61: Manifest-generated equipment belongs to the summon, cannot be taken, and never drops. Pickup and real-item storage are disabled by default. Authors can enable real inventories with explicit death and cleanup policies for keeping, dropping, or returning items. Persistence retains actual contents without regenerating copies.
- Q62: Configure follow distance, target acquisition range, and pursuit limit. Use the entity's supported navigation. Stop pursuing unreachable or out-of-range targets and return to the owner or guard position. Teleport catch-up is opt-in and requires a valid landing position.
- Q63: Do not force chunk loading. Require a terrain operation's region to be loaded before it starts. Keep a saved restoration record and defer cleanup for unloaded positions until their chunks load, before normal interaction. After restart, finish cleanup of ended temporary edits rather than resume their casts. Ordinary summons end on chunk unload. Persistent companions suspend and return at their saved location only when it is loaded and otherwise eligible.
- Q64: Let normal block physics apply. Restoration covers positions owned by the terrain effect, not indirect fluid spread, fire, falling blocks, redstone actions, or their consequences. Require explicit opt-in to place fluids and gravity-affected blocks. Neighboring physics can still react to ordinary edits.
- Q65: Use YAML palettes mapping short symbols to block states and layer grids describing their arrangement. Declare an origin and allow rotation and mirroring at use sites. Give skipped cells and explicit air distinct symbols so empty space does not silently erase terrain.

Q61 requires lifecycle policies only when authors enable real-item storage. Ordinary combat summons need no inventory declarations. Generated equipment must remain distinguishable from items transferred by players or picked up from the world.

Q63 applies Q27's shutdown cleanup to temporary terrain without requiring unloaded chunks to be kept active. Saving a restoration record supports completing cleanup after a restart; it does not preserve the originating activation. Crash ordering and recovery need explicit implementation validation.

Q64 deliberately limits what temporary restoration promises. Removing a block beside water may cause the water to flow even if the manifest places no fluid itself. Reversing every resulting world change would require a separate, substantially broader design. The accepted restrictions on drops from temporary blocks and on interaction with preserved inventories still apply.

## Later decisions

The accepted [manifest specification](manifest-spec.md) and [module design](architecture.md) cover placement checks, partial-operation cleanup, and companion reconciliation. Q75 settled work-limit policy; Q83 and Q84 in [design-review.md](design-review.md) settle companion recall and retained-item recovery. [ability-coverage.md](ability-coverage.md) requires persistent areas and standard target-shape feedback. A full block-pattern construction preview is not included. Native recovery and navigation still require integration validation.
