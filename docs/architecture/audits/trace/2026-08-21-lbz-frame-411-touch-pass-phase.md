# LBZ frame 411: the Flybot767 wakes a frame early

Attribution round for `TestS3kLbzZoneSliceTraceReplay`, whose first error is

```
Totals: 4592 errors, 0 warnings.
First error: frame 411 -- y_speed mismatch (expected=0x0000, actual=-0100)
```

Measured at `f7a3ba7101f4de7882f349c592381096091710cf`, re-measured at
`458e92ad3`. Attributed, not fixed.

**This document was revised.** Its first version concluded that the engine's
player touch pass ran a frame late against a same-frame badnik position -- a
shared-path ordering defect. That was wrong, and the section below says how the
error was made. The engine's touch phase is ROM-correct. The real defect is a
one-frame-early wake in a single object.

## Read this first: the physics CSV frame column is HEXADECIMAL

`StoredPhysicsFrameDomain.scan` defaults to `FrameEncoding.HEXADECIMAL`
(`src/main/java/com/openggf/trace/StoredPhysicsFrameDomain.java:38`), so the
`frame` column of `physics.csv` is base 16 and the domain is contiguous from 0.

* The row labelled `0411` is frame **1041**.
* Frame **411** is the row labelled `019B`.

The failure mode is vicious rather than merely annoying. Row `0411` of this
fixture already holds `player_y_speed=FF00` -- the engine's value. A lane that
reads that row sees expected and actual agreeing while the comparator reports a
mismatch, concludes the comparator is inverted, and goes hunting a comparator
defect that does not exist. Anyone reading a fixture by hand hits this.

The right row, `019B`, holds `player_y_speed=0000` and matches the divergence
report's ROM context exactly (`gfc=019C`, `cam=02A9,05CC`, `rings=3`, `anim=09`,
`map=86`). `FF00` first appears one row later, at `019C` = frame 412.

Aux JSONL `frame` fields are decimal and share this domain, so an aux row
`"frame":412` lines up with physics row `019C`.

## What frame 411 is

The player is idle on the ground at `(0346,062C)` while an LBZ Flybot767 dives
into him. `-0x100` is the `Touch_KillEnemy` bounce, enemy-above arm: `subi.w
#$100,y_vel(a0)`.

Both sides agree the badnik dies. They disagree by one frame about when:

| | ROM | engine |
|---|---|---|
| badnik killed, `y_vel -= $100` | frame 412 | frame 411 |

The ROM side is read out of the recording, not inferred. Aux `object_appeared`
places the Explosion (slot 7), Animal (slot 10) and Points (slot 11) all at
frame **412**, all at `0x0359,0x0619`, the same frame `player_y_speed` becomes
`FF00`.

## The badnik's trajectory is one frame ahead

Recorded `object_state` for slot 7 (`object_code 0x0008C96C` = `Obj_Flybot767`)
carries the approach: `0365,060D` @405 ... `035B,0617` @410, `0359,0619` @411.

The engine produces the same *values* in the same order, one row earlier. It
ends row 410 at `0359,0619`, where the ROM ends row 410 at `035B,0617`.

Two sequences of identical values, offset by one, look identical to any check
that does not pin both to a measured index. Establishing this needed the drive
index printed alongside the engine's own frame marker -- see the measured
section below.

## What the ROM does, and why 412 is the only answer it can give

* `TouchResponse` is called from the tail of `Obj_Sonic`
  (`docs/skdisasm/sonic3k.asm:22018-22022`, `loc_10C7E`).
* `Process_Sprites` walks `Object_RAM` upward from its first slot
  (`docs/skdisasm/sonic3k.asm:35963-35995`), and that first slot is `Player_1`
  (`docs/skdisasm/sonic3k.constants.asm:303-304`).
* `Touch_Loop` stores object RAM **pointers**, not snapshots, and dereferences
  `x_pos(a1)` / `y_pos(a1)` live at Sonic's execution time
  (`docs/skdisasm/sonic3k.asm:20655-20681`).

Sonic therefore scans before any badnik's slot has run that frame, and reads
every object's **end-of-previous-frame** position. The flybot only holds `0619`
at end of 411, so the ROM's scan can first see it on 412. That is the whole
explanation of the ROM's timing, and it holds for every object, layout-placed
or dynamically allocated, with no exceptions.

