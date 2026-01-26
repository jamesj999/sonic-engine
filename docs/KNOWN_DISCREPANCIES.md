# Known Discrepancies from Original ROM

This document tracks intentional deviations from the original Sonic 2 ROM implementation. These are cases where we've chosen a different approach for cleaner architecture, better maintainability, or other engineering reasons, while preserving identical runtime behavior.

## Table of Contents

1. [Gloop Sound Toggle](#gloop-sound-toggle)
2. [Spindash Release Transpose Fix](#spindash-release-transpose-fix)
3. [Pattern ID Ranges for GUI/Results Screen](#pattern-id-ranges-for-guiresults-screen)

---

## Gloop Sound Toggle

**Location:** `BlueBallsObjectInstance.java`
**ROM Reference:** `s2.sounddriver.asm` lines 2142-2149

### Original Implementation

The ROM implements the Gloop sound toggle in the Z80 sound driver itself:

```asm
zPlaySound_CheckGloop:
    cp    SndID_Gloop           ; Is this the gloop sound?
    jr    nz,zPlaySound_CheckSpindash
    ld    a,(zGloopFlag)
    cpl                         ; Toggle the flag
    ld    (zGloopFlag),a
    or    a
    ret   z                     ; Return WITHOUT playing if flag is 0
    jp    zPlaySound            ; Only play every other call
```

This hardcodes a specific sound ID check into the driver, causing the Gloop sound to only play every other time it's requested.

### Our Implementation

We implement the toggle in `BlueBallsObjectInstance.playGloopSound()` instead:

```java
private static boolean gloopToggle = false;

private void playGloopSound() {
    if (!isOnScreen()) {
        return;
    }
    // Toggle flag - only play every other call (ROM: zGloopFlag)
    gloopToggle = !gloopToggle;
    if (!gloopToggle) {
        return;
    }
    AudioManager.getInstance().playSfx(SND_ID_GLOOP);
}
```

### Rationale

1. **Gloop is exclusively used by BlueBalls** - A search of the disassembly confirms `SndID_Gloop` (0xDA) is only referenced in `Obj1D` (BlueBalls). No other object uses this sound.

2. **Keeps SMPS driver generic** - Hardcoding sound-specific behavior in the driver would make it less reusable and harder to maintain. The driver should ideally just play what it's told.

3. **Encapsulates behavior** - The toggle is really a BlueBalls-specific feature to prevent sound spam when multiple balls are active. Keeping it in the object makes the relationship explicit.

4. **Identical runtime behavior** - The end result is the same: Gloop plays every other call, preventing audio spam from staggered sibling balls.

### Verification

Both implementations result in the Gloop sound playing at 50% frequency, which prevents overwhelming audio when multiple BlueBalls objects are bouncing with staggered timers.

---

## Spindash Release Transpose Fix

**Location:** `Sonic2SfxData.java`
**ROM Reference:** `docs/s2disasm/sound/sfx/BC - Spin Dash Release.asm`

### Original Implementation

The ROM SFX header for Spindash Release (0xBC) uses an invalid transpose value for FM5:

```asm
    smpsHeaderSFXChannel cFM5, Sound3C_SpindashRelease_FM5, $90, $00
```

This value is called out in the disasm as a bug. Some SMPS drivers interpret `$90` as a large negative transpose, which can underflow the note calculation and skip the initial FM burst.

### Our Implementation

We patch only this invalid FM transpose value when parsing SFX headers:

```java
int transpose = (byte) data[pos + 4];
if ((channelId & 0x80) == 0 && transpose == (byte) 0x90) {
    transpose = 0x10;
}
```

### Rationale

1. **Targets a known bad data value** - The disasm explicitly documents the `$90` transpose as invalid for this SFX.
2. **Preserves other SFX behavior** - We do not mask or normalize all transposes, only this exact FM case.
3. **Improves fidelity** - Restores the missing initial FM burst for 0xBC that is audible in hardware/driver-correct playback.

### Verification

Spindash Release now includes the initial FM5 hit before the delayed PSG noise, matching expected playback.

---

## Pattern ID Ranges for GUI/Results Screen

**Location:** `LevelManager.java`, `ObjectRenderManager.java`, `PatternAtlas.java`
**ROM Reference:** VDP VRAM tile management

### Original Implementation

The Mega Drive VDP has limited VRAM (~64KB), so the original game dynamically loads and overwrites pattern data. When displaying the results screen after completing an act, the game overwrites level tile patterns that are no longer needed with results screen graphics (score tallies, continue icons, etc.). Pattern indices directly correspond to VRAM tile addresses (0x0000-0x07FF typical range).

From `s2.asm`, results screen art is loaded into VRAM locations previously used by level tiles:
```asm
; Load results screen patterns, overwriting level data
lea     (ArtNem_TitleCard).l,a0
lea     (vdp_control_port).l,a4
move.w  #tiles_to_bytes(ArtTile_Title_Card),d0
```

### Our Implementation

We use **extended pattern ID ranges** that don't overlap with level tile indices:

| Category | Pattern ID Range | Notes |
|----------|------------------|-------|
| Level tiles | 0 - ~2047 | Corresponds to VRAM tile indices |
| Objects, HUD, Results | 0x20000+ | Far above VRAM range, no collision |

```java
// LevelManager.java
private static final int OBJECT_PATTERN_BASE = 0x20000;

// ObjectRenderManager caches patterns starting at this base
int hudBaseIndex = objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
```

The `PatternAtlas` stores all patterns in a single HashMap keyed by pattern ID. By using IDs starting at `OBJECT_PATTERN_BASE` (0x20000 = 131072), we ensure they never collide with level patterns.

### Rationale

1. **Level patterns remain cached** - No need to reload level tiles after results screen, enabling instant transitions.

2. **Simpler state management** - No need to track which tiles were overwritten or restore them later.

3. **Easier debugging** - Level and UI patterns coexist without interference; inspecting the atlas shows all patterns.

4. **No VRAM constraints** - Modern systems have abundant texture memory; emulating the 64KB limit adds complexity with no benefit.

### Verification

The rendered output is identical to the original - the same graphics appear at the same screen positions. Only the internal storage differs.
