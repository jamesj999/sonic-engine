package com.openggf.game.sonic1.objects.badniks;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DestructionEffects;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Ball Hog (0x1E) - Cannon-wielding enemy from Scrap Brain Zone.
 * <p>
 * The Ball Hog stands on a platform and periodically launches cannonballs
 * from its hatch. The animation cycles through standing, squatting, and
 * leaping poses; when frame 1 (Open / hatch-open) is displayed, it spawns
 * a cannonball projectile.
 * <p>
 * Based on docs/s1disasm/_incObj/1E Ball Hog.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Hog_Main): Initialization - ObjectFall + ObjFloorDist until floor found</li>
 *   <li>2 (Hog_Action): Animate + spawn cannonballs on frame 1 + RememberState</li>
 * </ul>
 * <p>
 * Animation script (Ani_Hog):
 * <pre>
 * .hog: dc.b 9, 0, 0, 2, 2, 3, 2, 0, 0, 2, 2, 3, 2, 0, 0, 2, 2, 3, 2, 0, 0, 1, afEnd
 * </pre>
 * Single animation at speed 9 (10 frames per step), 22 frames total before looping.
 * <p>
 * The cannonball is spawned as a separate dynamic object ({@link Sonic1CannonballInstance}).
 * The Ball Hog copies its subtype to the cannonball, which uses it as an explosion timer
 * multiplier (subtype * 60 frames).
 */
public class Sonic1BallHogBadnikInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {

    // --- Collision ---
    // From disassembly: move.b #5,obColType(a0)
    // $00 = standard enemy category, $05 = size index
    private static final int COLLISION_SIZE_INDEX = 0x05;

    // --- Dimensions ---
    // From disassembly: move.b #$13,obHeight(a0), move.b #8,obWidth(a0)
    private static final int Y_RADIUS = 0x13;

    // --- Physics ---
    // From ObjectFall: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // --- Cannonball spawn offsets ---
    // From disassembly: moveq #-4,d0 (X offset), addi.w #$C,obY(a1) (Y offset)
    private static final int CANNONBALL_X_OFFSET = -4;
    private static final int CANNONBALL_Y_OFFSET = 0x0C;

    // From disassembly: move.w #-$100,obVelX(a1)
    private static final int CANNONBALL_X_VELOCITY = -0x100;

    // --- Render ---
    // From disassembly: move.b #4,obPriority(a0)
    private static final int RENDER_PRIORITY = 4;

    // --- Animation ---
    // ROM Ani_Hog speed byte (obTimeFrame reload value). Each step is held for
    // ANIM_SPEED_BYTE + 1 = 10 game frames via ROM AnimateSprite's "subq.b #1; bpl"
    // countdown cadence.
    // docs/s1disasm/_anim/Ball Hog.asm:6, docs/s1disasm/_incObj/sub AnimateSprite.asm:17-23
    private static final int ANIM_SPEED_BYTE = 9;
    // Frame sequence: 0, 0, 2, 2, 3, 2, 0, 0, 2, 2, 3, 2, 0, 0, 2, 2, 3, 2, 0, 0, 1
    private static final int[] ANIM_FRAMES = {
            0, 0, 2, 2, 3, 2, 0, 0, 2, 2,
            3, 2, 0, 0, 2, 2, 3, 2, 0, 0, 1
    };
    // Frame index 1 = Open (hatch open) is the cannonball spawn trigger
    private static final int OPEN_FRAME = 1;

    // --- Instance state ---
    private int currentX;
    private int currentY;
    private int yVelocity;
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private boolean facingLeft;
    private boolean initialized;
    private boolean destroyed;

    // Animation state, mirroring ROM AnimateSprite (docs/s1disasm/_incObj/sub AnimateSprite.asm):
    //   animTimeFrame   = obTimeFrame  (frame-duration countdown; starts at 0 so the first
    //                     Anim_Run call underflows immediately and loads frame[0])
    //   animScriptIndex = obAniFrame   (read-then-increment index into ANIM_FRAMES)
    //   displayedFrame  = obFrame      (currently displayed frame ID; ROM clears RAM to 0)
    private int animTimeFrame;
    private int animScriptIndex;
    private int displayedFrame;

    // hog_launchflag (objoff_32): 0 = ready to launch, nonzero = already launched this cycle
    private boolean launchFlag;

