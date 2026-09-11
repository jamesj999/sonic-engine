package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.SidekickCpuController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * OOZ Launcher Ball / Transporter Ball (Object 0x48).
 * <p>
 * Captures the player inside a sphere, plays a rotation animation, then launches
 * the player in a cardinal direction. Multiple launcher balls chain together to
 * transport the player across the level.
 * <p>
 * Based on Obj48 from the Sonic 2 disassembly (s2.asm lines 50755-51024).
 * <p>
 * State machine per player:
 * <ul>
 *   <li>0 - DETECTION: Check 32x32 bounding box for player entry</li>
 *   <li>2 - ANIMATION: Play 8-frame open/close animation at 7 frames per step</li>
 *   <li>4 - MOVEMENT: Launch player in cardinal direction, close animation</li>
 *   <li>6 - COOLDOWN: Brief cooldown before returning to detection</li>
 * </ul>
 */
public class LauncherBallObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(LauncherBallObjectInstance.class.getName());

    // Player states (matches ROM objoff_2C/objoff_36 values)
    private static final int STATE_DETECTION = 0;
    private static final int STATE_ANIMATION = 2;
    private static final int STATE_MOVEMENT = 4;
    private static final int STATE_COOLDOWN = 6;

    // Animation timing (ROM: move.w #7,anim_frame_duration)
    private static final int ANIM_FRAME_DURATION = 7;
    // Faster closing animation during movement (ROM: move.w #1,anim_frame_duration)
    private static final int CLOSE_ANIM_FRAME_DURATION = 1;

    // Launch velocity (ROM: $1000 = 4096 subpixels/frame)
    private static final int LAUNCH_VELOCITY = 0x1000;

    // Detection box half-size (ROM: cmpi.w #$20,d0 after addi.w #$10)
    private static final int DETECTION_HALF_SIZE = 0x10;
    private static final int DETECTION_FULL_SIZE = 0x20;

    // Cooldown timer (ROM: move.w #7,objoff_3C)
    private static final int COOLDOWN_FRAMES = 7;

    // Launch animation close timer (ROM: move.w #3,anim_frame_duration at launch)
    private static final int LAUNCH_CLOSE_DURATION = 3;

    // Velocity table (ROM: word_25464)
    // Index 0=UP, 1=RIGHT, 2=DOWN, 3=LEFT
    private static final int[][] VELOCITY_TABLE = {
            {0, -LAUNCH_VELOCITY},      // UP
            {LAUNCH_VELOCITY, 0},       // RIGHT
            {0, LAUNCH_VELOCITY},       // DOWN
            {-LAUNCH_VELOCITY, 0}       // LEFT
    };

    // Properties table (ROM: Obj48_Properties)
    // Indexed by (subtype & 0xF) + (x_flip ? 4 : 0)
    // Each entry: {xFlip, yFlip, reverseAnim}
    private static final boolean[][] PROPERTIES = {
            {false, false, false},  // 0: frame 0, forward
            {false, true,  true},   // 1: frame 7, reverse
            {true,  true,  false},  // 2: frame 0, forward
            {true,  false, true},   // 3: frame 7, reverse
            {true,  false, false},  // 4: frame 0, forward
            {false, false, true},   // 5: frame 7, reverse
            {false, true,  false},  // 6: frame 0, forward
            {true,  true,  true},   // 7: frame 7, reverse
    };
    private static final int[] START_FRAMES = {0, 7, 0, 7, 0, 7, 0, 7};

    // Static registry to track which launcher currently holds each player.
    // When a new launcher captures a player, the previous launcher's state is cleared.
    private static final Map<AbstractPlayableSprite, LauncherBallObjectInstance> activeCaptures = new HashMap<>();

    // Per-player state tracking
    private final Map<AbstractPlayableSprite, Integer> playerStates = new HashMap<>();
    private final Map<AbstractPlayableSprite, int[]> playerVelocities = new HashMap<>();
    private final Map<AbstractPlayableSprite, Integer> playerCooldowns = new HashMap<>();

    // Object properties (computed from subtype at init)
    private boolean renderXFlip;
    private boolean renderYFlip;
    private boolean reverseAnim;    // objoff_3E: animation direction flag
    private int startFrame;         // objoff_3F: initial mapping frame

    // Current animation state (shared between both characters, matches ROM behavior)
    private int mappingFrame;
    private int animFrameDuration;

    public LauncherBallObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Determine properties index: (subtype & 0xF) + (x_flip ? 4 : 0)
        int subtype = spawn.subtype() & 0xFF;
        int propIndex = subtype & 0xF;
        boolean spawnXFlip = (spawn.renderFlags() & 0x01) != 0;
        if (spawnXFlip) {
            propIndex += 4;
        }
        propIndex = Math.min(propIndex, PROPERTIES.length - 1);

        renderXFlip = PROPERTIES[propIndex][0];
        renderYFlip = PROPERTIES[propIndex][1];
        reverseAnim = PROPERTIES[propIndex][2];
        startFrame = START_FRAMES[propIndex];
        mappingFrame = startFrame;
        animFrameDuration = 0;
    }

    @Override
    public LauncherBallObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LauncherBallObjectInstance(ctx.spawn(), "LauncherBall");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (!participants.contains(player)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(player);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }

        for (PlayableEntity participant : participants) {
            processPlayer((AbstractPlayableSprite) participant, vIntRunCount);
        }
    }

    private void processPlayer(AbstractPlayableSprite player, int vIntRunCount) {
        int state = playerStates.getOrDefault(player, STATE_DETECTION);
        switch (state) {
            case STATE_DETECTION -> processDetection(player, vIntRunCount);
            case STATE_ANIMATION -> processAnimation(player);
            case STATE_MOVEMENT -> processMovement(player);
            case STATE_COOLDOWN -> processCooldown(player);
        }
    }

    /**
     * State 0: Detection (ROM: loc_252F0).
     * Check 32x32 bounding box for player entry.
     */
    private void processDetection(AbstractPlayableSprite player, int vIntRunCount) {
        // Skip if debug mode
        if (player.isDebugMode()) {
            return;
        }

        // Skip if player is hurt or dead (ROM: cmpi.b #6,routine(a1); bhs)
        if (player.isHurt() || player.getDead()) {
            return;
        }

        // ROM Obj48 skips Sidekick only while Tails_CPU_routine == 4
        // (docs/s2disasm/s2.asm:51316-51319), not for every airborne CPU sidekick.
        if (isCpuSidekickInFlyingRoutine(player)) {
            return;
        }

        // Check 32x32 detection box centered on launcher
        // ROM: sub.w x_pos(a0),d0; addi.w #$10,d0; cmpi.w #$20,d0
        int dx = player.getCentreX() - spawn.x() + DETECTION_HALF_SIZE;
        int dy = player.getCentreY() - spawn.y() + DETECTION_HALF_SIZE;
        if (dx < 0 || dx >= DETECTION_FULL_SIZE || dy < 0 || dy >= DETECTION_FULL_SIZE) {
            return;
        }
        if (OOZLauncherObjectInstance.crossedIntoLauncherBallThisFrame(
                player, vIntRunCount, spawn.x(), spawn.y())) {
            return;
        }

        // If player is currently held by another launcher, clear that launcher's state
        OOZLauncherObjectInstance.clearActiveLauncherFor(player);
        LauncherBallObjectInstance previousLauncher = activeCaptures.get(player);
        if (previousLauncher != null && previousLauncher != this) {
            previousLauncher.playerStates.put(player, STATE_DETECTION);
            previousLauncher.playerVelocities.remove(player);
            previousLauncher.playerCooldowns.remove(player);
        }

        // If player is on another object, clear that relationship
        if (player.isOnObject()) {
            player.setOnObject(false);
        }

        // Register this launcher as holding the player
        activeCaptures.put(player, this);

        // Transition to animation state
        playerStates.put(player, STATE_ANIMATION);

        // Snap player to launcher position
        NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x());
        NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y());

        // Setup character state (ROM: move.b #$81,obj_control(a1)).
        // Obj48 does not write global Control_Locked; Obj01_Control keeps
        // refreshing Ctrl_1_Logical while obj_control owns movement.
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setGSpeed((short) 0x1000);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setAir(true);
        player.setOnObject(true);
        player.setLatchedSolidObject(spawn.objectId(), this);

        // Reset mapping frame to initial state
        mappingFrame = startFrame;
        animFrameDuration = 0;

        // Play rolling sound
        try {
            services().playSfx(GameSound.ROLLING);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    private boolean isCpuSidekickInFlyingRoutine(AbstractPlayableSprite player) {
        if (!player.isCpuControlled()) {
            return false;
        }
        SidekickCpuController controller = player.getCpuController();
        return controller != null && controller.getDiagnosticRomCpuRoutine() == 0x04;
    }

    /**
     * State 2: Animation (ROM: loc_253C6).
     * Animate 8 frames at 7 game-frames per animation step.
     */
    private void processAnimation(AbstractPlayableSprite player) {
        // Check if player died while captured
        if (player.isHurt() || player.getDead() || player.isDebugMode()) {
            releasePlayer(player);
            return;
        }

        if (!reverseAnim) {
            // Forward animation: 0 -> 7
            if (mappingFrame >= 7) {
                launchPlayer(player);
                return;
            }
            animFrameDuration--;
            if (animFrameDuration >= 0) {
                return;
            }
            animFrameDuration = ANIM_FRAME_DURATION;
            mappingFrame++;
            if (mappingFrame >= 7) {
                launchPlayer(player);
            }
        } else {
            // Reverse animation: 7 -> 0
            if (mappingFrame <= 0) {
                launchPlayer(player);
                return;
            }
            animFrameDuration--;
            if (animFrameDuration >= 0) {
                return;
            }
            animFrameDuration = ANIM_FRAME_DURATION;
            mappingFrame--;
            if (mappingFrame <= 0) {
                launchPlayer(player);
            }
        }
    }

    /**
     * Transition from animation to launch (ROM: loc_25408).
     * Calculate velocity direction and set player in motion.
     */
    private void launchPlayer(AbstractPlayableSprite player) {
        // Calculate velocity index
        // ROM: move.b subtype(a0),d0; addq.b #1,d0
        int subtype = spawn.subtype() & 0xFF;
        int d0 = (subtype & 0xF) + 1;
        boolean spawnXFlip = (spawn.renderFlags() & 0x01) != 0;
        if (spawnXFlip) {
            d0 -= 2;
        }
        d0 &= 3;

        int xVel = VELOCITY_TABLE[d0][0];
        int yVel = VELOCITY_TABLE[d0][1];

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
        playerVelocities.put(player, new int[]{xVel, yVel});

        animFrameDuration = LAUNCH_CLOSE_DURATION;

        // Check if this is an exit launcher (subtype bit 7 set = negative byte)
        if ((subtype & 0x80) != 0) {
            // Final launcher: release player to normal physics
            ObjectControlState.none().applyTo(player);
            player.setControlLocked(false);
            player.setAir(true);
            player.setOnObject(false);
            player.setJumping(false);

            // Enter cooldown state
            playerStates.put(player, STATE_COOLDOWN);
            playerCooldowns.put(player, COOLDOWN_FRAMES);

            activeCaptures.remove(player);
        } else {
            // Chaining: player stays on_object, continues to next launcher
            playerStates.put(player, STATE_MOVEMENT);
        }
    }

    /**
     * State 4: Movement (ROM: loc_25474).
     * Move player between launchers at launch velocity.
     */
    private void processMovement(AbstractPlayableSprite player) {
        // Check if player died while moving
        if (player.isHurt() || player.getDead() || player.isDebugMode()) {
            releasePlayer(player);
            return;
        }

        // Check if player is still on screen (ROM: btst #render_flags.on_screen,render_flags(a1))
        // If off-screen, release player and reset
        if (!isPlayerOnScreen(player)) {
            releasePlayer(player);
            return;
        }

        // Reverse-close animation while moving
        // ROM: only animate if neither character is in STATE_ANIMATION
        boolean anyAnimating = false;
        for (int state : playerStates.values()) {
            if (state == STATE_ANIMATION) {
                anyAnimating = true;
                break;
            }
        }

        if (!anyAnimating) {
            animFrameDuration--;
            if (animFrameDuration < 0) {
                animFrameDuration = CLOSE_ANIM_FRAME_DURATION;
                if (!reverseAnim) {
                    // Normal direction: close by decrementing toward 0
                    if (mappingFrame > 0) {
                        mappingFrame--;
                    }
                } else {
                    // Reverse direction: close by incrementing toward 7
                    if (mappingFrame < 7) {
                        mappingFrame++;
                    }
                }
            }
        }

        // Move player using velocity (ROM sub-pixel accumulation via move.l)
        // ROM: ext.l d0; asl.l #8,d0; add.l d0,x_pos(a1)
        // LAUNCH_VELOCITY = 0x1000, shifted right 8 = 0x10 = 16 pixels/frame
        int[] vel = playerVelocities.getOrDefault(player, new int[]{0, 0});
        player.move((short) vel[0], (short) vel[1]);
    }

    /**
     * State 6: Cooldown (ROM: loc_254F2).
     * Brief countdown before returning to detection.
     */
    private void processCooldown(AbstractPlayableSprite player) {
        int timer = playerCooldowns.getOrDefault(player, 0);
        timer--;
        if (timer < 0) {
            playerStates.put(player, STATE_DETECTION);
            playerCooldowns.remove(player);
            playerVelocities.remove(player);
        } else {
            playerCooldowns.put(player, timer);
        }
    }

    /**
     * Release player from this launcher (emergency release on death/debug/offscreen).
     */
    private void releasePlayer(AbstractPlayableSprite player) {
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);
        player.setAir(true);
        player.setOnObject(false);
        playerStates.put(player, STATE_DETECTION);
        playerVelocities.remove(player);
        playerCooldowns.remove(player);
        activeCaptures.remove(player);
    }

    /**
     * Check if a player is within the camera viewport.
     * ROM: btst #render_flags.on_screen,render_flags(a1)
     */
    private boolean isPlayerOnScreen(AbstractPlayableSprite player) {
        Camera camera = player.currentCamera();
        return camera == null || camera.isOnScreen(player);
    }

    /**
     * Whether any player is in a non-detection state (prevents off-screen removal).
     */
    private boolean hasActivePlayer() {
        for (int state : playerStates.values()) {
            if (state != STATE_DETECTION) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPersistent() {
        return hasActivePlayer();
    }

    @Override
    public void onUnload() {
        // Release any captured players when object is unloaded
        List<AbstractPlayableSprite> toRelease = new ArrayList<>();
        for (Map.Entry<AbstractPlayableSprite, Integer> entry : playerStates.entrySet()) {
            if (entry.getValue() != STATE_DETECTION) {
                toRelease.add(entry.getKey());
            }
        }
        for (AbstractPlayableSprite p : toRelease) {
            releasePlayer(p);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.LAUNCH_BALL);
        if (renderer == null) return;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), renderXFlip, renderYFlip);
    }

    /**
     * Clear the static capture registry. Call on level load to prevent stale references.
     */
    public static void clearActiveCaptures() {
        activeCaptures.clear();
        OOZLauncherObjectInstance.clearActiveLaunchers();
    }
}
