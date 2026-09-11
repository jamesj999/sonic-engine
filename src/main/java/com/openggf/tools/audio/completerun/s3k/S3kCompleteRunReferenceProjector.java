package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import com.openggf.tools.audio.completerun.CompleteRunAudioCaptureStore;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transactional projection of validated S3K native observations into canonical records. */
public final class S3kCompleteRunReferenceProjector {
    // Closed transcription of games.s3k.hooks in the reviewed manifest at
    // tools/tracechaser commit 36502baf4ed9fce1c8ae8da6c27827cac9c5df38,
    // SHA-256 ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0.
    private static final Map<Integer, ManifestHook> S3K_HOOKS = Map.ofEntries(
            hook(1, 1, 0, HookAction.PUSH_BEGIN, 6, 0),
            hook(2, 1, 56, HookAction.PUSH_BEGIN, 3, 0),
            hook(4, 1, 56, HookAction.TAIL_POP_PUSH, 3, 6),
            hook(17, 1, 56, HookAction.TAIL_POP_PUSH, 3, 9),
            hook(21, 1, 56, HookAction.PUSH_BEGIN, 3, 7),
            hook(25, 1, 69, HookAction.POP_END_AT_PC, 0, 12),
            hook(3, 1, 132, HookAction.POP_END_AT_PC, 0, 3),
            hook(23, 1, 283, HookAction.PUSH_BEGIN, 11, 3),
            hook(24, 1, 289, HookAction.TAIL_POP_PUSH, 12, 11),
            hook(26, 1, 289, HookAction.TAIL_POP_PUSH, 12, 12),
            hook(18, 1, 4234, HookAction.PUSH_BEGIN, 9, 0),
            hook(19, 1, 4234, HookAction.TAIL_POP_PUSH, 9, 6),
            hook(9, 1, 4256, HookAction.PUSH_BEGIN, 9, 0),
            hook(20, 1, 4256, HookAction.TAIL_POP_PUSH, 9, 9),
            hook(22, 1, 4256, HookAction.TAIL_POP_PUSH, 9, 7),
            hook(10, 1, 4300, HookAction.PUSH_BEGIN, 7, 0),
            hook(11, 1, 4300, HookAction.TAIL_POP_PUSH, 7, 9),
            hook(12, 1, 4357, HookAction.POP_END_AT_PC, 0, 7),
            hook(13, 1, 4390, HookAction.PUSH_BEGIN, 10, 0),
            hook(14, 1, 4432, HookAction.PUSH_BEGIN, 8, 0),
            hook(15, 1, 4432, HookAction.TAIL_POP_PUSH, 8, 10),
            hook(16, 1, 4453, HookAction.POP_END_AT_PC, 0, 8),
            hook(5, 2, 642, HookAction.PUSH_BEGIN, 5, 0),
            hook(6, 2, 652, HookAction.POP_END_AT_PC, 0, 5),
            hook(7, 2, 4814, HookAction.PUSH_BEGIN, 2, 0),
            hook(8, 2, 4934, HookAction.POP_END_AT_PC, 0, 2));

    private enum HookAction {
        PUSH_BEGIN, POP_END_AT_PC, TAIL_POP_PUSH, DIRECT_PARENT_PROMOTION, OBSERVATION_MARKER
    }
    private record ManifestHook(int token, int sourceCpu, int pc, HookAction action,
            int serviceKind, int expectedKind) { }

    private static Map.Entry<Integer, ManifestHook> hook(int token, int sourceCpu, int pc,
            HookAction action, int serviceKind, int expectedKind) {
        return Map.entry(token, new ManifestHook(
                token, sourceCpu, pc, action, serviceKind, expectedKind));
    }

    public record Projection(List<CompleteRunAudioTrace.Record> records) {
        public Projection { records = List.copyOf(records); }
    }

