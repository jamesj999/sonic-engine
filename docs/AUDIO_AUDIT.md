# Audio Implementation Audit Report

**Date:** January 16, 2026  
**Scope:** YM2612, SN76489 (PSG), SMPS Sequencer, SMPS Driver  
**Reference Implementations:**
- `docs/s2disasm/s2.sounddriver.asm` (Sonic 2 Z80 SMPS Driver)
- `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (SMPSPlay Reference)
- `docs/SMPS-rips/SMPSPlay/libs/download/libvgm/emu/cores/ym2612.c` (libvgm YM2612)
- `docs/SMPS-rips/SMPSPlay/libs/download/libvgm/emu/cores/sn76489.c` (libvgm PSG)
- `docs/YM2612.java.example` (Gens YM2612 Java port by Stephan Dittrich)

---

## Cross-Reference: Features Available in YM2612.java.example

The project includes `docs/YM2612.java.example`, a Java port of the Gens emulator's YM2612 core. Many issues identified in this audit **already have reference implementations** in that file:

| Issue | Available in YM2612.java.example? | Notes |
|-------|-----------------------------------|-------|
| **Integer table generation** | ✅ YES | Uses same `StrictMath` approach but identical structure |
| **KEY_ON/KEY_OFF** | ✅ YES | Lines 257-285 with `DECAY_TO_ATTACK` handling |
| **CSM_Key_Control** | ✅ YES | Line 287-293 |
| **setSlot/setChannel** | ✅ YES | Lines 295-445 with full register handling |
| **AMS/FMS mapping** | ✅ YES | Uses `LFO_AMS_TAB` correctly at line 432 |
| **Timer A/B** | ✅ YES | Lines 467-499 with proper reload logic |
| **SSG-EG** | ⚠️ PARTIAL | Has `EnableSSGEG` flag but may be incomplete |
| **Algorithm routing** | ✅ YES | All 8 algorithms implemented |
| **Envelope states** | ✅ YES | ATTACK/DECAY/SUSTAIN/RELEASE enum |
| **S0/S1/S2/S3 slot order** | ✅ YES | Constants at lines 144-147 |

**Recommendation:** Port missing functionality from `YM2612.java.example` to `Ym2612Chip.java` rather than implementing from scratch.

---

## Cross-Reference: SMPS Flags Not Relevant for Sonic 2

Analysis of `s2.sounddriver.asm` coordination flag table (lines 2853-3001) shows which flags are **actually used** by Sonic 2:

| Flag | Name in S2 Driver | Used in Sonic 2? | Notes |
|------|-------------------|------------------|-------|
| `ED` | `cfClearPush` | ❌ **UNUSED** | Remnant from Sonic 1, does nothing (just `ret`) |
| `EE` | `cfStopSpecialFM4` | ❌ **UNUSED** | Remnant from Sonic 1, does nothing (`dec hl; ret`) |
| `FA-FF` | N/A | ❌ **NOT IN TABLE** | Sonic 2 flag table ends at F9 |

**Updated Assessment:**
- **ED, EE are NOT needed** — They exist in the jump table but are NOPs in Sonic 2
- **FA-FF are NOT needed** — Sonic 2's coordination flag table only covers E0-F9
- All originally flagged "missing" features are actually **not relevant** for Sonic 2 accuracy

---

## Executive Summary

The audio implementation is **structurally modeled** on libvgm/SMPSPlay but contains multiple high-risk accuracy issues that will prevent sample-perfect matching against the original hardware/driver:

1. **Clocking/Region handling is incomplete** — NTSC clocks are hardcoded
2. **YM2612/PSG tables are regenerated via floating-point math** — Not bit-identical to reference
3. **SMPS behaviors are subtly different** — Tempo/tick timing, note-fill/gating order, volume application timing

**Good News After Cross-Reference Analysis:**
- **Most SMPS coordination flags are already implemented** — 24 of 26 S2 flags work
- **ED/EE flags are NOPs in Sonic 2** — No implementation needed
- **FA-FF flags don't exist in S2** — Previously flagged as missing but not applicable
- **YM2612.java.example provides reference code** — Can port features directly

**Revised Issue Count:**
- ~~11 medium issues~~ → **5 remaining medium issues** (after removing non-applicable flags)
- **3 critical issues remain** (clocks, tables, tempo drift)

**Estimated Effort to Fix:** Medium (1 day for critical issues using existing YM2612.java.example as reference)

---

## Table of Contents

1. [YM2612 Issues](#1-ym2612-issues-ym2612chipjava)
2. [PSG/SN76489 Issues](#2-psgsn76489-issues-psgchipjava)
3. [SMPS Sequencer Issues](#3-smps-sequencer-issues-smpssequencerjava)
4. [SMPS Driver Issues](#4-smps-driver-issues-smpsdriverjava)
5. [Missing Features](#5-missing-features)
6. [Recommended Priority Fixes](#6-recommended-priority-fixes)
7. [Testing Recommendations](#7-testing-recommendations)
8. [Appendix: Coordination Flag Coverage](#appendix-coordination-flag-coverage)

---

## 1. YM2612 Issues (`Ym2612Chip.java`)

### 1.1 CRITICAL: Table Generation via Floating-Point Math

**Location:** Lines 103-223  
**Severity:** HIGH  

**Problem:**  
Tables are generated using `StrictMath.sin()`, `StrictMath.pow()`, `StrictMath.log10()` and `(int)` casts:

```java
// TL_TAB generation
x /= StrictMath.pow(10, (ENV_STEP * i) / 20);
TL_TAB[i] = (int) x;

