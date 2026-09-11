package com.openggf.game.sonic2.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.Sonic2ZoneFeatureProvider;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * BreakablePlating (Object 0xC1) from Wing Fortress Zone.
 * <p>
 * A wall panel that the player can grab onto. When grabbed, the player hangs on
 * the left side and can climb up/down with directional input. Pressing A/B/C or
 * waiting out a timer causes the plating to break apart into 4 fragments that
 * fall with gravity.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 80384-80565 (ObjC1 code)
 * <p>
 * <h3>Routine State Machine:</h3>
 * <table border="1">
 *   <tr><th>Routine</th><th>ROM Label</th><th>Behavior</th></tr>
 *   <tr><td>0</td><td>ObjC1_Init</td><td>Load SubObjData ($88), compute delay timer from subtype</td></tr>
 *   <tr><td>2</td><td>ObjC1_Main</td><td>Detect player touch, grab player, allow up/down, release on A/B/C or timer</td></tr>
 *   <tr><td>4</td><td>ObjC1_Breakup</td><td>Fragments fall with gravity, animate, delete when off-screen</td></tr>
 * </table>
 * <p>
 * <h3>Subtype Format:</h3>
 * subtype * 60 = initial delay timer (objoff_30). With subtype 0x02: timer = 120 frames.
 * After the player grabs the plating, the timer counts down. When it reaches zero,
 * the plating breaks and the player is released.
 * <p>
 * <h3>Touch Response:</h3>
 * collision_flags = $E1 (ROM category $C0 = special, size index $21).
 * ROM Touch_Special for index $21 sets collision_property to signal player overlap.
 * The object checks collision_property to detect the initial grab.
 * <p>
 * <h3>Grab Mechanics (ObjC1_Main loc_3C140):</h3>
 * <ul>
 *   <li>Player must be to the LEFT of the object center by at least $14 pixels</li>
 *   <li>Player must not be hurt (routine < 4)</li>
 *   <li>Player velocity zeroed, positioned at objX - $14</li>
 *   <li>Player x_flip set (facing right toward wall)</li>
 *   <li>Animation set to HANG (AniIDSonAni_Hang = $11)</li>
 *   <li>obj_control = 1, WindTunnel_holding_flag = 1</li>
 * </ul>
 * <p>
 * <h3>Hanging State (ObjC1_Main objoff_32 set):</h3>
 * <ul>
 *   <li>Up: move player Y - 1, clamped to objY - $18</li>
 *   <li>Down: move player Y + 1, clamped to objY + $18</li>
 *   <li>A/B/C press (Ctrl_1_Press_Logical): release player and break</li>
 *   <li>Timer countdown: when timer reaches 0, release player and break</li>
 * </ul>
 * <p>
 * <h3>Breakup (loc_3C19A + ObjC1_Breakup):</h3>
 * Spawns 4 fragment children at offsets from byte_3C1E4, with initial x_vel = -$400,
 * y_vel = 0, gravity = 8/frame. Each fragment has a staggered start delay from byte_3C1E0.
 * Plays SndID_SlowSmash ($CB).
 * <p>
 * <h3>Fragment Animation (Ani_objC1):</h3>
 * Anim 0: delay 3, frames {2, 3, 4, 5, 1}, $FF (loop to frame 1)
 */
