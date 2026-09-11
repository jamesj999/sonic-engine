# Trace Frontier Advancement Prompt

Reusable prompt for future trace replay work. Paste this into a fresh agent
session when the goal is to move one or more trace replay frontiers forward
without masking ROM parity bugs.

---

You are advancing OpenGGF trace replay parity. Your job is to make the engine
natively reproduce more of the recorded ROM behavior, one verified frontier at a
time.

## Required Reading

Before changing code:

1. Read `AGENTS.md`.
2. Read `.agents/skills/trace-replay-bug-fixing/SKILL.md`.
3. If you touch Sonic 2 objects or disassembly, read:
   - `.agents/skills/s2-implement-object/SKILL.md`
   - `.agents/skills/s2disasm-guide/SKILL.md`
4. If the target is S3K, read the matching S3K zone/object/disassembly skills.

Apply the trace replay invariant throughout: trace data is read-only comparison
and diagnostic input. The engine must not hydrate or sync state from trace rows
inside the replay loop.

## Mission

Advance the highest-priority failing trace frontier while keeping the engine
accurate to the original game and aligned with the current architecture.

Current priority:

1. Advance SCZ first if it still has an actionable frontier.
2. Then advance the nearest failing frame among the other S2 level-select traces.
3. Prefer a small, disassembly-backed engine fix over broad speculative work.

Reconfirm the current failures before coding — frontiers move as fixes
land. Use:

```
mvn -q "-Dtest=*TraceReplay" test -DfailIfNoTests=false 2>&1 | grep -E "First error|MSE:TESTS"
```

to enumerate live frontiers in one pass. Each first-error line names the
zone, frame, field, and expected/actual values; pick the highest-impact
target.

**Target-selection heuristics** based on the iter-1..N history:
- Same field + same first-error frame across multiple zones is a
  strong signal of a shared root cause — investigate jointly.
- A frontier that has stayed at the same frame across several iters
  is "stuck" — either deeply nested (e.g., the CNZ f202 tails_x 1px
  Flipper post-launch sub-pixel) or needs recorder-side diagnostics
  before another speculative fix attempt.
- A frontier that moves backward after a fix usually means the fix
  was correct and uncovered an earlier-cascade bug; the new frontier
  is the next thing to chase, not a regression.
- A fix that drops the error count significantly but doesn't advance
  the first-error frame is still real progress — the underlying bug
  is partially addressed, and the residual deserves its own iter.

## Execution Loop

1. Pick one target trace and run its focused test.
2. Read the first failing frame from:
   - `target/trace-reports/<game>_<zone>_report.json`
   - `target/trace-reports/<game>_<zone>_context.txt`
3. Classify the failure before editing:
   - bootstrap or route prelude,
   - camera or scroll,
   - terrain physics,
   - object spawn, routine, or placement,
   - solid object contact, riding, carry, or release,
   - sidekick AI or following history,
   - zone event, mutation, palette, PLC, or animation state.
4. Locate the ROM routine in the relevant disassembly and compare it to the
   engine path. Cite source file and line numbers in notes, commits, and any
   code comment that exists only to explain a ROM quirk.
5. Classify the implementation surface before coding:
   - game-local object/zone/event code,
   - shared object/solid/camera/scroll infrastructure,
   - shared physics/collision/player movement,
   - trace recorder/parser/reporting diagnostics only.
6. For any shared or plausibly shared behavior, verify the corresponding ROM
   behavior across Sonic 1, Sonic 2, and Sonic 3&K before choosing the code
   shape. If the games differ, introduce or reuse the narrowest typed
   `GameRules` rule, provider/profile, registry, or object-local path; do not
   let an S2 fix silently change S1/S3K.
7. Fix the engine path that should have produced the ROM value.
8. Run the focused test again.
9. Run cross-game regression checks proportional to the touched surface. Shared
   physics/collision changes require S1, S2, and S3K coverage; game-local object
   fixes require at least the target trace plus nearby shared-object/trace tests
   that exercise the same manager path.
10. Record whether the frontier passed, moved later, or stayed at the same frame,
   and record which cross-game checks were run or why a narrower scope was safe.
   Update `docs/status/trace-frontier-log.md` with the command, commit/worktree
   context, pass/fail status, error count, and first-error frame/field whenever
   the frontier changes, a trace fix is committed, a passing trace regresses, or
   a full trace sweep is used to pick the next target.
11. If the target moved, decide whether to continue on the same trace or switch
   to the next highest-priority trace based on route impact and fix scope.

Do not treat a moved frontier as a fully solved trace unless the trace test
passes. Report the exact new first failing frame.

## Rules

- Do not change trace data just to make a test pass.
- Do not add per-frame trace-to-engine hydration, sync, reseeding, tolerance
  bands, or elastic comparison windows.
