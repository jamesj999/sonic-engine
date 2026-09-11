# S1 Deferred Publication Bounds Design

## Status and decision

This revised design is proposed for parent review against
`348aa119624ad514656ccbc4f013fcffb1a1c3a1`. It changes no native observer,
manifest content, raw/canonical schema, or Java comparison semantics.

Use a **generation-aware in-memory frame batch with explicit recorder resource
caps**. Close the batch for reservation generation A after the whole frame that
consumes A has validated, even when that same frame ends with generation B
pending. Flush A's earlier frames in row order, then either publish the current
frame or make it the first held frame for B. This retains valid
consume/re-reserve relays while bounding production retention to one native
reservation generation.

Replace the combined `StringWriter deferredPublication` with a bounded list of
immutable frame strings. Eliminate `deferredEvidencePublication` as persistent
cross-frame state: finalized evidence for earlier rows exists only in the
consume frame's local commit plan and is merged while the resolved generation
flushes.

Row 21766 remains a separate test-only whole-run `StringWriter` failure. The
bounded selective proof sink at the reviewed base addresses that problem; it
neither proves nor fixes this production generation-relay risk.

## Problem

One action-11 reservation is source-bounded by its kind-6 blocker. Kind 6 has a
four-frame continuation limit, so a generation created at age zero can hold at
most five successfully completed frames; a generation carried through the
publication cutoff can hold at most four published frames after native age
reset.

That per-generation limit does not bound the current managed journal. A valid
frame may consume reservation A, close its child and blocker, reserve B (or
re-reserve the same blocker), and end with a reservation pending. `ProcessFrame`
currently consults only the final `pendingDeferredBegin` value. It therefore
appends the relay frame to the existing journal and leaves all finalized
evidence retained. Repeating this shape keeps the journal open across an
arbitrary number of individually valid generations.

The generation boundary cannot be inferred from blocker token or A7/return
identity. A re-reservation may reuse all of those values after the prior
reservation is consumed. It is the ordered action-12 consume followed by the
next action-11 reserve that creates a new generation.

## Goals and non-goals

The design must:

- keep every valid reserve/retry/consume callback and the ordinary nested
  service topology unchanged;
- preserve exact JSONL row order, record order, LF endings, and BOM-free UTF-8
  bytes at the production writer;
- keep same-row deferred evidence at its action-12 correlation point before
  later native records, and insert only prior-row evidence immediately before
  its original row's `frame_end`, exactly once;
- write nothing for a frame that fails semantic validation or a resource
  preflight;
- keep retained heap independent of route length, including same-blocker
  re-reservation and rotating-blocker relays;
- retain the existing fresh-capture recovery unit after output I/O failure; and
- preserve `NoReplacePublisher` as the only canonical commit authority.

It does not make an arbitrary `TextWriter` seekable or retryable, add a
disk-spill format, change native continuation semantics, emit internal
generation ids or limit diagnostics into canonical JSONL, or weaken the real
proof sink's independent bounds.

## Considered approaches

| Approach | Result | Decision |
|---|---|---|
| Generation-aware batch rotation plus caps | Accepts valid relays, restores the native per-generation lifetime bound, and keeps prior rows hidden until the consume frame fully validates. | **Chosen.** |
| Aggregate caps only | Is the smallest emergency hardening, but a valid long relay fails solely because all generations share one batch until the aggregate cap. | Safe fallback only; not preferred. |
| Session-owned disk spill | Can retain a route-length relay without generation rotation, but adds create/delete/replay I/O boundaries and still needs evidence indexing and caps. | Rejected as unnecessary complexity. |

Generation-aware rotation preserves the current atomicity level. Semantic and
capacity failures occur before any commit from the current frame. An outer
writer I/O failure can still leave a prefix in that writer because `TextWriter`
has no rollback contract; production canonical publication remains all-or-
nothing because the writer targets an unpublished staging inode.

This reconciles the sink reviews as follows: row 21766 was the selective
proof's former whole-run sink, the per-reservation native lifetime is genuinely
bounded, and the adjudication's consume/re-reserve relay still makes the
production managed journal route-proportional. Generation rotation fixes that
relay without a disk spill; incremental serialization/callback caps close the
separate gap that an end-of-frame-only check would leave.

