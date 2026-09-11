package com.openggf.audio.smps;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-derived main-tempo cadence vectors from the sound-driver behaviour specs
 * (docs/architecture/designs/audio/2026-08-30-s{1,2,3k}-sound-driver-behaviour-spec.md).
 * Every expected sequence below is computed from the cited ROM routine, not
 * measured from the engine: the ROM delay frame pre-increments every music
 * slot's DurationTimeout and still runs the whole track walk (S1 TempoWait
 * SD:1549-1561; S2 TempoWait sd:596-619; S3K TempoWait D:2607-2621).
 */
class TestSmpsSequencerCadence {

    private static final double ONE_SAMPLE_PER_FRAME = 60.0;

    /** Minimal music program: raw SMPS bytes with an explicit header tempo. */
    static final class ProgramMusicData extends AbstractSmpsData {
        ProgramMusicData(int tempo, byte[] program) {
            super(program, 0);
            this.tempo = tempo;
        }

        @Override
        protected void parseHeader() {
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return new byte[25];
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return new byte[0];
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

    private static SmpsSequencer newSequencer(SmpsSequencerConfig config, int tempo,
            byte[] program) {
        SmpsSequencer sequencer = new SmpsSequencer(
                new ProgramMusicData(tempo, program),
                AudioTestFixtures.EMPTY_DAC,
                new VirtualSynthesizer(),
                AudioManager.getInstance(),
                config);
        sequencer.setSampleRate(ONE_SAMPLE_PER_FRAME); // 1 sample per NTSC frame
        return sequencer;
    }

    /** Runs one driver update: the first via the primer, the rest via advanceBatch. */
    private static void runUpdate(SmpsSequencer sequencer, int updateIndex) {
        if (updateIndex == 0) {
            sequencer.advanceSamples(0);
        } else {
            sequencer.advanceBatch(1);
        }
    }

    private static Track addFmTrack(SmpsSequencer sequencer) {
        Track track = new Track(0, TrackType.FM, 0);
        sequencer.addTrack(track);
        return track;
    }

    // -----------------------------------------------------------------------
    // S2 spec §3.4 TV1 (EHZ tempo phase) + §3.1 (seed sd:1820-1822, first
    // TempoWait sd:545-551, delay = no carry with all-slot pre-increment)
    // -----------------------------------------------------------------------
    @Test
    void s2SeededAccumulatorRunsTempoWaitFromTheFirstUpdate() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .tempoOnFirstTick(true)
                .build();
        // note, dur 2, note, dur 2, ... — enough bytes for three notes
        byte[] program = {(byte) 0x81, 0x02, (byte) 0x82, 0x02, (byte) 0x83, 0x02};
        SmpsSequencer sequencer = newSequencer(config, 0x9E, program);
        Track track = addFmTrack(sequencer);

        // Hand-derivation from sd:596-619 with seed 9Eh (spec §3.4 TV1):
        // U1: 9E+9E=13C carry → normal, acc 3C; U2: 3C+9E=DA no carry → delay;
        // U3: DA+9E=178 carry → 78; U4: 78+9E=116 carry → 16; U5: 16+9E=B4 delay.
        int[] expectedAccumulator = {0x3C, 0xDA, 0x78, 0x16, 0xB4};
        // Note grid: U1 parses note 1 (dur 2); U2 delay holds it (pre-increment
        // cancels the decrement, but the walk still ran); U3 decrements; U4
        // expires and parses note 2; U5 delay holds note 2.
        int[] expectedDuration = {2, 2, 1, 2, 2};
        int[] expectedPos = {2, 2, 2, 4, 4};

        for (int update = 0; update < 5; update++) {
            runUpdate(sequencer, update);
            SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
            assertEquals(expectedAccumulator[update], snapshot.tempoAccumulator(),
                    "tempo accumulator after update " + (update + 1));
            assertEquals(expectedDuration[update], track.duration,
                    "duration after update " + (update + 1));
            assertEquals(expectedPos[update], track.pos,
                    "stream position after update " + (update + 1));
        }
    }

