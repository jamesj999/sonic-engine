# YM2612 Java vs GPGX Divergence Report

Generated: 2026-01-24
Updated: 2026-01-24 (status review)

Scope: Compare `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` with
`docs/gensplusgx/ym2612.c` (Genesis Plus GX YM2612 core).

## Status Legend
- ✅ **FIXED** - Implemented to match GPGX
- ⚠️ **PARTIAL** - Partially implemented, some differences remain
- 🛠️ **ATTEMPTED** - Implementation in progress; not yet verified against GPGX
- ❌ **DIVERGENT** - Not yet aligned with GPGX

## Reference notation
- GPGX refs use: `docs/gensplusgx/ym2612.c:line`
- Java refs use: `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java:line`

## Divergences, discrepancies, and missing features

### 1) Envelope Generator timing and rate tables ✅ FIXED
GPGX:
- EG uses table-driven increments (`eg_inc`, `eg_rate_select`, `eg_rate_shift`) with a
  global EG counter that advances every 3 samples. The counter is 12-bit and skips 0.
  (`docs/gensplusgx/ym2612.c:209`, `docs/gensplusgx/ym2612.c:242`,
  `docs/gensplusgx/ym2612.c:293`, `docs/gensplusgx/ym2612.c:1066`,
  `docs/gensplusgx/ym2612.c:2065-2079`)
Java:
- **NOW IMPLEMENTED:** Uses `EG_INC`, `EG_RATE_SELECT`, `EG_RATE_SHIFT` tables matching GPGX.
- Global `egCnt` counter (12-bit, cycles 1-4095, skips 0).
- `egTimer` counts 0-2 before incrementing egCnt (3-sample stepping).
- Per-operator rate cache (`egShAr`, `egSelAr`, etc.) updated on parameter changes.
- `advanceEgOperator()` uses rate-gated updates: `!(egCnt & ((1 << shift) - 1))`.
- Exponential attack: `volume += ((~volume) * inc) >> 4`.
  (Lines 91-152, 304-338, 850-870, 1000-1081, 1155-1171)
Impact:
- ✅ Now matches GPGX EG timing behavior.

### 2) LFO PM/AM waveform, timing, and PM table 🛠️ ATTEMPTED
GPGX:
- LFO is a 128-step inverted triangle updated using discrete sample counts, with
  AM depth shifts and PM lookup tables based on FNUM bits. PM does not modify block
  or keyscale code. (`docs/gensplusgx/ym2612.c:365`, `docs/gensplusgx/ym2612.c:385`,
  `docs/gensplusgx/ym2612.c:484`, `docs/gensplusgx/ym2612.c:1037-1062`,
  `docs/gensplusgx/ym2612.c:1295`, `docs/gensplusgx/ym2612.c:1321`)
Java:
- **ATTEMPTED:**
  - ✅ 128-step inverted triangle waveform
  - ✅ LFO updated AFTER channel calculation
  - ✅ PM now uses FNUM-based lookup table (`lfo_pm_table`)
  - ✅ AM uses `AM & AMmask` with channel AMS depth shifts
Status:
- Pending verification against GPGX.
Impact:
- Waveform shape is correct, but PM/AM depth mapping still differs from GPGX.

### 3) SSG-EG accuracy and key-off inversion handling 🛠️ ATTEMPTED
GPGX:
- Tracks `ssgn`, updates SSG inversion on key-on/off, and runs a dedicated SSG-EG
  update process with correct inversion and hold/loop behavior.
  (`docs/gensplusgx/ym2612.c:640-706`, `docs/gensplusgx/ym2612.c:1222-1278`,
  `docs/gensplusgx/ym2612.c:1610-1616`)
Java:
- **ATTEMPTED:** Added `ssgn` inversion flag, key-off conversion, and per-sample SSG update pass.
- SSG-EG test is @Ignored due to known inaccuracies.
Status:
- Pending verification against GPGX.
Impact:
- Misses GPGX fix for inverted attenuation on key-off and other SSG-EG edge cases.

### 4) DAC precision, ladder effect, and chip types ❌ DIVERGENT
GPGX:
- DAC output is converted to 14-bit, supports chip types (discrete/integrated/enhanced),
  applies DAC quantization via `op_mask`, and emulates the discrete ladder effect.
  (`docs/gensplusgx/ym2612.c:621-622`, `docs/gensplusgx/ym2612.c:1980-1984`,
  `docs/gensplusgx/ym2612.c:2110-2129`, `docs/gensplusgx/ym2612.c:2160-2180`)
