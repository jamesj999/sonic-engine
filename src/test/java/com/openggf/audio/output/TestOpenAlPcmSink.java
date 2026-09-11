package com.openggf.audio.output;

import com.openggf.audio.presentation.AudioPresentationCommandQueue;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationDependencyResolver;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.DecodedPcm;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.presentation.ResolvedSmpsSfxSource;
import com.openggf.audio.presentation.SampleBackedVoice;
import com.openggf.audio.presentation.SmpsSfxInstantiation;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.LiveCaptureAudioHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestOpenAlPcmSink {
    @Test
    void primesThreeDeviceBuffersBeforeStartingPlayback() {
        FakeDevice device = new FakeDevice(57_600);
        OpenAlPcmSink sink = sink(device);
        short[] source = stereoRamp(3_840);
        AudioPresentationProducer producer = producer(
                sink, 57_600, 60, sourceRegistry(57_600, source));
        int queued;
        try {
            producer.present(0, PresentationMode.FORWARD);
            producer.present(1, PresentationMode.FORWARD);
            producer.present(2, PresentationMode.FORWARD);
            sink.updateDevice();
            assertEquals(List.of(), device.enqueuedFrames);
            producer.present(3, PresentationMode.FORWARD);
            sink.updateDevice();
            queued = sink.queuedStereoFrames();
        } finally { producer.close(); }

        assertEquals(List.of(1_024, 1_024, 1_024),
                device.enqueuedFrames);
        assertArrayEquals(java.util.Arrays.copyOf(source, 1_024 * 2),
                device.enqueuedSamples.getFirst());
        assertEquals(768, queued);
    }

    @Test
    void reverseBoundaryFlushesDeviceAndSpeakerFifoBeforeReprime() {
        FakeDevice device = new FakeDevice(48_000);
        OpenAlPcmSink sink = sink(device);
        AudioPresentationProducer producer = producer(sink, 48_000, 60);
        try {
            producer.present(0, PresentationMode.SILENT);
            assertEquals(800, sink.queuedStereoFrames());
            producer.beginReverse(1.0);
        } finally { producer.close(); }

        assertEquals(0, sink.queuedStereoFrames());
        assertEquals(1, device.flushCount);
    }

    @Test
    void pausedSinkDoesNotAccumulateSilenceAcrossFocusTransitions() {
        FakeDevice device = new FakeDevice(48_000);
        OpenAlPcmSink sink = sink(device);
        AudioPresentationProducer producer = producer(sink, 48_000, 60);
        try {
            for (int frame = 0; frame < 4; frame++) {
                producer.present(frame, PresentationMode.FORWARD);
            }
            sink.updateDevice();
            assertEquals(128, sink.queuedStereoFrames());

            sink.pause();
            sink.pause();
            for (int frame = 4; frame < 184; frame++) {
                producer.present(frame, PresentationMode.SILENT);
                sink.updateDevice();
            }
            sink.pause();

            assertEquals(128, sink.queuedStereoFrames());
            assertEquals(1, device.pauseCount);
            sink.resume();
            assertEquals(1, device.resumeCount);
        } finally {
            producer.close();
        }
    }

    @Test
    void stalledDeviceDoesNotBlockProducerAndDropsSpeakerOnlyPcm() {
        FakeDevice device = new FakeDevice(10);
        OpenAlPcmSink sink = sink(device);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            AudioPresentationProducer producer = producer(sink, 10, 1);
            try {
                for (int frame = 0; frame < 10_000; frame++) {
                    producer.present(frame, PresentationMode.SILENT);
                }
            } finally {
                producer.close();
            }
        });
        assertTrue(sink.droppedStereoFrames() > 0);
    }

    @Test
    void overrunWarningIsRateLimitedToOncePerSecond() {
        FakeDevice device = new FakeDevice(10);
        AtomicLong nanos = new AtomicLong();
        List<String> warnings = new ArrayList<>();
        OpenAlPcmSink sink = new OpenAlPcmSink(device, ignored -> {
        }, nanos::get, warnings::add);
        AudioPresentationProducer producer = producer(sink, 10, 1);
        try {
            for (int frame = 0; frame < 5; frame++) {
                producer.present(frame, PresentationMode.SILENT);
            }
            assertEquals(1, warnings.size());
            nanos.set(999_999_999L);
            producer.present(5, PresentationMode.SILENT);
            assertEquals(1, warnings.size());
            nanos.set(1_000_000_000L);
            producer.present(6, PresentationMode.SILENT);
            assertEquals(2, warnings.size());
        } finally {
            producer.close();
        }
    }

    @Test
    void enqueueFailureAtomicallyRequestsNoDeviceReplacementOnce() {
        FakeDevice device = new FakeDevice(61_440);
        device.failEnqueue = true;
        AtomicInteger failures = new AtomicInteger();
        OpenAlPcmSink sink = new OpenAlPcmSink(device,
                ignored -> failures.incrementAndGet(), System::nanoTime,
                ignored -> {
                });
        AudioPresentationProducer producer = producer(sink, 61_440, 60);
        try {
            producer.present(0, PresentationMode.SILENT);
            producer.present(1, PresentationMode.SILENT);
            producer.present(2, PresentationMode.SILENT);
            sink.updateDevice();
            sink.updateDevice();
        } finally { producer.close(); }

        assertEquals(1, failures.get());
        assertEquals(1, device.closeCount);
        assertEquals(0, sink.queuedStereoFrames());
    }

    @Test
    void updateFailureClearsStaleSpeakerPacketsAndRequestsReplacementOnce() {
        FakeDevice device = new FakeDevice(48_000);
        device.failUpdate = true;
        AtomicInteger failures = new AtomicInteger();
        OpenAlPcmSink sink = new OpenAlPcmSink(device,
                ignored -> failures.incrementAndGet(), System::nanoTime,
                ignored -> {
                });
        AudioPresentationProducer producer = producer(sink, 48_000, 60);
        try {
            producer.present(0, PresentationMode.SILENT);
            sink.updateDevice();
            sink.updateDevice();
        } finally { producer.close(); }

        assertEquals(1, failures.get());
        assertEquals(1, device.closeCount);
        assertEquals(0, sink.queuedStereoFrames());
    }

    @Test
    void deviceCleanupFailureDoesNotPreventNoDeviceReplacement() {
        FakeDevice device = new FakeDevice(48_000);
        device.failUpdate = true;
        device.failClose = true;
        List<Throwable> failures = new ArrayList<>();
        OpenAlPcmSink sink = new OpenAlPcmSink(device,
                failures::add, System::nanoTime, ignored -> {
                });

        sink.updateDevice();

        assertEquals(1, failures.size());
        assertEquals(1, failures.getFirst().getSuppressed().length);
        assertEquals(1, device.closeCount);
    }

    @Test
    void closeIsIdempotent() {
        FakeDevice device = new FakeDevice(48_000);
        OpenAlPcmSink sink = sink(device);
        sink.close();
        sink.close();

        assertEquals(1, device.closeCount);
    }

    @Test
    void replacingOnlyTheSinkPreservesProducerHistoryClockCaptureAndNextPacket() {
        FakeDevice device = new FakeDevice(48_000);
        OpenAlPcmSink first = sink(device);
        short[] source = stereoRamp(4_800);
        AudioPresentationProducer producer = producer(
                first, 48_000, 60, sourceRegistry(48_000, source));
        RecordingSink controlSink = new RecordingSink(48_000);
        AudioPresentationProducer control = producer(
                controlSink, 48_000, 60, sourceRegistry(48_000, source));
        producer.setHistoryArmed(true);
        control.setHistoryArmed(true);
        producer.present(0, PresentationMode.FORWARD);
        control.present(0, PresentationMode.FORWARD);
        producer.beginReverse(1.0);
        control.beginReverse(1.0);
        LiveCaptureAudioHandle capture = producer.attachCapture(60);
        var before = producer.transactionFingerprint();
        RecordingSink replacement = new RecordingSink(48_000);

        producer.replaceSink(replacement);

        assertEquals(before, producer.transactionFingerprint());
        assertEquals(1, before.voiceIdentities().size());
        assertTrue(before.history().storedFrames() > 0);
        assertTrue(before.reverseActive());
        assertEquals(1, before.captureCount());
        producer.present(1, PresentationMode.REVERSE);
        control.present(1, PresentationMode.REVERSE);
        short[] captured =
                new short[capture.maxStereoFramesPerPacket() * 2];
        int frames = capture.drainPresentationFrame(captured);
        assertEquals(replacement.frames, frames);
        assertTrue(java.util.Arrays.stream(toInts(replacement.samples))
                .anyMatch(sample -> sample != 0));
        assertArrayEquals(controlSink.samples, replacement.samples,
                "sink replacement must not change the exact next PCM packet");
        assertArrayEquals(replacement.samples,
                java.util.Arrays.copyOf(captured, frames * 2));
        capture.close();
        producer.close();
        control.close();
    }

    private static OpenAlPcmSink sink(FakeDevice device) {
        return new OpenAlPcmSink(device, ignored -> {
        }, System::nanoTime, ignored -> {
        });
    }

    private static AudioPresentationProducer producer(
            AudioPresentationSink sink, int sampleRate, int frameRate) {
        return producer(sink, sampleRate, frameRate,
                new AudioVoiceRegistry());
    }

    private static AudioPresentationProducer producer(
            AudioPresentationSink sink, int sampleRate, int frameRate,
            AudioVoiceRegistry registry) {
        int maxFrames = (sampleRate + frameRate - 1) / frameRate;
        return new AudioPresentationProducer(sampleRate, frameRate,
                sampleRate, 0, registry,
                new AudioPresentationCommandQueue(),
                new AudioPresentationMixer(maxFrames), sink);
    }

    private static AudioVoiceRegistry sourceRegistry(
            int sampleRate, short[] source) {
        DecodedPcm pcm = new DecodedPcm(
                "source-bearing-test", 2, sampleRate, source);
        AudioPresentationDependencyResolver resolver =
                new AudioPresentationDependencyResolver() {
                    @Override public DecodedPcm resolvePcm(String assetId) {
                        return pcm;
                    }

                };
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                new SmpsSfxInstantiation() {
                    @Override public SmpsSequencer instantiateCached(
                            ResolvedSmpsSfxSource ignored,
                            SmpsDriver currentOwner) {
                        return null;
                    }

                },
                resolver,
                new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState()),
                ignored -> {
                });
        SampleBackedVoice voice = SampleBackedVoice.oneShot(
                1, 10, pcm, sampleRate, 1.0f, 1.0f);
        registry.apply(
                AudioPresentationCommand.StartSampleSfx.fromVoice(voice));
        return registry;
    }

    private static short[] stereoRamp(int stereoFrames) {
        short[] samples = new short[stereoFrames * 2];
        for (int sample = 0; sample < samples.length; sample++) {
            samples[sample] = (short) (sample + 1);
        }
        return samples;
    }

    private static int[] toInts(short[] samples) {
        int[] converted = new int[samples.length];
        for (int index = 0; index < samples.length; index++) {
            converted[index] = samples[index];
        }
        return converted;
    }

    private static final class FakeDevice implements OpenAlPcmSink.Device {
        private final int sampleRate;
        private final List<Integer> enqueuedFrames = new ArrayList<>();
        private final List<short[]> enqueuedSamples = new ArrayList<>();
        private boolean failEnqueue;
        private boolean failUpdate;
        private boolean failClose;
        private int flushCount;
        private int closeCount;
        private int queuedBuffers;
        private int pauseCount;
        private int resumeCount;

        private FakeDevice(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override
        public int initialize() {
            return sampleRate;
        }

        @Override
        public void enqueue(
                short[] stereoPcm, int stereoFrames, int sampleRate) {
            if (failEnqueue) {
                throw new IllegalStateException("enqueue");
            }
            enqueuedFrames.add(stereoFrames);
            enqueuedSamples.add(java.util.Arrays.copyOf(
                    stereoPcm, stereoFrames * 2));
            queuedBuffers++;
        }

        @Override
        public int update() {
            if (failUpdate) {
                throw new IllegalStateException("update");
            }
            return queuedBuffers;
        }

        @Override
        public void flush() {
            flushCount++;
            queuedBuffers = 0;
        }

        @Override
        public void pause() {
            pauseCount++;
        }

        @Override
        public void resume() {
            resumeCount++;
        }

        @Override
        public void close() {
            closeCount++;
            if (failClose) {
                throw new IllegalStateException("close");
            }
        }
    }

    private static final class RecordingSink
            implements AudioPresentationSink {
        private final int sampleRate;
        private short[] samples = new short[0];
        private int frames;

        private RecordingSink(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override public int sampleRate() {
            return sampleRate;
        }

        @Override
        public void accept(
                com.openggf.audio.presentation.AudioPresentationFrameView frame) {
            frames = frame.stereoFrames();
            samples = new short[frames * 2];
            frame.copyTo(samples, 0);
        }

        @Override public void onReverseBoundary() {
        }

        @Override public void close() {
        }
    }
}
