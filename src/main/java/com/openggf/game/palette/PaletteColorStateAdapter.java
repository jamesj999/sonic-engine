package com.openggf.game.palette;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.PaletteColorSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Captures and restores live palette COLOR words for the normal and
 * underwater surfaces, and re-caches GPU palette textures on restore.
 *
 * <p>{@link PaletteOwnershipRegistry} snapshots ownership metadata only;
 * the colors themselves are mutated in place on {@code Level} palettes and
 * the water system's underwater set (via {@code resolveInto} and direct
 * writes such as the AIZ intro's palette mutations). Without this adapter
 * those writes are invisible to rewind: flags revert, colors don't.
 *
 * <p>Suppliers are re-evaluated on every capture/restore so the adapter
 * follows level/act swaps without re-registration.
 */
public final class PaletteColorStateAdapter implements RewindSnapshottable<PaletteColorSnapshot> {

    private static final int BYTES_PER_COLOR = 3;
    private static final int BYTES_PER_LINE = Palette.PALETTE_SIZE * BYTES_PER_COLOR;

    private final Supplier<Palette[]> normalPalettes;
    private final Supplier<Palette[]> underwaterPalettes;
    private final Supplier<GraphicsManager> graphics;

    public PaletteColorStateAdapter(Supplier<Palette[]> normalPalettes,
                                    Supplier<Palette[]> underwaterPalettes,
                                    Supplier<GraphicsManager> graphics) {
        this.normalPalettes = Objects.requireNonNull(normalPalettes, "normalPalettes");
        this.underwaterPalettes = Objects.requireNonNull(underwaterPalettes, "underwaterPalettes");
        this.graphics = Objects.requireNonNull(graphics, "graphics");
    }

    @Override
    public String key() {
        return "palette-colors";
    }

    @Override
    public PaletteColorSnapshot capture() {
        return new PaletteColorSnapshot(pack(normalPalettes.get()), pack(underwaterPalettes.get()));
    }

    @Override
    public void restore(PaletteColorSnapshot snapshot) {
        Palette[] normal = normalPalettes.get();
        Palette[] underwater = underwaterPalettes.get();
        unpack(normal, snapshot.normalRgb());
        unpack(underwater, snapshot.underwaterRgb());
        recacheTextures(normal, underwater);
    }

    @Override
    public void resetForMissingSnapshot() {
        // Colors simply stay live; nothing to reset.
    }

    private static byte[] pack(Palette[] palettes) {
        if (palettes == null) {
            return new byte[0];
        }
        byte[] out = new byte[palettes.length * BYTES_PER_LINE];
        int i = 0;
        for (Palette palette : palettes) {
            if (palette == null) {
                i += BYTES_PER_LINE;
                continue;
            }
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                Palette.Color color = palette.colors[c];
                out[i++] = color.r;
                out[i++] = color.g;
                out[i++] = color.b;
            }
        }
        return out;
    }

    private static void unpack(Palette[] palettes, byte[] rgb) {
        if (palettes == null || rgb.length == 0) {
            return;
        }
        int lines = Math.min(palettes.length, rgb.length / BYTES_PER_LINE);
        int i = 0;
        for (int l = 0; l < lines; l++) {
            if (palettes[l] == null) {
                i += BYTES_PER_LINE;
                continue;
            }
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                // Write fields in place: other code aliases Color references.
                Palette.Color color = palettes[l].colors[c];
                color.r = rgb[i++];
                color.g = rgb[i++];
                color.b = rgb[i++];
            }
        }
    }

    private void recacheTextures(Palette[] normal, Palette[] underwater) {
        GraphicsManager g = graphics.get();
        if (g == null || !g.isGlInitialized()) {
            return;
        }
        if (normal != null) {
            for (int line = 0; line < normal.length; line++) {
                if (normal[line] != null) {
                    g.cachePaletteTexture(normal[line], line);
                }
            }
        }
        if (underwater != null) {
            Palette normalLine0 = normal != null && normal.length > 0 ? normal[0] : null;
            g.cacheUnderwaterPaletteTexture(underwater, normalLine0);
        }
    }
}
