package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.level.Pattern;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_RED;

/**
 * Texture atlas for 8x8 indexed patterns.
 * Stores patterns across one or more GL textures to avoid per-tile texture binds.
 */
public class PatternAtlas {
    private static final Logger LOGGER = Logger.getLogger(PatternAtlas.class.getName());

    public static final int TILE_SIZE = Pattern.PATTERN_WIDTH;
    private static final float UV_INSET_PIXELS = 0.01f;
    private static final int MAX_ATLASES = 2;
    private static final int NATIVE_PATTERN_ID_MAX = 0x7FF;
    // Max distinct dirty slots tracked exactly per page per batch. Below/at this count,
    // endBatch() uploads each dirty tile individually; past it, the bounding rectangle
    // of ALL dirty tiles (bounds keep updating after overflow) is uploaded instead.
    private static final int DIRTY_SLOT_TRACK_LIMIT = 64;

    private final int atlasWidth;
    private final int atlasHeight;
    private final int tilesPerRow;
    private final int tilesPerColumn;
    private final int maxSlots;

    // Tiered lookup, no Integer autoboxing on any render path:
    //  - fastEntries: flat array for dense low IDs (level tiles)
    //  - rangeEntries: per-PatternAtlasRange flat arrays (objects, HUD, sidekicks, ...)
    //    indexed by patternId - range.base(); lazily allocated on first use
    //  - sparseFallback: open-addressing int map for IDs outside both (e.g. aliases
    //    created in governance gaps, which bypass range validation)
    // Invariant: LEVEL_TILES IDs are served exclusively by fastEntries — the fast
    // array covers exactly that range, so the LEVEL_TILES rangeEntries slot is
    // never allocated. Derived (not duplicated) so growing LEVEL_TILES cannot
    // silently split its storage between tiers.
    private static final int FAST_ENTRIES_SIZE = PatternAtlasRange.LEVEL_TILES.endExclusive();
    // PatternAtlasRange bases/sizes are 4KB-aligned, so range ownership of any ID
    // resolves with one shift + one array read. buildRangeBlockTable() fails fast
    // at class init if a future range breaks that alignment.
    private static final int RANGE_BLOCK_SHIFT = 12;
    private static final PatternAtlasRange[] RANGE_BY_BLOCK = buildRangeBlockTable();
    private Entry[] fastEntries = new Entry[FAST_ENTRIES_SIZE];
    private final Entry[][] rangeEntries = new Entry[PatternAtlasRange.values().length][];
    private final SparsePatternMap sparseFallback = new SparsePatternMap();
    private final List<AtlasPage> pages = new ArrayList<>();
    // Per-(atlasIndex, slot) reference count. Replaces the O(N) scan in isSlotShared:
    // multiple Entry objects (aliases) can point at the same physical atlas slot, and we
    // must only free that slot when the last reference is removed. Key encoding:
    //   ((long) atlasIndex << 32) | (slot & 0xFFFFFFFFL)
    private final Map<Long, Integer> slotRefCounts = new HashMap<>();
    // Lazily allocated to avoid LWJGL native library loading in headless tests
    private ByteBuffer patternUploadBuffer;
    private boolean initialized = false;

    // Batch upload support: CPU-side pixel buffer mirrors the GPU atlas.
    // During batch mode, uploadPattern() writes to cpuPixels only (no GL calls).
    // endBatch() uploads only the dirty region of each dirty page: individual tiles
    // while the exact dirty-slot list holds, otherwise the dirty bounding rectangle.
    private byte[][] cpuPixels;      // per-atlas-page pixel data [atlasWidth * atlasHeight]
    private boolean[] dirtyPages;    // tracks which pages were written during batch
    // Exact dirty-slot tracking (per page): fixed-capacity slot list with overflow flag.
    // Bounds (min/max dirty tile x/y) keep updating even after the slot list overflows.
    private int[][] dirtySlots;
    private int[] dirtySlotCounts;
    private boolean[] dirtySlotOverflow;
    private int[] dirtyMinTileX;
    private int[] dirtyMinTileY;
    private int[] dirtyMaxTileX;
    private int[] dirtyMaxTileY;
    private boolean batchMode = false;
    private byte[] patternUploadScratch;
    private final PerformanceProfiler profiler;
    // GL upload seam: production sink issues the real GL calls; tests install a
    // recording sink so dirty-tracking/upload decisions run without a GL context.
    private AtlasUploadSink uploadSink = new GlUploadSink();

    /**
     * Package-private seam between upload-decision logic and the GL calls that
     * execute it. {@code src} is CPU-side pixel data ({@code srcStride} bytes per
     * row); the sink uploads the {@code width}x{@code height} rectangle whose
     * top-left source byte is {@code src[srcOffset]} to texel ({@code x},{@code y}).
     */
    interface AtlasUploadSink {
        void begin(int textureId);

