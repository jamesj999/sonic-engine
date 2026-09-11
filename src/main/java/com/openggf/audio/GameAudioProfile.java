package com.openggf.audio;

import com.openggf.audio.driver.SmpsRequestAdmissionPolicy;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsStatefulCommandPolicy;
import com.openggf.audio.presentation.AudioRequestService;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.data.Rom;

import java.util.Map;

public interface GameAudioProfile {
    /** Optional game-owned raw request front end, created per presentation session. */
    default AudioRequestService createAudioRequestService() {
        return null;
    }

    default String presentationGameId() {
        return "base";
    }

    /** Physical chip policy owned by this base-game presentation session. */
    default SmpsPhysicalPolicy smpsPhysicalPolicy() {
        return LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
    }

    /** Host-owned stateful SMPS commands; donor programs never select this. */
    default SmpsStatefulCommandPolicy smpsStatefulCommandPolicy() {
        return SmpsStatefulCommandPolicy.NONE;
    }

    default void configurePresentationCoordFlagHandlers(
            SmpsCoordFlagHandlerOwner owner) {
    }

    /** How speed shoes affect music playback. */
    enum SpeedMode {
        /** S1/S2: swap to a faster tempo value from the speed-up table. */
        TEMPO_SWAP,
        /** S3K: multiply frame ticks (music updates multiple times per frame). */
        FRAME_MULTIPLY
    }

    SmpsLoader createSmpsLoader(Rom rom);

    SmpsSequencerConfig getSequencerConfig();

    int getSpeedShoesOnCommandId();

    int getSpeedShoesOffCommandId();

    int getInvincibilityMusicId();

    int getExtraLifeMusicId();

    /**
     * Returns the drowning countdown music ID for this game.
     *
     * <p>Examples:
     * <ul>
     *   <li>Sonic 1: 0x92</li>
     *   <li>Sonic 2: 0xDC</li>
     *   <li>Sonic 3&amp;K: 0x31</li>
     * </ul>
     *
     * @return music ID to play when underwater air reaches the countdown threshold
     */
    int getDrowningMusicId();

    /** Returns the Super Sonic music ID, or -1 if not applicable. */
    default int getSuperSonicMusicId() {
        return -1;
    }

    /**
     * Returns true if this music interrupts the current song and restores it
     * when it ends, rather than replacing it.
     *
     * <p>The 1-up jingle is the only such music in every game. The sound driver
     * owns a single save slot used exclusively by it: S1's
     * {@code Sound_PlayBGM} backs up {@code v_1up_ram} and sets
     * {@code f_1up_playing}, and S3K's {@code zPlayMusic} copies
     * {@code zTracksStart} to {@code zTracksSaveStart} and sets
     * {@code zFadeToPrevFlag}. The jingle's own {@code E4} coord flag
     * ("fade in to previous song") is what restores it.
     *
     * <p>Invincibility and Super are <em>not</em> overrides. The ROM plays them
     * as ordinary music and restores the level music by re-issuing it when the
     * power-up ends — see {@code Sonic_ChkInvin}, which the Super revert defers
     * to by setting {@code invincibility_timer} to 1.
     */
    default boolean isMusicOverride(int musicId) {
        return musicId == getExtraLifeMusicId();
    }

    /**
     * Returns true if SFX should be completely blocked during this music.
     * In the original ROM, only the 1-up jingle sets 1upPlaying flag which blocks SFX.
     * Invincibility music does NOT block SFX - you can still hear rings, jumps, etc.
     */
    default boolean isSfxBlockingMusic(int musicId) {
        return musicId == getExtraLifeMusicId();
    }

    /**
     * Returns true if SFX should remain blocked while the previous music fades
     * back in after a blocking override ends.
     */
    default boolean blocksSfxDuringMusicRestoreFadeIn() {
        return true;
    }

    /**
     * Returns the priority for a given sound ID. Higher values = higher priority.
     * Used for SFX channel arbitration.
     */
    default int getSfxPriority(int soundId) {
        return 0x70; // Default priority
    }

    /**
     * Returns the game-owned whole-request SFX admission policy. The shared
     * default is exactly permissive, leaving existing driver-owned S1
     * per-role priority behavior unchanged.
     */
    default SmpsRequestAdmissionPolicy getSfxAdmissionPolicy() {
        return SmpsRequestAdmissionPolicy.PERMISSIVE;
    }

    /**
     * Returns true if the SFX ID is a continuous SFX (cfx_*).
     *
     * <p>Continuous SFX (S3K: 0xBC-0xDB) use a special looping mechanism in the
     * Z80 sound driver. When re-triggered while already playing, the driver
     * extends playback instead of restarting, producing seamless sustained sound.
     * The 0xFC coord flag ({@code cfLoopContinuousSFX}) checks the extension flag
     * to decide whether to loop or stop.
     */
    default boolean isContinuousSfx(int sfxId) {
        return false;
    }

