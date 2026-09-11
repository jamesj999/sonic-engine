package com.openggf.debug;

import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PixelFontTextRenderer;
import org.lwjgl.system.MemoryUtil;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.ShaderProgram;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders the performance profiling panel in the debug overlay.
 * Displays:
 * - Per-section timing statistics
 * - Pie chart showing relative time distribution
 * - Frame history graph showing frame time spikes
 */
public class PerformancePanelRenderer {

    static final float PERFORMANCE_TEXT_SCALE = 0.5f;

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

    /** Cached DebugColor objects for section colors to avoid per-frame allocations */
    private static final DebugColor[] SECTION_COLOR_OBJECTS;
    static {
        SECTION_COLOR_OBJECTS = new DebugColor[SECTION_COLORS.length];
        for (int i = 0; i < SECTION_COLORS.length; i++) {
            SECTION_COLOR_OBJECTS[i] = new DebugColor(
                (int)(SECTION_COLORS[i][0] * 255),
                (int)(SECTION_COLORS[i][1] * 255),
                (int)(SECTION_COLORS[i][2] * 255));
        }
    }

    /**
     * Gets a consistent color index for a section name (based on hash).
     */
    private int getColorIndexForSection(String name) {
        // Use absolute value of hash to get consistent color per section name
        return Math.abs(name.hashCode()) % SECTION_COLORS.length;
    }

    /** Target frame time at 60fps (16.67ms) */
    private static final float TARGET_FRAME_MS = 16.67f;

    private final PixelFontTextRenderer textRenderer;
    private int viewportWidth;
    private int viewportHeight;
    private double scaleX = 1.0;
    private double scaleY = 1.0;

    /** Font size for performance stats (small for compact display) */
    private static final FontSize PERF_FONT = FontSize.SMALL;

    private final GraphicsManager graphicsManager;
    private final PerformanceProfiler profiler;

    /** Base dimensions (game screen size) */
    private final int baseWidth;
    private final int baseHeight;

    /** Reusable StringBuilder for formatting stats text */
    private final StringBuilder perfBuilder = new StringBuilder(64);

    /** Reusable time-sorted section selection for the legend. */
    private final List<SectionStats> legendSections = new ArrayList<>(6);

    // VAO/VBO for primitive rendering
    private int vaoId;
    private int vboId;
    private FloatBuffer vertexBuffer;
    private static final int VERTEX_SIZE = 6; // 2 floats position + 4 floats color
    private static final int MAX_VERTICES = 512;

    // Cached uniform locations
    private int cachedProjectionLoc = -1;
    private int cachedCameraOffsetLoc = -1;
    private int lastProgramId = -1;

    public PerformancePanelRenderer(int baseWidth, int baseHeight, PixelFontTextRenderer textRenderer,
                                    GraphicsManager graphicsManager, PerformanceProfiler profiler) {
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
    }

    List<SectionStats> legendSections(ProfileSnapshot snapshot) {
        List<SectionStats> sortedSections = snapshot.getSectionsSortedByTime();
        legendSections.clear();
        int limit = Math.min(6, sortedSections.size());
        int audioIndex = -1;
        for (int i = 0; i < sortedSections.size(); i++) {
            if ("audio".equals(sortedSections.get(i).name())) {
                audioIndex = i;
                break;
            }
        }
        if (audioIndex < 0 || audioIndex < limit || limit < 6) {
            for (int i = 0; i < limit; i++) {
                legendSections.add(sortedSections.get(i));
            }
            return legendSections;
        }
        for (int i = 0; i < 5; i++) {
            legendSections.add(sortedSections.get(i));
        }
        legendSections.add(sortedSections.get(audioIndex));
        return legendSections;
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

    private void ensureBuffers() {
        if (vaoId == 0) {
            vaoId = glGenVertexArrays();
            vboId = glGenBuffers();
            vertexBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * VERTEX_SIZE);
        }
    }

    private void setupShader() {
        GraphicsManager gm = graphicsManager;
        ShaderProgram debugShader = gm.getDebugShaderProgram();
        if (debugShader == null) {
            return;
        }

        int programId = debugShader.getProgramId();
        glUseProgram(programId);

        // Cache uniform locations if program changed
        if (programId != lastProgramId) {
            cachedProjectionLoc = glGetUniformLocation(programId, "ProjectionMatrix");
            cachedCameraOffsetLoc = glGetUniformLocation(programId, "CameraOffset");
            lastProgramId = programId;
        }

        // Set projection matrix
        if (cachedProjectionLoc != -1) {
            float[] projMatrix = gm.getProjectionMatrixBuffer();
            if (projMatrix != null) {
                glUniformMatrix4fv(cachedProjectionLoc, false, projMatrix);
            }
        }

        // Set camera offset to zero - positions are in screen space
        if (cachedCameraOffsetLoc != -1) {
            glUniform2f(cachedCameraOffsetLoc, 0.0f, 0.0f);
        }
    }

