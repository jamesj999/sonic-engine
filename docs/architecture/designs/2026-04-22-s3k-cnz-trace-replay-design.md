# S3K CNZ Trace Replay - Design

Status: draft
Date: 2026-04-22
Scope: Record a full-run CNZ1+CNZ2 trace from the existing BK2, add a matching trace replay test, drive the test to a clean pass by implementing whatever CNZ parity gaps the divergence report surfaces.

Related BK2: `src/test/resources/traces/s3k/cnz/s3k-cnz-sonic-tails.bk2`.

Related context docs:
- `docs/architecture/designs/2026-03-26-bizhawk-trace-replay-testing-design.md` - trace replay framework this builds on.
- `AGENTS_S3K.md` / `CLAUDE.md` - S3K-specific constraints (S&K-side addresses only, zone-set-aware IDs, dual collision model, etc.).

## 1. Problem

The repo has a `TestS3kAizTraceReplay` fixture demonstrating end-to-end S3K trace replay for AIZ1 through HCZ. We need the same coverage for CNZ (Carnival Night Zone). We have a BK2 recording but no trace CSV, no trace test, and several implementation gaps in the CNZ engine code that will cause the trace to diverge even once recorded.

Two orthogonal problems have to be solved:

### 1.1 Recording workflow problem

The BK2 uses a realistic player's path to reach CNZ: title screen -> enter AIZ1 -> grab a vine and input the level-select button combo -> pause + A to soft-reset back to title -> open the now-visible Level Select -> pick CNZ with Sonic & Tails -> play through. The current `s3k_trace_recorder.lua` profiles do not handle this: `gameplay_unlock` starts recording when AIZ1 gameplay begins and finalises when the player exits back to the title, so the CNZ section that follows is lost; `aiz_end_to_end` records from BK2 frame 0 and never drops data, so the CNZ CSV would be prefixed with a throwaway AIZ1 segment. We need a "discard-and-reset on title-return" profile that treats any gameplay interrupted by a soft-reset to title as scratch data, resets the recorder state, and latches onto the next level.

### 1.2 Engine parity problem

CNZ has substantial infrastructure already (event handler, scroll handler, palette cycling, animated tile channels, 15+ zone objects, bounded miniboss + end-boss stubs) but several features needed for a full run are incomplete or missing: the Tails-carry entry sequence that plays when a sidekick-paired team enters from level select, the Act 1 mini-boss choreography beyond arena destruction, the Act 2 end-boss attack phases, and Act 2's Knuckles cutscene encounters. Approach C (first-divergence-first) means we discover the real gap empirically from a baseline replay run rather than speculating.

## 2. Goals

1. Produce a CNZ trace fixture (physics.csv, aux_state.jsonl, metadata.json) derived from the existing BK2 and containing only CNZ1+CNZ2 gameplay frames.
2. Add `TestS3kCnzTraceReplay` that consumes the fixture and passes with zero errors (warnings acceptable).
3. Maintain the existing AIZ test (`TestS3kAizTraceReplay`) with no regressions.
4. Not regress any S3K or S1/S2 trace tests or headless physics tests.
5. Keep all new and modified files cleanly attributable; every ROM offset verified against the S&K-side disassembly.

## 3. Non-goals

- Visual parity of the bosses (the user explicitly de-scoped visuals; art loading is attempted but not gated on).
- Recording S3K traces for any zone other than CNZ in this branch.
- Implementing any boss/object/cutscene that the divergence report does not actually surface as a blocker.
- Generalising the recorder to arbitrary multi-level sequences - only "prior level abandoned via reset-to-title" is in scope.

## 4. Architecture overview

Seven workstreams, labelled A through G, executed in dependency order. A and B are prerequisite; C through G are driven by the divergence report and dispatched lazily to subagents in parallel where independent.