    // -----------------------------------------------------------------------
    // S1 spec §3.1/§3.4 TV3.1+TV3.3: countdown holds at U3/U6/U9/U12; TempoWait
    // SD:1549-1561 increments every music slot without testing the playing bit.
    // -----------------------------------------------------------------------
    @Test
    void s1TimeoutExtensionHitsStoppedTracksAndStretchesTheNoteGrid() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .tempoOnFirstTick(true)
                .build();
        byte[] program = {(byte) 0x81, 0x08};
        SmpsSequencer sequencer = newSequencer(config, 3, program);
        Track playing = addFmTrack(sequencer);
        Track stopped = new Track(0, TrackType.FM, 1);
        stopped.active = false;
        stopped.duration = 5;
        sequencer.addTrack(stopped);

        // TV3.3: raw duration 8, divider 1, tempo 3 → holds at U3/U6/U9/U12,
        // duration reaches 0 on U13 (12 passes for 8 raw frames).
        int[] expectedDuration = {8, 7, 7, 6, 5, 5, 4, 3, 3, 2, 1, 1};
        for (int update = 0; update < 12; update++) {
            runUpdate(sequencer, update);
            assertEquals(expectedDuration[update], playing.duration,
                    "duration after pass " + (update + 1));
        }
        // The stopped slot was extended on every hold (U3, U6, U9, U12) even
        // though it is not playing — the ROM loop tests no playing bit.
        assertEquals(9, stopped.duration, "stopped slot after four holds");

        runUpdate(sequencer, 12); // U13: duration expires; no more stream data
        assertFalse(playing.active, "note grid reaches 0 exactly on pass 13");
    }

    // -----------------------------------------------------------------------
    // S3K spec §3(d) TV3.1: tempo 20h — accumulator (n+1)·20h mod 256, first
    // carry on update 7; the carry update holds durations but the walk runs.
    // -----------------------------------------------------------------------
    @Test
    void s3kCarryDelaysNoteExpiryWhileTheWalkKeepsRunning() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .build();
        byte[] program = {(byte) 0x81, 0x7F};
        SmpsSequencer sequencer = newSequencer(config, 0x20, program);
        Track track = addFmTrack(sequencer);

