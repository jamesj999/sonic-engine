# Signpost SFX (0xCF) Debug Diary

## Problem Description
The Signpost SFX sounds incorrect compared to the original game:
- Metallic/reverb quality that shouldn't be there
- Doesn't fade out as expected
- Described as "reverberating phaser" sound

## Signpost SFX Structure (from disassembly)

**Header:**
- 2 FM channels: FM4 and FM5
- FM4: transpose=0x27, volume=+3 (starts quieter)
- FM5: transpose=0x27, volume=0

**Track Data:**
- FM4: `nRst, $04` (rest 4 ticks) → then falls through to FM5's code
- FM5: `smpsSetvoice $00` → loop 21x: `nEb4 $05` + `smpsAlterVol +2`

**Both channels play the same note sequence**, FM4 starts 4 ticks later and 3 units quieter.

**Voice $00:** Algorithm 4, Feedback 6 (bell-like "ding")
- Algorithm 4 carriers: Op2 and Op4 (slots 2 and 3 in SMPS order)
- ALGO_OUT_MASK[4] = 0x0C (bits 2 and 3)

## Voice Data Format

SMPS voice data is 25 bytes. The order in ROM is **register order** (Slot0, Slot2, Slot1, Slot3), which the Z80 driver writes sequentially to YM2612 registers.

Our setInstrument uses swapped indices to reorder into operator order:
```java
int[] tlIdx = {21, 23, 22, 24};  // Op1, Op3, Op2, Op4 → Op1, Op2, Op3, Op4
```

---

## Attempted Fixes

### Attempt 1: Remove opOut reset from keyOn
**Date:** Prior to 2026-01-23
**Change:** Removed `opOut[0] = 0; opOut[1] = 0;` from keyOn() when operator 0 keys on
**Result:** ❌ Did not fix the issue
**Notes:** libvgm doesn't reset feedback history on keyOn

### Attempt 2: Simplify setInstrument reset
**Date:** Prior to 2026-01-23
**Change:** Reduced comprehensive state reset to just key-off
**Result:** ❌ Did not fix the issue

### Attempt 3: Remove early-exit for silent channels
**Date:** Prior to 2026-01-23
**Change:** Removed optimization that skipped processing silent channels
**Result:** ❌ Did not fix the issue

### Attempt 4: Change setInstrument to sequential indices
**Date:** 2026-01-23
**Change:** Changed setInstrument indices from `{21, 23, 22, 24}` to `{21, 22, 23, 24}`
**Result:** ❌ BROKE ALL AUDIO - "earsplitting noise" then "pretty bad"
**Notes:** setInstrument MUST use swapped indices. Reverted.

### Attempt 5: Fix refreshVolume to match setInstrument
**Date:** 2026-01-23
**Change:** Changed refreshVolume from `int tlIdx = 21 + slot` to `int[] tlIndices = {21, 23, 22, 24}`
**Result:** ⚠️ PARTIAL - Music and most SFX work correctly now. Signpost still has metallic reverb.
**Notes:** This fixed the inconsistency between setInstrument and refreshVolume, but the core Signpost issue remains.

### Attempt 6: Fix refreshInstrument to match setInstrument
**Date:** 2026-01-23
**Change:** Changed refreshInstrument from `int idx = tlBase + slot` (sequential) to `int[] tlIndices = {21, 23, 22, 24}` (swapped)
**Result:** ❌ BROKE Chemical Plant Zone instruments, did NOT fix Signpost
**Notes:** Reverted. The existing code apparently works correctly for music.

### Attempt 7: Genesis-Plus-GX feedback formula (SIN_BITS - fb)
**Date:** 2026-01-23
**Change:** Changed feedback calculation from `(fb != 0) ? (9 - fb) : 31` to `SIN_BITS - fb` (where SIN_BITS=10)
**Result:** ❌ Made CPZ FM1 sound muted, did NOT fix Signpost
**Notes:** Genesis-Plus-GX uses this formula with 10-bit phase precision. Our code uses 14-bit phase precision, so the feedback scaling is different. Reverted to `9 - fb`.

### Attempt 8: Add ENV_QUIET check for feedback calculation
**Date:** 2026-01-23
**Change:** Added ENV_QUIET threshold check before calculating feedback. When envelope is quiet, skip feedback calc and output 0 to decay the feedback buffer.
**Result:** ❌ Made Signpost sound "more reverby"
**Notes:** In Genesis-Plus-GX, when eg_out >= ENV_QUIET, the feedback calculation is skipped and op1_out gets filled with 0. This causes the feedback buffer to naturally decay. Our implementation may have different envelope scaling.

### Attempt 9: Restructure doAlgo to calculate SLOT1 outside switch
**Date:** 2026-01-23
**Change:** Moved SLOT1 feedback/output calculation before the algorithm switch with ENV_QUIET check
**Result:** ❌ Did not fix Signpost
**Notes:** Reverted as part of stash restoration.

### Attempt 10: Combined stashed changes
**Date:** 2026-01-23
**Change:** Restored all stashed changes including:
- refreshVolume rewrite (no longer calls refreshInstrument)
- ALGO_OUT_MASK[4] changed from 0x0A to 0x0C
- opOut reset moved to forceSilenceChannel
- Minimal setInstrument reset (just fInc = -1)
- `if (ch.feedback < SIN_BITS)` guards in doAlgo
**Result:** ⚠️ CPZ FM1 sounds muted with SIN_BITS-fb formula
**Notes:** The ALGO_OUT_MASK fix is important - algorithm 4's carriers are slots 2 and 3 (0x0C), not 1 and 3 (0x0A).

### Attempt 11: Revert feedback formula, keep other fixes
**Date:** 2026-01-23
**Change:** Reverted feedback formula back to `(fb != 0) ? (9 - fb) : 31` while keeping:
- refreshVolume rewrite with correct TL indices
- ALGO_OUT_MASK[4] = 0x0C
- `if (ch.feedback < SIN_BITS)` guards (works with both formulas)
- opOut reset in forceSilenceChannel
- Minimal setInstrument reset
**Result:** ⚠️ CPZ works correctly, Signpost still has metallic reverb
**Notes:** The `9 - fb` formula gives stronger feedback than Genesis-Plus-GX's `10 - fb`, but our table precision is different (14-bit vs 10-bit phase), so this may be correct for our implementation.

