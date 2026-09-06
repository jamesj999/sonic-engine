# OpenGGF - The Open-Source Java-Based Speedy Erinaceidae Engine

> This project is a work in progress. For the current state, please see the latest version in the
> Releases section of this document.

## Introduction

OpenGGF is a community-made, fan-made, open-source Java game engine for research and preservation
of classic Mega Drive / Genesis platform games, specifically the mainline Sonic the Hedgehog
series. It aims to faithfully reimplement the physics and rendering behaviour of the original
hardware using data loaded from user-supplied ROM images. The project's primary goal
is accuracy: physics, collision, and audio are all verified against community-maintained
disassemblies of titles in the Sonic the Hedgehog series. No copyrighted assets are included in
this repository; a legally obtained ROM is required to run the engine.

The engine also aims to provide modern tooling such as a level editor and an open framework for
modding and customisation. Neither is delivered yet: the editor is an experimental, config-gated
prototype and the modding framework is planned but not implemented.

> **Disclaimer:** OpenGGF is a community-made fan project. It is not affiliated with, sponsored by,
> approved by, or endorsed by Sega. Sonic the Hedgehog and all related characters, names, and
> trademarks are the property of Sega Corporation. No ROM images or other copyrighted game data are
> included in this repository. Users must supply their own legally obtained ROM files to use this
> software.
>
> The disclaimer is also shown in-engine on startup; it can be disabled by setting
> `startup.legalDisclaimer: false` in `config.yaml`.

## User Guide

A comprehensive user guide is available in [`docs/guide/`](docs/guide/index.md), covering:

- **Players:** [Getting started](docs/guide/playing/getting-started.md), [controls](docs/guide/playing/controls.md), [configuration](docs/guide/playing/configuration.md), [game status](docs/guide/playing/game-status.md), and [troubleshooting](docs/guide/playing/troubleshooting.md).
- **Contributors:** [Dev setup](docs/guide/contributing/dev-setup.md), [architecture overview](docs/guide/contributing/architecture.md), [adding zones](docs/guide/contributing/adding-zones.md), [adding bosses](docs/guide/contributing/adding-bosses.md), [audio system](docs/guide/contributing/audio-system.md), [testing](docs/guide/contributing/testing.md), and [trace replay testing](docs/guide/contributing/trace-replay.md).
- **Cross-referencers:** [68000 primer](docs/guide/cross-referencing/68000-primer.md), [mapping exercises](docs/guide/cross-referencing/mapping-exercises.md), [per-game notes](docs/guide/cross-referencing/per-game-notes.md), and [tooling](docs/guide/cross-referencing/tooling.md).

Contributor tests are JUnit 5 / Jupiter only. Do not add JUnit 4 tests, rules, runners, or `org.junit.*` imports.

## Configuration

The engine reads runtime settings from `config.yaml` in the working directory. A legacy
`config.json` is migrated automatically on first run. Key bindings can be written either as GLFW
integer codes or as human-readable names such as `SPACE`, `Q`, or `F9`. See
[`CONFIGURATION.md`](CONFIGURATION.md) and the player guide for the full reference.

## Controls

Keyboard and standard GLFW gamepads are supported for gameplay and the basic
startup/title/data-select menus.

### Player Controls

| Key | Action |
|-----|--------|
| Arrow Keys | Movement |
| Space | Player 1 action A / jump |
| Right Shift | Player 2 action A / jump |
| Enter | Pause / unpause |

The bundled `config.yaml` exposes keyboard bindings under `input.pause`,
`input.player1`, and `input.player2`. Keyboard B/C are unbound by default;
gamepads map west/south/east face buttons to Mega Drive A/B/C. On Xbox-style
pads that is X/A/B; on PlayStation-style pads that is Square/Cross/Circle.
Additional bindable inputs, including Start and controller assignment, are
documented in [`CONFIGURATION.md`](CONFIGURATION.md); keys omitted from the
template still use the engine defaults until added explicitly.

### Debug Controls

| Key | Action |
|-----|--------|
| F1 | Show/Hide Debug Overlay (text and bounding boxes) |
| F2 | Show/Hide Shortcuts Overlay |
| F3 | Show/Hide Player Panel |
| F4 | Show/Hide Sensor Labels |
| F5 | Show/Hide Object Labels |
| F6 | Show/Hide Camera Bounds |
| F7 | Show/Hide Player Bounds |
| F8 | Show/Hide Object Points |
| F9 | Show/Hide Ring Bounds |
| F10 | Show/Hide Plane Switchers |
| F11 | Show/Hide Touch Response |
| F12 | Show/Hide Art Viewer |
| Page Up | Cycle Acts (`debug.keys.nextAct`) |
| Page Down | Cycle Zones (`debug.keys.nextZone`) |

`F9` is also the default level-select shortcut (`debug.keys.levelSelect`), so it
can both open level select and toggle ring bounds while debug overlays are enabled.

### Editor Controls

| Key | Action |
|-----|--------|
| Shift+Tab | Toggle between gameplay and the experimental editor overlay (`debug.flags.editor` must be `true`) |
| F5 | Restart the playtest from editor mode |

## FAQ

### What does "GGF" stand for?

Gotta Go Fast!

### Is this an emulator?

No. OpenGGF is an independent reimplementation of the game logic and physics, written in Java
from scratch. It does not emulate the Mega Drive CPU or VDP. Instead, it reads data (level
layouts, art, music) from original ROM images and runs its own implementation of the game rules.
The implementation is developed and verified against the community-maintained disassemblies
([s1disasm], [s2disasm], [skdisasm]) to achieve pixel-accurate behaviour. The audio engine is a
partial exception: it features software emulation of the YM2612 FM synthesiser and SN76489 PSG
chips (based on [libvgm] and [Genesis Plus GX] reference cores) driven by a reimplemented SMPS
sound driver.

[libvgm]: https://github.com/ValleyBell/libvgm
[Genesis Plus GX]: https://github.com/ekeeke/Genesis-Plus-GX