// SIN_TAB generation  
x = StrictMath.sin(2.0 * StrictMath.PI * i / SIN_LEN);
x = 20 * StrictMath.log10(1.0 / x);
```

Even tiny cross-language differences in `sin/log10/pow` + rounding (C libm vs Java StrictMath/fdlibm) will produce **different integer tables**, which will **cascade into different PCM output**.

**Impact:** Every sample will be slightly wrong. Accumulates over time.

**Fix:**  
Copy the **exact integer tables** from libvgm's `ym2612.c` or port the original table-generation code with identical rounding. For sample-perfect output, table determinism must match the reference exactly.

**Affected Tables:**
- `TL_TAB[TL_LEN * 2]`
- `SIN_TAB[SIN_LEN]`
- `ENV_TAB[2 * ENV_LEN + 8]`
- `DECAY_TO_ATTACK[ENV_LEN]`
- `FINC_TAB[2048]`
- `AR_TAB[128]`
- `DR_TAB[96]`
- `DT_TAB[8][32]`
- `LFO_ENV_TAB[LFO_LEN]`
- `LFO_FREQ_TAB[LFO_LEN]`
- `LFO_INC_TAB[8]`

---

### 1.2 CRITICAL: Hardcoded NTSC Clock

**Location:** Line 11  
**Severity:** HIGH  

**Problem:**
```java
private static final double CLOCK = 7670453.0;
```

This is the NTSC Mega Drive YM2612 clock. PAL systems use a different clock (~7600489 Hz). If your engine supports PAL tempo adjustment but keeps NTSC chip clocks, you'll get pitch/timbre mismatch vs real hardware.

**Impact:** All frequencies will be wrong on PAL, causing pitch errors.

**Fix:**  
Make YM2612 clock region-dependent:
```java
// NTSC: 7670453 Hz (53.693175 MHz / 7)
// PAL:  7600489 Hz (53.203424 MHz / 7)
private double clock;

