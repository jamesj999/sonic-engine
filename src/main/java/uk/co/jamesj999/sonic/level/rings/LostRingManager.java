package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LostRingManager {
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
    private final RingRenderManager renderManager;
    private final TouchResponseTable touchResponseTable;
    private final int sparkleStartIndex;
    private final int sparkleFrameCount;
    private final int frameDelay;
    private final List<LostRing> rings = new ArrayList<>();
    private int nextId;

    public LostRingManager(LevelManager levelManager, RingRenderManager renderManager, TouchResponseTable touchResponseTable) {
        this.levelManager = levelManager;
        this.renderManager = renderManager;
        this.touchResponseTable = touchResponseTable;
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

    public void spawnLostRings(AbstractPlayableSprite player, int ringCount) {
        if (player == null || renderManager == null) {
            return;
        }
        if (ringCount <= 0) {
            return;
        }
        int count = Math.min(ringCount, MAX_LOST_RINGS);
        int angle = 0x288;
        int xVel = 0;
        int yVel = 0;

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

            rings.add(new LostRing(nextId++, player.getCentreX(), player.getCentreY(), xVel, yVel, LIFETIME_FRAMES));
            xVel = -xVel;
            angle = -angle;
        }

        player.setRingCount(0);
        AudioManager.getInstance().playSfx(GameSound.RING_SPILL);
    }

    public void update(AbstractPlayableSprite player, int frameCounter) {
        if (renderManager == null || rings.isEmpty()) {
            return;
        }

        PatternSpriteRenderer.FrameBounds bounds = renderManager.getSpinBounds();
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

        Iterator<LostRing> iterator = rings.iterator();
        while (iterator.hasNext()) {
            LostRing ring = iterator.next();

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

                if (player != null && ringOverlapsPlayer(playerX, playerY, playerHeight, ring)) {
                    ring.markCollected(frameCounter);
                    player.addRings(1);
                    AudioManager.getInstance().playSfx(GameSound.RING);
                }
            }

            ring.decLifetime();
            if (ring.getLifetime() <= 0) {
                iterator.remove();
                continue;
            }

            int cameraBottom = Camera.getInstance().getMaxY() + 224;
            if (ring.getY() > cameraBottom) {
                iterator.remove();
            }
        }
    }

    public void draw(int frameCounter) {
        if (renderManager == null || rings.isEmpty()) {
            return;
        }

        int spinFrameIndex = renderManager.getSpinFrameIndex(frameCounter);
        Iterator<LostRing> iterator = rings.iterator();
        while (iterator.hasNext()) {
            LostRing ring = iterator.next();
            if (!ring.isCollected()) {
                renderManager.drawFrameIndex(spinFrameIndex, ring.getX(), ring.getY());
                continue;
            }

            int sparkleStart = ring.getSparkleStartFrame();
            if (sparkleStart < 0 || sparkleFrameCount <= 0) {
                iterator.remove();
                continue;
            }
            int elapsed = frameCounter - sparkleStart;
            if (elapsed < 0) {
                elapsed = 0;
            }
            int sparkleFrameOffset = elapsed / frameDelay;
            if (sparkleFrameOffset >= sparkleFrameCount) {
                iterator.remove();
                continue;
            }
            int sparkleFrameIndex = sparkleStartIndex + sparkleFrameOffset;
            renderManager.drawFrameIndex(sparkleFrameIndex, ring.getX(), ring.getY());
        }
    }

    private boolean ringOverlapsPlayer(int playerX, int playerY, int playerHeight, LostRing ring) {
        if (playerHeight <= 0) {
            return false;
        }
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
