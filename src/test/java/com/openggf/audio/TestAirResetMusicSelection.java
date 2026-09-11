package com.openggf.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import org.junit.jupiter.api.Test;

/**
 * Which track resumes when the drowning countdown ends and the player's air is
 * reset.
 *
 * <p>Sonic 3 &amp; Knuckles' {@code Player_ResetAirTimer}
 * (sonic3k.asm:33663-33686) loads {@code Current_music} and then runs three
 * substitutions in order: {@code Status_Invincible} selects
 * {@code mus_Invincibility} ($2C), {@code Super_Sonic_Knux_flag} selects the
 * same track, and {@code Boss_flag} selects {@code mus_MinibossK} ($18). The
 * boss test is last, so it wins over the other two.
 *
 * <p>The bug this pins: the drowning restore always played the zone track, so
 * surfacing while invincible or Super cut the theme dead and slammed in the
 * level music. That was confirmed by running it, not by reading it.
 *
 * <p>Sonic 1 and 2 have the same shape with their own tracks, so each game's
 * rule is filled from its own routine rather than left to a shared default.
 * Sonic 1's overrides are conditional on {@code Revision<>0}, which holds for
 * the REV01 ROM this engine models (sonic.asm:14). Sonic 2 differs from S3K in
 * giving Super its own track instead of reusing the invincibility theme.
 */
class TestAirResetMusicSelection {
    private static final int LEVEL_MUSIC = Sonic3kMusic.AIZ1.id;

    private final GameAudioProfile s3k = new Sonic3kAudioProfile();

    @Test
    void anOrdinarySurfacingResumesTheLevelTrack() {
        assertEquals(LEVEL_MUSIC, resolve(s3k, false, false, false));
    }

    @Test
    void surfacingWhileInvincibleKeepsTheInvincibilityTheme() {
        assertEquals(Sonic3kMusic.INVINCIBILITY.id, resolve(s3k, true, false, false),
                "Status_Invincible selects mus_Invincibility (sonic3k.asm:33670-33672)");
    }

    @Test
    void surfacingWhileSuperKeepsTheInvincibilityTheme() {
        assertEquals(Sonic3kMusic.INVINCIBILITY.id, resolve(s3k, false, true, false),
                "Super_Sonic_Knux_flag selects mus_Invincibility (sonic3k.asm:33675-33677)");
    }

    @Test
    void surfacingDuringABossKeepsTheBossTheme() {
        assertEquals(Sonic3kMusic.MINIBOSS.id, resolve(s3k, false, false, true),
                "Boss_flag selects mus_MinibossK (sonic3k.asm:33680-33682)");
    }

    @Test
    void theBossTestIsTakenLastSoItWinsOverInvincibility() {
        assertEquals(Sonic3kMusic.MINIBOSS.id, resolve(s3k, true, true, true),
                "the ROM tests Boss_flag after both others, so it overwrites their choice");
    }

    @Test
    void sonic1SubstitutesItsOwnInvincibilityAndBossTracks() {
        GameAudioProfile s1 = new Sonic1AudioProfile();
        assertEquals(LEVEL_MUSIC, resolve(s1, false, false, false));
        assertEquals(Sonic1Music.INVINCIBILITY.id, resolve(s1, true, false, false),
                "v_invinc selects bgm_Invincible (sub ResumeMusic.asm:19-21, Revision = 1)");
        assertEquals(Sonic1Music.BOSS.id, resolve(s1, true, false, true),
                "f_lockscreen is tested second, so a boss wins over invincibility");
    }

    @Test
    void sonic2GivesSuperItsOwnTrackUnlikeSonic3k() {
        GameAudioProfile s2 = new Sonic2AudioProfile();
        assertEquals(Sonic2Music.INVINCIBILITY.id, resolve(s2, true, false, false),
                "status_secondary.invincible selects MusID_Invincible (s2.asm:42304-42306)");
        assertEquals(Sonic2Music.SUPER_SONIC.id, resolve(s2, true, true, false),
                "Super_Sonic_flag selects MusID_SuperSonic, its own track (s2.asm:42308-42310)");
        assertEquals(Sonic2Music.BOSS.id, resolve(s2, true, true, true),
                "Current_Boss_ID is tested last, so a boss wins over both");
    }

    private static int resolve(GameAudioProfile profile,
            boolean invincible, boolean superForm, boolean bossActive) {
        return profile.resolveAirResetMusic(LEVEL_MUSIC, invincible, superForm, bossActive);
    }
}
