package com.openggf.level;

import com.openggf.graphics.GraphicsManager;
import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;

import java.util.Arrays;
import java.util.List;

/**
 * Base class for all Level implementations, providing shared fields and
 * accessor methods.
 *
 * <p>Each game-specific subclass (Sonic1Level, Sonic2Level, Sonic3kLevel)
 * extends this class and adds its own loading logic and game-specific methods.
 */
public abstract class AbstractLevel implements Level {

    protected static final int PALETTE_COUNT = 4;

    protected Palette[] palettes;
    protected Pattern[] patterns;
    protected Chunk[] chunks;
    protected Block[] blocks;
    protected SolidTile[] solidTiles;
    protected Map map;
    protected List<ObjectSpawn> objects;
    protected List<RingSpawn> rings;
    protected RingSpriteSheet ringSpriteSheet;
    protected final int zoneIndex;

    protected int patternCount;
    protected int chunkCount;
    protected int blockCount;
    protected int solidTileCount;
    protected int minX;
    protected int maxX;
    protected int minY;
    protected int maxY;

    // Snapshot epoch counter for copy-on-write tracking.
    // Incremented on each snapshot restore; used by Block/Chunk/Map to
    // detect when to clone internal arrays.
    private long snapshotEpoch = 0L;

    protected AbstractLevel(int zoneIndex) {
        this.zoneIndex = zoneIndex;
    }

    // ===== Level interface accessors =====

    @Override
    public int getPaletteCount() {
        return PALETTE_COUNT;
    }

    @Override
    public Palette getPalette(int index) {
        if (index >= PALETTE_COUNT) {
            throw new IllegalArgumentException("Invalid palette index: " + index);
        }
        return palettes[index];
    }

    @Override
    public void setPalette(int index, Palette palette) {
        if (index >= 0 && index < PALETTE_COUNT && palette != null) {
            palettes[index] = palette;
        }
    }

    @Override
    public int getPatternCount() {
        return patternCount;
    }

    @Override
    public Pattern getPattern(int index) {
        if (index >= patternCount) {
            throw new IllegalArgumentException("Invalid pattern index: " + index);
        }
        return patterns[index];
    }

    @Override
    public void ensurePatternCapacity(int minCount) {
        if (minCount <= patternCount) {
            return;
        }
        patterns = Arrays.copyOf(patterns, minCount);
        GraphicsManager graphicsMan = GameServices.graphics();
        for (int i = patternCount; i < minCount; i++) {
            patterns[i] = new Pattern();
            if (graphicsMan.isGlInitialized()) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
        }
        patternCount = minCount;
    }

    @Override
    public int getChunkCount() {
        return chunkCount;
    }

    @Override
    public Chunk getChunk(int index) {
        if (index >= chunkCount) {
            throw new IllegalArgumentException("Invalid chunk index: " + index);
        }
        return chunks[index];
    }

    @Override
    public int getBlockCount() {
        return blockCount;
    }

    @Override
    public Block getBlock(int index) {
        if (index >= blockCount) {
            throw new IllegalArgumentException("Invalid block index: " + index);
        }
        return blocks[index];
    }

    @Override
    public int getSolidTileCount() {
        return solidTileCount;
    }

    @Override
    public SolidTile getSolidTile(int index) {
        if (index >= solidTileCount) {
            throw new IllegalArgumentException("Invalid solid tile index: " + index);
        }
        return solidTiles[index];
    }

    @Override
    public Map getMap() {
        return map;
    }

    @Override
    public List<ObjectSpawn> getObjects() {
        return objects;
    }

    @Override
    public List<RingSpawn> getRings() {
        return rings;
    }

    @Override
    public RingSpriteSheet getRingSpriteSheet() {
        return ringSpriteSheet;
    }

    @Override
    public int getMinX() {
        return minX;
    }

    @Override
    public int getMaxX() {
        return maxX;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public int getZoneIndex() {
        return zoneIndex;
    }

    // ===== Snapshot epoch API =====

    /** Returns the current snapshot epoch for copy-on-write tracking. */
    public long currentEpoch() {
        return snapshotEpoch;
    }

    /** Increments the snapshot epoch. Called after restoring a snapshot. */
    public void bumpEpoch() {
        snapshotEpoch++;
    }

    /** Returns a reference to the live blocks array. */
    public Block[] blocksReference() {
        return blocks;
    }

    /** Returns a reference to the live chunks array. */
    public Chunk[] chunksReference() {
        return chunks;
    }

    /** Replaces the live blocks array (used by snapshot restore). */
    public void replaceBlocks(Block[] newBlocks) {
        this.blocks = newBlocks;
        this.blockCount = newBlocks.length;
    }

    /** Replaces the live chunks array (used by snapshot restore). */
    public void replaceChunks(Chunk[] newChunks) {
        this.chunks = newChunks;
        this.chunkCount = newChunks.length;
    }

}
