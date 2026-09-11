package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless integration tests verifying that the S3K big ring (Obj_SSEntryRing)
 * is NOT interactable during its formation animation in a real AIZ2 scenario.
 * <p>
 * Setup: AIZ Act 2, post-fire section. A red spring at approximately (566, 1223)
 * launches Sonic upward past a big ring (near Y â‰ˆ 803). The ring should NOT trigger
 * during Sonic's ascent (it's still forming), but SHOULD trigger on his descent.
 * <p>
 * Test 1: Sonic spawns directly on the spring. Guards against the ring triggering
 * too soon after it enters the spawn/animation window.
 * <p>
 * Test 2: Sonic spawns nearby (x=518), idles for 2 seconds, then moves to the
 * spring. Guards against the ring's formation advancing while it is off-screen
 * (the ROM's Obj_WaitOffscreen gate). If the animation burned through off-screen,
 * the ring would already be interactable on Sonic's first pass, which is wrong.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz2BigRingFormation {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;
    private static final int FPS = 60;

    // Spring position â€” Sonic placed here should land on the red spring
    private static final short SPRING_X = 566;
    private static final short SPRING_Y = 1223;

    // Nearby position for the delayed-approach test
    private static final short NEARBY_X = 518;

    // Threshold: Sonic must rise above this Y to prove the spring launched him
    // past the ring. Y decreases upward, so "above" means Y <= this value.
    private static final int RING_Y_THRESHOLD = 803;

    // Timeout for each test phase
    private static final int LAUNCH_TIMEOUT_FRAMES = 5 * FPS;   // 5 seconds to reach peak
    private static final int CAPTURE_TIMEOUT_FRAMES = 5 * FPS;  // 5 seconds to descend and trigger
    private static final int IDLE_WAIT_FRAMES = 2 * FPS;        // 2 seconds for delayed approach

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_2);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        // Fresh object spawns per test
        GameServices.level().getObjectManager().reset(0);
    }

    // ---------------------------------------------------------------
    // Test 1: Spawn on the spring â€” immediate launch
    // ---------------------------------------------------------------

    /**
     * Sonic spawns on the red spring, gets launched upward past the big ring,
     * and must descend into it to be captured. The ring's 40-frame formation
     * animation should prevent early triggering on ascent.
     */
    @Test
    public void bigRingNotTriggeredDuringFormation_immediateSpring() {
        placeOnSpring(SPRING_X, SPRING_Y);
        assertSpringLaunchAndRingCapture("immediateSpring");
    }

    // ---------------------------------------------------------------
    // Test 2: Spawn nearby, idle 2 seconds, then move to spring
    // ---------------------------------------------------------------

    /**
     * Sonic spawns slightly left of the spring (x=518) and idles for 2 seconds.
     * During this time, the big ring may be in the ObjectManager's spawn window
     * but should NOT advance its formation animation because it's off-screen
     * (ROM's Obj_WaitOffscreen gate).
     * <p>
     * After the idle period, Sonic is teleported to the spring. The ring should
     * still need its full formation animation before becoming interactable.
     */
    @Test
    public void bigRingFormationDoesNotAdvanceOffScreen_delayedApproach() {
        // Phase 1: Place Sonic nearby and idle â€” ring might spawn but shouldn't animate
        placeOnSpring(NEARBY_X, SPRING_Y);
        for (int i = 0; i < IDLE_WAIT_FRAMES; i++) {
            fixture.stepIdleFrames(1);
        }

        // Phase 2: Teleport to the spring position
        placeOnSpring(SPRING_X, SPRING_Y);
        assertSpringLaunchAndRingCapture("delayedApproach");
    }

    // ---------------------------------------------------------------
    // Shared assertion
    // ---------------------------------------------------------------

    /**
     * Steps frames until Sonic rises past {@link #RING_Y_THRESHOLD},
     * then continues stepping until the big ring captures him
     * ({@code isObjectControlled()} becomes true during descent).
     * Fails if either condition is not met within the timeout.
     */
    private void assertSpringLaunchAndRingCapture(String testLabel) {
        boolean rosePastRing = false;
        boolean startedDescending = false;
        int minY = sprite.getY();
        int minYFrame = -1;

        // Phase A: Rise past the ring
        for (int frame = 0; frame < LAUNCH_TIMEOUT_FRAMES; frame++) {
            fixture.stepIdleFrames(1);

            int y = sprite.getY();
            if (y < minY) {
                minY = y;
                minYFrame = frame;
            }
            if (y <= RING_Y_THRESHOLD) {
                rosePastRing = true;
            }
            // Detect descent start (Y increasing after having risen)
            if (rosePastRing && y > minY + 4) {
                startedDescending = true;
                break;
            }

            if (sprite.getDead()) {
                fail(testLabel + ": Sonic died during ascent at frame " + frame
                        + " y=" + y);
            }
        }

        assertTrue(rosePastRing, testLabel + ": Sonic should rise past Y=" + RING_Y_THRESHOLD
                        + " (proving spring launched him). minY=" + minY
                        + " minYFrame=" + minYFrame
                        + " finalY=" + sprite.getY());

        // Phase B: Descend into the ring â€” expect capture (objectControlled)
        boolean captured = false;
        for (int frame = 0; frame < CAPTURE_TIMEOUT_FRAMES; frame++) {
            fixture.stepIdleFrames(1);

            if (sprite.isObjectControlled() || sprite.isHidden()) {
                captured = true;
                break;
            }

            if (sprite.getDead()) {
                fail(testLabel + ": Sonic died during descent at frame " + frame
                        + " y=" + sprite.getY());
            }
        }

        assertTrue(captured, testLabel + ": Ring should capture Sonic on descent after formation"
                        + " (isObjectControlled=" + sprite.isObjectControlled()
                        + " isHidden=" + sprite.isHidden()
                        + " minY=" + minY
                        + " finalY=" + sprite.getY() + ")");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void placeOnSpring(short x, short y) {
        sprite.setX(x);
        sprite.setY(y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAngle((byte) 0);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(true);  // Start airborne so Sonic drops onto the spring
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setHidden(false);

        Camera camera = fixture.camera();
        camera.setFrozen(false);
        camera.updatePosition(true);
    }
}


