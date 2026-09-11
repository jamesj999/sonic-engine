package com.openggf.graphics;

import com.openggf.graphics.PatternAtlas.Entry;
import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the dirty-rect pattern atlas upload path: small batch changes upload
 * individual tiles under one bind, larger changes upload the dirty bounding
 * rectangle, and the bytes pushed through the upload sink stay proportional to
 * the actual change instead of the full 1 MB page.
 *
 * Runs headless: the package-private {@link PatternAtlas.AtlasUploadSink} seam
 * replaces the GL calls with a recording sink.
 */
public class TestPatternAtlasDirtyUploads {

    private static final int TILE = PatternAtlas.TILE_SIZE;
    private static final int TILE_BYTES = TILE * TILE;

    /** Recording sink: accounts rect count + byte volume and captures uploaded pixels. */
    static final class RecordingSink implements PatternAtlas.AtlasUploadSink {
        record Upload(int x, int y, int width, int height, int srcStride, byte[] pixels) {}

        final List<Upload> uploads = new ArrayList<>();
        int begins;
        int ends;
        int boundTexture;
        long bytesUploaded;

        @Override
        public void begin(int textureId) {
            assertEquals(0, boundTexture, "begin() while a texture is already bound");
            assertNotEquals(0, textureId, "must not bind texture id 0 for uploads");
            boundTexture = textureId;
            begins++;
        }

        @Override
        public void upload(byte[] src, int srcOffset, int srcStride, int x, int y,
                int width, int height) {
            assertNotEquals(0, boundTexture, "upload() without a bound texture");
            byte[] pixels = new byte[width * height];
            for (int row = 0; row < height; row++) {
                System.arraycopy(src, srcOffset + row * srcStride, pixels, row * width, width);
            }
            uploads.add(new Upload(x, y, width, height, srcStride, pixels));
            bytesUploaded += (long) width * height;
        }

        @Override
        public void end() {
            assertNotEquals(0, boundTexture, "end() without a bound texture");
            boundTexture = 0;
            ends++;
        }

        void reset() {
            uploads.clear();
            begins = 0;
            ends = 0;
            bytesUploaded = 0;
        }
    }