[s1disasm]: https://github.com/sonicretro/s1disasm
[s2disasm]: https://github.com/sonicretro/s2disasm
[skdisasm]: https://github.com/sonicretro/skdisasm

### Which games are supported?

| Game | Status |
|------|--------|
| Sonic the Hedgehog (S1) | Broadest end-to-end coverage: all zones, bosses, special stages, title screen, ending, credits, and demo playback. |
| Sonic the Hedgehog 2 (S2) | Most complete module by object coverage (122/122 checklist objects) and trace parity. Includes all zones, bosses, special stages, Tails AI, ending, and credits. |
| Sonic 3 & Knuckles (S3K) | Work in progress. AIZ through LBZ have substantial route coverage, but this is not full parity: the AIZ miniboss napalm FallingShot and AIZ2 end-boss splash children now have native implementations with route/trace validation still outstanding, while Knuckles' LBZ Big Arm handoff remains inert. |

Work is ongoing across all three games. See `CHANGELOG.md` for detailed, per-merge history.

### Where do I get ROMs?

We do not supply ROM images. You must provide your own legally obtained copies. The engine expects
these specific revisions, placed in the working directory:

| Game | Expected filename | Expected revision and hash |
|------|-------------------|----------------------------|
| Sonic 1 | `s1.gen` | World, Revision 01; CRC32 `AFE05EEE`; SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| Sonic 2 | `s2.gen` | World, Revision 01; CRC32 `7B905383`; SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Sonic 3&K | `s3k.gen` | World lock-on combined ROM; CRC32 `63522553`; SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6` |

Other revisions (REV00, etc.) are untested and will likely produce incorrect results, as
ROM addresses are verified against these specific builds. ROM filenames are configurable via
`config.yaml` (see `roms.sonic1`, `roms.sonic2`, and `roms.sonic3k`).

### What is cross-game feature donation?

A feature that lets a donor game (S2 or S3K) provide player sprites, spindash mechanics, sound
effects, and the data select (save/load) screen while you play a different base game (e.g.
Sonic 1). This means you can play S1 levels with S2's Sonic and Tails sprites, spindash, and
sidekick AI — and when S3K is the donor, you also get the full S3K data select screen with
save slots and team selection before gameplay begins.
When S3K is the donor, that donated data select now also uses host-specific emerald presentation
and runtime-generated S1/S2 zone preview screenshots. Data select donation is only enabled when
`crossGame.enabled` is `true` and `crossGame.source` is `"s3k"`. Enable it in
`config.yaml`:

```yaml
crossGame:
  enabled: true
  source: "s3k"
