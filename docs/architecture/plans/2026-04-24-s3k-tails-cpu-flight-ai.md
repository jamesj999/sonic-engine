# S3K Tails CPU Flight/Hover AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `Tails_Catch_Up_Flying` (ROM CPU routine 0x02, `sonic3k.asm:26474`) and `Tails_FlySwim_Unknown` (ROM CPU routine 0x04, `sonic3k.asm:26534`) so CPU Tails can fly back to Sonic after separation, reducing `TestS3kCnzTraceReplay` error count significantly past its current frame-318 first-divergence.

**Architecture:** Extend `SidekickCpuController`'s state machine with two new states (`CATCH_UP_FLIGHT`, `FLIGHT_AUTO_RECOVERY`) matching the ROM's `Tails_CPU_Control_Index` layout (routine 0x02 and 0x04). `Tails_Catch_Up_Flying` runs the 64-frame gate + manual-trigger logic that teleports Tails to Sonic's position (center.x, center.y − 0xC0). `Tails_FlySwim_Unknown` runs the per-frame "fly toward Sonic's 16-frame delayed position" with a 5-second (`5*60` frame) auto-land timer that reverts to `CATCH_UP_FLIGHT` if Tails lingers off-screen. Transitions from `NORMAL` (routine 0x06 / `loc_13D4A`) happen via the existing `Tails_CPU_routine` byte-write mechanism — no change to `NORMAL` body except expanding `mapRomCpuRoutine` and the trigger checks.

**Tech Stack:** Java 21, JUnit 5, existing S3K trace-replay framework (`AbstractTraceReplayTest`), `HeadlessTestFixture`, `SidekickCpuController` state machine.

**Design references:**
- `docs/skdisasm/sonic3k.asm:26368-26386` — `Tails_CPU_Control_Index` (18-entry routine dispatch table, routines 0x00-0x22)
- `docs/skdisasm/sonic3k.asm:26474-26531` — `Tails_Catch_Up_Flying` (routine 0x02)
- `docs/skdisasm/sonic3k.asm:26534-26653` — `Tails_FlySwim_Unknown` (routine 0x04, includes target-X steer and the early-close transition back to routine 0x06)
- `docs/skdisasm/sonic3k.asm:26656-26795` — `loc_13D4A` (routine 0x06 / NORMAL body, already ported)
- `docs/skdisasm/sonic3k.asm:27592-27644` — `Tails_Move_FlySwim` (flight gravity +0x08 + boost decay, already partially ported for carry)

**Previous work this builds on:**
- Commit `1e5c8d448` (fix(s3k): sidekick follow snap threshold + post-carry flight persistence) established:
  - `PhysicsFeatureSet.sidekickFollowSnapThreshold` gates CPU-AI input override per game
  - `double_jump_flag` now drives Tails's flight gravity (+0x08) via `sprite.getSecondaryAbility() == FLY && sprite.getDoubleJumpFlag() != 0`
  - `updateCarrying()` ground-release branch matches ROM `loc_14016` (resets vel, keeps `double_jump_flag`, keeps air bit)

**Commit trailer policy:** every commit in this workstream MUST carry the 7 required policy trailers per `CLAUDE.md` §Branch Documentation Policy, with `Co-Authored-By` INSIDE the policy block (not separated by blank line — `git interpret-trailers --parse` treats blank-separated blocks as distinct and the validator only sees the last one). Intermediate task commits use `Changelog: n/a` / `S3K-Known-Discrepancies: n/a` (policy validator requires consistency: if the file IS staged the trailer must say `updated`, if NOT staged it must say `n/a`). Task 8 (final rollup) stages `CHANGELOG.md` and `docs/S3K_KNOWN_DISCREPANCIES.md` together with matching `updated` trailers.

---

## File Structure

### New files

| File | Responsibility |
|------|----------------|
| `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCatchUpFlight.java` | Unit tests for the `CATCH_UP_FLIGHT` state transition and teleport |
| `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java` | Unit tests for the `FLIGHT_AUTO_RECOVERY` state (steering, timer, close-enough transition) |

### Modified files

| File | Change |
|------|--------|
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Add `CATCH_UP_FLIGHT` and `FLIGHT_AUTO_RECOVERY` to `State` enum (positions 7 & 8). Extend `update()` dispatch. Add `updateCatchUpFlight()`, `updateFlightAutoRecovery()` bodies. Extend `mapRomCpuRoutine` so 0x02 → `CATCH_UP_FLIGHT` and 0x04 → `FLIGHT_AUTO_RECOVERY`. Fix the current mapping of 0x02 → SPAWNING and 0x04 → APPROACHING which was incorrect per the ROM dispatch table. Add `flightTimer` and `catchUpTargetX/Y` fields. Update `reset()` to clear them. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | Add `TAILS_CATCH_UP_Y_OFFSET = 0xC0`, `TAILS_FLIGHT_AUTO_LAND_FRAMES = 5 * 60`, `TAILS_FLIGHT_STEER_SHIFT = 4` (for the `lsr.w #4, d2` distance normalisation), `TAILS_FLIGHT_MAX_X_STEP = 0xC` (the `moveq #$C, d2` clamp in `loc_13C7E`), `TAILS_FLIGHT_Y_STEP = 1` (the `moveq #1, d2` Y step). |
| `CHANGELOG.md` | Task 8 rollup entry |
| `docs/S3K_KNOWN_DISCREPANCIES.md` | Task 8 removes/updates the "Remaining Gap" paragraph in the `Tails Flying-With-Cargo Physics` section |

### Unchanged

- `PlayableSpriteMovement.applyGravity` / `doObjectMoveAndFall` — the `FLY && doubleJumpFlag != 0` gate from commit `1e5c8d448` already covers both new flight states (they set/preserve `double_jump_flag=1` at entry).
- `PhysicsFeatureSet` / `CrossGameFeatureProvider` — no new feature flag needed; flight AI is S3K-specific and gated through `sidekick.setCarryTrigger()` + (new in Task 3) `sidekick.getSecondaryAbility() == FLY`.
- `AbstractPlayableSprite` — `setCentreXPreserveSubpixel`, `setCentreYPreserveSubpixel`, `setDoubleJumpFlag` already exist.

### Scope explicitly excluded (deferred)

- AIZ1 frame-2150 one-frame-early landing for rolling-airborne Tails. This is a terrain/sensor-collision divergence rather than a CPU-AI gap; file a separate plan if it persists after this workstream lands.
- Tails_Move_FlySwim boost mechanics (A/B/C press to gain altitude while flying). The CPU AI never presses A/B/C during normal follow flight (`loc_13DF2` only writes LEFT/RIGHT via the `ori.w #$404/$808` masks), so this doesn't affect the trace-replay result.
- `loc_141F2` / `loc_1421C` super-form carry variants (routines 0x1A / 0x1C). No Super Sonic during CNZ1 intro; out of scope for trace parity.
- Competition-mode flight (`Tails_Set_Flying_Animation loc_148F4` branch at `sonic3k.asm:27683`). Engine has no competition mode.

