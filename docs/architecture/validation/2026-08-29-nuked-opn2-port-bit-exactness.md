# Nuked-OPN2 port: bit-exactness against the pinned C build (round 1)

Status: validation record. Branch `feature/ai-nuked-opn2-fm-core`, port under
test at `74cd26969` (port commit `1c5eacf39`, facade `f6c7a59b2`), worktree
`.worktrees/nuked-opn2`. Contract:
[2026-08-29-nuked-opn2-port-contract.md](../designs/2026-08-29-nuked-opn2-port-contract.md),
stage 1 ("bit-exactness against the pinned C build") and the stage 2 tolerance
comparison used here only as a sanity check.

**Verdict: the Java port `com.openggf.audio.synth.nuked.NukedOpn2` is
cycle-for-cycle identical to the pinned `ym3438.c` on every script run — 732
runs, 1 201 398 116 clocked cycles (939.8 s of chip time), every MOL/MOR pin
value equal, every status/IRQ read equal. There is no first-mismatch table
because there is no first mismatch.**

The sanity comparison against the old core found nothing wrong with the port,
but it did surface one facade-level defect that is recorded below as a finding
for the adapter stage (key-on/key-off latches lost at the facade's 14-cycle
write pacing), plus the expected level, resting-level and onset differences.

## Pins

| Item | Value |
|---|---|
| Upstream | Nuked-OPN2 `335747d78cb0abbc3b55b004e62dad9763140115`, `ym3438.c` 1.0.12 (`tools/audio/nuked-opn2/PIN.md`) |
| `ym3438.c` sha256 | `8fa385546f0f2d1c975d097002af00cd729ae2ae097c068e9c883ce08ddf3a76` (verified on the reference tree used for every C run) |
| C toolchain | `cc (GCC) 16.2.1`, `-O2`, Linux x86-64 |
| Java | OpenJDK 21.0.11 via Maven 3.9.16 |
| ROMs (write logs only) | `s1.gen` (Sonic 1 W REV01), `s2.gen` (Sonic 2 W REV01), `s3k.gen` (S3&K locked-on) at the project root |
| Source rule | Only the pinned `ym3438.c`/`ym3438.h` were read on the reference side. No GPGX, libvgm, MAME or BizHawk source and no line of the current `Ym2612Chip.java` body was consulted; the old core was compiled from `git show develop:...Ym2612Chip.java` into a scratch class without being read. |

## Method

### The two twins

- **C twin**: `tools/audio/nuked-opn2/harness/bitexact_harness.c`, built
  against the fetched pinned source. It drives the chip with `OPN2_Write` /
  `OPN2_Clock` / `OPN2_Read` / `OPN2_ReadIRQPin` and streams the raw
  `Bit16s` MOL/MOR value of **every internal cycle** as little-endian int16
  stereo — the chip's native output with nothing summed, scaled, or
  resampled. Per-cycle equality subsumes equality of the 24-cycle output
  samples the engine consumes.
- **Java twin**: `src/test/java/com/openggf/audio/synth/nuked/NukedOpn2ScriptRunner.java`,
  driving `NukedOpn2.write` / `clock` / `read` / `readIrqPin` on the bare
  port (no facade, resampler, mixer or write queue in the path) and
  streaming the same format.

Both parse one grammar: `type <flags>` (`OPN2_SetChipType`), `pace <a> <d>`,
`write <port2bit> <data>` (raw strobe), `reg <part> <reg> <val>` (address
strobe, `a` cycles, data strobe, `d` cycles; default 1 / 13, the pacing the
facade documents in `adapter_parity_harness.c`), `clock <n>`, `at <frame>`
(clock until `frame * 24` cycles have run), `status <port>`, `irq`, `dump`.
Status, IRQ and dump lines go to a side log that is compared as text. The
`dump` command (selected `ym3438_t` fields on both sides) exists for
bisecting a mismatch by state; it was not needed.

Comparison: `cmp` of the binary streams and of the side logs, plus the
FNV-1a fold of the stream (`mol & 0xffff`, `mor & 0xffff` per cycle) and of
the side log that the committed test asserts.

### Scripts

Every body is run under all four chip-type flag sets (`0` YM3438, `1`
`ym3438_mode_ym2612`, `2` `ym3438_mode_readmode`, `3` both — the engine's
chip type 0 maps to 3, types 1/2 map to 2).

