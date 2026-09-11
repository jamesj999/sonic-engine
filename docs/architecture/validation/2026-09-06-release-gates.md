# Release gate remediation and verification

Brainpipe P6 coordinates this change from `develop` at `1f61f5746743938963113ea87ec561027e1569a4`.
The earlier [assessment](../audits/2026-09-06-release-blockers.md) is historical;
this record tracks remediation, not release approval.

## Gate contract

The [release workflow](../../../.github/workflows/release.yml) classifies skips
using an exact `class#method` [policy](../../../tools/testing/release-skip-policy.json).
All input capabilities must be declared. Release requires working GL and all
three ROMs; it allows named optional measurement inputs to be absent. Unknown
skips, absent required capabilities and stale policy entries fail. The existing
CPZ spin-tube scenario limitation remains explicit; no gameplay assertion changed.

Trace validation runs direct Maven on the candidate and a separate clean worktree
at the [reviewed baseline pin](../../../tools/testing/release-trace-baseline.txt).
Each tree owns its fresh `target/` reports. Maven assertion exit 1 is collected as
evidence only: compilation, crashes, errors, incomplete execution, stale reports,
missing owners, skipped required replays and unreconciled XML/log totals fail closed.
The candidate SHA is bound to the releasing checkout; the pin must be its ancestor.

The comparator requires identical testcase identities/outcomes/messages and all
existing owned JSON report payloads, including warnings, frontiers, frame counts
and report sets. Only checkout path prefixes are normalized. Fixture or trace
source inventory changes require explicit baseline review. Even an apparent
improvement requires reviewed rebaselining; lower totals alone never pass.
This conservative gate checks existing report evidence, including bounded run-chain
mismatch summaries; it does not claim complete parity or observe unreported state.
The baseline pin is reviewed source, never automatically advanced on a failed run.

Coverage checks honor Maven directory exclusions and class-level excluded tags,
count nested JUnit report families, and require the selected trace/policy classes
to execute. Four opt-in benchmark cases and two explicitly unrecorded deferred
bonus round-trip fixtures retain their exact baseline skip identities/reasons.
Existing missing-auxiliary-schema lists are fixture coverage limitations preserved
in the comparison; any change requires review. No report or owner may be missing.
Surefire logs three identical singleton slots replays and either overwrites the
class XML or retains all three identical passing rows with a singleton root count.
The latter form normalizes to the former only when every retained row has its own
completed all-pass singleton log verdict and the XML failure counters agree.
All completed per-suite verdicts and multiplicity are compared. Partial retained
rows, changed identities, failures, skips and multi-case repeats are rejected;
empty repeated parent containers remain permitted. Ordinary tests and structural
guards remain independent required runs.
The fixture runner must expose a working display via DISPLAY or WAYLAND_DISPLAY;
the graphics tests themselves must execute, so a dummy environment value cannot
satisfy the skip gate. Optional input capability settings must match supplied inputs.

## Verification ledger

- Base ordinary: `mvn -Dmse=off test -B` with all three verified absolute root ROM
  properties, detached base worktree at `1f61f5746`: **16,687 / 0 failures /
  0 errors / 23 skips**, completed 2026-09-06 00:44:05 BST. Actual display available.
  New classifier accepts all 23 skips, with zero unknown, required or stale entries.
- Base trace: `mvn -Dmse=off test -Ptrace-replay -B` plus the three absolute ROM
  properties: **852 / 7 failures / 0 errors / 6 skips**. Base fresh guards:
  `mvn -Dmse=off -Pguards test -B`: **609 / 0 / 0 / 0**.
- Candidate `82ae855d51f249cdd0b966f1113384ce7b1d3896`: full trace **852 / 7 / 0 / 6**;
  the real comparator accepts all **850 XML testcase identities and 173 owned JSON
  reports**, including suite multiplicity, failure/skip messages, warnings and
  frontiers, unchanged against the fresh baseline. Coverage proves 151 selected
  policy classes and 75 trace classes executed (809 and 117 executions respectively).
- Candidate ordinary: **16,687 / 0 / 0 / 23**, completed 01:18:43 BST. All 15,738
  unique XML testcase identities retain their baseline outcomes. Four passing
  nested cases have different duplicate XML multiplicities; none disappeared or
  changed outcome. All 23 skips are classified; no unknown/required skips or stale
  policy entries. Candidate fresh guards: **610 / 0 / 0 / 0**, completed 01:20:53 BST;
  the extra test executes the Python gate controls. The replaced warning-policy
  guard now requires baseline comparison; all other guard outcomes stay green.