---

### Task 1: Fix ROM-routine-to-state mapping

**Why first:** The existing `mapRomCpuRoutine` table incorrectly maps 0x02 → `SPAWNING` and 0x04 → `APPROACHING`. Per ROM `Tails_CPU_Control_Index` (sonic3k.asm:26368), 0x02 is `Tails_Catch_Up_Flying` and 0x04 is `Tails_FlySwim_Unknown`. This must be corrected before Task 3 can add the new states without collision. The fix here is purely mechanical — no behavioural change yet; the mapping is only read by `hydrateFromRomCpuState` (trace-replay bootstrap), which doesn't run a new trace that exercises these values until Task 7. This task also adds a javadoc comment documenting the ROM table.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java:779-792`

- [ ] **Step 1: Read current mapping**

```bash
grep -n "mapRomCpuRoutine" src/main/java/com/openggf/sprites/playable/SidekickCpuController.java
```

Expected: two matches (the method signature and its call site in `hydrateFromRomCpuState`).

- [ ] **Step 2: Replace `mapRomCpuRoutine` with a ROM-index-documented version**

Open `SidekickCpuController.java` around line 779 and replace:

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
                    "Unsupported ROM Tails CPU routine: 0x"
                            + Integer.toHexString(cpuRoutine));
        };
    }
```

with:

```java
    /**
     * ROM {@code Tails_CPU_Control_Index} (sonic3k.asm:26368-26386) is an 18-entry
     * word table indexed by {@code Tails_CPU_routine}, which the dispatcher reads at
     * sonic3k.asm:26362-26364. Each entry value is the CPU routine byte (0x00, 0x02,
     * 0x04, ...) — the table stride is 2 bytes, so the value equals the offset.
     *
     * <pre>
     *   0x00  loc_13A10               engine State.INIT  (zone-specific init, carry gate)
     *   0x02  Tails_Catch_Up_Flying   engine State.CATCH_UP_FLIGHT  (teleport-to-Sonic gate, sonic3k.asm:26474)
     *   0x04  Tails_FlySwim_Unknown   engine State.FLIGHT_AUTO_RECOVERY (fly-toward-Sonic + 5s timer, sonic3k.asm:26534)
     *   0x06  loc_13D4A               engine State.NORMAL (ground follow AI, sonic3k.asm:26656)
     *   0x08  loc_13F40               engine State.PANIC  (idle/standing ground, sonic3k.asm:26851)
     *   0x0A  locret_13FC0            (empty; used by Knuckles-only paths)
     *   0x0C  loc_13FC2               engine State.CARRY_INIT (carry body init)
     *   0x0E  loc_13FFA               engine State.CARRYING  (carry body per-frame)
     *   0x10-0x22  super/Knuckles/2P variants — not modelled
     * </pre>
     *
     * <p>Note: earlier versions of this file mapped 0x02 and 0x04 to SPAWNING and
     * APPROACHING respectively. Those engine states are behavioural inventions
     * (despawn-respawn flow, approach strategy) that the ROM doesn't have a
     * matching routine for; hydrating them from a recorded CPU routine byte was
     * never semantically correct. Prefer to leave hydration undefined for
     * engine-only states until there's a concrete trace that exercises them.
     */
    private static State mapRomCpuRoutine(int cpuRoutine) {
        return switch (cpuRoutine) {
            case 0x00 -> State.INIT;
            case 0x02 -> State.CATCH_UP_FLIGHT;
            case 0x04 -> State.FLIGHT_AUTO_RECOVERY;
            case 0x06 -> State.NORMAL;
            case 0x08 -> State.PANIC;
            case 0x0C -> State.CARRY_INIT;
            case 0x0E, 0x20 -> State.CARRYING;
            default -> throw new IllegalArgumentException(
                    "Unsupported ROM Tails CPU routine: 0x"
                            + Integer.toHexString(cpuRoutine));
        };
    }
```

(`State.CATCH_UP_FLIGHT` and `State.FLIGHT_AUTO_RECOVERY` don't exist yet; the file will not compile until Task 2. Intentional — Task 2 adds them.)

- [ ] **Step 3: Verify compile is blocked with the expected error**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && mvn -Dmse=relaxed -q compile
```

Expected: compilation error referencing `CATCH_UP_FLIGHT` and `FLIGHT_AUTO_RECOVERY` not found in enum `State`. This confirms the fix is wired but needs Task 2.

- [ ] **Step 4: Commit** (amend into Task 2 so the tree is always green — this step intentionally has no standalone commit)

---

### Task 2: Add `CATCH_UP_FLIGHT` and `FLIGHT_AUTO_RECOVERY` to the State enum

**Why next:** Resolves the compile break from Task 1 so subsequent tasks can add behaviour incrementally.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java:41-49`

- [ ] **Step 1: Extend the enum**

Replace:

```java
    public enum State {
        INIT,
        SPAWNING,
        APPROACHING,
        NORMAL,
        PANIC,
        CARRY_INIT,   // ROM routine 0x0C - first tick after trigger (teleport + pickup)
        CARRYING      // ROM routine 0x0E / 0x20 - per-frame carry body
    }
```

with:

```java
    public enum State {
        INIT,
        SPAWNING,
        APPROACHING,
        NORMAL,
        PANIC,
        CARRY_INIT,            // ROM routine 0x0C - first tick after trigger (teleport + pickup)
        CARRYING,              // ROM routine 0x0E / 0x20 - per-frame carry body
        CATCH_UP_FLIGHT,       // ROM routine 0x02 (Tails_Catch_Up_Flying, sonic3k.asm:26474)
        FLIGHT_AUTO_RECOVERY   // ROM routine 0x04 (Tails_FlySwim_Unknown, sonic3k.asm:26534)
    }
```

- [ ] **Step 2: Extend the dispatch switch** (around line 119-127)

Replace:

```java
        switch (state) {
            case INIT        -> updateInit();
            case SPAWNING    -> updateSpawning();
            case APPROACHING -> updateApproaching();
            case NORMAL      -> updateNormal();
            case PANIC       -> updatePanic();
            case CARRY_INIT  -> updateCarryInit();
            case CARRYING    -> updateCarrying();
        }
```

with:

```java
        switch (state) {
            case INIT                 -> updateInit();
            case SPAWNING             -> updateSpawning();
            case APPROACHING          -> updateApproaching();
            case NORMAL               -> updateNormal();
            case PANIC                -> updatePanic();
            case CARRY_INIT           -> updateCarryInit();
            case CARRYING             -> updateCarrying();
            case CATCH_UP_FLIGHT      -> updateCatchUpFlight();
            case FLIGHT_AUTO_RECOVERY -> updateFlightAutoRecovery();
        }
