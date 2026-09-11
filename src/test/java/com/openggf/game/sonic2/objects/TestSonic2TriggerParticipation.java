package com.openggf.game.sonic2.objects;

import com.openggf.game.GameStateManager;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.Sonic2ZoneFeatureProvider;
import com.openggf.game.sonic2.objects.badniks.SlicerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerPincerInstance;
import com.openggf.game.sonic2.objects.badniks.SpinyBadnikInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2ARZBossInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.LevelManager;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.SidekickCpuController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestSonic2TriggerParticipation {

    @BeforeEach
    void resetObjectCameraBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x2000, 0x2000, 0);
    }

    @Test
    void movingSpikeConsumesInitFrameBeforeFirstPositionStep() {
        SpikeObjectInstance spike = new SpikeObjectInstance(
                new ObjectSpawn(0x1548, 0x06B8, Sonic2ObjectIds.SPIKES, 0x01, 0, false, 0),
                "Spikes");

        spike.update(0, null);
        assertEquals(0x06B8, spike.getY(),
                "Obj36_Init returns before MoveSpikes changes the placement position");

        spike.update(1, null);
        assertEquals(0x06C0, spike.getY(),
                "the first Obj36_Upright execution applies the initial +8px retract step");
    }

    @Test
    void arrowShooterDetectsQueryOnlySidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1800, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1010, 0x1000);
        ArrowShooterObjectInstance shooter = new ArrowShooterObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x22, 0, 0, false, 0),
                "ArrowShooter");
        shooter.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        shooter.update(0, main);

        assertEquals(1, intField(shooter, "currentAnim"),
                "Arrow Shooter should use ObjectPlayerQuery participants for detection");
    }

    @Test
    void barrierRisesForQueryOnlySidekickInDetectionZone() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x0F00, 0x1000);
        BarrierObjectInstance barrier = new BarrierObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x2D, 0, 0, false, 0),
                "Barrier");
        barrier.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        barrier.update(0, main);

        assertEquals(0x0FF8, barrier.getY(),
                "Barrier should rise when a query participant enters its detection zone");
    }

    @Test
    void barrierSolidBottomBoundsUseLiveRollingRadius() {
        BarrierObjectInstance barrier = new BarrierObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BARRIER, 1, 0, false, 0),
                "Barrier");

        assertTrue(barrier.fullSolidBottomOverlapUsesCurrentYRadiusOnly(null),
                "Obj2D must use S2 SolidObject's live y_radius lower bound");
    }

    @Test
    void nutNativeXSnapsPreservePlayerSubpixel() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setSubpixelRaw(0xCF00, 0x1C00);
        NutObjectInstance nut = new NutObjectInstance(
                new ObjectSpawn(0x1000, 0x1020, Sonic2ObjectIds.NUT, 0, 0, false, 0),
                "Nut");
        nut.setServices(new QueryOnlyPlayerServices(main, List.of()));

        nut.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);
        nut.update(0, main);

        assertEquals(0x1000, main.getCentreX());
        assertEquals(0xCF00, main.getXSubpixelRaw(),
                "Obj69 align writes only x_pos(a1), preserving x_sub");

        main.setCentreXPreserveSubpixel((short) 0x1002);
        nut.onSolidContact(main, new SolidContact(true, false, false, true, false), 1);
        nut.update(1, main);

        assertEquals(0x1000, main.getCentreX());
        assertEquals(0xCF00, main.getXSubpixelRaw(),
                "Obj69 screw movement writes only x_pos(a1), preserving x_sub");
    }

    @Test
    void nutSidekickActionSeesLiveObjectStandingBit() {
        TestablePlayableSprite main = player("sonic", 0x1600, 0x0600);
        TestablePlayableSprite tails = player("tails", 0x16C0, 0x04D2);
        tails.setCpuControlled(true);
        tails.setSubpixelRaw(0xB500, 0x8500);
        NutObjectInstance nut = new NutObjectInstance(
                new ObjectSpawn(0x16C0, 0x04EE, Sonic2ObjectIds.NUT, 0, 0, false, 0),
                "Nut");
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.hasObjectStandingBit(tails, nut)).thenReturn(true);
        nut.setServices(new QueryOnlyPlayerServices(main, List.of(tails)).withObjectManager(objectManager));

        nut.update(0, main);

        tails.setCentreXPreserveSubpixel((short) 0x16C1);
        tails.setSubpixelRaw(0x4D00, 0x8500);
        nut.update(1, main);

        assertEquals(0x16C0, tails.getCentreX(),
                "Obj69's P2 action pass reads status(a0)'s p2 standing bit before SolidObject "
                        + "and writes x_pos(a0) to x_pos(a1) (s2.asm:54000-54013, 54017-54061, 54095-54107)");
        assertEquals(0x4D00, tails.getXSubpixelRaw(),
                "Obj69 writes only the native x_pos word, preserving x_sub");
    }

    @Test
    void nutSolidBottomBoundsUseLiveRollingRadius() {
        NutObjectInstance nut = new NutObjectInstance(
                new ObjectSpawn(0x13C0, 0x064C, Sonic2ObjectIds.NUT, 0x15, 0, false, 0),
                "Nut");

        assertTrue(nut.fullSolidBottomOverlapUsesCurrentYRadiusOnly(null),
                "Obj69 SolidObject tail doubles live y_radius(a1), so rolling lower-half contact "
                        + "must not use stand radius");
    }

    @Test
    void nutExposesNativeWidthForObjectEdgeBalance() {
        NutObjectInstance nut = new NutObjectInstance(
                new ObjectSpawn(0x18C0, 0x04F4, Sonic2ObjectIds.NUT, 0, 0, false, 0),
                "Nut");

        assertEquals(0x20, nut.getBalanceWidthPixels(),
                "Obj69 writes width_pixels=$20; player balance must not use the 16px sprite default "
                        + "or SolidObject's wider d1=$2B collision extent");
    }

    @Test
    void nutOffscreenSidekickSolidObjectGateReturnsNoContact() {
        NutObjectInstance nut = new NutObjectInstance(
                new ObjectSpawn(0x16C0, 0x04E6, Sonic2ObjectIds.NUT, 0, 0, false, 0),
                "Nut");
        TestablePlayableSprite tails = player("tails", 0x16C0, 0x04D2);
        tails.setCpuControlled(true);
        tails.setRenderFlagOnScreen(false);

        assertTrue(nut.airborneStaleStandingBitReturnsNoContact(tails),
                "Obj69's native P2 SolidObject tail returns before SolidObject_cont when "
                        + "the CPU sidekick render_flags.on_screen bit is clear (s2.asm:54006-54013, 35022-35025)");

        tails.setRenderFlagOnScreen(true);
        assertTrue(nut.airborneStaleStandingBitReturnsNoContact(tails),
                "Obj69's native P2 SolidObject tail consumes a stale airborne standing bit "
                        + "before SolidObject_cont even when Tails is on-screen (s2.asm:54006-54013, 35028-35046)");
        assertTrue(nut.suppressesGroundingRecoveryFromAirborneStaleRide(tails),
                "Obj69's late SolidObject tail must keep stale airborne ride latches out of "
                        + "pre-movement grounding recovery (s2.asm:54006-54013, 35028-35046)");
        assertFalse(nut.airborneStaleStandingBitReturnsNoContact(null));
        assertFalse(nut.suppressesGroundingRecoveryFromAirborneStaleRide(null));
    }

    @Test
    void wfzPaletteSwitcherUsesQueryOnlySidekickCrossing() {
        TestablePlayableSprite main = player("sonic", 0x0800, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x0FF0, 0x1000);
        GameStateManager gameState = new GameStateManager();
        WFZPalSwitcherObjectInstance switcher = new WFZPalSwitcherObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x8B, 0, 0, false, 0),
                "WFZPalSwitcher");
        switcher.setServices(new QueryOnlyPlayerServices(main, List.of(tails)).withGameState(gameState));

        switcher.update(0, main);
        tails.setCentreX((short) 0x1010);
        switcher.update(1, main);

        assertTrue(gameState.isWfzFireToggle(),
                "WFZ palette switcher should route sidekick crossing through ObjectPlayerQuery");
    }

    @Test
    void springAppliesCheckpointContactToQueryOnlySidekick() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x41, 0x10, 0, false, 0),
                "Spring");
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(tails));
        services.withObjectManager(new ObjectManager(
                List.of(), null, 0, null, null, null, null, services));
        services.withCheckpointBatch(new SolidCheckpointBatch(
                spring,
                Map.of(tails, pushingContact())));
        spring.setServices(services);

        spring.update(0, main);
        spring.update(1, main);

        assertEquals(0x1000, tails.getXSpeed() & 0xFFFF,
                "Spring should consume ObjectPlayerQuery participants for manual checkpoint contact");
        assertEquals(0x1000, tails.getGSpeed() & 0xFFFF);
    }

    @Test
    void speedLauncherStartsForQueryOnlyStandingSidekick() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        tails.setOnObject(true);
        SpeedLauncherObjectInstance launcher = new SpeedLauncherObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xC0, 0x01, 0, false, 0),
                "SpeedLauncher");
        launcher.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));
        launcher.onSolidContact(tails, new SolidContact(true, false, false, true, false), 0);

        launcher.update(0, main);

        assertEquals(0x0FF4, launcher.getX(),
                "Speed Launcher should use ObjectPlayerQuery participants when selecting standing riders");
        assertEquals(0x0FF4, tails.getCentreX() & 0xFFFF);
    }

    @Test
    void speedLauncherUsesLatchedStandingMaskOnDestinationFrame() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setOnObject(true);
        SpeedLauncherObjectInstance launcher = new SpeedLauncherObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xC0, 0x01, 0, false, 0),
                "SpeedLauncher");
        launcher.setServices(new QueryOnlyPlayerServices(main, List.of()));
        launcher.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        launcher.update(0, main);
        main.setOnObject(false);
        launcher.onSolidContactCleared(main, 1);

        launcher.update(1, main);

        assertTrue(main.getAir(),
                "ObjC0 must launch from its latched standing bit before PlatformObject refreshes it");
        assertEquals(0xF380, main.getXSpeed() & 0xFFFF);
        assertEquals(0xFC00, main.getYSpeed() & 0xFFFF);
    }

    @Test
    void hPropellerPushesQueryOnlySidekick() {
        OscillationManager.reset();
        TestablePlayableSprite main = player("sonic", 0x1800, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x0FB0);
        HPropellerObjectInstance propeller = new HPropellerObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xB5, 0x66, 0, false, 0));
        propeller.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        propeller.update(0, main);

        assertTrue(tails.getAir(),
                "Horizontal propeller should use ObjectPlayerQuery participants for push checks");
        assertEquals(0x0FAF, tails.getCentreY() & 0xFFFF,
                "ObjB5 adds the computed push to native y_pos, not sprite top-left bounds");
        assertEquals(Sonic2AnimationIds.FLOAT2.id(), tails.getAnimationId());
        assertEquals(0, tails.getYSpeed());
    }

    @Test
    void wallTurretShotUsesRomObj98SubtypeIdentity() throws Exception {
        ObjectSpawn parentSpawn = new ObjectSpawn(
                0x0100, 0x0080, Sonic2ObjectIds.WALL_TURRET, 0x74, 0, false, 0);
        WallTurretShotInstance shot = new WallTurretShotInstance(
                parentSpawn, 0x0100, 0x0098, 0, 0x0100);

        assertEquals(Sonic2ObjectIds.PROJECTILE, shot.getSpawn().objectId(),
                "ObjB8 should allocate Obj98 for its wall-turret shot child");
        assertEquals(0x8E, shot.getSpawn().subtype(),
                "ObjB8 writes subtype $8E so Obj98 loads ObjB8_SubObjData2");
        assertEquals(4, shot.getOnScreenHalfWidth(),
                "ObjB8_SubObjData2 sets the projectile width_pixels to 4");
        assertEquals(3, intField(shot, "mappingFrame"),
                "ObjB8 parent initializes the projectile mapping_frame to 3");

        shot.update(0, null);
        assertEquals(3, intField(shot, "mappingFrame"),
                "ROM AnimateSprite reads script frame 3 on the first Obj98_WallTurretShotMove update");
        shot.update(1, null);
        shot.update(2, null);
        assertEquals(3, intField(shot, "mappingFrame"),
                "Ani_WallTurretShot duration 2 keeps frame 3 for two wait ticks");
        shot.update(3, null);
        assertEquals(4, intField(shot, "mappingFrame"),
                "Ani_WallTurretShot advances to frame 4 after the duration expires");
    }

    @Test
    void wallTurretFireSpawnsObj98ProjectileChild() {
        TestablePlayableSprite main = player("sonic", 0x0100, 0x00C0);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of());
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        WallTurretObjectInstance turret = objectManager.createDynamicObject(
                () -> new WallTurretObjectInstance(
                        new ObjectSpawn(0x0100, 0x0080, Sonic2ObjectIds.WALL_TURRET, 0x74, 0, false, 0),
                        "WallTurret"));

        turret.update(0, main);
        turret.update(1, main);
        turret.update(2, main);

        WallTurretShotInstance shot = objectManager.getActiveObjects().stream()
                .filter(WallTurretShotInstance.class::isInstance)
                .map(WallTurretShotInstance.class::cast)
                .findFirst()
                .orElseThrow();

        assertInstanceOf(WallTurretShotInstance.class, shot);
        assertEquals(Sonic2ObjectIds.PROJECTILE, shot.getSpawn().objectId());
        assertEquals(0x8E, shot.getSpawn().subtype());
        assertEquals(0x0100, shot.getX(), "Centered turret shot should spawn at ObjB8 byte_3BA2A X offset 0");
        assertEquals(0x0098, shot.getY(), "Centered turret shot should spawn at ObjB8 byte_3BA2A Y offset $18");
    }

    @Test
    void wallTurretDetectsClosestQueryOnlySidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x0200, 0x00C0);
        TestablePlayableSprite tails = player("tails", 0x0130, 0x00C0);
        WallTurretObjectInstance turret = new WallTurretObjectInstance(
                new ObjectSpawn(0x0100, 0x0080, Sonic2ObjectIds.WALL_TURRET, 0x74, 0, false, 0),
                "WallTurret");
        turret.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        turret.update(0, main);

        assertEquals(4, intField(turret, "routine"),
                "ObjB8 uses Obj_GetOrientationToPlayer, which chooses the nearest MainCharacter/Sidekick by X");
        assertEquals(2, intField(turret, "fireTimer"));
    }

    @Test
    void wallTurretAimsAndFiresAtClosestQueryOnlySidekick() {
        TestablePlayableSprite main = player("sonic", 0x0200, 0x00C0);
        TestablePlayableSprite tails = player("tails", 0x0130, 0x00C0);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(tails));
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        WallTurretObjectInstance turret = objectManager.createDynamicObject(
                () -> new WallTurretObjectInstance(
                        new ObjectSpawn(0x0100, 0x0080, Sonic2ObjectIds.WALL_TURRET, 0x74, 0, false, 0),
                        "WallTurret"));

        turret.update(0, main);
        turret.update(1, main);
        turret.update(2, main);

        WallTurretShotInstance shot = objectManager.getActiveObjects().stream()
                .filter(WallTurretShotInstance.class::isInstance)
                .map(WallTurretShotInstance.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(0x0111, shot.getX(),
                "ObjB8 should aim right when the closest Obj_GetOrientationToPlayer participant is right of the turret");
        assertEquals(0x0090, shot.getY());
    }

    @Test
    void slidingSpikesTriggerForQueryOnlySidekick() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x0F60, 0x1000);
        SlidingSpikesObjectInstance spikes = new SlidingSpikesObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x76, 0, 0, false, 0),
                "SlidingSpikes");
        spikes.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCamera(cameraAt(main, 0x0F80, 0x1000)));

        spikes.update(0, main);
        spikes.update(1, main);

        assertEquals(0x0FFF, spikes.getX(),
                "Sliding Spikes should use ObjectPlayerQuery participants for approach detection");
    }

    @Test
    void vineSwitchGrabsQueryOnlySidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1028);
        VineSwitchObjectInstance vineSwitch = new VineSwitchObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x7F, 0, 0, false, 0),
                "VineSwitch");
        vineSwitch.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        vineSwitch.update(0, main);

        assertTrue(tails.isObjectControlled(),
                "Vine Switch should use ObjectPlayerQuery participants for grab checks");
        assertRomObjControlBitOneState(tails, "Vine Switch obj_control=1");
        assertEquals(Sonic2AnimationIds.HANG2.id(), tails.getAnimationId());
        assertEquals(1, intField(vineSwitch, "mappingFrame"));
    }

    @Test
    void movingVineGrabsQueryOnlySidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1088);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(tails));
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        MovingVineObjectInstance vine = objectManager.createDynamicObject(
                () -> new MovingVineObjectInstance(
                        new ObjectSpawn(0x1000, 0x1000, 0x80, 0, 0, false, 0),
                        "MovingVine"));

        vine.update(0, main);

        assertTrue(tails.isObjectControlled(),
                "Moving Vine should use ObjectPlayerQuery participants for grab checks");
        assertRomObjControlBitOneState(tails, "Moving Vine obj_control=1");
        assertEquals(Sonic2AnimationIds.HANG2.id(), tails.getAnimationId());
        assertTrue((boolean) field(vine, "player2Grabbed"),
                "Obj80 native P2 grab byte must be driven by sidekick participants");
    }

    @Test
    void wfzGrabObjectGrabsQueryOnlySidekickAsNativeP2() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1008);
        GrabObjectInstance grab = new GrabObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.GRAB, 0, 0, false, 0),
                "Grab");
        grab.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        grab.update(0, main);

        assertFalse(main.isObjectControlled());
        assertRomObjControlBitOneState(tails, "ObjD9 native P2 grab");
        assertEquals(0x1000, tails.getCentreY() & 0xFFFF,
                "ObjD9 writes object y_pos directly to player y_pos");
        assertEquals(Sonic2AnimationIds.HANG2.id(), tails.getAnimationId());
        assertTrue((boolean) field(grab, "player2Grabbed"),
                "ObjD9 objoff_31 must be driven by the sidekick participant");
    }

    @Test
    void wfzGrabObjectUsesRomRenderWidth() {
        GrabObjectInstance grab = new GrabObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.GRAB, 0, 0, false, 0),
                "Grab");

        assertEquals(0x18, grab.getOnScreenHalfWidth(),
                "ObjD9_Init sets width_pixels=$18 for MarkObjGone3/render bounds");
    }

    @Test
    void wfzGrabObjectReleasesQueryOnlySidekickWithDirectionCooldown() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1008);
        GrabObjectInstance grab = new GrabObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.GRAB, 0, 0, false, 0),
                "Grab");
        grab.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        grab.update(0, main);
        tails.setJumpInputPressed(true, true);
        tails.setDirectionalInputPressed(false, false, true, false);
        grab.update(1, main);

        assertFalse(tails.isObjectControlled());
        assertEquals(0xFD00, tails.getYSpeed() & 0xFFFF);
        assertFalse((boolean) field(grab, "player2Grabbed"));
        assertEquals(60, intField(grab, "player2ReleaseDelay"));
    }

    @Test
    void breakablePlatingGrabUsesRomObjControlBitOneState() throws Exception {
        TestablePlayableSprite player = player("sonic", 0x1000, 0x1000);
        BreakablePlatingObjectInstance plating = new BreakablePlatingObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xC1, 0, 0, false, 0),
                "BreakablePlating");

        Method grabPlayer = BreakablePlatingObjectInstance.class
                .getDeclaredMethod("grabPlayer", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        grabPlayer.setAccessible(true);
        grabPlayer.invoke(plating, player);

        assertRomObjControlBitOneState(player, "Breakable Plating obj_control=1");
        assertTrue(plating.requiresContinuousTouchCallbacks(),
                "Touch_Special must refresh ObjC1 collision_property during sustained overlap");
    }

    @Test
    void breakablePlatingDeclaresContinuousTouchResponseProfile() {
        BreakablePlatingObjectInstance plating = new BreakablePlatingObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_PLATING, 0, 0, false, 0),
                "BreakablePlating");

        assertDoesNotThrow(() -> BreakablePlatingObjectInstance.class
                .getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> BreakablePlatingObjectInstance.class
                .getDeclaredMethod("getTouchResponseProfile", boolean.class));

        TouchResponseProfile profile = plating.getTouchResponseProfile();
        assertTrue(profile.continuousCallbacks(),
                "Touch_Special must republish the ObjC1 overlap signal on every sustained-overlap frame");
        assertEquals(profile, plating.getTouchResponseProfile(false));
    }

    @Test
    void wfzBreakablePlatingTouchSignalIsNativeMainCharacterOnly() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        BreakablePlatingObjectInstance plating = new BreakablePlatingObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_PLATING, 0, 0, false, 0),
                "BreakablePlating");
        plating.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        plating.onTouchResponse(tails, null, 0);
        plating.update(0, main);

        assertFalse(main.isObjectControlled(),
                "ObjC1 reads MainCharacter after collision_property; Sidekick touch must not grab P1");
        assertFalse(tails.isObjectControlled(),
                "ObjC1 has no Sidekick grab branch");
    }

    @Test
    void wfzBreakablePlatingNativeMainTouchStillGrabs() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        Sonic2ZoneFeatureProvider zoneFeatures = new Sonic2ZoneFeatureProvider();
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getZoneFeatureProvider()).thenReturn(zoneFeatures);
        BreakablePlatingObjectInstance plating = new BreakablePlatingObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_PLATING, 0, 0, false, 0),
                "BreakablePlating");
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of());
        services.withLevelManager(levelManager);
        plating.setServices(services);

        plating.onTouchResponse(main, null, 0);
        plating.update(0, main);

        assertRomObjControlBitOneState(main, "Breakable Plating native P1 grab");
        assertEquals(0x0FEC, main.getCentreX() & 0xFFFF);
        assertEquals(Sonic2AnimationIds.HANG.id(), main.getAnimationId());
        assertTrue(zoneFeatures.isWfzWindTunnelHolding(),
                "ObjC1 publishes WindTunnel_holding_flag with its Hang/object-control tuple");
    }

    @Test
    void oozPoppingPlatformLockUsesRomObjControlBitOneState() throws Exception {
        TestablePlayableSprite player = player("sonic", 0x1000, 0x1000);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(player, List.of());
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        OOZPoppingPlatformObjectInstance platform = objectManager.createDynamicObject(
                () -> new OOZPoppingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x1000, 0x33, 1, 0, false, 0),
                        "OOZPoppingPlatform"));

        Method lockPlayer = OOZPoppingPlatformObjectInstance.class
                .getDeclaredMethod("lockPlayer", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        lockPlayer.setAccessible(true);
        lockPlayer.invoke(platform, player);

        assertRomObjControlBitOneState(player, "OOZ Popping Platform obj_control=1");
    }

    @Test
    void oozPoppingPlatformLaunchPreservesNativeXSubpixelAndClearsOnObject() throws Exception {
        TestablePlayableSprite player = player("sonic", 0x1020, 0x1000);
        player.setSubpixelRaw(0xCA00, 0x5A00);
        player.setOnObject(true);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(player, List.of());
        OOZPoppingPlatformObjectInstance platform = new OOZPoppingPlatformObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x33, 1, 0, false, 0),
                "OOZPoppingPlatform");
        platform.setServices(services);

        Method launchPlayer = OOZPoppingPlatformObjectInstance.class
                .getDeclaredMethod("launchPlayer", com.openggf.sprites.playable.AbstractPlayableSprite.class, int.class);
        launchPlayer.setAccessible(true);
        launchPlayer.invoke(platform, player, 42);

        assertEquals(0x1000, player.getCentreX() & 0xFFFF,
                "ROM move.w x_pos(a0),x_pos(a1) centers the launched player on Obj33");
        assertEquals(0xCA00, player.getXSubpixelRaw() & 0xFFFF,
                "Obj33 must preserve x_sub when writing the native x_pos word");
        assertFalse(player.isOnObject(), "ROM clears Status_OnObj when launching from Obj33");
        assertFalse(player.isObjectControlled(), "ROM clears obj_control after launch");
        assertTrue(player.getAir(), "ROM sets Status_InAir on launch");
        assertEquals(0x800, player.getGSpeed() & 0xFFFF);
        assertEquals(0xF000, player.getYSpeed() & 0xFFFF);
        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId());
    }

    @Test
    void oozPoppingPlatformRejectsBit7ObjectControlFreshLanding() {
        TestablePlayableSprite tails = player("tails", 0x1EBB, 0x021D);
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setOnObject(false);
        tails.setYSpeed((short) 0x04D8);
        tails.setGSpeed((short) 0x0954);
        tails.setRenderFlagOnScreen(true);
        ObjectControlState.nativeBit7FullControl().applyTo(tails);

        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(tails, List.of());
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        OOZPoppingPlatformObjectInstance platform = objectManager.createDynamicObject(
                () -> new OOZPoppingPlatformObjectInstance(
                        new ObjectSpawn(0x1EC0, 0x0238, 0x33, 0, 0, false, 0),
                        "OOZPoppingPlatform"));
        platform.snapshotPreUpdatePosition();

        objectManager.updateSolidContacts(tails);

        assertFalse(tails.isOnObject(),
                "Obj33 must branch to SolidObject_TestClearPush for obj_control=$81 "
                        + "before SolidObject_TopBottom can re-seat Tails (s2.asm:35344-35489)");
        assertTrue(tails.getAir());
        assertEquals(0x021D, tails.getCentreY() & 0xFFFF);
        assertEquals(0x04D8, tails.getYSpeed() & 0xFFFF);
        assertEquals(0x0954, tails.getGSpeed() & 0xFFFF);
    }

    @Test
    void oozPoppingPlatformApexLaunchesStandingSidekickWithoutJavaLockLatch() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x0F83);
        tails.setCpuControlled(true);
        tails.setOnObject(true);
        tails.setAir(false);

        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(tails));
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        OOZPoppingPlatformObjectInstance platform = objectManager.createDynamicObject(
                () -> new OOZPoppingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x1000, 0x33, 1, 0, false, 0),
                        "OOZPoppingPlatform"));
        objectManager.forceRidingObjectForBootstrap(tails, platform);
        setField(platform, "mode", oozPoppingPlatformMode("RISE_AND_LAUNCH"));
        setField(platform, "currentY", 0x0F83);
        setField(platform, "velocity", 0);
        setField(platform, "mainCharLocked", false);
        setField(platform, "sidekickLocked", false);

        platform.update(17, main);

        assertFalse(tails.isOnObject(), "Obj33 loc_23D60 clears Status_OnObj for any standing P2 bit");
        assertTrue(tails.getAir(), "Obj33 loc_23D60 sets Status_InAir from the platform status bit");
        assertEquals(0x1000, tails.getCentreX() & 0xFFFF);
        assertEquals(0x800, tails.getGSpeed() & 0xFFFF);
        assertEquals(0xF000, tails.getYSpeed() & 0xFFFF);
        assertEquals(Sonic2AnimationIds.ROLL.id(), tails.getAnimationId());
    }

    @Test
    void oozPoppingPlatformApexLaunchPreservesRiderNativeY() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1020, 0x0F75);
        main.setOnObject(true);
        main.setAir(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(main);

        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of());
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        services.withObjectManager(objectManager);
        OOZPoppingPlatformObjectInstance platform = objectManager.createDynamicObject(
                () -> new OOZPoppingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x1000, 0x33, 1, 0, false, 0),
                        "OOZPoppingPlatform"));
        objectManager.forceRidingObjectForBootstrap(main, platform);
        setField(platform, "mode", oozPoppingPlatformMode("RISE_AND_LAUNCH"));
        setField(platform, "currentY", 0x0F83);
        setField(platform, "velocity", 0);
        setField(platform, "mainCharLocked", true);
        setField(platform, "sidekickLocked", false);

        platform.update(17, main);

        assertEquals(0x0F75, main.getCentreY() & 0xFFFF,
                "ROM Obj33 launch does not write y_pos(a1) at the apex");
        assertFalse(main.isOnObject(), "ROM clears Status_OnObj when Obj33 launches the rider");
        assertFalse(main.isObjectControlled(), "ROM clears obj_control after Obj33 launch");
        assertTrue(main.getAir(), "ROM sets Status_InAir on Obj33 launch");
        assertEquals(0x1000, main.getCentreX() & 0xFFFF);
        assertEquals(0x800, main.getGSpeed() & 0xFFFF);
        assertEquals(0xF000, main.getYSpeed() & 0xFFFF);
        assertEquals(Sonic2AnimationIds.ROLL.id(), main.getAnimationId());
    }

    @Test
    void flipperAppliesCheckpointContactToQueryOnlySidekick() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1010, 0x1000);
        FlipperObjectInstance flipper = new FlipperObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x86, 0x01, 0, false, 0),
                "Flipper");
        flipper.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCheckpointBatch(new SolidCheckpointBatch(
                        flipper,
                        Map.of(tails, pushingContact()))));

        flipper.update(0, main);

        assertEquals(0x1000, tails.getXSpeed() & 0xFFFF,
                "Flipper should consume ObjectPlayerQuery participants for manual checkpoint contact");
        assertEquals(0x1000, tails.getGSpeed() & 0xFFFF);
    }

    @Test
    void verticalFlipperMovementSuppressionPreservesExistingObjectControlBits() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        tails.applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementActive());
        FlipperObjectInstance flipper = new FlipperObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x86, 0x00, 0, false, 0),
                "Flipper");
        flipper.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCheckpointBatch(new SolidCheckpointBatch(
                        flipper,
                        Map.of(tails, standingContact()))));

        flipper.update(0, main);

        assertTrue(tails.isObjectControlled(),
                "Obj86's local movement suppression must not clear unrelated object-control ownership");
        assertTrue(tails.isObjectControlAllowsCpu(),
                "Obj86 must preserve bit-0-to-6 CPU allowance from the owning object");
        assertTrue(tails.isObjectControlSuppressesMovement(),
                "Obj86 still suppresses movement while standing on the vertical flipper");
        assertFalse(tails.isTouchResponseSuppressedByObjectControl(),
                "Preserved bit-0-to-6 control must keep touch response active");
    }

    @Test
    void verticalFlipperLaunchReadsLogicalP1ButRawP2JumpPress() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        tails.setCpuControlled(true);
        SidekickCpuController cpuController = new SidekickCpuController(tails, main);
        tails.setCpuController(cpuController);

        FlipperObjectInstance flipper = new FlipperObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x86, 0x00, 0, false, 0),
                "Flipper");
        Method hasVerticalLaunchPress = FlipperObjectInstance.class
                .getDeclaredMethod("hasVerticalLaunchPress", AbstractPlayableSprite.class);
        hasVerticalLaunchPress.setAccessible(true);

        main.setLogicalInputState(false, false, false, false, true, true);
        assertTrue((boolean) hasVerticalLaunchPress.invoke(flipper, main),
                "Obj86 reads Ctrl_1_Logical's jump-press bit for the MainCharacter");

        tails.setJumpInputPressed(true, true);
        cpuController.setController2Input(0, 0);
        assertFalse((boolean) hasVerticalLaunchPress.invoke(flipper, tails),
                "CPU Tails' synthesized follow jump is not raw Ctrl_2 and must not trigger Obj86 launch");

        cpuController.setController2Input(0, AbstractPlayableSprite.INPUT_JUMP);
        assertTrue((boolean) hasVerticalLaunchPress.invoke(flipper, tails),
                "Obj86 reads raw Ctrl_2 jump press for the Sidekick");
    }

    @Test
    void verticalFlipperRollOwnershipClearsStaleObj85PreservedRollLatch() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        tails.setAir(false);
        tails.preserveRollingOnNextRollStop();
        FlipperObjectInstance flipper = new FlipperObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x86, 0x00, 0, false, 0),
                "Flipper");
        flipper.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCheckpointBatch(new SolidCheckpointBatch(
                        flipper,
                        Map.of(tails, standingContact()))));

        flipper.update(0, main);

        assertTrue(tails.getRolling(), "Obj86 first stand curls the player");
        assertTrue(tails.getPinballMode(), "Obj86 owns a temporary pinball-mode guard while standing");
        assertFalse(tails.shouldPreserveRollingOnNextRollStop(),
                "Obj86's explicit roll ownership must clear stale Obj85 preserved-roll handoff state");
    }

    @Test
    void springboardLaunchesQueryOnlySidekick() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        SpringboardObjectInstance springboard = new SpringboardObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x40, 0, 0, false, 0),
                "Springboard");
        springboard.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCheckpointBatch(new SolidCheckpointBatch(
                        springboard,
                        Map.of(tails, standingContact()))));

        springboard.update(0, main);
        springboard.update(1, main);

        assertTrue(tails.getAir(),
                "Springboard should launch sidekick participants from ObjectPlayerQuery");
        assertEquals(0xFB00, tails.getYSpeed() & 0xFFFF);
    }

    @Test
    void springboardSlopedSolidBypassesOffscreenSolidGate() {
        SpringboardObjectInstance springboard = new SpringboardObjectInstance(
                new ObjectSpawn(0x1623, 0x0788, 0x40, 0, 0, false, 0),
                "Springboard");

        assertTrue(springboard.bypassesOffscreenSolidGate(),
                "Obj40_Main calls SlopedSolid_SingleCharacter, which jumps to SlopedSolid_cont "
                        + "without SolidObject_OnScreenTest (s2.asm:52292-52313, 35126, 35263)");
    }

    @Test
    void lateralCannonDropsQueryOnlyRidingSidekickOnRetract() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, new TestObjectServices());
        LateralCannonObjectInstance cannon = new LateralCannonObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xBE, 0, 0, false, 0),
                "LateralCannon");
        cannon.setServices(new QueryOnlyPlayerServices(main, List.of(tails)).withObjectManager(objectManager));
        objectManager.forceRidingObjectForBootstrap(tails, cannon);
        tails.setOnObject(true);
        tails.setAir(false);

        for (int frame = 0; frame < 190; frame++) {
            cannon.update(frame, main);
        }

        assertFalse(objectManager.isRidingObject(tails, cannon),
                "Lateral cannon should drop sidekick riders from ObjectPlayerQuery on retract");
        assertFalse(tails.isOnObject());
        assertTrue(tails.getAir());
    }

    @Test
    void lateralCannonUsesRomRenderWidth() {
        LateralCannonObjectInstance cannon = new LateralCannonObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.LATERAL_CANNON, 0, 0, false, 0),
                "LateralCannon");

        assertEquals(0x18, cannon.getOnScreenHalfWidth(),
                "ObjBE_SubObjData sets width_pixels=$18 for MarkObjGone/render bounds");
    }

    @Test
    void wfzRivetBustIsNativeMainCharacterOnly() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        tails.setRolling(true);
        RivetObjectInstance rivet = new RivetObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.RIVET, 0, 0, false, 0),
                "Rivet");
        rivet.setServices(new QueryOnlyPlayerServices(main, List.of(tails)).withCamera(focusedCamera(main)));

        rivet.update(0, main);
        rivet.onSolidContact(tails, new SolidContact(true, false, false, true, false), 0);

        assertFalse(rivet.isDestroyed(),
                "ObjC2 reads MainCharacter+anim and has no Sidekick bust path");
    }

    @Test
    void wfzRivetStillBustsForRollingNativeMainCharacter() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setRolling(true);
        main.setAnimationId(Sonic2AnimationIds.ROLL.id());
        RivetObjectInstance rivet = new RivetObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.RIVET, 0, 0, false, 0),
                "Rivet");
        rivet.setServices(new QueryOnlyPlayerServices(main, List.of()).withCamera(focusedCamera(main)));

        rivet.update(0, main);
        rivet.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        assertTrue(rivet.isDestroyed(),
                "ObjC2 should still bust when native P1 is rolling on the rivet");
        assertTrue(main.getAir());
        assertFalse(main.isOnObject());
    }

    @Test
    void wfzRivetBustsFromCachedRollAnimationNotRollingStatus() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setRolling(false);
        main.setAnimationId(Sonic2AnimationIds.ROLL.id());
        RivetObjectInstance rivet = new RivetObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.RIVET, 0, 0, false, 0),
                "Rivet");
        rivet.setServices(new QueryOnlyPlayerServices(main, List.of()).withCamera(focusedCamera(main)));

        rivet.update(0, main);
        main.setAnimationId(0);
        rivet.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        assertTrue(rivet.isDestroyed(),
                "ObjC2 caches MainCharacter+anim before SolidObject and busts on cached anim==2");
    }

    @Test
    void cpzBreakableBlockBreaksFromRollAnimationNotRollingStatus() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setRolling(false);
        main.setAnimationId(Sonic2AnimationIds.ROLL.id());
        BreakableBlockObjectInstance block = new BreakableBlockObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_BLOCK, 0, 0, false, 0),
                "BreakableBlock");
        block.setServices(new QueryOnlyPlayerServices(main, List.of()).withGameState(new GameStateManager()));

        block.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        assertTrue(block.isDestroyed(),
                "Obj32 snapshots player anim before SolidObject and breaks on anim==Roll");
        assertEquals(0xFD00, main.getYSpeed() & 0xFFFF);
    }

    @Test
    void breakableBlockKeepsSolidObjectExactRightEdgeContact() {
        BreakableBlockObjectInstance block = new BreakableBlockObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_BLOCK, 0, 0, false, 0),
                "BreakableBlock");

        assertTrue(block.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj32's SolidObject bhi gate accepts relX == 2*d1");
    }

    @Test
    void cpzBreakableBlockRemainsSolidForLaterParticipantsInDestroyPass() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setRolling(false);
        main.setAnimationId(Sonic2AnimationIds.ROLL.id());
        TestablePlayableSprite sidekick = player("tails", 0x1000, 0x1000);
        BreakableBlockObjectInstance block = new BreakableBlockObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.BREAKABLE_BLOCK, 0, 0, false, 0),
                "BreakableBlock");
        block.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)).withGameState(new GameStateManager()));

        block.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        assertTrue(block.isDestroyed(),
                "Obj32 should destroy as soon as the rolling standing player breaks it");
        assertTrue(block.isSolidFor(sidekick),
                "ROM Obj32 computes both players' standing state before the destroy branch");
    }

    @Test
    void htzSmashableGroundBreaksFromRollAnimationNotRollingStatus() {
        TestablePlayableSprite main = player("sonic", 0x1000, 0x1000);
        main.setRolling(false);
        main.setAnimationId(Sonic2AnimationIds.ROLL.id());
        main.setTopSolidBit((byte) 0x0E);
        SmashableGroundObjectInstance ground = new SmashableGroundObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.SMASHABLE_GROUND, 0, 0, false, 0),
                "SmashableGround");
        GameStateManager gameState = new GameStateManager();
        ground.setServices(new QueryOnlyPlayerServices(main, List.of()).withGameState(gameState));

        ground.update(0, main);
        ground.onSolidContact(main, new SolidContact(true, false, false, true, false), 0);

        assertTrue(ground.isDestroyed(),
                "Obj2F snapshots player anim before SolidObject and breaks on anim==Roll");
        assertTrue(main.getAir());
    }

    @Test
    void cnzConveyorWidthUsesRomByteShiftWrap() {
        TestablePlayableSprite player = player("sonic", 0x1D9F, 0x061F);
        player.setAir(false);
        CNZConveyorBeltObjectInstance conveyor = new CNZConveyorBeltObjectInstance(
                new ObjectSpawn(0x1DFA, 0x0620, 0x72, 0x90, 0, false, 0),
                "CNZConveyorBelt");

        conveyor.update(0, player);

        assertEquals(0x1D9F, player.getCentreX() & 0xFFFF,
                "Obj72 stores (subtype & $7F) << 4 into a byte; WFZ subtype $90 wraps width to zero");
    }

    @Test
    void cnzConveyorShiftsNativePositionWhenInsideWrappedBounds() {
        TestablePlayableSprite player = player("sonic", 0x100F, 0x0FF0);
        player.setAir(false);
        CNZConveyorBeltObjectInstance conveyor = new CNZConveyorBeltObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x72, 0x01, 0, false, 0),
                "CNZConveyorBelt");

        conveyor.update(0, player);

        assertEquals(0x1011, player.getCentreX() & 0xFFFF);
    }

    @Test
    void seesawAssignsQueryOnlySidekickToNativeP2StandingSlot() {
        TestablePlayableSprite main = player("sonic", 0x1400, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        SeesawObjectInstance seesaw = new SeesawObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x14, 0xFF, 0, false, 0),
                "Seesaw");
        seesaw.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCheckpointBatch(new SolidCheckpointBatch(
                        seesaw,
                        Map.of(tails, standingContact()))));

        seesaw.update(0, main);

        assertSame(tails, seesaw.getStandingPlayer2(),
                "Seesaw has native P1/P2 standing bits, so P2 must come from ObjectPlayerQuery NATIVE_P1_P2");
    }

    @Test
    void spinyTargetsQueryOnlyClosestSidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1800, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1008, 0x1000);
        SpinyBadnikInstance spiny = new SpinyBadnikInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xA5, 0, 0, false, 0));
        spiny.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        // ROM ObjA5_Init `move.w #$80,objoff_2A` also seeds the adjacent detect
        // lockout byte objoff_2B=$80 (objoff_2A=$2A, objoff_2B=$2B; s2.constants.asm
        // line 133), so loc_38B10 skips detection for the first 128 frames
        // (s2.asm:76362, 76367-76370). Advance past that initial lockout, then the
        // spiny's first detection must resolve the closest sidekick (Tails) and attack.
        for (int i = 0; i <= 0x80; i++) {
            spiny.update(i, main);
        }

        assertEquals("ATTACKING", field(spiny, "state").toString(),
                "Spiny should resolve closest P2 candidates through ObjectPlayerQuery");
    }

    @Test
    void mtzSlicerThrowUsesClosestNativePlayerByRomX() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0x0F00, 0, 0x1100, 0x0200, 0);
        TestablePlayableSprite main = player("sonic", 0x0F90, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1010, 0x1000);
        SlicerBadnikInstance slicer = new SlicerBadnikInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.SLICER, 0, 0, false, 0));
        slicer.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        try {
            // ROM ObjA1_Init (routine 0) consumes the first object frame without
            // running the throw-detection logic (s2.asm:75777-75788); ObjA1_Main
            // (routine 2) first runs on the next frame. Step the INIT frame, then
            // the WALKING frame that exercises Obj_GetOrientationToPlayer.
            slicer.update(0, main);
            slicer.update(0, main);

            assertFalse("THROW_WINDUP".equals(field(slicer, "state").toString()),
                    "ObjA1 must run Obj_GetOrientationToPlayer against nearest native P1/P2, not always P1");
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }
    }

    @Test
    void mtzSlicerPincerHomesTowardClosestNativePlayerByRomX() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0x0F00, 0, 0x1100, 0x0200, 0);
        TestablePlayableSprite main = player("sonic", 0x0F00, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1010, 0x1000);
        SlicerPincerInstance pincer = new SlicerPincerInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.SLICER_PINCERS, 0, 0, false, 0),
                null,
                0x1000,
                0x1000,
                0,
                false,
                0x78);
        pincer.setServices(new QueryOnlyPlayerServices(main, List.of(tails)));

        try {
            pincer.update(0, main);

            assertEquals(0x10, intField(pincer, "xVelocity"),
                    "ObjA2 homing should accelerate toward nearest native P1/P2 by ROM X");
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }
    }

    @Test
    void arzBossInitWaitsForQueryOnlyExtendedSidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x2A80, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x2AA0, 0x1000);
        TestablePlayableSprite extraSidekick = player("knuckles", 0x2B80, 0x1000);
        Sonic2ARZBossInstance boss = new Sonic2ARZBossInstance(
                new ObjectSpawn(0x2AE0, 0x0388, 0x89, 0, 0, false, 0));
        boss.setServices(new QueryOnlyPlayerServices(main, List.of(tails, extraSidekick)));

        Method checkInitConditions = Sonic2ARZBossInstance.class
                .getDeclaredMethod("checkInitConditions", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        checkInitConditions.setAccessible(true);

        assertFalse((boolean) checkInitConditions.invoke(boss, main),
                "ARZ boss intro should wait for every engine sidekick exposed through ObjectPlayerQuery");
    }

    @Test
    void tiltingPlatformOrientationUsesQueryPlayersWhenRawSidekickListIsEmpty() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1200, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x0FF0, 0x1000);
        TiltingPlatformObjectInstance platform = new TiltingPlatformObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xB6, 0x04, 0, false, 0));
        platform.setServices(new QueryOnlyPlayerServices(main, List.of(tails))
                .withCamera(focusedCamera(main)));

        Method isPlayerToLeft = TiltingPlatformObjectInstance.class
                .getDeclaredMethod("isPlayerToLeft", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        isPlayerToLeft.setAccessible(true);

        assertTrue((boolean) isPlayerToLeft.invoke(platform, main),
                "ObjB6 orientation already uses every engine sidekick, but participants must come from ObjectPlayerQuery");
    }

    @Test
    void tiltingPlatformDropsQueryOnlyRidingSidekick() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x1200, 0x1000);
        TestablePlayableSprite tails = player("tails", 0x1000, 0x1000);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, new TestObjectServices());
        TiltingPlatformObjectInstance platform = new TiltingPlatformObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0xB6, 0, 0, false, 0));
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(tails))
                .withObjectManager(objectManager);
        services.withCamera(focusedCamera(main));
        platform.setServices(services);
        objectManager.forceRidingObjectForBootstrap(tails, platform);
        tails.setOnObject(true);
        tails.setAir(false);

        Method dropRidingPlayers = TiltingPlatformObjectInstance.class.getDeclaredMethod("dropRidingPlayers");
        dropRidingPlayers.setAccessible(true);
        dropRidingPlayers.invoke(platform);

        assertFalse(objectManager.isRidingObject(tails, platform),
                "ObjB6 drop already applies to engine sidekicks, but participants must come from ObjectPlayerQuery");
        assertFalse(tails.isOnObject());
        assertTrue(tails.getAir());
    }

    private static TestablePlayableSprite player(String code, int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) x, (short) y);
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        return player;
    }

    private static Camera focusedCamera(TestablePlayableSprite player) {
        Camera camera = mock(Camera.class);
        when(camera.getFocusedSprite()).thenReturn(player);
        return camera;
    }

    private static Camera cameraAt(TestablePlayableSprite player, int x, int y) {
        Camera camera = focusedCamera(player);
        when(camera.getX()).thenReturn((short) x);
        when(camera.getY()).thenReturn((short) y);
        return camera;
    }

    private static int intField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Object field(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertRomObjControlBitOneState(TestablePlayableSprite player, String context) {
        assertTrue(player.isObjectControlled(), context + " should set object control");
        assertTrue(player.isObjectControlAllowsCpu(), context + " should allow sidekick CPU dispatch");
        assertTrue(player.isObjectControlSuppressesMovement(), context + " should suppress normal movement");
        assertFalse(player.isTouchResponseSuppressedByObjectControl(),
                context + " should not suppress touch responses");
    }

    private static PlayerSolidContactResult pushingContact() {
        return new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                true,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, false, true),
                0);
    }

    private static PlayerSolidContactResult standingContact() {
        return new PlayerSolidContactResult(
                ContactKind.TOP,
                true,
                false,
                false,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, true, false),
                0);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> oozPoppingPlatformMode(String name) throws Exception {
        Class<?> modeClass = Class.forName(
                "com.openggf.game.sonic2.objects.OOZPoppingPlatformObjectInstance$Mode");
        return Enum.valueOf((Class) modeClass, name);
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private SolidExecutionRegistry solidExecution = SolidExecutionRegistry.inert();
        private ObjectManager objectManager;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        QueryOnlyPlayerServices withObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
            return this;
        }

        QueryOnlyPlayerServices withCheckpointBatch(SolidCheckpointBatch batch) {
            this.solidExecution = new FixedSolidExecutionRegistry(batch);
            return this;
        }

        @Override
        public SolidExecutionRegistry solidExecutionRegistry() {
            return solidExecution;
        }
    }

    private static final class FixedSolidExecutionRegistry implements SolidExecutionRegistry {
        private final SolidCheckpointBatch batch;

        private FixedSolidExecutionRegistry(SolidCheckpointBatch batch) {
            this.batch = batch;
        }

        @Override
        public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {
        }

        @Override
        public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {
        }

        @Override
        public ObjectSolidExecutionContext currentObject() {
            return new ObjectSolidExecutionContext(this, batch.object(), () -> batch);
        }

        @Override
        public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) {
            return PlayerStandingState.NONE;
        }

        @Override
        public void publishCheckpoint(SolidCheckpointBatch batch) {
        }

        @Override
        public void endObject(ObjectInstance object) {
        }

        @Override
        public void finishFrame() {
        }

        @Override
        public void clearTransientState() {
        }
    }
}
