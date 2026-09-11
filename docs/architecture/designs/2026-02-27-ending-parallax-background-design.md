# DEZ Parallax Background During S2 Ending Cutscene

## Problem

The S2 ending cutscene's sky phases (CAMERA_SCROLL and MAIN_ENDING) should display the DEZ star field background with per-row parallax scrolling. Currently, only a solid sky-blue clear color is rendered. The ROM renders the DEZ background plane (Plane B) with SwScrl_DEZ parallax animation throughout the ending.

## Approach: Reuse LevelManager Rendering Pipeline

DEZ level data (patterns, tilemap, BackgroundRenderer, ParallaxManager with SwScrlDez) **survives** the transition from gameplay to ending cutscene. The game mode changes but no level unload/cleanup occurs. We reuse the existing rendering pipeline rather than building a parallel one.

## ROM Behavior (Cross-Validated Against s2.asm)

### Ending Init (Lines 13084-13096)

| Variable | Value | Line |
|----------|-------|------|
| Camera_X_pos | 0 | 13088 |
| Camera_Y_pos | 0 | 13089 |
| Camera_BG_X_pos | 0 | 13092 |
| Camera_BG_Y_pos | $C8 (200) | 13093 |
| Vscroll_Factor | 0 (cleared) | 13094 |
| VDP Backdrop | Palette 2, color 0 ($8720) | 13021 |
| Plane A | Cleared via DMA fill | 13009 |
| Plane B | **NOT cleared** (preserved from DEZ) | - |
| VDP Plane Size | 64x32 (512x256px) | 13019 |

### SwScrl_DEZ Star Parallax (Lines 17415-17576)

**With camera at (0,0):** Stars still animate. The mechanism is TempArray accumulation via `addq` instructions that increment per-row scroll values each frame regardless of Camera_X_pos:

- Entry 0 (128px empty): set to Camera_X_pos (0, static)
- Entries 1-28 (star rows): `addq.w #speed,(a2)+` accumulates 3,2,4,1,2,4... pixels/frame
- Entries 29-35 (earth/sky): various speeds

The `Horiz_Scroll_Buf` is filled from these accumulated TempArray values, not directly from Camera_X_pos.

### Camera_BG_Y_pos Is NOT Fixed

**Critical finding:** `SetHorizVertiScrollFlagsBG` (line 18324-18327) **modifies** Camera_BG_Y_pos every frame by adding the shifted Y diff:

```
Camera_BG_Y_pos += (Camera_Y_pos_diff << 8)  // 32-bit fixed-point addition
```

- During INIT through CHARACTER_APPEAR: Camera_Y_pos_diff = 0, so Camera_BG_Y_pos stays at $C8
- During CAMERA_SCROLL (routine $C, line 13482): Camera_Y_pos_diff set to $100 each frame, so Camera_BG_Y_pos increments ~1px/frame
- `Vscroll_Factor_BG` is set to Camera_BG_Y_pos every frame (line 17427)

### Palette Fade Reveal

The background "reveal" is purely palette-driven:
- Normal_palette starts all-white ($0EEE) at init (line 13069-13074)
- ObjC9 subtype $A fades palette lines 2-3 from white toward target (Pal_ACDE "Ending Background.bin")
- Fade completes in ~32 frames (7 steps, 4-frame delay each)
- Background tiles use palette lines 2-3, so they become visible as colors fade from white to actual star/sky colors

## Java Engine Design

### Why No Dynamic Tile Loading

The ROM uses `LoadTilesAsYouMove` (called every V-INT via `Vint_Ending`) to dynamically repopulate the 64x32 VDP nametable as scroll crosses 16px boundaries. The Java engine doesn't need this because:

- `TilemapGpuRenderer` pre-builds a full GPU tilemap texture per level
- The parallax shader handles wrapping and per-scanline scroll sampling
- DEZ tilemap data is already in the GPU texture from level load

### Integration Points

**1. EndingProvider interface** — add two default methods:

```java
default boolean needsLevelBackground() { return false; }
default int getBackgroundVscroll() { return 0; }
```

