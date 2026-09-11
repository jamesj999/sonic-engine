package com.openggf.game;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.Sonic1ConveyorState;
import com.openggf.game.sonic1.Sonic1LevelInitProfile;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.Sonic2LevelInitProfile;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kLevelInitProfile;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for post-load assembly behavior.
 * <p>
 * Tests actual step behavior (checkpoint context round-trip, title card
 * suppression logic, sidekick presence), not just step-list verification.
 * No ROM required.
 */
public class TestPostLoadAssemblyBehavior {

    @BeforeEach
    public void resetCamera() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    // ========== Checkpoint Resume: Context Snapshot Round-Trip ==========

    @Test
    public void checkpointSnapshotPreservesAllFields() {
        Camera camera = GameServices.camera();
        camera.setX((short) 1000);
        camera.setY((short) 500);

        CheckpointState state = new CheckpointState();
        state.saveCheckpoint(3, 200, 400, false);

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(state);

        assertTrue(ctx.hasCheckpoint(), "Context should report checkpoint active");
        assertEquals(200, ctx.getCheckpointX());
        assertEquals(400, ctx.getCheckpointY());
        assertEquals(1000, ctx.getCheckpointCameraX());
        assertEquals(500, ctx.getCheckpointCameraY());
        assertEquals(3, ctx.getCheckpointIndex());
    }

    @Test
    public void checkpointRestoreFromSavedRoundTrip() {
        Camera camera = GameServices.camera();
        camera.setX((short) 800);
        camera.setY((short) 350);

        CheckpointState state = new CheckpointState();
        state.saveCheckpoint(5, 600, 900, false);

        // Step 1: Snapshot to context (simulates loadCurrentLevel pre-load)
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(state);

        // Step 2: Clear state (simulates InitPlayerAndCheckpoint step)
        state.clear();
        assertFalse(state.isActive(), "Checkpoint should be inactive after clear");

        // Step 3: Restore from context (simulates RestoreCheckpoint step)
        state.restoreFromSaved(
                ctx.getCheckpointX(), ctx.getCheckpointY(),
                ctx.getCheckpointCameraX(), ctx.getCheckpointCameraY(),
                ctx.getCheckpointIndex());

        assertTrue(state.isActive(), "Checkpoint should be active after restore");
        assertEquals(600, state.getSavedX());
        assertEquals(900, state.getSavedY());
        assertEquals(800, state.getSavedCameraX());
        assertEquals(350, state.getSavedCameraY());
        assertEquals(5, state.getLastCheckpointIndex());
    }

    @Test
    public void checkpointSnapshotWithWaterState() {
        CheckpointState state = createCheckpoint(2, 100, 200);
        state.saveWaterState(0x300, 4);

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(state);

        assertTrue(ctx.hasWaterState(), "Context should report water state");
        assertEquals(0x300, ctx.getCheckpointWaterLevel());
        assertEquals(4, ctx.getCheckpointWaterRoutine());
    }

    @Test
    public void checkpointSnapshotWithoutWaterState() {
        CheckpointState state = createCheckpoint(1, 100, 200);
        // Don't save water state

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(state);

        assertFalse(ctx.hasWaterState(), "Context should report no water state");
    }

    @Test
    public void checkpointSnapshotNullIsInactive() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(null);

