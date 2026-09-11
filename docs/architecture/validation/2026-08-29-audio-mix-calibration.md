# Audio mix calibration: FM:PSG balance after the two core rewrites

**Date:** 2026-08-29
**Branch / commit:** `feature/ai-audio-mix-calibration` (worktree `.worktrees/audio-mix`),
merge `1cb3a0ab5` of `feature/ai-psg-clean-room` into `feature/ai-nuked-opn2-fm-core`
(`48e3b9a51`), compared against `develop` at `8290558c4`.
**Scope:** the relative level of the YM2612 and SN76489 paths at
`VirtualSynthesizer.renderFrames`, and nothing inside either chip.

## Verdict

**Pre-rewrite parity, uncalibrated against hardware.** No capture with both FM
music and PSG sound in it exists in the repository (§4), so the balance was not
measured; it was restored numerically to what `develop` had. The single balance
constant is `VirtualSynthesizer.PSG_PREAMP_PERCENT = 38`, applied through
`PsgChip.configure(38, 0xFF)` from the mixer's constructor. Neither chip's
internal scale was changed. The residual against `develop`'s ratio is +0.10 dB
(the PSG sits 0.10 dB lower than a perfect restoration would put it), the
granularity of a whole-percent preamp. Headroom improved everywhere (§5).

## 1. What moved

Both chip cores were rewritten on the two branches merged here, and each
rewrite changed its own chip's full-scale output:

| Chip | `develop` core | Full scale on `develop` | This branch | Full scale here |
|---|---|---|---|---|
| PSG | Genesis Plus GX-derived `PsgChip` | `PSG_MAX_VOLUME = 2800` x built-in `DEFAULT_PREAMP = 150 %` = **4200** per channel (the old class's constants; `docs/architecture/validation/2026-08-29-psg-clean-room-capture-comparison.md` §1, "The reference emits `PSG_MAX_VOLUME = 2800` full scale"; the preamp precedent is GPGX's `psg_preamp = 150` cited in `docs/architecture/designs/2026-08-29-psg-clean-room-contract.md` "Mixer-level policy") | Clean-room SN76489, `PsgChip.FULL_SCALE = 8191`, constructor default `configure(100, 0xFF)` (`PsgChip.java` §5 table, `LEVEL[0] = 8191`) | **8191** per channel at unity preamp |
| FM | Genesis Plus GX-derived `Ym2612Chip` | nominal **8191** per channel (`docs/status/known-discrepancies.md`, "YM2612 Output Scale", Original Implementation) | Nuked-OPN2 facade: 24-cycle pin sum, 768 per full-scale channel, `<< OUTPUT_SHIFT (3)` (`Ym2612Chip.java:100`, class Javadoc) | **6144** per channel |

The mixer between the chips and the presentation path did not move:
`VirtualSynthesizer.renderFrames` sums both chips into one `int` buffer,
applies `MASTER_GAIN_SHIFT = 1` (`VirtualSynthesizer.java:14`, the `>> 1` the
PSG capture comparison noted) and clips to 16 bits. On `develop` there was no
per-chip preamp or `configure` call anywhere in production (`git show
develop:src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java` has no
`configure`/`preamp`; `grep -rn configure src/main/java` on this branch finds
none outside the chips before this change). The YM2612 resting level is the
Nuked model's own, +576 per side at chip scale, +288 after the shift (it was
+768 / +384 on `develop`); it is an offset, not a gain, and is not part of the
balance.

## 2. Effective full scale at the mix output

One channel at full scale, at the 16-bit output after `MASTER_GAIN_SHIFT`:

| Path | `develop` (`8290558c4`) | This branch, before calibration | This branch, calibrated |
|---|---|---|---|
| One FM channel | 8191 >> 1 = **4095** | 6144 >> 1 = **3072** | 6144 >> 1 = **3072** |
| One PSG channel | 4200 >> 1 = **2100** | 8191 >> 1 = **4095** | 8191 x 38 % = 3112, >> 1 = **1556** |
| Six FM channels (sum) | 24 573 | 18 432 | 18 432 |
| Four PSG channels (sum) | 8400 | 16 380 | 6224 |
| Everything at full scale | 32 973 (clips by 206) | 34 812 (clips by 2045) | 24 656 (8111 headroom) |
| FM resting level (silence) | +384 | +288 | +288 |
| One silent FM pin cycle | n/a | 3 << 3 >> 1 = 12 | 12 |
| FM : PSG, one channel each | 8191 / 4200 = 1.950 (**+5.80 dB**) | 6144 / 8191 = 0.750 (**-2.50 dB**) | 6144 / 3112 = 1.974 (**+5.90 dB**) |

