package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState.PlayerState;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.SpecialStageExpectedState;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.SpecialStageTraceFrame;
import com.openggf.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S2SpecialStageExpectedComparisonTest {

    @Test
    void f799EngineThreeRingsMatchesAtomicRunObjectsEndInsteadOfMidPassCsv() {
        SpecialStageTraceFrame csv = frame(799, 1);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(csv,
                List.of(snapshot(799, 3)));
        Sonic2SpecialStageComparisonState engine = engine(3);

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(expected, engine);

        assertEquals(Severity.MATCH, compared.get("combined_rings").severity());
    }

    @Test
    void f791EngineOneRingStillMatchesExactPassEnd() {
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(791, 1),
                List.of(snapshot(791, 1)));

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(expected, engine(1));

        assertEquals(Severity.MATCH, compared.get("combined_rings").severity());
    }

    @Test
    void signedRomTrackCoordinatesMatchEngineSignedIntegers() {
        SpecialStageTraceFrame.CharacterState sonic = character(0xFFFA, 0xFFF2, 0x006E);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(sonic), List.of());

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(new PlayerState(-6, -14, 0x006E, 64,
                                "NORMAL", 0, 1, 2, 0, 0, 0, 0)));

        assertEquals(Severity.MATCH, compared.get("sonic_ss_x").severity());
        assertEquals(Severity.MATCH, compared.get("sonic_ss_y").severity());
    }

    @Test
    void specialStageDepthRemainsAnUnsignedRawWord() {
        SpecialStageTraceFrame.CharacterState sonic = character(0, 0, 0xFFFA);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(sonic), List.of());

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(new PlayerState(0, 0, -6, 64,
                                "NORMAL", 0, 1, 2, 0, 0, 0, 0)));

        assertEquals(Severity.ERROR, compared.get("sonic_ss_z").severity());
        assertEquals("65530", compared.get("sonic_ss_z").expected());
    }

    @Test
    void perPlayerRingMismatchIsAnError() {
        SpecialStageTraceFrame.CharacterState sonic = character(0, 0, 0x006E, 5);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(sonic), List.of());

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(new PlayerState(0, 0, 0x006E, 64,
                                "NORMAL", 0, 1, 2, 4, 0, 0, 0)));

        FieldComparison rings = compared.get("sonic_rings");
        assertNotNull(rings);
        assertEquals(Severity.ERROR, rings.severity());
        assertEquals("5", rings.expected());
        assertEquals("4", rings.actual());
    }

    @Test
    void rawVblankPlayerTimersAreNotComparedAsAnAtomicObjectPass() {
        SpecialStageTraceFrame.CharacterState sonic = new SpecialStageTraceFrame.CharacterState(
                true, 0, 0, 0, 0, 0x6E, 64, 2, 0, 0, 1, 2,
                0, 8, 7, 6);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(
                new SpecialStageTraceFrame(1, 0, 0, false,
                        12, 0, 7, 4, 0, 2, 5, 7, 0, 0, 4, 0xFF,
                        sonic, character(128, 90, 300)),
                List.of());
        PlayerState engineSonic = new PlayerState(0, 0, 0x6E, 64,
                "NORMAL", 0, 1, 2, 0, 0, 0, 0);

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(engineSonic));

        assertEquals(Severity.ERROR, compared.get("swap_positions_flag").severity());
        assertFalse(compared.containsKey("sonic_hurt_timer"));
        assertFalse(compared.containsKey("sonic_slide_timer"));
        assertFalse(compared.containsKey("sonic_flip_timer"));
    }

    @Test
    void atomicPassEndTimersUseTheirRatchetedSeverities() {
        TraceEvent.StateSnapshot base = snapshot(799, 0);
        Map<String, Object> fields = new HashMap<>(base.fields());
        fields.put("sonic_hurt_timer", 8);
        fields.put("sonic_slide_timer", 7);
        fields.put("sonic_flip_timer", 6);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(799, 0),
                List.of(new TraceEvent.StateSnapshot(799, fields)));

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(new PlayerState(0, 0, 0x6E, 64,
                                "NORMAL", 0, 1, 2, 0, 0, 0, 0)));

        assertEquals(Severity.ERROR, compared.get("sonic_hurt_timer").severity());
        assertEquals(Severity.ERROR, compared.get("sonic_slide_timer").severity());
        assertEquals(Severity.ERROR, compared.get("sonic_flip_timer").severity());
    }

    @Test
    void playerAnimationTimerUsesAtomicPassEndAsRatchetedError() {
        TraceEvent.StateSnapshot base = snapshot(799, 0);
        Map<String, Object> fields = new HashMap<>(base.fields());
        fields.put("player_anim_frame_timer", 4);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(799, 0),
                List.of(new TraceEvent.StateSnapshot(799, fields)));

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(
                        expected, engine(0, 3, 0), false);

        assertEquals(Severity.ERROR, compared.get("player_anim_frame_timer").severity());
    }

    @Test
    void remainingManagerDiagnosticsAreRatchetedErrors() {
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(799, 0),
                List.of(snapshot(799, 0)));
        Sonic2SpecialStageComparisonState mismatched = new Sonic2SpecialStageComparisonState(
                12, 5, 8, 5, 10, 7, 0, 0, 10, 0, false,
                new PlayerState(128, 90, 300, 64, "NORMAL", 0, 1, 2,
                        0, 0, 0, 0),
                new PlayerState(128, 90, 300, 64, "NORMAL", 0, 1, 2,
                        0, 0, 0, 0));

        Map<String, FieldComparison> compared =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(expected, mismatched);

        assertEquals(Severity.ERROR, compared.get("track_drawing_index").severity());
        assertEquals(Severity.ERROR, compared.get("track_duration_timer").severity());
        assertEquals(Severity.ERROR, compared.get("tails_control_counter").severity());
    }

    @Test
    void ringsToGoIsAbsentBeforeRefreshGateAndBcdDecodedAfterIt() {
        TraceEvent.StateSnapshot base = snapshot(1324, 12);
        Map<String, Object> fields = new HashMap<>(base.fields());
        fields.put("rings_togo_bcd", 0x38);
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(1324, 12),
                List.of(new TraceEvent.StateSnapshot(1324, fields)));
        Sonic2SpecialStageComparisonState state = engine(12, 4, 37);

        Map<String, FieldComparison> before =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(expected, state, false);
        Map<String, FieldComparison> after =
                AbstractS2SpecialStageTraceReplayTest.compareExpectedFrame(expected, state, true);

        assertFalse(before.containsKey("rings_togo_bcd"));
        assertEquals(Severity.ERROR, after.get("rings_togo_bcd").severity());
        assertEquals(0, AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0));
        assertEquals(7, AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x0007));
        assertEquals(38, AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x0038));
        assertEquals(123, AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x0123));
        assertThrows(IllegalArgumentException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x000A));
        assertThrows(IllegalArgumentException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x0A00));
        assertThrows(IllegalArgumentException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.decodeRingsToGoBcd(0x1000));
    }

    @Test
    void refreshDiscoveryRejectsMissingInitialTriggerSample() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        List.of(), passes(3), List.of(0, 0, 0, 0xFF), List.of(3)));
        assertTrue(ex.getMessage().contains("initial"), ex.getMessage());
    }

    @Test
    void refreshDiscoveryRejectsMissingTriggerClear() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(3), List.of(0, 0, 0, 0xFF), List.of(3)));
        assertTrue(ex.getMessage().contains("clear"), ex.getMessage());
    }

    @Test
    void refreshDiscoveryRejectsDuplicateTriggerSamplesAtOneObservation() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(1, 0),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(1, 0));
        assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2), List.of(0, 0, 0xFF), List.of(2)));
    }

    @Test
    void refreshDiscoveryRequiresOneTerminalRiseMatchingFinishObservation() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(1, 0));
        assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2), List.of(0, 0, 0), List.of(2)));
        assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2), List.of(0, 0, 0xFF), List.of(2, 2)));
        assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2), List.of(0, 0, 0xFF), List.of(1)));
    }

    @Test
    void refreshDiscoverySupportsMultipleCompleteMessageCycles() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(1, 0),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(4, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(6, 0));
        assertEquals(java.util.Set.of(2, 7, 9),
                AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2, 3, 7),
                        List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF), List.of(9)));
    }

    @Test
    void refreshDiscoveryRejectsMultiplePassIdentitiesAtSelectedObservation() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(1, 0));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passes(2, 2), List.of(0, 0, 0xFF), List.of(2)));
        assertTrue(ex.getMessage().contains("multiple completed passes"), ex.getMessage());
    }

    @Test
    void committedTraceComparesFirstCompletedPassAfterTriggerTransitionOnly() throws Exception {
        SpecialStageTraceData trace = SpecialStageTraceData.load(Path.of(
                "src/test/resources/traces/s2/special_stage"));

        // Observations of the committed fixture's passes 748, 749 and 751. The
        // recorder's frame labelling shifted by one when its off-by-one was
        // corrected, so these are read off the current artifact's own
        // run_objects_end observations, not carried over from the old one.
        assertFalse(AbstractS2SpecialStageTraceReplayTest.isRingsToGoRefreshFrame(trace, 1323),
                "the transition observation still contains the pre-refresh BCD cell");
        assertEquals(true,
                AbstractS2SpecialStageTraceReplayTest.isRingsToGoRefreshFrame(trace, 1326),
                "the next completed RunObjects pass has executed Obj5A_RingsNeeded");
        assertFalse(AbstractS2SpecialStageTraceReplayTest.isRingsToGoRefreshFrame(trace, 1330),
                "later ring collection can occur after Obj5A in slot order and must not compare live subtraction");
        assertEquals(true,
                AbstractS2SpecialStageTraceReplayTest.isRingsToGoRefreshFrame(
                        trace, trace.stageFinishedObservedFrame().getAsInt()),
                "rising SS_Check_Rings_flag is an explicit refresh observation");
    }

    @Test
    void startedTransitionPublishesTerminalPreStartObjectPass() throws Exception {
        SpecialStageTraceData trace = SpecialStageTraceData.load(Path.of(
                "src/test/resources/traces/s2/special_stage"));

        // The recorder hooks both halves of SpecialStage_MainLoop
        // (docs/s2disasm/s2.asm:6674-6721), so the terminal pre-start pass is a
        // recorded pass — the last one whose Vint_S2SS input sample still saw
        // SpecialStage_Started clear, because the loop only tests the flag
        // after RunObjects returns (s2.asm:6691-6692). Pass pacing begins at
        // the publication observation of the recurring loop's first pass.
        assertEquals(180,
                AbstractS2SpecialStageTraceReplayTest.terminalPreStartPassSequence(trace));
        // The fixture's first pass whose Vint_S2SS sample already saw
        // SpecialStage_Started (sequence 181) is observed at 424.
        assertEquals(424,
                AbstractS2SpecialStageTraceReplayTest.passPacingStart(trace, -1));
    }

    /**
     * Synthetic completed passes whose object work finished on their own frame.
     */
    private static List<AbstractS2SpecialStageTraceReplayTest.PassSample> passes(int... frames) {
        List<AbstractS2SpecialStageTraceReplayTest.PassSample> passes = new java.util.ArrayList<>();
        for (int frame : frames) {
            passes.add(new AbstractS2SpecialStageTraceReplayTest.PassSample(frame, frame));
        }
        return passes;
    }

    @Test
    void refreshDiscoverySkipsPassesThatCompletedOnTheTransitionFrame() {
        var samples = List.of(
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(0, 0xFF),
                new AbstractS2SpecialStageTraceReplayTest.TriggerSample(4, 0));
        // The pass published on frame 5 completed its object work against frame
        // 4 -- the same frame the cleared trigger was first observed -- so it
        // still carries the pre-clear cell. Frame 7 is the first refreshed one.
        var passList = List.of(
                new AbstractS2SpecialStageTraceReplayTest.PassSample(5, 4),
                new AbstractS2SpecialStageTraceReplayTest.PassSample(7, 6));
        assertEquals(java.util.Set.of(7, 9),
                AbstractS2SpecialStageTraceReplayTest.discoverRingsToGoRefreshFrames(
                        samples, passList,
                        List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF), List.of(9)));
    }

    private static Sonic2SpecialStageComparisonState engine(int rings) {
        return engine(rings, 0, 0);
    }

    private static Sonic2SpecialStageComparisonState engine(int rings,
                                                             int playerAnimFrameTimer,
                                                             int ringsToGo) {
        PlayerState player = new PlayerState(128, 90, 300, 64, "NORMAL", 0, 1, 2,
                rings, 0, 0, 0);
        PlayerState tails = new PlayerState(128, 90, 300, 64, "NORMAL", 0, 1, 2,
                0, 0, 0, 0);
        return new Sonic2SpecialStageComparisonState(12, 5, 7, 4, 58,
                playerAnimFrameTimer, ringsToGo, rings, 9, 0, false, player, tails);
    }

    private static Sonic2SpecialStageComparisonState engine(PlayerState sonic) {
        PlayerState tails = new PlayerState(128, 90, 300, 64, "NORMAL", 0, 1, 2,
                0, 0, 0, 0);
        return new Sonic2SpecialStageComparisonState(12, 5, 7, 4, 10,
                0, 0, 0, 4, 0, false, sonic, tails);
    }

    private static TraceEvent.StateSnapshot snapshot(int frame, int sonicRings) {
        return SpecialStageExpectedStateTestFixtures.runObjectsEnd(frame, sonicRings);
    }

    private static SpecialStageTraceFrame frame(int frame, int sonicRings) {
        return SpecialStageExpectedStateTestFixtures.frame(frame, sonicRings);
    }

    private static SpecialStageTraceFrame frame(SpecialStageTraceFrame.CharacterState sonic) {
        SpecialStageTraceFrame.CharacterState tails = character(128, 90, 300);
        return new SpecialStageTraceFrame(1, 0, 0, false,
                12, 0, 7, 4, 0, 2, 5, 7, 0, 0, 4, 0, sonic, tails);
    }

    private static SpecialStageTraceFrame.CharacterState character(int ssX, int ssY, int ssZ) {
        return character(ssX, ssY, ssZ, 0);
    }

    private static SpecialStageTraceFrame.CharacterState character(int ssX, int ssY, int ssZ,
                                                                    int rings) {
        return new SpecialStageTraceFrame.CharacterState(true,
                ssX, 0, ssY, 0, ssZ, 64, 2, 0, 0, 1, 2,
                rings, 0, 0, 0);
    }
}
