package com.openggf.tests.trace;

import com.openggf.tests.SessionInvocationExtension.SessionInvocation;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.TraceVerificationScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared report-writing helper for the special-stage trace replay bases
 * (S1/S2/S3K). Their {@code writeReport(report, ssIndex)} bodies were identical
 * except for the filename prefix; this hoists the body and parameterizes it by
 * output directory, prefix, and context radius so the three bases cannot drift.
 */
public final class TraceReportWriter {

    private TraceReportWriter() {
    }

    public static TestSessionOutputPaths.ReportAllocation writeSpecialStageReport(
            DivergenceReport report,
            String profile,
            SessionInvocation invocation,
            String laneId,
            String prefix,
            int contextRadius) throws IOException {
        assertGroupAccountingHolds(report);
        return writeReport(report, profile, invocation, laneId, prefix,
                TraceVerificationScope.ALL, contextRadius);
    }

    public static TestSessionOutputPaths.ReportAllocation writeReport(
            DivergenceReport report,
            String profile,
            SessionInvocation invocation,
            String laneId,
            String logicalKey,
            TraceVerificationScope scope,
            int contextRadius) throws IOException {
        return writeReport(null, report, profile, invocation, laneId, logicalKey,
                scope, contextRadius);
    }

    /** Writes to a caller-selected report directory while retaining its legacy basename. */
    public static TestSessionOutputPaths.ReportAllocation writeReport(
            Path outputDirectory,
            DivergenceReport report,
            String profile,
            SessionInvocation invocation,
            String laneId,
            String logicalKey,
            TraceVerificationScope scope,
            int contextRadius) throws IOException {
        assertGroupAccountingHolds(report);
        TestSessionOutputPaths.ReportAllocation allocation =
                outputDirectory == null
                        || outputDirectory.toAbsolutePath().normalize().equals(
                                TestSessionOutputPaths.traceReports().toAbsolutePath().normalize())
                        ? TestSessionOutputPaths.allocateReport(profile,
                                invocation.className(), invocation.methodName(),
                                invocation.parameterIndex(), invocation.invocationId(),
                                laneId, logicalKey, ".json")
                        : TestSessionOutputPaths.allocateReport(outputDirectory,
                                profile, invocation.className(), invocation.methodName(),
                                invocation.parameterIndex(), invocation.invocationId(),
                                laneId, logicalKey, ".json");
        publish(allocation, allocation.physicalPath(), report.toJson(), "report");
        if (report.hasErrors(scope)) {
            Path contextPath = contextPath(allocation.physicalPath());
            publish(allocation, contextPath,
                    report.getContextWindow(report.firstErrorFrame(scope), contextRadius),
                    "context");
        } else {
            Path contextPath = contextPath(allocation.physicalPath());
            try {
                Files.deleteIfExists(contextPath);
            } catch (IOException failure) {
                throw publicationFailure(allocation, contextPath,
                        "stale context retirement", failure);
            }
        }
        try {
            TestSessionOutputPaths.publishOwnerMetadata(allocation);
        } catch (IOException failure) {
            throw publicationFailure(allocation, allocation.metadataPath(), "owner metadata",
                    failure);
        }
        return allocation;
    }

    private static void publish(
            TestSessionOutputPaths.ReportAllocation allocation,
            Path path,
            String content,
            String artifactKind) throws IOException {
        try {
            TestSessionOutputPaths.publish(path, content);
        } catch (IOException failure) {
            throw publicationFailure(allocation, path, artifactKind, failure);
        }
    }

    private static IOException publicationFailure(
            TestSessionOutputPaths.ReportAllocation allocation,
            Path path,
            String artifactKind,
            IOException failure) {
        return new IOException(
                "failed to publish trace " + artifactKind
                        + " for logical key '" + allocation.logicalKey()
                        + "' at " + path,
                failure);
    }

    private static Path contextPath(Path reportPath) {
        String filename = reportPath.getFileName().toString();
        String stem = filename.endsWith(".json")
                ? filename.substring(0, filename.length() - ".json".length())
                : filename;
        String contextName = stem.endsWith("_report")
                ? stem.substring(0, stem.length() - "_report".length()) + "_context.txt"
                : stem + "_context.txt";
        return reportPath.resolveSibling(contextName);
    }

    /**
     * Assert that the per-group error counts a standalone report publishes add up
     * to its flat {@code error_count}. The chain reports assert the same invariant
     * over the same increments; this is the standalone half, asserted while it
     * holds (56 of 56 fixtures at the time of writing) rather than after it stops
     * holding, because the reason the breakdown exists at all is that the chain
     * path had drifted somewhere nobody was checking.
     */
    public static void assertGroupAccountingHolds(DivergenceReport report) {
        assertEquals(report.publishedErrorCount(), report.errorCountByVerificationGroups(),
                "verification groups must account for the report's flat error count exactly");
    }
}
