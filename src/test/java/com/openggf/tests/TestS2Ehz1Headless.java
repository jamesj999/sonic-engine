package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.ResultsScreenObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grouped headless tests for Sonic 2 EHZ Act 1.
 *
 * Level data is loaded once via {@code @BeforeAll}; sprite, camera, and game
 * state are reset per test via {@link HeadlessTestFixture}.
 *
 * Merged from:
 * <ul>
 *   <li>TestHeadlessWallCollision</li>
 *   <li>TestHeadlessStaticObjectPushStability</li>
 *   <li>TestSidekickCpuController</li>
 *   <li>TestSignpostWalkOff</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Ehz1Headless {
    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_EHZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;
    private Sonic sonic;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        sonic = sprite;
    }

    // ========== From TestHeadlessWallCollision ==========

    @Test
    public void testGroundCollisionAndWalking() throws Exception {
        // Position at EHZ1 level start (center coordinates, matching loadCurrentLevel behavior).
        // The original test relied on loadZoneAndAct placing the sprite here with air=false.
        sprite.setCentreX((short) 96);
        sprite.setCentreY((short) 655);
        sprite.setAir(false);

        // Verify initial state
        assertFalse(sprite.getAir(), "Sprite should start on ground after level load");

        // Let Sonic settle onto the ground (5 frames with no input)
        for (int frame = 0; frame < 5; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        // Verify sprite is still on ground after settling
        assertFalse(sprite.getAir(), "Sprite should remain on ground after settling frames");

        // Record position before walking
        short initialX = sprite.getX();

        // Walk left for 5 frames
        for (int frame = 0; frame < 5; frame++) {
            fixture.stepFrame(false, false, true, false, false);
        }

        // Verify sprite stayed on ground while walking
        assertFalse(sprite.getAir(), "Sprite should remain on ground while walking left");

        // Verify X position decreased (moved left)
        short finalX = sprite.getX();
        assertTrue(finalX < initialX, "Sprite should have moved left (X decreased). Initial=" + initialX + ", Final=" + finalX);

        // Verify gSpeed is negative (moving left)
        assertTrue(sprite.getGSpeed() < 0, "Ground speed should be negative when walking left");
    }

    // ========== From TestHeadlessStaticObjectPushStability ==========

    private static final int TESTBED_X = 0x0180;
    private static final int TESTBED_FLOOR_Y = 0x0140;
    private static final int TESTBED_SPAWN_Y = TESTBED_FLOOR_Y - 0x60;
    private static final int LANDING_TIMEOUT_FRAMES = 120;
    private static final int CONTACT_TIMEOUT_FRAMES = 90;
    private static final int CONTACT_WARMUP_FRAMES = 10;
    private static final int STABILITY_FRAMES = 60;
    private static final int FLOOR_HALF_WIDTH = 0x90;
    private static final int FLOOR_HALF_HEIGHT = 0x10;
    private static final int WALL_HALF_WIDTH = 0x18;
    private static final int WALL_HALF_HEIGHT = 0x18;
    private static final int OBJECT_GAP = 4;
    private static final int PUSH_START_OFFSET = 0x30;

    @Test
    public void testNoJitterWhenPushingStaticObjectToRight() {
        assertNoPushJitter(true);
    }

    @Test
    public void testNoJitterWhenPushingStaticObjectToLeft() {
        assertNoPushJitter(false);
    }

    private void assertNoPushJitter(boolean pushRight) {
        GameServices.level().getObjectManager()
                .addDynamicObject(new StaticSolidObject(
                        TESTBED_X,
                        TESTBED_FLOOR_Y,
                        new SolidObjectParams(FLOOR_HALF_WIDTH, FLOOR_HALF_HEIGHT, FLOOR_HALF_HEIGHT),
                        true));

        sprite.setCentreX((short) (TESTBED_X + (pushRight ? -PUSH_START_OFFSET : PUSH_START_OFFSET)));
        sprite.setCentreY((short) TESTBED_SPAWN_Y);
        sprite.setAir(true);

        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);

        boolean landed = false;
        for (int frame = 0; frame < LANDING_TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (!sprite.getAir()) {
                landed = true;
                break;
            }
        }
        assertTrue(landed, "Sonic should land on the static floor testbed");

        int objectX = sprite.getCentreX()
                + (pushRight ? WALL_HALF_WIDTH + OBJECT_GAP : -(WALL_HALF_WIDTH + OBJECT_GAP));
        int objectY = sprite.getCentreY();

        GameServices.level().getObjectManager()
                .addDynamicObject(new StaticSolidObject(
                        objectX,
                        objectY,
                        new SolidObjectParams(WALL_HALF_WIDTH, WALL_HALF_HEIGHT, WALL_HALF_HEIGHT),
                        false));

        boolean pressingLeft = !pushRight;
        boolean contactReached = false;
        for (int frame = 0; frame < CONTACT_TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, pressingLeft, pushRight, false);
            if (sprite.getPushing()) {
                contactReached = true;
                break;
            }
        }
        assertTrue(contactReached, "Sonic should reach side-pushing contact (" + directionName(pushRight) + ")");

        for (int frame = 0; frame < CONTACT_WARMUP_FRAMES; frame++) {
            fixture.stepFrame(false, false, pressingLeft, pushRight, false);
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int transitionCount = 0;
        Integer previousX = null;
        for (int frame = 0; frame < STABILITY_FRAMES; frame++) {
            fixture.stepFrame(false, false, pressingLeft, pushRight, false);
            int x = sprite.getX();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            if (previousX != null && previousX != x) {
                transitionCount++;
            }
            previousX = x;
            assertFalse(sprite.getAir(), "Sonic should stay grounded while pushing (" + directionName(pushRight) + ")");
        }

        assertEquals(minX, maxX, "Sonic X position should stay stable while pushing static object (" + directionName(pushRight)
                        + "), minX=" + minX + ", maxX=" + maxX + ", transitions=" + transitionCount);
        assertEquals(0, transitionCount, "Sonic X should not oscillate while pushing static object (" + directionName(pushRight) + ")");
    }

    private static String directionName(boolean pushRight) {
        return pushRight ? "toward right-side object" : "toward left-side object";
    }

    private static final class StaticSolidObject extends AbstractObjectInstance implements SolidObjectProvider {
        private final int x;
        private final int y;
        private final SolidObjectParams params;
        private final boolean topSolidOnly;

        private StaticSolidObject(int x, int y, SolidObjectParams params, boolean topSolidOnly) {
            super(new ObjectSpawn(x, y, 0xFE, 0, 0, false, y), "TestStaticSolidObject");
            this.x = x;
            this.y = y;
            this.params = params;
            this.topSolidOnly = topSolidOnly;
            setServices(com.openggf.tests.TestEnvironment.objectServices());
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }

        @Override
        public boolean isTopSolidOnly() {
            return topSolidOnly;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for headless collision tests.
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // Static object.
        }
    }

    // ========== From TestSidekickCpuController ==========

    private Tails tails;
    private SidekickCpuController controller;
    private static final String DEFAULT_TAILS_SIDEKICK_CODE = "tails_p2";

    private void createTailsForTest() {
        sprite.setX((short) 100);
        sprite.setY((short) 624);
        // Replace the default headless-fixture Tails slot instead of appending a
        // second CPU sidekick alongside the registered P2 helper.
        tails = new Tails(DEFAULT_TAILS_SIDEKICK_CODE, (short) 60, (short) 624);
        tails.setCpuControlled(true);
        controller = new SidekickCpuController(tails, sprite);
        tails.setCpuController(controller);
        GameServices.sprites().addSprite(tails);
    }

    // -- State Transition Tests --

    @Test
    public void testInitialState() {
        createTailsForTest();
        assertEquals(SidekickCpuController.State.INIT, controller.getState(), "Controller should start in INIT state");
    }

    @Test
    public void testInitTransitionsToNormal() {
        createTailsForTest();
        controller.update(0);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(), "INIT should immediately transition to NORMAL");
    }

    @Test
    public void testResetClearsState() {
        createTailsForTest();
        // Run a few updates to change state
        controller.update(0);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());

        controller.reset();
        assertEquals(SidekickCpuController.State.INIT, controller.getState(), "Reset should return to INIT");
        assertFalse(controller.getInputLeft(), "Reset should clear all inputs");
        assertFalse(controller.getInputRight());
        assertFalse(controller.getInputJump());
    }

    // -- Input Generation Tests --

    @Test
    public void testNoInputWhenCloseToTarget() {
        createTailsForTest();
        // Settle both sprites on ground
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Place Tails very close to Sonic's delayed position
        short sonicDelayedX = sonic.getCentreX(16);
        short sonicDelayedY = sonic.getCentreY(16);
        tails.setX((short) (sonicDelayedX - tails.getWidth() / 2));
        tails.setY((short) (sonicDelayedY - tails.getHeight() / 2));

        controller.update(fixture.frameCount());

        assertFalse(controller.getInputLeft(), "No left input when close to target");
        assertFalse(controller.getInputRight(), "No right input when close to target");
    }

    @Test
    public void testInputRightWhenTargetIsRight() {
        createTailsForTest();
        // Settle sprites and populate history
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Move Sonic far to the right of Tails
        // Walk Sonic right for many frames to create distance
        for (int i = 0; i < 30; i++) {
            fixture.stepFrame(false, false, false, true, false);
            // Don't move Tails so gap opens up
        }

        // Now update Tails AI - Sonic's delayed position should be to the right
        controller.update(fixture.frameCount());

        // Sonic moved right, so the delayed position (16 frames ago) should be
        // somewhat to the right of Tails
        short tailsX = tails.getCentreX();
        short targetX = sonic.getCentreX(16);

        if (targetX - tailsX > 16) {
            assertTrue(controller.getInputRight(), "Should input right when target is to the right");
            assertFalse(controller.getInputLeft(), "Should not input left when target is right");
        }
    }

    @Test
    public void testInputLeftWhenTargetIsLeft() {
        createTailsForTest();
        // Settle sprites and populate history
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Place Tails far to the right of Sonic
        tails.setX((short) (sonic.getCentreX() + 100));
        tails.setY(sonic.getY());

        controller.update(fixture.frameCount());

        assertTrue(controller.getInputLeft(), "Should input left when target is to the left");
        assertFalse(controller.getInputRight(), "Should not input right when target is left");
    }

    @Test
    public void testJumpWhenSonicIsAboveAndFarAway() {
        createTailsForTest();
        // Settle sprites and populate history
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Place Tails well below AND far from Sonic. ROM requires Sonic to be
        // >= 32px above Tails (vertical check always required). On 256-frame
        // boundaries the distance check is skipped, so any horizontal gap works.
        tails.setX((short) (sonic.getCentreX() + 200));
        tails.setY((short) (sonic.getY() + 100));
        tails.setAir(false);

        // Frame 256: (256 & 0xFF) == 0 skips distance gate, (256 & 0x3F) == 0 passes timing
        controller.update(256);

        assertTrue(controller.getInputJump(), "Should trigger AI jump when Sonic is above by >= 32px on 256-frame boundary");
    }

    @Test
    public void testNoJumpWhenSameHeightDespiteLargeGap() {
        createTailsForTest();
        // Settle sprites and populate history
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Place Tails far from Sonic horizontally (>64px gap) but at SAME HEIGHT.
        // ROM: AI jump ALWAYS requires Sonic to be >= 32px above Tails.
        // Horizontal distance alone never triggers a jump.
        tails.setX((short) (sonic.getCentreX() + 200));
        tails.setY(sonic.getY());
        tails.setAir(false);

        // Even on a 256-frame boundary (skips distance gate), vertical check fails
        controller.update(256);

        assertFalse(controller.getInputJump(), "Should NOT jump when at same height, even with large horizontal gap");
    }

    @Test
    public void testNoJumpWhenAirborne() {
        createTailsForTest();
        // Settle sprites and populate history
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Place Tails far away but already airborne
        tails.setX((short) (sonic.getCentreX() + 200));
        tails.setY(sonic.getY());
        tails.setAir(true);

        controller.update(fixture.frameCount());

        assertFalse(controller.getInputJump(), "Should not trigger jump when already airborne");
    }

    // -- Despawn / Respawn Tests --

    @Test
    public void testDespawnWhenOffScreenTooLong() {
        createTailsForTest();
        // Get to NORMAL state first
        controller.update(0);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());

        // Move Tails far off-screen and mark airborne (fell off-screen)
        // Must be airborne to avoid triggering PANIC (stuck detection) before despawn
        forceDespawn();

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(), "Should transition to SPAWNING after being off-screen for 300 frames");
    }

    @Test
    public void testRespawnWhenSonicIsGrounded() {
        createTailsForTest();
        controller.update(0);
        forceDespawn();
        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());

        // Ensure Sonic is safely grounded
        sonic.setAir(false);
        sonic.setDead(false);

        controller.update(311);
        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(), "Respawn should still wait off the 64-frame cadence");
        controller.update(320);

        // After respawn, should be in APPROACHING state
        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState(), "Should transition to APPROACHING after respawn");
        assertTrue(controller.isApproaching(), "isApproaching() should return true in APPROACHING state");
    }

    @Test
    public void testSpawningWaitsIfSonicIsDead() {
        createTailsForTest();
        controller.update(0);
        forceDespawn();
        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());

        // Set Sonic as dead
        sonic.setDead(true);
        controller.update(320);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(), "Should stay in SPAWNING while Sonic is dead");
    }

    @Test
    public void testSpawningWaitsIfSonicIsAirborne() {
        createTailsForTest();
        controller.update(0);
        forceDespawn();
        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());

        // Set Sonic as airborne
        sonic.setAir(true);
        sonic.setDead(false);
        controller.update(320);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(), "Should stay in SPAWNING while Sonic is airborne");
    }

    // -- Flying State Tests --

    @Test
    public void testFlyingStateBypassesNormalPhysics() {
        createTailsForTest();
        assertFalse(controller.isApproaching(), "isApproaching() should be false initially");

        // Get to APPROACHING state via despawn/respawn
        controller.update(0);
        forceDespawn();

        sonic.setAir(false);
        sonic.setDead(false);
        controller.update(320);

        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        assertTrue(controller.isApproaching(), "isApproaching() should return true");
    }

    @Test
    public void testFlyingToNormalWhenReachedTarget() {
        createTailsForTest();
        // Get to APPROACHING state
        controller.update(0);
        forceDespawn();

        sonic.setAir(false);
        sonic.setDead(false);
        controller.update(320);
        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());

        // Place Tails right at Sonic's effective delayed position.
        short targetX = sonic.getCentreX(16);
        short targetY = sonic.getCentreY(16);
        tails.setX((short) (targetX - tails.getWidth() / 2));
        tails.setY((short) (targetY - tails.getHeight() / 2));

        // Ensure Sonic is grounded for landing condition
        sonic.setAir(false);

        controller.update(312);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(), "Should transition from APPROACHING to NORMAL when at target and Sonic grounded");
        assertFalse(controller.isApproaching(), "isApproaching() should be false after landing");
    }

    @Test
    public void testRegisteredTailsMovesAfterFlyInLandsInNormalState() {
        AbstractPlayableSprite registeredSidekick = GameServices.sprites().getSidekicks().getFirst();
        assertInstanceOf(Tails.class, registeredSidekick, "Sonic 2 should register Tails as the default sidekick");
        tails = (Tails) registeredSidekick;
        controller = tails.getCpuController();
        assertNotNull(controller, "registered Tails should have a CPU controller");

        controller.setInitialState(SidekickCpuController.State.SPAWNING);
        tails.setCentreX((short) (sonic.getCentreX() - 0x120));
        tails.setCentreY((short) (sonic.getCentreY() - 0xC0));
        tails.setAir(true);
        sonic.setAir(false);
        sonic.setDead(false);

        for (int i = 0; i < 80 && !controller.isApproaching(); i++) {
            fixture.stepFrame(false, false, false, false, false);
            sonic.setAir(false);
            sonic.setDead(false);
        }
        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());

        tails.setCentreX(sonic.getCentreX(16));
        tails.setCentreY(sonic.getCentreY(16));
        fixture.stepFrame(false, false, false, false, false);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());

        for (int i = 0; i < 90 && tails.getAir(); i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertFalse(tails.getAir(), "registered Tails should land before checking grounded follow");

        short initialX = tails.getCentreX();
        for (int i = 0; i < 40; i++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        assertTrue(tails.getCentreX() > initialX,
                "registered S2 Tails should resume grounded follow after fly-in landing");
    }

    @Test
    public void testRegisteredTailsDoesNotStandStillForSecondsAfterFlyInLanding() {
        AbstractPlayableSprite registeredSidekick = GameServices.sprites().getSidekicks().getFirst();
        assertInstanceOf(Tails.class, registeredSidekick, "Sonic 2 should register Tails as the default sidekick");
        tails = (Tails) registeredSidekick;
        controller = tails.getCpuController();
        assertNotNull(controller, "registered Tails should have a CPU controller");

        controller.setInitialState(SidekickCpuController.State.SPAWNING);
        tails.setCentreX((short) (sonic.getCentreX() - 0x120));
        tails.setCentreY((short) (sonic.getCentreY() - 0xC0));
        tails.setAir(true);
        sonic.setAir(false);
        sonic.setDead(false);

        for (int i = 0; i < 80 && !controller.isApproaching(); i++) {
            fixture.stepFrame(false, false, false, false, false);
            sonic.setAir(false);
            sonic.setDead(false);
        }
        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());

        int approachFrames = 0;
        int lastApproachTargetX = sonic.getCentreX(16) & 0xFFFF;
        int lastApproachTargetY = sonic.getCentreY(16) & 0xFFFF;
        for (; approachFrames < 180 && controller.isApproaching(); approachFrames++) {
            fixture.stepFrame(false, false, false, true, false);
            sonic.setAir(false);
            sonic.setDead(false);
            lastApproachTargetX = sonic.getCentreX(16) & 0xFFFF;
            lastApproachTargetY = sonic.getCentreY(16) & 0xFFFF;
        }
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "Tails should complete fly-in before checking post-landing follow; "
                        + "approachFrames=" + approachFrames
                        + ", tailsX=" + (tails.getCentreX() & 0xFFFF)
                        + ", tailsY=" + (tails.getCentreY() & 0xFFFF)
                        + ", targetX=" + lastApproachTargetX
                        + ", targetY=" + lastApproachTargetY
                        + ", sonicX=" + (sonic.getCentreX() & 0xFFFF)
                        + ", sonicY=" + (sonic.getCentreY() & 0xFFFF)
                        + ", xSpeed=0x" + Integer.toHexString(tails.getXSpeed() & 0xFFFF)
                        + ", ySpeed=0x" + Integer.toHexString(tails.getYSpeed() & 0xFFFF)
                        + ", gSpeed=0x" + Integer.toHexString(tails.getGSpeed() & 0xFFFF)
                        + ", generatedInput=0x" + Integer.toHexString(controller.getDiagnosticGeneratedHeldInput()));

        for (int i = 0; i < 120 && tails.getAir(); i++) {
            fixture.stepFrame(false, false, false, true, false);
            sonic.setAir(false);
            sonic.setDead(false);
        }
        assertFalse(tails.getAir(), "registered Tails should land before checking stationary duration");

        short initialX = tails.getCentreX();
        int firstMovementFrame = -1;
        int firstRightInputFrame = -1;
        int firstPanicFrame = -1;
        int maxMoveLock = 0;
        int lastGeneratedInput = 0;
        int lastTargetX = sonic.getCentreX(16) & 0xFFFF;
        int lastSonicX = sonic.getCentreX() & 0xFFFF;
        short lastGSpeed = tails.getGSpeed();
        short lastXSpeed = tails.getXSpeed();
        SidekickCpuController.State lastState = controller.getState();

        for (int frame = 1; frame <= 150; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            sonic.setAir(false);
            sonic.setDead(false);

            lastState = controller.getState();
            lastGeneratedInput = controller.getDiagnosticGeneratedHeldInput();
            lastTargetX = sonic.getCentreX(16) & 0xFFFF;
            lastSonicX = sonic.getCentreX() & 0xFFFF;
            lastGSpeed = tails.getGSpeed();
            lastXSpeed = tails.getXSpeed();
            maxMoveLock = Math.max(maxMoveLock, tails.getMoveLockTimer());
            if (firstRightInputFrame < 0 && controller.getInputRight()) {
                firstRightInputFrame = frame;
            }
            if (firstPanicFrame < 0 && lastState == SidekickCpuController.State.PANIC) {
                firstPanicFrame = frame;
            }
            if (tails.getCentreX() != initialX) {
                firstMovementFrame = frame;
                break;
            }
        }

        assertTrue(firstMovementFrame > 0 && firstMovementFrame <= 60,
                "registered S2 Tails should not stand still for seconds after fly-in landing; "
                        + "firstMovementFrame=" + firstMovementFrame
                        + ", firstRightInputFrame=" + firstRightInputFrame
                        + ", firstPanicFrame=" + firstPanicFrame
                        + ", state=" + lastState
                        + ", maxMoveLock=" + maxMoveLock
                        + ", generatedInput=0x" + Integer.toHexString(lastGeneratedInput)
                        + ", cpuDiag=" + controller.formatLatestNormalStepDiagnostics()
                        + ", targetX=" + lastTargetX
                        + ", sonicX=" + lastSonicX
                        + ", tailsX=" + (tails.getCentreX() & 0xFFFF)
                        + ", initialX=" + (initialX & 0xFFFF)
                        + ", gSpeed=0x" + Integer.toHexString(lastGSpeed & 0xFFFF)
                        + ", xSpeed=0x" + Integer.toHexString(lastXSpeed & 0xFFFF));
    }

    // -- Panic State Tests --

    @Test
    public void testPanicTriggersWhenMoveLockedAndStopped() {
        createTailsForTest();
        // Get to NORMAL state
        controller.update(0);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());

        // ROM: PANIC trigger is move_lock && zero inertia, not an idle timer.
        tails.setGSpeed((short) 0);
        tails.setMoveLockTimer(10);
        controller.update(1);

        assertEquals(SidekickCpuController.State.PANIC, controller.getState(), "Should enter PANIC state when move lock is active and inertia is zero");
    }

    @Test
    public void testPanicFacesTowardSonic() {
        createTailsForTest();
        // Get to PANIC state
        controller.update(0);
        tails.setGSpeed((short) 0);
        tails.setMoveLockTimer(1);
        controller.update(1);
        assertEquals(SidekickCpuController.State.PANIC, controller.getState());

        // Place Sonic to the right of Tails
        tails.setX((short) (sonic.getX() - 50));

        tails.setMoveLockTimer(0);
        controller.update(2);

        assertEquals(com.openggf.physics.Direction.RIGHT, tails.getDirection(), "Should face right toward Sonic during panic");
    }

    @Test
    public void testPanicHoldsDownAndReleasesSpindash() {
        createTailsForTest();
        // Get to PANIC state
        controller.update(0);
        tails.setGSpeed((short) 0);
        tails.setMoveLockTimer(1);
        controller.update(1);
        assertEquals(SidekickCpuController.State.PANIC, controller.getState());

        tails.setMoveLockTimer(0);
        controller.update(2);
        assertTrue(controller.getInputDown(), "Should hold down during panic");

        controller.update(128);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(), "Should return to NORMAL on the 128-frame boundary");
    }

    @Test
    public void testSonicDeathTransitionsToApproaching() {
        createTailsForTest();
        controller.update(0);

        sonic.setDead(true);
        controller.update(1);

        // ROM TailsCPU_Normal sends dead-Sonic recovery to routine 4
        // (TailsCPU_Flying). In the engine's S2 state model, that routine is
        // APPROACHING; FLIGHT_AUTO_RECOVERY models the S3K catch-up routine.
        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        assertTrue(tails.isObjectControlled());
        assertTrue(tails.getAir());
    }

    // -- findSonic Tests --

    @Test
    public void testFindsSonicAsNonCpuControlledSprite() {
        createTailsForTest();
        // controller.update should automatically find Sonic
        controller.update(0);

        // If Sonic wasn't found, all inputs would be cleared and state unchanged
        // Since state transitions to NORMAL, Sonic was found
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(), "Should find Sonic and transition to NORMAL");
    }

    @Test
    public void testClearsInputsWhenNoLeader() {
        createTailsForTest();
        // Explicitly clear leader â€” simulates a disconnected sidekick.
        // With explicit leader assignment (no scan), null leader = idle.
        controller.setLeader(null);
        controller.update(0);

        // Should stay in INIT (leader is null, update() returns early)
        assertEquals(SidekickCpuController.State.INIT, controller.getState(), "Should stay in INIT when no leader set");
        assertFalse(controller.getInputLeft());
        assertFalse(controller.getInputRight());
        assertFalse(controller.getInputJump());
    }

    // -- Input Replay Tests --

    @Test
    public void testInputReplayFromSonicHistory() {
        createTailsForTest();
        // Record Sonic's input history by walking right for 20+ frames
        // so that the effective 16-frame delayed input has right pressed
        for (int i = 0; i < 25; i++) {
            fixture.stepFrame(false, false, false, true, false);
            stepTailsFrame();
        }

        // Place Tails close to target so AI override doesn't force direction
        short targetX = sonic.getCentreX(16);
        short targetY = sonic.getCentreY(16);
        tails.setX((short) (targetX - tails.getWidth() / 2));
        tails.setY((short) (targetY - tails.getHeight() / 2));
        tails.setAir(false);

        controller.update(fixture.frameCount());

        // Sonic's recorded input from 16 frames ago should have RIGHT pressed
        // (since Sonic was walking right for all 25 frames)
        short replayedInput = sonic.getInputHistory(16);
        boolean replayedRight = (replayedInput & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        assertTrue(replayedRight, "Sonic's recorded input from 16 frames ago should have RIGHT pressed");
        assertTrue(controller.getInputRight(), "Tails should replay Sonic's right input");
    }

    @Test
    public void testInputReplayJump() {
        createTailsForTest();
        // Run idle frames to settle
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Make Sonic jump (record jump input)
        fixture.stepFrame(false, false, false, false, true);
        stepTailsFrame();

        // Continue for 15 more frames (not stepping Tails on the last one)
        for (int i = 0; i < 15; i++) {
            fixture.stepFrame(false, false, false, false, false);
            stepTailsFrame();
        }

        // Step Sonic one more frame but DON'T step Tails yet
        fixture.stepFrame(false, false, false, false, false);

        // Now the jump input is exactly 16 frames behind in Sonic's history.
        // Update Tails' controller fresh to see the replayed jump.
        tails.setAir(false);
        controller.update(fixture.frameCount());

        short replayedInput = sonic.getInputHistory(16);
        boolean replayedJump = (replayedInput & AbstractPlayableSprite.INPUT_JUMP) != 0;
        assertTrue(replayedJump, "Sonic's recorded input from 16 frames ago should have JUMP pressed");
        assertTrue(controller.getInputJump(), "Tails should replay Sonic's jump input");
    }

    // -- Position History / Delay Tests --

    @Test
    public void testPositionHistoryRecording() {
        createTailsForTest();
        // Walk Sonic right for 20 frames
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        short currentX = sonic.getCentreX();
        short delayedX = sonic.getCentreX(16);

        // After walking right for 20 frames, current position should be
        // to the right of the 16-frame-delayed position
        assertTrue(currentX > delayedX, "Current position should be further right than delayed position. " +
                        "Current=" + currentX + ", Delayed(16)=" + delayedX);
    }

    // -- SpriteManager Integration Test --

    @Test
    public void testSpriteManagerGetSidekick() {
        createTailsForTest();
        AbstractPlayableSprite sidekick = GameServices.sprites().getSidekicks().getFirst();
        assertNotNull(sidekick, "getSidekicks() should return Tails");
        assertTrue(sidekick.isCpuControlled(), "Sidekick should be CPU controlled");
        assertSame(tails, sidekick, "Sidekick should be our Tails instance");
    }

    // -- Tails Helper Methods --

    /**
     * Forces Tails into SPAWNING state by simulating off-screen for 300+ frames.
     * Sets Tails as airborne to avoid PANIC (stuck detection at 120 frames).
     * Also sets Sonic as airborne to prevent SPAWNING from immediately transitioning
     * to APPROACHING (which happens when Sonic is safely grounded).
     */
    private void forceDespawn() {
        tails.setX((short) -500);
        tails.setY((short) -500);
        tails.setAir(true); // Airborne prevents stuck detection -> PANIC

        for (int i = 1; i <= 310; i++) {
            // Keep Sonic airborne so SPAWNING doesn't auto-transition to APPROACHING
            sonic.setAir(true);
            controller.update(i);
            // Keep Tails off-screen and airborne each frame
            tails.setX((short) -500);
            tails.setY((short) -500);
            tails.setAir(true);
        }
    }

    /**
     * Steps Tails through one frame of AI + physics (simplified).
     * Mimics what SpriteManager does for CPU-controlled sprites.
     */
    private void stepTailsFrame() {
        controller.update(fixture.frameCount());
        if (!controller.isApproaching()) {
            tails.getMovementManager().handleMovement(
                    controller.getInputUp(),
                    controller.getInputDown(),
                    controller.getInputLeft(),
                    controller.getInputRight(),
                    controller.getInputJump(),
                    false, false, false);
        }
        tails.tickStatus();
        tails.endOfTick();
    }

    // ========== From TestSignpostWalkOff ==========

    // Start position: close to signpost but before it triggers.
    // EHZ1 signpost is near X=0x2A20. Start a bit earlier so we walk into it.
    private static final short START_X = 0x29A0;
    private static final short START_Y = 0x02A0;

    // Maximum frames to simulate
    private static final int MAX_FRAMES = 600;

    @Test
    public void testSignpostWalkOffAndResultsScreen() {
        // Reposition sprite for this test (after level load, set to desired test position)
        sprite.setCentreX(START_X);
        sprite.setCentreY(START_Y);
        fixture.camera().updatePosition(true);

        System.out.println("=== Signpost Walk-Off Regression Test ===");
        System.out.println("Start position: (" + START_X + ", " + START_Y + ")");
        System.out.println("Actual initial position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println();

        boolean signpostTriggered = false;
        boolean walkedOffScreen = false;
        boolean resultsSpawned = false;

        Camera camera = fixture.camera();

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            // Walk right
            fixture.stepFrame(false, false, false, true, false);

            // Check if signpost triggered (forceInputRight becomes true)
            if (!signpostTriggered && sprite.isForceInputRight()) {
                signpostTriggered = true;
                System.out.println("Frame " + (frame + 1) + ": Signpost triggered at X=" + sprite.getX());
            }

            // After signpost triggers, check if Sonic has walked off screen
            if (signpostTriggered) {
                int screenRightEdge = camera.getX() + camera.getWidth();
                if (sprite.getX() > screenRightEdge) {
                    walkedOffScreen = true;
                    System.out.println("Frame " + (frame + 1) + ": Sonic walked off screen at X=" + sprite.getX()
                            + " (screen right=" + screenRightEdge + ")");
                }

                // Check if results screen was spawned
                if (!resultsSpawned && GameServices.level().getObjectManager() != null) {
                    for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
                        if (obj instanceof ResultsScreenObjectInstance) {
                            resultsSpawned = true;
                            System.out.println("Frame " + (frame + 1) + ": Results screen spawned");
                            break;
                        }
                    }
                }

                // Both conditions met - we can stop early
                if (walkedOffScreen && resultsSpawned) {
                    break;
                }
            }
        }

        System.out.println();
        System.out.println("Final position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("Signpost triggered: " + signpostTriggered);
        System.out.println("Walked off screen: " + walkedOffScreen);
        System.out.println("Results spawned: " + resultsSpawned);

        assertTrue(signpostTriggered, "Signpost should have triggered (forceInputRight=true) but Sonic never reached it. "
                + "Final X=" + sprite.getX());
        assertTrue(walkedOffScreen, "Sonic should walk off the right edge of the screen after signpost, "
                + "but final X=" + sprite.getX() + " is still on screen. "
                + "This indicates the forced-right input is being cancelled by controlLocked.");
        assertTrue(resultsSpawned, "A ResultsScreenObjectInstance should have been spawned after walk-off");
    }
}
