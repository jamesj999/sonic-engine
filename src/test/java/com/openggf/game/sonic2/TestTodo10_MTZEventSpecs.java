package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.events.Sonic2MTZEvents;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec tests for Metropolis Zone (MTZ) level events.
 * MTZ has two separate event handlers: one for Acts 1-2 (no events) and one for Act 3 (boss).
 *
 * <p>ROM reference:
 * <ul>
 *   <li>LevEvents_MTZ (s2.asm:20471-20472): Acts 1/2 - immediate RTS (no events)</li>
 *   <li>LevEvents_MTZ3 (s2.asm:20476-20556): Act 3 - 5 routines for boss arena</li>
 * </ul>
 *
 * MTZ3 Routines (0-8, incremented by 2):
 * <ol start="0">
 *   <li>Routine 0: Wait for camera X >= $2530, set max Y to $450</li>
 *   <li>Routine 2: Wait for camera X >= $2980, lock left boundary, set max Y to $400</li>
 *   <li>Routine 4: Wait for camera X >= $2A80, lock arena at $2AB0, load boss PLC</li>
 *   <li>Routine 6: Lock min Y at $400, wait for boss spawn delay $5A, spawn MTZ boss</li>
 *   <li>Routine 8: Track camera position (boss active)</li>
 * </ol>
 *
 * <p>Production code: {@link Sonic2MTZEvents}
 */
public class TestTodo10_MTZEventSpecs {

    private Sonic2MTZEvents events;
    private Camera cam;

    static class TestableSidekick extends AbstractPlayableSprite {
        TestableSidekick() {
            super("tails_p2", (short) 0, (short) 0);
        }

        @Override
        public void draw() {}

        @Override
        public void defineSpeeds() {
            runHeight = 38;
            rollHeight = 28;
            standXRadius = 9;
            standYRadius = 19;
            rollXRadius = 7;
            rollYRadius = 14;
            setWidth(18);
            setHeight(runHeight);
            setDirection(Direction.RIGHT);
        }

        @Override
        protected void createSensorLines() {}
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(services);
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

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        cam = GameServices.camera();
        setConstructionContext(new TestObjectServices());
        events = new Sonic2MTZEvents();
        events.init(0);
    }

    @AfterEach
    public void tearDown() {
        clearConstructionContext();
        SessionManager.clear();
    }

    /**
     * MTZ Acts 1 and 2 have no events (immediate RTS).
     * ROM reference: LevEvents_MTZ (s2.asm:20471-20472)
     * <pre>
     *   LevEvents_MTZ:
     *     rts
     * </pre>
     */
    @Test
    public void testMTZAct1_NoEvents() {
        cam.setX((short) 0x2530);  // Would trigger routine 0 in Act 3
        events.update(0, 0);  // act 0 = Act 1
        assertEquals(0, events.getEventRoutine(), "Act 1 should not advance routine");
    }

    @Test
    public void testMTZAct2_NoEvents() {
        cam.setX((short) 0x2530);
        events.update(1, 0);  // act 1 = Act 2
        assertEquals(0, events.getEventRoutine(), "Act 2 should not advance routine");
    }

    /**
     * MTZ3 Routine 0: Set initial Y boundary when camera reaches X >= $2530.
     * ROM reference: LevEvents_MTZ3_Routine1 (s2.asm:20491-20499)
     * <pre>
     *   cmpi.w #$2530,(Camera_X_pos).w
     *   blo.s  +
     *   move.w #$500,(Camera_Max_Y_pos).w            ; immediate Y max
     *   move.w #$450,(Camera_Max_Y_pos_target).w     ; target Y max
     *   move.w #$450,(Tails_Max_Y_pos).w
     *   addq.b #2,(Dynamic_Resize_Routine).w
     * </pre>
     */
    @Test
    public void testMTZ3Routine0_DoesNotAdvanceBelowThreshold() {
        cam.setX((short) 0x2000);
        events.update(2, 0);  // act 2 = Act 3
        assertEquals(0, events.getEventRoutine(), "Should stay at routine 0 when camera X < $2530");
    }

    @Test
    public void testMTZ3Routine0_AdvancesAndSetsYBounds() {
        cam.setX((short) 0x2530);
        events.update(2, 0);
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when camera X >= $2530");
        assertEquals((short) 0x500, cam.getMaxY(), "Camera_Max_Y_pos should be $500");
        assertEquals((short) 0x450, cam.getMaxYTarget(), "Camera_Max_Y_pos_target should be $450");
    }

