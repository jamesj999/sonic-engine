# v0.6 Level Editor MVP Blueprint

Date: 2026-05-09
Scope: BG layer editing, mouse input, edit persistence
Status: Blueprint (no implementation)

The editor foundation is already complete: `LevelEditorController`, hierarchical
World/Block/Chunk navigation, undo/redo via `EditorHistory`, derive-on-edit, and a
working playtest round-trip via `SessionManager.enterEditorMode` /
`resumeGameplayFromEditor`. This blueprint covers the three remaining gaps that move
the editor from "technically meets v0.6 exit criteria" to "someone could actually
edit a level and keep the work."

---

## Requirements

### Goals

1. **BG layer editing** — operator can place blocks on the background layer (layer 1)
   in addition to foreground (layer 0), with clear visual indication of the active
   layer.
2. **Mouse input** — operator can move the cursor, place blocks, eyedrop, and drag-
   paint with the mouse. Keyboard input keeps working unchanged.
3. **Persistence** — edits made in editor mode survive engine restart. On level
   reload, the last saved edit set for that game/zone/act is automatically applied
   on top of the ROM-derived baseline.

### Non-goals

- Object/ring placement, move, delete (deferred to v0.7).
- Pattern (8×8) editing — hierarchy stops at chunk; no PATTERN depth navigation.
- Block flag editing (priority, palette, flips on map writes).
- Free camera pan/zoom in editor.
- New zone / blank-slate creation.
- Cloud save or export formats beyond a single on-disk JSON sidecar.
- Mouse input outside editor mode.
- Migrating in-flight edits between schema versions (single schema version 1 only).

### Constraints

- Must not break existing `TestEditorToggleIntegration`, `TestEditorRenderingSmoke`,
  `TestEditorCommands`, `TestEditorHistory`, `TestLevelEditorController`.
- Must not perturb gameplay input — mouse callbacks fire only when active and editor
  is enabled.
- Must follow project repo policy: trailer block on every commit, branch
  `feature/ai-*`, JUnit 5 only, Java 21.
- Persistence format must be human-inspectable (project precedent: Jackson JSON with
  SHA256 envelope hash via `SaveManager`).
- ROM bytes are the source of truth; persisted edits are deltas, never replacements.

### Acceptance criteria

- AC1: With editor open, pressing the layer-toggle key flips between FG and BG.
  Active layer is visible in the editor overlay. Block placement writes to the
  active layer; eyedrop reads from the active layer.
- AC2: Moving the mouse over the world view updates the editor cursor. Left-click
  applies the primary action at the hover cell. Holding LMB and dragging across
  cells coalesces into a single undoable stroke. Right-click eyedrops at the hover
  cell. Keyboard input still works.
- AC3: After making edits, exiting the engine, restarting, and loading the same
  zone/act, the edits are present. Loading a different zone/act loads its own
  edits (or none).
- AC4: Manual `Ctrl+S` saves explicitly; toggling out of editor mode auto-saves.
  A "saved/dirty" indicator is visible in the editor overlay.
- AC5: A corrupt or hash-mismatched edit file logs a warning, quarantines the file,
  and falls back to the ROM baseline (matches `SaveManager` corrupt-quarantine
  pattern).
- AC6: New JUnit 5 tests cover: BG layer round-trip (place→render→eyedrop), mouse
  click→world-cell mapping at multiple viewport scales, persistence round-trip
  including hash quarantine on tamper.

### Assumptions

- A1: BG block placement, when written via `MutableLevel.setBlockInMap(1, x, y, ...)`,
  flows through the existing `LevelTilemapManager.rebuildDirtyRegions` path and is
  rendered by `TilemapGpuRenderer` without further changes. (Verified during
  exploration; rebuilds both FG and BG on any dirty cell.)
- A2: Mouse coordinates from GLFW are window-pixel coords; viewport-aware screen→
  world translation reuses `GraphicsManager.getViewport*()` and `Camera.getX/Y`.
- A3: Persistence shape is a per-zone-act JSON sidecar under `saves/{game}/edits/`,
  modelled after `SaveManager`'s envelope (version, gameCode, payload, hash).
- A4: BG layer toggle is a per-session control state held on
  `LevelEditorController`, not persisted. The active layer is not a property of an
  edit; the edit just records "block X written to layer Y at (x,y)".
