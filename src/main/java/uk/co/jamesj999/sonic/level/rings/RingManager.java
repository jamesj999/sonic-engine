package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.camera.Camera;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Handles ring collection state, sparkle animation, rendering, and lost-ring behavior.
 */
public class RingManager {
    private final RingPlacement placement;
    private final RingRenderer renderer;
    private final LostRingPool lostRings;
    private PatternSpriteRenderer.FrameBounds spinBounds;

    public RingManager(List<RingSpawn> spawns, RingSpriteSheet spriteSheet,
            LevelManager levelManager, TouchResponseTable touchResponseTable) {
        this.placement = new RingPlacement(spawns);
        this.renderer = (spriteSheet != null && spriteSheet.getFrameCount() > 0)
                ? new RingRenderer(spriteSheet)
                : null;
        this.lostRings = new LostRingPool(levelManager, this.renderer, touchResponseTable);
    }

    public void reset(int cameraX) {
        placement.reset(cameraX);
        lostRings.reset();
        spinBounds = null;
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        if (renderer != null) {
            renderer.ensurePatternsCached(graphicsManager, basePatternIndex);
        }
    }

    public void update(int cameraX, AbstractPlayableSprite player, int frameCounter) {
        placement.update(cameraX);
        if (player == null || player.getDead() || renderer == null) {
            return;
        }

        PatternSpriteRenderer.FrameBounds bounds = getSpinBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }

        Collection<RingSpawn> active = placement.getActiveSpawns();
        if (active.isEmpty()) {
            return;
        }

        int playerLeft = player.getX();
        int playerTop = player.getY();
        int playerRight = playerLeft + player.getWidth();
        int playerBottom = playerTop + player.getHeight();

