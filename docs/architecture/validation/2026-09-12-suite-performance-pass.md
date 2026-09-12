# Systematic suite performance pass

Pinned baseline: `09f442379` (develop). Candidate worktree:
`.worktrees/ai-suite-performance-pass`. The seven-target
[work plan](../plans/2026-09-12-suite-performance-pass.md) records scope and delivery.

## Measurement

Fresh Java 21 Surefire JVMs, serial runs in the same worktree, verified S1/S2/S3K
ROM paths, `-Dmse=off`, and separate report directories. Class times include test
setup and teardown but exclude Maven compilation. Single pairs are observations,
not statistical confidence intervals or whole-suite speedup claims.

The ordinary baseline selected `TestCompleteRunAudioComparator`,
`TestFbzCompatibilityMatrix`, `TestRewindTorture`, `TestTraceSessionLauncherRunBranch`,
`TestNukedOpn2BitExactScripts`, and `TestDirectConnectEndToEnd` through `-Dtest`.
It completed 911 tests: 897 passes, 13 FBZ failures, and one opt-in rewind skip.
The separate `mvn -Dmse=off -Pguards test -B` baseline completed 657 tests in
82 classes, all passing without skips; class times summed to 215.251 seconds.
Focused Maven processes used the runner's bounded rolling-log helper, with
20-minute ordinary and 12-minute guard baseline deadlines. Temporary diagnostics
are consumed and deleted after comparison.

## Changes and retained coverage

- **Complete-run audio:** derive physical and semantic roots in one streaming pass
  and reuse that immutable pair for both producer fixtures. Both independent
  store writes and comparator validation remain. No generated frame is removed:
  434,417 high-entropy frames, each compressed input above 32 MiB, combined input
  above 64 MiB compressed and 256 MiB expanded, separate 32 MiB comparison JVM,
  and 500,000 semantic requests are retained. The store reads character blocks
  while enforcing the same per-record limit and LF-only policy. Root hashing
  reuses the exact canonical UTF-8 line bytes already written, avoiding a second
  serialization. Added boundary cases cover buffer edges, read-ahead, repeated
  EOF, exact 16 MiB limits, and CR-versus-limit error precedence.
- **Launcher:** parse the installed S1 reference once per class, release it after
  the class, and create synthetic S2 data only for consumers. Reference rows are
  read-only in these tests; sessions, payload leases, cursors, comparators, and
  synthetic files remain independently created. All 42 test identities remain.
- **Guards:** skip a regex scan only when its literally quoted method name is
  absent from the body (a necessary condition for any match). Cache method extraction by complete sanitized source for the duration
  of one test; cache control-flow results under the owning method graph identity.
  A new negative case checks identical root bodies with different helper bodies,
  preventing cross-source memo reuse. Cache complete ArchUnit imports per class
  with exactly the original scope: production-only for SMPS ownership; project
  classes including test fixtures, excluding archives, for payload authority.
  Negative fixture imports remain independent. No rule or analyzed class is removed.
- **FBZ:** standalone preflights and complete routes use identical parameter sources.
  Remove the second synchronous preflight immediately before the existing fresh
  session reset in each route case. All configurations, native route traversal,
  sidekick audits, and standalone transition checks remain.
- **Direct connect:** a package-private host clock overload leaves the public API
  and default wall clock unchanged. The test advances room time on the host event
  loop only after observing the preceding socket messages. Real WebSockets, the
  120 ms latency proxy, ghost rendering, standings, voting, next-round selection,
  disconnects, and cleanup remain. Focused host round tests cover timing boundaries.
- **FM:** unchanged after a measured experiment. Batching `at` into the existing
  cycle loop took 43.90 seconds versus 43.537 seconds before, so the patch was
  discarded. All 732 C-derived cycle and side-log pins remain; profiling showed
  chip execution dominates and script parsing offers little headroom.
- **Rewind:** unchanged after profiling. The fixed-adjacent pattern dominates the
  46.179-second class; CPU samples concentrate in replay audio synthesis (fast FM
  and PSG), with no sampled hotspot in `RewindSnapshotDiff`. Reducing frames,
  audio processing, seeds, or checkpoints would weaken this whole-state rewind
  exercise. Cross-fixture reference reuse would also lose the required Block/Chunk
  object identity. The current reference pass already stops at its final checkpoint.

A bounded JFR profile of the original source-flow guard, rewind, and FM cases
completed successfully. Of 4,637 samples with the FM script runner in the stack,
223 were in its cycle wrapper and most were inside chip execution; parsing had
only a small share. This supports retaining the simulation rather than adding a script cache
or reducing pin checks. The guard samples pointed to repeated regular-expression
analysis, motivating exact-source method extraction reuse.

## Before/after ledger

The first focused run completed 1,004 tests: 990 passes, 13 baseline FBZ failures,
and the same opt-in rewind skip. All 26 FBZ identities and all 13 complete failure
messages matched the baseline exactly. A refined guard/launcher run completed
80 tests, all passing. The final store/comparator run completed 130 tests, all passing without skips.
The bounded-reader microbenchmark consumed the same 65,536 lines / 536,805,376
characters from a generated 512 MiB stream under `-Xmx32m`: 3.120 seconds before,
0.541 seconds after. This isolates reader overhead; the class timing below
includes the actual full capture generation, validation, and comparison.

| Class | Baseline seconds | Candidate seconds |
|---|---:|---:|
| Complete-run audio comparator | 132.859 | 78.641 |
| Launcher | 43.923 | 10.580 |
| Audio presentation guard | 44.393 | 6.714 |
| SMPS session guard | 6.874 | 1.952 |
| Active payload authority guard | 24.712 | 22.749 |
| FBZ matrix | 64.512 | 64.033 |
| Direct connect | 33.670 | 2.614 |
| FM bit-exact scripts | 43.537 | Unchanged; experiment discarded |
| Rewind torture (unchanged) | 46.179 | 46.289 |

The six changed hotspot classes above (comparator, launcher, three guards,
and direct connect), plus FBZ, save 163.660 seconds in this serial pair;
FM and rewind remain intact. Whole-suite timing is measured separately below.
The remaining baseline guard leaders are whole-source clock terminology
(40.860 seconds), build tooling (30.233 seconds, already optimized in the earlier
pass), constructor-service checks (16.514 seconds), and trace/movie alignment
(10.355 seconds). The clock guard already performs one complete javac attribution
in a separate JVM; its source and override scope is retained.

## Required delivery validation

The selector includes all 2,504 ordinary candidate classes and all guards because
shared test infrastructure changed. Tool preflight passed in the actual launch
environment with `LUA_BIN=lua5.4`. Run once after focused work and documentation:

```bash
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base 09f442379 --run
```

The default stopping rule is 40 minutes total Maven time or 10 minutes without
output. Known failures are compared by identity and diagnostic; unrelated gameplay
failures do not justify repeated broad runs or changes to the engine.
