package com.openggf.game.sonic2.audio.smps;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsHeaderDecoder;

import java.util.Map;


public class Sonic2SmpsData extends AbstractSmpsData {
    private Map<Integer, byte[]> psgEnvelopes;

    public Sonic2SmpsData(byte[] data) {
        this(data, 0);
    }

    public Sonic2SmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress, true);
        SmpsHeaderDecoder.parseInto(this, SmpsHeaderDecoder.Format.Z80_LITTLE_ENDIAN);
    }

    public void setPsgEnvelopes(Map<Integer, byte[]> psgEnvelopes) {
        this.psgEnvelopes = psgEnvelopes;
    }

    @Override
    protected void parseHeader() {
        SmpsHeaderDecoder.parseInto(this, SmpsHeaderDecoder.Format.Z80_LITTLE_ENDIAN);
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

        byte[] voice = new byte[stride];
        System.arraycopy(data, offset, voice, 0, stride);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        if (psgEnvelopes != null && psgEnvelopes.containsKey(id)) {
            return psgEnvelopes.get(id);
        }
        return Sonic2PsgEnvelopes.getEnvelope(id);
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

    @Override
    public int getPsgBaseNoteOffset() {
        return 0; // PSG base note C per SMPSPlay Def_68k defaults
    }

}
