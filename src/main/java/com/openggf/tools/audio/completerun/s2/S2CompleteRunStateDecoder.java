package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.Asset;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.AssetPointer;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.DriverGlobals;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.LiveState;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SavedGlobals;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SavedMusic;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SourceLayer;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SourceSlot;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Decodes the complete shipped {@code $0000..$1FFF} S2 Z80 image at a service boundary. */
public final class S2CompleteRunStateDecoder {
    public static final int STATE_BYTES = 0x2000;
    private static final int GLOBALS = 0x1b80;
    private static final int MUSIC_TRACKS = 0x1b98;
    private static final int SFX_TRACKS = 0x1d3c;
    private static final int SAVED_GLOBALS = 0x1e38;
    private static final int SAVED_TRACKS = 0x1e50;
    private static final int TRACK_BYTES = 0x2a;
    // s2.sounddriver.asm:180-227: zVar=$1B80, live=$1B98/$1D3C,
    // saved zVar/tracks=$1E38/$1E50, ending exactly at the $2000 RAM ceiling.
    private static final HardwareRole[] MUSIC_ROLES = {
        HardwareRole.DAC, HardwareRole.FM1, HardwareRole.FM2, HardwareRole.FM3,
        HardwareRole.FM4, HardwareRole.FM5, HardwareRole.FM6,
        HardwareRole.PSG1, HardwareRole.PSG2, HardwareRole.PSG3
    };
    private static final HardwareRole[] SFX_ROLES = {
        HardwareRole.FM3, HardwareRole.FM4, HardwareRole.FM5,
        HardwareRole.PSG1, HardwareRole.PSG2, HardwareRole.PSG3
    };

    private S2CompleteRunStateDecoder() { }

