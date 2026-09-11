# Live Rewind Object Regression Repair Design

## Problem

Live gameplay rewind has regressed for Sonic 2 objects. Broken monitors can remain broken after rewinding to a frame where they were intact. EHZ Masher fish can resume from the wrong position, and other badniks can similarly desynchronize after restore.

The affected behavior crosses two layers of the object rewind system:

- Layout objects such as monitors combine per-instance state with `ObjectManager` placement and remembered-spawn state. Recreated objects can lazily initialize after restore and must observe the remembered state from the restored frame, not the later live frame.
- Badniks combine base position/velocity fields with subclass-owned authoritative movement state. A rewind restore must leave both representations synchronized before gameplay resumes.

## Scope

This repair targets normal live gameplay rewind. Trace Test Mode is not the reproduction target, but its rewind tests remain regression coverage because it consumes the same object snapshot machinery.

The fix will preserve the current snapshot format and generic recreate architecture. It will not migrate every legacy object override or redesign the rewind subsystem.

## Design

### Monitor root-cause isolation

The monitor repair will begin with a production-path break → rewind reproduction, not a predetermined restore-order change. The test must initialize the monitor, capture an intact frame, execute the real break path (including remembered state and spawned contents), capture the broken frame for diagnosis, then restore the intact frame through the same `ObjectManager` adapter used by live gameplay.

The test will compare the intact snapshot, restored object state, placement state, active/dynamic membership, and first resumed render/collision state. The implementation will change only the first seam proven divergent: per-object compact capture, recreation/reuse selection, remembered placement restoration, child cleanup, or render-state invalidation. Any manager ordering change must additionally prove that it performs no restore-time spawn, unload, or update and that an immediate recapture remains equivalent.

### Badnik state synchronization

Badniks with hand-written rewind state will restore the authoritative movement container and the base/render position and velocity fields to the same captured values. The repair will address the shared legacy restore seam where possible. Object-local handling is acceptable only where a subclass owns a genuinely unique authoritative state representation.

The implementation will first identify the user's “Snapper fish” against the EHZ object registry and document whether it resolves to the EHZ Masher (`0x5C`) or a distinct class. That concrete object must be included in the regression matrix. It will then inventory concrete badniks that override the legacy context-free `captureRewindState()` / `restoreRewindState()` pair and identify which hold a second authoritative movement container. The existing legacy dispatch contract will remain compatible. A shared post-normalization/context-routing change is permitted only if a failing test proves a shared invariant is violated; otherwise the fix remains object-local. Masher-specific code is permitted for its unique `SubpixelMotion.State`, with at least one non-Masher legacy badnik such as Buzzer or Coconuts retained as a negative control.

No zone, route, or frame-specific condition will be introduced.

### Compatibility

Existing snapshots, compact field policies, object identities, slot allocation, and recreate markers remain unchanged. The repair must not hydrate engine state from trace data.

## Testing

Tests will exercise the production `ObjectManager.rewindSnapshottable()` path:

1. Initialize and capture an intact Sonic 2 monitor, execute its real player-driven break path, then restore the intact snapshot through both forced-recreation and in-place-reuse variants. At least the variant matching the reported live failure must fail before the fix. After restore, the monitor must be intact and solid, broken-frame contents/explosion children must not remain, and the immediate recapture plus first resumed frame must match the intact control state.
2. Capture a moving Masher with non-zero subpixel state, advance/mutate it, restore, and run one resumed update. Position, velocity, subpixel phase, and subsequent movement must match a control object advanced from the captured state.
3. Inventory legacy badnik rewind overrides and exercise at least one non-Masher representative through capture, mutation, restore, immediate recapture, and multiple resumed frames. Base/render fields, subclass state, active membership, and trajectory must remain synchronized.

Each test that reproduces a confirmed reported defect must be observed failing before its production change. Inventory and negative-control tests may pass before the fix and must not be used to justify unrelated production edits. Verification will include focused tests, rewind round-trip and coverage guards, relevant Sonic 2 object tests, and the broader rewind/trace-replay suite where runtime permits.

Mandatory verification comprises the new focused tests, relevant Sonic 2 monitor/Masher/badnik unit tests, `TestRewindCoverageGuard`, `TestStaticStateRewindCoverageGuard`, `TestRewindArchitectureGuard`, and the applicable rewind round-trip tests. The full `*TraceReplay` sweep is best-effort because it may depend on local ROMs and runtime; if it cannot run or fails for unrelated existing reasons, the exact command and outcome must be reported.

## Success Criteria

- Live rewind restores Sonic 2 monitors to intact state before their destruction frame.
- Masher resumes at the captured position and follows the same subsequent trajectory.
- At least one non-Masher legacy badnik remains synchronized across immediate recapture and multiple resumed frames.
- Monitor placement bits, active/dynamic membership, and visible/collision state match the captured intact frame without orphaned break children.
- Rewind coverage and architecture guards remain green.
- No unrelated working-tree changes are modified or committed.