## Internal model

### Reservation generation

`DeferredManagedBegin` gains an internal, monotonically increasing
`GenerationId`. Assign it whenever action 11 changes the managed state from no
pending reservation to pending, including when a reservation is carried into
`BeginEpoch`. Repeated action-11 observations while the reservation is already
pending retain the same id. Action 12 consumes that id. A later action 11 gets
a fresh id even if blocker token, hook, A7, and return PC are identical.

The id is session-local bookkeeping. It is not trace evidence and must never be
serialized, compared by Java, or used to decide native/service semantics.
Checked id exhaustion fails the capture before creating a new reservation.
Rollback restores both the pending generation and the next-id counter.

### Deferred frame batch

Replace the two persistent fields with one batch:

```text
DeferredPublicationBatch
  generationId
  heldFrameLimit
  frames[]
    row
    jsonl                 immutable, LF-terminated frame transaction
    logicalRecordCount    includes deferred records reserved for this row
    missingEvidenceCount  records still awaiting this generation's consume
  frameCharacters
```

Rows in a batch are strictly increasing and every frame belongs to the batch's
single generation. A newly published generation has a maximum of five held
frames (`continuation_frames + 1`). A reservation carried through `BeginEpoch`
has a maximum of four held publication frames. Native continuation validation
remains authoritative and can reject sooner; these are local upper assertions,
not replacement semantics.

The batch stores frame strings separately. Flushing must not call a combined
`ToString`, `ReadAllText`, or `Split`, and therefore never makes a second copy
of every held frame at once.

### Frame-local evidence and commit plan

When action 12 consumes a reservation, finalize its observations into ordered
`DeferredEvidenceLine(row, managedCorrelationOrdinal, recordIndex, jsonl)`
values. Evidence for the current row is written immediately at the action-12
correlation point, exactly where `EmitDeferredManaged` writes it today. It
therefore remains before native records and managed evidence observed later in
the consume frame. Only evidence whose callback row precedes the consume row is
staged in the current `ProcessFrame` commit plan for insertion immediately
before its saved row's `frame_end`; it is not installed in a persistent
collection.

### Incremental bounded retention

End-of-frame preflight is necessary for generation rotation but is too late to
bound callback-time allocations. Every path that retains or appends data has an
incremental gate:

1. Manifest loading does not use `File.ReadAllText`. It opens a `FileStream`,
   rejects a missing/non-regular file or an initial length above 1,048,576,
   then reads through a strict UTF-8 decoder which stops and rejects before
   consuming byte 1,048,577. This catches file growth/races as well as invalid
   UTF-8 before `JObject.Parse`, so the whole-file allocation itself is
   intentionally bounded. After parsing, it rejects every string which can
   flow into raw output
   (`name`, `action`, `source_label`, `source`, schema/game identity, and any
   future manifest-derived raw string) above 1,024 UTF-16 characters.
2. Fixed-shape event records (native events, callback evidence, requests,
   decisions, dispatch, resets, frame delimiters, and terminal) have a checked
   worst-case escaped JSON size. Manifest/configuration loading rejects any
   fixed-shape line which can exceed its static event-line limit. Aggregate
   envelopes are deliberately excluded from that proof.
3. `BoundedJsonlSerializer` writes through a counting writer and refuses before
   exceeding the line-character limit. No retained `JObject` is accepted merely
   because it will be serialized later.
4. Before `OnHook` adds a record to `PendingManagedOccurrence`, it bounded-
   serializes the current record and reserves the checked maximum characters
   for fields added at correlation. For action 11 that reservation includes
   native-correlation JSON, deferred A7/return fields, and all maximum-width
   action-12 consume fields. Callback count, record count, and reserved
   characters are checked before the occurrence or record is enqueued.
5. Every native/current-frame record is bounded-serialized before it
   is appended to the frame writer or output. `BoundedFrameWriter` checks the
   remaining record and character budgets before each append.