    private static Pattern patternFor(int seed) {
        Pattern p = new Pattern();
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                p.setPixel(x, y, (byte) ((seed + x + y * TILE) & 0x7F));
            }
        }
        return p;
    }

    /** Mirrors a pattern write into a test-side expected page image. */
    private static void writeExpected(byte[] expectedPage, int pageWidth, Entry e, Pattern p) {
        byte[] tile = new byte[TILE_BYTES];
        p.copyInto(tile, 0);
        for (int row = 0; row < TILE; row++) {
            System.arraycopy(tile, row * TILE, expectedPage,
                    (e.tileY() * TILE + row) * pageWidth + e.tileX() * TILE, TILE);
        }
    }

    private static void assertUploadMatchesExpected(byte[] expectedPage, int pageWidth,
            RecordingSink.Upload u) {
        for (int row = 0; row < u.height(); row++) {
            for (int col = 0; col < u.width(); col++) {
                assertEquals(expectedPage[(u.y() + row) * pageWidth + u.x() + col],
                        u.pixels()[row * u.width() + col],
                        "uploaded pixel mismatch at (" + (u.x() + col) + "," + (u.y() + row) + ")");
            }
        }
    }

    private record Fixture(PatternAtlas atlas, RecordingSink sink, Entry[] entries,
            Pattern[] patterns, byte[] expectedPage, int pageWidth) {

        void writePattern(int index) {
            atlas.uploadPattern(patterns[index], entries[index]);
            writeExpected(expectedPage, pageWidth, entries[index], patterns[index]);
        }
    }

    private static Fixture newFixture(int atlasSize, int entryCount) {
        PatternAtlas atlas = new PatternAtlas(atlasSize, atlasSize);
        RecordingSink sink = new RecordingSink();
        atlas.setUploadSink(sink);
        Entry[] entries = new Entry[entryCount];
        Pattern[] patterns = new Pattern[entryCount];
        for (int i = 0; i < entryCount; i++) {
            patterns[i] = patternFor(i + 1);
            entries[i] = atlas.cachePatternHeadless(patterns[i], i);
            assertNotNull(entries[i], "entry " + i + " must allocate");
            assertEquals(0, entries[i].atlasIndex(), "fixture expects a single page");
        }
        atlas.assignHeadlessTextureIdsForTesting();
        return new Fixture(atlas, sink, entries, patterns, new byte[atlasSize * atlasSize], atlasSize);
    }

    @Test
    public void scatteredTilesUploadIndividuallyUnderOneBind() {
        Fixture f = newFixture(1024, 60);
        int[] dirty = {0, 25, 59}; // non-contiguous slots in the page

        f.atlas().beginBatch();
        for (int index : dirty) {
            f.writePattern(index);
        }
        f.atlas().endBatch();

        assertEquals(3, f.sink().uploads.size(), "one upload per dirty tile");
        assertEquals(3L * TILE_BYTES, f.sink().bytesUploaded,
                "bytes must equal 3 * tileByteSize, not page size");
        assertEquals(1, f.sink().begins, "single bind for the whole page flush");
        assertEquals(1, f.sink().ends);
        for (int i = 0; i < dirty.length; i++) {
            RecordingSink.Upload u = f.sink().uploads.get(i);
            assertEquals(TILE, u.width());
            assertEquals(TILE, u.height());
            assertEquals(f.entries()[dirty[i]].tileX() * TILE, u.x());
            assertEquals(f.entries()[dirty[i]].tileY() * TILE, u.y());
            assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), u);
        }
    }

    @Test
    public void repeatedWritesToSameTileUploadOnce() {
        Fixture f = newFixture(1024, 4);

        f.atlas().beginBatch();
        for (int i = 0; i < 5; i++) {
            f.writePattern(2);
        }
        f.atlas().endBatch();

        assertEquals(1, f.sink().uploads.size(), "slot must be deduplicated within a batch");
        assertEquals(TILE_BYTES, f.sink().bytesUploaded);
        assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), f.sink().uploads.get(0));
    }

    @Test
    public void endBatchWithoutWritesUploadsNothing() {
        Fixture f = newFixture(1024, 4);
        f.atlas().beginBatch();
        f.atlas().endBatch();
        assertEquals(0, f.sink().uploads.size());
        assertEquals(0, f.sink().begins);
        assertEquals(0L, f.sink().bytesUploaded);
    }

    @Test
    public void overflowFallsBackToDirtyBoundingRect() {
        // 128x128 page = 16x16 tiles, 256 slots. 65 distinct dirty slots overflows
        // the 64-entry exact tracker -> single bounding-rect upload.
        Fixture f = newFixture(128, 65);

        f.atlas().beginBatch();
        for (int i = 0; i < 65; i++) {
            f.writePattern(i);
        }
        f.atlas().endBatch();

        assertEquals(1, f.sink().uploads.size(), "overflow must collapse to one rect upload");
        RecordingSink.Upload u = f.sink().uploads.get(0);
        // Slots 0..64 fill tile rows 0..3 fully plus (0,4): union = cols 0..15, rows 0..4.
        assertEquals(0, u.x());
        assertEquals(0, u.y());
        assertEquals(128, u.width());
        assertEquals(5 * TILE, u.height());
        assertEquals(128, u.srcStride(), "rect path reads the page mirror at page stride");
        assertEquals(128L * 5 * TILE, f.sink().bytesUploaded);
        assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), u);
    }

    @Test
    public void boundsKeepUpdatingAfterSlotListOverflow() {
        Fixture f = newFixture(128, 256);

        f.atlas().beginBatch();
        for (int i = 0; i < 65; i++) {
            f.writePattern(i); // overflow the exact tracker
        }
        f.writePattern(200);   // tile (8, 12) — must still widen the bounds
        f.atlas().endBatch();

        assertEquals(1, f.sink().uploads.size());
        RecordingSink.Upload u = f.sink().uploads.get(0);
        assertEquals(0, u.x());
        assertEquals(0, u.y());
        assertEquals(128, u.width());
        assertEquals((f.entries()[200].tileY() + 1) * TILE, u.height(),
                "rect must cover the union including post-overflow writes");
        assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), u);
    }

    @Test
    public void fullPageDirtyUploadsWholePageThenResetsCoherently() {
        Fixture f = newFixture(128, 4);

        f.atlas().beginBatch();
        f.writePattern(0);
        f.atlas().markPageFullyDirty(0); // simulates fresh-texture page sync
        f.atlas().endBatch();

        assertEquals(1, f.sink().uploads.size());
        RecordingSink.Upload full = f.sink().uploads.get(0);
        assertEquals(128, full.width());
        assertEquals(128, full.height());
        assertEquals(128L * 128, f.sink().bytesUploaded);
        assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), full);

        // Dirty state must reset after the flush: next batch is incremental again.
        f.sink().reset();
        f.atlas().beginBatch();
        f.writePattern(1);
        f.atlas().endBatch();
        assertEquals(1, f.sink().uploads.size());
        assertEquals((long) TILE_BYTES, f.sink().bytesUploaded);
    }

    @Test
    public void immediatePathUploadsSingleTilePerBind() {
        Fixture f = newFixture(1024, 2);

        // No beginBatch: immediate path binds, uploads one tile, unbinds.
        f.writePattern(0);
        f.writePattern(1);

        assertEquals(2, f.sink().uploads.size());
        assertEquals(2, f.sink().begins, "immediate path binds per tile");
        assertEquals(2, f.sink().ends);
        assertEquals(2L * TILE_BYTES, f.sink().bytesUploaded);
        for (RecordingSink.Upload u : f.sink().uploads) {
            assertEquals(TILE, u.width());
            assertEquals(TILE, u.height());
            assertEquals(TILE, u.srcStride(), "immediate tile data is tightly packed");
        }
    }

    @Test
    public void simulatedDplcWorkloadBeatsBaselineByOver100x() {
        // Baseline workload (a): a typical DPLC change rewrites 32 scattered tiles
        // in one page every 2-4 frames; old endBatch uploaded the full 1 MB page.
        Fixture f = newFixture(1024, 256);
        long fullPageBytes = (long) f.atlas().getAtlasWidth() * f.atlas().getAtlasHeight();
        long maxBytesPerEndBatch = 0;
        int frames = 8;
        for (int frame = 0; frame < frames; frame++) {
            long before = f.sink().bytesUploaded;
            int uploadsBefore = f.sink().uploads.size();
            f.atlas().beginBatch();
            for (int i = 0; i < 32; i++) {
                f.writePattern(i * 8); // scattered slots
            }
            f.atlas().endBatch();
            maxBytesPerEndBatch = Math.max(maxBytesPerEndBatch, f.sink().bytesUploaded - before);
            assertFrameUploadsMatchExpected(f, uploadsBefore);
        }

        assertEquals(32L * TILE_BYTES, maxBytesPerEndBatch);
        double reduction = (double) fullPageBytes / maxBytesPerEndBatch;
        System.out.printf(
                "Simulated DPLC workload: %d bytes/endBatch vs %d full-page baseline -> %.1fx reduction%n",
                maxBytesPerEndBatch, fullPageBytes, reduction);
        assertTrue(reduction >= 100.0,
                "spec acceptance: >=100x upload reduction for DPLC animation, got " + reduction);
    }

    @Test
    public void simulatedSlotMachineBurstBeatsBaselineByOver100x() {
        // Baseline workload (b): CNZ slot machines rewrite 24-48 tiles EVERY frame.
        Fixture f = newFixture(1024, 48);
        long fullPageBytes = (long) f.atlas().getAtlasWidth() * f.atlas().getAtlasHeight();
        long maxBytesPerEndBatch = 0;
        int frames = 8;
        for (int frame = 0; frame < frames; frame++) {
            long before = f.sink().bytesUploaded;
            int uploadsBefore = f.sink().uploads.size();
            f.atlas().beginBatch();
            for (int i = 0; i < 48; i++) {
                f.writePattern(i);
            }
            f.atlas().endBatch();
            maxBytesPerEndBatch = Math.max(maxBytesPerEndBatch, f.sink().bytesUploaded - before);
            assertFrameUploadsMatchExpected(f, uploadsBefore);
        }

        assertEquals(48L * TILE_BYTES, maxBytesPerEndBatch);
        double reduction = (double) fullPageBytes / maxBytesPerEndBatch;
        System.out.printf(
                "Simulated CNZ slot-machine burst: %d bytes/endBatch vs %d full-page baseline -> %.1fx reduction%n",
                maxBytesPerEndBatch, fullPageBytes, reduction);
        assertTrue(reduction >= 100.0,
                "slot-machine burst should also stay >=100x under baseline, got " + reduction);
    }

    /** Asserts every upload issued since {@code uploadsBefore} carries the expected pixels. */
    private static void assertFrameUploadsMatchExpected(Fixture f, int uploadsBefore) {
        List<RecordingSink.Upload> uploads = f.sink().uploads;
        assertTrue(uploads.size() > uploadsBefore, "frame must produce uploads");
        for (int i = uploadsBefore; i < uploads.size(); i++) {
            assertUploadMatchesExpected(f.expectedPage(), f.pageWidth(), uploads.get(i));
        }
    }
}