- Focused workflow guard initially exposed two stale test expectations during
  implementation; both were corrected and the completed full guard run passes
  all 97 workflow checks. Python controls: **38 comparison/collection + 20 skip**
  tests pass, including the explicitly supplied archived headless evidence.
- Workflow YAML parses; all 15 Bash steps pass `bash -n`. Isolated coverage scenarios
  reject missing/skipped required S3K tests while honoring directory/tag exclusions
  and nested execution. Policy, local links and AGENTS/CLAUDE mirror checks pass.
- Integrated verification is recorded in the final delivery evidence below;
  the local candidate results above retain their actual source commit.
- Independent behavioral controls cover changed failures/warnings/frontiers,
  missing/stale reports, ownership, skipped replays, incomplete/crashed Maven,
  source mutation and checkout-path normalization. Full outcomes follow below.

## Remaining release evidence

Freeze the release candidate after integration, execute the existing Windows,
macOS, Linux and universal-JAR artifact jobs for that SHA, and record supported
route gameplay and SMPS listening sign-off. This change does not publish a release,
claim cross-platform binary execution, or supply human approval. Bounded automated
audio oracles remain hard assertions alongside the separate listening requirement.

## Recorded fixture coverage limitations

The baseline lists below come from `TraceData.missingAdvertisedAuxSchemas()`
(`TraceData.java:648`), which compares fixture metadata with loaded fixture aux
events. They do not describe engine output. P6 review confirmed this distinction.
The comparison retains these exact lists and fixture hashes. Repairing or
recording fixtures requires a reviewed baseline update; until then these reports
cannot establish parity for the absent auxiliary categories.

- `trace/s3k_aiz1-single-bc61c7f82ef2fd87.json`: `velocity_write_per_frame`, `position_write_per_frame`, `aiz_ship_loop_per_frame`, `sonic_record_pos_per_frame`, `tails_cpu_normal_step_per_frame`, `aiz_boundary_state_per_frame`, `aiz_transition_floor_solid_per_frame`.
- `trace/s3k_aiz1-single-bd5198b6a54c0f8e.json`: `cage_state_per_frame`, `cage_execution_per_frame`, `velocity_write_per_frame`, `position_write_per_frame`, `sonic_record_pos_per_frame`, `tails_cpu_normal_step_per_frame`, `cnz_cylinder_state_per_frame`, `cnz_cylinder_execution_per_frame`.
- `trace/s3k_cnz1-single-3b6e6f180fe3b76f.json`: `cage_execution_per_frame`, `velocity_write_per_frame`, `position_write_per_frame`, `sonic_record_pos_per_frame`, `tails_cpu_normal_step_per_frame`, `cnz_cylinder_execution_per_frame`.
- `trace/s3k_mgz1-single-a5c15b2edbc7b5a5.json`: `cage_state_per_frame`, `cage_execution_per_frame`, `velocity_write_per_frame`, `position_write_per_frame`, `sonic_record_pos_per_frame`, `tails_cpu_normal_step_per_frame`, `cnz_cylinder_state_per_frame`, `cnz_cylinder_execution_per_frame`.
- `trace/s3k_slots1-single-50e675b2c254b271.json`: `cage_state_per_frame`, `cage_execution_per_frame`, `velocity_write_per_frame`, `position_write_per_frame`, `sonic_record_pos_per_frame`, `tails_cpu_normal_step_per_frame`, `cnz_cylinder_state_per_frame`, `cnz_cylinder_execution_per_frame`.

## Hosted validation prerequisite

GitHub API inspection on September 6 returned **zero available repository runners**
and **zero repository Actions variables** (`gh api repos/OpenGGF/OpenGGF/actions/runners`
and `/actions/variables`). The existing `release-fixtures` job therefore needs an
approved host and three configured ROM-path variables before hosted release
validation can run. The host also needs Java 21, Maven, Lua 5.4, Python 3, Git,
and a working GL display. No runner was registered and no ROM paths were invented.
On `develop`, manual workflow dispatch validates/builds artifacts; publication
remains restricted to manual dispatch on `master`.

