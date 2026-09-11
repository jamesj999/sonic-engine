# Survey: which objects publish `render_flags` bit 7 a camera step early

Surveyed at `b480ca4b8`, after
[the ARZ bubble generator fix](../../../status/trace-frontier-log.md) moved the
S2 ARZ2 segment frontier from row 1015 to 2175 by reading that flag in the ROM's
frame phase.

**Nothing landed.** Two ROM-cited conversions of `BubbleObjectInstance` were
built and both measurably regressed a fixture the ARZ2 segment cannot see. The
survey, the already-correct population, a stale-test verdict and a broken
helper contract are the round's output.

## The engine already owns this, and most objects already use it

`ObjectInstance.refreshPostCameraRenderState()` is the sanctioned publication
point: `LevelFrameStep` calls it at step 7, *after* the frame's camera step
(`LevelFrameStep.java:531-532`), which is where the ROM's `BuildSprites` sits --
after `RunObjects` and after `DeformBgLayer` publishes the camera copies
(`s2.asm:5094-5110, 15178-15179`). An object that instead assigns its flag from
inside `update()` is publishing at step 3, before that camera step, so its
verdict is taken against a camera one deform older than the one `BuildSprites`
would have used.

Classified by the *enclosing method* of each `= isWithinRenderSpriteBounds(...)`
assignment, not by field name -- the field is spelled at least eight different
ways (`romRenderOnScreen`, `romRenderFlag`, `renderOnScreen`,
`renderedOnPreviousFrame`, `drawnLastFrame`, `placeholderRenderedOnscreen`,
`waitPlaceholderRenderFlag`, `movementEnabled`), so any single-identifier survey
understates the population. A first pass of this survey reported "six classes"
from one field name; the real assignment set is 29 sites in 25 classes.

**Publishing in the post-camera hook (correct):** `GrounderRockProjectile`,
`GrounderWallInstance`, `OOZLauncherObjectInstance`, `RisingPillarObjectInstance`
(x2), `BreakableWallObjectInstance`, `CollapsingBridgeObjectInstance` (x2),
`IczFreezerObjectInstance`, `Sonic3kCollapsingPlatformObjectInstance`,
`BlastoidBadnikInstance`, `CorkeyBadnikInstance`, `Flybot767BadnikInstance`,
`JawzBadnikInstance`, `MantisBadnikInstance`, `MegaChopperBadnikInstance`,
`RibotBadnikInstance`, `SnaleBlasterBadnikInstance` -- plus
`Sonic1BubblesObjectInstance` and `LostRingObjectInstance`, which reach their
assignment through a helper the hook calls.

**Publishing from inside `update()` (candidates):**
`Sonic1CaterkillerBadnikInstance`, `Sonic1CaterkillerBodyInstance`,
`HCZWaterWallObjectInstance` (two sites), `SpikerDrillObjectInstance`,
`OrbinautBadnikInstance` (`movementEnabled`, semantics differ -- read the
[wake-phase survey](2026-08-21-s3k-wait-offscreen-wake-phase-survey.md) first),
and `BubbleObjectInstance`. `Sonic1BubblesObjectInstance` also calls its helper
from three points inside `update()` as well as from the hook, so it is mixed.

`BubbleGeneratorObjectInstance` evaluates its bounds at the top of `update()`
rather than in the hook. That is behaviourally identical for a stationary
object -- the object pass and the previous frame's post-camera step see the same
camera -- but it bypasses the shared owner and should be moved onto it as a
no-value-change refactor, not as a fix.

## The Obj1F reds are stale tests, not a family member

`CollapsingPlatformObjectInstance` was already converted, to
`isPreUpdateWithinRenderSpriteBounds`, with the same ROM citation this survey
derives independently (`s2.asm:5095-5111, 15178-15179`). The three reds in
`TestSonic2ObjectBugFixes`
(`collapsingPlatformFragmentFallUsesApproximateRenderHeight`,
`...KeepsVerticalOnlyOffscreenParentForCpuSlotRefresh`,
`...DeletesUsingFallingParentY`) are written against the pre-conversion
implementation: the third reflects on a field `verticalOnlyOffscreenTicks` that
`3f0fd4a70` removed, and errors with `NoSuchField` rather than failing an
assertion. Same lever, and the tests were left red beside the fix rather than
inverted with it. **Not inverted here.** Rewriting a red assertion so it passes
needs its own authority and its own verification of the new expectations against
the ROM; that is a commission, not a tidy-up.

