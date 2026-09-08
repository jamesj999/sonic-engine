package com.openggf.audio.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestS1SfxTakeoverOrder {

    @Test
    void sonic1FmTakeoverStartsWithTheSfxRegisterInsteadOfSyntheticReset() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer sfx = sequencer(driver, Sonic1SmpsSequencerConfig.CONFIG);
        driver.addSequencer(sfx, true);
        observer.events.clear();

        sfx.writeFm(1, 0xB1, 0x3C);

        assertEquals(List.of("YM:1:B1:3C"), observer.events,
                "S1 SetVoice must be the first visible FM5 takeover write");
    }

    @Test
    void legacyProfilesRetainTheirExistingSyntheticTakeoverPolicy() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer sfx = sequencer(driver, new SmpsSequencerConfig.Builder().build());
        driver.addSequencer(sfx, true);
        observer.events.clear();

        sfx.writeFm(1, 0xB1, 0x3C);

        assertEquals(List.of("YM:0:28:05", "YM:1:B1:3C"), observer.events);
    }

    @Test
    void sonic1PsgTakeoverStartsWithTheSfxLatchInsteadOfSyntheticSilence() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer sfx = sequencer(driver, Sonic1SmpsSequencerConfig.CONFIG);
        driver.addSequencer(sfx, true);
        observer.events.clear();

        sfx.writePsg(0x80);

        assertEquals(List.of("PSG:80"), observer.events,
                "S1 Sound_PlaySFX emits no PSG1 admission silence; the"
                        + " SFX track's first latch owns the visible write");
    }

    @Test
    void sonic1Psg3AdmissionEmitsOnlyTheRomsExplicitToneAndNoiseSilencePair() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);

        driver.addSequencer(sequencer(driver,
                Sonic1SmpsSequencerConfig.CONFIG, 0xC0), true);

        assertEquals(List.of("PSG:DF", "PSG:FF"), observer.events,
                "S1 Sound_PlaySFX explicitly silences PSG3 and noise while"
                        + " loading a PSG3 SFX track");
    }

    @Test
    void sonic1Psg3ReplacementDoesNotDuplicateTheAdmissionSilencePair() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer oldSfx = sequencer(
                driver, Sonic1SmpsSequencerConfig.CONFIG, 0xC0, 0xC1);
        SmpsSequencer replacement = sequencer(
                driver, Sonic1SmpsSequencerConfig.CONFIG, 0xC0, 0xC2);
        driver.addSequencer(oldSfx, true);
        driver.writePsg(oldSfx, 0xC0);
        observer.events.clear();

        driver.addSequencer(replacement, true);

        assertEquals(List.of("PSG:DF", "PSG:FF"), observer.events,
                "S1's explicit PSG3 header pair is the sole admission write");
    }

    @Test
    void psg3SfxOwnershipAlsoSuppressesMusicNoiseLatches() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer sfx = sequencer(driver,
                Sonic1SmpsSequencerConfig.CONFIG, 0xC0);
        driver.addSequencer(sfx, true);
        driver.writePsg(sfx, 0xC0);
        observer.events.clear();

        driver.writePsg(sequencer(driver,
                Sonic1SmpsSequencerConfig.CONFIG), 0xF3);

        assertEquals(List.of(), observer.events,
                "SMPS PSG3 and noise share one source-driver ownership slot");
    }

    @Test
    void legacyProfilesRetainTheirSyntheticPsgTakeoverSilence() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer sfx = sequencer(
                driver, new SmpsSequencerConfig.Builder().build());
        driver.addSequencer(sfx, true);
        observer.events.clear();

        sfx.writePsg(0x80);

        assertEquals(List.of("PSG:9F", "PSG:80"), observer.events);
    }

    @Test
    void frequencyPairUsesItsSourceTrackGateButKeepsPhysicalLatchSemantics() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer noiseOwner = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xC0, 0xC1);
        SmpsSequencer toneOwner = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xA0, 0xC2);
        driver.addSequencer(noiseOwner, true);
        driver.writePsg(noiseOwner, 0xF0);
        driver.addSequencer(toneOwner, true);
        observer.events.clear();

        driver.writePsgFrequencyPair(toneOwner, 0xAF, 0xFF);

        assertEquals(List.of("PSG:AF", "PSG:FF"), observer.events,
                "the ROM-gated pair remains adjacent despite separate noise ownership");
        var psg = SmpsDriverTestAccess.captureSynthSnapshot(driver).psg();
        assertEquals(7, psg.latch(),
                "FF remains a physical PSG3-volume latch, not tone data");
        assertEquals(15, psg.attenuations()[3],
                "the physical FF must silence noise exactly as the chip decodes it");
        assertEquals(3, toneOwner.getPsgLatchChannel(),
                "later ordinary source writes must see the physical FF latch");
    }

    @Test
    void deniedFrequencyPairChangesNeitherBusNorPhysicalLatch() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer toneOwner = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xA0, 0xC1);
        driver.addSequencer(toneOwner, true);
        driver.writePsg(toneOwner, 0xAF);
        int latchBefore = SmpsDriverTestAccess.captureSynthSnapshot(driver)
                .psg().latch();
        SmpsSequencer music = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xA0, 0x81);
        int sourceLatchBefore = music.getPsgLatchChannel();
        observer.events.clear();

        driver.writePsgFrequencyPair(music, 0xAF, 0xFF);

        assertEquals(List.of(), observer.events,
                "a denied source gate must reject the complete transaction");
        assertEquals(latchBefore, SmpsDriverTestAccess.captureSynthSnapshot(driver)
                .psg().latch(), "a denied pair must not mutate physical latch state");
        assertEquals(sourceLatchBefore, music.getPsgLatchChannel(),
                "a denied pair must not mutate source-local latch state");
    }

    @Test
    void frequencyTransactionDoesNotBypassUnrelatedSingleLatchOwnership() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);
        SmpsSequencer noiseOwner = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xC0, 0xC1);
        driver.addSequencer(noiseOwner, true);
        driver.writePsg(noiseOwner, 0xFF);
        observer.events.clear();
        SmpsSequencer music = sequencer(
                driver, Sonic3kSmpsSequencerConfig.CONFIG, 0xA0, 0x81);

        driver.writePsg(music, 0xFF);

        assertEquals(List.of(), observer.events,
                "ordinary latches retain their own per-channel ownership gate");
    }

    @Test
    void frequencyTransactionRejectsANonToneFirstByteWithoutWriting() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);

        assertThrows(IllegalArgumentException.class,
                () -> driver.writePsgFrequencyPair(driver, 0xBF, 0x12));
        assertEquals(List.of(), observer.events);
    }

    @Test
    void driverOwnedFrequencyTransactionPreservesStandaloneBusSemantics() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriverTestAccess.setChipWriteObserver(driver, observer);

        driver.writePsgFrequencyPair(null, 0x84, 0x12);

        assertEquals(List.of("PSG:84", "PSG:12"), observer.events);
        var psg = SmpsDriverTestAccess.captureSynthSnapshot(driver).psg();
        assertEquals(0, psg.latch());
        assertEquals(0x124, psg.tonePeriods()[0]);
    }

    private static SmpsSequencer sequencer(SmpsDriver driver, SmpsSequencerConfig config) {
        return sequencer(driver, config, 5);
    }

    private static SmpsSequencer sequencer(
            SmpsDriver driver, SmpsSequencerConfig config, int channelMask) {
        return sequencer(driver, config, channelMask, 0xC1);
    }

    private static SmpsSequencer sequencer(
            SmpsDriver driver, SmpsSequencerConfig config,
            int channelMask, int id) {
        return new SmpsSequencer(new SingleTrackSfxData(channelMask, id), AudioTestFixtures.EMPTY_DAC,
                driver, () -> {}, config);
    }

    private static final class SingleTrackSfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private final int channelMask;

        private SingleTrackSfxData(int channelMask, int id) {
            super(new byte[] {0, (byte) 0xF2}, 0);
            setId(id);
            this.channelMask = channelMask;
        }

        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new Track(channelMask, 1, 0, 0));
        }
        @Override protected void parseHeader() { dividingTiming = 1; tempo = 1; }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private record Track(int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class RecordingObserver implements ChipWriteObserver {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            events.add("YM:%d:%02X:%02X".formatted(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            events.add("PSG:%02X".formatted(value));
        }
    }
}
