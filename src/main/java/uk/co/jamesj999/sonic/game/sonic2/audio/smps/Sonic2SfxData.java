package uk.co.jamesj999.sonic.game.sonic2.audio.smps;
import static uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsConstants.*;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSfxData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * SMPS Z80 SFX header parser for Sonic 2.
 * Layout (per SMPSPlay PlaySFX):
 *   0x00-0x01: Voice table pointer (SeqBase-relative Z80 pointer)
 *   0x02:     Tick multiplier
 *   0x03:     Track count
 *   0x04+:    Track headers (6 bytes each):
 *             [0]=playback flags, [1]=channel mask, [2]=transpose,
 *             [3]=volume, [4-5]=track pointer (SeqBase-relative)
 */
public class Sonic2SfxData extends AbstractSmpsData implements SmpsSfxData {
    public static class TrackEntry implements SmpsSfxTrack {
        public final int playbackFlags;
        public final int channelMask;
        public final int transpose;
        public final int volume;
        public final int pointer;

        TrackEntry(int playbackFlags, int channelMask, int transpose, int volume, int pointer) {
            this.playbackFlags = playbackFlags;
            this.channelMask = channelMask;
            this.transpose = transpose;
            this.volume = volume;
            this.pointer = pointer;
        }

        @Override
        public int channelMask() {
            return channelMask;
        }

        @Override
        public int pointer() {
            return pointer;
        }

        @Override
        public int transpose() {
            return transpose;
        }

        @Override
        public int volume() {
            return volume;
        }
    }

    private List<TrackEntry> tracks = new ArrayList<>();
    private final int bankOffset; // position of this blob within its Z80 bank (ptr & Z80_BANK_MASK for start)
    private final int headerOffset;
    private int tickMultiplier = 1;

    public Sonic2SfxData(byte[] data, int z80StartAddress, int bankOffset, int headerOffset) {
        super(data, z80StartAddress);
        this.bankOffset = bankOffset;
        this.headerOffset = headerOffset;
    }

    @Override
    public List<TrackEntry> getTrackEntries() {
        if (tracks == null || tracks.isEmpty()) {
            parseHeader();
        }
        return Collections.unmodifiableList(tracks);
    }

    @Override
    public int getTickMultiplier() {
        return tickMultiplier;
    }

    @Override
    protected void parseHeader() {
        if (tracks == null) {
            tracks = new ArrayList<>();
        } else {
            tracks.clear();
        }
        if (data.length < 4) {
            return;
        }

        int base = headerOffset;

        int rawVoicePtr = read16(base);
        this.tickMultiplier = data[base + 2] & 0xFF;
        if (tickMultiplier == 0) {
            tickMultiplier = 1;
        }
        this.dividingTiming = tickMultiplier;
        this.tempo = 0; // SFX tick every frame in driver, tempo handled separately.

        int trackCount = data[base + 3] & 0xFF;
        int pos = base + 4;
        int fmCount = 0;
        int psgCount = 0;
        boolean hasDac = false;
        this.voicePtr = relocatePtr(rawVoicePtr);

        // SFX track header layout:
        // 0: flags, 1: channel id, 2-3: data pointer, 4: key disp, 5: volume
        for (int i = 0; i < trackCount && pos + 5 < data.length; i++, pos += 6) {
            int flags = data[pos] & 0xFF;
            int channelId = data[pos + 1] & 0xFF;
            int ptr = relocatePtr(read16(pos + 2));
            int transpose = (byte) data[pos + 4];
            int volume = (byte) data[pos + 5];

            tracks.add(new TrackEntry(flags, channelId, transpose, volume, ptr));

            if (channelId == 0x16 || channelId == 0x10) {
                hasDac = true;
            } else if ((channelId & 0x80) != 0) {
                psgCount++;
            } else {
                fmCount++;
            }
        }

        this.channels = fmCount + (hasDac ? 1 : 0);
        this.psgChannels = psgCount;
    }

    @Override
    public byte[] getVoice(int voiceId) {
        int ptr = voicePtr;
        if (ptr == 0) return null;

        int offset = relocatePtr(ptr);
        if (offset < 0) return null;

        int stride = 25;
        offset += (voiceId * stride);

        if (offset < 0 || offset + stride > data.length) return null;

        byte[] voice = new byte[stride];
        System.arraycopy(data, offset, voice, 0, stride);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        return Sonic2PsgEnvelopes.getEnvelope(id);
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int relocatePtr(int ptr) {
        if (ptr == 0) return 0;
        // SFX pointers are absolute Z80 addresses within the bank.
        int rel = ptr - Z80_BANK_BASE;
        if (rel >= 0 && rel < data.length) {
            return rel;
        }
        return ptr;
    }

    @Override
    public int getBaseNoteOffset() {
        return 1; // Sonic 2 base note (B)
    }

    @Override
    public int getPsgBaseNoteOffset() {
        return 0; // PSG base note C for SFX (matches DefDrv default)
    }

}