## The blocker: `hasPreUpdateSnapshot()` does not mean "first frame"

Its javadoc says it is "False on an object's first frame", and callers modelling
`render_flags` rely on that to reproduce a setup row that seeds the bit SET.
Measured on `TestS2Arz2LevelSelectTraceReplay`: on a bubble's **first**
`update()` it is already `true`, for all 54 bubbles the segment creates, because
`snapshotPreUpdatePosition()` / `snapshotTouchResponseState()` run for
mid-frame-created children before their first pass. The seeded branch never
fires, so an object created off screen is destroyed on its creation frame
instead of surviving one execution. Any conversion onto that helper which
depends on the seeded value inherits this.

## What was measured, and why nothing landed

Control at `b480ca4b8`: `DebugS2Arz2Seg13CompleteEmeraldsSegmentTraceReplay`
9130 errors, first error frame 2175; `TestS2Arz2LevelSelectTraceReplay` passes
(surefire `tests="1" errors="0" failures="0"`).

| `BubbleObjectInstance` conversion | ARZ2 segment | ARZ2 level select |
|---|---|---|
| control (publishes inside `update()`) | 9130, first 2175 | pass |
| `isPreUpdateWithinRenderSpriteBounds` | 9130, first 2175 | **230 errors, first 670** `obj_s34_slot` 0x34 vs 0x33 |
| `refreshPostCameraRenderState` hook | 9130, first 2175 | **83 errors, first 1314** `obj_s2E_slot` 0x2E vs 0x11 |

Both are regressions and neither landed. Two things this pins down:

1. **The ARZ2 segment cannot measure this change.** A shadow probe carrying both
   evaluations found 28 rows where they disagree, so the path is live and the
   models genuinely differ -- and the segment's error list is byte-identical
   across the change, because it compares no field that bubble lifetime reaches.
   The level-select trace compares per-slot occupancy (`obj_sNN_slot`), which is
   exactly the field an earlier delete moves. Inertness measured on the fixture
   you happen to have is not inertness.
2. **A green fixture broke under a ROM-correct change**, which is the signature
   of a compensating pair rather than of a wrong citation. The control's
   early-published flag and something else are cancelling; the next round on this
   class should find the partner before republishing the flag, and remove both in
   one move.


---

## Follow-up at `64baf3d3e`: measured from the consumer, and the flag is not the whole story

The previous section guessed at a compensating pair from the shape of the
regression. This measures it from the delete itself, against the recording,
which is the ground truth the earlier attempts never consulted.

### The engine's two camera phases are exactly what the ROM argument assumes

Logged `cameraBounds` at both publication points on `arz2` (level select) and
compared with the recorded `camera_x`:

| row | object pass | post-camera hook | recorded `camera_x` |
|---|---|---|---|
| 399 | -- | 1047 | 1047 |
| 400 | 1047 | 1048 | 1048 |
| 401 | -- | 1050 | 1050 |

The object pass at row N sees `camera(N-1)`; the hook at row N sees
`camera(N)`. So the hook reproduces the ROM's own pair -- `BuildSprites` runs
after `DeformBgLayer` within frame N (`s2.asm:5094-5110`), judging the
post-`ObjectMove` position against `camera(N)`, and the pass that reads the flag
at N+1 sees that same camera. The engine wiring is not the problem and the
citation is not wrong.

### And the recorded removals say the opposite

`Obj24` bubbles delete on a clear `render_flags` bit 7 at `loc_1F988`
(`s2.asm:45265-45266`; `_btst`/`_beq` assemble to `tst.b`/`bpl`, so it is the
same bit-7 test). Every engine delete was logged with its driver row and scored
against the recording's 66 `object_removed` events for type `0x24`:

| arm | deletes landing on a recorded `0x24` removal frame | fixture |
|---|---|---|
| control (publishes inside `update()`) | **51 of 54** | passes |
| post-camera hook | **16 of 54** | 83 errors |

The hook moves roughly 35 of the 54 deletes exactly one frame earlier, and the
recording wants them where the control already has them. Three control frames
(738, 842, 1711) are the pre-existing mismatch and are not this change's.

### What that leaves

The stale flag is cancelling a second one-frame offset somewhere else in the
bubble's delete path -- the pair the fixture's green depends on. It is not the
slot reaper: destroyed objects are removed inside the same object-execution loop
that ran them. Publishing the flag correctly therefore has to land together with
whatever that second offset is, in one move; it has now failed twice from two
different sanctioned mechanisms, which is evidence about the pair rather than
about the mechanism.

**This does not reach the generator fix.** That object is stationary, its
consumer is a countdown rather than a delete, and it was confirmed against three
independent recorded quantities. But it does mean the family cannot be converted
on the phase argument alone: each class needs its consumer scored against a
recorded stream before its flag is moved, exactly as the delete was here.


---

## Resolved at `d8386e181`: the partner was the queue guard, not a delete-path offset

The follow-up above predicted a second one-frame offset "between `setDestroyed`
and the observable slot free". It is not there. Probing the bounds test at each
delete showed every one failing by hundreds of pixels rather than by a marginal
crossing, so the delete frame is decided by *how many passes a bubble survives*,
not by which camera judged it.

`refreshPostCameraRenderState` runs over `dynamicObjects` at step 7, including a
bubble allocated during that same frame's object pass which has not executed yet.
The ROM's `BuildSprites` only rewrites `render_flags` bit 7 for objects in the
sprite queue, and `Obj24` reaches `DisplaySprite` only at `loc_1F988`
(`s2.asm:45265-45267`) on a pass that survived the flag test -- so an unexecuted
object keeps `Obj24_Init`'s `$84` (`:45209`). Without that guard the hook judged
bubbles that had never drawn and killed every off-screen one a pass early.

Publishing inside `update()` is wrong by one camera deform; the missing guard is
wrong by one pass the other way; the two cancel. Landed together they reproduce
the control's delete rows exactly and the level-select trace passes.
`BubbleGeneratorObjectInstance` already carries the same guard as
`romDisplayedLastPass` -- it was load-bearing there too, and it is the second
half of any conversion in the remaining five.


---

## `hasPreUpdateSnapshot()` at `47f6014cb`: the contract was wrong, and there is no single ordering rule

Commissioned as "look for the single ordering rule before writing two patches".
There isn't one, and the reason is worth more than the rule would have been.

**The contract is what was wrong, not the behaviour.** `preUpdateValid` means
"a frame-start position snapshot exists". `ObjectManager` sets it deliberately on
a mid-update child the moment that child is registered into a slot the frame has
not reached yet (`ObjectManager.java:2696-2700`), precisely so the touch and
solid helpers have a frame-start position for the pass the child is about to
run. That is correct for its real job. The javadoc's claim -- "False on an
object's first frame" -- was never true, and the ROM's answer is a different fact
entirely: `BuildSprites` only rewrites `render_flags` bit 7 for objects that
queued through `DisplaySprite` that frame, so an unexecuted object keeps its
setup-seeded value. That is a per-object *execution* fact, not a snapshot fact.
The javadoc is corrected in place; the behaviour is left alone.

**The population is one caller, and it is unmeasurable here.**
`hasPreUpdateSnapshot()` has exactly one caller in the tree,
`WallTurretShotInstance:150`, and it was written on the doc's promise -- its own
comment reasons that "a missing snapshot must not be read as 'not drawn'" because
`ObjB8`'s `subObjData` row seeds `render_flags` with
`1<<render_flags.on_screen` (`s2.asm:74763`). So the guard it relies on does not
guard, and the shot is latently destroyable on its creation frame. It is not
fixed here: neither the shot nor its parent `WallTurretObjectInstance` executes
in either ARZ2 fixture -- a probe in each `update()` produced zero lines, with the
parent probe as the positive control -- so there is nothing to measure the change
against. Population brought rather than a fix, and it needs a fixture that fires
a wall turret.