public void setRegion(Region region) {
    this.clock = (region == Region.PAL) ? 7600489.0 : 7670453.0;
    recalculateTables(); // If tables depend on clock
}
```

---

### 1.3 MEDIUM: AMS Shift Mapping Verification

**Location:** Lines 89, 587-591  
**Severity:** MEDIUM  

**Problem:**  
The code uses:
```java
private static final int[] LFO_AMS_TAB = {31, 4, 1, 0};
```

And later:
```java
env += (envLfo >> sl.ams);
```

In libvgm, the register value (0–3) is mapped through `LFO_AMS_TAB` and then the code shifts by the **mapped value**. If `sl.ams` stores raw `0..3` instead of `{31,4,1,0}`, AMS will be wildly wrong.

**Verification Needed:**  
Confirm in `writeChannel()` at line 587:
```java
ch.ams = LFO_AMS_TAB[(val >> 4) & 3];
```
This appears correct, but verify `sl.ams` assignment in `writeSlot()` at line 521:
```java
sl.ams = sl.amsOn ? ch.ams : 31;
```
This also looks correct (inherits mapped value or uses 31 for disabled).

**Status:** Likely OK, but add unit test to verify.

---

### 1.4 MEDIUM: SSG-EG Implementation

**Location:** Lines 537-539  
**Severity:** MEDIUM  

**Problem:**
```java
sl.ssgEg = val & 0x0F;
sl.ssgEnabled = (val & 0x08) != 0;
```

SSG-EG is parsed but the envelope inversion/looping behavior in the synthesis loop must match libvgm's `Env_Substain_Next()` function exactly. In libvgm:

```c
static void Env_Substain_Next(ym2612_ *YM2612, slot_ *SL)
{
  if (YM2612->Enable_SSGEG)
  {
    if (SL->SEG & 8)    // SSG envelope type
    {
      if (SL->SEG & 1)
      {
        SL->Ecnt = ENV_END;
        SL->Einc = 0;
        SL->Ecmp = ENV_END + 1;
      }
      else
      {
        // re KEY ON
        SL->Ecnt = 0;
        SL->Einc = SL->EincA;
        SL->Ecmp = ENV_DECAY;
        SL->Ecurp = ATTACK;
      }
      SL->SEG ^= (SL->SEG & 2) << 1;  // Toggle bit 2 based on bit 1
    }
    // ...
  }
}
```

**Verification Needed:**  
Your `updateEnvelope()` method must implement:
1. Envelope inversion when `(ssgEg & 4) != 0`
2. Looping/alternating behavior based on bits 0-2
3. The toggle logic `SEG ^= (SEG & 2) << 1`

---

### 1.5 MEDIUM: Timer Tick Domain

**Location:** Lines 1088-1111  
**Severity:** MEDIUM  

**Problem:**
```java
private void tickTimers(int samples) {
    if (samples <= 0) return;
    int ticks = TIMER_BASE_INT * samples;
    // ...
}
```

Timers must advance in the **YM clock domain** (scaled), not 1:1 per audio sample unless properly scaled. The relationship is:
- YM2612 internal clock = Master clock / 6
- Timer A period = 18 * (1024 - TIMA) cycles
- Timer B period = 288 * (256 - TIMB) cycles

Verify `TIMER_BASE_INT` calculation:
```java
private static final int TIMER_BASE_INT = (int) (YM2612_FREQUENCY * 4096.0);
```

This may not match libvgm's timer prescaling exactly.

**Fix:**  
Compare timer overflow timing against libvgm's implementation and verify CSM mode triggers at the correct sample.

---

### 1.6 MEDIUM: CSM Mode and Mode Register Handling

**Location:** Lines 451-464  
**Severity:** MEDIUM  

**Problem:**  
In libvgm, writing the mode register can trigger recalculations:
```c
if ((data ^ YM2612->Mode) & 0x40)
{
  YM2612_Special_Update(YM2612);
  YM2612->CHANNEL[2].SLOT[0].Finc = -1;  // recalculate phase step
}
```

Your code does set `ch.ops[0].fInc = -1` but verify the timing of `csmKeyControl()` call:
```java
if ((val & 0x80) != 0) {
    csmKeyControl();  // This triggers key-on for CH3 slots
}
```

libvgm triggers CSM on **Timer A overflow**, not on mode register write. Your code at line 1096:
```java
if ((mode & 0x80) != 0) {
    csmKeyControl();
}
```
This is in `tickTimers()` which is correct, but verify the mode register write at line 461-463 doesn't also trigger it incorrectly.

---

### 1.7 LOW: ENV_CUT_OFF Missing

**Location:** N/A  
**Severity:** LOW  

**Problem:**  
libvgm defines:
```c
#define PG_CUT_OFF   ((int) (78.0 / ENV_STEP))
#define ENV_CUT_OFF  ((int) (68.0 / ENV_STEP))
```

Your code only has `PG_CUT_OFF`. The `ENV_CUT_OFF` is used to clamp very quiet envelope output to zero for correct behavior and performance.

**Fix:**  
Add `ENV_CUT_OFF` and apply it where libvgm does.

---

### 1.8 MEDIUM: DAC Timing Accuracy

**Location:** Lines 232-236  
**Severity:** MEDIUM  

**Problem:**
```java
private static final double DAC_BASE_CYCLES = 288.0;
private static final double DAC_LOOP_CYCLES = 26.0;
private static final double DAC_LOOP_SAMPLES = 2.0;
private static final double Z80_CLOCK = 3579545.0;
```

The Sonic 2 Z80 driver comment at line 337-338 of `s2.sounddriver.asm` states:
```asm
; 295 cycles for two samples. dpcmLoopCounter should use 295 divided by 2.
```

Your constants give: `288 + 26 = 314` cycles, not 295.

**Fix:**  
Derive DAC timing from the actual Z80 driver loop:
```java
private static final double DAC_CYCLES_PER_2_SAMPLES = 295.0;
private static final double DAC_CYCLES_PER_SAMPLE = DAC_CYCLES_PER_2_SAMPLES / 2.0;
```

---

### 1.9 LOW: DAC Interpolation Default

**Location:** Line 237  
**Severity:** LOW  

**Problem:**
```java
private boolean dacInterpolate = true;
```

Original hardware uses sample-and-hold (no interpolation). For accuracy testing, default should be `false` unless matching an "HQ" SMPSPlay build.

---

## 2. PSG/SN76489 Issues (`PsgChip.java`)

### 2.1 CRITICAL: Hardcoded NTSC Clock

**Location:** Line 4  
**Severity:** HIGH  

**Problem:**
```java
private static final double CLOCK = 3579545.0;
```

PAL Mega Drive PSG clock is ~3546895 Hz (not 3579545 Hz).

**Impact:** All PSG frequencies will be ~0.9% sharp on PAL.

**Fix:**
```java
private double clock = 3579545.0; // Default NTSC

public void setRegion(Region region) {
    this.clock = (region == Region.PAL) ? 3546895.0 : 3579545.0;
    recalculateStep();
}

