package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private final int[] registers = new int[8];
    private final double[] counters = new double[4];
    private final boolean[] outputs = new boolean[4];
    private int latch = 0;
    private int lfsr = 0x8000;

    public PsgChip() {
        for(int i=1; i<8; i+=2) registers[i] = 0xF; // Silence
    }

    public void write(int val) {
        if ((val & 0x80) != 0) {
            int channel = (val >> 5) & 0x03;
            int type = (val >> 4) & 0x01;
            int data = val & 0x0F;
            latch = (channel << 1) | type;
            if (type == 1) { // Volume
                registers[latch] = data;
            } else { // Tone/Noise
                registers[latch] = (registers[latch] & 0x3F0) | data;
                if (channel == 3) {
                    registers[latch] = data & 0x07;
                    lfsr = 0x8000;
                }
            }
        } else {
            if ((latch & 1) == 0 && latch < 6) { // Tone Data
                int data = val & 0x3F;
                registers[latch] = (registers[latch] & 0x0F) | (data << 4);
            } else { // Volume/Noise Data
                int data = val & 0x0F;
                if ((latch & 1) == 1) {
                    registers[latch] = data;
                } else if (latch == 6) {
                    registers[latch] = data & 0x07;
                    lfsr = 0x8000;
                }
            }
        }
    }

    public void render(short[] buffer) {
        // Clock ~3.58MHz / 16 = ~223721 Hz.
        double step = 223721.0 / 44100.0;

        for (int i = 0; i < buffer.length; i++) {
            int sample = 0;
            // Tone Channels
            for (int ch = 0; ch < 3; ch++) {
                int vol = registers[ch * 2 + 1];
                if (vol == 15) continue;

                int tone = registers[ch * 2];
                counters[ch] -= step;
                if (counters[ch] <= 0) {
                    counters[ch] += (tone == 0 ? 1 : tone);
                    outputs[ch] = !outputs[ch];
                }

                int amp = (15 - vol) * 200;
                sample += outputs[ch] ? amp : -amp;
            }

            // Noise Channel
            int noiseVol = registers[7];
            if (noiseVol != 15) {
                int noiseReg = registers[6];
                int rateVal = switch (noiseReg & 3) {
                    case 0 -> 0x10;
                    case 1 -> 0x20;
                    case 2 -> 0x40;
                    default -> registers[4];
                };

                counters[3] -= step;
                if (counters[3] <= 0) {
                    counters[3] += rateVal;
                    boolean bit0 = (lfsr & 1) != 0;
                    boolean bit3 = (lfsr & 8) != 0;
                    boolean feedback = ((noiseReg & 4) != 0) ? (bit0 ^ bit3) : bit0;
                    lfsr >>= 1;
                    if (feedback) lfsr |= 0x8000;
                    outputs[3] = (lfsr & 1) != 0;
                }
                int amp = (15 - noiseVol) * 200;
                sample += outputs[3] ? amp : -amp;
            }

            if (sample > 32000) sample = 32000;
            if (sample < -32000) sample = -32000;
            buffer[i] = (short)sample;
        }
    }
}
