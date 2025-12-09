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
        int ptr = voicePtr;
        if (ptr == 0) return null;

        // Relocate voice table from Z80 address space if needed.
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

        // Sonic 2 SMPS Z80 uses 25-byte voices (slot order).
        int stride = 25;
        offset += (voiceId * stride);

        if (offset < 0 || offset + 25 > data.length) return null;

        // Return raw 25-byte voice in SMPS slot order (Op1, Op3, Op2, Op4).
        // This corresponds to Hardware Order (Register Offsets 0x00, 0x04, 0x08, 0x0C).
        byte[] voice = new byte[25];
        System.arraycopy(data, offset, voice, 0, 25);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        return Sonic2PsgEnvelopes.getEnvelope(id);
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8); // Little Endian
    }

    @Override
    public int getBaseNoteOffset() {
        return 13; // Sonic 2 uses Base Note B (+13 offset for 0x81)
    }
}
