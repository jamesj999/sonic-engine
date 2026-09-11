# S3K `Obj_WaitOffscreen` visibility survey, by role

Measured at `078593625`, 2026-08-21. Commissioned after the Jawz `$22` y-margin turned out to be
a fitted constant: survey the same defect **by role** rather than by the constant, because a site
spelling the same fudge differently would not appear in a search for `$22`.

## The role

Every engine site that models `Obj_WaitOffscreen` (`docs/skdisasm/sonic3k.asm:180271-180305`):
the routine parks the object on `loc_85AD2` behind a `$20`-square `Map_Offscreen` placeholder,
and `loc_85B02` restores the saved operation pointer once `render_flags` bit 7 is set.

Population: **28 sites**, found by role (`WaitOffscreen`/`waitOffscreen` in any form), not by any
constant. Three axes were checked independently.

## The ROM half, settled exactly

`Render_Sprites` (`sonic3k.asm:36336-36366`) is what sets bit 7, and it tests the object's own
`width_pixels`/`height_pixels` — `$20` for the placeholder:

- X: `d3 = x - camX + width; bmi` reject, then `d3 = x - camX - width; cmpi.w #320,d3; bge`
  reject. Visible iff `x >= camX - w` **and** `x < camX + 320 + w`. Right edge **exclusive**.
- Y: `d1 = (y - camY + height) & Screen_Y_wrap_value`, `d2 = 2*height + 224`, `cmp.w d2,d1; bhs`
  reject. Visible iff `y >= camY - h` and `y < camY + 224 + h`. Bottom edge **exclusive**.

That is exactly `CameraBounds.containsRenderSpriteBounds` — i.e. `isWithinRenderSpriteBounds`.
`isOnScreen(margin)` is `MarkObjGone`'s inclusive point test and is **one pixel more permissive**
on the bottom and right edges.

## Axis A -- margin other than `$20`

**One member, already known: `MantisBadnikInstance` (`$22`).** The by-role survey found no site
computing a margin, biasing one elsewhere, or testing a different box. The `$22` was a singleton
plus its copy. Mantis stays unlanded — see the frontier log.

## Axis B -- staging: live test vs retained post-camera flag

Ten sites retain a post-camera flag and read it on the next dispatch; eighteen test visibility
live inside their own dispatch. The obvious hypothesis is that the live shape releases a frame
early, because the ROM's bit 7 is published by `Render_Sprites` *after* the object pass.

**Measured, and the hypothesis is false.** `TestS3kHczZoneSliceTraceReplay` records the ROM's own
code-pointer transition for a live site: the TurboSpiker at slot 13 goes `0x00085AD2` ->
`0x00087BCA` on frame **16573** and `routine` 0 -> 2 on **16574**. The engine's live
`isOnScreen(0x20)` releases on 16573 and inits on 16574 — exact. The object pass reads the camera
bounds as of the previous camera step, so the two shapes are in the same phase. **Axis B is not a
defect class.**

## Axis C -- wrong helper for a render-flag test

Eleven sites use the inclusive `isOnScreen`/`isOnScreenX` for what is a `Render_Sprites` test.
Converting all eleven to `isWithinRenderSpriteBounds($20, $20)` is neutral across the entire
`-Ptrace-segments` sweep (70 classes) **except LBZ**, and isolating the change file-by-file
attributes the whole of that to **`Flybot767BadnikInstance`**: `TestS3kLbzZoneSliceTraceReplay`
7028 -> 4592 errors, first error unchanged at frame 411. That class already used the
render-bounds helper on its layout-placed arm and the inclusive one on its dynamic arm; the ROM
has one predicate, so the two arms disagreeing was the defect. Landed.

**The other ten are ROM-correct and unmeasured, so they are not landed.** No trace moves for
them; a green sweep proves the fixture, not the change. They are listed here ready to apply, one
line each, with the citation above:

`BatbotBadnikInstance`, `BloominatorBadnikInstance`, `BubblesBadnikInstance`,
`CaterkillerJrHeadInstance`, `ClamerObjectInstance`, `MonkeyDudeBadnikInstance`,
`SparkleBadnikInstance`, `SpikerBadnikInstance`, `TunnelbotBadnikInstance`,
`TurboSpikerBadnikInstance`. `RhinobotBadnikInstance` is an eleventh of the same shape but tests
X only (`isOnScreenX`) and has no render-bounds equivalent, so it needs a helper first.

## Population

| Site | staging | wake predicate |
|---|---|---|
| `ClamerObjectInstance` | live | inclusive |
| `IczFreezerObjectInstance` | retained | render-bounds |
| `IczHarmfulIceObjectInstance` | live | render-bounds |
| `IczSnowPileObjectInstance` | live | render-bounds |
| `Sonic3kSSEntryRingObjectInstance` | live | render-bounds |
| `BatbotBadnikInstance` | live | inclusive |
| `BlastoidBadnikInstance` | retained | render-bounds |
| `BloominatorBadnikInstance` | live | inclusive |
| `BubblesBadnikInstance` | live | inclusive |
| `BuggernautBadnikInstance` | live | render-bounds |
| `CaterkillerJrHeadInstance` | live | inclusive |
| `CorkeyBadnikInstance` | retained | render-bounds |
| `Flybot767BadnikInstance` | retained | render-bounds |
| `JawzBadnikInstance` | retained | render-bounds |
| `MantisBadnikInstance` | retained | render-bounds |
| `MegaChopperBadnikInstance` | retained | render-bounds |
| `MonkeyDudeBadnikInstance` | live | inclusive |
| `OrbinautBadnikInstance` | live | render-bounds |
| `PenguinatorBadnikInstance` | live | render-bounds |
| `PoindexterBadnikInstance` | live | render-bounds |
| `RhinobotBadnikInstance` | live | inclusive |
| `RibotBadnikInstance` | retained | render-bounds |
| `SnaleBlasterBadnikInstance` | retained | render-bounds |
| `SparkleBadnikInstance` | live | inclusive |
| `SpikerBadnikInstance` | live | inclusive |
| `StarPointerBadnikInstance` | live | render-bounds |
| `TunnelbotBadnikInstance` | live | inclusive |
| `TurboSpikerBadnikInstance` | live | inclusive |

`live`/`render-bounds` rows are correct on both axes. `retained`/`render-bounds` rows are equally
correct — the two staging shapes are in the same phase (axis B). Only the `inclusive` column
marks a site as a member of axis C.

## Controls

`-Ptrace-segments` 70 classes, `-Ptrace-replay` 800 tests and `-Pguards` 500 were run in a matched
pair of worktrees at `078593625`. Segments differ only in the LBZ row; replay messages are
identical; guards 500/0. `TestS3kFlybot767Badnik` 13/0.