    /**
     * MTZ3 Routine 2: Lock left boundary and lower max Y at X >= $2980.
     * ROM reference: LevEvents_MTZ3_Routine2 (s2.asm:20502-20511)
     * <pre>
     *   cmpi.w #$2980,(Camera_X_pos).w
     *   blo.s  +
     *   move.w (Camera_X_pos).w,(Camera_Min_X_pos).w   ; lock left
     *   move.w (Camera_X_pos).w,(Tails_Min_X_pos).w
     *   move.w #$400,(Camera_Max_Y_pos_target).w        ; new Y target
     *   move.w #$400,(Tails_Max_Y_pos).w
     *   addq.b #2,(Dynamic_Resize_Routine).w
     * </pre>
     */
    @Test
    public void testMTZ3Routine2_DoesNotAdvanceBelowThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x2900);
        events.update(2, 0);
        assertEquals(2, events.getEventRoutine(), "Should stay at routine 2 when camera X < $2980");
    }

    @Test
    public void testMTZ3Routine2_LocksLeftAndLowersY() {
        events.setEventRoutine(2);
        cam.setX((short) 0x2980);
        events.update(2, 0);
        assertEquals(4, events.getEventRoutine(), "Should advance to routine 4 when camera X >= $2980");
        assertEquals((short) 0x2980, cam.getMinX(), "MinX should be locked to camera X ($2980)");
        assertEquals((short) 0x400, cam.getMaxYTarget(), "Camera_Max_Y_pos_target should be $400");
    }

    /**
     * MTZ3 Routine 4: Lock boss arena at $2AB0 and load boss PLC.
     * ROM reference: LevEvents_MTZ3_Routine3 (s2.asm:20514-20529)
     * <pre>
     *   cmpi.w #$2A80,(Camera_X_pos).w
     *   blo.s  +
     *   move.w #$2AB0,(Camera_Min_X_pos).w    ; lock left at $2AB0
     *   move.w #$2AB0,(Camera_Max_X_pos).w    ; lock right at $2AB0
     *   move.w #$2AB0,(Tails_Min_X_pos).w
     *   move.w #$2AB0,(Tails_Max_X_pos).w
     *   addq.b #2,(Dynamic_Resize_Routine).w
     *   move.w #MusID_FadeOut,d0              ; fade out music
     *   clr.b  (Boss_spawn_delay).w           ; reset boss timer
     *   move.b #7,(Current_Boss_ID).w         ; MTZ boss ID = 7
     *   moveq  #PLCID_MtzBoss,d0
     * </pre>
     */
    @Test
    public void testMTZ3Routine4_DoesNotAdvanceBelowThreshold() {
        events.setEventRoutine(4);
        cam.setX((short) 0x2A00);
        events.update(2, 0);
        assertEquals(4, events.getEventRoutine(), "Should stay at routine 4 when camera X < $2A80");
    }

    @Test
    public void testMTZ3Routine4_LocksArena() {
        events.setEventRoutine(4);
        cam.setX((short) 0x2A80);
        events.update(2, 0);
        assertEquals(6, events.getEventRoutine(), "Should advance to routine 6 when camera X >= $2A80");
        assertEquals((short) 0x2AB0, cam.getMinX(), "Arena minX should be locked at $2AB0");
        assertEquals((short) 0x2AB0, cam.getMaxX(), "Arena maxX should be locked at $2AB0");
    }

    /**
     * MTZ3 Routine 6: Lock min Y at $400, spawn MTZ boss after 90-frame delay.
     * ROM reference: LevEvents_MTZ3_Routine4 (s2.asm:20532-20549)
     * <pre>
     *   cmpi.w #$400,(Camera_Y_pos).w
     *   blo.s  +
     *   move.w #$400,(Camera_Min_Y_pos).w     ; lock floor at $400
     *   move.w #$400,(Tails_Min_Y_pos).w
     *   +
     *   addq.b #1,(Boss_spawn_delay).w
     *   cmpi.b #$5A,(Boss_spawn_delay).w      ; wait 90 frames
     *   blo.s  ++
     *   move.b #ObjID_MTZBoss,id(a1)          ; spawn MTZ boss (Obj54)
     *   addq.b #2,(Dynamic_Resize_Routine).w
     *   move.w #MusID_Boss,d0                 ; play boss music
     * </pre>
     */
    @Test
    public void testMTZ3Routine6_LocksMinYWhenAboveThreshold() {
        TestableSidekick tails = addCpuSidekick();
        events.setEventRoutine(6);
        cam.setY((short) 0x400);
        events.update(2, 0);
        assertEquals((short) 0x400, cam.getMinY(), "MinY should be locked at $400 when camera Y >= $400");
        assertEquals(0x400, tails.getCpuController().getMinYBound(Integer.MIN_VALUE),
                "MTZ3 should also mirror the ROM Tails_Min_Y_pos write");
        assertEquals(Integer.MIN_VALUE, tails.getCpuController().getMaxYBound(Integer.MIN_VALUE),
                "Routine 6 writes Tails_Min_Y_pos, not Tails_Max_Y_pos");
    }

    @Test
    public void testMTZ3Routine6_DoesNotLockMinYBelowThreshold() {
        TestableSidekick tails = addCpuSidekick();
        events.setEventRoutine(6);
        cam.setY((short) 0x300);
        short minYBefore = cam.getMinY();
        events.update(2, 0);
        assertEquals(minYBefore, cam.getMinY(), "MinY should not change when camera Y < $400");
        assertEquals(Integer.MIN_VALUE, tails.getCpuController().getMinYBound(Integer.MIN_VALUE),
                "Tails_Min_Y_pos should not change before the camera reaches the threshold");
    }

    @Test
    public void testMTZ3Routine6_DoesNotSpawnBossBeforeDelay() {
        events.setEventRoutine(6);
        cam.setY((short) 0x400);
        // Step 89 frames (0x59) -- one short of the $5A threshold
        for (int i = 0; i < 0x59; i++) {
            events.update(2, i);
        }
        assertEquals(6, events.getEventRoutine(), "Should stay at routine 6 before 90 frames ($5A)");
    }

    @Test
    public void testMTZ3Routine6_SpawnsBossAtDelay() {
        events.setEventRoutine(6);
        cam.setY((short) 0x400);
        // Step exactly 90 frames ($5A)
        for (int i = 0; i < 0x5A; i++) {
            events.update(2, i);
        }
        assertEquals(8, events.getEventRoutine(), "Should advance to routine 8 after 90 frames ($5A)");
    }

    /**
     * MTZ3 Routine 8: Track camera position (boss active).
     * ROM reference: LevEvents_MTZ3_Routine5 (s2.asm:20542-20544)
     * <pre>
     *   move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
     *   rts
     * </pre>
     * The routine is a single UNCONDITIONAL assignment of Camera_Min_X_pos to the
     * current Camera_X_pos. There is no ratchet/guard and no Tails bound write.
     */
    @Test
    public void testMTZ3Routine8_TracksCamera() {
        events.setEventRoutine(8);
        cam.setX((short) 0x2B00);
        cam.setMinX((short) 0x2A00);  // old minX
        events.update(2, 0);
        assertEquals((short) 0x2B00, cam.getMinX(), "MinX should advance to camera X during boss fight");
        assertEquals(8, events.getEventRoutine(), "Should remain at routine 8");
    }

    @Test
    public void testMTZ3Routine8_MinXFollowsCameraUnconditionally() {
        // ROM Routine5 (s2.asm:20543) writes Camera_Min_X_pos = Camera_X_pos with no
        // guard, so min-X follows the camera even when the camera is behind the old
        // min-X. (In real play the camera is pinned at $2AB0 during the boss, so this
        // never actually retreats; the unit test exercises the raw assignment.)
        events.setEventRoutine(8);
        cam.setMinX((short) 0x2B00);
        cam.setX((short) 0x2A00);  // camera behind current minX
        events.update(2, 0);
        assertEquals((short) 0x2A00, cam.getMinX(),
                "MinX should follow Camera_X unconditionally (ROM has no ratchet)");
    }

    /**
     * Verify the full MTZ3 event sequence progresses correctly.
     */
    @Test
    public void testMTZ3FullSequence() {
        // Routine 0: trigger at X >= $2530
        cam.setX((short) 0x2530);
        events.update(2, 0);
        assertEquals(2, events.getEventRoutine());

        // Routine 2: trigger at X >= $2980
        cam.setX((short) 0x2980);
        events.update(2, 1);
        assertEquals(4, events.getEventRoutine());

        // Routine 4: trigger at X >= $2A80
        cam.setX((short) 0x2A80);
        events.update(2, 2);
        assertEquals(6, events.getEventRoutine());

        // Routine 6: 90-frame delay then spawn
        cam.setY((short) 0x400);
        for (int i = 0; i < 0x5A; i++) {
            events.update(2, 3 + i);
        }
        assertEquals(8, events.getEventRoutine());
    }

    private TestableSidekick addCpuSidekick() {
        TestableSidekick tails = new TestableSidekick();
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails, null));
        GameServices.sprites().addSprite(tails);
        return tails;
    }
}