### Attempt 12: Remove opOut reset from forceSilenceChannel
**Date:** 2026-01-23
**Change:** Removed `ch.opOut[0] = 0; ch.opOut[1] = 0;` from forceSilenceChannel
**Result:** ❌ Signpost still has metallic reverb
**Notes:** The Z80 driver doesn't explicitly clear feedback state when switching channels. Didn't fix the issue.

### Attempt 13: Genesis-Plus-GX feedback formula + opOut reset
**Date:** 2026-01-23
**Change:**
- Changed feedback formula from `(fb != 0) ? (9 - fb) : 31` to `SIN_BITS - fb` (10 - fb)
- Restored opOut reset in forceSilenceChannel
- Changed default feedback from 31 to SIN_BITS
**Result:** ❌ Signpost still bad, Roll SFX (0xBE) also sounds bad
**Notes:** CPZ FM1 still muted with this formula.

### Attempt 14: Restructure algorithm 4 feedback (post-shift)
**Date:** 2026-01-23
**Change:** Changed algorithm 4 to apply feedback AFTER phase shift (like Genesis-Plus-GX) instead of before:
```java
int idx0 = (in0 >> SIN_LBITS) & SIN_MASK;
if (ch.feedback < SIN_BITS) {
    int fb = ((ch.opOut[0] + ch.opOut[1]) >> ch.feedback) >> (SIN_LBITS - SIN_BITS);
    idx0 = (idx0 + fb) & SIN_MASK;
}
```
**Result:** ❌ Made CPZ FM1 have weird background noise, Signpost still broken
**Notes:** Reverted. The feedback scaling for post-shift application is complex.

### Attempt 15: Add TL_TAB bounds checking (opCalc helper)
**Date:** 2026-01-23
**Change:** Added `opCalc(int phase, int env)` helper that returns 0 when `SIN_TAB[...] + env >= TL_LEN * 2`
**Result:** ❌ Signpost still has weird reverb/feedback effect
**Notes:** Matches Genesis-Plus-GX bounds checking. Ensures silence when envelope is maxed out.

### Attempt 16: Reduce feedback strength (+4 extra shift)
**Date:** 2026-01-23
**Change:** Changed feedback shift from `(9 - fb)` to `(9 - fb + 4)` (16x weaker feedback)
**Result:** ❌ Reverb effect still remains
**Notes:** This rules out feedback STRENGTH as the cause. The issue is elsewhere.

### Attempt 17: Mute FM4 to test two-channel interference
**Date:** 2026-01-23
**Change:** Added `DEBUG_MUTE_FM4 = true` to mute channel 3 (FM4) in renderChannel
**Result:** ❌ Reverb STILL persists with only FM5 playing
**Notes:** This rules out two-channel interference as the cause. The issue is within a single channel.

### Attempt 18: Completely disable feedback for algorithm 4
**Date:** 2026-01-23
**Change:** Commented out the feedback calculation line in algorithm 4 case
**Result:** ❌ Reverb STILL persists with NO feedback at all
**Notes:** **CRITICAL FINDING:** This definitively proves the issue is NOT in YM2612 feedback calculation. The problem must be in the SMPS sequencer or audio output path.

---

## Current State (2026-01-23)

**Current code has:**
- Feedback formula: `(fb != 0) ? (9 - fb) : SIN_BITS`
- `if (ch.feedback < SIN_BITS)` guard before feedback calculation
- `opCalc()` helper with TL_TAB bounds checking
- refreshVolume: standalone implementation, no key-off, uses swapped TL indices
- ALGO_OUT_MASK[4] = 0x0C (correct for algorithm 4 carriers)
- setInstrument: minimal reset (just fInc = -1)
- forceSilenceChannel: resets opOut
- keyOn: does NOT reset opOut

**Status:**
- Music: ✅ Working (with 9-fb formula)
- Most SFX: ⚠️ Roll SFX (0xBE) also has issues
- Signpost SFX: ❌ Still has metallic reverb

---

## CRITICAL FINDINGS (2026-01-23)

### Ruled Out Causes:
1. **Feedback formula** - Tried 9-fb, 10-fb, SIN_BITS-fb, +4 extra shift
2. **Feedback strength** - 16x reduction made no difference
3. **Two-channel interference** - Muting FM4 didn't help (FM5 alone has reverb)
4. **Feedback itself** - Completely disabling feedback didn't help
5. **TL_TAB bounds** - Added bounds checking, no change
6. **opOut reset timing** - Tried with/without reset in forceSilenceChannel

### The Problem is NOT in YM2612 Feedback!
The reverb persists even with:
- FM4 muted (single channel test)
- Algorithm 4 feedback completely disabled

This means the issue is in the **SMPS sequencer** or **audio output path**, not the YM2612 emulation.

### Next Steps to Investigate:
1. **SMPS note processing** - Are notes being triggered multiple times?
2. **Audio mixing** - Is there echo in the output buffer?
3. **Track duplication** - Is the SFX somehow playing on multiple tracks?
4. **Key on/off timing** - Are notes properly stopping before new ones start?
5. **Use BizHawk** - Compare register writes and audio output with Genesis-Plus-GX

---

## Key Differences: Genesis-Plus-GX vs Our Implementation

| Aspect | Genesis-Plus-GX | Our Code |
|--------|-----------------|----------|
| Phase precision | 10-bit (SIN_BITS) | 14-bit (SIN_LBITS) |
| TL_TAB values | 14-bit | ~28-bit (MAX_OUT_BITS) |
| Feedback formula | `10 - fb` | `9 - fb` |
| Feedback applied | After phase shift | Before phase shift |
| ENV_QUIET check | Skips calc when quiet | No equivalent |
| op1_out update | Always (even when quiet) | Always |

The fundamental difference is that Genesis-Plus-GX adds feedback AFTER shifting phase:
```c
// Genesis-Plus-GX
index = ((phase >> SIN_BITS) + feedback) & SIN_MASK;
```

