# Cross-Branch Red-Suite Remediation Design

**Date:** 2026-07-20
**Status:** Approved
**Target branches:** `develop`, then `next`

## Objective

Restore green in-scope test suites on both `develop` and `next` without duplicating shared fixes, weakening ROM-parity assertions, or treating unfinished Sonic & Knuckles zone implementations as release blockers.

The measured union contains 179 red `class#method` identities: 36 on `develop`, 170 on merged `next`, and 27 shared by both. The current scope excludes 96 gameplay, trace, and zone-evidence-tooling reds owned solely by unfinished Sonic & Knuckles zones (including MHZ, FBZ, SOZ, and later S&K content). Cross-cutting architecture, rewind, service-ownership, and singleton-closure guards remain in scope even when their diagnostics mention unfinished S&K objects or FBZ-named tooling. This leaves 83 unique in-scope reds: all 36 `develop` reds plus 47 additional `next`-only reds.

After the 2026-07-21 integration of `origin/develop` at `00498f16e`, the same two already-catalogued rewind inventory methods expose eight additional CNZ end-boss object-class gaps: five no-probe constructors and three parent-dependent children. These are nested diagnostic identities, not additional Surefire `class#method` identities, so the historical `179 / 36 / 170 / 27 / 83` method accounting and branch ownership do not change. CNZ is in scope; the eight classes require executable graph or isolated-probe evidence and may not be converted into baseline debt merely to restore aggregate totals.

The later `origin/develop` integration at `6af04f87e` likewise changes diagnostics inside three existing guard identities without adding method rows. `TestObjectPhysicsStandardizationGuard#productionObjectPhysicsStandardizationHasNoUnapprovedViolations` now reports the always-multi-region `CnzEndBossInstance`, `CnzEndBossArmChild`, and `CnzEndBossMagnetChild` because they publish `getMultiTouchRegions()` without declaring a canonical touch-response profile. `TestArchitecturalSourceGuard#rootDispatchMethodsDoNotGrowBeyondCurrentBudgets` reports `GameLoop#doExitBonusStage` at `167 > 142` and `GameLoop#enterBonusStage` at `119 > 86`; `releaseCriticalLargeClassesDoNotGrowWithoutExtraction` reports `GameLoop` at `3024 > 3005`. These remain in scope, retain the frozen method accounting, and must be resolved through explicit profile adoption and behavior-neutral bonus-transition extraction rather than guard baselines or budget increases.

## Scope

### In scope

- Every current `develop` red.
- Every `next`-only red outside unfinished S&K-zone gameplay and trace coverage.
- Shared physics, collision, rendering, services, rewind, object-graph, Mod API, pattern-window, and architecture-guard failures.
- Explicit baseline entries for intentional incomplete-object debt, with itemized justification.
- Documentation required by branch policy and trace/frontier policy when a measured frontier changes.

### Out of scope

- Gameplay parity, trace replay, and zone-evidence tooling owned solely by unfinished S&K zones, including MHZ, FBZ, SOZ, and later S&K content.
- Implementing missing S&K objects, bosses, events, or route completion merely to turn those zone tests green.
- Disabling tests, broad Maven exclusions, tolerance inflation, trace-to-engine state hydration, or zone/route/frame carve-outs.

The excluded test identities remain catalogued with an owner and reason. They are not deleted or annotated with `@Disabled`.

## Branch Strategy

Use forward-fix waves.

1. Fix shared and `develop`-only reds on `develop`.
2. Require the `develop` in-scope suite to pass twice consecutively.
3. Merge the resulting `develop` changes into `next`.
4. Re-inventory `next`; remove reds already eliminated by the forward merge.
5. Fix only the remaining `next`-specific reds on `next`.

Each target uses an isolated `bugfix/ai-*` implementation branch. Shared fixes are never implemented separately on both branches. A fix discovered on `next` that belongs to shared code is moved to `develop` first and then merged forward. This execution does not publish PRs; if publication is requested, the develop and next PRs are finished in separate sessions so each session publishes from one branch.

## Failure Triage Model

Every red is rerun alone before production code changes.

1. **Passes alone, fails in a batch:** classify as leaked static, singleton, session, registry, filesystem, or test-order state. Add a focused isolation test and repair lifecycle ownership.
2. **Fails alone in a guard:** fix the production violation. Change a baseline only when the reported state is intentional debt and the baseline row states why.
3. **Fails alone in gameplay:** identify the owning ROM routine and repair the narrowest accurate owner: object, profile, provider, registry, or typed `GameRules` record.
4. **Fails during fixture setup:** repair the fixture only after proving production behavior is correct. Do not make production paths accept invalid construction merely to satisfy a test.
5. **Fails after a shared-runtime change:** run focused S1, S2, and S3K regression coverage before merging.

