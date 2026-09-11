package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkFeatures;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.resources.QueueServiceObservation;
import com.openggf.data.compression.KosinskiInspectionCache;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.ResumableKosinskiDecoder;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Session-owned physical four-entry owner of S3K's {@code Queue_Kos} FIFO.
 * Timing jobs retain ready/claimed payloads after this owner retires a slot.
 */
public final class S3kKosDecompressionQueue
        implements RewindSnapshottable<S3kKosDecompressionQueueSnapshot> {
    public static final String REWIND_KEY = "s3k-kos-decompression-queue";
    private static final int MAX_QUEUE_DEPTH = 4;
    private static final String COMPRESSION_VARIANT = "kosinski";

    private final HardwareTimingService timing;
    private final ArrayDeque<HardwareWorkHandle> physicalEntries = new ArrayDeque<>();
    private final Map<HardwareWorkHandle, S3kKosDecompressionDescriptor> descriptors =
            new HashMap<>();
    private final Set<HardwareWorkHandle> preparedEntries = new HashSet<>();
    private boolean deferNewHeadPreparationVisibility;

    public S3kKosDecompressionQueue(HardwareTimingService timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    HardwareTimingService timingOwner() {
        return timing;
    }

    public HardwareWorkHandle queueStandardKos(
            Rom rom, int sourceAddress, int destinationAddress) throws IOException {
        Objects.requireNonNull(rom, "rom");
        if (physicalEntries.size() >= MAX_QUEUE_DEPTH) {
            throw new IllegalStateException("S3K Kosinski decompression FIFO is full");
        }
        // The archive lengths are a pure function of the ROM; a warmed cache
        // spares the submitting frame the full inspection decode.
        KosinskiReader.StandardArchiveInfo info =
                KosinskiInspectionCache.inspectStandard(rom, sourceAddress);
        byte[] compressed = rom.readBytes(sourceAddress, info.compressedLength());
        return queueInspected(
                compressed, sourceAddress, destinationAddress, info, 0);
    }

    HardwareWorkHandle queueModuleChild(
            byte[] archive,
            int archiveOffset,
            int sourceAddress,
            int destinationAddress) throws IOException {
        Objects.requireNonNull(archive, "archive");
        KosinskiReader.StandardArchiveInfo info =
                KosinskiReader.inspectStandard(archive, archiveOffset);
        byte[] compressed = java.util.Arrays.copyOfRange(
                archive, archiveOffset, archiveOffset + info.compressedLength());
        return queueInspected(
                compressed, sourceAddress, destinationAddress, info, 1);
    }

    public boolean hasCapacity() {
        return physicalEntries.size() < MAX_QUEUE_DEPTH;
    }

    public int availableCapacity() {
        return MAX_QUEUE_DEPTH - physicalEntries.size();
    }

    private HardwareWorkHandle queueInspected(
            byte[] compressed,
            int sourceAddress,
            int destinationAddress,
            KosinskiReader.StandardArchiveInfo info,
            int coordinationCount) throws IOException {
        if (!hasCapacity()) {
            throw new IllegalStateException("S3K Kosinski decompression FIFO is full");
        }
        S3kKosDecompressionDescriptor descriptor = new S3kKosDecompressionDescriptor(
                sourceAddress, info.compressedLength(), destinationAddress,
                info.decompressedLength());
        HardwareWorkFeatures features = S3kKosinskiWorkFeatureExtractor.inspect(
                compressed, 0, 1, coordinationCount);
        HardwareWorkHandle handle = timing.submit(new HardwareWorkSubmission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                descriptor.sourceAddress(), descriptor.compressedLength(),
                descriptor.destinationAddress(), descriptor.destinationLength(),
                COMPRESSION_VARIANT, 1, false, features,
                new DirectPreparation(descriptor, compressed)));
        physicalEntries.addLast(handle);
        descriptors.put(handle, descriptor);
        return handle;
    }

    /** Must be invoked after timing admission at each production boundary. */
    public void afterTimingService(HardwareServiceBoundary boundary) {
        if (boundary != HardwareServiceBoundary.PRE_MAIN_LOOP) {
            return;
        }
        HardwareWorkHandle servicedHead = physicalEntries.peekFirst();
        if (servicedHead != null && !deferNewHeadPreparationVisibility) {
            preparedEntries.add(servicedHead);
        }
        deferNewHeadPreparationVisibility = false;
        while (!physicalEntries.isEmpty() && timing.isReady(physicalEntries.peekFirst())) {
            preparedEntries.remove(physicalEntries.removeFirst());
        }
    }

    /** Keeps a child published at a held loop tail unarmed until its closure. */
    void deferNewHeadPreparationVisibilityForHeldLoopTail() {
        deferNewHeadPreparationVisibility = true;
    }

    public boolean decompressionsPending() {
        return !physicalEntries.isEmpty();
    }

    public int physicalQueueSize() {
        return physicalEntries.size();
    }

    QueueDiagnosticSnapshot captureDiagnostics(
            List<QueueServiceObservation> observations) {
        if (physicalEntries.isEmpty()) {
            return QueueDiagnosticSnapshot.idle(
                    QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                    observations);
        }
        HardwareWorkHandle active = physicalEntries.peekFirst();
        S3kKosDecompressionDescriptor activeDescriptor =
                Objects.requireNonNull(descriptors.get(active));
        List<String> waiting = physicalEntries.stream()
                .skip(1)
                .map(descriptors::get)
                .map(descriptor -> QueueDiagnosticSnapshot.fingerprint(
                        QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                        descriptor.sourceAddress(),
                        descriptor.destinationAddress(), null))
                .toList();
        return new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                true, preparedEntries.contains(active),
                activeDescriptor.sourceAddress(),
                activeDescriptor.destinationAddress(),
                -1, -1, waiting, observations);
    }

    public boolean isReady(HardwareWorkHandle handle) {
        return timing.isReady(handle);
    }

    public byte[] claim(HardwareWorkHandle handle) {
        byte[] payload = timing.claim(handle);
        descriptors.remove(handle);
        preparedEntries.remove(handle);
        return payload;
    }

    public S3kKosDecompressionDescriptor descriptor(HardwareWorkHandle handle) {
        S3kKosDecompressionDescriptor descriptor = descriptors.get(handle);
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is not owned by this queue");
        }
        return descriptor;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public S3kKosDecompressionQueueSnapshot capture() {
        return new S3kKosDecompressionQueueSnapshot(descriptors.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(entry -> entry.getKey().ordinal()))
                .map(entry -> new S3kKosDecompressionQueueSnapshot.Entry(
                        entry.getKey(), entry.getValue(),
                        physicalEntries.contains(entry.getKey()),
                        preparedEntries.contains(entry.getKey())))
                .toList(), deferNewHeadPreparationVisibility);
    }

    @Override
    public void restore(S3kKosDecompressionQueueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        physicalEntries.clear();
        descriptors.clear();
        preparedEntries.clear();
        deferNewHeadPreparationVisibility =
                snapshot.deferNewHeadPreparationVisibility();
        for (S3kKosDecompressionQueueSnapshot.Entry entry : snapshot.entries()) {
            if (timing.pendingHandle(entry.handle().kind(), entry.handle().ordinal()).isEmpty()) {
                throw new IllegalArgumentException("direct queue snapshot references missing timing job");
            }
            descriptors.put(entry.handle(), entry.descriptor());
            if (entry.physical()) {
                physicalEntries.addLast(entry.handle());
            }
            if (entry.prepared()) {
                preparedEntries.add(entry.handle());
            }
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        physicalEntries.clear();
        descriptors.clear();
        preparedEntries.clear();
        deferNewHeadPreparationVisibility = false;
    }

    static HardwareWorkPreparation recreatePreparation(S3kKosDecompressionSnapshot snapshot) {
        return new DirectPreparation(snapshot);
    }

    private static final class DirectPreparation implements HardwareWorkPreparation {
        private final S3kKosDecompressionDescriptor descriptor;
        private final byte[] compressedBytes;
        private final ResumableKosinskiDecoder decoder;

        private DirectPreparation(S3kKosDecompressionDescriptor descriptor, byte[] compressedBytes) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.compressedBytes = Objects.requireNonNull(compressedBytes, "compressedBytes").clone();
            try {
                this.decoder = new ResumableKosinskiDecoder(this.compressedBytes);
            } catch (IOException exception) {
                throw new IllegalArgumentException("invalid standard Kosinski stream", exception);
            }
        }

        private DirectPreparation(S3kKosDecompressionSnapshot snapshot) {
            this.descriptor = snapshot.descriptor();
            this.compressedBytes = snapshot.compressedBytes();
            this.decoder = snapshot.recreateDecoder();
        }

        @Override
        public boolean stepOneWorkUnit() {
            if (decoder.complete()) {
                return false;
            }
            try {
                return decoder.step(1).descriptorsProcessed() != 0;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to prepare S3K Kosinski stream", exception);
            }
        }

        @Override
        public boolean isPrepared() {
            return decoder.complete();
        }

        @Override
        public byte[] preparedPayload() {
            if (!decoder.complete()) {
                throw new IllegalStateException("standard Kosinski stream is not prepared");
            }
            return decoder.output();
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new S3kKosDecompressionSnapshot(descriptor, compressedBytes, decoder.snapshot());
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            throw new UnsupportedOperationException(
                    "hardware timing restores by recreating preparations");
        }

        @Override
        public boolean isBoundaryDriven() {
            return true;
        }

        @Override
        public boolean serviceBoundary(HardwareServiceBoundary boundary) {
            if (boundary != HardwareServiceBoundary.PRE_MAIN_LOOP || decoder.complete()) {
                return false;
            }
            boolean advanced = false;
            while (!decoder.complete()) {
                advanced |= stepOneWorkUnit();
            }
            return advanced;
        }
    }
}
