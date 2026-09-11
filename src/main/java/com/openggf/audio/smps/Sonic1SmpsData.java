package com.openggf.audio.smps;

/**
 * SMPS 68k music data parser for Sonic 1.
 *
 * <p>S1 music header layout (confirmed from s1disasm):
 * <pre>
 *   Offset 0x00: word  - Voice pointer (16-bit BE, relative to song start)
 *   Offset 0x02: byte  - FM channel count (includes DAC as the first "FM" channel)
 *   Offset 0x03: byte  - PSG channel count
 *   Offset 0x04: byte  - Dividing timing (initial tick multiplier)
 *   Offset 0x05: byte  - Tempo value
 *   Offset 0x06: DAC track entry (4 bytes):
 *     +0: word  - Data pointer (relative to song start)
 *     +2: byte  - Pitch/transpose
 *     +3: byte  - Volume
 *   Then FM channel entries (4 bytes each):
 *     +0: word  - Data pointer (relative to song start)
 *     +2: byte  - Transpose
 *     +3: byte  - Volume
 *   Then PSG channel entries (6 bytes each):
 *     +0: word  - Data pointer (relative to song start)
 *     +2: byte  - Transpose
 *     +3: byte  - Volume
 *     +4: byte  - Modulation/voice (unused in S1, always 0)
 *     +5: byte  - PSG volume envelope index
 * </pre>
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Pointers are 16-bit big-endian, relative to song start (offset 0 in data[]).
 *       S2 uses little-endian absolute Z80 addresses.</li>
 *   <li>FM voice operator order per group in ROM is Op4,Op3,Op2,Op1 (InsMode=DEFAULT).
 *       S2 stores Op4,Op2,Op3,Op1 (InsMode=Hardware). The middle two bytes are swapped.
 *       {@link #getVoice(int)} converts S1 format to S2 format on load.</li>
 *   <li>Base note is B (offset 1), same as S2 (DefDrv.txt: FMBaseNote = B).</li>
 * </ul>
 *
 * <p>Voice data format in ROM (25 bytes per FM instrument, S1 InsMode=DEFAULT order):
 * <pre>
 *   Byte  0:    (feedback &lt;&lt; 3) | algorithm
 *   Bytes 1-4:  (DT&lt;&lt;4)|MUL for operators 4, 3, 2, 1
 *   Bytes 5-8:  (RS&lt;&lt;6)|AR for operators 4, 3, 2, 1
 *   Bytes 9-12: AM|D1R for operators 4, 3, 2, 1
 *   Bytes 13-16: D2R for operators 4, 3, 2, 1
 *   Bytes 17-20: (D1L&lt;&lt;4)|RR for operators 4, 3, 2, 1
 *   Bytes 21-24: TL for operators 4, 3, 2, 1
 * </pre>
 * The S1 sequencer profile consumes a normalized Op4,Op2,Op3,Op1 layout, so
 * {@link #getVoice(int)} swaps the middle two bytes of each group on load.
 */
public class Sonic1SmpsData extends AbstractSmpsData {

    private byte[][] psgEnvelopes;

    public Sonic1SmpsData(byte[] data) {
        this(data, 0);
    }

    public Sonic1SmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    /**
     * Sets the PSG envelope data loaded from ROM by the Sonic1SmpsLoader.
     *
     * @param psgEnvelopes array of 9 PSG envelopes (index 0-8)
     */
    public void setPsgEnvelopes(byte[][] psgEnvelopes) {
        this.psgEnvelopes = psgEnvelopes;
    }

    @Override
    protected void parseHeader() {
        if (data.length < 8) {
            return;
        }

        // Voice pointer: 16-bit BE, relative to song start (data[0])
        this.voicePtr = read16(0);

        // Channel counts
        this.channels = data[2] & 0xFF;  // FM channel count (includes DAC)
        this.psgChannels = data[3] & 0xFF;

        // Timing
        this.dividingTiming = data[4] & 0xFF;
        this.tempo = data[5] & 0xFF;

        // DAC + FM channel entries start at offset 0x06.
        // The first entry in the channels count is the DAC track.
        // Each FM/DAC entry is 4 bytes: pointer(2) + transpose(1) + volume(1).
        int fmStart = 0x06;
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

        // The first FM pointer is the DAC pointer
        if (channels > 0) {
            this.dacPointer = this.fmPointers[0];
        }

        // PSG channel entries: 6 bytes each
        // pointer(2) + transpose(1) + volume(1) + modulation(1) + envelope index(1)
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

    @Override
    public byte[] getVoice(int voiceId) {
        int ptr = voicePtr;
        if (ptr == 0) {
            return null;
        }

        // S1 voice pointer is relative to song start (offset 0 in data[]).
        // Since z80StartAddress is 0 for S1, ptr is already a direct index into data[].
        int offset = ptr;
        if (offset < 0 || offset >= data.length) {
            // Fallback: try subtracting z80StartAddress in case it was set
            if (z80StartAddress > 0) {
                int rel = ptr - z80StartAddress;
                if (rel >= 0 && rel < data.length) {
                    offset = rel;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        int stride = 25;
        offset += (voiceId * stride);

        if (offset < 0 || offset + stride > data.length) {
            return null;
        }

        // S1 voice bytes per 4-byte group are in order Op4,Op3,Op2,Op1 (InsMode=DEFAULT).
        // The S1 sequencer profile consumes normalized order: Op4,Op2,Op3,Op1.
        // The difference: bytes at positions [g+1] and [g+2] are swapped.
        // Convert S1 → S2 format by swapping the middle two bytes in each group.
        byte[] voice = new byte[stride];
        System.arraycopy(data, offset, voice, 0, stride);

        // Swap positions [g+1] and [g+2] for each 4-byte operator group
        // Groups start at byte offsets 1, 5, 9, 13, 17, 21
        for (int g = 1; g < 25; g += 4) {
            byte tmp = voice[g + 1];
            voice[g + 1] = voice[g + 2];
            voice[g + 2] = tmp;
        }
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        // S1 uses 1-based VoiceIndex (driver does subq.w #1,d0 before table lookup).
        // VoiceIndex=0 means no envelope. VoiceIndex=1 maps to psgEnvelopes[0], etc.
        int index = id - 1;
        if (psgEnvelopes != null && index >= 0 && index < psgEnvelopes.length) {
            return psgEnvelopes[index];
        }
        return null;
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) {
            return 0;
        }
        // Big Endian (68k byte order)
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    @Override
    public int getBaseNoteOffset() {
        return 1; // Sonic 1 uses base note B (DefDrv.txt: FMBaseNote = B)
    }

    @Override
    public int getPsgBaseNoteOffset() {
        return 0; // PSG base note C (PSGSetFreq subtracts 0x81, table starts at C)
    }
}
