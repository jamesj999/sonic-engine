package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private static final double CLOCK = 3579545.0; // NTSC master clock
    private static final double SAMPLE_RATE = 44100.0;
    private static final double PSG_TICKS_PER_SAMPLE = (CLOCK / 16.0) / SAMPLE_RATE;
    // Approximate SN76489 volume curve (2dB steps, 15 = mute)
    private static final double[] VOLUME_TABLE = new double[16];
    // Gentle analog low-pass / DC-blocking
    private static final double HPF_ALPHA = 0.995;
    private static final double LPF_CUTOFF_HZ = 9000.0;
    private static final double LPF_ALPHA = LPF_CUTOFF_HZ / (LPF_CUTOFF_HZ + SAMPLE_RATE);

    static {
        for (int i = 0; i < 15; i++) {
            VOLUME_TABLE[i] = Math.pow(10.0, -(i * 2.0) / 20.0);
        }
        VOLUME_TABLE[15] = 0.0;
    }

    private final int[] registers = new int[8];
    private final double[] counters = new double[4];
    private final boolean[] outputs = new boolean[4];
    private final double[] hpfState = new double[2];
    private final double[] lpfState = new double[2];
    private double lastInputL;
    private double lastInputR;
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
        double[] left = new double[buffer.length];
        double[] right = new double[buffer.length];
        renderInternal(left, right);
        for (int i = 0; i < buffer.length; i++) {
            double mono = (left[i] + right[i]) * 0.5;
            int sample = (int) Math.max(-32768, Math.min(32767, buffer[i] + mono));
            buffer[i] = (short) sample;
        }
    }

    public void renderStereo(short[] left, short[] right) {
        int len = Math.min(left.length, right.length);
        double[] mixL = new double[len];
        double[] mixR = new double[len];
        renderInternal(mixL, mixR);
        for (int i = 0; i < len; i++) {
            int l = (int) Math.max(-32768, Math.min(32767, left[i] + mixL[i]));
            int r = (int) Math.max(-32768, Math.min(32767, right[i] + mixR[i]));
            left[i] = (short) l;
            right[i] = (short) r;
        }
    }

    private void renderInternal(double[] left, double[] right) {
        int len = Math.min(left.length, right.length);
        for (int i = 0; i < len; i++) {
            double sample = 0.0;
            // Tone Channels
            for (int ch = 0; ch < 3; ch++) {
                int vol = registers[ch * 2 + 1] & 0x0F;
                if (vol == 0x0F) continue;

                int tone = registers[ch * 2] & 0x3FF;
                counters[ch] -= PSG_TICKS_PER_SAMPLE;
                if (counters[ch] <= 0) {
                    double reload = Math.max(1.0, (tone == 0 ? 1.0 : tone) * 2.0);
                    counters[ch] += reload;
                    outputs[ch] = !outputs[ch];
                }

                double amp = VOLUME_TABLE[vol];
                sample += outputs[ch] ? amp : -amp;
            }

            // Noise Channel
            int noiseVol = registers[7] & 0x0F;
            if (noiseVol != 0x0F) {
                int noiseReg = registers[6];
                double rateVal = switch (noiseReg & 0x3) {
                    case 0 -> 0x10;
                    case 1 -> 0x20;
                    case 2 -> 0x40;
                    default -> Math.max(1, registers[4] & 0x3FF);
                };

                counters[3] -= PSG_TICKS_PER_SAMPLE;
                if (counters[3] <= 0) {
                    counters[3] += rateVal;
                    boolean bit0 = (lfsr & 1) != 0;
                    boolean bit3 = (lfsr & 8) != 0;
                    boolean feedback = ((noiseReg & 4) != 0) ? (bit0 ^ bit3) : bit0;
                    lfsr >>= 1;
                    if (feedback) lfsr |= 0x8000;
                    outputs[3] = (lfsr & 1) != 0;
                }
                double amp = VOLUME_TABLE[noiseVol];
                sample += outputs[3] ? amp : -amp;
            }

            // Apply a simple DC blocking filter then a light LPF to smooth the edges
            double hpOutL = HPF_ALPHA * (hpfState[0] + sample - lastInputL);
            lastInputL = sample;
            hpfState[0] = hpOutL;
            double lpOutL = lpfState[0] + (hpOutL - lpfState[0]) * LPF_ALPHA;
            lpfState[0] = lpOutL;

            double hpOutR = HPF_ALPHA * (hpfState[1] + sample - lastInputR);
            lastInputR = sample;
            hpfState[1] = hpOutR;
            double lpOutR = lpfState[1] + (hpOutR - lpfState[1]) * LPF_ALPHA;
            lpfState[1] = lpOutR;

            left[i] += lpOutL * 14000.0;
            right[i] += lpOutR * 14000.0;
        }
    }
}
