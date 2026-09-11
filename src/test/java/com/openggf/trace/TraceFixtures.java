package com.openggf.trace;

import java.util.List;
import java.util.Map;

import com.openggf.trace.timing.HardwareTimingSchedule;

/**
 * Test-only factories for building {@link TraceData} / {@link TraceMetadata}
 * values without disk I/O. Kept in {@code src/test/} so production code
 * never depends on these helpers. Lives in the same Java package as the
 * types it constructs so it can call their package-private constructors.
 */
public final class TraceFixtures {

    private TraceFixtures() {
    }

    /** In-memory TraceData for unit tests. */
    public static TraceData trace(TraceMetadata metadata, List<TraceFrame> frames) {
        return new TraceData(metadata, List.copyOf(frames), Map.of(), HardwareTimingSchedule.empty());
    }

    /** In-memory TraceData for unit tests with explicit aux events. */
    public static TraceData trace(TraceMetadata metadata, List<TraceFrame> frames,
                                  Map<Integer, List<TraceEvent>> eventsByFrame) {
        return new TraceData(metadata, List.copyOf(frames), Map.copyOf(eventsByFrame), HardwareTimingSchedule.empty());
    }

    public static TraceData trace(
            TraceMetadata metadata,
            List<TraceFrame> frames,
            HardwareTimingSchedule schedule) {
        return new TraceData(metadata, List.copyOf(frames), Map.of(), schedule);
    }

    /** Minimal metadata stub for unit tests. */
    public static TraceMetadata metadata(String gameId, int zoneId, int act) {
        return metadata(gameId, zoneId, act, null);
    }

    /** Minimal metadata stub with an explicit frame-0 RNG seed. */
    public static TraceMetadata metadataWithRngSeed(String gameId, int zoneId, int act, String rngSeedHex) {
        return metadata(gameId, zoneId, act, rngSeedHex);
    }

    public static TraceMetadata metadataWithHardwareTiming(
            String gameId, int zoneId, int act, int traceFrameCount) {
        TraceMetadata base = metadata(gameId, zoneId, act);
        return new TraceMetadata(
                base.game(), base.zone(), base.zoneId(), base.act(),
                base.bk2FrameOffset(), base.ringFloorCheckCounterPhase(),
                traceFrameCount, base.startXHex(), base.startYHex(),
                base.recordingDate(), base.recorder(), base.recorderVersion(), base.traceSchema(),
                base.traceProfile(), base.bizhawkVersion(),
                base.genesisCore(), base.auxSchemaExtras(), base.romZoneId(),
                base.route(), base.sourceBk2(), base.romChecksum(), base.notes(),
                base.characters(), base.mainCharacter(), base.sidekicks(),
                base.preTraceOscFrames(), base.rngSeedHex(), base.traceType(),
                base.inputSource(), base.creditsDemoIndex(), base.creditsDemoSlug(),
                base.specialStageIndex(), base.runId(), base.segmentIndex(),
                base.bonusStageType(), base.freshLoad(), base.vIntRunCount());
    }

    public static TraceMetadata metadataWithDynamicArt(
            String gameId, int zoneId, int act, int traceFrameCount) {
        TraceMetadata base = metadata(gameId, zoneId, act);
        return new TraceMetadata(
                base.game(), base.zone(), base.zoneId(), base.act(),
                base.bk2FrameOffset(), base.ringFloorCheckCounterPhase(),
                traceFrameCount, base.startXHex(), base.startYHex(),
                base.recordingDate(), base.recorder(), base.recorderVersion(), base.traceSchema(),
                base.traceProfile(), base.bizhawkVersion(),
                base.genesisCore(),
                List.of("dynamic_art_transfer_state_per_frame"),
                base.romZoneId(), base.route(), base.sourceBk2(),
                base.romChecksum(), base.notes(), base.characters(),
                base.mainCharacter(), base.sidekicks(),
                base.preTraceOscFrames(), base.rngSeedHex(), base.traceType(),
                base.inputSource(), base.creditsDemoIndex(),
                base.creditsDemoSlug(), base.specialStageIndex(), base.runId(),
                base.segmentIndex(), base.bonusStageType(), base.freshLoad(),
                base.vIntRunCount());
    }

    private static TraceMetadata metadata(String gameId, int zoneId, int act, String rngSeedHex) {
        return new TraceMetadata(
                gameId,
                "TEST",
                zoneId,
                act,
                0,
                null,
                0,
                "0x0000",
                "0x0000",
                null,
                null,
                null,
                5,
                null,
                null,
                null,
                null /* aux_schema_extras */,
                null,
                null,
                null,
                null,
                null,
                List.of("sonic"),
                "sonic",
                List.of(),
                0,
                rngSeedHex,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
