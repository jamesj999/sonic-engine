package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private static final double CLOCK = 3579545.0; // Master NTSC clock
    private static final double SAMPLE_RATE = 44100.0;
    private static final double CLOCK_DIV = 16.0; // SN76489 divides clock by 16
    private static final double STEP = (CLOCK / CLOCK_DIV) / SAMPLE_RATE;
    private static final double LPF_CUTOFF_HZ = 12000.0;
    private static final double LPF_ALPHA = LPF_CUTOFF_HZ / (LPF_CUTOFF_HZ + SAMPLE_RATE);
    private static final double HPF_CUTOFF_HZ = 20.0;
    private static final double HPF_ALPHA = SAMPLE_RATE / (SAMPLE_RATE + (2 * Math.PI * HPF_CUTOFF_HZ));
    private static final double[] VOLUME_TABLE = new double[16];
    private final int[] registers = new int[8];
    private final double[] counters = new double[4];
    private final boolean[] outputs = new boolean[4];
    private final int[] tonePeriod = new int[3];
    private int latch = 0;
    private int lfsr = 0x4000; // 15-bit noise register
    private double lpfStateL;
    private double lpfStateR;
    private double hpfStateL;
    private double hpfStateR;
    private double prevInL;
    private double prevInR;

    static {
        // Approximate SN76489 2 dB steps (15 -> silence)
        for (int i = 0; i < 15; i++) {
            VOLUME_TABLE[i] = Math.pow(10.0, -(i * 2.0) / 20.0) * 3000.0;
        }
        VOLUME_TABLE[15] = 0.0;
    }

    public PsgChip() {
        for (int i = 1; i < 8; i += 2) {
            registers[i] = 0xF; // Silence
        }
        tonePeriod[0] = tonePeriod[1] = tonePeriod[2] = 1;
    }

    public void write(int val) {
        if ((val & 0x80) != 0) {
            int channel = (val >> 5) & 0x03;
            int type = (val >> 4) & 0x01;
            int data = val & 0x0F;
            latch = (channel << 1) | type;
            if (type == 1) { // Volume
                registers[latch] = data & 0x0F;
            } else { // Tone/Noise
                registers[latch] = (registers[latch] & 0x3F0) | data;
                if (channel < 3) {
                    tonePeriod[channel] = Math.max(1, registers[latch] & 0x3FF);
                } else {
                    registers[latch] = data & 0x07;
                    lfsr = 0x4000;
                }
            }
        } else {
            if ((latch & 1) == 0 && latch < 6) { // Tone Data
                int data = val & 0x3F;
                registers[latch] = (registers[latch] & 0x0F) | (data << 4);
                int channel = latch >> 1;
                tonePeriod[channel] = Math.max(1, registers[latch] & 0x3FF);
            } else { // Volume/Noise Data
                int data = val & 0x0F;
                if ((latch & 1) == 1) {
                    registers[latch] = data;
                } else if (latch == 6) {
                    registers[latch] = data & 0x07;
                    lfsr = 0x4000;
                }
            }
        }
    }

    public void render(short[] buffer) {
        short[] tmp = new short[buffer.length];
        short[] tmpR = new short[buffer.length];
        renderStereo(tmp, tmpR);
        for (int i = 0; i < buffer.length; i++) {
            int mixed = (tmp[i] + tmpR[i]) / 2;
            buffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, buffer[i] + mixed));
        }
    }

    public void renderStereo(short[] left, short[] right) {
        int len = Math.min(left.length, right.length);
        for (int i = 0; i < len; i++) {
            double sampleL = 0;
            double sampleR = 0;

            // Tone Channels
            for (int ch = 0; ch < 3; ch++) {
                int vol = registers[ch * 2 + 1];
                if (vol == 15) continue;

                double period = tonePeriod[ch] * 2.0;
                counters[ch] -= STEP;
                while (counters[ch] <= 0) {
                    counters[ch] += period;
                    outputs[ch] = !outputs[ch];
                }

                double amp = VOLUME_TABLE[vol];
                double voice = outputs[ch] ? amp : -amp;
                double panL = (ch == 0) ? 0.8 : (ch == 1 ? 0.6 : 0.4);
                double panR = (ch == 0) ? 0.4 : (ch == 1 ? 0.6 : 0.8);
                sampleL += voice * panL;
                sampleR += voice * panR;
            }

            // Noise Channel
            int noiseVol = registers[7];
            if (noiseVol != 15) {
                int noiseReg = registers[6];
                double rateVal = switch (noiseReg & 3) {
                    case 0 -> 0x10;
                    case 1 -> 0x20;
                    case 2 -> 0x40;
                    default -> Math.max(1, tonePeriod[2] * 2);
                };

                counters[3] -= STEP;
                while (counters[3] <= 0) {
                    counters[3] += rateVal;
                    int bit0 = lfsr & 1;
                    int tap = ((noiseReg & 0x04) != 0) ? ((lfsr >> 1) & 1) ^ bit0 : bit0;
                    lfsr = (lfsr >> 1) | (tap << 14);
                    outputs[3] = (lfsr & 1) == 1;
                }
                double amp = VOLUME_TABLE[noiseVol];
                double voice = outputs[3] ? amp : -amp;
                sampleL += voice * 0.55;
                sampleR += voice * 0.45;
            }

            // Apply high-pass to remove DC then optional LPF for smoothing
            double inL = sampleL;
            double inR = sampleR;
            double hpOutL = HPF_ALPHA * (hpfStateL + inL - prevInL);
            double hpOutR = HPF_ALPHA * (hpfStateR + inR - prevInR);
            hpfStateL = hpOutL;
            hpfStateR = hpOutR;
            prevInL = inL;
            prevInR = inR;

            lpfStateL += (hpOutL - lpfStateL) * LPF_ALPHA;
            lpfStateR += (hpOutR - lpfStateR) * LPF_ALPHA;

            int outL = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, lpfStateL));
            int outR = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, lpfStateR));
            left[i] = (short) (left[i] + outL);
            right[i] = (short) (right[i] + outR);
        }
    }
}
