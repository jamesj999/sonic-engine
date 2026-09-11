package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.GameServices;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.camera.Camera;
import com.openggf.level.Block;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossTopPhysics {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void topAdvancesThroughInitAndWaitToMain() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setY((short) 0);
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);

        for (int i = 0; i < 240; i++) top.update(i, fixture.sprite());
        assertTrue(top.getCurrentRoutineForTest() >= 6,
                "After 240 frames the top should reach routine 6 (TopMain)");
    }

    @Test
    void wait2RawGetFasterMatchesRomTopGoCadence() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setY((short) 0);
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);

        top.update(0, fixture.sprite()); // Init -> Wait.
        top.update(1, fixture.sprite()); // Parent bit observed -> Wait2, raw script installed.
        assertEquals(4, top.getCurrentRoutineForTest());
        assertFalse(top.isWait2RawActiveForTest(),
                "Obj_CNZMinibossTopWait only installs $30/$34; Animate_RawGetFaster starts next update");

        top.update(2, fixture.sprite());
        assertTrue(top.isWait2RawActiveForTest(),
                "Animate_RawGetFaster bset #5,$38(a0) on the first Wait2 update");
        assertEquals(7, top.getWait2RawDelayForTest(),
                "AniRaw_CNZMinibossTop byte 0 seeds $2E to 7");
        assertEquals(0, top.getWait2RawLoopCounterForTest(),
                "Animate_RawGetFaster clears $2F when it first claims the script");
        assertEquals(1, top.getWait2RawAnimFrameForTest(),
                "Fresh ROM raw scripts increment anim_frame before reading 2(a1,d0)");
        assertEquals(8, top.getMappingFrameForTest(),
                "The first visible Wait2 frame is script byte 3, mapping frame 8");
        assertEquals(7, top.getWait2RawFrameTimerForTest());

        for (int i = 0; i < 120; i++) {
            top.update(3 + i, fixture.sprite());
        }
        assertEquals(4, top.getCurrentRoutineForTest(),
                "TopGo must not fire before the 122nd Wait2 Animate_RawGetFaster call");

        top.update(123, fixture.sprite());
        assertEquals(6, top.getCurrentRoutineForTest(),
                "$FC invokes Obj_CNZMinibossTopGo from inside the 122nd Wait2 update");
        assertFalse(top.isWait2RawActiveForTest(),
                "Animate_RawGetFaster clears status bit 5 before jsr $34");
        assertEquals(0, top.getWait2RawLoopCounterForTest(),
                "The $2F loop counter is cleared before TopGo runs");
        assertEquals(0, top.getWait2RawDelayForTest(),
                "The terminal $FC path stores zero in $2E/anim_frame_timer before TopGo");
        assertEquals(0, top.getWait2RawAnimFrameForTest());
        assertEquals(7, top.getMappingFrameForTest(),
                "TopGo changes routine, script pointer, and velocities only; it does not rewrite mapping_frame");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL, top.getCurrentXVelForTest());
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_Y_VEL, top.getCurrentYVelForTest());
    }

    @Test
    void parentStartGateLeavesObjCnzMinibossGoHandoffFrameBeforeInit() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        DefaultObjectServices services = TestEnvironment.objectServices();
        Sonic3kCNZEvents events = getCnzEvents();
        events.forceMinibossStartGateForTest(true, false);

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x32C0, 0x020C, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.update(0, fixture.sprite());
        assertEquals(0, boss.getCurrentRoutine(),
                "Before the Obj_Wait release, the placed engine object mirrors the ROM wait object");

        events.forceMinibossStartGateForTest(true, true);
        boss.update(1, fixture.sprite());
        assertEquals(0, boss.getCurrentRoutine(),
                "Obj_CNZMinibossGo only installs Obj_CNZMinibossStart on the release frame "
                        + "(sonic3k.asm:144838-144851)");

        boss.update(2, fixture.sprite());
        assertEquals(2, boss.getCurrentRoutine(),
                "Obj_CNZMinibossStart dispatches Obj_CNZMinibossInit on the next object update "
                        + "(sonic3k.asm:144863-144900)");
    }

    @Test
    void waitRoutineFollowsParentUntilBossSignalsLaunch() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x02E4, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);

        top.update(0, fixture.sprite());
        boss.onArenaChunkDestroyed();
        top.update(1, fixture.sprite());

        assertEquals(boss.getCentreX(), top.getX());
        assertEquals(boss.getCentreY() + 0x2C, top.getY(),
                "Refresh_ChildPosition keeps the waiting top at its parent child offset");
        assertEquals(2, top.getCurrentRoutineForTest(),
                "Without parent $38 bit 1, the top must remain in Wait");
    }

    @Test
    void topExposesRomTouchCollisionFlags() {
        Object top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));

        assertTrue(top instanceof TouchResponseProvider,
                "ObjDat3_CNZMinibossTop collision byte $AA must enter Draw_And_Touch_Sprite");
        TouchResponseProvider provider = (TouchResponseProvider) top;
        assertEquals(0xAA, provider.getCollisionFlags());
        assertEquals(0, provider.getCollisionProperty());
    }

    @Test
    void collisionResponseListDereferencesLivePostMovementPosition() {
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));

        assertTrue(top.usesCurrentTouchResponseState(),
                "Obj_CNZMinibossTopMain runs MoveSprite2 before Draw_And_Touch_Sprite, "
                        + "so Touch_Loop must dereference the live post-movement x_pos/y_pos "
                        + "(sonic3k.asm:145058-145064,178041-178043,20660-20712)");
    }

    @Test
    void topMainExposesRomSolidObjectFullCall() {
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));

        assertTrue(top instanceof SolidObjectProvider,
                "Obj_CNZMinibossTopMain calls SolidObjectFull, not only Draw_And_Touch_Sprite");
        SolidObjectProvider solid = top;
        SolidObjectParams params = solid.getSolidParams();
        assertEquals(0x13, params.halfWidth(),
                "Obj_CNZMinibossTopMain sets d1=$13 before SolidObjectFull");
        assertEquals(0x0C, params.airHalfHeight(),
                "Obj_CNZMinibossTopMain sets d2=$C before SolidObjectFull");
        assertEquals(0x08, params.groundHalfHeight(),
                "Obj_CNZMinibossTopMain sets d3=8 before SolidObjectFull");
        assertFalse(solid.isSolidFor(new TestablePlayableSprite("sonic", (short) 0, (short) 0)),
                "Only routine 6 runs the SolidObjectFull body");

        top.forceTopMainForTest();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        assertTrue(solid.isSolidFor(player),
                "Routine 6 must participate in the shared SolidObjectFull contact pass");
        assertTrue(solid.skipsCpuSidekickWhenRenderFlagOffScreen(),
                "S3K SolidObjectFull skips offscreen Player_2 before SolidObjectFull_1P");
        assertTrue(solid.airborneStaleStandingBitReturnsNoContact(player),
                "SolidObjectFull_1P consumes stale airborne standing bits before new contact");
        assertTrue(solid.seedsNewRideCarryFromPreUpdateX(),
                "The top saves x_pos before MoveSprite2 and passes that saved d4 to SolidObjectFull");
    }

    @Test
    void groundedSquashEdgeContactSetsPushOnMinibossTop() {
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3200, 0x01C0, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(TestEnvironment.objectServices());
        top.forceTopMainForTest(0x3200, 0x01C0, 0, 0);
        top.snapshotPreUpdatePosition();

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setCpuControlled(true);
        tails.setRenderFlagOnScreen(true);
        tails.setGameRulesForTest(GameRules.SONIC_3K);
        tails.setAir(false);
        SolidObjectParams params = top.getSolidParams();
        int maxTop = params.airHalfHeight() + tails.getYRadius();
        int totalHeight = maxTop + params.airHalfHeight() + tails.getStandYRadius();
        tails.setCentreX((short) (top.getX() - params.halfWidth() + 0x0A));
        tails.setCentreY((short) (top.getY() - 4 - maxTop + totalHeight - 0x08));
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0);
        tails.setGSpeed((short) 0);

        ObjectManager objectManager = createIsolatedTouchObjectManager();
        objectManager.processImmediateInlineSolidCheckpoint(top, null, List.of(tails));

        assertTrue(tails.getPushing(),
                "Obj_CNZMinibossTop uses SolidObjectFull: the grounded squash-edge escape "
                        + "branches through loc_1E042/loc_1E06E and sets Status_Push "
                        + "even when x_vel is not moving into the top "
                        + "(sonic3k.asm:145057-145063,41473-41495,41564-41568); "
                        + top.traceDebugDetails());

        top.forceTopMainForTest(0x31C0, 0x01C0, 0, 0);
        objectManager.processImmediateInlineSolidCheckpoint(top, null, List.of(tails));

        assertFalse(tails.getPushing(),
                "S3K SolidObjectFull's no-contact path clears Status_Push only "
                        + "when this same object's pushing bit was set "
                        + "(sonic3k.asm:41512-41532); moving top children must "
                        + "key that bit to the SST instance, not their dynamic "
                        + "per-frame spawn position");
    }

    @Test
    void topMainBouncesVerticallyBetweenArenaBounds() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();
        int startY = top.getY();
        for (int i = 0; i < 60; i++) top.update(i, fixture.sprite());
        assertNotEquals(startY, top.getY(), "TopMain must be moving the top vertically");
    }

    @Test
    void rollingPlayerBounceReversesTopVerticalAndOpposingHorizontalVelocity() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setY((short) 0);
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x3242, (short) 0x030E);
        player.setRolling(true);
        player.setCentreX((short) 0x3242);
        player.setCentreY((short) 0x030E);
        player.setXSpeed((short) -0x100);
        player.setYSpeed((short) -0x100);

        int afterBounceX;
        int afterBounceY;
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkCeilingDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            top.update(0, player);
            afterBounceX = top.getX();
            afterBounceY = top.getY();
            top.update(1, player);
        }

        assertTrue(top.getX() < afterBounceX,
                "Opposing player/top X velocities should reverse the top X velocity");
        assertTrue(top.getY() < afterBounceY,
                "A rolling upward player bounce should reverse downward top Y velocity");
    }

    @Test
    void nativeP2RollingBounceReversesTopWhenP1DoesNotContact() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setY((short) 0);

        TestablePlayableSprite p1 = new TestablePlayableSprite("sonic", (short) 0x3000, (short) 0x030E);
        TestablePlayableSprite p2 = new TestablePlayableSprite("tails", (short) 0x3242, (short) 0x030E);
        p2.setCpuControlled(true);
        p2.setRolling(true);
        p2.setXSpeed((short) -0x100);
        p2.setYSpeed((short) -0x100);

        TestObjectServices services = new TestObjectServices()
                .withCamera(fixture.camera())
                .withSidekicks(List.of(p2));
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();

        int afterBounceY;
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkCeilingDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            top.update(0, p1);
            afterBounceY = top.getY();
            assertTrue(top.usesPreUpdatePositionForSolidContact(p2),
                    "the post-bounce P2 checkpoint samples the prior top publication");
            top.update(1, p1);
        }

        assertTrue(top.getY() < afterBounceY,
                "CNZMinibossTop_CheckPlayerBounce checks native Player_2 after Player_1");
        assertFalse(top.usesPreUpdatePositionForSolidContact(p2),
                "the saved SolidObjectFull position expires after one object dispatch");
    }

    @Test
    void floorTerrainHitReversesTopAndQueuesSnappedArenaBlockDestruction() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();
        getCnzEvents().setArenaChunkDestructionQueued(false);
        int[] beforeArenaCell = arenaCellDescriptorValues(0x3250, 0x0310);
        assertTrue(hasAnySolidDescriptor(beforeArenaCell),
                "CNZ miniboss floor impact fixture should start with solid arena collision");

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0));

            top.update(0, fixture.sprite());
        }

        Sonic3kCNZEvents events = getCnzEvents();
        assertTrue(events.isArenaChunkDestructionQueued());
        assertEquals(0x3250, events.getPendingArenaChunkX());
        assertEquals(0x0310, events.getPendingArenaChunkY());
        assertFalse(allDescriptorsCleared(arenaCellDescriptorValues(0x3250, 0x0310)),
                "Obj_CNZMinibossTop only stores Events_bg+$00/$02 and creates the explosion child; "
                        + "CNZ1_ScreenEvent performs the chunk-descriptor clear on the next screen-event pass "
                        + "(sonic3k.asm:145182-145184,145204-145216,107340-107365)");

        events.forceBackgroundRoutine(Sonic3kCNZEvents.BG_BOSS);
        events.update(0, fixture.frameCount());

        assertFalse(events.isArenaChunkDestructionQueued());
        assertTrue(allDescriptorsCleared(arenaCellDescriptorValues(0x3250, 0x0310)),
                "CNZ1_ScreenEvent must consume the queued top impact and clear the touched 2x2 live collision cell "
                        + "(sonic3k.asm:107340-107365)");

        int afterBounceY = top.getY();
        top.update(1, fixture.sprite());
        assertTrue(top.getY() < afterBounceY, "Floor terrain hit must reverse y_vel");
    }

    @Test
    void topLaunchBouncesAtCameraBottomBeforeFixedArenaLowerBound() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        fixture.camera().setY((short) 0x01CF);
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x32C0, 0x02C6, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest(0x32C0, 0x02C6,
                Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL,
                Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_Y_VEL);

        top.update(0, fixture.sprite());

        assertEquals(0x32C2, top.getX(),
                "MoveSprite2 reaches the ROM f14690 top X before ObjCheckFloorDist");
        assertEquals(0x02C8, top.getY(),
                "MoveSprite2 reaches the ROM f14690 top Y before ObjCheckFloorDist");
        assertTrue(top.getCurrentYVelForTest() < 0,
                "Obj_CNZMinibossTopMain checks Camera_Y_pos+$E0 before the fixed $380 bound "
                        + "(sonic3k.asm:145101-145110); f14690 bounces at camera bottom $02AF/$02D0");
    }

    @Test
    void horizontalBaseHitUsesForwardProbeCoordinate() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3300, 0x029B, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x32E5, 0x0299, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);
        top.forceTopMainForTest();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            top.update(0, fixture.sprite());
        }

        assertEquals((short) -Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL, top.getCurrentXVelForTest(),
                "Obj_CNZMinibossTopMain passes x_pos+$10 to CNZMinibossTop_CheckHitBase "
                        + "(sonic3k.asm:145071-145077), not the top centre");
    }

    @Test
    void lowerArenaBoundBounceDoesNotQueueBlockDestruction() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x31F0, 0x0379, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();
        getCnzEvents().setArenaChunkDestructionQueued(false);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            top.update(0, fixture.sprite());
        }

        assertFalse(getCnzEvents().isArenaChunkDestructionQueued(),
                "The $380 lower-bound bounce uses loc_6DDCC, not CNZMiniboss_BlockExplosion");
        int afterBounceY = top.getY();
        top.update(1, fixture.sprite());
        assertTrue(top.getY() < afterBounceY);
    }

    @Test
    void arenaCollisionPublishesBlockExplosionWithoutDirectBaseLowering() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);

        int originalBossY = boss.getCentreY();
        top.forceArenaCollisionForTest(0x3200, 0x0300);
        top.update(0, fixture.sprite());
        assertEquals(originalBossY, boss.getCentreY(),
                "CNZMiniboss_BlockExplosion only publishes impact coordinates and the explosion child; "
                        + "base lowering waits for the Events_bg+$04 arena-row signal "
                        + "(sonic3k.asm:145204-145224, 107388-107414, 145508-145515)");
    }

    @Test
    void arenaCollisionSpawnsBossExplosionAnimationAndSfxAtSnappedBlockCentre() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        CapturingTopImpactServices services = new CapturingTopImpactServices(
                GameServices.level().getObjectManager(),
                GameServices.module().getLevelEventProvider());
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);

        top.forceArenaCollisionForTest(0x32D0, 0x0310);
        top.update(0, null);

        assertTrue(services.playedSfx.contains(Sonic3kSfx.EXPLODE.id),
                "CNZMiniboss_BlockExplosion creates Obj_CreateBossExplosion subtype 6, whose controller plays sfx_Explode");
        ObjectInstance explosion = services.spawnedChildren.stream()
                .filter(S3kBossExplosionChild.class::isInstance)
                .findFirst()
                .orElse(null);
        assertNotNull(explosion,
                "CNZMiniboss_BlockExplosion must spawn the visible boss explosion animation child");
        assertTrue(Math.abs(explosion.getX() - 0x32D0) <= 0x10);
        assertTrue(Math.abs(explosion.getY() - 0x0310) <= 0x10);
    }

    @Test
    void defeatedParentDestroysTopBeforeMovementAndArenaClear() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x335D, 0x029A, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        for (int hit = 0; hit < Sonic3kConstants.CNZ_MINIBOSS_REAL_HITS; hit++) {
            boss.simulateHitForTest();
        }

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x32F0, 0x0316, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);
        top.forceTopMainForTest(0x32F0, 0x0316,
                -Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_X_VEL,
                Sonic3kConstants.CNZ_MINIBOSS_TOP_INIT_Y_VEL);
        Sonic3kCNZEvents events = getCnzEvents();
        events.setArenaChunkDestructionQueued(false);

        top.update(0, fixture.sprite());

        assertTrue(top.isDestroyed(),
                "Obj_CNZMinibossTopMain destroys itself before MoveSprite2 when parent status bit 7 is set "
                        + "(sonic3k.asm:145053-145057,145190-145199)");
        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(CnzMinibossBlockExplosionControllerChild.class::isInstance),
                "loc_6DDD2 replaces the defeated top with Obj_CreateBossExplosion subtype 6");
        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(S3kBossExplosionChild.class::isInstance),
                "the creation dispatch must immediately emit the first visible explosion");
        assertFalse(events.isArenaChunkDestructionQueued(),
                "The destroyed top must not run wall/floor terrain probes or publish Events_bg+$00/$02");
    }

    @Test
    void arenaRowSignalArmsLower2InsteadOfJumpingBaseByARow() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        int originalBossY = boss.getCentreY();
        boss.onArenaChunkDestroyed();
        assertEquals(originalBossY, boss.getCentreY(),
                "CNZMiniboss_MoveDown only arms Lower2 after Events_bg+$04 changes; "
                        + "it does not jump the base by a 0x20 row immediately "
                        + "(sonic3k.asm:145508-145515)");
        boss.update(1, fixture.sprite());
        assertEquals(originalBossY + 1, boss.getCentreY(),
                "Obj_CNZMinibossLower2 lowers one pixel per update "
                        + "(sonic3k.asm:144972-144981)");
    }

    @Test
    void topHitDamagesOpenCoilWithoutPlayerHitDamageShortcut() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceOpenForTest();
        int originalHits = boss.getRemainingHits();

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x02D8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);
        top.forceTopMainForTest();

        top.update(0, fixture.sprite());

        assertEquals(originalHits - 1, boss.getRemainingHits(),
                "Only the top piece hitting the open coil should consume CNZ miniboss HP");
    }

    @Test
    void playerAttackBeforeCloseGoBouncesWithoutOpeningCoil() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        int originalHits = boss.getRemainingHits();

        boss.onPlayerAttack(fixture.sprite(), null);

        assertEquals(originalHits, boss.getRemainingHits(),
                "CNZ miniboss player rebounds do not consume the top-piece HP counter");

        for (int frame = 0; frame < 130; frame++) {
            boss.update(frame, fixture.sprite());
        }

        assertFalse(boss.isOpenForTopHit(),
                "Obj_CNZMinibossInit sets $38 bit 3, so early player rebounds must not open the coil");
    }

    @Test
    void playerAttackAfterCloseGoOpensCoilButDoesNotConsumeBossHp() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forcePlayerHitOpeningReadyForTest();
        int originalHits = boss.getRemainingHits();

        boss.onPlayerAttack(fixture.sprite(), null);

        assertEquals(originalHits, boss.getRemainingHits(),
                "CNZ miniboss player hits open the coil; the bouncing top piece consumes HP");

        for (int frame = 0; frame < 130; frame++) {
            boss.update(frame, fixture.sprite());
        }

        assertTrue(boss.isOpenForTopHit(),
                "After CloseGo clears $38 bit 3, player attack should open the coil for the top-piece damage window");
    }

    @Test
    void blockedBodyHitLeavesMoveDispatchToBossSlot() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3300, 0x029B, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x04);
        boss.forceXVelForTest((short) 0x0100);

        boss.onPlayerAttack(fixture.sprite(),
                new TouchResponseResult(0x0C, 0x14, 0x10, TouchCategory.ENEMY));

        assertEquals(0x3300, boss.getCentreX(),
                "$38 bit 3 blocks CheckPlayerHit without executing the Move body in the player slot");
        assertEquals(0x04, boss.getCurrentRoutine());

        boss.update(0, fixture.sprite());

        assertEquals(0x3301, boss.getCentreX(),
                "the boss's later SST slot remains the sole MoveSprite2/Obj_Wait dispatch");
    }

    @Test
    void bodyHitOpensAfterCurrentMoveSprite2Step() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3300, 0x029B, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x04);
        boss.forcePlayerHitOpeningReadyForTest();
        boss.forceXVelForTest((short) 0x0100);

        boss.onPlayerAttack(fixture.sprite(),
                new TouchResponseResult(0x0C, 0x14, 0x10, TouchCategory.ENEMY));

        assertEquals(0x3301, boss.getCentreX(),
                "Obj_CNZMinibossMove must apply MoveSprite2 before CheckPlayerHit installs Opening "
                        + "(sonic3k.asm:144912-144915, 145404-145425)");
        assertEquals(0x08, boss.getCurrentRoutine());
        assertEquals(0, boss.getCollisionFlags(),
                "The body collision remains suppressed for the ROM $3A restore window after the hit");

        boss.update(0, fixture.sprite());

        assertEquals(0x3301, boss.getCentreX(),
                "Opening does not run another MoveSprite2 step on the first animation update");
        assertEquals(0x08, boss.getCurrentRoutine());
    }

    @Test
    void closingRawMultiDelayUsesPointerOnlyEntryBeforeCloseGo() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3300, 0x029B, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x0C);

        for (int frame = 0; frame < 25; frame++) {
            boss.update(frame, fixture.sprite());
            assertEquals(0x0C, boss.getCurrentRoutine(),
                    "Closing raw animation should keep routine $0C until the ROM $F4 terminator");
        }

        boss.update(25, fixture.sprite());
        assertEquals(0x06, boss.getCurrentRoutine(),
                "the $F4 terminator invokes Obj_CNZMinibossCloseGo in the same animation dispatch "
                        + "(docs/skdisasm/sonic3k.asm:144960-144969,145707-145708,177558-177586)");
    }

    @Test
    void productionTouchResponseCoilAttackOpensBossWithoutConsumingHp() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        AbstractPlayableSprite player = fixture.sprite();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forcePlayerHitOpeningReadyForTest();
        int originalHits = boss.getRemainingHits();

        CnzMinibossCoilInstance coil = new CnzMinibossCoilInstance(
                new ObjectSpawn(0x3240, 0x02D4, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        coil.setServices(services);
        coil.attachBossForTest(boss);

        assertTrue(coil instanceof TouchResponseAttackable,
                "The production coil child must receive Touch_Enemy attack callbacks, not just expose flags");
        assertEquals(0x1A, coil.getCollisionFlags());
        assertEquals(0x70, coil.getCollisionProperty());
        assertTrue(coil.usesCurrentTouchResponseState(),
                "Refresh_ChildPosition runs before the coil publishes its live SST pointer");

        player.setCentreX((short) coil.getX());
        player.setCentreY((short) coil.getY());
        player.setRolling(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        player.setXSpeed((short) 0x0120);
        player.setYSpeed((short) -0x0180);
        player.setGSpeed((short) 0x0100);

        ObjectManager objectManager = createIsolatedTouchObjectManager();
        objectManager.addDynamicObject(coil);
        coil.snapshotPreUpdatePosition();
        objectManager.runTouchResponsesForPlayer(player, 1);

        assertEquals(originalHits, boss.getRemainingHits(),
                "Closed-coil player attacks through ObjectManager must open without consuming the top counter");
        assertEquals((short) -0x0120, player.getXSpeed(),
                "Touch_Enemy_Part2 boss-hit path should negate player x_vel when collision_property is non-zero");
        assertEquals((short) 0x0180, player.getYSpeed(),
                "Touch_Enemy_Part2 boss-hit path should negate player y_vel when collision_property is non-zero");
        assertEquals((short) -0x0100, player.getGSpeed(),
                "S3K boss-hit path should negate ground_vel as well");
        for (int frame = 0; frame < 130; frame++) {
            boss.update(frame, fixture.sprite());
        }

        assertTrue(boss.isOpenForTopHit(),
                "A player attack routed through the spawned coil child should open the boss");
        assertEquals(0xA9, coil.getCollisionFlags(),
                "Once the parent is open, the coil child must expose the ROM open collision byte");
        assertEquals(0, coil.getCollisionProperty());
    }

    @Test
    void openGoSpawnsRomCoilSparkChildren() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forcePlayerHitOpeningReadyForTest();
        boss.onPlayerAttack(fixture.sprite(),
                new TouchResponseResult(0x0C, 0x14, 0x10, TouchCategory.ENEMY));

        for (int frame = 0; frame < 130; frame++) {
            boss.update(frame, fixture.sprite());
        }

        long sparkCount = services.objectManager().getActiveObjects().stream()
                .filter(object -> "CNZMinibossSpark".equals(object.getName()))
                .count();
        assertEquals(3, sparkCount,
                "Obj_CNZMinibossOpenGo must create Child1_CNZCoilOpenSparks "
                        + "(sonic3k.asm:144945-144951,145672-145692)");
    }

    @Test
    void openCoilSparkAppliesSidekickHurtKnockback() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        AbstractPlayableSprite tails = fixture.sprite();
        tails.setCpuControlled(true);

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3339, 0x029A, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        CnzMinibossSparkInstance spark = new CnzMinibossSparkInstance(
                new ObjectSpawn(0x3335, 0x02D6, Sonic3kObjectIds.CNZ_MINIBOSS, 4, 0, false, 0));
        spark.setServices(services);
        spark.attachBossForTest(boss);

        tails.setCentreX((short) 0x3328);
        tails.setCentreY((short) 0x02ED);
        tails.setAir(true);
        tails.setRolling(true);
        tails.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        tails.setXSpeed((short) -0x0090);
        tails.setYSpeed((short) -0x0530);
        tails.setGSpeed((short) 0x0110);

        ObjectManager objectManager = createIsolatedTouchObjectManager();
        objectManager.addDynamicObject(spark);
        spark.snapshotPreUpdatePosition();
        objectManager.runTouchResponsesForPlayer(tails, 1);

        assertTrue(tails.isHurt(),
                "Touch_Hurt/HurtCharacter must put sidekick into routine 4 "
                        + "(sonic3k.asm:21050-21091)");
        assertEquals((short) -0x0200, tails.getXSpeed());
        assertEquals((short) -0x0400, tails.getYSpeed());
        assertEquals((short) 0, tails.getGSpeed());
        assertFalse(tails.getRolling(),
                "HurtCharacter clears rolling before applying the airborne hurt status");
    }

    @Test
    void productionTouchResponseClosedBodyBounceDoesNotOpenBeforeCloseGo() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        AbstractPlayableSprite player = fixture.sprite();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        int originalHits = boss.getRemainingHits();

        player.setCentreX((short) boss.getCentreX());
        player.setCentreY((short) boss.getCentreY());
        player.setRolling(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        player.setXSpeed((short) 0x0128);
        player.setYSpeed((short) -0x0400);
        player.setGSpeed((short) 0x00E0);

        ObjectManager objectManager = createIsolatedTouchObjectManager();
        objectManager.addDynamicObject(boss);
        boss.snapshotPreUpdatePosition();
        objectManager.runTouchResponsesForPlayer(player, 1);

        assertEquals(originalHits, boss.getRemainingHits(),
                "Closed body rebounds use collision_property but must not consume the top-piece HP counter");
        assertEquals((short) -0x0128, player.getXSpeed(),
                "Touch_Enemy_Part2 boss-hit path should still negate player x_vel");
        assertEquals((short) 0x0400, player.getYSpeed(),
                "Touch_Enemy_Part2 boss-hit path should still negate player y_vel");
        assertEquals((short) -0x00E0, player.getGSpeed(),
                "S3K boss-hit path should still negate ground_vel");
        assertFalse(boss.isOpenForTopHit(),
                "CNZMiniboss_CheckPlayerHit is blocked by $38 bit 3 before Obj_CNZMinibossCloseGo clears it");
        assertEquals(0, boss.getCollisionFlags(),
                "Touch_Enemy clears parent collision_flags; CNZMiniboss_CheckPlayerHit restores them only after $3A expires");

        player.setXSpeed((short) 0x0100);
        player.setYSpeed((short) 0x0300);
        player.setGSpeed((short) 0x0040);
        objectManager.runTouchResponsesForPlayer(player, 2);

        assertEquals((short) 0x0100, player.getXSpeed(),
                "Closed-body collision must stay suppressed, preventing a second immediate x_vel negation");
        assertEquals((short) 0x0300, player.getYSpeed(),
                "Closed-body collision must stay suppressed, preventing a second immediate y_vel negation");
        assertEquals((short) 0x0040, player.getGSpeed(),
                "Closed-body collision must stay suppressed, preventing a second immediate ground_vel negation");
    }

    @Test
    void bodyHitWhileOpeningStillSuppressesImmediateRepeatCollision() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3300, 0x029B, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x08);

        boss.onPlayerAttack(fixture.sprite(),
                new TouchResponseResult(0x0C, 0x14, 0x10, TouchCategory.ENEMY));

        assertEquals(0, boss.getCollisionFlags(),
                "Touch_Enemy clears collision_flags even when CNZMiniboss_CheckPlayerHit does not restart Opening "
                        + "(sonic3k.asm:20916-20921, 145404-145425)");
        assertEquals(0x08, boss.getCurrentRoutine(),
                "An in-progress Opening body hit must not restart or advance the parent routine");

        for (int frame = 0; frame < 0x10; frame++) {
            boss.update(frame, fixture.sprite());
            assertEquals(0, boss.getCollisionFlags(),
                    "CNZMiniboss_CheckPlayerHit keeps parent collision_flags suppressed while $3A counts down");
        }

        boss.update(0x10, fixture.sprite());
        assertEquals(0x0C, boss.getCollisionFlags(),
                "After the $3A restore window, CNZMiniboss_CheckPlayerHit restores the backed-up body byte");
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        return events.getCnzEvents();
    }

    private static int[] arenaCellDescriptorValues(int snappedWorldX, int snappedWorldY) {
        Level level = GameServices.level().getCurrentLevel();
        int rawWorldX = snappedWorldX - 0x10;
        int rawWorldY = snappedWorldY - 0x10;
        int blockPixelSize = level.getBlockPixelSize();
        int blockX = Math.floorDiv(rawWorldX, blockPixelSize);
        int blockY = Math.floorDiv(rawWorldY, blockPixelSize);
        int blockIndex = level.getMap().getValue(0, blockX, blockY) & 0xFF;
        Block block = level.getBlock(blockIndex);
        int chunkX = ((rawWorldX & (blockPixelSize - 1)) / 0x10) & ~1;
        int chunkY = ((rawWorldY & (blockPixelSize - 1)) / 0x10) & ~1;
        return new int[] {
                block.getChunkDesc(chunkX, chunkY).get(),
                block.getChunkDesc(chunkX + 1, chunkY).get(),
                block.getChunkDesc(chunkX, chunkY + 1).get(),
                block.getChunkDesc(chunkX + 1, chunkY + 1).get()
        };
    }

    private static boolean hasAnySolidDescriptor(int[] descriptors) {
        for (int descriptor : descriptors) {
            if (descriptor != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean allDescriptorsCleared(int[] descriptors) {
        for (int descriptor : descriptors) {
            if (descriptor != 0) {
                return false;
            }
        }
        return true;
    }

    private static ObjectManager createIsolatedTouchObjectManager() {
        TouchResponseTable table = mock(TouchResponseTable.class);
        when(table.getWidthRadius(0x1A)).thenReturn(0x10);
        when(table.getHeightRadius(0x1A)).thenReturn(0x20);
        when(table.getWidthRadius(0x12)).thenReturn(0x08);
        when(table.getHeightRadius(0x12)).thenReturn(0x10);
        when(table.getWidthRadius(0x0C)).thenReturn(0x14);
        when(table.getHeightRadius(0x0C)).thenReturn(0x10);

        DebugOverlayManager debugOverlay = mock(DebugOverlayManager.class);
        when(debugOverlay.isEnabled(any())).thenReturn(false);

        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x3200);
        when(camera.getY()).thenReturn((short) 0x0100);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        AbstractObjectInstance.updateCameraBounds(0x3200, 0x0100, 0x3200 + 320, 0x0100 + 224, 0);

        TestObjectServices objectServices = new TestObjectServices()
                .withDebugOverlay(debugOverlay)
                .withCamera(camera)
                .withGameModule(GameServices.module());
        assertNotNull(objectServices.gameModule());

        return new ObjectManager(List.of(), new ObjectRegistry() {
            @Override
            public com.openggf.level.objects.ObjectInstance create(ObjectSpawn spawn) {
                return null;
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "Test";
            }
        }, 0, null, table, null, camera, objectServices);
    }

    private static final class CapturingTopImpactServices extends TestObjectServices {
        private final ObjectManager objectManager;
        private final com.openggf.game.LevelEventProvider levelEventProvider;
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();

        private CapturingTopImpactServices(ObjectManager objectManager,
                                           com.openggf.game.LevelEventProvider levelEventProvider) {
            this.objectManager = mock(ObjectManager.class);
            this.levelEventProvider = levelEventProvider;
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(this.objectManager).addDynamicObjectAfterCurrent(any());
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(this.objectManager).addDynamicObjectAfterCurrentNextFrame(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public com.openggf.game.LevelEventProvider levelEventProvider() {
            return levelEventProvider;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
