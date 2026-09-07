package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extra-life jingle's restore in the Sonic 1 and Sonic 2 drivers, driven
 * through the live presentation path ({@code AudioManager.presentFrame}).
 *
 * <p>{@code cfFadeInToPrevious} copies the backed-up driver RAM over the live
 * region and then, per playing track: marks it at rest, adds the fade depth to
 * its volume, and for an FM track that no SFX owns re-sends its whole voice
 * ({@code SetVoice}, s1.sounddriver.asm:2166-2222; {@code zSetVoiceMusic},
 * s2.sounddriver.asm:3084-3163). PSG tracks get a note-off instead. The resend
 * matters because every FM channel is still holding the jingle's instrument
 * when the level music comes back; without it the song resumes played on the
 * wrong voices until each track next changes instrument on its own.
 *
 * <p>The two games share one body and differ only in ROM, profile and ids, so
 * the cases live here and the per-ROM subclasses supply the fixture.
 */
abstract class OneUpRestoreLivePathSupport {
    private Rom rom;

    protected abstract File romFile();

    protected abstract GameAudioProfile profile();

    protected abstract int levelMusicId();

    protected abstract int extraLifeMusicId();

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void theLevelMusicResumesWhereTheJingleInterruptedIt() {
        AudioManager audio = install();
        audio.playMusic(levelMusicId());
        // Sonic 2's request path admits the song through the 68k mailbox and
        // the driver's load, so the first service may be a few frames out.
        SmpsSequencerSnapshot fresh = null;
        for (int frame = 0; frame < 60 && fresh == null; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            fresh = musicSnapshot(audio);
        }
        assertNotNull(fresh, "the level music must be playing");
        for (int frame = 0; frame < 120; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        SmpsSequencerSnapshot beforeJingle = musicSnapshot(audio);

        audio.playMusic(extraLifeMusicId());
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(extraLifeMusicId(), playingMusicId(audio),
                "the jingle must replace the level music");
        assertTrue(runUntilRestored(audio, new ArrayList<>()),
                "the level music must come back when the jingle ends");

        SmpsSequencerSnapshot restored = musicSnapshot(audio);
        assertEquals(1, musicSequencerCount(audio),
                "the jingle's own sequencer must be gone once it has restored");
        assertEquals(beforeJingle.normalTempo(), restored.normalTempo(),
                "the restored song keeps the tempo it was interrupted at");
        boolean advanced = false;
        for (SmpsTrackSnapshot track : restored.tracks()) {
            if (track.type() == SmpsSequencer.TrackType.DAC) {
                continue;
            }
            SmpsTrackSnapshot start = match(fresh, track);
            if (start != null && start.pos() != track.pos()) {
                advanced = true;
            }
        }
        assertTrue(advanced,
                "the song must resume from its saved position, not restart");
    }

    @Test
    void theRestoreRestsEveryPlayingTrackAndResendsTheFmVoices() {
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
        audio.playMusic(levelMusicId());
        for (int frame = 0; frame < 120; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        audio.playMusic(extraLifeMusicId());
        assertTrue(runUntilRestored(audio, writes),
                "the level music must come back when the jingle ends");

        SmpsSequencerSnapshot restored = musicSnapshot(audio);
        assertTrue(restored.fade().active() && !restored.fade().fadeOut(),
                "cfFadeInToPrevious arms the fade in");
        int fmSeen = 0;
        int psgSeen = 0;
        int resting = 0;
        for (SmpsTrackSnapshot track : restored.tracks()) {
            if (!track.active()) {
                continue;
            }
            if (track.resting()) {
                resting++;
            }
            switch (track.type()) {
                case DAC -> assertTrue(track.dacMuted(),
                        "the DAC track is muted through the fade"
                                + " (s1.sounddriver.asm:2183, s2.sounddriver.asm:3094)");
                case FM -> {
                    fmSeen++;
                    if (!track.overridden()) {
                        int port = track.channelId() < 3 ? 0 : 1;
                        String algorithmRegister = String.format(
                                "ym%d[B%X]=", port, track.channelId() % 3);
                        assertTrue(writes.stream().anyMatch(
                                write -> write.startsWith(algorithmRegister)),
                                "the restore must re-send FM" + track.channelId()
                                        + "'s voice (SetVoice, s1.sounddriver.asm:2200;"
                                        + " zSetVoiceMusic, s2.sounddriver.asm:3118),"
                                        + " writes: " + writes);
                    }
                }
                case PSG -> {
                    psgSeen++;
                    // The third PSG track keys off whichever channel it is
                    // driving, the tone channel or the noise channel.
                    String noteOff = String.format(
                            "psg[%02X]", 0x9F | (track.channelId() << 5));
                    assertTrue(writes.contains(noteOff)
                                    || (track.channelId() == 2 && writes.contains("psg[FF]")),
                            "the restore must note-off PSG" + track.channelId()
                                    + " (PSGNoteOff, s1.sounddriver.asm:2212;"
                                    + " zPSGNoteOff, s2.sounddriver.asm:3132),"
                                    + " writes: " + writes);
                }
            }
        }
        assertNotEquals(0, fmSeen, "the level music must have playing FM tracks");
        assertNotEquals(0, psgSeen, "the level music must have playing PSG tracks");
        // Every playing track is rested by the restore (s1.sounddriver.asm:2193,
        // :2211; s2.sounddriver.asm:3107, :3131), and a track leaves rest as
        // soon as it reads its next note, which some do in this same frame.
        // The unit test on the restore body pins the per-track bit; here the
        // evidence that the live path ran it is that the rest bit survived
        // the frame on at least one track.
        assertNotEquals(0, resting,
                "the restore must rest the playing tracks on the live path");
    }

    @Test
    void theRestoredMusicComesBackAttenuatedAndFadesBackToItsVolume() {
        AudioManager audio = install();
        audio.playMusic(levelMusicId());
        for (int frame = 0; frame < 120; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        audio.playMusic(extraLifeMusicId());
        assertTrue(runUntilRestored(audio, new ArrayList<>()),
                "the level music must come back when the jingle ends");

        int atRestore = minimumActiveFmVolumeOffset(audio);
        // 28h steps were added and at most the first has been taken back.
        assertTrue(atRestore >= 0x27,
                "the restored song must come back attenuated by 28h"
                        + " (s1.sounddriver.asm:2185, s2.sounddriver.asm:3098),"
                        + " was " + atRestore);

        int previous = atRestore;
        boolean stepped = false;
        for (int frame = 0; frame < 30 && !stepped; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            stepped = minimumActiveFmVolumeOffset(audio) < previous;
        }
        assertTrue(stepped, "the fade in must step the attenuation back down");

        for (int frame = 0; frame < 400; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }
        SmpsSequencerSnapshot settled = musicSnapshot(audio);
        assertFalse(settled.fade().active(),
                "the fade in must run to completion");
        for (SmpsTrackSnapshot track : settled.tracks()) {
            if (track.type() == SmpsSequencer.TrackType.DAC) {
                assertFalse(track.dacMuted(),
                        "the DAC track is released when the fade in completes");
            }
        }
        assertTrue(minimumActiveFmVolumeOffset(audio) < 0x27,
                "the attenuation must be gone once the fade in completes");
    }

    private boolean runUntilRestored(AudioManager audio, List<String> writes) {
        for (int frame = 0; frame < 900; frame++) {
            writes.clear();
            audio.presentFrame(PresentationMode.FORWARD);
            if (playingMusicId(audio) == levelMusicId()) {
                return true;
            }
        }
        return false;
    }

    private static int minimumActiveFmVolumeOffset(AudioManager audio) {
        SmpsSequencerSnapshot music = musicSnapshot(audio);
        int minimum = Integer.MAX_VALUE;
        for (SmpsTrackSnapshot track : music.tracks()) {
            if (track.type() == SmpsSequencer.TrackType.FM && track.active()) {
                minimum = Math.min(minimum, track.volumeOffset());
            }
        }
        return minimum;
    }

    private static SmpsTrackSnapshot match(SmpsSequencerSnapshot snapshot,
            SmpsTrackSnapshot track) {
        for (SmpsTrackSnapshot candidate : snapshot.tracks()) {
            if (candidate.type() == track.type()
                    && candidate.channelId() == track.channelId()) {
                return candidate;
            }
        }
        return null;
    }

    private static SmpsSequencerSnapshot musicSnapshot(AudioManager audio) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (!entry.sfx()) {
                return entry.snapshot();
            }
        }
        return null;
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

    private AudioManager install() {
        rom = new Rom();
        assertTrue(rom.open(romFile().getAbsolutePath()));
        GameAudioProfile profile = profile();
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        return audio;
    }
}
