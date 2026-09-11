# Department audio optimisation verification

Base: `3fd7a15fc` on `develop`; fetched and fast-forward checked on 2026-09-05.
Task branch: `feature/ai-department-audio-perf`, isolated worktree
`.worktrees/ai-department-audio-perf`. Baseline tests use detached
`.worktrees/ai-department-audio-baseline` at the same fixed commit.

## Changes

The audio architecture guard reuses one production bytecode graph for its three
whole-project inspections. The graph belongs to the JUnit test-class instance;
it is not shared across builds. All production import scopes, checks and separate
negative-fixture imports remain. The ordinary and structural-guard sessions stay
separate. No test is removed or relaxed.

Audio load/override readiness reads the driver's clock region directly under the
same sequencer lock used by snapshot capture. Three former scalar queries no
longer capture every sequencer and track. Queries remain at the same points,
including after override restoration; an earlier snapshot is not reused across
that mutation. Snapshot ownership, rollback, clock selection and ROM service order
remain unchanged. A regression checks NTSC/PAL configuration, snapshot restore
and live-command rollback.

## Allocation probe

[RegionQueryProbe.java](2026-09-05-department-audio/RegionQueryProbe.java) compares
`captureSnapshot().region()` on the base with `getRegion()` on the task tree.
Both arms compile the same probe against their own freshly built production and
test classes, with their Surefire classpaths; no Maven build output is copied
between trees. Java 21.0.11, ThreadMXBean thread allocation; 5,000 warmups and
three repetitions of 3,000 queries per sequencer count. A MethodHandle selects
the arm before measurement; the result escapes through a volatile field.

| Synthetic sequencers | Base median bytes/query | Task median bytes/query |
|---|---:|---:|
| 0 | 896 | 0 |
| 1 | 1,216 | 0 |
| 32 | 8,552 | 0 |

The sequencers use empty track programs: this proves removal of redundant query
allocation, not a representative music-transition cost or an FPS improvement.
First base repetitions include small VM noise at 0/1 sequencers (900.341 and
1,216.424 B/query); other repetitions match the medians. All scalar repetitions
report zero. The probe does not claim allocation-free audio presentation.

Compile with `javac -cp ARM_CLASSPATH -d OUTPUT RegionQueryProbe.java`, then run
`java -cp OUTPUT:ARM_CLASSPATH RegionQueryProbe snapshot` for base and `scalar`
for development. ARM_CLASSPATH must contain the selected tree's own target/classes,
target/test-classes and test dependencies. Use the `java.class.path` property from
that arm's fresh Surefire XML. Supply a writable java.io.tmpdir as needed.

## Suite and timing evidence

Ordinary commands: `mvn -Dmse=off -B test` with all three explicit absolute
ROM properties below. Guards: the same options plus `-Pguards`, in a separate
Maven JVM, with `LUA_BIN=/usr/bin/lua5.4`.

```text
-Dsonic1.rom.path=${REPO_ROOT}/Sonic The Hedgehog (W) (REV01) [!].gen
-Dsonic2.rom.path=${REPO_ROOT}/Sonic The Hedgehog 2 (W) (REV01) [!].gen
-Ds3k.rom.path=${REPO_ROOT}/Sonic and Knuckles & Sonic 3 (W) [!].gen
```

Set REPO_ROOT to the absolute main-checkout path and EVIDENCE_ROOT to the
external task evidence directory. Pass each property as one argument. CRC32/SHA-1 were checked against the repository
identity table. No ROM was copied or relinked to satisfy a test; repository hooks
created their normal worktree resource links.

| Tree | Ordinary Surefire summary | Structural guards |
|---|---|---|
| Fixed baseline 3fd7a15fc | 16,641 tests; 0 failures/errors; 23 skips | 609 passed; no skips |
| Task tree | 16,642 tests; 0 failures/errors; 23 skips | 609 passed; no skips |