        void upload(byte[] src, int srcOffset, int srcStride, int x, int y, int width, int height);

        void end();
    }

    /** Test seam: replace the GL upload sink with a recording implementation. */
    void setUploadSink(AtlasUploadSink sink) {
        this.uploadSink = sink;
    }

    /**
     * Test seam: give headless-created pages synthetic non-zero texture ids so
     * endBatch() flushes them through the (stubbed) upload sink. Pages are treated
     * as already in sync with the CPU mirror (no full-page dirty mark).
     */
    void assignHeadlessTextureIdsForTesting() {
        int nextId = 1;
        for (AtlasPage page : pages) {
            if (page.textureId() == 0) {
                page.setTextureId(nextId);
            }
            nextId++;
        }
    }

    /** Describes a registered virtual pattern ID range for collision detection. */
    public record PatternRange(int base, int size, String category) {}

    public void registerRange(PatternAtlasRange range) {
        registerRange(range.base(), range.size(), range.category());
    }

    private final List<PatternRange> registeredRanges = new ArrayList<>();

    /**
     * Registers a virtual pattern ID range for collision detection.
     * Fails fast if the range overlaps an existing registered range.
     *
     * @param base     the starting pattern ID
     * @param size     the number of patterns in this range
     * @param category a human-readable name for logging (e.g., "Objects", "HUD")
     */
    public void registerRange(int base, int size, String category) {
        int newEnd = base + size;
        for (PatternRange existing : registeredRanges) {
            int existingEnd = existing.base() + existing.size();
            if (base < existingEnd && existing.base() < newEnd) {
                throw new IllegalArgumentException("Pattern range collision: " + category
                    + " [0x" + Integer.toHexString(base) + "-0x" + Integer.toHexString(newEnd)
                    + "] overlaps " + existing.category()
                    + " [0x" + Integer.toHexString(existing.base())
                    + "-0x" + Integer.toHexString(existingEnd) + "]");
            }
        }
        registeredRanges.add(new PatternRange(base, size, category));
    }

    /** Clears all registered ranges. Called on level unload or atlas reset. */
    public void clearRanges() {
        registeredRanges.clear();
    }

    /** Read-only view of registered governance ranges for verification. */
    public List<PatternRange> registeredRangesForTesting() {
        return List.copyOf(registeredRanges);
    }

    public PatternAtlas(int atlasWidth, int atlasHeight) {
        this(atlasWidth, atlasHeight, null);
    }

    public PatternAtlas(int atlasWidth, int atlasHeight, PerformanceProfiler profiler) {
        if (atlasWidth % TILE_SIZE != 0 || atlasHeight % TILE_SIZE != 0) {
            throw new IllegalArgumentException("Atlas size must be divisible by tile size");
        }
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.tilesPerRow = atlasWidth / TILE_SIZE;
        this.tilesPerColumn = atlasHeight / TILE_SIZE;
        this.maxSlots = tilesPerRow * tilesPerColumn;
        this.profiler = profiler;
        // patternUploadBuffer is lazily allocated when first needed
    }

    /**
     * Lazily allocate the pattern upload buffer.
     * This avoids triggering LWJGL native library loading during construction.
     */
    private ByteBuffer ensurePatternUploadBuffer() {
        if (patternUploadBuffer == null) {
            patternUploadBuffer = MemoryUtil.memAlloc(TILE_SIZE * TILE_SIZE);
        }
        return patternUploadBuffer;
    }

    // Lazily allocated full-page upload buffer for endBatch()
    private ByteBuffer fullPageUploadBuffer;

    private ByteBuffer ensureFullPageUploadBuffer() {
        int pagePixels = atlasWidth * atlasHeight;
        if (fullPageUploadBuffer == null || fullPageUploadBuffer.capacity() < pagePixels) {
            if (fullPageUploadBuffer != null) {
                MemoryUtil.memFree(fullPageUploadBuffer);
            }
            fullPageUploadBuffer = MemoryUtil.memAlloc(pagePixels);
        }
        return fullPageUploadBuffer;
    }

    public int getAtlasWidth() {
        return atlasWidth;
    }

    public int getAtlasHeight() {
        return atlasHeight;
    }

    public int getMaxSlotsPerAtlas() {
        return maxSlots;
    }

    public int getTextureId() {
        return getTextureId(0);
    }

