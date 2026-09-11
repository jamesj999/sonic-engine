# SMPS Playback Human Listening Checklist

## Purpose and gate

This is the human release gate for the bounded
[SMPS playback authenticity roadmap](../../plans/audio/2026-08-21-smps-playback-authenticity-roadmap.md).
It checks audible outcomes that source-backed state, register-write, and PCM
tests cannot completely judge. It does not authorize new complete-run observer
work or backend cleanup.

Do not approve the playback delivery until every row is either:

- marked **PASS** by a listener comparing the same retail ROM in OpenGGF and
  BizHawk 2.11 / Genesis Plus GX; or
- marked **KNOWN DIFFERENCE** with a concrete audible symptom and a linked,
  bounded follow-up.

An unchecked row means human approval is pending, not that the automated tests
failed.

## Reference setup

- Build and run OpenGGF on JDK 21.
- Use the exact supported ROM revisions and verify their SHA-1 values:
  - S1 World REV01: `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`
  - S2 World REV01: `8bca5dcef1af3e00098666fd892dc1c2a76333f9`
  - S3&K locked-on: `cfbf98c36c776677290a872547ac47c53d2761d6`
- Use BizHawk 2.11 with Genesis Plus GX as the reference. Match NTSC/PAL
  selection and start from the same gameplay event rather than trying to align
  title-screen wall-clock time.
- Keep `audio.dacInterpolate=false` and
  `audio.psgNoiseShiftEveryToggle=false`. Disable donor-audio substitution and
  other non-authentic audio options.
- Level-match the two outputs before judging balance. Listen on the same output
  device, without normalization, spatial effects, or EQ.
- For each row record listener, date, **PASS** / **KNOWN DIFFERENCE**, and one
  sentence of notes. A difference must name the first audible event, not merely
  say that the outputs “sound different.”

## Cross-game checks

| Status | Event | What to compare | Listener / date / notes |
|---|---|---|---|
| [ ] | Normal music for at least 60 seconds | Tempo, pitch, FM/PSG balance, DAC rhythm, loop continuity, modulation, and envelope cadence | |
| [ ] | Several overlapping FM and PSG SFX | Admission order, priority rejection, channel takeover, retained music channels, and release timing | Retest pending after the S1 per-track release and fixed SFX-RAM walk corrections; include skid PSG3/noise overlap, splash FM5+PSG3 ordering, ring replacement, ring-loss, and signpost release |
| [ ] | Pause during music, during an SFX, and during active DAC | Mute write order, absence of stray notes, continuous DAC clocking where applicable, and resume state | |
| [ ] | Fade start through terminal cleanup | Immediate channel silencing, fade length/steps, absence of an extra final step, and silence after completion | |
| [ ] | Speed-up enable and disable | Tempo transition phase, effect/envelope cadence, and restoration of normal tempo | |
| [ ] | First and repeated 1-up over active music | Jingle takeover, SFX blocking, saved-song identity, restore timing/fade, and repeated-jingle stability | |

## Sonic 1 REV01

| Status | Scene | Authenticity focus | Listener / date / notes |
|---|---|---|---|
| [ ] | Green Hill Zone music with drums, then ring, jump, spring, and explosion SFX | Direct 68k cadence, PSG envelopes, DAC timing, and music-before-SFX service order | Retest pending after the exact 1,967-tick SFX-oracle match; check jump release, alternating ring pan, skid PSG3/noise takeover, splash tied-volume/order, ring-loss volume, and signpost release |
| [ ] | Trigger two contending SFX, then replace the BGM while one remains active | Global priority latch; ordinary BGM replacement preserves live normal/special SFX and rebinds overrides | |
| [ ] | Pause and resume while FM, PSG, and DAC are active | FM pan-to-zero/key-off and PSG silence; resume restores pan without inventing a voice reload | |
| [ ] | Trigger 1-up while an SFX owns a channel, then retrigger before restore | SFX stop/block boundary, priority clear, restore fade, and the shipped `FixBugs=0` FM6/DAC masking behavior | |
| [ ] | Start a level/death fade while an SFX is active | S1 kills normal and special SFX at fade start and performs exact terminal cleanup | |

