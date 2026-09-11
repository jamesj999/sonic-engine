package com.openggf.level.objects;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.rules.GameRules;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance;
import com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance;
import com.openggf.game.sonic3k.objects.CnzTrapDoorInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.physics.Sensor;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;
import com.openggf.tests.TestEnvironment;
import java.lang.reflect.Field;
import java.util.List;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSolidObjectManager {

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void tearDownRestoresSonic2BootstrapAfterSonic1SolidCoverage() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());

        tearDown();

        assertInstanceOf(Sonic2GameModule.class, GameModuleRegistry.getBootstrapDefault(),
                "a following class must not inherit Sonic 1 unified-collision rules");
    }

    @Test
    public void manualCheckpointObjectSeesStandingStateInsideUpdate() {
        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) 83);
        player.setYSpeed((short) 0x100);
        player.setAir(true);

        ManualCheckpointProbeObject object = new ManualCheckpointProbeObject(100, 100);
        ObjectManager manager = buildManager(object);

        manager.update(0, player, List.of(), 0, false, true, false);

        assertTrue(object.standingSeenInsideUpdate);
        assertEquals(1, object.manualCheckpointCount);
        assertEquals(0, object.compatibilityCallbackCount);
    }

    @Test
    public void legacyAutoObjectStillReceivesOnePostUpdateCompatibilityCallback() {
        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) 83);
        player.setYSpeed((short) 0x100);
        player.setAir(true);

        AutoCheckpointProbeObject object = new AutoCheckpointProbeObject(100, 100);
        ObjectManager manager = buildManager(object);

        manager.update(0, player, List.of(), 0, false, true, false);

        assertEquals(1, object.compatibilityCallbackCount);
    }

    @Test
    public void nativeFloorReleaseDetachesRideAtNonPositiveDistance() {
        TestPlayableSprite player = createStandingProbePlayer();
        TestSolidObject object = new TestSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);
        manager.forceRidingObjectForBootstrap(player, object);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(
                            player.getCentreX(), player.getCentreY(), player.getYRadius()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 1));

            assertTrue(manager.checkPlayerReleaseFromObjectFloor(player));
        }

        assertFalse(manager.isRidingObject(player));
        assertFalse(manager.hasObjectStandingBit(player, object));
        assertFalse(player.isOnObject());
        assertTrue(player.getAir());
    }

    @Test
    public void nativeFloorReleaseKeepsRideAtPositiveDistance() {
        TestPlayableSprite player = createStandingProbePlayer();
        TestSolidObject object = new TestSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);
        manager.forceRidingObjectForBootstrap(player, object);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(
                            player.getCentreX(), player.getCentreY(), player.getYRadius()))
                    .thenReturn(new TerrainCheckResult(1, (byte) 0, 1));

            assertFalse(manager.checkPlayerReleaseFromObjectFloor(player));
        }

        assertTrue(manager.isRidingObject(player));
        assertTrue(manager.hasObjectStandingBit(player, object));
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
    }

    @Test
    public void topSolidHistoryProviderUsesPreviousPlayerPositionForNewLanding() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        HistoryTopSolidObject object = new HistoryTopSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setCentreX((short) 100);
        int rejectCentreY = 100 - 4 - params.airHalfHeight() - player.getYRadius() - 1;
        int contactCentreY = rejectCentreY + 2;
        player.setCentreY((short) rejectCentreY);
        player.endOfTick();

        player.setCentreY((short) contactCentreY);
        player.setYSpeed((short) 0x100);
        player.setAir(true);
        player.endOfTick();

        manager.update(0, player, List.of(), 0, false, true, false);

        assertTrue(player.getAir());
        assertFalse(player.isOnObject());

        player.setCentreY((short) (contactCentreY + 1));
        player.endOfTick();

        manager.update(0, player, List.of(), 0, false, true, false);

        assertFalse(player.getAir());
        assertTrue(player.isOnObject());
        assertEquals(0, player.getYSpeed());
    }

    @Test
    public void compatibilityAutoObjectPreservesPerPlayerCallbackTimingWithSidekickMutation() {
        TestPlayableSprite player = createStandingProbePlayer();
        TestPlayableSprite sidekick = createStandingProbePlayer();

        FirstContactDisablesSolidityProbeObject object =
                new FirstContactDisablesSolidityProbeObject(100, 100);
        ObjectManager manager = buildManager(object);

        manager.update(0, player, List.of(sidekick), 0, false, true, false);

        assertEquals(1, object.compatibilityCallbackCount);
        assertEquals(player, object.firstCallbackPlayer);
        assertTrue(object.mainPlayerSawCallback);
        assertFalse(object.sidekickSawCallback);
    }

    @Test
    public void testStandingContactOnFlatObject() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - player.getYRadius();
        player.setCentreY((short) centreY);
        player.setYSpeed((short) 0);

        assertTrue(manager.hasStandingContact(player));
    }

    @Test
    public void walkingPastRidingBoundsClearsOnObjectAndSetsAirEvenWithLatchedInteract() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - player.getYRadius();
        player.setCentreY((short) centreY);
        player.setYSpeed((short) 0);
        player.setAir(true);

        manager.updateSolidContacts(player);
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());

        player.setLatchedSolidObject(object.getSpawn().objectId(), object);
        player.setCentreX((short) (100 + (params.halfWidth() * 2) + 1));

        manager.updateSolidContacts(player);

        assertFalse(player.isOnObject(),
                "S3K SolidObjectFull_1P/SolidObjectTop_1P clear Status_OnObj when riding bounds are left");
        assertTrue(player.getAir(),
                "S3K SolidObjectFull_1P/SolidObjectTop_1P set Status_InAir on riding walkoff");
        assertEquals(object.getSpawn().objectId(), player.getLatchedSolidObjectId(),
                "The ROM interact slot can remain latched after Status_OnObj is cleared");
    }

    @Test
    public void offscreenS3kCpuSidekickSkipsFullSolidBeforeRidingBoundsUnseat() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.useGameRules(GameRules.SONIC_3K);
        sidekick.setCpuControlled(true);
        sidekick.setRenderFlagOnScreen(true);
        sidekick.setWidth(20);
        sidekick.setHeight(20);
        sidekick.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - sidekick.getYRadius();
        sidekick.setCentreY((short) centreY);
        sidekick.setYSpeed((short) 0);
        sidekick.setAir(true);

        manager.updateSolidContacts(sidekick);
        assertTrue(sidekick.isOnObject());
        assertFalse(sidekick.getAir());

        sidekick.setRenderFlagOnScreen(false);
        sidekick.setCentreX((short) (100 - params.halfWidth() - 1));

        manager.updateSolidContacts(sidekick);

        assertTrue(sidekick.isOnObject(),
                "S3K SolidObjectFull gates offscreen Player_2 before the standing bounds branch");
        assertFalse(sidekick.getAir(),
                "Skipping the offscreen Player_2 pass preserves the existing ROM standing state");
    }

    @Test
    public void offscreenS3kCpuSidekickSkipsFullSolidBeforeAirUnseat() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.useGameRules(GameRules.SONIC_3K);
        sidekick.setCpuControlled(true);
        sidekick.setRenderFlagOnScreen(true);
        sidekick.setWidth(20);
        sidekick.setHeight(20);
        sidekick.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - sidekick.getYRadius();
        sidekick.setCentreY((short) centreY);
        sidekick.setYSpeed((short) 0);
        sidekick.setAir(true);

        manager.updateSolidContacts(sidekick);
        assertTrue(sidekick.isOnObject());
        assertFalse(sidekick.getAir());
        assertTrue(manager.isRidingObject(sidekick));

        sidekick.setRenderFlagOnScreen(false);
        sidekick.setOnObject(true);
        sidekick.setAir(true);

        manager.updateSolidContacts(sidekick);

        assertTrue(sidekick.isOnObject(),
                "S3K SolidObjectFull gates offscreen Player_2 before the in-air riding unseat branch");
        assertTrue(sidekick.getAir(),
                "Skipping the offscreen Player_2 pass preserves the current ROM Status_InAir bit");
        assertTrue(manager.isRidingObject(sidekick),
                "The latched ride persists when ROM skips the offscreen Player_2 solid pass");
    }

    @Test
    public void inlineOffscreenS3kCpuSidekickSkipsFullSolidBeforeAirUnseat() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);
        TestPlayableSprite main = new TestPlayableSprite((short) 0, (short) 0);
        main.setCentreX((short) 0);
        main.setCentreY((short) 0);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.useGameRules(GameRules.SONIC_3K);
        sidekick.setCpuControlled(true);
        sidekick.setRenderFlagOnScreen(true);
        sidekick.setWidth(20);
        sidekick.setHeight(20);
        sidekick.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - sidekick.getYRadius();
        sidekick.setCentreY((short) centreY);
        sidekick.setYSpeed((short) 0);
        sidekick.setAir(true);

        manager.update(0, main, List.of(sidekick), 0, false, true, false);
        assertTrue(sidekick.isOnObject());
        assertFalse(sidekick.getAir());
        assertTrue(manager.isRidingObject(sidekick));

        sidekick.setRenderFlagOnScreen(false);
        sidekick.setOnObject(true);
        sidekick.setAir(true);

        manager.update(1, main, List.of(sidekick), 0, false, true, false);

        assertTrue(sidekick.isOnObject(),
                "Inline object updates must mirror the S3K offscreen Player_2 gate before air unseat");
        assertTrue(sidekick.getAir(),
                "Skipping the inline offscreen Player_2 pass preserves Status_InAir");
        assertTrue(manager.isRidingObject(sidekick),
                "Inline processing keeps the ride latched when ROM skips the offscreen Player_2 solid pass");
    }

    @Test
    public void offscreenS2AirborneSidekickRideLatchIsNotGroundingSupport() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new StaleStandingBitFullSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.useGameRules(GameRules.SONIC_2);
        sidekick.setCpuControlled(true);
        sidekick.setRenderFlagOnScreen(true);
        sidekick.setWidth(20);
        sidekick.setHeight(20);
        sidekick.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - sidekick.getYRadius();
        sidekick.setCentreY((short) centreY);
        sidekick.setYSpeed((short) 0);
        sidekick.setAir(true);

        manager.updateSolidContacts(sidekick);
        assertTrue(manager.isRidingObject(sidekick));
        assertTrue(manager.hasGroundingObjectSupport(sidekick));

        sidekick.setOnObject(true);
        sidekick.setAir(true);
        sidekick.setYSpeed((short) 0x0038);
        assertFalse(manager.hasGroundingObjectSupport(sidekick),
                "A stale airborne SolidObject standing bit is not active grounding support because "
                        + "the helper returns before SolidObject_cont (s2.asm:35028-35046)");

        sidekick.setRenderFlagOnScreen(false);
        sidekick.setOnObject(true);
        sidekick.setAir(true);
        sidekick.setYSpeed((short) 0x0038);

        assertTrue(manager.isRidingObject(sidekick),
                "S2 SolidObject preserves the stale P2 ride latch when render_flags.on_screen is clear");
        assertFalse(manager.hasGroundingObjectSupport(sidekick),
                "The preserved offscreen P2 latch is not active grounding support because "
                        + "SolidObject returns before SolidObject_cont (s2.asm:35022-35031)");

        manager.updateSolidContacts(sidekick);

        assertTrue(sidekick.isOnObject(),
                "Skipping offscreen P2 SolidObject preserves Status_OnObj");
        assertTrue(sidekick.getAir(),
                "Skipping offscreen P2 SolidObject preserves Status_InAir");
        assertTrue(manager.isRidingObject(sidekick));
        assertFalse(manager.hasGroundingObjectSupport(sidekick));
        assertEquals(0x0038, sidekick.getYSpeed() & 0xFFFF,
                "No SolidObject_cont support pass should zero the airborne sidekick's y_vel");
    }

    @Test
    public void s3kNormalSolidSupportClearsStaleObjectControlBitSixWallSuppression() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(38);
        player.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - player.getYRadius();
        player.setCentreY((short) centreY);
        player.setYSpeed((short) 0);
        player.setAir(true);
        player.setSuppressGroundWallCollision(true);
        player.setObjectControlled(false);

        manager.updateSolidContacts(player);

        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
        assertFalse(player.isObjectControlled());
        assertFalse(player.isSuppressGroundWallCollision(),
                "Normal S3K SolidObject support must not preserve stale object_control bit-6 wall suppression");
    }

    // ROM: SolidObjectFull's top-slice clamp (sonic3k.asm loc_1E154:41611) reads
    // width_pixels(a0) for its landing X gate, which is NOT always the collision
    // half-width minus $B. The MHZ1 cutscene button passes collision d1 = $1B but
    // has width_pixels = $80, so a rising rolling-jump graze at the exact right
    // edge (relX == width*2) must still receive the top-slice lift (MHZ1 trace
    // F966). isWithinTopLandingWidth must honor an explicitly wider configured
    // landing width, and the S3K upward-velocity lift must fire without latching a
    // ride (loc_1E154 writes y_pos before tst.w y_vel / bmi loc_1E198).
    @Test
    public void s3kRisingGrazeGetsTopSliceLiftWhenLandingWidthWiderThanCollisionBox() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(27, 4, 5);
            // topLandingHalfWidth 128 mirrors the button's ROM width_pixels = $80,
            // wider than the $1B (27) side-collision half-width.
            TestSolidObject object = new TestSolidObject(100, 100, params, false, 128);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.useGameRules(GameRules.SONIC_3K);
            player.setWidth(20);
            player.setHeight(38);
            int maxTop = params.airHalfHeight() + player.getYRadius();
            // Right-edge graze: relX = 2*halfWidth - 1 (just inside), distY = 0.
            player.setCentreX((short) (100 - params.halfWidth() + (params.halfWidth() * 2 - 1)));
            player.setCentreY((short) (100 - 4 - maxTop));
            player.setYSpeed((short) -0x100);
            player.setAir(true);

            int startCentreY = player.getCentreY();
            manager.updateSolidContacts(player);

            assertEquals(startCentreY + 3, player.getCentreY(),
                    "loc_1E154 lifts the rising graze by +3 (distY=0) even at the right edge");
            assertEquals((short) -0x100, player.getYSpeed(),
                    "Rising player keeps its upward velocity (tst.w y_vel / bmi loc_1E198 skips the ride)");
            assertFalse(player.isOnObject(),
                    "loc_1E154 returns d4=0 for the rising graze: no RideObject_SetRide latch");
            assertTrue(player.getAir(),
                    "Rising graze must not be grounded by the top-slice lift");
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void s3kRisingGrazeAtRightEdgeIsNotLiftedWithDefaultNarrowLandingWidth() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(27, 4, 5);
            // Default landing width: engine narrows to width_pixels = collision d1 - $B,
            // which rejects the exact-edge graze (this is the pre-button-fix behavior
            // and must be preserved for ordinary full solids that pass d1 = obActWid + $B).
            TestSolidObject object = new TestSolidObject(100, 100, params, false);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.useGameRules(GameRules.SONIC_3K);
            player.setWidth(20);
            player.setHeight(38);
            int maxTop = params.airHalfHeight() + player.getYRadius();
            player.setCentreX((short) (100 - params.halfWidth() + (params.halfWidth() * 2 - 1)));
            player.setCentreY((short) (100 - 4 - maxTop));
            player.setYSpeed((short) -0x100);
            player.setAir(true);

            int startCentreY = player.getCentreY();
            manager.updateSolidContacts(player);

            assertEquals(startCentreY, player.getCentreY(),
                    "Narrow default landing width rejects the right-edge graze: no lift");
            assertFalse(player.isOnObject());
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void walkingToExactRightRidingBoundaryClearsOnObjectWithoutStickyExtension() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - player.getYRadius();
        player.setCentreY((short) centreY);
        player.setYSpeed((short) 0);
        player.setAir(true);

        manager.updateSolidContacts(player);
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());

        player.setCentreX((short) (100 + params.halfWidth()));

        manager.updateSolidContacts(player);

        assertFalse(player.isOnObject(),
                "S3K SolidObjectFull_1P/SolidObjectTop_1P treat relX == width*2 as outside ride bounds");
        assertTrue(player.getAir(),
                "Leaving exact ride bounds sets Status_InAir instead of extending support with a sticky buffer");
    }

    @Test
    public void testHeadroomDistanceUpward() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 70, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) 100);

        int distance = manager.getHeadroomDistance(player, 0x00);

        assertEquals(3, distance);
    }

    @Test
    public void testCollapsingLedgeUsesSlopedSurfaceProfile() {
        ObjectSpawn spawn = new ObjectSpawn(100, 100, 0x1A, 0, 0, false, 0);
        Sonic1CollapsingLedgeObjectInstance ledge = new Sonic1CollapsingLedgeObjectInstance(spawn);
        ObjectManager manager = buildManager(ledge);
        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0);

        // ROM: SlopeObject uses absolute slope â€” surfaceY = objectY - slopeSample.
        // Stable centreY on surface = surfaceY - yRadius - 1 (where Platform3's +3 offset
        // cancels the +4 in the relY formula, leaving distY=3, newY = centreY - 3 + 3 = centreY).

        // Left-side sample (heightmap value 0x20=32): surfaceY=100-32=68, stable centreY=48.
        // Use an interior X so top resolution wins over side resolution.
        player.setCentreX((short) 64);
        player.setCentreY((short) 48);
        manager.updateSolidContacts(player);
        int leftCenterY = player.getCentreY();
        assertEquals(48, leftCenterY);

        // Right-side sample (heightmap value 0x30=48): surfaceY=100-48=52, stable centreY=32.
        player.setAir(true);
        player.setYSpeed((short) 0);
        player.setCentreX((short) 136);
        player.setCentreY((short) 32);
        manager.updateSolidContacts(player);
        int rightCenterY = player.getCentreY();
        assertEquals(32, rightCenterY);

        // Shape must not be flat: right edge is 16px higher than left edge.
        assertEquals(16, leftCenterY - rightCenterY);
    }

    @Test
    public void testCollapsingLedgeFragmentWalkOffWindowRemainsSolid() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(100, 100, 0x1A, 0, 0, false, 0);
        Sonic1CollapsingLedgeObjectInstance ledge = new Sonic1CollapsingLedgeObjectInstance(spawn);

        setPrivateInt(ledge, "routine", 6);
        setPrivateBoolean(ledge, "collapseFlag", true);
        assertTrue(ledge.isSolidFor(null));

        // Disassembly parity: once collapse flag is cleared in routine 6, the ledge no longer
        // runs walk-off collision and should not remain solid.
        setPrivateBoolean(ledge, "collapseFlag", false);
        assertFalse(ledge.isSolidFor(null));
    }

    @Test
    public void collapsingLedgeForcedReleasePublishesRunAsPreviousAnimation() throws Exception {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        ObjectSpawn spawn = new ObjectSpawn(100, 100, 0x1A, 0, 1, false, 0);
        Sonic1CollapsingLedgeObjectInstance ledge = new Sonic1CollapsingLedgeObjectInstance(spawn);
        ObjectManager manager = buildManager(ledge);
        ledge.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return manager;
            }
        });

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.applyCustomRadii(9, 19);
        player.setCentreX((short) 71);
        player.setCentreY((short) 34);
        player.setYSpeed((short) 0x100);
        player.setAir(true);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(5,
                List.of(0x08, 0x09, 0x0A), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(0);
        player.getAnimationManager().update(0);
        player.setMappingFrame(0x0A);
        player.setAnimationFrameIndex(2);
        player.setAnimationTick(5);

        manager.forceRidingObjectForBootstrap(player, ledge);
        assertTrue(player.isOnObject(), "Fixture should establish the collapsing ledge ride");
        setPrivateInt(ledge, "routine", 6);
        setPrivateBoolean(ledge, "collapseFlag", true);
        setPrivateInt(ledge, "collapseDelay", 1);

        manager.update(0, player, List.of(), 1, false, true, true);
        assertFalse(player.isOnObject());
        assertEquals(33, player.getCentreY(),
                "The release checkpoint must apply the final flipped-slope sample before detaching Sonic");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "Ledge_TimeZero writes canonical Run to prev_anim");
        player.getAnimationManager().update(2);
        assertEquals(0x08, player.getMappingFrame(),
                "Walk restarts at its first mapping on the next player slot");
    }

    @Test
    public void testNearTopSideContactDoesNotSetPushingFlag() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        // Left edge of object, near top edge: side graze while walking across tops.
        player.setCentreX((short) 85);
        player.setCentreY((short) 71);

        manager.updateSolidContacts(player);

        assertFalse(player.getPushing());
    }

    @Test
    public void testMidSideContactStillSetsPushingFlag() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        // Left edge of object, deeper than top-edge buffer: should count as push.
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);

        manager.updateSolidContacts(player);

        assertTrue(player.getPushing());
        assertTrue(manager.hasObjectPushingBit(player),
                "SolidObject side contact must retain the native per-player pushing latch");
    }

    @Test
    public void exactHorizontalCentreTieResolvesTowardLeftEdge() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setCentreX((short) 100);
        player.setCentreY((short) 100);

        manager.updateSolidContacts(player);

        assertEquals(84, player.getCentreX(),
                "cmp.w d0,d1 / bhs chooses the left half when player and solid centres are equal");
    }

    @Test
    public void fullSolidSideAirClearsPushWithoutPublishingSonic1NoCollisionWord() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(5, new SpriteAnimationScript(0,
                List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing());
        assertTrue(manager.hasObjectPushingBit(player));

        // Same horizontal side, but within four pixels of the top edge:
        // Solid_SideAir calls Solid_NotPushing rather than Solid_NoCollision.
        player.setCentreY((short) 71);
        manager.updateSolidContacts(player);

        assertFalse(player.getPushing());
        assertFalse(manager.hasObjectPushingBit(player));
        assertEquals(5, player.getAnimationId(),
                "Solid_NotPushing must bypass the retail S1 Solid_NoCollision word write");
        assertEquals(5, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void multiPieceSideContactClearsPushingWhenThisObjectNoLongerPushes() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing());
        assertTrue(object.lastPushingState);

        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertFalse(player.getPushing(),
                "SolidObject_TestClearPush clears Status_Push only for the object that owned the push bit");
        assertFalse(manager.hasObjectPushingBit(player),
                "Clearing the owning SolidObject contact must release its pushing latch");
        assertFalse(object.lastPushingState);
        assertEquals(2, object.pushingStateChanges);
    }

    @Test
    public void preservedMultiPieceRidingPushDoesNotPublishReleaseAnimationWord() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        PreservingRidingPushMultiPieceSolidObject object =
                new PreservingRidingPushMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_2);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x0F, 0x10, 0x11, 0x12), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(0);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing());
        assertTrue(manager.hasObjectPushingBit(player));
        player.captureOnObjectAtFrameStart();

        player.setCentreX((short) 100);
        player.setCentreY((short) 82);
        player.setYSpeed((short) 0);
        manager.updateSolidContacts(player);

        assertTrue(player.isOnObject(), "fixture should produce a standing sibling contact");
        assertTrue(player.getPushing(), "the provider-preserved native push latch stays visible");
        assertTrue(manager.hasObjectPushingBit(player));
        assertEquals(0, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "preserving a native push latch must not publish the Run prev_anim sentinel");
    }

    @Test
    public void preservedMultiPieceRidingPushIsNotConsultedWithoutLivePushLatch() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        PreservingRidingPushMultiPieceSolidObject object =
                new PreservingRidingPushMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setPushing(false);
        player.captureOnObjectAtFrameStart();
        player.setCentreX((short) 100);
        player.setCentreY((short) 83);
        player.setYSpeed((short) 0);

        manager.updateSolidContacts(player);

        assertTrue(player.isOnObject(), "fixture should establish a standing multi-piece contact");
        assertEquals(0, object.preserveChecks,
                "same-pass collision geometry cannot invent a live push latch");
    }

    @Test
    public void preservedMultiPieceRidingPushUsesLiveEarlierSlotLatch() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        PreservingRidingPushMultiPieceSolidObject object =
                new PreservingRidingPushMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setPushing(false);
        player.captureOnObjectAtFrameStart();
        assertFalse(player.getPushingAtFrameStart());

        // Model an earlier SolidObject SST setting Status_Push before this
        // riding provider's later slot observes the same live player byte.
        player.setPushing(true);
        player.setCentreX((short) 100);
        player.setCentreY((short) 83);
        player.setYSpeed((short) 0);

        manager.updateSolidContacts(player);

        assertTrue(player.isOnObject(), "fixture should establish a standing multi-piece contact");
        assertTrue(player.getPushing(), "the later riding slot must preserve the live earlier-slot push");
        assertTrue(manager.hasObjectPushingBit(player));
        assertTrue(object.preserveChecks > 0,
                "the provider must observe the live SST bit even when the frame-start snapshot was clear");
    }

    @Test
    public void sonic1SolidPushReleasePublishesWalkWithRunPreviousAnimation() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x08, 0x09), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(5, new SpriteAnimationScript(0,
                List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing(), "Fixture should establish the object's native push latch");

        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertFalse(player.getPushing());
        assertEquals(0, player.getAnimationId(),
                "S1 Solid_NoCollision writes Walk to the raw animation byte");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "The adjacent prev_anim byte must receive canonical Run");
        player.getAnimationManager().update(1);
        assertEquals(0x08, player.getMappingFrame(),
                "Walk must restart at its first mapping on the next player animation pass");
    }

    @Test
    public void sonic2SolidPushReleasePublishesNativeWalkRunAnimationWord() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_2);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(5, new SpriteAnimationScript(0,
                List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertEquals(0, player.getAnimationId(),
                "S2 SolidObject_TestClearPush writes Walk to anim");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "S2 SolidObject_TestClearPush writes Run to the adjacent prev_anim byte");
    }

    @Test
    public void sonic2SolidPushReleasePreservesRollAnimation() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_2);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(2, new SpriteAnimationScript(0,
                List.of(0x02), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(2);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertEquals(2, player.getAnimationId(),
                "SolidObject_TestClearPush branches around the Walk/Run word write for Roll");
        assertEquals(2, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic3kSolidPushReleasePublishesNativeWalkRunAnimationWord() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertEquals(0, player.getAnimationId(),
                "S3K SolidObjectFull loc_1E0A2 writes Walk to anim");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "S3K writes Run to the adjacent prev_anim byte");
    }

    @Test
    public void sonic3kOffscreenSolidPushReleasePublishesNativeWalkRunAnimationWord() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        ToggleOnScreenSolidObject object = new ToggleOnScreenSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.update(0, player, List.of(), 0, false, true, false);
        assertTrue(player.getPushing(), "the visible solid should own the native push latch");

        object.withinSolidContactBounds = false;
        manager.update(0, player, List.of(), 1, false, true, false);

        assertFalse(player.getPushing());
        assertEquals(0, player.getAnimationId(),
                "S3K offscreen SolidObject_TestClearPush writes Walk to anim");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "the offscreen release writes Run to the adjacent prev_anim byte");
    }

    @Test
    public void sonic3kSolidPushReleasePreservesSpindashAnimation() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(9, new SpriteAnimationScript(0,
                List.of(0x09), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(9);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertEquals(9, player.getAnimationId(),
                "S3K loc_1E0A2 branches around the word write for Spindash");
        assertEquals(9, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic1ObjectPushLatchPublishesWhilePlayerPushIsPaired() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(5, new SpriteAnimationScript(0,
                List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing());
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertEquals(0, player.getAnimationId(),
                "The paired object/player Status_Push bits own S1 Solid_NoCollision");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic1PreviousObjectCheckpointPublishesAfterMovementClearsLivePush() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        ManualPushCheckpointObject object = new ManualPushCheckpointObject(100, 100, false);
        ObjectManager manager = buildManager(object);
        TestPlayableSprite player = createSonic1WalkPushPlayer();

        manager.update(0, player, List.of(), 0, false, true, false);
        assertTrue(player.getPushing());

        // MoveLeft/MoveRight can clear the live player bit before the later
        // object slot. The paired frame-start bit plus this object's previous
        // checkpoint remains the bounded native owner.
        player.captureOnObjectAtFrameStart();
        assertTrue(player.getPushingAtFrameStart());
        player.setPushing(false);
        player.setAnimationId(5);
        player.setCentreX((short) 40);
        manager.update(0, player, List.of(), 1, false, true, false);

        assertEquals(0, player.getAnimationId());
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic1BatchedPreviousObjectPassPublishesAfterMovementClearsLivePush() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestMultiPieceSolidObject object = new TestMultiPieceSolidObject(
                100, 100, new SolidObjectParams(16, 8, 8));
        ObjectManager manager = buildManager(object);
        TestPlayableSprite player = createSonic1WalkPushPlayer();

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing());

        // The legacy S1 UNIFIED post-movement resolver has no inline checkpoint
        // callback, but its per-object latch still represents the immediately
        // previous SolidObject pass. Pair it with frame-start Status_Push just as
        // the inline resolver does for Solid_NoCollision.
        player.captureOnObjectAtFrameStart();
        player.setPushing(false);
        player.setAnimationId(5);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player, true, false);

        assertFalse(player.getPushing());
        assertEquals(0, player.getAnimationId());
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic1AirborneMonitorPushReleaseBypassesAnimationWord() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SolidObjectParams params = new SolidObjectParams(0x1A, 0x0F, 0x10);
        TestMultiPieceSolidObject object = new ProfileBackedMultiPieceSolidObject(
                100, 100, params, SolidRoutineProfile.monitorSolid(0, false));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 75);
        player.setCentreY((short) 100);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x08, 0x09), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(5, new SpriteAnimationScript(0,
                List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(5);
        player.getAnimationManager().update(0);

        manager.updateSolidContacts(player);
        assertTrue(player.getPushing(), "Fixture should establish the monitor's native push latch");

        player.setAir(true);
        player.setCentreX((short) 40);
        manager.updateSolidContacts(player);

        assertFalse(player.getPushing());
        assertFalse(manager.hasObjectPushingBit(player));
        assertEquals(5, player.getAnimationId(),
                "Mon_Solid airborne release branches directly to stoppushing without writing Walk");
        assertEquals(5, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void sonic1PersistentNativeLatchPublishesAfterSkippedSolidCheckpoint() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        ManualPushCheckpointObject object = new ManualPushCheckpointObject(100, 100, true);
        ObjectManager manager = buildManager(object);
        TestPlayableSprite player = createSonic1WalkPushPlayer();

        manager.update(0, player, List.of(), 0, false, true, false);
        assertTrue(player.getPushing());

        player.setPushing(false);
        player.setCentreX((short) 40);
        object.skipNextCheckpoint();
        manager.update(0, player, List.of(), 1, false, true, false);
        manager.update(0, player, List.of(), 2, false, true, false);

        assertEquals(0, player.getAnimationId());
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId());
    }

    private static TestPlayableSprite createSonic1WalkPushPlayer() {
        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x08, 0x09), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0,
                List.of(0x0A), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(0);
        player.getAnimationManager().update(0);
        return player;
    }

    @Test
    public void earlierSlotMultiPiecePushSurvivesRiddenPieceShortcut() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        SlotOrderedMultiPieceSolidObject object =
                new SlotOrderedMultiPieceSolidObject(80, 120, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x100);
        player.setCentreX((short) 120);
        player.setCentreY((short) 83);

        manager.updateSolidContacts(player);
        assertTrue(player.isOnObject());
        assertEquals(1, object.lastStandingPieceIndex);
        assertFalse(player.getPushing());

        object.setPieceX(0, 135);
        object.setPieceY(0, 96);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);

        manager.updateSolidContacts(player);

        assertTrue(object.piece0Contacted,
                "Earlier piece should contact before the ridden piece shortcut; player="
                        + player.getCentreX() + "," + player.getCentreY());
        assertTrue(object.piece0Pushed,
                "Earlier piece contact should push; standing=" + object.piece0Standing
                        + " side=" + object.piece0Side);
        assertTrue(player.getPushing(),
                "Earlier ROM-slot side push must survive the later ridden-piece shortcut");
        assertTrue(object.lastPushingState);
    }

    @Test
    public void airborneStaleMultiPieceRiderKeepsEarlierSiblingSidePush() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        SlotOrderedMultiPieceSolidObject object =
                new SlotOrderedMultiPieceSolidObject(80, 120, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.setCpuControlled(true);
        sidekick.setWidth(20);
        sidekick.setHeight(20);
        sidekick.setAir(true);
        sidekick.setYSpeed((short) 0x100);
        sidekick.setCentreX((short) 120);
        sidekick.setCentreY((short) 83);

        manager.updateSolidContacts(sidekick);
        assertTrue(manager.isRidingObject(sidekick));
        assertEquals(1, object.lastStandingPieceIndex);

        object.setPieceX(0, 135);
        object.setPieceY(0, 96);
        sidekick.setAir(true);
        sidekick.setOnObject(false);
        sidekick.setXSpeed((short) 0x100);
        sidekick.setGSpeed((short) 0x100);

        manager.updateSolidContacts(sidekick);

        assertTrue(object.piece0Contacted,
                "ROM Obj70 sibling slots still run SolidObject_cont before the stale ridden tooth returns");
        assertTrue(object.piece0Side,
                "Earlier sibling contact should stay on the side-contact path while the ridden tooth clears d6");
        assertFalse(object.piece0Pushed,
                "Airborne SolidObject side correction moves the player but does not set Status_Push");
        assertFalse(sidekick.getCentreX() == 120,
                "The earlier sibling side correction must not be suppressed by a different piece's stale standing bit");
    }

    @Test
    public void multiPieceRiderCarryDoesNotReapplyNewLandingSnapOnSamePiece() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        MutableMultiPieceSolidObject object = new MutableMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(true);
        player.setYSpeed((short) 0x100);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - params.groundHalfHeight() - player.getYRadius()));

        manager.updateSolidContacts(player);
        assertTrue(player.isOnObject());
        assertEquals(100 - params.groundHalfHeight() - player.getYRadius() - 1,
                player.getCentreY(),
                "Initial SolidObject_Landed contact includes the ROM subq #1 snap");

        object.setY(104);
        manager.updateSolidContacts(player);

        assertEquals(104 - params.groundHalfHeight() - player.getYRadius(),
                player.getCentreY(),
                "Continued multi-piece riding must use MvSonicOnPtfm and skip the same piece's new-landing snap");
    }

    @Test
    public void multiPieceSlopedSolidUsesSampledSurfaceForNewLanding() {
        SolidObjectParams params = new SolidObjectParams(16, 0, 0);
        TestSlopedMultiPieceSolidObject object = new TestSlopedMultiPieceSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x100);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - 8 - player.getYRadius() - 1));

        manager.updateSolidContacts(player);

        assertFalse(player.getAir(),
                "Multi-piece sloped solids must use SolidObjectTopSloped2 surfaceY = pieceY - sample");
        assertTrue(player.isOnObject());
        assertEquals(100 - 8 - player.getYRadius() - 1, player.getCentreY());
    }

    @Test
    public void cutsceneKnuxCnz2WallSidePushesPlayerBackToItsLeftFace() {
        // ROM: CutsceneKnux_CNZ2A init spawns ChildObjDat_66560 -> loc_62458, an
        // invisible SolidObjectFull2 wall (d1=$13, d2=$100) positioned at
        // parentX-$20 / parentY-$6C that stops Sonic before he reaches Knuckles
        // (docs/skdisasm/sonic3k.asm:129076, 129175, 134968).
        int wallCenterX = 0x1D00 - 0x20;
        int wallCenterY = 0x0280 - 0x6C;
        com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance wall =
                new com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance(
                        new ObjectSpawn(wallCenterX, wallCenterY, 0, 0, 0, false, 0), null);
        ObjectManager manager = buildManager(wall);
        // ROM: an object skips SolidObject on its first frame (obRender bit 7 not
        // yet set by DisplaySprite). Clear the engine's matching first-frame skip.
        wall.snapshotPreUpdatePosition();
        int halfWidth = wall.getSolidParams().halfWidth();

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);
        // Running rightward into the wall's left face from a few pixels inside it,
        // at ground level (deep in the tall solid so this resolves as a side push).
        player.setCentreX((short) (wallCenterX - halfWidth + 6));
        player.setCentreY((short) 0x0280);

        manager.updateSolidContacts(player);

        assertTrue(player.getPushing(),
                "SolidObjectFull2 side contact must set the pushing flag");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getGSpeed());
        assertEquals(wallCenterX - halfWidth, player.getCentreX(),
                "SolidObject_cont shoves the player back to objX - d1; he cannot pass the wall");
    }

    @Test
    public void optedInFullSolidRightEdgeIsInclusiveLikeRomBhiCheck() {
        SolidObjectParams params = new SolidObjectParams(19, 14, 15);
        TestSolidObject object = new InclusiveRightEdgeSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(false);
        player.setXSpeed((short) -0x100);
        player.setGSpeed((short) -0x100);
        player.setCentreX((short) (100 + params.halfWidth()));
        player.setCentreY((short) 100);

        manager.updateSolidContacts(player);

        assertTrue(player.getPushing(),
                "SolidObject_cont uses cmp/bhi, so relX == width*2 is still a side contact");
        assertEquals(100 + params.halfWidth(), player.getCentreX(),
                "Inclusive exact-edge contact has d0 == 0 in SolidObject_cont and must not shove X by 1px");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    public void cnzCylinderExactRightEdgeRetainsGroundPushWhileMovingAway() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(
                new ObjectSpawn(0x15C0, 0x04FF, 0x47, 0x45, 0, false, 0));
        ObjectManager manager = buildManager(cylinder);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(false);
        player.setXSpeed((short) 0x000C);
        player.setGSpeed((short) 0x000C);
        player.setCentreX((short) (0x15C0 + cylinder.getSolidParams().halfWidth()));
        player.setCentreY((short) 0x052C);

        manager.updateSolidContacts(player);

        assertTrue(player.getPushing(),
                "SolidObject_cont accepts relX == d1*2 and sets Status_Push for a grounded side contact");
        assertEquals(0x000C, player.getXSpeed(),
                "loc_1E042 preserves velocity when the rider is moving away from the cylinder");
        assertEquals(0x000C, player.getGSpeed());
        assertEquals(0x15C0 + cylinder.getSolidParams().halfWidth(), player.getCentreX(),
                "the exact edge has d0=0 and does not manufacture an X correction");
    }

    @Test
    public void zeroDistanceOnlyMotionHookPreservesExactEdgeWithoutChangingNonzeroCorrection() {
        SolidObjectParams params = new SolidObjectParams(19, 14, 15);
        TestSolidObject object = new ZeroDistanceMotionInclusiveRightEdgeSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite exactEdge = new TestPlayableSprite((short) 0, (short) 0);
        exactEdge.setWidth(20);
        exactEdge.setHeight(38);
        exactEdge.setAir(false);
        exactEdge.setXSpeed((short) -0x100);
        exactEdge.setGSpeed((short) -0x100);
        exactEdge.setCentreX((short) (100 + params.halfWidth()));
        exactEdge.setSubpixelRaw(0x8000, 0);
        exactEdge.setCentreY((short) 100);

        manager.updateSolidContacts(exactEdge);

        assertTrue(exactEdge.getPushing());
        assertEquals(100 + params.halfWidth(), exactEdge.getCentreX());
        assertEquals(0x8000, exactEdge.getXSubpixelRaw(),
                "SolidObject_AtEdge d0=0 preserves the native x_pos low word");
        assertEquals(-0x100, exactEdge.getXSpeed());
        assertEquals(-0x100, exactEdge.getGSpeed());

        TestPlayableSprite nonzeroOverlap = new TestPlayableSprite((short) 0, (short) 0);
        nonzeroOverlap.setWidth(20);
        nonzeroOverlap.setHeight(38);
        nonzeroOverlap.setAir(false);
        nonzeroOverlap.setXSpeed((short) 0x100);
        nonzeroOverlap.setGSpeed((short) 0x100);
        nonzeroOverlap.setCentreX((short) (100 + params.halfWidth() - 1));
        nonzeroOverlap.setSubpixelRaw(0x8000, 0);
        nonzeroOverlap.setCentreY((short) 100);

        manager.updateSolidContacts(nonzeroOverlap);

        assertEquals(100 + params.halfWidth(), nonzeroOverlap.getCentreX());
        assertEquals(0, nonzeroOverlap.getXSubpixelRaw(),
                "The zero-distance-only hook must leave ordinary nonzero correction on the shared snap path");
        assertEquals(0x100, nonzeroOverlap.getXSpeed());
        assertEquals(0x100, nonzeroOverlap.getGSpeed());
    }

    @Test
    public void eggPrisonBodyExactLeftEdgeSetsGroundPushWithoutStoppingSpeed() {
        EggPrisonObjectInstance eggPrison = new EggPrisonObjectInstance(
                new ObjectSpawn(0x3202, 0x04C2, 0x3E, 0, 0, false, 0),
                "EggPrison");
        ObjectManager manager = buildManager(eggPrison);
        AbstractObjectInstance.updateCameraBounds(0x315C, 0x0427, 0x315C + 320, 0x0427 + 224, 0);
        eggPrison.snapshotPreUpdatePosition();

        TestPlayableSprite tails = new TestPlayableSprite((short) 0, (short) 0);
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setWidth(20);
        tails.setHeight(38);
        tails.setAir(false);
        tails.setXSpeed((short) 0x0018);
        tails.setGSpeed((short) 0x0018);
        tails.setCentreX((short) 0x31D7);
        tails.setCentreY((short) 0x04D4);

        manager.updateSolidContacts(tails);

        assertTrue(tails.getPushing(),
                "S2 SolidObject_AtEdge sets Status_Push for grounded exact-edge capsule body contact");
        assertEquals(0x0018, tails.getXSpeed() & 0xFFFF,
                "AtEdge uses d0 == 0, so SolidObject must not stop Tails's x_vel");
        assertEquals(0x0018, tails.getGSpeed() & 0xFFFF,
                "AtEdge uses d0 == 0, so SolidObject must not stop Tails's ground_vel");
    }

    @Test
    public void solidRoutineProfileSnapshotsInclusiveRightEdgeBeforeDelegatedHooks() {
        SolidObjectParams params = new SolidObjectParams(19, 14, 15);
        TestSolidObject object = new MutatingInclusiveRightEdgeSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(false);
        player.setXSpeed((short) -0x100);
        player.setGSpeed((short) -0x100);
        player.setCentreX((short) (100 + params.halfWidth()));
        player.setCentreY((short) 100);

        manager.updateSolidContacts(player);

        assertTrue(player.getPushing(),
                "Solid routine profile should capture inclusive-right-edge policy before provider hooks mutate");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    public void solidRoutineProfileControlsMonitorSolidityInContactResolution() {
        SolidObjectParams params = new SolidObjectParams(14, 14, 14);
        TestSolidObject object = new ProfileBackedSolidObject(
                100, 100, params, solidProfile(
                        SolidRoutineKind.MONITOR_SOLID,
                        false,
                        true,
                        0,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(28);
        player.setHeight(38);
        player.setAir(true);
        player.setRolling(true);
        player.setYSpeed((short) 0x100);
        player.setCentreX((short) 100);
        int maxTop = params.airHalfHeight() + player.getYRadius();
        player.setCentreY((short) (100 - 4 - maxTop + 8));

        manager.updateSolidContacts(player);

        assertTrue(player.getAir(),
                "Rolling downward monitor contact is delegated to touch response, not full-solid landing");
        assertFalse(player.isOnObject());
    }

    @Test
    public void solidRoutineProfileControlsTopOnlySideClassification() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new ProfileBackedSolidObject(
                100, 100, params, solidProfile(
                        SolidRoutineKind.TOP_SOLID_ONLY,
                        true,
                        false,
                        0,
                        false,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(false);
        player.setXSpeed((short) 0x100);
        player.setCentreX((short) 85);
        player.setCentreY((short) 81);

        manager.updateSolidContacts(player);

        assertFalse(player.getPushing(),
                "Profile top-only policy must suppress full-solid side pushing even if provider hook disagrees");
    }

    @Test
    public void solidRoutineProfileControlsForceAirOnRideExit() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new ProfileBackedSolidObject(
                100, 100, params, solidProfile(
                        SolidRoutineKind.FULL_SOLID,
                        false,
                        false,
                        0,
                        false,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false));
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - params.groundHalfHeight() - player.getYRadius()));
        player.setYSpeed((short) 0);
        player.setAir(true);

        manager.updateSolidContacts(player);
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());

        player.setCentreX((short) (100 + params.halfWidth()));

        manager.updateSolidContacts(player);

        assertFalse(player.isOnObject());
        assertFalse(player.getAir(),
                "Profile forceAirOnRideExit=false should win over the provider hook default");
    }

    @Test
    public void upwardBottomCollisionPreservesGroundSpeed() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_2);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) -0x400);
        player.setGSpeed((short) 0x0B54);
        player.setCentreX((short) 100);
        player.setCentreY((short) 119);

        manager.updateSolidContacts(player);

        assertEquals(0x0B54, player.getGSpeed() & 0xFFFF);
        assertEquals(0, player.getYSpeed());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    public void upwardBottomCollisionCanClearGroundSpeedPerGameRules() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) -0x400);
        player.setGSpeed((short) 0x0813);
        player.setCentreX((short) 100);
        player.setCentreY((short) 119);

        manager.updateSolidContacts(player);

        assertEquals(0, player.getGSpeed());
        assertEquals(0, player.getYSpeed());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    public void groundedWallModeBottomCollisionPreservesGroundSpeed() {
        SolidObjectParams params = new SolidObjectParams(0x1B, 0x10, 0x11);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(28);
        player.setHeight(38);
        player.setAir(false);
        player.setRolling(true);
        player.setGroundMode(com.openggf.game.GroundMode.RIGHTWALL);
        player.setAngle((byte) 0xC0);
        player.setYSpeed((short) -0x0351);
        player.setGSpeed((short) 0x0351);
        player.setCentreX((short) 101);
        player.setCentreY((short) 129);

        manager.updateSolidContacts(player);

        assertEquals(0, player.getYSpeed());
        assertEquals(0x0351, player.getGSpeed() & 0xFFFF);
        assertFalse(player.getAir());
    }

    @Test
    public void sonic1RollingAirborneFullSolidIgnoresUndersideAtCurrentRadiusBoundary() {
        SolidObjectParams params = new SolidObjectParams(0x2B, 0x48, 0x49);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(28);
        player.setHeight(38);
        player.setAir(true);
        player.setRolling(true);
        player.setYSpeed((short) -0x00F0);
        player.setCentreX((short) 100);
        // S1 SolidObject uses the player's CURRENT obHeight for the full-rect overlap.
        // At deltaY = airHalfHeight + rollingYRadius, ROM remains just outside the box.
        player.setCentreY((short) (100 + params.airHalfHeight() + player.getYRadius()));

        manager.updateSolidContacts(player);

        assertEquals(-0x00F0, player.getYSpeed(),
                "Rolling airborne full-solid overlap should not use standing radius for the underside check");
        assertEquals(100 + params.airHalfHeight() + 14, player.getCentreY());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    public void sonic3kRollingAirborneFullSolidUsesTallerUndersideOverlapWindow() {
        SolidObjectParams params = new SolidObjectParams(0x1B, 8, 0x10);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(28);
        player.setHeight(38);
        player.setAir(true);
        player.setRolling(true);
        player.setYSpeed((short) -0x00F0);
        player.setGSpeed((short) 0x0813);
        player.setCentreX((short) 100);
        // This is the AIZ spring-style boundary: current-radius-only overlap misses it,
        // but the taller S2/S3K underside box should still resolve the bottom hit.
        player.setCentreY((short) (100 + params.airHalfHeight() + player.getYRadius()));

        manager.updateSolidContacts(player);

        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
        assertEquals(100 + params.airHalfHeight() + player.getStandYRadius(), player.getCentreY());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    public void sonic3kAirborneFullSolidSeparatesZeroVelocityBottomOverlap() {
        SolidObjectParams params = new SolidObjectParams(0x1B, 0x40, 0x40);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(28);
        player.setHeight(38);
        player.setAir(true);
        player.setRolling(true);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0x0123);
        player.setCentreX((short) 100);
        player.setCentreY((short) 176);

        manager.updateSolidContacts(player);

        assertEquals(183, player.getCentreY(),
                "S3K loc_1E0E0 separates an airborne bottom overlap even when y_vel is zero");
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    public void testSonic1TopSolidEdgeLandingZoneRejectionAndAcceptance() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(32, 8, 8);
            TestSolidObject object = new TestSolidObject(100, 100, params, true);
            ObjectManager manager = buildManager(object);

            // S1 PlatformObject lands on the caller's d1 directly (obActWid is the
            // platform's own half-width, _incObj/sub PlatformObject & SlopeObject.asm);
            // the -$B narrowing exists only in full-solid Solid_Landed. A player just
            // OUTSIDE the collision edge (X = 100 - 32 - 5 = 63) must be rejected.
            TestPlayableSprite playerOutside = new TestPlayableSprite((short) 0, (short) 0);
            playerOutside.setWidth(20);
            playerOutside.setHeight(20);
            playerOutside.setAir(true);
            playerOutside.setYSpeed((short) 0x100);
            int maxTop = params.groundHalfHeight() + playerOutside.getYRadius();
            int targetDistY = 10;
            int centreY = 100 - 4 - maxTop + targetDistY;
            playerOutside.setCentreX((short) (100 - params.halfWidth() - 5)); // X = 63
            playerOutside.setCentreY((short) centreY);

            manager.updateSolidContacts(playerOutside);

            assertFalse(playerOutside.isOnObject());
            assertTrue(playerOutside.getAir());

            // Player at 5px inside the platform's own half-width (X = 100 - 32 + 5 = 73)
            // is INSIDE the top-solid landing zone. Landing must succeed.
            int landingHalfWidth = params.halfWidth();
            TestPlayableSprite playerInside = new TestPlayableSprite((short) 0, (short) 0);
            playerInside.setWidth(20);
            playerInside.setHeight(20);
            playerInside.setAir(true);
            playerInside.setYSpeed((short) 0x100);
            playerInside.setCentreX((short) (100 - landingHalfWidth + 5)); // X = 84
            playerInside.setCentreY((short) centreY);

            manager.updateSolidContacts(playerInside);

            assertTrue(playerInside.isOnObject());
            assertFalse(playerInside.getAir());
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void cnzTrapDoorSolidObjectTopAcceptsExactSurfaceBoundaryAndLandsOnePixelInside() {
        ObjectSpawn spawn = new ObjectSpawn(100, 100, 0x44, 0, 0, false, 0);
        CnzTrapDoorInstance object = new CnzTrapDoorInstance(spawn);
        ObjectManager manager = buildManager(object);
        object.snapshotPreUpdatePosition();
        SolidObjectParams params = object.getSolidParams();

        TestPlayableSprite exactBoundary = new TestPlayableSprite((short) 0, (short) 0);
        exactBoundary.useGameRules(GameRules.SONIC_3K);
        exactBoundary.setWidth(20);
        exactBoundary.setHeight(38);
        exactBoundary.setAir(true);
        exactBoundary.setYSpeed((short) 0x100);
        exactBoundary.setCentreX((short) 100);
        int maxTop = params.groundHalfHeight() + exactBoundary.getYRadius();
        exactBoundary.setCentreY((short) (100 - 4 - maxTop));
        exactBoundary.resetPositionHistory();

        manager.updateSolidContacts(exactBoundary);

        assertTrue(exactBoundary.isOnObject(),
                "S3K SolidObjectTop accepts the ROM d0 == 0 boundary (sonic3k.asm:41996-42015)");
        assertFalse(exactBoundary.getAir());
        assertEquals(0, exactBoundary.getYSpeed());
        assertEquals(100 - params.groundHalfHeight() - exactBoundary.getYRadius() - 1,
                exactBoundary.getCentreY());

        TestPlayableSprite insideBoundary = new TestPlayableSprite((short) 0, (short) 0);
        insideBoundary.useGameRules(GameRules.SONIC_3K);
        insideBoundary.setWidth(20);
        insideBoundary.setHeight(38);
        insideBoundary.setAir(true);
        insideBoundary.setYSpeed((short) 0x100);
        insideBoundary.setCentreX((short) 100);
        insideBoundary.setCentreY((short) (100 - 4 - maxTop + 1));
        insideBoundary.resetPositionHistory();

        manager.updateSolidContacts(insideBoundary);

        assertTrue(insideBoundary.isOnObject());
        assertFalse(insideBoundary.getAir());
        assertEquals(0, insideBoundary.getYSpeed());
        assertEquals(100 - params.groundHalfHeight() - insideBoundary.getYRadius() - 1,
                insideBoundary.getCentreY());
    }

    @Test
    public void cnzTrapDoorUsesPreviousPlayerPositionForNewTopSolidLanding() {
        ObjectSpawn spawn = new ObjectSpawn(0x1560, 0x0284, 0x44, 0, 0, false, 0);
        CnzTrapDoorInstance object = new CnzTrapDoorInstance(spawn);
        ObjectManager manager = buildManager(object);
        object.snapshotPreUpdatePosition();

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(38);
        player.setAir(true);
        player.setYSpeed((short) 0x07A8);
        player.setCentreX((short) 0x1566);
        player.setCentreY((short) 0x025D);
        player.resetPositionHistory();
        player.setCentreY((short) 0x0264);

        manager.updateSolidContacts(player);

        assertTrue(player.getAir(),
                "Obj_CNZTrapDoor calls SolidObjectTop before Sonic's current-frame movement; "
                        + "the previous y_pos is still above the top boundary "
                        + "(sonic3k.asm:67217-67225,41982-42008)");
        assertFalse(player.isOnObject());
        assertEquals(0x07A8, player.getYSpeed() & 0xFFFF);
        assertEquals(0x0264, player.getCentreY() & 0xFFFF);
    }

    @Test
    public void aizTransitionFloorDelaysSonicExactBoundaryWhileAllowingInsideLanding() {
        AizTransitionFloorObjectInstance floor = new AizTransitionFloorObjectInstance();
        ObjectManager manager = buildManager(floor);
        SolidObjectParams params = floor.getSolidParams();

        TestPlayableSprite sonic = new TestPlayableSprite((short) 0, (short) 0);
        sonic.useGameRules(GameRules.SONIC_3K);
        sonic.setWidth(20);
        sonic.setHeight(38);
        sonic.setAir(false);
        sonic.setYSpeed((short) 0);
        sonic.setCentreX((short) floor.getX());
        int exactBoundaryY = floor.getY() - 4 - params.airHalfHeight() - sonic.getYRadius();
        sonic.setCentreY((short) exactBoundaryY);

        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0, (short) 0);
        sidekick.useGameRules(GameRules.SONIC_3K);
        sidekick.setWidth(20);
        sidekick.setHeight(38);
        sidekick.setAir(false);
        sidekick.setYSpeed((short) 0);
        sidekick.setCentreX((short) floor.getX());
        sidekick.setCentreY((short) (exactBoundaryY + 3));

        for (int i = 0; i < 21; i++) {
            manager.processImmediateInlineSolidCheckpoint(floor, sonic, List.of(sidekick));
            assertFalse(sonic.isOnObject(),
                    "AIZ transition floor exact-boundary checks reject during the fire-refresh window");
            assertEquals(exactBoundaryY, sonic.getCentreY());
            assertTrue(sidekick.isOnObject(),
                    "Inside-boundary landings still follow SolidObjectTop first landing");
        }

        manager.processImmediateInlineSolidCheckpoint(floor, sonic, List.of(sidekick));

        assertTrue(sonic.isOnObject(),
                "AIZ transition floor accepts Sonic after the refresh window reaches SolidObjectTop landing");
        assertEquals(exactBoundaryY + 3, sonic.getCentreY(),
                "SolidObjectTop first landing applies y_pos += d0 + 3 (sonic3k.asm:42013-42015)");
    }

    @Test
    public void testNarrowTopLandingWidthRejectsOuterEdgeStanding() {
        SolidObjectParams params = new SolidObjectParams(0x2B, 0x60, 0x61);
        TestSolidObject object = new TestSolidObject(100, 100, params, false, 0x20);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x100);

        // Inside collision width ($2B), but outside standable width ($20).
        player.setCentreX((short) (100 + 0x28));
        int maxTop = params.groundHalfHeight() + player.getYRadius();
        player.setCentreY((short) (100 - 4 - maxTop + 8));

        manager.updateSolidContacts(player);

        assertFalse(player.isOnObject());
        assertTrue(player.getAir());
    }

    @Test
    public void testTopSolidCanOptIntoFullCollisionLandingWidth() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(0x28, 8, 8);
            TestSolidObject object = new TestSolidObject(100, 100, params, true, null, true);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.setWidth(20);
            player.setHeight(20);
            player.setAir(true);
            player.setYSpeed((short) 0x100);
            player.setCentreX((short) (100 - 35));
            int maxTop = params.groundHalfHeight() + player.getYRadius();
            player.setCentreY((short) (100 - 4 - maxTop + 10));

            manager.updateSolidContacts(player);

            assertTrue(player.isOnObject());
            assertFalse(player.getAir());
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void testNarrowTopLandingWidthStillAllowsCenterStanding() {
        SolidObjectParams params = new SolidObjectParams(0x2B, 0x60, 0x61);
        TestSolidObject object = new TestSolidObject(100, 100, params, false, 0x20);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x100);

        player.setCentreX((short) (100 + 0x18));
        int maxTop = params.groundHalfHeight() + player.getYRadius();
        player.setCentreY((short) (100 - 4 - maxTop + 8));

        manager.updateSolidContacts(player);

        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
    }

    @Test
    public void testRidingStatePersistsWhileInsideCollisionWidth() {
        SolidObjectParams params = new SolidObjectParams(0x2B, 0x60, 0x61);
        TestSolidObject object = new TestSolidObject(100, 100, params, false, 0x20);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x100);

        int maxTop = params.groundHalfHeight() + player.getYRadius();

        // Land first to establish riding state.
        player.setCentreX((short) (100 + 0x10));
        player.setCentreY((short) (100 - 4 - maxTop + 8));
        manager.updateSolidContacts(player);
        assertTrue(manager.isRidingObject(player));

        // Move to X that is still inside the full collision width but outside the
        // narrower top-standing width. ExitPlatform-style continuation should keep
        // the player riding until they actually leave the object's collision box.
        player.setCentreX((short) (100 + 0x28));
        manager.updateSolidContacts(player);

        assertTrue(manager.isRidingObject(player));
    }

    @Test
    public void inlineCheckpointAirborneStaleStandingBitDoesNotRelandSameObject() {
        SolidObjectParams params = new SolidObjectParams(0x2B, 8, 9);
        TestSolidObject object = new StaleStandingBitFullSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        int maxTop = params.airHalfHeight() + player.getYRadius();
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - 4 - maxTop + 8));

        manager.forceRidingObjectForBootstrap(player, object);
        manager.clearRidingObject(player);
        player.setAir(true);
        player.setOnObject(false);
        player.setYSpeed((short) 0x0038);

        manager.processImmediateInlineSolidCheckpoint(object, player, List.of());

        assertTrue(player.getAir(),
                "SolidObjectFull_1P loc_1DC98 clears stale support and returns d4=0 "
                        + "when the object's standing bit is set and the player is airborne "
                        + "(sonic3k.asm:41017-41035)");
        assertFalse(player.isOnObject());
        assertFalse(manager.isRidingObject(player));
        assertEquals(0x0038, player.getYSpeed() & 0xFFFF);
    }

    @Test
    public void batchedAirborneRideExitDoesNotRelandSameObject() {
        SolidObjectParams params = new SolidObjectParams(0x20, 8, 9);
        TestSolidObject object = new TestSolidObject(100, 100, params, true,
                null, true);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 72);
        player.setCentreY((short) (100 - params.groundHalfHeight()
                - player.getYRadius()));
        player.setYSpeed((short) 0);
        manager.forceRidingObjectForBootstrap(player, object);

        // Sonic's own update can set Status_InAir while the previous frame's
        // Status_OnObj/object standing bit is still latched. ExitPlatform sees
        // the airborne bit, clears the ride, and returns through MvSonicOnPtfm2;
        // it does not run PlatformObject again for the same slot this frame.
        player.setAir(true);
        player.setOnObject(true);

        manager.updateSolidContacts(player);

        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
        assertFalse(manager.isRidingObject(player));
    }

    @Test
    public void groundedPrePhysicsStatusClearWaitsForLaterRidingCheckpoint() {
        AutoCheckpointProbeObject clearingObject = new AutoCheckpointProbeObject(60, 100);
        AutoCheckpointProbeObject support = new AutoCheckpointProbeObject(100, 100);
        SolidObjectParams params = support.getSolidParams();
        ObjectManager manager = buildManager(clearingObject);
        manager.addDynamicObject(support);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - params.groundHalfHeight()
                - player.getYRadius()));
        player.setYSpeed((short) 0);
        manager.forceRidingObjectForBootstrap(player, support);

        // A lower SST slot runs after Sonic in the ROM, but before physics in
        // the engine's legacy order. Its grounded Status_OnObj clear must not
        // manufacture InAir before this later support slot executes.
        manager.solidContacts().noteGroundedOnObjectClearedBeforePhysics(player, clearingObject);
        player.setOnObject(false);
        player.setAir(true);

        manager.updateSolidContacts(player);

        assertFalse(player.getAir());
        assertTrue(player.isOnObject());
        assertTrue(manager.isRidingObject(player, support));
    }

    @Test
    public void groundedStatusClearAfterRidingCheckpointIsNotRestored() {
        AutoCheckpointProbeObject support = new AutoCheckpointProbeObject(100, 100);
        AutoCheckpointProbeObject laterClearingObject = new AutoCheckpointProbeObject(140, 100);
        SolidObjectParams params = support.getSolidParams();
        ObjectManager manager = buildManager(support);
        manager.addDynamicObject(laterClearingObject);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_1);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - params.groundHalfHeight()
                - player.getYRadius()));
        player.setYSpeed((short) 0);
        manager.forceRidingObjectForBootstrap(player, support);

        manager.solidContacts().noteGroundedOnObjectClearedBeforePhysics(player, laterClearingObject);
        player.setOnObject(false);
        player.setAir(true);

        manager.updateSolidContacts(player);

        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
        assertFalse(manager.isRidingObject(player));
    }

    @Test
    public void earlierSolidCannotConsumeAnotherObjectsAirborneRidingState() {
        SolidObjectParams supportParams = new SolidObjectParams(0x2B, 0x30, 0x31);
        TestSolidObject earlierSolid = new TestSolidObject(0, 0,
                new SolidObjectParams(16, 8, 8));
        TestSolidObject movingSupport = new OwnCheckpointAirUnseatSolidObject(
                100, 100, supportParams);
        ObjectManager manager = buildManager(earlierSolid);
        manager.addDynamicObject(movingSupport);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(28);
        player.setCentreX((short) 100);
        player.setCentreY((short) (100 - supportParams.groundHalfHeight()
                - player.getYRadius()));
        manager.forceRidingObjectForBootstrap(player, movingSupport);

        player.setAir(true);
        player.setYSpeed((short) 0xF980);
        int jumpCentreY = player.getCentreY();

        manager.processImmediateInlineSolidCheckpoint(earlierSolid, player, List.of());

        assertTrue(manager.isRidingObject(player),
                "An earlier slot cannot consume a different object's native standing bit");
        assertTrue(player.isOnObject());

        manager.processImmediateInlineSolidCheckpoint(movingSupport, player, List.of());

        assertEquals(jumpCentreY, player.getCentreY(),
                "The ridden object's air-unseat must return before a fresh-contact Y snap");
        assertFalse(manager.isRidingObject(player));
        assertFalse(player.isOnObject());
        assertTrue(player.getAir());
    }

    @Test
    public void deferredControllerReleaseDoesNotDetachInterveningSolidLanding() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject formerSupport = new TestSolidObject(100, 100, params);
        TestSolidObject interveningSupport = new TestSolidObject(200, 100, params);
        ObjectManager manager = buildManager(formerSupport);
        manager.addDynamicObject(interveningSupport);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.useGameRules(GameRules.SONIC_3K);
        player.setWidth(20);
        player.setHeight(20);
        manager.forceRidingObjectForBootstrap(player, formerSupport);

        manager.clearRidingObjectAfterControllerAirborneRelease(player, formerSupport);
        player.setCentreX((short) interveningSupport.getX());
        int maxTop = params.airHalfHeight() + player.getYRadius();
        player.setCentreY((short) (interveningSupport.getY() - 4 - maxTop + 8));
        player.setYSpeed((short) 0x0100);
        manager.processImmediateInlineSolidCheckpoint(interveningSupport, player, List.of());

        assertTrue(manager.isRidingObject(player, interveningSupport),
                "An intervening solid slot should establish the replacement ride");
        assertFalse(player.getAir());

        manager.processImmediateInlineSolidCheckpoint(formerSupport, player, List.of());

        assertFalse(manager.hasObjectStandingBit(player, formerSupport),
                "The former support still consumes its deferred native standing bit");
        assertTrue(manager.isRidingObject(player, interveningSupport),
                "The former support slot must not detach a later slot's replacement ride");
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
    }

    @Test
    public void unifiedRideExitClearsOnObjectWithoutForcingAirSameFrame() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(16, 8, 8);
            TestSolidObject object = new TestSolidObject(100, 100, params, true);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.useGameRules(GameRules.SONIC_1);
            player.setWidth(20);
            player.setHeight(20);
            player.setAir(false);
            player.setOnObject(true);
            player.setYSpeed((short) 0);
            player.setCentreX((short) 100);
            player.setCentreY((short) (100 - params.groundHalfHeight() - player.getYRadius()));

            manager.update(0, player, List.of(), 0, false, true, true);

            assertTrue(manager.isRidingObject(player));
            assertTrue(player.isOnObject());
            assertFalse(player.getAir());

            player.setCentreX((short) (100 + params.halfWidth() + 12));
            manager.update(0, player, List.of(), 1, false, true, true);

            assertFalse(manager.isRidingObject(player));
            assertFalse(player.isOnObject());
            assertFalse(player.getAir());
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void testLandingFromAirRollOnObjectAdjustsYWhenUnrolling() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(16, 8, 8);
            TestSolidObject object = new TestSolidObject(100, 100, params);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.setWidth(20);
            player.setHeight(38);
            player.setAir(true);
            player.setYSpeed((short) 0x100);
            player.setRolling(true);
            player.setPushing(true);
            player.setAnimationId(2);
            player.setFlipAngle(0x80);
            player.setFlipType(2);
            player.setFlipTurned(true);
            player.setFlipsRemaining(3);

            // Within top landing window while rolling in air.
            player.setCentreX((short) 100);
            int rollingRadius = player.getYRadius(); // 14
            int distY = 8;
            int centerY = 100 - 4 - (params.groundHalfHeight() + rollingRadius) + distY;
            player.setCentreY((short) centerY);

            manager.updateSolidContacts(player);

            assertTrue(player.isOnObject());
            assertFalse(player.getAir());
            assertFalse(player.getRolling());
            assertFalse(player.getPushing(),
                    "ResetOnFloor_Part3 clears Status_Push on an ordinary object landing");
            assertEquals(0, player.getAnimationId(),
                    "Object landing must publish Sonic_ResetOnFloor's id_Walk write after clearing roll");
            assertEquals(0, player.getFlipAngle());
            assertEquals(0, player.getFlipType());
            assertFalse(player.isFlipTurned());
            assertEquals(0, player.getFlipsRemaining());

            int expectedStandingCenterY = 100 - params.groundHalfHeight() - 19 - 1;
            assertEquals(expectedStandingCenterY, player.getCentreY());
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void optedInS2NonRollingObjectLandingPublishesWalk() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(16, 8, 8);
            TestSolidObject object = new NonRollingWalkSolidObject(100, 100, params);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.useGameRules(GameRules.SONIC_2);
            player.setWidth(20);
            player.setHeight(38);
            player.setAir(true);
            player.setYSpeed((short) 0x100);
            player.setAnimationId(0x14);
            player.setCentreX((short) 100);
            player.setCentreY((short) (100 - 4 - params.groundHalfHeight()
                    - player.getYRadius() + 8));

            manager.updateSolidContacts(player);

            assertTrue(player.isOnObject());
            assertFalse(player.getAir());
            assertEquals(0, player.getAnimationId(),
                    "a full S2 ResetOnFloor landing publishes Walk when explicitly opted in");
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    @Test
    public void testS3kObjectLandingRollClearUsesCurrentYRadiusDelta() {
        GameModule previous = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        try {
            SolidObjectParams params = new SolidObjectParams(16, 8, 8);
            TestSolidObject object = new TestSolidObject(100, 100, params);
            ObjectManager manager = buildManager(object);

            TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
            player.useGameRules(GameRules.SONIC_3K);
            player.setWidth(20);
            player.setHeight(38);
            player.setAir(true);
            player.setYSpeed((short) 0x100);
            player.setRolling(true);
            player.applyStandingRadii(false);

            player.setCentreX((short) 100);
            int currentRadius = player.getYRadius();
            int distY = 8;
            int centerY = 100 - 4 - (params.groundHalfHeight() + currentRadius) + distY;
            player.setCentreY((short) centerY);

            manager.updateSolidContacts(player);

            assertTrue(player.isOnObject());
            assertFalse(player.getAir());
            assertFalse(player.getRolling());

            int expectedStandingCenterY = 100 - params.groundHalfHeight() - currentRadius - 1;
            assertEquals(expectedStandingCenterY, player.getCentreY(),
                    "S3K Player_TouchFloor uses the live y_radius delta; if y_radius is "
                            + "already standing-height, clearing Status_Roll must not apply "
                            + "another 5px lift");
        } finally {
            GameModuleRegistry.setCurrent(previous);
        }
    }

    private static void setPrivateInt(Object instance, String fieldName, int value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(instance, value);
    }

    private static void setPrivateBoolean(Object instance, String fieldName, boolean value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(instance, value);
    }

    private TestPlayableSprite createStandingProbePlayer() {
        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) 83);
        player.setYSpeed((short) 0x100);
        player.setAir(true);
        return player;
    }

    private ObjectManager buildManager(ObjectInstance instance) {
        ObjectRegistry registry = new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return instance;
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
                // No-op for tests.
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "TEST";
            }
        };

        ObjectManager objectManager = new ObjectManager(List.of(), registry, 0, null, null);
        objectManager.reset(0);
        objectManager.addDynamicObject(instance);
        return objectManager;
    }

    private static class TestSolidObject implements ObjectInstance, SolidObjectProvider {
        private final ObjectSpawn spawn;
        private final SolidObjectParams params;
        private final boolean topSolidOnly;
        private final Integer topLandingHalfWidth;
        private final boolean useCollisionHalfWidthForTopLanding;

        private TestSolidObject(int x, int y, SolidObjectParams params) {
            this(x, y, params, false, null, false);
        }

        private TestSolidObject(int x, int y, SolidObjectParams params, boolean topSolidOnly) {
            this(x, y, params, topSolidOnly, null, false);
        }

        private TestSolidObject(int x, int y, SolidObjectParams params, boolean topSolidOnly,
                Integer topLandingHalfWidth) {
            this(x, y, params, topSolidOnly, topLandingHalfWidth, false);
        }

        private TestSolidObject(int x, int y, SolidObjectParams params, boolean topSolidOnly,
                Integer topLandingHalfWidth, boolean useCollisionHalfWidthForTopLanding) {
            this.spawn = new ObjectSpawn(x, y, 0, 0, 0, false, 0);
            this.params = params;
            this.topSolidOnly = topSolidOnly;
            this.topLandingHalfWidth = topLandingHalfWidth;
            this.useCollisionHalfWidthForTopLanding = useCollisionHalfWidthForTopLanding;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // No-op for tests.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
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
        public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
            return topLandingHalfWidth != null ? topLandingHalfWidth
                    : SolidObjectProvider.super.getTopLandingHalfWidth(player, collisionHalfWidth);
        }

        @Override
        public boolean usesCollisionHalfWidthForTopLanding() {
            return useCollisionHalfWidthForTopLanding;
        }
    }

    private static final class InclusiveRightEdgeSolidObject extends TestSolidObject {
        private InclusiveRightEdgeSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean usesInclusiveRightEdge() {
            return true;
        }
    }

    private static final class ToggleOnScreenSolidObject extends TestSolidObject {
        private boolean withinSolidContactBounds = true;

        private ToggleOnScreenSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean isWithinSolidContactBounds() {
            return withinSolidContactBounds;
        }
    }

    private static final class OwnCheckpointAirUnseatSolidObject extends TestSolidObject {
        private OwnCheckpointAirUnseatSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean airborneRiderUnseatRequiresOwnCheckpoint(PlayableEntity player) {
            return true;
        }
    }

    private static final class ZeroDistanceMotionInclusiveRightEdgeSolidObject extends TestSolidObject {
        private ZeroDistanceMotionInclusiveRightEdgeSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean usesInclusiveRightEdge() {
            return true;
        }

        @Override
        public boolean preservesZeroDistanceSideContactMotion() {
            return true;
        }
    }

    private static final class NonRollingWalkSolidObject extends TestSolidObject {
        private NonRollingWalkSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean nonRollingLandingPublishesWalk(PlayableEntity player) {
            return true;
        }
    }

    private static final class MutatingInclusiveRightEdgeSolidObject extends TestSolidObject {
        private boolean inclusiveRightEdge = true;

        private MutatingInclusiveRightEdgeSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public SolidObjectParams getSolidParams() {
            inclusiveRightEdge = false;
            return super.getSolidParams();
        }

        @Override
        public boolean usesInclusiveRightEdge() {
            return inclusiveRightEdge;
        }
    }

    private static final class ProfileBackedSolidObject extends TestSolidObject {
        private final SolidRoutineProfile profile;

        private ProfileBackedSolidObject(int x, int y, SolidObjectParams params, SolidRoutineProfile profile) {
            super(x, y, params);
            this.profile = profile;
        }

        @Override
        public SolidRoutineProfile getSolidRoutineProfile() {
            return profile;
        }
    }

    private static class TestMultiPieceSolidObject extends TestSolidObject
            implements MultiPieceSolidProvider {
        private boolean lastPushingState;
        private int pushingStateChanges;

        private TestMultiPieceSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public int getPieceCount() {
            return 1;
        }

        @Override
        public int getPieceX(int pieceIndex) {
            return getX();
        }

        @Override
        public int getPieceY(int pieceIndex) {
            return getY();
        }

        @Override
        public void setPlayerPushing(PlayableEntity player, boolean pushing) {
            lastPushingState = pushing;
            pushingStateChanges++;
        }
    }

    private static final class PreservingRidingPushMultiPieceSolidObject
            extends TestMultiPieceSolidObject {
        private int preserveChecks;

        private PreservingRidingPushMultiPieceSolidObject(
                int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean preservesRidingPushStatus(PlayableEntity player) {
            preserveChecks++;
            return true;
        }
    }

    private static final class ProfileBackedMultiPieceSolidObject extends TestMultiPieceSolidObject {
        private final SolidRoutineProfile profile;

        private ProfileBackedMultiPieceSolidObject(
                int x, int y, SolidObjectParams params, SolidRoutineProfile profile) {
            super(x, y, params);
            this.profile = profile;
        }

        @Override
        public SolidRoutineProfile getSolidRoutineProfile() {
            return profile;
        }
    }

    private static final class SlotOrderedMultiPieceSolidObject extends TestSolidObject
            implements MultiPieceSolidProvider {
        private final int[] pieceX;
        private final int[] pieceY;
        private boolean lastPushingState;
        private boolean piece0Contacted;
        private boolean piece0Pushed;
        private boolean piece0Standing;
        private boolean piece0Side;
        private int lastStandingPieceIndex = -1;

        private SlotOrderedMultiPieceSolidObject(int firstPieceX, int secondPieceX, int y,
                SolidObjectParams params) {
            super(secondPieceX, y, params);
            this.pieceX = new int[] { firstPieceX, secondPieceX };
            this.pieceY = new int[] { y, y };
        }

        private void setPieceX(int pieceIndex, int x) {
            pieceX[pieceIndex] = x;
        }

        private void setPieceY(int pieceIndex, int y) {
            pieceY[pieceIndex] = y;
        }

        @Override
        public int getPieceCount() {
            return pieceX.length;
        }

        @Override
        public int getPieceX(int pieceIndex) {
            return pieceX[pieceIndex];
        }

        @Override
        public int getPieceY(int pieceIndex) {
            return pieceY[pieceIndex];
        }

        @Override
        public boolean resolvesEarlierPiecesBeforeRidingPiece() {
            return true;
        }

        @Override
        public boolean usesPieceScopedStandingBits() {
            return true;
        }

        @Override
        public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
            return true;
        }

        @Override
        public boolean sideContactReturnsNoContact(PlayableEntity player) {
            return player.isCpuControlled();
        }

        @Override
        public void setPlayerPushing(PlayableEntity player, boolean pushing) {
            lastPushingState = pushing;
        }

        @Override
        public void onPieceContact(int pieceIndex, PlayableEntity player,
                SolidContact contact, int frameCounter) {
            if (contact.standing()) {
                lastStandingPieceIndex = pieceIndex;
            }
            if (pieceIndex == 0) {
                piece0Contacted = true;
                piece0Standing = contact.standing();
                piece0Side = contact.touchSide();
            }
            if (pieceIndex == 0 && contact.pushing()) {
                piece0Pushed = true;
            }
        }
    }

    private static final class MutableMultiPieceSolidObject extends TestSolidObject
            implements MultiPieceSolidProvider {
        private int y;

        private MutableMultiPieceSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
            this.y = y;
        }

        private void setY(int y) {
            this.y = y;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getPieceCount() {
            return 1;
        }

        @Override
        public int getPieceX(int pieceIndex) {
            return getX();
        }

        @Override
        public int getPieceY(int pieceIndex) {
            return getY();
        }
    }

    private static final class TestSlopedMultiPieceSolidObject extends TestSolidObject
            implements MultiPieceSolidProvider, SlopedSolidProvider {
        private static final byte[] SLOPE = {
                8, 8, 8, 8, 8, 8, 8, 8,
                8, 8, 8, 8, 8, 8, 8, 8
        };

        private TestSlopedMultiPieceSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params, true);
        }

        @Override
        public int getPieceCount() {
            return 1;
        }

        @Override
        public int getPieceX(int pieceIndex) {
            return getX();
        }

        @Override
        public int getPieceY(int pieceIndex) {
            return getY();
        }

        @Override
        public byte[] getSlopeData() {
            return SLOPE.clone();
        }

        @Override
        public boolean isSlopeFlipped() {
            return false;
        }

        @Override
        public int getSlopeBaseline() {
            return 0;
        }
    }

    private static final class StaleStandingBitFullSolidObject extends TestSolidObject {
        private StaleStandingBitFullSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params);
        }

        @Override
        public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
            return true;
        }

        @Override
        public boolean suppressesGroundingRecoveryFromAirborneStaleRide(PlayableEntity player) {
            return true;
        }
    }

    private static SolidRoutineProfile solidProfile(
            SolidRoutineKind kind,
            boolean topSolidOnly,
            boolean monitorSolidity,
            int monitorVerticalOffset,
            boolean inclusiveRightEdge,
            boolean stickyContactBuffer,
            boolean usesPlatformLandingSnap,
            boolean usesCollisionHalfWidthForTopLanding,
            boolean usesGroundHalfHeightForTopSolidContact,
            boolean bypassesOffscreenSolidGate,
            boolean allowsObjectControlledSolidContacts,
            boolean forceAirOnRideExit,
            boolean dropOnFloor,
            boolean carriesAirborneRiderAfterExitPlatform) {
        return new SolidRoutineProfile(
                kind,
                topSolidOnly,
                monitorSolidity,
                monitorVerticalOffset,
                inclusiveRightEdge,
                stickyContactBuffer,
                usesPlatformLandingSnap,
                usesCollisionHalfWidthForTopLanding,
                usesGroundHalfHeightForTopSolidContact,
                bypassesOffscreenSolidGate,
                allowsObjectControlledSolidContacts,
                forceAirOnRideExit,
                dropOnFloor,
                carriesAirborneRiderAfterExitPlatform);
    }

    private static final class HistoryTopSolidObject extends TestSolidObject {
        private HistoryTopSolidObject(int x, int y, SolidObjectParams params) {
            super(x, y, params, true);
        }

        @Override
        public int getTopSolidPlayerPositionHistoryFrames(PlayableEntity player) {
            return 1;
        }
    }

    private static final class ManualCheckpointProbeObject extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        private boolean standingSeenInsideUpdate;
        private int manualCheckpointCount;
        private int compatibilityCallbackCount;

        private ManualCheckpointProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "ManualCheckpointProbe");
        }

        @Override
        public SolidExecutionMode solidExecutionMode() {
            return SolidExecutionMode.MANUAL_CHECKPOINT;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            PlayerSolidContactResult result = services().solidExecution().resolveSolidNow(player);
            standingSeenInsideUpdate = result.standingNow();
            manualCheckpointCount++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public boolean isSkipSolidContactThisFrame() {
            return false;
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
            compatibilityCallbackCount++;
        }
    }

    private static final class ManualPushCheckpointObject extends AbstractObjectInstance
            implements SolidObjectProvider {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        private final boolean persistentNativeLatch;
        private boolean skipNextCheckpoint;

        private ManualPushCheckpointObject(int x, int y, boolean persistentNativeLatch) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "ManualPushCheckpoint");
            this.persistentNativeLatch = persistentNativeLatch;
        }

        private void skipNextCheckpoint() {
            skipNextCheckpoint = true;
        }

        @Override
        public SolidExecutionMode solidExecutionMode() {
            return SolidExecutionMode.MANUAL_CHECKPOINT;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }

        @Override
        public boolean usesInstanceSolidStateLatchKey() {
            return true;
        }

        @Override
        public boolean preservesNativePushLatchAcrossSkippedSolidCheckpoints() {
            return persistentNativeLatch;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (skipNextCheckpoint) {
                skipNextCheckpoint = false;
                return;
            }
            services().solidExecution().resolveSolidNow(player);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public boolean isSkipSolidContactThisFrame() {
            return false;
        }
    }

    private static final class AutoCheckpointProbeObject extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        private int compatibilityCallbackCount;

        private AutoCheckpointProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "AutoCheckpointProbe");
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // No-op for tests.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public boolean isSkipSolidContactThisFrame() {
            return false;
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
            compatibilityCallbackCount++;
        }
    }

    private static final class FirstContactDisablesSolidityProbeObject extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        private boolean solid = true;
        private int compatibilityCallbackCount;
        private PlayableEntity firstCallbackPlayer;
        private boolean mainPlayerSawCallback;
        private boolean sidekickSawCallback;

        private FirstContactDisablesSolidityProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "FirstContactDisablesSolidityProbe");
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }

        @Override
        public boolean isSolidFor(PlayableEntity player) {
            return solid;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // No-op for tests.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public boolean isSkipSolidContactThisFrame() {
            return false;
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
            compatibilityCallbackCount++;
            if (firstCallbackPlayer == null) {
                firstCallbackPlayer = player;
                solid = false;
                mainPlayerSawCallback = true;
            } else {
                sidekickSawCallback = true;
            }
        }
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(short x, short y) {
            super("TEST", x, y);
        }

        private void useGameRules(GameRules fs) {
            super.setGameRulesForTest(fs);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 28;
            runHeight = 38;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            // No-op for tests.
        }
    }
}
