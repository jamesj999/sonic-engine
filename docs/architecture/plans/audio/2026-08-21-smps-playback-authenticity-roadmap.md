# SMPS Playback Authenticity Roadmap

> **Status: deferred to 0.7 (2026-08-27).** The runtime changes from this
> roadmap were reverted from the 0.6 release line because they produced audible
> regressions that the automated suite did not detect; the listening checklist
> that would have caught them stood at 1 of 29 rows. The engine's audio runtime
> is back at its pre-roadmap baseline (`703988718`). Nothing in this document,
> the research artifacts under `docs/architecture/research/audio/`, the audit
> and validation records, or the gpgx measurement tooling under `tools/` was
> removed -- the programme resumes from this record rather than being
> re-derived. Before re-landing any phase, read the rollback commit's notes on
> which constants were fitted to a single capture and which are source-derived.

## Outcome

OpenGGF should audibly reproduce the shipped Sonic 1 REV01, Sonic 2 REV01, and
Sonic 3 & Knuckles locked-on SMPS drivers as closely as the supported ROMs and
Mega Drive chip models permit. The acceptance surface is what reaches the
YM2612, PSG, and DAC/PCM paths: timing, register writes, sample selection,
mixing, interruption, and restoration.

This roadmap replaces broad semantic-transaction expansion as the active audio
priority. Trace and observer work is subordinate tooling. It is justified only
when a named playback discrepancy cannot be isolated with a smaller source-
backed test.

## Scope boundary

Work is in scope when it changes or verifies at least one of:

- music, SFX, jingle, or special-SFX scheduling;
- SMPS command and envelope interpretation;
- request priority, channel takeover, mixing, or release;
- pause, fade, speed-up, 1-up, and music replacement behavior;
- YM2612, PSG, DAC, or PCM writes and timing;
- ROM-backed song, SFX, voice, envelope, or sample resolution.

Work is out of scope unless a concrete playback mismatch proves it necessary:

- exhaustive semantic lifecycle ledgers;
- every-occurrence causal proofs across complete-game movies;
- new global trace schemas or native observer ABI expansion;
- full-run recapture solely to restamp metadata;
- architecture migration whose only outcome is cleaner abstraction;
- attempts to make the shipped drivers internally consistent by taking a
  `FixBugs`, `fixBugs`, or `fix_sndbugs` branch that the retail ROM did not take.

Short external captures are preferred to whole-game evidence. Diagnostics must
remain optional observers and must never select content or drive playback.

## Target architecture

Keep the common runtime already used by all three games:

`AudioManager -> presentation registry -> SmpsDriver/SmpsSequencer -> YM2612 + PSG + DAC -> mixer`

Game differences belong in immutable, typed configuration or the existing
game-owned loader/profile/coordination-flag boundary. Shared sequencer and chip
code must not branch on game names. The older duplicate backend is left alone
until audible parity is established; removing it early would mix cleanup risk
with behavioral correction.

## Delivery phases

Each phase is independently reviewable and shippable. A phase closes with
source citations, focused behavioral tests, the affected ROM integration tests,
and an audible/chip-write comparison where practical.

### Phase 0 — Small accuracy harness

- Keep compact register-write and PCM fixtures for named driver routines.
- Add short GPGX/BizHawk or SMPSPlay references only for mismatches that unit
  tests cannot distinguish.
- Compare ordered YM2612/PSG writes and PCM output around a bounded event, not
  an entire playthrough.
- Keep a human A/B listening checklist for tempo, modulation, takeover, pause,
  fade, and 1-up transitions.

### Phase 1 — Driver scheduler and tempo cadence

This is the first implementation slice.

- Separate "service this track" from "advance its duration". S2 and S3K still
  run envelopes, modulation, note fill, and command-side work on VInts where
  their tempo accumulator holds the note.
- Match S1 countdown behavior and its accumulator reset rules.
- Match S2 carry/no-carry duration extension and preserve its accumulator when
  the shipped driver does.
- Replace generic PAL tempo multiplication with the actual per-driver policy:
  no S1 compensation, and S2's extra music update every fifth PAL VInt for
  eligible songs while SFX remains single-service.
