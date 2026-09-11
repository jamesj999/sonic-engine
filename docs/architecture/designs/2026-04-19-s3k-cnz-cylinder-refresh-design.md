# S3K CNZ Cylinder Refresh Design

## Goal

Refresh the `CnzCylinderInstance` design after the new solid-ordering work so the next implementation pass targets the actual remaining ROM drift instead of stale pre-fix assumptions.

The target remains ROM-accurate behavior for `Obj_CNZCylinder`, especially `sub_321E2` and `sub_324C0`, while keeping shared solid-pipeline cleanup available if refreshed cylinder regressions prove it is still required.

## Why This Refresh Exists

The earlier CNZ cylinder spec and plan were written before the current solid-ordering fix landed. That older design correctly identified several cylinder approximations, but it treated cylinder-local fallback behavior as likely evidence that the shared solid/contact pipeline was still the main blocker.

That assumption is no longer safe.

The branch now has new solid-ordering behavior and dedicated headless coverage around same-frame solid/contact publication. The cylinder refresh therefore needs to:

- re-evaluate the object against the current engine behavior
- remove stale “engine is probably still wrong” framing
- keep shared solid-pipeline cleanup in scope only when refreshed failing tests prove the cylinder can no longer solve the mismatch locally

## Current Findings

These findings reflect the current post-`d8b11ae30` branch state, not the earlier pre-solid-ordering draft.

### Design/Code Review Findings

The current `CnzCylinderInstance` is no longer a trivial stub. It already contains:

- split motion-family handling
- rider slot state for player and sidekick
- twist frame handling using the ROM tables
- directed CNZ headless tests that currently pass on this branch

However, it still contains several approximation seams that are too heuristic for a literal ROM port:

- mode `0` still hot-seeds `mode0Velocity` from the speed cap at init
- `canFallbackCapture()` uses narrow overlap logic as a rider-entry seam
- slot ownership is inferred from camera focus / first sidekick heuristics rather than a more literal ROM-shaped rider contract
- the circular route logic is still engine-shaped rather than fully locked down by literal quadrant-transition parity tests
- release behavior is still modeled around the current engine abstraction rather than the exact ROM branches

### Delegated Review Findings

Parallel reviews of the current code, tests, and disassembly identified these concrete drift points:

- mode `0` live velocity starts “hot” in Java, while the ROM only seeds the speed cap and leaves live velocity untouched at init
- rider entry still accepts heuristic contact paths that are not the intended primary ROM capture contract
- standing-loss and forced-release behavior are still too conflated
- twist-frame tables match, but surrounding state transitions for roll/radii/priority are still only approximate
- current tests are mostly smoke-level and do not yet lock exact early motion progression, exact rider-state seams, or exact twist/priority windows

## Scope

Included:

- refreshing the cylinder design to fit the post-solid-ordering engine state
- stronger TDD coverage for motion, rider entry/release, and twist/render-state parity
- cylinder-local refactoring toward a more literal `sub_321E2` / `sub_324C0` port
- shared solid-pipeline cleanup if, and only if, refreshed cylinder regressions prove it is still required

Excluded:

- unrelated CNZ traversal object work
- speculative shared-engine rewrites before the refreshed cylinder tests exist
- broad renderer refactors unless cylinder parity specifically proves they are needed

Implementation approach: `cylinder-first, engine-fixes-if-proven`.

## Decision Rule For Shared Engine Cleanup

Shared solid-pipeline cleanup is explicitly in scope, but it is not the default first move.

The implementation should assume:

1. The cylinder is still the primary source of remaining drift until refreshed tests prove otherwise.
2. Shared solid/contact pipeline work becomes justified only when stricter cylinder regressions still fail after the obvious cylinder-local heuristics have been removed, tightened, or replaced.

Shared engine work is justified if the refreshed cylinder tests show that:

- any one of the following conditions is still true after the cylinder-local seams have been removed, tightened, or replaced
- this is an `OR`, not an `AND`; a single proven blocker is enough to justify the follow-up
- the ROM main loop ordering in `loc_32188` remains the blocker: `sub_321E2`, then `sub_324C0` for each rider slot, and only then `SolidObjectFull`, so rider logic observes the standing bit that `SolidObjectFull` published on the previous frame unless shared publication timing is changed
- valid ROM-like capture still cannot happen without a `canFallbackCapture()`-style seam
- the ROM gate `btst d6,status(a0)` in `sub_324C0` cannot be expressed closely enough through the current standing-bit/contact publication flow
- release semantics still require non-ROM cylinder-local hacks that other traversal full-solids would also likely need

