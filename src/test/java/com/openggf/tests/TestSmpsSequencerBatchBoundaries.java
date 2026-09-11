package com.openggf.tests;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.AudioManager;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSmpsSequencerBatchBoundaries {

    @Test
    public void durationThreeAtZeroReturnsTwentyFourSamples() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSamplesPerFrame(seq, 8.0);
        setSampleCounter(seq, 0.0);
        setTempoWeight(seq, 1);
        Track track = addTrack(seq, TrackType.FM, 1);
        track.duration = 3;
        track.scaledDuration = 3;
        track.fill = 0;

        assertEquals(24, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    public void fillNoteOffUsesShortenedDurationBoundary() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSamplesPerFrame(seq, 8.0);
        setSampleCounter(seq, 0.0);
        setTempoWeight(seq, 1);
        Track track = addTrack(seq, TrackType.FM, 1);
        track.duration = 5;
        track.scaledDuration = 5;
        track.fill = 2;

        assertEquals(16, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    public void activeFadeUsesNextTempoSampleBoundary() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSamplesPerFrame(seq, 8.0);
        setSampleCounter(seq, 0.0);
        setTempoWeight(seq, 1);
        setFadeState(seq, true, 1, 2);

        assertEquals(8, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    public void sfxModeWithSingleTickBudgetUsesNextTempoSampleBoundary() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSamplesPerFrame(seq, 8.0);
        setSampleCounter(seq, 0.0);
        setTempoWeight(seq, 1);
        setSfxMode(seq, true);
        setMaxTicks(seq, 1);

        assertEquals(8, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    public void nextTempoFrameUsesRemainingFractionalSamples() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSamplesPerFrame(seq, 8.0);
        setSampleCounter(seq, 7.0);
        setTempoWeight(seq, 1);

        assertEquals(1, seq.getSamplesUntilNextTempoFrame());
    }

    @Test
    public void activeFadeDoesNotForceSampleAccurateFallback() throws Exception {
        // Fade volume steps only apply inside processTempoFrame(), and hybrid chunks
        // never cross a tempo-frame boundary, so an active fade no longer degrades
        // the driver to per-sample rendering. TestSmpsFadeHybridParity is the
        // PCM-level proof that hybrid fade windows stay sample-identical.
        SmpsSequencer seq = newSequencer();
        setFadeState(seq, true, 1, 2);

        assertFalse(seq.requiresSampleAccurateFallback());
    }

    @Test
    public void requiresSampleAccurateFallbackForSpeedMultiplier() throws Exception {
        SmpsSequencer seq = newSequencer();
        setSpeedMultiplier(seq, 2);

        assertTrue(seq.requiresSampleAccurateFallback());
    }

    private static SmpsSequencer newSequencer() {
        byte[] data = new byte[32];
        data[2] = 2;
        data[5] = (byte) 0x80;
        AbstractSmpsData smps = new Sonic2SmpsData(data);
        return new SmpsSequencer(smps, null, new VirtualSynthesizer(), AudioManager.getInstance(),
                Sonic2SmpsSequencerConfig.CONFIG);
    }

    private static Track addTrack(SmpsSequencer seq, TrackType type, int channelId) throws Exception {
        java.lang.reflect.Constructor<Track> ctor = Track.class.getDeclaredConstructor(int.class, TrackType.class, int.class);
        ctor.setAccessible(true);
        Track track = ctor.newInstance(0, type, channelId);
        List<Track> tracks = tracks(seq);
        tracks.add(track);
        return track;
    }

    @SuppressWarnings("unchecked")
    private static List<Track> tracks(SmpsSequencer seq) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("tracks");
        field.setAccessible(true);
        return (List<Track>) field.get(seq);
    }

    private static void setSamplesPerFrame(SmpsSequencer seq, double value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("samplesPerFrame");
        field.setAccessible(true);
        field.setDouble(seq, value);
    }

    private static void setSampleCounter(SmpsSequencer seq, double value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("sampleCounter");
        field.setAccessible(true);
        field.setDouble(seq, value);
    }

    private static void setTempoWeight(SmpsSequencer seq, int value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("tempoWeight");
        field.setAccessible(true);
        field.setInt(seq, value);
    }

    private static void setMaxTicks(SmpsSequencer seq, int value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("maxTicks");
        field.setAccessible(true);
        field.setInt(seq, value);
    }

    private static void setSpeedMultiplier(SmpsSequencer seq, int value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("speedMultiplier");
        field.setAccessible(true);
        field.setInt(seq, value);
    }

    private static void setSfxMode(SmpsSequencer seq, boolean value) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("sfxMode");
        field.setAccessible(true);
        field.setBoolean(seq, value);
    }

    private static void setFadeState(SmpsSequencer seq, boolean active, int delayCounter, int delayInit) throws Exception {
        Field field = SmpsSequencer.class.getDeclaredField("fadeState");
        field.setAccessible(true);
        Object fadeState = field.get(seq);
        setField(fadeState, "active", active);
        setField(fadeState, "delayCounter", delayCounter);
        setField(fadeState, "delayInit", delayInit);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == int.class) {
            field.setInt(target, (Integer) value);
        } else if (field.getType() == boolean.class) {
            field.setBoolean(target, (Boolean) value);
        } else {
            field.set(target, value);
        }
    }
}
