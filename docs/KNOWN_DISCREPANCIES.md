# Known Discrepancies from Original ROM

This document tracks intentional deviations from the original Sonic 2 ROM implementation. These are cases where we've chosen a different approach for cleaner architecture, better maintainability, or other engineering reasons, while preserving identical runtime behavior.

## Table of Contents

1. [Gloop Sound Toggle](#gloop-sound-toggle)

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