    public Sonic1BallHogBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "BallHog");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.yVelocity = 0;

        // obRender bit 2 = use obStatus for flipping
        // obStatus bit 0 determines facing: 0 = facing right, 1 = facing left
        this.facingLeft = (spawn.renderFlags() & 1) != 0;
        this.initialized = false;
        this.destroyed = false;
        this.animTimeFrame = 0;
        this.animScriptIndex = 0;
        this.displayedFrame = 0;
        this.launchFlag = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        if (!initialized) {
            initialize();
        } else {
            updateAction(vIntRunCount, player);
        }
    }

    /**
     * Routine 0: Hog_Main - ObjectFall + ObjFloorDist until floor is found.
     * <pre>
     * Hog_Main:
     *     bsr.w  ObjectFall
     *     jsr    (ObjFloorDist).l
     *     tst.w  d1
     *     bpl.s  .floornotfound
     *     add.w  d1,obY(a0)
     *     move.w #0,obVelY(a0)
     *     addq.b #2,obRoutine(a0)
     * </pre>
     */
    private void initialize() {
        // ROM ObjectFall (_incObj/sub ObjectFall & SpeedToPos.asm:8-23): Y moves with
        // the OLD y_vel, then gravity is added to y_vel for the next frame (one-frame
        // delayed gravity). The previous manual pre-increment applied gravity before
        // the move.
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = yVelocity;
        SubpixelMotion.objectFall(motion, GRAVITY);
        currentY = motion.y;
        yVelocity = (short) motion.yVel;

        // ObjFloorDist: find floor from feet (obY + obHeight)
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // tst.w d1 / bpl.s .floornotfound
        if (floorResult.foundSurface() && floorResult.distance() < 0) {
            currentY += floorResult.distance(); // add.w d1,obY(a0)
            yVelocity = 0;                       // move.w #0,obVelY(a0)
            initialized = true;                   // addq.b #2,obRoutine(a0)
        }
    }

    /**
     * Routine 2: Hog_Action - Animate sprite, check for cannonball spawn on frame 1.
     * <pre>
     * Hog_Action:
     *     lea    (Ani_Hog).l,a1
     *     bsr.w  AnimateSprite
     *     cmpi.b #1,obFrame(a0)        ; is Open frame displayed?
     *     bne.s  .setlaunchflag          ; if not, clear flag
     *     tst.b  hog_launchflag(a0)     ; ready to launch?
     *     beq.s  .makeball               ; if flag=0, spawn cannonball
     *     bra.s  .remember
     *
     * .setlaunchflag:
     *     clr.b  hog_launchflag(a0)     ; reset flag for next cycle
     *
     * .remember:
     *     bra.w  RememberState
     *
     * .makeball:
     *     move.b #1,hog_launchflag(a0)  ; mark as launched
     *     [spawn cannonball]
     * </pre>
     */
    private void updateAction(int vIntRunCount, AbstractPlayableSprite player) {
        // AnimateSprite: advance animation
        updateAnimation();

        int currentFrame = displayedFrame;

        if (currentFrame == OPEN_FRAME) {
            // cmpi.b #1,obFrame(a0) / bne.s .setlaunchflag
            if (!launchFlag) {
                // tst.b hog_launchflag(a0) / beq.s .makeball
                launchFlag = true;
                spawnCannonball();
            }
            // else: already launched this cycle, skip to RememberState
        } else {
            // .setlaunchflag: clr.b hog_launchflag(a0)
            launchFlag = false;
        }

        // RememberState is implicit (isPersistent handles on-screen check)
    }

    /**
     * Advance animation by one tick. The animation uses speed byte 9 (10 ticks per step).
     * After reaching the end of the frame sequence, it loops back to the start (afEnd).
     */
    private void updateAnimation() {
        // ROM AnimateSprite (docs/s1disasm/_incObj/sub AnimateSprite.asm:17-23,46,58-63):
        //   subq.b #1,obTimeFrame ; bpl.s Anim_Wait   -> only advance when it underflows.
        //   On advance: reload obTimeFrame with the script speed byte, read the frame ID at
        //   obAniFrame, then addq.b #1,obAniFrame. afEnd ($FF) wraps the index back to 0.
        // obTimeFrame starts at 0, so the first call underflows immediately and shows
        // frame[0]; every step (including the first) is therefore held for
        // ANIM_SPEED_BYTE + 1 = 10 game frames. The previous count-up logic held the first
        // step for only 9 frames, advancing the whole animation one frame early and making
        // the Ball Hog launch its cannonball a frame ahead of ROM (SBZ1 f6082 early hurt).
        animTimeFrame--;
        if (animTimeFrame >= 0) {
            return; // bpl.s Anim_Wait
        }
        animTimeFrame = ANIM_SPEED_BYTE; // move.b (a1),obTimeFrame
        if (animScriptIndex >= ANIM_FRAMES.length) {
            animScriptIndex = 0; // afEnd: restart the animation from the beginning
        }
        displayedFrame = ANIM_FRAMES[animScriptIndex]; // read frame ID at obAniFrame
        animScriptIndex++;                              // addq.b #1,obAniFrame
    }

    /**
     * Spawns a cannonball (Object $20) at the Ball Hog's position with offsets.
     * <pre>
     * .makeball:
     *     move.b #1,hog_launchflag(a0)
     *     bsr.w  FindFreeObj
     *     bne.s  .fail
     *     _move.b #id_Cannonball,obID(a1)
     *     move.w obX(a0),obX(a1)
     *     move.w obY(a0),obY(a1)
     *     move.w #-$100,obVelX(a1)       ; cannonball bounces to the left
     *     move.w #0,obVelY(a1)
     *     moveq  #-4,d0
     *     btst   #0,obStatus(a0)         ; is Ball Hog facing right?
     *     beq.s  .noflip                  ; if not, branch
     *     neg.w  d0
     *     neg.w  obVelX(a1)              ; cannonball bounces to the right
     *
     * .noflip:
     *     add.w  d0,obX(a1)
     *     addi.w #$C,obY(a1)
     *     move.b obSubtype(a0),obSubtype(a1)
     * </pre>
     */
    private void spawnCannonball() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }

        int ballXVel = CANNONBALL_X_VELOCITY;
        int xOffset = CANNONBALL_X_OFFSET;

        // btst #0,obStatus(a0) / beq.s .noflip
        // When obStatus bit 0 = 1 (X-flipped / facingLeft): negate offset and velocity
        // Default (bit 0 = 0): ball goes left at -$100 with offset -4
        // Flipped (bit 0 = 1): ball goes right at +$100 with offset +4
        if (facingLeft) {
            xOffset = -xOffset;     // neg.w d0
            ballXVel = -ballXVel;   // neg.w obVelX(a1)
        }

        final int ballX = currentX + xOffset;
        final int ballY = currentY + CANNONBALL_Y_OFFSET;
        final int subtype = spawn.subtype();
        final int ballXVelFinal = ballXVel;

        spawnFreeChild(() -> new Sonic1CannonballInstance(
                ballX, ballY, ballXVelFinal, subtype));
    }

    /**
     * Returns the current mapping frame index from the animation sequence.
     */
    private int getMappingFrame() {
        return displayedFrame;
    }

    // --- TouchResponseProvider / TouchResponseAttackable ---

    @Override
    public int getCollisionFlags() {
        if (destroyed || !initialized) {
            return 0;
        }
        // obColType = $05: standard enemy category ($00) + size index $05
        return COLLISION_SIZE_INDEX & 0x3F;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }
        destroyBadnik(player);
    }

    /**
     * Handles badnik destruction via the centralised DestructionEffects system.
     */
    private void destroyBadnik(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        destroyed = true;
        // ROM parity: explosion inherits our slot (in-place obID change).
        int mySlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        setDestroyed(true);
        DestructionEffects.destroyBadnik(currentX, currentY, spawn, mySlot,
                player, services(), Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG);
    }

    // --- Rendering ---

    @Override
    public boolean isPersistent() {
        // BHog_Display ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/1E, 20 Badnik - Ball Hog and Cannonball.asm:61
        // -> _incObj/sub RememberState.asm:9 -> Macros.asm:278-295). The symmetric
        // isOnScreenX(160) gate freed the SST slot too early on the right.
        return !destroyed && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        // obPriority = 4
        return RenderPriority.clamp(RENDER_PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BALL_HOG);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // ori.b #4,obRender(a0): bit 2 set = use obStatus for flipping
        // obStatus bit 0 = 1 = X flip (facing left), no bchg toggle in Ball Hog
        // hFlip parameter directly matches obStatus bit 0 = facingLeft
        renderer.drawFrameIndex(frame, currentX, currentY, facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle (collision size index $05)
        ctx.drawRect(currentX, currentY, 8, 0x13, 1f, 1f, 0f);

        // State label
        String state = initialized ? "ACTIVE" : "INIT";
        String dir = facingLeft ? "L" : "R";
        int frame = getMappingFrame();
        String label = "BallHog " + state + " f" + frame + " s" + animScriptIndex + " " + dir;
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
