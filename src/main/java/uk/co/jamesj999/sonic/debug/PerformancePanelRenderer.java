package uk.co.jamesj999.sonic.debug;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.awt.TextRenderer;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

/**
 * Renders the performance profiling panel in the debug overlay.
 * Displays:
 * - Per-section timing statistics
 * - Pie chart showing relative time distribution
 * - Frame history graph showing frame time spikes
 */
public class PerformancePanelRenderer {

    /** Colors for pie chart sections (distinct, easy to differentiate) */
    private static final float[][] SECTION_COLORS = {
            {0.2f, 0.6f, 1.0f},   // Blue
            {1.0f, 0.4f, 0.4f},   // Red
            {0.4f, 0.9f, 0.4f},   // Green
            {1.0f, 0.8f, 0.2f},   // Yellow
            {0.8f, 0.4f, 0.9f},   // Purple
            {1.0f, 0.6f, 0.2f},   // Orange
            {0.4f, 0.9f, 0.9f},   // Cyan
            {0.9f, 0.6f, 0.8f},   // Pink
    };

    /**
     * Gets a consistent color index for a section name (based on hash).
     */
    private int getColorIndexForSection(String name) {
        // Use absolute value of hash to get consistent color per section name
        return Math.abs(name.hashCode()) % SECTION_COLORS.length;
    }

    /** Target frame time at 60fps (16.67ms) */
    private static final float TARGET_FRAME_MS = 16.67f;

    private TextRenderer textRenderer;
    private int viewportWidth;
    private int viewportHeight;
    private double scaleX = 1.0;
    private double scaleY = 1.0;

    /** Base dimensions (game screen size) */
    private final int baseWidth;
    private final int baseHeight;

    public PerformancePanelRenderer(int baseWidth, int baseHeight) {
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
    }

