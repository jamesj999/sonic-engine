package com.openggf.trace.replay.runs;

import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.trace.TraceRunManifest;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Carries the ROM-visible object VBlank clock across shortened run transitions.
 * Targets derive only from the production source clock and manifest/BK2 row
 * distances; no comparison frame value is read into gameplay state.
 */
public final class TraceRunVblankClock {
    private final TracePlaybackProfile profile;
    private final Map<Integer, SourceAnchor> levelSourceTails = new HashMap<>();
    private final Map<Integer, Integer> presentationBridgeEntries = new HashMap<>();

    public TraceRunVblankClock(TracePlaybackProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public void captureLevelSourceTail(
            int segmentIndex,
            TraceRunManifest.Segment source,
            int observedBk2Cursor,
            int observedVblank) {
        if (!profile.alignsInterLevelVblank()
                && !profile.alignUncomparedInteriorReturnVblank()) {
            return;
        }
        Objects.requireNonNull(source, "source");
        if (!"level".equals(source.kind())) {
            return;
        }
        levelSourceTails.put(segmentIndex, new SourceAnchor(
                source, sourceTailVblank(
                        segmentIndex, source, observedBk2Cursor, observedVblank)));
    }

    /**
     * The clock a special stage's presentation bridge starts on. The bridge is
     * the results screen the stage exits through: the engine plays every one of
     * its rows, but no level loop runs on them, so the production counter never
     * ticks and cannot be projected forward from. The value therefore comes from
     * the level that entered the stage, plus one tick for each movie row between
     * that level's tail and the bridge.
     */
    public OptionalInt presentationBridgeEntryTarget(
            int sourceIndex,
            TraceRunManifest.Segment source,
            int bridgeIndex,
            TraceRunManifest.Segment bridge) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bridge, "bridge");
        if (!profile.alignsStageResultsPresentationVblank()
                || !"level".equals(source.kind())
                || !"level".equals(bridge.kind())) {
            return OptionalInt.empty();
        }
        SourceAnchor sourceAnchor = sourceAnchor(sourceIndex, source);
        if (sourceAnchor == null) {
            return OptionalInt.empty();
        }
        int target = Math.addExact(
                sourceAnchor.tailVblank(),
                TraceRunReplayWalker.presentationBridgeEntryVblankBudget(
                        source, bridge));
        presentationBridgeEntries.put(bridgeIndex, target);
        return OptionalInt.of(target);
    }

    /**
     * A bridge's own tail is derived, not observed: its recorded rows each
     * carry a V-int except the profiled ones the ROM builds the results screen
     * through with interrupts disabled.
     */
    private int sourceTailVblank(
            int segmentIndex,
            TraceRunManifest.Segment source,
            int observedBk2Cursor,
            int observedVblank) {
        Integer bridgeEntry = presentationBridgeEntries.get(segmentIndex);
        if (bridgeEntry != null) {
            return Math.addExact(
                    bridgeEntry,
                    TraceRunReplayWalker.presentationBridgeVblankSpan(
                            source,
                            profile.stageResultsEntryNonAdvancingMovieRows()));
        }
        return TraceRunReplayWalker.sourceTailVblankAtBoundary(
                source, observedBk2Cursor, observedVblank);
    }

    public OptionalInt levelDestinationTarget(
            int sourceIndex,
            TraceRunManifest.Segment source,
            TraceRunManifest.Segment destination,
            int rowsConsumed) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (!profile.alignsInterLevelVblank()
                || !"level".equals(source.kind())
                || !"level".equals(destination.kind())) {
            return OptionalInt.empty();
        }
        SourceAnchor sourceAnchor = sourceAnchor(sourceIndex, source);
        if (sourceAnchor == null) {
            return OptionalInt.empty();
        }
        int ticks = TraceRunReplayWalker.interLevelVblankBudget(
                source, destination, rowsConsumed,
                maskedLevelEntryLoss(profile, destination));
        return OptionalInt.of(Math.addExact(sourceAnchor.tailVblank(), ticks));
    }

    public OptionalInt uncomparedInteriorReturnTarget(
            int sourceIndex,
            TraceRunManifest.Segment source,
            TraceRunManifest.Segment destination,
            int destinationFramesConsumed) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (!profile.alignUncomparedInteriorReturnVblank()
                || !"level".equals(source.kind())
                || !"level".equals(destination.kind())) {
            return OptionalInt.empty();
        }
        SourceAnchor sourceAnchor = sourceAnchor(sourceIndex, source);
        if (sourceAnchor == null) {
            return OptionalInt.empty();
        }
        int ticks = TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                source, destination, destinationFramesConsumed,
                specialStageReturnLoss(profile, destination));
        return OptionalInt.of(Math.addExact(sourceAnchor.tailVblank(), ticks));
    }

    /**
     * The destination level entry's masked row count, taken from the game
     * profile's owned table. The identity comes from the manifest segment the
     * walker is already crossing into; no zone predicate lives here.
     */
    public static int maskedLevelEntryLoss(
            TracePlaybackProfile profile,
            TraceRunManifest.Segment destination) {
        Integer zone = destination.zoneId();
        Integer act = destination.act();
        if (zone == null || act == null) {
            return profile.interLevelNonAdvancingMovieRows();
        }
        return profile.interLevelNonAdvancingMovieRows(zone, act);
    }

    /**
     * The masked row count for a whole level -> uncompared interior -> level
     * round trip, taken from the game profile's owned table. Same identity
     * source as {@link #maskedLevelEntryLoss}: the manifest segment the walker
     * is already crossing into, never a zone predicate here.
     */
    public static int specialStageReturnLoss(
            TracePlaybackProfile profile,
            TraceRunManifest.Segment destination) {
        Integer zone = destination.zoneId();
        Integer act = destination.act();
        if (zone == null || act == null) {
            return profile.specialStageReturnNonAdvancingMovieRows(-1, -1);
        }
        return profile.specialStageReturnNonAdvancingMovieRows(zone, act);
    }

    private SourceAnchor sourceAnchor(
            int sourceIndex, TraceRunManifest.Segment source) {
        SourceAnchor sourceAnchor = levelSourceTails.get(sourceIndex);
        if (sourceAnchor != null && !sourceAnchor.segment().equals(source)) {
            throw new IllegalArgumentException(
                    "source segment does not match captured index " + sourceIndex);
        }
        return sourceAnchor;
    }

    private record SourceAnchor(
            TraceRunManifest.Segment segment, int tailVblank) {
    }
}
