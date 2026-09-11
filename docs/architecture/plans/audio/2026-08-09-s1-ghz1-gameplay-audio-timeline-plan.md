# Sonic 1 GHZ1 Gameplay Audio Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare the natural music and SFX request, priority-decision, and channel-ownership timeline produced by Sonic 1 REV01 and OpenGGF during GHZ1 of the committed complete-game BK2.

**Architecture:** A read-only BizHawk probe records raw ROM queue submissions and
later sound-driver admissions around complete updates. A strict tooling-only
Java contract reduces both producers to one semantic frame per BK2 row: ordered
raw requests, separately ordered resolved admissions with per-role arbitration,
and final ownership. A test-side visual-run observer records OpenGGF's raw
`AudioManager` entry requests plus the existing command timeline and
presentation snapshots after the normal outer-frame audio boundary. A
deterministic comparator reports the first semantic difference; reference data
never drives engine audio.

**Tech Stack:** Java 21, JUnit Jupiter, Jackson streaming JSON, Lua 5.4/BizHawk 2.11 Lua, Bash, existing OpenGGF trace replay and audio presentation infrastructure.

## Global Constraints

- Use Sonic 1 World REV01 only: CRC32 `AFE05EEE`, SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`.
- Use committed `sonic1-complete-withemeralds.bk2`, SHA-256 `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`.
- Select GHZ1 from the committed manifest: gameplay rows `[860,4975)`, one gap row at `4975`, special-stage transition at `4976`.
- Emit a frame-860 baseline with active music `$81`. Retain the preceding ROM queue/dispatch only as diagnostic provenance; pre-arm timing is not cross-producer equality.
- The sidecar is comparison-only. No reference sound ID, decision, ownership value, or timing value may be submitted to OpenGGF gameplay or audio.
- Disable and assert disabled trace fast-forward. Advance OpenGGF audio exactly once per consumed outer BK2 row through the production presentation boundary. Never advance an SMPS driver directly.
- Preserve source ordering and chip-port ordering. Observation seams are disabled by default and omitted from rewind/snapshot state.
- All generated detailed output stays below `target/audio-parity/`, uses create-new atomic publication, and is not committed.
- Do not merge or push. Human gameplay/listening review is required before integration.

---

### Task 1: Strict gameplay-audio timeline contract

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/timeline/S1GameplayAudioTimeline.java`
- Create: `src/main/java/com/openggf/tools/audio/timeline/S1GameplayAudioTimelineJsonl.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1GameplayAudioTimelineJsonl.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`

**Interfaces:**
- Produces: immutable records `Metadata`, `Baseline`, `Frame`, `Request`, `RoleArbitration`, `OwnerVector`, and `Terminal` under `S1GameplayAudioTimeline`.
- Produces: `S1GameplayAudioTimelineJsonl.read(Path)`, `writeNew(Path, Metadata, Iterator<TimelineRecord>)`, and streaming `Reader` access for the comparator.
- Semantic coordinates: exactly one `Frame` for each `bk2Frame` in `[860,4975)`. Diagnostic tick coordinates are nullable/non-negative and excluded from equality. Ownership roles are exactly `FM3`, `FM4`, `FM5`, `PSG1`, `PSG2`, `PSG3`; `OwnerRef` contains owner class, sound ID, and request ordinal so same-class SFX replacement is visible.

- [ ] **Step 1: Write failing schema tests**

  Cover canonical round-trip bytes; exact metadata identities/bounds; the
  frame-860 `$81` baseline; request class/ID ranges; requested-role uniqueness;
  fixed role/owner values; `NONE` identity sentinels; distinct request ordinals
  for same-ID retriggers; arbitration roles must belong to the request;
  exact 4,115-frame continuity; diagnostic tick monotonicity; exact terminal counts; duplicate/unknown fields;
  trailing JSON roots; writer self-validation; unchanged destination on every
  failure; and bounded streaming with at least 10,000 records.

