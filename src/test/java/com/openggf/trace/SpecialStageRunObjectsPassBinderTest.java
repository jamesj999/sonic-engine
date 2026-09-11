package com.openggf.trace;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialStageRunObjectsPassBinderTest {

    @Test
    void twoPassObservationStepsTwiceInSequenceWithRecordedPhysicalInput() {
        SpecialStageRunObjectsPassBinder binder = new SpecialStageRunObjectsPassBinder(List.of(
                pass(434, 0, 433, 434, 433, 0x08, 0, 0, 0),
                pass(434, 1, 434, 434, 434, 0x08, 0, 0x08, 0)));

        assertTrue(binder.passesForObservation(433).isEmpty());
        ArrayList<Integer> stepped = new ArrayList<>();
        binder.passesForObservation(434).forEach(pass -> stepped.add(pass.sequence()));
        assertEquals(List.of(0, 1), stepped);
        assertEquals(0x08, binder.latestCompleted().orElseThrow().p1Held());
        assertTrue(binder.passesForObservation(436).isEmpty());
    }

    @Test
    void noPassObservationDoesNotExposeAnEngineStep() {
        SpecialStageRunObjectsPassBinder binder = new SpecialStageRunObjectsPassBinder(
                List.of(pass(918, 0, 916, 917, 916, 0, 0, 0, 0)),
                919,
                frame -> frame == 918);

        assertTrue(binder.passesForObservation(916).isEmpty());
        assertTrue(binder.passesForObservation(917).isEmpty());
        assertEquals(0, binder.passesForObservation(918).getFirst().sequence());
        assertFalse(binder.hasRemaining());
    }

    @Test
    void repeatedHeldSampleDoesNotSynthesizeAnotherPressEdge() {
        SpecialStageRunObjectsPassBinder binder = new SpecialStageRunObjectsPassBinder(List.of(
                pass(10, 0, 10, 10, 10, 0x08, 0x04, 0, 0),
                pass(11, 1, 11, 11, 11, 0x08, 0x04, 0x08, 0x04)));

        assertEquals(0x08, binder.passesForObservation(10).getFirst().p1Pressed());
        assertEquals(0x04, binder.latestCompleted().orElseThrow().p2Pressed());
        SpecialStageRunObjectsPassBinder.CompletedPass repeated =
                binder.passesForObservation(11).getFirst();
        assertEquals(0, repeated.p1Pressed());
        assertEquals(0, repeated.p2Pressed());
    }

    @Test
    void skippedJoypadSampleIsRejectedBecauseEveryActivePassConsumesOnePoll() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(
                        pass(10, 0, 10, 10, 10, 100, 0, 0, 0, 0),
                        pass(12, 1, 12, 12, 12, 102, 0x08, 0, 0x04, 0))));

        assertTrue(error.getMessage().contains("input sample sequence discontinuity"));
    }

    @Test
    void sequenceGapIsRejectedBeforeComparison() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(
                        pass(434, 0, 434), pass(436, 2, 436))));

        assertTrue(error.getMessage().contains("sequence discontinuity"));
    }

    @Test
    void bindingBeforeFirstEligibleFrameIsRejectedBeforeComparison() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(
                        pass(434, 0, 435, 434, 435, 0, 0, 0, 0))));

        assertTrue(error.getMessage().contains("precedes first eligible"));
    }

    @Test
    void completionCursorAfterBoundObservationIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(
                        pass(434, 0, 433, 435, 433, 0, 0, 0, 0))));

        assertTrue(error.getMessage().contains("completion cursor"));
    }

    @Test
    void bindingPastAnEarlierNonLagObservationIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(
                        pass(12, 0, 10, 10, 10, 0, 0, 0, 0))));

        assertTrue(error.getMessage().contains("first eligible observation"));
    }

    @Test
    void joypadPollMayPrecedePassCursorWhenCpuWorkCrossesVblank() {
        SpecialStageRunObjectsPassBinder binder = new SpecialStageRunObjectsPassBinder(List.of(
                pass(918, 0, 916, 917, 915, 0x08, 0, 0, 0)),
                919,
                frame -> frame == 918);

        SpecialStageRunObjectsPassBinder.CompletedPass pass =
                binder.passesForObservation(918).getFirst();
        assertEquals(915, pass.inputSampleFrame());
        assertEquals(3669, pass.inputSampleBk2Frame());
        assertEquals(100, pass.inputSampleSequence());
    }

    @Test
    void terminalLagObservationUsesItsOwnLaterJoypadIdentity() {
        SpecialStageRunObjectsPassBinder binder = new SpecialStageRunObjectsPassBinder(
                List.of(
                        pass(10, 0, 10, 10, 10, 100, 0x08, 0, 0, 0),
                        pass(11, 1, 11, 11, 11, 101, 0x10, 0, 0x08, 0)),
                12,
                frame -> frame == 10 || frame == 11);

        SpecialStageRunObjectsPassBinder.CompletedPass preceding =
                binder.passesForObservation(10).getFirst();
        SpecialStageRunObjectsPassBinder.CompletedPass terminal =
                binder.passesForObservation(11).getFirst();

        assertEquals(10, preceding.inputSampleFrame());
        assertEquals(11, terminal.inputSampleFrame());
        assertEquals(101, terminal.inputSampleSequence());
        assertEquals(0x10, terminal.p1Held());
        assertEquals(0x10, terminal.p1Pressed(),
                "terminal pass must not reuse the preceding RIGHT sample");
    }

    @Test
    void missingPhysicalInputIsRejectedBeforeReplay() {
        TraceEvent.StateSnapshot malformed = new TraceEvent.StateSnapshot(10, Map.of(
                "type", "run_objects_end",
                "pass_sequence", 0,
                "first_eligible_frame", 10,
                "completion_cursor_frame", 10,
                "input_sample_frame", 10,
                "input_sample_bk2_frame", 2764,
                "previous_input_sample_frame", 9,
                "previous_input_sample_bk2_frame", 2763,
                "input_sample_sequence", 0));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(List.of(malformed)));
        assertTrue(error.getMessage().contains("p1_held"));
    }

    @Test
    void tamperedAuxBk2IdentityIsRejectedAgainstMovieRows() throws Exception {
        TraceEvent.StateSnapshot tampered = passWithInputIdentity(
                1, 0, 1, 1, 1, 0, 0,
                0x08, 0x04, 0, 0);

        IllegalArgumentException error = constructWithMovieValidation(tampered);
        assertTrue(error.getMessage().contains("BK2 identity"));
    }

    @Test
    void tamperedAuxHeldValueIsRejectedAgainstMovieRows() throws Exception {
        TraceEvent.StateSnapshot tampered = passWithInputIdentity(
                1, 0, 1, 1, 1, 1, 0,
                0x04, 0x04, 0, 0);

        IllegalArgumentException error = constructWithMovieValidation(tampered);
        assertTrue(error.getMessage().contains("P1 held"));
    }

    private static IllegalArgumentException constructWithMovieValidation(
            TraceEvent.StateSnapshot snapshot) {
        List<Bk2FrameInput> frames = List.of(
                new Bk2FrameInput(0, 0, 0, false, 0, 0, false, "zero"),
                new Bk2FrameInput(1, 0x08, 0, false, 0x04, 0, false, "right/left"));
        return assertThrows(IllegalArgumentException.class,
                () -> new SpecialStageRunObjectsPassBinder(
                        List.of(snapshot), 2, frame -> true, 0, frames));
    }

    private static TraceEvent.StateSnapshot pass(int frame, int sequence, int firstEligible) {
        return pass(frame, sequence, firstEligible, frame, firstEligible,
                0, 0, 0, 0);
    }

    private static TraceEvent.StateSnapshot pass(int frame, int sequence, int firstEligible,
            int completionCursor, int inputSampleFrame, int p1Held, int p2Held,
            int previousP1Held, int previousP2Held) {
        return pass(frame, sequence, firstEligible, completionCursor, inputSampleFrame,
                100 + sequence, p1Held, p2Held, previousP1Held, previousP2Held);
    }

    private static TraceEvent.StateSnapshot passWithInputIdentity(
            int frame, int sequence, int firstEligible, int completionCursor,
            int inputSampleFrame, int inputSampleBk2Frame,
            int previousInputSampleBk2Frame,
            int p1Held, int p2Held, int previousP1Held, int previousP2Held) {
        return new TraceEvent.StateSnapshot(frame, Map.ofEntries(
                Map.entry("type", "run_objects_end"),
                Map.entry("pass_sequence", sequence),
                Map.entry("first_eligible_frame", firstEligible),
                Map.entry("completion_cursor_frame", completionCursor),
                Map.entry("input_sample_frame", inputSampleFrame),
                Map.entry("input_sample_bk2_frame", inputSampleBk2Frame),
                Map.entry("input_sample_sequence", sequence),
                Map.entry("previous_input_sample_frame", 0),
                Map.entry("previous_input_sample_bk2_frame", previousInputSampleBk2Frame),
                Map.entry("p1_held", p1Held),
                Map.entry("p2_held", p2Held),
                Map.entry("previous_p1_held", previousP1Held),
                Map.entry("previous_p2_held", previousP2Held)));
    }

    private static TraceEvent.StateSnapshot pass(int frame, int sequence, int firstEligible,
            int completionCursor, int inputSampleFrame, int inputSampleSequence,
            int p1Held, int p2Held, int previousP1Held, int previousP2Held) {
        return new TraceEvent.StateSnapshot(frame, Map.ofEntries(
                Map.entry("type", "run_objects_end"),
                Map.entry("pass_sequence", sequence),
                Map.entry("first_eligible_frame", firstEligible),
                Map.entry("completion_cursor_frame", completionCursor),
                Map.entry("input_sample_frame", inputSampleFrame),
                Map.entry("input_sample_bk2_frame", 2754 + inputSampleFrame),
                Map.entry("previous_input_sample_frame", inputSampleFrame - 1),
                Map.entry("previous_input_sample_bk2_frame", 2753 + inputSampleFrame),
                Map.entry("input_sample_sequence", inputSampleSequence),
                Map.entry("p1_held", p1Held),
                Map.entry("p2_held", p2Held),
                Map.entry("previous_p1_held", previousP1Held),
                Map.entry("previous_p2_held", previousP2Held)));
    }
}
