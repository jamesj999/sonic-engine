# Profiled load-time simulation

Date: 2026-07-29

## Purpose

Normal play currently releases prepared hardware work as soon as the production
scheduler permits. This can make queue-dependent transitions, object routines, music,
and presentation advance noticeably earlier than on original hardware. Trace replay
already has recorded completion authority, but normal play needs deterministic pacing
without depending on a trace.

This design adds a normal-play load-time policy based on measured trace observations.
It preserves production ROM bytes, queue ownership, FIFO contention, service boundaries,
global-empty predicates, rewind, and trace isolation. It is a pacing simulation, not
cycle-accurate hardware emulation.

## Current status (2026-09-06)

The S3K `PROFILED` implementation is live for the published Kosinski service model.

`FAST` is a real mode and the repository default since 2026-09-06. A game that ships a
FAST manifest resolves to it without a warning; S3K's is
`load-time-profiles/s3k-fast-v1.json`, seeded as a byte-for-byte copy of the measured
`s3k-v1.json` entries and then hand-tuned. Unlike the PROFILED manifest it may be
edited by hand, and that is its purpose: it also carries entries the native measurement
stream never observed, currently the title-screen Sonic frame decodes taken from the
original hardware capture that the removed `ANIM_FRAME_DURATIONS` table transcribed
(fixture label `legacy-hardware-capture:...@34482b194`). A game without a FAST manifest
warns on each `LoadTimeProfileFactory.resolve(...)` call and behaves as `NONE`.

`REALISTIC` remains intentionally unfinished and is retained as an explicit resolver
alias that warns and returns the supplied profiled profile. The factory does not maintain
a global warn-once registry. A normal gameplay context usually resolves once at
construction, but context reconstruction can resolve again.

The S3K title screen runs outside a gameplay session, so `Sonic3kTitleScreenManager`
owns a `HardwareTimingService` of its own for the title loop's Kosinski work: frame 7's
art is queued at `loc_4040` as the ROM does, and frames 8 to B are submitted when
`Iterate_TitleSonicFrame` selects them; a synchronous decode that is not ready stalls
the loop, one missed V-int per iteration, until the profile admits it. `serviceFrames`
for that kind count the `PRE_MAIN_LOOP` services from the iteration that starts the
decode, so a value of N stalls N-1 iterations and `NONE` stalls none.

No authoritative cross-game semantics can be derived from the current owners. The
`LoadTimeProfile` submission contract and `HardwareWorkKind` registry currently model
S3K Kosinski module/direct work only. S1/S2 Nemesis PLC and dynamic-art/DPLC services
remain game-owned queues and do not submit through `HardwareTimingService`. The
cross-game hardware-timing contract permits delaying already-submitted production art
work, but it does not define arbitrary safety delays or allow trace comparison data to
choose gameplay timing. Implementing either reserved mode therefore requires a new
model and measurements, not a fitted constant or a trace-specific branch.

## Configuration

The configuration key is:

```yaml
loadTimeSimulation: PROFILED
```

The accepted values and initial behavior are:

| configured value | effective normal-play behavior | diagnostic |
|---|---|---|
| `NONE` | Release prepared work as soon as its production dependencies permit. | None. |
| `PROFILED` | Use deterministic measured work costs, with a validated estimator for missing fingerprints. | Warn once per missing fingerprint that uses estimation or immediate fallback. |
| `FAST` | Reserved; behave as `NONE`. | Warn on each factory resolution that `FAST` is not implemented. |
| `REALISTIC` | Reserved; behave as `PROFILED`. | Warn on each factory resolution that `REALISTIC` currently uses `PROFILED`. |

Unknown values are configuration errors that list all accepted values. Existing
installations and the repository default remain `NONE`, so the feature does not silently
change established behavior. A gameplay context normally resolves the configured value
once during construction, but context reconstruction resolves it again and therefore
emits another reserved-alias warning. Changing configuration does not retime work already
pending in that context.

`FAST` is intended to provide short safety delays that prevent jarring lifecycle
compression. `REALISTIC` is reserved for a future higher-confidence model backed by
broader trace coverage and, if evidence requires it, context-specific observations.