- Implement locked-on S&K's driver-global PAL repeat at the shared driver
  boundary. The shipped `fix_sndbugs=0` path
  seeds 5, reloads 6, and tests before decrementing, so every sixth PAL VInt
  repeats the full update, including SFX, music, and the speed/fade tails. Do
  not approximate it with counters owned by independently admitted sequencers.
- Seed and advance the S3K accumulator exactly as the driver does.
- Match S3K speed-shoes cadence, including the two timeout services per outer
  VInt that produce an extra music update every four VInts at value 8.

Acceptance: focused tests prove service count, duration progression,
accumulator phase, and modulation/envelope activity for all three modes,
including the locked-on PAL driver loop; ROM presentation integrations remain
green.

Status: complete. S1, S2, and locked-on S3K now use their shipped duration,
tempo-phase, PAL-service, and speed-up schedules. The locked-on PAL repeat is
owned by the shared driver state rather than by individual sequencers.

### Phase 2 — Request admission, priority, and takeover order

- Implement the single global SFX priority latch used by S1 and S2, including
  shipped `FixDriverBugs=0` behavior; do not invent a priority table for S3K.
- Establish channel ownership at request admission before the same-frame music
  service, rather than waiting for the first SFX chip write.
- Match ordinary BGM replacement: S1 preserves live normal/special SFX and
  rebinds their channel overrides to the new song, while S2 and S3K stop SFX.
- Match each driver's music/SFX service order, takeover writes, and release
  point. Remove injected reset/key-off behavior where the retail driver relies
  on SFX bytecode instead.
- Preserve game-specific ordinary-BGM behavior: S1 retains active SFX and
  re-marks overrides; S2 kills SFX before loading music.

Acceptance: bounded contention tests cover free and occupied channels, lower /
equal / higher priority, same-frame writes, and exact restore timing.

Status: complete for the bounded engine playback path. S1/S2 use the global
priority latch, ownership is claimed at admission, service order is per-driver,
ordinary BGM replacement is game-authentic, and PSG release follows the
shipped rest-vs-same-VInt behavior.

### Phase 3 — Pause, fade, speed, and 1-up lifecycle

- Implement driver-level pause mute/restore rather than only pausing the host
  sink, including the correct DAC-service behavior.
- Port each game's fade channel set, step count, delay, terminal cleanup, and
  shipped PSG-envelope interaction.
- Port 1-up save/replace/restore behavior, speed-state handling, and S3K's
  native fade-back instead of a generic frozen-synth swap.
- Preserve shipped S1/S2 restore bugs where their `FixBugs=0` paths are audible.

Acceptance: ordered register-write tests around pause, fade start/end, first and
repeated 1-up, and restore; short external references for transitions.

Status: fade-out channel selection, speed-state clearing, initial SFX/DAC/PSG
halts, terminal count, and service-scoped all-audio cleanup now match the
shipped paths. S3K 1-up now runs at normal speed and restores the displaced
song's saved speed state, then applies the native FM-only restore fade.
Driver-level pause now uses typed S1/S2/S3K chip-write and restore policies at
the existing host pause boundary. Continuously clocked chip state, including
an already-started DAC sample, advances during silent paused frames without a
driver VInt. The remaining shipped S1/S2 1-up
SFX lifecycle is now modeled as well: both drivers stop and block effects
during the jingle, S1 clears its priority latch before saving, and S2 preserves
the shipped stale-latch restore bug. SFX admission resumes at each driver's
actual boundary (after fade-in for S1/S2, immediately on restore for S3K).
S1 also preserves the jingle's YM2612 DAC mode because retail `FixBugs=0`
omits the `$2B` repair, reproducing the resulting restored-FM6 masking bug.
S3K's intentionally invalid `fix_sndbugs=0` resume-memory overrun remains a
separately bounded follow-up.

### Phase 4 — SMPS bytecode and envelope quirks

- Audit modulation, volume envelopes, PSG envelopes, note fill, ties/holds,
  transposition, and coordination flags against each shipped interpreter.
- Port S3K's shipped modulation-envelope signed-byte and command-82 bugs.
- Restore the shipped S2 spindash-release transpose and request-transform
  timing rather than the current corrected/approximated behavior.
