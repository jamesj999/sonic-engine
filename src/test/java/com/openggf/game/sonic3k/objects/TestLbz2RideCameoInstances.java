package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PlayableEntity;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.data.Rom;
import com.openggf.level.Palette;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.LevelManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLbz2RideCameoInstances {
    private static final int OBJ_CUTSCENE_KNUCKLES = 0x82;
    private static final int OBJ_LBZ2_ROBOTNIK_SHIP = 0xC6;
    private static final int OBJ_LBZ_KNUX_PILLAR = 0xC8;

    @Test
    void shipGrabAppliesFullObjectControlAndPinsP1EveryFrame() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_AND_TAILS);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);
        TestablePlayableSprite sonic = playerAt(0x3BDE, 0x0654, "sonic");
        sonic.setXSpeed((short) 0x00AB);
        sonic.setYSpeed((short) 0x0680);

        touchShip(ship, sonic, 0);
        ship.update(0, sonic);

        assertEquals(0x3BDE, sonic.getCentreX() & 0xFFFF,
                "loc_8D2B6 capture does not run sub_8D506 on the same dispatch");
        assertEquals(0x0654, sonic.getCentreY() & 0xFFFF);
        assertEquals((short) 0x00AB, sonic.getXSpeed());
        assertEquals((short) 0x0680, sonic.getYSpeed());
        assertEquals(0xBA, sonic.getMappingFrame(),
                "capture still publishes the object-owned Hang mapping immediately");

        ship.update(1, sonic);

        assertTrue(sonic.isObjectControlled());
        assertFalse(sonic.isObjectControlAllowsCpu(),
                "object_control=$83 includes native bit 7, so CPU movement must not run");
        assertTrue(sonic.isObjectControlSuppressesMovement());
        assertEquals((ship.getCentreX() - 4) & 0xFFFF, sonic.getCentreX() & 0xFFFF);
        assertEquals((ship.getCentreY() - 0x12) & 0xFFFF, sonic.getCentreY() & 0xFFFF);
        assertEquals(0xBA, sonic.getMappingFrame());
        assertTrue(sonic.isObjectMappingFrameControl());
        assertEquals(Direction.RIGHT, sonic.getDirection());
        assertTrue(sonic.isHighPriority());
    }

    @Test
    void shipGrabOpensRideCameraAndStartsExhaustAndTileAnimationRequest() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        Camera camera = new Camera();
        camera.setMaxX((short) 0x3AB8);
        fixture.services.withCamera(camera);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);
        TestablePlayableSprite sonic = playerAt(0x3BDE, 0x0654, "sonic");

        touchShip(ship, sonic, 0);
        ship.update(0, sonic);

        assertEquals(0x3AB8, camera.getMaxX() & 0xFFFF,
                "ship should request gradual camera opening, not snap current max X");
        assertEquals(0x3AB8, camera.getMaxXTarget() & 0xFFFF,
                "the post-camera-pass stored write is not visible immediately");
        Lbz2RobotnikShipInstance.GradualCameraMaxXChild cameraChild =
                ship.spawnedChildrenForTest().stream()
                        .filter(Lbz2RobotnikShipInstance.GradualCameraMaxXChild.class::isInstance)
                        .map(Lbz2RobotnikShipInstance.GradualCameraMaxXChild.class::cast)
                        .findFirst()
                        .orElseThrow();
        cameraChild.update(0, sonic);
        assertEquals(0x3AB8, camera.getMaxX() & 0xFFFF);
        cameraChild.update(1, sonic);
        assertEquals(0x3AB8, camera.getMaxX() & 0xFFFF);
        cameraChild.update(2, sonic);
        assertEquals(0x3AB8, camera.getMaxX() & 0xFFFF);
        cameraChild.update(3, sonic);
        assertEquals(0x3AB9, camera.getMaxX() & 0xFFFF,
                "the $4000 accumulator reaches its first whole pixel");
        assertTrue(fixture.runtime.isLbz2RideAnimatedTileGateActive());
        assertTrue(ship.spawnedChildrenForTest().stream()
                        .anyMatch(Lbz2RobotnikShipInstance.ExhaustFlameChild.class::isInstance),
                "ship grab/start should spawn the LBZ2 exhaust flame child");
    }

    @Test
    void rideShipCarriesRomPriorityInFrontOfLaunchBackgroundShip() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);

        assertTrue(ship.isHighPriority(),
                "ObjDat_LBZ2RobotnikShip uses make_art_tile(...,0,1), so the carried craft should keep art priority");
        assertEquals(1, ship.getPriorityBucket(),
                "ObjDat_LBZ2RobotnikShip priority is $80, matching the first object priority band");
    }

    @Test
    void exhaustFlameUsesSharedShipFlameFrameOffsetAndCadence() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        fixture.services.withLevelManager(levelManager);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);
        TestablePlayableSprite sonic = playerAt(0x3BDE, 0x0654, "sonic");

        touchShip(ship, sonic, 0);
        ship.update(0, sonic);
        Lbz2RobotnikShipInstance.ExhaustFlameChild flame = ship.spawnedChildrenForTest().stream()
                .filter(Lbz2RobotnikShipInstance.ExhaustFlameChild.class::isInstance)
                .map(Lbz2RobotnikShipInstance.ExhaustFlameChild.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals((ship.getCentreX() - 0x1E) & 0xFFFF, flame.getCentreX());
        assertEquals(ship.getCentreY() & 0xFFFF, flame.getCentreY());

        flame.update(0, sonic);
        flame.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawFrameIndex(6, flame.getCentreX(), flame.getCentreY(), true, false);

        flame.update(1, sonic);
        flame.appendRenderCommands(new ArrayList<>());
        verify(renderer, never()).drawFrameIndex(6, flame.getCentreX(), flame.getCentreY(), false, false);
        verify(renderer, times(1)).drawFrameIndex(6, flame.getCentreX(), flame.getCentreY(), true, false);
    }

    @Test
    void shipUsesTailsHangMappingFrameWhenCarryingTails() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_AND_TAILS);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);
        TestablePlayableSprite tails = playerAt(0x3BDE, 0x0654, "tails");

        touchShip(ship, tails, 0);
        ship.update(0, tails);

        assertEquals(0xAD, tails.getMappingFrame());
    }

    @Test
    void shipRequestsLaunchStartThroughRuntimeStateAndLeavesLegacyFg5Alone() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x3BDE, 0x0654);
        CutsceneKnucklesLbz2Instance knuckles = fixture.knuckles(0x3E28, 0x0608);
        ship.attachCutsceneKnuckles(knuckles);
        TestablePlayableSprite sonic = playerAt(0x3BDE, 0x0654, "sonic");

        touchShip(ship, sonic, 0);
        boolean launchRequested = false;
        int frame = 0;
        for (; frame < 1200 && !launchRequested; frame++) {
            ship.update(frame, sonic);
            knuckles.update(frame, sonic);
            launchRequested = fixture.runtime.consumeLaunchStartRequested();
        }

        assertTrue(launchRequested,
                "ship thump/rumble phase must request the launch via LbzZoneRuntimeState");
        assertFalse(ship.didSetLegacyEventsFg5ForTest(),
                "Task E must not write the legacy LBZ1 reload flag directly");
        assertTrue(fixture.gameState.isScreenShakeActive(),
                "loc_8D450 sets Screen_shake_flag with Events_fg_5");

        // The cameo reads Screen_shake_flag on the frames after the ship sets it.
        for (int extra = 0; extra < 4; extra++) {
            knuckles.update(frame + extra, sonic);
        }
        assertTrue(knuckles.isScreenShakeObservedForTest());
    }

    private static void touchShip(Lbz2RobotnikShipInstance ship,
            TestablePlayableSprite player, int frameCounter) {
        ship.onTouchResponse(player,
                new TouchResponseResult(0x0A, 0x10, 8, TouchCategory.SPECIAL),
                frameCounter);
    }

    @Test
    void shipReleasesPlayerAtRomXAndSpawnsFinalBossAtExactCoordinates() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        Lbz2RobotnikShipInstance ship = fixture.ship(0x443F, 0x0640);
        TestablePlayableSprite sonic = playerAt(0x443F, 0x0640, "sonic");

        assertEquals(0x20, ship.getOnScreenHalfWidth());
        assertEquals(0x20, ship.getOnScreenHalfHeight());
        ship.grabPlayerForTest(sonic);
        fixture.runtime.setLaunchActive(true);
        ship.setRideRightForTest(0x0100);
        ship.update(0, sonic);

        assertFalse(sonic.isObjectControlled());
        assertFalse(sonic.isObjectMappingFrameControl());
        assertTrue(sonic.getAir());
        assertEquals(0x443C, sonic.getCentreX() & 0xFFFF,
                "release dispatch pins from the moved ship before throwing Sonic");
        assertEquals((short) -0x0100, sonic.getXSpeed());
        assertEquals((short) -0x0600, sonic.getYSpeed());
        assertEquals(2, sonic.getAnimationId());
        assertTrue(fixture.runtime.isLaunchActive(),
                "ship release must not end launch mode; later LBZ finale events own that state");

        ship.forceOffscreenForTest();
        ship.update(1, sonic);

        assertEquals(1, ship.spawnedChildrenForTest().size());
        LbzFinalBoss1Instance boss = assertInstanceOf(
                LbzFinalBoss1Instance.class, ship.spawnedChildrenForTest().get(0));
        assertEquals(0x44A0, boss.getCentreX() & 0xFFFF);
        assertEquals(0x0780, boss.getCentreY() & 0xFFFF);
        assertTrue(ship.isDestroyed());
    }

    @Test
    void pillarAppliesLaunchYDeltaEveryFrame() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        fixture.runtime.setLaunchYDelta(-3);
        LbzKnuxPillarInstance pillar = fixture.pillar(0x3D60, 0x061C, 0x02);

        pillar.update(0, playerAt(0x100, 0x100, "sonic"));
        pillar.update(1, playerAt(0x100, 0x100, "sonic"));

        assertEquals(0x0616, pillar.getCentreY() & 0xFFFF);
        assertTrue(pillar.isArtPriorityForTest(),
                "placement flag bit 1 selects the art priority bit for pillar scenery");
    }

    @Test
    void pillarAppliesPlacementPriorityToRuntimeRenderPriority() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        LbzKnuxPillarInstance lowPriorityPillar = fixture.pillar(0x3D60, 0x061C, 0x01);
        LbzKnuxPillarInstance highPriorityPillar = fixture.pillar(0x3D60, 0x061C, 0x02);

        assertFalse(lowPriorityPillar.isHighPriority());
        assertEquals(0, lowPriorityPillar.getPriorityBucket());
        assertTrue(highPriorityPillar.isHighPriority());
        assertEquals(5, highPriorityPillar.getPriorityBucket());
    }

    @Test
    void sonicCameoEnsuresRomSupportPillarLayoutObjectIsPresent() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        CutsceneKnucklesLbz2Instance cameo = fixture.knuckles(0x3E28, 0x0608);

        List<LbzKnuxPillarInstance> supportPillars = cameo.supportPillarsForTest();

        assertEquals(1, supportPillars.size(),
                "The LBZ2 cameo stands on Obj_LBZKnuxPillar at the matching layout coordinate");
        LbzKnuxPillarInstance pillar = supportPillars.getFirst();
        assertEquals(0x3D60, pillar.getCentreX() & 0xFFFF);
        assertEquals(0x061C, pillar.getCentreY() & 0xFFFF);
        assertTrue(pillar.isHighPriority(),
                "render_flags bit 1 on the ROM layout object sets art priority for the girder");
    }

    @Test
    void knucklesModeDeletesCameoImmediately() {
        Fixture fixture = new Fixture(PlayerCharacter.KNUCKLES);
        CutsceneKnucklesLbz2Instance cameo = fixture.knuckles(0x3E28, 0x0608);

        cameo.update(0, playerAt(0x3D00, 0x0608, "knuckles"));

        assertTrue(cameo.isDestroyed());
        assertEquals(0, cameo.swingChildrenForTest().size());
    }

    @Test
    void sonicCameoRespondsToTriggerShakeFlingAndSplash() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        fixture.runtime.setLaunchYDelta(2);
        WaterSystem water = new WaterSystem();
        water.loadForLevelFromProvider(new StaticWaterProvider(0x0640),
                null, Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.SONIC_ALONE);
        fixture.services.withWaterSystem(water);
        CutsceneKnucklesLbz2Instance cameo = fixture.knuckles(0x3E28, 0x0608);

        assertEquals(4, cameo.swingChildrenForTest().size());
        assertEquals(List.of(Sonic3kMusic.KNUCKLES.id), cameo.musicFadeTargetsForTest(),
                "loc_628E0 spawns Obj_Song_Fade_Transition -> mus_Knuckles");

        cameo.triggerFromShip();
        cameo.update(0, playerAt(0x3D00, 0x0608, "sonic"));
        assertTrue(cameo.isTriggeredForTest());

        // ROM: the taunt script (byte_666D2) plays out before the idle state
        // that watches Screen_shake_flag.
        fixture.gameState.setScreenShakeActive(true);
        int frame = 1;
        for (; frame < 200 && !cameo.isScreenShakeObservedForTest(); frame++) {
            cameo.update(frame, playerAt(0x3D00, 0x0608, "sonic"));
        }
        assertTrue(cameo.isScreenShakeObservedForTest());

        cameo.markFlungFromSwingForTest();
        for (; frame < 400 && !cameo.hasSplashedForTest(); frame++) {
            cameo.update(frame, playerAt(0x3D00, 0x0608, "sonic"));
        }

        assertTrue(cameo.hasSplashedForTest());
        assertTrue(fixture.playedSfx.contains(Sonic3kSfx.SPLASH.id));
        assertEquals(List.of(Sonic3kMusic.KNUCKLES.id, Sonic3kMusic.LBZ2.id),
                cameo.musicFadeTargetsForTest(),
                "flung cameo fades back to level music (loc_6297A)");
    }

    @Test
    void swingSubtypeZeroCopiesXToParentAndSignalsFlingAfterSixCrossings() {
        Fixture fixture = new Fixture(PlayerCharacter.SONIC_ALONE);
        fixture.gameState.setScreenShakeActive(true);
        CutsceneKnucklesLbz2Instance cameo = fixture.knuckles(0x3E28, 0x0608);
        CutsceneKnucklesLbz2Instance.SwingChild child = cameo.swingChildrenForTest().get(0);

        // First update arms the swing (loc_62A0A): x_vel = $100, $39 = 6.
        child.update(0, playerAt(0x3D00, 0x0608, "sonic"));
        assertTrue(child.isSwingingForTest());
        assertEquals(0x100, child.getXVelForTest(), "loc_62A0A seeds x_vel from word_629FA");
        assertEquals(6, child.getCrossingsRemainingForTest());

        int frame = 1;
        boolean upgraded = false;
        for (; frame < 600 && !cameo.isFlingRequestedForTest(); frame++) {
            child.update(frame, playerAt(0x3D00, 0x0608, "sonic"));
            if (child.getCrossingsRemainingForTest() == 2 && !upgraded) {
                upgraded = true;
                assertEquals(0x200, child.getXVelForTest(),
                        "the crossing where $39 == 3 upgrades the pair from word_62A9E");
            }
        }

        assertEquals(child.getCentreX(), cameo.getCentreX(),
                "subtype 0 is the leader chain link that drags Knuckles horizontally");
        assertTrue(upgraded, "the speed upgrade crossing must occur before the fling");
        assertTrue(cameo.isFlingRequestedForTest());
        assertTrue(child.isFreeFallingForTest());
        assertTrue(frame > 60, "six pendulum half-periods take dozens of frames, not instant reversals");
    }

    private static TestablePlayableSprite playerAt(int x, int y, String code) {
        return new TestablePlayableSprite(code, (short) x, (short) y);
    }

    private static final class Fixture {
        final LbzZoneRuntimeState runtime;
        final ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        final RecordingServices services = new RecordingServices();
        final com.openggf.game.GameStateManager gameState = new com.openggf.game.GameStateManager();
        final List<Integer> playedSfx = services.playedSfx;
        final List<Integer> playedMusic = services.playedMusic;

        Fixture(PlayerCharacter character) {
            runtime = new LbzZoneRuntimeState(1, character);
            registry.install(runtime);
            services.withZoneRuntimeRegistry(registry);
            services.withGameState(gameState);
            AbstractObjectInstance.updateCameraBounds(0x3800, 0x0500, 0x4600, 0x0800, 0);
        }

        Lbz2RobotnikShipInstance ship(int x, int y) {
            Lbz2RobotnikShipInstance ship = new Lbz2RobotnikShipInstance(
                    new ObjectSpawn(x, y, OBJ_LBZ2_ROBOTNIK_SHIP, 0, 0, false, y));
            ship.setServices(services);
            return ship;
        }

        LbzKnuxPillarInstance pillar(int x, int y, int flags) {
            LbzKnuxPillarInstance pillar = new LbzKnuxPillarInstance(
                    new ObjectSpawn(x, y, OBJ_LBZ_KNUX_PILLAR, 0, flags, false, y));
            pillar.setServices(services);
            return pillar;
        }

        CutsceneKnucklesLbz2Instance knuckles(int x, int y) {
            CutsceneKnucklesLbz2Instance knuckles = new CutsceneKnucklesLbz2Instance(
                    new ObjectSpawn(x, y, OBJ_CUTSCENE_KNUCKLES, 0x18, 0, false, y));
            knuckles.setServices(services);
            return knuckles;
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        final List<Integer> playedSfx = new ArrayList<>();
        final List<Integer> playedMusic = new ArrayList<>();

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public void playMusic(int musicId) {
            playedMusic.add(musicId);
        }
    }

    private static final class StaticWaterProvider implements WaterDataProvider {
        private final int level;

        private StaticWaterProvider(int level) {
            this.level = level;
        }

        @Override
        public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
            return true;
        }

        @Override
        public int getStartingWaterLevel(int zoneId, int actId) {
            return level;
        }

        @Override
        public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character) {
            return null;
        }

        @Override
        public com.openggf.game.DynamicWaterHandler getDynamicHandler(
                int zoneId, int actId, PlayerCharacter character) {
            return null;
        }
    }
}
