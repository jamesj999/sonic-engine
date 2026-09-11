package com.openggf.audio.smps;

/**
 * Allocation-free indexed access to an SMPS program.
 *
 * <p>The public {@link AbstractSmpsData} API remains available for metadata
 * inspection and compatibility. Sequencer internals use this view so frozen
 * program storage never has to expose or copy its backing arrays.</p>
 */
public interface SmpsProgramView {
    int dataLength();

    byte dataByteAt(int index);

    int fmPointerCount();

    int fmPointerAt(int index);

    int fmKeyOffsetAt(int index);

    int fmVolumeOffsetAt(int index);

    int psgPointerCount();

    int psgPointerAt(int index);

    int psgKeyOffsetAt(int index);

    int psgVolumeOffsetAt(int index);

    int psgModEnvelopeAt(int index);

    int psgInstrumentCount();

    int psgInstrumentAt(int index);

    int voiceLength(int voiceId);

    byte voiceByteAt(int voiceId, int index);

    int psgEnvelopeLength(int envelopeId);

    byte psgEnvelopeByteAt(int envelopeId, int index);

    int modEnvelopeLength(int envelopeId);

    byte modEnvelopeByteAt(int envelopeId, int index);
}
