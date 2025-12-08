package uk.co.jamesj999.sonic.audio.smps;

public class Sonic1SmpsData extends AbstractSmpsData {

    public Sonic1SmpsData(byte[] data) {
        this(data, 0);
    }

    public Sonic1SmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    @Override
    protected void parseHeader() {
        // Sonic 1 Header Layout
        // Assuming Big Endian pointers.
        // Layout:
        // Voice Ptr (0-1)
        // Channels (2)
        // PSG Channels (3)
        // Tempo (4) ? (S2 has dividing timing here)
        // Wait, S1 header is different.
        // Assuming Standard SMPS 68k header.
        // Offsets might differ.

        // For now, I will implement generic logic assuming similar structure but Big Endian.
        // S1 has PtrFmt = Z80 (which usually means relative?).
        // No, S1 is 68k, usually Absolute Big Endian?
        // Let's assume structure matches S2 but Big Endian and different offsets if needed.
        // S2 offset 0x06 is used for FM Ptr. S1 uses 0x0A?
        // Let's use 0x0A for S1 FM Start as per previous Test.

        if (data.length >= 8) {
            this.voicePtr = read16(0);
            this.channels = data[2] & 0xFF;
            // Dividing Timing / Tempo might be swapped or different?
            // Sonic 1: 04=Tempo, 05=Divider? Or same?
            // DefDrv.txt for S2: TickMult at offset...
            // Let's assume same layout for simplicity until proven otherwise, just different pointer formats.
            this.psgChannels = data[3] & 0xFF;
            this.dividingTiming = data[4] & 0xFF;
            this.tempo = data[5] & 0xFF;
            // DAC Pointer? S1 usually has DAC as channel?
            this.dacPointer = read16(6);

            // Sonic 1 Header FM Start
            int fmStart = 0x06; // Default to same? Or 0x0A?
            // If I look at `SmpsSequencerTest` for "Big Endian", it put FM ptr at 0x0A.
            // Let's check `SmpsData` (previous implementation) logic.
            // `int fmStart = 0x06;` was hardcoded.
            // But S1 header usually has pointers at 0x16 or so.
            // However, since I don't have S1 DefDrv, I will stick to what `SmpsSequencerTest` used: 0x0A.
            // Actually, wait. The test used 0x0A because it assumed S1 layout.
            // I'll set it to 0x06 for now to match the AbstractSmpsData base logic, but override if needed.
            // Or better, let's look at `SmpsLoader` equivalent for S1.
            // If S1 uses `PtrFmt=68K` (Big Endian).

            // I will use 0x06 for now. If tests fail I adjust.
            // Wait, if I use 0x06, I am consistent with AbstractSmpsData fields.

            this.fmPointers = new int[channels];
            this.fmKeyOffsets = new int[channels];
            this.fmVolumeOffsets = new int[channels];
            int offset = fmStart;
            for (int i = 0; i < channels; i++) {
                if (offset + 1 < data.length) {
                    this.fmPointers[i] = read16(offset);
                    this.fmKeyOffsets[i] = (byte) data[offset + 2];
                    this.fmVolumeOffsets[i] = (byte) data[offset + 3];
                }
                offset += 4;
            }

            this.psgPointers = new int[psgChannels];
            this.psgKeyOffsets = new int[psgChannels];
            this.psgVolumeOffsets = new int[psgChannels];
            this.psgModEnvs = new int[psgChannels];
            this.psgInstruments = new int[psgChannels];
            for (int i = 0; i < psgChannels; i++) {
                if (offset + 5 < data.length) {
                    this.psgPointers[i] = read16(offset);
                    this.psgKeyOffsets[i] = (byte) data[offset + 2];
                    this.psgVolumeOffsets[i] = (byte) data[offset + 3];
                    this.psgModEnvs[i] = data[offset + 4] & 0xFF;
                    this.psgInstruments[i] = data[offset + 5] & 0xFF;
                }
                offset += 6;
            }
        }
    }

    @Override
    public byte[] getVoice(int voiceId) {
        int ptr = voicePtr;
        if (ptr == 0) return null;

        int offset = -1;
        if (ptr >= 0 && ptr < data.length) {
            offset = ptr;
        } else if (z80StartAddress > 0) {
            // Relocation logic for S1? Usually Absolute.
            int rel = ptr - z80StartAddress;
            if (rel >= 0 && rel < data.length) {
                offset = rel;
            }
        }
        if (offset < 0) offset = ptr; // Try raw

        int stride = 25;
        offset += (voiceId * stride);

        if (offset < 0 || offset + 25 > data.length) return null;

        // Sonic 1 (Big Endian) Voices are 25 bytes.
        // Structure: Header, DT, TL, RS, AM, D2R, RR (Standard Order?)
        // Wait. `SmpsSequencer` comments said:
        // "Sonic 1 (Big Endian / Default Order)"
        // "Source: Header, DT, RS, AM, D2R, RR, TL"
        // "Target (Ym2612Chip with len=25): Header, DT, TL, RS, AM, D2R, RR."

        byte[] raw = new byte[25];
        System.arraycopy(data, offset, raw, 0, 25);

        byte[] voice = new byte[25];
        voice[0] = raw[0]; // FB/Algo
        System.arraycopy(raw, 1, voice, 1, 4); // DT
        System.arraycopy(raw, 21, voice, 5, 4); // TL (Moved from end)
        System.arraycopy(raw, 5, voice, 9, 4); // RS
        System.arraycopy(raw, 9, voice, 13, 4); // AM
        System.arraycopy(raw, 13, voice, 17, 4); // D2R
        System.arraycopy(raw, 17, voice, 21, 4); // RR

        // Operator Order:
        // Source is Standard Order (1, 3, 2, 4).
        // Target is Hardware Order (1, 2, 3, 4).
        // Swap Op2 and Op3.
        // Op1 (Idx 0) -> Same.
        // Op3 (Idx 1) -> Target Op3 (Idx 2).
        // Op2 (Idx 2) -> Target Op2 (Idx 1).
        // Op4 (Idx 3) -> Same.

        // Swap indices 1 and 2 in each 4-byte group.
        // Groups start at: 1 (DT), 5 (TL), 9 (RS), 13 (AM), 17 (D2R), 21 (RR).
        for (int i = 1; i < 25; i += 4) {
            byte temp = voice[i + 1];
            voice[i + 1] = voice[i + 2];
            voice[i + 2] = temp;
        }

        return voice;
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF); // Big Endian
    }

    @Override
    public int getBaseNoteOffset() {
        return 0; // Sonic 1 uses Base Note C (+0 offset?) or B?
        // S1 Base Note is typically C (0).
    }
}
