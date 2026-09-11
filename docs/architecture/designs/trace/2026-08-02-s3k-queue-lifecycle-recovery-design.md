# S3K Queue Lifecycle Recovery Design

Date: 2026-08-02

## Requirements

### Goals

- Remove S3K trace-replay queue lifecycle failures by modelling the ROM producers and service boundaries that create them.
- Preserve exact, fail-closed hardware timing admission: kind, ordinal, fingerprint, prepared state, and service boundary must still match.
- Move each affected trace frontier independently and keep the full S1/S2/S3K fleet free of regressions.
- Record every frontier movement in `docs/status/trace-frontier-log.md`.

### Non-goals

- Do not relax `HardwareTimingReplayPort`, accept mismatched fingerprints, synthesize missing production work, or drain a queue merely because a trace ends.
- Do not add game, zone, route, trace, or frame-number carve-outs to shared runtime code.
- Do not replace canonical trace fixtures unless a recorder defect is proved and the replacement payload is separately authorized.
- Do not hide earlier gameplay divergences to reach a later queue event.

### Constraints

- Runtime art bytes come only from the user-supplied ROM.
- Queue timing may release only matching production-submitted work under the cross-game timing contract.
- The shared direct and module queue implementations remain unchanged unless a new failing test demonstrates a queue-owned defect.
- Each fix must have focused producer/lifecycle coverage plus its affected trace replay.

### Acceptance criteria

- Gumball, Pachinko, and Slots close their standalone stage prefix at the recorder-observed mode departure only when all in-scope production queues are idle; ordinary fixture exhaustion still rejects every unconsumed timing edge.
- LBZ submits the recorded miniboss-box KosM parent at both ROM producer sites (object initialization and collapse-clear), once per site, with the exact ROM source and destination.
- AIZ, CNZ, ICZ, MGZ, HCZ, and MHZ are triaged at their current first error; each queue-owned or producer-owned mismatch is fixed without trace-keyed behavior.
- The focused queue/timing/authority matrix remains green.
- A full `*TraceReplay` sweep on JDK 21 introduces no S1 or S2 regression and establishes the final line-by-line S3K frontier.

### Assumptions

- The existing 138-test queue/timing/authority matrix accurately establishes that the common queue and admission mechanisms are internally coherent.
- Rows from the structurally observed `zone_act_state` departure onward belong to the outer return-to-level/title-card transition, which the standalone fixture does not execute; live provider completion may occur earlier because the ROM sets its restart flag during object processing and the recorder samples the following raw boundary.
- The repeated LBZ direct-queue fingerprint at ordinals 279 and 280 is the first child stream at `ART_KOSM_LBZ_MINIBOSS_BOX_ADDR + 2`; production creates it by submitting the parent KosM archive at object initialization and again at the collapse-clear transition rather than by renderer demand.

### Risks

- Advancing one queue mismatch may expose an earlier gameplay or object-lifecycle divergence. Such a frontier is reported and fixed at its owning subsystem rather than compensated in queue code.
- AIZ module event attribution may be recorder-owned. Recorder changes require native evidence and do not authorize fixture replacement.
- Several routes may share an art provider. Parallel implementation is limited to disjoint owners and merged serially with focused regression checks.

## Exploration synthesis

The complete fleet currently has S1 30/30 and S2 20/20 green. S3K has one green trace and thirteen red traces. A focused queue, timing, structural-sequence, authority, and guard matrix passes 138/138, which makes a shared queue-core defect unlikely.

The failures divide into four ownership groups:

1. **Replay lifecycle:** Gumball, Pachinko, and Slots intentionally stop at the live bonus completion flag. Their timing schedules, however, still contain outer return-to-level/title-card jobs from 154, 150, and 176 later source-movie rows, so strict whole-fixture closure correctly reports those out-of-scope jobs as unconsumed.
2. **Missing producer:** LBZ records identical direct children sourced from `0x37567C` at raw frames 17604 and 19709. The engine loads standalone render art but does not submit the parent `ArtKosM_LBZMinibossBox` module job at either ROM producer site (`loc_8CB9E` initialization and `loc_8CC8C` collapse-clear).
3. **Producer phasing or ordering:** AIZ, CNZ, ICZ, and MGZ show early, late, duplicate, or differently ordered production submissions.
4. **Upstream gameplay/object divergence:** HCZ and MHZ diverge in gameplay state before their eventual queue-closure diagnostics, so those earlier owners must be repaired first.

The common timing port is behaving as designed: it refuses absent work, unprepared work, wrong ordinals, wrong fingerprints, and wrong boundaries. The recovery therefore starts at the lifecycle and producer owners.

## Architecture decision

Use an owner-first, frontier-driven campaign. Keep the timing authority and both queue cores strict. Repair real production lifecycles in the smallest owner, rerun the affected trace, and only then select the newly exposed frontier.

Alternatives rejected:

- **Drive the whole source-movie tail inside the standalone bonus fixture.** The fixture does not execute the outer game-mode transition that owns the return-to-level/title-card jobs, so this would compare different production lifecycles.
- **Call the existing abort teardown at bonus completion.** Abort is reserved for preserving an earlier replay failure and does not prove that in-scope production work is idle.
- **Admit or synthesize jobs from trace events.** This violates the production-submitted-work constraint and lets comparison data decide gameplay work.
- **Apply a shared FIFO timing adjustment.** The passing focused matrix and mixed early/late/missing signatures do not support a common queue defect.
- **Regenerate fixtures immediately.** A possible AIZ recorder-attribution defect is not evidence that all queue failures are fixture defects, and replacement requires a separate exact-payload publication decision.

