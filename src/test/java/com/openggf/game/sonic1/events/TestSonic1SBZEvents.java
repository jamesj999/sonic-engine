package com.openggf.game.sonic1.events;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Scrap Brain Zone (Acts 1-2) and Final Zone dynamic level events.
 *
 * <p>ROM reference: DLE_SBZ + DLE_FZ (DynamicLevelEvents.asm)
 *
 * <p>SBZ Act 1 (DLE_SBZ1): Bottom boundary adjustments at three X thresholds.
 * SBZ Act 2 (DLE_SBZ2): 4-routine boss sequence with collapsing floor.
 * FZ (DLE_FZ): 5-routine boss sequence with left boundary locking.
 *
 * <p>Production code: {@link Sonic1SBZEvents}
 */
public class TestSonic1SBZEvents {

    private Sonic1SBZEvents events;
    private Camera cam;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        cam = GameServices.camera();
        TestObjectServices testServices = new TestObjectServices();
        setConstructionContext(testServices);
        // Inject a minimal ObjectManager so spawn calls in event handlers don't NPE.
        injectObjectManager(GameServices.level(), testServices);
        events = new Sonic1SBZEvents();
        events.init();
    }

    @AfterEach
    public void tearDown() {
        clearConstructionContext();
        SessionManager.clear();
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = AbstractObjectInstance.class
                    .getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(services);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class
                    .getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Injects a minimal ObjectManager into the LevelManager so that event
     * handler spawn calls (SBZ2 routine 2/4, FZ routine 2) don't NPE.
     */
    private static void injectObjectManager(LevelManager lm, ObjectServices services) {
        try {
            ObjectManager om = new ObjectManager(
                    List.of(), null, 0, null, null, null, GameServices.camera(), services);
            Field field = LevelManager.class.getDeclaredField("objectManager");
            field.setAccessible(true);
            field.set(lm, om);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ======== SBZ Act 1 (DLE_SBZ1) ========

    /**
     * SBZ1 default: when camera X < 0x1880, maxYTarget = 0x720.
     */
    @Test
    public void testSBZ1_DefaultBoundary() {
        cam.setX((short) 0x1000);
        events.update(0);
        assertEquals((short) 0x720, cam.getMaxYTarget(), "maxYTarget should be 0x720 when X < 0x1880");
    }

    /**
     * SBZ1 mid-section: when camera X >= 0x1880 and < 0x2000, maxYTarget = 0x620.
     */
    @Test
    public void testSBZ1_MidBoundary() {
        cam.setX((short) 0x1880);
        events.update(0);
        assertEquals((short) 0x620, cam.getMaxYTarget(), "maxYTarget should be 0x620 when X >= 0x1880");
    }

    /**
     * SBZ1 final section: when camera X >= 0x2000, maxYTarget = 0x2A0.
     */
    @Test
    public void testSBZ1_FinalBoundary() {
        cam.setX((short) 0x2000);
        events.update(0);
        assertEquals((short) 0x2A0, cam.getMaxYTarget(), "maxYTarget should be 0x2A0 when X >= 0x2000");
    }

    // ======== SBZ Act 2 (DLE_SBZ2) ========

    /**
     * SBZ2 routine 0: default bottom boundary is 0x800 when X < 0x1800.
     */
    @Test
    public void testSBZ2Routine0_DefaultBoundary() {
        cam.setX((short) 0x1000);
        events.update(1);
        assertEquals((short) 0x800, cam.getMaxYTarget(), "maxYTarget should be 0x800 when X < 0x1800");
        assertEquals(0, events.getEventRoutine(), "Should stay at routine 0");
    }

    /**
     * SBZ2 routine 0: maxYTarget drops to 0x510 at X >= 0x1800,
     * and advances to routine 2 at X >= 0x1E00.
     */
    @Test
    public void testSBZ2Routine0_AdvancesAtThreshold() {
        cam.setX((short) 0x1E00);
        events.update(1);
        assertEquals((short) 0x510, cam.getMaxYTarget(), "maxYTarget should be 0x510 (boss_sbz2_y) when X >= 0x1800");
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when X >= 0x1E00");
    }

    /**
     * SBZ2 routine 2: stays at routine 2 when X < 0x1EB0.
     */
    @Test
    public void testSBZ2Routine2_BelowThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x1E00);
        events.update(1);
        assertEquals(2, events.getEventRoutine(), "Should stay at routine 2 when X < 0x1EB0");
    }

    /**
     * SBZ2 routine 2: advances to routine 4 at X >= 0x1EB0 (spawns floor object).
     */
    @Test
    public void testSBZ2Routine2_AdvancesAtThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x1EB0);
        events.update(1);
        assertEquals(4, events.getEventRoutine(), "Should advance to routine 4 when X >= 0x1EB0");
    }

    /**
     * SBZ2 routine 4: locks minX to camera X, stays when X < 0x1F60.
     */
    @Test
    public void testSBZ2Routine4_LocksMinXBelowThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x1F00);
        events.update(1);
        assertEquals((short) 0x1F00, cam.getMinX(), "Should lock minX to camera X");
        assertEquals(4, events.getEventRoutine(), "Should stay at routine 4 when X < 0x1F60");
    }

    /**
     * SBZ2 routine 4: advances to routine 6 at X >= 0x1F60 (spawns boss).
     */
    @Test
    public void testSBZ2Routine4_AdvancesAtThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x1F60);
        events.update(1);
        assertEquals(6, events.getEventRoutine(), "Should advance to routine 6 when X >= 0x1F60");
    }

    /**
     * SBZ2 routine 6: when X >= 0x2050, does nothing (rts path).
     */
    @Test
    public void testSBZ2Routine6_PastBossX() {
        events.setEventRoutine(6);
        cam.setX((short) 0x2050);
        short minXBefore = cam.getMinX();
        events.update(1);
        assertEquals(6, events.getEventRoutine(), "Should stay at routine 6");
        assertEquals(minXBefore, cam.getMinX(), "Should not modify minX when X >= 0x2050");
    }

    /**
     * SBZ2 routine 6: when X < 0x2050, locks minX to camera X.
     */
    @Test
    public void testSBZ2Routine6_BeforeBossX() {
        events.setEventRoutine(6);
        cam.setX((short) 0x2000);
        events.update(1);
        assertEquals(6, events.getEventRoutine(), "Should stay at routine 6");
        assertEquals((short) 0x2000, cam.getMinX(), "Should lock minX to camera X when X < 0x2050");
    }

    // ======== Final Zone (DLE_FZ) ========

    /**
     * FZ routine 0: stays at routine 0 when X < 0x2148. Always locks minX.
     */
    @Test
    public void testFZRoutine0_BelowThreshold() {
        cam.setX((short) 0x2100);
        events.updateFZ();
        assertEquals(0, events.getEventRoutine(), "Should stay at routine 0 when X < 0x2148");
        assertEquals((short) 0x2100, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 0: advances to routine 2 at X >= 0x2148. Locks minX.
     */
    @Test
    public void testFZRoutine0_AdvancesAtThreshold() {
        cam.setX((short) 0x2148);
        events.updateFZ();
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when X >= 0x2148");
        assertEquals((short) 0x2148, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 2: stays at routine 2 when X < 0x2300. Locks minX.
     */
    @Test
    public void testFZRoutine2_BelowThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x2200);
        events.updateFZ();
        assertEquals(2, events.getEventRoutine(), "Should stay at routine 2 when X < 0x2300");
        assertEquals((short) 0x2200, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 2: advances to routine 4 at X >= 0x2300 (spawns boss). Locks minX.
     */
    @Test
    public void testFZRoutine2_AdvancesAtThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x2300);
        events.updateFZ();
        assertEquals(4, events.getEventRoutine(), "Should advance to routine 4 when X >= 0x2300");
        assertEquals((short) 0x2300, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 4: stays at routine 4 when X < 0x2450. Locks minX.
     */
    @Test
    public void testFZRoutine4_BelowThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x2400);
        events.updateFZ();
        assertEquals(4, events.getEventRoutine(), "Should stay at routine 4 when X < 0x2450");
        assertEquals((short) 0x2400, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 4: advances to routine 6 at X >= 0x2450. Locks minX.
     */
    @Test
    public void testFZRoutine4_AdvancesAtThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x2450);
        events.updateFZ();
        assertEquals(6, events.getEventRoutine(), "Should advance to routine 6 when X >= 0x2450");
        assertEquals((short) 0x2450, cam.getMinX(), "Should lock minX to camera X");
    }

    /**
     * FZ routine 6: no-op (rts). Does not modify anything.
     */
    @Test
    public void testFZRoutine6_NoOp() {
        events.setEventRoutine(6);
        cam.setX((short) 0x2500);
        short minXBefore = cam.getMinX();
        events.updateFZ();
        assertEquals(6, events.getEventRoutine(), "Should stay at routine 6");
        assertEquals(minXBefore, cam.getMinX(), "Routine 6 should not modify minX");
    }

    /**
     * FZ routine 8: locks minX to camera X.
     */
    @Test
    public void testFZRoutine8_LocksMinX() {
        events.setEventRoutine(8);
        cam.setX((short) 0x2600);
        events.updateFZ();
        assertEquals(8, events.getEventRoutine(), "Should stay at routine 8");
        assertEquals((short) 0x2600, cam.getMinX(), "Should lock minX to camera X");
    }
}
