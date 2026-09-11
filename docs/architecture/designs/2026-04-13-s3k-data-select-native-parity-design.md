# S3K Data Select Native Parity Design

## Goal

Bring the native Sonic 3 & Knuckles Data Select screen to disassembly-backed parity before continuing any cross-game donation work.

This design supersedes the current assumption that the existing S3K presentation is "working enough." It is not. The current branch still uses placeholder behavior in the native S3K screen, including a synthetic cursor overlay, incorrect background/layout placement, and missing menu movement sound effects.

The source of truth for this work is the Sonic 3 & Knuckles disassembly and the ROM-backed assets under `docs/skdisasm/General/Save Menu/`.

## Non-Goals

- Do not continue S1/S2 donated Data Select work until native S3K is visually and behaviorally correct enough to serve as the donor presentation.
- Do not invent replacement visuals, wireframes, placeholder cards, or generic menu UX.
- Do not redesign the save backend. Keep the existing `SaveManager`, `SaveSessionContext`, slot scanning, hash handling, and snapshot-provider pipeline unless parity work reveals a direct bug there.

## Current Problems

The current native S3K presentation is structurally incomplete:

- The save-screen background and authored elements are not being positioned according to the original object/layout flow.
- The selector is still represented by a placeholder `RECTI` overlay rather than the real `Obj_SaveScreen_Selector` behavior and mappings.
- Menu navigation does not play the original movement SFX.
- The frontend is still composed from partial tilemaps and synthetic render decisions instead of mirroring the original save-screen object responsibilities.

This makes the screen unsuitable as a native implementation and therefore unsuitable as the donor screen for any other game.

## Original Reference Points

The following disassembly sections define the native S3K save screen:

- `ObjDat_SaveScreen` in `docs/skdisasm/sonic3k.asm`
- `Obj_SaveScreen_Selector`
- `Obj_SaveScreen_NoSave_Slot`
- `Obj_SaveScreen_Save_Slot`
- `Obj_SaveScreen_Emeralds`
- `Obj_SaveScreen_Delete_Save`
- `Map_SaveScreen` and `General/Save Menu/Map - Save Screen General.asm`
- Save menu palettes, Enigma layouts, uncompressed slot maps, and Kosinski art under `docs/skdisasm/General/Save Menu/`

Important authored positions from `ObjDat_SaveScreen`:

- Title text: `x=$120`, `y=$14C`
- Selector: `x=$120`, `y=$E2`
- Delete icon: `x=$448`, `y=$D8`
- No-save slot: `x=$B0`, `y=$C8`
- Save slots 1-8:
  - slot 1: `x=$110`, `y=$108`
  - slot 2: `x=$178`, `y=$108`
  - slot 3: `x=$1E0`, `y=$108`
  - slot 4: `x=$248`, `y=$108`
  - slot 5: `x=$2B0`, `y=$108`
  - slot 6: `x=$318`, `y=$108`
  - slot 7: `x=$380`, `y=$108`
  - slot 8: `x=$3E8`, `y=$108`

These values are authored scene data and should be treated as authoritative, not approximated through ad hoc layout math.

## Architecture

### Keep

Retain the following backend and host-owned systems:

- `SaveManager`
- `SaveSessionContext`
- `DataSelectSessionController`
- `DataSelectHostProfile`
- save slot serialization and validation flow
- active-slot launch and save routing

These systems represent the engine/backend layer and remain valid for native S3K parity work.

### Replace

Replace the current S3K presentation abstraction with a native save-screen scene built around the original object responsibilities.

The native frontend should be decomposed into:

- `S3kDataSelectScene`
  - owns lifecycle, input processing, update ordering, and scene-level state
- `S3kSaveScreenObjectState`
  - stores the live state for selector, no-save slot, delete icon, eight save slots, and emerald child objects
- `S3kDataSelectRenderer`
  - draws authored objects using the real mappings, art, and palettes

The current `RenderModel`-style approach is insufficient for parity because the original screen is not a static arrangement of cards. It is an authored object scene with per-object update logic, frame changes, child objects, and sound-side effects.

## Object Responsibilities

### Selector

Port `Obj_SaveScreen_Selector` as its own authored object/state machine.

Required parity points:

- initial selector state derives from `Dataselect_entry`
- horizontal selector travel follows the original stepping logic
- mapping frame changes depend on x-range as in the original
- camera/scroll offset behavior mirrors the original save-screen movement
- left/right navigation uses the original SFX selection:
  - `sfx_SlotMachine`
  - `sfx_SmallBumpers` when in the alternate mode used by the original

The selector must not be represented by a generic rectangle, debug primitive, or arbitrary overlay.

