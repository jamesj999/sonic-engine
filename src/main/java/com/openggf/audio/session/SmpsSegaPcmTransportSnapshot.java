package com.openggf.audio.session;

import java.util.Objects;

/**
 * The rewind-visible state of an in-flight SEGA PCM transport: which byte of
 * the sample the Z80 loop is on, how much of the current byte's delay has
 * elapsed, and whether {@code cmd_StopSEGA} has already been seen in
 * {@code zMusicNumber} (Sound/Z80 Sound Driver.asm:4394-4397).
 *
 * <p>The sample bytes travel with the snapshot because they are the loop's
 * own ROM-read source data; the array is copied in and out.</p>
 */
public record SmpsSegaPcmTransportSnapshot(
        byte[] pcm,
        int cursor,
        long cycleAccumulator,
        boolean stopRequested) {
    public SmpsSegaPcmTransportSnapshot {
        pcm = Objects.requireNonNull(pcm, "pcm").clone();
        if (cursor < 0 || cursor > pcm.length) {
            throw new IllegalArgumentException(
                    "cursor must address the sample");
        }
        if (cycleAccumulator < 0) {
            throw new IllegalArgumentException(
                    "cycleAccumulator must not be negative");
        }
    }

    @Override
    public byte[] pcm() {
        return pcm.clone();
    }
}