```

- [ ] **Step 3: Stub the new handlers**

Add right after `updateCarrying()`'s closing brace (near line 473):

```java
    /**
     * ROM {@code Tails_Catch_Up_Flying} (sonic3k.asm:26474). Entered when
     * {@code Tails_CPU_routine == 2}. Waits on either (a) the sidekick's Ctrl_2
     * A/B/C/START press, or (b) a 64-frame gate firing while Sonic is not
     * object-controlled and not super. On trigger, teleports Tails to
     * (Sonic.x, Sonic.y - 0xC0), sets routine = 4, and enters flight AI.
     *
     * <p>Stubbed in Task 2; body lands in Task 4.
     */
    private void updateCatchUpFlight() {
        // TODO(Task 4): port sonic3k.asm:26474-26531.
    }

    /**
     * ROM {@code Tails_FlySwim_Unknown} (sonic3k.asm:26534). Entered when
     * {@code Tails_CPU_routine == 4}. Per-frame: increments Tails_CPU_flight_timer;
     * after 5*60 frames off-screen, falls back to {@code CATCH_UP_FLIGHT}.
     * Otherwise computes the 16-frame delayed Sonic position, steers Tails toward
     * it (X step &le; 0xC, Y step = 1 plus optional -0x20 lead), and transitions
     * to {@code NORMAL} (routine 0x06) once Tails is close enough to Sonic and
     * Sonic isn't hurt/dead.
     *
     * <p>Stubbed in Task 2; body lands in Task 5.
     */
    private void updateFlightAutoRecovery() {
        // TODO(Task 5): port sonic3k.asm:26534-26653.
    }
```

- [ ] **Step 4: Run the existing SidekickCpuController tests to confirm nothing regresses**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed \
  -Dtest="TestSidekickCpuControllerCarry,TestSidekickCpuFollowParity,TestSidekickCpuDespawnParity,TestS3kCnzCarryHeadless"
```

Expected: all tests pass (new states stubbed, no new behaviour yet).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java
git commit -m "$(cat <<'TRAILER'
feat(sidekick): stub CATCH_UP_FLIGHT and FLIGHT_AUTO_RECOVERY states

Adds the two remaining Tails CPU routines from Tails_CPU_Control_Index
(sonic3k.asm:26368) to the state enum and dispatch switch. Bodies are
stubbed — Task 4 ports Tails_Catch_Up_Flying, Task 5 ports
Tails_FlySwim_Unknown.

Also fixes mapRomCpuRoutine so 0x02 and 0x04 map to the ROM-matching
states instead of SPAWNING and APPROACHING (those engine states have
no ROM routine to hydrate from).

No behavioural change: neither new state is reached from any existing
transition yet. Follow-up tasks wire the transitions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

Expected: commit succeeds; `git log -1` shows the message.

---

### Task 3: Add carry fields and constants

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` (fields section around line 80-85; `reset()` around line 859)
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

- [ ] **Step 1: Add constants**

In `Sonic3kConstants.java`, add a new section near the existing Tails-carry constants:

```java
    // =====================================================================
    // S3K Tails CPU flight/catch-up constants
    // sonic3k.asm:26474+ (Tails_Catch_Up_Flying) and 26534+ (Tails_FlySwim_Unknown)
    // =====================================================================

    /** Y offset applied when Tails teleports above Sonic on catch-up entry.
     *  ROM sonic3k.asm:26494 (`subi.w #$C0, d0`). */
    public static final int TAILS_CATCH_UP_Y_OFFSET = 0xC0;

    /** Auto-land timeout for Tails_FlySwim_Unknown; after 5 seconds off-screen
     *  Tails falls back to CATCH_UP_FLIGHT so the teleport re-runs.
     *  ROM sonic3k.asm:26538 (`cmpi.w #5*60, (Tails_CPU_flight_timer).w`). */
    public static final int TAILS_FLIGHT_AUTO_LAND_FRAMES = 5 * 60;

    /** Horizontal steer step clamp for Tails_FlySwim_Unknown: the normalized
     *  |dx| >> 4 is capped at 0xC, producing a max of 12 px/frame X movement.
     *  ROM sonic3k.asm:26576 (`cmpi.w #$C, d2`). */
    public static final int TAILS_FLIGHT_MAX_X_STEP = 0xC;

    /** Vertical steer step for Tails_FlySwim_Unknown: always +/-1 px per frame
     *  toward the target Y.  ROM sonic3k.asm:26612 (`moveq #1, d2`). */
    public static final int TAILS_FLIGHT_Y_STEP = 1;

    /** The "ahead of Sonic" leading offset applied to Sonic's delayed X when
     *  he is not on an object and his ground speed is < 0x400.
     *  ROM sonic3k.asm:26694 (`subi.w #$20, d2`). */
    public static final int TAILS_FLIGHT_LEAD_X_OFFSET = 0x20;

    /** The ground-speed threshold Sonic must exceed for the lead offset to be
     *  suppressed.  ROM sonic3k.asm:26692 (`cmpi.w #$400, ground_vel(a1)`). */
    public static final int TAILS_FLIGHT_LEAD_SUPPRESS_GSPEED = 0x400;
```

- [ ] **Step 2: Add fields to `SidekickCpuController`**

In `SidekickCpuController.java`, after the carry fields (around line 85):

```java
    // =====================================================================
    // Tails flight/catch-up state (ROM Tails_CPU_flight_timer + steering state)
    // =====================================================================
    private int flightTimer;
    private int catchUpTargetX;
    private int catchUpTargetY;
```

- [ ] **Step 3: Extend `reset()` to clear them**

Find the existing `reset()` method (around line 859) and add before the final closing brace:

```java
        flightTimer = 0;
        catchUpTargetX = 0;
        catchUpTargetY = 0;
```

- [ ] **Step 4: Compile and run the existing sidekick tests**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed \
  -Dtest="TestSidekickCpuControllerCarry,TestSidekickCpuFollowParity,TestSidekickCpuDespawnParity,TestS3kCnzCarryHeadless"
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "$(cat <<'TRAILER'
feat(sidekick): add flight-AI fields and Sonic3kConstants literals

Adds flightTimer and catchUpTargetX/Y fields to SidekickCpuController
(reset() clears them to 0) alongside the five ROM constants referenced
by Tails_Catch_Up_Flying/Tails_FlySwim_Unknown: TAILS_CATCH_UP_Y_OFFSET
(0xC0), TAILS_FLIGHT_AUTO_LAND_FRAMES (5*60), TAILS_FLIGHT_MAX_X_STEP
(0xC), TAILS_FLIGHT_Y_STEP (1), TAILS_FLIGHT_LEAD_X_OFFSET (0x20), and
TAILS_FLIGHT_LEAD_SUPPRESS_GSPEED (0x400).

No behavioural change: the fields are written but not yet read.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

---

### Task 4: Implement `updateCatchUpFlight()` (ROM routine 0x02)

**Files:**
- Create: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCatchUpFlight.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` — body of `updateCatchUpFlight()`