private void recalculateStep() {
    step = (clock / CLOCK_DIV) / SAMPLE_RATE;
}
```

---

### 2.2 MEDIUM: Noise Flip-Flop Initialization

**Location:** Lines 43-44  
**Severity:** MEDIUM  

**Problem:**
```java
outputs[3] = (lfsr & 1) == 1;
```

This makes the initial noise output state depend on the LFSR LSB. libvgm initializes:
```c
chip->ToneFreqPos[3] = 1;  // Independent of SR
chip->NoiseShiftRegister = NoiseInitialState;  // 0x8000
```

With `NoiseInitialState = 0x8000`, the LSB is 0, so your `outputs[3]` would be `false`, but libvgm's flip-flop would be `+1` (true).

**Impact:** Noise phase alignment will differ, causing the exact noise pattern to be offset.

**Fix:**
```java
outputs[3] = true;  // Match libvgm: ToneFreqPos[3] = 1
lfsr = 0x8000;
```

---

### 2.3 LOW: Type Casting in Render

**Location:** Lines 107-109  
**Severity:** LOW  

**Problem:**
```java
double voice = sample * amp;
left[j] += voice;   // Adding double to int[]
right[j] += voice;
```

If `left` and `right` are `int[]`, this requires implicit casting that may differ from libvgm's integer arithmetic.

libvgm uses:
```c
int chnOut = (int)(PSGVolumeValues[...] * chip->ChannelState[i]);
buffer[0][j] += APPLY_PANNING_S(chnOut, ...);
```

**Fix:**  
Ensure explicit integer casting and verify scaling matches libvgm:
```java
int voice = (int)(sample * amp);
left[j] += voice;
right[j] += voice;
```

---

### 2.4 OK: Noise Feedback Taps

**Location:** Lines 195-201  
**Severity:** N/A (Verified OK)  

Your implementation:
```java
int bit0 = lfsr & 1;
if ((noiseReg & 0x04) != 0) { // White Noise
    int bit3 = (lfsr >> 3) & 1;
    feedback = bit0 ^ bit3;
} else { // Periodic
    feedback = bit0;
}
lfsr = (lfsr >> 1) | (feedback << 15);
```

This matches libvgm's Mega Drive configuration:
- `WhiteNoiseFeedback = 0x0009` (taps at bits 0 and 3)
- `SRWidth = 16`

**Status:** ✅ Correct

---

### 2.5 OK: PSG Cutoff

**Location:** Line 15  
**Severity:** N/A (Verified OK)  

```java
private static final int PSG_CUTOFF = 6;
```

Matches libvgm:
```c
#define PSG_CUTOFF 0x6
```

**Status:** ✅ Correct

---

## 3. SMPS Sequencer Issues (`SmpsSequencer.java`)

### 3.1 CRITICAL: Floating-Point Tempo Accumulator

**Location:** Lines 588-594  
**Severity:** HIGH  

**Problem:**
```java
private double sampleCounter = 0;

public void advance(double samples) {
    sampleCounter += samples;
    while (sampleCounter >= samplesPerFrame) {
        sampleCounter -= samplesPerFrame;
        processTempoFrame();
    }
}
```

Using floating-point for the timing accumulator will cause **drift over long playback** due to accumulated rounding errors.

**Impact:** After several minutes, music will be audibly out of sync.

**Fix:**  
Use integer phase accumulator:
```java
private long sampleAccumulator = 0;
private static final long SAMPLE_SCALE = 1 << 16; // Fixed-point scale

public void advance(int samples) {
    sampleAccumulator += samples * SAMPLE_SCALE;
    long samplesPerFrameScaled = (long)(samplesPerFrame * SAMPLE_SCALE);
    while (sampleAccumulator >= samplesPerFrameScaled) {
        sampleAccumulator -= samplesPerFrameScaled;
        processTempoFrame();
    }
}
```

---

### 3.2 MEDIUM: Return Stack Too Small

**Location:** Line 118  
**Severity:** MEDIUM  

**Problem:**
```java
final int[] returnStack = new int[4];
```

The Z80 driver allows deeper nesting (the stack grows downward in track memory and can collide with loop counters, but allows more than 4 levels). Some custom SMPS data uses deeper call chains.

**Impact:** Stack overflow will cause tracks to stop prematurely or corrupt data.

**Fix:**
```java
final int[] returnStack = new int[16];  // Or 32 for safety
```

---

### 3.3 ~~MEDIUM~~ LOW: Missing Coordination Flags

**Location:** Lines 745-851  
**Severity:** ~~MEDIUM~~ **LOW** (reclassified after S2 driver analysis)  

**Problem:**  
Several coordination flags listed in `flagParamLength()` are not implemented in `handleFlag()`.

**IMPORTANT UPDATE:** Analysis of `s2.sounddriver.asm` (lines 2853-3001) reveals:

| Flag | Name in S2 Driver | Actually Needed? | Notes |
|------|-------------------|------------------|-------|
| `ED` | `cfClearPush` | ❌ **NO** | NOP in Sonic 2 — remnant from Sonic 1, just returns |
| `EE` | `cfStopSpecialFM4` | ❌ **NO** | NOP in Sonic 2 — `dec hl; ret` (does nothing) |
| `FA-FF` | N/A | ❌ **NO** | Not in Sonic 2's flag table (ends at F9) |

**Impact:** ~~Songs using these flags will not play correctly.~~ **None for Sonic 2 — these flags are NOPs.**

**Fix:**  
For Sonic 2 accuracy, these can be safely ignored. If you want to consume the parameter byte for ED:

```java
case 0xED: // cfClearPush (Sonic 1 remnant - NOP in S2)
    // Does nothing in Sonic 2, but consumes no parameter
    // In original: just "ret" (broken - doesn't dec hl)
    break;
