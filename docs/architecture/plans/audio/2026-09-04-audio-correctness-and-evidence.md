# Audio correctness and evidence implementation plan

> Agentic workers use subagent-driven development with isolated worktrees and independent review. Repository workflow overrides skill defaults for artifact placement, parallel disjoint work, and integration.

**Goal:** Advance the active S3K audio discrepancy, establish trustworthy physical chip-write evidence, and preserve the performance investigation without selecting a production backend.

**Architecture:** Retain the existing logical SMPS driver and physical chip ownership. ROM-backed admission behavior belongs to the existing sequencer profile; observation must not alter timing, writes, snapshots, or existing oracle callbacks. Research tooling is opt-in and separate from runtime.

**Tech stack:** Java 21, Maven/JUnit 5, existing ROM-backed driver oracles, optional native C/C++ benchmark executables.

**Spec:** Requirements and per-task designs below implement the user's approved audio roadmap and the September 4 handover.

## Global constraints

- Base and delivery branch: `develop`, initially `4296bc291`; fetch and fast-forward before integration. Never switch the main workspace branch.
- Preserve all existing worktrees and user-modified disassembly submodules. Create and clean only this round's worktrees.
- User-supplied ROMs only. Discover and hash ROMs; pass absolute paths through `sonic1.rom.path`, `sonic2.rom.path`, `s3k.rom.path`.
- Maven runs on JDK 21, with `-Dmse=off`, and writes only to the current worktree's `target/`. Never overlap Maven invocations in one worktree.
- Model shipped-ROM branches, including `fix_sndbugs=0`. No trace-row, route, game-name, or fixture carve-outs in shared behavior.
- Preserve hard oracle comparisons and prove new gates fail when deliberately perturbed.
- No FM backend replacement, runtime native dependency, new generic trace framework, or external TraceChaser publication in this milestone.
- Do not commit upstream emulator sources, JDKs, binaries, ROMs, expanded trace streams, or generated PCM/WAV. Optional research inputs must have pinned provenance.
- Do not publish performance numbers while competing builds run; historical measurements remain explicitly historical.

## Requirements

1. S3K Collapse SFX admission emits the guaranteed retail PSG noise silence at the correct header position. Existing S1/S2 behavior remains unchanged.
2. The matching prefix through service 1569 is tested; the next measured frontier and separate DAC mismatch remain explicit, not declared solved.
3. Correct stale claims that current engine observation omits all real streamed DAC bytes. Distinguish queued logical writes from actual physical strobes before extending capture.
4. Establish a bounded complete physical-write capture contract, including real and synthetic DAC provenance, suitable for deterministic chip replay without changing synthesis.
5. Preserve reproducible synthetic benchmark tooling and a point-in-time research report; correct overstated release/oracle coverage.
6. Record fresh baseline, development and merged ordinary/guard results and compare exact failures. Delivery introduces no new regressions.

Non-goals for this milestone: closing every full-game oracle, resolving the independent S3K DAC cadence discrepancy by inference, choosing a native/fast backend, asserting listening parity, or claiming a low-end performance budget without hardware evidence.

## Exploration synthesis

- High-reasoning ROM investigation and lead inspection agree: `zGetSFXChannelPointers.is_psg` writes `$FF` unconditionally in the retail branch after stale-IX silence. Collapse header order is FM3, FM4, FM5, PSG3. `SmpsDriver.emitSfxTrackInitWrites` skips PSG, explaining the observed missing write.
- Stale IX can contribute other writes for other header arrangements. This change implements the guaranteed write only and must not claim complete stale-IX emulation.
- Capture investigation and lead inspection agree: `Ym2612Chip.serviceDac` already calls the legacy observer for real streamed bytes. Normal writes notify at queue admission; streamed bytes notify at address presentation; interpolated bytes are intentionally absent. Existing logs therefore cannot be relabelled physical bus traces.
- Evidence audit found incorrect merge attribution in the handover/frontier header, an S1 oracle labelled S2, and public wording conflating hard-asserted matching windows with published red windows.
- No sidecar tool is available. Paired investigation is performed by the assigned domain agent plus independent lead source review, with cross-agent/final review before integration.

## Architecture decision

