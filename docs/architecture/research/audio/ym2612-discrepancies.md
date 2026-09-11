# YM2612 Implementation Discrepancies

This document tracks discrepancies between the Java YM2612 implementation (`Ym2612Chip.java`) and the Genesis Plus GX reference (`ym2612.c`).

## Overview

The Java port aims for accuracy against GPGX but has several bugs affecting audio output. Issues are categorized by severity.

---

## 🔴 Critical Bugs

### 1. Algorithm 5 MEM Feed Swapped (MAJOR)

**Status:** Validated, Fixed (2026-01-25)  
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 1529-1637, `doAlgo()`)  
**Reference:** `docs/gensplusgx/ym2612.c` (lines 865-950 `setup_connection()`, lines 1436-1508 `chan_calc()`)

**Description:**  
Validation against GPGX shows the envelope/phase ordering is consistent and algorithms 0-4, 6-7 match. The mismatch is limited to algorithm 5: SLOT2 (Op2) should be modulated by the current SLOT1 output, while SLOT3 (Op3) should be modulated by MEM (previous SLOT1). The Java `doAlgo()` used MEM for SLOT2 and SLOT1 for SLOT3.

**Impact:**  
- Affects algorithm 5 voices (common modulator)
- Audible differences for instruments using algorithm 5

**Fix:**  
Swap the modulation inputs in `doAlgo()` case 5 (and the `computeModulationInputWithMem` test helper).
---

### 2. EG Counter Reset Value Wrong

**Status:** Fixed (2026-01-25)
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 581-584)  
**Reference:** `docs/gensplusgx/ym2612.c` (lines 1916-1918)

**Description:**  
On reset, Java sets `egCnt = 1` but GPGX sets `eg_cnt = 0`.

The EG increment table (`EG_INC`) is periodic over 8 sub-steps. Starting at the wrong phase shifts the entire envelope stepping pattern.

**Impact:**  
- Attack/decay microstepping differs from hardware
- Audible on percussive and bright instruments

**Fix:**
```java
// Current:
egCnt = 1;

// Correct:
egCnt = 0;
```

---

### 3. LFO Counter Not Reset on Disable

**Status:** Fixed (2026-01-25)
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 793-801)  
**Reference:** `docs/gensplusgx/ym2612.c` (lines 1532-1538)

**Description:**  
When LFO is disabled (register 0x22 bit 3 = 0), GPGX resets `lfo_cnt = 0`. Java only resets `lfoTimer`, `lfoAm`, and `lfoPm` but not `lfoCnt`.

**Impact:**  
- Re-enabling LFO resumes from incorrect phase
- Vibrato/tremolo effects start at wrong position

**Fix:**
```java
} else {
    lfoTimerOverflow = 0;
    lfoTimer = 0;
    lfoCnt = 0;  // ADD THIS LINE
    lfoAm = 126;
    lfoPm = 0;
}
```

---

### 4. DAC Latch Cleared on Disable

**Status:** Fixed (2026-01-25)
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 848-852, 1735-1739)  
**Reference:** `docs/gensplusgx/ym2612.c` (lines 1982-1985)

**Description:**  
When DAC is disabled (register 0x2B bit 7 = 0), Java calls `stopDac()` which clears `dacHasLatched = false`. GPGX never clears the DAC latch value—only the enable flag changes.

**Impact:**  
- If software writes DAC values while disabled, then enables DAC, the last value should output immediately
- Java outputs 0 until a new DAC write arrives

**Fix:**  
Split "stop sample playback" from "clear DAC latch":
```java
case 0x2B:
    dacEnabled = (val & 0x80) != 0;
    if (!dacEnabled) {
        // Stop sample playback but preserve latch
        currentDacSampleId = -1;
        dacPos = 0;
        // DO NOT clear dacHasLatched
    }
    break;
```

---

## 🟡 Minor Discrepancies

### 5. Decay→Sustain Volume Clamping

**Status:** Fixed (2026-01-25)
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 1208-1214)  
**Reference:** `docs/gensplusgx/ym2612.c` (lines 1131-1134)

**Description:**  
Java clamped volume to sustain level on transition (pre-fix):
```java
if (sl.volume >= sustainLevel) {
    sl.volume = sustainLevel;  // GPGX does not clamp
    sl.curEnv = EnvState.DECAY2;
}
```

GPGX only changes state when threshold is crossed; it does not clamp the volume value.

**Impact:**  
- Subtle amplitude difference at sustain transition
- May affect steady-state amplitude

**Fix:**
```java
if (sl.volume >= sustainLevel) {
    sl.curEnv = EnvState.DECAY2;
}
```

