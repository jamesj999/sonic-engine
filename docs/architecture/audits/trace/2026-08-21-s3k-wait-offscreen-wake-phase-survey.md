# Survey: which S3K objects can wake a frame early behind Obj_WaitOffscreen

Follow-up to
[the LBZ frame 411 attribution](2026-08-21-lbz-frame-411-touch-pass-phase.md),
which fixed one member of this class. Surveyed at `53cf33da0`.

**Result: the class is closed at one member.** `Obj_Flybot767` is the only S3K
object that can reach the defect, and it is fixed. Nothing to land, and nothing
to record ready-to-apply.

## First, a correction to the population

The earlier report said there were "six other `Obj_WaitOffscreen` callers" and
listed `sonic3k.asm:128225, 134031, 182275, 182373, 182698, 183323`. That was a
`grep | head` truncated to ten lines and read as the whole result. **There are
50 call sites**, one per routine, and the six named are simply the first six:
`loc_6167C`, `Obj_CNZWaterLevelCorkFloor`, `Obj_Bloominator`, `Obj_Rhinobot`,
`Obj_MonkeyDude`, `Obj_CaterKillerJr`.

Same hazard as an empty grep, in the other direction: a truncated result reads
as a complete one, and the number it produces is a fact about the pager.

## What the defect actually requires

From the fixed case, two conditions must hold together:

1. The object's **first** `Obj_WaitOffscreen` pass runs in the frame it was
   created -- so a live "am I on screen" test can succeed before any draw has
   published `render_flags` bit 7.
2. It is created **already on screen** -- otherwise the live test fails, the
   object waits, and by the time it is drawable it has existed for many frames,
   at which point the live test and the published flag agree.

They agree for a long-lived object because the engine's object pass (step 3 of
`LevelFrameStep.execute`) runs before that frame's camera step, so its live
bounds come from the end of the previous frame -- exactly what
`refreshObjectPostCameraRenderState` published at step 7 of the previous frame
("Cache BuildSprites on-screen results for next frame's logic",
`LevelFrameStep.java:531-532`).

Layout placement satisfies neither condition: the placement window is wider than
the screen, so a layout object is created off screen. That is why the Flybot's
layout arm was correct and its alarm arm was not.

## The ROM answer

S3K installs objects by direct code pointer -- there is no `ObjID_` table in
`sonic3k.asm` (zero occurrences) -- so `move.l #Obj_X,<dest>` is the complete
install idiom. Taking **every** such write anywhere in the ROM (316 distinct
objects) and intersecting with the 50 `Obj_WaitOffscreen` callers gives exactly
one name:

```
Obj_Flybot767
```

Its install site is `sonic3k.asm:57071`, inside `sub_2949C`, and the two lines
around it are the whole explanation:

* `sonic3k.asm:57069` -- `jsr (AllocateObjectAfterCurrent).l`, which takes a slot
  **after** the current object, so `Process_Sprites` reaches it later in the same
  frame's walk. Condition 1.
* `sonic3k.asm:57072-57074` -- the new object is seated at `Player_1`'s `x_pos` /
  `y_pos`. Condition 2.

Every other waiter is only ever reached as a layout-placed object, so neither
condition can hold for it. The class has one member in the ROM, not merely one
member in the engine.

## Engine cross-check, by role and per branch

33 engine classes model an `Obj_WaitOffscreen` wait. Grouped by how they gate the
wake:

* **On the published render flag** (a `refreshPostCameraRenderState` override
  plus a persistent flag field): Blastoid, Corkey, Flybot767, Jawz, MegaChopper,
  Ribot, SnaleBlaster, IczFreezer.
* **On a live bounds test** in the wake pass: the remaining 25.

Those 25 are *latent-safe rather than correct-by-construction*: they are safe
because nothing spawns them dynamically, not because their gate models the ROM's
phase. If any of them ever gains a dynamic spawn route, it acquires this defect
the same day.

The engine has exactly one id-based dynamic creation of any waiter,
`LbzAlarmObjectInstance.java:161` -- the fixed one. Three other apparent
cross-references are false positives and are recorded here so the next survey
does not re-chase them:

| Apparent | Actually |
|---|---|
| `SpikerBadnikInstance` inside `TurboSpikerBadnikInstance` | substring of `TurboSpikerBadnikInstance` |
| `ClamerObjectInstance` inside `MegaChopperBadnikInstance` | a comment naming a test |
| `IczFreezerObjectInstance` inside `IczEndBossInstance` | the nested `FrozenPlayerBlock` record, not a waiting object |

A fourth would have been added to that table: the grep classification above
counts `OrbinautBadnikInstance` as using a published flag because it names a
**local variable** `placeholderRendered` that it computes live. It belongs in the
live-bounds group. Counts derived from identifier names need one read each before
they are trusted.

## The survey had a positive control, and needed it

The first intersection returned **empty** -- no members at all, including the one
already fixed and known to be a member. The cause was that `sonic3k.asm` has CRLF
line endings, so `comm` was comparing `Obj_Flybot767\r` against `Obj_Flybot767`.

An empty survey result is indistinguishable from a survey that cannot match
anything. What made it distinguishable here was that a member was **already
known**, so "no members" was checkable rather than merely plausible. A survey for
further members of a defect class should always be run so that the known member
must appear in its output; if it does not, the survey is broken, not the class
empty.

## Standing risk

None outstanding. The remaining exposure is a future object gaining a dynamic
spawn route, and the guard for that already exists in the shape of
`TestS3kFlybot767Badnik.alarmSpawnedAndLayoutWaitsWakeOnTheSamePhase` -- an
equivalence test between the two spawn routes. Any object that grows a second
spawn route wants the same test.
