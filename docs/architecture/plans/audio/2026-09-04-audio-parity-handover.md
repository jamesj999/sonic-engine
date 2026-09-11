# Audio parity programme: handover (2026-09-04)

Debrief and handover for whoever picks up the SMPS audio parity work after the
2026-09-03/04 lead session. Everything below is on `develop` unless a branch is
named. Numbers are stamped with the commit they were measured on; re-measure
before relying on any of them (see the measurement-hazard table in
`docs/agent-workflow/briefing-trace-rounds.md`).

## September 5 next milestone (delivered)

### Active per-game driver parity campaign

Branch `feature/ai-audio-trace-coverage` carries the
[revised roadmap](2026-09-05-audio-trace-coverage-roadmap.md). The objective is
faithful behavior of each supported retail SMPS driver, with source-mapped
controlled differential cases and continuous movies providing complementary
evidence. Trace infrastructure alone is not the deliverable.

Develop merge `a1e00c643` removes a duplicate PSG note-start volume write and
repairs pair-level PSG ownership and FM3 mode restoration, advancing the S3K
frontier from service 1570 event 43 to service 1592 (`MUS_PSG3.volEnv`, reference
0 versus engine 1). The ROM's second byte `FF` retains its actual
hardware latch semantics; it is not masked into a data byte. The pair is
admitted once at the source track gate, and rejected pairs change neither bus
nor latch state. FM3 mode is restored before its music voice, and both driver
fade counters are now directly compared, including songless services.
Production observer tests now
expose blocked ring decisions and detect deliberately disabled suppression.
These are not full-run parity claims. Integration and exact post-merge regression
comparison passed: 16,641 ordinary executions, no failures/errors, 43 unchanged
skips, and 609 separate guards with no failures/errors/skips. Develop was pushed
through `7f73667b8`; completed worktrees and their merged local branches were
removed. The next isolated envelope/S2 cadence cycle is not part of this result.

### Second Sol cycle (delivered)

Develop merge `d004188d9`, pushed through `c7d04fbc3`, preserves S3K overridden-attack envelope timing and
byte-wrapped EC cursor state, corrects the S2 loader's DAC budget to the retail
295 cycles, and renames the S1 missing-reference test truthfully. The S3K
matching prefix reaches service 1651; service 1652 event 0 remains an ordered
write mismatch. Read-only diagnosis points to fixed SFX channel-slot walking;
that repair is not part of this batch. Later envelope+255 consumption is also
unverified. The [second-cycle record](../../validation/audio/2026-09-05-sol-smps-parity-cycle2.md)
owns exact verification and delivery status; do not infer full-run parity.
Post-merge ordinary verification passed 16,650 tests with 43 unchanged skips;
609 guards passed, both with exact candidate outcome equality. The completed
worktrees and their merged local branches were removed after push.

TraceChaser tempo-read capture, native reproducibility repairs and fresh S1
diagnostic work remain local, separately identified evidence. Production
provenance and authorized publication are not established by those diagnostics.

### Third Sol cycle (delivered)

Candidate `8306b9a3a` combines reviewed S3K fixed SFX slot walking and the
retail PSG stop-silence transaction. It is based on develop `32522d7cb`, which
also contains the intervening audio-performance change. Its expanded 75-test
focused run and 16,654-test ordinary suite pass with 43 unchanged skips; 609
separate guards pass with exact baseline equality. Develop merge `ebb024201`
passes the same ordinary and guard suites with exact candidate outcome equality;
develop was pushed and the completed worktrees/branches removed. Evidence is in the
[third-cycle ledger](../../validation/audio/2026-09-05-sol-smps-parity-cycle3.md).
The matching intro prefix reaches service 1689; service 1690 event 7 remains
a raw music-noise restoration discrepancy, separately assigned and not included
in this candidate. Physical-write mutations prove both a missing final FF and
an incorrectly admitted ordinary overridden write are detected.
The initial ordinary run's four assertion failures are retained: two omitted
the retail stop byte and two mixed ending SFX with same-frame music traffic.
Source-backed test corrections preserve that traffic and detect shortened tails.

