package com.openggf.tools.audio.completerun;

import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;

import com.openggf.tools.audio.completerun.CompleteRunAudioReport.Context;
import com.openggf.tools.audio.completerun.CompleteRunAudioReport.Kind;
import com.openggf.tools.audio.completerun.CompleteRunAudioReport.RecordView;
import com.openggf.tools.audio.completerun.CompleteRunAudioReport.Side;
import com.openggf.tools.audio.completerun.CompleteRunAudioReport.SourceIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Validation-first, two-pass, no-realignment comparator for canonical complete-run captures. */
public final class CompleteRunAudioComparator {
    private static final int CONTEXT_LIMIT = 8;
    private static final FileIdentityProvider PLATFORM_FILE_IDENTITIES = (path, attributes) -> {
        Object key = attributes.fileKey();
        if (key == null || key.toString().isBlank()) {
            throw new PublicationIdentityUnavailableException(
                    "filesystem publication identity is unavailable");
        }
        return key.toString();
    };

    private CompleteRunAudioComparator() {
    }

    /** Performs the same strict first-pass validation used by comparison. */
    public static void validate(Path capture, ProducerKind producerKind) throws ValidationException {
        Side side = producerKind == ProducerKind.REFERENCE ? Side.REFERENCE : Side.ENGINE;
        validate(new CompleteRunAudioCaptureStore(), capture, side, producerKind, PLATFORM_FILE_IDENTITIES);
    }

    /** Also binds orchestration to the exact requested profile rather than producer-selected metadata. */
    public static void validate(Path capture, ProducerKind producerKind, String profileId)
            throws ValidationException {
        Side side = producerKind == ProducerKind.REFERENCE ? Side.REFERENCE : Side.ENGINE;
        // The caller-selected binding is authority. Reject it before opening or interpreting any
        // attacker-controlled capture, including one that names a different pinned profile.
        try {
            CompleteRunAudioProducerRegistry.requirePinned(profileId, producerKind);
        } catch (CompleteRunAudioProducerRegistry.ProducerUnavailableException unavailable) {
            throw new ValidationException(ValidationException.Kind.PRODUCER_UNAVAILABLE, side,
                    unavailable.getMessage(), unavailable);
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException(ValidationException.Kind.PROFILE_UNKNOWN, side,
                    "requested profile is not registered", unknown);
        }
        Snapshot snapshot = validate(new CompleteRunAudioCaptureStore(), capture, side, producerKind,
                PLATFORM_FILE_IDENTITIES);
        if (!snapshot.metadata.profileId().equals(profileId)) {
            throw new ValidationException(ValidationException.Kind.METADATA_PROFILE_MISMATCH, side,
                    "capture profile does not match the orchestrator request");
        }
    }

    public static CompleteRunAudioReport compare(Path reference, Path engine) {
        return compare(reference, engine, () -> { }, PLATFORM_FILE_IDENTITIES);
    }

    /** Package-visible seam exercises real source replacement between the validation and comparison passes. */
    static CompleteRunAudioReport compare(Path reference, Path engine, PassBoundary boundary) {
        return compare(reference, engine, boundary, PLATFORM_FILE_IDENTITIES);
    }

    /** Package-visible seam proves that unavailable filesystem identities fail closed. */
    static CompleteRunAudioReport compare(Path reference, Path engine, PassBoundary boundary,
            FileIdentityProvider fileIdentities) {
        Objects.requireNonNull(reference, "reference capture");
        Objects.requireNonNull(engine, "engine capture");
        Objects.requireNonNull(boundary, "pass boundary");
        Objects.requireNonNull(fileIdentities, "file identity provider");
        Path referenceSource = reference.toAbsolutePath().normalize();
        Path engineSource = engine.toAbsolutePath().normalize();
        CompleteRunAudioCaptureStore store = new CompleteRunAudioCaptureStore();
        Snapshot referenceSnapshot = null;
        Snapshot engineSnapshot = null;
        try {
            referenceSnapshot = validate(store, referenceSource, Side.REFERENCE, ProducerKind.REFERENCE,
                    fileIdentities);
            engineSnapshot = validate(store, engineSource, Side.ENGINE, ProducerKind.OPENGGF,
                    fileIdentities);
            boundary.run();
            return compareValidated(store, referenceSnapshot, engineSnapshot, fileIdentities);
        } catch (ValidationException failure) {
            return failure(referenceSnapshot, engineSnapshot, failure, referenceSource, engineSource);
        } catch (IOException failure) {
            return failure(referenceSnapshot, engineSnapshot,
                    new ValidationException(ValidationException.Kind.IO_FAILURE, Side.REFERENCE,
                            "between-pass action failed", failure), referenceSource, engineSource);
        }
    }

    @FunctionalInterface
    interface PassBoundary {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface FileIdentityProvider {
        String fileKey(Path path, BasicFileAttributes attributes) throws IOException;
    }

    /** Package-visible bounded-state evidence for adversarial semantic streams. */
    record ValidationDiagnostics(int peakPendingRequests, int terminalPendingRequests,
            int peakSavedOwners, int liveRoleOwners, long completedRequests) { }

