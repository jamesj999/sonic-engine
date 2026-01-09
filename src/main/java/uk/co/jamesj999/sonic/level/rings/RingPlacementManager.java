package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal runtime manager that exposes rings within a camera window.
 */
public class RingPlacementManager extends AbstractPlacementManager<RingSpawn> {
    private static final int LOAD_AHEAD = 0x280;
    private static final int UNLOAD_BEHIND = 0x300;
    private static final int NO_SPARKLE = -1;

    private final BitSet collected = new BitSet();
    private final int[] sparkleStartFrames;

    public RingPlacementManager(List<RingSpawn> spawns) {
        super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
        this.sparkleStartFrames = new int[this.spawns.size()];
        Arrays.fill(this.sparkleStartFrames, NO_SPARKLE);
    }

    public void reset(int cameraX) {
        active.clear();
        collected.clear();
        Arrays.fill(sparkleStartFrames, NO_SPARKLE);
        update(cameraX);
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
        int windowStart = getWindowStart(cameraX);
        int windowEnd = getWindowEnd(cameraX);

        int start = lowerBound(windowStart);
        int end = upperBound(windowEnd);

        Set<RingSpawn> window = new LinkedHashSet<>();
        for (int i = start; i < end; i++) {
            window.add(spawns.get(i));
        }

        active.retainAll(window);
        active.addAll(window);
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
