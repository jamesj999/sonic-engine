package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class Ym2612Chip {
    private DacData dacData;
    private int currentDacSampleId = -1;
    private double dacPos;
    private double dacStep;
    private boolean dacEnabled;

    private enum EnvState { ATTACK, DECAY1, DECAY2, RELEASE, IDLE }

    private static class Operator {
        int dt1, mul;
        int tl;
        int rs, ar;
        int am, d1r;
        int d2r;
        int d1l, rr;

        // Runtime state
        EnvState envState = EnvState.IDLE;
        double envAmp = 0.0;
    }

    private static class Channel {
        boolean keyOn;
        int fNum;
        int block;
        double phase;

        // Channel registers
        int feedback, algo;
        int ams, fms;
        int l, r;

        Operator[] ops = new Operator[4];

        Channel() {
            for(int i=0; i<4; i++) ops[i] = new Operator();
        }
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
            this.dacStep = 0.5 / (1.0 + entry.rate * 0.05);
        }
    }

    /**
     * Loads a Sonic 2 format voice (25 bytes) into the specified channel.
     * Format:
     * 00: FB/Algo
     * 01-04: 30 (DT/MUL) for Op1, Op2, Op3, Op4
     * 05-08: 50 (RS/AR)
     * 09-0C: 60 (AM/D1R)
     * 0D-10: 70 (D2R)
     * 11-14: 80 (D1L/RR)
     * 15-18: 40 (TL)
     */
    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 25) return;
        Channel ch = channels[chIdx];

        int val00 = voice[0] & 0xFF;
        ch.feedback = (val00 >> 3) & 7;
        ch.algo = val00 & 7;

        for (int op = 0; op < 4; op++) {
            Operator o = ch.ops[op];
            // 30: DT/MUL
            int v30 = voice[1 + op] & 0xFF;
            o.dt1 = (v30 >> 4) & 7;
            o.mul = v30 & 0xF;

            // 50: RS/AR
            int v50 = voice[5 + op] & 0xFF;
            o.rs = (v50 >> 6) & 3;
            o.ar = v50 & 0x1F;

            // 60: AM/D1R
            int v60 = voice[9 + op] & 0xFF;
            o.am = (v60 >> 7) & 1;
            o.d1r = v60 & 0x1F;

            // 70: D2R
            int v70 = voice[13 + op] & 0xFF;
            o.d2r = v70 & 0x1F;

            // 80: D1L/RR
            int v80 = voice[17 + op] & 0xFF;
            o.d1l = (v80 >> 4) & 0xF;
            o.rr = v80 & 0xF;

            // 40: TL
            int v40 = voice[21 + op] & 0xFF;
            o.tl = v40 & 0x7F;
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

            // Update envelope state for each op
            for (int i = 0; i < 4; i++) {
                boolean on = ((ops >> i) & 1) != 0; // Wait, D4=Op1, D5=Op2...
                // Ops: 1, 2, 3, 4 -> bits 0, 1, 2, 3 of nibble?
                // D4 is Op1. D5 Op2.
                // So (val >> 4) bit 0 is Op1.

                Operator op = ch.ops[i];
                if (on) {
                    if (!ch.keyOn) { // Assuming 'keyOn' flag represents active note
                        op.envState = EnvState.ATTACK;
                    }
                } else {
                    op.envState = EnvState.RELEASE;
                }
            }
            ch.keyOn = (ops != 0);
        }

        if (port == 0 && reg == 0x2B) {
            dacEnabled = (val & 0x80) != 0;
        }

        // Freq
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (port * 3) + (reg - 0xA0);
            channels[ch].fNum = (channels[ch].fNum & 0x700) | val;
        }
        if (reg >= 0xA4 && reg <= 0xA6) {
            int ch = (port * 3) + (reg - 0xA4);
            channels[ch].fNum = (channels[ch].fNum & 0xFF) | ((val & 0x7) << 8);
            channels[ch].block = (val >> 3) & 0x7;
        }

        // Register writes for params (simplified logic, usually handled by setInstrument)
        // If SMPS writes directly, we should handle it, but for now relying on setInstrument.
    }

    public void render(short[] buffer) {
        double clock = 7670453.0;

        for (int i = 0; i < buffer.length; i++) {
            int sample = 0;

            // Mix DAC
            if (dacEnabled && currentDacSampleId != -1 && dacData != null) {
                byte[] data = dacData.samples.get(currentDacSampleId);
                if (data != null && dacPos < data.length) {
                    int s = (data[(int)dacPos] & 0xFF) - 128;
                    sample += s * 100;
                    dacPos += dacStep;
                }
            }

            // Mix FM
            for (int c = 0; c < 6; c++) {
                if (c == 5 && dacEnabled) continue;

                Channel ch = channels[c];

                // We only render Op4 (Carrier) for simplicity, or sum them?
                // Algorithm 7 sums all. Algorithm 0-6 chain them.
                // Op4 is usually the final carrier in most algos (except 7).
                // We'll render Op4 output modulated by its envelope.

                Operator op = ch.ops[3]; // Op4

                // Envelope Tick
                double rateBase = 1.0 / 44100.0;
                // AR: 0-31. 31 is instant.
                double arRate = (op.ar > 0) ? (op.ar / 31.0) * 0.5 : 0;

                switch (op.envState) {
                    case ATTACK:
                        op.envAmp += arRate;
                        if (op.ar == 31) op.envAmp = 1.0;
                        if (op.envAmp >= 1.0) { op.envAmp = 1.0; op.envState = EnvState.DECAY1; }
                        break;
                    case DECAY1:
                        op.envAmp -= 0.0001 * op.d1r; // Simplify
                        // D1L is sustain level? No, secondary decay level.
                        // D1L: 0-15. Multiplied by 8 gives attenuation?
                        // Let's say D1L=0 is Max (1.0). 15 is Min.
                        double sustainLevel = 1.0 - (op.d1l / 15.0);
                        if (op.envAmp <= sustainLevel) op.envState = EnvState.DECAY2;
                        break;
                    case DECAY2:
                        op.envAmp -= 0.00001 * op.d2r;
                        if (op.envAmp < 0) op.envAmp = 0;
                        break;
                    case RELEASE:
                        op.envAmp -= 0.001 * (op.rr > 0 ? op.rr : 1);
                        if (op.envAmp <= 0) { op.envAmp = 0; op.envState = EnvState.IDLE; }
                        break;
                    case IDLE:
                        op.envAmp = 0;
                        break;
                }

                if (op.envAmp <= 0) continue;

                double freq = (ch.fNum * clock) / (72.0 * (1 << (20 - ch.block)));
                double mul = (op.mul == 0) ? 0.5 : op.mul;
                freq *= mul;

                if (freq < 10 || freq > 22000) continue;

                double phaseInc = (freq * 2.0 * Math.PI) / 44100.0;
                ch.phase += phaseInc;
                if (ch.phase > 2.0 * Math.PI) ch.phase -= 2.0 * Math.PI;

                int vol = (127 - (op.tl & 0x7F));
                if (vol < 0) vol = 0;

                sample += (int) (Math.sin(ch.phase) * vol * 50 * op.envAmp);
            }

            int mixed = buffer[i] + sample;
            if (mixed > 32000) mixed = 32000;
            if (mixed < -32000) mixed = -32000;
            buffer[i] = (short) mixed;
        }
    }
}
