package com.openggf.game.palette;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;

/**
 * Small shared helper for systems that can submit normal-palette writes through
 * {@link PaletteOwnershipRegistry}, while preserving direct-write fallback for
 * older contexts that do not own a registry yet.
 */
public final class PaletteWriteSupport {
    private PaletteWriteSupport() {
    }

    public static void applyColor(PaletteOwnershipRegistry registry,
                                  Level level,
                                  GraphicsManager graphics,
                                  String ownerId,
                                  int priority,
                                  int paletteIndex,
                                  int colorIndex,
                                  int segaWord) {
        if (registry != null) {
            registry.submit(PaletteWrite.normal(
                    ownerId,
                    priority,
                    paletteIndex,
                    colorIndex,
                    segaWordBytes(segaWord)));
            return;
        }

        Palette palette = paletteOrNull(level, paletteIndex);
        if (palette == null) {
            return;
        }
        palette.getColor(colorIndex).fromSegaFormat(segaWordBytes(segaWord), 0);
        cachePaletteTextureIfReady(graphics, palette, paletteIndex);
    }

    /**
     * Applies any palette writes still pending at the end of the frame's
     * palette phase. Per-game palette cyclers resolve the registry themselves
     * where they exist; without this fallback, games or zones with no
     * resolving cycler (Sonic 1, Sonic 2 zones without palette cycles) drop
     * every submitted write — e.g. the shared boss hit-flash — when the next
     * {@code beginFrame()} clears the queue.
     */
    public static void resolvePendingFrameWrites(PaletteOwnershipRegistry registry,
                                                 Level level,
                                                 Palette[] underwaterPalettes,
                                                 GraphicsManager graphics) {
        if (registry == null || level == null || registry.hasResolvedThisFrame()) {
            return;
        }
        Palette[] normal = new Palette[level.getPaletteCount()];
        for (int i = 0; i < normal.length; i++) {
            normal[i] = level.getPalette(i);
        }
        registry.resolveInto(normal, underwaterPalettes, graphics, level.getPalette(0));
    }

    public static int segaWordFromColor(Palette.Color color) {
        int r3 = quantizeSegaComponent(color.r);
        int g3 = quantizeSegaComponent(color.g);
        int b3 = quantizeSegaComponent(color.b);
        return (b3 << 9) | (g3 << 5) | (r3 << 1);
    }

    private static Palette paletteOrNull(Level level, int paletteIndex) {
        if (level == null || paletteIndex < 0 || paletteIndex >= level.getPaletteCount()) {
            return null;
        }
        return level.getPalette(paletteIndex);
    }

    private static void cachePaletteTextureIfReady(GraphicsManager graphics, Palette palette, int paletteIndex) {
        if (graphics != null && graphics.isGlInitialized()) {
            graphics.cachePaletteTexture(palette, paletteIndex);
        }
    }

    private static int quantizeSegaComponent(byte component) {
        return (((component & 0xFF) * 7) + 127) / 255;
    }

    private static byte[] segaWordBytes(int segaWord) {
        return new byte[] {
                (byte) ((segaWord >>> 8) & 0xFF),
                (byte) (segaWord & 0xFF)
        };
    }
}