### Fourth Sol cycle (delivered)

Candidate `d14277e8f`, based on develop `e73ca442f`, restores covered S3K PSG
music after F2 using the exact signed raw noise byte, preserving playing/rest
state. The exact ending track releases only its own locks and admission claim
before the callback. Generic teardown retains its prior behavior; retail E3/E4
are distinct source paths, not implicitly certified by this change.

The matching intro prefix now reaches service 2011. Service 2012 event 1 remains
`BF` versus `FF`: source review identifies the preceding header within the same
Skid admission, not persistent retired-slot RAM. The first header's entry-IX
behavior remains separately unverified. Do not introduce historical slot state
to solve the proven intra-admission mismatch.

The 125-test focused run and 16,661-test ordinary suite pass, with 43 unchanged
skips. Exact comparison preserves every baseline outcome after one reviewed,
strengthened music-F2 test rename and adds seven passing identities. All 609
separate guards pass with exact baseline equality. Develop merge `1e33747f1`
passes both suites with exact candidate outcome equality. Develop was pushed
through `250b3409b`; the completed worktree and merged local branch were removed.
Evidence belongs to the
[fourth-cycle ledger](../../validation/audio/2026-09-05-sol-smps-parity-cycle4.md).
Native S1 capture A is now genuinely sealed, as recorded below, but remains
diagnostic rather than authenticated. Neither a matching prefix nor the ordinary
suite proves full-game driver parity.

### Fifth Sol cycle (delivered)

Reviewed source `32a37dd62`, updated to develop `cc75320b0` as `97aee3f30`,
keeps SFX admission in ROM header order while retaining fixed channel-slot
order for ongoing service. The previous initialized header supplies the retail
PSG silence before the next header's unconditional `FF`. Skid's PSG2→PSG1
sequence is `FF BF FF`; reverse and intervening-FM controls reject a fitted
byte or a stale earlier PSG selector. Constructor-derived indices resolve
current tracks after snapshot restoration without adding temporal state.

The matching intro prefix reaches service 2356; service 2357 now exposes
`MUS_FM4.overridden`, reference `false` versus engine `true`. First-header
entry-IX behavior and arbitrary non-retail initial header flags remain outside
the bounded repair. The 92-test focused run and 16,666-test ordinary suite
pass, with 43 skips. Merge `8a7dc5f15` passes the same ordinary suite and 609
separate guards with exact candidate outcome equality. Develop was pushed through
`9d153cf6a`; the completed worktree and local branch were removed. Evidence is in the
[fifth-cycle ledger](../../validation/audio/2026-09-05-sol-smps-parity-cycle5.md).

### Sixth Sol delivery group (delivered)

The reviewed coverage report, S1 prerequisite probe and S3K ring capture adapter
are merged through `ad152601b`. Their individual ordinary/guard comparisons
preserve the refreshed baseline and add 21 passing tests. Combined develop
`f56d4fae1` passes 16,687 ordinary tests with 43 unchanged skips, 609 guards and
24 focused checks, with exact baseline/candidate comparisons. Develop was pushed
through `be4428602`; the three completed worktrees and local branches were
removed. Evidence is in the
[group ledger](../../validation/audio/2026-09-05-sol-smps-parity-delivery-group.md).

The fixed complete-run profiles still report `full_parity=false`. S1 production
requests first differ at BK2 row 972 (`B5` versus no request), before the one-up;
no restoration values have been compared. The S3K diagnostic prefix now matches
through service 2408; service 2409 differs in `MUS_PSG1.overridden`.

Next behavioral priority is a source-backed production S3K request consumer:
three-slot consumption order, one-up conditional music/unconditional SFX
clearing, restore deferral, dispatch-time ring selection and boot-only speaker
initialization. Keep load-plan deferral separate: the current
`blocksForwardRequestConsumption` gate covers a non-immediate pending load,
not retail one-up suppression. The current forward resolver admits only one
consequence per boundary, so wiring an incomplete one-request service would
introduce different errors. Design and test the complete owning transaction
before connecting it; do not promote the capture-local selector into a false
production-parity claim. Native authentication/publication remains separately
blocked on its existing provenance and approval requirements.

