package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    private static final double CLOCK = 3579545.0; // Master NTSC clock
    private static final double SAMPLE_RATE = 44100.0;
    // SN76489 pre-divides master by 16 (see libvgm sn76489.c dClock)
    private static final double CLOCK_DIV = 16.0;
    private static final double STEP = (CLOCK / CLOCK_DIV) / SAMPLE_RATE;

    // SN76489 Volume Values (from sn76489.c, Mega Drive behavior)
    private static final double[] VOLUME_TABLE = {
            4096, 3254, 2584, 2053, 1631, 1295, 1029, 817, 649, 516, 410, 325, 258, 205, 163, 0
    };

    private static final int PSG_CUTOFF = 6;

    private final int[] registers = new int[8];
    // counters now track current progress (like ToneFreqVals in sn76489.c)
    private final double[] counters = new double[4];
    // toneFreqPos acts as the flip-flop state
    private final boolean[] outputs = new boolean[4];
    private final int[] tonePeriod = new int[3];

    // Intermediate position for anti-aliasing (0.0 to 1.0, or -1.0 if not active)
    private final double[] intermediatePos = new double[3];

    private double clock = 0;
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
            counters[ch] = 0;
            intermediatePos[ch] = -1.0;
        }
        outputs[3] = (lfsr & 1) == 1;
        counters[3] = 0;
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

        for (int j = 0; j < len; j++) {
            // Tone Channels (0-2)
            for (int i = 0; i <= 2; i++) {
                if (!mutes[i]) {
                    double sample;
                    if (intermediatePos[i] != -1.0) {
                        sample = intermediatePos[i];
                    } else {
                        sample = outputs[i] ? 1.0 : 0.0;
                    }

                    int vol = registers[i * 2 + 1] & 0x0F;
                    double amp = VOLUME_TABLE[vol];
                    double voice = sample * amp;

                    left[j] += voice;
                    right[j] += voice;
                }
            }

            // Noise Channel (3)
            if (!mutes[3]) {
                // Correct Logic: Output is determined by the LFSR bit 0, NOT the clock flip-flop.
                double noiseSample = (lfsr & 1) != 0 ? 1.0 : 0.0;

                int noiseVol = registers[7] & 0x0F;
                double amp = VOLUME_TABLE[noiseVol];

                // White Noise is half amplitude in sn76489.c
                int noiseReg = registers[6];
                if ((noiseReg & 0x04) != 0) {
                    amp *= 0.5;
                }

                double noiseVoice = noiseSample * amp;
                left[j] += noiseVoice;
                right[j] += noiseVoice;
            }

            // Update Clock & Counters
            clock += STEP;
            double numClocksForSample = Math.floor(clock);
            clock -= numClocksForSample;

            // Decrement Tone Counters
            for (int i = 0; i <= 2; i++) {
                counters[i] -= numClocksForSample;
            }

            // Noise Counter
            int noiseReg = registers[6];

            // Sync Logic: Must copy counters[2] *before* Tone 2 reloads it in the loop below.
            if ((noiseReg & 0x3) == 3) {
                counters[3] = counters[2];
            } else {
                counters[3] -= numClocksForSample;
            }

            // Process Tone Transitions
            for (int i = 0; i <= 2; i++) {
                if (counters[i] <= 0) {
                    int period = Math.max(1, tonePeriod[i]);
                    if (period >= PSG_CUTOFF) {
                        double toneFreqPosVal = outputs[i] ? 1.0 : -1.0;
                        double intermediate = (numClocksForSample - clock + 2 * counters[i]) * toneFreqPosVal / (numClocksForSample + clock);

                        // Map bipolar intermediate (-1..1) to unipolar (0..1)
                        intermediatePos[i] = (intermediate + 1.0) * 0.5;

                        outputs[i] = !outputs[i];
                    } else {
                        // Stuck Value (Cutoff)
                        outputs[i] = true;
                        intermediatePos[i] = -1.0;
                    }

                    counters[i] += period * (Math.floor(numClocksForSample / period) + 1);
                } else {
                    intermediatePos[i] = -1.0;
                }
            }

            // Process Noise Transitions
            if (counters[3] <= 0) {
                outputs[3] = !outputs[3];
                // Only reload counter if NOT in sync mode (Tone 2 handles the effective rate otherwise)
                if ((noiseReg & 0x3) != 3) {
                    double noiseRate;
                    switch (noiseReg & 0x3) {
                        case 0: noiseRate = 0x10; break;
                        case 1: noiseRate = 0x20; break;
                        case 2: noiseRate = 0x40; break;
                        default: noiseRate = 0x10; break; // Should not happen given logic above
                    }
                    counters[3] += noiseRate * (Math.floor(numClocksForSample / noiseRate) + 1);
                }

                if (outputs[3]) { // Positive Edge (0 -> 1)
                     int bit0 = lfsr & 1;
                     int feedback;
                     if ((noiseReg & 0x04) != 0) { // White Noise
                         int bit3 = (lfsr >> 3) & 1;
                         feedback = bit0 ^ bit3;
                     } else { // Periodic
                         feedback = bit0;
                     }
                     lfsr = (lfsr >> 1) | (feedback << 15);
                }
            }
        }
    }
}
