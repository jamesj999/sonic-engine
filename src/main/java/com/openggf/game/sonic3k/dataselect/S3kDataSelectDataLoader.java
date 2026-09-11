package com.openggf.game.sonic3k.dataselect;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;
import com.openggf.util.PatternDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.List;
import java.util.Objects;

/**
 * ROM-backed asset loader for the original S3K data select/save screen.
 *
 * <p>This class is intentionally asset-only for now: it loads authored ROM data
 * needed by a later renderer/presentation layer without applying any UI logic.
 */
public final class S3kDataSelectDataLoader implements S3kDataSelectAssetSource {
    private static final int PLANE_WIDTH_TILES = 128;
    private static final int PLANE_HEIGHT_TILES = 32;
    private static final int VISIBLE_WIDTH_TILES = 40;
    private static final int VISIBLE_HEIGHT_TILES = 28;
    private static final int SAVE_TEXT_WORD_BASE = Sonic3kConstants.ARTTILE_SAVE_TEXT - 0x10 + 0xA000;
    private static final int SLOT_ICON_BYTES = 0x8C0;
    private static final int S3_ZONE_REPACK_SOURCE_OFFSET = 0x2BC0;
    private static final int S3_ZONE_REPACK_DEST_OFFSET = 0x2300;
    private static final int S3_ZONE_REPACK_LENGTH = 0x1180;
    private static final int PORTRAIT_TRAILER_COPY_BYTES = 0x540;
    private static final int[] INITIAL_PLANE_COPIES = {
            0x0222, 0x0102, 0x0B, 0x0C,
            0x0000, 0x0118, 0x0D, 0x15,
            0x0000, 0x0132, 0x0D, 0x15,
            0x0000, 0x014C, 0x0D, 0x15,
            0x0000, 0x0166, 0x0D, 0x15,
            0x0000, 0x0180, 0x0D, 0x15,
            0x0000, 0x019A, 0x0D, 0x15,
            0x0104, 0x0E18, 0x0D, 0x0B,
            0x0104, 0x0E32, 0x0D, 0x0B,
            0x0104, 0x0E4C, 0x0D, 0x0B,
            0x0104, 0x0E66, 0x0D, 0x0B,
            0x0104, 0x0E80, 0x0D, 0x0B,
            0x0104, 0x0E9A, 0x0D, 0x0B,
            0x0000, 0x01B4, 0x0D, 0x15,
            0x0000, 0x01CE, 0x0D, 0x15,
            0x0222, 0x01E8, 0x0B, 0x0C,
            0x0104, 0x0EB4, 0x0D, 0x0B,
            0x0104, 0x0ECE, 0x0D, 0x0B
    };

    private final RomByteReader reader;

    private boolean loaded;

    private int[] layoutWords = new int[0];
    private int[] planeALayoutWords = new int[0];
    private int[] newLayoutWords = new int[0];
    private int[][] staticLayouts = new int[0][];
    private int[] menuBackgroundLayoutWords = new int[0];

    private Pattern[] menuBackgroundPatterns = new Pattern[0];
    private Pattern[] miscPatterns = new Pattern[0];
    private Pattern[] extraPatterns = new Pattern[0];
    private Pattern[] textPatterns = new Pattern[0];
    private Pattern[][] slotIconPatterns = new Pattern[0][];
    private Pattern[] skZonePatterns = new Pattern[0];
    private Pattern[] portraitPatterns = new Pattern[0];
    private Pattern[] s3ZonePatterns = new Pattern[0];

    private byte[] menuBackgroundPaletteBytes = new byte[0];
    private byte[] characterPaletteBytes = new byte[0];
    private byte[] emeraldPaletteBytes = new byte[0];
    private byte[][] finishCardPalettes = new byte[0][];
    private byte[][] zoneCardPalettes = new byte[0][];
    private byte[] s3ZoneCard8PaletteBytes = new byte[0];

    private List<SpriteMappingFrame> saveScreenMappings = List.of();
    private S3kSaveScreenLayoutObjects saveScreenLayoutObjects = S3kSaveScreenLayoutObjects.original();