- A5: Drag-paint stroke coalescing happens in the controller, not in mid-stack. One
  `EditorCommand` is created per stroke; the stroke command holds a list of
  per-cell before/after states.

### Risks

- R1: **Pattern data serialization** — patterns are pixel arrays. Naive JSON
  encoding bloats files. Mitigation: only serialize patterns whose
  `modifiedSinceBaseline` bit is set; encode pixel rows as packed hex strings.
  (For v0.6, pattern editing isn't a goal, so this risk is contingent on whether
  derive-on-edit ever creates a fresh `Pattern` — it does not in current code:
  derive-on-edit clones blocks/chunks only.)
- R2: **Diff-against-baseline tracking** — current dirty BitSets in `MutableLevel`
  are consumed each frame by `LevelFrameStep.processDirtyRegions()`. They cannot
  serve as the persistence diff. We need a parallel "modifiedSinceBaseline"
  BitSet per category that is never cleared except on full level reload from ROM.
- R3: **Map cell delta on layer 1** — must verify that `LevelTilemapManager`
  rebuilds the BG layer from a single dirty BG cell (not just FG). Exploration
  showed the rebuild marks both FG and BG dirty unconditionally — works for v0.6,
  inefficient long-term but acceptable.
- R4: **Mouse input gating** — must not fire in non-editor flows. Mitigation:
  `EditorInputHandler.update` only consults mouse state when invoked from the
  editor codepath; gameplay `InputHandler.update` ignores mouse state.
- R5: **Save corruption while engine is killed mid-write** — atomic write via
  temp-file + rename, matching `SaveManager` style. (`SaveManager` uses
  `Files.writeValue(file, env)` — verify atomicity, switch to temp-write-rename
  if not.)

---

## Exploration Synthesis

Findings from paired Explore agents on three bounded questions. All file paths are
relative to repo root.

### BG layer pipeline (does layer-1 edit propagate today?)

**Yes, the data and render pipelines fully support layer 1.** Only the editor UI
hardcodes layer 0.

| Component | File | Evidence |
|---|---|---|
| Map layer count | `src/main/java/com/openggf/level/Map.java:52-76` | `getLayerCount()`, layer-indexed `getValue/setValue` |
| Sonic 2 sets 2 layers | `src/main/java/com/openggf/game/sonic2/Sonic2Level.java:376` | `MAP_LAYERS = 2` |
| MutableLevel layer-aware writes | `src/main/java/com/openggf/level/MutableLevel.java:226-227` | `map.setValue(layer, bx, by, ...)`, `linearizeMapCell(layer, bx, by)` |
| Dirty region linearization | `MutableLevel.java:333-362` | `layer * width * height + y * width + x` |
| BG render path | `src/main/java/com/openggf/level/LevelRenderer.java:533-540` | Explicit `renderBackgroundShader()` |
| BG tilemap build | `src/main/java/com/openggf/level/LevelTilemapManager.java:245` | `buildTilemapData((byte) 1, ...)` |
| BG dirty rebuild | `LevelTilemapManager.java:496-503` | `foregroundTilemapDirty` and `backgroundTilemapDirty` both flagged |
| Editor UI hardcode | `src/main/java/com/openggf/editor/LevelEditorController.java:211` | `placeBlock(0, mapPosition.mapX(), mapPosition.mapY(), selectedBlock)` |
| Other layer-0 hardcodes | `LevelEditorController.java:230, 275, 347, 568` | Eyedrop reads via `getValue(0, ...)` |

**Implication**: BG editing is genuinely a small-scope change at the controller
level plus an overlay HUD update — no engine plumbing required.

### Mouse input infrastructure

**Not wired at all.** `InputHandler` is keyboard-only; no GLFW mouse callbacks are
registered.

| Component | File | Evidence |
|---|---|---|
| InputHandler keyboard-only | `src/main/java/com/openggf/control/InputHandler.java:28-89` | Only key state methods |
| GLFW key callback wiring | `src/main/java/com/openggf/Engine.java:267-271` | `glfwSetKeyCallback(...)` — no mouse equivalents nearby |
| Viewport getters | `src/main/java/com/openggf/Engine.java:993-1015` (reshape) | `viewportX/Y/Width/Height` cached on resize |
| GraphicsManager viewport | `src/main/java/com/openggf/graphics/GraphicsManager.java:910-923` | `getViewportX/Y/Width/Height()` |
| Camera origin | `src/main/java/com/openggf/Camera.java:548-608` | `getX/Y/Width/Height()` |
| Existing screen→tile math | `LevelEditorController.java:472-487` | `resolveWorldMapPosition` divides world cursor by `blockPixelSize` |

