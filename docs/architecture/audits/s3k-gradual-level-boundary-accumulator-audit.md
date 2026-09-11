# Audit: `Obj_*LevEnd*Gradual` accumulator ports (S3K)

Date: 2026-08-20. Base commit: `ffc05b664`.

## Scope

`AizAct2CameraResizeController` was found to carry three fitted compensations for a
creation-pass dispatch it assumed the engine was missing. This audit enumerates every
other port of the same ROM accumulator and states each one's status.

## The ROM argument, verified

`Obj_IncLevEndXGradual` / `Obj_DecLevStartXGradual` / `Obj_DecLevStartYGradual` /
`Obj_IncLevEndYGradual` (`docs/skdisasm/sonic3k.asm:178159-178228`) each hold a longword
accumulator at `$30`, add `$4000` (X and min-Y) or `$8000` (max-Y) per dispatch, store it
back, then `swap` to take the high word as the boundary step. `$30` is zero when the slot
is allocated, so the **first** dispatch always yields integer step 0.

Every creator of these workers reaches them in the same object pass:

- `CreateChild1_Normal` (`sonic3k.asm:176924-176950`) and `CreateChild6_Simple`
  (`sonic3k.asm:177119-177140`) both allocate through `AllocateObjectAfterCurrent`
  (`sonic3k.asm:37917-37930`), which starts at `a0` (the creating object) and walks
  forward through `next_object`. By construction it can only return a slot **after** the
  creator.
- `Process_Sprites` (`sonic3k.asm:35965-35995`) walks object RAM in ascending slot order
  within a single pass.

Therefore the creation frame **is** dispatch 1. Pre-charging an accumulator, or skipping
the creation-frame dispatch, makes engine dispatch *k* behave as ROM dispatch *k+1*.

`AllocateObject` (`sonic3k.asm:37909-37913`) scans from the bottom of
`Dynamic_object_RAM` instead and *can* return an earlier slot; sites that port an
`AllocateObject` creator must model that separately.

## Site status

| Site | Status |
|---|---|
| `Sonic3kLevelEventManager` (CNZ act-2 workers, `:1395-1480`) | **Correct.** Accumulators start at 0, dispatched in the creating pass, and the ROM argument is already written out in full at the arming site. This is the reference port. |
| `Sonic3kHCZEvents.updateAct2LevelSizeChildren` | **Correct.** Accumulators start at 0; the arming path (`loc_6A8AE` carrier release) runs inside `updateRetainedCarrierObjectPass` before the worker block in the same call, so the creation frame dispatches. |
| `Sonic3kICZEvents` act-2 workers | **Correct** as regards the accumulator: starts at 0, no pre-charge, no skip. (Whether `preparePostTitleAct2SizeChange` and `updatePostTitleAct2SizeWorkers` land in the same frame was not established — see Open questions.) |
| `Sonic3kLBZEvents` act-2 workers | **Fixed here.** Accumulators were correct, but a `postTitleAct2WorkersCreatedThisPass` flag existed to skip the creation-frame dispatch, with a comment teaching the defect. The flag was never set to `true`, so it was dead; removed with its comment, replaced by the ROM argument. No behaviour change. |
| `MgzDrillingRobotnikCameraUnlockController` | **Correct.** Real dynamic object, accumulator starts at 0, no pre-charge; its ROM creator uses `AllocateObject` and the engine models the slot question by keeping it a real object. |
| `Lbz1RobotnikEventController.updatePostCollapseCameraMax` | **Correct**, and its comment already cites `CreateChild6_Simple` allocating after Robotnik's slot. |
| `Lbz2RobotnikShipInstance.GradualCameraMaxXChild` | **Correct.** Real spawned child, accumulator starts at 0. |
| `LbzEndBossInstance` gradual max-X child | **Correct.** Real spawned child, accumulator starts at 0. |
| `HczEndBossGradualMaxXExtender` | **Correct**, and it is the exemplar for the opposite direction: it exposes an explicit `dispatchCreation()` so the allocation-frame dispatch runs when the reserved slot is already behind the live cursor, rather than pre-charging. |
| `Aiz2BossEndSequenceController` (post-button max-Y; `PostResultsGradualMaxX`) | **Correct.** `updatePostButtonCameraMaxYRelease()` runs at the tail of the same update that arms it, so the creation frame is dispatch 1 with step 0. |
| `LbzMinibossBoxKnuxInstance.updateGradualMaxYRaise` | **Fixed here** — see below. |
| `CutsceneKnucklesHcz2Instance.restoreCameraBoundariesGradually` | **Not this defect class.** It does not port the accumulator at all: it snaps `Camera_min_Y_pos` / `Camera_max_X_pos` to their stored values in one frame and says so ("Simplified"). A separate, larger gap. |
| `MgzDrillingRobotnikInstance` (`:1152-1156`) | Comment only; the worker itself is `MgzDrillingRobotnikCameraUnlockController`. |
| `MhzShipSequenceControllerInstance` | **Unrelated.** `Gradual_SwingOffset`, a different ROM accumulator. |
| `Sonic3kMGZEvents.updateAct2LevelSizeChange` | **Defect confirmed statically, candidate rejected by measurement** — see below. |

