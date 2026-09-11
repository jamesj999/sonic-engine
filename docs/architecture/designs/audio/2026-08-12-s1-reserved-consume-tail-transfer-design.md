# S1 Reserved Consume Tail-Owner Transfer Design

## Status and decision

This design corrects the Sonic 1 reference observer at real movie row 119247.
It extends the approved action-11/action-12 deferred-begin contract; it does not
replace it. The source reconstruction and exact failure are recorded in
`target/audio-parity/native/row119247-source-audit.md`.

Keep ABI version 3, every native structure size, all event kinds, the S1 raw
schema v1, and the Java complete-run schema unchanged. Split the one pending
reservation into:

- immutable **origin evidence**, captured by action 11 at M68K `$71B4C`; and
- a mutable **current blocking owner**, initially the same kind-6 service and
  transferred only by either exact configured action-4 tail below.

The only transfers are:

| Tail | Exact opcode | Old owner | Successor/current owner |
|---|---|---:|---:|
| Z80 `$0077` | `1A` | kind 6 | kind 2 |
| Z80 `$00C1` | `1A` | kind 6 | kind 3 |

The transfer emits no new event. Action 4 emits its ordinary cancellation
snapshot, kind-6 `SERVICE_END`, and adjacent successor `SERVICE_BEGIN`; after
that atomic transaction the reservation's current owner is the new service.
Action 12 at M68K `$71B82` later begins one kind-4 child under that current
owner and clears the reservation. Action 11's marker identity is never
rewritten to look as though it originated under kind 2 or 3.

This is not a route exception. It models the shipped program's bus-arbitration
state: the M68K has requested the Z80 bus, but the Z80 can enter a DPCM or Sega
PCM loop before the grant is observed. There is no retry-count condition,
movie-row condition, game-name branch in shared validation, recently closed
ancestry, wildcard tail, or generic permission for kind 6 to own children.

## Source-authentic sequence

The newly accepted physical sequence is:

1. Kind 6 is the active root after Z80 `$003A`.
2. One or more exact action-11 callbacks at M68K `$71B4C` emit marker value 4.
   The first creates the reservation; retries extend only its observation
   coordinates/count and must retain the same M68K A7 and return PC.
3. Z80 reaches either `$0077` or `$00C1` before M68K `$71B82`.
4. The selected action-4 hook atomically completes kind 6 and begins kind 2 or
   3 as a root. The reservation remains pending; its origin is unchanged and
   its current owner becomes the successor token.
5. The exact action-12 hook at `$71B82` begins kind 4 with
   `parent=currentOwner.token` and `depth=currentOwner.depth+1`.
6. Ordinary LIFO owns the M68K driver body through `$71C4C`. The async parent
   remains open or later completes under existing source hooks.

The existing no-transfer sequence remains valid: when `$71B82` occurs while
kind 6 is still current, action 12 begins kind 4 under kind 6 exactly as today.

## Native configuration contract

### Deferred family

Configuration derives one closed deferred family. It is invalid unless all of
the following are true:

1. ABI is 3 and there is exactly one action-11 reserve hook.
2. The reserve hook is M68K, has a nonzero target kind, has a nonzero expected
   blocker kind, and the blocker lacks `KIND_ALLOW_CHILDREN`. It has exact
   opcode proof and zero flags, range slice, predicate payload, and reserved
   bytes, as in the current contract.
3. Action-12 hooks all have the reserve target kind, the same M68K PC and exact
   opcode bytes, and zero flags/ranges/predicate/reserved bytes. Expected kinds
   are unique.
4. The expected-kind set for action 12 is exactly the origin blocker kind plus
   the successor kinds of configured action-4 hooks whose expected kind is the
   origin blocker. For S1 that set is exactly `{6, 2, 3}`.
5. There is exactly one origin action-12 hook for expected kind 6. For every
   transferred expected kind (2 and 3), the action-12 hook has exactly one
   action-7 observation partner with the same CPU, PC, opcode, and expected
   kind. The observation has service kind zero. No other same-PC/same-kind
   duplicate is legal.
6. Every transfer action-4 hook is Z80, has a distinct successor kind, is
   already exact-opcode/range validated, and its successor kind exists and has
   `KIND_ALLOW_CHILDREN`. No successor may equal the origin or target kind.
7. Every transferred consume kind has exactly one such tail and every such
   tail has exactly one transferred consume kind. The validator admits no
   additional transfer target, tail chain, or orphan consume route.

These checks use only the supplied typed config. The runtime never recognizes
`$0077`, `$00C1`, kind 2, kind 3, kind 6, Sonic 1, or row 119247 as constants;
those exact identities reside in the reviewed S1 manifest and are frozen by
manifest tests.

### Runtime hook selection at `$71B82`

