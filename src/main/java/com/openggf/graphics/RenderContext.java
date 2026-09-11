package com.openggf.graphics;

import com.openggf.game.GameId;
import com.openggf.level.Palette;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-game palette isolation for cross-game sprite rendering.
 *
 * <p>The base game occupies palette lines 0-3. Each donor game gets its own
 * block of 4 palette lines (4-7, 8-11, etc.) so donor sprites render with
 * their own colors without interfering with the base game's palettes.
 *
 * <p>Combines instance state (one game's palette context) with a static
 * registry that manages all active donor contexts.
 */
public class RenderContext {

    // --- Static registry ---

    /** Number of palette lines per context (matches Mega Drive's 4 palette lines). */
    public static final int LINES_PER_CONTEXT = 4;

    private static final Map<GameId, RenderContext> donorContexts = new LinkedHashMap<>();
    private static final Collection<RenderContext> donorContextView =
            Collections.unmodifiableCollection(donorContexts.values());
    private static final java.util.List<RenderContext> sidekickContexts = new java.util.ArrayList<>();
    private static int nextPaletteBase = LINES_PER_CONTEXT; // first donor starts at line 4

    /**
     * Returns (or creates) the donor render context for the given game.
     * The first donor gets palette lines 4-7, the second 8-11, etc.
     */
    public static RenderContext getOrCreateDonor(GameId gameId) {
        return donorContexts.computeIfAbsent(gameId, id -> {
            RenderContext ctx = new RenderContext(id, nextPaletteBase);
            nextPaletteBase += LINES_PER_CONTEXT;
            return ctx;
        });
    }

    /**
     * Creates a new sidekick render context with its own palette block.
     * Unlike donor contexts, sidekick contexts are NOT cached by GameId —
     * each sidekick gets a unique context even if they share the same game.
     */
    public static RenderContext createSidekickContext(GameId gameId) {
        RenderContext ctx = new RenderContext(gameId, nextPaletteBase);
        nextPaletteBase += LINES_PER_CONTEXT;
        sidekickContexts.add(ctx);
        return ctx;
    }

    /**
     * Returns the total number of palette lines needed (base + all donors).
     */
    public static int getTotalPaletteLines() {
        return nextPaletteBase;
    }

    /**
     * Uploads all donor palettes to the GPU palette texture.
     */
    public static void uploadDonorPalettes(GraphicsManager gm) {
        for (RenderContext ctx : donorContexts.values()) {
            for (int line = 0; line < LINES_PER_CONTEXT; line++) {
                Palette p = ctx.palettes[line];
                if (p != null) {
                    gm.cachePaletteTexture(p, ctx.paletteLineBase + line);
                }
            }
        }
        for (RenderContext ctx : sidekickContexts) {
            for (int line = 0; line < LINES_PER_CONTEXT; line++) {
                Palette p = ctx.palettes[line];
                if (p != null) {
                    gm.cachePaletteTexture(p, ctx.paletteLineBase + line);
                }
            }
        }
    }

    /**
     * Returns all active donor render contexts (unmodifiable).
     */
    public static Collection<RenderContext> getDonorContexts() {
        return donorContextView;
    }

    /**
     * Returns the normal palette assigned to an expanded palette-texture row.
     * Covers both shared donor contexts and per-sidekick contexts so every
     * cross-game sprite can receive a derived underwater row.
     */
    public static Collection<RenderContext> getSidekickContexts() {
        return Collections.unmodifiableList(sidekickContexts);
    }

    /** Returns a donor-supplied native underwater palette for an expanded row. */
    public static PaletteView getUnderwaterPaletteForEffectiveLine(int effectiveLine) {
        PaletteView palette = getUnderwaterPaletteForEffectiveLine(donorContexts.values(), effectiveLine);
        return palette != null
                ? palette
                : getUnderwaterPaletteForEffectiveLine(sidekickContexts, effectiveLine);
    }

    private static PaletteView getUnderwaterPaletteForEffectiveLine(
            Collection<RenderContext> contexts, int effectiveLine) {
        for (RenderContext ctx : contexts) {
            int logicalLine = effectiveLine - ctx.paletteLineBase;
            if (logicalLine >= 0 && logicalLine < LINES_PER_CONTEXT) {
                return ctx.underwaterPalettes[logicalLine];
            }
        }
        return null;
    }

    /**
     * Derives an underwater palette for a donor sprite by applying the base
     * game's average normal-to-underwater color shift to each donor color.
     *
     * <p>Uses a global per-channel ratio averaged across all non-transparent
     * colors, rather than per-index ratios. This is necessary because color
     * indices map to completely different things across games (e.g. S1 index 3
     * might be Sonic's blue while the donor's index 3 is Tails' orange).
     *
     * @param donorNormal    the donor's normal (above-water) palette
     * @param normalBase     the base game's normal palette (line 0)
     * @param underwaterBase the base game's underwater palette (line 0)
     * @return a new Palette with underwater-tinted donor colors
     */
    public static Palette deriveUnderwaterPalette(Palette donorNormal,
                                                   Palette normalBase,
                                                   Palette underwaterBase) {
        // Compute global per-channel ratio: avg(underwater) / avg(normal)
        long sumNR = 0, sumNG = 0, sumNB = 0;
        long sumUR = 0, sumUG = 0, sumUB = 0;
        int count = 0;
        for (int i = 1; i < 16; i++) { // skip index 0 (transparent)
            Palette.Color nb = normalBase.getColor(i);
            Palette.Color ub = underwaterBase.getColor(i);
            int nbR = Byte.toUnsignedInt(nb.r);
            int nbG = Byte.toUnsignedInt(nb.g);
            int nbB = Byte.toUnsignedInt(nb.b);
            if (nbR + nbG + nbB > 0) {
                sumNR += nbR; sumNG += nbG; sumNB += nbB;
                sumUR += Byte.toUnsignedInt(ub.r);
                sumUG += Byte.toUnsignedInt(ub.g);
                sumUB += Byte.toUnsignedInt(ub.b);
                count++;
            }
        }

        // Ratios scaled by 256 for fixed-point math
        int ratioR = 256, ratioG = 256, ratioB = 256;
        if (count > 0) {
            if (sumNR > 0) ratioR = (int) (sumUR * 256 / sumNR);
            if (sumNG > 0) ratioG = (int) (sumUG * 256 / sumNG);
            if (sumNB > 0) ratioB = (int) (sumUB * 256 / sumNB);
        }

        Palette result = new Palette();
        for (int i = 0; i < 16; i++) {
            Palette.Color dn = donorNormal.getColor(i);
            int dnR = Byte.toUnsignedInt(dn.r);
            int dnG = Byte.toUnsignedInt(dn.g);
            int dnB = Byte.toUnsignedInt(dn.b);

            int r = Math.min(255, dnR * ratioR / 256);
            int g = Math.min(255, dnG * ratioG / 256);
            int b = Math.min(255, dnB * ratioB / 256);

            result.setColor(i, new Palette.Color((byte) r, (byte) g, (byte) b));
        }
        return result;
    }

    /**
     * Clears all sidekick palette contexts and reclaims their palette line slots.
     * Called at the start of each level load to prevent accumulation.
     */
    public static void clearSidekickContexts() {
        if (sidekickContexts.isEmpty()) {
            return;
        }
        sidekickContexts.clear();
        // Recompute nextPaletteBase: base (4) + donor contexts
        nextPaletteBase = LINES_PER_CONTEXT;
        for (RenderContext ctx : donorContexts.values()) {
            int ctxEnd = ctx.paletteLineBase + LINES_PER_CONTEXT;
            if (ctxEnd > nextPaletteBase) {
                nextPaletteBase = ctxEnd;
            }
        }
    }

    /**
     * Clears all donor contexts. Call on engine cleanup or game reset.
     */
    public static void reset() {
        donorContexts.clear();
        sidekickContexts.clear();
        nextPaletteBase = LINES_PER_CONTEXT;
    }

    // --- Instance state ---

    private final GameId gameId;
    private final int paletteLineBase;
    private final Palette[] palettes = new Palette[LINES_PER_CONTEXT];
    private final PaletteView[] underwaterPalettes = new PaletteView[LINES_PER_CONTEXT];

    private RenderContext(GameId gameId, int paletteLineBase) {
        this.gameId = gameId;
        this.paletteLineBase = paletteLineBase;
    }

    public GameId getGameId() {
        return gameId;
    }

    public int getPaletteLineBase() {
        return paletteLineBase;
    }

    /**
     * Maps a logical palette line (0-3) to the effective line in the
     * expanded palette texture.
     */
    public int getEffectivePaletteLine(int logical) {
        return paletteLineBase + logical;
    }

    public void setPalette(int line, Palette p) {
        palettes[line] = p;
    }

    public Palette getPalette(int line) {
        return palettes[line];
    }

    public void setUnderwaterPalette(int line, PaletteView palette) {
        underwaterPalettes[line] = palette;
    }

    public PaletteView getUnderwaterPalette(int line) {
        return underwaterPalettes[line];
    }
}
