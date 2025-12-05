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

            // Auto-detect FM start offset. Standard S2 is 0x08, but some tracks (or variants)
            // might effectively start at 0x0A (possibly due to format quirks or padding).
            // We score both offsets based on how many pointers look valid (point to Z80 RAM range).
            int fmStart = detectFmStart(channels, psgChannels);

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

    private int detectFmStart(int fmChannels, int psgChannels) {
        // Only attempt detection if we have a Z80 start address context, otherwise assume standard 0x08.
        // Also check if data length is sufficient.
        if (z80StartAddress <= 0) {
            return 0x08;
        }

        int score08 = scoreOffset(0x08, fmChannels, psgChannels);
        int score0A = scoreOffset(0x0A, fmChannels, psgChannels);

        // Prefer 0x08 (Standard) if scores are equal or 0x08 is better.
        // Only switch to 0x0A if it is strictly better.
        if (score0A > score08) {
            return 0x0A;
        }
        return 0x08;
    }

    private int scoreOffset(int startOffset, int fmChannels, int psgChannels) {
        int score = 0;
        int offset = startOffset;

        // Check FM pointers
        for (int i = 0; i < fmChannels; i++) {
            if (offset + 1 >= data.length) break;
            int ptr = read16(data, offset);
            if (isValidPointer(ptr)) {
                score++;
            }
            offset += 4;
        }

        // Check PSG pointers
        for (int i = 0; i < psgChannels; i++) {
            if (offset + 1 >= data.length) break;
            int ptr = read16(data, offset);
            if (isValidPointer(ptr)) {
                score++;
            }
            offset += 4;
        }

        return score;
    }

    private boolean isValidPointer(int ptr) {
        // A pointer is valid if:
        // 1. It is 0 (NULL pointer is technically valid as unused, but doesn't confirm structure strongly).
        //    However, for scoring, we might want to ignore 0 to avoid false equivalence?
        //    Let's treat 0 as neutral (don't increment score). We want POSITIVE evidence.
        if (ptr == 0) return false;

        // 2. It points within the Z80 RAM range relative to the start address.
        //    For S2, music is at z80StartAddress. Pointers are usually >= z80StartAddress.
        //    They must not exceed roughly 64KB (usually much less).
        if (ptr >= z80StartAddress && ptr < z80StartAddress + 0x8000) { // arbitrary safe upper bound for checking
             return true;
        }

        return false;
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
