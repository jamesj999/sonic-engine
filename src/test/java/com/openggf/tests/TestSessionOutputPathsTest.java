package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSessionOutputPathsTest {

    private static final String TRACE_REPORTS = "openggf.trace.reports";
    private static final String DIAGNOSTICS = "openggf.test.diagnostics";
    private static final String ARTIFACT_ROOT = "openggf.artifact.root";

    private final Map<String, String> originalProperties = new HashMap<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void rememberSessionProperties() {
        for (String name : new String[]{TRACE_REPORTS, DIAGNOSTICS, ARTIFACT_ROOT}) {
            originalProperties.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach
    void restoreSessionProperties() {
        originalProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void noSessionKeepsLegacyOutputDefaults() {
        assertEquals(Path.of("target", "trace-reports"), TestSessionOutputPaths.traceReports());
        assertEquals(Path.of("target", "diagnostics", "physics"),
                TestSessionOutputPaths.diagnostics("physics"));
        assertEquals(Path.of("target"), TestSessionOutputPaths.artifactRoot());
    }

    @Test
    void sessionPropertiesResolveToSuppliedAbsolutePaths() throws IOException {
        Path sessionRoot = Files.createTempDirectory(tempDir, "openggf output paths ");
        Path traceReports = sessionRoot.resolve("trace reports");
        Path diagnostics = sessionRoot.resolve("diagnostics");
        Path artifacts = sessionRoot.resolve("artifacts");
        System.setProperty(TRACE_REPORTS, traceReports.toString());
        System.setProperty(DIAGNOSTICS, diagnostics.toString());
        System.setProperty(ARTIFACT_ROOT, artifacts.toString());

        assertEquals(traceReports, TestSessionOutputPaths.traceReports());
        assertEquals(diagnostics.resolve("physics"),
                TestSessionOutputPaths.diagnostics("physics"));
        assertEquals(artifacts, TestSessionOutputPaths.artifactRoot());
    }

    @Test
    void repeatedAllocationReplacesStaleReportAndPreservesOwnerMetadata() throws IOException {
        Path sessionRoot = Files.createTempDirectory(tempDir, "openggf report owner ");
        System.setProperty(TRACE_REPORTS, sessionRoot.toString());

        TestSessionOutputPaths.ReportAllocation first = TestSessionOutputPaths.allocateReport(
                "trace", "com.openggf.tests.ExampleTest", "replay", 2,
                "0123456789abcdef", "lane-a", "s2_mtz1", ".json");

        assertEquals("s2_mtz1", first.logicalKey());
        assertNotEquals(first.physicalPath(), first.metadataPath());
        assertTrue(first.physicalPath().startsWith(sessionRoot));
        TestSessionOutputPaths.publish(first.physicalPath(), "report");
        TestSessionOutputPaths.publishOwnerMetadata(first);
        assertTrue(Files.isRegularFile(first.metadataPath()),
                "allocation must publish the ownership sidecar atomically");

        TestSessionOutputPaths.ReportAllocation duplicate =
                TestSessionOutputPaths.allocateReport(
                        "trace", "com.openggf.tests.ExampleTest", "replay", 2,
                        "0123456789abcdef", "lane-a", "s2_mtz1", ".json");
        TestSessionOutputPaths.publish(duplicate.physicalPath(), "replacement");
        TestSessionOutputPaths.publishOwnerMetadata(duplicate);

        assertEquals("replacement", Files.readString(duplicate.physicalPath()));
        assertTrue(Files.readString(duplicate.metadataPath()).contains(
                "\"owner_key\": \"" + duplicate.ownerKey() + "\""));
    }

    @Test
    void equivalentPublicationIsIdempotentAndPreservesOwnerMetadata() throws IOException {
        Path sessionRoot = Files.createTempDirectory(tempDir, "openggf report repeat ");
        System.setProperty(TRACE_REPORTS, sessionRoot.toString());

        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport(
                        "trace", "com.openggf.tests.ExampleTest", "replay", 2,
                        "0123456789abcdef", "lane-a", "s2_mtz1", ".json");

        TestSessionOutputPaths.publish(allocation.physicalPath(), "report");
        TestSessionOutputPaths.publish(allocation.physicalPath(), "report");
        TestSessionOutputPaths.publishOwnerMetadata(allocation);
        TestSessionOutputPaths.publishOwnerMetadata(allocation);

        assertEquals("report", Files.readString(allocation.physicalPath()));
        assertTrue(Files.readString(allocation.metadataPath()).contains(
                "\"owner_key\": \"" + allocation.ownerKey() + "\""));
    }

    @Test
    void pathComponentsCannotEscapeTheirParent() throws IOException {
        Path sessionRoot = Files.createTempDirectory(tempDir, "openggf component safety ");
        System.setProperty(DIAGNOSTICS, sessionRoot.toString());
        System.setProperty(TRACE_REPORTS, sessionRoot.toString());

        assertEquals(sessionRoot.resolve("_.."), TestSessionOutputPaths.diagnostics(".."));
        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport(
                        "..", "com.openggf.tests.ExampleTest", "replay", 0,
                        "0123456789abcdef", "lane", "key", ".json");
        assertTrue(allocation.physicalPath().startsWith(sessionRoot));
    }

    @Test
    void explicitOutputDirectoryRetainsLegacyReportBasename() throws IOException {
        Path outputDirectory = Files.createTempDirectory(tempDir, "openggf explicit report ");
        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport(outputDirectory,
                        "trace", "com.openggf.tests.ExampleTest", "replay", 2,
                        "0123456789abcdef", "lane-a", "s2_mtz1", ".json");

        assertEquals(outputDirectory.resolve("s2_mtz1_report.json"),
                allocation.physicalPath());
        assertTrue(allocation.metadataPath().startsWith(outputDirectory));
    }
}
