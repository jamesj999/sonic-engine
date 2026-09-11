package com.openggf.game.rewind;

import com.openggf.game.GameId;
import com.openggf.game.rewind.coverage.ObjectClasspathScan;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parametrized gate: every spawnable concrete object class must survive a
 * rewind capture→restore round-trip with the same object COUNT and identical
 * scalar field values.
 *
 * <h2>Why this is the oracle for Phase 2</h2>
 * This test drives the <em>real</em> {@link com.openggf.level.objects.ObjectManager}
 * capture→restore path through {@link RewindRoundTripHarness}, unlike the legacy
 * {@code RewindRoundTripProbe} which re-constructs from spawn and passes tautologically.
 * Any rewind codec gap, double-spawn, or init-revert failure surfaces here as a hard failure.
 *
 * <h2>TDD contract</h2>
 * <ol>
 *   <li>{@link #dezRobotChildrenRoundTripExactlyThroughHarness} — the keystone test
 *       validating the harness wires the real ObjectManager + RewindRegistry path.
 *       This PASSES once the harness is correctly wired.</li>
 *   <li>{@link #everySpawnableObjectRoundTrips} — parametrized sweep over all discovered
 *       classes. Objects the harness cannot construct headlessly are recorded as
 *       "unprobed" (see {@link #unprobedCountIsWithinAllowance}).</li>
 * </ol>
 *
 * <h2>Unprobed allowance</h2>
 * Objects requiring ROM/OpenGL at construction time are unprobed. The count is bounded
 * by {@link #UNPROBED_ALLOWANCE}. Shrink the allowance as objects become headless-safe.
 */
public class TestEveryObjectRewindRoundTrip {

    /**
     * Maximum allowed unprobed count. Lower this as more objects become headlessly
     * constructable or gain codecs. A failure means a new object now lacks a codec
     * or requires ROM/OpenGL — either add a codec or document the gap.
     *
     * <p>Initial ceiling set from the first sweep run: 760 unprobed objects (mostly
     * objects without dynamic rewind codecs — correct behaviour for Phase 1).
     * Shrink this as Phase 2 adds codecs.
     */
    private static final int UNPROBED_ALLOWANCE = 800; // initial ceiling (760 measured)

    // =========================================================================
    // Setup / teardown
    // =========================================================================

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    // =========================================================================
    // Step 1 — Keystone test: DEZ boss-child round-trips exactly through harness
    // =========================================================================

    /**
     * DEZ robot children must round-trip through the harness with an unchanged
     * COUNT and exact, non-init scalar state. This is the keystone test that
     * validates the harness drives the real ObjectManager + RewindRegistry path.
     *
     * <p>It PASSES once the harness correctly wires placed-spawn materialisation,
     * capture, and restore — including the boss-child codec and the suppression
     * of double-spawn during restore reconstruction.
     */
    @Test
    void dezRobotChildrenRoundTripExactlyThroughHarness() {
        // Build harness with the DEZ boss as a placed/active object.
        // buildPlaced materialises via om.reset(0) and registers the RewindRegistry.
        RewindRoundTripHarness h = RewindRoundTripHarness.buildPlaced(
                GameId.S2, Sonic2ObjectIds.DEATH_EGG_ROBOT);

        // Record the count and a non-init scalar before round-trip.
        Map<String, Integer> before = h.countByType();
        int capturedX = h.firstArticulatedChildX();

        // Round-trip through the real ObjectManager + RewindRegistry.
        h.roundTrip();

        // (a) Count must not change: no double-spawn, no drop.
        assertEquals(before, h.countByType(),
                "object counts must be unchanged after rewind round-trip "
                        + "(no double-spawn, no drop): before=" + before
                        + " after=" + h.countByType());

        // (b) Scalar state must be exact — zero re-simulation.
        // ArticulatedChild currentX is set at construction from the boss's X position.
        // It is not the default -1 from the harness helper (no child found).
        assertTrue(capturedX != -1,
                "precondition: harness must find at least one ArticulatedChild after materialisation");
        assertEquals(capturedX, h.firstArticulatedChildX(),
                "ArticulatedChild currentX must be restored EXACTLY (target==keyframe, "
                        + "zero re-sim); capturedX=" + capturedX
                        + " restoredX=" + h.firstArticulatedChildX());
    }

    // =========================================================================
    // Steps 3b-4 — Parametrized sweep over all spawnable object classes
    // =========================================================================

    /**
     * Provides the FQNs of every concrete {@code AbstractObjectInstance} subclass
     * discovered by the source-file scan (including inner classes).
     */
    static Stream<String> allSpawnableObjectClassNames() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        if (srcMain == null) {
            // Running outside source checkout — skip sweep.
            return Stream.empty();
        }
        try {
            return ObjectClasspathScan.findConcreteObjectInstances(srcMain)
                    .stream()
                    .map(ObjectClasspathScan.SourceClass::fqn);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan object classes", e);
        }
    }

    /**
     * Every concrete spawnable object must survive a rewind round-trip with:
     * <ul>
     *   <li>Unchanged object count (no double-spawn, no silent drop), and</li>
     *   <li>Identical scalar/primitive/enum/String field values (no init-revert).</li>
     * </ul>
     *
     * <p>Objects that cannot be constructed headlessly (ROM/OpenGL required) are
     * recorded as "unprobed" — they are NOT silently passed. The aggregate unprobed
     * count is bounded by {@link #unprobedCountIsWithinAllowance}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allSpawnableObjectClassNames")
    void everySpawnableObjectRoundTrips(String fqn) {
        RewindRoundTripHarness.RoundTripSweepResult result =
                RewindRoundTripHarness.probeClass(fqn);

        if (result instanceof RewindRoundTripHarness.RoundTripSweepResult.Unprobed u) {
            // Record but do not fail — counted in the aggregate allowance check.
            System.out.println("[rewind-sweep][unprobed] " + fqn + ": " + u.reason());
            return;
        }

        if (result instanceof RewindRoundTripHarness.RoundTripSweepResult.GraphCovered g) {
            System.out.println("[rewind-sweep][graph-covered] " + fqn + ": " + g.evidence());
            return;
        }

        if (result instanceof RewindRoundTripHarness.RoundTripSweepResult.Passed) {
            return; // clean round-trip
        }

        if (result instanceof RewindRoundTripHarness.RoundTripSweepResult.CountMismatch cm) {
            assertEquals(cm.beforeCounts(), cm.afterCounts(),
                    "Object count changed across rewind round-trip for " + fqn
                            + " (double-spawn or drop): before=" + cm.beforeCounts()
                            + " after=" + cm.afterCounts());
        }

        if (result instanceof RewindRoundTripHarness.RoundTripSweepResult.ScalarMismatch sm) {
            assertTrue(sm.diffs().isEmpty(),
                    "Scalar fields differ after rewind round-trip for " + fqn
                            + ": " + sm.diffs());
        }
    }

    /**
     * Aggregate guard: the total unprobed count must not exceed {@link #UNPROBED_ALLOWANCE}.
     *
     * <p>This surfaces when new ROM-dependent objects are added without making them
     * headlessly constructable. Lower the allowance as coverage improves.
     */
    @Test
    void unprobedCountIsWithinAllowance() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        if (srcMain == null) {
            System.out.println(
                    "[rewind-sweep] Not in source checkout — skipping unprobed aggregate check.");
            return;
        }
        List<ObjectClasspathScan.SourceClass> classes;
        try {
            classes = ObjectClasspathScan.findConcreteObjectInstances(srcMain);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan object classes", e);
        }

        int total = classes.size();
        int unprobed = 0;
        int probed = 0;
        int graphCovered = 0;
        int countMismatches = 0;
        int scalarMismatches = 0;
        List<String> unprobedNames = new ArrayList<>();
        List<String> failingNames = new ArrayList<>();

        for (ObjectClasspathScan.SourceClass sc : classes) {
            RewindRoundTripHarness.RoundTripSweepResult result =
                    RewindRoundTripHarness.probeClass(sc.fqn());
            switch (result) {
                case RewindRoundTripHarness.RoundTripSweepResult.Passed ignored -> probed++;
                case RewindRoundTripHarness.RoundTripSweepResult.GraphCovered ignored -> graphCovered++;
                case RewindRoundTripHarness.RoundTripSweepResult.Unprobed u -> {
                    unprobed++;
                    unprobedNames.add(sc.fqn());
                }
                case RewindRoundTripHarness.RoundTripSweepResult.CountMismatch cm -> {
                    probed++;
                    countMismatches++;
                    failingNames.add("[count-mismatch] " + sc.fqn());
                }
                case RewindRoundTripHarness.RoundTripSweepResult.ScalarMismatch sm -> {
                    probed++;
                    scalarMismatches++;
                    failingNames.add("[scalar-mismatch] " + sc.fqn() + ": " + sm.diffs());
                }
            }
        }

        // Report
        System.out.println("[rewind-sweep] total=" + total
                + " probed=" + probed
                + " graph-covered=" + graphCovered
                + " unprobed=" + unprobed
                + " count-mismatches=" + countMismatches
                + " scalar-mismatches=" + scalarMismatches);
        if (!failingNames.isEmpty()) {
            System.out.println("[rewind-sweep] failing classes:");
            failingNames.forEach(n -> System.out.println("  " + n));
        }

        // Unprobed allowance check.
        assertTrue(unprobed <= UNPROBED_ALLOWANCE,
                "Unprobed count " + unprobed + " exceeds allowance " + UNPROBED_ALLOWANCE
                        + ". Shrink UNPROBED_ALLOWANCE or make objects headlessly constructable.");
    }

}
