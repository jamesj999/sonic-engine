package com.openggf.audio.session;

import java.util.Objects;
import java.util.Optional;

public interface SmpsPhysicalPolicy {
    record Identity(String value) {
        public Identity {
            value = Objects.requireNonNull(value, "value");
        }
    }

    Identity identity();

    SmpsWriteProgram boot();

    SmpsWriteProgram stopAll();

    /**
     * ROM work performed on entry to the driver's DAC idle loop, after the
     * one-shot driver init has completed.
     *
     * <p>S3K's {@code zInitAudioDriver} ends with {@code ei} and
     * {@code jp zPlayDigitalAudio} (Sound/Z80 Sound Driver.asm:550-551), so
     * the init service's own last write is {@code zStopAllSound}'s 27h.
     * Whatever {@code zPlayDigitalAudio} writes as it is entered belongs to
     * the following service window, not to the init. Drivers without a
     * distinct entry block return an empty program.</p>
     */
    default SmpsWriteProgram enterDacIdleLoop() {
        return SmpsWriteProgram.EMPTY;
    }

    /**
     * ROM work performed by the DAC idle loop once a sample has been queued.
     *
     * <p>S3K's {@code zPlayDigitalAudio} spins in {@code .dac_idle_loop}
     * reading {@code zDACIndex}, and the moment that index is non-zero it
     * writes 2Bh = 80h to enable the DAC before decoding the sample
     * (Sound/Z80 Sound Driver.asm:4264-4276). The index is set by the DAC
     * track's own update inside a V-int service, so the enable is emitted by
     * the idle loop the service returns to, not by the service itself.
     * Drivers whose DAC enable belongs elsewhere return an empty program.</p>
     */
    default SmpsWriteProgram enableDacFromIdleLoop() {
        return SmpsWriteProgram.EMPTY;
    }

    /**
     * The driver's blocking SEGA PCM transport, when the driver owns it.
     *
     * <p>S3K streams the chant itself in {@code zPlaySEGAPCM}
     * (Sound/Z80 Sound Driver.asm:4372-4424), so its policy describes the
     * transport and the session plays it through the chip's DAC. A policy
     * that returns {@link Optional#empty()} keeps whatever mechanism its
     * game already uses for the SEGA screen.</p>
     */
    default Optional<SmpsSegaPcmTransport> segaPcmTransport() {
        return Optional.empty();
    }

    /** Whether a new sound request makes the PCM loop leave its queue poll. */
    default boolean segaPcmInterruptedByRequest() {
        return false;
    }

    /**
     * Writes on PCM completion. The loaded music header carries the FM6/DAC
     * disposition; zero denotes a driver with no loaded music.
     */
    default SmpsWriteProgram exitSegaPcmTransport(int musicFmDacTrackCount) {
        return segaPcmTransport().orElseThrow().exit();
    }

    /** ROM work performed when a non-immediate music load begins. */
    default SmpsWriteProgram beginMusicLoad() {
        return SmpsWriteProgram.EMPTY;
    }

    /** Transiently silences all three tone channels and the noise channel. */
    default SmpsWriteProgram silenceAllPsg() {
        return SmpsWriteProgram.SILENCE_ALL_PSG;
    }

    SmpsWriteProgram activateMusic(SmpsMusicActivation activation);
}
