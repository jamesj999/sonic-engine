package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * Animated propeller/booster child for the AIZ1 intro plane.
 * ROM: loc_67824 / loc_67862 (sonic3k.asm)
 *
 * Two instances are spawned after the plane child by CreateChild1_Normal.
 * Each owns a real SST slot, follows the plane at its child-data offset, and
 * self-destructs when the parent is destroyed. The legacy class name predates
 * the SST-slot implementation; these objects use Map_AIZIntroPlane and
 * ArtTile_AIZIntroPlane, not the separately loaded Chaos Emerald art.
 */
public class AizIntroEmeraldGlowChild extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(AizIntroEmeraldGlowChild.class.getName());
    private final AizIntroPlaneChild parent;
    private final int variant;
    private final int xOffset;
    private final int yOffset;

    private static final int[][] ANIM_SEQUENCES = {
            {1, 2, 3, 4, 3, 2},
            {5, 6}
    };
    private static final int[] X_OFFSETS = {0x38, 0x18};
    private static final int[] Y_OFFSETS = {0x04, 0x18};
    private static final int ANIM_FRAME_DURATION = 1;
    private int animTimer;
    private int animIndex;

    /**
     * @param spawn spawn data (position is overridden by parent tracking)
     * @param parent plane child to follow
     * @param variant normalized 0/1 animation and offset selector; must match
     *                {@code spawn.subtype() & 1}
     */
    public AizIntroEmeraldGlowChild(
            ObjectSpawn spawn, AizIntroPlaneChild parent, int variant) {
        super(spawn, "AIZEmeraldGlow");
        this.parent = parent;
        int spawnVariant = spawn.subtype() & 1;
        int normalizedVariant = Math.clamp(variant, 0, 1);
        if (normalizedVariant != spawnVariant) {
            throw new IllegalArgumentException("AIZ intro glow variant " + normalizedVariant
                    + " disagrees with captured spawn subtype variant " + spawnVariant);
        }
        this.variant = spawnVariant;
        this.xOffset = X_OFFSETS[this.variant];
        this.yOffset = Y_OFFSETS[this.variant];
    }

    @Override
    public AizIntroEmeraldGlowChild recreateForRewind(RewindRecreateContext ctx) {
        AizIntroPlaneChild livePlane = AizIntroRewindLinks.liveIntroPlane(ctx);
        return livePlane == null ? null
                : new AizIntroEmeraldGlowChild(ctx.spawn(), livePlane, ctx.spawn().subtype() & 1);
    }

    @Override
    public int getX() {
        return parent.getX() + xOffset;
    }

    @Override
    public int getY() {
        return parent.getY() + yOffset;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: loc_67824/loc_67862 write priority(a0) = $280.
        return 5;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (parent.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        animTimer++;
        if (animTimer >= ANIM_FRAME_DURATION) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= ANIM_SEQUENCES[variant].length) {
                animIndex = 0;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = planePieceRenderer();
        if (renderer == null || !renderer.isReady()) return;
        // Screen-space coordinates use the ROM +128 sprite-table bias.
        int renderX = getX();
        int renderY = getY();
        try {
            Camera camera = services().camera();
            renderX += camera.getX() - 128;
            renderY += camera.getY() - 128;
        } catch (Exception e) {
            LOG.fine(() -> "AizIntroEmeraldGlowChild.appendRenderCommands: " + e.getMessage());
        }
        renderer.drawFrameIndex(
                ANIM_SEQUENCES[variant][animIndex], renderX, renderY, false, false);
    }

    PatternSpriteRenderer planePieceRenderer() {
        return AizIntroArtLoader.getPlaneRenderer(services());
    }
}
