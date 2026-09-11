package com.openggf.game.sonic1.objects.badniks;

import com.openggf.audio.AudioManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Bomb Enemy (0x5F) - Walking bomb badnik from Star Light Zone and Scrap Brain Zone.
 * <p>
 * The bomb stands still, periodically walks back and forth, and when Sonic gets close,
 * its fuse ignites and it eventually explodes into shrapnel pieces.
 * The bomb body HURTS Sonic on contact (obColType = $9A, category $80).
 * Shrapnel pieces also hurt Sonic (obColType = $98, category $80).
 * <p>
 * Based on docs/s1disasm/_incObj/5F Bomb Enemy.asm.
 * <p>
 * Main object state machine (ob2ndRout / 2):
 * <ul>
 *   <li>0 (.walk): Standing still, timer counts down. When timer expires, switches
 *       direction and starts walking. Continuously checks if Sonic is within $60px range.</li>
 *   <li>1 (.wait): Walking with obVelX. Timer counts down. When timer expires, stops
 *       and returns to .walk state. Continuously checks if Sonic is within $60px range.</li>
 *   <li>2 (.explode): Fuse lit, countdown to explosion. When timer expires, changes
 *       object to ExplosionBomb (object $3F). The explosion spawns independently.</li>
 * </ul>
 * <p>
 * Sub-objects spawned:
 * <ul>
 *   <li>Fuse (subtype 4): Spawned when Sonic detected. Moves with obVelY = $10 (or -$10 if
 *       upside-down). Renders fuse animation (frames 8-9). After timer expires, creates
 *       4 shrapnel pieces and deletes self.</li>
 *   <li>Shrapnel (subtype 6): 4 pieces with predefined velocities. Fall with gravity ($18/frame).
 *       Render shrapnel animation (frames 10-11). Deleted when off-screen.</li>
 * </ul>
 * <p>
 * Animations (Ani_Bomb):
 * <ul>
 *   <li>0 (.stand):     frames 1, 0 at speed $13 - standing idle</li>
 *   <li>1 (.walk):      frames 5, 4, 3, 2 at speed $13 - walking</li>
 *   <li>2 (.activated): frames 7, 6 at speed $13 - fuse lit, body flashing</li>
 *   <li>3 (.fuse):      frames 8, 9 at speed 3 - fuse sparking</li>
 *   <li>4 (.shrapnel):  frames $A, $B at speed 3 - shrapnel spinning</li>
 * </ul>
 */
public class Sonic1BombBadnikInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {

    // --- Collision ---
    // From disassembly: move.b #$9A,obColType(a0)
    // $80 = HURT category, $1A = size index (width $C, height $C)
    private static final int COLLISION_SIZE_INDEX = 0x1A;

    // --- Proximity ---
    // From disassembly: cmpi.w #$60,d0 (Sonic proximity check range)
    private static final int PROXIMITY_RANGE = 0x60;

    // --- Timers (in frames) ---
    // From disassembly: move.w #179,bom_time(a0) (standing still timer = 3 seconds)
    private static final int STAND_TIME = 179;
    // From disassembly: move.w #1535,bom_time(a0) (walk timer ~25 seconds)
    private static final int WALK_TIME = 1535;
    // From disassembly: move.w #143,bom_time(a0) (fuse countdown timer)
    private static final int FUSE_TIME = 143;

    // --- Velocities ---
    // From disassembly: move.w #$10,obVelX(a0) (walk speed)
    private static final int WALK_SPEED = 0x10;
    // From disassembly: move.w #$10,obVelY(a1) (fuse rise speed)
    private static final int FUSE_Y_SPEED = 0x10;
    // From disassembly: addi.w #$18,obVelY(a0) (shrapnel gravity)
    private static final int SHRAPNEL_GRAVITY = 0x18;

    // --- Shrapnel velocities from Bom_ShrSpeed ---
    // dc.w -$200, -$300, -$100, -$200, $200, -$300, $100, -$200
    private static final int[][] SHRAPNEL_VELOCITIES = {
            {-0x200, -0x300},
            {-0x100, -0x200},
            { 0x200, -0x300},
            { 0x100, -0x200}
    };

    // --- Animation ---
    // Animation IDs from Ani_Bomb
    private static final int ANIM_STAND = 0;
    private static final int ANIM_WALK = 1;
    private static final int ANIM_ACTIVATED = 2;
    private static final int ANIM_FUSE = 3;
    private static final int ANIM_SHRAPNEL = 4;

