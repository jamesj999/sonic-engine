package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageDataLoader;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Special Stage Results Screen.
 * <p>
 * Displays after completing or failing a special stage, showing:
 * - "SPECIAL STAGE" title
 * - "Sonic got a Chaos Emerald" (if emerald collected)
 * - "Sonic has all the Chaos Emeralds" (if all 7 collected)
 * - Collected emeralds (flashing)
 * - Ring bonus tally
 * - Emerald bonus (if applicable)
 * <p>
 * Based on Obj6F from s2.asm.
 */
public class SpecialStageResultsScreenObjectInstance extends AbstractResultsScreen {
    private static final Logger LOGGER = Logger.getLogger(SpecialStageResultsScreenObjectInstance.class.getName());

    // Y positions for text elements
    private static final int TITLE_Y = 32;
    private static final int GOT_TEXT_Y = 56;
    private static final int EMERALDS_Y = 88;
    private static final int RING_BONUS_Y = 136;
    private static final int EMERALD_BONUS_Y = 156;
    private static final int TOTAL_Y = 184;

    // Tile size in pixels
    private static final int TILE_SIZE = 8;

    // Pattern base for results art (cached during initialization)
    private static final int RESULTS_PATTERN_BASE = 0x20000;  // High ID to avoid conflicts

    // Emerald tile info from obj6F mappings
    // Emeralds are 2x2 tiles (16x16 pixels)
    // From obj6F.asm: spritePiece 0, 0, 2, 2, $5A4/$5A8/$5AC, 0, 0, palette, 1
    // Results art VRAM base is $590, so:
    // $5A4 - $590 = $14 = tile 20
    // $5A8 - $590 = $18 = tile 24
    // $5AC - $590 = $1C = tile 28
    private static final int EMERALD_TILE_BASE_A = 0x14;  // Tiles for emeralds 0,1 ($5A4)
    private static final int EMERALD_TILE_BASE_B = 0x18;  // Tiles for emeralds 4,5 ($5A8)
    private static final int EMERALD_TILE_BASE_C = 0x1C;  // Tiles for emeralds 2,3 ($5AC)

    // Palette indices for each emerald (from obj6F.asm mapping data)
    // Order matches Sonic 2's emerald order
    private static final int[] EMERALD_PALETTES = {
            2,  // 0: Blue emerald - palette 2
            3,  // 1: Yellow emerald - palette 3
            2,  // 2: Pink emerald - palette 2
            3,  // 3: Green emerald - palette 3
            3,  // 4: Orange emerald - palette 3
            2,  // 5: Purple emerald - palette 2
            1   // 6: Gray/Cyan emerald - palette 1
    };

    // Tile base for each emerald
    private static final int[] EMERALD_TILE_BASES = {
            EMERALD_TILE_BASE_A,  // 0: Blue
            EMERALD_TILE_BASE_A,  // 1: Yellow
            EMERALD_TILE_BASE_C,  // 2: Pink
            EMERALD_TILE_BASE_C,  // 3: Green
            EMERALD_TILE_BASE_B,  // 4: Orange
            EMERALD_TILE_BASE_B,  // 5: Purple
            EMERALD_TILE_BASE_B   // 6: Gray
    };

    // Input data
    private final int ringsCollected;
    private final boolean gotEmerald;
    private final int stageIndex;
    private final int totalEmeraldCount;

    // Bonus values
    private int ringBonus;
    private int emeraldBonus;
    private int totalBonus;

    // Art cache
    private Pattern[] resultsArtPatterns;
    private boolean artCached = false;

    public SpecialStageResultsScreenObjectInstance(int ringsCollected, boolean gotEmerald,
                                                    int stageIndex, int totalEmeraldCount) {
        super("ss_results_screen");
        this.ringsCollected = ringsCollected;
        this.gotEmerald = gotEmerald;
        this.stageIndex = stageIndex;
        this.totalEmeraldCount = totalEmeraldCount;

        calculateBonuses();
        loadResultsArt();

        LOGGER.info("Special Stage Results: rings=" + ringsCollected + ", gotEmerald=" + gotEmerald +
                ", stage=" + (stageIndex + 1) + ", totalEmeralds=" + totalEmeraldCount +
                ", ringBonus=" + ringBonus + ", emeraldBonus=" + emeraldBonus);
    }

