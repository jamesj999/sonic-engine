package com.openggf.audio.session;

import java.util.Objects;

/**
 * Host-owned extension point for stateful SMPS commands.
 *
 * <p>Programs describe content; they do not choose host operations.  The
 * session installs this policy from its profile configuration so a host
 * command implementation can consume an immutable projection and return an
 * operation without inspecting a game name or donor source.
 */
public interface SmpsStatefulCommandPolicy {
    SmpsStatefulCommandPolicy NONE = new SmpsStatefulCommandPolicy() {
        @Override
        public Identity identity() {
            return Identity.NONE;
        }
    };

    Identity identity();

    /** Blocks new SFX during an override, releasing them at music restoration. */
    default boolean suppressesSfxDuringOverride() {
        return false;
    }

    /**
     * Whether {@link #suppressesSfxDuringOverride()} lifts at the restore
     * itself, or only once the restored song's fade in has completed.
     *
     * <p>S3K clears {@code zFadeToPrevFlag} before the restoration fade
     * starts, so its block ends at the restore. S1 and S2 replace one flag
     * with another: {@code cfFadeInToPrevious} clears {@code f_1up_playing}
     * and sets {@code f_fadein_flag} (s1.sounddriver.asm:2220-2222,
     * s2.sounddriver.asm:3150-3155), the SFX entry refuses while either is
     * set (s1:978-982, s2:2118-2120), and only the fade-in stepper clears the
     * second flag when its counter reaches zero (s1:1650, s2:2740).
     */
    default boolean releasesSfxSuppressionAtRestore() {
        return true;
    }

    /**
     * Whether starting an override stops the SFX already playing. S1's
     * extra-life branch clears the playing bit on every SFX track before it
     * backs the driver up (s1.sounddriver.asm:769-774); S2 reaches the same
     * result for every song through {@code zStopSoundEffects}, which the
     * request path already models, and S3K does not stop them.
     */
    default boolean stopsSfxWhenOverrideStarts() {
        return false;
    }

    /** State and physical writes owned by the host's music-fade command. */
    default SmpsFadeOutEffects fadeOutEffects() {
        return SmpsFadeOutEffects.NONE;
    }

    /** Whether the terminal driver-owned fade step invokes the host's global stop. */
    default boolean fadeOutCompletesWithGlobalStop() {
        return false;
    }

    /**
     * Hosts without a stateful operation retain their established behavior.
     */
    default SmpsStatefulCommandOperation prepare(
            SmpsStatefulCommandOperation.Input input) {
        return SmpsStatefulCommandOperation.none(
                Objects.requireNonNull(input, "input"));
    }

    record Identity(String value) {
        public static final Identity NONE = new Identity("none");

        public Identity {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "stateful-command policy identity is required");
            }
        }
    }
}