Reference: `sonic3k.asm:26474-26531`:

```asm
Tails_Catch_Up_Flying:
    move.b  (Ctrl_2_logical).w, d0
    andi.b  #button_A_mask|button_B_mask|button_C_mask|button_start_mask, d0
    bne.s   loc_13B50             ; if A/B/C/START pressed, trigger
    move.w  (Level_frame_counter).w, d0
    andi.w  #$3F, d0              ; every 64 frames
    bne.w   locret_13BF6          ; otherwise return
    tst.b   object_control(a1)
    bmi.w   locret_13BF6          ; skip if Sonic is object-controlled
    move.b  status(a1), d0
    andi.b  #$80, d0              ; check Sonic's super bit
    bne.w   locret_13BF6          ; skip if Sonic is super

loc_13B50:                        ; Trigger path
    move.w  #4, (Tails_CPU_routine).w   ; advance to routine 0x04
    move.w  x_pos(a1), d0               ; Sonic's x
    move.w  d0, x_pos(a0)               ; Tails x = Sonic x
    move.w  d0, (Tails_CPU_target_X).w
    move.w  y_pos(a1), d0               ; Sonic's y
    move.w  d0, (Tails_CPU_target_Y).w
    subi.w  #$C0, d0                    ; Tails y = Sonic y - 0xC0
    tst.b   (Reverse_gravity_flag).w
    beq.s   loc_13B78
    addi.w  #2*$C0, d0                  ; Reverse-gravity variant (not applicable)

loc_13B78:
    move.w  d0, y_pos(a0)
    ori.w   #high_priority, art_tile(a0)
    move.w  #$100, priority(a0)
    moveq   #0, d0
    move.w  d0, x_vel(a0)
    move.w  d0, y_vel(a0)
    move.w  d0, ground_vel(a0)
    move.b  d0, flip_type(a0)
    move.b  d0, double_jump_flag(a0)    ; Clear flag first, then...
    move.b  #2, status(a0)              ; Set air=1, direction=right, clear else
    move.b  #30, air_left(a0)           ; Underwater air reset
    move.b  #$81, object_control(a0)    ; Tails is now self-object-controlled for flight
    move.b  d0, flips_remaining(a0)
    move.b  d0, flip_speed(a0)
    move.w  d0, move_lock(a0)
    move.b  d0, invulnerability_timer(a0)
    move.b  d0, invincibility_timer(a0)
    move.b  d0, speed_shoes_timer(a0)
    move.b  d0, status_tertiary(a0)
    move.b  d0, scroll_delay_counter(a0)
    move.w  d0, next_tilt(a0)
    move.b  d0, stick_to_convex(a0)
    move.b  d0, spin_dash_flag(a0)
    move.b  d0, spin_dash_flag(a0)      ; (ROM duplicates this write)
    move.w  d0, spin_dash_counter(a0)
    move.b  d0, jumping(a0)
    move.b  d0, $41(a0)
    move.b  #(8*60)/2, double_jump_property(a0)  ; 240-frame flight fuel budget
    bsr.w   Tails_Set_Flying_Animation
```

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCatchUpFlight.java`:

```java
package com.openggf.sprites.playable;

import com.openggf.game.EngineServices;
import com.openggf.game.RuntimeManager;
import com.openggf.physics.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SidekickCpuController.CATCH_UP_FLIGHT (ROM routine 0x02,
 * Tails_Catch_Up_Flying at sonic3k.asm:26474).
 */
class TestSidekickCpuControllerCatchUpFlight {

    @BeforeEach
    void setUp() {
        RuntimeManager.configureEngineServices(EngineServices.fromLegacySingletonsForBootstrap());
        RuntimeManager.createGameplay();
    }

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
    }

    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
    }

    @Test
    void catchUpTeleportsTailsToSonicMinus0xC0Y_onCtrl2ABCPress() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0400);
        tails.setCentreX((short) 0x0200);  // Far away, below screen, etc.
        tails.setCentreY((short) 0x0500);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);
        controller.setController2Input(0, AbstractPlayableSprite.INPUT_JUMP);  // A/B/C press

        controller.update(0);

        assertEquals(0x1000, tails.getCentreX() & 0xFFFF,
                "Catch-up teleport writes Sonic.x to Tails");
        assertEquals(0x0340, tails.getCentreY() & 0xFFFF,
                "Catch-up teleport writes Sonic.y - 0xC0 to Tails");
        assertEquals((short) 0, tails.getXSpeed(), "Velocities zeroed");
        assertEquals((short) 0, tails.getYSpeed(), "Velocities zeroed");
        assertEquals((short) 0, tails.getGSpeed(), "Velocities zeroed");
        assertEquals(1, tails.getDoubleJumpFlag(),
                "status.air bit set + double_jump_flag left at ROM-default of 1 for flight");
        assertTrue(tails.getAir(), "status air bit set");
        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Transitions to routine 0x04 (FLIGHT_AUTO_RECOVERY) on trigger");
    }

    @Test
    void catchUp64FrameGateTriggersOnlyEvery64Frames() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0400);
        tails.setCentreX((short) 0x0200);
        tails.setCentreY((short) 0x0500);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);

        // Frame counters not divisible by 64 — should NOT trigger
        controller.update(1);
        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Frame 1 is not a 64-frame boundary; stay in CATCH_UP");

        controller.update(63);
        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Frame 63 is not a 64-frame boundary; stay in CATCH_UP");

        // Frame 64 = 0x40 = divisible by 64 — SHOULD trigger (Sonic not object-controlled, not super)
        controller.update(64);
        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Frame 64 hits the 64-frame gate; transition to FLIGHT_AUTO_RECOVERY");
    }

    @Test
    void catchUp64FrameGateSuppressedWhenSonicIsObjectControlled() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setObjectControlled(true);  // ROM checks bit 7 of object_control (bmi)

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);

        controller.update(64);

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Sonic object-controlled suppresses the 64-frame gate");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestSidekickCpuControllerCatchUpFlight 2>&1 | tail -30
```

Expected: tests fail because `updateCatchUpFlight()` is empty.

- [ ] **Step 3: Implement the body**

In `SidekickCpuController.java`, replace the stubbed `updateCatchUpFlight()`:

```java
    private void updateCatchUpFlight() {
        // ROM Tails_Catch_Up_Flying (sonic3k.asm:26474-26531)
        boolean trigger = false;

        // Ctrl_2_logical A/B/C/START press → immediate trigger
        if ((controller2Logical & (AbstractPlayableSprite.INPUT_JUMP | INPUT_START)) != 0) {
            trigger = true;
        } else {
            // 64-frame gate, suppressed if Sonic is object-controlled (bit 7) or super.
            if ((frameCounter & 0x3F) == 0
                    && !leader.isObjectControlled()
                    && !leader.isSuperSonic()) {
                trigger = true;
            }
        }

        if (!trigger) {
            return;
        }

        // sonic3k.asm:26487 (loc_13B50) — teleport and enter FLIGHT_AUTO_RECOVERY.
        int targetX = leader.getCentreX() & 0xFFFF;
        int targetY = leader.getCentreY() & 0xFFFF;
        catchUpTargetX = targetX;
        catchUpTargetY = targetY;
        sidekick.setCentreXPreserveSubpixel((short) targetX);
        sidekick.setCentreYPreserveSubpixel(
                (short) (targetY - com.openggf.game.sonic3k.constants.Sonic3kConstants.TAILS_CATCH_UP_Y_OFFSET));
        sidekick.setXSpeed((short) 0);
        sidekick.setYSpeed((short) 0);
        sidekick.setGSpeed((short) 0);
        sidekick.setAir(true);
        sidekick.setRolling(false);
        sidekick.setRollingJump(false);
        sidekick.setJumping(false);
        sidekick.setPushing(false);
        sidekick.setOnObject(false);
        sidekick.setMoveLockTimer(0);
        sidekick.setForcedAnimationId(flyAnimId);
        // ROM writes double_jump_flag=0 then status=2 (clears all); engine separates
        // those: we want doubleJumpFlag=1 so the FLY-gravity gate in
        // PlayableSpriteMovement.applyGravity stays enabled immediately. The ROM
        // gets the same effect because Tails_Stand_Freespace reads status.air AND
        // status.underwater for its Tails_FlyingSwimming branch — but the engine's
        // gate is explicit, so we pre-set the flag.
        sidekick.setDoubleJumpFlag(1);

        flightTimer = 0;
        state = State.FLIGHT_AUTO_RECOVERY;
    }
