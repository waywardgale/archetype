# Terrain authoring

Terrain effects run on the server and edit loaded blocks only. A temporary edit lasts six seconds unless `duration` is supplied. Use `permanent: true` to keep the world change; it cannot be combined with `duration`. A cast can edit at most 256 cells. Existing block entities are skipped. Placement of fluids and falling blocks requires `allow_fluid: true` or `allow_gravity: true` on the effect.

```yaml
kind: block_pattern
id: short_wall
origin: [0, 0, 0]
palette:
  '#': {block: minecraft:stone_bricks}
  '.': {skip: true}
  '_': {block: minecraft:air}
layers:
  - ['###']
  - ['#.#']
```

Every row has the same width, and every layer has the same row count. X runs across rows, Z advances to the next row, and Y advances to the next layer. `origin` is the cell placed at the target position. Skipped symbols leave their positions untouched; air edits them. A `place_pattern` effect can rotate by 0, 90, 180, or 270 degrees around Y and mirror across the X or Z axis before placing:

```yaml
type: place_pattern
pattern: short_wall
at: ground
rotation: 90
mirror: none
duration: 6s
as: wall
```

`at` accepts `actor`, `target`, or `ground`. The containing ability must supply the corresponding target. Both terrain effects return `placed`, the number of cells actually written. A result is unavailable if the operation cannot start.

`edit_terrain` supports point, line, box, and sphere regions. Line endpoints are integer offsets from the anchor. A box starts at the anchor and extends along positive X, Y, and Z. A sphere uses integer cells within its radius. The entire region must be loaded even when a filter leaves some cells unchanged.

```yaml
type: edit_terrain
operation: replace
at: ground
region: {type: box, size: [2, 1, 2]}
block: minecraft:stone_bricks
filter:
  blocks: [minecraft:dirt, minecraft:grass_block]
  tags: [minecraft:flowers]
duration: 6s
```

`operation` is `set`, `replace`, or `break`. Set and replace require `block`; break forbids it. Replace requires a `filter`; set and break can also filter. A filter matches if the current block ID or any listed block tag matches. Region forms are `{type: point}`, `{type: line, to: [4, 0, 0]}`, `{type: box, size: [2, 1, 2]}`, and `{type: sphere, radius: 3}`. `loot: true` is allowed only for a permanent break, which uses native drops. Temporary edits and temporary block destruction create no drops or XP.

Temporary cells are kept in a write-ahead world journal. Later edits from players or world mechanics release ownership even if they write the same block state. Covered temporary layers expire independently. On shutdown, class/source cancellation, and restart, eligible cells restore their original state; unloaded cleanup waits for a chunk-load callback and never forces a chunk to load. Existing block entities are skipped until the accepted explicit preservation and operator recovery path is implemented.
