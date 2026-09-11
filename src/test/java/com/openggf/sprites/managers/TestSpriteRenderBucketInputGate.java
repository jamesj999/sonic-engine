package com.openggf.sprites.managers;

import com.openggf.sprites.Sprite;
import com.openggf.physics.Direction;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestSpriteRenderBucketInputGate {

    private static volatile boolean allocationSink;

    @Test
    void stableRenderBucketInputGateHasZeroAllocationSlopeAfterInlineInstrumentation() {
        assertNotNull(mock(SpriteManager.class),
                "fixture must inline-instrument SpriteManager before measuring the helper");
        Map<String, Sprite> liveSprites = new HashMap<>();
        Sprite[] retainedSprites = new Sprite[8];
        int[] retainedKeys = new int[8];
        Sprite retained = new StableNonPlayableSprite("retained");
        liveSprites.put(retained.getCode(), retained);
        retainedSprites[0] = retained;
        retainedKeys[0] = -1;
        for (int warm = 0; warm < 20_000; warm++) {
            allocationSink = SpriteRenderBucketInputGate.inputsChanged(
                    liveSprites, retainedSprites, retainedKeys, 1);
        }
        assertFalse(allocationSink, "fixture must exercise a stable retained entry");

        com.sun.management.ThreadMXBean bean = allocationBeanOrSkip();
        long threadId = Thread.currentThread().threadId();
        long allocation600k = measure(
                liveSprites, retainedSprites, retainedKeys, bean, threadId, 600_000);
        long allocation1200k = measure(
                liveSprites, retainedSprites, retainedKeys, bean, threadId, 1_200_000);

        assertTrue(allocation1200k <= allocation600k + 1_024,
                "doubling stable gate checks must not add allocation; 600k=" + allocation600k
                        + " 1200k=" + allocation1200k);
    }

    @Test
    void gateDetectsSizeChangesBeforeRetainedKeyScan() {
        Map<String, Sprite> liveSprites = new HashMap<>();
        Sprite[] retainedSprites = new Sprite[1];
        int[] retainedKeys = new int[1];

        assertFalse(SpriteRenderBucketInputGate.inputsChanged(
                liveSprites, retainedSprites, retainedKeys, 0));
        liveSprites.put("new", mock(Sprite.class));
        assertTrue(SpriteRenderBucketInputGate.inputsChanged(
                liveSprites, retainedSprites, retainedKeys, 0));
    }

    private static long measure(Map<String, Sprite> liveSprites,
                                Sprite[] retainedSprites,
                                int[] retainedKeys,
                                com.sun.management.ThreadMXBean bean,
                                long threadId,
                                int iterations) {
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < iterations; i++) {
            allocationSink = SpriteRenderBucketInputGate.inputsChanged(
                    liveSprites, retainedSprites, retainedKeys, 1);
        }
        return bean.getThreadAllocatedBytes(threadId) - before;
    }

    private static com.sun.management.ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof com.sun.management.ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        Assumptions.assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting could not be enabled");
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "thread allocation accounting returned an unavailable value");
        return bean;
    }

    private static final class StableNonPlayableSprite implements Sprite {
        private String code;

        private StableNonPlayableSprite(String code) { this.code = code; }
        @Override public String getCode() { return code; }
        @Override public void setCode(String code) { this.code = code; }
        @Override public void draw() { }
        @Override public short getCentreX() { return 0; }
        @Override public short getCentreY() { return 0; }
        @Override public void setCentreX(short x) { }
        @Override public void setCentreY(short y) { }
        @Override public short getX() { return 0; }
        @Override public void setX(short x) { }
        @Override public short getY() { return 0; }
        @Override public void setY(short y) { }
        @Override public int getHeight() { return 0; }
        @Override public void setHeight(int height) { }
        @Override public int getWidth() { return 0; }
        @Override public void setWidth(int width) { }
        @Override public short getBottomY() { return 0; }
        @Override public short getTopY() { return 0; }
        @Override public short getLeftX() { return 0; }
        @Override public short getRightX() { return 0; }
        @Override public void move(short xSpeed, short ySpeed) { }
        @Override public Direction getDirection() { return Direction.RIGHT; }
        @Override public void setDirection(Direction direction) { }
        @Override public void setLayer(byte layer) { }
        @Override public byte getLayer() { return 0; }
    }
}