- Keep game variation in `SmpsSequencerConfig` and its S3K provider, preserving the value through `SmpsAssetCatalog` copies.
- Keep existing `ChipWriteObserver` logical semantics compatible. Any added physical-event contract must name its clock, dispatch boundary, resets/discontinuities and event origin; never infer timing from output frame counts.
- Observers are diagnostic consumers, not state or scheduling authorities. Exported streams carry provenance and cannot hydrate gameplay.
- Research tooling remains opt-in. Java Nuked remains the production backend; rollback is ordinary Git reversal of independently scoped commits.

## Feature design

### Task 1: S3K PSG admission silence

Ownership: `SmpsDriver`, `SmpsSequencerConfig`, `Sonic3kSmpsSequencerConfig`, configuration copying in `SmpsAssetCatalog`, focused SFX/oracle tests, and a new audio-frontier entry. Avoid chip synth/capture files.

Add a semantic configuration flag, default false, enabled only by the S3K provider. At every PSG header entry during admission, emit the retail guaranteed raw `$FF` in declared track order. Do not use the existing PSG3 `$DF/$FF` helper or move silence before preceding FM initialization writes. Preserve the configuration through presentation copies.

Acceptance examples:

```text
Collapse admission: FM3 keyoff + four SSG clears,
                    FM4 keyoff + four SSG clears,
                    FM5 keyoff + four SSG clears,
                    PSG FF
then normal music/service writes.
```

Tests must also exercise a PSG1/PSG2 header under the enabled profile, disabled/default profile behavior, and config-copy coverage. Frame 1569 is comparison evidence only, never an implementation predicate.

### Task 2: Physical capture contract

Ownership: synth observation/plumbing and `FmSfxRenderTool`, with focused tests and contributor documentation; no sequencer admission edits.

First distinguish legacy observer behavior from physical writes with regression tests for ordinary `playDac`, real-byte uniqueness, and interpolation exclusion. Extend the existing observer registration with default primitive physical callbacks; preserve all legacy callbacks exactly. No duplicate logical notifications.

Approved interface semantics (worker may use equivalent project-consistent type names):

```java
default boolean observesPhysicalWrites() { return false; }
default void onYm2612BusWrite(long cycle, int busPort, int value,
                              PhysicalWriteOrigin origin) { }
default void onPsgBusWrite(long tick, int value) { }
default void onPhysicalTimelineBoundary(ChipClockDomain domain, long clock,
                                        BoundaryKind kind) { }
```

`busPort` is the raw Nuked 0..3 port, preserving separate address and data strobes. Origins distinguish external bus writes, real DAC stream bytes, and synthetic DAC interpolation. Record every core write after the mutation but before the next core clock. Real and synthetic writes are both needed for exact engine replay; synthetic writes must never masquerade as hardware-reference evidence.

Use distinct clock domains: YM internal cycles at `Ym2612Chip.getInternalRate() * 24`; PSG generator ticks at `PsgChip.TICK_RATE_HZ`. Do not claim a shared master clock: the FM input-clock constant is rounded and is not the 53MHz board master. Export callback ordinals separately; do not globally sort independent chip clocks. Counters are diagnostic-only and monotonic; resets, snapshot restore and non-bus model mutations explicitly delimit replay segments. Do not add counters to production snapshots or fabricate bus writes for policy operations.

All existing observer wrappers must forward physical capability and events. Disabled capture must not allocate per physical strobe. Existing session transaction buffering must preserve commit/rollback semantics; direct capture sinks must be nonthrowing and nonreentrant. Aborted physical writes are not published, but rollback must preserve a discontinuity notification before subsequent committed events: monotonic counters plus silently rolled-back chip state would otherwise produce an unreplayable, falsely continuous stream. A bounded capture sink records overflow and fails the CLI after rendering rather than throwing into the audio operation.

Add opt-in physical output to the existing FM render tool with explicit engine provenance, rates, origin and discontinuities; retain old output compatibility. Reject unsupported replay segments rather than inventing initial state. Continuous reset-origin YM replay against the existing Nuked implementation must recover the raw pins/write order, with separate PSG timing tests. Do not promise a whole-mixer replay across rewind or output-policy changes.

