package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * HTZ Zipline Lift (Object 0x16) - diagonal moving platform from Hill Top Zone.
 * <p>
 * A platform that waits idle until the player stands on it, then slides diagonally
 * for a duration based on subtype. After sliding, the platform section falls off
 * and the rope remains.
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 47326-47449.
 * <p>
 * <b>Subtype:</b> Duration in 8-frame units.
 * Duration = subtype * 8 frames. E.g., subtype 0x14 = 160 frames (~2.67 seconds).
 * <p>
 * <b>State machine:</b>
 * <ul>
 *   <li>0 = Wait: Platform waits until player stands on it</li>
 *   <li>1 = Slide: Moves diagonally, plays click sound every 16 frames</li>
 *   <li>2 = Fall: Platform section falls off, rope remains</li>
 * </ul>
 * <p>
 * <b>Velocities:</b>
 * <ul>
 *   <li>X velocity: ±0x200 (based on x_flip flag)</li>
 *   <li>Y velocity: +0x100 (always downward)</li>
 *   <li>Fall gravity: +0x38 per frame</li>
 * </ul>
 */
public class HTZLiftObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(HTZLiftObjectInstance.class.getName());

    // State constants
    private static final int STATE_WAIT = 0;
    private static final int STATE_SLIDE = 1;
    private static final int STATE_FALL = 2;

    // Physics constants from ROM
    private static final int X_VEL = 0x200;         // 2.0 pixels/frame (8.8 fixed-point)
    private static final int Y_VEL = 0x100;         // 1.0 pixels/frame
    private static final int FALL_GRAVITY = 0x38;   // 0.21875 pixels/frame^2

    // Collision params - adjusted for platform standing detection.
    // S2 Obj16 passes d3 = -$28 to PlatformObject, so the platform surface is
    // y_pos - d3 = objectY + $28 (docs/s2disasm/s2.asm:47384-47388).
    private static final int COLLISION_WIDTH = 0x20;    // 32 pixels half-width
    private static final int COLLISION_Y_RADIUS = 0x10; // 16 pixels
    private static final int COLLISION_Y_OFFSET = 0x38; // 0x28 + 0x10 = center offset for 40px standing surface

    // Render priority from ROM (priority = 1)
    private static final int PRIORITY = 1;

    // Platform offset from center (d3 = #-$28 in JmpTo3_PlatformObject)
    private static final int PLATFORM_Y_OFFSET = -0x28; // -40 pixels

    // Position state (8.8 fixed-point for sub-pixel accuracy)
    private int baseX;
    private int baseY;
    private int xFixed;     // 8.8 fixed-point X position
    private int yFixed;     // 8.8 fixed-point Y position

    // Velocity (8.8 fixed-point)
    private int xVel;
    private int yVel;

    // State machine
    private int routineSecondary;   // 0=Wait, 1=Slide, 2=Fall
    private int slideTimer;         // Countdown timer for slide duration
    private int mappingFrame;       // 0=main, 2=rope-only

    // Flip flag
    private boolean flippedX;

    // Track if scenery spawned
    private boolean scenerySpawned;

    public HTZLiftObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.xFixed = spawn.x() << 8;
        this.yFixed = spawn.y() << 8;
        this.flippedX = (spawn.renderFlags() & 0x1) != 0;

        // Calculate slide duration: subtype * 8 frames
        int subtype = spawn.subtype() & 0xFF;
        this.slideTimer = subtype << 3; // subtype * 8

        // Initialize state
        this.routineSecondary = STATE_WAIT;
        this.mappingFrame = 0;
        this.xVel = 0;
        this.yVel = 0;
        this.scenerySpawned = false;

        updateDynamicSpawn(xFixed >> 8, yFixed >> 8);

        LOGGER.fine(() -> String.format(
                "HTZLift init: pos=(%d,%d), subtype=0x%02X, duration=%d frames, flipped=%b",
                baseX, baseY, subtype, slideTimer, flippedX));
    }

    @Override
    public HTZLiftObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HTZLiftObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return xFixed >> 8;
    }

    @Override
    public int getY() {
        return yFixed >> 8;
    }

    @Override
    public int getBalanceWidthPixels() {
        return COLLISION_WIDTH;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (routineSecondary) {
            case STATE_WAIT -> updateWait();
            case STATE_SLIDE -> updateSlide(vIntRunCount);
            case STATE_FALL -> updateFall(player);
        }

        updateDynamicSpawn(xFixed >> 8, yFixed >> 8);
    }

    /**
     * Wait state: Check if player is standing on the platform.
     * ROM: Obj16_Wait (docs/s2disasm/s2.asm:47405-47417)
     */
    private void updateWait() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null && objectManager.isAnyPlayerRiding(this)) {
            startSlide();
        }
    }

    private void startSlide() {
        if (routineSecondary != STATE_WAIT) {
            return;
        }

        // ROM Obj16_Main runs Obj16_Wait before PlatformObject, so this reads
        // the status standing bits persisted by the previous frame's contact pass.
        // Obj16_Wait only arms the secondary routine and velocities.
        // ObjectMove runs on the following frame after Obj16_Slide is entered.
        routineSecondary = STATE_SLIDE;
        xVel = flippedX ? -X_VEL : X_VEL;
        yVel = Y_VEL;

        LOGGER.fine("HTZLift: Player standing, starting slide");
    }

    /**
     * Slide state: Move diagonally and play click sound every 16 frames.
     * ROM: Obj16_Slide (docs/s2disasm/s2.asm:47420-47433)
     */
    private void updateSlide(int vIntRunCount) {
        // Play click sound every 16 frames
        // ROM: andi.w #$F,d0 / bne.s + / move.w #SndID_HTZLiftClick,d0
        if ((vIntRunCount & 0x0F) == 0) {
            services().playSfx(Sonic2Sfx.HTZ_LIFT_CLICK.id);
        }

        // Apply velocity (ObjectMove equivalent)
        xFixed += xVel;
        yFixed += yVel;

        // Decrement timer
        slideTimer--;
        if (slideTimer <= 0) {
            // Transition to fall state
            routineSecondary = STATE_FALL;
            mappingFrame = 2;  // Switch to rope-only frame
            xVel = 0;
            yVel = 0;

            // Spawn scenery marker (BridgeStake subtype 6)
            spawnScenery();
        }
    }

    /**
     * Fall state: Apply gravity and eject player when off-screen.
     * ROM: Obj16_Fall (docs/s2disasm/s2.asm:47444-47466)
     *
     * ROM order: ObjectMove (with current y_vel) FIRST, then add gravity.
     * The first FALL frame therefore moves the lift by 0 (y_vel was just
     * reset to 0 by the SLIDE -> FALL transition) and only sets y_vel to
     * 0x38 for the next frame. Applying gravity before the move (engine
     * ordering prior to this fix) advanced the lift's integer Y position
     * one frame earlier than ROM, causing HTZ trace y/camera_y divergence
     * at f311 once the prior isSolidFor() bug was fixed.
     */
    private void updateFall(AbstractPlayableSprite player) {
        // ROM step 1: ObjectMove(y_vel) — move with the CURRENT y_vel.
        yFixed += yVel;

        // ROM step 2: addi.w #$38,y_vel(a0) — add gravity AFTER move.
        yVel += FALL_GRAVITY;

        // Check if fallen off bottom of screen
        Camera camera = services().camera();
        int screenBottom = camera.getMaxY() + 224;
        int currentY = yFixed >> 8;

        if (currentY > screenBottom) {
            // Eject any standing player and destroy self
            // ROM: move.w #$4000,x_pos(a0) effectively removes the object
            setDestroyed(true);
        }
    }

    /**
     * Spawn a scenery marker (BridgeStake) at current position.
     * ROM: move.b #ObjID_Scenery,id(a1) / move.b #6,subtype(a1)
     */
    private void spawnScenery() {
        if (scenerySpawned) {
            return;
        }
        scenerySpawned = true;

        int currentX = xFixed >> 8;
        int currentY = yFixed >> 8;

        // ROM Obj16_Slide uses AllocateObjectAfterCurrent before creating Obj1C
        // (docs/s2disasm/s2.asm:47827-47833), so the scenery must not steal the
        // lowest free SST slot.
        ObjectSpawn scenerySpawn = new ObjectSpawn(
                currentX,
                currentY,
                0x1C,  // ObjID_Scenery / BridgeStake
                6,     // subtype 6 for HTZ zipline stake
                spawn.renderFlags(),
                false,
                0);

        spawnChild(() -> new BridgeStakeObjectInstance(scenerySpawn, "BridgeStake"));

        LOGGER.fine(() -> String.format("HTZLift spawned scenery at (%d,%d)", currentX, currentY));
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Obj16_Wait reads the persisted standing bit during the object's next
        // update. Starting slide from the contact callback moves one frame early.
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(
                COLLISION_WIDTH,
                COLLISION_Y_RADIUS,
                COLLISION_Y_RADIUS,
                0,                    // offsetX
                COLLISION_Y_OFFSET);  // offsetY - move collision down to platform
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;  // Platform is only solid from the top
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM parity: Obj16_Main (docs/s2disasm/s2.asm:47381-47389) calls
        // Obj16_RunSecondaryRoutine (WAIT/SLIDE/FALL) THEN unconditionally calls
        // PlatformObject. Obj16_Fall (s2.asm:47444-47466) only clears the
        // standing bit when the lift has fallen below the camera + screen_height
        // (the bhs.s +++ at line 47450 skips the clear while the lift is on
        // screen). The engine's updateFall() destroys the lift via
        // setDestroyed(true) once currentY > screenBottom, so isDestroyed()
        // handles the off-screen unseat. While the lift is still on-screen
        // (including the SLIDE -> FALL transition frame), the platform must
        // remain solid so processInlineRidingObject continues to carry the
        // riders. Treating FALL as non-solid (as the previous implementation
        // did) immediately unseated Sonic and Tails on the transition frame,
        // causing HTZ trace divergence at f308.
        return !isDestroyed();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.HTZ_LIFT);
        if (renderer == null) return;

        int drawX = xFixed >> 8;
        int drawY = yFixed >> 8;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        renderer.drawFrameIndex(mappingFrame, drawX, drawY, flippedX, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }
}
