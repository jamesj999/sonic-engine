package com.openggf.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.runs.TraceRunExternalDiagnostics;

/**
 * Frame-0 bootstrap comparator. Verifies engine state at frame 0 already
 * matches the recorded frame-1 snapshots when native-prelude-mode is on.
 *
 * <p>Native-prelude mode is being added to {@link TraceMetadata} by worker T3.
 * Until that lands, the comparator routes through a package-private override
 * on {@link TraceBinder} so these synthetic tests can flip the flag without
 * depending on T3's record changes.
 */
class TestBootstrapComparator {

    @BeforeEach
    void enableNativePreludeForTest() {
        TraceBinder.setNativePreludeOverrideForTests(true);
    }

    @AfterEach
    void resetOverride() {
        TraceBinder.setNativePreludeOverrideForTests(null);
    }

    /**
     * When the trace metadata advertises native-prelude mode and every
     * recorded snapshot lines up with the engine projection, the comparator
     * must return no divergences.
     */
    @Test
    void synthetic_matching_snapshot_yields_empty_divergences() {
        TraceData trace = traceWithSnapshots(
                /* historyPos */ 0x34,
                /* xHistory  */ shorts(64, 0x0500),
                /* yHistory  */ shorts(64, 0x0300),
                /* inputHist */ shorts(64, 0x0000),
                /* statusHist*/ bytes(64, (byte) 0x00),
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                List.of(objectSnapshot(0, 0x10, 0x0400, 0x0200, 0x02, 0x40)));

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                12,
                new EngineSnapshot.SidekickCpuView(1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                Map.of(0, new EngineSnapshot.ObjectSnapshot(0x10, 0x0400, 0x0200, 0x02, 0x40)));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertTrue(divergences.isEmpty(),
                () -> "Expected no bootstrap divergences but found: " + divergences);
    }