**2. Sonic2EndingProvider** — delegate to cutscene manager:

```java
@Override
public boolean needsLevelBackground() {
    return cutsceneManager.needsLevelBackground();
}

@Override
public int getBackgroundVscroll() {
    return cutsceneManager.getBackgroundVscroll();
}
```

**3. Sonic2EndingCutsceneManager** — expose background state:

- `needsLevelBackground()` returns true during CAMERA_SCROLL and MAIN_ENDING states
- `getBackgroundVscroll()` returns the current background vertical scroll value
- Track `bgYPos` starting at $C8, incrementing during CAMERA_SCROLL per ROM behavior
- Call SwScrlDez.update() each frame during sky phases to accumulate star parallax

**4. LevelManager** — add ending-specific BG render method:

```java
public void renderEndingBackground(int bgVscroll) {
    // Update parallax: camera=(0,0), pass bgVscroll
    parallaxManager.update(currentZone, currentAct, camera, frameCounter, bgVscroll, level);
    // Render BG using existing shader pipeline
    renderBackgroundShader(collisionCommands, bgVscroll);
    frameCounter++;
}
```

**5. Engine.display()** — during ENDING_CUTSCENE, render BG before cutscene sprites:

```java
} else if (getCurrentGameMode() == GameMode.ENDING_CUTSCENE) {
    camera.setX((short) 0);
    camera.setY((short) 0);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        if (provider.needsLevelBackground()) {
            levelManager.renderEndingBackground(provider.getBackgroundVscroll());
        }
        provider.draw();
    }
}
```

### Phase Transitions

| Phase | Background | BG Vscroll |
|-------|-----------|------------|
| INIT → PHOTO_LOAD (photo loop) | None (clear color only) | N/A |
| CHARACTER_APPEAR | None (clear color, palette fading BG lines 2-3) | N/A |
| CAMERA_SCROLL | DEZ star field via LevelManager | Starts $C8, increments ~1px/frame |
| MAIN_ENDING | DEZ star field via LevelManager | Continues incrementing |
| TRIGGER_CREDITS | Handled by credits system | N/A |

No fade transition is needed between CHARACTER_APPEAR and CAMERA_SCROLL. The palette fade (ObjC9 subtype $A) completes during CHARACTER_APPEAR, so by the time CAMERA_SCROLL starts, palette lines 2-3 already contain the correct background colors.

### SwScrlDez Integration During Ending

The existing `SwScrlDez.java` TempArray accumulation system already matches the ROM behavior:
- Star rows accumulate via `addq`-equivalent additions each frame
- `Camera_X_pos = 0` means TempArray[0] = 0 (static), but rows 1-28 still animate
- The scroll buffer filling algorithm handles Vscroll positioning (skips rows based on BG Y position)

The only change needed is to call `parallaxManager.update()` with the correct BG Y scroll value during ending phases, which the new `renderEndingBackground()` method handles.

## Files to Change

| File | Change |
|------|--------|
| `EndingProvider.java` | Add `needsLevelBackground()`, `getBackgroundVscroll()` defaults |
| `Sonic2EndingProvider.java` | Delegate both methods to cutscene manager |
| `Sonic2EndingCutsceneManager.java` | Add `needsLevelBackground()`, `getBackgroundVscroll()`, `bgYPos` tracking, SwScrlDez update calls |
| `LevelManager.java` | Add `renderEndingBackground(int bgVscroll)` |
| `Engine.java` | Call `renderEndingBackground()` during ENDING_CUTSCENE when `needsLevelBackground()` is true |

## Verification

1. `mvn test` — all 1392 tests must pass
2. Visual validation:
   - During photos: no background visible (solid clear color)
   - During CHARACTER_APPEAR: still no background (palette fading)
   - During CAMERA_SCROLL: DEZ star field appears with per-row parallax
   - Stars drift at different speeds per row
   - Background scrolls vertically as bgYPos increments
   - During MAIN_ENDING: stars continue parallax animation behind tornado/clouds/birds
