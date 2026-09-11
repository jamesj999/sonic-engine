package com.openggf.tests.trace;

import com.openggf.trace.EngineDiagnostics;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceBinder;
import com.openggf.trace.TraceFrame;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard for the recorded {@code life_count} column.
 *
 * <p>The failure mode this exists to prevent is the one already present for
 * {@code routine} and {@code status_byte} in {@link TraceBinder}: a comparison
 * gated on the engine side having supplied a value, so that when the engine
 * stops supplying it the field is silently not compared and "no mismatch
 * reported" stops meaning "checked and passed". These tests assert that the
 * comparison actually <em>ran</em>, and that the only way it can be absent is
 * the recording itself not carrying the column.
 */
public class TestTraceLifeCountComparisonGuard {

    /** A 42-column v5 row (no life_count) followed by the same row with one. */
    private static final String ROW_42 =
            "0000,0000,0100,0200,0003,0010,0011,0000,"
            + "1,0050,03B0,0000,0000,0000,00,0,0,0,0000,0000,02,04,00,05,23,"
            + "0,0000,0000,0000,0000,0000,00,0,0,0,0000,0000,00,00,00,00,00";

    private static TraceFrame frameWithLives(int frameNumber, int lives) {
        return new TraceFrame(frameNumber, 0,
                (short) 0x0050, (short) 0x03B0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, null, lives);
    }

    private static EngineDiagnostics engineWithLives(int lives) {
        return EngineDiagnostics.EMPTY.withLives(lives);
    }

    private static FrameComparison compare(TraceBinder binder,
            TraceFrame frame, EngineDiagnostics diagnostics) {
        return binder.compareFrame(frame,
                frame.x(), frame.y(), frame.xSpeed(), frame.ySpeed(), frame.gSpeed(),
                frame.angle(), frame.air(), frame.rolling(), frame.groundMode(),
                null, diagnostics);
    }

    @Test
    void rowWithoutLifeCountColumnParsesAsAbsent() {
        TraceFrame frame = TraceFrame.parseCsvRow(ROW_42);
        assertEquals(TraceFrame.LIVES_ABSENT, frame.lives(),
                "42-column rows predate life_count and must report it absent");
    }

    @Test
    void rowWithLifeCountColumnParsesTheValue() {
        TraceFrame frame = TraceFrame.parseCsvRow(ROW_42 + ",03");
        assertEquals(3, frame.lives(),
                "43-column rows carry life_count in the trailing column");
    }

    @Test
    void aFixtureCarryingLifeCountIsActuallyCompared() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = compare(binder, frameWithLives(0, 3), engineWithLives(3));

