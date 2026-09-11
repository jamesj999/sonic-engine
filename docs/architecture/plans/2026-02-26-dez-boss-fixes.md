# DEZ Boss Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix 9 bugs across Silver Sonic, Robotnik transition, and Death Egg Robot in Sonic 2's Death Egg Zone.

**Architecture:** All fixes are in the `com.openggf.game.sonic2.objects.bosses` package. Bugs 1-3 are in `Sonic2MechaSonicInstance.java`, bugs 5-9 in `Sonic2DeathEggRobotInstance.java`, bug 4 requires a new `Sonic2DEZEggmanInstance.java`. The collision system routes via `ObjectManager.TouchResponses.decodeCategory()` using collision flag bits 7-6: 0x00=ENEMY, 0x80=HURT, 0xC0=BOSS.

**Tech Stack:** Java 21, Maven build (`mvn test`), no OpenGL required for unit tests.

**Design doc:** `docs/architecture/designs/2026-02-26-dez-boss-fixes-design.md`

---

### Task 1: Fix Silver Sonic facing direction (Bug 1)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java:704-712`
- Test: `src/test/java/com/openggf/tests/TestDEZMechaSonic.java`

**Step 1: Write failing test**

Add to `TestDEZMechaSonic.java`:

```java
@Test
public void testStartDashDoesNotChangeFacingDirection() {
    // ROM: loc_39D60 only sets x_vel and toggles objoff_2D.
    // It does NOT modify render_flags.x_flip.
    // Facing direction is ONLY changed at end-of-dash via bchg (loc_39A7C).
    Sonic2MechaSonicInstance boss = createBoss();
    // facingLeft starts false (facing right)
    assertFalse(boss.isFacingLeft(), "Initial facing should be right");

    // Force boss into attack state and trigger a dash
    // The startDash method should NOT change facingLeft
    // We verify indirectly: after the toggle-based system runs,
    // facingLeft should follow the toggle pattern, not velocity
    boss.forceRoutine(0x0A); // ROUTINE_ATTACK
    boss.forceAttack(0x06);  // AIM_AND_DASH

    // After first dash completes, facing should toggle from initial (false -> true)
    // NOT be set based on velocity direction
}
```

**Note:** If `forceRoutine`/`forceAttack` test helpers don't exist yet, skip the test and proceed to the fix — this is a 1-line change verified by visual inspection against the ROM.

**Step 2: Remove velocity-based facing from startDash()**

In `Sonic2MechaSonicInstance.java`, method `startDash()` at line 711, remove the line:

```java
// REMOVE this line — ROM loc_39D60 does NOT change render_flags.x_flip:
facingLeft = (xVel < 0);
```

The method becomes:

```java
private void startDash(int speed) {
    int xVel = speed;
    if (!dashDirectionToggle) {
        xVel = -xVel;
    }
    dashDirectionToggle = !dashDirectionToggle;
    state.xVel = xVel;
    // facingLeft is NOT set here — ROM only toggles it at end-of-dash (loc_39A7C)
}
```

**Step 3: Run tests**

Run: `mvn test -Dtest=TestDEZMechaSonic -q`
Expected: All existing tests pass.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java
git commit -m "fix: Silver Sonic facing direction matches ROM bchg-only toggle"
```

---

### Task 2: Fix ball form sparks/booster (Bug 2)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java` (MechaSonicLEDWindow inner class, ~line 1019-1028)

**Step 1: Add ball-form render guard**

In `MechaSonicLEDWindow.appendRenderCommands()`, add a check at the top:

```java
@Override
public void appendRenderCommands(List<GLCommand> commands) {
    if (parent.isDestroyed()) return;
    // ROM: LED overlay children are not rendered during ball form animations.
    // Frames 0x09/0x0A appear as sparks/thruster when overlaid on ball frames.
    Sonic2MechaSonicInstance mechParent = (Sonic2MechaSonicInstance) parent;
    if (mechParent.ballForm) return;

    ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
    if (renderManager == null) return;
    PatternSpriteRenderer renderer = renderManager.getRenderer(
            Sonic2ObjectArtKeys.DEZ_SILVER_SONIC);
    if (renderer == null || !renderer.isReady()) return;
    renderer.drawFrameIndex(mappingFrame, currentX, currentY, mechParent.facingLeft, false);
}
```