Shared engine work is not justified if the failure is still fully explained by cylinder-local state, motion, slot, or release logic.

## Refreshed Design

### 1. Motion Parity Slice

The first TDD slice should lock down the motion state that still differs materially from the ROM.

Required focus:

- mode `0` must not preload live velocity from the speed cap
  - ROM contract: init stores the vertical speed cap in `$3E(a0)` from `word_320E2`, but leaves live `y_vel(a0)` untouched at init; the live controller then starts from `0` and evolves inside `loc_32208` (`sonic3k.asm`, `Obj_CNZCylinder` init through `sub_321E2`)
- early-frame mode `0` behavior should be asserted more tightly than “it moved vertically”
  - ROM contract: `loc_32208` changes `y_vel(a0)` based on standing-mask transitions, current displacement from saved origin `$30(a0)`, and held up/down input, then snaps near-rest values to `0`
- representative circular subtype tests should assert literal quadrant evolution more directly than the current “visited both sides” smoke checks
  - ROM contract: `$3A(a0)` dispatches through `off_321EE` (13 entries): routine `0` runs `loc_32208` for the vertical controller, while routines `9-12` run `loc_323EC` for the square/circular family
  - ROM contract: `loc_323EC` mutates `$44(a0)` when the signed angle step crosses the low-half threshold, clamps `angle(a0)` back into the `$80-$FF` half, and then derives the square route edge from `$44(a0)` rather than a generic circular orbit

The object state should remain organized around explicit ROM-like motion fields:

- saved origin
- motion selector
- speed cap
- live vertical velocity
- route quadrant
- angle
- angle step

If a field only exists to preserve an approximation rather than a ROM state transition, it should be removed or replaced.

### 2. Rider Slot Parity Slice

The second TDD slice should target the capture/release seam directly.

Required focus:

- capture should be proven from valid standing-state conditions, not just broad overlap or side/push contact
  - ROM contract: entry is gated by `btst d6,status(a0)` at the top of `sub_324C0`; the rider slot only initializes when the object's standing bit for that player is already set
- the tests and implementation notes should explicitly record the `loc_32188` order dependency
  - ROM contract: `sub_324C0` runs before `SolidObjectFull`, so rider entry reads the standing bit from the prior `SolidObjectFull` pass unless the shared engine deliberately publishes a current-frame checkpoint earlier
- tests should distinguish:
  - valid capture entry
    - target ROM branch: slot-init branch after the standing-bit gate succeeds
  - no-capture on invalid contact paths
    - target ROM branch: pre-slot-init guard path where the standing-bit gate never opens
  - standing-loss behavior
    - target ROM branch: `loc_32604`, the standing-bit-clear path that clears slot state without reusing jump setup
  - jump-triggered release
    - target ROM branch: active-rider path observes jump input at `68059-68069`, sets jump/roll release state there, then falls through `loc_325F2 -> loc_32604` on that same frame to clear `object_control(a1)` and the rider slot
  - forced-release / invalid-rider-state behavior
    - target ROM branch: active-rider guard failures before the normal hold/update path continues
  - ROM contract: these are not one merged path. `sub_324C0` has a slot-init branch, an active-rider branch guarded by render/routine checks, a jump-state setup inside that active-rider path, and a distinct standing-bit-clear branch that clears slot state without reusing the jump setup
- player and sidekick slot behavior should remain independent and explicitly covered

The goal is to delete, replace, or narrowly justify the current heuristic seams:

- `canFallbackCapture()`
- slot inference from camera focus / first-sidekick assumptions
- release behavior that collapses multiple ROM cases into one engine-style branch

If the object still needs fallback capture after these tests are tightened, that becomes concrete evidence for shared solid-pipeline follow-up.

### 3. Twist And Render-State Parity Slice

The third TDD slice should keep the ROM twist tables but tighten everything around them.

Required focus:

- exact twist-frame sequence windows
  - ROM contract: `PlayerTwistFrames` is the exact 12-frame cycle
    - `$55, $59, $5A, $5B, $5A, $59, $55, $56, $57, $58, $57, $56`
  - ROM contract: `PlayerTwistFlip` remains coupled to that same 12-frame cycle
    - `0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0`
