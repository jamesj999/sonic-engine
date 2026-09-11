package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.DriverGlobals;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.LiveSfx;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Overlap;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.RomPointer;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.SavedMusic;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Snapshot;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Decodes the raw shipped {@code $1C00..$1FFF} driver image without trace hydration. */
public final class S3kCompleteRunStateDecoder {
    public static final int STATE_BYTES = 0x400;
    private static final int TRACK_BYTES = 0x30;
    private static final int MUSIC_TRACKS = 0x40;
    private static final int OVERLAP_TRACKS = MUSIC_TRACKS + 9 * TRACK_BYTES;
    private static final int ONE_UP_FADE_TO_PREVIOUS = 0x29;
    private static final int RESTORE_PREVIOUS_IN_PROGRESS = 0xff;
    private static final int[] MUSIC_VOICES = {0x06, 0x00, 0x01, 0x02, 0x04, 0x05, 0x80, 0xa0, 0xc0};
    private static final int[] SFX_VOICES = {0x02, 0x04, 0x05, 0x06, 0x80, 0xa0, 0xc0};

    private S3kCompleteRunStateDecoder() { }

    public static Snapshot decode(byte[] source, S3kCompleteRunAssetCatalog catalog) {
        Objects.requireNonNull(source, "S3K raw driver state");
        Objects.requireNonNull(catalog, "S3K audio asset catalog");
        if (source.length != STATE_BYTES) {
            throw new IllegalArgumentException("S3K raw driver state must be exactly 1024 bytes");
        }
        byte[] raw = source.clone();
        int currentBank = u8(raw, 0x3e);
        int fadeToPrevious = u8(raw, 0x16);
        int savedBank = u8(raw, 0x2d);
        // Z80 Sound Driver.asm:662-688 and 3067-3079 accept both $29 and $FF
        // while the overlap still owns saved music. zFadeInToPrevious clears the
        // flag before restoring tracks (2725-2747), so zero is post-restore SFX.
        boolean savedMode = fadeToPrevious == ONE_UP_FADE_TO_PREVIOUS
                || fadeToPrevious == RESTORE_PREVIOUS_IN_PROGRESS;

        DriverGlobals globals = new DriverGlobals(
                u8(raw, 0x02), u8(raw, 0x04),
                List.of(u8(raw, 0x05), u8(raw, 0x06), u8(raw, 0x07)),
                u8(raw, 0x08), u8(raw, 0x09), u8(raw, 0x0a), u8(raw, 0x0b), u8(raw, 0x0c),
                u8(raw, 0x0d), u8(raw, 0x0e), u8(raw, 0x0f), u8(raw, 0x10), u8(raw, 0x11),
                u8(raw, 0x13), fadeToPrevious, u8(raw, 0x19), u8(raw, 0x24), u8(raw, 0x25),
                u8(raw, 0x26), u8(raw, 0x27), u8(raw, 0x28), u8(raw, 0x29),
                savedMode ? optionalMusicOrDriverData(catalog, savedBank, word(raw, 0x2a)) : null,
                u8(raw, 0x2c), savedBank, u8(raw, 0x2e), u8(raw, 0x2f), u8(raw, 0x30),
                u8(raw, 0x31), u8(raw, 0x32),
                optionalMusic(catalog, currentBank, word(raw, 0x33)),
                optionalDriver(catalog, word(raw, 0x35)),
                optionalMusicOrDriverData(catalog, currentBank, word(raw, 0x37)),
                optionalSfxOrDriverData(catalog, word(raw, 0x39)), u8(raw, 0x3b), currentBank,
                u8(raw, 0x3f) != 0);

        List<Track> music = new ArrayList<>(MUSIC_VOICES.length);
        for (int index = 0; index < MUSIC_VOICES.length; index++) {
            music.add(track(raw, MUSIC_TRACKS + index * TRACK_BYTES, MUSIC_VOICES[index],
                    Layer.MUSIC, currentBank, false, globals.updatingSfx(), catalog));
        }

        Overlap overlap;
        if (savedMode) {
            List<Track> saved = new ArrayList<>(MUSIC_VOICES.length);
            for (int index = 0; index < MUSIC_VOICES.length; index++) {
                // fix_sndbugs=0 copies all nine tracks then clears bit 7 in each
                // (Z80 Sound Driver.asm:1754-1775). Restore later forces all nine live.
                saved.add(track(raw, OVERLAP_TRACKS + index * TRACK_BYTES, MUSIC_VOICES[index],
                        Layer.SAVED_MUSIC, savedBank, true, 0, catalog));
            }
            overlap = new SavedMusic(saved);
        } else {
            List<Track> sfx = new ArrayList<>(SFX_VOICES.length);
            for (int index = 0; index < SFX_VOICES.length; index++) {
                sfx.add(track(raw, OVERLAP_TRACKS + index * TRACK_BYTES, SFX_VOICES[index],
                        Layer.SFX, 0, false, globals.updatingSfx(), catalog));
            }
            overlap = new LiveSfx(sfx);
        }
        return new Snapshot(globals, music, overlap);
    }

