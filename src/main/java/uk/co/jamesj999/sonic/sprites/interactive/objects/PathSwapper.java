package uk.co.jamesj999.sonic.sprites.interactive.objects;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.interactive.InteractiveSprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class PathSwapper extends AbstractSprite implements InteractiveSprite {
    private final byte subtype;
    private final int radius;
    private final boolean isHorizontal; // Axis: 0 = Vertical, 1 = Horizontal
    private final boolean leftTopLayer; // Bit 3
    private final boolean rightBottomLayer; // Bit 4
    private final boolean groundedOnly; // Bit 7

    // Track current side of the player.
    // For Vertical: Left = false, Right = true
    // For Horizontal: Top = false, Bottom = true
    private boolean currentSideRightBottom;

    // Track if we have initialized the side yet (avoid triggering on spawn)
    private boolean initialized = false;

    public PathSwapper(String code, short x, short y, byte subtype) {
        super(code, x, y);
        this.subtype = subtype;

        // Parse subtype
        int sizeIndex = subtype & 0x03; // Bits 0-1
        // Radius: 00=32, 01=64, 10=128, 11=256
        this.radius = 32 << sizeIndex;

        // Bit 2: Orientation (Axis)
        // 0 = Vertical Line (Check Y range, Cross X)
        // 1 = Horizontal Line (Check X range, Cross Y)
        this.isHorizontal = (subtype & 0x04) != 0;

        // Bits 3-4: Path IDs
        // Bit 3: Left/Top Layer (0=Primary, 1=Secondary)
        this.leftTopLayer = (subtype & 0x08) != 0;
        // Bit 4: Right/Bottom Layer (0=Primary, 1=Secondary)
        this.rightBottomLayer = (subtype & 0x10) != 0;

        this.groundedOnly = (subtype & 0x80) != 0; // Bit 7
    }

    @Override
    public void draw() {
        // Invisible object, do nothing.
    }

    @Override
    protected void createSensorLines() {
        // No sensors needed for this object
    }

    @Override
    public boolean onCollide(AbstractSprite sprite) {
        if (sprite instanceof AbstractPlayableSprite) {
            handlePlayer((AbstractPlayableSprite) sprite);
        }
        return false;
    }

    private void handlePlayer(AbstractPlayableSprite player) {
        int playerX = player.getX();
        int playerY = player.getY();

        int myX = this.getX();
        int myY = this.getY();

        boolean inRange = false;

        // Note: "Horizontal" usually refers to the orientation of the line itself.
        // Horizontal Line (Axis=1): You cross it by moving vertically (Y axis). Checks X range.
        // Vertical Line (Axis=0): You cross it by moving horizontally (X axis). Checks Y range.
        if (isHorizontal) {
            // Horizontal Line: Check if Player X is within the width (radius)
            if (Math.abs(playerX - myX) <= radius) {
                inRange = true;
            }
        } else {
            // Vertical Line: Check if Player Y is within the height (radius)
            if (Math.abs(playerY - myY) <= radius) {
                inRange = true;
            }
        }

        // Determine current side (where player is NOW relative to the line)
        boolean newSideRightBottom;
        if (isHorizontal) {
            // Horizontal Line: Check Y. Top (smaller Y) vs Bottom (larger Y)
            newSideRightBottom = playerY >= myY;
        } else {
            // Vertical Line: Check X. Left (smaller X) vs Right (larger X)
            newSideRightBottom = playerX >= myX;
        }

        if (!initialized) {
            currentSideRightBottom = newSideRightBottom;
            initialized = true;
            return;
        }

        // Only switch if in range AND side changed (crossed the threshold)
        if (inRange && (newSideRightBottom != currentSideRightBottom)) {
            // Check Grounded Only flag
            if (!groundedOnly || player.getGroundMode() == uk.co.jamesj999.sonic.sprites.playable.GroundMode.GROUND) {
                performSwitch(player, newSideRightBottom);
            }
        }

        // Update state
        currentSideRightBottom = newSideRightBottom;
    }

    private void performSwitch(AbstractPlayableSprite player, boolean toRightBottom) {
        // Determine which layer to switch to based on the side entered
        boolean targetLayerBit = toRightBottom ? rightBottomLayer : leftTopLayer;
        byte targetLayer = targetLayerBit ? (byte) 1 : (byte) 0;

        player.setLayer(targetLayer);
    }
}