Red-first cases: queued burst strobes at internal cycles 0/1 then 35/36; real DAC byte address/data pair separated by one cycle; synthetic-origin events without added legacy notifications; PSG native-tick positioning; opt-in through session forwarding; aborted transaction publishes no aborted writes and the next committed operation has an explicit discontinuity; restore emits a boundary without rewinding diagnostic time; enabled/disabled capture yields identical PCM and snapshots; overflow and malformed/unsupported segment fail clearly. Run existing observer, DAC timing, snapshot, Sega PCM and relevant physical-device/session tests alongside new cases.

### Task 3: Evidence and maintained experiments

Ownership: `tools/audio/fm-core-benchmark/`, research report, handover correction, public release-copy correction. Do not modify the running audio-frontier entry owned by Task 1; send historical correction text to the lead.

Retain one opt-in, ROM-free synthetic benchmark entry point with pinned external sources and documented output/provenance. Remove machine-specific paths and CPU assumptions. Build under the invoking worktree's `target/`; reject accidental source-tree output. Validate checksums, snapshot replay and an active negative control; optional waveform comparisons must not be pass/fail audio-fidelity claims. Archive Graal/JNI results in the report rather than creating production integration.

## Implementation plan

- [x] Refresh `develop`; hash all three ROMs and verify Maven JDK 21.
- [x] Dispatch bounded exploration to appropriately sized agents; lead independently inspects source.
- [x] Create isolated coordination, S3K and capture worktrees; create evidence worktree.
- [x] Task 1: reproduce existing frontier, write failing admission/prefix tests, implement profile-owned guarantee, run focused tests, record new frontier separately from DAC, commit.
- [x] Task 2: approve concrete physical capture design, write timing/completeness/non-interference tests, implement and replay captured stream, commit.
- [x] Task 3: preserve minimal benchmark tooling and research evidence, correct public/handover claims, validate opt-in harness failure controls, commit.
- [x] Independent per-task spec/code review; return findings to the owner.
- [x] Merge reviewed task commits into the coordination branch, reconcile documentation, run ordinary suite and separate guards against the fresh baseline.
- [x] Independent whole-branch review and fix verification.
- [x] Refresh main `develop`, rebaseline if it moved, integrate with README release summary, run post-merge ordinary/guards and exact regression comparison, push only `develop`.
- [x] Inspect and remove only this round's fully merged worktrees/branches after successful push.

