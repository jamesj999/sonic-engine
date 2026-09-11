package com.openggf.level.rings;

import com.openggf.game.rules.GameRules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.camera.Camera;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLostRingObjectInstance {

    @Test
    void deferredObj37OwnerClearsRingsOnItsFirstExecution() {
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        player.setRingCount(11);
        LostRingObjectInstance owner = LostRingObjectInstance.forTest(
                0x100, 0x100, 0, 0, 0, 0xFF);
        owner.clearMainPlayerRingsOnFirstUpdate();

        assertEquals(11, player.getRingCount(),
                "allocation into an already-passed SST slot must not clear Ring_count immediately");

        owner.update(1, player);

        assertEquals(0, player.getRingCount(),
                "Obj37_Init clears Ring_count when the deferred owner slot first executes");
    }

    @Test
    void spilledRingCarriesRomDisplayPriority() {
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                0x48A0, 0x02B4, 0, 0, 0, 0xFF);

        assertTrue(ring.isHighPriority(),
                "Obj_Bouncing_Ring uses make_art_tile(ArtTile_Ring,1,1)");
        assertEquals(3, ring.getPriorityBucket(),
                "Obj_Bouncing_Ring priority $180 maps to display bucket 3");
    }

    @Test
    void eagerRemainderCanWaitForDeferredOwnerInitializationPass() {
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                0x100, 0x100, 0x0200, -0x0400, 0, 0xFF);
        ring.deferFirstUpdateUntilOwnerPass();

        ring.update(1, null);
        assertEquals(0x100, ring.getX(),
                "An eagerly allocated child must not move before the behind-cursor owner initializes");

        ring.update(2, null);
        assertEquals(0x102, ring.getX());
    }

    @Test
    void obj37DoesNotUseSharedXAxisOutOfRangeMacro() {
        // S1 Obj37 RLoss_Bounce does not call the shared out_of_range macro; it deletes only when the
        // shared spill animation timer expires or y_pos passes v_limitbtm2 + 224
        // (docs/s1disasm/s1disasm/_incObj/25, 37 Rings.asm).
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                0x067E, 0x0426, -0x0238, 0x09E8, 0x58, 0xFF);

        assertTrue(ring.usesCustomOutOfRangeCheck());
        assertFalse(ring.isCustomOutOfRange(0x0764),
                "Obj37 must not be deleted by the standard X-only out_of_range macro");
    }

    @Test
    void spillAnimationDeceleratesLikeRom() {
        // ROM ChangeRingFrame: accum += counter each frame; frame = (accum >> 9) & 3;
        // counter decrements; counter starts at 0xFF.
        SpillAnimationState anim = new SpillAnimationState();
        anim.reset();                 // counter=0xFF, accum=0, frame=0
        assertEquals(0xFF, anim.counter());
        anim.tick();                  // accum = 0xFF; frame = (0xFF>>9)&3 = 0; counter=0xFE
        assertEquals(0, anim.frame());
        assertEquals(0xFE, anim.counter());
        // advance enough to roll bits 10:9
        for (int i = 0; i < 3; i++) anim.tick();
        // accum after 4 ticks = 0xFF+0xFE+0xFD+0xFC = 0x03FA; (0x03FA>>9)&3 = 1
        assertEquals(1, anim.frame());
    }

    @Test
    void lostRingBoundaryCheckCadenceUsesTypedGameRule() {
        assertFalse(GameRules.SONIC_1.ring().lostRingBoundaryChecksOnlyOnProbeCadence());
        assertFalse(GameRules.SONIC_2.ring().lostRingBoundaryChecksOnlyOnProbeCadence());
        assertTrue(GameRules.SONIC_3K.ring().lostRingBoundaryChecksOnlyOnProbeCadence());

        SpillAnimationState expired = new SpillAnimationState();
        BoundaryCadenceRing s3kRising = new BoundaryCadenceRing(
                0x100, 0x100, -0x0400, 0, true, expired);

        s3kRising.update(0, null);

        assertFalse(s3kRising.isDestroyed(),
                "S3K Obj_Bouncing_Ring branches around the counter check while rising");

        BoundaryCadenceRing s3kFallingOffCadence = new BoundaryCadenceRing(
                0x100, 0x100, 0x0400, 1, true, expired);
        s3kFallingOffCadence.update(0, null);
        assertFalse(s3kFallingOffCadence.isDestroyed(),
                "S3K Obj_Bouncing_Ring branches around the counter check off cadence");

        BoundaryCadenceRing s3kFallingOnCadence = new BoundaryCadenceRing(
                0x100, 0x100, 0x0400, 0, true, expired);
        s3kFallingOnCadence.update(0, null);
        assertTrue(s3kFallingOnCadence.isDestroyed(),
                "S3K expires the ring once its cadence reaches the boundary check");

        BoundaryCadenceRing s1S2Rising = new BoundaryCadenceRing(
                0x100, 0x100, -0x0400, 0, false, expired);
        s1S2Rising.update(0, null);
        assertTrue(s1S2Rising.isDestroyed(),
                "S1/S2 rising branches still reach their boundary checks");

        BoundaryCadenceRing s1S2FallingOffCadence = new BoundaryCadenceRing(
                0x100, 0x100, 0x0400, 1, false, expired);
        s1S2FallingOffCadence.update(0, null);
        assertTrue(s1S2FallingOffCadence.isDestroyed(),
                "S1/S2 off-cadence branches still reach their boundary checks");
    }

    @Test
    void floorCheckCadenceReadsGameRulesMaskS1EveryFourFramesS2EveryEight() {
        // ROM: per-game floor-check cadence (relocated from RingManager.LostRingPool.updatePhysics,
        // RingManager.java:1242-1248). S1 probes every 4 frames (andi.b #3), S2/S3K every 8 (andi.b #7).
        // The object must consult GameRules.ringFloorCheckMask(), not a hardcoded constant.
        ProbeRecordingRing s1Ring = new ProbeRecordingRing(0x100, 0x100, 0, 0x0400,
                /*mask*/GameRules.SONIC_1.ring().ringFloorCheckMask(),
                /*reverseGravity*/false);
        // phaseOffset 0: probe fires when (vbla & mask) == 0.
        s1Ring.setVblaForTest(4);   // 4 & 3 == 0 → S1 probes; would NOT probe under S2 mask (4 & 7 == 4)
        s1Ring.stepPhysicsForTest(0x18, true);
        assertEquals(1, s1Ring.floorProbeCount, "S1 (#3 mask) must probe the floor on frame 4");

        ProbeRecordingRing s2Ring = new ProbeRecordingRing(0x100, 0x100, 0, 0x0400,
                /*mask*/GameRules.SONIC_2.ring().ringFloorCheckMask(),
                /*reverseGravity*/false);
        s2Ring.setVblaForTest(4);   // 4 & 7 == 4 → S2 does NOT probe on frame 4
        s2Ring.stepPhysicsForTest(0x18, true);
        assertEquals(0, s2Ring.floorProbeCount, "S2 (#7 mask) must NOT probe the floor on frame 4");
        s2Ring.setVblaForTest(8);   // 8 & 7 == 0 → S2 probes
        s2Ring.stepPhysicsForTest(0x18, true);
        assertEquals(1, s2Ring.floorProbeCount, "S2 (#7 mask) must probe the floor on frame 8");
    }

    @Test
    void s3kObj37CounterPhaseModelsNativeVIntVisibility() {
        assertEquals(0, GameRules.SONIC_1.ring().ringFloorCheckCounterPhase());
        assertEquals(0, GameRules.SONIC_2.ring().ringFloorCheckCounterPhase());
        assertEquals(4, GameRules.SONIC_3K.ring().ringFloorCheckCounterPhase(),
                "live S3K Obj37 starts four V-int counts ahead of the gameplay-scoped object clock");
    }

    @Test
    void s3kReverseGravityProbesCeilingNotFloor() {
        // ROM: S3K Reverse_gravity_flag routes Obj37 to Obj_Bouncing_Ring_Reverse_Gravity,
        // which probes the CEILING (RingCheckFloorDist_ReverseGravity, upward) and only when
        // yVel <= 0 (rising). Relocated from RingManager.java:1271-1294.
        ProbeRecordingRing ring = new ProbeRecordingRing(0x100, 0x100, 0, /*yVel rising*/-0x0400,
                /*mask*/GameRules.SONIC_2.ring().ringFloorCheckMask(),
                /*reverseGravity*/true);
        ring.setVblaForTest(0);     // 0 & 7 == 0 → probe fires this frame
        ring.stepPhysicsForTest(0x18, true);
        assertEquals(1, ring.ceilingProbeCount, "S3K reverse gravity must probe the ceiling");
        assertEquals(0, ring.floorProbeCount, "S3K reverse gravity must NOT probe the floor");
    }

    @Test
    void offscreenLostRingSkipsTerrainProbeUntilRenderFlagSet() {
        // S2/S3K Obj37 checks render_flags bit 7 before RingCheckFloorDist
        // (s2.asm:25215-25217): off-screen rings keep moving, but do not
        // bounce on terrain until the render pass has marked them visible.
        ProbeRecordingRing ring = new ProbeRecordingRing(0x100, 0x100, 0, 0x0400,
                /*mask*/GameRules.SONIC_2.ring().ringFloorCheckMask(),
                /*reverseGravity*/false,
                /*renderFlagForFloorProbe*/false);
        ring.setVblaForTest(0);

        ring.stepPhysicsForTest(0x18, true);

        assertEquals(0, ring.floorProbeCount,
                "cadence-hit Obj37 must still skip terrain while render_flags bit 7 is clear");
    }

    @Test
    void s2LostRingFloorProbeUsesLatchedPriorBuildSpritesRenderFlag() {
        // S2 Obj37_Main reads render_flags bit 7 before RingCheckFloorDist (s2.asm:25215-25217).
        // BuildSprites clears and refreshes that bit after DisplaySprite using width_pixels=8 and
        // the assumed 32 px Y band (s2.asm:30560-30588), so the floor probe must observe the
        // previously latched bit, not a same-step visibility recomputation after movement.
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        LatchedRenderProbeRing ring = new LatchedRenderProbeRing(
                0x100, 0x00FF, 0, 0x0200);

        ring.update(0, null);
        ring.refreshPostCameraRenderState();

        assertEquals(1, ring.floorProbeCount,
                "Obj37_Init starts render_flags at $84, so the first cadence hit may probe");
        assertFalse(ring.renderFlagForTest(),
                "post-step BuildSprites should clear bit 7 once y_pos reaches the assumed-height band edge");

        ring.update(1, null);

        assertEquals(1, ring.floorProbeCount,
                "the next object step must consume the latched clear bit and skip terrain");
    }

    @Test
    void s3kLostRingRenderFlagUsesZeroHeightPixels() {
        // S3K Render_Sprites reads height_pixels directly. Obj_Bouncing_Ring
        // initializes y_radius/x_radius but leaves height_pixels zero, so a ring
        // below the 224-line viewport clears bit 7 without S1/S2's 32 px band.
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        LatchedRenderProbeRing ring = new LatchedRenderProbeRing(
                0x100, 0x00E0, 0, 0, GameRules.SONIC_3K.ring().lostRingRenderYMargin());

        ring.update(0, null);
        ring.refreshPostCameraRenderState();

        assertFalse(ring.renderFlagForTest(),
                "S3K Obj37 height_pixels remains zero, making the viewport bottom exclusive");
    }

    @Test
    void s1OffscreenLostRingStillProbesTerrain() {
        // S1 RLoss_Bounce calls ObjFloorDist directly after the vblank cadence gate; unlike S2/S3K,
        // there is no render_flags bit-7 check before the floor probe.
        ProbeRecordingRing ring = new ProbeRecordingRing(0x100, 0x100, 0, 0x0400,
                /*mask*/GameRules.SONIC_1.ring().ringFloorCheckMask(),
                /*reverseGravity*/false,
                /*renderFlagForFloorProbe*/false,
                /*requiresRenderFlagForFloorProbe*/false);
        ring.setVblaForTest(0);

        ring.stepPhysicsForTest(0x18, true);

        assertEquals(1, ring.floorProbeCount,
                "S1 Obj37 must probe terrain even when the render flag is clear");
    }

    @Test
    void floorProbeUsesSharedFindFloorDistanceForBounce() {
        // ROM RingCheckFloorDist branches through Ring_FindFloor, including
        // extension/regression paths for slopes and negative height metrics. Obj37
        // must consume that shared object terrain distance instead of a local
        // one-tile shortcut, or shallow/sloped terrain bounces too low.
        TerrainBackedRing ring = new TerrainBackedRing(
                0x4512, 0x07E1, 0x0200, 0x0D1E,
                GameRules.SONIC_2.ring().ringFloorCheckMask(),
                false,
                true);
        ring.setVblaForTest(0);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x4514, 0x07F6))
                    .thenReturn(new TerrainCheckResult(-23, (byte) 0, 0x1F));

            ring.stepPhysicsForTest(0x18, true);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x4514, 0x07F6));
        }

        assertEquals(0x4514, ring.getX());
        assertEquals(0x07D7, ring.getY(),
                "Obj37 should apply the full Ring_FindFloor penetration distance");
        assertEquals(0xFFFFF617, ring.getYVelForTest(),
                "Obj37 bounce velocity follows y_vel -= y_vel>>2; neg.w y_vel");
    }

    @Test
    void s3kRingFindFloorIncludesActiveBackgroundCollisionPlane() {
        TerrainBackedRing ring = new TerrainBackedRing(
                0x3A00, 0x0910, 0, 0,
                GameRules.SONIC_3K.ring().ringFloorCheckMask(), false, true);
        GameStateManager gameState = mock(GameStateManager.class);
        Camera camera = mock(Camera.class);
        LevelManager levelManager = mock(LevelManager.class);
        Level level = mock(Level.class);
        when(gameState.isBackgroundCollisionFlag()).thenReturn(true);
        when(camera.getX()).thenReturn((short) 0x3900);
        when(camera.getY()).thenReturn((short) 0x08E0);
        when(levelManager.getCurrentLevel()).thenReturn(level);
        when(level.hasBackgroundCollisionRowAt(0x0918)).thenReturn(true);
        ring.setServices(new StubObjectServices() {
            @Override public GameStateManager gameState() { return gameState; }
            @Override public Camera camera() { return camera; }
            @Override public LevelManager levelManager() { return levelManager; }
        });

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x3A00, 0x0918))
                    .thenReturn(new TerrainCheckResult(4, (byte) 0, 0));
            terrain.when(() -> ObjectTerrainUtils.checkFloorDistOnLayer(
                            0x3A00, 0x0918, (byte) 1))
                    .thenReturn(new TerrainCheckResult(-7, (byte) 0, 1));

            assertEquals(-7, ring.probeFloorForTest(0x3A00, 0x0918),
                    "Ring_FindFloor must retain the more penetrating BG-plane result");
        }
    }

    /**
     * Captures which per-game probe path the object takes given an injected floor-check mask and
     * reverse-gravity flag — the feature-set-driven cadence decision is the unit under test, so the
     * actual level collision distance is stubbed to 0 (no terrain).
     */
    private static final class ProbeRecordingRing extends LostRingObjectInstance {
        private final int mask;
        private final boolean reverseGravity;
        private final boolean renderFlagForFloorProbe;
        private final boolean requiresRenderFlagForFloorProbe;
        private int vbla;
        int floorProbeCount;
        int ceilingProbeCount;

        private ProbeRecordingRing(int xPixel, int yPixel, int xVel, int yVel,
                                   int mask, boolean reverseGravity) {
            this(xPixel, yPixel, xVel, yVel, mask, reverseGravity, true);
        }

        private ProbeRecordingRing(int xPixel, int yPixel, int xVel, int yVel,
                                   int mask, boolean reverseGravity, boolean renderFlagForFloorProbe) {
            this(xPixel, yPixel, xVel, yVel, mask, reverseGravity, renderFlagForFloorProbe, true);
        }

        private ProbeRecordingRing(int xPixel, int yPixel, int xVel, int yVel,
                                   int mask, boolean reverseGravity, boolean renderFlagForFloorProbe,
                                   boolean requiresRenderFlagForFloorProbe) {
            super(new ObjectSpawn(xPixel & 0xFFFF, yPixel & 0xFFFF, 0x37, 0, 0, false, 0));
            initFixedPointForTest(xPixel, yPixel, xVel, yVel, 0, 0xFF);
            this.mask = mask;
            this.reverseGravity = reverseGravity;
            this.renderFlagForFloorProbe = renderFlagForFloorProbe;
            this.requiresRenderFlagForFloorProbe = requiresRenderFlagForFloorProbe;
        }

        void setVblaForTest(int vbla) {
            this.vbla = vbla;
        }

        @Override
        protected int resolveFloorCheckMask() {
            return mask;
        }

        @Override
        protected boolean isReverseGravityActive() {
            return reverseGravity;
        }

        @Override
        protected boolean hasRomRenderFlagForFloorProbe() {
            return renderFlagForFloorProbe;
        }

        @Override
        protected boolean ringFloorProbeRequiresRenderFlag() {
            return requiresRenderFlagForFloorProbe;
        }

        @Override
        protected int resolveVblaCounter() {
            return vbla;
        }

        @Override
        protected int ringCheckFloorDist(int x, int y) {
            floorProbeCount++;
            return 0;
        }

        @Override
        protected int ringCheckCeilingDist(int x, int y) {
            ceilingProbeCount++;
            return 0;
        }
    }

    private static final class BoundaryCadenceRing extends LostRingObjectInstance {
        private final boolean cadenceOnly;

        private BoundaryCadenceRing(int xPixel, int yPixel, int yVel, int phase,
                                    boolean cadenceOnly, SpillAnimationState spillAnimation) {
            super(new ObjectSpawn(xPixel & 0xFFFF, yPixel & 0xFFFF, 0x37, 0, 0, false, 0));
            initFixedPointForTest(xPixel, yPixel, 0, yVel, phase, 0xFF);
            this.cadenceOnly = cadenceOnly;
            setSpillAnimation(spillAnimation);
        }

        @Override
        protected int resolveFloorCheckMask() {
            return 7;
        }

        @Override
        protected int resolveVblaCounter() {
            return 0;
        }

        @Override
        protected boolean lostRingBoundaryChecksOnlyOnProbeCadence() {
            return cadenceOnly;
        }
    }

    private static final class LatchedRenderProbeRing extends LostRingObjectInstance {
        int floorProbeCount;
        private final int renderYMargin;

        private LatchedRenderProbeRing(int xPixel, int yPixel, int xVel, int yVel) {
            this(xPixel, yPixel, xVel, yVel, GameRules.SONIC_2.ring().lostRingRenderYMargin());
        }

        private LatchedRenderProbeRing(int xPixel, int yPixel, int xVel, int yVel,
                                       int renderYMargin) {
            super(new ObjectSpawn(xPixel & 0xFFFF, yPixel & 0xFFFF, 0x37, 0, 0, false, 0));
            initFixedPointForTest(xPixel, yPixel, xVel, yVel, 0, 0xFF);
            this.renderYMargin = renderYMargin;
        }

        boolean renderFlagForTest() {
            return hasRomRenderFlagForFloorProbe();
        }

        @Override
        protected int resolveFloorCheckMask() {
            return GameRules.SONIC_2.ring().ringFloorCheckMask();
        }

        @Override
        protected int resolveVblaCounter() {
            return 0;
        }

        @Override
        protected int resolveLostRingRenderYMargin() {
            return renderYMargin;
        }

        @Override
        protected int ringCheckFloorDist(int x, int y) {
            floorProbeCount++;
            return 0;
        }
    }

    @Test
    void ringBouncePhysicsMatchesLegacyPool() {
        // Fixed-point contract (identical to LostRing.reset, RingManager LostRing.java:24):
        //   xSubpixel = x << 8 (pixel coordinate stored in the high byte; low byte = sub-pixel).
        // forTest(x, y, ...) constructs with xSubpixel = x << 8, ySubpixel = y << 8.
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                /*xPixel*/0x100, /*yPixel*/0x100, /*xVel*/0x0200, /*yVel*/-0x0400, /*phase*/0, /*lifetime*/0xFF);
        assertEquals(0x100 << 8, ring.getXSubpixelForTest());      // 0x10000 at start
        ring.stepPhysicsForTest(/*gravity*/0x18, /*floorCheck*/false);
        // ROM step (LostRingPool.updatePhysics, RingManager.java:1245-1247):
        //   xSubpixel += xVel;  ySubpixel += yVel;  yVel += gravity.
        assertEquals((0x100 << 8) + 0x0200, ring.getXSubpixelForTest()); // 0x10200
        assertEquals((0x100 << 8) + (-0x0400), ring.getYSubpixelForTest()); // 0x0FC00
        assertEquals(-0x0400 + 0x18, ring.getYVelForTest());
    }

    @Test
    void appendRenderCommandsDrawsMovingLostRingObjectWithSharedSpillFrame() {
        RingManager ringManager = spy(buildRingManagerWithLevelManager(null));
        SpillAnimationState animation = new SpillAnimationState();
        animation.reset();
        for (int i = 0; i < 4; i++) {
            animation.tick();
        }
        assertEquals(1, animation.frame(), "test setup should advance shared spill frame");

        LostRingObjectInstance ring = LostRingObjectInstance.spawn(
                0x120, 0x140, 0, 0, 2, 0xFF, animation);
        ring.setServices(new StubObjectServices() {
            @Override
            public RingManager ringManager() {
                return ringManager;
            }
        });

        ring.appendRenderCommands(new ArrayList<>());

        verify(ringManager).drawRingFrameAt(0x120, 0x140, 3);
        verify(ringManager, never()).drawRingAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void appendRenderCommandsDrawsEachMovedLostRingObjectWithItsOwnPhase() {
        RingManager ringManager = spy(buildRingManagerWithLevelManager(null));
        SpillAnimationState animation = new SpillAnimationState();
        animation.reset();
        for (int i = 0; i < 4; i++) {
            animation.tick();
        }
        assertEquals(1, animation.frame(), "test setup should advance shared spill frame");

        LostRingObjectInstance rightMovingRing = lostRingWithRingManager(
                0x120, 0x140, 0x0200, 0, 0, animation, ringManager);
        LostRingObjectInstance leftMovingRing = lostRingWithRingManager(
                0x120, 0x140, -0x0300, 0, 2, animation, ringManager);

        rightMovingRing.stepPhysicsForTest(0x18, false);
        leftMovingRing.stepPhysicsForTest(0x18, false);
        rightMovingRing.appendRenderCommands(new ArrayList<>());
        leftMovingRing.appendRenderCommands(new ArrayList<>());

        verify(ringManager).drawRingFrameAt(0x122, 0x140, 1);
        verify(ringManager).drawRingFrameAt(0x11D, 0x140, 3);
        verify(ringManager, never()).drawRingAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void ringManagerDoesNotDrawRetiredLegacyLostRingPoolAtSpawnPoint() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = spy(buildRingManagerWithLevelManager(levelManager));
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        ringManager.spawnLostRings(player, 3, 0);

        ringManager.drawLostRings(12);

        verify(ringManager, never()).drawRingFrameAt(anyInt(), anyInt(), anyInt());
        verify(ringManager, never()).drawRingAt(anyInt(), anyInt(), anyInt());
    }

    @BeforeEach
    void setUpEngine() {
        TestEnvironment.resetAll();
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDownEngine() {
        GraphicsManager.getInstance().resetState();
        SessionManager.clear();
    }

    @Test
    void spawnRegistersRingObjectsInSlotOrder() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 4, 0);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(4, rings.size(), "spawnLostRings should register 4 LostRingObjectInstances");
        for (int i = 1; i < rings.size(); i++) {
            assertTrue(rings.get(i).getSlotIndex() > rings.get(i - 1).getSlotIndex(),
                    "lost-ring objects must occupy ascending slots");
        }
        assertEquals(SpillAnimationState.INITIAL_COUNTER, ringManager.getSpillAnimationState().counter(),
                "spawn should reset the shared spill-spin counter to 0xFF");
    }

    @Test
    void spawnPhaseUsesObjectLoopCountdownForS1S2Layout() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 1, 0);

        LostRingObjectInstance ring = objectManager.activeObjectsOfType(LostRingObjectInstance.class).get(0);
        assertEquals(127 - ring.getSlotIndex(), ring.getPhaseOffset(),
                "S1/S2 128-slot object loops keep the legacy 127-slot countdown phase");
    }

    @Test
    void spawnPhaseUsesS3kObjectLoopCountdown() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 1, 0);

        LostRingObjectInstance ring = objectManager.activeObjectsOfType(LostRingObjectInstance.class).get(0);
        assertEquals(ObjectSlotLayout.SONIC_3K.lastProcessSlotExclusive() - 1 - ring.getSlotIndex(),
                ring.getPhaseOffset(),
                "S3K Obj37 cadence uses the full Process_Sprites Object_RAM countdown");
    }

    @Test
    void s2SpawnUsesPreallocatedObj37OwnerSlot() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_2), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        for (int slot = 16; slot <= 22; slot++) {
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 3, 0);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(3, rings.size());
        assertEquals(23, rings.get(0).getSlotIndex(),
                "S2 HurtCharacter preallocates the first Obj37 owner slot before Obj37_Init");
        assertFalse(rings.get(0).usesCurrentTouchResponseState(),
                "S2's plain object scan keeps the ordinary pre-update touch snapshot");
        assertTrue(rings.get(1).getSlotIndex() > rings.get(0).getSlotIndex(),
                "with no lower holes, subsequent S2 lost rings occupy later slots");
    }

    @Test
    void s2LostRingRemainderUsesPlainAllocateObject() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_2), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        for (int slot = 16; slot <= 22; slot++) {
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
        assertTrue(objectManager.reserveDynamicSlot(30), "setup should reserve the owner slot");
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 3, 0, player.getCentreX(), player.getCentreY(), 30);

        List<LostRing> rings = ringManager.getActiveLostRings();
        assertEquals(3, rings.size());
        assertEquals(30, rings.get(0).getSlotIndex(),
                "S2 ring 0 uses the HurtCharacter-preallocated Obj37 owner slot");
        assertEquals(23, rings.get(1).getSlotIndex(),
                "S2 Obj37_Init calls plain AllocateObject for ring 1, not AllocateObjectAfterCurrent");
        assertEquals(24, rings.get(2).getSlotIndex(),
                "S2 plain AllocateObject continues from the lowest free dynamic slot");
    }

    @Test
    void s3kSpawnUsesPreallocatedObj37OwnerSlot() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        for (int slot = 4; slot <= 8; slot++) {
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 3, 0);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(3, rings.size());
        assertEquals(9, rings.get(0).getSlotIndex(),
                "S3K HurtCharacter preallocates the first Obj37 owner slot before Obj37_Init");
        assertTrue(rings.stream().allMatch(LostRingObjectInstance::usesCurrentTouchResponseState),
                "S3K's Collision_response_list retains live pointers for the whole after-current chain");
        assertTrue(rings.get(1).getSlotIndex() > rings.get(0).getSlotIndex(),
                "subsequent S3K lost rings allocate after the owner slot");
    }

    @Test
    void s3kLostRingRemainderUsesAllocateObjectAfterCurrent() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        for (int slot = 4; slot <= 8; slot++) {
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
        assertTrue(objectManager.reserveDynamicSlot(30), "setup should reserve the owner slot");
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRings(player, 3, 0, player.getCentreX(), player.getCentreY(), 30);

        List<LostRing> rings = ringManager.getActiveLostRings();
        assertEquals(3, rings.size());
        assertEquals(30, rings.get(0).getSlotIndex(),
                "S3K ring 0 uses the HurtCharacter-preallocated owner slot");
        assertEquals(31, rings.get(1).getSlotIndex(),
                "S3K Obj_Bouncing_Ring allocates ring 1 after the owner slot");
        assertEquals(32, rings.get(2).getSlotIndex(),
                "S3K Obj_Bouncing_Ring continues after the previous ring slot");
    }

    @Test
    void s3kPendingHurtReservesWholeAfterCurrentAllocationSnapshot() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        for (int slot = 4; slot <= 8; slot++) {
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
        assertTrue(objectManager.reserveDynamicSlot(10), "setup should leave a hole after the owner");
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        player.setRingCount(3);

        levelManager.spawnLostRingsAfterCurrentFrame(player, 0);

        assertEquals(9, objectManager.getAllocatedSlotCount(),
                "HurtCharacter must reserve owner plus both S3K remainder slots immediately");
        assertFalse(objectManager.reserveDynamicSlot(9), "owner slot should already be reserved");
        assertFalse(objectManager.reserveDynamicSlot(11), "first after-current slot should already be reserved");
        assertFalse(objectManager.reserveDynamicSlot(12), "second after-current slot should already be reserved");
    }

    @Test
    void delayedS3kSpawnConsumesReservedSnapshotWithoutFreshHoles() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);
        assertTrue(objectManager.reserveDynamicSlot(30));
        assertTrue(objectManager.reserveDynamicSlot(31));
        assertTrue(objectManager.reserveDynamicSlot(33));
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRingsWithInitialObjectStep(
                player, 3, 1, player.getCentreX(), player.getCentreY(),
                new int[] {30, 31, 33}, true);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(List.of(30, 31, 33),
                rings.stream().map(LostRingObjectInstance::getSlotIndex).toList());
        assertTrue(objectManager.reserveDynamicSlot(32),
                "deferred materialization must not consume a hole freed after the reservation snapshot");
    }

    @Test
    void delayedSpawnVariantAppliesInitialObj37MovementStep() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        ringManager.spawnLostRings(player, 1, 0);
        LostRingObjectInstance baseline =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class).get(0);
        int baselineXSub = baseline.getXSubpixelForTest();
        int baselineYSub = baseline.getYSubpixelForTest();
        int baselineXVel = baseline.getXVelForTest();
        int baselineYVel = baseline.getYVelForTest();

        LevelManager steppedLevelManager = GameServices.level();
        ObjectManager steppedObjectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(steppedLevelManager, "objectManager", steppedObjectManager);

        RingManager steppedRingManager = buildRingManagerWithLevelManager(steppedLevelManager);
        setField(steppedLevelManager, "ringManager", steppedRingManager);

        SpawnTestPlayableSprite steppedPlayer = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        steppedRingManager.spawnLostRingsWithInitialObjectStep(
                steppedPlayer, 1, 0, steppedPlayer.getCentreX(), steppedPlayer.getCentreY(), -1);
        LostRingObjectInstance stepped =
                steppedObjectManager.activeObjectsOfType(LostRingObjectInstance.class).get(0);

        assertEquals(baselineXSub + baselineXVel, stepped.getXSubpixelForTest(),
                "S3K delayed Obj37 materialization must catch up the init fall-through MoveSprite2 step");
        assertEquals(baselineYSub + baselineYVel, stepped.getYSubpixelForTest());
        assertEquals(baselineYVel + 0x18, stepped.getYVelForTest(),
                "Obj37_Main applies gravity after the same-frame position update");
        assertTrue(stepped.usesCurrentTouchResponseState(),
                "a deferred Obj37 step publishes a live post-movement position to the next touch pass");
    }

    @Test
    void forcedDeferredOwnerClearRetainsPreviousPublishedTouchPosition() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        ringManager.spawnLostRingsWithInitialObjectStep(
                player, 1, 0, player.getCentreX(), player.getCentreY(),
                new int[0], false, true);

        LostRingObjectInstance ring =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class).get(0);
        assertFalse(ring.usesCurrentTouchResponseState(),
                "a behind-cursor deferred owner retains the prior published-position touch phase");
    }

    @Test
    void collectedSparkleDoesNotConsumeS3kCollisionResponseListCapacity() {
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                0x120, 0x180, 0, 0, 0, 0xFF);

        assertTrue(ring.publishesTouchResponseListEntryThisFrame());

        ring.markCollected(10);

        assertFalse(ring.publishesTouchResponseListEntryThisFrame(),
                "Obj37's collected sparkle routine does not call Add_SpriteToCollisionResponseList");
    }

    @Test
    void delayedS2SpawnSkipsInitialObj37StepForAlreadyPassedChildSlots() throws Exception {
        LevelManager baselineLevelManager = GameServices.level();
        ObjectManager baselineObjectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_2), 0, null, null);
        setField(baselineLevelManager, "objectManager", baselineObjectManager);
        reserveS2Arz2LateFreedLostRingLayout(baselineObjectManager);

        RingManager baselineRingManager = buildRingManagerWithLevelManager(baselineLevelManager);
        setField(baselineLevelManager, "ringManager", baselineRingManager);

        SpawnTestPlayableSprite baselinePlayer = new SpawnTestPlayableSprite((short) 0x13D2, (short) 0x043C);
        baselineRingManager.spawnLostRings(
                baselinePlayer, 6, 0, baselinePlayer.getCentreX(), baselinePlayer.getCentreY(), 56);
        List<LostRingObjectInstance> baseline =
                baselineObjectManager.activeObjectsOfType(LostRingObjectInstance.class);

        LevelManager steppedLevelManager = GameServices.level();
        ObjectManager steppedObjectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_2), 0, null, null);
        setField(steppedLevelManager, "objectManager", steppedObjectManager);
        reserveS2Arz2LateFreedLostRingLayout(steppedObjectManager);

        RingManager steppedRingManager = buildRingManagerWithLevelManager(steppedLevelManager);
        setField(steppedLevelManager, "ringManager", steppedRingManager);

        SpawnTestPlayableSprite steppedPlayer = new SpawnTestPlayableSprite((short) 0x13D2, (short) 0x043C);
        steppedRingManager.spawnLostRingsWithInitialObjectStep(
                steppedPlayer, 6, 0, steppedPlayer.getCentreX(), steppedPlayer.getCentreY(), 56);
        List<LostRingObjectInstance> stepped =
                steppedObjectManager.activeObjectsOfType(LostRingObjectInstance.class);

        assertEquals(6, baseline.size());
        assertEquals(6, stepped.size());
        for (int i = 0; i < stepped.size(); i++) {
            LostRingObjectInstance baselineRing = baseline.get(i);
            LostRingObjectInstance steppedRing = stepped.get(i);
            assertEquals(baselineRing.getSlotIndex(), steppedRing.getSlotIndex());
            if (steppedRing.getSlotIndex() < 56) {
                assertEquals(baselineRing.getXSubpixelForTest(), steppedRing.getXSubpixelForTest(),
                        "child Obj37 slots below the owner were already passed by ExecuteObjects");
                assertEquals(baselineRing.getYSubpixelForTest(), steppedRing.getYSubpixelForTest());
                assertEquals(baselineRing.getYVelForTest(), steppedRing.getYVelForTest());
            } else {
                assertEquals(baselineRing.getXSubpixelForTest() + baselineRing.getXVelForTest(),
                        steppedRing.getXSubpixelForTest(),
                        "owner-or-later Obj37 slots execute Obj37_Main in the spawn frame");
                assertEquals(baselineRing.getYSubpixelForTest() + baselineRing.getYVelForTest(),
                        steppedRing.getYSubpixelForTest());
                assertEquals(baselineRing.getYVelForTest() + 0x18, steppedRing.getYVelForTest());
            }
        }
    }

    @Test
    void spawnStopsOnAllocationFailureAndCapsAt32() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        // Pre-fill the dynamic slot pool so only 3 slots remain free.
        objectManager.reserveAllButNFreeSlots(3);
        ringManager.spawnLostRings(player, 10, 0);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(3, rings.size(),
                "spawn must stop on the first -1 allocation, leaving exactly the 3 allocatable rings");
        for (int i = 1; i < rings.size(); i++) {
            assertTrue(rings.get(i).getSlotIndex() > rings.get(i - 1).getSlotIndex(),
                    "successfully-allocated rings must occupy ascending slots");
        }
    }

    @Test
    void neverSpawnsMoreThanRomCapOf32() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);

        // Leave 64 free slots; request 50 → capped at the ROM 0x20 (32) limit.
        objectManager.reserveAllButNFreeSlots(64);
        ringManager.spawnLostRings(player, 50, 0);

        assertEquals(0x20, objectManager.activeObjectsOfType(LostRingObjectInstance.class).size(),
                "spilled rings must never exceed the ROM cap of 0x20 (32)");
    }

    private RingManager buildRingManagerWithLevelManager(LevelManager levelManager) {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);

        RingFrame frame = new RingFrame(List.of(new RingFramePiece(0, 0, 1, 1, 0, false, false, 0)));
        List<RingFrame> frames = new ArrayList<>();
        frames.add(frame);
        frames.add(frame);
        frames.add(frame);

        Pattern[] patterns = new Pattern[16];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = pattern;
        }

        RingSpriteSheet spriteSheet = new RingSpriteSheet(patterns, frames, 1, 1, 1, 2);
        RingManager ringManager = new RingManager(
                List.of(), spriteSheet, levelManager, null, GameServices.audio());
        ringManager.ensurePatternsCached(GraphicsManager.getInstance(), 0);
        return ringManager;
    }

    private LostRingObjectInstance lostRingWithRingManager(int xPixel, int yPixel, int xVel, int yVel,
                                                           int phaseOffset, SpillAnimationState animation,
                                                           RingManager ringManager) {
        LostRingObjectInstance ring = LostRingObjectInstance.spawn(
                xPixel, yPixel, xVel, yVel, phaseOffset, 0xFF, animation);
        ring.setServices(new StubObjectServices() {
            @Override
            public RingManager ringManager() {
                return ringManager;
            }
        });
        return ring;
    }

    @Test
    void s3kAfterCurrentSpillContinuesLogicallyPastManagedSlotExhaustion() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(),
                new NoOpObjectRegistry(ObjectSlotLayout.SONIC_3K), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(levelManager);
        setField(levelManager, "ringManager", ringManager);

        objectManager.reserveAllButNFreeSlots(3);
        SpawnTestPlayableSprite player = new SpawnTestPlayableSprite((short) 0x100, (short) 0x100);
        ringManager.spawnLostRings(player, 32, 0);

        List<LostRingObjectInstance> rings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(32, rings.size(), "S3K's logical after-current chain keeps the full ROM spill cap");
        assertEquals(3, rings.stream().filter(ring -> ring.getSlotIndex() >= 0).count());
        List<LostRingObjectInstance> logical = rings.stream()
                .filter(ring -> ring.getSlotIndex() < 0)
                .toList();
        assertEquals(29, logical.size());
        assertEquals(15, logical.get(0).getPhaseOffset(),
                "the first virtual entry continues after the ROM-probed physical slot 93 "
                        + "in the 110-slot process loop");
        assertEquals(14, logical.get(1).getPhaseOffset(),
                "successive virtual entries retain distinct Process_Sprites countdown phases");
    }

    private void reserveS2Arz2LateFreedLostRingLayout(ObjectManager objectManager) {
        for (int slot = 16; slot <= 57; slot++) {
            if (slot == 48 || slot == 49 || slot == 54 || slot == 55 || slot == 57) {
                continue;
            }
            assertTrue(objectManager.reserveDynamicSlot(slot), "setup should reserve slot " + slot);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class NoOpObjectRegistry implements ObjectRegistry {
        private final ObjectSlotLayout slotLayout;

        private NoOpObjectRegistry() {
            this(ObjectSlotLayout.SONIC_1);
        }

        private NoOpObjectRegistry(ObjectSlotLayout slotLayout) {
            this.slotLayout = slotLayout;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return slotLayout;
        }
    }

    private static final class TerrainBackedRing extends LostRingObjectInstance {
        private final int mask;
        private final boolean reverseGravity;
        private final boolean renderFlagForFloorProbe;
        private int vbla;

        private TerrainBackedRing(int xPixel, int yPixel, int xVel, int yVel,
                                  int mask, boolean reverseGravity, boolean renderFlagForFloorProbe) {
            super(new ObjectSpawn(xPixel & 0xFFFF, yPixel & 0xFFFF, 0x37, 0, 0, false, 0));
            initFixedPointForTest(xPixel, yPixel, xVel, yVel, 0, 0xFF);
            this.mask = mask;
            this.reverseGravity = reverseGravity;
            this.renderFlagForFloorProbe = renderFlagForFloorProbe;
        }

        void setVblaForTest(int vbla) {
            this.vbla = vbla;
        }

        int probeFloorForTest(int x, int y) {
            return ringCheckFloorDist(x, y);
        }

        @Override
        protected int resolveFloorCheckMask() {
            return mask;
        }

        @Override
        protected boolean isReverseGravityActive() {
            return reverseGravity;
        }

        @Override
        protected boolean hasRomRenderFlagForFloorProbe() {
            return renderFlagForFloorProbe;
        }

        @Override
        protected int resolveVblaCounter() {
            return vbla;
        }
    }

    private static final class SpawnTestPlayableSprite extends AbstractPlayableSprite {
        private int ringCount;

        private SpawnTestPlayableSprite(short x, short y) {
            super("TEST", x, y);
            setWidth(16);
            setHeight(32);
            setCentreX(x);
            setCentreY(y);
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
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void addRings(int delta) {
            ringCount += delta;
        }

        @Override
        public int getRingCount() {
            return ringCount;
        }

        @Override
        public void setRingCount(int ringCount) {
            this.ringCount = ringCount;
        }

        @Override
        public void draw() {
        }
    }
}
