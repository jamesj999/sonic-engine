package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.ModManagerScreenHost;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.*;
import com.openggf.game.recording.*;
import com.openggf.game.recording.menu.UserRecordingMenu;
import com.openggf.mods.*;
import com.openggf.mods.ui.ModManagerScreen;
import com.openggf.testmode.TestModeTracePicker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.openggf.testmode.TraceLaunchStatus;
import com.openggf.testmode.TraceRunFailureStatus;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.openggf.configuration.SonicConfiguration.*;
import static com.openggf.game.MenuFeedback.Cue.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestChildMenuFeedback {
    @TempDir Path directory;

    @BeforeEach
    @AfterEach
    void clearHeldTraceDiagnostics() {
        TraceLaunchStatus.clear();
        TraceRunFailureStatus.clear();
    }

    @Test
    void feedbackScopeRestoresOuterSinkAfterFailureAndDoesNotLeak() {
        List<MenuFeedback.Cue> outer = new ArrayList<>(), inner = new ArrayList<>();
        MenuFeedback.withSink(outer::add, () -> {
            MenuFeedback.emit(NAVIGATE);
            assertThrows(IllegalStateException.class, () -> MenuFeedback.withSink(inner::add, () -> {
                MenuFeedback.emit(ERROR);
                throw new IllegalStateException("test");
            }));
            MenuFeedback.emit(CANCEL);
        });
        MenuFeedback.emit(CONFIRM);
        assertEquals(List.of(NAVIGATE, CANCEL), outer);
        assertEquals(List.of(ERROR), inner);
    }

    @Test
    void settingsCategoriesChoicesAndNestedBackHaveOneCuePerEdge() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        EngineSettingsScreen screen = new EngineSettingsScreen(config, null);
        Driver d = new Driver(screen::update);
        d.press(GLFW_KEY_DOWN); // Audio
        d.press(GLFW_KEY_ENTER); // fields
        d.press(GLFW_KEY_ENTER); // boolean chooser
        d.press(GLFW_KEY_DOWN);
        d.press(GLFW_KEY_ENTER); // set
        d.press(GLFW_KEY_ESCAPE); // categories
        assertEquals(List.of(NAVIGATE, CONFIRM, CONFIRM, NAVIGATE, CONFIRM, CANCEL), d.cues);
        assertFalse(screen.consumeCloseRequested());
    }

    @Test
    void editorBoundariesAreSilentAndValidationRejectsWithoutSuccessCue() {
        MenuTextEditor editor = new MenuTextEditor("TEST", "", 1);
        editor.deferAcceptanceFeedback();
        Driver d = new Driver(editor::update);
        d.press(GLFW_KEY_LEFT);
        d.press(GLFW_KEY_BACKSPACE);
        assertTrue(d.cues.isEmpty());
        MenuInput.handleCharEvent(d.input, 'a');
        d.frame();
        MenuInput.handleCharEvent(d.input, 'b');
        d.frame();
        d.press(GLFW_KEY_ENTER);
        assertEquals(MenuTextEditor.Result.ACCEPTED, editor.consumeResult());
        MenuFeedback.withSink(d.cues::add, () -> editor.reject("Invalid value"));
        d.press(GLFW_KEY_ESCAPE);
        assertEquals(List.of(NAVIGATE, ERROR, ERROR, CANCEL), d.cues);
    }

    @Test
    void physicalControllerHeldDirectionDoesNotRepeatNestedFeedback() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(CONTROLLER_ENABLED, true);
        config.setConfigValue(CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(CONTROLLER_PLAYER2, "none");
        boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), () -> List.of(
                GamepadStateSource.DeviceState.connected(0, "Test pad", buttons, 0, 0)));
        EngineSettingsScreen screen = new EngineSettingsScreen(config, null);
        Driver d = new Driver(screen::update, input);
        d.frame();
        buttons[GLFW_GAMEPAD_BUTTON_A] = true;
        d.frame(); d.frame(); d.frame();
        buttons[GLFW_GAMEPAD_BUTTON_A] = false;
        d.frame();
        buttons[GLFW_GAMEPAD_BUTTON_B] = true;
        d.frame(); d.frame();
        assertEquals(List.of(CONFIRM, CANCEL), d.cues);
        assertFalse(screen.consumeCloseRequested());
    }

    @Test
    void recordingPlaybackReportsSuccessOrFailureWithoutDuplicateOpenCue() {
        UserRecordingEntry entry = new UserRecordingEntry(Path.of("movie.bk2"), "Movie", null,
                10, Instant.EPOCH, RecordingVersionWarning.NONE, null);
        for (boolean fail : List.of(false, true)) {
            UserRecordingMenu screen = new UserRecordingMenu("s1", List.of(entry), null, (recording, options) -> {
                if (fail) throw new IllegalStateException("Unavailable movie");
            });
            Driver d = new Driver(screen::update);
            d.press(GLFW_KEY_ENTER); // options, default Play
            d.press(GLFW_KEY_ENTER);
            assertEquals(List.of(CONFIRM, fail ? ERROR : CONFIRM), d.cues);
        }
    }

    @Test
    void emptyTracePickerDoesNotNavigateButRejectsLaunchAndAllowsBack() {
        TestModeTracePicker picker = new TestModeTracePicker(List.of(), null);
        Driver d = new Driver(picker::update);
        d.press(GLFW_KEY_DOWN);
        d.press(GLFW_KEY_ENTER);
        d.press(GLFW_KEY_ESCAPE);
        assertEquals(List.of(ERROR, CANCEL), d.cues);
        assertEquals(TestModeTracePicker.Result.BACK, picker.consumeResult());
    }

    @Test
    void modHostAdaptsNestedNeutralFeedbackAndHeldInputIsSilent() {
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY, Map.of());
        ModManagerScreen screen = new ModManagerScreen(catalog,
                new PendingModStateEditor(ModState.EMPTY, catalog.scanned(), new ModStateStore(directory.resolve("mods"))),
                new ModRuntimeFindingStore(), null);
        ModManagerScreenHost host = new ModManagerScreenHost(screen);
        Driver d = new Driver(host::update);
        d.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        d.frame(); d.frame(); d.frame(); // empty Details page
        d.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_RELEASE);
        d.frame();
        d.press(GLFW_KEY_DOWN); // no overflow to scroll
        d.press(GLFW_KEY_ESCAPE);
        assertEquals(List.of(CONFIRM, CANCEL), d.cues);
        assertFalse(host.consumeCloseRequested());
    }

    private static final class Driver {
        final InputHandler input;
        final Consumer<InputHandler> update;
        final List<MenuFeedback.Cue> cues = new ArrayList<>();
        Driver(Consumer<InputHandler> update) { this(update, new InputHandler()); }
        Driver(Consumer<InputHandler> update, InputHandler input) { this.update = update; this.input = input; }
        void frame() {
            input.refreshLogicalSnapshot();
            MenuFeedback.withSink(cues::add, () -> update.accept(input));
            input.update();
        }
        void press(int key) {
            input.handleKeyEvent(key, GLFW_PRESS); frame();
            input.handleKeyEvent(key, GLFW_RELEASE); frame();
        }
    }
}
