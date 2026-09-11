package com.openggf.audio.synth.fast;

/**
 * Register-level YM2612 digital signal model behind the fast FM facade.
 *
 * <p>This is the seam between the licence-clean fast core and the engine. The
 * facade ({@code FastYm2612Chip}) owns everything that is not the chip's
 * synthesis: pending-write queuing, per-channel mute, L/R panning from the
 * {@code 0xB4..0xB6} registers, DAC sample streaming (delivered here as
 * ordinary {@code 0x2A} writes), output scaling and resampling, snapshots and
 * rewind. An implementation therefore only has to be a YM2612: registers in,
 * six channel outputs out.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #renderFrame(int[])} advances the chip by exactly one internal
 *       output frame (144 master clocks, {@code 7670453 / 144 ≈ 53267 Hz}) and
 *       writes the six channel outputs, before panning, into {@code out[0..5]}
 *       in the chip's native 14-bit range ({@code -8192..8191}). Channel 6
 *       carries the DAC sample while register {@code 0x2B} bit 7 is set. The
 *       call must not allocate.</li>
 *   <li>{@link #writeRegister(int, int, int)} applies a data byte to a register
 *       of port 0 or 1 immediately. Timers A/B, CSM, channel-3 special mode,
 *       LFO, SSG-EG and key on/off are register semantics and belong here.</li>
 *   <li>{@link #readStatus()} returns the status byte (timer overflow flags).</li>
 *   <li>{@link #copyStateTo(FmDsp)} makes {@code target} an exact state copy;
 *       {@link #newInstance()} creates a reset instance of the same class.
 *       Implementations must define {@code equals}/{@code hashCode} over their
 *       complete state so snapshots compare by value.</li>
 * </ul>
 *
 * <p>Provenance rule for implementations: public hardware documentation only
 * (datasheet, register-level references, published hardware-behaviour notes);
 * no code from GPL/LGPL/non-commercial emulator cores, including the in-tree
 * Nuked-OPN2 port, which remains the accurate core and the test oracle.
 */
public interface FmDsp {

    /** Channel count of the YM2612. */
    int CHANNELS = 6;

    /** Returns the chip to its power-on state. */
    void reset();

    /**
     * Applies one register write.
     *
     * @param port 0 for channels 1–3 and global registers, 1 for channels 4–6
     * @param register register address, 0x00–0xFF
     * @param value data byte, 0x00–0xFF
     */
    void writeRegister(int port, int register, int value);

    /**
     * Applies a write with its data-strobe position in the frame being rendered.
     * Implementations may use this to resolve a register's sampling boundary;
     * the default retains immediate register semantics.
     *
     * @param frameCycle internal-cycle offset, 0..24; 24 is the next frame's
     *                   boundary after an address strobe in cycle 23
     */
    default void writeRegister(int port, int register, int value, int frameCycle) {
        writeRegister(port, register, value);
    }

    /**
     * Advances one internal frame and writes the six pre-pan channel outputs.
     *
     * @param out at least {@link #CHANNELS} ints; entries 0..5 are written
     */
    void renderFrame(int[] out);

    /** Status byte: bit 0 timer A overflow, bit 1 timer B overflow. */
    int readStatus();

    /** Copies this chip's complete state into {@code target} (same class). */
    void copyStateTo(FmDsp target);

    /** A reset instance of the same implementation. */
    FmDsp newInstance();
}