## Architectural boundary

A game-agnostic `LoadTimingProfile` is consumed by `HardwareTimingService`. It receives
typed production submissions, their stable fingerprints, compression shape, and service
model. It returns a deterministic admission-cost decision:

- a measured cost;
- an estimated cost;
- or immediate/as-soon-as-possible behavior.

The hardware timing service and game-specific queue coordinators retain ownership of:

- submission and stable identity;
- FIFO order and capacity;
- direct/module child relationships;
- preparation and ROM-derived payloads;
- eligible service boundaries;
- readiness and payload claiming;
- global queue-empty predicates;
- rewind snapshots and reset lifecycle.

The profile is not a second queue, decompressor, event timer, sleep, or gameplay callback.
Events continue to poll their production queue predicates. Shared runtime code does not
branch on game or zone names. Each game module supplies a manifest and optional
compression estimator through the profile-provider boundary. This allows the incoming
Sonic 1 and Sonic 2 PLC queues to adopt the same system without coupling their policies
to S3K.

## Normal-play data flow

1. Production code submits a ROM-backed hardware job.
2. The timing service computes the existing stable submission fingerprint.
3. Under `NONE`, preparation and readiness use immediate/as-soon-as-possible scheduling.
4. Under `PROFILED`, the provider looks up the job by queue kind, fingerprint, and
   versioned service model.
5. An exact record supplies its deterministic measured cost. A missing record invokes
   the validated estimator for that compression kind.
6. Production preparation and payload creation proceed unchanged. When the job becomes
   the physical FIFO head, a separate profile-owned admission countdown starts and
   advances in parallel with preparation at that kind's eligible native boundaries.
7. `NONE` admits a prepared head immediately. `PROFILED` admits it only when both the
   payload is prepared and the remaining admission units have reached zero.
8. FIFO contention and child coordination occur normally. Consumers advance only when
   their existing individual or global predicates permit.

Queue waiting is not represented as an independent per-event delay. A job waiting behind
earlier FIFO work receives no admission service until it owns the queue position,
preventing contention from being counted twice. Preparation and the countdown run
concurrently rather than having their durations added. The gate delays readiness only
when preparation finishes before the measured countdown.

## Trace-replay isolation

Trace replay bypasses the normal-play profile completely:

1. Production submits and prepares the same ROM-backed job.
2. Recorded hardware authority matches kind, ordinal, fingerprint, and service boundary.
3. The matching recorded edge replaces the normal-play admission gate and authorizes
   readiness after production preparation.
4. Missing, extra, reordered, or mismatched work remains fatal.

Profile data cannot submit jobs, provide payloads, mutate gameplay state, satisfy a trace
edge, or conceal a trace mismatch. Hardware timing schema 1 retains its existing split:
module completion is recorded and direct completion uses the existing live production
policy, not `PROFILED`. Schema 2 retains recorded authority for both module and direct
work. The normal-play configuration has no effect on either schema.

## Manifest

Each game owns a checked-in, versioned manifest generated from committed trace timing
data. Records are sorted deterministically and conceptually contain:

```json
{
  "kind": "KOS_DECOMPRESSION_QUEUE",
  "fingerprint": "sha256:...",
  "serviceModel": "s3k-kos-direct-v1",
  "sampleCount": 3,
  "medianServiceUnits": 14,
  "minimumServiceUnits": 13,
  "maximumServiceUnits": 15,
  "traceSchema": 7,
  "hardwareTimingSchemas": [2],
  "recorderVersions": ["6.38-s3k"],
  "fixtures": ["s3k/aiz1_to_hcz_fullrun"],
  "observedCompletionBoundaries": ["PRE_MAIN_LOOP"]
}
```

The exact serialization schema is implementation-owned, but it must preserve:

- manifest format version;
- game/profile identity;
- queue kind and versioned service model;
- stable submission fingerprint;
- sample count, median, minimum, and maximum;
- fixture provenance;
- trace schema, hardware timing schema, and recorder versions;
- observed completion-boundary distribution as provenance and validation.

