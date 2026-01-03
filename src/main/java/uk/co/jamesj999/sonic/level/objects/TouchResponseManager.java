package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class TouchResponseManager {
    private final ObjectManager objectManager;
    private final TouchResponseTable table;
    private final Set<ObjectInstance> overlapping = Collections.newSetFromMap(new IdentityHashMap<>());
    private final TouchResponseDebugState debugState = new TouchResponseDebugState();
    private int frameCounter;

    public TouchResponseManager(ObjectManager objectManager, TouchResponseTable table) {
        this.objectManager = objectManager;
        this.table = table;
    }

    public void reset() {
        overlapping.clear();
        frameCounter = 0;
    }

    public void update(AbstractPlayableSprite player) {
        frameCounter++;
        if (player == null || objectManager == null) {
            overlapping.clear();
            debugState.clear();
            return;
        }

        int playerX = player.getCentreX() - 8;
        int baseYRadius = Math.max(1, player.getYRadius() - 3);
        int playerY = player.getCentreY() - 8 - baseYRadius;
        int playerHeight = baseYRadius * 2;
        boolean crouching = player.getCrouching();
        if (crouching) {
            playerY += 12;
            playerHeight = 20;
        }
        debugState.setPlayer(playerX, playerY, playerHeight, baseYRadius, crouching);
        debugState.clear();

        Set<ObjectInstance> current = Collections.newSetFromMap(new IdentityHashMap<>());
        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        for (ObjectInstance instance : activeObjects) {
            if (!(instance instanceof TouchResponseProvider provider)) {
                continue;
            }
            int flags = provider.getCollisionFlags();
            int sizeIndex = flags & 0x3F;
            int width = table.getWidthRadius(sizeIndex);
            int height = table.getHeightRadius(sizeIndex);
            TouchCategory category = decodeCategory(flags);

            boolean overlap = isOverlapping(playerX, playerY, playerHeight, instance.getSpawn(), width, height);
            debugState.addHit(new TouchResponseDebugHit(instance.getSpawn(), flags, sizeIndex, width, height, category, overlap));
            if (!overlap) {
                continue;
            }

            current.add(instance);
            if (!overlapping.contains(instance) && instance instanceof TouchResponseListener listener) {
                TouchResponseResult result = new TouchResponseResult(sizeIndex, width, height, category);
                listener.onTouchResponse(player, result, frameCounter);
            }
        }

        overlapping.clear();
        overlapping.addAll(current);
    }

    private boolean isOverlapping(int playerX, int playerY, int playerHeight,
                                  ObjectSpawn spawn, int objectWidth, int objectHeight) {
        int dx = spawn.x() - objectWidth - playerX;
        if (dx < 0) {
            int sum = (dx & 0xFFFF) + ((objectWidth * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dx > 0x10) {
            return false;
        }

        int dy = spawn.y() - objectHeight - playerY;
        if (dy < 0) {
            int sum = (dy & 0xFFFF) + ((objectHeight * 2) & 0xFFFF);
            if (sum <= 0xFFFF) {
                return false;
            }
        } else if (dy > playerHeight) {
            return false;
        }

        return true;
    }

    private TouchCategory decodeCategory(int flags) {
        int categoryBits = flags & 0xC0;
        return switch (categoryBits) {
            case 0x00 -> TouchCategory.ENEMY;
            case 0x40 -> TouchCategory.SPECIAL;
            case 0x80 -> TouchCategory.HURT;
            default -> TouchCategory.BOSS;
        };
    }

    public TouchResponseDebugState getDebugState() {
        return debugState;
    }
}