### Full S1 capture A (sealed diagnostic, unpublished)

The native full-movie run completed 225,101 frames and 224,241 audio rows on
September 5. Its terminal and attestation report zero faults/overflows, and the
root independently verified the raw SHA-256:
`798c2197005c88abf99173629815220fe4d574274d9fa774be76fdeb37d57122`.
The 25,700,433,659-byte raw and its original attestation are preserved under
`${S1_CAPTURE_EVIDENCE}/s1-capture-a/`; the sibling invocation and diagnostic
records identify the reproduced observer and exact command. One override/resume
boundary was observed at frame 3910, with 261 ordered writes and one PCM packet.
This is successful capture, not verified Java parity.

The genuine metadata reports installed native ABI 5, while the production
extractor accepts literal ABI 4. S1 still configures the ABI-4 interface; the
new ABI adds an action not used by S1 and retains the event layout. Compatibility
and producer authenticity remain different obligations: raw metadata and
attestation do not contain the exact core/patch/managed producer identity.
Do not relabel the capture or weaken those gates. Duplicate S1 and S2 capture
pairs, authenticated producer binding and authorized publication are still
required; no second capture, native push or OpenGGF submodule pin update occurred.
Local TraceChaser handover commit `97e7ccc` records the detailed ABI assessment.

### 1-up admission follow-up

The [1-up suppression audit](../../audits/audio/2026-09-05-s3k-oneup-sfx-suppression.md)
records a further live-path gap: S3K admitted new jump/ring effects during the
jingle. The existing AIZ end-to-end movie exercises jump overlap at frames
7733–7906 after the 1-up starts at 7700. The correction wires the existing
rewind-captured admission gate to the host override lifecycle, preserves ring
stereo selection on discard, and releases at restoration or global stop.
The 5,400-frame intro oracle still contains no 1-up and still bypasses this
production admission boundary; do not report these live regressions as a
full-run oracle pass.

### Later live-boundary follow-up

The [live audio boundary audit](../../audits/audio/2026-09-05-live-parity-boundary-audit.md)
records the later `develop` merge `4ac8a4a12`: shared native S1/S3K fade
effects, S3K terminal global stop and presentation cleanup, ordinary-load
tempo reset, two source-backed blue-sphere/FM-release fixes, and S2 harness
startup consolidation. Post-merge ordinary verification passes 16,579
executions with 43 unchanged skips; fresh guards pass 609. Exact comparisons
preserve every baseline outcome and add 35 passing identities.

Do not read matching driver windows as production song-start validation.
S1 startup, mixed donor/host PSG fade semantics and songless legacy direct-read
cadence remain explicit follow-ups. The controlled listening stimulus removes
the baseline's residual tone, but does not certify the user's exact cutscene
or orb symptom. The audit links the regenerable probe and remaining gaps.

The [next-milestone plan](2026-09-05-audio-next-milestone.md) starts from
`bbf28b7dc` and is merged into `develop` as `e258282e0`. Post-merge ordinary
verification passed 16,497 executions (22 existing skips), with 609 separate
guards and no failures/errors. Six ICZ methods were rerun successfully to
resolve overwritten nested-class XML records; the
[delivery record](../../validation/audio/2026-09-05-audio-next-review.md)
preserves that distinction. Verified work was pushed through `b8f474379`;
all five milestone worktrees and their local branches were removed afterward.
Original and first
follow-on measurements below remain historical.

- PSG correction `5ee8bb8ae` removes the synthetic takeover FF. Hard comparison
  retains 1,570 whole services and now the first 43 ordered writes of service
  1570; the next difference is event 43, reference YM0 A4=22 versus engine PSG
  F0. The next source-backed candidate is a duplicate volume tail after a
  modulated PSG note, not another admission-silence change. Stale-IX admission
  behavior remains separately incomplete.
