package com.openggf.audio.smps;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kOverriddenPsgEnvelopeTiming {
    @Test
    void overriddenAttackResetsWithoutSteppingUntilRelease() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = sequencer(
                Sonic3kSmpsSequencerConfig.CONFIG,
                new byte[] {(byte) 0x81, 2, (byte) 0xF2}, synth);
        SmpsSequencer.Track track = psgTrack(sequencer);
        track.overridden = true;

        sequencer.advanceSamples(0);

        assertEquals(0, track.envPos,
                "zFinishTrackUpdate resets VolEnv before the override return");
        assertTrue(volumeLatches(synth.writes).isEmpty(),
                "the override gate precedes zDoVolEnv and its volume tail");

        sequencer.setChannelOverridden(
                SmpsSequencer.TrackType.PSG, 2, false);
        synth.writes.clear();
        sequencer.advanceBatch(1);

        assertEquals(1, track.envPos,
                "the first unowned note-going pass consumes envelope byte zero");
        assertEquals(1, volumeLatches(synth.writes).size(),
                "release must step and send the envelope exactly once");
    }

    @Test
    void tiedNoteStepsItsCursorWhileRestResetsWithoutStepping() {
        CaptureSynth tiedSynth = new CaptureSynth();
        SmpsSequencer tied = sequencer(Sonic3kSmpsSequencerConfig.CONFIG,
                new byte[] {(byte) 0xE7, (byte) 0x81, 2, (byte) 0xF2}, tiedSynth);
        SmpsSequencer.Track tiedTrack = psgTrack(tied);
        tiedTrack.envPos = 1;
        tiedTrack.envValue = 3;
        tied.advanceSamples(0);
        assertEquals(2, tiedTrack.envPos,
                "a tied note preserves then steps its existing cursor");

        CaptureSynth restSynth = new CaptureSynth();
        SmpsSequencer rest = sequencer(Sonic3kSmpsSequencerConfig.CONFIG,
                new byte[] {(byte) 0x80, 2, (byte) 0xF2}, restSynth);
        SmpsSequencer.Track restTrack = psgTrack(rest);
        restTrack.envPos = 1;
        rest.advanceSamples(0);
        assertEquals(0, restTrack.envPos,
                "an attacked rest resets VolEnv before returning on the rest bit");
        assertTrue(restTrack.resting);
    }

    @Test
    void sonic1AndSonic2RetainTheirAttackedNoteEnvelopeStep() {
        for (SmpsSequencerConfig config : List.of(
                Sonic1SmpsSequencerConfig.CONFIG,
                Sonic2SmpsSequencerConfig.CONFIG)) {
            CaptureSynth synth = new CaptureSynth();
            SmpsSequencer sequencer = sequencer(config,
                    new byte[] {(byte) 0x81, 2, (byte) 0xF2}, synth);
            SmpsSequencer.Track track = psgTrack(sequencer);
            track.overridden = true;

            sequencer.advanceSamples(0);

            assertEquals(1, track.envPos,
                    "earlier drivers keep their existing note-start envelope timing");
        }
    }

    private static SmpsSequencer sequencer(
            SmpsSequencerConfig config, byte[] program, CaptureSynth synth) {
        SmpsSequencer sequencer = new SmpsSequencer(
                new ProgramData(program), AudioTestFixtures.EMPTY_DAC,
                synth, () -> { }, config);
        sequencer.setSampleRate(60.0);
        return sequencer;
    }

    private static SmpsSequencer.Track psgTrack(SmpsSequencer sequencer) {
        SmpsSequencer.Track track = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.PSG, 2);
        track.active = true;
        track.instrumentId = 1;
        track.envData = new byte[] {3, 4, (byte) 0x81};
        sequencer.addTrack(track);
        return track;
    }

    private static List<Integer> volumeLatches(List<Integer> writes) {
        return writes.stream()
                .filter(value -> (value & 0x90) == 0x90)
                .toList();
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
            tempo = 1;
        }

        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
