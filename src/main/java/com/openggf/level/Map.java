package com.openggf.level;

import java.util.Arrays;

/**
 * Represents a map with multiple layers of tiles in a level.
 *
 * Copy-on-write: when gameplay code mutates this map, it calls
 * cowEnsureWritable(currentEpoch) to clone the internal data[] array
 * if needed. This protects snapshot-captured map data from
 * being clobbered by subsequent mutations.
 */
public class Map {
    private final int layers;
    private final int height;
    private final int width;
    private byte[] data;

    // Epoch at which data was last cloned. If currentEpoch has moved
    // forward, the next mutation will clone the array.
    private long lastTouchedEpoch = 0L;

    // Constructor with default data initialization (zeros)
    public Map(int layers, int width, int height) {
        this(layers, width, height, null);
    }

    // Constructor with specified data
    public Map(int layers, int width, int height, byte[] data) {
        this.layers = layers;
        this.height = height;
        this.width = width;
        int size = layers * width * height;

        this.data = new byte[size];
        if (data != null) {
            System.arraycopy(data, 0, this.data, 0, Math.min(data.length, size));
        } else {
            Arrays.fill(this.data, (byte) 0);  // Initialize with zeros if no data provided
        }
    }

    // Getters for dimensions and layer count
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getLayerCount() {
        return layers;
    }

    // Get value from specific layer, x, and y coordinates
    public byte getValue(int layer, int x, int y) {
        if (layer >= layers) {
            throw new IllegalArgumentException("Invalid map layer index");
        }
        if (x >= width || y >= height) {
            throw new IllegalArgumentException("Invalid map tile index");
        }
        return data[y * width * layers + layer * width + x];
    }

    // Set value in specific layer, x, and y coordinates
    public void setValue(int layer, int x, int y, byte value) {
        if (layer >= layers) {
            throw new IllegalArgumentException("Invalid map layer index");
        }
        if (x >= width || y >= height) {
            throw new IllegalArgumentException("Invalid map tile index");
        }
        data[y * width * layers + layer * width + x] = value;
    }

    // Return the underlying data array
    public byte[] getData() {
        return data;
    }

    /**
     * Ensures this map's data array is writable for the current epoch.
     * On first write per epoch, clones the array. Subsequent writes within the
     * same epoch reuse the cloned array.
     *
     * Called before any gameplay mutation to protect snapshot references.
     *
     * @param currentEpoch the current snapshot epoch from the level
     */
    public void cowEnsureWritable(long currentEpoch) {
        if (lastTouchedEpoch < currentEpoch) {
            data = data.clone();
            lastTouchedEpoch = currentEpoch;
        }
    }

    /**
     * Restores this map's data to a previously captured snapshot.
     * Used by snapshot.restore() to swap in the old data reference.
     * Resets lastTouchedEpoch so the next mutation triggers a fresh CoW.
     *
     * @param newData the snapshot data to restore
     */
    public void restoreData(byte[] newData) {
        this.data = newData;
        this.lastTouchedEpoch = 0L;
    }

    /**
     * Package-private accessor for the live data array (testing only).
     * Marked as @VisibleForTesting: use only in test assertions.
     *
     * @return the internal data byte array reference
     */
    byte[] dataArrayForTest() {
        return data;
    }
}