**Required transform** (from window pixel `(wx, wy)` to block index):

```
fbX = wx - viewportX                     // viewport-relative
fbY = wy - viewportY
scale = viewportWidth / 320              // integer scale
gameX = fbX / scale                       // game-space (320 wide)
gameY = fbY / scale                       // (224 tall, top-left origin)
worldX = gameX + camera.getX()
worldY = gameY + camera.getY()
blockX = worldX / blockPixelSize
blockY = worldY / blockPixelSize
```

Y-axis: project ortho2D origin is bottom-left, but level coordinates use top-left;
exploration confirms the editor world cursor uses top-left. The transform above
treats both as top-left. Spot-check during implementation.

### Persistence + level reload

**SaveManager is the precedent**; level reload via `restoreInheritedLevel` already
re-runs ROM load and swaps the mutated level back in.

| Component | File | Evidence |
|---|---|---|
| SaveManager API | `src/main/java/com/openggf/game/save/SaveManager.java:30-79` | `writeSlot/readSlotSummary`, JSON envelope with SHA256 |
| Save root | `src/main/java/com/openggf/Engine.java:670` | `Path.of("saves")` |
| Existing slot path | `SaveManager.java:97` | `root.resolve(game).resolve("slot" + slot + ".json")` |
| Level reload mechanics | `src/main/java/com/openggf/level/LevelManager.java:419-430` | `restoreInheritedLevel`: full ROM reload, then `setLevel(inherited)` swaps mutated level back |
| MutableLevel snapshot | `MutableLevel.java:91-184` | Deep copies patterns, chunks, blocks, map, palettes |
| Block.saveState | `src/main/java/com/openggf/level/Block.java:88` | `int[]` of `ChunkDesc` raw values, one per grid cell |
| Chunk.saveState | `src/main/java/com/openggf/level/Chunk.java:87` | `int[6]` — 4 PatternDescs + 2 solid tiles |
| Dirty BitSets (consumed each frame) | `MutableLevel.java:19-25, 279-324` | `dirtyPatterns/Chunks/Blocks/MapCells/SolidTiles`, plus `objectsDirty/ringsDirty` flags |
| Level identity on session | `src/main/java/com/openggf/game/session/WorldSession.java:18-86` | `currentZone`, `currentAct`, plus implicit `GameId` |
| GameId codes | `src/main/java/com/openggf/game/GameId.java` | `S1("s1")`, `S2("s2")`, `S3K("s3k")` |

**Recommendation**: per-zone-act JSON sidecar at
`saves/{gameCode}/edits/zone_{zone}_act_{act}.json`. Schema mirrors `SaveEnvelope`
(version, gameCode, payload, hash); payload contains modified-block-indices,
modified-chunk-indices, and map-cell writes per layer. Pattern modifications are
out of scope for v0.6 (no current path mutates patterns; `setPattern` API is unused
by the editor).

---

## Architecture Decision

### Pillar 1: BG layer editing

**Ownership**: `LevelEditorController` gains a per-session `int activeLayer` field
(default 0). All hardcoded layer-0 reads/writes route through it.

**Boundaries**: no change to `MutableLevel`, `Map`, `LevelTilemapManager`,
`TilemapGpuRenderer`, or any rendering code. Pure UI-layer change.

**Lifecycle**: `activeLayer` resets to 0 on `attachLevel`. Not persisted, not
captured by `EditorPlaytestStash`, not part of save files.

**Input action**: new `EditorInputHandler.Action.TOGGLE_LAYER`, bound to a key
(proposal: `L`). Re-entrant — toggles 0↔1.

**Visual feedback**: `EditorOverlayRenderer` adds a small layer indicator (e.g.,
"FG" / "BG" text) near the existing toolbar. Active-layer cursor outline tinted
differently (proposal: cyan for BG, white for FG) so operator never loses track.

### Pillar 2: Mouse input

**Ownership**: extend `com.openggf.control.InputHandler` with mouse fields and
GLFW callbacks; `EditorInputHandler` consumes them. Gameplay code does not consult
mouse state.

