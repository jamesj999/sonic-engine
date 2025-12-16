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

        ym.renderStereo(left, right);

        // Attenuate YM/DAC by 50% (>> 1)
        // YM Peak ~24k -> ~12k
        for (int i = 0; i < frames; i++) {
            left[i] >>= 1;
            right[i] >>= 1;
        }

        int[] leftPsg = new int[frames];
        int[] rightPsg = new int[frames];
        psg.renderStereo(leftPsg, rightPsg);

        // Boost PSG by 4x (<< 2) to match user expectations for Noise volume relative to FM.
        // PSG Peak ~4k -> ~16k.
        // Noise Peak ~2k -> ~8k.
        // Resulting Ratio FM:Noise is ~1.5:1.
        for (int i = 0; i < frames; i++) {
            left[i] += (leftPsg[i] << 2);
            right[i] += (rightPsg[i] << 2);
        }

        for (int i = 0; i < frames; i++) {
            // Master Gain: No division (1.0) to match SMPSPlay levels which push near clipping.
            int l = left[i];
            int r = right[i];

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