- DAC diagnosis `b710033b2` / `c877fca10` changes reporting, not playback:
  ordinal run 338 starts at reference service 3658 versus engine 3837. At the
  same service 3837, reference run 363 has the engine sample's prefix. The
  first track scheduling difference is service 2940, where reference speed-up
  state changes without a mailbox request. The retail tempo routine writes
  that control directly, an input absent from the mailbox-only replay; enable
  timing first differs at 2943. Do not change the decoder or replace 297 with
  303 based on the old 88-versus-7F report. See the
  [source-backed evidence](../../validation/audio/2026-09-05-s3k-dac-run-provenance.md).
- Replay-bound capture `2d1ecf68d` proves a known reset-origin segment and
  exact native-cycle endpoint, including queue-drain time. An output gate is
  distinguished from raw chip mutation. Repeated one-up plus mid-fade snapshot
  coverage now includes AIZ1/2 and HCZ1/2. See the
  [scope and focused evidence](../../validation/audio/2026-09-05-bounded-ym-replay-and-slice.md).
- Required next reference work is observation of the actual external tempo
  control write, not hydration from later driver RAM. Human listening and
  low-end hardware validation remain open. Native/fast experiments must not
  be described as shipped backends.
- Performance research `c43e61e2e` proves actual-PCM JNI and partial-frame
  snapshot transfer on HotSpot and GraalVM Native Image on Linux. AIZ1's
  799,005 raw stereo frames match Java/C Nuked exactly. A small Java trial
  showed no reliable gain and was reverted; ymfm's deterministic replay uses
  frame-quantized writes and does not establish equivalent fidelity. See the
  [new measurements](../../research/audio/2026-09-05-fm-performance-follow-up.md).

## First coordinated follow-on milestone (delivered)

Delivered on September 5 as `develop` merge `078e5df6f`, pushed after post-merge
ordinary and guard verification with no new failures or skips. All four task
worktrees/local branches were cleaned up; the linked delivery record contains
the exact evidence and the remaining product decisions.

The follow-on round starts at `develop` `4296bc291`; the sections below retain
the original handover's historical measurements. See the
[coordination plan](2026-09-04-audio-correctness-and-evidence.md) and
[review/verification record](../../validation/audio/2026-09-04-coordinated-audio-review.md)
for delivery status, rather than interpreting the original "no lane running"
statement as current status.

- S3K admission correction `5aca88c31` matches 1,570 services, ordinals
  0–1569. The next first difference is service 1570/event 39, reference PSG
  `E7` versus engine `FF`; DAC remains run 338/byte 0, `88` versus `7F`.
  Resume from the newest audio-frontier entry, not the historical 1569 command
  interpretation below.
- The [FM performance research record](../../research/audio/2026-09-04-fm-core-performance-exploration.md)
  and `tools/audio/fm-core-benchmark/` preserve bounded backend experiments.
  Java Nuked remains the production core. Native/JNI feasibility and a faster
  candidate core do not establish platform readiness or listening parity.
- Physical capture `aefd59738` / `75c10b85a` adds opt-in raw YM/PSG strobes,
  DAC origins and discontinuity markers. The
  [capture contract](../../validation/audio/2026-09-04-physical-chip-capture.md)
  describes clock units, provenance and bounded replay validation. It is not
  a whole-mixer replay or a new hardware-reference capture.
- Ordinary baseline at `4296bc291`: 16,465 reported executions, zero failures
  or errors, 40 skips; separate guards: 609, zero failures/errors/skips.
  Combined and merged verification belongs to the linked delivery record.

## Where develop stands

- `develop` head at handover: `a306c0351` (S3K branch `bugfix/ai-s3k-oracle-freq-resend`
  fully merged by that merge commit; the branch's last content commit was `ebc90caa3`,
  and no lane was running at that handover point). Before it `37c3f0c0b` (the two
  audited P1 fade regressions fixed; before it `5dd1b8122`: S3K 1-up fade-in body on the live path,
  driver-owned fade machine, four more gated oracle fields, intro oracle 760 →
  1490, then 1569 on the branch).
- Ordinary suite on `5dd1b8122`: 16,476 tests, 0 failures, 22 skips (three ROM
  paths). Guards: 609 (incl. the snapshot-copy guard), 0 failures. CI on develop was green at `725d4cfd5`.
