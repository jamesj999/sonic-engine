package com.openggf.audio;

import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1-up jingle is the only music that interrupts the current song and
 * restores it, and the sound driver saves exactly one song for it.
 *
 * <p>The ROM owns a single save slot: S1's {@code Sound_PlayBGM} backs up
 * {@code v_1up_ram} and sets {@code f_1up_playing}; S3K's {@code zPlayMusic}
 * copies {@code zTracksStart} to {@code zTracksSaveStart} and sets
 * {@code zFadeToPrevFlag}. Any other music request abandons that save — S1 by
 * {@code clr.b f_1up_playing}, S3K by {@code zStopAllSound} zeroing the whole
 * backup area. Invincibility and Super are ordinary music that restore the
 * level music by re-issuing it, not by unwinding a saved song.
 *
 * <p>The claims are made against the presentation registry the mixer actually
 * renders: {@code activeMusic} is the voice being heard and the override stack
 * degenerates to that single save slot.
 */
class TestMusicOverrideRestore {

    private static final int SAMPLE_RATE = 48_000;
    private static final int LEVEL_MUSIC = 0x81;
    private static final int SUPER_MUSIC = 0x86;
    private static final int INVINCIBILITY_MUSIC = 0x87;
    private static final int EXTRA_LIFE_MUSIC = 0x88;

    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.endCaptureMode();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new OverrideProfile());
        // Fallback-WAV music: a durable looping sample voice, so a restored
        // song is still a live voice rather than one swept as complete.
        for (int musicId : new int[] {LEVEL_MUSIC, SUPER_MUSIC,
                INVINCIBILITY_MUSIC, EXTRA_LIFE_MUSIC}) {
            AudioManagerTestDiagnostics.registerFallbackSfxAsset(audio,
                    "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav",
                    new byte[SAMPLE_RATE], SAMPLE_RATE);
        }
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    /**
     * The jingle's "fade in to previous song" (SMPS E4) arrives as
     * {@code restoreMusic()}. With nothing pushed there is nothing to fade back
     * in and the rest of the act plays in silence.
     */
    @Test
    void extraLifeJingleRestoresZoneMusic() {
        play(LEVEL_MUSIC);
        assertNothingSaved();

        play(EXTRA_LIFE_MUSIC);
        assertEquals(EXTRA_LIFE_MUSIC, activeMusicId());
        assertSaved(LEVEL_MUSIC);

        restore();
        assertEquals(LEVEL_MUSIC, activeMusicId());
        assertNothingSaved();
    }

    /**
     * Invincibility and Super are ordinary music, not overrides. The ROM plays
     * them through the same {@code zPlayMusic_DoFade} path as any other song
     * and restores the level music by re-issuing it when the power-up ends
     * ({@code Sonic_ChkInvin}), so nothing is saved here.
     */
    @Test
    void invincibilityAndSuperReplaceTheZoneMusicWithoutSavingIt() {
        play(LEVEL_MUSIC);

        play(INVINCIBILITY_MUSIC);
        assertEquals(INVINCIBILITY_MUSIC, activeMusicId());
        assertNothingSaved();

        play(SUPER_MUSIC);
        assertEquals(SUPER_MUSIC, activeMusicId());
        assertNothingSaved();
    }

    /**
     * Any other music arriving while the jingle plays abandons the save.
     *
     * <p>ROM: a non-1-up request reaches {@code zPlayMusic_DoFade ->
     * zStopAllSound}, which zeroes {@code zFadeToPrevFlag} and the whole
     * {@code zTracksSaveStart} backup (S1 clears {@code f_1up_playing} the same
     * way). A later E4 must therefore leave the new song alone rather than
     * reinstating a jingle that no longer exists — the frozen, exhausted voice
     * that used to be reinstated here is what fell silent.
     */
    @Test
    void musicStartedDuringTheJingleDiscardsTheSavedSong() {
        play(LEVEL_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        assertSaved(LEVEL_MUSIC);

        play(SUPER_MUSIC);
        assertEquals(SUPER_MUSIC, activeMusicId());
        assertNothingSaved();

        // The jingle is gone, so its E4 can never arrive; if one does, Super
        // music keeps playing rather than the act falling silent.
        restore();
        assertEquals(SUPER_MUSIC, activeMusicId());
    }

    /**
     * Re-collecting a 1-up while the jingle plays reloads it without saving a
     * second time, so the zone music underneath survives.
     *
     * <p>ROM: {@code zPlayMusic} jumps straight to {@code zBGMLoad} when
     * {@code zFadeToPrevFlag} already holds the 1-up id.
     */
    @Test
    void retriggeredJingleKeepsTheOriginalSavedSong() {
        play(LEVEL_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        assertSaved(LEVEL_MUSIC);

        restore();
        assertEquals(LEVEL_MUSIC, activeMusicId());
    }

    /**
     * The save is a single slot, never a stack: whatever the sequence, at most
     * one song is ever waiting to be restored.
     */
    @Test
    void theSaveSlotNeverHoldsMoreThanOneSong() {
        play(LEVEL_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        play(SUPER_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        play(INVINCIBILITY_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        assertSaved(INVINCIBILITY_MUSIC);

        restore();
        assertEquals(INVINCIBILITY_MUSIC, activeMusicId());
        assertNothingSaved();
    }

    /** Ordinary music replaces the foreground and drops any saved song. */
    @Test
    void ordinaryMusicReplacesTheForegroundInsteadOfStacking() {
        play(LEVEL_MUSIC);
        play(EXTRA_LIFE_MUSIC);
        play(LEVEL_MUSIC);
        assertEquals(LEVEL_MUSIC, activeMusicId());
        assertNothingSaved();
    }

    @Test
    void fallbackPcmOverrideSuspendsAndRestoresSmpsMusic() {
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(LEVEL_MUSIC,
                smpsMusic("smps-zone", LEVEL_MUSIC));
        installMixedProfile(loader);
        registerFallbackMusic(EXTRA_LIFE_MUSIC);

        play(LEVEL_MUSIC);
        assertNotNull(AudioManagerTestDiagnostics
                .primeAdmittedSmpsMusic(audio));
        assertFalse(snapshot().smpsSession().physical().outputSilenced(),
                "precondition: the SMPS device is producing output");

        play(EXTRA_LIFE_MUSIC);
        assertEquals(EXTRA_LIFE_MUSIC, activeMusicId());
        assertNoSmpsMusic();
        assertTrue(snapshot().smpsSession().physical().outputSilenced(),
                "a PCM foreground must silence the suspended SMPS music");

        restore();
        assertEquals(LEVEL_MUSIC, activeMusicId());
        assertSmpsMusic(LEVEL_MUSIC);
        assertNothingSaved();
    }

    @Test
    void smpsOverrideStopsBeforeRestoringFallbackPcmMusic() {
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(EXTRA_LIFE_MUSIC,
                smpsMusic("smps-jingle", EXTRA_LIFE_MUSIC));
        installMixedProfile(loader);
        registerFallbackMusic(LEVEL_MUSIC);

        play(LEVEL_MUSIC);
        assertNoSmpsMusic();

        play(EXTRA_LIFE_MUSIC);
        assertSmpsMusic(EXTRA_LIFE_MUSIC);
        assertNotNull(AudioManagerTestDiagnostics
                .primeAdmittedSmpsMusic(audio));
        assertFalse(snapshot().smpsSession().physical().outputSilenced(),
                "precondition: the SMPS override is producing output");

        restore();
        assertEquals(LEVEL_MUSIC, activeMusicId());
        assertNoSmpsMusic();
        assertTrue(snapshot().smpsSession().physical().outputSilenced(),
                "restoring PCM must stop and silence the SMPS override");
        assertNothingSaved();
    }

    private void play(int musicId) {
        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);
    }

    private void restore() {
        audio.restoreMusic();
        audio.presentFrame(PresentationMode.SILENT);
    }

    private void installMixedProfile(
            AudioTestFixtures.StubSmpsLoader loader) {
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader) {
            @Override
            public SmpsSequencerConfig getSequencerConfig() {
                return new SmpsSequencerConfig.Builder().build();
            }

            @Override
            public int getExtraLifeMusicId() {
                return EXTRA_LIFE_MUSIC;
            }
        });
        audio.setRom(new Rom());
    }

    private void registerFallbackMusic(int musicId) {
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(audio,
                "music/" + Integer.toHexString(musicId).toUpperCase()
                        + ".wav",
                new byte[SAMPLE_RATE], SAMPLE_RATE);
    }

    private static AudioTestFixtures.StubSmpsData smpsMusic(
            String name, int musicId) {
        AudioTestFixtures.StubSmpsData music =
                new AudioTestFixtures.StubSmpsData(name);
        music.setId(musicId);
        return music;
    }

    private void assertNoSmpsMusic() {
        assertTrue(snapshot().smpsLogical().sequencers().stream()
                        .noneMatch(entry -> !entry.sfx()),
                "no session-owned SMPS music may remain active");
    }

    private void assertSmpsMusic(int musicId) {
        int actual = snapshot().smpsLogical().sequencers().stream()
                .filter(entry -> !entry.sfx())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no session-owned SMPS music is active"))
                .source().id();
        assertEquals(musicId, actual);
    }

    private int activeMusicId() {
        AudioPresentationSnapshot.MusicSlotSnapshot active =
                snapshot().activeMusic();
        assertNotNull(active, "no music is playing at all");
        return active.musicId();
    }

    /** Asserts the single song the 1-up jingle saved for restoration. */
    private void assertSaved(int musicId) {
        assertSavedSongs(musicId);
    }

    private void assertNothingSaved() {
        assertSavedSongs();
    }

    private void assertSavedSongs(int... musicIds) {
        int[] actual = snapshot().overrideStack().stream()
                .mapToInt(AudioPresentationSnapshot.MusicSlotSnapshot::musicId)
                .toArray();
        assertEquals(Arrays.toString(musicIds), Arrays.toString(actual),
                "song saved for the 1-up jingle to restore");
    }

    private AudioPresentationSnapshot snapshot() {
        return audio.captureLogicalSnapshot().presentation();
    }

    private record OverrideProfile() implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return null; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return INVINCIBILITY_MUSIC; }
        @Override public int getExtraLifeMusicId() { return EXTRA_LIFE_MUSIC; }
        @Override public int getSuperSonicMusicId() { return SUPER_MUSIC; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
        @Override public Map<GameMusic, Integer> getMusicMap() { return Map.of(); }
    }
}
