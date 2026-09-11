package com.openggf.game.audio;

import com.openggf.audio.SegaPcmSpec;
import com.openggf.data.Rom;

import java.io.IOException;

/**
 * Reads the boot SEGA PCM chant out of a user-supplied ROM on behalf of the
 * per-game audio profiles.
 *
 * <p>The audio layer describes the sample with a {@link SegaPcmSpec} but must
 * not read ROM bytes itself: {@code TestArchUnitRules}'s layering rule keeps
 * {@code com.openggf.audio} free of {@code com.openggf.data} edges so the audio
 * services stay reusable. The game profiles already live in the runtime layer
 * and may depend on both, so the read belongs here.
 */
public final class SegaPcmRomReader {

    private SegaPcmRomReader() {
    }

    /**
     * Reads the span the spec describes.
     *
     * @param rom  the active ROM value, as the audio layer holds it
     * @param spec the game's SEGA PCM span, or {@code null} when it has none
     * @return the sample bytes, or {@code null} when no ROM or span is present
     * @throws IOException if the ROM span cannot be read
     */
    public static byte[] read(Object rom, SegaPcmSpec spec) throws IOException {
        if (spec == null || !(rom instanceof Rom romValue)) {
            return null;
        }
        return romValue.readBytes(spec.address(), spec.length());
    }
}
