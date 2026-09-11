package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkFeatures;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kKosDecompressionQueue {
    private static final byte[] ABC_STREAM = {
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    @Test
    void extractsDeterministicWorkFeaturesFromStandardKosCommands() throws Exception {
        HardwareWorkFeatures features =
                S3kKosinskiWorkFeatureExtractor.inspect(ABC_STREAM, 0, 1, 0);

        assertEquals(new HardwareWorkFeatures(
                3, 0, 0, 0, 8, 3, 1, 3, 0), features);
    }

    @Test
    void workFeaturesDistinguishShortLongAndNoOutputCommands()
            throws Exception {
        byte[] shortMatch = {0x41, 0, 'A', (byte) 0xFF, 0, 0, 0};
        byte[] longMatch = {
                0x15, 0, 'A', (byte) 0xFF, (byte) 0xF9, 0, 0, 0
        };
        byte[] noOutput = {
                0x15, 0, 'A', (byte) 0xFF, 0, 1, 0, 0, 0
        };

        assertEquals(new HardwareWorkFeatures(
                        1, 1, 0, 2, 7, 3, 1, 3, 0),
                S3kKosinskiWorkFeatureExtractor.inspect(
                        shortMatch, 0, 1, 0));
        assertEquals(new HardwareWorkFeatures(
                        1, 0, 1, 3, 8, 4, 1, 4, 0),
                S3kKosinskiWorkFeatureExtractor.inspect(
                        longMatch, 0, 1, 0));
        assertEquals(new HardwareWorkFeatures(
                        1, 0, 0, 0, 9, 1, 1, 1, 0),
                S3kKosinskiWorkFeatureExtractor.inspect(
                        noOutput, 0, 1, 0));
    }

    @Test
    void standardKosStartsAtDescriptorAndPublishesOnlyAtPreMainLoop() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);

            HardwareWorkHandle handle = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);

            assertEquals(ABC_STREAM.length, queue.descriptor(handle).compressedLength());
            assertEquals(3, queue.descriptor(handle).destinationLength());
            assertTrue(queue.decompressionsPending());
            timing.service(HardwareServiceBoundary.POST_OBJECTS);
            assertFalse(queue.isReady(handle));
            queue.afterTimingService(HardwareServiceBoundary.POST_OBJECTS);
            assertTrue(queue.decompressionsPending());

            serviceUntilReady(timing, queue, handle);

            assertTrue(queue.isReady(handle));
            assertFalse(queue.decompressionsPending());
            assertArrayEquals(new byte[] {'A', 'B', 'C'}, queue.claim(handle));
        }
    }

    @Test
    void readyButUnclaimedPayloadDoesNotOccupyPhysicalFifoCapacity() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle first = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);
            serviceUntilReady(timing, queue, first);
            assertTrue(queue.isReady(first));

            for (int index = 0; index < 4; index++) {
                queue.queueStandardKos(rom, 0,
                        S3kKosRamDestinations.blockTableOffset(index * 8));
            }

            assertEquals(4, queue.physicalQueueSize());
            assertThrows(IllegalStateException.class, () -> queue.queueStandardKos(
                    rom, 0, S3kKosRamDestinations.BLOCK_TABLE));
            assertTrue(queue.decompressionsPending(),
                    "the four new physical submissions remain pending");
        }
    }

    @Test
    void diagnosticsContainOnlyPhysicalEntries() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue =
                    new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle retired = queue.queueStandardKos(
                    rom, 0, S3kKosRamDestinations.BLOCK_TABLE);
            serviceUntilReady(timing, queue, retired);
            queue.queueStandardKos(
                    rom, 0, S3kKosRamDestinations.BLOCK_TABLE);

            QueueDiagnosticSnapshot snapshot = queue.captureDiagnostics(
                    java.util.List.of());
            assertEquals(QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                    snapshot.kind());
            assertEquals(1, queue.physicalQueueSize());
            assertEquals(0, snapshot.activeSource());
            assertEquals(-1, snapshot.activeTotalWork());
            assertFalse(snapshot.prepared(),
                    "a newly queued retail descriptor is armed at PRE_MAIN_LOOP");
            assertEquals(java.util.List.of(), snapshot.serviceObservations());
        }
    }

    @Test
    void recordedDirectJobWaitsForItsExactPreMainLoopEdge() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            var authority = timing.beginRecordedAdmission(java.util.Map.of(
                    com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                    HardwareReadinessAdmissionPolicy.RECORDED,
                    com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE,
                    HardwareReadinessAdmissionPolicy.LIVE,
                    com.openggf.game.timing.HardwareWorkKind.NEMESIS_PLC_QUEUE,
                    HardwareReadinessAdmissionPolicy.LIVE));
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle handle = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);
            assertFalse(queue.captureDiagnostics(java.util.List.of()).prepared());

            for (int frame = 0; frame < 4; frame++) {
                timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
                queue.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            }
            assertTrue(queue.captureDiagnostics(java.util.List.of()).prepared());
            assertFalse(queue.isReady(handle));
            assertTrue(queue.decompressionsPending());

            authority.admitRecordedCompletion(HardwareServiceBoundary.PRE_MAIN_LOOP,
                    handle.kind(), handle.ordinal(), handle.submissionFingerprint());
            queue.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertTrue(queue.isReady(handle));
            assertFalse(queue.decompressionsPending());
        }
    }

    @Test
    void retirementShiftsIdenticalAdjacentJobsWithoutChangingTheirHandles() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle first = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);
            HardwareWorkHandle second = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);

            serviceUntilReady(timing, queue, first);
            HardwareWorkHandle third = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);

            var entries = queue.capture().entries().stream()
                    .filter(S3kKosDecompressionQueueSnapshot.Entry::physical)
                    .toList();
            assertEquals(2, entries.size());
            assertEquals(second, entries.get(0).handle());
            assertEquals(third, entries.get(1).handle());
            assertTrue(queue.isReady(first));
        }
    }

    @Test
    void restoresPhysicalFifoAndQueuedDecoderFromMatchingTimingSnapshot() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle handle = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);
            var timingSnapshot = timing.capture();
            var queueSnapshot = queue.capture();
            assertEquals(3,
                    timingSnapshot.jobs().getFirst().features().literalCommands());
            assertEquals(8,
                    timingSnapshot.jobs().getFirst().features().compressedLength());

            serviceUntilReady(timing, queue, handle);
            assertTrue(queue.isReady(handle));

            timing.restore(timingSnapshot);
            queue.restore(queueSnapshot);
            assertFalse(queue.isReady(handle));
            assertTrue(queue.decompressionsPending());
            serviceUntilReady(timing, queue, handle);
            assertArrayEquals(new byte[] {'A', 'B', 'C'}, queue.claim(handle));
        }
    }

    @Test
    void canonicalRamDestinationHighBitsChangeSubmissionFingerprint() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);

            HardwareWorkHandle canonical = queue.queueStandardKos(rom, 0, 0xFFFF9000);
            HardwareWorkHandle truncated = queue.queueStandardKos(rom, 0, 0x00FF9000);

            assertNotEquals(canonical.submissionFingerprint(), truncated.submissionFingerprint());
            assertEquals(0xFFFF9000, queue.descriptor(canonical).destinationAddress());
        }
    }

    @Test
    void claimedDescriptorIsNotCapturedOrRestored() throws Exception {
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
            HardwareWorkHandle handle = queue.queueStandardKos(rom, 0,
                    S3kKosRamDestinations.BLOCK_TABLE);
            serviceUntilReady(timing, queue, handle);
            queue.claim(handle);

            var timingSnapshot = timing.capture();
            var queueSnapshot = queue.capture();
            timing.restore(timingSnapshot);
            queue.restore(queueSnapshot);

            assertTrue(queueSnapshot.entries().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> queue.descriptor(handle));
        }
    }

    private Rom romWith(byte[] bytes) throws Exception {
        Path path = tempDir.resolve("fixture-" + Files.list(tempDir).count() + ".gen");
        Files.write(path, bytes);
        Rom rom = new Rom();
        assertTrue(rom.open(path.toString()));
        return rom;
    }

    private static void serviceUntilReady(HardwareTimingService timing,
                                          S3kKosDecompressionQueue queue,
                                          HardwareWorkHandle handle) {
        for (int frame = 0; frame < 16 && !queue.isReady(handle); frame++) {
            timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            queue.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
        }
        assertTrue(queue.isReady(handle));
    }
}