case 0xEE: // cfStopSpecialFM4 (Sonic 1 remnant - NOP in S2)
    // Does nothing in Sonic 2
    // In original: "dec hl; ret"
    break;
```

---

### 3.4 MEDIUM: E6 Volume Application Timing

**Location:** Lines 1023-1028  
**Severity:** MEDIUM  

**Problem:**
```java
private void setVolumeOffset(Track t) {
    if (t.pos < data.length) {
        t.volumeOffset += (byte) data[t.pos++];
        refreshVolume(t);  // Immediately refreshes
    }
}
```

In the Sonic 2 Z80 driver, FM track volume is **"only applied at voice changes"** (when loading a new instrument), not continuously. The volume offset modifies TL only for carrier operators as masked by the algorithm.

From `s2.sounddriver.asm` comment at line 106:
```asm
Volume:         ds.b 1  ; Channel volume (only applied at voice changes)
```

**Impact:** Volume changes may be applied at wrong times, causing incorrect dynamics.

**Fix:**  
For FM tracks, don't call `refreshVolume()` immediately. Instead, mark the track as needing volume refresh and apply it only during `loadVoice()` or `refreshInstrument()`.

---

### 3.5 MEDIUM: Note Fill / Gate Timing Order

**Location:** Lines 607-612  
**Severity:** MEDIUM  

**Problem:**
```java
if (t.fill > 0 && (t.scaledDuration - t.duration) >= t.fill && !t.tieNext) {
    stopNote(t);
}
```

The Z80 driver's update order is:
1. Decrement duration
2. If duration expired: clear no-attack bit, parse next note, key off, set freq, set duration, key on (unless hold)
3. If duration not expired: check note fill, then modulation update, then freq update

Your current order checks fill **before** modulation in the tick loop, but the exact timing of key-off relative to the next note read can differ.

**Reference:** `zFMUpdateTrack` in `s2.sounddriver.asm`:
```asm
zFMUpdateTrack:
    dec  (ix+zTrack.DurationTimeout)  ; Decrement duration
    jr   nz,.notegoing                ; If not time-out yet, go do updates only
    res  4,(ix+zTrack.PlaybackControl); Clear "do not attack" bit
    call zFMDoNext                    ; Handle coordination flags, get next note
    call zFMPrepareNote               ; Prepare to play next note
    call zFMNoteOn                    ; Key on (if allowed)
    call zDoModulation                ; Update modulation
    jp   zFMUpdateFreq                ; Apply frequency update

.notegoing:
    call zNoteFillUpdate              ; Check note fill (may key off)
    call zDoModulation                ; Update modulation
    jp   zFMUpdateFreq                ; Apply frequency
```

**Fix:**  
Reorganize tick loop to match Z80 order exactly.

---

### 3.6 LOW: Modulation Initialization

**Location:** Lines 1161-1167  
**Severity:** LOW  

**Problem:**
```java
if (t.modEnabled) {
    t.modDelay = t.modDelayInit;
    t.modRateCounter = t.modRate;
    t.modStepCounter = t.modSteps / 2;  // Half steps initially
    t.modAccumulator = 0;
    t.modCurrentDelta = t.modDelta;
}
```

The `modSteps / 2` initialization needs verification against the Z80 driver. In `s2.sounddriver.asm`, modulation has:
- `ModulationWait` - wait before modulation starts
- `ModulationSpeed` - how fast modulation updates
- `ModulationDelta` - change per step
- `ModulationSteps` - number of steps (divided by 2)

Verify the division and initial accumulator state match.

---

### 3.7 LOW: E9 Key Displacement Sign Handling

**Location:** Lines 1036-1039  
**Severity:** LOW  

**Problem:**
```java
private void setKeyOffset(Track t) {
    if (t.pos < data.length) {
        t.keyOffset += (byte) data[t.pos++];
    }
}
```

The cast to `byte` handles sign extension, but verify this matches Z80's 8-bit signed addition behavior, especially for edge cases like transposing from +127 by +1 (should wrap to -128).

---

### 3.8 MEDIUM: Tempo Accumulator Reset on EA

**Location:** Lines 829-836  
**Severity:** MEDIUM  

**Problem:**
```java
case 0xEA:
    // Set main tempo
    if (t.pos < data.length) {
        int newTempo = data[t.pos++] & 0xFF;
        normalTempo = newTempo;
        calculateTempo();
        tempoAccumulator = tempoWeight;  // Reset to weight
    }
    break;
