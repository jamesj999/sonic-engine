package com.openggf.game;

import com.openggf.level.Palette;
import com.openggf.game.sonic3k.titlescreen.Sonic3kTitleScreenMappings;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestMasterTitleRomPreview {

    @Test
    void composeTilemapImage_rendersPaletteIndexedPatterns() {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);
        pattern.setPixel(7, 7, (byte) 2);
        Palette palette = palette(0x00_00_00, 0xFF_00_00, 0x00_FF_00);

        MasterTitleRomPreview.Image image = MasterTitleRomPreview.composeTilemapImage(
                new Pattern[] { pattern },
                new Palette[] { palette },
                new int[] { 0 },
                1,
                1);

        assertEquals(8, image.width());
        assertEquals(8, image.height());
        assertPixel(image, 0, 0, 0xFF, 0x00, 0x00, 0xFF);
        assertPixel(image, 7, 7, 0x00, 0xFF, 0x00, 0xFF);
        assertPixel(image, 1, 0, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void composeTilemapImage_appliesHorizontalAndVerticalFlips() {
        Pattern pattern = new Pattern();
        pattern.setPixel(7, 7, (byte) 1);
        Palette palette = palette(0x00_00_00, 0xFF_FF_FF);

        MasterTitleRomPreview.Image image = MasterTitleRomPreview.composeTilemapImage(
                new Pattern[] { pattern },
                new Palette[] { palette },
                new int[] { 0x1800 },
                1,
                1);

        assertPixel(image, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF);
        assertPixel(image, 7, 7, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void overlaySpriteFrame_drawsMappingPiecesOverExistingImage() {
        MasterTitleRomPreview.Image image = new MasterTitleRomPreview.Image(16, 16, new byte[16 * 16 * 4]);
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);
        pattern.setPixel(7, 7, (byte) 2);
        Palette palette = palette(0x00_00_00, 0xAA_00_00, 0x00_AA_00);
        SpriteMappingFrame frame = new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false)));

        MasterTitleRomPreview.overlaySpriteFrame(image, new Pattern[] { pattern }, new Palette[] { palette },
                frame, 4, 5, 0);

        assertPixel(image, 4, 5, 0xAA, 0x00, 0x00, 0xFF);
        assertPixel(image, 11, 12, 0x00, 0xAA, 0x00, 0xFF);
        assertPixel(image, 3, 5, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void overlaySpriteFrameClipped_hidesMaskedDestinationPixels() {
        MasterTitleRomPreview.Image image = new MasterTitleRomPreview.Image(16, 16, new byte[16 * 16 * 4]);
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);
        pattern.setPixel(0, 1, (byte) 1);
        Palette palette = palette(0x00_00_00, 0xAA_00_00);
        SpriteMappingFrame frame = new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false)));

        MasterTitleRomPreview.overlaySpriteFrameClipped(image, new Pattern[] { pattern }, new Palette[] { palette },
                frame, 4, 5, 0, (x, y) -> y < 6);

        assertPixel(image, 4, 5, 0xAA, 0x00, 0x00, 0xFF);
        assertPixel(image, 4, 6, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void scaleImageIntoNativeCanvas_usesAreaSamplingWhenDownscalingSmallText() throws Exception {
        MasterTitleRomPreview.Image source = new MasterTitleRomPreview.Image(2, 2, new byte[] {
                (byte) 0xFF, 0x00, 0x00, (byte) 0xFF,
                0x00, (byte) 0xFF, 0x00, (byte) 0xFF,
                0x00, 0x00, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        });

        MasterTitleRomPreview.Image scaled = invokeScaleHelper(source, 1, 1);

        assertPixel(scaled, 0, 0, 0x80, 0x80, 0x80, 0xFF);
    }

    @Test
    void loadFor_returnsEmptyWhenRomPathIsMissing() {
        assertTrue(MasterTitleRomPreview.loadFor(
                MasterTitleScreen.GameEntry.SONIC_2,
                Path.of("definitely-missing-rom-file.gen")).isEmpty());
    }

    @Test
    void hasVisiblePixels_reportsWhetherImageContainsNonTransparentData() {
        MasterTitleRomPreview.Image blank = new MasterTitleRomPreview.Image(1, 1, new byte[] { 0, 0, 0, 0 });
        MasterTitleRomPreview.Image visible = new MasterTitleRomPreview.Image(1, 1, new byte[] { 1, 2, 3, 4 });

        assertFalse(blank.hasVisiblePixels());
        assertTrue(visible.hasVisiblePixels());
    }

    @Test
    void previewSequence_clampsToLastFrameAfterTimelineCompletes() {
        MasterTitleRomPreview.Image first = new MasterTitleRomPreview.Image(1, 1, new byte[] { 1, 0, 0, 1 });
        MasterTitleRomPreview.Image second = new MasterTitleRomPreview.Image(1, 1, new byte[] { 2, 0, 0, 1 });
        MasterTitleRomPreview.PreviewSequence sequence =
                MasterTitleRomPreview.sequenceForTest(new MasterTitleRomPreview.Image[] { first, second });

        assertEquals(0, sequence.frameTokenAt(0));
        assertEquals(1, sequence.frameTokenAt(1));
        assertEquals(1, sequence.frameTokenAt(99));
        assertEquals(first, sequence.imageAt(0));
        assertEquals(second, sequence.imageAt(99));
    }

    @Test
    void sonic2PreviewTimelineStartsWithCharacterEntryAndSettlesToFinalFrame() {
        assertEquals(5, MasterTitleRomPreview.sonic2PreviewSonicFrameAt(0));
        assertEquals(80 + MasterTitleRomPreview.sonic2PreviewCharacterYOffset(),
                MasterTitleRomPreview.sonic2PreviewSonicYAt(0));

        assertEquals(5, MasterTitleRomPreview.sonic2PreviewSonicFrameAt(4));
        assertEquals(64 + MasterTitleRomPreview.sonic2PreviewCharacterYOffset(),
                MasterTitleRomPreview.sonic2PreviewSonicYAt(4));

        assertEquals(0x12, MasterTitleRomPreview.sonic2PreviewSonicFrameAt(160));
        assertEquals(24 + MasterTitleRomPreview.sonic2PreviewCharacterYOffset(),
                MasterTitleRomPreview.sonic2PreviewSonicYAt(160));
    }

    @Test
    void sonic1PreviewSettledLoopKeepsAlternatingFinalFrames() throws Exception {
        int token = invokeIntHelper("sonic1PreviewTokenAt", 152);

        assertEquals(6, invokeIntHelper("sonic1PreviewSonicFrameAt", token));
    }

    @Test
    void sonic3kPreviewStartsAtFinalSceneFingerWag() throws Exception {
        assertEquals(4, invokeIntHelper("s3kPreviewFingerFrameAt", 0));
        assertEquals(4, invokeIntHelper("s3kPreviewFingerFrameAt", 35));
        assertEquals(0, invokeIntHelper("s3kPreviewFingerFrameAt", 36));
        assertEquals(4, invokeIntHelper("s3kPreviewFingerFrameAt", 42));
        assertEquals(1, invokeIntHelper("s3kPreviewFingerFrameAt", 48));
    }

    @Test
    void sonic3kPreviewFingerOverlayIsLoweredToMatchFinalTitlePose() {
        assertEquals(76, MasterTitleRomPreview.sonic3kPreviewFingerY());
    }

    @Test
    void sonic3kPreviewIncludesTitleWinkAnimation() throws Exception {
        assertEquals(2, invokeIntHelper("s3kPreviewWinkFrameAt", 0));
        assertEquals(3, invokeIntHelper("s3kPreviewWinkFrameAt", 2));
        assertEquals(2, invokeIntHelper("s3kPreviewWinkFrameAt", 10));
        assertEquals(4, invokeIntHelper("s3kPreviewWinkFrameAt", 16));
        assertEquals(2, invokeIntHelper("s3kPreviewWinkFrameAt", 168));
    }

    @Test
    void sonic3kPreviewWinkOverlayIsLoweredAgainstScaledFinalTitlePose() {
        assertEquals(56, MasterTitleRomPreview.sonic3kPreviewWinkY());
    }

    @Test
    void sonic3kPreviewUsesNativeCanvasWithoutResampling() {
        assertEquals(320, MasterTitleRomPreview.sonic3kPreviewScaledWidth());
        assertEquals(224, MasterTitleRomPreview.sonic3kPreviewScaledHeight());
    }

    @Test
    void sonic3kPreviewUsesSettledPlaneAVerticalScroll() {
        assertEquals(16, MasterTitleRomPreview.sonic3kPreviewVScroll());
        assertEquals(76, MasterTitleRomPreview.sonic3kPreviewFingerY());
        assertEquals(56, MasterTitleRomPreview.sonic3kPreviewWinkY());
    }

    @Test
    void sonic1PreviewUsesSettledIdleLoopFrame() {
        assertEquals(6, MasterTitleRomPreview.sonic1PreviewSonicFrameIndex());
    }

    @Test
    void sonic1PreviewUsesNativeScreenAndRuntimePlaneAOffset() {
        assertEquals(320, MasterTitleRomPreview.sonic1PreviewWidth());
        assertEquals(224, MasterTitleRomPreview.sonic1PreviewHeight());
        assertEquals(32, MasterTitleRomPreview.sonic1PlaneAX());
        assertEquals(32, MasterTitleRomPreview.sonic1PlaneAY());
    }

    @Test
    void sonic2PreviewUsesCurvedLowerLogoOcclusion() {
        assertEquals(104, MasterTitleRomPreview.sonic2LogoOcclusionStartPixel(159));
        assertEquals(120, MasterTitleRomPreview.sonic2LogoOcclusionStartPixel(0));
        assertEquals(120, MasterTitleRomPreview.sonic2LogoOcclusionStartPixel(319));
    }

    @Test
    void sonic2PreviewBodyClipFollowsCurvedLogoBoundary() {
        assertTrue(MasterTitleRomPreview.sonic2PreviewBodyPixelVisible(159, 103));
        assertFalse(MasterTitleRomPreview.sonic2PreviewBodyPixelVisible(159, 104));
        assertTrue(MasterTitleRomPreview.sonic2PreviewBodyPixelVisible(0, 119));
        assertFalse(MasterTitleRomPreview.sonic2PreviewBodyPixelVisible(0, 120));
    }

    @Test
    void sonic2PreviewOffsetsCharactersDownFromTitleOrigin() {
        assertEquals(8, MasterTitleRomPreview.sonic2PreviewCharacterYOffset());
    }

    @Test
    void sonic2PreviewDrawsHandsBehindLogoOcclusion() {
        assertTrue(MasterTitleRomPreview.sonic2PreviewDrawsHandsBehindLogoOcclusion());
    }

    @Test
    void sonic2PreviewDoesNotHideHandsBehindFullLogo() {
        assertFalse(MasterTitleRomPreview.sonic2PreviewDrawsHandsBehindFullLogoText());
    }

    @Test
    void sonic2PreviewPreservesOpaqueEmblemInteriorBehindCharacters() {
        MasterTitleScreen.GameEntry entry = MasterTitleScreen.GameEntry.SONIC_2;
        Path path = Path.of(MasterTitleScreen.expectedRomFilename(entry));
        assumeTrue(path.toFile().isFile(), "ROM not present: " + path);

        MasterTitleRomPreview.Image image = MasterTitleRomPreview.loadFor(entry, path).orElseThrow();

        int opaqueGreenPixels = 0;
        for (int y = 32; y < 104; y++) {
            for (int x = 72; x < 248; x++) {
                int offset = ((y * image.width()) + x) * 4;
                byte[] rgba = image.rgba();
                if ((rgba[offset] & 0xFF) == 0x92
                        && (rgba[offset + 1] & 0xFF) == 0xFF
                        && (rgba[offset + 2] & 0xFF) == 0x00
                        && (rgba[offset + 3] & 0xFF) == 0xFF) {
                    opaqueGreenPixels++;
                }
            }
        }
        assertTrue(opaqueGreenPixels > 500,
                "the emblem interior should remain opaque behind Sonic and Tails, got " + opaqueGreenPixels);
    }

    @Test
    void sonic3kPreviewOmitsInteractiveMenuSelection() {
        assertFalse(MasterTitleRomPreview.sonic3kPreviewDrawsMenuSelection());
    }

    @Test
    void sonic3kPreviewUsesSettledTitleBannerPosition() {
        assertEquals(84, MasterTitleRomPreview.sonic3kPreviewBannerY());
    }

    @Test
    void sonic3kPreviewIncludesCopyrightInMasterComposition() {
        assertTrue(MasterTitleRomPreview.sonic3kPreviewDrawsCopyright());
    }

    @Test
    void sonic3kPreviewComposesTrademarkAndCopyrightPixels() throws Exception {
        Pattern[] patterns = new Pattern[0x800];
        Pattern ink = new Pattern();
        ink.setPixel(0, 0, (byte) 1);
        var trademark = Sonic3kTitleScreenMappings.createBannerFrames().get(1).pieces().get(0);
        var copyright = Sonic3kTitleScreenMappings.createCopyrightFrame().get(0).pieces().get(0);
        patterns[trademark.tileIndex()] = ink;
        patterns[copyright.tileIndex()] = ink;
        Palette[] palettes = { palette(0), palette(0), palette(0), palette(0, 0xFF_FF_FF) };
        Method compose = MasterTitleRomPreview.class.getDeclaredMethod("composeSonic3kFinalPreview",
                MasterTitleRomPreview.Image.class, Pattern[].class, Palette[].class, int.class, int.class);
        compose.setAccessible(true);
        MasterTitleRomPreview.Image blank = new MasterTitleRomPreview.Image(320, 224, new byte[320 * 224 * 4]);

        MasterTitleRomPreview.Image image = (MasterTitleRomPreview.Image) compose.invoke(
                null, blank, patterns, palettes, -1, -1);

        assertPixel(image, 252, 104, 255, 255, 255, 255);
        // Copyright fits between the game selector and navigation hints without covering the logo.
        assertPixel(image, 216, 200, 255, 255, 255, 255);
    }

    @Test
    void sonic2PreviewStampsCopyrightTextIntoLogoMap() {
        int[] logo = new int[40 * 28];
        int[] words = new int[11];
        for (int i = 0; i < words.length; i++) {
            words[i] = 0x680 + i;
        }
        int[] stamped = MasterTitleRomPreview.withSonic2CopyrightText(logo, words);
        assertEquals(0, logo[26 * 40 + 28], "input map is not mutated");
        assertEquals(0x680, stamped[26 * 40 + 28]);
        assertEquals(0x680 + 10, stamped[26 * 40 + 38]);
        assertEquals(0, stamped[26 * 40 + 27]);
    }

    @Test
    void sonic2PreviewRendersCopyrightRow() {
        MasterTitleScreen.GameEntry entry = MasterTitleScreen.GameEntry.SONIC_2;
        Path path = Path.of(MasterTitleScreen.expectedRomFilename(entry));
        assumeTrue(path.toFile().isFile(), "ROM not present: " + path);

        MasterTitleRomPreview.Image image = MasterTitleRomPreview.loadFor(entry, path).orElseThrow();

        // Copyright line "@ 1992 SEGA" sits on tile row 26, columns 28-38 (x 224-311, y 208-215).
        int visible = 0;
        for (int y = 208; y < 216; y++) {
            for (int x = 224; x < 312; x++) {
                if ((image.rgba()[(y * image.width() + x) * 4 + 3] & 0xFF) != 0) {
                    visible++;
                }
            }
        }
        assertTrue(visible > 40, "copyright glyph pixels should render, got " + visible);
        int columnsLeft = 0;
        for (int y = 208; y < 216; y++) {
            for (int x = 0; x < 224; x++) {
                if ((image.rgba()[(y * image.width() + x) * 4 + 3] & 0xFF) != 0) {
                    columnsLeft++;
                }
            }
        }
        assertEquals(0, columnsLeft, "nothing else is drawn on the copyright row left of column 28");
    }

    @Test
    void loadFor_decodesRealRomPreviewWhenSuppliedRomExists() {
        for (MasterTitleScreen.GameEntry entry : MasterTitleScreen.GameEntry.values()) {
            Path path = Path.of(MasterTitleScreen.expectedRomFilename(entry));
            assumeTrue(path.toFile().isFile(), "ROM not present: " + path);

            Optional<MasterTitleRomPreview.Image> image = MasterTitleRomPreview.loadFor(entry, path);

            assertTrue(image.isPresent(), "preview should decode for " + entry.gameId);
            assertTrue(image.orElseThrow().hasVisiblePixels(), "preview should contain visible pixels");
        }
    }

    private static Palette palette(int... colors) {
        Palette palette = new Palette();
        for (int i = 0; i < colors.length; i++) {
            int rgb = colors[i];
            palette.setColor(i, new Palette.Color(
                    (byte) ((rgb >> 16) & 0xFF),
                    (byte) ((rgb >> 8) & 0xFF),
                    (byte) (rgb & 0xFF)));
        }
        return palette;
    }

    private static int invokeIntHelper(String methodName, int value) throws Exception {
        Method method = MasterTitleRomPreview.class.getDeclaredMethod(methodName, int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, value);
    }

    private static MasterTitleRomPreview.Image invokeScaleHelper(MasterTitleRomPreview.Image source,
                                                                 int scaledWidth,
                                                                 int scaledHeight) throws Exception {
        Method method = MasterTitleRomPreview.class.getDeclaredMethod(
                "scaleImageIntoNativeCanvas", MasterTitleRomPreview.Image.class, int.class, int.class);
        method.setAccessible(true);
        return (MasterTitleRomPreview.Image) method.invoke(null, source, scaledWidth, scaledHeight);
    }

    private static void assertPixel(MasterTitleRomPreview.Image image,
                                    int x,
                                    int y,
                                    int r,
                                    int g,
                                    int b,
                                    int a) {
        int offset = ((y * image.width()) + x) * 4;
        byte[] rgba = image.rgba();
        assertEquals(r, rgba[offset] & 0xFF);
        assertEquals(g, rgba[offset + 1] & 0xFF);
        assertEquals(b, rgba[offset + 2] & 0xFF);
        assertEquals(a, rgba[offset + 3] & 0xFF);
    }
}
