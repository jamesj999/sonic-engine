# Rewind Reference-Closure Guard Design

## Problem

Rewind compact capture assigns stable ids only to objects currently owned by
`ObjectManager`. A live object can retain a Java reference to a child that has
already unloaded, however. The removed child no longer has a rewind id, so the
next keyframe capture fails in `RewindCodecs.encodeObjectRef`.

The strict failure is correct: silently encoding the reference as `null` would
also hide required parent/child graph corruption. The coverage and field-
disposition guards cannot detect this class of bug because they classify field
policy statically; they do not observe object lifetimes.

The S2/S3K exposure began with the object-identity compact-capture rollout on
2026-06-18 (`a106dd27c`). Individual graph migrations then made previously
ignored references capture-visible. For the reported MHZ1 failure,
`Mhz1CutsceneButtonInstance.spawnedKnuckles` became captured on 2026-06-25
(`6a0188515`), while the actor's unload path did not clear that back-reference.

## Decision

Add a runtime rewind reference-closure validator that uses the same schema,
codecs, and identity context as real compact capture.

For every object that would contribute compact generic state to an
`ObjectManagerSnapshot`, every captured field that requires a rewind identity
table must encode successfully against the current live object and player set.
This includes direct references and references nested in the array, collection,
map, and supported plain-state-holder shapes already handled by the production
codecs. Records containing object references are not currently supported by the
compact schema and are outside this guard's scope.

Reference-closure violations are hard failures. They do not receive a baseline.

## Components

### Compact reference validation

`CompactFieldCapturer` will expose a focused validation operation for default
object-subclass schemas. The identity-bearing classification must be metadata on
the selected production codec/field plan, not a second validator-owned type
walker. Both focused validation and full compact capture will dispatch through
the same field plan and codec. A parity test will prove that they accept and
reject the same direct, array, collection, map-key, map-value, and supported
plain-state-holder references. The validator will reuse thread-local scratch
buffers and discard encoded output.

The normal compact-capture path will attach field and owner-instance context to
codec failures while preserving the original exception as the cause. Owner
context includes the rewind id when available, execution slot, and spawn. The
top-level captured field is always reported; container codecs may add an element
location, but exact nested paths are not required for closure correctness. For
example:

```text
Invalid rewind reference at
com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance#spawnedKnuckles
on com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance
[id=..., slot=..., spawn=...]:
RewindIdentityTable has no registered id for object reference
com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1Instance@...
```

### Object-manager closure check

`ObjectManager` will expose a read-only `validateRewindReferenceClosure()`
method. It will build the same identity context used by keyframe capture. Its
owner population will exactly mirror snapshot capture: placed objects plus
non-auxiliary dynamic objects, excluding objects routed through custom/legacy
capture overrides. Normal objects and badniks will use their respective
`GenericRewindEligibility` default-capture predicates through a shared helper
also consumed by production capture. That helper will additionally require
`CompactFieldCapturer.supportsDefaultObjectSubclassScalars(type)`, matching the
production fallback to generic non-compact capture when a default-eligible class
has an unsupported compact schema. A fixture will pin that fallback and prove
such a class is not independently closure-validated. Auxiliary objects remain
available as identity targets only when the production identity table registers
them; they are never independently validated as snapshot owners. It will not
capture a keyframe, mutate object state, or weaken ordinary capture behavior.

### Test and trace integration

Focused unit tests will prove:

- a live captured reference passes;
- a direct dangling reference fails with owner and field context;
- dangling references in supported arrays, collection elements, map keys, map
  values, and plain-state holders fail in parity with full compact capture;
- a reference marked transient/deferred is ignored;
- custom/legacy-capture and auxiliary objects are not validated as owners;
- removing an object through `ObjectManager` runs its cleanup before validation;
- normal compact capture and focused validation preserve the original identity
  exception as the cause.

One shared trace helper will invoke the closure check immediately after each
real engine-driven level frame and before comparison or an early exit. It will
be called from both frame-driving paths in `AbstractTraceReplayTest`: the general
S1/S2 loop and the separate `replayS3kTrace` loop used by S3K/MHZ. It will not run
for `VBLANK_ONLY` rows handled by `skipFrameFromRecording`, because those do not
advance level object state. Failures will add trace index, recorded ROM frame,
execution phase, game, zone, and act while retaining the closure failure as the
cause.

This observes only native engine state; it does not read diagnostic trace fields
or hydrate engine state. Consequently, existing S2 and S3K routes exercise real
spawn/unload sequences and expose the first frame at which an invalid edge
appears. Focused trace-integration tests will prove the shared helper is reached
by both the general and S3K driving paths.

The check belongs in the shared trace base rather than individual routes. It is
not gated by game, zone, route, or frame.

Special-stage traces without a level `ObjectManager` are unaffected. The helper
will use a null-safe level/object-manager lookup.

`HeadlessTestRunner` and shared frame drivers will not enable the guard
implicitly. Focused lifecycle tests will drive real frames and then call
`ObjectManager.validateRewindReferenceClosure()` explicitly, including an MHZ1
test that advances the actor through its natural self-delete/unload path.

## Repair policy

Failures exposed by the guard will be fixed at the smallest lifecycle owner:

1. If a parent retains a child that may unload independently, the child's
   `onUnload()` detaches itself from that parent.
2. If a reference is intentionally structural and restore already derives it,
   mark it transient and relink in `afterRewindRestoreSettled()`.
3. Required live parent/owner links remain captured and strict.

The reported MHZ1 actor follows rule 1: its `onUnload()` clears the button's
`spawnedKnuckles` field only when it still points to that actor. While both
objects are live, the bidirectional references remain captured exactly.

No global missing-reference-to-null fallback will be introduced.

## Validation and performance

The focused validator avoids serializing unrelated top-level scalar fields, but
each check builds a fresh identity table. Its cost is therefore
`O(live objects + identity-bearing payload)`, and a supported plain-state holder
may serialize unrelated members while its codec processes the holder. Schema
plans and scratch storage remain cached. A representative long S2 trace and long
S3K trace will be timed before and after integration. If the cost is material,
the validator will be optimized while remaining enabled in ordinary trace
replay; it will not become an optional check without a separate mandatory CI
profile that runs the full S2/S3K closure-validation sweep.

Verification will include focused validator and MHZ1 lifecycle tests, existing
rewind graph tests, S2/S3K trace replay tests, rewind coverage/field-disposition
guards, captured-policy compact-reachability tests, a no-baseline closure
fixture, and the S3K must-keep-green suite. Because the full trace sweep is used
to discover and prioritize closure failures, its exact command, commit context,
pass/fail/skip counts, and closure findings will be recorded in
`docs/status/trace-frontier-log.md`. Physics-frontier movement remains distinguished
from closure-only failures.

## Non-goals

- Automatically nulling missing references.
- Reflectively scrubbing every object when another object is removed.
- Synthetically removing every object in every graph; that creates invalid
  parent-first lifecycles and false positives.
- Changing ROM gameplay behavior or trace comparison tolerances.
