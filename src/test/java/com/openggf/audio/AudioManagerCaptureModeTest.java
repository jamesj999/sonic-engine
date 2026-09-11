package com.openggf.audio;

import com.openggf.audio.AudioTestFixtures.RecordingAudioBackend;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.runtime.PcmHistoryRing;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.debug.PerformanceProfiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline capture compatibility API over the single authoritative
 * presentation producer. {@code beginCaptureMode} / {@code drainCaptureFrame} /
 * {@code endCaptureMode} attach, read, and detach exactly one non-consuming
 * producer lease; they never replace the producer/registry/sink and never open
 * an audio device.
 *
 * <p>"No second history owner" is asserted here against the producer's own
 * observable history state across each lease operation, not against the
 * backend's class shape: the structural claim that the retired backend
 * declares no history ring or reverse cursor at all belongs to {@code
 * TestAudioPresentationArchitectureGuard
 * .backendHasNoPresentationHandoffOrReverseCursor}, and a class-shape check
 * repeated here could not fail as a result of anything these tests do.
 */
class AudioManagerCaptureModeTest {

    @AfterEach
    void tearDown() {
        AudioManager audio = AudioManager.getInstance();
        audio.endCaptureMode();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void captureModeProducesPerFramePcmEvenWithNonPresentationBackend() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();                                 // isolate from other tests
        // beginCaptureMode now rejects a lease clocked at anything other than
        // the producer's rate, and that rate comes from the shared
        // configuration singleton, so pin it rather than inheriting whatever
        // an earlier class in this JVM fork left behind.
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new RecordingAudioBackend());      // null backend: no real presentation

        audio.beginCaptureMode(48000, 60);

        short[] target = new short[800 * 2];
        long total = 0;
        for (int i = 0; i < 60; i++) {
            audio.presentFrame(PresentationMode.FORWARD);
            int frames = audio.drainCaptureFrame(target);
            assertTrue(frames > 0, "each frame produces PCM in capture mode");
            total += frames;
        }
        assertEquals(48000, total, "one second of stereo frames at 48kHz/60fps");