```

Both the base game ROM and the donor game ROM must be present.

### Why Java?

We knew Java, and nobody had done it before. Every other Sonic engine reimplementation out there is
written in C, C++, or C#. A Java implementation proves it can be done on a managed runtime, and
the JVM's cross-platform nature means it runs on Windows, macOS, and Linux without platform-specific
builds (though a GraalVM native image is also available for those who prefer it).

### Will Sega shut this down?

This project contains no copyrighted material. No ROM data, sprites, music, or other Sega assets
are included in the repository. The engine is an independent reimplementation, developed and
verified against the community-maintained disassemblies, that requires users to supply their own
legally obtained ROM files. We have no affiliation with Sega and make no claim to any of their
intellectual property.

### What platforms does it run on?

Anywhere Java 21 and LWJGL run: Windows, macOS, and Linux. The engine uses OpenGL 4.1 core profile
(chosen for macOS compatibility). A GraalVM native image build is also supported for ahead-of-time compiled
binaries.

### Did you use AI to write this? / This is AI slop!

Various agents (Claude, Codex, and Gemini, in various models, versions and forms) have all been used at various points in the project's history, and
the commit history doesn't hide it; you'll see `Co-Authored-By` tags throughout. But the project
has been in development since 2013, long before AI coding assistants existed.

The pre-AI core — the engine framework and architecture, the rendering pipeline, the physics
engine and its subpixel movement model, and the sensor-based collision system — was designed and
coded by hand over years, long before any agent touched the repo. Other subsystems were built
with heavy AI assistance under direct human oversight; the SMPS audio engine, in particular, was
AI-built and steered against reference implementations rather than hand-written. AI was brought in for bulk analysis and research, to accelerate
object and boss implementation, debugging, validation, and unit tests; all with accuracy verified
against the original ROM disassemblies. Every commit is reviewed, tested, and corrected where
needed.

[You can't prompt your way to ROM accuracy (yet!)](docs/project/ai-journey.md). But we certainly prompted our way through object
implementations, research and boilerplate code a lot faster than would have been possible by hand.

For the visual version of that story, the [Development Timeline](docs/project/development-timeline.md) is a
captioned gallery of real dev builds — bugs and all — from a 2015 white-box prototype through to
the present, including the audio engine slowly un-mangling itself.

### How can I contribute?

The project is open source. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md), then check the issue
tracker, OBJECT_CHECKLIST.md for unimplemented game objects, and CHANGELOG.md for the current state
of each game. The codebase uses a provider-based architecture that makes it relatively
straightforward to add new objects, zones, and game-specific behaviour.
## Licensing

OpenGGF is free software under the GNU General Public License, version 3
([`LICENSE`](LICENSE)). The FM sound core under
`src/main/java/com/openggf/audio/synth/nuked/` is a Java port of
[Nuked OPN2](https://github.com/nukeykt/Nuked-OPN2) by Alexey Khokholov
(Nuke.YKT) and remains under the GNU Lesser General Public License, version
2.1 or later ([`LICENSES/LGPL-2.1.txt`](LICENSES/LGPL-2.1.txt)); it may be
extracted and reused under that licence on its own, and the combined program
is conveyed under GPL-3 through LGPL section 3. [`NOTICE.md`](NOTICE.md)
records the component, its pinned upstream revision and the modifications
made; [`CREDITS.md`](CREDITS.md) lists every contributor, reference source and
library. The executable JAR carries `LICENSE`, `LICENSES/`, `NOTICE.md` and
`CREDITS.md` under `META-INF/openggf/`, and the release archives ship them
next to the launcher.

## Releases

### v0.6.prerelease — Current development snapshot

OpenGGF 0.6 is the current development release focused on accurate, playable
routes through the main Sonic 3 & Knuckles slice and broad Sonic 1 and Sonic 2
gameplay. The engine loads runtime data from user-supplied ROMs and validates
behavior against the original games' disassemblies and recorded hardware
traces.

#### 0.6 highlights

- **Continue screens:** ROM-backed countdowns and character sequences now follow
  Game Over when continues remain. Start restores three lives and spends one
  continue; S1/S2 restart the act, while S3K retains its checkpoint and saves the
  updated counts. Timeout returns to title.

- **Runtime performance:** live capture reuses pixel buffers and overlaps GPU
  readback with the next frame; audio transactions reuse private rollback storage.
  Live rewind bounds cold gameplay replay to nine ticks, trading more retained
  snapshots for shorter bursts. Animated-art uploads and shader state handling
  also avoid redundant work. Art decoders now share ROM readers, CNZ palette
  patches and profiler counters allocate less, and screenshot PNG writes run
  on a bounded background worker. See the [initial measurements](docs/architecture/validation/2026-09-05-runtime-performance.md)
  and [follow-up evidence](docs/architecture/validation/performance/2026-09-05-followup.md). Audio region queries also avoid full
  sequencer snapshots, and audio architecture checks reuse their production-class
  import; see the [department measurements](docs/architecture/validation/performance/2026-09-05-department-audio.md).

- **Audio correctness and evidence:** S3K effect admission now sends the
  retail PSG noise-silence write in header order. Opt-in physical chip capture
  distinguishes actual YM/PSG strobes and DAC origins from logical logs, while
  a pinned, ROM-free benchmark tool supports further backend evaluation.
  Complete-run profiles can print their fixture, producer, observation, and
  comparison coverage without treating unavailable or diagnostic-only layers
  as parity; narrow per-game oracles remain separate.
  New configurations select the faster register-level FM core; explicit
  `audio.fmCore=accurate` selections and physical reference captures retain
  Java Nuked. Full parity and listening validation remain open. See the [audio handover](docs/architecture/plans/audio/2026-09-04-audio-parity-handover.md).

  The S3K diagnostic oracle also alternates raw ring request `33h` at the
  driver's consume callback, moving its first mismatch to service 2409;
  production three-mailbox scheduling and reset parity remain open.
  Exact phase-arithmetic simplification measured about 2–3% less audio-rendering
  time in local AIZ1 benchmarks, with no native dependency or chip replacement;
  see the [measurement limits and reference checks](docs/architecture/research/audio/2026-09-05-nuked-phase-performance.md).
  Follow-on work removes an extra S3K noise-command mute, exposes mismatched
  DAC run timing instead of mislabelling it a decoder defect, and gives
  internal-rate FM captures verified replay bounds. Repeated extra-life and
  snapshot restoration checks cover both AIZ and HCZ acts. Native-core work
  remains research; fast FM is included in the 0.6 delivery work, with fidelity
  and final performance evidence tracked in the
  [fast FM validation record](docs/architecture/validation/audio/2026-09-06-fast-fm-release.md).
  The isolated candidate passes all 178 supported chip scripts and measures
  about 0.17–0.18 ms of audio per frame on this host; full-game listening
  sign-off remains open.
  New S3K effects are also suppressed during the 1-up jingle, without
  advancing ring stereo alternation, and resume at music restoration.
  See the [1-up investigation](docs/architecture/audits/audio/2026-09-05-s3k-oneup-sfx-suppression.md)

- **Bounded S1 audio diagnosis:** a strict diagnostic reader and canonical
  row-zero runner compare independently captured request prerequisites without
  publishing or hydrating reference state. The current measurement stops at
  the first request divergence (row 972), before the one-up restore, so it is
  explicitly not an SMPS register-parity result. See the
  [validation record](docs/architecture/validation/audio/2026-09-05-s1-restore-diagnostic.md).
  for the existing-movie reproduction and the limits of current oracle coverage.
  The follow-on SMPS parity campaign removes a duplicate S3K PSG note-volume
  write, preserves ROM-gated PSG frequency pairs, restores FM3 mode before its
  music voice, and makes blocked ring requests visible to admission diagnostics.
  Driver fade counters are now directly compared as well.
  S3K PSG envelopes now wait for channel ownership to return before consuming
  an attacked note's envelope and retain the driver's byte-wrapped volume-command
  cursor. S2 DAC playback uses the retail driver's 295-cycle compressed-byte budget.
  S3K effects now run in the driver's fixed channel-slot order and preserve
  its complete PSG F2 stop-silence transaction, including repeated physical writes.
  F2 also returns covered PSG music ownership before restoring its exact stored
  noise byte, without clearing the music track's rest state. This restoration
  is scoped to the driver's track-stop path, not generic effect teardown.
  S3K SFX admission also retains ROM header order separately from playback's
  channel-slot order, preserving the previous header's PSG silence writes.
  Mutation-tested production observers complement the driver oracles; neither
  the current movie windows nor a green ordinary suite establishes complete
  per-game driver parity. See the [parity roadmap](docs/architecture/plans/audio/2026-09-05-audio-trace-coverage-roadmap.md).

- **Contributor guidance:** streamlined agent instructions and all 23 task
  skills, retaining ROM accuracy contracts and searchable technical evidence.
- **Code and test maintenance:** removed unused helpers and duplicate test setup;
  rewind-boundary, snapshot-immutability, and mode-listener tests now check the
  production behavior they describe.
- **Three-game engine:** Sonic 1, Sonic 2, and Sonic 3 & Knuckles each have
  game-specific providers for level loading, physics, objects, bosses, audio,
  rendering, and special stages.
- **Playable game functionality:** Sonic 1 has broad end-to-end coverage;
  Sonic 2 has extensive zone, boss, title, ending, and special-stage support;
  Sonic 3 & Knuckles has a growing playable vertical slice centered on
  Angel Island and Hydrocity, with substantial work across later zones and
  bonus stages.
- **Sonic 2 Tornado parity:** Wing Fortress now preserves ObjB2's retail
  standing, initialization, and reused leader-wait/jump-countdown state across
  every trace row where the parent Tornado can be identified unambiguously.
- **Sonic 2 CNZ slot-machine parity:** Casino Night now runs its zone-global
  slot routine in the retail post-object order with ROM-accurate word
  arithmetic; both release traces match every compared row exactly.
- **Sonic 2 water-palette alignment:** sprite-priority shaders now resolve
  logical scanlines after removing viewport letterboxing, keeping characters,
  objects, terrain, and backgrounds aligned at dynamic waterlines such as CPZ2.
- **Stable object-priority layering:** priority buckets now share one
  palette-mask transition path, preserving slot draw order and water/bridge
  occlusion while keeping the rendering facade within its structural budget.
- **Sonic 2 special-stage timing authority:** recorded `VBlank_Lag` rows remain
  scheduling-only replay inputs, while ordinary play retains its existing
  stateless slowdown approximation until causal hardware timing can replace it.
- **Overridden S3K PSG tracks no longer advance their envelope:** the ROM's PSG
  update returns on the overriding bit before the frequency latch and envelope
  read, so a channel taken by an SFX resumes where it left off; intro oracle at
  the 1569 write difference.
- **S3K 1-up fade-in completes correctly and survives rewind:** PSG tracks are
  released at fade completion with their volumes untouched (`zDoMusicFadeIn`
  loops FM only), and the session snapshot copy now carries the driver fade
  state, guarded so no snapshot component can be dropped by the copy again.
- **S3K 1-up resume fades in with its instruments restored:** the fade-in-to-previous
  body (FM override clear, 40h attenuation, instrument resend, fade of 40h steps at
  delay 2) now runs on the live restore path; fade delay pair and fade-out
  machine are driver state; four more oracle fields gated.
- **S3K music returns after a 1-up:** the coord-flag handler now restores through
  the sequencer's injected sink (drained after the presentation batch) instead of
  the global AudioManager, whose command was refused inside the batch; S3K intro
  oracle at service 760 with requests consumed at the ROM's point in the service.
- **Second S2 recording green on state and writes:** the CPZ level-select window
  matches all 719 services once TraceChaser's request observer records the music
  mailbox (ring milestone, song loads); request windows compare per site and
  every published S2 window is regenerated under the v4 payload contract.
- **Music after drowning and S3K fades follow the ROM:** surfacing restores the
  invincibility, Super or boss theme per each game's own routine (a per-game
  audio-profile rule), and S3K object fades use the driver's delay 6 / 40 steps
  instead of the S1/S2 values.
- **S1 per-song oracle windows cover every song in a complete run:** 20 windows
  from the complete-run movie, all 15 songs, eight matching end to end across six
  songs (Green Hill, Labyrinth, Spring Yard, title, special stage, credits); the
  rest carry pinned frontiers.
- **S2 music restores cleanly after a 1-up:** the driver rests every music
  track on the fade-in-to-previous as the ROM does (S3K instead keeps its PSG
  overridden), so the level song no longer resumes on the notes the jingle cut
  into; pinned by a new 1-up driver-state window.
- **S3K title theme no longer dies on a late intro skip:** the SEGA chant stop is
  issued once, as the ROM does at `loc_3FE4`, so pressing Start during the Sonic
  animation keeps the title music; pinned by a headless request-order test.
- **S3K spindash release and collapsing-bridge sounds restored:** a PSG3 noise
  track now owns the noise channel, the PSG volume tail is sent every pass as the
  ROM does, the fabricated spindash pitch multiplier is gone, and sequencer config
  fields reach live playback (five were silently dropped); guarded by runtime-path
  tests with AIZ1 music and a config-copy guard.
- **S1 audio oracles cover whole complete runs per song:** windows tile each
  movie by `Sound_PlayBGM` and 1-up restore epochs; the title-screen song matches
  end to end (72 ticks), tied PSG notes step their envelope, a finished song keeps
  its tempo, and fade commands dispatch at the ROM's point in `UpdateMusic`.
- **`audio.psgNoiseShiftEveryToggle` removed:** the PSG noise LFSR now always
  clocks once per rising edge, the hardware rate; the every-toggle mode from the
  old PSG core is gone and an old config key is ignored with a warning.
- **S2 CPZ2 boss segment green:** the boss spawns its children from its own
  first update in ROM slot order, the container rewrites itself into the gunk
  in place, and `Obj6B` platforms gate solidity on the previous frame's on-screen
  bit; trace sweep 8 to 7 failing classes.
- **S1 sound-test oracles run in JUnit:** the GHZ music (14,690 ticks) and SFX
  (1,967 ticks) references are now ROM-gated assertions in the committed suite.
- **Matching audio oracles are pinned as assertions:** S2 v1 (698), S2 v2 state and
  writes (2,198), the three S2 request windows (25/52/27), S1 run 2 (5,257) and
  S2 CPZ state-only (720) now fail the build on regression.
- **First duration timeout seeds at 1 in all three drivers:** cited per ROM; two
  S1 unit fixtures that could never advance (tempo 1) corrected. S3K oracle at
  the first write of service 565.
- **S2 run-chain segment 15 diagnosed:** the CPZ2 boss gunk falls a frame early
  because the ROM rewrites the container in place (`Obj5D_Container_Extend`)
  while the engine respawns it; recorded, not yet closed.
- **S2 CPZ tick-237 is the ring speaker flag:** the driver alternates left/right
  ring variants on a flag outside the song-load clear, so a capture starting at
  the load inherits its phase; recorded as a capture-start limitation.
- **Note fill runs only on the unexpired-timer branch:** all three drivers reach
  the note-fill routine from the not-expired branch of the duration decrement;
  the engine now matches, cited per driver.
- **S2 CPZ oracle music id cited from the driver playlist:** request `8Eh` indexes
  the master playlist to Chemical Plant; the tick-237 write difference is a whole
  voice load one FM channel across, not a slot-search rule.
- **S3K SFX admission follows the ROM service order:** a newly requested SFX is
  walked from the next service while the music-track override bit is set on the
  admitting one; S3K oracle at the duration-timeout seed in service 565.
- **Second S2 driver-state recording (CPZ, rows 2700-3450):** captured from a
  different movie, zone and song; state matches all 720 ticks, writes diverge at
  tick 237 on a second overlapping SFX FM slot.
- **Level frame counter advances where the ROM does:** all three loops increment
  `Level_frame_counter` before the object pass; about two dozen per-reader `+1`
  compensations and the speed-shoes phase offset are deleted.
- **S3K duration-only units neither silence nor retune:** a positive stream byte
  only stores the duration, keeping the existing frequency and rest state; PSG
  volume silence keys on the rest bit. S3K oracle at service 565.
- **S2 DAC runs are bounded by the ROM sample length:** runs end at the
  `zDACLenTbl` length, a silent service, or a selector change; the residual
  byte difference is a supersession join and is reported as such.
- **S3K duration-only notes keep the rest state the ROM left:** the note-start path
  no longer recomputes the rest bit from a stale note byte; S3K oracle at a write
  difference inside service 551.
- **S2 DAC stream reports where the reference resyncs:** the byte-709 difference is
  a merged-play sample join, not decode or selection; the driver-state oracle now
  matches all 2,198 services for state and writes.
- **S3K resting a PSG track silences it:** `zRestTrack` runs straight into the
  PSG channel silence when the driver still owns the track, so a parked envelope
  rest silences every pass; S3K oracle at service 551.
- **Speed shoes expire on the ROM's frame in all three games:** the countdown
  is driven at the display step where `Obj01_ChkShoes` runs, restoring physics
  and issuing the slow-down command together, and the compensation constant
  is gone; the S2 driver-state oracle is a full MATCH over 2,198 services.
- **S3K per-track PSG silence writes tone then noise:** a noise PSG3 track
  silences its own channel before the noise channel as the driver does; S3K
  oracle at service 502.
- **Sonic 2 write stream matches through 1,789 services:** SFX tracks walk in
  RAM slot order, PSG takeover claims a channel with one bit and no write, and
  a PSG3 noise SFX re-latches its noise byte on release; every remaining
  divergence on the v2 fixture is the speed-shoes timer phase.
- **S3K music fade is driver-owned:** E1/E5 arm the fade counters and halt
  the DAC and PSG tracks per the S3K driver, the fade handler and tempo step run
  before the mailbox is read, and the capture reports no unsupported requests;
  S3K oracle at service 495.
- **Sonic 2 driver-state oracle: DAC stream and PSG note-on:** the v2 comparator
  now compares the DAC sample bytes as a whole-window stream (92 runs, delta 0)
  and a note-on PSG frequency is sent once as the driver does; service writes
  reach tick 228, and the speed-shoes timer compensation is recorded as the
  cause of the tempo phase.
- **S3K modulation state is compared and matches:** the six modulation bytes
  joined the compared track state, `zFinishTrackUpdate`'s clears and the 8-bit
  speed decrement are modelled, a resting FM track advances nothing, and
  `zSendTL` writes all four operators; S3K oracle reaches service 421.
- **S3K note attack, envelope rest and PSG volume flags follow the driver:**
  the do-not-attack bit clears at the top of the next note fetch, a parked
  envelope rest re-rests only under that bit, and the PSG volume coordination
  flags store without writing the chip; S3K oracle 180 to 331.
- **Sonic 2 music load writes match `zBGMLoad`:** activation keys FM6 off and
  silences it before the DAC enable per the song's channel count, and the load's
  two note-off loops send each slot's own voice-control byte; all 145 load
  writes agree in order.
- **S3K rest bits and sample-end follow the driver:** DAC tracks never rest,
  S3K's PSG envelope rests a track without silencing it, only S2 skips
  modulation at rest, and the sample-end DAC disable joins the excused byte
  stream; S3K oracle 143 to 180.
- **Sonic 2 driver-state reference v2:** driver RAM is now sampled by the
  observer core at both `zVInt` returns over rows 10150 to 12400 (2,243
  services, two byte-identical captures); the driver state agrees for 1,789
  consecutive services and first diverges on tempo at movie row 11,991.
- **S3K note cadence follows its own driver:** S3K resends a note's frequency
  on every pass because its modulation routine returns to the fall-through,
  and its PSG update latches frequency before volume even at rest; both are
  per-driver sequencer modes, S1 and S2 unchanged. S3K oracle 139 to 143.
- **Every Sonic 2 request window reproduces from the command:** the
  special-stage transition and Chemical Plant windows were recaptured through
  the TraceChaser command and match their published digests, so no fixture
  depends on the retired candidate core.
- **S2 request capture is reproducible end to end:** the observer core now
  carries the S2 request marker (ABI 5, plain build), the TraceChaser command
  reproduces a published window's raw and payload digests, and the gitlink
  descends from a CI-green TraceChaser main.
- **S3K music DAC on the observed bus:** every sample write the chip performs
  now reaches the write observer, a sample-end edge emits the ROM's DAC
  disable, and the oracle compares the DAC byte stream whole-window while
  excusing only the per-service split that Z80 service duration decides.
- **Sonic 2 monitor sounds match the ROM:** ring and shield monitors send
  their sound through the music mailbox as `super_ring` does, and a monitor's
  explosion makes its request from its own slot a pass later when allocated
  below the monitor; the request oracle matches every replayable window.
- **S3K DAC enable follows the idle loop:** the driver records the sample-index
  store and the DAC enable is emitted by the idle loop at the next service
  boundary, as `zPlayDigitalAudio` does; the oracle now stops on the music DAC
  byte pump, which the engine renders inside the chip rather than on the bus.
- **ROM-less CI green again:** the complete-run producer tests and two Sonic 2
  level-init tests now skip without a ROM instead of failing.
- **Sonic 2 request-window producer is a command:** TraceChaser now captures
  and extracts request windows from arguments (movie, hash, row interval,
  manifests, installation, output), the raw sink records the recording it
  actually opened, and every published S2 window cites that command.
- **S3K first music update matches the driver:** the post-load DAC pass keys
  off FM6 and restores FM3 mode, `cfSetVoice` writes the release-rate reset,
  notes send the frequency once without a pan write, and PSG frequencies keep
  their full width; the S3K oracle clears tick 138 write for write.
- **Sonic 2 request oracle widened:** four new duplicate-captured request
  windows (EHZ1 continuation, the special-stage transition, and a Chemical
  Plant level-select route) are committed; the oracle matches all 52 transfers
  of the next 750 rows and pins a new first divergence at movie row 12,132.
- **Second Sonic 1 gameplay oracle is a full match:** the shipped
  `Sound_PlaySpecial` silence tail, which writes two stale data bytes instead
  of the intended PSG3 latch pair when a normal PSG3 effect is playing, is now
  emitted as the ROM does; both S1 gameplay recordings match end to end.
- **S3K request sidecar published and wired:** the fourteen source-observed
  mailbox writes are a committed comparison-only fixture the v2 oracle resolves
  against completed services; PSG tracks keep the ROM's AMS/FMS default and
  music activation emits `zBGMLoad`'s single register write, moving the S3K
  oracle from tick 128 to 138.
- **Sonic 1 gameplay oracle widened and doubled:** the GHZ1 window now runs
  to its real boundary (2,562 updates, MATCH), ending where the invincibility
  theme replaces the song, and a second recording from a different complete
  run (5,257 updates) pins a new first divergence at update 1,906.
- **S3K SEGA chant is played by the driver:** the Sonic 3 & Knuckles session
  now runs `zPlaySEGAPCM`'s blocking DAC transport itself (one byte per 248 Z80
  cycles, interrupts held), replacing the presentation-layer sample for that
  game; the S3K oracle moves from tick 50 to 128.
- **Sonic 1 gameplay driver oracle is a full match:** normal sound effects now
  read the special-effect voice bank the shipped driver's `SendVoiceTL` bug
  points them at, closing the last divergence; the engine matches the
  recorded driver over all 2,343 updates of the Green Hill gameplay window.
- **S3K driver init ends where the ROM's does:** the DAC idle loop's entry
  write now opens the first interrupt window instead of the init service,
  moving the S3K oracle from tick 0 to tick 50, the SEGA chant, whose PCM
  transport the driver does not yet own.
- **Sonic 1 special sound effects follow the driver:** the Green Hill waterfall
  now waits for a busy channel, restores its own voice on release, is walked
  after the normal effect slots, and survives a normal effect taking its channel,
  moving the gameplay oracle from update 618 to 1,759.
- **S3K driver oracle reference v2:** the AIZ1 reference is now sampled by
  the observer core at the driver's `zVInt` return, one tick per completed
  service (5,263 ticks over 5,400 frames, 725,898 writes), from two byte-identical
  captures, with the frame shape recorded and the frame field proven
  provenance-only; the first divergence is a single init write at tick 0.
- **S3K oracle reference sampling diagnosed:** the AIZ1 v1 reference samples
  driver RAM mid-invocation on music-load frames, so its tick-138 state is a
  truncated update rather than an engine divergence; the next S3K reference
  must be sampled at the driver's return from the vertical interrupt.
- **Sonic 1 gameplay oracle records special sound effects:** the gameplay
  probe now captures `Sound_PlaySpecial` dispatches alongside normal SFX, and
  the v2 reference (81 live dispatches) exercises the engine's special-SFX
  driver path for the first time, pinning its first divergence at update 618.
- **Sonic 3 & Knuckles music requests observed at the source:** the pinned
  TraceChaser observer now reads the `Play_Music` mailbox while the Z80 is
  stopped, supplying the request the AIZ1 oracle was missing; the driver
  comparison moves from an unobservable input at service 128 to a real
  divergence at update 138. The native observer build is a plain script with
  recorded provenance, no longer gated on host toolchain hashes.
- **Sonic 1 SFX channel ownership follows the driver:** sound effects claim
  their music channels at admission and the driver walks its fixed SFX slots in
  channel-RAM order, moving the gameplay oracle from update 302 to 629, where
  the reference's special-SFX dispatches are not yet captured.
- **Second Sonic 1 audio oracle from real gameplay:** a duplicate-captured
  reference from the complete-run movie (power-on through early Green Hill,
  2,343 driver updates, 70 live sound effects) now sits beside the sound-test
  oracles, with its first divergence pinned at update 302.
- **Committed Sonic 2 request-window fixture:** the driver-oracle gate now
  runs from a published, duplicate-captured reference in the repository rather
  than a scratch capture, and the next audio roadmap (S3K request authority,
  wider windows, two recordings per game) is recorded under the audio plans.
- **Sonic 2 sound driver parity:** the engine's SMPS driver now matches the
  recorded hardware driver over the full EHZ reference window (698 updates)
  with every sound request transfer agreeing, driven from the same BK2 movie.
  Level music starts on the shipped level-entry cadence, SFX release and PSG
  override semantics follow the Z80 driver, and Sonic 2 requests travel the
  ROM's mailbox and queue order.
- **DAC sample pitch:** optional DAC interpolation no longer stalls the
  sample clock (it played drums about 9.5 semitones flat when enabled), and
  the option now defaults to off.
- **S3K temporary-music restoration:** extra-life completion now preserves its
  fade-to-previous request through the presentation boundary, while the AIZ1
  miniboss escape restores the current level track directly.
- **Live fade-command coverage:** S3K fades immediately silence halted PSG
  channels; S1 fades stop SFX and clear speed-up state. Capture adapters share
  these production effects, with explicit limits on what their startup
  comparisons validate.
- **S3K special-stage music tempo:** ordinary song changes clear retained
  speed-shoes acceleration, without disabling the stage's own later speed-up.
  Blue-sphere contacts request sound even with a full animation queue, and
  S3K FM effects preserve the covered music track's rest state on release.
- **AIZ presentation continuity:** the Angel Island fire curtain now remains
  continuous across exact art-loading seams and completes its ROM-shaped
  release tail in normal play and trace renders, while level music restoration
  follows the ROM escape timer across the AIZ1-to-AIZ2 reload.
- **ROM-accurate gameplay systems:** physics, subpixel movement, sensors,
  collision, solid objects, water, camera behavior, title cards, level events,
  bosses, badniks, sidekicks, Super Sonic, and cross-game feature donation are
  implemented through ROM-owned rules and data. S3K data-select launches retain
  their retail entry cue, music-fade cadence, and destination reveal timing,
  including host-native equivalents when that presentation is donated to S1 or
  S2.
- **MGZ2 boss handoff physics:** jumping out of Tails's carry clears stale
  physical-object support, while the transition camera remains owned by MGZ2's
  native resize event.
- **ICZ multi-sidekick freezer support:** boss frost puffs and placed freezer
  clouds preserve native Player 1/Player 2 ordering while freezing every
  additional configured sidekick into a rescuable ice block, even when the
  native object pool is full.
- **Audio and video hardware modeling:** YM2612 FM, PSG, DAC/PCM, SMPS
  sequencing, priority rendering, tilemaps, shaders, sprite batching, and staged
  art loading have received substantial accuracy and stability work. Audio
  output runs through the unified presentation pipeline, which live recording,
  offline trace capture, and the standalone ROM-backed sound-test launcher all
  share. The current development branch includes the Java Nuked-OPN2 FM core,
  PSG corrections, session-owned SMPS state, request scheduling, and subsequent
  fade/SFX fixes. This supersedes the August 28 audio-baseline withdrawal;
  complete driver parity and listening validation remain unfinished. Backend
  performance experiments do not change the production FM backend.
- **Sound-driver reverse-engineering groundwork:** disassembly-cited routine
  maps and behaviour specs for all three games' SMPS drivers, an engine gap
  analysis, and committed driver-parity oracles (S1 GHZ music and sound-test
  SFX, an S2 EHZ windowed driver capture, and the S3K AIZ1 intro) with a new
  `docs/status/audio-frontier-log.md` recording every comparison; all
  artifacts are indexed from
  `docs/architecture/designs/audio/2026-08-30-sound-driver-re-index.md`. The
  first source-backed corrections now lock live sequencing to one V-blank
  service per outer frame and reproduce S2/S3K PAL repeat cadence and S3K's
  shared speed-up tail. Sonic 1's committed sound-test SFX oracle now matches
  all 1,967 ticks while its protected GHZ music oracle remains matched across
  14,690 ticks. Newer request sidecars allow comparisons beyond the original
  capture limitations, but published windows include known divergences: a
  recorded window is not necessarily a passing assertion. See the frontier
  log and audio handover for the current measured boundaries.
- **Resolve-ready capture:** live and trace recording can select DNxHR SQ video
  with lossless 24-bit PCM audio in a MOV container for DaVinci Resolve on Linux.
- **Gameplay-scoped rewind:** dynamic objects, child graphs, rider state,
  level events, audio history, and relevant static state are captured and
  restored with explicit ownership rules. Completed in-frame act reloads such
  as the AIZ fire-curtain transition now re-root history at the destination
  frame, preventing incompatible cross-act restores while preserving rewind
  within the new act. Sonic 1 swinging platforms now also stop chain creation
  cleanly when object RAM is full, so every retained link has a rewind identity.
  Sonic 2 special stages preserve their first pre-start object-pass deferral
  across fade-from-white restores.
- **Modern development and validation tools:** level-editor foundations,
  ROM offset and compression tools, headless gameplay tests, trace replay,
  visual/audio regression checks, and release/architecture guards. Trace
  recording, probing, and publication utilities live in
  [`OpenGGF/TraceChaser`](https://github.com/OpenGGF/TraceChaser), pinned here
  as an optional submodule. They are not required to build, test, package, or
  run the engine; trace contributors can initialize them with
  `git submodule update --init --recursive tools/tracechaser`. TraceChaser uses
  a verified official BizHawk 2.11 installation rather than vendoring the
  emulator.
- **Agent-friendly workflows:** Codex and Claude workflows include ROM
  cross-referencing, object/boss/zone implementation guidance, trace diagnosis,
  and worktree-local direct-Maven procedures. The canonical Sonic 1, Sonic 2,
  and Sonic 3 & Knuckles disassemblies are pinned as optional Sonic Retro Git
  submodules, so GitHub preserves the exact reference revisions without making
  them part of the engine's build, test, or runtime dependency graph.
- **Normal local launchers:** `run.sh`, `run.cmd`, `dev.sh`, and `dev.cmd` keep
  the direct package-and-launch workflow for interactive development; builds,
  tests, guards, and trace evidence use the same direct Maven boundary.

#### Current release status

0.6 is not a final release yet. Automated build, test, guard, and trace
no-regression gates remain active, and human end-to-end gameplay and audio QA
are still required before release sign-off. Known-red Sonic 2 CPZ2 and
Sonic 3 & Knuckles trace/run-chain frontiers are documented 0.6 limitations;
finishing those parity campaigns is deferred to the next release. A frontier
still returns to the 0.6 fix queue when it exposes a confirmed release-impacting
gameplay defect.

Fast FM is now integrated for 0.6. New configurations select `fast`, while
explicit `accurate` choices and physical-reference captures retain the accurate
core. All 178 supported chip scripts pass. Post-merge verification passes
16,970 ordinary tests and 610 guards, with unchanged pinned trace evidence
and successful universal-JAR smoke checks. Candidate benchmarks retain matching
gameplay digests; native-platform execution and full-game listening sign-off
remain release tasks. See the
[FM delivery record](docs/architecture/validation/audio/2026-09-06-fast-fm-release.md).
A follow-up performance pass makes the fast core about 12 % cheaper on real
music (S1 GHZ1 0.173 to 0.151 ms per frame, S3K AIZ 0.164 to 0.147) with every
oracle output sample and gameplay digest unchanged; the
[performance review](docs/architecture/validation/performance/2026-09-06-fast-fm-perf-review.md)
and the
[listening-test plan](docs/architecture/validation/audio/2026-09-06-fast-fm-listening-test.md)
record it.

Rewind checkpoints no longer re-clone every spent hardware-timing job. In S3K
AIZ1 after the intro, a checkpoint capture falls from about 287 KB to 33 KB and
a restore from 315 KB to 30 KB, so the steady rewind allocation and the burst on
engaging rewind both shrink by roughly an order of magnitude with no timing
decision changed. Only claimed jobs are memoized, so a coordinator that still
holds an unclaimed preparation is always re-captured. Focused rewind, timing
and S3K suites pass (2,860 tests post-merge); a further verification follows
the claimed-only correction.

S3K AIZ1 no longer stutters at its two runtime hand-offs. The intro terrain
swap at camera X $1400 swaps in tilemaps pre-built during level load instead of
rebuilding both full-level tilemaps on the frame (about 25 ms headless to under
1 ms), and pattern-only art writes such as the $2E00 fire overlay now refresh
the pattern atlas lookup rather than rebuilding tilemaps. The fire curtain's
act 2 reload installs a level built on a preparer thread across the fire
event's own wait; the reload always joins that build, and a regression test
confirms the reload frame, positions, tilemap bytes and registered art match a
synchronous reload (reload frame about 43 ms to 9 ms at 60 fps pacing). Related
S3K, Kos, level and transition suites pass except two AIZ trace replays that
fail identically on the pre-merge develop; guards pass (610). Measured through
the real renderer at 60 fps pacing, that reload frame is now about 11 ms:
in-game progression saves encode their snapshot on the frame but write the file
on a save-writer thread (flushed before slot reads and at shutdown), the fire
hand-off decodes its act 2 collision tables off the frame, and GPU sprite sheets
whose pixels are unchanged across the act reload are kept rather than re-uploaded.

The [September 6 release assessment](docs/architecture/audits/2026-09-06-release-blockers.md)
identified release skip-classification and trace-policy mismatches. The
[remediation record](docs/architecture/validation/2026-09-06-release-gates.md)
tracks their replacement with explicit capability checks and a fresh, pinned
trace comparison. A frozen candidate still needs complete platform evidence and
human gameplay/listening sign-off. Post-integration verification passed 16,687 ordinary
tests and 610 guards, with its known trace failures and reports unchanged against
a fresh baseline. Its universal JAR passed packaging smoke checks. Hosted validation also requires a configured `release-fixtures` runner.

Known limitations: some Game Over timing details remain documented in
`docs/status/known-bugs.md`; there is no modding framework, the level editor
is a dormant prototype, and complete SMPS audio parity remains unfinished.
The September 4 audio handover supersedes the August 28 withdrawal statement:
audio corrections and comparison tooling have landed, not complete parity or
release listening approval. The release summary carries commit-stamped
validation numbers, the guard and trace policy statements match
`docs/status/trace-scope-release-6.md`, and
`RELEASE_NOTES_v0.6.prerelease.md` is a pointer to the summary.
Contributor and player guides were corrected on August 28 (hook installation,
config defaults, player-2 bindings, dead links), and the skill mirrors were
resynchronised.
Repository hygiene followed: IDE/scratch files and two native libraries were
untracked, root plans and launcher scripts moved to `docs/architecture/` and
`scripts/`, saved third-party web pages replaced with provenance stubs, and
`CREDITS.md` now attributes every runtime library, test tool, and chip core.
The object checklists were regenerated from the registries (S2 122/122, S3K
173/303), S3K object `$4F` is now gated by zone set so DEZ no longer spawns
MGZ sinking mud, and the stale S2/S3K bug lists were folded into
`docs/status/known-bugs.md`.
Runtime decompressors moved from `tools/` to `com.openggf.data.compression`,
power-up visuals now come from per-game `GameModule` factories instead of a
shared spawner naming S3K classes, and four unreferenced classes were removed.
The GAME OVER / TIME OVER card now runs in all three games from ROM art and
mappings: a time over restarts the act, a game over fades to the title screen;
continue screens are still absent (`docs/status/known-bugs.md`).
The SN76489 PSG core was rewritten clean-room from public hardware documentation
(`docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md`), removing
the Genesis Plus GX-derived `psg.c` code; behaviour was verified against a pinned GPGX
harness (`docs/architecture/validation/2026-08-29-psg-clean-room-capture-comparison.md`).
The FM:PSG mix balance was restored to its pre-rewrite ratio (PSG preamp 38 % in the mixer),
recorded as uncalibrated against hardware in `docs/status/known-discrepancies.md`
(`docs/architecture/validation/2026-08-29-audio-mix-calibration.md`).

The current S3K release priority is the AIZ → HCZ playable route. Knuckles
routes, later-zone completeness, and some bonus/special-stage paths remain
outside the primary release slice or are still under active development.

#### Release documentation

- [0.6 changelog](CHANGELOG.0.6.md)
- [Release Summary for website and GitHub](docs/changelog/v0.6-release-summary.md)
- [Detailed development ledger](docs/changelog/v0.6-prerelease-detailed.md)
- [Trace scope and release evidence](docs/status/trace-scope-release-6.md)
- [Known discrepancies](docs/status/known-discrepancies.md)
- [Release-readiness roadmap](docs/project/release-readiness-roadmap.md)

### Previous releases

| Release | Notes |
| --- | --- |
| [0.5.20260411](CHANGELOG.0.5.md) | Architectural overhaul, S3K expansion, editor foundations, rendering/audio improvements, and stronger testing infrastructure. |
| [0.4.20260304](CHANGELOG.0.4.md) | S1 expansion, S2 additions, S3K AIZ bring-up, level-loading and tooling improvements. |
| [0.3.20260206](CHANGELOG.0.3.md) | Multi-game architecture, playable Tails, physics rewrite, major object/boss coverage, rendering and audio foundations. |
| [Earlier releases](CHANGELOG.md) | Historical 0.2, 0.1, 0.05, and 0.01 notes. |

See [CHANGELOG.md](CHANGELOG.md) for the release index and [CONTRIBUTING.md](CONTRIBUTING.md)
for contribution guidance.