    Projection projectPrefixForTesting(Path raw, Path rom) throws IOException {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom);
        Transaction transaction = new Transaction(catalog);
        S3kCompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, transaction);
        return transaction.result();
    }

    void projectPrefixForTesting(Path raw, Path rom, Path output) throws IOException {
        Projection projection = projectPrefixForTesting(raw, rom);
        CompleteRunAudioTrace.Metadata metadata = syntheticMetadata(projection.records());
        projectToStore(raw, rom, output, metadata, null, false);
    }

    void project(Path raw, Path rom, Path output, CompleteRunAudioTrace.Metadata metadata,
            CompleteRunAudioTrace.NativeCapabilitySummary capability) throws IOException {
        metadata.validateFixtureProfile(S3kCompleteRunAudioProfile.profile());
        projectToStore(raw, rom, output, metadata, capability, true);
    }

    private void projectToStore(Path raw, Path rom, Path output, CompleteRunAudioTrace.Metadata metadata,
            CompleteRunAudioTrace.NativeCapabilitySummary capability, boolean full) throws IOException {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom);
        var writer = new CompleteRunAudioCaptureStore().writeNew(output, metadata);
        Transaction transaction = new Transaction(catalog, writer, capability,
                metadata.observerRuntimeIdentity()
                        instanceof CompleteRunAudioTrace.BufferedNativeObserverIdentity);
        boolean complete = false;
        try {
            if (full) S3kCompleteRunReferenceRawAdapter.scan(raw, transaction);
            else S3kCompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, transaction);
            complete = true;
        } finally {
            if (!complete) writer.abort();
        }
    }

    private static CompleteRunAudioTrace.Metadata syntheticMetadata(
            List<CompleteRunAudioTrace.Record> records) {
        var baseline = (CompleteRunAudioTrace.Baseline) records.getFirst();
        int end = ((CompleteRunAudioTrace.Frame) records.get(records.size() - 2)).absoluteFrame() + 1;
        var fixture = S3kCompleteRunAudioProfile.profile().fixture();
        var interval = new CompleteRunAudioTrace.CompleteRunFixture(fixture.romSha1(), fixture.romCrc32(),
                fixture.bk2Sha256(), fixture.bk2RowCount(), fixture.runManifestSha256(),
                List.of(new CompleteRunAudioTrace.ManifestSegment("test-prefix", baseline.absoluteFrame(), end)),
                baseline.absoluteFrame(), end);
        return syntheticMetadata(interval, "test-only." + S3kCompleteRunAudioProfile.ID);
    }

    static CompleteRunAudioTrace.Metadata fullSyntheticMetadataForTesting() {
        return syntheticMetadata(S3kCompleteRunAudioProfile.profile().fixture(), S3kCompleteRunAudioProfile.ID);
    }

    private static CompleteRunAudioTrace.Metadata syntheticMetadata(
            CompleteRunAudioTrace.CompleteRunFixture interval, String profileId) {
        var pinned = S3kCompleteRunAudioProfile.profile();
        return new CompleteRunAudioTrace.Metadata(CompleteRunAudioTrace.SCHEMA,
                profileId, interval,
                CompleteRunAudioTrace.ProducerKind.OPENGGF,
                new CompleteRunAudioTrace.ProducerRuntimeIdentity("OpenGGF", "test-only", "OpenGGF",
                        "test-only", "SMPS", "test-only", Map.of(
                                CompleteRunAudioTrace.RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64))),
                new CompleteRunAudioTrace.CallbackObserverIdentity("openggf.projector.test.v1"),
                new CompleteRunAudioTrace.ObserverProof("test-only", "test-only",
                        List.of(new CompleteRunAudioTrace.CallbackProof("projection", 1))),
                new CompleteRunAudioTrace.ChunkPolicy(4096, "gzip", 0), pinned.hardwareRoles(),
                pinned.stateInventory(), pinned.comparisonLayerInventory(),
                pinned.producerObservationInventories().get(CompleteRunAudioTrace.ProducerKind.OPENGGF));
    }

    private static final class Transaction implements S3kCompleteRunReferenceRawAdapter.Sink {
        private final S3kCompleteRunAssetCatalog catalog;
        private final List<CompleteRunAudioTrace.Record> staged = new ArrayList<>();
        private final CompleteRunAudioCaptureStore.Writer writer;
        private final CompleteRunAudioTrace.NativeCapabilitySummary capability;
        private final boolean retainNativeDiagnostics;
        private boolean started;
        private boolean committed;
        private int ymPort0Latch;
        private int ymPort1Latch;
        private long nextChipOrdinal;
        private long nextEventCoordinate;
        private final List<ObservedService> liveServices = new ArrayList<>();
        private final List<ObservedService> pendingServices = new ArrayList<>();
        private final Map<Long, ObservedService> servicesByBegin = new LinkedHashMap<>();
        private ObservedService resetService;
        private int nextToken;

        private Transaction(S3kCompleteRunAssetCatalog catalog) { this(catalog, null, null, true); }

        private Transaction(S3kCompleteRunAssetCatalog catalog, CompleteRunAudioCaptureStore.Writer writer,
                CompleteRunAudioTrace.NativeCapabilitySummary capability,
                boolean retainNativeDiagnostics) {
            this.catalog = catalog;
            this.writer = writer;
            this.capability = capability;
            this.retainNativeDiagnostics = retainNativeDiagnostics;
        }

        @Override public void begin() { started = true; }
        @Override public void header(S3kCompleteRunReferenceRawAdapter.Header value) { }

        @Override public void baseline(S3kCompleteRunReferenceRawAdapter.RawBoundary value) throws IOException {
            requireStarted();
            if (!value.activeServices().isEmpty() || !value.pendingDescendants().isEmpty()) {
                throw new IllegalArgumentException("S3K baseline frontier is not empty");
            }
            ymPort0Latch = value.ymPort0Latch();
            ymPort1Latch = value.ymPort1Latch();
            CompleteRunAudioTrace.CutoffNativeDiagnostics diagnostics = retainNativeDiagnostics
                    ? CompleteRunAudioTrace.CutoffFrontier.fromNative(List.of(), List.of(), List.of(), List.of(),
                            value.ymPort0Latch(), value.ymPort1Latch(), value.nativeArmEpoch(), value.nativeArmed(),
                            normalize(value.driverState()), sha256(value.driverState())).nativeDiagnostics()
                    : null;
            append(new CompleteRunAudioTrace.Baseline(value.row(), null, null,
                    new CompleteRunAudioTrace.BoundaryFrontier(
                            null, null, null, diagnostics, null, null)));
        }

        @Override public void frame(S3kCompleteRunReferenceRawAdapter.RawFrame value) throws IOException {
            requireStarted();
            List<CompleteRunAudioTrace.ChipEvent> chips = new ArrayList<>();
            List<ObservedService> completed = new ArrayList<>();
            long frameCoordinateBase = nextEventCoordinate;
            nextToken = 1;
            for (ObservedService active : liveServices) {
                while (nextToken == active.token()) nextToken = nextToken(nextToken);
            }
            for (int index = 0; index < value.events().size(); index++) {
                var event = value.events().get(index);
                long coordinate = Math.addExact(nextEventCoordinate, event.ordinal());
                ObservedEvent observed = new ObservedEvent(value.row(), coordinate, event);
                observe(observed, value.events(), index, chips, completed);
            }
            if (resetService != null || liveServices.stream().anyMatch(service -> service.snapshot != null)) {
                throw new IllegalArgumentException("S3K raw snapshot crosses a frame boundary");
            }
            pendingServices.addAll(completed);
            pendingServices.removeIf(service -> liveServices.stream()
                    .noneMatch(active -> active.token() == service.rootToken));
            servicesByBegin.clear();
            java.util.stream.Stream.concat(liveServices.stream(), pendingServices.stream())
                    .forEach(service -> servicesByBegin.put(service.begin.coordinate(), service));
            completed.forEach(service -> servicesByBegin.putIfAbsent(service.begin.coordinate(), service));
            nextEventCoordinate = Math.addExact(nextEventCoordinate, value.events().size());
            CompleteRunAudioTrace.FrameNativeDiagnostics nativeDiagnostics = retainNativeDiagnostics
                    ? frameDiagnostics(value.row(), frameCoordinateBase, nextEventCoordinate, completed)
                    : null;
            append(new CompleteRunAudioTrace.Frame(value.row(), segment(value.row()), null,
                    null, null, null, null, chips, nativeDiagnostics));
            java.util.stream.Stream.concat(completed.stream(), liveServices.stream()).distinct()
                    .forEach(ObservedService::clearFrameEvidence);
        }

        @Override public void cutoff(S3kCompleteRunReferenceRawAdapter.RawBoundary value) throws IOException {
            requireStarted();
            List<CompleteRunAudioTrace.FrontierService> active = value.activeServices().stream()
                    .map(this::frontierService).toList();
            List<CompleteRunAudioTrace.FrontierService> pending = value.pendingDescendants().stream()
                    .map(this::frontierService).toList();
            List<CompleteRunAudioTrace.FrontierService> services = java.util.stream.Stream
                    .concat(active.stream(), pending.stream()).toList();
            List<CompleteRunAudioTrace.FrontierOwnedChip> chips = new ArrayList<>();
            List<CompleteRunAudioTrace.FrontierOwnedSnapshot> snapshots = new ArrayList<>();
            for (int serviceIndex = 0; serviceIndex < services.size(); serviceIndex++) {
                var service = services.get(serviceIndex);
                for (var event : service.chipEvents()) {
                    chips.add(new CompleteRunAudioTrace.FrontierOwnedChip(
                            service.token(), serviceIndex, event));
                }
                for (var snapshot : service.snapshots()) {
                    snapshots.add(new CompleteRunAudioTrace.FrontierOwnedSnapshot(
                            service.token(), serviceIndex, snapshot));
                }
            }
            chips.sort(Comparator.comparingLong(valueChip -> valueChip.event().coordinate()));
            CompleteRunAudioTrace.CutoffFrontier projected = CompleteRunAudioTrace.CutoffFrontier.fromNative(
                    active, pending, chips, snapshots,
                    value.ymPort0Latch(), value.ymPort1Latch(), value.nativeArmEpoch(), value.nativeArmed(),
                    normalize(value.driverState()), sha256(value.driverState()));
            append(new CompleteRunAudioTrace.CutoffFrontier(null, null, null,
                    retainNativeDiagnostics ? projected.nativeDiagnostics() : null,
                    null, null, null));
        }

        @Override public void commit() throws IOException {
            if (writer != null) {
                writer.finish(capability);
                writer.close();
            }
            committed = true;
        }

        @Override public void abort() throws IOException {
            if (writer != null) writer.abort();
            else staged.clear();
            committed = false;
        }

        private void append(CompleteRunAudioTrace.Record record) throws IOException {
            if (writer != null) writer.append(record);
            else staged.add(record);
        }

        private Projection result() {
            if (!committed) throw new IllegalStateException("S3K projection did not commit");
            return new Projection(staged);
        }

        private CompleteRunAudioTrace.NormalizedState normalize(byte[] raw) {
            var result = S3kCompleteRunStateNormalizer.normalizeReference(
                    S3kCompleteRunStateDecoder.decode(raw, catalog), catalog.assets());
            S3kCompleteRunAudioProfile.profile().validateState(result);
            return result;
        }

        private void requireStarted() {
            if (!started) throw new IllegalStateException("S3K projection transaction is not open");
        }

        private CompleteRunAudioTrace.FrontierService frontierService(
                S3kCompleteRunReferenceRawAdapter.RawService raw) {
            ObservedService serviceEvidence = servicesByBegin.get(raw.beginCoordinate());
            if (serviceEvidence == null || serviceEvidence.token() != raw.token()) {
                throw new IllegalArgumentException("S3K cutoff service begin was not observed in the raw rows");
            }
            ObservedEvent begin = serviceEvidence.begin;
            ObservedEvent end = raw.complete() || raw.cancelled()
                    ? requireEvent(serviceEvidence.end, "S3K cutoff service end") : null;
            boolean observedComplete = serviceEvidence.end != null;
            if (observedComplete != (raw.complete() || raw.cancelled())
                    || (!observedComplete && !liveServices.contains(serviceEvidence))) {
                throw new IllegalArgumentException(
                        "S3K cutoff service lifecycle differs from observed events");
            }
            requireBoundaryEvent(begin, raw, true);
            if (end != null) requireBoundaryEvent(end, raw, false);
            requireChipEvidence(serviceEvidence, raw.chips());
            requireSnapshotEvidence(serviceEvidence, raw.snapshots());
            List<CompleteRunAudioTrace.FrontierChipEvent> chips = raw.chips().stream().map(chip ->
                    new CompleteRunAudioTrace.FrontierChipEvent(chip.coordinate(), chip.nativeOrdinal(),
                            cpu(chip.sourceCpu()), Math.toIntExact(chip.pc()), chip.eventKind(), chip.subject(),
                            chip.value(), chip.data(), chip.eventKind() == 3 ? chip.port() : null,
                            chip.eventKind() == 3 ? chip.register() : null)).toList();
            List<CompleteRunAudioTrace.FrontierSnapshot> snapshots = raw.snapshots().stream().map(snapshot ->
                    new CompleteRunAudioTrace.FrontierSnapshot(snapshot.rangeId(), cpu(snapshot.sourceCpu()),
                            Math.toIntExact(snapshot.pc()), unsigned(snapshot.bytes()))).toList();
            List<ObservedTransition> observedTransitions = serviceEvidence.transitions;
            if (observedTransitions.size() != raw.ancestryTransitions().size()) {
                throw new IllegalArgumentException("S3K cutoff ancestry transition coordinates are incomplete");
            }
            List<CompleteRunAudioTrace.NativeAncestryTransition> ancestry = new ArrayList<>();
            for (int index = 0; index < raw.ancestryTransitions().size(); index++) {
                var transition = raw.ancestryTransitions().get(index);
                ObservedTransition observed = observedTransitions.get(index);
                var event = observed.event();
                if (event.ordinal() != transition.nativeOrdinal()
                        || observed.coordinate() != transition.coordinate()
                        || observed.previousParent() != transition.previousParentToken()
                        || observed.previousDepth() != transition.previousDepth()
                        || event.serviceToken() != raw.token()
                        || event.serviceKind() != raw.kind()
                        || event.parentToken() != transition.currentParentToken()
                        || event.depth() != transition.currentDepth()
                        || event.subject() != transition.hookToken()
                        || event.sourceCpu() != transition.sourceCpu()
                        || event.pc() != transition.pc()) {
                    throw new IllegalArgumentException(
                            "S3K cutoff ancestry transition differs from its observed event");
                }
                ancestry.add(new CompleteRunAudioTrace.NativeAncestryTransition(
                        transition.coordinate(), observed.frame(), transition.nativeOrdinal(),
                        transition.previousParentToken(), transition.previousDepth(),
                        transition.currentParentToken(), transition.currentDepth(), transition.hookToken(),
                        cpu(transition.sourceCpu()), Math.toIntExact(transition.pc())));
            }
            CompleteRunAudioTrace.FrontierServiceState state = raw.cancelled()
                    ? CompleteRunAudioTrace.FrontierServiceState.RESET_CANCELLED
                    : raw.complete() ? CompleteRunAudioTrace.FrontierServiceState.COMPLETED
                            : CompleteRunAudioTrace.FrontierServiceState.OPEN;
            return new CompleteRunAudioTrace.FrontierService(raw.token(), raw.parentToken(), raw.depth(),
                    kind(raw.kind()), state, begin.frame(), begin.event().ordinal(), Math.toIntExact(raw.beginPc()),
                    raw.beginHookToken(), cpu(raw.beginSourceCpu()), end == null ? null : end.frame(),
                    end == null ? null : end.event().ordinal(), end == null ? null : Math.toIntExact(raw.endPc()),
                    end == null ? null : raw.endHookToken(), snapshots, chips, raw.currentParentToken(),
                    raw.currentDepth(), ancestry);
        }

        private CompleteRunAudioTrace.FrameNativeDiagnostics frameDiagnostics(int frame,
                long firstCoordinate, long exclusiveCoordinate, List<ObservedService> completed) {
            List<ObservedService> candidates = java.util.stream.Stream.concat(
                    completed.stream(), liveServices.stream())
                    .distinct()
                    .sorted(Comparator.comparingLong(service -> service.begin.coordinate())).toList();
            List<ObservedService> retained = candidates;
            List<CompleteRunAudioTrace.FrontierService> services = retained.stream()
                    .map(this::frameService).toList();
            List<CompleteRunAudioTrace.FrontierOwnedChip> chips = new ArrayList<>();
            List<CompleteRunAudioTrace.FrontierOwnedSnapshot> snapshots = new ArrayList<>();
            List<CompleteRunAudioTrace.FrontierOwnedAncestryTransition> transitions = new ArrayList<>();
            List<CompleteRunAudioTrace.NativeResetDiagnostic> resets = new ArrayList<>();
            for (int serviceIndex = 0; serviceIndex < services.size(); serviceIndex++) {
                CompleteRunAudioTrace.FrontierService service = services.get(serviceIndex);
                for (CompleteRunAudioTrace.FrontierChipEvent event : service.chipEvents()) {
                    if (event.coordinate() >= firstCoordinate && event.coordinate() < exclusiveCoordinate) {
                        chips.add(new CompleteRunAudioTrace.FrontierOwnedChip(
                                service.token(), serviceIndex, event));
                    }
                }
                for (CompleteRunAudioTrace.FrontierSnapshot snapshot : service.snapshots()) {
                    snapshots.add(new CompleteRunAudioTrace.FrontierOwnedSnapshot(
                            service.token(), serviceIndex, snapshot));
                }
                for (CompleteRunAudioTrace.NativeAncestryTransition transition
                        : service.ancestryTransitions()) {
                    if (transition.frame() == frame) {
                        transitions.add(new CompleteRunAudioTrace.FrontierOwnedAncestryTransition(
                                service.token(), transition));
                    }
                }
                if ("RESET".equals(service.beginSourceCpu())) {
                    resets.add(new CompleteRunAudioTrace.NativeResetDiagnostic(
                            service.token(), retained.get(serviceIndex).begin.event().flags() == 1));
                }
            }
            chips.sort(Comparator.comparingLong(owned -> owned.event().coordinate()));
            return new CompleteRunAudioTrace.FrameNativeDiagnostics(services, chips, snapshots,
                    resets, List.of(), List.of(), transitions);
        }

        private CompleteRunAudioTrace.FrontierService frameService(ObservedService observed) {
            ObservedEvent begin = observed.begin;
            ObservedEvent end = observed.end;
            CompleteRunAudioTrace.FrontierServiceState state = end == null
                    ? CompleteRunAudioTrace.FrontierServiceState.OPEN
                    : end.event().flags() == 2
                            ? CompleteRunAudioTrace.FrontierServiceState.RESET_CANCELLED
                            : CompleteRunAudioTrace.FrontierServiceState.COMPLETED;
            List<CompleteRunAudioTrace.NativeAncestryTransition> ancestry = observed.transitions.stream()
                    .map(transition -> new CompleteRunAudioTrace.NativeAncestryTransition(
                            transition.coordinate(), transition.frame(), transition.event().ordinal(),
                            transition.previousParent(), transition.previousDepth(),
                            transition.event().parentToken(), transition.event().depth(),
                            transition.event().subject(), cpu(transition.event().sourceCpu()),
                            Math.toIntExact(transition.event().pc()))).toList();
            return new CompleteRunAudioTrace.FrontierService(observed.token(),
                    begin.event().parentToken(), begin.event().depth(), kind(observed.kind), state,
                    begin.frame(), begin.event().ordinal(), Math.toIntExact(begin.event().pc()),
                    begin.event().sourceCpu() == 3 ? 0 : begin.event().subject(),
                    cpu(begin.event().sourceCpu()), end == null ? null : end.frame(),
                    end == null ? null : end.event().ordinal(),
                    end == null ? null : Math.toIntExact(end.event().pc()),
                    end == null ? null : end.event().sourceCpu() == 3 ? 0 : end.event().subject(),
                    observed.frameSnapshots, observed.frameChips, observed.currentParent,
                    observed.currentDepth, ancestry);
        }

        private static ObservedEvent requireEvent(ObservedEvent value, String label) {
            if (value == null) throw new IllegalArgumentException(label + " was not observed in the raw rows");
            return value;
        }

        private void observe(ObservedEvent observed,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index,
                List<CompleteRunAudioTrace.ChipEvent> chips, List<ObservedService> completed) {
            var event = observed.event();
            switch (event.kind()) {
                case 1 -> {
                    requireBoundaryHook(event, events, index, true);
                    beginService(observed, false);
                }
                case 2 -> {
                    if (event.flags() == 2) requireResetCancellationBoundary(event);
                    else requireBoundaryHook(event, events, index, false);
                    endService(observed, false, events, index, completed);
                }
                case 3 -> observeYm(observed, chips);
                case 4 -> observePsg(observed, chips);
                case 5, 6, 7 -> observeSnapshot(observed, events, index);
                case 8 -> {
                    ymPort0Latch = 0;
                    ymPort1Latch = 0;
                    beginService(observed, true);
                }
                case 9 -> endService(observed, true, events, index, completed);
                case 10 -> observeMarker(event);
                case 11 -> observeTransition(observed, events, index, completed);
                default -> throw new IllegalArgumentException("unknown S3K native event kind");
            }
        }

        private void beginService(ObservedEvent observed, boolean reset) {
            var event = observed.event();
            requireZeroPayload(event, reset ? "S3K raw reset begin shape changed"
                    : "S3K raw service begin shape changed");
            if (reset && event.serviceKind() != 1) {
                throw new IllegalArgumentException(
                        "S3K raw reset service kind differs from reviewed service manifest");
            }
            if (event.serviceToken() == 0 || event.serviceToken() != availableToken()
                    || ((event.depth() == 0) != (event.parentToken() == 0))) {
                throw new IllegalArgumentException("S3K raw service token allocation changed");
            }
            nextToken = nextToken(event.serviceToken());
            if (liveServices.stream().anyMatch(service -> service.token() == event.serviceToken())
                    || resetService != null) {
                throw new IllegalArgumentException("S3K raw service begin identity changed");
            }
            int expectedParent = liveServices.isEmpty() ? 0 : liveServices.getLast().token();
            if (!reset && (event.parentToken() != expectedParent || event.depth() != liveServices.size())) {
                throw new IllegalArgumentException(
                        "S3K raw service begin is not nested under innermost service");
            }
            kind(event.serviceKind());
            if (reset) {
                if (event.sourceCpu() != 3 || event.pc() != 0 || event.depth() != 0
                        || event.parentToken() != 0 || event.offset() != 0
                        || event.subject() != liveServices.size() || event.flags() > 1) {
                    throw new IllegalArgumentException("S3K raw reset begin shape changed");
                }
            } else if (event.subject() == 0 || event.flags() != 0) {
                throw new IllegalArgumentException("S3K raw service begin shape changed");
            }
            ObservedService service = new ObservedService(observed,
                    liveServices.isEmpty() ? event.serviceToken() : liveServices.getFirst().rootToken);
            if (reset) resetService = service;
            else liveServices.add(service);
        }

        private void endService(ObservedEvent observed, boolean reset,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index,
                List<ObservedService> completed) {
            var event = observed.event();
            requireZeroPayload(event, reset ? "S3K raw reset end shape changed"
                    : "S3K raw service end shape changed");
            if (reset && event.serviceKind() != 1) {
                throw new IllegalArgumentException(
                        "S3K raw reset service kind differs from reviewed service manifest");
            }
            boolean promotes = !reset && index + 1 < events.size() && events.get(index + 1).kind() == 11;
            ObservedService service;
            if (reset) {
                service = requireOwned(event);
            } else if (promotes) {
                if (liveServices.size() < 2) {
                    throw new IllegalArgumentException("S3K raw promotion has no direct parent");
                }
                service = liveServices.get(liveServices.size() - 2);
                requireIdentity(event, service);
            } else {
                service = requireOwned(event);
            }
            if (service.snapshot != null) {
                throw new IllegalArgumentException("S3K raw service ended during a snapshot");
            }
            if (reset) {
                if (!liveServices.isEmpty()) {
                    throw new IllegalArgumentException("S3K raw reset ended before service cancellations");
                }
                if (event.sourceCpu() != 3 || event.pc() != 0 || event.subject() != 0
                        || event.flags() != service.begin.event().flags()) {
                    throw new IllegalArgumentException("S3K raw reset end shape changed");
                }
            } else {
                boolean cancelled = (event.flags() & 2) != 0;
                if (cancelled != (resetService != null)) {
                    throw new IllegalArgumentException("S3K raw service cancellation/reset ownership changed");
                }
                if (cancelled ? event.flags() != 2 || event.subject() != 0
                        || event.sourceCpu() != 3 || event.pc() != 0
                        : event.flags() != 0 || event.subject() == 0) {
                    throw new IllegalArgumentException("S3K raw service end shape changed");
                }
            }
            service.end = observed;
            service.sealEvidence();
            if (reset) resetService = null;
            else liveServices.remove(service);
            completed.add(service);
        }

        private void observeYm(ObservedEvent observed, List<CompleteRunAudioTrace.ChipEvent> chips) {
            var event = observed.event();
            ObservedService service = requireOwned(event);
            requireChipSource(event, service);
            if (event.subject() < 0 || event.subject() > 3 || event.offset() != 0
                    || event.payloadLength() != 0 || event.payload().signum() != 0
                    || event.flags() != 0) {
                throw new IllegalArgumentException("S3K raw YM event shape changed");
            }
            int port = event.subject() < 2 ? 0 : 1;
            int register = port == 0 ? ymPort0Latch : ymPort1Latch;
            boolean data = event.subject() == 1 || event.subject() == 3;
            service.chips.addChip(observed.coordinate(), event.ordinal(), event.kind(), event.subject(),
                    event.value(), event.pc(), event.sourceCpu(), data, port, register);
            service.frameChips.add(new CompleteRunAudioTrace.FrontierChipEvent(
                    observed.coordinate(), event.ordinal(), cpu(event.sourceCpu()),
                    Math.toIntExact(event.pc()), event.kind(), event.subject(), event.value(), data,
                    port, register));
            if (data) {
                chips.add(new CompleteRunAudioTrace.YmWrite(
                        nextChipOrdinal++, port, register, event.value()));
            } else if (port == 0) {
                ymPort0Latch = event.value();
            } else {
                ymPort1Latch = event.value();
            }
        }

        private void observePsg(ObservedEvent observed, List<CompleteRunAudioTrace.ChipEvent> chips) {
            var event = observed.event();
            ObservedService service = requireOwned(event);
            requireChipSource(event, service);
            if (event.subject() != 0 || event.offset() != 0 || event.payloadLength() != 0
                    || event.payload().signum() != 0 || event.flags() != 0) {
                throw new IllegalArgumentException("S3K raw PSG event shape changed");
            }
            service.chips.addChip(observed.coordinate(), event.ordinal(), event.kind(), 0,
                    event.value(), event.pc(), event.sourceCpu(), true, 0, 0);
            service.frameChips.add(new CompleteRunAudioTrace.FrontierChipEvent(
                    observed.coordinate(), event.ordinal(), cpu(event.sourceCpu()),
                    Math.toIntExact(event.pc()), event.kind(), 0, event.value(), true, null, null));
            chips.add(new CompleteRunAudioTrace.PsgWrite(nextChipOrdinal++, event.value()));
        }

        private void observeSnapshot(ObservedEvent observed,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index) {
            var event = observed.event();
            ObservedService service = snapshotOwner(event, events, index);
            int length = switch (event.subject()) {
                case 1 -> 8192;
                case 2 -> 1;
                default -> throw new IllegalArgumentException("S3K raw snapshot range is unknown");
            };
            if (event.flags() != 0 || event.value() != 0) {
                throw new IllegalArgumentException("S3K raw snapshot event shape changed");
            }
            if (event.kind() == 5) {
                if (service.snapshot != null || event.offset() != 0 || event.payloadLength() != 0
                        || event.payload().signum() != 0) {
                    throw new IllegalArgumentException("S3K raw snapshot begin shape changed");
                }
                service.snapshot = new SnapshotAssembly(event.subject(), length,
                        event.sourceCpu(), event.pc());
            } else if (event.kind() == 6) {
                SnapshotAssembly snapshot = service.snapshot;
                if (snapshot == null || snapshot.range != event.subject()
                        || event.offset() != snapshot.bytes.size() || event.payloadLength() < 1
                        || event.payloadLength() > 8
                        || snapshot.bytes.size() + event.payloadLength() > snapshot.length
                        || event.sourceCpu() != snapshot.sourceCpu || event.pc() != snapshot.pc) {
                    if (snapshot != null && (event.sourceCpu() != snapshot.sourceCpu
                            || event.pc() != snapshot.pc)) {
                        throw new IllegalArgumentException(
                                "S3K raw snapshot source/PC continuity changed");
                    }
                    throw new IllegalArgumentException("S3K raw snapshot chunk shape changed");
                }
                writeLittleEndian(snapshot.bytes, event.payload(), event.payloadLength());
            } else {
                SnapshotAssembly snapshot = service.snapshot;
                if (snapshot == null || snapshot.range != event.subject() || event.offset() != snapshot.length
                        || snapshot.bytes.size() != snapshot.length || event.payloadLength() != 0
                        || event.payload().signum() != 0 || event.sourceCpu() != snapshot.sourceCpu
                        || event.pc() != snapshot.pc) {
                    throw new IllegalArgumentException("S3K raw snapshot end shape changed");
                }
                service.snapshots.addSnapshot(snapshot.range, snapshot.sourceCpu,
                        snapshot.pc, snapshot.bytes.toByteArray());
                service.frameSnapshots.add(new CompleteRunAudioTrace.FrontierSnapshot(
                        snapshot.range, cpu(snapshot.sourceCpu), Math.toIntExact(snapshot.pc),
                        unsigned(snapshot.bytes.toByteArray())));
                service.snapshot = null;
            }
        }

        private void observeTransition(ObservedEvent observed,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index,
                List<ObservedService> completed) {
            var event = observed.event();
            requireZeroPayload(event, "S3K raw ancestry transition shape changed");
            ManifestHook hook = requireManifestHook(event.subject());
            if (event.pc() != hook.pc() || event.sourceCpu() != hook.sourceCpu()
                    || hook.action() != HookAction.DIRECT_PARENT_PROMOTION) {
                throw new IllegalArgumentException(
                        "S3K raw ancestry hook semantics differ from reviewed service manifest");
            }
            if (event.flags() != 0 || event.subject() == 0
                    || ((event.depth() == 0) != (event.parentToken() == 0))) {
                throw new IllegalArgumentException("S3K raw ancestry transition shape changed");
            }
            if (index == 0 || events.get(index - 1).kind() != 2 || liveServices.isEmpty()) {
                throw new IllegalArgumentException(
                        "S3K raw ancestry transition is not adjacent to parent completion");
            }
            var ended = events.get(index - 1);
            ObservedService service = liveServices.getLast();
            int expectedParent = liveServices.size() < 2 ? 0
                    : liveServices.get(liveServices.size() - 2).token();
            int expectedDepth = liveServices.size() - 1;
            if (service.token() != event.serviceToken() || service.kind != event.serviceKind()
                    || ended.serviceToken() != service.currentParent
                    || ended.subject() != event.subject() || ended.pc() != event.pc()
                    || ended.sourceCpu() != event.sourceCpu()
                    || event.parentToken() != expectedParent || event.depth() != expectedDepth) {
                throw new IllegalArgumentException("S3K raw ancestry transition ownership changed");
            }
            int oldRoot = service.rootToken;
            service.transitions.add(new ObservedTransition(observed.frame(), observed.coordinate(),
                    service.currentParent, service.currentDepth, event));
            service.currentParent = event.parentToken();
            service.currentDepth = event.depth();
            if (event.parentToken() == 0) {
                service.rootToken = service.token();
                reassignDescendantRoots(service, oldRoot, completed);
            }
        }

        private void observeMarker(S3kCompleteRunReferenceRawAdapter.RawEvent event) {
            if (event.subject() == 0 || event.offset() != 0 || event.flags() != 0
                    || event.payloadLength() != 0 || event.payload().signum() != 0
                    || event.value() < 0 || event.value() > 4) {
                throw new IllegalArgumentException("S3K raw marker event shape changed");
            }
            ManifestHook hook = requireManifestHook(event.subject());
            if (event.pc() != hook.pc() || event.sourceCpu() != hook.sourceCpu()
                    || hook.action() != HookAction.OBSERVATION_MARKER
                    || hook.expectedKind() != event.serviceKind()) {
                throw new IllegalArgumentException(
                        "S3K raw marker hook semantics differ from reviewed service manifest");
            }
        }

        private void requireBoundaryHook(S3kCompleteRunReferenceRawAdapter.RawEvent event,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index, boolean begin) {
            ManifestHook hook = requireManifestHook(event.subject());
            if (event.pc() != hook.pc() || event.sourceCpu() != hook.sourceCpu()) {
                throw new IllegalArgumentException(
                        "S3K raw service hook semantics differ from reviewed service manifest");
            }
            boolean valid;
            if (begin && hook.action() == HookAction.PUSH_BEGIN) {
                int activeKind = liveServices.isEmpty() ? 0 : liveServices.getLast().kind;
                valid = event.serviceKind() == hook.serviceKind()
                        && hook.expectedKind() == activeKind;
            } else if (begin && hook.action() == HookAction.TAIL_POP_PUSH) {
                valid = event.serviceKind() == hook.serviceKind() && index > 0
                        && tailPair(events.get(index - 1), event, hook);
            } else if (!begin && hook.action() == HookAction.POP_END_AT_PC) {
                valid = event.serviceKind() == hook.expectedKind();
            } else if (!begin && hook.action() == HookAction.TAIL_POP_PUSH) {
                valid = event.serviceKind() == hook.expectedKind() && index + 1 < events.size()
                        && tailPair(event, events.get(index + 1), hook);
            } else {
                valid = false;
            }
            if (!valid) {
                throw new IllegalArgumentException(
                        "S3K raw service hook semantics differ from reviewed service manifest");
            }
        }

        private static void requireResetCancellationBoundary(
                S3kCompleteRunReferenceRawAdapter.RawEvent event) {
            if (event.subject() != 0 || event.pc() != 0 || event.sourceCpu() != 3) {
                throw new IllegalArgumentException("S3K raw reset cancellation semantics changed");
            }
        }

        private static boolean tailPair(S3kCompleteRunReferenceRawAdapter.RawEvent end,
                S3kCompleteRunReferenceRawAdapter.RawEvent begin, ManifestHook hook) {
            return end.kind() == 2 && begin.kind() == 1
                    && begin.ordinal() == end.ordinal() + 1
                    && end.subject() == hook.token() && begin.subject() == hook.token()
                    && end.pc() == hook.pc() && begin.pc() == hook.pc()
                    && end.sourceCpu() == hook.sourceCpu() && begin.sourceCpu() == hook.sourceCpu()
                    && end.serviceKind() == hook.expectedKind()
                    && begin.serviceKind() == hook.serviceKind();
        }

        private static ManifestHook requireManifestHook(int token) {
            ManifestHook hook = S3K_HOOKS.get(token);
            if (hook == null) {
                throw new IllegalArgumentException(
                        "S3K raw hook token is not declared by reviewed service manifest");
            }
            return hook;
        }

        private ObservedService requireOwned(S3kCompleteRunReferenceRawAdapter.RawEvent event) {
            if (resetService != null && resetService.token() == event.serviceToken()
                    && !liveServices.isEmpty()) {
                throw new IllegalArgumentException(
                        "S3K raw reset event precedes service cancellations");
            }
            ObservedService service = resetService != null && resetService.token() == event.serviceToken()
                    ? resetService : liveServices.isEmpty() ? null : liveServices.getLast();
            if (service == null || service.token() != event.serviceToken()) {
                throw new IllegalArgumentException(
                        "S3K raw event is not owned by innermost service");
            }
            requireIdentity(event, service);
            return service;
        }

        private static void requireIdentity(S3kCompleteRunReferenceRawAdapter.RawEvent event,
                ObservedService service) {
            if (service.kind != event.serviceKind() || service.currentParent != event.parentToken()
                    || service.currentDepth != event.depth()) {
                throw new IllegalArgumentException("S3K raw event ownership changed");
            }
        }

        private void requireChipSource(S3kCompleteRunReferenceRawAdapter.RawEvent event,
                ObservedService owner) {
            if (owner == resetService) {
                if (event.sourceCpu() != 3 || event.pc() != 0) {
                    throw new IllegalArgumentException("S3K raw reset chip source/PC changed");
                }
            } else if (event.sourceCpu() == 3) {
                throw new IllegalArgumentException("S3K raw ordinary chip source/PC changed");
            }
        }

        private ObservedService snapshotOwner(S3kCompleteRunReferenceRawAdapter.RawEvent event,
                List<S3kCompleteRunReferenceRawAdapter.RawEvent> events, int index) {
            if (resetService != null || liveServices.isEmpty()
                    || liveServices.getLast().token() == event.serviceToken()) return requireOwned(event);
            if (liveServices.size() < 2
                    || liveServices.get(liveServices.size() - 2).token() != event.serviceToken()) {
                throw new IllegalArgumentException("S3K raw event is not owned by innermost service");
            }
            ObservedService parent = liveServices.get(liveServices.size() - 2);
            requireIdentity(event, parent);
            int at = index;
            while (at < events.size() && events.get(at).kind() >= 5 && events.get(at).kind() <= 7) {
                if (events.get(at).serviceToken() != parent.token()) {
                    throw new IllegalArgumentException("S3K raw promotion snapshot ownership changed");
                }
                at++;
            }
            if (at + 1 >= events.size() || events.get(at).kind() != 2
                    || events.get(at).serviceToken() != parent.token() || events.get(at + 1).kind() != 11) {
                throw new IllegalArgumentException("S3K raw promotion snapshot adjacency changed");
            }
            return parent;
        }

        private static void requireZeroPayload(S3kCompleteRunReferenceRawAdapter.RawEvent event,
                String message) {
            if (event.offset() != 0 || event.payloadLength() != 0 || event.payload().signum() != 0
                    || event.value() != 0) {
                throw new IllegalArgumentException(message);
            }
        }

        private int availableToken() {
            int candidate = nextToken;
            for (int attempts = 0; attempts < 65535 && candidate != 0; attempts++) {
                int proposed = candidate;
                boolean used = liveServices.stream().anyMatch(service -> service.token() == proposed);
                if (!used) return candidate;
                candidate = nextToken(candidate);
            }
            return 0;
        }

        private static int nextToken(int token) { return token == 0xffff ? 0 : token + 1; }

        private void reassignDescendantRoots(ObservedService promoted, int oldRoot,
                List<ObservedService> completed) {
            for (ObservedService candidate : java.util.stream.Stream
                    .concat(pendingServices.stream(), completed.stream()).toList()) {
                if (candidate.rootToken != oldRoot) continue;
                int parent = candidate.begin.event().parentToken();
                for (int steps = 0; parent != 0 && steps < 8; steps++) {
                    if (parent == promoted.token()) {
                        candidate.rootToken = promoted.token();
                        break;
                    }
                    ObservedService ancestor = null;
                    for (ObservedService possible : java.util.stream.Stream
                            .concat(pendingServices.stream(), completed.stream()).toList()) {
                        if (possible.token() == parent
                                && possible.begin.coordinate() < candidate.begin.coordinate()
                                && (ancestor == null || possible.begin.coordinate() > ancestor.begin.coordinate())) {
                            ancestor = possible;
                        }
                    }
                    if (ancestor == null) break;
                    parent = ancestor.begin.event().parentToken();
                }
            }
        }

        private static void writeLittleEndian(ByteArrayOutputStream output, BigInteger payload, int length) {
            for (int index = 0; index < length; index++) {
                output.write(payload.shiftRight(index * 8).byteValue());
            }
        }

        private static void requireChipEvidence(ObservedService observed,
                List<S3kCompleteRunReferenceRawAdapter.RawChip> raw) {
            EvidenceDigest supplied = new EvidenceDigest((byte) 1);
            for (var chip : raw) supplied.addChip(chip.coordinate(), chip.nativeOrdinal(), chip.eventKind(),
                    chip.subject(), chip.value(), chip.pc(), chip.sourceCpu(), chip.data(),
                    chip.port(), chip.register());
            if (observed.chips.count != raw.size()
                    || !Arrays.equals(observed.chips.finish(), supplied.finish())) {
                throw new IllegalArgumentException("S3K cutoff chip differs from its observed event");
            }
        }

        private static void requireSnapshotEvidence(ObservedService observed,
                List<S3kCompleteRunReferenceRawAdapter.RawSnapshot> raw) {
            EvidenceDigest supplied = new EvidenceDigest((byte) 2);
            for (var snapshot : raw) supplied.addSnapshot(snapshot.rangeId(), snapshot.sourceCpu(),
                    snapshot.pc(), snapshot.bytes());
            if (observed.snapshots.count != raw.size()
                    || !Arrays.equals(observed.snapshots.finish(), supplied.finish())) {
                throw new IllegalArgumentException(
                        "S3K cutoff snapshot differs from its observed event sequence");
            }
        }

        private static void requireBoundaryEvent(ObservedEvent observed,
                S3kCompleteRunReferenceRawAdapter.RawService service, boolean begin) {
            S3kCompleteRunReferenceRawAdapter.RawEvent event = observed.event();
            int parent = begin ? service.parentToken() : service.currentParentToken();
            int depth = begin ? service.depth() : service.currentDepth();
            long coordinate = begin ? service.beginCoordinate() : service.endCoordinate();
            long pc = begin ? service.beginPc() : service.endPc();
            int hook = begin ? service.beginHookToken() : service.endHookToken();
            int source = begin ? service.beginSourceCpu() : service.cancelled() ? 3 : service.beginSourceCpu();
            if (observed.coordinate() != coordinate || event.kind() != (begin ? 1 : 2)
                    || event.serviceToken() != service.token()
                    || event.parentToken() != parent || event.serviceKind() != service.kind()
                    || event.depth() != depth || event.pc() != pc || event.subject() != hook
                    || event.sourceCpu() != source) {
                throw new IllegalArgumentException("S3K cutoff service differs from its observed boundary event");
            }
        }

        private static List<Integer> unsigned(byte[] bytes) {
            List<Integer> result = new ArrayList<>(bytes.length);
            for (byte value : bytes) result.add(Byte.toUnsignedInt(value));
            return List.copyOf(result);
        }

        private static String cpu(int source) {
            return switch (source) { case 1 -> "Z80"; case 2 -> "M68K"; case 3 -> "RESET";
                default -> throw new IllegalArgumentException("unknown S3K native source CPU"); };
        }

        private static String kind(int id) {
            return switch (id) {
                case 1 -> "Reset"; case 2 -> "SoundDriverLoad"; case 3 -> "VInt";
                case 5 -> "BootstrapPsgInit"; case 6 -> "DriverInit"; case 7 -> "DpcmIteration";
                case 8 -> "SegaPcmIteration"; case 9 -> "DigitalAudioDispatch";
                case 10 -> "SegaPcmDispatch"; case 11 -> "UpdateEverything";
                case 12 -> "UpdateMusic";
                default -> throw new IllegalArgumentException("unknown S3K native service kind");
            };
        }

        private static String sha256(byte[] bytes) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }

        private static String segment(int row) {
            return S3kCompleteRunAudioProfile.profile().fixture().segments().stream()
                    .filter(value -> row >= value.firstFrame() && row < value.exclusiveEnd())
                    .map(CompleteRunAudioTrace.ManifestSegment::id).findFirst().orElse(null);
        }

        private record ObservedEvent(int frame, long coordinate,
                S3kCompleteRunReferenceRawAdapter.RawEvent event) { }
        private record ObservedTransition(int frame, long coordinate, int previousParent, int previousDepth,
                S3kCompleteRunReferenceRawAdapter.RawEvent event) { }

        private static final class ObservedService {
            private final ObservedEvent begin;
            private final int kind;
            private int currentParent;
            private int currentDepth;
            private ObservedEvent end;
            private final List<ObservedTransition> transitions = new ArrayList<>();
            private final EvidenceDigest chips = new EvidenceDigest((byte) 1);
            private final EvidenceDigest snapshots = new EvidenceDigest((byte) 2);
            private final List<CompleteRunAudioTrace.FrontierChipEvent> frameChips = new ArrayList<>();
            private final List<CompleteRunAudioTrace.FrontierSnapshot> frameSnapshots = new ArrayList<>();
            private SnapshotAssembly snapshot;
            private int rootToken;

            private ObservedService(ObservedEvent begin, int rootToken) {
                this.begin = begin;
                this.kind = begin.event().serviceKind();
                this.currentParent = begin.event().parentToken();
                this.currentDepth = begin.event().depth();
                this.rootToken = rootToken;
            }

            private int token() { return begin.event().serviceToken(); }

            private void clearFrameEvidence() {
                frameChips.clear();
                frameSnapshots.clear();
            }

            private void sealEvidence() {
                chips.finish();
                snapshots.finish();
            }
        }

        private static final class SnapshotAssembly {
            private final int range;
            private final int length;
            private final int sourceCpu;
            private final long pc;
            private final ByteArrayOutputStream bytes;

            private SnapshotAssembly(int range, int length, int sourceCpu, long pc) {
                this.range = range;
                this.length = length;
                this.sourceCpu = sourceCpu;
                this.pc = pc;
                this.bytes = new ByteArrayOutputStream(length);
            }
        }

        private static final class EvidenceDigest {
            private final MessageDigest digest;
            private byte[] finished;
            private int count;

            private EvidenceDigest(byte domain) {
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                digest.update(domain);
            }

            private void addChip(long coordinate, long ordinal, int eventKind, int subject,
                    int value, long pc, int sourceCpu, boolean data, int port, int register) {
                requireOpen();
                integer(coordinate); integer(ordinal); integer(eventKind); integer(subject); integer(value);
                integer(pc); integer(sourceCpu); integer(data ? 1 : 0); integer(port); integer(register);
                count++;
            }

            private void addSnapshot(int range, int sourceCpu, long pc, byte[] bytes) {
                requireOpen();
                integer(range); integer(sourceCpu); integer(pc); integer(bytes.length); digest.update(bytes);
                count++;
            }

            private byte[] finish() {
                if (finished == null) finished = digest.digest();
                return finished.clone();
            }

            private void requireOpen() {
                if (finished != null) throw new IllegalStateException("S3K evidence digest is sealed");
            }

            private void integer(long value) {
                for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
            }
        }
    }
}
