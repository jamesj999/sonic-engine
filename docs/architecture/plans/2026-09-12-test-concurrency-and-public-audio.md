# Test concurrency and public audio follow-up

The follow-up was implemented from pinned base
`040ce4809420c809fd8f16eae295160afec8e12d` in
`feature/ai-test-concurrency-public-audio`. The original source audit used
`4ffc035dd`; earlier performance work is recorded in the
[five-target ledger](../validation/2026-09-12-test-efficiency-five.md).

## Delivery scope and validation

Keep the single-worker default, add `test-concurrent` / category `--workers 2`,
and retain serial Jupiter execution with per-fork temporary directories. Keep
guards serial. Separate independently pinned external game captures from public
synthetic tests; preserve the maximum streaming/memory test in `audio-stress`.
The former optional local WAV comparisons have their own `audio-local-wave`
lane because engine-generated snapshots are not independent reference evidence.
Public fixture manifests retain identity and hashes; raw payloads stay external.
Do not rewrite Git history.

The shared task `20260912-concurrency-public-audio` has one 40-minute aggregate
validation budget and one final broad attempt. Focused validation covers a matched
serial/two-fork experiment, public mixed-class selection, explicit missing-fixture
failure, external reference assertions and the retained stress case. Reserve about
15 minutes for the final full ordinary selection plus separate guards; inspect
identities, failures and skips, and attribute failures narrowly against the pinned
base. The one-broad-attempt rule supersedes repeated full runs per integration step.
No public-suite speedup claim includes cases moved into explicit deeper lanes.
Completed checks and baseline failure attribution are in the
[validation ledger](../validation/2026-09-12-test-concurrency-public-audio.md).

The representative unchanged-base experiment ran six classes and the same 950
identities with zero failures/errors/skips. Warm serial Maven took 92.541 seconds;
two forks took 62.613 seconds (32.3% less elapsed time). Both compiled test sources;
the initial 121.781-second serial run also compiled production sources and is not
the matched timing baseline. A 70-second host sample observed 3,532,364 KiB summed
RSS for matching project JVMs; this sampled subset is not a full-suite memory bound.


## Concurrency

The POM defaults to one reused 3 GiB Surefire JVM with alphabetical class order.
The global `HeadlessStateTeardownExtension` resets engine state and closes every
registered SMPS driver after every top-level test class. Enabling JUnit threads
across classes would allow one test to tear down another's engine/audio state.
Process isolation is the first useful experiment, keeping serial execution inside
each JVM. Surefire supports multiple forks and per-fork resource paths; the POM
already separates LWJGL extraction by fork number. See
[Surefire fork options](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html).

1. Benchmark two isolated workers against the completed serial evidence, retaining
   heap headroom. Measure wall time, peak RSS, exact failures, skips, class coverage
   and process cleanup. The current single-fork structural policy would need an
   intentional update if the experiment succeeds. Prior smaller-heap OOM evidence
   rules out blindly reverting to four 1 GiB forks.
2. Prefer disjoint, duration-balanced ordinary-suite shards with separate checkout/
   target directories for CI. Generate both lists from one inventory, prove their
   union and non-overlap, and aggregate test identities. Categories share common
   tests and cannot simply be run independently without duplicate work. CI already
   runs ordinary and guards as separate jobs on PR/manual triggers.
3. Local ordinary/guard overlap needs separate worktrees, enough memory for both
   engines and source-analysis heaps, and one delivery-wide budget/cancellation/
   cleanup owner. Never run two Maven invocations in one build tree.
4. Later, measure independent FM dynamic cases in a dedicated lane. Narrow parallel
   test execution needs a chip static-state audit and explicit isolation from the
   global teardown extension; source independence alone is not proof of safety.

The measured subset above supports the opt-in profile; it is not a full-suite
speedup guarantee. Forking divides class
work; it does not split the single 83-second comparator class automatically.

## Audio value and asset policy

At the audited base, the Git-tracked `src/test/resources/audio/parity` tree contained 58 files totaling
42.72 MiB. These include ROM-derived sound-chip writes and RAM snapshots, not only
non-reconstructable result digests. The committed S3K v2 reference includes 625,699
writes to the YM2612 DAC sample register. The inspected S2 raw fixture carries a
`pcm` field but all 753 inspected rows have null values; field names alone are not
proof of embedded PCM. DAC/register/RAM payloads still require a provenance audit.

The FM bit-exact class mixes 183 script bodies under four chip-type settings:
145 synthetic hardware exercises (580 cases) and 38 game-prefixed captured SMPS
music/SFX logs (152 cases), as documented by its class Javadoc. The synthetic
portion can remain publicly reproducible without game sounds; move game-derived
scripts to externally supplied reference validation under a conservative no-game-
assets policy. Do not retire synthetic edge cases solely because they are slow.
The 83-second comparator's large entropy fixtures are generated test data; removing
game captures would not eliminate that separate streaming/memory-budget cost.

The public suite should use original minimal SMPS programs, generated DAC ramps/
impulses, chip-state vectors, mixer/resampler boundaries, malformed formats and
small synthetic comparator/store cases. Preserve independently derived chip
expectations and required third-party licensing. Retain one bounded-memory stress
case in an explicit stress lane, rather than duplicating large files for every
negative assertion. Its existing stress-size coverage should not silently disappear.

ROM-parity validation should take an explicit external fixture root and verified
user-supplied ROMs, with a pinned independent capture tool/reference provenance.
Public manifests can retain identities/digests and expected outcomes while raw
captures stay outside Git and public build artifacts. Ordinary public tests should
run substantively without those references; an explicitly requested parity lane
should fail clearly if prerequisites are missing. Seven former ordinary WAV regression
cases skipped when locally generated references were absent; the explicit local
WAV lane now fails on missing prerequisites instead. Never regenerate a golden
from the candidate under test and call equality an independent correctness oracle.

Changing file formats or re-rendering a soundtrack is not an asset-clearance
strategy. Musical compositions and recordings are separate protected works; see
the [U.S. Copyright Office explanation](https://www.copyright.gov/engage/musicians/).
The proposed conservative repository policy is an engineering boundary, not a
legal clearance opinion on individual fixtures. It needs provenance review of
compressed traces and scripts as well as playable media. The tracked sample-mod
WAV/OGG files are documented as generator-produced examples and need their own
provenance check, rather than classification by extension alone.