- [ ] **Step 2: Run the RED test**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineJsonl test`

  Expected: compilation fails because the timeline types do not exist.

- [ ] **Step 3: Implement immutable types and strict streaming JSONL**

  Use a sealed record family:

  ```java
  public sealed interface TimelineRecord permits Baseline, Frame, Terminal {}
  public enum SoundClass { MUSIC, SFX, SPECIAL_SFX, COMMAND }
  public enum OwnerClass { NONE, MUSIC, NORMAL_SFX, SPECIAL_SFX }
  public record OwnerRef(OwnerClass ownerClass, int soundId, long requestOrdinal) {}
  public record RoleArbitration(HardwareRole role, boolean acquired,
                                OwnerRef displacedOwner, OwnerRef finalOwner) {}
  ```

  Pin the corrected schema string `s1_gameplay_audio_timeline.v2`, identities, segment
  bounds, and exact field inventory in metadata validation. Follow
  `AudioParityJsonl`'s record-at-a-time parser and atomic hard-link publication
  pattern; do not reuse its music-only metadata model.

- [ ] **Step 4: Add the authority/isolation guard**

  Extend the architecture guard so production packages outside
  `com.openggf.tools.audio.timeline` cannot import timeline types, and timeline
  sources cannot invoke `playMusic`, `playSfx`, logical restore/replay methods,
  producer advancement, or trace hardware-timing APIs.

- [ ] **Step 5: Run focused verification and commit**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineJsonl,com.openggf.audio.TestAudioPresentationArchitectureGuard test`

  Commit the four files with policy trailers.

### Task 2: Read-only BizHawk GHZ1 request and contention observer

**Files:**
- Create: `tools/bizhawk/audio/s1_gameplay_audio_timeline_contract.lua`
- Create: `tools/bizhawk/probes/s1_ghz1_gameplay_audio_timeline_probe.lua`
- Create: `src/test/resources/bizhawk/s1_gameplay_audio_timeline_contract_test.lua`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1GameplayAudioTimelineLuaContract.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1Ghz1GameplayAudioProbeContract.java`

**Interfaces:**
- Consumes: Task 1's canonical v2 record field names and enum spellings.
- Produces: a BizHawk JSONL stream through `ProbeRuntime`'s `OGGF_OUT` handle, with ROM queue slot/global priority/tick details confined to validated diagnostics.
- Maintains: an identity-bearing owner per active ROM SFX track: sound class,
  dispatched sound ID, and monotonically increasing request ordinal. Track
  replacement transfers identity only on an accepted dispatch.
- Observes: queue entry PCs `$00138E/$001394/$00139A`, consumption PCs `$071F02/$071F4C`, dispatch PCs `$071FD2/$0721C6/$07230C`, and complete update entry/return `$071B4C/$071C4C`.

- [ ] **Step 1: Write the failing pure-Lua behavioral harness**

  Exercise: queue overwrite before consumption; queue request consumed on a
  later tick; dispatch acceptance; no-dispatch rejection; equal-priority
  replacement; lower-priority rejection; normal SFX over special SFX;
  conversion to per-role acquired/displaced/final semantics; final owner vector;
  music restoration; same-stack-pointer DAC retry; and diagnostic tick monotonicity.

- [ ] **Step 2: Run the RED Lua/JUnit tests**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineLuaContract,com.openggf.tools.audio.timeline.TestS1Ghz1GameplayAudioProbeContract test`

  Expected: missing contract/probe failures.

- [ ] **Step 3: Implement the dependency-free timeline contract**

  Reuse the tested invocation-lifecycle and canonical-JSON behavior from
  `s1_audio_parity_contract.lua`. The new module owns request buffering,
  request/decision correlation, owner-vector diffing, and terminal counts. It
  must not contain BizHawk APIs so `/usr/bin/lua` can execute its tests.

- [ ] **Step 4: Implement the read-only gameplay probe**

  Verify the exact ROM, BK2, emulator, core, input length, manifest bounds, and
  opcode bytes before capture. Observe the last queue-1 `$81` before frame 860
  only to prove the frame-860 active-music baseline. Emit semantic frames for
  exactly `[860,4975)` and stop before the gap row. Read all
  18 ROM music/normal/special track headers at tick close, but emit only sparse
  effective-owner changes. Do not call any write, input, savestate, or register
  mutation API.

- [ ] **Step 5: Validate probe shape and commit**

  Run the focused command from Step 2 plus:

  ```bash
  /usr/bin/lua src/test/resources/bizhawk/s1_gameplay_audio_timeline_contract_test.lua \
    tools/bizhawk/audio/s1_gameplay_audio_timeline_contract.lua
  ```

  Commit the five files with policy trailers.

### Task 3: Natural OpenGGF GHZ1 timeline producer

