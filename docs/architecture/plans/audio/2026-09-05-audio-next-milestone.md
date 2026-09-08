# Audio next milestone implementation plan

> Agentic workers use subagent-driven-development with independent worktrees and review. Repository instructions override skill defaults for integration and artifact placement.

**Goal:** Advance retail S3K audio correctness, prepare AIZ-to-HCZ listening evidence, and measure viable FM performance improvements.

**Architecture:** Preserve Java Nuked as the production backend. Keep S3K variation in its existing sequencer profile; distinguish logical admission from physical chip execution before diagnosing DAC bytes. Native and alternative-core work stays opt-in research.

**Tech stack:** Java 21, Maven/JUnit 5, pinned Nuked C, optional native compiler/JNI/GraalVM, existing BizHawk 2.11 trace tooling.

**Spec:** The requirements and feature designs below implement the user's approved follow-on order in the September 4 audio correctness and evidence plan.

## Global constraints

- Base: main `develop` at `bbf28b7dc`; refresh before integration without switching main branches or overwriting user changes.
- Preserve user-modified disassembly submodules and every pre-existing worktree.
- All Maven output stays in the current worktree's `target/`; JDK 21 and `-Dmse=off` are mandatory. Use absolute verified ROM paths for all three games.
- Model shipped ROM behavior, including `fix_sndbugs=0`; never fit a frame, route, or timing constant to a fixture.
- Trace data is comparison evidence, not gameplay state. No new trace authority or broad audio architecture migration.
- No performance publication during competing builds. A fast desktop cannot establish a low-end budget, and generated clips cannot establish human listening approval.
- Research sources must retain verified provenance and licensing. Do not commit ROMs, expanded traces, WAVs, upstream sources, JDKs, or compiled binaries.
- Fetch/fast-forward, ordinary and guard baseline, development comparison, reviewed integration, post-merge comparison, push only develop, then remove only this task's clean fully merged worktrees and branches.

## Requirements

1. Remove the extra S3K PSG takeover write without losing guaranteed admission silence or changing other game profiles; extend the hard oracle prefix and report the next measured frontier.
2. Determine the cause of DAC run 338's first-byte discrepancy using annotated logical and physical writes. Fix only a demonstrated owner and add a reproducing regression.
3. Exercise AIZ-to-HCZ music, SFX, 1-up restoration and rewind through real audio paths; prepare reproducible listening material and explicitly retain unperformed human gates.
4. Profile Java Nuked; trial narrowly measured, bit-exact optimizations. Extend native feasibility evidence to actual PCM transfer and snapshots when supported by available tools, keeping native/fast backends experimental.
5. Review and integrate all justified deliverables, with exact verification and honest residual risks in the handover.

## Exploration synthesis

The retail noise command writes DF immediately followed by its operand (or FF for zero). Engine first-write acquisition of the separate noise slot injects an additional FF under inherited FORCE_SILENCE. Existing REGISTER_SEQUENCE policy is the smallest candidate, preserving ownership and previous admission writes.

Reference DAC run 337 ends in 7F; run 338 starts with 88 and that note has matched repeatedly earlier. Logical external writes notify at enqueue while DAC bytes notify during clocking. This mixed boundary must be tested before decoder or cadence changes. The source's 303-cycle annotation includes two approximate ROM stalls, unlike instruction-only 297; neither is an established fix for this discrepancy.

Existing physical observers capture raw strobes and provenance, but complete replay and native PCM/lifecycle validation remain separate requirements. Lead owns listening/corpus investigation while independent agents handle bounded code lanes.

## Architecture decision

Use existing semantic policies and observation surfaces. No new production backend selector, old-core restoration, eager PSG ownership alias, or fabricated common chip clock. Native/fast evaluation is research whose outcome may be a documented rejection. Broader architecture changes require explicit evidence and scope review.

## Feature design and implementation plan

### Task 1: PSG register-sequence takeover

