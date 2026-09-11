package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfile;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManifestSegment;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.OwnerOrigin;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunReferenceRawAdapter.Header;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunReferenceRawAdapter.RawBoundary;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunReferenceRawAdapter.RawFrame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publication-inert proof that every S3K raw state can enter the canonical state inventory.
 *
 * <p>This intentionally stops before translating native ABI events into canonical requests,
 * services, lifecycles, and cutoff coordinates. Omitting those records would manufacture a
 * semantically incomplete capture, so the result carries the exact remaining dependencies and
 * cannot be passed to the shared capture store.
 */
public final class S3kCompleteRunReferencePreflight {
    private static final CompleteRunAudioProfile PROFILE = S3kCompleteRunAudioProfile.profile();

    private S3kCompleteRunReferencePreflight() { }

    public enum Dependency {
        RAW_EVENT_SEMANTICS,
        CUTOFF_SERVICE_COORDINATES,
        REFERENCE_RUNTIME_AUTHORITY,
        RUN_LOCAL_BK2,
        BASELINE_OWNERSHIP_PREPUBLICATION
    }

    /** Decoded state at an epoch boundary; it deliberately carries no ownership claim. */
    public record DecodedBoundaryCandidate(int absoluteFrame, NormalizedState state) {
        public DecodedBoundaryCandidate {
            if (absoluteFrame < 0) throw new IllegalArgumentException("boundary frame is negative");
            Objects.requireNonNull(state, "S3K decoded boundary state");
        }
    }

    public record Result(DecodedBoundaryCandidate boundaryCandidate, NormalizedState terminalState,
            long frameRows, long lagRows, long rawEvents, Map<Integer, Long> rawEventKinds,
            Map<String, Long> segmentRows, long gapRows, int cutoffActiveServices,
            int cutoffPendingDescendants, List<Dependency> dependencies) {
        public Result {
            Objects.requireNonNull(boundaryCandidate, "S3K preflight boundary candidate");
            Objects.requireNonNull(terminalState, "S3K preflight terminal state");
            rawEventKinds = Map.copyOf(Objects.requireNonNull(rawEventKinds, "raw event kinds"));
            segmentRows = Map.copyOf(Objects.requireNonNull(segmentRows, "segment rows"));
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            if (frameRows < 0 || lagRows < 0 || lagRows > frameRows || rawEvents < 0
                    || gapRows < 0 || gapRows > frameRows || cutoffActiveServices < 0
                    || cutoffPendingDescendants < 0) {
                throw new IllegalArgumentException("S3K reference-preflight counts are invalid");
            }
        }

        /** Publication remains closed until all explicit authority dependencies are removed. */
        public boolean canonicalRecordsReady() { return dependencies.isEmpty(); }

        /** Mirrors the shared comparator's exact role activity/live-owner coherence rule. */
        public boolean baselineOwnershipCoherent() {
            var owners = PROFILE.baselineRoleOwners();
            if (owners.size() != boundaryCandidate.state().roles().size()) return false;
            for (int index = 0; index < owners.size(); index++) {
                var role = boundaryCandidate.state().roles().get(index);
                var owner = owners.get(index);
                if (role.role() != owner.role()
                        || role.active() != (owner.owner().origin() != OwnerOrigin.NONE)) return false;
            }
            return true;
        }
    }

