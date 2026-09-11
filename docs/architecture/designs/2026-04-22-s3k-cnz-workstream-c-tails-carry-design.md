# S3K CNZ Workstream C - Tails-Carry Intro Design

**Parent:** `docs/architecture/designs/2026-04-22-s3k-cnz-trace-replay-design.md` §7.1
**Baseline:** `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md` (first 20 divergences, all flagged C)
**Date:** 2026-04-22 (v3 - second-round engine-accuracy corrections: method names, gravity mitigation, player-mode accessor)
**Scope:** Only the CNZ1 Sonic+Tails carry-in sequence. Other Tails-carry entries (AIZ1, MGZ2, LBZ2, etc.) are explicitly out of scope.

## 1. Problem

When `TestS3kCnzTraceReplay` replays the committed BK2 (Sonic+Tails, level-select launched into CNZ1), the engine diverges at **frame 1**:

```
Frame 1: x_speed mismatch - expected 0x0100, actual 0x0000
Frame 2: y_speed mismatch - expected 0x0010, actual 0x00A8
Frame 43: air   mismatch - expected 1,       actual 0
```

Cause (per research Task 8b, confirmed by ROM disassembly read at `docs/skdisasm/sonic3k.asm`): on real hardware, Tails picks up Sonic on frame 0 and flies rightward for roughly 100 frames before Sonic lands and the carry releases. The engine instead spawns Sonic free-standing at the recorder's start position `(0x0018, 0x0600)` and lets him fall on his own physics immediately.

The trace baseline shows all 20 first-divergences flagged `C`, with the window extending to at least frame 363. Until the carry-in is fixed, the cascaded errors mask the remaining D/E/F/G workstreams; per design spec §7 and the plan's Task 8 Step 3, C is the gating workstream.

## 2. Goals

- Replay the Sonic+Tails CNZ1 carry-in with ROM-byte parity for the first ~100 frames (`x_speed`, `y_speed`, `x_pos`, `y_pos`, `air`, `rolling`, `object_control`, `anim` byte all within trace tolerance).
- Let the carry release into normal physics naturally, so that **the first divergence frame shifts to > 400** and the baseline can be re-captured to drive D/E/F/G triage.
- Keep `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, and `TestSonic3kDecodingUtils` green throughout.
- Keep `TestS3kAizTraceReplay` in its pre-existing red state (per `docs/architecture/audits/s3k-zones/cnz-task7-regression.md`); do not try to fix AIZ from within this workstream.

## 3. Non-goals

- **Carry-in for zones other than CNZ1.** AIZ1, MGZ2, LBZ2, SSZ start, and any other zone's Tails-carry intro stay out of scope. The Tails CPU routines implemented here must be general enough to handle the CNZ1 path without regressing the existing AIZ1 Tails follow path, but new zone-specific triggers are deferred to later workstreams.
- **Knuckles-only or Tails-only Player_mode variants.** This spec covers only `Player_mode == 0` (Sonic + Tails). Player_mode 1/2/3 entries at CNZ1 keep the current engine behaviour. The ROM's CNZ1 zone check in `loc_13A32` fires unconditionally; the Player_mode branching happens inside the carry body (`loc_14082`) and diverts to routines 6/0x10 for Sonic-alone/Tails-alone. For non-`Player_mode==0` the engine will continue to run its current pre-carry behaviour; there is no regression surface because the current engine doesn't invoke the carry path at all.
- **Visual parity.** The carry-in animation frames, flight-wing timing, sparkle effects, and the `AniRaw_Tails_Carry` mapping-frame sequence (`$91,$91,$90×6,$92×6,$91,$91,$FF`) are not verified by the trace and are out of scope. Only the physics-visible state (position, velocity, `object_control`, `anim` indexing byte) matters.
- **Re-capture of the trace.** The existing BK2 + CSV + JSONL stay byte-identical; we make the engine match them.

## 4. Baseline evidence

From `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md` (first 20 divergences, all start before frame 281):

| # | Frame (start) | Field | Expected | Actual | Interpretation |
|---|---------------|-------|----------|--------|----------------|
| 1 | 1 | `x_speed` | `0x0100` | `0x0000` | Tails carrying Sonic right at constant 0x0100 |
| 2 | 2 | `y_speed` | `0x0010` | `0x00A8` | Carry-descent trickle vs. engine's free fall |
| 3 | 43 | `air` | `1` | `0` | Sonic is in the air (being carried) for 43+ frames |
| 4 | 106 | `g_speed` | `0x0148` | `0x0000` | Post-release ground-speed handoff |
| 5 | 107 | `y_speed` | `-0100` | `0x0000` | First bumper bounce reaction, only possible if positioned by carry |
| 6 | 193 | `y_speed` | `0x0000` | `0x0530` | Second bumper interaction without the carry-placed position |

Row #20 ends at frame 363, so the carry-in window effectively covers **0..~400 frames**, though the actual carry body is only active for ~100 frames (frame 0 through roughly frame 105; the first post-release value shows at frame 106's `g_speed=0x0148`, consistent with a ground-contact release shortly before). After frame ~400 the cascaded ripples from the intro mispositioning continue deeper into CNZ1, but the direct carry window is ~100 frames.

## 5. ROM mechanic reference

All addresses listed below are `sonic3k.asm` (S&K-side) labels. The 68K addresses will be resolved via `RomOffsetFinder --game s3k search <label>` during implementation and added to `Sonic3kConstants.java`; no S3-side (`s3.asm`) offsets may be substituted.

### 5.1 Driving variables

| Name | RAM (expected) | Purpose |
|------|----------------|---------|
| `Tails_CPU_routine` | word | Tails sidekick AI state machine selector |
| `Flying_carrying_Sonic_flag` | byte + 1-byte cooldown | Global "Tails is carrying Sonic" flag plus post-release cooldown |
| `_unkF744` | 4 bytes | Tails-latched copy of Sonic's x_vel (used to detect external vel writes) |
| `_unkF74C` | 4 bytes | Tails-latched copy of Sonic's y_vel |
| `Current_zone_and_act` | word | CNZ1 trigger reads `$0300` here |
| `Player_mode` | word | `0`=Sonic+Tails, `1`=Sonic alone, `2`=Tails alone, `3`=Knuckles |
| `Ctrl_2_logical` | word | Tails's (controller 2) held + pressed buttons; written synthetically during carry |
| `Level_frame_counter` | word | Low byte drives the per-32-frame right-button injection cadence |

### 5.2 Tails_CPU_routine values

| Routine | Meaning | Label (sonic3k.asm) |
|---------|---------|---------------------|
| `0x00` | Fresh init - dispatches on zone | `TailsCPU_Init` (body starts at `loc_13A10`; the CNZ branch is at `loc_13A32`) |
| `0x02` | Spawning (off-screen teleport to leader) | `TailsCPU_Spawning` |
| `0x04` | Approaching (flying toward leader after respawn) | `TailsCPU_Approaching` |
| `0x06` | Normal (on-screen follow) | `TailsCPU_Normal` |
| `0x08` | Panic | `TailsCPU_Panic` |
| **`0x0C`** | **Carry init - first tick after CNZ1 trigger** | **`loc_13FC2`** |
| **`0x0E`** | **Carrying (per-frame body)** | **falls through from `0x0C` into `0x20` body** |
| **`0x20`** | **Carrying (main body of the per-frame routine)** | **`loc_13FFA`** |

### 5.3 State machine

```
  level init (CNZ1 entry, any Player_mode; carry applies to Player_mode==0)
          │
          ▼
   Tails_CPU_routine = 0  (loc_13A10 dispatches; CNZ1 branch at loc_13A32)
          │
          │  cmpi.w #$300,(Current_zone_and_act).w
          │  beq    loc_13A32
          │
          ▼
   loc_13A32 body:
     move.w  #$18,x_pos(a0)                  ; Teleport Tails to (0x0018, 0x0600)
     move.w  #$600,y_pos(a0)
     move.b  #2,status(a0)                   ; Face-dir + air flag per ROM
     clr.w   (Tails_CPU_idle_timer).w
     clr.w   (Tails_CPU_flight_timer).w
     move.w  #$C,(Tails_CPU_routine).w
          │
          ▼
   routine 0x0C  (loc_13FC2)
     double_jump_flag      ← 1                (flight active)
     double_jump_property  ← (8*60)/2 = 240   (set but NOT used as a carry-release timer - see §5.5)
     status.Status_InAir   ← 1
     Tails.x_vel           ← 0x0100
     Tails.y_vel           ← 0
     Tails.ground_vel      ← 0
     bsr.w  sub_1459E                        ; Pick up Sonic (see §5.4)
     Flying_carrying_Sonic_flag ← 1
     Flying_carrying_Sonic_flag+1 ← 0         (cooldown counter, unused on init)
     Tails_CPU_routine     ← 0x0E
          │
          ▼  fall through (no rts)
   routine 0x20 body  (loc_13FFA)
     every (Level_frame_counter+1) & 0x1F == 0:
       Ctrl_2_logical ← (button_right_mask<<8) | button_right_mask   ; inject held+pressed right
     if Player_1.status.Status_InAir == 0:
       Tails_CPU_routine ← 6                                          ; GROUND RELEASE (§5.5)
       Flying_carrying_Sonic_flag ← 0
       Player_1.object_control ← 0
       Player_1.anim ← 0
       ...                                                             ; drop back to NORMAL
       rts
     ; else continue carry
     bsr.w  Tails_Carry_Sonic                                          ; per-frame parentage (§5.6)
          │
          ▼  (next frame)
   routine 0x0E  (same loc_13FFA body)
          │
          ▼  eventually one of 3 release paths fires (§5.5)
    Tails_CPU_routine ← 0x06
    Flying_carrying_Sonic_flag+1 ← cooldown frames   (0x12 or 0x3C; prevents immediate re-grab)
