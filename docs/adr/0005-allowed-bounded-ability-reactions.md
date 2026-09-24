# Allowed bounded reactions to ability-generated events

Archetype allows events caused by one ability to trigger other abilities so manifest authors can compose reactions such as retaliation and healing. A reaction cannot re-enter its own causal chain, and the engine caps total generated work. This preserves combinations while terminating recursive reflection chains.

Supported events separate before adjustments from after reactions to actual outcomes. Explicit priority and stable IDs determine reaction order. Causal ancestry continues through delayed effects and projectiles. Operators control work limits, while required cleanup survives gameplay budget exhaustion. Concrete source keys and budget values remain part of the detailed specification and implementation validation.
