package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMusicTempoRuntime {
    private Rom rom;

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void specialStageMusicStartsAtNormalTempoAfterSpeedShoes() {
        rom = new Rom();
        assertTrue(rom.open(RomTestUtils.ensureSonic3kRomAvailable().getAbsolutePath()));
        var audio = AudioManager.getInstance();
        var profile = new Sonic3kAudioProfile();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.setSpeedMultiplier(8);
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(8, musicTempo(audio));
        // Ordinary zPlayMusic_DoFade -> zStopAllSound clears zTempoSpeedup;
        // the gameplay call itself supplies no explicit speed reset.
        audio.playMusic(Sonic3kMusic.SPECIAL_STAGE.id);
        for (int frame = 0; frame < 30; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
            assertEquals(1, musicTempo(audio), "special-stage frame " + frame);
        }
        audio.setSpeedMultiplier(40);
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(40, musicTempo(audio), "stage-local acceleration remains live");
    }

    private static int musicTempo(AudioManager audio) {
        return audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(entry -> !entry.sfx()).findFirst().orElseThrow()
                .snapshot().speedMultiplier();
    }
}
