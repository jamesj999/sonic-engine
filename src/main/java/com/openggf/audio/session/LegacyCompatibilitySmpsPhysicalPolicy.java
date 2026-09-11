package com.openggf.audio.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Physical policy matching the pre-session standalone synthesizer behavior.
 */
public final class LegacyCompatibilitySmpsPhysicalPolicy
        implements SmpsPhysicalPolicy {
    public static final LegacyCompatibilitySmpsPhysicalPolicy INSTANCE =
            new LegacyCompatibilitySmpsPhysicalPolicy();

    private static final Identity IDENTITY =
            new Identity("legacy-compatibility-v1");
    private static final SmpsWriteProgram SILENCE = legacySilenceProgram();
    private static final SmpsWriteProgram ACTIVATE_MUSIC =
            new SmpsWriteProgram(List.of(
                    new SmpsChipWrite.Ym2612(0, 0x2B, 0x80)));

    public LegacyCompatibilitySmpsPhysicalPolicy() {
    }

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public SmpsWriteProgram boot() {
        return SILENCE;
    }

    @Override
    public SmpsWriteProgram stopAll() {
        return SILENCE;
    }

    @Override
    public SmpsWriteProgram activateMusic(
            SmpsMusicActivation activation) {
        Objects.requireNonNull(activation, "activation");
        return ACTIVATE_MUSIC;
    }

    private static SmpsWriteProgram legacySilenceProgram() {
        List<SmpsChipWrite> writes = new ArrayList<>(202);
        for (int channel : new int[] {0x00, 0x04, 0x01, 0x05, 0x02, 0x06}) {
            writes.add(new SmpsChipWrite.Ym2612(0, 0x28, channel));
        }
        for (int register = 0x30; register < 0x90; register++) {
            writes.add(new SmpsChipWrite.Ym2612(0, register, 0xFF));
            writes.add(new SmpsChipWrite.Ym2612(1, register, 0xFF));
        }
        writes.add(new SmpsChipWrite.Psg(0x9F));
        writes.add(new SmpsChipWrite.Psg(0xBF));
        writes.add(new SmpsChipWrite.Psg(0xDF));
        writes.add(new SmpsChipWrite.Psg(0xFF));
        return new SmpsWriteProgram(writes);
    }
}
