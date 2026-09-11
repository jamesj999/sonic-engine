package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.events.Sonic2SCZEvents;
import com.openggf.game.sonic2.scroll.SwScrlScz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec tests for Sky Chase Zone (SCZ) level events.
 * SCZ is an auto-scrolling zone where the Tornado plane carries Sonic.
 * The event system controls Tornado velocity and camera boundaries.
 *
 * <p>ROM reference: LevEvents_SCZ (s2.asm:21803-21861)
 *
 * <p><b>Important:</b> SCZ level events are NOT in {@link Sonic2SCZEvents} (which is empty).
 * They are implemented in {@link SwScrlScz#advanceCameraForFrame} because the
 * Tornado velocity and camera auto-scroll are gameplay state owned by the
 * scroll handler.
 *
 * SCZ Act 1 Routines (managed by SwScrlScz):
 * <ol start="0">
 *   <li>Routine 0: Initialize Tornado velocity to (1, 0) - move right</li>
 *   <li>Routine 1: At X >= $1180, change velocity to (-1, 1) - descend</li>
 *   <li>Routine 2: At Y >= $500, change velocity to (1, 0) - move right again</li>
 *   <li>Routine 3: At X >= $1400, stop all velocity (0, 0) - end of stage</li>
 *   <li>Routine 4: No-op (stage complete)</li>
 * </ol>
 * SCZ Act 2: No events (immediate RTS).
 *
 * <p>Production code: {@link SwScrlScz}, {@link Sonic2SCZEvents}
 */
public class TestTodo11_SCZEventSpecs {

    private SwScrlScz scrollHandler;
    private Camera cam;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        cam = GameServices.camera();
        scrollHandler = new SwScrlScz();
        scrollHandler.init();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    /**
     * Sonic2SCZEvents.update() is empty -- all logic is in SwScrlScz.
     * Verify the event handler itself is a no-op.
     */
    @Test
    public void testSCZEventsHandlerIsNoOp() {
        Sonic2SCZEvents events = new Sonic2SCZEvents();
        events.init(0);
        cam.setX((short) 0x2000);
        events.update(0, 0);
        assertEquals(0, events.getEventRoutine(), "Sonic2SCZEvents should not change routine");
    }

    /**
     * SCZ Act 2 has no events in SwScrlScz either (actId != 0 skips events).
     */
    @Test
    public void testSCZAct2_NoEvents() {
        // Act 2 (actId=1): updateLevelEvents is skipped
        scrollHandler.advanceCameraForFrame(cam, 1);
        assertEquals(0, scrollHandler.getTornadoVelocityX(), "Tornado X velocity should remain 0 for Act 2");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Tornado Y velocity should remain 0 for Act 2");
    }

    /**
     * SCZ Routine 0: Set initial Tornado velocity.
     * ROM reference: LevEvents_SCZ_Routine1 (s2.asm:21820-21824)
     * <pre>
     *   move.w #1,(Tornado_Velocity_X).w    ; move right at speed 1
     *   move.w #0,(Tornado_Velocity_Y).w    ; no vertical movement
     *   addq.b #2,(Dynamic_Resize_Routine).w
     *   rts
     * </pre>
     */
    @Test
    public void testSCZRoutine0_InitialVelocity() {
        // First update triggers routine 0 -> sets velocity and advances
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(1, scrollHandler.getTornadoVelocityX(), "Initial Tornado X velocity should be 1 (rightward)");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Initial Tornado Y velocity should be 0");
    }

    /**
     * SCZ Routine 1 (fly right): At X >= $1180, begin descent.
     * ROM reference: LevEvents_SCZ_Routine2 (s2.asm:21827-21835)
     * <pre>
     *   cmpi.w #$1180,(Camera_X_pos).w
     *   blo.s  +
     *   move.w #-1,(Tornado_Velocity_X).w   ; move left
     *   move.w #1,(Tornado_Velocity_Y).w    ; move down
     *   move.w #$500,(Camera_Max_Y_pos_target).w  ; allow scrolling down
     *   addq.b #2,(Dynamic_Resize_Routine).w
     * </pre>
     */
    @Test
    public void testSCZRoutine1_DoesNotTriggerBelowThreshold() {
        // Initialize (routine 0 -> 1)
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Camera still below $1180 -- should stay at routine 1
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(1, scrollHandler.getTornadoVelocityX(), "Should still fly rightward below $1180");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Should have no vertical movement below $1180");
    }

    @Test
    public void testSCZRoutine1_BeginDescentAtThreshold() {
        // Initialize (routine 0 -> 1)
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Move camera to threshold
        cam.setX((short) 0x1180);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(-1, scrollHandler.getTornadoVelocityX(), "Tornado X velocity should be -1 (leftward) at $1180");
        assertEquals(1, scrollHandler.getTornadoVelocityY(), "Tornado Y velocity should be 1 (downward) at $1180");
        assertEquals((short) 0x500, cam.getMaxYTarget(), "Max Y target should be set to $500");
    }

    /**
     * SCZ Routine 2 (descend): At Y >= $500, resume rightward movement.
     * ROM reference: LevEvents_SCZ_Routine3 (s2.asm:21838-21845)
     * <pre>
     *   cmpi.w #$500,(Camera_Y_pos).w
     *   blo.s  +
     *   move.w #1,(Tornado_Velocity_X).w    ; move right
     *   move.w #0,(Tornado_Velocity_Y).w    ; stop vertical
     *   addq.b #2,(Dynamic_Resize_Routine).w
     * </pre>
     */
    @Test
    public void testSCZRoutine2_ResumeRightwardAtThreshold() {
        // Initialize (routine 0 -> 1)
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Trigger descent (routine 1 -> 2)
        cam.setX((short) 0x1180);
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Now at routine 2, set Y to threshold
        cam.setY((short) 0x500);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(1, scrollHandler.getTornadoVelocityX(), "Tornado X velocity should be 1 (rightward) at Y >= $500");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Tornado Y velocity should be 0 (stopped) at Y >= $500");
    }

    /**
     * SCZ Routine 3 (resume right): At X >= $1400, stop Tornado.
     * ROM reference: LevEvents_SCZ_Routine4 (s2.asm:21848-21856)
     * <pre>
     *   cmpi.w #$1400,(Camera_X_pos).w
     *   blo.s  LevEvents_SCZ_RoutineNull
     *   move.w #0,(Tornado_Velocity_X).w       ; stop horizontal
     *   move.w #0,(Tornado_Velocity_Y).w       ; stop vertical
     *   addq.b #2,(Dynamic_Resize_Routine).w
     * </pre>
     */
    @Test
    public void testSCZRoutine3_StopTornadoAtThreshold() {
        // Initialize (routine 0 -> 1)
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Trigger descent (routine 1 -> 2)
        cam.setX((short) 0x1180);
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Resume rightward (routine 2 -> 3)
        cam.setY((short) 0x500);
        scrollHandler.advanceCameraForFrame(cam, 0);
        // Stop tornado (routine 3 -> 4)
        cam.setX((short) 0x1400);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(0, scrollHandler.getTornadoVelocityX(), "Tornado X velocity should be 0 (stopped) at X >= $1400");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Tornado Y velocity should be 0 (stopped) at X >= $1400");
    }

    /**
     * After routine 4 (null), no further velocity changes should occur.
     */
    @Test
    public void testSCZRoutine4_NoOp() {
        // Progress through all routines
        scrollHandler.advanceCameraForFrame(cam, 0);           // 0 -> 1
        cam.setX((short) 0x1180);
        scrollHandler.advanceCameraForFrame(cam, 0);  // 1 -> 2
        cam.setY((short) 0x500);
        scrollHandler.advanceCameraForFrame(cam, 0);  // 2 -> 3
        cam.setX((short) 0x1400);
        scrollHandler.advanceCameraForFrame(cam, 0);  // 3 -> 4

        // Now at routine 4 (null) -- further updates should not change velocity
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(0, scrollHandler.getTornadoVelocityX(), "Velocity should remain 0 after stage complete");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Velocity should remain 0 after stage complete");
    }

    /**
     * Verify the complete SCZ auto-scroll flight path from disassembly.
     * The Tornado follows a specific path:
     * 1. Fly right from start (vel X=1, Y=0)
     * 2. At X=$1180: reverse and descend (vel X=-1, Y=1, max Y=$500)
     * 3. At Y=$500: fly right again (vel X=1, Y=0)
     * 4. At X=$1400: stop (vel X=0, Y=0)
     */
    @Test
    public void testSCZFlightPath() {
        // Phase 0 -> 1: initialize velocity
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(1, scrollHandler.getTornadoVelocityX(), "Phase 1 X velocity");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Phase 1 Y velocity");

        // Phase 1 -> 2: descent at $1180
        cam.setX((short) 0x1180);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(-1, scrollHandler.getTornadoVelocityX(), "Phase 2 X velocity");
        assertEquals(1, scrollHandler.getTornadoVelocityY(), "Phase 2 Y velocity");

        // Phase 2 -> 3: rightward again at Y=$500
        cam.setY((short) 0x500);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(1, scrollHandler.getTornadoVelocityX(), "Phase 3 X velocity");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Phase 3 Y velocity");

        // Phase 3 -> 4: stop at $1400
        cam.setX((short) 0x1400);
        scrollHandler.advanceCameraForFrame(cam, 0);
        assertEquals(0, scrollHandler.getTornadoVelocityX(), "Phase 4 X velocity");
        assertEquals(0, scrollHandler.getTornadoVelocityY(), "Phase 4 Y velocity");
    }
}


