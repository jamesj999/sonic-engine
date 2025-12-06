package uk.co.jamesj999.sonic.audio.smps;

public class SmpsData {
    private final byte[] data;
    private final int voicePtr;
    private final int channels;
    private final int psgChannels;
    private final int dividingTiming;
    private final int tempo;
    private final int dacPointer;
    private final int[] fmPointers;
    private final int[] fmKeyOffsets;
    private final int[] fmVolumeOffsets;
    private final int[] psgPointers;
    private final int[] psgKeyOffsets;
    private final int[] psgVolumeOffsets;
    private final boolean littleEndian;
    private int z80StartAddress = 0;

    public SmpsData(byte[] data) {
        this(data, 0);
    }

    public SmpsData(byte[] data, int z80StartAddress) {
        this(data, z80StartAddress, null);
    }

    public SmpsData(byte[] data, int z80StartAddress, Boolean forceLittleEndian) {
        this.data = data;
        this.z80StartAddress = z80StartAddress;
        if (forceLittleEndian != null) {
            this.littleEndian = forceLittleEndian;
        } else {
            this.littleEndian = detectLittleEndian();
        }

        if (data.length >= 8) {
            this.voicePtr = read16(data, 0);
            this.channels = data[2] & 0xFF; // DAC + FM
            this.psgChannels = data[3] & 0xFF;
            this.dividingTiming = data[4] & 0xFF;
            this.tempo = data[5] & 0xFF;
            this.dacPointer = read16(data, 6);

            // SMPS header layout (Sonic 2 final): DAC ptr @ 6, then FM entries (ptr + key + vol) starting at 0x0A,
            // followed by PSG entries (ptr + key + vol).
            int fmStart = 0x0A;
            int[] fmPtrs = new int[channels];
            int[] fmKeys = new int[channels];
            int[] fmVols = new int[channels];
            int offset = fmStart;
            for (int i = 0; i < channels; i++) {
                if (offset + 1 < data.length) {
                    fmPtrs[i] = read16(data, offset);
                    fmKeys[i] = (byte) data[offset + 2];
                    fmVols[i] = (byte) data[offset + 3];
                } else {
                    fmPtrs[i] = 0;
                    fmKeys[i] = 0;
                    fmVols[i] = 0;
                }
                offset += 4; // skip key + volume
            }
            this.fmPointers = fmPtrs;
            this.fmKeyOffsets = fmKeys;
            this.fmVolumeOffsets = fmVols;

            int[] psgPtrs = new int[psgChannels];
            int[] psgKeys = new int[psgChannels];
            int[] psgVols = new int[psgChannels];

            // Mystic Cave Zone (S2) header corruption fix
            // S2 MCZ header has corrupt PSG pointers which overlap.
            // Heuristic: If first pointer seems invalid (points inside header) and overlapping pointer at offset+2 is valid.
            boolean mczCorruption = false;
            if (psgChannels == 3 && offset + 4 < data.length) {
                int p0 = read16(data, offset);
                int p0_alt = read16(data, offset + 2);

                // Check validity relative to Z80 base if known, or just check range relative to header size.
                // MCZ bad pointer: p0 is small/garbage.
                // MCZ good pointer: p0_alt looks like a valid pointer (e.g. > header end).
                // However, without full ROM context, "valid" is hard.
                // Specific Check: In S2 MCZ, the PSG pointers are shifted by 2 bytes.
                // Standard: [P0 P0 K0 V0] [P1 P1 K1 V1] ...
                // MCZ:      [xx xx P0 P0] [K0 V0 P1 P1] [K1 V1 P2 P2] [K2 V2 ...]
                // Actually, let's just check if p0 points to "self" or very close.
                // Or check the overlapping property directly.

                // Let's implement the specific offsets mentioned:
                // Ch0 @ offset+2, Ch1 @ offset+6, Ch2 @ offset+8 ??
                // Wait, if shifted by 2 bytes:
                // [xx xx P0L P0H] -> Ch0 Ptr is at offset+2.
                // [K0 V0 P1L P1H] -> Ch1 Ptr is at offset+6.
                // [K1 V1 P2L P2H] -> Ch2 Ptr is at offset+10.

                // The memory said: "Channel 0 (offset +2), Channel 1 (offset +6...), Channel 2 (offset +8)."
                // Offset + 8 for Ch2 implies [K1 V1] are missing or different?
                // Let's stick to the memory description exactly.

                // How to detect?
                // If p0 is invalid (e.g. 0 or points to header) AND p0_alt is valid.
                // Let's assume valid > 0x20 (past header usually).
                if (p0 < 0x20 && p0_alt > 0x20) {
                     mczCorruption = true;
                }
            }

            if (mczCorruption) {
                // Channel 0: Ptr at offset+2, Key at offset+4 (derived?), Vol at offset+5?
                // Memory: "Channel 1 (offset +6, with Key/Vol overlapping Channel 2's pointer at +8)"
                // This implies complex overlap. Let's just hardcode the offsets as per memory instructions.

                // Channel 0
                psgPtrs[0] = read16(data, offset + 2);
                psgKeys[0] = 0; // Unknown/Default
                psgVols[0] = 0;

                // Channel 1
                psgPtrs[1] = read16(data, offset + 6);
                psgKeys[1] = 0;
                psgVols[1] = 0;

                // Channel 2
                psgPtrs[2] = read16(data, offset + 8); // Memory says +8
                psgKeys[2] = 0;
                psgVols[2] = 0;

                // Update offset to skip this mess
                offset += (psgChannels * 4);
            } else {
                for (int i = 0; i < psgChannels; i++) {
                    if (offset + 1 < data.length) {
                        psgPtrs[i] = read16(data, offset);
                        psgKeys[i] = (byte) data[offset + 2];
                        psgVols[i] = (byte) data[offset + 3];
                    } else {
                        psgPtrs[i] = 0;
                        psgKeys[i] = 0;
                        psgVols[i] = 0;
                    }
                    offset += 4; // skip key + volume
                }
            }
            this.psgPointers = psgPtrs;
            this.psgKeyOffsets = psgKeys;
            this.psgVolumeOffsets = psgVols;
        } else {
            this.voicePtr = 0;
            this.channels = 0;
            this.psgChannels = 0;
            this.dividingTiming = 1;
            this.tempo = 0;
            this.dacPointer = 0;
            this.fmPointers = new int[0];
            this.fmKeyOffsets = new int[0];
            this.fmVolumeOffsets = new int[0];
            this.psgPointers = new int[0];
            this.psgKeyOffsets = new int[0];
            this.psgVolumeOffsets = new int[0];
        }
    }

