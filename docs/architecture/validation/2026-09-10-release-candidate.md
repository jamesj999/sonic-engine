# 0.6 candidate validation — September 10

## Candidate and environment

Validated runtime and packaged artifact: `d2f1331b30e3193699ba2b60502fd98622e12407`.
The main `develop` checkout fast-forwarded from `d9136a15f` without conflicts,
incorporating the ICZ snowboard checkpoint/stalactite, MGZ spiral animation,
and LBZ2 ending fixes. Validation used an isolated checkout; existing research
submodule changes were preserved.

Java 21.0.11, Maven 3.9.16, Lua 5.4.9, Linux x64. The native display provided
accelerated OpenGL 4.6 through Mesa 26.2.2 and an AMD Radeon RX 9070 XT.
The existing S1 REV01, S2 REV01, and locked-on S3K ROMs matched the SHA-1 and
CRC32 identities in `AGENTS.md`. Every Maven command used `-Dmse=off`.

## Completed automated gates

| Completed run | Maven tests | Failures | Errors | Skips | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Initial ordinary suite, native display available | 17,128 | 0 | 0 | 25 | 0 |
| Focused foreground-window test, native context selected | 1 | 0 | 0 | 0 | 0 |
| Complete ordinary suite, native context selected | 17,128 | 0 | 0 | 24 | 0 |
| Separate structural guards | 613 | 0 | 0 | 0 | 0 |
| Pinned trace baseline `1f61f5746` | 852 | 7 | 0 | 6 | 1 |
| Candidate trace profile | 852 | 7 | 0 | 6 | 1 |

The initial ordinary run finished at 01:05:11 BST. Skip classification rejected
one unclassified abort: `TestForegroundWindowRendering` could not create its
default null-platform EGL context. Its existing `openggf.test.gl.native=true`
option selects the available native display without changing pixel assertions.
The focused test passed; the complete run with that option finished at
01:13:19 BST and passed exact classification: 24 allowed, zero unclassified,
required, undeclared-input, or stale-policy skips. No ROM or graphics skips
remained. The release workflow now supplies that option in its ordinary test
step; the skip policy and gameplay code are unchanged.

The baseline and candidate trace runs finished at 01:05:55 and 01:17:12 BST.
Both collection commands succeeded. Exact comparison passed for **850 test
identities and 173 owned trace reports**, retaining the same failure and warning
evidence. Repeated nested-suite execution explains 852 executions versus 850
identities. Required coverage passed: 151 policy reports / 809 executions and
75 expected trace reports / 117 executions. These sets overlap; they are not
additive. The profile excludes opt-in performance and release-7 scope.

The seven retained assertion failures are:

- `TestS3kReplayReferenceClosureIntegration#replayMatchesTrace`:
  113 errors; first frame 25,589, `player_animation_id`.
- `TestS3kAizTraceReplay#replayMatchesTrace`:
  37 errors; first frame 20,713, `air`.
- `TestS1CompleteEmeraldRunChain#ghz1ToScrapBrainAcrossEverySpecialStage`.
- `TestS2CompleteEmeraldRunChain#ehz1ToDeathEggAcrossEverySpecialStage`.
- `TestS2EhzHalfpipeRoundTripChain#ehzHalfpipeRoundTrip`.
- `TestS3kSonicTailsCompleteEmeraldRunChain#aiz1ToDoomsdayCollectingEverySevenEmeralds`.
- `TestTraceRunReplayWalkerControlFlow#metadataOnlySpecialStagePlanRejectsNonContiguousStoredRows(Path)`.

Trace skips comprise four opt-in benchmarks and two absent S3K bonus round-trip
fixtures. No baseline was refreshed, frontier selected, or assertion weakened.
No Maven compilation, fork, heap, or test-error failure occurred.

## Reproduction

Resolve the existing ROM paths from the main checkout before entering an
isolated candidate tree. Do not create replacement ROM links.

```bash
validation_rom_root=$(git rev-parse --show-toplevel)
rom_args=(
  "-Dsonic1.rom.path=${validation_rom_root}/s1.gen"
  "-Dsonic2.rom.path=${validation_rom_root}/s2.gen"
  "-Ds3k.rom.path=${validation_rom_root}/s3k.gen"
)
# In the isolated candidate checkout, with a working native display:
LUA_BIN=lua5.4 mvn -Dmse=off -Dopenggf.test.gl.native=true test -B \
  "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-evidence/default-native-reports
python3 tools/testing/classify_surefire_skips.py \
  --reports target/release-evidence/default-native-reports \
  --policy tools/testing/release-skip-policy.json \
  --capabilities gl=true,s2_bk2=false,s3k_observations=false,s1_bizhawk_reference=false,audio_reference_files=false \
  --check-evidence --root . --json target/release-evidence/default-native-skips.json
LUA_BIN=lua5.4 mvn -Dmse=off -Pguards test -B "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-evidence/guard-reports
mvn -Dmse=off package -Puniversal-jar -DskipTests -B
```

For traces, the unchanged release workflow's `compare_release_traces.py begin`
and `collect` protocol ran on clean candidate and detached pinned-baseline trees,
with fresh default report directories. Each tree ran
`LUA_BIN=lua5.4 mvn -Dmse=off test -Ptrace-replay -B "${rom_args[@]}"`.
The actual Maven exit was preserved before collection. Coverage and comparison
used `assert_release_trace_coverage.py` and `compare_release_traces.py compare`
with the full candidate SHA above and baseline
`1f61f5746743938963113ea87ec561027e1569a4` explicitly required.

