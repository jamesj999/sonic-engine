package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extra-life jingle's restore, driven through the live presentation path.
 *
 * <p>{@code cfFadeInToPrevious} stores {@code zFadeToPrevFlag} and the driver's
 * main loop acts on it (skdisasm Sound/Z80 Sound Driver.asm:3079-3082, with the
 * flag read at :659-666), so the whole restore belongs inside the driver's own
 * service. The engine splits it: the sequencer's coordination flag asks for the
 * restore, and the presentation layer brings the backed-up song back.
 *
 * <p>That ask has to go through the sequencer's injected restore sink. A
 * coordination flag runs inside the service, which for this path runs inside an
 * active presentation command batch, and the batch refuses a command submitted
 * into it. The refusal is logged rather than raised, so a handler that reaches
 * the global {@code AudioManager} instead loses the restore silently and the
 * music after the jingle is wrong. Sonic 1 and 2 reach it through the injected
 * sink already, in {@code SmpsSequencer.handleFadeIn}.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kOneUpRestoreRom {
    private Rom rom;

    @ParameterizedTest
    @ValueSource(ints = {0x62, 0x33})
    void requestsDuringOneUpAreDiscardedAndResumeAtRestoration(int request) {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        // Existing AIZ BK2: 1-up at 7700, first observed jump at 7733.
        // This is a stimulus offset, not a driver timing expectation.
        for (int frame = 0; frame < 33; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        boolean ringBefore = audio.captureLogicalSnapshot().ringLeft();
        audio.playSfx(request);
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(Sonic3kMusic.EXTRA_LIFE.id, playingMusicId(audio));
        assertNoSfx(audio);
        assertEquals(ringBefore, audio.captureLogicalSnapshot().ringLeft(),
                "discarded rings must not advance speaker alternation");
        for (int frame = 0; frame < 900
                && playingMusicId(audio) != Sonic3kMusic.AIZ1.id; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            assertNoSfx(audio);
        }
        assertEquals(Sonic3kMusic.AIZ1.id, playingMusicId(audio));
        audio.playSfx(request);
        audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().anyMatch(SmpsDriverSnapshot.SequencerEntry::sfx),
                "S3K permits SFX as soon as restoration starts, during the fade");
    }

    @Test
    void oneUpSuppressionSurvivesSnapshotRestoreAndOrdinaryMusicReleasesIt() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        audio.presentFrame(PresentationMode.FORWARD);
        var duringJingle = audio.captureLogicalSnapshot();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playSfx(0x62);
        audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().anyMatch(SmpsDriverSnapshot.SequencerEntry::sfx));
        audio.restoreLogicalSnapshot(duringJingle);
        audio.playSfx(0x62);
        audio.presentFrame(PresentationMode.FORWARD);
        assertNoSfx(audio);
    }

    private static void assertNoSfx(AudioManager audio) {
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().noneMatch(SmpsDriverSnapshot.SequencerEntry::sfx),
                "zUpdateMusic discards both SFX mailboxes while the 1-up plays");
    }

    @Test
    void oneUpWithoutSavedMusicReleasesSuppressionWhenItEnds() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        for (int frame = 0; frame < 900; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        audio.playSfx(0x62);
        audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().anyMatch(SmpsDriverSnapshot.SequencerEntry::sfx));
    }

    @Test
    void globalFadeStopReleasesOneUpSuppression() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.fadeOutMusic(1, 1);
        for (int frame = 0; frame < 16; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertEquals(-1, playingMusicId(audio));
        audio.playSfx(0x62);
        audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().anyMatch(SmpsDriverSnapshot.SequencerEntry::sfx));
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void theExtraLifeJingleRestoresTheLevelMusicWithoutARejectedCommand() {
        AudioManager audio = install();
        List<LogRecord> warnings = captureAudioManagerWarnings();

        audio.playMusic(Sonic3kMusic.AIZ1.id);
        for (int frame = 0; frame < 4; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertEquals(Sonic3kMusic.AIZ1.id, playingMusicId(audio),
                "the level music must be playing before the jingle");

        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        boolean restored = false;
        for (int frame = 0; frame < 900 && !restored; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            restored = playingMusicId(audio) == Sonic3kMusic.AIZ1.id;
        }

        assertTrue(restored,
                "the level music must come back when the jingle ends");
        assertFalse(warnings.stream().anyMatch(record ->
                        String.valueOf(record.getMessage())
                                .contains("shadow command mirror failed")),
                "the restore must not be lost to a rejected batch command: "
                        + warnings.stream().map(LogRecord::getMessage).toList());
        assertEquals(1, musicSequencerCount(audio),
                "the jingle's own sequencer must be gone once it has restored");
    }

    /**
     * {@code zFadeInToPrevious} does not merely bring the song back. Per FM
     * track it clears the overriding bit, lowers the volume by 40h and resends
     * the instrument, and it then arms a fade in of 40h steps
     * (skdisasm Sound/Z80 Sound Driver.asm:2744-2789). The PSG tracks keep the
     * overriding bit, which is what mutes them through the fade. So the level
     * music must come back attenuated and climb, not appear at full volume.
     */
    @Test
    void theRestoredMusicComesBackAttenuatedAndFadesIn() {
        AudioManager audio = install();
        List<String> writes = new ArrayList<>();
        audio.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(String.format("ym%d[%02X]=%02X", port, register, value));
            }

            @Override public void onPsgWrite(int value) {
                writes.add(String.format("psg[%02X]", value));
            }
        });

        audio.playMusic(Sonic3kMusic.AIZ1.id);
        for (int frame = 0; frame < 8; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        int settledVolume = fmVolumeOffset(audio);

        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        boolean restored = false;
        for (int frame = 0; frame < 900 && !restored; frame++) {
            writes.clear();
            audio.presentFrame(PresentationMode.FORWARD);
            restored = playingMusicId(audio) == Sonic3kMusic.AIZ1.id;
        }
        assertTrue(restored, "the level music must come back");

        int atRestore = fmVolumeOffset(audio);
        assertTrue(atRestore > settledVolume,
                "the restored song must come back attenuated, was " + atRestore
                        + " against a settled " + settledVolume);
        assertTrue(writes.stream().anyMatch(write -> write.startsWith("ym")),
                "the restore must resend the FM voices: " + writes);
        assertTrue(psgTrackOverridden(audio),
                "the PSG tracks stay overridden through the fade");

        int psgAtRestore = psgVolumeOffset(audio);
        int previous = atRestore;
        boolean climbed = false;
        for (int frame = 0; frame < 400 && !climbed; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            climbed = fmVolumeOffset(audio) < previous;
        }
        assertTrue(climbed,
                "the fade in must step the attenuation back down from "
                        + previous);

        // Run the fade to completion rather than stopping at the first step:
        // zDoMusicFadeIn only releases the PSG tracks when its timeout reaches
        // zero (Sound/Z80 Sound Driver.asm:2440-2452), so a test that stops
        // early cannot see whether they ever come back.
        for (int frame = 0; frame < 400; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }

        assertFalse(psgTrackOverridden(audio),
                "the PSG tracks must be released when the fade in completes,"
                        + " or the song plays on with no PSG at all");
        assertEquals(psgAtRestore, psgVolumeOffset(audio),
                "neither S3K fade stepper walks the PSG tracks, so their"
                        + " volume must not move with the fade");
        assertEquals(settledVolume, fmVolumeOffset(audio),
                "the FM tracks must arrive back at the volume they had"
                        + " before the jingle");
    }

    /**
     * A capture and restore round trip must not disturb a running fade.
     *
     * <p>The driver's fade counters are driver state like its lock table, and
     * the session's snapshot copy lists its arguments positionally. When it
     * dropped the fade counters the round trip zeroed them, and the fade in
     * stopped stepping: the attenuation stayed wherever the capture happened to
     * land, and the song never came back to its own volume.
     */
    @Test
    void aSnapshotRoundTripDuringTheFadeLeavesItRunning() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        for (int frame = 0; frame < 8; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        int settledVolume = fmVolumeOffset(audio);

        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        boolean restored = false;
        for (int frame = 0; frame < 900 && !restored; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            restored = playingMusicId(audio) == Sonic3kMusic.AIZ1.id;
        }
        assertTrue(restored, "the level music must come back");

        for (int frame = 0; frame < 20; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        int midFade = fmVolumeOffset(audio);
        assertTrue(midFade > settledVolume,
                "the fade must still be attenuating before the round trip");

        audio.restoreLogicalSnapshot(audio.captureLogicalSnapshot());

        for (int frame = 0; frame < 400; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertEquals(settledVolume, fmVolumeOffset(audio),
                "the fade must run to completion across a round trip,"
                        + " not freeze at the attenuation it was captured at");
    }

    /** Retail restore must survive retriggering throughout the release slice. */
    @ParameterizedTest
    @ValueSource(ints = {0x01, 0x02, 0x03, 0x04})
    void repeatedOneUpAndMidFadeRoundTripReleaseEveryPsgTrack(int musicId) {
        AudioManager audio = install();
        audio.playMusic(musicId);
        for (int frame = 0; frame < 8; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        for (int frame = 0; frame < 20; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertEquals(Sonic3kMusic.EXTRA_LIFE.id, playingMusicId(audio),
                "retrigger must exercise the live jingle, not a finished one");
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        for (int frame = 0; frame < 900 && playingMusicId(audio) != musicId; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertEquals(musicId, playingMusicId(audio),
                "retrigger must preserve the original displaced song");
        for (int frame = 0; frame < 20; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().fadeInTimeout() > 0,
                "snapshot must actually land during an active driver fade");
        audio.restoreLogicalSnapshot(audio.captureLogicalSnapshot());
        for (int frame = 0; frame < 400; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        SmpsDriverSnapshot restored = audio.shadowSmpsDriverSnapshotForTesting();
        assertEquals(0, restored.fadeInTimeout(), "driver fade must reach completion");
        assertEquals(1, musicSequencerCount(audio));
        assertEquals(musicId, playingMusicId(audio));
        int psgTracks = 0;
        for (SmpsDriverSnapshot.SequencerEntry entry : restored.sequencers()) {
            if (entry.sfx()) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() == SmpsSequencer.TrackType.PSG) {
                    psgTracks++;
                    assertFalse(track.overridden(),
                            "every PSG track must be released, not only the first one");
                }
            }
        }
        assertEquals(3, psgTracks, "all three PSG tracks must be exercised");
    }

    private static int psgVolumeOffset(AudioManager audio) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (entry.sfx()) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() == SmpsSequencer.TrackType.PSG) {
                    return track.volumeOffset();
                }
            }
        }
        return -1;
    }

    private static int fmVolumeOffset(AudioManager audio) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (entry.sfx()) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() == SmpsSequencer.TrackType.FM
                        && track.active()) {
                    return track.volumeOffset();
                }
            }
        }
        return -1;
    }

    private static boolean psgTrackOverridden(AudioManager audio) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (entry.sfx()) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() == SmpsSequencer.TrackType.PSG) {
                    return track.overridden();
                }
            }
        }
        return false;
    }

    private static int musicSequencerCount(AudioManager audio) {
        return (int) audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().filter(entry -> !entry.sfx()).count();
    }

    private static int playingMusicId(AudioManager audio) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (!entry.sfx()) {
                return entry.smpsData().getId();
            }
        }
        return -1;
    }

    private static List<LogRecord> captureAudioManagerWarnings() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(AudioManager.class.getName());
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record);
                }
            }

            @Override public void flush() { }

            @Override public void close() { }
        });
        return records;
    }

    private AudioManager install() {
        File file = RomTestUtils.ensureSonic3kRomAvailable();
        rom = new Rom();
        assertTrue(rom.open(file.getAbsolutePath()));
        Sonic3kAudioProfile profile = new Sonic3kAudioProfile();
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        return audio;
    }
}
