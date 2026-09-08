package com.openggf.game.sonic1.audio;

import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsChipWrite;
import com.openggf.audio.session.SmpsMusicActivation;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsSegaPcmTransport;
import com.openggf.audio.session.SmpsWriteProgram;

import java.util.List;
import java.util.Optional;

/** Named S1 host policy retaining the verified pre-migration 202-write program. */
public final class Sonic1SmpsCompatibilityPolicy
        implements SmpsPhysicalPolicy {
    public static final Sonic1SmpsCompatibilityPolicy INSTANCE =
            new Sonic1SmpsCompatibilityPolicy();

    private static final Identity IDENTITY =
            new Identity("sonic1-compatibility-v1");
    private static final LegacyCompatibilitySmpsPhysicalPolicy DELEGATE =
            LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;

    private Sonic1SmpsCompatibilityPolicy() {
    }

    @Override
    public Optional<SmpsSegaPcmTransport> segaPcmTransport() {
        // S1 StopAllSound enables the DAC before the boot chant. Include that
        // prerequisite here while the host retains its compatibility boot.
        // zPlay_SegaPCM itself leaves the DAC enabled on returning to idle.
        // Verified REV01 Z80 bytes at 00C8: 06 0B (delay counter 11);
        // sound/z80.asm:zPlaySEGAPCMLoop costs 90 + 13*10 = 220 cycles.
        return Optional.of(SEGA_PCM);
    }

    private static final SmpsSegaPcmTransport SEGA_PCM = new SmpsSegaPcmTransport(
            new SmpsWriteProgram(List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x80))),
            0, 0x2A,
            SmpsWriteProgram.EMPTY,
            16_000, 90);

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public SmpsWriteProgram boot() {
        return DELEGATE.boot();
    }

    @Override
    public SmpsWriteProgram stopAll() {
        return DELEGATE.stopAll();
    }

    @Override
    public SmpsWriteProgram activateMusic(SmpsMusicActivation activation) {
        return DELEGATE.activateMusic(activation);
    }
}
