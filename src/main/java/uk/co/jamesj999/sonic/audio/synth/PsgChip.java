package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private static final double CLOCK = 3579545.0; // Master NTSC clock
    private static final double SAMPLE_RATE = 44100.0;
    // SN76489 pre-divides master by 16 (see libvgm sn76489.c dClock)
    private static final double CLOCK_DIV = 16.0;
    private static final double STEP = (CLOCK / CLOCK_DIV) / SAMPLE_RATE;
    private static final double LPF_CUTOFF_HZ = 12000.0;
    private static final double LPF_ALPHA = LPF_CUTOFF_HZ / (LPF_CUTOFF_HZ + SAMPLE_RATE);
    private static final double HPF_CUTOFF_HZ = 20.0;
    private static final double HPF_ALPHA = SAMPLE_RATE / (SAMPLE_RATE + (2 * Math.PI * HPF_CUTOFF_HZ));

    // SN76489 Volume Values (from sn76489.c, Mega Drive behavior)
    private static final double[] VOLUME_TABLE = {
            4096, 3254, 2584, 2053, 1631, 1295, 1029, 817, 649, 516, 410, 325, 258, 205, 163, 0
    };

    private static final int PSG_CUTOFF = 6;

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

    public PsgChip() {
        for (int i = 1; i < 8; i += 2) {
            registers[i] = 0xF; // Silence
        }
        tonePeriod[0] = tonePeriod[1] = tonePeriod[2] = 1;
        for (int ch = 0; ch < 3; ch++) {
            outputs[ch] = true;
            counters[ch] = tonePeriod[ch];
        }
        outputs[3] = (lfsr & 1) == 1;
        counters[3] = 1.0;
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

    private double integrateToneChannel(int ch, double period, double clocksPerSample) {
        // If frequency is below cutoff, output is held constant (High)
        if (period < PSG_CUTOFF) {
            outputs[ch] = true;
            counters[ch] = period; // Reset counter for when it resumes? sn76489.c does weird stuff, but this is safe
            return 1.0;
        }

        double remaining = counters[ch] <= 0 ? period : counters[ch];
        double sample = 0.0;
        double time = clocksPerSample;

        while (time > 0) {
            if (remaining > time) {
                sample += (outputs[ch] ? time : 0); // Unipolar 0..1
                remaining -= time;
                time = 0;
            } else {
                sample += (outputs[ch] ? remaining : 0); // Unipolar 0..1
                time -= remaining;
                outputs[ch] = !outputs[ch];
                remaining = period;
            }
        }

        counters[ch] = remaining;
        return sample / clocksPerSample;
    }

    private void stepNoiseLfsr(int noiseReg) {
        int bit0 = lfsr & 1;
        int tap = ((noiseReg & 0x04) != 0) ? bit0 ^ ((lfsr >> 3) & 1) : bit0;
        lfsr = (lfsr >> 1) | (tap << 15);
        outputs[3] = (lfsr & 1) == 1;
    }

    private double integrateNoiseChannel(int noiseReg, double period, double clocksPerSample) {
        double remaining = counters[3] <= 0 ? period : counters[3];
        double sample = 0.0;
        double time = clocksPerSample;

        while (time > 0) {
            if (remaining > time) {
                sample += (outputs[3] ? time : 0); // Unipolar 0..1
                remaining -= time;
                time = 0;
            } else {
                sample += (outputs[3] ? remaining : 0); // Unipolar 0..1
                time -= remaining;
                stepNoiseLfsr(noiseReg);
                remaining = period;
            }
        }

        counters[3] = remaining;
        return sample / clocksPerSample;
    }


    public void renderStereo(int[] left, int[] right) {
        int len = Math.min(left.length, right.length);
        double clocksPerSample = STEP;
        for (int i = 0; i < len; i++) {
            double sampleL = 0;
            double sampleR = 0;

            // Tone Channels
            for (int ch = 0; ch < 3; ch++) {
                int vol = registers[ch * 2 + 1] & 0x0F;
                double period = Math.max(1, tonePeriod[ch]);
                double wave = integrateToneChannel(ch, period, clocksPerSample);
                double amp = VOLUME_TABLE[vol];
                double voice = wave * amp;
                if (!mutes[ch]) {
                    sampleL += voice;
                    sampleR += voice;
                }
            }

            // Noise Channel
            int noiseReg = registers[6];
            double rateVal = switch (noiseReg & 0x3) {
                case 0 -> 0x20;
                case 1 -> 0x40;
                case 2 -> 0x80;
                default -> Math.max(1, tonePeriod[2]);
            };
            int noiseVol = registers[7] & 0x0F;
            double noiseWave = integrateNoiseChannel(noiseReg, rateVal, clocksPerSample);
            double amp = VOLUME_TABLE[noiseVol];

            // White Noise is half amplitude in sn76489.c
            if ((noiseReg & 0x04) != 0) {
                amp *= 0.5;
            }

            double noiseVoice = noiseWave * amp;
            if (!mutes[3]) {
                sampleL += noiseVoice;
                sampleR += noiseVoice;
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
