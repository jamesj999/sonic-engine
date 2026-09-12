# Test concurrency and public audio validation

Implementation base: `040ce4809420c809fd8f16eae295160afec8e12d` (`develop`).
Development branch: `feature/ai-test-concurrency-public-audio`.
Shared validation receipt: `20260912-concurrency-public-audio`, one 40-minute
aggregate budget and one final broad attempt. Java 21 and Lua 5.4 are required;
preflight passed with `LUA_BIN=/usr/bin/lua5.4`. ROM arguments were discovered
from existing root files and verified by SHA-1, using the identities in AGENTS.md.

## Decisions

- Keep the one-fork default. `test-concurrent` and category `--workers 2` use
  two reused 3 GiB JVMs, serial Jupiter execution and per-fork temporary/native
  paths. Guards retain one JVM. No class-thread parallelism was introduced.
- Retain 145 synthetic FM scripts / 580 independent chip expectations publicly.
  Move 38 captured scripts / 152 expectations into `audio-reference`, together
  with game-derived parity payloads. Public mixed classes retain their synthetic
  assertions; only external methods are tagged.
- Preserve 95 original audio files byte-for-byte outside the checkout before
  deletion. The complete external bundle has 98 pinned members / 45,007,851 bytes:
  those files, captured FM expectations, and two manifest-referenced gameplay
  input movies. Public gameplay movies are unchanged. The public authoritative
  manifest pins every member by size and SHA-256; the reference prerequisite
  also checks all three ROM identities.
- Keep the maximum-size streaming comparator case intact under `audio-stress`.
  Move seven formerly optional local WAV snapshots to `audio-local-wave`, with
  explicit external paths and missing-input failures. These engine-generated
  snapshots are not independent reference evidence.
- Git history and unrelated gameplay trace payloads are outside this change.
  This is not a repository-wide asset-clearance certification.

## Focused evidence

Every Maven invocation used `-Dmse=off -B`, a separate target-local report directory,
and verified ROM properties. The six-class concurrency selection was
`TestNukedOpn2BitExactScripts,TestFastFmCoreTolerance,TestCompleteRunAudioCaptureStore,TestS3kOracleRequestSidecarWiring,TestMasterEndToEnd,TestVerifiedRoomEndToEnd`.

| Check | Result |
|---|---|
| Pinned-base initial serial, `-Dsurefire.forkCount=1` | 950 tests, no failures/errors/skips; 121.781 s, including production compilation |
| Pinned-base two forks, `-Dsurefire.forkCount=2` | Same 950 identities, no failures/errors/skips; 62.613 s |
| Matched warm serial control | Same 950 identities, no failures/errors/skips; 92.541 s |
| Python runner safety suite | 56 tests passed, 3.173 s |
| Public mixed-class/build focused selection, `-Ptest-concurrent`, no fixture root | 1,029 tests, no skips; two introduced integration failures and one reproduced baseline guard failure; 94.886 s |
| `-Paudio-reference -Dtest=TestAudioReferencePrerequisites`, no root | Expected explicit missing-root error; one test, no skips; 17.856 s |
| Initial complete `-Paudio-reference` with external bundle | 282 tests, 281 passed, no skips; one missing cross-referenced movie caught; 89.417 s |
| `-Paudio-stress` | One test passed, no skips; original 434,417-frame / 32 MiB child-JVM contract; 76.648 s test / 126.281 s Maven including production compilation |
| Corrected manifest/provenance and selected build guards, two workers | 43 tests passed, no skips; 18.344 s |
| `-Paudio-local-wave -Dtest=AudioRegressionTest#testMusicEhzMatchesReference`, no root | Expected explicit missing-root error; one test, no skips; 17.931 s |

Both matched warm runs compiled test sources but did not compile production sources.
The observed elapsed reduction was 32.3%, not the 48.6% suggested by the cold
serial run. A 70-second host sample observed 3,532,364 KiB summed project-JVM RSS;
it is a sampled subset observation, not a full-suite memory bound. The two-fork
experiment preserved exact test identities, including dynamic names.

The public focused failures led to explicit shared run-order declarations in the
new profiles and an updated normalization-contract destination in TraceChaser
provenance. The documentation guard failure reproduced at the unchanged base in
one test / 18.321 s: it still required `--base develop --run`, whereas current
AGENTS/CLAUDE prescribe the printed pinned base. Its expectation now follows that
existing instruction. The external reference failure identified two gameplay BK2
cross-references; both exact movies were added to the external bundle and pinned.

Reference-lane success means its existing assertions hold; some S1 run windows
remain explicitly measurement-only and report existing differences. No assertion
was changed to manufacture parity.

## Final validation and delivery

The single combined run completed at `718c3311f2ce3d2e841348e358a1f1b9292f5acc`:

```bash
LUA_BIN=/usr/bin/lua5.4 python3 tools/testing/run_categories.py \
  --base 040ce4809420c809fd8f16eae295160afec8e12d --workers 2 --max-minutes 18 --run
```

| Lane | Reports | Tests | Passed | Failures | Errors | Skips | Elapsed |
|---|---:|---:|---:|---:|---:|---:|---:|
| Ordinary, two workers | 2,513 | 19,765 | 19,728 | 12 | 12 | 13 | 273.64 s |
| Guards, one worker | 82 | 664 | 664 | 0 | 0 | 0 | 179.68 s |

All 2,511 ordinary candidate classes were selected. Candidate classes and emitted
reports are different inventories. Combined lane time was 453.32 seconds (7m33s).
This is the current breakdown, not a matched full-suite speedup claim: upstream
retired tests and this change moved deeper audio coverage out of ordinary selection.
A one-second process sample observed peak aggregate project JVM RSS of 7,332,580
KiB (about 7.0 GiB), including Maven and test children, over 431 samples, with at
most six Java processes. Two worker heaps need host headroom; the default is serial.

Two focused checks on the unchanged pinned base reproduced every red ordinary
case: placement/FBZ (27 tests, 12 failures, 80.537 s) and mod loading (13 tests,
12 errors, 20.837 s). Both ran in the main workspace with verified ROM properties.
Immediate accounting was attempted but the broad run held the shared lock; their
combined 101.373 seconds was recorded as soon as that lock was released.

- `TestObjectPlacementEncoding.commonParserPreservesDescendingFullXOrderInsideOnePlacementColumn`:
  expected `[448, 384]`, actual `[384, 448]`.
- `TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment`:
  cases 1–4 at frames 27356, 29138, 25729, 25885.
- `TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState`:
  cases 1–5 report `obj74-crossing-lost-flat-control`, target `$2360`, player
  `($2315,$96c)`, at frames 31003, 31034, 31034, 31031, 31026.
- `TestFbzCompatibilityMatrix.donatedMovementProfileCanReachTheMandatoryBossEntryWithoutSpindash`:
  cases 1–2 at frames 7343 and 29706.
- `TestS3kModZoneLifecycle.taggedIdentitySurvivesSaveReopenEditorAndRealBackwardSeek`
  and eleven `TestSampleFlappyIntegration` cases fail during level loading with
  deepest cause `IllegalArgumentException: invalid S3K level resource profile`.
  All twelve exact method identities, exception types, full message hashes and
  deepest-cause hashes match the pinned base.

Placement/FBZ attribution compares identities and concrete failure/frame signatures;
it does not claim untruncated full-diagnostic equality. All twelve mod error-message
SHA-256 hashes are `d9fd7e303e2685b9fe63d9e459146487691676d1d0e60884f027bccdda52bd46`;
the deepest-cause hash is `d40ffff0596196e255a9a5ea728f0fed529d0f1b318d3e12d4a43a6566cf9445`.
No unrelated gameplay or upstream resource-profile fix is included.

The 13 skips comprise optional audio/rewind/rendering/capture diagnostics, native
window/context availability, and the existing CPZ spin-tube route assumption.
None is an absent-ROM skip. Native rendering and those optional probes are not
claimed as covered.

A subsequent narrow upgrade check addresses Maven retaining removed resource copies
in existing build trees. Four synthetic stale sentinels survived `mvn validate`
before cleanup (0.812 s); afterward all four were removed while a synthetic sibling
was preserved (0.865 s). The lifecycle removes only retired audio resource copies
and refreshes FM expectations from current sources. The two focused public-resource
and profile guards then passed in 17.869 s. This does not change test selection;
it is verified narrowly rather than consuming a second broad attempt.

Consumed raw logs and reports were deleted after inspection; the combined run was
acknowledged.

The implementation and targeted upgrade fix were integrated without conflicts into
`develop` at `505fea4c55c411026039bd99f6b30c495db6f4c5`, after a fresh fast-forward
sync reported no further upstream commits. The main workspace retained its unrelated
untracked document and three dirty disassemblies. On that integrated commit:

```bash
mvn -Dmse=off -B -Ptest-concurrent \
  '-Dtest=TestFastFmCoreTolerance,TestBuildToolingGuard#publicAudioResourcesMustExcludeCapturedReferenceBodies' test
```

With the same verified ROM properties, this passed 141 tests / two reports with
no failures, errors or skips in 57.320 seconds, including compilation. The actual
main build tree previously held 95 obsolete audio copies; all were removed, and
its 145 synthetic FM bodies plus expected rows exactly matched current sources.
No raw build trees were copied between workspaces. Total accounted validation was
1,294.961 seconds (21m35s), including every focused/baseline check and the single
combined attempt. Remaining red suite cases are the matched baseline issues above;
this delivery is not an all-green engine-suite claim.
