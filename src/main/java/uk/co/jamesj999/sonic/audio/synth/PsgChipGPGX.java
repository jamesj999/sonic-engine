package uk.co.jamesj999.sonic.audio.synth;

public class PsgChipGPGX {
    public enum ChipType {
        INTEGRATED,
        DISCRETE
    }

    private static final double INPUT_CLOCK = 3579545.0; // NTSC PSG input clock (Hz)
    private static final double INTERNAL_RATE = INPUT_CLOCK / 16.0;
    private static final double DEFAULT_SAMPLE_RATE = 44100.0;

    // Clock ratio for integer timing precision (matches GPGX PSG_MCYCLES_RATIO = 15*16)
    private static final int CLOCK_RATIO = 15 * 16;
    private static final int CLOCK_FRAC_BITS = 32;
    private static final long CLOCK_FRAC_UNIT = 1L << CLOCK_FRAC_BITS;

    private static final int PSG_MAX_VOLUME = 2800;
    private static final int DEFAULT_PREAMP = 150; // GPGX uses ~1.5x PSG amplification for MD balance

    private static final int[] NOISE_SHIFT_WIDTH = {14, 15};
    private static final int[] NOISE_BIT_MASK = {0x6, 0x9};
    private static final int[] NOISE_FEEDBACK = {0, 1, 1, 0, 1, 0, 0, 1, 1, 0};

    private static final int[] VOLUME_TABLE = new int[16];

    static {
        double[] multipliers = {
                1.0,
                0.794328234,
                0.630957344,
                0.501187233,
                0.398107170,
                0.316227766,
                0.251188643,
                0.199526231,
                0.158489319,
                0.125892541,
                0.1,
                0.079432823,
                0.063095734,
                0.050118723,
                0.039810717,
                0.0
        };
        for (int i = 0; i < multipliers.length; i++) {
            VOLUME_TABLE[i] = (int) (PSG_MAX_VOLUME * multipliers[i]);
        }
    }

    private final int[] regs = new int[8];
    private final int[] freqInc = new int[4];
    private final int[] freqCounter = new int[4];  // Integer for drift-free timing
    private final int[] polarity = new int[4];
    private final int[][] chanOut = new int[4][2];
    private final int[][] chanAmp = new int[4][2];
    private final boolean[] mutes = new boolean[4];
    private final int[][] chanDelta = new int[4][2];  // Deferred deltas for volume changes

    private int latch;
    private int zeroFreqInc;
    private int noiseShiftValue;
    private int noiseShiftWidth;
    private int noiseBitMask;

    private double outputRate = DEFAULT_SAMPLE_RATE;
    private final BlipDeltaBuffer blip = new BlipDeltaBuffer(INTERNAL_RATE * CLOCK_RATIO, DEFAULT_SAMPLE_RATE);
    private int clocks = 0;  // Integer for drift-free timing
    private long clockFrac = 0;
    private long clocksPerSampleFixed = 0;
    private boolean hqPsg = true;  // false = fast mode (rawer sound), true = HQ sinc filter

    public PsgChipGPGX() {
        this(DEFAULT_SAMPLE_RATE, ChipType.INTEGRATED);
    }

    public PsgChipGPGX(double sampleRate) {
        this(sampleRate, ChipType.INTEGRATED);
    }

    public PsgChipGPGX(double sampleRate, ChipType type) {
        setChipType(type);
        setSampleRate(sampleRate);
        reset();
    }

    public void setSampleRate(double sampleRate) {
        if (sampleRate > 0.0) {
            this.outputRate = sampleRate;
            blip.reset(INTERNAL_RATE * CLOCK_RATIO, outputRate);
            double clocksPerSample = (INTERNAL_RATE * CLOCK_RATIO) / outputRate;
            clocksPerSampleFixed = (long) (clocksPerSample * CLOCK_FRAC_UNIT + 0.5);
        }
    }

    public void setChipType(ChipType type) {
        int idx = (type == ChipType.DISCRETE) ? 0 : 1;
        this.zeroFreqInc = (type == ChipType.DISCRETE) ? 0x400 : 0x1;
        this.noiseShiftWidth = NOISE_SHIFT_WIDTH[idx];
        this.noiseBitMask = NOISE_BIT_MASK[idx];
    }

    /**
     * Set PSG quality mode.
     * @param hq true for high-quality sinc filtering (smoother),
     *           false for fast linear interpolation (rawer, brighter - matches GPGX default)
     */
    public void setHqMode(boolean hq) {
        this.hqPsg = hq;
    }

    public boolean isHqMode() {
        return hqPsg;
    }

