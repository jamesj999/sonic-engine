package com.openggf.audio.session;

import com.openggf.audio.rewind.SmpsDriverSnapshot;

import java.util.Objects;

public record PreparedSmpsSfxProgram(
        SmpsDriverSnapshot.SequencerEntry incomingSfx,
        int continuousSfxId,
        int continuousTrackCount) {
    public PreparedSmpsSfxProgram {
        Objects.requireNonNull(incomingSfx, "incomingSfx");
        if (!incomingSfx.sfx()) {
            throw new IllegalArgumentException(
                    "SFX program requires an SFX sequencer");
        }
        if ((continuousSfxId & ~0xFF) != 0
                || (continuousTrackCount & ~0xFF) != 0) {
            throw new IllegalArgumentException(
                    "continuous SFX metadata must fit unsigned bytes");
        }
    }
}
