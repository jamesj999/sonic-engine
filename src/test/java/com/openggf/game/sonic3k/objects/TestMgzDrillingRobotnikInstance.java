package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.palette.PaletteWriteSupport;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.events.MgzObjectEventBridge;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ResultsHardwareTimingFixture;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.sprites.playable.TailsCarryController;
import org.mockito.MockedStatic;
import org.mockito.InOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@RequiresRom(SonicGame.SONIC_3K)
class TestMgzDrillingRobotnikInstance {

    private Camera camera;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.resetState();
        camera.setX((short) 0);
        camera.setY((short) 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void hitFlashKeepsRobotnikVisibleAndUsesWhitePaletteFlash() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null); // load Pal_MGZEndBoss into line 1

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_HANG");

        boss.onPlayerAttack(null, null);
        boss.update(1, null);
        renderBossGraph(boss);

        verify(services.drillRenderer).drawFrameIndex(eq(0), anyInt(), anyInt(), eq(false), eq(false));
        assertColorWord(services.paletteLine1, 11, 0x0EEE);
        assertColorWord(services.paletteLine1, 13, 0x0888);
        assertColorWord(services.paletteLine1, 14, 0x0AAA);
    }

    @Test
    void hitDuringDrillDropDoesNotImmediatelyForceCeilingEscape() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        boss.onPlayerAttack(null, null);

        assertEquals(staticInt("ROUTINE_DRILL_DROP"), boss.getState().routine,
                "Drop phase should keep drilling until the hang state checks the hit flag");
    }

    @Test
    void hangRoutineObservesHitOnFollowingObjectPass() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_HANG");

        boss.onPlayerAttack(null, null);
        boss.update(1, null);
        assertEquals(staticInt("ROUTINE_HANG"), boss.getState().routine,
                "MGZ2_SpecialCheckHit runs after the current movement routine");

        boss.update(2, null);
        assertEquals(staticInt("ROUTINE_CEILING_ESCAPE"), boss.getState().routine,
                "The published hurt bit should drive the next object pass");
    }

    @Test
    void ceilingEscapeUsesEscapePodFrameAndThrusterFlame() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_HANG");

        boss.onPlayerAttack(null, null);
        boss.update(1, null);
        boss.update(2, null);
        renderBossGraph(boss);

        verify(services.shipRenderer).drawFrameIndex(eq(10), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.shipRenderer).drawFrameIndex(eq(6), anyInt(), anyInt(), eq(false), eq(false), eq(0));
    }

    @Test
    void cleanupRestoresMgzPaletteLine1() throws Exception {
        RecordingServices services = new RecordingServices(camera)
                .withRuntimeArtRom();
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        S3kKosModuleQueue moduleQueue =
                S3kRuntimeArtCoordinator.from(services).moduleQueue();
        for (int frame = 0;
                frame < 256 && moduleQueue.hasPendingPhysicalModules();
                frame++) {
            moduleQueue.processModuleQueueAfterObjects();
            moduleQueue.prepareQueuedModuleBeforeVSync();
        }
        assertFalse(moduleQueue.hasPendingPhysicalModules(),
                "The initial boss-art batch must leave room for loc_6C200's four-entry batch");

        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "escapeTimer", 1);
        boss.getState().routine = staticInt("ROUTINE_ESCAPE_WAIT");

        boss.update(1, null);
        assertFalse(boss.isDestroyed(), "Obj_Wait does not fire its callback when $2E reaches zero");
        boss.update(2, null);
        assertTrue(boss.isDestroyed(), "Obj_Wait fires after $2E becomes negative");

        assertColorWord(services.paletteLine1, 0, 0x0000);
        assertColorWord(services.paletteLine1, 1, 0x0EEE);
    }

    @Test
    void drillingRobotnikCameraUnlockUsesNativeQuarterPixelWorker() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        camera.setMinX((short) 0x32C0);
        MgzDrillingRobotnikCameraUnlockController worker =
                new MgzDrillingRobotnikCameraUnlockController(true);
        worker.setServices(services);

        worker.update(1, null);
        worker.update(2, null);
        worker.update(3, null);
        assertEquals(0x32C0, camera.getMinX() & 0xFFFF,
                "The first three $4000 updates retain only fractional residue");

        worker.update(4, null);
        assertEquals(0x32BF, camera.getMinX() & 0xFFFF,
                "Obj_DecLevStartXGradual publishes one pixel on its fourth update");
    }

    @Test
    void rendersRomDrillChildPiecesAtBaseOffsets() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        renderBossGraph(boss);

        verify(services.drillRenderer).drawFrameIndex(eq(1), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.drillRenderer).drawFrameIndex(eq(4), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(6), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.drillRenderer).drawFrameIndex(eq(0x0F), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(0x19), anyInt(), anyInt(), eq(false), eq(false), eq(0));
    }

    @Test
    void rendersRomBackDrillPieceBeforeMainBodyAndShip() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        renderBossGraph(boss);

        verify(services.drillRenderer).drawFrameIndex(eq(1), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.drillRenderer).drawFrameIndex(eq(0), anyInt(), anyInt(), eq(false), eq(false));
        verify(services.shipRenderer).drawFrameIndex(eq(9), anyInt(), anyInt(), eq(false), eq(false));
    }

    @Test
    void splitsThrusterFlamesIntoRomPriorityBuckets() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        renderBossGraph(boss);

        InOrder order = inOrder(services.drillRenderer, services.shipRenderer);
        order.verify(services.drillRenderer)
                .drawFrameIndex(eq(0x19), eq(0x08D4), eq(0x06B8), eq(false), eq(false), eq(0));
        order.verify(services.drillRenderer).drawFrameIndex(eq(0), eq(0x08E0), eq(0x0690), eq(false), eq(false));
        order.verify(services.shipRenderer).drawFrameIndex(eq(9), anyInt(), anyInt(), eq(false), eq(false));
        order.verify(services.drillRenderer)
                .drawFrameIndex(eq(0x19), eq(0x08E8), eq(0x06B8), eq(false), eq(false), eq(0));
    }

    @Test
    void touchRegionsUseOnlyRomCollisionBearingBossParts() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");
        renderBossGraph(boss);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertEquals(4, regions.length,
                "ROM exposes the parent body, drill-tip child, and two lower hurt children; pod/head children have collision_flags=0");
        assertBodyRegion(regions[0], 0x08E0, 0x0690);
        assertEquals(0x08F4, regions[1].x());
        assertEquals(0x065C, regions[1].y());
        assertEquals(0x8B, regions[1].collisionFlags());
        assertEquals(0, regions[1].shieldReactionFlags());
        assertEquals(0x08E8, regions[2].x());
        assertEquals(0x06B8, regions[2].y());
        assertEquals(0x9A, regions[2].collisionFlags());
        assertEquals(0x10, regions[2].shieldReactionFlags());
        assertEquals(0x08D4, regions[3].x());
        assertEquals(0x06B8, regions[3].y());
        assertEquals(0x9A, regions[3].collisionFlags());
        assertEquals(0x10, regions[3].shieldReactionFlags());
    }

    @Test
    void hitFlashDisablesOnlyAttackableBodyCollisionNotChildHazards() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_HANG");
        renderBossGraph(boss);

        boss.onPlayerAttack(null, null);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();
        assertEquals(0, regions[0].collisionFlags(),
                "MGZ2_SpecialCheckHit leaves the parent body unhittable while its hit flash is active");
        assertEquals(0x8B, regions[1].collisionFlags(),
                "The drill-tip child is an independent hurt object, not Robotnik's attackable body");
        assertEquals(0x9A, regions[2].collisionFlags());
        assertEquals(0x9A, regions[3].collisionFlags());
    }

    @Test
    void ceilingEscapeRetainsIndependentChildHazards() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_CEILING_ESCAPE");

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertEquals(4, regions.length,
                "routine $16 still ends in Draw_And_Touch_Sprite and its child SSTs remain live");
        assertEquals(0x8B, regions[1].collisionFlags());
        assertEquals(0x9A, regions[2].collisionFlags());
        assertEquals(0x9A, regions[3].collisionFlags());
    }

    @Test
    void thrusterFlamesFlickerFromGameplayFrameNotRenderPass() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        renderBossGraph(boss);
        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(0x19), anyInt(), anyInt(), eq(false), eq(false), eq(0));

        boss.update(1, null);
        renderBossGraph(boss);

        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(0x19), anyInt(), anyInt(), eq(false), eq(false), eq(0));
    }

    @Test
    void thrusterFlamesApplyVIntRunCounterPhaseIndependentlyOfObjectClock() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        services.objectManager.initVIntRunCounterPhaseOffset(1);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");
        renderBossGraph(boss);

        verify(services.drillRenderer, never())
                .drawFrameIndex(eq(0x19), anyInt(), anyInt(), eq(false), eq(false), eq(0));
    }

    @Test
    void spawnFallingDebrisImmediatelyEnablesDebrisRendering() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);
        setPrivateInt(boss, "waitTimer", 0);

        Method spawnDebris = MgzDrillingRobotnikInstance.class.getDeclaredMethod("spawnFallingDebris");
        spawnDebris.setAccessible(true);
        spawnDebris.invoke(boss);

        List<MgzEndBossFallingDebrisChild> debris = services.objectManager.getActiveObjects().stream()
                .filter(MgzEndBossFallingDebrisChild.class::isInstance)
                .map(MgzEndBossFallingDebrisChild.class::cast).toList();
        assertEquals(10, debris.size());
        debris.forEach(child -> child.appendRenderCommands(new ArrayList<>()));
        verify(services.debrisRenderer, times(10)).drawFrameIndex(
                anyInt(), anyInt(), anyInt(), anyBoolean(), eq(false));
    }

    @Test
    void drillDropSpawnsArrivalDebrisAtCameraThreshold() throws Exception {
        camera.setY((short) 0x0590);
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);
        boss.update(0, null);

        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_DRILL_DROP");

        boss.update(1, null);
        assertEquals(10, services.objectManager.getActiveObjects().stream()
                .filter(MgzEndBossFallingDebrisChild.class::isInstance).count());
    }

    @Test
    void usesHighTilePriorityFromExplicitEndBossSetupOverride() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createEndBoss(services);

        assertTrue(boss.isHighPriority(),
                "loc_6C354 sets art_tile bit 7 after loading ObjDat_MGZDrillBoss");
    }

    @Test
    void drillingMiniEventRetainsObjDatLowTilePriority() throws Exception {
        assertFalse(createBoss(new RecordingServices(camera)).isHighPriority(),
                "loc_6BFCA does not apply the end boss's loc_6C354 art_tile override");
    }

    @Test
    void usesBucketSixFromObjDatPriorityWord() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzDrillingRobotnikInstance boss = createBoss(services);

        assertEquals(6, boss.getPriorityBucket(),
                "ObjDat_MGZDrillBoss priority word is $300, which maps to render bucket 6");
    }

    @Test
    void defeatCallbackChangesBodyToRomBucketFour() throws Exception {
        MgzEndBossInstance boss = createEndBoss(new RecordingServices(camera));
        setPrivateInt(boss, "endBossDefeatPhase", 1);

        assertEquals(4, boss.getPriorityBucket(),
                "loc_6C2BE writes priority $200 after Wait_FadeToLevelMusic");
    }

    @Test
    void compositePartsAreRealPriorityBearingBossChildren() throws Exception {
        MgzEndBossInstance boss = createEndBoss(new RecordingServices(camera));
        boss.update(0, null);
        List<MgzEndBossRenderChild> parts = boss.getChildComponents().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();

        assertEquals(8, parts.size());
        assertEquals(List.of(7, 6, 5, 5, 3, 7, 3, 6), parts.stream()
                .map(MgzEndBossRenderChild::getPriorityBucket).toList());
        assertEquals(List.of(false, false, true, false, false, false, false, false), parts.stream()
                .map(MgzEndBossRenderChild::isHighPriority).toList());
    }

    @Test
    void surpriseRobotnikPodInheritsTheMiniEventLowTilePriority() throws Exception {
        MgzDrillingRobotnikInstance boss = createBoss(new RecordingServices(camera));
        boss.update(0, null);

        List<MgzEndBossRenderChild> parts = boss.getChildComponents().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();

        assertFalse(parts.get(MgzEndBossRenderChild.ROLE_POD).isHighPriority(),
                "Child_SyncDraw must keep the surprise Robotnik ship behind MGZ terrain "
                        + "when Obj_MGZ2DrillingRobotnik has not set art_tile bit 7");
    }

    @Test
    void defeatDebrisUsesObjDat3BucketTwoAndHighTilePriority() {
        MgzEndBossDefeatDebrisChild debris = new MgzEndBossDefeatDebrisChild(0, 0, 0, false);
        assertEquals(2, debris.getPriorityBucket());
        assertTrue(debris.isHighPriority());
    }

    @Test
    void endBossConsumesEightHitsBeforeDefeat() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_APPROACH");

        for (int i = 7; i >= 0; i--) {
            boss.getState().invulnerable = false;
            boss.onPlayerAttack(null, null);
            assertEquals(i, boss.getCollisionProperty());
        }

        assertEquals(staticInt("ROUTINE_END_DEFEATED"), boss.getState().routine);
    }

    @Test
    void endBossEventPhaseHitsCannotKillUntilRomClearsEventFlag() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");

        for (int i = 0; i < 8; i++) {
            boss.getState().invulnerable = false;
            boss.onPlayerAttack(null, null);
        }

        assertFalse(boss.isDefeated(),
                "MGZ2_SpecialCheckHit keeps Obj_MGZEndBoss alive while $46 marks the terrain-destruction event phase");
        assertEquals(1, boss.getCollisionProperty(),
                "The ROM resets collision_property to 1 instead of allowing a kill while $46 is set");
        assertNotEquals(staticInt("ROUTINE_END_DEFEATED"), boss.getState().routine);
    }

    @Test
    void endBossDrillingPhaseRemainsHittableWhileScriptTimerRuns() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0x3F);
        boss.getState().routine = staticInt("ROUTINE_END_IMPACT_WAIT");

        assertEquals(0x0F, boss.getCollisionFlags(),
                "MGZ2 end-boss script timers should not disable the visible parent body touch box");

        boss.onPlayerAttack(null, null);

        assertEquals(7, boss.getCollisionProperty(),
                "MGZ2_SpecialCheckHit still registers non-lethal hits during the terrain-destruction phase");
        assertFalse(boss.isDefeated());
    }

    @Test
    void endBossFinalHitEntersRomDefeatWaitBeforeCapsuleHandoff() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = mock(GameStateManager.class);
        services.withGameState(gameState);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0x2A);
        boss.getState().routine = staticInt("ROUTINE_END_ACTIVE");

        for (int i = 0; i < 8; i++) {
            boss.getState().invulnerable = false;
            boss.onPlayerAttack(null, null);
        }

        assertEquals(staticInt("ROUTINE_END_DEFEATED"), boss.getState().routine);
        assertEquals(0x3F, getPrivateInt(boss, "waitTimer"),
                "BossDefeated_StopTimer falls through to BossDefeated and writes $2E=$3F");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(S3kBossExplosionChild.class::isInstance),
                "The final hit should immediately create a visible explosion before the boss debris handoff");
        assertFalse(boss.isDestroyed(),
                "Obj_MGZEndBoss must remain alive during the Wait_FadeToLevelMusic delay");
        assertEquals(0, services.fadeOutMusicCount,
                "Obj_Song_Fade_ToLevelMusic is allocated by Wait_FadeToLevelMusic, not started on the hit frame");
        verify(gameState).addScore(1000);
        verify(gameState, never()).setCurrentBossId(0);
    }

    @Test
    void endBossWaitFadeStageSpawnsSongFadeAndDebrisBeforeCapsuleHandoff() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = mock(GameStateManager.class);
        AbstractLevelEventManager levelEvents = mock(AbstractLevelEventManager.class);
        GameModule module = mock(GameModule.class);
        when(module.getLevelEventProvider()).thenReturn(levelEvents);
        services.withGameState(gameState);
        services.withGameModule(module);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_DEFEATED");
        boss.getState().defeated = true;

        boss.update(1, null);

        long debrisCount = services.objectManager().getActiveObjects().stream()
                .filter(MgzEndBossDefeatDebrisChild.class::isInstance)
                .count();
        assertEquals(3, debrisCount,
                "loc_6C2BE creates ChildObjDat_6D822's three MGZ boss fragments before the capsule handoff");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(SongFadeTransitionInstance.class::isInstance),
                "Wait_FadeToLevelMusic allocates Obj_Song_Fade_ToLevelMusic before the capsule wait");
        assertFalse(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2EndEggCapsuleInstance.class::isInstance),
                "loc_694AA must not run until loc_6C2BE's Obj_Wait delay expires");
        assertFalse(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2PostBossSequenceController.class::isInstance),
                "Obj_MGZEndBoss itself remains as loc_6C2EE after creating the capsule");
        verify(gameState, never()).setCurrentBossId(0);
        verify(levelEvents, never()).setBossActive(false);
        assertFalse(boss.isDestroyed(),
                "Obj_MGZEndBoss remains alive through loc_6C2BE's callback wait");
    }

    @Test
    void endBossWaitFadeHandoffHidesDrillBodyWhileKeepingControllerAlive() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        services.withGameState(mock(GameStateManager.class));
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_DEFEATED");
        boss.getState().defeated = true;

        boss.update(1, null);
        renderBossGraph(boss);

        assertFalse(boss.isDestroyed(),
                "loc_6C2BE/loc_6C2EE keep Obj_MGZEndBoss alive as a handoff controller");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(SongFadeTransitionInstance.class::isInstance),
                "Wait_FadeToLevelMusic should have advanced into the capsule-delay controller path");
        verify(services.drillRenderer, never()).drawFrameIndex(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
        verify(services.shipRenderer).drawFrameIndex(eq(10), anyInt(), anyInt(), eq(false), eq(false));
    }

    @Test
    void endBossWaitFadeHandoffBreaksDrillContraptionWhileRobotnikShipEscapes() throws Exception {
        camera.setY((short) 0x0600);
        RecordingServices services = new RecordingServices(camera);
        services.withGameState(mock(GameStateManager.class));
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_DEFEATED");
        boss.getState().defeated = true;

        boss.update(1, null);
        renderBossGraph(boss);

        verify(services.drillRenderer, never()).drawFrameIndex(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
        verify(services.shipRenderer).drawFrameIndex(eq(10), eq(0x3D1A), eq(0x066C), eq(false), eq(false));
        verify(services.shipRenderer).drawFrameIndex(eq(3), eq(0x3D1A), eq(0x0650), eq(false), eq(false));
        assertEquals(0, boss.getCollisionFlags(),
                "After loc_6D60A the boss no longer uses Draw_And_Touch_Sprite, so the controller must be non-colliding");

        for (int frame = 2; frame <= 50; frame++) {
            boss.update(frame, null);
        }
        renderBossGraph(boss);

        verify(services.shipRenderer, atLeastOnce())
                .drawFrameIndex(eq(10), intThat(x -> x > 0x3D1A), anyInt(), eq(true), eq(false));
        verify(services.shipRenderer, atLeastOnce())
                .drawFrameIndex(eq(6), anyInt(), anyInt(), eq(true), eq(false), eq(0));
    }

    @Test
    void endBossCapsuleHandoffWaitsForResultsFlagBeforeMgzToCnzFade() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = mock(GameStateManager.class);
        AbstractLevelEventManager levelEvents = mock(AbstractLevelEventManager.class);
        GameModule module = mock(GameModule.class);
        when(module.getLevelEventProvider()).thenReturn(levelEvents);
        when(gameState.isEndOfLevelFlag()).thenReturn(false);
        services.withGameState(gameState);
        services.withGameModule(module);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_DEFEATED");
        boss.getState().defeated = true;

        boss.update(1, null);
        for (int frame = 2; frame <= 121; frame++) {
            boss.update(frame, null);
        }

        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2EndEggCapsuleInstance.class::isInstance),
                "loc_694AA creates the floating capsule only after loc_6C2BE's Obj_Wait delay");
        assertFalse(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2PostBossPaletteFadeController.class::isInstance),
                "loc_6C2EE waits while the capsule/results flag is still active");
        assertFalse(boss.isDestroyed(),
                "Obj_MGZEndBoss must remain alive as the loc_6C2EE results waiter");
        verify(gameState).setCurrentBossId(0);
        verify(levelEvents).setBossActive(false);

        camera.setX((short) 0x3C80);
        camera.setMinX((short) 0x3C60);
        boss.update(122, null);

        assertEquals(0x3C80, camera.getMinX() & 0xFFFF,
                "loc_6C8F4 must repin the left boundary while the MGZ results flag remains active");

        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        boss.update(123, null);

        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2PostBossPaletteFadeController.class::isInstance),
                "When the results screen sets End_of_level_flag, loc_6C2EE creates loc_6D104");
        assertTrue(boss.isDestroyed(),
                "After creating loc_6D104, the Java boss object can retire");
    }

    @Test
    void mgzPostBossWaiterStartsPaletteFadeAfterResultsComplete() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        Mgz2PostBossSequenceController waiter = new Mgz2PostBossSequenceController();
        waiter.setServices(services);

        waiter.update(1, null);

        assertFalse(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2PostBossPaletteFadeController.class::isInstance),
                "loc_6C2EE waits while the results flag is still active");

        gameState.setEndOfLevelFlag(true);
        waiter.update(2, null);

        assertTrue(waiter.isDestroyed(),
                "The waiter should retire after spawning loc_6D104's palette controller");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2PostBossPaletteFadeController.class::isInstance),
                "When the results screen sets End_of_level_flag, MGZ runs loc_6D104's fade-to-CNZ controller");
    }

    @Test
    void mgzFloatingCapsuleSpawnsNineCarryAnimalsOnOpen() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        services.withGameState(new GameStateManager());
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);

        Method openCapsule = AbstractS3kFloatingEndEggCapsuleInstance.class.getDeclaredMethod("openCapsule");
        openCapsule.setAccessible(true);
        openCapsule.invoke(capsule);

        long animalCount = services.objectManager().getActiveObjects().stream()
                .filter(Mgz2CapsuleAnimalInstance.class::isInstance)
                .count();

        assertEquals(9, animalCount,
                "Obj_EggCapsule ChildObjDat_86B9A creates nine animals around Sonic/Tails during the score count");
    }

    @Test
    void mgzCarryAnimalTracksPlayerUntilResultsCompleteThenFliesLeft() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        Mgz2CapsuleAnimalInstance animal = new Mgz2CapsuleAnimalInstance(
                new ObjectSpawn(0x3D00, 0x0620, 0x28, 0, 0, false, 0), 0, 0, 2);
        animal.setServices(services);
        Sonic player = new Sonic("sonic", (short) 0x3D60, (short) 0x0660);

        for (int frame = 0; frame < 80; frame++) {
            animal.update(frame + 1, player);
        }

        assertTrue(Math.abs(animal.getX() - player.getCentreX()) < 96,
                "MGZ capsule animals should accelerate toward the carried player while results are counting");
        int trackedX = animal.getX();

        gameState.setEndOfLevelFlag(true);
        animal.update(2, player);
        animal.update(3, player);

        assertTrue(animal.getX() < trackedX,
                "After results clear the flag, the MGZ capsule animals leave left with the fly-off sequence");
    }

    @Test
    void mgzCarryAnimalAcceleratesTowardPlayerInsteadOfSnappingToOrbit() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        services.withGameState(new GameStateManager());
        Mgz2CapsuleAnimalInstance animal = new Mgz2CapsuleAnimalInstance(
                new ObjectSpawn(0x3C00, 0x0620, 0x28, 0, 0, false, 0), 0, 0, 3);
        animal.setServices(services);
        Sonic player = new Sonic("sonic", (short) 0x3D60, (short) 0x0660);

        animal.update(1, player);

        assertEquals(0x3C00, animal.getX(),
                "loc_868B6/sub_869F6 changes velocity first; animals should not teleport into an orbit");
        assertEquals(0x0620, animal.getY(),
                "MoveSprite2 applies 8.8 subpixel velocity, so the first step remains at the capsule position");

        for (int frame = 0; frame < 80; frame++) {
            animal.update(frame + 2, player);
        }

        assertTrue(animal.getX() > 0x3C00,
                "The animal should accelerate toward Player_1.x with a capped positive x velocity");
        assertTrue(animal.getY() > 0x0620 && animal.getY() < player.getCentreY(),
                "The animal should accelerate toward Player_1.y - $30 - subtype rather than a sine orbit");
    }

    @Test
    void mgzFloatingCapsuleWaitsForSonicToBeCarriedBeforeStartingResults() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("sonic");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("tails");
        services.withConfiguration(config);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        Sonic player = new Sonic("sonic", (short) 0x3D00, (short) 0x0660);
        player.setAir(true);
        player.setAnimationId(4);

        capsule.update(1, player);

        assertFalse(gameState.isEndOfLevelActive(),
                "ROM sub_86984 should wait for Flying_carrying_Sonic_flag before starting MGZ results for Sonic");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .noneMatch(Mgz2ResultsScreenObjectInstance.class::isInstance),
                "MGZ capsule should not start results unconditionally while Sonic is merely airborne");
    }

    @Test
    void mgzFloatingCapsuleStartsResultsDuringTailsCarryFlyOffWithoutFreezingPlayers() throws Exception {
        RecordingServices services = new RecordingServices(camera)
                .withRuntimeArtRom();
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("sonic");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("tails");
        services.withConfiguration(config);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        Sonic player = new Sonic("sonic", (short) 0x3D00, (short) 0x0660);
        player.setAir(true);
        player.setRenderFlagOnScreen(true);
        player.setAnimationId(4);
        Tails tails = new Tails("tails", (short) 0x3D00, (short) 0x0630);
        SidekickCpuController controller = new SidekickCpuController(tails, player);
        tails.setCpuController(controller);
        setMgzCarryActive(tails);
        services.withSidekicks(java.util.List.of(tails));

        capsule.update(1, player);

        assertTrue(gameState.isEndOfLevelActive());
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2ResultsScreenObjectInstance.class::isInstance),
                "MGZ capsule should start results while the Tails-carry/flying exit is still active");
        assertFalse(player.isObjectControlled(),
                "MGZ must not reuse AIZ2's victory-pose lock because Sonic/Tails keep flying during results");
        assertFalse(player.isControlLocked());
        assertEquals(4, player.getAnimationId());
    }

    @Test
    void mgzResultsOwnerRetainsCarryPublicationAcrossLowerSlotDelay() {
        Mgz2ResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new Mgz2ResultsScreenObjectInstance(
                        com.openggf.game.PlayerCharacter.SONIC_AND_TAILS, 1, true));

        assertTrue(results.shouldDeferInitialResultsArtLoadDispatch(),
                "sub_86984's Flying_carrying_Sonic_flag branch belongs to the allocation boundary, "
                        + "not the later result-owner pass");
    }

    @Test
    void mgzFloatingCapsuleDefersButtonPastLaterSupportOwner() throws Exception {
        Sonic main = new Sonic("sonic", (short) 0x4800, (short) 0x0100);
        Tails tails = new Tails("tails", (short) 0x49EA, (short) 0x0186);
        tails.setYSpeed((short) -1);
        tails.setInteractSlotIndex(8);
        RecordingServices services = new RecordingServices(camera)
                .withQueryOnlyPlayers(main, List.of(tails));
        services.withGameState(new GameStateManager());
        Mgz2EndEggCapsuleInstance capsule = new Mgz2EndEggCapsuleInstance(0x49EA, 0x0162);
        capsule.setSlotIndex(7);
        capsule.setServices(services);

        capsule.update(0, main);

        assertFalse(getPrivateBoolean(capsule, "buttonTriggered"),
                "the collapsed loc_86770 child cannot observe a later support owner's current dispatch");

        capsule.update(1, main);

        assertTrue(getPrivateBoolean(capsule, "buttonTriggered"),
                "the following capsule entry observes the support state published by the later SST");
    }

    @Test
    void mgzFloatingCapsuleUsesPlayerQueryForTailsCarryFlyOffTrigger() throws Exception {
        RecordingServices services = new RecordingServices(camera)
                .withRuntimeArtRom();
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("sonic");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("tails");
        services.withConfiguration(config);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        Sonic player = new Sonic("sonic", (short) 0x3D00, (short) 0x0660);
        player.setAir(true);
        player.setRenderFlagOnScreen(true);
        player.setAnimationId(4);
        Tails tails = new Tails("tails", (short) 0x3D00, (short) 0x0630);
        SidekickCpuController controller = new SidekickCpuController(tails, player);
        tails.setCpuController(controller);
        setMgzCarryActive(tails);
        services.withQueryOnlyPlayers(player, List.of(tails));

        capsule.update(1, player);

        assertTrue(gameState.isEndOfLevelActive(),
                "MGZ capsule Tails-carry trigger should resolve all engine sidekicks through ObjectPlayerQuery");
        assertTrue(services.objectManager().getActiveObjects().stream()
                .anyMatch(Mgz2ResultsScreenObjectInstance.class::isInstance));
    }

    @Test
    void mgzFloatingCapsuleStartsResultsForTailsEvenWhileAirborne() throws Exception {
        RecordingServices services = new RecordingServices(camera)
                .withRuntimeArtRom();
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("tails");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("");
        services.withConfiguration(config);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        Tails player = new Tails("tails", (short) 0x3D00, (short) 0x0660);
        player.setAir(true);
        player.setRenderFlagOnScreen(true);

        capsule.update(1, player);

        assertTrue(gameState.isEndOfLevelActive(),
                "ROM sub_86984 allows character_id=1 (Tails) to start MGZ results without a ground requirement");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .anyMatch(Mgz2ResultsScreenObjectInstance.class::isInstance));
    }

    @Test
    void mgzFloatingCapsuleWaitsWhenPlayerRenderFlagIsKnownOffScreen() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("tails");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("");
        services.withConfiguration(config);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        Tails player = new Tails("tails", (short) 0x3D00, (short) 0x0660);
        player.setRenderFlagOnScreen(false);

        capsule.update(1, player);

        assertFalse(gameState.isEndOfLevelActive(),
                "ROM sub_86984 waits while render_flags bit 7 reports Player 1 off-screen");
        assertTrue(services.objectManager().getActiveObjects().stream()
                        .noneMatch(Mgz2ResultsScreenObjectInstance.class::isInstance));
    }

    @Test
    void mgzResultsExitPreservesFlyOffCarryControlUntilFadeTransition() throws Exception {
        RecordingServices services = new RecordingServices(camera)
                .withRuntimeArtRom();
        GameStateManager gameState = new GameStateManager();
        services.withGameState(gameState);
        services.withZoneAct(2, 1);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("sonic");
        when(config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE)).thenReturn("tails");
        services.withConfiguration(config);
        Sonic player = new Sonic("sonic", (short) 0x3D00, (short) 0x0660);
        player.setObjectControlled(true);
        player.setControlLocked(false);
        Tails tails = new Tails("tails", (short) 0x3D00, (short) 0x0630);
        tails.setObjectControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, player);
        tails.setCpuController(controller);
        setMgzCarryActive(tails);
        services.withSidekicks(java.util.List.of(tails));
        camera.setX((short) 0x3C80);
        camera.setMinX((short) 0x3C80);
        camera.setMaxX((short) 0x3C80);
        Mgz2EndEggCapsuleInstance capsule = Mgz2EndEggCapsuleInstance.createForCamera(0x3C80, 0x0600);
        capsule.setServices(services);
        setPrivateBoolean(capsule, "opened", true);
        setPrivateInt(capsule, "postOpenTimer", 0);
        capsule.update(1, player);
        Object result = services.objectManager().getActiveObjects().stream()
                .filter(Mgz2ResultsScreenObjectInstance.class::isInstance)
                .findFirst()
                .orElseThrow();
        setPrivateObject(result, "playerRef", player);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(result);

        assertTrue(gameState.isEndOfLevelFlag());
        assertTrue(player.isObjectControlled(),
                "MGZ results exit must not clear the Tails-carry object_control state before loc_6D104");
        assertTrue(tails.isObjectControlled(),
                "Tails must keep owning the carry/fly-off state while the palette fade runs");
        assertTrue(camera.getFrozen(),
                "loc_6C8F4 sets Scroll_lock while the MGZ post-results fly-off remains active");
        assertEquals(0x3C80, camera.getMinX() & 0xFFFF,
                "MGZ results exit must retain the boss arena left boundary");
        assertEquals(0x3C80, camera.getMaxX() & 0xFFFF,
                "MGZ results exit must retain the boss arena right boundary");
    }

    @Test
    void mgzPostBossPaletteFadeUsesRomRowsThenRequestsCnzAct1() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        Mgz2PostBossPaletteFadeController controller = new Mgz2PostBossPaletteFadeController();
        controller.setServices(services);

        for (int frame = 0; frame < 260 && services.requestedZone < 0; frame++) {
            controller.update(frame, null);
        }

        for (int i = 0; i < Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROWS; i++) {
            verify(services.rom).readBytes(
                    Sonic3kConstants.PAL_MGZ_FADE_CNZ_ADDR
                            + i * Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE,
                    Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE);
        }
        assertEquals(S3kPaletteOwners.MGZ_POST_BOSS_FADE,
                services.registry.ownerAt(PaletteSurface.NORMAL, 3, 1),
                "MGZ2 Pal_MGZFadeCNZ rows should claim line 4 through palette ownership");
        assertEquals(0x0EAA, PaletteWriteSupport.segaWordFromColor(services.paletteLine3.getColor(1)),
                "The final Pal_MGZFadeCNZ ROM row should resolve into palette line 4");
        assertEquals(3, services.requestedZone,
                "StartNewLevel #$300 transitions from MGZ2 to CNZ1");
        assertEquals(0, services.requestedAct);
        assertTrue(services.deactivatedForTransition,
                "The level should freeze while GameLoop performs the final fade-to-black transition");
        assertTrue(controller.isDestroyed());
    }

    @Test
    void endBossDescendsVisiblyWhileTimedWaitRuns() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 1);

        boss.update(0, null);
        assertEquals(staticInt("ROUTINE_END_DESCEND"), boss.getState().routine);
        assertFalse(boss.isHidden(),
                "Obj_MGZEndBoss must draw during the timed descent after End Boss music starts");

        int startY = boss.getY();
        boss.update(1, null);
        boss.update(2, null);

        assertFalse(boss.isHidden());
        assertTrue(boss.getY() > startY,
                "Obj_MGZEndBoss MoveSprite2 runs before Obj_Wait during the descent timer");
    }

    @Test
    void endBossDoesNotDropImmediatelyAfterFirstSwingTimer() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_SWING");

        boss.update(1, null);

        assertNotEquals(staticInt("ROUTINE_END_FLOOR_DROP"), boss.getState().routine,
                "ROM loc_6C44C/loc_6C45A performs an angle-settle phase before the floor drop");
    }

    @Test
    void endBossFloorDropWaitsForTerrainFloorInsteadOfFixedY700() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0x400);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
        boss.getState().y = 0x0700;

        boss.update(1, null);

        assertEquals(staticInt("ROUTINE_END_FLOOR_DROP"), boss.getState().routine,
                "ObjHitFloor_DoRoutine should not fire from a fixed y=$700 threshold");
        assertEquals(0, services.playedSfxCount,
                "floor-impact SFX should only play after terrain collision reports a floor hit");
    }

    @Test
    void endBossFloorImpactWaitsBeforeStartingUpwardRecovery() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0x400);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
        boss.getState().x = 0x3D20;
        boss.getState().y = 0x0668;

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));
            boss.update(1, null);
        }

        assertEquals(0x3F, getPrivateInt(boss, "waitTimer"),
                "ROM loc_6C4BE waits $3F frames after floor impact before recovery velocity is set");
        assertEquals(0x400, getPrivateInt(boss, "yVel"),
                "The first impact frame should not immediately start the upward recovery");
        assertNotEquals(staticInt("ROUTINE_END_RECOVER"), boss.getState().routine);
    }

    @Test
    void repeatedEndBossFloorImpactsRequestCollapseOnlyOnce() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        RecordingMgzEventBridge mgzEvents = new RecordingMgzEventBridge();
        services.withLevelEventProvider(mgzEvents);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0x400);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
        boss.getState().x = 0x3D20;
        boss.getState().y = 0x0668;

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));
            boss.update(1, null);
            boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
            setPrivateInt(boss, "yVel", 0x400);
            boss.update(2, null);
        }

        assertEquals(1, mgzEvents.collapseHandoffCount,
                "Repeated floor collisions must not repeatedly request the MGZ2 collapse handoff");
        assertEquals(1, services.playedSfxCount,
                "Repeated floor collisions must not replay the floor-impact SFX");
    }

    @Test
    void endBossRecoveryAdvancesIntoAirPhaseSetupAfterCollapseHandoff() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0);
        boss.getState().routine = staticInt("ROUTINE_END_RECOVER");
        boss.getState().x = 0x3D20;
        boss.getState().y = 0x0600;

        for (int frame = 0; frame < 320 && boss.getState().x != 0x3E80; frame++) {
            boss.update(frame, null);
        }

        assertEquals(0x3E80, boss.getState().x,
                "ROM loc_6C598 repositions Robotnik to the air-boss entry point after the collapse/recovery waits");
        assertEquals(0x0700, boss.getState().y);
        assertEquals(staticInt("ROUTINE_END_AIR_APPROACH"), boss.getState().routine,
                "Robotnik should enter the post-collapse air sequence instead of swinging forever at center screen");
    }

    @Test
    void airPhaseKeepsDrillParentMappingFrameAndDoesNotUseChildCountAsBodyFrame() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_APPROACH");
        boss.getState().x = 0x3E80;
        boss.getState().y = 0x0700;

        renderBossGraph(boss);

        verify(services.drillRenderer).drawFrameIndex(eq(0), eq(0x3E80), eq(0x0700), anyBoolean(), eq(false));
        verify(services.drillRenderer, never()).drawFrameIndex(eq(6), eq(0x3E80), eq(0x0700), anyBoolean(), eq(false));
    }

    @Test
    void endBossDrillHeadRenderAndTouchFollowRomAngleTables() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "endBossAngle", 4);
        boss.getState().routine = staticInt("ROUTINE_END_ANGLE_SETTLE");

        renderBossGraph(boss);
        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        verify(services.drillRenderer).drawFrameIndex(eq(0x1E), eq(0x3D2C), eq(0x0684), eq(false), eq(false));
        verify(services.drillRenderer).drawFrameIndex(eq(0x20), eq(0x3D2C), eq(0x06A4), eq(false), eq(false));
        assertEquals(0x3D2C, regions[1].x(),
                "loc_6C948/loc_6C9E8 derive the drill-tip child from angle-indexed ROM tables");
        assertEquals(0x06A4, regions[1].y());
        assertEquals(0x8B, regions[1].collisionFlags());
    }

    @Test
    void movingAirAttackPublishesFoldedDrillTouchFromLaterChildSlotPosition() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        boss.getState().routine = staticInt("ROUTINE_END_ATTACK_MOVE");
        boss.getState().x = 0x3D4A;
        boss.getState().y = 0x0718;
        setPrivateInt(boss, "xSubpixel", 0);
        setPrivateInt(boss, "ySubpixel", 0);
        setPrivateInt(boss, "xVel", 0x0200);
        setPrivateInt(boss, "yVel", -0x0200);
        setPrivateInt(boss, "endBossAngle", 0x0C);
        setPrivateInt(boss, "airAttackPatternOffset", 4);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertEquals(0x06E2, regions[1].y(),
                "loc_6C9E8 refreshes after the parent MoveSprite2 slot, so the folded drill tip publishes from y-$34");
    }

    @Test
    void horizontalAirAttackRetainsItsAlreadyPublishedChildSlotPosition() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        boss.getState().routine = staticInt("ROUTINE_END_ATTACK_MOVE");
        boss.getState().x = 0x3CFA;
        boss.getState().y = 0x0710;
        setPrivateInt(boss, "xSubpixel", 0x80);
        setPrivateInt(boss, "ySubpixel", 0x30);
        setPrivateInt(boss, "xVel", 0x0200);
        setPrivateInt(boss, "yVel", 0);
        setPrivateInt(boss, "endBossAngle", 0);
        setPrivateInt(boss, "airAttackPatternOffset", 0);
        setPrivateBoolean(boss, "flipX", true);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertEquals(0x3D2D, regions[1].x(),
                "the horizontal sweep's retained child slot must not project a second parent MoveSprite2 step");
    }

    @Test
    void movingAirAttackPublishesFoldedBodyFromLiveCollisionListPosition() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        boss.getState().routine = staticInt("ROUTINE_END_ATTACK_MOVE");
        boss.getState().x = 0x3D40;
        boss.getState().y = 0x0710;
        setPrivateInt(boss, "xSubpixel", 0);
        setPrivateInt(boss, "ySubpixel", 0);
        setPrivateInt(boss, "xVel", 0x0200);
        setPrivateInt(boss, "yVel", 0);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertEquals(0x3D44, regions[0].x(),
                "the native collision-list pointer is live across the remaining parent/V-int phases");
        assertEquals(0x0710, regions[0].y());
    }

    @Test
    void enteringAirPhaseUsesRomChildPoseForLowerDrillPiecesAndHurtRegions() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_RISE");

        boss.update(2, null);
        renderBossGraph(boss);
        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(0x18), anyInt(), anyInt(), eq(true), eq(false));
        verify(services.drillRenderer, times(2))
                .drawFrameIndex(eq(0x1B), anyInt(), anyInt(), eq(true), eq(false), eq(0));
        assertEquals(0x3E58, regions[2].x(),
                "loc_6D710 sets $3A=6; loc_6CEB0/loc_6CF20 use that pose for flame hurtboxes");
        assertEquals(0x0708, regions[2].y());
        assertEquals(0x3E6C, regions[3].x());
        assertEquals(0x0708, regions[3].y());
    }

    @Test
    void enteringAirApproachPreservesNativeFractionalPositionWords() throws Exception {
        MgzEndBossInstance boss = createEndBoss(new RecordingServices(camera));
        boss.update(0, null);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_RISE");
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "xSubpixel", 0xA5);
        setPrivateInt(boss, "ySubpixel", 0x5A);
        setPrivateInt(boss, "yVel", 0);

        boss.update(1, null);

        assertEquals(0xA5, getPrivateInt(boss, "xSubpixel"));
        assertEquals(0x5A, getPrivateInt(boss, "ySubpixel"),
                "loc_6C598 writes x_pos/y_pos words without clearing their fractional words");
    }

    @Test
    void airApproachHitImmediatelyForcesRightwardSweep() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getCentreX()).thenReturn((short) 0x3D80);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_APPROACH");
        boss.getState().x = 0x3E80;
        boss.getState().y = 0x0700;
        setPrivateInt(boss, "xVel", -0x80);
        setPrivateInt(boss, "yVel", 0);

        boss.onPlayerAttack(player, null);
        boss.update(1, player);

        assertEquals(staticInt("ROUTINE_END_AIR_SWEEP"), boss.getState().routine,
                "loc_6C5C4 checks status bit 6 before Sonic's X-position and forces the flying attack sweep after a hit");
        assertEquals(0x0200, getPrivateInt(boss, "xVel"),
                "loc_6C5E8 sets x_vel=$200 when the hit flash is active");
    }

    @Test
    void endBossAirAttackWaitRepositionsFromCameraForNextSweep() throws Exception {
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_ATTACK_WAIT");
        boss.getState().x = 0x3E00;
        boss.getState().y = 0x0700;
        setPrivateInt(boss, "xSubpixel", 0x80);
        setPrivateInt(boss, "ySubpixel", 0x40);

        boss.update(1, null);

        assertEquals(staticInt("ROUTINE_END_ATTACK_WAIT"), boss.getState().routine,
                "ROM loc_6C646/loc_6D710 keeps Obj_Wait active for the short pre-sweep pause");
        assertEquals(0x3C40, boss.getState().x);
        assertEquals(0x0670, boss.getState().y);
        assertEquals(0x0200, getPrivateInt(boss, "xVel"));
        assertEquals(0, getPrivateInt(boss, "yVel"));
        assertEquals(0x80, getPrivateInt(boss, "xSubpixel"),
                "loc_6D710's x_pos word write preserves the native fractional position");
        assertEquals(0x40, getPrivateInt(boss, "ySubpixel"),
                "loc_6D710's y_pos word write preserves the native fractional position");
        assertEquals(0x20, getPrivateInt(boss, "waitTimer"),
                "The folded drill graph retains its later native child-publication phase after loc_6C646's $1F wait");
    }

    @Test
    void successiveAirAttackSetupsUseRomPatternSequence() throws Exception {
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_SWEEP");
        boss.getState().x = 0x3E00;

        boss.update(1, null); // loc_6C614 creates the first scaled warning child
        setPrivateInt(boss, "waitTimer", 0);
        boss.update(2, null); // loc_6D710 uses byte_6D708 entry 0
        assertEquals(0x3C40, boss.getState().x);
        assertEquals(0x0670, boss.getState().y);

        boss.getState().routine = staticInt("ROUTINE_END_AIR_SWEEP");
        boss.getState().x = 0x3E00;
        boss.update(3, null); // next byte_6D708 entry is 8
        setPrivateInt(boss, "waitTimer", 0);
        boss.update(4, null);

        assertEquals(0x3D20, boss.getState().x,
                "byte_6D708's second entry selects word_6D744 offset 8 for the next air attack");
        assertEquals(0x05B0, boss.getState().y);
        assertEquals(0x0080, getPrivateInt(boss, "xVel"));
        assertEquals(0x0200, getPrivateInt(boss, "yVel"));
        assertEquals(4, getPrivateInt(boss, "endBossAngle"));
        assertEquals(8, getPrivateInt(boss, "drillChildPose"));
    }

    @Test
    void enteringEachAirAttackCyclePlaysZoomSfxAndDrawsGeneratedMapScaledArtChild() throws Exception {
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        RecordingServices services = new RecordingServices(camera);
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_SWEEP");
        boss.getState().x = 0x3E00;
        boss.getState().y = 0x0700;

        boss.update(1, null);
        renderBossGraph(boss);

        assertTrue(services.playedSfxIds.contains(Sonic3kSfx.BOSS_ZOOM.id),
                "ROM loc_6C614 plays sfx_BossZoom before the $9F air-attack wait");
        assertFalse(services.graphics.renderCalls.isEmpty(),
                "ChildObjDat_6D836 creates a Map_ScaledArt sprite backed by generated tiles");
        assertTrue(services.graphics.scaledRenderCalls.isEmpty(),
                "ArtScaled_MGZEndBoss is raw source for Perform_Art_Scaling, not plain scaled sprite art");
        assertTrue(services.graphics.renderCalls.get(0).patternId() >= 0x4E000,
                "Initial $40=4 should draw from the cue instance's private transient atlas bank");
        assertEquals(0x3DC0, getPrivateInt(boss, "airZoomCueX"),
                "loc_6CFF4 anchors Map_ScaledArt directly at Camera_X+$140");
        assertEquals(0x0650, getPrivateInt(boss, "airZoomCueY"),
                "loc_6CFF4 anchors Map_ScaledArt directly at Camera_Y+$50");
    }

    @Test
    void airZoomCueScalingSamplesRawScanlineSourceInsteadOfTileMajorArt() throws Exception {
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        RecordingServices services = new RecordingServices(camera)
                .withScaledCueArt(horizontalNibbleRampSource());
        MgzEndBossInstance boss = createEndBoss(services);
        boss.update(0, null);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_AIR_SWEEP");
        boss.getState().x = 0x3E00;
        boss.getState().y = 0x0700;

        boss.update(1, null);
        renderBossGraph(boss);

        Field generatedField = MgzDrillingRobotnikInstance.class.getDeclaredField("airZoomCuePatterns");
        generatedField.setAccessible(true);
        Pattern firstGeneratedTile = ((Pattern[]) generatedField.get(boss))[0];
        assertEquals(0x0, firstGeneratedTile.getPixel(0, 0));
        assertEquals(0x2, firstGeneratedTile.getPixel(1, 0));
        assertEquals(0x4, firstGeneratedTile.getPixel(2, 0));
        assertEquals(0x6, firstGeneratedTile.getPixel(3, 0));
        assertEquals(0x8, firstGeneratedTile.getPixel(4, 0),
                "Perform_Art_Scaling reads ArtScaled_MGZEndBoss as 64-byte scanlines, not normal tile-major art");
        assertEquals(0xA, firstGeneratedTile.getPixel(5, 0));
        assertEquals(0xC, firstGeneratedTile.getPixel(6, 0));
        assertEquals(0xE, firstGeneratedTile.getPixel(7, 0));
        assertEquals(0, services.scaledSheet.getPatterns()[0].getPixel(1, 0),
                "runtime scaling must never mutate the provider's shared sheet");
    }

    @Test
    void twoLiveZoomCuesUseDistinctAtlasBanksSoQueuedDrawsCannotOverwrite() throws Exception {
        RecordingServices services = new RecordingServices(camera).withScaledCueArt(horizontalNibbleRampSource());
        MgzEndBossInstance first = createEndBoss(services);
        MgzEndBossInstance second = createEndBoss(services);
        for (MgzEndBossInstance boss : List.of(first, second)) {
            boss.update(0, null);
            setPrivateInt(boss, "waitTimer", 0);
            boss.getState().routine = staticInt("ROUTINE_END_AIR_SWEEP");
            boss.getState().x = 0x3E00;
            boss.getState().y = 0x0700;
            boss.update(1, null);
            renderBossGraph(boss);
        }
        Field rendererField = MgzDrillingRobotnikInstance.class.getDeclaredField("airZoomCueRenderer");
        rendererField.setAccessible(true);
        PatternSpriteRenderer firstRenderer = (PatternSpriteRenderer) rendererField.get(first);
        PatternSpriteRenderer secondRenderer = (PatternSpriteRenderer) rendererField.get(second);
        assertNotEquals(firstRenderer.getPatternBase(), secondRenderer.getPatternBase());
        assertTrue(firstRenderer.getPatternBase() + 0x80 <= secondRenderer.getPatternBase()
                        || secondRenderer.getPatternBase() + 0x80 <= firstRenderer.getPatternBase(),
                "queued cue draws must reference non-overlapping pattern IDs");
    }

    private static byte[] horizontalNibbleRampSource() {
        byte[] data = new byte[Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_SIZE];
        int pos = 0;
        for (int y = 0; y < 0x40; y++) {
            for (int xByte = 0; xByte < 0x40; xByte++) {
                int x = xByte * 2;
                data[pos++] = (byte) (((x & 0x0F) << 4) | ((x + 1) & 0x0F));
            }
        }
        return data;
    }

    private static MgzDrillingRobotnikInstance createBoss(RecordingServices services) {
        MgzDrillingRobotnikInstance boss = new MgzDrillingRobotnikInstance(
                new ObjectSpawn(0x08E0, 0x0690, 0, 0, 0, false, 0), false);
        boss.setServices(services);
        return boss;
    }

    private static void renderBossGraph(MgzDrillingRobotnikInstance boss) {
        List<ObjectInstance> renderGraph = new ArrayList<>();
        renderGraph.add(boss);
        renderGraph.addAll(boss.getChildComponents());
        renderGraph.sort(TestMgzDrillingRobotnikInstance::compareRuntimeRenderOrder);
        renderGraph.forEach(instance -> instance.appendRenderCommands(new ArrayList<>()));
    }

    private static int compareRuntimeRenderOrder(ObjectInstance left, ObjectInstance right) {
        int bucketOrder = Integer.compare(
                RenderPriority.clamp(right.getPriorityBucket()),
                RenderPriority.clamp(left.getPriorityBucket()));
        if (bucketOrder != 0) {
            return bucketOrder;
        }
        int tilePriorityOrder = Boolean.compare(left.isHighPriority(), right.isHighPriority());
        if (tilePriorityOrder != 0) {
            return tilePriorityOrder;
        }
        return Integer.compare(renderSlot(right), renderSlot(left));
    }

    private static int renderSlot(ObjectInstance instance) {
        return instance instanceof AbstractObjectInstance object ? object.getSlotIndex() : Integer.MAX_VALUE;
    }

    private static MgzEndBossInstance createEndBoss(RecordingServices services) {
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(services);
        return boss;
    }

    private static void setMgzCarryActive(Tails tails) {
        tails.getTailsCarryController().restore(new TailsCarryController.Snapshot(
                (short) 0,
                (short) 0,
                true,
                false,
                0,
                TailsCarryController.CarryContext.MGZ_BOSS));
    }

    private static int staticInt(String fieldName) throws Exception {
        Field field = MgzDrillingRobotnikInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setPrivateObject(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean getPrivateBoolean(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
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

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static void assertBodyRegion(TouchResponseProvider.TouchRegion region, int x, int y) {
        assertEquals(x, region.x());
        assertEquals(y, region.y());
        assertEquals(0x0F, region.collisionFlags());
        assertEquals(0, region.shieldReactionFlags());
    }

    private static final class RecordingServices extends TestObjectServices {
        private final ResultsHardwareTimingFixture resultsTiming = new ResultsHardwareTimingFixture();
        private final Camera camera;
        private final Level level;
        private final Palette paletteLine0 = new Palette();
        private final Palette paletteLine1 = new Palette();
        private final Palette paletteLine2 = new Palette();
        private final Palette paletteLine3 = new Palette();
        private final PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        private final ObjectManager objectManager;
        private final ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        private final RecordingGraphics graphics = new RecordingGraphics();
        private final com.openggf.level.render.PatternSpriteRenderer drillRenderer =
                mock(com.openggf.level.render.PatternSpriteRenderer.class);
        private final com.openggf.level.render.PatternSpriteRenderer shipRenderer =
                mock(com.openggf.level.render.PatternSpriteRenderer.class);
        private final com.openggf.level.render.PatternSpriteRenderer debrisRenderer =
                mock(com.openggf.level.render.PatternSpriteRenderer.class);
        private final PatternSpriteRenderer scaledRenderer;
        private final ObjectSpriteSheet scaledSheet;
        private final Rom rom = mock(Rom.class);
        private Rom resultsRom;
        private LevelEventProvider levelEventProvider;
        private int playedSfxCount;
        private int paletteUpdates;
        private int lastPaletteIndex = -1;
        private byte[] firstPaletteRow;
        private int fadeOutMusicCount;
        private int playMusicCount;
        private int requestedZone = -1;
        private int requestedAct = -1;
        private boolean deactivatedForTransition;
        private int romZoneId = 2;
        private int currentAct = 1;
        private ObjectPlayerQuery playerQueryOverride;
        private boolean failRawSidekickAccess;
        private boolean useRuntimeArtRom;
        private final List<Integer> playedSfxIds = new ArrayList<>();

        RecordingServices(Camera camera) throws Exception {
            this.camera = camera;
            this.level = mock(Level.class);
            this.objectManager = new ObjectManager(
                    java.util.List.of(), null, 0, null, null, null, camera, this);

            byte[] normalLine = new byte[32];
            normalLine[22] = 0x00;
            normalLine[23] = 0x22;
            normalLine[26] = 0x02;
            normalLine[27] = 0x44;
            normalLine[28] = 0x04;
            normalLine[29] = 0x66;
            paletteLine1.fromSegaFormat(normalLine);

            when(level.getPaletteCount()).thenReturn(4);
            when(level.getPalette(0)).thenReturn(paletteLine0);
            when(level.getPalette(1)).thenReturn(paletteLine1);
            when(level.getPalette(2)).thenReturn(paletteLine2);
            when(level.getPalette(3)).thenReturn(paletteLine3);

            when(drillRenderer.isReady()).thenReturn(true);
            when(shipRenderer.isReady()).thenReturn(true);
            when(debrisRenderer.isReady()).thenReturn(true);
            scaledSheet = buildScaledCueSheet();
            scaledRenderer = new PatternSpriteRenderer(scaledSheet, graphics);
            scaledRenderer.ensurePatternsCached(graphics, 0x24000);
            when(renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS)).thenReturn(drillRenderer);
            when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
            when(renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS_DEBRIS)).thenReturn(debrisRenderer);
            when(renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS_SCALED)).thenReturn(scaledRenderer);
            when(renderManager.getSheet(Sonic3kObjectArtKeys.MGZ_ENDBOSS_SCALED)).thenReturn(scaledSheet);

            byte[] bossLine = new byte[32];
            byte[] mgzLine = new byte[32];
            mgzLine[0] = 0x00;
            mgzLine[1] = 0x0E;
            mgzLine[2] = 0x02;
            mgzLine[3] = 0x4A;
            when(rom.readBytes(Sonic3kConstants.PAL_MGZ_ENDBOSS_ADDR, 32)).thenReturn(bossLine);
            when(rom.readBytes(Sonic3kConstants.PAL_MGZ_ADDR, 32)).thenReturn(mgzLine);
            for (int i = 0; i < Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROWS; i++) {
                byte[] fadeRow = new byte[Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE];
                fadeRow[0] = 0x00;
                fadeRow[1] = 0x00;
                fadeRow[2] = 0x0E;
                fadeRow[3] = (byte) (0xCA - i);
                when(rom.readBytes(
                        Sonic3kConstants.PAL_MGZ_FADE_CNZ_ADDR
                                + i * Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE,
                        Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE))
                        .thenReturn(fadeRow);
            }
            when(rom.readBytes(Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_ADDR,
                    Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_SIZE))
                    .thenReturn(new byte[Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_SIZE]);
        }

        RecordingServices withScaledCueArt(byte[] data) throws Exception {
            when(rom.readBytes(Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_ADDR,
                    Sonic3kConstants.ART_UNC_MGZ_ENDBOSS_SCALED_SIZE))
                    .thenReturn(data);
            return this;
        }

        RecordingServices withResultsArtFromRom(Rom resultsRom) {
            this.resultsRom = resultsRom;
            try {
                withRomReader(com.openggf.data.RomByteReader.fromRom(resultsRom));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return this;
        }

        @Override
        public com.openggf.game.timing.HardwareTimingService hardwareTiming() {
            return resultsTiming.hardwareTiming();
        }

        RecordingServices withRuntimeArtRom() {
            useRuntimeArtRom = true;
            withResultsArtFromRom(TestEnvironment.currentRom());
            return this;
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public Level currentLevel() {
            return level;
        }

        RecordingServices withZoneAct(int romZoneId, int currentAct) {
            this.romZoneId = romZoneId;
            this.currentAct = currentAct;
            return this;
        }

        RecordingServices withQueryOnlyPlayers(PlayableEntity main, List<? extends PlayableEntity> sidekicks) {
            this.playerQueryOverride = new ObjectPlayerQuery(() -> main, () -> sidekicks);
            this.failRawSidekickAccess = true;
            return this;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return playerQueryOverride != null ? playerQueryOverride : super.playerQuery();
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            if (failRawSidekickAccess) {
                throw new AssertionError("MGZ capsule should use ObjectPlayerQuery for Tails-carry sidekick checks");
            }
            return super.sidekicks();
        }

        @Override
        public int romZoneId() {
            return romZoneId;
        }

        @Override
        public int currentAct() {
            return currentAct;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public GraphicsManager graphicsManager() {
            return graphics;
        }

        @Override
        public Rom rom() {
            return useRuntimeArtRom ? TestEnvironment.currentRom() : rom;
        }

        @Override
        public RuntimeArtCoordinator runtimeArtCoordinator() {
            return TestEnvironment.activeGameplayMode().runtimeArtCoordinator();
        }

        @Override
        public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
            return registry;
        }

        RecordingServices withLevelEventProvider(LevelEventProvider levelEventProvider) {
            this.levelEventProvider = levelEventProvider;
            return this;
        }

        @Override
        public LevelEventProvider levelEventProvider() {
            return levelEventProvider;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfxCount++;
            playedSfxIds.add(soundId);
        }

        @Override
        public void playMusic(int musicId) {
            playMusicCount++;
        }

        @Override
        public void fadeOutMusic() {
            fadeOutMusicCount++;
        }

        @Override
        public void updatePalette(int paletteIndex, byte[] paletteData) {
            paletteUpdates++;
            lastPaletteIndex = paletteIndex;
            if (firstPaletteRow == null) {
                firstPaletteRow = java.util.Arrays.copyOf(paletteData, paletteData.length);
            }
        }

        @Override
        public void requestZoneAndAct(int zone, int act) {
            requestedZone = zone;
            requestedAct = act;
        }

        @Override
        public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
            requestedZone = zone;
            requestedAct = act;
            deactivatedForTransition = deactivateLevelNow;
        }
    }

    private static final class RecordingGraphics extends GraphicsManager {
        private final List<RenderCall> renderCalls = new ArrayList<>();
        private final List<ScaledRenderCall> scaledRenderCalls = new ArrayList<>();

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            renderCalls.add(new RenderCall(patternId, new PatternDesc(desc.get()), x, y));
        }

        @Override
        public void renderPatternWithIdScaled(int patternId, PatternDesc desc,
                float x, float y, float width, float height) {
            scaledRenderCalls.add(new ScaledRenderCall(patternId, new PatternDesc(desc.get()), x, y, width, height));
        }
    }

    private static ObjectSpriteSheet buildScaledCueSheet() {
        Pattern[] patterns = new Pattern[0x100];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
        }
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            frames.add(new SpriteMappingFrame(List.of()));
        }
        frames.add(new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 4, 4, 0x00, false, false, 0),
                new SpriteMappingPiece(0x20, 0, 4, 4, 0x10, false, false, 0),
                new SpriteMappingPiece(0, 0x20, 4, 4, 0x20, false, false, 0),
                new SpriteMappingPiece(0x20, 0x20, 4, 4, 0x30, false, false, 0))));
        return new ObjectSpriteSheet(patterns, frames, 1, 1);
    }

    private record RenderCall(int patternId, PatternDesc desc, int x, int y) {
    }

    private record ScaledRenderCall(int patternId, PatternDesc desc, float x, float y, float width, float height) {
    }

    private static final class RecordingMgzEventBridge implements LevelEventProvider, MgzObjectEventBridge {
        private int collapseHandoffCount;
        private int drillingRobotnikFleeCount;

        @Override
        public void initLevel(int zone, int act) {
        }

        @Override
        public void update() {
        }

        @Override
        public void triggerBossCollapseHandoff() {
            collapseHandoffCount++;
        }

        @Override
        public void completeDrillingRobotnikFlee() {
            drillingRobotnikFleeCount++;
        }
    }
}
