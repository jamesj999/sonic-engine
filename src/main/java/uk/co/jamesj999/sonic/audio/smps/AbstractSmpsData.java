package uk.co.jamesj999.sonic.audio.smps;

public abstract class AbstractSmpsData {
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
}