Candidate and baseline logs, command arrays, XML and JSON are preserved outside
the repository under `${EVIDENCE_ROOT}/brainpipe-release-0.6-remediation/`.
Archive hashes are recorded in the adjacent `*-archive.json` manifests.

## Integrated delivery verification

Integrated commit: **1514f4c9c32f4717d0d2a45eea8ebe1a87f8181a**, on `develop`.
There were no upstream changes or merge conflicts. The existing dirty research
submodules were preserved. Ordinary/guards/package ran in the main workspace;
trace ran in an isolated worktree at that exact same integrated commit, with
fresh worktree-local output directories and the same three verified ROM paths.

| Check | Completed result |
|---|---|
| `mvn -Dmse=off test -B` plus three absolute ROM properties | 16,687 tests, 0 failures, 0 errors, 23 skips; all 15,738 unique XML identities retain baseline outcomes. Duplicate passing XML multiplicities differ for 74 cases, with no missing identities or changed outcomes. |
| Exact skip classifier, `gl=true` and four optional inputs declared absent | 23 accepted; 0 unknown, required, undeclared-input or stale-policy entries. |
| Fresh `mvn -Dmse=off -Pguards test -B` plus ROM properties | 610 tests, 0 failures/errors/skips; all 610 identities/outcomes match the development run. |
| Fresh `mvn -Dmse=off test -Ptrace-replay -B` plus ROM properties | 852 executions, 7 existing failures, 0 errors, 6 existing skips; Maven assertion exit 1 retained. |
| Pinned trace comparison | Exit 0: all 850 XML testcase identities, suite execution multiplicities and 173 owned reports unchanged from the fresh baseline, including warnings/frontiers/coverage gaps. |
| Source-derived trace coverage | 151 required policy classes / 75 trace classes execute; 809 / 117 executions respectively. |
| `mvn -Dmse=off package -Puniversal-jar -DskipTests -B` | BUILD SUCCESS; tests were already completed separately above. |
| Unmodified universal-JAR smoke block extracted from `release.yml` | Passed manifest, licence-notice and all eight native-payload classifier checks. This is packaging evidence, not execution on eight platforms. |

`target/OpenGGF-universal.jar`: **27,541,120 bytes**, SHA-256
`377ecf484ace693c5a96cfa36a698fc4c2c0bf9a93ce85a7dbcfd7f6a2e086c1`.
The external `integrated-1514f4c9c.tar.gz` evidence archive has SHA-256
`081bdbac332f0ef7dac98191c0d13cbd8b7a9301720f62dc8960724e3d6bbf05`.
It preserves commands, completed logs, XML, owned JSON, comparisons and package
metadata. The universal JAR is also retained alongside it.

The final delivery follow-up changes documentation only; runtime, tests, tooling,
POM and fixtures retain the tested integrated content. Push/cleanup completion
is reported in the delivery result after those operations succeed.

## Retained failure identities

These are unchanged baseline debt, not seven new regressions. Full failure
messages and report payloads are preserved in the evidence manifests.

- `TestS3kReplayReferenceClosureIntegration#replayMatchesTrace`.
- `runs.TestS1CompleteEmeraldRunChain#ghz1ToScrapBrainAcrossEverySpecialStage`.
- `runs.TestS2CompleteEmeraldRunChain#ehz1ToDeathEggAcrossEverySpecialStage`.
- `runs.TestS2EhzHalfpipeRoundTripChain#ehzHalfpipeRoundTrip`.
- `runs.TestS3kSonicTailsCompleteEmeraldRunChain#aiz1ToDoomsdayCollectingEverySevenEmeralds`.
- `runs.TestTraceRunReplayWalkerControlFlow#metadataOnlySpecialStagePlanRejectsNonContiguousStoredRows(Path)`.
- `s3k.TestS3kAizTraceReplay#replayMatchesTrace`.

The `metadataOnlySpecialStagePlanRejectsNonContiguousStoredRows` failure is a
bounded test-fixture issue: it supplies a two-column S2 special-stage CSV and
expects a noncontiguous-row diagnostic, but the strict parser rejects the invalid
48-column schema first. The test and parser are unchanged here. Its eventual
repair requires reviewed baseline refresh, as does any changed red evidence.

Release remains pending: choose/configure the fixture runner, complete hosted
Windows/macOS/Linux native validation for a frozen candidate, and record human
supported-route gameplay and SMPS listening sign-off. No release was published.
