package com.openggf.audio.smps;

public abstract class AbstractSmpsData implements SmpsProgramView {
    protected final byte[] data;
    protected int voicePtr;
    protected int channels;
    protected int psgChannels;
    protected int dividingTiming;
    protected int tempo;
    protected int dacPointer;
    protected int[] fmPointers;
    protected int[] fmKeyOffsets;
    protected int[] fmVolumeOffsets;
    protected int[] psgPointers;
    protected int[] psgKeyOffsets;
    protected int[] psgVolumeOffsets;
    protected int[] psgModEnvs;
    protected int[] psgInstruments;
    protected int z80StartAddress = 0;
    protected int id;
    protected boolean palSpeedupDisabled;

    protected AbstractSmpsData(byte[] data, int z80StartAddress) {
        this.data = data;
        this.z80StartAddress = z80StartAddress;
        // Default initialization; subclasses must populate these fields.
        this.voicePtr = 0;
        this.channels = 0;
        this.psgChannels = 0;
        this.dividingTiming = 1;
        this.tempo = 0;
        this.dacPointer = 0;
        this.fmPointers = new int[0];
        this.fmKeyOffsets = new int[0];
        this.fmVolumeOffsets = new int[0];
        this.psgPointers = new int[0];
        this.psgKeyOffsets = new int[0];
        this.psgVolumeOffsets = new int[0];
        this.psgModEnvs = new int[0];
        this.psgInstruments = new int[0];

        parseHeader();
    }

    protected abstract void parseHeader();

    public abstract byte[] getVoice(int voiceId);

    public abstract byte[] getPsgEnvelope(int id);

    /**
     * Optional modulation envelope data lookup.
     * Games that do not use a dedicated modulation envelope table can keep the
     * default {@code null} behavior.
     */
    public byte[] getModEnvelope(int id) {
        return null;
    }

    public abstract int read16(int offset);

    public abstract int getBaseNoteOffset();

    /**
     * Some drivers (e.g., Sonic 2) use different base notes for FM vs PSG.
     * Defaults to the FM base note unless overridden.
     */
    public int getPsgBaseNoteOffset() {
        return getBaseNoteOffset();
    }

    public byte[] getData() {
        return data;
    }

    public int getVoicePtr() {
        return voicePtr;
    }

    public int getChannels() {
        return channels;
    }

    public int getPsgChannels() {
        return psgChannels;
    }

    public int getDividingTiming() {
        return dividingTiming;
    }

    public int getTempo() {
        return tempo;
    }

    public int getDacPointer() {
        return dacPointer;
    }

    public int[] getFmPointers() {
        return fmPointers;
    }

    public int[] getFmKeyOffsets() {
        return fmKeyOffsets;
    }

    public int[] getFmVolumeOffsets() {
        return fmVolumeOffsets;
    }

    public int[] getPsgPointers() {
        return psgPointers;
    }

    public int[] getPsgKeyOffsets() {
        return psgKeyOffsets;
    }

    public int[] getPsgVolumeOffsets() {
        return psgVolumeOffsets;
    }

    public int[] getPsgModEnvs() {
        return psgModEnvs;
    }

    public int[] getPsgInstruments() {
        return psgInstruments;
    }

    public int getZ80StartAddress() {
        return z80StartAddress;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isPalSpeedupDisabled() {
        return palSpeedupDisabled;
    }

    public void setPalSpeedupDisabled(boolean palSpeedupDisabled) {
        this.palSpeedupDisabled = palSpeedupDisabled;
    }

    @Override
    public int dataLength() {
        return data.length;
    }

    @Override
    public byte dataByteAt(int index) {
        return data[index];
    }

    @Override
    public int fmPointerCount() {
        return fmPointers.length;
    }

    @Override
    public int fmPointerAt(int index) {
        return fmPointers[index];
    }

    @Override
    public int fmKeyOffsetAt(int index) {
        return valueAt(fmKeyOffsets, index);
    }

    @Override
    public int fmVolumeOffsetAt(int index) {
        return valueAt(fmVolumeOffsets, index);
    }

    @Override
    public int psgPointerCount() {
        return psgPointers.length;
    }

    @Override
    public int psgPointerAt(int index) {
        return psgPointers[index];
    }

    @Override
    public int psgKeyOffsetAt(int index) {
        return valueAt(psgKeyOffsets, index);
    }

    @Override
    public int psgVolumeOffsetAt(int index) {
        return valueAt(psgVolumeOffsets, index);
    }

    @Override
    public int psgModEnvelopeAt(int index) {
        return valueAt(psgModEnvs, index);
    }

    @Override
    public int psgInstrumentCount() {
        return psgInstruments.length;
    }

    @Override
    public int psgInstrumentAt(int index) {
        return valueAt(psgInstruments, index);
    }

    @Override
    public int voiceLength(int voiceId) {
        return length(getVoice(voiceId));
    }

    @Override
    public byte voiceByteAt(int voiceId, int index) {
        return getVoice(voiceId)[index];
    }

    @Override
    public int psgEnvelopeLength(int envelopeId) {
        return length(getPsgEnvelope(envelopeId));
    }

    @Override
    public byte psgEnvelopeByteAt(int envelopeId, int index) {
        return getPsgEnvelope(envelopeId)[index];
    }

    @Override
    public int modEnvelopeLength(int envelopeId) {
        return length(getModEnvelope(envelopeId));
    }

    @Override
    public byte modEnvelopeByteAt(int envelopeId, int index) {
        return getModEnvelope(envelopeId)[index];
    }

    /**
     * Stateless compatibility lookup for sequencers consuming a legacy
     * loader. This intentionally preserves the historical one-getter-result
     * ownership used by raw playback. Frozen programs override it to return a
     * defensive copy of their private backing table.
     */
    protected byte[] materializeVoiceForSequencer(int voiceId) {
        return getVoice(voiceId);
    }

    /** See {@link #materializeVoiceForSequencer(int)}. */
    protected byte[] materializePsgEnvelopeForSequencer(int envelopeId) {
        return getPsgEnvelope(envelopeId);
    }

    /** See {@link #materializeVoiceForSequencer(int)}. */
    protected byte[] materializeModEnvelopeForSequencer(int envelopeId) {
        return getModEnvelope(envelopeId);
    }

    private static int valueAt(int[] values, int index) {
        return index < values.length ? values[index] : 0;
    }

    private static int length(byte[] values) {
        return values == null ? 0 : values.length;
    }
}
