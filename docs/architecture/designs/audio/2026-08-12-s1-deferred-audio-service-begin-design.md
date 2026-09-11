# S1 Deferred Audio Service Begin Design

## Status and scope

This revised design is approved for the Sonic 1 complete-run reference observer.
It replaces the source-false release-at-blocker-END model after a clean Task 5
build reproduced a hook-proof failure at M68K `$71BB2` while kind 6 was still
the active root. It does not generalize the observer to overlapping service
sets, grant arbitrary child ownership to the Z80 sample-setup service, or
hydrate engine state from the trace.

The physical ordering is fixed evidence:

1. M68K kind-4 `UpdateMusic` ends at native ordinal 12, PC `$71C4C`.
2. Z80 kind-6 `zCheckForSamples` begins as a root at ordinal 13, PC `$003A`.
3. M68K reaches `$71B4C` three times with the same A7 `$FFFDB2` and return PC
   `$000B64` while kind 6 remains the root.
4. The successful retry acquires the Z80 bus and enters `.driverinput` at
   M68K `$71B82` while kind 6 remains physically open but cannot execute.
5. Kind 4 begins there, owns the M68K driver work through `$71C4C`, and ends
   before the scheduler resumes Z80.
6. Z80 later reaches `$0077`, where kind 6 tails into DPCM.

The three M68K callbacks are genuine bus-wait loop re-entries, not callback
duplicates. The previous kind-4 token is already closed, so neither
direct-parent retry nor recently-closed ancestry is truthful. The earlier
Task 4 claim that kind 6 ended before the new kind-4 begin was produced by an
unreproducible stale setup: its report retained no complete stdout, raw event
artifact, or managed executable/configuration identity. The clean frozen gate
is authoritative and the old terminal-green claim is retracted.

## Chosen model

Use one bounded reservation plus one exact consume hook. ABI-v3 action 11
reserves the begin at M68K `$71B4C` while expected kind 6 is the active,
non-child-bearing blocker. A paired consume action at the first accepted driver
instruction, M68K `$71B82` with opcode proof `4D F9 00 FF F0 00`, materializes
the service as a child of that same kind-6 token.

The first matching callback reserves the future service begin. Every matching
callback emits `EVENT_HOOK_MARKER` value 4 and leaves the active stack unchanged.
The reservation is keyed by blocker token, hook token, CPU, PC, opcode proof,
target service kind, and managed A7/return identity. Subsequent callbacks must
match every key and are coalesced into the same reservation.

At `$71B82`, native validates the reservation, exact blocker token, hook pair,
and capacity before mutation. It emits one ordinary `EVENT_SERVICE_BEGIN` for
kind 4 with a fresh token, parent equal to the kind-6 token, depth one, and the
consume hook/PC/source proof, then marks the reservation consumed. Existing
LIFO ownership is exact from that point: all M68K callbacks and chip writes are
owned by kind 4; `$71C4C` ends kind 4 and exposes kind 6 as the root again; the
existing `$0077` tail then ends kind 6 and begins DPCM. Parentage here records
temporal and ownership nesting across the hardware bus handoff, not a Z80 call
instruction.

## Native invariants

- Capacity is exactly one deferred reservation per observer session.
- The expected blocker kind must not have `ALLOW_CHILDREN`; only the unique,
  configure-validated reserve/consume pair may bypass that generic rule.
- Reserve and consume hooks are both exact M68K PC/opcode proofs, name the same
  blocker/target kinds, have zero snapshot/predicate/reserved payload, and are
  unique in one configuration.
- The reserve action has no stack mutation. The consume action pushes exactly
  one ordinary child and cannot be reused without a new reservation.
- While reserved, another matching `$71B4C` callback is permitted. Before
  consume, any other M68K audio hook/write fails closed. Z80 events owned by
  the blocker remain valid.
- Only the exact `$71B82` consume hook with the same blocker token may consume
  the reservation. Capacity failure emits no BEGIN and leaves stack, token
  allocator, and reservation unchanged.
- Abort preserves first-fault diagnostics. Disable clears the reservation.
- A frame may end with the blocker and reservation still pending. Continuation
  aging applies only to the real blocker service until consume; afterward both
  ordinary nested services age under existing rules.
- Cutoff diagnostics retain the reservation; semantic cutoff state does not
  invent an active service before release.

## Managed correlation

`S1CompleteRunAudioReferenceCapture` buffers every `$71B4C` callback. Marker
value 4 is a terminal raw correlation event for each callback. The first marker
stores A7 and return PC; later markers must match them exactly. Marker ownership
must match the active blocker token/kind/parent/depth, while its manifest hook
names target kind 4.