## Feature design

### 1. Close the standalone bonus prefix explicitly

The standalone bonus fixture's existing audited `zone_act_state` events declare the scope structurally. The first state change after frame 0 that leaves bonus `game_mode=12` marks the outer return-to-level transition; the immediately preceding represented raw row is the last row owned by the standalone bonus fixture. No canonical fixture file or publication digest changes.

Live `BonusStageProvider.isStageComplete()` is not the scope authority. ROM `LevelLoop` checks `Restart_level_flag` after `Process_Sprites`, while `zone_act_state` is observed at the following raw boundary. Consequently, a ROM-faithful producer may report completion before the final represented bonus row (Gumball), on it (Slots), or only as the departure row begins (Pachinko). The replay must drive and compare the full structurally declared prefix, then close timing independently of the provider predicate.

The prefix close will:

1. Verify that every timing edge whose raw frame is at or before the declared boundary has been consumed.
2. Assert through the production timing authority that no submitted work remains pending.
3. End recorded admission and detach the replay observer.
4. Permit later recorded edges to remain only because the recorder-observed mode transition classifies them outside the standalone prefix.

It cannot submit, prepare, admit, claim, or drain work. If any in-scope production job is pending, it fails. Ordinary fixture exhaustion and run/segment handoff continue to call strict `verifySegmentEdges()` and reject the same future edge.

Boundary derivation validates that the fixture starts in the bonus mode, contains exactly one later departure relevant to this standalone segment, and has a represented predecessor row. Focused tests will prove all sides of the distinction: missing or malformed structural boundary fails; closing or advancing at any row other than the derived predecessor fails; an in-prefix unconsumed edge fails; prefix closure with pending production fails; exact structural closure with all in-prefix edges consumed and idle production succeeds despite a future out-of-prefix edge; strict closure with the identical schedule still fails. Each of the three bonus trace replays is the end-to-end acceptance test.

Exit-producer parity remains independently guarded. Gumball tests the ROM `Check_PlayerInRange` bounds and one-shot exit request, Pachinko tests the inclusive `y=-$20` non-exit and `y=-$21` exit branches, and Slots retains its 155-tick ROM fade-completion test. These tests prevent structural timing scope from concealing a broken production exit lifecycle.

### 2. Submit and own both LBZ miniboss-box modules

`Lbz1RobotnikEventController` will submit the miniboss-box module through `S3kRuntimeArtCoordinator.from(services()).moduleQueue()` from both ROM sites: once during `ROUTINE_INIT`, matching `loc_8CB9E`, and once at the single `WAIT_FOR_COLLAPSE_CLEAR` to `AFTER_COLLAPSE` transition, matching `loc_8CC8C`. It submits the parent archive at `ART_KOSM_LBZ_MINIBOSS_BOX_ADDR` to tile destination `ART_TILE_LBZ_MINIBOSS_BOX`. `S3kKosModuleQueue` alone creates the recorded first direct child from `ART_KOSM_LBZ_MINIBOSS_BOX_ADDR + 2`.

The controller routines own the two one-shot trigger guarantees. The controller retains both parent handles until readiness, claims each once, and mirrors the established object-owner rewind pattern: scalar ordinals are captured, transient handles are rebound through `services().hardwareTiming().pendingHandle(...)` after restore, and a missing restored handle fails closed. Focused tests will cover exact parent source/destination, same-frame submission at both sites, no duplicate on subsequent updates, independent readiness/claim of both handles, and rewind restoration before and after each producer site.

### 3. Advance producer frontiers independently

After the two high-confidence fixes merge, each remaining trace is rerun from its current frontier:

- AIZ: compare intro/cutscene art submission phase against the ROM producer and audit module event attribution separately.
- CNZ: resolve `0xDB408` in the disassembly and restore the badnik/explosion archive producer at its actual object or PLC owner.
- ICZ: validate the act-transition batch, ownership transfer, publication order, and duplicate suppression.
- MGZ: separate title-card dispatch phasing from the complete-run missing producer.
- HCZ and MHZ: fix the earlier physics/object/ring divergence before interpreting terminal queue diagnostics.

Each lane may change only its smallest production owner. Shared provider edits are serialized.

### 4. Recorder evidence lane

The native recorder's duplicate-level-frame classification is audited against program-counter and service-boundary evidence for the AIZ module event. If the event is misattributed, add recorder tests and correct the recorder. Capture replacement candidates with hashes, but do not install canonical payloads without explicit authorization.

### 5. Verification and integration

For every merged fix:

- Run its focused unit/headless tests.
- Run its affected trace replay with all three ROM properties available.
- Run the 138-test queue/timing/authority matrix after shared-owner changes.
- Update the trace frontier ledger with command, commit/worktree context, pass/fail, error count, and first error.

Before delivery, compare the complete Maven suite and the full `*TraceReplay` fleet against the updated `develop` baseline, merge into `develop`, rerun both on the merged branch, push `develop`, and remove the campaign worktrees and fully merged local branches.
