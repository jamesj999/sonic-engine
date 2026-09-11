# AIZ Intro Ocean Scroll Speed Investigation

## Context

The AIZ1 intro ocean background appears to scroll too slowly compared to the original ROM (verified via side-by-side emulator comparison). The ocean-to-beach transition also takes too long. Specifically: "The ocean animation is playing, just the shift in speeds seem to happen at the wrong events/times."

This document records an exhaustive side-by-side comparison of every routine, timer, speed change, and scroll calculation between the ROM disassembly (`s3.asm` Obj_AIZPlaneIntro at `loc_45888`) and the Java implementation (`AizPlaneIntroInstance.java`).

## ROM Architecture Overview

The AIZ1 intro uses a stride-2 routine dispatch (0x00-0x1A = 14 routines). Each frame:

1. **Routine dispatch** — runs the current routine (movement, timers, state transitions)
2. **Sonic_Load_PLC** — dynamic pattern loading (art tile DMA queueing)
3. **sub_45DE4 (scrollVelocity)** — accumulates `scrollSpeed` into `Events_fg_1`; while negative, drives BG scroll via `introScrollOffset`; once >= 0, moves the player rightward
4. **Draw_Sprite** — renders the object

The BG scroll handler (`AIZ1_IntroDeform` + `ApplyDeformation`) runs separately in the scroll management loop, reading `Events_fg_1` to compute per-scanline parallax band values.

### scrollVelocity Mechanism

```
Events_fg_1 starts at 0xE918 (-5864 signed)
Each frame: Events_fg_1 += scrollSpeed
While Events_fg_1 < 0: BG scrolls via introScrollOffset = Events_fg_1
Once Events_fg_1 >= 0: player.x += scrollSpeed (camera follows)
```

The BG scroll rate during the ocean phase is `scrollSpeed / 2` pixels/frame (the IntroDeform routine applies `asr.w #1` to the source value).

### scrollSpeed Transitions

