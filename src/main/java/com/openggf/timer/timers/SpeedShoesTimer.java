package com.openggf.timer.timers;

import com.openggf.audio.GameAudioProfile;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PowerUpRules;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.timer.AbstractTimer;
import com.openggf.timer.DisplayPhaseTimer;

/**
 * Timer for the Speed Shoes power-up effect.
 * Duration: 1200 movement frames (20 seconds @ 60fps) per SPG Sonic 2.
 * When timer expires, speed shoes are deactivated and music slows back down.
 *
 * <p>The ROM decrement cadence is per-game (see
 * {@link PowerUpRules#speedShoesTimerDecimation()}):
 * S1/S2 use a per-frame word timer counting from {@code 0x4B0}
 * (docs/s2disasm/s2.asm:36307-36326); S3K uses a byte timer counting from
 * {@code (20*60)/8 = 150} and decremented only on every 8th level frame —
 * {@code Sonic_ChkShoes} gates {@code subq.b} on
 * the low byte at {@code Level_frame_counter+1} being divisible by 8
 * (docs/skdisasm/sonic3k.asm:22108-22111; init
 * docs/skdisasm/sonic3k.asm:40858). Both expire after 1200 wall-clock frames.
 *
 * <p>This is a {@link DisplayPhaseTimer}: all three games run the countdown
 * from {@code Sonic_Display}, after the movement modes have been dispatched,
 * and do both consequences of reaching zero there in the one frame — restore
 * top speed, acceleration and deceleration, and queue the slow-down music.
 * S1 {@code docs/s1disasm/_incObj/01 Sonic.asm:182-204} (restore at
 * {@code :190-192}, {@code bgm_Slowdown} at {@code :203-204});
 * S2 {@code docs/s2disasm/s2.asm:36307-36326} (restore at
 * {@code :36314-36316}, {@code MusID_SlowDown} at {@code :36325-36326});
 * S3K {@code docs/skdisasm/sonic3k.asm:22103-22127} (restore at
 * {@code :22115-22117}, {@code Change_Music_Tempo} at {@code :22126-22127}).
 * Ticking it from the character's display step keeps the music command in the
 * same frame as the physics restore, so it reaches the same driver service the
 * ROM's queue write does.
 */
public class SpeedShoesTimer extends AbstractTimer implements DisplayPhaseTimer {

    public static final int ROM_DURATION_FRAMES = 0x4B0; // speedshoes_time(a0)

    /**
     * Offset aligning the engine's level frame counter to ROM
     * {@code Level_frame_counter} as read from the display step. It is zero:
     * {@code (Level_frame_counter+1).w} is 68k syntax for reading the low byte
     * at the label address plus one, not an increment of the value, and the
     * engine now advances its counter where the ROM does, at the top of the
     * level loop before the object pass
     * ({@code LevelManager.advanceLevelFrameCounter}), so the display step
     * reads the ROM's own value.
     */
    static final int LEVEL_FRAME_PHASE_OFFSET = 0;

    private final AbstractPlayableSprite sprite;
    /** Decrement cadence in level frames; a power of two (1 or 8). */
    private final int decimation;

    public SpeedShoesTimer(String code, AbstractPlayableSprite sprite) {
        super(code, durationTicks(sprite));
        this.sprite = sprite;
        this.decimation = decimationFor(sprite);
    }

    private static int decimationFor(AbstractPlayableSprite sprite) {
        PowerUpRules rules = powerUpRulesFor(sprite);
        int d = rules != null
                ? rules.speedShoesTimerDecimation()
                : 1;
        return d < 1 ? 1 : d;
    }

    private static int durationTicks(AbstractPlayableSprite sprite) {
        return ROM_DURATION_FRAMES / decimationFor(sprite);
    }

    @Override
    public Object displayPhaseOwner() {
        return sprite;
    }

    private static PowerUpRules powerUpRulesFor(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return null;
        }
        GameRules rules = sprite.getGameRules();
        if (rules != null && rules.powerUp() != null) {
            return rules.powerUp();
        }
        return null;
    }

    @Override
    public void decrementTick() {
        // The countdown is owned by the playable character's normal control
        // routine, not by the global level loop. Sonic_ChkShoes is reached from
        // Sonic_Display (routine 2); hurt routine 4 draws directly and does not
        // visit the shoes check. Keep the timer frozen until normal control
        // resumes, matching the same routine gate as the native display timer.
        if (sprite != null && sprite.isHurt()) {
            return;
        }
        // ROM decrements only on aligned level frames (every `decimation`-th
        // frame). For decimation == 1 this is every frame, so S1/S2 are
        // unchanged. Gate on the global level frame counter so expiry lands on
        // the ROM-accurate frame (phase-correct for trace parity). With no level
        // frame context (a unit harness) fall back to per-frame.
        LevelManager levelManager = sprite != null ? sprite.currentLevelManagerIfAvailable() : null;
        if (levelManager == null
                || isDecrementFrame(levelManager.getFrameCounter(), decimation)) {
            super.decrementTick();
        }
    }

    /**
     * Whether the timer decrements on the given level frame for a decimation.
     * For {@code decimation <= 1} every frame qualifies; otherwise only frames
     * where {@code (frame + LEVEL_FRAME_PHASE_OFFSET) & (decimation-1) == 0} do.
     * {@code decimation} is assumed to be a power of two.
     */
    static boolean isDecrementFrame(int frame, int decimation) {
        if (decimation <= 1) {
            return true;
        }
        return ((frame + LEVEL_FRAME_PHASE_OFFSET) & (decimation - 1)) == 0;
    }

    @Override
    public boolean perform() {
        // Deactivate speed shoes on the sprite
        sprite.deactivateSpeedShoes();

        // Slow down the music
        var audioManager = sprite.currentAudioManager();
        GameAudioProfile audioProfile = audioManager.getAudioProfile();
        if (audioProfile != null) {
            if (audioProfile.getSpeedMode()
                    == GameAudioProfile.SpeedMode.FRAME_MULTIPLY) {
                // S3K expiry writes zero to zTempoSpeedup. The engine's
                // normalized normal-speed multiplier is one.
                audioManager.setSpeedMultiplier(1);
            } else {
                audioManager.playMusic(
                        audioProfile.getSpeedShoesOffCommandId());
            }
        }
        return true;
    }
}
