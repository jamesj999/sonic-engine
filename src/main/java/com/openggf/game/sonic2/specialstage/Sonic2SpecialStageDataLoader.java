package com.openggf.game.sonic2.specialstage;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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
    private PlayerDplcPlans playerDplcPlans;

    private Pattern[] backgroundArtPatterns;
    private Pattern[] trackArtPatterns;
    private Pattern[] playerArtPatterns;
    private Pattern[] ringArtPatterns;
    private Pattern[] bombArtPatterns;
    private Pattern[] shadowHorizPatterns;
    private Pattern[] shadowDiagPatterns;
    private Pattern[] shadowVertPatterns;
    private Pattern[] hudArtPatterns;
    private Pattern[] tailsTextArtPatterns;
    private Pattern[] startArtPatterns;
    private Pattern[] messagesArtPatterns;
    private Pattern[] explosionArtPatterns;
    private Pattern[] starsArtPatterns;
    private Pattern[] emeraldArtPatterns;
    private Pattern[] resultsArtPatterns;

    public Sonic2SpecialStageDataLoader(Rom rom) {
        this.rom = rom;
    }

    /**
     * ROM-decoded special-stage player DPLC plans. Tile sources are normalized
     * to {@link Sonic2SpecialStageConstants#PLAYER_DPLC_RAM_BASE}, matching the
     * RAM source domain used by the native special-stage DMA routines.
     */
    public record PlayerDplcPlans(
            List<SpriteDplcFrame> sonic,
            List<SpriteDplcFrame> tails,
            List<SpriteDplcFrame> tailsTails) {
        public PlayerDplcPlans {
            sonic = List.copyOf(sonic);
            tails = List.copyOf(tails);
            tailsTails = List.copyOf(tailsTails);
        }
    }

    /**
     * Decodes and caches Obj09's special-stage DPLC records and their source
     * section pointers directly from the supplied Sonic 2 ROM.
     */
    public PlayerDplcPlans getPlayerDplcPlans() throws IOException {
        if (playerDplcPlans == null) {
            RomByteReader reader = RomByteReader.fromRom(rom);
            playerDplcPlans = new PlayerDplcPlans(
                    decodeCountedDplcFrames(reader, 0,
                            Sonic2SpecialStageConstants.SONIC_PLAYER_DPLC_FRAME_COUNT,
                            Sonic2SpecialStageConstants.SONIC_PLAYER_DPLC_SOURCE_TABLE_OFFSET,
                            4, 12, 16),
                    decodeCountedDplcFrames(reader,
                            Sonic2SpecialStageConstants.SONIC_PLAYER_DPLC_FRAME_COUNT,
                            Sonic2SpecialStageConstants.TAILS_PLAYER_DPLC_FRAME_COUNT,
                            Sonic2SpecialStageConstants.TAILS_PLAYER_DPLC_SOURCE_TABLE_OFFSET,
                            4, 12, 16),
                    decodeCompactDplcFrames(reader,
                            Sonic2SpecialStageConstants.SONIC_PLAYER_DPLC_FRAME_COUNT
                                    + Sonic2SpecialStageConstants.TAILS_PLAYER_DPLC_FRAME_COUNT,
                            Sonic2SpecialStageConstants.TAILS_TAILS_DPLC_FRAME_COUNT,
                            Sonic2SpecialStageConstants.TAILS_TAILS_DPLC_SOURCE_TABLE_OFFSET,
                            7, 14));
            LOGGER.fine("Loaded ROM-decoded special-stage player DPLC plans");
        }
        return playerDplcPlans;
    }

    private static List<SpriteDplcFrame> decodeCountedDplcFrames(
            RomByteReader reader,
            int tableFrameOffset,
            int frameCount,
            int sourceTableOffset,
            int... sectionStartFrames) {
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            int recordAddress = dplcRecordAddress(reader, tableFrameOffset + frame);
            int entryCount = reader.readU16BE(recordAddress);
            frames.add(new SpriteDplcFrame(decodeDplcEntries(reader,
                    recordAddress + 2, entryCount,
                    sourceTileBase(reader, sourceTableOffset,
                            sectionForFrame(frame, sectionStartFrames)))));
        }
        return List.copyOf(frames);
    }

    private static List<SpriteDplcFrame> decodeCompactDplcFrames(
            RomByteReader reader,
            int tableFrameOffset,
            int frameCount,
            int sourceTableOffset,
            int... sectionStartFrames) {
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            int recordAddress = dplcRecordAddress(reader, tableFrameOffset + frame);
            frames.add(new SpriteDplcFrame(decodeDplcEntries(reader,
                    recordAddress, 1,
                    sourceTileBase(reader, sourceTableOffset,
                            sectionForFrame(frame, sectionStartFrames)))));
        }
        return List.copyOf(frames);
    }

    private static int dplcRecordAddress(RomByteReader reader, int frameIndex) {
        return reader.readPointer16(
                Sonic2SpecialStageConstants.PLAYER_DPLC_TABLE_OFFSET,
                frameIndex);
    }

    private static int sourceTileBase(
            RomByteReader reader, int sourceTableOffset, int section) {
        int sourceAddress = reader.readU32BE(sourceTableOffset + section * 4)
                & 0x00FF_FFFF;
        int byteOffset = sourceAddress
                - Sonic2SpecialStageConstants.PLAYER_DPLC_RAM_BASE;
        if (byteOffset < 0 || byteOffset % 0x20 != 0) {
            throw new IllegalArgumentException(String.format(
                    "invalid special-stage DPLC RAM source $%06X", sourceAddress));
        }
        return byteOffset / 0x20;
    }

    private static int sectionForFrame(int frame, int... sectionStartFrames) {
        int section = 0;
        for (int boundary : sectionStartFrames) {
            if (frame < boundary) {
                return section;
            }
            section++;
        }
        return section;
    }

    private static List<TileLoadRequest> decodeDplcEntries(
            RomByteReader reader, int address, int count, int sourceTileBase) {
        List<TileLoadRequest> requests = new ArrayList<>(count);
        for (int entry = 0; entry < count; entry++) {
            int encoded = reader.readU16BE(address + entry * 2);
            requests.add(new TileLoadRequest(
                    sourceTileBase + ((encoded & 0x0FFF) >>> 4),
                    (encoded >>> 12) + 1));
        }
        return List.copyOf(requests);
    }

    /**
     * Loads object perspective data (Kosinski compressed).
     * Returns decompressed data: 56 word offset table + 6-byte entries per depth.
     */
    public byte[] getPerspectiveData() throws IOException {
        if (perspectiveData == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.PERSPECTIVE_DATA_OFFSET, Sonic2SpecialStageConstants.PERSPECTIVE_DATA_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.LEVEL_LAYOUTS_OFFSET, Sonic2SpecialStageConstants.LEVEL_LAYOUTS_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.OBJECT_LOCATIONS_OFFSET, Sonic2SpecialStageConstants.OBJECT_LOCATIONS_SIZE);
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
            trackFrames = new byte[Sonic2SpecialStageConstants.TRACK_FRAME_COUNT][];
            for (int i = 0; i < Sonic2SpecialStageConstants.TRACK_FRAME_COUNT; i++) {
                trackFrames[i] = rom.readBytes(Sonic2SpecialStageConstants.TRACK_FRAME_OFFSETS[i], Sonic2SpecialStageConstants.TRACK_FRAME_SIZES[i]);
            }
            LOGGER.fine("Loaded " + Sonic2SpecialStageConstants.TRACK_FRAME_COUNT + " track frames");
        }
        return trackFrames;
    }

    /**
     * Loads a single track frame by index (0-55).
     */
    public byte[] getTrackFrame(int index) throws IOException {
        if (index < 0 || index >= Sonic2SpecialStageConstants.TRACK_FRAME_COUNT) {
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.BACKGROUND_MAIN_MAPPINGS_OFFSET, Sonic2SpecialStageConstants.BACKGROUND_MAIN_MAPPINGS_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.BACKGROUND_LOWER_MAPPINGS_OFFSET, Sonic2SpecialStageConstants.BACKGROUND_LOWER_MAPPINGS_SIZE);
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
            skydomeScrollTable = rom.readBytes(Sonic2SpecialStageConstants.SKYDOME_SCROLL_TABLE_OFFSET, Sonic2SpecialStageConstants.SKYDOME_SCROLL_TABLE_SIZE);
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
            ringRequirementsTeam = rom.readBytes(Sonic2SpecialStageConstants.RING_REQ_TEAM_OFFSET, Sonic2SpecialStageConstants.RING_REQ_TABLE_SIZE);
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
            ringRequirementsSolo = rom.readBytes(Sonic2SpecialStageConstants.RING_REQ_SOLO_OFFSET, Sonic2SpecialStageConstants.RING_REQ_TABLE_SIZE);
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
            animDurationTable = rom.readBytes(Sonic2SpecialStageConstants.ANIM_DURATION_TABLE_OFFSET, Sonic2SpecialStageConstants.ANIM_DURATION_TABLE_SIZE);
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
        if (stage < 0 || stage >= Sonic2SpecialStageConstants.SPECIAL_STAGE_COUNT) {
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
        if (index < 0 || index >= Sonic2SpecialStageConstants.ANIM_BASE_DURATIONS.length) {
            index = Sonic2SpecialStageConstants.ANIM_BASE_DURATIONS.length - 1;
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
        if (segmentType < 0 || segmentType >= Sonic2SpecialStageConstants.SEGMENT_ANIMATIONS.length) {
            throw new IllegalArgumentException("Segment type out of range: " + segmentType);
        }
        return Sonic2SpecialStageConstants.SEGMENT_ANIMATIONS[segmentType];
    }

    /**
     * Loads background art patterns (Nemesis compressed).
     */
    public Pattern[] getBackgroundArtPatterns() throws IOException {
        if (backgroundArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.BACKGROUND_ART_OFFSET, Sonic2SpecialStageConstants.BACKGROUND_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.TRACK_ART_OFFSET, Sonic2SpecialStageConstants.TRACK_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.PLAYER_ART_OFFSET, Sonic2SpecialStageConstants.PLAYER_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.RING_ART_OFFSET, Sonic2SpecialStageConstants.RING_ART_SIZE + 16);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.BOMB_ART_OFFSET, Sonic2SpecialStageConstants.BOMB_ART_SIZE + 16);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.SHADOW_HORIZ_ART_OFFSET, Sonic2SpecialStageConstants.SHADOW_HORIZ_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.SHADOW_DIAG_ART_OFFSET, Sonic2SpecialStageConstants.SHADOW_DIAG_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.SHADOW_VERT_ART_OFFSET, Sonic2SpecialStageConstants.SHADOW_VERT_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.HUD_ART_OFFSET, Sonic2SpecialStageConstants.HUD_ART_SIZE);
            hudArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded HUD art: " + hudArtPatterns.length + " patterns");
        }
        return hudArtPatterns;
    }

    /** Loads the five dedicated overseas TAILS HUD patterns. */
    public Pattern[] getTailsTextArtPatterns() throws IOException {
        if (tailsTextArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.TAILS_TEXT_ART_OFFSET,
                    Sonic2SpecialStageConstants.TAILS_TEXT_ART_SIZE);
            tailsTextArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded TAILS text art: " + tailsTextArtPatterns.length + " patterns");
        }
        return tailsTextArtPatterns;
    }

    /**
     * Loads START banner art patterns (Nemesis compressed).
     * Contains the "START" text and checkered flag graphics.
     */
    public Pattern[] getStartArtPatterns() throws IOException {
        if (startArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.START_ART_OFFSET, Sonic2SpecialStageConstants.START_ART_SIZE);
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
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.MESSAGES_ART_OFFSET, Sonic2SpecialStageConstants.MESSAGES_ART_SIZE);
            messagesArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Messages art: " + messagesArtPatterns.length + " patterns");
        }
        return messagesArtPatterns;
    }

    /**
     * Loads Explosion art patterns (Nemesis compressed).
     * Used for bomb explosion animation (obj61 routine 6).
     * This is separate art from the bomb sprites themselves.
     */
    public Pattern[] getExplosionArtPatterns() throws IOException {
        if (explosionArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.EXPLOSION_ART_OFFSET, Sonic2SpecialStageConstants.EXPLOSION_ART_SIZE + 16);
            explosionArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Explosion art: " + explosionArtPatterns.length + " patterns");
        }
        return explosionArtPatterns;
    }

    /**
     * Loads Stars art patterns (Nemesis compressed).
     * Used for ring sparkle animation when rings are collected (obj60 routine 3).
     * This is separate art from the ring sprites themselves.
     */
    public Pattern[] getStarsArtPatterns() throws IOException {
        if (starsArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.STARS_ART_OFFSET, Sonic2SpecialStageConstants.STARS_ART_SIZE + 16);
            starsArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Stars art: " + starsArtPatterns.length + " patterns");
        }
        return starsArtPatterns;
    }

    /**
     * Loads Emerald art patterns (Nemesis compressed).
     * Used for the chaos emerald object at the end of special stages.
     * Art has 10 frames for perspective sizes (smallest to largest).
     */
    public Pattern[] getEmeraldArtPatterns() throws IOException {
        if (emeraldArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.EMERALD_ART_OFFSET, Sonic2SpecialStageConstants.EMERALD_ART_SIZE + 16);
            emeraldArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Emerald art: " + emeraldArtPatterns.length + " patterns");
        }
        return emeraldArtPatterns;
    }

    /**
     * Loads Results Screen art patterns (Nemesis compressed).
     * Contains text for "SONIC GOT A CHAOS EMERALD", emerald icons (7 colors),
     * ring bonus text, etc. Used by the Obj6F results screen.
     */
    public Pattern[] getResultsArtPatterns() throws IOException {
        if (resultsArtPatterns == null) {
            byte[] compressed = rom.readBytes(Sonic2SpecialStageConstants.RESULTS_ART_OFFSET, Sonic2SpecialStageConstants.RESULTS_ART_SIZE + 16);
            resultsArtPatterns = decompressNemesisToPatterns(compressed);
            LOGGER.fine("Loaded Results art: " + resultsArtPatterns.length + " patterns");
        }
        return resultsArtPatterns;
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