- Diagnostic reseeding is allowed only as uncommitted local investigation.
- If the trace lacks enough ROM-side state to diagnose the bug, extend the Lua
  recorder and Java parser as diagnostics only, bump script/schema metadata as
  needed, regenerate the affected trace, and keep the new data out of engine
  mutation paths.
- Use disassembly-backed constants and behavior. Do not infer ROM rules from
  engine convenience alone.
- For shared physics, collision, sidekick, object, camera, or scroll code,
  check whether S1, S2, and S3K share the behavior. Gate real per-game
  divergence with the narrowest typed `GameRules` rule or an existing
  provider/profile/registry/object owner, not game-name checks.
- Never assume a shared engine path is safe because one game's trace improves.
  Before landing shared behavior changes, verify whether Sonic 1, Sonic 2, and
  Sonic 3&K use the same ROM rule and run tests that can catch regressions in
  every involved game.
- Keep object code on `ObjectServices`; keep non-object runtime code on
  `GameServices` or explicit mode-context dependencies.
- Prefer runtime-owned shared frameworks when relevant:
  `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`,
  `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`,
  `ScrollEffectComposer`, `SpecialRenderEffectRegistry`, and
  `AdvancedRenderModeController`.
- For ROM `x_pos` and `y_pos`, use engine centre-coordinate APIs
  (`getCentreX`, `setCentreX`, `getCentreY`, `setCentreY`) unless the code is
  explicitly about sprite bounds or render extents.
- For gameplay-affecting scroll, logic-frame code must produce the state used
  by headless replay and rendering. The render pass should consume scroll state,
  not be the only place that mutates it.
- For moving solids, preserve ROM ordering: routine transition, timer update,
  object motion, embedded `SolidObject` call placement, standing-bit refresh,
  carry, and release.

## Subagent Strategy

Use subagents when the work naturally splits into independent tracks:

- one explorer to classify the failing frame and summarize report context,
- one explorer to locate and summarize the ROM routine,
- one worker to implement a bounded fix in a disjoint file set,
- one reviewer to check for trace invariant violations and architecture drift.

Do not delegate the immediate blocker if your next local step depends entirely
on that answer. Continue local work on a non-overlapping task while agents run.

### Subagent dispatch rules learned from prior loops

These are non-negotiable for any agent you dispatch to land an engine fix:

1. **Worktree isolation defaults to `master`, not the current branch.**
   Every dispatched agent must reset its worktree explicitly:
   ```
   git fetch origin develop 2>/dev/null || true
   git reset --hard develop
   git log --oneline -1
   ```
   Then verify by reading a landmark file you know lives only on the
   feature branch (e.g., a trace-package file the master branch lacks).
   Without this reset, the agent investigates and patches against the
   master-baseline copy of files — its changes are real but against the
   wrong baseline, and patches won't apply at integration time.

2. **Agents have hallucinated successful fixes.** An agent will sometimes
   report `Files modified: X.java` (with a plausible diff sketch) while
   the worktree contains zero on-disk changes. Bake the proof into the
   prompt:
   ```
   Before reporting, run `git status --short` and `git diff --stat`.
   Paste the output VERBATIM in your report. The status MUST show
   modified files; the stat MUST show line counts > 0. If they're
   empty, your fix didn't land — retry or report failure honestly.
   ```
   At integration time, verify in the orchestrator workspace:
   `git -C <worktree> status --short` and confirm the claimed files
   actually appear there before copying.

3. **The Edit tool intermittently fails to write CRLF files** in
   worktree-isolated agents (observed iter 4). Symptom: Edit reports
   success, `Read` shows the new content, but `git diff` shows nothing
   and the file's mtime doesn't update. Workaround in the prompt:
   if `git diff <file>` after an Edit is empty, retry with the Write
   tool (full-file rewrite) or via a small Python script with explicit
   `open(path, "wb")`.

4. **Single focused agent beats parallel-3** for the loop's typical
   workload. Parallel runs had a ~33% real-fix rate in iter 2 (1 of 3
   landed; 1 hallucinated, 1 honest failure). A single agent per iter
   gives clearer signal, easier integration, and lower hallucination
   risk. Reserve parallel dispatch for genuinely disjoint scopes
   (different files, different bug families) where one agent's failure
   doesn't block the others.

5. **Honest failure is allowed.** If an agent investigates extensively
   and cannot pin a root cause, the right thing is to revert any
   exploratory edits and return an empty-diff report with the
   investigation notes. Iter 2 Z did this correctly for CNZ f202;
   that's much better than a hallucinated "fix" that wastes
   integration time.

6. **Commit on your current branch, do NOT push.** When the agent is
   in a worktree, `git reset --hard develop` makes `develop` the
   worktree's checked-out branch name AND the orchestrator's shared
   branch name. Committing in the worktree puts the commit on the
   worktree's local branch ref; the orchestrator integrates it via
   cherry-pick or by copying the file changes. Do NOT push. Phrase
   the commit step as "commit on the current branch HEAD" — not "on
   develop" — to avoid confusion about which `develop` was meant.
   The orchestrator may also choose NOT to integrate ROM-correct
   collateral changes from non-staged files; only the staged commit
   is the contract.

