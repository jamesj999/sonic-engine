# MZ1 Audit Sweep — Design

**Date:** 2026-04-05
**Branch (initiating):** `feature/ai-s3k-bonus-stage-framework`
**Status:** Proposed, awaiting plan

## Background

Over a ~48-hour autonomous Opus run, the agent attempted to drive MZ1 trace-replay
errors from 229 down toward zero. Errors dropped to 57, then stalled on a 1-pixel
X difference at trace frame 6100 (a `MvSonicOnPtfm2` platform-carry case). The
final 57 errors all cascade from that single pixel.

It has since become clear that **the recording contains lag frames**, meaning
divergence can occur even when engine physics are accurate. This invalidates
the premise the agent was operating under — that any non-zero error count
implied a physics bug to hunt.

**Risk:** During the run the agent made many commits specifically to reduce the
error count. Some of these are legitimate ROM-parity fixes. Others may be
hacks that bend engine behavior to match a faulty trace — including
`fix(test):` commits that adjusted test expectations *after* the physics
changes landed (a classic tell for tests bent to match bad code).

The goal of this spec is to plan an audit that separates the legitimate fixes
from the hacks, **without touching code** until the audit is complete and
reviewed.

## Goals

1. Identify every commit made during the MZ1 debugging window that touches S1
   physics, collision, object interaction, or their tests.
2. For each commit, verify the change against `docs/s1disasm/` ground truth.
3. For each change that touches shared (cross-game) code, verify it is either
   correct for S2/S3K as well, or is properly gated behind a feature flag.
4. Produce a remediation plan (KEEP / REWRITE / REVERT / INVESTIGATE) per
   commit, with proposed fix sketches — **no code applied**.

## Non-Goals

- Applying any fixes. Remediation is a separate phase after user review.
- stable-retro re-recording of MZ1 trace. Separate phase.
- Cleaning diagnostic files (`batbrain_diag*.txt`, `targetdiag-*.txt`,
  `tools/bizhawk/diag_*.lua`, stray `.txt` files in root). Separate housekeeping.
- Auditing S3K/S2 physics commits unrelated to the MZ1 thread.
- Any refactoring beyond what a specific REWRITE verdict requires.

## Constraints

- **Worktree isolation:** Any git checkout (baseline comparison) happens in an
  isolated worktree at `.worktrees/mz1-audit-baseline`. Main workspace stays
  on current branch and provides `docs/s1disasm/`, `docs/s2disasm/`,
  `docs/skdisasm/`, and ROMs.
- **Disasm citations required:** A KEEP verdict requires a specific s1disasm
  citation (subroutine name + ROM address + code excerpt). No citation → not
  KEEP.
- **Cross-game check mandatory:** Every mechanism audit includes a cross-game
  surface check. Shared-code changes without S2/S3K verification are
  automatic REWRITE (need gating).
- **BizHawk is deprecated.** No new BizHawk-based verification. stable-retro
  is the future tool but is out of scope for this audit.

## Architecture

### Phase 0 — Establish baseline

Identify the commit immediately before the MZ1 debugging window began. Walk
`git log` backwards from HEAD along the S1-physics/collision commit thread
until the last commit that is clearly *not* MZ1-driven. Candidates to
evaluate:

- `d87bc9fa0 Upgrade trace infrastructure to v2.2 with engine diagnostics`
- `d21406289 feat: implement S3K AIZCollapsingLogBridge`
- `1f3cc06fd fix: use groundHalfHeight (d3) for MvSonicOnPtfm standing Y` —
  first commit that looks MZ1-tied

Create worktree `.worktrees/mz1-audit-baseline` checked out to the selected
baseline. Record the baseline SHA in the catalog.

**Output:** `docs/architecture/designs/2026-04-05-mz1-audit-sweep/00-scope.md`
- Chosen baseline commit SHA
- Rationale
- HEAD commit SHA at audit start
- Worktree path

### Phase 1 — Catalog pass (serial)

A single agent walks `git log baseline..HEAD` and groups each physics-touching
commit into a mechanism bucket. No verdicts issued yet — this pass only
*classifies*.

Expected mechanism groups (based on commit-log review during brainstorming):

1. **MvSonicOnPtfm2 platform-carry chain** — X/Y carry delta, offset (9 vs 8),
   release-frame behavior. Includes `clearRidingObject()` carry logic.
2. **Touch response timing** — pre-update collision flags, first-frame skip,
   on-screen X-only guard (ROM `MarkObjGone`).
3. **Path exclusivity / sticky buffer** — `ExitPlatform` behavior in S1.
4. **Solid object landing accuracy** — `Solid_Landed` width (`obActWid` vs
   collision halfWidth), boundaries, offsets for MovingBlock/Platform/
   CollapsingFloor.
5. **Out-of-range execution timing** — S1 OOR check during-execution vs
   post-execution.
6. **Spike hurt Y rewind & ROM-exact spike params** — `Spik_Hurt`, sideways
   spike d3.
7. **Airborne side speed handling** — "revert airborne side speed zeroing"
   commit, interaction with `airSuperspeedPreserved`.
8. **Object-controlled velocity pre-apply** — "skip velocity pre-apply when
   player is object-controlled" commit.
9. **OPL cursor & desync recovery** — cursor diagnostics + the S1 desync
   recovery fix.
