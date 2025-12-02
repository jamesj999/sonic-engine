# YM2612 Algorithm Definitions

This document describes the 8 FM synthesis algorithms used in the YM2612 sound chip (Sega Genesis/Mega Drive). It translates the visual flow charts into text-based signal flow logic suitable for agents or screen readers.

## Terminology Legend

* **Operator (Op):** A sound generation unit (S1, S2, S3, S4).
* **Carrier (C):** An operator that outputs sound directly to the audio bus.
* **Modulator (M):** An operator that sends its signal into another operator to alter its frequency (timbre).
* **Feedback:** In the YM2612, **Operator 1 (S1)** always has a feedback loop, allowing it to modulate itself.
* **Signal Flow:** Notation `A -> B` means Operator A modulates Operator B.

---

## Algorithm 0: Four Serial Connection Mode
**Structure:** Single vertical stack.
* **Modulators:** S1, S2, S3
* **Carriers:** S4
* **Signal Flow:**
    `S1 -> S2 -> S3 -> S4 -> Output`
* **Description:** Maximum harmonic complexity. The signal passes through three stages of modulation before being heard. S1 modulates S2; the result modulates S3; that result modulates the final carrier S4.

## Algorithm 1: Three Double Modulation Serial Connection Mode
**Structure:** Two parallel modulators feeding an intermediate modulator.
* **Modulators:** S1, S2, S3
* **Carriers:** S4
* **Signal Flow:**
    1. `S1 -> S3`
    2. `S2 -> S3`
    3. `S3 -> S4 -> Output`
* **Description:** S1 and S2 act as parallel modulators that both affect S3 simultaneously. S3 then modulates the final carrier S4.

## Algorithm 2: Double Modulation Mode ①
**Structure:** Two separate branches merging into one carrier.
* **Modulators:** S1, S2, S3
* **Carriers:** S4
* **Signal Flow:**
    1. Branch A: `S2 -> S3 -> S4`
    2. Branch B: `S1 -> S4`
    3. Combined: `(Output of S3 + Output of S1) -> S4 -> Output`
* **Description:** The carrier S4 is modulated by two sources: a stacked pair (S2 modulating S3) and the feedback operator (S1) directly.

## Algorithm 3: Double Modulation Mode ②
**Structure:** Two separate branches merging into one carrier (Feedback Op is in the stack).
* **Modulators:** S1, S2, S3
* **Carriers:** S4
* **Signal Flow:**
    1. Branch A: `S1 -> S2 -> S4`
    2. Branch B: `S3 -> S4`
    3. Combined: `(Output of S2 + Output of S3) -> S4 -> Output`
* **Description:** Similar to Algorithm 2, but the roles are swapped. The carrier S4 is modulated by a stacked pair (S1 modulating S2) and an independent modulator (S3).

## Algorithm 4: Two Serial Connection and Two Parallel Modes
**Structure:** Split "Dual Voice" (Two independent 2-op stacks).
* **Modulators:** S1, S3
* **Carriers:** S2, S4
* **Signal Flow:**
    1. Stack A: `S1 -> S2 -> Output`
    2. Stack B: `S3 -> S4 -> Output`
* **Description:** This effectively splits the chip channel into two separate voices. One sound is created by the S1->S2 pair, and a separate sound is created by the S3->S4 pair. They are mixed at the output.

## Algorithm 5: Common Modulation 3 Parallel Mode
**Structure:** One modulator, three carriers.
* **Modulators:** S1
* **Carriers:** S2, S3, S4
* **Signal Flow:**
    1. `S1 -> S2 -> Output`
    2. `S1 -> S3 -> Output`
    3. `S1 -> S4 -> Output`
* **Description:** S1 modulates S2, S3, and S4 simultaneously. This creates three distinct carriers that share a common vibrato/timbre characteristic defined by S1.

## Algorithm 6: Two Serial Connection + Two Sine Mode
**Structure:** One 2-op stack and two independent sine waves.
* **Modulators:** S1
* **Carriers:** S2, S3, S4
* **Signal Flow:**
    1. Stack A: `S1 -> S2 -> Output`
    2. Direct: `S3 -> Output`
    3. Direct: `S4 -> Output`
* **Description:** S2 produces a complex FM sound (modulated by S1). S3 and S4 act as simple additive oscillators (producing pure sine waves) that are mixed in with the output of S2.

## Algorithm 7: Four Parallel Sine Synthesis Mode
**Structure:** Additive Synthesis (Four independent operators).
* **Modulators:** None (except S1 self-feedback)
* **Carriers:** S1, S2, S3, S4
* **Signal Flow:**
    1. `S1 -> Output`
    2. `S2 -> Output`
    3. `S3 -> Output`
    4. `S4 -> Output`
* **Description:** All four operators function independently as carriers. No operator modulates another. Used for Hammond organ-style additive synthesis or stacking four distinct frequencies.

---

## Quick Reference Table

| Algo | Stack Type | Carriers (Output) | Modulators |
| :--- | :--- | :--- | :--- |
| **0** | 1 x 4 (Serial) | S4 | S1, S2, S3 |
| **1** | 2 into 1 into 1 | S4 | S1, S2, S3 |
| **2** | 2 branches into 1 | S4 | S1, S2, S3 |
| **3** | 2 branches into 1 | S4 | S1, S2, S3 |
| **4** | 2 x 2 (Split) | S2, S4 | S1, S3 |
| **5** | 1 Mod / 3 Car | S2, S3, S4 | S1 |
| **6** | 1 Stack / 2 Direct | S2, S3, S4 | S1 |
| **7** | 4 Independent | S1, S2, S3, S4 | None |