package com.openggf.game;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.launch.LaunchProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestMasterTitleStandaloneEntries {
    @TempDir Path tempDir;

    @Test
    void entryListIsDefensivelyCopiedAndDrivesSelection() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        List<MasterTitleEntry> supplied = new ArrayList<>(List.of(
                new MasterTitleEntry.Stock(MasterTitleScreen.GameEntry.SONIC_2),
                new MasterTitleEntry.Standalone("sample-game", "Sample Game", false)));
        MasterTitleScreen screen = new MasterTitleScreen(
                config, new LaunchProfileStore(config), supplied);
        supplied.clear();

        assertEquals(2, screen.entriesForTest().size());
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        press(screen, GLFW_KEY_DOWN);
        assertEquals("sample-game", screen.getSelectedGameId());
    }

    @Test
    void standaloneChooserReturnsTypedNewGameLaunch() {
        MasterTitleScreen screen = standaloneScreen(false);

        press(screen, GLFW_KEY_ENTER);
        press(screen, GLFW_KEY_ENTER);
        assertFalse(screen.isGameSelected(), "Start opens the standalone action chooser");
        assertEquals(List.of(MasterTitleEntry.Action.NEW_GAME), screen.standaloneActionsForTest());

        press(screen, GLFW_KEY_ENTER);
        assertTrue(screen.isGameSelected());
        assertEquals(new MasterTitleEntry.Launch(
                new MasterTitleEntry.Standalone("sample-game", "Sample Game", false),
                MasterTitleEntry.Action.NEW_GAME), screen.getSelectedLaunch());
    }

    @Test
    void standaloneChooserOffersContinueOnlyWhenSnapshotWasValid() {
        MasterTitleScreen screen = standaloneScreen(true);

        press(screen, GLFW_KEY_ENTER);
        press(screen, GLFW_KEY_ENTER);
        assertEquals(List.of(MasterTitleEntry.Action.NEW_GAME, MasterTitleEntry.Action.CONTINUE),
                screen.standaloneActionsForTest());
        press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);

        assertEquals(MasterTitleEntry.Action.CONTINUE, screen.getSelectedLaunch().action());
    }

    @Test
    void tabLaunchConfigurationIsStockOnly() {
        MasterTitleScreen screen = standaloneScreen(true);

        press(screen, GLFW_KEY_TAB);

        assertFalse(screen.isLaunchConfigPanelOpenForTest());
    }

    private MasterTitleScreen standaloneScreen(boolean canContinue) {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        MasterTitleScreen screen = new MasterTitleScreen(config, new LaunchProfileStore(config), List.of(
                new MasterTitleEntry.Stock(MasterTitleScreen.GameEntry.SONIC_2),
                new MasterTitleEntry.Standalone("sample-game", "Sample Game", canContinue)));
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setSelectedIndexForTest(1);
        return screen;
    }

    private static void press(MasterTitleScreen screen, int key) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        screen.update(input);
    }
}
