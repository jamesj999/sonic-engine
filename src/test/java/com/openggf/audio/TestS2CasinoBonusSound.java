package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.openggf.tests.RomTestUtils.ensureSonic2RomAvailable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestS2CasinoBonusSound {
    @ParameterizedTest
    @CsvSource({"fast,CASINO_BONUS,192", "accurate,CASINO_BONUS,192",
            "fast,SPLASH,170", "accurate,SPLASH,170"})
    void lowPrioritySoundIsAdmittedAfterOtherEffectsStop(String core, GameSound sound, int id) {
        TestEnvironment.resetAll();
        var file = ensureSonic2RomAvailable();
        assumeTrue(file != null);
        Rom rom = new Rom();
        assertTrue(rom.open(file.getAbsolutePath()));
        var config = GameServices.configuration();
        config.setSessionOverride(SonicConfiguration.AUDIO_FM_CORE, core);
        var audio = AudioManager.getInstance();
        var profile = new Sonic2AudioProfile();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        try {
            audio.playMusic(Sonic2Music.CASINO_NIGHT.id);
            present(audio, 60);
            audio.playSfx(GameSound.ROLLING);
            // BE's ROM program is rest 1 + note $25 + $2A repetitions of 2.
            // Let it finish, then prove C0 ($6F) and AA ($68) are no longer
            // rejected by the preceding effect's $70 priority.
            present(audio, 4);
            audio.playSfx(sound);
            present(audio, 4);
            assertFalse(audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                    .anyMatch(seq -> seq.sfx() && seq.source().id() == id),
                    "lower priority must still be rejected while Roll owns the latch");
            present(audio, 152);
            assertFalse(audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                    .anyMatch(seq -> seq.sfx()), "preceding Roll effect must have stopped");
            audio.playSfx(sound);
            present(audio, 4);
            assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                    .anyMatch(seq -> seq.sfx() && seq.source().id() == id
                            && seq.snapshot().tracks().stream().anyMatch(track -> track.active())),
                    sound + " must be playing over music on " + core);
        } finally {
            audio.resetState();
            config.clearSessionOverrides();
            rom.close();
        }
    }

    @Test
    void casinoBonusRomProgramProducesAudio() throws Exception {
        TestEnvironment.resetAll();
        var file = ensureSonic2RomAvailable();
        assumeTrue(file != null);
        Rom rom = new Rom();
        assertTrue(rom.open(file.getAbsolutePath()));
        var loader = new Sonic2SmpsLoader(rom);
        var data = loader.loadSfx(0xC0);
        assertNotNull(data);
        assertNotNull(data.getVoice(0), "C0 uses the shared BF/C0/C2 ROM voice");
        var driver = SmpsDriverTestAccess.create(44100);
        try {
            var seq = new SmpsSequencer(data, loader.loadDacData(), driver,
                    AudioManager.getInstance(), Sonic2SmpsSequencerConfig.CONFIG);
            driver.addSequencer(seq, true);
            short[] pcm = new short[44100];
            SmpsDriverTestAccess.read(driver, pcm);
            int peak = 0;
            for (short sample : pcm) peak = Math.max(peak, Math.abs((int) sample));
            assertTrue(peak > 100, "Casino Bonus PCM peak=" + peak);
        } finally {
            SmpsDriverTestAccess.close(driver);
            rom.close();
        }
    }

    private static void present(AudioManager audio, int frames) {
        for (int frame = 0; frame < frames; frame++) audio.presentFrame(PresentationMode.FORWARD);
    }
}
