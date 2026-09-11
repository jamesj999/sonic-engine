package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SMPS Z80 Type 2 SFX header parser for Sonic 3 &amp; Knuckles.
 *
 * <p>S3K SFX header layout (identical to Sonic 2):
 * <pre>
 *   0x00-0x01: Voice table pointer (16-bit LE, Z80 address)
 *   0x02:      Tick multiplier (dividing timing)
 *   0x03:      Track count
 *   0x04+:     Track headers (6 bytes each):
 *              [0] = playback control flags
 *              [1] = channel ID
 *              [2-3] = data pointer (16-bit LE, Z80 address)
 *              [4] = transpose
 *              [5] = volume
 * </pre>
 *
 * <p>Key differences from Sonic 2 SFX:
 * <ul>
 *   <li>Base note is C (offset 0) per DefDrv.txt</li>
 *   <li>InsMode=DEFAULT: raw voice bytes are retained for the S3K Z80
 *       instrument register table.</li>
 * </ul>
 */
public class Sonic3kSfxData extends AbstractSmpsData implements SmpsSfxData {

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
    private final int headerOffset;
    private int tickMultiplier = 1;
    private Map<Integer, byte[]> psgEnvelopes;
    private Map<Integer, byte[]> modEnvelopes;
    private byte[] globalVoiceData;

    public Sonic3kSfxData(byte[] data, int z80StartAddress, int bankOffset, int headerOffset) {
        super(data, z80StartAddress);
        this.headerOffset = headerOffset;
    }

    public void setPsgEnvelopes(Map<Integer, byte[]> psgEnvelopes) {
        this.psgEnvelopes = psgEnvelopes;
    }

    public void setModEnvelopes(Map<Integer, byte[]> modEnvelopes) {
        this.modEnvelopes = modEnvelopes;
    }

    public void setGlobalVoiceData(byte[] globalVoiceData) {
        this.globalVoiceData = globalVoiceData;
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
        this.voicePtr = relocatePtr(rawVoicePtr);
        this.tickMultiplier = data[base + 2] & 0xFF;
        if (tickMultiplier == 0) {
            tickMultiplier = 1;
        }
        this.dividingTiming = tickMultiplier;
        this.tempo = 0; // SFX tick every frame

        int trackCount = data[base + 3] & 0xFF;
        int pos = base + 4;
        int fmCount = 0;
        int psgCount = 0;
        boolean hasDac = false;

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

    private static final int VOICE_STRIDE = 25;

    @Override
    public byte[] getVoice(int voiceId) {
        int ptr = voicePtr;
        if (ptr == 0) {
            // No per-SFX voice table - use global instrument table
            return getGlobalVoice(voiceId);
        }

        int offset = relocatePtr(ptr);
        if (offset < 0) return getGlobalVoice(voiceId);

        offset += (voiceId * VOICE_STRIDE);

        if (offset < 0 || offset + VOICE_STRIDE > data.length) {
            // Voice ID beyond SFX's own table - fall back to global
            return getGlobalVoice(voiceId);
        }

        return copyVoice(data, offset);
    }

    private byte[] getGlobalVoice(int voiceId) {
        if (globalVoiceData == null) return null;
        return copyVoice(globalVoiceData, voiceId * VOICE_STRIDE);
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

    private int relocatePtr(int ptr) {
        if (ptr == 0) return 0;
        int rel = ptr - Sonic3kSmpsConstants.Z80_BANK_BASE;
        if (rel >= 0 && rel < data.length) {
            return rel;
        }
        return ptr;
    }

    @Override
    public int getBaseNoteOffset() {
        return 0; // S3K uses base note C (DefDrv.txt: FMBaseNote = C)
    }

    @Override
    public int getPsgBaseNoteOffset() {
        return 0; // PSG base note C
    }
}
