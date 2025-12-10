package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class VirtualSynthesizer implements Synthesizer {
    private static final int YM_CLOCK = 7670453;
    private static final int SAMPLE_RATE = 44100;

    private final PsgChip psg = new PsgChip();
    // Reference YM2612 core ported from libvgm.
    private final LibvgmYm2612 ym = new LibvgmYm2612();

    public VirtualSynthesizer() {
        ym.init(YM_CLOCK, SAMPLE_RATE);
        ym.reset();
    }

    @Override
    public void setDacData(DacData data) {
        // No-op: libvgm core expects DAC writes via ports (0x2A/0x2B).
    }

    @Override
    public void playDac(Object source, int note) {
        // No-op: DAC streaming must be performed via register writes.
    }

    @Override
    public void stopDac(Object source) {
        // No-op for libvgm core.
    }

    public void render(short[] buffer) {
        // Buffer is interleaved stereo (L,R, ...).
        int frames = buffer.length / 2;
        int[] fm = new int[frames * 2]; // interleaved L/R
        // libvgm core expects up to 4000 frames per update; process in chunks.
        final int MAX_FRAMES_PER_CALL = 4000;
        int processed = 0;
        while (processed < frames) {
            int chunk = Math.min(MAX_FRAMES_PER_CALL, frames - processed);
            ym.update(fm, processed, chunk);
            processed += chunk;
        }

        // Mix PSG into fm buffer
        int[] left = new int[frames];
        int[] right = new int[frames];
        psg.renderStereo(left, right);
        for (int i = 0; i < frames; i++) {
            fm[i * 2] += left[i];
            fm[i * 2 + 1] += right[i];
        }

        final double MASTER_GAIN = 1.0; // unity gain; adjust if clipping occurs
        for (int i = 0; i < frames; i++) {
            int l = (int) (fm[i * 2] * MASTER_GAIN);
            int r = (int) (fm[i * 2 + 1] * MASTER_GAIN);
            if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
            if (r > 32767) r = 32767; else if (r < -32768) r = -32768;
            buffer[i * 2] = (short) l;
            buffer[i * 2 + 1] = (short) r;
        }
    }

    @Override
    public void writeFm(Object source, int port, int reg, int val) {
        // Map to libvgm core ports: 0/1 = addr/data for FM port 0, 2/3 = addr/data for FM port 1.
        int addrPort = (port == 0) ? 0 : 2;
        int dataPort = addrPort + 1;
        ym.writePort(addrPort, reg);
        ym.writePort(dataPort, val);
    }

    @Override
    public void writePsg(Object source, int val) {
        psg.write(val);
    }

    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        // Instrument writes must be performed via register writes; nothing to do here.
    }

    @Override
    public void setFmMute(int channel, boolean mute) {
        // Not supported in libvgm shim; could be added via mask writes if needed.
    }

    @Override
    public void setPsgMute(int channel, boolean mute) {
        psg.setMute(channel, mute);
    }

    @Override
    public void setDacInterpolate(boolean interpolate) {
        // Not supported in libvgm shim.
    }
}