## `LbzMinibossBoxKnuxInstance` (fixed)

`loc_8CFC8` (`sonic3k.asm:192565-192600`) creates the `Child6_IncLevY` worker via
`CreateChild6_Simple`, so the worker dispatches later in the *same* pass. The engine
called `updateGradualMaxYRaise()` near the top of `update`, **before** the `switch` whose
`updateFight()` arm sets `maxYRaiseActive` — so the creation frame was skipped and every
dispatch ran one frame late. The call now runs after the switch, matching the child's
later slot on every frame, not only the arming one.

Second divergence at the same site: `loc_8CFC8` writes `Camera_stored_max_Y_pos` and
`Camera_target_max_Y_pos` **once**, at arm time, with `$A80`; `Obj_IncLevEndYGradual` then
moves `Camera_max_Y_pos` alone. The engine instead re-wrote `maxYTarget` to the current
interpolated `nextMax` on every dispatch. The target is now written once at arm.

No fixture covers this path (it is Knuckles-only, LBZ2 miniboss); the S3K chain and the
MGZ/LBZ trace classes are byte-identical before and after. A correct fix that no recording
can witness is still correct.

## `Sonic3kMGZEvents` — defect confirmed, candidate REJECTED

`updateAct2LevelSizeChange` carries the same family as `AizAct2CameraResizeController`:

```java
act2SizeMaxXAccumulator = 0x4000;
act2SizeMinYAccumulator = 0x4000;
act2SizeMaxYAccumulator = 0x8000;   // three pre-charges
camera().setMaxY((short) (camera().getMaxY() + 2));   // bare +2
```

All four comments narrate engine bookkeeping ("Each child executes its create entry before
joining the shared gradual-worker dispatch", "already contains the native two-pixel carry
at this owner handoff") rather than citing a ROM routine — the rule-40 signature.

Statically the ROM argument applies unchanged: MGZ is zone 2, so `Change_Act2Sizes`
(`sonic3k.asm:180580-180596`) passes both the SOZ1 and the HCZ (`d0 == $10`) early-outs and
falls through into `Make_LevelSizeObj` (`:180598-180604`), which creates all three workers
with `CreateChild1_Normal` -> `AllocateObjectAfterCurrent`.

**A candidate removing all four compensations was written, compiled, and measured, and is
rejected.** Same-tree control at `ffc05b664`, identical command, default profile,
`-Xmx3g`, all three ROM paths, `-Dmse=off`:

| Class | control | candidate |
|---|---|---|
| `TestS3kMgzTraceReplay` | **pass (0 errors)** | **fail, 2 errors** — first error frame 14529, `camera_y` expected `0x0813`, actual `0x0810` |
| `TestS3kSonicTailsMgzSegmentTraceReplay` | 4103 errors | 4104 errors |
| all other classes in the sweep (646 tests) | identical by message | identical by message |

The candidate is preserved as `s3k-mgz-gradual-precharge-candidate.patch` alongside this
document.

Reading: the engine is three pixels low on `camera_y` for two frames during the ramp, i.e.
one dispatch behind. The compensations are therefore absorbing an error elsewhere — most
plausibly that the engine's MGZ arming frame (when `updateAct2LevelSizeChange` first
observes `End_of_level_flag`) is one object pass later than `Obj_EndSignControlDoStart`'s
slot, so the pre-charge is standing in for a missing pass rather than for a missing
dispatch. Removing the compensations without first placing the arming frame correctly
trades a hidden fitted constant for a visible regression. That relocation is the real fix
and is out of scope for this round.

## Open questions (not established)

- ICZ / LBZ act-2 workers: `preparePostTitleAct2SizeChange` is called from
  `Sonic3kTitleCardManager`, while `updatePostTitleAct2SizeWorkers` runs from
  `updateFixedInLevelObjectsBeforeDynamicObjects` in `LevelFrameStep`. Whether both land
  in the same frame — i.e. whether the creation-frame dispatch happens — was not traced.
  Neither site pre-charges, so the worst case is the *opposite* error.
- `Sonic3kICZEvents.preloadedActCameraReleaseAdditionalDispatches()` returns a bare `2`
  justified by "ICZ's retained EndSignControl path has two further polls before release"
  with no ROM citation. Not part of this defect class, but the same comment shape.
