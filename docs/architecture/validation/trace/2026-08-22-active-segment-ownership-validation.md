# Active-segment trace ownership validation

## Result and measured commits

The bounded trace-run segment ownership implementation passes the applicable
feature-branch gates through Task 8 Step 7. Run planning retains compact
descriptors for the whole run and opens one eager payload only for the active
segment.

The authoritative full-suite and trace-sweep comparison uses synchronized
`develop` / `origin/develop` at
`d473365ed72facfffcd36d9e07af09666b094d37` against the reconciled feature
measurement point `1a96fbdf1588564d584afb57040f749656f3cbf4`. The merge
reconciled upstream's Tails sprite-manager change and preserved both projects'
additive README and CHANGELOG entries. Both trees were source-clean when measured. Older evidence
at `d9650fd7` and feature `3a3c1fc5` is historical, not the delivery baseline.
Later whole-branch review produced enforcement-only correction commits through
`7b16dc94b`; they do not change production ownership or the measured graph.
Their focused authority, ownership, reader, migration, structural benchmark,
and policy results are recorded below. The expensive full/default/trace suite
figures remain explicitly attributable to `1a96fbdf1`, not the later head.
Independent final review and integration were controller-owned Task 8 Steps 8
and 9. Step 8 completed GREEN at `c811c9d2d`; Step 9 completed on `develop`
through `f2b27262f`.

All commands used Maven 3.9.16 on OpenJDK 21.0.11, an unset
`JAVA_TOOL_OPTIONS`, and managed `TMPDIR` / `java.io.tmpdir` paths under:

```text
$AGENT_SCRATCH_ROOT/tasks/trace-active-segment-cursor-20260822T002616Z-260779-06974cb7/
```

## ROM inputs

The trace commands used the discovered project-root files directly; no ROM was
renamed, copied, deleted, or symlinked.

| Game | File | CRC32 | SHA-1 |
|---|---|---|---|
| Sonic 1 World REV01 | `Sonic The Hedgehog (W) (REV01) [!].gen` | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| Sonic 2 World REV01 | `Sonic The Hedgehog 2 (W) (REV01) [!].gen` | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Sonic 3&K locked-on | `Sonic and Knuckles & Sonic 3 (W) [!].gen` | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

## Memory, resources, and authority

Task 7's two warmed, forced-GC forks used the fixed 1,087,200,800-byte eager
denominator.

| Fork | Descriptor graph | Maximum installed graph | Reduction | Maximum segment |
|---|---:|---:|---:|---|
| 1 | 9,253,296 | 170,550,952 | 84.31% | `s3k-59-soz_2` |
| 2 | 9,252,768 | 170,910,128 | 84.28% | `s3k-59-soz_2` |

Both descriptor samples are below 16 MiB, both installed samples are below
256 MiB, and both reductions exceed 75%. These corrected samples retain the
real catalog entry, parsed movie, complete descriptor sets, real
`TraceSessionLauncher`, exact headless harness/fixture, and installed consumer
roots linked through the owners' actual fields. The prior lower installed
figures omitted or synthesized required owners and are superseded.
Both forks still sample representative S1 and S2 special stages, including the
S2 recorded-pass binder.

`TestTraceReaderLifecycle` passes 6/6, including three observer-failure
atomicity controls. Its deterministic matrix completed 100 cycles per payload
shape and balanced exactly 1,000 opens with 1,000 closes:
plain physics 100, plain aux 100, gzip physics 100, gzip aux 100, S1 physics
200, S2 physics 200, S2 aux 100, and S3K physics 100. At correction head
`7b16dc94b`, the authority guard passes 35/35 after runtime-name, enumeration,
field/method-handle, erased relay, typed receiver, same-source helper-flow,
primitive-name collision, transformed-lookup, standard class-producer, and
loader/field-alias/helper-return branch, reachable switch-baseline,
component-type symmetry, lookup-target, and direct lookup-bind mutations were
added. Authority plus ownership passes 38/38; including the reader lifecycle
gives 44/44. The focused
migration set passes 179/179. Existing
physics/aux-derived bootstrap and row-policy authority remains quarantined; the
lease does not broaden it.

## Recorded 67-segment oracle

The `1a96fbdf1` measurement-point focused chain run reran
`TestS3kKnucklesSuperEmeraldRunChain`. Its segment-0 report is `complete: true`
and consumed all 1,653 AIZ rows:

- row 0 first mismatch: `camera_x`, expected `0x1300`, actual `0x1308`;
  `bk2_frame_offset=810` makes this BizHawk frame 811;
- row 446 first non-camera mismatch: `y_speed`, expected `-0448`, actual
  `0x0448`; BizHawk frame 1,257;
