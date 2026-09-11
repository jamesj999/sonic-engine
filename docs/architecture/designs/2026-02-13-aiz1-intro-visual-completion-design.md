# AIZ1 Intro Visual Completion Design

**Date:** 2026-02-13
**Goal:** Make the AIZ1 intro cinematic visually complete with full ROM parity — all sprites rendered, animated, with correct palettes, camera scrolling, terrain collision, and a proper transition to gameplay.

## Current State

The AIZ1 intro has a 12-routine state machine (`AizPlaneIntroInstance`) with 8 child object classes, physics, art loading (`AizIntroArtLoader`), and palette cycling. All committed and tested. But **every `appendRenderCommands()` is a no-op stub** — objects are invisible. Animation frame cycling, terrain collision, and the intro→gameplay transition are also stubbed.

## Approach: Full Sprint

Wire all rendering, animation, terrain collision, palettes, camera, and the gameplay transition in one pass.

## Work Streams

### 1. Rendering (7 visible objects)

Each object lazily creates a `PatternSpriteRenderer` from its `ObjectSpriteSheet` (provided by `AizIntroArtLoader`). The `appendRenderCommands()` implementation calls `renderer.drawFrameIndex(mappingFrame, x, y, hFlip, vFlip)`.

| Object | Sheet | Notes |
|--------|-------|-------|
| `AizPlaneIntroInstance` | `planeSheet` | Parent orchestrator also renders plane body |
| `AizIntroPlaneChild` | `planeSheet` | Renders plane detail/cockpit pieces |
| `AizIntroWaveChild` | `introSpritesSheet` | Wave animation sprites |
| `AizIntroEmeraldGlowChild` | `emeraldSheet` | Glow effect on plane emeralds |
| `AizEmeraldScatterInstance` | `emeraldSheet` | Scattered emerald sprites |
| `CutsceneKnucklesAiz1Instance` | `knucklesSheet` | Knuckles with DPLC, hFlip for direction |
| `CutsceneKnucklesRockChild` | TBD (may use level art or dedicated sheet) | Breakable rock |

`AizIntroPaletteCycler` has no rendering (palette-only effect).

### 2. Animation

- **Plane:** Frame set by routine logic (frame 0 = normal, angled during descent/swing).
- **Waves:** 3-frame cycle every tick (ROM `byte_67A9B`). Self-deletes offscreen.
- **Knuckles:** ROM animation scripts (walk=0x0666A9, fall=0x0666AF, laugh=0x0666B9) with per-animation frame delay. DPLC selects which tiles to show per frame.
- **Emerald glow:** 3-frame cycle: frames 0→5→6 (ROM `byte_67A97`).
- **Emerald scatter:** Static frame based on subtype (emerald color).

Each object owns `animTimer` + `mappingFrame` updated in `update()`.

### 3. DPLC for Knuckles

`AizIntroArtLoader` already parses DPLC frames. The renderer maps `animationFrame → DPLC entry → tile range` within the 631-tile Knuckles art. This is the most complex rendering piece — different animation frames reference different tile offsets.

### 4. Camera Auto-Scroll

The plane sets the player's X position each frame. Camera follows the focused player sprite. Verify this produces smooth rightward scrolling. May need progressive `camera.setMaxX()` updates to match ROM behavior where `Camera_Max_X_pos` advances with the plane.

### 5. Terrain Collision

Two objects need `TerrainCollisionManager.getFloorDistance()`:

- **Knuckles FALL routine:** Floor detection → `landOnGround()` → transition to STAND.
- **Emerald FALLING phase:** Floor detection → transition to GROUNDED.

Both already have state transition stubs. Wire the collision call in `update()`.

### 6. Palettes

- Load Knuckles palette (32 bytes at 0x066912) to palette line 1 when Knuckles spawns.
- Load emerald palette (32 bytes at 0x067AAA) to palette line 2 at intro start.
- `AizIntroPaletteCycler` handles Super Sonic effect (cycles entries 0x24-0x36 every 6 frames through 10 entries). Already complete.
- All raw data cached by `AizIntroArtLoader`.

### 7. Player Control

Lock input at intro start (routine 0x00), unlock at end (routine 0x1C). The state machine already has control-lock logic — needs `playableSprite.setInputLocked(true/false)` or equivalent wired in.

### 8. Intro → Gameplay Transition

When Sonic X >= 0x13D0 (routine 0x1C completes):

1. **Art overlay:** Apply secondary art/blocks from `Sonic3k.readAiz1GameplayOverlayFromIntroEntry()` at runtime — decompress and replace the intro secondary patterns/blocks with gameplay versions.
2. **Boundaries:** Update level boundaries from intro (minX=0, maxY=0x1000) to gameplay (minX=0x1308, maxY=0x0390).
3. **Title card:** Spawn title card object (or trigger title card sequence).
4. **Controls:** Unlock player input.
5. **Plane:** Deletes itself.
6. **Music:** Start AIZ1 gameplay music if not already playing.

The art swap is the hardest part — it replays what `Sonic3k.loadLevel()` does for skip-intro, but at runtime into the already-loaded level.

## Testing

- Existing headless tests verify state machine transitions (all passing).
- Add test: after transition, level boundaries match gameplay values.
- Add test: art overlay applied flag is set after transition.
- Visual verification against original game for rendering accuracy.

## Files Modified

| File | Change |
|------|--------|
| `AizPlaneIntroInstance.java` | Rendering, animation frame logic |
| `AizIntroPlaneChild.java` | Rendering |
| `AizIntroWaveChild.java` | Rendering, animation cycling |
| `AizIntroEmeraldGlowChild.java` | Rendering, animation cycling |
| `AizEmeraldScatterInstance.java` | Rendering, terrain collision |
| `CutsceneKnucklesAiz1Instance.java` | Rendering, DPLC, animation, terrain collision, palette |
| `CutsceneKnucklesRockChild.java` | Rendering |
| `AizIntroArtLoader.java` | Possible additions for rock art, palette loading helpers |
| `Sonic3kAIZEvents.java` | Camera management during intro, transition orchestration |
| `Sonic3k.java` | Expose art overlay method for runtime use |
| `Sonic3kLevel.java` | Runtime pattern/block replacement API |
