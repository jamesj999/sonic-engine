# S2 complete-emerald frontier — production visual follow-up

**Date:** 2026-08-14  
**Base:** `develop` at `3f0dde97b`  
**Related manifest:**
[`2026-08-14-s2-emerald-frontier-manifest.md`](2026-08-14-s2-emerald-frontier-manifest.md)  
**Status:** Task 5 verified on `bugfix/ai-s2-visual-bootstrap-ownership` at
pre-documentation head `03e868042`; integration and post-merge verification
remain pending.

## Purpose

This is a relay log for the agent investigating the five axes in the related
manifest. It records an additional production-path failure found while reviewing
that work, the experiments that discriminate it, and the bounded implementation
that will be attempted. Measurements are labelled separately from hypotheses.

## Reproduced chain baseline

**MEASURED.** On JDK 21.0.11, the committed chain still reports exactly the five
axes in the related manifest:

```text
walk-failure: seg7_ehz2 cursor 3977 / 3997
segment-physics: seg11, 236 errors, first at f3525 queue.s2_nemesis_plc.busy
dynamic-art-gap: seg4_ehz1 -> seg5_ehz2, four movie rows at -1
dynamic-art-gap: ss_4 -> seg6_ehz2, two submission rows at +1
dynamic-art-gap: ss_5 -> seg7_ehz2, 16 expected edges / 18 actual
```

Command:

```bash
mvn -Ptrace-replay -Dmse=off -Dsurefire.forkCount=1 \
  -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldRunChain \
  -Dsonic2.rom.path=s2.gen test
```

This confirms the manifest is current for `AbstractRunChainTest`.

## Newly exposed production-path axis

**MEASURED.** A temporary S2 analogue of `TestS1CompleteEmeraldVisualRun`, using
`VisualRunReplayHarness`, pauses at segment 0 frame 0 with 91 comparison errors.
The first fields are `player_history.y[63]` through adjacent ring entries:

```text
ROM    0x0293
engine 0x0294
```

The committed pre-trace snapshot is structurally consistent with the ROM:

- `StartLocations/EHZ_1` is `(0x0060, 0x028F)`;
- `Obj01_Init_Continued` prefills the 64-entry position ring at
  `(Sonic.x - 0x20, Sonic.y + 4)`, hence Y `0x0293`;
- the 26 title-card player passes overwrite 26 entries with Sonic's settled
  Y `0x0290` before frame 0.

The chain fixture does not reveal this because its synthetic bootstrap explicitly
prepares the position ring. The production visual path runs the real title card.

## Discriminating experiments

All changes below were temporary and reverted after measurement.

### Experiment 1: preserve only the prepared visual session

**MEASURED.** Skipping `applyStartPositionAndGroundSnap` when
`TraceReplayDriver.startPlayback(..., preparedLevel=true)` reduced the initial
failure from 91 to 76 errors. The remaining prefill entries became `0x0290`, not
the ROM's `0x0293`.

**DERIVED.** The prepared replay path was incorrectly rerunning standalone
metadata-start setup, but removing that duplicate setup alone exposes a second
owner overwriting the ring.

### Experiment 2: preserve the level-load prefill through Tails CPU init

**MEASURED.** Combining experiment 1 with production level-load registration of
the already-established ROM prefill removed all 91 frame-0 errors. The visual
replay then consumed all of EHZ1 and entered special stage 1 before pausing at
special-stage frame 136:

```text
field: dynamic_art.edges
ROM:   [] with outstanding transfer ids [1, 2, 3]
engine:[4, 5, 6] with no outstanding transfers
```

This later dynamic-art readiness mismatch is independent and outside the first
implementation scope.

### Negative controls

**MEASURED.** Neither of these changed any of the original five chain axes:

- replacing the special-stage-return Tails top-left setters with centre setters;
- deleting the stale duplicate Tails reset in `enterTitleCardFromResults`.

The stale code remains cleanup debt, but it is not a cause of this frontier.

## Approved implementation design

### Production ownership

1. A prepared visual replay adopts the state already produced by the real title
   card. `TraceReplayDriver` must not reapply the standalone metadata position,
   ground snap, sidekick reposition, or position-ring prefill on that path.
   Standalone replay keeps the existing setup unchanged.
2. `LevelManager.spawnSidekicks` already performs the ROM's accurate position-
   and stat-history prefill. This is shared shipped-ROM behaviour, not an S2
   exception: S2 `Obj01_Init_Continued` offsets Player 1 by `(-$20,+4)` and
   fills/clears the rings (`s2.asm:36201-36217`), while S3K
   `Sonic_Init_Continued` calls `Reset_Player_Position_Array` under the same
   offset (`sonic3k.asm:21931-21940,22166-22178`). No typed per-game rule is
   therefore appropriate.
