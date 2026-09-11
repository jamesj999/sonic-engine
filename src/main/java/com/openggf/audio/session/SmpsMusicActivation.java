package com.openggf.audio.session;

import com.openggf.audio.rewind.SmpsSourceDescriptor;

import java.util.Objects;

public record SmpsMusicActivation(
        SmpsSourceDescriptor source,
        int fmDacTrackCount,
        int psgTrackCount) {
    public SmpsMusicActivation {
        Objects.requireNonNull(source, "source");
        if (fmDacTrackCount < 0) {
            throw new IllegalArgumentException(
                    "fmDacTrackCount must be non-negative");
        }
        if (psgTrackCount < 0) {
            throw new IllegalArgumentException(
                    "psgTrackCount must be non-negative");
        }
    }

    /** An activation whose PSG track count is not carried by the caller. */
    public SmpsMusicActivation(SmpsSourceDescriptor source,
            int fmDacTrackCount) {
        this(source, fmDacTrackCount, 0);
    }
}
