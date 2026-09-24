# Applied valid manifest edits automatically

Archetype automatically validates saved manifest edits and applies a valid definition set without a reload command, restart, or confirmation. Invalid edits keep the last working definitions and produce author-facing diagnostics. This prioritizes immediate author feedback over manual reload control.

Accepted edits stop affected running abilities, clean up their ongoing effects, and refresh passive bonuses while unrelated abilities continue. Completed world changes, such as damage already dealt, remain. We accepted interruption of affected abilities to avoid maintaining multiple running versions of their definitions.

Stable IDs preserve existing resource amounts within their new limits and preserve remaining cooldowns across edits. Changed cooldown durations apply to the next use, so authoring changes do not refill resources or reset cooldowns. File renames do not change explicit IDs. Removed definitions leave saved state dormant; explicit one-to-one ID migrations preserve compatible state, while incompatible changes require a supported migration. Invalid references retain the last working definition set.
