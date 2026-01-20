package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.logging.Logger;

public class SolidObjectManager {
    private static final Logger LOGGER = Logger.getLogger(SolidObjectManager.class.getName());
    private final ObjectManager objectManager;
    private int frameCounter;
    private ObjectInstance ridingObject;
    private int ridingX;
    private int ridingY;
    // For multi-piece objects, track the piece index to get correct delta
    private int ridingPieceIndex = -1;

    public SolidObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void reset() {
        frameCounter = 0;
        ridingObject = null;
        ridingPieceIndex = -1;
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

    /**
     * Clears the riding object state. Should be called when the player jumps
     * to prevent the sticky snapping tolerance from keeping them grounded.
     */
    public void clearRidingObject() {
        ridingObject = null;
    }

    public boolean hasStandingContact(AbstractPlayableSprite player) {
        if (player == null || objectManager == null || player.getDead()) {
            return false;
        }
        if (player.isDebugMode()) {
            return false;
        }
        // Disasm (SolidObject_Landed): If Sonic is moving upwards, he cannot be
        // standing on an object. This prevents the hasStandingContact check from
        // keeping the player grounded when they should be jumping.
        if (player.getYSpeed() < 0) {
            return false;
        }
        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        for (ObjectInstance instance : activeObjects) {
            if (!(instance instanceof SolidObjectProvider provider)) {
                continue;
            }
            if (!provider.isSolidFor(player)) {
                continue;
            }

            // Handle multi-piece objects (e.g., CPZ Staircase)
            if (provider instanceof MultiPieceSolidProvider multiPiece) {
                if (hasStandingContactMultiPiece(player, multiPiece, instance)) {
                    return true;
                }
                continue;
            }

            SolidObjectParams params = provider.getSolidParams();
            int anchorX = instance.getSpawn().x() + params.offsetX();
            int anchorY = instance.getSpawn().y() + params.offsetY();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
            byte[] slopeData = null;
            if (instance instanceof SlopedSolidProvider sloped) {
                slopeData = sloped.getSlopeData();
            }
            SolidContact contact;
            if (slopeData != null && instance instanceof SlopedSolidProvider sloped) {
                int slopeHalfHeight = params.groundHalfHeight();
                contact = resolveSlopedContact(player, anchorX, anchorY, params.halfWidth(), slopeHalfHeight,
                        slopeData, sloped.isSlopeFlipped(), provider.isTopSolidOnly(), instance, false);
            } else {
                contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                        provider.isTopSolidOnly(), instance, false);
            }
            if (contact != null && contact.standing()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStandingContactMultiPiece(AbstractPlayableSprite player,
            MultiPieceSolidProvider multiPiece, ObjectInstance instance) {
        int pieceCount = multiPiece.getPieceCount();
        for (int i = 0; i < pieceCount; i++) {
            SolidObjectParams params = multiPiece.getPieceParams(i);
            int anchorX = multiPiece.getPieceX(i) + params.offsetX();
            int anchorY = multiPiece.getPieceY(i) + params.offsetY();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();

            SolidContact contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                    multiPiece.isTopSolidOnly(), instance, false);
            if (contact != null && contact.standing()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if there's enough headroom for the player to jump.
     * Based on the original game's CalcRoomOverHead routine, this checks for
     * solid objects in the "overhead" direction relative to the current ground angle.
     *
     * The original game requires at least 6 pixels of room overhead to allow a jump.
     * The overhead direction is determined by adding 0x80 to the current angle:
     * - Angles 0x20-0x5F (after +0x80): check LEFT
     * - Angles 0x60-0x9F (after +0x80): check UP (ceiling)
     * - Angles 0xA0-0xDF (after +0x80): check RIGHT
     * - Angles 0xE0-0x1F (after +0x80): check DOWN (floor, unlikely on ground)
     *
     * @param player The player sprite
     * @param hexAngle The current ground angle in hex format (0-255)
     * @return The distance to the nearest solid object in the overhead direction,
     *         or Integer.MAX_VALUE if no obstruction is found
     */
    public int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
        if (player == null || objectManager == null || player.getDead()) {
            return Integer.MAX_VALUE;
        }
        if (player.isDebugMode()) {
            return Integer.MAX_VALUE;
        }

        int overheadAngle = (hexAngle + 0x80) & 0xFF;
        int quadrant = (overheadAngle + 0x20) & 0xC0;

        int minDistance = Integer.MAX_VALUE;
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();
        int playerXRadius = player.getXRadius();
        int playerYRadius = player.getYRadius();

        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        for (ObjectInstance instance : activeObjects) {
            if (!(instance instanceof SolidObjectProvider provider)) {
                continue;
            }
            if (!provider.isSolidFor(player)) {
                continue;
            }
            if (provider.isTopSolidOnly()) {
                continue;
            }
            SolidObjectParams params = provider.getSolidParams();
            int anchorX = instance.getSpawn().x() + params.offsetX();
            int anchorY = instance.getSpawn().y() + params.offsetY();
            int halfWidth = params.halfWidth();
            int halfHeight = params.groundHalfHeight();

            int distance = calculateOverheadDistance(quadrant, playerCenterX, playerCenterY,
                    playerXRadius, playerYRadius, anchorX, anchorY, halfWidth, halfHeight);
            if (distance >= 0 && distance < minDistance) {
                minDistance = distance;
            }
        }
        return minDistance;
    }

    private int calculateOverheadDistance(int quadrant, int playerCenterX, int playerCenterY,
            int playerXRadius, int playerYRadius, int objX, int objY, int objHalfWidth, int objHalfHeight) {
        switch (quadrant) {
            case 0x40 -> {
                // Check LEFT (overhead is to the left)
                int objRight = objX + objHalfWidth;
                int playerLeft = playerCenterX - playerXRadius;
                if (playerLeft < objRight) {
                    return -1;
                }
                int objTop = objY - objHalfHeight;
                int objBottom = objY + objHalfHeight;
                int playerTop = playerCenterY - playerYRadius;
                int playerBottom = playerCenterY + playerYRadius;
                if (playerBottom < objTop || playerTop > objBottom) {
                    return -1;
                }
                return playerLeft - objRight;
            }
            case 0x80 -> {
                // Check UP (overhead is above - standard ceiling check)
                int objBottom = objY + objHalfHeight;
                int playerTop = playerCenterY - playerYRadius;
                if (playerTop < objBottom) {
                    return -1;
                }
                int objLeft = objX - objHalfWidth;
                int objRight = objX + objHalfWidth;
                int playerLeft = playerCenterX - playerXRadius;
                int playerRight = playerCenterX + playerXRadius;
                if (playerRight < objLeft || playerLeft > objRight) {
                    return -1;
                }
                return playerTop - objBottom;
            }
            case 0xC0 -> {
                // Check RIGHT (overhead is to the right)
                int objLeft = objX - objHalfWidth;
                int playerRight = playerCenterX + playerXRadius;
                if (playerRight > objLeft) {
                    return -1;
                }
                int objTop = objY - objHalfHeight;
                int objBottom = objY + objHalfHeight;
                int playerTop = playerCenterY - playerYRadius;
                int playerBottom = playerCenterY + playerYRadius;
                if (playerBottom < objTop || playerTop > objBottom) {
                    return -1;
                }
                return objLeft - playerRight;
            }
            default -> {
                // 0x00: Check DOWN (floor) - unlikely when on ground, return no obstruction
                return Integer.MAX_VALUE;
            }
        }
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

        // Original game behavior (s2.asm SolidObject, lines 34806-34828):
        // Check if player is still within bounds of the platform's NEW position BEFORE
        // applying any movement delta. This is crucial for multi-block objects like
        // MTZ platforms where blocks teleport - if a block teleports away from Sonic,
        // the bounds check fails and Sonic falls off (transfers to another block)
        // instead of teleporting with the block.
        if (ridingObject != null && ridingObject instanceof SolidObjectProvider provider) {
            int currentX;
            int currentY;
            SolidObjectParams params;

            // For multi-piece objects, get the current position of the specific piece
            if (ridingPieceIndex >= 0 && ridingObject instanceof MultiPieceSolidProvider multiPiece) {
                currentX = multiPiece.getPieceX(ridingPieceIndex);
                currentY = multiPiece.getPieceY(ridingPieceIndex);
                params = multiPiece.getPieceParams(ridingPieceIndex);
            } else {
                currentX = ridingObject.getX();
                currentY = ridingObject.getY();
                params = provider.getSolidParams();
            }

            // Check if player is still within X bounds of the platform's NEW position
            // Original: sub.w x_pos(a0),d0 / add.w d1,d0 / bmi.s detach / cmp.w d2,d0 / bhs.s detach
            int halfWidth = params.halfWidth();
            int relX = player.getCentreX() - currentX + halfWidth;
            boolean inBounds = relX >= 0 && relX < halfWidth * 2;

            if (inBounds && provider.isSolidFor(player)) {
                // Still in bounds - apply delta immediately (like original's MvSonicOnPtfm)
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
                ridingX = currentX;
                ridingY = currentY;
            } else {
                // Out of bounds - detach from this object (like original's loc_1975A)
                // Don't apply any delta; collision detection will find a new object to stand on
                ridingObject = null;
                ridingPieceIndex = -1;
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

            // Handle multi-piece objects (e.g., CPZ Staircase)
            if (provider instanceof MultiPieceSolidProvider multiPiece) {
                MultiPieceContactResult result = processMultiPieceCollision(player, multiPiece, instance, frameCounter);
                if (result.pushing) {
                    player.setPushing(true);
                }
                if (result.standing) {
                    nextRidingObject = instance;
                    nextRidingX = result.ridingX;
                    nextRidingY = result.ridingY;
                    ridingPieceIndex = result.pieceIndex;
                }
                continue;
            }

            SolidObjectParams params = provider.getSolidParams();
            int anchorX = instance.getSpawn().x() + params.offsetX();
            int anchorY = instance.getSpawn().y() + params.offsetY();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
            SolidContact contact;
            byte[] slopeData = null;
            if (instance instanceof SlopedSolidProvider sloped) {
                slopeData = sloped.getSlopeData();
            }

            if (slopeData != null && instance instanceof SlopedSolidProvider sloped) {
                int slopeHalfHeight = params.groundHalfHeight();
                contact = resolveSlopedContact(player, anchorX, anchorY, params.halfWidth(), slopeHalfHeight,
                        slopeData, sloped.isSlopeFlipped(), provider.isTopSolidOnly(), instance, true);
            } else {
                contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                        provider.isTopSolidOnly(), instance, true);
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
                ridingPieceIndex = -1;  // Not a multi-piece object
            }
            if (instance instanceof SolidObjectListener listener) {
                listener.onSolidContact(player, contact, frameCounter);
            }
        }
        ridingObject = nextRidingObject;
        ridingX = nextRidingX;
        ridingY = nextRidingY;
    }

    private record MultiPieceContactResult(boolean standing, boolean pushing, int ridingX, int ridingY, int pieceIndex) {}

    /**
     * Processes collision for multi-piece objects like CPZ Staircase.
     *
     * Based on the original Sonic 2 disassembly (Obj78), each piece is a separate object
     * that calls SolidObject independently. The pieces have intentionally overlapping
     * collision boxes (27px half-width with 32px spacing = 22px overlap) to create
     * smooth transitions when walking across adjacent pieces.
     *
     * The original approach:
     * 1. Each piece calls SolidObject independently
     * 2. Touch flags from all pieces are ORed together (objoff_2E)
     * 3. Collision naturally resolves because pieces are processed sequentially
     *
     * Our approach mimics this by processing pieces sequentially. When standing contact
     * is found, we snap to that piece and record it for riding. Subsequent pieces
     * will either also have standing contact (same height) or be out of bounds
     * (different heights after snap).
     */
    private MultiPieceContactResult processMultiPieceCollision(AbstractPlayableSprite player,
            MultiPieceSolidProvider multiPiece, ObjectInstance instance, int frameCounter) {
        int pieceCount = multiPiece.getPieceCount();

        boolean anyStanding = false;
        boolean anyPushing = false;
        boolean anyTouchTop = false;
        boolean anyTouchBottom = false;
        boolean anyTouchSide = false;

        int standingPieceIndex = -1;
        int standingPieceX = 0;
        int standingPieceY = 0;

        // Process each piece sequentially, like the original game
        // Each piece gets its own SolidObject collision call
        for (int i = 0; i < pieceCount; i++) {
            SolidObjectParams params = multiPiece.getPieceParams(i);
            int pieceX = multiPiece.getPieceX(i);
            int pieceY = multiPiece.getPieceY(i);
            int anchorX = pieceX + params.offsetX();
            int anchorY = pieceY + params.offsetY();
            int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();

            // Process collision with apply=true for each piece
            // The collision logic naturally handles sequential processing
            SolidContact contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                    multiPiece.isTopSolidOnly(), instance, true);

            if (contact == null) {
                continue;
            }

            // OR all touch flags together (like the original: or.b d6,objoff_2E(a2))
            if (contact.standing()) {
                anyStanding = true;
                // Track the first piece we land on for riding delta calculations
                if (standingPieceIndex < 0) {
                    standingPieceIndex = i;
                    standingPieceX = pieceX;
                    standingPieceY = pieceY;
                }
            }
            if (contact.touchTop()) {
                anyTouchTop = true;
            }
            if (contact.touchBottom()) {
                anyTouchBottom = true;
            }
            if (contact.touchSide()) {
                anyTouchSide = true;
            }
            if (contact.pushing()) {
                anyPushing = true;
            }

            // Notify the object about this piece's contact (for touch detection/triggers)
            multiPiece.onPieceContact(i, player, contact, frameCounter);
        }

        // Notify the object about aggregate contact (for compatibility)
        if (anyStanding || anyTouchTop || anyTouchBottom || anyTouchSide || anyPushing) {
            if (instance instanceof SolidObjectListener listener) {
                SolidContact aggregateContact = new SolidContact(
                        anyStanding, anyTouchSide, anyTouchBottom, anyTouchTop, anyPushing);
                listener.onSolidContact(player, aggregateContact, frameCounter);
            }
        }

        return new MultiPieceContactResult(anyStanding, anyPushing, standingPieceX, standingPieceY, standingPieceIndex);
    }

    private SolidContact resolveContact(AbstractPlayableSprite player,
            int anchorX, int anchorY, int halfWidth, int halfHeight, boolean topSolidOnly, ObjectInstance instance,
            boolean apply) {
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();

        int relX = playerCenterX - anchorX + halfWidth;
        if (relX < 0 || relX > halfWidth * 2) {
            return null;
        }

        int playerYRadius = player.getYRadius();
        int maxTop = halfHeight + playerYRadius;
        int relY = playerCenterY - anchorY + 4 + maxTop;

        // Sticky Check
        boolean riding = isRidingObject(instance);
        int minRelY = riding ? -16 : 0;

        if (relY < minRelY || relY >= maxTop * 2) {
            return null;
        }

        return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                topSolidOnly, riding, apply);
    }

    private SolidContact resolveSlopedContact(AbstractPlayableSprite player, int anchorX, int anchorY, int halfWidth,
            int halfHeight, byte[] slopeData, boolean xFlip, boolean topSolidOnly, ObjectInstance instance,
            boolean apply) {
        if (slopeData == null || slopeData.length == 0) {
            return null;
        }
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();

        int relX = playerCenterX - anchorX + halfWidth;
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
        int baseY = anchorY - slopeOffset;

        int playerYRadius = player.getYRadius();
        int maxTop = halfHeight + playerYRadius;
        int relY = playerCenterY - baseY + 4 + maxTop;

        if (relY < minRelY || relY >= maxTop * 2) {
            return null;
        }

        return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                topSolidOnly, riding, apply);
    }

    private SolidContact resolveContactInternal(AbstractPlayableSprite player, int relX, int relY, int halfWidth,
            int maxTop, int playerCenterX, int playerCenterY, boolean topSolidOnly, boolean sticky, boolean apply) {
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
            // Original disassembly (SolidObject_LeftRight) uses threshold of 4 pixels.
            // We use a slightly larger threshold (6) to account for minor terrain/block
            // surface alignment differences. This allows stepping onto blocks when
            // the player's feet are within a few pixels of the block's top surface.
            boolean nearVerticalEdge = absDistY <= 6;

            // Original disassembly (SolidObject_LeftRight):
            // If Sonic is extremely close to the top or bottom, branch
            // to SolidObject_SideAir which returns side contact WITHOUT pushing out.
            // This allows walking over objects that are barely poking out.
            if (nearVerticalEdge) {
                // Just return side contact for touch detection, don't push out
                return new SolidContact(false, true, false, false, false);
            }

            // Not near vertical edge - do full side collision with push out
            boolean pushing = !player.getAir();
            boolean movingInto = leftSide ? player.getXSpeed() > 0 : player.getXSpeed() < 0;
            if (apply) {
                if (movingInto) {
                    player.setXSpeed((short) 0);
                    player.setGSpeed((short) 0);
                }
                player.setCentreX((short) (playerCenterX - distX));
            }
            return new SolidContact(false, true, false, false, pushing);
        }

        // If distY is positive (penetration) OR negative within sticky tolerance
        if (distY >= 0 || (sticky && distY >= -16)) {
            // Disasm (SolidObject_Landed): If Sonic is moving upwards, don't land on object.
            // This prevents landing on objects when jumping, even if slightly overlapping.
            if (player.getYSpeed() < 0) {
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

            // Snap Logic (from PlatformObject_cont):
            // Original: add.w d0,d2 / addq.w #3,d2
            // The +3 adjustment brings the player's feet to 1 pixel above the surface,
            // matching the original game's landing behavior.
            if (apply) {
                int newCenterY = playerCenterY - distY + 3;
                int newY = newCenterY - (player.getHeight() / 2);
                player.setY((short) newY);
                if (player.getYSpeed() > 0) {
                    player.setYSpeed((short) 0);
                }
                if (player.getAir()) {
                    LOGGER.fine(() -> "Solid object landing at (" + player.getX() + "," + player.getY() +
                        ") distY=" + distY);
                    player.setGSpeed(player.getXSpeed());
                    player.setAir(false);
                    // Sonic_ResetOnFloor (s2.asm:37744): Check pinball_mode before clearing rolling
                    // This allows spin tubes and other "must roll" areas to preserve rolling on landing
                    if (!player.getPinballMode()) {
                        player.setRolling(false);
                    }
                    // Clear pinball mode after landing check - only protects one landing
                    player.setPinballMode(false);
                }
            }
            return new SolidContact(true, false, false, true, false);
        }

        if (topSolidOnly) {
            return null;
        }

        // This is CEILING collision (hit from below)
        if (apply) {
            int newCenterY = playerCenterY - distY;
            int newY = newCenterY - (player.getHeight() / 2);
            player.setY((short) newY);
            if (player.getYSpeed() < 0) {
                LOGGER.fine(() -> "Solid object ceiling hit, zeroing ySpeed from " + player.getYSpeed());
                player.setYSpeed((short) 0);
            }
        }
        return new SolidContact(false, false, true, false, false);
    }
}
