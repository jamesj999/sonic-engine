package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class VirtualSynthesizer implements Synthesizer {
    private final PsgChip psg = new PsgChip();
    private final Ym2612Chip ym = new Ym2612Chip();

    // Output headroom: reduce overall level so 6 FM channels + PSG don't clip 16-bit output.
    private static final int MASTER_GAIN_SHIFT = 1; // -6 dB

    // Scratch buffers for render() to avoid per-call allocations
    private int[] scratchLeft = new int[0];
    private int[] scratchRight = new int[0];
    private int[] scratchLeftPsg = new int[0];
    private int[] scratchRightPsg = new int[0];

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

        // Reuse scratch buffers, resize only when needed
        if (scratchLeft.length < frames) {
            scratchLeft = new int[frames];
            scratchRight = new int[frames];
            scratchLeftPsg = new int[frames];
            scratchRightPsg = new int[frames];
        }

        // Clear reused buffers (chips accumulate into them)
        for (int i = 0; i < frames; i++) {
            scratchLeft[i] = 0;
            scratchRight[i] = 0;
            scratchLeftPsg[i] = 0;
            scratchRightPsg[i] = 0;
        }

        ym.renderStereo(scratchLeft, scratchRight);

        // GPGX-style: FM output is clipped to ±8191 internally.
        // No output gain applied here - volume issues are in the EG/feedback implementation.

        psg.renderStereo(scratchLeftPsg, scratchRightPsg);

        // Mix PSG at ~50% level relative to FM
        for (int i = 0; i < frames; i++) {
            scratchLeft[i] += scratchLeftPsg[i] >> 1;
            scratchRight[i] += scratchRightPsg[i] >> 1;
        }

        for (int i = 0; i < frames; i++) {
            // Master gain: apply fixed headroom scaling before 16-bit clamp.
            int l = scratchLeft[i];
            int r = scratchRight[i];

            if (MASTER_GAIN_SHIFT > 0) {
                l >>= MASTER_GAIN_SHIFT;
                r >>= MASTER_GAIN_SHIFT;
            }

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

    @Override
    public void silenceAll() {
        ym.silenceAll();
        psg.silenceAll();
    }

    /**
     * Force-silence an FM channel by directly resetting envelope state.
     * Used when SFX steals a channel to prevent chirp artifacts.
     */
    public void forceSilenceChannel(int channelId) {
        ym.forceSilenceChannel(channelId);
    }
}
