package uk.co.jamesj999.sonic.level;

import java.util.Arrays;

/**
 * Represents a map with multiple layers of tiles in a level.
 */
public class Map {
    private final int layers;
    private final int height;
    private final int width;
    private final byte[] data;

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
        return data[(layer * width * height) + (y * width) + x];
    }

    // Set value in specific layer, x, and y coordinates
    public void setValue(int layer, int x, int y, byte value) {
        if (layer >= layers) {
            throw new IllegalArgumentException("Invalid map layer index");
        }
        if (x >= width || y >= height) {
            throw new IllegalArgumentException("Invalid map tile index");
        }
        data[(layer * width * height) + (y * width) + x] = value;
    }

    // Return the underlying data array
    public byte[] getData() {
        return data;
    }
}
