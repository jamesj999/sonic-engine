package com.openggf.game.sonic3k.audio;

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

/** Shipped Sonic 3 &amp; Knuckles Z80 physical-device policy. */
public final class Sonic3kSmpsPhysicalPolicy
        implements SmpsPhysicalPolicy {
    public static final Sonic3kSmpsPhysicalPolicy INSTANCE =
            new Sonic3kSmpsPhysicalPolicy();

    private static final Identity IDENTITY =
            new Identity("sonic3k-shipped-v1");
    private static final SmpsWriteProgram STOP_ALL = stopAllProgram();
    private static final SmpsWriteProgram BOOT = STOP_ALL;
    private static final SmpsWriteProgram DAC_IDLE_ENTRY = dacIdleEntryProgram();
    private static final SmpsWriteProgram DAC_IDLE_ENABLE = dacIdleEnableProgram();
    private static final SmpsWriteProgram BGM_LOAD = bgmLoadProgram();
    private static final SmpsSegaPcmTransport SEGA_PCM = segaPcmTransportProgram();

    private Sonic3kSmpsPhysicalPolicy() {
    }

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public SmpsWriteProgram boot() {
        return BOOT;
    }

    @Override
    public SmpsWriteProgram stopAll() {
        return STOP_ALL;
    }

    @Override
    public SmpsWriteProgram enterDacIdleLoop() {
        return DAC_IDLE_ENTRY;
    }

    @Override
    public SmpsWriteProgram enableDacFromIdleLoop() {
        return DAC_IDLE_ENABLE;
    }

    @Override
    public Optional<SmpsSegaPcmTransport> segaPcmTransport() {
        return Optional.of(SEGA_PCM);
    }

    @Override
    public SmpsWriteProgram silenceAllPsg() {
        // Sound/Z80 Sound Driver.asm:zPlaySoundByIndex/zFadeEffects routes
        // E3h to zPSGSilenceAll, whose four iterations add 20h to 9Fh.
        return SmpsWriteProgram.SILENCE_ALL_PSG;
    }

    @Override
    public SmpsWriteProgram activateMusic(SmpsMusicActivation activation) {
        Objects.requireNonNull(activation, "activation");
        return BGM_LOAD;
    }

    private static SmpsWriteProgram bgmLoadProgram() {
        // Sound/Z80 Sound Driver.asm:zBGMLoad. Between the song bank switch
        // and the track loops it writes exactly one hardware register
        // (:1811-1816): 0B6h through the port 1 address/data pair, value 0C0h,
        // the default panning with only stereo L and R enabled. 0B6h on port 1
        // is FM6's AMS/FMS/panning, the channel FM6/DAC shares, and the load
        // resets it unconditionally for every song.
        //
        // The legacy activation this replaces wrote 2Bh=80h instead. zBGMLoad
        // never touches 2Bh; DAC enable belongs to zPlayDigitalAudio and the
        // DAC track update, which the engine already drives (SmpsDriver's own
        // 2Bh=80h on a DAC note), so nothing loses the DAC by this change.
        return new SmpsWriteProgram(
                List.of(new SmpsChipWrite.Ym2612(1, 0xB6, 0xC0)));
    }

    private static SmpsSegaPcmTransport segaPcmTransportProgram() {
        // Sound/Z80 Sound Driver.asm:zPlaySEGAPCM (:4372-4424). SonicDriverVer
        // is 4 for S&K (sonic3k.asm:27), so the SonicDriverVer==3 queue work is
        // assembled out and the loop's writes are exactly: 2Bh=80h, one latch
        // of 2Ah, then one byte of SEGA_PCM per iteration. Leaving the loop
        // re-enters zPlayDigitalAudio, which writes 2Bh=0 (:4256-4260).
        // 105 is the loop's own per-byte cycle cost, the base the ROM's
        // pcmLoopCounter macro is defined with (sonic3k.macros.asm:270-271).
        return new SmpsSegaPcmTransport(
                new SmpsWriteProgram(
                        List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x80))),
                0,
                0x2A,
                new SmpsWriteProgram(
                        List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x00))),
                Sonic3kSmpsConstants.SEGA_SOUND_SAMPLE_RATE,
                105);
    }

    private static SmpsWriteProgram dacIdleEntryProgram() {
        // Sound/Z80 Sound Driver.asm:zPlayDigitalAudio (:4256-4260) opens with
        // di / ld a,2Bh / ld c,0 / call zWriteFMI, disabling the DAC once more.
        // zInitAudioDriver reaches it by jp after ei (:550-551) and never
        // returns, so this write is the first work of the service that follows
        // the init, not part of the init itself. zStopAllSound's own 27h
        // (:2513-2519) is therefore the init's last write.
        return new SmpsWriteProgram(
                List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x00)));
    }

    private static SmpsWriteProgram dacIdleEnableProgram() {
        // Sound/Z80 Sound Driver.asm:zPlayDigitalAudio .dac_idle_loop
        // (:4264-4276). The loop reads zDACIndex every pass and, on finding it
        // non-zero, does ld a,2Bh / ld c,80h / di / call zWriteFMI before
        // decoding. zDACIndex is written by the DAC track's update during a
        // V-int service (:2896-2903), so the enable belongs to the idle loop
        // the service returns to and opens the next service's window.
        return new SmpsWriteProgram(
                List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x80)));
    }

    private static SmpsWriteProgram stopAllProgram() {
        List<SmpsChipWrite> writes = new ArrayList<>(84);
        // Sound/Z80 Sound Driver.asm:zStopAllSound, pinned skdisasm
        // 044fa467, fix_sndbugs=0. The shipped branch calls
        // zFMSilenceChannel; the fixed branch would inline a safer key-off.
        for (int channel : new int[] {6, 0, 1, 2, 4, 5}) {
            int port = (channel & 4) == 0 ? 0 : 1;
            int offset = channel & 3;
            for (int register = 0x80;
                    register <= 0x8C; register += 4) {
                writes.add(new SmpsChipWrite.Ym2612(
                        port, register + offset, 0xFF));
            }
            for (int register = 0x40;
                    register <= 0x4C; register += 4) {
                writes.add(new SmpsChipWrite.Ym2612(
                        port, register + offset, 0x7F));
            }
            writes.add(new SmpsChipWrite.Ym2612(
                    0, 0x28, channel));
            for (int register = 0x90;
                    register <= 0x9C; register += 4) {
                writes.add(new SmpsChipWrite.Ym2612(
                        port, register + offset, 0x00));
            }
        }
        writes.add(new SmpsChipWrite.Psg(0x9F));
        writes.add(new SmpsChipWrite.Psg(0xBF));
        writes.add(new SmpsChipWrite.Psg(0xDF));
        writes.add(new SmpsChipWrite.Psg(0xFF));
        writes.add(new SmpsChipWrite.Ym2612(0, 0x2B, 0x00));
        writes.add(new SmpsChipWrite.Ym2612(0, 0x27, 0x00));
        return new SmpsWriteProgram(writes);
    }
}