    private void loadResultsArt() {
        try {
            Sonic2SpecialStageManager manager = Sonic2SpecialStageManager.getInstance();
            if (manager != null) {
                Sonic2SpecialStageDataLoader dataLoader = manager.getDataLoader();
                if (dataLoader != null) {
                    resultsArtPatterns = dataLoader.getResultsArtPatterns();
                    if (resultsArtPatterns != null && resultsArtPatterns.length > 0) {
                        LOGGER.fine("Loaded " + resultsArtPatterns.length + " results art patterns");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load results art: " + e.getMessage());
            resultsArtPatterns = null;
        }
    }

    private void ensureArtCached() {
        if (artCached || resultsArtPatterns == null) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        // Cache all results art patterns to GPU
        for (int i = 0; i < resultsArtPatterns.length; i++) {
            graphicsManager.cachePatternTexture(resultsArtPatterns[i], RESULTS_PATTERN_BASE + i);
        }
        artCached = true;
        LOGGER.fine("Cached " + resultsArtPatterns.length + " results art patterns at base 0x" +
                Integer.toHexString(RESULTS_PATTERN_BASE));
    }

    private void calculateBonuses() {
        // Ring bonus: rings collected * 10
        ringBonus = ringsCollected * Sonic2SpecialStageConstants.RESULTS_RING_MULTIPLIER;

        // Emerald bonus: 1000 if collected an emerald
        emeraldBonus = gotEmerald ? Sonic2SpecialStageConstants.RESULTS_EMERALD_BONUS : 0;

        // Total starts at 0 and counts up as bonuses tally
        totalBonus = 0;
    }

    @Override
    protected int getSlideDuration() {
        return Sonic2SpecialStageConstants.RESULTS_SLIDE_DURATION;
    }

    @Override
    protected int getWaitDuration() {
        return Sonic2SpecialStageConstants.RESULTS_WAIT_DURATION;
    }

    @Override
    protected int getTallyTickInterval() {
        return Sonic2SpecialStageConstants.RESULTS_TALLY_TICK_INTERVAL;
    }

    @Override
    protected int getTallyDecrement() {
        return Sonic2SpecialStageConstants.RESULTS_TALLY_DECREMENT;
    }

    @Override
    protected TallyResult performTallyStep() {
        boolean anyRemaining = false;
        int totalIncrement = 0;

        // Decrement ring bonus
        int[] ringResult = decrementBonus(ringBonus);
        ringBonus = ringResult[0];
        totalIncrement += ringResult[1];
        if (ringResult[1] > 0) anyRemaining = true;

        // Decrement emerald bonus
        int[] emeraldResult = decrementBonus(emeraldBonus);
        emeraldBonus = emeraldResult[0];
        totalIncrement += emeraldResult[1];
        if (emeraldResult[1] > 0) anyRemaining = true;

        // Update total
        totalBonus += totalIncrement;

        return new TallyResult(anyRemaining, totalIncrement);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return;
        }

        // Ensure art is cached for rendering
        ensureArtCached();

        // Screen-space rendering
        int worldBaseX = camera.getX();
        int worldBaseY = camera.getY();

        float slideAlpha = getSlideAlpha();

        // Render white/light background
        renderBackground(commands, worldBaseX, worldBaseY);

        // "SPECIAL STAGE" title - slides from right
        int titleX = SCREEN_CENTER_X + (int) ((1 - slideAlpha) * 200);
        renderPlaceholderText(commands, worldBaseX + titleX, worldBaseY + TITLE_Y,
                "SPECIAL STAGE", 0.2f, 0.4f, 0.8f);

        // Result text - only if emerald collected
        if (gotEmerald) {
            int gotTextX = (int) (slideAlpha * SCREEN_CENTER_X) - 60;
            if (totalEmeraldCount >= 7) {
                renderPlaceholderText(commands, worldBaseX + gotTextX, worldBaseY + GOT_TEXT_Y,
                        "SONIC HAS ALL THE CHAOS EMERALDS!", 1.0f, 0.8f, 0.0f);
            } else {
                renderPlaceholderText(commands, worldBaseX + gotTextX, worldBaseY + GOT_TEXT_Y,
                        "SONIC GOT A CHAOS EMERALD", 0.0f, 0.8f, 0.2f);
            }
        }

        // Render emeralds (collected ones flash)
        renderEmeralds(worldBaseX, worldBaseY, slideAlpha);

        // Bonus displays - only after slide-in complete
        if (state >= STATE_TALLY) {
            // Ring bonus
            renderBonusLine(commands, worldBaseX, worldBaseY + RING_BONUS_Y,
                    "RING BONUS", ringBonus, 0.8f, 0.6f, 0.0f);

            // Emerald bonus (only if got emerald)
            if (gotEmerald) {
                renderBonusLine(commands, worldBaseX, worldBaseY + EMERALD_BONUS_Y,
                        "EMERALD BONUS", emeraldBonus, 0.0f, 0.8f, 0.4f);
            }

            // Total
            renderBonusLine(commands, worldBaseX, worldBaseY + TOTAL_Y,
                    "TOTAL", totalBonus, 1.0f, 1.0f, 1.0f);
        }

        // Flush any batched patterns
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager != null) {
            graphicsManager.flushPatternBatch();
        }
    }

    private void renderBackground(List<GLCommand> commands, int baseX, int baseY) {
        // Light blue/white background (similar to original results screen)
        float r = 0.85f, g = 0.9f, b = 0.95f;

        // Fill entire screen with background color using quads
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, baseX, baseY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, baseX + SCREEN_WIDTH, baseY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, baseX + SCREEN_WIDTH, baseY + SCREEN_HEIGHT, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, baseX, baseY + SCREEN_HEIGHT, 0, 0));
    }

    private void renderEmeralds(int baseX, int baseY, float slideAlpha) {
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        // Render 7 emerald positions in a row
        int emeraldSpacing = 32;
        int startX = SCREEN_CENTER_X - (3 * emeraldSpacing);

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            // Only render if collected
            if (hasThisEmerald) {
                // Flash effect: skip rendering every other 8 frames
                if ((frameCounter & 4) != 0) {
                    int emeraldX = startX + (i * emeraldSpacing);
                    int slideOffsetY = (int) ((1 - slideAlpha) * 100);

                    // Try to use ROM art, fall back to placeholder if not available
                    if (artCached && resultsArtPatterns != null &&
                            i < EMERALD_TILE_BASES.length && i < EMERALD_PALETTES.length) {
                        renderEmeraldFromArt(graphicsManager,
                                baseX + emeraldX - 8, // Center the 16x16 sprite
                                baseY + EMERALDS_Y + slideOffsetY,
                                EMERALD_TILE_BASES[i],
                                EMERALD_PALETTES[i]);
                    } else {
                        // Fallback: use colored placeholder
                        float[] color = getEmeraldColor(i);
                        renderEmeraldPlaceholder(graphicsManager,
                                baseX + emeraldX,
                                baseY + EMERALDS_Y + slideOffsetY,
                                color[0], color[1], color[2]);
                    }
                }
            }
        }
    }

    /**
     * Renders an emerald icon using ROM art patterns.
     * Each emerald is a 2x2 tile sprite (16x16 pixels).
     */
    private void renderEmeraldFromArt(GraphicsManager graphicsManager, int x, int y,
                                       int tileBase, int paletteIndex) {
        // Render 2x2 tiles in column-major order (VDP convention)
        for (int tx = 0; tx < 2; tx++) {
            for (int ty = 0; ty < 2; ty++) {
                int tileIndex = tileBase + (tx * 2) + ty;  // Column-major index

                // Skip if tile index is out of range
                if (resultsArtPatterns == null || tileIndex >= resultsArtPatterns.length) {
                    continue;
                }

                int patternId = RESULTS_PATTERN_BASE + tileIndex;

                PatternDesc desc = new PatternDesc();
                desc.setPriority(true);
                desc.setPaletteIndex(paletteIndex);
                desc.setHFlip(false);
                desc.setVFlip(false);
                desc.setPatternIndex(patternId & 0x7FF);

                int tileScreenX = x + tx * TILE_SIZE;
                int tileScreenY = y + ty * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
            }
        }
    }

    /**
     * Renders a placeholder emerald using simple colored shapes.
     */
    private void renderEmeraldPlaceholder(GraphicsManager graphicsManager, int x, int y,
                                          float r, float g, float b) {
        // Draw a simple diamond shape
        int size = 12;
        // Note: This would need to use raw GL commands or batch rendering
        // For now, we'll just skip rendering if art isn't available
        // The placeholder will be handled by the fallback in renderEmeralds
    }

    private float[] getEmeraldColor(int index) {
        // Emerald colors based on Sonic 2 order (used for placeholder fallback)
        return switch (index) {
            case 0 -> new float[]{0.0f, 0.0f, 1.0f};    // Blue
            case 1 -> new float[]{1.0f, 1.0f, 0.0f};    // Yellow
            case 2 -> new float[]{1.0f, 0.5f, 0.7f};    // Pink
            case 3 -> new float[]{0.0f, 1.0f, 0.0f};    // Green
            case 4 -> new float[]{1.0f, 0.5f, 0.0f};    // Orange
            case 5 -> new float[]{0.5f, 0.0f, 1.0f};    // Purple
            case 6 -> new float[]{0.5f, 0.5f, 0.5f};    // Gray/Silver
            default -> new float[]{1.0f, 1.0f, 1.0f};
        };
    }

    private void renderBonusLine(List<GLCommand> commands, int baseX, int y,
                                  String label, int value, float r, float g, float b) {
        // Label on left
        renderPlaceholderText(commands, baseX + 80, y, label, r * 0.7f, g * 0.7f, b * 0.7f);

        // Value on right
        String valueStr = String.valueOf(value);
        renderPlaceholderText(commands, baseX + 240, y, valueStr, r, g, b);
    }

    // Getters for testing
    public int getRingBonus() { return ringBonus; }
    public int getEmeraldBonus() { return emeraldBonus; }
    public int getTotalBonus() { return totalBonus; }
    public boolean didGetEmerald() { return gotEmerald; }
}
