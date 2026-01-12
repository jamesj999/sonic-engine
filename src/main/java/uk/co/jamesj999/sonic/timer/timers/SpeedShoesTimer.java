package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameAudioProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Timer for the Speed Shoes power-up effect.
 * Duration: 1200 frames (20 seconds @ 60fps) per SPG Sonic 2.
 * When timer expires, speed shoes are deactivated and music slows back down.
 */
public class SpeedShoesTimer extends AbstractTimer {
    public static final int DURATION_FRAMES = 1200; // 20 seconds @ 60fps

    private final AbstractPlayableSprite sprite;

    public SpeedShoesTimer(String code, AbstractPlayableSprite sprite) {
        super(code, DURATION_FRAMES);
        this.sprite = sprite;
    }

    @Override
    public boolean perform() {
        // Deactivate speed shoes on the sprite
        sprite.deactivateSpeedShoes();

        // Slow down the music
        AudioManager audioManager = AudioManager.getInstance();
        GameAudioProfile audioProfile = audioManager.getAudioProfile();
        if (audioProfile != null) {
            audioManager.playMusic(audioProfile.getSpeedShoesOffCommandId());
        }
        return true;
    }
}