        FieldComparison lives = result.fields().get("lives");
        assertNotNull(lives, "life_count was recorded, so the comparison must run");
        assertEquals(Severity.MATCH, lives.severity());
        assertEquals("3", lives.expected());
        assertEquals("3", lives.actual());
    }

    @Test
    void aLifeCountDifferenceIsAnError() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = compare(binder, frameWithLives(0, 3), engineWithLives(2));

        assertEquals(Severity.ERROR, result.fields().get("lives").severity());
        assertTrue(result.hasError());
    }

    /**
     * The core guard. If the engine snapshot stops carrying a life count the
     * comparison must not evaporate — it must fail loudly instead.
     */
    @Test
    void missingEngineLifeCountFailsInsteadOfSkippingTheComparison() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = compare(binder, frameWithLives(0, 3),
                engineWithLives(EngineDiagnostics.LIVES_ABSENT));

        FieldComparison present = result.fields().get("lives_present");
        assertNotNull(present,
                "a recorded life_count with no engine counterpart must be reported, not skipped");
        assertEquals(Severity.ERROR, present.severity());
        assertTrue(result.hasError());
    }

    @Test
    void missingEngineDiagnosticsEntirelyAlsoFails() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = compare(binder, frameWithLives(0, 3), null);

        assertEquals(Severity.ERROR, result.fields().get("lives_present").severity());
    }

    /**
     * Absence of the column is the one sanctioned way to compare nothing, and it
     * is keyed on the recording rather than on the engine.
     */
    @Test
    void recordingsWithoutTheColumnEmitNoLifeFieldsAtAll() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = compare(binder,
                frameWithLives(0, TraceFrame.LIVES_ABSENT), engineWithLives(3));

        assertFalse(result.fields().containsKey("lives"));
        assertFalse(result.fields().containsKey("lives_delta"));
        assertFalse(result.fields().containsKey("lives_present"));
    }

    /**
     * A death the engine does not reproduce must name the frame it happened on.
     * {@code lives_delta} is the field that does this: it flags exactly the
     * transition frame rather than every frame after it.
     */
    @Test
    void aDeathTheEngineMissesNamesTheExactFrame() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);

        FrameComparison before = compare(binder, frameWithLives(100, 3), engineWithLives(3));
        assertEquals(Severity.MATCH, before.fields().get("lives").severity());

        // Frame 101: the ROM loses a life; the engine does not.
        FrameComparison death = compare(binder, frameWithLives(101, 2), engineWithLives(3));
        FieldComparison delta = death.fields().get("lives_delta");
        assertNotNull(delta, "the transition frame must carry a delta comparison");
        assertEquals(Severity.ERROR, delta.severity());
        assertEquals("-1", delta.expected());
        assertEquals("0", delta.actual());
        assertEquals(101, death.frame());

        // Frame 102: both sides steady again. The level stays divergent, but the
        // delta is clean, so the transition frame remains identifiable.
        FrameComparison after = compare(binder, frameWithLives(102, 2), engineWithLives(3));
        assertEquals(Severity.MATCH, after.fields().get("lives_delta").severity());
        assertEquals(Severity.ERROR, after.fields().get("lives").severity());
    }

    @Test
    void aOneUpTheEngineMissesAlsoNamesTheExactFrame() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        compare(binder, frameWithLives(10, 3), engineWithLives(3));
        FrameComparison gain = compare(binder, frameWithLives(11, 4), engineWithLives(3));

        assertEquals(Severity.ERROR, gain.fields().get("lives_delta").severity());
        assertEquals("1", gain.fields().get("lives_delta").expected());
    }

    /** Re-merging a frame (aux merge) must reproduce the same delta, not drop it. */
    @Test
    void reComparingTheSameFrameKeepsTheDelta() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        compare(binder, frameWithLives(5, 3), engineWithLives(3));
        FrameComparison first = compare(binder, frameWithLives(6, 2), engineWithLives(3));
        FrameComparison again = compare(binder, frameWithLives(6, 2), engineWithLives(3));

        assertEquals(first.fields().get("lives_delta").severity(),
                again.fields().get("lives_delta").severity());
        assertEquals(Severity.ERROR, again.fields().get("lives_delta").severity());
    }

    /**
     * The engine-side half of the guard. The comparison above can only run if the
     * replay comparators keep feeding a real life count into
     * {@link EngineDiagnostics}; if that plumbing is dropped the comparison
     * degrades to a permanent {@code lives_present} failure on every re-recorded
     * fixture, which is exactly the silent-skip this file exists to forbid.
     */
    @Test
    void replayComparatorsStillSupplyTheEngineLifeCount() throws IOException {
        // Every comparator that builds an EngineDiagnostics for a level replay.
        for (String source : java.util.List.of(
                "src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java",
                "src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java",
                "src/main/java/com/openggf/trace/live/LiveTraceComparator.java")) {
            Path path = Path.of(source);
            assertTrue(Files.exists(path), "expected comparator source at " + path);
            assertTrue(Files.readString(path).contains("getLives()"),
                    path + " must read the engine life count for life_count comparison");
        }
        // The two comparators that re-wrap diagnostics before comparing must
        // carry the life count across the re-wrap rather than dropping it.
        for (String source : java.util.List.of(
                "src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java",
                "src/main/java/com/openggf/trace/live/LiveTraceComparator.java")) {
            assertTrue(Files.readString(Path.of(source)).contains("withLives("),
                    source + " must attach the engine life count to EngineDiagnostics");
        }
    }

    /**
     * Any committed fixture that carries the {@code life_count} header column must
     * parse into frames that report a life count. This fails if the column is ever
     * recorded in a position or format the parser does not pick up.
     */
    @Test
    void committedFixturesCarryingTheHeaderParseTheColumn() throws IOException {
        Path root = Path.of("src/test/resources/traces");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .toList()) {
                java.util.List<String> lines = Files.readAllLines(path);
                if (lines.size() < 2 || !lines.get(0).contains("life_count")) {
                    continue;
                }
                assertEquals(42, lines.get(0).split(",", -1).length - 1,
                        path + " must carry life_count as the trailing 43rd column");
                TraceFrame frame = TraceFrame.parseCsvRow(lines.get(1));
                assertTrue(frame.lives() >= 0,
                        path + " advertises life_count but parses as absent");
            }
        }
    }
}
