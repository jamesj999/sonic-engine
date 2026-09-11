package com.openggf.audio;

import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend installation after the split presentation runtime was removed.
 *
 * <p>There is no runtime to install any more: an {@link AudioBackend} supplies
 * SMPS sources, profile routing and the speaker sink, and the presentation
 * producer owns cadence, final PCM, history and every capture lease.
 *
 * <p>Two different "device swap" paths exist and they are deliberately not
 * symmetric, so each has its own test here:
 * <ul>
 *   <li>{@code setBackend} is a full re-initialization. It closes the producer,
 *       which tears the voice registry down — sounding voices do <em>not</em>
 *       survive it. What survives is the manager-owned logical ledger that
 *       decides which voices get played: donor bindings, ring alternation and
 *       the command timeline. Both halves are asserted rather than merely
 *       described, so a future change that starts preserving (or starts
 *       dropping) either half fails here.</li>
 *   <li>A speaker device failure mid-session replaces only the sink beneath the
 *       live producer, so sounding voices must survive it untouched. That is
 *       the path that would silence live music if it regressed, and it is
 *       pinned by {@link
 *       #speakerDeviceFailureReplacesOnlyTheSinkAndPreservesLiveVoices()}.</li>
 * </ul>
 */
class TestAudioManagerRuntimeInstallation {

    private static final int MUSIC_ID = 0x81;

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
    }

    @Test
    void backendReplacementChangesOnlyTheSinkAndPreservesTheManagerLedger()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new FixedRateNullBackend(48_000));
        audio.setAudioProfile(musicProfile());
        audio.setRom(new Rom());
        audio.registerDonorSound(GameSound.RING, "s3k", 0x2B);
        audio.playMusic(MUSIC_ID);
        audio.playSfx(GameSound.RING);
        audio.presentFrame(PresentationMode.SILENT);

        AudioPresentationSink sinkBefore = presentationSink(audio);
        assertEquals(48_000, sinkBefore.sampleRate());
        var logicalBefore = audio.captureLogicalSnapshot();
        assertFalse(logicalBefore.presentation().smpsLogical()
                        .sequencers().isEmpty(),
                "precondition: sounding SMPS state exists before the swap");
        assertNotNull(logicalBefore.presentation().activeMusic(),
                "precondition: music is playing before the swap");

        audio.setBackend(new FixedRateNullBackend(44_100));

        AudioPresentationSink sinkAfter = presentationSink(audio);
        assertNotSame(sinkBefore, sinkAfter,
                "installing a backend installs that backend's sink");
        assertEquals(44_100, sinkAfter.sampleRate(),
                "the producer is re-realized at the new sink's rate");
        assertEquals(44_100, audio.outputSampleRate());

        var logicalAfter = audio.captureLogicalSnapshot();
        assertEquals(logicalBefore.ringLeft(), logicalAfter.ringLeft(),
                "ring alternation is manager-owned logical state");
        assertEquals(logicalBefore.donorBindings(),
                logicalAfter.donorBindings(),
                "donor SFX bindings survive a backend swap");
        assertEquals(logicalBefore.commandTimelineFrame(),
                logicalAfter.commandTimelineFrame(),
                "the logical command timeline survives a backend swap");
        assertEquals(logicalBefore.commandTimelineNextOrder(),
                logicalAfter.commandTimelineNextOrder());

        // The other half of the contract: installing a backend closes the
        // producer, so the presentation voice registry is rebuilt empty at the
        // new rate rather than carried over. Pinned explicitly so that a change
        // which starts carrying voices across a producer rate change (where
        // their resampling state would no longer match the sink) is caught.
        assertEquals(List.of(), logicalAfter.presentation().voices(),
                "closing the producer rebuilds the voice registry empty");
        assertNull(logicalAfter.presentation().activeMusic(),
                "no voice survives the producer that owned it");

        assertBackendExposesNoPresentationSurface();
    }

    /**
     * Losing the speaker device mid-session must swap the sink underneath the
     * live producer and nothing else — the voices, the active music descriptor
     * and the manager ledger all keep playing into the replacement sink.
     */
    @Test
    void speakerDeviceFailureReplacesOnlyTheSinkAndPreservesLiveVoices()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new FixedRateNullBackend(48_000));
        audio.setAudioProfile(musicProfile());
        audio.setRom(new Rom());
        audio.registerDonorSound(GameSound.RING, "s3k", 0x2B);
        audio.playMusic(MUSIC_ID);
        audio.presentFrame(PresentationMode.SILENT);

        AudioPresentationSink sinkBefore = presentationSink(audio);
        var before = audio.captureLogicalSnapshot();
        var producerBefore =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertFalse(before.presentation().smpsLogical()
                        .sequencers().isEmpty(),
                "precondition: sounding SMPS state exists before the failure");
        assertNotNull(producerBefore.smpsSessionIdentity(),
                "the producer fingerprints its persistent SMPS session");

        audio.replaceFailedPresentationSink(
                new IllegalStateException("speaker device removed"));

        AudioPresentationSink sinkAfter = presentationSink(audio);
        assertNotSame(sinkBefore, sinkAfter,
                "a failed speaker is replaced by a fresh sink");
        assertInstanceOf(NoDeviceAudioSink.class, sinkAfter,
                "the replacement is the silent no-device sink");
        assertEquals(48_000, sinkAfter.sampleRate(),
                "the replacement sink keeps the producer's rate");

        var after = audio.captureLogicalSnapshot();
        var producerAfter =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        // Identity fingerprints, so this fails if the swap rebuilt, dropped or
        // reordered even one voice object rather than merely re-pointing the
        // sink beneath them.
        assertEquals(producerBefore.voiceIdentities(),
                producerAfter.voiceIdentities(),
                "a sink swap must not rebuild, drop or reorder a live voice");
        assertEquals(producerBefore, producerAfter,
                "a sink swap must not disturb the producer's clock, history, "
                        + "capture leases or reverse state");
        assertEquals(before.presentation().activeMusic(),
                after.presentation().activeMusic(),
                "the active music descriptor survives a speaker swap");
        assertEquals(before.presentation().nextVoiceId(),
                after.presentation().nextVoiceId());
        assertEquals(before.donorBindings(), after.donorBindings());
        assertEquals(before.commandTimelineFrame(),
                after.commandTimelineFrame());

        // The producer keeps presenting into the replacement sink.
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(audio.captureLogicalSnapshot().presentation()
                .activeMusic(),
                "music keeps playing across a speaker device swap");
    }

    @Test
    void baseGenerationChangesReplaceOneSessionWhileDonorChangesPreserveIt()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new FixedRateNullBackend(48_000));
        audio.setAudioProfile(musicProfile());
        audio.setRom(new Rom());
        audio.presentFrame(PresentationMode.SILENT);
        SmpsDriverSession first = shadowSession(audio);

        audio.setAudioProfile(musicProfile());

        SmpsDriverSession afterProfile = shadowSession(audio);
        assertNotSame(first, afterProfile,
                "a base profile generation change replaces the session");
        assertThrows(IllegalStateException.class, first::captureSnapshot,
                "the previous base session must be closed");
        audio.presentFrame(PresentationMode.SILENT);
        assertSame(afterProfile, shadowSession(audio),
                "the profile change installs exactly one replacement");

        audio.setRom(new Rom());

        SmpsDriverSession afterRom = shadowSession(audio);
        assertNotSame(afterProfile, afterRom,
                "a base ROM generation change replaces the session");
        assertThrows(IllegalStateException.class,
                afterProfile::captureSnapshot,
                "the prior-generation session must be closed");
        audio.presentFrame(PresentationMode.SILENT);
        assertSame(afterRom, shadowSession(audio),
                "the ROM change installs exactly one replacement");

        audio.registerDonorLoader("donor",
                new AudioTestFixtures.StubSmpsLoader(),
                AudioTestFixtures.EMPTY_DAC);

        assertSame(afterRom, shadowSession(audio),
                "donor catalog changes retain the base-owned session");
    }

    @Test
    void resetStateReinstallsBackendSinkWithoutReplacingTheBackend()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        CountingSinkBackend backend = new CountingSinkBackend();
        audio.resetState();
        audio.setBackend(backend);
        audio.presentFrame(PresentationMode.FORWARD);

        AudioPresentationSink first = presentationSink(audio);
        assertEquals(1, backend.sinkCreates);

        audio.resetState();
        assertEquals(1, backend.closedSinks,
                "reset must close the old presentation sink");
        assertNull(presentationSink(audio),
                "reset must leave the sink absent until the backend owns a new one");

        audio.ensurePresentationSink();
        AudioPresentationSink second = presentationSink(audio);
        assertNotSame(first, second);
        assertEquals(2, backend.sinkCreates,
                "reset/re-entry installs exactly one replacement sink");
        audio.ensurePresentationSink();
        assertEquals(2, backend.sinkCreates,
                "repeated ensure does not leak or duplicate the sink");

        audio.playSfx("UI_ERROR");
        audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(backend.acceptedFrames > 0,
                "the rebuilt sink must receive the next presentation frame");
    }

    @Test
    void failedDeviceInitializationInstallsNoDeviceSink() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();

        audio.setBackend(new FailingInitBackend());

        assertInstanceOf(NullAudioBackend.class, audio.getBackend(),
                "a backend that cannot initialize is replaced by the null one");
        assertInstanceOf(NoDeviceAudioSink.class, presentationSink(audio),
                "a failed device install must leave a silent no-device sink");

        // The producer is still the sole presentation owner and still presents
        // one clocked packet per outer frame.
        LiveCaptureAudioHandle capture = audio.beginLiveCaptureAudio(60);
        audio.presentFrame(PresentationMode.FORWARD);
        short[] packet = new short[capture.maxStereoFramesPerPacket() * 2];
        assertEquals(capture.sampleRate() / 60,
                capture.drainPresentationFrame(packet));
        capture.close();
    }

    @Test
    void stoppingCaptureDuringRewindLeavesProducerReverseOwnershipUntouched() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new FixedRateNullBackend(48_000));

        LiveCaptureAudioHandle capture = audio.beginLiveCaptureAudio(60);
        audio.beginReverseAudioPresentation();
        capture.close();

        assertTrue(
                AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .reverseActive(),
                "detaching capture must not end held reverse presentation");

        audio.endReverseAudioPresentation();

        assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());
    }

    /**
     * The backend interface must expose no presentation ownership at all —
     * neither a runtime attachment point nor reverse/history control.
     */
    private static void assertBackendExposesNoPresentationSurface() {
        java.util.Set<String> forbidden = java.util.Set.of(
                "attachDeterministicAudioRuntime",
                "supportsDeterministicRuntimePresentation",
                "supportsLiveCapturePresentation",
                "beginReversePresentation",
                "endReversePresentation",
                "setReversePlaybackRate",
                "setForwardPlaybackRate",
                "setRewindHistoryArmed",
                "captureLogicalSnapshot",
                "restoreLogicalSnapshot",
                "prepareLogicalRestore",
                "commitLogicalRestore",
                "discardLogicalRestore",
                "rollbackLogicalRestore",
                "playPcmSample",
                "stopPcmSample");
        for (var method : AudioBackend.class.getMethods()) {
            assertFalse(forbidden.contains(method.getName()),
                    "AudioBackend still exposes " + method.getName());
        }
    }

    /**
     * A profile whose SMPS loader resolves {@link #MUSIC_ID}, so the
     * presentation registry actually holds a sounding music voice and the
     * voice-preservation claims below are testing something.
     */
    private static AudioTestFixtures.StubAudioProfile musicProfile() {
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        AudioTestFixtures.StubSmpsData music =
                new AudioTestFixtures.StubSmpsData("backend-swap-music");
        music.setId(MUSIC_ID);
        loader.musicResults.put(MUSIC_ID, music);
        return new AudioTestFixtures.StubAudioProfile(loader) {
            @Override
            public com.openggf.audio.smps.SmpsSequencerConfig
                    getSequencerConfig() {
                return new com.openggf.audio.smps.SmpsSequencerConfig.Builder()
                        .build();
            }
        };
    }

    private static AudioPresentationSink presentationSink(AudioManager audio)
            throws Exception {
        Field field = AudioManager.class.getDeclaredField("presentationSink");
        field.setAccessible(true);
        return (AudioPresentationSink) field.get(audio);
    }

    private static SmpsDriverSession shadowSession(AudioManager audio)
            throws Exception {
        Field field = AudioManager.class.getDeclaredField(
                "shadowSmpsSession");
        field.setAccessible(true);
        return (SmpsDriverSession) field.get(audio);
    }

    private static class FixedRateNullBackend extends NullAudioBackend {
        private final int outputSampleRate;

        private FixedRateNullBackend(int outputSampleRate) {
            this.outputSampleRate = outputSampleRate;
        }

        @Override
        public int outputSampleRate() {
            return outputSampleRate;
        }
    }

    private static final class CountingSinkBackend extends NullAudioBackend {
        private int sinkCreates;
        private int closedSinks;
        private int acceptedFrames;

        @Override
        public AudioPresentationSink createPresentationSink(
                java.util.function.Consumer<Throwable> failureHandler,
                java.util.function.Consumer<String> warningHandler) {
            sinkCreates++;
            return new AudioPresentationSink() {
                @Override
                public int sampleRate() {
                    return 48_000;
                }

                @Override
                public void accept(com.openggf.audio.presentation.AudioPresentationFrameView frame) {
                    acceptedFrames++;
                }

                @Override
                public void onReverseBoundary() {
                }

                @Override
                public void close() {
                    closedSinks++;
                }
            };
        }
    }

    private static final class FailingInitBackend extends NullAudioBackend {
        @Override
        public void init() {
            throw new IllegalStateException("no audio device available");
        }
    }
}