**Boundaries**: no change to `Camera`, `GraphicsManager` (already exposes viewport
getters), or `Engine.reshape`. New static helper class
`com.openggf.editor.EditorMouseTransform` owns the screen→world math, taking
`InputHandler`, `Camera`, `GraphicsManager` and returning an
`EditorMouseTransform.Result` (viewport-hit boolean + worldX + worldY + tileX +
tileY) so it's testable without GLFW.

**Lifecycle**: GLFW callbacks registered alongside the existing key callback in
`Engine.init`. `InputHandler.update()` advances mouse-button "pressed-this-frame"
state the same way it does keys.

**Input semantics**:
- Cursor move with no button: hover updates `worldCursor` to the hovered cell;
  ghost block previewed in `EditorWorldOverlayRenderer`.
- Left button down: stroke begins. On each cell change while held, append a
  before/after entry to the in-progress stroke. On button up, push a single
  composite `EditorCommand` onto `EditorHistory`.
- Right button click: eyedrop (equivalent to existing `E` key path).
- Scroll wheel: deferred (out of scope; reserved for future zoom).

**Stroke object**: new `commands/StrokeCommand` (composite) — sequence of
`PlaceBlockCommand` deltas with per-cell `(layer, x, y, before, after)` so undo/redo
restores cell-by-cell on apply but presents as one history entry. (Reuses existing
`PlaceBlockCommand` shape.)

**Gating**: mouse callbacks always fire from GLFW, but `EditorInputHandler` reads
mouse state only when the editor is active. Out-of-editor input handlers ignore
mouse state.

### Pillar 3: Persistence

**Ownership**: new `com.openggf.editor.persistence.EditorSaveManager` (sibling to
`SaveManager`, separate concern). Owns serialization, deserialization, file
quarantine, and apply-on-load.

**File format** (Jackson JSON, SHA256-envelope, mirrors `SaveManager`):

```json
{
  "version": 1,
  "gameCode": "s2",
  "zone": 4,
  "act": 0,
  "savedAt": "2026-05-09T12:00:00Z",
  "payload": {
    "blocks": [{"index": 42, "state": [...]}],
    "chunks": [{"index": 17, "state": [...]}],
    "mapCells": [{"layer": 0, "x": 12, "y": 8, "blockIndex": 42}]
  },
  "hash": "..."
}
```

`payload.blocks[].state` and `payload.chunks[].state` are the existing
`Block.saveState()` / `Chunk.saveState()` int arrays (already serializable
primitives). `payload.mapCells[].blockIndex` is a single byte value.

**File path**: `saves/{gameCode}/edits/zone_{zone}_act_{act}.json` under the same
root as `SaveManager`. (Reuse `SaveManager`'s save root injected from `Engine`.)

**Modified-since-baseline tracking**: `MutableLevel` gains three new BitSets
parallel to the existing dirty sets:
- `modifiedBlocksSinceBaseline`
- `modifiedChunksSinceBaseline`
- `modifiedMapCellsSinceBaseline`

Set on every mutation, never cleared by frame processing. Cleared only when
`MutableLevel.snapshot(baseLevel)` is called (i.e., fresh load from ROM). These are
the source of truth for what to serialize.

Object/ring spawn changes are out of scope for v0.6; the corresponding boolean
flags are not yet captured.

**Apply-on-load flow**:

```
LevelManager.loadZoneAndAct(zone, act)
  → ROM-driven load constructs immutable Level
  → MutableLevel created via snapshot(baseLevel)         // baseline established
  → EditorSaveManager.tryApplyEdits(gameCode, zone, act, mutableLevel)
      → if file exists: parse, verify hash, replay block/chunk/mapCell mutations
      → if hash mismatch: quarantine file, log warning, fall back to baseline
  → setLevel publishes the mutated level
```

The apply step uses the controller's `placeBlock`-equivalent path (direct
`MutableLevel` mutation API) and bypasses `EditorHistory`. After apply, the dirty
BitSets are populated as if the edits had just been made — `processDirtyRegions`
on the next frame uploads them to the GPU. Modified-since-baseline BitSets are
also populated, so a subsequent save round-trips correctly.

**Save trigger**: `EditorSaveManager.save(gameCode, zone, act, mutableLevel)`
called from:
1. Editor exit / playtest toggle (`Engine.toggleEditorPlaytestMode` → before
   `enterEditorFromCurrentPlayer` returns to gameplay).
2. Explicit save action (`Ctrl+S` → new `EditorInputHandler.Action.SAVE`).

A "dirty" indicator on the overlay tracks whether `modifiedSinceLastSave` (separate
boolean flag, cleared on save) is set.