3. The ownership signal will be granted only to a controller whose
   `getLeader()` is the exact main-player instance whose ring `LevelManager`
   populated. Multi-sidekick teams chain later controllers to the preceding
   sidekick, whose ring was not populated by this operation; those controllers
   must retain their existing initialization path. A production-level
   multi-sidekick test will pin that distinction.
4. For the directly-following controller, `LevelManager` will explicitly say
   that the already-established prefill is authoritative. This is a distinct
   production ownership state, not an alias for the existing
   `bootstrapPreludePlacementApplied` state. On the first INIT tick it changes
   only the history operation: the controller still performs the ordinary
   captured-level-start-anchor placement and transient CPU reset, still
   preserves the air state applied after spawn by S3K's MGZ1/HCZ1/LRZ1 intro
   owner, and skips only the destructive leader-ring rewrite. The existing
   bootstrap skip helper is not reused because it reanchors from the live
   leader and forces `air=false`. The new internal state remains
   rewind-captured.
5. The existing bootstrap helper remains valid for standalone trace setup. The
   production API will describe ownership of an already-populated prefill rather
   than call a method named `ForBootstrap` from ordinary level loading.

No trace row or auxiliary value will be copied into gameplay state. Every value
continues to come from the ROM start location and ordinary production execution.

### Regression test

Add `TestS2CompleteEmeraldVisualRun` beside the S1 visual test. Its first canary
will replay through the end of segment 0 via `VisualRunReplayHarness`, proving:

- the real title-card path reaches comparison without pausing at frame 0;
- the entire first EHZ1 body remains strict;
- the test stops before the newly exposed special-stage frame-136 mismatch.

The test must be observed red on the current implementation and green only after
both ownership corrections.

Add a focused production-level test that calls `LevelManager.spawnSidekicks`,
runs the directly-following controller's first INIT tick, and proves the
main-player ring retains the hand-derived `(-$20,+4)` values. Extend the
multi-sidekick integration coverage to prove only the controller whose leader is
that main player preserves this prefill; chained followers continue to initialize
their own leader history through the existing path. Add a characterization for
an S3K falling-intro sidekick whose zone-event owner sets `air=true` after spawn:
the first CPU INIT tick must retain that state while using the captured spawn
anchor and preserving the main leader's prefilled ring.

### Non-goals

- Do not alter the five axes in the original chain manifest.
- Do not address special-stage frame 136 in the same change.
- Do not extend the interior-return census walk.
- Do not relax dynamic-art or history comparison.
- Do not change the hardware-timing trace contract.

## Implemented evidence relay

This section distinguishes the committed implementation evidence from temporary
diagnostics. Task 5's clean-worktree focused, default-profile, and trace-profile
comparisons are now recorded below; integration and post-merge comparison remain
pending.

### Causes and bounded fix

The two independent owners established by the red/green cycle were:

1. Prepared production-visual sessions re-applied standalone metadata position
   setup and ground snap after the real title card had already produced the
   level-start state.
2. The first direct sidekick CPU INIT rewrote the main leader's position-history
   ring after `LevelManager` had performed the ROM-owned level-start prefill.

The committed implementation (`fc917a43e`) therefore adopts prepared title-card
state only on prepared sessions, and gives only the controller whose leader is
the exact prefilled main player a one-shot, rewind-captured ownership token. Its
first INIT retains the captured anchor and event-authored air state while
skipping only that destructive leader-ring rewrite. Chained followers retain
their ordinary initialization path.

### Task 1 authoritative and environmental baseline

The default-suite baseline was **15,105 tests, 64 failures, 21 errors, 18
skipped**. The first two broad `*TraceReplay` attempts were contaminated by the
shared full `/tmp` filesystem: both reported **190 tests, 8 failures, 59
errors, 0 skipped**, including six CNZ metadata-variant copy errors. Supplying
`-Djava.io.tmpdir` as a Maven user property did not help because JUnit had
already selected `/tmp`.

The authoritative trace-profile baseline instead set a task-owned temporary
directory at JVM startup through `JAVA_TOOL_OPTIONS`; a focused CNZ proof was
green and the broad run reported **190 tests, 8 failures, 53 errors, 0 skipped**.
That is the valid Task 5 comparison baseline. The temporary directories and
their generated LWJGL caches were removed; no shared `/tmp` content was
modified.

The explicit S1 prepared-visual baseline was green (2 tests) with exact shared
cursors **9,741** at the return-bridge pin and **46,806** at the second
giant-ring/MZ2 pin. The S2 chain baseline is the five axes reproduced exactly
above; none of its values is an intended change in this work.

### Task 2 red evidence (task-owned JVM temporary directory)

The authoritative red batch ran 20 tests and produced 4 failures and 1 error:

- `TestS2CompleteEmeraldVisualRun` stopped at EHZ1 segment 0, frame 0 (outer
  step 81, cursor 769) with **91** errors; the first was
  `player_history.y[63]`, ROM `0x0293`, engine `0x0294`.
- The direct S2 ownership test proved the ring rewrite: slot-0 X was expected
  `168` and actual `200`.
- The S3K falling-intro test first proved its captured anchor and `air=true`,
  then failed only its full history ring: slot-0 X was expected `160` and
  actual `292`.
- The rewind sentinel failed with `NoSuchFieldException` for the absent
  `levelStartLeaderHistoryPrefillPending` scalar.
- The chained-leader control was green (all 6 `TestMultiSidekickSpawn` tests),
  including its own-leader-history assertion.

The same batch also retained the unrelated baseline fractional-word red in
`TestS2PostLoadAssemblyHeadless` (`23040` expected, `0` actual); it is not
attributed to this implementation.

### Task 3 intermediate, committed green, and later frontier

After only prepared-session adoption, the visual canary remained red but fell
from 91 to **76** frame-0/cursor-769 history errors; the first remaining value
was ROM `0x0293`, engine `0x0290`. This isolated the second owner before the
token was added.

On clean committed state at `fc917a43e`, the focused batch ran **96 tests, 2
failures, 0 errors**. The two failures are unchanged baseline reds only:

- `TestInitialPlayableProcessSpritesPass` line 320, `37` expected and `36`
  actual.
- `TestS2PostLoadAssemblyHeadless#sidekickSpawnPositionWritesPreserveFractionalWords`,
  fractional X word `23040` expected and `0` actual.

The permanent EHZ1 visual canary, direct ownership test, chained-leader
isolation, S3K falling-intro anchor/air/history test, rewind token round-trip,
carry constructor, S2 replay bootstrap, hardware authority guard, and rewind
coverage guard are green in that focused evidence. The clean committed EHZ1
canary itself ran **1 test, 0 failures, 0 errors** and reached cursor **4479**.

The wider special-stage result is a deliberately **uncommitted, reverted
probe**, not part of the permanent canary or a clean-commit result. It completed
EHZ1 and first paused at special-stage frame **136**, `dynamic_art.edges`:
ROM `[]` with outstanding transfers `[1, 2, 3]`; engine `[4, 5, 6]` with no
outstanding transfers. It remains a separate frontier.

### Skills control and Task 5 verification

Two fresh-context controls using the unchanged S2 and S3K reference skill
packages independently derived the complete safe ownership design. The reusable
pitfall checklist therefore found no failing baseline to justify a speculative
skill edit; `Skills: n/a` is a fresh-control conclusion, not an omission.

**TASK 5 VERIFIED — pre-integration head `03e868042`:**

- The task-owned-JVM-temp focused cross-game command ran **76 tests, 1 failure,
  1 error, 0 skipped**. The new S2 visual canary, S1 visual canary, direct and
  chained sidekick coverage, S3K anchor/air coverage, AIZ keep-green, level
  loading, bootstrap resolution, and decoding checks are green. The only
  failure is the unchanged `TestS2PostLoadAssemblyHeadless` fractional-word
  baseline red (`23040` expected, `0` actual); the only error is the exact
  recaptured S3K AIZ timing baseline, expected
  `KOS_DECOMPRESSION_QUEUE#16`
  `sha256:dae421a276e9d9f1749981d2790390efbf66af448fcf9420e0008e99cc8a9a58`
  while the engine has no pending job. The S1 pins remain green against the
  Task 1 exact cursors **9741** and **46806**.
- `TestS2CompleteEmeraldRunChain` remains exactly its five Task 1 axes:
  `seg7_ehz2` cursor **3977 / 3997**; segment **11**, **236** errors, first
  frame **3525** `queue.s2_nemesis_plc.busy` (ROM `false`, engine `true`);
  the four **-1** `seg4_ehz1 -> seg5_ehz2` rows; the two **+1**
  `ss_4 -> seg6_ehz2` rows; and **16 expected / 18 actual** for
  `ss_5 -> seg7_ehz2`.
- The first default-suite run with task-owned `JAVA_TOOL_OPTIONS` was
  **non-authoritative**: it reported **15108 / 65 failures / 21 errors / 18
  skipped** because its audio-shell guard correctly rejected the inherited
  environment variable. The authoritative default-profile rerun explicitly
  unset that variable and used Surefire's worktree-local `target/test-tmp`:
  **15108 / 63 failures / 21 errors / 18 skipped**, versus Task 1's
  **15105 / 64 failures / 21 errors / 18 skipped**. The three extra tests are
  the intended new non-trace tests; all **48** shared red classes retain exact
  failure/error counts, and baseline
  `TestCompleteRunAudioCli` is additionally green. XML records neither
  `/tmp/junit` nor `No space left on device`.
