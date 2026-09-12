# Java normalisation: validation delay and handoff

Recorded 12 September 2026, approximately 16:28 BST, at the user's request to
stop extending the testing exercise and explain the delay for another agent.

Subsequent delivery request: the user authorized committing the remaining
documentation, integrating `origin/develop`, and pushing. The implementation
plan has been reconciled with the completed evidence below. The optional test
fixes remain outside this delivery. The working-state and next-step descriptions
below record the earlier handoff, rather than the final Git state.

## What went wrong

The implementation was substantially finished before the long validation tail.
The final production changes were committed at **15:40:20 BST**. Validation then
occupied roughly **48 more minutes**, on top of focused tests during implementation.
The user is right that this was disproportionate and poorly communicated.

There were two causes: the repository's full ordinary selection is expensive,
and the agent handled its prerequisites and failure triage inefficiently.
The repository instructions required the change-based runner against the original
base, and its path policy selected all categories. That explains one full run;
it does not excuse starting it without a preflight, repeatedly waiting without
an explicit stopping rule, or treating unrelated failures as more delivery work.

Specific agent mistakes:

1. Started the full runner inside the sandbox even though native macOS graphics
   had already caused trouble during preview verification. It stalled in GLFW /
   macOS service initialization. The identified test JVM had to be terminated.
2. Did not preflight Lua and PowerShell. The first guard run completed with two
   missing-executable errors. Both tools were already installed under `/private/tmp`.
3. Missed an old cache test's reflection of the extracted `inFlight` field before
   the full run. It was fixed with the public `isGenerationRunning()` assertion.
4. Held all tracked-file edits until the entire runner ended because the runner
   fingerprints the tree. That is correct evidence handling, but left the agent
   serially waiting on lengthy unrelated tests instead of preparing the bounded
   comparison earlier in a separate build directory.
5. Let failure triage expand into donor test setup and graphics helper fixes.
   These are small, useful corrections, but they were not the user's main goal.
   They were verified in an export and have **not** been applied to the main tree.
6. Gave repetitive progress updates without promptly explaining that the full
   selection was over 20,000 tests and would consume tens of minutes.
7. Only established an explicit stop rule after the user complained. There
   should have been one upfront: one completed required selection, focused
   regression attribution, then deliver known unrelated failures as limitations.

## Measured timeline

All times below are BST on 12 September 2026.

| Time | Event |
| --- | --- |
| 15:10:54 | First implementation commit, debug shortcut ownership |
| 15:23–15:26 | Main refactor commits, including shields and deferred rings |
| 15:40:15–15:40:20 | Getter-order correction, diagnostic pilot, queue reservation reconciliation committed |
| 15:40:30 | First full category run began at `fde0567b7a` |
| 15:43:50 | Ordinary lane stalled in native graphics initialization |
| approximately 15:45 | Stalled test JVM terminated; runner continued to guards |
| approximately 15:53 | First run finished red: incomplete ordinary lane; guards missing Lua and PowerShell |
| 15:54:27 | Cache warmup test correction committed as `de893f0c07` |
| 15:54:40 | Second full category run began with native access and tool paths |
| approximately 16:18 | Ordinary lane completed: 20,329 tests; guards started |
| during guards | Pinned baseline and current-tree focused trace/FBZ comparison completed in an independent source export |
| 16:26:12 | Optional donor/GL test setup verification completed: 85 tests green |
| approximately 16:28 | Guards completed: 656 tests green; user requested this handoff |

The second ordinary lane consumed roughly 24 minutes. The second guards lane
consumed roughly 9–10 minutes. The `TestBuildToolingGuard` class alone took
**298.8 seconds for 113 tests**, exercising Git policy fixtures. Those temporary
Git repositories were under the runner's `target/.../guards-tmp`; they did not
modify or push the user's branch and were cleaned by the runner.

The following long ordinary classes were recoverable from retained rolling logs
(the table is not a complete timing inventory; earlier log portions rotate):

