package com.openggf.game.sonic3k.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.AudioManager;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Which track the drowning countdown hands back to, driven through a real AIZ1
 * fixture rather than through the profile alone.
 *
 * <p>{@code Player_ResetAirTimer} (sonic3k.asm:33663-33686) loads
 * {@code Current_music} and then substitutes {@code mus_Invincibility} ($2C)
 * when {@code Status_Invincible} is set, the same track again when
 * {@code Super_Sonic_Knux_flag} is set, and {@code mus_MinibossK} ($18) when
 * {@code Boss_flag} is set.
 *
 * <p>The engine restored the zone track in every case, so surfacing while
 * invincible or Super cut the theme dead and slammed in the level music. That
 * was found by running the events, not by reading them, and this is the wiring
 * half of the fix: TestAirResetMusicSelection pins each game's choice, and this
 * pins that the drowning restore actually asks for it.
 */
@RequiresRom(SonicGame.SONIC_3K)
@ExtendWith(SingletonResetExtension.class)
class TestS3kAirResetMusicRestore {
    /** Air starts at 30 and the countdown music triggers when it reaches 12. */
    private static final int UPDATES_TO_START_COUNTDOWN = (30 - 12 + 1) * 60;

    @Test
    void surfacingWhileInvincibleResumesTheInvincibilityTheme() {
        assertSurfacingResumes(true, false, Sonic3kMusic.INVINCIBILITY.id,
                "Status_Invincible selects mus_Invincibility (sonic3k.asm:33670-33672)");
    }

    @Test
    void surfacingWhileSuperResumesTheInvincibilityTheme() {
        assertSurfacingResumes(false, true, Sonic3kMusic.INVINCIBILITY.id,
                "Super_Sonic_Knux_flag selects mus_Invincibility (sonic3k.asm:33675-33677)");
    }

    @Test
    void anOrdinarySurfacingResumesTheZoneTrack() {
        assertSurfacingResumes(false, false, Sonic3kMusic.AIZ1.id,
                "with no override set the ROM resumes Current_music");
    }

    private void assertSurfacingResumes(boolean invincible, boolean superForm,
            int expectedMusicId, String why) {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        AudioManager audio = AudioManager.getInstance();

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();
        assertEquals(Sonic3kMusic.AIZ1.id, GameServices.level().getCurrentLevelMusicId(),
                "the fixture must be on AIZ1 for its track to be the baseline");

        DrowningController controller = new DrowningController(sonic);
        for (int update = 0; update < UPDATES_TO_START_COUNTDOWN; update++) {
            controller.update();
        }
        assertTrue(controller.isDrowningMusicPlaying(),
                "the countdown music must be running before the restore is meaningful");

        audio.presentFrame(PresentationMode.FORWARD);
        int before = musicRequests(audio).size();

        if (invincible) {
            sonic.setInvincibleFrames(600);
        }
        if (superForm) {
            sonic.setSuperSonic(true);
        }
        controller.onExitWater();
        audio.presentFrame(PresentationMode.FORWARD);

        List<AudioCommand.PlayMusic> afterSurfacing =
                musicRequests(audio).subList(before, musicRequests(audio).size());
        assertEquals(1, afterSurfacing.size(),
                "Player_ResetAirTimer requests exactly one track on surfacing");
        assertEquals(expectedMusicId, afterSurfacing.getFirst().musicId(), why);
    }

    private static List<AudioCommand.PlayMusic> musicRequests(AudioManager audio) {
        return audio.commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.PlayMusic.class::isInstance)
                .map(AudioCommand.PlayMusic.class::cast)
                .toList();
    }
}