The existing same-PC hook selection gains one narrow paired override:

- with a pending reservation whose exact current owner is the active top,
  choose the action-12 partner whose expected kind equals that owner kind;
- with no pending reservation, choose the action-7 observation partner for
  active kind 2 or 3;
- with any pending reservation whose token, kind, parent, or depth does not
  exactly match the active top and paired action-12 expected kind, select
  neither hook, emit neither a child BEGIN nor an action-7 marker, and record
  the exact `SERVICE` fault; and
- action 12 for origin kind 6 remains uniquely selected because it has no
  action-7 partner.

Pair selection precedes opcode proof, but does not weaken it: the selected
hook's complete opcode still must match. A second matching consume, a missing
reservation, an unpaired duplicate, or an observation substituted while a
matching reservation is pending fails closed.

## Native state and atomic transfer

The internal reservation has this conceptual shape; it is not exported ABI:

```text
DeferredBeginReservation
  origin             immutable trace_stack_entry from action 11
  currentOwner       mutable trace_stack_entry; initially origin
  reserveHookToken   immutable
  targetKind         immutable
  sourceCpu / pc     immutable action-11 proof
  pending
```

Consume hook identity is resolved from the configure-validated family by the
current owner kind; it must not be a single stored token tied permanently to
kind 6. `origin` is used for repeated marker validation and raw diagnostics.
`currentOwner` is used for transfer eligibility, pending chip/hook ownership,
consume parentage, cutoff, and reset/terminal validity.

### Exact transfer transaction

When action 4 is selected while a reservation is pending, native first checks,
without mutation:

- stack top equals `currentOwner` in token, parent, kind, and depth;
- the selected tail's expected kind equals `currentOwner.kind`;
- `currentOwner` is still the immutable origin (one transfer maximum);
- the tail belongs to the configure-validated family and its successor has the
  exact paired action-12 route for the reservation target;
- the ordinary action-4 parent/child legality still holds;
- a successor token is available without advancing the token cursor; and
- the entire completion-plus-begin group fits.

Range id 2 is one Z80 byte, so kind-6 completion is snapshot BEGIN, one CHUNK,
snapshot END, and service END. The successor BEGIN adds one. The transaction
therefore preflights exactly **five event slots**, derived from
`range_group_reservation(...) + 1`, before token allocation, event count,
stack, or reservation mutation.

On success it emits the ordinary four-event completion followed by successor
BEGIN. `SERVICE_END` and `SERVICE_BEGIN` are adjacent and ordered END then
BEGIN. Only after the fully prevalidated writes does it replace the stack top
with the successor and set `currentOwner` to that exact new entry. Origin and
pending state remain unchanged. No generic `pop_service` path may reject the
pending reservation halfway through this special transaction.

On one-short capacity, token exhaustion, proof failure, or identity failure,
the existing first fault/overflow accounting is recorded, but event count,
stack, token cursor, origin, current owner, and pending status are unchanged.
The implementation must use one transaction path rather than compensating
rollback after partial emission.

Action 12 independently preflights its one BEGIN slot and token availability.
It requires the exact active `currentOwner`, the action-12 expected kind for
that owner, and the immutable target kind. It emits the kind-4 child with a
fresh token, then clears the reservation only after the event and stack push
have succeeded.

## Projection and managed correlation

`CompleteRunAudioObserver` remains the sole managed-side topology authority.
Its `DeferredBeginReservation` mirrors immutable origin plus mutable current
owner internally. Projection recognizes a transfer only from an adjacent,
configure-proved action-4 `SERVICE_END`/`SERVICE_BEGIN` pair:

- END must complete the exact current owner;
- BEGIN must be the next ordinal and coordinate, use the same action-4 hook,
  PC, and source CPU, and preserve the ended entry's parent/depth while using
  the configured successor kind and a fresh token; and
- only after both events validate does projection rebind current owner.

All ordinary service builders and pending descendant behavior continue to be
updated by the existing action-4 lifecycle. A malformed transfer rejects the
projection transaction; `CommitProjection` publishes none of its scratch
state.

The S1 session's `DeferredManagedBegin` keeps:

- immutable origin marker token/parent/kind/depth, hook, CPU/PC, captured A7,
  return PC, observation count, and occurrence records; and
- mutable current-owner token/parent/kind/depth copied only from the same
  native adjacent transfer.

Action-11 evidence emitted later by `EmitDeferredManaged` continues to use the
origin token, parent, hook, ordinal, A7, and return PC. At action 12 the
contemporaneous callback must have the same A7 and return PC as the original
reservation, while the native child BEGIN must name the current owner as
parent and use current depth plus one. This holds in both prepublication and
published correlation.

