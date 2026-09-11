package com.openggf.debug;

import com.openggf.game.GameServices;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.ZoneFeatureRenderer;

import com.openggf.control.InputHandler;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Debug viewer for object art frames.
 */
public class DebugObjectArtViewer {
    private enum ArtTarget {
        SIGNPOST("Signpost"),
        RESULTS("Results"),
        CNZ_SLOTS("CNZ Slot Faces");

        private final String label;

        ArtTarget(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private enum ViewMode {
        FRAME("Frame"),
        PATTERNS("Patterns");

        private final String label;

        ViewMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private int frameIndex = 0;
    private int maxFrames = 0;
    private int maxPatterns = 0;
    private ArtTarget target = ArtTarget.SIGNPOST;
    private ViewMode viewMode = ViewMode.FRAME;
    private final int[] targetFrameIndices = new int[ArtTarget.values().length];
    private final int[] targetPatternIndices = new int[ArtTarget.values().length];
    private int paletteOverride = -1;

    private static final int GRID_COLUMNS = 16;
    private static final int GRID_ROWS = 16;

    // Y offset below HUD (HUD is ~32 pixels tall)
    private static final int DEBUG_Y_OFFSET = 48;

    public DebugObjectArtViewer() {
    }

    public void updateInput(InputHandler handler) {
        if (handler == null) {
            return;
        }
        if (!GameServices.debugOverlay().isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
            return;
        }
        if (handler.isKeyPressed(GLFW_KEY_TAB) || handler.isKeyPressed(GLFW_KEY_M)) {
            toggleViewMode();
        }
        if (handler.isKeyPressed(GLFW_KEY_PAGE_UP)) {
            stepTarget(-1);
        }
        if (handler.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
            stepTarget(1);
        }
        if (handler.isKeyPressed(GLFW_KEY_0)) {
            paletteOverride = -1;
        }
        if (handler.isKeyPressed(GLFW_KEY_1)) {
            paletteOverride = 0;
        }
        if (handler.isKeyPressed(GLFW_KEY_2)) {
            paletteOverride = 1;
        }
        if (handler.isKeyPressed(GLFW_KEY_3)) {
            paletteOverride = 2;
        }
        if (handler.isKeyPressed(GLFW_KEY_4)) {
            paletteOverride = 3;
        }
        if (viewMode == ViewMode.FRAME) {
            if (handler.isKeyPressed(GLFW_KEY_LEFT)) {
                stepFrame(-1);
            }
            if (handler.isKeyPressed(GLFW_KEY_RIGHT)) {
                stepFrame(1);
            }
        } else {
            if (handler.isKeyPressed(GLFW_KEY_LEFT)) {
                stepPatternCursor(-1);
            }
            if (handler.isKeyPressed(GLFW_KEY_RIGHT)) {
                stepPatternCursor(1);
            }
            if (handler.isKeyPressed(GLFW_KEY_UP)) {
                stepPatternCursor(-GRID_COLUMNS);
            }
            if (handler.isKeyPressed(GLFW_KEY_DOWN)) {
                stepPatternCursor(GRID_COLUMNS);
            }
            if (handler.isKeyPressed(GLFW_KEY_HOME)) {
                setPatternCursor(0);
            }
            if (handler.isKeyPressed(GLFW_KEY_END)) {
                setPatternCursor(maxPatterns - 1);
            }
        }
    }

    public void draw(ObjectRenderManager renderManager, Camera camera) {
        if (renderManager == null || camera == null) {
            return;
        }
        if (!GameServices.debugOverlay().isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
            return;
        }

        // Handle CNZ_SLOTS separately as it uses a different renderer
        if (target == ArtTarget.CNZ_SLOTS) {
            drawCnzSlots(camera);
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(renderManager);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        if (viewMode == ViewMode.FRAME) {
            int frameCount = getFrameCount(renderManager);
            setMaxFrames(frameCount);
            if (frameCount <= 0) {
                return;
            }
            PatternSpriteRenderer.FrameBounds bounds = renderer.getFrameBoundsForIndex(frameIndex);
            int drawX = camera.getX() + 16 - bounds.minX();
            int drawY = camera.getY() + DEBUG_Y_OFFSET - bounds.minY();
            renderer.drawFrameIndex(frameIndex, drawX, drawY, false, false);
        } else {
            int patternCount = getPatternCount(renderManager);
            setMaxPatterns(patternCount);
            if (patternCount <= 0) {
                return;
            }
            drawPatternGrid(renderer, camera, patternCount);
        }
    }

    /**
     * Draw CNZ slot faces for debug verification.
     * Shows all 6 faces in two rows with expected labels.
     */
    private void drawCnzSlots(Camera camera) {
        // Get the slot machine renderer from the zone feature provider
        ZoneFeatureRenderer featureRenderer = getZoneFeatureRenderer();
        if (featureRenderer == null || featureRenderer.getDebugFrameCount() == 0) {
            return;
        }

        // Get palette texture from graphics manager
        GraphicsManager gm = GameServices.graphics();
        Integer paletteTextureId = gm.getCombinedPaletteTextureId();
        if (paletteTextureId == null || paletteTextureId == 0) {
            return;
        }

        // Position below HUD (use screen coordinates, not world coordinates)
        int screenX = 16;
        int screenY = DEBUG_Y_OFFSET;

        // Create and queue the debug render command
        GLCommand cmd = featureRenderer.createDebugRenderCommand(screenX, screenY, paletteTextureId, frameIndex);
        if (cmd != null) {
            gm.registerCommand(cmd);
        }

        // Set frame info for display in panel
        setMaxFrames(featureRenderer.getDebugFrameCount());
    }

    /**
     * Get the zone feature renderer from the current game module's zone feature provider.
     */
    private ZoneFeatureRenderer getZoneFeatureRenderer() {
        try {
            ZoneFeatureProvider provider = GameServices.module().getZoneFeatureProvider();
            if (provider != null) {
                ZoneFeatureRenderer renderer = provider.getFeatureRenderer();
                return renderer != ZoneFeatureRenderer.NONE ? renderer : null;
            }
        } catch (Exception e) {
            // Not in a zone with a feature renderer
        }
        return null;
    }

    public String getTargetLabel() {
        return target.label();
    }

    public String getViewModeLabel() {
        return viewMode.label();
    }

    public boolean isPatternMode() {
        return viewMode == ViewMode.PATTERNS;
    }

    public int getPatternCursor() {
        return Math.max(0, Math.min(patternCursor(), maxPatterns - 1));
    }

    public int getPatternPageStart() {
        if (maxPatterns <= 0) {
            return 0;
        }
        int pageSize = GRID_COLUMNS * GRID_ROWS;
        int cursor = getPatternCursor();
        return (cursor / pageSize) * pageSize;
    }

    public int getPatternPageEnd() {
        if (maxPatterns <= 0) {
            return 0;
        }
        int pageStart = getPatternPageStart();
        return Math.min(maxPatterns - 1, pageStart + (GRID_COLUMNS * GRID_ROWS) - 1);
    }

    public String getPaletteLabel() {
        return paletteOverride < 0 ? "Auto" : String.format("P%d", paletteOverride);
    }

    /**
     * Check if currently viewing CNZ slot faces.
     */
    public boolean isCnzSlotsMode() {
        return target == ArtTarget.CNZ_SLOTS;
    }

    /**
     * Get slot face info for the current frame index (when in CNZ slots mode).
     * @return Array of [name, reward] strings, or null if not applicable
     */
    public String[] getSlotFaceInfo() {
        if (target != ArtTarget.CNZ_SLOTS) {
            return null;
        }
        ZoneFeatureRenderer renderer = getZoneFeatureRenderer();
        if (renderer == null) {
            return null;
        }
        int faceIndex = frameIndex % Math.max(1, renderer.getDebugFrameCount());
        String name = renderer.getDebugFrameName(faceIndex);
        String desc = renderer.getDebugFrameDescription(faceIndex);
        return (name != null || desc != null)
                ? new String[] { name != null ? name : "???", desc != null ? desc : "???" }
                : null;
    }

    public int getFrameIndex() {
        return frameIndex;
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    public int getMaxPatterns() {
        return maxPatterns;
    }

    private PatternSpriteRenderer getRenderer(ObjectRenderManager renderManager) {
        return switch (target) {
            case SIGNPOST -> renderManager.getSignpostRenderer();
            case RESULTS -> renderManager.getResultsRenderer();
            case CNZ_SLOTS -> null; // Handled separately in drawCnzSlots
        };
    }

    private int getFrameCount(ObjectRenderManager renderManager) {
        return switch (target) {
            case SIGNPOST -> renderManager.getSignpostSheet().getFrameCount();
            case RESULTS -> renderManager.getResultsSheet().getFrameCount();
            case CNZ_SLOTS -> 6; // 6 slot faces
        };
    }

    private int getPatternCount(ObjectRenderManager renderManager) {
        return switch (target) {
            case SIGNPOST -> renderManager.getSignpostSheet().getPatterns().length;
            case RESULTS -> renderManager.getResultsSheet().getPatterns().length;
            case CNZ_SLOTS -> 0; // No pattern grid view for slots
        };
    }

    private void setMaxFrames(int maxFrames) {
        this.maxFrames = Math.max(0, maxFrames);
        if (this.maxFrames == 0) {
            frameIndex = 0;
            return;
        }
        if (frameIndex >= this.maxFrames) {
            frameIndex = this.maxFrames - 1;
        }
    }

    private void setMaxPatterns(int maxPatterns) {
        this.maxPatterns = Math.max(0, maxPatterns);
        setPatternCursor(patternCursor());
    }

    private void stepFrame(int delta) {
        if (maxFrames <= 0) {
            return;
        }
        int next = frameIndex + delta;
        if (next < 0) {
            next = maxFrames - 1;
        } else if (next >= maxFrames) {
            next = 0;
        }
        frameIndex = next;
    }

    private void stepTarget(int delta) {
        ArtTarget[] targets = ArtTarget.values();
        if (targets.length == 0) {
            return;
        }
        int current = target.ordinal();
        int next = (current + delta) % targets.length;
        if (next < 0) {
            next += targets.length;
        }
        if (next == current) {
            return;
        }
        targetFrameIndices[current] = frameIndex;
        targetPatternIndices[current] = patternCursor();
        target = targets[next];
        frameIndex = targetFrameIndices[target.ordinal()];
        setPatternCursor(targetPatternIndices[target.ordinal()]);
        maxFrames = 0;
        maxPatterns = 0;
    }

    private void toggleViewMode() {
        viewMode = (viewMode == ViewMode.FRAME) ? ViewMode.PATTERNS : ViewMode.FRAME;
    }

    private int patternCursor() {
        return targetPatternIndices[target.ordinal()];
    }

    private void setPatternCursor(int value) {
        if (maxPatterns <= 0) {
            targetPatternIndices[target.ordinal()] = 0;
            return;
        }
        int clamped = Math.max(0, Math.min(value, maxPatterns - 1));
        targetPatternIndices[target.ordinal()] = clamped;
    }

    private void stepPatternCursor(int delta) {
        if (maxPatterns <= 0) {
            return;
        }
        setPatternCursor(patternCursor() + delta);
    }

    private void drawPatternGrid(PatternSpriteRenderer renderer, Camera camera, int patternCount) {
        int pageSize = GRID_COLUMNS * GRID_ROWS;
        int pageStart = (getPatternCursor() / pageSize) * pageSize;
        int paletteIndex = paletteOverride;
        int baseX = camera.getX() + 16;
        int baseY = camera.getY() + DEBUG_Y_OFFSET;
        int index = pageStart;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLUMNS; col++) {
                if (index >= patternCount) {
                    return;
                }
                int drawX = baseX + (col * Pattern.PATTERN_WIDTH);
                int drawY = baseY + (row * Pattern.PATTERN_HEIGHT);
                renderer.drawPatternIndex(index, drawX, drawY, paletteIndex);
                index++;
            }
        }
    }
}

