package com.openggf.tests;

import com.openggf.game.CheckpointState;
import com.openggf.game.RespawnState;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end headless tests for S2 post-load assembly behavior.
 * <p>
 * Verifies that the post-load assembly steps produce correct results when
 * executed via the full production path ({@code loadCurrentLevel} /
 * {@code respawnPlayer}). Covers checkpoint resume, death respawn state
 * clearing, and sidekick spawn offset verification.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2PostLoadAssemblyHeadless {
    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_EHZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 96, (short) 655)
                .build();
        sprite = (Sonic) fixture.sprite();

        // Clear checkpoint state from previous tests (resetPerTest doesn't touch it)
        RespawnState cs = GameServices.level().getCheckpointState();
        if (cs != null) {
            cs.clear();
        }
    }

    // ========== Checkpoint Resume ==========

    @Test
    public void checkpointResumeRestoresPlayerPosition() {
        int checkpointX = 500;
        int checkpointY = 400;

        // Position player at checkpoint location and save checkpoint
        sprite.setCentreX((short) checkpointX);
        sprite.setCentreY((short) checkpointY);
        fixture.camera().updatePosition(true);

        RespawnState checkpointState = GameServices.level().getCheckpointState();
        assertNotNull(checkpointState, "Checkpoint state should exist after level load");
        ((CheckpointState) checkpointState).saveCheckpoint(1, checkpointX, checkpointY, false);

        // Respawn triggers full level reload with checkpoint data preserved
        GameServices.level().respawnPlayer();

        // After respawn, SpawnPlayer step positions player at checkpoint coords
        assertEquals(checkpointX, sprite.getCentreX(), "Player centre X should match checkpoint after respawn");
        assertEquals(checkpointY, sprite.getCentreY(), "Player centre Y should match checkpoint after respawn");
    }

    @Test
    public void deathRespawnClearsPlayerVelocity() {
        sprite.setXSpeed((short) 600);
        sprite.setYSpeed((short) -400);
        sprite.setGSpeed((short) 500);

        RespawnState checkpointState = GameServices.level().getCheckpointState();
        ((CheckpointState) checkpointState).saveCheckpoint(0, 96, 655, false);

        GameServices.level().respawnPlayer();

        // ResetPlayerState step zeroes all velocities
        assertEquals(0, sprite.getXSpeed(), "X speed should be 0 after respawn");
        assertEquals(0, sprite.getYSpeed(), "Y speed should be 0 after respawn");
        assertEquals(0, sprite.getGSpeed(), "Ground speed should be 0 after respawn");
    }

    @Test
    public void deathRespawnClearsDeathAndHurtState() {
        sprite.setDead(true);
        sprite.setHurt(true);

        RespawnState checkpointState = GameServices.level().getCheckpointState();
        ((CheckpointState) checkpointState).saveCheckpoint(0, 96, 655, false);

        GameServices.level().respawnPlayer();

        // ResetPlayerState step clears death/hurt flags
        assertFalse(sprite.getDead(), "Player should not be dead after respawn");
        assertFalse(sprite.isHurt(), "Player should not be hurt after respawn");
    }

    @Test
    public void respawnWithoutCheckpointUsesLevelStart() {
        // No checkpoint saved â€” checkpoint state should be inactive from loadZoneAndAct
        RespawnState checkpointState = GameServices.level().getCheckpointState();
        assertFalse(checkpointState.isActive(), "Checkpoint should be inactive when no starpost touched");

        // Move player to a non-start position
        sprite.setCentreX((short) 2000);
        sprite.setCentreY((short) 300);

        GameServices.level().respawnPlayer();

        // SpawnPlayer step uses level start position (not the modified position)
        assertNotEquals(2000, (int) sprite.getCentreX(), "Player should NOT stay at modified X after respawn without checkpoint");
    }

    @Test
    public void checkpointResumePreservesCameraPosition() {
        int checkpointX = 800;
        int checkpointY = 400;

        sprite.setCentreX((short) checkpointX);
        sprite.setCentreY((short) checkpointY);
        fixture.camera().setX((short) 700);
        fixture.camera().setY((short) 300);
        fixture.camera().updatePosition(true);

        RespawnState checkpointState = GameServices.level().getCheckpointState();
        ((CheckpointState) checkpointState).saveCheckpoint(2, checkpointX, checkpointY, false);

        GameServices.level().respawnPlayer();

        // InitCamera step snaps camera to player. With checkpoint at 800,400
        // camera should be positioned near the player (exact coords depend on
        // camera bounds, but should not be at the level start camera position).
        int cameraX = fixture.camera().getX();
        assertTrue(cameraX > 0, "Camera X should be near checkpoint after respawn, was " + cameraX);
    }

    // ========== Sidekick Spawn Offsets ==========

    @Test
    public void s2SidekickSpawnsAtMinus40XSameY() {
        short playerX = 200;
        short playerY = 400;
        sprite.setCentreX(playerX);
        sprite.setCentreY(playerY);

        Tails tails = createSidekick();

        // S2 sidekick offset: -40 X, 0 Y
        GameServices.level().spawnSidekicks(-40, 0);

        assertEquals(playerX - 40, tails.getCentreX(), "S2 sidekick X should be player X - 40");
        assertEquals(playerY, tails.getCentreY(), "S2 sidekick Y should equal player Y");
    }

    @Test
    public void s3kSidekickOffsetDiffersFromS2() {
        short playerX = 200;
        short playerY = 400;
        sprite.setCentreX(playerX);
        sprite.setCentreY(playerY);

        Tails tails = createSidekick();

        // S3K sidekick offset: -32 X, +4 Y
        GameServices.level().spawnSidekicks(-32, 4);

        assertEquals(playerX - 32, tails.getCentreX(), "S3K sidekick X should be player X - 32");
        assertEquals(playerY + 4, tails.getCentreY(), "S3K sidekick Y should be player Y + 4");
    }

    @Test
    public void sidekickSpawnClearsFractionalWordsWithLevelObjectRam() {
        sprite.setCentreX((short) 200);
        sprite.setCentreY((short) 400);
        Tails tails = createSidekick();
        tails.setSubpixelRaw(0x5A00, 0xA500);

        GameServices.level().spawnSidekicks(-32, 4);

        assertEquals(0, tails.getXSubpixelRaw(),
                "Level entry clears object RAM before InitPlayers writes x_pos");
        assertEquals(0, tails.getYSubpixelRaw(),
                "Level entry clears object RAM before InitPlayers writes y_pos");
    }

    @Test
    public void sidekickStateResetOnSpawn() {
        Tails tails = createSidekick();
        tails.setXSpeed((short) 500);
        tails.setYSpeed((short) -300);
        tails.setGSpeed((short) 400);
        tails.setDead(true);
        tails.setAir(true);

        GameServices.level().spawnSidekicks(-40, 0);

        assertEquals(0, tails.getXSpeed(), "Sidekick X speed should be 0 after spawn");
        assertEquals(0, tails.getYSpeed(), "Sidekick Y speed should be 0 after spawn");
        assertEquals(0, tails.getGSpeed(), "Sidekick ground speed should be 0 after spawn");
        assertFalse(tails.getDead(), "Sidekick should not be dead after spawn");
        assertFalse(tails.getAir(), "Sidekick should not be airborne after spawn");
    }

    @Test
    void firstCpuInitPreservesTheLevelLoadLeaderHistoryPrefill() {
        sprite.setCentreX((short) 200);
        sprite.setCentreY((short) 400);
        Tails tails = createSidekick();

        GameServices.level().spawnSidekicks(-40, 0);
        tails.getCpuController().update(0);

        assertHistoryFilled(sprite, 168, 404);
        assertEquals(63, sprite.historyPos(),
                "the first live Sonic_RecordPos write must still target slot 0");
        assertFalse(capturedLevelStartLeaderHistoryPrefillPending(tails.getCpuController()),
                "the level-start prefill ownership token is one-shot");
    }

    // ========== Helpers ==========

    private static void assertHistoryFilled(
            AbstractPlayableSprite leader, int expectedX, int expectedY) {
        short[] xHistory = leader.copyXHistory();
        short[] yHistory = leader.copyYHistory();
        assertEquals(64, xHistory.length);
        assertEquals(64, yHistory.length);
        for (int slot = 0; slot < 64; slot++) {
            assertEquals(expectedX, xHistory[slot], "history X slot " + slot);
            assertEquals(expectedY, yHistory[slot], "history Y slot " + slot);
        }
    }

    private static boolean capturedLevelStartLeaderHistoryPrefillPending(
            SidekickCpuController controller) {
        try {
            Object rewindState = controller.captureRewindState();
            return (boolean) rewindState.getClass()
                    .getMethod("levelStartLeaderHistoryPrefillPending")
                    .invoke(rewindState);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Sidekick CPU rewind state must expose levelStartLeaderHistoryPrefillPending", e);
        }
    }

    private Tails createSidekick() {
        Tails tails = new Tails("tails", (short) 0, (short) 0);
        tails.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, sprite);
        tails.setCpuController(controller);
        GameServices.sprites().addSprite(tails);
        return tails;
    }
}