7. **Speculation creep: apply only the minimum-viable change.** When
   reading the ROM you may notice ROM-correct improvements in
   adjacent routines that don't affect the target frontier. These
   are tempting but risky: combined with the target fix they have
   regressed the frontier in several iters (HTZ Rexon iter 8 +
   iter 9 — the head-routine alignments were individually correct
   but slightly regressed when combined with the body fix). List
   these as "noted-but-not-applied" breadcrumbs in your report so a
   future iter can pursue them; do not bundle them into the current
   commit.

8. **Honest-failure → focused-followup pattern.** If your empty-diff
   report identifies a more upstream root cause than the assigned
   frontier (different file, different routine, different bug
   class), name it explicitly with `file:line` and a one-sentence
   fix hypothesis. The orchestrator's next iter will dispatch a
   narrowly-scoped follow-up agent that often lands in one shot
   (HTZ Rexon iter 8 → iter 9 demonstrated this).

9. **Diagnostic-extension iter every N stuck frontiers.** When two
   consecutive iters return honest-empty-diff citing the same
   recorder/diagnostic gap (sub-pixel carry, engine-side
   standing/ride truth, RAM-side value the recorder doesn't emit),
   the next iter MUST be a recorder/parser extension iter rather
   than another frontier-advance attempt. This batches the
   diagnostic work and unblocks the next 2–3 stuck frontiers at
   once (overnight iter cycle had 5 of 10 iters return empty-diff
   on the same two gaps; a single diag iter mid-cycle would have
   saved three of them).

### Skill self-improvement feedback loop

When a frontier-advancement commit touches code under
`src/main/java/com/openggf/game/sonic{1,2,3k}/` (objects, badniks,
moving solids, springs, monitors, etc.), evaluate whether the root
cause is a class of bug that could recur in any not-yet-implemented
object of the same game. If yes, append a new P-numbered entry to
`.agents/skills/s{1,2,3k}-implement-object/rom-pitfalls.md` AND the
`.claude/` mirror. Cross-apply across S2 and S3K when the ROM
convention is shared. Use the commit trailer `Skills: updated`.

The pitfall catalogue currently runs P1–P16 across S2 and S3K (S3K
catalogue tends to lag S2 by 1–2 entries — sync when you make
cross-game updates). Read those entries before dispatching any new
fix-attempt agent: a target may match an existing pattern, in which
case the diagnosis is half-done.

## Verification

Use focused commands while iterating. Examples:

```powershell
mvn -q "-Dtest=com.openggf.tests.trace.s2.TestS2SczLevelSelectTraceReplay" test -DfailIfNoTests=false
mvn -q "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay" test -DfailIfNoTests=false
mvn -q "-Dtest=com.openggf.tests.trace.s2.TestS2HtzLevelSelectTraceReplay" test -DfailIfNoTests=false
mvn -q "-Dtest=com.openggf.tests.trace.s2.TestS2OozLevelSelectTraceReplay" test -DfailIfNoTests=false
```

When touching shared infrastructure, run a broader trace gate:

```powershell
mvn -q "-Dtest=*TraceReplay" test -DfailIfNoTests=false
```

When touching shared physics/collision/player movement, include cross-game
coverage. Prefer existing focused tests first, then broaden:

```powershell
mvn -q "-Dtest=*Physics*,*Collision*,*Sensor*,*Sonic1*,*Sonic2*,*Sonic3k*" test -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false
mvn -q "-Dtest=*TraceReplay" test -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false
```

If the touched code is shared but only one game has a current trace for the
specific behavior, document the disassembly comparison for the other games and
run the closest available unit or integration tests for those games.

If Maven's quiet/silent output hides the useful failure, rerun with
`-Dmse=off`.

## Reporting

For every completed iteration, report:

- target trace and command run,
- old first failing frame and field,
- fix summary,
- disassembly evidence used,
- new first failing frame, or that the trace now passes,
- shared/non-shared behavior classification,
- cross-game verification performed for S1, S2, and S3K, including skipped
  checks with the reason,
- any tests not run and why.

If committing, keep commits logically small and follow the branch documentation
trailers in `AGENTS.md`. Do not use `--no-verify`.

## Stop Conditions

Stop and plan instead of forcing a local fix when:

- the failing frame requires several unimplemented objects or a whole zone
  subsystem,
- recorder diagnostics and parser changes are needed before root cause is
  knowable,
- the likely fix crosses shared physics/collision behavior for multiple games,
- the required change would fight the service architecture or runtime-owned
  framework stack,
- multiple agents should implement independent staged work.

The right stopping point is a clear frontier movement, a passing trace, or a
specific implementation plan with verified blockers. Do not land exploratory
hacks.
