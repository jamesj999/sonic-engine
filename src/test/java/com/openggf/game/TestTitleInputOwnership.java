package com.openggf.game;

import com.openggf.capture.LiveCaptureChord;
import com.openggf.configuration.KeyChord;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.color.DisplayColorProfile;
import com.openggf.graphics.color.DisplayColorProfileController;
import com.openggf.graphics.shaderlib.DisplayShaderPickerController;
import com.openggf.graphics.shaderlib.DisplayShaderPresetRef;
import com.openggf.graphics.shaderlib.DisplayShaderSelectionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestTitleInputOwnership {
    @TempDir Path root;

    @Test
    void actionPaneKeepsPrintableKeysOutOfRealGlobalDisplayControllers() {
        Fixture f = new Fixture();
        f.enterActions();
        MenuTextEditor editor = new MenuTextEditor("Path", "", 100);
        int[] keys = {GLFW_KEY_V, GLFW_KEY_LEFT_BRACKET, GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_BACKSLASH};
        String characters = "v[]\\";
        for (int i = 0; i < keys.length; i++) {
            f.input.handleKeyEvent(keys[i], GLFW_PRESS);
            MenuInput.handleCharEvent(f.input, characters.charAt(i));
            assertFalse(f.routeDisplay(), "The editor must keep this presentation frame");
            editor.update(f.input);
            assertNull(f.persisted.get(), "Typing V must not save display configuration");
            assertFalse(f.picker.isOpen(), "Typing a path separator must not open the shader picker");
            f.release(keys[i]);
        }
        assertEquals(characters, editor.value());
        assertEquals(0, f.shaderUpdates.get());
        assertEquals(DisplayColorProfile.RAW_RGB, f.color.currentProfile());
    }

    @Test
    void plainGameSelectionStillRoutesDisplayShortcuts() {
        Fixture f = new Fixture();
        f.input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        assertFalse(f.routeDisplay());
        assertEquals(DisplayColorProfile.MD_ANALOG, f.persisted.get());
        assertEquals(1, f.shaderUpdates.get());
        f.release(GLFW_KEY_V);
        f.input.handleKeyEvent(GLFW_KEY_BACKSLASH, GLFW_PRESS);
        assertTrue(f.routeDisplay());
        assertTrue(f.picker.isOpen());
        assertEquals(1, f.shaderUpdates.get(), "An open picker owns the frame before shader cycling");
    }

    @Test
    void alreadyOpenPickerCanCloseEvenWhenTitleOwnsItsUnderlyingMenu() {
        Fixture f = new Fixture();
        f.input.handleKeyEvent(GLFW_KEY_BACKSLASH, GLFW_PRESS);
        assertTrue(f.routeDisplay());
        f.release(GLFW_KEY_BACKSLASH);
        f.enterActions();
        f.input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        assertTrue(f.routeDisplay(), "Closing picker input must not fall through into the title child");
        assertFalse(f.picker.isOpen());
        assertNull(f.persisted.get());
        assertEquals(0, f.shaderUpdates.get());
    }

    @Test
    void programmaticallyOpenedChildOwnsInputWithoutActionPaneFocus() {
        Fixture f = new Fixture();
        f.title.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        assertTrue(f.title.tryOpenTimeAttackMenu());
        f.input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        assertFalse(f.routeDisplay());
        assertNull(f.persisted.get());
        assertEquals(0, f.shaderUpdates.get());
    }

    @Test
    void captureChordIsConsumedWhileBlockedAndCannotFireAfterReturningWithKeyHeld() {
        Fixture f = new Fixture();
        f.enterActions();
        LiveCaptureChord detector = new LiveCaptureChord();
        KeyChord binding = KeyChord.of(GLFW_KEY_C);
        java.util.function.BooleanSupplier updateChord = () -> detector.update(binding,
                f.input.isPhysicalKeyDown(GLFW_KEY_C), false, false, false, false);
        f.input.handleKeyEvent(GLFW_KEY_C, GLFW_PRESS);
        assertFalse(TitleInputOwnership.routeCapture(GameMode.MASTER_TITLE_SCREEN, f.title, updateChord));
        f.input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        f.title.update(f.input);
        f.release(GLFW_KEY_ESCAPE);
        assertFalse(TitleInputOwnership.routeCapture(GameMode.MASTER_TITLE_SCREEN, f.title, updateChord),
                "Returning to game selection must not trigger a suppressed held chord");
        f.release(GLFW_KEY_C);
        assertFalse(TitleInputOwnership.routeCapture(GameMode.MASTER_TITLE_SCREEN, f.title, updateChord));
        f.input.handleKeyEvent(GLFW_KEY_C, GLFW_PRESS);
        assertTrue(TitleInputOwnership.routeCapture(GameMode.MASTER_TITLE_SCREEN, f.title, updateChord));
    }

    @Test
    void playbackShortcutsDoNotRunAnywhereInMasterTitleButRemainLiveInGameplay() {
        AtomicInteger playbackUpdates = new AtomicInteger();
        TitleInputOwnership.routePlayback(GameMode.MASTER_TITLE_SCREEN, playbackUpdates::incrementAndGet);
        assertEquals(0, playbackUpdates.get());
        TitleInputOwnership.routePlayback(GameMode.LEVEL, playbackUpdates::incrementAndGet);
        assertEquals(1, playbackUpdates.get());
    }

    private final class Fixture {
        final InputHandler input = new InputHandler();
        final MasterTitleScreen title = new MasterTitleScreen(SonicConfigurationService.createStandalone(root));
        final AtomicReference<DisplayColorProfile> persisted = new AtomicReference<>();
        final AtomicInteger shaderUpdates = new AtomicInteger();
        final DisplayColorProfileController color = new DisplayColorProfileController(
                DisplayColorProfile.RAW_RGB, GLFW_KEY_V, persisted::set, () -> { });
        final DisplayShaderPickerController picker = new DisplayShaderPickerController(
                new DisplayShaderSelectionModel(List.of(DisplayShaderPresetRef.OFF)), GLFW_KEY_BACKSLASH);

        Fixture() { title.setStateForTest(MasterTitleScreen.State.ACTIVE); }

        boolean routeDisplay() {
            return TitleInputOwnership.routeDisplay(GameMode.MASTER_TITLE_SCREEN, title, picker.isOpen(), () -> {
                boolean wasOpen = picker.isOpen();
                var action = picker.update(input, DisplayShaderPresetRef.OFF);
                return wasOpen || picker.isOpen() || action.type() != DisplayShaderPickerController.ActionType.NONE;
            }, () -> color.update(input), shaderUpdates::incrementAndGet);
        }

        void enterActions() {
            input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
            title.update(input);
            release(GLFW_KEY_ENTER);
        }

        void release(int key) {
            input.handleKeyEvent(key, GLFW_RELEASE);
            input.update();
        }
    }
}