10. **Dormant spawn tracking** — OOR re-loading, `SpawnCallback`.
11. **Execution-order restructure** — `feature/s1-exec-order` attempts.
12. **Ring / phantom ring refactor** — may be unrelated to MZ1; needs triage.
13. **Collision probe / terrain fixes** — ROM parity of solidity lookups.

**Test-coupling:** Each mechanism group also lists `fix(test):` commits that
adjusted test expectations in the same window. These travel with the physics
verdict in Phase 2.

**Output:** `docs/architecture/designs/2026-04-05-mz1-audit-sweep/01-commit-catalog.md`
- Table of all commits with assigned mechanism group
- Test-coupling notes
- Any commits the cataloger couldn't classify (for user triage)

### Phase 2 — Per-mechanism audit (parallel sub-agents)

One sub-agent per mechanism group (≈10-13 agents in parallel). Each produces
one dossier at
`docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-<slug>.md`.

Dossier structure:

```markdown
# Mechanism: <name>

## Summary
One-paragraph: what this mechanism does in the ROM, what problem the commits
were trying to solve.

## Commits in scope
| Commit | Subject | Files touched | Notes |
|--------|---------|---------------|-------|

## s1disasm ground truth
- Subroutine name, ROM address
- Cited code (not paraphrased)
- Plain-english description

## Engine as-is
- File:line references
- Relevant code inline
- Plain-english description

## Divergence analysis
Per commit — match / partial-match / diverge from ROM.

## Verdicts
| Commit | Verdict | Rationale |
|--------|---------|-----------|

Verdict vocabulary:
- KEEP — matches disasm, properly scoped
- REWRITE — intent correct, implementation wrong or untethered from ROM
- REVERT — change exists only to make the trace fit; no disasm basis
- INVESTIGATE — can't determine from disasm alone, defer to stable-retro phase

## Cross-game surface check
- Files touched: <list>
- Code-path scope: S1-only (feature-flag or game-check gated) OR shared
- If shared: does behavior also match s2disasm? skdisasm? With citations.
- If shared & unverified: automatic REWRITE (must be gated)

## Coupled test changes
- fix(test): commits associated with this mechanism
- Per-test verdict: follows from KEEP, or masks a REVERT?

## Proposed fixes
For REWRITE/REVERT commits: specific diff sketch keyed to file:line. No code
applied.
```

**Dispatch rule:** Parallel sub-agents receive the catalog file + their
mechanism slug. They are read-only (Read / Grep / Glob / Bash for git history
and tests). They do not write code.

**Safeguard:** If a sub-agent's mechanism interacts with another mechanism
(e.g., touch response timing + on-screen guards), they cross-reference rather
than duplicate verdicts. The catalog assigns one owning mechanism per commit.

### Phase 3 — Synthesis (serial)

A single agent reads all dossiers and produces
`docs/architecture/designs/2026-04-05-mz1-audit-sweep/03-synthesis.md`.

Contents:
- Executive summary: N commits audited; verdict breakdown; top risks
- **Remediation queue (ordered by safety to apply):**
  1. Clear reverts (no disasm basis + no cross-game exposure)
  2. Clear rewrites (code wrong, fix specified)
  3. Cross-game hazards (shared code needing gating or verification)
  4. Test-expectation reverts coupled to the above
  5. Investigate-later bucket (deferred to stable-retro phase)
- Cross-references: mechanism interactions
- Open questions requiring user decision

Final step: post an abridged summary in-chat — top findings, recommended
remediation order, items needing user calls.

## Data Flow

```
git log baseline..HEAD
        |
        v
   [Phase 1 catalog agent]
        |
        v
 01-commit-catalog.md
        |
        +-------+-------+-------+   ...  (one per mechanism)
        |       |       |       |
        v       v       v       v
      [Phase 2 sub-agents, parallel]
        |       |       |       |
        v       v       v       v
   02-mechanism-<slug>.md (one per mechanism)
        |
        v
   [Phase 3 synthesis agent]
        |
        v
   03-synthesis.md + in-chat summary
```

## Testing Strategy

This audit is itself a documentation exercise — no code changes, so no tests
to write. However, the audit *identifies* tests that need reverting
(fix(test): commits that masked physics regressions).

The remediation plan (future phase) will include guard tests for each KEEP
verdict: headless tests that exercise the specific ROM behavior cited in the
audit, so future work can't silently undo a verified fix.

## Error Handling

- **Sub-agent fails to find disasm citation:** mechanism dossier flags the
  commit as INVESTIGATE rather than inventing one.
- **Commit can't be placed in a mechanism group:** catalog pass lists it
  separately for user triage before Phase 2 begins.
- **Baseline commit is ambiguous:** Phase 0 presents 2-3 candidates and asks
  user to pick.
- **Sub-agent discovers audit scope creep (commit touches a non-listed
  mechanism):** that commit spawns a new mechanism group; Phase 3 picks it up
  in synthesis.

## Open Questions

None for the audit process itself. All remediation decisions are deferred to
the post-audit review phase.

## Deliverables

```
docs/architecture/designs/2026-04-05-mz1-audit-sweep/
├── 00-scope.md                       # baseline SHA, HEAD SHA, worktree path
├── 01-commit-catalog.md              # all commits → mechanism groups
├── 02-mechanism-<slug>.md            # one per mechanism (≈10-13 files)
└── 03-synthesis.md                   # remediation queue + summary
```

Plus: in-chat summary of 03-synthesis when audit completes.