**Files:**
- Create: `src/main/java/com/openggf/audio/driver/SfxContentionObserver.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/VisualRunReplayHarness.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/S1Ghz1OpenGgfAudioTimelineCapture.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1Ghz1OpenGgfAudioTimelineCapture.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestAudioTimelineAuthorityGuard.java`
- Create: `src/test/java/com/openggf/audio/driver/TestSfxContentionObserver.java`

**Interfaces:**
- Consumes: Task 1 writer and v2 records.
- Produces: `S1Ghz1OpenGgfAudioTimelineCapture.capture(Path runDirectory, Path output)`.
- Produces: local-only JUnit entry method `captureRequestedOutput` gated by both
  `s1.audio.timeline.run.path` and `s1.audio.timeline.output`; it skips only
  when both properties are absent and rejects a partial pair.
- Adds: a `VisualRunReplayHarness.FrameObserver` overload whose immutable view exposes the consumed BK2 cursor, segment index/row, loop step, lag/gameplay status, and no mutation handles.
- Adds: disabled-by-default `SfxContentionObserver` callbacks for sequencer
  admission and each existing per-role arbitration result. Callbacks carry
  immutable source descriptors and per-admission ordinals, return `void`, and
  cannot influence the decision.

- [ ] **Step 1: Write failing cadence, natural-command, and authority tests**

  Prove two overlapping same-frame SFX requests produce two ordered contention
  event groups before the final snapshot, including normal-SFX A displaced by
  normal-SFX B on one role. Prove observer disabled/default and snapshot
  identity. Prove the frame observer sees title-card/segment frames;
  fast-forward is disabled; bootstrap/title-card presentations are counted
  separately; baseline is sampled before row 860; each consumed BK2 row gets
  exactly one `presentOuterFrame(false, false)` plus `GameServices.audio().update()`;
  all 4,115 rows produce 4,115 contiguous semantic frames and 4,115
  presentations; lag rows still advance once; `AudioCommandTimeline` entries retain frame and
  order; presentation snapshots map sequencer indices to FM/PSG lock owners;
  capture emits a frame-860 `$81` baseline, succeeds with no reference JSONL present; and no timeline class can
  call audio mutation or hardware-timing authority APIs.

- [ ] **Step 2: Run the RED tests**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineCapture,com.openggf.tools.audio.timeline.TestAudioTimelineAuthorityGuard test`

  Expected: missing capture and frame-observer APIs.

- [ ] **Step 3: Add the opt-in visual-run frame observer**

  Preserve every existing `replay` overload by delegating with a no-op
  observer. The audio overload rejects enabled fast-forward before driving,
  performs diagnostic presentations during bootstrap/title card, exposes a
  `beforeFirstSegmentRow` baseline callback, then performs one semantic
  presentation after each consumed segment row. Pass immutable coordinates to the
  observer after presentation; never expose `GameLoop`, `AudioManager`, or a
  producer through the callback interface.

- [ ] **Step 4: Implement the contention observer seam**

  Install `SfxContentionObserver.NONE` by default. Assign a monotonic admission
  ordinal when `addSequencer(..., true)` admits an SFX and emit its immutable
  source identity. At every FM/PSG `shouldStealLock` call, evaluate the existing
  result once, use that same boolean for behavior and observation, and emit the
  role, challenger, current owner, and result in source order. Do not add an
  alternate decision branch, callback exception recovery, or snapshot field.

- [ ] **Step 5: Implement OpenGGF capture**

  Launch the committed run with `stopAfterSegmentBody(0)`. Drain only newly
  appended `AudioTimelineEntry` values, map `PlayMusic` and `PlaySfx` to
  resolved admissions, derive each SFX's declared roles from its loaded tracks, and
  combine ordered contention-observer events with
  `captureLogicalSnapshot().presentation()` into identity-bearing per-role
  acquired/displaced/final arbitration plus the final owner vector. Retain
  partial ownership explicitly; do not emit a ROM queue slot, global priority
  transition, or whole-request decision for the engine. The capture API accepts
  only the committed run directory and output path; it has no reference
  timeline argument, loader, byte array, or callback.

  Add `captureRequestedOutput` to call the same capture API used by unit tests;
  the shell will invoke that one JUnit method with the two exact properties.

- [ ] **Step 6: Run regressions and commit**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineCapture,com.openggf.tools.audio.timeline.TestAudioTimelineAuthorityGuard,com.openggf.audio.driver.TestSfxContentionObserver,com.openggf.tests.trace.runs.TestS1CompleteEmeraldVisualRun,com.openggf.audio.TestAudioPresentationArchitectureGuard test`

  Commit the seven files with policy trailers and an explicit changelog
  justification if the disabled diagnostic seam does not update the changelog.

