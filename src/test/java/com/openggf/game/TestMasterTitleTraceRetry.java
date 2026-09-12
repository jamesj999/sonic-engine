package com.openggf.game;

import com.openggf.TraceSessionLauncher;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.save.SelectedTeam;
import com.openggf.graphics.PixelFont;
import com.openggf.testmode.TestModeTracePicker;
import com.openggf.testmode.TraceLaunchStatus;
import com.openggf.testmode.TraceRunFailureStatus;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.catalog.TraceEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import com.openggf.game.launch.LaunchProfileStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.mockito.Mockito.*;

/** Exercises the production title-screen launch dispatch, not just the picker's reset helper. */
class TestMasterTitleTraceRetry {
    @TempDir Path directory;

    @BeforeEach
    @AfterEach
    void clearHeldDiagnostics() {
        TraceLaunchStatus.clear();
        TraceRunFailureStatus.clear();
    }

    @Test
    void failedLaunchCanBeAcknowledgedRetriedAndReplacedWithAnotherSelection() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        TraceEntry broken = entry("broken");
        TraceEntry working = entry("working");
        TestModeTracePicker picker = new TestModeTracePicker(List.of(broken, working), mock(PixelFont.class));
        screen.setTracePickerForTest(picker);

        try (var launcher = mockStatic(TraceSessionLauncher.class)) {
            launcher.when(() -> TraceSessionLauncher.launch(broken)).thenAnswer(invocation -> {
                TraceLaunchStatus.record(broken, "Unable to read trace payload");
                return false;
            });
            launcher.when(() -> TraceSessionLauncher.launch(working)).thenReturn(true);

            launchSelected(screen, picker);
            launcher.verify(() -> TraceSessionLauncher.launch(broken));
            assertTrue(TraceLaunchStatus.current().isPresent());
            press(screen, GLFW_KEY_ENTER); // Acknowledge; this must not automatically retry.
            assertTrue(TraceLaunchStatus.current().isEmpty());
            launcher.verify(() -> TraceSessionLauncher.launch(broken), times(1));

            launchSelected(screen, picker); // Retry the same entry with a distinct confirmation.
            launcher.verify(() -> TraceSessionLauncher.launch(broken), times(2));
            assertTrue(TraceLaunchStatus.current().isPresent());
            press(screen, GLFW_KEY_ESCAPE); // Acknowledge and remain in the picker.
            assertTrue(TraceLaunchStatus.current().isEmpty());
            press(screen, GLFW_KEY_DOWN);
            assertEquals(working, picker.selectedEntry(), "Failure must release the old loading selection");

            launchSelected(screen, picker);
            launcher.verify(() -> TraceSessionLauncher.launch(working), times(1));
            assertTrue(TraceLaunchStatus.current().isEmpty());
        }
    }

    @Test
    void rootFailureIsNotAnnouncedAgainWhenThePickerIsRecreated() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        config.setConfigValue(SonicConfiguration.TRACE_CATALOG_DIR, directory.toString());
        List<MasterTitleScreen.AudioCue> cues = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config, new LaunchProfileStore(config), cues::add);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        var fontField = MasterTitleScreen.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(screen, mock(PixelFont.class));
        TraceLaunchStatus.record(entry("broken"), "Unable to read trace payload");

        screen.showRomLoadError("s1");
        press(screen, GLFW_KEY_ENTER);
        screen.update(new InputHandler());

        assertEquals(List.of(MasterTitleScreen.AudioCue.ERROR, MasterTitleScreen.AudioCue.CANCEL), cues);
        assertTrue(TraceLaunchStatus.current().isPresent());
    }

    private static void launchSelected(MasterTitleScreen screen, TestModeTracePicker picker) {
        press(screen, GLFW_KEY_ENTER);
        picker.render(); // The production launch boundary requires a presented loading screen.
        screen.update(new InputHandler());
    }
    private static void press(MasterTitleScreen screen, int key) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        screen.update(input);
    }
    private static TraceEntry entry(String name) {
        return new TraceEntry(Path.of(name), "s1", 0, 0, 100, 0, 0,
                new SelectedTeam("sonic", List.of()), Path.of(name + ".bk2"), mock(TraceMetadata.class));
    }
}
