package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;

import java.awt.event.KeyEvent;

/**
 * Debug viewer for object art frames.
 */
public class DebugObjectArtViewer {
    private static DebugObjectArtViewer instance;

    private enum ArtTarget {
        SIGNPOST("Signpost"),
        RESULTS("Results");

        private final String label;

        ArtTarget(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private int frameIndex = 0;
    private int maxFrames = 0;
    private ArtTarget target = ArtTarget.SIGNPOST;
    private final int[] targetFrameIndices = new int[ArtTarget.values().length];

    public static synchronized DebugObjectArtViewer getInstance() {
        if (instance == null) {
            instance = new DebugObjectArtViewer();
        }
        return instance;
    }

    public void updateInput(InputHandler handler) {
        if (handler == null) {
            return;
        }
        if (!DebugOverlayManager.getInstance().isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
            return;
        }
        if (handler.isKeyPressed(KeyEvent.VK_LEFT)) {
            stepFrame(-1);
        }
        if (handler.isKeyPressed(KeyEvent.VK_RIGHT)) {
            stepFrame(1);
        }
        if (handler.isKeyPressed(KeyEvent.VK_PAGE_UP)) {
            stepTarget(-1);
        }
        if (handler.isKeyPressed(KeyEvent.VK_PAGE_DOWN)) {
            stepTarget(1);
        }
    }

    public void draw(ObjectRenderManager renderManager, Camera camera) {
        if (renderManager == null || camera == null) {
            return;
        }
        if (!DebugOverlayManager.getInstance().isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(renderManager);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        int frameCount = getFrameCount(renderManager);
        setMaxFrames(frameCount);
        if (frameCount <= 0) {
            return;
        }
        int drawX = camera.getX() + 48;
        int drawY = camera.getY() + 64;
        renderer.drawFrameIndex(frameIndex, drawX, drawY, false, false);
    }

    public String getTargetLabel() {
        return target.label();
    }

    public int getFrameIndex() {
        return frameIndex;
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    private PatternSpriteRenderer getRenderer(ObjectRenderManager renderManager) {
        return switch (target) {
            case SIGNPOST -> renderManager.getSignpostRenderer();
            case RESULTS -> renderManager.getResultsRenderer();
        };
    }

    private int getFrameCount(ObjectRenderManager renderManager) {
        return switch (target) {
            case SIGNPOST -> renderManager.getSignpostSheet().getFrameCount();
            case RESULTS -> renderManager.getResultsSheet().getFrameCount();
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
        target = targets[next];
        frameIndex = targetFrameIndices[target.ordinal()];
        maxFrames = 0;
    }
}
