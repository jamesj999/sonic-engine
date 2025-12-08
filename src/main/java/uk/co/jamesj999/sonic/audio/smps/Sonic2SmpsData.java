package uk.co.jamesj999.sonic.audio.smps;

public class Sonic2SmpsData extends AbstractSmpsData {

    public Sonic2SmpsData(byte[] data) {
        this(data, 0);
    }

    public Sonic2SmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    @Override
    protected void parseHeader() {
        if (data.length >= 8) {
            this.voicePtr = read16(0);
            this.channels = data[2] & 0xFF; // DAC + FM
            this.psgChannels = data[3] & 0xFF;
            this.dividingTiming = data[4] & 0xFF;
            this.tempo = data[5] & 0xFF;
            this.dacPointer = read16(6);

            // SMPS header layout (Sonic 2 final): DAC ptr @ 6, then FM entries (ptr + key + vol) starting at 0x06.
            // Note: SMPSPlay reads tracks starting from 0x06. Track 0 is usually DAC.
            int fmStart = 0x06;
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
                offset += 4; // skip key + volume
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
                offset += 6; // pointer(2) + key(1) + vol(1) + mod(1) + ins(1)
            }
        }
    }

    @Override
    public byte[] getVoice(int voiceId) {
        int voiceLen = 21; // Sonic 2 (Little Endian / Hardware Order) voices are 21 bytes but may be packed tighter?
        // Wait, SMPS Z80 stores 21 bytes?
        // Let's assume the pointer logic is correct.
        // But we need to use 'voicePtr' which might be Z80 relocated.

        // Relocate logic duplicated from SmpsSequencer?
        // Ideally AbstractSmpsData should have relocate() or similar.
        // But relocate uses z80Start.

        int ptr = voicePtr;
        if (ptr == 0) return null;

        // Basic relocation:
        int offset = -1;
        if (ptr >= 0 && ptr < data.length) {
            offset = ptr;
        } else if (z80StartAddress > 0) {
            int rel = ptr - z80StartAddress;
            if (rel >= 0 && rel < data.length) {
                offset = rel;
            }
        }

        if (offset < 0) return null;

        // 25-byte stride for Sonic 1, but for Sonic 2?
        // Sonic 2 SMPS Z80 seems to use 25 bytes? Or 21?
        // Memory said: "Sonic 2 (Little Endian) also uses 25 bytes (21 used + 4 padding), despite memory suggesting 19."
        // So we use 25 byte stride.
        int stride = 25;

        offset += (voiceId * stride);

        if (offset < 0 || offset + 21 > data.length) return null; // Need at least 21 bytes

        // Create 25-byte normalized array (Standard Order: 1, 3, 2, 4)
        // Source is Hardware Order (1, 2, 3, 4)
        // Structure: Header, DT, RS, AM, D2R, RR, TL(padding)
        // Target: Header, DT, TL(0), RS, AM, D2R, RR

        byte[] voice = new byte[25];
        voice[0] = data[offset]; // Header
        System.arraycopy(data, offset + 1, voice, 1, 4); // DT (Hardware Order)

        // TL slots (5-8) initialized to 0.

        // Copy remaining parameters (RS, AM, D2R, RR)
        // Source offset + 5.
        // We need 16 bytes (4 parameters * 4 operators).
        // Check bounds
        if (offset + 5 + 16 <= data.length) {
            System.arraycopy(data, offset + 5, voice, 9, 16);
        }

        // SWAP Operators 2 and 3 to convert Hardware Order (1, 2, 3, 4) to Standard Order (1, 3, 2, 4).
        // Index 2 corresponds to Op 2. Index 3 corresponds to Op 3.
        // Swap indices 2 and 3 in each 4-byte group.
        // Groups start at: 1 (DT), 5 (TL), 9 (RS), 13 (AM), 17 (D2R), 21 (RR).

        for (int i = 1; i < 25; i += 4) {
            byte temp = voice[i + 2]; // Index 2 (Hardware Op 3? Wait)
            // Hardware Order: 0=Op1, 1=Op2, 2=Op3, 3=Op4.
            // Wait.
            // Hardware Order is: Op1, Op2, Op3, Op4.
            // Standard Order is: Op1, Op3, Op2, Op4.
            // Source[0] -> Op1 -> Target[0]
            // Source[1] -> Op2 -> Target[2]
            // Source[2] -> Op3 -> Target[1]
            // Source[3] -> Op4 -> Target[3]

            // So we take Source[1] and put to Target[2].
            // Take Source[2] and put to Target[1].

            // In my array copy above, I copied Source -> Target directly (Hardware Order).
            // So voice[i+1] is Source[1] (Op2).
            // voice[i+2] is Source[2] (Op3).

            // We want Target[1] to be Op3 (Source[2]).
            // We want Target[2] to be Op2 (Source[1]).

            // So Swap voice[i+1] and voice[i+2].

            temp = voice[i + 1];
            voice[i + 1] = voice[i + 2];
            voice[i + 2] = temp;
        }

        return voice;
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8); // Little Endian
    }

    @Override
    public int getBaseNoteOffset() {
        return 1; // Sonic 2 uses Base Note B (+1 offset for 0x81)
    }
}
