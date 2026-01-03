package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;

public class SolidObjectManager {
    private final ObjectManager objectManager;
    private int frameCounter;

    public SolidObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void reset() {
        frameCounter = 0;
    }

    public void update(AbstractPlayableSprite player) {
        frameCounter++;
        if (player == null || objectManager == null) {
            return;
        }
        player.setPushing(false);

        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        for (ObjectInstance instance : activeObjects) {
            if (!(instance instanceof SolidObjectProvider provider)) {
                continue;
            }
            SolidObjectParams params = provider.getSolidParams();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
            SolidContact contact = resolveContact(player,
                    instance.getSpawn(), params.halfWidth(), halfHeight);
            if (contact == null) {
                continue;
            }
            if (contact.pushing()) {
                player.setPushing(true);
            }
            if (instance instanceof SolidObjectListener listener) {
                listener.onSolidContact(player, contact, frameCounter);
            }
        }
    }

    private SolidContact resolveContact(AbstractPlayableSprite player,
                                        ObjectSpawn spawn, int halfWidth, int halfHeight) {
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();

        int relX = playerCenterX - spawn.x() + halfWidth;
        if (relX < 0 || relX > halfWidth * 2) {
            return null;
        }

        int playerYRadius = player.getYRadius();
        int maxTop = halfHeight + playerYRadius;
        int relY = playerCenterY - spawn.y() + 4 + maxTop;
        if (relY < 0 || relY >= maxTop * 2) {
            return null;
        }

        int distX;
        int absDistX;
        if (relX >= halfWidth) {
            distX = relX - (halfWidth * 2);
            absDistX = -distX;
        } else {
            distX = relX;
            absDistX = distX;
        }

        int distY;
        int absDistY;
        if (relY <= maxTop) {
            distY = relY;
            absDistY = distY;
        } else {
            distY = relY - 4 - (maxTop * 2);
            absDistY = Math.abs(distY);
        }

        if (absDistX <= absDistY) {
            boolean leftSide = distX > 0;
            boolean nearVerticalEdge = absDistY <= 4;
            boolean pushing = !player.getAir() && !nearVerticalEdge;
            boolean movingInto = leftSide ? player.getXSpeed() > 0 : player.getXSpeed() < 0;
            if (movingInto) {
                player.setXSpeed((short) 0);
                player.setGSpeed((short) 0);
            }
            player.setCentreX((short) (playerCenterX - distX));
            return new SolidContact(false, true, false, false, pushing);
        }

        if (distY >= 0) {
            int landingThreshold = 0x10;
            if (distY >= landingThreshold) {
                return null;
            }
            int newCenterY = playerCenterY - distY - 1;
            int newY = newCenterY - (player.getHeight() / 2);
            player.setY((short) newY);
            if (player.getYSpeed() > 0) {
                player.setYSpeed((short) 0);
            }
            player.setAir(false);
            return new SolidContact(true, false, false, true, false);
        }

        int newCenterY = playerCenterY - distY;
        int newY = newCenterY - (player.getHeight() / 2);
        player.setY((short) newY);
        if (player.getYSpeed() < 0) {
            player.setYSpeed((short) 0);
        }
        return new SolidContact(false, false, true, false, false);
    }
}
