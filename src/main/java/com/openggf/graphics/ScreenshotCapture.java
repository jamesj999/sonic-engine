package com.openggf.graphics;

import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11.*;

/**
 * Utility class for framebuffer capture and image comparison.
 * Used by visual regression testing to capture screenshots and compare them against reference images.
 */
public final class ScreenshotCapture {

    private ScreenshotCapture() {
        // Utility class
    }

    /**
     * Result of comparing two images.
     */
    public record ComparisonResult(
            boolean matched,
            int diffCount,
            int maxDiff,
            int firstDiffX,
            int firstDiffY
    ) {
        public static ComparisonResult success() {
            return new ComparisonResult(true, 0, 0, -1, -1);
        }

        public static ComparisonResult failure(int diffCount, int maxDiff, int firstDiffX, int firstDiffY) {
            return new ComparisonResult(false, diffCount, maxDiff, firstDiffX, firstDiffY);
        }
    }

    /**
     * Capture the current OpenGL framebuffer as an RGBA image.
     * Reads from the back buffer and flips the Y-axis (OpenGL origin is bottom-left).
     *
     * @param width  Width of the capture area in pixels
     * @param height Height of the capture area in pixels
     * @return An image containing the captured framebuffer contents
     */
    public static RgbaImage captureFramebuffer(int width, int height) {
        return captureFramebufferRegion(0, 0, width, height);
    }

    /**
     * Capture a specific region of the current OpenGL framebuffer as an RGBA image.
     * Reads from the back buffer and flips the Y-axis (OpenGL origin is bottom-left).
     *
     * @param x      Left edge of the capture area in framebuffer pixels
     * @param y      Bottom edge of the capture area in framebuffer pixels
     * @param width  Width of the capture area in pixels
     * @param height Height of the capture area in pixels
     * @return An image containing the captured framebuffer region
     */
    public static RgbaImage captureFramebufferRegion(int startX, int startY, int width, int height) {
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4);

        try {
            glReadBuffer(GL_BACK);
            glReadPixels(startX, startY, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            RgbaImage image = new RgbaImage(width, height, new int[width * height]);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int srcY = height - 1 - y;
                    int srcIndex = (srcY * width + x) * 4;

                    int r = buffer.get(srcIndex) & 0xFF;
                    int g = buffer.get(srcIndex + 1) & 0xFF;
                    int b = buffer.get(srcIndex + 2) & 0xFF;
                    int a = buffer.get(srcIndex + 3) & 0xFF;

                    image.setArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            return image;
        } finally {
            // Free the native memory
            MemoryUtil.memFree(buffer);
        }
    }

    /**
     * Compare two images for equality within a per-channel tolerance.
     *
     * @param reference The reference (expected) image
     * @param current   The current (actual) image
     * @param tolerance Per-channel tolerance for pixel comparison (0 = exact match required)
     * @return ComparisonResult with match status and diff details
     */
    public static ComparisonResult imagesMatch(RgbaImage reference, RgbaImage current, int tolerance) {
        if (reference.width() != current.width() || reference.height() != current.height()) {
            return ComparisonResult.failure(
                    reference.width() * reference.height(),
                    255,
                    0, 0
            );
        }

        int diffCount = 0;
        int maxDiff = 0;
        int firstDiffX = -1;
        int firstDiffY = -1;

        for (int y = 0; y < reference.height(); y++) {
            for (int x = 0; x < reference.width(); x++) {
                int refPixel = reference.argb(x, y);
                int curPixel = current.argb(x, y);

                // Extract ARGB components
                int refA = (refPixel >> 24) & 0xFF;
                int refR = (refPixel >> 16) & 0xFF;
                int refG = (refPixel >> 8) & 0xFF;
                int refB = refPixel & 0xFF;

                int curA = (curPixel >> 24) & 0xFF;
                int curR = (curPixel >> 16) & 0xFF;
                int curG = (curPixel >> 8) & 0xFF;
                int curB = curPixel & 0xFF;

                // Calculate per-channel differences
                int diffA = Math.abs(refA - curA);
                int diffR = Math.abs(refR - curR);
                int diffG = Math.abs(refG - curG);
                int diffB = Math.abs(refB - curB);

                int pixelMaxDiff = Math.max(Math.max(diffA, diffR), Math.max(diffG, diffB));

                if (pixelMaxDiff > tolerance) {
                    diffCount++;
                    if (pixelMaxDiff > maxDiff) {
                        maxDiff = pixelMaxDiff;
                    }
                    if (firstDiffX < 0) {
                        firstDiffX = x;
                        firstDiffY = y;
                    }
                }
            }
        }

        if (diffCount > 0) {
            return ComparisonResult.failure(diffCount, maxDiff, firstDiffX, firstDiffY);
        }
        return ComparisonResult.success();
    }

