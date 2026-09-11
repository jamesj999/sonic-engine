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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Executable inventory for the remaining non-passing rewind round-trip tail.
 *
 * <p>The codec-deletion phase is complete: the sweep has no {@code no-codec}
 * entries, and remaining non-passing classes need graph/session work or better
 * headless construction. This guard keeps that tail explicit so the next phase
 * can shrink it deliberately instead of rediscovering it from console output.
 */
class TestRemainingRewindTailInventory {
    private static final String INVENTORY_RESOURCE =
            "/rewind/round-trip-tail-inventory.txt";
    private static final String PARENT_DEPENDENT_RESOURCE =
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
    void remainingRoundTripTailMatchesInventory() {
        TailInventory expected = loadInventory();
        TailInventory current = currentInventory();
        String delta = describeDelta(expected, current);

        assertEquals(expected.total(), current.total(), "total object class count changed\n" + delta);
        assertEquals(expected.passed(), current.passed(), "passed object count changed\n" + delta);
        assertEquals(expected.graphCovered(), current.graphCovered(), "graph-covered object count changed\n" + delta);
        assertEquals(expected.noCodec(), current.noCodec(), "no-codec bucket must stay empty\n" + delta);
        assertEquals(expected.classesByBucket(), current.classesByBucket(),
                "Remaining rewind tail changed. If a class moved out, delete its row. "
                        + "If a class moved in, classify it honestly before continuing.\n" + delta);
    }

    private static TailInventory currentInventory() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        assertNotNull(srcMain, "test must run from a source checkout");

        List<ObjectClasspathScan.SourceClass> classes;
        try {
            classes = ObjectClasspathScan.findConcreteObjectInstances(srcMain);
        } catch (IOException e) {
            throw new AssertionError("Failed to scan object classes", e);
        }