## What the engine does, measured

Probes in `Flybot767BadnikInstance` (creation, the wait-offscreen arm, and each
movement pass), `AbstractObjectInstance.snapshotTouchResponseState`, the
touch-scan coordinate selection in `ObjectTouchResponseController`,
`applyEnemyBounce`, `LevelFrameStep.execute` (a per-frame marker), and
`AbstractTraceReplayTest` (the drive index and compared row). Each run was
checked for `COMPILATION ERROR` and for a nonzero probe-line count before its
output was read, and the failure text was confirmed unchanged so the probes are
non-perturbing.

The per-frame marker is what mattered. With frames labelled and the drive index
printed, the order is unambiguous:

```
FRAME 411 inline=true
  SNAP flybot live=(035B,0617)
  SCAN flybot sidekick=false pre=true ... used=(035B,0617)
  SCAN flybot sidekick=true  pre=true ... used=(035B,0617)
  MOVE flybot -> (0359,0619)
  DRIVE idx=410 row=019A expYSpd=0000 engYSpd=0000
FRAME 412 inline=true
  SNAP flybot live=(0359,0619)
  SCAN flybot sidekick=false pre=true ... used=(0359,0619)
  BOUNCE enemyY=0619 ySpeedBefore=0000
  SCAN flybot sidekick=true  pre=true ... used=(0359,0619)
  DRIVE idx=411 row=019B expYSpd=0000 engYSpd=FF00
```

Two things follow, both measured rather than inferred:

* **The engine's touch phase is correct.** Within a frame the snapshot and both
  scans run *before* the badnik moves, so the scan consumes the
  end-of-previous-frame position -- exactly `Touch_Loop`'s semantics.
  `ObjectManager.java:614-630` and `LevelFrameStep.java:299` are honoured.
  `preU == live` at the scan simply because the object has not moved yet.
* **`FRAME N` drives trace row `N-1`.** The `DRIVE idx=` line that follows each
  frame names the row it was driven for. So in row terms the engine ends row 410
  with the flybot at `0359,0619`, where the ROM ends row 410 at `035B,0617`.

**The badnik is one frame ahead of the ROM in trace-row terms.** Its correct
touch phase then faithfully reports the overlap one row early, and the bounce
lands on row 411 instead of 412.

## Where the frame is lost

`Obj_WaitOffscreen` (`docs/skdisasm/sonic3k.asm:180271-180302`) installs
`loc_85AD2` as the object's operation and draws it. `loc_85AD2` gates on
`tst.b render_flags(a0) / bmi` -- bit 7, which the *previous* frame's draw pass
published -- and only then reaches `loc_85B02`, which restores the saved pointer
and `rts`. The restored routine does not run on the frame that restores it.

The recording shows exactly that, and independently confirms the placeholder's
identity: aux `object_appeared` for slot 7 is `object_type 0x00085AD2` at row
**307** and `0x0008C96C` (`Obj_Flybot767`) at row **308**. So the ROM restores
during 308 and runs the Flybot's first pass on **309**.

The engine, measured:

```
FRAME 308 inline=true
  CREATE flybot
  WAIT flybot layoutArm=false placeholderRendered=false inBounds=true
  DRIVE idx=307 row=0133 ...
FRAME 309 inline=true
  SNAP flybot live=(0406,05CC)
  MOVE flybot -> (0406,05CC)
```

It creates the object and clears `waitingForOnscreen` in the **same** row (307),
so its first movement pass is row **308** -- one frame ahead of the ROM's 309.
That single frame propagates unchanged through the whole dive.

The cause is a per-branch gap in `Flybot767BadnikInstance.updateMovement`. The
layout arm waits for `placeholderRenderedOnscreen`, which
`refreshPostCameraRenderState` publishes from the preceding render pass -- the
ROM's `render_flags` bit 7 semantics, correctly modelled. The dynamic arm
(`layoutIndex < 0`, which is how `Obj_LBZAlarm` allocates its Flybots) instead
evaluates `isWithinRenderSpriteBounds(...)` live, in the creation frame, and so
skips the frame the ROM spends observing a flag that only a previous draw can
have set.