Measured values are never hand-edited. Regeneration must produce byte-identical output
from identical inputs. Code review can therefore distinguish trace evidence changes from
runtime changes.

## Measurement capture, attribution, and aggregation

Existing committed `hardware_timing.jsonl` files are completion-authority streams. They
contain completion frame, boundary, kind, ordinal, and fingerprint, but intentionally do
not contain submission time or work progress. They are insufficient to reconstruct
intrinsic service cost and must not be reinterpreted as measurement manifests.

Profile generation therefore requires a separate, diagnostic-only native measurement
artifact. An approved native capture records, for each mirrored ordinal:

- discovery/submission frame and boundary;
- stable kind and fingerprint;
- physical FIFO activation and retirement;
- each eligible admission-service boundary while it owns the physical head;
- parent/child identity for module-created direct work;
- observed completion frame and boundary;
- fixture, movie, recorder, trace-schema, and hardware-schema provenance.

This measurement stream is never loaded by gameplay or trace replay and grants no timing
authority. It may be generated by replaying the existing source movies through an
instrumented native recorder, but it cannot be derived from the current completion-only
fixtures. Any expanded committed capture artifact or replacement fixture follows the
repository's existing publication, approval, and immutable-hash policy.

The separately reviewed capture format must observe native queue/service execution
directly, with before/after markers around submission, head activation, decoder service,
module coordination, and retirement routines. Once-per-frame RAM samples alone are not
sufficient when activation and service can occur between samples. Every record carries a
monotonic within-frame sequence so the generator can distinguish activation before a
service from activation after it.

The extraction tool reconstructs physical FIFO ownership, activation, eligible service,
and retirement from this measurement artifact.

For direct work, an observation counts eligible native service boundaries from physical
head activation through native retirement, inclusive of the decisive retirement
boundary. Time waiting behind another job is not included. Runtime starts the countdown
at physical-head activation, concurrently with preparation. The activation boundary may
advance both preparation and the countdown; this avoids adding an artificial extra frame.

KosM module parents use the direct FIFO for child streams. Each child receives its own
direct observation. Module coordination retains its production POST-objects cadence. An
S3K KosM parent has zero additional profile admission units: on the decisive POST service,
the existing coordinator consumes the ready final child, completes parent preparation,
and may admit the parent on that same boundary. Parent elapsed-time observations are
retained only for end-to-end validation; they are never charged on top of child costs.
The service-model registry supplies this explicit zero-cost composite rule before
manifest lookup, so it does not invoke the missing-fingerprint estimator or warning.
Other composite queue kinds require an explicit service-model rule proving their
intrinsic parent cost before they may receive a nonzero parent budget.

Observations are grouped by queue kind, stable fingerprint, and service-model version.
The selected deterministic value is the lower median: after integer values are sorted,
index `(count - 1) / 2`. This always chooses an observed value, including for even sample
counts. The manifest retains sample count, range, and observed completion boundaries so
weak coverage, context variation, and high variance remain visible. Completion boundary
is validation provenance, not a lookup key known at submission. Wide or contradictory
ranges are generator diagnostics, not silently averaged evidence.

The generator fails closed on:

- inconsistent identity for one fingerprint;
- impossible FIFO activation or retirement order;
- a completion at the wrong service boundary;
- an unsupported or ambiguous timing schema;
- malformed or incomplete fixture timing data.

## Missing-entry estimator

`PROFILED` may estimate a missing fingerprint only when the estimator for that
compression kind passes the acceptance gate below. The estimator analyzes the compressed
command stream rather than using file size alone. Candidate deterministic features are:

- literal command count;
- short and long dictionary-copy counts;
- total copied output length;
- compressed and decompressed lengths;
- module count and final-module size;
- fixed coordination overhead per module.

Coefficients are trained independently per compression, queue kind, and service-model
version from measured manifest observations. Generated coefficients and their validation
report are checked in. Runtime never trains, samples, or uses wall-clock time.

Estimator acceptance leaves out entire fingerprint groups, never individual duplicate
observations. A secondary fixture-family-grouped validation checks route/capture leakage.
The 95th percentile uses nearest rank `ceil(0.95 * count)` over sorted absolute errors.
Acceptance requires:

- median absolute error no greater than two eligible frames; and
- 95th-percentile absolute error no greater than five eligible frames;
- at least 20 distinct measured fingerprints for the kind;
- observations from at least three independent fixture/movie families; and
- nonconstant variation in every retained estimator feature.

An estimator that fails either threshold is unavailable for that kind. A missing entry
for an unavailable or unsupported estimator warns once and uses `NONE` behavior for that
job. This prevents an unvalidated estimate from appearing authoritative.

## Diagnostics

Diagnostics are rate-limited by stable identity:

- `FAST` and `REALISTIC` emit one warning on every factory resolution describing their
  effective fallback; no global or session-level warn-once registry exists for these aliases.
- A missing manifest fingerprint emits one warning per session and fingerprint.
- The warning states whether an estimate or immediate fallback was selected.
- Debug logging may include kind, fingerprint, boundary, measured/estimated source,
  chosen units, sample count, and observed range. `MEASURED`, `ESTIMATED`, and
  `IMMEDIATE` are runtime decision labels; measured manifest records never masquerade as
  estimated observations.
- An optional end-of-session debug summary reports exact coverage, estimated jobs,
  immediate fallbacks, and repeated uses suppressed by warn-once behavior.

Trace replay emits none of these normal-play lookup warnings because the profile is
bypassed.

## Rewind and lifecycle

Pending jobs snapshot their assigned admission cost, remaining countdown, whether the
countdown has activated, lookup source
(`MEASURED`, `ESTIMATED`, or `IMMEDIATE`), and existing preparation/queue state. Restore
does not repeat lookup, retrain, or emit another warning. Rewinding therefore cannot
reroll timing or change behavior after a manifest update during the same process.

`GameplayModeContext` receives the timing-profile provider through a game/session-owned
hardware-timing factory. The factory constructs `HardwareTimingService` with the resolved
normal-play policy; shared code never discovers a provider through game-name branching.

Session reset clears resolved configuration, profile-owned missing-fingerprint warning
suppression, counters, pending budgets, and game-provided profile state. Reserved-alias
warnings remain factory-per-resolution. Seamless transitions preserve or transfer queue
work only through the existing production handoff contracts.

## Verification

Required automated coverage includes:

- deterministic measurement parsing, manifest generation, and byte-stable ordering;
- correct FIFO attribution without double-counting waiting time;
- exact fingerprint and service-model lookup;
- median selection and provenance retention;
- high-variance and malformed-input generator failures;
- estimator feature extraction and leave-one-out thresholds;
- missing-entry estimate and immediate fallback behavior;
- warn-once behavior per session/fingerprint;
- `NONE`, `PROFILED`, `FAST`, and `REALISTIC` resolution;
- trace replay bypassing all normal-play profile behavior;
- direct/module overlap and global queue-empty consumers;
- rewind with partially consumed measured and estimated budgets;
- session reset and cross-session isolation;
- S3K transitions, object lifecycles, and music pacing;
- a provider contract usable by S1/S2 PLC queues.

Acceptance requires that existing trace replay outcomes are unchanged, `NONE` preserves
the current normal-play contract, and `PROFILED` produces deterministic results across
repeated runs.

## Delivery sequence

1. Land the shared configuration, game/session timing factory, profile/provider contract,
   admission-gate state, and trace-replay bypass with `NONE` behavior unchanged.
2. Design and approve the diagnostic native measurement artifact and its publication
   policy; instrument capture without changing replay authority.
3. Replay approved source movies to publish measurements with provenance and immutable
   hashes.
4. Implement the measurement parser, manifest generator, and S3K attribution.
5. Check in the initial S3K measured manifest and coverage report.
6. Implement and validate estimators per supported compression kind.
7. Enable S3K `PROFILED` normal play and pacing regression tests.
8. Integrate S1/S2 queue providers after their PLC queue branch lands.
9. Treat `FAST` and `REALISTIC` as explicitly separate future designs before changing
   their fallback behavior.
