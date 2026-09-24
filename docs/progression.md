# Progression design discussion

Status: accepted design. Q31-Q50 establish progression policy, incorporated into the concrete authoring and runtime design accepted under Q81-Q85.

## Settled scope

Progression is required in v1. Packs choose whether to enable it, so classes can also work without progression. Progression content must be authored declaratively; Archetype does not ship a class roster or impose a fixed progression economy.

The rest of the framework's accepted contracts are recorded in [design.md](design.md). In particular, gameplay decisions belong to the server, content belongs to the world, manifests use stable IDs, and saved valid edits apply automatically. Progression state follows the accepted edit and accounting rules below.

## Settled decisions

### Progress and persistence

- Q31: Keep separate progress for each player's classes when progression is enabled, with explicitly shared player-wide progression tracks available. Class switching preserves progress. Server operators can configure whether class changes are free or conditional; their authority over switching rules is settled under Q36.
- Q32: Support XP and levels, prerequisite-based unlocks, and optional spendable points. Authors define customizable unlock trees and choose the abilities, empowerments, and other benefits their nodes grant. The framework must support automatic advancement, branching choices, and ability ranks. The user cited WoW: Mists of Pandaria as a reference for abilities and talents; exact replication of its row counts, level thresholds, or other systems has not been requested.
- Q33: Progress-earning rules are declared in manifests through gameplay events and conditions. No fixed earning economy is imposed.
- Q34: Keep progression values separate from Minecraft XP by default. Manifests may explicitly use vanilla XP gain as a progression source or vanilla XP points as an unlock cost.
- Q35: Preserve earned progress through death, class switching, logout, and world restarts. Loss requires an explicit manifest rule or administrative reset. Progression persistence is separate from temporary resource reset rules.

### Talents, specializations, and empowerments

- Q36: Pack rules provide switching defaults. Operators can replace switching conditions and costs or permit free switching. Class ownership, progression requirements, and active-class limits still apply.
- Q37: Authors distinguish automatic grants from player-selected talents. Trees support prerequisite links, ranks, and mutually exclusive choice groups. Authors choose thresholds, group sizes, and rewards, supporting both MoP-style rows and branching trees.
- Q38: Classes may define optional specializations, with one specialization at a time per class. Each specialization supplies abilities and passives while sharing class progress. Authors may share a talent tree across specializations or assign different trees.
- Q39: Empowerments must be able to alter an entire ability or class mechanic, including complete ability replacement. They are not limited to numeric bonuses or a small set of optional hooks. The previously accepted parameter changes, added effects, and ability replacement are included within this broader requirement. Their composition and state-preservation contracts are accepted under Q41-Q43.
- Q40: Players change talents through the talent HUD, freely by default with operator-configurable conditions and costs. Keep XP and levels, refund spent talent points, remove dependent selections that no longer qualify together, and preserve cooldowns. Refunding consumed vanilla XP or other external costs requires an explicit rule.

The Q39 flexibility requirement applies to behavior expressible through the framework's supported declarative operations. The existing decision to implement new interactions with Minecraft through registered Kotlin extensions still applies; embedded scripts and command execution remain excluded. The complete base ability definition stays reusable, while selected upgrades determine effective behavior.

### Empowerment composition and progression values

- Q41: Express changes as explicit additions, modifications, removals, and replacements of named definitions. A complete rewrite references a full replacement ability or behavior definition; small changes remain short. Empowerments can alter activation, targeting, costs, effects, resource rules, reactions, summon behavior, and other supported declarative behavior.
- Q42: Independent changes combine. Numeric additions apply before multipliers, with bounds applied last. Competing replacements require explicit precedence or mutually exclusive choices. Unresolved conflicts produce a validation error.
- Q43: Preserve a replaced ability's logical identity, input slot, remaining cooldown, and compatible resource values. Removed resources retain inactive state so restoring a talent cannot refill them. Authors declare conversions when changing resource models. Cancel affected ongoing effects and apply the valid replacement together.
- Q44: Lay out trees automatically by default, with optional author-defined node positions, icons, and descriptions. Connections reflect prerequisites. The talent HUD shows ranks, costs, and unmet requirements.
- Q45: Define cumulative XP thresholds using a table or formula and derive levels from earned XP. Talent points are separate, with optional costs per node or rank. Level-gated choices can spend no points.

### Live edits, rewards, and level caps

- Q46: Preserve earned XP and immediately recalculate levels and eligibility after valid edits. Remove invalid talent selections and their dependents, refund their spent talent points, and remove their effects. A harder curve may lower the derived level without deleting XP. Previously granted one-time rewards are not replayed.
- Q47: Existing purchases retain their original price when definitions change. New purchases use the updated price, and refunds return what was actually paid. Record point awards and one-time grants so reloads and respecs do not duplicate them. External currencies follow Q40's explicit refund policy.
- Q48: Each earning rule selects the actor, contributors, or nearby allies. Every eligible recipient receives the full reward by default. Authors may explicitly configure a fixed total to be split among recipients. Summon and projectile actions credit their owning player. The proposed default of splitting rewards was rejected.
- Q49: Class-local earning rules default to their own class's progression track. Shared tracks and additional awards are explicitly configured. Pack-level rules name their destination track; multiple active classes do not automatically multiply a rule's awards.
- Q50: Authors may define a cap per track. Capped tracks stop accepting new XP by default, with an option to bank overflow. Lowering a cap preserves previously saved XP.

Q35 preserves saved earnings through normal play events. Q46 defines the separately accepted consequence of an explicit edit to progression rules: levels and eligibility can change while earned XP stays intact. Under Q47, refunds use historical talent-point purchase prices; edits do not rewrite existing awards or replay recorded one-time grants.

## Reference: Mists of Pandaria

Original MoP granted core class and specialization abilities automatically at prescribed levels. Players selected a specialization at level 10. Its talent system used six level-gated rows at levels 15, 30, 45, 60, 75, and 90, with one of three alternatives selected per row. The talent rows were class-wide. This makes automatic ability acquisition, specialization selection, and talent choice distinct concepts in that reference. [Blizzard's released-game returning-player guide](https://worldofwarcraft.blizzard.com/en-us/news/8876440/a-return-to-world-of-warcraft), [Blizzard's talent-system explanation](https://worldofwarcraft.blizzard.com/en-us/news/3773320).

Archetype supports that arrangement through configurable unlock rules while also supporting the user's custom branching trees and optional specializations. Glyphs and MoP-specific balance values have not been added to the scope.

## Later decisions

The accepted [manifest specification](manifest-spec.md) and [module design](architecture.md) cover authored tracks, unlocks, empowerment identity, removal, migration, and reward accounting. Detailed curve validation, rank and choice constraints, and contribution eligibility must remain explicit in the concrete compiler contracts. These are compiler and integration requirements, not v1 omissions. Specific class progression content remains the user's design work. The final accepted round is recorded in [design-review.md](design-review.md).
