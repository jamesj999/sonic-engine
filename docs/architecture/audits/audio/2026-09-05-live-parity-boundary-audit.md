# Live audio and parity-harness boundary audit

## Scope and baseline

This investigation starts at `develop` `acd3a17cc` and compares local source
with fetched `origin/next` `55430b7b0`. It addresses the reported S3K Knuckles
intro fade, speed-shoes carryover into special stages, blue-sphere sound, and
places where an audio oracle supplies behavior missing from live playback.
It is not a claim of complete SMPS or audible parity.

## Confirmed fade masking

The live Knuckles-theme probe at six fade-entry phases (60, 120, 180, 240,
300 and 360 presentation frames) produced no PSG writes during the following
120 frames. PSG tracks became inactive with at least one hardware volume
latch still audible. The retail `zFadeOutMusic` falls through `zHaltDACPSG`
to `zPSGSilenceAll` (pinned Z80 driver, lines 2307–2325): it writes
`9F BF DF FF` immediately, while FM continues fading.

`S3kOpenGgfAudioCapture` previously supplied that silence itself, beside its
direct sequencer fade call. Its passing comparison therefore did not test
the production command boundary. The shared session fade operation now owns
the host-selected counter arming and physical silence, including when no
music sequencer exists. The capture and legacy backend use that operation.
The six live regressions failed before the change; a separate direct-backend
regression also failed with an empty write list.

S1 has the same category of masking: its capture stopped normal/special SFX
and cleared the speed-up flag itself. Retail `FadeOutMusic` owns those side
effects (`s1.sounddriver.asm`, lines 1360–1367). Host policy selects them;
the shared implementation splits preparation from arming because S1 capture
must remove SFX before the driver walks its sequencer list, but arm the fade
at the source-defined service point. Presentation metadata must retain the
session's resulting speed state so restore cannot resurrect the old flag.

The reported hanging tone stops before AIZ music begins. The missing PSG
silence is reproduced and source-backed, but that alone does not establish
the exact audible channel in the user's recording. Listening remains a
separate check.

Review also found a missing terminal boundary: reaching zero stopped only
local music tracks, or merely decremented the songless counter, instead of
calling `zStopAllSound`. The shared terminal operation now runs before the
terminal TL loop, clears SFX/save state/tempo and returns the existing stop
outcome so presentation metadata is cleared too. Source-configured cadence
does not imply host support: the new capability gate preserves existing
S3K-donor/S1-host local key-offs. Service-queued requests remain after the
stop and are processed in their original later phase.

