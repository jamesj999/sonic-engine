package com.openggf.tools.audio.parity.s3k;

import java.util.Objects;

/**
 * One zTrack struct's compared state (offsets from skdisasm
 * {@code Sound/Z80 Sound Driver.asm} D:21-96 via the routine map §2).
 *
 * <p>All integers are the raw unsigned byte/word values of the ROM struct.
 * Fields the engine cannot express are nullable and compared only when the
 * field registry marks them GATE on both sides.
 */
public record S3kAudioTrackState(
        String role,
        boolean playing,          // PlaybackControl bit 7
        Boolean overridden,       // PlaybackControl bit 2
        Boolean doNotAttack,      // PlaybackControl bit 1
        Boolean resting,          // PlaybackControl bit 4
        Integer voiceControl,     // offset 01
        Integer tempoDivider,     // offset 02
        Integer dataPointer,      // offsets 03-04 (Z80 address; engine: null)
        Integer transpose,        // offset 05, signed byte as -128..127
        Integer volume,           // offset 06, signed byte semantics
        Integer modulationCtrl,   // offset 07
        Integer voiceIndex,       // offset 08
        Integer amsFmsPan,        // offset 0A
        Integer durationTimeout,  // offset 0B
        Integer savedDuration,    // offset 0C
        Integer frequency,        // offsets 0D-0E as FreqHigh<<8|FreqLow (FM/PSG)
        Integer detune,           // offset 10, signed byte as -128..127
        Integer volEnv,           // offset 17
        Integer noteFillTimeout,  // offset 1E
        Integer noteFillMaster,   // offset 1F
        Integer modulationPtr,    // offsets 20-21 (Z80 address; engine: null)
        Integer modulationVal,    // offsets 22-23 as High<<8|Low, the accumulator
        Integer modulationWait,   // offset 24
        Integer modulationSpeed,  // offset 25, aliases ModEnvIndex
        Integer modulationDelta,  // offset 26
        Integer modulationSteps) {// offset 27

    public S3kAudioTrackState {
        Objects.requireNonNull(role, "role");
        if (!S3kAudioParitySchema.ROLES.contains(role)) {
            throw new IllegalArgumentException("unknown S3K track role: " + role);
        }
    }

    public static S3kAudioTrackState idle(String role) {
        return new S3kAudioTrackState(role, false, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
