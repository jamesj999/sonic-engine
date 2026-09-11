package com.openggf.level;

import com.openggf.graphics.PaletteView;

import java.util.Arrays;

/**
 * SEGA Palette implementation
 *
 * Provides a means by which to read/write SEGA palette data. This should be
 * portable across various SEGA Mega Drive
 * and Genesis titles, since it is tied closely to the palette implementation in
 * hardware.
 *
 * Once loaded, colors are stored in memory in RGB format, suitable for display
 * and manipulation on a PC.
 */
public class Palette implements PaletteView {
    public static final int BYTES_PER_COLOR = 2;
    public static final int PALETTE_SIZE = 16;
    public static final int PALETTE_SIZE_IN_ROM = BYTES_PER_COLOR * PALETTE_SIZE;

    // Inner Color class representing RGB values
    public static class Color {
        public byte r;
        public byte g;
        public byte b;

        public Color() {
        }

        public Color(byte r, byte g, byte b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        /** Returns red component as GL float (0.0 - 1.0) */
        public float rFloat() {
            return (r & 0xFF) / 255.0f;
        }

        /** Returns green component as GL float (0.0 - 1.0) */
        public float gFloat() {
            return (g & 0xFF) / 255.0f;
        }

        /** Returns blue component as GL float (0.0 - 1.0) */
        public float bFloat() {
            return (b & 0xFF) / 255.0f;
        }

        // Converts the color from Sega's format (char-based data)
        public void fromSegaFormat(byte[] bytes, int offset) {
            fromSegaFormat(((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF));
        }

        /** Decodes a packed Mega Drive color word without a temporary byte array. */
        public void fromSegaFormat(int word) {
            // Mega Drive palette format is 1 word (2 bytes) per color.
            // Format: 0000 BBB0 GGG0 RRR0 (bits 11-9=B, 7-5=G, 3-1=R, each 3 bits)
            // The LSB of each nibble is always 0 (values are shifted left by 1)
            // Stored Big-Endian in ROM:
            // Byte 0: 0000 BBB0
            // Byte 1: GGG0 RRR0

            // Extract 3-bit color values (shift right by 1 to remove the 0 bit)
            int r3 = (word >> 1) & 0x07;  // Bits 3-1 of byte 1
            int g3 = (word >> 5) & 0x07;  // Bits 7-5 of byte 1
            int b3 = (word >> 9) & 0x07;      // Bits 3-1 of byte 0

            // Scale 0-7 to 0-255: multiply by 255/7 ≈ 36.43
            // Use integer math: (value * 255 + 3) / 7 for proper rounding
            this.r = (byte) ((r3 * 255 + 3) / 7);
            this.g = (byte) ((g3 * 255 + 3) / 7);
            this.b = (byte) ((b3 * 255 + 3) / 7);
        }
    }

    public final Color[] colors;

    // Default constructor
    public Palette() {
        this.colors = new Color[PALETTE_SIZE];
        Arrays.setAll(this.colors, i -> new Color()); // Initialize palette with blank colors
    }

    // Load the palette from Sega format (char-based palette data)
    public void fromSegaFormat(byte[] bytes) {
        // We might receive more bytes than needed, but we only process PALETTE_SIZE
        // entries.
        if (bytes.length < PALETTE_SIZE_IN_ROM) {
            // Allow larger buffers, but warn on smaller?
            // For now, assume callers behave.
        }

        for (int index = 0; index < PALETTE_SIZE; index++) {
            int offset = index * BYTES_PER_COLOR;
            if ((offset + 1) < bytes.length) {
                colors[index].fromSegaFormat(bytes, offset);
            }
        }
    }

    // Get the number of colors in the palette
    public int getColorCount() {
        return PALETTE_SIZE;
    }

    /**
     * Copies color entries from another palette into this one for the given
     * index range (inclusive). Used for cross-game donation to merge character
     * colors into a host palette without overwriting universal colors.
     */
    public void mergeColorsFrom(Palette source, int fromIndex, int toIndex) {
        for (int i = fromIndex; i <= toIndex && i < PALETTE_SIZE; i++) {
            colors[i].r = source.colors[i].r;
            colors[i].g = source.colors[i].g;
            colors[i].b = source.colors[i].b;
        }
    }

    // Retrieve color by index
    public Color getColor(int index) {
        if (index >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Invalid palette index");
        }
        return colors[index];
    }

    @Override
    public byte red(int colorIndex) {
        return getColor(colorIndex).r;
    }

    @Override
    public byte green(int colorIndex) {
        return getColor(colorIndex).g;
    }

    @Override
    public byte blue(int colorIndex) {
        return getColor(colorIndex).b;
    }

    // Set a color by index
    public void setColor(int index, Color color) {
        if (index >= PALETTE_SIZE) {
            throw new IllegalArgumentException("Invalid palette index");
        }
        colors[index] = color;
    }

    /**
     * Compares color data with another palette. Returns true if all 16 colors
     * have identical RGB values.
     */
    public boolean dataEquals(Palette other) {
        if (other == null) return false;
        for (int i = 0; i < PALETTE_SIZE; i++) {
            if (colors[i].r != other.colors[i].r
                    || colors[i].g != other.colors[i].g
                    || colors[i].b != other.colors[i].b) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates a deep copy of this palette.
     * Each color is copied by value (new Color instances).
     */
    public Palette deepCopy() {
        Palette copy = new Palette();
        for (int i = 0; i < PALETTE_SIZE; i++) {
            copy.colors[i] = new Color(colors[i].r, colors[i].g, colors[i].b);
        }
        return copy;
    }
}
