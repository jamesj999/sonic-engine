package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class VirtualSynthesizer {
    private final PsgChip psg = new PsgChip();
    private final Ym2612Chip ym = new Ym2612Chip();

    public void setDacData(DacData data) {
        ym.setDacData(data);
    }

    public void playDac(int note) {
        ym.playDac(note);
    }

    public void stopDac() {
        ym.stopDac();
    }

    public void render(short[] buffer) {
        psg.render(buffer);
        ym.render(buffer);
    }

    public void writeFm(int port, int reg, int val) {
        ym.write(port, reg, val);
    }

    public void writePsg(int val) {
        psg.write(val);
    }

    public void setInstrument(int channelId, byte[] voice) {
        ym.setInstrument(channelId, voice);
    }
}
