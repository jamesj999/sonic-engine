package com.openggf.audio.smps;

/** Internal music-header format boundary, independent of sequencer and voice-bank lookup. */
public final class SmpsHeaderDecoder {
    public enum Format {
        RELATIVE_BIG_ENDIAN,
        Z80_LITTLE_ENDIAN
    }

    private SmpsHeaderDecoder() {
    }

    /** Decodes into a private result before installing any fields on the receiver. */
    public static void parseInto(AbstractSmpsData target, Format format) {
        Header header = decode(target.data, format);
        if (header == null) {
            return;
        }
        target.voicePtr = header.voicePointer;
        target.channels = header.fmPointers.length;
        target.psgChannels = header.psgPointers.length;
        target.dividingTiming = header.dividingTiming;
        target.tempo = header.tempo;
        target.dacPointer = header.dacPointer;
        target.fmPointers = header.fmPointers;
        target.fmKeyOffsets = header.fmKeys;
        target.fmVolumeOffsets = header.fmVolumes;
        target.psgPointers = header.psgPointers;
        target.psgKeyOffsets = header.psgKeys;
        target.psgVolumeOffsets = header.psgVolumes;
        target.psgModEnvs = header.psgModulations;
        target.psgInstruments = header.psgInstruments;
    }

    private static Header decode(byte[] data, Format format) {
        if (data.length < 8) {
            return null;
        }
        int fmCount = data[2] & 0xFF;
        int psgCount = data[3] & 0xFF;
        int[] fmPointers = new int[fmCount];
        int[] fmKeys = new int[fmCount];
        int[] fmVolumes = new int[fmCount];
        int offset = 6;
        // Preserve the existing malformed-input contracts: S1 requires all four
        // bytes; the Z80 parsers admit two then throw if key/volume is truncated.
        int requiredFmBytes = format == Format.RELATIVE_BIG_ENDIAN ? 4 : 2;
        for (int i = 0; i < fmCount; i++, offset += 4) {
            if (offset + requiredFmBytes <= data.length) {
                fmPointers[i] = read16(data, offset, format);
                fmKeys[i] = data[offset + 2];
                fmVolumes[i] = data[offset + 3];
            }
        }
        int[] psgPointers = new int[psgCount];
        int[] psgKeys = new int[psgCount];
        int[] psgVolumes = new int[psgCount];
        int[] psgModulations = new int[psgCount];
        int[] psgInstruments = new int[psgCount];
        for (int i = 0; i < psgCount; i++, offset += 6) {
            if (offset + 6 <= data.length) {
                psgPointers[i] = read16(data, offset, format);
                psgKeys[i] = data[offset + 2];
                psgVolumes[i] = data[offset + 3];
                psgModulations[i] = data[offset + 4] & 0xFF;
                psgInstruments[i] = data[offset + 5] & 0xFF;
            }
        }
        int dacPointer = format == Format.RELATIVE_BIG_ENDIAN
                ? (fmCount == 0 ? 0 : fmPointers[0]) : read16(data, 6, format);
        return new Header(read16(data, 0, format), data[4] & 0xFF, data[5] & 0xFF,
                dacPointer, fmPointers, fmKeys, fmVolumes, psgPointers, psgKeys,
                psgVolumes, psgModulations, psgInstruments);
    }

    private static int read16(byte[] data, int offset, Format format) {
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return format == Format.RELATIVE_BIG_ENDIAN ? (first << 8) | second : first | (second << 8);
    }

    private record Header(int voicePointer, int dividingTiming, int tempo, int dacPointer,
                          int[] fmPointers, int[] fmKeys, int[] fmVolumes,
                          int[] psgPointers, int[] psgKeys, int[] psgVolumes,
                          int[] psgModulations, int[] psgInstruments) {
    }
}
