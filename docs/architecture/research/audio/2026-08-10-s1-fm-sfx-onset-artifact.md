# Sonic 1 FM SFX Onset Artifact

## Symptom

Human testing after the YM2612 operator-routing correction found a very short incorrect
sound at the start of FM effects, most clearly Sonic 1's `$C1` badnik/item explosion.
The PSG-only jump effect did not exhibit it.

## Root cause

The fault was above operator routing. Constructing an SFX `SmpsSequencer` against the
live shared `SmpsDriver` wrote to the chip before `addSequencer(..., true)` established
SFX identity and channel ownership. For ROM-backed `$C1`, construction emitted:

- YM2612 DAC enable `$2B=$80`;
- a complete FM5 upload of voice 0; and
- FM5 pan/AMS/FMS.

At that point GHZ music could still own and have FM5 keyed. The old note therefore used
the explosion instrument until the first real SFX service roughly one NTSC frame later.
This explains both the effect-specific symptom and why PSG jump was unaffected.

The later acquisition path compounded the problem by calling
`Ym2612Chip.forceSilenceChannel`. That method directly zeroed feedback, MEM, envelope,
and key state and then `SmpsDriver` injected an extra `$28` key-off. Those state changes
cannot be produced by YM2612 register writes and are absent from the shipped Sonic 1
driver.

## Source-of-truth behavior

`Sound_PlaySFX` in `docs/s1disasm/s1.sounddriver.asm` initializes the track-RAM records;
it does not upload an FM voice. On the later `UpdateMusic`, `$C1` executes its stream from
`docs/s1disasm/sound/sfx/SndC1 - Break Item.asm`:

1. `smpsModSet`;
2. `smpsSetvoice $00`;
3. the normal FM note path's key-off, frequency, and key-on.

The ROM-backed regression now observes exactly one `$C1` FM5 upload beginning at
`B1=3C`, followed by `$28=05`, `A5=24`, `A1=3C`, and `$28=F5`. Construction emits no
chip write. Operator and port ordering are unchanged.

## Correction

- SFX construction selects initial voice state without refreshing shared hardware and
  suppresses the shared constructor's DAC-enable write. Music construction is unchanged.
- A typed FM takeover policy keeps the prior reset behavior as the default for unverified
  profiles. Sonic 1 selects register-sequence takeover, so its SFX bytecode owns every
  visible write and no internal chip reset occurs.
- Presentation-side config copying preserves that policy, preventing the normal queued
  playback path from silently falling back to the legacy reset.

This is intentionally not an operator/slot-order change and does not generalize Sonic 1's
takeover policy to Sonic 2 or Sonic 3&K without equivalent source-backed validation.
