package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.Asset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** REV01 ROM-derived occupied address catalog for the shipped S2 sound driver. */
public final class S2CompleteRunAssetCatalog {
    public static final String ROM_SHA1 = "8bca5dcef1af3e00098666fd892dc1c2a76333f9";
    private static final long ROM_BYTES = 1024L * 1024L;
    private static final int MUSIC_BUFFER = 0x1380;
    private static final int MUSIC_BUFFER_END = 0x1b80;
    private static final int BANK1 = 0xf0000;
    private static final int BANK2 = 0xf8000;
    private static final int SFX_TABLE = 0xfee91;
    private static final int FINAL_SFX_END = 0xfffec;
    // s2.asm:91488-91652 installs one song in bank $1E and thirty in bank $1F;
    // the table order below is the exact MusicPoint2 pointer order, not ID order.
    private static final int[] TABLE2_IDS = {
        0x88, 0x82, 0x85, 0x89, 0x8b, 0x83, 0x87, 0x8a, 0x92, 0x91,
        0x95, 0x94, 0x8e, 0x93, 0x8d, 0x84, 0x8f, 0x8c, 0x81, 0x96,
        0x86, 0x98, 0x99, 0x9a, 0x9b, 0x97, 0x9d, 0x90, 0x9f, 0x9e
    };
    // Exact sound/music/list of compressed songs.txt membership.
    private static final Set<Integer> COMPRESSED = Set.of(
            0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a,
            0x8b, 0x8c, 0x8d, 0x8e, 0x8f, 0x90, 0x91, 0x92, 0x93, 0x94,
            0x95, 0x96, 0x97, 0x99, 0x9a, 0x9c, 0x9f);

    private final Map<String, Asset> assets;
    private final Map<Integer, Song> songs;
    private final List<Asset> sfx;

    private S2CompleteRunAssetCatalog(byte[] rom) {
        LinkedHashMap<String, Asset> values = new LinkedHashMap<>();
        LinkedHashMap<Integer, SongSeed> seeds = new LinkedHashMap<>();
        seeds.put(0x9c, songSeed(rom, 0x9c, BANK1, BANK1));
        for (int index = 0; index < TABLE2_IDS.length; index++) {
            seeds.put(TABLE2_IDS[index], songSeed(rom, TABLE2_IDS[index], BANK2, BANK2 + index * 2));
        }

        List<Integer> occupiedStarts = new ArrayList<>();
        seeds.values().forEach(seed -> occupiedStarts.add(seed.physicalStart));
        occupiedStarts.add(SFX_TABLE); // s2.asm:91657: SoundIndex ends the final music asset.
        for (int index = 0; index <= 0xf0 - 0xa0; index++) {
            occupiedStarts.add(physical(BANK2, word(rom, SFX_TABLE + index * 2)));
        }
        occupiedStarts.sort(Comparator.naturalOrder());

        LinkedHashMap<Integer, Song> songValues = new LinkedHashMap<>();
        for (int id = 0x81; id <= 0x9f; id++) {
            SongSeed seed = seeds.get(id);
            boolean compressed = COMPRESSED.contains(id);
            byte[] decoded = compressed ? saxman(rom, seed.physicalStart) : null;
            int addressBase = compressed ? MUSIC_BUFFER : z80(seed.bankBase, seed.physicalStart);
            int physicalEnd = nextStart(occupiedStarts, seed.physicalStart, rom.length);
            int addressEnd = compressed ? MUSIC_BUFFER + decoded.length
                    : addressBase + physicalEnd - seed.physicalStart;
            if (addressEnd > 0x10000 || addressEnd <= addressBase
                    || compressed && addressEnd > MUSIC_BUFFER_END) {
                throw new IllegalArgumentException("invalid shipped S2 song extent");
            }
            String key = String.format("music.%02X", id);
            Asset asset = new Asset(key, addressBase, addressEnd);
            values.put(key, asset);
            songValues.put(id, new Song(id, seed.bankBase, asset, decoded));
        }

        List<Asset> sfxValues = new ArrayList<>();
        for (int id = 0xa0; id <= 0xf0; id++) {
            int start = physical(BANK2, word(rom, SFX_TABLE + (id - 0xa0) * 2));
            // Sound70 (Oil Slide) is a 10-byte SMPS header plus a 14-byte PSG
            // stream and ends at REV01 $FFFEC. $FFFEC..$FFFFF is bank padding,
            // not part of the asset (s2.asm:91836 through finishBank).
            int end = id == 0xf0 ? FINAL_SFX_END : nextStart(occupiedStarts, start, rom.length);
            String key = String.format("sfx.%02X", id);
            Asset asset = new Asset(key, z80(BANK2, start), z80(BANK2, end));
            values.put(key, asset);
            sfxValues.add(asset);
        }
        assets = Map.copyOf(values);
        songs = Map.copyOf(songValues);
        sfx = List.copyOf(sfxValues);
    }