    /** Runs the exact full raw interval using the authenticated locked-on ROM catalog. */
    public static Result preflight(Path raw, Path rom) throws IOException {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom);
        return scan(raw, bytes -> {
            var snapshot = S3kCompleteRunStateDecoder.decode(bytes, catalog);
            return S3kCompleteRunStateNormalizer.normalizeReference(snapshot, catalog.assets());
        }, true);
    }

    static Result preflightPrefixForTesting(Path raw, StateProjector projector) throws IOException {
        return scan(raw, projector, false);
    }

    private static Result scan(Path raw, StateProjector projector, boolean full) throws IOException {
        var sink = new PreflightSink(projector);
        if (full) S3kCompleteRunReferenceRawAdapter.scan(raw, sink);
        else S3kCompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink);
        return sink.result();
    }

    static String segmentAtForTesting(int row) { return segmentAt(row); }

    private static String segmentAt(int row) {
        for (ManifestSegment segment : PROFILE.fixture().segments()) {
            if (row < segment.firstFrame()) return null;
            if (row < segment.exclusiveEnd()) return segment.id();
        }
        return null;
    }

    @FunctionalInterface
    interface StateProjector {
        NormalizedState project(byte[] rawState);
    }

    private static final class PreflightSink implements S3kCompleteRunReferenceRawAdapter.Sink {
        private final StateProjector projector;
        private final Map<Integer, Long> eventKinds = new LinkedHashMap<>();
        private final Map<String, Long> segmentRows = new LinkedHashMap<>();
        private Header header;
        private DecodedBoundaryCandidate baselineCandidate;
        private NormalizedState terminalState;
        private long frames;
        private long lagRows;
        private long events;
        private long gaps;
        private int cutoffActive;
        private int cutoffPending;
        private boolean begun;
        private boolean committed;

        private PreflightSink(StateProjector value) {
            projector = Objects.requireNonNull(value, "S3K state projector");
        }

        @Override public void begin() {
            if (begun) throw new IllegalStateException("S3K reference preflight already began");
            begun = true;
        }

        @Override public void header(Header value) { header = Objects.requireNonNull(value, "raw header"); }

        @Override
        public void baseline(RawBoundary value) {
            NormalizedState state = project(value.driverState());
            baselineCandidate = new DecodedBoundaryCandidate(value.row(), state);
        }

        @Override
        public void frame(RawFrame value) {
            NormalizedState state = project(value.driverState());
            // Construction and profile validation are the proof; retaining 433,607 expanded
            // normalized states would violate the bounded streaming contract.
            new ValidatedRow(value.row(), segmentAt(value.row()), value.lag(), state,
                    value.events().size());
            frames = Math.addExact(frames, 1);
            if (value.lag()) lagRows = Math.addExact(lagRows, 1);
            events = Math.addExact(events, value.events().size());
            for (var event : value.events()) eventKinds.merge(event.kind(), 1L, Math::addExact);
            String segment = segmentAt(value.row());
            if (segment == null) gaps = Math.addExact(gaps, 1);
            else segmentRows.merge(segment, 1L, Math::addExact);
        }

        @Override
        public void cutoff(RawBoundary value) {
            terminalState = project(value.driverState());
            cutoffActive = value.activeServices().size();
            cutoffPending = value.pendingDescendants().size();
        }

        @Override public void commit() { committed = true; }

        @Override
        public void abort() {
            header = null;
            baselineCandidate = null;
            terminalState = null;
            eventKinds.clear();
            segmentRows.clear();
            frames = lagRows = events = gaps = 0;
            cutoffActive = cutoffPending = 0;
            committed = false;
        }

        private NormalizedState project(byte[] raw) {
            NormalizedState state = Objects.requireNonNull(projector.project(raw),
                    "S3K projected state");
            PROFILE.validateState(state);
            return state;
        }

        private Result result() {
            if (!committed || header == null || baselineCandidate == null || terminalState == null) {
                throw new IllegalStateException("S3K reference preflight did not commit");
            }
            return new Result(baselineCandidate, terminalState, frames, lagRows, events, eventKinds,
                    segmentRows, gaps, cutoffActive, cutoffPending, List.of(
                            Dependency.RAW_EVENT_SEMANTICS,
                            Dependency.CUTOFF_SERVICE_COORDINATES,
                            Dependency.REFERENCE_RUNTIME_AUTHORITY,
                            Dependency.RUN_LOCAL_BK2,
                            Dependency.BASELINE_OWNERSHIP_PREPUBLICATION));
        }
    }

    private record ValidatedRow(int row, String segment, boolean lag,
            NormalizedState state, int rawEventCount) {
        private ValidatedRow {
            if (row < PROFILE.fixture().firstFrame() || row >= PROFILE.fixture().exclusiveEnd()
                    || rawEventCount < 0) {
                throw new IllegalArgumentException("S3K canonical preflight row is outside the fixture");
            }
            Objects.requireNonNull(state, "S3K canonical preflight row state");
        }
    }
}