    private void putVertex(float x, float y, float r, float g, float b, float a) {
        putVertex(vertexBuffer, x, y, r, g, b, a);
    }

    private static void putVertex(FloatBuffer buffer, float x, float y, float r, float g, float b, float a) {
        buffer.put(x).put(y).put(r).put(g).put(b).put(a);
    }

    private void drawVertices(int drawMode, int vertexCount) {
        if (vertexCount <= 0) return;

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);

        // Position attribute (location 0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_SIZE * 4, 0L);
        glEnableVertexAttribArray(0);

        // Color attribute (location 1)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, VERTEX_SIZE * 4, 2 * 4L);
        glEnableVertexAttribArray(1);

        glDrawArrays(drawMode, 0, vertexCount);

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders the performance panel.
     * Must be called while in 2D overlay rendering mode.
     *
     * @param snapshot The profiling data snapshot to display
     */
    public void render(ProfileSnapshot snapshot) {
        float[] projectionMatrix = graphicsManager.getProjectionMatrixBuffer();
        if (projectionMatrix != null) {
            textRenderer.setProjectionMatrix(projectionMatrix);
        }

        // Panel position in game coordinates (right side, upper area)
        // Game coords: (0,0) = bottom-left, Y increases upward
        int panelRight = baseWidth - 8;    // 8 pixels from right edge
        int panelTop = baseHeight - 8;     // 8 pixels from top (in screen terms, high Y in GL)

        if (!snapshot.hasData()) {
            textRenderer.beginBatch();
            drawTextBottomLeft("Perf: collecting...", panelRight - 80, panelTop - 10, DebugColor.WHITE);
            textRenderer.endBatch();
            return;
        }

        // Layout in game coordinates for GL primitives
        int pieRadius = 16;
        int pieCenterX = panelRight - 24;
        int pieCenterY = panelTop - 50;  // Below the text stats

        // Draw pie chart (uses game coordinates directly)
        drawPieChart(pieCenterX, pieCenterY, pieRadius, snapshot);

        // Draw frame history graph below pie chart
        int graphWidth = 80;
        int graphHeight = 25;
        int graphX = panelRight - graphWidth - 4;
        int graphY = panelTop - 115;  // Below the pie chart
        drawFrameHistoryGraph(graphX, graphY, graphWidth, graphHeight, snapshot);

        // Draw memory stats below the frame graph
        MemoryStats.Snapshot memSnapshot = profiler.memoryStats().snapshot();

        int textX = panelRight - 85;
        int textY = panelTop - 10;
        int lineHeight = lineHeight(PERF_FONT);
        StringBuilder pb = perfBuilder;

        // Batch all text into a single GL draw call
        textRenderer.beginBatch();

        // Header line - show work time and actual FPS
        double workMs = snapshot.totalFrameTimeMs();
        double budgetPct = (workMs / TARGET_FRAME_MS) * 100;
        pb.setLength(0);
        appendFixed1(pb, workMs).append("ms (");
        appendFixed0(pb, budgetPct).append("%) ");
        appendFixed1(pb, snapshot.fps()).append("fps");
        drawTextBottomLeft(pb.toString(), textX, textY, DebugColor.WHITE);

        // Section legend uses time-sorted sections, while the pie chart uses
        // a cached name-sorted view for stable slice positions.
        int legendY = textY - lineHeight;

        for (SectionStats section : legendSections(snapshot)) {
            int colorIndex = getColorIndexForSection(section.name());
            DebugColor textColor = SECTION_COLOR_OBJECTS[colorIndex];

            String name = section.name();
            pb.setLength(0);
            appendFixed1(pb, section.timeMs()).append(' ');
            pb.append(name, 0, Math.min(name.length(), 10));
            drawTextBottomLeft(pb.toString(), textX, legendY, textColor);

            legendY -= lineHeight;
        }

        // Memory stats below the frame graph
        int memY = graphY - 8;
        drawTextBottomLeft(formatHeapLine(pb, memSnapshot.heapUsedMB(),
                memSnapshot.heapMaxMB(), memSnapshot.heapPercentage()),
                textX, memY, DebugColor.LIGHT_GRAY);

        memY -= lineHeight;
        drawTextBottomLeft(formatGcLine(pb, memSnapshot.gcCount(),
                memSnapshot.gcTimeMs(), memSnapshot.allocationRateMBPerSec()),
                textX, memY, DebugColor.LIGHT_GRAY);

        // Top allocators
        List<MemoryStats.SectionAllocation> topAllocators = memSnapshot.topAllocators();
        if (!topAllocators.isEmpty()) {
            memY -= lineHeight;
            drawTextBottomLeft("Top Alloc:", textX, memY, DebugColor.ORANGE);

            for (MemoryStats.SectionAllocation alloc : topAllocators) {
                memY -= lineHeight;
                String name = alloc.name();
                pb.setLength(0);
                appendFixed1(pb, alloc.kbPerFrame()).append("KB ");
                pb.append(name, 0, Math.min(name.length(), 9));
                drawTextBottomLeft(pb.toString(), textX, memY, DebugColor.ORANGE);
            }
        }

        textRenderer.endBatch();
    }

