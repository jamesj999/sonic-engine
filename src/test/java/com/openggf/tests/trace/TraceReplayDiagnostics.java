package com.openggf.tests.trace;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.EngineNearbyObject;
import com.openggf.trace.TraceVerificationScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared, read-only trace-replay diagnostic and gating helpers common to
 * {@link AbstractTraceReplayTest} and {@link AbstractCreditsDemoTraceReplayTest}.
 * The two bases have no common ancestor, so these byte-identical (or, for the
 * nearby-object scan, near-identical) fragments previously lived in both.
 *
 * <p>Comparison-only invariant: every method here reads engine/trace state to
 * build diagnostic context or evaluate the report; none writes recorded trace
 * rows back into engine state.
 */
final class TraceReplayDiagnostics {

    private TraceReplayDiagnostics() {
    }

    /** Join two diagnostic fragments with {@code " | "}, skipping empties. */
    static String combineDiagnostics(String base, String extra) {
        if (base == null || base.isEmpty()) {
            return extra == null ? "" : extra;
        }
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        return base + " | " + extra;
    }

    /** True when {@code fileName} (or its {@code .gz} sibling) exists in {@code dir}. */
    static boolean hasTracePayload(Path dir, String fileName) {
        return Files.exists(dir.resolve(fileName))
                || Files.exists(dir.resolve(fileName + ".gz"));
    }

    /**
     * Build the diagnostic list of on-screen objects near {@code sprite}. The
     * two bases and the sidekick scan differ only by the {@code maxDelta}
     * window and whether object id 0 is skipped, so those are parameters.
     * Returns objects sorted by slot index; read-only.
     */
    static List<EngineNearbyObject> buildNearbyObjects(ObjectManager om,
                                                       AbstractPlayableSprite sprite,
                                                       int maxDelta,
                                                       boolean skipObjectIdZero) {
        if (om == null || sprite == null) {
            return List.of();
        }
        List<EngineNearbyObject> nearbyObjects = new ArrayList<>();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            ObjectSpawn spawn = aoi.getSpawn();
            if (spawn == null || (skipObjectIdZero && spawn.objectId() == 0)) {
                continue;
            }
            int currentX = aoi.getX();
            int currentY = aoi.getY();
            int dx = Math.abs(currentX - sprite.getCentreX());
            int dy = Math.abs(currentY - sprite.getCentreY());
            if (dx > maxDelta || dy > maxDelta) {
                continue;
            }
            TouchResponseProvider provider =
                    instance instanceof TouchResponseProvider trp ? trp : null;
            nearbyObjects.add(new EngineNearbyObject(
                    aoi.getSlotIndex(),
                    spawn.objectId(),
                    aoi.getName(),
                    currentX,
                    currentY,
                    spawn.x(),
                    spawn.y(),
                    provider != null,
                    provider != null ? provider.getCollisionFlags() : -1,
                    provider != null ? aoi.getPreUpdateCollisionFlags() : -1,
                    aoi.getPreUpdateX(),
                    aoi.getPreUpdateY(),
                    aoi.isSkipTouchThisFrame(),
                    aoi.isSkipSolidContactThisFrame(),
                    aoi.isOnScreenForTouch(),
                    aoi.traceDebugDetails()));
        }
        nearbyObjects.sort(Comparator.comparingInt(EngineNearbyObject::slot));
        return nearbyObjects;
    }

    /**
     * Shared release gate: any error under {@code scope} fails; warnings fail
     * unless {@code allowDiagnosticOnlyWarnings} is set. Identical to the
     * previous per-base {@code assertReportHasNoReleaseBlockingDivergences}.
     */
    static void assertNoReleaseBlockingDivergences(DivergenceReport report,
                                                   TraceVerificationScope scope,
                                                   boolean allowDiagnosticOnlyWarnings) {
        if (report.hasErrors(scope)) {
            fail(report.toAssertionSummary(scope));
        }
        if (report.hasWarnings(scope) && !allowDiagnosticOnlyWarnings) {
            fail("Trace replay warning report is release-blocking by default: "
                    + report.toAssertionSummary(scope));
        }
    }
}
