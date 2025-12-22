package uk.co.jamesj999.sonic.level.rings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal runtime manager that exposes rings within a camera window.
 */
public class RingPlacementManager {
    private static final int LOAD_AHEAD = 0x280;
    private static final int UNLOAD_BEHIND = 0x300;
    private static final int NO_SPARKLE = -1;

    private final List<RingSpawn> spawns;
    private final Set<RingSpawn> active = new LinkedHashSet<>();
    private final Map<RingSpawn, Integer> spawnIndexMap = new IdentityHashMap<>();
    private final BitSet collected = new BitSet();
    private final int[] sparkleStartFrames;

    public RingPlacementManager(List<RingSpawn> spawns) {
        ArrayList<RingSpawn> sorted = new ArrayList<>(spawns);
        sorted.sort(Comparator.comparingInt(RingSpawn::x));
        this.spawns = Collections.unmodifiableList(sorted);
        this.sparkleStartFrames = new int[this.spawns.size()];
        Arrays.fill(this.sparkleStartFrames, NO_SPARKLE);
        for (int i = 0; i < this.spawns.size(); i++) {
            spawnIndexMap.put(this.spawns.get(i), i);
        }
    }

    public List<RingSpawn> getAllSpawns() {
        return spawns;
    }

    public Collection<RingSpawn> getActiveSpawns() {
        return Collections.unmodifiableCollection(active);
    }

    public void reset(int cameraX) {
        active.clear();
        collected.clear();
        Arrays.fill(sparkleStartFrames, NO_SPARKLE);
        update(cameraX);
    }

    public int getSpawnIndex(RingSpawn spawn) {
        Integer index = spawnIndexMap.get(spawn);
        return index != null ? index : -1;
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
        int windowStart = Math.max(0, cameraX - UNLOAD_BEHIND);
        int windowEnd = cameraX + LOAD_AHEAD;

        int start = lowerBound(windowStart);
        int end = upperBound(windowEnd);

        Set<RingSpawn> window = new LinkedHashSet<>();
        for (int i = start; i < end; i++) {
            window.add(spawns.get(i));
        }

        active.retainAll(window);
        active.addAll(window);
    }

    private int lowerBound(int value) {
        int low = 0;
        int high = spawns.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (spawns.get(mid).x() < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int upperBound(int value) {
        int low = 0;
        int high = spawns.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (spawns.get(mid).x() <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
