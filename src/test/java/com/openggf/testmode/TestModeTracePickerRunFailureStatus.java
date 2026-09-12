package com.openggf.testmode;

import com.openggf.control.InputHandler;
import com.openggf.game.save.SelectedTeam;
import com.openggf.graphics.PixelFont;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.catalog.TraceEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class TestModeTracePickerRunFailureStatus {

    @BeforeEach
    @AfterEach
    void clearHeldFailure() {
        TraceLaunchStatus.clear();
        TraceRunFailureStatus.clear();
    }

    @Test
    void comparisonFailureSurvivesPickerConstructionAndRendersUntilAcknowledged() {
        TraceRunFailureStatus.recordComparison(
                2, "special_stage/4", "level/CPZ/2", 12_345, 12_999);
        PixelFont font = mock(PixelFont.class);
        TestModeTracePicker picker = new TestModeTracePicker(entries("s1"), font);

        picker.render();

        assertTrue(TraceRunFailureStatus.current().isPresent());
        verifyText(font, "TRACE FAILED");
        assertTrue(mockingDetails(font).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("drawText")
                        && call.getArguments().length == 8 && "TRACE FAILED".equals(call.getArgument(0)))
                .anyMatch(call -> (Float) call.getArgument(4) > (Float) call.getArgument(5)
                        && (Float) call.getArgument(4) > (Float) call.getArgument(6)),
                "Failure title must retain its semantic red status colour");
        verifyText(font, "Segment: 2");
        verifyText(font, "Expected: special_stage/4");
        verifyText(font, "Actual: level/CPZ/2");
        verifyText(font, "Cursor: 12345   Steps: 12999");
        verifyText(font, "Enter/Esc Acknowledge");
        assertFalse(displayedText(font).contains("SELECTED:"),
                "Held failure details take precedence over the ordinary selection panel");
    }

    @Test
    void reasonFailureRendersReasonInsteadOfExpectedAndActual() {
        TraceRunFailureStatus.recordReason(
                1, "transition step cap exceeded", 320, 640);
        PixelFont font = mock(PixelFont.class);
        TestModeTracePicker picker = new TestModeTracePicker(entries("s2"), font);

        picker.render();

        verifyText(font, "Reason: transition step cap exceeded");
        assertFalse(displayedText(font).contains("Expected:"));
        assertFalse(displayedText(font).contains("Actual:"));
    }

    @Test
    void confirmAcknowledgesFailureWithoutLaunchingSelectedTrace() {
        TraceRunFailureStatus.recordReason(0, "wrong terminal mode", 99, 100);
        TestModeTracePicker picker = new TestModeTracePicker(
                entries("s1"), mock(PixelFont.class));
        InputHandler input = inputWith(GLFW_KEY_ENTER);

        picker.update(input);

        assertFalse(TraceRunFailureStatus.current().isPresent());
        assertEquals(TestModeTracePicker.Result.NONE, picker.consumeResult());
    }

    @Test
    void confirmCanLaunchOnTheNextPressAfterAcknowledgement() {
        TraceRunFailureStatus.recordReason(0, "wrong terminal mode", 99, 100);
        TestModeTracePicker picker = new TestModeTracePicker(
                entries("s1"), mock(PixelFont.class));

        picker.update(inputWith(GLFW_KEY_ENTER));
        picker.update(inputWith(GLFW_KEY_ENTER));
        picker.render();
        picker.update(new InputHandler());

        assertEquals(TestModeTracePicker.Result.LAUNCH, picker.consumeResult());
    }

    @Test
    void escapeAcknowledgesFailureWithoutLeavingPicker() {
        TraceRunFailureStatus.recordReason(0, "wrong terminal mode", 99, 100);
        TestModeTracePicker picker = new TestModeTracePicker(entries("s1"), null);
        InputHandler input = inputWith(GLFW_KEY_ESCAPE);

        picker.update(input);

        assertFalse(TraceRunFailureStatus.current().isPresent());
        assertEquals(TestModeTracePicker.Result.NONE, picker.consumeResult());
    }

    @Test
    void actualSelectionChangeClearsFailureAndStillMovesCursor() {
        TraceRunFailureStatus.recordReason(0, "wrong destination", 99, 100);
        TestModeTracePicker picker = new TestModeTracePicker(entries("s1", "s2"), null);
        InputHandler input = inputWith(GLFW_KEY_DOWN);

        picker.update(input);

        assertFalse(TraceRunFailureStatus.current().isPresent());
        assertEquals(1, picker.cursor());
    }

    @Test
    void navigationAtCatalogEdgeDoesNotClearFailureBecauseSelectionDidNotChange() {
        TraceRunFailureStatus.recordReason(0, "wrong destination", 99, 100);
        TestModeTracePicker picker = new TestModeTracePicker(entries("s1", "s2"), null);
        InputHandler input = inputWith(GLFW_KEY_UP);

        picker.update(input);

        assertTrue(TraceRunFailureStatus.current().isPresent());
        assertEquals(0, picker.cursor());
    }

    @Test
    void clearIsIdempotent() {
        TraceRunFailureStatus.recordReason(0, "cleanup failed", 0, 1);

        TraceRunFailureStatus.clear();
        TraceRunFailureStatus.clear();

        assertTrue(TraceRunFailureStatus.current().isEmpty());
    }

    @Test
    void aNewFailureReplacesThePreviousDiagnostic() {
        TraceRunFailureStatus.recordReason(0, "first failure", 1, 2);

        TraceRunFailureStatus.recordComparison(3, "expected", "actual", 4, 5);

        TraceRunFailureStatus.Failure held = TraceRunFailureStatus.current().orElseThrow();
        assertEquals(3, held.segmentIndex());
        assertEquals("expected", held.expectedIdentity());
        assertEquals("actual", held.actualIdentity());
        assertEquals(4, held.cursor());
        assertEquals(5, held.stepCount());
    }

    private static void verifyText(PixelFont font, String text) {
        // Wrapping may split a diagnostic mid-word; concatenate actual draw calls
        // without inserting characters, then ignore differences in whitespace.
        assertTrue(displayedText(font).contains(normalized(text)),
                () -> "Missing displayed diagnostic: " + text + " in " + displayedText(font));
    }

    private static String displayedText(PixelFont font) {
        return normalized(mockingDetails(font).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("drawText"))
                .map(call -> (String) call.getArgument(0))
                .collect(java.util.stream.Collectors.joining()));
    }

    private static String normalized(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static InputHandler inputWith(int key) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        return input;
    }

    private static List<TraceEntry> entries(String... gameIds) {
        SelectedTeam team = new SelectedTeam("sonic", List.of());
        return java.util.stream.IntStream.range(0, gameIds.length)
                .mapToObj(i -> new TraceEntry(
                        Path.of("entry-" + i),
                        gameIds[i],
                        0,
                        0,
                        0,
                        0,
                        0,
                        team,
                        Path.of("entry-" + i + ".bk2"),
                        metadataStub(gameIds[i])))
                .toList();
    }

    private static TraceMetadata metadataStub(String gameId) {
        return TraceFixtures.metadata(gameId, 0, 0);
    }
}