**Atomicity**: write to `*.json.tmp`, fsync, rename. (Verify `SaveManager` is
already atomic; if not, fix both consistently.)

**Migration / rollback**: schema version 1 only. On version mismatch in a future
release, log warning and quarantine. No backward compatibility shims yet.

### Cross-cutting

- `EditorOverlayRenderer` gains: layer indicator (FG/BG), save state indicator
  (saved / unsaved), and hover ghost preview.
- New JUnit 5 tests under `src/test/java/com/openggf/editor/`:
  `TestEditorBgLayer`, `TestEditorMouseTransform`, `TestEditorPersistence`,
  `TestEditorMouseStrokeUndo`.
- No changes to `RomManager`, `GameModule`, `SessionManager`, or any per-game code.
- All three pillars are gated behind the existing `isEditorEnabled()` check.

### Why these choices

- **No new runtime-owned framework**: persistence is an editor concern; gameplay
  code has no dependency on the save file. Lives in `com.openggf.editor.persistence`,
  not in a manager registry.
- **Reuse `Block`/`Chunk` saveState formats**: avoids inventing a new serialization
  schema and re-uses the same primitives that `EditorHistory` already exercises.
- **Modified-since-baseline tracking instead of diff-on-save**: simpler, O(1) per
  mutation, doesn't require keeping a baseline `Level` reference around. Cost is
  three extra BitSets in `MutableLevel`.
- **Stroke as composite `EditorCommand`**: matches existing `EditorHistory` shape;
  no new history machinery.
- **Static `EditorMouseTransform` helper**: lets viewport→world math be unit-
  tested without GLFW, OpenGL, or a real `Engine`.

### What this rules out

- Multi-user / collaborative editing.
- Per-frame autosave (only on exit and explicit Ctrl+S).
- Resource-overlay-pipeline integration (would model edits as a `LoadOp`, but
  that path doesn't accept patterns/blocks-as-state, only ROM-byte loads — would
  need new `LoadOp` variants; deferred).

---

## Feature Design

### BG layer editing

**Behavior**:
- Press `L` → toggle active layer between 0 (FG) and 1 (BG). Toolbar shows current
  layer.
- Place / drag-place / eyedrop / derive operations all act on the active layer.
- Selected block / chunk preview is unaffected by layer (the library is layer-
  agnostic).
- Switching layers does not clear selection or undo history.

**API additions**:
- `LevelEditorController.activeLayer() : int`
- `LevelEditorController.toggleActiveLayer() : void`
- `EditorInputHandler.Action.TOGGLE_LAYER` enum entry
- `EditorOverlayRenderer.renderLayerIndicator(...)`

**Edge cases**:
- Toggle in the middle of a drag stroke: stroke completes on the original layer;
  layer change applies to the next stroke. Implementation: stroke captures
  `activeLayer` at stroke start.
- A level with `getLayerCount() < 2` (no S1/S2/S3K module currently sets this, but
  defensive): toggle no-ops with a debug log.

**Acceptance tests**:
- Place block on FG, switch to BG, place different block. `MutableLevel.getMap().
  getValue(0, x, y)` returns FG block; `getValue(1, x, y)` returns BG block.
- Eyedrop on BG cell with FG layer active reads FG block, not BG.

### Mouse input

**Behavior**:
- Cursor moves: hover ghost preview at hovered tile. Status bar coordinates update.
- LMB click on world canvas: place active block (calls `applyPrimaryAction`).
- LMB drag: stroke. Each cell entered while held adds to stroke. Release commits
  one undoable history entry.
- RMB click: eyedrop.
- Click-through on overlay panes (toolbar, library): selecting a block in the
  library pane via mouse selects it as if `selectBlock(...)` were called.
- Mouse outside viewport: cursor unchanged, no action fires.

**API additions**:
- `InputHandler.handleMouseMove(double x, double y)`
- `InputHandler.handleMouseButton(int button, int action)`
- `InputHandler.getMouseX/Y() : double`
- `InputHandler.isMouseButtonDown(int button)`
- `InputHandler.isMouseButtonPressed(int button)`
- `EditorMouseTransform.toWorldTile(InputHandler, Camera, GraphicsManager,
  MutableLevel) : Result`
  - `Result = record(boolean inViewport, int worldX, int worldY, int tileX, int
    tileY, int layer)`
