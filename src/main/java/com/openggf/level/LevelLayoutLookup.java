package com.openggf.level;

/**
 * Resolves blocks, chunk descriptors and pattern descriptors from world pixel
 * coordinates for one {@link LevelManager}.
 *
 * <p>These lookups are the layout side of the manager: they translate a world
 * position into the ROM's Level_Layout row/column indices, apply the layer's
 * wrap or clamp rule, and walk block -> chunk -> pattern. They are called from
 * the physics sensors, the object terrain helpers and the tilemap builders, so
 * they form a cohesive unit that does not belong to the manager's level-load
 * and mode orchestration. The manager keeps thin delegating entry points; the
 * geometry state they read still lives there and is re-derived on every level
 * load.
 */
final class LevelLayoutLookup {

    private final LevelManager owner;

    LevelLayoutLookup(LevelManager owner) {
        this.owner = owner;
    }

    private int layoutLookupY(int y, int levelHeight) {
        int mask = owner.collisionLayoutYMask;
        if (mask <= 0) {
            // Mask not modelled for this game: retain the pre-existing clamp/wrap.
            if (owner.verticalWrapEnabled) {
                return BlockGridIndexer.wrapCoordinate(y, levelHeight);
            }
            return (y < 0 || y >= levelHeight) ? -1 : y;
        }
        int wrapped = y & mask;
        return wrapped >= levelHeight ? -1 : wrapped;
    }

    Block getBlockAtPosition(byte layer, int x, int y) {
        if (owner.level == null || owner.level.getMap() == null) {
            LevelManager.LOGGER.warning("Level or Map is not initialized.");
            return null;
        }
        return blockAt(owner.level,
                owner.getCachedLayerWidthPx(layer), owner.getCachedLayerHeightPx(layer),
                owner.blockPixelSize, owner.verticalWrapEnabled, layer, x, y);
    }

    /**
     * Block lookup for a level that is not (yet) the manager's current level,
     * using the same wrap/clamp rules as {@link #getBlockAtPosition}.
     */
    static Block blockAt(Level level, LevelGeometry geometry, boolean verticalWrapEnabled,
                         byte layer, int x, int y) {
        if (level == null || level.getMap() == null) {
            return null;
        }
        int levelWidth = layer == 0 ? geometry.fgWidthPx() : geometry.bgWidthPx();
        int levelHeight = layer == 0 ? geometry.fgHeightPx() : geometry.bgHeightPx();
        return blockAt(level, levelWidth, levelHeight, geometry.blockPixelSize(),
                verticalWrapEnabled, layer, x, y);
    }

    private static Block blockAt(Level level, int levelWidth, int levelHeight, int blockPixelSize,
                                 boolean verticalWrapEnabled, byte layer, int x, int y) {
        // Handle wrapping for X
        int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;

        // Handle wrapping for Y
        int wrappedY = y;
        if (layer == 1) {
            // Background loops vertically
            wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
        } else if (verticalWrapEnabled) {
            // ROM: LZ3/SBZ2 — FG also wraps vertically
            wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
        } else {
            // Foreground Clamps
            if (wrappedY < 0 || wrappedY >= levelHeight)
                return null;
        }

        Map map = level.getMap();
        int mapX = wrappedX / blockPixelSize;
        int mapY = wrappedY / blockPixelSize;

        byte value = map.getValue(layer, mapX, mapY);

        // Mask the value to treat the byte as unsigned
        int blockIndex = value & 0xFF;

        if (blockIndex >= level.getBlockCount()) {
            return null;
        }

        Block block = level.getBlock(blockIndex);
        if (block == null) {
            LevelManager.LOGGER.warning("Block at index " + blockIndex + " is null.");
        }

        return block;
    }

    int getBlockIdAt(int x, int y) {
        if (owner.level == null || owner.level.getMap() == null) {
            return -1;
        }
        int levelWidth = owner.cachedFgWidthPx;
        int levelHeight = owner.cachedFgHeightPx;
        if (levelWidth <= 0 || levelHeight <= 0 || owner.blockPixelSize <= 0) {
            return -1;
        }
        int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;
        int wrappedY = y;
        if (owner.verticalWrapEnabled) {
            wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
        } else if (wrappedY < 0 || wrappedY >= levelHeight) {
            return -1;
        }
        Map map = owner.level.getMap();
        int mapX = wrappedX / owner.blockPixelSize;
        int mapY = wrappedY / owner.blockPixelSize;
        return map.getValue(0, mapX, mapY) & 0xFF;
    }