public class BreakablePlatingObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    // SubObjData: priority = 4, width_pixels = $40, collision_flags = $E1
    private static final int PRIORITY = 4;
    private static final int WIDTH_PIXELS = 0x40;

    // Touch response: collision_flags = $E1 in ROM.
    // ROM category $C0 = Touch_Special, size index $21.
    // In our engine, we use SPECIAL category ($40) to let the listener handle it,
    // keeping the same size index $21 for accurate touch distance.
    private static final int COLLISION_FLAGS = 0x40 | 0x21; // SPECIAL category + size index $21
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchResponseProfile.singleRegionContinuousCallbacks());

    // Grab position offset: player placed at objX - $14 (20 pixels left of center)
    // ROM: subi.w #$14,d0 (line 80452, 80462)
    private static final int GRAB_X_OFFSET = 0x14;

    // Vertical climb range: player can move within [objY - $18, objY + $18]
    // ROM: subi.w #$18,d0 (line 80419) and addi.w #$30,d0 (line 80427)
    // $18 above center, $18 below center (range = $30 = 48 pixels)
    private static final int CLIMB_RANGE = 0x18;

    // Fragment spawn offsets (byte_3C1E4) - 4 fragments, each with (dx, dy) word pairs
    // ROM: dc.w -$10, -$10, -$10, $10, -$30, -$10, -$30, $10
    private static final int[][] FRAGMENT_OFFSETS = {
            {-0x10, -0x10},  // Fragment 0
            {-0x10,  0x10},  // Fragment 1
            {-0x30, -0x10},  // Fragment 2
            {-0x30,  0x10},  // Fragment 3
    };

    // Fragment stagger delays (byte_3C1E0): 0, 4, $18, $20
    // ROM: dc.b 0, 4, $18, $20
    private static final int[] FRAGMENT_DELAYS = {0, 4, 0x18, 0x20};

    // Fragment initial velocity
    // ROM: move.w #-$400,x_vel(a1) / move.w #0,y_vel(a1)
    private static final int FRAGMENT_X_VEL = -0x400;
    private static final int FRAGMENT_Y_VEL = 0;

    // Fragment gravity: addi_.w #8,y_vel(a0)
    private static final int FRAGMENT_GRAVITY = 8;

    // Fragment width_pixels: move.b #$10,width_pixels(a1)
    private static final int FRAGMENT_WIDTH = 0x10;

    // Animation: Ani_objC1 anim 0 - delay 3, frames {2, 3, 4, 5, 1}, $FF (loop)
    private static final int ANIM_DELAY = 3;
    private static final int[] ANIM_FRAMES = {2, 3, 4, 5, 1};

    // ========================================================================
    // State
    // ========================================================================

    private enum Routine {
        MAIN,     // routine 2: detect touch, handle grab
        BREAKUP   // routine 4: fragment falling with gravity + animation
    }

    private int x;
    private int y;
    private Routine routine;

    // Grab state (objoff_30: timer, objoff_32: grabbed flag)
    private int delayTimer;          // objoff_30: frames until forced breakup
    private boolean playerGrabbed;   // objoff_32: true when player is hanging on
    private boolean collisionProperty; // ROM collision_property signal set by Touch_Special
    private boolean playerWasJumpPressed; // Track edge-trigger for A/B/C release
    private int collisionFlags;      // Current collision flags (cleared on grab/breakup)
    private transient AbstractPlayableSprite lastNativeMainPlayer;

    // Fragment state (only used when routine == BREAKUP)
    private boolean isFragment;
    private int fragX;              // Fragment X position (8.8 fixed point, upper = pixel)
    private int fragY;              // Fragment Y position (8.8 fixed point, upper = pixel)
    private int fragXVel;           // Fragment X velocity (8.8 fixed point)
    private int fragYVel;           // Fragment Y velocity (8.8 fixed point)
    private int fragDelay;          // objoff_3F: stagger delay before movement starts
    private int mappingFrame;       // Current mapping frame for rendering
    private int animIndex;          // Current index into ANIM_FRAMES
    private int animTimer;          // Animation frame delay counter

    /**
     * Main constructor for the parent BreakablePlating object (placed in level layout).
     */
    public BreakablePlatingObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.y = spawn.y();
        this.routine = Routine.MAIN;
        this.isFragment = false;
        this.playerGrabbed = false;
        this.playerWasJumpPressed = false;

        // ROM: moveq #0,d0 / move.b subtype(a0),d0 / mulu.w #60,d0 / move.w d0,objoff_30(a0)
        this.delayTimer = (spawn.subtype() & 0xFF) * 60;

        // collision_flags initially set from SubObjData
        this.collisionFlags = COLLISION_FLAGS;

        // Mapping frame 0 = full intact plating
        this.mappingFrame = 0;
    }

    @Override
    public BreakablePlatingObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BreakablePlatingObjectInstance(ctx.spawn(), getName());
    }

    /**
     * Fragment constructor for spawned breakup pieces.
     * ROM: loc_3C1F4 - AllocateObjectAfterCurrent, set routine=4, copy mappings/art_tile,
     * set position from offsets, set x_vel=-$400, y_vel=0, mapping_frame=1.
     */
    private BreakablePlatingObjectInstance(ObjectSpawn parentSpawn, int fragX, int fragY,
                                           int fragXVel, int fragYVel, int fragDelay,
                                           int priority) {
        super(parentSpawn, "BreakPlatingFrag");
        this.x = fragX;
        this.y = fragY;
        this.routine = Routine.BREAKUP;
        this.isFragment = true;
        this.playerGrabbed = false;
        this.collisionFlags = 0; // Fragments have no collision

        this.fragX = fragX << 8;
        this.fragY = fragY << 8;
        this.fragXVel = fragXVel;
        this.fragYVel = fragYVel;
        this.fragDelay = fragDelay;

        // ROM: move.b #1,mapping_frame(a1)
        this.mappingFrame = 1;
        this.animIndex = 0;
        this.animTimer = 0; // ROM: anim_frame_duration starts at 0, AnimateSprite immediately loads first frame
    }

    // ========================================================================
    // Update
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = nativeMainPlayer(playerEntity);
        lastNativeMainPlayer = player;
        switch (routine) {
            case MAIN -> updateMain(player);
            case BREAKUP -> updateBreakup();
        }
    }

    // ========================================================================
    // Routine 2: Main - Touch Detection and Grab Handling (ObjC1_Main)
    // ========================================================================

    /**
     * ROM: ObjC1_Main (s2.asm lines 80409-80470)
     * <p>
     * Two sub-states controlled by objoff_32:
     * - objoff_32 == 0: Check collision_property for touch, grab player if valid
     * - objoff_32 != 0: Player is hanging, handle up/down/release
     */
    private void updateMain(AbstractPlayableSprite player) {
        if (playerGrabbed) {
            updateHanging(player);
        } else if (collisionProperty) {
            collisionProperty = false;
            tryGrabPlayer(player);
        }
        // Touch detection is handled by onTouchResponse callback
    }

    /**
     * ROM: ObjC1_Main when objoff_32 != 0 (s2.asm lines 80410-80444)
     * <p>
     * Handles the player hanging on the plating:
     * - Timer countdown (objoff_30), forced breakup when timer expires
     * - Up/Down directional input to climb within range
     * - A/B/C press to release and trigger breakup
     */
    private void updateHanging(AbstractPlayableSprite player) {
        if (player == null) {
            releasePlayer(player);
            return;
        }

        // ROM: tst.w objoff_30(a0) / beq.s + / subq.w #1,objoff_30(a0) / beq.s loc_3C12E
        if (delayTimer > 0) {
            delayTimer--;
            if (delayTimer == 0) {
                // Timer expired - force release and breakup
                releasePlayer(player);
                startBreakup();
                return;
            }
        }

        // ROM: btst #button_up,(Ctrl_1_Held).w / subq.w #1,y_pos(a1) / cmp...
        // Up: move player up by 1, clamp to objY - $18
        if (player.isUpPressed()) {
            int minY = y - CLIMB_RANGE;
            int newY = player.getCentreY() - 1;
            if (newY < minY) {
                newY = minY;
            }
            NativePositionOps.writeYPosPreserveSubpixel(player, newY);
        }

        // ROM: btst #button_down,(Ctrl_1_Held).w / addq.w #1,y_pos(a1) / cmp...
        // Down: move player down by 1, clamp to objY + $18
        if (player.isDownPressed()) {
            int maxY = y + CLIMB_RANGE;
            int newY = player.getCentreY() + 1;
            if (newY > maxY) {
                newY = maxY;
            }
            NativePositionOps.writeYPosPreserveSubpixel(player, newY);
        }

        // ROM: move.b (Ctrl_1_Press_Logical).w,d0
        // andi.w #button_B_mask|button_C_mask|button_A_mask,d0
        // Edge-triggered A/B/C press detection (Ctrl_1_Press_Logical = newly pressed)
        boolean jumpNow = player.isJumpPressed();
        if (jumpNow && !playerWasJumpPressed) {
            // Player just pressed A/B/C - release and break
            releasePlayer(player);
            startBreakup();
            return;
        }
        playerWasJumpPressed = jumpNow;
    }

    /**
     * Grab the player onto the plating.
     * ROM: loc_3C140 to loc_3C170 (s2.asm lines 80447-80467)
     */
    private void grabPlayer(AbstractPlayableSprite player) {
        // ROM: clr.w x_vel(a1) / clr.w y_vel(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        // ROM: move.w x_pos(a0),d0 / subi.w #$14,d0 / move.w d0,x_pos(a1)
        NativePositionOps.writeXPosPreserveSubpixel(player, x - GRAB_X_OFFSET);

        // ROM: bset #status.player.x_flip,status(a1)
        // x_flip set = facing left (s2.constants.asm: status.player.x_flip = render_flags.x_flip)
        player.setDirection(Direction.LEFT);

        // ROM: move.b #AniIDSonAni_Hang,anim(a1)
        player.setAnimationId(Sonic2AnimationIds.HANG);

        // ROM: move.b #1,(MainCharacter+obj_control).w
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        // ROM: move.b #1,(WindTunnel_holding_flag).w
        setWindTunnelHolding(true);

        // ROM: move.b #1,objoff_32(a0)
        playerGrabbed = true;
        playerWasJumpPressed = player.isJumpPressed(); // Initialize edge-trigger tracking
    }

    private void tryGrabPlayer(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // ROM: move.w x_pos(a0),d0 / subi.w #$14,d0 / cmp.w x_pos(a1),d0
        // bhs.s BranchTo16_JmpTo39_MarkObjGone
        int grabThresholdX = x - GRAB_X_OFFSET;
        if (grabThresholdX >= player.getCentreX()) {
            return;
        }

        // ROM: cmpi.b #4,routine(a1) / bhs.s BranchTo16_JmpTo39_MarkObjGone
        if (player.isHurt() || player.getDead()) {
            return;
        }

        grabPlayer(player);
    }

    /**
     * Release the player from the plating.
     * ROM: loc_3C12E (s2.asm lines 80439-80444)
     */
    private void releasePlayer(AbstractPlayableSprite player) {
        if (player != null && playerGrabbed) {
            // ROM: clr.b collision_flags(a0)
            collisionFlags = 0;

            // ROM: clr.b (MainCharacter+obj_control).w
            ObjectControlState.none().applyTo(player);

            // ROM: clr.b (WindTunnel_holding_flag).w
            setWindTunnelHolding(false);

            // ROM: clr.b objoff_32(a0)
            playerGrabbed = false;
        }
    }

    private void setWindTunnelHolding(boolean holding) {
        ObjectServices objectServices = tryServices();
        if (objectServices != null
                && objectServices.zoneFeatureProvider() instanceof Sonic2ZoneFeatureProvider sonic2) {
            sonic2.setWfzWindTunnelHolding(holding);
        }
    }

    // ========================================================================
    // Breakup: Spawn Fragments (loc_3C19A)
    // ========================================================================

    /**
     * ROM: loc_3C19A (s2.asm lines 80473-80557)
     * <p>
     * Spawns 4 fragment children at offsets from byte_3C1E4.
     * The first fragment reuses this object (sets routine=4), remaining 3 are
     * allocated via AllocateObjectAfterCurrent.
     * <p>
     * In our engine, we spawn all 4 as dynamic objects and mark this parent as destroyed.
     */
    private void startBreakup() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        // ROM: move.b priority(a0),d4 / subq.b #1,d4
        // Fragments get priority one less than parent
        int fragPriority = Math.max(1, PRIORITY - 1);

        // Spawn 4 fragments
        for (int i = 0; i < 4; i++) {
            int fragX = x + FRAGMENT_OFFSETS[i][0];
            int fragY = y + FRAGMENT_OFFSETS[i][1];

            final int delay = FRAGMENT_DELAYS[i];
            spawnChild(() -> new BreakablePlatingObjectInstance(
                    spawn, fragX, fragY, FRAGMENT_X_VEL, FRAGMENT_Y_VEL,
                    delay, fragPriority));
        }

        // ROM: move.w #SndID_SlowSmash,d0 / jmp (PlaySound).l
        services().playSfx(Sonic2AudioConstants.SFX_SLOW_SMASH);

        // Mark parent as destroyed (fragments handle their own lifecycle)
        routine = Routine.BREAKUP;
        setDestroyed(true);
    }

    // ========================================================================
    // Routine 4: Breakup - Fragment Movement and Animation (ObjC1_Breakup)
    // ========================================================================

    /**
     * ROM: ObjC1_Breakup (s2.asm lines 80478-80492)
     * <p>
     * Fragment behavior:
     * - If stagger delay (objoff_3F) > 0: decrement and wait (no movement/animation)
     * - Otherwise: ObjectMove (apply velocity), add gravity to y_vel, AnimateSprite
     * - Delete when off-screen
     */
    private void updateBreakup() {
        if (!isFragment) {
            return; // Parent is already destroyed
        }

        // ROM: tst.b objoff_3F(a0) / beq.s + / subq.b #1,objoff_3F(a0) / bra.s ++
        if (fragDelay > 0) {
            fragDelay--;
            return; // Wait for stagger delay
        }

        // ROM: jsrto JmpTo26_ObjectMove
        // ObjectMove: x_pos += x_vel, y_pos += y_vel (8.8 fixed point)
        fragX += fragXVel;
        fragY += fragYVel;

        // ROM: addi_.w #8,y_vel(a0)
        fragYVel += FRAGMENT_GRAVITY;

        // ROM: lea (Ani_objC1).l,a1 / jsrto JmpTo25_AnimateSprite
        updateFragmentAnimation();

        // ROM: _btst #render_flags.on_screen,render_flags(a0) / _beq.w JmpTo65_DeleteObject
        // Check if fragment is too far off-screen and should be deleted
        int pixelY = fragY >> 8;
        if (pixelY > y + 0x200) {
            setDestroyed(true);
        }
    }

    /**
     * ROM: AnimateSprite with Ani_objC1
     * Anim 0: delay 3, frames {2, 3, 4, 5, 1}, $FF (loop back to start of sequence)
     */
    private void updateFragmentAnimation() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }
        animTimer = ANIM_DELAY;

        animIndex++;
        if (animIndex >= ANIM_FRAMES.length) {
            // $FF = restart animation
            animIndex = 0;
        }
        mappingFrame = ANIM_FRAMES[animIndex];
    }

    // ========================================================================
    // TouchResponseProvider / TouchResponseListener
    // ========================================================================

    /**
     * ROM: collision_flags = $E1 (category $C0, size $21).
     * In our engine, we use SPECIAL category ($40) so the touch system delegates
     * to the listener without automatic hurt/bounce behavior.
     */
    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    /**
     * ROM: collision_property is just a signal byte; not used as a hit counter.
     */
    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // Touch_Special refreshes collision_property on every overlapping frame;
        // ObjC1 may reject an early overlap and accept it later without separation.
        return TOUCH_RESPONSE_PROFILE.continuousCallbacks();
    }

    /**
     * Called by the touch response system when the player overlaps this object.
     * ROM: collision_property is set by Touch_Special, then ObjC1_Main checks it.
     * <p>
     * ROM logic (loc_3C140-3C170):
     * - Check collision_property != 0
     * - Check player is to the LEFT of object by at least $14 pixels
     * - Check player routine < 4 (not hurt/dying)
     * - If all checks pass: grab the player
     */
    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (playerGrabbed) {
            return; // Already grabbed
        }
        // ROM ObjC1 consumes Touch_Special's collision_property through a
        // MainCharacter-only branch; Sidekick never reaches the grab routine.
        PlayableEntity nativeMain = services().playerQuery().mainPlayerOrNull();
        if (nativeMain == null) {
            nativeMain = lastNativeMainPlayer;
        }
        collisionProperty = playerEntity != null && playerEntity == nativeMain;
    }

    private AbstractPlayableSprite nativeMainPlayer(PlayableEntity updatePlayer) {
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        if (main instanceof AbstractPlayableSprite sprite) {
            return sprite;
        }
        return updatePlayer instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    // ========================================================================
    // Position
    // ========================================================================

    @Override
    public int getX() {
        if (isFragment) {
            return fragX >> 8;
        }
        return x;
    }

    @Override
    public int getY() {
        if (isFragment) {
            return fragY >> 8;
        }
        return y;
    }

    @Override
    public int getPriorityBucket() {
        if (isFragment) {
            return RenderPriority.clamp(PRIORITY - 1);
        }
        return RenderPriority.clamp(PRIORITY);
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.WFZ_BREAK_PANELS);
        if (renderer == null || !renderer.isReady()) {
            appendFallbackDebug(commands);
            return;
        }

        int renderX = isFragment ? (fragX >> 8) : x;
        int renderY = isFragment ? (fragY >> 8) : y;

        // ROM: render_flags = 1<<render_flags.on_screen | 1<<render_flags.level_fg
        renderer.drawFrameIndex(mappingFrame, renderX, renderY, false, false);
    }

    /**
     * Fallback debug rendering when art is not available.
     */
    private void appendFallbackDebug(List<GLCommand> commands) {
        int renderX = isFragment ? (fragX >> 8) : x;
        int renderY = isFragment ? (fragY >> 8) : y;
        int hw = isFragment ? FRAGMENT_WIDTH : WIDTH_PIXELS;
        int hh = isFragment ? 0x10 : 0x20;

        appendLine(commands, renderX - hw, renderY - hh, renderX + hw, renderY - hh);
        appendLine(commands, renderX + hw, renderY - hh, renderX + hw, renderY + hh);
        appendLine(commands, renderX + hw, renderY + hh, renderX - hw, renderY + hh);
        appendLine(commands, renderX - hw, renderY + hh, renderX - hw, renderY - hh);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.8f, 0.4f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.8f, 0.4f, 0.2f, x2, y2, 0, 0));
    }

    // ========================================================================
    // Debug Visualization
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!services().configuration().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        int renderX = isFragment ? (fragX >> 8) : x;
        int renderY = isFragment ? (fragY >> 8) : y;

        if (isFragment) {
            // Draw fragment bounds
            ctx.drawRect(renderX, renderY, FRAGMENT_WIDTH, 0x10, 0.8f, 0.4f, 0.2f);
            String label = String.format("C1f d%d f%d", fragDelay, mappingFrame);
            ctx.drawWorldLabel(renderX, renderY, -2, label, DebugColor.ORANGE);
        } else {
            // Draw main object bounds
            ctx.drawRect(renderX, renderY, WIDTH_PIXELS, 0x20, 0.8f, 0.6f, 0.2f);

            // Draw grab zone indicator
            int grabX = x - GRAB_X_OFFSET;
            ctx.drawRect(grabX, y, 4, CLIMB_RANGE, 0.2f, 0.8f, 0.4f);

            String stateLabel = String.format("C1:%s t%d %s",
                    playerGrabbed ? "GRAB" : "WAIT",
                    delayTimer,
                    collisionFlags != 0 ? "col" : "---");
            ctx.drawWorldLabel(renderX, renderY, -2, stateLabel, DebugColor.CYAN);
        }
    }
}