- Add a regression for every supported bytecode quirk before changing its
  interpreter.

Acceptance: command-level chip-write fixtures and representative real ROM
songs/SFX that execute each path.

Status: complete for every bytecode family reached by the supported retail
catalog. S3K signed modulation-envelope deltas and the retail bogus-`BC`
operand reads for `$82`/`$84` are implemented from the decompressed ROM driver.
The source audit also verified the supported volume-envelope command inventory,
note-fill, tie/hold, transposition, and coordination-flag paths. Generic command
forms not referenced by any supported retail stream remain deliberately
unsupported instead of being assigned guessed behavior.

### Phase 5 — Chip, DAC, PCM, and regional behavior

- Correct the S2 DAC service-cycle constant and validate DAC latch timing.
- Select hardware-reference PSG noise and DAC interpolation defaults; retain
  enhancements only as explicit non-authentic options.
- Use region-correct YM2612/PSG/Z80 clocks.
- Implement S3K StopSEGA/SEGA PCM exclusivity through YM DAC rather than mixing
  host PCM over active SMPS playback.
- Remove non-native time caps and global FM6/DAC workarounds once their owning
  driver behavior is modeled.

Acceptance: deterministic PCM/register fixtures and short external audio
goldens at NTSC and PAL rates.

Status: complete for the production chip path. S2 DAC cadence, the authentic PSG/DAC defaults, and region-correct
YM2612/PSG/Z80-derived clocks are complete. S3K's
SEGA PCM command now atomically stops all SMPS/sample owners before exclusive
PCM playback, streams the chant bytes through the region-clocked YM2612 DAC
core, and StopSEGA restores none of the discarded owners. Rewind/save-load
preserves the DAC latch and resampler state exactly. Optional smoothing remains
available only behind explicit non-authentic configuration; the retail defaults
and production presentation path contain no fallback mixer for SEGA PCM.

### Phase 6 — ROM loader and content hardening

- Verify supported-ROM song/SFX/voice/envelope/sample tables against exact
  retail offsets and compression framing.
- Remove silent heuristic fallbacks for supported hashes; fail clearly when a
  required mapping is absent or malformed.
- Keep runtime bytes ROM-owned. Disassemblies provide labels and meaning only.

Acceptance: all supported content IDs resolve from each verified ROM, malformed
tables fail closed, and representative playback remains byte-stable.

Status: complete. ROM-backed catalog sweeps resolve every declared S1, S2, and
S3K music/SFX entry and DAC catalog. S2 accepts only the shipped little-endian
Saxman framing and the four exact uncompressed-song boundaries; S1 PSG
envelopes require their retail hold terminator; S3K reads the exact eight
modulation and `$27` volume-envelope pointers rather than walking beyond the
tables. Unreadable DAC catalogs fail closed instead of publishing empty data.

### Phase 7 — Cleanup after parity

- Retire or isolate the legacy duplicate backend only after the shared runtime
  owns all verified behavior.
- Rename provisional/shadow presentation terminology once it is unquestionably
  the production authority.
- Remove diagnostic scaffolding that no longer protects a playback boundary.

Cleanup is not allowed to lead the roadmap or broaden a parity change.

## Working rules

1. Start from the shipped disassembly path and cite it next to non-obvious
   behavior, especially every `FixBugs=0` choice.
2. Write a focused failing behavioral test before production changes.
3. Prefer chip writes, PCM, track state, and audible lifecycle as assertions;
   do not assert internal architecture for its own sake.
4. Put a game difference in the smallest typed owner; never add game-name
   branches to shared runtime code.
5. Stop and repartition if a proposed diagnostic change is larger than the
   playback behavior it exists to prove.
6. Do not block a useful phase on unrelated complete-run evidence closure.

## Current slice

The bounded playback-authenticity implementation is complete through Phase 6.
Final delivery is limited to JDK 21 regression comparison, integration, and the
[manual listening checklist](../../validation/audio/2026-08-21-smps-playback-listening-checklist.md).
Human approval is still pending while that checklist is unchecked. Phase 7
remains intentionally deferred cleanup; it is not a prerequisite for authentic
playback and must not reopen native observer schemas or complete-run evidence.
