package com.openggf.audio;

import com.openggf.audio.smps.SmpsSequencer;

/** Device/session settings shared by legacy and shadow SMPS construction. */
public record AudioPresentationTuning(
        SmpsSequencer.Region region,
        boolean dacInterpolate,
        boolean fm6DacOff) {
    public static final AudioPresentationTuning DEFAULT =
            new AudioPresentationTuning(
                    SmpsSequencer.Region.NTSC, false, false);
}
