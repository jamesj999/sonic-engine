package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.tools.EnigmaReader;
import uk.co.jamesj999.sonic.tools.KosinskiReader;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Loads and caches Sonic 2 Special Stage data from the ROM.
 * All data is loaded lazily on first access.
 *
 * This class is specific to Sonic the Hedgehog 2. Other Sonic games have different
 * special stage implementations with different data formats and layouts.
 */
public class Sonic2SpecialStageDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageDataLoader.class.getName());

    private final Rom rom;

    private byte[] perspectiveData;
    private byte[] levelLayouts;
    private byte[] objectLocations;
    private byte[][] trackFrames;
    private byte[] backgroundMainMappings;
    private byte[] backgroundLowerMappings;
    private byte[] skydomeScrollTable;
    private byte[] ringRequirementsTeam;
    private byte[] ringRequirementsSolo;
    private byte[] animDurationTable;

    private Pattern[] backgroundArtPatterns;
    private Pattern[] trackArtPatterns;
    private Pattern[] playerArtPatterns;
    private Pattern[] ringArtPatterns;
    private Pattern[] bombArtPatterns;
    private Pattern[] shadowHorizPatterns;
    private Pattern[] shadowDiagPatterns;
    private Pattern[] shadowVertPatterns;
    private Pattern[] hudArtPatterns;
    private Pattern[] startArtPatterns;
    private Pattern[] messagesArtPatterns;

    public Sonic2SpecialStageDataLoader(Rom rom) {
        this.rom = rom;
    }

    /**
     * Loads object perspective data (Kosinski compressed).
     * Returns decompressed data: 56 word offset table + 6-byte entries per depth.
     */
    public byte[] getPerspectiveData() throws IOException {
        if (perspectiveData == null) {
            byte[] compressed = rom.readBytes(PERSPECTIVE_DATA_OFFSET, PERSPECTIVE_DATA_SIZE);
            perspectiveData = decompressKosinski(compressed);
            LOGGER.fine("Loaded perspective data: " + perspectiveData.length + " bytes");
        }
        return perspectiveData;
    }

    /**
     * Loads level layouts (Nemesis compressed).
     * Returns decompressed data: per-stage segment byte sequences.
     */
    public byte[] getLevelLayouts() throws IOException {
        if (levelLayouts == null) {
            byte[] compressed = rom.readBytes(LEVEL_LAYOUTS_OFFSET, LEVEL_LAYOUTS_SIZE);
            levelLayouts = decompressNemesis(compressed);
            LOGGER.fine("Loaded level layouts: " + levelLayouts.length + " bytes");
        }
        return levelLayouts;
    }

    /**
     * Loads object location lists (Kosinski compressed).
     * Returns decompressed data: object stream records per stage.
     */
    public byte[] getObjectLocations() throws IOException {
        if (objectLocations == null) {
            byte[] compressed = rom.readBytes(OBJECT_LOCATIONS_OFFSET, OBJECT_LOCATIONS_SIZE);
            objectLocations = decompressKosinski(compressed);
            LOGGER.fine("Loaded object locations: " + objectLocations.length + " bytes");
        }
        return objectLocations;
    }

    /**
     * Loads all 56 track mapping frames (raw binary data).
     * Returns array of 56 byte arrays, one per frame.
     */
    public byte[][] getTrackFrames() throws IOException {
        if (trackFrames == null) {
            trackFrames = new byte[TRACK_FRAME_COUNT][];
            for (int i = 0; i < TRACK_FRAME_COUNT; i++) {
                trackFrames[i] = rom.readBytes(TRACK_FRAME_OFFSETS[i], TRACK_FRAME_SIZES[i]);
            }
            LOGGER.fine("Loaded " + TRACK_FRAME_COUNT + " track frames");
        }
        return trackFrames;
    }

    /**
     * Loads a single track frame by index (0-55).
     */
    public byte[] getTrackFrame(int index) throws IOException {
        if (index < 0 || index >= TRACK_FRAME_COUNT) {
            throw new IllegalArgumentException("Track frame index out of range: " + index);
        }
        return getTrackFrames()[index];
    }

    /**
     * Loads main background mappings (Enigma compressed).
     * Returns decompressed data: big-endian 16-bit pattern name table entries.
     */
    public byte[] getBackgroundMainMappings() throws IOException {
        if (backgroundMainMappings == null) {
            byte[] compressed = rom.readBytes(BACKGROUND_MAIN_MAPPINGS_OFFSET, BACKGROUND_MAIN_MAPPINGS_SIZE);
            backgroundMainMappings = decompressEnigma(compressed, 0);
            LOGGER.fine("Loaded main background mappings: " + backgroundMainMappings.length + " bytes");
        }
        return backgroundMainMappings;
    }

    /**
     * Loads lower background mappings (Enigma compressed).
     * Returns decompressed data: big-endian 16-bit pattern name table entries.
     */
    public byte[] getBackgroundLowerMappings() throws IOException {
        if (backgroundLowerMappings == null) {
            byte[] compressed = rom.readBytes(BACKGROUND_LOWER_MAPPINGS_OFFSET, BACKGROUND_LOWER_MAPPINGS_SIZE);
            backgroundLowerMappings = decompressEnigma(compressed, 0);
            LOGGER.fine("Loaded lower background mappings: " + backgroundLowerMappings.length + " bytes");
        }
        return backgroundLowerMappings;
    }

    /**
     * Loads skydome scroll delta table (raw bytes).
     * Returns 77 bytes: 11 word offsets + 11 rows of 5 bytes each.
     */
    public byte[] getSkydomeScrollTable() throws IOException {
        if (skydomeScrollTable == null) {
            skydomeScrollTable = rom.readBytes(SKYDOME_SCROLL_TABLE_OFFSET, SKYDOME_SCROLL_TABLE_SIZE);
            LOGGER.fine("Loaded skydome scroll table: " + skydomeScrollTable.length + " bytes");
        }
        return skydomeScrollTable;
    }

    /**
     * Loads ring requirement table for team mode (raw bytes).
     * Returns 28 bytes: 7 stages x 4 quarters.
     */
    public byte[] getRingRequirementsTeam() throws IOException {
        if (ringRequirementsTeam == null) {
            ringRequirementsTeam = rom.readBytes(RING_REQ_TEAM_OFFSET, RING_REQ_TABLE_SIZE);
            LOGGER.fine("Loaded team ring requirements: " + ringRequirementsTeam.length + " bytes");
        }
        return ringRequirementsTeam;
    }

    /**
     * Loads ring requirement table for solo mode (raw bytes).
     * Returns 28 bytes: 7 stages x 4 quarters.
     */
    public byte[] getRingRequirementsSolo() throws IOException {
        if (ringRequirementsSolo == null) {
            ringRequirementsSolo = rom.readBytes(RING_REQ_SOLO_OFFSET, RING_REQ_TABLE_SIZE);
            LOGGER.fine("Loaded solo ring requirements: " + ringRequirementsSolo.length + " bytes");
        }
        return ringRequirementsSolo;
    }

    /**
     * Loads animation duration table (raw bytes).
     * Returns 8 bytes indexed by speed factor >> 1.
     */
    public byte[] getAnimDurationTable() throws IOException {
        if (animDurationTable == null) {
            animDurationTable = rom.readBytes(ANIM_DURATION_TABLE_OFFSET, ANIM_DURATION_TABLE_SIZE);
            LOGGER.fine("Loaded anim duration table: " + animDurationTable.length + " bytes");
        }
        return animDurationTable;
    }

    /**
     * Gets ring requirement for a specific stage and quarter.
     * @param stage Stage index (0-6)
     * @param quarter Quarter index (0-2, quarter 3 is unused)
     * @param teamMode True for team mode, false for solo mode
     * @return Ring count required
     */
    public int getRingRequirement(int stage, int quarter, boolean teamMode) throws IOException {
        if (stage < 0 || stage >= SPECIAL_STAGE_COUNT) {
            throw new IllegalArgumentException("Stage index out of range: " + stage);
        }
        if (quarter < 0 || quarter > 3) {
            throw new IllegalArgumentException("Quarter index out of range: " + quarter);
        }
        byte[] table = teamMode ? getRingRequirementsTeam() : getRingRequirementsSolo();
        return table[stage * 4 + quarter] & 0xFF;
    }

    /**
     * Gets animation duration for a given speed factor.
     * @param speedFactor The current speed factor value
     * @return Duration in frames
     */
    public int getAnimDuration(int speedFactor) throws IOException {
        int index = (speedFactor & 0xFFFF) >> 1;
        if (index < 0 || index >= ANIM_BASE_DURATIONS.length) {
            index = ANIM_BASE_DURATIONS.length - 1;
        }
        return getAnimDurationTable()[index] & 0xFF;
    }

    /**
     * Parses a segment byte from the level layout.
     * @param segmentByte The raw segment byte
     * @return Array of [segmentType, flipFlag] where flipFlag is 0 or 1
     */
    public static int[] parseSegmentByte(int segmentByte) {
        int flipFlag = (segmentByte >> 7) & 1;
        int segmentType = segmentByte & 0x7F;
        return new int[] { segmentType, flipFlag };
    }

    /**
     * Gets the animation frame sequence for a segment type.
     * @param segmentType Segment type (0-4)
     * @return Array of track frame indices
     */
    public static int[] getSegmentAnimation(int segmentType) {
        if (segmentType < 0 || segmentType >= SEGMENT_ANIMATIONS.length) {
            throw new IllegalArgumentException("Segment type out of range: " + segmentType);
        }
        return SEGMENT_ANIMATIONS[segmentType];
    }

    /**
     * Loads background art patterns (Nemesis compressed).
     */
    public Pattern[] getBackgroundArtPatterns() throws IOException {
        if (backgroundArtPatterns == null) {
            byte[] compressed = rom.readBytes(BACKGROUND_ART_OFFSET, BACKGROUND_ART_SIZE);
            backgroundArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded background art: " + backgroundArtPatterns.length + " patterns");
        }
        return backgroundArtPatterns;
    }

    /**
     * Loads track art patterns (Kosinski compressed).
     * The decompressed data starts with a word containing the tile count,
     * followed by the tile data itself.
     *
     * IMPORTANT: The track art uses a special compressed format where only ONE line
     * (4 bytes = 8 pixels) is stored per tile. The other 7 lines are identical,
     * so each line must be duplicated 8 times to create a full 8x8 tile.
     * See s2.asm comment at ArtKos_Special (line 90412-90414).
     */
    public Pattern[] getTrackArtPatterns() throws IOException {
        if (trackArtPatterns == null) {
            byte[] compressed = rom.readBytes(TRACK_ART_OFFSET, TRACK_ART_SIZE);
            byte[] decompressed = decompressKosinski(compressed);

            int tileCount = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
            trackArtPatterns = new Pattern[tileCount];
            int srcOffset = 2;
            int actualLoaded = 0;

            for (int i = 0; i < tileCount && srcOffset + 4 <= decompressed.length; i++) {
                Pattern pattern = new Pattern();
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 8; col += 2) {
                        byte packedByte = decompressed[srcOffset + (col / 2)];
                        pattern.setPixel(col, row, (byte) ((packedByte >> 4) & 0x0F));
                        pattern.setPixel(col + 1, row, (byte) (packedByte & 0x0F));
                    }
                }
                trackArtPatterns[i] = pattern;
                srcOffset += 4;
                actualLoaded++;
            }

            LOGGER.fine("Loaded track art: " + actualLoaded + " patterns (1-line-per-tile format)");
            if (actualLoaded < tileCount) {
                LOGGER.warning("Only loaded " + actualLoaded + " of " + tileCount + " tiles - data may be truncated!");
            }
        }
        return trackArtPatterns;
    }

    /**
     * Loads player (Sonic/Tails) art patterns (Nemesis compressed).
     */
    public Pattern[] getPlayerArtPatterns() throws IOException {
        if (playerArtPatterns == null) {
            byte[] compressed = rom.readBytes(PLAYER_ART_OFFSET, PLAYER_ART_SIZE);
            playerArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded player art: " + playerArtPatterns.length + " patterns");
        }
        return playerArtPatterns;
    }

    /**
     * Loads ring art patterns (Nemesis compressed).
     * Note: We read extra bytes as padding because Nemesis bitstream may read
     * slightly beyond the exact compressed size during decompression.
     */
    public Pattern[] getRingArtPatterns() throws IOException {
        if (ringArtPatterns == null) {
            // Add small buffer (16 bytes) for decompression edge cases
            byte[] compressed = rom.readBytes(RING_ART_OFFSET, RING_ART_SIZE + 16);
            ringArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded ring art: " + ringArtPatterns.length + " patterns");
        }
        return ringArtPatterns;
    }

    /**
     * Loads bomb art patterns (Nemesis compressed).
     * Note: We read extra bytes as padding because Nemesis bitstream may read
     * slightly beyond the exact compressed size during decompression.
     */
    public Pattern[] getBombArtPatterns() throws IOException {
        if (bombArtPatterns == null) {
            // Add small buffer (16 bytes) for decompression edge cases
            byte[] compressed = rom.readBytes(BOMB_ART_OFFSET, BOMB_ART_SIZE + 16);
            bombArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded bomb art: " + bombArtPatterns.length + " patterns");
        }
        return bombArtPatterns;
    }

    /**
     * Loads horizontal shadow art patterns (Nemesis compressed).
     */
    public Pattern[] getShadowHorizPatterns() throws IOException {
        if (shadowHorizPatterns == null) {
            byte[] compressed = rom.readBytes(SHADOW_HORIZ_ART_OFFSET, SHADOW_HORIZ_ART_SIZE);
            shadowHorizPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded horizontal shadow art: " + shadowHorizPatterns.length + " patterns");
        }
        return shadowHorizPatterns;
    }

    /**
     * Loads diagonal shadow art patterns (Nemesis compressed).
     */
    public Pattern[] getShadowDiagPatterns() throws IOException {
        if (shadowDiagPatterns == null) {
            byte[] compressed = rom.readBytes(SHADOW_DIAG_ART_OFFSET, SHADOW_DIAG_ART_SIZE);
            shadowDiagPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded diagonal shadow art: " + shadowDiagPatterns.length + " patterns");
        }
        return shadowDiagPatterns;
    }

    /**
     * Loads vertical shadow art patterns (Nemesis compressed).
     */
    public Pattern[] getShadowVertPatterns() throws IOException {
        if (shadowVertPatterns == null) {
            byte[] compressed = rom.readBytes(SHADOW_VERT_ART_OFFSET, SHADOW_VERT_ART_SIZE);
            shadowVertPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded vertical shadow art: " + shadowVertPatterns.length + " patterns");
        }
        return shadowVertPatterns;
    }

    /**
     * Loads HUD art patterns (Nemesis compressed).
     * Contains numbers 0-9, text characters for ring counts, etc.
     */
    public Pattern[] getHudArtPatterns() throws IOException {
        if (hudArtPatterns == null) {
            byte[] compressed = rom.readBytes(HUD_ART_OFFSET, HUD_ART_SIZE);
            hudArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded HUD art: " + hudArtPatterns.length + " patterns");
        }
        return hudArtPatterns;
    }

    /**
     * Loads START banner art patterns (Nemesis compressed).
     * Contains the "START" text and checkered flag graphics.
     */
    public Pattern[] getStartArtPatterns() throws IOException {
        if (startArtPatterns == null) {
            byte[] compressed = rom.readBytes(START_ART_OFFSET, START_ART_SIZE);
            startArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded START banner art: " + startArtPatterns.length + " patterns");
        }
        return startArtPatterns;
    }

    /**
     * Loads Messages art patterns (Nemesis compressed).
     * Contains letters for "GET", "RINGS!", numbers 0-9, and other message text.
     * Used by Obj5A for special stage messages.
     */
    public Pattern[] getMessagesArtPatterns() throws IOException {
        if (messagesArtPatterns == null) {
            byte[] compressed = rom.readBytes(MESSAGES_ART_OFFSET, MESSAGES_ART_SIZE);
            messagesArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Messages art: " + messagesArtPatterns.length + " patterns");
        }
        return messagesArtPatterns;
    }

    private byte[] decompressKosinski(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return KosinskiReader.decompress(channel);
        }
    }

    private byte[] decompressNemesis(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return NemesisReader.decompress(channel);
        }
    }

    private byte[] decompressEnigma(byte[] compressed, int startingArtTile) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return EnigmaReader.decompress(channel, startingArtTile);
        }
    }

    private Pattern[] decompressNemesisToPatterns(byte[] compressed) throws IOException {
        byte[] decompressed = decompressNemesis(compressed);
        return bytesToPatterns(decompressed);
    }

    private Pattern[] decompressKosinskiToPatterns(byte[] compressed) throws IOException {
        byte[] decompressed = decompressKosinski(compressed);
        return bytesToPatterns(decompressed);
    }

    private Pattern[] bytesToPatterns(byte[] data) {
        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(data, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }
}

