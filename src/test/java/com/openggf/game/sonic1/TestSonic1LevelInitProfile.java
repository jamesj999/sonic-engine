package com.openggf.game.sonic1;

import com.openggf.game.InitStep;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.LevelLoadMode;
import com.openggf.game.session.SessionManager;
import com.openggf.game.StaticFixup;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic1LevelInitProfile {

    private final Sonic1LevelInitProfile profile = new Sonic1LevelInitProfile(
            new Sonic1LevelEventManager(),
            new Sonic1SwitchManager(),
            new Sonic1ConveyorState());

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void teardownHasExpectedSteps() {
        List<InitStep> steps = profile.levelTeardownSteps();

        assertTrue(steps.size() >= 10, "Should have at least 10 teardown steps");

        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Audio")), "Should include audio reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("S1LevelEvents")), "Should include S1 level event reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Parallax")), "Should include parallax reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("LevelManager")), "Should include level manager reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Sprites")), "Should include sprite reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Collision")), "Should include collision reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Camera")), "Should include camera reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Graphics")), "Should include graphics reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Fade")), "Should include fade reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("GameState")), "Should include game state reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Timers")), "Should include timer reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Water")), "Should include water reset");
    }

    @Test
    public void perTestResetHasExpectedSteps() {
        List<InitStep> steps = profile.perTestResetSteps();

        assertTrue(steps.size() >= 7, "Should have at least 7 per-test reset steps");

        assertTrue(steps.stream().anyMatch(s -> s.name().contains("S1LevelEvents")), "Should include S1 level event reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Parallax")), "Should include parallax reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Sprites")), "Should include sprite reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Collision")), "Should include collision reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Camera")), "Should include camera reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Fade")), "Should include fade reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("GameState")), "Should include game state reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Timers")), "Should include timer reset");
        assertTrue(steps.stream().anyMatch(s -> s.name().contains("Water")), "Should include water reset");
    }

    @Test
    public void postTeardownFixupsAreEmpty() {
        List<StaticFixup> fixups = profile.postTeardownFixups();

        // S1 has no game-specific post-teardown fixups
        assertEquals(0, fixups.size());
    }

    @Test
    public void freshPlayerRunsOneNativePreludeDispatch() {
        assertEquals(1, profile.freshMainPlayablePreludeFrames(),
                "S1 GM_Level executes the freshly created Sonic slot once before Level_MainLoop");
    }

    @Test
    public void levelLoadStepsWithoutPostLoadHasMinimumSteps() {
        List<InitStep> steps = profile.levelLoadSteps(new com.openggf.game.LevelLoadContext());
        assertTrue(steps.size() >= 11, "Should have at least 11 level load steps without post-load");
    }

    @Test
    public void levelLoadStepsWithPostLoadHasExpectedSteps() {
        com.openggf.game.LevelLoadContext ctx = new com.openggf.game.LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        List<InitStep> steps = profile.levelLoadSteps(ctx);

        assertTrue(steps.size() >= 16, "Should have at least 16 level load steps with post-load");

        // Verify key ROM-aligned resource loading steps are present
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitGameModule")), "Should include InitGameModule");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("ResetRng")),
                "S1 GM_Level clears v_misc_variables, including v_random");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitAudio")), "Should include InitAudio");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("LoadLevelData")), "Should include LoadLevelData");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("QueueInitialPlcs")),
                "S1 Level must clear then enqueue its primary and Main2 PLCs before title-card admission");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitAnimatedContent")), "Should include InitAnimatedContent");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitObjectSystem")), "Should include InitObjectSystem");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitGameplayState")), "Should include InitGameplayState");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitRings")), "Should include InitRings");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitZoneFeatures")), "Should include InitZoneFeatures");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitArt")), "Should include InitArt");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitPlayerAndCheckpoint")), "Should include InitPlayerAndCheckpoint");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitWater")), "Should include InitWater");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitBackgroundRenderer")), "Should include InitBackgroundRenderer");

        // Verify key post-load assembly steps are present (S1 has no SpawnSidekick)
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("RestoreCheckpoint")), "Should include RestoreCheckpoint");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("SpawnPlayer")), "Should include SpawnPlayer");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("ResetPlayerState")), "Should include ResetPlayerState");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitCamera")), "Should include InitCamera");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitLevelEvents")), "Should include InitLevelEvents");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("RequestTitleCard")), "Should include RequestTitleCard");

        int moduleIndex = indexOfStep(steps, "InitGameModule");
        int rngIndex = indexOfStep(steps, "ResetRng");
        int audioIndex = indexOfStep(steps, "InitAudio");
        assertTrue(moduleIndex < rngIndex && rngIndex < audioIndex,
                "S1 v_misc_variables clear belongs at the start of GM_Level before resource setup");
    }

    @Test
    public void previewCaptureLoadOmitsAudioAndTitleCardSteps() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        ctx.setLoadMode(LevelLoadMode.PREVIEW_CAPTURE);

        List<InitStep> steps = profile.levelLoadSteps(ctx);

        assertFalse(steps.stream().anyMatch(s -> s.name().equals("InitAudio")),
                "Preview capture should not include InitAudio");
        assertFalse(steps.stream().anyMatch(s -> s.name().equals("RequestTitleCard")),
                "Preview capture should not include RequestTitleCard");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitGameModule")),
                "Preview capture should still include core level loading");
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("InitLevelEvents")),
                "Preview capture should still include level events");
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
