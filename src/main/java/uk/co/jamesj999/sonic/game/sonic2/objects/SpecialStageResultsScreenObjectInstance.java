package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
public class SpecialStageResultsScreenObjectInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(SpecialStageResultsScreenObjectInstance.class.getName());

    // States
    private static final int STATE_SLIDE_IN = 0;
    private static final int STATE_TALLY = 1;
    private static final int STATE_WAIT = 2;
    private static final int STATE_EXIT = 3;

    // Screen dimensions (H40 mode for results screen)
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    private static final int SCREEN_CENTER_X = SCREEN_WIDTH / 2;

    // Y positions for text elements
    private static final int TITLE_Y = 32;
    private static final int GOT_TEXT_Y = 56;
    private static final int EMERALDS_Y = 88;
    private static final int RING_BONUS_Y = 136;
    private static final int EMERALD_BONUS_Y = 156;
    private static final int TOTAL_Y = 184;

    // State tracking
    private int state = STATE_SLIDE_IN;
    private int stateTimer = 0;
    private int frameCounter = 0;

    // Input data
    private final int ringsCollected;
    private final boolean gotEmerald;
    private final int stageIndex;
    private final int totalEmeraldCount;

    // Bonus values
    private int ringBonus;
    private int emeraldBonus;
    private int totalBonus;

    // Slide animation
    private int slideProgress = 0;

    // Completion flag
    private boolean complete = false;

    public SpecialStageResultsScreenObjectInstance(int ringsCollected, boolean gotEmerald,
                                                    int stageIndex, int totalEmeraldCount) {
        super(null, "ss_results_screen");
        this.ringsCollected = ringsCollected;
        this.gotEmerald = gotEmerald;
        this.stageIndex = stageIndex;
        this.totalEmeraldCount = totalEmeraldCount;

        calculateBonuses();

        LOGGER.info("Special Stage Results: rings=" + ringsCollected + ", gotEmerald=" + gotEmerald +
                ", stage=" + (stageIndex + 1) + ", totalEmeralds=" + totalEmeraldCount +
                ", ringBonus=" + ringBonus + ", emeraldBonus=" + emeraldBonus);
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
    public void update(int frameCounter, AbstractPlayableSprite player) {
        this.frameCounter = frameCounter;
        stateTimer++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_EXIT -> complete = true;
        }
    }

    private void updateSlideIn() {
        slideProgress = Math.min(stateTimer, Sonic2SpecialStageConstants.RESULTS_SLIDE_DURATION);

        if (stateTimer >= Sonic2SpecialStageConstants.RESULTS_SLIDE_DURATION) {
            state = STATE_TALLY;
            stateTimer = 0;
        }
    }

    private void updateTally() {
        boolean anyRemaining = false;
        int totalIncrement = 0;

        // Decrement ring bonus
        if (ringBonus > 0) {
            int decrement = Math.min(Sonic2SpecialStageConstants.RESULTS_TALLY_DECREMENT, ringBonus);
            ringBonus -= decrement;
            totalIncrement += decrement;
            anyRemaining = true;
        }

        // Decrement emerald bonus
        if (emeraldBonus > 0) {
            int decrement = Math.min(Sonic2SpecialStageConstants.RESULTS_TALLY_DECREMENT, emeraldBonus);
            emeraldBonus -= decrement;
            totalIncrement += decrement;
            anyRemaining = true;
        }

        // Add to total and score
        if (totalIncrement > 0) {
            totalBonus += totalIncrement;
            GameStateManager.getInstance().addScore(totalIncrement);
        }

        // Play tick sound every 4 frames
        if (anyRemaining && (stateTimer % Sonic2SpecialStageConstants.RESULTS_TALLY_TICK_INTERVAL) == 0) {
            try {
                AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Blip);
            } catch (Exception e) {
                // Ignore audio errors
            }
        }

        // Check if tally complete
        if (!anyRemaining) {
            try {
                AudioManager.getInstance().playSfx(Sonic2Constants.SndID_TallyEnd);
            } catch (Exception e) {
                // Ignore audio errors
            }
            state = STATE_WAIT;
            stateTimer = 0;
        }
    }

    private void updateWait() {
        if (stateTimer >= Sonic2SpecialStageConstants.RESULTS_WAIT_DURATION) {
            state = STATE_EXIT;
        }
    }

    public boolean isComplete() {
        return complete;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return;
        }

        // Screen-space rendering
        int worldBaseX = camera.getX();
        int worldBaseY = camera.getY();

        // Calculate slide-in progress (0.0 to 1.0)
        float slideAlpha = (float) slideProgress / Sonic2SpecialStageConstants.RESULTS_SLIDE_DURATION;

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
        renderEmeralds(commands, worldBaseX, worldBaseY, slideAlpha);

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

    private void renderEmeralds(List<GLCommand> commands, int baseX, int baseY, float slideAlpha) {
        // Render 7 emerald positions in a row
        int emeraldSpacing = 32;
        int startX = SCREEN_CENTER_X - (3 * emeraldSpacing);

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            // Only render if collected
            if (hasThisEmerald) {
                // Flash effect: skip rendering every other frame
                if ((frameCounter & 4) != 0) {
                    int emeraldX = startX + (i * emeraldSpacing);
                    int slideOffsetY = (int) ((1 - slideAlpha) * 100);

                    // Each emerald has a different color
                    float[] color = getEmeraldColor(i);
                    renderEmeraldIcon(commands, baseX + emeraldX, baseY + EMERALDS_Y + slideOffsetY,
                            color[0], color[1], color[2]);
                }
            }
        }
    }

    private float[] getEmeraldColor(int index) {
        // Emerald colors based on Sonic 2 order
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

    private void renderEmeraldIcon(List<GLCommand> commands, int x, int y, float r, float g, float b) {
        // Simple diamond shape for emerald placeholder
        int size = 12;

        // Top
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y - size, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + size, y, 0, 0));

        // Right
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + size, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + size, 0, 0));

        // Bottom
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + size, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x - size, y, 0, 0));

        // Left
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x - size, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y - size, 0, 0));
    }

    private void renderPlaceholderText(List<GLCommand> commands, int x, int y,
                                        String text, float r, float g, float b) {
        // Simple placeholder box representing text
        int width = text.length() * 6;
        int height = 12;

        renderBox(commands, x - width / 2, y, width, height, r, g, b);
    }

    private void renderBonusLine(List<GLCommand> commands, int baseX, int y,
                                  String label, int value, float r, float g, float b) {
        // Label on left
        renderPlaceholderText(commands, baseX + 80, y, label, r * 0.7f, g * 0.7f, b * 0.7f);

        // Value on right
        String valueStr = String.valueOf(value);
        renderPlaceholderText(commands, baseX + 240, y, valueStr, r, g, b);
    }

    private void renderBox(List<GLCommand> commands, int x, int y, int width, int height,
                           float r, float g, float b) {
        // Draw a simple filled rectangle
        // Top edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        // Bottom edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
        // Left edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        // Right edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0); // Highest priority - draw on top
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getX() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getX() + SCREEN_CENTER_X : SCREEN_CENTER_X;
    }

    @Override
    public int getY() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getY() + SCREEN_HEIGHT / 2 : SCREEN_HEIGHT / 2;
    }

    // Getters for testing
    public int getRingBonus() { return ringBonus; }
    public int getEmeraldBonus() { return emeraldBonus; }
    public int getTotalBonus() { return totalBonus; }
    public boolean didGetEmerald() { return gotEmerald; }
    public int getState() { return state; }
}