    public int getTextureId(int atlasIndex) {
        if (atlasIndex < 0 || atlasIndex >= pages.size()) {
            return 0;
        }
        return pages.get(atlasIndex).textureId();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void init() {
        if (initialized) {
            return;
        }
        // Eagerly allocate the CPU-side pixel mirror BEFORE creating textures so
        // ALL uploads populate it and fresh textures can be marked fully dirty
        // (their GL contents are undefined until the first flush syncs the mirror).
        ensureCpuMirror();
        if (pages.isEmpty()) {
            pages.add(createPage(0));
        } else {
            for (AtlasPage page : pages) {
                if (page.textureId() == 0) {
                    page.setTextureId(createTexture());
                    markPageFullyDirty(page.atlasIndex());
                }
            }
        }
        initialized = true;
    }

    private void ensureCpuMirror() {
        if (cpuPixels != null) {
            return;
        }
        int pagePixels = atlasWidth * atlasHeight;
        cpuPixels = new byte[MAX_ATLASES][pagePixels];
        dirtyPages = new boolean[MAX_ATLASES];
        dirtySlots = new int[MAX_ATLASES][DIRTY_SLOT_TRACK_LIMIT];
        dirtySlotCounts = new int[MAX_ATLASES];
        dirtySlotOverflow = new boolean[MAX_ATLASES];
        dirtyMinTileX = new int[MAX_ATLASES];
        dirtyMinTileY = new int[MAX_ATLASES];
        dirtyMaxTileX = new int[MAX_ATLASES];
        dirtyMaxTileY = new int[MAX_ATLASES];
        for (int i = 0; i < MAX_ATLASES; i++) {
            clearDirtyState(i);
        }
    }

    /**
     * Marks every tile of a page dirty so the next {@link #endBatch()} uploads the
     * whole page. Used when a GL texture is (re)created for a page whose contents
     * are undefined relative to the CPU mirror. Package-private for tests.
     */
    void markPageFullyDirty(int atlasIndex) {
        // Never skip silently: an undefined GL texture must not be treated as clean.
        ensureCpuMirror();
        if (atlasIndex < 0 || atlasIndex >= dirtyPages.length) {
            throw new IllegalArgumentException("atlasIndex out of range: " + atlasIndex);
        }
        dirtyPages[atlasIndex] = true;
        dirtySlotOverflow[atlasIndex] = true;
        dirtyMinTileX[atlasIndex] = 0;
        dirtyMinTileY[atlasIndex] = 0;
        dirtyMaxTileX[atlasIndex] = tilesPerRow - 1;
        dirtyMaxTileY[atlasIndex] = tilesPerColumn - 1;
    }

    private void clearDirtyState(int atlasIndex) {
        dirtyPages[atlasIndex] = false;
        dirtySlotCounts[atlasIndex] = 0;
        dirtySlotOverflow[atlasIndex] = false;
        dirtyMinTileX[atlasIndex] = Integer.MAX_VALUE;
        dirtyMinTileY[atlasIndex] = Integer.MAX_VALUE;
        dirtyMaxTileX[atlasIndex] = -1;
        dirtyMaxTileY[atlasIndex] = -1;
    }

    private void markTileDirty(int atlasIndex, int slot, int tileX, int tileY) {
        if (dirtyPages == null || atlasIndex < 0 || atlasIndex >= dirtyPages.length) {
            return;
        }
        dirtyPages[atlasIndex] = true;
        // Bounds must keep updating even after the exact slot list overflows so the
        // fallback rectangle always covers the union of every dirty tile.
        if (tileX < dirtyMinTileX[atlasIndex]) {
            dirtyMinTileX[atlasIndex] = tileX;
        }
        if (tileX > dirtyMaxTileX[atlasIndex]) {
            dirtyMaxTileX[atlasIndex] = tileX;
        }
        if (tileY < dirtyMinTileY[atlasIndex]) {
            dirtyMinTileY[atlasIndex] = tileY;
        }
        if (tileY > dirtyMaxTileY[atlasIndex]) {
            dirtyMaxTileY[atlasIndex] = tileY;
        }
        if (dirtySlotOverflow[atlasIndex]) {
            return;
        }
        int[] slots = dirtySlots[atlasIndex];
        int count = dirtySlotCounts[atlasIndex];
        for (int i = 0; i < count; i++) {
            if (slots[i] == slot) {
                return;
            }
        }
        if (count < DIRTY_SLOT_TRACK_LIMIT) {
            slots[count] = slot;
            dirtySlotCounts[atlasIndex] = count + 1;
        } else {
            dirtySlotOverflow[atlasIndex] = true;
        }
    }

    public Entry cachePattern(Pattern pattern, int patternId) {
        Entry entry = ensureEntry(patternId, false);
        if (entry == null) {
            return null;
        }
        if (initialized && pattern != null) {
            uploadPattern(pattern, entry);
        }
        return entry;
    }

    public Entry cachePatternHeadless(Pattern pattern, int patternId) {
        return ensureEntry(patternId, true);
    }

    public Entry updatePattern(Pattern pattern, int patternId) {
        return cachePattern(pattern, patternId);
    }

    public Entry updatePatternHeadless(Pattern pattern, int patternId) {
        return cachePatternHeadless(pattern, patternId);
    }

    public Entry getEntry(int patternId) {
        if (patternId >= 0 && patternId < FAST_ENTRIES_SIZE) {
            return fastEntries[patternId];
        }
        PatternAtlasRange range = storageRangeFor(patternId);
        if (range != null) {
            Entry[] entries = rangeEntries[range.ordinal()];
            return entries == null ? null : entries[patternId - range.base()];
        }
        return sparseFallback.get(patternId);
    }

    private static PatternAtlasRange[] buildRangeBlockTable() {
        int blockSize = 1 << RANGE_BLOCK_SHIFT;
        int maxEnd = 0;
        for (PatternAtlasRange range : PatternAtlasRange.values()) {
            // Fail fast (surfaces as ExceptionInInitializerError at first class use)
            // if a future range breaks the 4KB alignment the block table depends on,
            // instead of silently demoting that range's IDs to the fallback map.
            if (range.base() % blockSize != 0 || range.size() % blockSize != 0) {
                throw new IllegalStateException("PatternAtlasRange " + range
                        + " must be aligned to 0x" + Integer.toHexString(blockSize)
                        + " for block-table lookup: base=0x" + Integer.toHexString(range.base())
                        + " size=0x" + Integer.toHexString(range.size()));
            }
            maxEnd = Math.max(maxEnd, range.endExclusive());
        }
        PatternAtlasRange[] table = new PatternAtlasRange[maxEnd >> RANGE_BLOCK_SHIFT];
        for (PatternAtlasRange range : PatternAtlasRange.values()) {
            int endBlock = range.endExclusive() >> RANGE_BLOCK_SHIFT;
            for (int block = range.base() >> RANGE_BLOCK_SHIFT; block < endBlock; block++) {
                table[block] = range;
            }
        }
        return table;
    }

    /**
     * Resolves which {@link PatternAtlasRange} stores a pattern ID, or {@code null}
     * if the ID belongs in the sparse fallback map. Must be used consistently by
     * every read/write/remove so an entry is always found where it was stored.
     */
    private static PatternAtlasRange storageRangeFor(int patternId) {
        if (patternId < 0) {
            return null;
        }
        int block = patternId >>> RANGE_BLOCK_SHIFT;
        return block < RANGE_BY_BLOCK.length ? RANGE_BY_BLOCK[block] : null;
    }

    /**
     * Remove a pattern entry from the atlas and reclaim its slot.
     * If other entries (aliases) share the same physical slot, the slot
     * is NOT freed until all references are removed.
     *
     * @param patternId The pattern ID to remove
     * @return true if the pattern was removed, false if it wasn't cached
     */
    public boolean removeEntry(int patternId) {
        Entry old;
        if (patternId >= 0 && patternId < FAST_ENTRIES_SIZE) {
            old = fastEntries[patternId];
            fastEntries[patternId] = null;
        } else {
            PatternAtlasRange range = storageRangeFor(patternId);
            if (range != null) {
                Entry[] entries = rangeEntries[range.ordinal()];
                if (entries != null) {
                    int index = patternId - range.base();
                    old = entries[index];
                    entries[index] = null;
                } else {
                    old = null;
                }
            } else {
                old = sparseFallback.remove(patternId);
            }
        }
        if (old != null) {
            if (releaseSlotRef(old.atlasIndex(), old.slot())) {
                AtlasPage page = pages.get(old.atlasIndex());
                page.freeSlot(old.slot());
            }
            return true;
        }
        return false;
    }

    private static long slotRefKey(int atlasIndex, int slot) {
        return ((long) atlasIndex << 32) | (slot & 0xFFFFFFFFL);
    }

    /** Increment the reference count for an (atlasIndex, slot) pair. */
    private void retainSlotRef(int atlasIndex, int slot) {
        long key = slotRefKey(atlasIndex, slot);
        slotRefCounts.merge(key, 1, Integer::sum);
    }

    /**
     * Decrement the reference count for an (atlasIndex, slot) pair.
     * @return {@code true} if the count reached zero (caller may free the physical slot).
     */
    private boolean releaseSlotRef(int atlasIndex, int slot) {
        long key = slotRefKey(atlasIndex, slot);
        Integer count = slotRefCounts.get(key);
        if (count == null) {
            return true;
        }
        if (count <= 1) {
            slotRefCounts.remove(key);
            return true;
        }
        slotRefCounts.put(key, count - 1);
        return false;
    }

    /**
     * Create an alias entry that points to the same atlas slot as another pattern.
     * This allows multiple pattern IDs to share the same texture data without
     * allocating additional atlas slots.
     *
     * Will NOT overwrite an existing entry - if aliasId already has an entry,
     * this method returns false and leaves it unchanged.
     *
     * @param aliasId The new pattern ID to create
     * @param targetId The existing pattern ID to alias to
     * @return true if the alias was created, false if target doesn't exist or aliasId already exists
     */
    public boolean aliasEntry(int aliasId, int targetId) {
        // Don't overwrite existing entries (e.g., ring patterns)
        if (getEntry(aliasId) != null) {
            return false;
        }
        Entry target = getEntry(targetId);
        if (target == null) {
            return false;
        }
        // Create a new entry with the alias ID but same atlas coordinates as target
        Entry alias = new Entry(aliasId, target.atlasIndex(), target.slot(),
                target.tileX(), target.tileY(), target.u0(), target.v0(), target.u1(), target.v1());
        putEntry(aliasId, alias);
        return true;
    }

    public int getAtlasCount() {
        return pages.size();
    }

    public void cleanup() {
        for (AtlasPage page : pages) {
            if (page.textureId() != 0) {
                glDeleteTextures(page.textureId());
            }
        }
        cleanupCommon();
    }

    public void cleanupHeadless() {
        cleanupCommon();
    }

    private void cleanupCommon() {
        initialized = false;
        batchMode = false;
        Arrays.fill(fastEntries, null);
        Arrays.fill(rangeEntries, null);
        sparseFallback.clear();
        slotRefCounts.clear();
        pages.clear();
        cpuPixels = null;
        dirtyPages = null;
        dirtySlots = null;
        dirtySlotCounts = null;
        dirtySlotOverflow = null;
        dirtyMinTileX = null;
        dirtyMinTileY = null;
        dirtyMaxTileX = null;
        dirtyMaxTileY = null;
        if (patternUploadBuffer != null) {
            MemoryUtil.memFree(patternUploadBuffer);
            patternUploadBuffer = null;
        }
        if (fullPageUploadBuffer != null) {
            MemoryUtil.memFree(fullPageUploadBuffer);
            fullPageUploadBuffer = null;
        }
    }

    private Entry ensureEntry(int patternId, boolean headless) {
        validatePatternIdGovernance(patternId);
        Entry existing = getEntry(patternId);
        if (existing != null) {
            return existing;
        }

        AtlasPage page = getOrCreatePage(headless);
        if (page == null) {
            LOGGER.warning("Pattern atlas capacity exceeded; patternId=" + patternId);
            return null;
        }

        int slot = page.allocateSlot();
        if (slot < 0) {
            LOGGER.warning("Pattern atlas slot allocation failed; patternId=" + patternId);
            return null;
        }
        int tileX = slot % tilesPerRow;
        int tileY = slot / tilesPerRow;

        int pixelX = tileX * TILE_SIZE;
        int pixelY = tileY * TILE_SIZE;

        float u0 = (pixelX + UV_INSET_PIXELS) / (float) atlasWidth;
        float u1 = (pixelX + TILE_SIZE - UV_INSET_PIXELS) / (float) atlasWidth;
        float v0 = (pixelY + UV_INSET_PIXELS) / (float) atlasHeight;
        float v1 = (pixelY + TILE_SIZE - UV_INSET_PIXELS) / (float) atlasHeight;

        Entry entry = new Entry(patternId, page.atlasIndex(), slot, tileX, tileY, u0, v0, u1, v1);
        putEntry(patternId, entry);
        return entry;
    }

    private void validatePatternIdGovernance(int patternId) {
        if (patternId <= NATIVE_PATTERN_ID_MAX) {
            return;
        }
        if (PatternAtlasRange.forPatternId(patternId) != null) {
            return;
        }
        throw new IllegalArgumentException("Virtual pattern ID 0x"
                + Integer.toHexString(patternId)
                + " is outside PatternAtlasRange governance");
    }

    private void putEntry(int patternId, Entry entry) {
        Entry previous;
        if (patternId >= 0 && patternId < FAST_ENTRIES_SIZE) {
            previous = fastEntries[patternId];
            fastEntries[patternId] = entry;
        } else {
            PatternAtlasRange range = storageRangeFor(patternId);
            if (range != null) {
                Entry[] entries = rangeEntries[range.ordinal()];
                if (entries == null) {
                    entries = new Entry[range.size()];
                    rangeEntries[range.ordinal()] = entries;
                }
                int index = patternId - range.base();
                previous = entries[index];
                entries[index] = entry;
            } else {
                previous = sparseFallback.put(patternId, entry);
            }
        }
        // If we displaced an existing entry at the same patternId, release its slot ref.
        // ensureEntry() short-circuits on existing IDs, so this branch is defensive — but
        // any code path that overwrites a live entry must keep ref counts balanced.
        if (previous != null) {
            if (releaseSlotRef(previous.atlasIndex(), previous.slot())) {
                pages.get(previous.atlasIndex()).freeSlot(previous.slot());
            }
        }
        retainSlotRef(entry.atlasIndex(), entry.slot());
    }

    /**
     * Begin batch mode. Pattern uploads write to a CPU-side buffer only.
     * Call {@link #endBatch()} to flush everything to the GPU in one call per page.
     */
    public void beginBatch() {
        ensureCpuMirror();
        batchMode = true;
    }

    /**
     * End batch mode and upload the dirty region of every dirty atlas page to the
     * GPU: individual 8x8 tile uploads under one texture bind while the exact
     * dirty-slot list holds (≤{@value #DIRTY_SLOT_TRACK_LIMIT} distinct tiles),
     * otherwise one upload of the dirty tiles' bounding rectangle.
     */
    public void endBatch() {
        if (!batchMode) {
            return;
        }
        batchMode = false;
        if (cpuPixels == null || dirtyPages == null) {
            return;
        }
        // Time the per-dirty-page glTexSubImage2D uploads. endBatch() runs at most
        // a few times per frame, but DPLC-driven calls happen mid render.sprites.
        // Using beginSection here would truncate render.sprites every frame, so we
        // measure manually and credit render.atlas_upload via recordSectionTime,
        // which preserves the active section by shifting its start timestamp.
        long uploadStartNanos = System.nanoTime();
        try {
            for (int i = 0; i < pages.size(); i++) {
                if (i >= dirtyPages.length || !dirtyPages[i]) {
                    continue;
                }
                int textureId = getTextureId(i);
                if (textureId == 0) {
                    // No texture yet (headless page): keep the dirty state pending so a
                    // later flush after texture creation still syncs the mirror.
                    continue;
                }
                flushDirtyPage(i, textureId);
            }
        } finally {
            if (profiler != null) {
                profiler.recordSectionTime("render.atlas_upload",
                        System.nanoTime() - uploadStartNanos);
            }
        }
    }

    private void flushDirtyPage(int atlasIndex, int textureId) {
        byte[] page = cpuPixels[atlasIndex];
        uploadSink.begin(textureId);
        try {
            if (!dirtySlotOverflow[atlasIndex]) {
                // Small change: upload each dirty tile individually under one bind.
                byte[] tile = ensurePatternUploadScratch();
                int[] slots = dirtySlots[atlasIndex];
                int count = dirtySlotCounts[atlasIndex];
                for (int i = 0; i < count; i++) {
                    int slot = slots[i];
                    int pixelX = (slot % tilesPerRow) * TILE_SIZE;
                    int pixelY = (slot / tilesPerRow) * TILE_SIZE;
                    for (int row = 0; row < TILE_SIZE; row++) {
                        System.arraycopy(page, (pixelY + row) * atlasWidth + pixelX,
                                tile, row * TILE_SIZE, TILE_SIZE);
                    }
                    uploadSink.upload(tile, 0, TILE_SIZE, pixelX, pixelY, TILE_SIZE, TILE_SIZE);
                }
            } else {
                // Large change: upload the bounding rectangle of all dirty tiles.
                int x0 = dirtyMinTileX[atlasIndex] * TILE_SIZE;
                int y0 = dirtyMinTileY[atlasIndex] * TILE_SIZE;
                int width = (dirtyMaxTileX[atlasIndex] + 1) * TILE_SIZE - x0;
                int height = (dirtyMaxTileY[atlasIndex] + 1) * TILE_SIZE - y0;
                uploadSink.upload(page, y0 * atlasWidth + x0, atlasWidth, x0, y0, width, height);
            }
        } finally {
            uploadSink.end();
        }
        // Cleared only after a successful flush: a throwing sink must leave the
        // dirty state intact so a later flush can still sync GPU and mirror.
        clearDirtyState(atlasIndex);
    }

    void uploadPattern(Pattern pattern, Entry entry) {
        int pixelX = entry.tileX() * TILE_SIZE;
        int pixelY = entry.tileY() * TILE_SIZE;
        byte[] patternPixels = ensurePatternUploadScratch();
        pattern.copyInto(patternPixels, 0);

        // Always write to the CPU-side buffer (keeps it in sync for future batches)
        if (cpuPixels != null && entry.atlasIndex() < cpuPixels.length) {
            byte[] page = cpuPixels[entry.atlasIndex()];
            for (int row = 0; row < TILE_SIZE; row++) {
                int srcRowStart = row * TILE_SIZE;
                int dstRowStart = (pixelY + row) * atlasWidth + pixelX;
                System.arraycopy(patternPixels, srcRowStart, page, dstRowStart, TILE_SIZE);
            }
        }

        if (batchMode) {
            // Record the dirty tile — actual GL upload deferred to endBatch()
            markTileDirty(entry.atlasIndex(), entry.slot(), entry.tileX(), entry.tileY());
            return;
        }

        // Immediate upload (non-batch path): bind, upload one tile, unbind.
        int textureId = getTextureId(entry.atlasIndex());
        uploadSink.begin(textureId);
        try {
            uploadSink.upload(patternPixels, 0, TILE_SIZE, pixelX, pixelY, TILE_SIZE, TILE_SIZE);
        } finally {
            uploadSink.end();
        }
    }

    /** Production sink: stages CPU pixels into a direct buffer and issues the GL calls. */
    private final class GlUploadSink implements AtlasUploadSink {
        @Override
        public void begin(int textureId) {
            glBindTexture(GL_TEXTURE_2D, textureId);
        }

        @Override
        public void upload(byte[] src, int srcOffset, int srcStride, int x, int y,
                int width, int height) {
            if (srcStride == width) {
                // Tightly packed source rows: stage exactly width*height bytes.
                int length = width * height;
                ByteBuffer buf = length <= TILE_SIZE * TILE_SIZE
                        ? ensurePatternUploadBuffer() : ensureFullPageUploadBuffer();
                buf.clear();
                buf.put(src, srcOffset, length);
                buf.flip();
                glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
                        GL_RED, GL_UNSIGNED_BYTE, buf);
                return;
            }
            // Strided source (dirty rectangle inside a page row): stage the spanning
            // bytes once and let GL_UNPACK_ROW_LENGTH skip the non-dirty columns.
            ByteBuffer buf = ensureFullPageUploadBuffer();
            buf.clear();
            buf.put(src, srcOffset, (height - 1) * srcStride + width);
            buf.flip();
            glPixelStorei(GL_UNPACK_ROW_LENGTH, srcStride);
            try {
                glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
                        GL_RED, GL_UNSIGNED_BYTE, buf);
            } finally {
                // Reset so later uploads are not poisoned by stale pixel-store state.
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            }
        }

