package com.openggf.audio.smps;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPsgVolumeChangeSemantics {
    @Test
    void s3kRewindsEnvelopeClearsRestAndClampsAtFifteenWithoutWriting() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer.Track track = runCommand(
                Sonic3kSmpsSequencerConfig.CONFIG, synth, 0x0C, 0, true, 3);

        assertEquals(0xFF, track.envPos);
        assertFalse(track.resting);
        assertEquals(0x0F, track.volumeOffset);
        assertTrue(synth.writes.isEmpty(),
                "cfChangePSGVolume changes track RAM; the update tail owns PSG output");
    }

    @Test
    void s3kClampsSignedUnderflowAfterByteWrap() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer.Track track = runCommand(
                Sonic3kSmpsSequencerConfig.CONFIG, synth,
                0, 4, false, -1);

        assertEquals(3, track.envPos);
        assertEquals(0x0F, track.volumeOffset,
                "00h + FFh wraps to FFh, which fails the unsigned CP 0Fh carry test");
        assertTrue(synth.writes.isEmpty());
    }

    @Test
    void s3kIgnoresPsgVolumeCommandOnAnFmTrack() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = new SmpsSequencer(
                new ProgramData(new byte[] {(byte) 0xEC, 3, 0}),
                AudioTestFixtures.EMPTY_DAC, synth, () -> { },
                Sonic3kSmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(60.0);
        SmpsSequencer.Track track = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.FM, 2);
        track.active = true;
        track.volumeOffset = 0x0C;
        track.envPos = 0;
        track.resting = true;
        sequencer.addTrack(track);

        sequencer.advanceSamples(0);
        sequencer.advanceBatch(1024);

        assertEquals(0x0C, track.volumeOffset);
        assertEquals(0, track.envPos);
        assertTrue(track.resting);
    }

    private static SmpsSequencer.Track runCommand(
            SmpsSequencerConfig config, CaptureSynth synth, int volume,
            int envPos, boolean resting, int delta) {
        return runCommand(config, synth, SmpsSequencer.TrackType.PSG,
                volume, envPos, resting, delta);
    }

    private static SmpsSequencer.Track runCommand(
            SmpsSequencerConfig config, CaptureSynth synth,
            SmpsSequencer.TrackType type, int volume,
            int envPos, boolean resting, int delta) {
        SmpsSequencer sequencer = new SmpsSequencer(
                // Terminate immediately after EC: this isolates the command's
                // RAM mutation and deliberately does not claim how a later
                // zDoVolEnv consumes the wrapped FF cursor.
                new ProgramData(new byte[] {(byte) 0xEC, (byte) delta, 0}),
                AudioTestFixtures.EMPTY_DAC, synth, () -> { }, config);
        sequencer.setSampleRate(60.0);
        SmpsSequencer.Track track = new SmpsSequencer.Track(
                0, type, 2);
        track.active = true;
        track.volumeOffset = volume;
        track.envPos = envPos;
        track.resting = resting;
        sequencer.addTrack(track);

        sequencer.advanceSamples(0);
        sequencer.advanceBatch(1024);
        return track;
    }

    private static final class CaptureSynth extends VirtualSynthesizer {
        private final List<Integer> writes = new ArrayList<>();

        @Override
        public void writePsg(Object source, int value) {
            writes.add(value & 0xFF);
            super.writePsg(source, value);
        }
    }

    private static final class ProgramData extends AbstractSmpsData {
        private ProgramData(byte[] program) {
            super(program, 0);
            tempo = 0x80;
        }

        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
