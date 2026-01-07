package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles plane switcher logic against active object spawns.
 */
public class PlaneSwitcherManager {
    private static final int[] HALF_SPANS = new int[]{0x20, 0x40, 0x80, 0x100};
    private static final int MASK_SIZE = 0x03;
    private static final int MASK_HORIZONTAL = 0x04;
    private static final int MASK_PATH_SIDE1 = 0x08;
    private static final int MASK_PATH_SIDE0 = 0x10;
    private static final int MASK_PRIORITY_SIDE1 = 0x20;
    private static final int MASK_PRIORITY_SIDE0 = 0x40;
    private static final int MASK_GROUNDED_ONLY = 0x80;

    private final ObjectPlacementManager placementManager;
    private final int objectId;
    private final PlaneSwitcherConfig config;
    private final Map<ObjectSpawn, PlaneSwitcherState> states = new HashMap<>();

    public PlaneSwitcherManager(ObjectPlacementManager placementManager, int objectId, PlaneSwitcherConfig config) {
        this.placementManager = placementManager;
        this.objectId = objectId & 0xFF;
        this.config = config;
    }

    public void update(AbstractPlayableSprite player) {
        if (placementManager == null || player == null || config == null) {
            return;
        }
        Collection<ObjectSpawn> active = placementManager.getActiveSpawns();
        if (active.isEmpty()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        for (ObjectSpawn spawn : active) {
            if (spawn.objectId() != objectId) {
                continue;
            }

            int subtype = spawn.subtype();
            PlaneSwitcherState state = states.computeIfAbsent(spawn,
                    key -> new PlaneSwitcherState(decodeHalfSpan(subtype)));

            boolean horizontal = isHorizontal(subtype);
            int sideNow = horizontal
                    ? (playerY >= spawn.y() ? 1 : 0)
                    : (playerX >= spawn.x() ? 1 : 0);
            if (!state.seeded) {
                state.sideState = (byte) sideNow;
                state.seeded = true;
            }

            int half = state.halfSpanPixels;
            boolean inSpan = horizontal
                    ? (playerX >= spawn.x() - half && playerX < spawn.x() + half)
                    : (playerY >= spawn.y() - half && playerY < spawn.y() + half);
            boolean groundedGate = onlySwitchWhenGrounded(subtype) && player.getAir();

            if (inSpan && !groundedGate && sideNow != state.sideState) {
                boolean skipCollisionChange = (spawn.renderFlags() & 0x1) != 0;
                if (!skipCollisionChange) {
                    int path = decodePath(subtype, sideNow);
                    player.setLayer((byte) path);
                    if (path == 0) {
                        player.setTopSolidBit(config.getPath0TopSolidBit());
                        player.setLrbSolidBit(config.getPath0LrbSolidBit());
                    } else {
                        player.setTopSolidBit(config.getPath1TopSolidBit());
                        player.setLrbSolidBit(config.getPath1LrbSolidBit());
                    }
                }
                boolean highPriority = decodePriority(subtype, sideNow);
                player.setHighPriority(highPriority);
            }

            state.sideState = (byte) sideNow;
        }

        states.keySet().removeIf(spawn -> spawn.objectId() == objectId && !active.contains(spawn));
    }

    public int getSideState(ObjectSpawn spawn) {
        PlaneSwitcherState state = states.get(spawn);
        if (state == null || !state.seeded) {
            return -1;
        }
        return state.sideState;
    }

    public static int decodeHalfSpan(int subtype) {
        int index = subtype & MASK_SIZE;
        if (index < 0 || index >= HALF_SPANS.length) {
            index = 0;
        }
        return HALF_SPANS[index];
    }

    public static boolean isHorizontal(int subtype) {
        return (subtype & MASK_HORIZONTAL) != 0;
    }

    public static int decodePath(int subtype, int side) {
        int mask = side == 1 ? MASK_PATH_SIDE1 : MASK_PATH_SIDE0;
        return (subtype & mask) != 0 ? 1 : 0;
    }

    public static boolean decodePriority(int subtype, int side) {
        int mask = side == 1 ? MASK_PRIORITY_SIDE1 : MASK_PRIORITY_SIDE0;
        return (subtype & mask) != 0;
    }

    public static boolean onlySwitchWhenGrounded(int subtype) {
        return (subtype & MASK_GROUNDED_ONLY) != 0;
    }

    public static char formatLayer(byte layer) {
        return layer == 0 ? 'A' : 'B';
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
