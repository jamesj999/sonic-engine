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
        psg.render(buffer);
        ym.render(buffer);
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
