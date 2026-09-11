package com.openggf.audio.smps;

import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.synth.Synthesizer;

/**
 * Descriptor-aware SMPS write boundary. The descriptor identifies the DAC
 * source for session-owned physical-device admission while standalone targets
 * may deliberately ignore that provenance.
 */
public interface SmpsLogicalWriteTarget extends Synthesizer {
    /**
     * Writes one source-driver PSG frequency transaction. Physical targets
     * receive both bytes verbatim; an owning driver may arbitrate the pair at
     * the source track's single ROM gate rather than between bus bytes.
     */
    default void writePsgFrequencyPair(
            Object source, int latchByte, int followingByte) {
        writePsg(source, latchByte);
        writePsg(source, followingByte);
    }

    /**
     * Writes the driver's PSG track-stop transaction. The ROM routine owns
     * this physical bus operation independently of ordinary channel-write
     * arbitration: tone silence, an optional noise silence, then the retail
     * helper's unconditional final noise silence.
     */
    default void writePsgDriverSilence(
            Object source, int toneChannel, boolean noiseMode) {
        if (toneChannel < 0 || toneChannel >= 3) {
            throw new IllegalArgumentException("PSG driver silence requires a tone channel");
        }
        writePsg(source, 0x80 | (toneChannel << 5) | 0x1F);
        if (noiseMode) {
            writePsg(source, 0xFF);
        }
        writePsg(source, 0xFF);
    }

    void selectDac(SmpsSourceDescriptor source, DacData data);
}