**4 writes** to `$40(a0)` affect scrollSpeed during the intro (the original investigation missed #2):

1. **Routine 0x00 (init):** `move.w #8,$40(a0)` → scrollSpeed = 8, BG moves 4 px/frame
2. **Routine 0x04 exit (`Swing_Setup1`):** `move.w #$10,$40(a0)` → scrollSpeed = **16**, BG moves 8 px/frame (**THIS WAS THE MISSING CHANGE**)
3. **Routine 0x10 exit (wait after walk right):** `move.w #$C,$40(a0)` → scrollSpeed = 12, BG moves 6 px/frame
4. **Routine 0x12 exit (walk left complete):** `move.w #$10,$40(a0)` → scrollSpeed = 16, BG moves 8 px/frame

**Root cause of the bug:** `$40(a0)` is a dual-purpose field — `Swing_Setup1` (sonic3k.asm:136829) writes `0x10` as the swing acceleration for `Swing_UpAndDown`, but `sub_67A08` reads the same field as the scroll speed every frame. The engine split these into separate variables (`SWING_ACCEL` for swing math, `scrollSpeed` for scroll) but didn't update `scrollSpeed` at the `Swing_Setup1` call. This meant the ocean scrolled at speed 8 for ~340 extra frames (routines 0x06–0x0E) instead of speed 16.

Note that change #3 actually *decreases* speed from 16→12 before #4 restores it. The original analysis assumed a monotonic 8→12→16 progression, but the real progression is 8→16→12→16.

## Detailed Comparison Table: ROM vs Java

### Per-Routine Timing

| Routine | Description | ROM Duration | Java Duration | Match? |
|---------|-------------|-------------|---------------|--------|
| 0x00 | Init: pos=(0x60,0x30), timer=0x40, scrollSpeed=8, eventsFg1=0xE918 | 1 frame | 1 frame | YES |
| 0x02 | Wait: Obj_Wait($2E=0x40), callback spawns plane child + sets velocity | 65 frames | 65 frames | YES |
| 0x04 | Descent: yVel-=0x18/frame, MoveSprite2. Exit when yVel==0: **Swing_Setup1 sets $40=0x10** | 64 frames | 64 frames | YES* |
| 0x06 | Swing+Wait: Swing_UpAndDown + MoveSprite2 + Obj_Wait($2E=0x5F). **scrollSpeed=16** | 96 frames | 96 frames | YES |
| 0x08 | Lift-off: xVel-=0x40, MoveSprite(+gravity). Exit when y>=0x130 | N frames (physics) | N frames (same physics) | YES |
| 0x0A | Ground decel: xVel-=0x40, MoveSprite2. Exit when x<0x40 | M frames (physics) | M frames (same physics) | YES |
| 0x0C | Super flash: secondaryTimer countdown ($3A=0x3F) | 64 frames | 64 frames | YES |
| 0x0E | Walk right: x+=4/frame + wave spawns. Exit when x>=0x200 | 112 frames | 112 frames | YES |
| 0x10 | Wait: secondaryTimer($3A=0x1F). **scrollSpeed→0x0C on exit** | 32 frames | 32 frames | YES |
| 0x12 | Walk left: x-=4/frame. Exit when x<=0x120. **scrollSpeed→0x10 on exit** | 56 frames | 56 frames | YES |
| 0x14 | Wait: secondaryTimer countdown ($3A=0x1F) | 32 frames | 32 frames | YES |
| 0x16 | Monitor: wait until player.x >= 0x918, spawn Knuckles | Variable | Variable | YES |
| 0x18 | Monitor: wait until player.x >= 0x1240, y-=0x20 | Variable | Variable | YES |
| 0x1A | Explosion: wait until player.x >= 0x13D0, release player | Variable | Variable | YES |

\*Routine 0x04 note: ROM uses `beq` (exact zero check) and skips MoveSprite2 on exit frame. Java uses `<= 0` and calls moveSprite2() first. Since 0x600 is exactly divisible by 0x18, both exit at the same frame count. Java produces ~3px extra rightward movement on the exit frame (cosmetic only).

### Timer Derivations

| Timer | ROM Value | Obj_Wait/subq Behavior | Duration |
|-------|-----------|------------------------|----------|
| Init wait ($2E) | 0x40 = 64 | `subq.w #1; bmi` → fires at -1 | 65 frames |
| Swing wait ($2E) | 0x5F = 95 | Same mechanism | 96 frames |
| Super flash ($3A) | 0x3F = 63 | `subq.w #1; bpl` → fires at -1 | 64 frames |
| Short wait ($3A) | 0x1F = 31 | Same mechanism | 32 frames |
| Wave spawn ($2E) | 5 | Reset to 5 on trigger | 6-frame cycle |

Java uses `timer--; if (timer < 0)` which is equivalent to `subq.w #1; bmi/bpl` — both fire when the value transitions from 0 to -1.

### scrollSpeed Change Timing

| Aspect | ROM | Java (before fix) | Java (after fix) | Match? |
|--------|-----|-------------------|------------------|--------|
| Initial speed (routine 0x00) | `move.w #8,$40(a0)` | `scrollSpeed = 8` | `scrollSpeed = 8` | YES |
| **Swing_Setup1 (routine 0x04 exit)** | `move.w #$10,$40(a0)` | **MISSING** (stayed at 8) | `scrollSpeed = SWING_ACCEL` (16) | **FIXED** |
| Walk phase (routine 0x10 exit) | `move.w #$C,$40(a0)` | `scrollSpeed = 0x0C` | `scrollSpeed = 0x0C` | YES |
| Final phase (routine 0x12 exit) | `move.w #$10,$40(a0)` | `scrollSpeed = 0x10` | `scrollSpeed = 0x10` | YES |
| Frames at speed 8 | 1+65+64 = 130 | 1+65+64+96+N+M+64+112+32 = 434+N+M | 130 | **FIXED** |
| Frames at speed 16 (first) | 96+N+M+64+112 = 272+N+M | 0 | 272+N+M | **FIXED** |
| Frames at speed 12 | 56 (routine 0x12 only) | 56 | 56 | YES |
| Frames at speed 16 (final) | 32 + variable | 32 + variable | 32 + variable | YES |

### scrollVelocity (sub_45DE4 / sub_67A08)

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| eventsFg1 initial value | `0xE918` = -5864 (signed word) | `(short) 0xE918` = -5864 | YES |
| Accumulation (while negative) | `add.w d1,d0; move.w d0,(Events_fg_1)` | `eventsFg1 += scrollSpeed` | YES |
| Gate check (transition) | `bpl` (branch if >= 0) | `if (eventsFg1 < 0)` | YES |
| Post-gate (once positive) | `add.w d1,(Player_1+x_pos)` | `player.setCentreX(x + scrollSpeed)` | YES |
| Intro scroll export | `Events_fg_1` read directly by scroll handler | `introScrollOffset = eventsFg1` | YES |
| Runs on init frame | YES (after routine dispatch returns) | YES (after routine dispatch) | YES |

### AIZ1_IntroDeform Band Computation

ROM source: `s3.asm` lines 70207-70265.

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| Source selection | `Events_fg_1 < 0 → use it; else → Camera_X_pos_copy` | `introOffset < 0 ? introOffset : cameraX` | YES |
| Half-speed shift | `asr.w #1,d0` (signed right shift) | `(short) source >> 1` | YES |
| Cap threshold | `cmpi.w #$580,d0; blt` | `if (d0 >= INTRO_DEFORM_CAP)` where cap=0x580 | YES |
| Band count | 37 (0x25 entries) | `INTRO_DEFORM_BANDS = 0x25` = 37 | YES |
| Step formula | `(d0-0x580) << 16; asr.l #5` | `(d0 - cap) << 16 >> 5` | YES |
| Band accumulation | `add.l d1,d0; swap d3; addi.w #$580,d3` | `accum += step; (accum >> 16) + cap` | YES |

### Deformation Segment Array (AIZ1_IntroDeformArray)

ROM source: `s3.asm` lines 70455-70459.

| Segment | ROM | Java | Match? |
|---------|-----|------|--------|
| First segment (ocean band) | 0x3E0 = 992 pixels | `segments[0] = 0x3E0` | YES |
| Middle segments (transition) | 36 entries × 4 pixels each | `segments[1..36] = 4` | YES |
| Terminator | 0x7FFF | `INTRO_DEFORM_TERMINATOR = 0x7FFF` | YES |

During the ocean phase (cameraY near 0), the first segment (992px) fills the entire visible screen (224 scanlines). All scanlines use band[0] = source/2, producing uniform scroll across the screen.

### ApplyDeformation (Per-Scanline HScroll Write)

ROM source: `s3.asm` lines 68687-68785.

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| FG scroll (Plane A) | `neg.w cameraX` (constant all lines) | `negWord(cameraX)` (constant all lines) | YES |
| BG scroll (Plane B) | `neg.w band_value` (per-segment) | `negWord(introBandValues[idx])` (per-segment) | YES |
| Pack format (longword) | high=FG, low=BG | `(fg << 16) \| bg` | YES |
| Y offset (segment skip) | Camera_Y_pos_BG_copy | `remainingY = (short) cameraY` | YES |

### Rendering Pipeline

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| HScroll format | VDP reads raw 16-bit words | Normalize to [-1,1], GPU re-expands ×32767 | YES (roundtrip preserves value) |
| Shader math | VDP: pixel = column + hscroll_value | `worldX = gameX - hScrollThis` | YES |
| FBO wrap period | VDP plane width (512px) | `BGTextureWidth = 512` | YES |
| Per-frame BG scroll delta | scrollSpeed/2 px/frame | Same computation | YES |

### Wave Spawn System

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| Timer init value | `$2E = 5` (set in routine 0x0A exit) | `waveTimer = 5` (set in routine 12 exit) | YES |
| Spawn cycle period | 6 frames (5→4→...→0→-1 trigger, reset 5) | Same | YES |
| Spawn X condition | `cmpi.w #$80,x_pos; blo skip` | `if (currentX >= 0x80)` | YES |
| Wave movement | `move.w $40(parent),d0; sub.w d0,x_pos` | `currentX -= parent.getScrollSpeed()` | YES |
| Active during routines | 0x0E, 0x10, 0x12 (via Obj_Wait calls) | 14, 16, 18 (via tickWaveSpawn calls) | YES |

### BG Transition Trigger (Intro → Normal Deformation)

ROM source: Dynamic_resize_routine 2 at `s3.asm` line 32140, BG event at line 70001.

| Aspect | ROM | Java | Match? |
|--------|-----|------|--------|
| Trigger position | `cmpi.w #$1400,(Camera_X_pos); blo` | `cameraX >= 0x1400` | YES |
| Signal mechanism | `st (Events_fg_5).w` | `mainLevelPhaseActive = true` | YES |
| Decompression gate | `tst.w (Kos_decomp_queue_count); bne` → keep intro mode until art loaded | No equivalent gate | **NO** |

### Minor Differences (Cosmetic Only — Do Not Affect Scroll Speed)

| Aspect | ROM | Java | Impact |
|--------|-----|------|--------|
| Routine 0x04 exit frame | MoveSprite2 NOT called (`beq` branches before `jmp MoveSprite2`) | moveSprite2() called with yVel=0 | ~3px extra X movement |
| Routine 0x04 exit check | `beq` (exactly 0) | `<= 0` | Same result (0x600 divides evenly by 0x18) |
| Routine 0x0A yVel | Set to 0 once in routine 0x08 exit | Set to 0 every frame | Redundant but correct |
| Wave deletion order | Check x>=0x60 THEN subtract speed | Subtract speed THEN check x<0x60 | Wave deletes 1 frame earlier |
| Wave spawn X check | Obj_Wait callback checks pre-increment X | tickWaveSpawn checks post-increment X | Spawn position off by 4px |

## Conclusion

### Root Cause Found (2026-02-16)

**The ocean scrolled too slowly because the engine missed a scroll speed change at `Swing_Setup1`.**

In the ROM, `$40(a0)` is a dual-purpose field: `Swing_Setup1` (sonic3k.asm:136829) writes `0x10` to it as the swing acceleration parameter for `Swing_UpAndDown`, but `sub_67A08` reads the same field every frame as the scroll speed. The Java engine split these into separate variables — `SWING_ACCEL` (used only for swing math) and `scrollSpeed` (used only for scroll) — and never updated `scrollSpeed` when entering the swing phase.

**Impact:** For ~340 frames (routines 0x06 through 0x0E), the engine scrolled at speed 8 instead of 16 — half the correct rate. The ocean background moved at 4 px/frame instead of 8 px/frame during the entire swing, lift-off, landing, flash, and walk-right phases. This made the ocean phase feel sluggish and the overall intro timing ~5.7 seconds too long.

**Fix:** One line in `routine4Descent()` at the `Swing_Setup1` equivalent:
```java
scrollSpeed = SWING_ACCEL;  // ROM: Swing_Setup1 writes 0x10 to $40(a0)
```

### Lesson Learned

The original investigation compared every field individually and confirmed each matched the ROM — but it analyzed `$40(a0)` as "scroll speed" and `Swing_Setup1` as "swing parameters" separately, never recognizing they were the same physical field. The assumption that `$40` was *only* a scroll speed variable (with 3 explicit writes) blinded the analysis to the 4th write hidden inside a swing initialization routine.

When the ROM uses a single field for multiple purposes, splitting it into separate engine variables requires updating *all* consumers at *every* write site — not just the ones that match the variable's name.

### Previous Hypotheses (Invalidated)

The original investigation concluded "every scroll calculation matches the ROM" and proposed frame rate drops, FBO content issues, or spawn ordering as causes. These were all wrong — the root cause was a simple missed speed change hiding in plain sight behind the dual-purpose `$40` field.

## Key Files Reference

| File | Role |
|------|------|
| `game/sonic3k/objects/AizPlaneIntroInstance.java` | Intro state machine, scroll speed control |
| `game/sonic3k/scroll/SwScrlAiz.java` | AIZ scroll handler, deformation bands |
| `game/sonic3k/objects/AizIntroWaveChild.java` | Wave child (uses parent scrollSpeed for movement) |
| `level/scroll/M68KMath.java` | Pack/unpack scroll words |
| `level/scroll/ParallaxManager.java` (replaced by `game/sonic3k/scroll/`) | Dispatches to zone handlers |
| `graphics/HScrollBuffer.java` | GPU upload of per-line scroll |
| `level/render/BackgroundRenderer.java` | FBO render + parallax shader pass |
| `resources/shaders/shader_parallax_bg.glsl` | Per-scanline scroll GLSL shader |
| `level/LevelManager.java:1140-1275` | BG render pipeline, FBO setup |
| `Engine.java` | Game loop timing, fixed-timestep accumulator |

## ROM Disassembly References

| Symbol | s3.asm Line | Address | Purpose |
|--------|------------|---------|---------|
| `Obj_AIZPlaneIntro` | 81188 | — | Main intro object entry point |
| `loc_45888` (routine 0) | 81214 | — | Init routine |
| `loc_458F0` (routine 2) | 81237 | — | Wait routine (Obj_Wait) |
| `sub_45DE4` | 81664 | — | scrollVelocity (S3 version) |
| `sub_67A08` | 135940 (sonic3k.asm) | — | scrollVelocity (S&K version, identical) |
| `AIZ1_IntroDeform` | 70207 | — | Band value computation |
| `AIZ1_IntroDeformArray` | 70455 | — | Deformation segment heights |
| `ApplyDeformation` | 68687 | — | Per-scanline HScroll write |
| `AIZ1BGE_Intro` | 70001 | — | Per-frame BG event handler |
| `AIZ1_Resize (routine 2)` | 32138 | — | Sets Events_fg_5 at Camera_X >= 0x1400 |
| `Swing_Setup1` | 136829 (sonic3k.asm) | — | Init swing params — **writes 0x10 to $40 (scroll speed + accel)** |
| `Swing_UpAndDown` | 177851 (sonic3k.asm) | — | Oscillate y_vel using $40 as acceleration |
| `Obj_Wait` | 102190 | — | Timer countdown with callback |
