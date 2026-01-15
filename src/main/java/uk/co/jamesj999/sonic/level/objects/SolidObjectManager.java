package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;

public class SolidObjectManager {
    private final ObjectManager objectManager;
    private int frameCounter;
    private ObjectInstance ridingObject;
    private int ridingX;
    private int ridingY;

    public SolidObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void reset() {
        frameCounter = 0;
        ridingObject = null;
    }

    /**
     * Returns true if the player is currently standing on a solid object.
     * Used by terrain collision to avoid overriding the object standing state.
     */
    public boolean isRidingObject() {
        return ridingObject != null;
    }

    public boolean isRidingObject(ObjectInstance instance) {
        return ridingObject == instance;
    }

    public void update(AbstractPlayableSprite player) {
        frameCounter++;
        if (player == null || objectManager == null || player.getDead()) {
            ridingObject = null;
            return;
        }

        // Skip all solid object collision in debug mode
        if (player.isDebugMode()) {
            ridingObject = null;
            return;
        }

        player.setPushing(false);

        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        if (ridingObject != null) {
            int currentX = ridingObject.getX();
            int currentY = ridingObject.getY();
            int deltaX = currentX - ridingX;
            int deltaY = currentY - ridingY;
            if (deltaX != 0) {
                int baseX = player.getCentreX() - (player.getWidth() / 2);
                player.setX((short) (baseX + deltaX));
            }
            if (deltaY != 0) {
                int baseY = player.getCentreY() - (player.getHeight() / 2);
                player.setY((short) (baseY + deltaY));
            }
        }

        ObjectInstance nextRidingObject = null;
        int nextRidingX = 0;
        int nextRidingY = 0;
        for (ObjectInstance instance : activeObjects) {
            if (!(instance instanceof SolidObjectProvider provider)) {
                continue;
            }
            if (!provider.isSolidFor(player)) {
                continue;
            }
            SolidObjectParams params = provider.getSolidParams();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
            SolidContact contact;
            byte[] slopeData = null;
            if (instance instanceof SlopedSolidProvider sloped) {
                slopeData = sloped.getSlopeData();
            }

            if (slopeData != null && instance instanceof SlopedSolidProvider sloped) {
                int slopeHalfHeight = params.groundHalfHeight();
                contact = resolveSlopedContact(player, instance.getSpawn(), params.halfWidth(), slopeHalfHeight,
                        slopeData, sloped.isSlopeFlipped(), provider.isTopSolidOnly(), instance);
            } else {
                contact = resolveContact(player, instance.getSpawn(), params.halfWidth(), halfHeight,
                        provider.isTopSolidOnly(), instance);
            }
            if (contact == null) {
                continue;
            }
            if (contact.pushing()) {
                player.setPushing(true);
            }
            if (contact.standing()) {
                nextRidingObject = instance;
                nextRidingX = instance.getX();
                nextRidingY = instance.getY();
            }
            if (instance instanceof SolidObjectListener listener) {
                listener.onSolidContact(player, contact, frameCounter);
            }
        }
        ridingObject = nextRidingObject;
        ridingX = nextRidingX;
        ridingY = nextRidingY;
    }

    private SolidContact resolveContact(AbstractPlayableSprite player,
            ObjectSpawn spawn, int halfWidth, int halfHeight, boolean topSolidOnly, ObjectInstance instance) {
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();

        int relX = playerCenterX - spawn.x() + halfWidth;
        if (relX < 0 || relX > halfWidth * 2) {
            return null;
        }

        int playerYRadius = player.getYRadius();
        int maxTop = halfHeight + playerYRadius;
        int relY = playerCenterY - spawn.y() + 4 + maxTop;

        // Sticky Check
        boolean riding = isRidingObject(instance);
        int minRelY = riding ? -16 : 0;

        if (relY < minRelY || relY >= maxTop * 2) {
            return null;
        }

        return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                topSolidOnly, riding);
    }

    private SolidContact resolveSlopedContact(AbstractPlayableSprite player, ObjectSpawn spawn, int halfWidth,
            int halfHeight, byte[] slopeData, boolean xFlip, boolean topSolidOnly, ObjectInstance instance) {
        if (slopeData == null || slopeData.length == 0) {
            return null;
        }
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();

        int relX = playerCenterX - spawn.x() + halfWidth;
        int width2 = halfWidth * 2;
        if (relX < 0 || relX > width2) {
            return null;
        }

        int sampleX = relX & 0xFFFF;
        if (xFlip) {
            sampleX = (~sampleX) & 0xFFFF;
            sampleX = (sampleX + width2) & 0xFFFF;
        }
        sampleX = (sampleX >> 1) & 0xFFFF;
        if (sampleX < 0 || sampleX >= slopeData.length) {
            return null;
        }

        int slopeSample = (byte) slopeData[sampleX];
        int slopeBase = (byte) slopeData[0];
        boolean riding = isRidingObject(instance);
        int minRelY = riding ? -16 : 0; // Look 16px above surface if riding

        int slopeOffset = slopeSample - slopeBase;
        int baseY = spawn.y() - slopeOffset;

        int playerYRadius = player.getYRadius();
        int maxTop = halfHeight + playerYRadius;
        int relY = playerCenterY - baseY + 4 + maxTop;

        if (relY < minRelY || relY >= maxTop * 2) {
            return null;
        }

        return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                topSolidOnly, riding);
    }

    private SolidContact resolveContactInternal(AbstractPlayableSprite player, int relX, int relY, int halfWidth,
            int maxTop, int playerCenterX, int playerCenterY, boolean topSolidOnly, boolean sticky) {
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
            absDistY = distY; // distY can be negative now if sticky
        } else {
            distY = relY - 4 - (maxTop * 2);
            absDistY = Math.abs(distY);
        }

        if (absDistX <= absDistY) {
            if (topSolidOnly) {
                return null;
            }
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

        // If distY is positive (penetration) OR negative within sticky tolerance
        if (distY >= 0 || (sticky && distY >= -16)) {
            // If we are sticking (distY < 0) but we are in the air and moving up, break the
            // stick.
            if (distY < 0 && player.getAir() && player.getYSpeed() < 0) {
                return null;
            }

            // If object is top-solid only (like a bridge), allow passing through from below
            if (topSolidOnly && player.getYSpeed() < 0) {
                return null;
            }

            int landingThreshold = 0x10;
            // Only reject if strictly ABOVE threshold (and not sticky snapping)
            if (distY >= landingThreshold) {
                return null;
            }

            // Snap Logic:
            // if distY > 0: playerCenterY - distY (Push UP)
            // if distY < 0: playerCenterY - (-4) = playerCenterY + 4 (Push DOWN)
            int newCenterY = playerCenterY - distY;
            int newY = newCenterY - (player.getHeight() / 2);
            player.setY((short) newY);
            if (player.getYSpeed() > 0) {
                player.setYSpeed((short) 0);
            }
            if (player.getAir()) {
                player.setGSpeed(player.getXSpeed());
                player.setAir(false);
                player.setRolling(false);
            }
            return new SolidContact(true, false, false, true, false);
        }

        if (topSolidOnly)

        {
            return null;
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
