package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonic 3&amp;K SFX lifecycle through the runtime request path.
 *
 * <p>The AIZ1 intro driver oracle covers Collapse admission. These runtime
 * tests additionally exercise one SFX taking a channel from another, a
 * wholesale teardown, and multi-track SFX whose tracks end at different
 * times. Each test names the ROM routine owning the asserted behavior.
 *
 * <p>Channel numbering: {@code FM4} is linear channel 4, so its key on/off
 * selector on register 28h is {@code (4 % 3) + 4 = 5}, and its per-operator
 * registers live on port 1 at offset 1. {@code PSG2} silences with a latched
 * volume of {@code DFh}.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSfxLifecycleRom {
    /** Key off for FM4: register 28h, no slot bits, channel selector 5. */
    private static final String FM4_KEY_OFF = "ym0[28]=05";
    /** Latched PSG2 volume of 0Fh, the channel's silence level. */
    private static final String PSG2_SILENCE = "psg[DF]";
    /** zFMClearSSGEGOps writes 90h and the three operator registers above. */
    private static final List<String> FM4_SSG_EG_CLEAR = List.of(
            "ym1[91]=00", "ym1[95]=00", "ym1[99]=00", "ym1[9D]=00");

    private final List<String> writes = new ArrayList<>();
    private Rom rom;

    /**
     * Collapse declares FM3, FM4, FM5, then PSG3. The retail
     * zGetSFXChannelPointers PSG branch writes FF even though the preceding
     * stale-IX silence sees FM5 and writes nothing (driver:2131-2136).
     */
    @Test
    void collapseAdmissionSilencesNoiseAfterTheThreeFmInitializations() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        for (int warm = 0; warm < 4; warm++) {
            service(audio);
        }
        observe(audio);
        assertTrue(audio.playSfx(Sonic3kSfx.COLLAPSE.id));
        service(audio);

        List<String> admission = List.of(
                "ym0[28]=02", "ym0[92]=00", "ym0[96]=00", "ym0[9A]=00", "ym0[9E]=00",
                "ym0[28]=04", "ym1[90]=00", "ym1[94]=00", "ym1[98]=00", "ym1[9C]=00",
                "ym0[28]=05", "ym1[91]=00", "ym1[95]=00", "ym1[99]=00", "ym1[9D]=00",
                "psg[FF]");
        assertTrue(writes.size() >= admission.size(), () -> "admission writes: " + writes);
        assertEquals(admission, writes.subList(0, admission.size()),
                "noise silence belongs after the preceding FM headers and before the music walk");
    }

    /**
     * Skid's shipped headers are PSG2 then PSG1. On the second pass IX still
     * names the initialized PSG2 header, so zGetSFXChannelPointers emits BF
     * before its unconditional FF (Sound/Z80 Sound Driver.asm:1997-2103,
     * 2109-2165; shipped fix_sndbugs=0).
     */
    @Test
    void skidAdmissionUsesThePreviousRomHeaderBeforeCurrentPsgSilence() {
        AudioManager audio = install();
        observe(audio);

        assertTrue(audio.playSfx(Sonic3kSfx.SKID.id));
        service(audio);

        assertTrue(writes.size() >= 3, () -> "Skid admission writes: " + writes);
        assertEquals(List.of("psg[FF]", "psg[BF]", "psg[FF]"),
                writes.subList(0, 3));
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    /**
     * {@code zSFXTrackInitLoop} keys off each track of an incoming SFX and
     * clears its SSG-EG operators while the sound is still being loaded
     * (skdisasm Sound/Z80 Sound Driver.asm:2092-2103). Nothing in that loop
     * asks who currently holds the channel, so the key off is sent even when
     * the channel is being taken from another SFX that is still sounding on
     * it. That is the case that matters audibly: without the key off the
     * outgoing SFX's note carries on under the new one's attack.
     */
    @Test
    void sfxTakingAnFmChannelFromAnotherSfxKeysOffTheOutgoingNote() {
        AudioManager audio = install();
        observe(audio);
        assertTrue(audio.playSfx(Sonic3kSfx.SPLASH.id));
        for (int frame = 0; frame < 3; frame++) {
            service(audio);
        }
        assertTrue(writes.contains("ym1[A1]=AB"),
                "SPLASH must be sounding on FM4 before the takeover");

        assertTrue(audio.playSfx(Sonic3kSfx.GRAB.id));
        service(audio);

        int keyOff = writes.indexOf(FM4_KEY_OFF);
        assertTrue(keyOff >= 0,
                "GRAB taking FM4 from SPLASH must key the channel off: "
                        + writes);
        for (String clear : FM4_SSG_EG_CLEAR) {
            assertTrue(writes.indexOf(clear) > keyOff,
                    clear + " must follow the key off: " + writes);
        }
    }

    /**
     * Tearing an SFX down wholesale never runs its {@code cfStopTrack}, whose
     * first act after clearing the playing flag is {@code zKeyOffIfActive}
     * for the track's channel (skdisasm Sound/Z80 Sound Driver.asm:3443-3449).
     * The driver owes that key off on the sequencer's behalf, and the PSG
     * track owes the latched silence {@code zStopPSGTrack} sends (:4230).
     */
    @Test
    void stoppingAllSfxKeysOffFmAndSilencesPsgBeforeReleasingTheChannels() {
        AudioManager audio = install();
        observe(audio);
        assertTrue(audio.playSfx(Sonic3kSfx.SPLASH.id));
        service(audio);
        service(audio);

        audio.stopAllSfx();
        service(audio);

        assertTrue(writes.contains(FM4_KEY_OFF),
                "stopping all SFX must key FM4 off: " + writes);
        assertTrue(writes.contains(PSG2_SILENCE),
                "stopping all SFX must silence PSG2: " + writes);
        assertEquals(0, sfxSequencerCount(audio),
                "no SFX sequencer may survive the teardown");
    }

    /**
     * A single track of a multi-track SFX reaching its end runs
     * {@code cfStopTrack}, which keys the channel off and then clears the
     * SFX-overriding bit on the music track it was covering
     * (skdisasm Sound/Z80 Sound Driver.asm:3443-3468). It is a per-track
     * routine: the other tracks of the same SFX keep playing and keep their
     * channels. SPLASH is the case in hand, with FM4 ending well before PSG2.
     */
    @Test
    void eachTrackOfAMultiTrackSfxHandsItsChannelBackAsItEnds() {
        AudioManager audio = install();
        observe(audio);
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        for (int warm = 0; warm < 4; warm++) {
            service(audio);
        }
        assertTrue(audio.playSfx(Sonic3kSfx.SPLASH.id));

        int fmEnded = -1;
        for (int frame = 0; frame < 120 && fmEnded < 0; frame++) {
            service(audio);
            if (!sfxTrackActive(audio, SmpsSequencer.TrackType.FM, 4)) {
                fmEnded = frame;
            }
        }

        assertTrue(fmEnded > 0, "SPLASH's FM4 track must end during playback");
        assertTrue(writes.contains(FM4_KEY_OFF),
                "the ending FM4 track must key its channel off: " + writes);
        assertFalse(musicTrackOverridden(
                        audio, SmpsSequencer.TrackType.FM, 4),
                "FM4 must return to the music track as its SFX track ends");
        assertTrue(sfxTrackActive(audio, SmpsSequencer.TrackType.PSG, 2),
                "SPLASH's PSG2 track must still be playing");
        assertTrue(musicTrackOverridden(
                        audio, SmpsSequencer.TrackType.PSG, 2),
                "PSG2 must stay with the SFX while its track runs");
    }

    /**
     * {@code zUpdateEverything} walks the SFX tracks before it fills the
     * sound queue (skdisasm Sound/Z80 Sound Driver.asm:653-655 against the
     * {@code zFillSoundQueue} call at :698), so an SFX admitted during one
     * service is first walked by {@code zUpdateSFXTracks} (:727-739) on the
     * service after it. The driver must not judge such an SFX finished in
     * between: a sound whose tracks are short enough to complete in a single
     * walk still has to be walked once and reach the chip.
     *
     * <p>The insta-shield is the sound in hand, a single PSG2 track.
     */
    @Test
    void anSfxIsWalkedOnceBeforeItCanBeJudgedFinished() {
        AudioManager audio = install();
        observe(audio);
        assertTrue(audio.playSfx(Sonic3kSfx.INSTA_SHIELD.id));

        service(audio);
        assertEquals(1, sfxSequencerCount(audio),
                "the admitted SFX must survive the service that admitted it");
        assertTrue(sfxTrackActive(audio, SmpsSequencer.TrackType.PSG, 2),
                "its PSG2 track must still be live before its first walk");
        assertEquals(List.of("psg[FF]"), writes,
                "the admitting service emits only its noise silence; "
                        + "the SFX walk precedes the queue fill");

        service(audio);
        assertTrue(writes.stream().anyMatch(write -> write.startsWith("psg[")),
                "the first walk after admission must reach the PSG: "
                        + writes);
    }

    private void service(AudioManager audio) {
        writes.clear();
        audio.presentFrame(PresentationMode.FORWARD);
    }

    private void observe(AudioManager audio) {
        audio.setChipWriteObserver(new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                writes.add(String.format(
                        "ym%d[%02X]=%02X", port, register, value));
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(String.format("psg[%02X]", value));
            }
        });
    }

    private int sfxSequencerCount(AudioManager audio) {
        return (int) audio.shadowSmpsDriverSnapshotForTesting().sequencers()
                .stream().filter(SmpsDriverSnapshot.SequencerEntry::sfx)
                .count();
    }

    private boolean sfxTrackActive(
            AudioManager audio, SmpsSequencer.TrackType type, int channel) {
        return trackMatches(audio, true, type, channel,
                SmpsTrackSnapshot::active);
    }

    private boolean musicTrackOverridden(
            AudioManager audio, SmpsSequencer.TrackType type, int channel) {
        return trackMatches(audio, false, type, channel,
                SmpsTrackSnapshot::overridden);
    }

    private boolean trackMatches(
            AudioManager audio,
            boolean sfx,
            SmpsSequencer.TrackType type,
            int channel,
            java.util.function.Predicate<SmpsTrackSnapshot> test) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : audio.shadowSmpsDriverSnapshotForTesting().sequencers()) {
            if (entry.sfx() != sfx) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() == type && track.channelId() == channel) {
                    return test.test(track);
                }
            }
        }
        return false;
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
