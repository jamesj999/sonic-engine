package com.openggf.game.rewind.coverage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical rewind round-trip probe.
 *
 * <p>For each concrete spawnable object it can construct without ROM/OpenGL,
 * this test:
 * <ol>
 *   <li>Constructs the object with a minimal {@code StubObjectServices} fixture.</li>
 *   <li>Captures its rewind state via {@code captureRewindState()}.</li>
 *   <li>Constructs a fresh instance from the same spawn.</li>
 *   <li>Restores captured state onto the fresh instance via {@code restoreRewindState()}.</li>
 *   <li>Reflectively diffs all non-static, non-synthetic scalar/enum/primitive fields
 *       from the object hierarchy (excluding {@code AbstractObjectInstance} base fields
 *       that are managed separately).</li>
 * </ol>
 *
 * <p>Any field whose value differs after restore is a REAL gap (not just a static
 * flag from the coverage analyzer). Objects that cannot be constructed without ROM
 * are SKIPPED and recorded as "unprobed".
 *
 * <p>The test writes a full report to {@code docs/status/rewind-round-trip-gaps.md}.
 * The test itself only fails if the report write fails; the gap list is
 * informational (not a hard gate).
 *
 * @see RewindRoundTripProbe
 */
@Tag("rewind-probe")
class TestRewindRoundTripProbe {

    @Test
    void probeReturnsNonEmptyResults() {
        RewindRoundTripProbe probe = new RewindRoundTripProbe();
        RewindRoundTripProbe.ProbeReport report = probe.run(Set.of());

        assertNotNull(report, "probe must return a non-null report");
        assertTrue(report.totalClasses() > 0,
                "probe must discover at least some object classes to probe");
        // At least some objects must be probed (even without ROM, simple badniks can be constructed)
        assertTrue(report.probed() > 0,
                "at least some objects must be constructable without ROM; got 0 probed from "
                        + report.totalClasses() + " total");
    }

    @Test
    void probeReportIsWrittenToDisk() throws IOException {
        RewindRoundTripProbe probe = new RewindRoundTripProbe();
        RewindRoundTripProbe.ProbeReport report = probe.run(Set.of());

        Path outDir = Paths.get("docs/status");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("rewind-round-trip-gaps.md");
        probe.writeReport(report, outFile);

        assertTrue(Files.exists(outFile), "docs/status/rewind-round-trip-gaps.md must exist after write");
        String content = Files.readString(outFile);
        assertTrue(content.contains("# Rewind Round-Trip Probe"), "report must have title");
        assertTrue(content.contains("Probed:"), "report must include probed count");
        assertTrue(content.contains("Skipped/Unprobed:"), "report must include unprobed count");
    }

    @Test
    void probeCoverageIsHonestlyReported() {
        RewindRoundTripProbe probe = new RewindRoundTripProbe();
        RewindRoundTripProbe.ProbeReport report = probe.run(Set.of());

        // Skipped count + probed count must equal total
        assertEquals(report.totalClasses(), report.probed() + report.skipped(),
                "probed + skipped must equal total classes discovered");

        // Probe fraction must be reported (0.0-1.0)
        double fraction = report.probedFraction();
        assertTrue(fraction >= 0.0 && fraction <= 1.0,
                "probe fraction must be in [0.0, 1.0], got: " + fraction);

        // We must not silently over-claim: if an object is unprobed, it must have a reason
        for (RewindRoundTripProbe.SkipRecord skip : report.skipRecords()) {
            assertNotNull(skip.reason(), "every skip must have a non-null reason");
            assertFalse(skip.reason().isBlank(), "every skip must have a non-empty reason");
            assertFalse(skip.className().isBlank(), "every skip must name the class");
        }
    }

    @Test
    void realGapRecordsHaveFieldDetails() {
        RewindRoundTripProbe probe = new RewindRoundTripProbe();
        RewindRoundTripProbe.ProbeReport report = probe.run(Set.of());

        for (RewindRoundTripProbe.GapRecord gap : report.realGaps()) {
            assertFalse(gap.className().isBlank(),
                    "gap record must have a class name");
            assertFalse(gap.fieldName().isBlank(),
                    "gap record must identify the differing field");
            // before/after may be null for Object types, but the field name must be present
        }
    }
}