```

- [ ] **Step 4: Run the tests to confirm pass**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestSidekickCpuControllerCatchUpFlight 2>&1 | tail -20
```

Expected: 3 tests pass.

- [ ] **Step 5: Regression check on previously-passing sidekick tests**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed \
  -Dtest="TestSidekickCpuControllerCarry,TestSidekickCpuFollowParity,TestSidekickCpuDespawnParity,TestS3kCnzCarryHeadless"
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCatchUpFlight.java
git commit -m "$(cat <<'TRAILER'
feat(sidekick): port Tails_Catch_Up_Flying (ROM CPU routine 0x02)

Implements the CATCH_UP_FLIGHT body matching sonic3k.asm:26474-26531.
Triggers on either Ctrl_2_logical A/B/C/START press or the 64-frame
level-counter gate (when Sonic is neither object-controlled nor super).
On trigger, teleports Tails to (Sonic.x, Sonic.y - 0xC0), zeros all
three velocities, sets the air bit, sets double_jump_flag=1 (so the
FLY-gravity gate in PlayableSpriteMovement.applyGravity stays enabled
on the next tick), and transitions to FLIGHT_AUTO_RECOVERY.

No transition INTO CATCH_UP_FLIGHT is wired yet; Task 6 adds that from
NORMAL.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

---

### Task 5: Implement `updateFlightAutoRecovery()` (ROM routine 0x04)

**Files:**
- Create: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` — body of `updateFlightAutoRecovery()`

Reference: `sonic3k.asm:26534-26653`. Summary of the per-frame logic:

1. If Tails is off-screen (`render_flags` bit 7 clear): bump `Tails_CPU_flight_timer`; when it reaches `5*60`, reset the timer, set `Tails_CPU_routine = 2` (back to CATCH_UP_FLIGHT), teleport Tails to `($0, $0)`, clear velocities, re-arm double-jump-property budget. Otherwise fall through.
2. If on-screen: clear the flight timer.
3. Compute `target = (Sonic.historical.x, Sonic.historical.y)` using the 16-frame lookback (`Sonic_Pos_Record_Index - $44`) — the same lookback table that `loc_13D4A` (NORMAL) reads. If Sonic isn't on an object AND his `ground_vel < $400`, subtract `$20` from target X (leading offset).
4. `dx = Tails.x - target.x`. If `dx == 0`, go to the Y-check. Otherwise steer X with a step of `min(|dx| >> 4, $C) + |Sonic.x_vel|` (ROM adds Sonic's speed magnitude as a "speed-match" term), clamped to `|dx|` so we don't overshoot.
5. `dy = Tails.y - target.y`. Step +/-1 toward target.
6. If Tails is close enough in both X and Y (both stepped to zero), AND Sonic's routine < 6 (alive), AND no-object-control condition, transition to NORMAL (routine 0x06). Set Tails_CPU_routine = 6, clear object_control, clear anim, clear velocities, set air bit, clear move_lock, ensure the high-priority bit copies Sonic's art_tile priority, copy Sonic's solid bits. If 2P mode, use routine 0x10 instead of 0x06 — not modelled (comp mode not in scope).
7. Otherwise, `move.b #$81, object_control(a0)` keeps Tails locked into flight-AI control.

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java`:

```java
package com.openggf.sprites.playable;

import com.openggf.game.EngineServices;
import com.openggf.game.RuntimeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SidekickCpuController.FLIGHT_AUTO_RECOVERY (ROM routine 0x04,
 * Tails_FlySwim_Unknown at sonic3k.asm:26534).
 */
class TestSidekickCpuControllerFlightAutoRecovery {