```

### 5.4 `sub_1459E` (Sonic pickup semantics)

Verified at disasm lines 27382-27413. On pickup, on Sonic (`Player_1`):

- `Sonic.x_pos` ← `Tails.x_pos`
- `Sonic.y_pos` ← `Tails.y_pos + 0x1C`  (28-pixel descend offset)
- `Sonic.object_control` ← `0x03`  (line 27393; rides Tails)
- `Sonic.anim`  ← `0x2200`  (word write; high byte = 0x22 "carried" animation, low byte = `prev_anim` cleared)
- `Sonic.status.Status_InAir` ← `1`
- `Sonic.x_vel` ← `Tails.x_vel` (= 0x0100)
- `Sonic.y_vel` ← `Tails.y_vel` (= 0)
- `Sonic.ground_vel` ← `0`
- `Sonic.angle` ← `0`
- `Sonic.status.Status_RollJump` ← `0`
- `Sonic.spin_dash_flag` ← `0`

**Not set:** there is no `interact` (riding-parent pointer) write. The carry is coordinated by the global `Flying_carrying_Sonic_flag` + the driver's state, not by per-object parentage.

### 5.5 Release conditions (THE critical correction)

The spec originally described a `double_jump_property` countdown; **that was wrong**. Verified at lines 26915-26950 and 27222-27332, the ROM has three release paths, and `double_jump_property` is set at init but never decremented in the 0x0E/0x20 body (it is re-stamped by unrelated routines `loc_1408A` / `loc_14106`).

**Path A - Ground contact (most common for CNZ1).** In the routine 0x20 body at `loc_14016` (lines 26926-26946), on every tick:
```
btst  #Status_InAir,status(Player_1)
bne.s <stay carrying>
; else:
move.w  #6,(Tails_CPU_routine).w
clr.b   (Flying_carrying_Sonic_flag).w
clr.b   object_control(Player_1)
clr.w   anim(Player_1)
move.b  #1<<Status_InAir,status(Tails)   ; Tails stays in-air post-release
; Player_mode branch: if Player_mode==1 (Sonic alone) detour to routine $10
rts
```
Triggered when Sonic's `Status_InAir` bit clears. Sonic's in-air status is updated by the `SonicKnux_DoLevelCollision` call inside `Tails_Carry_Sonic` the previous frame (see §5.6).

**Path B - Jump button press.** Inside `Tails_Carry_Sonic` at lines 27237-27265:
```
move.b  (Ctrl_1_Press).w,d0
andi.b  #button_A_mask | button_B_mask | button_C_mask,d0
beq.s   <no jump>
; else: release + convert to jump
move.w  #-$380,y_vel(Player_1)
move.w  #$200,x_vel(Player_1)              ; or -$200 if facing left
set  Status_RollJump
anim(Player_1) ← 2                          ; jump animation
clr.b   object_control(Player_1)
move.w  #6,(Tails_CPU_routine).w
clr.b   (Flying_carrying_Sonic_flag).w
Flying_carrying_Sonic_flag+1 ← $12          ; cooldown (~18 frames)
```
Any of A / B / C pressed this frame → release + impart a jump velocity.

**Path C - External velocity change (latch mismatch).** Inside `Tails_Carry_Sonic` at lines 27229-27234:
```
; compare Sonic's current vel to the latched copy from last frame
cmp.l   _unkF744,x_vel(Player_1)     ; x_vel latch check
bne.w   loc_14466                    ; mismatch => release (object pushed Sonic)
cmp.l   _unkF74C,y_vel(Player_1)     ; y_vel latch check
bne.w   loc_14466
```
`loc_14466` releases the carry with cooldown `$3C` (~60 frames). This path fires if any object (spring, bumper, monitor, boss) writes to Sonic's velocity while carried. CNZ1's bumper field at x ≈ 256+ will trigger this.

**Also (priority above A-C):** if Sonic's routine >= 4 (hurt/dead at line 27225), release immediately.

Post-release: `Flying_carrying_Sonic_flag` itself is cleared, but `Flying_carrying_Sonic_flag+1` holds a cooldown counter (`$12` for jump release, `$3C` for latch-mismatch release). While the cooldown > 0, the carry cannot be re-established. For CNZ1 the cooldown is irrelevant because the carry does not re-trigger after the intro; the CNZ1 zone check in routine 0 is a one-shot.

### 5.6 `Tails_Carry_Sonic` (per-frame parentage)

Verified at lines 27222-27332. Each frame during routine 0x0E / 0x20 (assuming no release fires), in order:

1. **Hurt/dead check** (27225): if `routine(Player_1) >= 4`, release.
2. **Release path C** (27229-27234): compare Sonic.x_vel / y_vel to `_unkF744` / `_unkF74C`; mismatch → release.
3. **Release path B** (27237-27265): A/B/C pressed → release + jump.
4. **Render-flag merge** (27294-27297): low 2 bits of `render_flags(Player_1)` ← Tails's status bit 0 (face-dir mirroring).
5. **Position parentage** (27284-27286, 27298-27300): `Sonic.x_pos ← Tails.x_pos`, `Sonic.y_pos ← Tails.y_pos + 0x1C`. Reverse-gravity handling at 27287-27289 inverts the offset; not relevant to CNZ1.
6. **Animation cycle** (27303-27316): `AniRaw_Tails_Carry` mapping-frame sequence (`$91,$91,$90×6,$92×6,$91,$91,$FF`) with `$B` frame-delay. Out of scope for physics parity.
7. **DPLC upload** (27320): `Perform_Player_DPLC` re-uploads Sonic's sprite tiles.
8. **Velocity latch update** at `loc_144F8` (27324-27327): `Sonic.x_vel ← Tails.x_vel`; `_unkF744 ← Tails.x_vel`; `Sonic.y_vel ← Tails.y_vel`; `_unkF74C ← Tails.y_vel`. This is the step that produces `x_speed=0x0100` on the trace.
9. **Level collision** (27329-27331): `SonicKnux_DoLevelCollision(Player_1)` runs normally on Sonic. If Sonic hits the floor, his `Status_InAir` bit clears, which is what path A detects on the NEXT tick.

**Not set by Tails_Carry_Sonic:**
- `Sonic.inertia` is **not** written (the earlier draft of this spec claimed `Sonic.inertia ← 0`; that is incorrect).
- `Sonic.g_speed` is not written (ground_vel is only zeroed at pickup in `sub_1459E`).

### 5.7 CNZ1 zone check + Player_mode gate

The zone check at `loc_13A32` (line 26405) is `cmpi.w #$300,(Current_zone_and_act).w` / `beq`. Any `Player_mode` passing through routine 0 at CNZ1 entry runs the carry setup. The Player_mode divergence happens inside the carry body at `loc_14082` (line 26944): if `Player_mode == 1` (Sonic alone), the ROM detours to `Tails_CPU_routine = $10` (solo Tails-flight-home), which is out of scope for this workstream.

