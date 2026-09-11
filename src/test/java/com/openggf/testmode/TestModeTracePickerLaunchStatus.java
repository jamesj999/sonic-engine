package com.openggf.testmode;

import com.openggf.control.InputHandler;
import com.openggf.game.save.SelectedTeam;
import com.openggf.graphics.PixelFont;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.catalog.TraceEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestModeTracePickerLaunchStatus {

    @BeforeEach
    void resetStatus() {
        TraceLaunchStatus.clear();
        TraceRunFailureStatus.clear();
    }

    @AfterEach
    void clearStatus() {
        TraceLaunchStatus.clear();
        TraceRunFailureStatus.clear();
    }

    @Test
    void launchWaitsUntilLoadingScreenHasRendered() {
        PixelFont font = mock(PixelFont.class);
        TestModeTracePicker picker = new TestModeTracePicker(List.of(entry()), font);

        picker.update(inputWith(GLFW_KEY_ENTER));
        assertEquals(TestModeTracePicker.Result.NONE, picker.consumeResult());

        picker.render();
        verifyText(font, "LOADING TRACE...");
        verifyText(font, "s1/entry");

        picker.update(mock(InputHandler.class));
        assertEquals(TestModeTracePicker.Result.LAUNCH, picker.consumeResult());
    }

    @Test
    void failedLaunchLeavesDiagnosticVisibleUntilAcknowledged() {
        PixelFont font = mock(PixelFont.class);
        TestModeTracePicker picker = new TestModeTracePicker(List.of(entry()), font);
        TraceLaunchStatus.record(entry(), new IllegalArgumentException(
                "hardware_timing.jsonl: events must use canonical ordering"));

        picker.launchFailed();
        picker.render();

        verifyText(font, "TRACE LAUNCH FAILED");
        verifyText(font, "Trace: s1/entry");
        verifyText(font,
                "Reason: hardware_timing.jsonl: events must use canonical ordering");
        assertTrue(TraceLaunchStatus.current().isPresent());

        picker.update(inputWith(GLFW_KEY_ENTER));

        assertFalse(TraceLaunchStatus.current().isPresent());
        assertEquals(TestModeTracePicker.Result.NONE, picker.consumeResult());
    }

    @Test
    void syntheticRunUsesManifestIdentityInPickerLoadingAndFailureText() {
        PixelFont font = mock(PixelFont.class);
        TraceEntry run = runEntry();
        TestModeTracePicker picker = new TestModeTracePicker(List.of(run), font);

        picker.render();
        verifyText(font, "> RUN s1-complete-run (2 segments)");
        picker.update(inputWith(GLFW_KEY_ENTER));
        picker.render();
        verifyText(font, "s1/s1-complete-run");

        TraceLaunchStatus.record(run, "parser failed");
        picker.launchFailed();
        picker.render();
        verifyText(font, "Trace: s1/s1-complete-run");
    }

    private static void verifyText(PixelFont font, String text) {
        verify(font).drawText(eq(text), anyInt(), anyInt(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    private static InputHandler inputWith(int key) {
        InputHandler input = mock(InputHandler.class);
        when(input.isKeyPressedWithoutModifiers(key)).thenReturn(true);
        return input;
    }

    private static TraceEntry entry() {
        SelectedTeam team = new SelectedTeam("sonic", List.of());
        return new TraceEntry(
                Path.of("entry"), "s1", 0, 0, 0, 0, 0, team,
                Path.of("entry.bk2"), metadataStub());
    }

    private static TraceEntry runEntry() {
        TraceRunManifest.Segment first = new TraceRunManifest.Segment(
                "first_completerun", "level", "complete_run",
                0, 1, 0, 1, null, null);
        TraceRunManifest.Segment second = new TraceRunManifest.Segment(
                "second_completerun", "level", "complete_run",
                1, 1, 1, 1, null, null);
        TraceRunManifest manifest = new TraceRunManifest(
                "s1", "s1-complete-run", "s1-complete-run.bk2",
                "checksum", List.of(first, second), List.of());
        return new TraceEntry(
                Path.of("s1"), "s1", 0, 0, 2, 0, 0,
                new SelectedTeam("sonic", List.of()),
                Path.of("s1-complete-run.bk2"), metadataStub(),
                Path.of("s1"), manifest);
    }

    private static TraceMetadata metadataStub() {
        return TraceFixtures.metadata("s1", 0, 0);
    }
}
