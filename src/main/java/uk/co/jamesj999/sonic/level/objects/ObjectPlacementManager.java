package uk.co.jamesj999.sonic.level.objects;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal runtime manager that spawns/despawns objects in a camera window.
 * Uses the original Sonic 2 window distances as a starting point.
 */
public class ObjectPlacementManager {
    private static final int LOAD_AHEAD = 0x280;   // see addi.w #$280,d6 in ObjectsManager_GoingForward (s2.asm)
    private static final int UNLOAD_BEHIND = 0x300; // see addi.w #$300,d6 when trimming right-side objects

    private final List<ObjectSpawn> spawns;
    private final Map<ObjectSpawn, Integer> spawnIndexMap = new IdentityHashMap<>();
    private final BitSet remembered = new BitSet();
    private final Set<ObjectSpawn> active = new LinkedHashSet<>();
    private int cursorIndex = 0;
    private int lastCameraX = Integer.MIN_VALUE;

    public ObjectPlacementManager(List<ObjectSpawn> spawns) {
        ArrayList<ObjectSpawn> sorted = new ArrayList<>(spawns);
        sorted.sort(Comparator.comparingInt(ObjectSpawn::x));
        this.spawns = Collections.unmodifiableList(sorted);
        for (int i = 0; i < this.spawns.size(); i++) {
            spawnIndexMap.put(this.spawns.get(i), i);
        }
    }

    public List<ObjectSpawn> getAllSpawns() {
        return spawns;
    }

    public Collection<ObjectSpawn> getActiveSpawns() {
        return Collections.unmodifiableCollection(active);
    }

    public void reset(int cameraX) {
        active.clear();
        remembered.clear();
        cursorIndex = 0;
        lastCameraX = cameraX;
        refreshWindow(cameraX);
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
        if (delta < 0 || delta > (LOAD_AHEAD + UNLOAD_BEHIND)) {
            refreshWindow(cameraX);
        } else {
            spawnForward(cameraX);
            trimActive(cameraX);
        }

        lastCameraX = cameraX;
    }

    public void markRemembered(ObjectSpawn spawn) {
        if (!spawn.respawnTracked()) {
            return;
        }
        Integer index = spawnIndexMap.get(spawn);
        if (index == null) {
            return;
        }
        remembered.set(index);
        active.remove(spawn);
    }

    public boolean isRemembered(ObjectSpawn spawn) {
        Integer index = spawnIndexMap.get(spawn);
        if (index == null) {
            return false;
        }
        return remembered.get(index);
    }

    public void clearRemembered() {
        remembered.clear();
    }

    private void spawnForward(int cameraX) {
        int spawnLimit = cameraX + LOAD_AHEAD;
        while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() <= spawnLimit) {
            trySpawn(cursorIndex);
            cursorIndex++;
        }
    }

    private void trimActive(int cameraX) {
        int windowStart = Math.max(0, cameraX - UNLOAD_BEHIND);
        int windowEnd = cameraX + LOAD_AHEAD;
        Iterator<ObjectSpawn> iterator = active.iterator();
        while (iterator.hasNext()) {
            ObjectSpawn spawn = iterator.next();
            if (spawn.x() < windowStart || spawn.x() > windowEnd) {
                iterator.remove();
            }
        }
    }

    private void refreshWindow(int cameraX) {
        int windowStart = Math.max(0, cameraX - UNLOAD_BEHIND);
        int windowEnd = cameraX + LOAD_AHEAD;
        int start = lowerBound(windowStart);
        int end = upperBound(windowEnd);
        cursorIndex = end;
        active.clear();
        for (int i = start; i < end; i++) {
            trySpawn(i);
        }
    }

    private void trySpawn(int index) {
        ObjectSpawn spawn = spawns.get(index);
        if (spawn.respawnTracked() && remembered.get(index)) {
            return;
        }
        active.add(spawn);
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