- `EditorInputHandler` consumes mouse state in `update()`.
- `commands/StrokeCommand` composite implementing `EditorCommand`.

**Edge cases**:
- Window resize during drag: viewport recomputed, but transform reads current
  viewport each frame, so cursor follows mouse correctly.
- LMB pressed outside viewport, dragged inside: stroke begins on first valid cell.
- Same cell hovered twice during drag: deduplicated (stroke only records cell on
  first entry).
- LMB held + keyboard arrow pressed: keyboard cursor moves are ignored while a
  mouse stroke is in progress (avoids contention).

**Acceptance tests**:
- `EditorMouseTransform` returns correct tile coords at viewport scales 1×, 2×, 3×.
- Single LMB click commits one history entry.
- Drag across 5 cells, release, commits one history entry. Undo restores all 5
  cells.
- Mouse outside viewport returns `inViewport=false`; no action fires.
- RMB sets selection to hovered cell's block.

### Persistence

**Behavior**:
- On editor toggle out → save current `MutableLevel` deltas to disk. Indicator
  flashes "saved".
- Ctrl+S → same save flow, no editor-toggle side effects.
- On level load (via `LevelManager.loadZoneAndAct`) → after baseline established,
  attempt to apply on-disk edits.
- On game exit → no special handling; rely on prior save.

**API additions**:
- `MutableLevel.modifiedBlocksSinceBaseline() : BitSet` (immutable view)
- `MutableLevel.modifiedChunksSinceBaseline() : BitSet`
- `MutableLevel.modifiedMapCellsSinceBaseline() : BitSet`
- `MutableLevel.markModifiedSinceLastSave() : void` and
  `MutableLevel.consumeModifiedSinceLastSave() : boolean`
- `EditorSaveManager.save(GameId, zone, act, MutableLevel) : SaveResult`
- `EditorSaveManager.tryApplyEdits(GameId, zone, act, MutableLevel) : ApplyResult`
- `EditorSaveManager.SaveResult = record(boolean ok, Path file, String hash)`
- `EditorSaveManager.ApplyResult = enum { NONE, APPLIED, QUARANTINED, MISMATCH }`
- `EditorInputHandler.Action.SAVE`

**On-disk schema** (version 1):

```json
{
  "version": 1,
  "gameCode": "s2",
  "zone": 4,
  "act": 0,
  "savedAt": "ISO-8601",
  "payload": {
    "blocks":   [{"index": 42, "state": [int, int, ...]}],
    "chunks":   [{"index": 17, "state": [int, int, int, int, int, int]}],
    "mapCells": [{"layer": 0, "x": 12, "y": 8, "blockIndex": 42}]
  },
  "hash": "sha256-of-payload-json"
}
```

**Edge cases**:
- File doesn't exist: `ApplyResult.NONE`, no warning.
- Hash mismatch: rename to `*.corrupt`, log warning, return `QUARANTINED`.
- Version mismatch: same as hash mismatch (quarantine + log).
- Game/zone/act in file disagrees with caller: return `MISMATCH`, do not apply,
  do not quarantine. (Operator may have moved files; caller decides.)
- `state` array length disagrees with current block/chunk dimensions: skip that
  entry, log warning, continue applying others. (Defensive against ROM revision
  changes.)
- Block/chunk index out of range: skip + warn.
- mapCell write to layer outside `getLayerCount()`: skip + warn.
- Atomic write fails halfway: temp file removed, original untouched, log error.

**Acceptance tests**:
- Save → read → assert equal payload.
- Save → tamper hash → read → `QUARANTINED`, file moved to `.corrupt`.
- Save in zone A, attempt apply in zone B → `MISMATCH`, no mutation.
- Round-trip: edit → save → restart engine → load same zone → edit visible in
  level + dirty BitSets repopulated.
- Cross-zone edits don't bleed: edit in (zone=1, act=0), load (zone=1, act=1) →
  no edits applied.

### Cross-cutting overlay

- Toolbar gains: layer indicator (`FG` / `BG`), save state (`saved` / `unsaved*`).
- World canvas gains: hover ghost preview (translucent block at hover cell).
- Hover ghost respects active layer (preview drawn at the layer's tilemap z-order
  if needed; for v0.6, draw on top of everything for visibility).

---

## Implementation Plan

Five tasks, three of which run in parallel after a short prep phase. Test-first
for behavior changes (skill rule).

### Branch strategy