    static ValidationDiagnostics validateSemanticsForDiagnostics(Metadata metadata,
            CompleteRunAudioProfile profile, Side side, java.util.Iterator<CompleteRunAudioTrace.Record> records)
            throws ValidationException {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(records, "records");
        try {
            metadata.validateRuntimeProfile(profile);
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.RUNTIME_IDENTITY_INVALID, side,
                    "diagnostic stream runtime identity does not match its profile", failure);
        }
        try {
            metadata.validateFixtureProfile(profile);
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.METADATA_PROFILE_MISMATCH, side,
                    "diagnostic stream metadata does not match its profile", failure);
        }
        StreamValidator validator = new StreamValidator(metadata, profile, side);
        while (records.hasNext()) validator.accept(records.next());
        validator.finish();
        return validator.diagnostics();
    }

    /** Typed validation categories are the sole classification source for capture failures. */
    public static final class ValidationException extends Exception {
        public enum Kind {
            IO_FAILURE,
            CAPTURE_INVALID,
            PROFILE_UNKNOWN,
            PRODUCER_UNAVAILABLE,
            METADATA_PROFILE_MISMATCH,
            RUNTIME_IDENTITY_INVALID,
            PRODUCER_KIND_MISMATCH,
            ORDINAL_INVALID,
            STATE_INVALID,
            REQUEST_IDENTITY_INVALID,
            ROLE_INVALID,
            DECISION_REFERENCE_INVALID,
            RESOLUTION_INVALID,
            OWNER_INVALID,
            OWNERSHIP_TRANSITION_INVALID,
            PENDING_CAPACITY_INVALID,
            PENDING_UNRESOLVED,
            RESTORE_STACK_INVALID,
            SEGMENT_INVALID,
            LIFECYCLE_INVALID,
            PUBLICATION_IDENTITY_UNAVAILABLE,
            SOURCE_REPLACED
        }

        private final Kind kind;
        private final Side side;

        ValidationException(Kind kind, Side side, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "validation kind");
            this.side = Objects.requireNonNull(side, "validation side");
        }

        ValidationException(Kind kind, Side side, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "validation kind");
            this.side = Objects.requireNonNull(side, "validation side");
        }

        public Kind kind() { return kind; }
        public Side side() { return side; }
    }

    private static Snapshot validate(CompleteRunAudioCaptureStore store, Path source, Side side,
            ProducerKind expectedProducer, FileIdentityProvider fileIdentities) throws ValidationException {
        Path normalized = source.toAbsolutePath().normalize();
        try {
            PublicationIdentity publication = PublicationIdentity.capture(normalized, fileIdentities);
            Metadata metadata;
            Terminal terminal = null;
            try (CompleteRunAudioCaptureStore.Reader reader = store.read(publication.realSource())) {
                metadata = reader.metadata();
                CompleteRunAudioProfile profile = validateMetadata(metadata, expectedProducer, side);
                StreamValidator validator = new StreamValidator(metadata, profile, side);
                while (reader.hasNext()) {
                    CompleteRunAudioTrace.Record record = reader.next();
                    validator.accept(record);
                    if (record instanceof Terminal value) terminal = value;
                }
                validator.finish();
            }
            if (!publication.equals(PublicationIdentity.capture(normalized, fileIdentities))) {
                throw new ValidationException(ValidationException.Kind.SOURCE_REPLACED, side,
                        "capture publication changed during validation pass");
            }
            if (terminal == null) {
                throw new ValidationException(ValidationException.Kind.CAPTURE_INVALID, side,
                        "validated capture has no terminal record");
            }
            return new Snapshot(normalized, metadata, terminal.rootDigest(), terminal.semanticDigest(), publication,
                    sha256(CompleteRunAudioJson.writeMetadata(metadata).getBytes(StandardCharsets.UTF_8)));
        } catch (ValidationException failure) {
            throw failure;
        } catch (PublicationIdentityUnavailableException failure) {
            throw new ValidationException(ValidationException.Kind.PUBLICATION_IDENTITY_UNAVAILABLE, side,
                    failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new ValidationException(ValidationException.Kind.IO_FAILURE, side,
                    "capture could not be read", failure);
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.CAPTURE_INVALID, side,
                    "capture failed strict store validation", failure);
        }
    }

    private static CompleteRunAudioProfile validateMetadata(Metadata metadata,
            ProducerKind expectedProducer, Side side) throws ValidationException {
        if (metadata.producerKind() != expectedProducer) {
            throw new ValidationException(ValidationException.Kind.PRODUCER_KIND_MISMATCH, side,
                    "capture is assigned to the wrong comparison side");
        }
        CompleteRunAudioProfile profile;
        try {
            profile = CompleteRunAudioProfiles.require(metadata.profileId());
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.PROFILE_UNKNOWN, side,
                    "capture profile is not registered", failure);
        }
        try {
            metadata.validateRuntimeProfile(profile);
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.RUNTIME_IDENTITY_INVALID, side,
                    "capture runtime identity is not the exact registered profile identity", failure);
        }
        try {
            metadata.validateFixtureProfile(profile);
        } catch (RuntimeException failure) {
            throw new ValidationException(ValidationException.Kind.METADATA_PROFILE_MISMATCH, side,
                    "capture fixture metadata is not the exact registered profile identity", failure);
        }
        return profile;
    }

    private static CompleteRunAudioReport compareValidated(CompleteRunAudioCaptureStore store,
            Snapshot reference, Snapshot engine, FileIdentityProvider fileIdentities)
            throws ValidationException {
        Difference first = metadataDifference(reference.metadata, engine.metadata);
        ContextCollector context = new ContextCollector(first != null);
        try (PassStream left = PassStream.open(store, reference, Side.REFERENCE, ProducerKind.REFERENCE,
                    fileIdentities);
                PassStream right = PassStream.open(store, engine, Side.ENGINE, ProducerKind.OPENGGF,
                    fileIdentities)) {
            while (true) {
                ComparisonLayerInventory layers = reference.metadata.comparisonLayerInventory();
                Entry expected = nextComparable(left, layers);
                Entry actual = nextComparable(right, layers);
                if (expected == null && actual == null) break;
                RecordView expectedView = view(expected);
                RecordView actualView = view(actual);
                if (first == null) {
                    Difference found = difference(expected == null ? null : expected.record,
                            actual == null ? null : actual.record, layers);
                    if (found == null) {
                        context.before(expectedView, actualView);
                    } else {
                        first = found;
                        context.mismatch(expectedView, actualView);
                    }
                } else {
                    context.after(expectedView, actualView);
                }
            }
            left.verifyStable(fileIdentities);
            right.verifyStable(fileIdentities);
        } catch (ValidationException failure) {
            throw failure;
        }
        SourceIdentity referenceIdentity = reference.identity(Side.REFERENCE);
        SourceIdentity engineIdentity = engine.identity(Side.ENGINE);
        if (first == null) {
            Context empty = emptyContext();
            Kind kind = reference.metadata.comparisonLayerInventory().allLayersCompared()
                    ? Kind.MATCH : Kind.REFERENCE_LIMITATION;
            return new CompleteRunAudioReport(kind, referenceIdentity, engineIdentity, -1,
                    null, null, null, empty, empty, null, null, null, null);
        }
        return new CompleteRunAudioReport(first.kind, referenceIdentity, engineIdentity, first.frame,
                first.location, first.referenceValue, first.engineValue, context.reference(),
                context.engine(), null, null, null, null);
    }

    private static CompleteRunAudioReport failure(Snapshot reference, Snapshot engine,
            ValidationException failure, Path referenceSource, Path engineSource) {
        Context empty = emptyContext();
        String source = failure.side() == Side.REFERENCE ? "reference" : "engine";
        return new CompleteRunAudioReport(Kind.CAPTURE_FAILURE,
                reference == null ? null : reference.identity(Side.REFERENCE),
                engine == null ? null : engine.identity(Side.ENGINE), -1, null, null, null,
                empty, empty, failure.side(), source, failure.kind(), failure.getMessage());
    }

    private static Context emptyContext() {
        return new Context(List.of(), null, List.of());
    }

    private static Entry nextComparable(PassStream stream, ComparisonLayerInventory layers)
            throws ValidationException {
        Entry entry;
        while ((entry = stream.next()) != null) {
            if (!(entry.record instanceof Lifecycle lifecycle)
                    || layers.isCompared(ComparisonLayer.LIFECYCLE)
                    || layers.isCompared(ComparisonLayer.OWNERSHIP)
                            && lifecycle.ownershipTransitions() != null
                            && !lifecycle.ownershipTransitions().isEmpty()) return entry;
        }
        return null;
    }

    private static Difference metadataDifference(Metadata reference, Metadata engine) {
        if (!reference.schema().equals(engine.schema())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.schema", reference.schema(), engine.schema());
        }
        if (!reference.profileId().equals(engine.profileId())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.profile_id",
                    reference.profileId(), engine.profileId());
        }
        if (!reference.fixture().equals(engine.fixture())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.fixture",
                    reference.fixture(), engine.fixture());
        }
        if (!reference.chunkPolicy().equals(engine.chunkPolicy())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.chunk_policy",
                    reference.chunkPolicy(), engine.chunkPolicy());
        }
        if (!reference.hardwareRoles().equals(engine.hardwareRoles())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.hardware_roles",
                    reference.hardwareRoles(), engine.hardwareRoles());
        }
        if (!reference.stateInventory().equals(engine.stateInventory())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.state_inventory",
                    reference.stateInventory(), engine.stateInventory());
        }
        if (!reference.comparisonLayerInventory().equals(engine.comparisonLayerInventory())) {
            return diff(Kind.METADATA_IDENTITY, -1, "metadata.comparison_layer_inventory",
                    reference.comparisonLayerInventory(), engine.comparisonLayerInventory());
        }
        return null;
    }

    /** Pure record classifier used by the streaming pass and defensive impossible-state tests. */
    static Difference difference(CompleteRunAudioTrace.Record reference,
            CompleteRunAudioTrace.Record engine) {
        return difference(reference, engine, ComparisonLayerInventory.allCompared());
    }

    static Difference difference(CompleteRunAudioTrace.Record reference,
            CompleteRunAudioTrace.Record engine, ComparisonLayerInventory layers) {
        Objects.requireNonNull(layers, "comparison layer inventory");
        if (reference == null || engine == null) return missingRecord(reference, engine);
        if (reference instanceof Baseline expected && engine instanceof Baseline actual) {
            if (expected.absoluteFrame() != actual.absoluteFrame()) {
                return frameCoordinate(expected.absoluteFrame(), actual.absoluteFrame(), "baseline.absolute_frame");
            }
            if (layers.isCompared(ComparisonLayer.STATE)) {
                Difference state = stateDifference(expected.state(), actual.state(), expected.absoluteFrame(),
                        "baseline.state");
                if (state != null) return state;
            }
            if (layers.isCompared(ComparisonLayer.OWNERSHIP)
                    && !expected.roleOwners().equals(actual.roleOwners())) {
                return diff(Kind.OWNER, expected.absoluteFrame(), "baseline.role_owners",
                        expected.roleOwners(), actual.roleOwners());
            }
            if (layers.isCompared(ComparisonLayer.CUTOFF_FRONTIER)
                    && (!cutoffServicePayloads(expected.frontier().activeStack()).equals(
                                cutoffServicePayloads(actual.frontier().activeStack()))
                            || !cutoffServicePayloads(expected.frontier().pendingDescendants()).equals(
                                cutoffServicePayloads(actual.frontier().pendingDescendants())))) {
                return diff(Kind.CUTOFF_FRONTIER_VALUE, expected.absoluteFrame(),
                        "baseline.frontier.services", expected.frontier(), actual.frontier());
            }
            if (layers.isCompared(ComparisonLayer.BOUNDARY_CHIP_STATE)
                    && (!expected.frontier().rawChipEvents().equals(actual.frontier().rawChipEvents())
                            || !Objects.equals(expected.frontier().ymPort0Latch(), actual.frontier().ymPort0Latch())
                            || !Objects.equals(expected.frontier().ymPort1Latch(), actual.frontier().ymPort1Latch()))) {
                return diff(Kind.CHIP_EVENT_VALUE, expected.absoluteFrame(), "baseline.frontier.chip_events",
                        new CutoffChipPayload(expected.frontier().rawChipEvents(),
                                expected.frontier().ymPort0Latch(), expected.frontier().ymPort1Latch()),
                        new CutoffChipPayload(actual.frontier().rawChipEvents(),
                                actual.frontier().ymPort0Latch(), actual.frontier().ymPort1Latch()));
            }
            return null;
        }
        if (reference instanceof Frame expected && engine instanceof Frame actual) {
            return frameDifference(expected, actual, layers);
        }
        if (reference instanceof Lifecycle expected && engine instanceof Lifecycle actual) {
            int frame = Math.min(expected.absoluteFrame(), actual.absoluteFrame());
            String location = "lifecycle[" + expected.ordinal() + "]";
            if (layers.isCompared(ComparisonLayer.LIFECYCLE)) {
                if (expected.ordinal() != actual.ordinal()) {
                    return diff(Kind.LIFECYCLE_ORDER, frame,
                            "lifecycle.ordinal", expected.ordinal(), actual.ordinal());
                }
                if (expected.absoluteFrame() != actual.absoluteFrame()) {
                    return diff(Kind.LIFECYCLE_VALUE, frame, location + ".absolute_frame",
                            expected.absoluteFrame(), actual.absoluteFrame());
                }
                if (!expected.kind().equals(actual.kind())) {
                    return diff(Kind.LIFECYCLE_VALUE, frame, location + ".kind",
                            expected.kind(), actual.kind());
                }
                if (!expected.details().equals(actual.details())) {
                    return diff(Kind.LIFECYCLE_VALUE, frame,
                            location + ".details", expected.details(), actual.details());
                }
            }
            if (layers.isCompared(ComparisonLayer.OWNERSHIP)) {
                return lifecycleOwnershipDifference(expected.ownershipTransitions(),
                        actual.ownershipTransitions(), frame, location + ".ownership_transitions");
            }
            return null;
        }
        if (reference instanceof CutoffFrontier expected && engine instanceof CutoffFrontier actual) {
            if (layers.isCompared(ComparisonLayer.CUTOFF_FRONTIER)
                    && (!cutoffServicePayloads(expected.activeStack()).equals(
                                cutoffServicePayloads(actual.activeStack()))
                            || !cutoffServicePayloads(expected.pendingDescendants()).equals(
                                cutoffServicePayloads(actual.pendingDescendants())))) {
                return diff(Kind.CUTOFF_FRONTIER_VALUE, -1, "cutoff_frontier.services",
                        cutoffServicePayloads(expected.activeStack()), cutoffServicePayloads(actual.activeStack()));
            }
            if (layers.isCompared(ComparisonLayer.BOUNDARY_CHIP_STATE)
                    && (!expected.rawChipEvents().equals(actual.rawChipEvents())
                            || !Objects.equals(expected.ymPort0Latch(), actual.ymPort0Latch())
                            || !Objects.equals(expected.ymPort1Latch(), actual.ymPort1Latch()))) {
                return diff(Kind.CHIP_EVENT_VALUE, -1, "cutoff_frontier.chip_events",
                        new CutoffChipPayload(expected.rawChipEvents(), expected.ymPort0Latch(),
                                expected.ymPort1Latch()),
                        new CutoffChipPayload(actual.rawChipEvents(), actual.ymPort0Latch(),
                                actual.ymPort1Latch()));
            }
            if (layers.isCompared(ComparisonLayer.STATE)) {
                return stateDifference(expected.terminalState(), actual.terminalState(), -1,
                        "cutoff_frontier.terminal_state");
            }
            return null;
        }
        if (reference instanceof Terminal expected && engine instanceof Terminal actual) {
            if (expected.exclusiveEnd() != actual.exclusiveEnd() || expected.frameCount() != actual.frameCount()
                    || layers.isCompared(ComparisonLayer.REQUESTS) && expected.requestCount() != actual.requestCount()
                    || layers.isCompared(ComparisonLayer.SERVICES) && expected.serviceCount() != actual.serviceCount()
                    || layers.isCompared(ComparisonLayer.DECISIONS) && expected.decisionCount() != actual.decisionCount()
                    || layers.isCompared(ComparisonLayer.FRAME_CHIP_EVENTS)
                            && (expected.ymCount() != actual.ymCount() || expected.psgCount() != actual.psgCount())
                    || layers.isCompared(ComparisonLayer.LIFECYCLE)
                            && expected.lifecycleCount() != actual.lifecycleCount()
                    || layers.isCompared(ComparisonLayer.CUTOFF_FRONTIER)
                            && (expected.cutoffActiveCount() != actual.cutoffActiveCount()
                                    || expected.cutoffPendingCount() != actual.cutoffPendingCount())) {
                return diff(Kind.TERMINAL_COUNT, Math.min(expected.exclusiveEnd(), actual.exclusiveEnd()),
                        "terminal.counts", expected.counts(), actual.counts());
            }
            if (layers.allLayersCompared() && !expected.semanticDigest().equals(actual.semanticDigest())) {
                return diff(Kind.TERMINAL_DIGEST, expected.exclusiveEnd(), "terminal.semantic_digest",
                        expected.semanticDigest(), actual.semanticDigest());
            }
            return null;
        }
        if (reference instanceof Lifecycle lifecycle) {
            if (!layers.isCompared(ComparisonLayer.LIFECYCLE)) return null;
            return diff(Kind.LIFECYCLE_MISSING, lifecycle.absoluteFrame(), "lifecycle",
                    lifecycle, engine);
        }
        if (engine instanceof Lifecycle lifecycle) {
            if (!layers.isCompared(ComparisonLayer.LIFECYCLE)) return null;
            return diff(Kind.LIFECYCLE_EXTRA, lifecycle.absoluteFrame(), "lifecycle",
                    reference, lifecycle);
        }
        if (reference instanceof Frame frame) {
            return diff(Kind.FRAME_MISSING, frame.absoluteFrame(), "frame", frame, engine);
        }
        if (engine instanceof Frame frame) {
            return diff(Kind.FRAME_EXTRA, frame.absoluteFrame(), "frame", reference, frame);
        }
        return diff(Kind.RECORD_SHAPE, recordFrame(reference, engine), "record.type",
                reference.getClass().getSimpleName(), engine.getClass().getSimpleName());
    }

    private static String baselineFrontierProjection(Baseline baseline) throws IOException {
        return CompleteRunAudioJson.writeSemanticRecord(new Baseline(baseline.absoluteFrame(),
                new NormalizedState(List.of(), List.of()), List.of(new RoleOwner(HardwareRole.FM1,
                        new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1))), baseline.frontier()));
    }

    private static Difference lifecycleOwnershipDifference(List<LifecycleOwnership> reference,
            List<LifecycleOwnership> engine, int frame, String location) {
        if (reference.size() != engine.size()) {
            return diff(Kind.LIFECYCLE_VALUE, frame, location + ".size",
                    reference.size(), engine.size());
        }
        for (int index = 0; index < reference.size(); index++) {
            LifecycleOwnership expected = reference.get(index);
            LifecycleOwnership actual = engine.get(index);
            String item = location + "[" + index + "]";
            if (expected.role() != actual.role()) {
                return diff(Kind.LIFECYCLE_VALUE, frame, item + ".role",
                        expected.role(), actual.role());
            }
            if (!expected.displacedOwner().equals(actual.displacedOwner())) {
                return diff(Kind.OWNER, frame, item + ".displaced_owner",
                        expected.displacedOwner(), actual.displacedOwner());
            }
            if (!expected.finalOwner().equals(actual.finalOwner())) {
                return diff(Kind.OWNER, frame, item + ".final_owner",
                        expected.finalOwner(), actual.finalOwner());
            }
        }
        return null;
    }

    private static Difference missingRecord(CompleteRunAudioTrace.Record reference,
            CompleteRunAudioTrace.Record engine) {
        CompleteRunAudioTrace.Record present = reference == null ? engine : reference;
        boolean extra = reference == null;
        if (present instanceof Frame frame) {
            return diff(extra ? Kind.FRAME_EXTRA : Kind.FRAME_MISSING, frame.absoluteFrame(), "frame",
                    reference, engine);
        }
        if (present instanceof Lifecycle lifecycle) {
            return diff(extra ? Kind.LIFECYCLE_EXTRA : Kind.LIFECYCLE_MISSING, lifecycle.absoluteFrame(),
                    "lifecycle", reference, engine);
        }
        return diff(Kind.RECORD_SHAPE, recordFrame(reference, engine), "record.eof", reference, engine);
    }

    private static Difference frameDifference(Frame reference, Frame engine, ComparisonLayerInventory layers) {
        if (reference.absoluteFrame() != engine.absoluteFrame()) {
            return frameCoordinate(reference.absoluteFrame(), engine.absoluteFrame(), "frame.absolute_frame");
        }
        int frame = reference.absoluteFrame();
        if (!Objects.equals(reference.segment(), engine.segment())) {
            return diff(Kind.FRAME_VALUE, frame, "frame.coordinates",
                    new FrameCoordinatesPayload(reference.segment(), reference.lag()),
                    new FrameCoordinatesPayload(engine.segment(), engine.lag()));
        }
        if (layers.isCompared(ComparisonLayer.ROW_LAG)
                && !Objects.equals(reference.lag(), engine.lag())) {
            return diff(Kind.FRAME_VALUE, frame, "frame.lag", reference.lag(), engine.lag());
        }
        if (layers.isCompared(ComparisonLayer.REQUESTS)) {
            Difference requests = requestDifference(reference.requests(), engine.requests(), frame);
            if (requests != null) return requests;
        }
        if (layers.isCompared(ComparisonLayer.DECISIONS)) {
            Difference decisions = decisionDifference(reference.decisions(), engine.decisions(), frame,
                    layers.isCompared(ComparisonLayer.OWNERSHIP));
            if (decisions != null) return decisions;
        } else if (layers.isCompared(ComparisonLayer.OWNERSHIP)) {
            Difference ownership = decisionOwnershipDifference(
                    reference.decisions(), engine.decisions(), frame);
            if (ownership != null) return ownership;
        }
        if (layers.isCompared(ComparisonLayer.SERVICES)) {
            Difference services = serviceDifference(reference.services(), engine.services(), frame);
            if (services != null) return services;
        }
        if (layers.isCompared(ComparisonLayer.STATE)) {
            Difference state = stateDifference(reference.postRowState(), engine.postRowState(), frame,
                    "frame.post_row_state");
            if (state != null) return state;
        }
        return layers.isCompared(ComparisonLayer.FRAME_CHIP_EVENTS)
                ? chipDifference(reference.chipEvents(), engine.chipEvents(), frame,
                        "frame.chip_events")
                : null;
    }

    private static Difference frameCoordinate(int reference, int engine, String location) {
        return diff(reference < engine ? Kind.FRAME_MISSING : Kind.FRAME_EXTRA,
                Math.min(reference, engine), location, reference, engine);
    }

    private static Difference requestDifference(List<Request> reference, List<Request> engine, int frame) {
        if (reference.size() != engine.size()) {
            int index = commonPrefix(reference, engine);
            return diff(reference.size() > engine.size() ? Kind.REQUEST_MISSING : Kind.REQUEST_EXTRA,
                    frame, "frame.requests[" + index + "]", at(reference, index), at(engine, index));
        }
        if (isPermutation(reference, engine, CompleteRunAudioComparator::requestPayload)) {
            return diff(Kind.REQUEST_ORDER, frame, "frame.requests", reference, engine);
        }
        for (int index = 0; index < reference.size(); index++) {
            Request expected = reference.get(index);
            Request actual = engine.get(index);
            if (expected.ordinal() != actual.ordinal()) {
                return diff(Kind.REQUEST_ORDER, frame, "frame.requests[" + index + "].ordinal",
                        expected.ordinal(), actual.ordinal());
            }
            if (!requestPayload(expected).equals(requestPayload(actual))) {
                return diff(Kind.REQUEST_VALUE, frame, "frame.requests[" + index + "]", expected, actual);
            }
            if (!expected.equals(actual)) {
                return diff(Kind.REQUEST_VALUE, frame, "frame.requests[" + index + "]", expected, actual);
            }
        }
        return null;
    }

    private static Difference serviceDifference(List<DriverService> reference,
            List<DriverService> engine, int frame) {
        if (reference.size() != engine.size()) {
            int index = commonPrefix(reference, engine);
            return diff(reference.size() > engine.size() ? Kind.SERVICE_MISSING : Kind.SERVICE_EXTRA,
                    frame, "frame.services[" + index + "]", at(reference, index), at(engine, index));
        }
        if (isPermutation(reference, engine, CompleteRunAudioComparator::servicePayload)) {
            return diff(Kind.SERVICE_ORDER, frame, "frame.services", reference, engine);
        }
        for (int index = 0; index < Math.min(reference.size(), engine.size()); index++) {
            DriverService expected = reference.get(index);
            DriverService actual = engine.get(index);
            String location = "frame.services[" + index + "]";
            if (expected.ordinal() != actual.ordinal()) {
                return diff(Kind.SERVICE_ORDER, frame, location + ".ordinal",
                        expected.ordinal(), actual.ordinal());
            }
            if (!expected.kind().equals(actual.kind())) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".kind", expected.kind(), actual.kind());
            }
            if (expected.completion() != actual.completion()) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".completion",
                        expected.completion(), actual.completion());
            }
            if (!Objects.equals(expected.carriedBoundaryOrdinal(),
                    actual.carriedBoundaryOrdinal())) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".carried_boundary_ordinal",
                        expected.carriedBoundaryOrdinal(), actual.carriedBoundaryOrdinal());
            }
            if (!Objects.equals(expected.beginCoordinate(), actual.beginCoordinate())) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".begin_coordinate",
                        expected.beginCoordinate(), actual.beginCoordinate());
            }
            if (!Objects.equals(expected.endCoordinate(), actual.endCoordinate())) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".end_coordinate",
                        expected.endCoordinate(), actual.endCoordinate());
            }
            if (!expected.ancestry().equals(actual.ancestry())) {
                return diff(Kind.SERVICE_VALUE, frame, location + ".ancestry",
                        expected.ancestry(), actual.ancestry());
            }
        }
        return null;
    }

    private static List<CutoffServicePayload> cutoffServicePayloads(List<CutoffService> services) {
        return services.stream().map(service -> new CutoffServicePayload(
                service.parentFrame(), service.parentOrdinal(), service.depth(), service.kind(), service.state(),
                service.beginFrame(), service.beginOrdinal(), service.endFrame(), service.endOrdinal(),
                service.ancestry())).toList();
    }

    private static Difference decisionDifference(List<Decision> reference, List<Decision> engine,
            int frame, boolean compareOwnership) {
        if (reference.size() != engine.size()) {
            int index = commonPrefix(reference, engine);
            return diff(reference.size() > engine.size() ? Kind.DECISION_MISSING : Kind.DECISION_EXTRA,
                    frame, decisionLocation(reference, engine, index), at(reference, index), at(engine, index));
        }
        if (isPermutation(reference, engine,
                decision -> decisionOrderPayload(decision, compareOwnership))) {
            return diff(Kind.DECISION_ORDER, frame, "frame.decisions", reference, engine);
        }
        for (int index = 0; index < reference.size(); index++) {
            Decision expected = reference.get(index);
            Decision actual = engine.get(index);
            String item = decisionLocation(reference, engine, index);
            if (expected.requestOrdinal() != actual.requestOrdinal()) {
                return diff(Kind.DECISION_ORDER, frame, item + ".request_ordinal",
                        expected.requestOrdinal(), actual.requestOrdinal());
            }
            if (!Objects.equals(expected.priorityBefore(), actual.priorityBefore())
                    || !Objects.equals(expected.priorityAfter(), actual.priorityAfter())) {
                return diff(Kind.PRIORITY, frame, item + ".priority",
                        new PriorityPayload(expected.priorityBefore(), expected.priorityAfter()),
                        new PriorityPayload(actual.priorityBefore(), actual.priorityAfter()));
            }
            if (compareOwnership && expected.roleDecisions().size() == actual.roleDecisions().size()) {
                for (int role = 0; role < expected.roleDecisions().size(); role++) {
                    RoleDecision expectedRole = expected.roleDecisions().get(role);
                    RoleDecision actualRole = actual.roleDecisions().get(role);
                    if (!expectedRole.displacedOwner().equals(actualRole.displacedOwner())
                            || !expectedRole.finalOwner().equals(actualRole.finalOwner())) {
                        return diff(Kind.OWNER, frame, item + ".roles[" + role + "].owner",
                                expectedRole, actualRole);
                    }
                }
            }
            if (!decisionPayload(expected, compareOwnership).equals(decisionPayload(actual, compareOwnership))) {
                return diff(Kind.DECISION_VALUE, frame, item, expected, actual);
            }
        }
        return null;
    }

    private static Difference decisionOwnershipDifference(List<Decision> reference,
            List<Decision> engine, int frame) {
        List<RoleDecision> expectedRoles = reference.stream()
                .flatMap(decision -> decision.roleDecisions().stream()).toList();
        List<RoleDecision> actualRoles = engine.stream()
                .flatMap(decision -> decision.roleDecisions().stream()).toList();
        if (expectedRoles.size() != actualRoles.size()) {
            int index = commonPrefix(expectedRoles, actualRoles);
            return diff(Kind.OWNER, frame, "frame.decision_ownership[" + index + "]",
                    at(expectedRoles, index), at(actualRoles, index));
        }
        for (int index = 0; index < expectedRoles.size(); index++) {
            RoleDecision expected = expectedRoles.get(index);
            RoleDecision actual = actualRoles.get(index);
            if (!expected.equals(actual)) {
                return diff(Kind.OWNER, frame, "frame.decision_ownership[" + index + "]",
                        expected, actual);
            }
        }
        return null;
    }

    private static String decisionLocation(List<Decision> reference, List<Decision> engine,
            int index) {
        return "frame.decisions[" + index + "]";
    }

    private static Difference chipDifference(List<ChipEvent> reference, List<ChipEvent> engine,
            int frame, String location) {
        if (reference.size() != engine.size()) {
            int index = commonPrefix(reference, engine);
            return diff(reference.size() > engine.size() ? Kind.CHIP_EVENT_MISSING : Kind.CHIP_EVENT_EXTRA,
                    frame, location + "[" + index + "]", at(reference, index), at(engine, index));
        }
        if (isPermutation(reference, engine, CompleteRunAudioComparator::chipPayload)) {
            return diff(Kind.CHIP_EVENT_ORDER, frame, location, reference, engine);
        }
        for (int index = 0; index < reference.size(); index++) {
            ChipEvent expected = reference.get(index);
            ChipEvent actual = engine.get(index);
            if (expected.ordinal() != actual.ordinal()) {
                return diff(Kind.CHIP_EVENT_ORDER, frame, location + "[" + index + "].ordinal",
                        expected.ordinal(), actual.ordinal());
            }
            if (!chipPayload(expected).equals(chipPayload(actual))) {
                return diff(Kind.CHIP_EVENT_VALUE, frame, location + "[" + index + "]", expected, actual);
            }
            if (!expected.equals(actual)) {
                return diff(Kind.CHIP_EVENT_VALUE, frame, location + "[" + index + "]", expected, actual);
            }
        }
        return null;
    }

    private static Difference stateDifference(NormalizedState reference, NormalizedState engine,
            int frame, String location) {
        Difference global = fieldsDifference(reference.fields(), engine.fields(), frame, location + ".fields");
        if (global != null) return global;
        if (reference.roles().size() != engine.roles().size()) {
            return diff(Kind.STATE_FIELD_NAME, frame, location + ".roles.size",
                    reference.roles().size(), engine.roles().size());
        }
        for (int index = 0; index < reference.roles().size(); index++) {
            RoleState expected = reference.roles().get(index);
            RoleState actual = engine.roles().get(index);
            String role = location + ".roles[" + index + "]";
            if (expected.role() != actual.role()) {
                return diff(Kind.STATE_FIELD_NAME, frame, role + ".role", expected.role(), actual.role());
            }
            if (expected.active() != actual.active()) {
                return diff(Kind.STATE_FIELD_VALUE, frame, role + ".active", expected.active(), actual.active());
            }
            Difference fields = fieldsDifference(expected.fields(), actual.fields(), frame, role + ".fields");
            if (fields != null) return fields;
        }
        return reference.equals(engine) ? null
                : diff(Kind.STATE_FIELD_VALUE, frame, location, reference, engine);
    }

    private static Difference fieldsDifference(List<StateField> reference, List<StateField> engine,
            int frame, String location) {
        if (reference.size() != engine.size()) {
            return diff(Kind.STATE_FIELD_NAME, frame, location + ".size", reference.size(), engine.size());
        }
        for (int index = 0; index < reference.size(); index++) {
            StateField expected = reference.get(index);
            StateField actual = engine.get(index);
            if (!expected.name().equals(actual.name())) {
                return diff(Kind.STATE_FIELD_NAME, frame, location + "[" + index + "].name",
                        expected.name(), actual.name());
            }
            if (!expected.value().equals(actual.value())) {
                return diff(Kind.STATE_FIELD_VALUE, frame, location + "[" + index + "].value",
                        expected.value(), actual.value());
            }
        }
        return null;
    }

    private static RequestPayload requestPayload(Request request) {
        return new RequestPayload(request.ownerClass(), request.contentKey(), request.nativeId(),
                request.queueSource(), request.queueSlot());
    }

    private static ServicePayload servicePayload(DriverService service) {
        return new ServicePayload(service.kind(), service.completion(), service.carriedBoundaryOrdinal(),
                service.beginCoordinate(), service.endCoordinate(), service.ancestry());
    }

    private static DecisionPayload decisionPayload(Decision decision, boolean includeOwnership) {
        return new DecisionPayload(decision.resolvedNativeId(), decision.resolvedContentKey(),
                decision.accepted(), decision.reason(), decision.priorityBefore(), decision.priorityAfter(),
                decision.requestedRoles(), includeOwnership ? decision.roleDecisions() : List.of());
    }

    private static DecisionOrderPayload decisionOrderPayload(Decision decision, boolean includeOwnership) {
        return new DecisionOrderPayload(decision.resolvedNativeId(), decision.resolvedContentKey(),
                decision.accepted(), decision.reason(), decision.priorityBefore(), decision.priorityAfter(),
                decision.requestedRoles(), includeOwnership ? decision.roleDecisions().stream()
                        .map(role -> new RoleOrderPayload(role.role(), ownerPayload(role.displacedOwner()),
                                ownerPayload(role.finalOwner())))
                        .toList() : List.of());
    }

    private static OwnerPayload ownerPayload(OwnerRef owner) {
        return new OwnerPayload(owner.ownerClass(), owner.contentKey(), owner.nativeId(), owner.origin());
    }

    private static Object chipPayload(ChipEvent event) {
        if (event instanceof YmWrite ym) return new YmPayload(ym.port(), ym.register(), ym.value());
        PsgWrite psg = (PsgWrite) event;
        return new PsgPayload(psg.value());
    }

    private static <T, P> boolean isPermutation(List<T> reference, List<T> engine,
            Function<T, P> payload) {
        if (reference.size() < 2 || reference.size() != engine.size()) return false;
        List<P> expected = reference.stream().map(payload).toList();
        List<P> actual = engine.stream().map(payload).toList();
        return !expected.equals(actual) && frequencies(expected).equals(frequencies(actual));
    }

    private static <T> Map<T, Integer> frequencies(List<T> values) {
        Map<T, Integer> counts = new HashMap<>();
        for (T value : values) counts.merge(value, 1, Integer::sum);
        return counts;
    }

    private static int commonPrefix(List<?> reference, List<?> engine) {
        int limit = Math.min(reference.size(), engine.size());
        int index = 0;
        while (index < limit && reference.get(index).equals(engine.get(index))) index++;
        return index;
    }

    private static Object at(List<?> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static Difference diff(Kind kind, int frame, String location,
            Object reference, Object engine) {
        return new Difference(kind, frame, location, describe(reference), describe(engine));
    }

    private static String describe(Object value) {
        return value == null ? "<absent>" : value.toString();
    }

    private static int recordFrame(CompleteRunAudioTrace.Record reference,
            CompleteRunAudioTrace.Record engine) {
        CompleteRunAudioTrace.Record[] records = {reference, engine};
        for (CompleteRunAudioTrace.Record record : records) {
            if (record instanceof Frame frame) return frame.absoluteFrame();
            if (record instanceof Lifecycle lifecycle) return lifecycle.absoluteFrame();
            if (record instanceof Baseline baseline) return baseline.absoluteFrame();
            if (record instanceof Terminal terminal) return terminal.exclusiveEnd();
        }
        return -1;
    }

    private static RecordView view(Entry entry) {
        if (entry == null) return null;
        try {
            return new RecordView(entry.index, recordFrame(entry.record, entry.record),
                    entry.record.getClass().getSimpleName().toLowerCase(),
                    CompleteRunAudioJson.writeRecord(entry.record));
        } catch (IOException failure) {
            throw new AssertionError("validated record could not be canonicalized", failure);
        }
    }

    record Difference(Kind kind, int frame, String location, String referenceValue,
            String engineValue) {
        Difference {
            Objects.requireNonNull(kind, "difference kind");
            Objects.requireNonNull(location, "difference location");
            Objects.requireNonNull(referenceValue, "reference value");
            Objects.requireNonNull(engineValue, "engine value");
        }
    }

    private record RequestPayload(OwnerClass ownerClass, String contentKey, int nativeId,
            String queueSource, Integer queueSlot) { }
    private record ServicePayload(String kind, ServiceCompletion completion, Long carriedBoundaryOrdinal,
            ServiceCoordinate beginCoordinate, ServiceCoordinate endCoordinate, ServiceAncestry ancestry) { }
    private record CutoffServicePayload(Integer parentFrame, long parentOrdinal, int depth, String kind,
            FrontierServiceState state, int beginFrame, long beginOrdinal, Integer endFrame, Long endOrdinal,
            ServiceAncestry ancestry) { }
    private record CutoffChipPayload(List<ChipEvent> events, int ymPort0Latch, int ymPort1Latch) { }
    private record DecisionPayload(int nativeId, String contentKey, boolean accepted, String reason,
            Integer priorityBefore, Integer priorityAfter, List<HardwareRole> requestedRoles,
            List<RoleDecision> roles) { }
    private record DecisionOrderPayload(int nativeId, String contentKey, boolean accepted, String reason,
            Integer priorityBefore, Integer priorityAfter, List<HardwareRole> requestedRoles,
            List<RoleOrderPayload> roles) { }
    private record RoleOrderPayload(HardwareRole role, OwnerPayload displaced, OwnerPayload finalOwner) { }
    private record OwnerPayload(OwnerClass ownerClass, String contentKey, int nativeId,
            OwnerOrigin origin) { }
    private record YmPayload(int port, int register, int value) { }
    private record PsgPayload(int value) { }
    private record FrameCoordinatesPayload(String segment, Boolean lag) { }
    private record PriorityPayload(Integer before, Integer after) { }

    private record Snapshot(Path source, Metadata metadata, String rootDigest, String semanticDigest,
            PublicationIdentity publication, String metadataSha256) {
        private SourceIdentity identity(Side side) {
            String logicalSource = side == Side.REFERENCE ? "reference" : "engine";
            List<CompleteRunAudioReport.ChunkIdentity> stableChunks = publication.chunks().stream()
                    .map(chunk -> new CompleteRunAudioReport.ChunkIdentity(chunk.file(),
                            chunk.compressedSha256(), "chunks/" + chunk.file(),
                            "sha256:" + chunk.compressedSha256()))
                    .toList();
            return new SourceIdentity(side, logicalSource, logicalSource, metadata,
                    metadataSha256, rootDigest, publication.manifestSha256(), publication.digest(),
                    "sha256:" + publication.digest(), stableChunks);
        }
    }

    private record PublicationIdentity(Path realSource, String sourceFileKey, String manifestSha256,
            List<CompleteRunAudioReport.ChunkIdentity> chunks, String digest) {
        private PublicationIdentity {
            Objects.requireNonNull(realSource, "resolved source");
            Objects.requireNonNull(sourceFileKey, "source file key");
            Objects.requireNonNull(manifestSha256, "manifest SHA-256");
            chunks = List.copyOf(chunks);
            Objects.requireNonNull(digest, "publication digest");
        }

        static PublicationIdentity capture(Path source, FileIdentityProvider fileIdentities)
                throws IOException {
            Path realSource;
            if (Files.isSymbolicLink(source)) {
                Path link = Files.readSymbolicLink(source);
                if (link.isAbsolute() || link.getNameCount() != 1
                        || !link.getFileName().toString().startsWith(".audio-published-")) {
                    throw new IOException("capture publication has a noncanonical root link: " + source);
                }
                realSource = source.getParent().resolve(link).normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            } else {
                realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
            }
            BasicFileAttributes sourceAttributes = Files.readAttributes(realSource, BasicFileAttributes.class);
            if (!sourceAttributes.isDirectory()) {
                throw new IOException("capture publication is not a directory: " + source);
            }
            String sourceFileKey = requireFileKey(realSource,
                    fileIdentities.fileKey(realSource, sourceAttributes));
            String manifestSha256 = sha256(realSource.resolve("manifest.json"));
            Path chunksDirectory = realSource.resolve("chunks");
            List<Path> paths;
            try (var listed = Files.list(chunksDirectory)) {
                paths = listed.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(CompleteRunAudioCaptureStore.MAX_CAPTURE_CHUNKS + 1L).toList();
            }
            if (paths.isEmpty() || paths.size() > CompleteRunAudioCaptureStore.MAX_CAPTURE_CHUNKS) {
                throw new IOException("capture publication has an invalid chunk count");
            }
            List<CompleteRunAudioReport.ChunkIdentity> chunks = new ArrayList<>(paths.size());
            for (Path path : paths) {
                Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new IOException("capture chunk is not a regular file: " + path);
                }
                chunks.add(new CompleteRunAudioReport.ChunkIdentity(path.getFileName().toString(),
                        sha256(realPath), realPath.toString(), requireFileKey(realPath,
                                fileIdentities.fileKey(realPath, attributes))));
            }
            MessageDigest digest = sha256Digest();
            digestField(digest, manifestSha256);
            for (CompleteRunAudioReport.ChunkIdentity chunk : chunks) {
                digestField(digest, chunk.file());
                digestField(digest, chunk.compressedSha256());
            }
            return new PublicationIdentity(realSource, sourceFileKey, manifestSha256, chunks,
                    HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static String requireFileKey(Path path, String fileKey)
            throws PublicationIdentityUnavailableException {
        if (fileKey == null || fileKey.isBlank()) {
            throw new PublicationIdentityUnavailableException(
                    "filesystem publication identity is unavailable");
        }
        return fileKey;
    }

    private static final class PublicationIdentityUnavailableException extends IOException {
        private PublicationIdentityUnavailableException(String message) {
            super(message);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private record Entry(long index, CompleteRunAudioTrace.Record record) { }

    private static final class PassStream implements AutoCloseable {
        private final CompleteRunAudioCaptureStore.Reader reader;
        private final Snapshot expected;
        private final Side side;
        private final StreamValidator validator;
        private long index;
        private Terminal terminal;
        private boolean finished;

        private PassStream(CompleteRunAudioCaptureStore.Reader reader, Snapshot expected, Side side,
                StreamValidator validator) {
            this.reader = reader;
            this.expected = expected;
            this.side = side;
            this.validator = validator;
        }

        static PassStream open(CompleteRunAudioCaptureStore store, Snapshot expected, Side side,
                ProducerKind producer, FileIdentityProvider fileIdentities) throws ValidationException {
            CompleteRunAudioCaptureStore.Reader reader;
            try {
                PublicationIdentity current = PublicationIdentity.capture(expected.source, fileIdentities);
                if (!current.equals(expected.publication)) {
                    throw new ValidationException(ValidationException.Kind.SOURCE_REPLACED, side,
                            "capture publication changed between passes");
                }
                reader = store.read(current.realSource());
            } catch (PublicationIdentityUnavailableException failure) {
                throw new ValidationException(ValidationException.Kind.PUBLICATION_IDENTITY_UNAVAILABLE, side,
                        failure.getMessage(), failure);
            } catch (IOException failure) {
                throw new ValidationException(ValidationException.Kind.IO_FAILURE, side,
                        "comparison pass could not reopen capture", failure);
            } catch (RuntimeException failure) {
                throw new ValidationException(ValidationException.Kind.CAPTURE_INVALID, side,
                        "comparison pass could not parse capture metadata", failure);
            }
            try {
                Metadata metadata = reader.metadata();
                if (!metadata.equals(expected.metadata)) {
                    reader.close();
                    throw new ValidationException(ValidationException.Kind.SOURCE_REPLACED, side,
                            "capture metadata changed between passes");
                }
                CompleteRunAudioProfile profile = validateMetadata(metadata, producer, side);
                return new PassStream(reader, expected, side, new StreamValidator(metadata, profile, side));
            } catch (ValidationException failure) {
                try { reader.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            } catch (IOException failure) {
                throw new ValidationException(ValidationException.Kind.IO_FAILURE, side,
                        "comparison pass could not close changed capture", failure);
            }
        }

        Entry next() throws ValidationException {
            if (finished) return null;
            try {
                if (!reader.hasNext()) {
                    validator.finish();
                    finished = true;
                    return null;
                }
                CompleteRunAudioTrace.Record record = reader.next();
                validator.accept(record);
                if (record instanceof Terminal value) terminal = value;
                return new Entry(index++, record);
            } catch (ValidationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new ValidationException(ValidationException.Kind.CAPTURE_INVALID, side,
                        "capture no longer passes strict validation in comparison pass", failure);
            }
        }

        void verifyStable(FileIdentityProvider fileIdentities) throws ValidationException {
            if (!finished || terminal == null || !terminal.rootDigest().equals(expected.rootDigest)
                    || !terminal.semanticDigest().equals(expected.semanticDigest)) {
                throw new ValidationException(ValidationException.Kind.SOURCE_REPLACED, side,
                        "capture root digest changed between passes");
            }
            try {
                if (!PublicationIdentity.capture(expected.source, fileIdentities).equals(expected.publication)) {
                    throw new ValidationException(ValidationException.Kind.SOURCE_REPLACED, side,
                            "capture publication changed during comparison pass");
                }
            } catch (PublicationIdentityUnavailableException failure) {
                throw new ValidationException(ValidationException.Kind.PUBLICATION_IDENTITY_UNAVAILABLE, side,
                        failure.getMessage(), failure);
            } catch (IOException failure) {
                throw new ValidationException(ValidationException.Kind.IO_FAILURE, side,
                        "capture publication could not be verified after comparison", failure);
            }
        }

        @Override
        public void close() throws ValidationException {
            try {
                reader.close();
            } catch (IOException failure) {
                throw new ValidationException(ValidationException.Kind.IO_FAILURE, side,
                        "comparison pass could not close capture", failure);
            }
        }
    }

    private static final class StreamValidator {
        private final Metadata metadata;
        private final CompleteRunAudioProfile profile;
        private final Side side;
        private final Set<HardwareRole> hardwareRoles;
        private final Map<NativeSoundIdentity, List<NativeSoundIdentity>> decisionResolutions;
        private final Map<String, OwnershipTransition> ownershipTransitions;
        private final PendingRequestPolicy pendingPolicy;
        private final ComparisonLayerInventory layers;
        private final Map<Long, NativeSoundIdentity> pendingRequests = new LinkedHashMap<>();
        private final Map<HardwareRole, OwnerRef> liveOwners = new EnumMap<>(HardwareRole.class);
        private final Map<HardwareRole, ArrayDeque<OwnerRef>> savedOwners =
                new EnumMap<>(HardwareRole.class);
        private final ArrayDeque<NativeResetExpectation> nativeResets = new ArrayDeque<>();
        private final Map<Long, ManagedServiceEvidence> activeManagedServices = new LinkedHashMap<>();
        private final Map<NativeBeginCoordinate, ManagedServiceEvidence> completedManagedServices =
                new LinkedHashMap<>();
        private final Map<Long, List<NativeAncestryTransition>> nativePromotionEvidence =
                new LinkedHashMap<>();
        private DeferredReservationState deferredReservation;
        private final LinkedHashMap<Long, CutoffService> carriedServices = new LinkedHashMap<>();
        private final Map<Long, FrontierService> nativeCarriedServices = new LinkedHashMap<>();
        private final Map<Long, FrontierService> activeNativeServices = new LinkedHashMap<>();
        private final Set<Long> releasedNativeCarriedTokens = new HashSet<>();
        private final Set<Long> continuedNativeCarriedTokens = new HashSet<>();
        private long requestOrdinal;
        private long serviceOrdinal;
        private long chipOrdinal;
        private long lifecycleOrdinal;
        private int previousNativeBeginFrame = -1;
        private long previousNativeBeginOrdinal = -1;
        private long previousNativeRawCoordinate = -1;
        private Integer nativeYmPort0Latch;
        private Integer nativeYmPort1Latch;
        private int segmentIndex;
        private int previousSourceFrame = -1;
        private int peakPendingRequests;
        private int peakSavedOwners;
        private long completedRequests;
        private boolean stateObservationRequired;
        private boolean baseline;
        private boolean terminal;

        private StreamValidator(Metadata metadata, CompleteRunAudioProfile profile, Side side) {
            this.metadata = metadata;
            this.profile = profile;
            this.side = side;
            hardwareRoles = Set.copyOf(profile.hardwareRoles());
            decisionResolutions = profile.decisionResolutions();
            ownershipTransitions = profile.ownershipTransitions();
            pendingPolicy = profile.pendingRequestPolicy();
            layers = metadata.comparisonLayerInventory();
        }

        void accept(CompleteRunAudioTrace.Record record) throws ValidationException {
            if (terminal) ordinal("record follows terminal");
            try {
                metadata.validateObservationShape(record);
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "record contradicts producer observation inventory", invalid);
            }
            if (record instanceof Baseline value) {
                if (baseline || value.absoluteFrame() != metadata.fixture().firstFrame()) {
                    ordinal("baseline is not the unique comparison-epoch baseline");
                }
                baseline = true;
                previousSourceFrame = value.absoluteFrame();
                if (observes(ComparisonLayer.OWNERSHIP)) baselineOwners(value);
                if (observes(ComparisonLayer.STATE)) state(value.state());
                baselineFrontier(value.frontier());
            } else if (record instanceof Frame frame) {
                if (!nativeResets.isEmpty()) ordinal("native reset is not followed by its typed lifecycle");
                if (!baseline) ordinal("frame precedes baseline");
                sourceCoordinate(frame.absoluteFrame());
                segment(frame);
                if (observes(ComparisonLayer.REQUESTS)) {
                    for (Request request : frame.requests()) {
                        if (request.ordinal() != requestOrdinal++) {
                            ordinal("request ordinal is not globally contiguous");
                        }
                        NativeSoundIdentity identity = requestIdentity(request);
                        if (observes(ComparisonLayer.DECISIONS)) {
                            if (pendingRequests.size() == pendingPolicy.maximumPending()) {
                                throw new ValidationException(ValidationException.Kind.PENDING_CAPACITY_INVALID, side,
                                        "unresolved requests exceed the profile-owned bound");
                            }
                            pendingRequests.put(request.ordinal(), identity);
                            peakPendingRequests = Math.max(peakPendingRequests, pendingRequests.size());
                        }
                    }
                }
                for (int serviceIndex = 0; observes(ComparisonLayer.SERVICES)
                        && serviceIndex < frame.services().size(); serviceIndex++) {
                    DriverService service = frame.services().get(serviceIndex);
                    if (service.ordinal() != serviceOrdinal++) {
                        ordinal("service ordinal is not globally contiguous");
                    }
                    if (observes(ComparisonLayer.CUTOFF_FRONTIER)) {
                        releaseCarriedService(service, frame.nativeDiagnostics(), serviceIndex);
                    }
                }
                if (observes(ComparisonLayer.DECISIONS)) {
                    for (Decision decision : frame.decisions()) decision(decision);
                }
                if (observes(ComparisonLayer.STATE)) state(frame.postRowState());
                if (observes(ComparisonLayer.FRAME_CHIP_EVENTS)) {
                    for (ChipEvent event : frame.chipEvents()) {
                        if (event.ordinal() != chipOrdinal++) ordinal("chip-event ordinal is not globally contiguous");
                    }
                }
                boolean buffered = metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity;
                if (buffered) {
                    DeferredFrameEvidence deferred = deferredServiceBegins(
                            frame.absoluteFrame(), frame.nativeDiagnostics());
                    nativeRawOrder(frame.absoluteFrame(), frame.nativeDiagnostics(), deferred);
                    for (int serviceIndex = 0;
                            serviceIndex < frame.nativeDiagnostics().services().size(); serviceIndex++) {
                        FrontierService service = frame.nativeDiagnostics().services().get(serviceIndex);
                        FrontierService priorActive = activeNativeServices.get(service.token());
                        boolean carried = priorActive != null && sameNativeGeneration(priorActive, service);
                        if (!carried && (service.beginFrame() < previousNativeBeginFrame
                                || service.beginFrame() == previousNativeBeginFrame
                                        && service.beginOrdinal() <= previousNativeBeginOrdinal
                                || service.endFrame() == null || service.endFrame() > frame.absoluteFrame()
                                || service.parentToken() == 0 && service.endFrame() != frame.absoluteFrame())) {
                            throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                    "native frame services violate global begin or release order");
                        }
                        if (profile.cutoffFrontierPolicy().serviceRules().stream()
                                .noneMatch(rule -> rule.matches(service, true)
                                        && rule.acceptsChipSources(service))) {
                            throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                    "native frame service is outside the exact observer manifest");
                        }
                        if (!carried) {
                            previousNativeBeginFrame = service.beginFrame();
                            previousNativeBeginOrdinal = service.beginOrdinal();
                        }
                        if (service.state() == FrontierServiceState.OPEN) {
                            activeNativeServices.put(service.token(), service);
                        } else if (carried) {
                            activeNativeServices.remove(service.token());
                        }
                    }
                    commitDeferredServiceBegin(deferred);
                    managedBoundaries(frame.absoluteFrame(), frame.nativeDiagnostics());
                    List<FrontierService> resetRoots = new ArrayList<>();
                    for (NativeResetDiagnostic reset : frame.nativeDiagnostics().resets()) {
                        int serviceIndex = -1;
                        for (int index = 0; index < frame.nativeDiagnostics().services().size(); index++) {
                            if (frame.nativeDiagnostics().services().get(index).token() == reset.serviceToken()) {
                                serviceIndex = index;
                                break;
                            }
                        }
                        if (serviceIndex < 0) throw new ValidationException(
                                ValidationException.Kind.STATE_INVALID, side,
                                "native reset diagnostic has no canonical service");
                        FrontierService resetRoot = frame.nativeDiagnostics().services().get(serviceIndex);
                        if (resetRoot.beginFrame() != frame.absoluteFrame()) {
                            throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                    "native reset root does not begin in its captured frame");
                        }
                        resetRoots.add(resetRoot);
                        if (observes(ComparisonLayer.LIFECYCLE)
                                && observes(ComparisonLayer.SERVICES)) {
                            nativeResets.addLast(new NativeResetExpectation(frame.absoluteFrame(), reset.power(),
                                    frame.services().get(serviceIndex).ordinal()));
                        }
                    }
                    {
                        int resetIndex = 0;
                        for (FrontierOwnedChip owned : frame.nativeDiagnostics().rawChipInventory()) {
                            while (resetIndex < resetRoots.size()
                                    && resetRoots.get(resetIndex).beginOrdinal() < owned.event().ordinal()) {
                                nativeYmPort0Latch = 0;
                                nativeYmPort1Latch = 0;
                                resetIndex++;
                            }
                            replayNativeYm(owned.event());
                        }
                        while (resetIndex < resetRoots.size()) {
                            nativeYmPort0Latch = 0;
                            nativeYmPort1Latch = 0;
                            resetIndex++;
                        }
                    }
                }
            } else if (record instanceof Lifecycle lifecycle) {
                boolean observeLifecycleRecord = observes(ComparisonLayer.LIFECYCLE);
                if (!baseline || observeLifecycleRecord && lifecycle.ordinal() != lifecycleOrdinal++) {
                    ordinal("lifecycle ordinal is not globally contiguous after baseline");
                }
                if (!nativeResets.isEmpty()) {
                    NativeResetExpectation reset = nativeResets.removeFirst();
                    String expected = reset.power() ? "power" : "reset";
                    Object serviceOrdinal = lifecycle.details().get("service_ordinal");
                    if (lifecycle.absoluteFrame() != reset.frame()
                            || !lifecycle.kind().equalsIgnoreCase(expected)
                            || !(serviceOrdinal instanceof Number number)
                            || number.longValue() != reset.serviceOrdinal()) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "native reset diagnostic and typed lifecycle disagree");
                    }
                }
                lifecycle(lifecycle);
            } else if (record instanceof CutoffFrontier frontier) {
                if (!nativeResets.isEmpty()) ordinal("native reset lifecycle is missing before cutoff");
                boolean buffered = metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity;
                boolean observeCutoff = observes(ComparisonLayer.CUTOFF_FRONTIER);
                boolean observeChips = observes(ComparisonLayer.BOUNDARY_CHIP_STATE);
                if (observeCutoff) accountCarriedAtCutoff(frontier);
                if (buffered) {
                    deferredCutoff(frontier.nativeDiagnostics());
                    List<FrontierService> terminalServices = java.util.stream.Stream.concat(
                            frontier.nativeDiagnostics().activeStack().stream(),
                            frontier.nativeDiagnostics().pendingDescendants().stream())
                            .sorted(java.util.Comparator.comparingInt(FrontierService::beginFrame)
                                    .thenComparingLong(FrontierService::beginOrdinal)).toList();
                    for (FrontierService service : terminalServices) {
                        FrontierService priorActive = activeNativeServices.get(service.token());
                        boolean continuedCarry = priorActive != null
                                && sameNativeGeneration(priorActive, service);
                        if (!continuedCarry && (service.beginFrame() < previousNativeBeginFrame
                                || service.beginFrame() == previousNativeBeginFrame
                                        && service.beginOrdinal() <= previousNativeBeginOrdinal)) {
                            throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                    "native cutoff service begins outside global order");
                        }
                        previousNativeBeginFrame = service.beginFrame();
                        previousNativeBeginOrdinal = service.beginOrdinal();
                        consumeNativePromotions(service);
                    }
                    activeNativeServices.clear();
                    managedCutoff(terminalServices);
                    continuedNativeCarriedTokens.clear();
                }
                if (buffered) replayNativeCutoffChips(frontier);
                if (observeCutoff || observeChips || buffered) {
                    try {
                        profile.cutoffFrontierPolicy().validate(frontier,
                                observeCutoff, observes(ComparisonLayer.STATE), observeChips);
                    } catch (RuntimeException failure) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "cutoff frontier does not match its exact service manifest", failure);
                    }
                }
                if (observes(ComparisonLayer.STATE)) state(frontier.terminalState());
            } else if (record instanceof Terminal) {
                if (observes(ComparisonLayer.CUTOFF_FRONTIER) && !carriedServices.isEmpty()
                        || metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity
                                && !nativeCarriedServices.isEmpty()) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "terminal leaves carried-in services unresolved");
                }
                if (metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity
                        && (!activeNativeServices.isEmpty() || !activeManagedServices.isEmpty()
                        || !completedManagedServices.isEmpty()
                        || !nativePromotionEvidence.isEmpty() || deferredReservation != null)) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "terminal leaves native managed-service evidence unaccounted");
                }
                CompleteRunAudioTrace.NativeCapabilitySummary expectedCapability =
                        profile.completeRunCapabilities().get(metadata.producerKind());
                if (!Objects.equals(expectedCapability, ((Terminal) record).nativeCapability())) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "terminal native capability does not match the exact profile literal");
                }
                if (observes(ComparisonLayer.DECISIONS)
                        && pendingRequests.size() > pendingPolicy.maximumAtTerminal()) {
                    throw new ValidationException(ValidationException.Kind.PENDING_UNRESOLVED, side,
                            "capture terminates with unresolved requests outside the profile allowance");
                }
                if (observes(ComparisonLayer.OWNERSHIP) && stateObservationRequired) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "capture terminates before a lifecycle ownership change is observed in state");
                }
                if (observes(ComparisonLayer.OWNERSHIP)) {
                    List<SavedOwnerDepth> terminalDepths = profile.hardwareRoles().stream()
                            .filter(role -> !savedOwners.get(role).isEmpty())
                            .map(role -> new SavedOwnerDepth(role, savedOwners.get(role).size()))
                            .toList();
                    if (!terminalDepths.equals(profile.restoreStackPolicy().terminalDepths())) {
                        throw new ValidationException(ValidationException.Kind.RESTORE_STACK_INVALID, side,
                                "capture terminal restore stacks do not match the exact profile allowance");
                    }
                }
                terminal = true;
            }
        }

        private void baselineFrontier(BoundaryFrontier frontier) throws ValidationException {
            boolean observeCutoff = observes(ComparisonLayer.CUTOFF_FRONTIER);
            boolean observeChips = observes(ComparisonLayer.BOUNDARY_CHIP_STATE);
            if (observeCutoff) {
                for (CutoffService service : frontier.activeStack()) {
                    carriedServices.put(service.beginOrdinal(), service);
                }
            }
            if (observeChips) chipOrdinal = frontier.rawChipEvents().size();
            boolean buffered = metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity;
            if (!buffered && frontier.nativeDiagnostics() != null) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "callback baseline cannot carry buffered-native proof");
            }
            if (frontier.nativeDiagnostics() == null) return;
            {
                NativeDeferredServiceBegin deferred =
                        frontier.nativeDiagnostics().pendingDeferredServiceBegin();
                if (deferred != null) {
                    FrontierService currentOwner = frontier.nativeDiagnostics().activeStack().getLast();
                    validateAttestedDeferredOwner(deferred, currentOwner);
                    deferredReservation = new DeferredReservationState(deferred,
                            DeferredOwner.from(currentOwner), null, false);
                }
                List<FrontierService> nativeBoundaryServices = java.util.stream.Stream.concat(
                        frontier.nativeDiagnostics().activeStack().stream(),
                        frontier.nativeDiagnostics().pendingDescendants().stream())
                        .sorted(java.util.Comparator.comparingInt(FrontierService::beginFrame)
                                .thenComparingLong(FrontierService::beginOrdinal)).toList();
                for (FrontierService service : nativeBoundaryServices) {
                    if (profile.cutoffFrontierPolicy().serviceRules().stream()
                            .noneMatch(rule -> rule.matches(service, true)
                                    && rule.acceptsChipSources(service))) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "baseline native carry-in is outside the exact observer manifest");
                    }
                    previousNativeBeginFrame = service.beginFrame();
                    previousNativeBeginOrdinal = service.beginOrdinal();
                    if (service.state() == FrontierServiceState.OPEN) {
                        activeNativeServices.put(service.token(), service);
                    }
                }
                for (int index = 0; observeCutoff
                        && index < frontier.nativeDiagnostics().activeStack().size(); index++) {
                    FrontierService service = frontier.nativeDiagnostics().activeStack().get(index);
                    nativeCarriedServices.put(frontier.activeStack().get(index).beginOrdinal(), service);
                }
            }
            {
                for (FrontierOwnedChip owned : frontier.nativeDiagnostics().rawChipInventory()) {
                    if (owned.event().coordinate() <= previousNativeRawCoordinate) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "baseline native chip coordinates are not globally increasing");
                    }
                    previousNativeRawCoordinate = owned.event().coordinate();
                    replayNativeYm(owned.event());
                }
                if (observeChips && (nativeYmPort0Latch != null
                            && !Objects.equals(nativeYmPort0Latch, frontier.ymPort0Latch())
                        || nativeYmPort1Latch != null
                            && !Objects.equals(nativeYmPort1Latch, frontier.ymPort1Latch()))) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "baseline native YM latch replay disagrees with semantic carry-in");
                }
            }
        }

        private static boolean sameNativeGeneration(FrontierService left, FrontierService right) {
            return left.token() == right.token()
                    && left.beginFrame() == right.beginFrame()
                    && left.beginOrdinal() == right.beginOrdinal()
                    && left.beginPc() == right.beginPc()
                    && left.beginHookToken() == right.beginHookToken()
                    && left.beginSourceCpu().equals(right.beginSourceCpu());
        }

        private void releaseCarriedService(DriverService service, FrameNativeDiagnostics diagnostics,
                int serviceIndex) throws ValidationException {
            Long carriedOrdinal = service.carriedBoundaryOrdinal();
            if (carriedOrdinal == null) return;
            if (carriedServices.isEmpty() || !carriedServices.containsKey(carriedOrdinal)
                    || !carriedServices.keySet().stream().toList().getLast().equals(carriedOrdinal)) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "service carry link is absent or violates inner-to-outer release order");
            }
            CutoffService carried = carriedServices.get(carriedOrdinal);
            if (!carried.kind().equals(service.kind())) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "service carry link kind differs from its baseline service");
            }
            if (diagnostics != null) {
                FrontierService proof = nativeCarriedServices.get(carriedOrdinal);
                FrontierService release = diagnostics.services().get(serviceIndex);
                FrontierServiceState expected = service.completion() == ServiceCompletion.COMPLETED
                        ? FrontierServiceState.COMPLETED : FrontierServiceState.RESET_CANCELLED;
                if (proof == null || release.token() != proof.token() || release.parentToken() != proof.parentToken()
                        || release.depth() != proof.depth() || !release.kind().equals(proof.kind())
                        || release.beginFrame() != proof.beginFrame()
                        || release.beginOrdinal() != proof.beginOrdinal()
                        || release.beginPc() != proof.beginPc()
                        || release.beginHookToken() != proof.beginHookToken()
                        || !release.beginSourceCpu().equals(proof.beginSourceCpu())
                        || release.state() != expected) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "native carried-service release disagrees with baseline proof");
                }
                nativeCarriedServices.remove(carriedOrdinal);
                releasedNativeCarriedTokens.add(proof.token());
            }
            carriedServices.remove(carriedOrdinal);
        }

        private void accountCarriedAtCutoff(CutoffFrontier frontier) throws ValidationException {
            if (carriedServices.isEmpty()) return;
            List<CutoffService> remaining = List.copyOf(carriedServices.values());
            if (frontier.activeStack().size() < remaining.size()) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "cutoff omits unresolved carried-in services");
            }
            for (int index = 0; index < remaining.size(); index++) {
                CutoffService carried = remaining.get(index);
                CutoffService open = frontier.activeStack().get(index);
                if (open.state() != FrontierServiceState.OPEN || open.depth() != carried.depth()
                        || !open.kind().equals(carried.kind())
                        || open.beginFrame() != carried.beginFrame()
                        || open.beginOrdinal() != carried.beginOrdinal()) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "cutoff frontier does not continue the same carried-in stack");
                }
                if (frontier.nativeDiagnostics() != null) {
                    FrontierService proof = nativeCarriedServices.get(carried.beginOrdinal());
                    FrontierService continued = frontier.nativeDiagnostics().activeStack().get(index);
                    if (proof == null || continued.token() != proof.token()
                            || continued.beginFrame() != proof.beginFrame()
                            || continued.beginOrdinal() != proof.beginOrdinal()
                            || continued.beginPc() != proof.beginPc()
                            || continued.beginHookToken() != proof.beginHookToken()
                            || !continued.beginSourceCpu().equals(proof.beginSourceCpu())) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "native cutoff does not continue the same carried-in service");
                    }
                    continuedNativeCarriedTokens.add(proof.token());
                }
            }
            carriedServices.clear();
            nativeCarriedServices.clear();
        }

        private void nativeRawOrder(int frame, FrameNativeDiagnostics diagnostics,
                DeferredFrameEvidence deferred) throws ValidationException {
            List<NativeRawSlot> slots = java.util.stream.Stream.concat(
                    java.util.stream.Stream.concat(
                            diagnostics.rawChipInventory().stream().map(owned -> new NativeRawSlot(
                                    owned.event().coordinate(), owned.event().ordinal())),
                            diagnostics.managedCorrelations().stream()
                                    .flatMap(correlation -> correlation.events().stream())
                                    .map(event -> new NativeRawSlot(event.coordinate(), event.ordinal()))),
                    diagnostics.rawAncestryTransitionInventory().stream()
                            .map(owned -> new NativeRawSlot(
                                    owned.event().coordinate(), owned.event().ordinal())))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            slots.sort(Comparator.comparingLong(NativeRawSlot::coordinate)
                    .thenComparingLong(NativeRawSlot::ordinal));
            long previousOrdinal = -1;
            Long coordinateBase = null;
            for (NativeRawSlot slot : slots) {
                if (slot.coordinate() <= previousNativeRawCoordinate
                        || slot.ordinal() <= previousOrdinal) {
                    throw nativeEvidence(
                            "native raw coordinates/ordinals collide or are not globally increasing");
                }
                long candidateBase = Math.subtractExact(slot.coordinate(), slot.ordinal());
                if (coordinateBase != null && candidateBase != coordinateBase) {
                    throw nativeEvidence("native frame coordinates do not share one exact ordinal base");
                }
                coordinateBase = candidateBase;
                previousNativeRawCoordinate = slot.coordinate();
                previousOrdinal = slot.ordinal();
            }
            if (deferred.consumeBegin() != null
                    && (coordinateBase == null
                            || deferred.consumeBegin().coordinate()
                                    != coordinateBase + deferred.consumeBegin().ordinal())) {
                throw nativeEvidence("deferred service-begin consume coordinate is not exact");
            }
            DeferredReservationState reservation = deferred.reservation();
            TailTransferProof transfer = reservation == null ? null : reservation.transferProof();
            if (transfer != null) {
                FrontierService origin = transfer.originCompletion();
                int latestMarkerFrame = Math.toIntExact(
                        reservation.origin().latestCoordinate() >>> 32);
                if (compareNativePosition(latestMarkerFrame, reservation.origin().latestOrdinal(),
                        origin.endFrame(), origin.endOrdinal()) >= 0) {
                    throw nativeEvidence("deferred origin completion does not follow its latest marker");
                }
                rejectRetainedServiceCollision(frame, origin.endFrame(), origin.endOrdinal(), slots);
                rejectRetainedServiceCollision(
                        frame, origin.endFrame(), origin.endOrdinal() + 1, slots);
                DeferredOwner current = reservation.currentOwner();
                if (transfer.reconciled()) {
                    if (deferred.consumeBegin() != null) {
                        int consumeFrame = Math.toIntExact(
                                deferred.consumeBegin().coordinate() >>> 32);
                        if (compareNativePosition(current.beginFrame(), current.beginOrdinal(),
                                consumeFrame, deferred.consumeBegin().ordinal()) >= 0) {
                            throw nativeEvidence(
                                    "deferred consume does not follow its exact tail successor");
                        }
                    }
                }
            }
            recordNativePromotions(diagnostics);
        }

        private void rejectRetainedServiceCollision(int frame, int serviceFrame,
                long serviceOrdinal, List<NativeRawSlot> slots) throws ValidationException {
            if (serviceFrame == frame
                    && slots.stream().anyMatch(slot -> slot.ordinal() == serviceOrdinal)) {
                throw nativeEvidence("retained tail service collides with another native raw event");
            }
        }

        private int compareNativePosition(int leftFrame, long leftOrdinal,
                int rightFrame, long rightOrdinal) {
            int frameOrder = Integer.compare(leftFrame, rightFrame);
            return frameOrder != 0 ? frameOrder : Long.compare(leftOrdinal, rightOrdinal);
        }

        private DeferredFrameEvidence deferredServiceBegins(int frame,
                FrameNativeDiagnostics diagnostics)
                throws ValidationException {
            List<NativeManagedEvent> markers = diagnostics.managedCorrelations().stream()
                    .flatMap(correlation -> correlation.events().stream())
                    .filter(event -> event.eventKind() == 10 && event.value() == 4).toList();
            NativeDeferredServiceBegin published = diagnostics.deferredServiceBegins().isEmpty()
                    ? null : diagnostics.deferredServiceBegins().getFirst();
            if (published == null) {
                if (!markers.isEmpty()
                        || deferredReservation != null && !deferredReservation.consumed()) {
                    throw nativeEvidence("deferred service-begin evidence is missing");
                }
                if (deferredReservation != null) {
                    deferredReservation = reconcileDeferredSuccessor(
                            deferredReservation, diagnostics.services());
                }
                return new DeferredFrameEvidence(deferredReservation, null);
            }
            if (deferredReservation != null && deferredReservation.consumed()) {
                throw nativeEvidence("deferred service-begin consume is duplicated");
            }
            NativeDeferredServiceBegin prior = deferredReservation == null
                    ? null : deferredReservation.origin();
            if (prior == null && markers.isEmpty()) {
                throw nativeEvidence("deferred service-begin evidence has no first marker");
            }
            NativeManagedEvent first = markers.isEmpty() ? null : markers.getFirst();
            NativeManagedEvent latest = markers.isEmpty() ? null : markers.getLast();
            long firstCoordinate = prior == null ? first.coordinate() : prior.firstCoordinate();
            long firstOrdinal = prior == null ? first.ordinal() : prior.firstOrdinal();
            long latestCoordinate = latest == null ? prior.latestCoordinate() : latest.coordinate();
            long latestOrdinal = latest == null ? prior.latestOrdinal() : latest.ordinal();
            int observations = Math.addExact(prior == null ? 0 : prior.observationCount(), markers.size());
            NativeManagedEvent identity = first == null ? null : first;
            long blockerToken = prior == null ? identity.serviceToken() : prior.blockerToken();
            long blockerParent = prior == null ? identity.parentToken() : prior.blockerParentToken();
            int blockerKind = prior == null ? identity.serviceKind() : prior.blockerKind();
            int blockerDepth = prior == null ? identity.depth() : prior.blockerDepth();
            int hookToken = prior == null ? identity.hookToken() : prior.hookToken();
            int pc = prior == null ? identity.pc() : prior.pc();
            int targetKind = prior == null ? published.targetKind() : prior.targetKind();
            NativeDeferredServiceBegin expected = new NativeDeferredServiceBegin(
                    blockerToken, blockerParent, blockerKind, blockerDepth, targetKind,
                    hookToken, 2, pc, firstCoordinate, latestCoordinate, firstOrdinal,
                    latestOrdinal, observations, published.consumed(), published.consumedToken(),
                    published.consumeCoordinate());
            for (NativeManagedEvent marker : markers) {
                if (!"M68K".equals(marker.sourceCpu()) || marker.pc() != pc
                        || marker.serviceToken() != blockerToken
                        || marker.parentToken() != blockerParent
                        || marker.serviceKind() != blockerKind || marker.depth() != blockerDepth
                        || marker.hookToken() != hookToken) {
                    throw nativeEvidence("deferred service-begin marker identity changed");
                }
            }
            if (!published.equals(expected)) {
                throw nativeEvidence("deferred service-begin diagnostic does not exactly extend its markers");
            }
            if (!diagnostics.resets().isEmpty()) {
                throw nativeEvidence("reset occurs while a deferred service begin is retained");
            }
            if (!published.consumed()) {
                DeferredReservationState state = deferredReservation == null
                        ? DeferredReservationState.atOrigin(published)
                        : new DeferredReservationState(published, deferredReservation.currentOwner(),
                                deferredReservation.transferProof(), false);
                state = observeDeferredTransfer(state, null, diagnostics.services());
                return new DeferredFrameEvidence(state, null);
            }
            List<NativeManagedEvent> consumeBegins = diagnostics.managedCorrelations().stream()
                    .flatMap(correlation -> correlation.events().stream())
                    .filter(event -> event.eventKind() == 1
                            && event.serviceToken() == published.consumedToken()).toList();
            if (consumeBegins.size() != 1) {
                throw nativeEvidence("deferred service-begin consume has no unique child begin proof");
            }
            NativeManagedEvent consumeBegin = consumeBegins.getFirst();
            DeferredReservationState state = deferredReservation == null
                    ? DeferredReservationState.atOrigin(published)
                    : new DeferredReservationState(published, deferredReservation.currentOwner(),
                            deferredReservation.transferProof(), false);
            state = observeDeferredTransfer(state, consumeBegin, diagnostics.services());
            if (consumeBegin.coordinate() != published.consumeCoordinate()
                    || !"M68K".equals(consumeBegin.sourceCpu())
                    || consumeBegin.parentToken() != state.currentOwner().token()
                    || consumeBegin.serviceKind() != targetKind
                    || consumeBegin.depth() != state.currentOwner().depth() + 1) {
                throw nativeEvidence("deferred service-begin consume identity is not exact");
            }
            FrontierService consumed = diagnostics.services().stream()
                    .filter(service -> service.token() == published.consumedToken()).findFirst().orElse(null);
            // A completed child may be retained in this frame, or its ordinary begin may remain
            // active until a later frame/cutoff. In either case, the real managed BEGIN owns the
            // transaction; no blocker-END-to-root boundary is synthesized.
            if (consumed != null && (consumed.parentToken() != state.currentOwner().token()
                    || consumed.depth() != state.currentOwner().depth() + 1
                    || consumed.beginFrame() != frame
                    || consumed.beginOrdinal() != consumeBegin.ordinal()
                    || consumed.beginPc() != consumeBegin.pc()
                    || consumed.beginHookToken() != consumeBegin.hookToken()
                    || !consumed.beginSourceCpu().equals(consumeBegin.sourceCpu()))) {
                throw nativeEvidence("deferred service-begin child does not match its consume proof");
            }
            state = new DeferredReservationState(published, state.currentOwner(),
                    state.transferProof(), true);
            return new DeferredFrameEvidence(state, consumeBegin);
        }

        private void commitDeferredServiceBegin(DeferredFrameEvidence deferred)
                throws ValidationException {
            DeferredReservationState reservation = deferred.reservation();
            deferredReservation = reservation != null && reservation.consumed()
                    && (reservation.transferProof() == null
                            || reservation.transferProof().reconciled())
                    ? null : reservation;
        }

        private void deferredCutoff(CutoffNativeDiagnostics diagnostics) throws ValidationException {
            if (deferredReservation != null) {
                deferredReservation = reconcileDeferredSuccessor(deferredReservation,
                        java.util.stream.Stream.concat(diagnostics.activeStack().stream(),
                                diagnostics.pendingDescendants().stream()).toList());
                if (deferredReservation.consumed()
                        && deferredReservation.transferProof() != null
                        && deferredReservation.transferProof().reconciled()) {
                    deferredReservation = null;
                }
            }
            NativeDeferredServiceBegin cutoff = diagnostics.pendingDeferredServiceBegin();
            NativeDeferredServiceBegin expected = deferredReservation == null
                    || deferredReservation.consumed() ? null : deferredReservation.origin();
            if (!Objects.equals(expected, cutoff)) {
                throw nativeEvidence("native cutoff does not carry the exact deferred service begin");
            }
            if (expected != null) {
                TailTransferProof transfer = deferredReservation.transferProof();
                if (transfer != null && !transfer.reconciled()) {
                    throw nativeEvidence(
                            "native cutoff omits the exact retained tail successor");
                }
                FrontierService current = diagnostics.activeStack().isEmpty()
                        ? null : diagnostics.activeStack().getLast();
                if (current == null || !deferredReservation.currentOwner().matches(current)) {
                    throw nativeEvidence("native cutoff does not carry the exact deferred current owner");
                }
            }
        }

        private DeferredReservationState observeDeferredTransfer(DeferredReservationState state,
                NativeManagedEvent consumeBegin, List<FrontierService> services)
                throws ValidationException {
            boolean consumeUnderOrigin = consumeBegin != null
                    && consumeBegin.parentToken() == state.origin().blockerToken();
            FrontierService originCompletion = services.stream()
                    .filter(service -> service.token() == state.origin().blockerToken())
                    .findFirst().orElse(null);
            boolean releasedAdjacentSuccessor = originCompletion != null
                    && originCompletion.endFrame() != null
                    && services.stream().anyMatch(service ->
                            service.token() != originCompletion.token()
                                    && service.beginFrame() == originCompletion.endFrame()
                                    && service.beginOrdinal() == originCompletion.endOrdinal() + 1);
            TailTransferProof proof = state.transferProof();
            if (proof == null && originCompletion != null
                    && (!consumeUnderOrigin || releasedAdjacentSuccessor)) {
                if (originCompletion.state() != FrontierServiceState.COMPLETED
                        || originCompletion.endFrame() == null) {
                    throw nativeEvidence("deferred origin owner ends without an exact transfer completion");
                }
                proof = new TailTransferProof(originCompletion,
                        consumeBegin == null ? 0 : consumeBegin.parentToken(), false);
            }
            DeferredOwner owner = state.currentOwner();
            if (proof != null && consumeBegin != null) {
                if (proof.successorToken() != 0
                        && proof.successorToken() != consumeBegin.parentToken()) {
                    throw nativeEvidence("deferred transfer and action-12 parent tokens disagree");
                }
                proof = new TailTransferProof(proof.originCompletion(),
                        consumeBegin.parentToken(), proof.reconciled());
                owner = DeferredOwner.provisional(consumeBegin.parentToken(),
                        state.origin().blockerParentToken(), state.origin().blockerDepth());
            }
            DeferredReservationState updated = new DeferredReservationState(
                    state.origin(), owner, proof, state.consumed());
            return reconcileDeferredSuccessor(updated, services);
        }

        private DeferredReservationState reconcileDeferredSuccessor(DeferredReservationState state,
                List<FrontierService> services) throws ValidationException {
            TailTransferProof proof = state.transferProof();
            if (proof == null || proof.reconciled()) return state;
            TailTransferProof expectedProof = proof;
            FrontierService successor = services.stream().filter(service ->
                    expectedProof.successorToken() == 0
                            ? service.beginFrame() == expectedProof.originCompletion().endFrame()
                                    && service.beginOrdinal()
                                            == expectedProof.originCompletion().endOrdinal() + 1
                            : service.token() == expectedProof.successorToken()).findFirst().orElse(null);
            if (successor == null) return state;
            validateTailSuccessor(proof.originCompletion(), successor);
            long token = proof.successorToken() == 0 ? successor.token() : proof.successorToken();
            if (successor.token() != token) {
                throw nativeEvidence("deferred transfer successor token is not exact");
            }
            return new DeferredReservationState(state.origin(), DeferredOwner.from(successor),
                    new TailTransferProof(proof.originCompletion(), token, true), state.consumed());
        }

        private void validateTailSuccessor(FrontierService origin, FrontierService successor)
                throws ValidationException {
            if (origin.token() == successor.token()
                    || successor.parentToken() != origin.currentParentToken()
                    || successor.depth() != origin.currentDepth()
                    || successor.beginFrame() != origin.endFrame()
                    || successor.beginOrdinal() != origin.endOrdinal() + 1
                    || successor.beginHookToken() != origin.endHookToken()
                    || successor.beginPc() != origin.endPc()
                    || !successor.beginSourceCpu().equals(origin.beginSourceCpu())
                    || successor.kind().equals(origin.kind())
                    || !matchesServiceRule(origin) || !matchesServiceRule(successor)) {
                throw nativeEvidence("deferred transfer lacks exact adjacent tail successor proof");
            }
        }

        private boolean matchesServiceRule(FrontierService service) {
            return profile.cutoffFrontierPolicy().serviceRules().stream()
                    .anyMatch(rule -> rule.matches(service, true) && rule.acceptsChipSources(service));
        }

        private void validateAttestedDeferredOwner(NativeDeferredServiceBegin origin,
                FrontierService currentOwner) throws ValidationException {
            if (!matchesServiceRule(currentOwner)) {
                throw nativeEvidence("attested deferred owner is outside the exact observer manifest");
            }
            boolean legalRoute = profile.cutoffFrontierPolicy().serviceRules().stream()
                    .filter(rule -> rule.state() == FrontierServiceState.COMPLETED)
                    .anyMatch(rule -> !rule.kind().equals(currentOwner.kind())
                            && Objects.equals(rule.endHookToken(), currentOwner.beginHookToken())
                            && Objects.equals(rule.endPc(), currentOwner.beginPc())
                            && rule.beginSourceCpu().equals(currentOwner.beginSourceCpu()));
            if (currentOwner.token() == origin.blockerToken()) {
                if (legalRoute) {
                    throw nativeEvidence("attested deferred successor reuses the immutable origin token");
                }
                return;
            }
            if (!legalRoute) {
                throw nativeEvidence("attested deferred owner has no configured one-hop tail route");
            }
        }

        private void recordNativePromotions(FrameNativeDiagnostics diagnostics)
                throws ValidationException {
            Map<Long, FrontierService> services = diagnostics.services().stream().collect(
                    java.util.stream.Collectors.toMap(FrontierService::token, value -> value));
            for (FrontierOwnedAncestryTransition owned
                    : diagnostics.rawAncestryTransitionInventory()) {
                NativeAncestryTransition transition = owned.event();
                FrontierService parent = services.get(transition.previousParentToken());
                if (parent == null || parent.state() != FrontierServiceState.COMPLETED
                        || parent.endFrame() == null || parent.endOrdinal() == null
                        || transition.frame() != parent.endFrame()
                        || transition.ordinal() != parent.endOrdinal() + 1
                        || transition.currentParentToken() != parent.currentParentToken()
                        || transition.currentDepth() != parent.currentDepth()
                        || transition.hookToken() != parent.endHookToken()
                        || !transition.sourceCpu().equals(parent.beginSourceCpu())
                        || transition.pc() != parent.endPc()) {
                    throw nativeEvidence("native promotion has no exact adjacent direct-parent completion");
                }
                List<NativeAncestryTransition> prior = nativePromotionEvidence.get(owned.ownerToken());
                if (prior == null) prior = List.of();
                if (!prior.isEmpty()) {
                    NativeAncestryTransition last = prior.getLast();
                    if (transition.previousParentToken() != last.currentParentToken()
                            || transition.previousDepth() != last.currentDepth()) {
                        throw nativeEvidence("native promotion history is not contiguous");
                    }
                }
                if (prior.size() == 7) {
                    throw nativeEvidence("native promotion history exceeds its bound");
                }
                List<NativeAncestryTransition> updated = new ArrayList<>(prior);
                updated.add(transition);
                nativePromotionEvidence.put(owned.ownerToken(), List.copyOf(updated));
            }
            long retained = nativePromotionEvidence.values().stream().mapToLong(List::size).sum();
            if (retained > MAX_CUTOFF_SERVICES * 7L) {
                throw nativeEvidence("retained native promotion evidence exceeds its bound");
            }
        }

        private void consumeNativePromotions(FrontierService service) throws ValidationException {
            List<NativeAncestryTransition> expected = nativePromotionEvidence.remove(service.token());
            if (expected == null) expected = List.of();
            List<NativeAncestryTransition> published = service.ancestryTransitions().stream()
                    .filter(transition -> transition.frame() >= metadata.fixture().firstFrame()).toList();
            if (!published.equals(expected)) {
                throw nativeEvidence("native service ancestry does not consume exact promotion evidence");
            }
        }

        private void managedBoundaries(int frame, FrameNativeDiagnostics diagnostics)
                throws ValidationException {
            List<ManagedBoundaryOperation> operations = new ArrayList<>();
            for (NativeManagedCorrelation correlation : diagnostics.managedCorrelations()) {
                for (NativeManagedEvent event : correlation.events()) {
                    if (event.eventKind() == 1 || event.eventKind() == 2) {
                        operations.add(new ManagedBoundaryOperation(event.ordinal(), event, null));
                    }
                }
            }
            for (FrontierService service : diagnostics.services()) {
                if ("M68K".equals(service.beginSourceCpu())
                        && service.state() == FrontierServiceState.RESET_CANCELLED) {
                    operations.add(new ManagedBoundaryOperation(service.endOrdinal(), null, service));
                }
            }
            operations.sort(Comparator.comparingLong(ManagedBoundaryOperation::ordinal));
            long priorOrdinal = -1;
            for (ManagedBoundaryOperation operation : operations) {
                if (operation.ordinal() <= priorOrdinal) {
                    throw nativeEvidence("native managed-service boundaries reuse an event ordinal");
                }
                priorOrdinal = operation.ordinal();
                if (operation.cancelled() != null) {
                    cancelManagedService(operation.cancelled());
                } else if (operation.event().eventKind() == 1) {
                    beginManagedService(frame, operation.event());
                } else {
                    endManagedService(frame, operation.event());
                }
            }
            for (FrontierService service : diagnostics.services()) {
                if ("M68K".equals(service.beginSourceCpu())
                        && service.state() == FrontierServiceState.COMPLETED) {
                    if (!releasedNativeCarriedTokens.remove(service.token())) {
                        NativeBeginCoordinate key = new NativeBeginCoordinate(
                                service.beginFrame(), service.beginOrdinal());
                        ManagedServiceEvidence evidence = completedManagedServices.remove(key);
                        if (evidence == null) {
                            throw nativeEvidence("M68K service has no retained managed begin/end evidence");
                        }
                        requireManagedService(evidence, service, true);
                    }
                }
                consumeNativePromotions(service);
            }
            managedEvidenceBound();
        }

        private void beginManagedService(int frame, NativeManagedEvent event) throws ValidationException {
            NativeBeginCoordinate key = new NativeBeginCoordinate(frame, event.ordinal());
            boolean duplicateCoordinate = completedManagedServices.containsKey(key)
                    || activeManagedServices.values().stream().anyMatch(evidence -> evidence.begin().equals(key));
            ManagedServiceEvidence evidence = new ManagedServiceEvidence(key, event, null, null);
            if (duplicateCoordinate || activeManagedServices.putIfAbsent(event.serviceToken(), evidence) != null) {
                throw nativeEvidence("native managed begin duplicates an active token or begin coordinate");
            }
            managedEvidenceBound();
        }

        private void endManagedService(int frame, NativeManagedEvent event) throws ValidationException {
            ManagedServiceEvidence begin = activeManagedServices.remove(event.serviceToken());
            List<NativeAncestryTransition> promotions = nativePromotionEvidence.get(event.serviceToken());
            long expectedParent = promotions == null || promotions.isEmpty()
                    ? begin == null ? -1 : begin.beginEvent().parentToken()
                    : promotions.getLast().currentParentToken();
            int expectedDepth = promotions == null || promotions.isEmpty()
                    ? begin == null ? -1 : begin.beginEvent().depth()
                    : promotions.getLast().currentDepth();
            if (begin == null || event.parentToken() != expectedParent
                    || event.serviceKind() != begin.beginEvent().serviceKind()
                    || event.depth() != expectedDepth) {
                throw nativeEvidence("native managed completion has no exact active begin");
            }
            ManagedServiceEvidence complete = new ManagedServiceEvidence(
                    begin.begin(), begin.beginEvent(), frame, event);
            if (completedManagedServices.putIfAbsent(begin.begin(), complete) != null) {
                throw nativeEvidence("native managed completion duplicates retained evidence");
            }
            managedEvidenceBound();
        }

        private void cancelManagedService(FrontierService service) throws ValidationException {
            ManagedServiceEvidence evidence = activeManagedServices.remove(service.token());
            if (evidence == null) {
                throw nativeEvidence("reset-cancelled M68K service has no active managed begin");
            }
            requireManagedService(evidence, service, false);
        }

        private void managedCutoff(List<FrontierService> terminalServices) throws ValidationException {
            for (FrontierService service : terminalServices) {
                if (!"M68K".equals(service.beginSourceCpu())) continue;
                if (continuedNativeCarriedTokens.contains(service.token())) continue;
                ManagedServiceEvidence evidence;
                if (service.state() == FrontierServiceState.OPEN) {
                    evidence = activeManagedServices.remove(service.token());
                    if (evidence == null) {
                        throw nativeEvidence("open cutoff M68K service has no active managed begin");
                    }
                    requireManagedService(evidence, service, false);
                } else if (service.state() == FrontierServiceState.COMPLETED) {
                    evidence = completedManagedServices.remove(new NativeBeginCoordinate(
                            service.beginFrame(), service.beginOrdinal()));
                    if (evidence == null) {
                        throw nativeEvidence("completed cutoff M68K service has no retained begin/end evidence");
                    }
                    requireManagedService(evidence, service, true);
                } else {
                    throw nativeEvidence("reset-cancelled M68K service was withheld until cutoff");
                }
            }
            if (!activeManagedServices.isEmpty() || !completedManagedServices.isEmpty()) {
                throw nativeEvidence("cutoff does not account for every retained M68K boundary");
            }
        }

        private void requireManagedService(ManagedServiceEvidence evidence, FrontierService service,
                boolean requireEnd) throws ValidationException {
            NativeManagedEvent begin = evidence.beginEvent();
            if (service.token() != begin.serviceToken()
                    || service.parentToken() != begin.parentToken()
                    || service.depth() != begin.depth()
                    || service.beginFrame() != evidence.begin().frame()
                    || service.beginOrdinal() != begin.ordinal()
                    || service.beginPc() != begin.pc()
                    || service.beginHookToken() != begin.hookToken()
                    || !service.beginSourceCpu().equals(begin.sourceCpu())) {
                throw nativeEvidence("M68K service does not match its managed begin evidence");
            }
            if (requireEnd) {
                NativeManagedEvent end = evidence.endEvent();
                if (end == null || !Objects.equals(service.endFrame(), evidence.endFrame())
                        || !Objects.equals(service.endOrdinal(), end.ordinal())
                        || !Objects.equals(service.endPc(), end.pc())
                        || !Objects.equals(service.endHookToken(), end.hookToken())
                        || service.token() != end.serviceToken()
                        || service.currentParentToken() != end.parentToken()
                        || service.currentDepth() != end.depth()) {
                    throw nativeEvidence("M68K service does not match its managed completion evidence");
                }
            } else if (evidence.endEvent() != null) {
                throw nativeEvidence("open/reset M68K service unexpectedly has managed completion evidence");
            }
        }

        private void managedEvidenceBound() throws ValidationException {
            if ((long) activeManagedServices.size() + completedManagedServices.size()
                    > MAX_CUTOFF_SERVICES) {
                throw nativeEvidence("retained native managed-service evidence exceeds its bound");
            }
        }

        private ValidationException nativeEvidence(String message) {
            return new ValidationException(ValidationException.Kind.STATE_INVALID, side, message);
        }

        private record NativeBeginCoordinate(int frame, long ordinal) {}
        private record NativeRawSlot(long coordinate, long ordinal) {}
        /** Derived only from immutable origin diagnostics plus ordinary native topology. */
        private record DeferredReservationState(NativeDeferredServiceBegin origin,
                DeferredOwner currentOwner, TailTransferProof transferProof, boolean consumed) {
            static DeferredReservationState atOrigin(NativeDeferredServiceBegin origin) {
                return new DeferredReservationState(origin,
                        DeferredOwner.provisional(origin.blockerToken(),
                                origin.blockerParentToken(), origin.blockerDepth()), null, false);
            }
        }
        private record DeferredOwner(long token, long parentToken, int depth, String kind,
                Integer beginFrame, Long beginOrdinal, Integer beginHookToken, Integer beginPc,
                String beginSourceCpu) {
            static DeferredOwner provisional(long token, long parentToken, int depth) {
                return new DeferredOwner(token, parentToken, depth,
                        null, null, null, null, null, null);
            }

            static DeferredOwner from(FrontierService service) {
                return new DeferredOwner(service.token(), service.currentParentToken(),
                        service.currentDepth(), service.kind(), service.beginFrame(),
                        service.beginOrdinal(), service.beginHookToken(), service.beginPc(),
                        service.beginSourceCpu());
            }

            boolean matches(FrontierService service) {
                return token == service.token() && parentToken == service.currentParentToken()
                        && depth == service.currentDepth()
                        && (kind == null || kind.equals(service.kind())
                                && beginFrame == service.beginFrame()
                                && beginOrdinal == service.beginOrdinal()
                                && beginHookToken == service.beginHookToken()
                                && beginPc == service.beginPc()
                                && beginSourceCpu.equals(service.beginSourceCpu()));
            }
        }
        private record TailTransferProof(FrontierService originCompletion,
                long successorToken, boolean reconciled) {}
        private record DeferredFrameEvidence(DeferredReservationState reservation,
                NativeManagedEvent consumeBegin) {
            static DeferredFrameEvidence empty() {
                return new DeferredFrameEvidence(null, null);
            }
        }
        private record ManagedServiceEvidence(NativeBeginCoordinate begin, NativeManagedEvent beginEvent,
                Integer endFrame, NativeManagedEvent endEvent) {}
        private record ManagedBoundaryOperation(long ordinal, NativeManagedEvent event,
                FrontierService cancelled) {}

        private void replayNativeFrameChips(FrameNativeDiagnostics diagnostics) throws ValidationException {
            List<FrontierService> resetRoots = diagnostics.resets().stream()
                    .map(reset -> diagnostics.services().stream()
                            .filter(service -> service.token() == reset.serviceToken())
                            .findFirst().orElseThrow(() -> new AssertionError(
                                    "validated native reset has no service")))
                    .toList();
            int resetIndex = 0;
            for (FrontierOwnedChip owned : diagnostics.rawChipInventory()) {
                while (resetIndex < resetRoots.size()
                        && resetRoots.get(resetIndex).beginOrdinal() < owned.event().ordinal()) {
                    nativeYmPort0Latch = 0;
                    nativeYmPort1Latch = 0;
                    resetIndex++;
                }
                if (owned.event().coordinate() <= previousNativeRawCoordinate) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "native frame chip coordinates are not globally increasing");
                }
                previousNativeRawCoordinate = owned.event().coordinate();
                replayNativeYm(owned.event());
            }
            while (resetIndex < resetRoots.size()) {
                nativeYmPort0Latch = 0;
                nativeYmPort1Latch = 0;
                resetIndex++;
            }
        }

        private void replayNativeCutoffChips(CutoffFrontier frontier) throws ValidationException {
            for (FrontierOwnedChip owned : frontier.nativeDiagnostics().rawChipInventory()) {
                if (owned.event().coordinate() <= previousNativeRawCoordinate) {
                    throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                            "native cutoff chip coordinates are not globally increasing");
                }
                previousNativeRawCoordinate = owned.event().coordinate();
                replayNativeYm(owned.event());
            }
            if (observes(ComparisonLayer.BOUNDARY_CHIP_STATE)
                    && (nativeYmPort0Latch != null
                            && !Objects.equals(nativeYmPort0Latch, frontier.ymPort0Latch())
                        || nativeYmPort1Latch != null
                            && !Objects.equals(nativeYmPort1Latch, frontier.ymPort1Latch()))) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "native YM latch replay disagrees with the terminal cutoff");
            }
        }

        private void replayNativeYm(FrontierChipEvent event) throws ValidationException {
            if (event.eventKind() != 3) return;
            Integer prior = event.port() == 0 ? nativeYmPort0Latch : nativeYmPort1Latch;
            if (prior != null && event.register() != prior) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "native YM write register disagrees with its prior address latch");
            }
            if (!event.data()) {
                if (event.port() == 0) nativeYmPort0Latch = event.value();
                else nativeYmPort1Latch = event.value();
            }
        }

        private record NativeResetExpectation(int frame, boolean power, long serviceOrdinal) {}

        void finish() throws ValidationException {
            if (!baseline || !terminal) ordinal("capture ended without baseline or terminal");
        }

        private void state(NormalizedState state) throws ValidationException {
            try {
                profile.validateState(state);
            } catch (RuntimeException failure) {
                throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                        "normalized state does not match the exact profile inventory", failure);
            }
            if (observes(ComparisonLayer.OWNERSHIP)) {
                for (RoleState role : state.roles()) {
                    OwnerRef owner = liveOwners.get(role.role());
                    if (owner == null || role.active() != (owner.origin() != OwnerOrigin.NONE)) {
                        throw new ValidationException(ValidationException.Kind.STATE_INVALID, side,
                                "normalized role activity does not match its exact live owner");
                    }
                }
                stateObservationRequired = false;
            }
        }

        private void baselineOwners(Baseline value) throws ValidationException {
            if (!value.roleOwners().equals(profile.baselineRoleOwners())) {
                throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                        "baseline role owners do not match the exact profile baseline");
            }
            for (RoleOwner roleOwner : value.roleOwners()) {
                liveOwners.put(roleOwner.role(), roleOwner.owner());
                savedOwners.put(roleOwner.role(), new ArrayDeque<>());
            }
        }

        private void segment(Frame frame) throws ValidationException {
            List<ManifestSegment> segments = metadata.fixture().segments();
            while (segmentIndex < segments.size()
                    && frame.absoluteFrame() >= segments.get(segmentIndex).exclusiveEnd()) {
                segmentIndex++;
            }
            String expected = null;
            if (segmentIndex < segments.size()) {
                ManifestSegment segment = segments.get(segmentIndex);
                if (frame.absoluteFrame() >= segment.firstFrame()) expected = segment.id();
            }
            if (!Objects.equals(expected, frame.segment())) {
                throw new ValidationException(ValidationException.Kind.SEGMENT_INVALID, side,
                        "frame segment does not exactly match the fixture interval");
            }
        }

        private void lifecycle(Lifecycle lifecycle) throws ValidationException {
            if (lifecycle.absoluteFrame() < metadata.fixture().firstFrame()
                    || lifecycle.absoluteFrame() >= metadata.fixture().exclusiveEnd()
                    || lifecycle.absoluteFrame() < previousSourceFrame) {
                throw new ValidationException(ValidationException.Kind.LIFECYCLE_INVALID, side,
                        "lifecycle coordinates are outside or regress within the fixture interval");
            }
            previousSourceFrame = lifecycle.absoluteFrame();
            LifecycleRule rule = profile.lifecycleRules().get(lifecycle.kind());
            try {
                if (rule == null || !rule.kind().equals(lifecycle.kind())
                        || !rule.detailFields().equals(lifecycle.details().keySet().stream().toList())) {
                    throw new IllegalArgumentException("lifecycle marker does not match the profile rule");
                }
            } catch (RuntimeException failure) {
                throw new ValidationException(ValidationException.Kind.LIFECYCLE_INVALID, side,
                        "lifecycle does not match the exact profile rule", failure);
            }
            if (observes(ComparisonLayer.OWNERSHIP)) {
                try {
                    if (rule == null || !rule.ownershipRoleSets().contains(lifecycle.ownershipTransitions().stream()
                            .map(LifecycleOwnership::role).toList())) {
                        throw new IllegalArgumentException("lifecycle ownership does not match the profile rule");
                    }
                } catch (RuntimeException failure) {
                    throw new ValidationException(ValidationException.Kind.LIFECYCLE_INVALID, side,
                            "lifecycle does not match the exact profile rule", failure);
                }
                boolean changed = false;
                for (LifecycleOwnership ownership : lifecycle.ownershipTransitions()) {
                    changed |= lifecycleTransition(ownership, rule.ownershipAction());
                }
                stateObservationRequired |= changed && observes(ComparisonLayer.STATE);
            }
        }

        private boolean lifecycleTransition(LifecycleOwnership transition,
                LifecycleOwnershipAction action) throws ValidationException {
            OwnerRef current = liveOwners.get(transition.role());
            if (current == null || !current.equals(transition.displacedOwner())) {
                throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                        "lifecycle displaced owner is not the role's current live owner");
            }
            ArrayDeque<OwnerRef> saved = savedOwners.get(transition.role());
            OwnerRef expectedFinal = switch (action) {
                case SAVE_CURRENT -> current;
                case RESTORE_SAVED -> {
                    if (saved.isEmpty()) {
                        throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                                "lifecycle restore has no saved owner");
                    }
                    yield saved.peekLast();
                }
                case RELEASE_TO_NONE -> noneOwner();
                case NONE -> throw new ValidationException(ValidationException.Kind.LIFECYCLE_INVALID, side,
                        "no-transition lifecycle carries ownership changes");
            };
            if (!expectedFinal.equals(transition.finalOwner())) {
                throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                        "lifecycle final owner does not match the profile-owned action");
            }
            if (action == LifecycleOwnershipAction.SAVE_CURRENT) {
                if (saved.size() == profile.restoreStackPolicy().maximumDepth()) {
                    throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                            "saved owner stack exceeds the profile-owned bound");
                }
                saved.addLast(current);
            }
            if (action == LifecycleOwnershipAction.RESTORE_SAVED) saved.removeLast();
            liveOwners.put(transition.role(), expectedFinal);
            peakSavedOwners = Math.max(peakSavedOwners,
                    savedOwners.values().stream().mapToInt(ArrayDeque::size).sum());
            return !current.equals(expectedFinal);
        }

        private void sourceCoordinate(int absoluteFrame) throws ValidationException {
            if (absoluteFrame < previousSourceFrame) {
                throw new ValidationException(ValidationException.Kind.LIFECYCLE_INVALID, side,
                        "frame coordinates regress behind a lifecycle marker in source order");
            }
            previousSourceFrame = absoluteFrame;
        }

        private NativeSoundIdentity requestIdentity(Request request) throws ValidationException {
            try {
                NativeSoundIdentity expected = profile.resolveRequest(new RawAudioRequest(
                        request.ownerClass(), request.nativeId(), request.queueSource(), request.queueSlot()));
                if (expected.ownerClass() != request.ownerClass()
                        || expected.nativeId() != request.nativeId()
                        || !expected.contentKey().equals(request.contentKey())) {
                    throw new IllegalArgumentException("resolved request identity differs");
                }
                return expected;
            } catch (RuntimeException failure) {
                throw new ValidationException(ValidationException.Kind.REQUEST_IDENTITY_INVALID, side,
                        "request does not match the exact profile-resolved native identity", failure);
            }
        }

        private void decision(Decision decision) throws ValidationException {
            for (HardwareRole role : decision.requestedRoles()) {
                if (!hardwareRoles.contains(role)) {
                    throw new ValidationException(ValidationException.Kind.ROLE_INVALID, side,
                            "decision requests a role outside the profile hardware inventory");
                }
            }
            if (observes(ComparisonLayer.OWNERSHIP)) {
                for (RoleDecision role : decision.roleDecisions()) {
                    if (!hardwareRoles.contains(role.role())) {
                        throw new ValidationException(ValidationException.Kind.ROLE_INVALID, side,
                                "role decision is outside the profile hardware inventory");
                    }
                }
            }
            NativeSoundIdentity requested = pendingRequests.get(decision.requestOrdinal());
            if (requested == null) {
                throw new ValidationException(ValidationException.Kind.DECISION_REFERENCE_INVALID, side,
                        "decision does not reference one unique captured request");
            }
            NativeSoundIdentity resolved = new NativeSoundIdentity(requested.ownerClass(),
                    decision.resolvedContentKey(), decision.resolvedNativeId());
            List<NativeSoundIdentity> allowed = decisionResolutions.get(requested);
            if (allowed == null || !allowed.contains(resolved)) {
                throw new ValidationException(ValidationException.Kind.RESOLUTION_INVALID, side,
                        "decision resolution is outside the profile-owned transformation contract");
            }
            if (observes(ComparisonLayer.OWNERSHIP)) {
                OwnershipTransition transition = ownershipTransitions.get(decision.reason());
                if (transition == null
                        || decision.accepted() != (transition != OwnershipTransition.REJECT_PRESERVE)) {
                    throw new ValidationException(ValidationException.Kind.OWNERSHIP_TRANSITION_INVALID, side,
                            "decision acceptance and reason do not select an exact profile transition");
                }
                OwnerRef admitted = new OwnerRef(resolved.ownerClass(), resolved.contentKey(), resolved.nativeId(),
                        OwnerOrigin.REQUEST, decision.requestOrdinal());
                for (RoleDecision role : decision.roleDecisions()) transition(role, admitted, transition);
            }
            pendingRequests.remove(decision.requestOrdinal());
            completedRequests++;
        }

        private void transition(RoleDecision decision, OwnerRef admitted,
                OwnershipTransition transition) throws ValidationException {
            OwnerRef current = liveOwners.get(decision.role());
            if (current == null || !current.equals(decision.displacedOwner())) {
                throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                        "displaced owner is not the role's current live owner");
            }
            ArrayDeque<OwnerRef> saved = savedOwners.get(decision.role());
            OwnerRef expectedFinal = switch (transition) {
                case ACQUIRE_REQUEST -> admitted;
                case REJECT_PRESERVE -> current;
                case RELEASE_TO_NONE -> noneOwner();
                case SAVE_AND_ACQUIRE_REQUEST -> {
                    if (saved.size() == profile.restoreStackPolicy().maximumDepth()) {
                        throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                                "saved owner stack exceeds the profile-owned bound");
                    }
                    yield admitted;
                }
            };
            if (!expectedFinal.equals(decision.finalOwner())) {
                throw new ValidationException(ValidationException.Kind.OWNER_INVALID, side,
                        "final owner does not match the profile-owned transition");
            }
            if (transition == OwnershipTransition.SAVE_AND_ACQUIRE_REQUEST) saved.addLast(current);
            liveOwners.put(decision.role(), expectedFinal);
            peakSavedOwners = Math.max(peakSavedOwners,
                    savedOwners.values().stream().mapToInt(ArrayDeque::size).sum());
        }

        private static OwnerRef noneOwner() {
            return new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1);
        }

        private boolean compares(ComparisonLayer layer) {
            return layers.isCompared(layer);
        }

        private boolean observes(ComparisonLayer layer) {
            return metadata.producerObservationInventory().isObserved(layer);
        }

        private ValidationDiagnostics diagnostics() {
            return new ValidationDiagnostics(peakPendingRequests, pendingRequests.size(), peakSavedOwners,
                    liveOwners.size(), completedRequests);
        }

        private void ordinal(String message) throws ValidationException {
            throw new ValidationException(ValidationException.Kind.ORDINAL_INVALID, side, message);
        }
    }

    private static final class ContextCollector {
        private final ArrayDeque<RecordView> referenceBefore = new ArrayDeque<>();
        private final ArrayDeque<RecordView> engineBefore = new ArrayDeque<>();
        private final List<RecordView> referenceAfter = new ArrayList<>(CONTEXT_LIMIT);
        private final List<RecordView> engineAfter = new ArrayList<>(CONTEXT_LIMIT);
        private RecordView referenceCurrent;
        private RecordView engineCurrent;
        private boolean mismatched;

        private ContextCollector(boolean metadataMismatch) {
            mismatched = metadataMismatch;
        }

        void before(RecordView reference, RecordView engine) {
            appendBounded(referenceBefore, reference);
            appendBounded(engineBefore, engine);
        }

        void mismatch(RecordView reference, RecordView engine) {
            mismatched = true;
            referenceCurrent = reference;
            engineCurrent = engine;
        }

        void after(RecordView reference, RecordView engine) {
            if (!mismatched) throw new IllegalStateException("context has no mismatch");
            appendAfter(referenceAfter, reference);
            appendAfter(engineAfter, engine);
        }

        Context reference() {
            return new Context(List.copyOf(referenceBefore), referenceCurrent, List.copyOf(referenceAfter));
        }

        Context engine() {
            return new Context(List.copyOf(engineBefore), engineCurrent, List.copyOf(engineAfter));
        }

        private static void appendBounded(ArrayDeque<RecordView> values, RecordView value) {
            if (value == null) return;
            if (values.size() == CONTEXT_LIMIT) values.removeFirst();
            values.addLast(value);
        }

        private static void appendAfter(List<RecordView> values, RecordView value) {
            if (value != null && values.size() < CONTEXT_LIMIT) values.add(value);
        }
    }
}