6. Baseline and other aggregate envelopes use explicit collection cardinality
   checks plus an actual serialized-character cap. They are not first assembled
   as an unbounded `JObject`/`JArray` graph. Each active service, pending
   descendant, and ancestry transition is bounded-serialized directly into one
   capped local envelope transaction while its running count and actual
   character charge are checked. The completed transaction is written to outer
   output only after every array closes within the cap. No static 65,536-line
   claim is made for records with dynamic arrays. The observer validates active,
   pending, combined-service, and transition counts before frontier cloning or
   baseline projection, so the aggregate cap is not preceded by an unchecked
   dynamic-array materialization.
7. Evidence finalization verifies its actual bounded serialization does not
   exceed the callback-time reservation before adding a same-row line to the
   frame or a prior-row line to the local commit plan.
8. Appending a validated immutable frame to a batch checks held rows and actual
   `jsonl.Length` before installing the new batch state.

The gates use checked `long` counters and subtraction-form limit tests. They
bound retained strings, objects, lists, and builders during capture rather than
observing their size after a whole frame has already accumulated.

After native drain, managed correlation, reset validation, empty-pending
checks, and `frame_end` all succeed, construct the immutable current frame
string and preflight the complete commit plan. Only then may it mutate the
batch or write to `output`.

The commit state table is exhaustive:

| Batch at frame start | Validated final reservation | Commit |
|---|---|---|
| none | none | Write the current frame. |
| none | G | Start batch G with the current frame. |
| G | the same unconsumed G | Append the current frame to batch G. |
| G | none, after consuming G | Flush batch G with its staged evidence, then write the current frame. |
| G | fresh H, after consuming G | Prebuild the new H batch, flush G with its staged evidence, then install H with the current frame. |

Any other combination is an internal lifecycle error and fails before output.
In particular, a batch may not silently change generation, and a final
reservation may not reuse a consumed `GenerationId`.

Multiple consume/re-reserve cycles in one frame do not create persistent
batches for the fully resolved intermediate generations. Only a batch present
at frame start can own earlier completed rows. Fully resolved current-row
evidence stays inside the current frame; the final pending generation, if any,
owns that frame.

### Ordered merge

Prior-row evidence lines are nondecreasing by
`(row, managedCorrelationOrdinal, recordIndex)`. Flush walks the batch frames
once and the evidence lines once. For each saved `frame_end`, it emits all
evidence for that row, then the unchanged `frame_end` line. It rejects before
the first output write if:

- an evidence row is outside the batch;
- evidence ordering regresses;
- a row receives fewer or more lines than its `missingEvidenceCount`;
- any evidence remains after the final held frame; or
- the reconstructed logical record count differs from the saved count.

This removes the current `frames x evidence` search and gives O(frame bytes +
evidence bytes) replay work within one bounded generation.

## Explicit recorder limits

The native record count is necessary but is not a character or managed-object
bound. Apply the following production limits with checked `long` arithmetic:

| Limit | Value | Charge |
|---|---:|---|
| Manifest file | 1,048,576 bytes | Checked before and during bounded UTF-8 read, before JSON materialization. |
| Manifest-derived raw string | 1,024 characters | Validated before storing parsed manifest values; escaped worst-case is included for fixed-shape records. |
| Fixed-shape event line | 65,536 characters including LF | Native/frame/callback/request/reset/terminal records only, proven from bounded fields and enforced while serializing. |
| Aggregate envelope line | 33,554,432 characters including LF | Baseline and any other dynamic-array envelope, charged by actual bounded serialization; no static line-shape proof. |
| Baseline active services | 8 entries | Must match the native maximum depth and exact managed token-set validation. |
| Baseline pending descendants | 65,536 entries | Must not exceed native event capacity or the observer's independently checked pending-service bound. |
| Baseline total services | 65,536 entries | Checked sum of active plus pending, matching the downstream cutoff-service bound. |
| Ancestry transitions per baseline service | 7 entries | Existing source/schema transition bound, checked before envelope serialization. |
| Total baseline ancestry transitions | 458,752 entries | Checked product of 65,536 total services and seven transitions per service; the aggregate character cap normally rejects first. |
| Current frame transaction | 33,554,432 characters | All materialized lines and reserved correlation/evidence augmentation in one `ProcessFrame`. |
| Held frames | 5 new / 4 cutoff-carried | Successfully completed publication frames in one batch. |
| Held frame strings | 167,772,160 characters | Sum of actual `jsonl.Length` for one five-frame batch. |
| Deferred evidence records | 4,096 records | All unresolved, finalized, and newly observed deferred records owned or touched by the current frame transaction. |
| Deferred evidence charge | 33,554,432 characters | Sum of checked per-record serialized upper bounds, retained until its generation commits. |

