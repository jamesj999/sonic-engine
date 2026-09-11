package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.BreakableWallObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.AbstractSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for the MGZ Act 1 stand-launcher route.
 *
 * <p>Scenario captured from the in-game debug overlay in MGZ Act 1:
 * Sonic falls from above onto the stand launcher at X=0x2970/Y=0x838, is carried
 * left through the MGZ breakable wall at X=0x28E0/Y=0x828, and soon after the
 * platform should enter its waypoint/arc route instead of continuing flat.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzTopLauncherTwistingLoopRegression {
    private static final int START_X = 10612;
    private static final int START_Y = 2036;
    private static final int LAUNCHER_X = 0x2970;
    private static final int LAUNCHER_Y = 0x838;
    private static final int WALL_X = 0x28E0;
    private static final int WALL_Y = 0x828;
    private static final int SPIRAL_CENTER_X = 0x27C0;
    private static final int SPIRAL_TOP_Y = 0x846;
    private static final int SPIRAL_BOTTOM_Y = 0xA60;
    private static final int SPIRAL_EXIT_X = 0x2860;
    private static final int ACTIVATION_WINDOW_FRAMES = 30;
    private static final int MIN_DESCENT_AFTER_WALL = 0x80;
    private static final int MIN_REVERSAL_SPAN = 0x40;
    private static final int MAX_FRAMES = 400;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    private record RouteResult(boolean landedOnLauncher,
                               boolean grabbedLauncher,
                               boolean brokeWall,
                               boolean descendedAfterWall,
                               boolean oscillatedAfterWall,
                               boolean reachedBottomOfSpiral,
                               boolean dead,
                               String detail) {
    }

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 0);
    }

    @AfterAll
    public static void cleanup() {
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
    public void setUp() {
        initialiseFixture();
    }

    private void initialiseFixture() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();

        sprite.setX((short) START_X);
        sprite.setY((short) START_Y);
        sprite.setPushing(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setDirection(com.openggf.physics.Direction.LEFT);
        sprite.clearWallClingState();

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(camera.getX());
        fixture.stepIdleFrames(1);
    }

    @Test
    public void mgzAct1Launcher_breaksWallAndDescendsThroughTowerRoute() {
        MGZTopLauncherObjectInstance launcher = findClosest(MGZTopLauncherObjectInstance.class, LAUNCHER_X, LAUNCHER_Y);
        BreakableWallObjectInstance wall = findClosest(BreakableWallObjectInstance.class, WALL_X, WALL_Y);
        assertNotNull(launcher, "Expected the Act 1 MGZ stand launcher to be active");
        assertNotNull(wall, "Expected the Act 1 MGZ breakable wall to be active");

        RouteResult result = simulateHoldLeftRoute();

        assertTrue(result.landedOnLauncher,
                "Expected Sonic to land on the passive launcher platform before activation. " + result.detail);
        assertTrue(result.grabbedLauncher,
                "Expected Sonic to activate the Act 1 stand launcher within 30 frames of landing and holding left. "
                        + result.detail);
        assertTrue(result.brokeWall,
                "Expected the Act 1 stand-launch route to smash the MGZ wall. " + result.detail);
        assertTrue(result.descendedAfterWall,
                "Expected the post-wall route to descend down the tower instead of continuing upward/flat. "
                        + result.detail);
        assertTrue(result.oscillatedAfterWall,
                "Expected the post-wall route to oscillate around the tower centre while descending. "
                        + result.detail);
        assertTrue(result.reachedBottomOfSpiral,
                "Expected the stand-launch route to reach the bottom of the tower spiral. "
                        + result.detail);
        assertFalse(result.dead,
                "Sonic should survive the Act 1 stand-launch route. " + result.detail);
    }

    private RouteResult simulateHoldLeftRoute() {
        BreakableWallObjectInstance wall = findClosest(BreakableWallObjectInstance.class, WALL_X, WALL_Y);
        MGZTopPlatformObjectInstance trackedPlatform = findClosest(MGZTopPlatformObjectInstance.class, LAUNCHER_X, LAUNCHER_Y);
        boolean landedOnLauncher = false;
        boolean grabbedLauncher = false;
        boolean brokeWall = false;
        boolean descendedAfterWall = false;
        boolean oscillatedAfterWall = false;
        boolean reachedBottomOfSpiral = false;
        int holdLeftStartFrame = -1;
        int firstGrabFrame = -1;
        int firstWallBreakFrame = -1;
        int firstWaypointFrame = -1;
        int firstWaypointStart = -1;
        int firstWaypointX = Integer.MIN_VALUE;
        int firstWaypointY = Integer.MIN_VALUE;
        int firstWaypointAngle = Integer.MIN_VALUE;
        int firstWaypointGroundVel = Integer.MIN_VALUE;
        int bottomReachedFrame = -1;
        int wallBreakPlatformY = Integer.MIN_VALUE;
        int postWallMinPlatformX = Integer.MAX_VALUE;
        int postWallMaxPlatformX = Integer.MIN_VALUE;
        int postWallMaxPlatformY = Integer.MIN_VALUE;
        int previousPostWallPlatformX = Integer.MIN_VALUE;
        int firstPostWallDirection = 0;
        boolean reversedDirectionAfterWall = false;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            if (trackedPlatform == null || !GameServices.level().getObjectManager().getActiveObjects().contains(trackedPlatform)) {
                trackedPlatform = findTrackedPlatform(trackedPlatform);
            }
            MGZTopPlatformObjectInstance platform = trackedPlatform;
            boolean left = false;
            if (holdLeftStartFrame < 0 && platform != null && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                landedOnLauncher = true;
                holdLeftStartFrame = frame;
            }
            if (holdLeftStartFrame >= 0) {
                left = true;
            }
            fixture.stepFrame(false, false, left, false, false);

            if (trackedPlatform == null || !GameServices.level().getObjectManager().getActiveObjects().contains(trackedPlatform)) {
                trackedPlatform = findTrackedPlatform(trackedPlatform);
            }
            platform = trackedPlatform;
            if (platform != null && isAnyPlayerGrabbed(platform)) {
                grabbedLauncher = true;
                if (firstGrabFrame < 0) {
                    firstGrabFrame = frame;
                }
            }
            if (platform != null && firstWaypointFrame < 0
                    && getIntField(platform, "firstActivatedWaypointEntryStart") >= 0) {
                firstWaypointFrame = frame;
                firstWaypointStart = getIntField(platform, "firstActivatedWaypointEntryStart");
                firstWaypointX = platform.getX();
                firstWaypointY = platform.getY();
                firstWaypointAngle = getIntField(platform, "angle") & 0xFF;
                firstWaypointGroundVel = getIntField(platform, "groundVel");
            }
            if (wall != null && (wall.isDestroyed()
                    || !GameServices.level().getObjectManager().getActiveObjects().contains(wall))) {
                brokeWall = true;
                if (firstWallBreakFrame < 0) {
                    firstWallBreakFrame = frame;
                    if (platform != null) {
                        wallBreakPlatformY = platform.getY();
                    }
                }
            }
            if (platform != null && firstWallBreakFrame >= 0) {
                postWallMinPlatformX = Math.min(postWallMinPlatformX, platform.getX());
                postWallMaxPlatformX = Math.max(postWallMaxPlatformX, platform.getX());
                postWallMaxPlatformY = Math.max(postWallMaxPlatformY, platform.getY());
                if (previousPostWallPlatformX != Integer.MIN_VALUE) {
                    int dx = platform.getX() - previousPostWallPlatformX;
                    int direction = Integer.compare(dx, 0);
                    if (direction != 0) {
                        if (firstPostWallDirection == 0) {
                            firstPostWallDirection = direction;
                        } else if (direction != firstPostWallDirection) {
                            reversedDirectionAfterWall = true;
                        }
                    }
                }
                previousPostWallPlatformX = platform.getX();
                if (wallBreakPlatformY != Integer.MIN_VALUE
                        && postWallMaxPlatformY - wallBreakPlatformY >= MIN_DESCENT_AFTER_WALL) {
                    descendedAfterWall = true;
                }
                if (reversedDirectionAfterWall
                        && postWallMinPlatformX != Integer.MAX_VALUE
                        && postWallMaxPlatformX != Integer.MIN_VALUE
                        && postWallMaxPlatformX - postWallMinPlatformX >= MIN_REVERSAL_SPAN
                        && postWallMinPlatformX < SPIRAL_CENTER_X
                        && postWallMaxPlatformX > SPIRAL_CENTER_X) {
                    oscillatedAfterWall = true;
                }
                if (!reachedBottomOfSpiral && platform.getY() >= SPIRAL_BOTTOM_Y) {
                    reachedBottomOfSpiral = true;
                    bottomReachedFrame = frame;
                }
            }

            if (holdLeftStartFrame >= 0 && firstGrabFrame < 0
                    && frame - holdLeftStartFrame > ACTIVATION_WINDOW_FRAMES) {
                break;
            }
            if (descendedAfterWall && oscillatedAfterWall && reachedBottomOfSpiral) {
                break;
            }
        }
        boolean activatedWithinWindow = firstGrabFrame >= 0
                && holdLeftStartFrame >= 0
                && (firstGrabFrame - holdLeftStartFrame) <= ACTIVATION_WINDOW_FRAMES;
        return new RouteResult(landedOnLauncher, activatedWithinWindow, brokeWall,
                descendedAfterWall, oscillatedAfterWall, reachedBottomOfSpiral, sprite.getDead(),
                "landed=" + landedOnLauncher
                        + ",holdLeftStartFrame=" + holdLeftStartFrame
                        + ",grabbed=" + grabbedLauncher
                        + ",activatedWithinWindow=" + activatedWithinWindow
                        + ",brokeWall=" + brokeWall
                        + ",descendedAfterWall=" + descendedAfterWall
                        + ",oscillatedAfterWall=" + oscillatedAfterWall
                        + ",reachedBottomOfSpiral=" + reachedBottomOfSpiral
                        + ",bottomReachedFrame=" + bottomReachedFrame
                        + ",dead=" + sprite.getDead()
                        + ",firstGrabFrame=" + firstGrabFrame
                        + ",firstWallBreakFrame=" + firstWallBreakFrame
                        + ",firstWaypointFrame=" + firstWaypointFrame
                        + ",firstWaypointStart=" + firstWaypointStart
                        + ",firstWaypointX=" + firstWaypointX
                        + ",firstWaypointY=" + firstWaypointY
                        + ",firstWaypointAngle=0x" + Integer.toHexString(firstWaypointAngle)
                        + ",firstWaypointGroundVel=" + firstWaypointGroundVel
                        + ",wallBreakPlatformY=" + wallBreakPlatformY
                        + ",postWallMinPlatformX=" + postWallMinPlatformX
                        + ",postWallMaxPlatformX=" + postWallMaxPlatformX
                        + ",postWallMaxPlatformY=" + postWallMaxPlatformY
                        + ",firstPostWallDirection=" + firstPostWallDirection
                        + ",reversedDirectionAfterWall=" + reversedDirectionAfterWall
                        + "," + describeState(trackedPlatform));
    }

    private MGZTopPlatformObjectInstance findTrackedPlatform(MGZTopPlatformObjectInstance current) {
        MGZTopPlatformObjectInstance grabbed = findGrabbedPlatform();
        if (grabbed != null) {
            return grabbed;
        }
        if (current != null) {
            return current;
        }
        return findClosest(MGZTopPlatformObjectInstance.class, LAUNCHER_X, LAUNCHER_Y);
    }

    private <T> T findClosest(Class<T> type, int targetX, int targetY) {
        T best = null;
        int bestDist = Integer.MAX_VALUE;
        for (var obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!type.isInstance(obj)) {
                continue;
            }
            ObjectInstance instance = (ObjectInstance) obj;
            int dist = Math.abs(instance.getX() - targetX) + Math.abs(instance.getY() - targetY);
            if (dist < bestDist) {
                bestDist = dist;
                best = type.cast(obj);
            }
        }
        return best;
    }

    private MGZTopPlatformObjectInstance findGrabbedPlatform() {
        for (var obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform && isAnyPlayerGrabbed(platform)) {
                return platform;
            }
        }
        return null;
    }

    private String describeState(MGZTopPlatformObjectInstance platform) {
        return "playerX=" + sprite.getX()
                + " playerY=" + sprite.getY()
                + " playerYSub=0x" + Integer.toHexString(sprite.getYSubpixelRaw())
                + " playerCX=" + sprite.getCentreX()
                + " playerCY=" + sprite.getCentreY()
                + " xSpeed=" + sprite.getXSpeed()
                + " ySpeed=" + sprite.getYSpeed()
                + " air=" + sprite.getAir()
                + " onObject=" + sprite.isOnObject()
                + " objCtrl=" + sprite.isObjectControlled()
                + " riding=" + describeRidingObject()
                + " launcherPresent=" + (findClosest(MGZTopLauncherObjectInstance.class, LAUNCHER_X, LAUNCHER_Y) != null)
                + " platform=" + describePlatform(platform)
                + " frame=" + fixture.frameCount();
    }

    private String describePlatform(MGZTopPlatformObjectInstance platform) {
        if (platform == null) {
            return "none";
        }
        return "x=" + platform.getX()
                + " y=" + platform.getY()
                + " grabbed=" + isAnyPlayerGrabbed(platform)
                + " state=" + describePlayerState(platform)
                + " arcMode=" + getIntField(platform, "arcMode")
                + " firstWaypointStart=" + getIntField(platform, "firstActivatedWaypointEntryStart")
                + " lastWaypointStart=" + getIntField(platform, "lastActivatedWaypointEntryStart")
                + " waypointActivations=" + getIntField(platform, "waypointActivationCount")
                + " arcDataIndex=" + getIntField(platform, "arcDataIndex")
                + " arcFlagsHi=0x" + Integer.toHexString(getIntField(platform, "arcFlagsHi") & 0xFF)
                + " arcFlagsLo=0x" + Integer.toHexString(getIntField(platform, "arcFlagsLo") & 0xFF)
                + " angle=0x" + Integer.toHexString(getIntField(platform, "angle") & 0xFF)
                + " airborne=" + getBooleanField(platform, "airborne")
                + " rolling=" + getIntField(platform, "rolling")
                + " groundVel=" + getIntField(platform, "groundVel")
                + " xVel=" + getIntField(platform, "xVel")
                + " yVel=" + getIntField(platform, "yVel");
    }

    private String describeRidingObject() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return "none";
        }
        ObjectInstance riding = objectManager.getRidingObject(sprite);
        return riding == null ? "none" : riding.getClass().getSimpleName() + "@" + riding.getX() + "," + riding.getY();
    }

    private static boolean isAnyPlayerGrabbed(MGZTopPlatformObjectInstance platform) {
        try {
            Field methodField = MGZTopPlatformObjectInstance.class.getDeclaredField("playerStates");
            methodField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Object, Object>) methodField.get(platform);
            for (Object state : map.values()) {
                Field routineField = state.getClass().getDeclaredField("routine");
                routineField.setAccessible(true);
                if (routineField.getInt(state) == 4) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect playerStates", e);
        }
    }

    private String describePlayerState(MGZTopPlatformObjectInstance platform) {
        try {
            Field methodField = MGZTopPlatformObjectInstance.class.getDeclaredField("playerStates");
            methodField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Object, Object>) methodField.get(platform);
            Object state = map.get(sprite);
            if (state == null) {
                return "none";
            }
            return "routine=" + getFieldInt(state, "routine")
                    + ",standingNow=" + getFieldBoolean(state, "standingNow")
                    + ",entrySideBias=" + getFieldInt(state, "entrySideBias");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect player state", e);
        }
    }

    private static int getFieldInt(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static boolean getFieldBoolean(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static void setSubpixel(AbstractSprite sprite, String fieldName, int value) {
        try {
            Field field = AbstractSprite.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setShort(sprite, (short) value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set " + fieldName, e);
        }
    }

    private static int getIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static boolean getBooleanField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }
}