    public void setMute(int ch, boolean mute) {
        if (ch < 0 || ch >= 4) {
            return;
        }
        if (mutes[ch] == mute) {
            return;
        }
        int deltaL = 0;
        int deltaR = 0;
        if (ch < 3) {
            if (polarity[ch] > 0) {
                int sign = mute ? -1 : 1;
                deltaL = sign * chanOut[ch][0];
                deltaR = sign * chanOut[ch][1];
            }
        } else {
            if ((noiseShiftValue & 1) != 0) {
                int sign = mute ? -1 : 1;
                deltaL = sign * chanOut[3][0];
                deltaR = sign * chanOut[3][1];
            }
        }
        // Defer mute delta
        if (deltaL != 0 || deltaR != 0) {
            chanDelta[ch][0] += deltaL;
            chanDelta[ch][1] += deltaR;
        }
        mutes[ch] = mute;
    }

    public void configure(int preamp, int panning) {
        for (int i = 0; i < 4; i++) {
            chanAmp[i][0] = preamp * ((panning >> (i + 4)) & 1);
            chanAmp[i][1] = preamp * ((panning >> (i + 0)) & 1);
        }
        for (int i = 0; i < 3; i++) {
            updateToneVolume(i);
        }
        updateNoiseVolume();
    }

    public void reset() {
        for (int i = 0; i < 4; i++) {
            regs[i * 2] = 0;
            regs[i * 2 + 1] = 0;
            freqInc[i] = ((i < 3) ? zeroFreqInc : 16) * CLOCK_RATIO;
            freqCounter[i] = 0;
            polarity[i] = -1;
            chanOut[i][0] = 0;
            chanOut[i][1] = 0;
            chanDelta[i][0] = 0;
            chanDelta[i][1] = 0;
        }
        latch = 3;
        noiseShiftValue = 1 << noiseShiftWidth;
        configure(DEFAULT_PREAMP, 0xFF);
        blip.reset(INTERNAL_RATE * CLOCK_RATIO, outputRate);
        clocks = 0;
        clockFrac = 0;
    }

    /**
     * Silence all PSG channels (ROM: zPSGSilenceAll).
     * Writes 0x9F, 0xBF, 0xDF, 0xFF to set all volumes to max attenuation.
     */
    public void silenceAll() {
        write(0x9F);
        write(0xBF);
        write(0xDF);
        write(0xFF);
    }

    /**
     * Synchronize clock to PSG cycle boundary (matches GPGX psg.clocks sync).
     * This ensures timestamps are aligned to PSG_MCYCLES_RATIO boundaries,
     * eliminating fractional clock drift.
     */
    private int syncToPsgBoundary(int currentClocks) {
        return ((currentClocks + CLOCK_RATIO - 1) / CLOCK_RATIO) * CLOCK_RATIO;
    }

    /**
     * Write with cycle synchronization before processing.
     * Matches GPGX behavior of syncing to PSG_MCYCLES_RATIO boundary at every write.
     */
    public void write(int value) {
        // Sync to PSG cycle boundary before processing (GPGX behavior)
        clocks = syncToPsgBoundary(clocks);
        int index;
        if ((value & 0x80) != 0) {
            latch = index = (value >> 4) & 0x07;
        } else {
            index = latch;
        }

        switch (index) {
            case 0:
            case 2:
            case 4: {
                int data;
                if ((value & 0x80) != 0) {
                    data = (regs[index] & 0x3F0) | (value & 0x0F);
                } else {
                    data = (regs[index] & 0x00F) | ((value & 0x3F) << 4);
                }
                regs[index] = data;
                freqInc[index >> 1] = ((data != 0) ? data : zeroFreqInc) * CLOCK_RATIO;
                if (index == 4 && (regs[6] & 0x03) == 0x03) {
                    freqInc[3] = freqInc[2];
                    freqCounter[3] = freqCounter[2];
                }
                break;
            }
            case 6: {
                int noise = value & 0x07;
                regs[6] = noise;
                int noiseFreq = noise & 0x03;
                if (noiseFreq == 0x03) {
                    freqInc[3] = freqInc[2];
                    freqCounter[3] = freqCounter[2];
                } else {
                    freqInc[3] = (0x10 << noiseFreq) * CLOCK_RATIO;
                }
                // Defer noise reset delta
                if ((noiseShiftValue & 1) != 0 && !mutes[3]) {
                    chanDelta[3][0] -= chanOut[3][0];
                    chanDelta[3][1] -= chanOut[3][1];
                }
                noiseShiftValue = 1 << noiseShiftWidth;
                break;
            }
            case 7: {
                regs[7] = value & 0x0F;
                updateNoiseVolume();
                break;
            }
            default: {
                int channel = index >> 1;
                regs[index] = value & 0x0F;
                updateToneVolume(channel);
                break;
            }
        }
    }

