package com.openggf.graphics;

/**
 * Screen-fixed nametable replacing the foreground from {@code top} down.
 * The owner keeps the descriptor array stable for the duration of a draw.
 * Entries are native 16-bit pattern descriptors, in row-major order from screen (0,0).
 */
public record ForegroundWindow(int widthTiles, int heightTiles, int top, int[] descriptors) {
    public ForegroundWindow {
        if (widthTiles <= 0 || heightTiles <= 0 || top < 0
                || descriptors == null || descriptors.length != widthTiles * heightTiles) {
            throw new IllegalArgumentException("Invalid foreground window nametable");
        }
    }
}