Verification commands (export `SONIC1_ROM`, `SONIC2_ROM`, and `S3K_ROM` as the discovered absolute ROM paths first; this round verified SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`, `8bca5dcef1af3e00098666fd892dc1c2a76333f9`, and `cfbf98c36c776677290a872547ac47c53d2761d6` respectively):

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Dsonic1.rom.path=$SONIC1_ROM" "-Dsonic2.rom.path=$SONIC2_ROM" "-Ds3k.rom.path=$S3K_ROM" test
LUA_BIN=lua5.4 mvn -Dmse=off -B -Pguards test
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Ds3k.rom.path=$S3K_ROM" -Dtest=TestS3kOracleRequestSidecarWiring,TestS3kSfxLifecycleRom,TestSmpsSequencerConfigCopyCoverageGuard test
```

## Integration report

Initial baseline: `4296bc291`, fetched and already current. Main workspace ordinary verification: 16,465 reported test executions, 0 failures, 0 errors, 40 skips (5:25); separate guards: 609, 0 failures/errors/skips (2:19). All three ROM hashes and Maven JDK 21 verified. Logs and compressed Surefire reports are archived under `target/audio-round-baseline-*` before reuse.

Measurement note: ordinary Surefire console reports 2,027 class-result lines but only 1,983 distinct final XML files; those XML files total 16,369 cases. Preserve the console execution count and XML case count separately rather than treating repeated class-result lines as independent XML artifacts. Both sources have empty failure/error sets. Comparison uses archived per-test outcomes and the final Maven result, not historical suite totals.

S3K `5aca88c31` and evidence tooling `856f91776` / `7ef9e44d8` are reviewed
and integrated into the coordination branch. Physical capture `aefd59738` /
`75c10b85a` is independently approved and integrated, with 48 focused tests
green. Main disassembly submodule changes are pre-existing
and out of scope. The [review record](../../validation/audio/2026-09-04-coordinated-audio-review.md)
tracks findings, corrections and subsequent combined/merged verification.

Combined verification: ordinary `ed9fd1e1b` 16,482 / 0 failures / 0 errors /
40 skips; guards `1da078e31` 609 / 0 / 0 / 0; combined focused audio selection
102 / 0 / 0 / 0. The first guard run exposed the new CLI-to-leaf-version edge;
independent review approved an explicit ratchet declaration, not a bypass.
Per-test comparisons show no new failures or skips. Main `develop` was fetched
and fast-forward checked again; it remains `4296bc291`, so no rebaseline is
needed. Delivery completed on September 5: merge `078e5df6f` was verified on
main (16,482 ordinary tests / 40 skips; 609 guards / no skips; zero failures
or errors in both), compared against baseline with no new regressions, and
pushed to `origin/develop`. The four fully merged task worktrees and local
branches were removed after evidence archival; older worktrees and user
changes were preserved. See the linked final delivery record for commands.

## End-to-end review

Required review questions: does the PSG fix follow the retail routine rather than the fixture? Are physical and logical notifications distinguishable? Can capture change PCM or scheduling? Are provenance, clock units and discontinuities explicit? Do research claims distinguish raw-core throughput, Native Image proof, waveform diagnostics and unperformed listening/platform tests? Do all delivery stages meet the repository workflow?

## Follow-on order after this milestone

1. Continue the S3K service oracle at the newly exposed lazy noise takeover
   write. Keep the DAC run-338 mismatch separate; derive its timing from the
   reference driver rather than absorbing it into a fitted constant.
2. Keep full-game BizHawk traces as the integration evidence. Use small,
   repeatable driver request windows to diagnose their first divergence, then
   replay the longer run to check interactions. State, ordered driver writes,
   physical chip strobes, and final PCM answer different questions; a match at
   one tier must not be substituted for a match at another.
3. Repair incomplete window context and extend recordings according to the
   handover priorities. A published red window remains evidence, not a green
   gate; preserve hard matching-prefix assertions and active negative controls.
4. Profile the unchanged Java Nuked implementation on a quiet host and a
   representative lower-end machine. Try measured hot-path optimizations under
   the pinned bit-exact tests before creating a runtime backend abstraction.
5. If the budget still requires a second core, compare batched native Nuked
   with a candidate fast core using complete physical inputs. A JNI experiment
   must next transfer actual PCM and exercise lifecycle, snapshots, Native
   Image packaging and supported platforms. A fast option is an explicit
   fidelity/performance choice, not an automatic resurrection of the removed
   core or an unmeasured clean-room rewrite.
6. Keep release listening and playable AIZ → HCZ validation ahead of broad
   framework migration. These release gates are not discharged by this round's
   automated audio work.

## Coordination ledger

- Agents: `s3k_frontier` (gpt-6-astra/high); `complete_capture` (gpt-5.6-terra/high); `evidence_audit` (gpt-5.6-sol/medium). Lead owns orchestration and integration.
- Ruling: parallel implementations use disjoint worktrees, overriding the generic SDD single-worktree serialization rule. Cost if boundaries are wrong: explicit merge/review work, never silent overwrite.
- Ruling: preserve Java Nuked this milestone; native/fast candidates remain research. Cost: no immediate performance improvement while correctness/evidence progresses.
- Ruling: capture work addresses actual timing/provenance rather than presumed missing DAC callbacks. Cost: a larger diagnostic contract than a simple logging patch, bounded by design review.
- Ruling: use explicit per-chip clocks, not an inferred common master clock. Cost: cross-chip consumers must retain clock domains/rates and cannot directly compare raw numeric timestamps.
- Preflight: Task 1 versus Task 2 shares no production file ownership; both observe the same audio pipeline and require combined regression testing. Task 1 versus Task 3 shares status prose only, resolved by lead ownership of historical frontier correction. Task 2 versus Task 3 shares tooling documentation only; separate subdirectories and explicit cross-links. Each lane must satisfy its own negative-control tests before review.
