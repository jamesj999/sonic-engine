package com.openggf.audio;

import java.util.Map;

/**
 * Shared base for game audio profiles. Extracts the common
 * {@link #handleSystemCommand} dispatch pattern (fade-out, stop-all, SEGA PCM)
 * and the immutable sound-map accessor.
 *
 * <p>Subclasses supply game-specific command IDs via {@link #getFadeOutCommandId()},
 * {@link #getStopAllCommandId()}, and {@link #getSegaCommandId()}.  Games that
 * do not use system commands (e.g. Sonic 2, where fade/stop are called directly)
 * can leave the defaults ({@code -1}) so the template returns {@code false}.
 *
 * <p>The fade-out action is delegated to {@link #executeFadeOut(AudioManager)} so
 * that each game can supply its own step/delay parameters.
 */
public abstract class AbstractAudioProfile implements GameAudioProfile {

    private final Map<GameSound, Integer> soundMap;
    private final Map<GameMusic, Integer> musicMap;

    protected AbstractAudioProfile(Map<GameSound, Integer> soundMap) {
        this(soundMap, Map.of());
    }

    protected AbstractAudioProfile(Map<GameSound, Integer> soundMap, Map<GameMusic, Integer> musicMap) {
        this.soundMap = soundMap;
        this.musicMap = musicMap;
    }

    // ------------------------------------------------------------------
    // System-command template
    // ------------------------------------------------------------------

    /**
     * Returns the sound ID that triggers a music fade-out, or {@code -1} if
     * this game does not route fade-out through {@code playMusic()} dispatch.
     */
    protected int getFadeOutCommandId() {
        return -1;
    }

    /** Optional second sound ID routed to the same fade routine. */
    protected int getAlternateFadeOutCommandId() {
        return -1;
    }

    /**
     * Returns the sound ID that stops all audio, or {@code -1} if this game
     * does not route stop-all through {@code playMusic()} dispatch.
     */
    protected int getStopAllCommandId() {
        return -1;
    }

    /** Optional out-of-table stop ID routed to the global-stop routine. */
    protected int getStopCommandId() {
        return -1;
    }

    /** Optional command that transiently silences PSG. */
    protected int getPsgSilenceCommandId() {
        return -1;
    }

    /** Optional command that releases only SMPS SFX ownership. */
    protected int getStopSfxCommandId() {
        return -1;
    }

    /**
     * Returns the sound ID that triggers the SEGA PCM jingle, or {@code -1}
     * if not applicable.
     */
    protected int getSegaCommandId() {
        return -1;
    }

    /**
     * Returns the sound ID that stops the SEGA PCM jingle without stopping music
     * or normal SFX, or {@code -1} if not applicable.
     */
    protected int getStopSegaCommandId() {
        return -1;
    }

    /**
     * Executes the fade-out action for a fade-out system command. Override
     * {@link GameAudioProfile#fadeOutMusic(AudioManager)} rather than this to
     * supply game-specific step and delay parameters; both the command path
     * and the direct object-services path then use the same values.
     */
    protected void executeFadeOut(AudioManager manager) {
        fadeOutMusic(manager);
    }

    /**
     * Template implementation of system-command dispatch.  Checks the
     * sound ID against fade-out, stop-all, and SEGA command IDs and
     * delegates to the appropriate {@link AudioManager} method.
     *
     * <p>If all three command IDs are {@code -1} (the default), this
     * method always returns {@code false}, matching the behaviour of
     * the {@link GameAudioProfile} interface default.
     */
    @Override
    public boolean handleSystemCommand(int soundId, AudioManager manager) {
        if ((soundId == getFadeOutCommandId()
                && getFadeOutCommandId() != -1)
                || (soundId == getAlternateFadeOutCommandId()
                && getAlternateFadeOutCommandId() != -1)) {
            executeFadeOut(manager);
            return true;
        } else if ((soundId == getStopAllCommandId()
                && getStopAllCommandId() != -1)
                || (soundId == getStopCommandId()
                && getStopCommandId() != -1)) {
            manager.retainGlobalStop(soundId);
            return true;
        } else if (soundId == getPsgSilenceCommandId()
                && getPsgSilenceCommandId() != -1) {
            manager.silencePsg(soundId);
            return true;
        } else if (soundId == getStopSfxCommandId()
                && getStopSfxCommandId() != -1) {
            manager.stopSmpsSfx(soundId);
            return true;
        } else if (soundId == getSegaCommandId() && getSegaCommandId() != -1) {
            manager.playSegaPcmCommand(soundId);
            return true;
        } else if (soundId == getStopSegaCommandId() && getStopSegaCommandId() != -1) {
            manager.stopSegaPcmAndRetainGlobalStop(soundId);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Sound map
    // ------------------------------------------------------------------

    @Override
    public Map<GameSound, Integer> getSoundMap() {
        return soundMap;
    }

    @Override
    public Map<GameMusic, Integer> getMusicMap() {
        return musicMap;
    }
}
