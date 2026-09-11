package com.openggf.trace.catalog;

import com.openggf.game.save.SelectedTeam;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;

import java.nio.file.Path;

/**
 * One trace directory scanned by {@link TraceCatalog}. Constructed from
 * {@code metadata.json} + {@code physics.csv} row count + BK2 path.
 *
 * <p>{@code runDir} and {@code runManifest} are non-null only for entries
 * built via {@link #forRun}, which represent a multi-segment trace run
 * ({@code run_manifest.json}) rather than an ordinary single-segment trace.
 */
public record TraceEntry(
        Path dir,
        String gameId,
        int zone,
        int act,
        int frameCount,
        int bk2StartOffset,
        int preTraceOscFrames,
        SelectedTeam team,
        Path bk2Path,
        TraceMetadata metadata,
        Path runDir,
        TraceRunManifest runManifest) {

    /**
     * Legacy constructor for ordinary (single-segment) trace entries. Leaves
     * the run-specific trailing components null.
     */
    public TraceEntry(
            Path dir,
            String gameId,
            int zone,
            int act,
            int frameCount,
            int bk2StartOffset,
            int preTraceOscFrames,
            SelectedTeam team,
            Path bk2Path,
            TraceMetadata metadata) {
        this(dir, gameId, zone, act, frameCount, bk2StartOffset, preTraceOscFrames,
                team, bk2Path, metadata, null, null);
    }

    /**
     * Builds a catalog entry for a multi-segment trace run. {@code team} and
     * {@code metadata} are derived from segment 0's {@code metadata.json} so
     * existing consumers (e.g. {@code TestModeTracePicker.formatTeam},
     * {@code displayLabel()}) can dereference them without special-casing
     * runs. Zone/act are converted from segment 0 exactly as
     * {@link TraceCatalog}'s ordinary trace loading does, since they still
     * feed the level-boot driver.
     */
    public static TraceEntry forRun(Path runDir, TraceRunManifest manifest, Path bk2Path) {
        TraceRunManifest.Segment first = manifest.segments().get(0);
        int frameCount = manifest.segments().stream()
                .mapToInt(TraceRunManifest.Segment::traceFrameCount)
                .sum();
        TraceMetadata segmentMeta;
        try {
            segmentMeta = TraceMetadata.load(
                    runDir.resolve(first.dir()).resolve("metadata.json"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "Could not load segment 0 metadata for run " + runDir, e);
        }
        String main = segmentMeta.recordedMainCharacter();
        SelectedTeam team = new SelectedTeam(
                main == null ? "sonic" : main,
                segmentMeta.recordedSidekicks());
        int romZoneId = first.zoneId() != null ? first.zoneId() : 0;
        int engineZone = TraceCatalog.romZoneToProgressionIndex(manifest.game(), romZoneId);
        int engineAct = Math.max(0, (first.act() != null ? first.act() : 1) - 1);
        return new TraceEntry(
                runDir,
                manifest.game(),
                engineZone,
                engineAct,
                frameCount,
                first.bk2FrameOffset(),
                segmentMeta.preTraceOscillationFrames(),
                team,
                bk2Path,
                segmentMeta,
                runDir,
                manifest);
    }

    public boolean isRun() {
        return runManifest != null;
    }

    /**
     * Human-readable label for this entry, used by the trace test-mode
     * picker. Run entries are labeled by their run id and segment count.
     * Special-stage traces (no meaningful zone/act) are labeled by their
     * 1-indexed {@code special_stage_index}; all other (level) traces keep
     * the zone/act-derived text.
     */
    public String displayLabel() {
        if (isRun()) {
            return "RUN " + runManifest.runId() + " (" + runManifest.segments().size() + " segments)";
        }
        if (isSpecialStageProfile(metadata.traceProfile())) {
            Integer index = metadata.specialStageIndex();
            int oneIndexed = (index != null ? index : 0) + 1;
            return gameId.toUpperCase() + " SPECIAL STAGE " + oneIndexed;
        }
        return String.format("Zone: %02X  Act: %d", zone, act);
    }

    private static boolean isSpecialStageProfile(String profile) {
        return "s1_special_stage".equals(profile)
                || "s2_special_stage".equals(profile)
                || "s3k_special_stage".equals(profile);
    }
}
