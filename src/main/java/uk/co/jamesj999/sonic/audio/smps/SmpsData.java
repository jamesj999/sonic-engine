package uk.co.jamesj999.sonic.audio.smps;

public class SmpsData {
    private final byte[] data;
    private int voicePtr;
    private int channels;
    private int psgChannels;
    private int dividingTiming = 1;
    private int tempo;

    public SmpsData(byte[] data) {
        this.data = data;
        if (data.length >= 6) {
            // Little Endian
            this.voicePtr = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
            this.channels = data[2] & 0xFF; // FM + DAC
            this.psgChannels = data[3] & 0xFF;
            this.dividingTiming = data[4] & 0xFF;
            // 05 is main tempo (Sonic 2 duty cycle)
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

    public int getDividingTiming() {
        return dividingTiming;
    }

    public int getTempo() {
        return tempo;
    }
}
