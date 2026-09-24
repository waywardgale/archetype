# Manifest authoring design discussion

Status: accepted design. Q66-Q70 establish the authoring policies; Q81 accepts the concrete grammar and examples in [manifest-spec.md](manifest-spec.md). [ability-coverage.md](ability-coverage.md) expands the required built-in composition catalog.

## Settled constraints

Manifests use descriptive YAML, stable namespaced IDs, explicit operation types, readable durations, and explicit arithmetic expressions. Unknown fields and invalid references are errors. Inline abilities and separate reusable definitions with parameters are accepted; inheritance chains, command execution, embedded scripts, and unrestricted loops are excluded.

Built-in mechanics and Kotlin extensions use the same registration approach. Definitions provide the information needed for validation, editor completion, and author documentation. Valid saved changes apply automatically, with the complete definition set validated before application. These contracts are recorded in [design.md](design.md).

Progression, summon AI, and terrain editing remain in v1. Authoring changes must preserve their accepted accounting, lifecycle, and restoration rules in [progression.md](progression.md) and [summons-and-terrain.md](summons-and-terrain.md).

## Settled decisions

- Q66: Use a `pack.yaml` file for metadata, format version, and dependencies. Put one reusable definition in each additional YAML file, with an explicit kind and stable ID. Folders organize content without determining identity. Keep small class-specific abilities inline, with stable local names.
- Q67: Limit `expr` to typed numeric calculations using arithmetic and a small documented set of functions such as min, max, clamp, and rounding. Keep conditions structured with all, any, and not. Expressions read declared parameters and exposed gameplay values without assignments or general-purpose code. Invalid calculations produce diagnostics rather than silently becoming zero.
- Q68: Use named read-only contexts for actor, owner, target, origin, event, and parameters. Actor is the entity performing the ability, while owner is its owning player. Check field availability for the execution stage and target type. Read live values when each step runs. Allow explicit named snapshots for values that should be captured earlier, such as damage fixed when a projectile launches.
- Q69: Extend explicit references and typed parameters to reusable conditions, selectors, and effect sequences. Parameters declare types and optional defaults. Overrides bind parameters rather than patch arbitrary YAML paths. Reject recursive reference cycles. Reuse should not require deep inheritance, text substitution, or YAML merge tricks.
- Q70: A file rename leaves an explicit ID unchanged. Removing a definition ends its behavior and keeps saved progression, resources, cooldowns, and companion records dormant rather than deleting them. Apply the accepted invalid-talent refund rules without replaying rewards or restoring refunded purchases automatically. Offer explicit one-to-one old-ID to new-ID migrations, applied once per saved record, with kind and conflict validation. Reject incompatible state changes without a supported migration.

## Authoring details

For Q66, moving a file or reorganizing folders must not change definition identity. An inline ability's stable local name belongs to its containing definition. Extracting it into a reusable definition requires preserving its player-facing ability identity or an explicit migration; file layout alone is not a migration.

For Q67, a numerical field can contain a literal such as `6` or an expression such as `{expr: "6 + params.power * 2"}`. This illustrates the accepted convention; no schema implementation exists yet. The parameter must be declared and checked. Boolean conditions remain structured instead of adding a second general-purpose expression language.

For Q68, context names are available only where they make sense. A projectile impact can expose an impact position and hit entity; an ordinary activation has no impact event. Optional participants require a presence condition or explicit fallback. Reading current health or a resource later in a sequence uses its value at that step. A named snapshot captures a value once and does not allow later assignment. Exact YAML spelling remains open.

For Q69, definitions expose only declared parameters. Diagnostics should identify both the reusable definition and the reference supplying an invalid argument. Cycles between reusable definitions are authoring errors; bounded runtime reactions follow their separately accepted causal-chain rules. This rule governs parameter binding for reusable references. Empowerments retain their accepted named add, modify, remove, and replace operations, including complete behavior replacements.

For Q70, valid removal stops active behavior immediately and performs required cleanup. Remaining references to a removed definition make the edited set invalid, retaining the last working definitions under Q9. Dormant state is not usable gameplay state. Restoring the same compatible ID can make retained state available again, subject to current ownership, selection, and eligibility rules. Previously refunded choices remain unselected. A migration must not merge conflicting saved records, duplicate one-time grants, discard real inventory, or erase pending terrain restoration. Purging saved state is a separate explicit maintenance action, not an incidental manifest edit.

## Remaining design work

Q71-Q80 settled execution, state, timing, presentation, and diagnostic policies in [runtime-behavior.md](runtime-behavior.md). The accepted [manifest specification](manifest-spec.md) and [module design](architecture.md) turn them into concrete contracts. [design-review.md](design-review.md) records the final accepted round, and [ability-coverage.md](ability-coverage.md) supplies the complex-ability coverage contract. Confirm shared understanding before implementation. These are remaining design decisions within the accepted v1 scope.
