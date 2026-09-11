# Fast FM core: human listening test plan (2026-09-06)

The fast core passes every oracle vector, but the oracle is a digital
comparison against the Nuked-OPN2 port; nobody has yet listened to the
release build with the fast core as default. This plan says what to run, what
to listen for, and how to report, so a finding can be turned into an oracle
script and fixed.

## Setup

- Build from `develop` (fast is the default there since 7c6b1754d) and start
  with `./dev.sh`. The log must show `FM core: fast`; if it shows `accurate`,
  the root `config.yaml` overrides the default.
- A/B: edit `audio.fmCore` in the root `config.yaml` to `accurate`, restart,
  play the same passage, then back to `fast`. Same volume, same output device,
  headphones preferred. The accurate core is the reference, not real hardware.
- Keep `audio.dacInterpolate` at its default for both runs.

## What to listen for, and where it is stressed

| Feature (what changed in the fast core) | Where it is audible | Symptom if wrong |
|---|---|---|
| LFO vibrato and tremolo (prescaler, PM depth arithmetic, AM history) | Sustained leads and pads: S1 Marble, Spring Yard; S2 Mystic Cave, Casino Night, Oil Ocean; S3K Hydrocity, Ice Cap, Carnival Night, Launch Base | Vibrato too fast/slow or too deep, pitch wobble that does not match the accurate core, tremolo pumping |
| Feedback and high-modulation SFX (scheduled write admission, feedback history) | S2 spring, ring loss, spin dash; S3K shield activations, insta-shield, rolling | Harsh or thin timbre, buzzing on the attack, a sound that differs between repeats |
| Dense pitch changes (frequency sampling at operator boundaries) | S3K effect 3C style sweeps, S1/S2 spring and jump, level-end pitch slides, speed shoes tempo change | Zipper noise, missed pitch steps, wrong start pitch |
| Envelope timing (EG sampling, decay steps, SSG-EG instruments) | Bass and percussive FM in S3K Marble Garden, Flying Battery; S2 Chemical Plant; S1 Star Light | Clicks at note starts, notes that hang or cut short, wrong decay shape |
| DAC drums mixed with FM (output timing, DAC slot sampling) | Every track; heaviest in S3K Angel Island and Launch Base, S1 Labyrinth | Drums late or early against FM, drum level wrong, clicks between samples |
| Pan and total-level fades (level and pan admission) | Act-end fade outs, S3K act transitions, pause/resume, 1-up jingle return, invincibility and drowning music | Fade stepping, a channel that stays loud or silent after a fade, wrong stereo placement |
| Channel 3 special mode and timers/CSM | S2 and S3K SFX that use per-operator pitch; anything driven by timer tempo | Detuned or missing SFX, tempo drift |
| Not modelled on purpose | Everywhere at low level | The YM2612 ladder "crunch" at quiet levels and busy-flag timing are outside the fast core's scope; a difference here is expected and not a defect |

## Suggested route

1. S1: GHZ1 full act, SYZ1 (spring-heavy), SLZ1, LZ1 with drowning music.
2. S2: EHZ1, CPZ1, CNZ1, MCZ1, OOZ1; get hit and lose rings, use the spin dash.
3. S3K: AIZ1 to HCZ1 including the act transition, ICZ1, MGZ1, LBZ1; fire,
   lightning and water shields.
4. Sound-test style repetition: trigger the same SFX ten times in a row and
   listen for run-to-run differences.

## Reporting

For each finding record: game, zone/act, what was playing, timestamp or
trigger, core (`fast`/`accurate`), what you heard, and whether the accurate
core sounds the same. A finding against the accurate core too is an SMPS
driver issue, not a fast-core issue. A fast-only finding becomes a register
script under `src/test/resources/audio/nuked-opn2/port/` and an oracle
tolerance case; the write stream can be captured with the chip write
observer (`PhysicalChipCapture` for the accurate core).

## Sign-off

Listening sign-off is a release gate for 0.6 alongside the fresh package,
ordinary suite, structural guards and trace run; it is recorded in the
[fast FM release record](2026-09-06-fast-fm-release.md).