`ManagedServiceTracker` stays identity-only. It stores only open kind-4
`ServiceToken + A7` entries. It does not add kind-2/3 entries or cache
parent/depth. Kind-4 observations match `ServiceToken + A7`; kind-2/3
observations match `ParentToken + A7` against their owning kind-4 service, as
already designed. Reservation current-owner state is a separate native-derived
correlation scalar, not a second managed topology engine. Boundary validation
continues to require exact set equality between native active kind-4 tokens
and managed tracker tokens.

## Raw diagnostics and unchanged schemas

### Existing shape and its trust boundary

`NativeDeferredServiceBegin.blocker*` remains immutable **origin** evidence.
Renaming or overwriting it with the successor would lose action-11 truth; adding
serialized `currentOwner*` fields would change the strict JSON object shape.
Neither is necessary.

Within an attached stream, the current owner and its transfer are losslessly
represented by existing data:

- in frame history, the origin service's action-4 end identity and the
  successor service's `beginFrame/beginOrdinal/beginHookToken/beginPc/source`
  prove END/BEGIN adjacency when the ordinary service record is released;
- an action-12 child BEGIN identifies the current parent token before the
  async parent is released; and
- at a cutoff, `CutoffNativeDiagnostics.activeStack().getLast()` is the
  producer-attested current owner, while `pendingDeferredServiceBegin` remains
  the origin.

Java therefore uses an internal state such as
`DeferredReservationState(origin, currentOwner, pendingTransferProof)` while
retaining the existing record and JSON codec. For a reservation first observed
inside the stream, `currentOwner` begins as the origin, changes only after exact
action-4 reconciliation, and may be provisionally identified by the action-12
parent until the corresponding ordinary successor BEGIN is released. For a
standalone/prepublication baseline, it is initialized from the exact legal
active-stack top as pinned-producer attestation, without fabricating a state
transition. A retained pending transfer proof must later reconcile exactly; it
cannot be forgotten merely because action 12 consumed the reservation.

There are two deliberately different validation authorities:

- A **standalone or prepublication baseline** starts after omitted history. It
  can validate the immutable origin plus a structurally and configurationally
  legal current top: either the exact origin, or a distinct open token with the
  origin's parent/depth, one of the exact configured successor kinds, and the
  configured action-4 successor hook/PC/source. Metadata must bind the record
  to the exact pinned native producer, observer identity, and observer proof.
  That is an attestation that the producer projected this origin/current state;
  it is not evidence that the omitted origin-token END directly preceded this
  successor BEGIN.
- A **stream-attached cutoff with retained validator history** has the native
  records that led to the cutoff. If the transfer occurred in that retained
  interval, it must prove the exact origin END followed immediately by the
  exact successor BEGIN. A stream continued from an already-transferred
  attested baseline validates exact carried-owner continuity and later consume,
  but never reconstructs causality that predates its baseline.

This is the trust boundary: exact native projection owns pre-epoch causality;
the standalone Java value and profile validators own structure, configuration,
and producer attestation only; the Java stream validator owns exact causality
only for history it has actually retained. Absence of a pre-baseline END/BEGIN
pair is therefore not a rejection condition.

The combined representation remains raw-sensitive. Origin diagnostics,
physical current-owner token/hook/PC/source, ordinary service records,
action-12 correlation, cutoff active stack, and producer identities participate
in raw JSON/storage roots. Two omitted histories that produce the same attested
baseline are intentionally indistinguishable. Semantic serialization excludes
the reservation and physical native identities, but still includes ordinary
semantic frontier topology; a raw-only identity change preserves the semantic
root, while a changed semantic kind/depth/topology does not.

### Java validator obligations

The stream validator must:

1. Retain the existing exact action-11 marker extension checks against origin.
2. For history observed after the baseline, accept origin completion while
   pending only when it is an exact configured action-4 transfer; remember its
   frame, END ordinal, hook, PC/source, old owner, successor kind, and expected
   successor token relation.
3. For that retained transfer history, require the eventual successor service
   to begin in the same frame at
   `endOrdinal + 1`, with the same hook/PC/source, the configured kind, preserved
   parent/depth, and a fresh token. If action 12 appears first, its parent token
   fixes the successor token that later proof must use.
4. Validate action-12 child parent/depth against current owner, not origin,
   while keeping the published `NativeDeferredServiceBegin` equal to the exact
   origin record plus consumed token/coordinate.
5. Include origin and derived or attested current owner in frame-to-frame
   equality. Raw identity changes alter the capture store's raw root. Semantic
   equality changes only when ordinary semantic topology changes.
6. At a standalone/prepublication baseline, require the exact carried origin,
   exact pinned producer/observer identity, and exact equality between the
   attested current owner and the last open active-stack service. Accept either
   the exact origin or the structurally legal one-hop configured successor;
   do not require an omitted predecessor record.