    // Animation speed $13 + 1 = 20 ticks per frame
    private static final int ANIM_SPEED_NORMAL = 0x13 + 1;
    // Animation speed 3 + 1 = 4 ticks per frame
    private static final int ANIM_SPEED_FAST = 3 + 1;

    // Stand animation: frames 1, 0
    private static final int[] STAND_FRAMES = {1, 0};
    // Walk animation: frames 5, 4, 3, 2
    private static final int[] WALK_FRAMES = {5, 4, 3, 2};
    // Activated animation: frames 7, 6
    private static final int[] ACTIVATED_FRAMES = {7, 6};
    // Fuse animation: frames 8, 9
    private static final int[] FUSE_FRAMES = {8, 9};
    // Shrapnel animation: frames 10, 11
    private static final int[] SHRAPNEL_FRAMES = {10, 11};

    // --- State machine values (ob2ndRout / 2) ---
    private static final int STATE_WALK = 0;
    private static final int STATE_WAIT = 1;
    private static final int STATE_EXPLODE = 2;

    // From disassembly: move.b #3,obPriority(a0)
    private static final int RENDER_PRIORITY = 3;

    // --- Instance state ---
    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private final SubpixelMotion.State motionState;
    private boolean facingLeft;
    private boolean ceilingBomb; // obStatus bit 1: bomb is upside-down on ceiling
    private boolean destroyed;

    private int state;          // ob2ndRout / 2
    private int timer;          // bom_time (objoff_30)
    private int currentAnim;    // obAnim
    private int animTickCounter;

