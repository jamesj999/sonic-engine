package com.openggf.audio;

import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.runtime.PcmHistoryRing;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Continuous PCM rewind-history recording must stay disarmed until a rewind
 * consumer (held-key live rewind, Trace Test Mode) actually arms it — otherwise
 * every presented packet pays a copy into a ring that nothing can ever read
 * back, and stale samples can survive to leak across a later boundary.
 *
 * <p>History is owned solely by the presentation producer; the backend has no
 * ring, no reverse cursor and no arming hook at all.
 */
class TestRewindHistoryArming {

    private SonicConfigurationService config;
    private AudioManager audio;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        config.resetToDefaults();
    }

    @Test
    void audioManagerRoutesArmingOnlyToTheProducer() {
        audio.setRewindHistoryArmed(true);
        assertTrue(audio.releaseStateForTesting().producer().historyArmed());

        audio.setRewindHistoryArmed(false);
        assertFalse(audio.releaseStateForTesting().producer().historyArmed());

        for (var method : AudioBackend.class.getMethods()) {
            assertFalse("setRewindHistoryArmed".equals(method.getName()),
                    "the backend must expose no rewind-history arming hook");
        }
    }

    @Test
    void armingRecordsPresentedPcmAndDisarmingStopsRecording() {
        audio.submitShadowRawPcmForTesting(rampPcm(8_000), 48_000);
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(0, storedFrames(),
                "an unarmed producer must not pay a history copy");

        audio.setRewindHistoryArmed(true);
        audio.presentFrame(PresentationMode.FORWARD);
        int armed = storedFrames();
        assertTrue(armed > 0, "arming must enable PCM history recording");

        audio.setRewindHistoryArmed(false);
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(armed, storedFrames(),
                "disarming must stop recording immediately");
    }

    @Test
    void clearingHistoryIsAHardBoundaryThatDropsStoredPcm() {
        audio.setRewindHistoryArmed(true);
        audio.submitShadowRawPcmForTesting(rampPcm(8_000), 48_000);
        audio.presentFrame(PresentationMode.FORWARD);
        long epochBefore = audio.releaseStateForTesting().producer()
                .history().epoch();
        assertTrue(storedFrames() > 0);

        audio.clearPcmHistory();

        assertEquals(0, storedFrames(),
                "a hard boundary must drop every pre-boundary sample");
        assertTrue(audio.releaseStateForTesting().producer().history().epoch()
                        > epochBefore,
                "clearing history must advance the history epoch");
    }

    @Test
    void repeatedArmingDuringHeldRewindPreservesTheReverseCursor() {
        audio.setRewindHistoryArmed(true);
        audio.submitShadowRawPcmForTesting(rampPcm(8_000), 48_000);
        audio.presentFrame(PresentationMode.FORWARD);
        LiveCaptureAudioHandle capture =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 60);

        audio.beginReverseAudioPresentation();
        audio.setRewindHistoryArmed(true);
        audio.setRewindHistoryArmed(true);
        audio.presentFrame(PresentationMode.REVERSE);

        short[] packet = new short[capture.maxStereoFramesPerPacket() * 2];
        int frames = capture.drainPresentationFrame(packet);
        assertEquals(800, frames);
        assertFalse(allZero(packet),
                "idempotent arming must preserve the active reverse cursor");
        assertEquals(packet[0], packet[1],
                "reverse playback must remain stereo after repeated arming");

        capture.close();
        audio.endReverseAudioPresentation();
    }

    @Test
    void timeLimitedHistoryCoversConfiguredSecondsAtTheOutputRate() {
        config.setConfigValue(
                SonicConfiguration.REWIND_AUDIO_HISTORY_LIMIT_TYPE, "time");
        config.setConfigValue(
                SonicConfiguration.REWIND_AUDIO_HISTORY_SECONDS, 2);

        AudioPresentationProducer.TransactionFingerprint producer =
                realizedProducer();

        assertEquals(producer.clock().sampleRate() * 2,
                producer.history().capacityFrames(),
                "time-limited history must cover configured seconds at the"
                        + " emitted sample rate");
    }

    @Test
    void sizeLimitedHistoryKeepsConfiguredByteCapacity() {
        config.setConfigValue(
                SonicConfiguration.REWIND_AUDIO_HISTORY_LIMIT_TYPE, "size");
        config.setConfigValue(
                SonicConfiguration.REWIND_AUDIO_HISTORY_SIZE_MB, 1);

        assertEquals((1024 * 1024) / (2 * Short.BYTES),
                realizedProducer().history().capacityFrames(),
                "size-limited history must retain its configured stereo PCM"
                        + " byte capacity");
    }

    /**
     * Rebuilds the shadow presentation so the ring the producer actually
     * allocates reflects the configuration set by the calling test, then
     * returns that live producer's fingerprint. Asserting against the
     * allocated ring — not {@link PcmHistoryRing#capacityFramesFor} — is what
     * keeps the config to allocation path covered end to end.
     */
    private AudioPresentationProducer.TransactionFingerprint
            realizedProducer() {
        audio.setBackend(new NullAudioBackend());
        return audio.releaseStateForTesting().producer();
    }

    private int storedFrames() {
        return audio.releaseStateForTesting().producer().history()
                .storedFrames();
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    private static boolean allZero(short[] samples) {
        for (short sample : samples) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }
}
