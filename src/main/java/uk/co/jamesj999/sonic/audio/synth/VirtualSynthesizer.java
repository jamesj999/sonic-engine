package uk.co.jamesj999.sonic.audio.synth;

public class VirtualSynthesizer {
    private final PsgChip psg = new PsgChip();
    private final Ym2612Chip ym = new Ym2612Chip();

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
}
