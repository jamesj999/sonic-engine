# Trace presentation profile

## Decision

Candidate F warrants a separate narrow design because eager numeric value
formatting is a material allocation source in the ROM-backed Sonic 2 CNZ
LevelSelect replay:

- the independently reproduced `TraceBinder.formatHex` stack accounts for a
  median **17.04% of sampled allocation weight** (16.07–19.27%); and
- the broader requested presentation stack union accounts for a median 37.82%
  of sampled allocation weight (35.31–40.64%), but includes diagnostics that
  are excluded from the first design.

The narrow 17.04% result exceeds the portfolio's 10% allocation gate and is
the sole basis for recommending a raw/lazy representation for
`FieldComparison` expected/actual values.

JFR execution samples measure sampled CPU activity, not wall time. The full
presentation union has a median 14.16% execution-sample share
(12.11–14.94%), while the exact `TraceBinder.formatHex` stack has a median
3.52% share (2.63–4.59%). Both are useful CPU context only. This experiment
did not measure presentation wall share and makes no claim against the 5%
wall-time gate.

This profiling branch does not implement the recommended design or change any
production/test API.

The first implementation branch must exclude auxiliary-event indexing,
report grouping/history changes, and deferred frame diagnostics. Event and
engine diagnostic deferral are also kept out to make the first experiment
strictly about raw/lazy field values.

## Baseline and workload

| Item | Value |
|---|---|
| Baseline | `405630a3e3e00c7e5c18dd530515580f823168ce` |
| Branch | `feature/ai-performance-trace-presentation-profile` |
| JVM | OpenJDK `21.0.11` |
| Host | 32 logical CPUs; every Maven/JFR command serialized with `flock` and pinned to CPU 31 |
| Display | live X11 display, `DISPLAY=:0` |
| Test | `com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay` |
| Trace | `src/test/resources/traces/s2/cnz`, 9,469 replay rows |
| ROM | `s2.gen`, Sonic 2 World REV01, SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Surefire fork | `-Xshare:off -Xmx1g`; no Mockito agent |
| JFR | `settings=profile`, dump on fork shutdown |

The pre-profile verification command was:

```bash
flock -x /tmp/openggf-performance-measurement.lock \
  taskset -c 31 env DISPLAY=:0 \
  mvn -Dmse=off -Ptrace-replay \
  "-Dtest=com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay" \
  "-Ds2.rom.path=s2.gen" test
```

It passed with 1 test, 0 failures, 0 errors, and 0 skips. The cold compile plus
test took 50.325 s; the Surefire test body took 7.975 s. No fallback trace was
needed.

## Measurement command

Two unreported warmups preceded the seven reported recordings. All nine runs
held one uninterrupted host lease:

```bash
flock -x /tmp/openggf-performance-measurement.lock \
  taskset -c 31 env DISPLAY=:0 bash -lc '
set -e
report=target/surefire-reports/TEST-com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay.xml
for i in 1 2; do
  printf "WARMUP %d\n" "$i"
  mvn -Dmse=off -q -Ptrace-replay \
    "-Dtest=com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay" \
    "-Ds2.rom.path=s2.gen" \
    "-Dsurefire.argLine=-Xshare:off -Xmx1g \
      -Dopenggf.trace.presentation.profile=true" test
  rg "<testsuite" "$report"
done
for i in 1 2 3 4 5 6 7; do
  printf "SAMPLE %d\n" "$i"
  mvn -Dmse=off -q -Ptrace-replay \
    "-Dtest=com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay" \
    "-Ds2.rom.path=s2.gen" \
    "-Dsurefire.argLine=-Xshare:off -Xmx1g \
      -Dopenggf.trace.presentation.profile=true \
      -XX:StartFlightRecording=filename=/tmp/openggf-trace-presentation-${i}.jfr,settings=profile,dumponexit=true" \
    test
  rg "<testsuite" "$report"
done
'
```

Line wrapping above is only for readability; each `surefire.argLine` was one
property value. The temporary system property enabled counters only in the
profile build. The seven reported forks executed identical rows and produced
identical counts.

