package com.openggf.sprites.render;

import com.openggf.game.GameId;
import com.openggf.graphics.RenderContext;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.art.SpriteArtSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPlayerSpriteRendererPaletteContext {

    @BeforeEach
    void setUp() {
        // Reset so the next donor created is the FIRST donor and therefore lands
        // at the deterministic palette base (RenderContext.LINES_PER_CONTEXT).
        RenderContext.reset();
    }

    @AfterEach
    void tearDown() {
        RenderContext.reset();
    }

    @Test
    void resolveRenderPaletteIndex_usesRenderContextPaletteBlockWhenPresent() {
        SpriteArtSet artSet = new SpriteArtSet(
                new Pattern[0],
                List.of(new SpriteMappingFrame(List.of())),
                List.of(new SpriteDplcFrame(List.of())),
                2,
                0,
                0,
                0,
                null,
                null);
        PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);

        RenderContext donorContext = RenderContext.getOrCreateDonor(GameId.S3K);
        renderer.setRenderContext(donorContext);

        // Independent oracle: the first donor's palette block starts at
        // LINES_PER_CONTEXT (4), so logical line 2 maps to the effective line
        // LINES_PER_CONTEXT + 2. Computed here without calling the SUT's own
        // getEffectivePaletteLine, so a wiring/offset regression in
        // resolveRenderPaletteIndex is caught.
        int expectedEffectiveLine = RenderContext.LINES_PER_CONTEXT + 2;
        assertEquals(expectedEffectiveLine, renderer.resolveRenderPaletteIndex(2));
    }

    @Test
    void resolveRenderPaletteIndex_keepsLogicalPaletteWhenNoRenderContext() {
        SpriteArtSet artSet = new SpriteArtSet(
                new Pattern[0],
                List.of(new SpriteMappingFrame(List.of())),
                List.of(new SpriteDplcFrame(List.of())),
                1,
                0,
                0,
                0,
                null,
                null);
        PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);

        assertEquals(1, renderer.resolveRenderPaletteIndex(1));
    }
}