- last compared row 1,652; BizHawk frame 2,463;
- the same terminal segment-0 `giant_ring` miss;
- the same unmatched completions at raw frame 1,617 / `PRE_MAIN_LOOP` /
  `KOS_DECOMPRESSION_QUEUE#14` /
  `3c96d8b9573e86f26814cb8a605459c8fef23cc1ca5425db2fd1cc250d408d91`
  and raw frame 1,618 / `POST_OBJECTS` / `KOS_MODULE_QUEUE#9` /
  `70da89e553f70fe647a00489dec5f2612854986b444b87a2e8d81ab0f821e431`;
- zero dynamic-art gaps and zero dynamic-art failures.

## Current-main versus feature trace sweep

Both trees ran the same freshly cleared all-game command with the verified ROM
properties:

```bash
mvn -Ptrace-replay -Dmse=off -Dsurefire.runOrder=alphabetical \
  -Dsonic1.rom.path="$s1" -Dsonic2.rom.path="$s2" \
  -Ds3k.rom.path="$s3k" test
```

| Tree | Maven total | Failures | Errors | Skips | XML classes |
|---|---:|---:|---:|---:|---:|
| current main `d473365ed` | 811 | 10 | 0 | 5 | 159 |
| feature `1a96fbdf1` | 840 | 10 | 0 | 6 | 165 |

Both runs reached explicit `BUILD FAILURE` terminal markers and their fresh
Surefire and trace-report trees were copied immediately. The exact ten
`kind + class#method` identities are shared; neither side has an identity the
other lacks. Seven complete XML payloads are raw-identical. The three chain
messages and every non-stack diagnostic/root-cause line are identical; their
only body differences are source-line numbers changed by the implementation.
Raw payloads and the explicit diff classification are preserved in
`task8-main-d473365-vs-feature-1a96fbdf-trace-comparison.json`.

The two Sonic 1 visual methods are green in both current sweeps. They were real
feature regressions when Task 8 first measured `3a3c1fc5`, not established
fleet reds: both aborted after destination segment 2 with
`visual run aborted after a replay failure: run structural comparison has no
diagnostic sink`, rooted at `VisualRunReplayHarness.java:1133`. Task 4 reopened
production ownership and fixed the retained bridge diagnostics in `25d4a41b7`
with coverage in `c8cb56808`. Current main passes the same methods as an eager-
ownership control; the reconciled feature contains those two fixes. The earlier
report's contrary classification is superseded.

## Baseline-green completion and starvation proof

Class-set equality was not treated as a completion oracle. The synchronized
main sweep contains 796 passing testcase executions (794 unique identities).
Every baseline-green replay identity is green on feature. The one
baseline-green identity absent from feature is the non-replay eager-plan unit
`descriptorPlanMatchesEagerPlanSummaries(Path)`, retired with its API.

The machine accounting
`task8-main-d473365-vs-feature-1a96fbdf-trace-green-completion.json` records 82
actual replay completions:

| Family | Methods/classes | Completion oracle |
|---|---:|---|
| `AbstractTraceReplayTest` | 46 | fresh `total_frames` equals independently computed expected replay rows/start policy |
| S1 credits demos | 8 | fixed `min(trace.frameCount, DEMO_TIMER)` loop returned normally with no frontier property |
| S1 special stages | 7 | full-frame loop plus terminal `exit_state_at_end=true` |
| S2 special stages | 8 | exact finish observation, one terminal pass, no remaining passes |
| bounded prefix/round-trip chains | 9 | named destination assertion plus `complete` segment reports |
| visual runs | 3 | exact cursor/segment/result assertions |
| S3K Slots bonus | 1 | exact semantic-prefix close plus 5,259-frame report |

All 56 baseline/feature reports exposing `total_frames` have equal counts.
Shared report filenames masked nine class-specific counts, so those feature
classes were rerun alone and preserved. All nine passed and matched expected:
5,598; 8,060; 3,420; 6,613; 5,837; 3,710; 3,377; 2,903; and 5,852. The
accounting has no unresolved replay-completion issue. Three `complete:false`
segment files belong only to known-red full-chain methods and are explicitly
excluded from the baseline-green set.

## Default-suite comparison

Current main and feature ran the same clean-environment command and preserved
the complete XML tree before any other Maven run:

```bash
mvn -Dmse=off test
```

| Tree | Tests | Failures | Errors | Skips | Unique red identities |
|---|---:|---:|---:|---:|---:|
| current main `d473365ed` | 15,299 | 55 | 81 | 26 | 136 |
| feature rerun `1a96fbdf1` | 15,330 | 55 | 65 | 26 | 120 |