**No single rule, and that closes nothing for free.** The correct mechanism is
object-local and already exists twice: a flag set where the object's own routine
reaches its `DisplaySprite` equivalent, consumed by
`refreshPostCameraRenderState()` (`BubbleGeneratorObjectInstance` and
`BubbleObjectInstance`, both as `romDisplayedLastPass`). Nothing shared needs
changing, which also corrects my own speculation from the previous round: this
does *not* close part of the remaining five, and each still needs its own
conversion with both halves.

**A separate population, not surveyed here.** The ten callers of
`isPreUpdateWithinRenderSpriteBounds` do not go through `hasPreUpdateSnapshot()`
at all -- they inherit its `preUpdateValid &&` prefix, which reads a missing
snapshot as "off screen". Any of them judged on its own first pass with a
setup-seeded bit has the same latent shape. That is a different question from
this commission and wants its own round.


---

## The three Obj1F reds at `cf5373d00`: two were a missing frame-step call, one is a real orphan

Commissioned as an inversion with a revert-first proof, and decided by the lead
that replacements must assert through the object's real path rather than through
reflection. The proof ran, and it changed the verdict on two of the three.

**They were not encoding the old behaviour. They were never driven properly.**
`3f0fd4a70` moved `Obj1F_FragmentFall`'s delete rule onto
`isPreUpdateWithinRenderSpriteBounds`, which returns `preUpdateValid && ...`.
A unit test that calls `platform.update(...)` directly never gets the frame-start
snapshot `ObjectManager` supplies every frame, so `preUpdateValid` is false, the
helper returns false, and the fragment is destroyed **unconditionally** whatever
its position. Two of the three reds are that and nothing else. Adding
`platform.snapshotPreUpdatePosition()` -- the object's own per-frame contract,
already used a dozen times elsewhere in the same test class, so this is the real
path and not more reflection -- makes both pass with their **original assertions
unchanged**. No assertion was inverted; the harness was completed.

**The revert-first proof, and what it says about each.** Reverting
`CollapsingPlatformObjectInstance` to its pre-`3f0fd4a70` form in place (the file
is otherwise untouched since, so the pre-image is exact) and rerunning:

| test | vs pre-conversion class | vs current class | verdict |
|---|---|---|---|
| `...DeletesUsingFallingParentY` | **RED** | green | discriminates; repaired and kept |
| `...UsesApproximateRenderHeight` | green | green | does **not** discriminate; repaired and kept, but it is not evidence for the conversion |
| `...KeepsVerticalOnlyOffscreenParentForCpuSlotRefresh` | green | **RED** | a correct test of removed behaviour |

Reporting the middle row matters as much as the first: a repaired test that passes
against both trees proves nothing about the fix, and claiming the proof for it
would be exactly the thing the proof exists to prevent.

**The third stays red, with the reason written into the test.** It asserts the
two-tick `verticalOnlyOffscreenTicks` grace that the conversion deleted on
purpose. It cannot be repaired by supplying the snapshot, because it is green
against the old class and red against the new one -- it is testing behaviour that
no longer exists. A first attempt to rewrite it to the new contract failed on its
own first tick, which is the tell that the expectation was being invented rather
than derived. Deleting it would discard the only record of what the grace was
for. It keeps its original assertions and carries a javadoc saying all of this.
Red count for the class goes 2 failures + 1 error to 1 failure.

**Generalisable.** A conversion that moves a decision onto manager-supplied
per-frame state silently breaks every unit test that drives `update()` directly,
and it breaks them in a way that reads exactly like a test encoding the old
behaviour. Check whether the harness supplies the frame step before concluding
that an assertion is stale.