Parameterized tests count as separate red identities. The inventory records `branch`, `class#method`, failure/error, isolated result, owner, wave, status, and disposition.

## Remediation Waves

### `develop`

#### D1: Rewind and architecture integrity — 15 current reds

- Reconcile rewind field disposition, transient policy, parent-dependent graph, and tail inventories.
- Repair badnik child graph recreation and exact parent/slot relinking.
- Resolve source-size, raw-access, typed-rule-size, and zone-event architecture guards through extraction or narrow production fixes.
- Baseline only deliberate incomplete S&K object debt.

#### D2: Shared physics and collision — 9 current reds

- Restore full-tile floor regression behavior and previous-angle preservation.
- Correct zero-distance solid motion without changing nonzero correction.
- Resolve forced-spin animation, top-solid profile, subpixel player participation, and CNZ release-state failures.
- Verify shared changes against representative S1/S2/S3K tests.

#### D3: S3-era gameplay and rendering — 12 current reds

- Repair AIZ priority handoff, ICZ palette restoration, CNZ boss mapping, and results transition state.
- Repair MGZ miniboss state/camera behavior, drilling Robotnik draw ordering, boss music/touch behavior, and quake cadence.
- Keep fixes ROM-driven and owned by the smallest relevant object/event/profile surface.

### `next`

Recalculate counts after merging green `develop`. The current upper bound is 47 additional in-scope reds.

#### N1: Mod API and SDK compatibility — up to 20 current reds

- Reconcile published Mod API signatures and annotations intentionally; preserve documented compatibility constructors.
- Repair sample-mod level loading, pattern-window allocation, mod-zone runtime/rewind setup, and SDK documentation generation.
- Treat API removals as breaking changes requiring an explicit version decision, never a silent signature-baseline rewrite.

#### N2: Rewind and runtime registration — up to 10 current reds

- Resolve null snapshots, duplicate registrations, compact-policy reachability, and nested object-graph restoration.
- Preserve identity-table and comparison-only trace invariants.

#### N3: Architecture and resource ownership — up to 7 current reds

- Remove forbidden singleton access and reconcile S3K object-set inventories.
- Repair pattern-range and service ownership at their registries rather than weakening guards.

#### N4: Remaining S3-era gameplay and rendering — up to 10 current reds

- Address remaining MGZ and isolated AIZ/CNZ/HCZ/ICZ/Pachinko/rendering failures.
- Exclude unfinished S&K-zone gameplay and trace cases from this wave while retaining their catalogue entries.

## Test Gates

Each PR must pass:

1. The exact targeted `Class#method` tests in isolation.
2. The owning package or subsystem batch.
3. Relevant cross-game regression tests for shared code.
4. Architecture, rewind, and comparison-only guards affected by the change.
5. The branch's complete in-scope suite.

A wave is complete only after the complete in-scope suite passes twice consecutively with identical red/green accounting. The unfiltered full suite is also run and its remaining failures must match only the explicit unfinished-S&K exclusion catalogue.

## Delivery and Integration

- Commit each coherent root-cause fix separately with required documentation trailers.
- Update `CHANGELOG.md` for engine behavior fixes unless the commit records an allowed explicit justification.
- Update `docs/status/trace-frontier-log.md` only when a trace frontier moves, regresses, or is used to select work.
- Merge completed `develop` waves before beginning `next`-only implementation.
- Rebase the red inventory after every PR; do not continue working from stale aggregate counts.

## Success Criteria

- `develop` has zero in-scope reds in two consecutive runs.
- After forward merge, `next` has zero in-scope reds in two consecutive runs.
- The only remaining full-suite reds are exact identities in the unfinished-S&K exclusion catalogue.
- No tests are disabled and no trace comparison thresholds or engine-state hydration paths are added.
- Shared fixes live on `develop`; `next` contains only forward-merged shared fixes plus genuinely `next`-specific remediation.

## Risks and Controls

- **Suite-order contamination:** require isolated reproduction before assigning a production owner.
- **Baseline masking:** require an itemized reason and focused graph/session coverage before adding baseline debt.
- **Cross-branch drift:** prohibit duplicate shared fixes and merge `develop` forward between phases.
- **Large failure clusters:** split by root cause, not by test class count; rerun the inventory after each fix.
- **Incomplete S&K leakage:** keep explicit exclusions while continuing to run cross-cutting guards against those classes.
