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

    // Intermediate position for anti-aliasing (Bipolar: -1.0 to 1.0)
    private final double[] intermediatePos = new double[3];

    // High-frequency cutoff flag - mute channels above Nyquist (MAME behavior)
    private final boolean[] highFreqCutoff = new boolean[3];

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
            intermediatePos[ch] = -2.0; // Sentinel value (valid range -1.0 to 1.0)
        }
        // Match libvgm: ToneFreqPos[3] = 1, independent of LFSR state
        outputs[3] = true;
        counters[3] = 0;
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 4) {
            mutes[ch] = mute;
        }
    }

    /**
     * Silence all PSG channels (ROM: zPSGSilenceAll).
     * Writes 0x9F, 0xBF, 0xDF, 0xFF to set all volumes to max attenuation.
     */
    public void silenceAll() {
        write(0x9F); // Channel 0 volume = 0xF (silence)
        write(0xBF); // Channel 1 volume = 0xF (silence)
        write(0xDF); // Channel 2 volume = 0xF (silence)
        write(0xFF); // Channel 3 (noise) volume = 0xF (silence)
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
                if (!mutes[i] && !highFreqCutoff[i]) {
                    // MAME: High-frequency tones (period <= FNumLimit) are muted via vol[i] = 0
                    // We skip output entirely when highFreqCutoff is true
                    double sample;
                    if (intermediatePos[i] != -2.0) {
                        sample = intermediatePos[i];
                    } else {
                        // Flat: Bipolar +/- 1.0
                        sample = outputs[i] ? 1.0 : -1.0;
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
                // Noise Logic (Bipolar)
                // Output is determined by the LFSR bit 0.
                // Bipolar: (lfsr & 1) ? 1.0 : -1.0
                double noiseSample = (lfsr & 1) != 0 ? 1.0 : -1.0;

                int noiseVol = registers[7] & 0x0F;
                double amp = VOLUME_TABLE[noiseVol];

                // Note: Maxim's sn76489.c halves white noise amplitude with a comment
                // "due to the way the white noise works here, it seems twice as loud".
                // However, MAME's sn76496.c (used by SMPSPlay) does NOT halve it.
                // We match MAME/SMPSPlay behavior for consistency.

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

                        // Bipolar intermediate: directly use the calculated value (-1..1)
                        intermediatePos[i] = intermediate;

                        outputs[i] = !outputs[i];
                        highFreqCutoff[i] = false;
                    } else {
                        // High-frequency cutoff: mute channel (MAME behavior)
                        // MAME sets vol[i] = 0 for periods <= FNumLimit
                        outputs[i] = true;
                        intermediatePos[i] = -2.0;
                        highFreqCutoff[i] = true;
                    }

                    counters[i] += period * (Math.floor(numClocksForSample / period) + 1);
                } else {
                    intermediatePos[i] = -2.0;
                }
            }

            // Process Noise Transitions
            // MAME sn76496.c shifts LFSR on EVERY counter underflow (no flip-flop gating)
            // Maxim sn76489.c only shifts on positive edge of flip-flop (half rate)
            // We use MAME behavior for accurate noise spectrum
            if (counters[3] <= 0) {
                // Shift LFSR on every underflow (MAME behavior)
                int bit0 = lfsr & 1;
                int feedback;
                if ((noiseReg & 0x04) != 0) { // White Noise
                    int bit3 = (lfsr >> 3) & 1;
                    feedback = bit0 ^ bit3;
                } else { // Periodic
                    feedback = bit0;
                }
                lfsr = (lfsr >> 1) | (feedback << 15);

                // Only reload counter if NOT in sync mode (Tone 2 handles the effective rate otherwise)
                if ((noiseReg & 0x3) != 3) {
                    double noiseRate;
                    switch (noiseReg & 0x3) {
                        case 0: noiseRate = 0x10; break;
                        case 1: noiseRate = 0x20; break;
                        case 2: noiseRate = 0x40; break;
                        default: noiseRate = 0x10; break;
                    }
                    counters[3] += noiseRate * (Math.floor(numClocksForSample / noiseRate) + 1);
                }
            }
        }
    }
}
