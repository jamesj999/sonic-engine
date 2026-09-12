# Build tooling guard fixture performance

## Scope and evidence

Base: `aaa45a0dd` on develop. Candidate:
`.worktrees/ai-build-guard-performance`, branch
`feature/ai-build-guard-performance`. Only `TestBuildToolingGuard` changes;
production policy scripts, manifest, test selection, and runtime code are unchanged.

Subprocess profiling identified two avoidable fixture costs. Restoring the
published 4,104,267-byte frontier from a neutral parent repeatedly scanned its
unrelated narrative for introduced historical lines (shell 6.051 seconds,
PowerShell 51.818 seconds). PowerShell startup alone was 0.120 seconds.
Fetching the policy cutover into an unrelated repository cost 28.366 seconds.
These instrumented observations identify causes; the serial timings below
measure the final change without instrumentation.

The eight-scenario shell/PowerShell matrix now uses a 180,064-byte fixture
containing every real historical machine-local line, in order: 567 occurrences
across 357 distinct allowances. It runs byte-identical copies of both scripts
with the real manifest's path/hash/count entries. Only the prefix byte length
and SHA-256 metadata change to describe the compact fixture. Two portable
header lines retain the non-home-line reorder scenario.

The same cases still check exact restoration plus append, altered content,
an extra occurrence, deletion, replay after deletion, reordering, wrong path,
and a newly introduced file. A separate added test runs the actual production
scripts and manifest against the complete published frontier: ordinary append
is accepted and historical-line tampering rejected. The existing independent
test still verifies every real manifest allowance against the published blob.

The unreachable-cutover fixture now borrows the project's immutable Git
objects through an alternate object directory. Its independently initialized
root and refs remain local. New assertions prove the cutover commit exists
and is not an ancestor of the fixture tip. The missing-cutover fixture remains
independent without an alternate. This removes fetch/pack work while retaining
the distinction between missing and present-but-unreachable history.

## Fault detection

Temporary mutation probes first ran all eight scenarios successfully with
both installed interpreters. They confirmed copied scripts were byte-identical
and full/compact historical counts matched every real allowance. Then:

- Bypassing prefix verification made deletion and replay-after-deletion
  incorrectly pass in both interpreters (four rejection assertions detect it).
- Increasing a historical occurrence allowance made the extra-occurrence
  scenario incorrectly pass in both interpreters (two assertions detect it).

Mutations affected generated copies under `target/` only and were restored.
No instrumentation or mutation is part of the committed tests. Bounded timing,
mutation, and suite comparisons are retained outside the repository in the
task evidence directory, named here by `$TASK_EVIDENCE_ROOT`.

## Serial timing

Java 21.0.11, Maven 3.9.16, PowerShell 7.6.5. Baseline ran first in the main
checkout at `aaa45a0dd`, then candidate in the task checkout, without other
Maven runs from this task or profiling enabled:

```bash
mvn -Dmse=off -Pguards -Dtest=TestBuildToolingGuard \
  -Dopenggf.surefire.reports=target/<task-run>/serial test -B
```

| Surefire timing | Baseline seconds | Candidate seconds |
|---|---:|---:|
| Complete class | 118.514 | 33.005 |
| Eight-case frontier matrix | 64.407 | 6.261 |
| Added published-prefix checks | — | 1.936 |
| Missing/unreachable cutover | 28.561 | 0.032 |

The class saves 85.509 seconds (72.2%, about 3.6 times faster). This is a
single serial pair of class timings, not a claim about total-suite speedup.
All 113 original test identities remain, with one added test. Both completed
runs have zero failures, errors, or skips. PowerShell was installed and its
conditional assertions executed; the test count alone would not prove that.
The focused four-test run also passed, including the real-manifest pin.

## Required suite comparison

The repository selector chose every ordinary category and all guards:

```bash
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base aaa45a0dd --run
```

Candidate run `20260912T150649Z-c215b6bc` used the unchanged base plus this
test patch. The runner found all three ROMs by verified SHA-1 and supplied
absolute paths to their existing files. The ordinary lane completed 20,299
tests in 2,509 reports: 20,260 passes, 14 failures, no errors, and 25 skips.
Every test identity, multiplicity, status, failure type/message, report class,
and complete nonpassing diagnostic matched the completed `aaa45a0dd` baseline.
The baseline's recorded command used `mvn -Dmse=off test -B`, the same ROMs,
and `-DmodApi.destinationBranch=develop`; it ran from 14:43:30 to 15:00:28 UTC.

The canonical ordinary outcome inventory SHA-256 matches between both runs:

```
4ed2f929be54328c53204daffb192b29cf9a5e9948d1989f51749f167287d821
```

Failures remain the FBZ Act 2 route and thirteen compatibility-matrix cases.
Skips remain optional audio references/BK2 inputs, opt-in diagnostic/capture/soak
checks, unavailable EGL, and the existing CPZ scenario assumption. Skips are
not passing coverage. Maven exits 1 for the ordinary lane because of these
baseline failures. No failure or skip diagnostics were omitted by the runner.

The separate guards lane completed 657 tests in 82 reports, all passing,
with zero skips. The runner verified that the candidate stayed unchanged
throughout both lanes. Its final exit code is 1 solely for the ordinary
baseline failures above.

## Integration baseline

While this candidate ran, develop received the independent menu/carousel
merge `8613f00db`. Its completed ordinary run (15:06:09–15:23:11 UTC) supplies
the updated integration baseline: 20,322 tests in 2,513 reports, 20,283 passes,
the same 14 failures, no errors, and 25 skips. It used the same recorded Maven
command and ROM properties as the earlier baseline. Its additional 23 passing
tests belong to the menu work. This optimization changes a different test
file, so the existing candidate correctness evidence remains attributed to
`aaa45a0dd`; the integrated run must be compared with `8613f00db`.
