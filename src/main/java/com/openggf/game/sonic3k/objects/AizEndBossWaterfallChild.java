package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * AIZ2 end-boss waterfall splash allocated by {@code ChildObjDat_69D2E}.
 *
 * <p>The child starts with the {@code $24} mapping from
 * {@code ObjDat_AIZEndBossWaterfall}; the first raw-animation tick advances to
 * the second entry, matching {@code Animate_RawNoSSTMultiDelayFlipX}'s
 * pre-incremented animation cursor. Subtype 0 deletes at the {@code $F4}
 * callback. Subtype 2 switches to the falling-drop routine, applies ROM's
 * {@code y_vel=$800}, and deletes at that routine's callback.</p>
 *
 * <p>ROM: {@code sonic3k.asm:138701-138739,139024-139026,139046-139049,
 * 139193-139221}.</p>
 */
public final class AizEndBossWaterfallChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int SUBTYPE_EMERGE = 0;
    private static final int SUBTYPE_DROP = 2;

    private static final int STATE_EMERGE = 0;
    private static final int STATE_DROP = 1;

    private static final int INITIAL_MAPPING_FRAME = 0x24;
    private static final int HIDDEN_MAPPING_FRAME = 0x2B;
    private static final int DROP_VELOCITY = 0x800;

    // AniRaw_AIZEndBossWaterfall. Values include Animate_Raw...FlipX's bit 6.
    private static final int[] EMERGE_FRAMES = {
            0x2B, 0x24, 0x24 | 0x40, 0x2B | 0x40,
            0x2C, 0x2C | 0x40, 0x2B | 0x40,
            0x24, 0x24 | 0x40, 0x2B | 0x40,
            0x2C, 0x2C | 0x40, 0x2B | 0x40
    };

    // AniRaw_AIZEndBossWaterfallDrop. Values include Animate_Raw...FlipX's bit 6.
    private static final int[] DROP_FRAMES = {
            0x2B, 0x24, 0x24 | 0x40, 0x2B | 0x40,
            0x2C, 0x2C | 0x40, 0x2B | 0x40,
            0x31, 0x31 | 0x40, 0x2B | 0x40,
            0x31, 0x31 | 0x40, 0x2B | 0x40
    };

    private final AizEndBossInstance boss;

    // All mutable fields below are ROM object state and are intentionally
    // non-final so the generic rewind capturer restores the active routine.
    private int routine = STATE_EMERGE;
    private boolean initialized;
    private boolean drawEligible;
    private int animationIndex;
    private int animationTimer;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private boolean renderFlipX;
    private int currentX;
    private int currentY;
    private int ySubpixel;
    private int yVelocity;

    public AizEndBossWaterfallChild(AizEndBossInstance boss, int subtype) {
        this(buildSpawn(boss, subtype), boss);
    }

    /**
     * Restore-probe signature: the generic dynamic rewind helper supplies the
     * captured spawn and a live AIZ end-boss parent before calling
     * {@link #recreateForRewind(RewindRecreateContext)}.
     */
    public AizEndBossWaterfallChild(ObjectSpawn spawn, AizEndBossInstance boss) {
        super(spawn, "AIZEndBossWaterfall");
        this.boss = boss;
        this.currentX = spawn == null ? boss.getX() : spawn.x();
        this.currentY = spawn == null ? boss.getY() : spawn.y();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null) {
            return null;
        }

        int subtype = ctx.spawn() == null ? SUBTYPE_EMERGE : ctx.spawn().subtype();
        AizEndBossWaterfallChild restored =
                new AizEndBossWaterfallChild(restoredBoss, subtype);
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        restored.rebuildDynamicSpawn(restored.currentX, restored.currentY);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        // Obj_AIZEndBossWaterfall_Init only installs attributes and the
        // callback. The first higher-slot dispatch returns before animation,
        // movement, or drawing; later dispatches enter the selected routine.
        if (!initialized) {
            initialized = true;
            return;
        }

        if (routine == STATE_DROP) {
            moveSprite2();
            animate(DROP_FRAMES);
        } else {
            // CreateChild1_Normal gives the child a position snapshot. The ROM
            // child does not follow later boss movement during this routine.
            animate(EMERGE_FRAMES);
        }
        if (!isDestroyed()) {
            drawEligible = true;
        }
    }

    private void animate(int[] frames) {
        // Animate_RawNoSSTMultiDelayFlipX: subq.b timer, then advance by one
        // pair. All waterfall delays are zero, but retaining the timer models
        // the ROM byte and makes the rewind surface explicit.
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }

        animationIndex++;
        if (animationIndex >= frames.length) {
            animationIndex = 0;
            animationTimer = 0;
            if (routine == STATE_DROP || getSpawn().subtype() != SUBTYPE_DROP) {
                // Go_Delete_Sprite for subtype 0 and the drop callback after
                // the subtype-2 drop script both delete the child.
                setDestroyed(true);
            } else {
                // AIZEndBossWaterfall_StartDrop changes only the routine,
                // y_vel and delete callback. It leaves the current mapping and
                // render flip in place until the first drop tick.
                routine = STATE_DROP;
                yVelocity = DROP_VELOCITY;
            }
            return;
        }

        int encodedFrame = frames[animationIndex];
        if ((encodedFrame & 0x40) != 0) {
            // The ROM uses bchg #0,render_flags, not an absolute assignment.
            renderFlipX = !renderFlipX;
        }
        mappingFrame = encodedFrame & 0x3F;
        animationTimer = 0;
    }

    private void moveSprite2() {
        int fixedY = (currentY << 8) | (ySubpixel & 0xFF);
        fixedY += yVelocity;
        currentY = fixedY >> 8;
        ySubpixel = fixedY & 0xFF;
        updateDynamicSpawn(currentX, currentY);
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
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || !drawEligible || mappingFrame == HIDDEN_MAPPING_FRAME) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, renderFlipX, false, 0);
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat_AIZEndBossWaterfall priority $100 → object bucket 2.
        return RenderPriority.clamp(2);
    }

    @Override
    public boolean isHighPriority() {
        // make_art_tile(ArtTile_AIZEndBoss,0,1) sets the ROM priority bit.
        return true;
    }

    public boolean isDroppingForTest() {
        return routine == STATE_DROP;
    }

    public int getYVelocityForTest() {
        return yVelocity;
    }

    public int getMappingFrameForTest() {
        return mappingFrame;
    }

    private static ObjectSpawn buildSpawn(AizEndBossInstance boss, int subtype) {
        int objectId = boss.getSpawn() == null
                ? Sonic3kObjectIds.AIZ_END_BOSS
                : boss.getSpawn().objectId();
        return new ObjectSpawn(boss.getX(), boss.getY(), objectId, subtype, 0, false, 0);
    }
}