    public S3kDataSelectDataLoader(RomByteReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void loadData() throws IOException {
        if (loaded) {
            return;
        }

        layoutWords = decompressEnigmaWords(
                Sonic3kConstants.MAP_ENI_SAVE_SCREEN_LAYOUT_ADDR,
                Sonic3kConstants.ENIGMA_BASE_SAVE_SCREEN_LAYOUT);
        newLayoutWords = readWordArray(
                Sonic3kConstants.MAP_UNC_SAVE_SCREEN_NEW_ADDR,
                Sonic3kConstants.MAP_UNC_SAVE_SCREEN_NEW_SIZE);
        staticLayouts = new int[][]{
                readWordArray(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_1_ADDR,
                        Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_1_SIZE),
                readWordArray(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_2_ADDR,
                        Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_2_SIZE),
                readWordArray(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_3_ADDR,
                        Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_3_SIZE),
                readWordArray(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_4_ADDR,
                        Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_4_SIZE)
        };
        menuBackgroundLayoutWords = decompressEnigmaWords(
                Sonic3kConstants.MAP_ENI_S3_MENU_BG_ADDR,
                Sonic3kConstants.ENIGMA_BASE_S3_MENU_BG);

        menuBackgroundPatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_S3_MENU_BG_ADDR);
        miscPatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_SAVE_SCREEN_MISC_ADDR);
        extraPatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_SAVE_SCREEN_EXTRA_ADDR);
        textPatterns = loadNemesisPatterns(Sonic3kConstants.ART_NEM_S22P_OPTIONS_ADDR);
        skZonePatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_SAVE_SCREEN_SK_ZONE_ADDR);
        portraitPatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_SAVE_SCREEN_PORTRAIT_ADDR);
        s3ZonePatterns = loadKosinskiPatterns(Sonic3kConstants.ART_KOS_SAVE_SCREEN_S3_ZONE_ADDR);
        slotIconPatterns = buildSlotIconPatterns();

        menuBackgroundPaletteBytes = reader.slice(
                Sonic3kConstants.PAL_SAVE_MENU_BG_ADDR, Sonic3kConstants.PAL_SAVE_MENU_BG_SIZE);
        characterPaletteBytes = reader.slice(
                Sonic3kConstants.PAL_SAVE_CHARS_ADDR, Sonic3kConstants.PAL_SAVE_CHARS_SIZE);
        emeraldPaletteBytes = reader.slice(
                Sonic3kConstants.PAL_SAVE_EMERALDS_ADDR, Sonic3kConstants.PAL_SAVE_EMERALDS_SIZE);
        finishCardPalettes = new byte[][]{
                reader.slice(Sonic3kConstants.PAL_SAVE_FINISH_CARD_1_ADDR, Sonic3kConstants.PAL_SAVE_FINISH_CARD_SIZE),
                reader.slice(Sonic3kConstants.PAL_SAVE_FINISH_CARD_2_ADDR, Sonic3kConstants.PAL_SAVE_FINISH_CARD_SIZE),
                reader.slice(Sonic3kConstants.PAL_SAVE_FINISH_CARD_3_ADDR, Sonic3kConstants.PAL_SAVE_FINISH_CARD_SIZE)
        };
        zoneCardPalettes = new byte[Sonic3kConstants.PAL_SAVE_ZONE_CARD_COUNT][];
        for (int i = 0; i < zoneCardPalettes.length; i++) {
            zoneCardPalettes[i] = reader.slice(
                    Sonic3kConstants.PAL_SAVE_ZONE_CARD_BASE_ADDR + (i * Sonic3kConstants.PAL_SAVE_ZONE_CARD_SIZE),
                    Sonic3kConstants.PAL_SAVE_ZONE_CARD_SIZE);
        }
        s3ZoneCard8PaletteBytes = reader.slice(
                Sonic3kConstants.PAL_SAVE_S3_ZONE_CARD_8_ADDR, Sonic3kConstants.PAL_SAVE_S3_ZONE_CARD_8_SIZE);

        saveScreenMappings = List.copyOf(
                S3kSpriteDataLoader.loadMappingFrames(
                        reader,
                        Sonic3kConstants.MAP_SAVE_SCREEN_GENERAL_ADDR,
                        Sonic3kConstants.MAP_SAVE_SCREEN_GENERAL_FRAME_COUNT));
        saveScreenLayoutObjects = loadSaveScreenLayoutObjects();
        planeALayoutWords = composeInitialPlaneALayout(layoutWords);
        loaded = true;
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }

    public int[] getLayoutWords() {
        return layoutWords;
    }

    @Override
    public int[] getPlaneALayoutWords() {
        return planeALayoutWords;
    }

    public int[] getNewLayoutWords() {
        return newLayoutWords;
    }

    public int[][] getStaticLayouts() {
        return staticLayouts;
    }

    public int[] getMenuBackgroundLayoutWords() {
        return menuBackgroundLayoutWords;
    }

    public Pattern[] getMenuBackgroundPatterns() {
        return menuBackgroundPatterns;
    }

    public Pattern[] getMiscPatterns() {
        return miscPatterns;
    }

    public Pattern[] getExtraPatterns() {
        return extraPatterns;
    }

    @Override
    public Pattern[] getTextPatterns() {
        return textPatterns;
    }

    @Override
    public Pattern[] getSlotIconPatterns(int iconIndex) {
        if (iconIndex < 0 || iconIndex >= slotIconPatterns.length) {
            return new Pattern[0];
        }
        return slotIconPatterns[iconIndex];
    }

    public Pattern[] getSkZonePatterns() {
        return skZonePatterns;
    }

    public Pattern[] getPortraitPatterns() {
        return portraitPatterns;
    }

    public Pattern[] getS3ZonePatterns() {
        return s3ZonePatterns;
    }

    public byte[] getMenuBackgroundPaletteBytes() {
        return menuBackgroundPaletteBytes;
    }

    public byte[] getCharacterPaletteBytes() {
        return characterPaletteBytes;
    }

    public byte[] getEmeraldPaletteBytes() {
        return emeraldPaletteBytes;
    }

    public byte[][] getFinishCardPalettes() {
        return finishCardPalettes;
    }

    public byte[][] getZoneCardPalettes() {
        return zoneCardPalettes;
    }

    public byte[] getS3ZoneCard8PaletteBytes() {
        return s3ZoneCard8PaletteBytes;
    }

    public List<SpriteMappingFrame> getSaveScreenMappings() {
        return saveScreenMappings;
    }

    public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
        return saveScreenLayoutObjects;
    }

    public int getMusicId() {
        return Sonic3kMusic.DATA_SELECT.id;
    }

    private Pattern[] loadKosinskiPatterns(int address) throws IOException {
        return PatternDecompressor.fromBytes(decompressKosinski(address));
    }

    private Pattern[] loadNemesisPatterns(int address) throws IOException {
        byte[] compressed = reader.slice(address, reader.size() - address);
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressed)) {
            return PatternDecompressor.fromBytes(NemesisReader.decompress(Channels.newChannel(input)));
        }
    }

    private byte[] decompressKosinski(int address) throws IOException {
        byte[] compressed = reader.slice(address, reader.size() - address);
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressed)) {
            return KosinskiReader.decompress(Channels.newChannel(input));
        }
    }

    private int[] decompressEnigmaWords(int address, int startingArtTile) throws IOException {
        byte[] compressed = reader.slice(address, reader.size() - address);
        byte[] decompressed;
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressed)) {
            decompressed = EnigmaReader.decompress(Channels.newChannel(input), startingArtTile);
        }
        return wordsFromBytes(decompressed);
    }

    private int[] readWordArray(int address, int sizeBytes) {
        return wordsFromBytes(reader.slice(address, sizeBytes));
    }

    private Pattern[][] buildSlotIconPatterns() throws IOException {
        byte[] s3ZoneBytes = decompressKosinski(Sonic3kConstants.ART_KOS_SAVE_SCREEN_S3_ZONE_ADDR);
        byte[] skZoneBytes = decompressKosinski(Sonic3kConstants.ART_KOS_SAVE_SCREEN_SK_ZONE_ADDR);
        byte[] portraitBytes = decompressKosinski(Sonic3kConstants.ART_KOS_SAVE_SCREEN_PORTRAIT_ADDR);

        int totalSize = S3_ZONE_REPACK_DEST_OFFSET + S3_ZONE_REPACK_LENGTH
                + skZoneBytes.length + portraitBytes.length + PORTRAIT_TRAILER_COPY_BYTES;
        byte[] ram = new byte[totalSize];
        System.arraycopy(s3ZoneBytes, 0, ram, 0, Math.min(s3ZoneBytes.length, ram.length));
        System.arraycopy(ram, S3_ZONE_REPACK_SOURCE_OFFSET, ram, S3_ZONE_REPACK_DEST_OFFSET, S3_ZONE_REPACK_LENGTH);

        int cursor = S3_ZONE_REPACK_DEST_OFFSET + S3_ZONE_REPACK_LENGTH;
        System.arraycopy(skZoneBytes, 0, ram, cursor, skZoneBytes.length);
        cursor += skZoneBytes.length;
        System.arraycopy(portraitBytes, 0, ram, cursor, portraitBytes.length);
        cursor += portraitBytes.length;
        System.arraycopy(ram, cursor - SLOT_ICON_BYTES, ram, cursor, PORTRAIT_TRAILER_COPY_BYTES);

        Pattern[][] result = new Pattern[Sonic3kConstants.PAL_SAVE_ZONE_CARD_COUNT][];
        for (int iconIndex = 0; iconIndex < result.length; iconIndex++) {
            int start = iconIndex * SLOT_ICON_BYTES;
            byte[] iconBytes = java.util.Arrays.copyOfRange(ram, start, start + SLOT_ICON_BYTES);
            result[iconIndex] = PatternDecompressor.fromBytes(iconBytes);
        }
        return result;
    }

    private int[] composeInitialPlaneALayout(int[] sourceWords) {
        int[] planeWords = new int[PLANE_WIDTH_TILES * PLANE_HEIGHT_TILES];
        for (int i = 0; i < INITIAL_PLANE_COPIES.length; i += 4) {
            copyRegion(sourceWords,
                    INITIAL_PLANE_COPIES[i],
                    INITIAL_PLANE_COPIES[i + 1],
                    INITIAL_PLANE_COPIES[i + 2],
                    INITIAL_PLANE_COPIES[i + 3],
                    planeWords);
        }

        writeText(planeWords, 0x0C06, "NO");
        writeText(planeWords, 0x0C0C, "SAVE");
        writeText(planeWords, 0x0CEC, "DELETE");

        return planeWords;
    }

    private void copyRegion(int[] sourceWords, int sourceByteOffset, int destVramOffset, int width, int height,
                            int[] planeWords) {
        int sourceIndex = sourceByteOffset / 2;
        int destIndex = destVramOffset / 2;
        int destRow = destIndex / PLANE_WIDTH_TILES;
        int destCol = destIndex % PLANE_WIDTH_TILES;
        for (int row = 0; row < height; row++) {
            int sourceRowIndex = sourceIndex + row * width;
            int destRowIndex = (destRow + row) * PLANE_WIDTH_TILES + destCol;
            System.arraycopy(sourceWords, sourceRowIndex, planeWords, destRowIndex, width);
        }
    }

    private void writeText(int[] planeWords, int destVramOffset, String text) {
        int index = destVramOffset / 2;
        int row = index / PLANE_WIDTH_TILES;
        int col = index % PLANE_WIDTH_TILES;
        for (int i = 0; i < text.length(); i++) {
            int encoded = S3kSaveTextCodec.encode(text.charAt(i));
            planeWords[row * PLANE_WIDTH_TILES + col + i] = encoded == 0
                    ? 0x8000
                    : SAVE_TEXT_WORD_BASE + encoded;
        }
    }

    private S3kSaveScreenLayoutObjects loadSaveScreenLayoutObjects() {
        int address = Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ADDR;
        S3kSaveScreenLayoutObjects.SceneObject titleText = readSceneObject(address);
        S3kSaveScreenLayoutObjects.SceneObject selector =
                readSceneObject(address + Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE);
        S3kSaveScreenLayoutObjects.SceneObject deleteIcon =
                readSceneObject(address + (Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE * 2));
        S3kSaveScreenLayoutObjects.SceneObject noSave =
                readSceneObject(address + (Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE * 3));
        int slotBaseAddress = address + (Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE * 4);
        List<S3kSaveScreenLayoutObjects.SaveSlotObject> slots = java.util.stream.IntStream
                .range(0, Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_SLOT_COUNT)
                .mapToObj(index -> readSaveSlotObject(
                        slotBaseAddress + (index * Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE)))
                .toList();
        return new S3kSaveScreenLayoutObjects(titleText, selector, deleteIcon, noSave, slots);
    }

    private S3kSaveScreenLayoutObjects.SceneObject readSceneObject(int address) {
        return new S3kSaveScreenLayoutObjects.SceneObject(
                reader.readU16BE(address + 4),
                reader.readU16BE(address + 6),
                reader.readU8(address + 8));
    }

    private S3kSaveScreenLayoutObjects.SaveSlotObject readSaveSlotObject(int address) {
        return new S3kSaveScreenLayoutObjects.SaveSlotObject(
                reader.readU16BE(address + 4),
                reader.readU16BE(address + 6),
                reader.readU8(address + 8),
                reader.readU8(address + 9));
    }

    private int[] wordsFromBytes(byte[] bytes) {
        if ((bytes.length & 1) != 0) {
            throw new IllegalArgumentException("Cannot decode odd-byte word payload: " + bytes.length);
        }
        int[] words = new int[bytes.length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < words.length; i++) {
            words[i] = buffer.getShort() & 0xFFFF;
        }
        return words;
    }
}
