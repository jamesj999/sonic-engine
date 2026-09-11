package com.openggf.tests.trace;
import com.openggf.tests.TestTempFiles;
import com.openggf.trace.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TestDivergenceReport {

    @Test
    void testEmptyReportHasNoErrors() {
        DivergenceReport report = new DivergenceReport(List.of());
        assertFalse(report.hasErrors());
        assertFalse(report.hasWarnings());
        assertTrue(report.errors().isEmpty());
    }

    @Test
    void testSingleErrorFrame() {
        FrameComparison frame = makeComparison(5, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        assertTrue(report.hasErrors());
        assertEquals(1, report.errors().size());
        DivergenceGroup group = report.errors().get(0);
        assertEquals("air", group.field());
        assertEquals(5, group.startFrame());
        assertEquals(5, group.endFrame());
    }

    @Test
    void testConsecutiveFramesGrouped() {
        FrameComparison f1 = makeComparison(10, "x", Severity.ERROR, "0x0050", "0x0150");
        FrameComparison f2 = makeComparison(11, "x", Severity.ERROR, "0x0052", "0x0152");
        FrameComparison f3 = makeComparison(12, "x", Severity.ERROR, "0x0054", "0x0154");

        DivergenceReport report = new DivergenceReport(List.of(f1, f2, f3));
        assertEquals(1, report.errors().size());
        DivergenceGroup group = report.errors().get(0);
        assertEquals(10, group.startFrame());
        assertEquals(12, group.endFrame());
        assertEquals(3, group.frameSpan());
    }

    @Test
    void testWarningsAndErrorsSeparated() {
        FrameComparison f1 = makeComparison(0, "x", Severity.WARNING, "0x0050", "0x0051");
        FrameComparison f2 = makeComparison(1, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(f1, f2));
        assertTrue(report.hasErrors());
        assertTrue(report.hasWarnings());
        assertEquals(1, report.errors().size());
        assertEquals(1, report.warnings().size());
    }

    @Test
    void testSummaryOutput() {
        FrameComparison f1 = makeComparison(5, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(f1));
        String summary = report.toSummary();

        assertTrue(summary.contains("1 error"));
        assertTrue(summary.contains("frame 5"));
    }

    @Test
    void sameFrameSummaryPrefersMovementFieldOverStatusByteDiagnostic() {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("tails_status_byte", new FieldComparison(
                "tails_status_byte", "0x0028", "0x0008", Severity.ERROR, 1));
        fields.put("tails_x_speed", new FieldComparison(
                "tails_x_speed", "0x0018", "-0080", Severity.ERROR, 1));
        FrameComparison frame = new FrameComparison(1775, fields);

        DivergenceReport report = new DivergenceReport(List.of(frame));

        assertEquals("tails_x_speed", report.errors().get(0).field());
        assertTrue(report.toAssertionSummary().contains(
                "First error: frame 1775 -- tails_x_speed mismatch"));
    }

    @Test
    void testCompactSummaryOmitsInlineDiagnostics() {
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "sub=(0000,5000) rtn=02 cam=(1308,0390)",
            "eng-player anim=00 frame=07 tick=07 vel=(0200,0000)");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String fullSummary = report.toSummary();
        String compactSummary = report.toCompactSummary();

        assertTrue(fullSummary.contains("rom={sub=(0000,5000)"));
        assertTrue(compactSummary.contains("1 error"));
        assertTrue(compactSummary.contains("frame 5"));
        assertTrue(compactSummary.contains("x_speed mismatch"));
        assertFalse(compactSummary.contains("rom={"));
        assertFalse(compactSummary.contains("engine={"));
    }

    @Test
    void assertionSummaryOmitsTraceContextAndInlineDiagnostics() throws IOException {
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "sub=(0000,5000) rtn=02 cam=(1308,0390)",
            "eng-player anim=00 frame=07 tick=07 vel=(0200,0000)");
        TraceData trace = createTraceDataWithAuxState();
        DivergenceReport report = new DivergenceReport(List.of(frame), trace);

        String assertionSummary = report.toAssertionSummary();

        assertTrue(assertionSummary.contains("Trace replay diverged."));
        assertTrue(assertionSummary.contains("Totals: 1 error, 0 warnings."));
        assertTrue(assertionSummary.contains(
                "First error: frame 5 -- x_speed mismatch (expected=0x0100, actual=0x0200)"));
        assertFalse(assertionSummary.contains("Latest checkpoint:"));
        assertFalse(assertionSummary.contains("Latest zone/act state:"));
        assertFalse(assertionSummary.contains("rom={"));
        assertFalse(assertionSummary.contains("engine={"));
    }

    @Test
    void testJsonSummaryUsesCompactSummary() {
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "sub=(0000,5000) rtn=02 cam=(1308,0390)",
            "eng-player anim=00 frame=07 tick=07 vel=(0200,0000)");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String json = report.toJson();

        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("First error: frame 5 -- x_speed mismatch"));
        assertFalse(json.contains("rom={"));
        assertFalse(json.contains("engine={"));
        assertFalse(json.contains("eng-player anim=00"));
    }

    @Test
    void testContextWindow() {
        FrameComparison f0 = makeMatchComparison(0, (short) 0x50, (short) 0x3B0);
        FrameComparison f1 = makeMatchComparison(1, (short) 0x51, (short) 0x3B0);
        FrameComparison f2 = makeComparison(2, "air", Severity.ERROR, "0", "1");
        FrameComparison f3 = makeComparison(3, "air", Severity.ERROR, "0", "1");
        FrameComparison f4 = makeMatchComparison(4, (short) 0x54, (short) 0x3B0);

        DivergenceReport report = new DivergenceReport(List.of(f0, f1, f2, f3, f4));
        String context = report.getContextWindow(2, 2);

        assertNotNull(context);
        assertTrue(context.contains("Frame"));
    }

    @Test
    void contextWindowOmitsEmptyBootstrapSectionByDefault() {
        FrameComparison frame = makeComparison(2, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.clearProperty("trace.context.bootstrap");
        try {
            String context = report.getContextWindow(2, 0);

            assertFalse(context.contains("=== Bootstrap (frame 0) ==="));
            assertFalse(context.contains("(no bootstrap divergences)"));
            assertTrue(context.contains("=== Per-frame ==="));
        } finally {
            restoreProperty("trace.context.bootstrap", previous);
        }
    }

    @Test
    void contextWindowCanRenderEmptyBootstrapSectionWhenRequested() {
        FrameComparison frame = makeComparison(2, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.setProperty("trace.context.bootstrap", "always");
        try {
            String context = report.getContextWindow(2, 0);

            assertTrue(context.contains("=== Bootstrap (frame 0) ==="));
            assertTrue(context.contains("(no bootstrap divergences)"));
            assertTrue(context.indexOf("=== Bootstrap (frame 0) ===")
                    < context.indexOf("=== Per-frame ==="));
        } finally {
            restoreProperty("trace.context.bootstrap", previous);
        }
    }

    @Test
    void testContextWindowUsesFrameNumbersNotListIndexes() {
        FrameComparison f100 = makeMatchComparison(100, (short) 0x50, (short) 0x3B0);
        FrameComparison f101 = makeComparison(101, "air", Severity.ERROR, "0", "1");
        FrameComparison f102 = makeMatchComparison(102, (short) 0x54, (short) 0x3B0);

        DivergenceReport report = new DivergenceReport(List.of(f100, f101, f102));
        String previous = System.setProperty("trace.context.rows", "all");
        String context;
        try {
            context = report.getContextWindow(101, 1);
        } finally {
            restoreProperty("trace.context.rows", previous);
        }

        assertTrue(context.contains("100"));
        assertTrue(context.contains("101"));
        assertTrue(context.contains("102"));
    }

    @Test
    void contextWindowDefaultsToDivergentFieldsOnly() {
        FrameComparison frame = makeMixedComparison(5);
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.clearProperty("trace.context.fields");
        try {
            String context = report.getContextWindow(5, 0);

            assertTrue(context.contains("Exp air"));
            assertFalse(context.contains("Exp x"));
            assertFalse(context.contains("Act x"));
        } finally {
            restoreProperty("trace.context.fields", previous);
        }
    }

    @Test
    void contextWindowShowsObservedNonBlockingMismatchesByDefault() {
        Map<String, FieldComparison> f4Fields = new LinkedHashMap<>();
        f4Fields.put("tails_status_byte", new FieldComparison(
                "tails_status_byte", "0x0029", "0x0009", Severity.MATCH, 0, true));
        FrameComparison f4 = new FrameComparison(4, f4Fields);
        FrameComparison f5 = makeComparison(5, "tails_x_speed", Severity.ERROR, "0x0080", "0x0000");
        DivergenceReport report = new DivergenceReport(List.of(f4, f5));

        String previous = System.clearProperty("trace.context.fields");
        try {
            String context = report.getContextWindow(5, 1);

            assertTrue(context.contains("Exp tails_status_byte"),
                    "Observed non-blocking mismatches should remain visible in compact context.");
            assertTrue(context.contains("~0x0009"),
                    "Observed non-blocking mismatches should use a distinct non-error marker.");
            assertFalse(report.hasWarnings());
            assertEquals(1, report.errors().size());
            assertEquals("tails_x_speed", report.errors().get(0).field());
        } finally {
            restoreProperty("trace.context.fields", previous);
        }
    }

    @Test
    void contextWindowDefaultsToRelevantRowsOnly() {
        FrameComparison f3 = makeMatchComparison(3, (short) 0x50, (short) 0x3B0);
        Map<String, FieldComparison> f4Fields = new LinkedHashMap<>();
        f4Fields.put("tails_status_byte", new FieldComparison(
                "tails_status_byte", "0x0029", "0x0009", Severity.MATCH, 0, true));
        FrameComparison f4 = new FrameComparison(4, f4Fields);
        FrameComparison f5 = makeComparison(5, "tails_x_speed", Severity.ERROR, "0x0080", "0x0000");
        FrameComparison f6 = makeMatchComparison(6, (short) 0x54, (short) 0x3B0);
        DivergenceReport report = new DivergenceReport(List.of(f3, f4, f5, f6));

        String previous = System.clearProperty("trace.context.rows");
        try {
            String context = report.getContextWindow(5, 2);

            assertFalse(context.contains("3     "),
                    "Default context should omit radius rows with no divergent or observed fields.");
            assertTrue(context.contains("4     "),
                    "Observed non-blocking mismatches should remain in the compact row set.");
            assertTrue(context.contains("5     "),
                    "The frontier row should always remain in the compact row set.");
            assertFalse(context.contains("6     "),
                    "Default context should omit post-frontier match-only rows.");
        } finally {
            restoreProperty("trace.context.rows", previous);
        }
    }

    @Test
    void contextWindowCanRenderAllRowsWhenRequested() {
        FrameComparison f3 = makeMatchComparison(3, (short) 0x50, (short) 0x3B0);
        FrameComparison f4 = makeMatchComparison(4, (short) 0x51, (short) 0x3B0);
        FrameComparison f5 = makeComparison(5, "tails_x_speed", Severity.ERROR, "0x0080", "0x0000");
        FrameComparison f6 = makeMatchComparison(6, (short) 0x54, (short) 0x3B0);
        DivergenceReport report = new DivergenceReport(List.of(f3, f4, f5, f6));

        String previous = System.setProperty("trace.context.rows", "all");
        try {
            String context = report.getContextWindow(5, 2);

            assertTrue(context.contains("3     "));
            assertTrue(context.contains("4     "));
            assertTrue(context.contains("5     "));
            assertTrue(context.contains("6     "));
        } finally {
            restoreProperty("trace.context.rows", previous);
        }
    }

    @Test
    void contextWindowCanRenderAllComparedFieldsWhenRequested() {
        FrameComparison frame = makeMixedComparison(5);
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.setProperty("trace.context.fields", "all");
        try {
            String context = report.getContextWindow(5, 0);

            assertTrue(context.contains("Exp air"));
            assertTrue(context.contains("Exp x"));
            assertTrue(context.contains("Act x"));
        } finally {
            restoreProperty("trace.context.fields", previous);
        }
    }

    @Test
    void contextWindowDefaultsToFrontierFrameDiagnosticsOnly() {
        FrameComparison f4 = makeComparisonWithDiagnostics(4, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 4", "engine frame 4");
        FrameComparison f5 = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 5", "engine frame 5");
        FrameComparison f6 = makeComparisonWithDiagnostics(6, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 6", "engine frame 6");
        DivergenceReport report = new DivergenceReport(List.of(f4, f5, f6));

        String previous = System.clearProperty("trace.context.diagnostics");
        try {
            String context = report.getContextWindow(5, 1);

            assertTrue(context.contains("4"));
            assertTrue(context.contains("5"));
            assertTrue(context.contains("6"));
            assertFalse(context.contains("rom frame 4"));
            assertTrue(context.contains("rom frame 5"));
            assertFalse(context.contains("rom frame 6"));
            assertEquals(1, countOccurrences(context, "       ROM: "));
            assertEquals(1, countOccurrences(context, "       ENG: "));
        } finally {
            restoreProperty("trace.context.diagnostics", previous);
        }
    }

    @Test
    void contextWindowCanRenderAllFrameDiagnosticsWhenRequested() {
        FrameComparison f4 = makeComparisonWithDiagnostics(4, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 4", "engine frame 4");
        FrameComparison f5 = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 5", "engine frame 5");
        FrameComparison f6 = makeComparisonWithDiagnostics(6, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 6", "engine frame 6");
        DivergenceReport report = new DivergenceReport(List.of(f4, f5, f6));

        String previous = System.setProperty("trace.context.diagnostics", "all");
        try {
            String context = report.getContextWindow(5, 1);

            assertTrue(context.contains("rom frame 4"));
            assertTrue(context.contains("rom frame 5"));
            assertTrue(context.contains("rom frame 6"));
            assertEquals(3, countOccurrences(context, "       ROM: "));
            assertEquals(3, countOccurrences(context, "       ENG: "));
        } finally {
            restoreProperty("trace.context.diagnostics", previous);
        }
    }

    @Test
    void contextWindowCanSuppressAllFrameDiagnosticsWhenRequested() {
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            "rom frame 5", "engine frame 5");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.setProperty("trace.context.diagnostics", "none");
        try {
            String context = report.getContextWindow(5, 0);

            assertTrue(context.contains("Frame"));
            assertFalse(context.contains("rom frame 5"));
            assertFalse(context.contains("engine frame 5"));
            assertEquals(0, countOccurrences(context, "       ROM: "));
            assertEquals(0, countOccurrences(context, "       ENG: "));
        } finally {
            restoreProperty("trace.context.diagnostics", previous);
        }
    }

    @Test
    void contextWindowTruncatesLongFrameDiagnosticsByDefault() {
        String longDiagnostic = "a".repeat(950) + "tail";
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            longDiagnostic, longDiagnostic);
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.clearProperty("trace.context.diagnosticChars");
        try {
            String context = report.getContextWindow(5, 0);

            assertTrue(context.contains("[truncated "));
            assertFalse(context.contains("tail"));
        } finally {
            restoreProperty("trace.context.diagnosticChars", previous);
        }
    }

    @Test
    void contextWindowCanRenderFullFrameDiagnosticsWhenRequested() {
        String longDiagnostic = "a".repeat(950) + "tail";
        FrameComparison frame = makeComparisonWithDiagnostics(5, "x_speed",
            Severity.ERROR, "0x0100", "0x0200",
            longDiagnostic, longDiagnostic);
        DivergenceReport report = new DivergenceReport(List.of(frame));

        String previous = System.setProperty("trace.context.diagnosticChars", "full");
        try {
            String context = report.getContextWindow(5, 0);

            assertFalse(context.contains("[truncated "));
            assertTrue(context.contains("tail"));
        } finally {
            restoreProperty("trace.context.diagnosticChars", previous);
        }
    }

    @Test
    void testSummaryIncludesLatestCheckpointAndZoneActState() throws IOException {
        TraceData trace = createTraceDataWithAuxState();
        FrameComparison frame = makeComparison(2, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(frame), trace);
        String summary = report.toSummary();

        assertTrue(summary.contains("1 error"));
        assertTrue(summary.contains("Latest checkpoint: cp gameplay_start z=0 a=0 ap=0 gm=12"));
        assertTrue(summary.contains("Latest zone/act state: zoneact z=0 a=0 ap=0 gm=12"));
    }

    @Test
    void testJsonIncludesLatestCheckpointAndZoneActState() throws IOException {
        TraceData trace = createTraceDataWithAuxState();
        FrameComparison frame = makeComparison(2, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(frame), trace);
        String json = report.toJson();

        assertTrue(json.contains("\"latest_checkpoint\""));
        assertTrue(json.contains("\"name\" : \"gameplay_start\""));
        assertTrue(json.contains("\"latest_zone_act_state\""));
        assertTrue(json.contains("\"actual_zone_id\" : 0"));
        assertTrue(json.contains("\"actual_act\" : 0"));
        assertTrue(json.contains("\"apparent_act\" : 0"));
        assertTrue(json.contains("\"game_mode\" : 12"));
    }

    @Test
    void testContextWindowIncludesLatestCheckpointAndZoneActState() throws IOException {
        TraceData trace = createTraceDataWithAuxState();
        FrameComparison f0 = makeMatchComparison(0, (short) 0x50, (short) 0x3B0);
        FrameComparison f1 = makeMatchComparison(1, (short) 0x51, (short) 0x3B0);
        FrameComparison f2 = makeComparison(2, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(f0, f1, f2), trace);
        String context = report.getContextWindow(2, 1);

        assertTrue(context.contains("Latest checkpoint: cp gameplay_start z=0 a=0 ap=0 gm=12"));
        assertTrue(context.contains("Latest zone_act_state: zoneact z=0 a=0 ap=0 gm=12"));
    }

    @Test
    void testContextWindowIncludesFocusedSidekickDiagnostics() throws IOException {
        TraceData trace = createTraceDataWithSidekickDiagnostics();
        FrameComparison frame = makeComparison(5, "sidekick_g_speed", Severity.ERROR, "0x000C", "-000C");

        DivergenceReport report = new DivergenceReport(List.of(frame), trace);
        String context = report.getContextWindow(5, 0);

        assertTrue(context.contains("Trace diagnostics @5:"));
        assertTrue(context.contains("tailsCpu status=00 obj=00 gv=000C"));
        assertTrue(context.contains("tailsInteract slot=4 ptr=B128 obj=000220C2"));
    }

    @Test
    void testContextWindowIncludesAizBoundaryDiagnostics() throws IOException {
        TraceData trace = createTraceDataWithAizBoundaryDiagnostics();
        FrameComparison frame = makeComparison(5, "tails_y", Severity.ERROR, "0x040F", "0x0403");

        DivergenceReport report = new DivergenceReport(List.of(frame), trace);
        String context = report.getContextWindow(5, 0);

        assertTrue(context.contains("Trace diagnostics @5:"));
        assertTrue(context.contains("tailsAizBoundary cam=2D80/4000"));
        assertTrue(context.contains("boundary=none 2D95,040F,0000,0000->2D95,040F,0000,0000"));
    }

    @Test
    void testContextWindowIncludesAizTransitionFloorDiagnostics() throws IOException {
        TraceData trace = createTraceDataWithAizTransitionFloorDiagnostics();
        FrameComparison frame = makeComparison(5, "tails_y", Severity.ERROR, "0x0380", "0x037F");

        DivergenceReport report = new DivergenceReport(List.of(frame), trace);
        String context = report.getContextWindow(5, 0);

        assertTrue(context.contains("Trace diagnostics @5:"));
        assertTrue(context.contains("aizFloor s4 @2FB0,03A0 st=90 stand=false/true"));
        assertTrue(context.contains("p1=first_reject y=0379"));
        assertTrue(context.contains("p2=standing y=0380"));
    }

    @Test
    void testTraceBinderBuildReportUsesTraceMetadataContext() throws IOException {
        TraceData trace = createTraceDataWithAuxState();
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        binder.compareFrame(trace.getFrame(0),
            trace.getFrame(0).x(), trace.getFrame(0).y(),
            trace.getFrame(0).xSpeed(), trace.getFrame(0).ySpeed(), trace.getFrame(0).gSpeed(),
            trace.getFrame(0).angle(), trace.getFrame(0).air(), trace.getFrame(0).rolling(),
            trace.getFrame(0).groundMode());
        binder.compareFrame(trace.getFrame(1),
            trace.getFrame(1).x(), trace.getFrame(1).y(),
            trace.getFrame(1).xSpeed(), trace.getFrame(1).ySpeed(), trace.getFrame(1).gSpeed(),
            trace.getFrame(1).angle(), trace.getFrame(1).air(), trace.getFrame(1).rolling(),
            trace.getFrame(1).groundMode());
        binder.compareFrame(trace.getFrame(2),
            trace.getFrame(2).x(), trace.getFrame(2).y(),
            trace.getFrame(2).xSpeed(), trace.getFrame(2).ySpeed(), trace.getFrame(2).gSpeed(),
            trace.getFrame(2).angle(), true, trace.getFrame(2).rolling(),
            trace.getFrame(2).groundMode());

        DivergenceReport report = binder.buildReport(trace);

        assertTrue(report.toSummary().contains("Latest checkpoint: cp gameplay_start z=0 a=0 ap=0 gm=12"));
        assertTrue(report.toJson().contains("\"latest_checkpoint\""));
    }

    @Test
    void testLegacyConstructorContractRemainsWithoutTraceContext() {
        FrameComparison frame = makeComparison(2, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(frame));
        String summary = report.toSummary();
        String json = report.toJson();
        String context = report.getContextWindow(2, 0);

        assertFalse(summary.contains("checkpoint"));
        assertFalse(json.contains("\"latest_checkpoint\""));
        assertFalse(context.contains("Latest checkpoint:"));
    }

    @Test
    void testBootstrapErrorCountsAsReportError() {
        BootstrapDivergence bootstrapError = new BootstrapDivergence(
                "player_history.x[12]",
                BootstrapDivergence.Severity.ERROR,
                "0x1234",
                "0x1200",
                "history ring mismatch");

        DivergenceReport report = new DivergenceReport(
                List.of(),
                null,
                List.of(bootstrapError));

        assertTrue(report.hasErrors());
        assertFalse(report.hasWarnings());
        assertTrue(report.errors().isEmpty());
        assertTrue(report.toSummary().contains("1 bootstrap error"));
        assertTrue(report.toSummary().contains("frame 0"));
        assertTrue(report.toSummary().contains("player_history.x[12]"));
        assertTrue(report.toJson().contains("\"error_count\" : 1"));
        assertTrue(report.toJson().contains("\"warning_count\" : 0"));
    }

    @Test
    void testBootstrapWarningCountsAsReportWarning() {
        BootstrapDivergence bootstrapWarning = new BootstrapDivergence(
                "object_slot[5]",
                BootstrapDivergence.Severity.WARNING,
                "present",
                "unavailable",
                "snapshot field not captured");

        DivergenceReport report = new DivergenceReport(
                List.of(),
                null,
                List.of(bootstrapWarning));

        assertFalse(report.hasErrors());
        assertTrue(report.hasWarnings());
        assertTrue(report.warnings().isEmpty());
        assertTrue(report.toSummary().contains("1 bootstrap warning"));
        assertTrue(report.toSummary().contains("frame 0"));
        assertTrue(report.toSummary().contains("object_slot[5]"));
        assertTrue(report.toJson().contains("\"error_count\" : 0"));
        assertTrue(report.toJson().contains("\"warning_count\" : 1"));
    }

    private FrameComparison makeComparison(int frame, String field,
            Severity severity, String expected, String actual) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put(field, new FieldComparison(field, expected, actual, severity,
            severity == Severity.MATCH ? 0 : 1));
        return new FrameComparison(frame, fields);
    }

    private FrameComparison makeComparisonWithDiagnostics(int frame, String field,
            Severity severity, String expected, String actual,
            String romDiagnostics, String engineDiagnostics) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put(field, new FieldComparison(field, expected, actual, severity,
            severity == Severity.MATCH ? 0 : 1));
        return new FrameComparison(frame, fields, romDiagnostics, engineDiagnostics);
    }

    private FrameComparison makeMatchComparison(int frame, short x, short y) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        String xHex = String.format("0x%04X", x & 0xFFFF);
        String yHex = String.format("0x%04X", y & 0xFFFF);
        fields.put("x", new FieldComparison("x", xHex, xHex, Severity.MATCH, 0));
        fields.put("y", new FieldComparison("y", yHex, yHex, Severity.MATCH, 0));
        fields.put("air", new FieldComparison("air", "0", "0", Severity.MATCH, 0));
        return new FrameComparison(frame, fields);
    }

    private FrameComparison makeMixedComparison(int frame) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("x", new FieldComparison("x", "0x0100", "0x0100", Severity.MATCH, 0));
        fields.put("air", new FieldComparison("air", "0", "1", Severity.ERROR, 1));
        return new FrameComparison(frame, fields);
    }

    private TraceData createTraceDataWithAuxState() throws IOException {
        Path dir = TestTempFiles.createTempDirectory("trace-report");
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "zone": "aiz",
              "zone_id": 0,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 3,
              "start_x": "0x0080",
              "start_y": "0x03A0",
              "recording_date": "2026-04-21",
              "trace_schema": 5,
              "rom_checksum": "test"
            }
            """);
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.LEVEL_HEADER + "\n"
                        + canonicalRow(0, "8=0", "9=0080", "10=03A0", "20=02",
                                "5=0001", "6=0001") + "\n"
                        + canonicalRow(1, "8=0", "9=0081", "10=03A0", "20=02",
                                "5=0002", "6=0002") + "\n"
                        + canonicalRow(2, "8=0", "9=0082", "10=03A0", "20=02",
                                "5=0003", "6=0003") + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"), """
            {"frame":0,"event":"checkpoint","name":"intro_begin","actual_zone_id":null,"actual_act":null,"apparent_act":null,"game_mode":12}
            {"frame":1,"event":"zone_act_state","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
            {"frame":2,"event":"checkpoint","name":"gameplay_start","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
            """);
        return TraceData.load(dir);
    }

    private TraceData createTraceDataWithSidekickDiagnostics() throws IOException {
        Path dir = TestTempFiles.createTempDirectory("trace-sidekick-diag-report");
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "zone": "cnz",
              "zone_id": 3,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 6,
              "start_x": "0x0080",
              "start_y": "0x03A0",
              "recording_date": "2026-04-29",
              "trace_schema": 5,
              "aux_schema_extras": ["tails_cpu_normal_step_per_frame", "sidekick_interact_object_per_frame"],
              "rom_checksum": "test"
            }
            """);
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.LEVEL_HEADER + "\n"
                        + TraceV5TestFixture.levelRow(0) + "\n"
                        + TraceV5TestFixture.levelRow(1) + "\n"
                        + TraceV5TestFixture.levelRow(2) + "\n"
                        + TraceV5TestFixture.levelRow(3) + "\n"
                        + TraceV5TestFixture.levelRow(4) + "\n"
                        + canonicalRow(5, "8=1", "9=0080", "10=03A0", "20=02",
                                "5=0001", "6=0001", "25=1", "26=0050",
                                "27=0288", "28=0010", "29=FFF0", "30=000C",
                                "31=08", "32=1", "35=8000", "36=4000",
                                "37=02", "39=03") + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"), """
            {"frame":5,"vfc":6,"event":"tails_cpu_normal_step","character":"tails","status":"0x00","object_control":"0x00","ground_vel":"0x000C","x_vel":"0x0000","delayed_stat":"0x08","delayed_input":"0x0800","loc_13dd0_branch":"leader_on_object","ctrl2_logical":"0x0808","ctrl2_held_logical":"0x08","path_pre_ground_vel":"0x000C","path_pre_x_vel":"0x0000","path_pre_status":"0x00","path_post_ground_vel":"0x000C","path_post_x_vel":"0x000C","path_post_status":"0x00"}
            {"frame":5,"vfc":6,"event":"sidekick_interact_object","character":"tails","interact":"0xB128","interact_slot":4,"tails_render_flags":"0x80","tails_object_control":"0x03","tails_status":"0x08","tails_on_object":true,"object_code":"0x000220C2","object_routine":"0x02","object_status":"0x10","object_x":"0x2D95","object_y":"0x0420","object_subtype":"0x40","object_render_flags":"0x80","object_object_control":"0x00","object_active":true,"object_destroyed":false,"object_p1_standing":false,"object_p2_standing":true}
            """);
        return TraceData.load(dir);
    }

    private TraceData createTraceDataWithAizBoundaryDiagnostics() throws IOException {
        Path dir = TestTempFiles.createTempDirectory("trace-aiz-boundary-diag-report");
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "zone": "aiz",
              "zone_id": 0,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 6,
              "start_x": "0x0080",
              "start_y": "0x03A0",
              "recording_date": "2026-04-29",
              "trace_schema": 5,
              "aux_schema_extras": ["aiz_boundary_state_per_frame"],
              "rom_checksum": "test"
            }
            """);
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.LEVEL_HEADER + "\n"
                        + TraceV5TestFixture.levelRow(0) + "\n"
                        + TraceV5TestFixture.levelRow(1) + "\n"
                        + TraceV5TestFixture.levelRow(2) + "\n"
                        + TraceV5TestFixture.levelRow(3) + "\n"
                        + TraceV5TestFixture.levelRow(4) + "\n"
                        + canonicalRow(5, "8=1", "9=2E2B", "10=0339", "11=0600",
                                "13=0600", "18=DA00", "19=3700", "20=02",
                                "2=2D8B", "3=02E0", "4=0049", "5=0466",
                                "6=058C", "22=04", "25=1", "26=2D95",
                                "27=040F", "32=1", "35=0000", "36=3A00",
                                "37=06", "38=02", "39=27") + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"), """
            {"frame":5,"vfc":1126,"event":"aiz_boundary_state","character":"tails","camera_min_x":"0x2D80","camera_max_x":"0x4000","camera_min_y":"0x0000","camera_max_y":"0x0300","tree_pre_x":"0x2D40","tree_pre_y":"0x0402","tree_pre_x_vel":"0x00F7","tree_pre_y_vel":"0x0198","tree_post_x":"0x2D95","tree_post_y":"0x040F","tree_post_x_vel":"0x0000","tree_post_y_vel":"0x0000","boundary_pre_x":"0x2D95","boundary_pre_y":"0x040F","boundary_pre_x_vel":"0x0000","boundary_pre_y_vel":"0x0000","boundary_post_x":"0x2D95","boundary_post_y":"0x040F","boundary_post_x_vel":"0x0000","boundary_post_y_vel":"0x0000","boundary_action":"none","post_move_x":"0x2D95","post_move_y":"0x040F","post_move_x_vel":"0x0000","post_move_y_vel":"0x0000"}
            """);
        return TraceData.load(dir);
    }

    private TraceData createTraceDataWithAizTransitionFloorDiagnostics() throws IOException {
        Path dir = TestTempFiles.createTempDirectory("trace-aiz-transition-floor-diag-report");
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "zone": "aiz",
              "zone_id": 0,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 6,
              "start_x": "0x0080",
              "start_y": "0x03A0",
              "recording_date": "2026-04-30",
              "trace_schema": 5,
              "aux_schema_extras": ["aiz_transition_floor_solid_per_frame"],
              "rom_checksum": "test"
            }
            """);
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.LEVEL_HEADER + "\n"
                        + TraceV5TestFixture.levelRow(0) + "\n"
                        + TraceV5TestFixture.levelRow(1) + "\n"
                        + TraceV5TestFixture.levelRow(2) + "\n"
                        + TraceV5TestFixture.levelRow(3) + "\n"
                        + TraceV5TestFixture.levelRow(4) + "\n"
                        + canonicalRow(5, "8=1", "9=2FCD", "10=0379", "18=CA00",
                                "19=F700", "20=02", "2=2F10", "3=02E0",
                                "4=0049", "5=1406", "6=1700", "22=04", "25=1",
                                "26=2FB1", "27=0380", "35=9A00", "36=3200",
                                "37=02", "38=08", "39=04") + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"), """
            {"frame":5,"vfc":1700,"event":"aiz_transition_floor_solid","slot":4,"object_status":"0x90","object_x":"0x2FB0","object_y":"0x03A0","p1_standing":false,"p2_standing":true,"p1_path":"first_reject","p2_path":"standing","p1_d1":"0x00A0","p1_d2":"0x0010","p1_d3":"0x0010","p1_status":"0x00","p1_object_control":"0x00","p1_y_radius":"0x13","p1_x":"0x2FCD","p1_y":"0x0379","p1_y_vel":"0x0000","p1_interact_slot":4,"p2_d1":"0x00A0","p2_d2":"0x0140","p2_d3":"0x0010","p2_status":"0x08","p2_object_control":"0x00","p2_y_radius":"0x10","p2_x":"0x2FB1","p2_y":"0x0380","p2_y_vel":"0x0000","p2_interact_slot":4}
            """);
        return TraceData.load(dir);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** Builds a canonical v5 row from field-index assignments. */
    private static String canonicalRow(int frame, String... assignments) {
        String[] v5 = TraceV5TestFixture.levelRow(frame).split(",", -1);
        for (String assignment : assignments) {
            int separator = assignment.indexOf('=');
            if (separator <= 0 || separator == assignment.length() - 1) {
                throw new IllegalArgumentException("invalid v5 field assignment: " + assignment);
            }
            int index = Integer.parseInt(assignment.substring(0, separator));
            if (index < 0 || index >= v5.length) {
                throw new IllegalArgumentException("v5 field index out of range: " + index);
            }
            v5[index] = assignment.substring(separator + 1);
        }
        return String.join(",", v5);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