    public void renderStereo(int[] left, int[] right) {
        int len = Math.min(left.length, right.length);
        if (len <= 0) {
            return;
        }
        blip.ensureCapacity(len);
        long total = clockFrac + clocksPerSampleFixed * len;
        int target = (int) (total >> CLOCK_FRAC_BITS);
        clockFrac = total & (CLOCK_FRAC_UNIT - 1);
        psgUpdate(target);
        endFrame(target);
        clocks = target;
        blip.readSamples(left, right, len);
    }

    private void psgUpdate(int targetClocks) {
        // Apply pending deferred deltas at current clock position
        for (int i = 0; i < 4; i++) {
            if ((chanDelta[i][0] | chanDelta[i][1]) != 0) {
                if (hqPsg) {
                    blip.addDelta(clocks, chanDelta[i][0], chanDelta[i][1]);
                } else {
                    blip.addDeltaFast(clocks, chanDelta[i][0], chanDelta[i][1]);
                }
                chanDelta[i][0] = 0;
                chanDelta[i][1] = 0;
            }
        }

        // Run tone/noise generation with integer timestamps
        for (int i = 0; i < 4; i++) {
            int timestamp = freqCounter[i];
            int pol = polarity[i];

            if (i < 3) {
                while (timestamp < targetClocks) {
                    pol = -pol;
                    if (!mutes[i]) {
                        if (hqPsg) {
                            blip.addDelta(timestamp, pol * chanOut[i][0], pol * chanOut[i][1]);
                        } else {
                            blip.addDeltaFast(timestamp, pol * chanOut[i][0], pol * chanOut[i][1]);
                        }
                    }
                    timestamp += freqInc[i];
                }
            } else {
                int shiftValue = noiseShiftValue;
                while (timestamp < targetClocks) {
                    pol = -pol;
                    if (pol > 0) {
                        int shiftOutput = shiftValue & 0x01;
                        if ((regs[6] & 0x04) != 0) {
                            int feedback = NOISE_FEEDBACK[shiftValue & noiseBitMask];
                            shiftValue = (shiftValue >> 1) | (feedback << noiseShiftWidth);
                        } else {
                            shiftValue = (shiftValue >> 1) | (shiftOutput << noiseShiftWidth);
                        }
                        int delta = (shiftValue & 0x01) - shiftOutput;
                        if (delta != 0 && !mutes[3]) {
                            if (hqPsg) {
                                blip.addDelta(timestamp, delta * chanOut[3][0], delta * chanOut[3][1]);
                            } else {
                                blip.addDeltaFast(timestamp, delta * chanOut[3][0], delta * chanOut[3][1]);
                            }
                        }
                    }
                    timestamp += freqInc[3];
                }
                noiseShiftValue = shiftValue;
            }

            freqCounter[i] = timestamp;
            polarity[i] = pol;
        }
    }

    private void endFrame(int clocksToAdvance) {
        int delta = clocksToAdvance - clocks;
        if (delta > 0) {
            int aligned = (delta + CLOCK_RATIO - 1) / CLOCK_RATIO;
            clocks += aligned * CLOCK_RATIO;
        }
        clocks -= clocksToAdvance;
        for (int i = 0; i < 4; i++) {
            freqCounter[i] -= clocksToAdvance;
        }
        blip.endFrame(clocksToAdvance);
    }

    private void updateToneVolume(int channel) {
        int vol = regs[channel * 2 + 1] & 0x0F;
        int base = VOLUME_TABLE[vol];
        int newL = (base * chanAmp[channel][0]) / 100;
        int newR = (base * chanAmp[channel][1]) / 100;
        // Defer volume delta to be applied at next psgUpdate
        if (polarity[channel] > 0 && !mutes[channel]) {
            chanDelta[channel][0] += newL - chanOut[channel][0];
            chanDelta[channel][1] += newR - chanOut[channel][1];
        }
        chanOut[channel][0] = newL;
        chanOut[channel][1] = newR;
    }

    private void updateNoiseVolume() {
        int vol = regs[7] & 0x0F;
        int base = VOLUME_TABLE[vol];
        int newL = (base * chanAmp[3][0]) / 100;
        int newR = (base * chanAmp[3][1]) / 100;
        // Defer volume delta to be applied at next psgUpdate
        if ((noiseShiftValue & 1) != 0 && !mutes[3]) {
            chanDelta[3][0] += newL - chanOut[3][0];
            chanDelta[3][1] += newR - chanOut[3][1];
        }
        chanOut[3][0] = newL;
        chanOut[3][1] = newR;
    }
}