        int[] expectedAccumulator = {0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0, 0x00, 0x20};
        int[] expectedDuration = {0x7F, 0x7E, 0x7D, 0x7C, 0x7B, 0x7A, 0x7A, 0x79};
        for (int update = 0; update < 8; update++) {
            runUpdate(sequencer, update);
            SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
            assertEquals(expectedAccumulator[update], snapshot.tempoAccumulator(),
                    "zTempoAccumulator after update " + (update + 1));
            assertEquals(expectedDuration[update], track.duration,
                    "duration after update " + (update + 1));
        }
    }

    // -----------------------------------------------------------------------
    // S3K spec §3(d) TV3.4: tempo 0 is the fastest setting — no carry ever, a
    // note is served on every update.
    // -----------------------------------------------------------------------
    @Test
    void s3kZeroTempoNeverDelays() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .build();
        byte[] program = {(byte) 0x81, 0x7F};
        SmpsSequencer sequencer = newSequencer(config, 0, program);
        Track track = addFmTrack(sequencer);

        for (int update = 0; update < 10; update++) {
            runUpdate(sequencer, update);
        }
        assertEquals(0x7F - 9, track.duration, "one decrement per update, no holds");
        assertEquals(0, sequencer.captureSnapshot().tempoAccumulator());
    }

    @Test
    void s2PalRunsSixMusicUpdatesPerFiveFramesButServicesSfxOncePerFrame() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .tempoOnFirstTick(true)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.EXTRA_MUSIC)
                .build();
        SmpsDriver driver = new SmpsDriver(50.0);
        Track music = addPrimedDriverTrack(driver, config, 0xFF, false);
        Track sfx = addPrimedDriverTrack(driver, config, 0xFF, true);
        driver.setRegion(SmpsSequencer.Region.PAL);

        for (int frame = 0; frame < 5; frame++) {
            driver.serviceOuterFrame();
        }
        assertEquals(5, driver.captureSnapshot().palUpdateCounter(),
                "the fifth S2 PAL frame reloads the shared counter");

        assertEquals(0x40 - 6, music.duration,
                "sd:445-452 repeats only zUpdateMusic on the fifth PAL V-int");
        assertEquals(0x40 - 5, sfx.duration,
                "the S2 SFX pass remains single-service on PAL");
    }

    @Test
    void palDoesNotRewriteAnyGamesCurrentTempo() {
        for (SmpsSequencerConfig.TempoMode tempoMode
                : SmpsSequencerConfig.TempoMode.values()) {
            SmpsSequencer sequencer = newSequencer(
                    new SmpsSequencerConfig.Builder()
                            .tempoMode(tempoMode)
                            .tempoOnFirstTick(true)
                            .build(),
                    0x80, new byte[] {(byte) 0x81, 0x08});
            sequencer.setRegion(SmpsSequencer.Region.PAL);
            assertEquals(0x80, sequencer.captureSnapshot().tempoWeight(),
                    tempoMode + " keeps the ROM CurrentTempo byte on PAL");
        }
    }

    @Test
    void s3kPalRunsSevenWholeUpdatesPerSixFramesInSfxThenMusicOrder() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.EXTRA_FULL)
                .build();
        SmpsDriver driver = new SmpsDriver(50.0);
        Track music = addPrimedDriverTrack(driver, config, 0, false);
        Track sfx = addPrimedDriverTrack(driver, config, 0, true);
        driver.setRegion(SmpsSequencer.Region.PAL);
        for (int frame = 0; frame < 5; frame++) {
            driver.serviceOuterFrame();
        }
        SmpsDriverSnapshot beforeRepeat = driver.captureSnapshot();
        assertEquals(0, beforeRepeat.palUpdateCounter(),
                "five S3K PAL frames leave the shared counter at zero");

        List<Boolean> sixthFrameOrder = new ArrayList<>();
        driver.setServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onServiceBegin(ServiceEvent event) {
                if (event.kind() == ServiceKind.SEQUENCER_TICK) {
                    sixthFrameOrder.add(event.sequencer().sfx());
                }
            }
        });
        driver.serviceOuterFrame();

        assertEquals(0x40 - 7, music.duration,
                "D:482-499 repeats the complete update on the sixth PAL V-int");
        assertEquals(0x40 - 7, sfx.duration,
                "the repeated S3K update includes the SFX pass");
        assertEquals(List.of(true, false, true, false), sixthFrameOrder,
                "each S3K full update keeps the ROM's SFX-before-music order");
        assertEquals(4, driver.captureSnapshot().palUpdateCounter(),
                "the repeat reloads 5 and the same-frame re-check decrements to 4");

        driver.restoreSnapshot(beforeRepeat);
        assertEquals(0, driver.captureSnapshot().palUpdateCounter(),
                "rewind restores the shared PAL phase");
        driver.serviceOuterFrame();
        assertEquals(4, driver.captureSnapshot().palUpdateCounter(),
                "the restored zero phase repeats on the same next frame");
    }

    @Test
    void s3kSpeedShoesRunFiveMusicUpdatesPerFourFrames() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.EXTRA_FULL)
                .build();
        SmpsDriver driver = new SmpsDriver(60.0);
        SmpsSequencer musicSequencer = addPrimedDriverSequencer(
                driver, config, 0, false);
        Track music = musicSequencer.getTracks().getFirst();
        Track sfx = addPrimedDriverTrack(driver, config, 0, true);
        musicSequencer.setSpeedMultiplier(8);

        int[] expectedMusicUpdates = {2, 1, 1, 1, 2, 1, 1, 1};
        int[] expectedTimeout = {6, 4, 2, 0, 6, 4, 2, 0};
        int previousMusicDuration = 0x40;
        int previousSfxDuration = 0x40;
        for (int frame = 0; frame < expectedMusicUpdates.length; frame++) {
            driver.serviceOuterFrame();
            assertEquals(expectedMusicUpdates[frame],
                    previousMusicDuration - music.duration,
                    "music services in speed-shoes frame " + (frame + 1));
            assertEquals(1, previousSfxDuration - sfx.duration,
                    "SFX remains one pass per NTSC frame " + (frame + 1));
            assertEquals(expectedTimeout[frame],
                    musicSequencer.captureSnapshot().speedupTimeout(),
                    "zSpeedupTimeout after frame " + (frame + 1));
            previousMusicDuration = music.duration;
            previousSfxDuration = sfx.duration;
        }
    }

    private static Track addPrimedDriverTrack(
            SmpsDriver driver, SmpsSequencerConfig config, int tempo,
            boolean sfx) {
        return addPrimedDriverSequencer(driver, config, tempo, sfx)
                .getTracks().getFirst();
    }

    private static SmpsSequencer addPrimedDriverSequencer(
            SmpsDriver driver, SmpsSequencerConfig config, int tempo,
            boolean sfx) {
        SmpsSequencer sequencer = new SmpsSequencer(
                new ProgramMusicData(tempo, new byte[] {(byte) 0x81, 0x40}),
                AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), config);
        Track track = addFmTrack(sequencer);
        sequencer.setSfxMode(sfx);
        sequencer.advanceSamples(0);
        driver.addSequencer(sequencer, sfx);
        return sequencer;
    }

    // -----------------------------------------------------------------------
    // CD CAD-03: S1 cfSetTempo (SD:2256-2258) resets the countdown; S2
    // (sd:3207-3209) and S3K (D:3861-3863) replace CurrentTempo only.
    // -----------------------------------------------------------------------
    @Test
    void eaTempoSetKeepsAccumulatorPhaseOnZ80DriversAndResetsItOnS1() {
        SmpsSequencerConfig z80Config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .tempoOnFirstTick(true)
                .build();
        byte[] z80Program = {(byte) 0xEA, 0x50, (byte) 0x81, 0x02};
        SmpsSequencer z80Sequencer = newSequencer(z80Config, 0x9E, z80Program);
        addFmTrack(z80Sequencer);
        runUpdate(z80Sequencer, 0);
        SmpsSequencerSnapshot snapshot = z80Sequencer.captureSnapshot();
        // TempoWait ran on the seed (9E+9E → carry → 3C) before the EA parsed;
        // the accumulated phase survives the tempo change.
        assertEquals(0x3C, snapshot.tempoAccumulator(), "phase kept across EA");
        assertEquals(0x50, snapshot.normalTempo(), "EA replaced CurrentTempo");
        runUpdate(z80Sequencer, 1);
        assertEquals(0x8C, z80Sequencer.captureSnapshot().tempoAccumulator(),
                "next TempoWait adds the new tempo to the kept phase");

        SmpsSequencerConfig s1Config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .tempoOnFirstTick(true)
                .build();
        byte[] s1Program = {(byte) 0xEA, 0x05, (byte) 0x81, 0x08};
        SmpsSequencer s1Sequencer = newSequencer(s1Config, 3, s1Program);
        addFmTrack(s1Sequencer);
        runUpdate(s1Sequencer, 0);
        assertEquals(5, s1Sequencer.captureSnapshot().tempoAccumulator(),
                "S1 EA resets the countdown to the new tempo");
        assertTrue(s1Sequencer.captureSnapshot().tempoWeight() == 5,
                "S1 EA installs the new tempo");
    }
}
