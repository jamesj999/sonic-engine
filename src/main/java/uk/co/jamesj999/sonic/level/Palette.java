package uk.co.jamesj999.sonic.level;

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
public class Palette {
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

        // Converts the color from Sega's format (char-based data)
        public void fromSegaFormat(byte[] bytes, int offset) {
            // Mega Drive palette format is 1 word (2 bytes) per color.
            // Format: 0000 BBB0 GGG0 RRR0 (bits 11-9=B, 7-5=G, 3-1=R, each 3 bits)
            // The LSB of each nibble is always 0 (values are shifted left by 1)
            // Stored Big-Endian in ROM:
            // Byte 0: 0000 BBB0
            // Byte 1: GGG0 RRR0

            // Extract 3-bit color values (shift right by 1 to remove the 0 bit)
            int r3 = (bytes[offset + 1] >> 1) & 0x07;  // Bits 3-1 of byte 1
            int g3 = (bytes[offset + 1] >> 5) & 0x07;  // Bits 7-5 of byte 1
            int b3 = (bytes[offset] >> 1) & 0x07;      // Bits 3-1 of byte 0

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
