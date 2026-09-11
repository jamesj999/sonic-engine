package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.GameRng;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCluckoidBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryCreatesCluckoidForSklSlot90InMhz() {
        Sonic3kObjectRegistry registry = new MhzRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x220, 0x120,
                Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));

        assertInstanceOf(CluckoidBadnikInstance.class, instance);
    }

    @Test
    void usesRomPriorityBucketFromObjSlot() {
        CluckoidBadnikInstance cluckoid = cluckoid();

        assertEquals(5, cluckoid.getPriorityBucket(),
                "ObjSlot_Cluckoid priority word $280 maps to render bucket 5");
    }

    @Test
    void usesRomRenderBoundsFromObjData() {
        CluckoidBadnikInstance cluckoid = cluckoid();
        CluckoidBadnikInstance.ArrowChild arrow = new CluckoidBadnikInstance.ArrowChild(cluckoid);
        CluckoidBadnikInstance.BreathDebrisChild debris = new CluckoidBadnikInstance.BreathDebrisChild(
                new ObjectSpawn(0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0),
                0x240,
                0x140,
                0,
                0,
                0,
                false,
                false);

        assertEquals(0x14, cluckoid.getOnScreenHalfWidth(),
                "ObjSlot_Cluckoid width_pixels is $14");
        assertEquals(0x10, cluckoid.getOnScreenHalfHeight(),
                "ObjSlot_Cluckoid height_pixels is $10");
        assertEquals(0x10, arrow.getOnScreenHalfWidth(),
                "ObjDat3_8E3EA arrow width_pixels is $10");
        assertEquals(0x0C, arrow.getOnScreenHalfHeight(),
                "ObjDat3_8E3EA arrow height_pixels is $0C");
        assertEquals(0x08, debris.getOnScreenHalfWidth(),
                "ObjDat3_8E3F6 breath debris width_pixels is 8");
        assertEquals(0x08, debris.getOnScreenHalfHeight(),
                "ObjDat3_8E3F6 breath debris height_pixels is 8");
        assertEquals(0, debris.getPriorityBucket(),
                "ObjDat3_8E3F6 breath debris priority word 0 maps to render bucket 0");
    }

    @Test
    void setupSpawnsRomArrowChildAfterWaitOffscreenReturns() {
        putCluckoidOnScreen();
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withGameState(mock(GameStateManager.class)));
        TestablePlayableSprite player = player(0x260, 0x120);

        cluckoid.update(0, player);

        assertTrue(spawned.isEmpty(),
                "Obj_WaitOffscreen returns before loc_8E1AA can create the arrow child");

        cluckoid.update(1, player);

        CluckoidBadnikInstance.ArrowChild arrow =
                assertInstanceOf(CluckoidBadnikInstance.ArrowChild.class, spawned.get(0));
        assertEquals(0x220, arrow.getX(),
                "ChildObjDat_8E402 uses normal-adjusted X offset 0");
        assertEquals(0x13C, arrow.getY(),
                "ChildObjDat_8E402 uses normal-adjusted Y offset $1C");

        cluckoid.update(2, player);

        assertEquals(1, spawned.size(),
                "loc_8E1AA runs only once, so the Cluckoid arrow child must not be duplicated");
    }

    @Test
    void waitsForPlayerWithinRomXAndYRange() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x2A0, 0x160);

        cluckoid.update(0, player);
        cluckoid.update(1, player);
        cluckoid.update(2, player);

        assertEquals("IDLE", cluckoid.getStateName());

        player.setCentreX((short) 0x29F);
        cluckoid.update(3, player);

        assertEquals("IDLE", cluckoid.getStateName());

        player.setCentreY((short) 0x15F);
        cluckoid.update(4, player);

        assertEquals("BREATHING", cluckoid.getStateName());
    }

    @Test
    void nativeP2InsideRomRangeStartsBreathWhenP1IsTooFar() {
        putCluckoidOnScreen();
        TestablePlayableSprite sidekick = player(0x221, 0x120);
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x2A0, 0x120);

        cluckoid.update(0, sonic);
        cluckoid.update(1, sonic);
        cluckoid.update(2, sonic);

        assertEquals("BREATHING", cluckoid.getStateName(),
                "loc_8E1BE uses Find_SonicTails before testing d2<$80 and d3<$40");
    }

    @Test
    void deadPlayerInsideRomRangeStillStartsBreath() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x221, 0x120);
        player.setDead(true);

        cluckoid.update(0, player);
        cluckoid.update(1, player);
        cluckoid.update(2, player);

        assertEquals("BREATHING", cluckoid.getStateName(),
                "loc_8E1BE only checks Find_SonicTails distance d2<$80 and d3<$40; it does not gate on player death");
    }

    @Test
    void firstBreathFrameUsesRomRawNoSstMultiDelayStep() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x260, 0x120);

        cluckoid.update(0, player);
        cluckoid.update(1, player);
        cluckoid.update(2, player);
        cluckoid.update(3, player);

        assertEquals(1, cluckoid.getMappingFrame(),
                "loc_8E1E2 calls Animate_RawNoSSTMultiDelay over byte_8E418, "
                        + "which advances from anim_frame 0 to script offset 2 on its first tick");
    }

    @Test
    void breathAnimationEnablesWindAndThenRunsRomCooldown() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x260, 0x120);

        cluckoid.update(0, player);
        cluckoid.update(1, player);
        cluckoid.update(2, player);
        cluckoid.update(3, player);

        for (int frame = 4; frame <= 0x5B; frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertTrue(cluckoid.getBreathProjectileCount() > 0);
        assertTrue(player.getCentreX() > 0x260);

        for (int frame = 0x5C; frame <= 0xA7; frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals("COOLDOWN", cluckoid.getStateName());
        assertEquals(0x60, cluckoid.getTimer(),
                "byte_8E418 terminates through loc_8E20C, which seeds $2E with $60");

        for (int frame = 0xA8; frame <= 0x10A; frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals("IDLE", cluckoid.getStateName());
        assertEquals(0, cluckoid.getMappingFrame());
    }

    @Test
    void cooldownExitsOnlyAfterTimerUnderflowsSignedLikeRomBmi() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x260, 0x120);

        for (int frame = 0; frame <= 0xA7; frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals("COOLDOWN", cluckoid.getStateName());
        assertEquals(0x60, cluckoid.getTimer());

        for (int frame = 0xA8; frame < 0xA8 + 0x60; frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals("COOLDOWN", cluckoid.getStateName(),
                "loc_8E21A uses subq.w #1,$2E then bmi, so timer value 0 still returns without resetting");
        assertEquals(0, cluckoid.getTimer());

        cluckoid.update(0xA8 + 0x60, player);

        assertEquals("IDLE", cluckoid.getStateName(),
                "The ROM exits cooldown only after the word timer underflows to negative");
        assertEquals(0, cluckoid.getMappingFrame());
    }

    @Test
    void breathStartPlaysEnemyBreathSfxOnceWhenRomLatchFirstSets() {
        putCluckoidOnScreen();
        RecordingServices services = new RecordingServices().withGameState(mock(GameStateManager.class));
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(services);
        TestablePlayableSprite player = player(0x260, 0x120);

        for (int frame = 0; frame <= 0x5B && !cluckoid.isWindActive(); frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals(List.of(Sonic3kSfx.ENEMY_BREATH.id), services.soundIds,
                "loc_8E1E2 plays sfx_EnemyBreath only when bset #7,$38(a0) first sets the breath latch");

        for (int frame = 0x5C; frame <= 0x63; frame++) {
            cluckoid.update(frame, player);
        }

        assertEquals(List.of(Sonic3kSfx.ENEMY_BREATH.id), services.soundIds,
                "The ROM bset latch suppresses repeated sfx_EnemyBreath playback while breath remains active");
    }

    @Test
    void breathWindAppliesToNativeP2AsWellAsPlayerOne() {
        putCluckoidOnScreen();
        TestablePlayableSprite sidekick = player(0x260, 0x120);
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 1, false, 0));
        cluckoid.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x400, 0x120);

        for (int frame = 0; frame <= 0x5B; frame++) {
            cluckoid.update(frame, sonic);
        }

        assertTrue(cluckoid.isWindActive());
        assertTrue(sidekick.getCentreX() > 0x260,
                "sub_8E37C calls sub_8E388 for Player_1 and then Player_2 while Cluckoid breath is active");
    }

    @Test
    void leftFacingBreathWindDoesNotPushPlayerBehindCluckoid() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        TestablePlayableSprite player = player(0x260, 0x120);
        int startX = player.getCentreX();

        for (int frame = 0; frame <= 0x5B && !cluckoid.isWindActive(); frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertEquals(startX, player.getCentreX(),
                "sub_8E388 returns when render_flags bit 0 is clear and the player is behind the left-facing breath");
    }

    @Test
    void leftFacingBreathWindPushesPlayerAtSameXLikeRomFindOtherObject() {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        TestablePlayableSprite player = player(0x220, 0x120);

        for (int frame = 0; frame <= 0x5B && !cluckoid.isWindActive(); frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertEquals(0x210, player.getCentreX(),
                "Find_OtherObject returns d0=0 when x_pos(a0)==x_pos(a1); "
                        + "left-facing sub_8E388 subtracts 2 from the side word, keeps it nonzero, "
                        + "and applies a full 16-pixel push to the left");
    }

    @Test
    void breathWindStillPushesDeadPlayerBecauseRomSub8E388HasNoDeathGate() {
        TestablePlayableSprite player = player(0x260, 0x120);
        player.setDead(true);

        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        int startX = player.getCentreX();

        for (int frame = 0; frame <= 0x5B; frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertTrue(player.getCentreX() > startX,
                "sub_8E388 gates on anim=8, spin_dash_flag, object_control, distance and facing, but not dead state");
    }

    @Test
    void breathWindPreservesPlayerXSubpixelLikeRomAddWordToXPos() {
        TestablePlayableSprite player = player(0x260, 0x120);
        player.setSubpixelRaw(0x5A00, 0x6B00);

        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();

        for (int frame = 0; frame <= 0x5B; frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertTrue(player.getCentreX() > 0x260);
        assertEquals(0x5A00, player.getXSubpixelRaw(),
                "sub_8E388 applies wind with add.w d2,x_pos(a1), preserving the x_sub fraction");
        assertEquals(0x6B00, player.getYSubpixelRaw(),
                "Cluckoid wind does not touch y_pos or y_sub");
    }

    @Test
    void breathWindSkipsDuckingPlayerPerRomSub8E388() {
        TestablePlayableSprite player = player(0x260, 0x120);
        player.setAnimationId(8);

        assertBreathWindDoesNotMovePlayer(player,
                "sub_8E388 returns before wind pressure when anim(a1) == 8");
    }

    @Test
    void breathWindSkipsSpindashingPlayerPerRomSub8E388() {
        TestablePlayableSprite player = player(0x260, 0x120);
        player.setSpindash(true);

        assertBreathWindDoesNotMovePlayer(player,
                "sub_8E388 returns before wind pressure when spin_dash_flag(a1) is nonzero");
    }

    @Test
    void breathWindSkipsObjectControlledPlayerPerRomSub8E388() {
        TestablePlayableSprite player = player(0x260, 0x120);
        player.setObjectControlled(true);

        assertBreathWindDoesNotMovePlayer(player,
                "sub_8E388 returns before wind pressure when object_control(a1) is nonzero");
    }

    @Test
    void breathCreatesRomDebrisChildEveryEightVintTicks() {
        putCluckoidOnScreen();
        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withGameState(mock(GameStateManager.class))
                .withRng(new GameRng(GameRng.Flavour.S3K, 1));
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(services);
        TestablePlayableSprite player = player(0x260, 0x120);

        for (int frame = 0; frame <= 0x60 && spawned.stream()
                .noneMatch(CluckoidBadnikInstance.BreathDebrisChild.class::isInstance); frame++) {
            cluckoid.update(frame, player);
        }

        CluckoidBadnikInstance.BreathDebrisChild debris =
                assertInstanceOf(CluckoidBadnikInstance.BreathDebrisChild.class,
                        spawned.stream()
                                .filter(CluckoidBadnikInstance.BreathDebrisChild.class::isInstance)
                                .findFirst()
                                .orElseThrow());
        assertEquals(0x01F0, debris.getX(),
                "sub_8E2EA selects word_8E438[2] for seed 1 and mirrors it left when render_flags bit 0 is clear");
        assertEquals(0x0148, debris.getY());
        assertEquals(-0x0600, debris.getXVelocity());
        assertEquals(-0x0180, debris.getYVelocity());
        assertEquals(0x20, debris.getXAcceleration());
    }

    @Test
    void breathDebrisCadenceUsesRomVintRunCountPlusThreePhase() {
        putCluckoidOnScreen();
        List<Integer> spawnFrames = new ArrayList<>();
        int[] currentFrame = {0};
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof CluckoidBadnikInstance.BreathDebrisChild) {
                spawnFrames.add(currentFrame[0]);
            }
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withGameState(mock(GameStateManager.class))
                .withRng(new GameRng(GameRng.Flavour.S3K, 1));
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0));
        cluckoid.setServices(services);
        TestablePlayableSprite player = player(0x260, 0x120);

        for (int frame = 0; frame <= 0x60 && spawnFrames.isEmpty(); frame++) {
            currentFrame[0] = frame;
            cluckoid.update(frame, player);
        }

        assertEquals(List.of(0x55), spawnFrames,
                "sub_8E2D4 gates CreateChild6_Simple on ((V_int_run_count+3)&7)==0, "
                        + "so the first active breath particle appears at frame $55 rather than frame $58");
    }

    @Test
    void objWaitOffscreenSuppressesIdleDetectionAndCollisionUntilSetupRuns() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        CluckoidBadnikInstance cluckoid = cluckoid();
        TestablePlayableSprite player = player(0x260, 0x120);

        cluckoid.update(0, player);

        assertEquals("IDLE", cluckoid.getStateName());
        assertEquals(0, cluckoid.getCollisionFlags(),
                "Obj_WaitOffscreen returns before SetUp_ObjAttributesSlotted while the Cluckoid is off-screen");

        putCluckoidOnScreen();
        cluckoid.update(1, player);

        assertEquals("IDLE", cluckoid.getStateName(),
                "Obj_WaitOffscreen restores the normal object op and returns before Cluckoid init");
        assertEquals(0, cluckoid.getCollisionFlags());

        cluckoid.update(2, player);

        assertEquals("IDLE", cluckoid.getStateName(),
                "loc_8E1AA runs SetUp_ObjAttributesSlotted before the first player detection frame");
        assertEquals(0x1A, cluckoid.getCollisionFlags());

        cluckoid.update(3, player);

        assertEquals("BREATHING", cluckoid.getStateName());
    }

    @Test
    void breathDebrisSettlesIntoSharedMhzPollenFloatRoutine() {
        CluckoidBadnikInstance.BreathDebrisChild debris = new CluckoidBadnikInstance.BreathDebrisChild(
                new ObjectSpawn(0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0),
                0x01F0,
                0x0148,
                -0x0600,
                -0x0180,
                0x20,
                true,
                false);
        debris.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        AbstractObjectInstance.updateCameraBounds(0x0100, 0x0100, 0x0300, 0x0200, 0);

        int frame = 0;
        while (frame < 80 && !debris.isFloatingRoutineForTest()) {
            debris.update(frame, null);
            frame++;
        }

        assertFalse(debris.isDestroyed(),
                "loc_8E294 switches Cluckoid breath debris to loc_3DBE0 instead of deleting it");
        assertTrue(debris.isFloatingRoutineForTest());
        assertEquals(0x78, debris.getYVelocity(),
                "loc_8E278 leaves y_vel unchanged when the next +8 step would reach $80");

        debris.update(frame, null);

        assertEquals(0, debris.getXVelocity(),
                "loc_3DBE0 starts with angle 0, so GetSineCosine writes x_vel=0 on the first floating frame");
        assertEquals(0x7A, debris.getYVelocity(),
                "loc_3DBE0 uses $34=2, so y_vel advances by 2 per floating frame after the transition");
    }

    @Test
    void offscreenBreathDebrisDeletesBeforeMotionLikeRomRenderFlagsBranch() {
        CluckoidBadnikInstance.BreathDebrisChild debris = new CluckoidBadnikInstance.BreathDebrisChild(
                new ObjectSpawn(0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0),
                0x0500,
                0x0500,
                -0x0600,
                -0x0180,
                0x20,
                true,
                false);
        debris.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0100, 0x0100, 0);

        debris.update(0, null);

        assertTrue(debris.isDestroyed(),
                "loc_8E250 branches to Delete_Current_Sprite immediately when render_flags is non-negative");
        assertEquals(0x0500, debris.getX(),
                "The offscreen branch runs before sub_3DC3A/MoveSprite2, so x_pos must not advance");
        assertEquals(0x0500, debris.getY(),
                "The offscreen branch runs before sub_3DC3A/MoveSprite2, so y_pos must not advance");
        assertEquals(-0x0600, debris.getXVelocity());
        assertEquals(-0x0180, debris.getYVelocity());
    }

    @Test
    void breathDebrisAnimatesMappingFrameBeforeFirstMoveLikeRomSub3Dc3A() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_POLLEN_SPRING)).thenReturn(renderer);
        CluckoidBadnikInstance.BreathDebrisChild debris = new CluckoidBadnikInstance.BreathDebrisChild(
                new ObjectSpawn(0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0),
                0x0200,
                0x0120,
                0x0600,
                -0x0180,
                -0x20,
                false,
                false);
        debris.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        }.withGameState(mock(GameStateManager.class)));
        AbstractObjectInstance.updateCameraBounds(0x0100, 0x0100, 0x0300, 0x0200, 0);

        debris.update(0, null);
        debris.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(1, 0x0205, 0x011E, false, false, -1, true);
    }

    @Test
    void floatingBreathDebrisUsesSharedPollenOffscreenCleanup() {
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        registry.install(runtimeState);
        assertTrue(runtimeState.tryReservePollenParticle());
        CluckoidBadnikInstance.BreathDebrisChild debris = new CluckoidBadnikInstance.BreathDebrisChild(
                new ObjectSpawn(0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 0, false, 0),
                0x01F0,
                0x0148,
                -0x0600,
                -0x0180,
                0x20,
                true,
                false);
        debris.setServices(new TestObjectServices()
                .withZoneRuntimeRegistry(registry)
                .withGameState(mock(GameStateManager.class)));
        AbstractObjectInstance.updateCameraBounds(0x0100, 0x0100, 0x0300, 0x0200, 0);
        int frame = 0;
        while (frame < 80 && !debris.isFloatingRoutineForTest()) {
            debris.update(frame, null);
            frame++;
        }
        assertTrue(debris.isFloatingRoutineForTest());

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0100, 0x0100, 0);
        debris.update(frame, null);

        assertEquals(0x7F00, debris.getX(),
                "loc_3DBE0 writes x_pos=$7F00 when render_flags reports the shared pollen routine off-screen");
        assertEquals(0, runtimeState.pollenParticleCount(),
                "loc_3DBE0 decrements MHZ_pollen_counter even when reached by Cluckoid breath debris");
    }

    private static CluckoidBadnikInstance cluckoid() {
        CluckoidBadnikInstance cluckoid = new CluckoidBadnikInstance(new ObjectSpawn(
                0x220, 0x120, Sonic3kObjectIds.CLUCKOID, 0, 1, false, 0));
        cluckoid.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        return cluckoid;
    }

    private static TestablePlayableSprite player(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static void assertBreathWindDoesNotMovePlayer(TestablePlayableSprite player, String message) {
        putCluckoidOnScreen();
        CluckoidBadnikInstance cluckoid = cluckoid();
        int startX = player.getCentreX();

        for (int frame = 0; frame <= 0x5B; frame++) {
            cluckoid.update(frame, player);
        }

        assertTrue(cluckoid.isWindActive());
        assertEquals(startX, player.getCentreX(), message);
    }

    private static void putCluckoidOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x180, 0x80, 0x2C0, 0x180, 0);
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<Integer> soundIds = new ArrayList<>();

        @Override
        public RecordingServices withGameState(GameStateManager gameState) {
            super.withGameState(gameState);
            return this;
        }

        @Override
        public void playSfx(int soundId) {
            soundIds.add(soundId);
        }
    }

    private static final class MhzRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }
}