```
A. Recorder profile     ---> (single trace recording run) ---> B. Trace fixture + test scaffold
                                                                |
                                                                v
                                                         Baseline divergence report
                                                                |
                                          +---------------------+---------------------+
                                          |        |        |        |        |       |
                                          v        v        v        v        v       v
                                        C. Intro D. MBoss E. EBoss F. Knux G. Objs   ...
                                             (all optional, dispatched only if divergence surfaces them)
                                          |        |        |        |        |       |
                                          +---------------------+---------------------+
                                                                |
                                                                v
                                                      Re-run test, loop until zero errors.
```

Each of C-G, when dispatched, operates inside a git worktree to avoid colliding with the other live agents (AIZ trace agent, S2 EHZ1 trace agent). Results are merged back into this worktree in the order they are completed.

## 5. Workstream A: Recorder "discard-on-reset" profile

### 5.1 Requirements

- Add a new profile name (proposal: `level_gated_reset_aware`) selectable via `OGGF_S3K_TRACE_PROFILE`.
- Behaviour: listen for gameplay entry (same heuristic as `gameplay_unlock`). If gameplay exits via a soft-reset to title screen, treat everything recorded so far as scratch, reset internal state, and keep listening for the next gameplay entry. Finalise on gameplay exit that is *not* a reset-to-title (i.e., normal level completion / next-level load), or on BK2 end.
- Output file naming / paths unchanged.

### 5.2 Detection

Soft-reset to title is visible as a Game_Mode transition from `0x0C` (level) to the title-screen mode in short order, without passing through the usual level-complete / signpost / score tally game modes. `GAMEMODE_LEVEL = 0x0C` is already defined in the recorder (`s3k_trace_recorder.lua:100`) and `AbstractTraceReplayTest.resolveS3kTraceGameMode` already knows title-screen mode `0x00` (`AbstractTraceReplayTest.java:507-527`). Working hypothesis: title = `0x00`, level-select = `0x08`, level = `0x0C`. Identification checklist:

- Confirm the title-screen Game_Mode constant via `RomOffsetFinder --game s3k search GameModeArray` and/or `search MainGameLoop`, cross-referenced with `AbstractTraceReplayTest.resolveS3kTraceGameMode`. Don't assume - check.
- Add constants for: `GAMEMODE_TITLE`, `GAMEMODE_LEVEL_SEL` (and re-use existing `GAMEMODE_LEVEL`).
- Reset trigger: we were recording (in gameplay), then Game_Mode transitions to title-screen-mode directly within <= some small frame window without passing through level-complete indicators. Simpler formulation: "Game_Mode was 0x0C, is now title-screen-mode" AND the Sonic death/results/score-tally flags were not set before the transition. The heuristic does not have to be perfect - the reference recording is deterministic, so once it works on this BK2 we pin it.

### 5.3 File implementation

- Edit `tools/bizhawk/s3k_trace_recorder.lua`. All edits additive: new profile table entry, new helper functions for reset detection, conditional branches that only run when the new profile is active. Do not change the behaviour of `gameplay_unlock` or `aiz_end_to_end`.
- Discarding the in-progress recording means truncating the in-memory buffers, resetting frame counters, re-opening (or just overwriting) the output files. Easiest approach: buffer all frames in memory until finalisation, then write once at the end; a reset clears the buffer. Safer for partial runs: write to a temp file while recording, rename on finalise, delete on reset. Given typical CNZ recording size is <50 MB CSV, <150 MB JSONL, on-disk-with-delete-on-reset is the safer option (in-memory buffering of 150 MB is wasteful for a Lua script inside the BizHawk process). Use `os.remove()` on the three outputs at reset, then overwrite normally once re-armed.
- **Metadata parity:** the new profile must write the same metadata fields the `aiz_end_to_end` profile does (`rom_checksum`, `notes`, `characters`, `main_character`, `sidekicks`) so Java-side loaders get a consistent schema. Add a single new top-level metadata field `trace_profile` (string) equal to `OGGF_S3K_TRACE_PROFILE` so downstream code can identify which profile produced the fixture. This is a backwards-compatible addition (existing loaders ignore unknown fields).
- **Recorder is read-only during the armed window.** The recorder must not poke RAM, load savestates, issue resets, or otherwise alter emulator state during a recording. Doing so would invalidate every other agent's fixture that shares the recorder.

