package com.openggf.data.compression;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResumableKosinskiDecoder {
    private static final byte[] ABC_STREAM = {
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };

    @Test
    void pausesOnDescriptorBudgetAndMatchesSynchronousDecoder() throws Exception {
        ResumableKosinskiDecoder decoder = new ResumableKosinskiDecoder(ABC_STREAM);

        DecoderStepResult first = decoder.step(2);

        assertFalse(first.complete());
        assertArrayEquals(new byte[] {'A', 'B'}, decoder.output());

        int steps = 0;
        while (!decoder.complete()) {
            assertTrue(steps++ < 4096,
                    "the resumable decoder must complete within a bounded number of steps");
            decoder.step(1);
        }

        assertArrayEquals(
                KosinskiReader.decompress(Channels.newChannel(
                        new ByteArrayInputStream(ABC_STREAM))),
                decoder.output());
    }

    @Test
    void snapshotRestoresDescriptorAndOutputState() throws Exception {
        ResumableKosinskiDecoder original = new ResumableKosinskiDecoder(ABC_STREAM);
        original.step(2);

        ResumableKosinskiDecoder restored =
                ResumableKosinskiDecoder.fromSnapshot(original.snapshot());

        assertFalse(restored.complete());
        int originalSteps = 0;
        while (!original.complete()) {
            assertTrue(originalSteps++ < 4096,
                    "the original decoder must complete within a bounded number of steps");
            original.step(1);
        }
        int restoredSteps = 0;
        while (!restored.complete()) {
            assertTrue(restoredSteps++ < 4096,
                    "the snapshot-restored decoder must complete within a bounded number of steps");
            restored.step(1);
        }

        assertTrue(restored.complete());
        assertArrayEquals(original.output(), restored.output());
    }

    @Test
    void rejectsBackreferenceBeforeAnyOutput() throws Exception {
        byte[] invalidBackreference = {
                0x04, 0x00,
                (byte) 0xFF
        };

        ResumableKosinskiDecoder decoder =
                new ResumableKosinskiDecoder(invalidBackreference);

        assertThrows(java.io.IOException.class, () -> decoder.step(1));
    }

    @Test
    void scannerCountsStandardKosDescriptorThroughTerminator() throws Exception {
        KosinskiReader.StandardArchiveInfo info = KosinskiReader.inspectStandard(
                ABC_STREAM, 0);

        assertEquals(ABC_STREAM.length, info.compressedLength());
        assertEquals(3, info.decompressedLength());
    }

    @Test
    void scannerHandlesDescriptorRefillAndAllMatchForms() throws Exception {
        byte[] refill = new byte[23];
        refill[0] = (byte) 0xFF;
        refill[1] = (byte) 0xFF;
        for (int index = 0; index < 15; index++) refill[index + 2] = (byte) index;
        refill[17] = 0x02;
        refill[18] = 0;
        refill[19] = 15;
        assertEquals(16, KosinskiReader.inspectStandard(refill, 0).decompressedLength());

        // LSB-first: literal, two-byte short match, then long-form terminator.
        byte[] shortMatch = {0x41, 0, 'A', (byte) 0xFF, 0, 0, 0};
        byte[] longMatch = {0x15, 0, 'A', (byte) 0xFF, (byte) 0xF9, 0, 0, 0};
        byte[] noOutput = {0x15, 0, 'A', (byte) 0xFF, 0, 1, 0, 0, 0};
        assertEquals(3, KosinskiReader.inspectStandard(shortMatch, 0).decompressedLength());
        assertEquals(4, KosinskiReader.inspectStandard(longMatch, 0).decompressedLength());
        assertEquals(1, KosinskiReader.inspectStandard(noOutput, 0).decompressedLength());
    }

    @Test
    void scannerRejectsRomBoundAndInvalidBackreferenceStreams() {
        assertThrows(java.io.IOException.class,
                () -> KosinskiReader.inspectStandard(new byte[] {1, 0, 'A'}, 0));
        assertThrows(java.io.IOException.class,
                () -> KosinskiReader.inspectStandard(new byte[] {4, 0, (byte) 0xFF}, 0));
    }
}
