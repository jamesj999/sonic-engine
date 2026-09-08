package com.openggf.audio;

import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;

import java.util.function.Consumer;

/**
 * SMPS source construction, profile routing, and logical music-source
 * descriptors for one game. The backend is <strong>not</strong> a presentation
 * owner: {@code AudioPresentationProducer} owns the presentation clock, final
 * PCM, history, reverse cursor, and every capture lease, and
 * {@code OpenAlPcmSink} is the only writer of a real audio device.
 */
public interface AudioBackend {

    void init();

    void setAudioProfile(GameAudioProfile profile);

    default void setAdmissionObserver(AudioAdmissionObserver observer) {
    }

    default void setDriverServiceObserver(
            SmpsDriverServiceObserver observer) {
    }

    default void setChipWriteObserver(ChipWriteObserver observer) {
    }

    default void setSfxContentionObserver(
            SfxContentionObserver observer) {
    }

    default void registerAudioProfileCoordHandlers(GameAudioProfile profile) {
    }

    default AudioPresentationTuning presentationTuning() {
        return AudioPresentationTuning.DEFAULT;
    }

    /**
     * Plays music by ID (potentially loading from ROM or fallback map).
     * 
     * @param musicId The music ID from the ROM/LevelData.
     */
    void playMusic(int musicId);

    void playSmps(AbstractSmpsData data, DacData dacData);

    /**
     * Plays music SMPS data with an explicit sequencer config override.
     * Used for donor (cross-game) music that needs a different driver configuration
     * than the base game's.
     *
     * @param forceOverride when true, treat as a temporary music override (push current
     *                      music onto the stack) regardless of the base game's audio profile.
     *                      Used for donor music whose IDs aren't recognized by the base profile.
     */
    default void playSmps(AbstractSmpsData data, DacData dacData,
                          SmpsSequencerConfig config, boolean forceOverride) {
        playSmps(data, dacData);
    }

    void playSfxSmps(AbstractSmpsData data, DacData dacData);

    void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch);

    /**
     * Plays an SFX with an explicit sequencer config override.
     * Used for donor (cross-game) SFX that need a different driver configuration
     * than the base game's.
     */
    default void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                             SmpsSequencerConfig config) {
        playSfxSmps(data, dacData, pitch);
    }

    /**
     * Plays an SFX with a fully captured sequencer and classification tuple.
     * Existing backends retain source compatibility through the explicit-config
     * overload; SMPS-aware backends override this to consume {@code policy}.
     */
    default void playSfxSmps(
            AbstractSmpsData data,
            DacData dacData,
            float pitch,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy policy) {
        playSfxSmps(data, dacData, pitch, config);
    }

    /**
     * Plays a sound effect by name (mapped to a WAV file).
     * 
     * @param sfxName The name of the SFX (e.g., "JUMP", "RING").
     */
    void playSfx(String sfxName);

    void playSfx(String sfxName, float pitch);

    /**
     * Stops any active music/streaming playback.
     */
    void stopPlayback();

    /**
     * Stops all playing SFX without affecting music.
     * Clears both SFX sequencers in the active music driver and the standalone SFX stream.
     */
    void stopAllSfx();

    /**
     * Fade out the currently playing music over time.
     * ROM equivalent: MusID_FadeOut (0xF9) / zFadeOutMusic.
     * Host policy owns the command's pre-fade effects; Sonic 1 stops its
     * normal and special SFX tracks before fading the music channels.
     *
     * @param steps total number of volume steps (ROM default: 0x28 = 40)
     * @param delay frames between each volume step (ROM default: 3)
     */
    void fadeOutMusic(int steps, int delay);

    void toggleMute(ChannelType type, int channel);

    void toggleSolo(ChannelType type, int channel);

    boolean isMuted(ChannelType type, int channel);

    boolean isSoloed(ChannelType type, int channel);

    void setSpeedShoes(boolean enabled);

    /**
     * Set the speed multiplier on the music sequencer (S3K speed shoes).
     * A multiplier of 1 means normal speed, >1 means extra tick calls per frame.
     */
    default void setSpeedMultiplier(int multiplier) {
        // Default no-op; backends that support S3K override this.
    }

    /**
     * Change the music dividing timing (tempo).
     * ROM: Change_Music_Tempo. Lower values = faster music.
     * Used by S3K special stages to speed up music as the player accelerates.
     */
    default void changeMusicTempo(int newDividingTiming) {
        // Default no-op; backends with SMPS sequencers override this.
    }

    void restoreMusic();

    /**
     * Ends a temporary music override (e.g., invincibility). If the override is
     * currently playing, it should restore the previous track. If it is queued
     * beneath another override, it should be removed so it does not resume.
     *
     * @param musicId The music ID to end.
     */
    void endMusicOverride(int musicId);

    void update();

    void destroy();

    /**
     * Pauses audio playback. Called when the game window is minimized or loses focus.
     * Audio should be suspended but state preserved so it can resume seamlessly.
     */
    void pause();

    /**
     * Resumes audio playback after being paused.
     * Called when the game window is restored or regains focus.
     */
    void resume();

    default void prepareLogicalMusicSource(AudioSourceDescriptor descriptor) {
    }

    default int outputSampleRate() {
        return 48_000;
    }

    /**
     * Creates the speaker-only final-PCM sink for this backend. Backends no
     * longer own audible music or SFX sources.
     */
    default AudioPresentationSink createPresentationSink(
            Consumer<Throwable> failureHandler,
            Consumer<String> warningHandler) {
        return new NoDeviceAudioSink(outputSampleRate());
    }
}
