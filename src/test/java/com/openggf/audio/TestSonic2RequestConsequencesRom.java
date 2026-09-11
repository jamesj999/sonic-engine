package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.presentation.AudioPresentationFrameView;
import com.openggf.audio.presentation.AudioPresentationForwardService;
import com.openggf.audio.presentation.AudioPresentationCommandResolver.AppliedOutcome;
import com.openggf.audio.presentation.AudioPresentationCommandResolver.OutcomeReservation;
import com.openggf.audio.presentation.AudioRequestService;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestService;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2RequestConsequencesRom {
    private Rom rom;

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void oneUpStopsSfxBeforeSaveAndRetainsShippedPriorityBug() {
        List<Sonic2SoundRequestService.Event> events = new ArrayList<>();
        AudioManager audio = install(events::add);

        assertTrue(audio.playSfx(0xBF));
        audio.presentFrame(PresentationMode.FORWARD);
        Sonic2SoundRequestService.Snapshot beforeOneUp = requestSnapshot(audio);
        assertEquals(0x7F, beforeOneUp.pipeline().sfxPriorityValue());

        audio.playMusic(Sonic2Music.EXTRA_LIFE.id);
        audio.presentFrame(PresentationMode.FORWARD);

        Sonic2SoundRequestService.Snapshot afterOneUp = requestSnapshot(audio);
        assertTrue(afterOneUp.pipeline().oneUpPlaying(), events.toString());
        assertEquals(0, afterOneUp.pipeline().sfxPriorityValue());
        assertEquals(0x7F,
                afterOneUp.pipeline().savedOneUpSfxPriorityValue(),
                "shipped FixDriverBugs=0 saves the old priority for restore");
        assertFalse(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                        .stream().anyMatch(entry -> entry.snapshot().sfx()),
                "pre-one-up SFX must not survive in the saved driver region");
    }

    @Test
    void diagnosticFailureCannotRetryOrDuplicateACommittedRequest() {
        AtomicInteger callbacks = new AtomicInteger();
        AudioManager audio = install(event -> {
            callbacks.incrementAndGet();
            throw new IllegalStateException("seeded observer failure");
        });

        assertTrue(audio.playSfx(0xA0));
        audio.presentFrame(PresentationMode.FORWARD);
        int committedCallbacks = callbacks.get();
        assertTrue(committedCallbacks > 0);
        assertEquals(1, audio.commandTimeline().entryCount());

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(committedCallbacks, callbacks.get());
        assertEquals(1, audio.commandTimeline().entryCount());
    }

    @Test
    void nullAndThrowingRomResolutionLeaveFullLiveStateExactAndRetryOnce() {
        for (FailureKind failure : List.of(
                FailureKind.NULL_SFX, FailureKind.THROW_SFX,
                FailureKind.NULL_MUSIC, FailureKind.THROW_MUSIC)) {
            List<Sonic2SoundRequestService.Event> events = new ArrayList<>();
            RejectingSonic2AudioProfile profile =
                    new RejectingSonic2AudioProfile(events::add);
            CountingSink sink = new CountingSink();
            AudioManager audio = install(profile, new SinkBackend(sink));
            assertTrue(audio.playSfx(0xB5));
            audio.presentFrame(PresentationMode.FORWARD);
            LiveCaptureAudioHandle firstCapture =
                    audio.attachShadowCaptureForTesting(60);
            LiveCaptureAudioHandle secondCapture =
                    audio.attachShadowCaptureForTesting(60);

            profile.failure = failure;
            if (failure.music()) {
                audio.playMusic(Sonic2Music.EMERALD_HILL.id);
            } else {
                assertTrue(audio.playSfx(0xBF));
            }
            AudioLogicalSnapshot logicalBefore =
                    audio.captureLogicalSnapshot();
            SmpsDriverSnapshot driverBefore =
                    audio.shadowSmpsDriverSnapshotForTesting();
            var parityBefore = audio.shadowParitySnapshot();
            int timelineBefore = audio.commandTimeline().entryCount();
            int diagnosticsBefore = events.size();
            int sinkBefore = sink.deliveries;
            Sonic2SoundRequestService.Snapshot requestBefore =
                    requestSnapshot(audio);

            audio.presentFrame(PresentationMode.FORWARD);

            assertDeepEquals(logicalBefore,
                    audio.captureLogicalSnapshot(),
                    failure + " logical state");
            assertDeepEquals(driverBefore,
                    audio.shadowSmpsDriverSnapshotForTesting(),
                    failure + " full driver/session state");
            assertEquals(parityBefore, audio.shadowParitySnapshot(),
                    failure + " parity");
            assertEquals(timelineBefore,
                    audio.commandTimeline().entryCount(),
                    failure + " timeline");
            assertEquals(diagnosticsBefore, events.size(),
                    failure + " diagnostics");
            assertEquals(sinkBefore, sink.deliveries,
                    failure + " speaker delivery");
            assertCaptureEmpty(firstCapture, failure + " first capture");
            assertCaptureEmpty(secondCapture, failure + " second capture");
            assertEquals(requestBefore, requestSnapshot(audio),
                    failure + " request mailbox/queue/priority");

            profile.failure = FailureKind.NONE;
            audio.presentFrame(PresentationMode.FORWARD);
            assertEquals(timelineBefore + 1,
                    audio.commandTimeline().entryCount(),
                    failure + " retries the same request exactly once");
            assertTrue(events.size() > diagnosticsBefore,
                    failure + " publishes diagnostics only after commit");
            assertEquals(sinkBefore + 1, sink.deliveries,
                    failure + " successful retry reaches the sink once");
            assertCaptureOnce(firstCapture,
                    failure + " first capture retry");
            assertCaptureOnce(secondCapture,
                    failure + " second capture retry");

            audio.resetState();
            rom.close();
            rom = null;
        }
    }

    @Test
    void diagnosticResolutionFailureEscapesAndRetryKeepsExactRequest() {
        RejectingSonic2AudioProfile profile =
                new RejectingSonic2AudioProfile(ignored -> { });
        AudioManager audio = install(profile);
        profile.failure = FailureKind.DIAGNOSTIC_MUSIC;
        audio.playMusic(Sonic2Music.EMERALD_HILL.id);
        AudioLogicalSnapshot before = audio.captureLogicalSnapshot();
        int timelineBefore = audio.commandTimeline().entryCount();

        AudioDiagnosticObserverException diagnostic = assertThrows(
                AudioDiagnosticObserverException.class,
                () -> audio.presentFrame(PresentationMode.FORWARD));
        assertEquals("seeded diagnostic rejection",
                diagnostic.getCause().getMessage());
        assertDeepEquals(before, audio.captureLogicalSnapshot(),
                "diagnostic rejection rollback");

        profile.failure = FailureKind.NONE;
        assertDoesNotThrow(() -> audio.presentFrame(PresentationMode.FORWARD));
        assertEquals(timelineBefore + 1,
                audio.commandTimeline().entryCount());
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(timelineBefore + 1,
                audio.commandTimeline().entryCount(),
                "retry must publish the request once");
    }

    @Test
    void reserveFailureRollsBackCandidateAndRetryKeepsExactRequest() {
        ReserveRejectingSonic2AudioProfile profile =
                new ReserveRejectingSonic2AudioProfile();
        AudioManager audio = install(profile);
        assertTrue(audio.playSfx(0xF9));
        AudioLogicalSnapshot before = audio.captureLogicalSnapshot();
        int timelineBefore = audio.commandTimeline().entryCount();

        IllegalStateException reserveFailure = assertThrows(
                IllegalStateException.class,
                () -> audio.presentFrame(PresentationMode.FORWARD));
        assertEquals("seeded reserve rejection", reserveFailure.getMessage());
        assertDeepEquals(before, audio.captureLogicalSnapshot(),
                "reserve rejection rollback");

        assertDoesNotThrow(() -> audio.presentFrame(PresentationMode.FORWARD));
        assertEquals(timelineBefore + 1,
                audio.commandTimeline().entryCount());
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(timelineBefore + 1,
                audio.commandTimeline().entryCount(),
                "retry must publish the request once");
    }

    private AudioManager install(
            java.util.function.Consumer<Sonic2SoundRequestService.Event>
                    observer) {
        return install(new Sonic2AudioProfile(observer));
    }

    private AudioManager install(Sonic2AudioProfile profile) {
        return install(profile, new NullAudioBackend());
    }

    private AudioManager install(
            Sonic2AudioProfile profile, NullAudioBackend backend) {
        File file = RomTestUtils.ensureSonic2RomAvailable();
        rom = new Rom();
        assertTrue(rom.open(file.getAbsolutePath()));
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(backend);
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        return audio;
    }

    private static void assertCaptureEmpty(
            LiveCaptureAudioHandle capture, String message) {
        assertEquals(0, capture.totalStereoFrames(), message);
        assertFalse(captureFresh(capture), message);
    }

    private static void assertCaptureOnce(
            LiveCaptureAudioHandle capture, String message) {
        short[] packet = new short[capture.maxStereoFramesPerPacket() * 2];
        assertTrue(captureFresh(capture), message);
        int frames = capture.drainPresentationFrame(packet);
        assertTrue(frames > 0, message);
        assertFalse(captureFresh(capture),
                message + " must consume exactly one published packet");
    }

    private static boolean captureFresh(LiveCaptureAudioHandle capture) {
        try {
            var field = capture.getClass().getDeclaredField("fresh");
            field.setAccessible(true);
            return field.getBoolean(capture);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class SinkBackend extends NullAudioBackend {
        private final CountingSink sink;

        private SinkBackend(CountingSink sink) {
            this.sink = sink;
        }

        @Override public int outputSampleRate() { return sink.sampleRate(); }

        @Override
        public AudioPresentationSink createPresentationSink(
                Consumer<Throwable> failureHandler,
                Consumer<String> warningHandler) {
            return sink;
        }
    }

    private static final class CountingSink implements AudioPresentationSink {
        private int deliveries;

        @Override public int sampleRate() { return 48_000; }

        @Override
        public void accept(AudioPresentationFrameView frame) {
            deliveries++;
        }

        @Override public void onReverseBoundary() { }
        @Override public void close() { }
    }

    private enum FailureKind {
        NONE, NULL_MUSIC, THROW_MUSIC, NULL_SFX, THROW_SFX,
        DIAGNOSTIC_MUSIC;

        boolean music() {
            return this == NULL_MUSIC || this == THROW_MUSIC;
        }
    }

    private static final class RejectingSonic2AudioProfile
            extends Sonic2AudioProfile {
        private FailureKind failure = FailureKind.NONE;

        private RejectingSonic2AudioProfile(
                java.util.function.Consumer<
                        Sonic2SoundRequestService.Event> observer) {
            super(observer);
        }

        @Override
        public SmpsLoader createSmpsLoader(Rom rom) {
            SmpsLoader delegate = super.createSmpsLoader(rom);
            return new SmpsLoader() {
                @Override
                public AbstractSmpsData loadMusic(int musicId) {
                    return switch (failure) {
                        case NULL_MUSIC -> null;
                        case THROW_MUSIC -> throw new IllegalStateException(
                                "seeded music loader rejection");
                        case DIAGNOSTIC_MUSIC ->
                                throw new AudioDiagnosticObserverException(
                                        new IllegalStateException(
                                                "seeded diagnostic rejection"));
                        default -> delegate.loadMusic(musicId);
                    };
                }

                @Override
                public AbstractSmpsData loadSfx(int sfxId) {
                    return switch (failure) {
                        case NULL_SFX -> null;
                        case THROW_SFX -> throw new IllegalStateException(
                                "seeded SFX loader rejection");
                        default -> delegate.loadSfx(sfxId);
                    };
                }

                @Override public AbstractSmpsData loadSfx(String name) {
                    return delegate.loadSfx(name);
                }

                @Override public DacData loadDacData() {
                    return delegate.loadDacData();
                }

                @Override public int findMusicOffset(int musicId) {
                    return delegate.findMusicOffset(musicId);
                }
            };
        }
    }

    private static final class ReserveRejectingSonic2AudioProfile
            extends Sonic2AudioProfile {
        private final ReserveRejectingRequestService requestService =
                new ReserveRejectingRequestService();

        @Override
        public AudioRequestService createAudioRequestService() {
            return requestService;
        }
    }

    private static final class ReserveRejectingRequestService
            implements AudioRequestService {
        private final Sonic2SoundRequestService delegate =
                new Sonic2SoundRequestService();
        private boolean rejectNextReserve = true;

        @Override public void submitMusic(int id, AudioCommand command) {
            delegate.submitMusic(id, command);
        }

        @Override public void submitSound(int id, AudioCommand command) {
            delegate.submitSound(id, command);
        }

        @Override public Snapshot snapshot() { return delegate.snapshot(); }

        @Override public void restore(
                AudioPresentationForwardService.Snapshot snapshot) {
            delegate.restore(snapshot);
        }

        @Override
        public ForwardBoundary beginForwardBoundary() {
            ForwardBoundary owned = delegate.beginForwardBoundary();
            return new ForwardBoundary() {
                @Override public void service(Consumer<AudioCommand> sink) {
                    owned.service(sink);
                }

                @Override public void reserveOutcome(
                        OutcomeReservation reservation) {
                    if (rejectNextReserve) {
                        rejectNextReserve = false;
                        throw new IllegalStateException(
                                "seeded reserve rejection");
                    }
                    owned.reserveOutcome(reservation);
                }

                @Override public void applyOutcome(AppliedOutcome outcome) {
                    owned.applyOutcome(outcome);
                }

                @Override public void prepareCommit() {
                    owned.prepareCommit();
                }

                @Override public CommittedReceipt commit() {
                    return owned.commit();
                }

                @Override public void publishDiagnostics(
                        CommittedReceipt receipt) {
                    owned.publishDiagnostics(receipt);
                }

                @Override public void rollback() { owned.rollback(); }
            };
        }
    }

    private static Sonic2SoundRequestService.Snapshot requestSnapshot(
            AudioManager audio) {
        return (Sonic2SoundRequestService.Snapshot) audio
                .captureLogicalSnapshot().forwardServiceSnapshot();
    }

    private static void assertDeepEquals(
            Object expected, Object actual, String path) {
        if (expected == actual) {
            return;
        }
        if (expected == null || actual == null
                || expected.getClass() != actual.getClass()) {
            assertEquals(expected, actual, path);
            return;
        }
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(length, Array.getLength(actual), path + ".length");
            for (int index = 0; index < length; index++) {
                assertDeepEquals(Array.get(expected, index),
                        Array.get(actual, index), path + "[" + index + "]");
            }
            return;
        }
        if (expected instanceof Iterable<?> left
                && actual instanceof Iterable<?> right) {
            var leftIterator = left.iterator();
            var rightIterator = right.iterator();
            int index = 0;
            while (leftIterator.hasNext() && rightIterator.hasNext()) {
                assertDeepEquals(leftIterator.next(), rightIterator.next(),
                        path + "[" + index++ + "]");
            }
            assertEquals(leftIterator.hasNext(), rightIterator.hasNext(),
                    path + ".size");
            return;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    assertDeepEquals(component.getAccessor().invoke(expected),
                            component.getAccessor().invoke(actual),
                            path + "." + component.getName());
                } catch (ReflectiveOperationException failure) {
                    fail("cannot inspect " + path, failure);
                }
            }
            return;
        }
        assertEquals(expected, actual, path);
    }
}
