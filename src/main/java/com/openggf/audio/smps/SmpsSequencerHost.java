package com.openggf.audio.smps;

import com.openggf.audio.driver.SmpsDriverServiceObserver;

/**
 * Logical driver callbacks used by an SMPS sequencer independently of its
 * physical write target.
 */
public interface SmpsSequencerHost {
    SmpsSequencerHost NONE = NoOp.INSTANCE;

    /** Host command ownership, independent of the source song's driver configuration. */
    default boolean fadeOutCompletesWithGlobalStop() {
        return false;
    }

    SmpsDriverServiceObserver.ServiceEvent beginSequencerService(
            SmpsSequencer sequencer,
            SmpsDriverServiceObserver.ServiceKind kind);

    void endSequencerService(SmpsDriverServiceObserver.ServiceEvent event);

    void reconcileInactiveSfxTracks(SmpsSequencer sequencer);

    /** See {@link CoordFlagContext#releaseChannelToMusic}. */
    default void releaseChannelToMusic(SmpsSequencer sequencer,
            SmpsSequencer.Track endingTrack) {
    }

    /**
     * The driver's running fade delay, {@code zFadeDelay}, which the fade
     * steppers decrement and reload (skdisasm Sound/Z80 Sound
     * Driver.asm:2337-2346, :2405-2414). Only a driver whose config claims the
     * pair answers these; the song keeps its own copy otherwise.
     */
    default int fadeDelay() {
        return 0;
    }

    default void setFadeDelay(int value) {
    }

    /** The driver's fade delay reload source, {@code zFadeDelayTimeout}. */
    default int fadeDelayTimeout() {
        return 0;
    }

    default void setFadeDelayTimeout(int value) {
    }

    /**
     * The driver's fade step counters, {@code zFadeOutTimeout} (1C0Dh) and
     * {@code zFadeInTimeout} (1C29h). These are what the steppers test to
     * decide whether a fade is running at all, and they are driver variables
     * like the delay pair (skdisasm Sound/Z80 Sound Driver.asm:2306-2308,
     * :2331-2335, :2784-2786).
     */
    default int fadeStepCounter(boolean fadeOut) {
        return 0;
    }

    default void setFadeStepCounter(boolean fadeOut, int value) {
    }

    /**
     * Releases the channels of any inactive SFX track, including those of a
     * sequencer whose every track has now finished.
     *
     * <p>Used by the driver-coordinated SFX slot walk: S1 {@code cfStopTrack}
     * hands a channel back to music from inside the finishing track's own slot
     * service (s1.sounddriver.asm:2489-2563), whether or not the sound has
     * other tracks still playing.
     */
    default void reconcileFinishedSfxSlot(SmpsSequencer sequencer) {
        reconcileInactiveSfxTracks(sequencer);
    }

    byte[] s1SpecialSfxVoiceForBug(int voiceId);

    boolean isContinuousSfxFlagSet();

    void clearContinuousSfxId();

    void clearContinuousSfxFlag();

    boolean decrementContSfxLoopCnt();

    final class NoOp implements SmpsSequencerHost {
        private static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }

        @Override
        public SmpsDriverServiceObserver.ServiceEvent beginSequencerService(
                SmpsSequencer sequencer,
                SmpsDriverServiceObserver.ServiceKind kind) {
            return null;
        }

        @Override
        public void endSequencerService(
                SmpsDriverServiceObserver.ServiceEvent event) {
        }

        @Override
        public void reconcileInactiveSfxTracks(SmpsSequencer sequencer) {
        }

        @Override
        public byte[] s1SpecialSfxVoiceForBug(int voiceId) {
            return null;
        }

        @Override
        public boolean isContinuousSfxFlagSet() {
            return false;
        }

        @Override
        public void clearContinuousSfxId() {
        }

        @Override
        public void clearContinuousSfxFlag() {
        }

        @Override
        public boolean decrementContSfxLoopCnt() {
            return true;
        }
    }
}
