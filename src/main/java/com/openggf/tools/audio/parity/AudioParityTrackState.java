package com.openggf.tools.audio.parity;

import java.util.List;
import java.util.Objects;

/** Immutable normalized state for one fixed S1 music slot. */
public record AudioParityTrackState(
        String role,
        String hardware,
        boolean active,
        Integer baseFrequency,
        Integer detune,
        Boolean doNotAttack,
        Integer duration,
        Integer durationReload,
        Integer envelopeCursor,
        List<Integer> loopCounters,
        Boolean modulationEnabled,
        Boolean overridden,
        Integer pan,
        Integer ams,
        Integer fms,
        List<Long> returnStack,
        Integer sequencePosition,
        Integer transpose,
        Integer voiceOrEnvelope,
        Integer volume) {

    public AudioParityTrackState {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(hardware, "hardware");
        if (!AudioParitySchema.ROLES.contains(role)) {
            throw new IllegalArgumentException("unknown fixed role: " + role);
        }
        if (!hardware.equals(AudioParitySchema.HARDWARE_BY_ROLE.get(role))) {
            throw new IllegalArgumentException("hardware does not match fixed role " + role);
        }
        loopCounters = loopCounters == null ? null : List.copyOf(loopCounters);
        returnStack = returnStack == null ? null : List.copyOf(returnStack);
        if (!active) {
            if (baseFrequency != null || detune != null || doNotAttack != null || duration != null
                    || durationReload != null || envelopeCursor != null || loopCounters != null
                    || modulationEnabled != null || overridden != null || pan != null || ams != null
                    || fms != null || returnStack != null || sequencePosition != null || transpose != null
                    || voiceOrEnvelope != null || volume != null) {
                throw new IllegalArgumentException("inactive role may contain only active, hardware, and role");
            }
        } else {
            boolean dac = role.equals("DAC");
            if (dac) {
                if (baseFrequency != null) {
                    throw new IllegalArgumentException("active DAC role cannot contain baseFrequency");
                }
            } else {
                require(baseFrequency, "baseFrequency");
            }
            require(detune, "detune");
            require(doNotAttack, "doNotAttack");
            require(duration, "duration");
            require(durationReload, "durationReload");
            require(loopCounters, "loopCounters");
            require(modulationEnabled, "modulationEnabled");
            require(overridden, "overridden");
            require(returnStack, "returnStack");
            require(sequencePosition, "sequencePosition");
            require(transpose, "transpose");
            require(voiceOrEnvelope, "voiceOrEnvelope");
            require(volume, "volume");
            if (baseFrequency != null) {
                wordRange(baseFrequency, "baseFrequency");
            }
            signedByte(detune, "detune");
            AudioParityChipWrite.byteRange(duration, "duration");
            AudioParityChipWrite.byteRange(durationReload, "durationReload");
            if (sequencePosition < 0) {
                throw new IllegalArgumentException("sequencePosition must be non-negative");
            }
            signedByte(transpose, "transpose");
            AudioParityChipWrite.byteRange(voiceOrEnvelope, "voiceOrEnvelope");
            signedByte(volume, "volume");
            loopCounters.forEach(value -> AudioParityChipWrite.byteRange(value, "loopCounters"));
            returnStack.forEach(value -> {
                if (value == null || value < 0 || value > 0xffff_ffffL) {
                    throw new IllegalArgumentException("returnStack entries must be unsigned longwords");
                }
            });
            boolean psg = role.startsWith("PSG");
            if (psg) {
                require(envelopeCursor, "envelopeCursor");
                AudioParityChipWrite.byteRange(envelopeCursor, "envelopeCursor");
                if (pan != null || ams != null || fms != null) {
                    throw new IllegalArgumentException("active PSG role cannot contain FM pan/AMS/FMS fields");
                }
            } else {
                require(pan, "pan");
                require(ams, "ams");
                require(fms, "fms");
                if (envelopeCursor != null) {
                    throw new IllegalArgumentException("active FM/DAC role cannot contain envelopeCursor");
                }
                AudioParityChipWrite.byteRange(pan, "pan");
                if ((pan & 0x3f) != 0) {
                    throw new IllegalArgumentException("pan must contain only the two YM pan bits");
                }
                range(ams, 0, 3, "ams");
                range(fms, 0, 7, "fms");
            }
        }
    }

    public static AudioParityTrackState inactive(String role) {
        return new AudioParityTrackState(role, AudioParitySchema.HARDWARE_BY_ROLE.get(role), false,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static void signedByte(int value, String field) {
        range(value, -128, 127, field);
    }

    private static void wordRange(int value, String field) {
        range(value, 0, 0xffff, field);
    }

    private static void range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is out of range");
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required for an active role");
        }
        return value;
    }
}
