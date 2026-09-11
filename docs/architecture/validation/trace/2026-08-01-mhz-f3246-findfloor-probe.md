# MHZ complete-run f3246: BizHawk probe of the ground-sensor path

**Date:** 2026-08-01
**Worktree/branch:** `.worktrees/f3246-probe`, `bugfix/ai-s3k-f3246-probe` off
`bugfix/ai-s3k-mhz-queue-frontier`.
**ROM:** locked-on Sonic 3 & Knuckles, CRC32 `63522553`.
**Movie:** `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`,
`bk2_frame_offset` 209756 (`src/test/resources/traces/s3k/mhz_completerun/metadata.json`).

## Question

Three prior investigations (`docs/status/trace-frontier-log.md`) established that at MHZ
complete-run frame 3246 the ROM leaves the ground and the engine does not, with position
and velocity byte-identical, and that no recorded input to `Player_AnglePos` /
`sub_F264` could produce a floor distance large enough to detach. They asked for the last
unrecorded input: `sub_F264`'s own inputs and outputs, per sensor, per frame.

Answer: **`sub_F264` was never the culprit.** The ROM's ground sensors agree with the
engine's exactly, and `Player_AnglePos` returns the player *grounded*. The airborne
transition comes from an object.

## Probes

All three are read/log-only and use the `ProbeRuntime` stage-and-hooks contract.

| Probe | What it records |
|---|---|
| `tools/bizhawk/probes/mhz_f3246_findfloor_probe.lua` | Per sensor, per frame: `sub_F264` entry `(d2, d3, d5, d6, a3, a4, a5)` and `Collision_addr`; the raw chunk word and chunk index at `loc_F282`; the resolved collision-block id and `AngleArray` byte at `loc_F2BA`; the exit path taken and the returned `d1`; the `Player_Angle` selection; and the `locret_ED12` / `loc_ED14` / `loc_ED38` outcome |
| `tools/bizhawk/probes/mhz_f3246_status_write_probe.lua` | Every write to Player_1's `status` byte (`$FFB02A`) in f3240-f3252, with the 68k PC, plus `Player_SlopeRepel` entry/speed-gate/detach |
| `tools/bizhawk/probes/mhz_f3246_madmole_release_probe.lua` | The `loc_8D724` off-camera despawn release: the child object's slot/code/routine/`$44`, and the released object's identity and state |

Captures: `2026-08-01-mhz-f3246-captures/` (`.txt`).

All hook addresses were verified against ROM bytes before use, e.g. `$00F264` = `4E95`
(`jsr (a5)`), `$00ED38` = `08E8 0001 002A` (`bset #1,$2A(a0)`), `$08D72C` =
`08E9 0001 002A` (`bset #1,$2A(a1)`).

## Result 1 — `sub_F264` and `Player_AnglePos` are correct

ROM at f3246 (`mhz_f3246_findfloor.txt`):

```
ANGLEPOS   x=0FA8 y=0777 xvel=073E gvel=077A ang=F6 status=00 rtn=02
           yrad=13 xrad=09 topbit=0C lrbbit=0D stick=00
           prim_coll=000987C0 sec_coll=000987C1 coll=000987C0 bgflag=00
F264_IN    sensor=1 sx=0FB1 sy=078A a4=F768(primary/right)  coll=000987C0
F264_SOLID sensor=1 chunk_word=F276 chunk_idx=276
F264_BLOCK sensor=1 block_id=38 angle_arr=F8
F264_EXIT  sensor=1 path=normal          d1=0 angle_out=F8
F264_IN    sensor=2 sx=0F9F sy=078A a4=F76A(secondary/left) coll=000987C0
F264_EXIT  sensor=2 path=tile_below_+10  d1=6 angle_out=F4
PLAYER_ANGLE primary_d0=0 secondary_d1=6 prim_angle=F8 sec_angle=F4
ED12_RETURN  d1=0 player_angle=F8
```

Every value matches what the prior analyses derived from the engine: sensor origins
`(0x0FB1, 0x078A)` and `(0x0F9F, 0x078A)`, `Collision_addr` = Primary `$000987C0`,
right-sensor distance 0 with angle `F8`. `Player_Angle` leaves `d1 = min = 0`, so
`Player_AnglePos` takes `beq.s locret_ED12` (sonic3k.asm:18809) and **does not detach**.
`loc_ED38` is never reached anywhere in f3200-f3260. The exit angle `F8` that the trace
records is written here, by the grounded path.

Two incidental corrections to the prior static analysis: the escape hatches do *not* both
force a flat angle 0 — `sub_F30C`'s `loc_F32A` writes the tile-below `AngleArray` byte
(the left sensor returns `F4` here) — and `loc_F31C` writes no angle at all.

