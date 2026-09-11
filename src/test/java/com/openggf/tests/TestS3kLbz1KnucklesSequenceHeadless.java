package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1CollapseChild;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1Instance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1RangeHelper;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1ThrownBomb;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1Instance;
import com.openggf.game.sonic3k.objects.LbzMinibossBoxRig;
import com.openggf.game.sonic3k.objects.LbzMinibossInstance;
import com.openggf.game.sonic3k.objects.Lbz1RobotnikEventController;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kLbz1KnucklesSequenceHeadless {
    private static final int SPAWN_X = 0x3BF4;
    private static final int SPAWN_Y = 0x00EC;

    private Object oldSkipIntros;
    private Object oldMainCharacter;
    private Object oldSidekickCharacters;

    @BeforeEach
    void setUpConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacters = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
    }

    @AfterEach
    void restoreConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacters != null ? oldSidekickCharacters : "");
    }

    @Test
    void subtype14RoutesToLbz1WhileMhz1SubtypeRemainsMapped() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance lbz1 = registry.create(new ObjectSpawn(
                SPAWN_X, SPAWN_Y, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x14, 0, false, 0));
        ObjectInstance mhz1 = registry.create(new ObjectSpawn(
                SPAWN_X, SPAWN_Y, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));

        assertInstanceOf(CutsceneKnucklesLbz1Instance.class, lbz1,
                "Subtype $14 is CutsceneKnux_LBZ1 in CutsceneKnuckles_Index.");
        assertInstanceOf(CutsceneKnucklesMhz1Instance.class, mhz1,
                "Subtype $1C must remain routed to CutsceneKnux_MHZ1.");
    }

    @Test
    void lbz1RobotnikFactoryRoutesAndArmsCollapseOnlyWhenGroundedAtBossThreshold() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertInstanceOf(Lbz1RobotnikEventController.class, registry.create(new ObjectSpawn(
                0x3EC0, 0x01A0, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0)));
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.forceRoutineForTest(0x06);

        GameServices.camera().setX((short) 0x3B3F);
        player.setCentreY((short) 0x01C0);
        player.setAir(false);
        robotnik.update(0, player);
        assertFalse(manager.getLbzEvents().isEndingCollapseActive(),
                "Camera_X_pos must reach $3B40 before Obj_LBZ1Robotnik sets Events_fg_4.");

        GameServices.camera().setX((short) 0x3B40);
        player.setCentreY((short) 0x01BF);
        robotnik.update(0, player);
        assertFalse(manager.getLbzEvents().isEndingCollapseActive(),
                "Player y_pos must be at least $1C0 before Obj_LBZ1Robotnik sets Events_fg_4.");

        player.setCentreY((short) 0x01C0);
        player.setAir(true);
        robotnik.update(0, player);
        assertFalse(manager.getLbzEvents().isEndingCollapseActive(),
                "Status_InAir blocks the Obj_LBZ1Robotnik collapse trigger.");

        player.setAir(false);
        robotnik.update(0, player);
        assertTrue(manager.getLbzEvents().isEndingCollapseActive(),
                "Grounded Player_1 at Camera_X_pos=$3B40,y_pos=$1C0 should set Events_fg_4.");
        assertEquals(0x08, robotnik.getRoutineForTest(),
                "After setting Events_fg_4, Obj_LBZ1Robotnik waits for the screen event to clear.");
    }

    @Test
    void lbz1RobotnikRunsRomInitBeforeCollapseTriggerRoutine() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();

        GameServices.camera().setMinX((short) 0);
        GameServices.camera().setMaxX((short) 0x4000);
        robotnik.update(0, player);

        assertEquals(0x02, robotnik.getRoutineForTest(),
                "loc_8CB9E performs SetUp_ObjAttributes/Swing_Setup1, then returns at routine $02.");
        assertEquals(0x3820, GameServices.camera().getMinX() & 0xFFFF,
                "Obj_LBZ1Robotnik init locks Camera_min_X_pos to $3820.");
        assertEquals(0x3AE8, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_LBZ1Robotnik init locks Camera_max_X_pos to $3AE8.");
        assertEquals(0x00C0, robotnik.getYVelocityForTest() & 0xFFFF,
                "Swing_Setup1 seeds y_vel=$00C0 for Robotnik's hover.");
        assertEquals(0x00C0, robotnik.getSwingMaxVelocityForTest() & 0xFFFF,
                "Swing_Setup1 stores $3E=$00C0.");
        assertEquals(0x0010, robotnik.getSwingAccelerationForTest() & 0xFFFF,
                "Swing_Setup1 stores $40=$0010.");
    }

    @Test
    void lbz1RobotnikApproachUsesRomProximityAndRiseTeleportSequence() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();

        robotnik.update(0, player);
        player.setCentreX((short) 0x3EC0);
        player.setCentreY((short) 0x01A0);
        player.setAir(false);
        robotnik.update(1, player);

        assertEquals(0x04, robotnik.getRoutineForTest(),
                "loc_8CBF2 enters the rise routine when Player_1 is within $70 x and $60 y while grounded.");
        assertEquals(0xFC00, robotnik.getYVelocityForTest() & 0xFFFF,
                "loc_8CC20 seeds y_vel=-$400.");

        for (int frame = 2; frame < 80 && robotnik.getRoutineForTest() != 0x06; frame++) {
            robotnik.update(frame, player);
        }

        assertEquals(0x06, robotnik.getRoutineForTest(),
                "loc_8CC3C teleports Robotnik back to the collapse trigger position after rising above y=$300.");
        assertEquals(0x3EC0, robotnik.getX());
        assertEquals(0x01A0, robotnik.getY());
        assertTrue(robotnik.isFacingLeftForTest(),
                "loc_8CC3C sets render_flags bit 0 before the collapse-trigger wait.");
    }

    @Test
    void lbz1RobotnikPostCollapseLaunchSpawnsVisibleFlameAndMinibossHandoff() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();

        robotnik.forceRoutineForTest(0x0A);
        player.setCentreX((short) 0x3EC0);
        player.setCentreY((short) 0x01A0);
        robotnik.update(0, player);
        assertEquals(0x0C, robotnik.getRoutineForTest(),
                "loc_8CCB4 enters the second rise when a player is within $60 of Robotnik.");

        for (int frame = 1; frame < 120 && robotnik.getRoutineForTest() != 0x0E; frame++) {
            robotnik.update(frame, player);
        }

        assertEquals(0x0E, robotnik.getRoutineForTest(),
                "loc_8CCF6 starts the diagonal escape once y_pos reaches $12C.");
        assertEquals(0x0200, robotnik.getXVelocityForTest() & 0xFFFF);
        assertEquals(0x0200, robotnik.getYVelocityForTest() & 0xFFFF);
        assertTrue(robotnik.isFlameVisibleForTest(),
                "Child1_MakeRoboShipFlame becomes visible once Robotnik has nonzero x_vel.");

        for (int frame = 120; frame < 120 + 0x20; frame++) {
            robotnik.update(frame, player);
        }

        ObjectInstance miniboss = activeObjectByName("LBZMiniboss");
        assertNotNull(miniboss,
                "loc_8CD6C turns the carried box into Obj_Wait($1F) before ChildObjDat_8D264 spawns the miniboss.");
        assertEquals(1, activeSongFadeTransitions(),
                "loc_8CD98 calls sub_8D116 before creating Obj_LBZMiniboss; it must allocate "
                        + "Obj_Song_Fade_Transition for mus_Miniboss.");
        assertEquals(0x3EC0, miniboss.getX(),
                "Obj_LBZMiniboss must inherit the dropped box child's x_pos, not Robotnik's later escape x_pos.");
        assertEquals(0x0160, miniboss.getY(),
                "The dropped box child is created at parent y_pos+$34 when Robotnik reaches y_pos=$12C.");
        assertEquals(0x0E, robotnik.getRoutineForTest(),
                "Obj_LBZ1Robotnik is still in the diagonal escape when the dropped box wait callback fires.");

        for (int frame = 120 + 0x20; frame < 260 && robotnik.getRoutineForTest() != 0x10; frame++) {
            robotnik.update(frame, player);
        }

        assertEquals(0x10, robotnik.getRoutineForTest(),
                "loc_8CD40 clears y_vel and switches to the post-handoff ship movement once y_pos reaches $1B8.");
        assertNotNull(miniboss,
                "ChildObjDat_8D264 creates Obj_LBZMiniboss as Robotnik hands off to the miniboss object.");
        assertFalse(miniboss instanceof PlaceholderObjectInstance,
                "The Robotnik handoff must instantiate the concrete Obj_LBZMiniboss, not the old placeholder.");
        assertInstanceOf(LbzMinibossInstance.class, miniboss,
                "Obj_LBZ1Robotnik's ChildObjDat_8D264 target is Obj_LBZMiniboss.");
    }

    @Test
    void lbzMinibossFactoryInitializesRomStateAndChildren() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance created = registry.create(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));

        assertInstanceOf(LbzMinibossInstance.class, created,
                "S3KL object $C9 must route to Obj_LBZMiniboss in Launch Base.");

        LbzMinibossInstance miniboss = GameServices.level().getObjectManager().createDynamicObject(
                () -> new LbzMinibossInstance(new ObjectSpawn(
                        0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0)));
        miniboss.update(0, player);

        assertEquals(0x02, miniboss.getRoutineForTest(),
                "loc_72400 runs SetUp_ObjAttributes, seeds $2E=$10, then waits in routine $02.");
        assertEquals(0x10, miniboss.getWaitTimerForTest(),
                "Obj_LBZMiniboss init writes $2E=$10 before loc_72458.");
        assertEquals(6, miniboss.getCollisionProperty(),
                "loc_72400 writes collision_property(a0)=6.");
        assertEquals(0, miniboss.getCollisionFlags(),
                "ObjDat_LBZMiniboss has collision_flags=0 until loc_724C8 opens the boss.");
        assertEquals(13, miniboss.getPanelCountForTest(),
                "ChildObjDat_7296E plus the two six-piece link lists create 13 visible children.");
        assertEquals(Sonic3kObjectIds.LBZ_MINIBOSS, GameServices.gameState().getCurrentBossId(),
                "loc_72400 sets Boss_flag; engine state should claim the LBZ miniboss slot.");
    }

    @Test
    void lbzMinibossOpenStateTracksPlayerAndUsesRomHitWindow() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        LbzMinibossInstance miniboss = GameServices.level().getObjectManager().createDynamicObject(
                () -> new LbzMinibossInstance(new ObjectSpawn(
                        0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0)));
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        player.setCentreX((short) 0x3EA0);
        player.setCentreY((short) 0x0200);
        miniboss.update(0, player);

        assertEquals(0x08, miniboss.getRoutineForTest());
        assertEquals(0xFF00, miniboss.getXVelocityForTest() & 0xFFFF,
                "loc_72522 chooses x_vel=-$100 when Player_1 is left of the boss.");
        assertEquals(0x0100, miniboss.getYVelocityForTest() & 0xFFFF,
                "Obj_LBZMiniboss targets Player_1 y_pos-$38, so y_vel=+$100 here.");
        assertEquals(0x06, miniboss.getCollisionFlags(),
                "loc_724C8 opens the boss by writing collision_flags=$06.");

        miniboss.onPlayerAttack(player, new TouchResponseResult(0x06, 0x20, 0x20, TouchCategory.ENEMY));

        assertEquals(5, miniboss.getCollisionProperty(),
                "Touch_Enemy_Part2 consumes one collision_property count on a non-fatal hit.");
        assertEquals(0, miniboss.getCollisionFlags(),
                "loc_72840 holds collision_flags clear during the $20 boss-hit flash.");
        assertEquals(0x20, miniboss.getHitReactionTimerForTest(),
                "loc_7285A writes $20(a0)=$20 for the boss-hit flash window.");
    }

    @Test
    void lbzMinibossTrackerUsesCompleteNativePlayerMovementAtDeadband() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        LbzMinibossInstance miniboss = GameServices.level().getObjectManager().createDynamicObject(
                () -> new LbzMinibossInstance(new ObjectSpawn(
                        0x3ED2, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0)));
        miniboss.forceOpenForTest(0x3ED2, 0x01B8);

        player.setCentreX((short) 0x3ED7);
        player.setCentreY((short) 0x01F0);
        player.setSubpixelRaw(0x1A00, player.getYSubpixelRaw());
        player.setXSpeed((short) 0xFF08);
        miniboss.update(0, player);

        assertEquals(0, miniboss.getXVelocityForTest(),
                "The complete 16:8 movement reaches x=$3ED6, exactly four pixels from the boss; "
                        + "loc_72522 must stop rather than move right from the pre-movement x_pos.");
    }

    @Test
    void lbzMinibossRendersPanelsFromMinibossMappingsNotBoxSheet() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer boxRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS)).thenReturn(bossRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX)).thenReturn(boxRenderer);
        when(bossRenderer.isReady()).thenReturn(true);
        when(boxRenderer.isReady()).thenReturn(true);

        LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withConfiguration(SonicConfigurationService.getInstance()));
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        for (int frame = 0; frame < 0x40; frame++) {
            miniboss.update(frame, null);
        }
        miniboss.appendRenderCommands(new ArrayList<>());

        org.mockito.Mockito.verify(bossRenderer, org.mockito.Mockito.times(14)).drawFrameIndex(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(1));
        org.mockito.Mockito.verify(boxRenderer, org.mockito.Mockito.never()).drawFrameIndex(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void lbzMinibossArmChildrenRenderAsLinkedChains() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS)).thenReturn(bossRenderer);
        when(bossRenderer.isReady()).thenReturn(true);

        LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withConfiguration(SonicConfigurationService.getInstance()));
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        for (int frame = 0; frame < 0x130; frame++) {
            miniboss.update(frame, null);
        }
        miniboss.appendRenderCommands(new ArrayList<>());

        ArgumentCaptor<Integer> xCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> yCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(bossRenderer, org.mockito.Mockito.times(14)).drawFrameIndex(
                org.mockito.ArgumentMatchers.anyInt(),
                xCaptor.capture(),
                yCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(1));
        int maxDistance = 0;
        for (int i = 1; i < xCaptor.getAllValues().size(); i++) {
            int dx = Math.abs(xCaptor.getAllValues().get(i) - 0x3EC0);
            int dy = Math.abs(yCaptor.getAllValues().get(i) - 0x01B8);
            maxDistance = Math.max(maxDistance, Math.max(dx, dy));
        }
        assertTrue(maxDistance >= 0x28,
                "The linked arm child routines wait $100 frames and then propagate $38 bit 1 inward; "
                        + "the arms must extend well beyond the old short constant-orbit layout.");
    }

    @Test
    void lbzMinibossArmChildrenExposeRomHurtTouchRegions() {
        LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new TestObjectServices());
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        TouchResponseProvider.TouchRegion[] regions = miniboss.getMultiTouchRegions();

        assertNotNull(regions,
                "Obj_LBZMiniboss arm children run Draw_And_Touch_Sprite with collision_flags=$98.");
        assertEquals(13, regions.length,
                "Open miniboss touch regions should include the vulnerable body plus twelve harmful arm pieces.");
        assertEquals(0x06, regions[0].collisionFlags(),
                "The parent body keeps collision_flags=$06 while open.");
        long hurtArmRegions = java.util.Arrays.stream(regions)
                .filter(region -> region.collisionFlags() == 0x98)
                .count();
        assertEquals(12, hurtArmRegions,
                "Each linked arm child uses ObjDat3 word_72968 collision_flags=$98.");
        assertEquals(0x3EC2, regions[6].x(),
                "MoveSprite_CircularSimple must carry 16.16 fractions through every first-arm link.");
        assertEquals(0x01A6, regions[6].y(),
                "The outer first-arm panel must retain the accumulated cosine fractions.");
    }

    @Test
    void lbzMinibossArmWaitStartsAfterInitialChildPlacement() {
        LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new TestObjectServices());
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        for (int frame = 0; frame < 0x100; frame++) {
            miniboss.update(frame, null);
        }
        assertEquals(0x02, miniboss.getPanelRoutineForTest(0, false),
                "loc_7261C's creation dispatch places the child without decrementing its $100 Obj_Wait.");

        miniboss.update(0x100, null);
        assertEquals(0x04, miniboss.getPanelRoutineForTest(0, false),
                "The first arm child enters routine $04 only when the following $100 wait underflows.");
    }

    @Test
    void lbzMinibossCenterChildUsesRawAnimationDelay() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS)).thenReturn(bossRenderer);
        when(bossRenderer.isReady()).thenReturn(true);

        LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                0x3EC0, 0x01B8, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withConfiguration(SonicConfigurationService.getInstance()));
        miniboss.forceOpenForTest(0x3EC0, 0x01B8);

        miniboss.appendRenderCommands(new ArrayList<>());
        miniboss.update(1, null);
        miniboss.appendRenderCommands(new ArrayList<>());

        ArgumentCaptor<Integer> frameCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(bossRenderer, org.mockito.Mockito.times(28)).drawFrameIndex(
                frameCaptor.capture(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(1));
        assertEquals(frameCaptor.getAllValues().get(1), frameCaptor.getAllValues().get(15),
                "Obj_LBZMiniboss center child runs Animate_Raw byte_72988, whose first byte delays frame changes; "
                        + "it must not advance every single update.");
    }

    @Test
    void lbz1RobotnikRendersShipHeadAndFlameFromSharedRobotnikShipSheet() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer boxRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX)).thenReturn(boxRenderer);
        when(renderer.isReady()).thenReturn(true);
        when(boxRenderer.isReady()).thenReturn(true);

        Lbz1RobotnikEventController robotnik = new Lbz1RobotnikEventController(new ObjectSpawn(
                0x3EC0, 0x01A0, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0));
        robotnik.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });
        robotnik.forceInitializedForTest(0x3EC0, 0x01A0, 0x0E, 0x0200, 0x0200, true);

        robotnik.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0x0A, 0x3EC0, 0x01A0, true, false, 0);
        verify(renderer).drawFrameIndex(0, 0x3EC0, 0x0184, true, false, 0);
        verify(renderer).drawFrameIndex(6, 0x3EA2, 0x01A0, true, false, 0);
        verify(boxRenderer).drawFrameIndex(6, 0x3EB0, 0x01D4, false, false, 2);
        verify(boxRenderer).drawFrameIndex(6, 0x3ED0, 0x01D4, false, true, 2);
        verify(boxRenderer).drawFrameIndex(9, 0x3EC0, 0x01E8, false, false, 2);
        verify(boxRenderer).drawFrameIndex(3, 0x3ED4, 0x01C8, false, false, 2);
    }

    @Test
    void lbz1RobotnikAnimatesMinibossBoxPiecesDuringEmergence() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer boxRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX)).thenReturn(boxRenderer);
        when(renderer.isReady()).thenReturn(true);
        when(boxRenderer.isReady()).thenReturn(true);

        Lbz1RobotnikEventController robotnik = new Lbz1RobotnikEventController(new ObjectSpawn(
                0x3EC0, 0x012C, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0));
        robotnik.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withConfiguration(SonicConfigurationService.getInstance()));
        robotnik.forceMinibossBoxReleaseForTest(0x3EC0, 0x0160);

        robotnik.appendRenderCommands(new ArrayList<>());
        for (int frame = 0; frame < 2; frame++) {
            robotnik.update(frame, null);
        }
        robotnik.appendRenderCommands(new ArrayList<>());

        ArgumentCaptor<Integer> frameCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(boxRenderer, org.mockito.Mockito.atLeast(11)).drawFrameIndex(
                frameCaptor.capture(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.eq(2));
        assertTrue(frameCaptor.getAllValues().contains(7) || frameCaptor.getAllValues().contains(8),
                "When loc_8CD9C sets parent bit 3, the first box children should run byte_8D280 "
                        + "instead of disappearing with the static carried-box frames.");
    }

    @Test
    void lbz1RobotnikFoldsAwayBurstPanelsAndKeepsDriftersUntilOffscreen() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer boxRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX)).thenReturn(boxRenderer);
        when(renderer.isReady()).thenReturn(true);
        when(boxRenderer.isReady()).thenReturn(true);

        LbzMinibossBoxRig rig = new LbzMinibossBoxRig(0x3EC0, 0x0160);
        rig.release();

        // ROM: the six burst panels run byte_8D280/byte_8D285 and Go_Delete;
        // the four large panels drift ($5F frames at ±$40 subpixel), animate
        // byte_8D28A/byte_8D28F, then keep drawing via loc_8CF1E.
        for (int frame = 0; frame < 0x100; frame++) {
            rig.update(0x3E80);
        }
        rig.draw(boxRenderer, 2);
        org.mockito.Mockito.verify(boxRenderer, org.mockito.Mockito.times(4)).drawFrameIndex(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt());

        // ROM loc_8CF1E: once the camera coarse position moves more than $280
        // past the pieces, they delete.
        rig.update(0x4480);
        org.mockito.Mockito.clearInvocations(boxRenderer);
        rig.draw(boxRenderer, 2);
        org.mockito.Mockito.verify(boxRenderer, org.mockito.Mockito.never()).drawFrameIndex(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt());
        assertFalse(rig.hasVisiblePieces(),
                "All ten box pieces should be gone once the camera leaves the arena.");
    }

    @Test
    void lbz1RobotnikInitLoadsSharedRobotnikShipAndBoxArtInLbz1() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();

        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.update(0, player);

        ObjectRenderManager renderManager = GameServices.level().getObjectRenderManager();
        assertNotNull(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP),
                "Obj_LBZ1Robotnik uses the shared Robotnik ship mapping, so LBZ1 must expose that standalone PLC art.");
        assertNotNull(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX),
                "Obj_LBZ1Robotnik queues ArtKosM_LBZMinibossBox and renders the carried yellow box children.");
    }

    @Test
    void lbz1RobotnikInitQueuesMinibossBoxKosmOnce() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        List<HardwareWorkHandle> before = pendingKosModuleHandles();

        robotnik.update(0, player);

        List<HardwareWorkHandle> submitted = addedKosModuleHandles(before);
        assertEquals(1, submitted.size(),
                "loc_8CB9E must submit ArtKosM_LBZMinibossBox exactly once.");
        HardwareWorkHandle handle = submitted.getFirst();
        var queue = S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator()).moduleQueue();
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR,
                queue.descriptor(handle).sourceAddress());
        assertEquals(Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX * 32,
                queue.descriptor(handle).destinationAddress());

        robotnik.update(1, player);

        assertEquals(submitted, addedKosModuleHandles(before),
                "ROUTINE_APPROACH_HOVER must not resubmit the initialization parent.");
    }

    @Test
    void lbz1RobotnikUsesNormalBossHitReactionWithoutDefeat() {
        List<Integer> sfx = new ArrayList<>();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        Lbz1RobotnikEventController robotnik = new Lbz1RobotnikEventController(new ObjectSpawn(
                0x3EC0, 0x01A0, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfx.add(soundId);
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withConfiguration(SonicConfigurationService.getInstance());
        robotnik.setServices(services);

        assertEquals(0x0F, robotnik.getCollisionFlags(),
                "ObjDat_LBZ1Robotnik sets collision_flags=$0F through SetUp_ObjAttributes.");
        assertEquals(0xFF, robotnik.getCollisionProperty() & 0xFF,
                "loc_8CB9E writes collision_property=-1 so the shared Touch_Enemy boss-hit rebound runs.");

        robotnik.onPlayerAttack(null, new TouchResponseResult(0x0F, 0x20, 0x20, TouchCategory.ENEMY));

        assertEquals(List.of(Sonic3kSfx.BOSS_HIT.id), sfx,
                "sub_8D1FC plays sfx_BossHit when TouchResponse clears collision_flags after a player attack.");
        assertEquals(0, robotnik.getCollisionFlags(),
                "sub_8D1FC keeps collision_flags clear while $20(a0)'s hit timer is nonzero.");
        assertEquals(0x20, robotnik.getHitReactionTimerForTest());
        assertFalse(robotnik.isDestroyed(),
                "Obj_LBZ1Robotnik reacts to hits but has no defeat/deletion path.");
        robotnik.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawFrameIndex(2, 0x3EC0, 0x0184, false, false, 0);

        robotnik.onPlayerAttack(null, new TouchResponseResult(0x0F, 0x20, 0x20, TouchCategory.ENEMY));
        assertEquals(1, sfx.size(),
                "A second overlap during the hit timer must not restart the BossHit sound/window.");

        for (int frame = 0; frame < 31; frame++) {
            robotnik.update(frame, null);
        }
        assertEquals(1, robotnik.getHitReactionTimerForTest());
        assertEquals(0, robotnik.getCollisionFlags());

        robotnik.update(31, null);
        assertEquals(0, robotnik.getHitReactionTimerForTest());
        assertEquals(0x0F, robotnik.getCollisionFlags(),
                "When the hit timer expires, sub_8D1FC restores collision_flags from $25(a0).");
        assertFalse(robotnik.isDestroyed());
    }

    @Test
    void lbz1ThrownBombUsesRomBackedBombArt() {
        HeadlessTestFixture fixture = lbzFixture();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();

        var renderManager = GameServices.level().getObjectRenderManager();
        var sheet = renderManager.getSheet("lbz1_cutscene_knuckles_bomb");

        assertNotNull(sheet,
                "PLC_60 includes ArtNem_LBZKnuxBomb and ObjDat3_6640E uses Map_LBZKnuxBomb.");
        assertEquals(1, sheet.getFrameCount(),
                "Map_LBZKnuxBomb has one mapping frame.");
        assertEquals(1, sheet.getFrame(0).pieces().size(),
                "Map_LBZKnuxBomb frame 0 is one 2x2 bomb sprite piece.");
        assertNotNull(renderManager.getRenderer("lbz1_cutscene_knuckles_bomb"),
                "The thrown bomb child needs a renderer for its visible projectile.");
    }

    @Test
    void lbz1RobotnikUnlocksCameraAfterEndingCollapseCompletes() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.forceRoutineForTest(0x06);

        GameServices.camera().setX((short) 0x3B40);
        GameServices.camera().setMaxX((short) 0x3B60);
        GameServices.camera().setMaxXTarget((short) 0x3B60);
        player.setCentreY((short) 0x01C0);
        player.setAir(false);
        robotnik.update(0, player);
        assertEquals(0x08, robotnik.getRoutineForTest());

        for (int frame = 1; frame <= 609; frame++) {
            manager.getLbzEvents().update(0, frame);
        }
        robotnik.update(610, player);

        assertEquals(0x0A, robotnik.getRoutineForTest(),
                "loc_8CC8C advances Obj_LBZ1Robotnik to routine $0A after LBZ1_EventVScroll clears.");
        assertEquals(0x3B60, GameServices.camera().getMaxX() & 0xFFFF,
                "Child6_IncLevX runs after Robotnik on its creation frame, but its first $4000 "
                        + "accumulator step has no integer carry.");

        GameServices.camera().setX((short) 0x3C00);
        robotnik.update(611, player);
        assertEquals(0x3C00, GameServices.camera().getMinX() & 0xFFFF,
                "loc_8CCB4 keeps Camera_min_X_pos pinned to Camera_X_pos until the boss approach.");
        assertEquals(0x3B60, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_IncLevEndXGradual's first two post-creation updates still have no integer carry.");

        robotnik.update(612, player);
        robotnik.update(613, player);
        assertEquals(0x3B61, GameServices.camera().getMaxX() & 0xFFFF,
                "The creation-frame step plus three later $4000 updates increase Camera_max_X_pos.");

        for (int frame = 614; frame <= 618; frame++) {
            robotnik.update(frame, player);
        }
        assertEquals(0x3B68, GameServices.camera().getMaxX() & 0xFFFF,
                "Obj_IncLevEndXGradual applies the swapped high word each frame, "
                        + "not a simple fractional carry.");
    }

    @Test
    void lbz1RobotnikCollapseClearRequeuesMinibossBoxKosmOnce() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.forceRoutineForTest(0x06);

        GameServices.camera().setX((short) 0x3B40);
        player.setCentreY((short) 0x01C0);
        player.setAir(false);
        robotnik.update(0, player);
        List<HardwareWorkHandle> beforeCollapseClear = pendingKosModuleHandles();
        for (int frame = 1; frame <= 609; frame++) {
            manager.getLbzEvents().update(0, frame);
        }

        robotnik.update(610, player);

        List<HardwareWorkHandle> submitted = addedKosModuleHandles(beforeCollapseClear);
        assertEquals(1, submitted.size(),
                "loc_8CC8C must requeue ArtKosM_LBZMinibossBox once when collapse clears.");
        HardwareWorkHandle handle = submitted.getFirst();
        var queue = S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator()).moduleQueue();
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR,
                queue.descriptor(handle).sourceAddress());
        assertEquals(Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX * 32,
                queue.descriptor(handle).destinationAddress());

        robotnik.update(611, player);

        assertEquals(submitted, addedKosModuleHandles(beforeCollapseClear),
                "ROUTINE_AFTER_COLLAPSE must not resubmit the collapse-clear parent.");
    }

    @Test
    void lbz1RobotnikSecondRiseQueuesMinibossKosmOnce() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.forceInitializedForTest(0x3EC0, 0x012C, 0x0C, 0, -0x0400, false);
        List<HardwareWorkHandle> before = pendingKosModuleHandles();

        robotnik.update(0, player);

        List<HardwareWorkHandle> submitted = addedKosModuleHandles(before);
        assertEquals(1, submitted.size(),
                "loc_8CCF6 must submit ArtKosM_LBZMiniboss exactly once.");
        HardwareWorkHandle handle = submitted.getFirst();
        var queue = S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator()).moduleQueue();
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_ADDR,
                queue.descriptor(handle).sourceAddress());
        assertEquals(Sonic3kConstants.ART_TILE_LBZ_MINIBOSS * 32,
                queue.descriptor(handle).destinationAddress());

        robotnik.update(1, player);

        assertEquals(submitted, addedKosModuleHandles(before),
                "ROUTINE_DIAGONAL_ESCAPE must not resubmit the miniboss parent.");
    }

    @Test
    void playerAtOrPastSpawnDeletesLbz1KnucklesOnFirstUpdate() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) SPAWN_X);
        player.setCentreY((short) SPAWN_Y);
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();

        CutsceneKnucklesLbz1Instance knuckles = spawnKnuckles();
        fixture.stepFrame(false, false, false, false, false);

        assertTrue(knuckles.isDestroyed(),
                "loc_62678 deletes immediately when Player_1 x_pos is at or beyond the object x_pos.");
    }

    @Test
    void playerModeKnucklesSkipsLbz1RivalKnucklesSequence() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        placePlayerInsideHelperBox(player);

        CutsceneKnucklesLbz1Instance knuckles = spawnKnuckles();
        fixture.stepFrame(false, false, false, false, false);

        assertTrue(knuckles.isDestroyed(),
                "Engine Knuckles playthroughs use the rival-Knuckles skip route for this generic cutscene object.");
    }

    @Test
    void helperOverlapAppliesObjectControlAndParentWaitsSixtyFrames() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        placePlayerInsideHelperBox(player);

        CutsceneKnucklesLbz1Instance knuckles = spawnKnuckles();
        fixture.stepFrame(false, false, false, true, false);

        assertEquals(0x04, knuckles.getRoutineForTest(),
                "The ROM setup Process_Sprites pass initializes the parent/helper; "
                        + "the first gameplay frame observes the helper signal and starts Obj_Wait.");
        assertEquals(0x16, knuckles.getMappingFrameForTest(),
                "LBZ1 initializes Knuckles on mapping frame $16.");
        assertEquals(0x00A0, fixture.camera().getMinY() & 0xFFFF,
                "Init writes Camera_min_Y_pos=$00A0.");
        assertNotNull(findActive(CutsceneKnucklesLbz1RangeHelper.class),
                "Init should create the invisible range helper at x offset -$40.");

        assertTrue(knuckles.hasHelperSignalForTest(),
                "Helper overlap should set parent signal bit 3.");
        assertTrue(player.isObjectControlled(),
                "sub_62800 writes object_control=$81 for touched players.");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=$81 should suppress normal movement while the cutscene owns Sonic.");
        assertTrue(player.isRightPressed(),
                "Ctrl_1_logical must retain live input for Sonic_RecordPos while object control owns movement.");

        fixture.stepIdleFrames(59);
        assertEquals(0x04, knuckles.getRoutineForTest(),
                "After helper signal, the parent waits 60 frames in Obj_Wait.");
        fixture.stepFrame(false, false, false, false, false);
        assertEquals(0x06, knuckles.getRoutineForTest(),
                "The 60-frame wait callback starts the multi-delay throw animation.");
    }

    @Test
    void sidekickOverlapCapturesButDoesNotSignalParentWithoutPlayer1() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        removeLbz1GroundLaunchIntro();
        CutsceneKnucklesLbz1Instance knuckles = spawnKnuckles();
        fixture.stepFrame(false, false, false, false, false);
        CutsceneKnucklesLbz1RangeHelper helper = findActive(CutsceneKnucklesLbz1RangeHelper.class);
        assertNotNull(helper, "Init should create the invisible range helper.");

        player.setCentreX((short) (SPAWN_X - 0x100));
        player.setCentreY((short) SPAWN_Y);
        player.setObjectControlled(false);
        player.setControlLocked(false);
        AbstractPlayableSprite sidekick = GameServices.sprites().getRegisteredSidekicks().getFirst();
        placePlayerInsideHelperBox(sidekick);

        helper.update(0, player);

        assertFalse(knuckles.hasHelperSignalForTest(),
                "Check_PlayerInRange only lets Player_1 set parent bit 3.");
        assertFalse(player.isObjectControlled(), "Player_1 should remain free while outside the helper box.");
        assertTrue(sidekick.isObjectControlled(), "Player_2 is still captured by sub_62800 when inside the helper box.");
    }

    @Test
    void sequenceSpawnsBombCollapseHelpersThenExitsAndReleasesPlayers() {
        HeadlessTestFixture fixture = lbzFixture();
        AbstractPlayableSprite player = fixture.sprite();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        placePlayerInsideHelperBox(player);
        GameServices.camera().setX((short) (SPAWN_X - 0x00A0));
        GameServices.camera().setY((short) (SPAWN_Y - 0x0060));
        GameServices.camera().setMaxX((short) 0x3B60);
        int originalBuildingCell = GameServices.level().getCurrentLevel().getMap().getValue(0, 0x74, 0) & 0xFF;

        fixture.stepFrame(false, false, false, false, false);
        CutsceneKnucklesLbz1Instance knuckles = findActive(CutsceneKnucklesLbz1Instance.class);
        assertNotNull(knuckles, "The ROM-placed subtype $14 object should spawn when the camera reaches LBZ1 Knuckles.");
        CutsceneKnucklesLbz1RangeHelper helper = findActive(CutsceneKnucklesLbz1RangeHelper.class);
        assertNotNull(helper, "The Knuckles setup pass should create the Player_1/Player_2 capture helper.");
        for (AbstractPlayableSprite sidekick : GameServices.sprites().getRegisteredSidekicks()) {
            // The setup Process_Sprites pass owns initial P2 CPU state. Once
            // that pass has completed, place the live sidekick inside the
            // ROM helper range, hold the fixture in
            // the ordinary routine-6 CPU state so this long integration run
            // verifies loc_6278A's object_control clear rather than a later,
            // unrelated off-screen catch-up marker reclaiming bit 7.
            placePlayerInsideHelperBox(sidekick);
            sidekick.getCpuController().setInitialState(
                    com.openggf.sprites.playable.SidekickCpuController.State.NORMAL);
        }
        helper.update(0, player);
        for (AbstractPlayableSprite sidekick : GameServices.sprites().getRegisteredSidekicks()) {
            assertTrue(sidekick.isObjectControlled(),
                    "The live Player_2 slot must be captured before exercising the exit release.");
        }
        PatternSpriteRenderer bossExplosionRenderer = GameServices.level().getObjectRenderManager()
                .getBossExplosionRenderer();
        assertNotNull(bossExplosionRenderer,
                "CutsceneKnux_LBZ1 loads PLC_BossExplosion for the later rising building stream.");
        assertTrue(bossExplosionRenderer.isReady(),
                "Late-loaded PLC_BossExplosion art must be cached before S3kBossExplosionChild tries to draw.");

        int frame = 1;
        while (frame++ < 240 && findActive(CutsceneKnucklesLbz1ThrownBomb.class) == null) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertNotNull(findActive(CutsceneKnucklesLbz1ThrownBomb.class),
                "Throw callback should spawn the thrown bomb child; frame=" + frame
                        + ", routine=$" + Integer.toHexString(knuckles.getRoutineForTest())
                        + ", mapping=$" + Integer.toHexString(knuckles.getMappingFrameForTest())
                        + ", objects=" + activeObjectNames());

        while (frame++ < 360 && activeCollapseChildren() < 4) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(4, activeCollapseChildren(),
                "Collapse phase should create the four building/pillar helper children.");
        assertVisibleShakeWithinFrames(fixture, 8,
                "loc_6277A writes Screen_shake_flag=-1; LBZ parallax must publish a visible shake offset "
                        + "instead of relying on a direct Camera shake write that render propagation overwrites.");
        fixture.stepIdleFrames(12);
        assertTrue(activeBossExplosions() >= 4,
                "sonic3k.asm loc_6285A routes the LBZ1 rising building controllers through Obj_BossExpControl1, "
                        + "which emits Child6_MakeBossExplosion1 orange boss-explosion sprites.");
        assertEquals(0, activeNormalExplosions(),
                "The locked-on S3K LBZ1 path must not use the Sonic 3 standalone Child*_MakeNormalExplosion stream.");
        assertEquals(originalBuildingCell, GameServices.level().getCurrentLevel().getMap().getValue(0, 0x74, 0) & 0xFF,
                "Knuckles starting the collapse should not instantly apply LBZ1_ModEndingLayout.");
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        assertFalse(manager.getLbzEvents().isEndingCollapseActive(),
                "CutsceneKnux_LBZ1 only spawns explosion helpers; Obj_LBZ1Robotnik arms LBZ1_EventVScroll.");

        Lbz1RobotnikEventController robotnik = spawnRobotnikCollapseController();
        robotnik.forceRoutineForTest(0x06);
        GameServices.camera().setX((short) 0x3B40);
        player.setCentreY((short) 0x01C0);
        player.setAir(false);
        robotnik.update(frame, player);
        assertTrue(manager.getLbzEvents().isEndingCollapseActive(),
                "Obj_LBZ1Robotnik should arm LBZ1_EventVScroll after the ROM camera/player/ground checks pass.");
        fixture.stepIdleFrames(24);
        short[] collapseVScroll = manager.getLbzEvents()
                .buildEndingCollapseForegroundVScrollOverride(GameServices.camera().getX());
        assertNotNull(collapseVScroll,
                "The active collapse should feed foreground per-column VScroll before the layout is changed.");
        assertTrue(hasNonZero(collapseVScroll),
                "The visual collapse should move at least one foreground VScroll column.");

        while (frame++ < 1000 && (GameServices.level().getCurrentLevel().getMap().getValue(0, 0x74, 0) & 0xFF) != 0) {
            keepRegisteredSidekicksOnScreen();
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEndingLayoutApplied();

        while (frame++ < 1100 && !knuckles.isDestroyed()) {
            keepRegisteredSidekicksOnScreen();
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(knuckles.isDestroyed(), "Knuckles should delete after running offscreen to the right.");
        assertEquals(0, activeSongFadeTransitions(),
                "CutsceneKnux_LBZ1 exit does not spawn Obj_Song_Fade_ToLevelMusic; Knuckles music should remain "
                        + "active until the later miniboss handoff.");
        assertFalse(player.isObjectControlled(), "Exit should clear Player_1 object_control.");
        for (AbstractPlayableSprite sidekick : GameServices.sprites().getRegisteredSidekicks()) {
            assertFalse(sidekick.isObjectControlled(), "Exit should clear native Player_2 object_control.");
        }
        assertEquals(0x3B60, fixture.camera().getMaxX() & 0xFFFF,
                "Exit should restore Camera_stored_max_X_pos=$3B60.");
        assertEquals(0x0148, fixture.camera().getMaxYTarget() & 0xFFFF,
                "Exit should target Camera_Max_Y_pos=$0148.");
    }

    @Test
    void endingCollapseVScrollUsesRomWorldColumnsAndDeltas() {
        HeadlessTestFixture fixture = lbzFixture();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();
        GameServices.camera().setX((short) (0x3B60 - 12));

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.getLbzEvents().startEndingCollapse();
        assertNull(manager.getLbzEvents().buildEndingCollapseForegroundVScrollOverride(GameServices.camera().getX()),
                "The ROM arms cell VScroll before the first falling-strip delta is generated.");

        manager.getLbzEvents().update(0, 1);
        GameServices.parallax().update(Sonic3kZoneIds.ZONE_LBZ, 0, GameServices.camera(), 1, 0);
        assertNotEquals(0, GameServices.parallax().getShakeOffsetY(),
                "LBZ1_EventVScroll keeps Screen_shake_flag=-1 active while the foreground strips fall.");

        short[] override = manager.getLbzEvents()
                .buildEndingCollapseForegroundVScrollOverride(GameServices.camera().getX());
        assertNotNull(override, "The first LBZ1_EventVScroll update should expose falling-strip deltas.");
        for (int column = 0; column < 10; column++) {
            assertEquals(-1, override[column],
                    "LBZ1_FGVScrollArray starts at world x=$3B60 and maps one 16px falling strip per column.");
        }
        for (int column = 10; column < override.length; column++) {
            assertEquals(0, override[column],
                    "Columns outside the ten falling strips should keep the normal foreground VScroll delta.");
        }
    }

    @Test
    void endingCollapseUsesRomAccumulatorTiming() {
        HeadlessTestFixture fixture = lbzFixture();
        removeLbz1GroundLaunchIntro();
        applyTitleCardHandoff();

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.getLbzEvents().startEndingCollapse();

        for (int frame = 1; frame <= 608; frame++) {
            manager.getLbzEvents().update(0, frame);
        }
        assertTrue(manager.getLbzEvents().isEndingCollapseActive(),
                "LBZ1_EventVScroll still has one unclamped column after 608 updates.");
        assertFalse(manager.getLbzEvents().isEndingCollapseFinished(),
                "LBZ1_ModEndingLayout should not run until the 609th event update.");

        manager.getLbzEvents().update(0, 609);

        assertFalse(manager.getLbzEvents().isEndingCollapseActive());
        assertTrue(manager.getLbzEvents().isEndingCollapseFinished(),
                "The ROM reaches all ten -$300 columns on the 609th update.");
    }

    private HeadlessTestFixture lbzFixture() {
        return HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .build();
    }

    private CutsceneKnucklesLbz1Instance spawnKnuckles() {
        return GameServices.level().getObjectManager().createDynamicObject(
                () -> new CutsceneKnucklesLbz1Instance(new ObjectSpawn(
                        SPAWN_X, SPAWN_Y, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x14, 0, false, 0)));
    }

    private Lbz1RobotnikEventController spawnRobotnikCollapseController() {
        return GameServices.level().getObjectManager().createDynamicObject(
                () -> new Lbz1RobotnikEventController(new ObjectSpawn(
                        0x3EC0, 0x01A0, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0)));
    }

    private List<HardwareWorkHandle> pendingKosModuleHandles() {
        return GameServices.hardwareTiming().pendingHandles().stream()
                .filter(handle -> handle.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
    }

    private List<HardwareWorkHandle> addedKosModuleHandles(List<HardwareWorkHandle> before) {
        return pendingKosModuleHandles().stream()
                .filter(handle -> !before.contains(handle))
                .toList();
    }

    private void placePlayerInsideHelperBox(AbstractPlayableSprite player) {
        player.setCentreX((short) (SPAWN_X - 0x40));
        player.setCentreY((short) SPAWN_Y);
        player.setObjectControlled(false);
        player.setControlLocked(false);
        player.clearForcedInputMask();
        player.clearLogicalInputState();
    }

    private void keepRegisteredSidekicksOnScreen() {
        for (AbstractPlayableSprite sidekick : GameServices.sprites().getRegisteredSidekicks()) {
            sidekick.setCentreX((short) ((GameServices.camera().getX() & 0xFFFF) + 0x60));
            sidekick.setCentreY((short) ((GameServices.camera().getY() & 0xFFFF) + 0x60));
        }
    }

    private void removeLbz1GroundLaunchIntro() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        List<ObjectInstance> intros = objectManager.getActiveObjects().stream()
                .filter(object -> "LBZ1GroundLaunchIntro".equals(object.getName()))
                .toList();
        for (ObjectInstance intro : intros) {
            objectManager.removeDynamicObject(intro);
        }
    }

    private void applyTitleCardHandoff() {
        ((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider())
                .applyZonePlayerStateAfterTitleCard();
        // This class exercises the later rival-Knuckles/miniboss route. The
        // title-card handoff legitimately creates the independent buried
        // ground-launch controller, so remove that controller after publishing
        // the handoff instead of letting it reclaim P1/P2 object_control during
        // the sequence under test.
        removeLbz1GroundLaunchIntro();
    }

    private <T> T findActive(Class<T> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    private int activeCollapseChildren() {
        return (int) GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(CutsceneKnucklesLbz1CollapseChild.class::isInstance)
                .count();
    }

    private int activeNormalExplosions() {
        return (int) GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(ExplosionObjectInstance.class::isInstance)
                .count();
    }

    private int activeBossExplosions() {
        return (int) GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(S3kBossExplosionChild.class::isInstance)
                .count();
    }

    private void assertVisibleShakeWithinFrames(HeadlessTestFixture fixture, int maxFrames, String message) {
        for (int i = 0; i < maxFrames; i++) {
            if (GameServices.parallax().getShakeOffsetY() != 0) {
                return;
            }
            fixture.stepFrame(false, false, false, false, false);
        }
        assertNotEquals(0, GameServices.parallax().getShakeOffsetY(), message);
    }

    private int activeSongFadeTransitions() {
        return (int) GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(SongFadeTransitionInstance.class::isInstance)
                .count();
    }

    private void assertEndingLayoutApplied() {
        var map = GameServices.level().getCurrentLevel().getMap();
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 4; col++) {
                assertEquals(0, map.getValue(0, 0x74 + col, row) & 0xFF,
                        "LBZ1_ModEndingLayout clears the upper building strip at row=" + row + ", col=" + col);
            }
        }
        for (int row = 9; row < 12; row++) {
            for (int col = 0; col < 4; col++) {
                int expected = map.getValue(0, 0x98 + col, row - 9) & 0xFF;
                assertEquals(expected, map.getValue(0, 0x74 + col, row) & 0xFF,
                        "LBZ1_ModEndingLayout copies the lower building strip from hidden staging rows.");
            }
        }
    }

    private boolean hasNonZero(short[] values) {
        for (short value : values) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    private String activeObjectNames() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .map(ObjectInstance::getName)
                .filter(name -> name.contains("CutsceneKnuxLBZ1")
                        || name.contains("LBZ")
                        || name.contains("Robotnik"))
                .toList()
                .toString();
    }

    private ObjectInstance activeObjectByName(String name) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> name.equals(object.getName()))
                .findFirst()
                .orElse(null);
    }
}
