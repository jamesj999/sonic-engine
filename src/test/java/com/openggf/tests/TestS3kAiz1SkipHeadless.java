package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.physics.GroundSensor;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Grouped headless tests for Sonic 3K AIZ Act 1 with intro skip enabled.
 *
 * Level data is loaded once via {@code @BeforeAll}; sprite, camera, and game
 * state are reset per test via {@link HeadlessTestFixture}.
 *
 * Merged from:
 * <ul>
 *   <li>TestS3kAiz1SpawnStability</li>
 *   <li>TestS3kAizHollowLogTraversal</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1SkipHeadless {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
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
        // Reset static hollow-tree reveal counter -- the original per-test level load
        // cleared this via Sonic3kAIZEvents.initLevel(), but we share one level load.
        AizHollowTreeObjectInstance.resetTreeRevealCounter();
        // Reset object manager so spawn windows and active objects are fresh per test.
        // The original per-test level load did this implicitly.
        GameServices.level().getObjectManager().reset(0);
    }

    // ========== From TestS3kAiz1SpawnStability ==========

    @Test
    public void aiz1IntroSkipSpawnHasCollisionAndNoImmediatePitDeath() {
        int centreX = sprite.getCentreX();
        int centreY = sprite.getCentreY();
        assertTrue(hasPrimaryCollisionBelow(centreX, centreY, 512), "Expected collidable primary terrain below AIZ1 intro-skip spawn");

        fixture.stepIdleFrames(30);
        assertFalse(sprite.getDead(), "AIZ1 intro-skip spawn should not immediately enter death state");
    }

    private boolean hasPrimaryCollisionBelow(int worldX, int worldY, int rangePixels) {
        Level level = GameServices.level().getCurrentLevel();
        if (level == null) {
            return false;
        }
        int endY = worldY + Math.max(0, rangePixels);
        for (int y = worldY; y <= endY; y += 16) {
            ChunkDesc chunkDesc = GameServices.level().getChunkDescAt((byte) 0, worldX, y);
            if (chunkDesc == null || !chunkDesc.hasPrimarySolidity()) {
                continue;
            }
            int chunkIndex = chunkDesc.getChunkIndex();
            if (chunkIndex < 0 || chunkIndex >= level.getChunkCount()) {
                continue;
            }
            Chunk chunk = level.getChunk(chunkIndex);
            if (chunk.getSolidTileIndex() != 0) {
                return true;
            }
        }
        return false;
    }

    // ========== From TestS3kAizHollowLogTraversal ==========

    private static final int FPS = 60;
    private static final int TIMEOUT_FRAMES = 9 * FPS;

    // User-provided debug state (top-left position and raw speeds).
    private static final short START_X = (short) 11164;
    private static final short START_Y = (short) 951;
    private static final short START_X_SPEED = (short) 1954;
    private static final short START_Y_SPEED = (short) 803;
    private static final short START_G_SPEED = (short) 2120;
    private static final byte START_ANGLE = 0x10;

    private static final short WAYPOINT_X = (short) 11412;
    private static final short WAYPOINT_Y = (short) 1070;
    private static final int TARGET_EXIT_Y = 808;
    private static final int AIZ_MINIBOSS_TRIGGER_X = 0x2F10;
    private static final int TOP_EXIT_CHECK_MAX_FRAMES = 96;
    private static final int TOP_EXIT_CHECK_MAX_X = AIZ_MINIBOSS_TRIGGER_X - 0x20;

    @Test
    public void hollowLogTraversal_regressionScenario_fromDebugCoords() {
        applyDebugStartState();

        boolean reachedWaypoint = false;
        boolean reachedExitHeight = false;
        int minYAfterWaypoint = Integer.MAX_VALUE;
        int minYOverall = Integer.MAX_VALUE;
        int minYOverallFrame = -1;
        int stuckFramesNear1097 = 0;
        boolean enteredHollowLogRide = false;
        int firstRideFrame = -1;
        int rideFrames = 0;
        int firstRideOffFrame = -1;
        int frame = 0;
        int prevX = sprite.getX();

        // Phase 1: advance while holding Right until exact waypoint is reached.
        for (; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int x = sprite.getX();
            int y = sprite.getY();
            if (y < minYOverall) {
                minYOverall = y;
                minYOverallFrame = frame;
            }
            if (!enteredHollowLogRide && sprite.isObjectMappingFrameControl()) {
                enteredHollowLogRide = true;
                firstRideFrame = frame;
            }
            if (sprite.isObjectMappingFrameControl()) {
                rideFrames++;
            } else if (enteredHollowLogRide && firstRideOffFrame < 0) {
                firstRideOffFrame = frame;
            }

            // Frame-granular crossing check: the exact waypoint can occur between two
            // discrete simulation steps (e.g. x 11404 -> 11414 with y=1070).
            boolean crossedWaypointX = prevX <= WAYPOINT_X && x >= WAYPOINT_X;
            if (!reachedWaypoint && y == WAYPOINT_Y && crossedWaypointX) {
                reachedWaypoint = true;
                frame++;
                break;
            }
            prevX = x;
        }

        // Phase 2: continue holding Right and verify hollow log traversal.
        for (; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int x = sprite.getX();
            int y = sprite.getY();
            int absGSpeed = Math.abs(sprite.getGSpeed());
            if (y < minYOverall) {
                minYOverall = y;
                minYOverallFrame = frame;
            }
            if (!enteredHollowLogRide && sprite.isObjectMappingFrameControl()) {
                enteredHollowLogRide = true;
                firstRideFrame = frame;
            }
            if (sprite.isObjectMappingFrameControl()) {
                rideFrames++;
            } else if (enteredHollowLogRide && firstRideOffFrame < 0) {
                firstRideOffFrame = frame;
            }
            if (y < minYAfterWaypoint) {
                minYAfterWaypoint = y;
            }

            if (y <= TARGET_EXIT_Y) {
                reachedExitHeight = true;
                break;
            }

            // Explicit fail mode from report: speed collapses and Sonic stalls near Y=1097.
            boolean stuckNearBottomCenter = y >= 1095 && y <= 1099 && absGSpeed <= 0x20;
            if (stuckNearBottomCenter) {
                stuckFramesNear1097++;
                if (stuckFramesNear1097 >= 30) {
                    fail("Hollow log failure: Sonic got stuck near Y=1097 with near-zero speed."
                            + " x=" + x + " y=" + y + " gSpeed=" + sprite.getGSpeed()
                            + " frame=" + frame);
                }
            } else {
                stuckFramesNear1097 = 0;
            }
            prevX = x;
        }

        assertTrue(reachedWaypoint, "Expected Sonic to pass waypoint X=11412 Y=1070 within 7 in-game seconds. "
                        + "Final X=" + sprite.getX() + " Y=" + sprite.getY()
                        + " Input=HoldRight"
                        + " MinYOverall=" + minYOverall
                        + " MinYOverallFrame=" + minYOverallFrame
                        + " EnteredRide=" + enteredHollowLogRide
                        + " FirstRideFrame=" + firstRideFrame
                        + " RideFrames=" + rideFrames
                        + " FirstRideOffFrame=" + firstRideOffFrame
                        + " ObjCtrl=" + sprite.isObjectControlled()
                        + " ObjMapCtrl=" + sprite.isObjectMappingFrameControl());
        assertTrue(reachedExitHeight, "Expected Sonic to traverse hollow log and reach Y<=808 within 7 in-game seconds."
                        + " MinYAfterWaypoint=" + minYAfterWaypoint
                        + " MinYOverall=" + minYOverall
                        + " MinYOverallFrame=" + minYOverallFrame
                        + " FinalY=" + sprite.getY()
                        + " FinalGSpeed=" + sprite.getGSpeed()
                        + " Input=HoldRight"
                        + " EnteredRide=" + enteredHollowLogRide
                        + " FirstRideFrame=" + firstRideFrame
                        + " RideFrames=" + rideFrames
                        + " FirstRideOffFrame=" + firstRideOffFrame
                        + " ObjCtrl=" + sprite.isObjectControlled()
                        + " ObjMapCtrl=" + sprite.isObjectMappingFrameControl());
    }

    @Test
    public void hollowLogTraversal_appliesTreeRevealChunkSwitch() {
        applyDebugStartState();

        boolean reachedFirstThreshold = false;
        boolean reachedSecondThreshold = false;
        int firstThresholdFrame = -1;
        int maxRevealCounter = 0;
        for (int frame = 0; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int revealCounter = AizHollowTreeObjectInstance.getTreeRevealCounter();
            maxRevealCounter = Math.max(maxRevealCounter, revealCounter);
            if (revealCounter >= 0x14) {
                reachedFirstThreshold = true;
                if (firstThresholdFrame < 0) {
                    firstThresholdFrame = frame;
                }
            }
            if (revealCounter >= 0x24) {
                reachedSecondThreshold = true;
            }
        }

        assertTrue(reachedFirstThreshold, "Expected Hollow Log to drive Events_fg_4 to at least 0x14 in Act 1. "
                        + "Frame=" + firstThresholdFrame);
        assertTrue(reachedSecondThreshold, "Expected Hollow Log to progress to at least the second reveal threshold (0x24). "
                        + "FirstThresholdFrame=" + firstThresholdFrame);
        // Counter must reach the upper reveal range. The exact peak depends on
        // frame-level tick ordering; 0x33 and 0x34 both indicate full progression.
        assertTrue(maxRevealCounter >= 0x33, "Expected upper reveal progression (counter >= 0x33) to occur for full top-section reveal."
                        + " MaxRevealCounter=0x" + Integer.toHexString(maxRevealCounter));
    }

    @Test
    public void hollowLogTraversal_treeRevealCompletesAndClears() {
        applyDebugStartState();

        boolean revealStarted = false;
        boolean revealClearedAfterStart = false;
        int maxRevealCounter = 0;
        int clearFrame = -1;

        for (int frame = 0; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int revealCounter = AizHollowTreeObjectInstance.getTreeRevealCounter();
            if (revealCounter > 0) {
                revealStarted = true;
            }
            if (revealCounter > maxRevealCounter) {
                maxRevealCounter = revealCounter;
            }
            if (revealStarted && revealCounter == 0) {
                revealClearedAfterStart = true;
                clearFrame = frame;
                break;
            }
        }

        assertTrue(revealStarted, "Expected Events_fg_4 to start (non-zero) during hollow-log traversal.");
        assertTrue(revealClearedAfterStart, "Expected Events_fg_4 to self-clear after reveal progression while holding Right."
                        + " MaxCounter=0x" + Integer.toHexString(maxRevealCounter)
                        + " ClearFrame=" + clearFrame);
    }

    @Test
    public void hollowLogTraversal_doesNotMutateCollisionMapRows() {
        applyDebugStartState();

        Map map = GameServices.level().getCurrentLevel().getMap();
        byte[][] before = snapshotTreeColumns(map);

        for (int frame = 0; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        byte[][] after = snapshotTreeColumns(map);
        for (int row = 0; row < before.length; row++) {
            if (before[row][0] != after[row][0] || before[row][1] != after[row][1]) {
                fail("Expected tree collision-driving map bytes to remain unchanged; row="
                        + row + " before=(" + (before[row][0] & 0xFF) + "," + (before[row][1] & 0xFF) + ")"
                        + " after=(" + (after[row][0] & 0xFF) + "," + (after[row][1] & 0xFF) + ")");
            }
        }
    }

    @Test
    public void hollowLogTraversal_visualRevealStableAcrossRepeatRuns() {
        // Reload level to get a clean tilemap â€” earlier tests may have mutated it
        // via applyHollowTreeScreenEvent during frame stepping.
        reloadAizAct1AndApplyDebugStart();

        LevelManager levelManager = GameServices.level();

        assertTrue(runUntilTreeRevealClears(), "Expected first traversal to trigger and finish tree reveal.");
        int[] firstPass = snapshotRevealWindow(levelManager);

        reloadAizAct1AndApplyDebugStart();
        assertTrue(runUntilTreeRevealClears(), "Expected second traversal to trigger and finish tree reveal.");
        int[] secondPass = snapshotRevealWindow(levelManager);

        int diffCount = 0;
        int firstDiffIndex = -1;
        for (int i = 0; i < firstPass.length; i++) {
            if (firstPass[i] != secondPass[i]) {
                diffCount++;
                if (firstDiffIndex < 0) {
                    firstDiffIndex = i;
                }
            }
        }

        assertTrue(diffCount == 0, "Expected repeat traversal to produce identical reveal tilemap output."
                        + " diffCount=" + diffCount
                        + " firstDiffIndex=" + firstDiffIndex);
    }

    @Test
    public void hollowLogTraversal_reentryLandsOnBottomFloor() {
        applyDebugStartState();

        assertTrue(runUntilExitHeightHoldingRight(), "Expected first pass to complete hollow-log ascent before re-entry check.");

        boolean descendedBackIntoTunnel = false;
        boolean landedAtBottomFloor = false;
        int maxYAfterExit = sprite.getY();
        int landedY = -1;

        // Return into the tunnel and ensure Sonic lands instead of falling through.
        for (int frame = 0; frame < TIMEOUT_FRAMES * 3; frame++) {
            fixture.stepFrame(false, false, true, false, false);

            int y = sprite.getY();
            if (y > maxYAfterExit) {
                maxYAfterExit = y;
            }
            if (y >= 1024) {
                descendedBackIntoTunnel = true;
            }
            if (descendedBackIntoTunnel && !sprite.getAir() && y >= 1060 && y <= 1130) {
                landedAtBottomFloor = true;
                landedY = y;
                break;
            }
            if (sprite.getDead() || y > 1500) {
                fail("Hollow tree re-entry failure: Sonic fell through bottom floor."
                        + " y=" + y
                        + " dead=" + sprite.getDead()
                        + " frame=" + frame);
            }
        }

        assertTrue(descendedBackIntoTunnel, "Expected Sonic to descend back into the hollow-tree tunnel after ascent."
                        + " maxYAfterExit=" + maxYAfterExit);
        assertTrue(landedAtBottomFloor, "Expected Sonic to land on the tunnel bottom floor after re-entry."
                        + " landedY=" + landedY
                        + " maxYAfterExit=" + maxYAfterExit);
    }

    @Test
    public void hollowLogTraversal_topExitMaintainsForwardTraversal() {
        applyDebugStartState();

        assertTrue(runUntilExitHeightHoldingRight(), "Expected hollow-log ascent to reach top-exit height before momentum check.");

        int exitX = sprite.getX();
        int exitY = sprite.getY();
        boolean releasedFromRide = !sprite.isObjectMappingFrameControl();
        int releaseFrame = releasedFromRide ? 0 : -1;
        int maxForwardGain = 0;
        int groundedNearTopZeroSpeedFrames = 0;
        int finalCheckedX = exitX;
        int finalCheckedFrame = -1;

        for (int frame = 0; frame < TOP_EXIT_CHECK_MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int x = sprite.getX();
            int y = sprite.getY();
            finalCheckedX = x;
            finalCheckedFrame = frame;
            int forwardGain = x - exitX;
            if (forwardGain > maxForwardGain) {
                maxForwardGain = forwardGain;
            }
            if (!releasedFromRide && !sprite.isObjectMappingFrameControl()) {
                releasedFromRide = true;
                releaseFrame = frame;
            }

            boolean nearTopBand = y >= 760 && y <= 920;
            if (nearTopBand && !sprite.getAir() && Math.abs(sprite.getGSpeed()) <= 0x20) {
                groundedNearTopZeroSpeedFrames++;
                if (groundedNearTopZeroSpeedFrames >= 20) {
                    fail("Top-exit stall regression: Sonic grounded near top with zero momentum."
                            + " frame=" + frame
                            + " x=" + x
                            + " y=" + y
                            + " gSpeed=" + sprite.getGSpeed()
                            + " exitX=" + exitX
                            + " exitY=" + exitY);
                }
            } else {
                groundedNearTopZeroSpeedFrames = 0;
            }

            // Keep this regression scoped to hollow-log exit behavior; beyond this point,
            // AIZ miniboss scripting can legitimately alter camera/movement state.
            if (x >= TOP_EXIT_CHECK_MAX_X) {
                break;
            }
        }

        assertTrue(releasedFromRide, "Expected ride release to occur shortly after top-exit traversal."
                        + " releaseFrame=" + releaseFrame
                        + " exitX=" + exitX
                        + " exitY=" + exitY);
        assertTrue(maxForwardGain >= 48, "Expected continued forward traversal after top exit while holding Right."
                        + " maxForwardGain=" + maxForwardGain
                        + " releaseFrame=" + releaseFrame
                        + " finalCheckedFrame=" + finalCheckedFrame
                        + " finalCheckedX=0x" + Integer.toHexString(finalCheckedX)
                        + " exitX=" + exitX
                        + " exitY=" + exitY);
    }

    // ========== Hollow Log Helpers ==========

    private static byte[][] snapshotTreeColumns(Map map) {
        byte[][] values = new byte[9][2];
        for (int row = 0; row < values.length; row++) {
            values[row][0] = map.getValue(0, 0x59, row);
            values[row][1] = map.getValue(0, 0x5A, row);
        }
        return values;
    }

    private void applyDebugStartState() {
        Camera camera = fixture.camera();
        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed(START_X_SPEED);
        sprite.setYSpeed(START_Y_SPEED);
        sprite.setGSpeed(START_G_SPEED);
        sprite.setAngle(START_ANGLE);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);

        camera.updatePosition(true);
        sprite.updateSensors(sprite.getX(), sprite.getY());
    }

    private boolean runUntilTreeRevealClears() {
        boolean revealStarted = false;
        for (int frame = 0; frame < TIMEOUT_FRAMES * 2; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            int revealCounter = AizHollowTreeObjectInstance.getTreeRevealCounter();
            if (revealCounter > 0) {
                revealStarted = true;
            }
            if (revealStarted && revealCounter == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean runUntilExitHeightHoldingRight() {
        for (int frame = 0; frame < TIMEOUT_FRAMES * 2; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            if (sprite.getY() <= TARGET_EXIT_Y) {
                return true;
            }
        }
        return false;
    }

    private void reloadAizAct1AndApplyDebugStart() {
        try {
            AizHollowTreeObjectInstance.resetTreeRevealCounter();
            LevelManager levelManager = GameServices.level();
            levelManager.getObjectManager().reset(0);
            levelManager.loadZoneAndAct(ZONE_AIZ, ACT_1);
            GroundSensor.setLevelManager(levelManager);
            fixture.camera().updatePosition(true);
            applyDebugStartState();
        } catch (Exception e) {
            throw new AssertionError("Failed to reload AIZ1 for repeat traversal.", e);
        }
    }

    private static int[] snapshotRevealWindow(LevelManager levelManager) {
        final int startX = 0x2C80;
        final int endXExclusive = 0x2D80;
        final int startY = 0x280;
        final int endYExclusive = 0x480;
        final int step = 8;

        int widthTiles = (endXExclusive - startX) / step;
        int heightTiles = (endYExclusive - startY) / step;
        int[] descriptors = new int[widthTiles * heightTiles];
        int index = 0;

        for (int y = startY; y < endYExclusive; y += step) {
            for (int x = startX; x < endXExclusive; x += step) {
                descriptors[index++] = readForegroundDescriptorCompat(levelManager, x, y);
            }
        }
        return descriptors;
    }

    private static int readForegroundDescriptorCompat(LevelManager levelManager, int worldX, int worldY) {
        try {
            Method fromTilemap = LevelManager.class.getMethod(
                    "getForegroundTileDescriptorFromTilemapAtWorld",
                    int.class,
                    int.class);
            return (int) fromTilemap.invoke(levelManager, worldX, worldY);
        } catch (NoSuchMethodException ignored) {
            // Older branches may not expose foreground descriptor APIs used by this regression test.
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed reading foreground tile descriptor via tilemap API.", e);
        }

        try {
            Method resolved = LevelManager.class.getMethod(
                    "getForegroundTileDescriptorAtWorld",
                    int.class,
                    int.class);
            return (int) resolved.invoke(levelManager, worldX, worldY);
        } catch (NoSuchMethodException ignored) {
            // Fall through to map-chunk snapshot fallback.
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed reading foreground tile descriptor via resolved API.", e);
        }

        Map map = levelManager.getCurrentLevel().getMap();
        int chunkX = Math.clamp(worldX >> 7, 0, map.getWidth() - 1);
        int chunkY = Math.clamp(worldY >> 7, 0, map.getHeight() - 1);
        return map.getValue(0, chunkX, chunkY) & 0xFF;
    }
}