        audio.endCaptureMode();
    }

    @Test
    void captureModeProducesOneSecondOfPcmAt44100Hz() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        audio.setBackend(new HeadlessSmpsAudioBackend(
                config, PerformanceProfiler.getInstance(), 44_100));

        audio.beginCaptureMode(44_100, 60);

        short[] target = new short[735 * 2];
        long total = 0;
        for (int i = 0; i < 60; i++) {
            audio.presentFrame(PresentationMode.FORWARD);
            total += audio.drainCaptureFrame(target);
        }
        assertEquals(44_100, total,
                "one second of stereo frames at 44.1kHz/60fps");

        audio.endCaptureMode();
    }

    @Test
    void beginCaptureAttachesWithoutReplacingProducerRegistryOrSink()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        // beginCaptureMode rejects a lease clocked at anything other than the
        // producer's rate, and that rate comes from the shared configuration
        // singleton, so pin it rather than inheriting whatever an earlier class
        // in this JVM fork left behind.
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(4_000), 48_000);
        audio.presentFrame(PresentationMode.FORWARD);

        AudioPresentationProducer.TransactionFingerprint before =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        AudioPresentationSink sinkBefore = presentationSink(audio);
        Object producerBefore = producer(audio);

        audio.beginCaptureMode(48_000, 60);

        assertSame(producerBefore, producer(audio),
                "offline capture must not replace the authoritative producer");
        assertSame(sinkBefore, presentationSink(audio),
                "offline capture must not replace the presentation sink");
        AudioPresentationProducer.TransactionFingerprint after =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before.voiceIdentities(), after.voiceIdentities(),
                "attaching the compatibility lease must not rebind voices");
        assertEquals(before.clock(), after.clock(),
                "attaching the compatibility lease must not move the clock");
        assertEquals(before.history(), after.history(),
                "attaching the compatibility lease must not move history");
        assertEquals(before.captureCount() + 1, after.captureCount(),
                "exactly one compatibility lease is attached");

        audio.endCaptureMode();
    }

    @Test
    void drainReturnsExactlyTheMostRecentUnifiedPacketOnce() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(4_000), 48_000);

        audio.beginCaptureMode(48_000, 60);
        LiveCaptureAudioHandle speaker =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 60);
        audio.presentFrame(PresentationMode.FORWARD);

        short[] offline = new short[800 * 2];
        short[] independent = new short[800 * 2];
        assertEquals(800, audio.drainCaptureFrame(offline));
        assertEquals(800, speaker.drainPresentationFrame(independent));
        assertFalse(allZero(offline),
                "the offline lease must see the rendered unified packet");
        assertArrayEquals(independent, offline,
                "offline and speaker views are copies of one producer packet");

        speaker.close();
        audio.endCaptureMode();
    }

    @Test
    void drainBeforeTheFirstPresentYieldsClockedSilence() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(4_000), 48_000);

        audio.beginCaptureMode(48_000, 60);

        short[] target = new short[800 * 2];
        java.util.Arrays.fill(target, (short) 0x4321);
        assertEquals(800, audio.drainCaptureFrame(target),
                "a drain issued before the first presentation is still one"
                        + " clocked frame");
        assertTrue(allZero(target),
                "nothing has been presented, so the packet is fresh silence");

        audio.endCaptureMode();
    }

    @Test
    void beginCaptureRejectsAFrameRateTheProducerIsNotClockedAt() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(8_000), 48_000);
        assertEquals(60, audio.presentationFrameRate(),
                "the default headless producer is clocked at 60 fps");

        AudioPresentationProducer.TransactionFingerprint before =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertThrows(IllegalArgumentException.class,
                () -> audio.beginCaptureMode(48_000, 30),
                "a slower capture clock than the producer is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> audio.beginCaptureMode(48_000, 50),
                "a faster capture clock than the producer is rejected");
        AudioPresentationProducer.TransactionFingerprint after =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before.captureCount(), after.captureCount(),
                "a rejected lease attaches nothing");
        assertEquals(before.clock(), after.clock());
        assertThrows(IllegalStateException.class,
                () -> audio.drainCaptureFrame(new short[4096]),
                "no lease is live after a rejected rate");

        // Why the guard exists: the producer presents one packet per outer
        // frame at ITS rate, so a mismatched capture clock silently corrupts
        // every packet. Attach the mismatched lease directly on the producer
        // to pin that corruption.
        LiveCaptureAudioHandle mismatched =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 30);
        audio.presentFrame(PresentationMode.FORWARD);
        short[] packet = new short[1_600 * 2];
        assertEquals(1_600, mismatched.drainPresentationFrame(packet),
                "a 30 fps lease asks for twice the producer's 60 fps packet");
        assertFalse(allZero(java.util.Arrays.copyOfRange(packet, 0, 800 * 2)),
                "the producer's real packet fills only the first half");
        assertTrue(allZero(java.util.Arrays.copyOfRange(
                        packet, 800 * 2, 1_600 * 2)),
                "the rest is zero-padding, i.e. half the recording is silence");
        mismatched.close();
    }

    @Test
    void secondDrainReturnsClockedSilenceRatherThanStalePcm() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(4_000), 48_000);

        audio.beginCaptureMode(48_000, 60);
        audio.presentFrame(PresentationMode.FORWARD);

        short[] first = new short[800 * 2];
        assertEquals(800, audio.drainCaptureFrame(first));
        assertFalse(allZero(first));

        short[] second = new short[800 * 2];
        java.util.Arrays.fill(second, (short) 0x4321);
        assertEquals(800, audio.drainCaptureFrame(second),
                "a second drain still yields one clocked frame");
        assertTrue(allZero(second),
                "a second drain yields fresh silence, never stale PCM");

        audio.endCaptureMode();
    }

    @Test
    void endCaptureDetachesOnlyCompatibilityHandle() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());

        LiveCaptureAudioHandle live = audio.beginLiveCaptureAudio(60);
        AudioPresentationProducer.TransactionFingerprint withLiveOnly =
                AudioManagerTestDiagnostics.producerFingerprint(audio);

        audio.beginCaptureMode(48_000, 60);
        assertEquals(withLiveOnly.captureCount() + 1,
                AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .captureCount());

        audio.endCaptureMode();
        audio.endCaptureMode();     // idempotent

        AudioPresentationProducer.TransactionFingerprint after =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(withLiveOnly.captureCount(), after.captureCount(),
                "only the compatibility lease is detached");
        assertEquals(withLiveOnly.voiceIdentities(), after.voiceIdentities());

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(800, live.drainPresentationFrame(new short[800 * 2]),
                "the live lease survives offline capture teardown");
        assertThrows(IllegalStateException.class,
                () -> audio.drainCaptureFrame(new short[800 * 2]),
                "draining after endCaptureMode is rejected");
        live.close();
    }

    /**
     * A capture lease can only be released on the producer's owner thread. A
     * refused release must leave the manager's view and the producer's lease
     * list in agreement: dropping the handle reference anyway would orphan a
     * lease that keeps receiving every presented packet, and would let the next
     * {@code beginCaptureMode} attach a <em>second</em> lease instead of
     * rejecting it.
     */
    @Test
    void endCaptureKeepsTheLeaseWhenTheProducerRefusesTheDetach()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new RecordingAudioBackend());

        int leasesBefore = AudioManagerTestDiagnostics
                .producerFingerprint(audio).captureCount();
        audio.beginCaptureMode(48_000, 60);
        assertEquals(leasesBefore + 1, AudioManagerTestDiagnostics
                .producerFingerprint(audio).captureCount());

        Throwable[] offThreadFailure = new Throwable[1];
        Thread offOwnerThread = new Thread(() -> {
            try {
                audio.endCaptureMode();
            } catch (Throwable failure) {
                offThreadFailure[0] = failure;
            }
        }, "not-the-producer-owner");
        offOwnerThread.start();
        offOwnerThread.join();

        assertInstanceOf(IllegalStateException.class, offThreadFailure[0],
                "the producer refuses an off-owner-thread detach");
        assertEquals(leasesBefore + 1, AudioManagerTestDiagnostics
                        .producerFingerprint(audio).captureCount(),
                "the refused detach left the lease attached to the producer");
        assertThrows(IllegalStateException.class,
                () -> audio.beginCaptureMode(48_000, 60),
                "the manager must still know it holds that lease, so a second"
                        + " lease is rejected rather than silently attached");
        assertEquals(800, audio.drainCaptureFrame(new short[800 * 2]),
                "the still-attached lease is still the manager's lease");

        audio.endCaptureMode();

        assertEquals(leasesBefore, AudioManagerTestDiagnostics
                        .producerFingerprint(audio).captureCount(),
                "a retry on the owner thread releases it");
        assertThrows(IllegalStateException.class,
                () -> audio.drainCaptureFrame(new short[800 * 2]));
    }

    @Test
    void rejectsSecondLeaseAndRateChangeAfterVoicesHaveBegun() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new RecordingAudioBackend());
        audio.submitShadowRawPcmForTesting(rampPcm(4_000), 48_000);
        audio.presentFrame(PresentationMode.FORWARD);

        audio.beginCaptureMode(48_000, 60);
        assertThrows(IllegalStateException.class,
                () -> audio.beginCaptureMode(48_000, 60),
                "a second compatibility lease is rejected");
        audio.endCaptureMode();

        AudioPresentationProducer.TransactionFingerprint before =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertThrows(IllegalArgumentException.class,
                () -> audio.beginCaptureMode(44_100, 60),
                "a rate change after voices have begun is rejected");
        AudioPresentationProducer.TransactionFingerprint after =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before.clock(), after.clock(),
                "a rejected rate change must not migrate cursors");
        assertEquals(before.voiceIdentities(), after.voiceIdentities());
        assertEquals(before.captureCount(), after.captureCount());
    }

    @Test
    void offlinePacketContainsSmpsWavAndRawPcmTogether() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        audio.setBackend(new HeadlessSmpsAudioBackend(
                config, PerformanceProfiler.getInstance()));

        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(0x81, smpsData("music", 0x81));
        loader.musicResults.put(0x82, smpsData("music2", 0x82));
        Rom rom = mock(Rom.class);
        byte[] segaPcm = rampPcm(2_000);
        when(rom.readBytes(0x10, segaPcm.length)).thenReturn(segaPcm);
        audio.setAudioProfile(new CaptureModeProfile(
                loader, new SegaPcmSpec(0x10, segaPcm.length, 8_000),
                new SmpsSequencerConfig.Builder().build()));
        audio.setRom(rom);
        // Pre-decode the fallback WAV asset so the sample voice resolves
        // without a classpath asset, exactly as a decoded ROM-era asset would.
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/jump.wav", rampPcm(1_000), 48_000);

        audio.beginCaptureMode(48_000, 60);

        // 1. SMPS alone must be audible offline. Admission is production work:
        //    the offline presentation drains the queued command and registers
        //    the composite voice, and only then is there a voice to prime. A
        //    SILENT presentation is the production drain that does not also
        //    render — the stub asset carries no sequencer data, so a FORWARD
        //    render would sweep the voice as complete before it can be primed.
        audio.playMusic(0x81);
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the offline presentation must admit the SMPS music voice");
        audio.presentFrame(PresentationMode.FORWARD);
        short[] smpsOnly = new short[800 * 2];
        assertEquals(800, audio.drainCaptureFrame(smpsOnly));
        assertFalse(allZero(smpsOnly),
                "SMPS synthesis must reach the offline packet");

        // 2. SMPS, fallback WAV SFX, and raw PCM share one registry and one
        //    final packet. Again the offline presentation is what admits every
        //    one of them; the test only primes the stub driver's synthesis.
        audio.playMusic(0x82);
        audio.playSfx("JUMP");
        audio.playSegaPcm();
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the offline presentation must admit the SMPS music voice");

        AudioPresentationSnapshot presentation =
                audio.captureLogicalSnapshot().presentation();
        assertNotNull(presentation.activeMusic(),
                "SMPS music voice must own the music slot at render time");
        assertNotNull(presentation.rawPcmVoiceId(),
                "raw PCM voice must be admitted offline");
        assertTrue(hasSampleAsset(presentation, "sfx/jump.wav"),
                "fallback WAV SFX voice must be admitted offline");
        assertTrue(hasSmpsVoice(presentation),
                "SMPS composite voice must be admitted offline");

        LiveCaptureAudioHandle speaker =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 60);
        audio.presentFrame(PresentationMode.FORWARD);
        short[] combined = new short[800 * 2];
        short[] speakerPcm = new short[800 * 2];
        assertEquals(800, audio.drainCaptureFrame(combined));
        assertEquals(800, speaker.drainPresentationFrame(speakerPcm));
        assertFalse(allZero(combined),
                "offline capture renders the same audible sources as live");
        assertArrayEquals(speakerPcm, combined,
                "offline and speaker views are copies of one final packet");

        speaker.close();
        audio.endCaptureMode();
    }

    @Test
    void headlessCaptureNeverOpensAnAudioDevice() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        audio.setBackend(new HeadlessSmpsAudioBackend(
                config, PerformanceProfiler.getInstance()));

        AudioPresentationSink sinkBefore = presentationSink(audio);
        assertInstanceOf(NoDeviceAudioSink.class, sinkBefore,
                "headless initialization uses the no-device sink");

        PcmHistoryRing.DiagnosticSnapshot historyBefore =
                AudioManagerTestDiagnostics.producerFingerprint(audio).history();

        audio.beginCaptureMode(48_000, 60);

        assertEquals(historyBefore,
                AudioManagerTestDiagnostics.producerFingerprint(audio).history(),
                "attaching the offline lease must not allocate or move a "
                        + "second presentation history");

        audio.presentFrame(PresentationMode.FORWARD);
        audio.drainCaptureFrame(new short[800 * 2]);

        assertSame(sinkBefore, presentationSink(audio),
                "offline capture must never open a speaker device");
        assertInstanceOf(NoDeviceAudioSink.class, presentationSink(audio));

        audio.endCaptureMode();
        assertInstanceOf(NoDeviceAudioSink.class, presentationSink(audio));
    }

    @Test
    void offlineCaptureDoesNotReplacePresentationHistoryOwner()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        audio.setBackend(new HeadlessSmpsAudioBackend(
                config, PerformanceProfiler.getInstance()));
        try {
            audio.setRewindHistoryArmed(true);
            assertTrue(audio.releaseStateForTesting().producer().historyArmed());
            PcmHistoryRing.DiagnosticSnapshot historyBeforeAttach =
                    AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .history();

            audio.beginCaptureMode(48_000, 60);

            assertEquals(historyBeforeAttach,
                    AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .history(),
                    "attaching the offline lease must not replace, resize or "
                            + "advance the producer's history");
            audio.presentFrame(PresentationMode.FORWARD);
            short[] target = new short[800 * 2];
            assertEquals(800, audio.drainCaptureFrame(target),
                    "48 kHz capture at 60 Hz must produce exactly 800 stereo frames");

            PcmHistoryRing.DiagnosticSnapshot historyBeforeDetach =
                    AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .history();
            audio.endCaptureMode();

            assertTrue(audio.releaseStateForTesting().producer().historyArmed(),
                    "ending offline capture must leave producer history ownership intact");
            assertEquals(historyBeforeDetach,
                    AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .history(),
                    "detaching the offline lease must not clear or rewind the "
                            + "producer's history");
        } finally {
            audio.endCaptureMode();
            audio.resetState();
            config.resetToDefaults();
        }
    }

    /**
     * The producer rejects a close issued off its owner thread <em>before</em>
     * it releases anything, so a rejected close must leave both capture leases
     * held by the manager. Dropping either manager-side reference there would
     * let the next {@code beginCaptureMode} / {@code beginLiveCaptureAudio}
     * attach a second lease to a producer that still holds the first, silently
     * burning the producer's fixed capture slots and leaving an orphaned lease
     * being fed real packets.
     */
    @Test
    void aRejectedProducerCloseKeepsBothCaptureLeasesHeld() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        // The producer's owner thread is the thread that realizes it, i.e. this
        // one.
        audio.setBackend(new RecordingAudioBackend());
        audio.beginCaptureMode(48_000, 60);
        LiveCaptureAudioHandle live = audio.beginLiveCaptureAudio(60);
        int leasesBefore = AudioManagerTestDiagnostics
                .producerFingerprint(audio).captureCount();
        assertEquals(2, leasesBefore,
                "precondition: one offline and one live lease are attached");

        AtomicReference<Throwable> rejection = new AtomicReference<>();
        Thread offOwner = new Thread(() -> {
            try {
                audio.resetState();
            } catch (Throwable failure) {
                rejection.set(failure);
            }
        }, "off-owner-audio-close");
        offOwner.start();
        offOwner.join();

        assertInstanceOf(IllegalStateException.class, rejection.get(),
                "an off-owner-thread producer close must be rejected");
        assertEquals(leasesBefore, AudioManagerTestDiagnostics
                        .producerFingerprint(audio).captureCount(),
                "a rejected close releases no lease");
        assertThrows(IllegalStateException.class,
                () -> audio.beginLiveCaptureAudio(60),
                "the live lease is still held, so a second attach is refused");
        assertThrows(IllegalStateException.class,
                () -> audio.beginCaptureMode(48_000, 60),
                "the offline lease is still held, so a second attach is "
                        + "refused");

        // The owner thread can still complete the close afterwards.
        audio.resetState();
        live.close();
    }

    private static boolean hasSampleAsset(
            AudioPresentationSnapshot presentation, String assetId) {
        for (PresentationVoiceSnapshot voice : presentation.voices()) {
            if (voice instanceof PresentationVoiceSnapshot.Sample sample
                    && assetId.equals(sample.assetId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSmpsVoice(
            AudioPresentationSnapshot presentation) {
        return presentation.smpsLogical() != null
                && !presentation.smpsLogical().sequencers().isEmpty();
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

    private static AbstractSmpsData smpsData(String name, int id) {
        AbstractSmpsData data = new AudioTestFixtures.StubSmpsData(name);
        data.setId(id);
        return data;
    }

    private static AudioPresentationSink presentationSink(AudioManager audio)
            throws Exception {
        Field field = AudioManager.class.getDeclaredField("presentationSink");
        field.setAccessible(true);
        return (AudioPresentationSink) field.get(audio);
    }

    private static Object producer(AudioManager audio) throws Exception {
        Field field = AudioManager.class.getDeclaredField("shadowProducer");
        field.setAccessible(true);
        return field.get(audio);
    }

    private record CaptureModeProfile(
            SmpsLoader loader,
            SegaPcmSpec spec,
            SmpsSequencerConfig config)
            implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() { return config; }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
        @Override public SegaPcmSpec getSegaPcmSpec() { return spec; }
        @Override public byte[] loadSegaPcm(Object rom) throws java.io.IOException {
            return com.openggf.game.audio.SegaPcmRomReader.read(rom, getSegaPcmSpec());
        }
    }
}