    public static LiveState decode(byte[] source, S2CompleteRunAssetCatalog catalog) {
        Objects.requireNonNull(source, "S2 raw driver state");
        Objects.requireNonNull(catalog, "S2 audio asset catalog");
        if (source.length != STATE_BYTES) {
            throw new IllegalArgumentException("S2 raw driver state must be exactly 8192 bytes");
        }
        byte[] raw = source.clone();
        int currentSong = u8(raw, 0x1300);
        boolean oneUp = u8(raw, GLOBALS + 0x11) != 0;
        Asset liveMusic = activeMusicAsset(raw, catalog, currentSong, MUSIC_TRACKS, MUSIC_ROLES);
        if (liveMusic != null) catalog.requireMusicBank(currentSong, u8(raw, GLOBALS + 0x16));

        DriverGlobals globals = new DriverGlobals(
                u8(raw, GLOBALS), u8(raw, GLOBALS + 1), u8(raw, GLOBALS + 2),
                u8(raw, GLOBALS + 3), u8(raw, GLOBALS + 4), u8(raw, GLOBALS + 5),
                u8(raw, GLOBALS + 6), u8(raw, GLOBALS + 7), u8(raw, GLOBALS + 8),
                List.of(u8(raw, GLOBALS + 9), u8(raw, GLOBALS + 0x0a), u8(raw, GLOBALS + 0x0b)),
                assetPointer(catalog, liveMusic,
                        liveMusic == null ? 0 : word(raw, GLOBALS + 0x0c), "live voice table"),
                u8(raw, GLOBALS + 0x0e), u8(raw, GLOBALS + 0x0f), u8(raw, GLOBALS + 0x10),
                oneUp, u8(raw, GLOBALS + 0x12), u8(raw, GLOBALS + 0x13),
                u8(raw, GLOBALS + 0x14) != 0, u8(raw, GLOBALS + 0x15) != 0,
                u8(raw, GLOBALS + 0x16), u8(raw, GLOBALS + 0x17) != 0,
                u8(raw, 0x1307) != 0, u8(raw, 0x12fe), u8(raw, 0x12ff), currentSong,
                u8(raw, 0x1301) != 0, u8(raw, 0x1302), u8(raw, 0x1303) != 0,
                u8(raw, 0x1304), u8(raw, 0x1305), u8(raw, 0x1306) != 0);

        List<SourceSlot> live = new ArrayList<>(16);
        for (int index = 0; index < MUSIC_ROLES.length; index++) {
            live.add(new SourceSlot(SourceLayer.MUSIC, MUSIC_ROLES[index],
                    track(raw, MUSIC_TRACKS + index * TRACK_BYTES, SourceLayer.MUSIC,
                            MUSIC_ROLES[index], liveMusic, catalog)));
        }
        for (int index = 0; index < SFX_ROLES.length; index++) {
            int base = SFX_TRACKS + index * TRACK_BYTES;
            Asset asset = (u8(raw, base) & 0x80) == 0 ? null : catalog.sfx(word(raw, base + 3));
            live.add(new SourceSlot(SourceLayer.SFX, SFX_ROLES[index],
                    track(raw, base, SourceLayer.SFX, SFX_ROLES[index], asset, catalog)));
        }

        SavedMusic saved = null;
        if (oneUp) {
            // fixBugs=0 (s2.sounddriver.asm:1667-1724) copies zVar plus all ten
            // music tracks before clearing live priority. The fixed path would
            // clear priority before the copy, so preserve the shipped stale value.
            int savedVoice = word(raw, SAVED_GLOBALS + 0x0c);
            Asset savedAsset = savedVoice == 0
                    ? activeMusicAsset(raw, catalog, -1, SAVED_TRACKS, MUSIC_ROLES)
                    : catalog.savedMusic(raw, u8(raw, SAVED_GLOBALS + 0x16), savedVoice);
            SavedGlobals savedValues = new SavedGlobals(
                    u8(raw, SAVED_GLOBALS), u8(raw, SAVED_GLOBALS + 1), u8(raw, SAVED_GLOBALS + 2),
                    u8(raw, SAVED_GLOBALS + 3), u8(raw, SAVED_GLOBALS + 4), u8(raw, SAVED_GLOBALS + 5),
                    u8(raw, SAVED_GLOBALS + 6), u8(raw, SAVED_GLOBALS + 7), u8(raw, SAVED_GLOBALS + 8),
                    List.of(u8(raw, SAVED_GLOBALS + 9), u8(raw, SAVED_GLOBALS + 0x0a),
                            u8(raw, SAVED_GLOBALS + 0x0b)),
                    assetPointer(catalog, savedAsset, savedVoice, "saved voice table"),
                    u8(raw, SAVED_GLOBALS + 0x0e), u8(raw, SAVED_GLOBALS + 0x0f),
                    u8(raw, SAVED_GLOBALS + 0x10), false, u8(raw, SAVED_GLOBALS + 0x12),
                    u8(raw, SAVED_GLOBALS + 0x13), u8(raw, SAVED_GLOBALS + 0x14) != 0,
                    u8(raw, SAVED_GLOBALS + 0x15) != 0, u8(raw, SAVED_GLOBALS + 0x16),
                    u8(raw, SAVED_GLOBALS + 0x17) != 0);
            List<SourceSlot> savedSlots = new ArrayList<>(10);
            for (int index = 0; index < MUSIC_ROLES.length; index++) {
                savedSlots.add(new SourceSlot(SourceLayer.MUSIC, MUSIC_ROLES[index],
                        track(raw, SAVED_TRACKS + index * TRACK_BYTES, SourceLayer.MUSIC,
                                MUSIC_ROLES[index], savedAsset, catalog)));
            }
            saved = new SavedMusic(savedValues, savedSlots);
        }
        return new LiveState(globals, live, saved);
    }

    private static Asset activeMusicAsset(byte[] raw, S2CompleteRunAssetCatalog catalog,
            int currentSong, int tracksBase, HardwareRole[] roles) {
        int firstPointer = 0;
        for (int index = 0; index < roles.length; index++) {
            int base = tracksBase + index * TRACK_BYTES;
            if ((u8(raw, base) & 0x80) != 0) { firstPointer = word(raw, base + 3); break; }
        }
        if (currentSong >= 0) {
            if (firstPointer == 0) return null;
            return catalog.music(currentSong, firstPointer);
        }
        if (firstPointer == 0) return null;
        return catalog.savedMusic(raw, u8(raw, SAVED_GLOBALS + 0x16), firstPointer);
    }