    @BeforeEach
    void setUp() {
        RuntimeManager.configureEngineServices(EngineServices.fromLegacySingletonsForBootstrap());
        RuntimeManager.createGameplay();
    }

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
    }

    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
    }

    private TestableSprite sonicAt(int x, int y) {
        TestableSprite sonic = new TestableSprite("sonic");
        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) x);
        Arrays.fill(yHistory, (short) y);
        sonic.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);
        sonic.setCentreX((short) x);
        sonic.setCentreY((short) y);
        return sonic;
    }

    @Test
    void flightSteersXByDistanceOver16ClampedTo0xC() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1F00);   // 0xF00 away from Sonic (to the right)
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setDoubleJumpFlag(1);
        // Sonic.x_vel defaults to 0 so the test doesn't pull in the
        // "speed match" term; step = clamp(|dx|>>4, 0xC) + |0| + 1 = 0xD.

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        // |dx| = 0xF00, |dx| >> 4 = 0xF0, clamped to 0xC; +0 (Sonic idle) +1 = 0xD.
        // Tails is to the right of Sonic, so X decreases by 0xD.
        assertEquals(0x1EF3, tails.getCentreX() & 0xFFFF,
                "X steps toward Sonic by (clamp(|dx|>>4, 0xC) + |Sonic.x_vel| + 1) = 0xD");
    }

    @Test
    void flightTimerRollsBackToCatchUpAfter300FramesOffscreen() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x3000);   // Far off-screen
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);
        // setRenderFlagOnScreen(boolean) sets the renderFlagOnScreenValid
        // flag internally, so a single call covers both "state valid" and
        // "off-screen".
        tails.setRenderFlagOnScreen(false);

        for (int i = 0; i < 5 * 60; i++) {
            controller.update(i);
        }

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "After 5s off-screen, FLIGHT_AUTO_RECOVERY rolls back to CATCH_UP_FLIGHT");
    }

    @Test
    void flightTransitionsToNormalWhenCloseEnoughAndSonicAlive() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);   // Already aligned horizontally
        tails.setCentreY((short) 0x0400);   // Already aligned vertically
        tails.setAir(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState(),
                "Tails aligned with Sonic + Sonic alive = transition to NORMAL (routine 0x06)");
        assertFalse(tails.isObjectControlled(),
                "Transition clears Tails's object_control");
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestSidekickCpuControllerFlightAutoRecovery 2>&1 | tail -20
```

Expected: all three tests fail.

- [ ] **Step 3: Implement the body**

In `SidekickCpuController.java`, replace the stubbed `updateFlightAutoRecovery()`:

```java
    private void updateFlightAutoRecovery() {
        // ROM Tails_FlySwim_Unknown (sonic3k.asm:26534-26653).
        final int AUTO_LAND_FRAMES = com.openggf.game.sonic3k.constants
                .Sonic3kConstants.TAILS_FLIGHT_AUTO_LAND_FRAMES;
        final int MAX_X_STEP = com.openggf.game.sonic3k.constants
                .Sonic3kConstants.TAILS_FLIGHT_MAX_X_STEP;
        final int Y_STEP = com.openggf.game.sonic3k.constants
                .Sonic3kConstants.TAILS_FLIGHT_Y_STEP;
        final int LEAD_SUPPRESS = com.openggf.game.sonic3k.constants
                .Sonic3kConstants.TAILS_FLIGHT_LEAD_SUPPRESS_GSPEED;
        final int LEAD_OFFSET = com.openggf.game.sonic3k.constants
                .Sonic3kConstants.TAILS_FLIGHT_LEAD_X_OFFSET;
        final int FLIGHT_FUEL = (8 * 60) / 2;   // ROM loc_13C3A:26552 double_jump_property reload

        // 1. Off-screen timer. The ROM check is `tst.b render_flags(a0); bmi.s loc_13C3A`.
        //    Engine: hasRenderFlagOnScreenState() + isRenderFlagOnScreen() mirrors the bit.
        boolean onScreen = sidekick.hasRenderFlagOnScreenState()
                ? sidekick.isRenderFlagOnScreen()
                : isCurrentlyVisible();
        if (!onScreen) {
            flightTimer++;
            if (flightTimer >= AUTO_LAND_FRAMES) {
                // ROM sonic3k.asm:26540-26547 — reset and bounce back to CATCH_UP.
                flightTimer = 0;
                sidekick.setCentreX((short) 0);
                sidekick.setCentreY((short) 0);
                sidekick.setObjectControlled(true);
                sidekick.setAir(true);
                sidekick.setDoubleJumpFlag(1);
                sidekick.setDoubleJumpProperty((byte) FLIGHT_FUEL);
                sidekick.setForcedAnimationId(flyAnimId);
                state = State.CATCH_UP_FLIGHT;
                return;
            }
        } else {
            // ROM loc_13C3A (sonic3k.asm:26551-26555): every on-screen frame
            // resets the flight timer AND refuels double_jump_property to
            // (8*60)/2 = 240. The refuel is what keeps Tails's flapping
            // animation + flight state active indefinitely while on-screen.
            flightTimer = 0;
            sidekick.setDoubleJumpProperty((byte) FLIGHT_FUEL);
            // Tails_Set_Flying_Animation is normally called here; the engine's
            // animation is driven by the forced-anim slot already set at entry.
        }

        // 3. Target = Sonic's 16-frame-delayed position.
        int targetX = leader.getCentreX(ROM_FOLLOW_DELAY_FRAMES) & 0xFFFF;
        int targetY = leader.getCentreY(ROM_FOLLOW_DELAY_FRAMES) & 0xFFFF;
        // ROM sonic3k.asm:26690-26694: if Sonic not on-object AND ground_vel < $400,
        // lead him by $20 on X.
        if (!leader.isOnObject() && Math.abs(leader.getGSpeed()) < LEAD_SUPPRESS) {
            targetX -= LEAD_OFFSET;
        }
        catchUpTargetX = targetX;
        catchUpTargetY = targetY;

        // 4. X steer: dx = Tails.x - target.x. Track residual distance AFTER
        //    the step (ROM d0 is zeroed in the overshoot-clamp branch at
        //    loc_13CA6/loc_13CAA, so the close-enough check uses the
        //    post-step value, not the pre-step value).
        int dx = (sidekick.getCentreX() & 0xFFFF) - targetX;
        int residualX = dx;
        if (dx != 0) {
            int absDx = Math.abs(dx);
            int step = absDx >> 4;
            if (step > MAX_X_STEP) {
                step = MAX_X_STEP;
            }
            // ROM sonic3k.asm:26580-26586: move.b x_vel(a1), d1 reads the HIGH
            // byte of Sonic's 16-bit x_vel (big-endian 68000). Engine x_vel is
            // stored in subpixels (256/px), so the ROM's "pixel velocity" byte
            // is (xSpeed >> 8) & 0xFF. Use the signed 8-bit absolute value.
            int sonicPixelXVel = (leader.getXSpeed() >> 8);
            int sonicXVelMag = Math.abs((byte) sonicPixelXVel);
            step += sonicXVelMag + 1;   // ROM addq.w #1, d2
            if (step >= absDx) {
                step = absDx;           // Clamp to |dx| — overshoot branch
                residualX = 0;          //   (loc_13CA6 / loc_13CAA clear d0)
            }
            int newX = (dx > 0)
                    ? (sidekick.getCentreX() & 0xFFFF) - step
                    : (sidekick.getCentreX() & 0xFFFF) + step;
            sidekick.setCentreXPreserveSubpixel((short) newX);
        }

        // 5. Y steer: +/-1 per frame. Same post-step residual tracking.
        int dy = (sidekick.getCentreY() & 0xFFFF) - targetY;
        int residualY = dy;
        if (dy != 0) {
            int newY = (dy > 0)
                    ? (sidekick.getCentreY() & 0xFFFF) - Y_STEP
                    : (sidekick.getCentreY() & 0xFFFF) + Y_STEP;
            sidekick.setCentreYPreserveSubpixel((short) newY);
            // Y step never overshoots (it's ±1 per frame), so residualY
            // approaches 0 but may not reach it for many frames. That matches
            // the ROM which only clears d1 via the beq.s loc_13CD2 at
            // sonic3k.asm:26613 when y_pos(a0) == target before the step.
            if (Math.abs(residualY) <= Y_STEP) {
                residualY = 0;
            }
        }

        // 6. Transition to NORMAL when close enough AND Sonic alive AND
        //    Sonic not in an uninterruptible state. Close-enough uses the
        //    post-step residuals (see residualX/residualY above).
        boolean closeEnough = residualX == 0 && residualY == 0;
        boolean sonicAlive = !leader.isHurt() && !leader.getDead();
        // ROM sonic3k.asm:26624-26630: also checks a stat_table flag bit 7.
        // Engine approximation: isObjectControlled.
        boolean sonicFreeOfLock = !leader.isObjectControlled();

        if (closeEnough && sonicAlive && sonicFreeOfLock) {
            // ROM sonic3k.asm:26631-26648 — return to NORMAL (routine 0x06).
            sidekick.setObjectControlled(false);
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
            sidekick.setMoveLockTimer(0);
            sidekick.setForcedAnimationId(-1);
            sidekick.setAir(true);
            state = State.NORMAL;
            normalFrameCount = 0;
            return;
        }

        // 7. Otherwise keep object_control locked to keep flight AI active.
        sidekick.setObjectControlled(true);
    }