### No Save Slot

Port `Obj_SaveScreen_NoSave_Slot` behavior.

Required parity points:

- team/player cycling follows the authored behavior used on the no-save entry
- confirm path initializes a no-save run using the existing backend/session path
- no-save visuals use the original mappings/art, not text-driven labels

### Save Slots

Port `Obj_SaveScreen_Save_Slot` behavior.

Required parity points:

- each slot is initialized from its authored slot number and save payload
- occupied slot visuals choose icons/presentation from the original authored logic
- clear-slot restart restrictions still come from the host/backend profile, but the visible S3K slot behavior must match the original native presentation model
- slot-local visual state should mirror the original frame/icon handling rather than selecting one of a few synthetic card tilemaps

### Emerald Child Object

Port `Obj_SaveScreen_Emeralds`.

Required parity points:

- emerald display is driven as a child/display element of the slot path
- emerald count/state comes from the existing save payload/backend interpretation
- visuals use the real authored mappings/art/palette path

### Delete Icon

Port `Obj_SaveScreen_Delete_Save`.

Required parity points:

- delete mode behavior matches the original S3K screen flow as closely as possible while still using the engine backend
- delete-related SFX must come from the original save-screen interaction points, not synthetic menu code

## Rendering

### Background And Layout

The background must be rendered using the real save-screen/menu assets and their authored placement.

Requirements:

- use the real Enigma layout and menu background assets from the S3K ROM
- use the save-screen authored placements from the disassembly
- do not place the background by guesswork or by mixing screen-space and world-space arbitrarily
- do not synthesize card backgrounds as the primary slot presentation

### Authored Objects

All visible interactive elements must render as authored mapped objects using `Map_SaveScreen`.

Requirements:

- title text uses real mappings
- selector uses real mappings and frame changes
- no-save slot uses real mappings
- delete icon uses real mappings
- save slots use real mappings and original visual states
- emerald display uses real mappings

### Placeholder Rendering Ban

The following are explicitly disallowed in the native S3K production path:

- `PixelFontTextRenderer` as the main Data Select presentation
- `GLCommand.RECTI` cursor/selection rectangles
- synthetic debug labels for slots, teams, or states as the primary UI
- custom card framing that replaces the authored save-screen visuals

If any of these remain visible in native S3K Data Select, parity is not achieved.

## Input And Sound

Input behavior must follow the original authored save-screen flow rather than the prior generic menu abstraction.

Required parity points:

- selector movement uses the original direction semantics and timing expectations
- no-save team cycling mirrors the original no-save object behavior
- clear-slot destination cycling remains supported, but must be integrated into the real S3K visual flow instead of a generic menu abstraction
- left/right movement and relevant interaction points play the original SFX from the same decision points as the disassembly

At minimum, the branch must implement the original movement SFX behavior before the screen can be considered working.

## Integration

The native S3K screen must continue to integrate with the existing backend/session flow:

- entering from S3K title screen `1 PLAYER`
- launching `NO SAVE`
- launching a fresh slot
- loading an occupied slot
- starting a clear-slot restart
- deleting a slot

The backend remains engine-owned; the frontend becomes native S3K-authored.

## Priority Order

Implement native parity in this order:

1. Selector parity
   - real selector mappings
   - real movement behavior
   - real movement SFX
   - remove yellow rectangle/placeholder overlay

2. Screen composition parity
   - correct background/layout placement
   - title/no-save/delete/slot positioning from `ObjDat_SaveScreen`
   - correct layering

3. Slot visual parity
   - empty slot
   - occupied slot
   - clear slot
   - emerald display

4. Interaction parity
   - no-save team cycling
   - clear-slot restricted restart interaction
   - delete flow
   - confirm/start behavior

5. Only after native S3K parity is acceptable
   - resume cross-game donation work

## Testing

Add and maintain tests for:

- native S3K provider no longer using placeholder cursor rendering
- selector movement SFX dispatch
- authored object positions for selector/no-save/delete/slots
- asset-loader backed rendering inputs for `Map_SaveScreen`
- no-save / slot / clear / delete flows still reaching the existing backend actions

Testing alone is not sufficient here. Manual visual validation against the original S3K screen is required before resuming donation work.

## Success Criteria

The native S3K Data Select screen is considered working only when:

- the visible screen no longer contains placeholder rectangles or text-first UI
- the background and authored objects are placed correctly
- the selector is the real S3K selector behavior and visuals
- movement uses the correct SFX
- slot/no-save/delete behavior remains backend-correct
- the screen is credible enough to serve as the donor presentation for S1/S2 later

Until then, cross-game donation remains blocked.
