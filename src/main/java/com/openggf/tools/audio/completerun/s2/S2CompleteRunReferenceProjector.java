package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioCaptureStore;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transactional projection of validated S2 native observations into canonical records. */
public final class S2CompleteRunReferenceProjector {
    public record Projection(List<CompleteRunAudioTrace.Record> records) {
        public Projection { records = List.copyOf(records); }
    }

    Projection projectPrefixForTesting(Path raw, Path rom) throws IOException {
        S2CompleteRunAssetCatalog.load(rom);
        Transaction transaction = new Transaction();
        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, transaction);
        return transaction.result();
    }

    void projectPrefixForTesting(Path raw, Path rom, Path output) throws IOException {
        Projection projection = projectPrefixForTesting(raw, rom);
        CompleteRunAudioTrace.Metadata metadata = syntheticMetadata(projection.records());
        projectToStore(raw, rom, output, metadata, null, false);
    }

    void project(Path raw, Path rom, Path output, CompleteRunAudioTrace.Metadata metadata,
            CompleteRunAudioTrace.NativeCapabilitySummary capability) throws IOException {
        metadata.validateFixtureProfile(S2CompleteRunAudioProfile.profile());
        projectToStore(raw, rom, output, metadata, capability, true);
    }

    private void projectToStore(Path raw, Path rom, Path output, CompleteRunAudioTrace.Metadata metadata,
            CompleteRunAudioTrace.NativeCapabilitySummary capability, boolean full) throws IOException {
        S2CompleteRunAssetCatalog.load(rom);
        var writer = new CompleteRunAudioCaptureStore().writeNew(output, metadata);
        Transaction transaction = new Transaction(writer, capability,
                metadata.observerRuntimeIdentity()
                        instanceof CompleteRunAudioTrace.BufferedNativeObserverIdentity);
        boolean complete = false;
        try {
            if (full) S2CompleteRunReferenceRawAdapter.scan(raw, transaction);
            else S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, transaction);
            complete = true;
        } finally {
            if (!complete) writer.abort();
        }
    }

    private static CompleteRunAudioTrace.Metadata syntheticMetadata(
            List<CompleteRunAudioTrace.Record> records) {
        var baseline = (CompleteRunAudioTrace.Baseline) records.getFirst();
        int end = ((CompleteRunAudioTrace.Frame) records.get(records.size() - 2)).absoluteFrame() + 1;
        var fixture = S2CompleteRunAudioProfile.profile().fixture();
        var interval = new CompleteRunAudioTrace.CompleteRunFixture(fixture.romSha1(), fixture.romCrc32(),
                fixture.bk2Sha256(), fixture.bk2RowCount(), fixture.runManifestSha256(),
                List.of(new CompleteRunAudioTrace.ManifestSegment("test-prefix", baseline.absoluteFrame(), end)),
                baseline.absoluteFrame(), end);
        return syntheticMetadata(interval, "test-only." + S2CompleteRunAudioProfile.ID);
    }

    static CompleteRunAudioTrace.Metadata fullSyntheticMetadataForTesting() {
        return syntheticMetadata(S2CompleteRunAudioProfile.profile().fixture(), S2CompleteRunAudioProfile.ID);
    }

    private static CompleteRunAudioTrace.Metadata syntheticMetadata(
            CompleteRunAudioTrace.CompleteRunFixture interval, String profileId) {
        var pinned = S2CompleteRunAudioProfile.profile();
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

    private static final class Transaction implements S2CompleteRunReferenceRawAdapter.Sink {
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
        private final Map<Origin, ObservedService> observedServices = new LinkedHashMap<>();
        private ObservedService resetService;

        private Transaction() { this(null, null, true); }

        private Transaction(CompleteRunAudioCaptureStore.Writer writer,
                CompleteRunAudioTrace.NativeCapabilitySummary capability,
                boolean retainNativeDiagnostics) {
            this.writer = writer;
            this.capability = capability;
            this.retainNativeDiagnostics = retainNativeDiagnostics;
        }

        @Override public void begin() { started = true; }
        @Override public void header(S2CompleteRunReferenceRawAdapter.Header value) { }

        @Override public void baseline(S2CompleteRunReferenceRawAdapter.RawBoundary value) throws IOException {
            requireStarted();
            if (!value.pendingDescendants().isEmpty()) {
                throw new IllegalArgumentException("S2 baseline pending frontier is unsupported");
            }
            ymPort0Latch = value.ymPort0Latch();
            ymPort1Latch = value.ymPort1Latch();
            liveServices.clear();
            observedServices.clear();
            for (var service : value.activeServices()) {
                ObservedService observed = ObservedService.fromBoundary(service);
                liveServices.add(observed);
                observedServices.put(Origin.of(service), observed);
            }
            CompleteRunAudioTrace.CutoffNativeDiagnostics diagnostics = retainNativeDiagnostics
                    ? boundaryDiagnostics(value) : null;
            append(new CompleteRunAudioTrace.Baseline(value.row(), null, null,
                    new CompleteRunAudioTrace.BoundaryFrontier(
                            null, null, null, diagnostics, null, null)));
        }

        @Override public void frame(S2CompleteRunReferenceRawAdapter.RawFrame value) throws IOException {
            requireStarted();
            List<CompleteRunAudioTrace.ChipEvent> chips = new ArrayList<>();
            List<ObservedService> completed = new ArrayList<>();
            long firstCoordinate = nextEventCoordinate;
            for (var event : value.events()) {
                observe(value.row(), event, chips, completed);
            }
            nextEventCoordinate = Math.addExact(nextEventCoordinate, value.events().size());
            CompleteRunAudioTrace.FrameNativeDiagnostics diagnostics = retainNativeDiagnostics
                    ? frameDiagnostics(completed, firstCoordinate, nextEventCoordinate) : null;
            append(new CompleteRunAudioTrace.Frame(value.row(), segment(value.row()), null,
                    null, null, null, null, List.copyOf(chips), diagnostics));
            java.util.stream.Stream.concat(completed.stream(), liveServices.stream()).distinct()
                    .forEach(ObservedService::clearFrameEvidence);
        }

        @Override public void cutoff(S2CompleteRunReferenceRawAdapter.RawBoundary value) throws IOException {
            requireStarted();
            CompleteRunAudioTrace.CutoffNativeDiagnostics diagnostics = retainNativeDiagnostics
                    ? boundaryDiagnostics(value) : null;
            append(new CompleteRunAudioTrace.CutoffFrontier(null, null, null,
                    diagnostics, null, null, null));
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
            if (!committed) throw new IllegalStateException("S2 projection did not commit");
            return new Projection(staged);
        }

        private void observe(int row, S2CompleteRunReferenceRawAdapter.RawEvent event,
                List<CompleteRunAudioTrace.ChipEvent> chips, List<ObservedService> completed) {
            long coordinate = Math.addExact(nextEventCoordinate, event.ordinal());
            switch (event.kind()) {
                case 1 -> {
                    ObservedService service = ObservedService.fromEvent(row, coordinate, event, false);
                    liveServices.add(service);
                    observedServices.put(service.origin, service);
                }
                case 2 -> {
                    ObservedService service = requireOwned(event.serviceToken());
                    service.end(row, coordinate, event);
                    liveServices.remove(service);
                    completed.add(service);
                }
                case 3 -> {
                    ObservedService owner = requireOwned(event.serviceToken());
                    int port = event.subject() >>> 1;
                    if ((event.subject() & 1) == 0) {
                        if (port == 0) ymPort0Latch = event.value(); else ymPort1Latch = event.value();
                    } else {
                        int register = port == 0 ? ymPort0Latch : ymPort1Latch;
                        chips.add(new CompleteRunAudioTrace.YmWrite(
                                nextChipOrdinal++, port, register, event.value()));
                        owner.frameChips.add(new CompleteRunAudioTrace.FrontierChipEvent(
                                coordinate, event.ordinal(), cpu(event.sourceCpu()),
                                Math.toIntExact(event.pc()), 3, event.subject(), event.value(),
                                true, port, register));
                    }
                }
                case 4 -> {
                    ObservedService owner = requireOwned(event.serviceToken());
                    chips.add(new CompleteRunAudioTrace.PsgWrite(nextChipOrdinal++, event.value()));
                    owner.frameChips.add(new CompleteRunAudioTrace.FrontierChipEvent(
                            coordinate, event.ordinal(), cpu(event.sourceCpu()),
                            Math.toIntExact(event.pc()), 4, 0, event.value(), true, null, null));
                }
                case 5 -> requireOwned(event.serviceToken()).beginSnapshot(event);
                case 6 -> requireOwned(event.serviceToken()).appendSnapshot(event);
                case 7 -> requireOwned(event.serviceToken()).endSnapshot(event);
                case 8 -> {
                    resetService = ObservedService.fromEvent(row, coordinate, event, true);
                    observedServices.put(resetService.origin, resetService);
                    ymPort0Latch = ymPort1Latch = 0;
                }
                case 9 -> {
                    if (resetService == null || resetService.token != event.serviceToken()) {
                        throw new IllegalArgumentException("S2 raw reset completion lost its generation");
                    }
                    resetService.end(row, coordinate, event);
                    completed.add(resetService);
                    resetService = null;
                }
                default -> { }
            }
        }

        private ObservedService requireOwned(int token) {
            if (resetService != null && resetService.token == token) return resetService;
            return liveServices.stream().filter(service -> service.token == token).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "S2 raw event lost its service generation"));
        }

        private CompleteRunAudioTrace.FrameNativeDiagnostics frameDiagnostics(
                List<ObservedService> completed, long firstCoordinate, long exclusiveCoordinate) {
            List<ObservedService> retained = java.util.stream.Stream.concat(
                    completed.stream(), liveServices.stream()).distinct()
                    .sorted(Comparator.comparing(service -> service.origin)).toList();
            List<CompleteRunAudioTrace.FrontierService> services = retained.stream()
                    .map(ObservedService::frameService).toList();
            List<CompleteRunAudioTrace.FrontierOwnedChip> rawChips = new ArrayList<>();
            List<CompleteRunAudioTrace.FrontierOwnedSnapshot> rawSnapshots = new ArrayList<>();
            List<CompleteRunAudioTrace.NativeResetDiagnostic> resets = new ArrayList<>();
            for (int serviceIndex = 0; serviceIndex < services.size(); serviceIndex++) {
                var service = services.get(serviceIndex);
                for (var chip : service.chipEvents()) {
                    if (chip.coordinate() >= firstCoordinate && chip.coordinate() < exclusiveCoordinate) {
                        rawChips.add(new CompleteRunAudioTrace.FrontierOwnedChip(
                                service.token(), serviceIndex, chip));
                    }
                }
                for (var snapshot : service.snapshots()) {
                    rawSnapshots.add(new CompleteRunAudioTrace.FrontierOwnedSnapshot(
                            service.token(), serviceIndex, snapshot));
                }
                if ("RESET".equals(service.beginSourceCpu())) {
                    resets.add(new CompleteRunAudioTrace.NativeResetDiagnostic(
                            service.token(), retained.get(serviceIndex).resetPower));
                }
            }
            rawChips.sort(Comparator.comparingLong(value -> value.event().coordinate()));
            return new CompleteRunAudioTrace.FrameNativeDiagnostics(
                    services, rawChips, rawSnapshots, resets);
        }

        private CompleteRunAudioTrace.CutoffNativeDiagnostics boundaryDiagnostics(
                S2CompleteRunReferenceRawAdapter.RawBoundary boundary) {
            List<CompleteRunAudioTrace.FrontierService> active = boundary.activeServices().stream()
                    .map(this::boundaryService).toList();
            List<CompleteRunAudioTrace.FrontierService> pending = boundary.pendingDescendants().stream()
                    .map(this::boundaryService).toList();
            List<CompleteRunAudioTrace.FrontierService> all = java.util.stream.Stream
                    .concat(active.stream(), pending.stream()).toList();
            List<CompleteRunAudioTrace.FrontierOwnedChip> chips = new ArrayList<>();
            List<CompleteRunAudioTrace.FrontierOwnedSnapshot> snapshots = new ArrayList<>();
            for (int index = 0; index < all.size(); index++) {
                var service = all.get(index);
                for (var chip : service.chipEvents()) chips.add(
                        new CompleteRunAudioTrace.FrontierOwnedChip(service.token(), index, chip));
                for (var snapshot : service.snapshots()) snapshots.add(
                        new CompleteRunAudioTrace.FrontierOwnedSnapshot(service.token(), index, snapshot));
            }
            chips.sort(Comparator.comparingLong(value -> value.event().coordinate()));
            return new CompleteRunAudioTrace.CutoffNativeDiagnostics(active, pending, chips, snapshots,
                    boundary.nativeArmEpoch(), boundary.nativeArmed(), sha256(boundary.driverState()));
        }

        private CompleteRunAudioTrace.FrontierService boundaryService(
                S2CompleteRunReferenceRawAdapter.RawService raw) {
            ObservedService observed = observedServices.get(Origin.of(raw));
            if (observed == null) throw new IllegalArgumentException(
                    "S2 cutoff service origin was not retained by the projector");
            Integer endFrame = raw.complete() || raw.cancelled() ? observed.endRow : null;
            Long endOrdinal = raw.complete() || raw.cancelled() ? observed.endOrdinal : null;
            List<CompleteRunAudioTrace.FrontierChipEvent> chips = raw.chips().stream().map(chip ->
                    new CompleteRunAudioTrace.FrontierChipEvent(chip.coordinate(), chip.nativeOrdinal(),
                            cpu(chip.sourceCpu()), Math.toIntExact(chip.pc()), chip.eventKind(),
                            chip.subject(), chip.value(), chip.data(),
                            chip.eventKind() == 3 ? chip.port() : null,
                            chip.eventKind() == 3 ? chip.register() : null)).toList();
            List<CompleteRunAudioTrace.FrontierSnapshot> snapshots = raw.snapshots().stream().map(snapshot ->
                    new CompleteRunAudioTrace.FrontierSnapshot(snapshot.rangeId(), cpu(snapshot.sourceCpu()),
                            Math.toIntExact(snapshot.pc()), unsigned(snapshot.bytes()))).toList();
            var state = raw.cancelled() ? CompleteRunAudioTrace.FrontierServiceState.RESET_CANCELLED
                    : raw.complete() ? CompleteRunAudioTrace.FrontierServiceState.COMPLETED
                            : CompleteRunAudioTrace.FrontierServiceState.OPEN;
            return new CompleteRunAudioTrace.FrontierService(raw.token(), raw.parentToken(), raw.depth(),
                    kind(raw.kind()), state, raw.beginRow(), raw.beginNativeOrdinal(),
                    Math.toIntExact(raw.beginPc()), raw.beginHookToken(), cpu(raw.beginSourceCpu()),
                    endFrame, endOrdinal, endFrame == null ? null : Math.toIntExact(raw.endPc()),
                    endFrame == null ? null : raw.endHookToken(), snapshots, chips,
                    raw.currentParentToken(), raw.currentDepth(), List.of());
        }

        private void requireStarted() {
            if (!started) throw new IllegalStateException("S2 projection transaction is not open");
        }

        private static String segment(int row) {
            return S2CompleteRunAudioProfile.profile().fixture().segments().stream()
                    .filter(value -> row >= value.firstFrame() && row < value.exclusiveEnd())
                    .map(CompleteRunAudioTrace.ManifestSegment::id).findFirst().orElse(null);
        }

        private static String cpu(int source) {
            return switch (source) { case 0, 3 -> "RESET"; case 1 -> "Z80"; case 2 -> "M68K";
                default -> throw new IllegalArgumentException("unknown S2 native source CPU"); };
        }

        private static String kind(int id) {
            return switch (id) {
                case 1 -> "Reset"; case 2 -> "SoundDriverLoad"; case 3 -> "VInt";
                case 4 -> "DpcmIteration"; case 5 -> "BootstrapPsgInit";
                case 6 -> "DacDispatch"; case 7 -> "SegaPcmIteration";
                case 8 -> "SaxmanIteration"; case 9 -> "UpdateMusic";
                default -> throw new IllegalArgumentException("unknown S2 native service kind");
            };
        }

        private static List<Integer> unsigned(byte[] bytes) {
            List<Integer> values = new ArrayList<>(bytes.length);
            for (byte value : bytes) values.add(Byte.toUnsignedInt(value));
            return List.copyOf(values);
        }

        private static String sha256(byte[] bytes) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }

        private record Origin(int row, long ordinal) implements Comparable<Origin> {
            private static Origin of(S2CompleteRunReferenceRawAdapter.RawService service) {
                return new Origin(service.beginRow(), service.beginNativeOrdinal());
            }
            @Override public int compareTo(Origin other) {
                int rowOrder = Integer.compare(row, other.row);
                return rowOrder != 0 ? rowOrder : Long.compare(ordinal, other.ordinal);
            }
        }

        private static final class ObservedService {
            private final int token, parent, serviceKind, depth, beginPc, beginHook, beginSource;
            private final Origin origin;
            private final boolean resetPower;
            private Integer endRow, endPc, endHook;
            private Long endOrdinal;
            private boolean cancelled;
            private final List<CompleteRunAudioTrace.FrontierChipEvent> frameChips = new ArrayList<>();
            private final List<CompleteRunAudioTrace.FrontierSnapshot> frameSnapshots = new ArrayList<>();
            private SnapshotAssembly snapshot;

            private ObservedService(int token, int parent, int serviceKind, int depth,
                    Origin origin, int beginPc, int beginHook, int beginSource, boolean resetPower) {
                this.token=token;this.parent=parent;this.serviceKind=serviceKind;this.depth=depth;
                this.origin=origin;this.beginPc=beginPc;this.beginHook=beginHook;
                this.beginSource=beginSource;this.resetPower=resetPower;
            }
            private static ObservedService fromBoundary(S2CompleteRunReferenceRawAdapter.RawService raw) {
                return new ObservedService(raw.token(), raw.parentToken(), raw.kind(), raw.depth(),
                        Origin.of(raw), Math.toIntExact(raw.beginPc()), raw.beginHookToken(),
                        raw.beginSourceCpu(), false);
            }
            private static ObservedService fromEvent(int row, long coordinate,
                    S2CompleteRunReferenceRawAdapter.RawEvent event, boolean reset) {
                return new ObservedService(event.serviceToken(), event.parentToken(), event.serviceKind(),
                        event.depth(), new Origin(row, event.ordinal()), Math.toIntExact(event.pc()),
                        reset ? 0 : event.subject(), event.sourceCpu(), reset && event.flags() == 1);
            }
            private void end(int row, long coordinate, S2CompleteRunReferenceRawAdapter.RawEvent event) {
                endRow=row;endOrdinal=event.ordinal();endPc=Math.toIntExact(event.pc());
                endHook=event.sourceCpu()==3?0:event.subject();cancelled=event.flags()==2;
            }
            private void beginSnapshot(S2CompleteRunReferenceRawAdapter.RawEvent event) {
                snapshot=new SnapshotAssembly(event.subject(), event.sourceCpu(), Math.toIntExact(event.pc()));
            }
            private void appendSnapshot(S2CompleteRunReferenceRawAdapter.RawEvent event) {
                for(int index=0;index<event.payloadLength();index++)
                    snapshot.bytes.write(event.payload().shiftRight(index*8).byteValue());
            }
            private void endSnapshot(S2CompleteRunReferenceRawAdapter.RawEvent event) {
                frameSnapshots.add(new CompleteRunAudioTrace.FrontierSnapshot(snapshot.range,
                        cpu(snapshot.source), snapshot.pc, unsigned(snapshot.bytes.toByteArray())));
                snapshot=null;
            }
            private CompleteRunAudioTrace.FrontierService frameService() {
                var state=endRow==null?CompleteRunAudioTrace.FrontierServiceState.OPEN
                        :cancelled?CompleteRunAudioTrace.FrontierServiceState.RESET_CANCELLED
                                :CompleteRunAudioTrace.FrontierServiceState.COMPLETED;
                return new CompleteRunAudioTrace.FrontierService(token,parent,depth,kind(serviceKind),state,
                        origin.row(),origin.ordinal(),beginPc,beginHook,cpu(beginSource),endRow,endOrdinal,
                        endPc,endHook,List.copyOf(frameSnapshots),List.copyOf(frameChips));
            }
            private void clearFrameEvidence(){frameChips.clear();frameSnapshots.clear();}
        }

        private static final class SnapshotAssembly {
            private final int range, source, pc;
            private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            private SnapshotAssembly(int range,int source,int pc){this.range=range;this.source=source;this.pc=pc;}
        }
    }
}