| Set | Bodies | Runs | What it covers |
|---|---|---|---|
| (a) smoke | 1 | 4 | The `TestNukedOpn2PortSmoke` patch at its own 4/28 pacing, 4096 frames. The type-3 checksum reproduces the value pinned in that test (`0xba1b4bba3bcb91bd`). |
| (b) sweep | 106 | 424 | All 8 algorithms × feedback 0/7 with release tails; AR (12 values), DR (9), SR (6), RR (6), SL (4), KS × block (12); SSG-EG modes 8–15 with instant and slow attack and a mid-script retrigger (16); LFO frequency 0–7 with AMS/PMS on six keyed channels, plus an LFO on/off/reset toggle; DT 0–7 × MUL {0,1,7,15} over blocks 2/5/7 (32); channel 3 special mode with mode toggling; CSM with status/IRQ reads; timer A and B at four periods with reset/enable sequences, both timers with repeated resets, and the disabled-flag case; DAC ramp with enable on/off and FM6 keyed underneath, plus a fine-grained raw-strobe `0x2A` stream; LSI test registers `0x21`/`0x2C` with test-data status reads; pan/TL sweeps on six channels; partial and invalid key-on; strobes with no clocks between them. |
| (b) fuzz | 3 | 12 | Three seeded 20 000-step streams over the whole bus: raw strobes on all four ports, random `0x28`, mode registers, operator/channel registers, random clock gaps 0–40, status reads on all ports, IRQ reads. |
| (c) SMPS logs | 38 | 152 | Real write logs from `FmSfxRenderTool --rate internal`: S1 SFX `A3 A6 AC AF B5 BE C4 C6 CC CE CF` (11), S2 SFX `A3 A6 AC AE B5 BC C1 C4 CC CF D0` (11), S3K SFX `33 35 3A 3C 3D 45 4D 4E 50 51` (10), and 10 s intros of S1 `81` (GHZ) and `82` (LZ), S2 `81` (EHZ) and `8C` (CPZ), S3K `01` (AIZ1) and `03` (HCZ1). Four requested SFX (`A0` on S1/S2, `43` and `48` on S3K) issue no YM writes and were dropped. |

The SMPS logs are captured at the chip's own output rate (`clock / 144`), so
each frame stamp is exactly 24 cycles and `at <frame>` places every write on
the cycle the engine placed it; consecutive writes in one frame are paced at
the facade's 1/13 rule. Internally scheduled `0x2A` DAC sample writes are not
observed by `ChipWriteObserver` (contract, "Write observer"), so the song logs
carry the `0x2B` enable but no sample data.

Regeneration: `tools/audio/nuked-opn2/harness/regenerate-bitexact-expectations.sh
<pinned src>` rebuilds the C twin, expands every body under the four types and
rewrites `src/test/resources/audio/nuked-opn2/port/expected.txt`;
`generate-bitexact-scripts.py` and `log-to-bitexact-script.py` produce the
bodies.

## Results

### Per-script equality (C vs Java, native per-cycle stream)

| Set | Runs | Cycles compared | PCM bytes equal | Side log equal |
|---|---|---|---|---|
| smoke | 4 | 397 824 | 4 / 4 | 4 / 4 |
| sweep + fuzz | 576 | 470 M | 576 / 576 | 576 / 576 |
| SMPS logs | 152 | 731 M | 152 / 152 | 152 / 152 |
| **total** | **732** | **1 201 398 116** | **732 / 732** | **732 / 732** |

Side logs carry 14 444 lines over 60 runs; the status bytes are not
degenerate (6 946 non-zero reads: busy `0x80`, timer A/B flags `1`/`2`/`3`,
test-data values `0x20`/`0xFF`, and 1 560 IRQ-high reads), so the
timer/CSM/test-register paths are compared on live values, not on zeros.

The committed pin `TestNukedOpn2BitExactScripts` (732 dynamic tests, one per
body × type) passed under Maven: `Tests run: 732, Failures: 0, Errors: 0`
(50 s). `TestNukedOpn2PortSmoke` and the facade pin
`TestYm2612ChipNukedParity` ran green in the same invocation.

### First-mismatch table

None. No script produced a differing sample or side-log line, so no
bisection by state dump was performed and no port function is implicated.

## Old-core sanity comparison (information, not failures)

