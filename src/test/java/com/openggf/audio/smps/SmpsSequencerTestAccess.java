package com.openggf.audio.smps;

public final class SmpsSequencerTestAccess {
    private SmpsSequencerTestAccess() {
    }

    public static void addActiveDacTrack(SmpsSequencer sequencer) {
        SmpsSequencer.Track dac = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.DAC, 5);
        dac.active = true;
        sequencer.addTrack(dac);
    }

    public static SmpsSequencer.Track addActiveFmTrack(
            SmpsSequencer sequencer, int channel) {
        SmpsSequencer.Track fm = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.FM, channel);
        fm.active = true;
        sequencer.addTrack(fm);
        return fm;
    }

    public static SmpsSequencer.Track addActivePsgTrack(
            SmpsSequencer sequencer, int channel) {
        SmpsSequencer.Track psg = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.PSG, channel);
        psg.active = true;
        sequencer.addTrack(psg);
        return psg;
    }
}
