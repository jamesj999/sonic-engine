# Sonic 1 GHZ Music-Driver Parity Result

**Date:** 2026-08-09

**Result:** Full deterministic music parity across 14,690 ticks

## Scope and command

This run exercised the music-only Sonic 1 parity harness against the supplied
sound-test movie. It did not compare PCM, DAC sample timing, sound effects, or
music/SFX contention.

The original four-capture run on `feature/ai-s1-audio-parity` established a
valid mismatch at tick zero. Follow-up work on the isolated local review branch
`bugfix/ai-s1-audio-parity-frontier` used that result as the starting frontier.
The final comparison used the same pinned inputs and reference-controlled
interval. This branch is intentionally not merged into `develop` pending human
audio testing.

```bash
S1_ROM_PATH='<repository-root>/Sonic The Hedgehog (W) (REV01) [!].gen'
tools/audio/run_s1_audio_parity.sh --rom "$S1_ROM_PATH"
```

The final comparator returned `0` and reported `MATCH (14690 ticks)`.

## Input identity

| Input | Verified identity |
|---|---|
| ROM | Sonic 1 World REV01; SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`; CRC32 `afe05eee` |
| BK2 | `s1-soundtest-ghz.bk2`; SHA-256 `622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c`; 989 input rows |
| Emulator | BizHawk 2.11, Genesis Plus GX; `EmuHawk.exe` SHA-256 `b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3` |

The reference observer selected the proved `memory_callback` path, so there was
no PC-manifest fallback. Its callback proof covered 26,143 YM2612 port-zero
pairs, 4,363 port-one pairs, and 23,530 PSG writes. The 514 pre-epoch
`UpdateMusic` invocations were launch diagnostics only and were not replayed by
OpenGGF.

## Determinism and cycle proof

Detailed final evidence remains locally in the ignored run directory
`target/audio-parity/s1-ghz/run.qzlzklGq/` in the review worktree.

| Producer | Files | SHA-256 for each file | Bytes each | Records |
|---|---|---|---:|---:|
| BizHawk reference | `reference-1.jsonl` | `5941958c4eb38da4f71e1e5860b49b2d13d6fa0aaedcf244fa7b8d4ecb5d6efc` | 117,646,785 | 14,690 ticks plus metadata |
| OpenGGF | `openggf-29.jsonl`, `openggf-30.jsonl` | `06f2fda57779b6e1ec53078bc3040ff49135ff89ddb37bb325ef5d4f5e65187a` | 45,876,731 | 14,690 ticks plus metadata |

`cmp` confirmed byte identity for the two independently generated final
OpenGGF streams. Their metadata differs from BizHawk by producer provenance and
non-gating diagnostics, as intended. After projecting every record to its
ordered `{events,state}` contract, all three streams have the same SHA-256,
`b81ad3a74044a22ae2d02b22715bba3b67414022f8d8b940d82f7c32bf030d7b`.
Every stream spans ordinals 0 through 14,689. Reference recurrence starts at ordinal
5,473 with period 4,608; the terminal ordinal is
`5,473 + (2 * 4,608) = 14,689`, proving one complete cycle followed by an
identical repeated cycle.

The reference run reached the recurrence stop and published normally. The
probe would have failed before publication on a second `$81`, a queued sound,
pause/fade/reset/Sega-PCM/speed-up contamination, a changed sound-test state,
or non-neutral post-movie input; none fired. BizHawk emitted non-fatal X11
`BadMatch` diagnostics during window initialization, but both output streams
remained byte-identical.

## Resolved parity frontiers

Each change below was driven by the first comparator mismatch, checked against
the shipped `FixBugs=0` sound driver, and locked with an adjacent state or
chip-write regression. Existing chip-port ordering was left intact except where
the reference proved a specific missing, extra, or misordered transaction.

- The DAC track's `$10` word is `SavedDAC`, not a frequency; the parity schema
  now omits `baseFrequency` for DAC while retaining it for FM and PSG.
- The OpenGGF epoch now begins with the ROM's `InitMusicPlayback` silence and
  GHZ header-load writes, including the shipped `FixBugs=0` absent-FM6 alias.
- S1/S2 PSG rests retain the `$FFFF` invalid-frequency sentinel, rest envelopes
  advance without emitting volume writes, and `nMaxPSG` preserves period zero.
- S1 note-fill uses its independent countdown and skips the remainder of the
  track update when it expires.
- The 68k voice path now uses the ROM's operator/register ordering, byte-add TL
  rules, carrier mask, and lack of injected key-off/SSG clears.
- S1 `smpsNoAttack` remains latched through the tied note: it suppresses
  `FMNoteOff` and per-note resets but does not suppress the later `FMNoteOn`.
- S1 modulation halves its configured step count, advances on resting tracks,
  writes the signed frequency word verbatim, and reloads phase on non-tied rest
  transitions.

The last mismatch moved from tick zero through ticks 96, 97, 376, 878, 883,
and 3,049 before the final full-cycle match. `parity-report-final.txt` and
`parity-report-final.json` contain the bounded final success result.

## Verification

The final explicit parity, sequencer, chip-observer, capture, comparator, JSONL,
and rewind-snapshot test set ran 104 tests with zero failures or errors and one
local-only capture skip. The full three-ROM suite ran 14,491 tests and retained
the recorded red baseline: 36 failures, 14 errors, and 32 skips. No modified
audio, parity, capture, or rewind-snapshot test failed. The four initially new
Sonic 2/generic sequencer regressions were removed by expressing S1's direct
68k update contract as an explicit disabled-by-default sequencer capability,
instead of inferring it from the modulation algorithm shared with Sonic 2.

No detailed capture or report file is tracked. They remain ignored for local
human review and can be regenerated from the pinned ROM, BK2, and toolchain.