Files: `Sonic3kSmpsSequencerConfig.java`, `TestS3kNoiseFormEffectWriteStream.java`, `TestS3kOracleRequestSidecarWiring.java`, relevant admission tests, a validation record and audio frontier log.

- [x] Tighten the existing ROM-backed noise-pair test to `formAt == silenceAt + 1`; observe red for Splash, InstaAttack and Collapse.
- [x] Add an admitted PSG3 zero-operand case proving DF,FF is adjacent and admission retains its existing FF.
- [x] Apply `.psgSfxTakeoverMode(SmpsSequencerConfig.PsgSfxTakeoverMode.REGISTER_SEQUENCE)` in the S3K provider with retail source citation.
- [x] Verify focused admission, release, lifetime, rollback, cross-game policy and config-copy tests.
- [x] Attempt 1,571 matching services, then preserve the measured result: 1,570 whole services plus the first 43 ordered writes of service 1570. The isolated fix exposes a second mismatch at event 43, so claiming another full service would be false.
- [x] Record red/green commands, new frontier and remaining stale-IX limitation; commit for review.

### Task 2: DAC boundary diagnosis and regression

Files: existing `S3kOpenGgfAudioCapture`, `S3kAudioParityComparator`, `Ym2612Chip` tests and diagnostic utilities only as the measured owner requires; separate validation record. Coordinate shared frontier prose through lead.

- [x] Generate current annotated engine runs 337-339, including every 2B boundary, service/write indices, lengths, first/last bytes and actual sample submissions.
- [x] Compare existing physical observer address/data strobes against logical callbacks using a bounded diagnostic, without trace hydration or production timing mutation.
- [x] Create a synthetic distinct-sentinel two-sample test that discriminates enqueue ordering from physical handoff behavior.
- [x] If capture or comparator is wrong, correct that boundary with a failing test; if physical playback is wrong, derive and test the smallest physical fix. Do not change cadence based solely on run bytes.
- [x] Re-run focused DAC timing, snapshot, physical observer and oracle tests; document evidence even if no playback change is justified.
- [x] Commit the tested bounded result for review, retaining any unresolved timing question explicitly.

### Task 3: FM performance experiments

Files: `tools/audio/fm-core-benchmark/`, optional focused synth tests, and a new report under `docs/architecture/research/audio/`.

- [x] Inspect retained harness and available JFR/perf/native tools; select repeatable sustained workloads with snapshots and complete write input.
- [x] Record a quiet-host Java profile, identify actual hot methods, and trial a narrow optimization only under pinned sample/state equality and negative controls.
- [x] Compare baseline and candidate in repeated equal-order runs; retain a production optimization only if both exactness and meaningful speedup are demonstrated.
- [x] Extend the existing JNI experiment to actual stereo PCM transfer and snapshot/lifecycle round trips, with Java/C sample equality and invalid-input tests; record Native Image feasibility separately from packaging/platform support.
- [x] Compare native/fast candidate throughput honestly; report licensing, fidelity, packaging and lower-end evidence gaps without adding a runtime selector.
- [x] Commit reproducible tooling and research conclusions for review; no bundled binaries or unverified results.

### Task 4: AIZ-to-HCZ evidence and delivery

Files: existing render/capture tooling if a demonstrated gap requires changes, listening checklist, handover, validation/research docs, release notes and README release summary.

- [x] Inventory existing movie/reference PCM capability and current engine presentation tests; identify equivalent bounded audio events before making clips.
- [ ] Produce available engine/reference artifacts externally with commit, ROM, settings, event and tool provenance; do not label unrelated renders as synchronized A/B evidence.
- [x] Verify first/repeated 1-up terminal fade and snapshot/rewind continuation through presentation tests; retain human listening approval as pending unless actually supplied.
- [x] Independently review each lane and resolve findings, then combine in the coordination worktree.
- [x] Run full ordinary and fresh guards with focused audio tests; compare per-test outcomes with the pinned baseline.
- [x] Update handover and release claims, independently review whole branch, refresh and merge into main develop, run post-merge comparisons, push develop and clean only this round's worktrees/branches.