    private static Track track(byte[] raw, int base, SourceLayer layer, HardwareRole role,
            Asset asset, S2CompleteRunAssetCatalog catalog) {
        int playback = u8(raw, base);
        if ((playback & 0x80) == 0) return inactive();
        if (asset == null) throw new IllegalArgumentException("active S2 track has no asset owner");
        int voice = u8(raw, base + 1);
        requireVoice(role, voice);
        int stack = u8(raw, base + 0x0a);
        if (stack < 0x20 || stack > 0x2a || (stack & 1) != 0) {
            throw new IllegalArgumentException("S2 active track has invalid stack pointer");
        }
        int data = word(raw, base + 3);
        catalog.require(asset, data, "S2 track data pointer");
        boolean dac = role == HardwareRole.DAC;
        boolean psg = role == HardwareRole.PSG1 || role == HardwareRole.PSG2 || role == HardwareRole.PSG3;
        boolean fm = !dac && !psg;
        int modulation = word(raw, base + 0x11);
        if (!dac && (playback & 8) != 0 && modulation != 0)
            catalog.require(asset, modulation, "S2 modulation pointer");
        int voicePointer = word(raw, base + 0x1c);
        if (layer == SourceLayer.SFX && fm && voicePointer != 0)
            catalog.require(asset, voicePointer, "S2 SFX voice pointer");
        int tlPointer = word(raw, base + 0x1e);
        if (fm && tlPointer != 0) catalog.require(asset, tlPointer, "S2 TL pointer");
        List<Integer> loops = new ArrayList<>(10);
        for (int offset = 0x20; offset < 0x2a; offset++) loops.add(u8(raw, base + offset));
        for (int offset = stack; offset < 0x2a; offset += 2)
            catalog.require(asset, word(raw, base + offset), "S2 return pointer");
        return new Track(true, asset.key(), data, playback, voice, u8(raw, base + 2),
                u8(raw, base + 5), u8(raw, base + 6), u8(raw, base + 7), u8(raw, base + 8),
                u8(raw, base + 9), stack, u8(raw, base + 0x0b), u8(raw, base + 0x0c),
                word(raw, base + 0x0d), u8(raw, base + 0x0f), u8(raw, base + 0x10),
                modulation, u8(raw, base + 0x13), u8(raw, base + 0x14), u8(raw, base + 0x15),
                u8(raw, base + 0x16), word(raw, base + 0x17), u8(raw, base + 0x19),
                u8(raw, base + 0x1a), u8(raw, base + 0x1b), voicePointer, tlPointer, loops);
    }

    private static AssetPointer assetPointer(S2CompleteRunAssetCatalog catalog,
            Asset asset, int pointer, String label) {
        if (pointer == 0) return new AssetPointer(null, 0);
        if (asset == null) throw new IllegalArgumentException(label + " has no asset owner");
        catalog.require(asset, pointer, label);
        return new AssetPointer(asset.key(), pointer);
    }

    private static Track inactive() {
        return new Track(false, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private static void requireVoice(HardwareRole role, int value) {
        boolean valid = switch (role) {
            case DAC, FM6 -> value == 6; case FM1 -> value == 0; case FM2 -> value == 1;
            case FM3 -> value == 2; case FM4 -> value == 4; case FM5 -> value == 5;
            case PSG1 -> value == 0x80; case PSG2 -> value == 0xa0;
            case PSG3 -> value == 0xc0 || value == 0xe0;
        };
        if (!valid) throw new IllegalArgumentException("S2 active track voice disagrees with its slot");
    }

    private static int u8(byte[] raw, int offset) { return raw[offset] & 0xff; }
    private static int word(byte[] raw, int offset) { return u8(raw, offset) | u8(raw, offset + 1) << 8; }
}
