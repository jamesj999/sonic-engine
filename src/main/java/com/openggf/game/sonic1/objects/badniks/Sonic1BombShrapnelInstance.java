package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroPairRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Bomb Shrapnel child object (subtype 6) - Flying debris pieces after the bomb fuse expires.
 * <p>
 * Based on docs/s1disasm/_incObj/5F Bomb Enemy.asm, Bom_End routine (routine 6).
 * <p>
 * Each shrapnel piece flies with predefined X/Y velocity from Bom_ShrSpeed, with
 * gravity applied each frame (addi.w #$18,obVelY). The shrapnel hurts Sonic on
 * contact (obColType = $98: HURT category $80, size index $18 = 4x4 pixels).
 * <p>
 * Shrapnel is deleted when it goes off-screen (tst.b obRender(a0) / bpl.w DeleteObject).
 * <p>
 * Animations: Uses shrapnel animation (Ani_Bomb index 4): frames $A, $B at speed 3.
 */
public class Sonic1BombShrapnelInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateZeroPairRewindRecreatable {

    // From disassembly: move.b #$98,obColType(a1)
    // $80 = HURT category, $18 = size index (width 4, height 4)
    private static final int COLLISION_SIZE_INDEX = 0x18;

    // From disassembly: addi.w #$18,obVelY(a0) (gravity per frame)
    private static final int GRAVITY = 0x18;

    // Animation speed 3 + 1 = 4 ticks per frame
    private static final int ANIM_SPEED = 3 + 1;

    // Shrapnel mapping frames (from Ani_Bomb .shrapnel: $A, $B)
    private static final int[] SHRAPNEL_FRAMES = {10, 11};

    // From disassembly: move.b #3,obPriority (inherited from parent init)
    // Bom_End does not change priority; shrapnel inherits from the fuse which
    // inherited from the bomb body's priority of 3.
    private static final int RENDER_PRIORITY = 3;

    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private final SubpixelMotion.State motionState;
    private int animTickCounter;
    private boolean destroyed;
    // Non-final so the generic rewind field capturer records it like the other
    // scalar state; it is only read by skipsSameFrameUpdateAfterSpawn() at spawn
    // scheduling and is inert afterwards.
    private boolean deferFirstMove;

    /**
     * Creates a shrapnel piece at the given position with the given velocity.
     * Used by the rewind recreate path (no spawn-frame move deferral needed
     * because the object is reconstructed mid-flight, not freshly spawned).
     *
     * @param x          Spawn X position (from fuse/bomb position)
     * @param y          Spawn Y position (from original bomb Y)
     * @param xVel       Initial X velocity (from Bom_ShrSpeed)
     * @param yVel       Initial Y velocity (from Bom_ShrSpeed)
     */
    public Sonic1BombShrapnelInstance(int x, int y, int xVel, int yVel) {
        this(x, y, xVel, yVel, false);
    }

    /**
     * Creates a shrapnel piece, optionally deferring its first {@code SpeedToPos}
     * move by one frame.
     * <p>
     * ROM parity (docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:181-220):
     * {@code Bom_BurnFuseAndExplode} makes the FIRST shrapnel reuse the fuse's slot
     * (movea.l a0,a1) and falls straight through to {@code Bom_Shrapnel} —
     * {@code SpeedToPos} runs on the expiry frame, so piece 0 moves immediately. The
     * other three are created via {@code FindNextFreeObj} with a cleared
     * {@code obRoutine}, so on their creation frame they execute {@code Bom_Main}
     * (routine 0), which only sets {@code obRoutine = obSubtype} (= 6) and returns
     * WITHOUT calling SpeedToPos (Walking Bomb.asm:30-33). They first move on the
     * following frame. The engine spawns all four as ready-to-move shrapnel, so
     * pieces 1-3 moved one frame too early — putting the hitting piece one step
     * ahead (1px low) and missing the standing player's ReactToItem box at
     * SLZ3 trace f3249. Deferring pieces 1-3's first update reproduces the
     * Bom_Main creation-frame hold.
     *
     * @param deferFirstMove true for FindNextFreeObj-created pieces (index 1-3)
     */
    public Sonic1BombShrapnelInstance(int x, int y, int xVel, int yVel, boolean deferFirstMove) {
        super(new ObjectSpawn(x, y, 0x5F, 6, 0, false, 0), "BombShrapnel");

        this.currentX = x;
        this.currentY = y;
        this.xVelocity = xVel;
        this.yVelocity = yVel;
        this.motionState = new SubpixelMotion.State(x, y, 0, 0, xVel, yVel);
        this.animTickCounter = 0;
        this.destroyed = false;
        this.deferFirstMove = deferFirstMove;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        // Pieces 1-3 run Bom_Main (no move) on their creation frame; only the
        // fuse-slot-reuse first piece moves the same frame. See the deferring
        // constructor's Javadoc for the ROM citation.
        return deferFirstMove;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        // Bom_End:
        // bsr.w SpeedToPos
        // addi.w #$18,obVelY(a0) - gravity
        // AnimateSprite
        // tst.b obRender(a0) / bpl.w DeleteObject - delete if off-screen
        // bra.w DisplaySprite

        // SpeedToPos + gravity: apply velocity then add gravity
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentX = motionState.x;
        currentY = motionState.y;

        // Apply gravity
        yVelocity += GRAVITY;

        // ROM Bom_Shrapnel deletes via the render flag, not a raw camera margin:
        //   tst.b obRender(a0) / bpl.w DeleteObject
        // (docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:218-219). obRender
        // bit 7 is set by BuildSprites when the sprite's box overlaps the screen
        // (X bound [cam-obActWid, cam+320+obActWid), obActWid=12 for the shrapnel;
        // docs/s1disasm/_inc/BuildSprites.asm:47-58). The engine previously used
        // isOnScreenX(160) (raw [cam-160, cam+480]) which keeps shrapnel alive far
        // longer than ROM, changing the FindFreeObj survival set (SLZ3 f3249).
        // isWithinSolidContactBounds() is the ROM obRender bit-7 render-box test.
        if (!isWithinSolidContactBounds()) {
            destroyed = true;
            setDestroyed(true);
            return;
        }

        // Animate
        animTickCounter++;
    }

    /**
     * Returns the mapping frame index for shrapnel animation.
     * From Ani_Bomb .shrapnel: dc.b 3, $A, $B, afEnd
     */
    private int getMappingFrame() {
        int step = (animTickCounter / ANIM_SPEED) % SHRAPNEL_FRAMES.length;
        return SHRAPNEL_FRAMES[step];
    }

    // --- TouchResponseProvider ---

    @Override
    public int getCollisionFlags() {
        if (destroyed) {
            return 0;
        }
        // obColType = $98: HURT category ($80) + size index $18
        return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // --- Rendering ---

    @Override
    public boolean isPersistent() {
        // ROM Bom_Shrapnel keeps the shrapnel only while obRender bit 7 is set
        // (the render-box on-screen test, same bound as the self-delete above);
        // align the keep-alive with that bound, not the raw isOnScreenX(160).
        return !destroyed && isWithinSolidContactBounds();
    }

    /**
     * ROM shrapnel display width: {@code obActWid = 24/2 = 12} (inherited from
     * Bom_Main, docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:28). Drives
     * the BuildSprites obRender on-screen X bound used for the delete/keep-alive.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return 12;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(RENDER_PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BOMB);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // Shrapnel does not flip based on facing direction
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Red hitbox (shrapnel hurts Sonic)
        ctx.drawRect(currentX, currentY, 4, 4, 1f, 0f, 0f);

        // Velocity arrow
        if (xVelocity != 0 || yVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            int endY = currentY + (yVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, endY, 1f, 0.5f, 0f);
        }

        ctx.drawWorldLabel(currentX, currentY, -2, "Shrapnel", DebugColor.RED);
    }

    // --- Position accessors ---

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(currentX, currentY, 0x5F, 6, 0, false, 0);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }
}
