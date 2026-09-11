package com.openggf.audio.presentation;

import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.runtime.AudioFrameClock;
import com.openggf.audio.runtime.PcmHistoryRing;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.synth.ChipWriteObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioPresentationProducerRewind {
    @Test
    void transactionIdentityFingerprintsContainOnlyScalarTokens() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);

        var first = fixture.producer.transactionFingerprint();
        var second = fixture.producer.transactionFingerprint();

        assertEquals(first.voiceIdentities(), second.voiceIdentities());
        for (var field : AudioPresentationProducer.IdentityFingerprint.class
                .getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                assertTrue(field.getType().isPrimitive(),
                        "diagnostic identities must not retain live objects: "
                                + field);
            }
        }
    }

    @Test
    void reverseEntryFlushesSinkBeforeFirstReversePacket() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);

        fixture.producer.beginReverse(1.0);
        fixture.producer.present(1, PresentationMode.REVERSE);

        assertEquals(
                List.of("forward", "reverse-boundary", "reverse"),
                fixture.sink.events);
    }

    @Test
    void reverseRateChangesOnlyProducerOwnedCursor() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        fixture.producer.present(2, PresentationMode.FORWARD);
        long voicePosition = fixture.musicPosition();
        LiveCaptureAudioHandle capture = fixture.producer.attachCapture(2);

        fixture.producer.beginReverse(1.0);
        fixture.producer.present(3, PresentationMode.REVERSE);
        short[] firstCapture = new short[4];
        assertEquals(2, capture.drainPresentationFrame(firstCapture));
        assertArrayEquals(new short[] {5, 105, 4, 104}, firstCapture);

        fixture.producer.setReverseRate(2.0);
        fixture.producer.present(4, PresentationMode.REVERSE);
        short[] secondCapture = new short[4];
        assertEquals(2, capture.drainPresentationFrame(secondCapture));

        assertArrayEquals(new short[] {3, 103, 1, 101},
                fixture.sink.lastPacket());
        assertArrayEquals(fixture.sink.lastPacket(), secondCapture);
        assertEquals(voicePosition, fixture.musicPosition(),
                "reverse and capture consumption must not advance voices");

        PcmHistoryRing diagnosticHistory = new PcmHistoryRing(4);
        diagnosticHistory.write(new short[] {1, 10, 2, 20}, 2);
        PcmHistoryRing.ReverseCursor diagnosticCursor =
                diagnosticHistory.createReverseCursor();
        diagnosticCursor.setRate(2.0);
        assertEquals(new PcmHistoryRing.CursorState(1.0, 0, 2.0, 0),
                diagnosticCursor.state(),
                "cursor diagnostics expose the producer-owned rate and epoch");
    }

    @Test
    void captureAttachedDuringHeldRewindGetsTheNextSameReversePacket() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(2, PresentationMode.REVERSE);

        LiveCaptureAudioHandle capture = fixture.producer.attachCapture(2);
        fixture.producer.present(3, PresentationMode.REVERSE);
        short[] captured = new short[4];

        assertEquals(2, capture.drainPresentationFrame(captured));
        assertArrayEquals(fixture.sink.lastPacket(), captured);
        assertArrayEquals(new short[] {1, 101, 0, 100}, captured);
    }

    @Test
    void lateCaptureAttachAlignsWithFractionalHeldReversePackets() {
        Fixture fixture = fixture(5, 2);
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(2, PresentationMode.REVERSE);

        LiveCaptureAudioHandle capture = fixture.producer.attachCapture(2);
        assertEquals(new AudioFrameClock.Snapshot(5, 2, 0, 1),
                capture.clockSnapshot());
        short[] captured =
                new short[capture.maxStereoFramesPerPacket() * 2];

        fixture.producer.present(3, PresentationMode.REVERSE);
        assertEquals(3, capture.drainPresentationFrame(captured));
        assertArrayEquals(fixture.sink.lastPacket(), captured);
        assertArrayEquals(new short[] {2, 102, 1, 101, 0, 100},
                captured);
        assertEquals(new AudioFrameClock.Snapshot(5, 2, 3, 0),
                capture.clockSnapshot());

        fixture.producer.present(4, PresentationMode.REVERSE);
        assertEquals(2, capture.drainPresentationFrame(captured));
        assertArrayEquals(fixture.sink.lastPacket(),
                Arrays.copyOf(captured, 4));
        assertEquals(new AudioFrameClock.Snapshot(5, 2, 5, 1),
                capture.clockSnapshot());
    }

    @Test
    void reverseDoesNotRenderOrAppendForwardHistory() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        long voicePosition = fixture.musicPosition();
        fixture.producer.beginReverse(1.0);

        fixture.producer.present(2, PresentationMode.REVERSE);
        fixture.producer.present(3, PresentationMode.REVERSE);

        assertEquals(voicePosition, fixture.musicPosition());
        fixture.producer.endReverse();
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(4, PresentationMode.REVERSE);
        assertArrayEquals(new short[] {0, 0, 0, 0},
                fixture.sink.lastPacket(),
                "consumed reverse packets must not be appended as forward history");
    }

    @Test
    void releaseCommitsSelectedLogicalSnapshotAndCrossfadesExactlyOnce() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.commands.applyPending(fixture.registry::apply);
        AudioPresentationSnapshot selected = fixture.producer.snapshot();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        AudioPresentationSnapshot beforeDeferredRestore =
                fixture.producer.snapshot();
        assertNotEquals(selected, beforeDeferredRestore);
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(2, PresentationMode.REVERSE);

        fixture.producer.restore(selected, fixture.resolver, true);
        assertEquals(beforeDeferredRestore, fixture.producer.snapshot(),
                "logical restore is selected during reverse, then committed on release");
        fixture.producer.endReverse();
        assertEquals(selected, fixture.producer.snapshot());
        fixture.sink.expectCrossfade = true;
        fixture.producer.present(3, PresentationMode.FORWARD);
        assertArrayEquals(new short[] {1, 101, 1, 101},
                fixture.sink.lastPacket(),
                "two-frame release crossfade must bridge from the last reverse frame");
        fixture.producer.present(4, PresentationMode.FORWARD);
        assertArrayEquals(new short[] {2, 102, 3, 103},
                fixture.sink.lastPacket(),
                "release crossfade must be applied exactly once");

        assertEquals(List.of(
                "forward", "forward", "reverse-boundary", "reverse",
                "reverse-boundary", "crossfade", "forward"),
                fixture.sink.events);
    }

    @Test
    void failedReleaseRestoreKeepsCompleteRegistryAndReverseStateForRetry() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.commands.applyPending(fixture.registry::apply);
        AudioPresentationSnapshot selected = fixture.producer.snapshot();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        AudioPresentationSnapshot beforeRelease = fixture.producer.snapshot();
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(2, PresentationMode.REVERSE);

        AtomicBoolean fail = new AtomicBoolean(true);
        AudioPresentationDependencyResolver flaky =
                new AudioPresentationDependencyResolver() {
                    @Override
                    public DecodedPcm resolvePcm(String assetId) {
                        if (fail.getAndSet(false)) {
                            throw new IllegalStateException("release dependency");
                        }
                        return fixture.resolver.resolvePcm(assetId);
                    }

                };
        fixture.producer.restore(selected, flaky, true);

        assertThrows(IllegalStateException.class,
                fixture.producer::endReverse);
        assertEquals(beforeRelease, fixture.producer.snapshot(),
                "failed release must leave the complete live registry intact");

        fixture.producer.endReverse();
        assertEquals(selected, fixture.producer.snapshot(),
                "the same deferred selection must remain retryable");
        fixture.producer.present(3, PresentationMode.FORWARD);
        assertArrayEquals(new short[] {1, 101, 1, 101},
                fixture.sink.lastPacket(),
                "history cursor and release crossfade must survive the retry");
    }

    @Test
    void hardBoundaryEpochMakesStaleReverseCursorReturnSilence() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.beginReverse(1.0);

        fixture.producer.clearHistory();
        fixture.producer.present(1, PresentationMode.REVERSE);

        assertArrayEquals(new short[4], fixture.sink.lastPacket());
        assertEquals(2L << 32, fixture.musicPosition());
    }

    @Test
    void repeatedForwardReverseAndCaptureAttachDetachPreserveOneTimeline() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.present(1, PresentationMode.FORWARD);
        fixture.producer.beginReverse(0.5);

        LiveCaptureAudioHandle reverseCapture =
                fixture.producer.attachCapture(2);
        fixture.producer.present(2, PresentationMode.REVERSE);
        short[] reversePcm = new short[4];
        reverseCapture.drainPresentationFrame(reversePcm);
        assertArrayEquals(fixture.sink.lastPacket(), reversePcm);
        assertEquals(2, reverseCapture.totalStereoFrames());
        reverseCapture.close();

        fixture.producer.endReverse();
        fixture.producer.present(3, PresentationMode.FORWARD);
        LiveCaptureAudioHandle forwardCapture =
                fixture.producer.attachCapture(2);
        fixture.producer.present(4, PresentationMode.FORWARD);
        short[] forwardPcm = new short[4];
        forwardCapture.drainPresentationFrame(forwardPcm);

        assertArrayEquals(fixture.sink.lastPacket(), forwardPcm);
        assertEquals(2, forwardCapture.totalStereoFrames());
        assertEquals(8L << 32, fixture.musicPosition(),
                "capture attach/detach must not fork or advance the producer timeline");
    }

    @Test
    void exhaustedReversePacketCrossfadesFromBroadcastSilence() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.beginReverse(1.0);
        fixture.producer.present(1, PresentationMode.REVERSE);
        fixture.producer.present(2, PresentationMode.REVERSE);
        assertArrayEquals(new short[4], fixture.sink.lastPacket());

        fixture.producer.endReverse();
        fixture.producer.present(3, PresentationMode.FORWARD);

        assertArrayEquals(new short[] {1, 51, 3, 103},
                fixture.sink.lastPacket(),
                "release must crossfade from the final padded PCM frame actually broadcast");
    }

    @Test
    void partialReverseTailCrossfadesFromItsPaddedFinalFrame() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.beginReverse(2.0);
        fixture.producer.present(1, PresentationMode.REVERSE);
        assertArrayEquals(new short[] {1, 101, 0, 0},
                fixture.sink.lastPacket());

        fixture.producer.endReverse();
        fixture.producer.present(2, PresentationMode.FORWARD);

        assertArrayEquals(new short[] {1, 51, 3, 103},
                fixture.sink.lastPacket());
    }

    @Test
    void staleEpochReverseSilenceIsTheReleaseCrossfadeSource() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.producer.present(0, PresentationMode.FORWARD);
        fixture.producer.beginReverse(1.0);
        fixture.producer.clearHistory();
        fixture.producer.present(1, PresentationMode.REVERSE);
        assertArrayEquals(new short[4], fixture.sink.lastPacket());

        fixture.producer.endReverse();
        fixture.producer.present(2, PresentationMode.FORWARD);

        assertArrayEquals(new short[] {1, 51, 3, 103},
                fixture.sink.lastPacket());
    }

    @Test
    void hardBoundaryClearsDeferredLogicalRestore() {
        Fixture fixture = fixture();
        fixture.startMusic();
        fixture.commands.applyPending(fixture.registry::apply);
        AudioPresentationSnapshot staleSelection =
                fixture.producer.snapshot();
        fixture.producer.present(0, PresentationMode.FORWARD);
        AudioPresentationSnapshot hardBoundaryState =
                fixture.producer.snapshot();
        assertNotEquals(staleSelection, hardBoundaryState);
        fixture.producer.beginReverse(1.0);
        fixture.producer.restore(staleSelection, fixture.resolver, true);

        fixture.producer.clearHistory();
        fixture.producer.endReverse();

        assertEquals(hardBoundaryState, fixture.producer.snapshot(),
                "a hard history epoch must discard pre-boundary deferred logical state");
    }

    private static Fixture fixture() {
        return fixture(4, 2);
    }

    private static Fixture fixture(int sampleRate, int frameRate) {
        DecodedPcm pcm = rampStereo(32, sampleRate);
        AudioPresentationDependencyResolver resolver =
                new AudioPresentationDependencyResolver() {
                    @Override
                    public DecodedPcm resolvePcm(String assetId) {
                        assertEquals(pcm.assetId(), assetId);
                        return pcm;
                    }

                };
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                noSmps(), resolver,
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                warning -> {
                    throw new AssertionError(warning);
                });
        AudioPresentationCommandQueue commands =
                new AudioPresentationCommandQueue(registry::isRendering);
        EventSink sink = new EventSink(
                sampleRate, (sampleRate + frameRate - 1) / frameRate);
        AudioPresentationProducer producer = new AudioPresentationProducer(
                sampleRate, frameRate, 32, 2, registry, commands,
                new AudioPresentationMixer(
                        (sampleRate + frameRate - 1) / frameRate,
                        registry::onVoiceFailure),
                sink);
        producer.setHistoryArmed(true);
        return new Fixture(
                registry, commands, producer, sink, resolver, pcm);
    }

    private static SmpsSfxInstantiation noSmps() {
        return new SmpsSfxInstantiation() {
            @Override
            public com.openggf.audio.smps.SmpsSequencer instantiateCached(
                    ResolvedSmpsSfxSource source,
                    com.openggf.audio.driver.SmpsDriver currentOwner) {
                throw new AssertionError("no SMPS SFX expected");
            }

        };
    }

    private static DecodedPcm rampStereo(int frames, int sampleRate) {
        short[] samples = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            samples[frame * 2] = (short) frame;
            samples[frame * 2 + 1] = (short) (frame + 100);
        }
        return new DecodedPcm(
                "rewind-music", 2, sampleRate, samples);
    }

    private record Fixture(
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationProducer producer,
            EventSink sink,
            AudioPresentationDependencyResolver resolver,
            DecodedPcm pcm) {
        private void startMusic() {
            SampleBackedVoice music =
                    SampleBackedVoice.loopingMusic(
                            1, pcm, pcm.sampleRate(), 1.0f);
            commands.submit(new ReplaceMusic(MusicVoiceEntry.fromVoice(
                            7, AudioSourceDescriptor.baseMusic(7), music)),
                    () -> true, registry::apply);
        }

        private long musicPosition() {
            return ((PresentationVoiceSnapshot.Sample)
                    registry.orderedVoiceAt(0).snapshot()).sourcePositionQ32();
        }
    }

    private static final class EventSink implements AudioPresentationSink {
        private final List<String> events = new ArrayList<>();
        private final int sampleRate;
        private final short[] packet;
        private int copiedFrames;
        private boolean expectCrossfade;

        private EventSink(int sampleRate, int maxStereoFrames) {
            this.sampleRate = sampleRate;
            packet = new short[maxStereoFrames * 2];
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public void accept(AudioPresentationFrameView frame) {
            if (frame.mode() == PresentationMode.REVERSE) {
                events.add("reverse");
            } else if (expectCrossfade) {
                events.add("crossfade");
                expectCrossfade = false;
            } else {
                events.add("forward");
            }
            Arrays.fill(packet, (short) 0);
            copiedFrames = frame.stereoFrames();
            frame.copyTo(packet, 0);
        }

        @Override
        public void onReverseBoundary() {
            events.add("reverse-boundary");
        }

        @Override
        public void close() {
        }

        private short[] lastPacket() {
            return Arrays.copyOf(packet, copiedFrames * 2);
        }
    }

}
