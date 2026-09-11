package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteWrite;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.WaterSystem;

public final class S3kPaletteWriteSupport {
    private S3kPaletteWriteSupport() {
    }

    public static void applyLine(PaletteOwnershipRegistry registry,
                                 Level level,
                                 GraphicsManager graphics,
                                 String ownerId,
                                 int priority,
                                 int paletteIndex,
                                 byte[] lineData) {
        applyLine(registry, level, graphics, ownerId, priority, paletteIndex, lineData, false);
    }

    public static void applyLine(PaletteOwnershipRegistry registry,
                                 Level level,
                                 GraphicsManager graphics,
                                 String ownerId,
                                 int priority,
                                 int paletteIndex,
                                 byte[] lineData,
                                 boolean resolveImmediately) {
        if (lineData == null) {
            return;
        }
        if (registry != null) {
            registry.submit(PaletteWrite.normal(ownerId, priority, paletteIndex, 0, lineData.clone()));
            if (resolveImmediately) {
                resolvePendingWritesNow(registry, level, graphics);
            }
            return;
        }
        Palette palette = paletteOrNull(level, paletteIndex);
        if (palette == null) {
            return;
        }
        for (int i = 0; i < lineData.length / 2; i++) {
            palette.getColor(i).fromSegaFormat(lineData, i * 2);
        }
        cachePaletteTextureIfReady(graphics, palette, paletteIndex);
    }

    public static void applyPaletteLine(PaletteOwnershipRegistry registry,
                                        Level level,
                                        GraphicsManager graphics,
                                        String ownerId,
                                        int priority,
                                        int paletteIndex,
                                        Palette sourcePalette,
                                        boolean resolveImmediately) {
        if (sourcePalette == null) {
            return;
        }
        byte[] lineData = paletteLineBytes(sourcePalette);
        applyLine(registry, level, graphics, ownerId, priority, paletteIndex, lineData, resolveImmediately);
    }

    public static void cacheStandaloneLine(GraphicsManager graphics, int paletteIndex, byte[] lineData) {
        if (lineData == null || graphics == null || !graphics.isGlInitialized()) {
            return;
        }
        Palette palette = new Palette();
        for (int i = 0; i < lineData.length / 2; i++) {
            palette.getColor(i).fromSegaFormat(lineData, i * 2);
        }
        graphics.cachePaletteTexture(palette, paletteIndex);
    }

    public static void applyUnderwaterLine(PaletteOwnershipRegistry registry,
                                           Level level,
                                           GraphicsManager graphics,
                                           Palette[] underwaterPalettes,
                                           String ownerId,
                                           int priority,
                                           int paletteIndex,
                                           byte[] lineData,
                                           boolean resolveImmediately) {
        if (lineData == null || underwaterPalettes == null
                || paletteIndex < 0 || paletteIndex >= underwaterPalettes.length) {
            return;
        }
        if (registry != null) {
            registry.submit(PaletteWrite.underwater(ownerId, priority, paletteIndex, 0, lineData.clone()));
            if (resolveImmediately && level != null) {
                registry.resolveInto(levelPalettes(level), underwaterPalettes, graphics, level.getPalette(0));
            }
            return;
        }
        Palette palette = underwaterPalettes[paletteIndex];
        if (palette == null) {
            palette = new Palette();
            underwaterPalettes[paletteIndex] = palette;
        }
        for (int i = 0; i < lineData.length / 2; i++) {
            palette.getColor(i).fromSegaFormat(lineData, i * 2);
        }
        if (graphics != null && graphics.isGlInitialized() && level != null) {
            graphics.cacheUnderwaterPaletteTexture(underwaterPalettes, level.getPalette(0));
        }
    }

    /**
     * Writes a contiguous run of colors into the underwater palette, mirroring
     * {@link #applyContiguousPatch} for the water surface. Used by effects whose
     * ROM code writes both {@code Normal_palette} and {@code Water_palette}.
     */
    public static void applyUnderwaterContiguousPatch(PaletteOwnershipRegistry registry,
                                                      Level level,
                                                      GraphicsManager graphics,
                                                      String ownerId,
                                                      int priority,
                                                      int paletteIndex,
                                                      int startColor,
                                                      byte[] segaData) {
        if (segaData == null) {
            return;
        }
        if (registry != null) {
            registry.submit(PaletteWrite.underwater(ownerId, priority, paletteIndex, startColor, segaData.clone()));
            return;
        }
        Palette[] underwaterPalettes = resolveUnderwaterPalettes(level);
        if (underwaterPalettes == null || paletteIndex < 0 || paletteIndex >= underwaterPalettes.length) {
            return;
        }
        Palette palette = underwaterPalettes[paletteIndex];
        if (palette == null) {
            palette = new Palette();
            underwaterPalettes[paletteIndex] = palette;
        }
        for (int i = 0; i < segaData.length / 2; i++) {
            palette.getColor(startColor + i).fromSegaFormat(segaData, i * 2);
        }
        if (graphics != null && graphics.isGlInitialized() && level != null) {
            graphics.cacheUnderwaterPaletteTexture(underwaterPalettes, level.getPalette(0));
        }
    }

