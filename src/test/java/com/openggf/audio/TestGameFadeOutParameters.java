package com.openggf.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A music fade takes its step count and delay from the game whose driver is
 * playing, not from shared code.
 *
 * <p>Sonic 3 &amp; Knuckles' {@code zFadeOutMusic} loads {@code zFadeOutTimeout}
 * with 28h and both {@code zFadeDelayTimeout} and {@code zFadeDelay} with 6
 * (Sound/Z80 Sound Driver.asm:2306-2311), so it reaches silence in 240 frames.
 * Sonic 1 and 2 use the same step count with a delay of 3, so they take 120.
 *
 * <p>The bug this pins: the no-argument fade hardcoded the Sonic 1 and 2 delay,
 * and the object-services facade called it directly. Every S3K object fade ran
 * at double speed, so cuts the ROM places mid-fade landed on dead silence
 * instead. Both the command path and the direct path now resolve through the
 * profile, so they cannot drift apart again.
 */
class TestGameFadeOutParameters {
    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new AudioTestFixtures.RecordingAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void sonic3kFadesOverTheRomsTwoHundredAndFortyFrames() {
        assertFadeParameters(new Sonic3kAudioProfile(), 0x28, 6);
    }

    @Test
    void sonic1FadesOverItsOwnHundredAndTwentyFrames() {
        assertFadeParameters(new Sonic1AudioProfile(), 0x28, 3);
    }

    @Test
    void sonic2FadesOverItsOwnHundredAndTwentyFrames() {
        assertFadeParameters(new Sonic2AudioProfile(), 0x28, 3);
    }

    @Test
    void theSystemCommandAndTheDirectCallAgreeForS3k() {
        audio.setAudioProfile(new Sonic3kAudioProfile());
        audio.beginCommandTimelineFrame(0);
        audio.fadeOutMusic();
        audio.playMusic(Sonic3kSmpsConstants.CMD_FADE_OUT);

        List<AudioCommand.FadeOutMusic> fades = recordedFades();
        assertEquals(2, fades.size(),
                "both the direct fade and the fade-out command must reach the driver");
        assertEquals(fades.get(0), fades.get(1),
                "the fade-out command and the direct fade must carry the same"
                + " ROM parameters, or one path silently uses another game's");
    }

    private void assertFadeParameters(GameAudioProfile profile, int steps, int delay) {
        audio.setAudioProfile(profile);
        audio.beginCommandTimelineFrame(0);
        audio.fadeOutMusic();

        List<AudioCommand.FadeOutMusic> fades = recordedFades();
        assertEquals(1, fades.size(), "expected exactly one recorded fade");
        assertEquals(new AudioCommand.FadeOutMusic(steps, delay), fades.get(0));
    }

    private List<AudioCommand.FadeOutMusic> recordedFades() {
        return audio.commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.FadeOutMusic.class::isInstance)
                .map(AudioCommand.FadeOutMusic.class::cast)
                .toList();
    }
}