Surefire overwrites the same XML on each run. The exact `rg "<testsuite"`
immediately after each Maven invocation emitted the fresh `<testsuite ...>`
line, including its `time` attribute, before the next run overwrote the file.
The original run did not copy the XML. A repeat that also archives each fresh
file can replace that `rg` line in the reported loop with:

```bash
cp "$report" "/tmp/openggf-trace-presentation-${i}-surefire.xml"
rg -o 'time="[0-9.]+"' \
  "/tmp/openggf-trace-presentation-${i}-surefire.xml" | head -1
```

Warmup Surefire times were 8.278 s and 8.090 s.

## Seven samples

`Surefire time` is the test-body time from the fresh XML. `Recording length`
includes the Surefire fork startup/shutdown captured by JFR. `Union CPU` is
the share of all `jdk.ExecutionSample` events whose stack contained at least
one requested presentation class. `Hex CPU` matches only the exact
`TraceBinder.formatHex` class/method pair. Allocation columns apply the same
stack predicates to the `weight` sum of `jdk.ObjectAllocationSample`.

| Sample | Surefire | JFR length | Union CPU | Hex CPU, matched / total | Union allocation | Hex allocation, matched / total |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 8.414 s | 9.21 s | 14.94% | 4.15%, 20 / 482 | 37.82% | 17.04%, 395,632,880 / 2,321,864,712 |
| 2 | 8.034 s | 8.78 s | 12.16% | 2.94%, 14 / 477 | 40.64% | 19.27%, 447,283,776 / 2,320,626,168 |
| 3 | 7.864 s | 8.58 s | 14.47% | 2.63%, 12 / 456 | 38.57% | 16.07%, 367,584,456 / 2,287,440,128 |
| 4 | 8.024 s | 8.79 s | 13.88% | 4.56%, 21 / 461 | 38.75% | 16.71%, 387,253,128 / 2,318,094,728 |
| 5 | 7.958 s | 8.69 s | 12.11% | 3.52%, 16 / 454 | 37.42% | 16.24%, 377,006,120 / 2,321,871,240 |
| 6 | 7.816 s | 8.53 s | 14.85% | 4.59%, 21 / 458 | 37.15% | 18.00%, 417,816,824 / 2,321,741,200 |
| 7 | 7.689 s | 8.39 s | 14.16% | 2.92%, 13 / 445 | 35.31% | 17.19%, 399,127,168 / 2,321,645,552 |
| **Median** | **7.958 s** | **8.69 s** | **14.16%** | **3.52%** | **37.82%** | **17.04%** |

Every sample passed with 1 test, 0 failures, 0 errors, and 0 skips.

The execution percentages are not wall-time shares. Pinning removes migration
noise but does not turn periodic JFR CPU samples into elapsed-time
instrumentation. The denominator also includes fork startup, trace parsing,
and shutdown in addition to the 9,469-row replay.

## JFR views and attribution

The same standard views were extracted from every recording:

```bash
flock -x /tmp/openggf-performance-measurement.lock taskset -c 31 bash -lc '
for i in 1 2 3 4 5 6 7; do
  jfr view --width 220 recording \
    /tmp/openggf-trace-presentation-${i}.jfr
  jfr view --width 220 hot-methods \
    /tmp/openggf-trace-presentation-${i}.jfr
  jfr view --width 220 allocation-by-class \
    /tmp/openggf-trace-presentation-${i}.jfr
  jfr view --width 260 --cell-height 3 allocation-by-site \
    /tmp/openggf-trace-presentation-${i}.jfr
done
'
```

Stack-aware totals came from JSON views of
`jdk.ExecutionSample` and `jdk.ObjectAllocationSample` at stack depth 64.
This is the directly runnable aggregation used for the reported union, exact
`formatHex` lower bound, missing-stack accounting, and medians:

