package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.physics.Direction;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.timer.AbstractTimer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HeadlessTestFixture} builder.
 * <p>
 * These tests verify that the fixture correctly sets up a playable sprite,
 * camera, and headless runner, and that separate fixture instances do not
 * interfere with each other.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHeadlessTestFixture {
    private static SharedLevel shared;

    @BeforeAll
    public static void loadLevel() throws Exception {
        shared = SharedLevel.load(SonicGame.SONIC_2, 0, 0); // EHZ Act 1
    }

    @AfterAll
    public static void cleanup() {
        if (shared != null) shared.dispose();
    }

    @Test
    public void testFixtureCreatesSprite() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        AbstractPlayableSprite sprite = fixture.sprite();
        assertNotNull(sprite, "Fixture should create a non-null sprite");
        assertEquals(96, sprite.getX(), "Sprite X should match start position");
        assertEquals(655, sprite.getY(), "Sprite Y should match start position");
    }

    @Test
    public void testFixtureSetsUpCamera() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        Camera camera = fixture.camera();
        assertNotNull(camera, "Fixture should provide a non-null camera");
        assertFalse(camera.getFrozen(), "Camera should not be frozen after fixture build");
        assertTrue(camera.getMaxX() > 0, "Camera maxX should be non-zero after fixture build");
    }

    @Test
    public void testFixtureRegistersConfiguredSidekickTeam() {
        HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        var sidekicks = GameServices.sprites().getSidekicks();
        assertEquals(1, sidekicks.size(), "EHZ1 headless fixture should include Sonic 2's default Tails sidekick");
        AbstractPlayableSprite tails = sidekicks.getFirst();
        assertTrue(tails.isCpuControlled(), "Configured sidekick should be CPU-controlled");
        assertNotNull(tails.getCpuController(), "Configured sidekick should have an active CPU controller");
        assertEquals("tails", GameServices.sprites().getSidekickCharacterName(tails),
                "Registered sidekick should preserve the configured character name");
    }

    @Test
    public void sonic2LiveTailsRetainsRunSlopeFrameOrientationWhileTimerIsLive() {
        HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertInstanceOf(ScriptedVelocityAnimationProfile.class, tails.getAnimationProfile());
        tails.setAnimationId(0);
        tails.setMovementInputActive(true);
        tails.setGSpeed((short) 0x600);
        tails.setAngle((byte) 0x20);
        tails.setDirection(Direction.RIGHT);

        for (int frameIndex = 0; frameIndex < 4; frameIndex++) {
            tails.setAnimationFrameIndex(frameIndex);
            tails.setAnimationTick(0);
            tails.getAnimationManager().update(frameIndex);
            assertEquals(0x40 + frameIndex, tails.getMappingFrame(),
                    "TAnim_WalkRunZoom must skip each interleaved two-frame HaulAss bank");
            assertTrue(tails.getRenderHFlip());
            assertTrue(tails.getRenderVFlip());

            tails.getAnimationManager().update(frameIndex);
            assertEquals(0x40 + frameIndex, tails.getMappingFrame(),
                    "TAnim_WalkRunZoom must retain the selected slope frame while its timer is live");
            assertTrue(tails.getRenderHFlip(),
                    "the timer early-return must retain the slope frame's horizontal flip");
            assertTrue(tails.getRenderVFlip(),
                    "the timer early-return must retain the slope frame's vertical flip");
        }
    }

    @Test
    public void testFixtureCanStepFrames() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        short initialX = fixture.sprite().getX();

        // Hold right for 10 frames
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        short finalX = fixture.sprite().getX();
        assertTrue(finalX > initialX, "Sprite should have moved right after 10 frames. "
                + "Initial=" + initialX + ", Final=" + finalX);
        assertEquals(10, fixture.frameCount(), "Frame counter should be 10");
    }

    @Test
    public void testFixtureStepsRuntimeTimersBeforeLevelFrame() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        AtomicBoolean fired = new AtomicBoolean(false);
        GameServices.timers().registerTimer(new AbstractTimer("headless-step-test", 1) {
            @Override
            public boolean perform() {
                fired.set(true);
                return true;
            }
        });

        fixture.stepFrame(false, false, false, false, false);

        assertTrue(fired.get(), "Headless frame stepping should advance runtime timers like GameLoop");
        assertNull(GameServices.timers().getTimerForCode("headless-step-test"));
    }

    @Test
    public void testZoneAndActBuildRegistersPlayerBeforeLoad() {
        Logger logger = Logger.getLogger("com.openggf.level.LevelManager");
        LogCaptureHandler handler = new LogCaptureHandler();
        boolean useParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(0, 0)
                    .build();

            assertNotNull(fixture.sprite(), "Fixture should still create a sprite for zone/act loads");
            assertEquals(0, handler.countAtOrAbove(Level.WARNING),
                    "Fixture build should not warn that no player sprite was registered before load");
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(useParentHandlers);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void testTwoFixturesDoNotInterfere() {
        // Note: building fixture2 calls resetPerTest(), which clears SpriteManager.
        // After fixture2 is built, fixture1 is no longer usable. Each fixture
        // "owns" the singleton state between its build() and the next build().

        // Use the same known-good EHZ1 spawn point so the sprite is grounded
        HeadlessTestFixture fixture1 = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        // Step fixture1 so it moves right
        for (int i = 0; i < 10; i++) {
            fixture1.stepFrame(false, false, false, true, false);
        }
        short fixture1X = fixture1.sprite().getX();
        assertTrue(fixture1X > 96, "Fixture1 should have moved from start");

        // Create a second fixture at the same start position
        HeadlessTestFixture fixture2 = HeadlessTestFixture.builder()
                .withSharedLevel(shared)
                .startPosition((short) 96, (short) 655)
                .build();

        // Fixture2 should have its own sprite at the original position,
        // unaffected by fixture1's movement
        assertEquals(96, fixture2.sprite().getX(), "Fixture2 sprite should be at its own start X");
        assertEquals(655, fixture2.sprite().getY(), "Fixture2 sprite should be at its own start Y");
        assertEquals(0, fixture2.frameCount(), "Fixture2 frame counter should be 0");

        // Fixture2's sprite is a different object than fixture1's
        assertNotSame(fixture1.sprite(), fixture2.sprite(), "Fixtures should have different sprite instances");
    }
}
