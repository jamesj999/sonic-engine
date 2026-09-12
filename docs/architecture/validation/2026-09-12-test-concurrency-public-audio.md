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

Pending one combined ordinary/guard run. Consumed raw logs and reports are deleted after inspection;
this ledger retains counts, commands, timings and bounded attribution only.
