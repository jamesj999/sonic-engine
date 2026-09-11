package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.IczPathFollowPlatformObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kIcz1PathFollowPlatformHeadless {
    private static final int START_X = 22142;
    private static final int START_Y = 1049;
    private static final int PLATFORM_X = 22211;
    private static final int PLATFORM_Y = 1050;
    private static final int MIN_PLATFORM_FORWARD_PROGRESS = 400;
    private static final int MIN_PLATFORM_DESCENT = 100;
    private static final int MAX_FRAMES = 1800;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite sprite;
    private IczPathFollowPlatformObjectInstance platform;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_ICZ, 0);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) START_X, (short) START_Y)
                .build();
        sprite = fixture.sprite();
        sprite.setX((short) START_X);
        sprite.setY((short) START_Y);
        sprite.setPushing(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setMoveLockTimer(0);
        // Keep the harness focused on the post-spike platform path rather than
        // the earlier pre-existing hurt-block contact in this partial ICZ slice.
        sprite.setRingCount(1);
        sprite.setDirection(Direction.RIGHT);
        sprite.clearWallClingState();

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(camera.getX());

        platform = findPathFollowPlatform();
        assertNotNull(platform, "Expected the ICZ path-follow platform near the repro start to be active");
    }

    @Test
    void icz1PushBlockActivatesPostSpikeSlopeWithoutKillingSonic() {
        RouteResult result = runRoute();

        assertTrue(result.activated,
                "Expected holding right to activate the subtype 0x02 path-follow platform. " + result.detail);
        assertTrue(result.platformMovedRight,
                "Expected the platform to follow the post-spike slope to the right. " + result.detail);
        assertTrue(result.platformDescended,
                "Expected the platform to follow the post-spike slope downward. " + result.detail);
        assertFalse(result.sonicDied,
                "Sonic must not be crushed or killed while the platform is activated in this partial ICZ slice. "
                        + result.detail);
    }

    private RouteResult runRoute() {
        boolean activated = false;
        boolean platformMovedRight = false;
        boolean platformDescended = false;
        boolean sonicDied = false;
        int activatedFrames = 0;
        int initialPlatformX = platform.getX();
        int initialPlatformY = platform.getY();
        StringBuilder trace = new StringBuilder();

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            if (platform.getRoutineByteForTesting() == 0x08) {
                activated = true;
                activatedFrames++;
            }

            fixture.stepFrame(false, false, false, true, false);

            platformMovedRight |= platform.getX() - initialPlatformX >= MIN_PLATFORM_FORWARD_PROGRESS;
            platformDescended |= platform.getY() - initialPlatformY >= MIN_PLATFORM_DESCENT;
            sonicDied |= sprite.getDead();

            if (frame % 30 == 0 || sonicDied || (platformMovedRight && platformDescended)) {
                appendTrace(trace, frame);
            }
            if (sonicDied || (activated && platformMovedRight && platformDescended)) {
                break;
            }
        }

        return new RouteResult(activated, platformMovedRight, platformDescended, sonicDied,
                "Final " + describeState() + " activatedFrames=" + activatedFrames
                        + " platformDelta=(" + (platform.getX() - initialPlatformX)
                        + "," + (platform.getY() - initialPlatformY) + ")\nTrace:\n" + trace);
    }

    private IczPathFollowPlatformObjectInstance findPathFollowPlatform() {
        IczPathFollowPlatformObjectInstance best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof IczPathFollowPlatformObjectInstance candidate
                    && candidate.getRoutineByteForTesting() == 0x06) {
                int distance = Math.abs(candidate.getX() - PLATFORM_X) + Math.abs(candidate.getY() - PLATFORM_Y);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return bestDistance <= 128 ? best : null;
    }

    private void appendTrace(StringBuilder trace, int frame) {
        trace.append(String.format(
                "f=%04d sonic=(%d,%d) centre=(%d,%d) air=%s dead=%s ride=%s top=%02X lrb=%02X plat=(%d,%d) r=%02X xv=%d yv=%d%n",
                frame,
                sprite.getX(), sprite.getY(),
                sprite.getCentreX(), sprite.getCentreY(),
                sprite.getAir(), sprite.getDead(),
                GameServices.level().getObjectManager().getRidingObject(sprite) == platform,
                sprite.getTopSolidBit() & 0xFF,
                sprite.getLrbSolidBit() & 0xFF,
                platform.getX(), platform.getY(),
                platform.getRoutineByteForTesting(),
                platform.getXVelocityForTesting(),
                platform.getYVelocityForTesting()));
    }

    private String describeState() {
        return String.format(
                "frame=%d sonic=(%d,%d) centre=(%d,%d) air=%s dead=%s plat=(%d,%d) r=%02X xv=%d yv=%d",
                fixture.frameCount(),
                sprite.getX(), sprite.getY(),
                sprite.getCentreX(), sprite.getCentreY(),
                sprite.getAir(), sprite.getDead(),
                platform.getX(), platform.getY(),
                platform.getRoutineByteForTesting(),
                platform.getXVelocityForTesting(),
                platform.getYVelocityForTesting());
    }

    private record RouteResult(boolean activated,
                               boolean platformMovedRight,
                               boolean platformDescended,
                               boolean sonicDied,
                               String detail) {
    }
}
