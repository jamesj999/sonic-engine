package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.objects.Sonic1ElevatorObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the SLZ3 credits demo trace divergence at frame 500.
 *
 * <p>ROM behavior: when Sonic jumps off an SLZ Elevator (object 0x59) while
 * the elevator is moving, the ROM applies both
 * <ol>
 *   <li>{@code Sonic_Jump}'s {@code addq.w #5, obY(a0)} rolling-radius adjust
 *       (docs/s1disasm/_incObj/01 Sonic.asm:1166), which lowers Sonic's
 *       centre y by 5 to align his now-rolling sprite with the floor; AND</li>
 *   <li>The elevator's continued-riding pull-up via
 *       {@code MvSonicOnPtfm2} (docs/s1disasm/_incObj/15 Swinging Platforms.asm:177-194),
 *       called from {@code Elev_Action} (docs/s1disasm/_incObj/59 SLZ Elevators.asm:84-101)
 *       AFTER {@code ExitPlatform} has cleared Sonic's on-object flag — i.e.
 *       the carry still applies on the same frame Sonic launches.</li>
 * </ol>
 *
 * <p>The bug: the engine's elevator class did not opt into the existing
 * {@code SolidObjectProvider.carriesAirborneRiderAfterExitPlatform} hook
 * (already used by Sonic 1 Obj52 Moving Block, which has the structurally
 * identical {@code ExitPlatform} → move → {@code MvSonicOnPtfm2} sequence).
 * As a result the engine applied only the +5 jump adjust, leaving Sonic's
 * y a couple of pixels below ROM whenever the elevator moved up at the same
 * time as the jump.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1JumpFromElevator {

    // Registry indices (gameplay progression order: GHZ=0, MZ=1, SYZ=2, LZ=3,
    // SLZ=4, SBZ=5), NOT the ROM zone IDs in Sonic1Constants. SharedLevel.load
    // takes the registry index. See Sonic1ZoneRegistry constructor and
    // Sonic1CreditsDemoData.DEMO_ZONE.
    private static final int ZONE_SLZ_REGISTRY_INDEX = 4;
    private static final int ACT_3 = 2;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SLZ_REGISTRY_INDEX, ACT_3);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.reset(fixture.camera().getX());
        }
    }

    /**
     * Reproduces the SLZ3 credits demo frame-500 divergence.
     *
     * <p>Setup mirrors the ROM scenario: Sonic stands on a moving-up SLZ
     * Elevator for a handful of frames so the elevator is in routine 4
     * (Elev_Action / actionType 2 / decelerating) and traveling upward at a
     * steady pace, then we press jump for a single frame.
     *
     * <p>Expected: the post-jump Sonic centre y matches the ROM-derived
     * value where BOTH the {@code +5} rolling adjust AND the elevator
     * pull-up are applied. With the bug present, the engine only applies
     * the {@code +5} adjust, leaving Sonic ~2 pixels too high.
     */
    @Test
    public void jumpOffMovingElevatorAppliesPostExitPullUp() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager required for this test");

        // Spawn an SLZ Elevator under the camera. Subtype 0 maps via Elev_Var2
        // to {distance=$10, type=1}; type 1 is the trigger that promotes
        // actionType to 2 (move up) once Sonic stands on the platform.
        int spawnX = fixture.camera().getX() + 160;
        int spawnY = fixture.camera().getY() + 96;
        ObjectSpawn spawn = new ObjectSpawn(spawnX, spawnY,
                com.openggf.game.sonic1.constants.Sonic1ObjectIds.SLZ_ELEVATOR,
                0x00, 0, false, 0);
        Sonic1ElevatorObjectInstance elevator = new Sonic1ElevatorObjectInstance(spawn);
        objectManager.addDynamicObject(elevator);

        // Place Sonic above the elevator with a small drop so the elevator
        // catches him via the normal solid-contact path.
        fixture.sprite().setCentreX((short) spawnX);
        fixture.sprite().setCentreY((short) (spawnY - 32));
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setAir(true);
        fixture.camera().updatePosition(true);

        // Settle Sonic on the elevator and let the elevator transition into
        // the moving-up Elev_Action routine.
        boolean rideEstablished = false;
        for (int i = 0; i < 240; i++) {
            fixture.stepFrame(false, false, false, false, false);
            ObjectInstance riding = objectManager.getRidingObject(fixture.sprite());
            if (riding == elevator
                    && !fixture.sprite().getAir()
                    && fixture.sprite().isOnObject()) {
                rideEstablished = true;
                // Run a few more frames so the elevator is mid-travel and
                // moving up at non-zero speed before we jump.
                for (int j = 0; j < 16; j++) {
                    fixture.stepFrame(false, false, false, false, false);
                }
                break;
            }
        }
        assertTrue(rideEstablished, "Sonic failed to settle on the SLZ elevator");

        int elevatorYBeforeJumpFrame = elevator.getY();

        // Capture the pre-jump centre y before the jump frame. This is the
        // baseline for computing the expected post-jump value.
        short preJumpCentreY = fixture.sprite().getCentreY();

        // Press jump for a single frame, exactly mirroring the credits demo
        // frame-500 input where Sonic was riding and the jump button got hit.
        fixture.stepFrame(false, false, false, false, true);

        int elevatorYAfterJumpFrame = elevator.getY();
        int elevatorDelta = elevatorYAfterJumpFrame - elevatorYBeforeJumpFrame;

        // ROM expectations:
        //  - Sonic_Jump applies +5 to obY (rolling-radius adjust).
        //  - Elev_Action calls MvSonicOnPtfm2 AFTER the elevator moves, which
        //    rewrites obY to (elevatorY - 9 - obHeight) where obHeight is now
        //    the rolling height (0xE = 14). The net effect: Sonic follows the
        //    elevator's NEW position with a fixed 23-pixel offset above its
        //    centre, regardless of his pre-jump centre y.
        //
        //  Engine equivalent in applyRidingCarry(): sonic.centreY ends at
        //    elevatorYAfter + offsetY - groundHalfHeight - sonic.yRadius
        //  With offsetY=0, groundHalfHeight=9, yRadius=14 (rolling) this is
        //    elevatorYAfter - 9 - 14 = elevatorYAfter - 23.
        int expectedCentreY = elevatorYAfterJumpFrame - 9 - 14;

        // Sanity: the elevator should be moving up (negative delta) on the
        // jump frame, otherwise this scenario doesn't exercise the bug.
        assertTrue(elevatorDelta < 0,
                "Test setup invariant: elevator should be moving up on the jump frame, but delta="
                        + elevatorDelta);

        // ROM Elev_Action / MvSonicOnPtfm2 leaves Sonic airborne — only the
        // y_pos is rewritten to track the elevator. Without the
        // continued-riding carry, the engine re-lands Sonic on the post-move
        // elevator surface (rolling yRadius=14) and zeroes y_speed, which is
        // a separate symptom of the same missing pull-up.
        assertTrue(fixture.sprite().getAir(),
                "Sonic should remain airborne after jumping — ROM Elev_Action calls "
                        + "MvSonicOnPtfm2 but does not clear in_air. "
                        + "Re-landing here means the carry didn't run, and the engine "
                        + "fell into the regular new-contact resolution path.");

        assertEquals(expectedCentreY, fixture.sprite().getCentreY(),
                "Post-jump centre y must include the elevator's continued-riding pull-up "
                        + "(MvSonicOnPtfm2). preJumpY=" + preJumpCentreY
                        + ", elevatorYBefore=" + elevatorYBeforeJumpFrame
                        + ", elevatorYAfter=" + elevatorYAfterJumpFrame
                        + ", elevatorDelta=" + elevatorDelta);
    }
}