Our code adds feedback BEFORE shifting:
```java
// Our code
in0 += feedback;
index = (in0 >> SIN_LBITS) & SIN_MASK;
```

This means the feedback scaling relationship is different between the two implementations.

---

## Remaining Hypotheses

### H1: Two-channel phase interference (LIKELY)
FM4 and FM5 play the same note with a 4-tick offset. If their phases or feedback buffers don't align correctly, they create the phaser/reverb effect.

### H2: refreshVolume vs Z80 zSetChanVol differences
The Z80 driver's zSetChanVol may have subtleties we're not matching.

### H3: Timing of volume changes
The smpsAlterVol commands happen every note. The timing of when TL registers are written may affect the sound.

### H4: forceSilenceChannel timing
When SFX starts, we call forceSilenceChannel which resets opOut. This may not match what the Z80 driver does.

---

## Key Code Locations

- `Ym2612Chip.java` - YM2612 emulation
  - Feedback formula: line ~637
  - doAlgo: line ~882
  - setInstrument: line ~970
  - forceSilenceChannel: line ~431
- `SmpsSequencer.java` - SMPS driver
  - refreshVolume: line ~1392
  - ALGO_OUT_MASK: line ~109
- `docs/s2disasm/sound/sfx/CF - Signpost.asm` - Reference disassembly
- `docs/gensplusgx/ym2612.c` - Reference implementation

---

## Debug Tools Created

- `TraceSignpostRegisters.java` - Traces register writes during Signpost playback
- `TraceTLWrites.java` - Analyzes expected TL progression
- `DumpRawVoice.java` - Dumps raw voice bytes from ROM
- `VerifyVoiceOrder.java` - Verifies voice data byte ordering
- `ExportSignpost.java` - Exports Signpost SFX to WAV file
- `SoundTestApp.java` - Added Ctrl+E export to WAV feature

---

## Next Steps to Try

1. **Compare register writes** - Log exact YM2612 register writes and compare against Genesis-Plus-GX
2. **Check phase counter state** - Both channels should have independent fCnt values
3. **Investigate forceSilenceChannel timing** - Does resetting opOut at SFX start cause the issue?
4. **Try removing opOut reset from forceSilenceChannel** - Let feedback persist naturally
5. **Check if Z80 driver does anything special for multi-channel SFX**

---

## Further Analysis (Post-Attempt 18)

### Voice Data Analysis

Signpost voice $00 parameters (from disassembly macros):
- Algorithm 4, Feedback 6
- D1L: Op1=$0F, Op2=$0F, Op3=$00, Op4=$00
- D1R: Op1=$0B, Op2=$0B, Op3=$00, Op4=$00
- TL: Op1=$00, Op2=$0B, Op3=$03, Op4=$0C

**Algorithm 4 carriers:** Op2 and Op4 (ALGO_OUT_MASK[4] = 0x0C)

**Carrier envelope behavior:**
- Op2: D1R=11, D1L=15 → decays to near-silence
- Op4: D1R=0, D1L=0 → sustains at full volume

This is intentional - Op2 decays creating a bell-like attack, while Op4 provides sustained body.

### Voice Data Storage Order

SMPS voice format stores operator parameters in "register order": [Op1, Op3, Op2, Op4]

Our setInstrument uses index arrays to reorder SMPS to YM operator order:
```java
int[] d1lRrIdx = {17, 19, 18, 20};  // Read [17,19,18,20] = [Op1,Op2,Op3,Op4]
```

Potential issue: The indices may be swapping operators incorrectly, causing carriers to get modulator settings and vice versa.

### Next Investigation: Voice Loading Verification

Create a test that:
1. Loads Signpost voice data
2. Logs all register writes during setInstrument
3. Verifies D1L values match expected:
   - Op2 (carrier): D1L=15 (fade out)
   - Op4 (carrier): D1L=0 (sustain)

If D1L values are swapped between modulators and carriers, it would explain the incorrect fade-out behavior.

### Voice Loading Verification (2026-01-23)

Traced through setInstrument indices and confirmed voice loading is CORRECT:

**SMPS Format:** Stores in **operator order** [Op1, Op2, Op3, Op4], not slot order.

**Index Arrays:** The {base, base+2, base+1, base+3} pattern converts from operator order to slot order:
- dtIdx = {1, 3, 2, 4} reads [Op1, Op2, Op3, Op4] → reorders to [Op1, Op3, Op2, Op4] (slot order)
- Same pattern for TL, RS/AR, AM/D1R, D2R, D1L/RR

**Verification:** Sequential indices {21, 22, 23, 24} broke audio because they don't reorder.
Current indices are correct for converting SMPS operator order to YM2612 slot order.

---

## FM Modulation Scaling Analysis

**Critical Difference Found:**

| Aspect | Genesis-Plus-GX | Our Code |
|--------|-----------------|----------|
| Modulation application | AFTER phase shift | BEFORE phase shift |
| Formula | `(phase >> 10) + phase_mod` | `(phase + mod) >> 14` |
| Phase shift | 10 bits | 14 bits |

**Genesis-Plus-GX:**
```c
index = ((SLOT->Fcnt >> SIN_BITS) + phase_mod) & SIN_MASK;
// phase_mod is added AFTER shifting to 10-bit index scale
```

**Our Code:**
```java
in1 += ch.opOut[1];  // mod added BEFORE shift
int p = SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK];  // then shift by 14
```

**Impact:**
- Modulation at different scales affects modulation DEPTH
- Our modulation is effectively scaled by 2^(14-10) = 16x differently
- This changes the FM synthesis timbre, making sounds more or less "metallic"

### Potential Fix

Change modulation application to match Genesis-Plus-GX:
```java
// Instead of: in1 += ch.opOut[1]; then opCalc(in1, env)
// Do: index = ((in1 >> SIN_LBITS) + (opOut >> (SIN_LBITS - SIN_BITS))) & SIN_MASK
```

But this requires careful scaling analysis to match exact modulation depth.

---

## CRITICAL FIX FOUND: Algorithm 4 Modulation Routing Bug (2026-01-23)

### Root Cause Identified

The **YM2612 Algorithm 4 implementation had completely wrong modulation routing!**

