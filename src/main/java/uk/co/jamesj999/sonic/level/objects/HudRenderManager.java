package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.LevelGamestate;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.camera.Camera;

public class HudRenderManager {
    private static final int DIGIT_ZERO = 0; // Relative to loaded HUD digit patterns
    private static final int DIGIT_COLON = 10; // Assuming colon is after 9

    // Pattern indices relative to the base HUD patterns loaded
    // We need to know where HUD patterns are loaded.
    // Usually they are at fixed VRAM, but our GraphicsManager uses pattern indices.
    // We might need to look up mapping frames or assume a sequence.

    // For now, assuming a simple draw capability.
    // However, given the engine structure, we likely need to use PatternDescs or
    // similar if we want to bypass sprite system,
    // OR create sprites for HUD elements.
    // Given the HUD overlay nature, direct pattern drawing via GraphicsManager
    // (like DebugOverlay) or a dedicated SpriteRenderManager bucket is best.
    // We'll follow the pattern of DebugOverlayManager / DebugRenderer for now, or
    // similar to RingRenderManager.

    // BUT, Sonic 2 HUD is effectively a set of sprites/patterns fixed to screen.
    // Let's assume we can draw direct patterns to screen coordinates.

    private final GraphicsManager graphicsManager;
    private int digitPatternIndex;
    private int textPatternIndex;

    // PatternDesc for standard HUD rendering (Palette 0, no flip, priority high?)
    // Priority is handled by draw order usually, but PatternDesc needs a priority
    // bit.
    // Assuming priority 1 (high).
    // PatternDesc for standard HUD rendering (Palette 1, no flip, priority high?)
    // Priority is handled by draw order usually, but PatternDesc needs a priority
    // bit.
    // Assuming priority 1 (high).
    // P=1 (bit 15), Palette=1 (bits 13-14), VFlip=0 (bit 12), HFlip=0 (bit 11).
    // 0x8000 | (1 << 13) = 0xA000
    private final PatternDesc hudPatternDesc = new PatternDesc(0xA000);

    public HudRenderManager(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
    }

    private int textPatternCount;

    public void setDigitPatternIndex(int digitPatternIndex) {
        this.digitPatternIndex = digitPatternIndex;
        System.out.println("HudRenderManager Digit Index: " + digitPatternIndex);
    }

    public void setTextPatternIndex(int textPatternIndex, int count) {
        this.textPatternIndex = textPatternIndex;
        this.textPatternCount = count;
        System.out.println("HudRenderManager Text Index: " + textPatternIndex + ", Count: " + count);
    }

    public void draw(LevelGamestate levelGamestate) {
        if (levelGamestate == null)
            return;

        // Debug logging (temporary)
        // System.out.println("Drawing HUD. Score: " +
        // GameStateManager.getInstance().getScore());

        // Draw Score
        drawHudString(16, 16, "SCORE");
        // Score: Ones digit at 104.
        drawNumberRightAligned(64, 16, GameStateManager.getInstance().getScore(), 6);

        // Draw Time
        drawHudString(16, 32, "TIME");
        // Time: Y=32.
        String timeStr = levelGamestate.getTimer().getDisplayTime(); // M:SS
        boolean flash = levelGamestate.getTimer().shouldFlash();
        if (!flash) {
            drawTime(56, 32, timeStr);
        }

        // Draw Rings
        drawHudString(16, 48, "RINGS");
        // Rings: Y=48.
        drawNumberRightAligned(72, 48, levelGamestate.getRings(), 2);

        // drawDebugStrip();
    }

