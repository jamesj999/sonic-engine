package uk.co.jamesj999.sonic.level.spawn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared windowing support for spawn placement managers.
 */
public abstract class AbstractPlacementManager<T extends SpawnPoint> {
    protected final List<T> spawns;
    protected final Set<T> active = new LinkedHashSet<>();
    protected final Map<T, Integer> spawnIndexMap = new IdentityHashMap<>();
    private final int loadAhead;
    private final int unloadBehind;

    protected AbstractPlacementManager(List<T> spawns, int loadAhead, int unloadBehind) {
        ArrayList<T> sorted = new ArrayList<>(spawns);
        sorted.sort(Comparator.comparingInt(SpawnPoint::x));
        this.spawns = Collections.unmodifiableList(sorted);
        this.loadAhead = loadAhead;
        this.unloadBehind = unloadBehind;
        for (int i = 0; i < this.spawns.size(); i++) {
            spawnIndexMap.put(this.spawns.get(i), i);
        }
    }

    public List<T> getAllSpawns() {
        return spawns;
    }

    public Collection<T> getActiveSpawns() {
        return Collections.unmodifiableCollection(active);
    }

    public int getSpawnIndex(T spawn) {
        Integer index = spawnIndexMap.get(spawn);
        return index != null ? index : -1;
    }

    protected int getLoadAhead() {
        return loadAhead;
    }

    protected int getUnloadBehind() {
        return unloadBehind;
    }

    protected int getWindowStart(int cameraX) {
        return Math.max(0, cameraX - unloadBehind);
    }

    protected int getWindowEnd(int cameraX) {
        return cameraX + loadAhead;
    }

    protected int lowerBound(int value) {
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

    protected int upperBound(int value) {
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
