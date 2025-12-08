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
        // Sonic 1 Header Layout (Generic SMPS 68k)
        // Assuming Big Endian pointers.
        // Offsets:
        // 00: Voice Ptr (16-bit)
        // 02: Channels (2 FM)
        // 03: PSG Channels (3 PSG)
        // 04: Tempo
        // 05: Dividing Timing
        // 06: DAC Ptr (16-bit)
        // 08: FM1 Ptr (16-bit), 0A: Transpose, 0B: Volume
        // ...

        if (data.length >= 8) {
            this.voicePtr = read16(0);
            this.channels = data[2] & 0xFF;
            this.psgChannels = data[3] & 0xFF;
            this.tempo = data[4] & 0xFF;
            this.dividingTiming = data[5] & 0xFF;
            this.dacPointer = read16(6);

            int fmStart = 0x08; // Pointers start here
            this.fmPointers = new int[channels];
            this.fmKeyOffsets = new int[channels];
            this.fmVolumeOffsets = new int[channels];
            int offset = fmStart;
            for (int i = 0; i < channels; i++) {
                if (offset + 3 < data.length) {
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
            // Check for relative address
            // Usually absolute ROM address in S1 (68k pointer).
            // But we have a chunk of data.
            // If the pointer is > start address, we map it.
            // S1 SMPS pointers are usually 16-bit offsets from the start of the song?
            // No, Header pointers are 16-bit relative to start of song header in some formats.
            // In S1 (68k), header pointers are 16-bit relative to start of header.
            // Let's assume relative to header start (offset 0).
            offset = ptr;
        }

        if (offset < 0) offset = ptr;

        int stride = 25;
        offset += (voiceId * stride);

        if (offset < 0 || offset + 25 > data.length) return null;

        byte[] raw = new byte[25];
        System.arraycopy(data, offset, raw, 0, 25);

        // Convert S1 (Standard Order 1,3,2,4, TL at end) to Hardware Order (1,2,3,4, TL interleaved)
        byte[] voice = new byte[25];
        voice[0] = raw[0]; // FB/Algo
        System.arraycopy(raw, 1, voice, 1, 4); // DT
        System.arraycopy(raw, 21, voice, 5, 4); // TL (Moved from end)
        System.arraycopy(raw, 5, voice, 9, 4); // RS
        System.arraycopy(raw, 9, voice, 13, 4); // AM
        System.arraycopy(raw, 13, voice, 17, 4); // D2R
        System.arraycopy(raw, 17, voice, 21, 4); // RR

        // Swap Ops 2 and 3 (Indices 1 and 2 in groups)
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
        return 0;
    }
}