**Standard YM2612 Algorithm 4:**
- Op1 (with feedback) → Op2 [carrier]
- Op3 → Op4 [carrier]
- Output = Op2 + Op4

**In our slot order (S0=Op1, S1=Op3, S2=Op2, S3=Op4):**
- S0(FB) → S2 [carrier]
- S1 → S3 [carrier]
- Output = S2 + S3

**Our BUGGY implementation was:**
```java
in1 += ch.opOut[1];  // S1 (Op3) modulated by S0 output - WRONG!
in3 += opCalc(in2, env2);  // S3 (Op4) modulated by S2 (Op2) output - WRONG!
ch.out = opCalc(in3) + opCalc(in1);  // Output S3 + S1 - WRONG!
```

**This created the wrong routing:**
- S0(FB) → S1 (should be S2)
- S2 → S3 (should be S1)
- Output = S1 + S3 (should be S2 + S3)

### Why This Caused the Reverb Effect

1. **Wrong carriers**: ALGO_OUT_MASK[4] = 0x0C correctly targets S2 and S3 (Op2 and Op4) for volume changes, but our code had S1 and S3 as actual carriers. Volume fading was applied to the wrong operators!

2. **Wrong modulation paths**: The FM synthesis routing was completely different from standard YM2612, creating an unintended timbre with different harmonic relationships.

3. **Why feedback disabling didn't help**: The bug wasn't in feedback - it was in the fundamental algorithm routing. Even with feedback disabled, the wrong operators were being used as carriers and the modulation paths were incorrect.

### The Fix

Changed algorithm 4 in `Ym2612Chip.doAlgo()`:

```java
case 4: {
    // Algorithm 4: S0(FB)→S2 and S1→S3, carriers= S2 + S3.
    if (ch.feedback < SIN_BITS) in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
    ch.opOut[1] = ch.opOut[0];
    ch.opOut[0] = opCalc(in0, env0);
    in2 += ch.opOut[1];  // S2 input from S0 output (feedback buffer)
    in3 += opCalc(in1, env1);  // S3 input = S1 output
    ch.out = (opCalc(in2, env2) + opCalc(in3, env3)) >> OUT_SHIFT;
    ...
}
```

### Verification

This fix aligns:
- Algorithm routing with standard YM2612 documentation
- Carrier operators (S2, S3 = Op2, Op4) with ALGO_OUT_MASK[4] = 0x0C
- Volume fading now affects the correct carriers

### Attempt 19: Fix Algorithm 4 Modulation Routing
**Date:** 2026-01-23
**Change:** Changed algorithm 4 routing from S0→S1, S2→S3 to S0→S2, S1→S3
**Result:** ❌ BROKE Chemical Plant Zone instruments
**Notes:** Reverted. The original algorithm routing is correct for this codebase's conventions. The slot/operator mapping must be different from what I assumed. The Signpost issue is NOT in algorithm routing.

---

## Remaining Investigation Areas

Since algorithm routing is confirmed correct, the Signpost reverb issue must be in:

1. **SMPS note processing** - Are notes being triggered multiple times?
2. **Audio buffer/mixing** - Is there echo in the output path?
3. **Track duplication** - Is the SFX playing on multiple tracks somehow?
4. **Key on/off timing** - Are notes properly stopping before new ones start?
5. **Volume envelope application** - Is refreshVolume working correctly for algorithm 4?

### Attempt 20: Change ALGO_OUT_MASK[4] from 0x0C to 0x0A
**Date:** 2026-01-23
**Change:** Changed ALGO_OUT_MASK[4] to match algorithm 4's apparent carriers (S1+S3 = bits 1+3 = 0x0A)
**Result:** ❌ BROKE Chemical Plant Zone instruments, no improvement to Signpost
**Notes:** Reverted. The mask and algorithm routing are apparently correct together for this codebase's conventions.

---

## Conclusion: Issue is NOT in YM2612 or Algorithm Routing

Both the algorithm 4 routing AND the ALGO_OUT_MASK[4] value are correct as-is. Changing either one breaks Chemical Plant Zone music.

The Signpost reverb issue must be in:
1. **SMPS sequencer** - note timing, duplicate triggers, key on/off handling
2. **Audio output path** - buffer issues, mixing problems
3. **SFX-specific handling** - how Signpost data is loaded/processed

Next step: Enable DEBUG_SIGNPOST_TRACE to see actual note events during playback.

---

## Further Attempts (2026-01-24)

