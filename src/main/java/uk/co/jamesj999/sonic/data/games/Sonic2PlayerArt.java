package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.SpriteDplcFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.level.render.TileLoadRequest;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationEndAction;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationScript;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads Sonic/Tails sprite art, mappings, and DPLCs for Sonic 2 (REV01).
 */
public class Sonic2PlayerArt {
    private final RomByteReader reader;
    private SpriteArtSet cachedSonic;
    private SpriteArtSet cachedTails;

    public Sonic2PlayerArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        String normalized = characterCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tails" -> loadTails();
            case "sonic" -> loadSonic();
            default -> null;
        };
    }

    public SpriteArtSet loadSonic() throws IOException {
        if (cachedSonic != null) {
            return cachedSonic;
        }
        cachedSonic = loadCharacter(
                Sonic2Constants.ART_UNC_SONIC_ADDR,
                Sonic2Constants.ART_UNC_SONIC_SIZE,
                Sonic2Constants.MAP_UNC_SONIC_ADDR,
                Sonic2Constants.MAP_R_UNC_SONIC_ADDR,
                Sonic2Constants.ART_TILE_SONIC
        );
        return cachedSonic;
    }

    public SpriteArtSet loadTails() throws IOException {
        if (cachedTails != null) {
            return cachedTails;
        }
        cachedTails = loadCharacter(
                Sonic2Constants.ART_UNC_TAILS_ADDR,
                Sonic2Constants.ART_UNC_TAILS_SIZE,
                Sonic2Constants.MAP_UNC_TAILS_ADDR,
                Sonic2Constants.MAP_R_UNC_TAILS_ADDR,
                Sonic2Constants.ART_TILE_TAILS
        );
        return cachedTails;
    }

    private SpriteArtSet loadCharacter(
            int artAddr,
            int artSize,
            int mappingAddr,
            int dplcAddr,
            int basePatternIndex
    ) throws IOException {
        Pattern[] artTiles = loadArtTiles(artAddr, artSize);
        List<SpriteMappingFrame> mappingFrames = loadMappingFrames(mappingAddr);
        List<SpriteDplcFrame> dplcFrames = loadDplcFrames(dplcAddr);

        int bankSize = resolveBankSize(dplcFrames, mappingFrames);
        int frameDelay = 1;
        int paletteIndex = 0;
        SpriteAnimationSet animationSet = basePatternIndex == Sonic2Constants.ART_TILE_SONIC
                ? loadSonicAnimations()
                : null;
        SpriteAnimationProfile animationProfile = new ScriptedVelocityAnimationProfile(
                Sonic2AnimationIds.WAIT,
                Sonic2AnimationIds.WALK,
                Sonic2AnimationIds.RUN,
                Sonic2AnimationIds.ROLL,
                Sonic2AnimationIds.ROLL,
                0x40,
                0x100,
                0
        );

        return new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                paletteIndex,
                basePatternIndex,
                frameDelay,
                bankSize,
                animationProfile,
                animationSet
        );
    }

    private Pattern[] loadArtTiles(int artAddr, int artSize) throws IOException {
        if (artSize % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent player art tile data");
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

    private SpriteAnimationSet loadSonicAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        int base = Sonic2Constants.SONIC_ANIM_DATA_ADDR;
        int count = Sonic2Constants.SONIC_ANIM_SCRIPT_COUNT;

        for (int i = 0; i < count; i++) {
            int scriptAddr = base + reader.readU16BE(base + i * 2);
            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int nextAnimId = i;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value == 0xFF) {
                    endAction = SpriteAnimationEndAction.LOOP;
                    break;
                }
                if (value == 0xFE) {
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                if (value == 0xFD) {
                    endAction = SpriteAnimationEndAction.SWITCH;
                    nextAnimId = reader.readU8(scriptAddr);
                    scriptAddr += 1;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, nextAnimId));
        }
        return set;
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

    private static class Sonic2AnimationIds {
        private static final int WALK = 0x00;
        private static final int RUN = 0x01;
        private static final int ROLL = 0x02;
        private static final int WAIT = 0x05;
    }
}