The `$71B82` consume hook has a contemporaneous managed callback. Its A7 and
return PC must match the reservation, and its ordinary SERVICE_BEGIN must be
adjacent to the consume proof with the exact blocker parent/depth. The emitted
managed evidence retains all physical retry callbacks plus the accepted-entry
callback and child token. Consumer rejection and later validation failure must
roll back both active service state and the reservation transactionally.

The managed service tracker is an identity correlator, not a second native
topology engine. It retains only each open kind-4 native token and its captured
A7. Native `CompleteRunAudioObserver` remains the sole authority for begin and
current parent/depth, action-8 promotion, ownership, reset ordering, and LIFO
validation. A kind-4 managed observation matches its exact `ServiceToken` and
A7; a kind-2/3 observation matches its exact current `ParentToken` and that
parent kind-4 token's A7. Retry and close operations remain exact-token
operations. At a publication boundary the managed token set must equal the set
of active native kind-4 tokens, with no duplicates or cardinality mismatch;
managed code does not compare or cache ancestry.

This split is intentional. At real row 523, native action 8 closes a kind-2
parent and event 11 promotes the surviving kind-4 child from `(parent=1,
depth=1)` to `(parent=0, depth=0)`. A managed tracker that freezes begin
ancestry becomes stale even though token and A7 identity remain exact. Copying
every native topology transition into C# would create a competing lifecycle
machine and another drift surface. The identity-only tracker retains the
independent managed proof without weakening native topology validation.

## Raw and canonical schema

Raw diagnostics gain a bounded `NativeDeferredServiceBegin` record containing:

- blocker token/kind/parent/depth;
- target service kind and hook token;
- CPU, instruction-start PC, first frame/ordinal, latest frame/ordinal;
- observation count; and
- whether it is still pending or was consumed at the exact `$71B82` coordinate
  by a specific child token.

Each frame retains its marker-value-4 events in the existing native correlation
chains. A buffered-native cutoff must include the pending reservation; OpenGGF
and callback producers must not emit this native sidecar. Raw/storage roots are
sensitive to every field. Semantic serialization and comparison exclude the
physical markers and reservation. After release, the ordinary canonical
DriverService begins at the release coordinate and participates in comparison
normally.

The stream validator retains at most one reservation across frames. It requires
all marker proofs in global native order, exact blocker continuity, an exact
consume proof followed by the ordinary nested BEGIN, and exact cutoff
carry/discharge. Missing, duplicated, forged, or dangling evidence is capture
failure. No producer-neutral semantic transition type is added: the resulting
parent/depth relationship is an ordinary DriverService hierarchy.

## Reset, failure, and cutoff behavior

A reset or power boundary while a deferred begin is pending is rejected. There
is no source evidence at row 8775 for translating the unbegun service into reset
state, so silently dropping or synthesizing it would be lossy. After consume,
existing top-down reset cancellation applies to the ordinary kind-4 child and
kind-6 parent. A movie cutoff may retain a pending reservation or the ordinary
nested active services, but cleanup may discard neither before immutable cutoff
diagnostics and digests are captured.

## Rejected alternatives

- Per-CPU overlapping roots replace the single-owner model across native,
  managed, cutoff, and comparator layers when exact LIFO already matches the
  bus-acquired interval.
- Observation-only suppression loses the new `UpdateMusic` lifetime.
- Recently-closed ancestry falsifies the proven physical order.
- Granting kind 6 `ALLOW_CHILDREN` permits source-invalid nested services.
- Beginning kind 4 at the first `$71B4C` retry steals Z80 chip ownership while
  the bus request synchronously lets Z80 run.
- Releasing kind 4 at kind-6 END reverses the real execution and leaves the
  intervening M68K driver body unowned.
- Fitting the observed retry count of three would fail another valid bus-wait
  duration; the bound is one reservation, not three observations.

## Acceptance

Acceptance requires native RED/GREEN matrices, managed transactional/correlation
tests, raw-schema round trips and adversarial stream validation, and an exact
real row-8775 proof of retry markers, `$71B82` nested BEGIN, `$71C4C` child END,
and later `$0077` parent tail. The gate must also probe QueueSound or a fresh
`$71B4C` between child END and parent tail rather than assuming none can occur.
Acceptance further requires a terminal reference-observer probe, unchanged
legacy S2/S3 semantic vectors, deterministic paired builds/installs, identity-
bound performance gates, and independent review. No capture is published merely
because row 8775 clears.

Managed acceptance additionally requires the real row-523 promotion sequence,
kind-4 and kind-2/3 token+A7 matches before and after promotion, forged token/A7
rejection, exact cutoff token-set equality, reset/power cancellation, malformed
rollback, and identical behavior in pre-publication and published epochs.