**For the engine, we gate the carry trigger on `Player_mode == 0` at level-init time** (not inside the body) to match how the trace-recorded BK2 was captured. This differs slightly from ROM dispatch order but is equivalent for the BK2's fixed `Player_mode == 0` run.

## 6. Engine gap analysis

### 6.1 What exists

- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` - sidekick AI driver with states `{INIT, SPAWNING, APPROACHING, NORMAL, PANIC}` mapping ROM routines 0/2/4/6/8. `mapRomCpuRoutine` at lines 525-535 **throws** on unknown routine values including the carry routines 0x0C / 0x0E / 0x20.
- `hydrateFromRomCpuState(cpuRoutine, controlCounter, respawnCounter, interactId, jumping)` at lines 504-515 for trace replay bootstrap.
- Driver input outputs: `getInputLeft/Right/Up/Down/Jump` + `getInputJumpPress` (lines 537-548). **Note:** the name is `getInputJumpPress`, not `getInputJumpPressed`.
- `AbstractPlayableSprite`:
  - `setObjectControlled(boolean)` / `isObjectControlled()` (lines 1992-2006)
  - `setLatchedSolidObjectId(int)` (line 1194; normally used for bridges/monitors)
  - `setForcedAnimationId(int)` (line 1017; `-1` clears)
  - `isJumpPressed()` / `isJumpJustPressed()` (lines 2062, 2069)
  - `getInputHistory(int)` / `getStatusHistory(int)` (lines 3101, 3114)
  - `setXSpeed(short)` / `setYSpeed(short)` in subpixel units (256 = 1 px/frame; see field comment line 51)
  - `getAir()` (line 1102) — grounded = `!getAir()`. **There is no `isGrounded()` method.**
- `AbstractSprite` (parent of `AbstractPlayableSprite`): `setCentreXPreserveSubpixel(short)` / `setCentreYPreserveSubpixel(short)` (lines 86, 95).
- `PlayableSpriteMovement` gates the self-movement pipeline on `isObjectControlled()` at multiple call sites (lines 211, 470, 1743, 1998) plus `SuperStateController`. **The gates do NOT live in `AbstractPlayableSprite.update()` itself**, so any physics-bypass changes this workstream needs should be additions in `PlayableSpriteMovement`, not the base sprite class.
- `Sonic3k.loadLevel(int levelIdx)` at `Sonic3k.java:187` - per-zone branches already exist (AIZ1 intro at lines 227-245).
- `GameModule.onLevelLoad(...)` (`GameModule.java:286`) is the per-game level-load hook.
- `HeadlessTestFixture` supports `withZoneAndAct(3, 0)` + `MAIN_CHARACTER_CODE` / `SIDEKICK_CHARACTER_CODE` (lines 116-120, 165-166) - CNZ1 Sonic+Tails bootstrap is directly supported.

### 6.2 What is missing

1. **Carry state-machine states** in `SidekickCpuController`: routines 0x0C / 0x0E / 0x20 have no engine equivalent; `mapRomCpuRoutine` rejects them.
2. **CNZ1-entry trigger.** Nothing in `Sonic3kLevelEventManager`, `Sonic3kCNZEvents`, or the Tails sidekick init path detects "CNZ1 + Player_mode == 0" and primes the driver into carry mode. Tails currently spawns in `NORMAL` (routine 6) at the recorder's Player_2 position.
3. **Tails position override on trigger.** The ROM teleports Tails to `(0x0018, 0x0600)` on the routine 0 → 0x0C transition. The engine spawns Tails from `START_LOC_ARRAY` at a per-character offset; nothing resets him to the carry-in position.
4. **Carry physics bypass.** When `Sonic.object_control == 3`, `PlayableSpriteMovement` must skip self-driven x/y velocity updates for Sonic (the driver writes them). The current gates stop rolling / jumping / ground-mode self-updates but still apply gravity. Audit needed for the specific gate set.
5. **Per-frame parentage.** No engine path copies Tails' x/y/vel into Sonic each tick.
6. **Velocity latch.** No engine equivalent of `_unkF744` / `_unkF74C` exists; the latch must be kept on the driver.
7. **Input injection for Tails.** The carrying routine 0x20 injects a synthetic "right" (held + pressed) press into Tails's controller buffer every 32 frames. `SidekickCpuController` already emits inputs via `inputRight` etc., but has no "injected-motion + carry-payload" mode.
8. **Release handling.** Need to implement all three release paths (§5.5) with the correct priority and cooldown semantics.
9. **Trace bootstrap state.** `hydrateFromRomCpuState` rejects carry routine values; extend to accept 0x0C / 0x0E / 0x20 and restore the latch + cooldown state.

### 6.3 Constants / ROM addresses needed

ROM addresses to resolve via `RomOffsetFinder --game s3k search <label>` during implementation:

| Constant (Java) | ROM label / literal | Purpose |
|-----------------|--------------------|---------|
| `TAILS_CPU_ROUTINE_ADDR` | `Tails_CPU_routine` | RAM address (debug probe + trace hydrator) |
| `FLYING_CARRYING_SONIC_FLAG_ADDR` | `Flying_carrying_Sonic_flag` | RAM address (debug probe + trace hydrator) |
| `CARRY_DESCEND_OFFSET_Y` | literal `0x1C` | Sonic hangs 28 px below Tails's centre |
| `CARRY_INIT_TAILS_X` | literal `0x0018` | Teleport target on CNZ1 trigger |
| `CARRY_INIT_TAILS_Y` | literal `0x0600` | Ditto |
| `CARRY_INIT_TAILS_X_VEL` | literal `0x0100` | Constant horizontal flight velocity during carry |
| `CARRY_INPUT_INJECT_MASK` | literal `0x1F` | `(Level_frame_counter+1 & MASK) == 0` injects right press (both held+pressed) |
| `CARRY_COOLDOWN_JUMP_RELEASE` | literal `0x12` | Cooldown frames after A/B/C release (~18) |
| `CARRY_COOLDOWN_LATCH_RELEASE` | literal `0x3C` | Cooldown frames after external-vel release (~60) |
| `CARRY_SONIC_ANIM_BYTE` | literal `0x22` | Sonic's `anim` field high byte during carry |
| `CARRY_RELEASE_JUMP_Y_VEL` | literal `-0x380` | Post-release y_vel imparted by A/B/C release |
| `CARRY_RELEASE_JUMP_X_VEL` | literal `0x200` | Post-release x_vel (sign = face-dir) |

Zone trigger:

| Constant (Java) | Value | Purpose |
|-----------------|-------|---------|
| `CARRY_TRIGGER_ZONE_ACT_WORD` | `0x0300` | `Current_zone_and_act` match for CNZ1 |
| `CARRY_TRIGGER_PLAYER_MODE` | `0` | `Player_mode == 0` (Sonic + Tails) |

All numeric offsets MUST come from `sonic3k.asm` (S&K-side, addresses < 0x200000) per `CLAUDE.md`. `RomOffsetFinder` output for each constant is captured in the commit message.

## 7. Design

### 7.1 Target: extend `SidekickCpuController`

Rather than introducing a new parallel driver, extend the existing one. The rationale:

- Trace replay already hydrates `SidekickCpuController` state from the ROM log. Adding carry routines as first-class states keeps the hydration path linear.
- Release-from-carry drops back into `NORMAL` (routine 0x06), which is already implemented. No new "re-entry" code needed.
- Keeping everything on one controller avoids cross-module coordination around who owns Tails at any given frame.

The S3K-specific trigger + constants live under `game/sonic3k/`, but the driver body stays in `sprites.playable` so zones other than CNZ1 can be handled later by the same state machine.

### 7.2 New driver states

Add two states to `SidekickCpuController.State`:

```java
public enum State {
    INIT,
    SPAWNING,
    APPROACHING,
    NORMAL,
    PANIC,
    CARRY_INIT,   // ROM routine 0x0C - first tick after trigger
    CARRYING      // ROM routine 0x0E / 0x20 - per-frame carry body
}
```

Update `mapRomCpuRoutine`:

```java
private static State mapRomCpuRoutine(int cpuRoutine) {
    return switch (cpuRoutine) {
        case 0x00 -> State.INIT;
        case 0x02 -> State.SPAWNING;
        case 0x04 -> State.APPROACHING;
        case 0x06 -> State.NORMAL;
        case 0x08 -> State.PANIC;
        case 0x0C -> State.CARRY_INIT;
        case 0x0E, 0x20 -> State.CARRYING;
        default -> throw new IllegalArgumentException(
                "Unsupported ROM Tails CPU routine: " + Integer.toHexString(cpuRoutine));
    };
}
```

Both 0x0E and 0x20 map to `CARRYING` because the ROM falls through from `0x0C` directly into the `0x20` body and stores 0x0E back into `Tails_CPU_routine` - the body code is a single block.

### 7.3 Game-agnostic trigger interface

Introduce a new interface in `com.openggf.sprites.playable` that describes *when* a sidekick should enter carry mode and *how* to position itself:

```java
public interface SidekickCarryTrigger {
    /**
     * Invoked at each INIT tick. If this returns true, the driver transitions to
     * CARRY_INIT on the current frame.
     *
     * @param zoneId       canonical zone id
     * @param actId        zero-based act id
     * @param playerMode   main player's character enum
     */
    boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode);

    /** Positions the carrier and cargo for the first CARRY_INIT tick. */
    void applyInitialPlacement(AbstractPlayableSprite carrier, AbstractPlayableSprite cargo);

    /** Sonic's descend offset below Tails's centre (ROM: 0x1C). */
    int carryDescendOffsetY();

    /** Constant horizontal velocity held while carrying (ROM: 0x0100). */
    short carryInitXVel();

    /** Level_frame_counter cadence mask (ROM: 0x1F = every 32 frames). */
    int carryInputInjectMask();

    /** Cooldown frames after A/B/C jump release (ROM: 0x12). */
    int carryJumpReleaseCooldownFrames();

    /** Cooldown frames after external-vel (latch mismatch) release (ROM: 0x3C). */
    int carryLatchReleaseCooldownFrames();

    /** Post-release jump y_vel (ROM: -0x380). */
    short carryReleaseJumpYVel();

    /** Post-release jump x_vel (ROM: 0x200). Sign applied from cargo face direction at release. */
    short carryReleaseJumpXVel();
}
```

This interface uses only primitives + `PlayerCharacter` (which exists in `com.openggf.game`) + types already in `com.openggf.sprites.playable`. The previous spec draft referenced a non-existent `LevelContext` type; that is replaced by primitives here so no new support types are introduced.

`Sonic3kGameModule` supplies a `Sonic3kCnzCarryTrigger` instance; `Sonic1GameModule` / `Sonic2GameModule` return `null` so their drivers behave exactly as today.

### 7.4 Driver state behaviour

**`updateInit` (modified).** At the top of the existing body, check the trigger:

```java
private void updateInit() {
    if (carryTrigger != null && leader != null) {
        LevelManager lm = sidekick.currentLevelManager();
        if (lm != null
                && carryTrigger.shouldEnterCarry(
                        lm.getCurrentZone(), lm.getCurrentAct(), resolvePlayerCharacter())) {
            carryTrigger.applyInitialPlacement(sidekick, leader);
            state = State.CARRY_INIT;
            return;
        }
    }
    // existing body: transition to NORMAL etc.
    state = State.NORMAL;
    ...
}