- priority changes across the twist cycle
  - ROM contract: active riders default to priority `$100`, then drop to `$80` only when the computed threshold byte `3(a2) = ((sin(angle)+$100)>>2)` is less than object byte `$35(a0)` (`cmp.b 3(a2),d0 / bls.s ... / move.w #$80,priority(a1)`)
- capture-state radii / roll / animation ownership rules
  - ROM contract: on capture, `sub_324C0` restores default radii and clears roll/in-air/push/roll-jump bits before active twist updates take over mapping-frame ownership
- visible cylinder frame cadence, not only initial frame selection
  - ROM contract: object `mapping_frame(a0)` still advances on the cylinder's own 4-frame idle loop via `anim_frame_timer(a0)` after `SolidObjectFull`
  - ROM contract: the SST starts zero-initialized, so `anim_frame_timer(a0)` begins at `0`; the first `loc_32188` pass decrements it, reloads `1`, and advances `mapping_frame(a0)` to `1`, which means a post-update cadence check should expect `1, 1, 2, 2, 3, 3, 0, 0` even though the pre-update initial frame is still `0`
- object byte `$35(a0)` must be sourced explicitly rather than hand-waved
  - design requirement: the implementation plan must define where the engine reads the `$35(a0)` equivalent from, and whether `CnzCylinderInstance` currently has any field/write path for that byte at all

The current object should not be treated as done merely because the frame table values themselves match the ROM. The surrounding state transitions matter just as much.

## Test Strategy

This refresh should remain headless-first and TDD-driven.

The refreshed tests should be added before production changes for each slice:

- motion-init / motion-family parity regressions
- rider-entry / standing-loss / release-path regressions
- twist / priority / visible-frame cadence regressions

Current tests that are only smoke-level should be strengthened rather than discarded.

Key principle:

- outcome-only assertions are not enough when the remaining disagreement is about literal ROM control flow

## Implementation Sequence

1. Refresh the implementation plan
   - split the work into motion, rider slot, and twist/render slices
   - explicitly mark shared solid-pipeline cleanup as conditional, not presumed

2. Execute TDD slices in order
   - motion parity
   - rider slot parity
   - twist/render-state parity
   - optional shared solid-pipeline cleanup if still proven necessary

3. Verify in layers
   - focused CNZ cylinder tests
   - related CNZ traversal tests
   - then full `mvn test`; if unrelated trace-replay failures remain, record the exact failing class names in the validation note instead of treating a generic trace exception as implicitly acceptable

## Files Expected To Change

Primary:

- `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`

Conditional shared-engine follow-up:

- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- related solid/contact-publication classes touched by the new ordering work
- `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`

Related dependent verification:

- `src/test/java/com/openggf/game/sonic3k/TestS3kCnzVisualCapture.java`
- `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`

## Success Criteria

This refresh is successful when the next implementation pass is forced to answer the right question:

- can the cylinder now pass stricter ROM-facing tests with a more literal object-local design?

And the resulting implementation is successful when one of these is true:

1. `CnzCylinderInstance` passes the refreshed parity tests without needing the current heuristic capture/release seams, or
2. a narrowly scoped shared solid-pipeline cleanup is proven necessary by refreshed failing cylinder tests and is covered by dedicated regressions

The stricter pass conditions should be literal, not outcome-only. At minimum, the refreshed implementation/test set should prove that:

- mode `0` init seeds the speed cap but not the live vertical velocity
- standing-bit transitions drive capture semantics through the ROM gate `btst d6,status(a0)` rather than through side/push/overlap heuristics
- no remaining code path captures a rider solely because broad overlap or fallback contact guessed that capture should happen
- standing-loss, jump-release, and forced-release behaviors remain distinct and map to the correct ROM branches, with jump release clearing the rider slot on the same frame as the jump input
- the full 12-frame twist cycle is observed as
  - `$55, $59, $5A, $5B, $5A, $59, $55, $56, $57, $58, $57, $56`
- the matching flip cycle is observed as
  - `0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0`
- the active-rider priority drop follows the ROM threshold rule instead of a hand-picked simplified split
- the visible cylinder frame cadence remains locked to the object's own idle animation loop, including the zero-initialized post-update sequence `1, 1, 2, 2, 3, 3, 0, 0`

Either way, the work should end with a cylinder design that is more literal than the current one and a plan that no longer relies on stale pre-solid-ordering assumptions.