- One feature branch: `feature/ai-editor-mvp-v06`.
- Within it, the three pillar tasks (T2/T3/T4) can each be a separate worktree if
  you want true parallelism, then merged into the feature branch sequentially.

### T1 — Prep: modified-since-baseline + transform helper [sequential, blocks T2–T4]

**Owner**: single agent.
**Files**:
- `src/main/java/com/openggf/level/MutableLevel.java` (add 3 BitSets + accessors)
- `src/main/java/com/openggf/editor/EditorMouseTransform.java` (new)
- `src/test/java/com/openggf/level/TestMutableLevelBaselineTracking.java` (new)
- `src/test/java/com/openggf/editor/TestEditorMouseTransform.java` (new)

**Tests first**:
- Mutating a block sets the corresponding `modifiedBlocksSinceBaseline` bit.
- `snapshot(baseLevel)` clears all modified-since-baseline BitSets.
- `EditorMouseTransform.toWorldTile` returns correct tile at scale 1×, 2×, 3×, and
  reports `inViewport=false` outside.

**Verification**: `mvn test -Dtest=TestMutableLevelBaselineTracking,TestEditorMouseTransform`

### T2 — BG layer editing [parallel after T1]

**Owner**: single agent.
**Files**:
- `src/main/java/com/openggf/editor/LevelEditorController.java`
  (add `activeLayer`, replace 5 hardcoded layer-0 references at lines 211, 230,
  275, 347, 568)
- `src/main/java/com/openggf/editor/EditorInputHandler.java`
  (add `Action.TOGGLE_LAYER`, key binding `L`)
- `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java`
  (add layer indicator)
- `src/test/java/com/openggf/editor/TestEditorBgLayer.java` (new)

**Tests first**:
- Toggle changes `activeLayer` 0↔1.
- Place at `(x, y)` with `activeLayer=1` writes to layer 1 in `MutableLevel`.
- Eyedrop with `activeLayer=1` reads layer 1.
- Mid-stroke toggle does not switch layer for the stroke.

**Verification**: `mvn test -Dtest=TestEditorBgLayer,TestLevelEditorController,TestEditorCommands`

### T3 — Mouse input [parallel after T1]

**Owner**: single agent.
**Files**:
- `src/main/java/com/openggf/control/InputHandler.java`
  (add mouse fields, callbacks, getters)
- `src/main/java/com/openggf/Engine.java`
  (register `glfwSetCursorPosCallback` + `glfwSetMouseButtonCallback` near line 271)
- `src/main/java/com/openggf/editor/EditorInputHandler.java`
  (consume mouse state in `update`, manage stroke lifecycle)
- `src/main/java/com/openggf/editor/commands/StrokeCommand.java` (new)
- `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java`
  (add hover ghost preview)
- `src/test/java/com/openggf/editor/TestEditorMouseStrokeUndo.java` (new)
- `src/test/java/com/openggf/control/TestInputHandlerMouse.java` (new)

**Tests first**:
- Mouse-press → mouse-release with no movement = one history entry.
- Mouse-drag across 3 cells = one history entry; undo restores all 3.
- RMB click invokes eyedrop path.
- Mouse outside viewport: no action fires.
- Keyboard input still works (existing tests must stay green).

**Verification**: `mvn test -Dtest=TestInputHandlerMouse,TestEditorMouseStrokeUndo,TestEditorToggleIntegration`

### T4 — Persistence [parallel after T1]

**Owner**: single agent.
**Files**:
- `src/main/java/com/openggf/editor/persistence/EditorSaveManager.java` (new)
- `src/main/java/com/openggf/editor/persistence/EditorSaveEnvelope.java` (new record)
- `src/main/java/com/openggf/editor/persistence/EditorSavePayload.java` (new record)
- `src/main/java/com/openggf/level/LevelManager.java`
  (call `tryApplyEdits` in `loadZoneAndAct` after baseline established)
- `src/main/java/com/openggf/Engine.java`
  (wire save on `toggleEditorPlaytestMode`, inject save root)
- `src/main/java/com/openggf/editor/EditorInputHandler.java`
  (add `Action.SAVE`, bind to `Ctrl+S`)
- `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java`
  (save state indicator)
- `src/test/java/com/openggf/editor/persistence/TestEditorSaveManager.java` (new)
- `src/test/java/com/openggf/editor/persistence/TestEditorPersistenceRoundTrip.java`
  (new — uses temp dir; no ROM)