    /**
     * Updates the viewport dimensions for scaling.
     */
    public void updateViewport(int viewportWidth, int viewportHeight) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.scaleX = viewportWidth / (double) baseWidth;
        this.scaleY = viewportHeight / (double) baseHeight;
    }

    /**
     * Renders the performance panel.
     * Must be called while in 2D overlay rendering mode.
     *
     * @param gl The OpenGL context
     * @param snapshot The profiling data snapshot to display
     */
    public void render(GL2 gl, ProfileSnapshot snapshot) {
        if (textRenderer == null) {
            textRenderer = new TextRenderer(new Font("SansSerif", Font.PLAIN, 10), true, true);
        }

        // Panel position in game coordinates (right side, upper area)
        // Game coords: (0,0) = bottom-left, Y increases upward
        int panelRight = baseWidth - 8;    // 8 pixels from right edge
        int panelTop = baseHeight - 8;     // 8 pixels from top (in screen terms, high Y in GL)

        if (!snapshot.hasData()) {
            textRenderer.beginRendering(viewportWidth, viewportHeight);
            drawOutlined(textRenderer, "Perf: collecting...", uiX(panelRight - 80), uiY(panelTop - 10), Color.WHITE);
            textRenderer.endRendering();
            return;
        }

        // Layout in game coordinates for GL primitives
        int pieRadius = 16;
        int pieCenterX = panelRight - 24;
        int pieCenterY = panelTop - 50;  // Below the text stats

        // Draw pie chart (uses game coordinates directly)
        drawPieChart(gl, pieCenterX, pieCenterY, pieRadius, snapshot);

        // Draw frame history graph below pie chart
        int graphWidth = 80;
        int graphHeight = 25;
        int graphX = panelRight - graphWidth - 4;
        int graphY = panelTop - 115;  // Below the pie chart
        drawFrameHistoryGraph(gl, graphX, graphY, graphWidth, graphHeight, snapshot);

        // Draw text stats (uses viewport coordinates via uiX/uiY)
        textRenderer.beginRendering(viewportWidth, viewportHeight);

        int textX = uiX(panelRight - 85);
        int textY = uiY(panelTop - 10);
        int lineHeight = Math.max(8, uiY(9));

        // Header line - show work time and actual FPS
        // Work time is how long the frame took to process (should be < 16.7ms for 60fps)
        double workMs = snapshot.totalFrameTimeMs();
        double budgetPct = (workMs / TARGET_FRAME_MS) * 100;
        drawOutlined(textRenderer, String.format("%.1fms (%.0f%%) %.0ffps", workMs, budgetPct, snapshot.fps()),
                textX, textY, Color.WHITE);

        // Section legend
        List<SectionStats> sections = snapshot.getSectionsSortedByTime();
        int legendY = textY - lineHeight;

        int count = 0;
        for (SectionStats section : sections) {
            int colorIndex = getColorIndexForSection(section.name());
            float[] color = SECTION_COLORS[colorIndex];
            Color textColor = new Color(color[0], color[1], color[2]);

            String name = section.name();
            if (name.length() > 10) {
                name = name.substring(0, 10);
            }
            String line = String.format("%.1f %s", section.timeMs(), name);
            drawOutlined(textRenderer, line, textX, legendY, textColor);

            legendY -= lineHeight;
            count++;

            if (count >= 6) {
                break;
            }
        }

        textRenderer.endRendering();
    }

    /**
     * Draws a pie chart showing the time distribution across sections.
     * Uses game coordinates (0-320, 0-224 with Y=0 at bottom).
     * Sections are drawn in alphabetical order for stable positioning.
     */
    private void drawPieChart(GL2 gl, int centerX, int centerY, int radius, ProfileSnapshot snapshot) {
        // Sort by name for stable pie chart positioning
        List<SectionStats> sections = snapshot.getSectionsSortedByTime().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
        if (sections.isEmpty()) {
            return;
        }

        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);

        float startAngle = 90; // Start from top

        for (SectionStats section : sections) {
            float sweepAngle = (float) (section.percentage() * 360.0 / 100.0);
            if (sweepAngle < 2.0f) {
                continue; // Skip tiny slices
            }

            int colorIndex = getColorIndexForSection(section.name());
            float[] color = SECTION_COLORS[colorIndex];
            gl.glColor3f(color[0], color[1], color[2]);

            gl.glBegin(GL2.GL_TRIANGLE_FAN);
            gl.glVertex2f(centerX, centerY);

            for (float a = startAngle; a >= startAngle - sweepAngle; a -= 10) {
                float rad = (float) Math.toRadians(a);
                gl.glVertex2f(centerX + radius * (float) Math.cos(rad),
                              centerY + radius * (float) Math.sin(rad));
            }
            float endRad = (float) Math.toRadians(startAngle - sweepAngle);
            gl.glVertex2f(centerX + radius * (float) Math.cos(endRad),
                          centerY + radius * (float) Math.sin(endRad));
            gl.glEnd();

            startAngle -= sweepAngle;
        }

        // Outline
        gl.glColor3f(0.7f, 0.7f, 0.7f);
        gl.glBegin(GL2.GL_LINE_LOOP);
        for (int a = 0; a < 360; a += 15) {
            float rad = (float) Math.toRadians(a);
            gl.glVertex2f(centerX + radius * (float) Math.cos(rad),
                          centerY + radius * (float) Math.sin(rad));
        }
        gl.glEnd();

        gl.glEnable(GL2.GL_TEXTURE_2D);
    }

    /**
     * Draws a line graph showing recent frame time history.
     * Uses game coordinates (0-320, 0-224 with Y=0 at bottom).
     * Auto-scales based on actual data range.
     */
    private void drawFrameHistoryGraph(GL2 gl, int x, int y, int width, int height,
                                        ProfileSnapshot snapshot) {
        float[] history = snapshot.frameHistory();
        int currentIndex = snapshot.historyIndex();
        int historySize = history.length;

        // Find max value for auto-scaling
        float maxVal = 0.1f; // Minimum scale of 0.1ms
        for (float val : history) {
            if (val > maxVal) {
                maxVal = val;
            }
        }
        // Add 20% headroom and round up to nice values
        float graphMax = maxVal * 1.2f;
        if (graphMax < 1.0f) {
            graphMax = (float) Math.ceil(graphMax * 10) / 10; // Round to 0.1ms
        } else if (graphMax < 5.0f) {
            graphMax = (float) Math.ceil(graphMax * 2) / 2; // Round to 0.5ms
        } else {
            graphMax = (float) Math.ceil(graphMax); // Round to 1ms
        }

        gl.glDisable(GL2.GL_TEXTURE_2D);
        gl.glEnable(GL2.GL_BLEND);

        // Background
        gl.glColor4f(0.0f, 0.0f, 0.0f, 0.6f);
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(x, y);
        gl.glVertex2f(x + width, y);
        gl.glVertex2f(x + width, y + height);
        gl.glVertex2f(x, y + height);
        gl.glEnd();

        // Mid-line (at 50% of scale)
        float midY = y + 0.5f * height;
        gl.glColor3f(0.3f, 0.3f, 0.3f);
        gl.glBegin(GL2.GL_LINES);
        gl.glVertex2f(x, midY);
        gl.glVertex2f(x + width, midY);
        gl.glEnd();

        // Frame time line
        gl.glColor3f(0.3f, 0.9f, 0.3f);
        gl.glBegin(GL2.GL_LINE_STRIP);
        for (int i = 0; i < historySize; i++) {
            int idx = (currentIndex + i) % historySize;
            float frameTime = history[idx];
            float graphX = x + (float) i / historySize * width;
            float normalizedY = Math.min(frameTime / graphMax, 1.0f);
            float graphY = y + normalizedY * height;
            gl.glVertex2f(graphX, graphY);
        }
        gl.glEnd();

        // Border
        gl.glColor3f(0.5f, 0.5f, 0.5f);
        gl.glBegin(GL2.GL_LINE_LOOP);
        gl.glVertex2f(x, y);
        gl.glVertex2f(x + width, y);
        gl.glVertex2f(x + width, y + height);
        gl.glVertex2f(x, y + height);
        gl.glEnd();

        gl.glEnable(GL2.GL_TEXTURE_2D);
    }

    private void drawOutlined(TextRenderer renderer, String text, int x, int y, Color color) {
        renderer.setColor(Color.BLACK);
        renderer.draw(text, x - 1, y);
        renderer.draw(text, x + 1, y);
        renderer.draw(text, x, y - 1);
        renderer.draw(text, x, y + 1);
        renderer.setColor(color);
        renderer.draw(text, x, y);
    }

    /** Scale game X to viewport X (for TextRenderer) */
    private int uiX(int gameX) {
        return (int) Math.round(gameX * scaleX);
    }

    /** Scale game Y to viewport Y (for TextRenderer) */
    private int uiY(int gameY) {
        return (int) Math.round(gameY * scaleY);
    }
}
