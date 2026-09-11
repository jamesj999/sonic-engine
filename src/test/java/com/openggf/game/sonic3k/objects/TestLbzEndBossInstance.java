package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameStateManager;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance;
import com.openggf.data.Rom;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TestLbzEndBossInstance {
    private static final ObjectSpawn SPAWN = new ObjectSpawn(0x3B00, 0x05F8, 0xCB, 0, 0, false, 0x05F8);

    @Test
    void constructorSeedsRomHitboxPositionAndArtKey() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices());

        assertEquals(8, boss.getCollisionProperty(), "Obj_LBZEndBoss starts with collision_property=8");
        assertEquals(0, boss.getState().routine, "constructor should leave the ROM init/gate routine active");
        assertEquals(0x18, boss.getCollisionFlags(), "ObjDat_LBZEndBoss collision_flags byte is $18");
        assertEquals(0x3B00, boss.getX(), "ROM x_pos maps to centre X");
        assertEquals(0x05F8, boss.getY(), "ROM y_pos maps to centre Y");
        assertEquals(Sonic3kObjectArtKeys.LBZ_END_BOSS, boss.getArtKeyForTests());
        assertEquals(2, boss.getOwnedChildrenForTests().size(), "init spawns cockpit and tower children");
        LbzEndBossInstance.LbzEndBossTowerChild tower = boss.getOwnedChildrenForTests().stream()
                .filter(LbzEndBossInstance.LbzEndBossTowerChild.class::isInstance)
                .map(LbzEndBossInstance.LbzEndBossTowerChild.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(8, tower.getOnScreenHalfWidth(),
                "ObjDat3_74110 width_pixels keeps the tower's SolidObjectFull gate live");
        assertEquals(0x80, tower.getOnScreenHalfHeight(),
                "ObjDat3_74110 height_pixels covers the full launcher tower");
        assertTrue(tower.zeroXSpeedStopsOnLeftSideContact(),
                "SolidObject_cont routes zero x_vel through the left-side stop path");
        assertEquals(0x77, boss.getRequestedPlcIdForTests(), "routine 0 requests PLC $77");
        assertTrue(boss.isLbzEndBossArtQueuedForTests(), "routine 0 queues/uses LBZ end-boss art");
        assertTrue(boss.isLbzEndBossPaletteLine1RequestedForTests(), "routine 0 requests Pal_LBZEndBoss line 1");
        assertTrue(boss.isPaletteRuntimeIntegrationPendingForTests(),
                "no ROM/level in this isolated fixture means palette application is deferred to integration");
    }

    @Test
    void objectOnlyConstructionKeepsBossArtOrdinalAbsent() throws Exception {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices());
        var ordinal = LbzEndBossInstance.class.getDeclaredField("bossArtOrdinal");
        ordinal.setAccessible(true);

        assertEquals(-1L, ordinal.getLong(boss),
                "missing runtime-art services must not look like restored ordinal zero");
    }

    @Test
    void pendingPaletteLoadRetriesAfterServicesAttachForMovingPlatformBridge() throws Exception {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices());
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] palettes = {new Palette(), new Palette(), new Palette(), new Palette()};
        Level level = mock(Level.class);
        when(level.getPaletteCount()).thenReturn(palettes.length);
        when(level.getPalette(anyInt())).thenAnswer(invocation -> palettes[invocation.getArgument(0)]);
        byte[] line = new byte[32];
        line[0x16] = 0x02;
        line[0x17] = 0x22;
        Rom rom = mock(Rom.class);
        when(rom.readBytes(Sonic3kConstants.PAL_LBZ_END_BOSS_ADDR, 32)).thenReturn(line);
        TestObjectServices readyServices = new TestObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }
        };
        readyServices.withRom(rom).withPaletteOwnershipRegistry(registry);

        boss.setServices(readyServices);
        boss.update(0, null);
        registry.resolveInto(palettes, null, null, null);

        assertFalse(boss.isPaletteRuntimeIntegrationPendingForTests(),
                "Obj_LBZEndBoss should retry Pal_LBZEndBoss once services are attached");
        assertEquals("s3k.lbz.endBoss", registry.ownerAt(PaletteSurface.NORMAL, 1, 0x0B),
                "the bobbing platform/bridge uses the LBZ end-boss sheet on palette line 1");
    }

    @Test
    void cameraGateCompletionStartsIntroRunnerAndPlatforms() {
        Camera camera = cameraAt(0x3A20, 0x05A0);
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(camera));

        runFrames(boss, 121);

        assertEquals(4, boss.getPlatformChildrenForTests().size(), "gate callback spawns four bobbing platforms");
        assertTrue(boss.getPlatformChildrenForTests().stream()
                        .allMatch(platform -> platform.getBalanceWidthPixels() == 8),
                "ObjDat3_74140 width_pixels drives edge balancing independently of the $12 collision span");
        assertTrue(boss.getPlatformChildrenForTests().stream()
                        .allMatch(LbzEndBossInstance.LbzEndBossPlatformChild::rejectsZeroDistanceTopSolidLanding),
                "sub_73FCE's SolidObjectTop call excludes the exact-zero vertical boundary");
        assertTrue(boss.hasRunnerForTests(), "gate callback spawns Robotnik runner");
        assertEquals(4, boss.getState().routine, "gate callback enters routine $04 intro wait");
        assertEquals(0x3A20, camera.getMinX() & 0xFFFF);
        assertEquals(0x3A20, camera.getMaxX() & 0xFFFF);
    }

    @Test
    void runnerCompletionSetsActivationFlagAndAdvancesToCameraPan() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));

        enterIntro(boss);
        runFrames(boss, 0x1F + 0x29 + 4);

        assertTrue(boss.isFlagSetForTests(7), "Robotnik runner sets parent flag bit 7 after the jump");
        assertEquals(6, boss.getState().routine, "routine $04 observes bit 7 and advances to routine $06");
        assertTrue(boss.isScrollLockActiveForTests(), "routine $04/$06 models Scroll_lock while panning camera left");
    }

    @Test
    void runnerSeparatesAllocationAndInitializationFromFirstMovement() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));

        enterIntro(boss);
        var runner = boss.getOwnedChildrenForTests().stream()
                .filter(child -> "LBZEndBossRunner".equals(child.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(0x3B70, runner.getX(), "ChildObjDat_7415A allocates the runner at boss X+$70");
        runner.update(0x7000, null);
        assertEquals(0x3B70, runner.getX(), "loc_73D8E initializes without applying x_vel");
        runner.update(0x7001, null);
        assertEquals(0x3B6E, runner.getX(), "loc_73DB6 begins movement on the following dispatch");
    }

    @Test
    void scrollLockClearsWhenBossStartsRising() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));

        enterFireCycle(boss);

        assertFalse(boss.isScrollLockActiveForTests(), "routine $08 clears Scroll_lock after camera pan completes");
    }

    @Test
    void cameraPanStartsRisingOnTheClampToTargetFrame() {
        Camera camera = cameraAt(0x3A20, 0x05A0);
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(camera));
        enterIntro(boss);
        runFrames(boss, 0x1F + 0x29 + 4);
        assertEquals(6, boss.getState().routine);

        camera.setX((short) 0x39F2);
        boss.update(0x7777, null);

        assertEquals(0x39F0, camera.getX() & 0xFFFF);
        assertEquals(8, boss.getState().routine,
                "loc_7399E subtracts two before comparing, then falls through to loc_739B2");
    }

    @Test
    void fireCycleGatesPlatformsAndLaunchesRomSubtypeSequence() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));

        enterFireCycle(boss);
        runUntilSpikeCount(boss, 8, 4000);

        assertEquals(List.of(0, 2, 1, 2, 0, 1, 2, 1), boss.getLaunchedSpikeBallRomSubtypesForTests(),
                "byte_73F3A subtype sequence is used raw: 0,2,1,2,0,1,2,1");
        assertTrue(boss.getPlatformChildrenForTests().stream().anyMatch(LbzEndBossInstance.LbzEndBossPlatformChild::hasRunBobGateForTests),
                "leader platform runs the parent bit1 bob gate before the next launch");
    }

    @Test
    void hitReactionDisablesCollisionForTwentyHexFramesThenRestores() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices());

        boss.onPlayerAttack(null, null);

        assertEquals(7, boss.getCollisionProperty());
        assertEquals(0, boss.getCollisionFlags(), "hit reaction clears collision_flags immediately");
        assertTrue(boss.isHitFlashActiveForTests());
        assertEquals(0x20, boss.getState().invulnerabilityTimer);

        runFrames(boss, 0x20);

        assertEquals(0x18, boss.getCollisionFlags(), "ROM restores the ObjDat backup collision byte after flash");
        assertFalse(boss.isHitFlashActiveForTests());
    }

    @Test
    void postRiseHitRestoresObjDatBackupCollisionByte() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));

        enterFireCycle(boss);

        assertEquals(0x0F, boss.getCollisionFlags(), "routine $08 makes the raised launcher hittable with $0F");

        boss.onPlayerAttack(null, null);
        runFrames(boss, 0x20);

        assertEquals(0x0F, boss.getCollisionFlags(),
                "Touch_Enemy saves the live collision byte into $25 at hit time, so the "
                        + "post-rise flash restores $0F, not the ObjDat $18");
    }

    @Test
    void finalHitStartsDefeatAndSignalsChildrenWithoutCapsule() {
        GameStateManager gameState = new GameStateManager();
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withGameState(gameState));

        for (int i = 0; i < 8; i++) {
            boss.onPlayerAttack(null, null);
            runFrames(boss, 0x20);
        }

        assertTrue(boss.isDefeatActiveForTests());
        assertTrue(boss.isFlagSetForTests(4), "defeat sets parent flag bit 4 for child cleanup");
        assertEquals(0, boss.getCollisionFlags());
        assertEquals(0, boss.getCollisionProperty());
        assertFalse(boss.spawnsCapsuleForTests(), "LBZ spike-ball launcher defeat has no capsule");
        assertEquals(1000, gameState.getScore(),
                "loc_7403A moveq #100 + HUD_AddToScore (tens) awards 1000 displayed points");
    }

    @Test
    void defeatUsesGradualCameraMaxExtenderInsteadOfImmediateJump() {
        Camera camera = cameraAt(0x3A20, 0x05A0);
        camera.setMaxX((short) 0x39F0);
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(camera));

        triggerDefeat(boss);

        assertEquals(0x3AB8, boss.getDefeatStoredMaxXForTests());
        assertTrue(boss.usesLocalGradualMaxXExtenderForTests(),
                "owned local child models Child6_IncLevX when no shared object-scope helper is available");
        assertEquals(0x39F0, camera.getMaxX() & 0xFFFF, "defeat should not jump Camera_max_X_pos immediately");

        boss.update(0, null);

        assertEquals(0x39F0, camera.getMaxX() & 0xFFFF,
                "Obj_IncLevEndXGradual accumulates $4000/frame, so the first integer step lands on frame 4");

        boss.update(1, null);
        boss.update(2, null);
        boss.update(3, null);

        assertEquals(0x39F1, camera.getMaxX() & 0xFFFF,
                "frame 4 of the $4000 accumulator contributes the first +1 pixel");
    }

    @Test
    void defeatExplosionControllerEmitsSharedBossExplosionChildren() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices());

        triggerDefeat(boss);
        runFrames(boss, 4);

        assertTrue(boss.getSharedExplosionEmissionCountForTests() > 0,
                "defeat controller must drain S3kBossExplosionController pending emissions");
    }

    @Test
    void fallingSpikeBallUsesFloorProbeAndSnapsToTerrainBeforeRolling() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));
        LbzEndBossInstance.LbzEndBossSpikeBallChild ball = launchFirstSpikeBall(boss);

        // ObjHitFloor_DoRoutine only probes while y_vel >= 0: a rising ball must
        // not run the floor check at all.
        ball.forceFallingForTests(-0x60);
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            ball.update(0x4F00, null);
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()), times(0));
        }

        ball.forceFallingForTests(0x1E0);
        int beforeY = ball.getY();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-3, (byte) 0, 0));

            ball.update(0x5000, null);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), eq(0x10)), times(1));
        }

        assertEquals(2, ball.getPhaseForTests(), "terrain contact, not a boss-relative Y threshold, starts rolling");
        assertEquals(beforeY + 2 - 3, ball.getY(),
                "gravity ($1E0+$20 = 2px) then snap by the -3 terrain distance, per loc_73B12");
        assertEquals(0, ball.getYVelForTests(), "terrain landing clears vertical speed");
    }

    @Test
    void rollingSpikeBallUsesRightWallAndLookAheadFloorProbes() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));
        LbzEndBossInstance.LbzEndBossSpikeBallChild ball = launchFirstSpikeBall(boss);
        ball.forceRollingForTests(-0x210);
        int beforeChildCount = boss.getOwnedChildrenForTests().size();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-1, (byte) 0, 0));

            // Frame counter not divisible by 4: V_int_run_count & 3 gates smoke off.
            ball.update(0x6001, null);

            terrain.verify(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()), times(1));
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), eq(0x10)), times(1));
            terrain.verify(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()), times(0));
        }

        assertEquals(-0x200, ball.getXVelForTests(),
                "loc_73B3C always accelerates the roll by +$10 per frame (no cap)");
        assertEquals(beforeChildCount, boss.getOwnedChildrenForTests().size(),
                "no smoke on frames where V_int_run_count & 3 != 0");
    }

    @Test
    void rollingSpikeBallEmitsSmokeOnFourthFramesWhenFastInEitherDirection() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));
        LbzEndBossInstance.LbzEndBossSpikeBallChild ball = launchFirstSpikeBall(boss);
        ball.forceRollingForTests(-0x300);
        int beforeChildCount = boss.getOwnedChildrenForTests().size();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            ball.update(0x6004, null);
        }

        assertEquals(beforeChildCount + 1, boss.getOwnedChildrenForTests().size(),
                "the smoke gate is the unsigned x_vel+$200 >= $400 compare, so fast leftward rolls smoke too");
    }

    @Test
    void rollingSpikeBallExplodesIntoSprayOnRightWallContact() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));
        LbzEndBossInstance.LbzEndBossSpikeBallChild ball = launchFirstSpikeBall(boss);
        ball.forceRollingForTests(0x200);
        int beforeChildCount = boss.getOwnedChildrenForTests().size();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0));

            ball.update(0x6010, null);
        }

        assertTrue(ball.isDestroyed(), "ObjHitWall_DoRoutine runs $34 = loc_73B82: the ball detonates, never bounces");
        assertEquals(beforeChildCount + 12, boss.getOwnedChildrenForTests().size(),
                "ChildObjDat_741A0 spray: 8 flicker debris + 4 delayed smoke puffs");
    }

    @Test
    void spikeBallDefeatSpraySpawnsEightDebrisAndFourSmokePuffs() {
        LbzEndBossInstance boss = constructBoss(new TestObjectServices().withCamera(cameraAt(0x3A20, 0x05A0)));
        LbzEndBossInstance.LbzEndBossSpikeBallChild ball = launchFirstSpikeBall(boss);

        boss.getState().hitCount = 1;
        boss.onPlayerAttack(null, null);
        int beforeSprayChildCount = boss.getOwnedChildrenForTests().size();
        ball.update(0x7000, null);

        assertEquals(beforeSprayChildCount + 12, boss.getOwnedChildrenForTests().size(),
                "spike-ball defeat spray emits 8 debris children and 4 smoke puffs");
    }

    private static LbzEndBossInstance constructBoss(TestObjectServices services) {
        LbzEndBossInstance boss = ObjectConstructionContext.construct(
                services,
                () -> new LbzEndBossInstance(SPAWN));
        boss.setServices(services);
        return boss;
    }

    private static Camera cameraAt(int x, int y) {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setX((short) x);
        camera.setY((short) y);
        camera.setMinX((short) 0);
        camera.setMaxX((short) 0x7FFF);
        camera.setMinY((short) 0);
        camera.setMaxY((short) 0x7FFF);
        return camera;
    }

    private static void enterIntro(LbzEndBossInstance boss) {
        runFrames(boss, 121);
    }

    private static void enterFireCycle(LbzEndBossInstance boss) {
        enterIntro(boss);
        runFrames(boss, 0x1F + 0x29 + 4);
        runFrames(boss, 0x30);
        runFrames(boss, 0xDF + 4);
    }

    private static LbzEndBossInstance.LbzEndBossSpikeBallChild launchFirstSpikeBall(LbzEndBossInstance boss) {
        enterFireCycle(boss);
        runUntilSpikeCount(boss, 1, 512);
        return boss.getOwnedChildrenForTests().stream()
                .filter(LbzEndBossInstance.LbzEndBossSpikeBallChild.class::isInstance)
                .map(LbzEndBossInstance.LbzEndBossSpikeBallChild.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void runUntilSpikeCount(LbzEndBossInstance boss, int count, int maxFrames) {
        for (int i = 0; i < maxFrames && boss.getLaunchedSpikeBallRomSubtypesForTests().size() < count; i++) {
            boss.update(i, null);
        }
    }

    private static void triggerDefeat(LbzEndBossInstance boss) {
        for (int i = 0; i < 8; i++) {
            boss.onPlayerAttack(null, null);
            if (i < 7) {
                runFrames(boss, 0x20);
            }
        }
    }

    private static void runFrames(LbzEndBossInstance boss, int frames) {
        for (int i = 0; i < frames; i++) {
            boss.update(i, null);
        }
    }
}
