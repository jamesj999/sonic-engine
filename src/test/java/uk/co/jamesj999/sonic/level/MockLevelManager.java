package uk.co.jamesj999.sonic.level;

import java.util.HashMap;
import java.util.Map;

public class MockLevelManager extends LevelManager {

    private final Map<String, ChunkDesc> chunkDescMap = new HashMap<>();
    private final Map<ChunkDesc, SolidTile> solidTileMap = new HashMap<>();

    public MockLevelManager() {
        super();
    }

    public void registerChunkDesc(byte layer, int x, int y, ChunkDesc desc) {
        chunkDescMap.put(getKey(layer, x, y), desc);
    }

    public void registerSolidTile(ChunkDesc desc, SolidTile tile) {
        solidTileMap.put(desc, tile);
    }

    @Override
    public ChunkDesc getChunkDescAt(byte layer, int x, int y) {
        // Round x, y to chunk/block coords if needed?
        // GroundSensor calls with pixel coordinates.
        // LevelManager.getChunkDescAt does coordinate wrapping.
        // For testing simplicity, we can just key by the exact coordinates passed or use a simpler mapping.
        // But GroundSensor might query slightly different coordinates (e.g. x-1, x+1).
        // Let's assume the test calls with specific coordinates.
        // Or better, let's map by "Block coordinate" or just return a default if not found.

        // Let's implement a simple region check or just direct key matching.
        // Since we control the test, we can ensure we query the exact spot.
        return chunkDescMap.get(getKey(layer, x, y));
    }

    @Override
    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, byte layer) {
        return solidTileMap.get(chunkDesc);
    }

    private String getKey(byte layer, int x, int y) {
        return layer + ":" + x + ":" + y;
    }
}
