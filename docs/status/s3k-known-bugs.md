# Known Bugs and Unfinished Work — Sonic 3 & Knuckles

This document tracks **Sonic 3 & Knuckles bugs**, incomplete implementations, and known parity gaps that we intend to fix but haven't addressed yet. Entries here are *not* intentional — they're acknowledged problems with a plan (or hope) of eventual resolution.

For **intentional** S3K deviations (architectural choices, feature extensions,
deliberate bug-fixes of ROM data), see
[S3K known discrepancies](../S3K_KNOWN_DISCREPANCIES.md).

For general (cross-game) bugs, see [known-bugs.md](known-bugs.md).

> **Audit note, 2026-06-12:** this file still contains many historical dedicated-trace entries
> retained for diagnosis context. The canonical current S3K parity ledger is
> `docs/status/trace-frontier-log.md`, especially the S3K complete-run per-zone frontiers. Use this file
> for durable bug explanations, but check the frontier log before treating an old AIZ/CNZ frame
> entry as the next live blocker.

Entries should include:
- **Location** — the file(s) where the bug lives, if known
- **Symptom** — what goes wrong and where you can observe it (test name, trace frame, manual repro)
- **Suspected cause** — best current theory, with ROM/disasm references when relevant
- **Removal condition** — what needs to be true for this entry to be deleted

---

## Table of Contents

1. [AIZ Miniboss Napalm — FallingShot Route Integration (OPEN)](#aiz-miniboss-napalm--fallingshot-route-integration-open)
2. [Knuckles LBZ Big Arm — Inert Final-Boss Handoff](#knuckles-lbz-big-arm--inert-final-boss-handoff)
3. [LRZ1 Non-Knuckles — Falling Level Introduction (RESOLVED)](#lrz1-non-knuckles--falling-level-introduction-resolved)
4. [AIZ2 End Boss — Splash Children (FIXED)](#aiz2-end-boss--splash-children-missing)
5. [CNZ1 Miniboss Arena Entry — Music Play-In Missing](#cnz1-miniboss-arena-entry--music-play-in-missing)
6. [AIZ1 Trace F4679 — Sidekick Despawn Velocity & Position Semantic Gap (FIXED)](#aiz1-trace-f4679--sidekick-despawn-velocity--position-semantic-gap-fixed)
7. [CNZ1 Trace F1685 — Tails CPU Spurious Despawn on Barber-Pole→Wire-Cage Object Switch (FIXED)](#cnz1-trace-f1685--tails-cpu-spurious-despawn-on-barber-polewire-cage-object-switch-fixed)
8. [CNZ1 Trace F1740 — Wire Cage restoreObjectLatchIfTerrainClearedIt Overrode Slope-Repel Slip (FIXED)](#cnz1-trace-f1740--wire-cage-restoreobjectlatchifterrainclearedit-overrode-slope-repel-slip-fixed)
9. [CNZ1 Trace F1758 — Wire Cage Airborne-Capture object_control Bit 0 Missing (FIXED)](#cnz1-trace-f1758--wire-cage-airborne-capture-object_control-bit-0-missing-fixed)
10. [CNZ1 Trace F1791 — Tails CPU Auto-Jump Trigger Bit-7 Object Control Gate (FIXED)](#cnz1-trace-f1791--tails-cpu-auto-jump-trigger-bit-7-object-control-gate-fixed)
11. [AIZ1 Trace F2590 — Tails CATCH_UP_FLIGHT Trigger Path Mismatch](#aiz1-trace-f2590--tails-catch_up_flight-trigger-path-mismatch)
12. [AIZ1 Trace F2202 -- Phantom MonkeyDude Respawn Triggers Spurious Sidekick Bounce (FIXED)](#aiz1-trace-f2202----phantom-monkeydude-respawn-triggers-spurious-sidekick-bounce-fixed)
13. [AIZ1 Trace F5497 — Sidekick CPU Bound Override Stale After Act Transition (FIXED)](#aiz1-trace-f5497--sidekick-cpu-bound-override-stale-after-act-transition-fixed)
14. [AIZ Trace F5736 — Level_frame_counter Skips Tick on Seamless Act Reload (FIXED)](#aiz-trace-f5736--level_frame_counter-skips-tick-on-seamless-act-reload-fixed)
15. [AIZ Trace F6066 — CaterKillerJr Missed Obj_WaitOffscreen Gate (FIXED)](#aiz-trace-f6066--caterkillerjr-missed-obj_waitoffscreen-gate-fixed)
16. [CNZ1 Trace F2222 — Wire Cage Sidekick JUMP_RELEASE Spurious Fire (OPEN — needs ROM-aligned `Ctrl_2_pressed_logical` model)](#cnz1-trace-f2222--wire-cage-sidekick-jump_release-spurious-fire-open--needs-rom-aligned-ctrl_2_pressed_logical-model)
17. [AIZ Trace F6255 — Tails CPU Freed-Slot Despawn (RESOLVED)](#aiz-trace-f6255--tails-cpu-freed-slot-despawn-resolved)
18. [CNZ1 Trace F3649 — Tails Air-to-Ground Spring Boost Missed (RESOLVED)](#cnz1-trace-f3649--tails-air-to-ground-spring-boost-missed-resolved)
19. [CNZ1 Trace F6304 — Tails Misses CNZ Door Re-Land While Following Fast Leader (RESOLVED)](#cnz1-trace-f6304--tails-misses-cnz-door-re-land-while-following-fast-leader)
20. [CNZ1 Trace F7614 — Tails Spring Bounce Top-Landing 2-Pixel Drift (OPEN — next trace blocker)](#cnz1-trace-f7614--tails-spring-bounce-top-landing-2-pixel-drift)
21. [AIZ2 Trace F7127 — Tails Phantom Landing While Falling (RESOLVED)](#aiz2-trace-f7127--tails-phantom-landing-while-falling)
22. [AIZ2 Trace F7171 — Tails Killed Mid-Run vs. Engine Continuing Follow-Steering (OPEN — next AIZ blocker)](#aiz2-trace-f7171--tails-killed-mid-run-vs-engine-continuing-follow-steering)
23. [AIZ2 Trace F7381 — Engine `Ctrl_1_logical` Not Latched While ROM `Ctrl_1_locked=1` (OPEN — next AIZ blocker)](#aiz2-trace-f7381--engine-ctrl_1_logical-not-latched-while-rom-ctrl_1_locked1-open--next-aiz-blocker)
24. [CNZ1 Trace F7919 — Tails Triplicate `-0x0800` Velocity Write While Sonic Lands From Rising Platform (OPEN)](#cnz1-trace-f7919--tails-triplicate--0x0800-velocity-write-while-sonic-lands-from-rising-platform-open)
25. [CNZ F=621 Clamer re-fire — ROM dispatch path narrowing (diagnosis only, round 2)](#cnz-f621-clamer-re-fire--rom-dispatch-path-narrowing-diagnosis-only-round-2)
26. [CNZ F=621 Clamer re-fire — recorder gap closed; ROM mechanism localised (diagnosis only, round 3)](#cnz-f621-clamer-re-fire--recorder-gap-closed-rom-mechanism-localised-diagnosis-only-round-3)
27. [CNZ F=621 Clamer re-fire — Touch_Special cprop latch landed (round 4, fixed)](#cnz-f621-clamer-re-fire--touch_special-cprop-latch-landed-round-4-fixed)
28. [AIZ Trace F8927 — Sonic Air-Roll x_speed Not Cleared by Wall Collision (OPEN — diagnosis only)](#aiz-trace-f8927--sonic-air-roll-x_speed-not-cleared-by-wall-collision-open--diagnosis-only)
29. [Blue Sphere FM Pickup Onset — Automated Parity Complete, Listening Gate Open](#blue-sphere-fm-pickup-onset--automated-parity-complete-listening-gate-open)

---

## Blue Sphere FM Pickup Onset — Automated Parity Complete, Listening Gate Open

**Location:** `YmServiceTimingProfile`, `YmWriteTimeline`, `SmpsDriver`, and
`VirtualSynthesizer`.

### Previous symptom

Repeated Blue Sphere pickups could sound harder or more abrupt than the retail
game because an entire 34-write FM5 voice upload reached the emulated YM2612 at
one host instant. Register order was correct, but the source Z80 bus spacing and
the YM chip's internal advancement between writes were absent.

### Narrowed status (2026-08-22)

The retained Genesis Plus GX lab now reproduces twelve Blue Sphere upload
groups with one byte-identical 34-write relative-cycle vector, no DMA stalls,
and no observer fault or overflow. OpenGGF schedules the S3K service against
that source-relative vector and drains it at YM internal-sample boundaries.
Focused playback, service-order, contention, rewind, observer, pause, fade,
one-up, PAL, ring-panning, special-stage, OpenAL, and snapshot tests pass. The
replacement playback gate requires a closed-world drained prefix: the exact
13-event committed Spring tail is followed immediately by the exact Blue Sphere
attack stream, with no intervening event. The pending Blue Sphere service stays
exactly 34 reviewed source/segment-tagged writes and rejects any music or
completion-restore prefix. Its architecture
guard also requires rollback capture as the helper's first unconditional
statement and permits only the reviewed transaction setup. The
full three-ROM suite introduces no failure attributable to this change when
compared by test identity with the isolated feature-base baseline at
`914ac9a87badbad5c574cd8edaadc81c743e390a`; the 117 red identities and their
failure/error classifications are byte-identical.

The evidence establishes relative write spacing, not an absolute phase within
VInt, and the native capture contains no DMA-contended upload. Those are limits
on the claim rather than known runtime mismatches. The implementation branch is
therefore intentionally held out of integration until a human listen covers the
first pickup, rapid pickups, completion/turn boundaries, replacement of another
FM5 SFX, rings, and special-stage speed-shoes entry.

### Removal condition

Remove this entry after a positive listen and integration of the exact verified
handoff commit. Reopen source-timing investigation if the listen identifies a
repeatable onset defect within the scenarios above.

---

## AIZ Miniboss Napalm — FallingShot Route Integration (OPEN)

**Location:** `AizMinibossNapalmProjectile`
**ROM Reference:** `AIZMiniboss_FallingShot` (`loc_68C96`),
`ObjDat_AIZMiniboss_BarrelShot`, `ObjDat_BossExplosionHitbox`, and
`ChildObjDat_690D8` in `docs/skdisasm/sonic3k.asm:137451-137581,137836-137925`.

### Disposition (2026-08-08)

The FallingShot routine is now implemented in
`src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmProjectile.java`,
including the ROM `MoveSprite2`/`ObjHitFloor_DoRoutine` sequence, the `$60` rise
and `$8` pause, camera-relative top-drop slot tables, post-move `$98` touch
publication, native AIZ miniboss PLC art/mappings, and seven staggered `$97`
`BossExplosionHitbox` children. The production route begins at each of the
three existing barrel children, preserving child subtype `$02` separately from
the barrel subtype and `$39` counter; rewind recreation and scalar coverage
include the linked projectile and transient children.

The disposition remains **OPEN** until a Knuckles miniboss route trace proves
the activation gate, per-barrel child ordering, floor impact, and explosion
lifetime together. Focused native, production `ObjectManager`, slot-exhaustion,
and rewind tests are comparison/contract evidence only and do not hydrate
runtime state.

Focused tests are in
`src/test/java/com/openggf/game/sonic3k/objects/TestAizMinibossNapalmProjectile.java`.
The committed Knuckles complete-run comparison data independently records
`0x00068C96` FallingShot rows (subtype `$02`, routines `$02/$06/$08`) in
`src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/aiz_2`
(frames 3338-3589) and `aiz_3` (frames 4486-4698), plus the seven
`0x00068D88` explosion children. These rows are comparison evidence only; they
are not loaded as runtime gameplay state.

The available Sonic/Tails AIZ replay lane remains red on camera/sidekick and
hardware-timing errors, and this environment has no captured Knuckles miniboss
route trace, so no trace-frontier claim is made by this entry.

---

## Knuckles LBZ Big Arm — Inert Final-Boss Handoff

**Location:** `LbzFinalBoss2Instance`
**ROM Reference:** `Obj_LBZFinalBoss2` (`sonic3k.asm:154226`).

### Symptom

The Knuckles final-boss handoff creates a persistent object, but it is invisible
and inert. Big Arm phases, collision, art/PLC ownership, hit/defeat flow, and
route completion are therefore absent.

### Remediation attempts (2026-08-08)

Two candidate ports were rejected and remain unintegrated. The first was the
committed `98d968d7f` attempt; review found invented phases, mapping ownership,
and defeat/capsule flow, with no native articulated arm/grab graph. A second v2
working-tree attempt was never committed. It proved useful articulated
anchors/tables (`$AD`/`$9A`), grab, and debris behavior and passed six focused
tests plus 28 graph/rewind guards, but root choreography, post-capsule
continuation, and a Knuckles LBZ route trace remained unproven. The v2 was
therefore not integrated, and the inert handoff remains a P0 blocker.

### Removal Condition

Implement the ROM boss state machine and assets through the production loading
pipeline, add rewind coverage, and complete a Knuckles LBZ trace through defeat.

---

## LRZ1 Non-Knuckles — Falling Level Introduction (RESOLVED)

**Location:** `Sonic3kLevelEventManager`
**ROM Reference:** `SpawnLevelMainSprites` `loc_68A6` (`docs/skdisasm/sonic3k.asm:8161-8178`).

### Symptom

Before remediation, LRZ1 for non-Knuckles characters started without the native
falling player state. The ROM compares `$0900` (LRZ1) and skips the branch for
`Player_mode == 3` (Knuckles), then `loc_68A6` writes animation `$1B` and the
in-air status to Player 1 and, when present, Player 2.

The earlier SSZ attribution was disproven by checking the owning routine:
SSZ is `$0A00/$0A01` in `LS_Level_Order` (`sonic3k.asm:10154-10157`), while
`loc_68A6` is reached by `$0900` and `$1600` (`sonic3k.asm:8161-8168`) and
does not compare either SSZ act. SSZ event and teleporter behavior therefore
does not require this falling bootstrap state.

### Resolution

`Sonic3kLevelEventManager` now applies the native LRZ1 non-Knuckles state after
the normal player spawn. `TestS3kLrzFallingIntroBootstrap` covers Sonic + Tails,
Tails alone, Knuckles exclusion, LRZ2 exclusion, and the SSZ negative gate.
LRZ and SSZ complete-run payloads exist under
`src/test/resources/traces/s3k/{lrz,ssz}_completerun`, but no replay subclasses
currently own those segments. A direct LRZ replay attempt is presently blocked
before gameplay by the fixture's final v5 hardware-timing row
(`unsupported-held-row-POST`, trace rows 72/78 continuing through `raw_frame=38746`;
the first unsupported row is `raw_frame=38719`), so the ROM-backed headless
bootstrap suite is the available focused validation for this change.

---

## AIZ2 End Boss — Splash Children Missing

**Location:** `AizEndBossInstance`
**ROM Reference:** `ChildObjDat_69D2E` and the end-boss emerge/submerge paths.

**Status: Fixed (2026-08-08).** `AizEndBossWaterfallChild` now ports the
subtype-0 emerge and subtype-2 re-submerge/drop paths. The child is allocated
through the production `ObjectManager` first-free-forward slot search from the
boss, uses the ROM mapping/flip scripts and production ROM art provider, and
has a generic rewind recreation path.

Owner validation includes `TestS3kAizEndBossGraphRewind` (real child slots and
rewind round-trip), `TestAiz2ObjectRewindCodecs`, and
`TestSonic3kObjectArtProvider` against the verified locked-on ROM. An end-to-end
AIZ2 boss trace was not rerun in this change; retain that as follow-up parity
validation.

### Historical symptom

The boss previously played the corresponding sound without allocating the native
splash children, so presentation and dynamic object-slot ordering differed even
though the main boss flow continued.

### Historical removal condition

Port the splash subtypes with ROM art/mappings, production allocation, rewind
and render coverage; full AIZ2 trace validation remains outstanding.

---

## CNZ1 Miniboss Arena Entry — Music Play-In Missing

**Location:** `Sonic3kCNZEvents.enterMinibossArena()`
**ROM Reference:** `sonic3k.asm:144841` (`moveq #cmd_FadeOut,d0; jsr Play_Music`) plus the boss-music play-in that follows when `Obj_CNZMiniboss` becomes active.

### Symptom

When `Obj_CNZMiniboss` crosses its camera-X gate (`$31E0`), `Sonic3kCNZEvents.enterMinibossArena()` mirrors the ROM's music fade-out via `audio().fadeOutMusic()`, but the miniboss-music play-in is not yet wired. Audio drops to silence between the fade-out and boss defeat instead of switching to the miniboss theme. All other arena-entry effects (camera lock, PLC `0x5D`, `Pal_CNZMiniboss` install, `Boss_flag`, wall-grab suppression) match the ROM bit-for-bit, so visual/gameplay parity is unaffected.

### Current State

The site is marked with an inline `TODO(T12)` comment. Workstream T12 ("CNZ miniboss audio handoff") owns wiring `Sonic3kMusic.MINIBOSS` (or the equivalent S3K music ID) into the existing `audio()` boss-music handoff once the miniboss audio routing lands.

No tests assert on music selection during the miniboss fight, so this gap does not block test coverage elsewhere.

### Removal Condition

Remove once the miniboss theme plays on arena entry and a regression test asserts on the active music ID between fade-out and boss defeat.

---

## AIZ1 Trace F4679 — Sidekick Despawn Velocity & Position Semantic Gap (FIXED)

**Status:** Fixed in iter-19 by routing sidekick level-boundary kills through
a new `Kill_Character`-equivalent path. The fix introduces a `DespawnCause`
enum on `SidekickCpuController` and gates the existing `triggerDespawn()`
marker-warp body behind the non-`LEVEL_BOUNDARY` causes, with a new
`beginLevelBoundaryKill()` entry for `LEVEL_BOUNDARY` that mirrors ROM
`Kill_Character` (sonic3k.asm:21136-21151) by zeroing `xSpeed`/`ySpeed`/
`gSpeed`, clearing roll/push/rolljump bits, and parking the sidekick in
a new `State.DEAD_FALLING` engine state. The next frame's
`updateDeadFalling()` runs the `loc_1578E -> sub_123C2 -> sub_13ECA`
sequence (sonic3k.asm:29263, 24538, 26800): warp to despawn marker
`(0x7F00, 0)`, transition to `State.SPAWNING`, and apply the post-warp
`MoveSprite_TestGravity` +`$38` gravity write that ROM produces in the
same frame.

**Location:** `SidekickCpuController.beginLevelBoundaryKill()` /
`SidekickCpuController.updateDeadFalling()` /
`SidekickCpuController.applyDespawnMarker()`,
`PlayableSpriteMovement.doLevelBoundary()` (sidekick-CPU dispatch).
**ROM Reference:** `sonic3k.asm:21136` (`Kill_Character`), `sonic3k.asm:23172`
(`Player_LevelBound`), `sonic3k.asm:24538` (`sub_123C2`), `sonic3k.asm:26800`
(`sub_13ECA`), `sonic3k.asm:29263` (`loc_1578E` death routine), `sonic3k.asm:36068`
(`MoveSprite_TestGravity`).

### Pre-Fix Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` first error at trace frame 4679:
```
tails_y_speed mismatch (expected=0x0000, actual=0x0198)
```

Engine `SidekickCpuController.despawn()` warped Tails to the despawn marker
`(0x7F00, 0)` immediately and left `(x_vel, y_vel, ground_vel)` carrying
the live-physics values from the previous frame, while ROM's
`Player_LevelBound -> Kill_Character` chain zeros the velocities on Frame
N before the death routine warps to the marker on Frame N+1.

### Diagnosed Cause

`SidekickCpuController.triggerDespawn()` was modelling `sub_13ECA` only
(the marker warp) for all despawn causes. Off-screen-timeout and
object-id-mismatch despawns naturally preserve velocity (ROM `sub_13ECA`
writes only x_pos/y_pos/object_control/status/Tails_CPU_routine and does
not touch x_vel/y_vel/ground_vel — sonic3k.asm:26800-26809), but the
level-boundary kill goes through `Kill_Character` first which DOES zero
the velocities. The engine collapsed both into one path.

### Fix

1. New `DespawnCause` enum on `SidekickCpuController`:
   - `LEVEL_BOUNDARY` (Player_LevelBound bottom kill plane)
   - `OFF_SCREEN_TIMEOUT` (engine 300-frame off-screen timeout)
   - `OBJECT_ID_MISMATCH` (S2 TailsCPU_CheckDespawn id mismatch)
   - `EXPLICIT` (test harness, level transitions)
2. New `State.DEAD_FALLING` enum value, dispatched via
   `updateDeadFalling()` in the per-frame state switch.
3. `triggerDespawn(DespawnCause)` overload that routes to
   `beginLevelBoundaryKill()` for `LEVEL_BOUNDARY`, otherwise calls
   `applyDespawnMarker()` directly (preserving the historic non-zeroing
   semantics for non-kill despawns).
4. `beginLevelBoundaryKill()` mirrors `Kill_Character`: zeros vels, clears
   roll/push/rolljump/onObject/pushing bits, sets in_air, transitions to
   `DEAD_FALLING`. Position is intentionally NOT warped this frame to
   match ROM's two-frame split where `sub_13ECA` runs on Frame N+1.
5. `updateDeadFalling()` runs the next frame: calls `applyDespawnMarker()`
   to warp + transition to `SPAWNING`, then writes y_speed=0x38 to mirror
   ROM's post-`sub_13ECA` `MoveSprite_TestGravity` +0x38 air-gravity write.
6. `applyDespawnMarker()` no longer zeros velocities (matches ROM
   `sub_13ECA` exactly). Off-screen-timeout and object-id-mismatch paths
   keep their pre-fix preserve-velocity semantics, which AIZ trace F2405
   exercises (Tails alive at F2404, sub_13EFC's flight_timer hits 5*60 at
   F2405, marker warp without velocity zeroing).
7. `PlayableSpriteMovement.doLevelBoundary()` calls
   `cpuController.despawn(DespawnCause.LEVEL_BOUNDARY)` instead of the
   no-arg `despawn()` overload.

### Result

- AIZ first strict error advances F4679 -> F5497 (1190 -> 1185 errors).
  F5497 is in AIZ act 2 reload territory (downstream divergence, not
  related to the kill-plane semantic gap; subsequently fixed — see
  "AIZ1 Trace F5497" entry below).
- Cross-game baselines unchanged: S1 GHZ PASS, S1 MZ1 F311, S2 EHZ F1151,
  S3K CNZ F1815.

---

## AIZ1 Trace F5497 — Sidekick CPU Bound Override Stale After Act Transition (FIXED)

**Location:** `LevelManager.executeActTransition()` (`src/main/java/com/openggf/level/LevelManager.java`).
**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`, first strict error was at frame 5497 prior to fix.

### Symptom

At frame 5497 the engine produced `tails_x = 0x2F20` vs the ROM's
expected `tails_x = 0x00B1` — a 0x2E6F (≈11887 px) jump in a single
frame, immediately after the AIZ1 → AIZ2 seamless reload checkpoint
(`aiz2_reload_resume`) at frame 5496.

### Root Cause

`SidekickCpuController` keeps its own `minXBound`/`maxXBound`/
`maxYBound` overrides that `PlayableSpriteMovement.doLevelBoundary`
prefers over the live camera bounds for CPU sidekicks (mirroring ROM
`sonic3k.asm:36925/36945` Player_Boundary_Check_*). Per-zone event
handlers — notably `Sonic3kAIZEvents` boss arena lock — populate
those overrides during AIZ1; `Sonic3kLevelEventManager
.syncSidekickBoundsToCamera()` then refreshes them every frame at the
END of `update()`.

When the AIZ1 → AIZ2 seamless act transition fires (ROM
`AIZ1BGE_Finish` at `sonic3k.asm:104722-104771`), the engine's
`executeActTransition()` ran `restoreCameraBoundsForCurrentLevel()`
to pick up AIZ2 camera bounds but did NOT refresh the CPU bound
overrides. The next frame's `doLevelBoundary` therefore saw stale
AIZ1-boss-arena bounds (`minXBound = maxXBound = 0x2F10`),
computed `leftBoundary = 0x2F20`, found `predictedX = 0x00B2 < 0x2F20`,
and clamped Tails to `0x2F20` — teleporting the sidekick across the
entire AIZ2 reload offset (-0x2F00).

ROM has no analogous bug: its act-2 reload resets
`Camera_min_X_pos` / `Camera_min_Y_pos` (sonic3k.asm:104758-104762),
and Tails-CPU code reads those camera fields directly — there is no
separate "Tails CPU bounds" storage to fall out of sync.

### Fix

Step 7b in `executeActTransition()` now iterates
`spriteManager.getSidekicks()` after the camera bounds restore and
calls `cpu.setLevelBounds(cam.getMinX(), cam.getMaxX(),
max(cam.getMaxY(), cam.getMaxYTarget()))` to refresh each sidekick's
CPU bound override to the new act's camera bounds. This matches the
ROM semantics (camera reset = sidekick bound reset) without affecting
the per-frame `syncSidekickBoundsToCamera()` flow that was already
running successfully on every non-transition frame.

### Result

- AIZ first strict error advances F5497 → F5736 (1185 → 1184 errors).
  F5736 is a fresh tails_x_speed divergence in the AIZ2 main gameplay
  region.
- Cross-game baselines unchanged: S1 GHZ PASS, S1 MZ1 F311, S2 EHZ
  F1151, S3K CNZ F2175.


---

## AIZ1 Tails Rolling-Airborne Post-Jump y_speed Divergence (Trace Frame 2202)

**Location:** `PlayableSpriteMovement.doJumpHeight` or flight-gravity gate — the exact code path that mishandles Tails's rolling-jump airborne velocity is still open.
**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`, first strict error at frame 2202.

### Status

Frame 2150 was an earlier divergence on a `0xFA` slope zero-distance landing; that was fixed by narrowing the `Sonic3kZoneFeatureProvider.shouldTreatZeroDistanceAirLandingAsGround` angle window to exactly-flat (0x00 or 0xFF) per commit `ad232cf10`. The AIZ replay's first strict error has since moved to frame 2202.

### Symptom

At frame 2202 the engine produces `tails_y_speed = -0x02E0` vs the ROM's expected `-0x03E0` (engine is 0x0100 subpixels less upward than the ROM). Seed state at frame 2201: Tails `y_vel = -0x0418`, rolling+airborne (status 0x07, no rolling-jump bit), jumping flag set from a frame-1947 rolling jump.

The expected result is standard +0x38 air gravity: `-0x0418 + 0x38 = -0x03E0`. The engine is applying an extra ~0x0100 downward velocity somewhere in the air-movement path.

### Suspected Cause

The extra 0x100 matches `Tails_JumpHeight`'s jump-release cap behaviour: ROM `sonic3k.asm:28592` clips `y_vel` to `-0x0400` when `jumping` is set, `y_vel < -0x0400`, and no A/B/C button is *just-pressed* this frame. After the clip, gravity adds +0x38 → `-0x03C8`, still 0x018 above the engine's observed value. The remaining difference (and the 0x100 total gap) likely comes from a layered interaction: CPU-Tails input replay (button-press vs button-held distinction), rolling-airborne animation state, or an air-collision side-effect. Needs instrumentation.

### Removal Condition

Remove once `TestS3kAizTraceReplay`'s first strict error moves past frame 2202, OR a unit test pins the exact per-frame y_speed divergence for CPU-Tails rolling-airborne and a fix lands.

---

## CNZ1 Trace F1758 — Wire Cage Airborne-Capture object_control Bit 0 Missing (FIXED)

**Status:** Fixed in iter-14 by mirroring ROM `loc_3394C` (sonic3k.asm:69921) airborne-capture path in `CnzWireCageObjectInstance.tryLatch`. ROM sets `bset #0, object_control(a1)` (and cooldown=1) when capturing an airborne player, which gates `Tails_Modes` dispatch the next frame at `loc_1384A` (sonic3k.asm:26211). Engine was taking the grounded `loc_33958` branch when its terrain pass had pre-grounded Tails on the cage rim by the time the cage update ran (engine runs player physics first then objects). Fix: snapshot the player's pre-physics state at the start of `PlayableSpriteMovement.handleMovement` (new `capturePrePhysicsSnapshot`), then in `tryLatch` use the pre-physics air/angle/gSpeed (falling back to post-physics state for unit tests bypassing the physics tick). Hydration test seam (`AbstractTraceReplayTest.applyRecordedFirstSidekickState`) now also preserves `objectControlled` when the engine has the wire cage latched, since the trace CSV does not capture `object_control` and the existing hydration was zeroing the bit between cage capture and the next frame's physics gate. First strict error advanced F1758 → F1791 (next-frame Tails CPU AI auto-jump trigger / `Ctrl_2_logical` mirror — overlaps with AIZ F2721 work).

### Pre-iter-14 Status (preserved for context)

Iter-13 refined the iter-11 F1740 fix by replacing the `move_lock > 0` short-circuit in `restoreObjectLatchIfTerrainClearedIt` with a new `slopeRepelJustSlipped` per-tick flag. The flag is cleared at the start of every `handleMovement` call and set inside `Player_SlopeRepel` only on the frame where `bset #Status_InAir` actually fires.

Result: F1758's `tails_angle` (0x40), `tails_x` (0x134F — cage orbit position), `tails_y` (0x0709), `tails_air` (0), and `tails_ground_mode` (1) all match expected. First error field shifted from `tails_angle mismatch` to `tails_y_speed mismatch` (expected 0x0147, actual 0x0291). Total errors 4678 → 4625.

The remaining `tails_g_speed`/`tails_y_speed` divergence (engine 0x291, expected 0x271 at F1758 onward) is a slope-resist suppression issue: ROM appears to skip `Player_SlopeResist` for Tails on the cage from F1758 onward (no `+0x20` per frame at angle 0x40), while the engine adds `+0x20` per frame. Likely cause: ROM has set `bit 0` of `object_control(a0)` from the cage's `loc_339B6: bset #0, object_control(a1)` path (line 69958), which gates the entire physics dispatch in `loc_10BFC` (sonic3k.asm:21973-21976) and skips `Sonic_Modes`. Without per-frame `object_control(a0)` recording, can't verify this hypothesis directly.

### Pre-iter-13 Status (preserved for context)

The F1740 fix advanced the first strict error to F1758. ROM trace F1757 → F1758:
- **F1756**: Tails airborne (air=1, angle=0, mode=0), engine matches.
- **F1757**: Tails LANDS on slope at angle 0x40, mode 1, air=0. Engine matches.
- **F1758**: ROM teleports Tails x: 0x1309 → 0x134F (Δ +0x46) with x_speed=0, status=0x08 (on_object only, NO in_air). Engine releases Tails to airborne (angle=0, air=1) instead of staying on cage orbit.

The +0x46 x teleport at F1758 with x_speed=0 is the cage's `loc_33A50/loc_33BBA` orbit positioning (`x = cage.x + cosine(phase)/4 + y_radius * cosine(phase) / 256`). For phase ≈ 0x04 with cage.x = 0x1300 and y_radius = 19, that yields x ≈ 0x1352, which matches the trace 0x134F within rounding. So the cage RECAPTURED Tails between F1740 and F1757 (cooldown $10 expired by F1756), then ran the orbit at F1758.

### Diagnosed Cause

The engine's slope-repel correctly slips Tails into air at F1758 (angle 0x40 + gSpeed 0x271 < 0x280 sets Status_InAir, move_lock=30). Engine's cage `continueRide` then sees:
- `state.cooldown == 0` (latched at F1757, no release yet)
- `player.getAir() == true` (slope-repel slip)
- `gSpeed < MIN_SPEED_TO_CONTINUE (0x300)`
- → enters cooldown branch → `updateReleaseRide(player, state)` → in_air → `release()`

ROM appears to take a different path. By manual trace of `loc_339A0` with `gSpeed < 0x300 → loc_339B6 → set cooldown → bra loc_33ADE → loc_33B1E → in_air → bne loc_33B62 → release`, ROM should ALSO release. But the trace shows ROM at F1758 has status=0x08 (on_object only, NO in_air). That status pattern is inconsistent with both the slope-repel slip path AND the cage release path — one or both didn't fire in ROM at this frame.

Without per-frame `Tails_CPU_routine`, `Ctrl_2_logical`, and `move_lock` recording, pinpointing why ROM kept Tails on cage at F1758 (while engine releases) requires either (a) regenerating the trace with the v6 recorder extension that's already committed, or (b) stepping through ROM in BizHawk for F1755-F1760 to capture the exact ROM path firing.

### Required Investigation / Fix

1. **Regenerate the CNZ trace** with the v6 recorder extension committed in commit `4e6a2b77a` (`feat(trace): add per-frame Tails CPU state recorder + parser plumbing`). The new per-frame `cpu_state` events would expose `Tails_CPU_routine`, `move_lock`, `Status_InAir`, and `Ctrl_2_logical` at every frame so the F1758 ROM path can be observed directly. Trace regeneration was attempted but blocked by a BizHawk chromeless headless emulation issue in this environment.
2. Without per-frame CPU state, alternative is to step through ROM in BizHawk for F1755-F1760 with breakpoints on `Player_SlopeRepel`, `sub_338C4`, and `loc_33ADE` to capture the exact ROM path firing.

### Removal Condition

Met: `TestS3kCnzTraceReplay`'s first strict error has advanced past F1758 to F1791.

---

## CNZ1 Trace F1791 — Tails CPU Auto-Jump Trigger Bit-7 Object Control Gate (FIXED)

**Status:** Fixed in iter-15 by porting ROM's bit-7 (`bmi.w`) object_control gate to the engine's `SidekickCpuController.updateNormal()` early-out. ROM `Tails_Normal` Part 2 entry at `sonic3k.asm:26672` does `tst.b object_control(a0); bmi.w loc_13EBE` — only the sign bit (bit 7) suppresses Tails_CPU_Control. Bits 0-6 (CNZ wire cage's `$42`, MGZ twisting loop's `$43`, etc.) leave the CPU dispatcher running so the auto-jump trigger at `loc_13E9C` (sonic3k.asm:26775) can keep firing while the player is held by the controlling object — that is what lets ROM write `Ctrl_2_logical = 0x7878` so the cage's `loc_33ADE` (sonic3k.asm:70052) reads jump-pressed and launches Tails with `x_vel = -0x800, y_vel = -0x200`.

**Location:** `SidekickCpuController.updateNormal()` early-out, `AbstractPlayableSprite.objectControlAllowsCpu` flag, `CnzWireCageObjectInstance.beginLatchedCooldown()` and `continueRide()` cooldown paths.

### Diagnosed Cause

Engine `setObjectControlled(true)` is a single boolean covering both ROM bit 7 (full CPU + physics suppression: flight `$81`, despawn marker `$81`, super state `$83`, debug `$83`) and ROM bits 0-6 (per-object physics ownership where CPU still runs: cage bits 1+6, MGZ twisting loop bits 0+1+6). Engine's CPU controller was treating both cases identically and skipping the auto-jump trigger when Tails was riding the cage, so `inputJumpPress` was never set, and the cage's `tryJumpRelease` (which polls `player.isJumpJustPressed()`) never fired.

### Fix

1. New `objectControlAllowsCpu` flag on `AbstractPlayableSprite` (default `false` to preserve existing engine behaviour for bit-7 callers). Cleared automatically by `setObjectControlled(false)`. Bits-0-6 callers must set the flag alongside `setObjectControlled(true)` each time they re-assert physics ownership.
2. `SidekickCpuController.updateNormal()` early-exits only when `isObjectControlled() && !isObjectControlAllowsCpu()`, mirroring ROM's sign-bit-only `bmi.w`.
3. `CnzWireCageObjectInstance` sets the flag in `beginLatchedCooldown()` (airborne capture) and the two cooldown branches of `continueRide()` (release-cooldown, low-speed continuation).

### Result

- First strict error advances F1791 → F1815 (5144 → 5080 errors).
- Cross-game baselines unchanged (S1 GHZ pass, S1 MZ1 F311, S2 EHZ F1151, S3K AIZ F2919).
- F1815 is a 1-frame landing-detection timing divergence (engine Tails lands 1 frame after ROM); engine and ROM converge from F1816 onwards.

### Removal Condition

Met: `TestS3kCnzTraceReplay`'s first strict error has advanced past F1791 to F1815.

---

## CNZ1 Trace F1740 — Wire Cage `restoreObjectLatchIfTerrainClearedIt` Overrode Slope-Repel Slip (FIXED)

**Status:** Fixed in iter-11 by short-circuiting `restoreObjectLatchIfTerrainClearedIt` when `move_lock > 0`.

ROM `Player_SlopeRepel` (sonic3k.asm:23907) has NO `Status_OnObj` gate — it runs even when on object, and slips the player into air (`bset #Status_InAir`) when |gSpeed| < `$280` at a steep angle. The cage's released path (`loc_33ADE` → `loc_33B1E` → `bne loc_33B62`) then honours the in_air bit and runs a simple release that preserves `y_vel = -gSpeed` (no launch impulse — that's `loc_339CC` which only fires from the active-ride branch when gSpeed >= `$300`).

Engine's slope repel did fire and set `air = true` correctly (S3K's `slopeRepelChecksOnObject = false` ensured the on-object gate didn't block it). But the cage's `restoreObjectLatchIfTerrainClearedIt` hack — added to compensate for the engine's terrain-probe being too aggressive about marking the player airborne under invisible level art — reverted it, keeping Tails on the cage. Adding a `move_lock > 0` short-circuit lets the cage honour the slope-repel slip while preserving the original hack's behaviour for the terrain-probe case (terrain probes don't set move_lock).

First strict error advanced F1740 → F1758.

---

## CNZ1 Trace F1685 — Tails CPU Spurious Despawn on Barber-Pole→Wire-Cage Object Switch (FIXED)

**Status:** Fixed in iter-10 by gating the engine's despawn-on-object-id-mismatch path behind `ObjectInteractionRules.sidekickDespawnUsesObjectIdMismatch`. S3K disables the path because ROM's `sub_13EFC` (`sonic3k.asm:26823`) compares the high word of the cached vs current object's routine pointer (`cmp.w (a3),d0`), and all S3K gameplay objects share the same high word `0x0003`, making the ROM check effectively dormant. Engine was comparing 8-bit object IDs (`0x4D` barber pole vs `0x4E` wire cage) and triggering despawn on legitimate same-region transitions. S2 keeps the existing behaviour because `TailsCPU_CheckDespawn` (`s2.asm:39067`) genuinely does compare object id bytes.

**Location:** `SidekickCpuController.checkDespawn()`, `ObjectInteractionRules.sidekickDespawnUsesObjectIdMismatch`.
**Trace reference:** `src/test/resources/traces/s3k/cnz`, first strict error at frame 1685.

### Symptom (pre-fix)

`tails_y_speed mismatch (expected=-0D51, actual=-0B22)` at F1685, plus a cluster of cascading mismatches because the engine had Tails despawned to `(0x7F00, 0)` while the ROM kept Tails alive on the wire cage.

### Diagnosed Cause

ROM `sub_13EFC` reads the FIRST WORD of the object's data structure (`(a3)` = high 16 bits of the routine pointer) and compares against the cached `Tails_CPU_interact`. For S3K, all gameplay objects live in `0x0001xxxx-0x0007xxxx`, so the high word is identical for virtually every object the player can be on; the check therefore almost never fires in real ROM play. Engine instead compared 8-bit object IDs, which DO differ between any two distinct object types — at F1685 Tails legitimately moved from the CNZ barber pole (id `0x4D`, routine `loc_335A8`) to a CNZ wire cage (id `0x4E`, routine `loc_3385E`). Both routines live at `0x000338xx`, so ROM's high-word comparison would yield `0x0003 == 0x0003` (no despawn); engine's id comparison yielded `0x4D != 0x4E` (spurious despawn).

### Removal Condition

Removed when `TestS3kCnzTraceReplay`'s first strict error advanced past F1685.

---

## AIZ1 Trace F2667 — Sidekick vs. Spike Side-Push Triggers Where ROM Does Not (FIXED)

**Status:** Fixed in iter-12 by adding the ROM `SolidObject_cont` on-screen
gate to `ObjectManager.SolidContacts.processInlineObjectForPlayer`,
gated for S3K via `CollisionRules.solidObjectOffscreenGate`. The v6.2-s3k
recorder added per-frame `object_state` and `interact_state` events that
made the camera-vs-spike geometry directly inspectable; with those events
the ROM control flow at F2667 became unambiguous. First strict error
advanced from F2667 to F2721 (a downstream Tails CPU-AI jump-trigger
divergence) without touching S1/S2 trace baselines.

**Location:** `ObjectManager.SolidContacts.processInlineObjectForPlayer` /
`resolveContactInternal` side-path zeroing on the AIZ1 top-spike
(`Sonic3kSpikeObjectInstance` slot 22, ROM `loc_24090` at
sonic3k.asm:49011).
**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`,
first strict error at frame 2667 (after the iter-10 F2590 fix).

### Iter-12 Diagnosis (correct)

The v6.2-s3k recorder per-frame events at F2667 showed:

* Slot 22 spike: `x=0x1C80, y=0x03C0, status=0x00` (no p2_standing bit).
* Tails: `interact=0xB65C` (= slot 22, sticky from prior `sub_24280` hurt
  path), `object_control=0x00`.
* Camera at F2667: `x=0x1C99, y=0x035A`.
* Spike right edge in screen space: `0x1C80 + 0x10 - 0x1C99 = -9` -- the
  spike is fully off-screen left.

Hand-tracing ROM `Render_Sprites` (sonic3k.asm:36336-36370) confirms that
when the spike was last rendered (end of F2666) its bounding-box right
edge was already to the left of `Camera_X_pos_copy`, so Render_Sprites
cleared `render_flags` bit 7 via `bmi.s Render_Sprites_NextObj`.

At F2667 Obj_Spikes runs `loc_24090 -> SolidObjectFull -> SolidObjectFull_1P`
for Player_2.  With slot 22's standing bit clear, control reaches
`loc_1DF88` (sonic3k.asm:41390) which executes `tst.b render_flags(a0);
bpl.w loc_1E0A2`. Bit 7 is clear -> the branch is taken to
`loc_1E0A2 -> sub_1E0C2` (sonic3k.asm:41528) which only clears the
push-state bookkeeping and exits with `moveq #0, d4`. The collision /
side-push branches (`SolidObject_cont` at 41394, `loc_1E042` at 41468)
are not entered, so ROM never zeroes Tails's `ground_vel` or `x_vel`.
ROM CSV is consistent: tails_x_speed accelerates uninterrupted through
0x01B0 -> 0x01BC -> 0x01C8 across F2666-F2668 with no zeroing.

The S2 disassembly even documents this gate:

> SolidObject_OnScreenTest:
>   ; If the object is not on-screen, then don't try to collide with it.
>   ; This is presumably an optimisation, but this means that if Sonic
>   ; outruns the screen then he can phase through solid objects.
>   _btst #render_flags.on_screen,render_flags(a0)
>   _beq.w SolidObject_TestClearPush

(s2.asm:35140-35145.) S1 has the same shape via `Solid_ChkEnter` at
`s1disasm/_incObj/sub SolidObject.asm:124-126`.

### Iter-12 Fix

* Added `ObjectInstance.isWithinSolidContactBounds()` (default true) and an
  `AbstractObjectInstance` override that mirrors ROM `Render_Sprites`'s
  bit-7 semantic via `cameraBounds.contains(getX(), getY(), 16)` -- the
  16-pixel margin matches the typical ROM `width_pixels` for gameplay
  solids and avoids depending on `SolidObjectParams.halfWidth` (which
  reflects collision halfwidth, not render extent).
* Added `CollisionRules.solidObjectOffscreenGate` (S3K=true, S1/S2=false
  for now to keep current trace baselines stable while the on-screen
  semantic is validated game-by-game).
* Inserted the gate in
  `ObjectManager.SolidContacts.processInlineObjectForPlayer`, after the
  riding-state branch (so MvSonicOnPtfm-equivalent platform carry stays
  unaffected) and before `resolveContact`. When the gate fires, only
  `provider.setPlayerPushing(player, false)` runs, mirroring ROM
  `sub_1E0C2`.

### Removal Condition

This entry remains as the iter-12 retrospective; the F2667 entry can be
deleted in a later cleanup pass. The S1/S2 false setting on
`solidObjectOffscreenGate` is intentional and should be revisited when
the S1 GHZ1 / MZ1 / S2 EHZ1 trace baselines are re-grounded.

### Recorder Inputs (iter-12)

The F2590 fly-back-exit-gate fix advanced the first-divergence frame to
F2667. Side-log probe instrumentation (temporary, removed) confirmed:

* Engine Tails position matches ROM exactly from the F2640 hurt-bounce
  landing through F2666 (running rightward with the same accelerating
  `g_speed`/`x_speed = 0x01B0 → 0x01A4 → ...` per frame).
* At engine fc=2378 (= trace F2667), engine sees Tails enter the spike's
  collision box at `playerX = 0x1C67` (spike x = 0x1C80, halfWidth = 27,
  `relX_raw = 2`). `resolveContactInternal` classifies as side contact
  (X-overlap = 2, Y-overlap = 31, Y > X) → side path → zeroes both
  `xSpeed` and `gSpeed`, snaps player back to `0x1C65`, sets `pushing=true`.
* ROM CSV at F2667 shows Tails kept moving (`x_speed = 0x01BC`,
  `g_speed = 0x01BC`) with no stop — implying ROM took a code path that
  preserved velocity.

### Open question

Hand-tracing ROM `SolidObject_cont` (sonic3k.asm:41394) with the same
inputs (`d0=2, d1=halfW=27, d2=height=16, d3=pY-oY+4=4, d4=spikeX,
default_y_radius=15, y_radius=15`) follows the same algebra to
`loc_1E034` and falls into `loc_1E042` (side path). At `tst.w d0` the
register is non-zero (positive, 2), `bmi` not taken, `tst.w x_vel`
positive, so `bra loc_1E056` zeros `ground_vel` and `x_vel` — exactly
the engine's behaviour. ROM's `interact(a0)` is set to slot 22 from
F2590's `sub_24280` hurt path (the recorder reports
`sidekick_stand_on_obj=22` continuously through F2640-F2670), but
`Status_OnObj` (bit 3) is cleared in the recorded `sidekick_status_byte`
at every post-landing frame.

The discrepancy implies one of:
1. ROM's spike (slot 22) is not running its `loc_24090` body at F2667
   (some out-of-range / proximity gate the engine doesn't replicate;
   `Sprite_OnScreen_Test2` at sonic3k.asm:37281 deletes the sprite when
   `(x_pos & 0xFF80) - Camera_X_pos_coarse_back > 0x280`, but the
   trace's `object_appeared`/`object_removed` events on slot 22 show it
   alive from F2474 to F2774, spanning F2667).
2. ROM has the spike's `p2_standing_bit` set from a path I haven't
   traced (e.g. an earlier frame's `loc_1E154` landing branch with
   `d3 ∈ [0, 0x10)` and player y_vel ≥ 0 calling `RideObject_SetRide`).
   Hand-tracing F2640 onwards the d3 transformations stay negative
   (Tails y > spike y), so the standing path is never entered through
   the conventional landing flow visible in the disassembly.
3. The `Sonic_AnglePos` re-grounding pass between Tails' physics and
   the spike's update sets `interact` and the spike's standing-bit via
   a path outside `SolidObjectFull` (e.g. ground collision detecting
   the spike as a solid surface and routing through a different solid
   handler).

### Attempted Fixes (reverted)

- **Iter-11a:** Added `ObjectManager.getObjectAtSlot(int)` and
  `ObjectManager.setRidingObject(player, instance, pieceIndex)` plus a
  call from `applyRecordedFirstSidekickState` that hydrates engine
  `SolidContacts.ridingStates` from the recorded
  `sidekick_stand_on_obj` slot whenever the recorded
  `Status_OnObj` bit (`statusByte & 0x08`) is set. The bit is never
  set for Tails post-F2640 in this trace, so the gate never fired —
  no measurable effect on F2667. Reverted to keep the diff minimal.
- **Iter-11b:** Same hydration with the gate dropped (always set when
  `standOnObj > 0`). Made things slightly worse (1208 → 1212 errors)
  by registering riding state during the F2590-F2640 hurt-bounce when
  ROM's `interact` field still pointed at the spike from `sub_24280`
  but Tails was airborne. Reverted.
- **Iter-11c:** Removed the `distX==0 && movingInto → zero speed`
  block in `resolveContactInternal`'s pre-movement side path (S2/S3K
  branch only). Did not affect F2667 because the `relX = 2` (not 0)
  at the divergent frame still routes to the standard
  `distX != 0 && movingInto` zero. Reverted.

The v6.2-s3k recorder bumped from v6.0-s3k by adding two diagnostic
event types (additive, no schema bump):

* `object_state` -- per-frame snapshot for every OST slot within
  `OBJECT_PROXIMITY` (160 px) of either Player_1 or Player_2: routine
  pointer, status byte (offset $22), subtype (offset $1C), x_pos /
  y_pos / x_radius / y_radius. For the Obj_Spikes top-facing variant
  ($24090) bits 3 and 4 of `status` reflect `p1_standing` /
  `p2_standing` (sonic3k.constants.asm
  `standing_mask = p1_standing|p2_standing`).
* `interact_state` -- per-frame snapshot per active player: `interact`
  field at offset $42 (RAM address of last object stood on, set by
  `RideObject_SetRide` / `loc_1E154`), the resolved OST slot index,
  and `object_control` (offset $2A) -- bit 7 set causes ROM
  `SolidObject_cont` (sonic3k.asm:41439) to take the `bmi.w loc_1E0A2`
  branch which skips side-push velocity zeroing.

The diagnostic events are advertised via `aux_schema_extras`
(`object_state_per_frame`, `interact_state_per_frame`) so parsers can
query them with `TraceMetadata.hasPerFrameObjectState()` /
`hasPerFrameInteractState()`. The events are READ-ONLY diagnostic input
to the trace-replay test; they are NOT hydrated into engine object state
(per the comparison-only invariant of trace replay). The fix uses them
to identify the ROM control-flow divergence and then matches ROM
natively in engine code.

---

## AIZ1 Trace F2590 — Tails Fly-Back Exit Gate Per-Game Mask Mismatch (FIXED)

**Status:** Fixed in iter-10 by splitting `TailsRespawnStrategy.updateApproaching`'s exit-gate mask per game via `SidekickCpuRules.sidekickFlyLandStatusBlockerMask` and `sidekickFlyLandRequiresLeaderAlive`. Kept here for context — the F2590 entry rolled forward through several investigations before the per-game divergence was identified.

**Location:** `TailsRespawnStrategy.updateApproaching` (engine APPROACHING → NORMAL transition, equivalent to ROM `Tails_FlySwim_Unknown` exit at sonic3k.asm:26622-26648 / s2.asm:38870-38883).

**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`, first strict error at frame 2590.

### Symptom (pre-fix)

`tails_y_speed mismatch (expected=-0400, actual=0x02D8)` at F2590.

### Iter-10 Diagnosis (correct)

Side-log probe (`AizF2590SideLog` — temporary, removed after the fix
landed) showed engine Tails was permanently locked in
`SidekickCpuController.State.APPROACHING` with `object_controlled = true`
through the entire descent that culminated in F2590, so
`processInlineObjectForPlayer` short-circuited every spike
`onSolidContact` callback and Tails never received the standard
hurt-bounce.

`updateApproaching` could not exit because its status-blocker mask
was hard-coded to `0xD2`, the **S2** value (s2.asm:38872-38873
`andi.b #$D2,d2 / bne return`). Bit 1 (in_air) of Sonic's recorded
status was set throughout the descent, so the gate refused to land.

ROM **S3K** at the same call site (sonic3k.asm:26625) uses
`andi.b #$80,d2` — bit 7 only, which is not a real Sonic status flag.
S3K's gate practically never blocks, and lands as soon as residuals
are zero. ROM S3K also adds a leader-alive check that S2 lacks
(sonic3k.asm:26629-26630 `cmpi.b #6,(Player_1+routine).w / bhs`).

### Iter-10 Fix

- `SidekickCpuRules`: added two fields:
  - `sidekickFlyLandStatusBlockerMask` — `0xD2` (S2) / `0x80` (S3K) /
    `0` (S1, no CPU sidekick).
  - `sidekickFlyLandRequiresLeaderAlive` — `false` (S1/S2) / `true`
    (S3K, ROM `cmpi.b #6,(Player_1+routine).w`).
- `TailsRespawnStrategy.updateApproaching` now reads both fields from
  `sidekick.getGameRules().sidekickCpu()`. The legacy `0xD2` mask is kept
  as a fallback when no rules are resolved (legacy unit tests
  that build a sprite without a game module).
- `CrossGameFeatureProvider` and `TestHybridGameRules`
  threaded through the new fields.

### Effect on AIZ trace replay

- First strict divergence frame moved 2590 → 2667.
- Errors 1558 → 1208. Warnings 2010 → 1633.

ROM at F2667 has Tails getting hurt-bounced again and recovering;
the engine doesn't yet — that's a separate divergence that surfaces
once the F2590 spike landing actually fires in the engine.

---

## CNZ1 Trace F825 — Tails Off-Screen Recovery Teleport Target Mismatch (FIXED)

**Status:** Fixed in commit `19ed59532`. Kept in this doc as the F825 entry rolled forward through several follow-up fixes (despawn-X marker, fc alignment, SPAWNING-gate) that consumed it.

**Location:** `SidekickCpuController` flight-recovery / off-screen teleport path. ROM `sub_13ECA` (sonic3k.asm:26800) teleports Tails to `(0x7F00, 0x0000)` when the catch-up routine resets; the engine's equivalent path teleports to `(0x4000, 0x0000)` instead.
**Trace reference:** `src/test/resources/traces/s3k/cnz`, first strict error at frame 825.

### Status (2026-04-24)

Frame 318 was caused by `AbstractTraceReplayTest.applyRecordedFirstSidekickState` calling `setMoveLockTimer(0)` every frame, defeating ROM `Player_SlopeRepel`'s 30-frame move_lock counter. Fixed in commit `28940b604`. First error moved 318 → 383.

Frame 383 was the Tails-CPU jump-trigger gate failing its 64-frame mask check because `SpriteManager.frameCounter` lagged `Level_frame_counter` by 1: ROM had `gfc=0x180` (mask=0, fires) at the moment the engine had `fc=0x17F` (mask=0x3F, no fire). The lag came from CNZ's seed state being recorded at `T_0.gfc=1` (gameplay starts immediately) while engine fc starts at 0 — for AIZ the seed is at `T_289.gfc=0` (still inside the intro, ROM's `Level_frame_counter` not yet incrementing) so engine fc=0 happens to align naturally. Fixed by pre-setting `SpriteManager.frameCounter = T_(K_start-1).gfc` at the trace replay loop start in `AbstractTraceReplayTest.replayS3kTrace` so engine `fc++` on each step keeps pace with ROM `Level_frame_counter` for both traces. Total errors 4997 → 4946 (−51); first error moved 383 → 825.

### Symptom (current)

At trace frame 825, ROM `tails_x = 0x7F00, tails_y = 0x0000, tails_air = 1`; engine `tails_x = 0x4000, tails_y = 0x0000, tails_air = 1`. Sonic state matches the trace exactly through this and many surrounding frames (`x=0x0997, y=0x0703, x_speed=0x05BB`, on-ground, rolling). The trace shows `sidekick_routine=2` here, indicating the ROM has just entered `Tails_CPU_routine = 2` (`Tails_Catch_Up_Flying`) — which is reached via `sub_13ECA` (sonic3k.asm:26800):

```
sub_13ECA:
    ...
    move.w  #2,(Tails_CPU_routine).w
    move.b  #$81,object_control(a0)
    move.b  #1<<Status_InAir,status(a0)
    move.w  #$7F00,x_pos(a0)
    move.w  #0,y_pos(a0)
    ...
```

The engine takes the same routine transition but writes `x_pos = 0x4000` instead of `0x7F00`. The teleport is a marker meaning "Tails is despawned, waiting to fly back in"; the exact value matters because the next frame's bounding/active checks use it.

### Suspected Cause

Engine's `SidekickCpuController.CATCH_UP_FLIGHT` / `FLIGHT_AUTO_RECOVERY` enter logic, or the despawn helper that runs when Tails leaves the camera bounds long enough, hardcodes `0x4000` somewhere instead of `0x7F00`. ROM's marker is the magic value `0x7F00` at multiple sites (`sub_13ECA`, dynamic Tails respawn). Search for `0x4000` constants in the sidekick / respawn / catch-up paths, or for any place the engine clamps a teleport X to a level-bounds-derived value.

### Removal Condition

Remove once `TestS3kCnzTraceReplay`'s first strict error moves past frame 825 AND the engine writes `tails_x = 0x7F00` on the catch-up-flight init frame.


---

## AIZ1 Trace F2202 -- Phantom MonkeyDude Respawn Triggers Spurious Sidekick Bounce (FIXED)

**Location:** `ObjectManager.Placement` (S3K spawn windowing) /
`AbstractS3kBadnikInstance.defeat` -> `removeFromActiveSpawns`.

**Symptom (resolved):** `TestS3kAizTraceReplay#replayMatchesTrace` first
strict divergence at frame 2202: `tails_y_speed expected=-0x3E0,
actual=-0x2E0` (delta `+0x100` = an extra enemy-defeat bounce). Tails
was at `(0x1845, 0x0421)` flying past a MonkeyDude that ROM destroyed at
F1722 via Sonic. ROM has the MonkeyDude permanently absent from this
spawn point through F2202; the engine had it alive again, so when Tails
overlapped it, the engine applied the `+0x100` bounce ROM never fires.

**Root cause (confirmed):** The engine's placement system was clearing
`destroyedInWindow` whenever a destroyed spawn's position fell outside
the live placement window (called from `trimLeftCountered`,
`trimRightCountered`, `trimLeftNonCounter`, `trimRightNonCounter`,
`refreshWindow`, `refreshCounterBased`, and the post-window-update else
branch in `update`). ROM's equivalent flag is bit 7 of
`Object_respawn_table[respawn_index]`, set by the cursor spawn helpers
(`sonic3k.asm` `loc_1BA40` / `loc_1BA92` / `sub_1BA0C` `bset #7,(a3)`)
and cleared **only** when a still-alive object self-destructs via
`Sprite_OnScreen_Test` / `Delete_Sprite_If_Not_In_Range` /
`Sprite_CheckDeleteTouch` (`sonic3k.asm:37271-37388` family --
`bclr #7,(a2)`). Once a player kill replaces the badnik with
`Obj_Explosion`, that path never runs, so bit 7 stays set permanently
until level init wipes the table at `loc_1B784`. Mirroring this, the
engine now leaves `destroyedInWindow` latched after a kill and only
clears it on level reset; `dormant` continues to handle the
alive-offscreen out-of-range case.

**Discovered via:** Fix for AIZ trace F3834 (sidekick enemy-bounce
self-destroy gating) which previously masked this divergence by
incorrectly skipping the bounce whenever Tails herself destroyed the
phantom badnik.

**Resolution:** `ObjectManager.Placement.permanentDestroyLatch` was
added (enabled via `ObjectManager.enablePermanentDestroyLatch()` in
`LevelManager` when `gameModule.getGameId() == GameId.S3K`).
`removeFromActive` only sets `destroyedInWindow` when the flag is on,
and the helper `clearDestroyedLatchOutsideWindow` was removed entirely
so the latch never clears mid-level. S1 / S2 do NOT enable the flag --
their ROM `ObjectsManager_Main` (docs/s2disasm/s2.asm:33402,
`tst.b 2(a0); bpl.s +`) only latches respawn-tracked spawns, modeled
by the engine's separate `remembered` flag, so non-tracked S1 / S2
spawns continue to re-spawn on cursor re-entry. After the fix
`TestS3kAizTraceReplay` first strict error advances past F2202 to
F4679 (a separate Tails respawn divergence). All baselines stay green:
S1 GHZ PASS, S1 MZ1 F311, S2 EHZ F1151, S3K CNZ F1815.

---

## AIZ Trace F5736 — Level_frame_counter Skips Tick on Seamless Act Reload (FIXED)

**Status:** Fixed by bumping `SpriteManager.frameCounter` by 1 inside
`LevelManager.applySeamlessTransition()` for `RELOAD_SAME_LEVEL` and
`RELOAD_TARGET_LEVEL` transitions.

**Location:** `LevelManager.applySeamlessTransition()` /
`LevelManager.advanceFrameCounterAcrossSeamlessReload()`,
`SpriteManager.frameCounter`,
`SidekickCpuController.updateNormal()` (loc_13E9C jump cadence gate).
**ROM Reference:** `sonic3k.asm` `VInt_0_Main` (Level_frame_counter
unconditionally incremented every gameplay frame),
`sonic3k.asm:26775 loc_13E9C` (Tails CPU 64-frame jump cadence gate
reading `(Level_frame_counter & $3F)`).

### Pre-Fix Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` first error at trace frame
5736:
```
tails_x_speed mismatch (expected=-00C4, actual=0x000C)
tails_y_speed mismatch (expected=-0680, actual=0x0000)
tails_air mismatch (expected=1, actual=0)
tails_rolling mismatch (expected=1, actual=0)
```

ROM Tails was stuck pushing against AIZ2 terrain (status_byte=0x20
across F5712-F5735) and the `loc_13E9C` 64-frame jump cadence gate
fired at gfc=0x1540 (`& 0x3F == 0`), launching Tails out of his stuck
state with the `Tails_Jump` initial velocity `y_vel = -$680`. Engine
Tails was also stuck pushing but never triggered the jump.

### Diagnosed Cause

After AIZ act 1 → act 2 reload (F5496) the engine's
`SpriteManager.frameCounter` lagged ROM's `Level_frame_counter` by
exactly 1 frame for the rest of the trace. `Level_frame_counter` is
incremented in ROM's VBlank handler unconditionally every gameplay
frame, including the act-reload frame. The engine equivalent
(`SpriteManager.frameCounter`) only ticks inside `SpriteManager.update()`,
which is itself called from `LevelFrameStep.execute()`. Both `GameLoop`
and `HeadlessTestRunner.stepFrame()` `return` early after applying a
seamless transition, never reaching `LevelFrameStep.execute()`, so the
counter does not tick on the reload frame.

The cumulative effect: the engine's `(frameCounter & 0x3F)` was 0x3F
when ROM's was 0, so the Tails-CPU auto-jump cadence gate stayed shut
on every frame thereafter.

### Fix

`LevelManager.applySeamlessTransition()` now calls
`advanceFrameCounterAcrossSeamlessReload()` after `executeActTransition()`
for `RELOAD_SAME_LEVEL` and `RELOAD_TARGET_LEVEL`. The helper bumps
`SpriteManager.frameCounter` by 1 to mirror the ROM VBlank handler tick
the engine otherwise misses.

`MUTATE_ONLY` transitions are intentionally NOT patched: the AIZ1
fire-transition art-overlay path (`Sonic3kAIZEvents.applyFireTransitionMutation`)
runs mid-frame without skipping the rest of the gameplay loop, so it
must not double-tick the counter.

### Test Results

After the fix `TestS3kAizTraceReplay` first strict error advances past
F5736 to F6066 (a separate Sonic-only divergence in AIZ2 main gameplay),
total errors drop from 1184 to 1178. All cross-game baselines stay
green: S1 GHZ PASS, S1 MZ1 F311, S2 EHZ F1151, S3K CNZ F2175.

---

## AIZ Trace F6066 — CaterKillerJr Missed Obj_WaitOffscreen Gate (FIXED)

**Location:** `src/main/java/com/openggf/game/sonic3k/objects/badniks/CaterkillerJrHeadInstance.java`
**Trace:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`
**Symptom:** ROM at trace F6066 has Sonic on the ground in AIZ2 narrow
corridor running left at `g_speed=-0x98`, `air=0`, `status=0x01`.
Engine has Sonic in air with `x_speed=-0x200`, `y_speed=-0x400`,
`g_speed=0`, `air=1`, `status=0x06` — exact `applyHurt(NORMAL)`
knockback signature (sourceX > centreX → x dir = -1 → x_speed =
0x200 \* -1 = -0x200; y_speed = -0x400 hardcoded).
Stack trace: `runTouchResponsesForPlayer →
TouchResponses.processCollisionLoop → handleTouchResponse → applyHurt`
with `hitter=CaterkillerJrHeadInstance objId=8F x=0x07EF y=0x0326`.

### Root Cause

ROM `Obj_CaterKillerJr` at `sonic3k.asm:183317-183323` begins:
```
Obj_CaterKillerJr:
    jsr (Obj_WaitOffscreen).l
    moveq #0,d0
    move.b routine(a0),d0
    move.w CaterKillerJr_Index(pc,d0.w),d1
    jsr CaterKillerJr_Index(pc,d1.w)
    jmp Sprite_CheckDeleteTouch(pc)
```

`Obj_WaitOffscreen` (`sonic3k.asm:180266-180297`) replaces the object's
`(a0)` code pointer with `loc_85AD2` and saves the original return
address at `$34`. `loc_85AD2` runs every frame thereafter:

```
loc_85AD2:
    tst.b render_flags(a0)
    bmi.s loc_85B02       ; on-screen X (bit 7 set) → restore real code
    move.w x_pos(a0),d0
    andi.w #$FF80,d0
    sub.w (Camera_X_pos_coarse_back).w,d0
    cmpi.w #$280,d0
    bhi.s loc_85AF0        ; too far → delete
    jmp (Draw_Sprite).l    ; otherwise just draw the offscreen indicator
```

So the badnik enters a frozen "wait" state (no swing, no movement) until
the camera catches up and the on-screen X bit gets set.

The S3K placement cursor advances 1 chunk per frame, allocating slots
at the chunk-boundary transition (cam_x crossing 0x80 boundaries).
Because S3K loads its window at `cameraChunk + 0x280`, the cursor
allocates the CaterKillerJr's slot ~40 frames before the camera reaches
the spawn x. ROM stays in the offscreen wait the whole pre-roll.

The engine's `CaterkillerJrHeadInstance.update()` had no equivalent
gate — the swing state machine ran from spawn frame, so the badnik
moved at `-0x100` x-velocity for those ~40 extra frames and ended up
~0x29 (41) pixels further left than ROM by the time Sonic arrived.

This matters in the AIZ2 narrow corridor at spawn x=0x0850 (camera
window crossing chunk 0x0600 at gfc=5670 vs ROM at gfc=5713). The
displaced engine badnik happens to overlap Sonic at gfc=5770 (trace
F6066), triggering hurt; the ROM badnik is still 41 px to the right.

### Fix

`CaterkillerJrHeadInstance.update()` now early-returns when
`!isOnScreenX()`. Other AIZ badniks already had this guard
(`BlastoidBadnikInstance`, `BuggernautBadnikInstance`,
`MonkeyDudeBadnikInstance`, `BatbotBadnikInstance`,
`TunnelbotBadnikInstance`) — CaterKillerJr was the missing case.

The fix does not affect body segments. Their ROM entry point
(`loc_8778C` at `sonic3k.asm:183389-183395`) does **not** call
`Obj_WaitOffscreen`; bodies always run, ticking `waitTimer` per frame
even off-screen. The engine's `CaterkillerJrBodyInstance` already
matches that.

### Test Results

After the fix `TestS3kAizTraceReplay` first strict error advances from
F6066 to F6255 (separate Tails despawn handoff). Total errors rise from
1178 to 6782 because the test no longer cascades from a single early
hurt event and now exposes downstream Tails AI divergences.
All cross-game baselines stay green: S1 GHZ PASS, S1 MZ1 F311, S2 EHZ
F1151, S3K CNZ F2222.

---

## CNZ1 Trace F2222 — Wire Cage Sidekick JUMP_RELEASE Spurious Fire (FIXED via ROM `d6` register corruption-by-`Perform_Player_DPLC` model)

**Status:** FIXED in v6.3-s3k iteration via post-leader-release sidekick
gate in `CnzWireCageObjectInstance`. Engine
Tails gets launched off the cage with `x_vel=-0x800, y_vel=-0x200`
(`JUMP_RELEASE_X/Y_SPEED`) at F2222 while ROM Tails stays on the cage.

**Location:** `CnzWireCageObjectInstance.tryJumpRelease()` reads
`player.isJumpJustPressed()` which is engine edge-detection on the
sidekick's `inputJump` (= `(recordedInput & INPUT_JUMP) != 0`). For
sidekicks, `recordedInput` is read from leader (Sonic's)
`inputHistory[historyPos - 16]` populated each frame from the active
player's effective post-`controlLocked` `logicalInputState`.

**Trace reference:** `src/test/resources/traces/s3k/cnz`, first strict
error at frame 2222 (`tails_y_speed expected=-02EA actual=-0200`).

### Symptom

Sequence around the divergence (CSV / aux from v6.2 recorder):

| K (trace) | gfc    | Sonic input | Sonic on cage | Engine cage | ROM cage |
|-----------|--------|-------------|---------------|-------------|----------|
| 2204      | 0x089D | 0x08 RIGHT  | yes (status=08, OnObj=04) | latched     | latched |
| 2205      | 0x089E | 0x18 RIGHT+JUMP | released (status=02) | release fires (-800/-200) | release fires (-800/-200) |
| 2206      | 0x089F | 0x18 RIGHT+JUMP | airborne | airborne | airborne |
| ...       |        |             |               |             |          |
| 2221      | 0x08AE | 0x08 RIGHT  | airborne | Tails on cage, y_vel=-02EA | Tails on cage, y_vel=-02EA |
| 2222      | 0x08AF | 0x08 RIGHT  | airborne | **Tails LAUNCHED y_vel=-0200, air=1** | Tails on cage, y_vel=-02EA |

v6.2 aux `cpu_state` events for Tails confirm:
- F2221: `cpu_routine=6` (NORMAL), `ctrl2_held=0x48`, `ctrl2_pressed=0x48` (RIGHT+button_A pressed)
- F2222: `cpu_routine=6`, `ctrl2_held=0x48`, `ctrl2_pressed=0x08` (RIGHT only, NOT pressed)

### Diagnosed Cause (engine side)

Engine `recordedInput[K-16]` for Tails at iter K=2222 reads Sonic's
`logicalInputState` from iter K=2206. `logicalInputState` is computed
in `SpriteManager.publishInputState` from `effectiveJump =
(!controlLocked && space) || forcedJump`, i.e. it filters out jump
when the per-sprite `controlLocked` (engine analogue of ROM
`object_control bit 0`) is set.

ROM `Sonic_RecordPos` at sonic3k.asm:22132 records `Ctrl_1_logical`
unconditionally — only the GLOBAL `Ctrl_1_locked` (cutscenes / death,
sonic3k.asm:21542) blocks the recording, never the per-sprite
`object_control bit 0`. So ROM Stat_table for K=2204 has JUMP cleared
(BK2 had no jump pressed) but K=2205 has JUMP set (newly pressed) and
K=2206+ has JUMP set (held).

In the engine, Sonic enters cage cooldown at K=2192 (`gspeed=0x02F4 <
0x300`) which calls `setControlLocked(true)`. After that, every frame
through K=2204 stays in `state.cooldown!=0` → `tryJumpRelease` +
`updateReleaseRide`, which never re-call `setControlLocked(false)`.
Sonic stays controlLocked until the cage's `release()` path fires at
K=2205 (which clears it). Meanwhile every frame K=2192..K=2204 the
engine records `effectiveJump=false` into `inputHistory[K]`.

This produces a 1-frame skewed JUMP edge: engine `inputHistory[2204]`
= no JUMP, `inputHistory[2205]` = no JUMP (controlLocked still true
when publishInputState ran at iter 2205), `inputHistory[2206]` = JUMP
(controlLocked cleared by cage release at end of iter 2205).

Tails's edge detection at iter K=2222 (reading K=2206) sees a
false-to-true transition between iter K=2221 (reads K=2205, no JUMP)
and iter K=2222 (reads K=2206, JUMP). `jumpInputJustPressed=true`,
the cage's `tryJumpRelease` fires `releaseWithJumpImpulse`.

### Mystery (ROM side, unresolved)

Even with raw input recording (held-only, ignoring controlLocked) the
edge would shift to iter K=2221 (reads K=2205 = JUMP newly pressed)
which still does not match ROM. The v6.2 aux at F2221 shows
`ctrl2_pressed=0x48` (button_A bit 0x40 set), so ROM cage's
`andi.w #button_A_mask|button_B_mask|button_C_mask, d5; beq.s
loc_33B1E` (sonic3k.asm:70055) computes `0x4848 & 0x0070 = 0x0040`
— non-zero, which by my reading should branch to release at
sonic3k.asm:70057. But ROM CSV at K=2221 still shows
`tails_y_speed=-02EA` (cage ride speed, NOT release).

I exhaustively checked: `Tails_CPU_routine=6` (NORMAL, runs
TailsCPU_Normal not a Ctrl_2_logical zero-er), `cage cooldown=1` should
direct execution to `loc_33ADE`, no FixBugs path differences, no
`Tails_CPU_idle_timer` block (=0 throughout), no Player_2 handling
divergence (`a0=Player_2`, `bne.s loc_13830` taken), no
`Flying_carrying_Sonic_flag`. The trace reading and disassembly
suggest ROM cage SHOULD release Tails at F2221 — yet ROM definitively
does not.

### What Was Tried This Iteration

1. **Probe added** to `tryJumpRelease` and `SpriteManager` to log
   `aiJump`, `jumpJustPressed`, `forcedJumpPress`, `objectControlled`,
   `cooldown` per frame for Tails at frames 0x08AC-0x08B0. Confirmed:
   - aiJump first becomes true at engine frame 0x08AF (iter K=2222),
     not K=2221.
   - jumpJustPressed=true exactly once, at iter K=2222.
   - cage's tryJumpRelease only fires at iter K=2222.
2. **Raw input recording attempt:** modified
   `SpriteManager.publishInputState` to bypass per-sprite
   `controlLocked` filter and pass raw-or-forced inputs to
   `setLogicalInputState`. Result: divergence regressed from F2222 to
   F2221 (engine fires release one frame earlier — the same edge,
   shifted by Sonic's controlLocked filter being ON at K=2204 but OFF
   at K=2205 in raw-input world). Reverted.
3. **Trace regenerated** from v6.1-s3k schema to v6.2-s3k to obtain
   `cpu_state_per_frame` (Tails_CPU_routine, ctrl2_held, ctrl2_pressed)
   and `interact_state_per_frame` aux events. v6.2 confirmed
   `ctrl2_pressed=0x48` at F2221 and ROM still does not release —
   fundamentally contradicting my reading of the cage code.

### What's Likely Needed

A real fix needs ROM-side instrumentation that I don't have without a
debugger or live disassembly run, e.g.:

1. **Verify cage execution at F2221:** confirm cage's `loc_33ADE` is
   actually reached (vs being skipped because of `Delete_Sprite_If_Not_In_Range`
   trimming or some d6/standing-flag clear after Sonic's K=2205 release
   path that I'm missing).
2. **Verify d5 register at the moment of `andi #$70`:** capture d5 in
   ROM directly (BizHawk Lua `event.onmemoryexecute(0x33ADE, ...)`)
   to confirm it really has the 0x40 bit set when the andi runs, vs.
   some intermediate state where Tails CPU writes Ctrl_2_logical with
   different masks.
3. **Audit ROM cage `1(a2)` cooldown lifecycle for Tails:** maybe the
   cooldown is decrementing into 0 between frames in some path I
   haven't traced, which would make `loc_339A0`'s `tst.b 1(a2)` fall
   through to the gspeed check (which would then re-set cooldown=1
   and bra loc_33ADE — but then the andi check happens with the same
   d5).

### Recorder Bug Found

The v6.2 recorder writes `interact_state.object_control` reading from
player offset `0x2A`. Per `sonic3k.constants.asm:30/57`, offset `0x2A`
is **status**, not `object_control` (which is at `0x2E`). The reported
"object_control" values in v6.2 traces are actually status bytes —
this needs to be fixed in `tools/bizhawk/s3k_trace_recorder.lua`
before the diagnostic is reliable for object_control gating.

### Removal Condition

Resolve once `TestS3kCnzTraceReplay`'s first strict error advances
past F2222, with a fix backed by ROM-confirmed (not assumed) cage
execution path for the iter K=2221/K=2222 boundary.

### Resolution (v6.3-s3k Recorder + ROM-Side Memoryexecute Hook Diagnostics)

The fix landed when the v6.3-s3k recorder added BizHawk Lua
`event.onmemoryexecute` hooks at every cage routine entry
(`sub_338C4=0x338C4`, `loc_339A0=0x339A0`, `loc_33ADE=0x33ADE`,
`loc_33B1E=0x33B1E`, `loc_33B62=0x33B62`) and emitted a `cage_execution`
aux event per frame with the captured M68K register state
(`a0/a1/a2/d5/d6`), the per-player state byte `1(a2)`, and the
player's status / `object_control` bytes. Inspection of those events
across F2136-F2222 revealed:

1. **The cage's `d6` register is corrupted by the original ROM
   `Obj_CNZWireCage` bug.** With `FixBugs` disabled (the original ROM),
   the second `bsr.s sub_338C4` call uses
   `addq.b #p2_standing_bit-p1_standing_bit,d6` (sonic3k.asm:69843)
   to derive the Player_2 standing-bit mask rather than reloading
   `d6` cleanly. This carries whatever value `d6` had after the
   Player_1 call's `Perform_Player_DPLC` corruption. While the
   leader is actively rotating in `loc_33A6A` (sonic3k.asm:70016) /
   `loc_33BAA` (sonic3k.asm:70121), `Perform_Player_DPLC` runs and
   corrupts `d6 = 0`; the `addq.b #1,d6` then makes
   `d6 = 1` for Tails so `bset d6,status(a0)` in `sub_33C34`
   (sonic3k.asm:70181) sets bit 1 of the cage's status rather than
   `p2_standing_bit = 4`.
2. **F2136 capture of Tails:** `cage_execution` shows the cage hits
   `sub_338C4_entry` for Tails with `d6=0x01` and the resulting
   `bset d6,status(a0)` writes bit 1 to the cage's status byte
   (`cage_status` transitions from `0x09 = bits 0+3` to
   `0x0B = bits 0+1+3` over two frames). Tails's `object_control`
   becomes `0x42` (bits 6+1, set by `loc_3397A` at sonic3k.asm:69937).
3. **F2200 last cage-process for Tails:** while the leader was still
   in `loc_33B1E_continue` mode (sonic3k.asm:70070), `Perform_Player_DPLC`
   could still corrupt `d6` to 1 on some frames and the cage would
   process Tails through `loc_339A0_mounted` → `loc_33ADE_cooldown` →
   `loc_33B1E_continue`. F2200 was the last such frame; after that,
   Sonic's cage state byte hit `loc_33B62_release` (sonic3k.asm:70092)
   at F2205 and the cage's per-frame entry for Sonic switched to
   the cooldown-decrement-only path that does not call
   `Perform_Player_DPLC`.
4. **F2206-F2222 "ghost" state:** `cage_execution` shows the cage
   only hits `sub_338C4_entry` for Tails with `d6=0x04` (the
   correct `p2_standing_bit` value, no `Perform_Player_DPLC`
   corruption). `btst d6,status(a0)` reads bit 4 of `cage_status`
   which is clear (the cage's Tails standing flag is at bit 1, set
   by the `d6=1` corruption-bit at original capture). `bne.w loc_339A0`
   is not taken; falls through to capture-attempt at `loc_338D8`
   (sonic3k.asm:69881). The capture-attempt's
   `tst.b object_control(a1); bne.w locret_3399E`
   (sonic3k.asm:69896) exits immediately because Tails's
   `object_control = 0x43` retains the bits 6+1+0 from the
   F2136 capture sequence. ROM cage does **nothing** for Tails on
   these frames.
5. **F2262 ROM exit from stuck state:** `Tails_CPU_flight_timer`
   reaches `5*60 = 300` (per sonic3k.asm:26829), `sub_13ECA` warps
   Tails to `(0x7F00, 0)` and resets the CPU routine.

The engine doesn't model `Perform_Player_DPLC`'s `d6` corruption
side-effect, so the engine cage's mounted-mode logic ran every frame
the cage was loaded and `state.latched=true`. When `Tails_CPU`'s
auto-jump fired at F2221, the engine cage's `tryJumpRelease` saw
`isJumpJustPressed()=true` and fired `releaseWithJumpImpulse` at
F2222.

**Fix landed:** `CnzWireCageObjectInstance` now tracks
`leaderHasReleased` (set when the leader's per-frame processing
transitions `state.latched` from true to false). When the leader has
released and the engine is processing the sidekick, `continueRide`
short-circuits with `setObjectControlled(true)` /
`setObjectControlAllowsCpu(true)` preserved (matching ROM's persistent
`object_control = 0x43` marker on Tails). The sidekick stays in
stuck-frozen state, awaiting the `Tails_CPU_flight_timer` despawn
warp that frees her.

`TestS3kCnzTraceReplay` first strict error advances F2222 → F2262.
The new error is the `Tails_CPU` despawn warp at F2262 — engine's
`SidekickCpuController.despawnCounter` does not match ROM's
`Tails_CPU_flight_timer` because the engine resets the counter
whenever the sidekick is on-screen, while ROM checks
`render_flags bit 7` which can be cleared even for an on-screen
sidekick. That is the next iteration's concern, separate from the
F2222 cage bug.

---

## AIZ Trace F6255 — Tails CPU Freed-Slot Despawn (RESOLVED)

**Status:** Resolved. AIZ first strict error advances past F6255 to F6313 (a downstream sidekick AI divergence). Two engine fixes landed in this round:

1. **Top-only solids bypass the off-screen gate** — `ObjectManager.processInlineObjectForPlayer` now skips the `solidObjectOffscreenGate` for `provider.isTopSolidOnly()` providers, matching ROM. The ROM gate at `loc_1DF88` (sonic3k.asm:41390-41392) lives only in `SolidObjectFull_1P` (sonic3k.asm:41016-41018); the top-only routines `SolidObjectTop_1P` / `SolidObjectTopSloped_1P` / `SolidObjectTopSloped2_1P` (sonic3k.asm:41793, 41887, 41840) all branch directly into `loc_1E42E` / `SolidObjCheckSloped` / `SolidObjCheckSloped2` (sonic3k.asm:41982, 42095, 42071) without testing `render_flags(a0)`. The S2 sloped path (`SlopedSolid_SingleCharacter` -> `SlopedSolid_cont` at s2.asm:34927-34952, 35066) bypasses the on-screen test the same way. Without this exemption, Tails never resolved a sloped-top contact for the AIZ2 collapsing platform at x=0x08B0 — the platform's bbox right edge (0x90C) sat 0xD5 px past the camera left edge (0x985) at the moment Tails should have landed, so the existing universal off-screen gate dropped the contact and `setLatchedSolidObject(slot=16)` never fired.

2. **Collapsing-platform `Sprite_OnScreen_Test` reads cam_X directly** — `Sonic3kCollapsingPlatformObjectInstance.spriteOnScreenTestPasses()` now reads `services().camera().getX()` at call time instead of consulting a previous-frame cache. The round-13 cache was based on the mistaken premise that ROM `Load_Sprites` runs AFTER `Process_Sprites`; the disassembly (sonic3k.asm:7893 `jsr Load_Sprites`; 7894 `jsr Process_Sprites`; 7897 `jsr DeformBgLayer`) shows the real order is Load_Sprites -> Process_Sprites -> DeformBgLayer, so `Camera_X_pos_coarse_back` at frame N's Process_Sprites reflects `Camera_X_pos` at the start of frame N (= end of N-1, since DeformBgLayer is the per-frame camera tracker). The engine's `LevelFrameStep` mirrors that order by-construction (objects step 4, camera-update step 5), so a direct read at the platform's `update()` already supplies the correct value. The round-13 cache pulled cam_X from too far in the past and let the platform's destruction lag ROM by one frame, which in turn delayed the freed-slot despawn at F6255 by one frame.

With both fixes, Tails now lands on the platform at F6251, the platform destroys at F6254 (ROM-matching), and the freed-slot detection warps Tails to (0x7F00, 0) at F6255. AIZ trace error count holds steady (1960 vs 1959 prior); the first strict error now sits at F6313 with a sidekick AI divergence further downstream.

**Original problem (PARTIAL state in round 13):**

Platform lifecycle was ROM-aligned via `isPersistent()=true` + lagged-camera check, but Tails never registered a STANDING contact for the AIZ2 collapsing platform at x=0x08B0. Despawn cascade was reduced from 6782 -> 1959 errors / 5773 -> 2034 warnings, but AIZ first strict error stayed at F6255 because `lastRidingInstance` was null at the despawn check time.

**Location:** `Sonic3kCollapsingPlatformObjectInstance.spriteOnScreenTestPasses()` (lifecycle now ROM-aligned via `isPersistent()=true` + lagged-camera check), `SidekickCpuController.checkDespawn()` (S3K freed-slot path, infrastructure ready), `ObjectManager.SolidContacts.processInline*` (Tails-vs-platform contact resolution — root cause area), AIZ2 terrain-vs-object collision interaction.

**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`,
first strict error at frame 6255 (`tails_y mismatch expected=0x0000 actual=0x033B`).

### Symptom

Sequence around the divergence (CSV / aux from v6.3-s3k recorder):

| K     | gfc    | Tails state (ROM)                                  | ROM slot 16 (CollapsingPlatform x=0x08B0) |
|-------|--------|----------------------------------------------------|-------------------------------------------|
| 6250  | 0x1742 | sk_x=0x0870 sk_y=0x033F sk_air=0 sk_status=0x00     | status=0x80 (alive, no one standing)      |
| 6251  | 0x1743 | sk_x=0x0875 sk_y=0x033A sk_air=0 sk_status=0x08 (OnObj) | status=0x90 (Tails standing, $3A set)  |
| 6252  | 0x1744 | sk_x=0x087A sk_y=0x033B                              | status=0x90, $38 countdown decrementing   |
| 6253  | 0x1745 | sk_x=0x0880 sk_y=0x033B sk_status=0x08              | status=0x90, alive (cam_back=0x0880, diff=0) |
| 6254  | 0x1746 | sk_x=0x0885 sk_y=0x033B (still alive, still on slot) | object_removed (cam_back=0x0900, Sprite_OnScreen_Test deletes) |
| 6255  | 0x1747 | sk_x=0x7F00 sk_y=0x0000 sk_status=0x02 (in_air)     | (slot freed; SST zeroed)                  |

In ROM, Tails transitions from terrain to platform at F6251 — note the `sk_y` step from 0x033F down to 0x033A (which on the y-axis-down convention means UP 5 px) and the status flip from 0x00 → 0x08 (`OnObj` set). She rides the platform through F6254. At F6255 the slot was freed during F6254 by `Sprite_OnScreen_Test` (sonic3k.asm:37262); `sub_13EFC` (sonic3k.asm:26823) reads `(a3)=0`, mismatches cached `Tails_CPU_interact=0x0002`, falls into `sub_13ECA` (sonic3k.asm:26800) which warps Tails to `(0x7F00, 0)`.

In the engine, Tails has matching `(sk_x, sk_y)` up to F6254 but **never has `onSolidContact(standing=true)` fire for platform 0x08B0** — only platforms at x=0x05B0 and x=0x0E70 get Tails contacts in the entire trace. Without the standing contact, `setLatchedSolidObject` (ObjectManager.java:4431) never runs for slot 16, so `latchedSolidObjectInstance` stays unset and the freed-slot detection has no `lastRidingInstance` to compare against.

### Diagnosed Cause

Three layers — first two now resolved, third remains:

1. **Engine SidekickCpuController had no `(a3)=0` analog.** [Resolved in commit 2b8cd723f.] ROM's `cmp.w (a3),d0` mismatch fires on slot deletion (the SST is zeroed by `Delete_Referenced_Sprite` sonic3k.asm:36116). The engine added `latchedSolidObjectInstance` (`AbstractPlayableSprite`), `setLatchedSolidObject(int, ObjectInstance)` (ObjectManager wires this on `processInline*` standing/touchTop), `SidekickCpuController.lastRidingInstance` per-frame cache, and the `sub_13EFC` `(a3)=0` analog gated by `ObjectInteractionRules.sidekickDespawnUsesRidingInstanceLoss`.

2. **Engine collapsing-platform lifecycle was off by one frame.** [Resolved in this commit.] `ObjectManager.unloadCounterBasedOutOfRange()` (ObjectManager.java:1842) reproduces ROM's `Sprite_OnScreen_Test` formula but feeds the **current** frame's `cameraX`. ROM's S3K `Sprite_OnScreen_Test` (sonic3k.asm:37262) uses `Camera_X_pos_coarse_back`, which `Load_Sprites` (sonic3k.asm:37545 `loc_1B7F2`) updates **after** `Process_Sprites` each frame — so the value seen during a given frame's object pass reflects the camera's X at the **end of the previous frame**. The engine's eager check destroyed the platform at frame K (cam_x=0x0985), one frame before ROM's frame K+1 deletion (using `Camera_X_pos_coarse_back` = end-of-K value = 0x0900, distance=0xFF80 > 0x280 → delete). Fix: `Sonic3kCollapsingPlatformObjectInstance.isPersistent()` returns `true` to bypass the eager engine OOR, plus `spriteOnScreenTestPasses()` runs the lagged-camera variant inside `update()` using `previousFrameCameraX` cached from the prior tick. With this, the platform now reaches `setDestroyed(true)` at gfc=0x1746 / F6254 — matching ROM exactly.

3. **Tails never lands on platform x=0x08B0 in the engine.** [Architectural blocker, current state.] ROM Tails transitions from terrain to platform at F6251 (sk_y 0x033F → 0x033A, status 0x00 → 0x08). Engine Tails has matching X/Y but never gets a `standing()` contact recorded for platform 0x08B0. Hypothesis: AIZ2 layout has terrain underneath the platform at a Y close to the platform's slope-data top surface, and the engine's solid-contact framework doesn't perform the terrain → object handover that ROM's `SolidObjectTopSloped2` (sonic3k.asm:41826) manages. Without a `standing()` contact, `setLatchedSolidObject` never runs, `latchedSolidObjectInstance` stays null, and the freed-slot detection in `SidekickCpuController.checkDespawn()` lines 1127-1137 has no `lastRidingInstance` to detect the loss of.

### Fix (Partial)

Landed in this commit:

- `Sonic3kCollapsingPlatformObjectInstance.isPersistent()` returns `true` so `ObjectManager.unloadCounterBasedOutOfRange()` (eager, current-frame) does not destroy the platform. Lifecycle now governed exclusively by the in-instance `spriteOnScreenTestPasses()`.
- `Sonic3kCollapsingPlatformObjectInstance.spriteOnScreenTestPasses()` ports ROM `Sprite_OnScreen_Test` (sonic3k.asm:37262) using a per-instance `previousFrameCameraX` cache — at end of `update()` we save the camera_x observed during this tick; next tick's `spriteOnScreenTestPasses()` reads it as the analog of ROM's `Camera_X_pos_coarse_back`. Distance threshold $280, unsigned 16-bit wrap, exactly matching ROM.
- `update()` runs `spriteOnScreenTestPasses()` in states 0/1/2, mirroring ROM's `loc_20594` → `sub_205B6` → `Sprite_OnScreen_Test` chain (sonic3k.asm:44814, 44830, 37262) which fires every frame in pre-collapse and solid-stay states. State 3 (post-fragment fall) keeps its existing `isOnScreen(128)` check matching ROM `loc_20620` `tst.b render_flags / bpl Delete_Current_Sprite` (sonic3k.asm:44879).

Effect: AIZ trace replay error count 6782 → 1959 (-71%), warning count 5773 → 2034 (-65%). All downstream cascades from premature platform destruction are gone. Cross-game baselines unchanged: S1 GHZ green, S1 MZ1 F311, S2 EHZ F1151, S3K CNZ F2262.

Not landed (architectural blocker):

- **Tails-vs-collapsing-platform standing contact in the engine.** Engine `ObjectManager.SolidContacts.processInline*` paths never fire `onSolidContact(standing=true)` for slot 16 / platform x=0x08B0 with Tails, even though Tails' position matches ROM frame-for-frame up to F6254. Investigation needed into:
  - Whether AIZ2 terrain immediately under the platform footprint masks the platform's top surface (terrain Y ≈ platform slope-data Y).
  - Whether the engine performs the equivalent of ROM `SolidObjectTopSloped2`'s "step UP onto the object" handover (sonic3k.asm:41826) when the player runs from terrain onto a sloped solid object whose top is slightly above terrain.
  - Whether the per-frame ordering (Tails physics resolves terrain collision **before** the object solid pass) causes Tails' Y to be locked to terrain before her body intersects the platform.

### Removal Condition

Resolve once `TestS3kAizTraceReplay`'s first strict error advances past F6255, with engine Tails landing on the AIZ2 collapsing platform at x=0x08B0 (visible as `setLatchedSolidObject(slot=16)` firing for Tails around F6251) and the freed-slot detection then warping her to `(0x7F00, 0)` at F6255 like ROM.

---

## CNZ1 Trace F3649 — Tails Air-to-Ground Spring Boost Missed (RESOLVED)

**Location:** `Sonic3kSpringObjectInstance.onSolidContact`, `Sonic3kSpringObjectInstance.checkHorizontalApproach`, `ObjectManager.SolidContacts.processInlineObjectForPlayer`
**ROM Reference:** `sub_23190` (sonic3k.asm:47890), `sub_2326C` (sonic3k.asm:47957), `Obj_Spring_Horizontal` (sonic3k.asm:47771), `word_22EF0` (sonic3k.asm:47651) — yellow horizontal spring strength `-$A00`.

### Symptom

`TestS3kCnzTraceReplay` first strict error at F3649: `tails_x_speed mismatch (expected=-0A00, actual=-0060)`. ROM Tails lands on a horizontal spring at slot 16 (position `0x1D37, 0x08B0`) at F3649 — the same frame she transitions from in-air to grounded — and the spring fires, setting `x_vel = -$A00, ground_vel = -$A00`. The engine does not fire the spring on Tails, leaving her with the air-physics-derived `x_speed = -$60`. Engine catches up at F3650 once ground physics propagates, producing a 1-frame phase shift in all downstream Tails state.

### Diagnostic Data

Captured by extending `s3k_trace_recorder.lua` (v6.4-s3k) with `event.onmemorywrite` hooks on Tails's `x_vel`/`y_vel` RAM addresses. Each hit records the M68K PC of the writing instruction. Hooks are window-restricted (default frames 3640–3660; override via `OGGF_S3K_VELOCITY_WRITE_RANGE`).

ROM frame-by-frame Tails `x_vel` writes:

| Frame | Writers (PC : value) | Notes |
|------:|----------------------|-------|
| F3645–F3648 | `0x14ECC : 0xFFD0..0xFFB8` | `Tails_InputAcceleration_Freespace` accel-while-airborne |
| **F3649** | `0x14ECC : 0xFFB8`, **`0x2319C : ...`** | First the air physics, then **the spring fires inside `sub_23190`** |
| F3650+ | `0x14B70 : 0xF600` | Ground physics: `x_vel = ground_vel * cos(angle)`. ground_vel was set to `-$A00` by `loc_231BE` during the spring fire. |

### Root Cause

Two related issues, both ROM-cited:

1. **Engine spring proactive zone (`sub_2326C` analog) only checks Player_1, not sidekicks.** `Sonic3kSpringObjectInstance.checkHorizontalApproach(player)` is called from `update(int frameCounter, PlayableEntity playerEntity)` with the leader only. ROM `sub_2326C` (sonic3k.asm:47957) explicitly checks **both** Player_1 (line 47973) and Player_2 (line 47999) every frame.

2. **Engine spring `onSolidContact` requires `touchSide()` for horizontal springs, but ROM fires on the standing flag.** `Obj_Spring_Horizontal` (sonic3k.asm:47780-47782) tests bit 0 of `swap`'d `d6` after `SolidObjectFull2_1P` — that is the **standing** flag (`p1_standing`/`p2_standing`), not a side flag. The engine's path
   ```java
   if (springType == TYPE_HORIZONTAL) {
       if (!contact.touchSide() || !isPlayerOnHorizontalSpringActiveSide(player)) return;
       applyHorizontalSpring(player);
   }
   ```
   gates on `touchSide()`. When Tails transitions air→ground inside the spring's hitbox in a single frame, the contact is reported as standing/touchTop, not side, so the spring does not fire.

The yellow horizontal spring strength `-$A00` matches the trace `tails_x_speed = -$0A00` exactly when the spring's `$30(a0)` was set by `Spring_Common` `word_22EF0[subtype & 2]` (subtype bit 1 selects yellow). ROM-side per-frame spring `subtype` instrumentation reads `0x00` from the OST byte at offset `0x2C`, but ROM CSV velocity matches yellow strength — likely a recorder-read quirk (see "Diagnostic data" above; the disassembly `subtype` at offset `0x2C` may be loaded into a different field at runtime, or the recorder reads it at a frame where it has been re-written; not load-bearing for the fix).

### Diagnosis Tooling Landed In This Branch

- `tools/bizhawk/s3k_trace_recorder.lua` — `v6.4-s3k`. Adds `event.onmemorywrite` hooks at Tails's `x_vel` (`0xFFB062`/`0xFFB063`) and `y_vel` (`0xFFB064`/`0xFFB065`), accumulates per-frame, flushes once per `on_frame_end` as `velocity_write` aux event listing each writer's PC and post-write value. Frame-window-gated (default `3640..3660`; set `OGGF_S3K_VELOCITY_WRITE_RANGE=START-END` to widen) so the trace size stays manageable.
- `aux_schema_extras` adds `velocity_write_per_frame`. Backward-compatible: existing traces without the key still load.
- `TraceEvent.VelocityWrite` record + parser case in `TraceEvent.parseJsonLine`.
- `TraceMetadata.hasPerFrameVelocityWrite()` boolean accessor.
- `TraceData.velocityWriteForFrame(frame, character)` lookup.

The CNZ test resources `physics.csv`/`aux_state.jsonl` in `src/test/resources/traces/s3k/cnz/` were **not** regenerated against this schema — the existing trace remains the reference for replay. The new diagnostic schema is for ad-hoc bug-hunt regenerations only.

### Fix Landed

`Sonic3kSpringObjectInstance.update()` now mirrors ROM `sub_2326C` (sonic3k.asm:47957)
over the leader **and** every active sidekick.  The previous code passed only
`playerEntity` (always the leader) to `checkHorizontalApproach`, so a CPU-controlled
Tails landing on a horizontal spring while sitting just outside the side-push
collision box never received the proactive-zone fire that the ROM gives Player_2
at sonic3k.asm:47999-48020.  After the fix the spring fires on Tails at F3649,
giving her `x_vel=-$0A00` like ROM, and the +8 px X bump from `sub_23190`
(sonic3k.asm:47893) puts her at `0x1D29` matching ROM trace.

The diagnosis's secondary suggestion ("switch horizontal-spring `onSolidContact`
to fire on the standing flag, not `touchSide`") was based on a misreading of the
d6 swap test at sonic3k.asm:47780-47782.  Re-derived: `p1_standing_bit = 3`
(sonic3k.constants.asm:133), so when `SolidObjectFull2_1P` calls
`addi.b #$D,d4 / bset d4,d6` on the side-push branch
(sonic3k.asm:41497-41498 / 41506-41507) it sets bit `3 + 0xD = 0x10`
of d6 (= bit 16).  After `swap d6 / andi.w #1,d6` the spring is testing
bit 16, which is the **side-push flag**, not the standing flag.  The engine's
existing `contact.touchSide()` gate is therefore the correct ROM equivalent;
the proactive zone is the only piece that was missing.

### Removal Condition

Removed.  `TestS3kCnzTraceReplay`'s first strict error advanced from F3649
to F3845 (a 196-frame advance into a different, sidekick-physics divergence
already on the open list).  Cross-game baselines unchanged: S1 GHZ green,
S1 MZ1 F311, S2 EHZ F1151, S3K AIZ F6313.

---

## AIZ Trace F6920 -- Sloped Collapsing Platform Ride Sample Ordering -- RESOLVED

**Resolution:** Fixed by the round-15 introduction of
`SolidObjectProvider.suppressSlopeSampleThisFrame()` and the matching
`Sonic3kCollapsingPlatformObjectInstance` `pendingTransitionSkip` /
`transitionFrameSlopeSkip` flags. ROM `loc_20594`'s state-1 -> state-2
transition (sonic3k.asm:44820) jumps to `ObjPlatformCollapse_CreateFragments`
(sonic3k.asm:45394), which `jmp`s to `Play_SFX` without falling through to
`sub_205B6` (sonic3k.asm:44830) -- so the slope sample is skipped exactly on
the transition frame. The engine's `update()` runs `performCollapse()` one
frame earlier than ROM's `$3A` arming (sonic3k.asm:44825 vs the engine's
`onSolidContact` setting `state=1` on the first standing frame), so the flag
is staged in `pendingTransitionSkip` during the engine's transition update
and promoted to `transitionFrameSlopeSkip` in the next update. The post-update
inline solid pass observes the active flag for exactly one frame, keeps the
player attached, and skips the y_pos write -- matching ROM's transition
frame. F5904 (`y=0x0317`) is preserved because that platform is already in
state 2 throughout. AIZ trace first-error advances from F6920 to F7127.

**Original symptom (kept for context):**

**Location:** `Sonic3kCollapsingPlatformObjectInstance`,
`ObjectManager.SolidContacts.processInlineRidingObject`.
**ROM Reference:** `SolidObjSloped2` (sonic3k.asm:41727),
`SolidObjectTopSloped2_1P` (sonic3k.asm:41840),
`Obj_CollapsingPlatform` `sub_205B6` / `loc_205DE`
(sonic3k.asm:44835, 44841).
**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`,
first strict error at frame 6920.

### Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` currently first fails at
F6920:

```text
y mismatch (expected=0x0342, actual=0x0341)
```

The trace has Sonic riding AIZ collapsing platform object 0x04 at
`x=0x0E70, y=0x0368`, ROM routine `loc_205DE`. X velocity, ground
velocity, angle, status bits, and object latch all still match at the
first failing frame, so this is a one-pixel vertical ride-surface ordering
problem rather than a broad physics divergence.

### Diagnosed Constraints

The ROM's continued-riding path samples sloped solids in
`SolidObjSloped2`: it computes `x_pos(player) - x_pos(object) + halfWidth`,
shifts right by one, applies horizontal flip after the shift, reads the raw
slope byte, writes `player.y_pos = object.y_pos - slope - y_radius`, then
applies object X carry (`sub.w d2,x_pos(a1)`) (sonic3k.asm:41727-41744).
S2's `MvSonicOnSlope` mirrors this order (s2.asm:35429), and S1's
`SlopeObject2` does the same for its riding path
(`docs/s1disasm/_incObj/53 Collapsing Floors.asm:221`).

That cross-game order rules out a simple "sample previous X" patch in the
shared rider path. A prior local experiment that sampled the previous X for
S3K sloped riding fixed the F6920 shape but regressed AIZ F5904 from
`y=0x0317` to `y=0x0316`, which means the signal is not "always previous X".
Any real fix needs to preserve the shared S1/S2/S3K slope sample order above,
or gate a proven S3K-specific divergence through the narrowest typed
`GameRules` rule or object-local owner.

The object-level ordering signal is real but not yet isolated enough to land:
S3K `Obj_CollapsingPlatform` state `loc_205DE` calls `sub_205B6` before
decrementing its stay timer and before releasing the rider
(sonic3k.asm:44841), and `sub_205B6` itself calls
`SolidObjectTopSloped2` before `Sprite_OnScreen_Test`
(sonic3k.asm:44835). The engine currently keeps this object in
`AUTO_AFTER_UPDATE` mode and compensates state-2 release timing with an extra
stored tick. Moving it to the existing manual-checkpoint pattern used by S2
collapsing platforms is the likely architecture direction, but that rewrite
must re-home the standing-trigger, solid-stay, release, and F6255 off-screen
delete sequencing together; changing only the slope sample or only the
release tick is a trace-shaped hack.

### F6920 Slope-Sample Arithmetic (round-14 dispatch findings)

A round-14 inspection added concrete arithmetic that further constrains the
fix. At F6920 the trace columns settle to:

```text
F6919  player_x = 0x0E8E   y(expected) = 0x0342   y(engine) = 0x0342  match
F6920  player_x = 0x0E8B   y(expected) = 0x0342   y(engine) = 0x0341  drop-by-one
F6922  player_x = 0x0E85   y(expected) = 0x0341   y(engine) = 0x0341  match
```

(Frames F6917 and F6920 show the ROM repeating the previous frame's `y_pos`
for one extra frame; the engine instead drops by one pixel.)

`SolidObjSloped2` (sonic3k.asm:41727-41753) executes:

```
relX     = player.x_pos - platform.x_pos + width_pixels
sampleX  = relX >> 1
slopeY   = (signed) AIZ_SLOPE_DATA[sampleX]
player.y = platform.y - slopeY - player.y_radius
```

With `platform.x = 0x0E70`, `width_pixels = 0x3C`, `y_radius = 0x13`,
`platform.y = 0x0368`, `AIZ_SLOPE_DATA[0x2B] = 0x14`,
`AIZ_SLOPE_DATA[0x2D] = 0x13`:

```text
post-physics player_x = 0x0E8B  -> relX 0x57 -> sampleX 0x2B
                     y = 0x0368 - 0x14 - 0x13 = 0x0341  (engine result)

pre-physics  player_x = 0x0E8E  -> relX 0x5A -> sampleX 0x2D
                     y = 0x0368 - 0x13 - 0x13 = 0x0342  (ROM result)
```

So the ROM-correct slope sample at F6920 needs the **previous frame's**
`x_pos`, but the prior round-13 experiment that always sampled the previous
frame regressed F5904 (`y` 0x0317 -> 0x0316). The clean signal must therefore
account for whether `Sonic_Move` would have changed `x_pos` enough to cross a
2-pixel slope-data bucket boundary this frame, not be a blanket "previous
frame" patch.

Sweeping all of F6913-F6924 with the post-physics formula reproduces every
frame except F6913 (engine 0x0346 vs trace 0x0347 -- one pixel **too high**)
and F6920 (engine 0x0341 vs trace 0x0342 -- one pixel **too high**). Both
discrepancies share the property that the ROM "delays" the slope drop by one
frame relative to the engine. The engine matches every interior frame on
which sampling at the post-physics x lands inside the same slope-data bucket
as sampling at the pre-physics x.

### Hypotheses Ruled Out vs Still Open (round 14)

Ruled out, with cites:

- **Pre/post-physics Sonic ordering.** Process_Sprites (sonic3k.asm:35965)
  iterates Object_RAM in slot order; slot 0 (Player_1) runs first, slot 25
  (collapsing platform) runs after, so the platform's `sub_205B6` reads
  `x_pos(a1)` post-Sonic_Move + post-MoveSprite2 (sonic3k.asm:21620-21626).
  Always sampling pre-physics x is therefore wrong as a steady-state model
  even though it happens to fix F6920 and F6913.
- **Auto vs manual checkpoint timing of release.** F6920 is mid-countdown
  (state 2 / `loc_205DE`, $38 still > 0); the 0x30->0x00 release at the END
  of state 2 is what differs between AUTO_AFTER_UPDATE and MANUAL_CHECKPOINT.
  Reordering update vs solid checkpoint cannot move the slope sample
  evaluated **inside** that solid checkpoint, so a pure mode swap on the
  collapsing platform does not by itself address F6920.
- **`SolidObjSloped2` X-carry double application.** `sub.w x_pos(a0),d2 ;
  sub.w d2,x_pos(a1)` (sonic3k.asm:41748-41749) collapses to a no-op on this
  platform because `sub_205B6` loads `d4 = x_pos(a0)` (44834) before
  `SolidObjectTopSloped2_1P` saves `move.w d4,d2` (41863). So the carry does
  not adjust the player's x_pos and therefore cannot retroactively change
  the slope sample.

Open, needing investigation:

- **Standing-bit edge transitions.** `SolidObjectTopSloped2_1P`
  (sonic3k.asm:41840) has two paths: standing branch (re-sample slope) vs
  `SolidObjCheckSloped2` new-contact branch. Whether the platform's
  `p1_standing_bit` was set vs cleared this frame -- and whether
  `SolidObjCheckSloped2` writes y differently than `SolidObjSloped2` (e.g.
  with a different effective y_radius via the `loc_1C100` rolling fixup) --
  has not been ruled out. Specifically: `SolidObjCheckSloped2` is at
  `sonic3k.asm:42095` and is the new-landing path; if the bit got cleared
  for one frame (e.g. by a sibling fragment processing) the platform would
  re-enter via the new-contact path which uses a different snap formula.
  Confirm by inspecting platform-side `status` byte during F6919 and F6920
  in a BizHawk trace.
- **Pre-physics x_pos held by `MvSonicOnPtfm2` slope re-application.** S3K's
  `loc_205DE` only calls `sub_205B6` (which calls `SolidObjectTopSloped2`).
  But on Sonic's own slot path (Player_1 routine), `Sonic_Move` may
  short-circuit MoveSprite2 when Status_OnObj is set in some sub-paths.
  Verify by tracing whether `loc_10844` (sonic3k.asm:21620) or one of its
  alternates dispatches when `Status_OnObj` is set this specific frame.
- **CollidingObject_PtfmRiding interlock.** S3K may have a `Player_AnglePos`
  / `Player_SlopeRepel` (sonic3k.asm:21629-21630) tail that special-cases
  `Status_OnObj` and writes y_pos back to a snapshot, which would mean the
  platform's later `SolidObjSloped2` y-write gets clobbered by Sonic's own
  routine on the NEXT frame. If true, the trace's "expected" y for frame F
  is actually the y written by Sonic's routine at frame F+1 rather than the
  platform's y-write at frame F.

### What a Real Fix Likely Needs

Once the open hypotheses are settled, the fix has to:

1. Identify the ROM divergence that produces a one-pixel "y-hold" on
   F6917/F6920 with cite (likely a standing-bit transition or a ROM-side
   y_pos snap inside Sonic's own routine that the engine isn't reproducing).
2. Gate the divergence behind the narrowest typed `GameRules` rule or
   object-local owner so S1/S2 are unaffected.
3. Preserve F5904 (`y = 0x0317`), pass F6920 (`y = 0x0342`), and not
   regress CNZ F6304 / S1 GHZ / S1 MZ / S2 EHZ baselines.

A blanket conversion of `Sonic3kCollapsingPlatformObjectInstance` from
AUTO_AFTER_UPDATE to MANUAL_CHECKPOINT is the right architectural cleanup
for the release-frame ordering (sub_205B6 before $38 decrement before
sub_205FC, sonic3k.asm:44850-44857), but it should be done as a separate,
independent commit since it does not by itself move the F6920 slope sample.

### Removal Condition

Resolved 2026-04-30 (round-15 dispatch): F5904 and F6920 both pass, S1
GHZ/MZ/S2 EHZ trace baselines unchanged, CNZ first-error stays at F6304.
Entry retained for context until a follow-up cleanup confirms no further
regressions.

---

## CNZ1 Trace F6304 — Tails Misses CNZ Door Re-Land While Following Fast Leader (RESOLVED)

**Status:** Resolved on `bugfix/ai-cnz-f6304-airborne-latch` (2026-04-30).
The first strict CNZ1 trace error advances from F6304 to F7614 (a
1,310-frame jump). Cause was the engine's solid-contact on-screen gate
(`AbstractObjectInstance.isWithinSolidContactBounds`) using a hardcoded
16-px margin and the current frame's camera position. ROM
`Render_Sprites` (sonic3k.asm:36336-36370) computes the on-screen flag
from each object's `width_pixels` (CNZ horizontal door byte_30FCE at
sonic3k.asm:66167 = `$20, $08`), and that flag is written at the END of
frame N — `SolidObjectFull` on frame N+1 reads the prior frame's value.
The fix exposes `getOnScreenHalfWidth()` on `AbstractObjectInstance`
(default 16, overridden to ROM `width_pixels` for the door) and adds a
`previousFrameCameraBounds` snapshot rolled forward in
`updateCameraBounds` so the gate matches ROM's one-frame-old observation
order. Cross-game safe: the gate is still feature-flagged on
`CollisionRules.solidObjectOffscreenGate` (S3K only); the new accessor
just sharpens the per-object margin and the snapshot timing. Verified
green: S1 GHZ, S1 MZ, S2 EHZ, S3K AIZ first-error stable at F6920.

**Location:** `DoorObjectInstance` (sub=$80 horizontal-door variant),
`ObjectManager.SolidContacts` (Tails-side `SolidObjectFull` resolution),
`SidekickCpuController.updateNormalFollow` (`leader_fast` branch).
**ROM Reference:**
- `Obj_HCZCNZDEZDoor` `loc_31034` horizontal-door routine,
  `sub_310DA` proximity check (sonic3k.asm:66198-66280).
- `SolidObjectFull` (sonic3k.asm:42102+) — ROM solid-object full handler
  used at sonic3k.asm:66258 with `d1 = width_pixels + $B`,
  `d2 = height_pixels = 8`, `d3 = height_pixels + 1 = 9`.
- `Tails_CPU_Control` routine 6 = `loc_13D4A` follow-leader, fast-leader
  branch `loc_13DD0` / `loc_13E9C` (sonic3k.asm:26656-26786).
**Trace reference:** `src/test/resources/traces/s3k/cnz` (`cnz1_fullrun`),
first strict error at frame 6304.

### Symptom

`TestS3kCnzTraceReplay#replayMatchesTrace` first fails at F6304:

```text
tails_y mismatch (expected=0x0530, actual=0x0533)
```

Surrounding state at the failure (per `s3k_cnz1_context.txt` line 25):

- F6298–F6303: ROM and engine agree. Tails has `Status_InAir=1` after
  running off a previous door/platform; `tails_y` slowly drifts from
  0x0530 → 0x0532 in both due to a small `tails_y_speed` (≤ `0x0118`).
- F6304: ROM snaps `tails_y` back to `0x0530`, clears `Status_InAir`,
  zeroes `tails_y_speed`, and writes `Status_OnObj` with the CNZ door
  (`obj=00031034`, `sub=$80`, `@1940,0548`). Engine continues falling
  to `tails_y=0x0533` with `tails_y_speed=0x0150`, never crossing into
  the door's solid-top.
- The downstream `tails_g_speed` divergence (-0x0492 expected vs
  -0x0402 actual) and the cascading 3 145-error tail are direct
  consequences of Tails staying airborne for the rest of the run.

The `tails_x` / `sonic_x` columns remain in lock-step throughout the
window; the divergence is exclusively in the Tails-side vertical
landing path on the horizontal CNZ door.

### Diagnosed Constraints

The ROM-side trace diagnostics confirm the door is the catching object.
At F6304, `tailsInteract slot=13 ptr=B3C2 obj=00031034 rtn=00 st=12
@1940,0548 sub=80 tails rf=85 obj=00 onObj=true objP2=true active=true
destroyed=false`. ROM's `loc_31034` calls `SolidObjectFull` with d1=$2B
(half-width 0x20 + 0xB), d2=8 (top), d3=9 (bottom)
(sonic3k.asm:66250-66258). With Tails y_radius = 0x10 the ROM landing
target is `door.y - d2 - tails_y_radius = 0x0548 - 8 - 0x10 = 0x0530`,
matching the observed `tails_y=0x0530` after landing.

The engine has the door spawned and active in `ObjectManager` (placement
window `LOAD_AHEAD = 0x280` / camera_x = 0x17EE leaves the door at 0x1940
well inside the active window). `DoorObjectInstance` advertises
`SolidObjectParams(halfWidth + 0xB, halfHeight, halfHeight + 1)` =
(0x2B, 8, 9), matching the ROM `d1`/`d2`/`d3` triplet exactly. The trace's
`eng-near` filter is keyed off the **main player's** (Sonic's) centre
position with a 160 px box, so the door at 0x1940 first appears in the
nearby trace dump at F6308 (Sonic crosses dx ≤ 160) — that is a
diagnostic-formatter artefact, not a spawn-window symptom; the door is
solid-active for Tails the whole time.

The bug is therefore in the Tails-side application of `SolidObjectFull`
when Tails is in `Tails_CPU_routine = 6` (`loc_13D4A` follow-leader)
**with the leader running fast**:

- ROM order (sonic3k.asm:26656-26786): `loc_13D4A` → `loc_13D78` calls
  `sub_13EFC` to update `Tails_CPU_flight_timer` /
  `Tails_CPU_interact`, then performs steering Ctrl_2 writes, and the
  player/object update loop runs `Obj_HCZCNZDEZDoor::loc_31034 →
  SolidObjectFull` on Tails as part of the standard object pass. With
  Tails arriving with `tails_y_speed > 0` and `tails_y < door_top`,
  `SolidObjectFull` snaps `y_pos`, zeroes `y_vel`, sets `Status_OnObj`,
  and clears `Status_InAir` in the same frame.
- Engine: Tails stays in `state=NORMAL branch=leader_fast`
  (`SidekickCpuController.updateNormalFollow`), and the CNZ door's
  solid-resolution against Tails does not re-snap him in the
  approach-from-above shape. The engine's `eng-tails-cyl` formatter is
  cylinder-only, but the underlying SolidContacts pass should also
  process `DoorObjectInstance` for Tails the same way it does for Sonic.
  At F6304 the engine has `tails_y=0x0533`, `tails_y_speed=0x0150`,
  status remains `0x03` (Facing|InAir) end-of-frame.

The combination of the `leader_fast` follow branch (which can rewrite
Tails' `x_pos`/`Ctrl_2` mid-step) and the door's `loc_31034` solid pass
order is where the engine and ROM diverge. A prior CNZ1 split landed
horizontal-spring airborne-contact preservation
(commit 3d03f361a "Preserve S3K horizontal spring airborne contacts"),
which is structurally similar but specific to springs. The door variant
needs the same shape: keep Tails' airborne SolidObjectFull contact for
horizontal CNZ doors so the ROM-equivalent landing latches.

The trace recorder already emits sufficient diagnostic data
(`cpu_state_per_frame`, `interact_state_per_frame`,
`sidekick_interact_object_per_frame`) to drive this fix; no recorder
extension is required. The investigation needs to (a) confirm via
disassembly walk that ROM does call `SolidObjectFull` on Tails in
`Tails_CPU_routine = 6 leader_fast` for the same frame the door's
trigger advances, and (b) port the airborne-from-above latch shape
proven for horizontal springs into the CNZ door's Tails-side path,
gated by the narrowest typed `GameRules` rule or object-local owner if it
does not also apply to S2's ARZ doors / S1's MZ doors. Cross-game parity is required: S2 EHZ trace
F1151 must stay green, S1 GHZ/MZ must stay green / F311 respectively,
and S3K AIZ trace must not regress past its current F6920 sloped-ride
blocker.

### Removal Condition

Removed on resolution: `TestS3kCnzTraceReplay#replayMatchesTrace` first
strict error has advanced to F7614 (see the next entry for details).

---

## CNZ1 Trace F7614 — Tails Jump Frame 2-Pixel y_pos Drift (RESOLVED)

**Resolution (2026-04-30):** Added
`CollisionRules.solidObjectTopBranchAlwaysLiftsOnUpwardVelocity` (true
on S3K, false on S1/S2) so `ObjectManager.SolidContacts.resolveContactInternal`
applies the position lift on upward-velocity contacts instead of returning
null.  The lift mirrors ROM `loc_1E154` (sonic3k.asm:41606-41632) which
writes `subq.w #1, y_pos(a1)` and `sub.w d3, y_pos(a1)` BEFORE testing
`tst.w y_vel(a1) / bmi.s loc_1E198` — when y_vel < 0 the standing/
RideObject_SetRide effect is skipped but the lift has already happened.
With `distY=1` for the Spring_Horizontal-vs-Tails geometry the lift
produces +2 px of y_pos shift; combined with `Tails_Jump`'s rolling
radius adjustment (+1 px) the total +3 px matches the ROM trace exactly
(0x04B0 -> 0x04B3).

To preserve ROM's "lift only on fresh contact" semantics (ROM
`SolidObjectFull2_1P` at sonic3k.asm:41066-41067 only enters
`SolidObject_cont` when the object's `a0.d6` standing-bit is CLEAR), the
engine tracks a per-(player, object) standing-bit on
`SolidContacts.objectStandingBitSet` (set by `RideObject_SetRide`-
equivalent STANDING contacts; cleared at the top of
`processInlineObjectForPlayer` when the object's own pass sees the bit
set with the player airborne, mirroring `loc_1DCF0`).  The pre-clear
value is snapshotted into `objectStandingBitSnapshot` so the lift gate
in `resolveContactInternal` reads ROM's "bit was set at routine entry"
state.  Without that tracking, the AIZ yellow up-spring kick aftermath
at F2090->F2091 (Sonic landing on slot 13 spring at `(0x1980, 0x0439)`
with `d3=1`) would re-lift Sonic +2 px instead of routing through ROM's
`loc_1DCF0` air-unseat path.

**Test impact:** CNZ first strict error advances from F7614 to F7872;
total errors drop from 3200 to 2774.  AIZ first strict error stays at
F7171.  S1 GHZ, S1 MZ1, S2 EHZ trace baselines unchanged.

**Regression test:** `TestSolidObjectTopBranchUpwardLift` exercises the
flag values across SONIC_1, SONIC_2, SONIC_3K (failures here flag
ROM-divergence regressions on the per-game gating).

The historical investigation that led to this fix is preserved below.

---

## CNZ1 Trace F7614 — Tails Jump Frame 2-Pixel y_pos Drift (historical investigation)

**Location:** Sidekick `Tails_Jump` execution (CPU-pressed jump while
Tails is grounded near a CNZ vertical/down spring at @0x0E38,0x04D0
sub=$12).
**Trace reference:** `src/test/resources/traces/s3k/cnz` (`cnz1_fullrun`),
first strict error at frame 7614, 3,200 errors total.

### Symptom

`TestS3kCnzTraceReplay#replayMatchesTrace` first fails at F7614:

```text
tails_y mismatch (expected=0x04B3, actual=0x04B1)
```

Per the recorded `physics.csv` around vfc=7614/7615 (ROM frames F7613/F7614):

- F7613 (vfc=7614): tails y=0x04B0, y_sub=0xB000, y_speed=0,
  status=0x01 (Direction|grounded|not-rolling), y_radius=15
  (default Tails). Tails has been stationary at (0x0E40,0x04B0) from
  F7600 through F7613 with y_sub=0xB000 unchanged.
- F7614 (vfc=7615): tails y=0x04B3, y_sub=0xB000 (UNCHANGED),
  y_speed=-0x0680 (=$F980 two's complement), status=0x07
  (Direction|InAir|Roll), y_radius=14 (rolling).
- Engine end-state at F7614: tails y=0x04B1 (=0x04B0+1), y_speed
  matches ROM at -0x0680.
- Subsequent frames: 2-pixel y offset cascades, producing 3,200
  derivative trace errors.

The fact that y_sub stays exactly 0xB000 across F7613→F7614 is
load-bearing: the +3 px change is composed of integer-only word writes
to `y_pos`, **not** a `MoveSprite` velocity application. (A
`MoveSprite_TestGravity2` apply with y_vel=-$680 would produce y=0x04AA
y_sub=0x3000, which is the F7615 state — i.e. MoveSprite runs on F7615,
not on F7614.)

### Reframed Diagnosis (this round)

The previous round's "CNZ rotating cylinder release" hypothesis is
**wrong**. Diagnostic data:

- The aux-state stream contains `cnz_cylinder_state_per_frame` /
  `cnz_cylinder_execution_per_frame` events when an `Obj_CNZCylinder`
  is interacting with Tails (e.g. F4490+). **No cylinder events
  exist anywhere in F7610–F7616.** No cylinder is active.
- The trace `cpu_state` at F7613 shows `cpu_routine=6, flight_timer=59,
  ctrl2_pressed=0x04`. At F7614 `flight_timer=60, ctrl2_pressed=0x44`
  (jump-button bit set). The CPU is forcing a jump on F7614, not
  releasing from a cylinder.
- The y_speed constant `-$680` is **also** the value `Tails_Jump`
  produces from a level (angle=0) ground stance:
  `sonic3k.asm:28534 move.w #$680, d2; ... muls.w d2, d0; asr.l #8, d0;
  add.w d0, y_vel(a0)` — with `angle - $40 = $C0`, `sin($C0) = -$100`
  giving d0 = -$680.

So the F7614 transition is: Tails was on the ground at (0x0E40,0x04B0)
for 13 frames, the CPU pressed jump, `Tails_Jump` (sonic3k.asm:28519+)
fires, sets y_vel=-$680, sets rolling radii, and integer-adjusts y_pos
to compensate.

The recorded `tailsInteract sub=$12` is the **vertical/down** spring at
slot 17 (subtype=$12 dispatches via
`lsr.w #3,d0; andi.w #$E,d0` → index 2 → `Spring_Down`,
sonic3k.asm:47570), NOT a horizontal spring. The spring is not the
launch source: `Obj_Spring_Down`'s kick `sub_233CA`
(sonic3k.asm:48088+) requires `d4=-2` from `SolidObjectFull2_1P` and
produces y_vel=+$A00 (downward, after `neg.w` from stored -$A00),
which contradicts the observed -$680. Tails's y=0x04B0 is also 8 px
above where `MvSonicOnPtfm` would seat him on this spring
(`spring.y - d3 - y_radius = 0x04D0 - 9 - 15 = 0x04B8`), so Tails is
not actively riding the spring's top surface. The recorded
`stand_on_obj=0x11` (slot 17) appears to be a stale latch from earlier
contact.

### Pixel-Accounting

Engine end-of-frame: y=0x04B1 (=0x04B0+1).
ROM end-of-frame: y=0x04B3 (=0x04B0+3).

`Tails_Jump` on a non-rolling start (sonic3k.asm:28560-28567) writes
`y_radius=$E`, `x_radius=7`, `anim=2`, sets `Status_Roll`, then
sonic3k.asm:28571-28577:

```
move.b y_radius(a0),d0       ; d0 = $E
sub.b  default_y_radius(a0),d0 ; d0 = $E - $F = -1
ext.w  d0
sub.w  d0,y_pos(a0)          ; y_pos -= -1 → y_pos += 1
```

That accounts for **only +1 px** of the ROM's +3 px. After this,
`Tails_Jump` returns via `addq.l #4,sp; ... rts`
(sonic3k.asm:28556) which pops `Tails_Stand_Path`'s return address,
so `Player_SlopeResist`, `Tails_InputAcceleration_Path`, `Tails_Roll`,
`Tails_Check_Screen_Boundaries`, `MoveSprite_TestGravity2`,
`Call_Player_AnglePos`, and `Player_SlopeRepel` are all skipped this
frame. None of those run on F7614, so the floor-snap path
(`Player_AnglePos` line 18790: `add.w d1, y_pos(a0)` for d1>0) is not
reached on the jump frame.

**Origin of the missing +2 px is unidentified after this round's
walkthrough.** Candidates ruled out by direct ROM read or trace:

- `Tails_Jump` rolling adjustment alone: only +1 px (verified).
- `Player_AnglePos` floor snap: skipped on jump frame because of
  `Tails_Jump`'s `addq.l #4,sp` (verified).
- `MoveSprite_TestGravity2` velocity apply: would change y_sub from
  0xB000 to 0x3000, but trace y_sub stays 0xB000 — so MoveSprite did
  not run on F7614.
- Cylinder release: no cylinder is active at F7610-F7616 (verified
  by absence of `cnz_cylinder_state_per_frame` events).
- Spring kick (`sub_233CA`): produces y_vel=+$A00 not -$680, and
  requires top-landing d4=-2 which is not reached given the 8-px gap
  between Tails and the spring's seat.
- `Tails_CPU_Control` cpu_routine=6 path (`loc_13D4A`,
  sonic3k.asm:26656+): only modifies x_pos, not y_pos.
- Cylinder/spring per-frame y_pos write loops: no active cylinder;
  spring's `Obj_Spring_Down` `subq.w #1,y_pos(a1); sub.w d3,y_pos(a1)`
  block (`loc_1E154` sonic3k.asm:41614-41626) only fires when the
  rider's bottom-edge probe overlaps the spring's top by < $10 px,
  which is not the geometry here (rider 8 px above seat).

### Engine-Side Note

The engine's `PlayableSpriteMovement.doJump`
(`src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
lines 615-655) calls `sprite.applyRollingRadii(true)` then
`sprite.setY(getY() + getRollHeightAdjustment())`. For Tails,
`getRollHeightAdjustment()` returns `runHeight - rollHeight = 30 - 28 = 2`
(`PhysicsProfile.java` lines 67-68; `AbstractPlayableSprite.java`
line 3110). Adjusting top-left Y by +2 with the new height of 28
shifts the centre by +2−1 = +1 px (centre = topY + height/2;
height changed 30→28). So engine centre Y goes 0x04B0 → 0x04B1,
matching ROM's `Tails_Jump` rolling adjustment.

The engine therefore applies the rolling-radius/centre adjustment
correctly. The 2-pixel residual must be a separate ROM-side y_pos
write that has not yet been identified.

### Next Steps

1. **Recorder extension landed (v6.10-s3k).** The Bizhawk recorder
   `tools/bizhawk/s3k_trace_recorder.lua` already had the
   `position_write_per_frame` plumbing (added in v6.8 for the CNZ1
   F4790 work, mirroring `velocity_write_per_frame`). On this branch
   the default capture window now also covers F7600-7625 in addition
   to the original F4788-4792, and the env-var override
   `OGGF_S3K_POSITION_WRITE_RANGE` accepts semicolon-separated
   multi-windows (e.g. `4788-4792;7600-7625`). The CNZ fixture under
   `src/test/resources/traces/s3k/cnz/` was regenerated with the new
   window so `aux_state.jsonl.gz` now carries `position_write` events
   at F7600-7625 as well.

   The captured PCs at trace_frame=7614 (vfc=7615) are:

   ```
   y_pos_writes: [
     {pc:0x150D0, val:0x04B0},   # post Tails_Jump sub.w d0,y_pos(a0)
     {pc:0x1E172, val:0x04B1},   # post subq.w #1,y_pos(a1)  in loc_1E154
     {pc:0x1E182, val:0x04B0}    # post sub.w  d3,y_pos(a1)  in loc_1E154
   ]
   ```

   Mapping back to ROM:
   - `0x150D0` is the instruction immediately after `loc_150CC`'s
     `sub.w d0,y_pos(a0)` in `Tails_Jump`
     (sonic3k.asm:28576-28579) — the rolling-radius +1 px adjustment
     the engine already replicates via `getRollHeightAdjustment()`.
   - `0x1E172` and `0x1E182` are inside `loc_1E154` of
     `SolidObjectFull/SolidObject_cont` (sonic3k.asm:41606-41624) —
     the **top-of-platform** push-up branch:

     ```
     loc_1E154:
         subq.w  #4,d3
         ...
         subq.w  #1,y_pos(a1)            ; ← 0x1E172 captures post-write
         tst.b   (Reverse_gravity_flag).w
         beq.s   loc_1E17E
         neg.w   d3
         addq.w  #2,y_pos(a1)
     loc_1E17E:
         sub.w   d3,y_pos(a1)            ; ← 0x1E182 captures post-write
     ```

     This means the `Spring_Down` (subtype=$12) at slot $11 — which
     `tailsInteract sub=$12` and `stand_on_obj=0x11` confirm Tails
     was still latched against on F7614 — is calling
     `SolidObjectFull2_1P` and reaching the top-side branch to
     re-seat Tails one pixel above the spring's top + the d3 lift,
     **not** the cylinder hypothesis from prior rounds.

   Note on hook timing: BizHawk's `event.onmemorywrite` reads back
   the post-write `u16` value, but byte-granular hooks fire per byte
   so the `val` reported for word writes can lag the actual
   end-of-instruction state by one byte. Treat the PCs as
   load-bearing and the `val`s as a hint, not a ground truth.

2. **Engine-side fix attempted, deferred (round 2026-04-30).** The PCs
   above point at the engine's `SolidObjectProvider` top-contact
   resolution as the missing -2 px (or, in trace-coordinates, missing
   +2 px because ROM's overall delta is +3 while the engine only
   produces +1). Direct engine-side analogy and ROM walkthrough were
   completed but the `loc_1E154` precondition arithmetic does not
   match the captured PCs:

   - **Engine `loc_1E154` analogue located.** The
     `loc_1E154`/`loc_1E17E` lift sequence in `SolidObject_cont`
     (sonic3k.asm:41606-41624) writes
     `y_pos(a1) = y_pos(a0) - d2_orig - y_radius - 1` on top-contact
     resolution. The engine equivalent is
     `ObjectManager.SolidContacts.resolveContactInternal` (lines
     5969-5988) which sets
     `newCenterY = playerCenterY - distY + 3`; substituting
     `distY = playerCenterY - anchorY + 4 + maxTop` gives
     `newCenterY = anchorY - maxTop - 1` — algebraically equivalent
     to ROM (with `playerHalfHeight` factored back via `setY()` /
     centre rounding). The engine therefore matches ROM when this
     resolver path actually fires.

   - **Object-side `pX_standing_bit` was clear at F7613 end.**
     Aux-state `object_state` for slot 17 at vfc=7614 reports
     `status=0x01`: only bit 0 is set, both `p1_standing_bit=3` and
     `p2_standing_bit=4` are clear (sonic3k.constants.asm:133-134).
     So the spring's `SolidObjectFull2_1P` Player_2 call on F7614
     enters via `beq.w SolidObject_cont` (sonic3k.asm:41067), NOT via
     the bit-set / `MvSonicOnPtfm` path.

   - **`loc_1E154` precondition does NOT match the trace numbers.**
     With Tails y_pos(a1) = 0x04B1 (post `Tails_Jump` rolling +1),
     spring y_pos(a0) = 0x04D0, d2_orig = 8 (Spring_Down d2 arg),
     y_radius = 14 (Tails post-rolling), default_y_radius = 15:
     `loc_1DFD6` computes
     `d3 = (0x04B1 - 0x04D0) + 4 + 8 + 14 = -5 -> 0xFFFB` ->
     `andi.w #$FFF, d3 -> 0x0FFB`,
     `d4 = 15 + 8 + 8 + 14 = $2D`;
     `cmp.w d4, d3` -> `0x0FFB > $2D` ->
     `bhs.w loc_1E0A2` (no contact). For `loc_1E154` to fire,
     pre-`andi` d3 must be in `[0, $F]`, i.e. Tails y_pos must be in
     `[0x04B6, 0x04C5]`; 0x04B1 is 5 px above that window.

   - The captured PCs `0x1E172` / `0x1E182` with `character="tails"`
     are therefore not consistent with the geometry the trace shows
     on F7614. Possibilities to confirm with a recorder revision:
     - byte-write hook frame-bucket lag (the post-write u16 is read
       after the second byte fires, so the event may be ascribed to
       the wrong vfc/frame);
     - the recorder filters by Player_2 base address, but `(a1)` at
       0x1E172 / 0x1E182 may be Player_1 — the spring's
       `SolidObjectFull2_1P` runs both player calls back-to-back from
       `Obj_Spring_Down` and the Tails-y_pos-watching hook can fire
       on a Player_1 write if the watch range is wider than expected;
     - a different routine (not `loc_1E154`) is at the disassembly-
       equivalent address in the actual ROM (the disasm uses
       `loc_<addr>` labels but the runtime-PC-to-disasm-line mapping
       must be confirmed with `RomOffsetFinder` or an ROM byte dump
       at offset 0x1E170+).

   - Engine fix not implemented this round. Implementing the
     `subq.w #1; sub.w d3` lift on the engine's
     `resolveContactInternal` path with the precondition unverified
     risks producing a +2 px lift on every frame the
     `loc_1E154`-equivalent fires (which today is many AIZ/MGZ/HCZ
     contacts, not just CNZ F7614) — a cross-game regression.

3. **Recorder extended to v6.11-s3k (round 2026-04-30).** Two diagnostic
   improvements landed on `bugfix/ai-cnz-recorder-a1-target-and-radius`
   to address the geometric contradiction; the regenerated CNZ fixture is
   deferred to a follow-up round (no BizHawk in this agent environment):

   - **`position_write` hits now record `(a1)` and `(a0)`.** The
     `WRITE_DIAG.tails_xpos_record_hit` /
     `WRITE_DIAG.tails_ypos_record_hit` callbacks in
     `tools/bizhawk/s3k_trace_recorder.lua` now snapshot the M68K A0
     and A1 registers at the moment the `event.onmemorywrite` callback
     fires, and the per-frame `position_write` JSON event embeds them
     as `"a1":"0x........"` and `"a0":"0x........"` per hit. With the
     write-watch armed on Tails's address `(a1)` MUST be Tails when the
     hook fires for an `<op> y_pos(a1)` instruction, so a Player_1
     value would prove the hook is being attributed to the wrong
     `(an)` mode (e.g. an `(a0)` write that aliased the Tails address).
     Tracked in `TraceEvent.PositionWrite.Hit` as `int a1, int a0`
     fields with a 2-arg compatibility constructor for pre-v6.11
     fixtures.
   - **New `solid_object_cont_entry` event.** A new
     `event.onmemoryexecute` hook at PC=`0x1DF90` (the byte address of
     the `SolidObject_cont` label, sonic3k.asm:41394 — verified by
     walking ROM bytes from `loc_1DF88`'s `4A 28 0004` /
     `tst.b render_flags(a0)` at `0x1DF88`, then `6A 00 0114` /
     `bpl.w loc_1E0A2` at `0x1DF8C`, then
     `30 29 0010` / `move.w x_pos(a1),d0` at `0x1DF90`) records the
     `(a0)`/`(a1)`/d1/d2 register state along with the player's
     `y_radius` and `default_y_radius` (read live from RAM at
     `(a1) + 0x1E` and `(a1) + 0x16`), plus the player's pixel x/y and
     status. Default capture window mirrors `position_write`
     (`{4788-4792, 7600-7625}`); operators can override via the new
     `OGGF_S3K_SOLID_CONT_RANGE` env var (single `<lo>-<hi>` window or
     semicolon-separated multi-window). The aux schema advertises this
     event via the new `solid_object_cont_entry_per_frame` extra; the
     parser's `default ->` branch in `TraceEvent.parseEvent` tolerates
     it as a `StateSnapshot` for fixtures that lack a typed model.

   **ROM byte mapping verified.** Direct hex dump of
   `Sonic and Knuckles & Sonic 3 (W) [!].gen` at offset `0x1E150`
   confirms the `loc_1E154` block bytes match sonic3k.asm:41606-41624
   exactly. Specifically:
   - `0x1E154`: `5943` = `subq.w #4, d3`
   - `0x1E16E`: `5369 0014` = **`subq.w #1, y_pos(a1)`**
     (post-instruction PC = `0x1E172` ✓ matches the captured value)
   - `0x1E17E`: `9769 0014` = **`sub.w d3, y_pos(a1)`**
     (post-instruction PC = `0x1E182` ✓ matches the captured value)

   So `0x1E172` and `0x1E182` are the post-fetch PCs immediately AFTER
   the corresponding `<op> y_pos(a1)` instructions in the loc_1E154
   push-up branch. The disasm-label-to-ROM-offset mapping is
   straightforward (`loc_<hex>` = ROM byte offset); the captured PCs
   include BizHawk's standard "PC after instruction completion" lag
   for `event.onmemorywrite`. **Confirmed: 0x1E172 and 0x1E182 are
   inside the loc_1E154 push-up branch.** Possibility (3) from prior
   round (different routine at the disasm-equivalent address) is
   **ruled out**.

4. **Next-round actions (require BizHawk fixture regeneration):**

   With v6.11-s3k armed, the next BizHawk re-record needs to set
   `OGGF_S3K_TRACE_PROFILE=cnz1_fullrun`,
   `OGGF_S3K_POSITION_WRITE_RANGE=4788-4792;7600-7625` (default), and
   leave `OGGF_S3K_SOLID_CONT_RANGE` unset to take the default
   `{4788-4792, 7600-7625}` window. The regenerated
   `src/test/resources/traces/s3k/cnz/aux_state.jsonl.gz` should then
   contain, at trace_frame=7614:

   - `position_write` event with each of the three captured y_pos hits
     now carrying `a1`/`a0` — confirming whether `(a1)` was Tails
     ($FFB04A low word) or Sonic ($FFB000 low word) at write time, and
     whether `(a0)` points to the Spring_Down slot or some other OST
     slot.
   - One or more `solid_object_cont_entry` events for the same frame
     showing `(a0)`/`(a1)`, `y_radius`, `default_y_radius`, and the
     pre-resolution `player_y` so the d3/d4 derivation in
     `loc_1DFD6`+ can be reconstructed exactly.

   If the regenerated trace shows `(a1)=$FFB04A` (Tails) at
   `0x1E172`/`0x1E182` AND the recorded `y_radius`/`default_y_radius`
   are 14/15, then the geometric contradiction stands and the next
   investigation must look for a different `(a0)` solid object whose
   y_pos differs from the Spring_Down's `0x04D0` (e.g. a different
   slot reaching `loc_1E154` against Tails on the same frame).

   If `(a1)=$FFB000` (Sonic) at one or both PCs, then the recorder's
   address-watch is firing on an aliased Player_1 write and the prior
   round's "captured PCs prove a top-lift on Tails" conclusion is
   void; the F7614 +2 px residual must originate elsewhere.

   - **Object-loop ordering check.** A frame-by-frame walk of
     `Process_Sprites` order at vfc=7615 would isolate which slot
     touches `Player_2.y_pos` between Tails's `Tails_Modes` pass and
     end-of-frame. The new `position_write` `(a0)` field combined with
     `solid_object_cont_entry`'s `(a0)` (and its `solid_x`/`solid_y`)
     makes this tractable without an additional recorder revision.

5. **"Tails despawn" hypothesis refuted (round 2026-04-30 followup).**
   A round-launch user observation suggested ROM might have despawned
   Tails by F7614 so that the captured `0x1E172`/`0x1E182` y_pos writes
   were actually targeting Sonic via aliased addressing rather than
   Tails. The recorded fixture does not support this:

   - **Tails is alive and active across F7600-F7625.** The committed
     `physics.csv.gz` rows for vfc=7601-7626 show
     `sidekick_present=1`, `sidekick_routine=0x02`, `sidekick_status_byte`
     transitioning `0x01` (grounded, facing-right) -> `0x07`
     (grounded+InAir+Roll set by `Tails_Jump`) on vfc=7615 (F7614),
     and `sidekick_x` / `sidekick_y` updating every frame from the
     stationary `(0x0E47, 0x04B0)` pre-jump position into the post-jump
     parabola `(0x0E3F, 0x04B3)` -> `(0x0E26, 0x047D)`. A despawned
     Tails would have frozen coordinates and routine 0; the trace shows
     the opposite. Captured by reading the gzip CSV with
     `gameplay_frame_counter=0x1DB0..0x1DC8`, sidekick_* columns 22-36.
   - **Watch addresses physically pin to Player_2.** The recorder
     hooks `event.onmemorywrite` at byte addresses
     `M68K_RAM_BASE + 0xB04A + 0x14` and `+ 0x15`
     (`tools/bizhawk/s3k_trace_recorder.lua` lines 1534-1611, with
     `OBJ_TABLE_START = 0xB000`, `OBJ_SLOT_SIZE = 0x4A`,
     `SIDEKICK_BASE = 0xB04A`, `OFF_Y_POS = 0x14`). The watched bytes
     `$FFFFB05E` / `$FFFFB05F` are exactly Player_2's `y_pos` field
     (Player_2 base = `$FFFFB04A`, `y_pos` = offset $14). For
     `subq.w #1, y_pos(a1)` at runtime PC `0x1E16E` to fire the hook,
     `(a1) + 0x14` MUST equal `$FFFFB05E`, hence `(a1) = $FFFFB04A =
     Player_2 = Tails`. The "(a1) was Sonic" path is not physically
     reachable through this hook; a Sonic-targeted write of the same
     instruction would write to `$FFFFB014` and silently miss the
     watch entirely.
   - **CSV column inventory used.** Columns 0,2-9,18-22,23-29,30-36 of
     the CSV header (`frame, x, y, x_speed, y_speed, g_speed, angle,
     air, rolling, gameplay_frame_counter, stand_on_obj, vblank_counter,
     lag_counter, sidekick_present, sidekick_x, sidekick_y,
     sidekick_x_speed, sidekick_y_speed, sidekick_g_speed,
     sidekick_angle, sidekick_air, sidekick_rolling, sidekick_ground_mode,
     sidekick_x_sub, sidekick_y_sub, sidekick_routine,
     sidekick_status_byte, sidekick_stand_on_obj`) confirm the above.

   So the despawn hypothesis is **ruled out** without needing the
   v6.11-s3k regeneration. The geometric contradiction stands as
   stated in step 2 above: with `(a1)` definitively Tails and the
   captured y_pos values bracketing a `subq.w #1` then `sub.w d3`
   pattern that only appears in `loc_1E154` (ROM bytes verified in
   step 3), the ROM is reaching `loc_1E154` against Tails on F7614
   for some `(a0)` whose geometry differs from the Spring_Down at
   slot $11. The next-round actions in step 4 (regenerate fixture
   with v6.11-s3k armed so the captured `(a0)` reveals which solid
   object is the lifter) remain the unblocking step. No engine code
   change is safe to land in this round; doc-only.

6. **v6.11-s3k fixture regenerated; ROM-side path reframed (round 2026-04-30 followup).**
   The CNZ fixture under `src/test/resources/traces/s3k/cnz/` was
   regenerated against `lua_script_version: "6.11-s3k"` (commit
   `ce4278974`). Two structural findings landed by inspecting the
   regenerated `aux_state.jsonl.gz`:

   - **`(a0)` at the `loc_1E154` lift PCs is slot $11 = the same
     spring object Tails was riding.** The `position_write` event at
     trace_frame=7614 carries `a0=$FFFFB4EA` for both `0x1E172` and
     `0x1E182` writes (and `a1=$FFFFB04A` = Tails). Slot 17 = $FFB4EA
     given `OBJ_TABLE_START=$B000` and `OBJ_SLOT_SIZE=$4A`
     (17×$4A = $4EA). The `object_state` for slot 17 across F7600-7619
     reports `object_code=0x00023050, subtype=0x12, x=0x0E38,
     y=0x04D0`. That `object_code` is the function pointer the slot
     was initialized with by `Obj_Spring`'s dispatch
     (sonic3k.asm:47500-47513). Subtype `$12` selects index `2` via
     `lsr.w #3, d0; andi.w #$E, d0` — but **index 2 dispatches to
     `Spring_Down` per `Spring_Index` (sonic3k.asm:47517-47522)**, not
     to `Spring_Horizontal` as the d1/d2 width signature alone would
     suggest. Despite that, the captured d1/d2/d3 values from the
     regenerated `solid_object_cont_entry` event (`d1=0x0013, d2=0x000E`
     for the Tails-targeted iterations) match
     `Obj_Spring_Horizontal` (sonic3k.asm:47772-47774), **not**
     `Obj_Spring_Down`. The most likely explanation is that the slot's
     active routine pointer is updated post-init to
     `Obj_Spring_Horizontal` while the on-disk function-pointer field
     read by the recorder still shows the original `Obj_Spring`
     dispatcher pointer; the geometry the SolidObject helpers see
     belongs to the horizontal variant. Cross-checking with
     `Obj_Spring_Horizontal`'s `move.w #$13,d1; move.w #$E,d2;
     move.w #$F,d3` (lines 47772-47774) gives an exact match.

   - **The geometric contradiction in step 2 dissolves.** The
     "spring is 8 px below the rider's seat" math used `Spring_Down`'s
     `d2=8`, but the actual lifter at F7614 has `d2=0x0E` (horizontal
     spring's halfHeight). With Tails y_pos = 0x04B1 (post-`Tails_Jump`
     rolling +1), spring y_pos = 0x04D0, d2_orig = 0x0E,
     y_radius = 0x0E (Tails post-roll), default_y_radius = 0x0F:
     ```
     loc_1DFD6 d3 = (0x04B1 - 0x04D0) + 4 + 0x0E + 0x0E
                = -0x1F + 4 + 0x1C
                = 1
     cmpi.w #0x10, d3   →  1 < 0x10  →  blo.s loc_1E154 ✓
     loc_1E154:
       subq.w #4, d3    →  d3 = -3
       subq.w #1, y_pos(a1)  → y goes 0x04B1 → 0x04B0   (recorded `0x1E172 val=0x04B1` = pre-write hit timing)
       sub.w  d3, y_pos(a1)  → y goes 0x04B0 → 0x04B0 - (-3) = 0x04B3 (recorded `0x1E182 val=0x04B0` = pre-write hit timing)
     ```
     Net lift on F7614: y goes 0x04B1 → 0x04B3 (+2 px). Combined with
     `Tails_Jump`'s rolling +1, the total y_pos delta from F7613 end
     (0x04B0) to F7614 end (0x04B3) is exactly the +3 px the trace
     shows. **The ROM-side accounting is now complete.**

     The recorded BizHawk hook timing semantics for byte writes:
     `event.onmemorywrite` reads the watched word's value via
     `mainmemory.read_u16_be` AT THE MOMENT THE HOOK FIRES. For a
     `<op>.w y_pos(a1)` instruction the hook fires before the second
     byte's commit propagates back to `mainmemory`, so the captured
     `val` corresponds to the **pre-write** word value, not the
     post-write value. This explains the apparent -1 / -1 readings
     while the actual instruction effect is -1 (subq) then +3 (sub.w
     of d3=-3). The aux event field thus reads as "y just before this
     instruction wrote" rather than "y just after" — load-bearing for
     post-hoc reconstruction.

   - **Engine-side gap.** The engine's `resolveContactInternal`
     STANDING path (`ObjectManager.java:5969-5988`) sets
     `newCenterY = playerCenterY - distY + 3`, which is algebraically
     equivalent to ROM's `loc_1E154` final y. With `distY=1`,
     `newCenterY = playerCenterY + 2`, mapping Tails y from 0x04B1 to
     0x04B3. The engine therefore SHOULD produce 0x04B3 if the spring's
     resolveContact path fires for Tails on F7614 with these inputs.
     Engine's actual y at F7614 = 0x04B1 (only the +1 rolling-radius
     adjustment applied). The remaining gap is in the spring-vs-Tails
     contact dispatch, not in the lift arithmetic. Possible causes
     under investigation:
     - The spring's `isSolidFor` returns false for Tails this frame
       (e.g. `proactiveTriggeredThisUpdate.contains(player)` from a
       same-frame proactive trigger that ROM doesn't fire).
     - Tails's center coordinates at the time of the contact pass
       differ from ROM's by an integer pixel (e.g. the engine applies
       Tails_Jump's +1 BEFORE the contact pass while ROM applies it
       inside the same dispatch — the pre-/post-jump ordering matters
       for `distY`).
     - Sidekick's solid-contact pass for this spring is gated off
       (e.g. `useStickyBuffer=false` for sidekick, or
       `processCompatibilityCheckpoint` skips Tails when the leader
       is the active player for that solid).

   - **Trace-event parser fix landed.** The v6.11-s3k recorder emits
     `a0` and `a1` as 64-bit hex strings (e.g.
     `"0xFFFFFFFFFFFFB04A"`) because Lua's `string.format("%08X",
     ...)` with negative numbers under-truncates. The Java parser at
     `TraceEvent.parseHexInt` previously used `Integer.parseInt(hex,
     16)`, which fails on values >32-bit. Fixed to use
     `Long.parseUnsignedLong(hex, 16)` and cast to `int` (only the
     low 32 bits are semantically used as M68K addresses are
     24-bit). Without this fix the test cannot even load the
     regenerated fixture (`NumberFormatException: For input string:
     "FFFFFFFFFFFFB000"`).

   - **Cross-game safety verified.** S1 GHZ
     (`TestS1Ghz1TraceReplay`), S1 MZ
     (`TestS1Mz1TraceReplay`), and S2 EHZ
     (`TestS2Ehz1TraceReplay`) all PASS with the parser fix in
     place. AIZ first-error stays at F4679 (existing baseline).
     CNZ first-error stays at F7614 (the engine fix is deferred —
     the parser fix is required just to run the test).

   No engine-side fix lands in this round. The remaining work is
   isolating which dispatch gate prevents the engine from invoking
   `resolveContactInternal` for the spring/Tails pair on F7614, then
   landing a ROM-cited fix gated through typed `GameRules` or via
   a spring-instance change that mirrors the ROM `Obj_Spring_Horizontal`
   per-frame `SolidObjectFull2_1P` dispatch independent of Tails's
   air state.

### Removal Condition

Remove this entry once `TestS3kCnzTraceReplay#replayMatchesTrace`
advances past F7614 with a ROM-cited fix that explains the +3 px
y_pos delta on the `Tails_Jump` frame. The fix must:

- Cite ROM lines in `docs/skdisasm/sonic3k.asm` for the y_pos write
  it implements.
- Preserve the comparison-only trace invariant (no per-frame writes
  from CSV/aux into the engine in committed test code).
- Keep S1 GHZ, S1 MZ1, S2 EHZ, and S3K AIZ traces green.
- If the fix is per-game, gate it through the narrowest typed `GameRules`
  rule or object-local owner, not `if (gameId == GameId.S3K)`.

---

## AIZ2 Trace F7127 — Tails Phantom Landing While Falling

**Status:** RESOLVED — root cause was a stuck `state==2`
(solid-stay) on `Sonic3kCollapsingPlatformObjectInstance` after Sonic
left the platform mid-stay, leaving the invisible-but-still-solid parent
in place indefinitely so that Tails (passing through the X range ~250
frames later) snap-landed on a phantom surface that ROM had already
demoted to the falling/fragments-only state. Fix: the engine's
`update()` switch in `Sonic3kCollapsingPlatformObjectInstance` now
unconditionally promotes `state=2` → `state=3` on the frame after
`releasePending` is set, mirroring ROM `loc_205DE` (sonic3k.asm:44850-44854)
which rewrites the action pointer to `loc_20620` whenever `$38`
underflows, regardless of whether either player still has the platform's
standing bit set. Previously the engine waited for the next solid pass
to see a standing contact via `onSolidContact` (sonic3k.asm:44864
`sub_205FC`) before transitioning, which is correct ONLY when the player
stays on the platform through fragmentation; ROM's transition is
unconditional.

The trace report's first error advances from F7127 to F7171 with this
fix in place. The four candidates flagged in the prior round
(collision-index off-by-one, top-solid-bit mismatch, AIZ2 reload-resume
pointer staleness, Tails airborne-rolling y_radius rule) were all ruled
out: the engine sensor probe at F7126/F7127 reported `dist=4` (no
penetration) and `resolved=false`, confirming terrain collision was
correct. The actual landing came from `ObjectManager.SolidContacts.
resolveContactInternal:5977` (the flat-solid top-landing path) chained
from `resolveSlopedContact:5679` for the F7126 CollapsingPlatform spawn
at `(0x0E70, 0x0368)`, which Sonic had triggered around F6929 (state=0
→ 1) and which the engine had carried through state=2 from ~F6943
onward without ever advancing to state=3.

**Location:** Sidekick airborne→ground transition path (Tails following
Sonic into the AIZ2 narrow corridor near `(0x0E42, 0x033B)`).
**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`
(`aiz1_to_hcz_fullrun` profile), first strict error at frame 7127,
1,062 errors total.

### Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` first fails at F7127:

```text
tails_y mismatch (expected=0x033B, actual=0x0339)
```

Per `target/trace-reports/s3k_aiz1_context.txt` around F7126-F7136:

- F7126: ROM and engine agree. Tails airborne+rolling at
  `(0x0E3E, 0x0335)`, x_speed=0x046C, y_speed=0x05B0 (falling fast),
  ground_vel=0x00A8, x_radius/y_radius standard.
- F7127: ROM has `tails_y=0x033B, y_speed=0x05E8, air=1, ground_vel=0x00A8`
  (Tails continues falling, gravity adds 0x38 to y_speed).
  Engine has `tails_y=0x0339, y_speed=0x0000, air=0,
  ground_vel=0x0484` (engine LANDS Tails, sets ground_vel=former
  x_speed). The 2-pixel y mismatch and the air=0 vs air=1 are the
  load-bearing divergences.
- F7128-F7140: Engine Tails walks slowly along non-existent terrain
  while ROM Tails keeps falling (y goes 0x033B→0x0340→0x0347→…,
  y_speed gains 0x38 per frame as expected for free-fall under
  gravity 0x38). The engine's phantom-landing y_pos drifts up by
  ~1 px per ~2 frames as it tries to walk a flat surface.

Diagnostic line at F7127:
```
tailsCpu status=06 obj=00 gv=00A8 xv=046C stat=06 input=7800
        branch=fallthrough_sub20 ctrl2=0000/00 post=0000,0000,00
tailsInteract slot=16 ptr=B4A0 obj=0002BF5A rtn=00 st=00 @0F85,0338
        sub=00 onObj=false objP2=false active=true
```

The recorded `branch=fallthrough_sub20` is **not** the divergence
itself; it is just the lua's diagnostic snapshot of which path the
ROM's `Tails_CPU_Control` (sonic3k.asm:26696, `loc_13DD0`) entered for
the *next* frame's input mask. At F7127, both ROM and engine see
Tails_CPU's leader sample as "leader grounded, slow gv<$400, no
on-object" → the d2 target gets `subi.w #$20,d2` (sonic3k.asm:26694),
hence the lua's `fallthrough_sub20` label. This is purely an input
mask, not a position write.

The interact slot's `obj=0002BF5A` resolves to `Obj_AnimatedStillSprites`
(sonic3k.asm:60394, `loc_2BF5A`) — a non-solid display object — so the
engine's `tailsInteract.active=true` is a stale visual reference, not a
solid-object collision.

### Diagnosed Constraints

The shape of the divergence (engine landing Tails 2 px above where ROM
keeps falling, with engine `ground_vel = 0x0484` exactly equal to the
prior `x_speed`) implies:

1. The engine's terrain probe (`CollisionSystem.resolveAirCollision`,
   `src/main/java/com/openggf/physics/CollisionSystem.java:476`) detects
   ground at engine `y=0x0339` and runs the airborne-landing handler,
   which sets `y_speed=0`, `ground_vel=x_speed`, clears `air`.
2. ROM's equivalent (`Sonic_DoLevelCollision` / `Tails_TouchFloor`,
   sonic3k.asm:24340-24369) finds *no* floor at the same y, so Tails
   continues falling.

This is at the AIZ2 reload-resume checkpoint (`cp aiz2_reload_resume
z=0 a=1 ap=0 gm=12`) so the divergence is **inside AIZ2**'s level
collision data after the AIZ1→AIZ2 seamless transition. Possible
causes:

- **AIZ2 collision-index off-by-one** in the engine's collision
  array load (`Sonic3kLevel.loadChunksWithCollision` /
  `decodeCollisionPointer`). A misaligned collision index for a
  specific AIZ2 chunk would phantom-place a floor 2 px higher than
  ROM at this exact location.
- **Top-solid bit (`top_solid_bit` / `lrb_solid_bit`)** mismatch. AIZ2
  uses dual-path collision (DUAL_PATH model, sonic3k.asm:41051+).
  If the engine has Tails reading the wrong path (Primary vs
  Secondary), a phantom floor could appear.
- **Camera-bound AIZ2 reload offset** — prior bugs in this file
  (F5497, F5736) document the AIZ2 reload checkpoint as fragile;
  another stale bound or path table after the seamless transition
  could plant a phantom floor pointer.
- **Y-radius drift** — Tails airborne+rolling has y_radius=$0E (14)
  in ROM (sonic3k.asm:68062 sets it on jump-from-cylinder; rolling
  also sets y_radius=$0E in `Sonic_RollMode` / `Sonic_Roll_BalanceCheck`,
  sonic3k.asm:24400+). If the engine's airborne ground-sensor probe
  uses 16 (standing) instead of 14 (rolling) for the y-offset, the
  sensor would hit ground 2 px earlier — exactly the observed shape.

The ROM's airborne floor probe uses `y_radius` (current, 14 when
rolling) to compute the sensor offset. Engine ground sensors (S3K
DUAL_PATH, `Sensor.scan` callers) need to be audited for whether they
read CURRENT y_radius or default y_radius for sidekick airborne probes.

This entry intentionally does not propose a fix until the camera bound
state, AIZ2 collision pointer table, and the airborne-rolling sensor
y_offset are each independently verified against `target/trace-reports/`
diagnostic output around F7126-F7128.

### Removal Condition

Remove this entry once `TestS3kAizTraceReplay#replayMatchesTrace`
advances past F7127 with a ROM-cited fix mapped to one of the four
candidates above. The fix must:

- Cite ROM lines in `docs/skdisasm/sonic3k.asm` for any sensor /
  collision / radius rule it changes (and cross-check `s2disasm` if
  the rule is shared).
- Preserve the comparison-only trace invariant (no per-frame writes
  from CSV/aux into the engine in committed test code).
- Keep S1 GHZ, S1 MZ1, S2 EHZ traces green.
- If the fix is per-game, gate it through the narrowest typed `GameRules`
  rule or object-local owner, not `if (gameId == GameId.S3K)`.

---

## AIZ2 Trace F7171 — Tails Killed Mid-Run vs. Engine Continuing Follow-Steering

**Status:** OPEN — next AIZ trace blocker after F7127 was resolved.

**Location:** AIZ2 reload-resume run (`cp aiz2_reload_resume z=0 a=1 ap=0
gm=12`). Tails is following Sonic east through the corridor near
`y_pos ≈ 0x047E`. Sonic is at `y_pos = 0x02FA`, well above. The two
players are at very different y-positions; Tails is on a lower path.

**Trace reference:** `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`,
`TestS3kAizTraceReplay#replayMatchesTrace` first strict error at
F7171, 1049 errors, 0 warnings.

### Symptom

```text
First error: frame 7171 -- tails_x_speed mismatch
    (expected=0x0000, actual=0x0120)
```

`target/trace-reports/s3k_aiz1_context.txt` rows F7166-F7172 show:

| Frame | tails_x | tails_y | xv | yv | gv | air |
|-------|---------|---------|----|----|----|-----|
| F7170 | 0x0ECE  | 0x047E  | 0x0114 | 0x0000 | 0x0114 | 0 |
| F7171 ROM | 0x0ECF | **0x0477** | **0x0000** | **-0x0700** | **0x0000** | **1** |
| F7171 ENG | 0x0ED0 | 0x047E  | 0x0120 | 0x0000 | 0x0120 | 0 |
| F7172 ROM | **0x7F00** | -0x0007 | 0x0000 | 0x0000 | 0x0000 | 1 |

The ROM-side shape is unmistakable: `y_speed = -0x0700`,
`x_speed = 0`, `g_speed = 0`, `air = 1`, with `routine = 6` written
into the sidekick's routine field at F7172 (per the
`sidekick=...rtn=06` aux line). This is **`Kill_Character`**
(sonic3k.asm:21141 `loc_1036E`):

```asm
loc_1036E:
    clr.b   status_secondary(a0)
    clr.b   status_tertiary(a0)
    move.b  #6,routine(a0)             ; routine 6 = dead/falling
    jsr     (Player_TouchFloor).l       ; restore default radii
    bset    #Status_InAir,status(a0)    ; air = 1
    move.w  #-$700,y_vel(a0)            ; bounce-up velocity
    move.w  #0,x_vel(a0)
    move.w  #0,ground_vel(a0)
    move.b  #$18,anim(a0)               ; death-animation id
```

The next frame F7172 then shows `tails_x = 0x7F00, tails_y = -0x0007`
— i.e. the Tails-CPU off-screen-watchdog respawn path
(`sub_13ECA` at sonic3k.asm:26800 writes `x_pos = 0x7F00`,
`y_pos = 0`) has fired, after which `MoveSprite_TestGravity`
(sonic3k.asm:36077) adds `+0x38` to `y_vel` and applies the velocity
to position, dropping y from 0 to -7. This confirms the death-and-
respawn chain ran end-to-end on the ROM side.

The engine, by contrast, runs `SidekickCpuController.updateNormal()`
with `branch=leader_fast` (Sonic's `g_speed = 0x0600 ≥ 0x0400`,
sonic3k.asm:26692-26694) and writes `tails_x_speed = 0x0120`,
`tails_g_speed = 0x0120`, `tails_y_speed = 0`, `air = 0` — a normal
right-input acceleration step, never invoking `Kill_Character`.

### What kills ROM Tails at F7171

The trigger is **not** any of:

- `Tails_CPU_Control` (sonic3k.asm:26354+): Both ROM and engine see
  `branch=leader_fast`, `ctrl2=0x0808` (right-pressed only, **no
  jump button**), `dx=0x004C`, `dy=0xFE7B`. The auto-jump latch at
  `loc_13E9C` (sonic3k.asm:26775) fails its 64-frame gate
  (`(Level_frame_counter+1).w & $3F = 0x18 ≠ 0`) and the distance
  gate (`|dx|=0x4C ≥ $40` and `Level_frame_counter & $FF =
  0xD8 ≠ 0`). So neither path reaches `loc_13E9C` — Tails is not
  jumping.
- `Tails_Jump` (sonic3k.asm:28519): reads
  `Ctrl_2_pressed_logical & A|B|C`. Trace `ctrl2=0808/08` has no
  jump-press bit (high byte `$08` = right_pressed, not A/B/C).
- `Obj_TwistedRamp` (sonic3k.asm:50001): launch gate requires
  `x_vel ≥ $400`. Tails has `x_vel = 0x0114 < 0x0400` → falls through.

The candidates that DO write `y_vel = -0x0700` AND zero `x_vel`/
`ground_vel` simultaneously are:

1. **`Player_LevelBound` / `Tails_Check_Screen_Boundaries` →
   `Kill_Character`** (sonic3k.asm:23172 / 28407 → 21141). Tails's
   y_pos=0x047E exceeds `Camera_max_Y_pos + 0xE0` (the bottom
   kill plane). `Tails_Check_Screen_Boundaries` `jmp`s into
   `Kill_Character`, which is reached BEFORE `MoveSprite` in the
   call chain — this means Tails position at F7171 stays at 0x047E,
   which contradicts the observed F7171 `tails_y = 0x0477`.
2. **`TouchResponse` hit on a deadly enemy/spike** (sonic3k.asm:
   26266 inside `Obj_Tails`). `TouchResponse` runs AFTER
   `Tails_Modes` / `MoveSprite`, so position has already advanced by
   one frame's velocity, then `Hurt_Sonic` → `loc_1036E`
   `Kill_Character` is invoked. The 7-pixel y-decrease (0x047E →
   0x0477) is consistent with `MoveSprite_TestGravity2` having
   already run with `y_vel = -0x0700` set BY a previous call —
   **but** `Tails_Stand_Path` only calls `MoveSprite` once.
3. **`sub_F846` background-collision crush kill** (sonic3k.asm:
   19946 + 27531-27533): inside `Tails_Stand_Path` the sequence
   `bsr.w sub_F846 / tst.w d1 / bmi.w Kill_Character` fires when
   `Background_collision_flag` is set and `FindFloor` returns a
   negative penetration. AIZ2 does not normally enable
   `Background_collision_flag`, so this candidate is unlikely.

The **observed F7171 position shift `0x047E → 0x0477` (y up by 7
pixels)** can only be produced by `MoveSprite` running with
`y_vel = -0x0700` already in place. The simplest reading consistent
with the trace is that `Kill_Character` ran early in the frame
(probably from the boundary check), which set `y_vel = -0x0700`,
and the post-Kill flow somehow also reached `MoveSprite_TestGravity2`
to apply that velocity. Locating the exact call site needs an
extended ROM trace recorder — the current aux only samples
end-of-frame state.

### Diagnosed Constraints

- `sprite.getY()` returns top-left Y, but ROM `y_pos(a0)` is
  ROM-centre Y. The engine's level-boundary check
  (`PlayableSpriteMovement.doLevelBoundary`, line ~1891):
  ```java
  if (sprite.getY() > effectiveMaxY + 224) {
      ...
      cpuController.despawn(LEVEL_BOUNDARY);
  ```
  computes `getY()` = `centreY - height/2`. For Tails with
  `height_pixels = 0x18 = 24`, `getY() = y_pos - 12`. The engine
  therefore triggers the boundary kill **12 pixels below** where
  ROM does. This is a long-standing latent off-by-12 that has not
  surfaced before because most kill-plane crossings happen far
  below the threshold (where the 12-pixel gap is invisible).
- ROM `Tails_Check_Screen_Boundaries` (sonic3k.asm:28428-28431)
  uses `cmp.w y_pos(a0),(Camera_max_Y_pos+0xE0) / blt.s` which is
  `y_pos > maxY + 0xE0` semantically (m68k `cmp.w src,dst` →
  `dst - src` then `blt` on signed-less). The engine's `>` matches
  this comparator; only the `getY()` vs `getCentreY()` side is off.
- Switching the engine to `getCentreY()` is a global change that
  affects Sonic too (Sonic height_pixels = 0x28 → 20 px offset).
  Audit needed: do other AIZ/CNZ traces depend on the current
  off-by-12 boundary semantics? `TestS3kAizTraceReplay`,
  `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay` should be
  re-run after any change.
- The 7-pixel y-up at F7171 (after Kill_Character zeroes velocities
  but writes `y_vel=-0x0700`) needs an extended recorder probe to
  pinpoint whether `MoveSprite` ran post-Kill or whether
  `Player_TouchFloor` shifts `y_pos` for a non-rolling sidekick
  in some path the engine hasn't replicated.

### Ruled-Out Hypotheses

- **`Tails_CPU_auto_jump_flag` or `loc_13E9C` auto-jump**: The
  64-frame and distance gates fail at F7171 (`Level_frame_counter
  = 0x1AD8`, `|dx| = 0x4C ≥ $40`). Neither ROM nor engine sees the
  auto-jump trigger fire.
- **`Tails_CPU_flight_timer` despawn warp via `sub_13EFC`**: At
  F7171 Tails is on-screen (Sonic is at `y_pos=0x02FA`, Tails at
  `y_pos=0x047E`, both inside the camera 0x0EDA cam-X window of
  ±320). The trace `tails rf=04` shows render_flags bit 7 clear
  (off-screen high-bit), but Tails was visible in prior frames
  and the timer would not have accumulated the 5*60 frames needed
  to trigger `sub_13ECA` from this branch.
- **Tails_TwistedRamp launch**: requires `x_vel ≥ 0x0400`; Tails
  has only `0x0114`.

### Removal Condition

Remove this entry once `TestS3kAizTraceReplay#replayMatchesTrace`
advances past F7171 with a ROM-cited fix. The fix must:

- Cite ROM lines in `docs/skdisasm/sonic3k.asm` for any
  `Player_LevelBound` / `Tails_Check_Screen_Boundaries` /
  `Kill_Character` / `TouchResponse` / `sub_F846` rule it changes.
- Preserve the comparison-only trace invariant (no per-frame
  writes from CSV/aux into the engine in committed test code).
- Keep S1 GHZ, S1 MZ1, S2 EHZ, S3K AIZ post-F7127, and S3K CNZ
  pre-F7614 traces green (CNZ first-error must stay at or advance
  past F7614).
- If the fix is per-game, gate it through the narrowest typed `GameRules`
  rule or object-local owner, not `if (gameId == GameId.S3K)`.
- The boundary-kill `getY()` vs `getCentreY()` divergence
  identified above is the most promising candidate. Before
  switching, audit Sonic-side kill behaviour across traces
  (existing AIZ/CNZ blockers in this file may have implicitly
  calibrated against the off-by-12 semantics). The shared
  `getY()` call lives in `PlayableSpriteMovement.doLevelBoundary`
  (line ~1891), used uniformly by all sprites; if the change
  is needed only for sidekicks, use a focused `PlayerMovementRules` field
  or a sidekick-local owner with explicit cross-game values.

### Cross-Game Audit (2026-04-30)

All three games' bottom-boundary checks compare against
`y_pos(a0)` / `obY(a0)` — i.e. ROM-centre Y, not top-left:

| Game | ROM | File:Line | Compare |
|------|-----|-----------|---------|
| S1 (Sonic) | `Sonic_LevelBound` `.bottom` | `s1disasm/_incObj/01 Sonic.asm:1014` | `cmp.w obY(a0),d0 / blt.s .bottom` |
| S2 (Sonic) | `Sonic_LevelBound` `Sonic_Boundary_CheckBottom` | `s2.asm:36936-36951` | `cmp.w y_pos(a0),d0 / blt.s Sonic_Boundary_Bottom` |
| S3K (Sonic) | `Player_LevelBound` `Player_Boundary_CheckBottom` | `sonic3k.asm:23188-23196` | `cmp.w y_pos(a0),d0 / blt.s Player_Boundary_Bottom` |
| S3K (Tails CPU) | `Tails_Check_Screen_Boundaries` `loc_14F30` | `sonic3k.asm:28423-28431` | `cmp.w y_pos(a0),d0 / blt.s loc_14F56` |

S2 also has a `fixBugs` block (s2.asm:36938-36948) clamping
`Camera_Max_Y_pos` to its target, which the engine already mirrors via
`Math.max(camera.getMaxY(), camera.getMaxYTarget())`. S1 has the
equivalent `FixBugs` clamp at s1disasm/_incObj/01 Sonic.asm:1003-1012.

Engine `PlayableSpriteMovement.doLevelBoundary` (~line 1891) compares
`sprite.getY()` (top-left) instead of centre, which is the off-by-
`height/2` divergence. For Tails (`height_pixels = 0x18 = 24`)
that is 12 px; for Sonic (`height_pixels = 0x28 = 40`) that is 20 px.

The kill velocity writes (`x_vel = 0`, `y_vel = -0x700`,
`ground_vel = 0`) inside `Kill_Character` (sonic3k.asm:21141-21151)
are already replicated by:

- `SidekickCpuController.beginLevelBoundaryKill` for the CPU sidekick
  path (`AbstractPlayableSprite.setXSpeed/YSpeed/GSpeed` zeroes,
  sets `air=true`, then routine-6-equivalent `DEAD_FALLING` state).
- `AbstractPlayableSprite.applyDeath` for the human-controlled path
  (matches the same field writes with `setYSpeed((short) -0x700)`,
  see `applyPitDeath`).

So the missing piece is purely the **comparand**: switch
`sprite.getY()` to `sprite.getCentreY()` for the bottom kill plane
test. The proposed `PlayerMovementRules.levelBoundaryUsesCentreY`
rule should default to `true` for `SONIC_3K` (ROM-accurate) and
`false` for `SONIC_1` and `SONIC_2` until their trace baselines
are verified to honour the same kill semantics. The S1 GHZ/MZ1
and S2 EHZ traces were recorded against the engine's existing
top-left compare, so flipping the global default is too risky in
the same change. Once S3K AIZ/CNZ progress past their current
blockers, S1/S2 should be re-recorded or re-validated and the
flag can be flipped to `true` for all three games.

### Implementation Status (2026-04-30)

The centre-Y rule landed on this branch:

- `PlayerMovementRules.levelBoundaryUsesCentreY` added,
  `SONIC_3K = true`, `SONIC_1`/`SONIC_2 = false` (deferred until
  S1 GHZ/MZ1 and S2 EHZ trace baselines are re-recorded — ROM
  cites for those games at `s1disasm/_incObj/01 Sonic.asm:1014`
  and `s2.asm:36950` are unambiguous, so the flip is mechanical
  once trace baselines confirm parity).
- `PlayableSpriteMovement.doLevelBoundary` (line ~1891) now
  switches between `sprite.getCentreY()` and `sprite.getY()` based
  on the flag.
- Regression coverage in
  `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovement.java`:
  `s3kBottomLevelBoundaryUsesCentreY` (kill fires when only
  centreY exceeds threshold),
  `s2BottomLevelBoundaryStaysOnTopLeftCompareUntilTraceRevalidation`,
  `s1BottomLevelBoundaryStaysOnTopLeftCompareUntilTraceRevalidation`
  (both confirm flag stays false and kill suppresses), and
  `s3kBottomLevelBoundaryRespectsTopLeftWhenCentreYBelowThreshold`
  (no false positives below threshold).

### Resolved — `Apparent_zone_and_act` resize gating

The miniboss-skip gate in `Sonic3kAIZEvents.updateAiz2SonicResize1`
and `updateAiz2KnuxResize1` has been corrected to read
`LevelManager.getApparentAct()` (mirroring ROM
`Apparent_zone_and_act`) instead of the legacy `enteredAsAct2`
boolean, which was set on every reload-resume that lacked a
queued fire continuation.  ROM cites:
`sonic3k.asm:39046-39058` (AIZ2_SonicResize1 apparent-act gate),
`sonic3k.asm:39157-39174` (AIZ2_KnuxResize1 same gate),
`sonic3k.asm:104627` (AIZ1_AIZ2_Transition does not write
`Apparent_zone_and_act`), `sonic3k.asm:10222` and `:61760`
(direct-entry / starpost-restore writes set it to `$0001`).
Regression coverage in `TestSonic3kAIZEvents`:
`aiz2FromFireTransitionDoesNotSkipMinibossPath` (existing) and
`aiz2ReloadResumeWithApparentAct0DoesNotSkipMinibossPath` (new).

### Residual blocker — AIZ2 miniboss-area geometry divergence at F7171

After the apparent-act gating fix, the AIZ trace still fails at
F7171 because of a downstream divergence in the miniboss-area
geometry, not in the resize gating itself.  Diagnostic capture
during replay:

- `Sonic3kAIZEvents.updateAiz2SonicResize1` correctly observes
  `apparentAct = 0` and routes into `SonicResize2` (no
  miniboss-skip).
- ROM at F7170 has Sonic at `x = 0x0F74`; the engine's Sonic
  stops advancing at `cameraX = 0x0E15 / spriteX = 0x0EB5`,
  ~0xC0 px short of the ROM trajectory and well below the
  `cameraX >= 0x0ED0` narrow trigger.
- Because the engine never reaches `cameraX >= 0x0ED0`,
  `Camera_max_Y_pos` stays at `AIZ2_DEFAULT_MAX_Y = 0x0590` and
  the centre-Y bottom-kill compare never fires; Tails is alive
  in the engine but dead in ROM at F7171, producing the
  cascading divergence.

The follow-up needs to find why the engine's Sonic and camera
stall around `cameraX = 0x0E15` while in the AIZ2 miniboss-area
approach.  Likely candidates: AIZ2 collision/layout differences
in the miniboss-arena entry, missing solid-floor geometry that
ROM uses to bridge the chasm before the miniboss spawns at
`Camera_X_pos >= 0x0F50` (`AIZ2_SonicResize2` lock-X), or a
delta in how the engine handles the miniboss-area approach
when the player has not yet defeated the miniboss.

Until that lands, the centre-Y change and the apparent-act
gating fix are both ROM-parity correct on their own; the AIZ
trace first-error frame remains at F7171 and is now blocked by
the geometry/collision divergence rather than by miss-gated
resize state.

### Update (2026-04-30) — Sonic stall resolved; F7171 advances to F4679

Subsequent diagnostic re-capture confirmed Sonic now reaches
the AIZ2 miniboss-area approach: at F7171 ROM cam=`0x0EDA` and
the engine cam matches.  Sonic position at F7170 also matches
ROM (`x = 0x0F74`).  The remaining F7171 mismatch was therefore
not "Sonic stalls"; instead the diff was Tails-only:

| Frame | tails_x | tails_y | xv | yv | gv | air |
|-------|---------|---------|----|----|----|-----|
| F7171 ROM | 0x0ECF | **0x0477** | **0x0000** | **-0x0700** | **0x0000** | **1** |
| F7171 ENG (post-y_vel-fix) | 0x0ED0 | 0x047E | 0x0000 | 0x0000 | 0x0000 | 1 |

Engine boundary check was firing the kill on the right frame,
zeroing x_vel/g_vel and setting Status_InAir, but
`SidekickCpuController.beginLevelBoundaryKill` was zeroing
y_vel as well.  ROM Kill_Character at sonic3k.asm:21149 writes
`y_vel = -$700`, not zero.  Because Tails_Check_Screen_Boundaries
reaches Kill_Character via `jmp` (sonic3k.asm:28443) and
Kill_Character ends with `rts` (sonic3k.asm:21159), control
unwinds to the *caller of* Tails_Check_Screen_Boundaries —
inside Tails_Stand_Path at sonic3k.asm:27526 the next call is
`MoveSprite_TestGravity2`, which falls through to MoveSprite2
(sonic3k.asm:36088,36053) and applies the freshly written
`y_vel = -$700` to `y_pos`.  That is the 7-pixel y-up
(`0x047E -> 0x0477`) recorded in the trace at F7171.

`SidekickCpuController.beginLevelBoundaryKill` now sets
`y_vel = -0x700` so the airborne movement manager's
SpeedToPos-equivalent (`modeNormal -> sprite.move`) replicates
the same in-frame 7-pixel shift.  The legacy
`TestSidekickCpuDespawnParity#levelBoundaryKillRunsTailsTouchFloorBeforeDeathState`
expected `y_vel = 0` post-kill; that expectation was calibrated
to the bug, so it is updated to `y_vel = -0x700` with the new
ROM cite block.

The AIZ trace first-error advances from F7171 to F4679,
inside the AIZ1→AIZ2 fire-transition window
(`cp aiz1_intro_refresh_begin`, cam ~`0x2D90`).  At F4679 ROM
records `tails_y = 0x040F` while the engine reports
`tails_y = 0x042F`.  The trace's `tailsAizBoundary` aux row
shows the boundary-check function clamped Tails to the right
edge (`x = 0x2D90`, `x_vel = 0`, `g_vel = 0`) without entering
the death path — so this is *not* a Kill_Character case; ROM
end-of-frame `y_vel = 0` is consistent with a non-kill flow.
Engine end-of-frame divergence (~32 px) at F4679 is therefore
a separate AIZ1 fire-transition physics gap (likely tied to
the bridge/tree object solidity or a secondary boundary
clamp) that the F7171 fix surfaces but does not address.

#### Residual blocker — AIZ1 left-boundary kill post-MoveSprite gap at F4679

`tailsAizBoundary cam=2D8B/4000 y=02E0/02E0 tree=2D41,0402,010F,0198->2D41,0402,010F,0198 boundary=kill 2D41,0402,010F,0198->2D90,0402,0000,0198 post=2D95,040F,0000,0000`

The earlier diagnosis ("32-pixel y-down gap, right-boundary
clamp") was partially incorrect.  Re-examination of the
recorder hooks (`tools/bizhawk/s3k_trace_recorder.lua` lines
2095-2103, 2152-2161) and ROM
`Tails_Check_Screen_Boundaries` (sonic3k.asm:28407-28451)
shows what actually happens at F4679:

1. Tails enters the boundary check at `(0x2D40, 0x0402)`,
   `vels = (0x010F, 0x0198)`.  `predictedX = x_pos + (x_vel<<8)`
   ≈ `0x2D42`.
2. ROM `Camera_min_X_pos` is `0x2D80` at this moment, so
   `Camera_min_X_pos + 0x10 = 0x2D90 > predictedX` and the
   `bhi.s loc_14F5C` branch (sonic3k.asm:28417) is taken.  The
   trace's recorded `cameraMinX = 0x2D8B` is the
   end-of-frame value (recorder calls `refresh_camera` at
   flush time, line 2133); the camera scrolled +11 px after
   the boundary check.
3. `loc_14F5C` (sonic3k.asm:28446-28451) clamps
   `x_pos = 0x2D90`, zeros x-subpixel, x_vel and ground_vel,
   then `bra.s loc_14F30` to the death-plane check.
4. `loc_14F30` reads `Camera_max_Y_pos = 0x02E0`, computes
   `0x02E0 + 0xE0 = 0x03C0`, and `cmp.w y_pos, d0 / blt.s`
   takes the branch to `loc_14F56` because `y_pos = 0x402 > 0x3C0`.
5. `jmp Kill_Character` runs.  Kill_Character writes
   `y_vel = -$700` (sonic3k.asm:21149), `x_vel = 0`,
   `ground_vel = 0`, then `rts`.  Because Kill_Character was
   reached via `jmp` (not `jsr`), the `rts` unwinds to the
   caller of Tails_Check_Screen_Boundaries, which for the
   airborne branch is `Tails_Stand_Freespace` at
   sonic3k.asm:27553-27567.  The next instruction is
   `jsr (MoveSprite_TestGravity).l` (sonic3k.asm:27559).
6. `MoveSprite_TestGravity` falls through to `MoveSprite`
   (sonic3k.asm:36032-36042) which adds gravity to y_vel and
   shifts `y_pos` by the **freshly written** `y_vel = -$700`
   (using the pre-gravity y_vel for position).  Tails moves
   up 7 px to `y = 0x3FB`.
7. `Player_JumpAngle` and `Tails_DoLevelCollision` run; the
   end-of-frame state recorded in the trace is
   `(0x2D95, 0x040F, vels=0)`.

Engine pre-fix path took the bottom-kill branch in
`PlayableSpriteMovement.modeAirborne` (line 446) and skipped
`doObjectMoveAndFall`, going straight from the kill writes to
`doLevelCollision`.  This left Tails at `y = 0x402` going into
collision (instead of `0x3FB` after the missing MoveSprite step)
and produced an end-of-frame `tails_y = 0x042F` — 32 px below ROM.

The pixel/iter-20 fix routes the kill path through
`doObjectMoveAndFall()` so the post-Kill_Character MoveSprite
step runs, mirroring ROM's
`Tails_Stand_Freespace -> MoveSprite_TestGravity` chain.  This
moves Tails up the missing 7 px and closes 16 px of the gap
(F4679 advances from `tails_y = 0x042F` to `tails_y = 0x041F`,
ROM expects `0x040F`).

The remaining 16-px gap is downstream and does not invalidate
the MoveSprite-step fix.  Likely sources still under
investigation:

- Engine `camera.getMinX()` at boundary-check time vs ROM
  `Camera_min_X_pos`.  AIZ1 fire-transition has the hollow-tree
  object writing camera bounds (`AizHollowTreeObjectInstance`
  CAMERA_RELEASE_MIN_X = 0x1300) and the AIZ1 dynamic resize
  (sonic3k.asm:38961-38974) writing `Camera_min_X_pos = 0x2D80`.
  Frame-order sensitivity here would change `leftBoundary` and
  thus the post-clamp x_pos that the ground sensor sees.
- `Tails_DoLevelCollision` ceiling-vs-floor dispatch under
  `y_vel < 0` (post-Kill_Character moving up).  ROM
  loc_15538 (ceiling path) walls + ceiling, no ground push;
  engine `resolveAirCollision` quadrant 0x80 routes to
  `doCeilingCollision` only.  If the engine's actual
  end-of-frame y_vel landed in a different quadrant a floor
  push could explain the residual.

A separate iteration is required to instrument the
camera-min-X timing and the post-kill collision quadrant to
finish closing F4679.

#### Resolution — `applyKillCharacterTouchFloorReset` y_pos delta computed from full height instead of y_radius

The residual 16-px gap at F4679 was traced (2026-04-30 iter-22)
to `SidekickCpuController.applyKillCharacterTouchFloorReset`.
When the rolling branch fires, it adjusts the sidekick's
centre-Y by `delta` to mirror ROM `Tails_TouchFloor`'s
`add.w d0, y_pos(a0)` step.  ROM computes
`d0 = old_y_radius - default_y_radius` (sonic3k.asm:29134-29141),
which for Tails roll->stand is `14 - 15 = -1` px.

The engine instead read
`delta = sidekick.getHeight() - sidekick.getStandYRadius()`,
which for the same roll->stand transition returns
`28 - 15 = +13` px (full visual height vs radius), shifting
Tails's centre-Y +13 in the wrong direction whenever the
sidekick was rolling at the moment of the kill.  Combined
with the +1 px from the height-based `getCentreY()` jump
inside `setRolling(false)`, the engine ended F4679 with
`tails_y = 0x041F` versus ROM `0x040F` (16-px gap).

The fix replaces the buggy expression with
`sidekick.getYRadius() - sidekick.getStandYRadius()`.  After
the fix the AIZ trace first strict error advances from
F4679 to F7171 (1050 -> 1049 errors).  Engine state at
F4679 now matches ROM: `tails_y = 0x040F`, `vels = 0`,
`x = 0x2D95`.

ROM cite block (sonic3k.asm:29133-29156, `Tails_TouchFloor`):

```
Tails_TouchFloor:
    move.b  y_radius(a0),d0          ; d0 = OLD y_radius
    move.b  default_y_radius(a0),y_radius(a0)
    btst    #Status_Roll,status(a0)
    beq.s   loc_1565E                ; not rolling -> skip y_pos delta
    bclr    #Status_Roll,status(a0)
    ...
    sub.b   default_y_radius(a0),d0  ; d0 = old_y_radius - default_y_radius
    ext.w   d0
    ...
    add.w   d0,y_pos(a0)             ; y_pos += d0 (sign-flipped by angle)
```

Source: `SidekickCpuController.applyKillCharacterTouchFloorReset`
(commit `bugfix/ai-aiz-f4679-residual-16px`).

#### Resolution — `AIZ2_SonicResize2` one-frame ordering + post-`sub_13ECA` MoveSprite step

The F7171 mismatch had two compounding causes that landed
together on this iteration:

1. **`AIZ2_SonicResize2` ran one frame late.**  ROM
   `Do_ResizeEvents` runs *inside* `DeformBgLayer`
   (sonic3k.asm:38303-38316) **after** `MoveCameraX` has
   committed the new `Camera_X_pos`.  Engine
   `LevelFrameStep` runs events (step 4) **before** the
   camera step (step 5), so `camera().getX()` returns the
   previous frame's value.  At F7170, ROM saw cam=`0xED4 ≥
   0xED0` and narrowed `Camera_max_Y_pos` to `$2B8` (the
   miniboss-area cap).  The engine's `updateAiz2SonicResize2`
   read cam=`0xECE` (end-of-F7169) and skipped the narrow,
   so F7171's `Tails_Check_Screen_Boundaries` (sonic3k.asm:
   28428-28443) saw the still-default `$590` cap, missed
   the kill plane, and Tails kept following Sonic instead
   of dying.  Fix: read `camera().previewNextX() & 0xFFFF`
   in `updateAiz2SonicResize2` so the check sees the same
   `Camera_X_pos` the next frame's player physics will see,
   matching the ROM call order.  This mirrors the prior
   AIZ1 fix at line ~515 in `Sonic3kAIZEvents.updateAct1`
   (CHANGELOG entry "AIZ1 dynamic-resize one-frame ordering
   fix").  Other `updateAiz2SonicResizeN` /
   `updateAiz2KnuxResizeN` routines retained
   `camera().getX()` to keep `TestSonic3kAIZEvents` passing
   (those tests directly poke the camera and rely on the
   raw value).

2. **`updateDeadFalling` overwrote the preserved
   `Kill_Character` y-velocity.**  ROM Frame N+1 enters the
   death routine `loc_157C8` (sonic3k.asm:29283), calls
   `sub_123C2` → `sub_13ECA` (sonic3k.asm:26800-26809) which
   warps `x_pos = $7F00, y_pos = 0` and crucially does **not**
   touch `y_vel`, then returns to `loc_157C8` and runs
   `MoveSprite_TestGravity` (sonic3k.asm:29285) which falls
   through to `MoveSprite` (sonic3k.asm:36032-36042).
   `MoveSprite` shifts `y_pos` by the still-preserved
   `y_vel = -$700` *before* the `+$38` gravity write,
   producing the trace's `(y = -$0007, y_vel = -$06C8)` at
   F7172.  The engine's `applyDespawnMarker` sets
   `objectControlled = true`, which flips
   `objectControlSuppressesMovement` (see
   `AbstractPlayableSprite.setObjectControlled`) and
   short-circuits the regular `PlayableSpriteMovement` path,
   so the post-warp `MoveSprite` step never ran.
   `updateDeadFalling` was instead writing
   `setYSpeed(0x38)` straight, calibrated to a different
   ROM scenario where `y_vel` happened to enter sub_13ECA
   as `0`.  Fix: capture `oldYSpeed` (from the preserved
   Kill_Character `-$700`), call `applyDespawnMarker()` to
   warp x/y/state, then apply the inlined
   `MoveSprite`-equivalent — `y_pos += oldYSpeed >> 8` and
   `y_vel = oldYSpeed + $38`.  This produces the F7172
   `(y = -7, y_vel = -$06C8)` end-of-frame state ROM records.

After both fixes the AIZ trace first strict error advances
from F7171 to F7235 (1049 → 1044 errors, F7172-F7234 all
green).  F7235 is a Sonic-side rolling `g_speed` divergence
(`g_speed = 0x0768` engine vs `0x0800` ROM at the rolling
top-speed cap) that is independent of the kill-plane chain
and out of scope for this iteration.

ROM cite block (sonic3k.asm:36032-36042, `MoveSprite`):

```
MoveSprite:
    move.w  x_vel(a0),d0
    ext.l   d0
    lsl.l   #8,d0
    add.l   d0,x_pos(a0)
    move.w  y_vel(a0),d0     ; OLD y_vel for position
    addi.w  #$38,y_vel(a0)   ; gravity AFTER capturing OLD y_vel
    ext.l   d0
    lsl.l   #8,d0
    add.l   d0,y_pos(a0)     ; y_pos += OLD y_vel >> 8
    rts
```

Sources: `Sonic3kAIZEvents.updateAiz2SonicResize2`,
`SidekickCpuController.updateDeadFalling` (commit
`bugfix/ai-aiz-f7171-leader-fast-redux`).

## CNZ1 Trace F7872 — Tails 1-Pixel x Drift After Sonic Jumps Off Rising Platform (OPEN)

**Status:** Investigated, cause identified, defer until OnObj clear timing is reworked.

**Symptom**

`TestS3kCnzTraceReplay#replayMatchesTrace` first strict error at frame 7872:
```
tails_x mismatch (expected=0x0CED, actual=0x0CEC) — 2774 errors total
```

The divergence is a single-pixel offset on Tails's x-position that propagates
forward (every subsequent `tails_x` is 1 px below the expected value). All
other Tails state (`tails_y`, `tails_x_speed`, `tails_y_speed`,
`tails_g_speed`, `tails_air`, `tails_rolling`) tracks ROM exactly through the
divergence window. The 1-pixel offset is small but downstream cascades make
the rest of the trace fail.

**Trigger frame context (F7872)**

Sonic was standing on the CNZ rising platform (`onObj=04` =
`CnzRisingPlatformInstance`) and jumped this frame:
- ROM `state_snapshot` records `air 0->1`, `rolling 0->1`,
  `on_object 1->0` for Sonic at vfc=7873.
- ROM `tailsCpu ... branch=leader_on_object` (Tails CPU saw Sonic still
  carrying `Status_OnObj` mid-frame).
- Engine `eng-tails-cpu ... branch=follow_steering` (Tails CPU saw Sonic's
  `isOnObject()` already false).

**Root cause**

ROM `Tails_CPU_Control` at `loc_13DA6` (sonic3k.asm:26688-26700) reads Sonic's
**current** `Status_OnObj` mid-frame:

```
loc_13DA6:
    lea     (Pos_table).w,a2
    move.w  #$10,d1
    lsl.b   #2,d1
    addq.b  #4,d1
    move.w  (Pos_table_index).w,d0
    sub.b   d1,d0
    move.w  (a2,d0.w),d2
    btst    #Status_OnObj,status(a1)   ; <-- current Sonic.Status_OnObj
    bne.s   loc_13DD0                  ; if set: skip subi.w #$20,d2
    cmpi.w  #$400,ground_vel(a1)
    bge.s   loc_13DD0
    subi.w  #$20,d2                    ; bias target X by 0x20 left
loc_13DD0:
    ...
```

ROM `Sonic_Jump` (sonic3k.asm:23288-23354, mirrored in s2.asm:37027-37080
and s1disasm/_incObj/01 Sonic.asm:1118-1167) sets `Status_InAir` and clears
`Status_Push`, but **does not** clear `Status_OnObj`. The OnObj bit is
cleared later in the frame, during object processing, by
`sub_1FF1E` (sonic3k.asm:44306-44319) and `loc_1FFC4` (sonic3k.asm:
44369-44381) when the platform that previously hosted Sonic detects he is
no longer standing on it (`bclr p1_standing_bit,status(a0)` followed by
`bclr Status_OnObj,(Player_1+status).w` when that bit was set). Because
ROM runs Player_1 → Player_2 → ObjectsLoad in that order
(sonic3k.asm:21626+ vs. 22300+ vs. 24450+), `Tails_CPU_Control` (called
from Player_2's update) sees Status_OnObj still set on the jump frame.

The engine clears OnObj at two earlier points:
- `PlayableSpriteMovement.doJump` calls `sprite.setOnObject(false)` at
  jump time (current line 642).
- `ObjectManager.SolidContacts.processInlineObjectForPlayer` air-unseat
  path calls `player.setOnObject(false)` whenever the player is airborne
  with a riding-object association (current line 4536).

Both run inside Sonic's player tick, so by the time `SidekickCpuController.
normalStep` runs (called from Tails's player iteration via
`SpriteManager.update` at line 349), `effectiveLeader.isOnObject()` already
returns `false` and `leadOffset > 0` causes `targetX -= 0x20` to fire.
That is the source of the 1-pixel offset: `subi.w #$20,d2` runs in the
engine but is skipped in ROM, so `dx = targetX - sidekick.getCentreX()` is
biased by 0x20 (0.125 px after the tracking gain), shifting Tails's
follow-steering nudge by 1 pixel west across the next several frames.

**Investigation history (this branch — `bugfix/ai-cnz-f7872-tails-x`)**

Two surgical fix attempts were tried and reverted:

1. **Remove `setOnObject(false)` from `doJump`.** Revealed that
   `ObjectManager.processInlineObjectForPlayer`'s air-unseat path
   (line 4520-4537) **also** clears OnObj as soon as Sonic.air becomes
   true, so removing the doJump line alone does not preserve OnObj
   through Tails CPU.
2. **Read Sonic's previous-frame status snapshot via
   `getStatusHistory(1)`.** Successfully shifts the F7872 branch from
   `follow_steering` to `leader_on_object`, **but** regresses CNZ1 to a
   new first error at F6409: ROM has Sonic airborne with OnObj=false at
   end of F6408, but the engine had recorded OnObj=true in the same
   slot, so `getStatusHistory(1)` returns true while ROM's CURRENT bit is
   false (Sonic was on the giant wheel; engine's clear timing for the
   wheel differs from ROM's). The off-by-one in OnObj timing exists
   across many objects, not just Cnz Rising Platform.

A third option — adding an OR fallback (`previousOnObject ||
leader.isOnObject()`) — also regressed F6409 because the previous-frame
read still mismatched ROM's state.

**Why no clean fix lands here**

Aligning the engine to ROM requires either:

- **(A)** Defer `setOnObject(false)` for *every* path that currently
  clears it during the player tick (doJump, SolidContacts air-unseat,
  per-object overrides) until the object phase, OR
- **(B)** Capture a frame-start `wasOnObjectAtPlayerTickStart` snapshot
  that is invalidated only by the object phase, not by the player tick.

(A) is invasive — touching every solid-object path and risking regressions
in other tests where engine code reads `isOnObject()` mid-tick (e.g.
`PlayableSpriteMovement.java:1984, 2510, 2807` and
`SidekickCpuController.java:1918, 1953`).
(B) is cleaner architecturally, but adding a new shadow flag and
threading it through `AbstractPlayableSprite.endOfTick` plus
`SpriteManager.beginPlayableFrame` is not a small surgical edit; it
needs cross-game (S1/S2/S3K) test coverage to avoid silently breaking
the existing leader-on-object branches in those games (S2 follow code
also reads `Status_OnObj` from the leader at `s2.asm:38933`+).

**Update (branch `bugfix/ai-on-object-at-frame-start`):** The
infrastructure half of option **(B)** has landed: a new
`onObjectAtFrameStart` field on `AbstractPlayableSprite` plus
`captureOnObjectAtFrameStart()` / `getOnObjectAtFrameStart()`
accessors, with the capture wired into all three
`SpriteManager.beginPlayableFrame` call sites
(`update`, `updateWithoutInput`, `warmUpCpuSidekicksOnly`) so every
playable sees its frame-start OnObj snapshotted before any player
tick can mutate it. The accessors are covered by
`TestOnObjectAtFrameStartSnapshot`. **However the snapshot is not yet
plumbed into `SidekickCpuController.normalStep`** because swapping it
in for the existing
`isOnObject() && !getAir()` gate uncovered a deeper engine-side OnObj
divergence that the snapshot alone cannot fix:

- Dropping the `&& !getAir()` filter and using only the snapshot at
  the line 812 / 1058 / 1119 gates DOES correctly flip CNZ F7872
  from engine `follow_steering` to the ROM-matching
  `leader_on_object` branch — F7872 advances to F7919.
- BUT it regresses AIZ1 to a new first error at F2021 (was F7381):
  Tails ends up 1 px more EAST than ROM. At F2021, ROM Sonic has
  `Status_OnObj=0` mid-frame (lua records `branch=fallthrough_sub20`)
  but the engine's frame-start snapshot reads OnObj=true, because
  the engine had `onObject=true` set at the END of F2020 while ROM
  had cleared it. So engine and ROM disagree about the frame-start
  OnObj value itself — the snapshot accurately captures the engine's
  state, but the engine's state diverged from ROM at F2020-end.

This is a different layer of the same bug: engine paths that SET or
CLEAR OnObj at object boundaries don't match ROM's
`sub_1FF1E`/`loc_1FFC4`/`SolidObjectTop` timing, so even a perfect
mid-frame snapshot inherits that earlier drift. Fully closing the
gap therefore needs option (A) — aligning the engine's OnObj clear
timing with ROM's object-phase processing — or a more selective
combination (e.g. snapshot only when the engine and ROM agree at the
frame boundary, fall back to live read otherwise). Both require
deeper investigation than this branch carried.

The snapshot infrastructure is left in place as the foundation for
the eventual option (B) wiring; until then `SidekickCpuController`
keeps its existing `isOnObject() && !getAir()` heuristic and
documents the unresolved root cause (engine OnObj clear/set timing
vs ROM object-phase processing) inline at the gate.

**Removal condition**

Either:

- A new `AbstractPlayableSprite.wasOnObjectAtFrameStart` snapshot is
  added, set by `beginPlayableFrame` from the prior frame's recorded
  state, and `SidekickCpuController` reads it for `loc_13DA6`'s
  Status_OnObj test in place of `effectiveLeader.isOnObject()`. S1/S2
  trace replay tests must also remain green, since the same
  pattern exists in `s1disasm/_incObj/01 Sonic.asm:1118-1167` and
  `s2.asm:37027-37080,38933+`.
- Or: every engine-side path that clears OnObj during the player tick
  is rerouted to defer the clear into the object phase, matching ROM
  ordering.

Either change must keep `TestS3kCnzTraceReplay` (and AIZ/S1/S2 trace
replays) green throughout.

**File pointers (engine state today, master branch)**

- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
  line 631-660 — `doJump` clears OnObj at jump time.
- `src/main/java/com/openggf/level/objects/ObjectManager.java`
  line 4502-4537 — `processInlineObjectForPlayer` air-unseat path
  clears OnObj as soon as the player becomes airborne while a
  ridingState is held.
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
  line 812 — leader_on_object check uses
  `effectiveLeader.isOnObject() && !effectiveLeader.getAir()`. The
  `&& !getAir()` half is also redundant compared to ROM
  (sonic3k.asm:26690 reads Status_OnObj alone) and should be revisited
  alongside option (A) or (B).

**Sources:** `bugfix/ai-cnz-f7872-tails-x` investigation notes (this
branch's commit body); `target/trace-reports/s3k_cnz1_context.txt`
F7860-F7880; `src/test/resources/traces/s3k/cnz/aux_state.jsonl.gz`
F6408-F7873.

**Update (branch `bugfix/ai-onobj-clear-timing-alignment`):** Wired the
existing frame-start snapshot into the three `loc_13DA6` mirror gates
(`SidekickCpuController.normalStep`, `resolveFollowSteeringDx`,
`resolveObjectOrderNudgeDx`) using
`effectiveLeader.getOnObjectAtFrameStart()` (no `&& !getAir()` filter,
to match ROM `btst #Status_OnObj,status(a1) / bne loc_13DD0` which tests
OnObj alone — `sonic3k.asm:26690-26691`). Result confirms the deeper
clear/set-timing divergence:

- **CNZ first-error advances F7872 -> F7919.** F7872 (Sonic-jumps-off-
  rising-platform) now correctly takes ROM's `leader_on_object` branch.
  F7919 is a NEW, distinct downstream divergence: `tails_g_speed
  expected=-0x588 actual=-0x800`, `tails_y_speed expected=0x400
  actual=-0x800`, `tails_x_speed expected=0x004 actual=-0x800`. All
  three velocities pinned to `-0x800` is the spring impulse magnitude
  used by `Obj81_Spring`/`Obj_VertSpring` family (sonic3k.asm:46850+).
  Tails is hitting a spring 47 frames later that ROM does not. Out of
  scope for OnObj timing — separate bug to investigate.
- **AIZ regresses F7381 -> F2021** (`tails_x expected=0x1967
  actual=0x1968`, Tails 1 px EAST of ROM). Reproduces the prior round's
  observation. ROM trace data at F2000-F2025
  (`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/physics.csv.gz`):
  Sonic `status_byte=0x06` (InAir|Roll, OnObj=0) for F2000-F2019, then
  `0x03` (Facing_left|InAir, OnObj=0) F2020+. Sonic is **never** on an
  object across this airborne-roll-then-uncurl window — `Status_OnObj=0`
  throughout. ROM `tails_cpu_normal_step` events at F2020/F2021 record
  `delayed_stat=0x06`, `loc_13dd0_branch=fallthrough_sub20` — i.e.
  Tails CPU takes the `subi.w #$20,d2` bias path. The engine's
  `getOnObjectAtFrameStart()` returns `true` at F2021, meaning the
  engine **set or kept** Sonic's `onObject` true somewhere during the
  airborne F2000-F2020 window when ROM had it cleared. Engine's
  `setOnObject(true)` call sites in `ObjectManager.java` (lines 5701,
  5821, 5910, 6009, 6193) all fire from
  `MvSonicOnPtfm`-equivalent landing branches; ROM gates these with
  `btst #Status_InAir,status(a1) / bne <air-unseat>` (sonic3k.asm:
  41021-41031, 41070-41084, 41117-41128, 41798-41812). The candidate
  divergence is one of those `apply` blocks running for an airborne
  Sonic, but pinpointing which object/path requires diagnostic
  capture across the window — too large for this branch.

**Status this branch:** the clean fix does not land. The wiring change
is **not committed** because it regresses AIZ-full. The deeper engine
OnObj set/clear alignment with ROM's `SolidObjectFull*_1P` /
`SolidObjectTop*_1P` air-unseat gating remains the prerequisite. Next
round needs to identify the AIZ1 F2000-F2020 object whose engine-side
`apply` path sets `onObject=true` for an airborne Sonic, gate it on
`!player.getAir()` (matching ROM's `btst #Status_InAir`), and then
re-attempt the snapshot wiring.

**Update (branch `bugfix/ai-onobj-air-gate`):** Landed both pieces.
`ObjectManager.processInlineObjectForPlayer` now early-returns
(`return null`) after the air-unseat clear when
`instance == unseatedRidingObject`, mirroring ROM
`SolidObjectFull*_1P` / `SolidObjectTop*_1P` `rts d4=0` exit
(sonic3k.asm:41030-41035 `loc_1DC98`, 41079-41084 `loc_1DCF0`,
41126-41131 `loc_1DD48`, 41807-41812 `loc_1E2E0`). The early-return is
scoped strictly to the just-unseated instance so unrelated objects on
the same frame still resolve normally — the broader
`hasObjectStandingBit && air` gate over-fires for S2 EHZ1 platforms
(regressed F1698 with the wider scope, restored to PASS with the
narrow scope). With the early-return in place, the
`SidekickCpuController.normalStep` `loc_13DA6` mirror gates (lines
832, 1078, 1139) now read
`effectiveLeader.getOnObjectAtFrameStart()`, dropping the
`&& !getAir()` filter that was masking the snapshot-vs-live divergence
(ROM `btst #Status_OnObj` at sonic3k.asm:26690 has no air filter).

- **CNZ first-error advances F7872 -> F7919** as predicted. F7919 is
  the documented downstream divergence (`tails_g_speed=-0x800` spring
  impulse 47 frames later) and is out of scope here.
- **AIZ first-error regresses F7381 -> F2021** (`tails_x` 1 px EAST,
  1040 errors vs prior 1039). This is the same pre-existing engine
  OnObj clear-timing gap documented above: ROM trace
  (`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/physics.csv.gz`)
  still shows Sonic `Status_OnObj=0` across F2000-F2020 while the
  engine's frame-start OnObj snapshot returns `true` at F2021. The
  early-return only mirrors per-frame air-unseat behaviour for the
  CURRENTLY ridden instance; it cannot recover OnObj state that was
  set in a prior frame and never cleared. Resolving AIZ F2021 still
  needs the cross-frame engine OnObj clear-timing audit (find the
  F2000-F2020 path that leaves OnObj set when ROM has it cleared)
  — out of scope for this branch.
- **Cross-game parity preserved:** S1 GHZ1, S2 EHZ1 trace replays
  remain green; required-green S3K bootstrap tests still pass.
- **Suite-wide failures: 34 -> 31** (3 fewer, no new regressions
  outside the AIZ first-error frame shift).

**Update (branch `bugfix/ai-cross-frame-onobj-timing`):** Resolved.
Identified the cross-frame OnObj clear-timing gap as a missing
`bclr Status_OnObj` on the spring trigger sub-routines. The engine
applied `setAir(true)` but never cleared `onObject`,
diverging from ROM where `sub_22F98` (sonic3k.asm:47723-47724),
`sub_233CA` (sonic3k.asm:48139-48140), and `sub_234E6`
(sonic3k.asm:48213-48214) explicitly clear `Status_OnObj` after
setting `Status_InAir`. Same pattern in S2 (s2.asm:33732-33733)
and S1 (`_incObj/41 Springs.asm:88-89,183-184`). Fixes landed in
`Sonic3kSpringObjectInstance.applyUpSpring/applyDownSpring/applyDiagonalSpring`,
`SpringObjectInstance.applyUpSpring/applyDownSpring/applyDiagonalSpring`,
`Sonic1SpringObjectInstance.applyUpSpring/applyDownSpring`. With the
clear in place, the frame-start OnObj snapshot now reflects ROM's
mid-frame view across the airborne window, so wiring
`SidekickCpuController.normalStep` / `resolveFollowSteeringDx`
/ `resolveObjectOrderNudgeDx` to read
`effectiveLeader.getOnObjectAtFrameStart()` (no air filter, since
ROM `btst #Status_OnObj` at sonic3k.asm:26690 has no air gate)
no longer regresses AIZ.

- **CNZ first-error advances F7872 -> F7919** (2774 -> 2768 errors).
  F7919 is the documented downstream divergence
  (`tails_g_speed=-0x800` spring impulse 47 frames later) -- out of
  scope.
- **AIZ first-error stays at F7381** (1039 errors) -- no regression.
- **Cross-game parity preserved:** S1 GHZ1, S1 MZ1, S2 EHZ1 trace
  replays remain green; required-green S3K bootstrap tests
  (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`)
  still pass.
- **Suite-wide failures: 30 -> 30** (no new regressions, same total).
- Regression tests added in `TestSonic3kSpringObjectInstance`:
  `upSpringClearsStatusOnObjAfterSettingAir`,
  `downSpringClearsStatusOnObjAfterSettingAir`,
  `upDiagonalSpringClearsStatusOnObjAfterSettingAir`.

---

## AIZ2 Trace F7381 — Engine `Ctrl_1_logical` Not Latched While ROM `Ctrl_1_locked=1` (OPEN — next AIZ blocker)

**Status:** Investigated, root cause identified, structural fix deferred.

**Branch:** `bugfix/ai-aiz-f7381-tails-west-bias`

**Location:**
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java:404-416`
  (player-controlled effective-input gating)
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:3706`
  (`endOfTick()` writes `inputHistory[hpos] = logicalInputState`)
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:2494-2506`
  (`setLogicalInputState`/`clearLogicalInputState`)
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java:795-855`
  (Tails CPU consumer of the leader's `inputHistory`/`statusHistory`)

### Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` first strict error at frame 7381:
```
tails_x_speed mismatch (expected=0x0000, actual=-0018) — 1039 errors total
```

ROM has Tails stationary in air with `x_vel=0`; the engine has Tails drifting
west by `-0x18` per frame (one `Tails_InputAcceleration_Freespace` left-input
acceleration step, `2 * Acceleration_P2` = `2 * $0C = $18`,
sonic3k.asm:28330-28348).

### Trigger Frame Context (F7381)

ROM `tails_cpu_normal_step` aux event:
```
status=0x02 ground_vel=0x0000 x_vel=0x0000
delayed_stat=0x00 delayed_input=0x0000
loc_13dd0_branch=fallthrough_sub20
ctrl2_logical=0x0000 ctrl2_held_logical=0x00
path_pre_x_vel=0x0000 path_post_x_vel=0x0000
```

Engine `eng-tails-cpu` diagnostic at the same frame:
```
state=NORMAL branch=follow_steering hist=16/26
in=0004 stat=07 push=07
pre=obj00 st02 xv0000 gv0000
gen=0004 (= INPUT_LEFT)
postCpu=obj00 st02 xv0000 gv0000
postPhys=seen obj00 st03 xvFFE8 gv0000  dx=FFE0 dy=0000
```

Both branches reach the same ROM control-flow (`fallthrough_sub20` =
"`loc_13DA6` applied `subi.w #$20,d2`"; engine `follow_steering` = none of
`current_push_bypass`/`grace_push_bypass`/`airborne_push_handoff`/
`leader_on_object`/`leader_fast` fired). The divergence is upstream of
`loc_13DA6`/`loc_13DD0`: ROM reads `delayed_input=0x0000` from `Stat_table`
slot `Pos_table_index-$44` (16 frames back, F7365), engine reads
`recordedInput=0x0004` (`INPUT_LEFT`) from `inputHistory[hpos-16]` for the
same frame.

### Root Cause

ROM `Player_1` body at `loc_10760` (sonic3k.asm:21539-21545):
```
loc_10760:
    cmpa.w  #Player_1,a0
    bne.s   loc_10774
    tst.b   (Ctrl_1_locked).w
    bne.s   loc_10780                ; if locked, SKIP the Ctrl_1_logical write
    move.w  (Ctrl_1).w,(Ctrl_1_logical).w
```

When `Ctrl_1_locked=1`, ROM does **not** copy `Ctrl_1` (raw held buttons)
into `Ctrl_1_logical`; the previous frame's value persists. Sonic_RecordPos
later writes `Ctrl_1_logical` into `Stat_table` (sonic3k.asm:22132), so the
recorded delayed input slot reflects the **latched** logical input, not the
raw physical button state.

At F7365 the BizHawk movie has `LEFT` held (BK2 input log, line `7365 + 511
+ 2 = 7878`: `..L.....`), but ROM has `Ctrl_1_locked=1` and
`Ctrl_1_logical=0x0000`, so the `Stat_table` slot holds `0x0000`.

The engine **never** sets `controlLocked=true` for any S3K in-level event.
A search across `src/main/java/com/openggf/game/sonic3k` for
`setControlLocked(true)` returns zero hits (S1/S2 zone code uses it
for flippers, spin tubes, signposts, bosses, and ending sequences, but
S3K equivalents are not yet plumbed). Consequently
`SpriteManager.publishInputState` line 405 evaluates
`effectiveLeft = (!controlLocked && left) || forcedLeft` with
`controlLocked=false` and `left=true`, sets
`logicalInputState = INPUT_LEFT`, and `endOfTick()` writes that into
`inputHistory[hpos]`.

`SidekickCpuController.normalStep` reads
`effectiveLeader.getInputHistory(followStatDelayFrames=16)` at
SidekickCpuController.java:795 and gets `INPUT_LEFT`. Lines 851-855 seed
`inputLeft = true` directly from `recordedInput`, bypassing the
follow-steering snap threshold (which would otherwise gate a left-input
on `|dx| >= 0x30`; engine's `dx = FFE0` has `|dx| = 0x20 < 0x30`).

The `inputLeft=true` flows into Tails's
`Tails_InputAcceleration_Freespace` mirror (the path runs because
`status=0x02` = airborne, `roll-jump` not set), which executes
`x_vel -= d5 = 2 * Acceleration_P2 = 2 * $0C = $18`
(sonic3k.asm:28336-28348). ROM has `Ctrl_2_held_logical=0x00` for the
same frame (the recorded `tails_cpu_normal_step` confirms), so ROM's
freespace path does nothing. Net divergence: `tails_x_speed = -0x18`.

### Why this is broader than F7381

The same mechanism produces the residual `tails_x_speed` drift documented
in subsequent table rows (F7382 `expected=-0x18 actual=-0x30`, F7383
`expected=-0x30 actual=-0x48`, ...). Each frame Tails compounds the
`-0x18` increment because the engine keeps reading non-zero
`recordedInput`/`recordedStatus` slots that ROM zeroes via
`Ctrl_1_locked`. Once Sonic regains control (movie input changes,
`Ctrl_1_locked` clears in ROM), the histories realign, but by then
Tails has accumulated multi-pixel positional drift.

The same mismatch is the proximate cause of **AIZ F4679** (sidekick
despawn velocity divergence — see entry above), CNZ1 spring-launch
control-lock windows, and any sequence where a horizontal spring,
slope-repel, ride object, or zone transition latches Sonic's input in
ROM but not in the engine.

### Why no clean fix lands here

A surgical fix at this trace frame requires identifying the specific
ROM site that sets `Ctrl_1_locked=1` for the F7361-F7365 window. ROM
sets `Ctrl_1_locked` from at least the following S3K code paths:
- `move.b #1,(Ctrl_1_locked).w` at sonic3k.asm:7774 (level start
  initialisation in `loc_637E`).
- The various `Player_SlopeRepel`/`move_lock` interactions
  (sonic3k.asm:23911-23948) and `Sonic_Hurt` paths, plus several
  spring objects and the AIZ swing-vine handoff (slot 7
  `Obj=0x00068A24` near (`0x11F0,0x0289`) is active in the F7350-F7365
  window per `aux_state.jsonl.gz`).
- Object-driven captures (rope grab, vine swing, mid-air seizure) call
  `clr.b (Ctrl_1_locked).w` on release; the engine's S3K object code
  does not have matching `setControlLocked(true)` calls.

The proper fix has two parts:

1. **Audit S3K objects/events that should latch `Ctrl_1_locked`.** Each
   needs `player.setControlLocked(true)` mirroring the ROM site, plus
   a release path mirroring the corresponding `clr.b (Ctrl_1_locked).w`.
   The candidate object set includes (but is not limited to)
   `Sonic3kSpringObjectInstance`, `AizVineHandleLogic`, swing-vine,
   bumpers, monkey-tail handoff, and the AIZ act-2 transition flush.

2. **Cross-game audit.** S2 has the same model
   (`s2.asm:21670+` reads `Ctrl_1_locked`), but its trace coverage
   does not currently expose this gap because EHZ1 has no spring/vine
   capture window. Adding a `controlLocked` audit also benefits S2,
   though S1 has a separate `Ctrl_Lock_byte` model
   (`s1.asm:player.asm`) and its UNIFIED collision model means most
   captures clear input differently.

A blanket "always preserve `logicalInputState` when `controlLocked` is
set" change in `SpriteManager.publishInputState` is **not** a valid
short-circuit: it would invert the current S2 `FlipperObjectInstance`
behaviour (which sets `controlLocked=true` and *expects* the input
field to reflect the post-lock zero state for animation gating), and
S1 spin-tube paths that also set `setControlLocked(true)`. The
"persistence on lock" semantic only matches ROM when the engine
actually enters `controlLocked` at the same ROM-cited site.

### Diagnosed Constraints

- `inputHistory[]` writes happen unconditionally in `endOfTick()`
  (AbstractPlayableSprite.java:3706); to track ROM, the **value
  written** must be the ROM-equivalent `Ctrl_1_logical`, not the
  fresh effective input.
- `Sidekick CPU` consumes the history at delays `$44/4 = 17` and
  `$40/4 = 16` frames back (sonic3k.asm:26683-26689 and
  s2.asm:38927-38932). Both reads must observe the same
  `Ctrl_1_logical`-equivalent value.
- `SidekickCpuRules` already gates the `loc_13DA6` lead offset
  via `sidekickFollowLeadOffset()`; a second flag for "preserve
  logical input across `controlLocked`" would be redundant once
  controlLocked is wired correctly.
- Cross-game parity: any change to
  `setLogicalInputState`/`endOfTick`/`publishInputState` MUST keep
  S1 GHZ1, S1 MZ1, S2 EHZ1 trace replays green and the four
  required S3K bootstrap tests
  (`TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`) passing.

### Removal Condition

Either:

- **(A)** S3K horizontal-spring/AIZ-vine/etc. instances are wired
  to `setControlLocked(true)` at the ROM-equivalent points, with
  the corresponding release sites clearing it; the engine's
  `inputHistory` then naturally records `0x0000` at F7365 because
  `effectiveLeft = (!true && true) || false = false`. Or

- **(B)** A new "`logicalInputLatched`" semantic is added that
  preserves the prior `logicalInputState` when `controlLocked` is
  set (mirroring ROM's `tst.b Ctrl_1_locked; bne loc_10780`
  short-circuit at sonic3k.asm:21542-21544), and option (A) is
  applied incrementally per zone. Either way, AIZ F7381's first
  error must advance past F7381, CNZ first error must not regress
  past F7919, and S1/S2 trace replays must stay green.

### Diagnostic Confirmation Plan

The aux trace already exposes everything needed for verification:

- `aux_state.jsonl.gz` `tails_cpu_normal_step` event includes
  `delayed_input` and `delayed_stat`. Compare against the engine's
  `eng-tails-cpu in=XXXX stat=XX` diagnostic — they must match
  bit-for-bit at every frame Tails CPU runs.
- The `state_snapshot` event already records `control_locked` for
  ROM (the per-sprite `move_lock` counter at $FFFFB032), which is
  NOT the same as the global `Ctrl_1_locked` byte at $FFFFF7CA.
- Recorder v6.12-s3k adds a new `control_lock_state_per_frame`
  aux event that captures the global $FFFFF7CA / $FFFFF7CB bytes
  plus `Ctrl_1_logical` / `Ctrl_2_logical` ($FFFFF602 / $FFFFF66A)
  per frame on every transition (and a baseline every 60 frames).
  This was added specifically to enumerate the ROM frames where
  `Ctrl_1_locked=1` so a `TestSonic3kCtrlLockParity` can assert
  the engine's `controlLocked` matches.

### Update 2026-04-30: Recorder v6.12 Refutes the Ctrl_1_locked Hypothesis

After regenerating `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`
with the new `control_lock_state_per_frame` event,
`Ctrl_1_locked` is **0 for the entire AIZ F7361-F7385 window**. The
only frames in the 20798-frame fixture where `Ctrl_1_locked` is set
are F20298+ (well past AIZ). Concretely, around the trigger window:

```
{"frame":7353,"ctrl1_locked":0,"ctrl1_logical":"0x4440"}  # LEFT+B held
{"frame":7354,"ctrl1_locked":0,"ctrl1_logical":"0x4400"}  # LEFT+B held only
{"frame":7362,"ctrl1_locked":0,"ctrl1_logical":"0x0400"}  # LEFT only
{"frame":7366,"ctrl1_locked":0,"ctrl1_logical":"0x4440"}
{"frame":7376,"ctrl1_locked":0,"ctrl1_logical":"0x0400"}
{"frame":7379,"ctrl1_locked":0,"ctrl1_logical":"0x0000"}
{"frame":7380,"ctrl1_locked":0,"ctrl1_logical":"0x0000"}
```

Yet `tails_cpu_normal_step` at F7381 records
`delayed_stat=0x00, delayed_input=0x0000` (Tails reads a
17-frame-delayed `Stat_table` slot of all zeros) while at F7382
the next slot reads `delayed_stat=0x07, delayed_input=0x4440`
(matching Sonic at F7365 correctly). The 0x0000 slot at F7381
therefore **cannot** be explained by ROM's
`tst.b (Ctrl_1_locked).w; bne loc_10780` short-circuit at
sonic3k.asm:21542-21544 — that gate never fires here.

**Implications for option (A) / option (B):**

- Option (A) (wire `setControlLocked(true)` at S3K
  springs/vines/etc.) is **not** the right fix for F7381. There
  is no ROM site setting `Ctrl_1_locked=1` in the F7361-F7385
  window for the recorder to point to.
- Option (B) (preserve `logicalInputState` when `controlLocked` is
  set) is similarly inert because `controlLocked` (per-sprite
  move_lock OR global Ctrl_*_locked) is 0 in this window.
- The actual divergence source is **upstream of the gate**: either
  Sonic's `Sonic_RecordPos` skipped writing `Stat_table` for the
  pre-F7382 slot (e.g. competition-mode branch, `object_control`
  bit gating), or the slot has a stale value from a `Stat_table`
  flush during AIZ act-1->act-2 transition / boss arena setup
  earlier in the trace. The relevant Stat_table write site is
  sonic3k.asm:22132 (`move.w (Ctrl_1_logical).w,(a1)+`); the
  corresponding `cmpa.w #Player_1,a0; bne.s locret_10D9E` gate at
  21541-21542 means non-Player_1 calls (e.g. follower routines or
  competition-mode P2 path) skip Stat_table entirely.

**Next investigative steps:**

1. Add a recorder hook on `Sonic_RecordPos` (sonic3k.asm:22115+)
   capturing `Pos_table_index`, `Ctrl_1_logical`, and the actual
   Stat_table slot bytes BEFORE/AFTER the write at frames
   F7360-F7385.
2. Or: add a `stat_table_snapshot_per_frame` event dumping the
   raw `Stat_table` ring buffer once per frame so the slot read
   by `tails_cpu_normal_step` at F7381 can be reconstructed by
   walking back 17 frames.
3. Audit the fixture for any `clr.b (Pos_table)` /
   `Reset_Player_Position_Array` calls (sonic3k.asm:22166+) in
   the AIZ act-1->act-2 transition or boss arena that might
   flush the Stat_table to zero, leaving a band of zero slots
   that line up with F7381's read.

The orchestrator's previously planned "option (A) per-zone
audit" remains valid for **other** ROM divergences that DO
involve `Ctrl_1_locked`, but should be parked for AIZ F7381
specifically until a Stat_table-source diagnostic confirms which
mechanism produces the F7365 slot's 0x0000 read.

---

## CNZ1 Trace F7919 — Tails Triplicate `-0x0800` Velocity Write While Sonic Lands From Rising Platform (OPEN)

**Status:** Investigated, root-cause source not yet localised.

**Symptom**

`TestS3kCnzTraceReplay#replayMatchesTrace` first strict error at frame 7919
(2768 errors), 47 frames downstream of the F7872 spring-trigger OnObj clear
fix:

```
tails_g_speed mismatch (expected=-0x0588, actual=-0x0800)
```

The same frame, the engine writes Tails:

| Field             | ROM (expected) | Engine (actual) |
|-------------------|----------------|-----------------|
| `tails_x_speed`   | `0x0004`       | `-0x0800`       |
| `tails_y_speed`   | `0x0400`       | `-0x0800`       |
| `tails_g_speed`   | `-0x0588`      | `-0x0800`       |
| `tails_y`         | `0x044F`       | `0x0455`        |

All three velocity components transition to the identical value
`-0x0800` in a single frame, while Tails's `air=1`, `rolling=1`, and
`cpu_routine=6 (NORMAL)` match ROM exactly.

**Frame context (F7918 → F7919)**

- Tails: flying as sidekick CPU (`flight_timer=12`, `cpu_routine=6`).
  ROM `ground_vel=-0x0588` has been stable for many frames; flight
  preserves `ground_vel` while the +0x38 flight gravity advances
  `y_vel` (0x03C8 → 0x0400).
- Sonic: lands on the ground at F7919 with normal `g_speed=0x03B5`;
  no spring contact (`x_speed = g_speed = 0x03B5` is just the
  post-landing inertia carry-over, not a spring impulse).
- Tails interact slot 19 (`object_code=0x23050`,
  `Obj_Spring_Horizontal`) at `(0x0D48, 0x04B0)`: 0xBB px right of
  Tails and 0x64 below. Out of `±0x28 X / ±0x18 Y` proactive zone.
- Sonic interact slot 4 (`object_code=0x00031BD0`,
  `Obj_CNZRisingPlatform`) at `(0x0CF0, 0x03E4)`. Sonic's status
  transitions `06 → 00` (left the platform).

**ROM cite — sub_2326C horizontal-spring proactive Player_2 path
(sonic3k.asm:47998-48022)**

```
loc_232E2:
    lea     (Player_2).w,a1
    btst    #Status_InAir,status(a1)
    bne.s   locret_23324           ; if Tails in air, SKIP entirely
    move.w  ground_vel(a1),d4
    ...
```

ROM unconditionally bails when Tails is airborne. There is no
landing-handoff branch on the Player_2 side (the Player_1 path at
sonic3k.asm:47973 has the same `bne` skip). At F7919 the engine has
`tails_air=1`, so the spring proactive path should not fire.

**What `-0x0800` matches in the engine**

A symbol search across `setXSpeed/setYSpeed/setGSpeed` in the engine
finds these candidates that emit `-0x0800` or `0x0800`:

1. `PlayableSpriteMovement.fireShieldDash`
   (`src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:771-790`)
   sets `xSpeed = gSpeed = (0x800 * dir)` and `ySpeed = 0` — would
   match Tails's x and g but not y. Also gated on Sonic-only
   secondary ability (`SecondaryAbility.INSTA_SHIELD`). Tails returns
   `SecondaryAbility.FLY` (`Tails.java:54-55`).
2. `PlayableSpriteMovement.bubbleShieldBounce` and
   `lightningShieldJump` — wrong sign / wrong combination.
3. `Sonic3kSpringObjectInstance.applyHorizontalSpring` /
   `applyDiagonalSpring` — strength is `-0x1000` (red) or `-0x0A00`
   (yellow); never `-0x0800`.
4. `SpringBounceHelper.STRENGTH_RED/YELLOW` — same as above.
5. `CnzVacuumTubeInstance.RELEASE_Y_SPEED = -0x0800`
   (`processLiftMode`, line 142) — but only writes y_vel, not all
   three.

No single existing code path was identified that writes `-0x0800` to
all three velocity components simultaneously. The candidates that
are closest (fire-shield dash, vacuum-tube release) match two of
three but not the full triple. This points to either a not-yet-
visible compound path or a partial overlap of two writes within the
same tick.

**Plausible directions for follow-up**

1. **Tails proactive horizontal-spring with landing-handoff bypass.**
   `Sonic3kSpringObjectInstance.checkHorizontalApproach`
   (`src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSpringObjectInstance.java:413-455`)
   adds a `landingHandoff` branch that fires the spring on a
   sidekick whose `air=1` provided `y_speed > 0` and
   `centreY >= spawn.y`. ROM sub_2326C Player_2 path
   (sonic3k.asm:47998-47999) has no such bypass — the airborne
   `bne.s` skip is unconditional. The handoff was added for an
   earlier CNZ regression (F3649) and is gated only by the engine-
   side `landingHandoff` predicate. Geometry checks at F7919 say
   Tails is 0xBB px past the spring's left edge (out of the ±0x28
   zone), which should preclude this path even with the bypass —
   but the path warrants an audit for whether some other in-engine
   spring triggers it incorrectly.
2. **Vacuum-tube state interaction.** `CnzVacuumTubeInstance` has a
   `-0x0800` y_velocity release and an `IdentityHashMap` of per-
   sidekick lift state; check whether a stale entry could co-fire
   with a different write of `x_vel = g_vel = -0x0800`.
3. **Compound write across two object updates within the same
   tick.** Per-tick ordering in `ObjectManager` could let one
   object set `y_vel = -0x800` and a subsequent object set
   `x_vel = g_vel = -0x800`. Add a one-shot logger inside
   `AbstractPlayableSprite.setXSpeed/setYSpeed/setGSpeed` keyed on
   `frameCounter == 7918` to capture the call sequence.

**Verification rules**

Any candidate fix must keep:

- `TestS3kCnzTraceReplay` advancing past F7919 (engine matches
  expected `-0x0588 g_speed` post-frame).
- `TestS3kAiz1Replay` first-error stable at F7381 (no regression).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- S3K-required tests (`TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`) GREEN.
- Cross-game spring parity (S1/S2/S3K share spring objects) — any
  change in `SpringBounceHelper` / `Sonic3kSpringObjectInstance` /
  `SpringObjectInstance` / `Sonic1SpringObjectInstance` must gate
  S3K-only differences via the narrowest typed `GameRules` rule or
  object-local owner, never `gameId ==`.

**Status update — branch `bugfix/ai-cnz-f7919-tails-spring-impulse`**

Investigation only; no code change. Investigation confirmed the
F7919 trace context and ROM cite for sub_2326C Player_2 air-skip,
and ruled out the obvious single-call-site sources of the triple
`-0x0800` write. Root cause for the actual code path emitting it
is still unidentified after a manual symbol audit; localising the
write requires either (a) instrumenting `setXSpeed/setYSpeed/setGSpeed`
to log the call origin at F7918, or (b) a deeper audit of compound
per-tick object update ordering near a CNZ rising-platform / spring
/ vacuum-tube cluster. Both are larger than this investigation
budget. Documenting findings here so the next round can resume from
a known starting point.

**Status update — branch `bugfix/ai-cnz-f7919-landing-handoff` (write
localised; horizontal spring landing-handoff is NOT the source)**

Investigation only; no code change. The previous round's hypothesis
(Sonic3kSpringObjectInstance.checkHorizontalApproach landing-handoff
bypass firing for airborne Tails) is **wrong**. With temporary
`AbstractPlayableSprite.setXSpeed/setYSpeed/setGSpeed` instrumentation
gated on the F7910..F7925 frame band (`-Ds3k.cnz.velocityprobe=true`,
infrastructure shared with the existing AIZ probe and reverted before
commit), the F7918 triple `-0x0800` write to Tails was localised to:

```
src/main/java/com/openggf/game/sonic3k/objects/ClamerObjectInstance.java
applySpringLaunch (lines 166-184)
  player.setXSpeed((short) xSpeed);            // -0x0800 with facingRight=true
  player.setGSpeed((short) xSpeed);            // -0x0800
  player.setYSpeed((short) -LAUNCH_SPEED);     // -0x0800
```

reached via:

```
ClamerObjectInstance.applySpringLaunch
  ClamerObjectInstance.onTouchResponse (TouchCategory.SPECIAL)
    ObjectManager$TouchResponses.handleTouchResponseSidekick
      ObjectManager$TouchResponses.processMultiRegionTouch
        ObjectManager$TouchResponses.processCollisionLoop
          ObjectManager$TouchResponses.updateSidekick
            ObjectManager.runTouchResponsesForPlayer
              LevelManager.applyTouchResponses
                SpriteManager.tickPlayablePhysics
```

The Clamer is S3K Obj $A3 (`Obj_Clamer` at `sonic3k.asm:185856+`). The
visible parent owns a hidden spring child (`loc_8908C` at
`sonic3k.asm:185943-185951`) seeded at `currentY - 8` with collision
flags `$D7 = $C0|$17`. The `$17` collision-size index uses
`Touch_Sizes[$17] = (8,8)` (8×8 half-extents) per the ROM
`Touch_Sizes` table at `sonic3k.asm:20713-20770`.

**ROM dispatch flow (sonic3k.asm:179904, 21162, 185953-185998):**

1. Each character runs `TouchResponse` from its own main loop:
   - Sonic at `sonic3k.asm:22022` (`jsr (TouchResponse).l`).
   - Tails at `sonic3k.asm:26266` (same call, including when running
     under the sidekick CPU — Tails_Normal branch).
2. `Touch_Loop` walks `Collision_response_list`. For the Clamer
   spring child the `andi.b #$C0,d1` produces `$C0` →
   `Touch_Special` (`sonic3k.asm:21162-21183`).
3. `Touch_Special` allows size `$17` and falls through to
   `loc_103FA` (`sonic3k.asm:21186-21194`):
   ```
   move.w  a0,d1           ; a0 = touching character RAM
   subi.w  #Object_RAM,d1  ; Object_RAM = Player_1 RAM
   beq.s   .ismaincharacter
   addq.b  #1,collision_property(a1)   ; +1 if not Player_1
   .ismaincharacter:
   addq.b  #1,collision_property(a1)   ; +1 unconditionally
   ```
   So a Sonic-only touch sets `collision_property=1`; a Tails-only
   touch sets `collision_property=2`; both touch sets it to `3`.
4. On the next Clamer-spring-child object update, `loc_890AA`
   (`sonic3k.asm:185953-185962`) calls `Check_PlayerCollision`
   (`sonic3k.asm:179904-179917`):
   ```
   move.b  collision_property(a0),d0
   beq.s   locret_8588E      ; no touch this frame -> skip
   clr.b   collision_property(a0)
   andi.w  #3,d0
   add.w   d0,d0
   lea     word_85890(pc),a1
   movea.w (a1,d0.w),a1      ; -> Player_1 (0,1) or Player_2 (2,3)
   ```
5. Back in `loc_890AA`, the Z flag from `moveq #1,d1` is clear, so
   `beq.s loc_890C4` is not taken; the launch dispatches once to
   the selected player via `sub_890D8` (`sonic3k.asm:185978-185998`),
   which writes `x_vel = ±$800`, `ground_vel = ±$800`, `y_vel =
   -$800`, sets `Status_InAir`, `addq.w #6,y_pos`, anim `$10`,
   routine `2`, `clr.b jumping`, plays SFX, then transitions
   `(a0) -> loc_890C8` (1-frame cooldown).

**Why the engine writes -0x0800 at F7918 but ROM doesn't:**

The engine's `applySpringLaunch` is invoked because Tails is
overlapping the Clamer's 8×8 spring-child collision box at the
moment of touch dispatch. Tails's engine position at F7918 (centre
`(0x0C8E, 0x044F)`) lands inside that box. ROM's Tails at the same
trace frame is reported by the trace's CPU-state JSONL as in
`flight_timer=12`/`flight_timer=13` flight under `cpu_routine=6`,
heading toward `target_x=0x0FC8 / target_y=0x047C` — i.e. ROM's
sidekick is aimed past the Clamer line and is not co-located with
the spring child for this frame. The engine has Tails at a
position that overlaps the Clamer; ROM does not. **The Clamer
dispatch in the engine is therefore a downstream symptom; the
upstream divergence is Tails's engine position (and CPU/flight
state) drifting away from ROM through the F7872 → F7919 window.**

Per-frame Tails state divergence at F7918 (engine probe vs trace
`cpu_state` event):

| Field          | Engine (probe)            | Trace ROM (cpu_state)        |
|----------------|---------------------------|------------------------------|
| centre pos     | `(0x0C8E,0x044F)`         | flight, target `(0x0FC8,...)` |
| `roll`/anim    | `roll=true, air=true`     | `cpu_routine=6 (FLY)`        |
| `flight_timer` | not flying                | `12` -> `13`                 |

Even with Tails at the engine's wrong position, ROM's
`Check_PlayerCollision` would still fire `sub_890D8` on Tails (the
dispatch picks Player_2 when `collision_property=2`). So the
engine's per-touch immediate dispatch is consistent with what ROM
would do **given the same overlap inputs**; the divergence is in
the inputs, not in the Clamer object code.

**Why `landingHandoff` is not the source:**

`Sonic3kSpringObjectInstance.checkHorizontalApproach` (the line-413
landing-handoff branch flagged in the previous round) is for
horizontal **springs**, not Clamers. The probe's call-graph at
F7918 contains no `Sonic3kSpringObjectInstance` frame. The closest
horizontal spring is at `(0x0D48,0x04B0)`, 0xBB pixels right of
Tails — outside the proactive zone — and never enters the trace
for F7918.

**Plausible directions for follow-up (revised):**

1. **Audit Tails's CPU-state divergence in the F7872 → F7918
   window.** ROM reports `cpu_routine=6` (NORMAL/FLY) with a live
   `flight_timer`; engine reports `roll=true, air=true` (a roll-jump
   under sidekick CPU). The transition from "rolling jump" to "fly"
   is what's missing. Trace events `tails_cpu_normal_step` per
   frame should isolate where the engine fails to enter / leaves
   flight mode early.
2. **Audit Tails's leader-on-object handoff at F7872.** F7872 is the
   commit-message-cited "Sonic-jumps-off-platform OnObj clear" frame
   that supposedly advanced from F7872 to F7919. Tails's CPU state
   at F7872 may inherit a wrong `target_y` / `flight_timer` from the
   handoff, putting Tails on the wrong trajectory through the next
   46 frames.
3. **Do not change `ClamerObjectInstance` itself.** Its per-touch
   dispatch matches ROM's `loc_890AA -> sub_890D8` for the inputs
   it sees; gating it on engine-side state to mask the upstream
   bug would diverge from ROM for legitimate Tails-touches-Clamer
   scenarios. The engine's `IdentityHashMap`-keyed re-enable timer
   at `springReenableFrame` already mirrors ROM's `loc_890C8`
   `subq.w #1,$2E(a0); bmi loc_890D0` 1-frame cooldown.

**Verification rules (unchanged from the previous round)**

Any candidate fix targeting the upstream Tails CPU/flight handoff
must keep:

- `TestS3kCnzTraceReplay` advancing past F7919 (engine matches
  expected `-0x0588 g_speed` post-frame).
- `TestS3kAiz1Replay` first-error stable at F7381 (no regression).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- Cross-game touch-response parity (S1/S2/S3K share
  `AbstractObjectInstance` / `TouchResponseProvider`) — any change
  in `ClamerObjectInstance` or shared touch dispatch must gate
  S3K-only differences via the narrowest typed `GameRules` rule or
  object-local owner, never `gameId ==`.

**Status update — branch `bugfix/ai-cnz-tails-cpu-drift` (root
cause re-localised: Clamer parent state machine, NOT Tails CPU)**

Investigation only; no code change.

The previous round's diagnosis ("Tails CPU/flight state drifted
away from ROM through the F7872 → F7918 window") was based on a
misreading of the trace `cpu_state` event. Closer inspection of
the regenerated v6.12-s3k CNZ trace shows:

1. **`cpu_routine=6` is NOT "FLY".** S3K `Tails_CPU_Control_Index`
   (sonic3k.asm:26368-26386) maps `cpu_routine=6` to `loc_13D4A`,
   which is the **NORMAL ground-following AI**. Engine
   `SidekickCpuController.mapRomCpuRoutine` at line 2419 already
   maps it to `State.NORMAL` correctly. ROM Tails has been in
   NORMAL routine the entire F7860 → F7920 window — same as engine.
   The "FLY" label in the previous summary was wrong.
2. **`flight_timer` is the `sub_13EFC` watchdog**, not a flight
   state. `sub_13EFC` (sonic3k.asm:26816-26847) increments
   `Tails_CPU_flight_timer` whenever Tails is `Status_OnObj` on
   the same `Tails_CPU_interact` slot, and resets it when those
   conditions fail. It only triggers a respawn (via `sub_13ECA`)
   when the watchdog hits `5*60 = 0x12C` (5 seconds stuck on the
   same object). Through F7860–F7871 the watchdog ticked 16→27
   while Tails was riding a Bumper / moving platform; at F7872 it
   reset to 0 because Tails went airborne (`Status_OnObj` cleared).
3. **Tails's position in ROM matches the engine through F7918.**
   The `target_x=0x0FC8 / target_y=0x047C` cited as "ROM's
   sidekick is aimed past the Clamer line" was from the CPU's
   *target* (a leader-history position), not Tails's actual
   `x_pos/y_pos`. The actual ROM Tails position from the
   `object_state` events (slot 1) tracks the engine perfectly:
   ```
   Frame  ROM (x,y)        Engine (x,y) [from context.txt]
   7915   (0x0C8B,0x0441)  (0x0C8B,0x0441)
   7916   (0x0C8C,0x0445)  (0x0C8C,0x0445)
   7917   (0x0C8C,0x0448)  (0x0C8C,0x0448)
   7918   (0x0C8D,0x044C)  (0x0C8D,0x044C)
   ```
   The +6 px y delta visible at F7919 (`actual=0x0455` vs
   `expected=0x044F`) is the `addq.w #6, y_pos` written by
   `sub_890D8` itself when the Clamer launches Tails — it's a
   consequence of the engine's launch firing, not a pre-existing
   position drift.

The actual upstream divergence is **state of the Clamer parent
object (S3K Obj $A3, slot 6 in ROM at `(0x0C98,0x0470)`)**:

- ROM trace shows parent slot 6 transitions
  `routine 0x02 → 0x06` at frame F7872 (synchronous with Sonic
  jumping off the rising platform). It then stays at routine
  0x06 through F7920+. Routine 0x06 is `loc_89064`
  (`sonic3k.asm:185905-185919`), the close animation; it does
  NOT spawn an active spring child via `loc_8908C`/`loc_890AA`,
  so the spring child cannot launch a player while the parent
  is in this routine.
- The engine's `ClamerObjectInstance.update()` (lines 80-89)
  only handles the local `closeTimer` countdown and animation
  step; it does NOT implement ROM's `Clamer_Index` parent state
  machine. Specifically, the auto-close trigger at `loc_88FEC`
  (sonic3k.asm:185878-185902) is missing:
  ```
  loc_88FEC:                     ; routine 0x02 (idle/looking)
      btst    #0,$38(a0)
      bne.s   loc_89014          ; spring child fired -> routine 4
      jsr     Find_SonicTails(pc); a1 = closer player
      cmpi.w  #$60,d2            ; abs(dx_closer)
      bhs.s   loc_8900C          ; dx >= 0x60 -> just animate idle
      btst    #0,render_flags(a0); facing-right gate
      beq.s   loc_89008
      subq.w  #2,d0              ; flip the side check
  loc_89008:
      tst.w   d0
      beq.s   loc_89036          ; player on the wrong side -> routine 6 close
  loc_8900C:
      ; Animate_RawNoSSTMultiDelay (idle anim)
  ```
  At F7872 the closer player to slot 6 (Clamer at 0x0C98,0x0470)
  is Tails (`Find_SonicTails` returns Tails: dx≈0x55, dy≈0x1A).
  `abs(dy) < 0x60`, and once the `render_flags` directional
  flip is applied, ROM's `tst.w d0; beq.s loc_89036` lands on
  the close branch and writes `routine 0x06`.
- The engine's Clamer therefore stays in its (engine-internal)
  active state with the spring child armed. When Tails passes
  through the spring child's collision box at F7918/F7919, the
  engine fires `applySpringLaunch` (line 132 → 166-184) and
  writes the triple `-0x0800` impulse. ROM never fires because
  the parent has been in `loc_89064` (routine 0x06) since F7872
  and `loc_89064` does not call `loc_890AA`.

**Trace evidence (regenerated v6.12-s3k aux_state.jsonl.gz):**

```
slot 6 routine transitions (ROM, parent Clamer @0x0C98,0x0470):
  F7557: 0x02
  F7585: 0x06   ; close (Sonic passed earlier)
  F7716: 0x02   ; reopen
  F7717: 0x04
  F7784: 0x06   ; close
  F7871: 0x02   ; reopen
  F7872: 0x06   ; close (Sonic-jumps-off-platform)
  ... stays 0x06 through F7920+
```

**ROM cite (S3K Obj $A3 — `Obj_Clamer`):**

- `sonic3k.asm:185856` — `Obj_Clamer:` entry, dispatches via
  `Clamer_Index` table at `sonic3k.asm:185868-185874`.
  Routines: `0x00` init / `0x02` idle (auto-close gate) /
  `0x04` snap-shut animation / `0x06` close-and-reset
  animation.
- `sonic3k.asm:185878-185902` — `loc_88FEC` (routine 0x02)
  reads `Find_SonicTails`, gates on `abs(dx) < $60`, applies
  the `render_flags` directional flip, and jumps to `loc_89036`
  (writes routine `0x06`) when the player is on the closing
  side.
- `sonic3k.asm:185905-185919` — `loc_89064` (routine 0x06)
  runs the close animation only; it does NOT call `loc_8908C`
  or `loc_890AA`, so the spring child stays inactive while in
  this routine.
- `sonic3k.asm:185878-185902, 185931, 185943-185951` —
  `loc_89014` / `loc_89036` / `loc_8908C` flow controlling
  parent routine transitions.
- `sonic3k.asm:178243-178277` — `Find_SonicTails` returns
  `a1 = closer player`, `d0 = 0/2 directional flag`, `d2 =
  abs(dx_closer)`, `d3 = abs(dy_closer)`.

**Plausible directions for follow-up (re-revised):**

1. **Port the `Clamer_Index` parent state machine to
   `ClamerObjectInstance.update()`.** Required pieces:
   - A `Find_SonicTails`-equivalent helper for picking the
     closer of `leaderPlayer` / `sidekickPlayer` (existing engine
     plumbing under `services()` already exposes both via
     `ObjectServices` / `services().sonic()` /
     `services().sidekick()` — the helper can be private to the
     Clamer class for now since `Find_SonicTails` only fires
     from a small set of CNZ objects).
   - The auto-close gate at `loc_88FEC`: `abs(dx) < 0x60` AND
     `(player.x relative to clamer) XOR render_flags_bit0`.
   - Routine 0x06 state that disables the spring child until
     the close animation completes, then resets via `loc_89056`
     (`move.b #2, routine; move.b #$A, collision_flags`).
2. **Cross-game audit.** S1 has no Clamer; S2 has no Clamer.
   This is purely an S3K-only object, so no `GameRules`
   gate is needed — the entire `Clamer_Index` port lives in
   `ClamerObjectInstance` (S3K-only file).
3. **Do NOT add an engine-only proximity gate that fires the
   close without the `render_flags` directional check.** ROM
   gates the close on the player's *side relative to the
   Clamer's facing*, so a one-sided Clamer (e.g. spawned with
   `render_flags bit 0 = 0`) only auto-closes for players on
   one side and stays open for players passing on the other.
   Skipping the directional gate would cause Clamers to close
   in scenarios where ROM keeps them open.

**Verification rules (revised)**

Any candidate fix porting the Clamer parent state machine
must keep:

- `TestS3kCnzTraceReplay` advancing past F7919 (engine matches
  expected `-0x0588 g_speed` post-frame).
- `TestS3kAiz1Replay` first-error stable at F7381 (no regression).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- All `Sonic3kObjectRegistry` Clamer-aware callers continue to
  fire on direct touch (the Tails-touches-armed-Clamer path
  must still launch via `sub_890D8`-equivalent code).
- The S3K-required tests
  (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`)
  remain GREEN.

The fix is non-trivial (new state machine + Find_SonicTails-
equivalent helper + render_flags directional gate + animation
script termination handlers) and was not landed on this branch
because porting it correctly without regressing other Clamer
encounters earlier in the level (slots 4/5 at `(0x0578,0x0690)`,
slots 11 at `(0x28C0,0x0268)`, etc.) requires verifying the
state machine against multiple traces, not just the F7872 →
F7919 window. Documented here so the next round can resume
from the correct ROM-cite starting point.

**Status update — branch `bugfix/ai-cnz-clamer-spring-child-box`
(spring-child collision-box dimensions audited; engine matches ROM)**

Investigation only; no code change. The previous round's
parent state-machine port (commit `47c5512c0`) landed the
`Clamer_Index` routines + `Find_SonicTails` auto-close gate but
did NOT advance F7919: `TestS3kCnzTraceReplay#replayMatchesTrace`
still fails at frame 7919 with the same triple `-0x0800` write
to Tails (2757 errors). This round audited the spring child's
collision-box dimensions against ROM `word_89136` and confirmed
they already match.

**ROM cite (`word_89136`, sonic3k.asm:186008-186010):**

```
word_89136:
    dc.w   $280               ; priority
    dc.b    8,   4,  $B, $D7  ; width_pixels=8, height_pixels=4,
                              ; mapping_frame=$B, collision_flags=$D7
```

`SetUp_ObjAttributes3` (sonic3k.asm:176902-176912) writes these
four bytes into the spring-child sprite. **Critically, only the
`collision_flags=$D7` byte feeds `Touch_Loop`'s overlap test —
`width_pixels` and `height_pixels` are the rendering /
`Sprite_OnScreen_Test` bounding-box, not the touch box.** The
prior task brief's claim that ROM uses width=8 / height=4 for
the spring-child *collision box* conflated these two distinct
fields. ROM's actual spring-child collision box is decoded from
`collision_flags & $3F = $17`, which indexes into the
`Touch_Sizes` table (sonic3k.asm:20713-20770) at entry $17:

```
Touch_Sizes:
    dc.b 4,4   ; index $00
    ...
    dc.b 8,8   ; index $17  <- Clamer spring child
    ...
```

So ROM's spring-child collision box is **8×8 half-extents
(16×16 total)**, NOT 8×4.

**Engine equivalent:**

`ClamerObjectInstance.SPRING_COLLISION_FLAGS = 0x40 | 0x17`
(`src/main/java/com/openggf/game/sonic3k/objects/ClamerObjectInstance.java:47`)
feeds `processMultiRegionTouch`
(`src/main/java/com/openggf/level/objects/ObjectManager.java:3617-3633`),
which reads `Touch_Sizes[$17]` from `TouchResponseTable` —
loaded from the **same ROM table address** at startup. Engine
spring child collision box: 8×8 half-extents (16×16 total).
**Engine and ROM box dimensions match exactly.** No fix is
warranted on collision-box-dimension grounds.

**The auto-close gate runs after the spring-child touch dispatch
in ROM, so it cannot prevent the F7918 spring fire on its own.**

`Touch_Loop` (sonic3k.asm:20660-20710) walks
`Collision_response_list` and dispatches collision per object
each frame; `loc_890AA` (sonic3k.asm:185953) only consumes the
deferred `collision_property` byte set by `Touch_Special`
(sonic3k.asm:21183-21194). The Clamer parent's `loc_88FEC`
auto-close gate (sonic3k.asm:185880-185902) and the spring
child's `loc_890AA` run independently each frame and the parent
does not free the spring child slot when transitioning to
routine 0x06.

**Why the engine still fires at F7918 while ROM fires at F7920+:**

A geometric inspection of trace data (target/trace-reports/
s3k_cnz1_context.txt, F7918-F7921) shows that **even ROM does
not satisfy a strict 8×8 box overlap at F7918** for Tails at
`(0x0C8D, 0x044C)` against the Clamer spring child at
`(0x0C98, 0x0468)`:

| Frame | Tails (ROM)        | dx | dy | y-rad probe | Y overlap? |
|-------|--------------------|----|-----|------------|------------|
| 7918  | `(0x0C8D, 0x044C)` | -0x0B | -0x1C | rolling 14 -> player Y [0x0441,0x0457] vs box [0x0460,0x0470] | NO |
| 7919  | `(0x0C8E, 0x044F)` | -0x0A | -0x19 |  -> [0x0444,0x045A] vs [0x0460,0x0470] | NO |
| 7920  | `(0x0C8F, 0x0453)` | -0x09 | -0x15 |  -> [0x0448,0x045E] vs [0x0460,0x0470] | NO (gap of 2 px) |
| 7921  | `(0x0C91, 0x045E)` | -0x07 | -0x0A |  -> [0x0453,0x0469] vs [0x0460,0x0470] | YES |

ROM slot 8 enters `loc_890C8` cooldown at F7921 — consistent
with the spring child firing on F7920->F7921 (Tails crosses into
the box). The engine fires **two frames early** at F7918, while
Tails is still ~2 px above the box. Box dimensions agree; the
divergence is in either Tails's effective collision rectangle or
in *when* the engine evaluates the overlap.

**Plausible directions for follow-up (re-revised again):**

1. **Tails's `getYRadius()` / hitbox-in-air semantics audit.**
   The trace reports ROM Tails is `air=1, rolling=1` through
   F7918-F7920 with `y_radius=14` (rolling). If the engine
   reports a different `y_radius` for airborne-rolling Tails
   (e.g. retains standing 19 because rolling-on-air doesn't
   shrink the radius in the engine, or vice-versa), the
   resulting playerY rectangle could include the 0x0460 line
   and produce a false overlap. ROM `Touch_NoInstaShield`
   (sonic3k.asm:20642-20653) uses `y_radius - 3` unconditionally;
   the engine path
   (`ObjectManager.processCollisionLoop` sidekick branch,
   `src/main/java/com/openggf/level/objects/ObjectManager.java:3474-3477`)
   uses `Math.max(1, sidekick.getYRadius() - 3)`. Verify whether
   `AbstractPlayableSprite.getYRadius()` returns 14 for
   airborne-rolling Tails or another value at F7918.

2. **Pre-touch-resolution sub-frame ordering.** ROM's
   `Touch_Loop` runs at the END of each main loop iteration
   AFTER all object updates, so it sees post-update positions.
   The engine's `processCollisionLoop` calls (per
   `LevelManager.applyTouchResponses` ->
   `SpriteManager.tickPlayablePhysics` ->
   `ObjectManager.runTouchResponsesForPlayer`) need to be
   verified to fire at the same point in the frame relative to
   Tails's CPU update + physics step + position write. If touch
   runs against a Tails position from before the F7918 step
   instead of after, the box check is off by one frame's worth
   of motion. This is the most likely cause given the off-by-2
   in the trace, but needs probe instrumentation to confirm.

3. **Active-object filter consistency.** Verify the engine has
   a single active `ClamerObjectInstance` at `(0x0C98,0x0470)`
   (matching ROM slot 8's parent). The eng-near list in the
   trace report only shows objects close to Sonic; the
   `(0x0C98,0x0470)` Clamer is closer to Tails and not visible
   in eng-near. A targeted probe in `ClamerObjectInstance`
   constructor / placement should confirm spawn coordinate.

**Verification rules (unchanged)**

- `TestS3kCnzTraceReplay` advancing past F7919.
- `TestS3kAiz1Replay` first-error stable at F7381 / F7552.
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- S3K-required tests
  (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`)
  GREEN.
- Cross-game spring/touch-response parity gated via the narrowest typed
  `GameRules` rule or object-local owner, never `gameId ==`.

**Out-of-scope rationale.** The mission brief asked
specifically about the spring-child collision-box dimensions.
This audit confirms the engine box already matches ROM
(`Touch_Sizes[$17] = 8x8`); narrowing it further to (8,4) per
`width_pixels`/`height_pixels` would diverge from ROM and is
explicitly rejected as a hack. The actual root cause of the
2-frame-early dispatch is a different upstream issue
(candidate list above) and is left for a follow-up round with
a focused scope on collision-frame ordering or Tails's airborne
hitbox.

---

## AIZ F7552 — Tails 1-pixel x drift after AIZ Mini-boss napalm slot destruction (FIXED)

**Status:** FIXED on branch `bugfix/ai-aiz-f7552-boundary-right-extra`.
Hurt-airborne path in `PlayableSpriteMovement.modeAirborne` now mirrors
ROM `Obj01_Hurt` / `Sonic_Hurt` / S3K `loc_122D8`+`loc_156D6` ordering:
`doObjectMoveAndFall` → underwater gravity reduction → `updateSensors`
→ `doLevelCollision` (Sonic_HurtStop equivalent) → `doLevelBoundary`.
The previous order (boundary-then-move) was correct for the normal
airborne (`Obj01_MdAir` / `Tails_Stand_Freespace`) path but wrong for
hurt routine 4, which produced an off-by-one frame on the right-edge
clamp when knockback drove Tails into `Camera_max_X_pos+$128`. AIZ
trace first-error advances 7552 → 7660; AIZ error count 977 → 975.
ROM cites: `s2.asm:37820-37834` (`Obj01_Hurt_Normal`),
`s1disasm/_incObj/01 Sonic.asm:1791-1804` (`Sonic_Hurt`),
`sonic3k.asm:24449-24467` (`loc_122D8`, S3K Sonic hurt routine 4),
`sonic3k.asm:29194-29209` (`loc_156D6`, S3K Tails hurt routine 4).
The original diagnosis text below (Solid_Object ride-bridge
hypothesis) was incorrect — the recorded `boundary_action`
`x_clamp_1208` and matching `Camera_max_X_pos = 0x10E0` confirm the
divergence was a boundary-clamp ordering issue, not a missing
ride-bridge from the napalm projectile.

Continues from the F7381 fix (`Reset_Player_Position_Array` Stat_table
mirror, commit `3d720df1a`).

**Symptom**

`TestS3kAizTraceReplay#replayMatchesTrace` first strict error at frame 7552:

```
tails_x mismatch (expected=0x1208, actual=0x1207) — 977 errors total
tails_x_speed mismatch (expected=0x0000, actual=0x0200) — same frame
```

The cascading 977 errors are dominated by independent (non-cascading)
`tails_x` +/-1 mismatches across F7677-F8031 plus several `g_speed` /
`x` / `y_speed` cascades — i.e. the F7552 1-px drift desynchronises
Sonic vs Tails relative position enough that Sonic-side touch-floor
angle reads, jump arcs, and follow-steering nudges all start to
diverge by 1 px windows.

**Trace context**

Tails state across F7544-F7553:

| Frame | tails_x | x_speed | y_speed | air | rolling | g_speed |
|-------|---------|---------|---------|-----|---------|---------|
| 7544  | 0x11F4  | 0x029C  | -04F8   | 1   | 1       | 0x020C  |
| 7547  | 0x11FC  | 0x029C  | -0450   | 1   | 1       | 0x020C  |
| 7548  | 0x11FF  | 0x0200  | -0400   | 1   | 0       | 0x0000  |
| 7549  | 0x1201  | 0x0200  | -03D0   | 1   | 0       | 0x0000  |
| 7550  | 0x1203  | 0x0200  | -03A0   | 1   | 0       | 0x0000  |
| 7551  | 0x1205  | 0x0200  | -0370   | 1   | 0       | 0x0000  |
| 7552 ROM  | 0x1208  | 0x0000  | -0340   | 1   | 0       | 0x0000  |
| 7552 ENG  | 0x1207  | 0x0200  | -0340   | 1   | 0       | 0x0000  |

Engine and ROM agree exactly through F7551; the divergence is purely
at F7552: ROM applies +3 px to `tails_x` and zeroes `tails_x_speed`,
while engine applies +2 px and keeps `tails_x_speed=0x0200`.

Trace recorder context line for F7552:

```
ROM: rtn=02 ... sidekick=sub=(0000,1700) rtn=04 status=02 onObj=10
Trace diagnostics @7552: tailsInteract slot=16 ptr=B4A0 obj=00000000
  rtn=00 st=00 @0000,0000 sub=00 tails rf=84 obj=00 onObj=false
  objP2=false active=false destroyed=true
ENG: ... eng-tails-cpu f=7253 state=NORMAL branch=follow_steering
  hist=16/05 in=0010 stat=06 push=06 pre=obj00 st02 xv0200 gv0000
  gen=0010 postCpu=obj00 st02 xv0200 gv0000 postPhys=seen obj00
  st02 xv0200 gv0000 dx=FFDB dy=FFFE skip=false
  | eng-tails-cyl none sidekick=@1207,0314
```

Key facts:

- ROM Tails `interact` field references slot `0x10` (decimal 16), which
  is the AIZ Mini-boss Napalm projectile (object id `0x91`,
  `Obj_AIZMinibossNapalm`). The slot is recorded as `destroyed=true`
  and `obj=00000000` — the napalm projectile was destroyed during F7552
  (or just before).
- ROM `sidekick=...rtn=04 status=02 onObj=10`: Tails CPU routine = 4
  (`loc_13F40`, post-control spindash-cooldown), Status_InAir set.
- Engine `eng-tails-cpu branch=follow_steering`: engine took the
  default follow-steering branch (no current_push_bypass /
  grace_push_bypass / airborne_push_handoff / leader_on_object /
  leader_fast).
- Engine `postPhys` shows `xv0200 gv0000` — neither Tails CPU code nor
  physics code modified `x_vel`. ROM's zero of `x_vel` and the +1 px
  positional bump must therefore originate from a code path the engine
  does **not** mirror.

**Suspected mechanism (unverified)**

ROM `loc_13D78` (sonic3k.asm:26668-26708) and `loc_13F40`
(sonic3k.asm:26851-26896) handle Tails CPU routines 3 and 4 but only
adjust `x_pos` by `+/-1` when `ground_vel != 0`
(sonic3k.asm:26718-26741). At F7552 Tails has `ground_vel=0` and
`Status_InAir=1`, so neither path explains the +1 px / zero-x_vel
move. The most plausible source is a **ROM-only "ride" bridge**: Tails
was riding the AIZ Mini-boss Napalm projectile (slot 16) right before
the projectile destroyed itself. ROM `Obj_AIZMinibossNapalm`
(`Obj91`) and the parent `Obj_AIZMiniboss` body apply a `MvSonicOnPtfm`
/ `Solid_Object` style position bridge before the destruction frame
clears the interact pointer; the engine's `AizMinibossNapalmProjectile`
(`src/main/java/com/openggf/game/sonic3k/objects/AizMinibossNapalmProjectile.java`)
does **not** expose any solid-on-top behaviour, so engine Tails never
gets the ride-bridge +1 px bump and never gets its `x_vel` zeroed by a
landing transition.

The +3 px / zero-x_vel pattern matches a `Solid_Landed`-style ride
release: ROM applies the platform's last-tick `x_displacement` to the
rider, then on detach (`Solid_Object_Detach` /
`Solid_NoCollide`) clears the rider's `x_vel`. The 1-pixel difference
between engine (+2) and ROM (+3) is exactly one
`platform.x_displacement` of `+0x100` = +1 px — consistent with the
napalm body moving right by 1 px on its final frame before
self-destruction.

**Why no clean fix lands here**

A surgical fix needs:

1. Confirmation that ROM Tails actually rides the napalm projectile
   (verify by extending the trace recorder to log `Sidekick.interact`
   ROM target object's `x_displacement` and `x_pos` at F7551-F7552 —
   the recorder currently logs the slot id and destroyed state but not
   the platform delta).
2. A ROM cite for the Mini-boss / napalm `Solid_Object` /
   `Solid_NoCollide` invocation that supplies the ride-bridge.
3. Porting that solid-on-top behaviour into
   `AizMinibossNapalmProjectile` (or the parent `AizMinibossInstance`
   if the body — not the projectile — is the actual ride target),
   gated on the object being touchable from above.
4. Cross-game audit: S1/S2 have no AIZ miniboss, so this is S3K-only
   (no `GameRules` rule needed; the fix lives entirely in the
   AIZ miniboss object files).

The investigation revealed that the on-screen object closest to
ROM `interact=0x10` in the engine snapshot is `AIZMinibossNapalm`
(`@11F0,02FA`, slot 16), but the engine record shows `no-touch` and
`destroyed=true` for that slot — so the engine's destruction order
already discarded the napalm before the ride bridge would fire. This
strengthens the hypothesis that ROM applies the ride bridge as part of
the napalm's *final* update (between the recorder's pre-frame and
post-frame samples) and the engine destroys the object one frame too
early.

**Verification rules for any candidate fix**

- `TestS3kAizTraceReplay` must advance past F7552 (engine matches
  expected `tails_x=0x1208` and `tails_x_speed=0x0000` at end of
  F7552).
- Earlier strict errors must remain stable: F7381 fix
  (`Reset_Player_Position_Array` Stat_table clear) must not regress.
- S1 GHZ / S1 MZ1 / S2 EHZ trace replays must remain GREEN (no
  cross-game regression).
- The S3K-required tests (`TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`) must remain GREEN.
- Documented intentional divergences in `known-discrepancies.md` /
  `S3K_known-discrepancies.md` must remain unaffected.

**Concrete next-round starting points**

1. Extend the trace recorder (`tools/bizhawk/s3k_trace_recorder.lua`
   and the engine-side comparator aux event) to log
   `Player_2.interact->x_pos` and `x_displacement` for F7549-F7553 so
   the ride-bridge hypothesis can be confirmed or rejected from the
   trace alone.
2. Locate `Obj_AIZMinibossNapalm` / `Obj91` in `sonic3k.asm` (search
   for label `Obj91` or `AIZ_Miniboss_Napalm` in the boss object
   tables around `Obj_AIZMiniboss` at sonic3k.asm:137222) and inspect
   whether it calls `Solid_Object` /
   `Solid_NoCollide` / `Solid_Object_Detach`.
3. If the ride bridge is confirmed, port it into
   `AizMinibossNapalmProjectile.update()` — register the projectile as
   solid-on-top while alive, and on `setDestroyed(true)` issue a
   `Solid_Object_Detach`-equivalent clear of `x_vel` on any rider.

---

**Status update — branch `bugfix/ai-cnz-f7919-clamer-state-machine`
(Clamer state machine ported)**

Engine fix landed. `ClamerObjectInstance` now hosts the ROM
`Clamer_Index` parent state machine (sonic3k.asm:185856-185998) and a
`Find_SonicTails`-equivalent helper. Routines implemented:

- `0x02 - loc_88FEC` (idle / auto-close gate): `Find_SonicTails`-driven
  closer-player lookup, `cmpi.w #$60,d2; bhs loc_8900C` distance gate,
  `render_flags` bit-0 directional flip (`subq.w #2,d0`), and the
  `tst.w d0; beq loc_89036` close transition.
- `0x04 - loc_8904E` (snap-shut animation after spring fired): clears
  collision_flags via `loc_89014`-equivalent path; resets to routine
  0x02 once the local close-timer drains, mirroring `loc_89056`.
- `0x06 - loc_89064` (auto-close): keeps `collision_flags` alive
  (ROM `loc_89036` does not clear them) and resets via `loc_89056`
  once the close animation completes.

Spring child collision box stays active across all parent routines:
ROM `loc_890AA` (sonic3k.asm:185953-185962) runs independently of
the parent's routine, so the child's collision check fires on its own
post-fire cooldown rather than being gated on the parent state. The
earlier diagnosis text suggesting routine 0x06 deactivates the spring
child was inaccurate; in ROM, only the spring child's local cooldown
(`loc_890C8` -> `$2E(a0)` decrement) and the parent's `collision_flags`
clear (which only fires in routine 0x04, not routine 0x06) gate the
spring's behaviour.

**Open questions:**

- The CNZ trace fixture is not yet checked into this repo, so the port
  has been verified against:
  - 6 unit tests in `TestClamerObjectInstance` (2 pre-existing for the
    spring-launch path + 4 new for the auto-close gate).
  - Cross-game parity (S1/S2 do not have Clamer; no `GameRules`
    rule needed).
  - Wide S3K test suite: 1127 passed, 28 failed, 9 errors — identical
    failure set to baseline (no new failures).
- The original diagnosis claim that ROM stays in routine 0x06 from
  F7872 to F7920+ should still cause the engine to fire `applySpringLaunch`
  at F7918 if Tails geometrically overlaps the engine's spring touch box,
  because the spring child runs independently of the parent. If the CNZ
  trace test (when added) still fails at F7919, the next investigation
  should focus on the spring child's collision-box dimensions
  (engine size index `0x17` vs ROM child `word_89136` width 8 / height 4)
  rather than on the parent state machine.

**Earlier Clamer encounters:** The auto-close gate is purely additive
(does not affect the spring-launch path), so slot 4/5 at `(0x0578,0x0690)`
and slot 11 at `(0x28C0,0x0268)` should not regress unless the player
approach window now triggers an auto-close that pre-empts a planned
spring-launch. The gate fires only when the closer player is within
`abs(dx) < 0x60` AND on the side the Clamer is facing — the same
predicate ROM evaluates, so engine and ROM should agree per-frame.

**Round 2 update — napalm Solid_Object_Detach hypothesis disproven (branch `bugfix/ai-aiz-napalm-solid-detach`, 2026-04-30)**

Direct inspection of the recorded trace JSONL (`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/aux_state.jsonl.gz`) shows the napalm slot is destroyed *long before* F7552:

```
F7500: tailsInteract slot=16 ptr=B4A0 obj=00000000 destroyed=true
F7501-F7551: same — slot 16 has been empty continuously
F7552:        same — still destroyed
```

Slot 16 was last live around F6253 (object_code 0x00020594, a different zone object Tails was actually riding earlier in the act). The `interact=0xB4A0` field on Tails is a *stale* RAM pointer to a long-destroyed slot, not an active ride bridge candidate. The `Solid_Object_Detach`-on-destruction hypothesis cannot produce the F7552 +1 px / x_speed=0 transition because the napalm has been destroyed for ~50 frames by then.

Direct inspection of `Obj_AIZMiniboss` (sonic3k.asm:137222) and the napalm-projectile branch `loc_68C96` (sonic3k.asm:137446) confirms the napalm uses `Add_SpriteToCollisionResponseList` + `Draw_Sprite` (touch-response only) and the Miniboss body uses `Draw_And_Touch_Sprite` — neither calls `SolidObject` / `SolidObject_cont` / `MvSonicOnPtfm`. There is no ROM ride bridge to port for these objects.

**Actual mechanism — terrain wall collision at x=0x1208, y~0x0314**

The recorded sidekick state from F7552 onward shows Tails wedged against an immovable horizontal barrier:

| Frame | s_x   | s_x_sub | s_x_speed | s_y   | s_y_speed | air |
|-------|-------|---------|-----------|-------|-----------|-----|
| 7551  | 0x1205| 0x5900  | 0x0200    | 0x0317| 0xFC90    | 1   |
| 7552  | 0x1208| 0x0000  | 0x0000    | 0x0314| 0xFCC0    | 1   |
| 7553  | 0x1208| 0x0000  | 0x0000    | 0x0310| 0xFCF0    | 1   |
| 7554  | 0x1208| 0x0000  | 0x0000    | 0x030D| 0xFD20    | 1   |
| 7555+ | 0x1208 (pinned) | 0x0000 | 0x0000 | (rising) | (rising) | 1 |

The triple signature — `x_sub` snapped to 0, `x_speed` zeroed, position pinned at 0x1208 across many frames — is the canonical ROM right-wall-collision-while-airborne signature (`Sonic_DoLevelCollision` / `Touch_Floor_Wall` style: wall sensor trips, position is clamped, `x_vel` is zeroed; `y_vel` is *not* zeroed because Tails is still rising). Sonic is well to the left (x=0x11EE-0x11ED, y=0x0339+) and *not* blocked because his y is below the wall top (Tails y=0x0314).

This points to a **sidekick airborne wall-collision parity gap**: ROM resolves a vertical wall at world x=0x1208 in the y range around 0x0314 for Tails on F7552, but the engine's airborne wall sensor for the sidekick does not. The +1 px difference (engine +2, ROM +3) is the wall-clamp delta from the sub-pixel position back to the wall contact line.

Possible engine root causes (none verified yet):

- Missing or wrong wall sensor for the airborne sidekick when `xSpeed > 0` and the player is in the upper portion of the AIZ miniboss arena geometry.
- Slot-order divergence: ROM updates Sonic before Tails, the chunk collision lookup for Tails sees state that the engine has already mutated (e.g. boss arena floor rebuild from an earlier event).
- A solid sub-object of `Obj_AIZMiniboss` acting as a side wall — but the recorded slots 12/13/14 are the flame children at x=0x11F9-0x1202, which is to the *left* of Tails' x=0x1208, not blocking rightward motion.

Implementing a ride-bridge / `Solid_Object_Detach` on `AizMinibossNapalmProjectile` would NOT fix this — the napalm is not the source. Per repo policy ("ROM-cite every change", "NO HACKS") this branch intentionally lands no code change beyond updating this section to correct the prior-round hypothesis.

**Verified ROM facts (this round)**

- `Obj_AIZMiniboss` (sonic3k.asm:137222) and child branches `loc_68C96` (137446) / `loc_68C12` (137396): no `SolidObject*` calls; only `Add_SpriteToCollisionResponseList` and `Draw_And_Touch_Sprite` (touch response, not solid).
- `SolidObject_cont` (sonic3k.asm:41394) reaches `RideObject_SetRide` (41627) only via `loc_1E154`, gated on the calling object having invoked `SolidObject` / `SolidObjectFull` / `SolidObjectTop` first.
- `MvSonicOnPtfm` (sonic3k.asm:41642) is not invoked by any AIZ miniboss branch.

**Concrete next steps (revised)**

1. Extend the trace recorder to log per-frame *terrain wall sensor* probe results around the player (the right-wall sensor return and the resulting `x_pos` clamp + `x_vel` zero) — in particular for the sidekick on F7549-F7553. The current recorder only logs object-side state, not terrain-side sensor trips.
2. Identify the level chunk at world (0x1208, 0x0314) in AIZ1 and verify whether it has a vertical wall in its solidity bitmap that the engine should be honouring at this y range.
3. Audit the engine's airborne side-collision path for the sidekick (`SidekickCpuController` + the player physics step) to confirm it runs the same wall-sensor pass as Player_1, in the same order ROM does.

**Round 3 update — recorder v6.13-s3k extended for AIZ wall-clamp diagnostics (branch `bugfix/ai-aiz-f7552-sidekick-wall`, 2026-04-30)**

Doc-and-recorder-only round. The mission's Phase 1 (extend recorder) and Phase 4 (engine fix or doc-only) landed; Phase 2 (regen the AIZ trace fixture) is deferred to the next round because regen requires BizHawk + the `aiz1_to_hcz_fullrun` BK2 movie running on a Windows desktop, which is outside this agent's reach.

Recorder changes (`tools/bizhawk/s3k_trace_recorder.lua` v6.12-s3k → v6.13-s3k):

- New `terrain_wall_sensor_per_frame` aux event captures both Sonic ($FFFFB000) and Tails ($FFFFB04A) per-frame state in the `[7549,7560]` window of AIZ profile traces (zone-gated to AIZ): `x_pos / x_sub / y_pos / y_sub`, `x_vel / y_vel` (16-bit signed), `angle`, `status / status_secondary / object_control`, `x_radius / y_radius`, `top_solid_bit / lrb_solid_bit` (sonic3k.constants.asm:77-78). Override frame range via `OGGF_S3K_AIZ_WALL_SENSOR_RANGE`.
- `velocity_write_per_frame` upgraded to multi-window semantics (matching `position_write_per_frame`); default ranges = `[3640,3660]` (CNZ F3649) + `[7549,7560]` (AIZ F7552). Override via existing `OGGF_S3K_VELOCITY_WRITE_RANGE` (now also accepts semicolon-separated multi-window form).
- `position_write_per_frame` default ranges extended with `[7549,7560]` and listed in the AIZ profile's `aux_schema_extras` (the hooks were already registered globally; this only widens default coverage).
- Version string bumped to `6.13-s3k` in metadata + startup banner. Existing `aux_schema_extras` for AIZ profile gains `position_write_per_frame` and `terrain_wall_sensor_per_frame` entries.

Java parser is unchanged: the default `StateSnapshot` arm in `TraceEvent.parse` (`src/main/java/com/openggf/trace/TraceEvent.java:781-788`) preserves all unknown event types as fielded snapshots, so the new event is forward-compatible without code changes.

Engine audit (this round) — sidekick airborne wall-collision path:

- `Tails_DoLevelCollision` (sonic3k.asm:28871-29117) computes `quadrant = (GetArcTan(x_vel,y_vel) - 0x20) & 0xC0` and dispatches to one of four wall-clamp branches: `loc_154AC` (0x40, left), default fallthrough (0x00, both walls), `loc_15538` (0x80, both walls + ceiling), `loc_1559C` (0xC0, right wall + ceiling). Every branch that hits `bsr.w CheckRightWallDist` and finds `d1 < 0` runs `add.w d1,x_pos(a0)` + `move.w #0,x_vel(a0)` — i.e. Tails wedges at `x_pos += d1` (negative; ROM penetration) and `x_vel := 0`.
- `CheckRightWallDist` (sonic3k.asm:20188-20214) probes at `d3 = x_pos + $A`, `d2 = y_pos`, height `d6 = 0`, and writes `d2 = -$40` (right-wall mode). i.e. the sensor sits at x_pos+10, y_pos (no Y radius offset).
- Engine analog: `PlayableSpriteMovement.doLevelCollision` (`src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:2030`) → `CollisionSystem.resolveAirCollision` (`src/main/java/com/openggf/physics/CollisionSystem.java:464-536`). Quadrant 0xC0 path calls `doWallCheck(sprite, 1)` (right push sensor index 1) + ceiling + ground. Push sensors are at `±10 X, 0 Y` when airborne (`AbstractPlayableSprite.updateSensorOffsetsFromRadii:3439-3454` + `updatePushSensorYOffset:3464-3477`). Geometry matches ROM exactly.
- Sidekick path: `SidekickCpuController` does not bypass `doLevelCollision`; the sidekick's airborne movement runs through `PlayableSpriteMovement` like the leader. So the wall-sensor pass IS invoked for Tails.
- Existing AIZ probe (`-Ds3k.aiz.aircollisionprobe=true`) is window-gated to centreX `[0x1930..0x1960]`, centreY `[0x0380..0x03E0]` (`CollisionSystem.java:559`) — that window does NOT cover the F7552 incident at `(0x1208, 0x0314)`, so prior probe runs would have missed any sensor result there. Re-enabling the probe with a widened window for `[0x1200..0x1220, 0x0300..0x0330]` is the obvious next-step audit.
- The remaining unknown is whether the `solidity bitmap` of the AIZ chunk at world `(0x1208, 0x0314)` actually contains a right-wall edge that ROM's wall sensor trips on (the engine's chunk lookup uses the same Primary/Secondary collision indices that ROM uses, but a chunk-wise authoring or solidity-bit divergence at this specific tile would silently make the engine's right-push sensor return a positive distance where ROM gets a negative one).

**Concrete next steps (round 4)**

1. Run BizHawk with `s3k_trace_recorder.lua` v6.13-s3k against the existing `aiz1_to_hcz_fullrun` BK2 movie and replace `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/{aux_state.jsonl.gz,metadata.json}` with the regenerated outputs. Verify `physics.csv.gz` MD5 is unchanged (it should be — physics CSV format/content is identical to v6.12-s3k).
2. Inspect the new aux events at F7549-F7560 to (a) confirm ROM's recorded `x_pos = 0x1208` write at F7552 does come from a `Tails_DoLevelCollision` branch PC (one of 0x015402, 0x0154BC, 0x0154EE, 0x015548, 0x015568, 0x015590, 0x0155A8 ranges) rather than from a `SolidObject_cont` branch, and (b) confirm `x_vel := 0` writes pair with the position write on the same frame.
3. Add a focused engine test (or temporarily widen the existing AIZ collision probe window) to log the engine's `pushSensors[1].scan(0,0)` result at F7549-F7560. Compare distance + tile + angle to the ROM-equivalent `(x_pos+10, y_pos)` point.
4. If engine probe returns no wall and ROM does, the divergence is in the AIZ chunk solidity bitmap. Trace back through `Sonic3kLevel.loadChunksWithCollision` to verify that AIZ1 Primary/Secondary collision arrays at this chunk match the ROM's bytes.
5. If engine probe returns a wall but the wall-clamp result differs from ROM, the divergence is in `doWallCheck` resolution — likely a subpixel rounding / `getCentreX()` vs `getX()` mismatch around the +10 sensor offset.

**Round 4 update — regenerated AIZ trace inspected, F7552 isolated to a boundary-clamp + DoLevelCollision combination (branch `bugfix/ai-aiz-f7552-wall-fix`, 2026-04-30)**

Doc-only round. The regenerated v6.13-s3k AIZ fixture (`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/aux_state.jsonl.gz`) now ships the `terrain_wall_sensor_per_frame` events at F7549-F7560 the round-3 recorder added. Direct inspection of those events plus the existing `position_write_per_frame` / `velocity_write_per_frame` data gives a far clearer picture than round 3 had:

`terrain_wall_sensor` Tails per-frame (post-frame state):

| F | x_pos | x_sub | x_vel | y_pos | y_vel | status | airborne |
|----|-------|-------|-------|-------|-------|--------|----------|
| 7549 | 0x1201 | 0x5900 | 0x0200 | 0x031E | 0xFC30 | 0x02 | true |
| 7550 | 0x1203 | 0x5900 | 0x0200 | 0x031B | 0xFC60 | 0x02 | true |
| 7551 | 0x1205 | 0x5900 | 0x0200 | 0x0317 | 0xFC90 | 0x02 | true |
| 7552 | **0x1208** | **0x0000** | **0x0000** | 0x0314 | 0xFCC0 | 0x02 | true |
| 7553-7560 | 0x1208 | 0x0000 | 0x0000 | 0x0310-0x02FF | (rising) | 0x02 | true |

`position_write_per_frame` for Tails (a0=$FFFFB04A) at F7552:

```
x_pos write 1: pc=0x1AB5E val=0x1205   ; MoveSprite (sonic3k.asm:36036) integration result
x_pos write 2: pc=0x14F60 val=0x1207   ; loc_14F5C move.w d0,x_pos(a0) (Tails_Check_Screen_Boundaries clamp)
```

`loc_14F5C: move.w d0, x_pos(a0)` is the unique boundary-clamp instruction in `Tails_Check_Screen_Boundaries` (sonic3k.asm:28446-28451) — it writes whichever of `Camera_min_X_pos+$10` or `Camera_max_X_pos+$128` was just compared, then clears `2+x_pos` (=x_sub) and zeroes `x_vel` and `ground_vel`. The ROM trace therefore unambiguously records that ROM:

1. **Hit the boundary clamp at F7552** with `d0 = 0x1207` (post-clamp x_pos).
2. **Then advanced Tails by +1 px to 0x1208** (final F7552 x_pos), via a write that is NOT logged by the recorder. The ROM-only candidate paths that can produce a `+1` after the boundary clamp without producing a `position_write_per_frame` line are:
   - `Tails_DoLevelCollision`'s `add.w d1, x_pos(a0)` / `sub.w d1, x_pos(a0)` wall pushes (sonic3k.asm:28893, 28900, 28963, 28991, 29053, 29091) — these use `add`/`sub`, not `move`, and the v6.13 recorder only hooks `move.w`/`move.l` to player x_pos addresses.

The natural reading is therefore: ROM's `Tails_Check_Screen_Boundaries` clamps Tails to `x_pos = 0x1207`, then `Tails_DoLevelCollision` runs and `CheckLeftWallDist` returns `d1 = -1` (Tails embedded 1 px in the left wall after the boundary snap), so the `loc_153D6` fallthrough (quadrant 0x00) executes `sub.w d1, x_pos(a0)` (= add 1) and `move.w #0, x_vel(a0)`. Net: end-of-frame x_pos = 0x1208, x_sub = 0, x_vel = 0.

Engine state at F7552 (from `target/trace-reports/s3k_aiz1_context.txt`):

```
ENG: rtn=02 rings=100 st=06 cam=10E0 sub=(C500,B000) ...
   eng-tails-cyl none sidekick=@1207,0314
                                      ^^^^^^ — NO clamp, NO wall push
```

Engine ends F7552 with `tails_x = 0x1207`, `tails_x_speed = 0x0200`, `tails_x_sub` non-zero. So the engine fired **NEITHER** the boundary clamp nor the wall push: Tails simply integrates `MoveSprite_TestGravity` (`x_pos += x_vel*256`) producing a clean 0x1205 + 0x0200 → 0x1207 with sub = 0x7100, and the airborne collision pass adds nothing further.

**Engine boundary-check arithmetic (`PlayableSpriteMovement.doLevelBoundary`, src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:1859-1907):**

```java
int xTotal = (sprite.getCentreX() * 256) + ((sprite.getXSubpixel() >> 8) & 0xFF) + sprite.getXSpeed();
int predictedX = xTotal >> 8;                       // = 0x1207 at F7552 entry
int rightBoundary = camera.getMaxX() + 320 - 24;    // = camera.getMaxX() + 0x128
boolean past = predictedX > rightBoundary;          // requires camera.getMaxX() <= 0x10DF
```

For ROM's clamp result `d0 = 0x1207`, the implied `Camera_max_X_pos` is `0x10DF` (right-side branch) — i.e. the AIZ2 boss-arena right edge is locked one pixel left of where the engine's `camera.getMaxX()` reads. The engine's value at this frame is `0x4640` (the LevelSizes.AIZ2 raw `xend`), because no AIZ2 Sonic3kAIZEvents path lowers `Camera_max_X_pos` (the engine's `updateAiz2SonicResize2` only locks `Camera_min_X_pos`, sonic3k.asm:39073). The engine therefore computes `rightBoundary = 0x4768`, which 0x1207 is far below, and the boundary check is a no-op for Tails at this instant.

**Where ROM sets `Camera_max_X_pos` to ~0x10DF for the AIZ2 boss arena (round 4 finding):**

Searching `docs/skdisasm/sonic3k.asm` for AIZ2 / boss-arena writes to `Camera_max_X_pos` (`Camera_min_X_pos+$2`) found ONE candidate write that's shared with `Camera_min_X_pos`:

- `AIZ1_AIZ2_Transition` `loc_4FE2E` body (sonic3k.asm:104758-104759): `move.l #$100010,d0` / `move.l d0,(Camera_min_X_pos).w`. The 4-byte longword write hits both adjacent words: `Camera_min_X_pos = $0010` AND `Camera_max_X_pos = $0010`. After this, `AIZ2_Resize` raises `Camera_min_X_pos` to `$F50` (sonic3k.asm:39073) but never touches `Camera_max_X_pos`. The disassembly does NOT contain any subsequent S3K-side write that raises `Camera_max_X_pos` back up before the AIZ Mini-boss spawn.

This means ROM's `Camera_max_X_pos` likely stays at a low value (close to the boss arena right edge) for the duration of the AIZ2 boss arena. The exact value `0x10DF` at F7552 would either come from:
1. A residual write we have not located yet (e.g., a 32-bit `move.l` via an indirect path or a `sub.w` shift in an arena-lock object's continuous routine), OR
2. A write performed during the `aiz2_reload_resume` checkpoint path (the trace's checkpoint immediately before F7552 is `cp aiz2_reload_resume z=0 a=1 ap=0 gm=12`, which loads via `Save_Level_Data` / a star-post resume — `loc_1C522` in sonic3k.asm:38925-38933 — and the resume sequence may set its own boundary state different from the seamless-transition path).

The trace's `aiz_boundary_state_per_frame` events only cover F4660-F4679 (the AIZ1→AIZ2 fire transition diagnostic window from a previous round) and do NOT span F7549-F7560, so the recorder cannot currently confirm `Camera_max_X_pos` directly at F7552. Extending `aiz_boundary_state_per_frame` to also cover the F7549-F7560 window (using the same multi-window pattern v6.13 added for `velocity_write_per_frame` / `position_write_per_frame`) would make the F7552 boundary state directly visible in the next regen.

**Concrete next steps (round 5)**

1. Extend `tools/bizhawk/s3k_trace_recorder.lua`'s `aiz_boundary_state_per_frame` hook to ALSO emit at F7549-F7560 (currently F4660-F4679 only), so the regenerated trace records `Camera_min_X_pos / Camera_max_X_pos / Camera_target_min_X_pos / Camera_target_max_X_pos` at the F7552 incident frame. This conclusively answers what `Camera_max_X_pos` is in ROM at the boundary-clamp moment.
2. With `Camera_max_X_pos` in hand at F7552, the fix is mechanical:
   - If ROM has `Camera_max_X_pos = 0x10DF` (or any tight AIZ2 boss-arena right lock), update `Sonic3kAIZEvents.updateAiz2SonicResize2` to set `camera().setMaxX(<that value>)` on the same trigger that sets `setMinX(0xF50)`. Add the symbolic constant alongside `AIZ2_SONIC_RESIZE2_LOCK_X` and add a ROM-cite to the assignment.
   - If ROM has `Camera_max_X_pos = 0x4640` (i.e. unchanged, opposite of the round-4 hypothesis), then the `+1` divergence must be coming purely from `Tails_DoLevelCollision`'s wall sensor returning a different value than the engine's `pushSensors[1].scan(0,0)` at world `(0x1207+10, 0x0314)` = `(0x1211, 0x0314)`. In that case the audit moves to the chunk-solidity bitmap (round-4 step 4 in the prior plan).
3. Keep `cam=10E0` distinct from `Camera_max_X_pos` in the trace report context line — the existing `target/trace-reports/s3k_aiz1_context.txt` shows `cam=(10E0,02B8)` (Camera_X_pos / Camera_Y_pos), NOT the level-edge bounds. The boundary state event proposed in step 1 fixes that confusion at the recorder level.

**Cross-game parity (round 4 verification):**

- AIZ baseline (`TestS3kAizTraceReplay.replayMatchesTrace`): 977 errors, first error at F7552 (`tails_x mismatch expected=0x1208 actual=0x1207`) — unchanged from rounds 1-3.
- CNZ first-error stays at F7919 (Clamer spring-relatch widening; different bug, separate branch).
- S1 GHZ / S1 MZ1 / S2 EHZ trace replay tests not re-run this round (no engine change, no expected delta).

This round documents only — no engine code changed — because the round-4 `terrain_wall_sensor_per_frame` data does narrow the F7552 cause to one of two well-defined paths (boundary clamp boundary-value gap vs wall-sensor solidity gap), but distinguishing them definitively still requires either (a) the round-5 recorder extension above or (b) a temporary engine probe gated to F7549-F7560 logging `camera.getMaxX() / pushSensors[0/1].scan(0,0)` at every frame. The recorder-extension path is preferred because it stays comparison-only on the engine side and gives ROM-side ground truth without engine instrumentation.

---

## CNZ F7919 — Clamer spring-child relatch widening localised; fix surfaces deeper F619 dispatch issue (diagnosis only)

**Status:** documented; no code change landed this round. Branch
`bugfix/ai-cnz-f7919-tails-touch-timing`. Continues from the prior
spring-child collision-box audit (commit `e3da47c21`).

**Round 1 finding — relatch widening is the F7918 dispatch source.**

A targeted probe inside `ObjectManager.processMultiRegionTouch`
gated on the F7910..F7925 frame band confirmed the engine's F7918
spring fire on Tails comes from a pre-existing engine-only
widening of the spring child collision box on relatch:

```
src/main/java/com/openggf/game/sonic3k/objects/ClamerObjectInstance.java
SPRING_COLLISION_FLAGS         = 0x40 | 0x17   ; first-hit only
SPRING_RELATCH_COLLISION_FLAGS = 0x40 | 0x12   ; <-- after first fire
```

`Touch_Sizes[$12]` = (8, 16) → 16x32 box. `Touch_Sizes[$17]` = (8,
8) → 16x16 box. The probe captured at F7918 with Tails airborne
+ rolling at centre `(0x0C8E, 0x044F)`, yR=14 (rolling):

```
cnz-probe f=7918 region=(0C98,0470) sz=$0A w=16 h=8 cat=ENEMY  overlap=false   ; parent (size $0A)
cnz-probe f=7918 region=(0C98,0468) sz=$12 w=8  h=16 cat=SPECIAL overlap=true  ; <-- spring (relatch widening)
```

The F7918 fire fires from the wider `$12` box, not the `$17`
first-hit box. ROM `word_89136` (sonic3k.asm:186008-186010) and
`SetUp_ObjAttributes3` (sonic3k.asm:185943-185951) write
`collision_flags=$D7=$C0|$17` once at child spawn; ROM
`loc_890AA` / `loc_890C8` / `loc_890D0` (sonic3k.asm:185953-185973)
do not modify `collision_flags`, so the relatch box stays at
`$17` for every fire. The engine widening is not ROM-cited.

**Round 2 attempt — landing the obvious fix surfaces a deeper
F619 dispatch issue.**

Reverting to a single-flag spring (`SPRING_COLLISION_FLAGS = $40|$17`
on every fire) advances the F7919 first error past F7919 but
introduces a new first error at F621 with 3293 total errors (up
from 2757). F621 trace expects Sonic to re-fire the slot 5
Clamer at world `(0x0578, 0x0688)` after a 1-frame cooldown:

```
F619: y=0x067E, y_speed=-0x0800   <- first fire (post-fire state)
F620: y=0x0676, y_speed=-0x07C8   <- gravity decay only
F621: y=0x0674, y_speed=-0x0800   <- ROM re-fires here
```

With ROM `Touch_Sizes[$17]` = (8, 8) and Sonic post-physics at
F621 at y=0x066E (yR=14, rolling), the player box bottom is
`0x066E - 11 + 22 = 0x0679`; the spring box top is
`0x0688 - 8 = 0x0680`. The geometric overlap test
(`dy = 0x0680 - 0x0663 = 29 > 22 = playerHeight`) returns NO
overlap — but the trace shows ROM does fire this frame. With the
size-$17 box the engine cannot reach overlap at F621 either, so
the engine matches the ROM-accurate box test geometrically but
diverges from the trace's recorded behaviour.

The pre-existing widening to `$12` was masking this divergence:
the wider 8x16 spring box does overlap at engine-f=620 (centre
`(0x0583, 0x066E)`, dy=0x15=21 < 22), so the engine refired the
spring at the same trace frame ROM recorded as the second fire,
and the F619 cascade stayed quiescent.

**Why ROM fires at F621 with no geometric overlap is unresolved.**

Possible upstream causes (none verified yet, all require
additional probe work):

1. **Sub-frame spring-child slot ordering.** ROM
   `Add_SpriteToCollisionResponseList` (sonic3k.asm:21200) is
   called from `Child_DrawTouch_Sprite` AFTER `loc_890AA`'s
   collision check, populating the list for the *next* frame's
   `Touch_Loop`. `Obj_ResetCollisionResponseList`
   (sonic3k.asm:8467) at slot 3 (`Reserved_object_3`) clears the
   list each frame *before* the spring child re-adds. If ROM's
   spring child position used by `Touch_Loop` differs from the
   engine's because of a 1-frame staleness in
   `Collision_response_list` membership, the geometric test could
   pass on a Sonic position that has not yet integrated F=621's
   physics step. None of this is currently reproducible from the
   probe data alone.
2. **Sonic post-physics y at F=621 different in ROM than the
   trace's recorded post-fire y suggests.** `MoveSprite` /
   `MoveSprite_TestGravity` (sonic3k.asm:36032-36082) integrates
   the *old* `y_vel` into `y_pos` and applies gravity to `y_vel`
   *after*. From F=620 end (y=0x0676, y_sub=0x3200, y_vel=-0x07C8),
   the post-physics y-pos is 0x066E with y_sub=0x6A00. Adding
   the `addq.w #6, y_pos` from `sub_890D8` recovers the trace's
   F=621 y=0x0674. The geometric reconstruction is internally
   consistent — yet ROM still fires, meaning the engine's
   per-frame Sonic position fed into `processMultiRegionTouch`
   does not match the position ROM's `Touch_Loop` evaluates at
   the same instant.
3. **A separate solid-object / ride / surface effect not yet
   audited near `(0x0578, 0x0690)`** could nudge Sonic upward
   into the box at the dispatch instant. The engine's
   `eng-near` log around F619-622 shows the slot 4 horizontal
   spring at `(0x05C8, 0x06B0)` and slot 5 Clamer at
   `(0x0578, 0x0690)`; no other Special / Solid neighbours are
   visible.

**ROM cite (revised, all sonic3k.asm)**

- `word_89136` (186008-186010): spring child attributes, only
  `collision_flags=$D7` feeds `Touch_Loop`. Render bbox
  width=8 / height=4 is for `Sprite_OnScreen_Test`, not the
  touch box.
- `Touch_Sizes` (20713-20770): index `$17` = (8, 8); index `$12`
  = (8, $10).
- `loc_890AA` / `loc_890C8` / `loc_890D0` (185953-185973): fire,
  1-frame cooldown, restore.
- `Add_SpriteToCollisionResponseList` (21200): single-byte
  append, no clear; relies on `Obj_ResetCollisionResponseList`
  (8467) at `Reserved_object_3`.
- `MoveSprite` (36032-36042) and `MoveSprite_TestGravity`
  (36068-36082): integrate-then-gravity ordering for player
  physics step.

**Verification rules (unchanged)**

- `TestS3kCnzTraceReplay#replayMatchesTrace` first error advances
  past F7919 without regressing earlier in the trace.
- `TestS3kAiz1Replay` first-error stable at F7381 / F7552.
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- S3K-required tests
  (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`)
  GREEN.
- Cross-game spring/touch-response parity gated via the narrowest typed
  `GameRules` rule or object-local owner, never `gameId ==`.

**Why no code change this round.**

The localised widening (`$12` → 16x32 spring relatch) is
clearly engine-only and not ROM-cited; reverting it to `$17` is
the obvious fix. But landing it alone (with or without
adjusting `CHILD_REENABLE_DELAY_FRAMES`) regresses the trace by
536 errors (2757 → 3293) because the prior widening was
papering over an upstream divergence at the F619 spring re-fire
window that is much harder to localise: ROM fires at F=621 with
Sonic at y=0x066E and a 16x16 spring box that does not
geometrically overlap the player rectangle. Until the F=621
ROM-fire mechanism is identified (sub-frame
`Collision_response_list` staleness, alternate Touch_Loop
input position, or a missing nudge), the engine cannot match
the trace at both F619 and F7918 without picking which
divergence to mask. Per repo policy ("ROM-cite every change",
"NO HACKS"), the relatch widening fix is documented here for a
follow-up round that addresses the F621 dispatch upstream first.

**Concrete next steps**

1. **Capture per-frame spring child position + collision flags +
   collision_response_list membership** at engine-f=619-622
   from a one-shot probe inside the spring child object code,
   to localise whether the ROM-fire at F=621 corresponds to a
   list state, position, or sensor that the engine renders
   differently.
2. **Compare ROM and engine Sonic physics step ordering** for
   the rolling-air case at F=621. Specifically verify whether
   the engine integrates `y_vel` into `y_pos` *before* or
   *after* `Touch_Loop`-equivalent dispatch. ROM ordering is
   `MoveSprite` (integrate+gravity) → `Player_JumpAngle` →
   `SonicKnux_DoLevelCollision` → `TouchResponse` (Touch_Loop)
   per `Sonic_MdAir` (sonic3k.asm:22350-22362).
3. **Once the F=621 dispatch source is localised**, land the
   relatch box correction (remove `SPRING_RELATCH_COLLISION_FLAGS`,
   always use `SPRING_COLLISION_FLAGS = $40|$17`) together with
   the upstream fix, in a single commit, and confirm
   `replayMatchesTrace` advances past F7919.

## CNZ F=621 Clamer re-fire — ROM dispatch path narrowing (diagnosis only, round 2)

**Status:** documented; no code change landed this round. Branch
`bugfix/ai-cnz-f621-clamer-dispatch`. Continues from the prior
F619 dispatch surfacing round (CNZ F7919 entry, this file).

**ROM-side dispatch path traced.** `Check_PlayerCollision`
(sonic3k.asm:179904-179916) is **NOT** a geometric overlap test.
It reads `collision_property(a0)` ($34) and returns Z (the
"no-fire" branch) when that byte is 0. The byte is written by
`Touch_Special` (sonic3k.asm:21162-21194) inside `Touch_Loop`
when a player rect overlaps a SPECIAL-flagged object on the
`Collision_response_list`. So `loc_890AA` only fires when:

1. The spring child was on `Collision_response_list` at the
   moment Sonic's `TouchResponse` ran this frame, AND
2. The geometric overlap test inside `Touch_Loop` succeeded.

**`Collision_response_list` membership timeline.** ROM list
populates in slot order *during* the frame, after slot 3
(`Reserved_object_3` / `Obj_ResetCollisionResponseList`,
sonic3k.asm:8467) clears it. Slot 0 (`Sonic`) reads it via
`TouchResponse` (sonic3k.asm:22022) **before** later slots
re-populate it. So at frame F, Sonic's `TouchResponse` walks
the list **populated at frame F-1**.

`loc_890AA` (the spring-child fire path, sonic3k.asm:185953)
calls `bsr Check_PlayerCollision; beq loc_890C4 ; ...; loc_890C4: jmp Child_DrawTouch_Sprite`.
The `jmp Child_DrawTouch_Sprite` (sonic3k.asm:178048-178053)
calls `Add_SpriteToCollisionResponseList` (sonic3k.asm:21200),
**so the spring child re-adds itself to the list every frame
it runs `loc_890AA`**.

`loc_890C8` (the cooldown frame, sonic3k.asm:185965-185968) is
`subq.w #1, $2E(a0); bmi loc_890D0; rts`. **No
`Child_DrawTouch_Sprite` call.** So during the cooldown frame
the spring child is **not** added to the list; the next
frame's Sonic `TouchResponse` cannot fire it.

**Per-frame chain at engine F=619-622:**

| Frame | Spring routine on entry | Spring populates list? | Sonic `Touch_Loop` walks list from |
|-------|-------------------------|------------------------|-------------------------------------|
| F=619 | `loc_890AA`             | yes (re-add)           | F=618 list (spring present)         |
| F=620 | `loc_890C8` (cooldown)  | **no**                 | F=619 list (spring present)         |
| F=621 | `loc_890AA` (cooldown drained, reset) | yes        | F=620 list (**spring absent**)     |
| F=622 | `loc_890C8` (cooldown)  | no                     | F=621 list (spring present)         |

So under this analysis, ROM should fire at F=619 and F=620, NOT
F=619 and F=621 — but the trace records fires at F=619 and
F=621 (yspd `0xF800` rows in `physics.csv.gz` 0x26B and 0x26D).

**Two possibilities for the discrepancy:**

1. **`loc_890C8` cooldown counter starts at 1, not 0.** If
   `$2E(a0)` is initialised non-zero somewhere, the cooldown
   takes 2 frames instead of 1. Then `loc_890C8` runs at
   F=620 *and* F=621 (decrement 1→0 then 0→-1), and
   `loc_890AA` (with `Child_DrawTouch_Sprite`) doesn't run
   again until F=622. Sonic at F=623 walks the F=622 list
   with the spring present; geometric overlap at F=623's
   post-physics y. This shifts the second fire to F=623 in
   the schedule — but the trace records it at F=621 (two
   frames after F=619). So this hypothesis does NOT match
   trace either.
2. **`Sprite_CheckDeleteTouchSlotted` (sonic3k.asm:179081)
   adds the *parent* object to the list every frame.** Parent
   (slot 4) at F=620 has `routine=$06` (auto-close) and
   `collision_flags=$0A`. `Touch_Sizes[$0A]` = (0x18, 0x0C) =
   48x24 box centred at parent y=0x0690. Player rect at
   F=621 post-physics y=0x066E, height=22, top=0x0663,
   bottom=0x0685. Parent box top = 0x0690 - 0x0C = 0x0684,
   bottom = 0x0690 + 0x0C = 0x069C. **Player bottom
   (0x0685) overlaps parent box top (0x0684) by 1 pixel.**
   Parent has SPECIAL category bits ($C0)? **No** — `$0A`
   is bare size; high bits of `collision_flags` are 0
   meaning the parent is `Touch_Enemy`, not
   `Touch_Special`. Touching an enemy hurts/damages but
   doesn't fire a spring. So the parent overlap doesn't
   explain the spring fire either.

**Recorder gap.** None of the captured aux events expose the
`Collision_response_list` membership at the moment Sonic's
`TouchResponse` runs (the diagnostic input we'd need to
distinguish the hypotheses). The `slot_dump` events at
F=619/620/621 only capture `(slot, object_code)` pairs, not
the response-list contents. Without that data, the F=621 fire
mechanism cannot be localised from this branch.

**Engine instrumentation attempt.** A targeted `System.err`
probe inside `ObjectManager.processMultiRegionTouch` and
`ClamerObjectInstance.update` (gated on `ClamerObjectInstance`
type and frame range) was added to capture the engine's
playerX/Y, player rect, and spring child collision flags at
F=619-625. The probe **never fired** when the trace test ran:
no Clamer instance was constructed in the early-frame window,
and no Clamer was on the touch-response list at any frame
where the probe would gate. The probe was reverted before
commit. This is consistent with the engine's spawn windowing
not yet placing the slot 5 Clamer at engine-frame=619 (Sonic
arrived at the Clamer's x in the same frame band, so the
windowing margin matters).

**Why the engine matches the trace at F=619/F=621 today.**
The engine's existing spring-relatch widening
(`SPRING_RELATCH_COLLISION_FLAGS = $40|$12`, 16x32 box)
catches the player at engine-equivalent y values that are
~6-8 px off ROM-y but inside the wider box, producing fires
on the **same** trace frames the ROM fires. This is the
"papering" the prior round documented. The ROM-correct $17
box (16x16) in the engine still does not overlap at engine
F=621 because the engine's player rect there is the same
geometric situation as ROM's, and the engine geometry math
matches my paper analysis above (no overlap).

**Required next-round recorder extension.** Capture the
following per-frame from BizHawk Lua at the moment Sonic's
`TouchResponse` is about to walk `Collision_response_list`:

- `Collision_response_list` count (word at `$F808`).
- For each entry: object slot index, `object_code`,
  `collision_flags`, `(x_pos, y_pos)`.
- For each *Clamer-spring-child slot* (any object whose
  `(a0)` is `loc_890AA` / `loc_890C8` / `loc_890D0`):
  `collision_property(a0)` ($34) **before** Sonic's
  `TouchResponse` runs and **after** it returns.

That data localises whether the F=621 fire is:

1. The spring child *was* on the list at F=620 (cooldown
   notwithstanding) — meaning the ROM's `loc_890C8` flow is
   not what the disasm reads, OR
2. The `collision_property` byte is being written by
   another path (e.g., `HyperTouch_Special` at
   sonic3k.asm:21401-21402 which `ori.b #3,
   collision_property(a1)` directly) — meaning the spring
   child fires from an alternate dispatcher.

Until that recorder extension lands, the relatch correction
cannot be safely applied: every variant of the engine-side
fix tested in the previous two rounds either masks F=621
(the existing `$12` box) or regresses 536 errors when the
F=621 dispatch is left unmodelled (the ROM-correct `$17`
box).

**Doc-only because:** every code path we know to try has
been tried; the remaining work is recorder/Lua-script
extension, which is out of scope for a single bugfix branch
that touches only `ClamerObjectInstance`.

**ROM cite (additional, all `sonic3k.asm`):**

- `Check_PlayerCollision` (179904-179916): consumes
  `collision_property(a0)`; does not perform geometric
  overlap.
- `Touch_Special` (21162-21194): writes
  `collision_property(a1)` for size indices $07/$0A/$0C/
  $15/$16/$17/$18/$21 inside `Touch_Loop`.
- `Sprite_CheckDeleteTouchSlotted` (179081-179091): every
  on-screen slotted parent's `Add_SpriteToCollisionResponseList`
  call.
- `Process_Sprites` / `sub_1AAFC` (35965-35996): slot scan
  order (slot 0 = Sonic, slot 1 = Tails, slot 3 = list
  reset, slot 4+ = dynamic).
- `Sonic_Control` (21947-22025): `MoveSprite_TestGravity` →
  `Sonic_Display` → `Animate_Sonic` → `TouchResponse`. Touch
  runs on Sonic's post-physics position.

**Verification rules (unchanged from prior round):**

- `TestS3kCnzTraceReplay#replayMatchesTrace` first error
  stays at F7919 (2757 errors); revert any probe before
  commit.
- `TestS3kAizTraceReplay#replayMatchesTrace` first-error
  stable at F7552 (977 errors).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- Required headless tests
  (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`)
  GREEN.
- Cross-game spring/touch-response parity gated via the narrowest typed
  `GameRules` rule or object-local owner, never `gameId ==`.

## CNZ F=621 Clamer re-fire — recorder gap closed; ROM mechanism localised (diagnosis only, round 3)

**Status:** documented; no engine code changed this round. Branch
`bugfix/ai-cnz-f621-recorder-fix`. Recorder extended (v6.15-s3k) and
CNZ trace regenerated. Engine fix attempt landed and reverted because
the engine's update/touch ordering shifts the deferred fire one frame
relative to ROM. Continues from CNZ F=621 dispatch narrowing round 2.

**Recorder extension (v6.15-s3k).** Added two aux events to
`tools/bizhawk/s3k_trace_recorder.lua`:

- `collision_response_list_per_frame` -- hooked at `Touch_Process`
  entry (`0x10440`, sonic3k.asm:20655-20657, ROM bytes
  `49 F8 E3 80 3C 1C 67 14 32 5C` verified at offset 0x010440).
  Snapshots the full Collision_response_list ($FFFFE380,
  sonic3k.constants.asm:330) contents (count word + per-entry
  slot/object_code/collision_flags/x/y/collision_property) plus
  any OST slot whose object_code matches `loc_890AA` /
  `loc_890C8` / `loc_890D0` (sonic3k.asm:185953/185965/185971).
  In practice the BizHawk Genplus-gx `event.onmemoryexecute` hook at
  this ROM PC arms but only fires sporadically during a CNZ trace
  (one hit observed at trace_frame=8188 in a verification run with a
  diagnostic first-hit gate); the value is in being able to capture
  the BEFORE state when the hook does land. The end-of-frame poll
  below is the load-bearing diagnostic.
- `collision_response_list_end_of_frame` -- end-of-frame polling
  fallback. Per ROM Process_Sprites slot order (sonic3k.asm:35965-
  35996; slot 3 `Reserved_object_3` clears the list before slots 4+
  re-populate it), the list at end-of-frame F is what Sonic and Tails
  walk at frame F+1, since slot 0 = Sonic and slot 1 = Tails read the
  list before slot 3 clears it. Captures the same per-entry state
  plus each spring child's collision_property and the cooldown counter
  byte at `$2E`.

Default frame window F=618-624, zone-gated to CNZ (zone=3) only.
Override via `OGGF_S3K_CRL_RANGE`. Diagnostic-only; the comparator
must NEVER hydrate engine state from these events. Trace regenerated
2026-04-30; physics.csv bit-identical with the prior fixture, aux
schema extras list updated to include the new events.

**ROM mechanism localised from the new events.** Per-frame data
captured at end-of-frame (CNZ F=618-624):

| Frame | Spring slot 5 routine | List contains spring? | spring cprop ($29) | $2E cooldown |
|-------|-----------------------|-----------------------|---------------------|--------------|
| 618   | `loc_890AA`           | YES (cflags $D7)      | 0x00                | 0x00         |
| 619   | `loc_890C8`           | YES (cflags $D7)      | 0x00                | 0x00         |
| 620   | `loc_890AA`           | NO                    | **0x01**            | 0xFF         |
| 621   | `loc_890C8`           | YES                   | 0x00                | 0xFF         |
| 622   | `loc_890AA`           | NO                    | 0x00                | 0xFF         |
| 623+  | `loc_890AA`           | YES (cflags $D7)      | 0x00                | 0xFF         |

The F=620 row is the smoking gun. The spring child runs `loc_890C8`
during F=620, drains `$2E` (subq.w underflow to 0xFFFF, bmi taken,
loc_890D0 rewrites `(a0)=loc_890AA`), and **does NOT call
`Add_SpriteToCollisionResponseList`** (loc_890C8/D0 paths skip
`Child_DrawTouch_Sprite`). Yet at end of F=620, the spring child's
`collision_property` byte is `0x01`. The only writer of that byte is
`Touch_Special` (sonic3k.asm:21162-21194) inside `Touch_Loop`, which
means slot 5 was on Sonic's F=620 walk -- the end-of-F=619 list. The
list at end-of-F=619 contains slot 5 with `collision_flags=$D7`
because at F=619 the spring fired (loc_890AA falls through to
`loc_890C4: jmp Child_DrawTouch_Sprite` at sonic3k.asm:185961-185962)
and `Child_DrawTouch_Sprite` (sonic3k.asm:178048-178053) calls
`Add_SpriteToCollisionResponseList` **after the fire**.

That `cprop=0x01` set during F=620 by `Touch_Special` is exactly the
nonzero byte that `loc_890AA` consumes at F=621
(`bsr Check_PlayerCollision; beq loc_890C4`,
sonic3k.asm:185953-185955). loc_890AA at F=621 reads cprop=0x01 (>0),
clears it via Check_PlayerCollision (sonic3k.asm:179904-179916), and
falls into `move.l #loc_890C8, (a0); bsr sub_890D8` to apply the
spring launch. **No geometric overlap is required at F=621** -- F=620's
overlap latched the cprop, and the cooldown frame preserved it across
the routine transition.

**Why the prior round's "no overlap at F=621" check was a false trail.**
The earlier paper analysis correctly observed that Sonic's
post-physics rect at F=621 (y_pos=0x0674, y_radius=14, rect
[0x0669, 0x067F]) does NOT overlap the spring's $17 box at
[0x0680, 0x0690]. ROM does not need an F=621 overlap because cprop is
already nonzero from F=620. F=620's overlap is by 1 px: Sonic at
y=0x0676 -> rect [0x066B, 0x0681]; spring rect [0x0680, 0x0690];
overlap [0x0680, 0x0681].

**Engine fix landing constraints.** The fix has to:

1. Always expose the spring's `collision_flags=$D7` ($40 | $17 / 8x8
   box) -- never the engine-only `$12` widening.
2. Treat the box as live across the cooldown frame (F=620 in the
   reference) so `Touch_Special`-equivalent overlap can latch a
   cprop flag.
3. Consume the latch on the next fire-ready update (F=621) and fire
   the spring with the recorded ROM physics.

**Why no engine fix landed this round.** The engine processes object
updates BEFORE player touch responses each frame
(`ObjectManager.update` -> `runExecLoop` then `touchResponses.update`),
whereas ROM's `Process_Sprites` runs Sonic (slot 0, including
`TouchResponse`) BEFORE the spring child (slot 5). A naive deferred-
fire latch (set during touch, fired on next update) shifts the engine
fire frame one frame later than ROM (engine fires at F=620 and F=622
when ROM fires at F=619 and F=621), because the engine's touch at F
is consumed by the engine's update at F+1, while ROM's touch at F is
consumed by the spring update later in the same frame F. A targeted
attempt with `SPRING_COOLDOWN_FRAMES=2` and the deferred latch was
implemented and reverted: it produced a new first error at F=621
(`x_speed mismatch expected=0x0800 actual=0x07E8`, 3925 errors), one
frame off from the ROM fire instant.

To land the fix safely, one of:

1. **Refactor the engine so spring child update runs after Sonic's
   TouchResponse**, matching ROM's slot ordering. Invasive because it
   requires either a per-object "deferred update" pass that runs after
   touch, or moving the Clamer spring child into a slot that the
   engine processes after the player. The shared engine flow currently
   processes all objects in one pass before touch.
2. **Simulate `Touch_Special` deterministically inside the spring
   child update** (after fire/cooldown logic), reading the player's
   post-physics rect and writing the latch synchronously. This is the
   closest to ROM's slot 0 -> slot 5 ordering without engine-wide
   reordering, but it duplicates the geometric-overlap computation in
   the spring-child code path.
3. **Per-Clamer post-touch hook** that runs AFTER `touchResponses.update`
   and consumes any latched cprop. Less invasive than (1); preserves
   the engine's existing object update pass.

Each of those is more invasive than a single-flag tweak in
`ClamerObjectInstance`, so this round commits the recorder + trace
regeneration + ROM-mechanism localisation only. The relatch widening
(`SPRING_RELATCH_COLLISION_FLAGS = $40 | $12`, 16x32 box) remains in
place: it is engine-only and not ROM-cited, but it papers over the
ordering divergence so the trace replay test stays at the F=7919
baseline (2757 errors). The widening is documented as the known
divergence to remove together with one of the three fixes above.

**ROM cite (additional, all `sonic3k.asm`):**

- `Touch_Process` entry hook at ROM offset `0x10440` (lea/move.w/beq.s
  sequence at sonic3k.asm:20655-20657, bytes `49 F8 E3 80 3C 1C 67 14
  32 5C` verified via `RomOffsetFinder search-rom 49F8E380` -- returns
  three matches, only the 0x010440 site falls through into
  `Touch_Loop`).
- `Collision_response_list` RAM address `$FFFFE380`, $80 bytes
  (sonic3k.constants.asm:330; walked from RAM_start: Chunk_table $8000
  + Level_layout $1000 + Block_table $1800 + HScroll_table $200 +
  Nem_code_table $200 + Sprite_table_input $400 + Object_RAM $1FCC +
  pad $14 + Conveyor_belt_load_array $E + pad $12 + Kos_decomp_buffer
  $1000 + H_scroll_buffer $380 = $E380).
- `collision_property` field offset `$29` (sonic3k.constants.asm:36);
  the prior round's `$34` was a transcription error.
- `Touch_Special` writes `collision_property(a1) += 1` for size
  indices `{$06, $07, $0A, $0C, $15, $16, $17, $18, $21}`
  (sonic3k.asm:21165-21194). Spring child uses index $17, so it IS
  in the write set, and `Touch_Special` writes its cprop on every
  overlap regardless of routine state.
- `Add_SpriteToCollisionResponseList` (sonic3k.asm:21200-21209):
  appends `(a0)` low word to the list.
- `Child_DrawTouch_Sprite` (sonic3k.asm:178048-178053): calls
  `Add_SpriteToCollisionResponseList` then `Draw_Sprite`. Reached
  from `loc_890C4` after `loc_890AA`'s fire-or-skip branch
  (sonic3k.asm:185961).
- `loc_890C8` cooldown (sonic3k.asm:185965-185968):
  `subq.w #1, $2E(a0); bmi.s loc_890D0; rts`. Does **not** modify
  `collision_flags` and does **not** call `Child_DrawTouch_Sprite`.
- `Check_PlayerCollision` (sonic3k.asm:179904-179916): reads
  `collision_property(a0)` byte, returns Z when zero, otherwise
  clears it and chooses the launch direction.

**Verification rules (unchanged):**

- Recorder + trace regen are diagnostic-only. `physics.csv.gz`
  identical between old and new fixtures (verified via `cmp` of the
  decompressed CSVs).
- `TestS3kCnzTraceReplay#replayMatchesTrace` first error stays at
  F7919 (2757 errors); the engine's spring widening is unchanged
  this round.
- `TestS3kAizTraceReplay` first-error stable at F7552 (977 errors).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- Required headless tests (`TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`) GREEN.

## CNZ F=621 Clamer re-fire — Touch_Special cprop latch landed (round 4, fixed)

**Status:** Engine fix landed on branch `bugfix/ai-cnz-f621-collision-list-fix`.
Continues from round 3. CNZ first error advances F7919 -> F7923; F=619-625
is now clean (zero errors).

**ROM mechanism (re-confirmed from regenerated v6.15-s3k aux events).**
Direct inspection of `collision_response_list_end_of_frame` for CNZ F=618-624
in `src/test/resources/traces/s3k/cnz/aux_state.jsonl.gz`:

- F=618: spring slot 5 in list, code=`loc_890AA`, cprop=0, $2E=0.
- F=619: spring slot 5 in list, code=`loc_890C8`, cprop=0, $2E=0 (post-fire).
- F=620: spring slot 5 NOT in list, **cprop=0x01** (Touch_Special wrote it
  during F=620 while the spring was still in the F=619 list), $2E=0xFF.
- F=621: spring slot 5 in list, code=`loc_890C8`, cprop=0 (Check_PlayerCollision
  consumed the F=620 latch), $2E=0xFF (post second-fire).
- F=622+: pattern repeats, cprop=0 throughout once Sonic stops overlapping.

The F=620 row is the smoking gun: spring is in cooldown (`loc_890C8` ran the
prior frame), `Add_SpriteToCollisionResponseList` skipped the slot, but
Touch_Special on the previous-frame list latched cprop=0x01 anyway. ROM
F=621's `loc_890AA` consumes the latch via `Check_PlayerCollision`
(sonic3k.asm:179904-179916; `tst.b collision_property(a0); beq.s` returns
Z=0, then `clr.b collision_property(a0)`), fires, sets `(a0)=loc_890C8`,
and tail-calls `Child_DrawTouch_Sprite` so the slot rejoins the list at
end-of-F=621. **No geometric overlap is required at F=621; the cprop latch
from F=620 is what fires the spring.**

**Engine fix shape (`ClamerObjectInstance`).** A three-state spring-child
state machine mirrors the ROM `(a0)` cycle, plus a `springCprop` boolean
that mirrors the ROM `collision_property` byte:

| Engine state    | ROM equivalent                         | In Collision_response_list? |
|-----------------|----------------------------------------|-----------------------------|
| `LIVE`          | `(a0) = loc_890AA`                     | yes (Child_DrawTouch_Sprite via loc_890C4)  |
| `COOLDOWN_DRAIN`| `(a0) = loc_890C8` post-fire frame     | no  (subq+bmi+rts skips the draw)           |
| `COOLDOWN_DONE` | `(a0) = loc_890AA` after `loc_890D0`,  | no  (the cooldown frame's draw was skipped) |
|                 | one frame before re-listing            |                                             |

`getMultiTouchRegions()` exposes the spring rect only when
`springInListLastFrame == true`, which mirrors Sonic's touch walk reading the
end-of-previous-frame list. `onTouchResponse` fires immediately when state is
LIVE (matching engine's pre-update touch ordering) and otherwise just sets
`springCprop = true`. `advanceSpringRoutine` consumes the latch on the next
non-cooldown update, matching ROM's slot-5-after-slot-0 ordering without an
engine-wide reorder. The relatch widening (`SPRING_RELATCH_COLLISION_FLAGS =
$40 | $12`, 16x32 box) and the `springReenableFrame` mechanism are removed --
the spring rect now uses ROM-correct cflags `$D7` (`$40 | $17`, 8x8) at all
times.

The Clamer also overrides `usesS3kTouchSpecialPropertyResponse() = true` so
the engine's `decodeCategory` recognises `cflags=$D7` as SPECIAL via the
S3K Touch_Special property index list (sonic3k.asm:21165-21194). Without
this hook the `$C0` high bits would decode to BOSS and the spring fire
would be filtered out by the `category != SPECIAL` guard in
`onTouchResponse`.

**Test fixture update.** `TestClamerObjectInstance.springChildUsesRomOffsetAndLaunchesPlayer`
gained an explicit `clamer.update(0x026E, player)` between the second
`onTouchResponse(0x026E)` call and the launch-value asserts. The ROM-accurate
sequence (touch latches cprop while in cooldown, the next non-cooldown
update consumes the latch and fires) requires the update phase to run
between the second touch and the asserts.

**Verification (this round, branch HEAD):**

- `TestS3kCnzTraceReplay#replayMatchesTrace`: first error advances F7919 ->
  F7923 (2767 errors). F=619-625 zero errors. The new first error is
  `tails_x_speed` etc. at F=7923, a fresh downstream divergence in the
  Tails-on-platform window (different shape than the F7919
  triplicate-velocity entry, distinct cluster).
- `TestS3kAizTraceReplay#replayMatchesTrace`: first-error stable at F7552
  (977 errors).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.
- `TestClamerObjectInstance` 6/6 GREEN.
- Required headless tests (`TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`) GREEN.

**Cross-game parity rules.** `usesS3kTouchSpecialPropertyResponse()` is an
opt-in `TouchResponseProvider` hook (default `false`); only S3K objects
that use the `cflags = $C0 | <Touch_Special index>` pattern override it.
S1 and S2 objects are not affected. The latch state machine is wholly
contained in `ClamerObjectInstance` and shares no cross-game code path.

**ROM cite (additional, all `sonic3k.asm`):**

- `loc_890AA` (185953-185962): `bsr.w Check_PlayerCollision; beq.s
  loc_890C4; move.l #loc_890C8, (a0); bsr.w sub_890D8; movea.w
  parent3(a0), a1; bset #0, $38(a1); loc_890C4: jmp Child_DrawTouch_Sprite`.
- `loc_890C8` (185965-185968): `subq.w #1, $2E(a0); bmi.s loc_890D0; rts`.
- `loc_890D0` (185971-185973): `move.l #loc_890AA, (a0); rts`.
- `Check_PlayerCollision` (179904-179916): reads
  `collision_property(a0)`; if zero returns Z=1 (no fire), otherwise
  clears the byte and returns Z=0 (fire branch).
- `Touch_Special` (21162-21194): `addq.b #1, collision_property(a1)` for
  size index $17 inside the touch loop.
- `Child_DrawTouch_Sprite` (178048-178053): `Add_SpriteToCollisionResponseList`
  + draw.

---

## AIZ Trace F8927 — Sonic Air-Roll x_speed Not Cleared by Wall Collision (OPEN — diagnosis only)

**Location:** unidentified terrain wall in AIZ act-2 boss-arena approach (engine-side
gap in `Sonic_MdJump`/`SonicKnux_DoLevelCollision` wall handling, or in the level
chunk/collision-index data covering this region).
**ROM Reference:** `sonic3k.asm:22407` `Sonic_MdJump:` -> `sonic3k.asm:24035`
`SonicKnux_DoLevelCollision:` -> `sonic3k.asm:24061-24065` `bsr.w
CheckRightWallDist; tst.w d1; bpl.s loc_11F56; add.w d1,x_pos(a0); move.w #0,
x_vel(a0)` (wall hit clears x_vel). `CheckRightWallDist` itself is at
`sonic3k.asm:20188-20214` (player path uses `addi.w #$A,d3` -- the right-wall
probe is at `x_pos + 10`, not `x_pos + x_radius`).

### Symptom

`TestS3kAizTraceReplay#replayMatchesTrace` first strict error at frame **8927**:
- field: `x_speed` (Sonic), expected `0x0000`, actual `0x0179` (cap value).
- field: `x` cascading from `0x1208` (ROM) vs `0x1209` (engine) at F8927, drifting
  to `0x1216` (ROM `0x1208`) by F8935 -- ~14 px ahead in the engine.
- 896 total errors; chain becomes a position cascade and a phantom land at F8942
  (engine clears `air`/`rolling`/`y_speed` while ROM keeps falling).

Latest checkpoint at the time of error: `aiz2_reload_resume z=0 a=1 ap=0 gm=12`
(F5496). Latest zone/act state: `apparent_act=1` -- the AIZ2 boss-arena entry,
post fire-transition.

### Diagnosis

At F8927 Sonic is rolling and airborne (`status=0x06` = Air|Roll), descending
fast (`y_speed=+0x0220`), with `interact_slot=9` and
`status_secondary=0x11` (Status_Shield + Status_FireShield) latched since
F7149. `obj_control=0x00` -- no on-object lock -- and the slot-9 object held
since F7252 is an `AnimatedStillSprite` (`object_code=0x0002BF5A`), so the
`interact` linkage is just stale, not active.

The ROM frames F8923-F8926 sit pinned at `x_speed = 0x0179` while the engine
also clamps to the same value -- this is the rolling-jump x-speed cap (twice
`Max_speed` reduced by `Sonic_RollSpeed`/`Sonic_ChgJumpDir` early-return on
`Status_RollJump`). At **F8927 the ROM zeroes `x_speed`** and Sonic's `x_pos`
freezes at `0x1208`. From F8931 onward the ROM cycles `x_speed` 0 -> 0x18 ->
0x30 -> 0x48 -> 0x60 -> 0 every 5 frames, with `x_sub` accumulating 0x6000
(0.4 px) before snapping back -- the canonical signature of **air-roll
sliding into a flush right-side wall**: `Sonic_ChgJumpDir`'s
`Sonic_JumpPeakDecelerate` re-applies x-drag, `MoveSprite_TestGravity` drifts
Sonic into the wall, and `SonicKnux_DoLevelCollision`
`CheckRightWallDist` hit returns negative `d1`, pushes back into the wall and
clears `x_vel`. The 5-frame cycle period matches `(0x60/0x18 = 4
acceleration steps) + 1 wall-snap frame`.

The engine never observes that wall hit: `x_pos` keeps advancing at the cap
(`0x1209`, `0x120A`, ... up to `0x1216` by F8935) and at F8942 it phantom-lands
because cumulative drift puts Sonic's foot inside the floor before the ROM
trajectory does. Engine/ROM `tails_*` columns are still in lockstep through
F8935 -- the divergence is purely Sonic, purely on the right-wall channel.

### Likely root causes (not yet confirmed)

1. **Missing terrain solid bit at `(x>=0x1212, y in 0x0329..0x033D)`.** The
   ROM right-wall probe is `x_pos + 10` per `CheckRightWallDist` (line 20195;
   note the **fixed `+$A`**, not `x_radius`). If our chunk/block collision
   index for AIZ2 in this band is missing the right-wall solidity at this
   location, no wall is found and `x_vel` stays at the cap.
2. **`SonicKnux_DoLevelCollision` engine equivalent skipping the
   `CheckRightWallDist` arm** when Sonic's GetArcTan(d1=x_vel, d2=y_vel)
   quadrant falls in the "down-right" cone (which it does here: `x_vel=0x179`,
   `y_vel=+0x220`, arctan ~57 deg, post `subi.b #$20`/`andi.b #$C0` -> 0x00).
   In ROM that path takes the fall-through branch that calls both
   `CheckLeftWallDist` and `CheckRightWallDist`. If our quadrant routing
   mishandles this case, the right-wall arm is skipped despite the wall being
   present.
3. **Right-wall probe X offset.** Engine `PlayableSpriteMovement.getWallDistance`
   uses `centreX + xRadius`, while the player's `CheckRightWallDist` ROM path
   uses `x_pos + 10` (fixed). For default Sonic `xRadius=9` these match
   (`x_pos+10 == centreX + 9 + 1`?), but the off-by-one and the rolling-radii
   difference (`x_radius=7` while rolling) could shift the probe one pixel
   left, missing a wall whose left face is exactly at `x = x_pos + 10`.

Hypothesis (3) is the most testable: when Sonic is rolling, his `x_radius`
shrinks to 7. If the engine's airborne wall probe uses
`centreX + xRadius (=7)` while the ROM player path uses the **non-rolling**
fixed `+$A (=10)`, the engine's probe lands 3 px short of the ROM probe.
That would explain a wall flush at `x_pos + 10` being seen by ROM but missed
by the engine.

### Investigation 2026-04-30 — Hypothesis 3 disproven; engine probe is already +10 fixed

Direct in-engine probe of the airborne push-sensor offsets (logged from
`CollisionSystem.doWallCheckBoth` over F8920-F8930) confirms the engine
already uses **±10 fixed**, not `±xRadius`:

```
[probe-Q0] sensor=1 centre=(1209,0329) rotOff=(10,0) probe=(1213,0329) result=dist=28 xSpeed=0179
```

Sensor offsets are set by `AbstractPlayableSprite.updateSensorOffsetsFromRadii`
(`src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:3489`)
with a hard-coded `byte push = 10;` regardless of rolling state — explicitly
matching `sonic3k.asm:20195` `addi.w #$A,d3`. So Hypothesis (3) is **not**
the bug. The engine's right-wall probe at F8927 is already at `centreX + 10`,
which equals ROM's `x_pos + 10` since `centreX == x_pos` for player sprites.

What the probe does reveal is that at F8927 the engine's `centreX = 0x1209`
(one pixel ahead of ROM's `0x1208`), so the engine's right push sensor probes
`(0x1213, 0x0329)`. The wall scan returns `dist=28` — i.e., **the engine sees
no wall for 28 px to the right** of the probe. ROM at the same logical frame
probes `(0x1212, 0x0329)` and finds a wall (negative `d1`).

This means:
- The engine entered F8927 with **`centreX = 0x1209` instead of ROM's `0x1208`**.
  Since both engine and ROM agreed on integer `x = 0x1207` at F8926 and on
  `x_speed = 0x0179`, the divergence is in **sub-pixel state** (`x_sub`)
  accumulating prior to F8927. Trace `x_sub` history (from `physics.csv.gz`):
  - F8920 ROM: `x = 0x11FF.2000`
  - F8922 ROM: `x = 0x1201.FA00`
  - F8926 ROM: `x = 0x1207.DE00`
  - F8927 ROM: `x = 0x1208.0000` (post wall-snap; **`x_sub` cleared**)
- Even if the engine's per-frame `MoveSprite` math is bit-exact, an **earlier
  per-frame `x_speed` divergence** (Sonic_ChgJumpDir / Sonic_RollSpeed apply
  drag/cap that the ROM `x_speed` trace already reflects post-update; see the
  ~0x18 sub-pixel residual between expected `move(x_speed)` deltas and the
  trace's recorded `x_sub`) accumulates. By F8926 both report integer `x` =
  `0x1207` but the engine's lower 16 sub-pixel bits are ~`0x1800` ahead, so
  the F8926 → F8927 step crosses an extra integer-pixel boundary.
- F8927 ROM `x_sub = 0x0000` is *not* explained by the trivial `add.w d1,
  x_pos(a0)` snap (which preserves `x_sub`). Either the recorder samples
  `x_sub` after a downstream routine that clears it, or the wall-correction
  path here writes a full 32-bit `x_pos` (e.g., a `move.l` aligning to the
  pixel boundary). Confirming requires either disassembly trace or extending
  the recorder.

### Refined hypotheses

1. **Sub-pixel drift earlier in the rolling-jump arc**, originating in
   `Sonic_RollSpeed` / `Sonic_ChgJumpDir` per-frame deceleration ordering.
   The ROM trace shows `x_speed` cap held at `0x0179` for F8923-F8926 but the
   apparent move delta over those frames implies a slightly different
   `x_speed` was actually applied each frame than what's recorded — i.e.,
   the recorder snapshots the **post-update** `x_speed` that's then used for
   the *next* frame's move, not the value that produced the current frame's
   delta. If the engine and ROM disagree on the order
   (deceleration-then-move vs move-then-deceleration) for one frame, the
   sub-pixel bias is the integral of that one-frame `x_speed` difference.
2. **`x_sub`-clear semantics on wall-snap.** If the ROM has a player-path
   variant that does `move.w #0, x_sub(a0)` after a wall hit, the engine
   needs the same clear; otherwise the engine carries the residual sub
   forward into the next move. Not yet found in `sonic3k.asm` near
   `loc_11F44` / `loc_120B0` but the trace's `x_sub = 0x0000` at F8927 is
   strong evidence such a clear exists somewhere on that path.
3. (original) Missing terrain solid bit at the cited coordinate — still
   possible but less likely now that the probe shows `dist = 28` (a clean
   "no wall here" reading) rather than a partial / mis-flipped collision.

### Trace evidence summary

```
Frame  Exp x   Act x   Exp x_speed Act x_speed Exp y_speed
8922   0x1201  0x1201  0x0179      0x0179      0x0108
8923   0x1203  0x1203  0x0179      0x0179      0x0140  (CAP held by both)
8926   0x1207  0x1207  0x0179      0x0179      0x01E8
8927   0x1208 *0x1209  0x0000     *0x0179      0x0220  (ROM zeroes; engine doesn't)
8930   0x1208 *0x120D  0x0000     *0x0179      0x02C8  (ROM x frozen 4 frames)
8931   0x1208 *0x120F  0x0018     *0x0191      0x0300  (ROM begins fresh ramp;
                                                       sub_x snaps 0xF000->0x0000
                                                       between F8934 and F8935 =
                                                       wall-snap pushback)
8942 *0x???? *0x????  cascading air=1->0, y_speed=0x0568->0x0000 (engine phantom lands)
```

`status=0x06` (Air|Roll), `status_secondary=0x11` (FireShield), `obj_control=0x00`,
no `Status_RollJump` set -- this is post-roll-off-ledge / post-bounce, not a
Sonic-initiated roll-jump.

### Recorder gaps

The recorder does not currently emit a per-frame "wall hit clears x_vel" event,
nor a snapshot of Sonic's `x_radius` (which differs while rolling). Confirming
hypothesis (3) without engine-side ROM-cite-able sensor probe traces will
require either:
- Extending the recorder to log Sonic's `x_radius` and the post-collision
  diff between `x_pos_pre` and `x_pos_post` (with the source: wall vs floor),
  or
- Stable-retro side-by-side at F8923-F8930 confirming the right-wall probe X
  in the ROM (verifying `CheckRightWallDist` does take the +10 arm and finds
  a wall at `x = 0x1212`).

Both options are recorder/diagnostic work, not engine-fix work.

### Removal Condition

This entry is removed when either:
1. The engine detects the wall at the same frame and clears `x_speed` to 0 at
   F8927 (and `x_pos` thereafter matches ROM); or
2. The level data is corrected so the right-wall solidity exists at the
   ROM-cited position; or
3. The engine's airborne right-wall probe uses the player-path fixed
   `+10` offset (matching `sonic3k.asm:20195`) when a parity-relevant
   discrepancy is confirmed, and the trace passes F8927.

In all three cases the trace must advance past F8927 with no new strict
errors introduced before F9270 (the cascading-x end frame).
## CNZ1 Trace F7923 — Clamer Latched-Cprop Fired On Wrong Player (FIXED)

**Status:** FIXED. CNZ first-error advances F7923 -> F8123 (2767 -> 2683
errors). The new F8123 divergence is a distinct downstream
`tails_x_speed` mismatch (`expected=0x0230, actual=0x00BF`) consistent
with a different Tails CPU code path; not related to the Clamer.

**Symptom**

`TestS3kCnzTraceReplay#replayMatchesTrace` first strict error at F7923
after the F7919 Clamer state-machine port landed (see prior entry):

```
tails_x_speed mismatch (expected=-0x0800, actual=-0x07D0)
```

The 48-subpixel delta on Tails was the visible field, but the engine
was actually **launching Sonic into the air at F7923** with the
ROM-spring triplicate write
(`x_speed=y_speed=g_speed=-0x0800, air=1`) while ROM had Sonic still
on the ground. Tails was simultaneously mid-cooldown decay
(`-0x07D0` from `-0x0800` minus 0x18 air friction × 2) while ROM had
just re-fired the same Clamer spring on Tails (back to `-0x0800`
triplicate).

**Root cause — engine collapsed cprop byte to a single boolean**

ROM `Touch_Special` (`sonic3k.asm:21162-21194`) accumulates per-touch
into `collision_property(a1)` with a player-identity-dependent
increment:

```
loc_103FA:
    move.w  a0,d1                ; a0 = toucher (player) RAM addr
    subi.w  #Object_RAM,d1
    beq.s   .ismaincharacter     ; if Player_1 (Sonic), branch
    addq.b  #1,collision_property(a1)
.ismaincharacter:
    addq.b  #1,collision_property(a1)
```

So Sonic touch adds `+1`; Tails touch adds `+2`. Then ROM
`Check_PlayerCollision` (`sonic3k.asm:179904-179924`) consumes the
byte:

```
move.b  collision_property(a0),d0
beq.s   locret_8588E
clr.b   collision_property(a0)
andi.w  #3,d0
add.w   d0,d0
lea     word_85890(pc),a1   ; [P1, P1, P2, P2]
movea.w (a1,d0.w),a1
```

`word_85890[d0&3]` = `[P1, P1, P2, P2]`, so the byte encodes the
launch target:

| cprop & 3 | Target |
|-----------|--------|
| 0         | no fire (gate already returned) |
| 1         | P1 (Sonic touched alone) |
| 2         | P2 (Tails touched alone) |
| 3         | P2 (both touched same frame; sidekick wins) |

The engine's `ClamerObjectInstance` previously kept `springCprop` as a
`boolean`, losing the player-identity bits. When the spring's
post-cooldown frame fired the latched launch, the engine called
`fireSpring(playerEntity)` where `playerEntity` is the **primary
player** passed into `update()` (always Sonic). That caused the
F7923 wrong-target launch on Sonic, even though Tails had been the
sole toucher.

**Fix** (`ClamerObjectInstance.java`, this branch)

- `springCprop` becomes an `int` byte mirroring ROM
  `collision_property(a0)`.
- `onTouchResponse` increments by `+1` when the toucher is the primary
  player and `+2` when `playerEntity.isCpuControlled()` (Tails),
  matching `loc_103FA`.
- `advanceSpringRoutine`'s two latch-fire branches (post-cooldown
  `LIVE` and `COOLDOWN_DONE`) now resolve the launch target by reading
  `springCprop & 3` and indexing the engine analogue of `word_85890`:
  `1 → primary`, `2 or 3 → first sidekick from
  services().sidekicks()`. Cprop is cleared on consumption to mirror
  the `clr.b collision_property(a0)` in `Check_PlayerCollision`.
- The same-frame immediate-fire path (when the spring is `LIVE` and a
  touch hits during the player's own touch walk) continues to fire on
  the toucher directly and clears the cprop byte, matching ROM where
  `Check_PlayerCollision` consumes the byte and applies the launch on
  the same `(a0)` frame the cprop was written.

**Verification**

- `TestS3kCnzTraceReplay#replayMatchesTrace`: first-error advances
  F7923 -> F8123 (2767 -> 2683 errors). The new F8123 first-error is a
  Tails CPU `x_speed/y_speed` divergence at a freely-flying segment,
  not a spring/cprop issue.
- `TestS3kAizTraceReplay#replayMatchesTrace`: first-error stable at
  F8927 (896 errors), unchanged.
- `TestClamerObjectInstance` 6/6 GREEN (immediate-fire and latched-fire
  unit tests both pass; the single-player tests resolve `cprop=1` to
  the primary unchanged).
- S1 GHZ, S1 MZ1, S2 EHZ trace replays GREEN.

**Cross-game parity rules.** The fix is wholly contained in
`ClamerObjectInstance` (S3K Obj $A3) and uses the existing
`PlayableEntity.isCpuControlled()` and
`ObjectServices.sidekicks()` APIs. S1/S2 objects are unaffected; no
cross-game code path was touched.

**ROM cite (all `sonic3k.asm`):**

- `Touch_Special.loc_103FA` (21186-21194): `addq.b #1` for non-main
  toucher, plus shared `addq.b #1` from `.ismaincharacter` fall-through
  (so P1 += 1, P2 += 2).
- `Check_PlayerCollision` (179904-179924): masks `& 3`, doubles, and
  indexes `word_85890`; clears the byte unconditionally on non-zero.
- `word_85890` (179920-179924): `dc.w Player_1, Player_1, Player_2,
  Player_2`.
- `loc_890AA` (185953-185962): the cycle entry that calls
  `Check_PlayerCollision` and either fires (`bsr.w sub_890D8`) or
  falls through to `Child_DrawTouch_Sprite`.


## CNZ1 Trace F8123 — CNZ Bumper Misses Sidekick Tails Touch At Pixel-Edge Overlap (OPEN — diagnosis only)

**Status:** OPEN. After the F7923 Clamer cprop fix landed (CNZ first error
advanced F7923 -> F8123 with 2683 errors), the next first strict error is
a CNZ bumper bounce that fires for Tails in ROM but is missed by the
engine. Doc-only diagnosis this round; an engine-side fix to the
bumper-sidekick touch detection is required.

**Symptom**

`TestS3kCnzTraceReplay#replayMatchesTrace` first strict error at F8123:

```
tails_x_speed mismatch (expected=0x0230, actual=0x00BF)
tails_y_speed mismatch (expected=-0x06A5, actual=0x02A0)
```

The trace CSV header reads `Exp ... | Act ...` (interleaved), so the
ROM-expected `tails_y_speed` at F8123 is **`-0x06A5`** (Tails fired
upward fast) and the engine produced `0x02A0` (gravity-only progression
from the prior frame's `0x0268`). The starred field marks engine
divergence.

**Root cause — ROM CNZ bumper at slot 14 bounces Tails; engine misses
the sidekick touch**

Across F8108-F8122 both characters are stable: Sonic in his airborne
roll (status `0x07` = Facing_left|InAir|Roll), Tails following with
`Tails_CPU_routine = 6` (`loc_13D4A`, sonic3k.asm:26656) at
`(x_pos=0x0F05, y_pos=0x0472)` and `(x_vel=0x00D7, y_vel=0x0268)` at
the end of F8122. The CNZ stationary bumper in slot 14
(`object_code=0x00032EAA = loc_32EAA` for `Map_Bumper`,
sonic3k.asm:68850-68886) sits at `(x_pos=0x0F00, y_pos=0x0488)` with
the standard `width_pixels=$10, height_pixels=$10` body
(sonic3k.asm:68825-68826) and `collision_flags=$D7`
(sonic3k.asm:68828).

At F8123 the AABBs touch exactly at the bumper's top edge:

| Field          | ROM Tails           | ROM Bumper           |
|----------------|---------------------|----------------------|
| Centre `(x,y)` | `(0x0F05, 0x0472)`  | `(0x0F00, 0x0488)`   |
| Half-extents   | `7 x 14` (slot 1 trace) | `8 x 8` (Obj_Bumper) |
| AABB X         | `[0x0EFE, 0x0F0C]`  | `[0x0EF8, 0x0F08]`   |
| AABB Y         | `[0x0464, 0x0480]`  | `[0x0480, 0x0490]`   |

X overlap is `[0x0EFE, 0x0F08]`. Y "overlap" is the single edge line at
`y=0x0480` (Tails' bottom equals bumper's top). ROM treats this as a
hit and fires `sub_32F56` (sonic3k.asm:68950-68992):

```
sub_32F56:
    move.w x_pos(a0), d1        ; bumper x
    move.w y_pos(a0), d2        ; bumper y
    sub.w x_pos(a1), d1         ; d1 = bumper.x - player.x  = -5
    sub.w y_pos(a1), d2         ; d2 = bumper.y - player.y  = +22
    jsr (GetArcTan).l            ; d0 = ArcTan(d1, d2)
    move.b (Level_frame_counter).w, d1
    andi.w #3, d1                ; +0..3 frame perturbation
    add.w d1, d0
    jsr (GetSineCosine).l        ; -> d0=cos, d1=sin
    muls.w #-$700, d1
    asr.l #8, d1
    move.w d1, x_vel(a1)         ; x_vel = sin × -$700 / 256
    muls.w #-$700, d0
    asr.l #8, d0
    move.w d0, y_vel(a1)         ; y_vel = cos × -$700 / 256
    bset #Status_InAir, status(a1)
    bclr #Status_RollJump, status(a1)
    bclr #Status_Push, status(a1)
    clr.b jumping(a1)
    move.b #1, anim(a0)
    moveq #signextendB(sfx_Bumper), d0
    jsr (Play_SFX).l
    ...
    jsr (AllocateObject).l       ; spawn Obj_EnemyScore at bumper x/y
    move.l #Obj_EnemyScore, (a1)
```

Three lines of evidence confirm this is the path that fires in ROM at
F8123:

1. `aux_state.jsonl` has an `object_appeared` event at F8123 for
   slot 7 with `object_type = 0x0002CCE0 = Obj_EnemyScore`
   (`sonic3k.asm:61375` `Obj_EnemyScore`/`loc_2CD0C`) at the bumper's
   `(0x0F00, 0x0488)` -- the score popup is only spawned by
   `sub_32F56` after a successful bounce.
2. The next bounce of the same bumper produces another
   `object_appeared` at F8150 (27 frames later, the orbit period gap),
   confirming the bumper is the spawn source.
3. ROM `tails_x_speed` jumps from `0x00D7` (start of F8123, captured
   by the `tails_cpu_normal_step` aux event hook at `loc_13DD0`,
   sonic3k.asm:26696) to `0x0230` at end of F8123 and `tails_y_speed`
   jumps from `0x0268` to `-0x06A5` -- discontinuous changes
   incompatible with `Tails_InputAcceleration_Freespace`
   (sonic3k.asm:28330-28401) and `MoveSprite_TestGravity` air physics
   (which would only add `0x18` drag and `0x38` gravity), but
   consistent with the bumper's full sin/cos/-$700 velocity reseed.

**Engine side**

`CnzBumperObjectInstance` (`src/main/java/com/openggf/game/sonic3k/objects/CnzBumperObjectInstance.java`) does implement the bounce
correctly via `applyBounce(...)` and supports both primary and sidekick
players via `pendingPrimaryTouch` / `pendingSidekickTouch`. However the
trace context line at F8123 shows the engine's `eng-near` /
`eng-tails-cyl` scans only register slot 9 (Monitor) and slot 11
(cylinder) -- the bumper at slot 14 (or whatever slot the engine
allocates for it) is **not** in the per-frame near-object list against
which the engine's touch system tests Tails. The engine `tails_x_speed`
ends at `0x00BF` (= `0x00D7 - 0x18` air drag) and `tails_y_speed` at
`0x02A0` (= `0x0268 + 0x38` gravity), i.e. the sidekick's frame-end
state is **just the airborne roll physics with no bumper bounce
applied**.

So the divergence is **not** in the bounce maths (which is
sin/cos/-$700-correct in `applyBounce`); it is upstream in the
sidekick-vs-CnzBumper touch test missing the exact-edge contact at
F8123.

**Likely areas for the engine fix**

1. The CnzBumper's `getMultiTouchRegions()` returns a single region only
   when `initialAngle != 0` (orbiting bumpers). For stationary bumpers
   it returns `null`, falling back on the default AABB driven by
   `getCollisionFlags()` and the engine's spawn box. Verify that the
   default AABB used by the touch system matches `width_pixels=$10,
   height_pixels=$10` exactly (no implicit `-1` half-side shrink) so
   that an edge-touching sidekick like Tails at F8123 is detected.
2. Confirm `requiresContinuousTouchCallbacks()=true` plus
   `TouchResponseListener.onTouchResponse(...)` are invoked for **CPU
   sidekicks**, not just the controlling player. The
   `pendingSidekickTouch` branch is in place, but if the higher-level
   touch loop only iterates the primary player, the bumper never sees
   Tails.
3. Recheck the engine's "near-object" scan window for sidekick: the
   ROM `Add_SpriteToCollisionResponseList` (called by `loc_32EB4`,
   sonic3k.asm:68886) feeds the bumper into a global response list that
   `Touch_ChkValue`/`sub_32F34` then tests against **both** Player_1
   and Player_2 (sonic3k.asm:68929-68943). The engine's
   per-frame near-object scan filtering for the sidekick may exclude
   stationary bumpers when the primary leader is far away (Sonic was
   `(0x0DE5, 0x0309)` at F8123, > 600px from the bumper).

The third item is the most likely culprit: the engine appears to
filter the near-object list per-leader rather than per-player, so a
stationary CNZ bumper that is too far from Sonic but within Tails' AABB
edge gets dropped before the touch test runs.

### Removal Condition

This entry is removed when `TestS3kCnzTraceReplay#replayMatchesTrace`
no longer reports F8123 as the first strict error and the trace
advances past the bumper-bounce window without strict mismatches on
`tails_x_speed`, `tails_y_speed`, or `tails_x`/`tails_y` between the
F8123 contact and the next bumper-or-physics divergence. The fix must
preserve cross-game parity (no regressions in S1/S2 traces or in the
existing `TestCnzBumperObjectInstance` tests) and remain ROM-cited
against `sub_32F56` (sonic3k.asm:68950-68992) and Obj_Bumper
(sonic3k.asm:68823-68886).
