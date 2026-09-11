package com.openggf.tests.trace;

import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.SessionInvocationExtension.SessionInvocation;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceVerificationScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceReplayReportPolicy {

    @TempDir
    Path tempDir;

    @Test
    void traceReplayWarningsAreReleaseBlockingByDefault() {
        ReportPolicySubject subject = new ReportPolicySubject(false);
        DivergenceReport report = warningOnlyReport();

        assertThrows(AssertionError.class,
                () -> subject.assertClean(report),
                "Warning-only trace reports must fail release replay by default.");
    }

    @Test
    void traceReplayWarningsCanBeMarkedDiagnosticOnly() {
        ReportPolicySubject subject = new ReportPolicySubject(true);
        DivergenceReport report = warningOnlyReport();

        assertDoesNotThrow(() -> subject.assertClean(report));
    }

    @Test
    void traceReplayErrorsUseNoiseReducedAssertionSummary() {
        ReportPolicySubject subject = new ReportPolicySubject(false);
        DivergenceReport report = errorReport();

        AssertionError error = assertThrows(AssertionError.class,
                () -> subject.assertClean(report));

        String message = error.getMessage();
        assertTrue(message.startsWith("Trace replay diverged. Totals: 1 error, 0 warnings."));
        assertTrue(message.contains(
                "First error: frame 7 -- x mismatch (expected=0x0100, actual=0x0101)"));
        assertFalse(message.contains("Latest checkpoint:"));
        assertFalse(message.contains("Latest zone/act state:"));
    }

    @Test
    void traceReplayContextRadiusDefaultsToTightFrontierWindow() {
        String previous = System.clearProperty("trace.context.radius");
        try {
            assertEquals(2, TraceReplayConsole.contextRadius(),
                    "Default trace context should stay tight; pass -Dtrace.context.radius=N for wider bisection output.");
        } finally {
            restoreProperty("trace.context.radius", previous);
        }
    }

    @Test
    void traceReplayContextRadiusCanBeExpandedForInvestigation() {
        String previous = System.setProperty("trace.context.radius", "12");
        try {
            assertEquals(12, TraceReplayConsole.contextRadius());
        } finally {
            restoreProperty("trace.context.radius", previous);
        }
    }

    @Test
    void creditsDemoTraceWarningsAreReleaseBlockingByDefault() {
        CreditsReportPolicySubject subject = new CreditsReportPolicySubject(false);
        DivergenceReport report = warningOnlyReport();

        assertThrows(AssertionError.class,
                () -> subject.assertClean(report),
                "Credits demo warning-only reports must fail release replay by default.");
    }

    @Test
    void creditsDemoTraceWarningsCanBeMarkedDiagnosticOnly() {
        CreditsReportPolicySubject subject = new CreditsReportPolicySubject(true);
        DivergenceReport report = warningOnlyReport();

        assertDoesNotThrow(() -> subject.assertClean(report));
    }

    @Test
    void creditsDemoTraceErrorsUseNoiseReducedAssertionSummary() {
        CreditsReportPolicySubject subject = new CreditsReportPolicySubject(false);
        DivergenceReport report = errorReport();

        AssertionError error = assertThrows(AssertionError.class,
                () -> subject.assertClean(report));

        String message = error.getMessage();
        assertTrue(message.startsWith("Trace replay diverged. Totals: 1 error, 0 warnings."));
        assertTrue(message.contains(
                "First error: frame 7 -- x mismatch (expected=0x0100, actual=0x0101)"));
        assertFalse(message.contains("Latest checkpoint:"));
        assertFalse(message.contains("Latest zone/act state:"));
    }

    @Test
    void reportKeepsIndependentPhysicsAndAnimationFrontiers() {
        Map<String, FieldComparison> animationFields = new LinkedHashMap<>();
        animationFields.put("player_mapping_frame", FieldComparison.animation(
                "player_mapping_frame", "0x01", "0x02", Severity.ERROR, 1));
        Map<String, FieldComparison> physicsFields = new LinkedHashMap<>();
        physicsFields.put("x", new FieldComparison(
                "x", "0x0100", "0x0101", Severity.ERROR, 1));
        DivergenceReport report = new DivergenceReport(List.of(
                new FrameComparison(3, animationFields),
                new FrameComparison(7, physicsFields)));

        assertEquals(3, report.firstErrorFrame(TraceVerificationScope.ANIMATION));
        assertEquals(7, report.firstErrorFrame(TraceVerificationScope.PHYSICS));
        assertTrue(report.hasErrors(TraceVerificationScope.ANIMATION));
        assertTrue(report.hasErrors(TraceVerificationScope.PHYSICS));
        assertTrue(report.toJson().contains("\"verification_groups\""));
    }

    @Test
    void greenRerunRetiresContextFromPreviousRedRun() throws Exception {
        SessionInvocation invocation = new SessionInvocation(
                getClass().getName(), "greenRerunRetiresContextFromPreviousRedRun",
                0, "0123456789abcdef", "green rerun");

        TraceReportWriter.writeReport(tempDir, errorReport(), "trace", invocation,
                "s1-ghz1", "s1_ghz1", TraceVerificationScope.ALL, 2);
        Path context = tempDir.resolve("s1_ghz1_context.txt");
        assertTrue(Files.isRegularFile(context));

        TraceReportWriter.writeReport(tempDir, new DivergenceReport(List.of()),
                "trace", invocation, "s1-ghz1", "s1_ghz1",
                TraceVerificationScope.ALL, 2);

        assertFalse(Files.exists(context),
                "a green rerun must not leave obsolete divergence context in reusable target output");
    }

    private static DivergenceReport errorReport() {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("x",
                new FieldComparison("x",
                        "0x0100", "0x0101", Severity.ERROR, 1));
        return new DivergenceReport(List.of(new FrameComparison(7, fields)));
    }

    private static DivergenceReport warningOnlyReport() {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("bootstrap.object_slot[5]",
                new FieldComparison("bootstrap.object_slot[5]",
                        "present", "unavailable", Severity.WARNING, 1));
        return new DivergenceReport(List.of(new FrameComparison(0, fields)));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class ReportPolicySubject extends AbstractTraceReplayTest {
        private final boolean diagnosticOnlyWarnings;

        private ReportPolicySubject(boolean diagnosticOnlyWarnings) {
            this.diagnosticOnlyWarnings = diagnosticOnlyWarnings;
        }

        void assertClean(DivergenceReport report) {
            assertReportHasNoReleaseBlockingDivergences(report);
        }

        @Override
        protected boolean allowDiagnosticOnlyWarnings() {
            return diagnosticOnlyWarnings;
        }

        @Override
        protected SonicGame game() {
            return SonicGame.SONIC_1;
        }

        @Override
        protected int zone() {
            return 0;
        }

        @Override
        protected int act() {
            return 0;
        }

        @Override
        protected Path traceDirectory() {
            return Path.of("unused");
        }
    }

    private static final class CreditsReportPolicySubject extends AbstractCreditsDemoTraceReplayTest {
        private final boolean diagnosticOnlyWarnings;

        private CreditsReportPolicySubject(boolean diagnosticOnlyWarnings) {
            this.diagnosticOnlyWarnings = diagnosticOnlyWarnings;
        }

        void assertClean(DivergenceReport report) {
            assertReportHasNoReleaseBlockingDivergences(report);
        }

        @Override
        protected boolean allowDiagnosticOnlyWarnings() {
            return diagnosticOnlyWarnings;
        }

        @Override
        protected int creditsDemoIndex() {
            return 0;
        }

        @Override
        protected Path traceDirectory() {
            return Path.of("unused");
        }
    }
}