**Step 2: Run tests**

Run: `mvn test -Dtest=TestDEZMechaSonic -q`
Expected: PASS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java
git commit -m "fix: hide Silver Sonic LED overlay during ball form"
```

---

### Task 3: Fix Egg Robo initial facing direction (Bugs 7 + partial 8)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java:456-458`
- Test: `src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java`

**Step 1: Write failing test**

Add to `TestDEZDeathEggRobot.java`:

```java
@Test
public void testInitialFacingIsLeft() {
    // ROM: Egg Robo faces left toward the player who enters from the left.
    // facingLeft=false causes forearm punches to go right (away from player).
    Sonic2DeathEggRobotInstance boss = createBoss();
    assertTrue(boss.isFacingLeft(), "Egg Robo should initially face left toward the player");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestDEZDeathEggRobot#testInitialFacingIsLeft -q`
Expected: FAIL — `facingLeft` is currently `false`.

**Step 3: Fix initial facing**

In `initializeBossState()` at line 457, change:

```java
// BEFORE:
facingLeft = false;

// AFTER:
facingLeft = true; // ROM: Egg Robo faces left toward the approaching player
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestDEZDeathEggRobot -q`
Expected: All tests pass. If any test asserts `facingLeft == false`, update that test too.

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git add src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java
git commit -m "fix: Egg Robo initial facing left toward player"
```

---

### Task 4: Fix Sonic bounce on boss hit (Bug 8)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java` (HeadChild inner class, ~line 1598-1601)

**Context:** The bounce infrastructure already exists in `ObjectManager.TouchResponses`:
- `applyBossBounce()` negates both X and Y velocity (line 1388)
- `applyEnemyBounce()` has position-aware Y negate (line 1363)
- `decodeCategory()` at line 1309 routes: `flags & 0xC0` → 0x00=ENEMY, 0xC0=BOSS

The head has collision flag 0x2A → category ENEMY (bits 7-6 = 0x00). The `case ENEMY` handler at line 1330 DOES call `applyEnemyBounce()` when the player is attacking. So the bounce should work via the ObjectManager.

However, body parts have collision flags 0x8F/0x9C/0x86 → category HURT (bit 7 set). The `case HURT` handler calls `applyHurt()` unconditionally — even when the player is attacking. When Sonic jumps on the head, they simultaneously overlap body parts, causing damage.

**Step 1: Fix body part collision to not hurt attacking players**

The body's own collision flag 0x16 → ENEMY category → already safe (attacking player bounces, non-attacking player takes damage). The problem is the HURT-category children (legs, forearms, thighs, jet).

In the ROM, the order of collision processing and the frame-by-frame nature means the head bounce moves the player away before body parts can hurt. In our engine, all collisions are processed simultaneously.

**Fix approach:** The head collision flag should use BOSS category (0xC0) instead of ENEMY (0x00). This routes through `case BOSS ->` which calls `applyBossBounce()` (negates both X and Y). The stronger bounce pushes the player away from body parts more effectively. Change `COLLISION_HEAD` from 0x2A to 0xEA (0xC0 | 0x2A):

```java
// BEFORE:
static final int COLLISION_HEAD = 0x2A;

// AFTER (BOSS category + same size index):
static final int COLLISION_HEAD = 0xC0 | 0x2A; // 0xEA — BOSS category, size 0x2A
```

**Step 2: Run tests**

Run: `mvn test -Dtest=TestDEZDeathEggRobot -q`
Expected: PASS. If any test asserts the exact value `0x2A`, update to `0xEA`.

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git commit -m "fix: Egg Robo head uses BOSS collision category for proper bounce"
```

---

### Task 5: Fix head glow animation loop (Bug 5)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java` (HeadChild inner class, ~line 1559-1569)

**Step 1: Add play-once behavior to stepGlow()**

Add a `glowPlayedOnce` field to HeadChild and modify `stepGlow()`:

```java
// Add field:
private boolean glowPlayedOnce;

// In constructor, initialize:
this.glowPlayedOnce = false;

// Replace stepGlow():
private void stepGlow() {
    if (glowPlayedOnce) return; // Hold on last frame after first play
    glowTimer++;
    if (glowTimer > HEAD_GLOW_SPEED) {
        glowTimer = 0;
        glowIndex++;
        if (glowIndex >= HEAD_GLOW_FRAMES.length) {
            // Play once and hold on last frame (frame 2)
            glowIndex = HEAD_GLOW_FRAMES.length - 1;
            glowPlayedOnce = true;
        }
    }
}
```

**Step 2: Run tests**

Run: `mvn test -Dtest=TestDEZDeathEggRobot -q`
Expected: PASS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git commit -m "fix: Egg Robo head glow plays once on boarding then holds"
```

---

### Task 6: Validate and fix child priorities (Bug 6)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java:476-486`

**Context:** ROM `ChildObjC7_*` subtype bytes (4, 6, 8, $A, $C, $E, $10, $12, $14, $16) are routine indices, NOT render priorities. Display priorities are set in each child's init routine. The visual rule is:
- Front parts: render IN FRONT of body (lower priority number)
- Body: priority 5
- Back parts: render BEHIND body (higher priority number)

**Step 1: Fix back-part priorities**

Current back parts have priority 5 (same as body). They should be priority 6 (behind body):

```java
// BEFORE:
backLowerLeg = new ArticulatedChild(this, "BackLowerLeg", 5, FRAME_LOWER_LEG, 4);
backForearm = new ForearmChild(this, "BackForearm", 5, false);
backThigh = new ArticulatedChild(this, "BackThigh", 5, FRAME_THIGH, 4);

// AFTER:
backLowerLeg = new ArticulatedChild(this, "BackLowerLeg", 6, FRAME_LOWER_LEG, 6);
backForearm = new ForearmChild(this, "BackForearm", 6, false);
backThigh = new ArticulatedChild(this, "BackThigh", 6, FRAME_THIGH, 6);
```