```bash
flock -x /tmp/openggf-performance-measurement.lock \
  taskset -c 31 bash -lc '
set -euo pipefail
out=/tmp/openggf-trace-presentation-aggregation.txt
{
  for i in 1 2 3 4 5 6 7; do
    recording="/tmp/openggf-trace-presentation-${i}.jfr"
    jfr print --json --events jdk.ExecutionSample --stack-depth 64 \
      "$recording" 2>/dev/null |
      jq -r --arg sample "$i" '\''
        def frames: (.values.stackTrace.frames? // []);
        def hasClass($class):
          any(frames[]?; .method.type.name == $class);
        def hasMethod($class; $method):
          any(frames[]?;
            .method.type.name == $class and .method.name == $method);
        def requested:
          hasClass("com/openggf/trace/TraceBinder") or
          hasClass("com/openggf/trace/FieldComparison") or
          hasClass("com/openggf/trace/TraceFrame") or
          hasClass("com/openggf/trace/TraceEventFormatter") or
          hasClass("com/openggf/trace/EngineDiagnostics");
        .recording.events as $events
        | ($events | length) as $total
        | ([$events[] | select((frames | length) == 0)] | length)
            as $missing
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceBinder"))] |
              length) as $binder
        | ([$events[] |
              select(hasClass("com/openggf/trace/FieldComparison"))] |
              length) as $field
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceFrame"))] |
              length) as $trace
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceEventFormatter"))] |
              length) as $event
        | ([$events[] |
              select(hasClass("com/openggf/trace/EngineDiagnostics"))] |
              length) as $engine
        | ([$events[] | select(requested)] | length) as $union
        | ([$events[] |
              select(hasMethod("com/openggf/trace/TraceBinder";
                               "formatHex"))] | length) as $narrow
        | "CPU sample=\($sample) total=\($total) " +
          "missingStacks=\($missing) binder=\($binder) field=\($field) " +
          "trace=\($trace) event=\($event) engine=\($engine) " +
          "union=\($union) " +
          "unionPct=\(100*$union/$total) narrow=\($narrow) " +
          "narrowPct=\(100*$narrow/$total)"
      '\''

    jfr print --json --events jdk.ObjectAllocationSample --stack-depth 64 \
      "$recording" 2>/dev/null |
      jq -r --arg sample "$i" '\''
        def frames: (.values.stackTrace.frames? // []);
        def hasClass($class):
          any(frames[]?; .method.type.name == $class);
        def hasMethod($class; $method):
          any(frames[]?;
            .method.type.name == $class and .method.name == $method);
        def requested:
          hasClass("com/openggf/trace/TraceBinder") or
          hasClass("com/openggf/trace/FieldComparison") or
          hasClass("com/openggf/trace/TraceFrame") or
          hasClass("com/openggf/trace/TraceEventFormatter") or
          hasClass("com/openggf/trace/EngineDiagnostics");
        .recording.events as $events
        | ([$events[].values.weight] | add // 0) as $total
        | ([$events[] | select((frames | length) == 0)] | length)
            as $missing
        | ([$events[] | select((frames | length) == 0) |
              .values.weight] | add // 0) as $missingWeight
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceBinder")) |
              .values.weight] | add // 0) as $binder
        | ([$events[] |
              select(.values.objectClass.name ==
                     "com/openggf/trace/FieldComparison") |
              .values.weight] | add // 0) as $fieldClass
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceFrame")) |
              .values.weight] | add // 0) as $trace
        | ([$events[] |
              select(hasClass("com/openggf/trace/TraceEventFormatter")) |
              .values.weight] | add // 0) as $event
        | ([$events[] |
              select(hasClass("com/openggf/trace/EngineDiagnostics")) |
              .values.weight] | add // 0) as $engine
        | ([$events[] | select(requested) | .values.weight] |
              add // 0) as $union
        | ([$events[] |
              select(hasMethod("com/openggf/trace/TraceBinder";
                               "formatHex")) |
              .values.weight] | add // 0) as $narrow
        | "ALLOC sample=\($sample) totalWeight=\($total) " +
          "missingStacks=\($missing) missingWeight=\($missingWeight) " +
          "binderWeight=\($binder) fieldClassWeight=\($fieldClass) " +
          "traceWeight=\($trace) eventWeight=\($event) " +
          "engineWeight=\($engine) " +
          "unionWeight=\($union) unionPct=\(100*$union/$total) " +
          "narrowWeight=\($narrow) narrowPct=\(100*$narrow/$total)"
      '\''
  done
} | tee "$out"

median() {
  local prefix="$1" key="$2"
  rg "^${prefix} " "$out" |
    sed -E "s/.* ${key}=([^ ]+).*/\\1/" |
    sort -n | sed -n "4p"
}
printf "CPU union median: %s%%\n" "$(median CPU unionPct)"
printf "CPU formatHex median: %s%%\n" "$(median CPU narrowPct)"
printf "allocation union median: %s%%\n" "$(median ALLOC unionPct)"
printf "allocation formatHex median: %s%%\n" \
  "$(median ALLOC narrowPct)"
'
```