    ChunkDesc getChunkDescAt(byte layer, int x, int y) {
        if (owner.level == null || owner.level.getMap() == null) {
            return null;
        }

        int levelWidth = owner.getCachedLayerWidthPx(layer);
        int levelHeight = owner.getCachedLayerHeightPx(layer);
        if (levelWidth <= 0 || levelHeight <= 0) {
            return null;
        }

        // Wrap X (always wraps)
        int wrappedX = BlockGridIndexer.wrapCoordinate(x, levelWidth);

        // Wrap Y the way the ROM's layout row index does (see layoutLookupY).
        int wrappedY;
        if (layer == 1) {
            // Background loops vertically
            wrappedY = BlockGridIndexer.wrapCoordinate(y, levelHeight);
        } else {
            wrappedY = layoutLookupY(y, levelHeight);
            if (wrappedY < 0) {
                return null;
            }
        }

        // Block lookup (inlined from getBlockAtPosition to reuse wrappedX/wrappedY).
        Map map = owner.level.getMap();
        int mapX = owner.blockGrid.blockIndex(wrappedX);
        int mapY = owner.blockGrid.blockIndex(wrappedY);

        byte value = map.getValue(layer, mapX, mapY);
        int blockIndex = value & 0xFF;

        if (blockIndex >= owner.level.getBlockCount()) {
            return null;
        }

        Block block = owner.level.getBlock(blockIndex);
        if (block == null) {
            return null;
        }

        // Intra-block position (reuses already-wrapped coordinates)
        return block.getChunkDesc(owner.blockGrid.blockLocal(wrappedX) / LevelConstants.CHUNK_WIDTH,
                owner.blockGrid.blockLocal(wrappedY) / LevelConstants.CHUNK_HEIGHT);
    }

    ChunkDesc getChunkDescAt(byte layer, int x, int y, boolean loopLowPlane) {
        if (!loopLowPlane || layer != 0) {
            return getChunkDescAt(layer, x, y);
        }

        // Loop low plane: resolve collision block via Level.resolveCollisionBlockIndex
        if (owner.level == null || owner.level.getMap() == null) {
            return null;
        }

        int levelWidth = owner.getCachedLayerWidthPx((byte) 0);
        int levelHeight = owner.getCachedLayerHeightPx((byte) 0);
        if (levelWidth <= 0 || levelHeight <= 0) {
            return null;
        }
        int wrappedX = BlockGridIndexer.wrapCoordinate(x, levelWidth);
        int wrappedY = layoutLookupY(y, levelHeight);
        if (wrappedY < 0) {
            return null;
        }

        Map map = owner.level.getMap();
        int mapX = owner.blockGrid.blockIndex(wrappedX);
        int mapY = owner.blockGrid.blockIndex(wrappedY);

        int rawBlockIndex = map.getValue(0, mapX, mapY) & 0xFF;
        int resolvedIndex = owner.level.resolveCollisionBlockIndex(rawBlockIndex, mapX, mapY);

        if (resolvedIndex >= owner.level.getBlockCount()) {
            return null;
        }

        Block block = owner.level.getBlock(resolvedIndex);
        if (block == null) {
            return null;
        }

        return block.getChunkDesc(
                owner.blockGrid.blockLocal(wrappedX) / LevelConstants.CHUNK_WIDTH,
                owner.blockGrid.blockLocal(wrappedY) / LevelConstants.CHUNK_HEIGHT);
    }

    int getTileDescriptorAtWorld(byte layer, int worldX, int worldY) {
        if (owner.level == null || owner.level.getMap() == null) {
            return 0;
        }

        int levelWidth = owner.getLayerLevelWidthPx(layer);
        int levelHeight = owner.getLayerLevelHeightPx(layer);
        if (levelWidth <= 0 || levelHeight <= 0) {
            return 0;
        }

        int wrappedX = Math.floorMod(worldX, levelWidth);
        int wrappedY = worldY;
        if (layer == 1 || owner.verticalWrapEnabled) {
            wrappedY = Math.floorMod(worldY, levelHeight);
        } else if (wrappedY < 0 || wrappedY >= levelHeight) {
            return 0;
        }

        Block block = getBlockAtPosition(layer, wrappedX, wrappedY);
        if (block == null) {
            return 0;
        }

        int xBlockBit = (wrappedX % owner.blockPixelSize) / LevelConstants.CHUNK_WIDTH;
        int yBlockBit = (wrappedY % owner.blockPixelSize) / LevelConstants.CHUNK_HEIGHT;
        ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);
        if (chunkDesc == null) {
            return 0;
        }

        int chunkIndex = chunkDesc.getChunkIndex();
        if (chunkIndex < 0 || chunkIndex >= owner.level.getChunkCount()) {
            return 0;
        }

        Chunk chunk = owner.level.getChunk(chunkIndex);
        if (chunk == null) {
            return 0;
        }

        int tileX = (wrappedX & (LevelConstants.CHUNK_WIDTH - 1)) / Pattern.PATTERN_WIDTH;
        int tileY = (wrappedY & (LevelConstants.CHUNK_HEIGHT - 1)) / Pattern.PATTERN_HEIGHT;
        int logicalX = chunkDesc.getHFlip() ? 1 - tileX : tileX;
        int logicalY = chunkDesc.getVFlip() ? 1 - tileY : tileY;
        PatternDesc patternDesc = chunk.getPatternDesc(logicalX, logicalY);
        if (patternDesc == null) {
            return 0;
        }

        int descriptor = patternDesc.get();
        if (chunkDesc.getHFlip()) {
            descriptor ^= 0x800;
        }
        if (chunkDesc.getVFlip()) {
            descriptor ^= 0x1000;
        }
        return descriptor & 0xFFFF;
    }
}
