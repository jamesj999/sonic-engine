package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal runtime manager that spawns/despawns objects in a camera window.
 * Uses the original Sonic 2 window distances as a starting point.
 */
public class ObjectPlacementManager extends AbstractPlacementManager<ObjectSpawn> {
    private static final int LOAD_AHEAD = 0x280; // see addi.w #$280,d6 in ObjectsManager_GoingForward (s2.asm)
    private static final int UNLOAD_BEHIND = 0x300; // see addi.w #$300,d6 when trimming right-side objects

    private final BitSet remembered = new BitSet();
    private int cursorIndex = 0;
    private int lastCameraX = Integer.MIN_VALUE;

    public ObjectPlacementManager(List<ObjectSpawn> spawns) {
        super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
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
        if (delta < 0 || delta > (getLoadAhead() + getUnloadBehind())) {
            refreshWindow(cameraX);
        } else {
            spawnForward(cameraX);
            trimActive(cameraX);
        }

        lastCameraX = cameraX;
    }

    public void markRemembered(ObjectSpawn spawn) {
        // Always remove from active list when object is destroyed
        // (except Monitors which have special broken-state behavior)
        if (spawn.objectId() != 0x26) {
            active.remove(spawn);
        }

        // Track ALL destroyed objects to prevent respawning during this session
        // (original game only tracks respawnTracked objects, but we need to prevent
        // immediate respawning when camera moves backward and refreshWindow is called)
        int index = getSpawnIndex(spawn);
        if (index < 0) {
            return;
        }
        remembered.set(index);
    }

    public boolean isRemembered(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        return index >= 0 && remembered.get(index);
    }

    public void clearRemembered() {
        remembered.clear();
    }

    private void spawnForward(int cameraX) {
        int spawnLimit = cameraX + getLoadAhead();
        while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() <= spawnLimit) {
            trySpawn(cursorIndex);
            cursorIndex++;
        }
    }

    private void trimActive(int cameraX) {
        int windowStart = getWindowStart(cameraX);
        int windowEnd = getWindowEnd(cameraX);
        Iterator<ObjectSpawn> iterator = active.iterator();
        while (iterator.hasNext()) {
            ObjectSpawn spawn = iterator.next();
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
            trySpawn(i);
        }
    }

    private void trySpawn(int index) {
        ObjectSpawn spawn = spawns.get(index);
        // Skip spawning any object that has been destroyed (remembered) this session
        // Monitors (0x26) are an exception: they spawn in a broken state if remembered
        if (remembered.get(index) && spawn.objectId() != 0x26) {
            return;
        }
        active.add(spawn);
    }
}