    /**
     * Draws a pie chart showing the time distribution across sections.
     * Uses game coordinates (0-320, 0-224 with Y=0 at bottom).
     * Sections are drawn in alphabetical order for stable positioning.
     */
    private void drawPieChart(int centerX, int centerY, int radius, ProfileSnapshot snapshot) {
        List<SectionStats> sections = snapshot.getSectionsSortedByName();
        if (sections.isEmpty()) {
            return;
        }

        ensureBuffers();
        setupShader();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        vertexBuffer.clear();
        int vertexCount = 0;
        float startAngle = 90; // Start from top

        for (SectionStats section : sections) {
            float sweepAngle = (float) (section.percentage() * 360.0 / 100.0);
            if (sweepAngle < 2.0f) {
                continue; // Skip tiny slices
            }

            int colorIndex = getColorIndexForSection(section.name());
            float[] color = SECTION_COLORS[colorIndex];

            vertexCount += appendPieSliceTriangles(vertexBuffer, centerX, centerY, radius,
                    startAngle, sweepAngle, color);
            startAngle -= sweepAngle;
        }

        vertexBuffer.flip();
        drawVertices(GL_TRIANGLES, vertexCount);

        // Outline
        vertexBuffer.clear();
        int outlineVertices = 0;
        for (int a = 0; a < 360; a += 15) {
            float rad = (float) Math.toRadians(a);
            putVertex(centerX + radius * (float) Math.cos(rad),
                     centerY + radius * (float) Math.sin(rad),
                     0.7f, 0.7f, 0.7f, 1.0f);
            outlineVertices++;
        }
        vertexBuffer.flip();
        drawVertices(GL_LINE_LOOP, outlineVertices);

        glUseProgram(0);
    }

    /**
     * Draws a line graph showing recent frame time history.
     * Uses game coordinates (0-320, 0-224 with Y=0 at bottom).
     * Auto-scales based on actual data range.
     */
    private void drawFrameHistoryGraph(int x, int y, int width, int height,
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

        ensureBuffers();
        setupShader();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Background - two triangles forming a quad
        vertexBuffer.clear();
        putVertex(x, y, 0.0f, 0.0f, 0.0f, 0.6f);
        putVertex(x + width, y, 0.0f, 0.0f, 0.0f, 0.6f);
        putVertex(x + width, y + height, 0.0f, 0.0f, 0.0f, 0.6f);
        putVertex(x, y + height, 0.0f, 0.0f, 0.0f, 0.6f);
        putVertex(x + width, y + height, 0.0f, 0.0f, 0.0f, 0.6f);
        putVertex(x, y, 0.0f, 0.0f, 0.0f, 0.6f);
        vertexBuffer.flip();
        drawVertices(GL_TRIANGLES, 6);

        vertexBuffer.clear();
        int guideVertices = appendGraphLineBatch(vertexBuffer, x, y, width, height);
        vertexBuffer.flip();
        drawVertices(GL_LINES, guideVertices);

        // Frame time line
        vertexBuffer.clear();
        int lineVertices = 0;
        for (int i = 0; i < historySize; i++) {
            int idx = (currentIndex + i) % historySize;
            float frameTime = history[idx];
            float graphX = x + (float) i / historySize * width;
            float normalizedY = Math.min(frameTime / graphMax, 1.0f);
            float graphY = y + normalizedY * height;
            putVertex(graphX, graphY, 0.3f, 0.9f, 0.3f, 1.0f);
            lineVertices++;
        }
        vertexBuffer.flip();
        drawVertices(GL_LINE_STRIP, lineVertices);

        glUseProgram(0);
    }