    /**
     * Returns true if the SFX ID is a "special" SFX class for this game profile.
     *
     * <p>Some drivers (notably Sonic 1 68k) route special SFX through dedicated
     * tracks with different override rules than normal SFX.
     */
    default boolean isSpecialSfx(int soundId) {
        return false;
    }

    /**
     * Allows a game profile to normalize high-level SFX pitch requests before
     * they are routed to SMPS or fallback playback.
     */
    default float adjustSfxPitch(GameSound sound, float requestedPitch) {
        return requestedPitch;
    }

    /**
     * Fades the current music out with this game's own ROM parameters.
     *
     * <p>Every fade goes through here, so the driver constants live with the
     * game that owns them rather than in shared code. The default is the
     * Sonic 1 and 2 value of forty steps three frames apart; Sonic 3 &amp;
     * Knuckles overrides it, because {@code zFadeOutMusic} loads a delay of 6
     * (Sound/Z80 Sound Driver.asm:2306-2311).
     *
     * @param manager the audio manager to run the fade on
     */
    default void fadeOutMusic(AudioManager manager) {
        manager.fadeOutMusic(0x28, 3);
    }

    /**
     * Runs {@code profile}'s fade, falling back to the S1/S2 parameters when no
     * profile is installed. Exists so callers stay a single call: {@code
     * AudioManager.fadeOutMusic()} lives in a class whose compiled shape a
     * strict zero-allocation presentation assertion is sensitive to.
     *
     * @param profile the active profile, or {@code null} when none is installed
     * @param manager the audio manager to run the fade on
     */
    static void fadeOut(GameAudioProfile profile, AudioManager manager) {
        if (profile == null) {
            manager.fadeOutMusic(0x28, 3);
            return;
        }
        profile.fadeOutMusic(manager);
    }

    /**
     * Chooses the track to restore when the drowning countdown ends and the
     * player's air is reset.
     *
     * <p>The choice belongs to the game. Sonic 3 &amp; Knuckles substitutes one
     * of three tracks for the level track depending on player state
     * (sonic3k.asm:33663-33686); Sonic 1's {@code ResumeMusic} and Sonic 2's
     * equivalent resume the level track unchanged, which is what this default
     * expresses for them.
     *
     * @param levelMusicId the track that would otherwise resume
     * @param invincible whether the player is star-invincible
     * @param superForm whether the player is in a Super or Hyper form
     * @param bossActive whether a boss currently owns the music
     * @return the track to request
     */
    default int resolveAirResetMusic(int levelMusicId, boolean invincible,
            boolean superForm, boolean bossActive) {
        return levelMusicId;
    }

    /**
     * Handle a game-specific system command (e.g., fade out, stop all).
     * Called early in {@code AudioManager.playMusic()} dispatch.
     *
     * @param soundId the sound/command ID
     * @param manager the AudioManager instance for executing the command
     * @return true if the command was handled, false if it should continue normal dispatch
     */
    default boolean handleSystemCommand(int soundId, AudioManager manager) {
        return false;
    }

    /**
     * Returns ROM metadata for the boot SEGA PCM chant, or {@code null} when the
     * game has no command-driven SEGA PCM path.
     */
    default SegaPcmSpec getSegaPcmSpec() {
        return null;
    }

    /**
     * Reads this game's boot SEGA PCM chant out of the active ROM value.
     *
     * <p>The audio layer owns the request but not the ROM read: the per-game
     * profiles live in the runtime layer, which may depend on
     * {@code com.openggf.data}, so they supply the bytes and keep the audio
     * services free of ROM edges.
     *
     * @param rom the active ROM value as the audio layer holds it
     * @return the sample bytes, or {@code null} when this game has no
     *         command-driven SEGA PCM path or the ROM is unavailable
     * @throws java.io.IOException if the described ROM span cannot be read
     */
    default byte[] loadSegaPcm(Object rom) throws java.io.IOException {
        return null;
    }

    /** How speed shoes affect music playback. Default: TEMPO_SWAP (S1/S2). */
    default SpeedMode getSpeedMode() {
        return SpeedMode.TEMPO_SWAP;
    }

    /** S3K speed multiplier value. Default 0x08 means ~1.25x speed. */
    default int getSpeedMultiplierValue() {
        return 0x08;
    }

    /**
     * Returns the GameSound to game-specific SFX ID mapping.
     * Used by the game class to configure AudioManager's sound dispatch.
     *
     * @return unmodifiable map of GameSound enum values to native SFX IDs
     */
    Map<GameSound, Integer> getSoundMap();

    /**
     * Returns the GameMusic to game-specific music ID mapping.
     * Used for shared gameplay cues that are music/jingles rather than SFX.
     */
    default Map<GameMusic, Integer> getMusicMap() {
        return Map.of();
    }
}