Java:
- DAC uses 8-bit samples with optional interpolation and simple gain.
- No quantization, ladder effect, or chip type selection.
Impact:
- Misses GPGX DAC distortion/precision behavior, and the 9-bit vs 14-bit DAC modes.

### 5) Algorithm memory (MEM) delay path 🛠️ ATTEMPTED
GPGX:
- Uses a one-sample delay memory (`mem_value`) with per-algorithm routing.
  (`docs/gensplusgx/ym2612.c:631-633`, `docs/gensplusgx/ym2612.c:931-949`,
  `docs/gensplusgx/ym2612.c:1447-1481`)
Java:
- **ATTEMPTED:** Added per-channel one-sample `memValue` delay and algorithm routing.
- Algorithms 0-3 and 5 now route via MEM; 4/6/7 bypass MEM (GPGX-style).
Status:
- Pending verification against GPGX.
Impact:
- Algorithms 0-3 and 5 rely on the MEM delay in GPGX; Java will diverge for those.
  Algorithms 4, 6, and 7 do not use MEM in GPGX.

### 6) Timers and CSM key-off gating 🛠️ ATTEMPTED
GPGX:
- Timer A/B have specific internal stepping and overflow handling; CSM key-off is
  only issued if Timer A does not retrigger. (`docs/gensplusgx/ym2612.c:782-803`,
  `docs/gensplusgx/ym2612.c:825`, `docs/gensplusgx/ym2612.c:2137-2152`,
  `docs/gensplusgx/ym2612.c:2157`)
Java:
- **ATTEMPTED:** Added CSM key-on on Timer A overflow and key-off gating when no retrigger.
- Status bit setting now gated by timer enable flags.
Status:
- Pending verification against GPGX.
Impact:
- Misses GPGX timer overflow behavior and CSM key-off timing.
- **Java's CSM is effectively non-functional** after first key-on.

### 7) Table resolutions and scaling (SIN/ENV/TL) 🛠️ ATTEMPTED
GPGX:
- Uses `ENV_BITS=10`, `SIN_BITS=10`, `ENV_STEP=128.0/ENV_LEN`, and a TL table sized
  for real chip resolution. (`docs/gensplusgx/ym2612.c:153-185`)
- `op_calc` uses `(env << 3) + sin_tab[...]` for envelope scaling.
Java:
- **ATTEMPTED:** Switched to `ENV_BITS=10`, `SIN_BITS=10`, `ENV_STEP=128/ENV_LEN`.
- Rebuilt TL/SIN tables using GPGX formulas.
- `opCalc` now uses `(env << 3) + sin_tab[...]` and GPGX PM scaling.
- `volOut` cache now uses `volume + tll`.
Status:
- Pending verification against GPGX.
Impact:
- **CRITICAL:** Missing `env << 3` in opCalc affects attenuation curve and silence detection.
- Amplitude scaling differs from GPGX hardware-accurate tables.
- This is likely a major cause of remaining audio issues (fade-out behavior).

### 8) Phase generator, detune overflow, and KSR handling ❌ DIVERGENT
GPGX:
- Detune/multiple uses fixed-point tables and includes detune overflow behavior;
  `set_ar_ksr` includes a hardware-verified AR max rule. (`docs/gensplusgx/ym2612.c:952-999`)
Java:
- `calcFIncSlot` uses floating-point multiply for detune.
- No detune overflow handling or AR max gating rule.
Impact:
- Detune and AR behavior can diverge, especially at extreme rates or keyscale settings.

### 9) Key-on logic differences 🛠️ ATTEMPTED
GPGX:
- Key-on resets phase and uses AR+KSR threshold logic to force immediate decay/sustain.
  (`docs/gensplusgx/ym2612.c:640-663`)
Java:
- **ATTEMPTED:** AR+KSR threshold logic added for immediate decay/sustain on fast attacks.
Status:
- Pending verification against GPGX.
Impact:
- Re-triggering behavior correct, but fast-attack edge cases differ from GPGX.