## Sonic 2 REV01

| Status | Scene | Authenticity focus | Listener / date / notes |
|---|---|---|---|
| [ ] | Emerald Hill or Chemical Plant for at least 60 seconds, including drums | Carry/no-carry note holds while envelopes/modulation continue every VInt; 295-cycle DAC service | Retest pending after the tempo-delay and outer-frame cadence corrections |
| [ ] | Charge spindash repeatedly, release, wait for timeout, and repeat | Bounded semitone ladder, 60-service reset, and shipped `$90` release transpose | |
| [ ] | Play lower/equal/higher-priority SFX on free and occupied roles | Single global priority latch rejects low priority even when another role is free | |
| [ ] | Replace BGM while an SFX is active | S2 kills SFX before the new music load; no synthetic takeover/reset writes | |
| [ ] | Pause and resume during active music | Destructive FM silencer on pause and voice reload on resume | |
| [ ] | Trigger speed shoes and 1-up in both orders, including a repeated 1-up | Preserved tempo phase and the shipped `FixDriverBugs=0` stale priority-latch restore | |
| [ ] | Run an eligible song in PAL mode | Six music services across five PAL VInts while SFX remains single-service | Retest pending after the driver-global PAL counter correction |

## Sonic 3 & Knuckles locked-on

| Status | Scene | Authenticity focus | Listener / date / notes |
|---|---|---|---|
| [ ] | AIZ music, then CNZ or LBZ music with audible modulation envelopes | Seeded tempo phase, signed negative modulation deltas, and shipped bogus-`BC` loop operands | |
| [ ] | Trigger overlapping FM/PSG SFX during music and let each end | SFX-before-music service order, admission-time override, and same-VInt release | Retest pending after the frame-locked SFX-before-music correction |
| [ ] | Trigger speed shoes for at least eight VInts | Shared timeout tails and the extra music update every four outer VInts | Retest pending after the shared speed-up-tail correction |
| [ ] | Pause/resume while FM6/DAC and PSG are active | FM6/DAC remains live under the S3K pause policy; PSG silence and FM pan restore match retail | |
| [ ] | Trigger first and repeated 1-up while speed-up is active | Jingle runs at normal speed, displaced speed state returns, and native FM-only fade-back is audible | |
| [ ] | Start fade-out while DAC, PSG, and FM are active | DAC/PSG stop immediately; only FM performs the 40-step delayed fade | |
| [ ] | Let the boot SEGA chant play, then stop or skip it | StopAll exclusivity, YM2612 DAC rendering, no simultaneous SMPS mix, and no discarded-owner restore | Retest required after the S3K chant moved into the driver's own `zPlaySEGAPCM` transport |
| [ ] | S3K only: let the chant run to its end, and separately skip it early, then confirm the title music starts | Chant pitch and length come from the ROM's 248-cycle loop; the driver leaves the loop and resumes services in both cases | New row for the driver-owned transport |
| [ ] | Run locked-on PAL playback through at least two repeat boundaries | Driver-global sixth-VInt full repeat includes SFX, music, fade, and speed tails without dephasing | Retest pending after the driver-global PAL repeat correction |
| [ ] | Enter a Blue Sphere stage while speed shoes are active | Special-stage music starts at normal tempo; the outgoing level fade is unchanged | Retest pending after the entry-boundary fix |
| [x] | Collect several special-stage rings | Retail Z80 `zRingSpeaker` alternates left and right output | User listening pass, 2026-08-21 |
| [ ] | Collect isolated and rapidly adjacent Blue Spheres, including immediately after a spring or bumper | Both notes retain the intended `$05` then `$0A` carrier attenuation; admission key-off/SSG-EG clear occurs before the following driver update's `RR=FF`/voice/key-on phase; the first note emits one final modulated frequency before key-on; cross-SFX replacement does not transiently upload the music voice | The mixer-level candidate still sounded wrong after a completed pickup. Retest the key-on-aligned candidate, especially the first sphere after enough time for the prior effect to finish. |

## Approval

- Listener:
- Date:
- OpenGGF commit:
- Reference emulator/core:
- Output device:
- Overall verdict: **PENDING**
- Linked known differences:
