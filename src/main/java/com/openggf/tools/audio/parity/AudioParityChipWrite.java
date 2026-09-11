package com.openggf.tools.audio.parity;

import java.util.Objects;

/** One decoded chip transaction in execution order. */
public record AudioParityChipWrite(String chip, Integer port, Integer register, int value) {
    public AudioParityChipWrite {
        Objects.requireNonNull(chip, "chip");
        byteRange(value, "value");
        switch (chip) {
            case "ym2612" -> {
                if (port == null || (port != 0 && port != 1)) {
                    throw new IllegalArgumentException("YM port must be 0 or 1");
                }
                if (register == null) {
                    throw new IllegalArgumentException("YM register is required");
                }
                byteRange(register, "register");
            }
            case "psg" -> {
                if (port != null || register != null) {
                    throw new IllegalArgumentException("PSG writes cannot have port or register fields");
                }
            }
            default -> throw new IllegalArgumentException("unknown chip: " + chip);
        }
    }

    public static AudioParityChipWrite ym2612(int port, int register, int value) {
        return new AudioParityChipWrite("ym2612", port, register, value);
    }

    public static AudioParityChipWrite psg(int value) {
        return new AudioParityChipWrite("psg", null, null, value);
    }

    static int byteRange(int value, String field) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException(field + " must be an unsigned byte");
        }
        return value;
    }
}
