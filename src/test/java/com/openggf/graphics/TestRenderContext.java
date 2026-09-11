package com.openggf.graphics;

import com.openggf.level.Palette;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameId;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RenderContext} static registry and instance behavior.
 */
public class TestRenderContext {

    @BeforeEach
    public void setUp() {
        RenderContext.reset();
    }

    @AfterEach
    public void tearDown() {
        RenderContext.reset();
    }

    @Test
    public void baseGameOccupiesLines0Through3() {
        // With no donors, total lines should be 4 (base game only)
        assertEquals(4, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void firstDonorGetsLines4Through7() {
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S2);
        assertEquals(4, ctx.getPaletteLineBase());
        assertEquals(8, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void secondDonorGetsLines8Through11() {
        RenderContext.getOrCreateDonor(GameId.S2);
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S3K);
        assertEquals(8, ctx.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void getOrCreateReturnsSameInstanceForSameGame() {
        RenderContext first = RenderContext.getOrCreateDonor(GameId.S2);
        RenderContext second = RenderContext.getOrCreateDonor(GameId.S2);
        assertSame(first, second);
        // Still only 8 total lines (not 12)
        assertEquals(8, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void getEffectivePaletteLineRemapsLogicalLine() {
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S2);
        // Logical line 0 -> effective line 4
        assertEquals(4, ctx.getEffectivePaletteLine(0));
        // Logical line 1 -> effective line 5
        assertEquals(5, ctx.getEffectivePaletteLine(1));
        // Logical line 3 -> effective line 7
        assertEquals(7, ctx.getEffectivePaletteLine(3));
    }

    @Test
    public void resetClearsAllDonorContexts() {
        RenderContext.getOrCreateDonor(GameId.S2);
        RenderContext.getOrCreateDonor(GameId.S3K);
        assertEquals(12, RenderContext.getTotalPaletteLines());

        RenderContext.reset();

        assertEquals(4, RenderContext.getTotalPaletteLines());

        // After reset, creating S2 again gets lines 4-7 (not 12-15)
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S2);
        assertEquals(4, ctx.getPaletteLineBase());
    }

    @Test
    public void gameIdReturnsCorrectValue() {
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S3K);
        assertEquals(GameId.S3K, ctx.getGameId());
    }

    @Test
    public void paletteStorageAndRetrieval() {
        RenderContext ctx = RenderContext.getOrCreateDonor(GameId.S2);
        assertNull(ctx.getPalette(0));

        Palette palette = new Palette();
        ctx.setPalette(0, palette);
        assertSame(palette, ctx.getPalette(0));
        assertNull(ctx.getPalette(1));
    }

    @Test
    public void createSidekickContext_allocatesFreshBlock() {
        RenderContext sidekick = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(4, sidekick.getPaletteLineBase());
        assertEquals(8, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void createSidekickContext_doesNotCacheByGameId() {
        RenderContext first = RenderContext.createSidekickContext(GameId.S3K);
        RenderContext second = RenderContext.createSidekickContext(GameId.S3K);
        assertNotSame(first, second, "Each sidekick must get its own context");
        assertEquals(4, first.getPaletteLineBase());
        assertEquals(8, second.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void createSidekickContext_coexistsWithDonorContexts() {
        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        assertEquals(4, donor.getPaletteLineBase());
        RenderContext sidekick = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(8, sidekick.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void clearSidekickContexts_reclaimsSlots() {
        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        assertEquals(4, donor.getPaletteLineBase());

        RenderContext sk1 = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(8, sk1.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());

        RenderContext.clearSidekickContexts();

        // After clearing, next sidekick should reuse the reclaimed slot
        RenderContext sk2 = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(8, sk2.getPaletteLineBase()); // same as sk1 had
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }

    // --- deriveUnderwaterPalette tests ---
    // Uses GLOBAL average per-channel ratio (not per-index), so donor sprites
    // with different palette layouts (e.g., Tails in S1) get a consistent tint.

    @Test
    public void deriveUnderwaterPalette_appliesGlobalAverageRatio() {
        // Base: 2 non-transparent colors. Normal avg R=(200+100)/2=150, UW avg R=(100+50)/2=75
        // Global ratio R = 75/150 = 0.5
        Palette normalBase = new Palette();
        normalBase.setColor(1, new Palette.Color(
                (byte) 200, (byte) 100, (byte) 50));
        normalBase.setColor(2, new Palette.Color(
                (byte) 100, (byte) 100, (byte) 50));

        Palette underwaterBase = new Palette();
        underwaterBase.setColor(1, new Palette.Color(
                (byte) 100, (byte) 50, (byte) 25));
        underwaterBase.setColor(2, new Palette.Color(
                (byte) 50, (byte) 50, (byte) 25));

        // Donor: color 3 has completely different meaning than base colors 1-2
        Palette donorNormal = new Palette();
        donorNormal.setColor(3, new Palette.Color(
                (byte) 180, (byte) 80, (byte) 40));

        Palette result = RenderContext.deriveUnderwaterPalette(
                donorNormal, normalBase, underwaterBase);

        // Global ratios: R=(100+50)/(200+100)=150/300=0.5, G=(50+50)/(100+100)=100/200=0.5, B=(25+25)/(50+50)=50/100=0.5
        // Donor color 3: (180*128/256, 80*128/256, 40*128/256) = (90, 40, 20)
        Palette.Color c = result.getColor(3);
        assertEquals(90, Byte.toUnsignedInt(c.r));
        assertEquals(40, Byte.toUnsignedInt(c.g));
        assertEquals(20, Byte.toUnsignedInt(c.b));
    }

    @Test
    public void deriveUnderwaterPalette_appliesUniformTintAcrossAllDonorColors() {
        // Same base palettes â€” ratio ~0.5 across all channels
        Palette normalBase = new Palette();
        normalBase.setColor(1, new Palette.Color(
                (byte) 200, (byte) 200, (byte) 200));

        Palette underwaterBase = new Palette();
        underwaterBase.setColor(1, new Palette.Color(
                (byte) 100, (byte) 100, (byte) 100));

        // Donor has Tails-like orange at index 4 AND blue at index 8
        Palette donorNormal = new Palette();
        donorNormal.setColor(4, new Palette.Color(
                (byte) 200, (byte) 100, (byte) 0));
        donorNormal.setColor(8, new Palette.Color(
                (byte) 0, (byte) 0, (byte) 200));

        Palette result = RenderContext.deriveUnderwaterPalette(
                donorNormal, normalBase, underwaterBase);

        // Both should get the same 0.5 factor: orangeâ†’(100,50,0), blueâ†’(0,0,100)
        Palette.Color orange = result.getColor(4);
        assertEquals(100, Byte.toUnsignedInt(orange.r));
        assertEquals(50, Byte.toUnsignedInt(orange.g));
        assertEquals(0, Byte.toUnsignedInt(orange.b));

        Palette.Color blue = result.getColor(8);
        assertEquals(0, Byte.toUnsignedInt(blue.r));
        assertEquals(0, Byte.toUnsignedInt(blue.g));
        assertEquals(100, Byte.toUnsignedInt(blue.b));
    }

    @Test
    public void deriveUnderwaterPalette_clampsTo255() {
        // Ratio > 1 (underwater brighter than normal on average)
        Palette normalBase = new Palette();
        normalBase.setColor(1, new Palette.Color(
                (byte) 50, (byte) 50, (byte) 50));

        Palette underwaterBase = new Palette();
        underwaterBase.setColor(1, new Palette.Color(
                (byte) 200, (byte) 200, (byte) 200));

        Palette donorNormal = new Palette();
        donorNormal.setColor(1, new Palette.Color(
                (byte) 200, (byte) 200, (byte) 200));

        Palette result = RenderContext.deriveUnderwaterPalette(
                donorNormal, normalBase, underwaterBase);

        // ratio=4.0, 200*4=800, clamped to 255
        Palette.Color c = result.getColor(1);
        assertEquals(255, Byte.toUnsignedInt(c.r));
    }

    @Test
    public void deriveUnderwaterPalette_allZeroBase_preservesDonorColors() {
        // All base colors are black â€” ratio defaults to 1.0 (no shift)
        Palette normalBase = new Palette();
        Palette underwaterBase = new Palette();

        Palette donorNormal = new Palette();
        donorNormal.setColor(1, new Palette.Color(
                (byte) 120, (byte) 60, (byte) 30));

        Palette result = RenderContext.deriveUnderwaterPalette(
                donorNormal, normalBase, underwaterBase);

        // No valid base colors to compute ratio from â€” donor colors pass through unchanged
        Palette.Color c = result.getColor(1);
        assertEquals(120, Byte.toUnsignedInt(c.r));
        assertEquals(60, Byte.toUnsignedInt(c.g));
        assertEquals(30, Byte.toUnsignedInt(c.b));
    }

    @Test
    void headlessFlushDiscardsDeferredCommands() {
        GraphicsManager graphics = new GraphicsManager();
        graphics.initHeadless();
        TrackingCommand command = new TrackingCommand(false);
        graphics.registerCommand(command);

        graphics.flushWithCamera((short) 0, (short) 0, (short) 320, (short) 224);

        assertEquals(0, command.executions);
        assertEquals(1, command.releases);
    }

    @Test
    void executedAndExceptionalQueuesReleaseEveryCommandExactlyOnce() throws Exception {
        GraphicsManager graphics = new GraphicsManager();
        setBooleanField(graphics, "glInitialized", true);
        TrackingCommand first = new TrackingCommand(false);
        TrackingCommand throwing = new TrackingCommand(true);
        TrackingCommand remaining = new TrackingCommand(false);
        graphics.registerCommand(first);
        graphics.registerCommand(throwing);
        graphics.registerCommand(remaining);

        assertThrows(IllegalStateException.class,
                () -> graphics.flushWithCamera((short) 0, (short) 0, (short) 320, (short) 224));

        assertEquals(1, first.executions);
        assertEquals(1, throwing.executions);
        assertEquals(0, remaining.executions);
        assertEquals(1, first.releases);
        assertEquals(1, throwing.releases);
        assertEquals(1, remaining.releases);
    }

    @Test
    void nestedHeadlessCaptureDiscardsCommandsAtTheirOwningDrain() {
        GraphicsManager graphics = new GraphicsManager();
        graphics.initHeadless();
        TrackingCommand outerBefore = new TrackingCommand(false);
        TrackingCommand inner = new TrackingCommand(false);
        TrackingCommand outerAfter = new TrackingCommand(false);

        graphics.executeCapturedCommands(() -> {
            graphics.registerCommand(outerBefore);
            graphics.executeCapturedCommands(() -> graphics.registerCommand(inner), 0, 0, 320, 224);
            graphics.registerCommand(outerAfter);
        }, 0, 0, 320, 224);

        assertEquals(1, outerBefore.releases);
        assertEquals(1, inner.releases);
        assertEquals(1, outerAfter.releases);
    }

    @Test
    void resetAndHeadlessCleanupDiscardQueuedCommands() {
        GraphicsManager graphics = new GraphicsManager();
        graphics.initHeadless();
        TrackingCommand resetCommand = new TrackingCommand(false);
        graphics.registerCommand(resetCommand);
        graphics.resetState();
        assertEquals(1, resetCommand.releases);

        TrackingCommand cleanupCommand = new TrackingCommand(false);
        graphics.registerCommand(cleanupCommand);
        graphics.cleanup();
        assertEquals(1, cleanupCommand.releases);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static final class TrackingCommand implements GLCommandable {
        private final boolean throwOnExecute;
        private final AtomicBoolean released = new AtomicBoolean();
        private int executions;
        private int releases;

        private TrackingCommand(boolean throwOnExecute) {
            this.throwOnExecute = throwOnExecute;
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            executions++;
            try {
                if (throwOnExecute) {
                    throw new IllegalStateException("expected");
                }
            } finally {
                discard();
            }
        }

        @Override
        public void discard() {
            if (released.compareAndSet(false, true)) {
                releases++;
            }
        }
    }

}
