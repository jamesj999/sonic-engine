package com.openggf.tools;

import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.trace.TraceData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceBenchmarkToolArgs {

    @Test
    void defaultsFavourAMeasurableRun() {
        TraceBenchmarkTool.Args args = TraceBenchmarkTool.Args.parse(new String[]{"--trace", "aiz1"});

        assertEquals("aiz1", args.trace());
        assertEquals(TraceBenchmarkTool.MODE_UPDATE, args.mode(),
                "the default mode must exclude rendering, which measures the GPU driver");
        assertTrue(args.warmupFrames() > 0, "a benchmark with no warmup measures the interpreter");
        assertTrue(args.measureFrames() >= args.warmupFrames());
        assertTrue(args.iterations() > 1, "a single pass cannot separate cold from warm");
        assertFalse(args.trackAllocations(),
                "allocation tracking costs a JMX call inside every measured section");
        assertTrue(args.audio(), "audio synthesis is real per-frame work and is measured by default");
    }

    @Test
    void everyFlagIsParsed() {
        TraceBenchmarkTool.Args args = TraceBenchmarkTool.Args.parse(new String[]{
                "--trace", "hcz1",
                "--mode", "full",
                "--warmup-frames", "500",
                "--measure-frames", "1500",
                "--iterations", "5",
                "--json", "out/report.json",
                "--markdown", "out/report.md",
                "--label", "graal21",
                "--track-allocations",
                "--no-audio"});

        assertEquals("hcz1", args.trace());
        assertEquals(TraceBenchmarkTool.MODE_FULL, args.mode());
        assertEquals(500, args.warmupFrames());
        assertEquals(1500, args.measureFrames());
        assertEquals(5, args.iterations());
        assertEquals(Paths.get("out/report.json"), args.json());
        assertEquals(Paths.get("out/report.md"), args.markdown());
        assertEquals("graal21", args.label());
        assertTrue(args.trackAllocations());
        assertFalse(args.audio());
    }

    @Test
    void fmCoreSelectionIsExplicitAndRejectsTypos() {
        for (String core : new String[] {"fast", "accurate"}) {
            assertEquals(core, TraceBenchmarkTool.Args.parse(new String[] {
                    "--trace", "aiz1", "--fm-core", core}).fmCore());
        }
        assertThrows(IllegalArgumentException.class, () -> TraceBenchmarkTool.Args.parse(
                new String[] {"--trace", "aiz1", "--fm-core", "fat"}),
                "a typo must not silently benchmark the accurate fallback");
    }

    @Test
    void traceIsRequired() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(new String[]{"--mode", "update"}));
        assertTrue(error.getMessage().contains("--trace"));
    }

    @Test
    void anUnknownModeIsRejectedRatherThanSilentlyTreatedAsUpdate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(new String[]{"--trace", "aiz1", "--mode", "render"}));
        assertTrue(error.getMessage().contains("--mode"));
    }

    @Test
    void zeroMeasuredFramesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TraceBenchmarkTool.Args.parse(
                new String[]{"--trace", "aiz1", "--measure-frames", "0"}));
    }

    @Test
    void zeroIterationsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TraceBenchmarkTool.Args.parse(
                new String[]{"--trace", "aiz1", "--iterations", "0"}));
    }

    @Test
    void zeroWarmupIsAllowedForShortDiagnosticRuns() {
        TraceBenchmarkTool.Args args = TraceBenchmarkTool.Args.parse(
                new String[]{"--trace", "aiz1", "--warmup-frames", "0"});

        assertEquals(0, args.warmupFrames());
    }

    @Test
    void valuesBelowTheMinimumRetainTheirCurrentMessages() {
        IllegalArgumentException warmupError = assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(
                        new String[]{"--trace", "aiz1", "--warmup-frames", "-1"}));
        assertEquals("--warmup-frames must be >= 0, got -1", warmupError.getMessage());

        IllegalArgumentException measureError = assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(
                        new String[]{"--trace", "aiz1", "--measure-frames", "0"}));
        assertEquals("--measure-frames must be >= 1, got 0", measureError.getMessage());
    }

    @Test
    void missingAndMalformedValuesRetainTheirExistingExceptionContracts() {
        IllegalArgumentException missingValue = assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(new String[]{"--trace", "aiz1", "--iterations"}));
        assertEquals("Missing value for --iterations", missingValue.getMessage());

        assertThrows(NumberFormatException.class,
                () -> TraceBenchmarkTool.Args.parse(
                        new String[]{"--trace", "aiz1", "--iterations", "three"}));
    }

    @Test
    void unknownFlagsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TraceBenchmarkTool.Args.parse(
                new String[]{"--trace", "aiz1", "--fast"}));
    }

    @Test
    void aFlagMissingItsValueIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceBenchmarkTool.Args.parse(new String[]{"--trace"}));
    }

    @Test
    void benchmarkUsesRecordedAdmissionOnlyForTimedFixtures() throws IOException {
        var timed = new com.openggf.trace.timing.HardwareTimingSchedule(java.util.List.of());
        var legacy = com.openggf.trace.timing.HardwareTimingSchedule.empty();

        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                TraceBenchmarkTool.admissionPolicyFor(timed));
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                TraceBenchmarkTool.admissionPolicyFor(legacy));
    }
}
