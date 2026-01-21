package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal runtime manager that exposes rings within a camera window.
 */
public class RingPlacementManager extends AbstractPlacementManager<RingSpawn> {
    private static final int LOAD_AHEAD = 0x280;
    private static final int UNLOAD_BEHIND = 0x300;
    private static final int NO_SPARKLE = -1;

    private final BitSet collected = new BitSet();
    private final int[] sparkleStartFrames;
    private int cursorIndex = 0;
    private int lastCameraX = Integer.MIN_VALUE;

    public RingPlacementManager(List<RingSpawn> spawns) {
        super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
        this.sparkleStartFrames = new int[this.spawns.size()];
        Arrays.fill(this.sparkleStartFrames, NO_SPARKLE);
    }

    public void reset(int cameraX) {
        active.clear();
        collected.clear();
        Arrays.fill(sparkleStartFrames, NO_SPARKLE);
        cursorIndex = 0;
        lastCameraX = cameraX;
        refreshWindow(cameraX);
    }

    public boolean isCollected(int index) {
        return index >= 0 && collected.get(index);
    }

    public void markCollected(int index) {
        if (index >= 0) {
            collected.set(index);
        }
    }

    public int getSparkleStartFrame(int index) {
        if (index < 0 || index >= sparkleStartFrames.length) {
            return NO_SPARKLE;
        }
        return sparkleStartFrames[index];
    }

    public void setSparkleStartFrame(int index, int startFrame) {
        if (index < 0 || index >= sparkleStartFrames.length) {
            return;
        }
        sparkleStartFrames[index] = startFrame;
    }

    public void clearSparkle(int index) {
        if (index < 0 || index >= sparkleStartFrames.length) {
            return;
        }
        sparkleStartFrames[index] = NO_SPARKLE;
    }

    public void update(int cameraX) {
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

    public int getTotalRingCount() {
        return spawns.size();
    }

    public int getCollectedCount() {
        return collected.cardinality();
    }

    public boolean areAllCollected() {
        return !spawns.isEmpty() && collected.cardinality() >= spawns.size();
    }
}
