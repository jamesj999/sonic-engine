package uk.co.jamesj999.sonic.sprites.interactive.objects;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.interactive.InteractiveSprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

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
        if (!configService.getBoolean(SonicConfiguration.DEBUG_COLLISION_VIEW_ENABLED)) {
            return;
        }

        // Draw debug visualization
        // Horizontal Switcher (Axis 1): Horizontal Line. Drawn width = 2*Radius.
        // Vertical Switcher (Axis 0): Vertical Line. Drawn height = 2*Radius.
        // We will draw a rectangle to be visible.

        Camera camera = Camera.getInstance();
        int camX = camera.getX();
        int camY = camera.getY();

        int myX = this.getX();
        int myY = this.getY();

        // Screen coordinates
        int screenX = myX - camX;
        int screenY = myY - camY;

        // Use graphics manager to register commands
        // Similar to LevelManager.processCollisionMode

        // We need to disable textures and shaders for solid color drawing
        List<GLCommand> commands = new ArrayList<>();
        commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, 0));
        commands.add(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_TEXTURE_2D));

        int x1, y1, x2, y2;
        int thickness = 2; // Thickness of the visual line

        if (isHorizontal) {
            // Horizontal Line (Axis 1)
            // Draws across X (width = radius * 2)
            // Checks X range, Crosses Y.
            // Wait, previous logic: "Horizontal Line: Check if Player X is within the width (radius)"
            // So it spans horizontally.

            x1 = screenX - radius;
            x2 = screenX + radius;
            y1 = screenY - thickness;
            y2 = screenY + thickness;
        } else {
            // Vertical Line (Axis 0)
            // Draws vertically.
            x1 = screenX - thickness;
            x2 = screenX + thickness;
            y1 = screenY - radius;
            y2 = screenY + radius;
        }

        // Add coordinate offset for GL rendering (Engine.display resets view, but sprites might need to account for it if they use screen coords)
        // Actually, SpriteRenderManager usually handles translation?
        // No, SpriteRenderManager iterates sprites and calls draw().
        // LevelManager draws relative to camera.
        // Let's assume we need to provide absolute screen coordinates (0 to Width, 0 to Height).
        // My screenX/Y calculation is relative to top-left?
        // JOGL setup in Engine.java: gluOrtho2D(0, realWidth, 0, realHeight);
        // Y=0 is bottom.
        // Sonic coordinates: Y increases DOWN.
        // So Screen Y = (Height - (myY - camY)).
        // Actually, let's look at DebugRenderer: "height - yAdjusted".

        int screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        int glY = screenHeight - screenY; // Invert Y for GL

        // Recalculate rect for GL
        if (isHorizontal) {
            // Horizontal Line
            y1 = glY - thickness;
            y2 = glY + thickness;
        } else {
            // Vertical Line
            y1 = glY - radius;
            y2 = glY + radius;
        }

        // Offset X is just screenX?
        // gluOrtho2D 0 is left.

        // Color: Orange (1.0, 0.5, 0.0)
        commands.add(new GLCommand(GLCommand.CommandType.RECTI, GL2.GL_2D, 1.0f, 0.5f, 0.0f, x1, y2, x2, y1));

        // Restore state
        commands.add(new GLCommand(GLCommand.CommandType.ENABLE, GL2.GL_TEXTURE_2D));
        // We don't easily know the previous program ID here without querying, but LevelManager restores it.
        // For safety, we can leave it 0 or try to restore if we knew it.
        // Standard practice here seems to be "enable texturing" and let next draw call set program?
        // Or we can rely on GraphicsManager state.

        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));
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