---

### 6. Unused FINC_TAB Table

**Status:** Validated (benign)
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 347-353)

**Description:**  
`FINC_TAB` is built during static initialization but never used. Frequency increment is computed directly via `(fc * mul) >> 1`.

**Impact:** None (dead code)

---

### 7. DAC Interpolation and Highpass

**Status:** Intentional divergence
**Files:** `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (lines 1752-1778)

**Description:**  
Java adds optional linear interpolation and highpass filtering for DAC output. GPGX DAC is a simple latch with no processing.

**Impact:**  
- Smoother DAC playback (intentional)
- Not bit-accurate to reference

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | Algorithm routing (§1) | High | High |
| 2 | EG counter reset (§2) | Low | Medium |
| 3 | LFO counter reset (§3) | Low | Medium |
| 4 | DAC latch semantics (§4) | Low | Low |
| 5 | Decay clamping (§5) | Low | Low |

---

## Testing Recommendations

1. **Algorithm bug:** Compare output of all 8 algorithms against GPGX reference recordings. Focus on instruments using algorithms 0-3 which use the MEM path.
2. **EG counter bug:** Test percussive instruments with fast attack; compare envelope shape
3. **LFO bug:** Test vibrato instruments; disable/re-enable LFO mid-note
4. **DAC bug:** Write DAC values while disabled, then enable; verify immediate output

---

## Related Files Analysis

### SmpsSequencer.java - No Issues Found

The sequencer correctly handles operator ordering:

1. **ALGO_OUT_MASK conversion** (lines 109-127): Correctly converts slot masks to SMPS order
2. **updateFmTotalLevel** (lines 1430-1458): Uses correct operator mapping `int[] tlIdx = { 21, 23, 22, 24 }` and writes to `0x40 + (op * 4) + ch`
3. **Key On/Off** (lines 1272-1296): Writes `0x28` with correct channel encoding `chVal = (port == 0) ? ch : (ch + 4)`

The sequencer writes correct register addresses; the bug is in Ym2612Chip's interpretation of those addresses.

### SmpsDriver.java - No Issues Found

The driver correctly:
1. Maps register 0x28 channel values (lines 155-166)
2. Passes through FM writes without operator remapping

### Ym2612Chip.java - Register-to-Operator Mapping is Correct

The `writeSlot()` function (lines 877-956) correctly maps register slots to internal operators:
```java
int[] regToOp = { 0, 2, 1, 3 };  // Slot 0→op0, Slot 1→op2, Slot 2→op1, Slot 3→op3
int regSlot = (addr >> 2) & 3;
int opIdx = regToOp[regSlot];
```

This matches GPGX's `OPN_SLOT(r)` and slot definitions:
```c
#define SLOT1 0
#define SLOT2 2
#define SLOT3 1
#define SLOT4 3
```

**The bug is isolated to:**
1. `doAlgo()` - algorithm 5 modulation inputs (MEM vs SLOT1)
2. `computeModulationInputWithMem()` - test helper case 5 mapping

---

## Verification Notes

### Key Mapping Analysis (Verified Correct)

The key on/off mapping in `writeYm()` was initially suspected but is actually **correct**:

```java
// Java writeYm() (Ym2612Chip.java:818-842):
if ((mask & 1) != 0) keyOn(ch, 0); // SLOT1 → ops[0] ✓
if ((mask & 2) != 0) keyOn(ch, 2); // SLOT2 → ops[2] ✓
if ((mask & 4) != 0) keyOn(ch, 1); // SLOT3 → ops[1] ✓
if ((mask & 8) != 0) keyOn(ch, 3); // SLOT4 → ops[3] ✓
```

This correctly maps to the `regToOp = {0, 2, 1, 3}` layout where:
- `ops[0]` = SLOT1, `ops[1]` = SLOT3, `ops[2]` = SLOT2, `ops[3]` = SLOT4

### Algorithm 5 MEM Feed - Confirmed

Validation against GPGX shows the envelope/phase ordering in `renderChannel()` is consistent. The mismatch was limited to algorithm 5: SLOT2 should be modulated by current SLOT1 output while SLOT3 should be modulated by MEM (previous SLOT1). This is fixed in `doAlgo()` and `computeModulationInputWithMem`.

---

## References

- Genesis Plus GX source: https://github.com/ekeeke/Genesis-Plus-GX
- YM2612 die analysis by Sauraen: http://gendev.spritesmind.net/forum/viewtopic.php?t=386
- Nemesis hardware tests: Referenced throughout GPGX changelog
