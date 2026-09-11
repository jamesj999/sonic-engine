package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotPlayerRuntime {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void initializeUsesRomBootstrapState() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setX((short) 0x460);
        player.setY((short) 0x430);

        state.setLastCollision(5, 12);
        runtime.initialize(player);

        assertTrue(player.getAir());
        assertTrue(player.getRolling());
        assertEquals(0, player.getGSpeed());
        assertEquals(0, player.getXSpeed());
        // ROM Obj_Sonic_RotatingSlotBonus's routine==0 init handler falls
        // straight through into the per-frame movement dispatcher on the
        // same spawn-frame call (sonic3k.asm:98710-98784 loc_4B9CE ->
        // loc_4BA4E, no intervening rts) -- one gravity tick (AIR_GRAVITY
        // 0x2A) is already baked into y_speed by the time initialize()
        // returns.
        assertEquals(0x2A, player.getYSpeed());
        assertEquals(0, state.lastCollisionTileId());
        assertEquals(-1, state.lastCollisionIndex());
        assertTrue(runtime.slotOriginX() > 0);
        assertTrue(runtime.slotOriginY() > 0);
    }

    @Test
    void advanceRotationUsesStageStateScalar() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);

        runtime.advanceRotation(false);
        assertEquals(0x40, state.statTable());

        runtime.advanceRotation(true);
        assertEquals(0x440, state.statTable());
    }

    @Test
    void tickRecordsCollisionThroughCollisionSystem() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        int compactIndex = 0;
        int expandedIndex = buffers.compactToExpandedIndex(compactIndex);
        buffers.expandedLayout()[expandedIndex] = 5;
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setX((short) 0x2EC);
        player.setY((short) 0x2BC);

        runtime.initialize(player);
        player.setAir(false);

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(state.lastCollisionTileId() > 0);
        assertTrue(state.lastCollisionIndex() >= 0);
        assertFalse(player.getAir());
    }

    @Test
    void airMotionPreservesSpecialCollisionWhenLaterProbeHitsPlainSolid() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        int specialCompactIndex = 1;
        int specialExpandedIndex = buffers.compactToExpandedIndex(specialCompactIndex);
        int plainExpandedIndex = specialExpandedIndex + buffers.layoutStrideBytes() - 1;
        buffers.expandedLayout()[specialExpandedIndex] = 5;
        buffers.expandedLayout()[plainExpandedIndex] = 7;
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setX((short) 0x304);
        player.setY((short) 0x2BC);

        runtime.initialize(player);
        player.setXSpeed((short) (24 * 0x100));
        player.setYSpeed((short) (24 * 0x100));

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(state.lastCollisionTileId() >= 1 && state.lastCollisionTileId() <= 6);
        assertTrue(state.lastCollisionIndex() >= 0);
    }

    @Test
    void tickAppliesRotatedFallEvenWhenStartingGrounded() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);
        player.setAir(false);

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(player.getAir());
        assertEquals(0, player.getXSpeed());
        // initialize()'s spawn-frame fallthrough already applies one gravity
        // tick (0x2A); this explicit tick() applies a second, matching ROM's
        // first regular per-frame dispatch after the object's creation frame.
        assertEquals(0x54, player.getYSpeed());
        assertTrue(player.getY() > 0x360);
    }

    @Test
    void tickAppliesInertiaMovementWhileAirborne() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);
        player.setAir(true);
        player.setGSpeed((short) 0x800);
        int startCentreX = player.getCentreX();

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(player.getCentreX() > startCentreX);
        assertTrue(Math.abs(runtime.slotOriginX() - (player.getCentreX() << 16)) <= 0x10000);
    }

    @Test
    void tickAdvancesStageRotationInPlayerStep() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);

        runtime.tick(player, false, false, false, false, false, 0);

        // initialize()'s spawn-frame fallthrough already advances Stat_table
        // by SStage_scalar_index_1 (0x40) once; this tick() advances it a
        // second time, matching ROM's loc_4BA4E tail (sonic3k.asm:98781-98783).
        assertEquals(0x80, state.rawStatTable());
    }

    @Test
    void tickAcceptsExternalPositionAdjustmentBeforePhysicsStep() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        int compactIndex = 0;
        int expandedIndex = buffers.compactToExpandedIndex(compactIndex);
        buffers.expandedLayout()[expandedIndex] = 5;
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);

        runtime.initialize(player);
        player.setX((short) 0x2EC);
        player.setY((short) 0x2BC);
        player.setAir(false);

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(state.lastCollisionTileId() >= 0);
    }

    @Test
    void objectControlledTickSyncsFixedOriginFromPlayerPosition() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);

        runtime.initialize(player);
        player.setCentreX((short) 0x460);
        player.setCentreY((short) 0x430);
        player.setObjectControlled(true);

        runtime.tick(player, false, false, false, false, false, 0);

        assertEquals(0x460 << 16, runtime.slotOriginX());
        assertEquals(0x430 << 16, runtime.slotOriginY());
    }

    @Test
    void objectControlledTickRoundTripsNativePositionWordsAndFractions() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);

        runtime.initialize(player);
        player.setCentreXPreserveSubpixel((short) 0x461);
        player.setCentreYPreserveSubpixel((short) 0x431);
        player.setSubpixelRaw(0x5A00, 0x6B00);
        player.setObjectControlled(true);

        runtime.tick(player, false, false, false, false, false, 0);

        assertEquals(0x461, player.getCentreX());
        assertEquals(0x431, player.getCentreY());
        assertEquals(0x5A00, player.getXSubpixelRaw());
        assertEquals(0x6B00, player.getYSubpixelRaw());
        assertEquals((0x461 << 16) | 0x5A00, runtime.slotOriginX());
        assertEquals((0x431 << 16) | 0x6B00, runtime.slotOriginY());
    }

    @Test
    void tickAppliesMoveSprite2StepAfterVelocityUpdate() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setX((short) 0x460);
        player.setY((short) 0x360);

        runtime.initialize(player);
        player.setAir(true);
        int startCentreY = player.getCentreY();

        runtime.tick(player, false, false, false, false, false, 0);

        // initialize()'s primed fallthrough tick already contributed one
        // gravity application (y_speed 0x2A, +0x2A00 subpixel) before this
        // explicit tick() runs a second, giving y_speed 0x54 and a
        // cumulative +0x7E00 subpixel -- see initializeUsesRomBootstrapState.
        assertEquals(0x54, player.getYSpeed());
        assertEquals(startCentreY, player.getCentreY());
        assertEquals((startCentreY << 16) + 0x7E00, runtime.slotOriginY());
    }

    @Test
    void groundedMoveAppliesOnlyGroundInertiaDeltaBeforeVelocityStep() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setX((short) 0x460);
        player.setY((short) 0x360);

        runtime.initialize(player);
        player.setAir(false);
        int startOriginX = runtime.slotOriginX();

        runtime.tick(player, false, false, false, true, false, 0);

        assertEquals(startOriginX + 0x0C00, runtime.slotOriginX());
        assertEquals(0, player.getXSpeed());
    }

    @Test
    void bumperResponseUsesTileAnchorRatherThanTileCentre() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);

        var anchor = collisionSystem.resolveTileResponse(5, (short) 0x460, (short) 0x430,
                (short) 0x454, (short) 0x41C);
        var centre = collisionSystem.resolveTileResponse(5, (short) 0x460, (short) 0x430,
                (short) 0x460, (short) 0x428);

        assertNotEquals(anchor.launchXVel(), centre.launchXVel());
        assertNotEquals(anchor.launchYVel(), centre.launchYVel());
    }

    @Test
    void tileResponseAnchorMatchesRomPointerMath() {
        // ROM sub_4BE3A (sonic3k.asm:99214-99223): $32(a0) holds the post-increment
        // sub_4BDA2 pointer (TABLE + idx + 1), and subi.l #-$CFFF,d1 (sonic3k.asm:99215)
        // adds it back -- TABLE's low word is $3000, and $3000 + $CFFF + 1 == $10000,
        // so the low-word reconstruction is exactly idx (the "+1" cancels out, it does
        // NOT carry through to the recovered row/col). See
        // S3kSlotCollisionSystem.tileResponseAnchorX/Y javadoc for the full derivation,
        // confirmed against src/test/resources/traces/s3k/bonus_slots frame 445/446
        // (a bumper launch whose expected direction only matches with no +1).
        int row = 57;
        int col = 47;
        int expandedIndex = row * S3kSlotCollisionSystem.EXPANDED_STRIDE + col;

        assertEquals((col * S3kSlotCollisionSystem.CELL_SIZE) - S3kSlotCollisionSystem.COLLISION_X_OFFSET,
                S3kSlotCollisionSystem.tileResponseAnchorX(expandedIndex));
        assertEquals((row * S3kSlotCollisionSystem.CELL_SIZE) - S3kSlotCollisionSystem.COLLISION_Y_OFFSET,
                S3kSlotCollisionSystem.tileResponseAnchorY(expandedIndex));
    }

    @Test
    void debugModeForcesRotationOffAndRestoresSavedRotationOnExit() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);
        state.setStatTable(0x5A00);
        state.setScalarIndex1(0x40);

        player.setDebugMode(true);
        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(runtime.isDebugActive());
        assertEquals(0, state.rawStatTable());
        assertEquals(0, state.scalarIndex1());
        assertFalse(player.getRolling());

        player.setDebugMode(false);
        runtime.tick(player, false, false, false, false, false, 1);

        assertFalse(runtime.isDebugActive());
        assertEquals(0x5A00, state.rawStatTable());
        assertEquals(0x40, state.scalarIndex1());
        assertTrue(player.getRolling());
    }

    @Test
    void debugModeMovesDirectlyWithoutAdvancingRotation() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);
        player.setDebugMode(true);
        int startCentreX = player.getCentreX();
        int startCentreY = player.getCentreY();

        runtime.tick(player, true, false, false, true, false, 0);

        assertEquals(0, state.rawStatTable());
        assertEquals(0, state.scalarIndex1());
        assertEquals(startCentreX + 3, player.getCentreX());
        assertEquals(startCentreY - 3, player.getCentreY());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
    }

    @Test
    void groundProjectedOriginStaysAtPreAirMotionSnapshotWhileSlotOriginAdvances() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x360);

        runtime.initialize(player);
        int groundProjectedYBeforeTick = runtime.groundProjectedOriginY();
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setYSpeed((short) 0x1000);

        runtime.tick(player, false, false, false, false, false, 0);

        // ROM sub_4BDCA (ring pickup, sonic3k.asm:99144) and sub_4BE3A (tile dispatch,
        // sonic3k.asm:99195) both read x_pos(a0)/y_pos(a0) right after sub_4BABC's
        // ground-velocity projection and BEFORE this frame's air velocity is folded
        // into position by MoveSprite2 (sonic3k.asm:98776-98780) -- see the
        // groundProjectedOriginX/Y field javadoc. With gSpeed==0 the ground-projection
        // step contributes no delta, so groundProjectedOriginY() must stay pinned at
        // its pre-tick snapshot for this tick while the fully-stepped slotOriginY()
        // (which S3kSlotBonusStageRuntime.currentPlayerOriginX/Y exposes, NOT what the
        // ring/tile checks should read) advances several pixels from the large
        // air-velocity step.
        assertEquals(groundProjectedYBeforeTick, runtime.groundProjectedOriginY());
        assertTrue((runtime.slotOriginY() >> 16) > groundProjectedYBeforeTick + 4,
                "fully-stepped slotOriginY should advance well past the ground-projected "
                        + "snapshot after a large air-velocity step this tick");
    }

    @Test
    void debugModeSuppressesCollisionState() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        Arrays.fill(buffers.expandedLayout(), (byte) 0);
        int compactIndex = 0;
        int expandedIndex = buffers.compactToExpandedIndex(compactIndex);
        buffers.expandedLayout()[expandedIndex] = 5;
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(buffers, state);
        S3kSlotPlayerRuntime runtime = new S3kSlotPlayerRuntime(state, collisionSystem);
        Sonic player = new Sonic("sonic", (short) 0x2EC, (short) 0x2BC);

        runtime.initialize(player);
        player.setDebugMode(true);

        runtime.tick(player, false, false, false, false, false, 0);

        assertEquals(0, state.lastCollisionTileId());
        assertEquals(-1, state.lastCollisionIndex());
    }
}

