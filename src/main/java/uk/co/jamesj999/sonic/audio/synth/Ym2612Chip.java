package uk.co.jamesj999.sonic.audio.synth;

public class Ym2612Chip {
    private static class Channel {
        boolean keyOn;
        int fNum;
        int block;
        double phase;
        int tl;
    }

    private final Channel[] channels = new Channel[6];

    public Ym2612Chip() {
        for(int i=0; i<6; i++) channels[i] = new Channel();
    }

    public void write(int port, int reg, int val) {
        if (port == 0 && reg == 0x28) { // Key On/Off
            int chIdx = (val & 0x7);
            if (chIdx >= 0 && chIdx <= 2) {
                // Ch 0-2
            } else if (chIdx >= 4 && chIdx <= 6) {
                chIdx -= 1; // Map 4-6 to 3-5
            } else {
                return;
            }
            int ops = (val >> 4) & 0xF;
            channels[chIdx].keyOn = (ops != 0);
        }

        // Frequency
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (port * 3) + (reg - 0xA0);
            channels[ch].fNum = (channels[ch].fNum & 0x700) | val;
        }
        if (reg >= 0xA4 && reg <= 0xA6) {
            int ch = (port * 3) + (reg - 0xA4);
            channels[ch].fNum = (channels[ch].fNum & 0xFF) | ((val & 0x7) << 8);
            channels[ch].block = (val >> 3) & 0x7;
        }

        // Total Level (Volume) - Assuming Op 4 control
        if (reg >= 0x40 && reg <= 0x4F) {
            int offset = reg & 0xF;
            // Offsets 0,4,8,C correspond to slots.
            // 0xC, 0xD, 0xE are Op4 for Ch 1,2,3 (or 4,5,6)
            if (offset >= 0xC && offset <= 0xE) {
                int ch = (port * 3) + (offset - 0xC);
                channels[ch].tl = val;
            }
        }
    }

    public void render(short[] buffer) {
        double clock = 7670453.0;

        for (int i = 0; i < buffer.length; i++) {
            int sample = 0;

            for (Channel ch : channels) {
                if (!ch.keyOn) continue;

                double freq = (ch.fNum * clock) / (72.0 * (1 << (20 - ch.block)));
                if (freq < 10 || freq > 22000) continue;

                double phaseInc = (freq * 2.0 * Math.PI) / 44100.0;
                ch.phase += phaseInc;
                if (ch.phase > 2.0 * Math.PI) ch.phase -= 2.0 * Math.PI;

                int vol = (127 - (ch.tl & 0x7F));
                if (vol < 0) vol = 0;

                // Simple sine wave
                sample += (int) (Math.sin(ch.phase) * vol * 50);
            }

            int mixed = buffer[i] + sample;
            if (mixed > 32000) mixed = 32000;
            if (mixed < -32000) mixed = -32000;
            buffer[i] = (short) mixed;
        }
    }
}