The old 4,096-character claim is not used: hook names and close reasons were
not previously length-bounded. Manifest-string validation plus checked
record-shape calculation makes the 65,536-character bound formal for
fixed-shape event records even under maximum JSON escaping. Baseline active,
pending, and ancestry arrays are dynamic, so their safety comes from explicit
cardinality validation and an actual 33,554,432-character aggregate-envelope
policy, not a false static per-record proof. Runtime serialization enforces
both classes so a new field cannot silently invalidate the calculation.

The large theoretical pending/transition cardinalities do not imply that such
a baseline is accepted: actual serialization must fit the aggregate-envelope
cap before a byte reaches `output`. Exceeding a cardinality or aggregate cap
faults the session, emits no baseline, and requires a fresh power-on capture.
The local transaction itself is capped while it grows, so rejection does not
first allocate the prohibited aggregate object graph or oversized final
string.

The 33,554,432-character frame limit exceeds the reviewed native-only maximum
frame of approximately 18,546,206 characters. It is nevertheless an explicit
recorder policy, not a claim that every native-valid mix of managed records is
accepted. A native-valid, maximum-volume frame may be rejected by this policy;
that is an intentional fail-closed resource decision, not ROM semantics. The
held/evidence caps are likewise recorder policy and are not inferred from row
21766, a retry count, a route, or a BK2.

Keep these values in one internal immutable `DeferredPublicationLimits`
policy. The production constructor uses the constants above; tests may inject
smaller positive limits. Do not add them to the ROM/source manifest or raw
schema. Test-only observability exposes held frame count, held characters,
evidence count/charge, and generation id without exposing mutable collections.

At an action-11 marker, reserve the checked serialized upper bound for every
managed record before retaining it. The per-frame cumulative counter does not
release units when an intermediate generation consumes; this bounds many
consume/re-reserve cycles in one frame. At successful frame commit, retained
charge becomes only the final pending generation's charge. A finalized line
must independently fit its reservation and the fixed-shape 65,536-character
line ceiling.

The existing `max_records_per_frame = 65,536` remains independently enforced.
Deferred managed records are charged to the row where their callback occurred,
as they are today; replay does not charge them again to the consume row.
`logicalRecordCount` and `missingEvidenceCount` make that reservation explicit
and make under/over-insertion a pre-output error.

All `limit + delta` checks use subtraction or checked addition before writing,
appending, cloning, or incrementing the public row cursor. A limit failure is a
capture failure; it emits no truncation marker, terminal, or partial logical
frame.

## Exact invariants

1. **One native reservation, one managed generation.** At most one
   `DeferredManagedBegin` is pending. Repeated reserve markers coalesce only
   within its generation; every post-consume reserve has a greater internal id.
2. **One batch, one generation.** A nonempty batch has exactly one pending
   generation with the same id at frame start. The sole exception is the
   cutoff-carried generation before its first published frame, which may be
   pending while the batch is empty.
3. **Resolved-before-rotation.** A batch can close only after its exact
   generation's action-12 consume and nested child begin both correlate.
4. **Full-frame gate.** No batch flush occurs from a callback. The consuming
   frame must pass every native, managed, reset, record-count, order, and
   resource check through `frame_end` first.
5. **Chronological output.** Published row numbers are strictly increasing.
   Old batch rows flush before the relay frame; the relay frame is either
   written next or held as the first row of the final generation.
6. **Evidence completeness and position.** Every reserved deferred record
   appears exactly once in its callback row; no other row receives it. Same-row
   records remain at action-12 correlation before later consume-frame records.
   Prior-row records appear immediately before that row's `frame_end`. Within
   either position, order is callback order then record order.
7. **No persistent finalized evidence.** After a successful `ProcessFrame`,
   there is no cross-frame finalized-evidence list. Evidence is either still
   an unresolved observation of the final generation, embedded in a held frame,
   or already written.
8. **Local boundedness.** The batch, current transaction, pending observations,
   and frame-local evidence each satisfy all table limits. Generation relay
   count and route length do not appear in the bound.
