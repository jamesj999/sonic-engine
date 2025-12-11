package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private static final double CLOCK = 3579545.0; // Master NTSC clock
    private static final double SAMPLE_RATE = 44100.0;
    // Use master/8 so tone freq matches SN76496 formula f = clock / (32 * N) (SMPSPlay parity)
    private static final double CLOCK_DIV = 8.0;
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
    private double lpfStateL;
    private double lpfStateR;
    private double hpfStateL;
    private double hpfStateR;
    private double prevInL;
    private double prevInR;
    private int latch = 0;
    private int lfsr = 0x8000; // 16-bit noise register

    private final boolean[] mutes = new boolean[4];

    static {
        // SN76489 2 dB steps (15 -> silence)
        for (int i = 0; i < 15; i++) {
            VOLUME_TABLE[i] = Math.pow(2.0, i / -3.0) * 8192.0;
        }
        VOLUME_TABLE[15] = 0.0;
    }

    public PsgChip() {
        for (int i = 1; i < 8; i += 2) {
            registers[i] = 0xF; // Silence
        }
        tonePeriod[0] = tonePeriod[1] = tonePeriod[2] = 1;
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 4) {
            mutes[ch] = mute;
        }
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
                    lfsr = 0x8000;
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
                    lfsr = 0x8000;
                }
            }
        }
    }


    public void renderStereo(int[] left, int[] right) {
        int len = Math.min(left.length, right.length);
        for (int i = 0; i < len; i++) {
            double sampleL = 0;
            double sampleR = 0;

            // Tone Channels
            for (int ch = 0; ch < 3; ch++) {
                int vol = registers[ch * 2 + 1] & 0x0F;
                if (vol == 0x0F) continue;

                double period = Math.max(1, tonePeriod[ch]) * 4.0;
                counters[ch] -= STEP;
                while (counters[ch] <= 0) {
                    counters[ch] += period;
                    outputs[ch] = !outputs[ch];
                }

                double amp = VOLUME_TABLE[vol];
                double voice = outputs[ch] ? amp : -amp;
                if (!mutes[ch]) {
                    sampleL += voice;
                    sampleR += voice;
                }
            }

            // Noise Channel
            int noiseVol = registers[7] & 0x0F;
            if (noiseVol != 0x0F) {
                int noiseReg = registers[6];
                double rateVal = switch (noiseReg & 0x3) {
                    case 0 -> 0x40;
                    case 1 -> 0x80;
                    case 2 -> 0x100;
                    default -> Math.max(1, tonePeriod[2] * 4);
                };

                counters[3] -= STEP;
                while (counters[3] <= 0) {
                    counters[3] += rateVal;
                    int bit0 = lfsr & 1;
                    int tap = ((noiseReg & 0x04) != 0) ? bit0 ^ ((lfsr >> 3) & 1) : bit0;
                    lfsr = (lfsr >> 1) | (tap << 15);
                    outputs[3] = (lfsr & 1) == 1;
                }
                double amp = VOLUME_TABLE[noiseVol];
                double voice = outputs[3] ? amp : -amp;
                if (!mutes[3]) {
                    sampleL += voice;
                    sampleR += voice;
                }
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

            left[i] += (int) lpfStateL;
            right[i] += (int) lpfStateR;
        }
    }
}
