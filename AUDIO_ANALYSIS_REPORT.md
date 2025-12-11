# Audio Engine Analysis Report

## Executive Summary
A detailed comparison between the project's audio implementation (`Ym2612Chip.java`, `PsgChip.java`) and the reference SMPSPlay/libvgm cores (`ym2612.c`, `sn76489.c`) has revealed a **Critical Discrepancy** in the YM2612 operator routing. This issue causes Operators 2 and 3 to be effectively swapped in the FM algorithms, leading to incorrect instrument timbres and modulation. This incorrect logic is also codified in the unit test `TestYm2612AlgorithmRouting.java`. Additionally, the PSG implementation lacks the anti-aliasing logic present in the reference core.

## YM2612 Discrepancies

### 1. Critical: Operator Routing Mismatch (Op2 vs Op3)
**Severity:** Critical (Audibly Incorrect)
**Reference:** `Ym2612Chip.java` lines 504-507 vs `ym2612.c` macros `DO_ALGO_x`.
**Test Reference:** `TestYm2612AlgorithmRouting.java`

**Analysis:**
The C reference core (`ym2612.c`) and its Java port example (`YM2612.java.example.txt`) define internal operator indices `S0`, `S1`, `S2`, `S3` as `0`, `2`, `1`, `3`. This maps the linear index 1 to Operator 3, and index 2 to Operator 2.
The algorithms in `ym2612.c` use input variables `in0`, `in1`, `in2`, `in3` assigned from these indices:
- `in1` = `SLOT[S1]` = `SLOT[2]` (Operator 3)
- `in2` = `SLOT[S2]` = `SLOT[1]` (Operator 2)

`Ym2612Chip.java` assigns these variables linearly:
```java
in1 = ch.ops[1].fCnt; // Operator 2
in2 = ch.ops[2].fCnt; // Operator 3
```
However, the `doAlgo` method uses the algorithm logic copy-pasted from the C core, which expects `in1` to be Operator 3 and `in2` to be Operator 2.

**Impact:**
- **Algorithm 4:** Should be `(Op1->Op3) + (Op2->Op4)`. Current Java implementation results in `(Op1->Op2) + (Op3->Op4)`.
- **Algorithm 0:** Should be `Op1 -> Op3 -> Op2 -> Op4`. Current Java implementation results in `Op1 -> Op2 -> Op3 -> Op4`.
- This fundamentally changes the sound of most instruments.
- The unit test `TestYm2612AlgorithmRouting.java` asserts this incorrect routing (e.g., Algo 4 expects Op1->Op2), suggesting the test was written to match the broken implementation rather than the hardware spec.

### 2. Register Write Timing
**Severity:** Major (Timing/Accuracy)
**Reference:** `Ym2612Chip.java` vs `ym2612.c`.

**Analysis:**
The C core implements a `Busy` flag duration. `Ym2612Chip.java` has the variables (`busyCycles`) but they don't seem to effectively block or delay writes in a way that mimics hardware, although `readStatus` checks it. More importantly, the `SmpsDriver` writes directly to the chip without checking the busy flag, whereas a real Z80 driver would wait. This is likely acceptable for a high-level emulation but noted for completeness.

### 3. SIN_LBITS Calculation
**Severity:** Minor
**Reference:** `Ym2612Chip.java` vs `ym2612.c`.

**Analysis:**
`Ym2612Chip.java` hardcodes `SIN_LBITS = 14`. The C code derives it: `26 - SIN_HBITS`. Since `SIN_HBITS` is 12, the result is 14, so they match effectively. However, the C code has a clamp `if (SIN_LBITS > 16) SIN_LBITS = 16`. This is a minor implementation detail.

## PSG (SN76489) Discrepancies

### 1. Lack of Anti-Aliasing (Band-Limited Synthesis)
**Severity:** Moderate/Major (Audio Quality)
**Reference:** `PsgChip.java` vs `sn76489.c`.

**Analysis:**
`sn76489.c` implements a "Super-high quality tone channel 'oversampling'" using `IntermediatePos` to handle waveform transitions that occur between sample points. `PsgChip.java` uses a simple step-decrement loop.
- **Missing:** Sub-sample precision for high-frequency tones.
- **Impact:** Aliasing artifacts, especially on high notes.

### 2. Volume Table Generation
**Severity:** Minor
**Reference:** `PsgChip.java`.

**Analysis:**
`PsgChip.java` generates the volume table using `Math.pow(2.0, i / -3.0)`. This correctly approximates the -2dB per step attenuation of the hardware. The values are slightly different from the lookup table in `sn76489.c` (likely due to scaling factor 8192 vs 4096), but the ratios are correct.

## Driver & SMPS Discrepancies

### 1. Instrument Operator Mapping
**Severity:** Verified Correct (but undermined by Chip bug)
**Reference:** `SmpsSequencer.java` -> `refreshInstrument`.

**Analysis:**
SMPS stores voice data in order: Op1, Op3, Op2, Op4.
`SmpsSequencer.java` correctly maps the TL (Total Level) bytes:
```java
int[] opMap = {0, 2, 1, 3}; // Op1(0), Op3(2), Op2(1), Op4(3)
```
This correctly handles the SMPS format. However, because `Ym2612Chip` swaps the algorithm usage of Op2 and Op3, the resulting sound is still wrong.

## Recommendations

1.  **Fix YM2612 Operator Routing:**
    Modify `Ym2612Chip.java` `doAlgo` method to swap the assignment of `in1` and `in2` (and corresponding `en` variables) to match the C core's expectation, OR modify the algorithms cases to swap usages of `in1`/`in2`.
    *Suggested Fix:*
    ```java
    // Current
    in1 = ch.ops[1].fCnt;
    in2 = ch.ops[2].fCnt;
    // ...
    // case 4: in1 += ch.opOut[1]; // Adds feedback to Op2 (Wrong)

    // Correction (Swap logic mapping)
    in1 = ch.ops[2].fCnt; // Map in1 to Op3
    in2 = ch.ops[1].fCnt; // Map in2 to Op2
    // Also swap en1/en2 assignments similarly
    ```
    *Note:* `TestYm2612AlgorithmRouting.java` must also be updated to reflect the correct routing.

2.  **Enhance PSG Quality:**
    Port the `IntermediatePos` logic from `sn76489.c` to `PsgChip.java` to reduce aliasing.

3.  **Verify LFO/Detune:**
    Once the operator routing is fixed, verify if the LFO modulation (which also uses `in1`/`in2` logic) behaves correctly. The discrepancy likely affects LFO amplitude modulation routing as well.
