# trace-green-fleet — workflow & decision record

`trace-green-fleet` (`.claude/workflows/trace-green-fleet.js`) drives failing
`*TraceReplay` tests toward green: for each failing trace it creates a dedicated
persistent worktree and runs a triage → fix → verify agent pipeline, committing
only fixes that are green-or-advanced with no same-game regression.

This file records the **decisions** that shape the workflow and the process around
it, with the rationale, so future runs (and the "next pass") follow the same
vetted approach instead of re-deriving it. It complements — does not replace —
`docs/status/trace-frontier-log.md` (per-frontier results) and the per-fix commit
messages (per-fix ROM rationale).

## Discovery decisions

1. **Single flake-filtered sweep, never N parallel per-game builds.** Three
   simultaneous full builds overwhelm the shared machine and silently drop
   results (a discovery agent that errors returns an empty list). Run ONE sweep
   over all trace classes, parse `target/surefire-reports/*TraceReplay*.txt`.
2. **Authoritative verdicts use `-Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical`.**
   The default `forkCount=4` makes the forked JVMs race to extract the
   lwjgl/glfw natives, erroring most GL-dependent trace tests with
   `UnsatisfiedLinkError` nondeterministically (25–28 of ~32 classes "flaked"
   even with nothing else running). One fork = 0 flakes (slower, but
   trustworthy). The property name matters: `pom.xml` binds `<forkCount>` to
   `${surefire.forkCount}`, so a bare **`-DforkCount` is silently ignored** and the
   run stays on four forks — `TestBuildToolingGuard` now fails any prescriptive
   doc that teaches the ignored flag. Pin `runOrder` too: Surefire's default is
   filesystem order, which differs between checkouts and makes an
   order-dependent result irreproducible.
3. **A real parity failure is an `AssertionFailedError` carrying
   `First error: frame N -- <field>`.** Classify on that, NOT on raw
   failure/error counts. Filter two recurring environmental flakes:
   `UnsatisfiedLinkError` / missing `lwjgl.dll`/`glfw.dll`, and
   `TestBundledConfigResource` (config.yaml/config.json parallel-fork
   contamination). MSE-relaxed's `total=NNNN` line is a misleading project-wide
   tally — trust the per-class `Tests run:` line.
4. **Prefer feeding a confirmed list via `args`** (discovered inline and
   flake-filtered) over the in-workflow discovery agent, to keep the human in
   the loop on scope.

## Per-trace pipeline decisions

5. **Persistent named worktree per trace**, `git worktree add -b
   bugfix/ai-trace-<game>-<zone> .worktrees/trace-<game>-<zone> develop`. Based
   on `develop`, never `master`. Worktrees survive for review.
6. **triage → fix → verify mini-pipeline**, an independent verify agent re-runs
   the result and owns the commit decision (separation of concerns).
7. **Concurrency capped at 4** worktrees despite a higher engine ceiling: the
   repo is shared with many concurrent sessions and trace builds are heavy.
8. **Success bar = green OR frontier-advanced.** Most frontiers are deep;
   "advanced" (first-error frame strictly increases) is real progress and is
   committable. `no-change` / `regressed` / `error` are not.
9. **Commit only with real source edits + green/advanced + no regression.**
   NEVER fabricate a no-op commit (e.g. doc-only churn) for an already-green or
   unchanged trace. This was the failure mode of the very first run, which
   "fixed" 6 already-green traces (all native-lib flakes) — fixed by decisions
   1–3.
