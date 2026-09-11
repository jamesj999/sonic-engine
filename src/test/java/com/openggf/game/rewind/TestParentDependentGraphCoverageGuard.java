package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.rewind.coverage.ObjectClasspathScan;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard for the object-reference families that the scalar-only harness cannot
 * honestly prove in isolation.
 *
 * <p>These classes are expected to show up as {@code parent-dependent} in the
 * headless round-trip sweep: they can be constructed, but recreate needs a live
 * parent/sibling graph in {@code ObjectManager}. The baseline keeps that bucket
 * explicit and marks focused graph rewind test evidence for each family. Any
 * remaining unproven families stay visible as {@code session-tail} rows until
 * focused session/family harnesses cover them. An empty baseline is valid once
 * the parent-dependent tail is fully graph-covered.
 */
class TestParentDependentGraphCoverageGuard {
    private static final String BASELINE_RESOURCE =
            "/rewind/parent-dependent-graph-coverage-baseline.txt";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests() {
        Map<String, BaselineEntry> baseline = loadBaseline();
        Set<String> current = currentParentDependentClasses();

        assertEquals(baseline.keySet(), current,
                "Parent-dependent rewind bucket changed. If a class moved out, "
                        + "delete or reclassify its baseline row. If a class moved in, "
                        + "add a row as covered only after a focused graph/session test exists; "
                        + "otherwise mark it session-tail.");

        long covered = baseline.values().stream()
                .filter(entry -> entry.status() == Status.COVERED)
                .count();
        long sessionTail = baseline.values().stream()
                .filter(entry -> entry.status() == Status.SESSION_TAIL)
                .count();
        assertEquals(baseline.size(), covered + sessionTail,
                "baseline rows must be accounted for as covered or session-tail");

        for (BaselineEntry entry : baseline.values()) {
            if (entry.status() == Status.COVERED) {
                assertGraphTestClassExists(entry.evidence(), entry.className());
            } else {
                assertFalse(entry.evidence().isBlank(),
                        entry.className() + " session-tail row must explain remaining work");
            }
        }
    }

    private static Set<String> currentParentDependentClasses() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        assertNotNull(srcMain, "test must run from a source checkout");

        List<ObjectClasspathScan.SourceClass> classes;
        try {
            classes = ObjectClasspathScan.findConcreteObjectInstances(srcMain);
        } catch (IOException e) {
            throw new AssertionError("Failed to scan object classes", e);
        }

        Set<String> parentDependent = new TreeSet<>();
        for (ObjectClasspathScan.SourceClass sourceClass : classes) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(sourceClass.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed
                    && unprobed.reason().startsWith("parent-dependent")) {
                parentDependent.add(sourceClass.fqn());
            }
        }
        return parentDependent;
    }

    private static Map<String, BaselineEntry> loadBaseline() {
        InputStream stream = TestParentDependentGraphCoverageGuard.class
                .getResourceAsStream(BASELINE_RESOURCE);
        assertNotNull(stream, "missing " + BASELINE_RESOURCE);

        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\|", 3);
                assertEquals(3, parts.length,
                        "baseline line " + lineNumber + " must be status|class|evidence");
                Status status = Status.parse(parts[0], lineNumber);
                String className = parts[1];
                String evidence = parts[2];
                assertTrue(entries.put(className, new BaselineEntry(status, className, evidence)) == null,
                        "duplicate baseline class " + className);
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + BASELINE_RESOURCE, e);
        }
        return entries;
    }

    private static void assertGraphTestClassExists(String testName, String coveredClassName) {
        assertFalse(testName.isBlank(), coveredClassName + " covered row must name a test");
        String className = testName.contains(".")
                ? testName
                : TestParentDependentGraphCoverageGuard.class.getPackageName() + "." + testName;
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(
                    coveredClassName + " is marked covered by missing graph test " + testName, e);
        }
    }

    private enum Status {
        COVERED,
        SESSION_TAIL;

        static Status parse(String raw, int lineNumber) {
            return switch (raw) {
                case "covered" -> COVERED;
                case "session-tail" -> SESSION_TAIL;
                default -> throw new AssertionError(
                        "baseline line " + lineNumber + " has unknown status " + raw);
            };
        }
    }

    private record BaselineEntry(Status status, String className, String evidence) {
    }
}