Citations: FM 6144 and the +3-per-silent-cycle resting model are
`Ym2612Chip.java` class Javadoc and `NukedOpn2.java:825-848` (the YM2612
output stage, `ym3438.c` `OPN2_Output`); PSG 8191 is `PsgChip.FULL_SCALE`;
`MASTER_GAIN_SHIFT` is `VirtualSynthesizer.java:14`; the `develop` numbers are
the old-core constants named in §1.

**Ratio change before calibration:** the merged branch had moved the FM:PSG
balance from +5.80 dB to -2.50 dB, i.e. the PSG had become **8.30 dB louder
relative to the FM** (2.50 dB from the FM full scale dropping 8191 -> 6144,
5.80 dB from the PSG full scale rising 4200 -> 8191).

## 3. Calibration applied

Preferred method (a two-chip reference, §4) was not available, so the balance
is restored numerically. The PSG preamp `p` that reproduces `develop`'s ratio
given the new full scales is

```
6144 / (8191 x p) = 8191 / 4200
p = 6144 x 4200 / 8191^2 = 0.38464 -> 38 %
```

`PsgChip.configure` takes a whole percent. 38 % gives 6144 / 3112 = 1.974
(+5.90 dB), residual +0.10 dB; 39 % gives 6144 / 3194 = 1.924 (+5.68 dB),
residual -0.12 dB. 38 is the nearer and is used. (A preamp of 51 % would
restore the PSG's *absolute* `develop` level, 4200, but not the ratio, since
the FM also dropped by 2.5 dB.)