- Trace sweep (`-Ptrace-replay`, three ROM paths) on `5dd1b8122`: 854 tests,
  7 failures, 6 skips; the seven are S1/S2/S3K complete-run chains,
  S2 EHZ halfpipe, `TestS3kAizTraceReplay`, `TestS3kReplayReferenceClosureIntegration`,
  `TestTraceRunReplayWalkerControlFlow`. CPZ2 segment 10 went green this session.

## The evidence base (what "green" means here)

Driver oracles compare, per driver service, a driver-RAM snapshot plus the
ordered YM/PSG/DAC write stream against a reference captured from the emulator
(TraceChaser observer core, ABI 5, `SNAPSHOT_AT_PC`). They are comparison-only;
recorded requests are the only stimulus. Every currently matching oracle is a
hard assertion on `Kind.MATCH` plus its literal tick count, so a regression
fails the build. Read the `MEASUREMENT_ONLY` lines by content; a green count is
not evidence (see `TestS2WidenedRequestOracle` history in the frontier log).

| Oracle | State at handover |
|---|---|
| S1 sound test music / SFX | MATCH 14,690 / 1,967 (JUnit since `373b4376c`) |
| S1 gameplay GHZ1, two recordings | MATCH 2,562 / 5,257 |
| S1 per-song windows (complete-run movie) | 20 windows, all 15 songs; 8 MATCH across 6 songs, 12 red with pinned frontiers |
| S2 driver v1 | MATCH 698 |
| S2 driver-state v2 EHZ w10150-12400 | MATCH 2,198 state and writes; DAC stream: one supersession join, excused |
| S2 request windows | MATCH per site (25+2, 52+0, 27+2 SFX+music), payload v4 |
| S2 CPZ w2700-3450 (second recording) | MATCH 720 state / 719 writes |
| S2 1-up window w20107-23600 | restore service pinned; whole window red (state tick 2880, writes tick 0) |
| S3K AIZ1 intro | service 1,569 of 5,263 (write difference: reference PSG FFh vs engine FM1 frequency, at an SFX taking PSG3); DAC stream red at run 338 byte 0 (88h vs 7Fh). Branch merged; resume from develop |

Per-effect and runtime-path tests (S3K): `TestS3kSfxLifecycleRom`,
`TestS3kSfxNoiseTailWriteStream`, `TestS3kSfxRuntimePathWithMusic`,
`TestS3kNoiseFormEffectWriteStream`, `TestSonic3kTitleScreenIntroSkipAudio`,
`TestS3kOneUpRestoreRom`, plus `TestSmpsSequencerConfigCopyCoverageGuard`
(every sequencer config field must survive the presentation copy; it found
five silently dropped settings on its first run).

## User-reported bugs and their status

| Report | Status | Where |
|---|---|---|
| S3K title theme dead after a late intro skip | fixed | `35d11ab69` |
| S3K spindash release wrong | fixed (noise-channel lock ownership, fabricated pitch multiplier removed) | `3947ba305` |
| S3K collapsing bridge cut early / no tail | fixed (same, plus per-pass PSG volume tail, config copier) | `3947ba305` |
| S3K quick shield silent | passes the in-play sequence test with the SFX-takeover write fix; not proven red-before | `3947ba305` |
| S3K abrupt music changes | drowning-restore substitution and S3K fade rate fixed | `fb091dfc3` |
| S2 music after 1-up ("funky remix") | fixed (tracks rest on hand-back) | `633ee400f` |
| S3K 1-up: exception in batch, no fade-in / wrong instruments, then PSG lost after the fade and fade state dropped by rewind | fixed in three steps | `725d4cfd5`, `5dd1b8122`, `37c3f0c0b` |
| S3K Knuckles held note | not reproduced by any test | open |

## Open items, in priority order

1. (closed at `37c3f0c0b`) Audit P1: PSG tracks stayed overridden and were
   attenuated after the S3K 1-up fade-in; now released at completion with
   volumes untouched, per `zDoMusicFadeIn`.
2. (closed at `37c3f0c0b`) Audit P1: the session snapshot copy dropped the
   driver fade counters; fixed, with a record-component survival guard.
