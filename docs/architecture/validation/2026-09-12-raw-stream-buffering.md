# Request-aware S2 oracle input buffering

Base: `060ec5991` on develop. Candidate branch:
`feature/ai-raw-stream-fixtures`, worktree `.worktrees/ai-raw-stream-fixtures`.

## Change

`S2RequestAwareOracleRawStream` now wraps its file input in a
`BufferedInputStream`. `StrictLines` still reads, validates, and hashes every
byte from the same open descriptor. The parser, closure accounting, line-size
limit, UTF-8/CR/BOM rules, EOF checks, and input ownership are unchanged.
The standard input buffer bounds additional memory independently of file size.

Profiling the existing tests pointed to complete scans rather than just fixture
construction: the closure-mutation test alone took 35.582 seconds. Inspection
showed one unbuffered file read per byte. Buffering fixes this shared cost for
synthetic and published inputs. No fixture was shortened, no parser result was
cached, and no test or assertion was removed or changed.

## Focused evidence

Java 21.0.11 and Maven 3.9.16. The unchanged baseline ran first, then the candidate,
in fresh test JVMs in the same worktree:

```bash
mvn -Dmse=off -Dtest=TestS2RequestAwareOracleRawStream \
  -Dopenggf.surefire.reports=target/raw-stream-performance/<baseline-or-candidate> test -B
```

| Surefire timing | Baseline seconds | Buffered seconds |
|---|---:|---:|
| Entire class | 66.687 | 11.360 |
| Closure and PCM mutation test | 35.582 | 4.539 |

The single serial pair saved 55.327 seconds (83.0%, about 5.9 times faster).
These are class timings, not total Maven or whole-suite timings. Both completed
24 tests: 21 passing and three optional explicit ROM/BK2 checks skipped. Every
test identity, outcome, and full skip diagnostic matched. The skips are not
passing coverage. The executed cases retain the full 750-row synthetic inputs,
independently computed closure claims, malformed identity/byte forms, and
self-consistent adversarial override/PCM mutations.

The committed-window check uses:

```bash
mvn -Dmse=off -Dtest=TestS2PublishedRequestWindows \
  -Dopenggf.surefire.reports=target/raw-stream-performance/published test -B
```

It checks every pinned compressed and raw digest, strict parsing, frame bounds,
transfer counts, and rejection under another window's identity. Both tests
passed without skips in 13.479 seconds. Java/Lua/PowerShell preflight passed.

## Delivery validation

The change-based selector chooses audio, common, rewind, and tooling (946
candidate classes) plus all structural guards. The required selection is run
once for this candidate; subsequent integration checks are focused unless a
material change invalidates its evidence. Consumed diagnostics are acknowledged
and deleted, not archived.

```bash
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base 060ec5991 --preflight
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base 060ec5991 --run
```

Run `20260912T162429Z-e51b993f` completed at candidate `5325ce49a` with the
working tree unchanged. The runner supplied all three ROM paths after matching
their expected SHA-1 identities. Ordinary selection: 8,873 tests in 953 reports,
8,852 passing, one failure, zero errors, and 20 skips (490.94 seconds). Guards:
657 tests in 82 reports, all passing without skips (228.20 seconds).

The failure was
`TestObjectPlacementEncoding.commonParserPreservesDescendingFullXOrderInsideOnePlacementColumn`:
expected `[448, 384]`, actual `[384, 448]`. A focused run on unmodified develop
`060ec5991` reproduced the exact test identity and complete failure diagnostic:

```bash
mvn -Dmse=off \
  '-Dtest=TestObjectPlacementEncoding#commonParserPreservesDescendingFullXOrderInsideOnePlacementColumn' \
  -Dopenggf.surefire.reports=target/raw-stream-baseline-placement/reports test -B
```

This pre-existing placement-order disagreement is outside the oracle change.
The 20 skips were inspected: optional reference audio/BK2 data, opt-in rewind
measurements/soak tests, and explicitly requested captures or observations.
No failure or skip diagnostics were omitted. The runner exited 1 because of
that baseline failure; this is a completed category comparison, not a claim
that the full engine suite is green.

Integration into develop was conflict-free at `4d91ecf12`. The focused merged-tree
check completed 26 tests: 23 passing, the same three optional ROM/BK2 skips,
zero failures and errors. It used:

```bash
mvn -Dmse=off \
  '-Dtest=TestS2RequestAwareOracleRawStream,TestS2PublishedRequestWindows' \
  -Dopenggf.surefire.reports=target/raw-stream-integrated/reports test -B
```

Consumed category diagnostics were acknowledged and deleted. Focused-run
diagnostics are removed after recording these results; no failure archive is
retained. The existing unrelated disassembly changes and local note are preserved.
