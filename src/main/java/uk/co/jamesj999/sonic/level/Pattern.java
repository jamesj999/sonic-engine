package uk.co.jamesj999.sonic.level;

import java.util.Arrays;

/**
 * SEGA Pattern implementation
 *
 * Provides a means by which to read/write SEGA palette data. This should be portable across various SEGA Mega Drive
 * and Genesis titles, since it is tied closely to the palette implementation in hardware.
 *
 * Patterns are 8x8 pixels in size. In a ROM, two pixels are stored per byte (each pixel is one nibble). The
 * pixel value is a palette column index. Fifteen colors may be referenced, with zero used to mark transparent
 * pixels.
 *
 * Once loaded into memory, pixels are stored 1-per-byte (i.e. as a byte index into a palette).
 */
public class Pattern {
    public static final int PATTERN_WIDTH = 8;
    public static final int PATTERN_HEIGHT = 8;
    public static final int PIXELS_PER_BYTE = 2;
    public static final int PATTERN_SIZE_IN_MEM = PATTERN_WIDTH * PATTERN_HEIGHT;
    public static final int PATTERN_SIZE_IN_ROM = PATTERN_SIZE_IN_MEM / PIXELS_PER_BYTE;

    private final byte[] pixels;

    // Default constructor
    public Pattern() {
        this.pixels = new byte[PATTERN_SIZE_IN_MEM];
        Arrays.fill(this.pixels, (byte) 0);  // Fill with zeros (transparent pixels)
    }

    // Load pattern from Sega format (2 pixels per byte, 4 bits per pixel)
    public void fromSegaFormat(byte[] buffer) {
        if (buffer.length != PATTERN_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match pattern size in ROM");
        }

        int bufferPos = 0;
        for (int row = 0; row < PATTERN_HEIGHT; row++) {
            for (int col = 0; col < PATTERN_WIDTH; col += 2) {
                pixels[row * PATTERN_WIDTH + col] = (byte) ((buffer[bufferPos] >> 4) & 0x0F);
                pixels[row * PATTERN_WIDTH + col + 1] = (byte) (buffer[bufferPos] & 0x0F);
                bufferPos++;
            }
        }
    }

    // Get pixel value at x, y position
    public byte getPixel(int x, int y) {
        if (x < 0 || x >= PATTERN_WIDTH || y < 0 || y >= PATTERN_HEIGHT) {
            throw new IllegalArgumentException("Invalid pixel coordinates");
        }
        return pixels[y * PATTERN_WIDTH + x];
    }

    // Set pixel value at x, y position
    public void setPixel(int x, int y, byte value) {
        if (x < 0 || x >= PATTERN_WIDTH || y < 0 || y >= PATTERN_HEIGHT) {
            throw new IllegalArgumentException("Invalid pixel coordinates");
        }
        pixels[y * PATTERN_WIDTH + x] = value;
    }
}