### Task 4: Semantic comparator and safe orchestration

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/timeline/S1GameplayAudioTimelineComparator.java`
- Create: `src/main/java/com/openggf/tools/audio/timeline/S1GameplayAudioTimelineReport.java`
- Create: `src/main/java/com/openggf/tools/audio/timeline/S1GameplayAudioTimelineTool.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1GameplayAudioTimelineComparator.java`
- Create: `src/test/java/com/openggf/tools/audio/timeline/TestS1GameplayAudioTimelineCli.java`
- Create: `tools/audio/run_s1_ghz1_gameplay_audio_timeline.sh`
- Modify: `tools/bizhawk/README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: complete, strictly validated Task 1 streams and Task 2/3 producers.
- Produces: deterministic human and compact JSON first-mismatch reports.
- Produces: `publish-reference --staging <fresh-file> --output <new-file>` that
  strictly parses the complete staging stream, atomically create-new publishes
  it, and deletes staging on both success and failure.
- CLI exit codes: `0` semantic match, `2` usage, `3` parity mismatch, `4` capture/tool failure.

- [ ] **Step 1: Write failing comparator and CLI tests**

  Cover metadata mismatch; missing/extra/reordered request; request frame/order,
  class, ID, and requested-role differences; acquired/displaced/final per-role differences; partial ownership;
  restoration mismatch; side-aware capture failures; bounded 8-before/8-after
  context; source replacement between validation and comparison; output-root
  traversal/symlink/control-character rejection; create-new report pairs; and
  no replaceable Java/Mono command seam. Add reference staging tests proving an
  existing destination is unchanged, malformed/partial staging is deleted, and
  successful publication is validated and atomic create-new.

- [ ] **Step 2: Run the RED tests**

  Run:
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineComparator,com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineCli test`

  Expected: missing comparator/tool failures.

- [ ] **Step 3: Implement validation-first bounded comparison**

  Hash each input during a complete validation pass, compare a second streaming
  pass without realignment, consume both streams to EOF after retaining the
  first mismatch context, and reject a changed digest. Compare baseline,
  ordered raw requests, resolved admissions, per-role arbitration, and final
  owner vectors. Validate but exclude ROM-only queue
  slot/global-priority/audio-tick diagnostics.

### Final semantic-boundary correction (completed 2026-08-09)

- [x] Bump the strict contract to v2 and split raw caller/ROM queue requests
  from resolved driver/presentation admissions. Emit each at its own BK2 frame
  with one shared monotonic ordinal; compare without frame realignment.
- [x] Add the disabled `AudioManager` request observer before ring alternation,
  keep it out of rewind snapshots, and correlate normal presentation admission
  through the existing command and contention observers.
- [x] Observe S1 ring resolution at ROM PC `$721F4`, retaining queue `$B5` and
  admitted `$CE`; cover frame-958 request parity and the reference frame-959
  versus OpenGGF frame-958 jump admission.
- [x] Preserve the displaced old SFX identity for same-ID production
  retriggers through `SmpsDriver`'s pending conflict mechanism.
- [x] Pin the installed core assembly and GPGX binary by full SHA-256; add
  negative validation coverage.
- [x] Limit comparator context to at most eight prior records, the mismatch,
  and exactly eight subsequent records without backfilling a short prefix.
- [x] Route nonzero BizHawk producer exits only to trusted staging discard;
  prove a complete failed staging stream cannot publish.
- [x] Run two real BizHawk and two real OpenGGF captures. Duplicate pairs are
  byte-identical and the first valid mismatch is `ADMISSION_EXTRA` at frame
  958, after both streams agree on raw jump request ordinal 1 at frame 958.

- [ ] **Step 4: Implement safe CLI and shell runner**

  The shell validates identities, creates one unique run directory beneath
  `target/audio-parity/s1-ghz1-gameplay/`, records BizHawk twice, records
  OpenGGF twice through
  `mvn -Pci -Dtest=com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineCapture#captureRequestedOutput -Ds1.audio.timeline.run.path=<run> -Ds1.audio.timeline.output=<new-output> test`,
  with each probe writing only to a fresh `.staging` child passed to
  `publish-reference` after exit 0 or `discard-reference` after nonzero exit; gates each published pair
  with `cmp`, and then invokes the trusted Java comparator. Preserve all four
  captures, logs, and two reports. Reject command-replacement environment
  variables and never overwrite an existing detailed or report file.