Class matching uses the exact slash-form JFR class names shown above. The
lower bound additionally requires method name `formatHex`. `frames? // []`
handles an absent stack without dropping the event. CPU denominators count all
execution events. Allocation denominators sum every event's `weight`,
including missing-stack events. The union predicate is one boolean selection
per event, so a stack containing multiple requested classes contributes once,
not once per class. The `FieldComparison` allocation category intentionally
matches `objectClass.name` because it reports the direct record allocation;
the requested union remains stack-based. With seven samples, sorting and
selecting line four is the median.

All 3,233 execution samples had stacks. Allocation samples had 13–15 missing
stacks per recording, with only 37,296–38,408 bytes of weight; those weights
remain in the denominators.

Median per-category shares are:

| Category | Execution samples | Allocation weight | Interpretation |
|---|---:|---:|---|
| `TraceBinder` stack | 8.99% | 22.83% | Includes formatting called beneath the binder |
| `FieldComparison` | 0% sampled constructor CPU | 0.68% direct record class | The record itself is small; its eager strings allocate at caller/formatter sites |
| `TraceFrame.formatDiagnostics` stack | 1.74% | 4.31% | ROM-side frame context |
| `TraceEventFormatter` stack | 2.40% | 6.26% | Per-frame auxiliary-event summaries |
| `EngineDiagnostics.format` stack | 1.09% | 6.73% | Engine-side context, including the enabled touch-response diagnostic text |
| **Requested-class union** | **14.16%** | **37.82%** | Deduplicated; category rows overlap and must not be added |
| **Exact `TraceBinder.formatHex` stack** | **3.52%** | **17.04%** | Narrow lower bound that justifies the raw/lazy design through allocation only |

The standard hot-method view still showed FM synthesis as the largest
individual methods, but eager presentation was distributed through
`String.format`, `Formatter`, string builders/concatenation, and binder helper
sites. This is why the stack-aware union is materially larger than any single
top-frame entry. For example, sample 1 reported `TraceBinder.compareFrame` as
only 0.83% of top-frame samples while the requested stack union was 14.94%.

The allocation-by-class view likewise spreads presentation cost across
`byte[]`, `Object[]`, `String`, formatter internals, builders, map entries, and
`FieldComparison`; allocation-by-class alone would under-attribute the seam.

## Temporary counter evidence

The temporary probe counted calls and returned text payload only; JFR remained
the allocation and CPU authority. Logical bytes are `String.length() * 2`
(UTF-16 payload), not heap-size estimates.

| Owner | Calls/records | Characters | Logical bytes |
|---|---:|---:|---:|
| `TraceBinder.compareFrame` | 9,469 | — | — |
| `FieldComparison` expected + actual values | 390,017 | 3,713,092 | 7,426,184 |
| `TraceFrame.formatDiagnostics` | 9,469 | 1,648,505 | 3,297,010 |
| `TraceEventFormatter.summariseFrameEvents` | 9,469 | 3,713,489 | 7,426,978 |
| `EngineDiagnostics.format` | 18,938 | 38,176,050 | 76,352,100 |

