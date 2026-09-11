package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kSuperStateController;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.objects.badniks.JawzBadnikInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossPaletteFadeController;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikShipFlameInstance;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.data.Rom;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Direction;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestMhzBossObjects {
    @AfterEach
    void resetObjectCameraBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryRoutesSklMhzBossSlotsToMhzHandlers() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance tree = registry.create(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));
        ObjectInstance miniboss = registry.create(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        ObjectInstance endBoss = registry.create(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));

        assertInstanceOf(MhzMinibossTreeInstance.class, tree,
                "SKL slot $91 is Obj_MHZMinibossTree, not the S3KL AIZ miniboss slot");
        assertInstanceOf(MhzMinibossInstance.class, miniboss,
                "SKL slot $92 is Obj_MHZMiniboss, not the S3KL AIZ end boss slot");
        assertInstanceOf(MhzEndBossInstance.class, endBoss,
                "SKL slot $93 is Obj_MHZEndBoss, not the S3KL Jawz badnik slot");
    }

    @Test
    void registryKeepsS3klAizAndJawzRoutes() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_AIZ);

        ObjectInstance aizMiniboss = registry.create(new ObjectSpawn(
                0x1000, 0x0300, Sonic3kObjectIds.AIZ_MINIBOSS, 0, 0, false, 0));
        ObjectInstance jawz = registry.create(new ObjectSpawn(
                0x2000, 0x0300, Sonic3kObjectIds.JAWZ, 0, 0, false, 0));

        assertInstanceOf(AizMinibossInstance.class, aizMiniboss);
        assertInstanceOf(JawzBadnikInstance.class, jawz);
    }

    @Test
    void mhzBossesExposeRomCollisionHitCounts() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));

        assertEquals(6, miniboss.getCollisionProperty(),
                "Obj_MHZMiniboss init writes collision_property(a0)=6");
        assertEquals(9, endBoss.getCollisionProperty(),
                "Obj_MHZEndBoss loc_76004 writes collision_property(a0)=9");
        assertEquals(0xCF, miniboss.getCollisionFlags(),
                "ObjDat_MHZMiniboss uses collision size $0F; engine boss touch category is $C0");
        assertEquals(0xCF, endBoss.getCollisionFlags(),
                "Obj_MHZEndBoss active core restores collision size $0F; engine boss touch category is $C0");
    }

    @Test
    void mhzEndBossInitialPositionAppliesRomSpawnXOffset() {
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));

        assertEquals(0x42C0, endBoss.getState().x,
                "Obj_MHZEndBoss init adds $C0 to x_pos(a0) before normal setup");
        assertEquals(0x42C0 << 16, endBoss.getState().xFixed,
                "The fixed-point shadow must match the ROM-adjusted x_pos");
    }

    @Test
    void mhzEndBossSetupPinsCameraMinXAndSetsBossFlag() {
        Camera camera = new Camera();
        camera.setX((short) 0x3C40);
        camera.setMinX((short) 0x0000);
        Sonic3kLevelEventManager events = mock(Sonic3kLevelEventManager.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public com.openggf.game.LevelEventProvider levelEventProvider() {
                return events;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        assertEquals(0x3C40, camera.getMinX() & 0xFFFF,
                "Obj_MHZEndBoss setup writes Camera_X_pos into Camera_min_X_pos before the arena fight");
        verify(events).setBossFlag(true);
    }

    @Test
    void mhzEndBossWaitsForRomCameraRangeBeforeSetup() {
        Camera camera = new Camera();
        camera.setX((short) 0x3900);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0x1234);
        Sonic3kLevelEventManager events = mock(Sonic3kLevelEventManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public com.openggf.game.LevelEventProvider levelEventProvider() {
                return events;
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        assertEquals(0x1234, camera.getMinX() & 0xFFFF,
                "Obj_MHZEndBoss calls Check_CameraInRange with word_769F4 before arena setup");
        verifyNoInteractions(events, objectManager);
        assertEquals(0, endBoss.getCustomFlag(0x100),
                "ChildObjDat_7699C/76982/7697C/76976 are created only after Check_CameraInRange passes");
    }

    @Test
    void mhzEndBossLoadsRomPaletteIntoOwnershipRegistryLineOne() {
        byte[] paletteLine = new byte[32];
        paletteLine[0] = 0x0E;
        paletteLine[1] = (byte) 0xEE;
        paletteLine[2] = 0x08;
        paletteLine[3] = (byte) 0x88;

        RecordingPaletteServices services = new RecordingPaletteServices();
        services.withRom(new FixedReadRom(paletteLine));
        services.registry.beginFrame();
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.MHZ_END_BOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 0),
                "Obj_MHZEndBoss should load Pal_MHZEndBoss through the boss palette owner");
        assertEquals(S3kPaletteOwners.MHZ_END_BOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 1),
                "PalLoad_Line1 should own the full palette line");
        assertColorWord(services.level.getPalette(1), 0, 0x0EEE);
        assertColorWord(services.level.getPalette(1), 1, 0x0888);
    }

    @Test
    void mhzMinibossLoadsRomPaletteIntoOwnershipRegistryLineOne() {
        byte[] paletteLine = new byte[32];
        paletteLine[0] = 0x0E;
        paletteLine[1] = (byte) 0xEE;
        paletteLine[2] = 0x02;
        paletteLine[3] = 0x24;
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);

        RecordingPaletteServices services = new RecordingPaletteServices();
        services.withRom(new OffsetReadRom().with(Sonic3kConstants.PAL_MHZ_MINIBOSS_ADDR, paletteLine));
        services.withCamera(camera);
        services.registry.beginFrame();
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.MHZ_MINIBOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 0),
                "Obj_MHZMiniboss should load Pal_MHZMiniboss through the miniboss palette owner");
        assertEquals(S3kPaletteOwners.MHZ_MINIBOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 1),
                "PalLoad_Line1 should own the full palette line");
        assertColorWord(services.level.getPalette(1), 0, 0x0EEE);
        assertColorWord(services.level.getPalette(1), 1, 0x0224);
    }

    @Test
    void mhzMinibossSetupPlaysMinibossMusic() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        RecordingPaletteServices services = new RecordingPaletteServices();
        services.withCamera(camera);
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);

        assertEquals(Sonic3kMusic.MINIBOSS.id, services.lastMusicId,
                "Obj_MHZMiniboss setup spawns Obj_PlayMusic with subtype mus_Miniboss");
    }

    @Test
    void mhzEndBossHitFlashWritesRomFiveWordPaletteSet() {
        RecordingPaletteServices services = new RecordingPaletteServices();
        services.withRom(new FixedReadRom(new byte[32]));
        com.openggf.graphics.GraphicsManager graphicsManager = mock(com.openggf.graphics.GraphicsManager.class);
        when(graphicsManager.isGlInitialized()).thenReturn(false);
        services.withGraphicsManager(graphicsManager);
        services.registry.beginFrame();
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        endBoss.onPlayerAttack(null, null);
        services.registry.beginFrame();
        endBoss.update(1, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        // loc_767B0 seeds $20(a0)=$20 (32, even) on the fresh-hit frame; loc_767E8's
        // btst #0,$20(a0) is clear on an even count, so the first painted frame is the
        // white/flash row, not the real-color row.
        Palette palette = services.level.getPalette(1);
        assertColorWord(palette, 4, 0x0888);
        assertColorWord(palette, 10, 0x0AAA);
        assertColorWord(palette, 11, 0x0EEE);
        assertColorWord(palette, 13, 0x0888);
        assertColorWord(palette, 14, 0x0AAA);
    }

    @Test
    void mhzEndBossHitFlashRestoresRomRealColorsWhenInvulnerabilityWindowEnds() {
        RecordingPaletteServices services = new RecordingPaletteServices();
        services.withRom(new FixedReadRom(new byte[32]));
        com.openggf.graphics.GraphicsManager graphicsManager = mock(com.openggf.graphics.GraphicsManager.class);
        when(graphicsManager.isGlInitialized()).thenReturn(false);
        services.withGraphicsManager(graphicsManager);
        services.registry.beginFrame();
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        endBoss.onPlayerAttack(null, null);
        for (int frame = 1; frame <= 32; frame++) {
            services.registry.beginFrame();
            endBoss.update(frame, null);
            services.registry.resolveInto(services.level.palettes(), null, null, null);
        }

        // loc_767E8 decrements $20(a0) AFTER painting; the last painted frame occurs at
        // $20==1 (odd), which selects the real-color row, so the flash ends resting on the
        // boss's normal colors rather than the white row.
        assertEquals(false, endBoss.getState().invulnerable,
                "loc_767F6 clears status bit 6 once $20(a0) underflows to zero");
        Palette palette = services.level.getPalette(1);
        assertColorWord(palette, 4, 0x0E42);
        assertColorWord(palette, 10, 0x0228);
        assertColorWord(palette, 11, 0x0000);
        assertColorWord(palette, 13, 0x0C40);
        assertColorWord(palette, 14, 0x0820);
    }

    @Test
    void mhzEndBossQueuesSpikeArtWhenCameraReachesRomThreshold() {
        Camera camera = new Camera();
        camera.setX((short) 0x400F);
        Sonic3kObjectArtProvider artProvider = mock(Sonic3kObjectArtProvider.class);
        ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0A;
        endBoss.setCustomFlag(0x2E, 0);

        endBoss.update(0, null);
        verifyNoInteractions(artProvider);

        camera.setX((short) 0x4010);
        endBoss.update(1, null);

        verify(artProvider).ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES);
        verifyNoMoreInteractions(artProvider);
    }

    @Test
    void mhzMinibossFatalHitQueuesRomFadeExplosionAndSignpostHandoff() {
        List<ObjectInstance> spawned = new ArrayList<>();
        int[] lastSfx = {-1};
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.getState().hitCount = 1;
        miniboss.getState().routine = 0x0E;
        miniboss.setCustomFlag(0x2E, 0);

        miniboss.onPlayerAttack(null, null);
        miniboss.update(0, null);
        miniboss.update(1, null);
        miniboss.update(2, null);

        assertEquals(0, miniboss.getState().hitCount,
                "Touch_Enemy_Part2 consumes the final Obj_MHZMiniboss collision_property count");
        assertEquals(0, miniboss.getState().invulnerabilityTimer,
                "loc_75DCC replaces the boss routine instead of leaving the base hit-flash timer active");
        assertEquals(Sonic3kSfx.EXPLODE.id, lastSfx[0],
                "Child6_CreateBossExplosion subtype $10 starts the timed explosion controller");
        assertEquals(1, spawned.stream().filter(SongFadeTransitionInstance.class::isInstance).count(),
                "Wait_FadeToLevelMusic allocates Obj_Song_Fade_ToLevelMusic when $2E underflows");
        assertEquals(1, spawned.stream().filter(S3kBossDefeatSignpostFlow.class::isInstance).count(),
                "loc_757BA hands off to Obj_EndSignControl after the music fade wait");
        assertEquals(1, spawned.stream().filter(S3kBossExplosionChild.class::isInstance).count(),
                "Child6_CreateBossExplosion subtype $10 emits normal explosion children every three frames");
        assertEquals(true, miniboss.isDestroyed(),
                "the boss body slot is replaced by the persistent signpost-flow controller");
        verify(levelState).pauseTimer();
        verify(gameState).addScore(1000);
    }

    @Test
    void mhzEndBossChildSignalArmsRisePrepWait() {
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setCustomFlag(0x38, 0x04);

        endBoss.update(0, null);

        assertEquals(4, endBoss.getState().routine,
                "loc_7601A switches to routine $04 when the vulnerable child sets $38 bit 2");
        assertEquals(0x3F, endBoss.getCustomFlag(0x2E),
                "loc_7601A seeds Obj_Wait timer $2E=$3F before loc_7603E");
    }

    @Test
    void mhzEndBossWeatherMachineChildSignalsParentWhenDestroyed() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        Optional<ObjectInstance> weatherMachineSpawn = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst();
        assertTrue(weatherMachineSpawn.isPresent(),
                "Obj_MHZEndBoss init creates the loc_76520 weather-machine child from ChildObjDat_76982");
        ObjectInstance weatherMachine = weatherMachineSpawn.get();
        assertEquals(0x42D1, weatherMachine.getX(),
                "ChildObjDat_76982 offsets loc_76520 by +$11 from the parent x_pos");
        assertEquals(0x02AF, weatherMachine.getY(),
                "ChildObjDat_76982 offsets loc_76520 by -$51 from the parent y_pos");
        assertEquals(0x11, ((TouchResponseProvider) weatherMachine).getCollisionFlags(),
                "ObjDat3_76940 gives the weather-machine child collision_flags=$11");

        ((AbstractObjectInstance) weatherMachine).setServices(services);
        ((TouchResponseAttackable) weatherMachine).onPlayerAttack(null, null);
        weatherMachine.update(1, null);

        assertEquals(0x04, endBoss.getCustomFlag(0x38) & 0x04,
                "loc_76574 sets parent $38 bit 2 when the weather-machine child is destroyed");
        assertEquals(0, ((TouchResponseProvider) weatherMachine).getCollisionFlags(),
                "loc_7654A branches to loc_76574 once collision_flags becomes zero");
        assertEquals(1, spawned.stream()
                        .filter(SongFadeTransitionInstance.class::isInstance)
                        .count(),
                "loc_765DE allocates Obj_Song_Fade_Transition with mus_EndBoss when the weather machine breaks");
        assertEquals(1, spawned.stream()
                        .filter(S3kBossExplosionChild.class::isInstance)
                        .count(),
                "loc_765F2 creates Child6_CreateBossExplosion when the weather machine breaks");
    }

    @Test
    void mhzEndBossWeatherMachineKeepsSpawnPositionWhileParentMoves() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        endBoss.getState().x = 0x4300;
        endBoss.getState().y = 0x0340;
        weatherMachine.update(1, null);

        assertEquals(0x42D1, weatherMachine.getX(),
                "loc_7654A does not call Refresh_ChildPositionAdjusted; the weather machine keeps its spawn x_pos");
        assertEquals(0x02AF, weatherMachine.getY(),
                "loc_7654A does not call Refresh_ChildPositionAdjusted; the weather machine keeps its spawn y_pos");
    }

    @Test
    void mhzEndBossWeatherMachinePlaysRomLoopingSfxWhenCountdownUnderflows() {
        List<ObjectInstance> spawned = new ArrayList<>();
        int[] playCount = {0};
        int[] lastSfx = {-1};
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public void playSfx(int soundId) {
                playCount[0]++;
                lastSfx[0] = soundId;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        weatherMachine.update(1, null);
        weatherMachine.update(2, null);

        assertEquals(1, playCount[0],
                "loc_7654A plays sfx_WeatherMachine only when $2E underflows, then reseeds $2E to Random($F)+8");
        assertEquals(Sonic3kSfx.WEATHER_MACHINE.id, lastSfx[0],
                "loc_7654A queues sfx_WeatherMachine when the live weather-machine timer underflows");
    }

    @Test
    void mhzEndBossWeatherMachineDeletesAfterRomWaitDrawTimer() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        ((TouchResponseAttackable) weatherMachine).onPlayerAttack(null, null);
        weatherMachine.update(1, null);
        assertEquals(false, weatherMachine.isDestroyed(),
                "loc_76574 switches to Wait_Draw and keeps drawing while $2E=$3F counts down");

        for (int frame = 2; frame <= 65; frame++) {
            weatherMachine.update(frame, null);
        }

        assertEquals(true, weatherMachine.isDestroyed(),
                "Go_Delete_Sprite runs after the loc_76574 Wait_Draw timer expires");
    }

    @Test
    void mhzEndBossWeatherMachineSpawnsRomPaletteFadeController() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        RecordingPaletteServices services = new RecordingPaletteServices()
                .withObjectManager(objectManager);
        services.withRom(new FixedReadRom(new byte[64]));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        ((TouchResponseAttackable) weatherMachine).onPlayerAttack(null, null);
        weatherMachine.update(1, null);
        MhzEndBossPaletteFadeController paletteFade = spawned.stream()
                .filter(MhzEndBossPaletteFadeController.class::isInstance)
                .map(MhzEndBossPaletteFadeController.class::cast)
                .findFirst()
                .orElseThrow();
        paletteFade.setServices(services);

        services.registry.beginFrame();
        paletteFade.update(2, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.MHZ_END_BOSS_DEFEAT_FADE,
                services.registry.ownerAt(PaletteSurface.NORMAL, 0, 0),
                "loc_85E64 owns the full Normal_palette while fading toward white");
        assertColorWord(services.level.getPalette(0), 0, 0x0222);
    }

    @Test
    void mhzEndBossPaletteFadeSuppressesSuperPaletteRotationScript() throws Exception {
        RecordingPaletteServices services = new RecordingPaletteServices();
        services.level.getPalette(0).fromSegaFormat(paletteLine(0));
        MhzEndBossPaletteFadeController paletteFade =
                new MhzEndBossPaletteFadeController(new byte[][] {
                        paletteLine(0), paletteLine(0), paletteLine(0), paletteLine(0)
                });
        paletteFade.setServices(services);

        services.registry.beginFrame();
        paletteFade.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestableS3kSuperStateController superState = new TestableS3kSuperStateController(player);
        superState.seedActiveCycle(new byte[] {
                0x00, 0x02, 0x00, 0x04, 0x00, 0x06,
                0x00, 0x08, 0x00, 0x0A, 0x00, 0x0C
        });
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getCurrentLevel()).thenReturn(services.level);

        try (MockedStatic<GameServices> gameServices = mockStatic(GameServices.class)) {
            gameServices.when(GameServices::level).thenReturn(levelManager);
            gameServices.when(GameServices::paletteOwnershipRegistryOrNull).thenReturn(services.registry);
            gameServices.when(GameServices::graphics).thenReturn(mock(com.openggf.graphics.GraphicsManager.class));

            superState.tickSuperPalette();
        }

        assertColorWord(services.level.getPalette(0), 2, 0x0222);
        assertColorWord(services.level.getPalette(0), 3, 0x0222);
        assertColorWord(services.level.getPalette(0), 4, 0x0222);
    }

    @Test
    void mhzEndBossWeatherMachineFadeReturnsToRomPostWeatherPaletteTargets() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        byte[] currentLine1 = paletteLine(0x0246);
        byte[] endBossLine2 = paletteLine(0x0864);
        byte[] springLines3And4 = paletteBlock(0x0A20, 0x004E);
        RecordingPaletteServices services = new RecordingPaletteServices()
                .withObjectManager(objectManager);
        services.level.getPalette(0).fromSegaFormat(currentLine1);
        services.withRom(new OffsetReadRom()
                .with(Sonic3kConstants.PAL_MHZ_END_BOSS_ADDR, endBossLine2)
                .with(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR, springLines3And4));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        ((TouchResponseAttackable) weatherMachine).onPlayerAttack(null, null);
        weatherMachine.update(1, null);
        MhzEndBossPaletteFadeController paletteFade = spawned.stream()
                .filter(MhzEndBossPaletteFadeController.class::isInstance)
                .map(MhzEndBossPaletteFadeController.class::cast)
                .findFirst()
                .orElseThrow();
        paletteFade.setServices(services);

        for (int frame = 2; frame < 80; frame++) {
            services.registry.beginFrame();
            paletteFade.update(frame, null);
            services.registry.resolveInto(services.level.palettes(), null, null, null);
        }

        assertColorWord(services.level.getPalette(0), 0, 0x0246);
        assertColorWord(services.level.getPalette(1), 0, 0x0864);
        assertColorWord(services.level.getPalette(2), 0, 0x0A20);
        assertColorWord(services.level.getPalette(3), 0, 0x004E);
    }

    @Test
    void mhzEndBossWeatherMachineDestructionClearsRomSeasonFlag() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        Sonic3kMHZEvents events = new Sonic3kMHZEvents();
        events.applySeasonStateForTest(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN);
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS, events);
        RecordingPaletteServices services = new RecordingPaletteServices()
                .withObjectManager(objectManager)
                .withZoneRuntimeState(runtimeState);
        services.withRom(new FixedReadRom(new byte[64]));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        ((TouchResponseAttackable) weatherMachine).onPlayerAttack(null, null);
        weatherMachine.update(1, null);

        assertEquals(false, runtimeState.isSeasonFlagSet(),
                "loc_765F2 clears _unkF7C1 after creating the MHZ end-boss destruction effects");
    }

    @Test
    void mhzEndBossWeatherMachineSpawnsRomAnimatedVisualChildren() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        weatherMachine.update(1, null);

        List<ObjectInstance> animatedParts = spawned.stream()
                .filter(object -> "MHZEndBossWeatherAnimatedPart".equals(object.getName()))
                .toList();
        List<ObjectInstance> sparks = spawned.stream()
                .filter(object -> "MHZEndBossWeatherSpark".equals(object.getName()))
                .toList();
        assertEquals(2, animatedParts.size(),
                "loc_76520 creates two loc_76604 animated weather-machine children from ChildObjDat_769A4");
        assertEquals(4, sparks.size(),
                "loc_76520 creates four loc_76636 children from ChildObjDat_769AA");
        for (ObjectInstance animatedPart : animatedParts) {
            ((AbstractObjectInstance) animatedPart).setServices(services);
            assertEquals(0x42D1, animatedPart.getX(),
                    "loc_76604 children inherit the weather-machine x_pos");
            assertEquals(0x02AF, animatedPart.getY(),
                    "loc_76604 children inherit the weather-machine y_pos");
            assertEquals(3, animatedPart.getPriorityBucket(),
                    "word_76958 gives loc_76604 priority $180");
            animatedPart.appendRenderCommands(new ArrayList<>());
        }
        assertEquals(0x4284, sparks.get(0).getX(),
                "word_7672E subtype 0 offsets loc_76636 by -$4D x");
        assertEquals(0x0261, sparks.get(0).getY(),
                "word_7672E subtype 0 offsets loc_76636 by -$4E y");
        assertEquals(0x4230, sparks.get(1).getX(),
                "word_7672E subtype 1 offsets loc_76636 by -$A1 x");
        assertEquals(0x020D, sparks.get(1).getY(),
                "word_7672E subtype 1 offsets loc_76636 by -$A2 y");
        assertEquals(0x41DC, sparks.get(2).getX(),
                "word_7672E subtype 2 offsets loc_76636 by -$F5 x");
        assertEquals(0x01B9, sparks.get(2).getY(),
                "word_7672E subtype 2 offsets loc_76636 by -$F6 y");
        assertEquals(0x4188, sparks.get(3).getX(),
                "word_7672E subtype 3 offsets loc_76636 by -$149 x");
        assertEquals(0x0165, sparks.get(3).getY(),
                "word_7672E subtype 3 offsets loc_76636 by -$14A y");
        for (ObjectInstance spark : sparks) {
            ((AbstractObjectInstance) spark).setServices(services);
            assertEquals(3, spark.getPriorityBucket(),
                    "word_7695E gives loc_76636 priority $180");
            spark.appendRenderCommands(new ArrayList<>());
        }

        verify(renderer, org.mockito.Mockito.times(2)).drawFrameIndex(5, 0x42D1, 0x02AF, false, false);
        // byte_769CE's initial mapping frame is $D (sonic3k.asm:157786-157787); the
        // three Animate_Raw ticks that follow are $E, $F, $10.
        verify(renderer).drawFrameIndex(0x0D, 0x4284, 0x0261, false, false);
        verify(renderer).drawFrameIndex(0x0D, 0x4230, 0x020D, false, false);
        verify(renderer).drawFrameIndex(0x0D, 0x41DC, 0x01B9, false, false);
        verify(renderer).drawFrameIndex(0x0D, 0x4188, 0x0165, false, false);
    }

    @Test
    void mhzEndBossWeatherVisualChildrenDeleteWhenWeatherMachineParentIsDeleted() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        ObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) weatherMachine).setServices(services);

        weatherMachine.update(1, null);
        List<ObjectInstance> visualChildren = spawned.stream()
                .filter(object -> "MHZEndBossWeatherAnimatedPart".equals(object.getName())
                        || "MHZEndBossWeatherSpark".equals(object.getName()))
                .toList();
        assertEquals(6, visualChildren.size());
        for (ObjectInstance child : visualChildren) {
            ((AbstractObjectInstance) child).setServices(services);
        }

        ((AbstractObjectInstance) weatherMachine).setDestroyed(true);
        visualChildren.forEach(child -> child.update(2, null));

        for (ObjectInstance child : visualChildren) {
            assertTrue(child.isDestroyed(),
                    "Child_Draw_Sprite deletes loc_76604/loc_76636 children when their weather-machine parent has status bit 7 set");
        }
    }

    @Test
    void mhzEndBossChildObjectsUseRomObjectDataRenderBounds() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        spawned.stream()
                .filter(object -> "MHZEndBossRobotnikHead".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(head -> assertRenderBounds(head, 0x10, 0x08,
                        "ObjDat_RobotnikHead gives Obj_RobotnikHead4 x/y radius $10,$08"));
        spawned.stream()
                .filter(object -> "MHZEndBossVisual".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(visual -> assertRenderBounds(visual, 0x80, 0x80,
                        "ObjDat3_76934 gives loc_764D0/loc_76502 visual x/y radius $80,$80"));

        AbstractObjectInstance weatherMachine = spawned.stream()
                .filter(object -> "MHZEndBossWeatherMachine".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
        weatherMachine.setServices(services);
        weatherMachine.update(1, null);

        assertRenderBounds(weatherMachine, 0x80, 0x80,
                "ObjDat3_76940 gives loc_76520 x/y radius $80,$80");
        spawned.stream()
                .filter(object -> "MHZEndBossSpike".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(spike -> assertRenderBounds(spike, 0x10, 0x10,
                        "ObjDat3_7694C gives loc_7665E x/y radius $10,$10"));
        spawned.stream()
                .filter(object -> "MHZEndBossHitProxy".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(hitProxy -> assertRenderBounds(hitProxy, 0x18, 0x28,
                        "word_76964 gives loc_764A0 x/y radius $18,$28"));
        spawned.stream()
                .filter(object -> "MHZEndBossWeatherAnimatedPart".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(animatedPart -> assertRenderBounds(animatedPart, 0x10, 0x10,
                        "word_76958 gives loc_76604 x/y radius $10,$10"));
        spawned.stream()
                .filter(object -> "MHZEndBossWeatherSpark".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .forEach(spark -> assertRenderBounds(spark, 0x80, 0x80,
                        "word_7695E gives loc_76636 x/y radius $80,$80"));
        MhzEndBossRobotnikShipFlameInstance flame = new MhzEndBossRobotnikShipFlameInstance(endBoss);
        assertRenderBounds(flame, 0x08, 0x04,
                "ObjDat3_RoboShipFlame gives Obj_RobotnikShipFlame x/y radius $08,$04");
    }

    @Test
    void mhzEndBossHitProxyUsesRomOffsetAndDelegatesAcceptedHits() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().hitCount = 2;

        endBoss.update(0, null);

        Optional<ObjectInstance> hitProxySpawn = spawned.stream()
                .filter(object -> "MHZEndBossHitProxy".equals(object.getName()))
                .findFirst();
        assertTrue(hitProxySpawn.isPresent(),
                "ChildObjDat_76982 creates the loc_764A0 hit proxy child after the weather-machine child");
        ObjectInstance hitProxy = hitProxySpawn.get();
        assertEquals(0x42E1, hitProxy.getX(),
                "ChildObjDat_76982 offsets loc_764A0 by +$21 from the parent x_pos");
        assertEquals(0x02F0, hitProxy.getY(),
                "ChildObjDat_76982 offsets loc_764A0 by -$10 from the parent y_pos");
        assertEquals(0x25, ((TouchResponseProvider) hitProxy).getCollisionFlags(),
                "word_76964 gives the hit proxy collision_flags=$25");
        assertEquals(-1, ((TouchResponseProvider) hitProxy).getCollisionProperty(),
                "loc_764A0 writes collision_property(a0)=-1 for the hit proxy slot");

        ((AbstractObjectInstance) hitProxy).setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4210, (short) 0x02F0);
        player.setXSpeed((short) 0x0600);

        ((TouchResponseAttackable) hitProxy).onPlayerAttack(player, null);

        assertEquals(1, endBoss.getState().hitCount,
                "sub_76782 consumes the accepted hit through the child slot's parent pointer");
        assertEquals(0, player.getXSpeed(),
                "sub_76782 clears x_vel on the player that struck the hit proxy child");
        assertEquals(0, ((TouchResponseProvider) hitProxy).getCollisionFlags(),
                "sub_76782 clears collision_flags on both the parent and child during the hit flash");
    }

    @Test
    void mhzEndBossHitProxyQueuesTouchResponseWithoutDrawing() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        ObjectInstance hitProxy = spawned.stream()
                .filter(object -> "MHZEndBossHitProxy".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) hitProxy).setServices(services);
        hitProxy.appendRenderCommands(new ArrayList<>());

        verifyNoInteractions(renderManager, renderer);
    }

    @Test
    void mhzEndBossRobotnikShipFlameMirrorsWithParentRenderFlags() {
        Sonic3kObjectArtProvider artProvider = mock(Sonic3kObjectArtProvider.class);
        PatternSpriteRenderer renderer = readyRenderer();
        when(artProvider.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4400, 0x0200, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().renderFlags = 1;
        endBoss.getState().xVel = 0x400;

        MhzEndBossRobotnikShipFlameInstance flame = new MhzEndBossRobotnikShipFlameInstance(endBoss);
        flame.setServices(services);
        flame.update(0, null);
        flame.appendRenderCommands(new ArrayList<>());

        int expectedFlameX = endBoss.getX() - 0x1E;
        assertEquals(expectedFlameX, flame.getX(),
                "Refresh_ChildPositionAdjusted mirrors Child1_MakeRoboShipFlame dx=$1E when parent render_flags bit 0 is set");
        assertEquals(endBoss.getY(), flame.getY(),
                "Child1_MakeRoboShipFlame keeps dy=0 while mirroring only x");
        verify(renderer).drawFrameIndex(6, expectedFlameX, endBoss.getY(), true, false);
    }

    @Test
    void mhzEndBossArenaAlternateSpikeReturnsBeforeDrawWhenInactive() {
        Sonic3kMHZEvents events = mock(Sonic3kMHZEvents.class);
        when(events.isEndBossArenaForegroundRefreshActive()).thenReturn(true);
        when(events.isEndBossArenaSpikeDeletionFlagSet()).thenReturn(false);
        when(events.getEndBossArenaSpikeActiveForTest()).thenReturn(new boolean[] {false, false});
        when(events.getEndBossArenaSpikeYForTest()).thenReturn(new int[] {-1, -1});
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES)).thenReturn(renderer);
        MhzEndBossArenaHelperInstance spike =
                MhzEndBossArenaHelperInstance.spike(events, 1, 2, true);
        spike.setServices(servicesWithRenderManager(renderManager));

        spike.update(0, null);
        spike.appendRenderCommands(new ArrayList<>());

        assertEquals(0, spike.getCollisionFlags(),
                "loc_557C8 should not add inactive alternate spike helpers to collision response");
        verifyNoInteractions(renderManager, renderer);
    }

    @Test
    void mhzEndBossHitProxyDeletesWhenParentStatusBit7IsSet() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);

        ObjectInstance hitProxy = spawned.stream()
                .filter(object -> "MHZEndBossHitProxy".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) hitProxy).setServices(services);

        endBoss.setDestroyed(true);
        hitProxy.update(1, null);

        assertTrue(hitProxy.isDestroyed(),
                "Child_AddToTouchList deletes the hit proxy when parent3 has status bit 7 set");
    }

    @Test
    void mhzEndBossSpikeChildrenUseRomOffsetsAndAlternatingDashCollision() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        List<ObjectInstance> spikes = spawned.stream()
                .filter(object -> "MHZEndBossSpike".equals(object.getName()))
                .toList();
        assertEquals(2, spikes.size(),
                "ChildObjDat_76982 creates the two loc_7665E spike children before the weather-machine child");
        ObjectInstance frontSpike = spikes.get(0);
        ObjectInstance rearSpike = spikes.get(1);
        assertEquals(0x42AC, frontSpike.getX(),
                "ChildObjDat_76982 offsets the first loc_7665E child by -$14 from parent x_pos");
        assertEquals(0x0318, frontSpike.getY(),
                "ChildObjDat_76982 offsets both spike children by +$18 from parent y_pos");
        assertEquals(0x4294, rearSpike.getX(),
                "ChildObjDat_76982 offsets the second loc_7665E child by -$2C from parent x_pos");

        ((AbstractObjectInstance) frontSpike).setServices(services);
        ((AbstractObjectInstance) rearSpike).setServices(services);
        TouchResponseProvider frontProvider = (TouchResponseProvider) frontSpike;
        TouchResponseProvider rearProvider = (TouchResponseProvider) rearSpike;

        endBoss.setCustomFlag(0x38, 0);
        frontSpike.update(0, null);
        rearSpike.update(0, null);
        assertEquals(0, frontProvider.getCollisionFlags(),
                "loc_766A8 skips adding spike collision while parent $38 bit 6 is clear");
        assertEquals(0, rearProvider.getCollisionFlags(),
                "both spike subtypes are disabled until the dash-phase bit is set");

        endBoss.setCustomFlag(0x38, 0x40);
        frontSpike.update(0, null);
        rearSpike.update(0, null);
        assertEquals(0x8B, frontProvider.getCollisionFlags(),
                "loc_76698/loc_766A8 enables subtype 0 on even V-int frames during the dash phase");
        assertEquals(0, rearProvider.getCollisionFlags(),
                "loc_76698 inverts the parity gate for nonzero subtype");

        frontSpike.update(1, null);
        rearSpike.update(1, null);
        assertEquals(0, frontProvider.getCollisionFlags(),
                "subtype 0 skips odd V-int frames");
        assertEquals(0x8B, rearProvider.getCollisionFlags(),
                "subtype 1 is active on odd V-int frames during the dash phase");
    }

    @Test
    void mhzEndBossSpikeChildrenDeleteWhenParentStatusBit7IsSet() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);

        List<ObjectInstance> spikes = spawned.stream()
                .filter(object -> "MHZEndBossSpike".equals(object.getName()))
                .toList();
        assertEquals(2, spikes.size());
        for (ObjectInstance spike : spikes) {
            ((AbstractObjectInstance) spike).setServices(services);
        }

        endBoss.setDestroyed(true);
        spikes.forEach(spike -> spike.update(1, null));

        for (ObjectInstance spike : spikes) {
            assertTrue(spike.isDestroyed(),
                    "loc_76674 deletes spike children when parent3 has status bit 7 set");
        }
    }

    @Test
    void mhzEndBossSpikeDrawsOnlyOnRomActiveCollisionFrames() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);

        ObjectInstance frontSpike = spawned.stream()
                .filter(object -> "MHZEndBossSpike".equals(object.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) frontSpike).setServices(services);

        endBoss.setCustomFlag(0x38, 0);
        frontSpike.update(0, null);
        frontSpike.appendRenderCommands(new ArrayList<>());

        verifyNoInteractions(renderManager, renderer);
    }

    @Test
    void mhzEndBossVisualChildrenUseRomPriorityFramesAndArenaHighPriorityFlag() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        List<ObjectInstance> visuals = spawned.stream()
                .filter(object -> "MHZEndBossVisual".equals(object.getName()))
                .toList();
        assertEquals(3, visuals.size(),
                "Obj_MHZEndBoss init creates one loc_764D0 visual and two loc_76502 visual layers");
        for (ObjectInstance visual : visuals) {
            ((AbstractObjectInstance) visual).setServices(services);
            assertEquals(0x42C0, visual.getX(),
                    "sub_7675C refreshes visual-child x_pos from the parent every frame");
            assertEquals(0x0300, visual.getY(),
                    "sub_7675C refreshes visual-child y_pos from the parent every frame");
        }

        assertEquals(5, visuals.get(0).getPriorityBucket(),
                "loc_764D0 uses ObjDat3_76934 priority $280");
        assertEquals(6, visuals.get(1).getPriorityBucket(),
                "sub_7673E subtype 0 uses word_76754 priority $300");
        assertEquals(4, visuals.get(2).getPriorityBucket(),
                "sub_7673E subtype 1 uses word_76754 priority $200");

        List<GLCommand> commands = new ArrayList<>();
        visuals.forEach(visual -> visual.appendRenderCommands(commands));

        verify(renderer).drawFrameIndex(1, 0x42C0, 0x0300, false, false);
        verify(renderer).drawFrameIndex(2, 0x42C0, 0x0300, false, false);
        verify(renderer).drawFrameIndex(3, 0x42C0, 0x0300, false, false);

        endBoss.setCustomFlag(0x38, 0x08);
        visuals.get(0).update(1, null);

        assertEquals(true, visuals.get(0).isHighPriority(),
                "loc_764E0 sets the loc_764D0 child's art_tile high-priority bit once parent $38 bit 3 is set");
    }

    @Test
    void mhzEndBossVisualChildrenDeleteWhenParentSetsChildDrawSprite2Flag() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);

        List<ObjectInstance> visuals = spawned.stream()
                .filter(object -> "MHZEndBossVisual".equals(object.getName()))
                .toList();
        assertEquals(3, visuals.size());
        for (ObjectInstance visual : visuals) {
            ((AbstractObjectInstance) visual).setServices(services);
        }

        endBoss.setCustomFlag(0x38, 0x10);
        visuals.forEach(visual -> visual.update(1, null));

        for (ObjectInstance visual : visuals) {
            assertTrue(visual.isDestroyed(),
                    "Child_Draw_Sprite2 deletes loc_764D0/loc_76502 visual children when parent $38 bit 4 is set");
        }
    }

    @Test
    void mhzEndBossSpawnsRobotnikHead4ChildFromRomSetupData() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);

        endBoss.update(0, null);

        AbstractObjectInstance head = spawned.stream()
                .filter(object -> "MHZEndBossRobotnikHead".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
        head.setServices(services);
        assertEquals(0x42C0, head.getX(),
                "ChildObjDat_7699C spawns Obj_RobotnikHead4 at parent x_pos");
        assertEquals(0x02E4, head.getY(),
                "ChildObjDat_7699C offsets Obj_RobotnikHead4 by -$1C y");
        assertEquals(5, head.getPriorityBucket(),
                "Obj_RobotnikHead4 calls Child_GetPriority, inheriting the parent priority $280");

        head.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawFrameIndex(0, 0x42C0, 0x02E4, false, false);

        endBoss.setCustomFlag(0x38, endBoss.getCustomFlag(0x38) | 0x20);
        head.update(1, null);

        assertEquals(true, head.isDestroyed(),
                "Obj_RobotnikHead4 deletes when parent $38 bit 5 is set during the post-capsule handoff");
    }

    @Test
    void mhzEndBossRobotnikHeadDoesNotUseDefeatedFrameAfterHurtFlashClears() {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.update(0, null);
        AbstractObjectInstance head = spawned.stream()
                .filter(object -> "MHZEndBossRobotnikHead".equals(object.getName()))
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
        head.setServices(services);

        endBoss.getState().invulnerable = true;
        head.update(1, null);
        head.appendRenderCommands(new ArrayList<>());
        endBoss.getState().invulnerable = false;
        for (int frame = 2; frame <= 8; frame++) {
            head.update(frame, null);
            head.appendRenderCommands(new ArrayList<>());
        }

        verify(renderer, org.mockito.Mockito.atLeastOnce()).drawFrameIndex(2, 0x42C0, 0x02E4, false, false);
        verify(renderer, org.mockito.Mockito.never()).drawFrameIndex(3, 0x42C0, 0x02E4, false, false);
    }

    @Test
    void mhzEndBossRisePrepWaitCallbackStartsUpwardLaunch() {
        int[] lastSfx = {-1};
        ObjectServices services = new StubObjectServices() {
            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 4;
        endBoss.setCustomFlag(0x2E, 0);

        endBoss.update(0, null);

        assertEquals(6, endBoss.getState().routine,
                "loc_7603E switches to routine $06 after the rise-prep Obj_Wait expires");
        assertEquals(-0x200, endBoss.getState().yVel,
                "loc_7603E launches the boss upward with y_vel=-$200");
        assertEquals(0x23, endBoss.getCustomFlag(0x2E),
                "loc_7603E seeds the next Obj_Wait timer $2E=$23 before loc_7606E");
        assertEquals(Sonic3kSfx.RISING.id, lastSfx[0],
                "loc_7603E plays sfx_Rising when the upward launch starts");
    }

    @Test
    void mhzEndBossUpwardLaunchWaitCallbackStartsSwingWait() {
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.getState().routine = 6;
        endBoss.getState().yVel = -0x200;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 1);

        endBoss.update(0, null);

        assertEquals(8, endBoss.getState().routine,
                "loc_7606E switches to routine $08 after routine $06 MoveSprite2/Obj_Wait expires");
        assertEquals(0x02FE, endBoss.getY(),
                "loc_76062 applies MoveSprite2 with y_vel=-$200 before the callback");
        assertEquals(0x3F, endBoss.getCustomFlag(0x2E),
                "loc_7606E seeds the swing Obj_Wait timer $2E=$3F before loc_7609A");
        assertEquals(0xC0, endBoss.getState().yVel,
                "Swing_Setup1 seeds y_vel=$C0 for the first swing frame");
        assertEquals(0xC0, endBoss.getCustomFlag(0x3E),
                "Swing_Setup1 stores max swing velocity $3E=$C0");
        assertEquals(0x10, endBoss.getCustomFlag(0x40),
                "Swing_Setup1 stores swing acceleration $40=$10");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 1,
                "Swing_Setup1 clears $38 bit 0 before routine $08 begins");
    }

    @Test
    void mhzEndBossSwingWaitCallbackStartsDash() {
        int[] lastSfx = {-1};
        ObjectServices services = new StubObjectServices() {
            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 8;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);

        assertEquals(0x0A, endBoss.getState().routine,
                "loc_7609A switches to routine $0A after the routine $08 Swing_UpAndDown/MoveSprite2/Obj_Wait expires");
        assertEquals(0x0300, endBoss.getY(),
                "routine $08 applies Swing_UpAndDown before MoveSprite2; y_vel=$B0 leaves integer y unchanged");
        assertEquals(0xB0, endBoss.getState().yVel,
                "Swing_UpAndDown subtracts acceleration $10 from y_vel=$C0 while $38 bit 0 is clear");
        assertEquals(0x40, endBoss.getCustomFlag(0x38) & 0x40,
                "loc_7609A sets $38 bit 6 for the dash phase");
        assertEquals(0x400, endBoss.getState().xVel,
                "loc_7609A starts the dash with x_vel=$400");
        assertEquals(0x3F, endBoss.getCustomFlag(0x2E),
                "loc_7609A seeds the next Obj_Wait timer $2E=$3F before loc_760C4");
        assertEquals(Sonic3kSfx.DASH.id, lastSfx[0],
                "loc_7609A plays sfx_Dash when the dash starts");
    }

    @Test
    void mhzEndBossDashWaitCallbackAnchorsArenaAndExpandsCameraTarget() {
        Camera camera = new Camera();
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0A;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xB0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x40);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);

        assertEquals(0x0C, endBoss.getState().routine,
                "loc_760C4 switches to routine $0C after the routine $0A Swing_UpAndDown/MoveSprite2/Obj_Wait expires");
        assertEquals(0x4180, endBoss.getX(),
                "loc_760C4 anchors x_pos to $4180 for the arena pass");
        assertEquals(0x02E0, endBoss.getY(),
                "loc_760C4 anchors y_pos to $02E0 for the arena pass");
        assertEquals(0, endBoss.getState().xVel,
                "loc_760C4 clears x_vel after snapping the boss to the arena anchor");
        assertEquals(0xC8, endBoss.getCustomFlag(0x38) & 0xC8,
                "loc_760C4 sets $38 bits 7 and 3 while preserving the dash-phase bit");
        assertEquals(0x45A0, Short.toUnsignedInt(camera.getMaxXTarget()),
                "loc_760C4 stores Camera_stored_max_X_pos=$45A0 and Child6_IncLevX drives the target");
    }

    @Test
    void mhzEndBossCameraApproachThresholdStartsAlternatingDashWaitSameFrame() {
        Camera camera = new Camera();
        camera.setX((short) 0x40A0);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0C;
        endBoss.getState().x = 0x4180;
        endBoss.getState().y = 0x02E0;
        endBoss.getState().xFixed = 0x4180 << 16;
        endBoss.getState().yFixed = 0x02E0 << 16;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0x3F);
        endBoss.setCustomFlag(0x38, 0xC8);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);

        assertEquals(0x0E, endBoss.getState().routine,
                "loc_76120 switches to routine $0E once Camera_X_pos+$E0 catches x_pos");
        assertEquals(0x5F, endBoss.getCustomFlag(0x2E),
                "loc_76136 runs in the same frame and seeds the next wait timer $2E=$5F");
        assertEquals(0x300, endBoss.getState().xVel,
                "loc_76136 toggles $38 bit 6 from set to clear and applies x_vel=$400-$100");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x40,
                "loc_76136 clears $38 bit 6 when it was set before the bchg");
        assertEquals(0xB0, endBoss.getState().yVel,
                "routine $0E still runs the loc_76088 Swing_UpAndDown step before the callback");
        assertEquals(0x02E0, endBoss.getY(),
                "the first $B0 subpixel swing step does not move integer y");
    }

    @Test
    void mhzEndBossKnucklesAlternatingDashUsesRomHigherBaseVelocity() {
        Camera camera = new Camera();
        camera.setX((short) 0x40A0);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return new MhzZoneRuntimeState(1, PlayerCharacter.KNUCKLES);
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0C;
        endBoss.getState().x = 0x4180;
        endBoss.getState().y = 0x02E0;
        endBoss.getState().xFixed = 0x4180 << 16;
        endBoss.getState().yFixed = 0x02E0 << 16;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0x3F);
        endBoss.setCustomFlag(0x38, 0xC8);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);

        assertEquals(0x400, endBoss.getState().xVel,
                "loc_76136 uses base x_vel=$500 for Knuckles, then bchg #6 applies the -$100 phase");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x40,
                "loc_76136 still clears $38 bit 6 when Knuckles enters from the set phase");
    }

    @Test
    void mhzEndBossCameraApproachBeforeThresholdOnlySwingsAndMoves() {
        Camera camera = new Camera();
        camera.setX((short) 0x3F00);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0C;
        endBoss.getState().x = 0x4180;
        endBoss.getState().y = 0x02E0;
        endBoss.getState().xFixed = 0x4180 << 16;
        endBoss.getState().yFixed = 0x02E0 << 16;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0x3F);
        endBoss.setCustomFlag(0x38, 0xC8);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);

        assertEquals(0x0C, endBoss.getState().routine,
                "loc_76106 stays in routine $0C while Camera_X_pos+$E0 is still below x_pos");
        assertEquals(0x3F, endBoss.getCustomFlag(0x2E),
                "loc_76106 does not tick Obj_Wait before the camera threshold branch");
        assertEquals(0xB0, endBoss.getState().yVel,
                "loc_76106 applies Swing_UpAndDown while waiting for the camera");
        assertEquals(0x4180, endBoss.getX(),
                "with x_vel=0 the pre-threshold MoveSprite2 keeps the anchored x_pos");
        assertEquals(0x02E0, endBoss.getY(),
                "the first $B0 subpixel swing step keeps integer y stable");
    }

    @Test
    void mhzEndBossFatalHitEntersRomDefeatDashHandoff() {
        int[] lastSfx = {-1};
        GameStateManager gameState = mock(GameStateManager.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4400, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0E;
        endBoss.getState().hitCount = 1;
        endBoss.setCustomFlag(0x38, 0xC8);

        endBoss.onPlayerAttack(null, null);

        assertEquals(0, endBoss.getState().hitCount,
                "fatal Touch_Enemy_Part2 hit consumes the final collision_property count");
        assertEquals(0x400, endBoss.getState().xVel,
                "loc_76822 starts the post-hit defeat dash with x_vel=$400 for non-Knuckles");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x40,
                "loc_76822 clears $38 bit 6 before the defeat dash");
        assertEquals(0, endBoss.getState().invulnerabilityTimer,
                "the custom defeat handoff cancels the base hit flash timer");
        assertEquals(Sonic3kSfx.BOSS_HIT.id, lastSfx[0],
                "the final hit still plays sfx_BossHit before the defeat handoff");
        verify(gameState).addScore(1000);
    }

    @Test
    void mhzEndBossAcceptedHitClearsAttackingPlayerHorizontalVelocity() {
        ObjectServices services = new StubObjectServices();
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4400, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0E;
        endBoss.getState().hitCount = 2;
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x43F0, (short) 0x02E0);
        player.setXSpeed((short) 0x0600);

        endBoss.onPlayerAttack(player, null);

        assertEquals(1, endBoss.getState().hitCount,
                "sub_76782 consumes one boss hit before starting the invulnerability flash");
        assertEquals(0, player.getXSpeed(),
                "sub_76782 clears x_vel on the player that struck the MHZ end-boss hit child");
    }

    @Test
    void mhzEndBossKnucklesFatalHitUsesRomHigherDefeatDashVelocity() {
        GameStateManager gameState = mock(GameStateManager.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return new MhzZoneRuntimeState(1, PlayerCharacter.KNUCKLES);
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4400, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0E;
        endBoss.getState().hitCount = 1;
        endBoss.setCustomFlag(0x38, 0xC8);

        endBoss.onPlayerAttack(null, null);

        assertEquals(0x500, endBoss.getState().xVel,
                "loc_76822 starts the post-hit defeat dash with x_vel=$500 for Knuckles");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x40,
                "loc_76822 still clears $38 bit 6 before the Knuckles defeat dash");
        verify(gameState).addScore(1000);
    }

    @Test
    void mhzEndBossFatalHitSpawnsRomPlayerWalkoffPrepController() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        List<ObjectInstance> freeSpawned = new ArrayList<>();
        doAnswer(invocation -> {
            freeSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4400, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0E;
        endBoss.getState().hitCount = 1;
        endBoss.setCustomFlag(0x38, 0xC8);

        endBoss.onPlayerAttack(null, null);

        assertEquals(1, freeSpawned.size(),
                "loc_76822 allocates the loc_768B6 Player_1 walkoff-prep controller on the fatal hit");
        ObjectInstance controller = freeSpawned.getFirst();
        assertEquals("MHZEndBossWalkoffPrep", controller.getName(),
                "the helper models ROM loc_768B6 rather than the later ship-carry controller");
        ((AbstractObjectInstance) controller).setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x45F0, (short) 0x0200);
        player.setRolling(true);
        player.setDirection(Direction.LEFT);

        controller.update(0, player);

        assertEquals(0, player.getForcedInputMask(),
                "loc_768B6 returns while _unkFAA9 is still nonzero");
        assertEquals(false, controller.isDestroyed(),
                "loc_768B6 waits for the final-hit handoff flag to clear before deleting itself");

        setPrivateInt(endBoss, "finalHitHandoffFlag", 0);
        controller.update(1, player);

        assertEquals(0x55, getPrivateInt(mhzEvents, "endBossWalkoffPrepEventFlag"),
                "loc_768D2 writes Events_fg_5=$55 when Player_1 is grounded");
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, player.getForcedInputMask(),
                "loc_768FE writes Ctrl_1_logical=RIGHT until Player_1.x_pos reaches $4600");
        assertEquals(Direction.RIGHT, player.getDirection(),
                "loc_768D2 clears Status_Facing so Player_1 faces right");
        assertEquals(false, player.getRolling(),
                "loc_768D2 clears Status_Roll before the forced walk");
        assertEquals(true, player.isControlLocked(),
                "loc_768D2 sets Ctrl_1_locked before forcing the walk");

        player.setCentreX((short) 0x4600);
        player.setXSpeed((short) 0x300);
        player.setYSpeed((short) -0x100);
        player.setGSpeed((short) 0x280);
        controller.update(2, player);

        assertEquals(0x4600, player.getCentreX(),
                "loc_76912 clamps Player_1.x_pos to $4600");
        assertEquals(0, player.getXSpeed(),
                "loc_76912 calls Stop_Object after the $4600 clamp");
        assertEquals(0, player.getYSpeed(),
                "Stop_Object clears y_vel for the walkoff-prep controller");
        assertEquals(0, player.getGSpeed(),
                "Stop_Object clears ground_vel for the walkoff-prep controller");
        assertEquals(0, player.getForcedInputMask(),
                "loc_76912 clears Ctrl_1_logical before deleting the helper");
        assertEquals(true, controller.isDestroyed(),
                "loc_76912 jumps to Delete_Current_Sprite after stopping Player_1");
    }

    @Test
    void mhzEndBossFatalHitStartsRomSubtype20ExplosionController() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        int[] lastSfx = {-1};
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4300, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().routine = 0x0E;
        endBoss.getState().hitCount = 1;
        endBoss.setCustomFlag(0x38, 0xC8);

        endBoss.onPlayerAttack(null, null);
        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);

        assertEquals(Sonic3kSfx.EXPLODE.id, lastSfx[0],
                "loc_76822 creates Child6_CreateBossExplosion with subtype $20 on the fatal hit");
        assertEquals(1, spawned.stream().filter(S3kBossExplosionChild.class::isInstance).count(),
                "CreateBossExp20 emits its first explosion after the ROM three-frame wait");
    }

    @Test
    void mhzEndBossDefeatDashThresholdStartsFadeShakeAndTimerStop() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);

        assertEquals(0x44D0, endBoss.getX(),
                "loc_76164 checks the threshold after Swing_UpAndDown/MoveSprite2 moves x_pos to $44D0");
        verify(objectManager).addDynamicObject(any(SongFadeTransitionInstance.class));
        verify(objectManager, org.mockito.Mockito.times(6)).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        verify(gameState).setScreenShakeActive(true);
        verify(levelState).pauseTimer();
        verifyNoMoreInteractions(objectManager, gameState, levelState);
    }

    @Test
    void mhzEndBossFadeWaitUnderflowStartsRomClimbAwaySetup() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Sonic3kObjectArtProvider artProvider = mock(Sonic3kObjectArtProvider.class);
        ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 1);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);

        verify(objectManager).addDynamicObject(any(SongFadeTransitionInstance.class));
        assertEquals(0x44D0, endBoss.getX(),
                "Wait_FadeToLevelMusic only counts down; loc_761B2 has not moved x_pos");
        assertEquals(0, endBoss.getState().xVel,
                "loc_761B2 clears x_vel before Robotnik rises out of the arena");
        assertEquals(-0x200, endBoss.getState().yVel,
                "loc_761B2 seeds y_vel=-$200 for the climb-away routine");
        assertEquals(0x10, endBoss.getCustomFlag(0x38) & 0x10,
                "loc_761B2 sets $38 bit 4 for the post-defeat flag handoff");
        assertEquals(0x77, endBoss.getCustomFlag(0x2E),
                "Wait_FadeToLevelMusic resets $2E to (2*60)-1 before jumping to loc_761B2");
        assertEquals(2, endBoss.getPriorityBucket(),
                "loc_761B2 changes priority from $280 to $100");
        assertEquals(true, endBoss.isHighPriority(),
                "loc_761B2 sets the art_tile high-priority bit");
        verify(artProvider).ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.EGG_CAPSULE);
        verify(artProvider).ensureBossExplosionArtLoaded();
    }

    @Test
    void mhzEndBossFadeWaitUnderflowSpawnsRomDefeatFragments() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4400);
        camera.setY((short) 0x0200);
        List<ObjectInstance> afterCurrentSpawned = new ArrayList<>();
        doAnswer(invocation -> {
            afterCurrentSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        Sonic3kObjectArtProvider artProvider = mock(Sonic3kObjectArtProvider.class);
        PatternSpriteRenderer renderer = readyRenderer();
        when(artProvider.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(renderer);
        ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 1);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);

        List<ObjectInstance> fragments = afterCurrentSpawned.stream()
                .filter(object -> "MHZEndBossDefeatFragment".equals(object.getName()))
                .toList();
        assertEquals(6, fragments.size(),
                "loc_761B2 creates ChildObjDat_769B0, allocating six loc_766CA flicker fragments");
        int[] expectedPriorityBuckets = {4, 4, 6, 6, 4, 4};
        // Set_IndexedVelocity's d0=8 base combines with CreateChild6_Simple seeding each
        // fragment's raw ROM subtype byte as 0,2,4,6,8,10 (not 0..5): effective stride is
        // 8 + spawnIndex*4 -- Obj_VelocityIndex (ROM 0x0852F4) rows 2-7, ROM-verified via
        // RomOffsetFinder search-rom against the raw ROM bytes.
        int[] expectedXAfterMove = {0x44CE, 0x44D2, 0x44CD, 0x44D3, 0x44CE, 0x44D0};
        int[] expectedYAfterMove = {0x02DE, 0x02DE, 0x02DE, 0x02DE, 0x02DE, 0x02DE};
        for (int i = 0; i < fragments.size(); i++) {
            ObjectInstance fragment = fragments.get(i);
            ((AbstractObjectInstance) fragment).setServices(services);
            assertEquals(0x44D0, fragment.getX(),
                    "CreateChild6_Simple spawns each loc_766CA fragment at the parent x_pos");
            assertEquals(0x02E0, fragment.getY(),
                    "CreateChild6_Simple spawns each loc_766CA fragment at the parent y_pos");
            assertEquals(expectedPriorityBuckets[i], fragment.getPriorityBucket(),
                    "word_766FC supplies the per-subtype loc_766CA priority word");

            fragment.update(3, null);
            assertEquals(expectedXAfterMove[i], fragment.getX(),
                    "Set_IndexedVelocity with d0=8 supplies the loc_766CA x velocity");
            assertEquals(expectedYAfterMove[i], fragment.getY(),
                    "Obj_FlickerMove applies MoveSprite with the ROM y velocity before gravity");
            fragment.appendRenderCommands(new ArrayList<>());
            verify(renderer).drawFrameIndex(0x12 + i, fragment.getX(), fragment.getY(), false, false);
        }
    }

    @Test
    void mhzEndBossClimbPastCameraHandoffSpawnsFixedCapsuleAndClearsBossId() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setY((short) 0x0180);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);

        ObjectInstance capsule = spawned.stream()
                .filter(AbstractS3kUprightEggCapsuleInstance.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("loc_761E8 must allocate Obj_EggCapsule"));
        assertEquals(0x4640, capsule.getX(),
                "loc_761E8 writes the MHZ post-boss Obj_EggCapsule x_pos=$4640");
        assertEquals(0x0320, capsule.getY(),
                "loc_761E8 writes the MHZ post-boss Obj_EggCapsule y_pos=$0320");
        assertEquals(0x20, endBoss.getCustomFlag(0x38) & 0x20,
                "loc_761E8 sets $38 bit 5 after the climb-away camera threshold");
        verify(gameState).setCurrentBossId(0);
    }

    @Test
    void mhzEndBossPostCapsuleHandoffScrollsCameraRightAndPinsAt45A0() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        camera.setFrozen(true);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4600, (short) 0x0200);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x45C0, (short) 0x0200);
        player.setControlLocked(true);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        sidekick.setControlLocked(true);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> player, () -> List.of(sidekick));
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);

        assertEquals(0x459C, Short.toUnsignedInt(camera.getX()),
                "loc_7623C adds 4 to Camera_X_pos while it is still below $45A0");
        assertEquals(1, spawned.stream().filter(AbstractS3kUprightEggCapsuleInstance.class::isInstance).count(),
                "loc_7623C scrolls the camera without allocating another capsule");

        endBoss.update(4, player);

        assertEquals(0x45A0, Short.toUnsignedInt(camera.getX()),
                "loc_7623C clamps Camera_X_pos to $45A0 once the +4 step reaches the target");
        assertEquals(0x45A0, Short.toUnsignedInt(camera.getMinX()),
                "loc_7623C writes Camera_min_X_pos=$45A0 at the scroll endpoint");
        assertEquals(0x45A0, Short.toUnsignedInt(camera.getMaxX()),
                "loc_7623C writes Camera_max_X_pos=$45A0 at the scroll endpoint");
        assertEquals(false, camera.getFrozen(),
                "loc_7623C clears Scroll_lock when the camera reaches $45A0");
        assertEquals(false, player.isControlLocked(),
                "loc_7623C clears Ctrl_1_locked when the camera reaches $45A0");
        assertEquals(0, player.getForcedInputMask(),
                "loc_7623C clears Ctrl_1_logical when the camera reaches $45A0");
        assertEquals(false, sidekick.isControlLocked(),
                "loc_7623C clears Ctrl_2_locked when the camera reaches $45A0");
        assertEquals(1, spawned.stream().filter(AbstractS3kUprightEggCapsuleInstance.class::isInstance).count(),
                "the endpoint transition must not duplicate the fixed egg capsule");
    }

    @Test
    void mhzEndBossCapsuleResultsFlagStartsPostCapsuleEscapeSetup() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        Sonic3kObjectArtProvider artProvider = mock(Sonic3kObjectArtProvider.class);
        ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS, mhzEvents);
        List<ObjectInstance> spawned = new ArrayList<>();
        List<ObjectInstance> afterCurrentSpawned = new ArrayList<>();
        int[] lastMusic = {-1};
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            afterCurrentSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public void playMusic(int musicId) {
                lastMusic[0] = musicId;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);
        endBoss.update(3, null);
        endBoss.update(4, null);
        endBoss.update(5, null);

        assertEquals(0x4560, endBoss.getX(),
                "loc_76270 writes x_pos=Camera_X_pos-$40 after the results flag clears");
        assertEquals(0x01C0, endBoss.getY(),
                "loc_76270 writes y_pos=Camera_Y_pos+$40 after the results flag clears");
        assertEquals(0x1BF, endBoss.getCustomFlag(0x2E),
                "loc_76270 seeds the Robotnik ship wait timer $2E=$1BF");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x20,
                "loc_76270 clears $38 bit 5 after leaving the capsule wait");
        assertEquals(0x5000, Short.toUnsignedInt(camera.getMaxXTarget()),
                "loc_76270 writes Camera_stored_max_X_pos=$5000 for the post-capsule escape");
        assertEquals(0x17, lastMusic[0],
                "loc_76270 restores the level music after the capsule results finish");
        assertEquals(true, mhzEvents.isShipTransitionFlagSet(),
                "loc_76270 writes Events_fg_4=$55 to trigger the MHZ2 ship sequence");
        assertEquals(1, spawned.stream().filter(AbstractS3kUprightEggCapsuleInstance.class::isInstance).count(),
                "loc_76270 reuses the existing capsule and must not allocate another one");
        ObjectInstance robotnikHead = afterCurrentSpawned.stream()
                .filter(object -> "MHZEndBossRobotnikHead".equals(object.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("loc_76270 recreates Obj_RobotnikHead4 from ChildObjDat_7699C"));
        assertEquals(0x4560, robotnikHead.getX(),
                "ChildObjDat_7699C keeps the post-capsule Robotnik head at the ship x_pos");
        assertEquals(0x01A4, robotnikHead.getY(),
                "ChildObjDat_7699C offsets the post-capsule Robotnik head by -$1C y");
        verify(artProvider).ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
    }

    @Test
    void mhzEndBossCapsuleResultsFlagRelocksNativePlayersWithUpInput() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4600, (short) 0x0200);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x45C0, (short) 0x0200);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> player, () -> List.of(sidekick));
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        for (int frame = 0; frame <= 5; frame++) {
            endBoss.update(frame, player);
        }

        assertEquals(true, player.isControlLocked(),
                "loc_76270 sets Ctrl_1_locked immediately after Restore_PlayerControl");
        assertEquals(AbstractPlayableSprite.INPUT_UP, player.getForcedInputMask(),
                "loc_76270 writes button_up_mask to Ctrl_1_logical");
        assertEquals(true, sidekick.isControlLocked(),
                "loc_76270 sets Ctrl_2_locked immediately after Restore_PlayerControl2");
        assertEquals(AbstractPlayableSprite.INPUT_UP, sidekick.getForcedInputMask(),
                "loc_76270 writes button_up_mask to Ctrl_2_logical");
    }

    @Test
    void mhzEndBossRobotnikShipTimerUnderflowStartsEscapeAndFlameChild() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        List<ObjectInstance> freeSpawned = new ArrayList<>();
        List<ObjectInstance> afterCurrentSpawned = new ArrayList<>();
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        doAnswer(invocation -> {
            freeSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            afterCurrentSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);
        endBoss.update(3, null);
        endBoss.update(4, null);
        endBoss.update(5, null);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, null);

        assertEquals(0x4560, endBoss.getX(),
                "loc_76318 only starts the escape velocity; it does not move x_pos in the transition frame");
        assertEquals(0x400, endBoss.getState().xVel,
                "loc_76318 seeds x_vel=$400 for Robotnik's ship escape");
        assertEquals(0, endBoss.getState().yVel,
                "loc_76318 clears y_vel before the horizontal ship escape");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x10,
                "loc_76318 clears $38 bit 4 before the flame child tracks the ship");
        assertEquals(1, endBoss.getState().renderFlags & 1,
                "loc_76318 sets render_flags bit 0 for the escape-facing orientation");
        assertEquals(1, freeSpawned.stream().filter(AbstractS3kUprightEggCapsuleInstance.class::isInstance).count(),
                "the loc_76318 transition must not duplicate the fixed egg capsule");
        assertEquals(1, afterCurrentSpawned.stream()
                        .filter(instance -> "MHZEndBossRobotnikShipFlame".equals(instance.getName()))
                        .count(),
                "loc_76318 creates Child1_MakeRoboShipFlame after the current boss slot");
    }

    @Test
    void mhzEndBossRobotnikShipEscapeMovesWithCustomGravityAndSetsCameraFlag() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x45C0, (short) 0x0200);
        List<ObjectInstance> freeSpawned = new ArrayList<>();
        int[] lastSfx = {-1};
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        doAnswer(invocation -> {
            freeSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> null, () -> List.of(sidekick));
            }

            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);
        endBoss.update(3, null);
        endBoss.update(4, null);
        endBoss.update(5, null);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, null);
        camera.setY((short) 0x0200);
        endBoss.update(7, null);

        assertEquals(0x4564, endBoss.getX(),
                "loc_76356 applies MoveSprite_CustomGravity after loc_76318 seeds x_vel=$400");
        assertEquals(0x01C0, endBoss.getY(),
                "loc_76356 uses the old y_vel before custom gravity; y_vel=0 keeps y_pos stable");
        assertEquals(0, endBoss.getState().yVel,
                "when Camera_X_pos+$80 has caught x_pos, loc_76356 uses d1=0 custom gravity");
        assertEquals(0x20, endBoss.getCustomFlag(0x38) & 0x20,
                "loc_76356 sets $38 bit 5 once Camera_Y_pos-$40 is at or below the ship y_pos");
        assertEquals(1, freeSpawned.stream()
                        .filter(instance -> "MHZEndBossSidekickLock".equals(instance.getName()))
                        .count(),
                "loc_76356 allocates loc_863C0 when the ship reaches the camera-Y handoff threshold");
        ObjectInstance sidekickLock = freeSpawned.stream()
                .filter(instance -> "MHZEndBossSidekickLock".equals(instance.getName()))
                .findFirst()
                .orElseThrow();
        ((AbstractObjectInstance) sidekickLock).setServices(services);
        sidekick.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
        sidekickLock.update(8, null);
        assertEquals(true, sidekick.isControlLocked(),
                "loc_863C0 sets Ctrl_2_locked while Player_2 exists");
        assertEquals(0, sidekick.getForcedInputMask(),
                "loc_863D6 clears Ctrl_2_logical every frame while Ctrl_2_locked remains set");
        sidekick.setControlLocked(false);
        sidekick.setForcedInputMask(AbstractPlayableSprite.INPUT_LEFT);
        sidekickLock.update(9, null);
        assertEquals(0, sidekick.getForcedInputMask(),
                "loc_863E2 clears Ctrl_2 when the P2 lock is released");
        assertEquals(true, sidekickLock.isDestroyed(),
                "loc_863E6 deletes the helper after releasing P2 input");
        assertEquals(Sonic3kSfx.ROBOTNIK_SIREN.id, lastSfx[0],
                "loc_76356 calls Play_SFX_Continuous with sfx_RobotnikSiren every escape frame");
    }

    @Test
    void mhzEndBossRobotnikShipEscapeSubtractsLevelRepeatOffsetAfterMovement() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);
        endBoss.update(3, null);
        endBoss.update(4, null);
        endBoss.update(5, null);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, null);
        setPrivateInt(mhzEvents, "levelRepeatOffset", 0x0200);
        camera.setY((short) 0x0180);

        endBoss.update(7, null);

        assertEquals(0x4364, endBoss.getX(),
                "loc_763A4 subtracts Level_repeat_offset from x_pos after MoveSprite_CustomGravity");
    }

    @Test
    void mhzEndBossActiveCoreSubtractsLevelRepeatOffsetAfterRoutineDispatch() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        Camera camera = new Camera();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x40C0, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.setCustomFlag(0x100, 1);
        setPrivateInt(mhzEvents, "levelRepeatOffset", 0x0200);
        endBoss.update(1, null);

        assertEquals(0x3F80, endBoss.getX(),
                "loc_75FD4 subtracts Level_repeat_offset from x_pos after the active routine dispatch");
    }

    @Test
    void mhzEndBossRobotnikShipEscapeAppliesNegativeGravityBeforeCameraCatchesShip() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, null);
        endBoss.update(1, null);
        endBoss.update(2, null);
        endBoss.update(3, null);
        endBoss.update(4, null);
        endBoss.update(5, null);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, null);
        camera.setX((short) 0x44D0);
        endBoss.update(7, null);

        assertEquals(0x4564, endBoss.getX(),
                "loc_76356 still applies x_vel=$400 while Camera_X_pos+$80 is behind the ship");
        assertEquals(0x01C0, endBoss.getY(),
                "MoveSprite_CustomGravity uses the old y_vel before the custom gravity update");
        assertEquals(-0x10, endBoss.getState().yVel,
                "when Camera_X_pos+$80 is below x_pos, loc_76356 applies d1=-$10 to y_vel");
        assertEquals(0, endBoss.getCustomFlag(0x38) & 0x20,
                "this branch stays in loc_76356 while Camera_Y_pos-$40 is still above the ship threshold");
    }

    @Test
    void mhzEndBossRobotnikShipCameraFlagStopsPlayerAtWalkoffThreshold() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        player.setXSpeed((short) 0x300);
        player.setYSpeed((short) -0x200);
        player.setGSpeed((short) 0x280);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);

        assertEquals(0, player.getXSpeed(),
                "loc_763C4 calls Stop_Object when Player_1.x_pos reaches $4778");
        assertEquals(0, player.getYSpeed(),
                "Stop_Object clears y_vel during the MHZ2 walkoff handoff");
        assertEquals(0, player.getGSpeed(),
                "Stop_Object clears ground_vel during the MHZ2 walkoff handoff");
        assertEquals(0, player.getForcedInputMask(),
                "loc_763C4 clears Ctrl_1_logical before stopping Player_1");
    }

    @Test
    void mhzEndBossWalkoffStopStateForcesUpWhileWaitingForLaunchTrigger() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        player.setXSpeed((short) 0x300);
        player.setYSpeed((short) -0x200);
        player.setGSpeed((short) 0x280);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);
        endBoss.update(9, player);

        assertEquals(AbstractPlayableSprite.INPUT_UP, player.getForcedInputMask(),
                "loc_763D8 writes Ctrl_1_logical=UP while waiting for _unkFAA9");
        assertEquals(0, player.getXSpeed(),
                "loc_763D8 only forces input while waiting; Stop_Object velocity remains cleared");
        assertEquals(0, player.getYSpeed(),
                "loc_763D8 does not launch the player until _unkFAA9 becomes nonzero");
    }

    @Test
    void mhzEndBossWalkoffStopStateLaunchesPlayerWhenShipSignalFlagSet() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        setPrivateInt(mhzEvents, "shipControllerSignalFlag", 1);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);
        endBoss.update(9, player);

        assertEquals(AbstractPlayableSprite.INPUT_JUMP, player.getForcedInputMask(),
                "loc_763D8 writes Ctrl_1_logical=C when _unkFAA9 is nonzero");
        assertEquals(0x200, player.getXSpeed(),
                "loc_763D8 launches Player_1 forward with x_vel=$200");
        assertEquals(0, player.getYSpeed(),
                "loc_763D8 leaves y_vel unchanged until player physics applies the jump");
    }

    @Test
    void mhzEndBossWalkoffLaunchGrabsPlayerWhenVerticalVelocityTurnsNonNegative() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        setPrivateInt(mhzEvents, "shipControllerSignalFlag", 1);
        int[] lastSfx = {-1};
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }

            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        player.setDirection(Direction.RIGHT);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);
        endBoss.update(9, player);
        player.setYSpeed((short) -1);
        lastSfx[0] = -1;

        endBoss.update(10, player);

        assertEquals(AbstractPlayableSprite.INPUT_JUMP, player.getForcedInputMask(),
                "loc_76404 keeps C held while y_vel is still negative");
        assertEquals(0x200, player.getXSpeed(),
                "loc_76404 returns without Stop_Object while Player_1.y_vel is negative");
        assertEquals(-1, lastSfx[0],
                "loc_76404 must not play sfx_Grab before y_vel becomes non-negative");

        player.setYSpeed((short) 0);
        endBoss.update(11, player);

        assertEquals(0, player.getXSpeed(),
                "loc_76404 calls Stop_Object when Player_1.y_vel is non-negative");
        assertEquals(0, player.getYSpeed(),
                "Stop_Object clears y_vel at the MHZ2 grab handoff");
        assertEquals(0, player.getGSpeed(),
                "Stop_Object clears ground_vel at the MHZ2 grab handoff");
        assertEquals(AbstractPlayableSprite.INPUT_JUMP, player.getForcedInputMask(),
                "loc_76404 writes Ctrl_1_logical=C on the grab frame");
        assertEquals(Sonic3kAnimationIds.HANG.id(), player.getAnimationId(),
                "loc_76404 writes anim=$11 for the carried pose");
        assertEquals(Direction.LEFT, player.getDirection(),
                "loc_76404 sets Status_Facing, which faces Player_1 left");
        assertEquals(Sonic3kSfx.GRAB.id, lastSfx[0],
                "loc_76404 plays sfx_Grab when the carried pose begins");
        assertEquals(0x5E, endBoss.getCustomFlag(0x2E),
                "loc_76404 seeds $2E=$5F and immediately falls through to loc_76456's decrement");
        assertEquals(true, player.isObjectControlled(),
                "loc_76404 writes object_control=$81, enabling full object control");
        assertEquals(ObjectControlState.nativeBit7FullControl().objectControlSuppressesMovement(),
                player.isObjectControlSuppressesMovement(),
                "object_control=$81 suppresses normal player movement");
    }

    @Test
    void mhzEndBossGrabHandoffSpawnsPlayerTwoCarryChild() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        setPrivateInt(mhzEvents, "shipControllerSignalFlag", 1);
        List<ObjectInstance> freeSpawned = new ArrayList<>();
        doAnswer(invocation -> {
            freeSpawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x4700, (short) 0x01E0);
        sidekick.setCpuControlled(true);
        sidekick.setDirection(Direction.LEFT);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> player, () -> List.of(sidekick));
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);
        endBoss.update(9, player);
        int spawnedBeforeGrab = freeSpawned.size();
        player.setYSpeed((short) 0);

        endBoss.update(10, player);

        assertEquals(spawnedBeforeGrab + 1, freeSpawned.size(),
                "loc_76404 allocates the Player_2 carry controller after sfx_Grab");
        ObjectInstance carryController = freeSpawned.getLast();
        ((AbstractObjectInstance) carryController).setServices(services);
        carryController.update(11, player);

        assertEquals(true, sidekick.isObjectControlled(),
                "loc_7646E writes object_control=$81 to Player_2");
        assertEquals(ObjectControlState.nativeBit7FullControl().objectControlSuppressesMovement(),
                sidekick.isObjectControlSuppressesMovement(),
                "Player_2 object_control=$81 suppresses normal movement");
        assertEquals(Sonic3kAnimationIds.FLY.id(), sidekick.getAnimationId(),
                "loc_7646E writes anim=$20 for Tails flight");
        assertEquals(Direction.RIGHT, sidekick.getDirection(),
                "loc_7646E clears Status_Facing, so Player_2 faces right");
        assertEquals(0x4700, sidekick.getCentreX(),
                "loc_7646E only initializes Player_2; movement starts at loc_76492 on the next child update");
        assertEquals(0x01E0, sidekick.getCentreY(),
                "loc_7646E does not move Player_2 on the initialization frame");

        carryController.update(12, player);

        assertEquals(0x4701, sidekick.getCentreX(),
                "loc_76492 adds 1 to Player_2.x_pos every frame");
        assertEquals(0x01DF, sidekick.getCentreY(),
                "loc_76492 subtracts 1 from Player_2.y_pos every frame");
    }

    @Test
    void mhzEndBossGrabWaitTimerRequestsFlyingBatteryAndDeletesBoss() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        Sonic3kMHZEvents mhzEvents = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, mhzEvents);
        setPrivateInt(mhzEvents, "shipControllerSignalFlag", 1);
        int[] requestedZone = {-1};
        int[] requestedAct = {-1};
        boolean[] deactivateLevelNow = {false};
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return runtimeState;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }

            @Override
            public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNowValue) {
                requestedZone[0] = zone;
                requestedAct[0] = act;
                deactivateLevelNow[0] = deactivateLevelNowValue;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4778, (short) 0x0200);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);
        endBoss.update(9, player);
        player.setYSpeed((short) 0);
        endBoss.update(10, player);
        endBoss.setCustomFlag(0x2E, 0);

        endBoss.update(11, player);

        assertEquals(Sonic3kZoneIds.ZONE_FBZ, requestedZone[0],
                "loc_7645E calls StartNewLevel #$0400, which routes to FBZ Act 1");
        assertEquals(0, requestedAct[0],
                "StartNewLevel #$0400 uses act 0");
        assertEquals(true, deactivateLevelNow[0],
                "StartNewLevel from the ending cutscene should deactivate the current level immediately");
        assertEquals(true, endBoss.isDestroyed(),
                "loc_7645E jumps to Delete_Current_Sprite after requesting the level transition");
    }

    @Test
    void mhzEndBossRobotnikShipCameraFlagForcesRightUntilWalkoffThreshold() {
        ObjectManager objectManager = mock(ObjectManager.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        Camera camera = new Camera();
        camera.setX((short) 0x4598);
        camera.setY((short) 0x0180);
        when(gameState.isEndOfLevelFlag()).thenReturn(true);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public LevelState levelGamestate() {
                return levelState;
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public int getCurrentLevelMusicId() {
                return 0x17;
            }
        };
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x440C, 0x02E0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4777, (short) 0x0200);
        endBoss.setServices(services);
        endBoss.getState().defeated = true;
        endBoss.getState().xVel = 0x400;
        endBoss.getState().yVel = 0xC0;
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.setCustomFlag(0x38, 0x88);
        endBoss.setCustomFlag(0x3E, 0xC0);
        endBoss.setCustomFlag(0x40, 0x10);

        endBoss.update(0, player);
        endBoss.update(1, player);
        endBoss.update(2, player);
        endBoss.update(3, player);
        endBoss.update(4, player);
        endBoss.update(5, player);
        endBoss.setCustomFlag(0x2E, 0);
        endBoss.update(6, player);
        camera.setY((short) 0x0200);
        endBoss.update(7, player);
        endBoss.update(8, player);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, player.getForcedInputMask(),
                "loc_763B2 writes Ctrl_1_logical=RIGHT while Player_1.x_pos is still below $4778");
    }

    @Test
    void mhzMinibossInitializesFromCameraOnFirstManagedUpdate() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);

        assertEquals(0x2E10, miniboss.getX(),
                "loc_75220 writes x_pos = Camera_X_pos + $110");
        assertEquals(0x0488, miniboss.getY(),
                "loc_75220 writes y_pos = Camera_Y_pos - $78");
        assertEquals(0x100, miniboss.getState().yVel,
                "loc_75220 starts the body falling with y_vel=$100");
        assertEquals(0x97, miniboss.getCustomFlag(0x2E),
                "loc_75220 seeds timer $2E with $97");
        assertEquals(5, miniboss.getCustomFlag(0x42),
                "loc_75220 seeds scratch byte $42 with 5");
    }

    @Test
    void mhzMinibossInitializationSetsMhzBossFlag() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        Sonic3kLevelEventManager events = mock(Sonic3kLevelEventManager.class);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public com.openggf.game.LevelEventProvider levelEventProvider() {
                return events;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);

        verify(events).setBossFlag(true);
    }

    @Test
    void mhzMinibossSetupSpawnsAndRendersRomFlameChildren() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);

        List<MhzMinibossFlameInstance> flames = spawned.stream()
                .filter(MhzMinibossFlameInstance.class::isInstance)
                .map(MhzMinibossFlameInstance.class::cast)
                .toList();
        assertEquals(2, flames.size(),
                "ChildObjDat_75E84 creates two loc_757C0 flame child sprites during Obj_MHZMiniboss setup");

        flames.forEach(flame -> {
            flame.setServices(services);
            flame.update(1, null);
            flame.appendRenderCommands(new ArrayList<>());
        });

        verify(renderer).drawFrameIndex(0x16, 0x2E1B, 0x04A4, false, false);
        verify(renderer).drawFrameIndex(0x16, 0x2E11, 0x04A4, false, false);
    }

    @Test
    void mhzMinibossAppliesLevelRepeatOffsetToLivePositionLikeRomPostDispatch() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x4308, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));

        miniboss.applyLevelRepeatOffset(-0x0200, 0);

        assertEquals(0x4108, miniboss.getX(),
                "Obj_MHZMiniboss subtracts Level_repeat_offset from x_pos after dispatch");
        assertEquals(0x0520, miniboss.getY(),
                "MHZ miniboss level repeat should not change y_pos");
        assertEquals(0x4108 << 16, miniboss.getState().xFixed,
                "fixed-point x position must stay aligned with the wrapped x_pos");
        assertEquals(0x0520 << 16, miniboss.getState().yFixed,
                "fixed-point y position should be unchanged for a horizontal loop wrap");
    }

    @Test
    void mhzMinibossInitialWaitPhaseMovesWithRomYVelocityAndCountsDown() {
        MhzMinibossInstance miniboss = managedMinibossAtCamera(0x2D00, 0x0500);

        miniboss.update(0, null);
        miniboss.update(1, null);

        assertEquals(0x0489, miniboss.getY(),
                "loc_752D4 applies MoveSprite2 with y_vel=$100 after the init frame");
        assertEquals(0x96, miniboss.getCustomFlag(0x2E),
                "Obj_Wait decrements the initial $97 timer during the first wait frame");
        assertEquals(2, miniboss.getState().routine,
                "SetUp_ObjAttributes advances the init routine to the wait/move routine");
    }

    @Test
    void mhzMinibossInitialWaitCallbackSeedsFirstSwingState() {
        MhzMinibossInstance miniboss = managedMinibossAtCamera(0x2D00, 0x0500);

        miniboss.update(0, null);
        for (int frame = 1; frame <= 0x98; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(4, miniboss.getState().routine,
                "Obj_Wait jumps to loc_752E6 when timer $2E becomes negative");
        assertEquals(0x0520, miniboss.getY(),
                "loc_752D4 applies MoveSprite2 on the callback frame before loc_752E6 runs");
        assertEquals(0x80, miniboss.getState().yVel,
                "loc_75302 seeds the first swing with y_vel=$80");
        assertEquals(0x80, miniboss.getCustomFlag(0x3E),
                "loc_75302 seeds maximum swing velocity $3E=$80");
        assertEquals(0x10, miniboss.getCustomFlag(0x40),
                "loc_75302 seeds swing acceleration $40=$10");
        assertEquals(3, miniboss.getCustomFlag(0x39),
                "loc_752E6 seeds the first Swing_UpAndDown_Count counter");
        assertEquals(0x10, miniboss.getCustomFlag(0x3A),
                "loc_752E6 stores x_pos+1 in scratch byte $3A");
        assertEquals(0x0520, miniboss.getCustomFlag(0x3C),
                "loc_752E6 stores the callback y_pos in scratch word $3C");
    }

    @Test
    void mhzMinibossFirstSwingCountTransitionsIntoHorizontalDashSetup() {
        MhzMinibossInstance miniboss = managedMinibossAtCamera(0x2D00, 0x0500);

        miniboss.update(0, null);
        for (int frame = 1; frame <= 0x98; frame++) {
            miniboss.update(frame, null);
        }
        for (int frame = 0x99; frame <= 0xD5; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(6, miniboss.getState().routine,
                "loc_7531C transitions to loc_75330 after four Swing_UpAndDown_Count peaks");
        assertEquals(0x0520, miniboss.getY(),
                "loc_75330 snaps y_pos back to the stored hover center $3C");
        assertEquals(0x400, miniboss.getState().xVel,
                "loc_75330 starts the horizontal dash with x_vel=$400");
        assertEquals(0, miniboss.getState().yVel,
                "loc_75330 clears y_vel before the horizontal dash wait");
        assertEquals(0x1F, miniboss.getCustomFlag(0x2E),
                "loc_75330 seeds the horizontal dash wait timer $2E=$1F");
    }

    @Test
    void mhzMinibossHorizontalDashWaitCallbackSeedsOffscreenDashWait() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(renderer);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        miniboss.update(0, null);
        for (int frame = 1; frame <= 0x98; frame++) {
            miniboss.update(frame, null);
        }
        for (int frame = 0x99; frame <= 0xD5; frame++) {
            miniboss.update(frame, null);
        }
        for (int frame = 0xD6; frame <= 0xF5; frame++) {
            miniboss.update(frame, null);
        }
        miniboss.appendRenderCommands(new ArrayList<>());

        assertEquals(8, miniboss.getState().routine,
                "routine 6 reuses loc_752D4 and jumps through $34 to loc_75356 when $2E expires");
        assertEquals(0x2E90, miniboss.getX(),
                "loc_752D4 moves at x_vel=$400 for the full $1F wait before loc_75356 runs");
        assertEquals(0x4F, miniboss.getCustomFlag(0x2E),
                "loc_75356 seeds the offscreen dash wait timer $2E=$4F");
        assertEquals(0x6000, camera.getMaxXTarget() & 0xFFFF,
                "loc_75356 writes Camera_target_max_X_pos=$6000");
        verify(renderer).isReady();
        verify(renderer).drawFrameIndex(5, 0x2E90, 0x0520, false, false);
        verifyNoMoreInteractions(renderer);
    }

    @Test
    void mhzMinibossOffscreenDashWaitOnlyRunsWhenRenderedAndThenStartsDecelerationDash() {
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(renderer);
        MhzMinibossInstance miniboss = managedMinibossWithRenderManager(0x2D00, 0x0500, renderManager);

        advanceMinibossToOffscreenDashWait(miniboss);
        miniboss.update(0xF6, null);
        assertEquals(8, miniboss.getState().routine,
                "loc_75392 returns without moving or waiting until render_flags bit 7 is set");
        assertEquals(0x2E90, miniboss.getX());
        assertEquals(0x4F, miniboss.getCustomFlag(0x2E));

        miniboss.getState().renderFlags = 0x80;
        for (int frame = 0xF7; frame <= 0x146; frame++) {
            miniboss.update(frame, null);
        }
        miniboss.appendRenderCommands(new ArrayList<>());

        assertEquals(0xA, miniboss.getState().routine,
                "loc_75392 jumps through $34 to loc_753A4 after the onscreen $4F wait");
        assertEquals(0x2FD2, miniboss.getX(),
                "loc_753A4 adds 2 pixels after the final x_vel=$400 wait frame");
        verify(renderer).isReady();
        verify(renderer).drawFrameIndex(4, 0x2FD2, 0x0520, false, false);
        verifyNoMoreInteractions(renderer);
    }

    @Test
    void mhzMinibossDecelerationDashReturnsToSwingSetupWhenVelocityReachesZero() {
        MhzMinibossInstance miniboss = managedMinibossAtCamera(0x2D00, 0x0500);

        advanceMinibossToDecelerationDash(miniboss);
        for (int frame = 0x147; frame <= 0x166; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x0C, miniboss.getState().routine,
                "loc_753B6 switches to routine $C when x_vel reaches zero");
        assertEquals(0x3010, miniboss.getX(),
                "loc_753CA restores x_pos+1 from scratch byte $3A after the deceleration dash");
        assertEquals(0, miniboss.getState().xVel,
                "loc_753CA clears x_vel before re-entering the swing setup");
        assertEquals(0x80, miniboss.getState().yVel,
                "loc_75302 reseeds y_vel=$80 for the next swing pass");
        assertEquals(0x80, miniboss.getCustomFlag(0x3E),
                "loc_75302 reseeds maximum swing velocity $3E=$80");
        assertEquals(0x10, miniboss.getCustomFlag(0x40),
                "loc_75302 reseeds swing acceleration $40=$10");
    }

    @Test
    void mhzMinibossCameraApproachSwingStartsChoppingWhenNearCamera() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        advanceMinibossToCameraApproachSwing(miniboss);
        camera.setX((short) 0x2F00);
        miniboss.update(0x167, null);

        assertEquals(0x0E, miniboss.getState().routine,
                "loc_753DE switches to routine $E when x_pos-Camera_X_pos <= $110");
        assertEquals(0x3010, miniboss.getX(),
                "loc_753DE does not change X before the camera threshold branch");
        assertEquals(0x0520, miniboss.getY(),
                "the first Swing_UpAndDown/MoveSprite2 pass keeps integer Y stable at y_vel=$70");
        assertEquals(0x70, miniboss.getState().yVel,
                "Swing_UpAndDown applies the upward acceleration before loc_753FA");
    }

    @Test
    void mhzMinibossChoppingAnimationDecrementsTreeCounterAndCallbacksToSwingPeakWait() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(renderer);
        int[] lastSfx = {-1};
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }

            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);

        advanceMinibossToCameraApproachSwing(miniboss);
        camera.setX((short) 0x2F00);
        miniboss.update(0x167, null);
        for (int frame = 0x168; frame < 0x168 + 50; frame++) {
            miniboss.update(frame, null);
        }
        miniboss.appendRenderCommands(new ArrayList<>());

        assertEquals(0x0E, miniboss.getState().routine);
        assertEquals(4, miniboss.getCustomFlag(0x42),
                "loc_7541A decrements $42 when Animate_RawMultiDelay reaches anim_frame=$A");
        assertEquals(Sonic3kSfx.CHOP_TREE.id, lastSfx[0],
                "loc_7541A plays sfx_ChopTree on the chop frame");
        verify(renderer).isReady();
        verify(renderer).drawFrameIndex(0x0D, miniboss.getX(), miniboss.getY(), false, false);

        for (int frame = 0x168 + 50; frame < 0x168 + 159; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x10, miniboss.getState().routine,
                "byte_75EBB terminator $F4 calls loc_75444 and switches to routine $10");
        verifyNoMoreInteractions(renderer);
    }

    @Test
    void mhzMinibossSwingPeakWaitStartsNonFinalFallRecovery() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x10;
        miniboss.getState().yVel = 0x70;
        miniboss.setCustomFlag(0x38, 1);
        miniboss.setCustomFlag(0x3E, 0x80);
        miniboss.setCustomFlag(0x40, 0x10);
        miniboss.setCustomFlag(0x42, 4);

        miniboss.update(0, null);

        assertEquals(0x12, miniboss.getState().routine,
                "loc_7544C enters routine $12 when Swing_UpAndDown reports a peak");
        assertEquals(0x200, miniboss.getState().yVel,
                "loc_7545C starts the downward fall with y_vel=$200");
        assertEquals(0x0F, miniboss.getCustomFlag(0x2E),
                "non-final chops seed Obj_Wait timer $2E=$F before returning to routine $E");
    }

    @Test
    void mhzMinibossFallRecoveryWaitReturnsToChoppingForNonFinalCounter() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x12;
        miniboss.getState().yVel = 0x200;
        miniboss.setCustomFlag(0x2E, 0x0F);
        miniboss.setCustomFlag(0x42, 4);

        for (int frame = 0; frame <= 0x0F; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x0E, miniboss.getState().routine,
                "loc_754AC returns non-final routine $12 waits to the chopping animation loop");
        assertEquals(0x0540, miniboss.getY(),
                "loc_754A0 applies y_vel=$200 for every Obj_Wait frame before the callback");
        assertEquals(0x80, miniboss.getState().yVel,
                "loc_75302 restores swing y_vel after the wait callback");
        assertEquals(0x80, miniboss.getCustomFlag(0x3E),
                "loc_75302 restores the swing velocity cap");
        assertEquals(0x10, miniboss.getCustomFlag(0x40),
                "loc_75302 restores swing acceleration");
    }

    @Test
    void mhzMinibossFallRecoveryWaitStartsFinalStuckAnimationForNonKnuckles() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x12;
        miniboss.getState().yVel = 0x200;
        miniboss.setCustomFlag(0x2E, 7);
        miniboss.setCustomFlag(0x42, 1);

        for (int frame = 0; frame <= 7; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x14, miniboss.getState().routine,
                "loc_754BE starts the final non-Knuckles stuck animation after the last chop");
        assertEquals(0x0530, miniboss.getY(),
                "loc_754A0 still applies y_vel=$200 during the final 8-frame Obj_Wait");
        assertEquals(0x200, miniboss.getState().yVel,
                "loc_754BE only switches animation state and leaves the downward velocity intact");
    }

    @Test
    void mhzMinibossFallRecoveryWaitStartsKnucklesEscapeSwingForKnuckles() {
        ObjectServices services = new StubObjectServices() {
            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return new MhzZoneRuntimeState(0, PlayerCharacter.KNUCKLES);
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.getState().routine = 0x12;
        miniboss.getState().yVel = 0x200;
        miniboss.setCustomFlag(0x2E, 7);
        miniboss.setCustomFlag(0x38, 1);
        miniboss.setCustomFlag(0x42, 1);

        for (int frame = 0; frame <= 7; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x20, miniboss.getState().routine,
                "loc_75480 routes Knuckles to loc_754D6/routine $20 instead of the stuck animation");
        assertEquals(0x80, miniboss.getState().yVel,
                "loc_754D6 falls through loc_75302 and seeds y_vel=$80 for the escape swing");
        assertEquals(0x80, miniboss.getCustomFlag(0x3E),
                "loc_75302 seeds maximum swing velocity $3E=$80 for the Knuckles escape branch");
        assertEquals(0x10, miniboss.getCustomFlag(0x40),
                "loc_75302 seeds swing acceleration $40=$10 for the Knuckles escape branch");
        assertEquals(0, miniboss.getCustomFlag(0x38) & 1,
                "loc_75302 clears bit 0 of $38 before routine $20 starts swinging");
    }

    @Test
    void mhzMinibossFinalStuckAnimationShiftsAndPlaysStuckSfx() {
        int[] lastSfx = {-1};
        ObjectServices services = new StubObjectServices() {
            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.getState().routine = 0x14;

        for (int frame = 0; frame <= 101; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x2FF0, miniboss.getX(),
                "loc_754E0 subtracts $20 from x_pos when byte_75ED0 reaches anim_frame=$C");
        assertEquals(Sonic3kSfx.CHOP_STUCK.id, lastSfx[0],
                "loc_754E0 plays sfx_ChopStuck when byte_75ED0 reaches mapping_frame=$12");
    }

    @Test
    void mhzMinibossFinalLaunchUsesMoveSpriteGravityOrder() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x16;
        miniboss.getState().xVel = -0x400;
        miniboss.getState().yVel = -0x400;

        miniboss.update(0, null);

        assertEquals(0x16, miniboss.getState().routine,
                "loc_75532 stays in routine $16 while y_vel remains negative");
        assertEquals(0x300C, miniboss.getX(),
                "MoveSprite applies the old x_vel before any later routine changes");
        assertEquals(0x051C, miniboss.getY(),
                "MoveSprite applies the old y_vel before adding gravity");
        assertEquals(-0x3C8, miniboss.getState().yVel,
                "MoveSprite adds gravity $38 after using the previous y_vel for movement");
    }

    @Test
    void mhzMinibossFinalLaunchFloorContactStartsBounceThresholdRoutine() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x16;
        miniboss.getState().xVel = -0x400;
        miniboss.getState().yVel = 0;

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-1, (byte) 0, 0));

            miniboss.update(0, null);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x300C, 0x0520, 0x0F));
        }
        assertEquals(0x18, miniboss.getState().routine,
                "loc_75550 switches to routine $18 when ObjCheckFloorDist returns a negative distance");
        assertEquals(-0x200, miniboss.getState().xVel,
                "loc_75550 seeds x_vel=-$200 for the final bounce threshold phase");
        assertEquals(-0x300, miniboss.getState().yVel,
                "loc_75550 seeds y_vel=-$300 for the final bounce threshold phase");
    }

    @Test
    void mhzMinibossFinalBounceThresholdStartsRoutine1A() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x18;
        miniboss.getState().xVel = -0x200;
        miniboss.getState().yVel = 0xC8;
        miniboss.setCustomFlag(0x38, 0x40);

        miniboss.update(0, null);

        assertEquals(0x1A, miniboss.getState().routine,
                "loc_75564 switches to routine $1A once post-gravity y_vel reaches $100");
        assertEquals(0x300E, miniboss.getX(),
                "loc_75564 uses MoveSprite before applying the routine $1A velocity reset");
        assertEquals(0x0520, miniboss.getY(),
                "old y_vel=$C8 contributes no whole-pixel movement before gravity crosses the threshold");
        assertEquals(0x100, miniboss.getState().xVel,
                "loc_75578 seeds x_vel=$100 for the next bounce");
        assertEquals(-0x400, miniboss.getState().yVel,
                "loc_75578 seeds y_vel=-$400 for the next bounce");
        assertEquals(0x10, miniboss.getCustomFlag(0x2E),
                "loc_75578 seeds Obj_Wait timer $2E=$10");
        assertEquals(0, miniboss.getCustomFlag(0x38) & 0x40,
                "loc_75578 clears bit 6 of $38");
    }

    @Test
    void mhzMinibossFinalWaitCallbacksToReturnBounce() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x1A;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = -0x400;
        miniboss.setCustomFlag(0x2E, 0x10);

        for (int frame = 0; frame <= 0x10; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x1C, miniboss.getState().routine,
                "loc_754A0 waits on $2E and calls loc_755A0 after the timer becomes negative");
        assertEquals(0x3021, miniboss.getX(),
                "routine $1A uses MoveSprite2 every wait frame before the callback");
        assertEquals(0x04DC, miniboss.getY(),
                "routine $1A applies y_vel=-$400 for all 17 Obj_Wait frames");
        assertEquals(0x40, miniboss.getCustomFlag(0x38) & 0x40,
                "loc_755A0 sets bit 6 of $38 for the return bounce");
    }

    @Test
    void mhzMinibossReturnBounceLandingSeedsRoutine1E() {
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(renderer);
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3021, 0x051F, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(servicesWithRenderManager(renderManager));
        miniboss.getState().routine = 0x1C;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = 0x100;
        miniboss.setCustomFlag(0x38, 0x40);
        miniboss.setCustomFlag(0x3C, 0x0520);

        miniboss.update(0, null);
        miniboss.appendRenderCommands(new ArrayList<>());

        assertEquals(0x1E, miniboss.getState().routine,
                "loc_755C6 switches to routine $1E once y_pos reaches the stored return height $3C");
        assertEquals(0x3022, miniboss.getX(),
                "loc_755AE applies MoveSprite before the landing setup");
        assertEquals(0x0520, miniboss.getY(),
                "loc_755C6 snaps y_pos to the stored return height $3C");
        assertEquals(0, miniboss.getState().yVel,
                "loc_755C6 clears y_vel after snapping to $3C");
        assertEquals(0x400, miniboss.getState().xVel,
                "loc_755C6 starts the next horizontal wait with x_vel=$400");
        assertEquals(0x2A, miniboss.getCustomFlag(0x2E),
                "loc_755C6 seeds Obj_Wait timer $2E=$2A");
        assertEquals(0, miniboss.getCustomFlag(0x38) & 0x40,
                "loc_755C6 clears bit 6 of $38");
        verify(renderer).isReady();
        verify(renderer).drawFrameIndex(5, 0x3022, 0x0520, false, false);
        verifyNoMoreInteractions(renderer);
    }

    @Test
    void mhzMinibossReturnWaitCallbacksToHorizontalDashSetup() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3022, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x1E;
        miniboss.getState().xVel = 0x400;
        miniboss.getState().yVel = 0;
        miniboss.setCustomFlag(0x2E, 0x2A);
        miniboss.setCustomFlag(0x3C, 0x0520);

        for (int frame = 0; frame <= 0x2A; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(6, miniboss.getState().routine,
                "routine $1E reuses loc_754A0 and calls loc_75330 when $2E becomes negative");
        assertEquals(0x30CE, miniboss.getX(),
                "loc_754A0 applies MoveSprite2 at x_vel=$400 for all 43 wait frames");
        assertEquals(0x0520, miniboss.getY(),
                "loc_75330 snaps y_pos back to the stored hover center $3C");
        assertEquals(0x400, miniboss.getState().xVel,
                "loc_75330 keeps x_vel=$400 for the horizontal dash wait");
        assertEquals(0, miniboss.getState().yVel,
                "loc_75330 clears y_vel before the horizontal dash wait");
        assertEquals(0x1F, miniboss.getCustomFlag(0x2E),
                "loc_75330 seeds the next Obj_Wait timer $2E=$1F");
    }

    @Test
    void mhzMinibossFinalEscapeSwingUsesSwingBeforeMoveSprite2() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x20;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = 0x80;
        miniboss.setCustomFlag(0x38, 0);
        miniboss.setCustomFlag(0x3E, 0x80);
        miniboss.setCustomFlag(0x40, 0x10);

        miniboss.update(0, null);

        assertEquals(0x20, miniboss.getState().routine,
                "loc_7560C stays in routine $20 until Animate_RawMultiDelay reaches anim_frame=$A");
        assertEquals(0x3011, miniboss.getX(),
                "loc_7560C calls Swing_UpAndDown before MoveSprite2");
        assertEquals(0x0520, miniboss.getY(),
                "post-swing y_vel=$70 has no whole-pixel movement on the first frame");
        assertEquals(0x70, miniboss.getState().yVel,
                "Swing_UpAndDown subtracts acceleration $10 from y_vel=$80 before movement");
    }

    @Test
    void mhzMinibossFinalEscapeSwingSpawnsEscapeShardOnChopFrame() throws Exception {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any());
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.getState().routine = 0x20;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = 0x80;
        miniboss.setCustomFlag(0x38, 0);
        miniboss.setCustomFlag(0x3E, 0x80);
        miniboss.setCustomFlag(0x40, 0x10);
        miniboss.setCustomFlag(0x42, 1);
        setPrivateInt(miniboss, "animFrame", 8);
        setPrivateInt(miniboss, "animFrameTimer", 0);

        miniboss.update(0, null);

        assertEquals(0x22, miniboss.getState().routine,
                "loc_7560C switches to routine $22 when byte_75EBB reaches anim_frame=$A");
        assertEquals(0x0F, getPrivateInt(miniboss, "mappingFrame"),
                "loc_7560C writes mapping_frame=$F for the escape animation setup");
        assertEquals(0, miniboss.getCustomFlag(0x42),
                "loc_7560C decrements $42 after the final Knuckles chop frame");
        assertEquals(1, spawned.size(),
                "loc_7560C creates ChildObjDat_75E9E on the escape transition");
        assertEquals("MHZMinibossEscapeShard", ((MhzMinibossEscapeShardInstance) spawned.getFirst()).getName());
        assertEquals(0x3011, spawned.getFirst().getX(),
                "ChildObjDat_75E9E spawns at the parent x_pos after MoveSprite2");
        assertEquals(0x0518, spawned.getFirst().getY(),
                "ChildObjDat_75E9E applies the ROM child y offset -8");
    }

    @Test
    void mhzMinibossEscapeShardInitializesLaunchVelocityTowardPlayer() {
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getCentreX()).thenReturn((short) 0x3081);
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3011, 0x0518, parent);

        shard.update(0, player);

        assertEquals(0x3013, shard.getX(),
                "loc_7585A computes x_vel=(Player_1.x_pos-child.x_pos)<<16/$3800, then loc_7589E moves it");
        assertEquals(0x0515, shard.getY(),
                "loc_7585A seeds y_vel=-$300 before MoveSprite_LightGravity applies the old velocity");
    }

    @Test
    void mhzMinibossEscapeShardUsesRomLogAnimationScript() {
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getCentreX()).thenReturn((short) 0x3081);
        PatternSpriteRenderer renderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG)).thenReturn(renderer);
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3011, 0x0518, parent);
        shard.setServices(servicesWithRenderManager(renderManager));

        shard.update(0, player);
        shard.appendRenderCommands(new ArrayList<>());

        verify(renderer).isReady();
        verify(renderer).drawFrameIndex(0x18, 0x3013, 0x0515, false, false);
        verifyNoMoreInteractions(renderer);
    }

    @Test
    void mhzMinibossEscapeShardFirstFloorTouchBouncesWithHalvedVelocity() throws Exception {
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3000, 0x0520, parent);
        setPrivateInt(shard, "xVel", 0x100);
        setPrivateInt(shard, "yVel", 0x100);
        setPrivateInt(shard, "initialized", 1);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-3, (byte) 0, 0));

            shard.update(0, null);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x3001, 0x0521, 6));
        }
        assertEquals(0x3001, shard.getX(),
                "loc_7589E probes floor after MoveSprite_LightGravity moves with the old x_vel");
        assertEquals(0x051E, shard.getY(),
                "sub_758BE adds negative floor distance d1 to y_pos on first floor touch");
        assertEquals(0x80, getPrivateInt(shard, "xVel"),
                "sub_758BE halves x_vel on the first floor touch");
        assertEquals(-0x90, getPrivateInt(shard, "yVel"),
                "sub_758BE reverses and halves the post-gravity y_vel on the first floor touch");
    }

    @Test
    void mhzMinibossEscapeShardSecondFloorTouchSeedsWaitRoutine() throws Exception {
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3000, 0x0520, parent);
        setPrivateInt(shard, "xVel", 0x80);
        setPrivateInt(shard, "yVel", 0x100);
        setPrivateInt(shard, "initialized", 1);
        setPrivateInt(shard, "touchedFloor", 1);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0))
                    .thenReturn(TerrainCheckResult.noCollision());

            shard.update(0, null);
            shard.update(1, null);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x3000, 0x0521, 6));
        }
        assertEquals(0x051F, shard.getY(),
                "sub_758BE adds d1 to y_pos, then routine 4 waits without continuing to fall");
        assertEquals(0x80, getPrivateInt(shard, "xVel"),
                "the second floor-touch branch leaves x_vel unchanged");
        assertEquals(0x120, getPrivateInt(shard, "yVel"),
                "the second floor-touch branch leaves the post-gravity y_vel unchanged");
    }

    @Test
    void mhzMinibossEscapeShardWaitCallbackStartsUpwardRoutineAndSpawnsSplinter() throws Exception {
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any());
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3000, 0x051F, parent);
        shard.setServices(services);
        setPrivateInt(shard, "initialized", 1);
        setPrivateInt(shard, "routine", 4);
        setPrivateInt(shard, "timer", 0);
        setPrivateInt(shard, "xVel", 0x80);
        setPrivateInt(shard, "yVel", 0x120);

        shard.update(0, null);

        assertEquals(6, getPrivateInt(shard, "routine"),
                "loc_758FC switches the log shard to routine 6 after Obj_Wait expires");
        assertEquals(0, getPrivateInt(shard, "xVel"),
                "loc_758FC clears x_vel before the upward MoveSprite2 wait");
        assertEquals(-0x200, getPrivateInt(shard, "yVel"),
                "loc_758FC launches the shard upward with y_vel=-$200");
        assertEquals(0x1F, getPrivateInt(shard, "timer"),
                "loc_758FC seeds the next Obj_Wait delay to $1F frames");
        assertEquals(1, spawned.size(),
                "loc_758FC creates ChildObjDat_75EA6 for the attached splinter visual");
        assertEquals("MHZMinibossEscapeShardSplinter", spawned.getFirst().getName());
        assertEquals(0x3001, spawned.getFirst().getX(),
                "ChildObjDat_75EA6 applies x offset 1 to the parent shard position");
        assertEquals(0x0532, spawned.getFirst().getY(),
                "ChildObjDat_75EA6 applies y offset $13 to the parent shard position");
        assertEquals(5, spawned.getFirst().getPriorityBucket(),
                "loc_759C0 uses word_75E5E priority $280 for the attached splinter visual");
    }

    @Test
    void mhzMinibossEscapeShardRoutine6MovesAndSeedsRoutine8AfterWait() throws Exception {
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3000, 0x051F, parent);
        setPrivateInt(shard, "initialized", 1);
        setPrivateInt(shard, "routine", 6);
        setPrivateInt(shard, "timer", 0);
        setPrivateInt(shard, "xVel", 0);
        setPrivateInt(shard, "yVel", -0x200);

        shard.update(0, null);

        assertEquals(0x051D, shard.getY(),
                "loc_75924 calls MoveSprite2 before loc_7592A/Obj_Wait handles the callback");
        assertEquals(8, getPrivateInt(shard, "routine"),
                "loc_75936 switches the log shard to routine 8 after the upward wait expires");
        assertEquals(0x1F, getPrivateInt(shard, "timer"),
                "loc_75936 seeds the next Obj_Wait delay to $1F frames");
        assertEquals(-0x200, getPrivateInt(shard, "yVel"),
                "loc_75936 does not change y_vel");
    }

    @Test
    void mhzMinibossEscapeShardRoutine8CallbackTargetsParentAndStartsReturnFall() throws Exception {
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x3000, 0x051D, parent);
        setPrivateInt(shard, "initialized", 1);
        setPrivateInt(shard, "routine", 8);
        setPrivateInt(shard, "timer", 0);
        setPrivateInt(shard, "xVel", 0);
        setPrivateInt(shard, "yVel", -0x200);

        shard.update(0, null);

        assertEquals(0xA, getPrivateInt(shard, "routine"),
                "loc_7594C switches the log shard to routine $A after the hover wait expires");
        assertEquals(-0x300, getPrivateInt(shard, "yVel"),
                "loc_7594C starts the return fall with y_vel=-$300");
        assertEquals(0x1D, getPrivateInt(shard, "xVel"),
                "loc_7594C targets parent x_pos-6 over divisor $6100");
        assertEquals(3, shard.getPriorityBucket(),
                "loc_7594C lowers priority to $180 (bucket 3) for the return arc");
    }

    @Test
    void mhzMinibossEscapeShardReturnArcSignalsParentAndDeletesAtTargetHeight() throws Exception {
        MhzMinibossInstance parent = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        parent.setCustomFlag(0x38, 0);
        MhzMinibossEscapeShardInstance shard = new MhzMinibossEscapeShardInstance(
                0x300B, 0x0518, parent);
        setPrivateInt(shard, "initialized", 1);
        setPrivateInt(shard, "routine", 0xA);
        setPrivateInt(shard, "xVel", 0);
        setPrivateInt(shard, "yVel", 0);

        shard.update(0, null);

        assertEquals(0x04, parent.getCustomFlag(0x38) & 0x04,
                "loc_759B0 sets parent $38 bit 2 when the child reaches parent.y_pos-8");
        assertEquals(0x10, getPrivateInt(shard, "yVel"),
                "loc_75986 applies +$10 gravity before the terminal height check");
        assertEquals(0x0518, shard.getY(),
                "MoveSprite2 applies the new y_vel but $10 subpixels do not move a whole pixel");
        assertEquals(true, shard.isDestroyed(),
                "loc_759B0 deletes the child after signalling the parent");
    }

    @Test
    void mhzMinibossFinalEscapeAnimateCallbackSeedsRoutine24() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x22;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = 0x80;
        miniboss.setCustomFlag(0x38, 0);
        miniboss.setCustomFlag(0x3E, 0x80);
        miniboss.setCustomFlag(0x40, 0x10);

        for (int frame = 0; frame < 140 && miniboss.getState().routine == 0x22; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x24, miniboss.getState().routine,
                "byte_75F02 terminator $F4 calls loc_7566E and switches to routine $24");
        assertEquals(-0x80, miniboss.getState().yVel,
                "loc_7566E seeds y_vel=-$80 for the routine $24 wait");
        assertEquals(0x3F, miniboss.getCustomFlag(0x2E),
                "loc_7566E seeds Obj_Wait timer $2E=$3F");
    }

    @Test
    void mhzMinibossFinalEscapeWaitCallbacksToRoutine26SwingSetup() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x24;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = -0x80;
        miniboss.setCustomFlag(0x2E, 0x3F);
        miniboss.setCustomFlag(0x38, 1);

        for (int frame = 0; frame <= 0x3F; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x26, miniboss.getState().routine,
                "routine $24 reuses loc_754A0 and calls loc_7568A when $2E becomes negative");
        assertEquals(0x3050, miniboss.getX(),
                "loc_754A0 applies MoveSprite2 at x_vel=$100 for all 64 wait frames");
        assertEquals(0x0500, miniboss.getY(),
                "loc_754A0 applies y_vel=-$80 for all 64 wait frames before the callback");
        assertEquals(0x80, miniboss.getState().yVel,
                "loc_7568A reuses loc_75302 and seeds y_vel=$80");
        assertEquals(0x80, miniboss.getCustomFlag(0x3E),
                "loc_75302 seeds maximum swing velocity $3E=$80");
        assertEquals(0x10, miniboss.getCustomFlag(0x40),
                "loc_75302 seeds swing acceleration $40=$10");
        assertEquals(0, miniboss.getCustomFlag(0x38) & 1,
                "loc_75302 clears bit 0 of $38");
    }

    @Test
    void mhzMinibossFinalEscapeSwingBranchesWhenSignalBitIsSet() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0520, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x26;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = 0x80;
        miniboss.setCustomFlag(0x38, 0x04);
        miniboss.setCustomFlag(0x3E, 0x80);
        miniboss.setCustomFlag(0x40, 0x10);

        miniboss.update(0, null);

        assertEquals(0x28, miniboss.getState().routine,
                "loc_75694 branches to routine $28 only when bit 2 of $38 is set");
        assertEquals(0x3011, miniboss.getX(),
                "loc_75694 applies MoveSprite2 before testing the signal bit");
        assertEquals(0x0520, miniboss.getY(),
                "post-swing y_vel=$70 has no whole-pixel movement before the branch setup");
        assertEquals(-0x200, miniboss.getState().yVel,
                "loc_756AA seeds y_vel=-$200 for the camera-rise phase");
    }

    @Test
    void mhzMinibossFinalEscapeCameraRiseSnapsToCameraThreshold() {
        Camera camera = new Camera();
        camera.setY((short) 0x0500);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0532, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.update(0, null);
        miniboss.getState().x = 0x3010;
        miniboss.getState().y = 0x0532;
        miniboss.getState().xFixed = 0x3010 << 16;
        miniboss.getState().yFixed = 0x0532 << 16;
        miniboss.getState().routine = 0x28;
        miniboss.getState().xVel = 0x100;
        miniboss.getState().yVel = -0x200;

        miniboss.update(1, null);

        assertEquals(0x2A, miniboss.getState().routine,
                "loc_756C6 switches to routine $2A once y_pos reaches Camera_Y_pos+$30");
        assertEquals(0x3011, miniboss.getX(),
                "loc_756C6 applies MoveSprite2 before the camera threshold branch");
        assertEquals(0x0530, miniboss.getY(),
                "loc_756E2 snaps y_pos to Camera_Y_pos+$30");
        assertEquals(0x1F, miniboss.getCustomFlag(0x2E),
                "loc_756E2 seeds Obj_Wait timer $2E=$1F");
    }

    @Test
    void mhzMinibossFinalEscapeCameraWaitCallbacksToRoutine2C() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3011, 0x0530, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x2A;
        miniboss.setCustomFlag(0x2E, 0x1F);

        for (int frame = 0; frame <= 0x1F; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(0x2C, miniboss.getState().routine,
                "routine $2A reuses loc_756FC and calls loc_75708 when $2E becomes negative");
        assertEquals(-0x400, miniboss.getState().xVel,
                "loc_75708 seeds x_vel=-$400 for the final upward escape");
        assertEquals(0x700, miniboss.getState().yVel,
                "loc_75708 seeds y_vel=$700 before routine $2C decelerates upward");
    }

    @Test
    void mhzMinibossFinalEscapeUpwardSnapsToCameraReturnPosition() {
        Camera camera = new Camera();
        camera.setX((short) 0x2D00);
        camera.setY((short) 0x0500);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x3010, 0x0485, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        miniboss.update(0, null);
        miniboss.getState().x = 0x3010;
        miniboss.getState().y = 0x0485;
        miniboss.getState().xFixed = 0x3010 << 16;
        miniboss.getState().yFixed = 0x0485 << 16;
        miniboss.getState().routine = 0x2C;
        miniboss.getState().xVel = -0x400;
        miniboss.getState().yVel = -0x6C0;
        miniboss.setCustomFlag(0x38, 0x04);

        miniboss.update(1, null);

        assertEquals(0x2E, miniboss.getState().routine,
                "loc_7571C switches to routine $2E once y_pos reaches Camera_Y_pos-$80");
        assertEquals(0x2D30, miniboss.getX(),
                "loc_7574A snaps x_pos to Camera_X_pos_copy+$30");
        assertEquals(0x04A4, miniboss.getY(),
                "loc_7574A snaps y_pos to Camera_Y_pos_copy-$5C");
        assertEquals(0x400, miniboss.getState().xVel,
                "loc_7574A seeds x_vel=$400 for the return wait");
        assertEquals(0x400, miniboss.getState().yVel,
                "loc_7574A seeds y_vel=$400 for the return wait");
        assertEquals(0x37, miniboss.getCustomFlag(0x2E),
                "loc_7574A seeds Obj_Wait timer $2E=$37");
        assertEquals(0, miniboss.getCustomFlag(0x38) & 0x04,
                "loc_7574A clears bit 2 of $38");
    }

    @Test
    void mhzMinibossFinalEscapeReturnWaitCallbacksToHorizontalDash() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x2D30, 0x04A4, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.getState().routine = 0x2E;
        miniboss.getState().xVel = 0x400;
        miniboss.getState().yVel = 0x400;
        miniboss.setCustomFlag(0x2E, 0x37);
        miniboss.setCustomFlag(0x3C, 0x0520);

        for (int frame = 0; frame <= 0x37; frame++) {
            miniboss.update(frame, null);
        }

        assertEquals(6, miniboss.getState().routine,
                "routine $2E reuses Obj_Wait and calls loc_75330 when $2E becomes negative");
        assertEquals(0x0520, miniboss.getY(),
                "loc_75330 snaps y_pos back to the stored hover center $3C");
        assertEquals(0x400, miniboss.getState().xVel,
                "loc_75330 keeps x_vel=$400 for the horizontal dash wait");
        assertEquals(0, miniboss.getState().yVel,
                "loc_75330 clears y_vel before the horizontal dash wait");
        assertEquals(0x1F, miniboss.getCustomFlag(0x2E),
                "loc_75330 seeds the next Obj_Wait timer $2E=$1F");
    }

    @Test
    void mhzBossesRenderInitialRomFramesWithVerifiedArtKeys() {
        PatternSpriteRenderer minibossRenderer = readyRenderer();
        PatternSpriteRenderer treeRenderer = readyRenderer();
        PatternSpriteRenderer endBossRenderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS)).thenReturn(minibossRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_TREE)).thenReturn(treeRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS)).thenReturn(endBossRenderer);
        ObjectServices services = servicesWithRenderManager(renderManager);

        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        tree.setServices(services);
        endBoss.setServices(services);

        List<GLCommand> commands = new ArrayList<>();
        miniboss.appendRenderCommands(commands);
        tree.appendRenderCommands(commands);
        endBoss.appendRenderCommands(commands);

        verify(minibossRenderer).isReady();
        verify(treeRenderer).isReady();
        verify(endBossRenderer).isReady();
        verify(minibossRenderer).drawFrameIndex(0, 0x1800, 0x0400, false, false);
        verify(treeRenderer).drawFrameIndex(5, 0x1810, 0x0410, false, false);
        verify(endBossRenderer).drawFrameIndex(1, 0x42C0, 0x0300, false, false);
        verifyNoMoreInteractions(minibossRenderer, treeRenderer, endBossRenderer);
    }

    @Test
    void mhzMinibossTreeMirrorsBossScratch42MappingFrame() {
        AbstractObjectInstance.updateCameraBounds(0x1700, 0x0300, 0x1900, 0x0500, 0);
        PatternSpriteRenderer treeRenderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_TREE)).thenReturn(treeRenderer);

        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setCustomFlag(0x42, 3);
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));

        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(miniboss, tree));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        tree.setServices(services);

        tree.update(0, null);
        tree.appendRenderCommands(new ArrayList<>());

        verify(treeRenderer).isReady();
        verify(treeRenderer).drawFrameIndex(3, 0x1810, 0x0410, false, false);
    }

    @Test
    void mhzMinibossTreeChipUsesRomYOffsetAndDefaultMoveSprite2Velocity() {
        AbstractObjectInstance.updateCameraBounds(0x1700, 0x0300, 0x1900, 0x0500, 0);
        List<ObjectInstance> spawned = new ArrayList<>();
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setCustomFlag(0x42, 1);
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));

        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(miniboss, tree));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        tree.setServices(services);

        tree.update(0, null);

        assertEquals(1, spawned.size(),
                "CreateChild6_Simple should allocate one tree chip when mapping_frame drops below 5");
        ObjectInstance chip = spawned.get(0);
        assertEquals(0x1810, chip.getX(),
                "loc_75AD4 keeps the child's x_pos at the tree x_pos before MoveSprite2");
        assertEquals(0x0430, chip.getY(),
                "byte_75B2E[1]=$20 should offset the child y_pos by +32 pixels");

        chip.update(1, null);

        assertEquals(0x180C, chip.getX(),
                "non-Knuckles loc_75AD4 forces x_vel=-$400, so MoveSprite2 shifts left 4 px/frame");
        assertEquals(0x0430, chip.getY(),
                "without the bounce flag, loc_75B34 applies MoveSprite2 with zero y_vel");
    }

    @Test
    void mhzMinibossTreeAndChipUseRomObjectDataMetadata() {
        AbstractObjectInstance.updateCameraBounds(0x1700, 0x0300, 0x1900, 0x0500, 0);
        List<ObjectInstance> spawned = new ArrayList<>();
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setCustomFlag(0x42, 1);
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(miniboss, tree));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        tree.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        tree.update(0, null);

        assertEquals(0x14, tree.getOnScreenHalfWidth(),
                "ObjDat_MHZMinibossTree width_pixels is $14");
        assertEquals(0x90, tree.getOnScreenHalfHeight(),
                "ObjDat_MHZMinibossTree height_pixels is $90");
        AbstractObjectInstance chip = assertInstanceOf(AbstractObjectInstance.class, spawned.getFirst());
        TouchResponseProvider chipTouch = assertInstanceOf(TouchResponseProvider.class, chip,
                "ObjDat3_75E72 gives the tree chip collision_flags=$86");
        assertEquals(7, chip.getPriorityBucket(),
                "ObjDat3_75E72 priority word $380 maps to render bucket 7");
        assertEquals(0x14, chip.getOnScreenHalfWidth(),
                "ObjDat3_75E72 chip width_pixels is $14");
        assertEquals(0x14, chip.getOnScreenHalfHeight(),
                "ObjDat3_75E72 chip height_pixels is $14");
        assertEquals(0x86, chipTouch.getCollisionFlags(),
                "ObjDat3_75E72 collision_flags is $86");
        assertEquals(0, chipTouch.getCollisionProperty(),
                "ObjDat3_75E72 collision_property is 0");
    }

    @Test
    void mhzMinibossTreeChipUsesKnucklesRandomSlowBounceVelocity() {
        // The slow-bounce chip probes terrain through ObjectTerrainUtils, which
        // consults GameServices.levelOrNull(). Clear any level leaked by a
        // fixture-based test class earlier in the same surefire fork (including
        // the shared-level cache, which resetPerTest intentionally keeps) so
        // the floor probe stays deterministic regardless of fork composition.
        com.openggf.tests.TestEnvironment.resetAll();
        AbstractObjectInstance.updateCameraBounds(0x1700, 0x0300, 0x1900, 0x0500, 0);
        List<ObjectInstance> spawned = new ArrayList<>();
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setCustomFlag(0x42, 1);
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));

        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(miniboss, tree));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ZoneRuntimeState zoneRuntimeState() {
                return new MhzZoneRuntimeState(0, PlayerCharacter.KNUCKLES);
            }
        };
        services.rng().setSeed(1);
        tree.setServices(services);

        tree.update(0, null);
        ObjectInstance chip = spawned.get(0);
        chip.update(1, null);

        assertEquals(0x180E, chip.getX(),
                "loc_75AD4 keeps Random_Number bit 0 for Knuckles and uses x_vel=-$200 when $42 is nonzero");
        assertEquals(0x0430, chip.getY(),
                "loc_75B34 applies the slow-chip y_vel after the first MoveSprite2 position step");
        chip.update(2, null);
        chip.update(3, null);
        chip.update(4, null);
        assertEquals(0x0431, chip.getY(),
                "the Knuckles slow-chip branch sets $3A, so loc_75B34 accumulates +$20 y_vel before MoveSprite2");
    }

    @Test
    void mhzMinibossTreeChipRunsRomRawAnimationScript() {
        AbstractObjectInstance.updateCameraBounds(0x1700, 0x0300, 0x1900, 0x0500, 0);
        List<ObjectInstance> spawned = new ArrayList<>();
        PatternSpriteRenderer logRenderer = readyRenderer();
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG)).thenReturn(logRenderer);
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setCustomFlag(0x42, 2);
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));

        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(miniboss, tree));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        tree.setServices(services);

        tree.update(0, null);
        AbstractObjectInstance chip = (AbstractObjectInstance) spawned.get(0);
        chip.setServices(services);
        chip.update(1, null);
        chip.update(2, null);
        chip.appendRenderCommands(new ArrayList<>());

        verify(logRenderer).drawFrameIndex(1, 0x1808, 0x0410, false, false);
    }

    @Test
    void mhzBossesUseRomPriorityBuckets() {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        MhzMinibossTreeInstance tree = new MhzMinibossTreeInstance(new ObjectSpawn(
                0x1810, 0x0410, Sonic3kObjectIds.MHZ_MINIBOSS_TREE, 0, 0, false, 0));
        MhzEndBossInstance endBoss = new MhzEndBossInstance(new ObjectSpawn(
                0x4200, 0x0300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0));

        assertEquals(4, miniboss.getPriorityBucket(),
                "ObjDat_MHZMiniboss uses priority $200");
        assertEquals(7, tree.getPriorityBucket(),
                "ObjDat_MHZMinibossTree uses priority $380");
        assertEquals(5, endBoss.getPriorityBucket(),
                "ObjDat3_76934 for the active MHZ end boss core uses priority $280");
        assertRenderBounds(endBoss, 0x80, 0x80,
                "ObjDat3_76934 gives the active MHZ end boss core x/y radius $80,$80");
    }

    @Test
    void mhzMinibossFinalLaunchScriptSettlesOnRomFrameSixAfterJumpCommand() throws Exception {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        int[] script = getPrivateStaticIntArray("FINAL_LAUNCH_SCRIPT");
        Method animate = MhzMinibossInstance.class.getDeclaredMethod("animateRawMultiDelay", int[].class);
        animate.setAccessible(true);

        // Run past the leading $0A/$08/$06 frames and the $F8 jump so the script settles.
        for (int i = 0; i < 400; i++) {
            animate.invoke(miniboss, (Object) script);
        }
        assertEquals(6, getPrivateInt(miniboss, "mappingFrame"),
                "loc_845E4's $F8 re-points the raw-animation script base by its signed relative "
                        + "offset, and loc_845F2's fallthrough settles the launch animation on the "
                        + "repeated frame $06 pair sitting at the new base");

        // Without $F8 re-pointing scriptBase, the script would restart from index 0 and cycle
        // back through $0A/$08 again; collecting every frame seen after settling proves the
        // animation is genuinely pinned on $06 forever, not just coincidentally $06 at this tick.
        java.util.Set<Integer> framesAfterSettling = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 600; i++) {
            animate.invoke(miniboss, (Object) script);
            framesAfterSettling.add(getPrivateInt(miniboss, "mappingFrame"));
        }
        assertEquals(java.util.Set.of(6), framesAfterSettling,
                "the $F8 re-point base keeps re-emitting only frame $06 indefinitely through the "
                        + "bare $FC command at the tail of FINAL_LAUNCH_SCRIPT, never cycling back "
                        + "to $0A/$08");
    }

    @Test
    void mhzMinibossFinalCameraRiseScriptLoopsBackToRomFrameZero() throws Exception {
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0x1800, 0x0400, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        int[] script = getPrivateStaticIntArray("FINAL_CAMERA_RISE_SCRIPT");
        Method animate = MhzMinibossInstance.class.getDeclaredMethod("animateRawMultiDelay", int[].class);
        animate.setAccessible(true);

        int zeroHits = 0;
        for (int i = 0; i < 400; i++) {
            Object result = animate.invoke(miniboss, (Object) script);
            if ((int) result == 1 && getPrivateInt(miniboss, "mappingFrame") == 0) {
                zeroHits++;
            }
        }

        assertTrue(zeroHits >= 2,
                "loc_845F2's bare $FC command should re-emit FINAL_CAMERA_RISE_SCRIPT's frame-0 "
                        + "entry every time the 1-2-3-2-1-0 cycle wraps, not just once");
    }

    private static PatternSpriteRenderer readyRenderer() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        return renderer;
    }

    private static void assertRenderBounds(
            AbstractObjectInstance object, int expectedHalfWidth, int expectedHalfHeight, String message) {
        assertEquals(expectedHalfWidth, object.getOnScreenHalfWidth(), message);
        assertEquals(expectedHalfHeight, object.getOnScreenHalfHeight(), message);
    }

    private static ObjectServices servicesWithRenderManager(ObjectRenderManager renderManager) {
        return new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        if (field.getType() == boolean.class) {
            field.setBoolean(target, value != 0);
        } else {
            field.setInt(target, value);
        }
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static int[] getPrivateStaticIntArray(String fieldName) throws Exception {
        Field field = MhzMinibossInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    private static MhzMinibossInstance managedMinibossAtCamera(int cameraX, int cameraY) {
        Camera camera = new Camera();
        camera.setX((short) cameraX);
        camera.setY((short) cameraY);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        return miniboss;
    }

    private static MhzMinibossInstance managedMinibossWithRenderManager(
            int cameraX, int cameraY, ObjectRenderManager renderManager) {
        Camera camera = new Camera();
        camera.setX((short) cameraX);
        camera.setY((short) cameraY);
        ObjectServices services = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        MhzMinibossInstance miniboss = new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(services);
        return miniboss;
    }

    private static void advanceMinibossToOffscreenDashWait(MhzMinibossInstance miniboss) {
        miniboss.update(0, null);
        for (int frame = 1; frame <= 0x98; frame++) {
            miniboss.update(frame, null);
        }
        for (int frame = 0x99; frame <= 0xD5; frame++) {
            miniboss.update(frame, null);
        }
        for (int frame = 0xD6; frame <= 0xF5; frame++) {
            miniboss.update(frame, null);
        }
    }

    private static void advanceMinibossToDecelerationDash(MhzMinibossInstance miniboss) {
        advanceMinibossToOffscreenDashWait(miniboss);
        miniboss.getState().renderFlags = 0x80;
        for (int frame = 0xF7; frame <= 0x146; frame++) {
            miniboss.update(frame, null);
        }
    }

    private static void advanceMinibossToCameraApproachSwing(MhzMinibossInstance miniboss) {
        advanceMinibossToDecelerationDash(miniboss);
        for (int frame = 0x147; frame <= 0x166; frame++) {
            miniboss.update(frame, null);
        }
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

    private static byte[] paletteLine(int word) {
        byte[] line = new byte[32];
        for (int offset = 0; offset < line.length; offset += 2) {
            line[offset] = (byte) ((word >>> 8) & 0xFF);
            line[offset + 1] = (byte) (word & 0xFF);
        }
        return line;
    }

    private static byte[] paletteBlock(int line3Word, int line4Word) {
        byte[] block = new byte[64];
        byte[] line3 = paletteLine(line3Word);
        byte[] line4 = paletteLine(line4Word);
        System.arraycopy(line3, 0, block, 0, line3.length);
        System.arraycopy(line4, 0, block, line3.length, line4.length);
        return block;
    }

    private static final class RecordingPaletteServices extends TestObjectServices {
        private final StubLevel level = new StubLevel();
        private final PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        private ObjectManager objectManager;
        private ZoneRuntimeState zoneRuntimeState;
        private int lastMusicId = -1;

        RecordingPaletteServices withObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
            return this;
        }

        RecordingPaletteServices withZoneRuntimeState(ZoneRuntimeState zoneRuntimeState) {
            this.zoneRuntimeState = zoneRuntimeState;
            return this;
        }

        @Override
        public Level currentLevel() {
            return level;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ZoneRuntimeState zoneRuntimeState() {
            return zoneRuntimeState;
        }

        @Override
        public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
            return registry;
        }

        @Override
        public void playMusic(int musicId) {
            lastMusicId = musicId;
        }
    }

    private static final class TestableS3kSuperStateController extends Sonic3kSuperStateController {
        private TestableS3kSuperStateController(AbstractPlayableSprite player) {
            super(player);
        }

        void seedActiveCycle(byte[] paletteData) throws Exception {
            setField("paletteData", paletteData);
            setField("paletteState", -1);
            setField("paletteFrame", 0);
            setField("paletteTimer", -1);
        }

        void tickSuperPalette() {
            updateSuperPalette();
        }

        private void setField(String name, Object value) throws Exception {
            Field field = Sonic3kSuperStateController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        }
    }

    private static final class FixedReadRom extends Rom {
        private final byte[] bytes;

        private FixedReadRom(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] readBytes(long offset, int count) {
            return bytes;
        }
    }

    private static final class OffsetReadRom extends Rom {
        private final java.util.Map<Long, byte[]> reads = new java.util.HashMap<>();

        OffsetReadRom with(long offset, byte[] bytes) {
            reads.put(offset, bytes);
            return this;
        }

        @Override
        public byte[] readBytes(long offset, int count) {
            byte[] bytes = reads.get(offset);
            if (bytes == null) {
                throw new IllegalArgumentException("No fixture bytes for ROM offset " + offset);
            }
            return java.util.Arrays.copyOf(bytes, count);
        }
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = new Palette[] {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

        Palette[] palettes() {
            return palettes;
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { throw new UnsupportedOperationException(); }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