The full ordinary runs overlapped in separate worktrees; their wall times are
not an optimisation comparison. XML identity comparison found unchanged skip
reasons, the one new region regression, and six missing ICZ top-level methods in
the task XML. Both logs report repeated nested ICZ class execution; the XML
contains duplicated and overwritten results, so raw XML counts differ from the
Surefire summaries. A separate one-fork focused run explicitly selected those
six methods plus TestSmpsDriverSnapshot and TestSmpsSessionTransitionMatrix:
44 checks passed without skips. Combining that evidence covers every baseline
identity plus the new check, with no changed failure/skip outcome. All 609 guard
identities/outcomes match exactly. The four required S3K stability classes passed
without skips (8/38/6/3 checks). No unrelated report-discovery behavior was changed.

BG reviewed the four-file source/test diff and approved both changes (BG119).

### Sequential guard measurements

Four fresh JVM runs, order baseline/task/task/baseline, after both full suites:

```sh
mvn -Dmse=off -B -Pguards -Dsurefire.forkCount=1 -Dtest=TestAudioPresentationArchitectureGuard test
```

All runs passed the same 37 checks with no skips. Class elapsed time excludes
Maven compilation/startup; wall time includes it. Maximum RSS is the external
process resource report, not a Java heap measurement.

| Run | Class seconds | Wall seconds | Maximum RSS KiB |
|---|---:|---:|---:|
| 1-baseline | 45.365 | 63.72 | 2,000,816 |
| 2-development | 39.608 | 55.74 | 1,877,324 |
| 3-development | 38.902 | 54.11 | 2,004,224 |
| 4-baseline | 41.046 | 57.80 | 1,778,580 |

Mean of these two samples per arm: 43.206 → 39.255 seconds for the class (about
9.1% lower). This is a small local sample, not a suite-wide or statistically
controlled speedup claim. The resource samples do not establish a consistent
memory reduction. Cache scope and the separate guard JVM keep its lifetime
bounded to this verification session.

## Upstream reconciliation

Before integration, develop advanced to `d004188d9`, adding the reviewed S3K
PSG-envelope and Sonic 2 DAC-cadence repairs. The optimisation commit was rebased
as `753268b00`; source paths were disjoint. The sole conflict was the changelog's
newest-first insertion, resolved by retaining both release entries.

Clean ordinary and separate guard suites were repeated on that fixed updated
baseline and the rebased task tree, each with its own build output:

| Tree | Ordinary Surefire summary | Structural guards |
|---|---|---|
| Updated baseline d004188d9 | 16,650 tests; 0 failures/errors; 23 skips | 609 passed; no skips |
| Rebased task 753268b00 | 16,651 tests; 0 failures/errors; 23 skips | 609 passed; no skips |

Updated XML identity comparison found no missing baseline identity, unchanged
skip/failure outcomes and exactly the new region-query regression. No focused
coverage repair was needed for this pair. Performance figures above remain
attributed to the original base/task comparison; they were not relabeled as
measurements of the updated base. Subsequent upstream `c7d04fbc3` changes only
its audio validation document and is retained during integration.

## Integration

Integrated into `develop` as `32522d7cb`, preserving both upstream documentation
closeouts (`c7d04fbc3`, `075bbca75`) and adding the README release summary. The
task worktree was fast-forwarded to that exact integrated commit for verification,
keeping Maven output isolated from other sessions in the main checkout.

`mvn -Dmse=off -B package` with the explicit ROM properties above passed:
16,651 tests, zero failures/errors and the same 23 skips. Both ordinary and
bundled JARs were built. Separate `-Pguards test` passed all 609 guards without
skips. Integrated identity comparison found no missing baseline identity and no
changed failure/skip outcome. In addition to the new region regression, integrated
XML retains the six pre-existing ICZ methods absent from the updated baseline
XML; all pass. Those are recovered report entries, not newly added tests.

Final documentation-only edits were checked for local links, whitespace and
repository content/trailer policy; no production/test source changed after the
integrated run. Raw commands, logs, complete test identities/statuses, focused
selections and process resource reports are retained under `${EVIDENCE_ROOT}/`.