7. At a stream-attached cutoff, require exact equality with the validator's
   retained current owner. If the transfer occurred in retained history, the
   stateful profile validator must reconcile the exact tail route and adjacent
   END/BEGIN proof. If it predates an attested baseline, require carried-owner
   continuity rather than manufacturing predecessor causality.
8. Reject duplicate, regressing, colliding, missing, wrong-kind, wrong-token,
   wrong-hook, wrong-PC/source, nonadjacent, reset-interposed, and dangling
   transfer evidence when that evidence occurs in retained stream history.
9. Reject terminal while a reservation is pending or while any consumed
   transfer proof has not reconciled to its ordinary successor service.

Thus cutoff/store/comparator equality covers both identities without a schema
version or field addition. There is no schema contradiction unless
`currentOwner*` is required to be a standalone serialized property; this
design explicitly does not require that redundant representation.

## Cutoff, reset, abort, capacity, and terminal behavior

- A frame may end with either the origin kind 6 or transferred kind 2/3 as the
  current owner. Native and managed cutoff sidecars retain the immutable origin
  and exact current owner. Continuation aging applies only to real open service
  entries under existing kind limits.
- A standalone/prepublication baseline treats that pair as pinned-producer
  attestation. It rejects an illegal kind, token relationship, parent/depth,
  begin hook/PC/source, open state, or producer identity, but does not reject
  merely because the predecessor END/BEGIN occurred before the baseline and is
  absent. A stream-attached cutoff requires exact causality for a transfer that
  occurred in its retained history.
- Reset or power while any reservation is pending remains fail-closed, whether
  current owner is kind 6, 2, or 3. It does not synthesize a child, discard the
  reservation, or cancel only one of its identities.
- Abort while pending remains rejected and preserves first-fault diagnostics.
  Disable clears origin and current owner together. A failed projection or
  consumer callback commits no native/projection/managed publication state.
- Capacity-short transfer and consume behavior is atomic as specified above.
  No partial END-only topology or consumed-without-BEGIN state is observable.
- Managed `Complete` rejects any pending reservation/current owner. Java
  terminal validation also rejects an unresolved consumed transfer proof.
- The deferred-publication generation/bounds design remains orthogonal. A
  tail transfer neither consumes nor creates a generation; only action 12
  consumes it and a later action 11 creates the next generation.

## Rejected alternatives

- Clearing the reservation at `$0077/$00C1` loses the eventual kind-4 service.
- Beginning kind 4 at the tail invents M68K execution before `$71B82`.
- Rewriting action-11 blocker fields to the successor falsifies raw origin.
- Adding a transfer event kind or ABI field duplicates ordinary action-4 proof.
- Adding JSON `currentOwner*` fields changes a strict schema without adding
  information that ordinary topology and cutoff active stack already carry.
- Allowing every action-4 tail, any successor with children, or multi-hop
  transfer creates an uncited wildcard contract.
- Granting kind 6 `ALLOW_CHILDREN`, using overlapping roots, or using a
  recently closed token broadens ownership beyond the shipped schedule.
- Accepting the action-7 observation while a matching transferred reservation
  is pending silently drops the deferred consume.
- Requiring a standalone baseline to prove an origin-token predecessor event
  that predates the baseline invents history outside the serialized contract.

## Acceptance matrix

Acceptance requires RED/GREEN proof of all of the following:

- exact kind-6 to kind-2 and kind-6 to kind-3 five-slot transactions, including
  snapshot/END/BEGIN order, token cursor, immutable origin, and rebound owner;
- no-transfer kind-6 consume and transferred kind-2/kind-3 consume;
- exact-current pending action-12 selection, no-pending action-7 selection for
  kinds 2/3, and pending mismatch selection of neither with no marker/BEGIN and
  the exact `SERVICE` fault;
- malformed config families, ambiguous pairs, unpaired tails/consumes, wrong
  opcode, wrong owner/target, one-short capacity, token exhaustion, duplicate
  transfer/consume, forbidden M68K activity, Z80 ownership, reset, abort,
  disable, frame carry, cutoff, rollback, and terminal rejection;
- managed A7/return identity and identity-only tracker behavior in both epochs;
- Java attested standalone baseline structure, exact stream-attached
  origin/current reconciliation, cutoff round trips, raw/semantic/store
  equality, cross-frame consume-before-parent-release, and dangling terminal;
- existing action-11/12, action-8/9, row-8775, row-12525, S2, and S3K focused
  contracts unchanged; and
- the exact real configured-terminal gate. Row 119247 must no longer fail at
  `4:1:77:6:1:0:4`. The gate reports the next deterministic frontier and does
  not claim `Complete(225101)`, publication, or MATCH unless actually reached
  and independently verified.