- [ ] **Step 5: Run focused verification and commit**

  Run the Task 1–4 explicit test classes, `bash -n` on the runner, runner
  `--help`, and `git diff --check`. Commit source, tests, README, and changelog
  with policy trailers.

### Task 5: Real GHZ1 evidence and contention diagnosis

**Files:**
- Create: `docs/architecture/research/audio/2026-08-09-s1-ghz1-gameplay-audio-timeline-result.md`
- Modify only if evidence proves a ROM mismatch: the smallest owning audio driver/presentation files and focused regression tests.

**Interfaces:**
- Consumes: Task 4 runner and the discovered S1 ROM at repository root.
- Produces: ignored detailed run artifacts plus a compact, non-reconstructive research result.

- [ ] **Step 1: Run two-producer deterministic capture**

  Run the new shell runner with the discovered REV01 ROM and the installed
  BizHawk 2.11. Record exact hashes, byte counts, request inventory, contention
  examples, and the first mismatch. Fail the run if GHZ1 lacks either a
  music/SFX takeover-and-restore sequence or an SFX request while another SFX
  is active. Detailed JSONL remains ignored.

- [ ] **Step 2: Classify the first causal mismatch**

  Use request equality to separate caller/queue submission from audio-driver
  behavior. If requests differ, stop at the submission boundary. If admission
  roles or arbitration results differ, verify `PlaySoundID`, `SoundPriorities`, and
  SFX headers in `s1.sounddriver.asm`. If final ownership differs, verify
  `Sound_PlaySFX`, `Sound_PlaySpecial`, `StopSFX`, `StopSpecialSFX`, and
  `cfStopTrack`. Do not tune against observed frames or IDs.

- [ ] **Step 3: Add a RED regression before any behavior fix**

  Reduce the proven ROM rule to the smallest synthetic driver/presentation
  test. Demonstrate the old code fails for the same priority/channel
  transition observed in the real run, then implement the smallest ROM-derived
  correction. Preserve chip-port write order unless the reference timeline
  proves it wrong.

- [ ] **Step 4: Re-run deterministic capture and focused tests**

  Repeat the full four-capture runner after each correction. Run the existing
  SMPS driver snapshot, fade, chip observer, YM2612 GPGX parity, timeline, and
  authority test classes explicitly.

- [ ] **Step 5: Record evidence and commit**

  The research note must state observed sound IDs/times, both contention
  classes present or absent, first mismatch before/after, exact commands and
  results, residual differences, and the human listening checklist. Do not
  claim audible correctness from structural parity. Commit intended source,
  tests, changelog, and the research note; leave detailed captures unstaged.

### Task 6: End-to-end review and verification

**Files:**
- Modify only files required to address review findings.

- [ ] **Step 1: Independent whole-feature review**

  Review requirements traceability, FixBugs=0 behavior, queue/dispatch PC and
  opcode proof, frame/tick cadence, priority semantics, snapshot exclusion,
  authority isolation, parser strictness, bounded memory, atomic publication,
  and chip-port ordering preservation.

- [ ] **Step 2: Resolve all Critical and Important findings with focused RED/GREEN tests**

  Use one fix owner and one scoped re-review per round. Record any accepted
  Minor finding in the final report.

- [ ] **Step 3: Run final verification**

  Run all explicit timeline tests plus the existing audio parity, SMPS driver,
  audio architecture, YM2612 GPGX parity, and GHZ1 visual-run replay tests on
  JDK 21. Run Lua syntax/behavior checks, `bash -n`, `git diff --check`, and the
  real deterministic four-capture command.

  Then run `mvn test -Pci` on JDK 21 with all three discovered ROM properties.
  Record every failure/error and compare it with the latest known branch
  baseline; no newly failing test attributable to this branch is acceptable.

- [ ] **Step 4: Stop for human review**

  Report commits, exact test results, capture hashes, mismatch status, and a
  listening/gameplay checklist. Do not merge, push, remove the worktree, or
  delete the local branch.
