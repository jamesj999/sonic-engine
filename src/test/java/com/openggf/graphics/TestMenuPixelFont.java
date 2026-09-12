package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMenuPixelFont {
    @Test
    void compactMetricsAreNativeAtEveryFractionalScaleAndFullMetricsAreIntegerScaled() {
        MenuPixelFont font = new MenuPixelFont();
        for (float scale : new float[] {.4f, .5f, .6f, .65f, .8f, .99f}) {
            assertEquals(60, font.measureWidth("OpenGGF123", scale));
            assertEquals(6, MenuPixelFont.glyphAdvance(scale));
            assertEquals(8, MenuPixelFont.glyphLineHeight(scale));
        }
        assertEquals(18, font.measureWidth("AB", 1.3f));
        assertEquals(36, font.measureWidth("AB", 1.6f));
        assertEquals(20, MenuPixelFont.glyphLineHeight(1.6f));
    }

    @Test
    void compactQuadsUseExactSixByEightCellsAndIntegralOrigins() throws Exception {
        Fixture fixture = new Fixture();
        fixture.font.drawText("A B", 9.4f, 12.6f, .65f, 1, .7f, .2f, 1);
        assertEquals(1, fixture.renderer.draws.size());
        Draw draw = fixture.renderer.draws.getFirst();
        assertEquals(11, draw.texture());
        assertEquals(2, draw.quads());
        assertBounds(draw.vertices(), 0, 9, 211);
        assertBounds(draw.vertices(), TexturedQuadRenderer.COLORED_QUAD_FLOATS, 21, 211);
    }

    @Test
    void mixedFontAndSolidBatchesKeepSubmissionOrderAndColors() throws Exception {
        Fixture fixture = new Fixture();
        MenuPixelFont font = fixture.font;
        font.beginMegaBatch();
        font.drawText("A", 0, 0, 1f, 1, 0, 0, 1);
        font.drawText("B", 9, 0, 1f, 0, 1, 0, 1);
        assertTrue(fixture.renderer.draws.isEmpty());
        font.drawText("c", 0, 12, .6f, 0, 0, 1, 1);
        font.drawText("d", 6, 12, .8f, 1, 1, 1, .5f);
        font.fillRect(0, 20, 50, 2, 1, 0, 0, 1);
        font.drawText("e", 0, 23, .5f, 1, 1, 1, 1);
        font.drawText("A", 0, 32, 1.7f, 1, 1, 1, 1);
        font.endMegaBatch();
        assertEquals(List.of(7, 11, 13, 11, 7),
                fixture.renderer.draws.stream().map(Draw::texture).toList());
        assertEquals(List.of(2, 2, 1, 1, 1),
                fixture.renderer.draws.stream().map(Draw::quads).toList());
        assertEquals(1, fixture.renderer.draws.get(0).vertices()[4]);
        assertEquals(0, fixture.renderer.draws.get(0).vertices()[5]);
        assertEquals(0, fixture.renderer.draws.get(0).vertices()[TexturedQuadRenderer.COLORED_QUAD_FLOATS + 4]);
        assertEquals(1, fixture.renderer.draws.get(0).vertices()[TexturedQuadRenderer.COLORED_QUAD_FLOATS + 5]);
        int draws = fixture.renderer.draws.size();
        font.endMegaBatch();
        assertEquals(draws, fixture.renderer.draws.size(), "Completed batch is not submitted twice");
    }

    @Test
    void allPrintableAsciiHasArtworkAndAtlasCellsKeepTransparentColumnSpacing() {
        byte[] atlas = MenuGlyphs.atlasRgba();
        for (char character = 33; character <= 126; character++) {
            int ink = 0;
            for (int y = 0; y < MenuGlyphs.HEIGHT; y++) ink |= MenuGlyphs.row(character, y);
            assertNotEquals(0, ink, "Missing glyph " + character);
        }
        for (int y = 0; y < MenuGlyphs.HEIGHT; y++) assertEquals(0, MenuGlyphs.row(' ', y));
        for (int index = 0; index < 95; index++) {
            int originX = index % MenuGlyphs.COLUMNS * 6;
            int originY = index / MenuGlyphs.COLUMNS * 8;
            for (int y = 0; y < 8; y++) assertEquals(0, alpha(atlas, originX + 5, originY + y));
            char character = (char) (index + 32);
            for (int y = 0; y < MenuGlyphs.HEIGHT; y++) {
                for (int x = 0; x < 5; x++) {
                    int expected = (MenuGlyphs.row(character, y) & (1 << (4 - x))) == 0 ? 0 : 255;
                    assertEquals(expected, alpha(atlas, originX + x, originY + y),
                            "Atlas must include every authored row for " + character);
                }
            }
        }
        assertEquals(255, alpha(atlas, ('A' - 32) % 16 * 6 + 1, ('A' - 32) / 16 * 8));
    }

    @Test
    void lowercaseDescendersReachBelowTheSharedBaselineWithinTheSameCell() {
        for (char character : "gjpqy".toCharArray()) {
            assertNotEquals(0, MenuGlyphs.row(character, 7), "Missing descender for " + character);
        }
        for (char character : "gpqy".toCharArray()) {
            assertEquals(0, MenuGlyphs.row(character, 0));
            assertEquals(0, MenuGlyphs.row(character, 1));
            assertNotEquals(0, MenuGlyphs.row(character, 2), "Lowercase bodies share the x-height");
        }
        assertNotEquals(0, MenuGlyphs.row('j', 0), "Keep the dot above the j stem");
        for (char character : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefhiklmnorstuvwxz0123456789".toCharArray()) {
            assertEquals(0, MenuGlyphs.row(character, 7), "Non-descenders keep their baseline: " + character);
        }
        assertEquals(6, MenuGlyphs.ADVANCE);
        assertEquals(8, MenuGlyphs.HEIGHT);
    }

    @Test
    void pathsAndAsciiPunctuationRenderEveryNonSpaceCharacter() throws Exception {
        Fixture fixture = new Fixture();
        String path = "C:\\ROMs\\Sonic (REV01).gen /roms/[a]_{b}-+!@#$%^&*='\";:,.?<>|`~";
        fixture.font.drawText(path, 0, 0, .6f, 1, 1, 1, 1);
        assertEquals(path.chars().filter(c -> c != ' ').count(), fixture.renderer.draws.getFirst().quads());
        assertEquals('\'', MenuGlyphs.normalized('\u2019'));
        assertEquals('"', MenuGlyphs.normalized('\u201c'));
        assertNotEquals(MenuGlyphs.row('/', 0), MenuGlyphs.row('\\', 0));
    }

    private static int alpha(byte[] atlas, int x, int topY) {
        return atlas[((MenuGlyphs.ATLAS_HEIGHT - 1 - topY) * MenuGlyphs.ATLAS_WIDTH + x) * 4 + 3] & 255;
    }

    private static void assertBounds(float[] vertices, int offset, int minX, int topY) {
        // Colored vertex layout: x,y,u,v,r,g,b,a for each of six vertices.
        assertEquals(minX, vertices[offset]);
        assertEquals(topY, vertices[offset + 1]);
        assertEquals(topY - 8, vertices[offset + 9]);
        assertEquals(minX + 6, vertices[offset + 16]);
        for (int i = 0; i < 6; i++) {
            assertEquals(Math.round(vertices[offset + i * 8]), vertices[offset + i * 8]);
            assertEquals(Math.round(vertices[offset + i * 8 + 1]), vertices[offset + i * 8 + 1]);
        }
    }

    private record Draw(int texture, int quads, float[] vertices) { }

    private static final class RecordingRenderer extends TexturedQuadRenderer {
        private final List<Draw> draws = new ArrayList<>();
        @Override
        public void drawColoredTextureBatch(int texture, float[] vertices, int quads) {
            draws.add(new Draw(texture, quads, Arrays.copyOf(vertices, quads * COLORED_QUAD_FLOATS)));
        }
        @Override
        public void drawTexture(int texture, float x, float y, float w, float h,
                                float r, float g, float b, float a) {
            draws.add(new Draw(texture, 1, new float[] {x, y, w, h, r, g, b, a}));
        }
    }

    private static final class Fixture {
        final MenuPixelFont font = new MenuPixelFont();
        final RecordingRenderer renderer = new RecordingRenderer();
        Fixture() throws Exception {
            set(MenuPixelFont.class, font, "menuRenderer", renderer);
            set(MenuPixelFont.class, font, "compactTexture", 11);
            set(MenuPixelFont.class, font, "solidTexture", 13);
            set(PixelFont.class, font, "renderer", renderer);
            set(PixelFont.class, font, "textureId", 7);
            set(PixelFont.class, font, "textureWidth", 416);
            set(PixelFont.class, font, "textureHeight", 80);
            boolean[] known = (boolean[]) field(PixelFont.class, font, "charKnown");
            int[] columns = (int[]) field(PixelFont.class, font, "charToCol");
            known['A'] = true;
            known['B'] = true;
            columns['B'] = 1;
        }
    }

    private static Object field(Class<?> type, Object object, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
