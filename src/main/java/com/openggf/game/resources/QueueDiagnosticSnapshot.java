package com.openggf.game.resources;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable comparison-only projection of one physical load queue. */
public record QueueDiagnosticSnapshot(
        Kind kind,
        boolean busy,
        boolean prepared,
        int activeSource,
        int activeDestination,
        int activeTotalWork,
        int activeRemainingWork,
        List<String> queuedFingerprints,
        List<QueueServiceObservation> serviceObservations) {

    public enum Kind {
        S1_NEMESIS_PLC(1, "s1_nemesis_plc"),
        S2_NEMESIS_PLC(2, "s2_nemesis_plc"),
        S3K_KOS_DIRECT(3, "s3k_kos_direct"),
        S3K_KOS_MODULE(4, "s3k_kos_module");

        private final int wireId;
        private final String wireName;

        Kind(int wireId, String wireName) {
            this.wireId = wireId;
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Kind fromWireName(String wireName) {
            for (Kind value : values()) {
                if (value.wireName.equals(wireName)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown queue kind: " + wireName);
        }
    }

    public QueueDiagnosticSnapshot {
        Objects.requireNonNull(kind, "kind");
        queuedFingerprints = List.copyOf(
                Objects.requireNonNull(queuedFingerprints, "queuedFingerprints"));
        serviceObservations = List.copyOf(
                Objects.requireNonNull(serviceObservations, "serviceObservations"));
        if (!serviceObservations.isEmpty()) {
            throw new IllegalArgumentException(
                    "version 1 service observations must be empty");
        }
        if (!busy && (prepared
                || activeSource != -1
                || activeDestination != -1
                || activeTotalWork != -1
                || activeRemainingWork != -1
                || !queuedFingerprints.isEmpty())) {
            throw new IllegalArgumentException("idle queue state must be canonical");
        }
        if (busy && activeSource < -1) {
            throw new IllegalArgumentException("active source is invalid");
        }
    }

    public static QueueDiagnosticSnapshot idle(
            Kind kind, List<QueueServiceObservation> observations) {
        return new QueueDiagnosticSnapshot(
                kind, false, false, -1, -1, -1, -1,
                List.of(), observations);
    }

    public static String fingerprint(
            Kind kind, int source, int destination, Integer totalWork) {
        Objects.requireNonNull(kind, "kind");
        int size = totalWork == null ? 15 : 19;
        ByteBuffer bytes = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        bytes.put((byte) 'O').put((byte) 'Q').put((byte) 'D').put((byte) 'F');
        bytes.put((byte) 1);
        bytes.put((byte) kind.wireId);
        bytes.putInt(source);
        bytes.putInt(destination);
        bytes.put((byte) (totalWork == null ? 0 : 1));
        if (totalWork != null) {
            bytes.putInt(totalWork);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
