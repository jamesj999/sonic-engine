package uk.co.jamesj999.sonic.audio.synth;

public class PsgChip {
    public enum ChipType {
        INTEGRATED,
        DISCRETE
    }

    private static final double INPUT_CLOCK = 3579545.0; // NTSC PSG input clock (Hz)
    private static final double INTERNAL_RATE = INPUT_CLOCK / 16.0;
    private static final double DEFAULT_SAMPLE_RATE = 44100.0;

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
    private final double[] freqCounter = new double[4];
    private final int[] polarity = new int[4];
    private final int[][] chanOut = new int[4][2];
    private final int[][] chanAmp = new int[4][2];
    private final boolean[] mutes = new boolean[4];

    private int latch;
    private int zeroFreqInc;
    private int noiseShiftValue;
    private int noiseShiftWidth;
    private int noiseBitMask;

    private double outputRate = DEFAULT_SAMPLE_RATE;
    private final BlipDeltaBuffer blip = new BlipDeltaBuffer(INTERNAL_RATE, DEFAULT_SAMPLE_RATE);
    private double clocks = 0.0;

    public PsgChip() {
        this(DEFAULT_SAMPLE_RATE, ChipType.INTEGRATED);
    }

    public PsgChip(double sampleRate) {
        this(sampleRate, ChipType.INTEGRATED);
    }

    public PsgChip(double sampleRate, ChipType type) {
        setChipType(type);
        setSampleRate(sampleRate);
        reset();
    }

    public void setSampleRate(double sampleRate) {
        if (sampleRate > 0.0) {
            this.outputRate = sampleRate;
            blip.reset(INTERNAL_RATE, outputRate);
        }
    }

    public void setChipType(ChipType type) {
        int idx = (type == ChipType.DISCRETE) ? 0 : 1;
        this.zeroFreqInc = (type == ChipType.DISCRETE) ? 0x400 : 0x1;
        this.noiseShiftWidth = NOISE_SHIFT_WIDTH[idx];
        this.noiseBitMask = NOISE_BIT_MASK[idx];
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
        if (deltaL != 0 || deltaR != 0) {
            blip.addDelta(clocks, deltaL, deltaR);
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
            freqInc[i] = (i < 3) ? zeroFreqInc : 16;
            freqCounter[i] = 0;
            polarity[i] = -1;
            chanOut[i][0] = 0;
            chanOut[i][1] = 0;
        }
        latch = 3;
        noiseShiftValue = 1 << noiseShiftWidth;
        configure(DEFAULT_PREAMP, 0xFF);
        blip.reset(INTERNAL_RATE, outputRate);
        clocks = 0.0;
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

    public void write(int value) {
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
                freqInc[index >> 1] = (data != 0) ? data : zeroFreqInc;
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
                    freqInc[3] = (0x10 << noiseFreq);
                }
                if ((noiseShiftValue & 1) != 0 && !mutes[3]) {
                    blip.addDelta(clocks, -chanOut[3][0], -chanOut[3][1]);
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
        double clocksPerSample = INTERNAL_RATE / outputRate;
        double target = clocks + len * clocksPerSample;
        psgUpdate(target);
        clocks = target;
        blip.readSamples(left, right, len);
        endFrame(target);
    }

    private void psgUpdate(double targetClocks) {
        for (int i = 0; i < 4; i++) {
            double timestamp = freqCounter[i];
            int pol = polarity[i];

            if (i < 3) {
                while (timestamp < targetClocks) {
                    pol = -pol;
                    if (!mutes[i]) {
                        blip.addDelta(timestamp, pol * chanOut[i][0], pol * chanOut[i][1]);
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
                            blip.addDelta(timestamp, delta * chanOut[3][0], delta * chanOut[3][1]);
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

    private void endFrame(double clocksToAdvance) {
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
        if (polarity[channel] > 0 && !mutes[channel]) {
            blip.addDelta(clocks, newL - chanOut[channel][0], newR - chanOut[channel][1]);
        }
        chanOut[channel][0] = newL;
        chanOut[channel][1] = newR;
    }

    private void updateNoiseVolume() {
        int vol = regs[7] & 0x0F;
        int base = VOLUME_TABLE[vol];
        int newL = (base * chanAmp[3][0]) / 100;
        int newR = (base * chanAmp[3][1]) / 100;
        if ((noiseShiftValue & 1) != 0 && !mutes[3]) {
            blip.addDelta(clocks, newL - chanOut[3][0], newR - chanOut[3][1]);
        }
        chanOut[3][0] = newL;
        chanOut[3][1] = newR;
    }
}
