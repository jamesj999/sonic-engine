# Shared SEGA DAC gain

The three boot chants now use `SmpsDriverSession`'s ROM-backed PCM transport,
`SmpsPhysicalDevice`, and the same YM2612 DAC and `VirtualSynthesizer` mixer as
music samples. There are no game-specific gain values. This establishes a
consistent hardware model; it does not calibrate the analogue output against
a console recording. The existing [mixer calibration limitation](../2026-08-29-audio-mix-calibration.md)
still applies.

Previously S1/S2 used `SampleBackedVoice.rawSegaPcm`: unsigned 8-bit to signed
16-bit conversion followed by a legacy 0.25 gain. That bypassed the current
YM output scale and the synthesizer's master gain. S3K already used the chip.
The generic standalone voice remains available for profiles without a physical
transport; none of the three shipped profiles uses it for the chant.

## ROM evidence

Verified the root ROMs against the repository's CRC32/SHA-1 identities:
S1 REV01 `AFE05EEE`, S2 REV01 `7B905383`, locked-on S3K `63522553`.
Reference trees are research inputs only; runtime bytes still come from the
active ROM through `loadSegaPcm`.

| Driver | Source | Loop | End behavior |
|---|---|---|---|
| S1 | `s1.sounddriver.asm:PlaySegaSound`, `sound/z80.asm:zPlaySEGAPCMLoop` | 90 + 13 × 10 = 220 Z80 cycles/byte | Return to sample idle; leave DAC enabled |
| S2 | `s2.sounddriver.asm:zPlaySegaSound` | 152/2 + 13 × 11 = 219 Z80 cycles/byte | Restore music DAC disposition |
| S3K | `Sound/Z80 Sound Driver.asm:zPlaySEGAPCM` | 105 + 13 × 11 = 248 Z80 cycles/byte | Return through DAC idle entry, disabling DAC |

Decompressed S1's Kosinski archive at ROM `0x72E7C`: Z80 `0x00C8` contains
`06 0B` (`ld b,11`). Decompressed S2's headerless Saxman driver at ROM
`0xEC0E8`, length `0x0F64`: the chant contains two `06 0C` delay loads.
Both halves of S2's pair have equal byte-to-byte cost, despite different
queue-check and sample-count work. The shipped `FixDriverBugs=0` omits S2's
optional panning reset; the transport preserves that choice.

S1's compatibility boot does not yet reproduce `StopAllSound`'s DAC-enable
write. The chant transport explicitly establishes that ROM prerequisite at
entry. This is not a claim of complete boot-register or CPU-cycle parity:
S1's 68000 busy-wait duration, sub-output-sample write timing, and the existing
compatibility initialization remain separate from the shared gain invariant.

S2's DAC disposition is retained independently of live music tracks, including
session snapshots and live-mutation rollback. Ordinary song completion must not
clear it. New S2 sound requests terminate the chant before mutating that state,
matching the loop's `QueueToPlay != 0x80` exit condition at the host's command
boundary. S3K keeps its explicit stop-command behavior.

## Verification

Base: `develop` at `634983c00e807704c2e7f944f73b0dd43777d77a`.
Development worktree: `.worktrees/sega-dac`.

Focused command (`MAIN_REPO` is the absolute path of the main checkout), with all three absolute ROM paths supplied:

```sh
mvn -Dmse=off -B \
  -Dtest=TestCrossGameSegaDac,TestSmpsSegaPcmTransport,TestSegaPcmCommandRouting,TestVirtualSynthesizerMix \
  "-Dsonic1.rom.path=${MAIN_REPO}/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=${MAIN_REPO}/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=${MAIN_REPO}/Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

The production-command test captures two seconds at 60 presentations/second,
asserts absence of a standalone PCM voice, and compares every observed DAC
byte with the complete ROM sample. The observed accurate-core output was:

| Game | ROM bytes delivered exactly once | Peak absolute 16-bit output |
|---|---:|---:|
| S1 | 27000 | 3319 |
| S2 | 24948 | 3313 |
| S3K | 24111 | 3169 |

These are engine measurements, not hardware recordings or loudness targets.
Different sample content and cadence can legitimately produce different peaks.
A synthetic held DAC code separately produces exactly equal steady output
across the three policies on each of the accurate and fast FM backends.
Rewind and split-versus-whole rendering are checked across all three transports.
The existing S3K stop-command tests remain in the focused selection.

The focused lifecycle selection also includes `TestSmpsSessionTransitionMatrix`,
`TestSmpsSessionSnapshot`, `TestSmpsStatefulCommandPolicy` and
`TestTitleScreenAudioRegression`: 61 tests passed, none skipped. The completed
baseline full run reported 17036 tests with 44 skips and no failures; the baseline guard run passed
613 tests without skips. Full-suite skips include unavailable GL capture,
opt-in measurements, missing reference audio/BK2 inputs, and the existing CPZ
spin-tube assumption. The three new ROM-backed chant checks do not skip.

Final development full run: `mvn -Dmse=off -B` with the same three ROM properties
and `test`, completed 2026-09-08 10:13:56 Europe/London: **17047 tests,
0 failures, 0 errors, 44 skips**. Comparison by test identity found no changed
skip identities or new failures. The old assertion that S1/S2 lacked a DAC
transport was replaced by transport/gain/lifecycle checks. An earlier full run
exposed the title-reset test's obsolete standalone-voice expectation; it now
checks that reset stops the actual DAC transport.

Final development structural guards: `LUA_BIN=lua5.4 mvn -Dmse=off -Pguards test -B`, completed 2026-09-08 10:17:00 Europe/London: **613 tests,
0 failures, 0 errors, 0 skips**. No new source edits followed these checks.
