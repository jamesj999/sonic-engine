package com.openggf.audio.smps;

/**
 * Exposes sequencer internals to {@link CoordFlagHandler} implementations
 * without making private methods public on {@code SmpsSequencer} itself.
 *
 * <p>Implemented by {@code SmpsSequencer}. Game-specific flag handlers receive
 * this interface so they can manipulate track state, load voices/envelopes,
 * read pointers, manage tempo, and write directly to the synth when needed.
 */
public interface CoordFlagContext {

    // -----------------------------------------------------------------------
    // Data / config access
    // -----------------------------------------------------------------------

    /** Allocation-free indexed access to the immutable SMPS program. */
    SmpsProgramView programView();

    /** The parsed SMPS header data. */
    AbstractSmpsData getSmpsData();

    /** Sequencer configuration. */
    SmpsSequencerConfig getConfig();

    // -----------------------------------------------------------------------
    // Track operations
    // -----------------------------------------------------------------------

    /** Load an FM voice (instrument) into the track. */
    void loadVoice(SmpsSequencer.Track t, int voiceId);

    /** Load a PSG envelope into the track. */
    void loadPsgEnvelope(SmpsSequencer.Track t, int envId);

    /** Stop the currently playing note on the track (key off / mute). */
    void stopNote(SmpsSequencer.Track t);

    /**
     * Emits the ROM driver's complete PSG stop transaction. Unlike an
     * ordinary track write, reaching this command owns the physical bus
     * operation even while another track retains logical channel ownership.
     */
    void stopPsgNoteWithDriverSilence(SmpsSequencer.Track t);

    /**
     * Hands one channel back to music from inside the track-end flag, which is
     * where S3K's {@code cfStopTrack} does it: after keying the SFX track off
     * it clears the overridden music track's bit and sends that track's FM
     * instrument, all before the music update of the same service
     * (skdisasm Sound/Z80 Sound Driver.asm:3059-3086). Routing it through the
     * post-service sweep instead puts the restore after the music's own
     * writes.
     */
    /**
     * Asks for the music beneath an override to come back, as
     * {@code cfFadeInToPrevious} does by storing {@code zFadeToPrevFlag} for
     * the main loop to act on (skdisasm Sound/Z80 Sound Driver.asm:3079-3082,
     * and the flag's own read at :659-666).
     *
     * <p>A handler must reach the restore through here rather than through the
     * global {@code AudioManager}. A coordination flag runs inside the
     * sequencer's service, which for the presentation path runs inside an
     * active presentation command batch, and that batch refuses a command
     * submitted into it. The sequencer's injected sink is the one wired to
     * defer the restore to the batch boundary; the singleton's is not, and a
     * flag that calls it loses the restore to a swallowed exception.
     */
    default void restorePreviousMusic() {
    }

    default void releaseChannelToMusic(SmpsSequencer.Track endingTrack) {
    }

    /** Refresh the track's volume (re-apply TL for FM, attenuation for PSG). */
    void refreshVolume(SmpsSequencer.Track t);

    /** Refresh the track's instrument (re-send all voice registers). */
    void refreshInstrument(SmpsSequencer.Track t);

    // -----------------------------------------------------------------------
    // Pointer operations
    // -----------------------------------------------------------------------

    /**
     * Read a jump/loop/call pointer from the track data, handling both
     * PC-relative (S1) and absolute Z80 (S2/S3K) addressing modes.
     *
     * @return the resolved data offset, or -1 if invalid
     */
    int readJumpPointer(SmpsSequencer.Track t);

    // -----------------------------------------------------------------------
    // Tempo / timing
    // -----------------------------------------------------------------------

    /** Set the normal (base) tempo value. */
    void setNormalTempo(int tempo);

    /** Get the current normal (base) tempo value. */
    int getNormalTempo();

    /** Recalculate the effective tempo weight from current settings. */
    void recalculateTempo();

    /** Update the dividing timing (tick multiplier) for all tracks. */
    void updateDividingTiming(int newDividingTiming);

    // -----------------------------------------------------------------------
    // Modulation
    // -----------------------------------------------------------------------

    /** Clear all modulation state on the track. */
    void clearModulation(SmpsSequencer.Track t);

    // -----------------------------------------------------------------------
    // Fade
    // -----------------------------------------------------------------------

    /** Trigger a fade-in effect. */
    void triggerFadeIn();

    /** Trigger a fade-out effect. */
    void triggerFadeOut(int steps, int delay);

    // -----------------------------------------------------------------------
    // Communication byte
    // -----------------------------------------------------------------------

    /** Set the communication data byte (E2 flag in S2). */
    void setCommData(int value);

    /** Get the communication data byte. */
    int getCommData();

    // -----------------------------------------------------------------------
    // Synth access (for direct hardware writes)
    // -----------------------------------------------------------------------

    /** Write an FM register value. */
    void writeFm(int port, int reg, int value);

    /** Write a PSG byte. */
    void writePsg(int value);

    /** Play a DAC sample by note ID. */
    void playDac(int noteId);

    /** Stop DAC playback. */
    void stopDac();

    // -----------------------------------------------------------------------
    // Continuous SFX (S3K cfLoopContinuousSFX support)
    // -----------------------------------------------------------------------

    /** Returns true if the continuous SFX extension flag is set (Z80: zContinuousSFXFlag == 0x80). */
    default boolean isContinuousSfxFlagSet() {
        return false;
    }

    /** Clear the continuous SFX ID (Z80: zContinuousSFX = 0). */
    default void clearContinuousSfxId() {
    }

    /** Clear the continuous SFX extension flag (Z80: zContinuousSFXFlag = 0). */
    default void clearContinuousSfxFlag() {
    }

    /**
     * Decrement the continuous SFX loop counter (Z80: zContSFXLoopCnt--).
     * @return true if the counter reached zero (all tracks have looped)
     */
    default boolean decrementContSfxLoopCnt() {
        return true;
    }
}
