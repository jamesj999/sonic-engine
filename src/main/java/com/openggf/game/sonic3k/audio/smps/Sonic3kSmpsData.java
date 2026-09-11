package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.AbstractSmpsData;

import java.util.Map;

import static com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
import static com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants.Z80_GLOBAL_INSTRUMENT_TABLE;

/**
 * SMPS Z80 Type 2 music data parser for Sonic 3 &amp; Knuckles.
 *
 * <p>S3K header layout is identical to Sonic 2 (Z80 Type 2):
 * <pre>
 *   Offset 0x00: word  - Voice pointer (16-bit LE, Z80 address)
 *   Offset 0x02: byte  - FM channel count (includes DAC as first channel)
 *   Offset 0x03: byte  - PSG channel count
 *   Offset 0x04: byte  - Dividing timing
 *   Offset 0x05: byte  - Tempo value
 *   Offset 0x06+: DAC/FM track entries (4 bytes each):
 *     +0: word  - Data pointer (LE, Z80 address)
 *     +2: byte  - Transpose
 *     +3: byte  - Volume
 *   Then PSG channel entries (6 bytes each):
 *     +0: word  - Data pointer (LE, Z80 address)
 *     +2: byte  - Transpose
 *     +3: byte  - Volume
 *     +4: byte  - Modulation envelope
 *     +5: byte  - PSG volume envelope index
 * </pre>
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Base note is C (offset 0) per DefDrv.txt: {@code FMBaseNote = C}</li>
 *   <li>InsMode=DEFAULT: voice operator order is Op4,Op3,Op2,Op1. The raw
 *       bytes are preserved so the sequencer can reproduce zSendFMInstrument's
 *       30,38,34,3C register traversal.</li>
 *   <li>Some songs use a global instrument table rather than per-song voices.</li>
 * </ul>
 */
public class Sonic3kSmpsData extends AbstractSmpsData {
    private static final int VOICE_STRIDE = 25;

    private Map<Integer, byte[]> psgEnvelopes;
    private Map<Integer, byte[]> modEnvelopes;
    private byte[] globalVoiceData;
    private byte[] bankData;       // Full 32KB bank for shared voice table resolution
    private int bankZ80Base;       // Z80 base address of the bank (0x8000)

    public Sonic3kSmpsData(byte[] data) {
        this(data, 0);
    }

    public Sonic3kSmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    public void setPsgEnvelopes(Map<Integer, byte[]> psgEnvelopes) {
        this.psgEnvelopes = psgEnvelopes;
    }

    public void setModEnvelopes(Map<Integer, byte[]> modEnvelopes) {
        this.modEnvelopes = modEnvelopes;
    }

    /**
     * Sets the global instrument table data.
     * Some S3K songs reference voices from this shared table rather than
     * from a per-song voice pointer.
     */
    public void setGlobalVoiceData(byte[] globalVoiceData) {
        this.globalVoiceData = globalVoiceData;
    }

    /**
     * Sets the full bank data for shared voice table resolution.
     * S3K songs within the same bank share a voice table located at the
     * beginning of the bank. Songs that start later in the bank need
     * access to this data to resolve voice pointers that precede their
     * own start address.
     */
    public void setBankData(byte[] bankData, int bankZ80Base) {
        this.bankData = bankData;
        this.bankZ80Base = bankZ80Base;
    }

    public byte[] getBankData() { return bankData; }
    public int getBankZ80Base() { return bankZ80Base; }

    @Override
    protected void parseHeader() {
        if (data.length < 8) {
            return;
        }

        this.voicePtr = read16(0);
        this.channels = data[2] & 0xFF;
        this.psgChannels = data[3] & 0xFF;
        this.dividingTiming = data[4] & 0xFF;
        this.tempo = data[5] & 0xFF;
        this.dacPointer = read16(6);

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

    @Override
    public byte[] getVoice(int voiceId) {
        int ptr = voicePtr;
        if (ptr == 0) {
            // Try global voice table if per-song pointer is absent
            return getGlobalVoice(voiceId);
        }

        // Try 1: local voices within the song's own data blob
        int localBase = resolveLocalVoiceBase(ptr);
        if (localBase >= 0) {
            byte[] local = copyVoice(data, localBase + (voiceId * VOICE_STRIDE));
            if (local != null) {
                return local;
            }
        }

        // Try 2: shared voices within the bank (voice table before song start)
        if (bankData != null && bankZ80Base > 0) {
            int bankRel = ptr - bankZ80Base;
            if (bankRel >= 0 && bankRel + (voiceId * VOICE_STRIDE) + VOICE_STRIDE <= bankData.length) {
                byte[] bankVoice = copyVoice(bankData, bankRel + (voiceId * VOICE_STRIDE));
                if (bankVoice != null) {
                    return bankVoice;
                }
            }
        }

        // Try 3: global instrument table (pointer-based offset)
        byte[] globalFromPointer = getGlobalVoiceFromPointer(ptr, voiceId);
        if (globalFromPointer != null) {
            return globalFromPointer;
        }

        // Final fallback: treat global table as voiceId-based.
        return getGlobalVoice(voiceId);
    }

    private int resolveLocalVoiceBase(int ptr) {
        if (ptr < 0) {
            return -1;
        }

        if (z80StartAddress > 0) {
            int rel = ptr - z80StartAddress;
            if (rel >= 0 && rel < data.length) {
                return rel;
            }
            // Some ripped data stores bank-local offsets with the 0x80xx high byte stripped.
            // Keep that compatibility for small in-song offsets only.
            if (ptr < data.length && ptr < Z80_GENERAL_PTR_LIST) {
                return ptr;
            }
            return -1;
        }

        if (ptr < data.length) {
            return ptr;
        }

        return -1;
    }

    private byte[] copyVoice(byte[] source, int offset) {
        if (source == null || offset < 0 || offset + VOICE_STRIDE > source.length) {
            return null;
        }

        byte[] voice = new byte[VOICE_STRIDE];
        System.arraycopy(source, offset, voice, 0, VOICE_STRIDE);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        if (psgEnvelopes != null && psgEnvelopes.containsKey(id)) {
            return psgEnvelopes.get(id);
        }
        return null;
    }

    @Override
    public byte[] getModEnvelope(int id) {
        if (modEnvelopes != null && modEnvelopes.containsKey(id)) {
            return modEnvelopes.get(id);
        }
        return null;
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8); // Little Endian
    }

    @Override
    public int getBaseNoteOffset() {
        return 0; // S3K uses base note C (DefDrv.txt: FMBaseNote = C)
    }

    @Override
    public int getPsgBaseNoteOffset() {
        return 0; // PSG base note C
    }

    /**
     * Look up a voice from the global instrument table.
     */
    private byte[] getGlobalVoice(int voiceId) {
        if (globalVoiceData == null) {
            return null;
        }

        return copyVoice(globalVoiceData, voiceId * VOICE_STRIDE);
    }

    private byte[] getGlobalVoiceFromPointer(int ptr, int voiceId) {
        if (globalVoiceData == null) {
            return null;
        }
        int globalBase = ptr - Z80_GLOBAL_INSTRUMENT_TABLE;
        if (globalBase < 0 || globalBase >= globalVoiceData.length) {
            return null;
        }
        return copyVoice(globalVoiceData, globalBase + (voiceId * VOICE_STRIDE));
    }
}
