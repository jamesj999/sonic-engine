# S3K CNZ Cylinder ROM Parity Design

## Goal

Port `CNZCylinder` to match `Obj_CNZCylinder`, `sub_321E2`, and `sub_324C0` in the S3K disassembly as closely as the engine can express, using the ROM as the source of truth. The preferred implementation strategy is:

1. Port the object literally first.
2. Change shared engine behavior only when the current architecture provably blocks ROM semantics.
3. Avoid cylinder-specific hacks that merely mask architectural inaccuracies.

## Scope

This pass is scoped to `CNZCylinder` only.

Included:

- Full subtype motion-family parity from `sub_321E2`
- Full rider-control/twist parity from `sub_324C0`
- Headless regression coverage for all key motion and rider-control seams
- Minimal shared-engine changes if required for correct ROM semantics

Excluded:

- Broader CNZ object parity work
- Live-runtime visual verification as a completion gate
- Opportunistic refactors unrelated to cylinder parity

## Current State

The current object is no longer using the worst earlier approximation, but it still diverges materially from the ROM in several places:

- Motion mode `0` is not a literal port of the ROM vertical velocity controller.
- High-nibble subtype handling is simplified and does not preserve the ROM distinction between the amplitude/speed fields.
- Circular/quadrant motion is approximated rather than following the ROM route-quadrant evolution.
- Rider state is modeled as an engine-oriented capture abstraction rather than the ROM’s per-player local slot layout.
- Twist rendering and priority are only partially ROM-like.
- Entry currently uses a solid-contact path plus a narrow fallback instead of a fully disassembly-driven standing-bit contract.

## Selected Approach

We are taking approach `1`: a literal object port first, with minimal engine change only where the existing architecture blocks correct semantics.

Rationale:

- The remaining known mismatches are still mostly object-code mismatches.
- A shared-engine rewrite before the literal object port would increase risk without first proving the object itself is no longer the main source of divergence.
- Continuing to patch the current approximation would move the engine away from the project’s accuracy standard.

## Object Design

`CnzCylinderInstance` should be reorganized around ROM state, not around the current synthetic gameplay abstraction.

Target object state:

- Base position fields corresponding to ROM saved origin
- Motion selector field corresponding to `$3A(a0)`
- Standing-mask cache / velocity state required by mode `0`
- Speed/amplitude fields corresponding to `$3E(a0)` and `$46(a0)`
- Route quadrant field corresponding to `$44(a0)`
- Angle state matching the ROM’s angle byte/word usage
- Two rider slots matching the ROM per-player local state shape at `$32(a0)` and `$36(a0)`
- Visible mapping frame / animation timer fields matching the ROM’s 4-frame idle animation cadence

The implementation should favor direct translation of the disassembly logic into readable Java helpers, rather than “cleaned up” abstractions that hide the ROM state transitions.

## Motion Parity

`sub_321E2` should be ported as distinct motion families, not merged into a generic movement routine.

Required behavior:

- Mode `0`: vertical motion using the ROM’s standing-mask transition logic, `y_vel` acceleration/deceleration, held up/down influence, and near-rest snap-to-zero behavior
- Modes `1`-`4`: horizontal sine displacement families using the exact ROM shift/amplitude relationships
- Modes `5`-`8`: vertical sine displacement families using the exact ROM shift/amplitude relationships
- Remaining modes: circular/quadrant route movement with the ROM quadrant mutation and angle clamping behavior

This means preserving the ROM distinction between:

- The speed cap / mode-0 vertical controller sourced from `word_320E2`
- The angle-step field derived from subtype high nibble and object facing

If the existing engine representation cannot express one of these distinctions cleanly, the representation should change rather than collapsing the behavior.

## Rider-Control Parity

`sub_324C0` should be ported with ROM semantics, not approximate player handling.

Required behavior:

- One rider slot per player
- Entry when the player is standing on the cylinder and not already in controlled/ineligible state
- Capture clears motion and restores default radii exactly as in the ROM
- Twist updates use per-rider angle, horizontal distance, and priority-threshold state
- Twist rendering is object-owned through the ROM `PlayerTwistFrames` / `PlayerTwistFlip` tables
- Release occurs on jump or invalid rider state (including hurt/dead/routine-equivalent invalidity)
- Release cleanup returns mapping-frame ownership and render priority to the player systems

The cylinder should continue to own coarse front/back layering through player priority buckets while acknowledging that exact renderer interleave is a separate engine concern.

## Solid Ordering Concerns

This must be recorded because it affects not only `CNZCylinder`, but ROM accuracy more broadly.

### What the ROM Does

The ROM processes:

1. Object motion update
2. Object-specific rider logic
3. `SolidObjectFull`

within the object’s own update flow. That means each object can react to standing/contact state that was established for that same object in the same frame.

### What the Engine Currently Does

The current engine exposes inline solid-contact callbacks, which is better than a fully deferred pass, but the overall object/update/solid model is still not a perfect mirror of the ROM. The current notes and code indicate:

- Some collision state is still observed from an engine-owned contact model rather than from direct object-local standing bits
- Not every object sees exactly the same same-frame dataflow the ROM sees
- Several historical fixes have compensated with proximity or fallback seams rather than architectural parity

### Concern

If `CNZCylinder` still requires object-local fallback rules after a literal object port, that is a sign the engine’s solid ordering or contact publication model is still too approximate.

That concern should be treated as architectural, not as a cylinder-specific nuisance.

### Threshold For Engine Work

Shared engine changes are justified if, after the literal cylinder port:

- the object still cannot use same-frame standing/contact state to enter and maintain the rider state correctly, or
- rider Y placement / release semantics still require cylinder-specific correction that the ROM gets “for free” from inline solid resolution, or
- the same mismatch pattern appears in other moving full-solid traversal objects

Shared engine changes are not justified if the remaining problem is still fully explained by an incorrect cylinder port.

## Testing Strategy

This pass should be TDD-driven and headless-first.

New or updated tests should cover:

- Mode `0` vertical controller behavior
- Representative subtype coverage for each motion family
- Quadrant evolution for circular routes
- Capture from valid standing contact
- Dual-rider slot separation
- Twist-frame ownership and priority-bucket changes
- Release on jump
- Release on invalid rider state
- Initial visible mapping frame parity

If a shared-engine change is made, add targeted tests for the underlying solid-ordering/contact-publication behavior so the architecture change is justified by accuracy, not by convenience.

## Files Expected To Change

Primary:

- `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`

Potentially, if architecture proves to be the blocker:

- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/level/objects/SolidObjectListener.java`
- other shared solid-contact / frame-step classes directly involved in same-frame contact publication

## Risks

- A literal port may expose existing inaccuracies in shared solid ordering that were previously hidden by approximations.
- Shared engine changes in the solid pipeline can affect other traversal objects and must therefore be covered by targeted regressions.
- Over-normalizing the ROM logic into “nicer” abstractions is a real accuracy risk.

## Success Criteria

This pass is successful when:

- `CNZCylinder` motion paths follow the disassembly rather than the current approximation
- Rider-control flow matches the disassembly contract closely enough that no cylinder-specific heuristics are needed beyond what the architecture genuinely requires
- Focused headless parity tests pass for motion and rider-control behavior
- Any shared-engine changes are justified by ROM accuracy and covered by dedicated tests
