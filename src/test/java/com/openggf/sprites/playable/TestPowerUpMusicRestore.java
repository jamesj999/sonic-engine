package com.openggf.sprites.playable;

import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.level.LevelManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When invincibility ends the level music is re-issued, not unwound from a
 * saved song.
 *
 * <p>ROM: {@code Sonic_ChkInvin} plays {@code Current_music} when
 * {@code invincibility_timer} reaches zero, and skips the change entirely
 * during a boss fight or while the drowning countdown owns playback. The Super
 * revert has no music code of its own — {@code SonicKnux_SuperHyper}'s
 * {@code .revertToNormal} sets {@code invincibility_timer} to 1 and lets this
 * same path run a tick later.
 */
class TestPowerUpMusicRestore {

    private static final int LEVEL_MUSIC = 0x05;

    private AudioManager audio;
    private Sonic player;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        installLevelMusic(LEVEL_MUSIC);
        // The module's event provider outlives a single test's session.
        levelEvents().setBossActive(false);
        player = new Sonic("test", (short) 0, (short) 0);
    }

    @AfterEach
    void tearDown() {
        audio.setBackend(new NullAudioBackend());
        audio.resetState();
        SessionManager.clear();
    }

    @Test
    void invincibilityExpiryPlaysTheLevelMusic() {
        player.setInvincibleFrames(1);
        player.tickStatus();

        assertEquals(List.of(LEVEL_MUSIC), musicRequests());
    }

    /**
     * The Super revert defers to the invincibility path, which is what makes a
     * transform/revert cycle land back on the zone music no matter what else
     * happened while Super was active.
     */
    @Test
    void superRevertReachesTheSamePathThroughTheInvincibilityTimer() {
        // The revert sets invincibility_timer to 1 and plays nothing itself.
        player.setInvincibleFrames(1);
        assertTrue(musicRequests().isEmpty(),
                "the revert itself must not issue music");

        player.tickStatus();
        assertEquals(List.of(LEVEL_MUSIC), musicRequests());
    }

    /** ROM: {@code tst.b (Boss_flag).w} — don't change music in a boss fight. */
    @Test
    void bossFightKeepsItsMusicWhenInvincibilityExpires() {
        levelEvents().setBossActive(true);

        player.setInvincibleFrames(1);
        player.tickStatus();

        assertTrue(musicRequests().isEmpty(),
                "boss music must survive invincibility expiring");
    }

    /** ROM: {@code cmpi.b #12,air_left(a0)} — don't change music while drowning. */
    @Test
    void drowningCountdownKeepsItsMusicWhenInvincibilityExpires() {
        DrowningController drowning = player.getDrowningController();
        drowning.setRemainingAirFromFixedCountdown(11);
        assertTrue(drowning.isCountdownOwningMusic());

        player.setInvincibleFrames(1);
        player.tickStatus();

        assertTrue(musicRequests().isEmpty(),
                "drowning countdown music must survive invincibility expiring");
    }

    /** Nothing is restored from a saved song — the jingle owns the save slot. */
    @Test
    void expiryDoesNotUnwindASavedSong() {
        player.setInvincibleFrames(1);
        player.tickStatus();

        assertTrue(audio.commandTimeline().entries().stream()
                        .map(entry -> entry.command())
                        .noneMatch(command ->
                                command instanceof AudioCommand.EndMusicOverride
                                        || command instanceof AudioCommand.RestoreMusic),
                "the power-up path must not touch the 1-up jingle's save slot");
        assertEquals(List.of(LEVEL_MUSIC), musicRequests());
        assertEquals(Sonic3kMusic.INVINCIBILITY.id,
                audio.getAudioProfile().getSuperSonicMusicId(),
                "S3K shares one theme between Super and invincibility, so the "
                        + "restore cannot be keyed on the music id");
    }

    private List<Integer> musicRequests() {
        return audio.commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.PlayMusic.class::isInstance)
                .map(AudioCommand.PlayMusic.class::cast)
                .map(AudioCommand.PlayMusic::musicId)
                .toList();
    }

    private AbstractLevelEventManager levelEvents() {
        return (AbstractLevelEventManager)
                GameServices.currentOrBootstrapGameModule().getLevelEventProvider();
    }

    /**
     * Installs a level manager reporting a known zone music id. Loading a real
     * level would need a ROM for a claim that is only about which music the
     * expiry re-issues.
     */
    private void installLevelMusic(int musicId) {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getCurrentLevelMusicId()).thenReturn(musicId);
        GameplayModeContext mode = SessionManager.getCurrentGameplayMode();
        try {
            Field field = GameplayModeContext.class.getDeclaredField("levelManager");
            field.setAccessible(true);
            field.set(mode, levelManager);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
