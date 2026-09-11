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
| Sonic 3 & Knuckles (S3K) | Work in progress. AIZ through LBZ have substantial route coverage, including shipped-ROM collision handling for the AIZ2 end boss, but this is not full parity: route/trace validation remains outstanding for the AIZ miniboss napalm FallingShot and AIZ2 end-boss splash children, while Knuckles' LBZ Big Arm handoff remains inert. |

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
binaries. Third-party libraries (LWJGL, and Jackson for `config.yaml` and save files) are kept on
advisory-free versions: a Dependabot advisory against `master` is replicated onto `develop` and
`next` as an aligned bump rather than merged into the released line.

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

A high-level summary of what 0.6 is about. Every individual change is recorded
in [the 0.6 changelog](CHANGELOG.0.6.md).

- **Three-game engine:** Sonic 1, Sonic 2, and Sonic 3 & Knuckles each run from
  their own ROM through shared, rule-driven systems — physics, subpixel
  movement, sensors, collision, solid objects, water, camera, title cards,
  level events, bosses, badniks, sidekicks, Super Sonic, and cross-game feature
  donation are implemented from ROM-owned rules and data rather than per-game
  special cases.

- **Playable routes:** Sonic 1 has broad end-to-end coverage, Sonic 2 covers
  most zones, and Sonic 3 & Knuckles is playable through the Angel Island to
  Hydrocity slice with later zones in progress. 0.6 extends zone-specific
  behaviour across Wing Fortress, Casino Night, Marble Garden, Icecap and
  Angel Island, including multi-sidekick support and act-transition continuity.

- **Sound driver accuracy:** the largest single area of 0.6. The engine's SMPS
  driver, YM2612 FM, PSG and DAC/PCM paths were reverse-engineered against the
  three ROMs' drivers and are now checked by committed driver-parity oracles
  covering music, sound effects, fades, extra-life restoration and request
  scheduling. Full parity and listening validation remain open; the measured
  boundaries are recorded in
  [`docs/status/audio-frontier-log.md`](docs/status/audio-frontier-log.md) and
  the [audio handover](docs/architecture/plans/audio/2026-09-04-audio-parity-handover.md).

- **Faster FM core:** new configurations select a register-level FM core that
  passes all 178 supported chip scripts; `audio.fmCore=accurate` still selects
  Java Nuked. Audio playback now allocates about an eighth of what it did per
  frame on either core, with every oracle sample unchanged. Full-game
  listening sign-off remains open. See the
  [fast FM validation record](docs/architecture/validation/audio/2026-09-06-fast-fm-release.md).

- **Runtime performance:** capture, rewind, art decoding, animated-art uploads
  and shader state handling all do less redundant work per frame. See the
  [initial measurements](docs/architecture/validation/2026-09-05-runtime-performance.md)
  and [follow-up evidence](docs/architecture/validation/performance/2026-09-05-followup.md).

- **Continue screens:** ROM-backed countdowns and character sequences follow
  Game Over when continues remain, with per-game restart behaviour.

- **Gameplay-scoped rewind:** dynamic objects, child graphs, rider state, level
  events, audio history, and relevant static state are captured and restored
  under explicit ownership rules, including across in-frame act reloads.

- **Recording and capture:** live and offline trace recording share the
  presentation pipeline and can emit DNxHR SQ video with lossless 24-bit PCM
  for DaVinci Resolve on Linux.

- **Development and validation tools:** level-editor foundations, ROM offset
  and compression tools, headless gameplay tests, trace replay, visual/audio
  regression checks, and release/architecture guards. Trace recording, probing
  and publication live in [`OpenGGF/TraceChaser`](https://github.com/OpenGGF/TraceChaser),
  pinned as an optional submodule that is not needed to build, test, package or
  run the engine.

- **Agent-friendly workflows:** ROM cross-referencing, object/boss/zone
  implementation guidance, trace diagnosis and worktree-local Maven procedures,
  with the three canonical disassemblies pinned as optional submodules outside
  the build and runtime dependency graph.

- **Normal local launchers:** `run.sh`, `run.cmd`, `dev.sh` and `dev.cmd` keep
  the direct package-and-launch workflow for interactive development.

#### Current release status

0.6 is not a final release yet. Human end-to-end gameplay and audio QA are
still required before release sign-off.

Level loads no longer intermittently fail or detect the wrong game. The ROM is
read from two threads during a load, and the header readers and the Sonic 3 &
Knuckles art loaders were seeking the shared file handle without the lock every
other reader holds, so a background read could move the position out from under
them.

Angel Island Act 1 no longer fails to load when its intro tilemap pre-build
cannot read the ROM; the pre-build is a cache warm the terrain-swap frame
already knows how to do without.

Continuous integration has been cut back to a fast per-push check. A push runs
only the `smoke` profile -- the ordinary suite minus ten exhaustive oracle
sweeps that were most of its runtime -- so it finishes in minutes instead of
half an hour. The full suite, the structural guards and the trace replay
fixtures now run on pull requests and on demand, with no scheduled run, so a
regression in the guards or the excluded sweeps is caught by a contributor
running them locally rather than by CI. Release validation is unchanged and
still runs everything. Known-red Sonic 2 CPZ2 and
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

Special-stage entry now plays the ROM's transition in normal play: in Sonic 2
the level freezes and fades to white over 22 frames while the entry sound and
the music fade run, the screen stays white through the stage's startup waits,
and the stage music starts with the fade from white; Sonic 1 and Sonic 3 &
Knuckles keep the level on screen through their own fade-to-white the same way,
and every stage now loads its palette after that fade rather than during it.
`gameplay.loadTimeSimulation: FAST` is a real mode and the default, resolving
to a hand-tunable copy of the measured S3K load-time profile that also paces
the title screen's Sonic frames 8 to A from the original hardware capture.
Special-stage unit, headless and replay suites pass across the three games,
the ordinary suite and guards pass, and every run-chain failure is identical
to the pre-merge develop.

`config.yaml` now holds only the settings you changed. Defaults live in the
program and are documented by `config.yaml.example`, so a default that changes
in a later build reaches every install that never set the key, with no
migration or version bump; changing one is three edits and a guard test keeps
the example honest. Files written by older builds are converted once on load,
keeping your changes and dropping materialised defaults, including the former
`loadTimeSimulation: NONE`. The ordinary suite (16994) and guards (610) pass.

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

- [Publishing a GitHub release](docs/project/release-publishing.md) — pushes to
  `master` automatically publish after validation and builds succeed.
- [0.6 changelog](CHANGELOG.0.6.md)
- [Release Summary for website and GitHub](docs/changelog/v0.6-release-summary.md)
- [Detailed development ledger](docs/changelog/v0.6-prerelease-detailed.md)
- [Archived entry-by-entry 0.6 ledger](docs/changelog/v0.6-development-ledger.md)
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
