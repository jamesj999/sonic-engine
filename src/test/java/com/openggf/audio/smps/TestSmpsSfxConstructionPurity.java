package com.openggf.audio.smps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.synth.ChipWriteObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestSmpsSfxConstructionPurity {
    @Test
    void constructingFmSfxDoesNotMutateSharedSynthBeforeAdmission() {
        RecordingObserver observer = new RecordingObserver();
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000, observer);

        new SmpsSequencer(new SingleFmSfxData(), AudioTestFixtures.EMPTY_DAC,
                driver, () -> {}, new SmpsSequencerConfig.Builder().build());

        assertEquals(List.of(), observer.events,
                "SFX construction must not write DAC enable, voice, or pan registers");
    }

    @Test
    void musicConstructionDoesNotSelectDacOrWriteYm() {
        RecordingObserver observer = new RecordingObserver();
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000, observer);

        new SmpsSequencer(new EmptyMusicData(), AudioTestFixtures.EMPTY_DAC,
                driver, () -> {}, new SmpsSequencerConfig.Builder().build());

        assertEquals(List.of(), observer.events,
                "music construction must only prepare logical state");
    }

    private static final class SingleFmSfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private static final byte[] VOICE = {
                0x3C,
                0x0F, 0x01, 0x03, 0x01,
                0x1F, 0x1F, 0x1F, 0x1F,
                0x19, 0x12, 0x19, 0x0E,
                0x05, 0x12, 0x00, 0x0F,
                0x0F, 0x7F, (byte) 0xFF, (byte) 0xFF,
                0x00, (byte) 0x80, 0x00, (byte) 0x80
        };

        private SingleFmSfxData() {
            super(new byte[] {0, (byte) 0xF2}, 0);
            setId(0xC1);
        }

        @Override
        public int getTickMultiplier() {
            return 1;
        }

        @Override
        public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new Track(5, 1, 0, 0));
        }

        @Override
        protected void parseHeader() {
            dividingTiming = 1;
            tempo = 1;
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return voiceId == 0 ? VOICE.clone() : null;
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return null;
        }

        @Override
        public int read16(int offset) {
            return 0;
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }

    private record Track(int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class EmptyMusicData extends AbstractSmpsData {
        private EmptyMusicData() {
            super(new byte[0], 0);
        }

        @Override protected void parseHeader() { dividingTiming = 1; tempo = 1; }
        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
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
