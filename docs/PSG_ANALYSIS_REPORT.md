# Sonic 2 PSG Audio Analysis Report

## Executive Summary
This report details the findings of a comparative analysis between the `SmpsSequencer` implementation (Java) and the reference `SMPSPlay` driver (C) / `sn76489.c` core, specifically focusing on Sonic 2's PSG noise and envelope handling.

**Key Finding:** A significant discrepancy was identified in the **PSG Envelope Command processing**, where the Java implementation incorrectly interprets the `0x80` command as "Reset/Loop" instead of "Hold," which causes incorrect behavior for envelopes used in Sonic 2 (e.g., Attack envelopes).

## 1. PSG Chip Emulation (`PsgChip.java` vs `sn76489.c`)
The low-level emulation of the SN76489 chip in `PsgChip.java` is **highly accurate** and matches the reference `sn76489.c` implementation in all analyzed areas.

*   **Noise Generation:**
    *   **LFSR:** Correctly implements a 16-bit LFSR with taps at bits 0 and 3, using XOR feedback for White Noise.
    *   **Volume:** Correctly applies the 0.5x attenuation (half volume) for White Noise output, matching `sn76489.c`.
    *   **Frequency:** Correctly implements the frequency divider rates (16, 32, 64) and the "Tone 2 Match" mode.
*   **Volume Levels:**
    *   Uses the correct hardcoded volume table (max 4096) matching the "VolMode = Algo" / Hardware behavior expected by SMPSPlay.
*   **Output:**
    *   Correctly implements Bipolar output (-1.0 to 1.0) and "Intermediate Position" sampling for anti-aliasing.

## 2. PSG Envelope Processing Discrepancy
A critical logic difference was found in how PSG Envelope commands are interpreted.

### The Discrepancy
*   **Reference (`DefDrv.txt` for Sonic 2):** Explicitly defines `80 = Hold`.
    ```ini
    [EnvelopeCmds]
    80 = Hold
    ```
*   **Reference (`PSG 6.bin`):** The "Attack" envelope (used for fading in notes) ends with byte `0x80`:
    `03 03 03 02 02 ... 00 80`
    *   **Intended Behavior:** Fade volume from offset `03` down to `00` (Max Volume) and **HOLD** at `00`.
*   **Java Implementation (`SmpsSequencer.java`):**
    ```java
    if (val == 0x80) {
        // RESET / LOOP
        t.envPos = 0;
    }
    ```
    *   **Actual Behavior:** The envelope fades to `00` and then immediately loops back to the beginning (`03`), causing a "tremolo" or "pumping" effect instead of a sustained note.

### Remediation
Update `SmpsSequencer.java` to respect the Sonic 2 driver definition where `0x80` acts as a **HOLD** command for PSG envelopes, similar to `0x81`.

## 3. Driver Configuration & Timing
*   **Tempo Mode:** `SmpsSequencer` correctly implements the `Overflow2` (0x100 base) tempo accumulation logic used by Sonic 2.
*   **NoteOnPrevent:** Sonic 2 uses `NoteOnPrevent = Rest`. `SmpsSequencer` handles Rests (`0x80`) by stopping the note and returning early in `playNote`, effectively preventing re-articulation. This is functionally consistent with the requirement.

## 4. Remediation Plan
To align the Java implementation with the SMPSPlay reference for Sonic 2:

1.  **Modify `SmpsSequencer.processPsgEnvelope`:**
    *   Change the handling of command `0x80`.
    *   For Sonic 2 compatibility, `0x80` must set `t.envHold = true` (Hold) rather than resetting `t.envPos` (Loop).
    *   *Note:* If the engine needs to support multiple games, this should be configurable via `AbstractSmpsData` or a Driver Configuration object, as standard SMPS Z80 typically uses `0x80` for Loop. However, for the specific scope of "Sonic 2 features," hardcoding or flagging this change is necessary.

2.  **Verify `0x81`:**
    *   `PSG 1.bin` ends in `0x81`. `SmpsSequencer` correctly handles `0x81` as Hold.

## 5. Conclusion
The core audio generation is accurate. The primary behavioral difference lies in the interpretation of the PSG Envelope `0x80` command. Fixing this will resolve potential volume envelope issues (e.g., unintended looping of attack phases) in Sonic 2.