| Class | Reported duration |
| --- | --- |
| com.openggf.tools.audio.parity.s2.TestS2PublishedRequestWindows | 163.7 s |
| com.openggf.tools.audio.parity.s2.TestS2RequestAwareOracleRawStream | 156.8 s |
| com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator | 140.2 s |
| com.openggf.tests.TestFbzCompatibilityMatrix | 119.7 s |
| com.openggf.tests.TestModApiHookPolicy | 65.03 s |
| com.openggf.tools.audio.parity.s2.TestS2RequestWindowFixture | 34.78 s |
| com.openggf.net.TestDirectConnectEndToEnd | 33.71 s |
| com.openggf.tools.audio.parity.s3k.TestS3kOracleRequestSidecarWiring | 32.23 s |
| com.openggf.tools.audio.parity.TestS1RunWindowAudioDriverOracle | 18.38 s |
| com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore | 15.98 s |

## Repository state at the handoff (historical)

- Main workspace remains on **develop**, HEAD **`de893f0c07`**.
- Original integration base is **`3e56cfb246235ac599ba813f1048508356856a76`**.
- All planned production boundaries are implemented and committed. Item 11 is
  deliberately the single diagnostic pilot plus follow/history/AIZ ownership
  assessment described in the approved plan, not a general controller rewrite.
- Fifteen local commits cover the work. No push or release was performed.
- Uncommitted pre-existing-in-this-task documentation edits remain in
  `CHANGELOG.0.7.md`, `docs/architecture/engine-map.md`, and the normalisation plan.
  This handoff is an additional uncommitted document.
- Main source/test files are otherwise clean. The optional three test-setup
  corrections described below exist **only in the comparison export**.
- No ROM files, aliases, disassembly trees, or submodules were changed.
- **No validation process remains running. Do not restart the full suite just
  because an earlier commentary said it was still running.**

## Completed evidence — do not rerun it blindly

### Full category selection

Command at `de893f0c07` plus documentation-only working changes:

```sh
PATH="/private/tmp/openggf-release-pwsh:$PATH" \
LUA_BIN=/private/tmp/openggf-slicer-lua/lua-5.4.8/src/lua \
python3 tools/testing/run_categories.py --base 3e56cfb246 --run
```

It ran with approved native graphics access. Run ID:
`20260912T145440Z-c573b892`.

- Ordinary: **20,329 tests, 27 failures, 8 errors, 97 skips**. This is **not green**.
- Guards: **656 tests, 0 failures, 0 errors, 0 skips**.
- The four mandatory S3K bootstrap/loading checks and Mod API signature checks
  passed without skips.
- Skips were inspected: optional benchmarks/captures, missing references,
  disabled editor cases, and legacy ROM-filename assumptions. Do not call the
  skipped cases passing.

Evidence remains under `target/category-tests/20260912T145440Z-c573b892/`:
`results.json`, `plan.json`, exact lane command JSON, and bounded logs. Raw XML
and temporary directories were intentionally cleaned by the runner. The root
`target/java-normalisation-category-run.log` contains the lane summaries.

The 35 ordinary failures/errors group into:

- FBZ act-2 route and compatibility-matrix cases;
- missing literal `s3k.gen` donor setup (two Tails donation tests and one HUD test);
- legacy GL-context helpers versus 4.1 shaders (data-select capture and trace boot dimensions);
- macOS-incompatible shell/base64/secure-directory assumptions in audio and sample builds;
- sample/scaffolder packaging prerequisites;
- one `TestModApiHookPolicy` fixture assertion.

Not every unrelated failure was individually baseline-tested. Do not claim that
all 35 have been proven pre-existing. The exact failure inventory and messages
are in `results.json` and `target/java-normalisation-ordinary-failures.json`.

### Matched route comparison

An independent source export was created at
`/private/tmp/openggf-normalisation-baseline-3e56cfb246`. It first ran the pinned
base, then was replaced with committed `de893f0c07` source and rebuilt with
`mvn clean ... test`. It had its own target directory; no build trees were copied
or shared and the main workspace branch never changed.

Both measurements selected:

```text
mvn -Dmse=off -Ptrace-replay
-Dtest=TestS1Mz1LostRingCollectionOrderRegression,TestS1Sbz2CompleteRunTraceReplay,TestS3kAizTraceReplay,TestS3kHczZoneSliceTraceReplay,TestFbzAct2RouteHeadless
-Dsonic1.rom.path=<verified absolute S1 REV01 path>
-Dsonic2.rom.path=<verified absolute S2 REV01 path>
-Ds3k.rom.path=<verified absolute locked-on S3K path>
test
```