The constant lives in the mixer: `VirtualSynthesizer.PSG_PREAMP_PERCENT`,
applied in the constructor as `psg.configure(PSG_PREAMP_PERCENT, 0xFF)`
(panning `0xFF` = every channel on both sides; the Mega Drive's SN76489 has no
stereo register). This is the shape the PSG contract prescribes ("if achieving
the engine's existing FM:PSG balance needs a non-unity preamp, it is expressed
as an explicit `psg.configure(preamp, 0xFF)` call in `VirtualSynthesizer`").
`PsgChip` and `Ym2612Chip` emit their hardware-relative scales unchanged;
`Ym2612Chip` received only a Javadoc edit pointing here instead of stating a
"2.5 dB lower relative to the PSG" that the mixer now compensates.

Chosen levels, for the tests to assert (`TestVirtualSynthesizerMix`):

| Quantity | Value at the mix output |
|---|---|
| One full-scale FM channel | 3072 |
| One full-scale PSG channel | 1556 |
| FM : PSG | 1.974 (+5.90 dB), `develop` was 1.950 (+5.80 dB) |
| Resting level, silence | +288 per side |
| DAC `0x2A = 0x00` (-256, full-scale negative) | 288 - 3072 - 12 - 6 x 12 = **-2868** |
| DAC `0x2A = 0xFF` (+255) | 288 - 12 + 3072 x 255/256 = **3336** |

The DAC rows are how the FM full scale is observed exactly: the DAC is the one
way to hold a YM2612 channel at a known constant. Its top code is +255, one
9-bit step below the +256 that defines full scale, and in YM2612 mode a
negative sample also flips the channel's three sign cycles from +3 to -3.

## 4. Why no hardware calibration

Searched for a capture with FM music and PSG sound together:

- `src/test/resources/traces/**`: no `*.pcm`, `*.wav` or `audio_*` sidecar;
  the v5 trace contract carries no PCM stream. `src/test/resources/audio/`
  holds Nuked-OPN2 register scripts and the S1 parity `.bk2` +
  normalisation contract, not audio.
- `docs/architecture/research/audio/*.wav|mp3`: every `*-reference.*` file
  is FM-only by the disassembly SFX headers (Ring `B5` FM5, Ring Left `CE`
  FM4, Ring Loss `C6` FM4+FM5, Hit Spikes `A6` FM5, Roll `BE` FM4, Shield
  `AF` FM5, Get Bubble `AD` FM5, Signpost `CF` FM4+FM5), as the PSG contract
  (`2026-08-29-psg-clean-room-contract.md`, "Reference captures on hand")
  and the PSG capture comparison §1 already recorded. Re-checked: no file
  with ring/signpost/PSG content was added since.
- The BizHawk headless recorder and `gpgx-audio-observer` observe chip
  *writes*, not the mixed PCM, so they cannot supply a two-chip level
  reference either.

So the balance is pre-rewrite parity. A hardware or trusted-emulator capture
of one passage with FM music and a PSG SFX (an S1 `B6` Spikes Move or `AA`
Splash over GHZ music would do) is what closes this; when one exists, render
the same passage with `FmSfxRenderTool` / `PsgSfxRenderTool`, compare the RMS
of the FM-dominated and PSG-dominated segments, and replace the 38 % with the
measured value here and in `docs/status/known-discrepancies.md`.

## 5. Headroom

Peak of each render (16-bit, both sides) through `FmSfxRenderTool` (`-mix`
full driver mix, `-fm` PSG muted) and `PsgSfxRenderTool` (`-psg` FM muted),
8 s cap, before (merged branch, PSG at 100 %) and after (38 %). Songs are the
loudest passages of each game's first level; SFX are the four PSG SFX the
capture comparison used and two FM SFX as controls. No render clipped in
either state; `develop`'s worst case in §2 (all channels full scale) would clip
where this branch does not.

| Render | Before: peak (dBFS) | After: peak (dBFS) | After: RMS |
|---|---|---|---|
| S1 music `81` GHZ, mix | 9935 (-10.37) | 7979 (-12.27) | 1394 |
| S1 music `81` GHZ, FM only | 8368 (-11.86) | 8368 (-11.86) | 1311 |
| S2 music `82` EHZ, mix | 5650 (-15.27) | 4888 (-16.53) | 878 |
| S3K music `01` AIZ1, mix | 6611 (-13.90) | 5438 (-15.60) | 1103 |
| S1 SFX `B6` Spikes Move, PSG only | 4376 (-17.49) | 1841 (-25.01) | 518 |
| S1 SFX `AA` Splash, mix | 6618 (-13.89) | 4112 (-18.03) | 450 |
| S2 SFX `A2`, PSG only | 4367 (-17.51) | 1837 (-25.03) | 622 |
| S3K SFX `42`, PSG only | 3373 (-19.75) | 1460 (-27.02) | 585 |
| S1 SFX `B5` Ring, FM only | 3951 (-18.37) | 3951 (-18.37) | 1001 |
| S1 SFX `A6` Hit Spikes, FM only | 4004 (-18.26) | 4004 (-18.26) | 1132 |

The PSG-only peaks include the +288 FM resting level: (4376 - 288) x 0.38 +
288 = 1841, as expected. Loudest peak after calibration is the S1 GHZ mix at
-11.9 dBFS, 7.9 dB below the loudest `develop`-era worst case computed in §2.

## 6. Verification

- `TestVirtualSynthesizerMix`: resting level 288 exact; DAC -256 / +255 at
  -2868 / 3336 (+-1 LSB); PSG preamp 38 and configured full scale 1556 exact
  through the snapshot, the rendered DC step within 0.5 % (the blip kernel's
  overshoot on an AC-coupled path prevents a 1-LSB assertion on a rendered
  PSG step); ratio 1.974 and the +0.10 dB residual against 8191/4200; and
  the existing determinism / batch-invariance / both-chips assertions. Set to
  39 % on purpose the preamp and ratio assertions fail (`expected: <38> but
  was: <39>`, `expected: <1.974> but was: <1.9236>`).
- Whole audio package, `TestNukedOpn2BitExactScripts` (732),
  `TestYm2612ChipNukedParity`, `TestPsgChipHardwareBehaviour`: green after
  the merge and after the calibration; `-Pguards` green. Full-suite numbers
  are in the merge report.