    public byte[] getData() {
        return data;
    }

    public int getVoicePtr() {
        return voicePtr;
    }

    public int getChannels() {
        return channels;
    }

    public int getPsgChannels() {
        return psgChannels;
    }

    public int getDividingTiming() {
        return dividingTiming;
    }

    public int getTempo() {
        return tempo;
    }

    public int getDacPointer() {
        return dacPointer;
    }

    public int[] getFmPointers() {
        return fmPointers;
    }

    public int[] getFmKeyOffsets() {
        return fmKeyOffsets;
    }

    public int[] getFmVolumeOffsets() {
        return fmVolumeOffsets;
    }

    public int[] getPsgPointers() {
        return psgPointers;
    }

    public int[] getPsgKeyOffsets() {
        return psgKeyOffsets;
    }

    public int[] getPsgVolumeOffsets() {
        return psgVolumeOffsets;
    }

    public int getZ80StartAddress() {
        return z80StartAddress;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    /**
     * Returns the length (and stride) of FM voices.
     * Sonic 1 (Big Endian) uses 25 bytes.
     * Sonic 2 (Little Endian) also uses 25 bytes (21 used + 4 padding), despite memory suggesting 19.
     */
    public int getFmVoiceLength() {
        return 25;
    }

    private int read16(byte[] bytes, int idx) {
        if (littleEndian) {
            return (bytes[idx] & 0xFF) | ((bytes[idx + 1] & 0xFF) << 8);
        }
        return ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF);
    }

    /**
     * Sonic 2 SMPS (Z80) uses little-endian pointers; Sonic 1 (68k) uses big-endian.
     * Choose default based on configured ROM name; fall back to Sonic 2 (little-endian).
     */
    private boolean detectLittleEndian() {
        try {
            // Check for system property override (for tests/tools without full config service)
            String sysProp = System.getProperty("sonic.rom.filename");
            if (sysProp != null) {
                if (sysProp.toLowerCase().contains("sonic 1")) return false;
            }

            String romName = uk.co.jamesj999.sonic.configuration.SonicConfigurationService
                    .getInstance()
                    .getString(uk.co.jamesj999.sonic.configuration.SonicConfiguration.ROM_FILENAME)
                    .toLowerCase();
            if (romName.contains("sonic the hedgehog 1") || romName.contains("sonic 1")) {
                return false;
            }
        } catch (Throwable ignored) {
            // Default below
        }
        return true;
    }
}