**Tests first**:
- Save + read round-trip: payload equal.
- Save + tamper + read: file quarantined, `ApplyResult.QUARANTINED`.
- Save + read with mismatched zone/act: `ApplyResult.MISMATCH`, no apply.
- File missing: `ApplyResult.NONE`, no warning.
- Apply repopulates dirty BitSets so frame-step uploads to GPU.
- Cross-zone isolation: save in (1,0), load (1,1), no apply.

**Verification**: `mvn test -Dtest=TestEditorSaveManager,TestEditorPersistenceRoundTrip,TestEditorToggleIntegration`

### T5 — End-to-end integration test [sequential after T2/T3/T4]

**Owner**: single agent.
**Files**:
- `src/test/java/com/openggf/editor/TestEditorMvpIntegration.java` (new)

**Test cases**:
- BG layer + mouse + persistence: enter editor, toggle to BG layer, mouse-drag a
  stroke, exit (auto-save), tear down `RuntimeManager`, recreate, load same zone,
  verify edit present on layer 1.
- Hash quarantine path end-to-end.
- v0.5-era `TestEditorToggleIntegration` and `TestEditorRenderingSmoke` still green.

**Verification**: `mvn test -Dtest=TestEditor*` (full editor suite)

### Dependencies

```
T1 (prep) ─┬─→ T2 (BG layer) ─┐
           ├─→ T3 (mouse)     ├─→ T5 (integration)
           └─→ T4 (persist)   ┘
```

### Disjoint file ownership

| File | Task |
|---|---|
| `MutableLevel.java` | T1 only |
| `EditorMouseTransform.java` | T1 only |
| `LevelEditorController.java` | T2 only |
| `EditorInputHandler.java` | T2, T3, T4 (split via merge: T2 adds TOGGLE_LAYER, T3 adds mouse hooks, T4 adds SAVE; merge sequentially in that order) |
| `Engine.java` | T3 (mouse callbacks) and T4 (save wiring); merge T3 first |
| `InputHandler.java` | T3 only |
| `StrokeCommand.java` | T3 only |
| `EditorOverlayRenderer.java` | T2 (layer indicator) and T4 (save indicator); merge T2 first |
| `EditorWorldOverlayRenderer.java` | T3 only (hover ghost) |
| `EditorSaveManager.java` + payload records | T4 only |
| `LevelManager.java` | T4 only |

`EditorInputHandler.java`, `Engine.java`, and `EditorOverlayRenderer.java` are
shared between tasks. Sequence them in the order documented; each task should
rebase on `develop` before starting and resolve conflicts during merge into
`feature/ai-editor-mvp-v06`.

### Per-task self-review checklist

- All new tests pass with `mvn test`.
- No JUnit 4 imports.
- No singleton access from object code (CLAUDE.md rule).
- Trailer block present on every commit (`Changelog`, `Guide`, etc., per CLAUDE.md).
- Documentation updated where relevant (`CHANGELOG.md`, `docs/guide/` editor entry).

### Verification command (final)

```
mvn test
```

Expected: all editor tests green, all v0.5 regression suites unaffected, no
TestRomLogic regressions when ROM is present.

---

## Open questions for human review

1. **Layer toggle key**: `L` proposed. Confirm not bound elsewhere in editor input.
2. **Save root path**: confirm `saves/{game}/edits/zone_{zone}_act_{act}.json`
   matches your preference, or use a flatter naming (e.g., `editor_zone_{zone}_
   act_{act}.json`).
3. **Auto-save on editor exit**: proposed default-on. Alternative: prompt the
   operator. Default-on is simpler and matches the "edits don't vanish" goal.
4. **Mouse stroke vs single-click coalescing**: a stationary LMB-down→up creates
   one stroke containing one cell. Confirm this is desired (vs. emitting an
   immediate `PlaceBlockCommand` on press and a no-op `StrokeCommand` on release).
5. **BG layer when layer count < 2**: defensive no-op proposed. Any game module
   ever expected to set `MAP_LAYERS = 1`? (Answer affects whether toggle should
   be hidden entirely on such modules.)
6. **Y-axis sanity**: exploration noted OpenGL ortho2D origin is bottom-left, but
   editor world cursor uses top-left. Spot-verify during T1 implementation.

After human confirmation on the above, implementation can proceed via T1 → T2/T3/T4
in parallel → T5 → end-to-end review → merge to `develop`.
