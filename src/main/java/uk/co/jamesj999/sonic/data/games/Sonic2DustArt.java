package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.SpriteDplcFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.level.render.TileLoadRequest;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads spindash dust art, mappings, and DPLCs for Sonic 2 (REV01).
 */
public class Sonic2DustArt {
    private final RomByteReader reader;
    private SpriteArtSet cachedSonicDust;
    private SpriteArtSet cachedTailsDust;

    public Sonic2DustArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        String normalized = characterCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tails" -> loadTailsDust();
            case "sonic" -> loadSonicDust();
            default -> null;
        };
    }

    public SpriteArtSet loadSonicDust() throws IOException {
        if (cachedSonicDust != null) {
            return cachedSonicDust;
        }
        cachedSonicDust = loadDustArt(Sonic2Constants.ART_TILE_SONIC_DUST);
        return cachedSonicDust;
    }

    public SpriteArtSet loadTailsDust() throws IOException {
        if (cachedTailsDust != null) {
            return cachedTailsDust;
        }
        cachedTailsDust = loadDustArt(Sonic2Constants.ART_TILE_TAILS_DUST);
        return cachedTailsDust;
    }

    private SpriteArtSet loadDustArt(int basePatternIndex) throws IOException {
        Pattern[] artTiles = loadArtTiles(
                Sonic2Constants.ART_UNC_SPLASH_DUST_ADDR,
                Sonic2Constants.ART_UNC_SPLASH_DUST_SIZE
        );
        List<SpriteMappingFrame> mappingFrames = loadMappingFrames(
                Sonic2Constants.MAP_UNC_OBJ08_ADDR
        );
        List<SpriteDplcFrame> dplcFrames = loadDplcFrames(
                Sonic2Constants.MAP_R_UNC_OBJ08_ADDR
        );

        int bankSize = resolveBankSize(dplcFrames, mappingFrames);
        int paletteIndex = 0;
        int frameDelay = 1;

        return new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                paletteIndex,
                basePatternIndex,
                frameDelay,
                bankSize,
                null,
                null
        );
    }

    private Pattern[] loadArtTiles(int artAddr, int artSize) throws IOException {
        if (artSize % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent dust art tile data");
        }
        int tileCount = artSize / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[tileCount];
        for (int i = 0; i < tileCount; i++) {
            patterns[i] = new Pattern();
            int start = i * Pattern.PATTERN_SIZE_IN_ROM;
            patterns[i].fromSegaFormat(reader.slice(artAddr + start, Pattern.PATTERN_SIZE_IN_ROM));
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

    private List<SpriteDplcFrame> loadDplcFrames(int dplcAddr) {
        int offsetTableSize = reader.readU16BE(dplcAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = dplcAddr + reader.readU16BE(dplcAddr + i * 2);
            int requestCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<TileLoadRequest> requests = new ArrayList<>(requestCount);
            for (int r = 0; r < requestCount; r++) {
                int entry = reader.readU16BE(frameAddr);
                frameAddr += 2;
                int count = ((entry >> 12) & 0xF) + 1;
                int startTile = entry & 0x0FFF;
                requests.add(new TileLoadRequest(startTile, count));
            }
            frames.add(new SpriteDplcFrame(requests));
        }
        return frames;
    }

    private int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        int maxTiles = 0;
        for (SpriteDplcFrame frame : dplcFrames) {
            int total = 0;
            for (TileLoadRequest request : frame.requests()) {
                total += Math.max(0, request.count());
            }
            maxTiles = Math.max(maxTiles, total);
        }
        if (maxTiles > 0) {
            return maxTiles;
        }
        int maxIndex = 0;
        for (SpriteMappingFrame frame : mappingFrames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileCount = piece.widthTiles() * piece.heightTiles();
                maxIndex = Math.max(maxIndex, piece.tileIndex() + tileCount);
            }
        }
        return Math.max(0, maxIndex);
    }
}