This is rule 120 in the wild: the routine is modelled, one of its two arms is
not, and a citation naming the routine is accurate about the half that works.
Note that `ef5bc60d7` made the two arms test the same *bounds*; they remain on
different *phases*.

## Eliminated

* **`Flybot767BadnikInstance.usesCurrentTouchResponseState()`.** Forced to
  `false`, rebuilt, re-run: byte-identical `4592 errors, first error frame 411`.
  Not the lever -- this Flybot is LBZAlarm-allocated (`layoutIndex < 0`) and
  already took the snapshot branch.

  **This is rule 110 in the wild.** Without checking the recompiled class
  timestamp the result would have been reported as "changed nothing, therefore
  ruled out" when the truth was "the branch never ran". A negative control that
  is never exercised is indistinguishable from a negative control that is
  exercised and inert. The same check caught two further probes in this round
  that compiled into the wrong method or sat on a code path the replay never
  takes, and printed nothing at all.
* **A shared-path touch-ordering defect.** Disproved by the labelled trace
  above; see the next section for how it was wrongly concluded in the first
  place.
* **Sidekick involvement** -- `sidekick_y_speed` is `0000` throughout the
  window, and the failing field is the unprefixed player one
  (`TraceBinder.java:217`).
* **Comparator inversion** -- fully explained by the hexadecimal frame domain
  above.

## How the first version got it wrong

The first round's probe log had no frame delimiter. It read

```
MOVE flybot -> (0359,0619)
SNAP flybot live=(0359,0619)
PROBE ... used=(0359,0619)
BOUNCE ...
```

which groups equally well as `[MOVE][SNAP][SCAN]` -- the object moving before
its own touch scan -- or as `[MOVE] | [SNAP][SCAN]` with a frame boundary in the
middle, which is the truth. Both groupings are consistent with the bytes.

The tie was broken by reasoning backwards from the failing comparison: the
engine's `y_speed` diverges at row 411, therefore the bounce must be inside
frame 411, therefore the first grouping. That inference silently assumed
`engine frame == trace row`, an unverified one-point clock conversion of exactly
the kind rule 101 warns about. It is off by one, and the whole conclusion turned
on it.

The remedy is cheap and worth generalising: **a probe stream that spans frames
must print the frame, and the frame it prints must be the compared row, not a
counter of the probe's own.** Here that meant emitting the driver's `DRIVE idx=`
alongside, which converts the clock by measurement instead of by assumption.

The elimination that this same error produced -- "the trajectory is in phase,
so it is not a creation-frame error" -- was likewise built on an assumed anchor
(engine's first movement pass ↔ ROM row 309) rather than a measured one. The
values matched because they are the same values one row apart. Comparing two
sequences by value, without pinning either to a measured index, cannot detect a
uniform shift between them: that comparison is incapable of returning the answer
it was asked for.

## Not established

* **Whether the `usesCurrentTouchResponseState()` opt-in is wrong for the other
  ~20 S3K objects that override it.** Its justifying comment says a retained
  slot exposes a live SST coordinate to the touch list; per the ROM reading
  above that looks wrong, because `Touch_Loop` dereferences the pointer at
  Sonic's slot and no object can legitimately expose a same-frame post-move
  position. Held as a hypothesis and deliberately not swept: one inert test is
  not evidence about twenty.
* **Whether other objects share the dynamic-arm wake phase gap.**
  `Obj_WaitOffscreen` has several callers in the disassembly
  (`sonic3k.asm:128225`, `134031`, `182275`, `182373`, `182698`, `183323`). Only
  the Flybot767 was measured.

## A reporting gap that hides a data gap

`metadata.json` for `src/test/resources/traces/s3k/lbz_completerun` advertises
`collision_response_list_per_frame`, `collision_response_list_end_of_frame` and
`velocity_write_per_frame` in `aux_schema_extras`. The fixture contains **zero**
rows for all three.

Worse, the divergence report's own "Missing advertised aux schemas" line does
not list `collision_response_list` at all -- it names cage, velocity_write,
position_write, sonic_record_pos, tails_cpu_normal_step and cnz. So that
particular gap is silently unreported, and a reader trusting the line concludes
the stream is present.

A `velocity_write` stream would have named this defect in one grep, by pointing
at the PC that wrote `-0x100`. Both the missing streams and the incomplete
missing-schema report are worth someone's round.