    public static S2CompleteRunAssetCatalog load(Path romPath) throws IOException {
        Objects.requireNonNull(romPath, "S2 REV01 ROM path");
        if (Files.size(romPath) != ROM_BYTES || !ROM_SHA1.equals(sha1(romPath))) {
            throw new IllegalArgumentException("S2 audio catalog requires the pinned World REV01 ROM");
        }
        return new S2CompleteRunAssetCatalog(Files.readAllBytes(romPath));
    }

    public Map<String, Asset> assets() { return assets; }

    Asset music(int songId, int pointer) {
        Song song = songs.get(songId);
        if (song == null || !contains(song.asset, pointer)) {
            throw new IllegalArgumentException("S2 music pointer has no exact current-song owner");
        }
        return song.asset;
    }

    void requireMusicBank(int songId, int bankByte) {
        Song song = songs.get(songId);
        if (song == null || song.bankByte() != bankByte) {
            throw new IllegalArgumentException("S2 current song disagrees with its shipped bank");
        }
    }

    Asset savedMusic(byte[] raw, int bankByte, int pointer) {
        List<Song> matches = new ArrayList<>();
        for (Song song : songs.values()) {
            if (song.bankByte() != bankByte || !contains(song.asset, pointer)) continue;
            if (song.decoded == null || matchesBuffer(raw, song.decoded)) matches.add(song);
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException("S2 saved music pointer is unknown or ambiguous");
        }
        return matches.getFirst().asset;
    }

    Asset sfx(int pointer) {
        Asset match = null;
        for (Asset asset : sfx) if (contains(asset, pointer)) {
            if (match != null) throw new IllegalArgumentException("ambiguous S2 SFX pointer");
            match = asset;
        }
        if (match == null) throw new IllegalArgumentException("S2 SFX pointer is outside occupied assets");
        return match;
    }

    void require(Asset asset, int pointer, String label) {
        if (!contains(asset, pointer)) {
            throw new IllegalArgumentException(label + " is outside its exact S2 asset");
        }
    }

    private static boolean matchesBuffer(byte[] raw, byte[] decoded) {
        for (int index = 0; index < decoded.length; index++) {
            if (raw[MUSIC_BUFFER + index] != decoded[index]) return false;
        }
        return true;
    }

    private static boolean contains(Asset asset, int pointer) {
        return pointer >= asset.addressBase() && pointer < asset.addressEndExclusive();
    }

    private static SongSeed songSeed(byte[] rom, int id, int bank, int pointerOffset) {
        return new SongSeed(id, bank, physical(bank, word(rom, pointerOffset)));
    }

    private static int physical(int bank, int z80Pointer) {
        if (z80Pointer < 0x8000) throw new IllegalArgumentException("S2 ROM pointer escaped its Z80 window");
        return bank + z80Pointer - 0x8000;
    }

    private static int z80(int bank, int physical) { return 0x8000 + physical - bank; }

    private static int nextStart(List<Integer> starts, int start, int fallback) {
        return starts.stream().filter(value -> value > start).findFirst().orElse(fallback);
    }

    private static byte[] saxman(byte[] rom, int start) {
        int compressedBytes = word(rom, start);
        int cursor = start + 2;
        int end = cursor + compressedBytes;
        if (end > rom.length) throw new IllegalArgumentException("truncated S2 Saxman song");
        byte[] output = new byte[MUSIC_BUFFER_END - MUSIC_BUFFER];
        int size = 0;
        int descriptor = 0;
        int bits = 0;
        while (cursor < end) {
            if (bits == 0) { descriptor = rom[cursor++] & 0xff; bits = 8; }
            boolean literal = (descriptor & 1) != 0;
            descriptor >>>= 1;
            bits--;
            if (literal) {
                if (cursor >= end || size >= output.length) throw new IllegalArgumentException("invalid S2 Saxman literal");
                output[size++] = rom[cursor++];
            } else {
                if (cursor + 1 >= end) throw new IllegalArgumentException("truncated S2 Saxman match");
                int low = rom[cursor++] & 0xff;
                int high = rom[cursor++] & 0xff;
                int relative = (((high & 0xf0) << 4) | low) + 0x12 & 0xfff;
                int length = (high & 0x0f) + 3;
                int source = (size & 0xf000) | relative;
                if (source > size) source -= 0x1000;
                for (int index = 0; index < length; index++) {
                    if (size >= output.length) throw new IllegalArgumentException("S2 Saxman song exceeds buffer");
                    output[size++] = source < 0 ? 0 : output[source++];
                }
            }
        }
        return Arrays.copyOf(output, size);
    }

    private static int word(byte[] bytes, int offset) {
        return bytes[offset] & 0xff | (bytes[offset + 1] & 0xff) << 8;
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0; ) if (count > 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM is missing SHA-1", error);
        }
    }

    private record SongSeed(int id, int bankBase, int physicalStart) { }
    private record Song(int id, int bankBase, Asset asset, byte[] decoded) {
        private int bankByte() { return bankBase == BANK1 ? 0 : 0x80; }
    }
}