The 42 captured logs were replayed through the new facade (`Ym2612Chip`, the
Nuked port behind it) and through the old develop core built as a scratch
class, both at the internal rate with one-frame render granularity and writes
applied before the frame they were logged on. Per key-on segment (first 8192
frames after each key-on, resting level subtracted): dominant frequency by
Hann-windowed FFT peak, RMS level, normalised cross-correlation at the best
lag within ±96 frames, and onset position.

| Observation | Value | Reading |
|---|---|---|
| Resting level (chip scale, all channels silent) | new **576**, old **768** | The +288 / +384 LSB figures after `MASTER_GAIN_SHIFT` already recorded in `known-discrepancies.md`; the model-1 ladder resting level of the die model versus the old core's constant. |
| Level | new is **−2.5 dB** on every SFX segment (189 segments, min −2.93, median −2.51, max −1.93 dB; SFX peaks 6312 vs 8416 = exactly 3/4) | A constant scale difference, the "about 2.5 dB lower" already in `CHANGELOG.0.6.md`. |
| Dominant frequency | 507 / 671 segments within 0.5 %, 44 within 2 %, 9 within 10 %, 111 beyond | Every clean tone agrees (rings, springs, skids, S3K `51`, GHZ intro 85/85). The remainder are noise-like high-feedback patches where the FFT peak is not a pitch (S1/S2 `C4` bomb, `CF` signpost with near-Nyquist content, S2 `BC` spindash, S3K `3C` roll), multi-channel windows in the songs, and song segments where the facade dropped the key-on (next row). |
| Cross-correlation | ≥ 0.89 on tonal SFX; 0.09–0.5 on the noise-like patches | The die-accurate operator pipeline and the old table core differ in phase/feedback detail for chaotic patches; no silence or pitch error is involved. |
| Onset | new later by 19–22 frames (0.4 ms) on SFX whose voice loads in the same frame as the key-on; ≤ 8 frames otherwise | The facade drains each write's bus cycles synchronously (30 voice writes × 14 cycles ≈ 17.5 frames) whereas the old core applied writes instantly. |
| Silence | none: every FM log has audible output in both cores (PSG-only SFX excluded) | |

### Finding for the adapter stage: key latches lost at 1/13 pacing

The song comparisons showed segments where the old core plays a note and the
new facade is at rest (e.g. LZ `82` frame 102 982: channel 1 keyed with
`0x28 = 0xF1`, immediately followed by the driver's `0x28 = 0x02` and
`0x28 = 0x04` key-offs for the next channels). Probed at the pin level on the
bit-exact port: with `pace 1 13` channel 1's four slots sit in release at
attenuation 1023 at frame 103 500; with `pace 4 28` or `pace 1 24` they are in
decay with levels 0–7, i.e. keyed.

Mechanism (`ym3438.c`, `OPN2_KeyOn`): a `0x28` data write latches
`mode_kon_channel` / `mode_kon_operator`, and the latch is consumed only on
the cycle where `chip->cycles == chip->mode_kon_channel`, which is at most 23
cycles away. The next `0x28` data strobe overwrites the latch. With one
address cycle and 13 data cycles per write, two consecutive `0x28` writes are
14–15 cycles apart, so a latch set while `cycles` has just passed the channel
number is overwritten before it lands. Counting overwritten latches on the
SMPS logs at 1/13 pacing:

| Log | Key-ons lost | Key-offs lost |
|---|---|---|
| S1 `81` GHZ | 27 / 189 | 8 / 232 |
| S1 `82` LZ | 11 / 73 | 7 / 125 |
| S2 `81` EHZ | 65 / 138 | 8 / 178 |
| S2 `8C` CPZ | 2 / 118 | 25 / 184 |
| S3K `01` AIZ1 | 12 / 357 | 18 / 472 |
| S3K `03` HCZ1 | 8 / 231 | 25 / 339 |
| all 32 SFX | 0 | 5 (S1/S2 `CF`, S3K `50`) |

