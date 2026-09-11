package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Seesaw Ball (child of Object 0x14) from Hill Top Zone.
 * <p>
 * A ball that sits on the seesaw and launches when the angle changes.
 * When the ball lands, any player standing on the seesaw gets launched.
 * <p>
 * Uses Sol badnik art (ArtNem_Sol).
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 47117-47271.
 */
public class SeesawBallObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // Ball Y offsets per seesaw frame (Obj14_YOffsets)
    // ROM: dc.w -8, -28, -47, -28, -8 ; low, balanced, high, balanced, low
    private static final int[] BALL_Y_OFFSETS = {-8, -28, -47, -28, -8};

    // Launch velocities based on angle change
    // ROM: loc_21B00 through loc_21B52
    private static final int LAUNCH_Y_SMALL = -0x818;   // angle change = 1
    private static final int LAUNCH_X_SMALL = -0x114;
    private static final int LAUNCH_Y_MEDIUM = -0xAF0;  // angle change = 2
    private static final int LAUNCH_X_MEDIUM = -0xCC;
    private static final int LAUNCH_Y_HEAVY = -0xE00;   // heavy landing (y_vel > 0x0A00)
    private static final int LAUNCH_X_HEAVY = -0xA0;

    // Threshold for heavy landing
    private static final int HEAVY_LANDING_THRESHOLD = 0x0A00;

    // Collision flags (0x8B from ROM)
    private static final int COLLISION_FLAGS = 0x8B;

    // Width pixels (0x0C from ROM)
    private static final int WIDTH_PIXELS = 0x0C;

    // Priority from ROM
    private static final int PRIORITY = 4;

    // Gravity constant (standard Sonic 2 gravity: 0x38 per frame)
    private static final int GRAVITY = 0x38;

    // Ball state
    private enum State {
        RESTING,  // On seesaw
        FLYING    // In air
    }

    private State state = State.RESTING;

    // Parent seesaw reference.
    // Recreate relinks this through the ctor after matching the restored parent.
    private SeesawObjectInstance parent;

    // Position tracking - combined 16.16 fixed-point (pixel in bits 16-31, subpixel in bits 0-15)
    // This matches ROM's 32-bit position format for correct signed arithmetic
    private int xPos;  // Combined position (xPos >> 16 = pixel X)
    private int yPos;  // Combined position (yPos >> 16 = pixel Y)
    private int xVel;  // 8.8 fixed-point velocity
    private int yVel;  // 8.8 fixed-point velocity

    // Seesaw reference position (objoff_30, objoff_34 in ROM).
    // Un-final so GenericFieldCapturer can seed captured values before parent matching.
    private int seesawCenterX;
    private int seesawBottomY;

    // Stored angle state (objoff_3A in ROM)
    private int storedAngle;

    // Palette animation frame (toggles every 4 frames)
    private int paletteFrame;
    private int animTimer;
    // Store spawn for dynamic override.
    // Un-final for rewind-capture consistency (policy-marked TRANSIENT; reapplied/relinked
    // via the ctor on recreate).
    private ObjectSpawn originalSpawn;

    SeesawBallObjectInstance() {
        super(new ObjectSpawn(0, 0, 0x14, 0, 0, false, 0), "SeesawBall");
        this.originalSpawn = spawn;
    }

    public SeesawBallObjectInstance(
            int seesawCenterX,
            int seesawBottomY,
            int initialX,
            int initialY,
            SeesawObjectInstance parent,
            boolean flipped
    ) {
        super(new ObjectSpawn(initialX, initialY, 0x14, 0, 0, false, 0),
                "SeesawBall");
        this.originalSpawn = spawn;

        this.seesawCenterX = seesawCenterX;
        this.seesawBottomY = seesawBottomY;
        this.xPos = initialX << 16;  // Convert pixel to 16.16 fixed-point
        this.yPos = initialY << 16;
        this.parent = parent;

        // ROM: btst #status.npc.x_flip,status(a0) / move.b #2,objoff_3A(a0)
        this.storedAngle = flipped ? 2 : 0;

        this.paletteFrame = 0;
        this.animTimer = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        seedCapturedScalars(ctx);
        SeesawObjectInstance restoredParent =
                findParentForRewind(ctx, seesawCenterX, seesawBottomY);
        if (restoredParent == null) {
            return null;
        }
        int centerX = restoredParent.getSpawn().x();
        int bottomY = restoredParent.getSpawn().y() + 0x10;
        SeesawBallObjectInstance restored = new SeesawBallObjectInstance(
                centerX,
                bottomY,
                centerX,
                bottomY,
                restoredParent,
                restoredParent.isFlippedHorizontal());
        restoredParent.adoptBallForRewind(restored);
        return restored;
    }

    private void seedCapturedScalars(RewindRecreateContext ctx) {
        if (ctx == null || ctx.state() == null || ctx.state().compactGenericState() == null) {
            return;
        }
        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(this, ctx.state().compactGenericState());
    }

    private static SeesawObjectInstance findParentForRewind(
            RewindRecreateContext ctx,
            int capturedCenterX,
            int capturedBottomY) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof SeesawObjectInstance seesaw) || seesaw.isDestroyed()) {
                continue;
            }
            if (seesaw.getSpawn().subtype() != 0 || seesaw.hasLiveBall()) {
                continue;
            }
            int centerX = seesaw.getSpawn().x();
            int bottomY = seesaw.getSpawn().y() + 0x10;
            if (centerX == capturedCenterX && bottomY == capturedBottomY) {
                return seesaw;
            }
        }
        return null;
    }

    /**
     * Returns dynamic spawn with current position.
     * Bug fix #1: Collision must follow ball movement, not stay at original spawn.
     */
    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(
                xPos >> 16,
                yPos >> 16,
                originalSpawn.objectId(),
                originalSpawn.subtype(),
                originalSpawn.renderFlags(),
                originalSpawn.respawnTracked(),
                originalSpawn.rawYWord());
    }

    /**
     * ROM parity: {@code Obj14_Ball_Init} (docs/s2disasm/s2.asm:47151) stores
     * the parent seesaw's x_pos in {@code objoff_30(a0)} BEFORE applying the
     * {@code addi.w #$28} ball offset. The dispatcher's {@code MarkObjGone2}
     * (s2.asm:46996, {@code move.w objoff_30(a0),d0 / jmpto JmpTo_MarkObjGone2})
     * therefore checks the ball's out-of-range using the parent seesaw's x,
     * not the ball's own. Without this, a flipped seesaw whose ball spawns
     * at {@code parent_x - 0x28} can straddle a 128-byte chunk boundary,
     * causing the ball alone to be unloaded as soon as the camera enters
     * the parent's chunk. The seesaw's own out-of-range check still passes,
     * leaving the parent with a stale {@code ball} reference and the
     * trace-replay path with a permanently launchless seesaw.
     */
    @Override
    public int getOutOfRangeReferenceX() {
        return seesawCenterX;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Update palette animation (toggle every 4 frames)
        // ROM: Obj14_Animate
        animTimer++;
        if ((animTimer & 3) == 0) {
            paletteFrame = 1 - paletteFrame; // Toggle between 0 and 1
        }

        // Update facing direction based on main character
        // ROM: Obj14_SetSolToFaceMainCharacter
        // (Handled in render - flip based on player position)

        switch (state) {
            case RESTING:
                updateResting(player);
                break;
            case FLYING:
                updateFlying(player);
                break;
        }
    }

    /**
     * Ball is resting on seesaw - check if angle changed to launch.
     * ROM: Obj14_Ball_Main
     */
    private void updateResting(AbstractPlayableSprite player) {
        int parentAngle = parent.getCurrentAngle();

        // Calculate angle delta
        // ROM: move.b objoff_3A(a0),d0 / sub.b objoff_3A(a1),d0
        int angleDelta = storedAngle - parentAngle;
        if (angleDelta == 0) {
            // No change - update position to rest on seesaw
            positionOnSeesaw();
            return;
        }

        // Angle changed - calculate launch velocity
        // ROM: loc_21B00 through loc_21B52
        int absDelta = Math.abs(angleDelta);

        int launchYVel, launchXVel;
        if (absDelta == 1) {
            launchYVel = LAUNCH_Y_SMALL;
            launchXVel = LAUNCH_X_SMALL;
        } else {
            // Check for heavy landing
            int storedPlayerYVel = parent.getStoredPlayerYVel();
            if (storedPlayerYVel >= HEAVY_LANDING_THRESHOLD) {
                launchYVel = LAUNCH_Y_HEAVY;
                launchXVel = LAUNCH_X_HEAVY;
            } else {
                launchYVel = LAUNCH_Y_MEDIUM;
                launchXVel = LAUNCH_X_MEDIUM;
            }
        }

        // Set velocities
        yVel = launchYVel;
        xVel = launchXVel;

        // Negate X velocity if ball is on the left side of seesaw
        // ROM: sub.w objoff_30(a0),d0 / bcc.s + / neg.w x_vel(a0)
        int currentX = xPos >> 16;
        if (currentX < seesawCenterX) {
            xVel = -xVel;
        }

        // Update stored angle to match parent
        storedAngle = parentAngle;

        // Enter flying state
        state = State.FLYING;

        // Reset parent's stored Y velocity
        parent.resetStoredPlayerYVel();

        // ROM branches directly from Obj14_Ball_Main into Obj14_Ball_Fly after
        // assigning velocity (docs/s2disasm/s2.asm:47142-47144).
        updateFlying(player);
    }

    /**
     * Position ball to rest on seesaw based on current visual frame.
     * ROM: Obj14_SetBallToRestOnSeeSaw uses mapping_frame(a1), not objoff_3A.
     */
    private void positionOnSeesaw() {
        // ROM: move.b mapping_frame(a1),d0 - uses VISUAL frame for Y offset
        int parentFrame = parent.getMappingFrame();

        // Calculate which Y offset to use
        // ROM: Complex offset calculation based on ball position and frame
        int offsetIndex = parentFrame;
        int xOffset = 0x28;

        int currentX = xPos >> 16;
        if (currentX < seesawCenterX) {
            xOffset = -0x28;
            offsetIndex += 2;
        }

        // Clamp to array bounds
        if (offsetIndex < 0) offsetIndex = 0;
        if (offsetIndex >= BALL_Y_OFFSETS.length) offsetIndex = BALL_Y_OFFSETS.length - 1;

        // Set combined positions
        yPos = (seesawBottomY + BALL_Y_OFFSETS[offsetIndex]) << 16;
        xPos = (seesawCenterX + xOffset) << 16;
    }

    private void updateFlying(AbstractPlayableSprite player) {
        // S2 Obj14_Ball_Fly branches on y_vel before ObjectMoveAndFall
        // (docs/s2disasm/s2.asm:47214-47231). A zero velocity is already descending.
        if (yVel >= 0) {
            moveAndFall();
            int currentX = xPos >> 16;
            int currentY = yPos >> 16;
            int landingY = calculateLandingY(currentX);
            if (currentY >= landingY) {
                // Determine which end we landed on
                // ROM: moveq #2,d1 / tst.w x_vel(a0) / bmi.s + / moveq #0,d1
                int landingAngle = (xVel < 0) ? 2 : 0;

                // ROM: cmp.b mapping_frame(a1),d1 / beq.s loc_21C1E
                // Get the OLD visual frame BEFORE setting the new angle
                // Player is ONLY launched if the ball landing causes a VISUAL change
                int oldMappingFrame = parent.getMappingFrame();

                // ROM: move.b d1,objoff_3A(a1) - only sets target angle, not mapping_frame
                // Set parent seesaw's target angle (visual transition happens later)
                parent.setCurrentAngle(landingAngle);

                // Update our stored angle
                storedAngle = landingAngle;

                // Only launch players if angle changed from OLD visual frame
                // ROM: If same as mapping_frame, jumps to loc_21C1E which skips player launch
                if (landingAngle != oldMappingFrame) {
                    launchStandingPlayers();
                }

                // Clear velocities and return to resting state
                xVel = 0;
                yVel = 0;
                state = State.RESTING;
            }
        } else {
            moveAndFall();
            int currentY = yPos >> 16;
            // Ascending - ROM calls ObjectMoveAndFall TWICE when ball is below upper bound
            // ROM: loc_21BA0-21BB4:
            //   jsrto JmpTo_ObjectMoveAndFall  ; first call
            //   move.w objoff_34(a0),d0
            //   subi.w #$2F,d0                  ; upper bound = seesaw_bottom - 0x2F
            //   cmp.w y_pos(a0),d0
            //   bgt.s return_21BB4              ; if above bound, return (only 1 call)
            //   jsrto JmpTo_ObjectMoveAndFall  ; second call if below bound
            int upperBound = seesawBottomY - 0x2F;
            if (currentY >= upperBound) {
                // Below upper bound - apply movement+gravity a SECOND time
                // This doubles the acceleration when ball is still ascending but near the apex
                moveAndFall();
            }
        }
    }

    private void moveAndFall() {
        // ObjectMoveAndFall adds old velocity to 16.16 position, then applies gravity
        // (docs/s2disasm/s2.asm:29967-29981).
        xPos += (xVel << 8);
        yPos += (yVel << 8);
        yVel += GRAVITY;
    }

    /**
     * Calculates the Y position where the ball should land on the seesaw.
     * ROM uses mapping_frame for Y offset calculation, not objoff_3A.
     */
    private int calculateLandingY(int currentX) {
        // ROM: Uses mapping_frame(a1) for offset lookup
        int parentFrame = parent.getMappingFrame();

        // Calculate which Y offset to use based on ball X position
        int offsetIndex = parentFrame;
        if (currentX < seesawCenterX) {
            offsetIndex += 2;
        }

        // Clamp to array bounds
        if (offsetIndex < 0) offsetIndex = 0;
        if (offsetIndex >= BALL_Y_OFFSETS.length) offsetIndex = BALL_Y_OFFSETS.length - 1;

        return seesawBottomY + BALL_Y_OFFSETS[offsetIndex];
    }

    /**
     * Launch players standing on the seesaw.
     * ROM: Obj14_LaunchCharacter
     *
     * Uses the parent seesaw's tracked standing players (via status bits in ROM)
     * instead of position-based detection. This matches ROM behavior where
     * p1_standing_bit/p2_standing_bit on the seesaw object tracks who is standing.
     */
    private void launchStandingPlayers() {
        // ROM: bclr #p1_standing_bit,status(a1) / beq.s + / bsr.s Obj14_LaunchCharacter
        // Check and launch player 1
        AbstractPlayableSprite player1 = parent.getStandingPlayer1();
        if (player1 != null) {
            launchPlayer(player1);
            parent.clearStandingPlayer1();
        }

        // ROM: bclr #p2_standing_bit,status(a1) / beq.s + / bsr.s Obj14_LaunchCharacter
        // Check and launch player 2
        AbstractPlayableSprite player2 = parent.getStandingPlayer2();
        if (player2 != null) {
            launchPlayer(player2);
            parent.clearStandingPlayer2();
        }
    }

    /**
     * Launches a player with inverse ball Y velocity.
     * ROM: Obj14_LaunchCharacter
     */
    private void launchPlayer(AbstractPlayableSprite player) {
        // ROM: move.w y_vel(a0),y_vel(a2) / neg.w y_vel(a2)
        player.setYSpeed((short) -yVel);

        // ROM: bset #status.player.in_air,status(a2)
        player.setAir(true);

        // ROM: bclr #status.player.on_object,status(a2)
        player.setOnObject(false);

        // ROM: clr.b jumping(a2) - clear jumping flag
        player.setJumping(false);

        // ROM: move.b #AniIDSonAni_Spring,anim(a2)
        player.setAnimationId(Sonic2AnimationIds.SPRING);

        // ROM: move.b #2,routine(a2) - set to airborne routine
        // Note: This engine manages routines differently; setAir(true) handles this

        // fixBugs (s2.asm:27 `fixBugs = 0`, block at s2.asm:47739-47744): the shipped
        // branch does NOT clear spindash_flag here, so a player launched by the seesaw
        // while charging a Spin Dash keeps the Spin Dash state in the air. fixBugs=1
        // adds `clr.b spindash_flag(a2)`. The engine models the shipped branch: leave
        // the spindash flag alone.

        // ROM: move.w #SndID_Spring,d0 / jmp (PlaySound).l
        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0; // No special property
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProvider.super.getTouchResponseProfile(multiRegionSource);
    }

    /**
     * ROM parity: S2 {@code TouchResponse} reads {@code x_pos(a1)} and
     * {@code y_pos(a1)} from the object SST when testing collision
     * (docs/s2disasm/s2.asm:85075,85092). Obj14's sprite bounds are offset
     * from that center position for drawing, so publish an explicit touch
     * region at the ROM position instead of the sprite top-left.
     */
    @Override
    public TouchRegion[] getMultiTouchRegions() {
        return new TouchRegion[] { new TouchRegion(getCentreX(), getCentreY(), COLLISION_FLAGS) };
    }

    @Override
    public int getX() {
        return (xPos >> 16) - WIDTH_PIXELS;
    }

    @Override
    public int getY() {
        return (yPos >> 16) - 8;
    }

    /**
     * Returns the center X for collision and position checks.
     */
    public int getCentreX() {
        return xPos >> 16;
    }

    /**
     * Returns the center Y for collision and position checks.
     */
    public int getCentreY() {
        return yPos >> 16;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s pos=%04X.%04X,%04X.%04X vel=%04X,%04X stored=%d parentFrame=%d parentAngle=%d",
                state,
                xPos >> 16, xPos & 0xFFFF,
                yPos >> 16, yPos & 0xFFFF,
                xVel & 0xFFFF, yVel & 0xFFFF,
                storedAngle,
                parent.getMappingFrame(),
                parent.getCurrentAngle());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SEESAW_BALL);
        if (renderer == null) return;

        // ROM: Obj14_SetSolToFaceMainCharacter
        // Face toward main character: x-flip if player is to the right of the ball
        // ROM: andi.b #~(1<<render_flags.x_flip),render_flags(a0)
        //      move.w (MainCharacter+x_pos).w,d0
        //      sub.w x_pos(a0),d0
        //      bcs.s return_21C8C  ; if player X < ball X, skip (face left = no flip)
        //      ori.b #1<<render_flags.x_flip,render_flags(a0)  ; player X >= ball X, face right = flip
        boolean hFlip = false;
        // Get the main character from the sprites (the update() player parameter)
        // We use LevelManager to get access to it since we're in the render method
        {
            // Get main playable sprite from all sprites
            for (Sprite sprite : services().spriteManager().getAllSprites()) {
                if (sprite instanceof AbstractPlayableSprite mainChar) {
                    // x-flip when player is to the right of the ball (face toward player)
                    hFlip = mainChar.getCentreX() >= (xPos >> 16);
                    break; // Only use first (main) playable sprite
                }
            }
        }

        // Use paletteFrame to select mapping frame (0 or 1 for palette toggle)
        renderer.drawFrameIndex(paletteFrame, xPos >> 16, yPos >> 16, hFlip, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

}
