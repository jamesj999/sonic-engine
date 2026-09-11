package com.openggf.game.sonic1.audio;

import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsChipWrite;
import com.openggf.audio.session.SmpsMusicActivation;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsSegaPcmTransport;
import com.openggf.audio.session.SmpsWriteProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** S1 host policy for shipped music-load and compatibility stop programs. */
public final class Sonic1SmpsCompatibilityPolicy
        implements SmpsPhysicalPolicy {
    public static final Sonic1SmpsCompatibilityPolicy INSTANCE =
            new Sonic1SmpsCompatibilityPolicy();

    private static final Identity IDENTITY =
            new Identity("sonic1-compatibility-v2");
    private static final LegacyCompatibilitySmpsPhysicalPolicy DELEGATE =
            LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
    private static final SmpsWriteProgram ACTIVATE_MUSIC =
            initMusicPlaybackSilenceProgram();

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
        Objects.requireNonNull(activation, "activation");
        return ACTIVATE_MUSIC;
    }

    private static SmpsWriteProgram initMusicPlaybackSilenceProgram() {
        // Sound_PlayBGM calls InitMusicPlayback. With the shipped FixBugs=0,
        // that routine calls FMSilenceAll and PSGSilenceAll even when SFX own
        // channels. Logical SFX ownership remains in the session, matching the
        // ROM's separate track RAM while preventing the previous song hanging.
        List<SmpsChipWrite> writes = new ArrayList<>(34);
        for (int channel : new int[] {2, 6, 1, 5, 0, 4}) {
            writes.add(new SmpsChipWrite.Ym2612(0, 0x28, channel));
        }
        for (int channel = 0; channel < 3; channel++) {
            for (int operator = 0; operator < 4; operator++) {
                int register = 0x40 + channel + operator * 4;
                writes.add(new SmpsChipWrite.Ym2612(
                        0, register, 0x7F));
                writes.add(new SmpsChipWrite.Ym2612(
                        1, register, 0x7F));
            }
        }
        writes.add(new SmpsChipWrite.Psg(0x9F));
        writes.add(new SmpsChipWrite.Psg(0xBF));
        writes.add(new SmpsChipWrite.Psg(0xDF));
        writes.add(new SmpsChipWrite.Psg(0xFF));
        return new SmpsWriteProgram(writes);
    }
}
