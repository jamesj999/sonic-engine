package com.openggf.game;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestMasterTitleHub {
    @TempDir Path directory;

    private MasterTitleScreen screen() {
        var config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        var screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        return screen;
    }

    @Test void directionsSwitchPanesAndOnlyCycleGamesInTheGamePane() {
        var screen = screen();
        press(screen, GLFW_KEY_UP);
        assertEquals("s1", screen.getSelectedGameId());
        press(screen, GLFW_KEY_DOWN);
        assertEquals("s2", screen.getSelectedGameId());
        press(screen, GLFW_KEY_RIGHT);
        assertFalse(screen.isGameSelected(), "right only opens the actions pane");
        press(screen, GLFW_KEY_RIGHT);
        assertEquals("s2", screen.getSelectedGameId(), "actions never cycle the selected game");
        press(screen, GLFW_KEY_DOWN);
        assertEquals("s2", screen.getSelectedGameId(), "down selects actions in the right pane");
        press(screen, GLFW_KEY_LEFT);
        press(screen, GLFW_KEY_DOWN);
        assertEquals("s3k", screen.getSelectedGameId());
    }

    @Test void launchOptionsAreReachableWithoutShortcut() {
        var screen = screen();
        press(screen, GLFW_KEY_ENTER);
        press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        assertTrue(screen.isLaunchConfigPanelOpenForTest());
        assertFalse(screen.isGameSelected());
    }

    @Test void startRemainsReachableWithTwoDistinctConfirmations() {
        var screen = screen();
        press(screen, GLFW_KEY_ENTER);
        press(screen, GLFW_KEY_ENTER);
        assertTrue(screen.isGameSelected());
    }

    @Test void cancelledLaunchDraftDoesNotWriteAndFailedSaveCanBeRetried() {
        var config = SonicConfigurationService.createStandalone(directory);
        var store = new com.openggf.game.launch.LaunchProfileStore(config) {
            boolean fail = true;
            @Override public void save(MasterTitleScreen.GameEntry game,
                    com.openggf.game.launch.LaunchProfile profile) {
                if (fail) {
                    fail = false;
                    throw new java.io.UncheckedIOException(new java.io.IOException("Read-only destination"));
                }
                super.save(game, profile);
            }
        };
        var screen = new MasterTitleScreen(config, store);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        press(screen, GLFW_KEY_TAB);
        press(screen, GLFW_KEY_RIGHT);
        press(screen, GLFW_KEY_ESCAPE);
        assertFalse(screen.isLaunchConfigPanelOpenForTest());
        assertFalse(store.load(MasterTitleScreen.GameEntry.SONIC_2).rewind());

        press(screen, GLFW_KEY_TAB);
        press(screen, GLFW_KEY_RIGHT);
        press(screen, GLFW_KEY_ENTER);
        assertTrue(screen.isLaunchConfigPanelOpenForTest(), "failed save retains editable draft");
        assertTrue(screen.currentLaunchProfileForTest().rewind());
        assertFalse(store.load(MasterTitleScreen.GameEntry.SONIC_2).rewind());
        press(screen, GLFW_KEY_ENTER);
        assertFalse(screen.isLaunchConfigPanelOpenForTest());
        assertTrue(store.load(MasterTitleScreen.GameEntry.SONIC_2).rewind());
    }

    @Test void scaledRomLogosStayInTheLeftPaneWithoutStretching() {
        for (int viewport : new int[] {320, 400, 528}) {
            var logo = MasterTitleScreen.hubLogoLayout(320, 224, viewport);
            assertTrue(logo.x() >= 9);
            assertTrue(logo.x() + logo.width() <= viewport / 2f - 9);
            assertTrue(logo.height() <= 112);
            assertEquals(320.0 / 224, (double) logo.width() / logo.height(), .02);
            assertTrue(logo.y() >= 224 - 155);
        }
    }

    static void press(MasterTitleScreen screen, int key) {
        var input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        screen.update(input);
    }
}
