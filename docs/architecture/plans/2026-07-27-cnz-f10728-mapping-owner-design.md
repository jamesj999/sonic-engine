# CNZ f10728 mapping-owner design

## Goal

Advance the standalone CNZ trace beyond frame 10728 by modelling the ROM
owner that replaces the cylinder's final twist mapping with ordinary player
animation after release.

## Established state

At f10727 ROM has released Sonic from cylinder object control and retains
`anim=$00`, `mapping_frame=$59`. At f10728 ROM retains `anim=$00` and publishes
`mapping_frame=$08`. The engine matches position, velocity, status, routine,
and animation ID at f10728, but keeps `$59` while its animation timer is
running. This isolates the divergence to release-to-animation ownership rather
than geometry, physics, or final-state synchronization.

## Investigation and ownership

Trace the native cylinder release and player animation calls in execution
order through `Obj_CNZCylinder`, `sub_324C0`, the player object, and the
relevant `Animate_Sonic` script. Compare that order with
`CnzCylinderInstance`, `AbstractPlayableSprite`, and
`PlayableSpriteAnimation`.

Use committed trace rows and disassembly first. If they do not distinguish
whether `$08` is written by an animation reset, timer expiry, or another
object pass, capture one bounded native execution/write probe around
f10727-f10728. The probe is diagnostic only and will not ship.

## Implementation boundary

Add a real-behavior RED at the narrowest confirmed owner. It must distinguish
the release row, which retains `$59`, from the next ordinary animation pass,
which publishes literal `$08`. Then make one semantic correction at that
owner.

Do not add a visual correction, expected-trace writeback, frame predicate,
zone predicate, or final mapping assignment. Do not change shared animation
suppression unless disassembly and the native execution order prove a shared
contract. The excluded route remains outside inspection, execution, and
target selection.

## Verification

Run the focused RED/GREEN test, neighboring cylinder and animation tests, CNZ
scenario checks, frontier-only and canonical standalone CNZ comparisons, and
rewind coverage when production state changes. Run standalone AIZ and MGZ as
explicit non-LBZ canaries. Record the prior and new first divergence, canonical
error count, native evidence, worktree context, and commands in the audit,
frontier log, and changelog. Commit all required artifacts for review without
pushing.