private static PlayerCharacter resolvePlayerCharacter() {
    // Canonical engine lookup: mirror LevelManager.java:1004-1007.
    // The authoritative source is the game's AbstractLevelEventManager subclass.
    GameModule gameModule = GameModuleRegistry.getCurrent();
    if (gameModule != null) {
        LevelEventProvider lep = gameModule.getLevelEventProvider();
        if (lep instanceof AbstractLevelEventManager alem) {
            return alem.getPlayerCharacter();
        }
    }
    return PlayerCharacter.SONIC_AND_TAILS;  // conservative default
}
```

**Player-mode accessor.** The engine does **not** have a `LevelManager.getCurrentPlayerMode()` method. The canonical lookup is via `AbstractLevelEventManager.getPlayerCharacter()` — see `LevelManager.java:1004-1007` for the reference pattern (used by the water-system seeder). Use this pattern here rather than adding a new passthrough to `LevelManager`; it keeps the accessor co-located with the authoritative source and avoids growing the `LevelManager` surface.

**`updateCarryInit` (new).** Runs for exactly one frame, then immediately performs one tick of `updateCarrying` (ROM fall-through from `0x0C` into `0x20` body on the same frame):

```java
private void updateCarryInit() {
    // sub_1459E semantics on Sonic (the leader = cargo)
    leader.setObjectControlled(true);
    leader.setAir(true);
    leader.setRolling(false);
    leader.setRollingJump(false);
    leader.setGSpeed((short) 0);
    leader.setXSpeed(carryTrigger.carryInitXVel());
    leader.setYSpeed((short) 0);
    leader.setForcedAnimationId(carriedAnimationId);   // high-byte 0x22 mapped through the sprite's anim table

    // Tails's per-carry state
    sidekick.setAir(true);
    sidekick.setXSpeed(carryTrigger.carryInitXVel());
    sidekick.setYSpeed((short) 0);
    sidekick.setGSpeed((short) 0);
    sidekick.setControlLocked(true);
    sidekick.setForcedAnimationId(flyAnimId);

    // Initialize the latch (Sonic's current vel matches the Tails vel we just wrote)
    carryLatchX = carryTrigger.carryInitXVel();
    carryLatchY = 0;
    flyingCarryingFlag = true;
    releaseCooldown = 0;

    state = State.CARRYING;
    // Fall through (match ROM 0x0C -> 0x20 same-frame continuation):
    updateCarrying();
}
```

**`updateCarrying` (new).** Runs each subsequent frame. Checks execute in ROM-equivalent order:

```java
private void updateCarrying() {
    // Release priority (match ROM order inside Tails_Carry_Sonic):
    // 1. Sonic routine >= 4 (hurt/dead) — engine uses isHurt() || getDead()
    //    NOTE: API names verified against AbstractPlayableSprite.java:
    //          isHurt()  at line 1480 (NOT getHurt())
    //          getDead() at line 1446
    if (leader.isHurt() || leader.getDead()) {
        releaseCarry(carryTrigger.carryLatchReleaseCooldownFrames());
        return;
    }
    // 2. External velocity change (latch mismatch)
    if (leader.getXSpeed() != carryLatchX || leader.getYSpeed() != carryLatchY) {
        releaseCarry(carryTrigger.carryLatchReleaseCooldownFrames());
        return;
    }
    // 3. A/B/C just-pressed
    if (leader.isJumpJustPressed()) {
        performJumpRelease();
        return;
    }
    // 4. Ground release (ROM routine 0x20 prelude, loc_14016): Sonic in-air bit clear
    //    We check it *before* re-applying position, since the previous frame's
    //    SonicKnux_DoLevelCollision already wrote the flag.
    if (!leader.getAir()) {
        releaseCarry(0);   // ground release has no cooldown in ROM
        return;
    }

    // Inject synthetic right press into Tails (held + pressed) on cadence
    if ((frameCounter & carryTrigger.carryInputInjectMask()) == 0) {
        inputRight = true;
        // pressed-edge: the ROM also writes into the "pressed" byte this frame.
        // Surface via a new field wired into AbstractPlayableSprite's controller
        // buffer read path, or re-use the existing controller2-held mechanism
        // with a "virtual pressed" overlay. See §7.6.
    }

    // Tails continues at constant x_vel; y_vel evolves per the normal Tails physics
    // (gravity + flight). The engine's Tails physics pipeline already runs before
    // the driver; we overwrite x_vel to re-clamp to the carry velocity.
    sidekick.setXSpeed(carryTrigger.carryInitXVel());

    // Sonic parentage (Tails_Carry_Sonic semantics):
    //   Sonic.x_pos = Tails.x_pos
    //   Sonic.y_pos = Tails.y_pos + 0x1C
    //   Sonic.x_vel = Tails.x_vel
    //   Sonic.y_vel = Tails.y_vel
    //   Sonic.g_speed = 0 (not written by ROM Tails_Carry_Sonic, but remains 0 from pickup)
    leader.setCentreXPreserveSubpixel((short) sidekick.getCentreX());
    leader.setCentreYPreserveSubpixel(
            (short) (sidekick.getCentreY() + carryTrigger.carryDescendOffsetY()));
    leader.setXSpeed(sidekick.getXSpeed());
    leader.setYSpeed(sidekick.getYSpeed());

    // Refresh the latch after our write so next frame's compare is against the
    // values we just wrote, not stale ones.
    carryLatchX = leader.getXSpeed();
    carryLatchY = leader.getYSpeed();
}
```

**`performJumpRelease` (new).**

```java
private void performJumpRelease() {
    short xVel = leader.getDirection() == Direction.LEFT
            ? (short) -carryTrigger.carryReleaseJumpXVel()
            : carryTrigger.carryReleaseJumpXVel();
    leader.setXSpeed(xVel);
    leader.setYSpeed(carryTrigger.carryReleaseJumpYVel());
    leader.setRollingJump(true);
    // anim ← 2 (jump) per ROM
    leader.setForcedAnimationId(leader.resolveAnimationId(CanonicalAnimation.JUMP));
    releaseCarry(carryTrigger.carryJumpReleaseCooldownFrames());
}
```

**`releaseCarry` (new).**

```java
private void releaseCarry(int cooldownFrames) {
    leader.setObjectControlled(false);
    leader.setForcedAnimationId(-1);
    sidekick.setControlLocked(false);
    sidekick.setForcedAnimationId(-1);
    flyingCarryingFlag = false;
    releaseCooldown = cooldownFrames;
    state = State.NORMAL;
    normalFrameCount = 0;
}
```

**Per-frame cooldown decrement.** Added at the top of `update(int)` for all states: if `releaseCooldown > 0`, decrement. This matches the ROM's cooldown byte which ticks down regardless of the CPU routine.

The method signatures on `AbstractPlayableSprite` called above (`isJumpJustPressed`, `isHurt`, `getDead`, `getAir`, `setXSpeed`, `setYSpeed`, `setRollingJump`, `getDirection`, `resolveAnimationId`) all exist today; **no new sprite-level API is introduced**. Three method-name corrections from earlier drafts, all verified against the current engine source:
- `isJumpJustPressed()` — NOT `getInputJumpPressed` (AbstractPlayableSprite.java:2069)
- `!getAir()` for the "grounded" check — NOT `isGrounded()` (AbstractPlayableSprite.java:1102; no `isGrounded()` exists)
- `isHurt()` — NOT `getHurt` (AbstractPlayableSprite.java:1480; the getter uses the `is` prefix because hurt is a boolean flag)
- `getDead()` is correct (AbstractPlayableSprite.java:1446)

### 7.5 Physics bypass while `object_control == 3`

The gates that stop self-movement while an object controls the sprite live in `PlayableSpriteMovement` (not `AbstractPlayableSprite.update()`). Existing call sites at lines 211, 470, 1743, 1998 already short-circuit rolling, spindash, input-driven ground speed, level-boundary checks, and `resetOnFloor` — verified against `PlayableSpriteMovement.java` at those exact lines; all four use `sprite.isObjectControlled()` as the gate predicate.

The audit needed for the carry:

- **Gravity (BLOCKING — must be gated).** There is NO separate `applyGravity()` method in the engine. Gravity is inlined at TWO call sites inside `PlayableSpriteMovement`:
  - Line 1731, inside `doObjectMoveAndFall()`: `sprite.setYSpeed((short) (oldYSpeed + sprite.getGravity()));  // Apply gravity first`
  - Line 2276, inside another physics step: `sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getGravity()));`

  Neither write is currently gated on `isObjectControlled()`. This is the root cause of the latch-interference risk in §9.1: gravity will add to Sonic's y_vel between frames, triggering the latch-mismatch release path on the very first check. The mitigation is to wrap **both** writes with `if (!sprite.isObjectControlled())`. Prefer refactoring the two identical expressions into a private `applyGravity()` helper in `PlayableSpriteMovement`, gate the helper, and replace both call sites — this makes the gate a single-point change instead of two parallel edits. This is a **required** change for this workstream, not a best-effort TODO.