```

- [ ] **Step 4: Run the unit tests**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestSidekickCpuControllerFlightAutoRecovery 2>&1 | tail -20
```

Expected: all three tests pass.

- [ ] **Step 5: Regression check**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed \
  -Dtest="TestSidekickCpuController*,TestS3kCnzCarryHeadless,TestSidekickCpu*"
```

Expected: all pass. `TestSidekickCpuControllerCarry`, `TestSidekickCpuFollowParity`, and the new catch-up + flight-auto-recovery tests should all be green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java
git commit -m "$(cat <<'TRAILER'
feat(sidekick): port Tails_FlySwim_Unknown (ROM CPU routine 0x04)

Implements FLIGHT_AUTO_RECOVERY matching sonic3k.asm:26534-26653.
Per-frame: increments a 5-second off-screen timer that rolls back to
CATCH_UP_FLIGHT on expiry. When on-screen, computes Sonic's 16-frame-
delayed target (with a -0x20 lead if Sonic isn't on-object and isn't
sprinting past $400), steers Tails horizontally by |dx|>>4 clamped to
0xC plus Sonic's |x_vel|, and steers vertically by +/-1. When Tails is
aligned in both axes and Sonic is alive/free, transitions to NORMAL
(routine 0x06, existing engine follow AI).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

---

### Task 6: Wire the NORMAL → CATCH_UP_FLIGHT transition for dead/off-screen Sonic

**Why:** The existing `updateNormal()` returns early if `leader.getDead()`; it should instead transition to `CATCH_UP_FLIGHT` so the flight AI re-aligns Tails with Sonic's corpse (ROM sonic3k.asm:26657-26665 in `loc_13D4A`: `cmpi.b #6, (Player_1+routine); blo.s loc_13D78; move.w #4, (Tails_CPU_routine)`). Similarly on any condition that writes `Tails_CPU_routine = 2`/`4` outside the carry path.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` — `updateNormal()` dead-leader branch

- [ ] **Step 1: Update `updateNormal()` dead-leader branch**

Find (around line 253):

```java
        if (leader.getDead()) {
            enterApproachingState();
            return;
        }
```

Replace with:

```java
        if (leader.getDead() || leader.isHurt()) {
            // ROM loc_13D4A (sonic3k.asm:26657-26665): `cmpi.b #6, (Player_1+
            // routine); bhs.s loc_13D78` — fires when Sonic's routine byte is
            // 6 (dead) or above. In the engine, Sonic's "routine >= 6" range
            // maps to either `isHurt()` (routine 0x04/0x05 during the hurt
            // bounce that precedes a potential death) or `getDead()`
            // (routine 0x06+). Covering both mirrors the ROM's bhs test.
            //
            // The APPROACHING/respawn-strategy path was the pre-port
            // approximation; now that FLIGHT_AUTO_RECOVERY exists we route
            // into it directly.
            flightTimer = 0;
            sidekick.setAir(true);
            sidekick.setDoubleJumpFlag(1);
            sidekick.setForcedAnimationId(flyAnimId);
            state = State.FLIGHT_AUTO_RECOVERY;
            return;
        }
```

- [ ] **Step 2: Add a test covering the transition**

In `TestSidekickCpuControllerFlightAutoRecovery.java`, append:

```java
    @Test
    void normalTransitionsToFlightAutoRecoveryWhenLeaderDies() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        sonic.setDead(true);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0F00);
        tails.setCentreY((short) 0x0400);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        controller.update(10);

        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Dead Sonic drives Tails into flight AI (routine 0x04)");
        assertEquals(1, tails.getDoubleJumpFlag(),
                "Flight transition sets double_jump_flag=1 so flight gravity applies");
        assertTrue(tails.getAir(), "Flight transition sets air bit");
    }
```

- [ ] **Step 3: Run tests**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestSidekickCpuControllerFlightAutoRecovery
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java
git commit -m "$(cat <<'TRAILER'
feat(sidekick): route dead-leader NORMAL into FLIGHT_AUTO_RECOVERY

ROM loc_13D4A (sonic3k.asm:26657-26665) jumps to Tails_CPU_routine=4
(FLIGHT_AUTO_RECOVERY) when Sonic's routine >= 6 (dead). The engine
previously approximated this with the APPROACHING/respawn-strategy
path; route the transition correctly now that flight AI exists.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

---

### Task 7: CNZ trace replay verification

**Why:** Validates the work against the actual ROM-recorded trace.

**Files:**
- No production changes expected. This task runs the existing trace-replay tests and captures the delta.

- [ ] **Step 1: Baseline error count before the task (for comparison)**

```bash
cd /c/Users/farre/IdeaProjects/sonic-engine && \
  mvn test -Dmse=relaxed -Dtest=TestS3kCnzTraceReplay 2>&1 | grep -E "errors|First error"
