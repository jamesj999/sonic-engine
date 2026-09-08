package com.openggf.audio.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ROM's blocking SEGA PCM transport, described as physical work.
 *
 * <p>S3K's {@code zPlaySEGAPCM} (Sound/Z80 Sound Driver.asm:4372-4424) runs
 * with {@code di} for its whole duration: it enables the DAC with
 * {@code 2Bh = 80h}, latches {@code 2Ah} once, then sends one byte of
 * {@code SEGA_PCM} per loop iteration until the sample is exhausted or
 * {@code cmd_StopSEGA} appears in {@code zMusicNumber}. Because interrupts
 * stay masked the V-ints of the whole transport are missed, so the entire
 * byte stream belongs to one service window. Leaving the loop jumps back
 * into {@code zPlayDigitalAudio}, whose entry writes {@code 2Bh = 0}
 * (:4256-4260); that write is this transport's exit.
 *
 * <p>S2's {@code zPlaySegaSound} (s2.sounddriver.asm:1603-1652) and S1's
 * {@code zPlay_SegaPCM} (sound/z80.asm:187-206) are the same shape with
 * different brackets and loop cycle counts, so this record is the shared
 * vocabulary rather than an S3K type; a driver whose policy does not supply
 * one keeps whatever mechanism it already uses.
 *
 * @param enter          writes performed before the first sample byte
 * @param dataPort       YM2612 port carrying the sample bytes
 * @param dataRegister   YM2612 register carrying the sample bytes
 * @param exit           writes performed on leaving the loop
 * @param sampleRate     the ROM sample's declared rate, in Hz
 * @param loopBaseCycles Z80 cycles the loop spends per byte before its
 *                       {@code djnz} delay
 */
public record SmpsSegaPcmTransport(
        SmpsWriteProgram enter,
        int dataPort,
        int dataRegister,
        SmpsWriteProgram exit,
        int sampleRate,
        int loopBaseCycles) {

    /** {@code Z80_Clock = Master_Clock/15} (sonic3k.constants.asm:202-204). */
    public static final int Z80_CLOCK_HZ = 53_693_175 / 15;

    /** The {@code djnz} cycle cost the ROM's loop-counter macro divides by. */
    private static final int DJNZ_TAKEN_CYCLES = 13;

    public SmpsSegaPcmTransport {
        Objects.requireNonNull(enter, "enter");
        Objects.requireNonNull(exit, "exit");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (loopBaseCycles <= 0) {
            throw new IllegalArgumentException(
                    "loopBaseCycles must be positive");
        }
    }

    /**
     * The ROM's {@code pcmLoopCounterBase} macro
     * (sonic3k.macros.asm:270-271), evaluated with this transport's rate and
     * loop cost. Integer division throughout, as the assembler does it.
     */
    public int loopCounter() {
        return 1 + (Z80_CLOCK_HZ / sampleRate - loopBaseCycles
                + DJNZ_TAKEN_CYCLES / 2) / DJNZ_TAKEN_CYCLES;
    }

    /** Z80 cycles the loop spends delivering one sample byte. */
    public int z80CyclesPerByte() {
        return loopBaseCycles + DJNZ_TAKEN_CYCLES * (loopCounter() - 1);
    }

    /**
     * The ordered write program for {@code pcm} with the default exit:
     * the enter block, one write per byte, then the exit block. A driver
     * restoring a music-dependent DAC disposition resolves its final block
     * through {@link SmpsPhysicalPolicy#exitSegaPcmTransport(int)}.
     */
    public SmpsWriteProgram program(byte[] pcm) {
        Objects.requireNonNull(pcm, "pcm");
        List<SmpsChipWrite> writes =
                new ArrayList<>(enter.writes().size() + pcm.length
                        + exit.writes().size());
        writes.addAll(enter.writes());
        for (byte value : pcm) {
            writes.add(new SmpsChipWrite.Ym2612(
                    dataPort, dataRegister, value & 0xFF));
        }
        writes.addAll(exit.writes());
        return new SmpsWriteProgram(writes);
    }
}
