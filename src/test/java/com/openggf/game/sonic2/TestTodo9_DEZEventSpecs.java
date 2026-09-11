package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.events.Sonic2DEZEvents;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec tests for Death Egg Zone (DEZ) level events.
 * DEZ is a single-act zone with a unique event sequence: Silver Sonic boss,
 * then transition to the final Eggman boss.
 *
 * <p>ROM reference: LevEvents_DEZ (s2.asm:21661-21724)
 * DEZ has 5 routines (0-8, incremented by 2):
 * <ul>
 *   <li>Routine 0: Wait for camera X >= 320 ($140), spawn Silver Sonic</li>
 *   <li>Routine 2: No-op (Silver Sonic active)</li>
 *   <li>Routine 4: Lock left boundary to camera X, wait for X >= $300, load DEZ boss PLC</li>
 *   <li>Routine 6: Lock left boundary to camera X, wait for X >= $680, lock arena</li>
 *   <li>Routine 8: No-op (final boss active)</li>
 * </ul>
 *
 * <p>Production code: {@link Sonic2DEZEvents}
 */
public class TestTodo9_DEZEventSpecs {

    private Sonic2DEZEvents events;
    private Camera cam;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        cam = GameServices.camera();
        events = new Sonic2DEZEvents();
        events.init(0);
        // Set construction context so that any objects spawned by event updates
        // (e.g., Silver Sonic) can call services() during their constructor.
        setConstructionContext(new TestObjectServices());
    }

    @AfterEach
    public void tearDown() {
        clearConstructionContext();
        SessionManager.clear();
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = com.openggf.level.objects.AbstractObjectInstance.class
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
            Field field = com.openggf.level.objects.AbstractObjectInstance.class
                    .getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Routine 0: Spawn Silver Sonic at camera X >= 320.
     * ROM reference: LevEvents_DEZ_Routine1 (s2.asm:21676-21691)
     * <pre>
     *   move.w #320,d0                     ; trigger threshold
     *   cmp.w  (Camera_X_pos).w,d0
     *   bhi.s  +                           ; skip if camera X < 320
     *   move.b #ObjID_MechaSonic,id(a1)    ; spawn Silver Sonic (ObjAF)
     *   move.b #$48,subtype(a1)
     *   move.w #$348,x_pos(a1)             ; spawn at X=$348
     *   move.w #$A0,y_pos(a1)              ; spawn at Y=$A0
     *   moveq  #PLCID_FieryExplosion,d0
     *   jmpto  JmpTo2_LoadPLC
     * </pre>
     */
    @Test
    public void testDEZRoutine0_DoesNotAdvanceBelowThreshold() {
        cam.setX((short) 0x100);
        events.update(0, 0);
        assertEquals(0, events.getEventRoutine(), "Should stay at routine 0 when camera X < $140");
    }

    @Test
    public void testDEZRoutine0_AdvancesAtThreshold() {
        cam.setX((short) 0x140);
        events.update(0, 0);
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when camera X >= $140");
    }

    @Test
    public void testDEZRoutine0_AdvancesAboveThreshold() {
        cam.setX((short) 0x200);
        events.update(0, 0);
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when camera X > $140");
    }

    /**
     * Routine 2: No-op while Silver Sonic is active.
     * ROM reference: LevEvents_DEZ_Routine2 (s2.asm:21694-21695)
     * <pre>
     *   LevEvents_DEZ_Routine2:
     *     rts
     * </pre>
     */
    @Test
    public void testDEZRoutine2_NoOp() {
        events.setEventRoutine(2);
        short minXBefore = cam.getMinX();
        short maxXBefore = cam.getMaxX();
        events.update(0, 0);
        assertEquals(2, events.getEventRoutine(), "Routine 2 should not change the event routine");
        assertEquals(minXBefore, cam.getMinX(), "Routine 2 should not change minX");
        assertEquals(maxXBefore, cam.getMaxX(), "Routine 2 should not change maxX");
    }

    /**
     * Routine 4: Track camera left boundary, load DEZ boss PLC at X >= $300.
     * ROM reference: LevEvents_DEZ_Routine3 (s2.asm:21698-21707)
     * <pre>
     *   move.w (Camera_X_pos).w,(Camera_Min_X_pos).w  ; lock left to camera
     *   cmpi.w #$300,(Camera_X_pos).w
     *   blo.s  +                                       ; skip if X < $300
     *   addq.b #2,(Dynamic_Resize_Routine).w
     *   moveq  #PLCID_DezBoss,d0
     *   jmpto  JmpTo2_LoadPLC
     * </pre>
     */
    @Test
    public void testDEZRoutine4_TracksMinXBeforeThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x200);
        events.update(0, 0);
        assertEquals(0x200, cam.getMinX(), "Routine 4 should set minX to camera X");
        assertEquals(4, events.getEventRoutine(), "Should stay at routine 4 when camera X < $300");
    }

    @Test
    public void testDEZRoutine4_AdvancesAtThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x300);
        events.update(0, 0);
        assertEquals(6, events.getEventRoutine(), "Should advance to routine 6 when camera X >= $300");
    }

    /**
     * Routine 6: Lock arena at camera X >= $680.
     * ROM reference: LevEvents_DEZ_Routine4 (s2.asm:21710-21720)
     * <pre>
     *   move.w (Camera_X_pos).w,(Camera_Min_X_pos).w  ; lock left to camera
     *   move.w #$680,d0
     *   cmp.w  (Camera_X_pos).w,d0
     *   bhi.s  +                                       ; skip if X < $680
     *   addq.b #2,(Dynamic_Resize_Routine).w
     *   move.w d0,(Camera_Min_X_pos).w                 ; lock left at $680
     *   addi.w #$C0,d0                                 ; $680 + $C0 = $740
     *   move.w d0,(Camera_Max_X_pos).w                 ; lock right at $740
     * </pre>
     */
    @Test
    public void testDEZRoutine6_TracksMinXBeforeThreshold() {
        events.setEventRoutine(6);
        cam.setX((short) 0x500);
        events.update(0, 0);
        assertEquals(0x500, cam.getMinX(), "Routine 6 should set minX to camera X before threshold");
        assertEquals(6, events.getEventRoutine(), "Should stay at routine 6 when camera X < $680");
    }

    @Test
    public void testDEZRoutine6_LocksArenaAtThreshold() {
        events.setEventRoutine(6);
        cam.setX((short) 0x680);
        events.update(0, 0);
        assertEquals(8, events.getEventRoutine(), "Should advance to routine 8 when camera X >= $680");
        assertEquals((short) 0x680, cam.getMinX(), "Arena left boundary should be locked at $680");
        assertEquals((short) 0x740, cam.getMaxX(), "Arena right boundary should be locked at $740 ($680+$C0)");
    }

    @Test
    public void testDEZRoutine6_LocksArenaAboveThreshold() {
        events.setEventRoutine(6);
        cam.setX((short) 0x700);
        events.update(0, 0);
        assertEquals(8, events.getEventRoutine(), "Should advance to routine 8");
        assertEquals((short) 0x680, cam.getMinX(), "Arena left boundary should be $680 (not camera X)");
        assertEquals((short) 0x740, cam.getMaxX(), "Arena right boundary should be $740");
    }

    /**
     * Routine 8: No-op while final boss is active.
     * ROM reference: LevEvents_DEZ_Routine5 (s2.asm:21723-21724)
     * <pre>
     *   LevEvents_DEZ_Routine5:
     *     rts
     * </pre>
     */
    @Test
    public void testDEZRoutine8_NoOp() {
        events.setEventRoutine(8);
        cam.setX((short) 0x700);
        short minXBefore = cam.getMinX();
        short maxXBefore = cam.getMaxX();
        events.update(0, 0);
        assertEquals(8, events.getEventRoutine(), "Routine 8 should not change the event routine");
        assertEquals(minXBefore, cam.getMinX(), "Routine 8 should not change minX");
        assertEquals(maxXBefore, cam.getMaxX(), "Routine 8 should not change maxX");
    }

    /**
     * Verify the full DEZ event sequence progresses correctly through all routines.
     */
    @Test
    public void testDEZFullSequence() {
        // Start at routine 0
        assertEquals(0, events.getEventRoutine());

        // Move camera to trigger routine 0 -> 2
        cam.setX((short) 0x140);
        events.update(0, 0);
        assertEquals(2, events.getEventRoutine(), "After trigger at $140, should be routine 2");

        // Routine 2 is a no-op (boss advances it externally)
        events.update(0, 1);
        assertEquals(2, events.getEventRoutine(), "Routine 2 should not self-advance");

        // Externally advance to routine 4 (as boss defeat would)
        events.setEventRoutine(4);

        // Routine 4: below $300 threshold
        cam.setX((short) 0x250);
        events.update(0, 2);
        assertEquals(4, events.getEventRoutine());
        assertEquals((short) 0x250, cam.getMinX(), "MinX should track camera at $250");

        // Routine 4: at $300 threshold
        cam.setX((short) 0x300);
        events.update(0, 3);
        assertEquals(6, events.getEventRoutine(), "After $300 trigger, should be routine 6");

        // Routine 6: below $680 threshold
        cam.setX((short) 0x600);
        events.update(0, 4);
        assertEquals(6, events.getEventRoutine());

        // Routine 6: at $680 threshold
        cam.setX((short) 0x680);
        events.update(0, 5);
        assertEquals(8, events.getEventRoutine(), "After $680 trigger, should be routine 8");
        assertEquals((short) 0x680, cam.getMinX(), "Arena locked at $680");
        assertEquals((short) 0x740, cam.getMaxX(), "Arena right at $740");
    }
}


