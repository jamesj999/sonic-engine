package com.openggf.audio.presentation;

import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.smps.SmpsSequencerConfig;

import java.util.Objects;

/**
 * Hosts a creator-supplied streamed music override as an ordinary presentation
 * voice, so mod music participates in the presentation clock, history, reverse
 * cursor, capture leases, and rewind snapshots exactly like SMPS and sample
 * music do.
 *
 * <p>The logical playback state stays owned by the streamed presentation
 * session:
 * loop points, fade ramps, the pause mask, and speed-shoes tempo are all
 * port-side concepts with their own validation, and re-expressing them as a
 * plain Q32.32 source cursor (as {@link SampleBackedVoice} does) would drop
 * them. This voice is therefore an adapter over the port, not a
 * reimplementation of its state machine.
 *
 * <p>Like every other voice this is confined to the presentation thread, which
 * is the confinement the port already requires.
 */
public final class StreamedMusicVoice implements PcmPresentationVoice {

    /** Music voices mix at the base priority, matching {@code loopingMusic}. */
    private static final int MUSIC_PRIORITY = 0;

    private final long voiceId;
    private final int priority;
    private final StreamedPresentationSession.Cursor cursor;
    private final AudioSourceDescriptor sourceDescriptor;
    private final RestorePolicy restorePolicy;
    private short[] scratch = new short[0];
    private boolean stopped;

    StreamedMusicVoice(long voiceId, StreamedPresentationSession.Cursor cursor,
            AudioSourceDescriptor sourceDescriptor) {
        this(voiceId, cursor, sourceDescriptor, RestorePolicy.immediate());
    }

    StreamedMusicVoice(long voiceId, StreamedPresentationSession.Cursor cursor,
            AudioSourceDescriptor sourceDescriptor, RestorePolicy restorePolicy) {
        this(voiceId, MUSIC_PRIORITY, cursor, sourceDescriptor, restorePolicy);
    }

    private StreamedMusicVoice(long voiceId, int priority,
            StreamedPresentationSession.Cursor cursor,
            AudioSourceDescriptor sourceDescriptor,
            RestorePolicy restorePolicy) {
        this.voiceId = voiceId;
        this.priority = priority;
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.sourceDescriptor = Objects.requireNonNull(sourceDescriptor, "sourceDescriptor");
        this.restorePolicy = Objects.requireNonNull(restorePolicy, "restorePolicy");
    }

    /**
     * Rebuilds a streamed voice from a captured snapshot against the installed
     * port. A creator track's identity is its owner-scoped {@code TrackRef}, so
     * only the port that prepared it can vouch for the restore; a port that no
     * longer holds the track rejects it rather than resuming silence.
     */
    static StreamedMusicVoice restore(PresentationVoiceSnapshot.Streamed snapshot,
            StreamedPresentationSession.Cursor cursor,
            RestorePolicy restorePolicy) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new StreamedMusicVoice(snapshot.voiceId(), snapshot.priority(),
                Objects.requireNonNull(cursor, "cursor"),
                snapshot.sourceDescriptor(), restorePolicy);
    }

    @Override
    public long voiceId() {
        return voiceId;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public void mixInto(long[] accumulation, int stereoFrames) {
        Objects.requireNonNull(accumulation, "accumulation");
        if (stereoFrames < 0 || accumulation.length < (long) stereoFrames * 2) {
            throw new IllegalArgumentException("accumulation cannot hold requested stereo frames");
        }
        if (isComplete() || stereoFrames == 0) {
            return;
        }
        int samples = stereoFrames * 2;
        if (scratch.length < samples) {
            // Grown only when the packet size grows, never per frame: this is the
            // presentation hot path and the port's own mixInto is allocation-free.
            scratch = new short[samples];
        }
        int mixedFrames = cursor.mixInto(scratch, stereoFrames);
        for (int frame = 0; frame < mixedFrames; frame++) {
            int index = frame * 2;
            accumulation[index] += scratch[index];
            accumulation[index + 1] += scratch[index + 1];
        }
    }

    public void fadeOut(int steps, int delay) {
        cursor.fadeOut(steps, delay);
    }

    public void fadeIn(int steps, int delay) {
        cursor.fadeIn(steps, delay);
    }

    public void setSpeedMultiplier(int multiplier) {
        cursor.setSpeedMultiplier(multiplier);
    }

    void beginOverrideRestore() {
        if (restorePolicy.fadeIn()) {
            cursor.fadeIn(restorePolicy.steps(), restorePolicy.delay());
        }
    }

    boolean releasesSfxOnRestore() {
        return !restorePolicy.fadeIn()
                || restorePolicy.releaseOnRestore();
    }

    boolean restoreFadeComplete() {
        return !restorePolicy.fadeIn()
                || (!cursor.fadeActive() && cursor.fadeAtFullGain());
    }

    void retireCompleted() {
        if (cursor.isComplete()) {
            stopped = true;
            cursor.retire();
        }
    }

    /** Releases a descriptor-only cursor that was never published. */
    void retireUnpublished() {
        stopped = true;
        cursor.retire();
    }

    void restoreMutation(PresentationVoiceSnapshot.Streamed snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        cursor.restoreMutation(snapshot.playback(), snapshot.stopped());
        stopped = snapshot.stopped();
    }

    @Override
    public boolean isComplete() {
        return stopped || cursor.isComplete();
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        cursor.retire();
    }

    @Override
    public PresentationVoiceSnapshot snapshot() {
        return new PresentationVoiceSnapshot.Streamed(
                voiceId, priority, sourceDescriptor, cursor.snapshot(),
                stopped || cursor.stopped());
    }

    record RestorePolicy(
            boolean fadeIn,
            int steps,
            int delay,
            boolean releaseOnRestore) {
        RestorePolicy {
            if (fadeIn && (steps <= 0 || delay < 0)) {
                throw new IllegalArgumentException(
                        "invalid streamed restore fade");
            }
        }

        static RestorePolicy from(SmpsSequencerConfig config, boolean blockSfxDuringFade) {
            Objects.requireNonNull(config, "config");
            boolean fade = config.getFadeInSteps() > 0;
            return new RestorePolicy(fade,
                    fade ? config.getFadeInSteps() : 0,
                    fade ? config.getFadeInDelay() : 0,
                    !blockSfxDuringFade);
        }

        static RestorePolicy immediate() {
            return new RestorePolicy(false, 0, 0, true);
        }
    }
}