    private void drawHudString(int x, int y, String text) {
        int camX = Camera.getInstance().getX();
        int camY = Camera.getInstance().getY();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int patternId = getPatternIndexForChar(c);
            if (patternId != -1) {
                // Interleaved layout: Top tile is even (2*k), Bottom tile is odd (2*k + 1)
                // Assuming "Even items were bottoms" meant "2nd, 4th..." (1-based count) ->
                // Indices 1, 3, 5...
                // If indices 0, 2, 4 were bottoms, text would be upside down in a linear strip.
                // Standard Nemesis/Interleaved is Top then Bottom.
                renderSafe(textPatternIndex + (patternId * 2), hudPatternDesc, x + camX + (i * 8), y + camY);
                renderSafe(textPatternIndex + (patternId * 2) + 1, hudPatternDesc, x + camX + (i * 8), y + camY + 8);
            }
        }
    }

    // Mapping derived from debug strip: "SCORRINGTIME"
    // Sequence in ROM: S, C, O, R, R, I, N, G, T, I, M, E
    // Indices: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    private int getPatternIndexForChar(char c) {
        return switch (c) {
            case 'S' -> 0;
            case 'C' -> 1;
            case 'O' -> 2;
            case 'R' -> 3; // Using first R (index 3). Second R is 4.
            case 'I' -> 5; // Using first I (index 5). Second I is 9.
            case 'N' -> 6;
            case 'G' -> 7;
            case 'T' -> 8;
            case 'M' -> 10;
            case 'E' -> 11;
            case ':' -> 12; // Assuming colon might be next?
            default -> -1;
        };
    }

    private void drawNumberRightAligned(int startX, int y, int value, int digits) {
        int camX = Camera.getInstance().getX();
        int camY = Camera.getInstance().getY();

        // Convert to string, pad with spaces? Or just calculate offsets.
        // Sonic 2 Score is usually 0-padded? No, user screenshot shows "100".
        // Sonic 2 Rings: "6" is right aligned.
        String str = String.valueOf(value);

        // If value is longer than digits, it extends left? Or right?
        // "Right justified" in this context usually means the last digit is at a fixed
        // position.
        // Let's assume startX is the Left edge of the field.
        // Field width = digits * 8.

        // Calculate offset for right alignment
        int padding = digits - str.length();
        if (padding < 0)
            padding = 0; // Overflow field

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int digit = c - '0';
            // Position: startX + (padding + i) * 8
            int xPos = startX + (padding + i) * 8;

            // Draw top
            renderSafe(digitPatternIndex + (digit * 2), hudPatternDesc, xPos + camX, y + camY);
            // Draw bottom
            renderSafe(digitPatternIndex + (digit * 2) + 1, hudPatternDesc, xPos + camX, y + camY + 8);
        }
    }

    private void drawTime(int x, int y, String timeStr) {
        int camX = Camera.getInstance().getX();
        int camY = Camera.getInstance().getY();
        for (int i = 0; i < timeStr.length(); i++) {
            char c = timeStr.charAt(i);
            int patternIdx;
            if (c == ':') {
                patternIdx = digitPatternIndex + 20; // Reverted to use digit patterns for colon
            } else {
                patternIdx = digitPatternIndex + ((c - '0') * 2);
            }
            // Draw top
            renderSafe(patternIdx, hudPatternDesc, x + camX + (i * 8), y + camY);
            // Draw bottom
            renderSafe(patternIdx + 1, hudPatternDesc, x + camX + (i * 8), y + camY + 8);
        }
    }

    private void renderSafe(int patternId, PatternDesc desc, int x, int y) {
        // Simple bounds check if we knew the max, but for now just catch GL errors
        // effectively?
        // Actually, just delegate. The error comes from map lookup failure.
        // We will add debug strip here.
        graphicsManager.renderPatternWithId(patternId, desc, x, y);
    }

    public void drawDebugStrip() {
        int camX = Camera.getInstance().getX();
        int camY = Camera.getInstance().getY();
        int chunks = Math.min(40, textPatternCount); // Clamp limit
        for (int i = 0; i < chunks; i++) {
            graphicsManager.renderPatternWithId(textPatternIndex + i, hudPatternDesc, camX + 10 + (i * 8), camY + 100);
        }
    }
}