9. **State commit follows destination commit.** `lastRow`, batch replacement,
   evidence release, and final-generation charge advance only after the
   selected journal/output operation succeeds. Preflight and semantic failures
   leave them at the preceding frame.
10. **Fault is terminal in both epochs.** Any failure in
    `ObservePreEpochFrame` after native frame capture or emulator advance has
    begun, any failed published `ProcessFrame` after that point, and any
    envelope, cap, serialization, allocation, or output I/O failure marks the
    session faulted. Once faulted, later `ObservePreEpochFrame`, `BeginEpoch`,
    `CaptureFrame`, and `Complete` calls all reject before native/emulator/output
    work; disposal remains allowed. A fresh power-on capture with a new staging
    file is the only retry unit.
11. **No canonical partial.** Internal generation data and capacity failures
    never reach canonical JSONL. The canonical final is absent until the whole
    capture, terminal, writer flush, file `Flush(true)`, and no-replace link all
    succeed.
12. **Clean terminal.** A successful terminal requires no managed service, no
    pending generation, no batch, no staged evidence, and zero retained
    counters.

## Cutoff, rollback, reset, and terminal behavior

### Cutoff

Prepublication action-11 callbacks continue to cross `BeginEpoch` only as the
reviewed scalar identity/count. No pre-epoch occurrence objects, frame bytes,
or evidence lines enter the publication batch. `BeginEpoch` assigns the
carried reservation a generation id and a four-held-frame local limit. Metadata
and baseline values are unchanged, but their output now uses the bounded
fixed/aggregate serializers described above.

`ObservePreEpochFrame` is not a retry sandbox. Once its native `BeginFrame` or
emulator `advance` has begun, any native, managed, reset, correlation, cap, or
allocation failure faults the whole session even though it emitted no
publication bytes. It must not accept another pre-epoch row or attempt
`BeginEpoch`; only disposal and a fresh power-on capture are valid.

### Rollback

The frame-start snapshot includes pending/boundary reservations, next
generation id, managed trackers, request/reset scratch, batch reference and
counters, and pending evidence charge. The existing batch is immutable during
capture. Newly finalized earlier-row evidence and a possible replacement batch
are local until commit.

A semantic, correlation, or reset failure before output restores the
managed snapshot, leaves the old batch byte-for-byte unchanged, writes no
current-frame bytes, and does not advance `lastRow`. A cap or bounded-
serialization failure has the same no-write/unchanged-batch result. In every
case the session is then faulted: rollback is a no-publication/diagnostic
guarantee, never permission to replay the row.

### Reset

Reset or power while a reservation is pending remains fail-closed before
advance and leaves its batch unchanged. A native reset after the old
reservation has consumed is allowed only under the existing reset lifecycle.
If that frame validates without a new pending reservation, it closes the old
batch; if it reserves again, the ordinary pending-reset exclusions apply. A
reset validation failure after consume cannot flush the old batch.

### Terminal and disposal

`Complete` rejects a pending generation or nonempty batch and writes no
terminal. Resolving the last generation on the final row flushes old rows,
writes the final row, and only then permits the terminal record. `Dispose`
eagerly drops the batch, pending/finalized evidence references, frame-local
scratch, and counters after disabling callbacks/native observation so a
rejected terminal does not retain the journal until garbage collection.

The terminal `complete` flag advances only after its terminal write succeeds.
A metadata, baseline, or terminal write failure follows the same fatal-output
policy as a frame write: no same-session retry and no canonical final.

## Output I/O and canonical no-replace publication

Generation rotation writes earlier validated rows sooner to the production
staging `StreamWriter`; it never writes a canonical path. The existing route
remains:

```text
FileMode.CreateNew sibling temporary
  -> BOM-free UTF-8 StreamWriter
  -> complete capture + terminal
  -> writer Flush + FileStream.Flush(true)
  -> link(temporary, final)
  -> delete temporary
```

