package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class Ym2612Chip {
    private DacData dacData;
    private int currentDacSampleId = -1;
    private double dacPos;
    private double dacStep;
    private boolean dacEnabled; // Reg 2B

    private enum EnvState { ATTACK, DECAY1, DECAY2, RELEASE, IDLE }

    private static class Channel {
        boolean keyOn;
        int fNum;
        int block;
        double phase;
        int tl;

        // Envelope
        EnvState envState = EnvState.IDLE;
        double envAmp = 0.0; // 0.0 (Silence) to 1.0 (Full)
        int ar = 31, d1r = 0, d2r = 0, rr = 15, d1l = 0;
    }

    private final Channel[] channels = new Channel[6];

    public Ym2612Chip() {
        for(int i=0; i<6; i++) channels[i] = new Channel();
    }

    public void setDacData(DacData data) {
        this.dacData = data;
    }

    public void playDac(int note) {
        if (dacData == null) return;
        DacData.DacEntry entry = dacData.mapping.get(note);
        if (entry != null) {
            this.currentDacSampleId = entry.sampleId;
            this.dacPos = 0;
            // Rough rate approximation
            // Rate 0 is fast, Rate 20 is slow.
            // Base step 0.5?
            this.dacStep = 0.5 / (1.0 + entry.rate * 0.05);
        }
    }

    public void write(int port, int reg, int val) {
        if (port == 0 && reg == 0x28) { // Key On/Off
            int chIdx = (val & 0x7);
            if (chIdx >= 0 && chIdx <= 2) { }
            else if (chIdx >= 4 && chIdx <= 6) chIdx -= 1;
            else return;

            int ops = (val >> 4) & 0xF;
            Channel ch = channels[chIdx];
            if (ops != 0) { // Key On
                if (!ch.keyOn) {
                    ch.keyOn = true;
                    ch.envState = EnvState.ATTACK;
                }
            } else { // Key Off
                if (ch.keyOn) {
                    ch.keyOn = false;
                    ch.envState = EnvState.RELEASE;
                }
            }
        }

        if (port == 0 && reg == 0x2B) {
            dacEnabled = (val & 0x80) != 0;
        }

        // Handle Regs...
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (port * 3) + (reg - 0xA0);
            channels[ch].fNum = (channels[ch].fNum & 0x700) | val;
        }
        if (reg >= 0xA4 && reg <= 0xA6) {
            int ch = (port * 3) + (reg - 0xA4);
            channels[ch].fNum = (channels[ch].fNum & 0xFF) | ((val & 0x7) << 8);
            channels[ch].block = (val >> 3) & 0x7;
        }
        if (reg >= 0x40 && reg <= 0x4F) {
            int offset = reg & 0xF;
            if (offset >= 0xC && offset <= 0xE) { // Op 4
                int ch = (port * 3) + (offset - 0xC);
                channels[ch].tl = val;
            }
        }
        // Envelope Regs (Simplified: Assign to Channel, ignoring Op specifics)
        if (reg >= 0x50 && reg <= 0x5F) { // AR
             // ...
        }
        // For brevity in this turn, using defaults or simple parsing if easy.
    }

    public void render(short[] buffer) {
        double clock = 7670453.0;

        for (int i = 0; i < buffer.length; i++) {
            int sample = 0;

            // Mix DAC
            if (dacEnabled && currentDacSampleId != -1 && dacData != null) {
                byte[] data = dacData.samples.get(currentDacSampleId);
                if (data != null && dacPos < data.length) {
                    int s = (data[(int)dacPos] & 0xFF) - 128; // Unsigned to Signed
                    sample += s * 100; // Gain
                    dacPos += dacStep;
                }
            }

            // Mix FM
            for (int c = 0; c < 6; c++) {
                if (c == 5 && dacEnabled) continue; // Ch6 disabled by DAC

                Channel ch = channels[c];

                // Envelope Tick (Simplified)
                double rate = 0.001; // Base rate
                switch (ch.envState) {
                    case ATTACK:
                        ch.envAmp += rate * 10;
                        if (ch.envAmp >= 1.0) { ch.envAmp = 1.0; ch.envState = EnvState.DECAY1; }
                        break;
                    case DECAY1:
                        // Decay to sustain?
                        ch.envAmp -= rate;
                        if (ch.envAmp <= 0.8) ch.envState = EnvState.DECAY2;
                        break;
                    case DECAY2:
                        ch.envAmp -= rate * 0.1;
                        if (ch.envAmp < 0) ch.envAmp = 0;
                        break;
                    case RELEASE:
                        ch.envAmp -= rate * 2;
                        if (ch.envAmp <= 0) { ch.envAmp = 0; ch.envState = EnvState.IDLE; }
                        break;
                    case IDLE:
                        ch.envAmp = 0;
                        break;
                }

                if (ch.envAmp <= 0) continue;

                double freq = (ch.fNum * clock) / (72.0 * (1 << (20 - ch.block)));
                if (freq < 10 || freq > 22000) continue;

                double phaseInc = (freq * 2.0 * Math.PI) / 44100.0;
                ch.phase += phaseInc;
                if (ch.phase > 2.0 * Math.PI) ch.phase -= 2.0 * Math.PI;

                int vol = (127 - (ch.tl & 0x7F));
                if (vol < 0) vol = 0;

                sample += (int) (Math.sin(ch.phase) * vol * 50 * ch.envAmp);
            }

            int mixed = buffer[i] + sample;
            if (mixed > 32000) mixed = 32000;
            if (mixed < -32000) mixed = -32000;
            buffer[i] = (short) mixed;
        }
    }
}