    public static void applyContiguousPatch(PaletteOwnershipRegistry registry,
                                            Level level,
                                            GraphicsManager graphics,
                                            String ownerId,
                                            int priority,
                                            int paletteIndex,
                                            int startColor,
                                            byte[] segaData) {
        if (segaData == null) {
            return;
        }
        if (registry != null) {
            registry.submit(PaletteWrite.normal(ownerId, priority, paletteIndex, startColor, segaData.clone()));
            return;
        }
        Palette palette = paletteOrNull(level, paletteIndex);
        if (palette == null) {
            return;
        }
        for (int i = 0; i < segaData.length / 2; i++) {
            palette.getColor(startColor + i).fromSegaFormat(segaData, i * 2);
        }
        cachePaletteTextureIfReady(graphics, palette, paletteIndex);
    }

    public static void applyContiguousPatchToPalette(PaletteOwnershipRegistry registry,
                                                     Level level,
                                                     GraphicsManager graphics,
                                                     Palette targetPalette,
                                                     int gpuLine,
                                                     String ownerId,
                                                     int priority,
                                                     int startColor,
                                                     byte[] segaData) {
        if (targetPalette == null || segaData == null) {
            return;
        }
        if (registry != null && level != null && gpuLine >= 0 && gpuLine < level.getPaletteCount()
                && targetPalette == level.getPalette(gpuLine)) {
            registry.submit(PaletteWrite.normal(ownerId, priority, gpuLine, startColor, segaData.clone()));
            return;
        }
        for (int i = 0; i < segaData.length / 2; i++) {
            targetPalette.getColor(startColor + i).fromSegaFormat(segaData, i * 2);
        }
        cachePaletteTextureIfReady(graphics, targetPalette, gpuLine);
    }

    public static void applyColors(PaletteOwnershipRegistry registry,
                                   Level level,
                                   GraphicsManager graphics,
                                   String ownerId,
                                   int priority,
                                   int paletteIndex,
                                   int[] colorIndices,
                                   int[] segaWords) {
        if (colorIndices == null || segaWords == null || colorIndices.length != segaWords.length) {
            return;
        }
        if (registry != null) {
            for (int i = 0; i < colorIndices.length; i++) {
                registry.submit(PaletteWrite.normal(
                        ownerId,
                        priority,
                        paletteIndex,
                        colorIndices[i],
                        segaWordBytes(segaWords[i])));
            }
            return;
        }
        Palette palette = paletteOrNull(level, paletteIndex);
        if (palette == null) {
            return;
        }
        for (int i = 0; i < colorIndices.length; i++) {
            palette.getColor(colorIndices[i]).fromSegaFormat(segaWordBytes(segaWords[i]), 0);
        }
        cachePaletteTextureIfReady(graphics, palette, paletteIndex);
    }

    public static void resolvePendingWritesNow(PaletteOwnershipRegistry registry,
                                               Level level,
                                               GraphicsManager graphics) {
        if (registry == null || level == null) {
            return;
        }
        registry.resolveInto(levelPalettes(level), resolveUnderwaterPalettes(level), graphics, level.getPalette(0));
    }

    private static Palette paletteOrNull(Level level, int paletteIndex) {
        if (level == null || paletteIndex < 0 || paletteIndex >= level.getPaletteCount()) {
            return null;
        }
        return level.getPalette(paletteIndex);
    }

    private static Palette[] levelPalettes(Level level) {
        Palette[] palettes = new Palette[level.getPaletteCount()];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = level.getPalette(i);
        }
        return palettes;
    }

    private static Palette[] resolveUnderwaterPalettes(Level level) {
        WaterSystem water = GameServices.waterOrNull();
        LevelManager levelManager = GameServices.levelOrNull();
        if (water == null || levelManager == null) {
            return null;
        }
        return water.getUnderwaterPalette(level.getZoneIndex(), levelManager.getCurrentAct());
    }

    private static void cachePaletteTextureIfReady(GraphicsManager graphics, Palette palette, int paletteIndex) {
        if (graphics != null && graphics.isGlInitialized()) {
            graphics.cachePaletteTexture(palette, paletteIndex);
        }
    }

    private static byte[] segaWordBytes(int segaWord) {
        return new byte[] {
                (byte) ((segaWord >>> 8) & 0xFF),
                (byte) (segaWord & 0xFF)
        };
    }

    private static byte[] paletteLineBytes(Palette palette) {
        byte[] data = new byte[palette.getColorCount() * 2];
        for (int i = 0; i < palette.getColorCount(); i++) {
            int segaWord = com.openggf.game.palette.PaletteWriteSupport.segaWordFromColor(palette.getColor(i));
            data[i * 2] = (byte) ((segaWord >>> 8) & 0xFF);
            data[i * 2 + 1] = (byte) (segaWord & 0xFF);
        }
        return data;
    }
}