- The authoritative task-owned-JVM-temp `*TraceReplay` sweep is exact at
  **190 tests / 8 failures / 53 errors / 0 skipped**, with the unchanged
  **61-class** red/error set and no `/tmp/junit` or disk-exhaustion text. Its
  first independent error remains MHZ expected
  `KOS_DECOMPRESSION_QUEUE#335`
  `sha256:3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91`
  versus engine pending `<none>`; Pachinko Sonic+Tails remains frame 0
  `tails_y_speed`, expected `0x01B2`, actual `0x0000`.
- Independent whole-implementation review verdict: **READY / NO BLOCKING
  ISSUES**; no Critical or Important findings. The first review's stale-Javadoc
  Minor was corrected in `e3783a6b8`; final whole-range review found only an
  adjacent stale ownership comment, corrected in the review follow-up, and
  optional direct S3K prepared-visual coverage.

Integration and post-merge comparison remain required; Task 5 does not claim
the development worktree is already merged.

## Expected verification

Focused verification will include the new visual canary, the S2 complete-emerald
chain, S2 frame-0/bootstrap tests, direct- and multi-sidekick level-start tests,
and the existing S1 production visual canary. Because the changed owners are
shared with S3K, verification will also include affected S3K sidekick tests, the
mandatory keep-green set (`TestS3kAiz1SkipHeadless`,
`TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, and
`TestSonic3kDecodingUtils`), plus a trace-profile `*TraceReplay` sweep with all
three ROMs. The default complete Maven suite and the trace-profile sweep will be
compared against updated `develop` baselines before integration and again after
merge. Any newly exposed visual frontier will also be recorded in
`docs/status/trace-frontier-log.md` with its exact command and first error.

## Progress log

- **2026-08-14 — REVIEW:** Original five axes reproduced exactly.
- **2026-08-14 — REVIEW:** Production visual path exposed 91 earlier frame-0
  history errors.
- **2026-08-14 — PROBE:** Two ownership changes cleared those 91 errors and
  moved the production visual frontier to special-stage frame 136.
- **2026-08-14 — DESIGN:** Scope bounded to the visual-session adoption and
  level-start prefill ownership fixes plus one permanent visual canary.
- **2026-08-14 — DESIGN REVIEW:** Independent review identified shared S3K
  coverage and chained-leader ownership as blockers. The design now cites the
  equivalent S3K reset, scopes authority to the actually populated main-player
  ring, and requires explicit S3K, multi-sidekick, and trace-profile coverage.
- **2026-08-14 — DESIGN RE-REVIEW:** Review found that the existing bootstrap
  skip helper also reanchors from the live leader and clears S3K intro air state.
  Production ownership is now a separate rewind-captured predicate that skips
  only the ring rewrite and retains the ordinary captured-anchor/air semantics.
- **2026-08-14 — TDD RED:** The task-owned-JVM-temp red batch exposed both
  production owners: the 91-error EHZ1 visual failure and direct main-leader
  ring overwrite; S3K anchor/air passed before its ring assertion failed, the
  rewind scalar was absent, and the chained-leader control was green.
- **2026-08-14 — TDD ISOLATION:** Prepared-session adoption alone reduced the
  visual failure from 91 to 76 errors, proving the remaining live-leader rewrite
  was a separate root cause.
- **2026-08-14 — IMPLEMENTED:** `fc917a43e` made the prepared-session adoption
  and one-shot rewind-captured direct-leader ownership changes. The clean focused
  result is 96 tests with only the two recorded baseline reds. The original five
  chain axes are unchanged.
- **2026-08-14 — FRONTIER:** A temporary reverted wider probe reaches
  special-stage frame 136 and pauses on `dynamic_art.edges`; it is deliberately
  outside the permanent EHZ1 canary.
- **2026-08-14 — SKILLS CONTROL:** Fresh-context S2 and S3K skill-package
  controls already supplied the safe design, so no speculative skill edit was
  warranted and `Skills: n/a` remains valid.
- **2026-08-14 — TASK 5 VERIFIED:** Focused cross-game evidence is 76 tests
  with only the known fractional-word failure and exact S3K AIZ KOS#16 error;
  the five chain axes and authoritative 190/8F/53E trace set are unchanged.
  The authoritative default-profile comparison is 15108/63F/21E/18S with no
  new or worsened red. Independent review is READY / NO BLOCKING ISSUES;
  integration and post-merge comparison remain pending.
- **2026-08-14 — FINAL REVIEW:** Whole-range review found no Critical or
  Important issue. Both stale ownership comments are corrected. A direct S3K
  prepared-visual characterization remains optional coverage polish; existing
  S1/S2 prepared, S3K standalone, S3K falling-intro, and broad trace controls
  remain the integration gate.
