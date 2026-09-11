package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * OOZ Popping Platform (Object 0x33) - Green burner platform from Oil Ocean Zone.
 * <p>
 * A platform sitting atop a burner pipe. It periodically pops upward (propelled by a gas flame),
 * then bounces back down. When a player stands on the player-triggered variant, they are locked
 * onto the platform and launched upward like a powerful spring at the apex.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 49222-49450 (Obj33)
 * <p>
 * <h3>Subtypes</h3>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Behavior</th></tr>
 *   <tr><td>0x00</td><td>Auto-pop: pops on a 120-frame timer, bounces back down</td></tr>
 *   <tr><td>!=0</td><td>Player-triggered: waits for player standing in center, launches player at apex</td></tr>
 * </table>
 */
public class OOZPoppingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    // Solid collision: d1 = width_pixels + $B = $18 + $B = $23
    private static final int WIDTH_PIXELS = 0x18;      // move.b #$18,width_pixels(a0)
    private static final int SOLID_HALF_WIDTH = WIDTH_PIXELS + 0x0B; // $23 = 35px
    private static final int SOLID_HALF_HEIGHT_AIR = 8; // move.w #8,d2
    private static final int SOLID_HALF_HEIGHT_GND = 9; // move.w #9,d3

    // Timer: objoff_36 = $78 (120 frames / 2 seconds)
    private static final int POP_TIMER_RESET = 0x78;

    // Pop velocity: objoff_32 = -$96800 (32-bit fixed-point 16.16, upward)
    private static final int POP_VELOCITY = -0x96800;

    // Gravity: objoff_32 += $3800 per frame
    private static final int GRAVITY = 0x3800;

    // Bounce termination threshold: velocity < $10000
    private static final int BOUNCE_THRESHOLD = 0x10000;

    // Player trigger X range: |player_x - platform_x| < $10 (16 pixels)
    private static final int TRIGGER_X_RANGE = 0x10;

    // Apex height: home_Y - $7D (125 pixels above home)
    private static final int APEX_OFFSET = 0x7D;

    // Launch Y velocity: -$1000 (8.8 format = -16 pixels/frame upward)
    private static final int LAUNCH_Y_VEL = -0x1000;

    // Launch inertia: $800
    private static final int LAUNCH_INERTIA = 0x800;

    // Flame visibility threshold: distance >= $14 (20 pixels)
    private static final int FLAME_VISIBLE_THRESHOLD = 0x14;

    // ========================================================================
    // State
    // ========================================================================

    // Sub-mode state machine (routine_secondary in ROM)
    private enum Mode {
        TIMER_COUNTDOWN,    // Mode 0: wait for timer, then pop
        POP_PHYSICS,        // Mode 2: rising/falling with bounce
        WAIT_FOR_PLAYER,    // Mode 4: player-triggered, wait for standing
        RISE_AND_LAUNCH,    // Mode 6: rising to apex, then launch player
        IDLE                // Mode 8: terminal state after launch
    }

    private int x;
    private int homeY;             // objoff_30: saved home Y position
    private int currentY;          // current y_pos (integer part)
    private int yFractional;       // fractional y_pos for 32-bit physics
    private int velocity;          // objoff_32: 32-bit velocity (16.16 fixed-point)
    private int timer;             // objoff_36: countdown timer
    private Mode mode;
    private boolean isPlayerTriggered; // subtype != 0

    // Tracking which players are locked onto the platform (mode 6)
    private boolean mainCharLocked;
    private boolean sidekickLocked;

    // Flame child
    private OOZBurnerFlameObjectInstance flameChild;

    public OOZPoppingPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.homeY = spawn.y();
        this.currentY = homeY;
        this.yFractional = 0;
        this.timer = POP_TIMER_RESET;
        this.velocity = 0;
        this.isPlayerTriggered = (spawn.subtype() != 0);

        // ROM Obj33_Init (s2.asm:49653-49657): routine_secondary defaults to 2 (mode 2 =
        // loc_23BEA pop-physics), and is only overridden to 4 (wait-for-player) when subtype != 0.
        // For the auto-pop (subtype 0) variant, the ROM burns one mode-2 frame immediately after
        // Init with objoff_32 (velocity) = 0: y stays at home, velocity becomes $3800 (< $10000),
        // so it transitions back to mode 0 and applies the bounce, THEN mode 0 starts the $78 timer
        // the next frame. Starting at TIMER_COUNTDOWN here would skip that single mode-2 frame and
        // fire every auto-pop one frame early (observed: engine pops at 1133 vs ROM 1134), which
        // drags the riding player down a frame too soon. updatePopPhysics() with velocity 0 already
        // reproduces the ROM's immediate transition-to-timer + bounce, so start in POP_PHYSICS.
        // (See s2.asm:49710-49728 loc_23BEA.)
        this.mode = isPlayerTriggered ? Mode.WAIT_FOR_PLAYER : Mode.POP_PHYSICS;

        updateDynamicSpawn(x, currentY);
    }

    @Override
    public OOZPoppingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new OOZPoppingPlatformObjectInstance(ctx.spawn(), "OOZPoppingPform");
    }

    private void ensureFlameChildSpawned() {
        if (flameChild != null || services().objectManager() == null) {
            return;
        }

        // Flame position: same X as parent, Y = parent Y - $10 (16 pixels above)
        int flameX = x;
        int flameY = homeY - 0x10;
        ObjectSpawn flameSpawn = new ObjectSpawn(flameX, flameY, spawn.objectId(), spawn.subtype(),
                spawn.renderFlags(), false, spawn.rawYWord());
        // ROM Obj33_Init uses AllocateObjectAfterCurrent.
        flameChild = spawnChild(() -> new OOZBurnerFlameObjectInstance(flameSpawn, this));
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return currentY;
    }

    /**
     * Stable home (spawn) Y used as the burner flame's parent identity on rewind relink.
     * The flame child spawns at {@code homeY - 0x10}, so a dropped flame can be re-bound to
     * its platform by matching {@code getX() == flameSpawn.x()} and
     * {@code getHomeY() == flameSpawn.y() + 0x10}.
     */
    public int getHomeY() {
        return homeY;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureFlameChildSpawned();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (mode) {
            case TIMER_COUNTDOWN -> updateTimerCountdown();
            case POP_PHYSICS -> updatePopPhysics();
            case WAIT_FOR_PLAYER -> updateWaitForPlayer(player, vIntRunCount);
            case RISE_AND_LAUNCH -> updateRiseAndLaunch(player, vIntRunCount);
            case IDLE -> { /* rts - do nothing */ }
        }
        updateDynamicSpawn(x, currentY);
    }

    // ========================================================================
    // Mode 0: Timer Countdown (loc_23BC6)
    // ========================================================================

    private void updateTimerCountdown() {
        timer--;
        if (timer >= 0) {
            return;
        }
        // Timer expired: pop
        timer = POP_TIMER_RESET;
        velocity = POP_VELOCITY;
        mode = Mode.POP_PHYSICS;
        services().playSfx(Sonic2Sfx.OOZ_LID_POP.id);
    }

    // ========================================================================
    // Mode 2: Pop Physics with Bounce (loc_23BEA)
    // ========================================================================

    private void updatePopPhysics() {
        // Apply velocity to Y position (32-bit fixed-point arithmetic)
        // y_pos(32-bit) += velocity
        int yPos32 = (currentY << 16) | (yFractional & 0xFFFF);
        yPos32 += velocity;
        currentY = yPos32 >> 16;
        yFractional = yPos32 & 0xFFFF;

        // Apply gravity
        velocity += GRAVITY;

        // Check if returned to or passed home Y
        if (currentY < homeY) {
            return; // Still above home
        }

        // ROM: cmpi.l #$10000,d0 / bhs.s + / subq.b #2,routine_secondary(a0)
        // When velocity < threshold, switch back to timer mode BUT still perform the bounce below.
        // Both paths (threshold met or not) fall through to the bounce code in ROM.
        if (velocity < BOUNCE_THRESHOLD) {
            mode = Mode.TIMER_COUNTDOWN;
        }

        // Bounce: ROM uses lsr.l #2,d0 (logical shift right) / neg.l d0
        // Always executed regardless of threshold check above.
        velocity = -(velocity >>> 2);
        currentY = homeY;
        yFractional = 0;
    }

    // ========================================================================
    // Mode 4: Wait for Player Standing (loc_23C26)
    // ========================================================================

    private void updateWaitForPlayer(AbstractPlayableSprite player, int vIntRunCount) {
        ObjectManager objectManager = services().objectManager();
        AbstractPlayableSprite sidekick = firstTrackedSidekick();

        // ROM: Check standing bits first, then X range
        boolean mainStanding = isPlayerStandingOnThis(player, objectManager);
        boolean sidekickStanding = sidekick != null && isPlayerStandingOnThis(sidekick, objectManager);

        if (!mainStanding && !sidekickStanding) {
            return; // No one standing on platform
        }

        // ROM: cmpi.b #standing_mask,d0 / beq.s loc_23CA0
        // When BOTH players are standing, both must be in X range (loc_23CA0).
        // When only one is standing, check that one individually.
        if (mainStanding && sidekickStanding) {
            // Both standing: both must be in X trigger range or no pop
            boolean mainInRange = isPlayerInXRange(player);
            boolean sidekickInRange = isPlayerInXRange(sidekick);
            if (!mainInRange || !sidekickInRange) {
                return;
            }
            lockPlayer(player);
            lockPlayer(sidekick);
            mainCharLocked = true;
            sidekickLocked = true;
        } else if (mainStanding) {
            if (!isPlayerInXRange(player)) {
                return;
            }
            lockPlayer(player);
            mainCharLocked = true;
        } else {
            // sidekickStanding
            if (!isPlayerInXRange(sidekick)) {
                return;
            }
            lockPlayer(sidekick);
            sidekickLocked = true;
        }

        // Pop
        velocity = POP_VELOCITY;
        mode = Mode.RISE_AND_LAUNCH;
        services().playSfx(Sonic2Sfx.OOZ_LID_POP.id);
    }

    /**
     * Check if a player is standing on this platform (riding it via SolidContacts).
     */
    private boolean isPlayerStandingOnThis(AbstractPlayableSprite player, ObjectManager objectManager) {
        if (player == null || player.getDead()) {
            return false;
        }
        return objectManager.isRidingObject(player, this);
    }

    /**
     * Check if a player's X position is within the trigger range.
     * ROM: subi.w #$10,d2 / cmpi.w #$20,d2 / bhs.s (return)
     */
    private boolean isPlayerInXRange(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - x;
        return dx >= -TRIGGER_X_RANGE && dx < TRIGGER_X_RANGE;
    }

    /**
     * Lock player onto platform.
     * ROM: move.b #1,obj_control(a1) / clr.w inertia(a1) / clr.w x_vel(a1) / clr.w y_vel(a1)
     *      bclr #status.player.pushing,status(a1) / bclr #high_priority_bit,art_tile(a1)
     */
    private void lockPlayer(AbstractPlayableSprite player) {
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setGSpeed((short) 0);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setPushing(false);
        player.setHighPriority(false); // ROM: bclr #high_priority_bit,art_tile(a1)
    }

    // ========================================================================
    // Mode 6: Rise to Apex and Launch Player (loc_23D20)
    // ========================================================================

    private void updateRiseAndLaunch(AbstractPlayableSprite player, int vIntRunCount) {
        // Apply velocity (same as pop physics)
        int yPos32 = (currentY << 16) | (yFractional & 0xFFFF);
        yPos32 += velocity;
        currentY = yPos32 >> 16;
        yFractional = yPos32 & 0xFFFF;

        // Apply gravity
        velocity += GRAVITY;

        // ROM: cmp.w d0,d1 / bne.s + (exact equality check)
        // The physics are calibrated so currentY always hits the exact apex value.
        int apexY = homeY - APEX_OFFSET;
        if (currentY != apexY) {
            // The engine's shared moving-solid carry can lose the positive obj_control rider
            // before Obj33 reaches its exact apex. Preserve the existing bridge on rising
            // frames only. ROM loc_23D20 launches without writing y_pos(a1), then clears
            // Status_OnObj before Obj33_Main reaches SolidObject, so the apex frame must not
            // re-seat the player to the platform top.
            moveLockedPlayers(player);
            return; // Not at apex yet
        }

        // Reached apex - launch standing players
        // ROM re-checks status standing bits at launch time (andi.b #p1_standing/p2_standing)
        mode = Mode.IDLE;
        ObjectManager objectManager = services().objectManager();

        if (player != null && objectManager.isRidingObject(player, this)) {
            launchPlayer(player, vIntRunCount);
        }
        mainCharLocked = false;

        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        if (sidekick != null && objectManager.isRidingObject(sidekick, this)) {
            launchPlayer(sidekick, vIntRunCount);
        }
        sidekickLocked = false;
    }

    private void moveLockedPlayers(AbstractPlayableSprite mainChar) {
        if (mainCharLocked && mainChar != null) {
            NativePositionOps.writeYPosPreserveSubpixel(mainChar, currentY - SOLID_HALF_HEIGHT_AIR);
        }
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        if (sidekickLocked && sidekick != null) {
            NativePositionOps.writeYPosPreserveSubpixel(sidekick, currentY - SOLID_HALF_HEIGHT_AIR);
        }
    }

    private AbstractPlayableSprite firstTrackedSidekick() {
        return services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                ? sidekick
                : null;
    }

    /**
     * Launch player at apex.
     * ROM: move.b #AniIDSonAni_Roll,anim(a1) / move.w #$800,inertia(a1)
     *      bset #1,status(a1) / move.w #-$1000,y_vel(a1)
     *      bclr #3,status(a1) / clr.b obj_control(a1)
     */
    private void launchPlayer(AbstractPlayableSprite player, int vIntRunCount) {
        // ROM move.w x_pos(a0),x_pos(a1): write the native pixel word and preserve x_sub.
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        // Set rolling animation
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        // Set inertia
        player.setGSpeed((short) LAUNCH_INERTIA);
        // Set airborne
        player.setAir(true);
        // Set strong upward velocity
        player.setYSpeed((short) LAUNCH_Y_VEL);
        // ROM bclr #status.player.on_object,status(a1)
        player.setOnObject(false);
        // Release from object control
        player.releaseFromObjectControl(vIntRunCount);
        // Play spring sound
        services().playSfx(Sonic2Sfx.SPRING.id);
    }

    // ========================================================================
    // Solid Object Interface
    // ========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT_AIR, SOLID_HALF_HEIGHT_GND);
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj33_Init writes width_pixels=$18. Balance uses width_pixels, while
        // the SolidObject call separately adds its $B horizontal allowance.
        return WIDTH_PIXELS;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj33_Main calls the standard SolidObject helper with d1=$18+$B.
        // Its unsigned BHI rejection keeps relX==2*d1 in range.
        return true;
    }

    @Override
    public boolean preservesZeroDistanceSideContactMotion() {
        // At the inclusive edge, SolidObject_ChkBounds computes d0=0 and
        // reaches SolidObject_AtEdge without entering StopCharacter. It sets
        // Status_Push but leaves x_pos, x_vel, and inertia unchanged
        // (docs/s2disasm/s2.asm:35338-35444).
        return true;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // Obj33 writes obj_control(a1)=1 while the player rides the rising lid
        // (s2.asm:49809/49837/49843), then still calls SolidObject from Obj33_Main.
        // SolidObject_ChkBounds rejects only negative object_control values
        // (s2.asm:35375-35377), so the positive capture state must keep support.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        // Obj33_Main still calls SolidObject after moving the lid
        // (s2.asm:49727-49740), but only positive object_control=1 riders pass
        // through SolidObject_ChkBounds. Signed bit-7 states such as Tails's
        // off-screen/flying obj_control=$81 branch to SolidObject_TestClearPush
        // before side/top classification (s2.asm:35344-35489).
        return player instanceof AbstractPlayableSprite;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid collision handled by ObjectManager
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.OOZ_BURNER_LID);
        if (renderer == null) return;
        // Single mapping frame (index 0)
        renderer.drawFrameIndex(0, x, currentY, false, false);
    }

    /**
     * Returns the current Y position of the platform lid.
     * Used by the flame child to determine visibility.
     */
    int getPlatformY() {
        return currentY;
    }
}
