# Native Load-Time Measurement Stream

Date: 2026-07-29

## Purpose

Produce diagnostic, deterministic service-cost observations for S3K Kosinski queue jobs
by replaying approved immutable BK2 movies through the existing headless BizHawk/GPGX
recorder. The output feeds the offline load-time manifest generator. It is never a trace
replay authority and is never loaded by production gameplay.

## Existing authority boundary

`tools/bizhawk-headless/src/Recording/HardwareTimingEventEngine.cs` already mirrors the
native direct and KosM FIFOs from RAM and computes the same fingerprints as Java. It
remains the run-wide ledger and lifecycle authority. The scoped native-harness contract
permits exactly one execute callback: the already reviewed instruction after
`Process_Kos_Module_Queue` returns from `Queue_Kos`
(`ModuleChildSubmissionPc = 0x001B46`). Measurement reuses that callback and the existing
audited frame-end FIFO reconciliation; it does not add general callback registrations.
The locked-ROM instruction-byte golden test for the permitted callback remains the
fail-fast revision check.

The permitted callback gives module-child submission identity and ordering exactly.
Top-level direct submissions are first observed when frame-end reconciliation sees their
occupied FIFO entries and can also be invisible when they submit and retire between
samples. They are coverage diagnostics only: the generator rejects them from runtime rows,
estimator training, and estimator validation. Records mark this provenance explicitly.
KosM parents likewise remain validation-only.

The existing `hardware_timing.jsonl` format and schema remain unchanged. Measurement uses
a separate `load_time_measurements.jsonl` diagnostic artifact and a separate recorder
version. Trace loaders and `HardwareTimingReplayPort` must reject this filename;
guard tests forbid imports from measurement tooling into replay/runtime authority.

## Aggregate records

The permitted execute callback and `ObserveFrameEnd` reconciliation update the existing
run-wide ledger. The tooling stream emits one immutable aggregate only when a direct job
retires; it does not expose internal lifecycle hooks as a second replay-like authority.
The aggregate is sufficient because the production-owned ledger has already enforced FIFO
submission and retirement before emission, while the strict offline parser independently
recomputes identity and validates ordering.

Every LF-terminated JSONL aggregate contains:

- `measurement_schema: 1`;
- immutable movie SHA-256 and ROM SHA-1;
- fixture/movie SHA-256, ROM SHA-1, recorder version, and service model;
- reset epoch, raw retirement frame, and monotonic `sequence_in_frame`;
- `kind`, epoch-local ordinal, fingerprint, and eligible retirement boundary;
- parent fingerprint for exact module-created direct children, or null for censored
  top-level diagnostics;
- canonical descriptor and deterministic command-stream feature vector;
- exact/censored precision, classified flag, and observed service opportunities.
- `observation_precision`: `exact_callback` or `frame_end_censored`.

Records use a stable field order. Duplicate JSON keys, unknown fields, nonmonotonic
epoch/frame/sequence/ordinal order, invalid boundary/model/hash, recomputed fingerprint
disagreement, parent-precision disagreement, or descriptor/feature disagreement invalidate
the whole capture. Ordinal gaps are permitted because reset-aborted jobs never emit.

## Cost attribution

Cost is the count of observed eligible native service opportunities from physical-head
activation through the opportunity that retires the head, inclusive. Direct jobs advance
only at `pre_main_loop`. Only exact-callback child samples with at least one observed
opportunity whose whole lifetime stays in classified service modes are eligible for
publication. A zero-opportunity completion proves an unobserved synchronous lifetime and
is excluded. A duplicate level counter outside
the title-card exception marks an active sample unclassified and excludes it. Censored
top-level observations carry no runtime cost label. KosM parents have zero additional
units; their direct children carry the decoder cost. Waiting behind another physical head
is excluded.

The run-wide ledger state machine is `queued -> active-head -> retired`, with claimed
state irrelevant to native capture. The permitted module-child callback emits the exact
child submission after RAM contains it. Frame-end reconciliation discovers top-level
submissions, counts admitted direct-service boundaries, and observes retirement using the
same rules that publish schema-2 hardware timing. Reset clears the ledger and reports
aborted pending work separately rather than converting it into completed samples.

Staged PRE retirements, title-card POST_OBJECTS exceptions, VInt-only frames, and
same-frame retire/enqueue use the existing callback/reconciliation ordering and the shared
sequence. Ordinals and ledger state remain run-wide inside a reset epoch across complete-run
segments and seamless transitions. Power/reset advances the epoch, clears pending work,
and restarts ordinals; per-segment writers never own/reset the ledger.

## Aggregation

The offline Java generator groups direct jobs by game, kind, fingerprint, and
service-model version.
It rejects descriptor/feature disagreement. Each group stores the lower median
(`sorted[(n-1)/2]`), sample count, minimum, maximum, and fixture provenance. Runtime
manifests dictionary-encode fixture names and contain one record per fingerprint.