### Attempt 21: GPGX-style key flag for key-on/key-off
**Date:** 2026-01-24
**Change:** Added separate `key` boolean flag to Operator class (matching GPGX's approach). Key-on only triggers when `!key` (0→1 transition), key-off only triggers when `key` (1→0 transition). This separates key state from envelope state.
**Result:** ❌ Did not fix Signpost
**Notes:** CPZ still works. The key flag approach is more accurate to hardware but didn't resolve the issue.

### Attempt 22: AR+KSR threshold for instant attack
**Date:** 2026-01-24
**Change:** Added GPGX's AR+KSR threshold logic: when `ar + ksr >= 94`, skip ATTACK phase and go directly to DECAY/SUSTAIN.
**Result:** ❌ Made it worse - "feedback mess"
**Notes:** Reverted. Our AR/KSR scaling differs from GPGX, so the threshold value doesn't translate correctly.

### Attempt 23: Fix algorithm 4-7 modulation timing
**Date:** 2026-01-24
**Change:** Changed algorithms 4, 5, 6, 7 to use CURRENT S0 output for carrier modulation instead of DELAYED output (via `ch.opOut[1]`). The feedback buffer should only be used for self-feedback averaging, not for inter-operator modulation.
**Before:** `in1 += ch.opOut[1]` (one sample delay)
**After:** `int s0_out = opCalc(in0, env0); in1 += s0_out;` (current sample)
**Result:** ❌ CPZ OK, Signpost still has metallic reverb
**Notes:** This fix is more accurate to GPGX but didn't resolve the Signpost issue.

### Debug Trace Results (2026-01-24)
Enabled DEBUG_SIGNPOST_TRACE and confirmed sequencing is correct:
```
[SIGNPOST] KEY ON: ch=4 (FM5) note=0xb4 vol=0 duration=5 isSfx=true
[SIGNPOST] KEY ON: ch=3 (FM4) note=0xb4 vol=3 duration=5 isSfx=true
[SIGNPOST] KEY ON: ch=4 (FM5) note=0xb4 vol=2 duration=5 isSfx=true
...
```
- FM5 starts first (vol=0), FM4 starts 4 ticks later (vol=3)
- Both play note 0xB4 (Eb4) for 5 ticks each
- Volume increases by 2 each iteration (21 iterations total)
- Sequencing matches the assembly exactly

---

## Current State (2026-01-24)

**Active changes:**
- GPGX-style `key` flag for proper key-on/key-off gating
- Algorithm 4-7 use current S0 output for carrier modulation (no delay)
- AR+KSR threshold logic removed (caused issues)

**Status:**
- Music (CPZ): ✅ Working
- Signpost SFX: ❌ Still has metallic/reverb quality

**Remaining hypotheses:**
1. **Envelope Generator timing** - Our per-sample updates vs GPGX's 3-sample EG clock
2. **Table resolutions** - Different ENV_BITS (12 vs 10), SIN_BITS precision
3. **Core timebase** - 44.1kHz vs 53.3kHz affects all timing
4. **Attack formula** - GPGX uses exponential attack, we use linear

---

## GPGX Compatibility Overhaul (2026-01-24)

### Attempt 24: Implement Full GPGX-style YM2612 Changes
**Date:** 2026-01-24
**Changes:** Implemented all 5 priority GPGX compatibility fixes:

1. **Timebase Fix (~53.267 kHz internal)**
   - Changed from 44.1kHz direct rendering to ~53.267kHz internal rate
   - Added linear interpolation resampling to output 44.1kHz
   - All timing tables now use internal rate

2. **GPGX-style EG Clock (3-sample stepping)**
   - Added `EG_INC`, `EG_RATE_SELECT`, `EG_RATE_SHIFT` tables
   - Global `egCnt` counter (12-bit, cycles 1-4095, skips 0)
   - Per-operator rate cache (`egShAr`, `egSelAr`, etc.)
   - Rate-gated updates: `!(egCnt & ((1 << shift) - 1))`
   - Exponential attack: `volume += ((~volume) * inc) >> 4`

3. **Phase Modulation Scaling (add after shift)**
   - Changed `opCalc()` to add modulation AFTER phase shift
   - Formula: `idx = ((phase >> SIN_LBITS) + (pm >> (SIN_LBITS + 1))) & SIN_MASK`
   - Matches GPGX's modulation depth

4. **Output Clipping (±8191)**
   - Changed from ±24575 to ±8191/-8192 (asymmetric like GPGX)
   - Removed YM output attenuation in VirtualSynthesizer

5. **LFO Waveform/Ordering**
   - Changed from 1024-step sine to 128-step inverted triangle
   - LFO updated AFTER channel calculation (not before)
   - Disabled state: AM=126, PM=0

6. **Additional refinements:**
   - Added `slReg` field to store raw 4-bit sustain level register value
   - Added `volOut` caching (`(volume << 2) + tll`) like GPGX
   - Updated `advanceEgOperator` to use `slReg` directly instead of reverse-extracting
   - Cache updated on TL write and volume changes

**Result:** ⚠️ PARTIAL SUCCESS
- **Signpost SFX:** Much closer to original! The basic timbre is correct.
- **BUT:** Volume/feedback doesn't fade out properly - still reverberates instead of clean decay
- **Side Effect:** Many music instruments sound too soft throughout Sonic 2 soundtrack

**Notes:**
The GPGX changes have significantly improved the Signpost timbre, confirming that the envelope generator timing and phase modulation scaling were major factors. However, two issues remain:

1. **Reverberation on fade-out** - The Signpost still has a lingering reverb instead of clean decay. This may be related to:
   - Feedback buffer not decaying properly when envelope reaches silence
   - Volume scaling differences affecting when "silence" is detected
   - Missing ENV_QUIET check that GPGX uses to zero feedback when envelope is high

2. **Music too soft** - The ±8191 clipping and new envelope scaling may have reduced overall output levels. Need to investigate:
   - Whether output gain needs adjustment
   - Whether envelope-to-TL scaling is correct
   - Whether the volume << 2 scaling in `volOut` is appropriate

### Current Code State (Post-GPGX Overhaul)

**Ym2612Chip.java:**
- Internal rate: 53267 Hz with resampling to 44100 Hz
- EG: GPGX-style rate-gated 3-sample stepping with `egCnt` counter
- Attack: Exponential (`volume += ((~volume) * inc) >> 4`)
- Decay/Release: Linear (`volume += inc`)
- Phase modulation: Added after shift, scaled by `>> (SIN_LBITS + 1)`
- Output clipping: ±8191/-8192
- LFO: 128-step inverted triangle, updated after channel calc
- Volume caching: `volOut = (volume << 2) + tll`

**VirtualSynthesizer.java:**
- No YM attenuation (was `>> 1` before GPGX clipping change)
- PSG mixed at 50% (`>> 1`)

### Next Steps

1. **Investigate fade-out issue** - Why doesn't the Signpost decay cleanly?
   - Check if ENV_QUIET threshold is needed
   - Verify feedback zeroing when envelope maxes out
   - Compare volume progression with GPGX reference

2. **Fix music volume** - Why are instruments too soft?
   - May need output gain adjustment
   - Check if envelope scaling is correct
   - Verify TL-to-envelope relationship

3. **Continue GPGX integration** - More accurate emulation features:
   - SSG-EG behavior (currently @Ignored test)
   - Timer precision
   - Additional edge cases

**Decision:** Keep GPGX changes and continue investigation. The improved Signpost timbre confirms we're on the right track.

---

## Further GPGX Investigation (2026-01-24)

### Attempt 25: Fix ENV_QUIET scale and add feedback decay
**Date:** 2026-01-24
**Changes:**
1. Added ENV_QUIET check to zero feedback output when envelope is quiet
2. Changed ENV_QUIET from 896 to PG_CUT_OFF (3328) to match our envelope scale
   - GPGX uses 896 in their 10-bit scale (max ~2039) = 44% of max
   - Our volOut max is ~8156, so 44% ≈ 3589
   - Using PG_CUT_OFF (3328) as threshold since that's where TL_TAB produces zero

**Result:** Tests pass. Feedback should now decay when notes fade out.

### Attempt 26: Fix modulation depth
**Date:** 2026-01-24
**Change:** Removed extra `>> 1` from phase modulation scaling.
- Old: `pm >> (SIN_LBITS + 1)` = shift by 15
- New: `pm >> SIN_LBITS` = shift by 14

**Result:** Restores full modulation depth, should improve FM timbres.

### Attempt 27: Fix feedback formula (MAJOR FIX)
**Date:** 2026-01-24
**Issue Found:** Feedback was 128x too strong (7 bits off)!

**GPGX feedback formula:**
```c
phase += (out << (9-fb)) >> 16 = out >> (16-fb)
```
- For fb=7 (max): shift by 9
- For fb=1 (min): shift by 15

**Our OLD formula:**
```java
feedback = (9 - fb)  // shift by 2-8
```

**Our NEW formula:**
```java
feedback = (16 - fb)  // shift by 9-15 (matches GPGX)
```

**Result:** Feedback strength now matches GPGX. This was a major source of timbre errors.

### Attempt 28: Fix output scaling
**Date:** 2026-01-24
**Issue Found:** Output was clipping and losing 50% of dynamic range!

- TL_TAB output max: ~268 million (28-bit)
- Old OUT_SHIFT=14: max output ~16383
- Clipping at ±8191: loses ~50% of range!

**Fix:** Changed OUT_BITS from 14 to 13, making OUT_SHIFT=15
- New max output: ~8191 (matches clip limit)
- No more dynamic range loss

**Result:** Full dynamic range preserved within ±8191 clipping.

### Current State (Post-Investigation)

**All GPGX-related changes:**
1. ✅ Timebase: ~53.267 kHz internal with resampling to 44.1 kHz
2. ✅ EG Clock: GPGX-style rate-gated 3-sample stepping with egCnt counter
3. ✅ Phase Modulation: Added after shift, scaled by `>> SIN_LBITS`
4. ✅ Output Clipping: ±8191/-8192 with matching OUT_SHIFT
5. ✅ LFO: 128-step inverted triangle, updated after channel calc
6. ✅ Feedback: GPGX formula `(16 - fb)` for shift amount
7. ✅ ENV_QUIET: Zeros feedback when envelope ≥ PG_CUT_OFF
8. ✅ Volume caching: volOut = (volume << 2) + tll
9. ✅ slReg: Direct storage of 4-bit sustain level

**Summary of major fixes found:**
1. Feedback was 128x too strong (7 bits off)
2. Output was losing 50% dynamic range to clipping
3. ENV_QUIET threshold was in wrong scale
4. Modulation depth was halved unnecessarily

**Tests:** All 493 pass (1 skipped: SSG-EG needs further work)

---

## GPGX Divergences Document Review (2026-01-24)

Consulted `docs/YM2612_GPGX_DIVERGENCES.md` which lists 22 divergences between our implementation and GPGX.

### Attempt 29: Fix feedback formula using divergences doc
**Date:** 2026-01-24
**Issue Found:** My `(16-fb)` fix was wrong! The divergences doc (#11) clearly states:

> GPGX: Feedback shift is `SIN_BITS - fb` where `SIN_BITS=10`, so fb=1→9, fb=7→3.
> Java: Uses `9 - fb` for fb in 1..7, so fb=1→8, fb=7→2.
> Impact: Java feedback shift is 1 smaller than GPGX, meaning feedback is effectively one step **stronger**

**Correct formula:** `(10 - fb)` not `(16 - fb)`!

- fb=7 (max): shift = 3 (strongest feedback)
- fb=1 (min): shift = 9 (weakest non-zero feedback)

My previous `(16-fb)` gave shifts of 9-15, which was 64x too weak!

**Change:** Reverted to `ch.feedback = (fb != 0) ? (10 - fb) : 31`

### Attempt 30: Revert OUT_SHIFT change
**Date:** 2026-01-24
**Issue:** Changing OUT_SHIFT from 14 to 15 halved the output level, which may have affected volume/fade perception.

**Change:** Reverted OUT_BITS to 14, OUT_SHIFT back to 14.
- Max pre-clip output: ~16383
- Clipping at ±8191 may clip some peaks, but overall volume is preserved

### Key Divergences Still Outstanding (from docs/YM2612_GPGX_DIVERGENCES.md)

1. **#5 MEM delay path** - Algorithms 0-3 and 5 use one-sample delay memory in GPGX
2. **#7 Table resolutions** - ENV_STEP differs (96.0/ENV_LEN vs 128.0/ENV_LEN)
3. **#10 Write-time EG corrections** - SL-triggered state transitions, SSG conversion
4. **#16 SSG-EG 4x rate** - SSG-EG increments should be 4x larger
5. **#17 LFO reset state** - LFO_AM=126 when disabled, not 0

### Current State

**Feedback formula:** `(10 - fb)` matching GPGX
**OUT_SHIFT:** 14 (original)
**Clipping:** ±8191 (GPGX-style)
**Tests:** All pass

### Attempt 31: GPGX MEM Persistence for Algorithms 0-3, 5
**Date:** 2026-01-24
**Issue Found:** When S0 (modulator) becomes quiet, we were zeroing its output, which:
1. Changed the carrier modulation to 0 (timbre changes to pure sine during fade)
2. Caused feedback buffer to decay faster than GPGX

**GPGX behavior (divergence #5):**
- For algos 0-3, 5: When S0 is quiet, `mem_value` is NOT updated - it persists
- Carriers continue receiving the last non-zero modulation value
- This preserves the modulated timbre throughout the fade-out
- For algos 4, 6, 7: S0 output becomes 0 when quiet (no MEM path)

**Our OLD behavior:**
```java
int s0_out = s0Quiet ? 0 : opCalc(in0 + fb, env0);
ch.opOut[0] = s0_out;  // Always update to 0 when quiet
```

**Our NEW behavior:**
```java
int s0_out;
if (!s0Quiet) {
    s0_out = opCalc(in0 + fb, env0);
    ch.opOut[0] = s0_out;  // Only update when not quiet
} else {
    s0_out = ch.opOut[0];  // Persist modulation for carriers
    // Don't update opOut[0] - persists for next sample's feedback
}
```

**Changes:**
- Algorithms 0-3, 5: When S0 quiet, preserve `opOut[0]` and pass persisted value to carriers
- Algorithms 4, 6, 7: Unchanged - S0 becomes 0 when quiet (matches GPGX)

**Expected Impact:**
- Fade-out should maintain the modulated timbre (bell-like) instead of transitioning to pure sine
- Feedback buffer should persist at stable value when quiet (like GPGX)
- This should improve the quality of the Signpost fade-out

**Result:** ❌ No improvement. Signpost still reverberates.

### Attempt 32: Fix GPGX EG Rate Tables (MAJOR BUG FIX)
**Date:** 2026-01-24
**Issue Found:** EG_RATE_SELECT and EG_INC tables were completely wrong!

**GPGX eg_rate_select pattern:**
- Rates 0-1: row 18 (zero increment, dummy)
- Rates 2-3: rows 2-3
- Rates 4-47: `(rate & 3) * 8` cycling through rows 0-3
- Rates 48-51 (rate 12): rows 4-7 with larger increments
- Rates 52-55 (rate 13): rows 8-11
- Rates 56-59 (rate 14): rows 12-15
- Rates 60-63 (rate 15): row 16 (max rate)

**Our WRONG formula:** `(rate >> 2) * 8` - This selected the wrong rows entirely!
- Rate 4: we selected row 1, should be row 0
- Rate 8: we selected row 2, should be row 0
- Rate 48: we selected row 12, should be row 4

**Our WRONG EG_INC table:** Had incorrect increment patterns that didn't match GPGX's actual table.

**Fix:**
1. Rewrote EG_INC table to match GPGX ym2612.c exactly (19 rows of 8 values)
2. Fixed EG_RATE_SELECT initialization:
   - Rates 0-1: row 18 (zero)
   - Rates 2-47: `(rate & 3) * 8` for proper cycling
   - Rates 48-63: rows 4-16 with proper base offsets
3. Fixed EG_RATE_SHIFT: `11 - ((rate - 4) >> 2) - 1` for rates 4-47

**Impact:** This affects ALL envelope behavior - attack, decay, and release rates were all wrong!
- Envelopes were advancing at incorrect speeds
- Some rates were updating too often, others not often enough
- This explains why notes weren't fading/releasing correctly

**Result:** ⚠️ Had impact but didn't fully resolve the issue.

### Attempt 33: Fix EG Timer (3-sample stepping)
**Date:** 2026-01-24
**Issue Found:** EG counter was advancing every sample instead of every 3 samples!

**GPGX behavior:**
- EG runs at `chip_clock / 144 / 3 = ~17.76 kHz`
- A timer counts 0, 1, 2 before incrementing egCnt
- EG is updated only when timer overflows (every 3 internal samples)

**Our OLD behavior:**
- egCnt incremented every sample (3x too fast)
- advanceEgOperator called every sample inside renderChannel

**Fix:**
1. Added `egTimer` that counts 0, 1, 2 before triggering egCnt increment
2. Moved advanceEgOperator calls to renderOneSample, only when egTimer overflows
3. Fixed egCnt increment: simple `egCnt++` with wrap to 1 (was using `|1` which made it always odd)

**Impact:** Envelope advances at correct speed, matching hardware timing.

**Result:** ⚠️ Had impact but problems still persist. User reports "same underlying problems".

### Attempt 34: Fix MEM Persistence Bug (0 propagation)
**Date:** 2026-01-24
**Issue Found:** When S0 becomes quiet, we were NOT updating opOut[0], causing the old value to persist forever!

**Our WRONG behavior (Attempt 31):**
```java
if (!s0Quiet) {
    s0_out = opCalc(in0 + fb, env0);
    ch.opOut[0] = s0_out;
} else {
    s0_out = ch.opOut[0];  // BUG: Use old value forever
    // Don't update opOut[0]
}
```

This caused the old modulation value to persist indefinitely, creating the "reverberating" effect because:
1. Carriers continued receiving non-zero modulation even when S0 was silent
2. Feedback buffer never decayed to 0
3. Sound never properly faded out

**Correct GPGX behavior:**
- MEM is a one-sample DELAY, not infinite persistence
- When S0 is quiet, `op_calc` returns 0
- That 0 becomes the new MEM value
- Modulation naturally decays to 0

**Fix:**
```java
int s0_out = s0Quiet ? 0 : opCalc(in0 + fb, env0);
ch.opOut[0] = s0_out;  // ALWAYS update - quiet means 0 propagates
```

Applied to all algorithms (0-7). When S0 is quiet, s0_out becomes 0, and that 0:
1. Propagates to downstream operators (no modulation)
2. Updates opOut[0] for feedback calculation
3. Causes natural fade-out

**Result:** ⚠️ MEM now properly decays, but user reports:
- Signpost: "metallic rattle sound still"
- CPZ: "better, but some still sound blobby like a muted trumpet"

### Attempt 35: Increase Modulation Depth and Feedback Strength
**Date:** 2026-01-24
**Issue Found:** Two potential causes for the remaining issues:

1. **Modulation too weak (blobby CPZ):** In GPGX op_calc, modulation (pm) is shifted by 1:
   ```c
   index = ((phase >> SIN_BITS) + (pm >> 1)) & SIN_MASK;
   ```
   Our code was shifting by SIN_LBITS (14), which is correct for scale but may give weaker modulation.

2. **Feedback formula (blobby/muted):** Some GPGX versions use `9 - fb` not `10 - fb` for feedback shift.
   This gives 2x stronger feedback.

3. **Metallic rattle during fade-out:** Feedback continuing during decay creates phase modulation
   that extracts small outputs even at high envelope, causing a "rattle" sound.

**Changes:**
1. Changed modulation scaling from `pm >> SIN_LBITS` to `pm >> (SIN_LBITS - 1)` (2x more modulation)
2. Changed feedback formula from `10 - fb` to `9 - fb` (2x stronger feedback)
3. Added `feedbackCutoff` threshold at `ENV_QUIET - 500` to zero feedback earlier during fade-out

**Code:**
```java
// opCalc modulation: pm >> 13 instead of pm >> 14
int idx = ((phase >> SIN_LBITS) + (pm >> (SIN_LBITS - 1))) & SIN_MASK;

// Feedback: 9 - fb instead of 10 - fb
ch.feedback = (fb != 0) ? (9 - fb) : 31;

// Early feedback cutoff to prevent rattle
boolean feedbackCutoff = env0 >= (ENV_QUIET - 500);
int fb = (ch.feedback < 10 && !feedbackCutoff) ? (ch.opOut[0] + ch.opOut[1]) >> ch.feedback : 0;
```

**Expected Impact:**
- Stronger modulation = brighter, more harmonically rich sound (fix blobby)
- Stronger feedback = more complex waveforms (fix muted trumpet)
- Early feedback cutoff = cleaner fade-out (fix metallic rattle)

**Result:** ❌ REVERTED - User feedback: "Sounds way too over the top now. Did this change come from any basis or is it just twiddling knobs"

**Notes:** These changes were speculative and not based on careful GPGX analysis. Reverted all changes back to GPGX-correct values (`10 - fb` feedback, `pm >> SIN_LBITS` modulation, no early cutoff).

---

## Fundamental Table Scaling Analysis (2026-01-24)

### Critical GPGX vs Our Code Differences

After careful comparison of GPGX `ym2612.c` with our `Ym2612Chip.java`, discovered **fundamental envelope/TL scaling differences**:

| Aspect | GPGX | Our Code | Impact |
|--------|------|----------|--------|
| vol_out formula | `volume + tl` | `(volume << 2) + tll` | 4x volume scale |
| TL storage | `tl = (v&0x7f) << 3` | `tll = tl << 5` | Different TL weight |
| op_calc env | `(env << 3) + sin_tab[...]` | `SIN_TAB[idx] + env` | Missing env×8 scale |
| ENV_QUIET | 832 (TL_TAB_LEN >> 3) | 3328 (PG_CUT_OFF) | Different silence threshold |
| volume range | 0-1023 | 0-1023 | Same |
| MAX_ATT_INDEX | 0x3FF (1023) | 1023 | Same |

### Key Insight: op_calc Envelope Scaling

**GPGX op_calc:**
```c
UINT32 p = (env<<3) + sin_tab[ ( (phase >> SIN_BITS) + (pm >> 1) ) & SIN_MASK ];
if (p >= TL_TAB_LEN) return 0;
return tl_tab[p];
```

**Our opCalc:**
```java
int idx = ((phase >> SIN_LBITS) + (pm >> SIN_LBITS)) & SIN_MASK;
int p = SIN_TAB[idx] + env;
if (p >= TL_LEN * 2) return 0;
return TL_TAB[p];
```

**The difference:** GPGX multiplies `env` by 8 before adding to sin_tab lookup, we don't.

This affects:
1. How quickly envelope changes affect output level
2. When silence threshold (ENV_QUIET) is effectively reached
3. The relationship between TL and envelope attenuation

### Why This Matters for Signpost

The metallic rattle during fade-out may be caused by:
1. Our envelope not decaying "fast enough" in perception
2. TL_TAB producing non-zero values for longer than GPGX
3. Feedback continuing to modulate carrier even when GPGX would be silent

### Possible Root Cause

Our `volOut = (volume << 2) + tll` where `tll = tl << 5` creates a different attenuation curve than GPGX's simple `vol_out = volume + tl` where `tl = register_value << 3`.

The `(volume << 2)` term means our volume changes have 4x the weight relative to TL compared to GPGX.

### Next Steps (When Context Available)

1. **Verify table scaling alignment** - Ensure our SIN_TAB + TL_TAB produce same outputs as GPGX for equivalent inputs
2. **Consider adding env<<3 to opCalc** - Match GPGX's envelope scaling in sine lookup
3. **Recalculate ENV_QUIET** - Should be based on actual TL_TAB behavior, not arbitrary threshold
4. **Compare actual waveforms** - Generate reference from GPGX and compare sample-by-sample

---

### Attempt 36: GPGX Alignment & SmpsSequencer Reversions (FINAL FIX)
**Date:** 2026-01-24
**Changes:**
- Implemented sufficient changes from `docs/YM2612_GPGX_DIVERGENCES.md` (EG timing, phase modulation scaling, feedback strength, etc.) to accurately emulate the chip's behavior.
- Committed additional reversions of `SmpsSequencer` logic that were found to be causing the persistent reverberating sound when combined with the old YM2612 implementation.
**Result:** ✅ FIXED SIGNPOST SOUND! The signpost "ding" now decays correctly and matches the original hardware timbre.

---

## Current State (2026-01-24 - AFTER FULL FIXES)

**Current Status:**
- Signpost SFX (0xCF): ✅ Fixed. The metallic "phaser" effect and lingering reverb are gone.
- Music/SFX Timbre: ⚠️ Regressed. Many instruments across the soundtrack have lost their original colour or have incorrect envelopes since the core changes.

**Remaining Issues:**
- **Ring Sound (0xB1):** The iconic ring collect sound is "a little off" (likely envelope or feedback depth).
- **Music Instruments:** General regression in timbre/colour/envelope comparison to original Genesis output.
- **Persisting with Troubleshooting:** We need to keep refining the YM2612 implementation and SmpsSequencer to restore the audio quality of music and other SFX.

---

## Conclusion (Final Resolution)

The Signpost SFX issue was a multi-layered problem. Initial attempts to fix it within the YM2612 chip were hindered by fundamental scaling errors (EG rates, modulation depth, feedback strength). Once the core chip was aligned with Genesis Plus GX (GPGX), it was discovered that previous "fixes" in the `SmpsSequencer` were now redundant and actually causing new reverberation issues. Reverting those sequencer changes finally produced the correct signpost sound.

While the Signpost is now accurate, the major shifts in core emulation have caused widespread regressions in instrument timbre. Future work will focus on these regressions while maintaining the now-accurate signpost sound.