        assertFalse(ctx.hasCheckpoint(), "Null state should yield inactive context");
        assertEquals(0, ctx.getCheckpointX());
        assertEquals(0, ctx.getCheckpointY());
        assertEquals(-1, ctx.getCheckpointIndex());
    }

    @Test
    public void checkpointSnapshotInactiveStateIsInactive() {
        CheckpointState state = new CheckpointState();
        assertFalse(state.isActive(), "Fresh CheckpointState should be inactive");

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(state);

        assertFalse(ctx.hasCheckpoint(), "Inactive state should yield inactive context");
    }

    // ========== Death Respawn: Context Configuration ==========

    @Test
    public void deathRespawnContextSuppressesTitleCard() {
        // respawnPlayer() calls loadCurrentLevel(false), setting showTitleCard=false
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setShowTitleCard(false);
        ctx.setIncludePostLoadAssembly(true);

        assertFalse(ctx.isShowTitleCard(), "Death respawn context should suppress title card");
        assertTrue(ctx.isIncludePostLoadAssembly(), "Death respawn context should include post-load assembly");
    }

    @Test
    public void freshStartContextShowsTitleCard() {
        // loadCurrentLevel() defaults to showTitleCard=true
        LevelLoadContext ctx = new LevelLoadContext();

        assertTrue(ctx.isShowTitleCard(), "Fresh context should default to showing title card");
    }

    @Test
    public void deathRespawnContextPreservesCheckpointData() {
        CheckpointState state = createCheckpoint(5, 300, 600);

        // Replicate what loadCurrentLevel(false) does:
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setShowTitleCard(false);
        ctx.setIncludePostLoadAssembly(true);
        ctx.snapshotCheckpoint(state);

        assertFalse(ctx.isShowTitleCard(), "Title card should be suppressed on respawn");
        assertTrue(ctx.hasCheckpoint(), "Checkpoint data should survive into respawn context");
        assertEquals(300, ctx.getCheckpointX());
        assertEquals(600, ctx.getCheckpointY());
    }

    @Test
    public void checkpointRestoreUsesRomCentreCoordinates() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x0100);
        camera.setY((short) 0x0020);

        CheckpointState state = new CheckpointState();
        state.saveCheckpoint(1, 0x1234, 0x0456, false);

        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        state.restoreToPlayer(player, camera);

        assertEquals(0x1234, player.getCentreX() & 0xFFFF,
                "Checkpoint x_pos must restore to the playable centre coordinate");
        assertEquals(0x0456, player.getCentreY() & 0xFFFF,
                "Checkpoint y_pos must restore to the playable centre coordinate");
    }

    // ========== S3K Title Card Behavior on Checkpoint Resume ==========

    @Test
    public void s3kTitleCardStepStillExistsWhenCheckpointActive() {
        CheckpointState state = createCheckpoint(1, 100, 200);

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        ctx.snapshotCheckpoint(state);
        assertTrue(ctx.hasCheckpoint());

        Sonic3kLevelInitProfile profile = newS3kProfile();
        InitStep titleCardStep = findStep(profile.levelLoadSteps(ctx), "RequestTitleCard");
        assertNotNull(titleCardStep, "S3K profile should include RequestTitleCard step");
    }

    @Test
    public void s3kTitleCardStepHasNoCheckpointGuard() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic3kLevelInitProfile profile = newS3kProfile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "RequestTitleCard");

        assertFalse(step.romRoutine().contains("skipped on checkpoint"),
                "S3K title card step should not suppress the title card just because a checkpoint is active");
    }

    @Test
    public void s2TitleCardStepHasNoCheckpointGuard() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic2LevelInitProfile profile = newS2Profile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "RequestTitleCard");

        assertFalse(step.romRoutine().contains("skipped on checkpoint"), "S2 title card step should NOT suppress on checkpoint");
    }

    @Test
    public void s1TitleCardStepHasNoCheckpointGuard() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic1LevelInitProfile profile = newS1Profile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "RequestTitleCard");

        assertFalse(step.romRoutine().contains("skipped on checkpoint"), "S1 title card step should NOT suppress on checkpoint");
    }

    // ========== Sidekick Spawn Step Presence ==========

    @Test
    public void s1ProfileOmitsSpawnSidekickStep() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic1LevelInitProfile profile = newS1Profile();
        InitStep sidekickStep = findStep(profile.levelLoadSteps(ctx), "SpawnSidekick");

        assertNull(sidekickStep, "S1 should NOT include SpawnSidekick (no Tails in Sonic 1)");
    }

    @Test
    public void s1ProfileHas6PostLoadSteps() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic1LevelInitProfile profile = newS1Profile();
        List<InitStep> steps = profile.levelLoadSteps(ctx);

        // 12 resource steps + S1's v_misc_variables/RNG reset + native PLC
        // lifecycle assembly + 6 post-load steps (no SpawnSidekick) = 20
        // (InitObjectManager + InitCameraBounds merged into InitObjectSystem)
        assertEquals(20, steps.size(), "S1 should have 20 steps (14 resource + 6 post-load)");
    }

    @Test
    public void s2ProfileIncludesSpawnSidekickStep() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic2LevelInitProfile profile = newS2Profile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "SpawnSidekick");

        assertNotNull(step, "S2 should include SpawnSidekick step");
    }

    @Test
    public void s3kProfileIncludesSpawnSidekickStep() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic3kLevelInitProfile profile = newS3kProfile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "SpawnSidekick");

        assertNotNull(step, "S3K should include SpawnSidekick step");
    }

    @Test
    public void s3kSidekickStepDocumentsDifferentOffset() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);

        Sonic3kLevelInitProfile profile = newS3kProfile();
        InitStep step = findStep(profile.levelLoadSteps(ctx), "SpawnSidekick");

        // S3K uses -$20 (32px) X offset and +4 Y offset (differs from S2's -40, 0)
        assertTrue(step.romRoutine().contains("$20"), "S3K sidekick step should document the $20 X offset");
        assertTrue(step.romRoutine().contains("+4"), "S3K sidekick step should document the +4 Y offset");
    }

    @Test
    public void initialProcessSpritesLifecycleProfilesAndStepOrderAreTyped() {
        assertEquals(InitialProcessSpritesLifecycle.NONE, newS1Profile().initialProcessSpritesLifecycle());
        assertEquals(InitialProcessSpritesLifecycle.NONE, newS2Profile().initialProcessSpritesLifecycle());

        Sonic3kLevelInitProfile profile = newS3kProfile();
        assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE,
                profile.initialProcessSpritesLifecycle());

        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        ctx.setAssemblyKind(LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY);
        List<InitStep> steps = profile.levelLoadSteps(ctx);
        int zoneState = indexOfStep(steps, "InitZonePlayerState");
        int requestSetup = indexOfStep(steps, "RequestInitialProcessSprites");
        int titleCard = indexOfStep(steps, "RequestTitleCard");

        assertEquals(zoneState + 1, requestSetup);
        assertEquals(requestSetup + 1, titleCard);
        steps.get(requestSetup).execute();
        assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE,
                ctx.requestedInitialProcessSpritesLifecycle());
    }

    // ========== Helpers ==========

    private CheckpointState createCheckpoint(int index, int x, int y) {
        Camera camera = GameServices.camera();
        CheckpointState state = new CheckpointState();
        state.saveCheckpoint(index, x, y, false);
        return state;
    }

    private static Sonic1LevelInitProfile newS1Profile() {
        return new Sonic1LevelInitProfile(
                new Sonic1LevelEventManager(),
                new Sonic1SwitchManager(),
                new Sonic1ConveyorState());
    }

    private static Sonic2LevelInitProfile newS2Profile() {
        return new Sonic2LevelInitProfile(new Sonic2LevelEventManager());
    }

    private static Sonic3kLevelInitProfile newS3kProfile() {
        return new Sonic3kLevelInitProfile(new Sonic3kLevelEventManager());
    }

    private static InitStep findStep(List<InitStep> steps, String name) {
        return steps.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static int indexOfStep(List<InitStep> steps, String name) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