- **Input processing.** `object_control != 0` already gates input-driven ground speed in `PlayableSpriteMovement` at line 211. OK — no change needed.

- **Collision resolution.** The ROM runs `SonicKnux_DoLevelCollision` on the carried Sonic each frame (§5.6 step 9). The engine's collision pipeline must also run on Sonic during carry, so the in-air bit updates correctly when Tails descends into terrain. **Do NOT gate collision on `object_control`.** Existing code does not gate collision this way; verify during implementation that nothing inserted by this workstream accidentally does.

- **Level-boundary clamping.** Already gated at line 1743 (`if (sprite.isObjectControlled()) { return; }`). OK.

- **`resetOnFloor` state cleanup.** Already gated at line 1998. OK.

- **Animation.** `setForcedAnimationId(carriedAnimationId)` suppresses the normal animation scheduler. OK.

### 7.6 Input injection for Tails's synthetic right-press

Two options:
1. **Piggyback on `controller2Held`.** `SidekickCpuController` already reads `controller2Held` in manual-control mode. Add a parallel `controller2Synthetic` field that the driver sets internally and OR-s into `inputRight` on the injection cadence. Does not affect external input paths.
2. **Direct `inputRight = true` for one tick.** Set the flag only on the injection frame; let it fall through to the normal input consumer.

