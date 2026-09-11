# Visual Trace Complete-Run Validation

Date: 2026-08-01

## Outcome

The master-title visual trace tool now treats a run manifest as one playback
session. A shared structural coordinator owns segment closure, transition
gaps, destination admission, special-stage local rows, return comparisons,
and terminal movie tails. The visual launcher and real headless chain consume
the same typed actions and admission receipts.

Trace data remains comparison-only. The run adapter never restores recorded
positions, rings, emeralds, RNG, clocks, PLC/DPLC work, or Kosinski jobs.
Recorded hardware timing retains only its bounded authority to delay matching,
prepared, production-submitted Kosinski work. PLC/DPLC and structural gap
evidence reaches trace code only through immutable diagnostics snapshots.

## Delivered contracts

- The catalog resolves a shared movie first and then a contained run-local
  `source_bk2`; escaping paths are rejected, while launch-time parser/profile/
  bounds diagnostics leave the run visible in the picker.
- One `BoundaryProbe` pins the observer that prepared a row through that row's
  publication. Semantic bonus, special-stage, stage-exit, level-advance, and
  death-restart signals cannot redirect a source publication to a destination
  comparator.
- Production level loads publish a typed cause, identity, and generation.
  Same-mode level replacements and unrelated reloads are therefore distinct.
- S1, S2, and S3K special-stage rows use a segment-local clock with their own
  lag, VBlank, synthetic lifecycle, hardware-timing, and advertised DPLC
  policy. Physical input rows and press predecessors remain BK2-owned.
- Source close, gap open, and destination open are structurally ordered.
  Schema 2 also compares the actual gap edge journal and opening ledger;
  schema 1 still proves the structural order without inventing payload.
- A schema-2 segment may inherit the exact manifest-declared production ledger
  from its preceding gap. This fixes strict validation of the committed S2
  run's pending `tails-tails` transfer 8078 without weakening ledger/ID checks.
- Return position/checkpoint, rings, emerald progression, and next-act checks
  use one comparison-only helper in both adapters.
- The terminal tail owns each remaining physical BK2 row exactly once across
  all modes. Early exit, a wrong terminal mode, or a transition step-cap
  failure is retained as `TRACE FAILED` until the picker acknowledges it.

## Focused verification

All commands ran under Maven's JDK 21 JVM.

The final coordinator/catalog/launcher/boundary/dynamic-art/picker batch
passed 122 tests with no failures or errors. It includes the actual visual
launcher's action translator—not just two coordinator fakes—and compares its
complete level-to-bonus transcript with the headless policy harness.

The following safety and transfer suites also passed:

- `TestHardwareTimingAuthorityGuard`: 20 tests.
- `TestS1S2PlcComparisonOnlyGuard`: 7 tests.
- `TestTraceRunHardwareTimingCoordinator`: 8 tests.
- `TestTraceRunDynamicArtGapComparator`: 5 tests.
- `TestTraceRunDynamicArtGapJournal`: 1 test.
- `TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`: 2 tests.

The broader standalone visual/headless parity batch also passed. It covers
prepared-input admission, advance-only and rewind presentation, PLC lifecycle,
skipped-row closure, hardware timing, comparator observers, launch/production
failure cleanup, and the ordinary `GameLoop` contract.

Independent review completed after four fix rounds covering source ownership,
dynamic-art ledger provenance, uncompared special-stage emerald policy, timing
handoff order, and load-inside-source-tail ownership. The final review reported
no blocking issues; its refreshed seven-class focused suite passed.

`TestArchitecturalSourceGuard` retains two pre-existing baseline failures:
the local `AGENTS.md` wording check and `LevelManager` at 2537 effective lines
against its 2500-line ratchet. `GameLoop` remains within its ratchet, and the
feature adds no effective growth to `LevelManager`.

## Real-run diagnostics

The committed run for each game was attempted with the locally discovered ROM.
They demonstrate strict ownership and expose existing engine/recording
choreography frontiers rather than hydrating around them:

- S1 reaches the giant-ring transition with the source comparator at
  4084/4182 after production has moved to `SPECIAL_STAGE`. The adapter refuses
  to compare special-stage production as the remaining level rows.
- S2 now passes schema-2 planning, including its non-empty DPLC opening ledger,
  and enters the first special stage. The engine exits with 3723 represented
  rows remaining, which is reported as an early-exit failure.
- S3K reaches the first AIZ transition with the comparator at 4544/4654 after
  title-card ownership replaces the source level. The still-unconsumed
  `KOS_DECOMPRESSION_QUEUE` completion at raw frame 4570 remains a named
  hardware-timing diagnostic.

These are deliberate fail-closed outcomes under the design's trace-authority
rule: the visual tool supports the complete-run lifecycle, but it does not
claim green traversal where the production engine leaves a represented source
or special stage earlier than the recorded run.

## Integration

The refreshed `develop` baseline at `5ec5badb7` ran 14,016 tests: 38 failures,
8 errors, and 31 skips. Its failures include the existing hardware-boundary,
rewind-torture, S2 special-stage cadence, architecture-ratchet, S3K object,
fixture-version, and Tornado test groups. The feature and merged-tree runs are
compared against this exact method-level baseline during final integration.

After merging that baseline into the feature, the identical command ran
14,054 tests: 26 failures, 8 errors, and 31 skips. The 38 additional tests are
the feature's new non-ROM contracts. There is no failure/error present only in
the feature result; 12 pre-existing S3K failures passed in this run. The broad
visual/headless parity, hardware-authority, S1/S2 comparison-only, rewind, and
static-state rewind batch also passed after reconciliation.

Upstream's S2 special-stage pass-identity/spill-normalization work was retained
alongside the new immutable lifecycle descriptor provenance. `CHANGELOG.md`,
`README.md`, and the append-only trace frontier keep both histories.

The feature was merged into the main-workspace `develop` as `ba731d143`,
without switching that workspace. A concurrently published S2 special-stage
finish-boundary change (`220e0bc35`) was then reconciled additively: its replay
harness and test changes compose with the shared run coordinator, and both
release-note/frontier histories were retained. The reconciliation merge is
`419824d87`.

The final merged-tree full-suite command ran 14,054 tests: 29 failures, 8
errors, and 31 skips. Compared with the pre-merge feature result, the only
additional failures were three unrelated suite-order-sensitive object/special-
stage tests (`Sonic1SpecialStageResultsScreenTest`, `TestS3kSignpostInstance`,
and `TestMhzMushroomParachuteObjectInstance`); all three classes passed in an
immediate combined focused rerun. The remaining method-level failure/error set
matches the feature run, with no visual trace, complete-run coordinator, or
PLC/DPLC/Kosinski regression. The upstream finish-boundary plus shared
coordinator focused batch also passed.

`develop` through `419824d87` was pushed successfully. The clean
`.worktrees/visual-trace-complete-runs` worktree was removed, its fully merged
local `feature/ai-visual-trace-complete-runs` branch was deleted, and worktree
metadata was pruned.