Open follow-ups are recorded in
[known discrepancies](../../../status/known-discrepancies.md#audio-harness-startup-and-compatibility-fade-boundaries):
S1 production startup, mixed donor/host PSG fade semantics, and songless
legacy direct-read cadence. Native forward presentation and service-based
captures do not share that last limitation.

## Tempo ownership and `next`

The relevant historical `next` change (`09d7900a9`) also entered develop,
but its gameplay-loop resets were subsequently reverted by `b4c8fbd8a`.
The narrow reset in a game loop is not the correct general owner: ordinary
S3K music loading goes through `zStopAllSound`, which clears `zTempoSpeedup`
(Z80 driver, lines 1786–1787 and 2459–2473). The ordinary song-load policy
must clear retained tempo controls, including deferred loads and snapshots.
An ordinary replacement also abandons the temporary song backup.

The special-stage song is engine ID `1C`. A real AudioManager/ROM test is
required: unit-only tests initially missed the asset catalogue's config
copy boundary. Tempo reset and SFX-preservation policy are independent;
enabling one must not accidentally change the other.

The existing one-up override tempo behavior is not certified by this change.
The retail driver saves, clears and later restores the override tempo;
ordinary-load reset must not be mistaken for completion of that separate
state machine.

## Harness coverage inventory

The blue-sphere audit found no separate unmerged fix in `next` at the pinned
tips. Historical admission/register-takeover work already exists on develop;
the old dedicated blue-sphere tests and instruction-timing experiment are not
present on either tip. Their historical verification is not current coverage.
Two newly reproduced defects are corrected: `loc_97AA` must reach the sound
request at `loc_97BE` even when the animation queue is full
(`sonic3k.asm`, lines 12131–12142), and retail `cfStopTrack` must preserve
music rest bit 4 while clearing override bit 2 and restoring its voice
(Z80 driver, lines 3443–3518). A semantic FM release policy preserves the
different S1/S2 behavior. These defects do not by themselves establish the
cause of every audible orb complaint.

| Boundary | Finding and implication |
| --- | --- |
| S3K fade command | Harness-owned counters/silence masked missing production effects; replaced by shared session operation. |
| S1 fade command | Harness-owned SFX stops/speed clear masked missing production effects; shared preparation/arming preserves service ordering. |
| Legacy backend fade | Direct sequencer call bypassed host effects; routed through its owned stream. |
| S1 song startup | Harness still supplies initialization writes. Ownership-sensitive retail initialization is not established for production by these captures. Reports must state this limitation explicitly. |
| S2 song startup | Existing production compatibility policy already owns initialization. Harness now emits those same programs; literal partial/full track-header write contracts and unchanged oracle windows cover the consolidation. This is not evidence of a confirmed live startup defect. |
| S3K ordinary music admission | Capture stops the stream and constructs a fresh sequencer, then uses the production physical activation program. This does not test retained session tempo across a music replacement; the new live regression must cover that boundary. |
| External S3K tempo writes | Mailbox-only capture cannot reproduce direct game writes to tempo RAM. The existing DAC provenance investigation identifies this missing stimulus; do not hydrate candidate state from comparison RAM. |

Reference requests are input stimulus, not proof that every gameplay caller
submits the same requests. A full-game capture and a driver-service oracle
cover different boundaries; both are needed. An engine-to-engine snapshot
or PCM comparison can preserve a shared bug and is not a ROM oracle.

## Verification record

All runs use JDK 21, Maven's ordinary output directory, and absolute paths
to the three user-supplied ROMs. Commands below abbreviate those paths:

```sh
mvn -Dmse=off -B -Dsonic1.rom.path=<absolute-s1-rom> -Dsonic2.rom.path=<absolute-s2-rom> -Ds3k.rom.path=<absolute-s3k-rom> test
mvn -Dmse=off -B -Pguards -Dsonic1.rom.path=<absolute-s1-rom> -Dsonic2.rom.path=<absolute-s2-rom> -Ds3k.rom.path=<absolute-s3k-rom> test
```

- Main baseline `acd3a17cc`: ordinary console reports 16,544 executions,
  zero failures/errors, 43 skips; separate guards 609, all passing.
- Shared S3K fade focused run: 25 tests, all passing.
- Legacy backend plus live fade/session/diagnostic focused run: 38 tests,
  all passing. Before the fix the new backend test failed with no PSG writes.
- Combined `3529bf6c6`: 28 focused boundary cases pass; ordinary suite reports
  16,579 executions, zero failures/errors, 43 skips. The exact identity/status
  comparison preserves all 15,598 baseline identities and adds 35 passing
  cases (15,633 total). Fresh guards: 609, all passing, exact baseline match.
- Isolated speed/harness and orb branches each pass 16,552 ordinary executions
  and 609 guards; S1/terminal branch passes 16,559 and 609. Each preserves the
  baseline's existing outcomes. The S1 worker retained stale guard XML in its
  raw ordinary report directory: the comparison uses a clean selection of
  the 1,999 XML suite names matching the 1,999 completed ordinary log suites.
  Raw evidence is preserved separately. The combined run started with an
  empty report directory and needs no such filtering.
- Independent review caught and resolved the source/host terminal capability
  mismatch. Integration between task branches had no textual conflicts;
  overlapping session/config changes were combined and tested together.
- Merged into `develop` as `4ac8a4a12`, after fetching and confirming the
  integration baseline remained `acd3a17cc`. No integration conflicts or
  upstream changes required reconciliation; pre-existing disassembly edits
  were preserved.
- Post-merge ordinary suite: 16,579 executions, zero failures/errors, 43
  unchanged skips. Exact comparison again preserves all baseline outcomes
  and adds the same 35 passing identities. Fresh guards: 609, all passing,
  with an exact baseline identity/status match. Console execution totals
  are not unique testcase counts: nested XML duplication differs between
  runs, but both combined and merged runs contain 15,633 unique outcomes.

Final evidence is archived outside Git under the explicit task directory
`audio-boundary-2026-09-05`: baseline, isolated workstreams, combined and
post-merge report/log archives, plus the two listening WAVs. Only temporary
task worktrees/local branches are cleanup targets; ROM/disassembly symlink
targets and other existing worktrees are not.

Evidence filenames below each producing worktree's `target/` include
`s3k-fade-red.log`, `s3k-fade-boundary.log`, `legacy-fade-red.log`, and
`legacy-fade-green.log`. Main baseline report sets are
`s3k-audio-regressions-baseline-reports` and
`s3k-audio-regressions-baseline-guards-reports`.

## Controlled listening evidence

The [regenerable listening probe](../../research/audio/2026-09-05-live-fade-probe/README.md)
produces 528,000 stereo frames at 48 kHz: Knuckles music, fade at four
seconds, AIZ1 request at nine seconds. Baseline `acd3a17cc` and candidate
`3529bf6c6` are sample-identical for the first four seconds. During seconds
eight to nine, baseline has 95,994 nonzero channel samples (peak 685,
RMS 183.316 in signed 16-bit units), while candidate is exactly silent.
This demonstrates removal of a residual tone in the controlled stimulus,
not the exact identity/timing of the user's cutscene symptom or human
listening approval. The WAVs contain ROM-derived audio and stay outside Git.