    /**
     * Save an RGBA image as a PNG file.
     *
     * @param image The image to save
     * @param path  The file path to save to
     * @throws IOException If the file cannot be written
     */
    // STB's flip-on-write setting is process-global. Serialize every PNG write
    // through this entry point, including asynchronous interactive screenshots.
    public static synchronized void savePNG(RgbaImage image, Path path) throws IOException {
        ByteBuffer rgba = toRgbaByteBuffer(image);
        try {
            STBImageWrite.stbi_flip_vertically_on_write(false);
            if (!STBImageWrite.stbi_write_png(path.toString(), image.width(), image.height(), 4, rgba, image.width() * 4)) {
                throw new IOException("Failed to write PNG: " + path);
            }
        } finally {
            MemoryUtil.memFree(rgba);
        }
    }

    /**
     * Load a PNG file as an RGBA image.
     *
     * @param path The file path to load from
     * @return The loaded image
     * @throws IOException If the file cannot be read
     */
    public static RgbaImage loadPNG(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
        raw.put(bytes).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer decoded = STBImage.stbi_load_from_memory(raw, width, height, channels, 4);
            if (decoded == null) {
                throw new IOException("Failed to decode PNG: " + path + " (" + STBImage.stbi_failure_reason() + ")");
            }
            try {
                return fromRgbaByteBuffer(decoded, width.get(0), height.get(0));
            } finally {
                STBImage.stbi_image_free(decoded);
            }
        } finally {
            MemoryUtil.memFree(raw);
        }
    }

    /**
     * Create a visual diff image showing the differences between two images.
     * Pixels that match are dimmed, pixels that differ are highlighted in red.
     *
     * @param reference The reference image
     * @param current   The current image
     * @param tolerance Per-channel tolerance
     * @return A new image highlighting the differences
     */
    public static RgbaImage createDiffImage(RgbaImage reference, RgbaImage current, int tolerance) {
        int width = Math.max(reference.width(), current.width());
        int height = Math.max(reference.height(), current.height());
        RgbaImage diff = new RgbaImage(width, height, new int[width * height]);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= reference.width() || y >= reference.height() ||
                        x >= current.width() || y >= current.height()) {
                    diff.setArgb(x, y, 0xFFFF0000);
                    continue;
                }

                int refPixel = reference.argb(x, y);
                int curPixel = current.argb(x, y);

                int refR = (refPixel >> 16) & 0xFF;
                int refG = (refPixel >> 8) & 0xFF;
                int refB = refPixel & 0xFF;

                int curR = (curPixel >> 16) & 0xFF;
                int curG = (curPixel >> 8) & 0xFF;
                int curB = curPixel & 0xFF;

                int diffR = Math.abs(refR - curR);
                int diffG = Math.abs(refG - curG);
                int diffB = Math.abs(refB - curB);

                int maxDiff = Math.max(Math.max(diffR, diffG), diffB);

                if (maxDiff > tolerance) {
                    int intensity = Math.min(255, 128 + maxDiff);
                    diff.setArgb(x, y, 0xFF000000 | (intensity << 16));
                } else {
                    int dimR = curR / 4;
                    int dimG = curG / 4;
                    int dimB = curB / 4;
                    diff.setArgb(x, y, 0xFF000000 | (dimR << 16) | (dimG << 8) | dimB);
                }
            }
        }

        return diff;
    }

    /**
     * Capture the framebuffer and save directly to a PNG file using STBImageWrite.
     * This method does NOT depend on AWT and is safe for GraalVM native-image builds.
     *
     * @param width  Width of the capture area in pixels
     * @param height Height of the capture area in pixels
     * @param path   The file path to save to
     * @throws IOException If the file cannot be written
     */
    public static void captureAndSavePNG(int width, int height, Path path) throws IOException {
        savePNG(captureFramebuffer(width, height), path);
    }

    private static ByteBuffer toRgbaByteBuffer(RgbaImage image) {
        ByteBuffer buffer = MemoryUtil.memAlloc(image.width() * image.height() * 4);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int argb = image.argb(x, y);
                buffer.put((byte) ((argb >> 16) & 0xFF));
                buffer.put((byte) ((argb >> 8) & 0xFF));
                buffer.put((byte) (argb & 0xFF));
                buffer.put((byte) ((argb >> 24) & 0xFF));
            }
        }
        buffer.flip();
        return buffer;
    }

    private static RgbaImage fromRgbaByteBuffer(ByteBuffer buffer, int width, int height) {
        RgbaImage image = new RgbaImage(width, height, new int[width * height]);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 4;
                int r = buffer.get(index) & 0xFF;
                int g = buffer.get(index + 1) & 0xFF;
                int b = buffer.get(index + 2) & 0xFF;
                int a = buffer.get(index + 3) & 0xFF;
                image.setArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }
}
