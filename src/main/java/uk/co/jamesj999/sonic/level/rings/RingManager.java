package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.List;

/**
 * Handles ring collection state, sparkle animation, and rendering.
 */
public class RingManager {
    private final RingPlacementManager placementManager;
    private final RingRenderManager renderManager;
    private final int sparkleStartIndex;
    private final int sparkleFrameCount;
    private final int frameDelay;
    private RingRenderManager.RingFrameBounds spinBounds;

    public RingManager(RingPlacementManager placementManager, RingRenderManager renderManager) {
        this.placementManager = placementManager;
        this.renderManager = renderManager;
        if (renderManager != null) {
            this.sparkleStartIndex = renderManager.getSparkleStartIndex();
            this.sparkleFrameCount = renderManager.getSparkleFrameCount();
            this.frameDelay = Math.max(1, renderManager.getFrameDelay());
        } else {
            this.sparkleStartIndex = 0;
            this.sparkleFrameCount = 0;
            this.frameDelay = 1;
        }
    }

    public void update(int cameraX, AbstractPlayableSprite player, int frameCounter) {
        if (placementManager == null) {
            return;
        }
        placementManager.update(cameraX);
        if (player == null) {
            return;
        }
        if (renderManager == null) {
            return;
        }

        RingRenderManager.RingFrameBounds bounds = getSpinBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }

        Collection<RingSpawn> active = placementManager.getActiveSpawns();
        if (active.isEmpty()) {
            return;
        }

        int playerLeft = player.getX();
        int playerTop = player.getY();
        int playerRight = playerLeft + player.getWidth();
        int playerBottom = playerTop + player.getHeight();

        for (RingSpawn ring : active) {
            int index = placementManager.getSpawnIndex(ring);
            if (index < 0 || placementManager.isCollected(index)) {
                continue;
            }

            int ringLeft = ring.x() + bounds.minX();
            int ringRight = ring.x() + bounds.maxX();
            int ringTop = ring.y() + bounds.minY();
            int ringBottom = ring.y() + bounds.maxY();

            if (playerRight < ringLeft || playerLeft > ringRight || playerBottom < ringTop || playerTop > ringBottom) {
                continue;
            }

            placementManager.markCollected(index);
            if (sparkleFrameCount > 0) {
                placementManager.setSparkleStartFrame(index, frameCounter);
            }
            AudioManager.getInstance().playSfx(GameSound.RING);
            player.addRings(1);
        }
    }

    public void draw(int frameCounter) {
        if (placementManager == null || renderManager == null) {
            return;
        }
        Collection<RingSpawn> active = placementManager.getActiveSpawns();
        if (active == null || active.isEmpty()) {
            return;
        }

        int spinFrameIndex = renderManager.getSpinFrameIndex(frameCounter);
        for (RingSpawn ring : active) {
            int index = placementManager.getSpawnIndex(ring);
            if (index < 0) {
                continue;
            }
            if (!placementManager.isCollected(index)) {
                renderManager.drawFrameIndex(spinFrameIndex, ring.x(), ring.y());
                continue;
            }

            int sparkleStartFrame = placementManager.getSparkleStartFrame(index);
            if (sparkleStartFrame < 0 || sparkleFrameCount <= 0) {
                continue;
            }

            int elapsed = frameCounter - sparkleStartFrame;
            if (elapsed < 0) {
                elapsed = 0;
            }
            int sparkleFrameOffset = elapsed / frameDelay;
            if (sparkleFrameOffset >= sparkleFrameCount) {
                placementManager.clearSparkle(index);
                continue;
            }
            int sparkleFrameIndex = sparkleStartIndex + sparkleFrameOffset;
            renderManager.drawFrameIndex(sparkleFrameIndex, ring.x(), ring.y());
        }
    }

    public boolean isRenderable(RingSpawn ring, int frameCounter) {
        if (placementManager == null || ring == null) {
            return false;
        }
        int index = placementManager.getSpawnIndex(ring);
        if (index < 0) {
            return false;
        }
        if (!placementManager.isCollected(index)) {
            return true;
        }
        int sparkleStartFrame = placementManager.getSparkleStartFrame(index);
        if (sparkleStartFrame < 0 || sparkleFrameCount <= 0) {
            return false;
        }
        int elapsed = frameCounter - sparkleStartFrame;
        if (elapsed < 0) {
            return true;
        }
        int sparkleFrameOffset = elapsed / frameDelay;
        if (sparkleFrameOffset >= sparkleFrameCount) {
            placementManager.clearSparkle(index);
            return false;
        }
        return true;
    }

    public RingRenderManager.RingFrameBounds getSpinBounds() {
        if (spinBounds == null && renderManager != null) {
            spinBounds = renderManager.getSpinBounds();
        }
        return spinBounds != null ? spinBounds : new RingRenderManager.RingFrameBounds(0, 0, 0, 0);
    }

    public Collection<RingSpawn> getActiveSpawns() {
        if (placementManager == null) {
            return List.of();
        }
        return placementManager.getActiveSpawns();
    }
}
