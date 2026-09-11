package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.stream.Stream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestShadowAudioPresentationRouting {
    private final AudioManager audio = AudioManager.getInstance();

    @AfterEach
    void tearDown() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void sixtyPresentedFramesTickShadowExactlySixtyTimes() {
        audio.setBackend(new NullAudioBackend());
        for (int frame = 0; frame < 60; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        var snapshot = audio.shadowParitySnapshot();
        assertEquals(60, snapshot.presentedFrames());
        assertEquals(60, snapshot.forwardFrames());
        assertEquals(0, snapshot.silentFrames());
        assertEquals(0, snapshot.reverseFrames());
    }

    @Test
    void everyLegacyControlHasOneSameOrderShadowCommand() {
        audio.setBackend(new NullAudioBackend());
        audio.setSpeedShoes(true);
        audio.setSpeedMultiplier(2);
        audio.changeMusicTempo(3);
        audio.stopAllSfx();
        audio.stopMusic();
        audio.restoreMusic();
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(6,
                audio.shadowParitySnapshot().commandCount());
    }

    @Test
    void repeatedBaseSmpsMusicLoadsAndFreezesOncePerGeneration() {
        CountingMusicLoader loader = new CountingMusicLoader();
        audio.setAudioProfile(new CountingMusicProfile(loader));
        audio.setRom(new Rom());

        audio.playMusic(0x81);
        audio.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot rewindPoint = audio.captureLogicalSnapshot();
        long firstVoiceId = rewindPoint.presentation()
                .activeMusic().voiceId();
        SmpsDriverSnapshot.SequencerEntry first =
                audio.shadowSmpsDriverSnapshotForTesting()
                        .sequencers().getFirst();
        audio.update();

        audio.playMusic(0x81);
        audio.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot repeated = audio.captureLogicalSnapshot();
        long secondVoiceId = repeated.presentation()
                .activeMusic().voiceId();
        SmpsDriverSnapshot.SequencerEntry second =
                audio.shadowSmpsDriverSnapshotForTesting()
                        .sequencers().getFirst();

        assertEquals(1, loader.musicLoads.get(),
                "manager classification and resolver application must share "
                        + "one registered load");
        assertEquals(1, loader.materializations.get(),
                "a repeated manager start must not freeze, hash, or compare "
                        + "the music again");
        assertNotEquals(firstVoiceId, secondVoiceId,
                "every start still owns a distinct mutable music voice");
        assertSharedMusicDependencies(first, second);
        assertNotSame(first.snapshot(), second.snapshot());
        assertNotSame(first.snapshot().tracks(), second.snapshot().tracks());
        assertNotSame(first.snapshot().tracks().getFirst(),
                second.snapshot().tracks().getFirst());

        audio.restoreLogicalSnapshot(rewindPoint);
        SmpsDriverSnapshot.SequencerEntry restored =
                audio.shadowSmpsDriverSnapshotForTesting()
                        .sequencers().getFirst();
        assertSharedMusicDependencies(first, restored);

        audio.update();
        audio.setRom(new Rom());
        audio.playMusic(0x81);
        audio.presentFrame(PresentationMode.SILENT);
        SmpsDriverSnapshot.SequencerEntry nextGeneration =
                audio.shadowSmpsDriverSnapshotForTesting()
                        .sequencers().getFirst();

        assertEquals(2, loader.musicLoads.get(),
                "a source generation change must cause one additional load");
        assertEquals(2, loader.materializations.get(),
                "a source generation change must cause one additional freeze");
        assertNotEquals(first.source().dependencyGeneration(),
                nextGeneration.source().dependencyGeneration());
        assertNotSame(first.smpsData(), nextGeneration.smpsData());
    }

    @Test
    void nullBaseSmpsProbeRetainsFallbackWavRouting() {
        CountingMusicLoader loader = new CountingMusicLoader();
        loader.returnNull = true;
        audio.setAudioProfile(new CountingMusicProfile(loader));
        audio.setRom(new Rom());

        audio.playMusic(0x82);

        var command = (com.openggf.audio.rewind.AudioCommand.PlayMusic)
                audio.commandTimeline().entryAt(0).command();
        assertEquals(com.openggf.audio.rewind.AudioCommand.MusicRoute.FALLBACK_WAV,
                command.route());
        assertEquals(1, loader.musicLoads.get());
        assertEquals(0, loader.materializations.get());
    }

    @Test
    void backendRemainsCompatibilityOwnerAcrossPresentationTicks() {
        NullAudioBackend backend = new NullAudioBackend();
        audio.setBackend(backend);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(backend, audio.getBackend());
    }

    @Test
    void muteAndSoloQueriesUseShadowState() {
        audio.setBackend(new NullAudioBackend());
        audio.toggleMute(ChannelType.FM, 2);
        audio.toggleSolo(ChannelType.PSG, 1);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(true, audio.isMuted(ChannelType.FM, 2));
        assertEquals(true, audio.isSoloed(ChannelType.PSG, 1));
    }

    @Test
    void nineSimulationDevicePumpsProduceOneOuterPresentationPacket() {
        audio.setBackend(new NullAudioBackend());

        for (int simulationStep = 0; simulationStep < 9; simulationStep++) {
            audio.updateLegacyDevice();
        }
        assertEquals(0,
                audio.shadowParitySnapshot().presentedFrames(),
                "simulation-only pumps must not present a packet");

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1,
                audio.shadowParitySnapshot().presentedFrames());
    }

    @ParameterizedTest
    @MethodSource("presentationTunings")
    void shadowUsesTheLegacyBackendPresentationTuning(
            AudioPresentationTuning tuning) {
        audio.setBackend(new TuningBackend(tuning));

        assertEquals(tuning, audio.shadowTuningForTesting());
    }

    static Stream<AudioPresentationTuning> presentationTunings() {
        return Stream.of(
                new AudioPresentationTuning(
                        SmpsSequencer.Region.NTSC, false, false),
                new AudioPresentationTuning(
                        SmpsSequencer.Region.PAL, true, true),
                new AudioPresentationTuning(
                        SmpsSequencer.Region.NTSC, false, true),
                new AudioPresentationTuning(
                        SmpsSequencer.Region.PAL, true, false));
    }

    @Test
    void presentationFailureNeverFallsBackToLegacyAudibleCommand() {
        FailingShadowBackend backend = new FailingShadowBackend();
        audio.setBackend(backend);

        assertDoesNotThrow(audio::stopMusic);

        assertEquals(false, backend.stopped,
                "legacy backend must never become a second audible owner");
        assertEquals(1, audio.commandTimeline().entryCount(),
                "logical ordering remains recorded");
    }

    @Test
    void rawPcmPresentationFailureNeverFallsBackToLegacyAudibleCommand()
            throws Exception {
        FailingShadowBackend backend = new FailingShadowBackend();
        Rom rom = mock(Rom.class);
        byte[] pcm = {1, 2, 3, 4};
        when(rom.readBytes(10, pcm.length)).thenReturn(pcm);
        audio.setBackend(backend);
        audio.setAudioProfile(new PcmProfile(
                new SegaPcmSpec(10, pcm.length, 8_000)));
        audio.setRom(rom);

        assertDoesNotThrow(audio::playSegaPcm);

        assertEquals(0, backend.totalCalls(),
                "a failed presentation must not fall back to the backend");
    }

    @Test
    void muteAndSoloNeverReachLegacyBackend() {
        FailingShadowBackend backend = new FailingShadowBackend();
        audio.setBackend(backend);

        assertDoesNotThrow(() -> audio.toggleMute(ChannelType.FM, 1));
        assertDoesNotThrow(() -> audio.toggleSolo(ChannelType.PSG, 2));

        assertEquals(0, backend.muteCalls);
        assertEquals(0, backend.soloCalls);
    }

    private static class TuningBackend extends NullAudioBackend {
        private final AudioPresentationTuning tuning;

        TuningBackend(AudioPresentationTuning tuning) {
            this.tuning = tuning;
        }

        @Override
        public AudioPresentationTuning presentationTuning() {
            return tuning;
        }
    }

    private static void assertSharedMusicDependencies(
            SmpsDriverSnapshot.SequencerEntry expected,
            SmpsDriverSnapshot.SequencerEntry actual) {
        assertSame(expected.source(), actual.source());
        assertSame(expected.smpsData(), actual.smpsData());
        assertSame(expected.dacData(), actual.dacData());
        assertSame(expected.config(), actual.config());
    }

    private static final class CountingMusicLoader implements SmpsLoader {
        private final AtomicInteger musicLoads = new AtomicInteger();
        private final AtomicInteger materializations = new AtomicInteger();
        private final DacData dac = new DacData(
                Collections.emptyMap(), Collections.emptyMap(), 297);
        private boolean returnNull;

        @Override
        public AbstractSmpsData loadMusic(int musicId) {
            musicLoads.incrementAndGet();
            return returnNull ? null
                    : new CountingMusicData(musicId, materializations);
        }

        @Override
        public AbstractSmpsData loadSfx(int sfxId) {
            return null;
        }

        @Override
        public AbstractSmpsData loadSfx(String sfxName) {
            return null;
        }

        @Override
        public DacData loadDacData() {
            return dac;
        }
    }

    private static final class CountingMusicData extends AbstractSmpsData {
        private final AtomicInteger materializations;

        private CountingMusicData(
                int musicId, AtomicInteger materializations) {
            super(musicBytes(), 0);
            this.materializations = materializations;
            setId(musicId);
        }

        private static byte[] musicBytes() {
            byte[] bytes = new byte[0x80];
            bytes[2] = 1;
            bytes[4] = 1;
            bytes[5] = (byte) 0x80;
            bytes[6] = 0x40;
            bytes[0x40] = (byte) 0xF2;
            return bytes;
        }

        @Override
        protected void parseHeader() {
            channels = data[2] & 0xFF;
            psgChannels = data[3] & 0xFF;
            dividingTiming = data[4] & 0xFF;
            tempo = data[5] & 0xFF;
            fmPointers = new int[] {read16(6)};
        }

        @Override
        public byte[] getData() {
            materializations.incrementAndGet();
            return super.getData();
        }

        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) {
            return new byte[] {(byte) 0x81};
        }
        @Override public int read16(int offset) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8);
        }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class CountingMusicProfile
            implements GameAudioProfile {
        private final CountingMusicLoader loader;
        private final SmpsSequencerConfig config =
                new SmpsSequencerConfig.Builder().build();

        private CountingMusicProfile(CountingMusicLoader loader) {
            this.loader = loader;
        }

        @Override public String presentationGameId() { return "base"; }
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return config;
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() {
            return Map.of();
        }
    }

    private static final class FailingShadowBackend
            extends NullAudioBackend {
        boolean stopped;
        int muteCalls;
        int soloCalls;

        int totalCalls() {
            return muteCalls + soloCalls + (stopped ? 1 : 0);
        }

        @Override
        public AudioPresentationTuning presentationTuning() {
            throw new IllegalStateException("injected shadow failure");
        }

        @Override
        public void stopPlayback() {
            stopped = true;
        }

        @Override
        public void toggleMute(ChannelType type, int channel) {
            muteCalls++;
        }

        @Override
        public void toggleSolo(ChannelType type, int channel) {
            soloCalls++;
        }
    }

    private record PcmProfile(SegaPcmSpec spec) implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return null; }
        @Override public SmpsSequencerConfig getSequencerConfig() { return null; }
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
