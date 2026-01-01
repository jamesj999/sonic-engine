package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles Sonic 2 plane switcher (Obj03) logic against active object spawns.
 */
public class PlaneSwitcherManager {
    public static final int OBJECT_ID = 0x03;

    private static final int[] HALF_SPANS = new int[]{0x20, 0x40, 0x80, 0x100};

    private final ObjectPlacementManager placementManager;
    private final Map<ObjectSpawn, PlaneSwitcherState> states = new HashMap<>();

    public PlaneSwitcherManager(ObjectPlacementManager placementManager) {
        this.placementManager = placementManager;
    }

    public void reset() {
        states.clear();
    }

    public void update(AbstractPlayableSprite player) {
        if (placementManager == null || player == null) {
            return;
        }

        Collection<ObjectSpawn> active = placementManager.getActiveSpawns();
        if (active.isEmpty()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        for (ObjectSpawn spawn : active) {
            if (spawn.objectId() != OBJECT_ID) {
                continue;
            }

            int subtype = spawn.subtype();
            PlaneSwitcherState state = states.computeIfAbsent(spawn,
                    s -> new PlaneSwitcherState(decodeHalfSpan(s.subtype())));

            boolean horizontal = isHorizontal(subtype);
            if (!state.seeded) {
                state.sideState = (byte) (horizontal
                        ? (playerY >= spawn.y() ? 1 : 0)
                        : (playerX >= spawn.x() ? 1 : 0));
                state.seeded = true;
            }

            if (onlySwitchWhenGrounded(subtype) && player.getAir()) {
                continue;
            }

            int half = state.halfSpanPixels;
            boolean inSpan = horizontal
                    ? (playerX >= spawn.x() - half && playerX < spawn.x() + half)
                    : (playerY >= spawn.y() - half && playerY < spawn.y() + half);

            if (!inSpan) {
                continue;
            }

            int sideNow = horizontal
                    ? (playerY >= spawn.y() ? 1 : 0)
                    : (playerX >= spawn.x() ? 1 : 0);

            if (sideNow == state.sideState) {
                continue;
            }
            state.sideState = (byte) sideNow;

            boolean skipCollisionChange = (spawn.renderFlags() & 0x1) != 0;
            if (!skipCollisionChange) {
                int path = decodePath(subtype, sideNow);
                player.setLayer((byte) path);
            }

            boolean highPriority = decodePriority(subtype, sideNow);
            player.setHighPriority(highPriority);
        }

        states.keySet().removeIf(spawn -> spawn.objectId() == OBJECT_ID && !active.contains(spawn));
    }

    public static int decodeHalfSpan(int subtype) {
        return HALF_SPANS[subtype & 0x3];
    }

    public static boolean isHorizontal(int subtype) {
        return (subtype & 0x04) != 0;
    }

    public static int decodePath(int subtype, int side) {
        int bit = (side == 1) ? 0x08 : 0x10;
        return (subtype & bit) != 0 ? 1 : 0;
    }

    public static boolean decodePriority(int subtype, int side) {
        int bit = (side == 1) ? 0x20 : 0x40;
        return (subtype & bit) != 0;
    }

    public static boolean onlySwitchWhenGrounded(int subtype) {
        return (subtype & 0x80) != 0;
    }

    public static char formatLayer(int path) {
        return path == 0 ? 'A' : 'B';
    }

    public static char formatPriority(boolean highPriority) {
        return highPriority ? 'H' : 'L';
    }

    private static final class PlaneSwitcherState {
        private final int halfSpanPixels;
        private byte sideState = 0;
        private boolean seeded = false;

        private PlaneSwitcherState(int halfSpanPixels) {
            this.halfSpanPixels = halfSpanPixels;
        }
    }
}
