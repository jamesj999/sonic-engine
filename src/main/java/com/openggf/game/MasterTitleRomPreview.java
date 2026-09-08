package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenMappings;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.titlescreen.TitleScreenMappings;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.titlescreen.Sonic3kTitleScreenMappings;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.util.PatternDecompressor;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glTexSubImage2D;

/**
 * Runtime-only preview art for the master title selector.
 *
 * <p>The preview is decoded from user-supplied ROM data into an in-memory RGBA
 * texture. It deliberately does not write extracted title art into resources.
 */
final class MasterTitleRomPreview {
    private static final Logger LOGGER = Logger.getLogger(MasterTitleRomPreview.class.getName());

    private static final int S1_PREVIEW_WIDTH_TILES = 40;
    private static final int S1_PREVIEW_HEIGHT_TILES = 28;
    private static final int S1_TITLE_WIDTH_TILES = 34;
    private static final int S1_TITLE_HEIGHT_TILES = 22;
    private static final int S2_TITLE_WIDTH_TILES = 40;
    private static final int S2_TITLE_HEIGHT_TILES = 28;
    private static final int S3K_TITLE_WIDTH_TILES = 40;
    private static final int S3K_TITLE_HEIGHT_TILES = 28;
    private static final int S1_PLANE_A_SPLIT_ROW = 9;
    private static final int S1_PLANE_A_PIXEL_X = 32;
    private static final int S1_PLANE_A_PIXEL_Y = 32;
    private static final int S1_SONIC_MASK_Y = S1_PLANE_A_PIXEL_Y + S1_PLANE_A_SPLIT_ROW * Pattern.PATTERN_HEIGHT;
    private static final int S1_PREVIEW_SONIC_FRAME = 6;
    private static final int S1_SONIC_X = 0xF0 - 128;
    private static final int S1_SONIC_START_Y = 0xDE;
    private static final int S1_SONIC_FINAL_Y = 0x96;
    private static final int S1_SONIC_DELAY_FRAMES = 30;
    private static final int S1_SONIC_MOVE_STEP = 8;
    private static final int S1_SONIC_ANIM_DURATION = 7;
    private static final int S2_CHARACTER_PREVIEW_Y_OFFSET = 8;
    private static final boolean S2_PREVIEW_DRAWS_HANDS_BEHIND_LOGO_OCCLUSION = true;
    private static final boolean S2_PREVIEW_DRAWS_HANDS_BEHIND_FULL_LOGO_TEXT = false;
    private static final int S2_LOGO_OCCLUSION_BASE_Y = 13 * 8;
    private static final int S2_LOGO_OCCLUSION_CURVE_PIXELS = 16;
    private static final int S2_SCREEN_WIDTH = 320;
    private static final int S2_LOGO_OCCLUSION_HALF_WIDTH = 104;
    private static final int S2_SONIC_START_TICK = 0;
    private static final int S2_TAILS_START_TICK = 64;
    private static final int S2_SETTLED_TICK = 160;
    private static final int S3K_PREVIEW_BANNER_Y = 84;
    private static final int S3K_PREVIEW_VSCROLL = 16;
    private static final int S3K_PREVIEW_FINGER_X = 0x148 - 128;
    private static final int S3K_PREVIEW_FINGER_Y = 0xDC - S3K_PREVIEW_VSCROLL - 128;
    private static final int S3K_PREVIEW_FINGER_FRAME_DURATION = 6;
    private static final int S3K_PREVIEW_WINK_X = 0xF8 - 128;
    private static final int S3K_PREVIEW_WINK_Y = 0xC8 - S3K_PREVIEW_VSCROLL - 128;
    private static final int S3K_PREVIEW_SCALE_NUMERATOR = 1;
    private static final int S3K_PREVIEW_SCALE_DENOMINATOR = 1;
    private static final int S3K_PREVIEW_COPYRIGHT_X = 0x158 - 128;
    // Raise the title's y=204 copyright by four pixels to clear the master navigation hints.
    private static final int S3K_PREVIEW_COPYRIGHT_Y = 200;
    private static final boolean S3K_PREVIEW_DRAWS_COPYRIGHT = true;
    private static final boolean S3K_PREVIEW_DRAWS_MENU_SELECTION = false;
    private static final int[] S3K_PREVIEW_FINGER_WAG_FRAMES = {
            4, 4, 4, 4, 4, 4, 0, 4, 1, 4, 0, 4, 1, 4, 0, 4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4
    };
    private static final int[] S3K_PREVIEW_WINK_DATA = {
            2, 1,
            3, 7,
            2, 5,
            4, 0x67,
            4, 0x2F
    };
    private static final int[] S2_ANIM_SONIC = { 5, 6, 7 };
    private static final int[] S2_ANIM_TAILS = { 0, 1, 2, 3, 4 };
    private static final int[][] S2_SONIC_POSITIONS = {
            { 136, 80 }, { 128, 64 }, { 120, 48 }, { 118, 38 },
            { 122, 30 }, { 128, 26 }, { 132, 25 }, { 136, 24 },
    };
    private static final int[][] S2_TAILS_POSITIONS = {
            { 87, 72 }, { 83, 56 }, { 78, 44 }, { 76, 38 },
            { 74, 34 }, { 73, 33 }, { 72, 32 },
    };
    private static final int[][] S2_SONIC_HAND_POSITIONS = {
            { 195, 65 }, { 192, 66 }, { 193, 65 },
    };
    private static final int[][] S2_TAILS_HAND_POSITIONS = {
            { 140, 80 }, { 141, 81 },
    };
    private static final int[] S3K_ANIM_FRAME_DURATIONS = {
            16, 4, 4, 4, 4, 6, 16, 12, 12, 10, 3
    };
    private static final int[] S3K_SONIC_FRAME_INDEX_TABLE = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB
    };
    private static final int[] S3K_FRAME_ART_ADDRS = {
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC8_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC9_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC_A_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC_B_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC_C_ADDR,
            Sonic3kConstants.ART_KOS_TITLE_SONIC_D_ADDR,
    };
    private static final int[] S3K_FRAME_MAP_ADDRS = {
            Sonic3kConstants.MAP_ENI_TITLE_SONIC1_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC2_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC3_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC4_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC5_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC6_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC7_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC8_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC9_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_A_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_B_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_C_ADDR,
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_D_ADDR,
    };

    private MasterTitleRomPreview() {
    }

    @FunctionalInterface
    interface PixelClip {
        boolean visible(int x, int y);
    }

    record Image(int width, int height, byte[] rgba) {
        Image {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Preview image dimensions must be positive");
            }
            if (rgba == null || rgba.length != width * height * 4) {
                throw new IllegalArgumentException("RGBA data must be width*height*4 bytes");
            }
        }

        boolean hasVisiblePixels() {
            for (int i = 3; i < rgba.length; i += 4) {
                if ((rgba[i] & 0xFF) != 0) {
                    return true;
                }
            }
            return false;
        }
    }

    abstract static class PreviewSequence {
        final int width;
        final int height;
        final int stillTick;

        PreviewSequence(int width, int height, int stillTick) {
            this.width = width;
            this.height = height;
            this.stillTick = stillTick;
        }

        abstract int frameTokenAt(int tick);

        abstract Image imageAt(int tick);
    }

    private static final class GeneratedPreviewSequence extends PreviewSequence {
        private final IntUnaryOperator tokenFunction;
        private final IntFunction<Image> imageFactory;
        private final Map<Integer, Image> cache = new HashMap<>();

        GeneratedPreviewSequence(int width,
                                 int height,
                                 int stillTick,
                                 IntUnaryOperator tokenFunction,
                                 IntFunction<Image> imageFactory) {
            super(width, height, stillTick);
            this.tokenFunction = tokenFunction;
            this.imageFactory = imageFactory;
        }

        @Override
        int frameTokenAt(int tick) {
            return tokenFunction.applyAsInt(Math.max(0, tick));
        }

        @Override
        Image imageAt(int tick) {
            int token = frameTokenAt(tick);
            return cache.computeIfAbsent(token, imageFactory::apply);
        }
    }

    private static final class ArrayPreviewSequence extends PreviewSequence {
        private final Image[] frames;

        ArrayPreviewSequence(Image[] frames) {
            super(frames[0].width(), frames[0].height(), frames.length - 1);
            this.frames = frames;
        }

        @Override
        int frameTokenAt(int tick) {
            return Math.min(Math.max(0, tick), frames.length - 1);
        }

        @Override
        Image imageAt(int tick) {
            return frames[frameTokenAt(tick)];
        }
    }

    static Optional<Image> loadFor(MasterTitleScreen.GameEntry entry, Path romPath) {
        return loadSequenceFor(entry, romPath).map(sequence -> sequence.imageAt(sequence.stillTick));
    }

    static Optional<PreviewSequence> loadSequenceFor(MasterTitleScreen.GameEntry entry, Path romPath) {
        if (entry == null || romPath == null || !Files.isRegularFile(romPath)) {
            return Optional.empty();
        }

        try (Rom rom = new Rom()) {
            if (!rom.open(romPath.toString())) {
                return Optional.empty();
            }
            PreviewSequence sequence = switch (entry) {
                case SONIC_1 -> loadSonic1Sequence(rom);
                case SONIC_2 -> loadSonic2Sequence(rom);
                case SONIC_3K -> loadSonic3kSequence(rom);
            };
            return sequence != null && sequence.imageAt(sequence.stillTick).hasVisiblePixels()
                    ? Optional.of(sequence)
                    : Optional.empty();
        } catch (RuntimeException | IOException e) {
            LOGGER.log(Level.WARNING, "Failed to build master-title ROM preview for " + entry.gameId, e);
            return Optional.empty();
        }
    }

    static int uploadTexture(Image image) {
        ByteBuffer pixels = MemoryUtil.memAlloc(image.rgba.length);
        try {
            putFlippedRows(pixels, image);
            pixels.flip();

            int texId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texId);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image.width, image.height,
                    0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
            return texId;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    static void updateTexture(int textureId, Image image) {
        ByteBuffer pixels = MemoryUtil.memAlloc(image.rgba.length);
        try {
            putFlippedRows(pixels, image);
            pixels.flip();

            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, image.width, image.height,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glBindTexture(GL_TEXTURE_2D, 0);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void putFlippedRows(ByteBuffer pixels, Image image) {
        // TexturedQuadRenderer uses OpenGL texture coordinates; mirror the PNG
        // loader by uploading rows bottom-to-top.
        int stride = image.width * 4;
        for (int y = image.height - 1; y >= 0; y--) {
            pixels.put(image.rgba, y * stride, stride);
        }
    }

    static PreviewSequence sequenceForTest(Image[] frames) {
        return new ArrayPreviewSequence(frames);
    }

    static int sonic1PreviewSonicFrameIndex() {
        return S1_PREVIEW_SONIC_FRAME;
    }

    static int sonic1PreviewWidth() {
        return S1_PREVIEW_WIDTH_TILES * Pattern.PATTERN_WIDTH;
    }

    static int sonic1PreviewHeight() {
        return S1_PREVIEW_HEIGHT_TILES * Pattern.PATTERN_HEIGHT;
    }

    static int sonic1PlaneAX() {
        return S1_PLANE_A_PIXEL_X;
    }

    static int sonic1PlaneAY() {
        return S1_PLANE_A_PIXEL_Y;
    }

    static int sonic2PreviewCharacterYOffset() {
        return S2_CHARACTER_PREVIEW_Y_OFFSET;
    }

    static int sonic2PreviewSonicFrameAt(int tick) {
        if (tick < sonic2SonicAnimationStartTick()) {
            return S2_ANIM_SONIC[0];
        }
        int animStep = (tick - sonic2SonicAnimationStartTick()) / 2;
        return animStep >= S2_ANIM_SONIC.length ? 0x12 : S2_ANIM_SONIC[animStep];
    }

    static int sonic2PreviewSonicYAt(int tick) {
        if (tick < S2_SONIC_START_TICK) {
            return 96 + S2_CHARACTER_PREVIEW_Y_OFFSET;
        }
        return sonic2PositionAt(tick, S2_SONIC_START_TICK, S2_SONIC_POSITIONS)[1] + S2_CHARACTER_PREVIEW_Y_OFFSET;
    }

    static boolean sonic2PreviewDrawsHandsBehindLogoOcclusion() {
        return S2_PREVIEW_DRAWS_HANDS_BEHIND_LOGO_OCCLUSION;
    }

    static boolean sonic2PreviewDrawsHandsBehindFullLogoText() {
        return S2_PREVIEW_DRAWS_HANDS_BEHIND_FULL_LOGO_TEXT;
    }

    static int sonic2LogoOcclusionStartPixel(int screenX) {
        double center = (S2_SCREEN_WIDTH - 1) * 0.5;
        double norm = Math.abs((screenX - center) / S2_LOGO_OCCLUSION_HALF_WIDTH);
        norm = Math.min(1.0, norm);
        int curveOffset = (int) Math.round(norm * norm * S2_LOGO_OCCLUSION_CURVE_PIXELS);
        return S2_LOGO_OCCLUSION_BASE_Y + curveOffset;
    }

    static boolean sonic2PreviewBodyPixelVisible(int screenX, int screenY) {
        return screenY < sonic2LogoOcclusionStartPixel(screenX);
    }

    static boolean sonic3kPreviewDrawsMenuSelection() {
        return S3K_PREVIEW_DRAWS_MENU_SELECTION;
    }

    static boolean sonic3kPreviewDrawsCopyright() {
        return S3K_PREVIEW_DRAWS_COPYRIGHT;
    }

    static int sonic3kPreviewBannerY() {
        return S3K_PREVIEW_BANNER_Y;
    }

    static int sonic3kPreviewVScroll() {
        return S3K_PREVIEW_VSCROLL;
    }

    static int sonic3kPreviewFingerY() {
        return S3K_PREVIEW_FINGER_Y;
    }

    static int sonic3kPreviewWinkY() {
        return S3K_PREVIEW_WINK_Y;
    }

    static int sonic3kPreviewScaledWidth() {
        return S3K_TITLE_WIDTH_TILES * Pattern.PATTERN_WIDTH
                * S3K_PREVIEW_SCALE_NUMERATOR / S3K_PREVIEW_SCALE_DENOMINATOR;
    }

    static int sonic3kPreviewScaledHeight() {
        return S3K_TITLE_HEIGHT_TILES * Pattern.PATTERN_HEIGHT
                * S3K_PREVIEW_SCALE_NUMERATOR / S3K_PREVIEW_SCALE_DENOMINATOR;
    }

    static int sonic3kPreviewCopyrightX() {
        return S3K_PREVIEW_COPYRIGHT_X;
    }

    static int sonic3kPreviewCopyrightY() {
        return S3K_PREVIEW_COPYRIGHT_Y;
    }

    static Image composeTilemapImage(Pattern[] patterns,
                                     Palette[] palettes,
                                     int[] map,
                                     int widthTiles,
                                     int heightTiles) {
        return composeTilemapImage(patterns, palettes, map, widthTiles, heightTiles, 0);
    }

    static Image composeTilemapImage(Pattern[] patterns,
                                     Palette[] palettes,
                                     int[] map,
                                     int widthTiles,
                                     int heightTiles,
                                     int tileIndexBase) {
        int width = widthTiles * Pattern.PATTERN_WIDTH;
        int height = heightTiles * Pattern.PATTERN_HEIGHT;
        byte[] rgba = new byte[width * height * 4];

        if (patterns == null || palettes == null || map == null) {
            return new Image(width, height, rgba);
        }

        int tileCount = Math.min(map.length, widthTiles * heightTiles);
        for (int tile = 0; tile < tileCount; tile++) {
            int word = map[tile];
            int patternIndex = (word & 0x7FF) - tileIndexBase;
            if (patternIndex < 0 || patternIndex >= patterns.length) {
                continue;
            }
            Pattern pattern = patterns[patternIndex];
            if (pattern == null) {
                continue;
            }

            int paletteIndex = (word >> 13) & 0x03;
            Palette palette = paletteIndex < palettes.length ? palettes[paletteIndex] : null;
            if (palette == null) {
                continue;
            }

            boolean hFlip = (word & 0x0800) != 0;
            boolean vFlip = (word & 0x1000) != 0;
            int tileX = tile % widthTiles;
            int tileY = tile / widthTiles;
            blitPattern(rgba, width, tileX * 8, tileY * 8, pattern, palette, hFlip, vFlip);
        }

        return new Image(width, height, rgba);
    }

    static void overlaySpriteFrame(Image image,
                                   Pattern[] patterns,
                                   Palette[] palettes,
                                   SpriteMappingFrame frame,
                                   int originX,
                                   int originY,
                                   int tileIndexBase) {
        overlaySpriteFrameClipped(image, patterns, palettes, frame, originX, originY, tileIndexBase,
                (x, y) -> true);
    }

    static void overlaySpriteFrameClipped(Image image,
                                          Pattern[] patterns,
                                          Palette[] palettes,
                                          SpriteMappingFrame frame,
                                          int originX,
                                          int originY,
                                          int tileIndexBase,
                                          PixelClip clip) {
        if (image == null || patterns == null || palettes == null || frame == null || frame.pieces() == null) {
            return;
        }
        for (int i = frame.pieces().size() - 1; i >= 0; i--) {
            overlaySpritePiece(image, patterns, palettes, frame.pieces().get(i), originX, originY, tileIndexBase, clip);
        }
    }

    private static PreviewSequence loadSonic1Sequence(Rom rom) throws IOException {
        Pattern[] patterns = PatternDecompressor.nemesis(
                rom, Sonic1Constants.ART_NEM_TITLE_FG_ADDR, 8192, "MasterPreviewS1TitleFg");
        Pattern[] sonicPatterns = PatternDecompressor.nemesis(
                rom, Sonic1Constants.ART_NEM_TITLE_SONIC_ADDR, 65536, "MasterPreviewS1TitleSonic");
        Pattern[] tmPatterns = PatternDecompressor.nemesis(
                rom, Sonic1Constants.ART_NEM_TITLE_TM_ADDR, 1024, "MasterPreviewS1TitleTM");
        int[] map = loadEnigmaMap(rom, Sonic1Constants.MAP_ENI_TITLE_ADDR, 0, 1024);
        Palette[] palettes = loadPaletteLines(rom.readBytes(Sonic1Constants.PAL_TITLE_ADDR, 128));

        int stillTick = 128;
        return new GeneratedPreviewSequence(sonic1PreviewWidth(), sonic1PreviewHeight(), stillTick,
                MasterTitleRomPreview::sonic1PreviewTokenAt,
                tick -> composeSonic1Preview(patterns, sonicPatterns, tmPatterns, map, palettes, tick));
    }

    private static Image composeSonic1Preview(Pattern[] patterns,
                                              Pattern[] sonicPatterns,
                                              Pattern[] tmPatterns,
                                              int[] map,
                                              Palette[] palettes,
                                              int tick) {
        Image image = emptyTilemapImage(S1_PREVIEW_WIDTH_TILES, S1_PREVIEW_HEIGHT_TILES);
        overlayTilemapRows(image, patterns, palettes, map, S1_TITLE_WIDTH_TILES,
                0, S1_PLANE_A_SPLIT_ROW, Sonic1Constants.ARTTILE_TITLE_FOREGROUND,
                S1_PLANE_A_PIXEL_X, S1_PLANE_A_PIXEL_Y);
        SpriteMappingFrame sonic = Sonic1TitleScreenMappings.createFrames()
                .get(sonic1PreviewSonicFrameAt(tick));
        overlaySpriteFrameClipped(image, sonicPatterns, new Palette[] { palettes[1] }, sonic,
                S1_SONIC_X, sonic1PreviewSonicYAt(tick) - 128, 0,
                MasterTitleRomPreview::sonic1PreviewSonicPixelVisible);
        overlayTilemapRows(image, patterns, palettes, map, S1_TITLE_WIDTH_TILES,
                S1_PLANE_A_SPLIT_ROW, S1_TITLE_HEIGHT_TILES, Sonic1Constants.ARTTILE_TITLE_FOREGROUND,
                S1_PLANE_A_PIXEL_X, S1_PLANE_A_PIXEL_Y);
        overlaySpriteFrame(image, tmPatterns, new Palette[] { palettes[1] },
                Sonic1TitleScreenMappings.createFrames().get(Sonic1TitleScreenMappings.FRAME_TM),
                0x170 - 128, 0xF8 - 128, 0);
        return image;
    }

    private static PreviewSequence loadSonic2Sequence(Rom rom) throws IOException {
        Pattern[] patterns = PatternDecompressor.nemesis(
                rom, Sonic2Constants.ART_NEM_TITLE_ADDR, 8192, "MasterPreviewS2Title");
        Pattern[] spritePatterns = PatternDecompressor.nemesis(
                rom, Sonic2Constants.ART_NEM_TITLE_SPRITES_ADDR, 65536, "MasterPreviewS2TitleSprites");
        Pattern[] menuJunkPatterns = PatternDecompressor.nemesis(
                rom, Sonic2Constants.ART_NEM_MENU_JUNK_ADDR, 1024, "MasterPreviewS2MenuJunk");
        Pattern[] combinedSpritePatterns = appendPatternsAt(spritePatterns, menuJunkPatterns, 0x2A2);
        // TitleScreen uploads the standard font to ArtTile_ArtNem_FontStuff_TtlScr and copies
        // the CopyrightText words ("@ 1992 SEGA", word_3E82) into the logo map at
        // planeLoc(40,28,26) before it reaches Plane A.
        Pattern[] fontPatterns = PatternDecompressor.nemesis(
                rom, Sonic2Constants.ART_NEM_FONT_STUFF_ADDR, 4096, "MasterPreviewS2FontStuff");
        patterns = appendPatternsAt(patterns, fontPatterns, Sonic2Constants.ART_TILE_FONT_STUFF_TITLE_SCREEN);
        int[] map = loadEnigmaMap(rom, Sonic2Constants.MAP_ENI_TITLE_LOGO_ADDR, 0xE000, 1024);
        map = withSonic2CopyrightText(map, readWords(rom,
                Sonic2Constants.TITLE_COPYRIGHT_TEXT_ADDR, Sonic2Constants.TITLE_COPYRIGHT_TEXT_WORDS));
        Palette[] palettes = loadSonic2TitlePalettes(rom);
        Image image = composeTilemapImage(patterns, palettes, map, S2_TITLE_WIDTH_TILES, S2_TITLE_HEIGHT_TILES);
        Image logo = composeTilemapImage(patterns, palettes, map, S2_TITLE_WIDTH_TILES, S2_TITLE_HEIGHT_TILES);
        int stillTick = S2_SETTLED_TICK;
        return new GeneratedPreviewSequence(image.width(), image.height(), stillTick,
                MasterTitleRomPreview::sonic2PreviewTokenAt,
                tick -> composeSonic2Preview(image, logo, combinedSpritePatterns, palettes, tick));
    }

    private static Image composeSonic2Preview(Image baseLogo,
                                              Image logoOcclusion,
                                              Pattern[] spritePatterns,
                                              Palette[] palettes,
                                              int tick) {
        Image image = copyImage(baseLogo);
        var frames = TitleScreenMappings.createFrames();
        if (tick >= S2_SONIC_START_TICK) {
            int[] sonic = sonic2PositionAt(tick, S2_SONIC_START_TICK, S2_SONIC_POSITIONS);
            overlaySpriteFrameClipped(image, spritePatterns, palettes, frames.get(sonic2PreviewSonicFrameAt(tick)),
                    sonic[0], sonic[1] + S2_CHARACTER_PREVIEW_Y_OFFSET, 0,
                    MasterTitleRomPreview::sonic2PreviewBodyPixelVisible);
        }
        if (tick >= S2_TAILS_START_TICK) {
            int[] tails = sonic2PositionAt(tick, S2_TAILS_START_TICK, S2_TAILS_POSITIONS);
            overlaySpriteFrameClipped(image, spritePatterns, palettes, frames.get(sonic2PreviewTailsFrameAt(tick)),
                    tails[0], tails[1] + S2_CHARACTER_PREVIEW_Y_OFFSET, 0,
                    MasterTitleRomPreview::sonic2PreviewBodyPixelVisible);
        }
        int sonicHandStart = sonic2SonicAnimationStartTick() + S2_ANIM_SONIC.length * 2;
        if (tick >= sonicHandStart) {
            int[] hand = heldPositionAt(tick, sonicHandStart, S2_SONIC_HAND_POSITIONS);
            overlaySpriteFrame(image, spritePatterns, palettes, frames.get(9),
                    hand[0], hand[1] + S2_CHARACTER_PREVIEW_Y_OFFSET, 0);
        }
        int tailsHandStart = sonic2TailsAnimationStartTick() + S2_ANIM_TAILS.length * 2;
        if (tick >= tailsHandStart) {
            int[] hand = heldPositionAt(tick, tailsHandStart, S2_TAILS_HAND_POSITIONS);
            overlaySpriteFrame(image, spritePatterns, palettes, frames.get(0x13),
                    hand[0], hand[1] + S2_CHARACTER_PREVIEW_Y_OFFSET, 0);
        }
        overlaySonic2CurvedLogoOcclusion(image, logoOcclusion);
        return image;
    }

    static int[] withSonic2CopyrightText(int[] logoMap, int[] copyrightWords) {
        int[] map = logoMap.clone();
        int base = Sonic2Constants.TITLE_COPYRIGHT_PLANE_ROW * S2_TITLE_WIDTH_TILES
                + Sonic2Constants.TITLE_COPYRIGHT_PLANE_COLUMN;
        for (int i = 0; i < copyrightWords.length && base + i < map.length; i++) {
            map[base + i] = copyrightWords[i];
        }
        return map;
    }

    private static int[] readWords(Rom rom, int address, int count) throws IOException {
        byte[] bytes = rom.readBytes(address, count * 2);
        int[] words = new int[count];
        for (int i = 0; i < count; i++) {
            words[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        return words;
    }

    private static PreviewSequence loadSonic3kSequence(Rom rom) throws IOException {
        Pattern[] patterns = PatternDecompressor.kosinski(rom, Sonic3kConstants.ART_KOS_TITLE_SONIC_D_ADDR);
        Pattern[] spritePatterns = loadSonic3kSpritePatterns(rom);
        int[] map = loadEnigmaMap(rom, Sonic3kConstants.MAP_ENI_TITLE_SONIC_D_ADDR,
                0x8000, Sonic3kConstants.MAP_ENI_TITLE_READ_SIZE);
        Palette[] palettes = loadPaletteLines(rom.readBytes(
                Sonic3kConstants.PAL_TITLE_SONIC_D_ADDR,
                Sonic3kConstants.PAL_TITLE_SONIC_D_SIZE));
        Image finalScene = shiftImage(composeTilemapImage(patterns, palettes, map,
                S3K_TITLE_WIDTH_TILES, S3K_TITLE_HEIGHT_TILES), 0, -sonic3kPreviewVScroll());
        int stillTick = 0;
        return new GeneratedPreviewSequence(finalScene.width(), finalScene.height(), stillTick,
                MasterTitleRomPreview::s3kPreviewTokenAt,
                token -> composeSonic3kFinalPreview(finalScene, spritePatterns, palettes,
                        s3kPreviewFingerFrameFromToken(token), s3kPreviewWinkFrameFromToken(token)));
    }

    private static Image composeSonic3kFinalPreview(Image finalScene,
                                                    Pattern[] spritePatterns,
                                                    Palette[] palettes,
                                                    int fingerFrame,
                                                    int winkFrame) {
        Image image = copyImage(finalScene);

        if (winkFrame >= 0) {
            overlaySpriteFrame(image, spritePatterns, palettes,
                    Sonic3kTitleScreenMappings.createSonicAnimFrames().get(winkFrame),
                    S3K_PREVIEW_WINK_X, S3K_PREVIEW_WINK_Y, 0);
        }
        if (fingerFrame >= 0) {
            overlaySpriteFrame(image, spritePatterns, palettes,
                    Sonic3kTitleScreenMappings.createSonicAnimFrames().get(fingerFrame),
                    S3K_PREVIEW_FINGER_X, S3K_PREVIEW_FINGER_Y, 0);
        }
        int bannerY = sonic3kPreviewBannerY();
        overlaySpriteFrame(image, spritePatterns, palettes, Sonic3kTitleScreenMappings.createBannerFrames().get(0),
                0x120 - 128, bannerY, 0);
        // Settled title TM uses fixed VDP coordinates, as in the title-screen manager.
        overlaySpriteFrame(image, spritePatterns, palettes, Sonic3kTitleScreenMappings.createBannerFrames().get(1),
                0x188 - 128, 0xEC - 128, 0);
        overlaySpriteFrame(image, spritePatterns, palettes, Sonic3kTitleScreenMappings.createAndKnucklesFrames().get(0),
                0x120 - 128, bannerY + 0x5C, 0);
        if (sonic3kPreviewDrawsMenuSelection()) {
            overlaySpriteFrame(image, spritePatterns, palettes, Sonic3kTitleScreenMappings.createSelectionFrames().get(0),
                    0xF0 - 128, 0x140 - 128, 0);
        }
        if (sonic3kPreviewDrawsCopyright()) {
            overlaySpriteFrame(image, spritePatterns, palettes, Sonic3kTitleScreenMappings.createCopyrightFrame().get(0),
                    sonic3kPreviewCopyrightX(), sonic3kPreviewCopyrightY(), 0);
        }
        return scaleImageIntoNativeCanvas(image, sonic3kPreviewScaledWidth(), sonic3kPreviewScaledHeight());
    }

    private static Image copyImage(Image image) {
        return new Image(image.width(), image.height(), Arrays.copyOf(image.rgba(), image.rgba().length));
    }

    private static Image shiftImage(Image source, int deltaX, int deltaY) {
        byte[] shifted = new byte[source.rgba().length];
        for (int y = 0; y < source.height(); y++) {
            int destY = y + deltaY;
            if (destY < 0 || destY >= source.height()) {
                continue;
            }
            for (int x = 0; x < source.width(); x++) {
                int destX = x + deltaX;
                if (destX < 0 || destX >= source.width()) {
                    continue;
                }
                int sourceOffset = ((y * source.width()) + x) * 4;
                int destOffset = ((destY * source.width()) + destX) * 4;
                shifted[destOffset] = source.rgba()[sourceOffset];
                shifted[destOffset + 1] = source.rgba()[sourceOffset + 1];
                shifted[destOffset + 2] = source.rgba()[sourceOffset + 2];
                shifted[destOffset + 3] = source.rgba()[sourceOffset + 3];
            }
        }
        return new Image(source.width(), source.height(), shifted);
    }

    private static Image scaleImageIntoNativeCanvas(Image source, int scaledWidth, int scaledHeight) {
        int width = source.width();
        int height = source.height();
        byte[] scaled = new byte[source.rgba().length];
        int offsetX = (width - scaledWidth) / 2;
        int offsetY = (height - scaledHeight) / 2;
        double scaleX = width / (double) scaledWidth;
        double scaleY = height / (double) scaledHeight;
        for (int y = 0; y < scaledHeight; y++) {
            double sourceY0 = y * scaleY;
            double sourceY1 = (y + 1) * scaleY;
            for (int x = 0; x < scaledWidth; x++) {
                double sourceX0 = x * scaleX;
                double sourceX1 = (x + 1) * scaleX;
                int destOffset = (((offsetY + y) * width) + offsetX + x) * 4;
                writeAreaSample(source, sourceX0, sourceX1, sourceY0, sourceY1, scaled, destOffset);
            }
        }
        return new Image(width, height, scaled);
    }

    private static void writeAreaSample(Image source,
                                        double sourceX0,
                                        double sourceX1,
                                        double sourceY0,
                                        double sourceY1,
                                        byte[] dest,
                                        int destOffset) {
        int width = source.width();
        int height = source.height();
        byte[] rgba = source.rgba();
        int startX = Math.max(0, (int) Math.floor(sourceX0));
        int endX = Math.min(width, (int) Math.ceil(sourceX1));
        int startY = Math.max(0, (int) Math.floor(sourceY0));
        int endY = Math.min(height, (int) Math.ceil(sourceY1));
        double red = 0;
        double green = 0;
        double blue = 0;
        double alpha = 0;
        double coverage = 0;

        for (int sy = startY; sy < endY; sy++) {
            double overlapY = Math.min(sy + 1.0, sourceY1) - Math.max(sy, sourceY0);
            if (overlapY <= 0) {
                continue;
            }
            for (int sx = startX; sx < endX; sx++) {
                double overlapX = Math.min(sx + 1.0, sourceX1) - Math.max(sx, sourceX0);
                if (overlapX <= 0) {
                    continue;
                }
                double weight = overlapX * overlapY;
                int sourceOffset = ((sy * width) + sx) * 4;
                double sourceAlpha = (rgba[sourceOffset + 3] & 0xFF) / 255.0;
                red += (rgba[sourceOffset] & 0xFF) * sourceAlpha * weight;
                green += (rgba[sourceOffset + 1] & 0xFF) * sourceAlpha * weight;
                blue += (rgba[sourceOffset + 2] & 0xFF) * sourceAlpha * weight;
                alpha += sourceAlpha * weight;
                coverage += weight;
            }
        }

        if (coverage <= 0 || alpha <= 0) {
            return;
        }
        dest[destOffset] = roundedByte(red / alpha);
        dest[destOffset + 1] = roundedByte(green / alpha);
        dest[destOffset + 2] = roundedByte(blue / alpha);
        dest[destOffset + 3] = roundedByte((alpha / coverage) * 255.0);
    }

    private static byte roundedByte(double value) {
        int rounded = Math.max(0, Math.min(255, (int) Math.round(value)));
        return (byte) rounded;
    }

    private static int sonic1PreviewSonicYAt(int tick) {
        if (tick < S1_SONIC_DELAY_FRAMES) {
            return S1_SONIC_START_Y;
        }
        int moved = (tick - S1_SONIC_DELAY_FRAMES + 1) * S1_SONIC_MOVE_STEP;
        return Math.max(S1_SONIC_FINAL_Y, S1_SONIC_START_Y - moved);
    }

    private static int sonic1PreviewTokenAt(int tick) {
        int normalized = Math.max(0, tick);
        if (normalized < S1_SONIC_DELAY_FRAMES) {
            return 0;
        }
        int finalTick = S1_SONIC_DELAY_FRAMES + ((S1_SONIC_START_Y - S1_SONIC_FINAL_Y) / S1_SONIC_MOVE_STEP);
        if (normalized < finalTick) {
            return normalized;
        }
        int frame = (normalized - finalTick) / (S1_SONIC_ANIM_DURATION + 1);
        if (frame <= Sonic1TitleScreenMappings.FRAME_TITLE_SONIC_7) {
            return finalTick + frame * (S1_SONIC_ANIM_DURATION + 1);
        }
        int loopFrame = 6 + ((frame - 8) & 1);
        return finalTick + loopFrame * (S1_SONIC_ANIM_DURATION + 1);
    }

    private static int sonic1PreviewSonicFrameAt(int tick) {
        int finalTick = S1_SONIC_DELAY_FRAMES + ((S1_SONIC_START_Y - S1_SONIC_FINAL_Y) / S1_SONIC_MOVE_STEP);
        if (tick < finalTick) {
            return Sonic1TitleScreenMappings.FRAME_TITLE_SONIC_0;
        }
        int animTick = tick - finalTick;
        int frame = animTick / (S1_SONIC_ANIM_DURATION + 1);
        if (frame <= Sonic1TitleScreenMappings.FRAME_TITLE_SONIC_7) {
            return frame;
        }
        return 6 + ((frame - 8) & 1);
    }

    private static int[] sonic2PositionAt(int tick, int startTick, int[][] positions) {
        if (tick < startTick) {
            return positions[0];
        }
        int index = Math.min((tick - startTick) / 4, positions.length - 1);
        return positions[index];
    }

    private static int[] heldPositionAt(int tick, int startTick, int[][] positions) {
        int index = Math.min(Math.max(0, (tick - startTick) / 4), positions.length - 1);
        return positions[index];
    }

    private static int sonic2SonicAnimationStartTick() {
        return S2_SONIC_START_TICK + S2_SONIC_POSITIONS.length * 4;
    }

    private static int sonic2TailsAnimationStartTick() {
        return S2_TAILS_START_TICK + S2_TAILS_POSITIONS.length * 4;
    }

    private static int sonic2PreviewTailsFrameAt(int tick) {
        if (tick < sonic2TailsAnimationStartTick()) {
            return 1;
        }
        int animStep = (tick - sonic2TailsAnimationStartTick()) / 2;
        return animStep >= S2_ANIM_TAILS.length
                ? S2_ANIM_TAILS[S2_ANIM_TAILS.length - 1]
                : S2_ANIM_TAILS[animStep];
    }

    private static int sonic2PreviewTokenAt(int tick) {
        int clamped = Math.min(Math.max(0, tick), S2_SETTLED_TICK);
        if (clamped < S2_SONIC_START_TICK) {
            return 0;
        }
        int sonicAnimStart = sonic2SonicAnimationStartTick();
        if (clamped < sonicAnimStart) {
            return S2_SONIC_START_TICK + ((clamped - S2_SONIC_START_TICK) / 4) * 4;
        }
        int sonicHandStart = sonicAnimStart + S2_ANIM_SONIC.length * 2;
        if (clamped < sonicHandStart) {
            return sonicAnimStart + ((clamped - sonicAnimStart) / 2) * 2;
        }
        if (clamped < S2_TAILS_START_TICK) {
            return sonicHandStart + Math.min((clamped - sonicHandStart) / 4,
                    S2_SONIC_HAND_POSITIONS.length - 1) * 4;
        }
        int tailsAnimStart = sonic2TailsAnimationStartTick();
        if (clamped < tailsAnimStart) {
            return S2_TAILS_START_TICK + ((clamped - S2_TAILS_START_TICK) / 4) * 4;
        }
        int tailsHandStart = tailsAnimStart + S2_ANIM_TAILS.length * 2;
        if (clamped < tailsHandStart) {
            return tailsAnimStart + ((clamped - tailsAnimStart) / 2) * 2;
        }
        if (clamped < tailsHandStart + S2_TAILS_HAND_POSITIONS.length * 4) {
            return tailsHandStart + Math.min((clamped - tailsHandStart) / 4,
                    S2_TAILS_HAND_POSITIONS.length - 1) * 4;
        }
        return S2_SETTLED_TICK;
    }

    private static int s3kPreviewTokenAt(int tick) {
        return (s3kPreviewWinkFrameAt(tick) << 8) | s3kPreviewFingerFrameAt(tick);
    }

    private static int s3kPreviewFingerFrameFromToken(int token) {
        return token & 0xFF;
    }

    private static int s3kPreviewWinkFrameFromToken(int token) {
        return (token >> 8) & 0xFF;
    }

    private static int s3kPreviewFingerFrameAt(int tick) {
        int index = (Math.max(0, tick) / S3K_PREVIEW_FINGER_FRAME_DURATION)
                % S3K_PREVIEW_FINGER_WAG_FRAMES.length;
        return S3K_PREVIEW_FINGER_WAG_FRAMES[index];
    }

    private static int s3kPreviewWinkFrameAt(int tick) {
        int normalized = Math.max(0, tick) % s3kPreviewWinkCycleTicks();
        for (int i = 0; i < S3K_PREVIEW_WINK_DATA.length; i += 2) {
            int frame = S3K_PREVIEW_WINK_DATA[i];
            int duration = S3K_PREVIEW_WINK_DATA[i + 1] + 1;
            if (normalized < duration) {
                return frame;
            }
            normalized -= duration;
        }
        return 4;
    }

    private static int s3kPreviewWinkCycleTicks() {
        int total = 0;
        for (int i = 1; i < S3K_PREVIEW_WINK_DATA.length; i += 2) {
            total += S3K_PREVIEW_WINK_DATA[i] + 1;
        }
        return total;
    }

    private static int[] loadEnigmaMap(Rom rom, int address, int startingArtTile, int readSize) throws IOException {
        byte[] compressed = rom.readBytes(address, readSize);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            byte[] decompressed = EnigmaReader.decompress(channel, startingArtTile);
            int[] words = new int[decompressed.length / 2];
            ByteBuffer buf = ByteBuffer.wrap(decompressed);
            for (int i = 0; i < words.length; i++) {
                words[i] = buf.getShort() & 0xFFFF;
            }
            return words;
        }
    }

    private static Palette[] loadPaletteLines(byte[] data) {
        int lines = data.length / Palette.PALETTE_SIZE_IN_ROM;
        Palette[] palettes = new Palette[lines];
        for (int line = 0; line < lines; line++) {
            palettes[line] = loadPalette(Arrays.copyOfRange(data,
                    line * Palette.PALETTE_SIZE_IN_ROM,
                    (line + 1) * Palette.PALETTE_SIZE_IN_ROM));
        }
        return palettes;
    }

    private static Palette loadPalette(byte[] data) {
        Palette palette = new Palette();
        palette.fromSegaFormat(data);
        return palette;
    }

    private static Palette[] loadSonic2TitlePalettes(Rom rom) throws IOException {
        Palette[] palettes = new Palette[4];
        palettes[0] = loadPalette(rom.readBytes(
                Sonic2Constants.PAL_TITLE_SONIC_ADDR,
                Sonic2Constants.PAL_TITLE_SONIC_SIZE));
        palettes[1] = loadPalette(rom.readBytes(
                Sonic2Constants.PAL_TITLE_ADDR,
                Sonic2Constants.PAL_TITLE_SIZE));
        palettes[2] = loadPalette(rom.readBytes(
                Sonic2Constants.PAL_TITLE_BACKGROUND_ADDR,
                Sonic2Constants.PAL_TITLE_BACKGROUND_SIZE));
        palettes[3] = loadPalette(rom.readBytes(
                Sonic2Constants.PAL_TITLE_EMBLEM_ADDR,
                Sonic2Constants.PAL_TITLE_EMBLEM_SIZE));
        return palettes;
    }

    private static Pattern[] loadSonic3kSpritePatterns(Rom rom) {
        Pattern[] sonicSprites = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_SONIC_SPRITES_ADDR, 65536, "MasterPreviewS3kTitleSonicSprites");
        Pattern[] andKnuckles = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_AND_KNUCKLES_ADDR, 8192, "MasterPreviewS3kTitleAndKnuckles");
        Pattern[] banner = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_BANNER_ADDR, 65536, "MasterPreviewS3kTitleBanner");
        Pattern[] menuText = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_SCREEN_TEXT_ADDR, 8192, "MasterPreviewS3kTitleScreenText");

        Pattern[] combined = appendPatternsAt(new Pattern[0], sonicSprites,
                Sonic3kConstants.VRAM_TITLE_MISC - 0x400);
        combined = appendPatternsAt(combined, andKnuckles,
                Sonic3kConstants.VRAM_TITLE_AND_KNUCKLES - 0x400);
        combined = appendPatternsAt(combined, banner,
                Sonic3kConstants.VRAM_TITLE_BANNER - 0x400);
        return appendPatternsAt(combined, menuText,
                Sonic3kConstants.VRAM_TITLE_MENU - 0x400);
    }

    private static Pattern[] appendPatternsAt(Pattern[] base, Pattern[] addition, int offset) {
        if (addition == null || addition.length == 0) {
            return base != null ? base : new Pattern[0];
        }
        int baseLength = base != null ? base.length : 0;
        int total = Math.max(baseLength, offset + addition.length);
        Pattern[] combined = new Pattern[total];
        if (base != null) {
            System.arraycopy(base, 0, combined, 0, base.length);
        }
        System.arraycopy(addition, 0, combined, offset, addition.length);
        return combined;
    }

    private static Image emptyTilemapImage(int widthTiles, int heightTiles) {
        return new Image(widthTiles * Pattern.PATTERN_WIDTH,
                heightTiles * Pattern.PATTERN_HEIGHT,
                new byte[widthTiles * Pattern.PATTERN_WIDTH * heightTiles * Pattern.PATTERN_HEIGHT * 4]);
    }

    private static void overlayTilemapRows(Image image,
                                           Pattern[] patterns,
                                           Palette[] palettes,
                                           int[] map,
                                           int widthTiles,
                                           int startTileRow,
                                           int endTileRow,
                                           int tileIndexBase) {
        overlayTilemapRows(image, patterns, palettes, map, widthTiles, startTileRow, endTileRow,
                tileIndexBase, 0, 0);
    }

    private static void overlayTilemapRows(Image image,
                                           Pattern[] patterns,
                                           Palette[] palettes,
                                           int[] map,
                                           int widthTiles,
                                           int startTileRow,
                                           int endTileRow,
                                           int tileIndexBase,
                                           int pixelOffsetX,
                                           int pixelOffsetY) {
        if (patterns == null || palettes == null || map == null) {
            return;
        }
        int tileCount = Math.min(map.length, widthTiles * endTileRow);
        for (int tile = Math.max(0, startTileRow) * widthTiles; tile < tileCount; tile++) {
            int word = map[tile];
            int patternIndex = (word & 0x7FF) - tileIndexBase;
            if (patternIndex < 0 || patternIndex >= patterns.length || patterns[patternIndex] == null) {
                continue;
            }
            int paletteIndex = (word >> 13) & 0x03;
            Palette palette = paletteIndex < palettes.length ? palettes[paletteIndex] : null;
            if (palette == null) {
                continue;
            }
            blitPattern(image.rgba, image.width,
                    pixelOffsetX + (tile % widthTiles) * 8,
                    pixelOffsetY + (tile / widthTiles) * 8,
                    patterns[patternIndex],
                    palette,
                    (word & 0x0800) != 0,
                    (word & 0x1000) != 0);
        }
    }

    private static void overlaySonic2CurvedLogoOcclusion(Image image, Image logo) {
        if (image == null || logo == null || image.width != logo.width || image.height != logo.height) {
            return;
        }
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                if (y < sonic2LogoOcclusionStartPixel(x)) {
                    continue;
                }
                int offset = ((y * image.width) + x) * 4;
                if ((logo.rgba[offset + 3] & 0xFF) == 0) {
                    continue;
                }
                image.rgba[offset] = logo.rgba[offset];
                image.rgba[offset + 1] = logo.rgba[offset + 1];
                image.rgba[offset + 2] = logo.rgba[offset + 2];
                image.rgba[offset + 3] = logo.rgba[offset + 3];
            }
        }
    }

    private static void overlaySpritePiece(Image image,
                                           Pattern[] patterns,
                                           Palette[] palettes,
                                           SpriteMappingPiece piece,
                                           int originX,
                                           int originY,
                                           int tileIndexBase,
                                           PixelClip clip) {
        SpritePieceRenderer.renderPiece(piece, originX, originY, -tileIndexBase, -1, false, false,
                (patternIndex, hFlip, vFlip, paletteIndex, drawX, drawY) -> {
                    Palette palette = paletteIndex < palettes.length ? palettes[paletteIndex] : null;
                    if (palette == null || patternIndex < 0 || patternIndex >= patterns.length
                            || patterns[patternIndex] == null) {
                        return;
                    }
                    blitPattern(image.rgba, image.width, drawX, drawY,
                            patterns[patternIndex], palette, hFlip, vFlip, clip);
                });
    }

    private static void blitPattern(byte[] rgba,
                                    int imageWidth,
                                    int dstX,
                                    int dstY,
                                    Pattern pattern,
                                    Palette palette,
                                    boolean hFlip,
                                    boolean vFlip) {
        blitPattern(rgba, imageWidth, dstX, dstY, pattern, palette, hFlip, vFlip, (x, y) -> true);
    }

    private static void blitPattern(byte[] rgba,
                                    int imageWidth,
                                    int dstX,
                                    int dstY,
                                    Pattern pattern,
                                    Palette palette,
                                    boolean hFlip,
                                    boolean vFlip,
                                    PixelClip clip) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            int sourceY = vFlip ? Pattern.PATTERN_HEIGHT - 1 - y : y;
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                int sourceX = hFlip ? Pattern.PATTERN_WIDTH - 1 - x : x;
                int colorIndex = pattern.getPixel(sourceX, sourceY) & 0x0F;
                if (colorIndex == 0) {
                    continue;
                }
                int outX = dstX + x;
                int outY = dstY + y;
                if (outX < 0 || outY < 0 || outX >= imageWidth || outY >= rgba.length / 4 / imageWidth) {
                    continue;
                }
                if (clip != null && !clip.visible(outX, outY)) {
                    continue;
                }
                Palette.Color color = palette.getColor(colorIndex);
                int offset = ((outY * imageWidth) + outX) * 4;
                rgba[offset] = color.r;
                rgba[offset + 1] = color.g;
                rgba[offset + 2] = color.b;
                rgba[offset + 3] = (byte) 0xFF;
            }
        }
    }

    private static boolean sonic1PreviewSonicPixelVisible(int screenX, int screenY) {
        return screenY < S1_SONIC_MASK_Y;
    }
}
