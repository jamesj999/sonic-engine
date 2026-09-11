package com.openggf.audio.output;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TestSpeakerPacketFifo {
    @Test
    void capacityIsExactlyTwoSecondsAtNegotiatedRate() {
        SpeakerPacketFifo fifo = new SpeakerPacketFifo(10);
        fifo.offer(sequence(0, 20), 20);

        assertEquals(20, fifo.queuedStereoFrames());
        assertEquals(0, fifo.droppedStereoFrames());
    }

    @Test
    void overflowDropsOldestUntilOneSecondIsFree() {
        SpeakerPacketFifo fifo = new SpeakerPacketFifo(10);
        fifo.offer(sequence(0, 20), 20);
        fifo.offer(sequence(20, 4), 4);

        assertEquals(14, fifo.queuedStereoFrames());
        assertEquals(10, fifo.droppedStereoFrames());
    }

    @Test
    void newestTailIsRetainedInOriginalSampleOrder() {
        SpeakerPacketFifo fifo = new SpeakerPacketFifo(10);
        fifo.offer(sequence(0, 20), 20);
        fifo.offer(sequence(20, 4), 4);
        short[] drained = new short[28];

        assertEquals(14, fifo.drain(drained, 14));
        assertArrayEquals(sequence(10, 14), drained);
    }

    @Test
    void producerOfferNeverBlocksWhenConsumerStalls() {
        SpeakerPacketFifo fifo = new SpeakerPacketFifo(10);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            for (int index = 0; index < 100_000; index++) {
                fifo.offer(sequence(index, 4), 4);
            }
        });
    }

    @Test
    void flushDropsOnlySpeakerPackets() {
        SpeakerPacketFifo fifo = new SpeakerPacketFifo(10);
        fifo.offer(sequence(0, 8), 8);
        fifo.flush();

        assertEquals(0, fifo.queuedStereoFrames());
        assertEquals(0, fifo.droppedStereoFrames());
    }

    private static short[] sequence(int firstFrame, int stereoFrames) {
        short[] samples = new short[stereoFrames * 2];
        for (int frame = 0; frame < stereoFrames; frame++) {
            short value = (short) (firstFrame + frame);
            samples[frame * 2] = value;
            samples[frame * 2 + 1] = (short) -value;
        }
        return samples;
    }
}
