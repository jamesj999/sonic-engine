package com.openggf.game.timeattack;

import org.junit.jupiter.api.Test;
import com.openggf.control.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link TimeAttackMenuState} directly (no GL, no InputHandler
 * simulation needed since the row-navigation/adjust/go methods are public).
 */
class TestTimeAttackMenuState {

    @Test
    void controllerBackDoesNotQueueOrDispatchLaunch(@TempDir Path root) {
        java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(root), null,
                request -> launches.incrementAndGet());
        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, InputActionMasks.ACTION_C, InputActionMasks.ACTION_C, false, false),
                PlayerInputState.neutral()));
        menu.update(input);
        assertTrue(menu.consumeCloseRequested());
        assertEquals(0, launches.get());
        assertNull(menu.state().consumeLaunchRequest());
    }

    @Test
    void startsOnRequestedGameAndFirstTrack(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s1", "s2", "s3k"), "s3k", new GhostStore(root));

        assertEquals("s3k", state.currentGameId());
        assertEquals(0, state.trackIndex());
        assertEquals(TimeAttackTrackCatalog.tracksFor("s3k").get(0), state.currentTrack());
        assertEquals(TimeAttackMenuState.Row.GAME, state.focusedRow());
    }

    @Test
    void unknownInitialGameFallsBackToFirstAvailable(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s2", "s3k"), "s1", new GhostStore(root));
        assertEquals("s2", state.currentGameId());
    }

    @Test
    void changingGameResetsTrackAndCharacter(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s1", "s2", "s3k"), "s1", new GhostStore(root));
        state.adjust(1); // GAME row: s1 -> s2
        assertEquals("s2", state.currentGameId());
        assertEquals(0, state.trackIndex());
        assertEquals(0, state.characterIndex());

        state.adjust(-1); // wraps back to s1
        assertEquals("s1", state.currentGameId());
    }

    @Test
    void trackAndCharacterColumnsNavigateAndWrap(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s3k"), "s3k", new GhostStore(root));
        state.moveFocus(1); // GAME -> TRACK
        assertEquals(TimeAttackMenuState.Row.TRACK, state.focusedRow());

        int trackCount = TimeAttackTrackCatalog.tracksFor("s3k").size();
        state.adjust(-1); // wraps to last track
        assertEquals(trackCount - 1, state.trackIndex());

        state.moveFocus(1); // TRACK -> CHARACTER
        assertEquals(TimeAttackMenuState.Row.CHARACTER, state.focusedRow());
        state.adjust(1);
        assertEquals("tails", state.currentCharacter());
    }

    @Test
    void goProducesLaunchRequestMatchingSelection(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s3k"), "s3k", new GhostStore(root));
        state.moveFocus(1); // TRACK
        state.adjust(1); // HCZ1
        state.moveFocus(1); // CHARACTER
        state.adjust(2); // sonic -> tails -> knuckles

        state.pressGo();
        var request = state.consumeLaunchRequest();

        assertNotNull(request);
        assertEquals("s3k", request.gameId());
        assertEquals(1, request.zone());
        assertEquals(0, request.act());
        assertEquals("knuckles", request.character());
        assertNull(state.consumeLaunchRequest(), "launch request should be consumed once");
    }

    @Test
    void summaryReflectsSavedBestAndImportCount(@TempDir Path root) throws Exception {
        GhostStore store = new GhostStore(root);
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s3k"), "s3k", store);
        assertFalse(state.bestExists());
        assertEquals(0, state.importCount());

        Path importDir = root.resolve("s3k").resolve("import");
        Files.createDirectories(importDir);
        Files.createFile(importDir.resolve("someone.ggfghost"));

        state.moveFocus(1);
        state.adjust(0); // no-op adjust just to trigger a refresh path exercise
        // Force a refresh via a real navigation round-trip.
        state.adjust(1);
        state.adjust(-1);

        assertEquals(1, state.importCount());
    }

    @Test
    void closeRequestDefaultsFalseAndIsConsumedOnce(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s3k"), "s3k", new GhostStore(root));
        assertFalse(state.consumeCloseRequested());
    }
    @Test
    void soloAndJoinNavigationSkipHiddenHostSettings(@TempDir Path root) {
        TimeAttackMenuState state = new TimeAttackMenuState(List.of("s2"), "s2", new GhostStore(root));
        state.moveFocus(-1);
        assertEquals(TimeAttackMenuState.Row.MODE, state.focusedRow());
        state.adjust(2); // JOIN_LAN
        state.moveFocus(1);
        assertEquals(TimeAttackMenuState.Row.GAME, state.focusedRow());
        state.moveFocus(-1);
        state.adjust(-1); // HOST_LAN
        state.moveFocus(1);
        assertEquals(TimeAttackMenuState.Row.POLICY, state.focusedRow());
    }

    @Test
    void launchNeedsTheVisibleStartRow(@TempDir Path root) {
        java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(root), null,
                request -> launches.incrementAndGet());
        InputHandler input = new InputHandler();
        for (int i = 0; i < 4; i++) press(menu, input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertEquals(0, launches.get(), "Field selection must not launch a run");
        press(menu, input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertEquals(1, launches.get());
    }

    private static void press(TimeAttackMenu menu, InputHandler input, int key) {
        input.handleKeyEvent(key, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        menu.update(input); input.update();
        input.handleKeyEvent(key, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        menu.update(input); input.update();
    }

}