Both finished **27 tests, 5 failures, 0 errors, 0 skips**. All five failure
messages were **byte-for-byte identical**:

| Check | Before and after |
| --- | --- |
| S1 MZ lost-ring ordering | All six tests pass |
| S1 SBZ2 complete run | Pass |
| AIZ → HCZ replay | 59 errors; first frame 5497, `camera_x`, expected `$0010`, actual `$0012` |
| AIZ camera-lock assertions | Same two fire-reveal/reload assertion failures |
| HCZ complete replay | 4571 errors; first frame 9482, `air`, expected 1, actual 0 |
| FBZ native act-2 route | Same `obj74-crossing-lost-flat-control` at frame 31034 |

This establishes no regression in these measured checks, not full route parity.
Older log numbers (37 AIZ errors or two HCZ ring errors) are not this baseline.
The agent initially quoted those older entries, then correctly replaced them
with a direct paired measurement. Trace payloads/tolerances/hydration were untouched.

Authoritative small summaries:

- `target/java-normalisation-baseline-failures.json`
- `target/java-normalisation-paired-results.json`
- `target/java-normalisation-comparison-command.json`
- `target/java-normalisation-current-traces.log`

The export has since run optional test-setup checks without a clean. Do not
aggregate its entire current `surefire-reports` directory: it contains reports
from more than one command. Use the summaries above and exact selected classes.

### Other completed targeted checks

- Initial debug/loop: 87 green, no skips.
- Correction run covering debug integration, shields and ring queue: 120 green,
  no skips.
- Final queue reservation/diagnostic regression run: 36 green, no skips.
- Actual shared S1/S2 preview capture succeeded in hidden engine-compatible GL4.1
  contexts: both 320×224; images inspected. Logs and images are under
  `target/java-normalisation-preview-s1.*` and `...-s2.*`.
- Final independent read-only review found no motion arithmetic, diagnostic
  sampling/sentinel/formatting, rewind ownership, or gameplay-authority regression.

## Optional test fixes prepared but not applied

These were verified in the export at `de893f0c07` plus exact edits:
**85 tests, zero failures/errors/skips**, completed 16:26:12 BST.

1. `src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java`:
   configure `SONIC_3K_ROM` from `RomTestUtils.ensureSonic3kRomAvailable()` before
   initializing the donor provider.
2. `src/test/java/com/openggf/game/sonic2/TestSonic2LivesHudDonation.java`:
   configure the same donor ROM path instead of relying on `s3k.gen`.
3. `src/test/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectVisualCapture.java`:
   request GL4.1 core/forward-compatible context, retain the shader projection
   matrix, remove legacy fixed-function matrix calls, add explicit GLFW imports.

Exact edited files are in the export at those relative paths. Verification log:
`<export>/target/normalisation-test-prerequisites.log`.
No production change was involved. These corrections should not trigger another
full-suite run merely to restate unchanged production evidence; decide their
separate delivery scope explicitly.

## Follow-up scope at the handoff

The user's immediate request is this explanation, **not permission to resume an
hour of tests**. Do not start new validation automatically.

If asked to finish delivery, review the existing source commits, reconcile the
three pending documentation edits with the completed results above, decide whether
to include the already-verified optional test fixes, and commit the relevant
artifacts with the seven required trailers. Do not switch the main branch or
push without a request. The draft `target/java-normalisation-final-plan.md` was
prepared before this handoff; it is **not authoritative** and contains a guard
placeholder plus prose assuming the optional fixes were applied. Do not copy it
blindly or claim the goal was fully delivered.

If asked to improve the workflow, investigate the category policy and slow-test
classification using the evidence above. Possible work includes separating
long artifact/oracle integrations from short ordinary feedback, preflighting
native/runtime prerequisites, reporting an estimated cost before broad selection,
and defining a single broad-run stopping rule. That is a separate change requiring
its own concrete scope; do not weaken guards or hide failures to make the output green.