```

Verify this matches the Z80 behavior. The original driver may reset to 0 or to the new tempo value, not `tempoWeight`.

---

## 4. SMPS Driver Issues (`SmpsDriver.java`)

### 4.1 LOW: Priority System

**Location:** Lines 257-270  
**Severity:** LOW  

**Problem:**
```java
private boolean shouldStealLock(SmpsSequencer currentLock, SmpsSequencer challenger) {
    // ...
    // Both are SFX. Priority: Newer wins.
    int currentIdx = sequencers.indexOf(currentLock);
    int challengerIdx = sequencers.indexOf(challenger);
    return challengerIdx > currentIdx;
}
```

The Z80 driver uses a **priority value** stored in `zComRange` (byte at +00h), not sequencer order. SFX with higher priority values take precedence.

**Impact:** SFX priority may not match original behavior.

**Fix:**  
Implement priority-based channel stealing using the priority byte from SFX data.

---

### 4.2 OK: Channel Lock/Unlock

The channel locking mechanism for SFX overriding music channels appears correctly implemented.

**Status:** ✅ Appears correct

---

## 5. Missing Features

### 5.1 Coordination Flags Status (Revised After S2 Driver Analysis)

**Key Finding:** Sonic 2's coordination flag table only covers E0-F9. Flags FA-FF do not exist in the S2 driver.

| Flag | S2 Driver Name | Parameters | Status | Notes |
|------|----------------|------------|--------|-------|
| `E0` | cfPanningAMSFMS | 1 | ✅ Implemented | |
| `E1` | cfDetune | 1 | ✅ Implemented | |
| `E2` | cfSetCommunication | 1 | ✅ Implemented | |
| `E3` | cfJumpReturn | 0 | ✅ Implemented | |
| `E4` | cfFadeInToPrevious | 0 | ✅ Implemented | |
| `E5` | cfSetTempoDivider | 1 | ✅ Implemented | |
| `E6` | cfChangeFMVolume | 1 | ⚠️ Timing issue | Should apply via zSetChanVol |
| `E7` | cfPreventAttack | 0 | ✅ Implemented | |
| `E8` | cfNoteFill | 1 | ✅ Implemented | |
| `E9` | cfChangeTransposition | 1 | ✅ Implemented | |
| `EA` | cfSetTempo | 1 | ✅ Implemented | |
| `EB` | cfSetTempoMod | 1 | ✅ Implemented | |
| `EC` | cfChangePSGVolume | 1 | ✅ Implemented | |
| `ED` | cfClearPush | 0 | ✅ N/A | **NOP in S2** (Sonic 1 remnant) |
| `EE` | cfStopSpecialFM4 | 0 | ✅ N/A | **NOP in S2** (Sonic 1 remnant) |
| `EF` | cfSetVoice | 1 | ✅ Implemented | |
| `F0` | cfModulation | 4 | ✅ Implemented | |
| `F1` | cfEnableModulation | 0 | ✅ Implemented | |
| `F2` | cfStopTrack | 0 | ✅ Implemented | |
| `F3` | cfSetPSGNoise | 1 | ✅ Implemented | |
| `F4` | cfDisableModulation | 0 | ✅ Implemented | |
| `F5` | cfSetPSGTone | 1 | ✅ Implemented | |
| `F6` | cfJumpTo | 2 | ✅ Implemented | |
| `F7` | cfRepeatAtPos | 4 | ✅ Implemented | |
| `F8` | cfJumpToGosub | 2 | ✅ Implemented | |
| `F9` | cfOpF9 | 0 | ⚠️ Partial | See F9 analysis below |
| `FA-FF` | N/A | N/A | ✅ N/A | **Not in S2 driver** |

**F9 (cfOpF9) Analysis:**  
In Sonic 2, F9 is used for SFX-related functionality. The current implementation writes to specific operator registers but may need verification against the exact S2 behavior.

### 5.2 PSG Volume Envelope Commands

Current implementation handles:
- `0x00-0x7F` - Volume values ✅
- `0x80` - Hold ✅
- `0x81` - Hold ✅
- `0x82 xx` - Loop to index ✅
- `0x83` - Stop ✅
- `0x84 xx` - Change multiplier ⚠️ (skipped, not modeled)

### 5.3 FM Channel 3 Special Mode

The `channel3SpecialMode` flag exists in `Ym2612Chip.java`, and frequency slot writes are implemented at lines 563-577, but verify:
1. Per-operator frequencies are used during synthesis
2. Phase calculation uses per-slot frequencies when mode is active

---

## 6. Recommended Priority Fixes

### Priority 1: Critical (Must Fix)

| # | Issue | File | Impact | Reference |
|---|-------|------|--------|-----------|
| 1 | Region-dependent clocks | Ym2612Chip, PsgChip | Wrong pitch on PAL | — |
| 2 | Copy exact integer tables from libvgm | Ym2612Chip | Every sample wrong | YM2612.java.example |
| 3 | Integer tempo accumulator | SmpsSequencer | Long-play drift | — |

### Priority 2: High (Should Fix)

| # | Issue | File | Impact | Reference |
|---|-------|------|--------|-----------|
| 4 | Fix update order to match Z80 | SmpsSequencer | Timing errors | s2.sounddriver.asm:431-444 |
| 5 | DAC timing (295 cycles, not 314) | Ym2612Chip | DAC pitch wrong | s2.sounddriver.asm:337-338 |
| 6 | E6 volume timing (voice changes only) | SmpsSequencer | Wrong dynamics | s2.sounddriver.asm:3173-3176 |

### Priority 3: Medium (Nice to Have)

| # | Issue | File | Impact | Reference |
|---|-------|------|--------|-----------|
| 7 | Expand return stack to 16+ | SmpsSequencer | Edge case crashes | — |
| 8 | PSG noise flip-flop init | PsgChip | Noise phase offset | sn76489.c |
| 9 | SFX priority system | SmpsDriver | Wrong SFX behavior | s2.sounddriver.asm:32 |
| 10 | SSG-EG verification | Ym2612Chip | FM synthesis errors | YM2612.java.example:356-361 |

### Priority 4: Low (Polish)

| # | Issue | File | Impact |
|---|-------|------|--------|
| 11 | ENV_CUT_OFF addition | Ym2612Chip | Minor accuracy |
| 12 | DAC interpolation default | Ym2612Chip | HQ vs accuracy |
| 13 | E9 sign wrap verification | SmpsSequencer | Edge case |

### ~~Previously Listed (Now Removed)~~

The following were originally flagged but are **not needed for Sonic 2**:
- ~~ED/EE coordination flags~~ — NOPs in S2 driver
- ~~FA-FF coordination flags~~ — Don't exist in S2 driver

---

## 7. Testing Recommendations

### 7.1 Register-Write Trace Tests

Record every YM/PSG write (register, value, sample index) while playing a known song. Compare against:
- SMPSPlay engine output with identical SMPS data
- Trace dumped from Sonic 2 Z80 driver in an emulator (Gens, BlastEm)

### 7.2 PCM Output Hash Tests

Render N seconds of audio and compare SHA-256 hash of left/right PCM against SMPSPlay+libvgm output at the same sample rate.

Configuration must match:
- Same sample rate (44100 Hz)
- Same chip clocks (NTSC or PAL)
- Same interpolation/HQ settings (disabled for accuracy)

### 7.3 Boundary Tests

Test edge cases:
- Maximum note value (0xDF) with maximum positive transpose
- Minimum note value (0x81) with maximum negative transpose
- Nested call/return at maximum depth
- Loop counter overflow
- Long playback (5+ minutes) for drift detection
- All 8 FM algorithms with all feedback levels
- PSG noise in all 4 modes (0x00-0x03)

### 7.4 Specific Song Tests

| Song | Test Focus |
|------|------------|
| Emerald Hill Zone | Basic FM + PSG + DAC |
| Chemical Plant Zone | Fast tempo, complex DAC |
| Casino Night Zone | PSG envelopes, modulation |
| Mystic Cave Zone | FM modulation |
| Title Screen | All channels, transitions |
| 1-Up Jingle | Fade in/out, music restore |
| Ring SFX | SFX priority, channel stealing |
| Spring SFX | PSG noise |

---

## Appendix: Coordination Flag Coverage

### Sonic 2 Z80 Driver Flags (0xE0-0xFF)

| Flag | Name | Params | Java Status | Notes |
|------|------|--------|-------------|-------|
| E0 | Pan/AMS/FMS | 1 | ✅ | `setPanAmsFms()` |
| E1 | Detune | 1 | ✅ | `setDetune()` |
| E2 | Communication | 1 | ✅ | Sets `commData` |
| E3 | Return | 0 | ✅ | `handleReturn()` |
| E4 | Fade In | 0 | ✅ | `handleFadeIn()` |
| E5 | Tick Multiplier | 1 | ✅ | `setTrackDividingTiming()` |
| E6 | Volume (add) | 1 | ⚠️ | Timing may be wrong |
| E7 | Tie Next | 0 | ✅ | Sets `tieNext` |
| E8 | Note Fill | 1 | ✅ | `setFill()` |
| E9 | Key Displacement | 1 | ✅ | `setKeyOffset()` |
| EA | Set Tempo | 1 | ✅ | `setTempoWeight()` |
| EB | Set Dividing | 1 | ✅ | `updateDividingTiming()` |
| EC | PSG Volume | 1 | ✅ | `setPsgVolume()` |
| ED | cfClearPush | 0 | ✅ N/A | **NOP in S2** (Sonic 1 remnant) |
| EE | cfStopSpecialFM4 | 0 | ✅ N/A | **NOP in S2** (Sonic 1 remnant) |
| EF | Set Voice | 1 | ✅ | `loadVoice()` |
| F0 | Modulation | 4 | ✅ | `handleModulation()` |
| F1 | Modulation On | 0 | ✅ | Sets `modEnabled` |
| F2 | Stop | 0 | ✅ | Sets `active = false` |
| F3 | PSG Noise | 1 | ✅ | `setPsgNoise()` |
| F4 | Modulation Off | 0 | ✅ | `clearModulation()` |
| F5 | PSG Instrument | 1 | ✅ | `loadPsgEnvelope()` |
| F6 | Jump | 2 | ✅ | `handleJump()` |
| F7 | Loop | 4 | ✅ | `handleLoop()` |
| F8 | Call | 2 | ✅ | `handleCall()` |
| F9 | SND_OFF | 0 | ⚠️ | May be incomplete |
| FA-FF | N/A | N/A | ✅ N/A | **Not in S2 driver** (flag table ends at F9) |
| FD | Fade Out | 2 | ✅ | `handleFadeOut()` (custom extension) |

---

## Addendum: Implementation Strategy

### Leveraging YM2612.java.example

The `docs/YM2612.java.example` file (Gens YM2612 Java port by Stephan Dittrich, 2005) provides a **complete, tested Java implementation** that can be used to fill gaps in `Ym2612Chip.java`. Key advantages:

1. **Same language** — No C-to-Java translation needed
2. **Same table structures** — `SIN_TAB`, `TL_TAB`, `ENV_TAB`, etc. use identical layouts
3. **Proven accuracy** — Gens is a well-tested emulator
4. **Reference implementations available for:**
   - `KEY_ON()` / `KEY_OFF()` with `DECAY_TO_ATTACK` envelope conversion (lines 257-285)
   - `CSM_Key_Control()` for Timer A overflow triggering (lines 287-293)
   - `setSlot()` / `setChannel()` register handling (lines 295-445)
   - Timer A/B logic with proper reload semantics (lines 467-499)
   - All 8 FM algorithm routings
   - LFO AMS/FMS application with correct shift mapping

**Recommended Approach:**
```
1. Compare Ym2612Chip.java methods against YM2612.java.example equivalents
2. Port missing logic (especially KEY_ON envelope conversion)
3. Validate with register-trace tests
4. Consider replacing Ym2612Chip.java entirely with YM2612.java.example if gaps are extensive
```

### Sonic 2-Specific Priority

This audit focuses on **Sonic 2 accuracy first**. The following decisions reflect S2-specific behavior:

| Decision | Rationale |
|----------|-----------|
| ED/EE flags marked as N/A | NOPs in S2 driver (Sonic 1 remnants) |
| FA-FF flags not needed | S2 flag table ends at F9 |
| F9 (cfOpF9) needs verification | S2-specific SFX behavior |
| PSG envelope commands | S2 uses 0x80-0x84 subset |
| DAC timing = 295 cycles | Per s2.sounddriver.asm comment |

### Future Game Support Considerations

When extending to **Sonic 1** or **Sonic 3 & Knuckles**, the following will need revisiting:

| Game | Additional Requirements |
|------|------------------------|
| **Sonic 1** | ED (cfClearPush) may have real behavior; different tempo system; 68K SMPS variant |
| **Sonic 3K** | Extended coordination flags; different PSG envelope format; additional DAC samples; SMPS Z80 Type 2 |

**Recommendation:** Create game-specific `SmpsSequencerConfig` implementations and flag the driver variant in `AbstractSmpsData`. The current architecture with `Sonic2SmpsSequencerConfig` supports this pattern.

### Updated Priority Matrix (S2-Focused)

| Priority | Issue | S2 Impact | Implementation Source |
|----------|-------|-----------|----------------------|
| **P1** | Region clocks | Wrong pitch on PAL | Manual implementation |
| **P1** | Integer tempo accumulator | Long-play drift | Manual implementation |
| **P1** | Table determinism | Every sample wrong | Copy from YM2612.java.example |
| **P2** | DAC timing (295 cycles) | DAC pitch wrong | s2.sounddriver.asm:337-338 |
| **P2** | E6 volume timing | Wrong dynamics | s2.sounddriver.asm:3173-3176 |
| **P2** | Update order | Timing errors | s2.sounddriver.asm:431-444 |
| **P3** | KEY_ON envelope conversion | Attack transients | YM2612.java.example:257-270 |
| **P3** | SSG-EG behavior | Rare FM sounds | YM2612.java.example:356-361 |
| **P3** | SFX priority system | SFX conflicts | s2.sounddriver.asm:32 |
| **Defer** | ED/EE implementation | None for S2 | Defer to S1/S3K work |
| **Defer** | Extended flags (FA-FF) | None for S2 | Defer to S3K work |

---

## Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-01-16 | Amp AI | Initial audit |
| 2026-01-16 | Amp AI | Added cross-reference analysis; reclassified ED/EE/FA-FF as not needed for S2 |
| 2026-01-16 | Amp AI | Added implementation strategy addendum with YM2612.java.example guidance |
