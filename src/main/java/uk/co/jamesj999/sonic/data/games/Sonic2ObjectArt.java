package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.objects.ObjectArtData;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationEndAction;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationScript;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads common object art (monitors, spikes, springs) for Sonic 2 (REV01).
 */
public class Sonic2ObjectArt {
    private final Rom rom;
    private final RomByteReader reader;
    private ObjectArtData cached;

    public Sonic2ObjectArt(Rom rom, RomByteReader reader) {
        this.rom = rom;
        this.reader = reader;
    }

    public ObjectArtData load() throws IOException {
        if (cached != null) {
            return cached;
        }

        // Load Monitor Art (base art)
        Pattern[] monitorBasePatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_MONITOR_ADDR);
        // Load Tails Life Art (used for Tails Monitor icon, requests tile 340)
        Pattern[] tailsLifePatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_TAILS_LIFE_ADDR);

        List<SpriteMappingFrame> monitorMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MONITOR_ADDR);

        // Calculate max requested tile index
        int maxTileIndex = 0;
        for (SpriteMappingFrame frame : monitorMappings) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                maxTileIndex = Math.max(maxTileIndex, piece.tileIndex());
            }
        }

        // Extend monitor patterns to cover the max requested index
        int requiredSize = maxTileIndex + 1;
        // Ensure we have enough space for Tails Life Art starting at 340 (0x154 * 32
        // bytes = 10880 offset)
        int lifeArtOffset = 340;
        requiredSize = Math.max(requiredSize, lifeArtOffset + tailsLifePatterns.length);

        Pattern[] monitorPatterns = new Pattern[requiredSize];
        // Copy base patterns
        System.arraycopy(monitorBasePatterns, 0, monitorPatterns, 0, monitorBasePatterns.length);
        // Copy Tails Life patterns at offset 340
        if (lifeArtOffset < monitorPatterns.length) {
            System.arraycopy(tailsLifePatterns, 0, monitorPatterns, lifeArtOffset,
                    Math.min(tailsLifePatterns.length, monitorPatterns.length - lifeArtOffset));
        }

        // Fill gaps with empty patterns to prevent NPEs
        for (int i = 0; i < monitorPatterns.length; i++) {
            if (monitorPatterns[i] == null) {
                monitorPatterns[i] = new Pattern();
            }
        }

        ObjectSpriteSheet monitorSheet = new ObjectSpriteSheet(monitorPatterns, monitorMappings, 0, 1);

        Pattern[] spikePatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SPIKES_ADDR);
        Pattern[] spikeSidePatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SPIKES_SIDE_ADDR);
        List<SpriteMappingFrame> spikeMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SPIKES_ADDR);
        ObjectSpriteSheet spikeSheet = new ObjectSpriteSheet(spikePatterns, spikeMappings, 1, 1);
        ObjectSpriteSheet spikeSideSheet = new ObjectSpriteSheet(spikeSidePatterns, spikeMappings, 1, 1);

        Pattern[] springVerticalPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_VERTICAL_ADDR);
        Pattern[] springHorizontalPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_HORIZONTAL_ADDR);
        Pattern[] springDiagonalPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_DIAGONAL_ADDR);
        List<SpriteMappingFrame> springMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SPRING_ADDR);
        List<SpriteMappingFrame> springMappingsRed = loadMappingFrames(Sonic2Constants.MAP_UNC_SPRING_RED_ADDR);
        ObjectSpriteSheet springVerticalSheet = new ObjectSpriteSheet(springVerticalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springHorizontalSheet = new ObjectSpriteSheet(springHorizontalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springDiagonalSheet = new ObjectSpriteSheet(springDiagonalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springVerticalRedSheet = new ObjectSpriteSheet(springVerticalPatterns, springMappingsRed, 1,
                1);
        ObjectSpriteSheet springHorizontalRedSheet = new ObjectSpriteSheet(springHorizontalPatterns, springMappingsRed,
                1, 1);

        ObjectSpriteSheet springDiagonalRedSheet = new ObjectSpriteSheet(springDiagonalPatterns, springMappingsRed, 1,
                1);

        Pattern[] explosionPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_EXPLOSION_ADDR);
        List<SpriteMappingFrame> explosionMappings = createExplosionMappings();
        ObjectSpriteSheet explosionSheet = new ObjectSpriteSheet(explosionPatterns, explosionMappings, 0, 1);

        Pattern[] shieldPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_SHIELD_ADDR);
        List<SpriteMappingFrame> shieldMappings = createShieldMappings();
        ObjectSpriteSheet shieldSheet = new ObjectSpriteSheet(shieldPatterns, shieldMappings, 0, 1);

        SpriteAnimationSet monitorAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ26_ADDR,
                Sonic2Constants.ANI_OBJ26_SCRIPT_COUNT);
        SpriteAnimationSet springAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ41_ADDR,
                Sonic2Constants.ANI_OBJ41_SCRIPT_COUNT);

        cached = new ObjectArtData(
                monitorSheet,
                spikeSheet,
                spikeSideSheet,
                springVerticalSheet,
                springHorizontalSheet,
                springDiagonalSheet,
                springVerticalRedSheet,
                springHorizontalRedSheet,

                springDiagonalRedSheet,
                explosionSheet,
                shieldSheet,
                monitorAnimations,
                springAnimations);
        return cached;
    }

    private Pattern[] loadNemesisPatterns(int artAddr) throws IOException {
        FileChannel channel = rom.getFileChannel();
        channel.position(artAddr);
        byte[] result = NemesisReader.decompress(channel);

        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent object art tile data");
        }

        int patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    private List<SpriteMappingFrame> loadMappingFrames(int mappingAddr) {
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = mappingAddr + reader.readU16BE(mappingAddr + i * 2);
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // 2P tile word, unused in 1P.
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, paletteIndex));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    private SpriteAnimationSet loadAnimationSet(int animAddr, int scriptCount) {
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (int i = 0; i < scriptCount; i++) {
            int scriptAddr = animAddr + reader.readU16BE(animAddr + i * 2);
            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int endParam = 0;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value >= 0xF0) {
                    if (value == 0xFF) {
                        endAction = SpriteAnimationEndAction.LOOP;
                        break;
                    }
                    if (value == 0xFE) {
                        endAction = SpriteAnimationEndAction.LOOP_BACK;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    if (value == 0xFD) {
                        endAction = SpriteAnimationEndAction.SWITCH;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
        }
        return set;
    }

    private List<SpriteMappingFrame> createExplosionMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: -8, -8, 2x2, tile 0 (16x16 pixels)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        // Frame 1: -16, -16, 4x4, tile 4 (32x32 pixels)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 4));
        // Frame 2: -16, -16, 4x4, tile 20 (0x14)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 20));
        // Frame 3: -16, -16, 4x4, tile 36 (0x24)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 36));
        // Frame 4: -16, -16, 4x4, tile 52 (0x34)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 52));
        return frames;
    }

    private List<SpriteMappingFrame> createShieldMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_obj38_000C (0 tiles offset)
        frames.add(create2x2Frame(0));
        // Frame 1: Map_obj38_002E (4 tiles offset)
        frames.add(create2x2Frame(4));
        // Frame 2: Map_obj38_0050 (8 tiles offset)
        frames.add(create2x2Frame(8));
        // Frame 3: Map_obj38_0072 (12 tiles offset)
        frames.add(create2x2Frame(12));
        // Frame 4: Map_obj38_0094 (16 tiles offset)
        frames.add(create2x2Frame(16));

        // Frame 5: Map_obj38_00B6 (20 tiles offset) - Larger frame
        List<SpriteMappingPiece> pieces5 = new ArrayList<>();
        // Note: Palette index 0 assumed.
        // pieces: xOffset, yOffset, w, h, tileIndex, hFlip, vFlip, palIndex
        // obj38.asm: spritePiece -$18, -$20, 3, 4, $14... (3 tiles wide, 4 tiles high)
        pieces5.add(new SpriteMappingPiece(-24, -32, 3, 4, 20, false, false, 0));
        pieces5.add(new SpriteMappingPiece(0, -32, 3, 4, 20, true, false, 0));
        pieces5.add(new SpriteMappingPiece(-24, 0, 3, 4, 20, false, true, 0));
        pieces5.add(new SpriteMappingPiece(0, 0, 3, 4, 20, true, true, 0));

        frames.add(new SpriteMappingFrame(pieces5));

        return frames;
    }

    private SpriteMappingFrame create2x2Frame(int startTile) {
        List<SpriteMappingPiece> pieces = new ArrayList<>();
        // 2x2 tiles (16x16 pixels). w=2, h=2.
        pieces.add(new SpriteMappingPiece(-16, -16, 2, 2, startTile, false, false, 0));
        pieces.add(new SpriteMappingPiece(0, -16, 2, 2, startTile, true, false, 0));
        pieces.add(new SpriteMappingPiece(-16, 0, 2, 2, startTile, false, true, 0));
        pieces.add(new SpriteMappingPiece(0, 0, 2, 2, startTile, true, true, 0));
        return new SpriteMappingFrame(pieces);
    }

    private SpriteMappingFrame createSimpleFrame(int x, int y, int wTiles, int hTiles, int tileIndex) {
        SpriteMappingPiece piece = new SpriteMappingPiece(x, y, wTiles, hTiles, tileIndex, false, false, 0);
        return new SpriteMappingFrame(List.of(piece));
    }
}