        Map<Bucket, TreeSet<String>> buckets = emptyBuckets();
        int passed = 0;
        int graphCovered = 0;
        int noCodec = 0;
        for (ObjectClasspathScan.SourceClass sourceClass : classes) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(sourceClass.fqn());
            if (result instanceof RoundTripSweepResult.Passed) {
                passed++;
            } else if (result instanceof RoundTripSweepResult.GraphCovered) {
                graphCovered++;
            } else if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                String reason = unprobed.reason();
                if (reason.startsWith("no dynamic recreate path")) {
                    noCodec++;
                } else if (reason.startsWith("parent-dependent")) {
                    buckets.get(Bucket.PARENT_DEPENDENT).add(sourceClass.fqn());
                } else if (reason.contains("NoSuchMethodError")
                        || reason.contains("No probe-compatible constructor")) {
                    buckets.get(Bucket.NO_PROBE_CTOR).add(sourceClass.fqn());
                } else {
                    buckets.get(Bucket.OTHER_FAILURE).add(sourceClass.fqn());
                }
            } else if (result instanceof RoundTripSweepResult.CountMismatch) {
                buckets.get(Bucket.COUNT_MISMATCH).add(sourceClass.fqn());
            } else if (result instanceof RoundTripSweepResult.ScalarMismatch) {
                buckets.get(Bucket.SCALAR_MISMATCH).add(sourceClass.fqn());
            }
        }
        return new TailInventory(classes.size(), passed, graphCovered, noCodec, buckets);
    }

    private static TailInventory loadInventory() {
        Map<Bucket, TreeSet<String>> buckets = emptyBuckets();
        loadBucketRows(INVENTORY_RESOURCE, buckets);
        loadParentDependentRows(buckets);

        assertEquals(0, buckets.get(Bucket.NO_PROBE_CTOR).size(), "no-probe-ctor inventory count");
        assertEquals(0, buckets.get(Bucket.PARENT_DEPENDENT).size(), "parent-dependent inventory count");
        assertEquals(0, buckets.get(Bucket.OTHER_FAILURE).size(), "other-failure inventory count");
        assertEquals(0, buckets.get(Bucket.COUNT_MISMATCH).size(), "count-mismatch inventory count");
        assertEquals(0, buckets.get(Bucket.SCALAR_MISMATCH).size(), "scalar-mismatch inventory count");

        // 886/690: the object inventory gained six concrete runtime classes;
        // five pass the isolated round-trip probe, while Obj11's parent-owned
        // bridge segment is covered by TestS2BridgeSegmentGraphRewind.
        // 889/693: the three GAME OVER / TIME OVER card classes, all passing
        // the isolated probe through their zero-scalar recreate constructor.
        return new TailInventory(889, 693, 196, 0, buckets);
    }

    private static void loadBucketRows(String resource, Map<Bucket, TreeSet<String>> buckets) {
        InputStream stream = TestRemainingRewindTailInventory.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing " + resource);

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
                String[] parts = trimmed.split("\\|", 2);
                assertEquals(2, parts.length,
                        "inventory line " + lineNumber + " must be bucket|class");
                Bucket bucket = Bucket.parse(parts[0], lineNumber, resource);
                buckets.get(bucket).add(parts[1]);
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + resource, e);
        }
    }

    private static void loadParentDependentRows(Map<Bucket, TreeSet<String>> buckets) {
        InputStream stream = TestRemainingRewindTailInventory.class
                .getResourceAsStream(PARENT_DEPENDENT_RESOURCE);
        assertNotNull(stream, "missing " + PARENT_DEPENDENT_RESOURCE);

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
                        "parent baseline line " + lineNumber + " must be status|class|evidence");
                buckets.get(Bucket.PARENT_DEPENDENT).add(parts[1]);
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + PARENT_DEPENDENT_RESOURCE, e);
        }
    }

    private static Map<Bucket, TreeSet<String>> emptyBuckets() {
        Map<Bucket, TreeSet<String>> buckets = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) {
            buckets.put(bucket, new TreeSet<>());
        }
        return buckets;
    }

    private static String describeDelta(TailInventory expected, TailInventory current) {
        StringBuilder sb = new StringBuilder();
        sb.append("expected total=").append(expected.total())
                .append(" passed=").append(expected.passed())
                .append(" graphCovered=").append(expected.graphCovered())
                .append(" noCodec=").append(expected.noCodec())
                .append("; current total=").append(current.total())
                .append(" passed=").append(current.passed())
                .append(" graphCovered=").append(current.graphCovered())
                .append(" noCodec=").append(current.noCodec());
        for (Bucket bucket : Bucket.values()) {
            TreeSet<String> expectedRows = expected.classesByBucket().get(bucket);
            TreeSet<String> currentRows = current.classesByBucket().get(bucket);
            TreeSet<String> added = new TreeSet<>(currentRows);
            added.removeAll(expectedRows);
            TreeSet<String> removed = new TreeSet<>(expectedRows);
            removed.removeAll(currentRows);
            if (!added.isEmpty() || !removed.isEmpty()) {
                sb.append(System.lineSeparator()).append(bucket.id).append(" added=").append(added)
                        .append(" removed=").append(removed);
            }
        }
        return sb.toString();
    }

    private enum Bucket {
        NO_PROBE_CTOR("no-probe-ctor"),
        PARENT_DEPENDENT("parent-dependent"),
        OTHER_FAILURE("other-failure"),
        COUNT_MISMATCH("count-mismatch"),
        SCALAR_MISMATCH("scalar-mismatch");

        private final String id;

        Bucket(String id) {
            this.id = id;
        }

        static Bucket parse(String raw, int lineNumber, String resource) {
            for (Bucket bucket : values()) {
                if (bucket.id.equals(raw)) {
                    return bucket;
                }
            }
            throw new AssertionError(resource + " line " + lineNumber
                    + " has unknown bucket " + raw);
        }
    }

    private record TailInventory(
            int total,
            int passed,
            int graphCovered,
            int noCodec,
            Map<Bucket, TreeSet<String>> classesByBucket) {
    }
}