    @Test
    void liveComparatorPublishesBootstrapMismatchWithoutAdvancingCursor() {
        TraceData trace = traceWithSnapshots(
                0x34,
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                null,
                List.of());
        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                13,
                null,
                Map.of());
        AtomicInteger firstError = new AtomicInteger();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null,
                firstError::incrementAndGet);

        List<BootstrapDivergence> divergences =
                comparator.compareBootstrap(snapshot);

        assertEquals(1, divergences.size());
        assertEquals(1, comparator.errorCount());
        assertEquals(0, comparator.warningCount());
        assertEquals(1, firstError.get());
        assertEquals(0, comparator.cursor());
        assertEquals("player_history.pos",
                comparator.recentMismatches().getFirst().field());
        assertEquals(divergences, comparator.bootstrapDivergences());
    }

    @Test
    void bootstrapObserverPublishesEveryDivergenceBeforeLaterGapErrors() {
        TraceData trace = traceWithSnapshots(
                0x34,
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                null,
                List.of());
        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0600),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                13,
                null,
                Map.of());
        AtomicInteger pauses = new AtomicInteger();
        TraceRunExternalDiagnostics runDiagnostics =
                new TraceRunExternalDiagnostics(pauses::incrementAndGet);
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null,
                pauses::incrementAndGet, runDiagnostics::acceptDisplayed);

        List<BootstrapDivergence> divergences =
                comparator.compareBootstrap(snapshot);
        runDiagnostics.acceptBootstrap(divergences);
        runDiagnostics.accept(new FrameComparison(1, Map.of(
                "run_gap.edge_count", new FieldComparison(
                        "run_gap.edge_count", "0", "1",
                        Severity.ERROR, 1))));

        assertTrue(divergences.size() > 5,
                "coverage requires more mismatches than the HUD ring retains");
        assertEquals(divergences.size() + 1, runDiagnostics.errorCount());
        assertEquals(1, pauses.get(),
                "the later gap error must not toggle pause a second time");
    }

    /**
     * ROM {@code Sonic_Pos_Record_Index} is a byte offset into 4-byte Pos_table
     * records; the engine snapshot exposes the already-normalized 0-63 slot.
     */
    @Test
    void player_history_pos_compares_rom_byte_offset_as_engine_slot() {
        TraceData trace = traceWithSnapshots(
                /* historyPos */ 0x68,
                /* xHistory  */ shorts(64, 0x0500),
                /* yHistory  */ shorts(64, 0x0300),
                /* inputHist */ shorts(64, 0x0000),
                /* statusHist*/ bytes(64, (byte) 0x00),
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                List.of());

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                0x19,
                new EngineSnapshot.SidekickCpuView(1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                Map.of());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertTrue(divergences.isEmpty(),
                () -> "Equivalent ROM byte offset and engine slot must not diverge: "
                        + divergences);
    }

    @Test
    void inactive_frame_zero_sidekick_suppresses_missing_engine_cpu_warning() {
        TraceData trace = traceWithSnapshots(
                0x34,
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                List.of(),
                List.of(frameWithSidekickAbsent()));

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                12,
                null,
                Map.of());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertTrue(divergences.isEmpty(),
                () -> "An absent frame-0 sidekick must not require an engine CPU view: "
                        + divergences);
    }

    @Test
    void missing_engine_object_slot_is_comparator_visibility_gap_not_warning() {
        TraceData trace = traceWithSnapshots(
                0x34,
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                List.of(objectSnapshot(20, 0xB2, 0x2F3C, 0x0588, 0x06, 0x08)));

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                12,
                new EngineSnapshot.SidekickCpuView(1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                Map.of());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertTrue(divergences.isEmpty(),
                () -> "Missing engine object slots are skipped until the snapshot captures them: "
                        + divergences);
    }

    /**
     * Modifying a single recorded field must produce exactly one ERROR-severity
     * divergence with the matching field name, expected and actual rendered as
     * hex, and a non-blank context string.
     */
    @Test
    void single_field_mismatch_yields_one_divergence() {
        short[] xHistory = shorts(64, 0x0500);
        xHistory[12] = (short) 0x0501; // ROM trace has 0x0501 at idx 12

        TraceData trace = traceWithSnapshots(
                0x34,
                xHistory,
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                List.of());

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500), // engine still has 0x0500 at idx 12
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                12,
                new EngineSnapshot.SidekickCpuView(1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                Map.of());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertEquals(1, divergences.size(),
                () -> "Expected exactly one divergence but got: " + divergences);
        BootstrapDivergence only = divergences.get(0);
        assertEquals("player_history.x[12]", only.field(),
                "Field label should identify the array index that diverged");
        assertEquals(BootstrapDivergence.Severity.ERROR, only.severity(),
                "Mismatched recorded values must escalate to ERROR severity");
        assertEquals("0x0501", only.expected());
        assertEquals("0x0500", only.actual());
        assertFalse(only.context() == null || only.context().isBlank(),
                "Divergence context should carry a human-readable hint");
    }

    /**
     * If the trace omits the recorded player-history snapshot the comparator
     * should still complete, emitting a WARNING-level divergence that flags
     * the missing frame -1 event (not an ERROR).
     */
    @Test
    void missing_frame_minus_one_event_warns_not_errors() {
        // No player_history_snapshot, but cpu and object snapshots present so
        // the comparator can prove it skips only the missing schema.
        Map<Integer, List<TraceEvent>> events = new HashMap<>();
        events.put(-1, List.of(
                cpu("tails", 1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                objectSnapshot(0, 0x10, 0x0400, 0x0200, 0x02, 0x40)));
        TraceData trace = TraceFixtures.trace(TraceFixtures.metadata("s2", 4, 0), List.of(), events);

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0500),
                shorts(64, 0x0300),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                12,
                new EngineSnapshot.SidekickCpuView(1, 0, 2, (short) 0x0480, (short) 0x0320, 0x0000, false),
                Map.of(0, new EngineSnapshot.ObjectSnapshot(0x10, 0x0400, 0x0200, 0x02, 0x40)));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        // Must contain at least one WARNING about the missing player_history_snapshot
        // and NO ERROR-severity divergences (the rest of the snapshot matches).
        boolean missingWarning = divergences.stream().anyMatch(d ->
                d.severity() == BootstrapDivergence.Severity.WARNING
                && d.field() != null
                && d.field().startsWith("player_history"));
        boolean anyErrors = divergences.stream()
                .anyMatch(d -> d.severity() == BootstrapDivergence.Severity.ERROR);
        if (!missingWarning) {
            fail("Expected a WARNING-level divergence for the missing player_history_snapshot, got: "
                    + divergences);
        }
        assertFalse(anyErrors,
                () -> "Missing snapshot must not raise ERROR severity. Divergences: "
                        + divergences);
    }

    /**
     * When the metadata indicates legacy (non-native) prelude mode, the
     * comparator must return an empty list without examining any state.
     * Verify by feeding a snapshot that would otherwise mismatch.
     */
    @Test
    void legacy_mode_skips_comparator() {
        // Override turned off => legacy mode.
        TraceBinder.setNativePreludeOverrideForTests(false);

        // Intentional mismatches that would ERROR if examined.
        Map<Integer, List<TraceEvent>> events = new HashMap<>();
        events.put(-1, List.of(
                new TraceEvent.PlayerHistorySnapshot(-1,
                        0,
                        shorts(64, 0x9999),
                        shorts(64, 0x9999),
                        shorts(64, 0x9999),
                        bytes(64, (byte) 0x99))));
        TraceData trace = TraceFixtures.trace(TraceFixtures.metadata("s2", 4, 0), List.of(), events);

        EngineSnapshot snapshot = new EngineSnapshot(
                shorts(64, 0x0000),
                shorts(64, 0x0000),
                shorts(64, 0x0000),
                bytes(64, (byte) 0x00),
                0,
                null,
                Map.of());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        List<BootstrapDivergence> divergences = binder.compareBootstrapFrame0(trace, snapshot);

        assertTrue(divergences.isEmpty(),
                () -> "Legacy traces must skip the comparator entirely; divergences: "
                        + divergences);
    }

    // ---- Untouched Obj01_Init pre-fill remnant (mid-act re-entry) ----

    /**
     * A replay whose level load ran ROM {@code LevelSizeLoad}'s checkpoint
     * branch (start X differs from the zone's {@code StartLocations} X,
     * docs/s2disasm/s2.asm:14773-14778) cannot reproduce
     * {@code Obj01_Init_Continued}'s pre-fill anchor, because that anchor is
     * {@code Saved_x_pos}/{@code Saved_y_pos} from a star post touched before
     * the segment began (docs/s2disasm/s2.asm:36202-36218, :44737-44738).
     * The untouched remnant slots -- at and above the next-free ring index --
     * must therefore be excluded.
     */
    @Test
    void checkpointEntryExcludesUntouchedPreFillRemnant() {
        List<BootstrapDivergence> divergences =
                comparePreFillRemnantCase(0x0060, 0x00AE, 0x00B1, 0x0500);

        assertTrue(divergences.isEmpty(),
                () -> "Pre-fill remnant must not be compared for a checkpoint "
                        + "entry; divergences: " + divergences);
    }

    /**
     * The same snapshot, but with the replay starting at the zone's
     * {@code StartLocations} X: the engine ran the same {@code Obj01_Init}
     * branch as the ROM, so every remnant slot stays compared.
     */
    @Test
    void startLocationEntryStillComparesPreFillRemnant() {
        List<BootstrapDivergence> divergences =
                comparePreFillRemnantCase(0x0000, 0x00AE, 0x00B1, 0x0500);

        assertEquals(38, divergences.size(),
                () -> "Expected all 38 remnant slots compared; got: " + divergences);
        assertEquals("player_history.y[26]", divergences.getFirst().field());
    }

    /**
     * The exclusion must never swallow a slot the ROM actually wrote during
     * the segment. Slot 5 is below the next-free index, so it is compared even
     * on a checkpoint entry.
     */
    @Test
    void checkpointEntryStillComparesWrittenSlots() {
        List<BootstrapDivergence> divergences =
                comparePreFillRemnantCase(0x0060, 0x00AE, 0x00B1, 0x0501);

        assertEquals(1, divergences.size(),
                () -> "Written slots must stay compared; got: " + divergences);
        assertEquals("player_history.y[5]", divergences.getFirst().field());
    }

    /**
     * Builds the mid-act shape: 26 genuine {@code Sonic_RecordPos} writes
     * (next-free byte offset 26*4 = 0x68) followed by 38 untouched pre-fill
     * slots carrying one identical coordinate pair with zero input and status.
     *
     * @param levelStartX zone {@code StartLocations} X seen by the engine; the
     *                    fixture metadata's own start X is 0x0000
     * @param romRemnantY pre-fill anchor Y the ROM recorded
     * @param engineRemnantY pre-fill anchor Y the cold-booted engine produced
     * @param engineSlot5Y engine Y for written slot 5 (the ROM's is 0x0500)
     */
    private static List<BootstrapDivergence> comparePreFillRemnantCase(
            int levelStartX, int romRemnantY, int engineRemnantY, int engineSlot5Y) {
        short[] romX = shorts(64, 0x0DD0);
        short[] romY = shorts(64, romRemnantY);
        short[] engineX = shorts(64, 0x0DD0);
        short[] engineY = shorts(64, engineRemnantY);
        short[] input = shorts(64, 0x0000);
        byte[] status = bytes(64, (byte) 0x00);
        for (int i = 0; i < 26; i++) {
            romY[i] = (short) 0x0500;
            engineY[i] = (short) 0x0500;
            input[i] = (short) 0x0020;
            status[i] = (byte) 0x02;
        }
        engineY[5] = (short) engineSlot5Y;

        TraceData trace = traceWithSnapshots(0x68, romX, romY, input, status,
                null, List.of());
        EngineSnapshot snapshot = new EngineSnapshot(
                engineX, engineY, input, status, 25, null, Map.of(), levelStartX);

        return new TraceBinder(ToleranceConfig.DEFAULT)
                .compareBootstrapFrame0(trace, snapshot);
    }

    // ---- Test helpers ----

    /**
     * Build a TraceData with the three frame-1 events populated. Native prelude
     * mode is signalled to TraceBinder via the override toggled in {@link #enableNativePreludeForTest()}.
     */
    private static TraceData traceWithSnapshots(int historyPos,
                                                short[] xHistory,
                                                short[] yHistory,
                                                short[] inputHistory,
                                                byte[] statusHistory,
                                                TraceEvent.CpuStateSnapshot cpu,
                                                List<TraceEvent.ObjectStateSnapshot> objects) {
        return traceWithSnapshots(
                historyPos, xHistory, yHistory, inputHistory, statusHistory,
                cpu, objects, List.of());
    }

    private static TraceData traceWithSnapshots(int historyPos,
                                                short[] xHistory,
                                                short[] yHistory,
                                                short[] inputHistory,
                                                byte[] statusHistory,
                                                TraceEvent.CpuStateSnapshot cpu,
                                                List<TraceEvent.ObjectStateSnapshot> objects,
                                                List<TraceFrame> frames) {
        List<TraceEvent> frameMinusOne = new ArrayList<>();
        frameMinusOne.add(new TraceEvent.PlayerHistorySnapshot(
                -1, historyPos, xHistory, yHistory, inputHistory, statusHistory));
        if (cpu != null) {
            frameMinusOne.add(cpu);
        }
        if (objects != null) {
            frameMinusOne.addAll(objects);
        }
        Map<Integer, List<TraceEvent>> events = new HashMap<>();
        events.put(-1, frameMinusOne);
        return TraceFixtures.trace(TraceFixtures.metadata("s2", 4, 0), frames, events);
    }

    private static TraceEvent.CpuStateSnapshot cpu(String character, int controlCounter,
                                                   int respawnCounter, int cpuRoutine,
                                                   short targetX, short targetY,
                                                   int interactId, boolean jumping) {
        return new TraceEvent.CpuStateSnapshot(-1, character, controlCounter, respawnCounter,
                cpuRoutine, targetX, targetY, interactId, jumping);
    }

    private static TraceEvent.ObjectStateSnapshot objectSnapshot(int slot, int objectType,
                                                                  int xPos, int yPos,
                                                                  int routine, int status) {
        Map<Integer, Integer> byteFields = new LinkedHashMap<>();
        Map<Integer, Integer> wordFields = new LinkedHashMap<>();
        wordFields.put(0x08, xPos & 0xFFFF); // x_pos
        wordFields.put(0x0C, yPos & 0xFFFF); // y_pos
        byteFields.put(0x22, status & 0xFF); // status
        byteFields.put(0x24, routine & 0xFF); // routine
        return new TraceEvent.ObjectStateSnapshot(-1, slot, objectType,
                new RomObjectSnapshot(byteFields, wordFields));
    }

    private static TraceFrame frameWithSidekickAbsent() {
        return new TraceFrame(0, 0,
                (short) 0x0500, (short) 0x0300,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                0, 0, 0x02, 0, 0, 0, 0,
                0, 0, 0, 0,
                new TraceCharacterState(false,
                        (short) 0, (short) 0,
                        (short) 0, (short) 0, (short) 0,
                        (byte) 0, false, false, 0,
                        0, 0, 0, 0, 0));
    }

    private static short[] shorts(int len, int fill) {
        short[] arr = new short[len];
        Arrays.fill(arr, (short) fill);
        return arr;
    }

    private static byte[] bytes(int len, byte fill) {
        byte[] arr = new byte[len];
        Arrays.fill(arr, fill);
        return arr;
    }
}