Option 2 is simpler and fits the existing driver architecture. The spec prescribes option 2. The "pressed" (edge) byte distinction from the ROM does not matter for the engine because Tails's own physics doesn't use `pressed` during carrying routine 0x20 - only `held`.

### 7.7 Trace hydration

Extend `hydrateFromRomCpuState` to accept 0x0C / 0x0E / 0x20 (already added to `mapRomCpuRoutine`). Additionally, when the routine is carry-related, hydrate:
- `flyingCarryingFlag` from the trace's `aux_state.jsonl` (if not present, add to recorder Lua; CNZ1 starts at routine 0 so this is a future-proofing concern, not a current blocker)
- `carryLatchX` / `carryLatchY` from the trace (if present)
- `releaseCooldown` from the ROM's `Flying_carrying_Sonic_flag+1` byte

For the CNZ1 trace the starting state is routine 0 (pre-carry), so hydration just needs to not-throw on 0x0C/0x0E/0x20; no real mid-carry state is restored.

### 7.8 Files touched

| File | Change kind | What |
|------|-------------|------|
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Modify | Add `CARRY_INIT`, `CARRYING` states; add `updateCarryInit`, `updateCarrying`, `releaseCarry`, `performJumpRelease`; extend `mapRomCpuRoutine`; add `carryTrigger`, `carryLatchX`, `carryLatchY`, `flyingCarryingFlag`, `releaseCooldown` fields + accessors; extend `reset()` to clear carry state. |
| `src/main/java/com/openggf/sprites/playable/SidekickCarryTrigger.java` | **New** | Game-agnostic trigger interface (see §7.3). |
| `src/main/java/com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java` | **New** | CNZ1-specific implementation. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` | Modify | Add `getSidekickCarryTrigger()` accessor returning the CNZ1 trigger instance. |
| `src/main/java/com/openggf/game/GameModule.java` | Modify | Add default `getSidekickCarryTrigger()` returning `null`; non-S3K modules fall back to today's behaviour. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3k.java` | Modify | In `loadLevel` (or via `onLevelLoad` if cleaner), if the zone is CNZ1 and `PlayerCharacter == SONIC_AND_TAILS`, call `sidekickController.setCarryTrigger(gameModule.getSidekickCarryTrigger())` and pre-seed `setInitialState(State.INIT)` to force the next tick through the trigger check. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | Modify | Add the literal constants from §6.3 (no new ROM addresses needed beyond existing work; all literals are either hand-copied from the disasm or resolved via `RomOffsetFinder --game s3k search`). |
| `src/main/java/com/openggf/level/LevelManager.java` | No modification | Player-mode lookup uses the canonical pattern from `LevelManager.java:1004-1007` (game module → level event manager → `getPlayerCharacter()`); no new accessor added. |
| `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java` | **Modify (required)** | Extract the gravity writes at lines 1731 and 2276 into a private `applyGravity(sprite)` helper; add `if (sprite.isObjectControlled()) return;` at the top of the helper. Verified via Grep: both sites currently ungated. See §7.5 and §9.1 for why this is blocking, not optional. |
| `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` | No modification expected | All API surface exists today. Verified method names: `isHurt()` (1480), `getDead()` (1446), `getAir()` (1102), `isJumpJustPressed()` (2069), `setCentreXPreserveSubpixel()` / `setCentreYPreserveSubpixel()` inherited from `AbstractSprite.java` at lines 86 / 95. |
| `tools/bizhawk/s3k_trace_recorder.lua` | Possibly modify | Add `flying_carrying_sonic_flag`, `carry_latch_x`, `carry_latch_y`, `release_cooldown` to `aux_state.jsonl` if future traces need mid-carry hydration. NOT required for the CNZ1 trace to pass. |
| `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java` | **New** | Unit tests for state transitions and per-frame parentage. |
| `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzCarryTrigger.java` | **New** | Unit tests for trigger predicate and initial placement. |
| `src/test/java/com/openggf/tests/TestS3kCnzCarryHeadless.java` | **New** | Headless integration test: CNZ1 Sonic+Tails bootstrap, frame-1 `x_speed` parity. |