10. **Regression guard = same-game green traces** (S3K has no green trace, so it
    falls back to the must-keep-green unit tests: `TestS3kAiz1SkipHeadless`,
    `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
    `TestSonic3kDecodingUtils`). Verify agents A/B (stash the change, re-run) to
    separate real regressions from pre-existing failures.

## Correctness / scope decisions (cross-game)

11. **Comparison-only invariant**: trace data is read-only diagnostic input,
    never hydrated into engine state in the per-frame loop.
12. **No zone/route/frame/"known-failing-trace" carve-outs.** Model the ROM
    state that drives the branch; cite disasm file+line.
13. **Scope rule, audited before integrating any shared-code change:** a genuine
    per-game divergence uses the smallest accurate owner from
    `docs/architecture/per-game-rule-placement.md`; a *universal* ROM correction
    goes engine-level; never branch on `gameId`. Example from this effort: the `GroundSensor.FindFloor`
    blank-extension-tile fix is universal Sonic floor physics → engine-level,
    and was empirically confirmed inert for S1/S3K by the integration sweep. The
    `LevelEventProvider.updatePrePhysics()` hook defaults to no-op so non-OOZ
    games are unaffected; the OOZ behavior is dispatched through the S2 event
    handler.

## Integration decisions

14. **Cherry-pick verified per-trace fixes onto one integration branch off
    `develop`**, then run ONE combined single-fork sweep. Commit the integration
    only if every fixed target holds its advance, no previously-green trace
    regresses, and S1 + S3K frontiers are byte-identical. (Source file-sets were
    disjoint, so only CHANGELOG/FRONTIER_LOG "conflicted"; resolved by writing
    one consolidated entry.)
15. **Do not merge into `develop` over a dirty shared checkout.** Merging
    requires a clean `develop` working tree; if another concurrent session has
    uncommitted work (especially in a file the integration also touches), do not
    clobber it — wait or coordinate. Merging a non-`master` branch into
    `develop` also requires a `README.md` release-log update per the branch
    documentation policy.

## Model-routing decision — 2026-07-28

16. **Terra-first, rather than universal Luna-first.** Discovery, ordinary
    triage, and narrow object-local fixes begin on Terra at the route/effort in
    the trace-fleet contract. `gpt-5.6-luna` remains future-only until it is an
    explicitly supported, observable worker route; it is never substituted for
    an available Terra or Sol route. Even in the disabled Luna benchmark policy,
    Luna is limited to Discovery/mechanical collection; Triage and Fix remain
    Terra-first until objective Sol escalation.
17. **Sol owns shared/deep work and escalation.** Shared runtime surfaces,
    cross-game semantics, disputed ROM evidence, and deep roots go directly to
    Sol; a Terra stall gets one Sol final attempt after at most two unsuccessful
    attempts. Verification likewise moves to Sol after Sol/shared/disputed/
    escalated work, and Sol independently repeats a Terra-detected regression.
18. **Measure accepted output first.** Primary measures are tokens per accepted
    result and tokens per trace greened. Frontier movement is secondary and
    within-trace only: it distinguishes an advanced red root from no change but
    cannot compare remaining difficulty across traces or prove acceptance.
    Missing runtime telemetry stays null rather than inferred.
19. **Benchmark results are executed records, not route plans.** Final result
    validation rejects pending stages and routes outside the selected enabled
    policy, and cross-checks stage completion, attempts, regressions, and
    frontiers. A blocked, error, or no-change run remains valid evidence even
    with no owned source changes: it retains an empty patch with its SHA-256 and
    a result tree equal to the pinned base tree. The lifecycle runs with strict
    shell error handling, so a disabled-policy preflight or invalid final result
    stops before allocation or cleanup respectively. Empty-patch/base-tree
    results are reserved for blocked, error, or no-change outcomes; green and
    advanced results require a source diff.
20. **Benchmark evidence preserves fleet semantics.** Results retain
    `advanced-with-regression` and `rejected-not-genuine` plus independent
    acceptance, genuineness, and reviewer-rejection flags. Exact guard outcomes
    and structured ROM citations make rejection, citation-completeness, token,
    and regression metrics computable. Complete patch/tree capture compares
    commits plus working edits and new owned files to the pinned base.

## Run record — 2026-06-03

- Baseline failing set: S2 ×16 (arz1/arz2/cnz1/cnz2/cpz1/cpz2/dez1/htz1/htz2/
  mcz1/mcz2/mtz1/mtz2/mtz3/ooz1/ooz2), S3K ×3 (aiz f8941/cnz f17276/mgz f4124);
  S2 green = ehz1/scz/wfz; S1 all 10 green.
- Outcome: **7 advanced + committed + integrated** (arz1 1106→1208, arz2 225→241,
  cpz2 1515→1607, htz1 5511→5647, mcz1 1085→1455, mcz2 264→3003, ooz1 563→741) on
  `feature/ai-trace-green-integration`. 2 rejected as regressions (htz2, mtz1 —
  both advanced their target but broke green EHZ1 via the Tails-CPU id-mismatch
  despawn). Rest no-change.
- Follow-ups: ForearmChild raw-`new` crash already fixed on develop
  (`createPermanentChild`), no change needed. Persistent-`Tails_interact_ID`
  modeling attempted but not landable (advances mtz1, still regresses EHZ1 at
  f1417 — engine `getLatchedSolidObjectId()` doesn't track ROM's persistent
  `interact(a0)` slot across the EHZ1 transition); needs the EHZ1 f1417 latch
  trace next.