    private static Track track(byte[] raw, int base, int expectedVoice, Layer layer, int musicBank,
            boolean forcePopulated, int updatingSfx, S3kCompleteRunAssetCatalog catalog) {
        int playback = u8(raw, base);
        boolean populated = forcePopulated || (playback & 0x80) != 0;
        if (!populated) return Track.inactive();

        int voice = u8(raw, base + 1);
        if (voice != expectedVoice) {
            throw new IllegalArgumentException("S3K active track voice does not match its physical slot");
        }
        int stackPointer = u8(raw, base + 9);
        if (stackPointer < 0x2c || stackPointer > 0x30 || ((0x30 - stackPointer) & 1) != 0) {
            throw new IllegalArgumentException("S3K active track has an invalid stack pointer");
        }
        RomPointer data = pointer(catalog, layer, musicBank, word(raw, base + 3));
        boolean psg = (voice & 0x80) != 0;
        boolean dac = layer != Layer.SFX && voice == 0x06;
        boolean fm = !psg && !dac;
        int fmVolumeEnvelope = u8(raw, base + 0x18);
        RomPointer ssgEg = fm && (fmVolumeEnvelope & 0x80) != 0
                ? ownedDataPointer(catalog, layer, musicBank, word(raw, base + 0x19)) : null;
        RomPointer totalLevel = fm
                ? optionalOwnedDataPointer(catalog, layer, musicBank, word(raw, base + 0x1c)) : null;
        int modulationControl = u8(raw, base + 7);
        RomPointer modulation = !dac && modulationControl == 0x80
                ? pointer(catalog, layer, musicBank, word(raw, base + 0x20)) : null;

        List<Integer> shared = new ArrayList<>(8);
        for (int offset = 0x28; offset < 0x30; offset++) shared.add(u8(raw, base + offset));
        RomPointer voices = layer == Layer.SFX && fm && updatingSfx != 0
                ? ownedDataPointer(catalog, layer, musicBank, word(raw, base + 0x2a)) : null;
        List<RomPointer> returns = new ArrayList<>((0x30 - stackPointer) / 2);
        for (int offset = stackPointer; offset < 0x30; offset += 2) {
            returns.add(pointer(catalog, layer, musicBank, word(raw, base + offset)));
        }

        return new Track(true, data, playback, voice, u8(raw, base + 2), u8(raw, base + 5),
                u8(raw, base + 6), modulationControl, u8(raw, base + 8), stackPointer,
                u8(raw, base + 0x0a), u8(raw, base + 0x0b), u8(raw, base + 0x0c),
                word(raw, base + 0x0d), u8(raw, base + 0x0f), u8(raw, base + 0x10),
                u8(raw, base + 0x11), u8(raw, base + 0x17), fmVolumeEnvelope,
                u8(raw, base + 0x19), ssgEg, u8(raw, base + 0x1a), u8(raw, base + 0x1b),
                totalLevel, u8(raw, base + 0x1e), u8(raw, base + 0x1f), modulation,
                word(raw, base + 0x22), u8(raw, base + 0x24), u8(raw, base + 0x25),
                u8(raw, base + 0x26), u8(raw, base + 0x27), shared, voices, returns);
    }

    private static RomPointer pointer(S3kCompleteRunAssetCatalog catalog,
            Layer layer, int musicBank, int value) {
        return switch (layer) {
            case MUSIC, SAVED_MUSIC -> catalog.musicPointer(musicBank, value);
            case SFX -> catalog.sfxPointer(value);
        };
    }

    private static RomPointer ownedDataPointer(S3kCompleteRunAssetCatalog catalog,
            Layer layer, int musicBank, int value) {
        return switch (layer) {
            case MUSIC, SAVED_MUSIC -> catalog.musicOrDriverDataPointer(musicBank, value);
            case SFX -> catalog.sfxOrDriverDataPointer(value);
        };
    }

    private static RomPointer optionalOwnedDataPointer(S3kCompleteRunAssetCatalog catalog,
            Layer layer, int musicBank, int value) {
        return value == 0 ? null : ownedDataPointer(catalog, layer, musicBank, value);
    }

    private static RomPointer optionalMusic(S3kCompleteRunAssetCatalog catalog, int bank, int value) {
        return value == 0 ? null : catalog.musicPointer(bank, value);
    }

    private static RomPointer optionalMusicOrDriverData(
            S3kCompleteRunAssetCatalog catalog, int bank, int value) {
        return value == 0 ? null : catalog.musicOrDriverDataPointer(bank, value);
    }

    private static RomPointer optionalSfxOrDriverData(S3kCompleteRunAssetCatalog catalog, int value) {
        return value == 0 ? null : catalog.sfxOrDriverDataPointer(value);
    }

    private static RomPointer optionalDriver(S3kCompleteRunAssetCatalog catalog, int value) {
        return value == 0 ? null : catalog.driverPointer(value);
    }

    private static int u8(byte[] raw, int offset) {
        return raw[offset] & 0xff;
    }

    private static int word(byte[] raw, int offset) {
        return u8(raw, offset) | u8(raw, offset + 1) << 8;
    }

    private enum Layer { MUSIC, SFX, SAVED_MUSIC }
}