        @Override
        public void end() {
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    private byte[] ensurePatternUploadScratch() {
        if (patternUploadScratch == null) {
            patternUploadScratch = new byte[TILE_SIZE * TILE_SIZE];
        }
        return patternUploadScratch;
    }

    private AtlasPage getOrCreatePage(boolean headless) {
        if (pages.isEmpty()) {
            pages.add(createPage(0, headless));
        }
        AtlasPage current = pages.get(pages.size() - 1);
        if (current.hasCapacity()) {
            return current;
        }
        if (pages.size() >= MAX_ATLASES) {
            return null;
        }
        AtlasPage next = createPage(pages.size(), headless);
        pages.add(next);
        return next;
    }

    private AtlasPage createPage(int atlasIndex) {
        return createPage(atlasIndex, false);
    }

    private AtlasPage createPage(int atlasIndex, boolean headless) {
        int textureId = headless ? 0 : createTexture();
        if (textureId != 0) {
            // Fresh GL texture contents are undefined: schedule a full sync from the
            // CPU mirror so dirty-rect uploads never leave stale/undefined texels.
            markPageFullyDirty(atlasIndex);
        }
        return new AtlasPage(atlasIndex, textureId, maxSlots);
    }

    private int createTexture() {
        int textureId = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, atlasWidth, atlasHeight, 0,
                GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    private static final class AtlasPage {
        private final int atlasIndex;
        private int textureId;
        private final int maxSlots;
        private int nextSlot;
        private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();

        private AtlasPage(int atlasIndex, int textureId, int maxSlots) {
            this.atlasIndex = atlasIndex;
            this.textureId = textureId;
            this.maxSlots = maxSlots;
        }

        private boolean hasCapacity() {
            return nextSlot < maxSlots || !freeSlots.isEmpty();
        }

        private int allocateSlot() {
            if (!freeSlots.isEmpty()) {
                return freeSlots.removeLast();
            }
            if (nextSlot >= maxSlots) {
                return -1;
            }
            return nextSlot++;
        }

        private void freeSlot(int slot) {
            freeSlots.addLast(slot);
        }

        private int atlasIndex() {
            return atlasIndex;
        }

        private int textureId() {
            return textureId;
        }

        private void setTextureId(int textureId) {
            this.textureId = textureId;
        }
    }

    public record Entry(int patternId, int atlasIndex, int slot, int tileX, int tileY,
            float u0, float v0, float u1, float v1) {
    }

    /**
     * Minimal open-addressing int-keyed map for pattern IDs outside the fast array
     * and outside every {@link PatternAtlasRange}. Linear probing with tombstone
     * deletion; entries store their own key ({@link Entry#patternId()}), so no
     * boxing and no parallel key array. Package-private for focused tests.
     *
     * <p><b>Why hand-rolled despite being cold:</b> this path is rarely reached —
     * {@code ensureEntry} governance throws for unranged IDs, so only
     * {@code aliasEntry} (which bypasses validation, and has no production callers
     * today) can populate it. The map exists to keep the atlas uniformly
     * boxing/allocation-free per the performance-optimization plan, not because
     * this tier was measured hot. A {@code HashMap<Integer, Entry>} would be
     * functionally equivalent here.
     *
     * <p><b>Invariants:</b>
     * <ul>
     * <li>{@code used} counts live entries <em>plus</em> tombstones and is capped
     *     at 50% of capacity before any insert. Tombstones must count because they
     *     lengthen probe chains exactly like live entries; excluding them would let
     *     deletion churn degrade lookups toward full-table scans.</li>
     * <li>Probe-chain discipline: a {@code null} slot terminates a probe (the key
     *     is absent); a tombstone never terminates — probing must continue past it
     *     so entries inserted before a later-deleted neighbor stay reachable.
     *     Inserts reuse the first tombstone seen, but only after the full chain
     *     confirms the key is absent.</li>
     * <li>{@code size} (live entries only) drives the rehash decision when the
     *     {@code used} cap is hit: double capacity if live load justifies growth
     *     ({@code size * 4 > capacity}), otherwise rehash at the same capacity
     *     purely to purge accumulated tombstones.</li>
     * </ul>
     */
    static final class SparsePatternMap {
        private static final Entry TOMBSTONE =
                new Entry(Integer.MIN_VALUE, -1, -1, -1, -1, 0f, 0f, 0f, 0f);
        private static final int INITIAL_CAPACITY = 16;

        private Entry[] table = new Entry[INITIAL_CAPACITY];
        private int size;
        private int used; // live entries plus tombstones

        Entry get(int key) {
            Entry[] t = table;
            int mask = t.length - 1;
            int index = indexFor(key, t.length);
            for (Entry e = t[index]; e != null; e = t[index = (index + 1) & mask]) {
                if (e != TOMBSTONE && e.patternId() == key) {
                    return e;
                }
            }
            return null;
        }

        /** {@code key} must equal {@code entry.patternId()}; returns the displaced entry. */
        Entry put(int key, Entry entry) {
            if (key != entry.patternId()) {
                throw new IllegalArgumentException("key " + key
                        + " != entry.patternId() " + entry.patternId());
            }
            if ((used + 1) * 2 > table.length) {
                // Double only when live load justifies it; otherwise rehash at the
                // same capacity purely to purge accumulated tombstones.
                rehash(size * 4 > table.length ? table.length * 2 : table.length);
            }
            Entry[] t = table;
            int mask = t.length - 1;
            int index = indexFor(key, t.length);
            int firstTombstone = -1;
            while (true) {
                Entry e = t[index];
                if (e == null) {
                    if (firstTombstone >= 0) {
                        t[firstTombstone] = entry;
                    } else {
                        t[index] = entry;
                        used++;
                    }
                    size++;
                    return null;
                }
                if (e == TOMBSTONE) {
                    if (firstTombstone < 0) {
                        firstTombstone = index;
                    }
                } else if (e.patternId() == key) {
                    t[index] = entry;
                    return e;
                }
                index = (index + 1) & mask;
            }
        }

        Entry remove(int key) {
            Entry[] t = table;
            int mask = t.length - 1;
            int index = indexFor(key, t.length);
            for (Entry e = t[index]; e != null; e = t[index = (index + 1) & mask]) {
                if (e != TOMBSTONE && e.patternId() == key) {
                    t[index] = TOMBSTONE;
                    size--;
                    return e;
                }
            }
            return null;
        }

        void clear() {
            Arrays.fill(table, null);
            size = 0;
            used = 0;
        }

        int size() {
            return size;
        }

        int capacity() {
            return table.length;
        }

        private void rehash(int newCapacity) {
            Entry[] old = table;
            table = new Entry[newCapacity];
            size = 0;
            used = 0;
            for (Entry e : old) {
                if (e != null && e != TOMBSTONE) {
                    insertFresh(e);
                }
            }
        }

        /** Insert into a table known to have a free slot and no duplicate of this key. */
        private void insertFresh(Entry entry) {
            Entry[] t = table;
            int mask = t.length - 1;
            int index = indexFor(entry.patternId(), t.length);
            while (t[index] != null) {
                index = (index + 1) & mask;
            }
            t[index] = entry;
            size++;
            used++;
        }

        /** Bucket for a key at a given power-of-two capacity. Exposed for collision tests. */
        static int indexFor(int key, int capacity) {
            int h = key * 0x9E3779B9;
            return (h ^ (h >>> 16)) & (capacity - 1);
        }
    }
}
