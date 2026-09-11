package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Plane child sprite for AIZ1 intro - the biplane that Sonic rides.
 * ROM: loc_45B08 (s3.asm) — child of Obj_intPlane.
 *
 * Uses Map_AIZIntroPlane / ArtTile_AIZIntroPlane.
 * Normal mode follows parent position with an offset and applies swing
 * oscillation to Y (shared Swing_UpAndDown parameters with parent).
 * After parent detaches (routine 8), swings independently.
 * When parent signals walk-left (routine 0xA), walks left and self-deletes at x<0x20.
 *
 * Spawns 2 AizIntroBoosterChild sub-objects for the booster flame animation.
 */
public class AizIntroPlaneChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizIntroPlaneChild.class.getName());

    // Swing parameters from ROM (Swing_UpAndDown acceleration / max)
    private static final int SWING_ACCELERATION = 0x10;
    private static final int SWING_MAX_VELOCITY = 0xC0;

    // Offset from parent position (plane drawn relative to intro object)
    private static final int PARENT_X_OFFSET = -0x22;
    private static final int PARENT_Y_OFFSET = 0x2C;

    /** X threshold below which walk-left self-deletes. */
    private static final int DELETE_X = 0x20;
    private final AizPlaneIntroInstance parent;
    private int currentX;
    private int currentY;
    private int swingVelocity;
    private boolean swingDirectionDown;
    private int mappingFrame;

    /** Fractional Y accumulator for subpixel swing tracking. */
    private int ySub;

    // Emerald glow children (spawned during init, follow this plane)
    private AizIntroEmeraldGlowChild glowChild1;
    private AizIntroEmeraldGlowChild glowChild2;

    public AizIntroPlaneChild(ObjectSpawn spawn, AizPlaneIntroInstance parent) {
        super(spawn, "AIZIntroPlane");
        this.parent = parent;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.ySub = 0;

    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizPlaneIntroInstance liveParent = AizIntroRewindLinks.liveIntroParent(ctx);
        return liveParent == null ? null : new AizIntroPlaneChild(ctx.spawn(), liveParent);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: loc_6777A writes priority(a0) = $280.
        return 5;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        boolean detached = parent.isPlaneDetached();
        boolean walkLeft = parent.isPlaneShouldWalkLeft();

        if (walkLeft) {
            // Walk left mode: move left 4px/frame, continue swinging
            currentX -= 4;
            applySwingMove();

            // Self-delete when walked off-screen
            if (currentX < DELETE_X) {
                LOG.fine("Plane child: walked off-screen left, destroying");
                setDestroyed(true);
            }
        } else if (detached) {
            // Detached mode: swing independently, don't follow parent
            applySwingMove();
        } else {
            // ROM order: Swing_UpAndDown + MoveSprite2, then Refresh_ChildPosition
            // while attached. Refresh_ChildPosition overrides X/Y to parent+offset.
            applySwingMove();
            currentX = parent.getX() + PARENT_X_OFFSET;
            currentY = parent.getY() + PARENT_Y_OFFSET;
        }

    }

    /**
     * Sets the two emerald glow children. Called during integration when
     * the parent spawns this plane child and its glow sub-children.
     */
    public void setGlowChildren(AizIntroEmeraldGlowChild glow1, AizIntroEmeraldGlowChild glow2) {
        this.glowChild1 = glow1;
        this.glowChild2 = glow2;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public void setMappingFrame(int mappingFrame) {
        this.mappingFrame = mappingFrame;
    }

    private void applySwingMove() {
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCELERATION, swingVelocity, SWING_MAX_VELOCITY, swingDirectionDown);
        swingVelocity = result.velocity();
        swingDirectionDown = result.directionDown();

        int yTotal = (ySub & 0xFF) + (swingVelocity & 0xFF);
        currentY += (swingVelocity >> 8) + (yTotal >> 8);
        ySub = yTotal & 0xFF;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getPlaneRenderer(services());
        if (renderer == null || !renderer.isReady()) return;
        // Screen-space coordinates use the ROM +128 sprite-table bias.
        int renderX = currentX;
        int renderY = currentY;
        Camera camera = null;
        try {
            camera = services().camera();
            if (camera != null) {
                renderX += camera.getX() - 128;
                renderY += camera.getY() - 128;
            }
        } catch (Exception e) {
            LOG.fine(() -> "AizIntroPlaneChild.appendRenderCommands: " + e.getMessage());
        }
        renderer.drawFrameIndex(mappingFrame, renderX, renderY, false, false);

        // The two animated pieces render from their own SST objects after this
        // parent, matching CreateChild1_Normal allocation/render order.
    }
}