No disassembly files are copied into the engine; everything references `docs/skdisasm/sonic3k.asm` for traceability only.

## 8. Testing strategy

### 8.1 Unit tests (`TestSidekickCpuControllerCarry`)

Using stubbed `AbstractPlayableSprite` pair (leader + sidekick) and a stub `CarryTrigger` that returns `true` for "CNZ1 + Player_mode 0":

- Drive `updateInit()` once; assert state transitioned to `CARRY_INIT` → `CARRYING` (same frame), Tails teleported to `(0x0018, 0x0600)`, `flyingCarryingFlag=true`, Sonic `object_control == 3`, Sonic `anim` has been forced to the carry animation id, Sonic `x_speed == 0x0100`.
- Drive `update()` again; assert state is still `CARRYING` and Sonic `x_speed == 0x0100`, Sonic position parented to Tails + 0x1C offset.
- **Ground release:** set Sonic `air = false` at frame 50, drive one tick, assert state returned to `NORMAL`, `object_control == 0`, `flyingCarryingFlag = false`, `releaseCooldown == 0`.
- **A/B/C release:** set Sonic `isJumpJustPressed = true` at frame 30, drive one tick, assert state returned to `NORMAL`, Sonic `y_speed == -0x0380`, Sonic `x_speed == ±0x0200` (sign depends on face-dir), `releaseCooldown == 0x12`.
- **Latch release:** between ticks, externally write Sonic.x_speed to 0x0500 (simulating a bumper), drive one tick, assert state returned to `NORMAL`, `releaseCooldown == 0x3C`.
- **Cooldown tick:** after a release with cooldown, assert `releaseCooldown` decrements by 1 per `update()`.
- **Injection cadence:** drive 64 frames in `CARRYING`; assert `inputRight=true` on frames where `(frameCounter & 0x1F) == 0`, `inputRight=false` on other frames.
- **Non-CNZ1 zone:** stub trigger returns `false` for zone 0 (AIZ); assert `updateInit` transitions directly to `NORMAL` without touching carry state (AIZ regression guard).
- **hydrateFromRomCpuState accepting 0x0C:** call with `cpuRoutine=0x0C`, assert state is `CARRY_INIT` and no exception.
- **hydrateFromRomCpuState accepting 0x20:** call with `cpuRoutine=0x20`, assert state is `CARRYING`.

### 8.2 Trigger tests (`TestSonic3kCnzCarryTrigger`)

- CNZ1 + Sonic+Tails → `shouldEnterCarry` returns `true`.
- CNZ1 + Sonic alone → returns `false`.
- CNZ1 + Tails alone → returns `false`.
- CNZ1 + Knuckles → returns `false`.
- CNZ2 + Sonic+Tails → returns `false`.
- AIZ1 + Sonic+Tails → returns `false` (critical AIZ regression guard).
- MGZ1 + Sonic+Tails → returns `false`.
- `applyInitialPlacement` writes exactly `(0x18, 0x600)` to Tails and does not alter Sonic's position.
- Returns the exact ROM constants: `carryDescendOffsetY() == 0x1C`, `carryInitXVel() == 0x100`, `carryInputInjectMask() == 0x1F`, `carryJumpReleaseCooldownFrames() == 0x12`, `carryLatchReleaseCooldownFrames() == 0x3C`, `carryReleaseJumpYVel() == -0x380`, `carryReleaseJumpXVel() == 0x200`.

### 8.3 Headless integration (`TestS3kCnzCarryHeadless`)

