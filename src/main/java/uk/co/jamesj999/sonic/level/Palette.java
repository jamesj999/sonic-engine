package uk.co.jamesj999.sonic.level;

import java.util.Arrays;

/**
 * SEGA Palette implementation
 *
 * Provides a means by which to read/write SEGA palette data. This should be portable across various SEGA Mega Drive
 * and Genesis titles, since it is tied closely to the palette implementation in hardware.
 *
 * Once loaded, colors are stored in memory in RGB format, suitable for display and manipulation on a PC.
 */
public class Palette {
    public static final int BYTES_PER_COLOR = 2;
    public static final int PALETTE_SIZE = 16;
    public static final int PALETTE_SIZE_IN_ROM = BYTES_PER_COLOR * PALETTE_SIZE;

    // Inner Color class representing RGB values
    public static class Color {
        public byte r;
        public byte g;
        public byte b;

        // Converts the color from Sega's format (char-based data)
        public void fromSegaFormat(byte[] bytes) {
            this.r = (byte) ((bytes[1] & 0x0F) * 0x10);  // Red: lower nibble of byte 1
            this.g = (byte) (bytes[1] & 0xF0);           // Green: upper nibble of byte 1
            this.b = (byte) (bytes[0] * 0x10);           // Blue: byte 0 * 16
        }
    }

    private final Color[] colors;

    // Default constructor
    public Palette() {
        this.colors = new Color[PALETTE_SIZE];
        Arrays.setAll(this.colors, i -> new Color());  // Initialize palette with blank colors
    }

    // Load the palette from Sega format (char-based palette data)
    public void fromSegaFormat(byte[] bytes) {
        if (bytes.length != PALETTE_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match palette size in ROM");
        }

        for (int index = 0; index < PALETTE_SIZE; index++) {
            colors[index].fromSegaFormat(Arrays.copyOfRange(bytes, index * BYTES_PER_COLOR, (index + 1) * BYTES_PER_COLOR));
        }
    }

    // Get the number of colors in the palette
    public int getColorCount() {
        return PALETTE_SIZE;
    }

    // Retrieve color by index
    public Color getColor(int index) {
        if (index >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Invalid palette index");
        }
        return colors[index];
    }

    // Set a color by index
    public void setColor(int index, Color color) {
        if (index >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Invalid palette index");
        }
        colors[index] = color;
    }
}