The rerun has 119 shared red identities, 17 baseline-only identities, and one
raw feature-only ICZ identity. Of the shared cases, 116 complete XML payloads
are raw-identical. Two are identical after normalizing only the demonstrated
per-JVM `SeamlessLevelTransitionRequest@<hex>` identity hash; invocation,
arguments, interaction count, and source lines remain equal. The remaining
case has the same `NullPointerException: phase` message and root detail; only a
source line moved from 2763 to 2963. Raw XML and comparison are preserved in
`task8-main-d473365-vs-feature-1a96fbdf-rerun-full-suite-comparison.json`.

The raw ICZ failure was not dismissed because a solo run passed. Forcing its
same-fork predecessor `TestS3kIcz1SnowboardIntroHeadless` before
`TestS3kIczCrushingColumnObject` reproduced the exact expected 1791 / actual
1774 failure on synchronized main and feature. The first feature full run's raw
MGZ-only registry failure likewise reproduced on both trees when
`TestMhzPollenLevelInit` preceded it. Neither predecessor/target pair has a
feature diff. These are demonstrated upstream singleton/order leaks, not
feature-attributable regressions. Attributable new/worsened identities are
zero; this report does not claim raw identity parity.

## Final focused acceptance

After the full suites and current trace comparison, feature reran the four
prescribed groups with fresh reports:

| Group | Result | Exact red classification |
|---|---|---|
| core catalog/ownership/timing | 588 tests, 1 failure | existing S2 `started_at_input_sample` omission |
| S1/S2/S3K special-stage traces | 32 tests, 7 errors | existing Sonic/Tails SS8-14 unmatched completions |
| exact headless chains | 16 tests, 4 failures, 1 error, 2 skips | same five exact chain/oracle identities and messages |
| visual/complete-audio | 20 tests, 1 failure | only the S2 omission; both S1 visuals green |

The headless group also supplied the fresh 67-segment oracle above.

## Final integration verification

After a final fetch, `origin/develop` remained exactly
`d473365ed72facfffcd36d9e07af09666b094d37`; the required fast-forward pull was
already up to date. Final feature head `8c2487848` completed the exact
`mvn -Dmse=off test` suite at 15,333 tests / 54 failures / 65 errors / 26
skips. Merge commit `d3b62c23a` completed the same suite at 15,333 / 54 / 75 /
26. Against the preserved current-main baseline (15,299 / 55 / 81 / 26), the
merged tree has 128 shared red identities, eight baseline-only identities, and
one raw merged-only identity.

The merged-only identity is
`TestMhzMushroomParachuteObjectInstance#fallingPlayerInGrabWindowIsCarriedAtRomOffsetAndParachuteStartsFalling`.
Its expected 5378 / actual 5408 payload is byte-identical to an earlier
main-only full-suite failure, neither its source nor test changes in this
feature, and the merged class passes 12/12 in isolation. The ten-error swing
between final feature and merged runs is the established order-dependent S3K
decoder ROM-property set already present in the current-main baseline. The
only three shared message differences are the known S2 lag-table first-bucket
selection and two Mockito identity hashes. No baseline-passing failure or
baseline red worsening is attributable to the merge.

Fresh merged focused gates passed: authority/ownership/reader 44/44,
migration/lifecycle 179/179, exact installed-root structure 1/1, and the two
hash-verified Sonic 1 visual bridge routes 2/2. The complete logs, fresh XML,
and three-way identity comparison are preserved under the managed task root
`trace-active-segment-final-integration-20260822T160522Z-1236228-7a08fffb`.

The final `ci-push` range check exposed a shell-scoping defect in the policy
hook: a nested content validator overwrote the caller's saved `IFS`, causing a
true two-parent merge to be treated as a trailer-bearing ordinary commit.
Commit `f2b27262f` fixes that behavior and adds a real temporary-repository
merge regression. The focused test passed 1/1, the complete tooling and
architecture policy matrix passed 157/157, and the actual delivery range then
passed `ci-push`. `develop` was pushed to `origin`; the clean, fully merged
feature worktree and local feature branch were removed and worktree metadata
was pruned. Its ignored SDD working reports were archived under the controller
scratch root before removal.

## Conclusion

Every gate applicable through Task 8 Step 7 passed at the reconciled
`1a96fbdf1` measurement point, and the subsequent enforcement corrections
through `7b16dc94b` pass their focused acceptance matrix. The design is
implemented and feature-branch validated. This conclusion
includes the controller-owned independent review, which found no Critical,
Important, or Minor issues and returned GREEN spec and code-quality verdicts
after a fresh 44/44 focused gate. The merge and post-merge comparison are also
complete with zero attributable regression. `develop` was pushed and the
required feature-worktree and local-branch cleanup completed.