Also verify the jet renders behind the body (it's mounted on the back):

```java
// Jet should be behind body (priority 6):
jet = new JetChild(this, 6);
```

And verify the shoulder/head render in front:

```java
// Shoulder and head in front of body (priority 3 for head to be clearly on top):
shoulder = new ArticulatedChild(this, "Shoulder", 4, FRAME_SHOULDER, 4);
head = new HeadChild(this, 3);
```

**Step 2: Run tests**

Run: `mvn test -Dtest=TestDEZDeathEggRobot -q`
Expected: PASS. Update any tests that assert specific priority values.

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git commit -m "fix: Egg Robo child render priorities match visual layering"
```

---

### Task 7: Verify defeat sequence (Bug 9)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java:1195-1207`
- Test: `src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java`

**Context:** After fixing bugs 7 (facing) and 8 (bounce), the defeat should be reachable. But there's a code issue in `breakApart()`: `ForearmChild extends ArticulatedChild`, so `instanceof ArticulatedChild` matches ForearmChild first, making the `instanceof ForearmChild` branch unreachable. Currently harmless since both call the same `startFalling()`, but it's a code smell.

**Step 1: Write test for defeat trigger**

```java
@Test
public void testDefeatTriggersAtZeroHP() {
    Sonic2DeathEggRobotInstance boss = createBoss();
    // Simulate 12 hits
    for (int i = 0; i < 12; i++) {
        boss.onHeadHit();
        boss.getState().invulnerable = false; // Reset invuln for next hit
        boss.getState().invulnerabilityTimer = 0;
    }
    assertEquals(Sonic2DeathEggRobotInstance.BODY_DEFEAT, boss.getBodyRoutine(),
            "Boss should enter defeat after 12 hits");
}
```

**Note:** If `getState()` or `BODY_DEFEAT` aren't accessible in tests, add test accessors.

**Step 2: Fix instanceof order in breakApart()**

```java
private void breakApart() {
    AbstractBossChild[] breakParts = {
            shoulder, frontLowerLeg, frontForearm, upperArm,
            frontThigh, backLowerLeg, backForearm, backThigh
    };
    for (int i = 0; i < breakParts.length && i < BREAK_VELOCITIES.length; i++) {
        // Check ForearmChild FIRST (more specific subclass)
        if (breakParts[i] instanceof ForearmChild fc) {
            fc.startFalling(BREAK_VELOCITIES[i][0], BREAK_VELOCITIES[i][1]);
        } else if (breakParts[i] instanceof ArticulatedChild ac) {
            ac.startFalling(BREAK_VELOCITIES[i][0], BREAK_VELOCITIES[i][1]);
        }
    }
}
```

**Step 3: Run tests**

Run: `mvn test -Dtest=TestDEZDeathEggRobot -q`
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git add src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java
git commit -m "fix: Egg Robo defeat sequence reachable, fix instanceof order"
```

---

### Task 8: Investigate window visibility (Bug 3)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java` (MechaSonicDEZWindow inner class)

This task requires visual testing. The animation logic in `MechaSonicDEZWindow` appears correct (opens blinds → shows Robotnik → closes on defeat). Potential issues:

**Step 1: Add diagnostic logging**

Add temporary logging to `MechaSonicDEZWindow.update()` and `appendRenderCommands()`:

```java
// In update():
System.out.println("[DEZWindow] animId=" + animId + " animFrame=" + animFrame
    + " mappingFrame=" + mappingFrame + " beginUpdate=" + true);

// In appendRenderCommands():
System.out.println("[DEZWindow] rendering frame=" + mappingFrame + " at (" + currentX + "," + currentY + ")");
```

**Step 2: Run the game and observe**

Run: `java -jar target/sonic-engine-0.4.prerelease-jar-with-dependencies.jar`
Navigate to DEZ. Watch console output for window state progression.

**Step 3: Fix based on findings**

Common issues to check:
1. **Position wrong:** Window at (0x2C0, 0x139) — verify this is visible in the Silver Sonic arena (camera locked at X=0x224, so screen X = 0x2C0-0x224 = 0x9C ≈ 156px from left)
2. **beginUpdate() gating:** If `beginUpdate()` returns false, the animation never advances
3. **Art not loaded:** Verify `Sonic2ObjectArtProvider.loadArtForZone()` loads DEZ_WINDOW art for the DEZ zone
4. **Closing animation:** After defeat, `onDefeatStarted()` calls `dezWindow.setAnimId(1)` which plays the closing anim. Verify it runs before `setDestroyed(true)` is called on the parent

**Step 4: Remove diagnostic logging and commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java
git commit -m "fix: DEZ window visibility during Silver Sonic fight"
```

---

### Task 9: Implement Robotnik escape sequence (Bug 4)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DEZEggmanInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java` (defeat handler)
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java` (HeadChild boarding)
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2DEZEvents.java`
- Possibly modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java` (art loading)
- Possibly modify: `src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java` (ROM addresses)

**Context:** ROM ObjC6 (s2.asm:81568-81785) handles Robotnik running from the Silver Sonic arena to the Egg Robo cockpit. Uses `ArtTile_ArtKos_LevelArt` (DEZ level art tiles) with mappings from `objC6_a.asm`. The object has 5 phases: init, wait for player, pause, run right, jump into cockpit.

**Step 1: Create Sonic2DEZEggmanInstance**

```java
package com.openggf.game.sonic2.objects.bosses;

// ROM: ObjC6 (s2.asm:81568-81785)
// Robotnik transition character between Silver Sonic and Death Egg Robot fights.
// Spawned after Silver Sonic defeat, runs right and jumps into Egg Robo cockpit.

public class Sonic2DEZEggmanInstance extends AbstractBossChild {

    // State machine
    private static final int STATE_INIT = 0;
    private static final int STATE_WAIT_PLAYER = 2;
    private static final int STATE_PAUSE = 4;
    private static final int STATE_RUN = 6;
    private static final int STATE_JUMP = 8;

    // ROM constants
    private static final int PAUSE_TIMER = 0x18;       // frames before running
    private static final int RUN_X_VEL = 0x200;        // running speed (8.8 fixed)
    private static final int JUMP_X_VEL = 0x80;        // jump horizontal speed
    private static final int JUMP_Y_VEL = -0x200;      // jump vertical speed
    private static final int JUMP_TIMER = 0x50;         // frames before despawn
    private static final int GRAVITY = 0x10;            // jump gravity per frame
    private static final int ESCAPE_X = 0x810;          // X threshold for jump
    private static final int PLAYER_TRIGGER_DIST = 0xB8; // angle threshold for player approach

    private int eggmanState;
    private int timer;
    private int xVel;
    private int yVel;
    private int animFrame;
    private int animTimer;

    // ... constructor, update(), render()
    // Animation: 4-frame running cycle from ROM Ani_objC6: {1, 0, 1, 2, 3, $FA}
}
```

**Step 2: Spawn from Silver Sonic defeat handler**

In `Sonic2MechaSonicInstance.updateDefeat()`, after the explosion timer ends and before destroying self, spawn the Eggman transition:

```java
private void updateDefeat(int frameCounter) {
    defeatTimer--;
    if (defeatTimer < 0) {
        Camera camera = Camera.getInstance();
        camera.setMaxX((short) 0x1000);
        Sonic2LevelEventManager eventManager = Sonic2LevelEventManager.getInstance();
        eventManager.setEventRoutine(eventManager.getEventRoutine() + 2);
        GameServices.gameState().setCurrentBossId(0);
        AudioManager.getInstance().playMusic(Sonic2Music.DEATH_EGG.id);

        // Spawn Robotnik transition (ObjC6)
        spawnEggmanTransition();

        setDestroyed(true);
        return;
    }
    // ... explosion spawning
}

private void spawnEggmanTransition() {
    if (levelManager.getObjectManager() == null) return;
    Sonic2DEZEggmanInstance eggman = new Sonic2DEZEggmanInstance(this, 0x3F8, 0x160);
    levelManager.getObjectManager().addDynamicObject(eggman);
}
```

**Step 3: Connect to HeadChild boarding**

Replace the timer-based `isEggmanBoarded()` in `HeadChild` with a flag that the Eggman transition object sets when it reaches the Egg Robo:

```java
// In Sonic2DeathEggRobotInstance:
private boolean eggmanBoardedFlag = false;

void setEggmanBoarded() {
    this.eggmanBoardedFlag = true;
}

// In HeadChild.isEggmanBoarded():
boolean isEggmanBoarded() {
    Sonic2DeathEggRobotInstance boss = (Sonic2DeathEggRobotInstance) parent;
    if (boss.eggmanBoardedFlag && headRoutine < 4) {
        headRoutine = 4;
        eggmanBoarded = true;
    }
    return eggmanBoarded;
}
```

**Step 4: Art investigation**

ROM ObjC6 uses `ArtTile_ArtKos_LevelArt` — this means it reuses tiles already loaded as part of the DEZ level art (Kosinski-compressed). The mapping offsets in `objC6_a.asm` reference these pre-loaded tiles.

Options:
1. **Preferred:** Use RomOffsetFinder to find the Robotnik running art in the DEZ level art blocks and reference the existing tiles
2. **Fallback:** Load separate Nemesis-compressed Robotnik art (`ArtNem_RobotnikRunning` at tile 0x0518) and parse `objC6_a.asm` mappings

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=search robotnik" -q`

Use results to determine the correct art loading approach.

**Step 5: Wire up in DEZ events**

In `Sonic2DEZEvents.java`, the Eggman transition object needs to be tracked so the event system waits for it to complete before spawning the Death Egg Robot. Add a reference in the event handler:

```java
// In case 4 (after Silver Sonic defeat):
case 4 -> {
    camera.setMinX(camera.getX());
    if (camera.getX() >= 0x300) {
        eventRoutine += 2;
        // Load DEZ boss art PLC
    }
}
```

**Step 6: Run full test suite**

Run: `mvn test -q`
Expected: All tests pass.

**Step 7: Visual test**

Run the game, play through DEZ. Verify:
1. Silver Sonic fight works with fixes from Tasks 1-2
2. After Silver Sonic defeat, Robotnik appears and runs right
3. Robotnik jumps off screen right
4. Death Egg Robot fight begins with Eggman boarding sequence
5. Head animation plays once and stops
6. Hitting the head bounces Sonic
7. Forearms punch toward the player
8. Defeat animation plays when HP reaches 0

**Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DEZEggmanInstance.java
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java
git add src/main/java/com/openggf/game/sonic2/events/Sonic2DEZEvents.java
git commit -m "feat: Robotnik escape sequence between DEZ boss fights (ObjC6)"
```