        for (RingSpawn ring : active) {
            int index = placement.getSpawnIndex(ring);
            if (index < 0 || placement.isCollected(index)) {
                continue;
            }

            int ringLeft = ring.x() + bounds.minX();
            int ringRight = ring.x() + bounds.maxX();
            int ringTop = ring.y() + bounds.minY();
            int ringBottom = ring.y() + bounds.maxY();

            if (playerRight < ringLeft || playerLeft > ringRight || playerBottom < ringTop || playerTop > ringBottom) {
                continue;
            }

            placement.markCollected(index);
            if (renderer.getSparkleFrameCount() > 0) {
                placement.setSparkleStartFrame(index, frameCounter);
            }
            AudioManager.getInstance().playSfx(GameSound.RING);
            player.addRings(1);
        }
    }

    public void updateLostRings(AbstractPlayableSprite player, int frameCounter) {
        lostRings.update(player, frameCounter);
    }

    public void draw(int frameCounter) {
        if (renderer == null) {
            return;
        }
        Collection<RingSpawn> active = placement.getActiveSpawns();
        if (active == null || active.isEmpty()) {
            return;
        }

        int spinFrameIndex = renderer.getSpinFrameIndex(frameCounter);
        for (RingSpawn ring : active) {
            int index = placement.getSpawnIndex(ring);
            if (index < 0) {
                continue;
            }
            if (!placement.isCollected(index)) {
                renderer.drawFrameIndex(spinFrameIndex, ring.x(), ring.y());
                continue;
            }

            int sparkleStartFrame = placement.getSparkleStartFrame(index);
            if (sparkleStartFrame < 0 || renderer.getSparkleFrameCount() <= 0) {
                continue;
            }

            int elapsed = frameCounter - sparkleStartFrame;
            if (elapsed < 0) {
                elapsed = 0;
            }
            int sparkleFrameOffset = elapsed / renderer.getFrameDelay();
            if (sparkleFrameOffset >= renderer.getSparkleFrameCount()) {
                placement.clearSparkle(index);
                continue;
            }
            int sparkleFrameIndex = renderer.getSparkleStartIndex() + sparkleFrameOffset;
            renderer.drawFrameIndex(sparkleFrameIndex, ring.x(), ring.y());
        }
    }

    public void drawLostRings(int frameCounter) {
        lostRings.draw(frameCounter);
    }

    public void spawnLostRings(AbstractPlayableSprite player, int ringCount, int frameCounter) {
        lostRings.spawnLostRings(player, ringCount, frameCounter);
    }

    public boolean areAllCollected() {
        return placement.areAllCollected();
    }

    public boolean isRenderable(RingSpawn ring, int frameCounter) {
        if (ring == null) {
            return false;
        }
        int index = placement.getSpawnIndex(ring);
        if (index < 0) {
            return false;
        }
        if (!placement.isCollected(index)) {
            return true;
        }
        int sparkleStartFrame = placement.getSparkleStartFrame(index);
        if (sparkleStartFrame < 0 || renderer == null || renderer.getSparkleFrameCount() <= 0) {
            return false;
        }
        int elapsed = frameCounter - sparkleStartFrame;
        if (elapsed < 0) {
            return true;
        }
        int sparkleFrameOffset = elapsed / renderer.getFrameDelay();
        if (sparkleFrameOffset >= renderer.getSparkleFrameCount()) {
            placement.clearSparkle(index);
            return false;
        }
        return true;
    }

    public boolean isCollected(RingSpawn ring) {
        if (ring == null) {
            return false;
        }
        int index = placement.getSpawnIndex(ring);
        return placement.isCollected(index);
    }

    public int getSparkleStartFrame(RingSpawn ring) {
        if (ring == null) {
            return -1;
        }
        int index = placement.getSpawnIndex(ring);
        return placement.getSparkleStartFrame(index);
    }

    public PatternSpriteRenderer.FrameBounds getSpinBounds() {
        if (spinBounds == null) {
            spinBounds = renderer != null ? renderer.getSpinBounds() : new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0);
        }
        return spinBounds;
    }

    public PatternSpriteRenderer.FrameBounds getFrameBounds(int frameCounter) {
        if (renderer == null) {
            return new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0);
        }
        return renderer.getFrameBounds(frameCounter);
    }

    public int getSparkleStartIndex() {
        return renderer != null ? renderer.getSparkleStartIndex() : 0;
    }

    public int getSparkleFrameCount() {
        return renderer != null ? renderer.getSparkleFrameCount() : 0;
    }

    public int getFrameDelay() {
        return renderer != null ? renderer.getFrameDelay() : 1;
    }

    public void drawFrameIndex(int frameIndex, int originX, int originY) {
        if (renderer != null) {
            renderer.drawFrameIndex(frameIndex, originX, originY);
        }
    }

    public boolean hasRenderer() {
        return renderer != null;
    }

    public Collection<RingSpawn> getActiveSpawns() {
        return placement.getActiveSpawns();
    }

    private static final class RingPlacement extends AbstractPlacementManager<RingSpawn> {
        private static final int LOAD_AHEAD = 0x280;
        private static final int UNLOAD_BEHIND = 0x300;
        private static final int NO_SPARKLE = -1;

        private final BitSet collected = new BitSet();
        private final int[] sparkleStartFrames;
        private int cursorIndex = 0;
        private int lastCameraX = Integer.MIN_VALUE;

        private RingPlacement(List<RingSpawn> spawns) {
            super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
            this.sparkleStartFrames = new int[this.spawns.size()];
            Arrays.fill(this.sparkleStartFrames, NO_SPARKLE);
        }

        private void reset(int cameraX) {
            active.clear();
            collected.clear();
            Arrays.fill(sparkleStartFrames, NO_SPARKLE);
            cursorIndex = 0;
            lastCameraX = cameraX;
            refreshWindow(cameraX);
        }

        private boolean isCollected(int index) {
            return index >= 0 && collected.get(index);
        }

        private void markCollected(int index) {
            if (index >= 0) {
                collected.set(index);
            }
        }

        private int getSparkleStartFrame(int index) {
            if (index < 0 || index >= sparkleStartFrames.length) {
                return NO_SPARKLE;
            }
            return sparkleStartFrames[index];
        }

        private void setSparkleStartFrame(int index, int startFrame) {
            if (index < 0 || index >= sparkleStartFrames.length) {
                return;
            }
            sparkleStartFrames[index] = startFrame;
        }

        private void clearSparkle(int index) {
            if (index < 0 || index >= sparkleStartFrames.length) {
                return;
            }
            sparkleStartFrames[index] = NO_SPARKLE;
        }

        private void update(int cameraX) {
            if (spawns.isEmpty()) {
                return;
            }
            if (lastCameraX == Integer.MIN_VALUE) {
                reset(cameraX);
                return;
            }

            int delta = cameraX - lastCameraX;
            if (delta < 0 || delta > (getLoadAhead() + getUnloadBehind())) {
                refreshWindow(cameraX);
            } else {
                spawnForward(cameraX);
                trimActive(cameraX);
            }

            lastCameraX = cameraX;
        }

        private void spawnForward(int cameraX) {
            int spawnLimit = cameraX + getLoadAhead();
            while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() <= spawnLimit) {
                active.add(spawns.get(cursorIndex));
                cursorIndex++;
            }
        }

        private void trimActive(int cameraX) {
            int windowStart = getWindowStart(cameraX);
            int windowEnd = getWindowEnd(cameraX);
            Iterator<RingSpawn> iterator = active.iterator();
            while (iterator.hasNext()) {
                RingSpawn spawn = iterator.next();
                if (spawn.x() < windowStart || spawn.x() > windowEnd) {
                    iterator.remove();
                }
            }
        }

        private void refreshWindow(int cameraX) {
            int windowStart = getWindowStart(cameraX);
            int windowEnd = getWindowEnd(cameraX);
            int start = lowerBound(windowStart);
            int end = upperBound(windowEnd);
            cursorIndex = end;
            active.clear();
            for (int i = start; i < end; i++) {
                active.add(spawns.get(i));
            }
        }

        private boolean areAllCollected() {
            return !spawns.isEmpty() && collected.cardinality() >= spawns.size();
        }
    }

    private static final class RingRenderer {
        private final RingSpriteSheet spriteSheet;
        private final PatternSpriteRenderer renderer;
        private PatternSpriteRenderer.FrameBounds spinBoundsCache;

        private RingRenderer(RingSpriteSheet spriteSheet) {
            this.spriteSheet = spriteSheet;
            this.renderer = new PatternSpriteRenderer(spriteSheet);
        }

        private void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
            renderer.ensurePatternsCached(graphicsManager, basePatternIndex);
        }

        private int getSpinFrameIndex(int frameCounter) {
            int frameCount = spriteSheet.getSpinFrameCount();
            if (frameCount <= 0) {
                frameCount = spriteSheet.getFrameCount();
            }
            if (frameCount <= 0) {
                return 0;
            }
            int delay = Math.max(1, spriteSheet.getFrameDelay());
            return (frameCounter / delay) % frameCount;
        }

        private PatternSpriteRenderer.FrameBounds getFrameBounds(int frameCounter) {
            return renderer.getFrameBoundsForIndex(getSpinFrameIndex(frameCounter));
        }

        private PatternSpriteRenderer.FrameBounds getSpinBounds() {
            if (spinBoundsCache != null) {
                return spinBoundsCache;
            }
            int spinCount = spriteSheet.getSpinFrameCount();
            if (spinCount <= 0) {
                spinCount = spriteSheet.getFrameCount();
            }
            if (spinCount <= 0) {
                spinBoundsCache = new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0);
                return spinBoundsCache;
            }
            boolean first = true;
            int minX = 0;
            int minY = 0;
            int maxX = 0;
            int maxY = 0;
            for (int i = 0; i < spinCount; i++) {
                PatternSpriteRenderer.FrameBounds bounds = renderer.getFrameBoundsForIndex(i);
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    continue;
                }
                if (first) {
                    minX = bounds.minX();
                    minY = bounds.minY();
                    maxX = bounds.maxX();
                    maxY = bounds.maxY();
                    first = false;
                } else {
                    minX = Math.min(minX, bounds.minX());
                    minY = Math.min(minY, bounds.minY());
                    maxX = Math.max(maxX, bounds.maxX());
                    maxY = Math.max(maxY, bounds.maxY());
                }
            }
            spinBoundsCache = first ? new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0)
                    : new PatternSpriteRenderer.FrameBounds(minX, minY, maxX, maxY);
            return spinBoundsCache;
        }

        private void drawFrameIndex(int frameIndex, int originX, int originY) {
            renderer.drawFrameIndex(frameIndex, originX, originY);
        }

        private int getSparkleStartIndex() {
            return spriteSheet.getSparkleStartIndex();
        }

        private int getSparkleFrameCount() {
            return spriteSheet.getSparkleFrameCount();
        }

        private int getFrameDelay() {
            return Math.max(1, spriteSheet.getFrameDelay());
        }
    }

    private static final class LostRingPool {
        private static final int MAX_LOST_RINGS = 0x20;
        private static final int GRAVITY = 0x18;
        private static final int LIFETIME_FRAMES = 0xFF;
        private static final int RING_TOUCH_SIZE_INDEX = 0x07;
        private static final int SOLIDITY_TOP = 0x0C;

        private static final short[] SINE_TABLE = {
                0, 6, 12, 18, 25, 31, 37, 43, 49, 56, 62, 68, 74, 80, 86, 92,
                97, 103, 109, 115, 120, 126, 131, 136, 142, 147, 152, 157, 162, 167, 171, 176,
                181, 185, 189, 193, 197, 201, 205, 209, 212, 216, 219, 222, 225, 228, 231, 234,
                236, 238, 241, 243, 244, 246, 248, 249, 251, 252, 253, 254, 254, 255, 255, 255,
                256, 255, 255, 255, 254, 254, 253, 252, 251, 249, 248, 246, 244, 243, 241, 238,
                236, 234, 231, 228, 225, 222, 219, 216, 212, 209, 205, 201, 197, 193, 189, 185,
                181, 176, 171, 167, 162, 157, 152, 147, 142, 136, 131, 126, 120, 115, 109, 103,
                97, 92, 86, 80, 74, 68, 62, 56, 49, 43, 37, 31, 25, 18, 12, 6,
                0, -6, -12, -18, -25, -31, -37, -43, -49, -56, -62, -68, -74, -80, -86, -92,
                -97, -103, -109, -117, -120, -126, -131, -136, -142, -147, -152, -157, -162, -167, -171, -176,
                -181, -185, -189, -193, -197, -201, -205, -209, -212, -216, -219, -222, -225, -228, -231, -234,
                -236, -238, -241, -243, -244, -246, -248, -249, -251, -252, -253, -254, -254, -255, -255, -255,
                -256, -255, -255, -255, -254, -254, -253, -252, -251, -249, -248, -246, -244, -243, -241, -238,
                -236, -234, -231, -228, -225, -222, -219, -216, -212, -209, -205, -201, -197, -193, -189, -185,
                -181, -176, -171, -167, -162, -157, -152, -147, -142, -136, -131, -126, -120, -117, -109, -103,
                -97, -92, -86, -80, -74, -68, -62, -56, -49, -43, -37, -31, -25, -18, -12, -6,
                0, 6, 12, 18, 25, 31, 37, 43, 49, 56, 62, 68, 74, 80, 86, 92,
                97, 103, 109, 115, 120, 126, 131, 136, 142, 147, 152, 157, 162, 167, 171, 176,
                181, 185, 189, 193, 197, 201, 205, 209, 212, 216, 219, 222, 225, 228, 231, 234,
                236, 238, 241, 243, 244, 246, 248, 249, 251, 252, 253, 254, 254, 255, 255, 255
        };

        private final LevelManager levelManager;
        private final RingRenderer renderer;
        private final TouchResponseTable touchResponseTable;
        private final AudioManager audioManager = AudioManager.getInstance();
        private final Camera camera = Camera.getInstance();
        private final LostRing[] ringPool = new LostRing[MAX_LOST_RINGS];
        private int activeRingCount = 0;
        private int nextId;

        private LostRingPool(LevelManager levelManager, RingRenderer renderer, TouchResponseTable touchResponseTable) {
            this.levelManager = levelManager;
            this.renderer = renderer;
            this.touchResponseTable = touchResponseTable;
            for (int i = 0; i < MAX_LOST_RINGS; i++) {
                ringPool[i] = new LostRing();
            }
        }

        private void reset() {
            activeRingCount = 0;
        }

        private void spawnLostRings(AbstractPlayableSprite player, int ringCount, int frameCounter) {
            if (player == null || renderer == null) {
                return;
            }
            if (ringCount <= 0) {
                return;
            }
            int count = Math.min(ringCount, MAX_LOST_RINGS);
            int angle = 0x288;
            int xVel = 0;
            int yVel = 0;

            activeRingCount = 0;

            for (int i = 0; i < count; i++) {
                if (angle >= 0) {
                    int sin = calcSine(angle & 0xFF);
                    int cos = calcCosine(angle & 0xFF);
                    int scale = (angle >> 8) & 0xFF;
                    xVel = sin << scale;
                    yVel = cos << scale;
                    angle = (angle + 0x10) & 0xFFFF;
                    if ((angle & 0x100) != 0) {
                        angle = (angle - 0x80) & 0xFFFF;
                        if ((angle & 0x100) != 0) {
                            angle = 0x288;
                        }
                    }
                }

                ringPool[activeRingCount].reset(nextId++, player.getCentreX(), player.getCentreY(),
                        xVel, yVel, LIFETIME_FRAMES, frameCounter);
                activeRingCount++;
                xVel = -xVel;
                angle = -angle;
            }

            player.setRingCount(0);
            audioManager.playSfx(GameSound.RING_SPILL);
        }

        private void update(AbstractPlayableSprite player, int frameCounter) {
            if (renderer == null || activeRingCount == 0) {
                return;
            }

            PatternSpriteRenderer.FrameBounds bounds = renderer.getSpinBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }

            int playerX = 0;
            int playerY = 0;
            int playerHeight = 0;
            if (player != null) {
                int baseYRadius = Math.max(1, player.getYRadius() - 3);
                playerX = player.getCentreX() - 8;
                playerY = player.getCentreY() - 8 - baseYRadius;
                playerHeight = baseYRadius * 2;
                if (player.getCrouching()) {
                    playerY += 12;
                    playerHeight = 20;
                }
            }

            int cameraBottom = camera.getMaxY() + 224;

            for (int i = 0; i < activeRingCount; i++) {
                LostRing ring = ringPool[i];
                if (!ring.isActive()) {
                    continue;
                }

                if (!ring.isCollected()) {
                    ring.addXSubpixel(ring.getXVel());
                    ring.addYSubpixel(ring.getYVel());
                    ring.addYVel(GRAVITY);

                    if (ring.getYVel() >= 0 && ((frameCounter + ring.getId()) & 7) == 0) {
                        int dist = ringCheckFloorDist(ring.getX(), ring.getY());
                        if (dist < 0) {
                            ring.addYSubpixel(dist << 8);
                            int yVel = ring.getYVel();
                            yVel -= (yVel >> 2);
                            ring.setYVel(-yVel);
                        }
                    }

                    if (player != null && ring.canBeCollected(frameCounter)
                            && !player.getDead()
                            && player.getInvulnerableFrames() < 90
                            && ringOverlapsPlayer(playerX, playerY, playerHeight, ring)) {
                        ring.markCollected(frameCounter);
                        player.addRings(1);
                        audioManager.playSfx(GameSound.RING);
                    }
                }

                ring.decLifetime();
                if (ring.getLifetime() <= 0 || ring.getY() > cameraBottom) {
                    ring.deactivate();
                }
            }
        }

        private void draw(int frameCounter) {
            if (renderer == null || activeRingCount == 0) {
                return;
            }

            int spinFrameIndex = renderer.getSpinFrameIndex(frameCounter);
            for (int i = 0; i < activeRingCount; i++) {
                LostRing ring = ringPool[i];
                if (!ring.isActive()) {
                    continue;
                }

                if (!ring.isCollected()) {
                    if (ring.getLifetime() < 64 && (ring.getLifetime() & 1) != 0) {
                        continue;
                    }
                    renderer.drawFrameIndex(spinFrameIndex, ring.getX(), ring.getY());
                    continue;
                }

                int sparkleStartFrame = ring.getSparkleStartFrame();
                if (sparkleStartFrame < 0 || renderer.getSparkleFrameCount() <= 0) {
                    continue;
                }

                int elapsed = frameCounter - sparkleStartFrame;
                if (elapsed < 0) {
                    elapsed = 0;
                }
                int sparkleFrameOffset = elapsed / renderer.getFrameDelay();
                if (sparkleFrameOffset >= renderer.getSparkleFrameCount()) {
                    ring.deactivate();
                    continue;
                }
                int sparkleFrameIndex = renderer.getSparkleStartIndex() + sparkleFrameOffset;
                renderer.drawFrameIndex(sparkleFrameIndex, ring.getX(), ring.getY());
            }
        }

        private boolean ringOverlapsPlayer(int playerX, int playerY, int playerHeight, LostRing ring) {
            int width = touchResponseTable != null ? touchResponseTable.getWidthRadius(RING_TOUCH_SIZE_INDEX) : 6;
            int height = touchResponseTable != null ? touchResponseTable.getHeightRadius(RING_TOUCH_SIZE_INDEX) : 6;
            int dx = ring.getX() - width - playerX;
            if (dx < 0) {
                int sum = (dx & 0xFFFF) + ((width * 2) & 0xFFFF);
                if (sum <= 0xFFFF) {
                    return false;
                }
            } else if (dx > 0x10) {
                return false;
            }

            int dy = ring.getY() - height - playerY;
            if (dy < 0) {
                int sum = (dy & 0xFFFF) + ((height * 2) & 0xFFFF);
                if (sum <= 0xFFFF) {
                    return false;
                }
            } else if (dy > playerHeight) {
                return false;
            }

            return true;
        }

        private int ringCheckFloorDist(int x, int y) {
            if (levelManager == null) {
                return 0;
            }
            ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, x, y);
            SolidTile tile = getSolidTile(chunkDesc, SOLIDITY_TOP);
            SensorMetric metric = getMetric(tile, chunkDesc, x, y);
            if (metric.metric == 0) {
                return 0;
            }
            if (metric.metric == 16) {
                int prevY = y - 16;
                ChunkDesc prevDesc = levelManager.getChunkDescAt((byte) 0, x, prevY);
                SolidTile prevTile = getSolidTile(prevDesc, SOLIDITY_TOP);
                SensorMetric prevMetric = getMetric(prevTile, prevDesc, x, prevY);
                if (prevMetric.metric > 0 && prevMetric.metric < 16) {
                    return calculateDistance(prevMetric.metric, x, y, prevY);
                }
                return calculateDistance(metric.metric, x, y, y);
            }
            return calculateDistance(metric.metric, x, y, y);
        }

        private SolidTile getSolidTile(ChunkDesc chunkDesc, int solidityBitIndex) {
            if (chunkDesc == null || !chunkDesc.isSolidityBitSet(solidityBitIndex)) {
                return null;
            }
            return levelManager.getSolidTileForChunkDesc(chunkDesc, solidityBitIndex);
        }

        private SensorMetric getMetric(SolidTile tile, ChunkDesc desc, int x, int y) {
            if (tile == null) {
                return new SensorMetric((byte) 0);
            }
            int index = x & 0x0F;
            if (desc != null && desc.getHFlip()) {
                index = 15 - index;
            }
            byte metric = tile.getHeightAt((byte) index);
            if (metric != 0 && metric != 16) {
                boolean invert = (desc != null && desc.getVFlip());
                if (invert) {
                    metric = (byte) (16 - metric);
                }
            }
            return new SensorMetric(metric);
        }

        private int calculateDistance(byte metric, int x, int y, int checkY) {
            int tileY = checkY & ~0x0F;
            return (tileY + 16 - metric) - y;
        }

        private int calcSine(int angle) {
            return SINE_TABLE[angle & 0xFF];
        }

        private int calcCosine(int angle) {
            return SINE_TABLE[(angle & 0xFF) + 0x40];
        }

        private record SensorMetric(byte metric) {
        }
    }
}
