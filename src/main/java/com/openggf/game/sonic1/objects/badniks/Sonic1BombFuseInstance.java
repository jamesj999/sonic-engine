package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Bomb Fuse child object (subtype 4) - The sparking fuse that rises from the bomb's body.
 * <p>
 * Based on docs/s1disasm/_incObj/5F Bomb Enemy.asm, Bom_Display routine (routine 4).
 * <p>
 * The fuse moves with obVelY = $10 (rising up, or -$10 if upside-down via btst #1,obStatus).
 * After bom_time expires, it creates 4 shrapnel pieces and resets itself (which in ROM
 * means the object is recycled; in our engine we just destroy it).
 * <p>
 * Animations: Uses fuse animation (Ani_Bomb index 3): frames 8, 9 at speed 3.
 */
public class Sonic1BombFuseInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Animation speed 3 + 1 = 4 ticks per frame
    private static final int ANIM_SPEED = 3 + 1;

    // Fuse mapping frames (from Ani_Bomb .fuse: 8, 9)
    private static final int[] FUSE_FRAMES = {8, 9};

    // From disassembly: move.b #3,obPriority(a0)
    private static final int RENDER_PRIORITY = 3;
    private final Sonic1BombBadnikInstance parent;
    private int currentX;
    private int currentY;
    private int origY;              // bom_origY (objoff_34): original Y position
    private int yVelocity;
    private final SubpixelMotion.State motionState;
    private int timer;              // bom_time
    private int animTickCounter;
    private boolean facingLeft;
    private boolean ceilingBomb;    // obStatus bit 1: parent is upside-down
    private boolean destroyed;

    Sonic1BombFuseInstance() {
        this(0, 0, false, false, 0, 0, null);
    }

    /**
     * Creates a fuse child at the bomb's position.
     *
     * @param x             Bomb X position
     * @param y             Bomb Y position
     * @param facingLeft    Bomb's facing direction (status bit 0)
     * @param ceilingBomb   Whether parent is upside-down (status bit 1)
     * @param fuseTime      Countdown timer (143 frames)
     * @param fuseYSpeed    Vertical speed ($10 or -$10 for upside-down)
     * @param parent        Parent bomb instance for shrapnel spawning
     * @param levelManager  Level manager reference
     */
    public Sonic1BombFuseInstance(int x, int y, boolean facingLeft, boolean ceilingBomb,
                                  int fuseTime, int fuseYSpeed,
                                  Sonic1BombBadnikInstance parent) {
        super(new ObjectSpawn(x, y, 0x5F, 4, 0, false, 0), "BombFuse");
        
        this.parent = parent;
        this.currentX = x;
        this.currentY = y;
        this.origY = y;
        this.yVelocity = fuseYSpeed;
        this.motionState = new SubpixelMotion.State(x, y, 0, 0, 0, fuseYSpeed);
        this.timer = fuseTime;
        this.facingLeft = facingLeft;
        this.ceilingBomb = ceilingBomb;
        this.animTickCounter = 0;
        this.destroyed = false;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        // ROM parity: Bom_CheckStartFuse (docs/s1disasm/_incObj/5F Badnik - Walking
        // Bomb.asm) creates the fuse via FindNextFreeObj and sets bom_time = 143,
        // but the fuse's Bom_Fuse routine (which runs Bom_BurnFuseAndExplode ->
        // subq.w #1,bom_time) does NOT decrement on the creation frame — BizHawk
        // shows the fuse holding bom_time=143 at the end of the frame it appears
        // (SLZ1 bk2 f137203), then counting down 142,141,... on subsequent frames,
        // expiring when 0 -> -1 at f137347. Letting the engine fuse decrement on
        // its spawn frame expired it one frame early, which spawned the shrapnel
        // one frame early and landed the shrapnel hurt at SLZ1 trace f723 instead
        // of ROM's f724 (the whole 38-frame shrapnel flight was shifted back one
        // frame). Deferring the fuse's first update reproduces the ROM creation-
        // frame hold.
        return true;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn capturedSpawn = ctx.spawn();
        Sonic1BombBadnikInstance restoredParent = nearestLiveBombParentForRewind(ctx);
        if (capturedSpawn == null || restoredParent == null) {
            return null;
        }
        // Scalars are restored after recreate.
        return new Sonic1BombFuseInstance(
                capturedSpawn.x(), capturedSpawn.y(), false, false, 0, 0, restoredParent);
    }

    private static Sonic1BombBadnikInstance nearestLiveBombParentForRewind(RewindRecreateContext ctx) {
        ObjectServices services = ctx.objectServices();
        ObjectManager objectManager = services != null ? services.objectManager() : null;
        ObjectSpawn capturedSpawn = ctx.spawn();
        if (objectManager == null || capturedSpawn == null) {
            return null;
        }
        Sonic1BombBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof Sonic1BombBadnikInstance bomb) || bomb.isDestroyed()) {
                continue;
            }
            long dx = bomb.getX() - capturedSpawn.x();
            long dy = bomb.getY() - capturedSpawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = bomb;
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        // loc_11B70: countdown and move
        timer--;
        if (timer < 0) {
            // loc_11B7C: Timer expired - spawn shrapnel and destroy
            // In ROM: clears bom_time, resets obRoutine to 0, restores origY,
            // then creates 4 shrapnel at current position. The fuse object (a0)
            // BECOMES the first shrapnel in place (movea.l a0,a1), so its SST slot
            // is reused for shrapnel #0; pieces 1-3 are FindNextFreeObj-allocated
            // after the fuse slot (docs/s1disasm/_incObj/5F Badnik - Walking
            // Bomb.asm:181-205). Detach the fuse slot here so removing the fuse
            // does not free it; spawnShrapnel hands it to shrapnel #0.
            currentY = origY;
            int fuseSlot = ObjectLifetimeOps.detachSlotForTransfer(this);
            parent.spawnShrapnel(currentX, currentY, fuseSlot);

            // Destroy the fuse
            destroyed = true;
            setDestroyed(true);
            return;
        }

        // SpeedToPos: apply Y velocity
        motionState.y = currentY;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentY = motionState.y;

        // Animate
        animTickCounter++;
    }

    /**
     * Returns the mapping frame index for fuse animation.
     * From Ani_Bomb .fuse: dc.b 3, 8, 9, afEnd
     */
    private int getMappingFrame() {
        int step = (animTickCounter / ANIM_SPEED) % FUSE_FRAMES.length;
        return FUSE_FRAMES[step];
    }

    @Override
    public boolean isPersistent() {
        // Bom_Fuse ends in RememberState (docs/s1disasm/_incObj/5F Badnik -
        // Walking Bomb.asm:158), whose off-screen test is the out_of_range macro
        // (Macros.asm:273-289): chunk-align obX and (v_screenposx-128) and delete
        // when the unsigned distance exceeds 640. The fuse moves only vertically,
        // so getX() (= spawn obX) is the live obX the macro reads. Using the
        // approximate symmetric isOnScreenX(160) kept fuses ~160px left of the
        // viewport that the ROM had already deleted off the left edge — those
        // lingering fuses then expired and spawned phantom shrapnel the ROM never
        // created (SBZ2 f1596: extra shrapnel at x=0x09B9/0x09E9).
        return !destroyed && isInRange();
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
        // obStatus bit 1 inherited from parent: V-flip for ceiling bombs
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, ceilingBomb);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, 4, 8, 1f, 0.5f, 0f);
        String label = "Fuse t" + timer;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.ORANGE);
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
