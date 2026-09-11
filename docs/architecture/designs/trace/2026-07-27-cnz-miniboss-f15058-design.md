# CNZ Miniboss Frame 15058 Design

## Problem

Standalone CNZ first diverges at frame 15058 when the engine negates Sonic's
X, Y, and ground velocities while the ROM retains their signs. Sonic's
position, subpixels, status, camera, and input agree before the response.
Existing diagnostics show the response is the standard S3K enemy/boss rebound
from the CNZ miniboss coil. The response itself is correct for an overlap.

The overlap is engine-only. At the frontier the ROM miniboss parent/coil is at
X `$3337` in parent routine `$06`, while the engine parent/coil is at `$3334`
in parent routine `$08`. The owner is therefore an earlier
`CnzMinibossInstance` state-machine cadence departure, not Tails, terrain,
CNZ cylinders, player control, or shared collision response.

## Design

Drive the production CNZ replay and compare the live miniboss parent's routine
and X position with the committed ROM `object_state` stream from arena release
through frame 15058. Temporary diagnostics may expose this timeline but must
not remain in the final change. The first mismatched handoff determines the
native owner.

Add a focused behavior test for that handoff using the ROM routine's own
counter, callback, and object-slot semantics. Verify the test fails before
changing production code. Correct only the earliest object-local cadence error
in `CnzMinibossInstance`, citing the corresponding S&K-side disassembly.

Do not delay or suppress the coil collision, alter shared touch response, flip
velocity signs directly, hydrate from trace data, or introduce a frame, route,
or zone predicate. The frame-15058 overlap must disappear naturally because
the miniboss parent reaches the ROM position and routine at the ROM time.

## Validation

Run the focused RED/GREEN test, CNZ miniboss and scenario suites, rewind
coverage, standalone CNZ replay, and the non-LBZ AIZ, MGZ, and CNZ complete-run
canaries. If the standalone frontier moves, update `CHANGELOG.md` and
`docs/status/trace-frontier-log.md` with before/after error count, first-error
frame/field, commands, branch, and commit context.
