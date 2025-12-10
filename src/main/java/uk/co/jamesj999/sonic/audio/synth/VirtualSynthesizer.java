package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class VirtualSynthesizer implements Synthesizer {
    private final PsgChip psg = new PsgChip();
    private final Ym2612Chip ym = new Ym2612Chip();

    @Override
    public void setDacData(DacData data) {
        ym.setDacData(data);
    }

    @Override
    public void playDac(Object source, int note) {
        ym.playDac(note);
    }

    @Override
    public void stopDac(Object source) {
        ym.stopDac();
    }

    public void render(short[] buffer) {
        // Assume buffer is Stereo Interleaved (L, R, L, R...)
        int frames = buffer.length / 2;
        int[] left = new int[frames];
        int[] right = new int[frames];

        psg.renderStereo(left, right);
        ym.renderStereo(left, right);

        for (int i = 0; i < frames; i++) {
            // Apply Master Gain (approx -36dB to match reference level)
            // Reference uses 1/128 effectively, but we use 1/64 here to be safe.
            int l = (int) (left[i] / 64.0);
            int r = (int) (right[i] / 64.0);

            if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
            if (r > 32767) r = 32767; else if (r < -32768) r = -32768;

            buffer[i * 2] = (short) l;
            buffer[i * 2 + 1] = (short) r;
        }
    }

    @Override
    public void writeFm(Object source, int port, int reg, int val) {
        ym.write(port, reg, val);
    }

    @Override
    public void writePsg(Object source, int val) {
        psg.write(val);
    }

    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        ym.setInstrument(channelId, voice);
    }

    @Override
    public void setFmMute(int channel, boolean mute) {
        ym.setMute(channel, mute);
    }

    @Override
    public void setPsgMute(int channel, boolean mute) {
        psg.setMute(channel, mute);
    }

    @Override
    public void setDacInterpolate(boolean interpolate) {
        ym.setDacInterpolate(interpolate);
    }
}