`EngineDiagnostics.format` runs twice per compared frame in this S2 harness:
the replay assembles its engine/sidekick context, then the binder formats the
camera-preserving wrapper. The large payload includes touch-response
diagnostics deliberately enabled by the trace test for failure context.

The counter values were identical across observed forks. They show mechanism
and payload volume but are not added to the JFR allocation estimate.

The temporary package-private
`src/main/java/com/openggf/trace/TracePresentationProbe.java` used plain
`static long` fields because this replay invokes these seams on the test's main
thread. Its operative definitions were:

```java
static final boolean ENABLED =
        Boolean.getBoolean("openggf.trace.presentation.profile");

private static long binderCalls;
private static long fieldComparisons;
private static long fieldComparisonChars;
private static long traceDiagnosticCalls;
private static long traceDiagnosticChars;
private static long eventSummaryCalls;
private static long eventSummaryChars;
private static long engineDiagnosticCalls;
private static long engineDiagnosticChars;

static void recordBinderCall() {
    binderCalls++;
}

static void recordFieldComparison(String expected, String actual) {
    fieldComparisons++;
    fieldComparisonChars += length(expected) + length(actual);
}

static void recordTraceDiagnostics(String value) {
    traceDiagnosticCalls++;
    traceDiagnosticChars += length(value);
}

static void recordEventSummary(String value) {
    eventSummaryCalls++;
    eventSummaryChars += length(value);
}

static void recordEngineDiagnostics(String value) {
    engineDiagnosticCalls++;
    engineDiagnosticChars += length(value);
}

private static int length(String value) {
    return value == null ? 0 : value.length();
}
```

A shutdown hook printed every call/record and character field above, plus one
logical-byte field per character field computed as `chars * 2`. The probe was
guarded at every call site with
`if (TracePresentationProbe.ENABLED)`.

The temporary placements were:

| File/seam | Placement |
|---|---|
| `FieldComparison` | canonical compact record constructor; `recordFieldComparison(expected, actual)` |
| `TraceBinder.compareFrame` | final full overload, immediately before creating its `LinkedHashMap`; `recordBinderCall()` |
| `TraceFrame.formatDiagnostics` | after all primary/sidekick text was appended and immediately before returning `base`; `recordTraceDiagnostics(base)` |
| `TraceEventFormatter.summariseFrameEvents` | `recordEventSummary("")` before the empty return, otherwise store `String.join(...)` in `result`, record it, then return it |
| `EngineDiagnostics.format` | store `sb.toString()` in `result`, record it, then return it |

This placement counts returned payload, not transient formatter objects, and
does not call a timer. Reapplying these conditional snippets and running the
measurement command above reproduces the counter definitions without making
the probe part of a commit.

## Scope and next design

The result supports a separate raw/lazy design, not an in-place optimization:

1. retain raw numeric/boolean values and exact severity/delta in
   `FieldComparison`;
2. materialize signed/hex expected and actual strings only when a report or
   assertion renders them; and
3. add golden-output tests for exact report text, signed/unsigned hex,
   ordering, context windows, and repeated report access.

That design must preserve comparison semantics and report bytes. It must not
absorb the separately owned auxiliary-event type index, change report grouping
or history retention, or defer frame, event, or engine diagnostics in its
first branch. The diagnostic categories remain measured follow-up evidence,
not authority to broaden that first raw/lazy experiment.

## Cleanup proof

All temporary production-source edits and the temporary
`TracePresentationProbe` class were removed before the audit was written.
After removal:

```text
git diff -- src/main/java src/test/java
# no output

test ! -e src/main/java/com/openggf/trace/TracePresentationProbe.java
# exit 0

rg -n "TracePresentationProbe|openggf\.trace\.presentation\.profile|TRACE_PRESENTATION_PROBE" \
  src/main src/test
# no matches
```

The seven raw JFR recordings remain outside the repository under
`/tmp/openggf-trace-presentation-{1..7}.jfr`; their sizes are 1,334,536,
1,334,121, 1,312,182, 1,301,956, 1,316,482, 1,309,879, and 1,311,963 bytes.
They are transient evidence and are not committed.