- Bootstrap via `HeadlessTestFixture.withZoneAndAct(3, 0).withMainCharacter(SONIC).withSidekickCharacter(TAILS)...`.
- Step 1 frame.
- Assert Sonic `x_speed == 0x0100`, `y_speed == 0`, `object_control == 3`, `air == 1`.
- Step 43 more frames.
- Assert Sonic still `air == 1` (still being carried, per trace row #3).
- Step until release (expected around frame 100-110 based on trace); assert state transitions to `NORMAL` within that window.
- After release, assert `object_control == 0` and Sonic physics runs normally.

### 8.4 Trace replay (`TestS3kCnzTraceReplay`)

- Run the existing test. Measured goals:
  - First-divergence frame shifts from 1 to > 400.
  - Total error count drops from 1635 to < 1000 (target: < 800).
- Hard floor: no NEW divergences introduced in frames 0-100 (the carry window) beyond what the baseline already shows.

### 8.5 Wider guard (per design spec §8.2)

Run after every commit during this workstream:

```
mvn test -Dtest="TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kCnzCarryHeadless,TestSidekickCpuControllerCarry,TestSonic3kCnzCarryTrigger"
```

All must pass. `TestS3kAizTraceReplay` is expected to stay red (pre-existing, workstream H). `TestS3kCnzTraceReplay` is expected to improve, not go green.

## 9. Risks & unknowns

### 9.1 Latch interference from ambient collisions

**The single biggest risk.** The latch mechanism (§5.5 path C) will release the carry whenever *any* engine-side write modifies Sonic's x_vel / y_vel between the driver's latch update (end of frame N-1) and the driver's latch compare (start of frame N). CNZ1's environment during the carry window includes bumpers and — critically — gravity, which Grep confirms is currently ungated in `PlayableSpriteMovement` (lines 1731 and 2276, both applying `sprite.getGravity()` unconditionally).

Mitigations (taken together):

1. **Gate the two gravity writes on `isObjectControlled()`.** §7.5 describes the required refactor: extract the gravity write expression into a private `applyGravity(sprite)` helper in `PlayableSpriteMovement`, add `if (sprite.isObjectControlled()) return;` at the top, and replace both call sites. Because the engine currently has NO separate `applyGravity()` method (verified via Grep), this refactor creates one. The earlier draft of this spec assumed such a method already existed; it does not.

2. **Place the driver's per-frame work AFTER physics resolution, not before.** `SidekickCpuController.update(frameCount)` is already invoked late in `PlayableSpriteController` (verify during implementation); the latch compare must observe the post-physics vel.

3. **Alternative considered but rejected.** "Have the driver write the post-gravity-expected y_vel into the latch" was considered (so the gravity-modified y_vel matches the latch on the next compare). Rejected because it couples the driver to a physics internal, and any future physics tweak to gravity would silently break the carry.

If, after mitigation 1 is implemented, any OTHER engine-side write to Sonic's velocity surfaces during the carry window (e.g. a ground-collision response routine writing y_vel = 0 on terrain contact), that write is exactly what ROM release path A / C is designed to detect; it should trigger a release, not be suppressed. So the `isObjectControlled()` gate is intentionally scoped to gravity alone, not a blanket "ignore all velocity writes while carrying".

### 9.2 Animation id mapping

`anim=0x22` is the ROM byte. The engine's `CanonicalAnimation` registry may or may not have a "carried" entry mapped to this id for Sonic. If not, `setForcedAnimationId(carriedAnimationId)` is a no-op and visual state is wrong (but physics parity is unaffected, so the trace test still passes). Adding the canonical mapping is a small follow-up; not blocking for workstream C.

### 9.3 Post-release ground-vel handoff

The trace shows `g_speed=0x0148` at frame 106 (row #4 of the baseline). This is Sonic running on the ground post-release. The ROM relies on Sonic's own `SonicKnux_DoLevelCollision` and mode-switcher to infer ground mode + ground speed from the final x_vel / y_vel + terrain contact. The engine's collision pipeline must do the same. If post-release ground-speed handoff diverges from the trace, that is a `PlayableSpriteMovement` / `CollisionSystem` issue, not a sidekick-driver issue — handle as a straggler in workstream G.

### 9.4 Interaction with parallel AIZ recovery (workstream H)

Both H and C extend `SidekickCpuController`. Merge conflicts are expected on `mapRomCpuRoutine` (different ROM routine additions) and possibly `State` enum (if H adds new states). Mitigation: land C first (smaller surface, trace-gated), then H rebases.

### 9.5 Cascaded divergences past the carry window

Even with the carry-in fixed perfectly, post-release physics may still diverge at frame ~250 if any bumper / spring collision has off-by-one timing. This is EXPECTED - it is what re-capture of the baseline (Task 8h) is for. Success is "first divergence frame > 400", not "0 errors".

### 9.6 CNZ level-select entry bypasses `Level_Init` music / palette

The CNZ1 carry-in on real hardware happens inside the normal level-init flow. The level-select entry (used by the trace) may skip some of that flow. The engine's level-select → `Sonic3k.loadLevel` must still arrive at "Tails_CPU_routine = 0" before frame 0; if the level-select path sets Tails into NORMAL directly, detour that path for CNZ1 + Player_mode 0. Audit `Sonic3kDataSelectManager` → `Sonic3k.loadLevel` during implementation.

## 10. Success criteria

- `TestS3kCnzCarryHeadless` passes with `Sonic.x_speed == 0x0100` at frame 1, `object_control == 3` during carry window, `object_control == 0` after release.
- `TestSidekickCpuControllerCarry` and `TestSonic3kCnzCarryTrigger` each have ≥ 9 meaningful assertions covering all three release paths and the cooldown semantics.
- `TestS3kCnzTraceReplay` first-divergence frame shifts from 1 to > 400; error count < 1000.
- Wider guard (per §8.5) is all-green, with the pre-existing `TestS3kAizTraceReplay` red state unchanged.
- All new ROM-derived constants in `Sonic3kConstants.java` have `// ROM: <label> - RomOffsetFinder --game s3k search <label>` comments; all literal constants (e.g. 0x0100, 0x1C) reference their `sonic3k.asm` source line in a comment.
- Commit messages for every commit in this workstream carry the 7 required policy trailers, with `Co-Authored-By` INSIDE the trailer block (see `CLAUDE.md` §Branch Documentation Policy).

## 11. Appendix - disassembly pointers

Exact file references for reviewers and implementer subagents:

| Disassembly site | Topic | Line range (sonic3k.asm) |
|------------------|-------|---------------------------|
| `loc_13A10` | TailsCPU_Init dispatch entry | ~26380-26400 |
| `loc_13A32` | CNZ1 zone check + teleport + routine 0→0xC | ~26401-26420 |
| `loc_13FC2` | Routine 0xC: carry init (fall through to 0x20) | ~26903-26913 |
| `loc_13FFA` | Routine 0x20: per-frame carry body with right-injection | ~26915-26925 |
| `loc_14016` | Ground-release branch (release path A) | ~26926-26946 |
| `loc_14082` | Post-carry Player_mode divert (out of scope) | ~26944-26960 |
| `sub_1459E` | Sonic pickup | ~27382-27413 |
| `Tails_Carry_Sonic` | Per-frame parentage | ~27222-27332 |
| `loc_14466` | Latch-mismatch release target (release path C) | ~27470-27490 |
| `AniRaw_Tails_Carry` | Carry animation cycle table | ~27334-27335 |

Implementer must cite the exact disassembly lines in each commit message alongside the RomOffsetFinder output.