Release helper tests passed 50 tests. Skip-helper tests passed 19 with one
optional historical-report test skipped because its preserved `f56d4fae1`
reports were absent. Workflow YAML parsed through the packaged SnakeYAML
library; its extracted ordinary-test command passed `bash -n` and selected
native GL in exactly the intended step. Independent review confirmed the
flag leaves the graphics assertions and fail-closed skip policy intact.

## Packaged Linux application

The universal-JAR build and the release workflow's unmodified archive smoke
both passed. The latter checks the entry point, license notices, and all eight
LWJGL native payload families; archive presence does not prove platform launch.
The resulting `OpenGGF-universal.jar` is **27,654,906 bytes**, SHA-256:

`21dbbd7ea27e49aef30811c54359c6349e0c6673ac753a001ebcfeb5adf06332`

That exact JAR was launched directly with Java, without rebuilding, from a
task-owned directory outside the repository. Its configuration used absolute
paths to the existing ROMs, audio enabled, all three launch profiles' rewind
enabled, controllers disabled, and two capture encoder threads. User saves and
configuration were not used. Targeted X11 input and client-window screenshots
exercised the application; no desktop-wide capture was taken.

Observed checks:

- Master menu, all three games' title/start flows, S3K AIZ intro, S1 GHZ1,
  and S2 EHZ1 rendered successfully.
- An empty numbered S3K slot wrote `saves/s3k/slot1.json`. After process exit
  and restart, the occupied slot showed Zone 1, Sonic/Tails, and three lives;
  selecting it relaunched AIZ. This verifies initial-slot persistence, not a
  later-zone progression save or crash recovery.
- AIZ, GHZ, and HCZ accepted movement/rewind input; the live rewind presentation
  was observed. This is a smoke check, not a pixel-level rewind equivalence test.
- Holding Escape returned to the master menu. A recording remained active
  across GHZ rewind, session teardown, and S2 startup/gameplay.
- Normal Shift+O stop finalized a recording. Closing the window while a second
  recording was active also finalized it; both attached JVM runs exited zero.
- The explicit debug profile's Next Zone control loaded HCZ1 from AIZ, followed
  by movement and rewind. **The genuine AIZ2 boss/end-sequence handoff was not
  played through.**

The temporary input helper initially used zero X11 timestamps, and capture
did not start. Nonzero server timestamps and a correctly formed modifier chord
made the normal shortcut work; no application code was changed for this.
There is no separate live-capture cancel key. Forced cancellation remains
covered by the [lifecycle regressions](2026-09-09-release-lifecycle-fixes.md),
not by this ordinary UI smoke.

Both finalized Matroska recordings contain FFV1 BGRA video at 960×672 / 60 fps
and FLAC stereo audio decoding to signed 16-bit PCM at 48 kHz. Full decoding
with `ffmpeg -v error -xerror -err_detect explode` completed with exit zero and
empty error output for both streams in both files.

| Termination | Decoded frames | Samples per channel | Exact media duration | Audio peak / RMS, dBFS |
| --- | ---: | ---: | ---: | ---: |
| Normal stop | 1,268 | 1,014,400 | 21.133333 s | −12.873773 / −35.447326 |
| Window close | 2,049 | 1,639,200 | 34.150 s | −11.056612 / −30.233152 |

Frame/sample durations agree exactly. The second clip's reported video end is
1 ms earlier than audio, within its 1/1000 timebase quantization. Audio is
nonzero without full-scale samples; this does not establish listening quality,
sample equality with the producer, or hardware parity.

## Remaining sign-off

This validates the named runtime candidate and Linux JVM artifact. Human
end-to-end route QA, the genuine AIZ2 → HCZ1 handoff, and reference listening
remain open. Native executables and launch on other release platforms were not
validated here. The existing seven trace failures remain accepted baseline
debt, not passing parity claims. No release was tagged or published.

Completed logs, XML/trace evidence, JAR, screenshots, isolated configuration,
save payload, and media inspection outputs are retained in the external
`release-validation-20260910` task directory. Later runtime changes require
new candidate evidence; historical results above keep their original SHA.

## Integration verification

The workflow/evidence change `e8cd7e653` merged into `develop` without conflicts
at `0b81fbd4ea27d224db72e9b7061bd10fd626fc23`. On that integrated commit,
the complete ordinary command above passed again at 01:40:29 BST:
17,128 tests, zero failures/errors, and 24 policy-accepted skips. The separate
guard command passed at 01:42:47 BST: 613 tests, no failures/errors/skips.
These runs used report directories under
`target/release-validation-20260910-integrated/` in the main checkout.

Comparison retained all 15,999 distinct ordinary testcase identities with
identical outcomes and skip reasons, and identical console suite execution
summaries. XML testcase totals alone differed (17,125 versus 17,122): five
passing nested-test identities had different duplicate-entry multiplicities,
with no missing identity or changed console execution. All 613 guard identities
and outcomes matched exactly. Runtime, tests, fixtures, and POM bytes remained
identical to the trace/package candidate; the integration changed only the
workflow flag and documentation. Existing dirty research submodules remained
untouched. Independent review found no corrections to the gate or evidence.