### 5.4 Considered alternatives

An earlier iteration considered a `OGGF_S3K_TRACE_START_ZONE=3` gate (don't start recording until the player enters CNZ) instead of the discard-on-reset heuristic. Rejected because:
- The user explicitly asked for a "detect reset to title and discard" mechanism so agents reviewing the recording behaviour see the intent in the control flow.
- The reset mechanism is more robust to BK2 retiming (a one-second shift in the level-select cheat timing doesn't require updating an environment variable).
- Both mechanisms end up with the same fixture, but the reset mechanism lets us verify the actual reset path is exercised in the recording (useful signal for future multi-level trace work).

If the reset heuristic proves flaky, we fall back to `OGGF_S3K_TRACE_START_BK2_FRAME=N` as an escape hatch. The Lua should support both; the test profile defaults to reset-detection and sets no start frame.

### 5.5 Coordination risk with AIZ trace agent

The AIZ agent is actively iterating on `s3k_trace_recorder.lua`. Mitigations:
- Keep our additions inside clearly-marked blocks at the bottom of the file where possible.
- Do the edit in one commit, push immediately, tell the user so they can inform the AIZ agent.
- If a merge conflict arises, prefer additive resolution (keep both agents' additions).
- If the AIZ agent has already added a `level_gated_reset_aware` or similar named profile, reuse their machinery rather than duplicating.
- **Additive-only is best-effort.** If another agent has rewritten `should_start_recording()` so that an additive replace-block fails, do *not* overwrite their changes; rebase the new branch on top of theirs, re-apply minimally, and re-commit.

### 5.6 Validation

- Run the new profile against the CNZ BK2 and confirm the AIZ1-then-reset prefix is discarded, CNZ frames are captured, metadata `zone_id = 3` and `trace_profile = "level_gated_reset_aware"`.
- Run the existing AIZ end-to-end BK2 with `aiz_end_to_end` profile and confirm output is byte-identical to the current fixture (no behavioural regression). "Byte-identical" means `physics.csv` and `aux_state.jsonl` diff clean against `HEAD~1`; `metadata.json` may gain the new `trace_profile` field and a `recording_date` bump, which we accept.

## 6. Workstream B: Trace fixture + test scaffold

### 6.1 Record the trace

Invoke `tools/bizhawk/record_s3k_trace.bat` with:
- ROM: `Sonic and Knuckles & Sonic 3 (W) [!].gen`
- BK2: `src/test/resources/traces/s3k/cnz/s3k-cnz-sonic-tails.bk2`
- Profile: `level_gated_reset_aware`

Copy outputs to `src/test/resources/traces/s3k/cnz/`:
- `physics.csv`
- `aux_state.jsonl`
- `metadata.json`

Commit all four files (BK2 already present + three recorder outputs). CSV typically stays under 10 MB, JSONL can approach 150 MB for a two-act run - above GitHub's 50 MB large-file warning but below the 100 MB hard cap (confirm post-recording; if a single file exceeds 100 MB, use Git LFS or truncate the frame range).

### 6.2 Minimum checkpoint skeleton (required for elastic window)

`S3kElasticWindowController` synchronises on named aux-stream checkpoints; with zero CNZ checkpoints emitted, strict mode runs for the entire trace and the test has no way to tolerate tiny frame-offset drift between recorder and replay. The recorder's `emit_s3k_semantic_events()` must emit at least:

- `gameplay_start` - first frame where `Game_Mode == 0x0C` and zone == CNZ.
- `cnz1_miniboss_arena_lock` - camera bounds narrow to miniboss arena (detect via `Dynamic_Resize` state or camera MaxX drop).
- `act_transition_to_cnz2` - zone stays CNZ, act flips to 1, player position snaps to act-2 spawn.
- `cnz2_knuckles_cutscene_start` - Knuckles cutscene routine routine secondary transitions.
- `cnz2_endboss_arena_lock` - end-boss camera bounds engage.
- `gameplay_end` - last recorded frame (emit when finalising).

These are the minimum set the test needs to engage elastic windows at the right phase boundaries. Additional fine-grained checkpoints (e.g. individual boss-hit frames) can be added later, driven by the divergence report.

### 6.3 Test scaffold

New file `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`, modelled on `TestS3kAizTraceReplay`:

```java
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 0x03; }   // CNZ
    @Override protected int act()  { return 0x00; }   // CNZ1 start - 0-based
    @Override protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s3k/cnz");
    }
}
```

Note the metadata file writes `"act": 1` (1-based) while `act()` returns `0` (0-based). `AbstractTraceReplayTest.validateMetadata` currently validates `game` but not `act`, so this asymmetry is harmless - document it in a comment on the `act()` override.

If `AbstractTraceReplayTest` grows CNZ-specific hooks during iteration (elastic windows around bosses, cutscene tolerance), those hooks should be exposed as `protected` overrides rather than hard-coded CNZ branches. If the AIZ agent has introduced a more general hook surface already, we reuse it.

### 6.4 Baseline run

First test run will almost certainly fail. We capture the divergence report (`target/trace-reports/s3k_0300_report.json` and `_context.txt`), copy it into `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md`, and commit it as the ground truth for what C-G need to fix. **Archiving the baseline report is a required deliverable, not optional** - without it there is no audit trail for C-G dispatch decisions.

## 7. Workstreams C-G (lazy)

Specs are written only when a divergence report surfaces the need. Each gets its own design doc and plan under `docs/architecture/designs/`. Expected shape:

### 7.1 C - Tails-carry entry (Sonic & Tails, level-select launched)

When a team is launched from level select with Tails as sidekick in a two-player session, Tails *may* fly in carrying Sonic. Level-select entry sometimes skips the sidekick-carry routine entirely and spawns both characters in-place; verify the actual behaviour from the BK2 before implementing. Disassembly entry point: start from `RomOffsetFinder --game s3k search Tails_LoadData` and `search LevelEntryTails`, cross-reference `Level` init in `sonic3k.asm`. Implementation lives under `game/sonic3k/` shared code rather than CNZ-specific. **Do not implement speculatively** - dispatch only if the divergence report shows sidekick position drift in the first ~60 frames.

### 7.2 D - CNZ Act 1 mini-boss (`Obj_CNZMiniboss`)

Existing `CnzMinibossInstance` + `CnzMinibossScrollControlInstance` + `CnzMinibossTopInstance` live under `game/sonic3k/objects/`. **Verify first** that these factories are actually registered in `Sonic3kObjectRegistry` before claiming they are "promoted" - check via `Grep` for `CnzMinibossInstance` in the registry file. The full state machine (swing up-down, coil retract / extend, player hit windows, hit count, defeat sequence, capsule spawn) is missing. Uses `Sonic3kBossInstance` base class, follows the `s3k-implement-boss` skill.

### 7.3 E - CNZ Act 2 end-boss (`Obj_CNZEndBoss`)

Existing `CnzEndBossInstance` handles startup presence and defeat handoff only. Attack phases (pinball-flipper routine, drop attack, shield reactions if any) are missing. Same base as D.

### 7.4 F - Knuckles cutscene encounters

Use `RomOffsetFinder --game s3k search KnuxCNZ` and `RomOffsetFinder --game s3k search CutsceneKnuckles` to find the real labels - do NOT guess names like `Obj_KnucklesCutscene`. S3K usually has a Knuckles-interferes routine per zone that sets up trap doors, drops rocks, or similar. For CNZ the teleporter route switch already exists (`Sonic3kCNZEvents` handles camera clamping), but if the trace shows a Knuckles object spawn that is not currently spawning, a dedicated cutscene instance is needed.

### 7.5 G - Stragglers

Anything the divergence report surfaces that is not covered by C-F: missing badnik, wrong monitor payload, off-by-one in a bumper bounce, missing ring layout, palette cycling off-phase, etc. Each gets a tiny targeted fix rather than a full spec.

## 8. Testing strategy

1. After each iteration, run `mvn test -Dtest=TestS3kCnzTraceReplay` and read `target/trace-reports/s3k_0300_*`.
2. Run a wider guard: `mvn test -Dtest="TestS3kAizTraceReplay,TestS3kCnzTraceReplay,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils"` after any change to shared S3K infrastructure.
3. When a subagent finishes a workstream, it is responsible for running these two suites before claiming completion.
4. Final gate: a full `mvn test` before the final commit / PR.

## 9. Risks and unknowns

- **Reset-detection heuristic fragility.** If the title-screen Game_Mode transition looks identical to other transitions in certain edge cases, false discards could happen. Mitigated by keeping the reference recording deterministic and verifying the exact frame ranges against the BK2's Input Log. Escape hatch: `OGGF_S3K_TRACE_START_BK2_FRAME=N` (see §5.4).
- **Simultaneous edits with AIZ trace agent.** High risk on `s3k_trace_recorder.lua`, `AbstractTraceReplayTest.java`, `S3kElasticWindowController.java`, and `S3kReplayCheckpointDetector.java`. The latter two will have *semantic* merge conflicts as both agents add checkpoints and phase definitions - textual conflicts are the easy case. Mitigate by additive editing, early commits, and running both `TestS3kAizTraceReplay` and `TestS3kCnzTraceReplay` on merge.
- **Trace size.** CNZ1+CNZ2 full run is longer than AIZ1; CSV could approach 5-10 MB, JSONL could approach 80-150 MB - above GitHub's 50 MB large-file warning. Measure post-recording; if a single file exceeds 100 MB, evaluate Git LFS vs frame-range truncation before committing.
- **Boss choreography ROM divergences.** The S3K ROM has subtle RNG sources (player-position-dependent timers) that can cause tiny but compounding divergences. `S3kElasticWindowController` handles *frame-offset tolerance between named checkpoints* - it does NOT paper over per-frame RNG drift during a continuous boss fight. If a boss phase drifts mid-phase, the fix is an engine-side parity fix (ROM-accurate RNG source, cycle-accurate frame order), not a widened window. If we genuinely need per-frame elasticity, that is a separate design doc and a user decision - it is not an implementation choice for C-G.
- **Art loading for bosses.** Visuals are out of scope per user; attempting art loading but not gating tests on it. If art loading crashes the headless test, we catch and log; if it throws unchecked, we patch.
- **Level-select entry bootstrap path.** CNZ1 starts from the level-select screen, not from a natural level-entry flow. `Sonic3k.loadLevel` / `Sonic3kLevelEventManager` may have untested "came from level select with mid-zone team init" code paths. First divergence surfacing as "player position wrong at frame 0" is this. Diagnose before dispatching C.
- **`S3K_SKIP_INTROS` config interaction.** The config flag controls whether the engine's title sequence is skipped. It must be disabled (or handled) for the CNZ test, because the trace starts mid-game mode transitions. Confirm at test bootstrap time.

## 10. Success criteria

- `mvn test -Dtest=TestS3kCnzTraceReplay` passes with 0 errors and <= 200 warnings on first green run (tightened iteratively toward <= 50 before merge).
- `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md` exists, committed as the first iteration artefact. Without this the audit trail for C-G dispatch is broken.
- `TestS3kAizTraceReplay` still passes, with `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/physics.csv` and `aux_state.jsonl` unchanged byte-for-byte against HEAD~N (N = commit count since the start of this workstream). Metadata may gain the new `trace_profile` field and a `recording_date` bump; other fields must be identical.
- No regressions in the full `mvn test` run.
- All ROM addresses referenced by new code are verified against the S&K-side disassembly and documented (source label + address + the `RomOffsetFinder` command that produced them).
