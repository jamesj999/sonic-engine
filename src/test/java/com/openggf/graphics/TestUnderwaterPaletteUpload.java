package com.openggf.graphics;

import com.openggf.game.GameId;
import com.openggf.level.Palette;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TestUnderwaterPaletteUpload {
    private GraphicsManager graphics;
    private RecordingUnderwaterUploadOps stagingUploads;

    @AfterEach
    void resetState() {
        if (graphics != null) {
            graphics.clearPaletteTextures();
        }
        RenderContext.reset();
    }

    @Test
    void unchangedContentReusesPreparedStaging() throws Exception {
        graphics = new GraphicsManager();
        Palette[] underwater = fourRows();
        Palette normal = paletteWithColor(1, 200, 100, 50);

        assertTrue(cacheUnderwaterPaletteTexture(underwater, normal));
        ByteBuffer firstBuffer = uploadBuffer(graphics);
        assertFalse(cacheUnderwaterPaletteTexture(copyRows(underwater), normal.deepCopy()),
                "a second shader consumer must reuse the frame's prepared upload");
        assertSame(firstBuffer, uploadBuffer(graphics));
    }

    @Test
    void realCacheBoundaryUploadsOnceForBothConsumersAndInvalidatesContributingContent() {
        graphics = new GraphicsManager();
        graphics.initHeadless();
        RecordingUnderwaterUploadOps uploads = new RecordingUnderwaterUploadOps();
        graphics.setUnderwaterPaletteUploadOps(uploads);

        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        Palette donorRow = paletteWithColor(1, 180, 80, 40);
        donor.setPalette(0, donorRow);
        Palette[] underwater = fourRows();
        underwater[0] = paletteWithColor(1, 100, 50, 25);
        Palette normal = paletteWithColor(1, 200, 100, 50);

        Integer scalarShaderTexture = graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        Integer instancedShaderTexture = graphics.cacheUnderwaterPaletteTexture(underwater, normal);

        assertEquals(scalarShaderTexture, instancedShaderTexture,
                "both shader configuration paths must consume the same texture");
        assertEquals(scalarShaderTexture, uploads.lastTextureId);
        assertEquals(1, uploads.createCount);
        assertEquals(1, uploads.uploadCount, "unchanged second consumer must not upload again");
        assertEquals(16 * RenderContext.getTotalPaletteLines() * 4, uploads.lastUploadBytes);

        graphics.setDisplayColorProfile(com.openggf.graphics.color.DisplayColorProfile.MD_ANALOG);
        assertSingleNewUpload(uploads, () -> graphics.cacheUnderwaterPaletteTexture(underwater, normal));

        normal.getColor(1).r++;
        assertSingleNewUpload(uploads, () -> graphics.cacheUnderwaterPaletteTexture(underwater, normal));

        underwater[0].getColor(1).b++;
        assertSingleNewUpload(uploads, () -> graphics.cacheUnderwaterPaletteTexture(underwater, normal));

        donorRow.getColor(1).g++;
        assertSingleNewUpload(uploads, () -> graphics.cacheUnderwaterPaletteTexture(underwater, normal));
        assertEquals(1, uploads.createCount, "content changes must update the existing texture");
    }

    @Test
    void structurallyDifferentRowsCannotShareAnUploadKey() {
        graphics = new GraphicsManager();
        graphics.initHeadless();
        RecordingUnderwaterUploadOps uploads = new RecordingUnderwaterUploadOps();
        graphics.setUnderwaterPaletteUploadOps(uploads);

        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        Palette x = paletteWithColor(1, 80, 40, 20);
        Palette y = paletteWithColor(1, 20, 40, 80);
        Palette normal = paletteWithColor(1, 100, 100, 100);

        // Rows 4-6: derived X, absent, direct Y.
        Palette[] firstLayout = new Palette[RenderContext.getTotalPaletteLines()];
        firstLayout[0] = normal.deepCopy();
        firstLayout[6] = y.deepCopy();
        donor.setPalette(0, x.deepCopy());
        graphics.cacheUnderwaterPaletteTexture(firstLayout, normal);
        byte[] firstUpload = uploads.lastUpload.clone();

        // Rows 4-6: absent, direct X, derived Y. The old variable-length key
        // serialized both layouts identically despite their different output rows.
        Palette[] secondLayout = new Palette[RenderContext.getTotalPaletteLines()];
        secondLayout[0] = normal.deepCopy();
        secondLayout[5] = x.deepCopy();
        donor.setPalette(0, null);
        donor.setPalette(2, y.deepCopy());
        graphics.cacheUnderwaterPaletteTexture(secondLayout, normal);

        assertEquals(2, uploads.uploadCount, "row case framing must force a new upload");
        assertFalse(java.util.Arrays.equals(firstUpload, uploads.lastUpload),
                "the two row layouts must produce different whole-texture bytes");
    }

    @Test
    void donorRowCacheRecomputesOnlyRowsWhoseContributingContentChanges() {
        graphics = new GraphicsManager();
        graphics.initHeadless();
        RecordingUnderwaterUploadOps uploads = new RecordingUnderwaterUploadOps();
        graphics.setUnderwaterPaletteUploadOps(uploads);

        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        Palette donor0 = paletteWithColor(1, 180, 80, 40);
        Palette donor1 = paletteWithColor(1, 40, 80, 180);
        donor.setPalette(0, donor0);
        donor.setPalette(1, donor1);
        Palette normal = paletteWithColor(1, 200, 100, 50);
        Palette[] underwater = new Palette[RenderContext.getTotalPaletteLines()];
        underwater[0] = paletteWithColor(1, 100, 50, 25);

        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(2, graphics.getUnderwaterDerivedRowRecomputeCount());

        donor.setPalette(0, donor0.deepCopy());
        donor.setPalette(1, donor1.deepCopy());
        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(2, graphics.getUnderwaterDerivedRowRecomputeCount(),
                "equal replacement objects must reuse cached derived RGBA");

        donor.getPalette(0).getColor(1).r++;
        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(3, graphics.getUnderwaterDerivedRowRecomputeCount(),
                "only the mutated donor row should be re-derived");

        normal.getColor(1).g++;
        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(5, graphics.getUnderwaterDerivedRowRecomputeCount());

        underwater[0].getColor(1).b++;
        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(7, graphics.getUnderwaterDerivedRowRecomputeCount());

        graphics.setDisplayColorProfile(com.openggf.graphics.color.DisplayColorProfile.MD_ANALOG);
        graphics.cacheUnderwaterPaletteTexture(underwater, normal);
        assertEquals(9, graphics.getUnderwaterDerivedRowRecomputeCount());
    }

    private static void assertSingleNewUpload(RecordingUnderwaterUploadOps uploads, Runnable cacheCall) {
        int before = uploads.uploadCount;
        cacheCall.run();
        cacheCall.run();
        assertEquals(before + 1, uploads.uploadCount);
    }

    @Test
    void inPlacePaletteMutationForcesAnotherUploadAndPreservesRgbaRules() throws Exception {
        graphics = new GraphicsManager();
        Palette[] underwater = fourRows();
        underwater[0].setColor(1, new Palette.Color((byte) 146, (byte) 73, (byte) 219));

        assertTrue(cacheUnderwaterPaletteTexture(underwater, new Palette()));
        ByteBuffer staged = uploadBuffer(graphics);
        assertArrayEquals(new int[] {0, 0, 0, 0}, rgbaAt(staged, 0));
        assertArrayEquals(new int[] {146, 73, 219, 255}, rgbaAt(staged, 1));

        underwater[0].getColor(1).g = (byte) 74;
        assertTrue(cacheUnderwaterPaletteTexture(underwater, new Palette()),
                "in-place palette cycling/fading must invalidate the content gate");
        assertArrayEquals(new int[] {146, 74, 219, 255}, rgbaAt(uploadBuffer(graphics), 1));
    }

    @Test
    void nativeStagingBufferIsReusedAndOnlyGrows() throws Exception {
        graphics = new GraphicsManager();
        Palette[] baseRows = fourRows();
        assertTrue(cacheUnderwaterPaletteTexture(baseRows, new Palette()));
        ByteBuffer baseBuffer = uploadBuffer(graphics);
        int baseCapacity = baseBuffer.capacity();

        baseRows[0].getColor(2).b = 1;
        assertTrue(cacheUnderwaterPaletteTexture(baseRows, new Palette()));
        assertSame(baseBuffer, uploadBuffer(graphics), "same-sized uploads must reuse native staging");

        RenderContext.getOrCreateDonor(GameId.S2);
        assertTrue(cacheUnderwaterPaletteTexture(baseRows, new Palette()));
        ByteBuffer grown = uploadBuffer(graphics);
        assertTrue(grown.capacity() > baseCapacity);

        RenderContext.reset();
        baseRows[0].getColor(2).b = 2;
        assertTrue(cacheUnderwaterPaletteTexture(baseRows, new Palette()));
        assertEquals(grown.capacity(), uploadBuffer(graphics).capacity(),
                "staging capacity must not shrink when donor rows disappear");
    }

    @Test
    void clearingPaletteTexturesReleasesStagingAndAllowsReinitialization() throws Exception {
        graphics = new GraphicsManager();
        assertTrue(cacheUnderwaterPaletteTexture(fourRows(), new Palette()));
        assertNotNull(uploadBuffer(graphics));

        graphics.clearPaletteTextures();
        assertNull(uploadBuffer(graphics));

        assertTrue(cacheUnderwaterPaletteTexture(fourRows(), new Palette()));
        assertNotNull(uploadBuffer(graphics));
        graphics.clearPaletteTextures();
    }

    @Test
    void headlessOwnerCleanupReleasesNativeStagingAndContentCache() throws Exception {
        graphics = new GraphicsManager();
        graphics.initHeadless();
        assertTrue(cacheUnderwaterPaletteTexture(fourRows(), new Palette()));
        assertNotNull(uploadBuffer(graphics));
        assertNotNull(fieldValue(graphics, "underwaterPaletteContentKey"));

        graphics.cleanup();

        assertNull(uploadBuffer(graphics), "owner cleanup must release native staging without a GL context");
        assertNull(fieldValue(graphics, "underwaterPaletteContentKey"),
                "owner cleanup must invalidate the corresponding content cache");
        assertFalse((Boolean) fieldValue(graphics, "underwaterPaletteContentKeyValid"));
    }

    private static Palette[] fourRows() {
        return new Palette[] {new Palette(), new Palette(), new Palette(), new Palette()};
    }

    private boolean cacheUnderwaterPaletteTexture(Palette[] palettes, Palette normalLine0) {
        if (stagingUploads == null) {
            graphics.initHeadless();
            stagingUploads = new RecordingUnderwaterUploadOps();
            graphics.setUnderwaterPaletteUploadOps(stagingUploads);
        }
        int uploadCount = stagingUploads.uploadCount;
        graphics.cacheUnderwaterPaletteTexture(palettes, normalLine0);
        return stagingUploads.uploadCount > uploadCount;
    }

    private static Palette[] copyRows(Palette[] source) {
        Palette[] copy = new Palette[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i].deepCopy();
        return copy;
    }

    private static Palette paletteWithColor(int index, int r, int g, int b) {
        Palette palette = new Palette();
        palette.setColor(index, new Palette.Color((byte) r, (byte) g, (byte) b));
        return palette;
    }

    private static ByteBuffer uploadBuffer(GraphicsManager graphics) throws Exception {
        return (ByteBuffer) fieldValue(graphics, "underwaterPaletteUploadBuffer");
    }

    private static Object fieldValue(GraphicsManager graphics, String name) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(graphics);
    }

    private static int[] rgbaAt(ByteBuffer buffer, int colorIndex) {
        int offset = colorIndex * 4;
        return new int[] {
                Byte.toUnsignedInt(buffer.get(offset)),
                Byte.toUnsignedInt(buffer.get(offset + 1)),
                Byte.toUnsignedInt(buffer.get(offset + 2)),
                Byte.toUnsignedInt(buffer.get(offset + 3))
        };
    }

    private static final class RecordingUnderwaterUploadOps implements GraphicsManager.UnderwaterPaletteUploadOps {
        private int createCount;
        private int uploadCount;
        private int lastTextureId;
        private int lastUploadBytes;
        private byte[] lastUpload;

        @Override
        public int createTexture() {
            createCount++;
            return 73;
        }

        @Override
        public void configureTexture(int textureId) {
            lastTextureId = textureId;
        }

        @Override
        public void uploadTexture(int textureId, int totalLines, ByteBuffer rgbaBytes) {
            uploadCount++;
            lastTextureId = textureId;
            lastUploadBytes = rgbaBytes.remaining();
            lastUpload = new byte[lastUploadBytes];
            rgbaBytes.duplicate().get(lastUpload);
            assertEquals(16 * totalLines * 4, lastUploadBytes);
        }
    }
}
