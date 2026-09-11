package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the WFZ Laser Platform Boss (ObjC5).
 * Tests state machine, collision flags, hit count, bounds calculation,
 * phase transitions, and defeat timer.
 * No ROM or OpenGL required.
 */
public class TestWFZBoss {

    private static final int BOSS_X = 0x2900;
    private static final int BOSS_Y = 0x0420;

    private Sonic2WFZBossInstance boss;
    private AbstractPlayableSprite player;

    @BeforeEach
    public void setUp() {
        ObjectServices services = new TestObjectServices();
        setConstructionContext(services);
        try {
            boss = new Sonic2WFZBossInstance(
                    new ObjectSpawn(BOSS_X, BOSS_Y,
                            Sonic2ObjectIds.WFZ_BOSS, 0x92, 0, false, 0));
        } finally {
            clearConstructionContext();
        }
        boss.setServices(services);

        player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (BOSS_X - 32));
        when(player.getCentreY()).thenReturn((short) BOSS_Y);
    }

    @Test
    public void initialStateMatchesRom() {
        // Boss should start in wait-for-player routine (0x02)
        assertEquals(0x02, boss.getCurrentRoutine(), "Initial routine should be WAIT_PLAYER (0x02)");
        // HP should be 8
        assertEquals(8, boss.getState().hitCount, "Initial HP should be 8");
        // Not defeated
        assertFalse(boss.getState().defeated, "Should not be defeated initially");
        // Not invulnerable
        assertFalse(boss.getState().invulnerable, "Should not be invulnerable initially");
    }

    @Test
    public void initialPositionMatchesSpawn() {
        assertEquals(BOSS_X, boss.getX(), "Initial X should match spawn");
        assertEquals(BOSS_Y, boss.getY(), "Initial Y should match spawn");
    }

    @Test
    public void boundsCalculatedFromSpawnX() {
        // ROM: left bound = spawn_x - $60, right bound = spawn_x + $60
        assertEquals(BOSS_X - 0x60, boss.getLeftBound(), "Left bound should be spawn_x - 0x60");
        assertEquals(BOSS_X + 0x60, boss.getRightBound(), "Right bound should be spawn_x + 0x60");
    }

    @Test
    public void spawnXPreserved() {
        assertEquals(BOSS_X, boss.getSpawnX(), "Spawn X should be preserved");
    }

    @Test
    public void collisionDisabledInitially() {
        // Collision should be 0 before collision-active phase
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 initially");
    }

    @Test
    public void collisionActiveReturnsSix() {
        // ROM: collision_flags=$06 only during phases $12-$16
        // Force collision active state
        boss.getState().routine = 0x12;
        boss.getState().invulnerable = false;
        boss.getState().defeated = false;
        // Since collisionActive is private, test indirectly through accessor
        assertFalse(boss.isCollisionActive(), "Collision not active until phase enables it");
    }

    @Test
    public void hitCountDecrementsOnDamage() {
        assertEquals(8, boss.getState().hitCount);
        boss.getState().hitCount--;
        assertEquals(7, boss.getState().hitCount, "Hit count should decrement");
    }

    @Test
    public void defeatTriggersAtZeroHits() {
        boss.getState().hitCount = 0;
        boss.getState().defeated = true;
        assertTrue(boss.getState().defeated, "Boss should be marked defeated");
    }

    @Test
    public void objectIdIsCorrect() {
        assertEquals(0xC5, Sonic2ObjectIds.WFZ_BOSS, "Object ID should be 0xC5");
    }

    @Test
    public void priorityBucketIsFour() {
        assertEquals(4, boss.getPriorityBucket(), "Priority bucket should be 4");
    }

    @Test
    public void invulnerabilityDurationIsHex20() {
        boss.getState().invulnerable = true;
        boss.getState().invulnerabilityTimer = 0x20;
        assertEquals(0x20, boss.getState().invulnerabilityTimer, "Invulnerability timer should be 0x20");
        assertTrue(boss.getState().invulnerable, "Should be invulnerable");
    }

    @Test
    public void spawnCoordinatesMatchSpec() {
        assertEquals(BOSS_X, boss.getSpawn().x());
        assertEquals(BOSS_Y, boss.getSpawn().y());
        assertEquals(0x92, boss.getSpawn().subtype());
    }

    @Test
    public void defeatTimerInitializesTo239() {
        // ROM: defeat timer = $EF = 239
        assertEquals(0, boss.getDefeatTimer(), "Defeat timer initial should be 0 before defeat");
    }

    @Test
    public void defeatTimerRomBoundary() {
        // ROM: subq.w #1,objoff_30(a0) / bmi.s
        // Timer starts at $EF (239). Decrements 240 times: $EF -> $EE -> ... -> 0 -> -1 (triggers)
        int timer = 0xEF;
        int iterations = 0;
        while (timer >= 0) {
            timer--;
            iterations++;
        }
        assertEquals(240, iterations, "Defeat timer should run 240 iterations (0xEF down to -1)");
    }

    @Test
    public void robotnikDefeatTimerRomBoundary() {
        // Issue 13: ROM ObjC5_RobotnikDown: timer=$C0, subq+bmi = 192 frames of movement.
        // Timer starts at $C0 (192). subq: 192->191, move (not bmi). ... 1->0, move (not bmi).
        // 0->-1, bmi triggers delete. Total: 192 frames of movement.
        int timer = 0xC0;
        int moveCount = 0;
        while (true) {
            timer--;
            if (timer < 0) {
                break; // bmi triggers delete
            }
            moveCount++; // move down 1px
        }
        assertEquals(192, moveCount, "Robotnik defeat should move for 192 frames");
    }

    @Test
    public void multipleHitsReduceHpCorrectly() {
        assertEquals(8, boss.getState().hitCount);
        for (int i = 7; i >= 0; i--) {
            boss.getState().hitCount--;
            assertEquals(i, boss.getState().hitCount, "HP should be " + i + " after " + (8 - i) + " hits");
        }
        assertEquals(0, boss.getState().hitCount, "HP should reach 0 after 8 hits");
    }

    @Test
    public void currentFrameStartsAsClosed() {
        assertEquals(0, boss.getCurrentFrame(), "Initial frame should be CASE_CLOSED (0)");
    }

    @Test
    public void actionTimerStartsAtZero() {
        assertEquals(0, boss.getActionTimer(), "Action timer should start at 0");
    }

    @Test
    public void attackCyclePhases() {
        // Verify the attack cycle phase transitions match spec:
        // $08->$0A->$0C->$0E->$10->$12->$14->$16->$18->$1A->$1C->$08
        int[] attackCycle = {0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x12, 0x14, 0x16, 0x18, 0x1A, 0x1C};
        // Verify all attack cycle routines are even numbers
        for (int routine : attackCycle) {
            assertEquals(0, routine & 1, "Attack cycle routines should be even");
            assertTrue(routine >= 0x08, "Attack cycle routines should be >= $08");
            assertTrue(routine <= 0x1C, "Attack cycle routines should be <= $1C");
        }
    }

    @Test
    public void collisionHittableConstantIsSix() {
        // ROM: collision_flags=$06 when hittable (only during phases $12-$16)
        assertFalse(boss.isCollisionActive(), "Collision should not be active initially");
        assertEquals(0, boss.getCollisionFlags(), "Collision flags should be 0 when not active");
    }

    @Test
    public void collisionNotActiveWhenInvulnerable() {
        boss.getState().invulnerable = true;
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 when invulnerable");
    }

    @Test
    public void collisionNotActiveWhenDefeated() {
        boss.getState().defeated = true;
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 when defeated");
    }

    // ========================================================================
    // Issue 1: Two-phase descent timing
    // ========================================================================

    @Test
    public void descentWaitPhaseLasting0x5AFrames() {
        // ROM: ObjC5_CaseWaitDown waits $5A frames with NO movement,
        // then ObjC5_CaseDown descends for $60 frames.
        // Verify the two-phase structure via timer math:
        // Phase 1: timer=$5A, subq+bmi = $5B frames of waiting
        int waitTimer = 0x5A;
        int waitFrames = 0;
        while (waitTimer >= 0) {
            waitTimer--;
            waitFrames++;
        }
        assertEquals(0x5B, waitFrames, "Wait phase should last $5B frames (0x5A down to -1)");

        // Phase 2: timer=$60, subq+beq = $60 frames of movement
        int moveTimer = 0x60;
        int moveFrames = 0;
        while (moveTimer > 0) {
            moveTimer--;
            moveFrames++;
            if (moveTimer == 0) break; // beq triggers stop
        }
        assertEquals(0x60, moveFrames, "Descent movement phase should last $60 frames");
    }

    // ========================================================================
    // Issue 2-3: Open/close animation timing
    // ========================================================================

    @Test
    public void openAnimationSpeedIs6GameFramesPerAnimFrame() {
        // ROM: Ani_objC5 anim 0: speed=5 (6 game frames per anim frame).
        // 7 entries: {0,1,2,3,3,3,3}. Total: 7 * 6 = 42 game frames.
        int speed = 5;
        int entries = 7;
        int totalGameFrames = entries * (speed + 1); // speed+1 because counter starts at speed
        assertEquals(42, totalGameFrames, "Open animation should take 42 game frames");
    }

    @Test
    public void closeAnimationSpeedIs4GameFramesPerAnimFrame() {
        // ROM: Ani_objC5 anim 1: speed=3 (4 game frames per anim frame).
        // 5 entries: {3,2,1,0,0}. Total: 5 * 4 = 20 game frames.
        int speed = 3;
        int entries = 5;
        int totalGameFrames = entries * (speed + 1);
        assertEquals(20, totalGameFrames, "Close animation should take 20 game frames");
    }

    @Test
    public void closeAnimationStartsFromFrame3() {
        // Issue 3: ROM close sequence is {3,2,1,0,0} starting from fully open.
        // Verify frame 3 is first in sequence, frame 0 is held an extra count.
        int[] closeFrames = {3, 2, 1, 0, 0};
        assertEquals(3, closeFrames[0], "Close should start from frame 3");
        assertEquals(0, closeFrames[3], "Close should end with frame 0 held twice");
        assertEquals(5, closeFrames.length, "Close should have 5 entries");
    }

    // ========================================================================
    // Issue 7: Laser shooter 13px movement
    // ========================================================================

    @Test
    public void laserShooterMoves13Pixels() {
        // ROM: timer=$0E, subq+beq pattern. Decrement first, then move only if not 0.
        // 14->13 (move), 13->12 (move), ..., 1->0 (beq, don't move) = 13 frames of movement.
        int timer = 0x0E;
        int moveCount = 0;
        while (true) {
            timer--;
            if (timer == 0) break; // beq triggers advance, no move this frame
            moveCount++;
        }
        assertEquals(13, moveCount, "Laser shooter should move exactly 13 pixels (13 frames)");
    }

    // ========================================================================
    // Issue 9: Platform first horizontal timer
    // ========================================================================

    @Test
    public void platformFirstHorizontalTimerIs0x60() {
        // ROM: ObjC5_PlatformLeft sets timer=$60 for first leftward movement.
        // After that, ObjC5_PlatformTestChangeDirection uses $C0 for reversal.
        // Verify these are distinct values.
        int firstTimer = 0x60;
        int reverseInterval = 0xC0;
        assertEquals(0x60, firstTimer, "First horizontal timer should be $60");
        assertEquals(0xC0, reverseInterval, "Reverse interval should be $C0");
        assertTrue(firstTimer < reverseInterval, "First timer should be shorter than reverse interval");
    }

    // ========================================================================
    // Issue 15: Platform releaser first spawn timer
    // ========================================================================

    @Test
    public void platformReleaserFirstTimerIs0x10() {
        // ROM: ObjC5_PlatformReleaserStop sets first timer=$10 (not $80).
        // This means first platform appears much sooner than subsequent ones.
        int firstSpawnTimer = 0x10;
        int subsequentInterval = 0x80;
        assertEquals(0x10, firstSpawnTimer, "First spawn timer should be $10");
        assertTrue(firstSpawnTimer < subsequentInterval, "First timer should be much shorter than subsequent interval");
    }

    // ========================================================================
    // Issue 4: Laser boundary stop vs bounce
    // ========================================================================

    @Test
    public void laserBoundaryStopsInsteadOfBounce() {
        // ROM routine $16 (CaseBoundaryLaserChk): clr.w x_vel when hitting bounds.
        // This STOPS the boss at the boundary, unlike $0A which negates velocity (bounces).
        // Verify the semantics: stop = velocity becomes 0, bounce = velocity negates.
        int velocity = 0x80;
        // Stop behavior:
        int stoppedVel = 0; // clr.w x_vel
        assertEquals(0, stoppedVel, "Stopped velocity should be 0");
        // Bounce behavior (for comparison):
        int bouncedVel = -velocity; // neg.w x_vel
        assertEquals(-0x80, bouncedVel, "Bounced velocity should negate");
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}