If a direct frame write or generation flush throws before, during, or after a
logical line, the session is poisoned and must not retry. A generic
`TextWriter` may contain a prefix and cannot be rolled back. In production,
`StageAll` does not call `Publish`; it removes the temporary best-effort, and
the final path is absent. A pre-existing or racing final remains byte-for-byte
unchanged because `link(2)` refuses `EEXIST`. Cleanup failure may leave a
random-named noncanonical temporary. Directory fsync remains outside the
existing guarantee, so this is process-failure atomic/no-replace publication,
not formal power-loss durability.

Prebuild and preflight the possible new batch before flushing the old batch.
This ensures that after old bytes reach `output`, the only remaining expected
failure class is output/runtime I/O or an exceptional allocation/runtime
failure, all of which abort the whole staging transaction.

| Failure point | Required state and publication result |
|---|---|
| Semantic/native/reset/cap failure before commit | No current-frame or old-batch bytes are written; old batch and `lastRow` remain unchanged; session is faulted. |
| Current frame/journal materialization failure | No outer write occurs; session is fatal and the old batch remains available only for diagnostics/disposal. |
| Outer write fails before or during a generation flush | Generic writer may contain a prefix; session is poisoned, no state is advanced for retry, and production staging is discarded without a final. |
| Old flush succeeds but the current direct write fails | Staging may contain all old rows plus a current-row prefix; session is poisoned and the whole staging file is discarded. |
| Metadata/baseline/terminal write fails | Staging may contain an envelope prefix; capture fails and no final is linked. |
| Final `link(2)` sees `EEXIST` | The competing final is unchanged; the owned temporary is removed best-effort. |
| Cleanup fails | Preserve the original failure; a random-named noncanonical temporary may remain, never a partial canonical final. |

## TDD matrix

Use injected small limits for boundary tests; do not allocate production-sized
buffers or use RSS/GC thresholds.

