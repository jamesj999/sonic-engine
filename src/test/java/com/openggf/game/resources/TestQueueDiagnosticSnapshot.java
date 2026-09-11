package com.openggf.game.resources;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestQueueDiagnosticSnapshot {
    @Test
    void descriptorFingerprintsMatchCrossLanguageGoldenVectors() {
        assertEquals(
                "e1cb5d156a023180550e107c0fa41e1de038683d4cbe8c6176b3395bb4dae2fa",
                QueueDiagnosticSnapshot.fingerprint(
                        QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC,
                        0x123456, 0x345, 17));
        assertEquals(
                "61fd12622bb980712702efbb2b9a3f0fd8daf5c85c78c2c51b8129ba3ef5907b",
                QueueDiagnosticSnapshot.fingerprint(
                        QueueDiagnosticSnapshot.Kind.S2_NEMESIS_PLC,
                        0x1000, 0x400, 32));
        assertEquals(
                "1d3688513bf473d8934c182419593716b71a92a8c6d75bb48ae7b7f1dedbfa92",
                QueueDiagnosticSnapshot.fingerprint(
                        QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                        0x12233, 0xFF8000, null));
        assertEquals(
                "a283ef4143ceb64b27d9190da9cf3d3739166307bc7ad8fff658545391bd7133",
                QueueDiagnosticSnapshot.fingerprint(
                        QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                        0x20000, 0x10000, 8));
    }

    @Test
    void idleSnapshotIsCanonicalAndImmutable() {
        QueueDiagnosticSnapshot idle = QueueDiagnosticSnapshot.idle(
                QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC, List.of());

        assertFalse(idle.busy());
        assertFalse(idle.prepared());
        assertEquals(-1, idle.activeSource());
        assertEquals(-1, idle.activeDestination());
        assertEquals(-1, idle.activeTotalWork());
        assertEquals(-1, idle.activeRemainingWork());
        assertEquals(List.of(), idle.queuedFingerprints());
        assertThrows(UnsupportedOperationException.class,
                () -> idle.queuedFingerprints().add("x"));
    }

    @Test
    void snapshotRejectsContradictoryIdleState() {
        assertThrows(IllegalArgumentException.class, () ->
                new QueueDiagnosticSnapshot(
                        QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC,
                        false, true, 1, 2, 3, 3, List.of(), List.of()));
    }

    @Test
    void versionOneRejectsServiceObservations() {
        assertThrows(IllegalArgumentException.class, () ->
                new QueueDiagnosticSnapshot(
                        QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC,
                        true, true, 1, 2, 3, 2, List.of("abc"),
                        List.of(new QueueServiceObservation(
                                "ordinary_level", 3))));
    }
}
