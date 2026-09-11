package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.session.SessionManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.S3kSlotRingRewardObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSlotSpikeRewardObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotBonusStageRuntime {
    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void bootstrapReplacesTailsMainCharacterAtRawPositionTransfersRendererStateAndRemovesSidekicks() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(SpriteArtSet.EMPTY);
        originalPlayer.setSpriteRenderer(renderer);
        originalPlayer.setMappingFrame(3);
        originalPlayer.setAnimationFrameCount(5);
        originalPlayer.setAnimationId(7);
        originalPlayer.setAnimationFrameIndex(2);
        originalPlayer.setAnimationTick(11);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        AbstractPlayableSprite sidekick = new Sonic("sonic_p2", (short) 0x420, (short) 0x430);
        sidekick.setCpuControlled(true);
        GameServices.sprites().addSprite(sidekick, "sonic");

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertTrue(runtime.isInitialized());
        assertNotNull(runtime.activeSlotCageForTest());
        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());
        assertTrue(runtime.activeSlotSpikeRewardsForTest().isEmpty());
        assertTrue(GameServices.sprites().getSprite("tails") instanceof S3kSlotBonusPlayer);

        AbstractPlayableSprite slotPlayer = assertInstanceOf(Tails.class, GameServices.sprites().getSprite("tails"));
        assertTrue(slotPlayer instanceof S3kSlotBonusPlayer);
        assertFalse(slotPlayer instanceof Sonic);
        assertEquals("tails", slotPlayer.getCode());
        assertEquals(S3kSlotRomData.SLOT_BONUS_PLAYER_START_X, slotPlayer.getCentreX());
        assertEquals(S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y, slotPlayer.getCentreY());
        assertSame(renderer, slotPlayer.getSpriteRenderer());
        assertEquals(3, slotPlayer.getMappingFrame());
        assertEquals(5, slotPlayer.getAnimationFrameCount());
        assertEquals(7, slotPlayer.getAnimationId());
        assertEquals(2, slotPlayer.getAnimationFrameIndex());
        assertEquals(11, slotPlayer.getAnimationTick());
        assertTrue(GameServices.sprites().getSidekicks().isEmpty());
        assertNull(GameServices.sprites().getSprite("sonic_p2"));
        assertNotSame(originalPlayer, slotPlayer);
        assertSame(slotPlayer, GameServices.camera().getFocusedSprite());
        assertEquals(slotPlayer.getCentreX() - 0xA0, GameServices.camera().getX());
        assertEquals(slotPlayer.getCentreY() - 0x70, GameServices.camera().getY());
        assertNotNull(runtime.activeLayoutForTest());
        assertEquals(32 * 32, runtime.activeLayoutForTest().length);

        runtime.shutdown();

        assertSame(sidekick, GameServices.sprites().getSprite("sonic_p2"));
        assertEquals(1, GameServices.sprites().getSidekicks().size());
    }

    @Test
    void queuedRingRewardActivatesInsideRuntimeAndExpires() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        // Move player away from cage to prevent capture interference
        slotPlayer.setX((short) 0x200);
        slotPlayer.setY((short) 0x200);
        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());

        runtime.queueRingReward();
        runtime.update(0);
        assertFalse(runtime.activeSlotRingRewardsForTest().isEmpty());
        // ROM Obj_SlotRing: 0x1A frames interpolation + 8 frames sparkle before deletion
        for (int frame = 1; frame <= 0x1A + 8; frame++) {
            runtime.update(frame);
        }

        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());
        assertSame(slotPlayer, GameServices.sprites().getSprite("tails"));
    }

    @Test
    void queuedRingRewardsSpawnIndependentTransientChildren() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        runtime.queueRingReward();
        runtime.queueRingReward();
        runtime.queueRingReward();
        runtime.update(0);

        assertEquals(3, runtime.activeSlotRingRewardsForTest().size());
        assertTrue(runtime.activeSlotRingRewardsForTest().stream().allMatch(S3kSlotRingRewardObjectInstance::isActive));
    }

    @Test
    void queuedSpikeRewardsSpawnIndependentTransientChildren() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        runtime.queueSpikeReward();
        runtime.queueSpikeReward();
        runtime.update(0);

        assertEquals(2, runtime.activeSlotSpikeRewardsForTest().size());
        assertTrue(runtime.activeSlotSpikeRewardsForTest().stream().allMatch(S3kSlotSpikeRewardObjectInstance::isActive));
    }

    @Test
    void bootstrapPreservesLiveCollisionBitsOnSwappedSlotPlayer() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        originalPlayer.setTopSolidBit((byte) 0x02);
        originalPlayer.setLrbSolidBit((byte) 0x03);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        assertTrue(slotPlayer instanceof S3kSlotBonusPlayer);
        assertEquals((byte) 0x02, slotPlayer.getTopSolidBit());
        assertEquals((byte) 0x03, slotPlayer.getLrbSolidBit());
    }

    @Test
    void bootstrapKeepsSlotPlayerLowPriorityEvenWhenLivePlayerWasHighPriority() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        originalPlayer.setHighPriority(true);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        assertTrue(slotPlayer instanceof S3kSlotBonusPlayer);
        assertFalse(slotPlayer.isHighPriority());
    }

    @Test
    void runtimeUpdateDoesNotImmediatelyCaptureAndFreezeBootstrapPlayer() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        runtime.update(0);

        // Cage should NOT capture on first frame (suppressInitialCaptureOnce)
        assertFalse(slotPlayer.isControlLocked());
        assertFalse(slotPlayer.isObjectControlled());
        // Player starts airborne per ROM (bset #Status_InAir)
        assertTrue(slotPlayer.getAir());
    }

    // ROM loc_4BA62 (sonic3k.asm:98751-98752) returns straight out of the whole
    // ground/air/ring/tile dispatch chain while object_control(a0) is set -- e.g.
    // during the bonus cage grab (sub_4AF80/loc_4B130, sonic3k.asm:98136). Commit
    // 2bf9ac104 added the matching `!slotPlayer.isObjectControlled()` gate around
    // checkRingPickup(). Pin both sides: the ring is NOT consumed while object
    // controlled (this test), and IS consumed once released, so a regression that
    // drops or inverts the gate is caught even without a trace-frontier signal.
    @Test
    void ringPickupIsSuppressedWhilePlayerIsObjectControlled() throws Exception {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));

        S3kSlotRenderBuffers buffers = runtime.renderBuffersForTest();
        int expandedIndex = buffers.compactToExpandedIndex(0);
        int row = expandedIndex / buffers.layoutStrideBytes();
        int col = expandedIndex % buffers.layoutStrideBytes();
        buffers.expandedLayout()[expandedIndex] = 8; // ring tile id (S3kSlotCollisionSystem.checkRingPickup)
        buffers.layout()[0] = 8; // keep the compact layout in sync, as real gameplay data does

        int xPixel = col * S3kSlotCollisionSystem.CELL_SIZE - S3kSlotCollisionSystem.RING_X_OFFSET;
        int yPixel = row * S3kSlotCollisionSystem.CELL_SIZE - S3kSlotCollisionSystem.RING_Y_OFFSET;
        setGroundProjectedOrigin(runtime.slotPlayerRuntimeForTest(), xPixel, yPixel);

        slotPlayer.setObjectControlled(true);
        runtime.stageStateForTest().clearCollision();

        runtime.update(0);

        // consumeRing (S3kSlotCollisionSystem) zeroes the COMPACT layout[0] entry --
        // the durable "this ring is gone" record -- and startRingAnimationAt starts a
        // transient sparkle animation at the same compact index. Neither must fire
        // while checkRingPickup itself is gated off.
        assertEquals(8, buffers.layout()[0],
                "the compact layout entry must stay unconsumed while object controlled");
        assertEquals(8, buffers.expandedLayout()[expandedIndex] & 0xFF,
                "the ring tile must remain in the layout, unconsumed, while object controlled");
        assertFalse(buffers.hasActiveTransientAnimationAt(0),
                "no pickup sparkle should start while checkRingPickup is suppressed");
    }

    @Test
    void ringPickupRunsAndConsumesTheTileOnceObjectControlIsReleased() throws Exception {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        // ROM Cage_Timer suppresses capture for the runtime's first update() call
        // (suppressInitialCaptureOnce) -- isObjectControlled() is already false
        // here, matching runtimeUpdateDoesNotImmediatelyCaptureAndFreezeBootstrapPlayer.
        assertFalse(slotPlayer.isObjectControlled());

        S3kSlotRenderBuffers buffers = runtime.renderBuffersForTest();
        int expandedIndex = buffers.compactToExpandedIndex(0);
        int row = expandedIndex / buffers.layoutStrideBytes();
        int col = expandedIndex % buffers.layoutStrideBytes();
        buffers.expandedLayout()[expandedIndex] = 8;
        buffers.layout()[0] = 8;

        int xPixel = col * S3kSlotCollisionSystem.CELL_SIZE - S3kSlotCollisionSystem.RING_X_OFFSET;
        int yPixel = row * S3kSlotCollisionSystem.CELL_SIZE - S3kSlotCollisionSystem.RING_Y_OFFSET;
        setGroundProjectedOrigin(runtime.slotPlayerRuntimeForTest(), xPixel, yPixel);

        // initialize()'s own spawn-frame collision probe (inside bootstrap(), against
        // the real ROM layout before this test's tile injection) may have latched a
        // stale lastCollisionTileId for the spawn cell; clear it so dispatchTileInteraction
        // doesn't fire on stale state and stomp the ring tile this test just placed.
        runtime.stageStateForTest().clearCollision();

        // Ring pickup (ROM sub_4BDCA) now runs inside the player runtime's movement
        // branch, spliced in before MoveSprite2 (sonic3k.asm:98776-98780) so a bumper
        // launch reaches the same frame's velocity step. update() no longer owns it;
        // drive the hook directly here after seeding the ground-projected origin.
        runtime.runPreMovePlayerInteractionsForTest();

        // consumeRing zeroes both layout arrays at this index, but startRingAnimationAt
        // (called right after, in the same checkRingPickup branch) immediately writes
        // the first ring-sparkle frame back over both arrays via setCompactTile -- so
        // the reliable post-pickup signal is the sparkle animation becoming active
        // and the raw ring tile id (8) no longer being present, not a literal 0.
        assertTrue(buffers.hasActiveTransientAnimationAt(0),
                "the pickup sparkle animation must start once checkRingPickup actually fires");
        assertNotEquals(8, buffers.layout()[0],
                "the raw ring tile id must no longer be present once the pickup actually runs");
    }

    private static void setGroundProjectedOrigin(S3kSlotPlayerRuntime runtime, int xPixel, int yPixel) throws Exception {
        Field xField = S3kSlotPlayerRuntime.class.getDeclaredField("groundProjectedOriginX");
        Field yField = S3kSlotPlayerRuntime.class.getDeclaredField("groundProjectedOriginY");
        xField.setAccessible(true);
        yField.setAccessible(true);
        xField.setInt(runtime, xPixel << 16);
        yField.setInt(runtime, yPixel << 16);
    }

    @Test
    void runtimeUpdateKeepsCameraBoundToSlotRuntimeOrigin() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("tails"));
        runtime.update(0);

        assertEquals(slotPlayer.getCentreX() - 0xA0, GameServices.camera().getX());
        assertEquals(slotPlayer.getCentreY() - 0x70, GameServices.camera().getY());
    }

    @Test
    void runtimeUpdateBuildsVisibleSemanticCellsForSlotLayout() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        runtime.update(0);

        assertNotNull(runtime.activeVisibleCellsForTest());
        assertFalse(runtime.activeVisibleCellsForTest().isEmpty());
        assertTrue(runtime.activeVisibleCellsForTest().size() >= 8);
        int cameraX = GameServices.camera().getX();
        int cameraY = GameServices.camera().getY();
        S3kSlotRenderBuffers.VisibleCells cells = runtime.activeVisibleCellsForTest();
        for (int i = 0; i < cells.size(); i++) {
            assertTrue(cells.cellIdAt(i) > 0);
            assertTrue(cells.cellIdAt(i) != 0x09);
            assertTrue(cells.worldXAt(i) >= cameraX - 0x10);
            assertTrue(cells.worldXAt(i) < cameraX + 0x150);
            assertTrue(cells.worldYAt(i) >= cameraY - 0x10);
            assertTrue(cells.worldYAt(i) < cameraY + 0xF0);
        }
    }

    @Test
    void runtimeUsesSharedMachineAnchorForCageAndDisplay() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        runtime.update(0);

        S3kSlotMachineDisplayState displayState = runtime.slotMachineDisplayStateForTest();
        assertNotNull(displayState);
        assertTrue(displayState.worldX() < runtime.stageStateForTest().eventsBgX());
        assertTrue(displayState.worldY() < runtime.stageStateForTest().eventsBgY());
        assertFalse(displayState.worldX() == runtime.stageStateForTest().eventsBgX()
                && displayState.worldY() == runtime.stageStateForTest().eventsBgY());
        assertEquals(3, displayState.faces().length);
        assertEquals(3, displayState.nextFaces().length);
        assertEquals(3, displayState.offsets().length);
    }

    @Test
    void machineDisplayAnchorDoesNotRotateWithStageAngle() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        runtime.update(0);

        S3kSlotMachineDisplayState baseline = runtime.slotMachineDisplayStateForTest();
        runtime.stageStateForTest().setStatTable(0x4000);
        S3kSlotMachineDisplayState rotated = runtime.slotMachineDisplayStateForTest();

        assertEquals(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X + S3kSlotRomData.SLOT_MACHINE_PANEL_CENTER_OFFSET_X,
                baseline.worldX());
        assertEquals(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y + S3kSlotRomData.SLOT_MACHINE_PANEL_CENTER_OFFSET_Y,
                baseline.worldY());
        assertEquals(baseline.worldX(), rotated.worldX());
        assertEquals(baseline.worldY(), rotated.worldY());
    }

    @Test
    void goalExitReportsCompletedProviderFadeAfterRomExitFadeCompletes() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        runtime.startGoalExitForTest();

        assertFalse(runtime.hasCompletedExitFadeToBlack());

        for (int frame = 0; frame < 155; frame++) {
            runtime.update(frame);
        }

        assertTrue(runtime.hasCompletedExitFadeToBlack());
        assertTrue(runtime.isExitTriggered());
    }

    @Test
    void lateRuntimeRenderPassDoesNotDrawMachineFacePanel() throws Exception {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        RecordingRenderer renderer = new RecordingRenderer();
        installRenderer(renderer, Sonic3kObjectArtKeys.SLOT_MACHINE_FACE);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        runtime.update(0);
        runtime.renderSlotLayout(GameServices.camera());

        assertEquals(0, renderer.drawCount);
    }

    @Test
    void shutdownRestoresOriginalPlayerAndCameraFocus() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertTrue(GameServices.sprites().getSprite("tails") instanceof S3kSlotBonusPlayer);
        assertNotSame(originalPlayer, GameServices.sprites().getSprite("tails"));
        assertSame(GameServices.sprites().getSprite("tails"), GameServices.camera().getFocusedSprite());

        runtime.shutdown();

        assertSame(originalPlayer, GameServices.sprites().getSprite("tails"));
        assertSame(originalPlayer, GameServices.camera().getFocusedSprite());
        assertNull(runtime.activeSlotCageForTest());
        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());
        assertTrue(runtime.activeSlotSpikeRewardsForTest().isEmpty());
    }

    @Test
    void shutdownRestoresOriginalPlayerOnBootstrapRuntimeAfterCurrentRuntimeRecreation() {
        GameplayModeContext bootstrapMode = TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Tails originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertTrue(bootstrapMode.getSpriteManager().getSprite("tails") instanceof S3kSlotBonusPlayer);
        assertNotSame(originalPlayer, bootstrapMode.getSpriteManager().getSprite("tails"));
        assertSame(bootstrapMode.getSpriteManager().getSprite("tails"), bootstrapMode.getCamera().getFocusedSprite());

        GameplayModeContext recreatedMode = TestEnvironment.activeGameplayMode();

        runtime.shutdown();

        // After the session ownership migration, GameplayModeContext owns the
        // disposable managers (SpriteManager, Camera, etc.). The active
        // gameplay mode owns those managers, so both references resolve to the same
        // SpriteManager. The shutdown invariant — original player restored to
        // the active SpriteManager — is preserved through that shared view.
        assertSame(originalPlayer, bootstrapMode.getSpriteManager().getSprite("tails"));
        assertSame(originalPlayer, bootstrapMode.getCamera().getFocusedSprite());
        assertFalse(runtime.isInitialized());
        assertSame(originalPlayer, recreatedMode.getSpriteManager().getSprite("tails"));

        bootstrapMode.destroy();
    }

    @Test
    void bootstrapInitializesAllSubsystems() {
        TestEnvironment.activeGameplayMode();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
        GameServices.sprites().addSprite(originalPlayer);
        GameServices.camera().setFocusedSprite(originalPlayer);

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertTrue(runtime.isInitialized());
        assertNotNull(runtime.activeSlotCageForTest());
        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());
        assertTrue(runtime.activeSlotSpikeRewardsForTest().isEmpty());
        assertNotNull(runtime.activeLayoutForTest());
        assertNotNull(runtime.optionCycleSystemForTest());
        assertNotNull(runtime.activeLayoutAnimatorForTest());
        assertFalse(runtime.isExitTriggered());

        // Run a few frames to verify no crashes
        for (int i = 0; i < 50; i++) {
            runtime.update(i);
        }

        assertTrue(runtime.isInitialized());

        runtime.shutdown();
        assertFalse(runtime.isInitialized());
    }

    @Test
    void bootstrapWithoutGameplayRuntimeDoesNotMarkInitialized() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertFalse(runtime.isInitialized());
    }

    private void installRenderer(RecordingRenderer renderer, String artKey) throws Exception {
        ObjectRenderManager renderManager = new ObjectRenderManager(new StubObjectArtProvider(renderer, artKey));
        Field objectRenderManagerField = LevelManager.class.getDeclaredField("objectRenderManager");
        objectRenderManagerField.setAccessible(true);
        objectRenderManagerField.set(TestEnvironment.activeGameplayMode().getLevelManager(), renderManager);
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer renderer;
        private final String artKey;

        private StubObjectArtProvider(PatternSpriteRenderer renderer, String artKey) {
            this.renderer = renderer;
            this.artKey = artKey;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return artKey.equals(key) ? renderer : null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public com.openggf.sprites.animation.SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of(artKey);
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private int drawCount;

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
            drawCount++;
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}