Frames f3247-f3255 contain no `Player_AnglePos` call at all: the player is already
airborne and running `Sonic_MdAir`. So the transition happens after `Player_AnglePos`
within f3246.

## Result 2 — the writer of `Status_InAir`

`mhz_f3246_status_write.txt` shows one status write per grounded frame from
`pc=01148E` (a `bclr #0,status(a0)` facing update, value `00`), then at f3246, after
`Player_SlopeRepel` has returned without detaching:

```
f=3246 STATUS_WRITE pc=08D732 ...  x=0FA8 y=0777 ang=F8 status=00
f=3247 STATUS_WRITE pc=011690 ...  x=0FA8 y=0777 ang=F8 status=02
```

`pc=08D732` is the instruction after `$08D72C` — `bset #Status_InAir,status(a1)` in
`loc_8D724`, the off-camera despawn tail of `loc_8D6E6` (sonic3k.asm:193222-193228):

```
loc_8D724:
        move.w  $44(a0),d0
        beq.s   loc_8D736
        movea.w d0,a1
        bset    #Status_InAir,status(a1)
        clr.b   object_control(a1)
loc_8D736:
        jmp     (Go_Delete_Sprite).l
```

## Result 3 — which object, and on whom

`mhz_f3246_madmole_release.txt`:

```
f=3246 RELEASE released_is_player1=yes
       child_ptr=B5C8 child_slot=20 child_code=0008D6E6 child_rtn=06
       child_x=0E0C child_y=0724 child_objctl=00 child_p44=B000 child_parent3=B57E
       released_ptr=B000 released_slot=0 released_x=0FA8 released_y=0777
       released_status=00 released_objctl=00
```

`loc_8D6E6` is the Madmole's side-drill arm child (`ChildObjDat_8D9C8` /
`ChildObjDat_8D9D0`, sonic3k.asm:193508-193514). At f3246 it is at routine 6 — the
post-release drift state — with `object_control` clear on both itself and Sonic. Sonic is
running normally on the ground 500px away. The arm scrolls out of the `loc_8D6E6` band
and its despawn tail detaches him anyway.

**`$44(a0)` is written once and never cleared.** The straight drill's touch response
`sub_8D8E6` writes it (`move.w a2,$44(a0)`, sonic3k.asm:193439) and the arc grab
`sub_8D94A` writes it (sonic3k.asm:193477); the wall and floor release paths
(`loc_8D820` / `loc_8D85E` into `loc_8D834`, sonic3k.asm:193337-193346 and
193363-193367) only set `Status_InAir`, clear `object_control` and drop the arm to
routine 6. So an arm that has already knocked a player away still detaches that same
player when it later leaves the camera band. That is what f3246 is.

The signature matches the recorded trace exactly: `ground_vel` preserved at `0x077A` and
frozen through f3247-f3254, no velocity impulse, `routine` unchanged at `0x02`,
`move_lock` 0, and a clean `+0x38` gravity chain `FE30, FE68, FEA0, FED8, FF10`.

## Engine defect and fix

`MadmoleBadnikInstance$SideDrillChild` kept only `capturedPlayer`, which is nulled at
release (`enterPostCaptureDrift`), and gated `checkDeleteAndReleaseCapturedPlayer` on it.
The straight touch path never recorded the player at all. Instrumenting the class showed
the engine's arm following the ROM's trajectory byte-for-byte through f3240-f3246
(`x=0E36 … 0E0C`, `y=0724`) and despawning on the same frame — with nothing to release.

Fix: a `releaseTargetPlayer` field modelling ROM `$44(a0)`, written by both the straight
touch response and the arc grab, never cleared at release, and consumed by the
`loc_8D724` despawn path. Registered `CAPTURED` in `DefaultObjectRewindPolicies`.

## Measurements

MHZ complete-run: **903 errors / 7218 frames, first non-queue error f3246** →
**934 / 7218, first error f3326** (`y_speed`, expected `0x0000` actual `0x03C8`). The
f3246 seed is closed and the frontier advanced 80 frames onto a new seed; the error count
rises because the new seed cascades further within the captured window.

Other six segments byte-identical to baseline: aiz 8/64, hcz 7/1320, mgz 18/16510,
cnz 7/9711, icz 10/12375, lbz 8/35. `TestS3kAizTraceReplay` holds at 4 failures + 1 error.
`TestRewindCoverageGuard`, `TestStaticStateRewindCoverageGuard`,
`TestMadmoleBadnikInstance` (31), `TestS3kAiz1SkipHeadless` (8),
`TestSonic3kLevelLoading` (36), `TestSonic3kBootstrapResolver` (5),
`TestSonic3kDecodingUtils` (3) all pass.