For S3K `s3k-kos-v1`, only `KOS_DECOMPRESSION_QUEUE` observations may produce runtime
rows or estimator inputs. `KOS_MODULE_QUEUE` parent observations appear only in the
validation report for end-to-end child/parent ordering. The generator rejects attempted
S3K parent rows or coefficients; runtime applies its typed zero-cost parent rule before
lookup and never warns or estimates parents.

## Estimator

Candidate estimators use deterministic features computed while scanning the compressed
stream: literal commands, short/long dictionary copies, total copied length, compressed
and decompressed length, module count, final-module size, and module coordination count.

Validation leaves out whole fingerprint groups. A secondary validation groups by fixture
family. Nearest-rank p95 uses checked integer rank
`r = ceil(95*n/100) = (95*n+99)/100` and zero-based element `errors[r-1]`. The same rule
governs candidate tie-breaking and acceptance. A model is publishable only with:

- median absolute error at most two eligible frames;
- p95 absolute error at most five;
- at least 20 distinct fingerprints;
- at least three fixture/movie families; and
- nonconstant variation in every retained feature.

The finite ordered candidate family is:

```text
prediction = max(0, intercept + ceil(feature / divisor))
feature    = each retained feature in the order listed above
intercept  = 0..8
divisor    = 1..65536
```

For each leave-one-fingerprint fold, select on the training rows by lowest median absolute
error, then lowest nearest-rank p95, then feature order, intercept, and divisor. Predict
the held-out group with that candidate. Repeat with whole fixture families held out.
The published candidate is selected over the complete dataset with the same ordering,
but only if the accumulated held-out predictions pass every gate. All arithmetic is
64-bit checked integer arithmetic; `ceil(a/b) = (a+b-1)/b`, predictions clamp only at
zero, and overflow rejects the candidate. Serialization stores feature name, intercept,
and divisor as integers, so no floating-point solver or coefficient normalization exists.

If no candidate passes,
the measured manifest still publishes without an estimator and missing fingerprints warn
once then use immediate behavior.

## Publication

The measurement parser/generator lives under `com.openggf.tools.timing`; production
feature values live in `com.openggf.game.timing`, but diagnostic record types do not.
Trace loaders explicitly reject `load_time_measurements.jsonl` when placed in a trace
fixture, and architecture guards forbid runtime/replay packages from importing tooling
types or opening that filename. Measurement events cannot satisfy replay cardinality,
fingerprint, ordinal, or boundary matching; only `hardware_timing.jsonl` can.

Publication inputs list movie path/SHA-256, ROM SHA-1, recorder commit/version, command,
and output SHA-256. The generator produces byte-stable JSON and a validation report.
The no-replace key is SHA-256 over the sorted movie path/hash pairs, ROM SHA-1,
measurement schema, recorder version, and recorder commit. Existing
completion-only fixtures may support cardinality checks but never duration.

The authorized unique S3K source movies are:

- `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`;
- `src/test/resources/traces/s3k/_movies/s3-knux-multibonus-ss.bk2`;
- `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`;
- `src/test/resources/traces/s3k/cnz/s3k-cnz-sonic-tails.bk2`;
- `src/test/resources/traces/s3k/mgz/s3k-mgz-sonic-tails.bk2`.

The capture command receives the discovered locked-on ROM through `--rom`; it verifies
SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6` and never copies/publishes ROM bytes.
The run-wide raw measurement output is staged in `target/load-time-measurements/`.
Checked-in deliverables are:

- `src/main/resources/load-time-profiles/s3k-v1.json`;
- `docs/architecture/audits/2026-07-29-s3k-load-time-profile-validation.md`;
- `docs/architecture/audits/2026-07-29-s3k-load-time-publication.tsv`.

The TSV records the no-replace key, every input/output hash, command, and versions.
Regeneration into a fresh target directory must reproduce manifest/report bytes exactly.

Before enabling
`PROFILED`, generated coverage is compared with the provisional 436-row cross-game lower
bound and the S3K schema-2 125-row direct lower bound. These are comparison points, not
acceptance thresholds.

## Verification

- synthetic FIFO tests cover activation, waiting exclusion, same-frame replacement,
  the permitted module-child callback, eligible-boundary counting, censor labels, and reset;
- native/Lua differential tests preserve existing completion output byte-for-byte;
- strict parser and generator tests cover malformed order, identity disagreement,
  lower median, provenance dictionary, and byte stability;
- estimator tests cover all thresholds and the no-model path;
- shared synthetic vectors cover descriptor refill, terminators, short/long and overlapping
  copies, module padding/alignment, final-module size, malformed streams, and overflow;
  C# and Java must emit identical fingerprints and feature vectors;
- architecture guards prove runtime/replay cannot load measurement records;
- end-to-end capture replays at least three independent movie families.
