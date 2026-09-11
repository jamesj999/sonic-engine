package com.openggf.game.sonic3k.features;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.FireCurtainStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: probes exactly what the overlay composition plan
 * produces at the fire zone boundaries.
 */
public class TestFireCurtainBoundaryDiag {

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 224;

    /**
     * Simulate the RISING phase when fire is just appearing (small cover).
     * coverHeight = bgY + 224 - 0x100.  With bgY=0x30: cover=16.
     * The top edge of the fire is at clipTop = 224-16 = 208.
     *
     * Key question: does the overlay produce any draws at or above screenY=208?
     * And does it leave a gap between clipTop and the first draw?
     */
    @Test
    public void diagRisingTopEdge() {
        // No-arg constructor: sampler=null, and the headless BG returns empty
        // descriptors. The renderer must fail closed instead of synthesizing fire.
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                16,       // coverHeightPx
                0,        // wavePhase
                8,        // frameCounter
                0x1000,   // sourceWorldX
                0x30,     // sourceWorldY (bgY)
                new int[20], // flat wave (no offsets)
                FireCurtainStage.AIZ1_RISING,
                0x500,    // fireOverlayTileBase
                121);     // fireOverlayTileCount

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, SCREEN_W, SCREEN_H);

        System.out.println("=== RISING TOP EDGE (bgY=0x30, cover=16, clipTop=208) ===");
        System.out.println("Columns: " + plan.columns().size());

        int totalDraws = 0;
        for (AizFireCurtainRenderer.ColumnRenderPlan col : plan.columns()) {
            totalDraws += col.draws().size();
        }
        System.out.println("Total draws: " + totalDraws);

        if (!plan.columns().isEmpty()) {
            AizFireCurtainRenderer.ColumnRenderPlan col0 = plan.columns().get(0);
            System.out.println("Col0 topY=" + col0.topY() + " draws=" + col0.draws().size());
            int minScreenY = Integer.MAX_VALUE;
            int maxScreenY = Integer.MIN_VALUE;
            for (AizFireCurtainRenderer.TileDraw d : col0.draws()) {
                minScreenY = Math.min(minScreenY, d.screenY());
                maxScreenY = Math.max(maxScreenY, d.screenY());
            }
            System.out.println("Col0 screenY range: [" + minScreenY + ", " + maxScreenY + "]");
            System.out.println("Col0 first draw: screenY=" + col0.draws().get(0).screenY()
                    + " patId=0x" + Integer.toHexString(col0.draws().get(0).renderPatternId()));
            System.out.println("Col0 last draw: screenY="
                    + col0.draws().get(col0.draws().size() - 1).screenY()
                    + " patId=0x"
                    + Integer.toHexString(col0.draws().get(col0.draws().size() - 1).renderPatternId()));
        }

        assertTrue(plan.columns().isEmpty(), "Headless no-ROM path should fail closed for RISING");
        assertEquals(0, totalDraws, "No synthetic draws should be emitted");
    }

    /**
     * Simulate the EXIT phase: fire scrolling upward off screen.
     * bgY=0x240, so BG Y=0x310 maps to screenY = 0x310 - 0x240 = 208.
     * The fire zone ends at 0x310. The tile at BG Y=0x310 is the first
     * non-fire tile below the fire zone.
     *
     * Key question: does the overlay draw at screenY=208 or does it stop
     * at screenY=200 (BG Y=0x308, last fire zone tile)?
     */
    @Test
    public void diagExitBottomEdge() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                224,      // coverHeightPx (full screen in post-rising)
                0,        // wavePhase
                60,       // frameCounter
                0x1000,   // sourceWorldX
                0x240,    // sourceWorldY (bgY) - fire near exit
                new int[20], // flat wave
                FireCurtainStage.AIZ1_REFRESH,
                0x500,
                121);

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, SCREEN_W, SCREEN_H);

        System.out.println("=== EXIT BOTTOM EDGE (bgY=0x240, cover=224, clipTop=0) ===");
        System.out.println("Columns: " + plan.columns().size());

        if (!plan.columns().isEmpty()) {
            AizFireCurtainRenderer.ColumnRenderPlan col0 = plan.columns().get(0);
            System.out.println("Col0 topY=" + col0.topY() + " draws=" + col0.draws().size());
            int minScreenY = Integer.MAX_VALUE;
            int maxScreenY = Integer.MIN_VALUE;
            for (AizFireCurtainRenderer.TileDraw d : col0.draws()) {
                minScreenY = Math.min(minScreenY, d.screenY());
                maxScreenY = Math.max(maxScreenY, d.screenY());
            }
            System.out.println("Col0 screenY range: [" + minScreenY + ", " + maxScreenY + "]");

            // What's at the bottom? The last draw should reach screenY=216 (the tile
            // covering screen bottom at Y=223). If it only reaches 200, there's a gap.
            System.out.println("Col0 last draw (bottom-most): screenY="
                    + col0.draws().get(0).screenY()  // draws are bottom-to-top
                    + " patId=0x" + Integer.toHexString(col0.draws().get(0).renderPatternId()));

            // BG Y at screen bottom: 0x240 + 223 = 0x463. Fire zone ends at 0x310.
            // Only fire zone tiles drawn. Last fire tile: BG Y=0x308, screenY=0x308-0x240=200.
            // Screen Y range [200,223] should be covered. 200+8=208. Next tile at 208+8=216. 216+8=224.
            // So tiles at screenY 200,208,216 cover to 224.
            // BUT: BG Y=0x308 is screen 200. BG Y=0x310 is screen 208. BG Y=0x318 is screen 216.
            // 0x310 is outside old filter. With +TILE_SIZE padding: included.
            // 0x318 is outside even padded filter. But 216+8=224=screenHeight so covered.
        }

        assertTrue(plan.columns().isEmpty(), "Headless no-ROM path should fail closed for EXIT");
    }

    /**
     * Simulate RISING with the test sampler (for comparison with production path).
     * Same parameters as diagRisingTopEdge but with a sampler.
     */
    @Test
    public void diagRisingTopEdgeWithSampler() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer((worldX, worldY) -> {
            int pattern = ((worldX / 8) + (worldY / 8)) & 0x7FF;
            return (3 << 13) | pattern;
        });
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                16,
                0,
                8,
                0x1000,
                0x30,
                new int[20],
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, SCREEN_W, SCREEN_H);

        System.out.println("=== RISING TOP EDGE WITH SAMPLER (bgY=0x30, cover=16, clipTop=208) ===");
        System.out.println("Columns: " + plan.columns().size());

        if (!plan.columns().isEmpty()) {
            AizFireCurtainRenderer.ColumnRenderPlan col0 = plan.columns().get(0);
            System.out.println("Col0 topY=" + col0.topY() + " draws=" + col0.draws().size());
            int minScreenY = Integer.MAX_VALUE;
            int maxScreenY = Integer.MIN_VALUE;
            for (AizFireCurtainRenderer.TileDraw d : col0.draws()) {
                minScreenY = Math.min(minScreenY, d.screenY());
                maxScreenY = Math.max(maxScreenY, d.screenY());
            }
            System.out.println("Col0 screenY range: [" + minScreenY + ", " + maxScreenY + "]");
        }

        assertFalse(plan.columns().isEmpty(), "Sampler path should produce draws");
    }
}


