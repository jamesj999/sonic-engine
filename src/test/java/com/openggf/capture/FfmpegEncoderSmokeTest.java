package com.openggf.capture;

import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegEncoderSmokeTest {

    @Test
    void productionEncoderWritesTenFramesToANonEmptyMkv() throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        Assumptions.assumeTrue(ffmpeg.isPresent(), "ffmpeg not on PATH");
        Path out = TestTempFiles.createTempFile("trace-capture-smoke-", ".mkv");
        Files.deleteIfExists(out);

        FfmpegEncoder enc = new FfmpegEncoder(ffmpeg.get().toString(), 2);
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.BLOCK, 8);
        try {
            int w = 32, h = 24, fps = 30, sr = 48000, perFrame = sr / fps; // 1600
            sink.open(out, w, h, fps, sr);
            for (int i = 0; i < 10; i++) {
                byte[] rgba = new byte[w * h * 4];
                java.util.Arrays.fill(rgba, (byte) (i * 20)); // varying grey
                short[] pcm = new short[perFrame * 2];        // silence
                sink.submit(new CapturedFrame(rgba, w, h, pcm, perFrame, i));
            }
            Path result = sink.stop();

            assertTrue(Files.exists(result), "output mkv exists");
            assertTrue(Files.size(result) > 0, "output mkv non-empty");
        } finally {
            sink.abort();
            Files.deleteIfExists(out);
        }
    }
}
