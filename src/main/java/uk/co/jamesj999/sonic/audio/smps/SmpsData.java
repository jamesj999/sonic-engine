package uk.co.jamesj999.sonic.audio.smps;

public class SmpsData {
    private final byte[] data;
    private int voicePtr;
    private int channels;
    private int psgChannels;
    private int tempo;

    public SmpsData(byte[] data) {
        this.data = data;
        if (data.length >= 6) {
            // Little Endian
            this.voicePtr = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
            this.channels = data[2] & 0xFF; // FM + DAC
            this.psgChannels = data[3] & 0xFF;
            // 04 is Timing
            this.tempo = data[5] & 0xFF;
        }
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

    public int getTempo() {
        return tempo;
    }
}