At `pace 4 28` (the 32-cycle busy window, the pacing `TestNukedOpn2PortSmoke`
uses) and at `pace 1 24` nothing is lost on any log. This is not a port
defect — the port and the C build agree on every cycle either way, and the
hardware really does drop a latch overwritten within its 24-cycle window — but
it is a defect in the facade's model of bus timing: the ROM drivers never
issue two `0x28` writes within 24 chip cycles (the 68k/Z80 drivers space them
by at least the next track's update, and wait on busy), so the facade must
hold each write for the 32-cycle busy window (or at minimum 24 cycles after a
`0x28` data strobe) before presenting the next one. `TestYm2612ChipNukedParity`
does not catch this because its scripts and expectations both use the 1/13
rule. This belongs to the adapter stage; it is outside this round's port
scope and the contract's stage-1 verdict.

### Pacing fix (adapter stage, 2026-08-29)

The facade now paces every write as a busy-polling driver does: address
strobe, 1 clock (consumed by `doIo`; an address write does not raise busy in
`ym3438.c`, and whether it does on silicon is the behaviour-vectors doc's open
question 1, so the smallest hold that latches the address before the data
strobe is used), data strobe, then 34 clocks — the strobe is consumed on the
first clock (`doIo` raises `write_busy`), the status byte reflects it from the
second (`busy = write_busy`), and `write_busy_cnt` holds busy for 32 cycles
(behaviour-vectors doc REG-06, "Address latch behaviour"; asserted by
`TestYm2612HardwareBehaviour.reg06BusyFlagLastsThirtyTwoInternalCycles`), so
34 is the first clock at which a status poll reads not-busy. The DAC's `0x2A`
stream holds the same window; nothing keys on the register. `Ym2612Chip`
(`ADDRESS_SETTLE_CYCLES` / `DATA_SETTLE_CYCLES`) and the adapter parity
harness (`ADDRESS_HOLD` / `DATA_HOLD`) carry the same two constants, and
`src/test/resources/audio/nuked-opn2/adapter/expected.txt` was regenerated
from the C build at the new pacing (68 scripts; frame counts unchanged,
checksums changed): `TestYm2612ChipNukedParity` 68/68 pass.

The port-level fixtures were not touched (their `pace` lines are script
data): `regenerate-bitexact-expectations.sh` re-run against the pinned C build
left `port/expected.txt` byte-identical, and `TestNukedOpn2BitExactScripts`
passes 732/732.

Overwritten `0x28` latches on the SMPS logs at the new pacing (the probe from
the finding above, `pace 1 34`):

| Log | Key-ons lost | Key-offs lost |
|---|---|---|
| S1 `81` GHZ | 0 / 189 | 0 / 232 |
| S1 `82` LZ | 0 / 73 | 0 / 125 |
| S2 `81` EHZ | 0 / 138 | 0 / 178 |
| S2 `8C` CPZ | 0 / 118 | 0 / 184 |
| S3K `01` AIZ1 | 0 / 357 | 0 / 472 |
| S3K `03` HCZ1 | 0 / 231 | 0 / 339 |
| all 32 SFX | 0 / 370 | 0 / 442 |

Audible check on the LZ `82` segment above, through `Ym2612Chip` itself at
its internal rate with the same write log: channel 1's four slots are at
attenuation 1023 in release at frame 102 981, and after the frame-102 982
`0x28 = 0xF1` (followed by the `0x02` / `0x04` key-offs) they read
`0/3/0/0` in attack/decay at frame 102 990, `7/0/2/0` in decay at frame
103 500 and `16/0/3/0` at frame 104 000 — the note sounds, where the 1/13
facade left the channel at 1023.
`TestYm2612HardwareBehaviour.FacadeContract.consecutiveKeyWritesThroughTheFacadeBothLand`
pins the behaviour through the public API for every ordered channel pair
(key-on then key-off); with `DATA_SETTLE_CYCLES` set back to 13 it fails on
the first pair.

## Files

- C twin, generators and regeneration script:
  `tools/audio/nuked-opn2/harness/{bitexact_harness.c,generate-bitexact-scripts.py,log-to-bitexact-script.py,regenerate-bitexact-expectations.sh}`
- Java twin and pin: `src/test/java/com/openggf/audio/synth/nuked/{NukedOpn2ScriptRunner,TestNukedOpn2BitExactScripts}.java`
- Fixtures: `src/test/resources/audio/nuked-opn2/port/` (183 gzip script bodies, `expected.txt`)
- Write-log capture: `src/main/java/com/openggf/tools/audio/FmSfxRenderTool.java`
- Scratch (not committed): the raw C and Java streams (4.6 GB), the
  old-vs-new `summary.tsv` / `segments.tsv`, and the lost-latch probe under
  the session scratchpad `nuked/bitexact/`.