## Integration report

Delivered as develop merge `e258282e0`, pushed with verification record `b8f474379`; all five task worktrees/local branches were cleaned up. Isolated ordinary baseline at `bbf28b7dc` passed 16,482 reported executions with zero failures/errors and 22 skips. Develop's independent guidance update `ce3b9e291` merged cleanly into coordination (both README entries retained). Updated baseline passed 16,482 ordinary and 609 guard executions; development and merged develop passed 16,497 ordinary and 609 guards, with no failures/errors or new skips. The ordinary 22 skips remain explicitly recorded. Six overwritten ICZ XML case records were resolved by a separate explicit six-method passing run and independent evidence audit, not by changing the full-run totals. Equivalent retail/gameplay A/B, human listening and platform gates remain open as documented below.

First baseline invocation aborted in `TestEditorToggleIntegration`: native `glGenTextures` was called without a current context, after a prior GLFW initialization failure. Reported 16,453 executions are an incomplete run, not the baseline suite total. The class passed alone; a later same-tree retry collided with an independent main Maven build and is also excluded. The completed isolated baseline above replaces these invalid attempts. Preserve their provenance rather than comparing truncated counts.

## End-to-end review

Review retail ownership versus fitted symptom fixes, logical/physical ordering, observer non-interference, unchanged rollback and snapshot semantics, meaningful negative controls, benchmark comparability and provenance, and the distinction between generated material and actual listening/platform approval.

## Coordination ledger

Ruling: independent implementations use disjoint worktrees in parallel, per the approved orchestration and repository test-isolation contract; shared documents are reconciled by the lead. Cost if wrong: explicit integration rework.

Ruling: keep native and fast backends experimental until measured physical-input, PCM, lifecycle and platform evidence supports production use. Cost: no immediate runtime backend choice.

Ruling: preserve exact within-service PSG progress rather than expanding this fix to a second volume-tail defect. Cost: whole-service frontier remains 1570 while the hard ordered prefix advances four events.

Ruling: distinguish the session's final PCM silence gate with `OUTPUT_GATE_CHANGE`; it changes neither raw chip state nor clocks. Raw-YM replay may cross it, but full presentation-PCM reconstruction may not. Cost if this distinction is wrong: false candidate equivalence, guarded by unchanged synth-state and explicit scope tests.

| Tasks | Shared surface | Preflight result |
|---|---|---|
| 1 / 2 | S3K oracle evidence | Separate code ownership; lead reconciles frontier prose and re-runs combined oracle |
| 2 / 3 | YM physical observation | DAC lane owns synth correctness; performance lane proposes production edits before touching shared synth files |
| 3 / 4 | Rendering and research provenance | External artifacts retain exact workload and settings; no listening claims inferred from throughput |
| 1 | Policy and test expectations | Existing setting can remove only synthetic takeover write; explicit admission regression protects prior fix |
| 2 | Investigation and implementation | Measurement precedes choosing owner; absence of proof is recorded, not replaced by a guessed fix |
| 3 | Profiling and timing | Heavy jobs are sequenced before publishable measurements |
| 4 | Evidence and release gate | Automated results and pending human/hardware work remain separate |

## Measured scope adjustments

Task 2 completed as diagnostic provenance and regressions, not a playback fix:
reference and engine run 338 represent different plays after missing external
tempo control. Task 3 retained no Java optimization because the measured trial
showed no reliable gain. Native actual-PCM/GraalVM and deterministic ymfm
proofs are research; direct-proof failure controls passed independent re-review.
Task 4 produced four verified 60-second standalone engine music sets externally;
equivalent full-slice retail/gameplay A/B needs a reviewed TraceChaser sound-on
WAV adapter and tempo-input contract, and human listening remains pending.
These gaps are not marked complete by generated files or green automated tests.