### 10) Write-time EG state corrections 🛠️ ATTEMPTED
GPGX:
- TL/AR/SL/SSG writes perform write-time corrections: SL write can trigger DECAY→SUS
  state transition, SSG inversion conversions update `vol_out`, and AR-max blocking
  is evaluated at write time. Stores cached `vol_out` updated by these setters.
  (`docs/gensplusgx/ym2612.c:960-999`, `docs/gensplusgx/ym2612.c:1022-1029`,
  (`docs/gensplusgx/ym2612.c:1610-1616`)
Java:
- **ATTEMPTED:** SL-triggered DECAY→SUS state transition, SSG write-time conversion, and AR-max attack blocking.
Status:
- Pending verification against GPGX.
Impact:
- TL/env changes take effect correctly. Write-time edge-case state corrections missing.

### 11) Feedback shift mapping ✅ FIXED
GPGX:
- Feedback shift is `SIN_BITS - fb` where `SIN_BITS=10`, so fb=1→9, fb=7→3.
  (`docs/gensplusgx/ym2612.c:1742`, `docs/gensplusgx/ym2612.c:172`)
Java:
- **NOW IMPLEMENTED:** Uses `(10 - fb)` for fb in 1..7, so fb=1→9, fb=7→3.
- fb=0 uses shift of 31 (effectively disabled).
  (Line 809)
Impact:
- ✅ Now matches GPGX feedback strength.

### 12) LFO update order relative to channel output ✅ FIXED
GPGX:
- LFO advances after channel calculation each sample. (`docs/gensplusgx/ym2612.c:2050-2063`)
Java:
- **NOW IMPLEMENTED:** LFO values read BEFORE update, counter incremented AFTER channel calc.
  (Lines 1116-1125, 1150-1153)
Impact:
- ✅ Now matches GPGX LFO timing.

### 13) Address/data port behavior 🛠️ ATTEMPTED
GPGX:
- Uses an address latch and data port semantics that match hardware.
  (`docs/gensplusgx/ym2612.c:1961-1974`)
Java:
- **ATTEMPTED:** Added address latch and data port semantics; `write()` now routes through latch.
Status:
- Pending verification against GPGX.
Impact:
- Misses GPGX port behavior fixes (not critical for SMPS driver usage).

### 14) Operator output masking (DAC quantization) 🛠️ ATTEMPTED
GPGX:
- Applies per-operator DAC bitmasking via `opmask` parameter in `op_calc`, supporting
  chip-type-specific quantization. (`docs/gensplusgx/ym2612.c:1418-1425`,
  `docs/gensplusgx/ym2612.c:636`, `docs/gensplusgx/ym2612.c:1443-1444`)
Java:
- **ATTEMPTED:** `opCalc` applies per-operator output masks based on chip type.
Status:
- Pending verification against GPGX.
Impact:
- No support for discrete/integrated chip type quantization (minor audio difference).

### 15) AM depth calculation method 🛠️ ATTEMPTED
GPGX:
- AM uses `volume_calc(OP) = vol_out + (AM & AMmask)` with LFO_AM as a 0-126 triangle.
  (`docs/gensplusgx/ym2612.c:1416`, `docs/gensplusgx/ym2612.c:1054-1057`)
Java:
- **ATTEMPTED:** AM now applied as `AM & AMmask` and AMS depth uses GPGX shift table.
Status:
- Pending verification against GPGX.
Impact:
- AM depth scaling still differs from GPGX.

### 16) SSG-EG 4x rate multiplier 🛠️ ATTEMPTED
GPGX:
- SSG-EG decay/sustain/release increments are multiplied by 4 (`4 * eg_inc[...]`).
  (`docs/gensplusgx/ym2612.c:1113`, `docs/gensplusgx/ym2612.c:1148`,
  `docs/gensplusgx/ym2612.c:1183`)
Java:
- **ATTEMPTED:** 4x multiplier applied to SSG-EG decay/sustain/release.
Status:
- Pending verification against GPGX.
Impact:
- SSG-EG envelopes decay 4x slower than GPGX.

### 17) LFO reset state when disabled ✅ FIXED
GPGX:
- Disabling LFO holds a reset state: `LFO_AM=126`, `LFO_PM=0`, counters cleared.
  (`docs/gensplusgx/ym2612.c:1525-1538`)
Java:
- **NOW IMPLEMENTED:** Default `envLfo = 126` when `lfoInc == 0`.
- `lfoCnt` cleared when LFO disabled.
  (Lines 644-649, 1118-1119)
Impact:
- ✅ Now matches GPGX LFO reset state.

### 18) Timer control semantics and status reset 🛠️ ATTEMPTED
GPGX:
- `set_timers` handles load bits, status flag reset, and CSM key-off on mode change.
  CSM key-on occurs on Timer A overflow, with key-off gated in the render loop.
  (`docs/gensplusgx/ym2612.c:825-860`, `docs/gensplusgx/ym2612.c:2137-2149`)
Java:
- **ATTEMPTED:** Timer load on 0→1 transitions, status reset mask, and CSM gating added.
Status:
- Pending verification against GPGX.
Impact:
- Timer status flags, reload behavior, and CSM key-on/off timing diverge.

### 19) Output clipping range ✅ FIXED
GPGX:
- Per-channel output clipped to 14-bit range (-8192..8191).
  (`docs/gensplusgx/ym2612.c:2065-2097`)
Java:
- **NOW IMPLEMENTED:** Asymmetric clipping: `LIMIT_CH_OUT_POS = 8191`, `LIMIT_CH_OUT_NEG = -8192`.
  (Lines 52-53, 1223-1225)
Impact:
- ✅ Now matches GPGX output clipping.

### 20) Core timebase / sample-rate coupling ✅ FIXED
GPGX:
- Runs at the chip's internal sample rate (~53.267 kHz = 7670453 / 144) and does not
  incorporate host sample rate into core timing. Changelog explicitly states "removed
  input clock / output samplerate frequency ratio".
  (`docs/gensplusgx/ym2612.c:43-46`)
Java:
- **NOW IMPLEMENTED:** `INTERNAL_RATE = CLOCK / 144.0` (~53267 Hz).
- Renders at internal rate with linear interpolation resampling to 44.1 kHz output.
- `resampleAccum` tracks position; `renderOneSample()` generates at internal rate.
- LFO_INC_TAB uses internal rate for GPGX-accurate LFO speeds.
  (Lines 11-16, 293-302, 1088-1109)
Impact:
- ✅ Core timebase now matches GPGX hardware-accurate rate.

### 21) DAC amplitude scaling 🛠️ ATTEMPTED
GPGX:
- DAC output conversion: `(v - 0x80) << 6` (14-bit, ~64x gain).
  (`docs/gensplusgx/ym2612.c:1979-1981`)
Java:
- **ATTEMPTED:** DAC gain adjusted to `<<6` (64x) to match GPGX `(v - 0x80) << 6`.
Status:
- Pending verification against GPGX.
Impact:
- Java DAC output is 2x louder than GPGX before any ladder/quantization effects.

### 22) Timer B multi-overflow handling 🛠️ ATTEMPTED
GPGX:
- Timer B reload uses a loop: `do { TBC += TBL; } while (TBC <= 0);` to handle
  multiple overflows in a single update step.
  (`docs/gensplusgx/ym2612.c:815-820`)
Java:
- **ATTEMPTED:** Timer B reload loops until counter > 0.
Status:
- Pending verification against GPGX.
Impact:
- Java can miss multiple Timer B overflows if tick scaling or update step is large.

---

## Summary

| Status | Count | Items |
|--------|-------|-------|
| ✅ FIXED | 6 | #1 (EG timing), #11 (feedback), #12 (LFO order), #17 (LFO reset), #19 (clipping), #20 (timebase) |
| ⚠️ PARTIAL | 0 | — |
| 🛠️ ATTEMPTED | 14 | #2 (LFO PM/AM), #3 (SSG-EG), #5 (MEM delay), #6 (timers), #7 (table scaling), #9 (key-on), #10 (write-time EG), #13 (ports), #14 (op mask), #15 (AM depth), #16 (SSG 4x), #18 (timer reset), #21 (DAC gain), #22 (Timer B) |
| ❌ DIVERGENT | 2 | #4 (DAC), #8 (detune) |

## Priority Fixes for Signpost SFX Issue

Based on the debug diary analysis, the most likely remaining cause of audio issues is:

1. **#7 Table scaling (CRITICAL):** Implementation attempted; verify against GPGX
   - GPGX: `p = (env << 3) + sin_tab[...]`
   - Java: `p = SIN_TAB[idx] + env` (env NOT shifted)
   - This affects when silence is detected and how fade-out behaves

2. **#5 MEM delay path:** Implementation attempted; verify against GPGX
   - Currently using opOut[] for feedback only, not inter-operator modulation delay

---

## Architectural Note

**Divergence #20 (timebase) has been resolved.** The implementation now runs at ~53.267 kHz
internally with resampling to 44.1 kHz output, matching GPGX's hardware-accurate timing.