    private static int appendPieSliceTriangles(FloatBuffer buffer, int centerX, int centerY, int radius,
                                               float startAngle, float sweepAngle, float[] color) {
        float endAngle = startAngle - sweepAngle;
        float segmentStart = startAngle;
        int vertices = 0;
        while (segmentStart > endAngle) {
            float segmentEnd = Math.max(endAngle, segmentStart - 10.0f);
            putVertex(buffer, centerX, centerY, color[0], color[1], color[2], 1.0f);
            putPieEdgeVertex(buffer, centerX, centerY, radius, segmentStart, color);
            putPieEdgeVertex(buffer, centerX, centerY, radius, segmentEnd, color);
            vertices += 3;
            segmentStart = segmentEnd;
        }
        return vertices;
    }

    private static void putPieEdgeVertex(FloatBuffer buffer, int centerX, int centerY, int radius,
                                         float angle, float[] color) {
        float rad = (float) Math.toRadians(angle);
        putVertex(buffer,
                centerX + radius * (float) Math.cos(rad),
                centerY + radius * (float) Math.sin(rad),
                color[0], color[1], color[2], 1.0f);
    }

    private static int appendGraphLineBatch(FloatBuffer buffer, int x, int y, int width, int height) {
        float midY = y + 0.5f * height;
        putVertex(buffer, x, midY, 0.3f, 0.3f, 0.3f, 1.0f);
        putVertex(buffer, x + width, midY, 0.3f, 0.3f, 0.3f, 1.0f);

        putVertex(buffer, x, y, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x + width, y, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x + width, y, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x + width, y + height, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x + width, y + height, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x, y + height, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x, y + height, 0.5f, 0.5f, 0.5f, 1.0f);
        putVertex(buffer, x, y, 0.5f, 0.5f, 0.5f, 1.0f);
        return 10;
    }

    private void drawTextBottomLeft(String text, int x, int y, DebugColor color) {
        int topLeftY = baseHeight - y - PixelFont.scaledGlyphHeight(PERFORMANCE_TEXT_SCALE);
        textRenderer.drawShadowedText(text, x, topLeftY, color, PERFORMANCE_TEXT_SCALE);
    }

    private static int lineHeight(FontSize fontSize) {
        int baseHeight = switch (fontSize) {
            case SMALL -> 10;
            case MEDIUM -> 12;
            case LARGE -> 14;
        };
        return Math.max(1, Math.round(baseHeight * PERFORMANCE_TEXT_SCALE));
    }

    private static String formatHeapLine(StringBuilder sb, double heapUsedMB, double heapMaxMB, int heapPercentage) {
        sb.setLength(0);
        sb.append("Heap ");
        appendFixed0(sb, heapUsedMB).append('/');
        appendFixed0(sb, heapMaxMB).append(' ');
        sb.append(heapPercentage).append('%');
        return sb.toString();
    }

    private static String formatGcLine(StringBuilder sb, long gcCount, long gcTimeMs, double allocationRateMBPerSec) {
        sb.setLength(0);
        sb.append("GC ").append(gcCount).append(' ')
                .append(gcTimeMs).append("ms ");
        appendFixed1(sb, allocationRateMBPerSec).append("M/s");
        return sb.toString();
    }

    /** Append a double formatted to 1 decimal place without String.format. */
    private static StringBuilder appendFixed1(StringBuilder sb, double value) {
        if (value < 0) { sb.append('-'); value = -value; }
        long scaled = Math.round(value * 10);
        sb.append(scaled / 10).append('.').append(scaled % 10);
        return sb;
    }

    /** Append a double formatted to 0 decimal places without String.format. */
    private static StringBuilder appendFixed0(StringBuilder sb, double value) {
        sb.append(Math.round(value));
        return sb;
    }

    /** Cleanup GL resources */
    public void cleanup() {
        if (vboId != 0) {
            glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (vertexBuffer != null) {
            MemoryUtil.memFree(vertexBuffer);
            vertexBuffer = null;
        }
    }
}
