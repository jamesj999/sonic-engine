package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
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

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgz2TopLauncherTerrainFollowRegression {
    private static final int START_X = 5553;
    private static final int START_Y = 1917;
    private static final int MONITOR_START_X = 5772;
    private static final int MONITOR_END_X = 6000;
    private static final int SPRING_MONITOR_X = 7197;
    private static final int SPRING_MONITOR_Y = 1938;
    private static final int EXPECTED_ROUTE_SPRING_TYPE = 6;
    private static final int EXPECTED_ROUTE_SPRING_SUBTYPE = 0x32;
    private static final int EXPECTED_ROUTE_SPRING_STRENGTH = -0x0A00;
    private static final int EXPECTED_ROUTE_PLATFORM_SPRING_Y_VEL = EXPECTED_ROUTE_SPRING_STRENGTH + 8;
    private static final int POST_SPRING_OBSERVE_FRAMES = 4;
    private static final int SECOND_USE_REGRAB_WINDOW = 80;
    private static final int SECOND_USE_RELEASE_WINDOW = 45;
    private static final int SECOND_LAUNCHER_DROP_HEIGHT = 80;
    private static final int DISTINCT_SECOND_LAUNCHER_MIN_DISTANCE = 256;
    private static final int LAUNCHER_RESPAWN_TRAVEL_X = 7600;
    private static final int LAUNCHER_RESPAWN_TRAVEL_Y = 2100;
    private static final int LAUNCHER_NEAR_WINDOW = 48;
    private static final int ATTACH_WINDOW_FRAMES = 45;
    private static final int MAX_FRAMES = 500;
    private static final int MAX_FRAMES_EXTENDED = 900;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    private record RouteResult(boolean landedOnLauncher,
                               boolean attachedToLauncher,
                               boolean launched,
                               boolean reachedMonitorStart,
                               boolean reachedMonitorEnd,
                               boolean movedUpAfterMonitorStart,
                               boolean becameAirborneAfterMonitorStart,
                               String detail) {
    }

    private record SpringRouteResult(boolean landedOnLauncher,
                                     boolean attachedToLauncher,
                                     boolean launched,
                                     boolean reachedSpringMonitor,
                                     boolean foundSpringNearMonitor,
                                     int springType,
                                     int springSubtype,
                                     boolean playerSpringTriggered,
                                     boolean platformInheritedSpring,
                                     boolean platformInheritedWithinRomWindow,
                                     int playerSpringXSpeed,
                                     int playerSpringYSpeed,
                                     int platformSpringXVel,
                                     int platformSpringYVel,
                                     int postSpringFramesObserved,
                                     int postSpringXDelta,
                                     int postSpringYDelta,
                                     int postSpringUpFrames,
                                     String detail) {
    }

    private record AlternatingLauncherResult(boolean firstUseWorked,
                                             boolean secondUseReached,
                                             boolean secondUseGrabbed,
                                             boolean secondUseLaunchedGrabbedPlatform,
                                             boolean sonicStayedOnOriginalPlatform,
                                             boolean jumpReleasedExactlyOnePlatform,
                                             String detail) {
    }

    private record ReloadedLauncherUseResult(boolean grabbed,
                                             boolean launchedGrabbedPlatform,
                                             boolean sonicStayedOnOriginalPlatform,
                                             boolean jumpReleasedExactlyOnePlatform,
                                             String detail) {
    }

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 1);
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
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        sprite.setX((short) START_X);
        sprite.setY((short) START_Y);
        sprite.setPushing(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setDirection(com.openggf.physics.Direction.RIGHT);
        sprite.clearWallClingState();

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(camera.getX());
        fixture.stepIdleFrames(1);
    }

    @Test
    void mgzAct2Launcher_routeStaysGroundedAndDoesNotClimbBeforeX6000() {
        MGZTopLauncherObjectInstance launcher = findClosest(MGZTopLauncherObjectInstance.class, START_X, START_Y);
        assertNotNull(launcher, "Expected the MGZ2 top launcher near the repro start to be active");

        RouteResult result = simulateHoldRightRoute(launcher);

        assertTrue(result.landedOnLauncher,
                "Expected Sonic to fall onto the MGZ2 top launcher before activation. " + result.detail);
        assertTrue(result.attachedToLauncher,
                "Expected holding right to attach Sonic to the MGZ2 top launcher. " + result.detail);
        assertTrue(result.launched,
                "Expected the MGZ2 launcher to transition the top platform into launched body-driven motion. "
                        + result.detail);
        assertTrue(result.reachedMonitorStart,
                "Expected the launched top platform to reach the X=5772 monitor point. " + result.detail);
        assertTrue(result.reachedMonitorEnd,
                "Expected the launched top platform to stay on the route through at least X=6000. "
                        + result.detail);
        assertFalse(result.movedUpAfterMonitorStart,
                "The MGZ2 launched top platform should not start climbing after X=5772. " + result.detail);
        assertFalse(result.becameAirborneAfterMonitorStart,
                "The MGZ2 launched top platform should stay terrain-following instead of going airborne. "
                        + result.detail);
    }

    @Test
    void mgzAct2Launcher_routePassesAboveSpringNearX7197WithoutSpringHandoff() {
        MGZTopLauncherObjectInstance launcher = findClosest(MGZTopLauncherObjectInstance.class, START_X, START_Y);
        assertNotNull(launcher, "Expected the MGZ2 top launcher near the repro start to be active");

        SpringRouteResult result = simulateRouteToSpring(launcher);

        assertTrue(result.landedOnLauncher,
                "Expected Sonic to fall onto the MGZ2 top launcher before activation. " + result.detail);
        assertTrue(result.attachedToLauncher,
                "Expected holding right to attach Sonic to the MGZ2 top launcher. " + result.detail);
        assertTrue(result.launched,
                "Expected the MGZ2 launcher to transition the top platform into launched body-driven motion. "
                        + result.detail);
        assertTrue(result.reachedSpringMonitor,
                "Expected the MGZ2 launcher route to reach the spring monitor near X=7197. " + result.detail);
        assertTrue(result.foundSpringNearMonitor,
                "Expected to find the spring near X=7197/Y=1938 on the launcher route. " + result.detail);
        assertTrue(result.springType == EXPECTED_ROUTE_SPRING_TYPE,
                "Expected the route spring to be the flipped diagonal-up spring from the ROM path. "
                        + result.detail);
        assertTrue(result.springSubtype == EXPECTED_ROUTE_SPRING_SUBTYPE,
                "Expected the route spring subtype to match the MGZ2 ROM placement. " + result.detail);
        assertFalse(result.playerSpringTriggered,
                "The X=7197 spring exists in the loaded object set, but the launcher route passes above it; "
                        + "the carried player should not receive a diagonal spring launch. " + result.detail);
        assertFalse(result.platformInheritedSpring,
                "The MGZ top platform should not inherit spring velocity from a spring it did not contact. "
                        + result.detail);
    }

    @Test
    void mgzAct2Launcher_secondUseDoesNotLaunchAnotherPlatformOrDoubleDropOnRelease() {
        MGZTopLauncherObjectInstance launcher = findClosest(MGZTopLauncherObjectInstance.class, START_X, START_Y);
        assertNotNull(launcher, "Expected the MGZ2 top launcher near the repro start to be active");

        AlternatingLauncherResult result = simulateTwoConsecutiveLauncherUses(launcher);

        assertTrue(result.firstUseWorked,
                "Expected the first MGZ2 launcher use to succeed before checking alternating-use parity. "
                        + result.detail);
        assertTrue(result.secondUseReached,
                "Expected the route to reach another MGZ2 launcher in the same session. " + result.detail);
        assertTrue(result.secondUseGrabbed,
                "Expected Sonic to attach to the second MGZ2 launcher platform. " + result.detail);
        assertTrue(result.secondUseLaunchedGrabbedPlatform,
                "Expected the second launcher to launch the platform Sonic was actually grabbed to. "
                        + result.detail);
        assertFalse(result.sonicStayedOnOriginalPlatform,
                "Sonic should not stay attached to the original platform while another one launches. "
                        + result.detail);
        assertTrue(result.jumpReleasedExactlyOnePlatform,
                "Jumping off the second launcher use should release exactly one active platform, not two. "
                        + result.detail);
    }

    @Test
    void mgzAct2Launcher_respawnKeepsSinglePassiveChildAndLaunchesTheGrabbedPlatform() {
        MGZTopLauncherObjectInstance launcher = findClosest(MGZTopLauncherObjectInstance.class, START_X, START_Y);
        assertNotNull(launcher, "Expected the MGZ2 top launcher near the repro start to be active");

        int launcherX = launcher.getX();
        int launcherY = launcher.getY();
        int passiveChildrenBeforeTravel = countPassivePlatformsNear(launcherX, launcherY, LAUNCHER_NEAR_WINDOW);
        String passiveBeforeSnapshot = describePlatformsNear(launcherX, launcherY, LAUNCHER_NEAR_WINDOW);

        teleportPlayerTo(LAUNCHER_RESPAWN_TRAVEL_X, LAUNCHER_RESPAWN_TRAVEL_Y);
        fixture.stepIdleFrames(8);

        teleportPlayerTo(launcherX, launcherY - SECOND_LAUNCHER_DROP_HEIGHT);
        fixture.stepIdleFrames(6);

        MGZTopLauncherObjectInstance reloadedLauncher = findClosest(MGZTopLauncherObjectInstance.class, launcherX, launcherY);
        int passiveChildrenAfterReload = countPassivePlatformsNear(launcherX, launcherY, LAUNCHER_NEAR_WINDOW);
        String passiveAfterReloadSnapshot = describePlatformsNear(launcherX, launcherY, LAUNCHER_NEAR_WINDOW);

        MGZTopPlatformObjectInstance nearbyPassivePlatform = findPassivePlatformNear(
                launcherX, launcherY, LAUNCHER_NEAR_WINDOW);
        if (nearbyPassivePlatform != null) {
            teleportPlayerTo(nearbyPassivePlatform.getX(), nearbyPassivePlatform.getY() - SECOND_LAUNCHER_DROP_HEIGHT);
            fixture.stepIdleFrames(1);
        }

        ReloadedLauncherUseResult result = simulateLauncherUse(reloadedLauncher != null ? reloadedLauncher : launcher);

        assertTrue(passiveChildrenBeforeTravel == 1,
                "Expected one passive MGZ launcher child before forcing the launcher to unload. "
                        + "before=" + passiveBeforeSnapshot);
        assertTrue(reloadedLauncher != null,
                "Expected the MGZ2 launcher to reload after travelling away and back. "
                        + "afterReload=" + passiveAfterReloadSnapshot);
        assertTrue(passiveChildrenAfterReload == 1,
                "Reloading the same MGZ2 launcher should not leave duplicate passive children alive. "
                        + "afterReload=" + passiveAfterReloadSnapshot);
        assertTrue(result.grabbed,
                "Expected Sonic to attach to the reloaded MGZ2 launcher child. " + result.detail);
        assertTrue(result.launchedGrabbedPlatform,
                "Expected the reloaded MGZ2 launcher to launch the platform Sonic actually grabbed. "
                        + result.detail);
        assertFalse(result.sonicStayedOnOriginalPlatform,
                "Reloading the same launcher should not leave Sonic attached to one platform while another launches. "
                        + result.detail);
        assertTrue(result.jumpReleasedExactlyOnePlatform,
                "Jumping off the reloaded launcher should release exactly one platform, not multiple. "
                        + result.detail);
    }

    private RouteResult simulateHoldRightRoute(MGZTopLauncherObjectInstance launcher) {
        MGZTopPlatformObjectInstance trackedPlatform = findClosest(
                MGZTopPlatformObjectInstance.class, launcher.getX(), launcher.getY());
        boolean landedOnLauncher = false;
        boolean attachedToLauncher = false;
        boolean launched = false;
        boolean reachedMonitorStart = false;
        boolean reachedMonitorEnd = false;
        boolean movedUpAfterMonitorStart = false;
        boolean becameAirborneAfterMonitorStart = false;
        int holdRightStartFrame = -1;
        int firstAttachFrame = -1;
        int firstLaunchFrame = -1;
        int firstMonitorFrame = -1;
        int firstMonitorY = Integer.MIN_VALUE;
        int previousMonitorY = Integer.MIN_VALUE;
        int upwardFrame = -1;
        int airborneFrame = -1;
        String firstUpwardSnapshot = "none";
        String firstAirborneSnapshot = "none";

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);

            boolean right = false;
            if (holdRightStartFrame < 0 && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                landedOnLauncher = true;
                holdRightStartFrame = frame;
            }
            if (holdRightStartFrame >= 0) {
                right = true;
            }

            fixture.stepFrame(false, false, false, right, false);

            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);
            if (trackedPlatform != null && isAnyPlayerGrabbed(trackedPlatform)) {
                attachedToLauncher = true;
                if (firstAttachFrame < 0) {
                    firstAttachFrame = frame;
                }
            }
            if (trackedPlatform != null && getBooleanField(trackedPlatform, "bodyDriven")) {
                launched = true;
                if (firstLaunchFrame < 0) {
                    firstLaunchFrame = frame;
                }
            }
            if (trackedPlatform != null && trackedPlatform.getX() >= MONITOR_START_X) {
                reachedMonitorStart = true;
                if (firstMonitorFrame < 0) {
                    firstMonitorFrame = frame;
                    firstMonitorY = trackedPlatform.getY();
                }
                if (previousMonitorY != Integer.MIN_VALUE && trackedPlatform.getY() < previousMonitorY) {
                    movedUpAfterMonitorStart = true;
                    if (upwardFrame < 0) {
                        upwardFrame = frame;
                        firstUpwardSnapshot = describePlatform(trackedPlatform);
                    }
                }
                if (getBooleanField(trackedPlatform, "airborne")) {
                    becameAirborneAfterMonitorStart = true;
                    if (airborneFrame < 0) {
                        airborneFrame = frame;
                        firstAirborneSnapshot = describePlatform(trackedPlatform);
                    }
                }
                previousMonitorY = trackedPlatform.getY();
                if (trackedPlatform.getX() >= MONITOR_END_X) {
                    reachedMonitorEnd = true;
                    break;
                }
            }

            if (holdRightStartFrame >= 0 && firstAttachFrame < 0
                    && frame - holdRightStartFrame > ATTACH_WINDOW_FRAMES) {
                break;
            }
        }

        boolean attachedWithinWindow = firstAttachFrame >= 0
                && holdRightStartFrame >= 0
                && (firstAttachFrame - holdRightStartFrame) <= ATTACH_WINDOW_FRAMES;
        return new RouteResult(landedOnLauncher, attachedWithinWindow, launched, reachedMonitorStart,
                reachedMonitorEnd, movedUpAfterMonitorStart, becameAirborneAfterMonitorStart,
                "holdRightStartFrame=" + holdRightStartFrame
                        + ",firstAttachFrame=" + firstAttachFrame
                        + ",firstLaunchFrame=" + firstLaunchFrame
                        + ",launchToMonitorFrames="
                        + (firstLaunchFrame >= 0 && firstMonitorFrame >= 0
                        ? (firstMonitorFrame - firstLaunchFrame) : -1)
                        + ",firstMonitorFrame=" + firstMonitorFrame
                        + ",firstMonitorY=" + firstMonitorY
                        + ",upwardFrame=" + upwardFrame
                        + ",launchToUpwardFrames="
                        + (firstLaunchFrame >= 0 && upwardFrame >= 0 ? (upwardFrame - firstLaunchFrame) : -1)
                        + ",firstUpwardSnapshot=" + firstUpwardSnapshot
                        + ",airborneFrame=" + airborneFrame
                        + ",launchToAirborneFrames="
                        + (firstLaunchFrame >= 0 && airborneFrame >= 0 ? (airborneFrame - firstLaunchFrame) : -1)
                        + ",firstAirborneSnapshot=" + firstAirborneSnapshot
                        + "," + describeState(trackedPlatform));
    }

    private SpringRouteResult simulateRouteToSpring(MGZTopLauncherObjectInstance launcher) {
        MGZTopPlatformObjectInstance trackedPlatform = findClosest(
                MGZTopPlatformObjectInstance.class, launcher.getX(), launcher.getY());
        boolean landedOnLauncher = false;
        boolean attachedToLauncher = false;
        boolean launched = false;
        boolean reachedSpringMonitor = false;
        boolean foundSpringNearMonitor = false;
        boolean playerSpringTriggered = false;
        boolean platformInheritedSpring = false;
        boolean platformInheritedWithinRomWindow = false;
        int holdRightStartFrame = -1;
        int firstAttachFrame = -1;
        int firstLaunchFrame = -1;
        int firstSpringMonitorFrame = -1;
        int playerSpringFrame = -1;
        int platformSpringFrame = -1;
        int springType = -1;
        int springSubtype = -1;
        int playerSpringXSpeed = 0;
        int playerSpringYSpeed = 0;
        int platformSpringXVel = 0;
        int platformSpringYVel = 0;
        int postSpringFramesObserved = 0;
        int postSpringXDelta = 0;
        int postSpringYDelta = 0;
        int postSpringUpFrames = 0;
        int platformSpringStartX = Integer.MIN_VALUE;
        int platformSpringStartY = Integer.MIN_VALUE;
        int previousPostSpringY = Integer.MIN_VALUE;
        Sonic3kSpringObjectInstance spring = null;
        String springSnapshot = "none";
        String playerSpringSnapshot = "none";
        String platformSpringSnapshot = "none";
        StringBuilder postSpringTrace = new StringBuilder();

        for (int frame = 0; frame < MAX_FRAMES_EXTENDED; frame++) {
            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);

            boolean right = false;
            if (holdRightStartFrame < 0 && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                landedOnLauncher = true;
                holdRightStartFrame = frame;
            }
            if (holdRightStartFrame >= 0) {
                right = true;
            }

            fixture.stepFrame(false, false, false, right, false);

            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);
            if (trackedPlatform != null && isAnyPlayerGrabbed(trackedPlatform)) {
                attachedToLauncher = true;
                if (firstAttachFrame < 0) {
                    firstAttachFrame = frame;
                }
            }
            if (trackedPlatform != null && getBooleanField(trackedPlatform, "bodyDriven")) {
                launched = true;
                if (firstLaunchFrame < 0) {
                    firstLaunchFrame = frame;
                }
            }
            if (trackedPlatform != null && trackedPlatform.getX() >= SPRING_MONITOR_X) {
                reachedSpringMonitor = true;
                if (firstSpringMonitorFrame < 0) {
                    firstSpringMonitorFrame = frame;
                    spring = findClosest(Sonic3kSpringObjectInstance.class, SPRING_MONITOR_X, SPRING_MONITOR_Y);
                    foundSpringNearMonitor = spring != null;
                    if (spring != null) {
                        springType = getIntField(spring, "springType");
                        springSubtype = spring.getSpawn().subtype() & 0xFF;
                    }
                    springSnapshot = describeSpring(spring);
                }
                if (!playerSpringTriggered
                        && sprite.getAnimationId() == Sonic3kAnimationIds.SPRING.id()
                        && sprite.getXSpeed() == EXPECTED_ROUTE_SPRING_STRENGTH
                        && sprite.getYSpeed() == EXPECTED_ROUTE_SPRING_STRENGTH) {
                    playerSpringTriggered = true;
                    playerSpringFrame = frame;
                    playerSpringXSpeed = sprite.getXSpeed();
                    playerSpringYSpeed = sprite.getYSpeed();
                    playerSpringSnapshot = describeState(trackedPlatform);
                }
                if (playerSpringTriggered && playerSpringFrame >= 0 && frame - playerSpringFrame <= 4) {
                    if (!postSpringTrace.isEmpty()) {
                        postSpringTrace.append(" | ");
                    }
                    postSpringTrace.append("f=").append(frame)
                            .append(":anim=").append(sprite.getAnimationId())
                            .append(",springing=").append(sprite.getSpringing())
                            .append(",px=").append(sprite.getXSpeed())
                            .append(",py=").append(sprite.getYSpeed())
                            .append(",air=").append(sprite.getAir())
                            .append(",platformAir=").append(trackedPlatform != null
                                    && getBooleanField(trackedPlatform, "airborne"))
                            .append(",platformX=").append(trackedPlatform != null
                                    ? getIntField(trackedPlatform, "xVel") : Integer.MIN_VALUE)
                            .append(",platformY=").append(trackedPlatform != null
                                    ? getIntField(trackedPlatform, "yVel") : Integer.MIN_VALUE);
                }
                if (playerSpringTriggered
                        && trackedPlatform != null
                        && getBooleanField(trackedPlatform, "airborne")
                        && getIntField(trackedPlatform, "xVel") == EXPECTED_ROUTE_SPRING_STRENGTH
                        && getIntField(trackedPlatform, "yVel") == EXPECTED_ROUTE_PLATFORM_SPRING_Y_VEL) {
                    platformInheritedSpring = true;
                    platformSpringFrame = frame;
                    platformSpringXVel = getIntField(trackedPlatform, "xVel");
                    platformSpringYVel = getIntField(trackedPlatform, "yVel");
                    platformInheritedWithinRomWindow = (platformSpringFrame - playerSpringFrame) <= 1;
                    platformSpringStartX = trackedPlatform.getX();
                    platformSpringStartY = trackedPlatform.getY();
                    previousPostSpringY = trackedPlatform.getY();
                    platformSpringSnapshot = describeState(trackedPlatform);
                }
            }

            if (platformInheritedSpring && trackedPlatform != null && frame > platformSpringFrame) {
                postSpringFramesObserved++;
                postSpringXDelta = trackedPlatform.getX() - platformSpringStartX;
                postSpringYDelta = trackedPlatform.getY() - platformSpringStartY;
                if (trackedPlatform.getY() < previousPostSpringY) {
                    postSpringUpFrames++;
                }
                previousPostSpringY = trackedPlatform.getY();
                if (!postSpringTrace.isEmpty()) {
                    postSpringTrace.append(" | ");
                }
                postSpringTrace.append("pf=").append(frame)
                        .append(":x=").append(trackedPlatform.getX())
                        .append(",y=").append(trackedPlatform.getY())
                        .append(",xVel=").append(getIntField(trackedPlatform, "xVel"))
                        .append(",yVel=").append(getIntField(trackedPlatform, "yVel"))
                        .append(",air=").append(getBooleanField(trackedPlatform, "airborne"));
                if (postSpringFramesObserved >= POST_SPRING_OBSERVE_FRAMES) {
                    break;
                }
            }

            if (reachedSpringMonitor && trackedPlatform != null && trackedPlatform.getX() > SPRING_MONITOR_X + 160) {
                break;
            }
        }

        boolean attachedWithinWindow = firstAttachFrame >= 0
                && holdRightStartFrame >= 0
                && (firstAttachFrame - holdRightStartFrame) <= ATTACH_WINDOW_FRAMES;
        return new SpringRouteResult(landedOnLauncher, attachedWithinWindow, launched, reachedSpringMonitor,
                foundSpringNearMonitor, springType, springSubtype, playerSpringTriggered,
                platformInheritedSpring, platformInheritedWithinRomWindow,
                playerSpringXSpeed, playerSpringYSpeed, platformSpringXVel, platformSpringYVel,
                postSpringFramesObserved, postSpringXDelta, postSpringYDelta, postSpringUpFrames,
                "holdRightStartFrame=" + holdRightStartFrame
                        + ",firstAttachFrame=" + firstAttachFrame
                        + ",firstLaunchFrame=" + firstLaunchFrame
                        + ",firstSpringMonitorFrame=" + firstSpringMonitorFrame
                        + ",playerSpringFrame=" + playerSpringFrame
                        + ",platformSpringFrame=" + platformSpringFrame
                        + ",launchToSpringFrames="
                        + (firstLaunchFrame >= 0 && firstSpringMonitorFrame >= 0
                        ? (firstSpringMonitorFrame - firstLaunchFrame) : -1)
                        + ",launchToPlayerSpringFrames="
                        + (firstLaunchFrame >= 0 && playerSpringFrame >= 0 ? (playerSpringFrame - firstLaunchFrame) : -1)
                        + ",launchToPlatformSpringFrames="
                        + (firstLaunchFrame >= 0 && platformSpringFrame >= 0 ? (platformSpringFrame - firstLaunchFrame) : -1)
                        + ",spring=" + springSnapshot
                        + ",playerSpringSnapshot=" + playerSpringSnapshot
                        + ",platformSpringSnapshot=" + platformSpringSnapshot
                        + ",postSpringFramesObserved=" + postSpringFramesObserved
                        + ",postSpringXDelta=" + postSpringXDelta
                        + ",postSpringYDelta=" + postSpringYDelta
                        + ",postSpringUpFrames=" + postSpringUpFrames
                        + ",postSpringTrace=" + postSpringTrace
                        + "," + describeState(trackedPlatform));
    }

    private AlternatingLauncherResult simulateTwoConsecutiveLauncherUses(MGZTopLauncherObjectInstance launcher) {
        MGZTopPlatformObjectInstance trackedPlatform = findClosest(
                MGZTopPlatformObjectInstance.class, launcher.getX(), launcher.getY());
        boolean firstUseWorked = false;
        boolean secondUseReached = false;
        boolean secondUseGrabbed = false;
        boolean secondUseLaunchedGrabbedPlatform = false;
        boolean sonicStayedOnOriginalPlatform = false;
        boolean jumpReleasedExactlyOnePlatform = false;

        int holdRightStartFrame = -1;
        int firstLaunchFrame = -1;
        int secondGrabFrame = -1;
        int jumpFrame = -1;
        int airbornePlatformCountAtJump = -1;
        MGZTopPlatformObjectInstance firstLaunchedPlatform = null;
        MGZTopPlatformObjectInstance secondGrabbedPlatform = null;
        ObjectSpawn secondLauncherSpawn = null;
        MGZTopLauncherObjectInstance secondLauncher = null;
        String firstLaunchSnapshot = "none";
        String secondGrabSnapshot = "none";
        String secondLaunchSnapshot = "none";
        String releaseSnapshot = "none";
        String secondLauncherSnapshot = "none";

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);

            boolean right = holdRightStartFrame >= 0;
            if (holdRightStartFrame < 0 && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                holdRightStartFrame = frame;
                right = true;
            }

            fixture.stepFrame(false, false, false, right, false);

            trackedPlatform = findTrackedPlatform(trackedPlatform, launcher);
            if (!firstUseWorked && trackedPlatform != null && getBooleanField(trackedPlatform, "bodyDriven")) {
                firstUseWorked = true;
                firstLaunchFrame = frame;
                firstLaunchedPlatform = trackedPlatform;
                firstLaunchSnapshot = describeState(trackedPlatform);
                break;
            }

            if (holdRightStartFrame >= 0 && !firstUseWorked && frame - holdRightStartFrame > ATTACH_WINDOW_FRAMES) {
                break;
            }
        }

        if (firstUseWorked && firstLaunchedPlatform != null) {
            for (int i = 0; i < SECOND_USE_RELEASE_WINDOW; i++) {
                fixture.stepFrame(false, false, false, false, false);
            }
            fixture.stepFrame(false, false, false, false, true);
            fixture.stepIdleFrames(8);

            secondLauncherSpawn = findNextLauncherSpawn(launcher.getX(), launcher.getY());
            secondUseReached = secondLauncherSpawn != null;
            secondLauncherSnapshot = describeLauncherSpawn(secondLauncherSpawn);
            if (secondLauncherSpawn != null) {
                sprite.setCentreX((short) secondLauncherSpawn.x());
                sprite.setCentreY((short) (secondLauncherSpawn.y() - SECOND_LAUNCHER_DROP_HEIGHT));
                sprite.setXSpeed((short) 0);
                sprite.setYSpeed((short) 0);
                sprite.setAir(true);
                sprite.setOnObject(false);
                sprite.setObjectControlled(false);
                sprite.clearWallClingState();
                Camera camera = fixture.camera();
                camera.updatePosition(true);
                GameServices.level().postCameraObjectPlacementSync();
                fixture.stepIdleFrames(4);
                secondLauncher = findClosest(MGZTopLauncherObjectInstance.class,
                        secondLauncherSpawn.x(), secondLauncherSpawn.y());
                secondUseReached = secondLauncher != null;
                secondLauncherSnapshot = describeLauncher(secondLauncher);
                MGZTopPlatformObjectInstance secondPassivePlatform = findClosest(
                        MGZTopPlatformObjectInstance.class, secondLauncherSpawn.x(), secondLauncherSpawn.y());
                if (secondPassivePlatform != null) {
                    sprite.setCentreX((short) secondPassivePlatform.getX());
                    sprite.setCentreY((short) (secondPassivePlatform.getY() - SECOND_LAUNCHER_DROP_HEIGHT));
                    fixture.stepIdleFrames(1);
                }

                int secondHoldRightStartFrame = -1;

                for (int frame = 0; frame < MAX_FRAMES; frame++) {
                    boolean right = secondHoldRightStartFrame >= 0;
                    if (secondHoldRightStartFrame < 0 && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                        secondHoldRightStartFrame = frame;
                        right = true;
                    }

                    fixture.stepFrame(false, false, false, right, false);

                    MGZTopPlatformObjectInstance grabbed = findGrabbedPlatform();
                    if (!secondUseGrabbed && grabbed != null) {
                        secondUseGrabbed = true;
                        secondGrabFrame = frame;
                        secondGrabbedPlatform = grabbed;
                        secondGrabSnapshot = describeState(grabbed);
                    }

                    if (secondGrabbedPlatform != null
                            && !secondUseLaunchedGrabbedPlatform
                            && getBooleanField(secondGrabbedPlatform, "bodyDriven")) {
                        secondUseLaunchedGrabbedPlatform = true;
                        secondLaunchSnapshot = describeState(secondGrabbedPlatform);
                        sonicStayedOnOriginalPlatform = firstLaunchedPlatform != null
                                && isAnyPlayerGrabbed(firstLaunchedPlatform)
                                && !isAnyPlayerGrabbed(secondGrabbedPlatform);
                    }

                    if (secondUseGrabbed && frame >= SECOND_USE_RELEASE_WINDOW) {
                        fixture.stepFrame(false, false, false, false, true);
                        jumpFrame = frame;
                        airbornePlatformCountAtJump = countAirborneBodyDrivenPlatforms();
                        fixture.stepIdleFrames(2);
                        jumpReleasedExactlyOnePlatform = countAirborneBodyDrivenPlatforms() == 1;
                        releaseSnapshot = describeAllTopPlatforms();
                        break;
                    }

                    if (secondHoldRightStartFrame >= 0
                            && frame - secondHoldRightStartFrame > SECOND_USE_REGRAB_WINDOW
                            && !secondUseGrabbed) {
                        break;
                    }
                }
            }
        }

        return new AlternatingLauncherResult(firstUseWorked, secondUseReached, secondUseGrabbed,
                secondUseLaunchedGrabbedPlatform, sonicStayedOnOriginalPlatform, jumpReleasedExactlyOnePlatform,
                "holdRightStartFrame=" + holdRightStartFrame
                        + ",firstLaunchFrame=" + firstLaunchFrame
                        + ",secondGrabFrame=" + secondGrabFrame
                        + ",jumpFrame=" + jumpFrame
                        + ",airbornePlatformCountAtJump=" + airbornePlatformCountAtJump
                        + ",firstLaunchSnapshot=" + firstLaunchSnapshot
                        + ",secondLauncherSnapshot=" + secondLauncherSnapshot
                        + ",secondGrabSnapshot=" + secondGrabSnapshot
                        + ",secondLaunchSnapshot=" + secondLaunchSnapshot
                        + ",releaseSnapshot=" + releaseSnapshot
                        + "," + describeState(trackedPlatform));
    }

    private ReloadedLauncherUseResult simulateLauncherUse(MGZTopLauncherObjectInstance launcher) {
        MGZTopPlatformObjectInstance grabbedPlatform = null;
        boolean grabbed = false;
        boolean launchedGrabbedPlatform = false;
        boolean sonicStayedOnOriginalPlatform = false;
        boolean jumpReleasedExactlyOnePlatform = false;
        int holdRightStartFrame = -1;
        int grabFrame = -1;
        int launchFrame = -1;
        int jumpFrame = -1;
        int airbornePlatformCountAtJump = -1;
        String grabSnapshot = "none";
        String launchSnapshot = "none";
        String releaseSnapshot = "none";

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            boolean right = holdRightStartFrame >= 0;
            if (holdRightStartFrame < 0 && sprite.isOnObject() && sprite.getYSpeed() == 0) {
                holdRightStartFrame = frame;
                right = true;
            }

            fixture.stepFrame(false, false, false, right, false);

            MGZTopPlatformObjectInstance currentGrabbed = findGrabbedPlatform();
            if (!grabbed && currentGrabbed != null) {
                grabbed = true;
                grabFrame = frame;
                grabbedPlatform = currentGrabbed;
                grabSnapshot = describeState(currentGrabbed);
            }

            MGZTopPlatformObjectInstance launchedPlatform = findBodyDrivenPlatformNear(
                    launcher.getX(), launcher.getY(), LAUNCHER_NEAR_WINDOW);
            if (!launchedGrabbedPlatform && launchedPlatform != null) {
                launchFrame = frame;
                launchSnapshot = describeState(launchedPlatform);
                launchedGrabbedPlatform = launchedPlatform == grabbedPlatform;
                sonicStayedOnOriginalPlatform = grabbedPlatform != null
                        && isAnyPlayerGrabbed(grabbedPlatform)
                        && launchedPlatform != grabbedPlatform;
            }

            if (grabbed && frame >= SECOND_USE_RELEASE_WINDOW) {
                fixture.stepFrame(false, false, false, false, true);
                jumpFrame = frame;
                airbornePlatformCountAtJump = countBodyDrivenPlatforms();
                fixture.stepIdleFrames(2);
                jumpReleasedExactlyOnePlatform = countBodyDrivenPlatforms() == 1;
                releaseSnapshot = describeAllTopPlatforms();
                break;
            }

            if (holdRightStartFrame >= 0 && !grabbed && frame - holdRightStartFrame > SECOND_USE_REGRAB_WINDOW) {
                break;
            }
        }

        return new ReloadedLauncherUseResult(grabbed, launchedGrabbedPlatform, sonicStayedOnOriginalPlatform,
                jumpReleasedExactlyOnePlatform,
                "holdRightStartFrame=" + holdRightStartFrame
                        + ",grabFrame=" + grabFrame
                        + ",launchFrame=" + launchFrame
                        + ",jumpFrame=" + jumpFrame
                        + ",airbornePlatformCountAtJump=" + airbornePlatformCountAtJump
                        + ",grabSnapshot=" + grabSnapshot
                        + ",launchSnapshot=" + launchSnapshot
                        + ",releaseSnapshot=" + releaseSnapshot
                        + "," + describeState(grabbedPlatform));
    }

    private MGZTopPlatformObjectInstance findTrackedPlatform(MGZTopPlatformObjectInstance current,
                                                             MGZTopLauncherObjectInstance launcher) {
        MGZTopPlatformObjectInstance grabbed = findGrabbedPlatform();
        if (grabbed != null) {
            return grabbed;
        }
        if (current != null && GameServices.level().getObjectManager().getActiveObjects().contains(current)) {
            return current;
        }
        return findClosest(MGZTopPlatformObjectInstance.class, launcher.getX(), launcher.getY());
    }

    private MGZTopPlatformObjectInstance findGrabbedPlatform() {
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform && isAnyPlayerGrabbed(platform)) {
                return platform;
            }
        }
        return null;
    }

    private MGZTopPlatformObjectInstance findBodyDrivenPlatformNear(int targetX, int targetY, int maxDistance) {
        MGZTopPlatformObjectInstance best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!(obj instanceof MGZTopPlatformObjectInstance platform)) {
                continue;
            }
            if (!getBooleanField(platform, "bodyDriven")) {
                continue;
            }
            int dist = Math.abs(platform.getX() - targetX) + Math.abs(platform.getY() - targetY);
            if (dist > maxDistance || dist >= bestDist) {
                continue;
            }
            best = platform;
            bestDist = dist;
        }
        return best;
    }

    private <T> T findClosest(Class<T> type, int targetX, int targetY) {
        T best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!type.isInstance(obj)) {
                continue;
            }
            int dist = Math.abs(obj.getX() - targetX) + Math.abs(obj.getY() - targetY);
            if (dist < bestDist) {
                bestDist = dist;
                best = type.cast(obj);
            }
        }
        return best;
    }

    private ObjectSpawn findNextLauncherSpawn(int currentX, int currentY) {
        ObjectSpawn best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ObjectSpawn spawn : sharedLevel.level().getObjects()) {
            if (spawn.objectId() != Sonic3kObjectIds.MGZ_TOP_LAUNCHER) {
                continue;
            }
            int dist = Math.abs(spawn.x() - currentX) + Math.abs(spawn.y() - currentY);
            if (dist < DISTINCT_SECOND_LAUNCHER_MIN_DISTANCE) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = spawn;
            }
        }
        return best;
    }

    private MGZTopPlatformObjectInstance findPassivePlatformNear(int targetX, int targetY, int maxDistance) {
        MGZTopPlatformObjectInstance best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!(obj instanceof MGZTopPlatformObjectInstance platform)) {
                continue;
            }
            if (getBooleanField(platform, "bodyDriven")) {
                continue;
            }
            int dist = Math.abs(platform.getX() - targetX) + Math.abs(platform.getY() - targetY);
            if (dist > maxDistance || dist >= bestDist) {
                continue;
            }
            best = platform;
            bestDist = dist;
        }
        return best;
    }

    private int countPassivePlatformsNear(int targetX, int targetY, int maxDistance) {
        int count = 0;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!(obj instanceof MGZTopPlatformObjectInstance platform)) {
                continue;
            }
            if (getBooleanField(platform, "bodyDriven")) {
                continue;
            }
            int dist = Math.abs(platform.getX() - targetX) + Math.abs(platform.getY() - targetY);
            if (dist <= maxDistance) {
                count++;
            }
        }
        return count;
    }

    private String describePlatformsNear(int targetX, int targetY, int maxDistance) {
        StringBuilder builder = new StringBuilder();
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!(obj instanceof MGZTopPlatformObjectInstance platform)) {
                continue;
            }
            int dist = Math.abs(platform.getX() - targetX) + Math.abs(platform.getY() - targetY);
            if (dist > maxDistance) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(describePlatform(platform));
        }
        return builder.toString();
    }

    private void teleportPlayerTo(int centreX, int centreY) {
        sprite.setCentreX((short) centreX);
        sprite.setCentreY((short) centreY);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setObjectControlled(false);
        sprite.clearWallClingState();
        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
    }

    private String describeState(MGZTopPlatformObjectInstance platform) {
        return "playerX=" + sprite.getX()
                + ",playerY=" + sprite.getY()
                + ",playerCX=" + sprite.getCentreX()
                + ",playerCY=" + sprite.getCentreY()
                + ",xSpeed=" + sprite.getXSpeed()
                + ",ySpeed=" + sprite.getYSpeed()
                + ",air=" + sprite.getAir()
                + ",onObject=" + sprite.isOnObject()
                + ",objCtrl=" + sprite.isObjectControlled()
                + ",playerStates=" + describePlayerStates(platform)
                + ",platform=" + describePlatform(platform)
                + ",frame=" + fixture.frameCount();
    }

    private String describePlatform(MGZTopPlatformObjectInstance platform) {
        if (platform == null) {
            return "none";
        }
        return "x=" + platform.getX()
                + ",y=" + platform.getY()
                + ",grabbed=" + isAnyPlayerGrabbed(platform)
                + ",bodyDriven=" + getBooleanField(platform, "bodyDriven")
                + ",airborne=" + getBooleanField(platform, "airborne")
                + ",rolling=" + getIntField(platform, "rolling")
                + ",angle=0x" + Integer.toHexString(getIntField(platform, "angle") & 0xFF)
                + ",groundVel=" + getIntField(platform, "groundVel")
                + ",xVel=" + getIntField(platform, "xVel")
                + ",yVel=" + getIntField(platform, "yVel")
                + ",arcMode=" + getIntField(platform, "arcMode");
    }

    private String describeSpring(Sonic3kSpringObjectInstance spring) {
        if (spring == null) {
            return "none";
        }
        return "x=" + spring.getX()
                + ",y=" + spring.getY()
                + ",springType=" + getIntField(spring, "springType")
                + ",subtype=0x" + Integer.toHexString(spring.getSpawn().subtype() & 0xFF)
                + ",renderFlags=0x" + Integer.toHexString(spring.getSpawn().renderFlags() & 0xFF);
    }

    private String describeLauncher(MGZTopLauncherObjectInstance launcher) {
        if (launcher == null) {
            return "none";
        }
        return "x=" + launcher.getX() + ",y=" + launcher.getY();
    }

    private String describeLauncherSpawn(ObjectSpawn spawn) {
        if (spawn == null) {
            return "none";
        }
        return "x=" + spawn.x() + ",y=" + spawn.y() + ",layoutIndex=" + spawn.layoutIndex();
    }

    private int countAirborneBodyDrivenPlatforms() {
        int count = 0;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform
                    && getBooleanField(platform, "bodyDriven")
                    && getBooleanField(platform, "airborne")) {
                count++;
            }
        }
        return count;
    }

    private int countBodyDrivenPlatforms() {
        int count = 0;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform
                    && getBooleanField(platform, "bodyDriven")) {
                count++;
            }
        }
        return count;
    }

    private String describeAllTopPlatforms() {
        StringBuilder builder = new StringBuilder();
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (!(obj instanceof MGZTopPlatformObjectInstance platform)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(describePlatform(platform));
        }
        return builder.toString();
    }

    private static boolean isAnyPlayerGrabbed(MGZTopPlatformObjectInstance platform) {
        try {
            Field field = MGZTopPlatformObjectInstance.class.getDeclaredField("playerStates");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Object, Object>) field.get(platform);
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

    private String describePlayerStates(MGZTopPlatformObjectInstance platform) {
        if (platform == null) {
            return "none";
        }
        try {
            Field field = MGZTopPlatformObjectInstance.class.getDeclaredField("playerStates");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Object, Object>) field.get(platform);
            StringBuilder builder = new StringBuilder();
            for (var entry : map.entrySet()) {
                Object player = entry.getKey();
                Object state = entry.getValue();
                Field routineField = state.getClass().getDeclaredField("routine");
                routineField.setAccessible(true);
                Field grabbedField = state.getClass().getDeclaredField("grabbed");
                grabbedField.setAccessible(true);
                if (!builder.isEmpty()) {
                    builder.append(" | ");
                }
                builder.append(player == sprite ? "main" : player.getClass().getSimpleName())
                        .append(":routine=").append(routineField.getInt(state))
                        .append(",grabbed=").append(grabbedField.getBoolean(state));
            }
            return builder.isEmpty() ? "empty" : builder.toString();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect playerStates", e);
        }
    }

    private static int getIntField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static boolean getBooleanField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
