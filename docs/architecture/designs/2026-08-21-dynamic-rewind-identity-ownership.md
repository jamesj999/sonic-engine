# Dynamic rewind identity ownership

## Status

Investigation result from 2026-08-21. The retention defect is reproduced, but
direct pruning is **not approved**: collision-response lists retain object
references beyond membership in the manager's live collections, and one
destroyed-object cleanup path bypasses the initially proposed removal method.
No runtime change is authorised or included with this document.

## Reproduced problem

`ObjectManager.removeActiveObject()` prunes the removed instance from
`rewindObjectIds`, but dynamic removal paths do not consistently retire the
identity. A temporary package-private count seam made the lifetime observable.
Adding and removing one dynamic object left one identity entry: the focused
class ran 15 tests with exactly one intended failure, `expected: <0> but was:
<1>`.

A companion test captured before removal, removed the object, restored the
snapshot, and recovered the same `ObjectRefId`. That test passed and confirms
that ordinary active/dynamic snapshot entries carry historical identity. It
does not prove that every other runtime holder has released the removed object.

## Why immediate pruning is unsafe

`ObjectCollisionResponseList` owns two ordered runtime views:
`previousObjects` and the partially constructed `currentObjects`. The object
manager removes an instance from `dynamicObjects` independently of those views.
`abortCurrentBuild()` explicitly preserves a partial list as rewind-visible
state, and `captureRewindState()` encodes both lists through
`rewindObjectIds::get`. Its encoder throws if any retained publisher has no
identity.

A dynamic object can therefore leave the manager's live collection while a
collision-response list still owns it. Pruning in
`removeDynamicObjectInstance()` would turn a valid capture seam into
`collision response publisher has no rewind identity` and could also lose the
ordered previous/current list state required by restore.

The initial removal-path inventory was also incomplete.
`cleanupDestroyedDynamicObjects()` calls `Iterator.remove()` directly after
unload/notification and never enters `removeDynamicObjectInstance()`. A change
limited to that method would leave the retention defect on destroyed children
cleaned up after a parent unload.

## Current ownership map

| Holder or access | Lifetime implication |
|---|---|
| `activeObjects` / `dynamicObjects` | Primary execution and per-object snapshot membership |
| `previousObjects` collision view | May outlive dynamic membership until the next completed collision build |
| Partial `currentObjects` collision view | May outlive dynamic membership across an aborted build and is rewind-visible |
| `rewindObjectIds` | Supplies IDs to all three holder groups and currently keeps retired instances strongly reachable |
| `ObjectManagerSnapshot` entries | Carry IDs after capture; sufficient for ordinary active/dynamic recreation |
| Fresh restore table | Resolves snapshot-owned IDs during two-phase restore |

The retention defect is real, but “not in active or dynamic collections” is not
the correct retirement predicate.

## Required follow-up design work

Choose an explicit owner for an identity that remains referenced by a collision
view. Candidate mechanisms must be evaluated rather than selected from source
shape alone:

- retire an ID only after the object has left active/dynamic membership and
  both collision views;
- give collision views explicit identity leases that release on reset,
  completed-build replacement, and restore; or
- move stable identity onto a non-retaining object-owned or snapshot-owned
  representation while preserving identity lookup for collision capture.

Whichever mechanism is chosen must centralise every successful dynamic
retirement path, including `removeDynamicObjectInstance()` and
`cleanupDestroyedDynamicObjects()`, without reusing the monotonic ID or changing
object execution/removal order.

## Tests required before selecting a fix

The next design must first make these current lifetimes executable:

1. publish a dynamic responder into `previousObjects`, remove it from the
   manager, capture, and restore the exact previous-list identity and order;
2. publish into a partial `currentObjects` build, abort the build, remove,
   capture, and restore the build cursor/stage and identity;
3. destroy a dynamic child and retire it through
   `cleanupDestroyedDynamicObjects()`;
4. remove a dynamic object that was never published and prove its identity can
   become unreachable; and
5. capture/remove/restore an ordinary dynamic and recover its snapshot ID.

The cross-game graph tests and both rewind guards remain mandatory, followed by
the full baseline/development/merged-suite comparison from `AGENTS.md`.

Raw red-test and access-map evidence is retained under
`$AGENT_SCRATCH_ROOT/tasks/performance-candidate-validation-20260821T161822Z-3319778-904a0080/rewind-identity/`.
