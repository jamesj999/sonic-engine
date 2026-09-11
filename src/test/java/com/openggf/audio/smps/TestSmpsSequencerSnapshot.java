package com.openggf.audio.smps;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSmpsSequencerSnapshot {

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void captureAndRestoreRoundTripsSequencerAndTrackStateWithDeepCopies() {
        SmpsSequencer sequencer = newSequencer();
        sequencer.setRegion(SmpsSequencer.Region.PAL);
        sequencer.setSpeedShoes(true);
        sequencer.setSpeedMultiplier(4);
        sequencer.setSfxMode(true);
        sequencer.setNormalTempo(0x33);
        sequencer.setCommData(0x7A);
        sequencer.setFm6DacOff(true);
        sequencer.setIsSfx(true);
        sequencer.setPitch(1.5f);
        sequencer.setSfxPriority(0x92);
        sequencer.setSpecialSfx(true);
        sequencer.setPsgLatchChannel(2);
        sequencer.triggerFadeOut(5, 2);

        SmpsSequencer.Track track = new SmpsSequencer.Track(0x1234, SmpsSequencer.TrackType.FM, 3);
        populateTrack(track);
        sequencer.addTrack(track);

        SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
        snapshot.tracks().get(0).loopCounters()[0] = 77;
        snapshot.tracks().get(0).voiceData()[0] = 77;
        snapshot.tracks().get(0).customSsgEgPayload()[0] = 77;

        sequencer.setSpeedShoes(false);
        sequencer.setSpeedMultiplier(1);
        sequencer.setNormalTempo(0x11);
        track.pos = 0;
        track.loopCounters[0] = 99;
        track.returnStack[0] = 99;
        track.voiceData[0] = 99;
        track.voiceScratch[0] = 99;
        track.modEnvData[0] = 99;
        track.envData[0] = 99;
        track.fmVolEnvData[0] = 99;
        track.ssgEg[0] = 99;

        sequencer.restoreSnapshot(snapshot);

        assertEquals(4, sequencer.getSpeedMultiplier());
        assertEquals(0x33, sequencer.getNormalTempo());
        assertEquals(0x7A, sequencer.getCommData());

        SmpsSequencerSnapshot restored = sequencer.captureSnapshot();
        assertEquals(SmpsSequencer.Region.PAL, restored.region());
        assertTrue(restored.speedShoes());
        assertTrue(restored.sfxMode());
        assertTrue(restored.fm6DacOff());
        assertTrue(restored.sfx());
        assertTrue(restored.specialSfx());
        assertEquals(0x92, restored.sfxPriority());
        assertEquals(2, restored.psgLatchChannel());
        assertTrue(restored.fade().active());
        assertTrue(restored.fade().fadeOut());
        assertEquals(5, restored.fade().steps());

        SmpsSequencer.Track restoredTrack = sequencer.getTracks().get(0);
        assertEquals(0x1234, restoredTrack.pos);
        assertEquals(7, restoredTrack.loopCounters[0]);
        assertEquals(0x4567, restoredTrack.returnStack[0]);
        assertEquals(1, restoredTrack.voiceData[0]);
        assertEquals(3, restoredTrack.voiceScratch[0]);
        assertEquals(5, restoredTrack.modEnvData[0]);
        assertEquals(7, restoredTrack.envData[0]);
        assertEquals(9, restoredTrack.fmVolEnvData[0]);
        assertEquals(0x22, restoredTrack.ssgEg[0]);
        assertTrue(restoredTrack.customSsgEgPresent);
        assertArrayEquals(new int[] {0x22, 0, 0, 0}, restoredTrack.customSsgEgPayload);
        assertTrue(restoredTrack.customSsgEgPayloadKnown);
        assertEquals(0xE7, restoredTrack.rawPsgNoise);
        assertTrue(restoredTrack.rawPsgNoiseKnown);
        assertTrue(restoredTrack.modStepInEffect);
        assertTrue(restoredTrack.modEnvStepChanged);
        assertEquals(0x66, restoredTrack.modEnvStepDelta);
        assertTrue(restoredTrack.fm3SpecialMode);
    }

    @Test
    void restoreSnapshotDoesNotPersistFadeCompleteCallback() {
        SmpsSequencer sequencer = newSequencer();
        sequencer.setOnFadeComplete(() -> {});
        assertTrue(sequencer.hasFadeCompleteCallback());

        SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
        SmpsSequencer restored = newSequencer();
        restored.restoreSnapshot(snapshot);

        assertFalse(restored.hasFadeCompleteCallback());
    }

    private static SmpsSequencer newSequencer() {
        return new SmpsSequencer(
                new AudioTestFixtures.StubSmpsData("snapshot"),
                AudioTestFixtures.EMPTY_DAC,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
    }

    private static void populateTrack(SmpsSequencer.Track track) {
        track.duration = 12;
        track.note = 0x45;
        track.active = true;
        track.overridden = true;
        track.rawDuration = 6;
        track.scaledDuration = 8;
        track.fill = 2;
        track.keyOffset = -3;
        track.volumeOffset = 4;
        track.tieNext = true;
        track.pan = 0x80;
        track.ams = 1;
        track.fms = 2;
        track.voiceData = new byte[] {1, 2};
        track.voiceScratch[0] = 3;
        track.voiceId = 4;
        track.baseFnum = 0x321;
        track.baseBlock = 5;
        track.loopCounters[0] = 7;
        track.loopTarget = 0x2345;
        track.returnStack[0] = 0x4567;
        track.returnSp = 1;
        track.dividingTiming = 3;
        track.modDelay = 1;
        track.modDelayInit = 2;
        track.modRate = 3;
        track.modDelta = 4;
        track.modSteps = 5;
        track.modStepsFull = 6;
        track.modRateCounter = 7;
        track.modStepCounter = 8;
        track.modAccumulator = 9;
        track.modCurrentDelta = 10;
        track.modEnabled = true;
        track.customModEnabled = true;
        track.detune = -2;
        track.modEnvId = 3;
        track.modEnvData = new byte[] {5, 6};
        track.modEnvPos = 1;
        track.modEnvMult = 2;
        track.modEnvCache = 3;
        track.modEnvHold = true;
        track.rawFreqMode = true;
        track.rawFrequency = 0x155;
        track.instrumentId = 6;
        track.noiseMode = true;
        track.fm3SpecialMode = true;
        track.psgNoiseParam = 7;
        track.rawPsgNoise = 0xE7;
        track.rawPsgNoiseKnown = true;
        track.decayOffset = 8;
        track.decayTimer = 9;
        track.envData = new byte[] {7, 8};
        track.envPos = 2;
        track.envValue = 3;
        track.envHold = true;
        track.envAtRest = true;
        track.fmVolEnvData = new byte[] {9, 10};
        track.fmVolEnvPos = 2;
        track.fmVolEnvValue = 3;
        track.fmVolEnvHold = true;
        track.fmVolEnvOpMask = 0x0F;
        track.forceRefresh = true;
        track.ssgEg[0] = 0x22;
        track.customSsgEgPresent = true;
        track.customSsgEgPayload[0] = 0x22;
        track.customSsgEgPayloadKnown = true;
        track.dacMuted = true;
        track.modStepInEffect = true;
        track.modStepChanged = true;
        track.modStepDelta = 0x55;
        track.modEnvStepInEffect = true;
        track.modEnvStepChanged = true;
        track.modEnvStepDelta = 0x66;
    }
}