    public Sonic1BombBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Bomb");

        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = 0;
        this.yVelocity = 0;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);

        // obStatus bit 0 is initialized from spawn data's render flags.
        // Bom_Main: bchg #0,obStatus(a0) toggles it.
        // facingLeft=true ↔ status bit 0=0; after bchg, new bit = !original.
        // So facingLeft = (new bit == 0) = (original bit == 1) = spawnBit0.
        boolean spawnBit0 = (spawn.renderFlags() & 1) != 0;
        this.facingLeft = spawnBit0;
        // obStatus bit 1: ceiling bomb (upside-down). Used for V-flip rendering
        // and negating fuse Y velocity. From: btst #1,obStatus(a0)
        this.ceilingBomb = (spawn.renderFlags() & 2) != 0;
        this.destroyed = false;

        int subtype = spawn.subtype();
        if (subtype == 0) {
            // Normal bomb body - set collision and start in walk state
            // move.b #$9A,obColType(a0)
            // bom_time starts at 0 from zero-init, so first frame of .walk
            // immediately transitions to .wait (timer -1 < 0).
            this.state = STATE_WALK;
            this.timer = 0;  // ROM: bom_time starts at 0 (zero-init)
            this.currentAnim = ANIM_STAND;
            this.animTickCounter = 0;
        }
        // Subtypes 4 (fuse) and 6 (shrapnel) are handled by dedicated child classes
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }
        updateMovement(vIntRunCount, player);
        updateAnimation(vIntRunCount);
    }

    /**
     * Bom_Action: Main update dispatches to state-specific handler, then
     * AnimateSprite + RememberState.
     */
    private void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case STATE_WALK -> updateWalk(vIntRunCount, player);
            case STATE_WAIT -> updateWait(vIntRunCount, player);
            case STATE_EXPLODE -> updateExplode(vIntRunCount);
        }
    }

    /**
     * State 0 (.walk): Standing still, counting down timer.
     * Checks if Sonic is within range. When timer expires, starts walking.
     * <pre>
     * .walk:
     *     bsr.w   .chksonic
     *     subq.w  #1,bom_time(a0)
     *     bpl.s   .noflip
     *     addq.b  #2,ob2ndRout(a0)
     *     move.w  #1535,bom_time(a0)
     *     move.w  #$10,obVelX(a0)
     *     move.b  #1,obAnim(a0)
     *     bchg    #0,obStatus(a0)
     *     beq.s   .noflip
     *     neg.w   obVelX(a0)
     * </pre>
     */
    private void updateWalk(int vIntRunCount, AbstractPlayableSprite player) {
        checkSonic(player);

        // ROM Bom_Action_Waiting decrements bom_time on the SAME frame, AFTER
        // Bom_CheckStartFuse returns (subq.w #1,bom_time(a0); docs/s1disasm/
        // _incObj/5F Badnik - Walking Bomb.asm:56-66). When the fuse just
        // started, bom_time was set to 143 and is immediately decremented to
        // 142; the bpl then returns (142 >= 0) without running the
        // advance-to-walking branch. Performing this tick before the
        // state-change early-return reproduces ROM's same-frame decrement so
        // the fuse expires at T+143, not T+144 (SBZ2 f1595: four bombs exploded
        // into id_Explosion one frame late, drifting OST free-slot cadence).
        timer--;
        if (state != STATE_WALK) {
            return; // chksonic transitioned to explode; bpl returns this frame
        }

        if (timer < 0) {
            // Timer expired: start walking
            state = STATE_WAIT;
            timer = WALK_TIME;
            xVelocity = WALK_SPEED;
            setAnimation(ANIM_WALK);

            // bchg #0,obStatus(a0): toggle facing direction
            facingLeft = !facingLeft;
            // beq.s .noflip: branches when bit 0 is clear (facing right)
            // neg.w obVelX(a0): negate velocity when bit 0 is set (facing left)
            if (facingLeft) {
                xVelocity = -xVelocity;
            }
        }
    }

    /**
     * State 1 (.wait): Walking, applying velocity, counting down timer.
     * Checks if Sonic is within range. When timer expires, stops walking.
     * <pre>
     * .wait:
     *     bsr.w   .chksonic
     *     subq.w  #1,bom_time(a0)
     *     bmi.s   .stopwalking
     *     bsr.w   SpeedToPos
     *     rts
     *
     * .stopwalking:
     *     subq.b  #2,ob2ndRout(a0)
     *     move.w  #179,bom_time(a0)
     *     clr.w   obVelX(a0)
     *     move.b  #0,obAnim(a0)
     * </pre>
     */
    private void updateWait(int vIntRunCount, AbstractPlayableSprite player) {
        checkSonic(player);

        // ROM Bom_Action_Walking likewise decrements bom_time on the SAME frame
        // after Bom_CheckStartFuse (subq.w #1,bom_time(a0); docs/s1disasm/
        // _incObj/5F Badnik - Walking Bomb.asm:69-79). When the fuse just
        // started, bom_time (143 -> 142) does not go minus, so bmi is not taken
        // and the routine falls through to SpeedToPos with obVelX already
        // cleared (a no-op). Decrement before the state-change early-return to
        // match ROM's fuse start tick (see updateWalk).
        timer--;
        if (state != STATE_WAIT) {
            return; // chksonic transitioned to explode; bmi not taken this frame
        }

        if (timer < 0) {
            // Timer expired: stop walking, return to stand state
            state = STATE_WALK;
            timer = STAND_TIME;
            xVelocity = 0;
            setAnimation(ANIM_STAND);
        } else {
            // SpeedToPos: apply velocity
            applyVelocity();
        }
    }

    /**
     * State 2 (.explode): Fuse lit, counting down to explosion.
     * <pre>
     * .explode:
     *     subq.w  #1,bom_time(a0)
     *     bpl.s   .noexplode
     *     _move.b #id_ExplosionBomb,obID(a0)
     *     move.b  #0,obRoutine(a0)
     * </pre>
     */
    private void updateExplode(int vIntRunCount) {
        timer--;
        if (timer < 0) {
            // Timer expired: change the bomb into an explosion (object $3F).
            // ROM Bom_Action_WaitAndExplode does this IN PLACE via
            // _move.b #id_Explosion,obID(a0) (docs/s1disasm/_incObj/5F Badnik -
            // Walking Bomb.asm:93-94), keeping the bomb's SST slot. Detach the
            // slot so removing the bomb does not free it, then place the
            // explosion at that same slot (mirrors the badnik-kill in-place
            // obID change, DestructionEffects/AbstractBadnikInstance.destroyBadnik).
            // Spawning the explosion at the lowest free slot instead shifted OST
            // occupancy and cascaded later FindFreeObj allocations (SBZ2 f1596).
            int mySlot = ObjectLifetimeOps.detachSlotForTransfer(this);
            spawnBombExplosion(mySlot);
            destroyed = true;
            setDestroyed(true);
            var objectManager = services().objectManager();
            if (objectManager != null) {
                if (spawn.respawnTracked()) {
                    ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
                } else {
                    ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
                }
            }
        }
    }

    /**
     * .chksonic: Check if Sonic is within $60 pixels in both X and Y.
     * If so, transition to explode state and spawn a fuse child.
     * <pre>
     * .chksonic:
     *     move.w  (v_player+obX).w,d0
     *     sub.w   obX(a0),d0
     *     bcc.s   .isleft
     *     neg.w   d0
     * .isleft:
     *     cmpi.w  #$60,d0
     *     bhs.s   .outofrange
     *     move.w  (v_player+obY).w,d0
     *     sub.w   obY(a0),d0
     *     bcc.s   .isabove
     *     neg.w   d0
     * .isabove:
     *     cmpi.w  #$60,d0
     *     bhs.s   .outofrange
     *     tst.w   (v_debuguse).w
     *     bne.s   .outofrange
     *     [activate bomb, spawn fuse]
     * </pre>
     */
    private void checkSonic(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int dx = player.getCentreX() - currentX;
        if (dx < 0) dx = -dx;
        if (dx >= PROXIMITY_RANGE) {
            return;
        }

        int dy = player.getCentreY() - currentY;
        if (dy < 0) dy = -dy;
        if (dy >= PROXIMITY_RANGE) {
            return;
        }

        // tst.w (v_debuguse).w / bne.s .outofrange
        if (player.isDebugMode()) {
            return;
        }

        // Activate: transition to explode state
        state = STATE_EXPLODE;
        timer = FUSE_TIME;
        xVelocity = 0;
        setAnimation(ANIM_ACTIVATED);

        // Spawn fuse child object
        spawnFuseChild(player);
    }

    /**
     * Spawns the fuse child object (subtype 4) at the bomb's position.
     * <pre>
     *     bsr.w   FindNextFreeObj
     *     bne.s   .outofrange
     *     _move.b #id_Bomb,obID(a1)
     *     move.w  obX(a0),obX(a1)
     *     move.w  obY(a0),obY(a1)
     *     move.w  obY(a0),bom_origY(a1)
     *     move.b  obStatus(a0),obStatus(a1)
     *     move.b  #4,obSubtype(a1)
     *     move.b  #3,obAnim(a1)
     *     move.w  #$10,obVelY(a1)
     *     btst    #1,obStatus(a0)
     *     beq.s   .normal
     *     neg.w   obVelY(a1)
     * .normal:
     *     move.w  #143,bom_time(a1)
     *     move.l  a0,bom_parent(a1)
     * </pre>
     */
    private void spawnFuseChild(AbstractPlayableSprite player) {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        // btst #1,obStatus(a0) / beq.s .normal / neg.w obVelY(a1)
        final int fuseYSpeed = ceilingBomb ? -FUSE_Y_SPEED : FUSE_Y_SPEED;
        // ROM: FindNextFreeObj allocates the slot AFTER the bomb. When object RAM
        // is full past the bomb, ROM's `bsr.w FindNextFreeObj / bne.s .return`
        // (Walking Bomb.asm:111-112) leaves the body activated to explode but
        // does NOT create the fuse. Allocate up front so a failure skips the
        // fuse rather than letting spawnFreeChild fall back to a lowest-free
        // FindFreeObj slot (which would fabricate a fuse the ROM never made and
        // drift OST cadence).
        final int mySlot = getSlotIndex();
        final int fuseSlot = ObjectLifetimeOps.reserveFindNextFreeChildSlot(objectManager, mySlot);
        if (fuseSlot < 0) {
            return; // bne.s .return: object RAM full after bomb, no fuse spawned
        }
        spawnFreeChild(() -> {
            Sonic1BombFuseInstance fuse = new Sonic1BombFuseInstance(
                    currentX, currentY, facingLeft, ceilingBomb, FUSE_TIME, fuseYSpeed, this);
            fuse.setSlotIndex(fuseSlot);
            return fuse;
        });
    }

    /**
     * Spawns a bomb explosion (object $3F = ExplosionBomb).
     * From ExBom_Main: uses Map_ExplodeBomb, ArtTile_Explosion, plays sfx_Bomb (0xC4).
     * The explosion reuses the standard ExItem_Animate with 5 frames at 7 ticks each.
     */
    private void spawnBombExplosion(int transferredSlot) {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        // Place the explosion into the bomb's just-vacated slot (in-place obID
        // change parity). Constructed directly (no spawnFreeChild) like the
        // shared badnik-kill path in DestructionEffects, which also uses
        // addReplacementAtTransferredSlot; ExplosionObjectInstance's constructor
        // does not require the spawn ConstructionContext.
        ExplosionObjectInstance explosion = new ExplosionObjectInstance(
                0x3F, currentX, currentY, services().renderManager());
        ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager, explosion, transferredSlot);

        // sfx_Bomb = $C4 = BOSS_EXPLOSION
        services().playSfx(Sonic1Sfx.BOSS_EXPLOSION.id);
    }

    /**
     * Spawns 4 shrapnel pieces with predefined velocities from Bom_ShrSpeed.
     * Called by the fuse child when its timer expires.
     * <pre>
     *     moveq   #3,d1
     *     movea.l a0,a1
     *     lea     (Bom_ShrSpeed).l,a2
     *     bra.s   .makeshrapnel
     * .loop:
     *     bsr.w   FindNextFreeObj
     *     bne.s   .fail
     * .makeshrapnel:
     *     _move.b #id_Bomb,obID(a1)
     *     move.w  obX(a0),obX(a1)
     *     move.w  obY(a0),obY(a1)
     *     move.b  #6,obSubtype(a1)
     *     move.b  #4,obAnim(a1)
     *     move.w  (a2)+,obVelX(a1)
     *     move.w  (a2)+,obVelY(a1)
     *     move.b  #$98,obColType(a1)
     *     bset    #7,obRender(a1)
     * </pre>
     */
    void spawnShrapnel(int fuseX, int fuseY, int fuseSlot) {
        final ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        for (int i = 0; i < SHRAPNEL_VELOCITIES.length; i++) {
            final int vx = SHRAPNEL_VELOCITIES[i][0];
            final int vy = SHRAPNEL_VELOCITIES[i][1];
            // ROM: the first shrapnel reuses the fuse slot and runs Bom_Shrapnel
            // (SpeedToPos) on the expiry frame, so it moves immediately. Pieces
            // 1-3 are FindNextFreeObj-allocated with obRoutine cleared, so they
            // run Bom_Main (no move) on their creation frame and first move the
            // following frame (docs/s1disasm/_incObj/5F Badnik - Walking
            // Bomb.asm:181-220, 30-33). Defer pieces 1-3's first move to match.
            final boolean deferFirstMove = i != 0;
            final boolean firstPiece = i == 0;
            // Slot allocation (Walking Bomb.asm:181-203): the loop keeps a0 = the
            // fuse, so the first shrapnel reuses the fuse's slot (movea.l a0,a1)
            // and pieces 1-3 each take FindNextFreeObj's first empty slot AFTER
            // the fuse (already-allocated pieces are skipped, so the slots climb).
            // Using lowest-free FindFreeObj instead put the shrapnel below the
            // high-slot fuse, drifting OST occupancy and cascading later
            // FindFreeObj allocations (SBZ2 f1596 -> the f2306 conveyor slot).
            //
            // ROM .loopShrapnel runs `bsr.w FindNextFreeObj / bne.s .nextShrapnel`
            // (docs/s1disasm/_incObj/sub FindFreeObj.asm:32-50, Walking Bomb.asm:
            // 191-192): when no slot is free AFTER the fuse (object RAM full past
            // the parent), the shrapnel is NOT created — the dbf just moves on.
            // Allocate the slot up front so a FindNextFreeObj failure SKIPS the
            // piece, instead of letting spawnFreeChild fall back to a lowest-free
            // FindFreeObj slot. The fallback fabricated shrapnel the ROM never
            // made (SBZ2 f1596: when four fuses expired the same frame but only
            // six high slots were free, ROM made shrapnel for the first two
            // fuses and skipped the rest; the engine packed the overflow into low
            // free slots, drifting OST cadence into the f6839 bomb-slot error).
            final int assignedSlot;
            if (firstPiece) {
                assignedSlot = fuseSlot;            // movea.l a0,a1: reuse fuse slot
            } else if (fuseSlot >= 0) {
                assignedSlot = ObjectLifetimeOps.reserveFindNextFreeChildSlot(
                        objectManager, fuseSlot); // FindNextFreeObj
                if (assignedSlot < 0) {
                    continue;                        // bne.s .nextShrapnel: skip this piece
                }
            } else {
                assignedSlot = -1;
            }
            spawnFreeChild(() -> {
                Sonic1BombShrapnelInstance shrapnel = new Sonic1BombShrapnelInstance(
                        fuseX, fuseY, vx, vy, deferFirstMove);
                if (assignedSlot >= 0) {
                    shrapnel.setSlotIndex(assignedSlot);
                }
                return shrapnel;
            });
        }
    }

    private void setAnimation(int newAnim) {
        if (newAnim != currentAnim) {
            currentAnim = newAnim;
            animTickCounter = 0;
        }
    }

    private void updateAnimation(int vIntRunCount) {
        animTickCounter++;
    }

    /**
     * SpeedToPos: Apply X and Y velocity to position with subpixel precision.
     */
    private void applyVelocity() {
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentX = motionState.x;
        currentY = motionState.y;
    }

    /**
     * Returns the mapping frame index based on current animation state.
     * From Ani_Bomb:
     * <pre>
     * .stand:     dc.b $13, 1, 0, afEnd
     * .walk:      dc.b $13, 5, 4, 3, 2, afEnd
     * .activated: dc.b $13, 7, 6, afEnd
     * .fuse:      dc.b 3, 8, 9, afEnd
     * .shrapnel:  dc.b 3, $A, $B, afEnd
     * </pre>
     */
    private int getMappingFrame() {
        int[] frames;
        int speed;
        switch (currentAnim) {
            case ANIM_STAND -> { frames = STAND_FRAMES; speed = ANIM_SPEED_NORMAL; }
            case ANIM_WALK -> { frames = WALK_FRAMES; speed = ANIM_SPEED_NORMAL; }
            case ANIM_ACTIVATED -> { frames = ACTIVATED_FRAMES; speed = ANIM_SPEED_NORMAL; }
            case ANIM_FUSE -> { frames = FUSE_FRAMES; speed = ANIM_SPEED_FAST; }
            case ANIM_SHRAPNEL -> { frames = SHRAPNEL_FRAMES; speed = ANIM_SPEED_FAST; }
            default -> { return 0; }
        }
        int step = (animTickCounter / speed) % frames.length;
        return frames[step];
    }

    // --- TouchResponseProvider / TouchResponseAttackable ---

    @Override
    public int getCollisionFlags() {
        if (destroyed) {
            return 0;
        }
        // obColType = $9A: HURT category ($80) + size index $1A
        return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // The bomb has HURT category ($80), so this should not normally be called.
        // However if the player is invincible, the bomb could be destroyed.
        // In ROM, React_ChkHurt returns without destroying the object when invincible.
        // The bomb only dies through its own explosion timer.
    }

    // --- Rendering ---

    @Override
    public boolean isPersistent() {
        // Bom_Action ends in bra.w RememberState (docs/s1disasm/_incObj/5F Badnik -
        // Walking Bomb.asm:49), whose off-screen test is the out_of_range macro
        // (Macros.asm:273-289): chunk-align obX and (v_screenposx-128) and delete
        // when the unsigned distance exceeds 640. Use isInRange() (the exact macro)
        // for the body, matching the fuse (which also ends in RememberState). The
        // approximate symmetric isOnScreenX(160) kept the walking body alive up to
        // 160px past the right edge that the ROM had already deleted at the 640px
        // window edge (BizHawk s1-complete-run: the SBZ2 x=0x12C0/0x12F0 bodies
        // delete at trace f2931 when chunk-aligned obX - screen exceeds 640).
        return !destroyed && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        // obPriority = 3
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
        // ori.b #4,obRender(a0): bit 2 set = use obStatus for flipping
        // obStatus bit 0 = X flip, obStatus bit 1 = Y flip (ceiling bomb)
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, ceilingBomb);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle (collision size $1A: width $C, height $C)
        ctx.drawRect(currentX, currentY, 12, 12, 1f, 1f, 0f);

        // Cyan velocity arrow if walking
        if (xVelocity != 0 || yVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            int endY = currentY + (yVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, endY, 0f, 1f, 1f);
        }

        // State label
        String stateStr = switch (state) {
            case STATE_WALK -> "STAND";
            case STATE_WAIT -> "WALK";
            case STATE_EXPLODE -> "FUSE";
            default -> "?";
        };
        String dir = facingLeft ? "L" : "R";
        String label = "Bomb " + stateStr + " t" + timer + " f" + getMappingFrame() + " " + dir;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);
    }

    // --- Position accessors ---

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
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