3. S3K intro oracle from 1,569: resume from develop; the frontier log's top entry has the resume command and the six gated fields.
4. S1 red windows: five share one cause (an SFX from the previous window still
   holds a track at the epoch). Fix designed: replay from the predecessor
   window's epoch with its recorded requests and compare from the target epoch.
   Two are the act-clear double driver pass (ruled not admissible under rule 4;
   stays open). One is the 1-up restore. Four single frontiers.
5. S1 second movie (`sonic1-complete-withemeralds.bk2`): surveyed (101 windows,
   plan in the fixture manifest), uncaptured. ~45 min per capture pass, two
   passes per movie.
6. S1 `TestS1OverrideResumeAudioOracle` is inverted (asserts its reference is
   missing). Fix requires the 1-up windows `$88`/`$84` from the S1 whole-run
   probe; notes on branch `feature/ai-validate-next-audio-fixes`.
7. Driver-level 1-up backup of tempo, tempo speed-up, voice pointer and bank has
   no engine equivalent (presentation override stack preserves the sequencer).
8. S2 public request observer double-fires the ring at row 10960 (coalesced
   downstream; no parity failure today).
9. S3K second recordings (Tails/Knuckles) and widening past the AIZ1 intro; the
   insta-shield never fires in any committed S3K movie, so its oracle coverage
   needs a new recording.
10. Listening validation of the release build against the 08-27 checklist.

## Unmerged branches with handovers

- `bugfix/ai-s2-runchain-art-gaps` (two ROM-cited commits; closes the S2
  36–40-row art-gap family; the uniform 1 needs a level-load scheduling change:
  player creation has two owners in the engine, one in the ROM; five failed
  arms recorded).
- `bugfix/ai-boss-constructor-spawn-order` (research only; four ROM-correct
  spawn-order changes reverted because they destabilise rewind identity for
  after-current allocations; resumption plan in the trace frontier log).
- `feature/ai-validate-next-audio-fixes` (audit: both `next` audio fixes are
  already on develop; see `docs/architecture/audits/2026-09-04-next-audio-fixes-validation.md`).

## Rules that were learned the hard way this session

- Merge to develop only at a milestone (an oracle going green); lanes stay on
  their branch otherwise.
- Never `2>/dev/null` a commit in a chain; gate pushes on a clean tree.
- Never build or merge inside a lane's worktree; use `.worktrees/lead-verify`.
- `pgrep -f`/`pkill -f` match their own shell; use exit-marker files and PIDs.
- Two Maven runs in one worktree produce phantom reds (shared `target/`).
- Report the walk-failure axis name before any error count; a truncated chain
  reports fewer errors.
- A comparison that has never failed and one that never runs look identical:
  break each new gate on purpose once.
- The 08-27 revert (`b4c8fbd8a`) is why the S3K SFX "regressed"; re-derive from
  the ROM with oracle evidence, never re-apply those diffs verbatim.
- BizHawk exits 0 on Lua errors; treat a silent capture as failed until its
  output exists.

## Commands

```bash
# audio parity + audio packages, three ROM paths, S2 request movie
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Dsonic1.rom.path=$PWD/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=$PWD/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=$PWD/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  "-Ds2.request.bk2.path=$PWD/docs/BizHawk-2.11-linux-x64/Movies/sonic-2-sonic-tails-complete-emeralds.bk2" \
  "-Dtest=com/openggf/tools/audio/parity/**/*,com/openggf/audio/**/*" test
mvn -Dmse=off -B -Pguards test
LUA_BIN=lua5.4 mvn -Dmse=off -B -Ptrace-replay "-Dsonic1.rom.path=..." "-Dsonic2.rom.path=..." "-Ds3k.rom.path=..." test
```

Observer core matching the TraceChaser artifact lock (patch `2e1d1e59…`):
`<agent-scratch>/s2-widen/core-build/out/` (the s2-widen lane's build; its
`identity.json` matches the lock), installed beside a stock BizHawk at
`<agent-scratch>/s2-widen/bizhawk-s2req`. TraceChaser main is at `4fb6d08` (music-mailbox
observer site, payload v4).
