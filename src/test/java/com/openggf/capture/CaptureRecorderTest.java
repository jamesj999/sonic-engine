package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureRecorderTest {

    private static class FakeEncoder implements CaptureEncoder {
        final List<Long> encoded = new ArrayList<>();
        Path openedOutput;
        int width, height, fps, sampleRate;
        Path finishedAt;

        @Override public void open(Path output, int w, int h, int f, int sr) {
            openedOutput = output; width = w; height = h; fps = f; sampleRate = sr;
        }
        @Override public void encode(CapturedFrame frame) { encoded.add(frame.frameIndex()); }
        @Override public Path finish() { finishedAt = openedOutput; return finishedAt; }
        @Override public void abort() { }
    }

    private static CapturedFrame frame(long i) {
        return new CapturedFrame(new byte[4], 1, 1, new short[2], 1, i);
    }

    @Test
    void resolvesTimestampedPathAndDrivesEncoder() throws Exception {
        FakeEncoder enc = new FakeEncoder();
        CaptureRecorder recorder = new CaptureRecorder(
                enc, BackpressurePolicy.BLOCK, 4,
                Path.of("/out"), "aiz1", "20260603-101500");
        Path expected = Path.of("/out", "capture-aiz1-20260603-101500.mkv");
        assertEquals(expected, recorder.outputFile());

        recorder.start(320, 224, 60, 48000);
        for (long i = 0; i < 5; i++) recorder.submit(frame(i));
        Path result = recorder.stop();

        assertEquals(expected, enc.openedOutput, "recorder hands its output path to the encoder");
        assertEquals(expected, result, "stop returns the finalized output file");
        assertEquals(320, enc.width);
        assertEquals(224, enc.height);
        assertEquals(60, enc.fps);
        assertEquals(48000, enc.sampleRate);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), enc.encoded);
        assertNotNull(enc.finishedAt);
    }

    @Test void abortIsIdempotentAndDelegated() {
        class AbortEncoder extends FakeEncoder {
            int aborts;
            @Override public void abort() { aborts++; }
        }
        AbortEncoder enc = new AbortEncoder();
        CaptureRecorder recorder = new CaptureRecorder(enc, BackpressurePolicy.BLOCK, 1,
                Path.of("target"), "live", "now");
        recorder.abort();
        recorder.abort();
        assertEquals(1, enc.aborts);
    }
}