| Area | RED/GREEN proof |
|---|---|
| Direct byte identity | A no-deferral capture and same-frame reserve/consume capture are byte-for-byte identical before/after, including LF and no BOM through the staged file. |
| One generation | Create at age zero, hold ages 0-4, consume on the next legal frame, and prove five rows flush once in row order with zero retained counters. Native continuation failure prevents a sixth held completion. |
| Cutoff-carried generation | Carry scalar identity/count through `BeginEpoch`, retain no pre-epoch evidence, hold at most four published frames, then consume and flush correctly. |
| Same-blocker relay | Consume A, close its child, re-reserve the same blocker as B in one validated frame. Prove A's earlier rows flush and the relay frame is the first/only held row for a distinct B id. Repeat for many generations without increasing peak counters. |
| Rotating-blocker relay | Consume/remove A's blocker, begin another kind-6 blocker, reserve B, and repeat. Peak held rows/chars/evidence remain one-generation bounded. |
| Multiple cycles in one frame | Consume/reserve A/B/C several times. Prior completed rows flush once only after full-frame validation; resolved current-row evidence stays ordered in the transaction; only final C owns the new batch. |
| Same-row evidence placement | Consume after earlier native records, then emit later native records. Same-row deferred evidence remains at action-12 correlation between those groups; it is not moved to `frame_end`. |
| Prior-row mixed evidence | Use multiple observations and multiple managed records in several held rows. Assert each prior-row evidence line is inserted immediately before its original `frame_end`, follows callback/record order, and is never assigned to the consume row. |
| Evidence merge adversaries | Reject regressing/evidence-outside-batch rows, wrong expected counts, leftover evidence, duplicate lines, and logical record-count mismatch before the first output write. |
| Semantic rollback | Consume A and reserve B, then fail a later native/managed/reset check. Assert output and old batch are unchanged, A is restored diagnostically, B disappears, next generation id and `lastRow` do not advance, and the session cannot claim completion. |
| Sequential deferrals | Finish A with no final pending reservation, verify all counters/batch are zero, then start B in a later frame and prove no bytes/evidence cross lifetimes. |
| Manifest string/shape bounds | Reject a 1,025-character manifest-derived raw string and a synthetic record shape whose maximum escaped form exceeds 65,536; accept exact limits. Exercise quotes, backslashes, controls, and non-ASCII. |
| Manifest read bound | Reject a manifest reported or observed above 1,048,576 bytes before JSON allocation. Race file growth between metadata inspection and read, feed invalid/truncated UTF-8, and assert the bounded stream read never retains more than the byte policy and no session is created. |
| Incremental line cap | For injected `limit-1`, exact limit, and `limit+1`, prove the bounded serializer fails before retaining/appending the extra character and faults the session. |
| Baseline aggregate bounds | Independently exceed active-service, pending-descendant, total-service, per-service transition, total-transition, and actual aggregate-character limits. Each rejects before baseline output. With injected small limits, exact boundaries succeed only when every cardinality and actual character charge also fits. Prove the 65,536 fixed-event-line bound is never cited as the baseline proof. |
| Baseline byte identity | For a bounded baseline containing active, pending, and ancestry arrays, the streaming aggregate serializer is byte-for-byte identical to the reviewed JSONL order/escaping/LF and makes one outer write only after the local envelope closes. |
| Callback-time cap | Exceed pending callback count, retained record count, base serialization, correlation augmentation, and evidence reservations inside `OnHook`; no oversized occurrence/JObject is enqueued and no frame/batch/output commit occurs. |
| Frame-character cap | `limit-1` and exact limit succeed; `limit+1` fails before journal append/output and leaves `lastRow` unchanged. |
| Held-frame cap | Inject 2: two frames succeed and the third fails without increasing batch count. Separately prove production 5/4 values derive from kind-6/new-vs-cutoff lifecycle, not a row constant. |
| Journal-character cap | Exact aggregate succeeds; one additional character fails before append. Generation rotation resets the charge for the new batch. |
| Evidence record/charge caps | Exact record count and actual serialized upper-bound charge succeed; the next action-11 callback fails before it or its records are retained. Many same-frame generations share the cumulative frame-local charge. |
| Pending reset/power | Reset, power, and combined input at every held age reject with no output/batch change. A fully validated reset after consume may close the batch; reset after a new reserve remains rejected. |
| Pre-epoch fatal failure | After `ObservePreEpochFrame` begins native capture/advance, inject native end failure, managed correlation failure, callback cap failure, and allocation/serialization failure. Then assert `ObservePreEpochFrame`, `BeginEpoch`, `CaptureFrame`, and `Complete` all reject without another API/advance/write; disposal disables once. A new session from a fresh host succeeds. |
| Pending terminal | `Complete` with a pending generation emits no terminal. Disposal clears counters. Resolve on the final row and assert old rows, final row, then exactly one terminal. |
| Journal/serialization failure | Inject failure while materializing the current string or finalized evidence. No outer write occurs, the old batch stays unchanged, and capture is fatal. |
| Direct output failure | Throw before the first character and mid-frame. The session is faulted, `lastRow` does not advance, and no same-session retry/terminal is accepted. |
| Generation flush failure | Throw before the first held row, within an evidence line, within `frame_end`, and while writing the current direct frame. The publisher creates no final and cleans its temporary when cleanup succeeds. |
| Envelope output failure | Throw during metadata, baseline, and terminal writes. The session is poisoned, terminal state never commits on a failed write, and publisher behavior matches frame-write failure. |
| No-replace success/race | Final remains absent during every generation flush and appears only after terminal and durable staging flush. A pre-existing/racing final remains unchanged. |
| Dispose | Dispose during pending, after terminal rejection, and after a poisoned write; callbacks/native disable once and every retained test counter returns to zero. |
| Existing contract suites | All native action-11/12 matrices, managed promotion/token+A7 tests, Java raw/cutoff/store/comparator tests, S2/S3 legacy vectors, and the bounded row-8775/12525/terminal proof gates remain green. |

The managed relay test fake must enforce continuation ages. The current fake's
unconditional `EndFrame` success is useful for corrupt-stream tests but cannot
prove the native cross-layer lifetime bound.

## Acceptance and implementation scope

The smallest implementation touches
`S1CompleteRunAudioReferenceCapture.cs` and its focused C# tests. It may add
small publisher-wiring tests but does not require native patch, manifest,
artifact lock, Java schema, comparator, or canonical fixture changes.

Acceptance requires the exact output-order and cap matrix above, fresh-process
failure behavior through `RunTraceCapture`, and byte-identical successful raw
output. A design or implementation that merely caps the current aggregate
journal, flushes inside the consume callback, retains finalized evidence across
generations, or claims rollback of an arbitrary failed `TextWriter` does not
satisfy this contract.
