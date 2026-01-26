package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.level.Pattern;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Texture atlas for 8x8 indexed patterns.
 * Stores patterns across one or more GL textures to avoid per-tile texture binds.
 */
public class PatternAtlas {
    private static final Logger LOGGER = Logger.getLogger(PatternAtlas.class.getName());

    public static final int TILE_SIZE = Pattern.PATTERN_WIDTH;
    private static final float UV_INSET_PIXELS = 0.01f;
    private static final int MAX_ATLASES = 2;

    private final int atlasWidth;
    private final int atlasHeight;
    private final int tilesPerRow;
    private final int tilesPerColumn;
    private final int maxSlots;

    private final Map<Integer, Entry> entries = new HashMap<>();
    private final List<AtlasPage> pages = new ArrayList<>();
    private final ByteBuffer patternUploadBuffer;
    private boolean initialized = false;

    public PatternAtlas(int atlasWidth, int atlasHeight) {
        if (atlasWidth % TILE_SIZE != 0 || atlasHeight % TILE_SIZE != 0) {
            throw new IllegalArgumentException("Atlas size must be divisible by tile size");
        }
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.tilesPerRow = atlasWidth / TILE_SIZE;
        this.tilesPerColumn = atlasHeight / TILE_SIZE;
        this.maxSlots = tilesPerRow * tilesPerColumn;
        this.patternUploadBuffer = GLBuffers.newDirectByteBuffer(TILE_SIZE * TILE_SIZE);
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

    public void init(GL2 gl) {
        if (initialized || gl == null) {
            return;
        }
        if (pages.isEmpty()) {
            pages.add(createPage(gl, 0));
        } else {
            for (AtlasPage page : pages) {
                if (page.textureId() == 0) {
                    page.setTextureId(createTexture(gl));
                }
            }
        }
        initialized = true;
    }

    public Entry cachePattern(GL2 gl, Pattern pattern, int patternId) {
        Entry entry = ensureEntry(gl, patternId);
        if (entry == null) {
            return null;
        }
        if (gl != null && initialized && pattern != null) {
            uploadPattern(gl, pattern, entry);
        }
        return entry;
    }

    public Entry updatePattern(GL2 gl, Pattern pattern, int patternId) {
        return cachePattern(gl, pattern, patternId);
    }

    public Entry getEntry(int patternId) {
        return entries.get(patternId);
    }

    /**
     * Remove a pattern entry from the atlas.
     * This makes getEntry() return null for this pattern ID, causing
     * the renderer to skip it. The atlas slot is not reclaimed.
     *
     * @param patternId The pattern ID to remove
     * @return true if the pattern was removed, false if it wasn't cached
     */
    public boolean removeEntry(int patternId) {
        return entries.remove(patternId) != null;
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
        if (entries.containsKey(aliasId)) {
            return false;
        }
        Entry target = entries.get(targetId);
        if (target == null) {
            return false;
        }
        // Create a new entry with the alias ID but same atlas coordinates as target
        Entry alias = new Entry(aliasId, target.atlasIndex(), target.slot(),
                target.tileX(), target.tileY(), target.u0(), target.v0(), target.u1(), target.v1());
        entries.put(aliasId, alias);
        return true;
    }

    public int getAtlasCount() {
        return pages.size();
    }

    public void cleanup(GL2 gl) {
        if (gl != null) {
            for (AtlasPage page : pages) {
                if (page.textureId() != 0) {
                    gl.glDeleteTextures(1, new int[] { page.textureId() }, 0);
                }
            }
        }
        initialized = false;
        entries.clear();
        pages.clear();
    }

    private Entry ensureEntry(GL2 gl, int patternId) {
        Entry existing = entries.get(patternId);
        if (existing != null) {
            return existing;
        }

        AtlasPage page = getOrCreatePage(gl);
        if (page == null) {
            LOGGER.warning("Pattern atlas capacity exceeded; patternId=" + patternId);
            return null;
        }

        int slot = page.allocateSlot();
        int tileX = slot % tilesPerRow;
        int tileY = slot / tilesPerRow;

        int pixelX = tileX * TILE_SIZE;
        int pixelY = tileY * TILE_SIZE;

        float u0 = (pixelX + UV_INSET_PIXELS) / (float) atlasWidth;
        float u1 = (pixelX + TILE_SIZE - UV_INSET_PIXELS) / (float) atlasWidth;
        float v0 = (pixelY + UV_INSET_PIXELS) / (float) atlasHeight;
        float v1 = (pixelY + TILE_SIZE - UV_INSET_PIXELS) / (float) atlasHeight;

        Entry entry = new Entry(patternId, page.atlasIndex(), slot, tileX, tileY, u0, v0, u1, v1);
        entries.put(patternId, entry);
        return entry;
    }

    private void uploadPattern(GL2 gl, Pattern pattern, Entry entry) {
        ByteBuffer patternBuffer = patternUploadBuffer;
        patternBuffer.clear();
        for (int col = 0; col < TILE_SIZE; col++) {
            for (int row = 0; row < TILE_SIZE; row++) {
                byte colorIndex = pattern.getPixel(row, col);
                patternBuffer.put(colorIndex);
            }
        }
        patternBuffer.flip();

        int pixelX = entry.tileX() * TILE_SIZE;
        int pixelY = entry.tileY() * TILE_SIZE;

        int textureId = getTextureId(entry.atlasIndex());
        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexSubImage2D(GL2.GL_TEXTURE_2D, 0, pixelX, pixelY, TILE_SIZE, TILE_SIZE,
                GL2.GL_RED, GL2.GL_UNSIGNED_BYTE, patternBuffer);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
    }

    private AtlasPage getOrCreatePage(GL2 gl) {
        if (pages.isEmpty()) {
            pages.add(createPage(gl, 0));
        }
        AtlasPage current = pages.get(pages.size() - 1);
        if (current.hasCapacity()) {
            return current;
        }
        if (pages.size() >= MAX_ATLASES) {
            return null;
        }
        AtlasPage next = createPage(gl, pages.size());
        pages.add(next);
        return next;
    }

    private AtlasPage createPage(GL2 gl, int atlasIndex) {
        int textureId = gl != null ? createTexture(gl) : 0;
        return new AtlasPage(atlasIndex, textureId, maxSlots);
    }

    private int createTexture(GL2 gl) {
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        int textureId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RED, atlasWidth, atlasHeight, 0,
                GL2.GL_RED, GL2.GL_UNSIGNED_BYTE, null);

        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        return textureId;
    }

    private static final class AtlasPage {
        private final int atlasIndex;
        private int textureId;
        private final int maxSlots;
        private int nextSlot;

        private AtlasPage(int atlasIndex, int textureId, int maxSlots) {
            this.atlasIndex = atlasIndex;
            this.textureId = textureId;
            this.maxSlots = maxSlots;
        }

        private boolean hasCapacity() {
            return nextSlot < maxSlots;
        }

        private int allocateSlot() {
            return nextSlot++;
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
}