```

Record the `NN errors` count and `First error: frame NN`.

- [ ] **Step 2: Expected outcome**

After Tasks 1-6, `TestS3kCnzTraceReplay` should:
- Show a lower total error count than the pre-workstream value (5024 at the start of this plan).
- Show the first strict error frame later than 318, OR the first error should be a non-flight-AI issue (e.g., a landing sensor divergence, terrain angle mismatch, or a different Tails behaviour).

If the first error moves past frame ~500, the plan succeeded. If it doesn't move, commit Task 8's documentation rollup noting the partial progress and file a follow-up issue referencing the new first-divergence frame.

- [ ] **Step 3: Also verify AIZ didn't regress**

```bash
mvn test -Dmse=relaxed -Dtest=TestS3kAizTraceReplay 2>&1 | grep -E "errors|First error"
```

Expected: error count is the same or lower, and the first error is at the pre-workstream frame (2150) or later. AIZ shouldn't use catch-up flight much because Tails rarely separates from Sonic far enough during AIZ1 — if the error count goes up, revisit Task 4's trigger conditions.

- [ ] **Step 4: If there are regressions, pause and triage**

Run the full suite to confirm no unrelated regressions:

```bash
mvn test -Dmse=relaxed 2>&1 | tail -5
```

Compare against the `1e5c8d448` commit's baseline of 55 failures. If new files are failing, open the most surprising one first (e.g., a test whose name suggests it shouldn't care about flight AI). Most likely culprit: `TestSidekickChainHealing` (daisy-chain sidekicks) — the new transition to `FLIGHT_AUTO_RECOVERY` in `updateNormal()` may interact with chain-heal logic that expected the old `APPROACHING` path.

- [ ] **Step 5: No commit in this task** (read-only verification)

---

### Task 8: Documentation and CHANGELOG rollup

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`

- [ ] **Step 1: Extend the existing Unreleased section in `CHANGELOG.md`**

Insert a new subsection BELOW the existing "Sonic 3&K Sidekick CPU Parity (AIZ/CNZ Trace Replay Follow-Ups)" block from commit `1e5c8d448`:

```markdown
#### Tails CPU Flight AI (Catch-Up + Auto-Recovery)

- Ported `Tails_Catch_Up_Flying` (sonic3k.asm:26474, ROM CPU routine
  0x02) to `SidekickCpuController.CATCH_UP_FLIGHT`. Triggers on either
  the sidekick's Ctrl_2_logical A/B/C/START press or the 64-frame
  `Level_frame_counter` gate (suppressed if Sonic is object-controlled
  or super). On trigger, teleports Tails to (Sonic.x, Sonic.y - 0xC0),
  zeros all three velocities, sets the air and double_jump_flag bits,
  and transitions to FLIGHT_AUTO_RECOVERY.
- Ported `Tails_FlySwim_Unknown` (sonic3k.asm:26534, ROM CPU routine
  0x04) to `SidekickCpuController.FLIGHT_AUTO_RECOVERY`. Implements
  the 5-second off-screen timer that rolls back to CATCH_UP_FLIGHT on
  expiry, the 16-frame-delayed target with the -0x20 lead when Sonic
  isn't on-object and isn't sprinting past 0x400, the X steer of
  `min(|dx|>>4, 0xC) + |Sonic.x_vel| + 1` clamped to |dx|, and the Y
  steer of +/-1 per frame. Transitions back to NORMAL (routine 0x06)
  when aligned and Sonic is alive/free.
- Routed `updateNormal()`'s dead-leader branch to FLIGHT_AUTO_RECOVERY,
  replacing the APPROACHING/respawn-strategy approximation with the
  ROM-accurate behavior from `loc_13D4A` (sonic3k.asm:26657-26665).
- Corrected the `mapRomCpuRoutine` table: 0x02 → `CATCH_UP_FLIGHT` and
  0x04 → `FLIGHT_AUTO_RECOVERY` (previously misrouted to SPAWNING /
  APPROACHING).
```

- [ ] **Step 2: Update `docs/S3K_KNOWN_DISCREPANCIES.md`**

Find the section `## Tails Flying-With-Cargo Physics` (updated by commit `1e5c8d448`) and replace its `### Remaining Gap` paragraph:

**Before:**

```markdown
### Remaining Gap

Tails's **post-carry catch-up/hover AI** (`Tails_Catch_Up_Flying` at `sonic3k.asm:26474`, routine 0x02, and `Tails_FlySwim_Unknown` at `sonic3k.asm:26534`, routine 0x04) is still missing. Those routines teleport Tails back to Sonic when the gap exceeds a threshold, then fly toward the Sonic_Pos_Record_Buf trail with a 5-second timer, falling through to ground AI when close. Until they exist, `TestS3kCnzTraceReplay` still diverges later in the trace (first strict error around frame 318 in the current recording), but the CNZ1 carry intro itself is ROM-accurate.
```

**After:**

```markdown
### Status

Closed. Tails CPU flight AI (Catch_Up_Flying routine 0x02 and
FlySwim_Unknown routine 0x04) was ported in plan
`docs/architecture/plans/2026-04-24-s3k-tails-cpu-flight-ai.md` and
landed with commits covering `SidekickCpuController.CATCH_UP_FLIGHT`
and `SidekickCpuController.FLIGHT_AUTO_RECOVERY`. The CNZ1 trace
replay's first-divergence frame moved from 318 to <NEW_FRAME>
(see Task 7's verification output).
```

*Fill in the `<NEW_FRAME>` placeholder during the task execution from the actual `TestS3kCnzTraceReplay` output. If the first-error frame didn't move past 318, instead write:*

```markdown
### Residual Gap

The flight AI port closed the catch-up and auto-recovery routines, but
`TestS3kCnzTraceReplay` still first diverges at frame <NEW_FRAME> —
this new first-divergence is unrelated to flight AI. File a follow-up
plan once the new divergence is diagnosed.
```

- [ ] **Step 3: Commit (rollup)**

```bash
git add CHANGELOG.md docs/S3K_KNOWN_DISCREPANCIES.md
git commit -m "$(cat <<'TRAILER'
docs(s3k): roll up Tails CPU flight AI plan into CHANGELOG + discrepancies

Task 8 closure of 2026-04-24-s3k-tails-cpu-flight-ai.md. Records the
two ROM CPU routines (0x02 Tails_Catch_Up_Flying, 0x04
Tails_FlySwim_Unknown), the routine-table mapping fix, and the dead-
leader NORMAL transition under the existing "Sonic 3&K Sidekick CPU
Parity" Unreleased section. Updates the "Tails Flying-With-Cargo
Physics" discrepancy to reflect the now-closed catch-up gap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: updated
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
TRAILER
)"
```

---

## Deferred / Out-of-Scope Follow-ups

- **AIZ1 frame 2150 rolling-airborne premature landing.** Tails detects a floor one frame earlier than ROM on an angle-0xFA slope. This is a terrain/sensor divergence (either the engine's ground sensor uses a 1-pixel-shorter radius than ROM while Tails is rolling airborne, or the slope-angle probe reads a different block). File a separate plan titled `docs/architecture/plans/YYYY-MM-DD-s3k-tails-rolling-airborne-landing.md` with a minimal-reproducer unit test pinning the sensor-contact frame, then the fix.
- **Tails flight boost (A/B/C press + y_vel clamp)** in `Tails_Move_FlySwim` (sonic3k.asm:27617-27631). The CPU never presses A/B/C during flight, so this only matters for player-controlled Tails in S3K 1P Tails mode. Not needed for trace parity.
- **Super-form carry routines** (0x1A / 0x1C). No Super Sonic in any current trace fixture.
