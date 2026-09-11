package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.CustomPlayablePhysics;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotBonusPlayer {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void tailsSlotPlayerDelegatesToRuntimeSeam() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create(
                "tails", (short) 0x460, (short) 0x430, runtime);

        assertInstanceOf(Tails.class, player);
        assertEquals("tails", player.getCode());
        assertTrue(player instanceof CustomPlayablePhysics);
        assertTrue(player instanceof S3kSlotBonusPlayer);
    }

    @Test
    void createReturnsKnucklesSlotPlayerForKnucklesMainCode() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create(
                "knuckles", (short) 0x460, (short) 0x430, runtime);

        assertInstanceOf(Knuckles.class, player);
        assertFalse(player instanceof Tails);
        assertFalse(player instanceof Sonic);
        assertEquals("knuckles", player.getCode());
        assertTrue(player instanceof CustomPlayablePhysics);
        assertTrue(player instanceof S3kSlotBonusPlayer);
    }

    @Test
    void slotBonusPlayersKeepRomLowPrioritySoFgGlassCanOccludeThem() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();

        assertFalse(S3kSlotBonusPlayer.create("sonic", (short) 0x460, (short) 0x430, runtime).isHighPriority());
        assertFalse(S3kSlotBonusPlayer.create("tails", (short) 0x460, (short) 0x430, runtime).isHighPriority());
        assertFalse(S3kSlotBonusPlayer.create("knuckles", (short) 0x460, (short) 0x430, runtime).isHighPriority());
    }

    @Test
    void customPhysicsConsumesLaunchedVelocityIntoPosition() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic", (short) 0x460, (short) 0x430, runtime);

        runtime.initialize(player);
        short startX = player.getX();
        short startY = player.getY();
        player.setAir(false);
        player.setJumpInputPressed(true);

        ((CustomPlayablePhysics) player).tickCustomPhysics(
                false, false, false, true, true, false, false, false, (LevelManager) null, 42);

        assertTrue(player.getAir());
        assertTrue(player.getX() != startX || player.getY() != startY);
    }

    @Test
    void groundMotionProjectsVelocityThroughRotationAngle() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.tick();
        runtime.syncFromController(controller);

        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
                (short) 0x460, (short) 0x430, runtime);
        player.setGSpeed((short) 0x200);
        player.setAir(false);

        short startX = player.getX();
        short startY = player.getY();

        runtime.tick(player, false, false, false, false, false, 0);

        assertTrue(player.getX() != startX || player.getY() != startY);
    }

    @Test
    void airGravityIsAngleDependentUsingFactor0x2A() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();

        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
                (short) 0x460, (short) 0x430, runtime);
        player.setAir(true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        runtime.tick(player, false, false, false, false, false, 0);

        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) 0x2A, player.getYSpeed());
    }

    @Test
    void airGravityAtAngle0x40ProducesXComponent() {
        S3kSlotPlayerRuntime runtime = newEmptyRuntime();
        runtime.stageState().setStatTable(0x4000);

        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
                (short) 0x460, (short) 0x430, runtime);
        player.setAir(true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        runtime.tick(player, false, false, false, false, false, 0);

        assertEquals((short) 0x2A, player.getXSpeed());
        assertTrue(Math.abs(player.getYSpeed()) <= 1, "Y should be near zero at angle 0x40");
    }

    @Test
    void movementConstantsMatchDisassembly() {
        assertEquals(0x0C, S3kSlotBonusPlayer.GROUND_ACCEL);
        assertEquals(0x0C, S3kSlotBonusPlayer.GROUND_DECEL);
        assertEquals(0x40, S3kSlotBonusPlayer.GROUND_REVERSAL_DECEL);
        assertEquals(0x800, S3kSlotBonusPlayer.GROUND_MAX_SPEED);
    }

    @Test
    void startPositionMatchesCageCapture() {
        assertEquals((short) 0x0460, S3kSlotRomData.SLOT_BONUS_PLAYER_START_X);
        assertEquals((short) 0x0360, S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y);
        assertEquals((short) 0x0460, S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X);
        assertEquals((short) 0x0430, S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y);
    }

    private static S3kSlotPlayerRuntime newEmptyRuntime() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        java.util.Arrays.fill(buffers.layout(), (byte) 0);
        java.util.Arrays.fill(buffers.expandedLayout(), (byte) 0);
        return new S3kSlotPlayerRuntime(state, new S3kSlotCollisionSystem(buffers, state));
    }
}


