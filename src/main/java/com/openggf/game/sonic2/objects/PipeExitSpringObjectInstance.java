package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.SpringHelper;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;

import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.physics.Direction;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpringBounceHelper;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Pipe Exit Spring (Object 0x7B)
 *
 * A vertical spring used at warp tube exits in Chemical Plant Zone.
 * Launches the player upward when they stand on it.
 *
 * ROM Reference: Obj7B in s2.asm lines 55838-56015
 *
 * Subtype bits:
 * - Bit 1 (0x02): Strength - 0=full (-0x1000), 1=reduced (-0xA80)
 * - Bit 7 (0x80): If set, clears X velocity when launched
 * - Bits 0, 2-3: Rotation/flip physics and collision response (rarely used)
 *
 * Tube proximity animation (s2.asm lines 55907-55948):
 * When the player is inside the tube BELOW the spring (within X ± 16,
 * Y range spring.y to spring.y + 48), the spring plays animation 2
 * which shows the spring in a raised/compressed position (frames 1,2,2,2,4).
 * This creates the visual effect of the spring moving out of the way
 * as the player passes underneath through the tube.
 */
public class PipeExitSpringObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // Animation IDs matching Ani_obj7B in the disassembly
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_TRIGGERED = 1;
    private static final int ANIM_RAISED = 2;  // Spring raised when player is below in tube

    // Proximity detection bounds (ROM: lines 55908-55914)
    private static final int PROXIMITY_X_RANGE = 0x10;  // ± 16 pixels from center
    private static final int PROXIMITY_Y_BELOW = 0x30;  // 48 pixels below spring

    private boolean fullStrength;
    private ObjectAnimationState animationState;
    private int mappingFrame;
    private boolean initialized;

    public PipeExitSpringObjectInstance(ObjectSpawn spawn, String name) {
        // ROM: width_pixels = 0x10 (16), priority = 1
        super(spawn, name, 16, 16, 0.0f, 0.8f, 0.2f, false);
        // ROM: bit 1 of subtype: 0=full strength (-$1000), 1=reduced (-$A80)
        this.fullStrength = (spawn.subtype() & 0x02) == 0;
        this.mappingFrame = 0;
    }

    @Override
    public PipeExitSpringObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PipeExitSpringObjectInstance(ctx.spawn(), getName());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getAnimations(Sonic2ObjectArtKeys.ANIM_PIPE_EXIT_SPRING) : null,
                ANIM_IDLE,
                mappingFrame);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        // Prevent re-triggering while player is already springing
        if (player.getSpringing()) {
            return;
        }

        // ROM: Only trigger when player is standing on the spring
        if (!contact.standing()) {
            return;
        }

        applySpring(player);
    }

    /**
     * ROM: loc_296C2 - Spring launch logic
     * - addq.w #4,y_pos(a1) - adjust player Y by 4 (push down in Y-down coords)
     * - move.w objoff_30(a0),y_vel(a1) - set Y velocity to spring strength
     * - bset #status.player.in_air - put player in air
     * - bclr #status.player.on_object - remove object contact
     * - move.b #AniIDSonAni_Spring,anim(a1) - set spring animation
     */
    private void applySpring(AbstractPlayableSprite player) {
        // ROM: addq.w #4,y_pos(a1) - push player down slightly before velocity kicks in
        // This prevents immediate re-collision with the spring
        player.setY((short) (player.getY() + 4));

        // ROM: move.w objoff_30(a0),y_vel(a1) - negative = up
        player.setYSpeed((short) getStrength());

        // ROM: bset #status.player.in_air
        player.setAir(true);
        // ROM: bclr #status.player.on_object,status(a1) (s2.asm:56461). The launch
        // detaches the character from the spring in the same frame SolidObject set
        // the standing bit, so status must not still carry on_object on the bounce
        // frame (seg8_cpz1 f2700/f2797: rom 0x03, engine was 0x0B).
        player.setOnObject(false);
        // ROM loc_296C2 (s2.asm:56394-56435) never writes inertia in the non-twirl
        // path. Do NOT zero g_speed here - the landing-frame inertia must be preserved
        // exactly as the ROM does (e.g. CPZ pipe-exit f2038: inertia stays -03B4).

        // ROM: bit 7 of subtype - if set, clear X velocity
        if ((spawn.subtype() & 0x80) != 0) {
            player.setXSpeed((short) 0);
        }

        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);

        // ROM: move.b #AniIDSonAni_Spring,anim(a1) - Set Spring animation first
        player.setAnimationId(Sonic2AnimationIds.SPRING);

        // ROM: Animation override based on subtype bit 0 (twirl flag)
        int subtype = spawn.subtype();
        if ((subtype & 0x01) != 0) {
            // ROM: loc_29967-29979 - Twirl animation setup
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setFlipAngle(1);
            player.setFlipSpeed(4);
            // ROM: bit 1 controls flip count - 1 flip if clear, 0 flips if set
            player.setFlipsRemaining((subtype & 0x02) != 0 ? 0 : 1);

            // ROM: move.w #1,inertia(a1) - Set inertia for twirl
            short inertia = 1;

            // ROM: Negate flip_angle and inertia if player facing left
            if (player.getDirection() == Direction.LEFT) {
                player.setFlipAngle(-player.getFlipAngle());
                inertia = -1;  // ROM: neg.w inertia(a1)
            }
            player.setGSpeed(inertia);
        }
        // ROM: non-twirl path (beq.s loc_29736 at s2.asm:56407) jumps straight to the
        // collision-bit setup at loc_29736 (s2.asm:56422-56435) and leaves inertia
        // untouched - identical to the regular vertical spring Obj41 (s2.asm:33801+).
        // Do NOT clear g_speed in the non-twirl branch.

        // ROM: loc_29736-29768 - Set collision layer based on subtype bits 2-3
        SpringHelper.applyCollisionLayerBits(player, subtype);

        trigger();
    }

    private void trigger() {
        animationState.setAnimId(ANIM_TRIGGERED);
        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    /**
     * ROM: Obj7B_Strengths equivalent
     * - Full strength: -$1000 (used for most tube exits)
     * - Reduced strength: -$A80 (lower bounce)
     */
    private int getStrength() {
        return fullStrength ? SpringBounceHelper.STRENGTH_RED : -0x0A80;
    }

    /**
     * Make spring non-solid when in raised state or player is already springing.
     * This prevents ceiling collision from zeroing Y velocity immediately after launch,
     * and allows Sonic to pass through when the spring is raised out of the way.
     */
    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: cmpi.b #1,mapping_frame(a0) / beq.s loc_29648
        // Skip collision when spring is in raised position (frames 1 and 2)
        // Both frames have Y offset -32 (raised 16 pixels higher than normal)
        // This prevents the spring from activating as Sonic passes underneath in the tube
        // The raised animation (ANIM_RAISED) cycles: 1, 2, 2, 2, 4 - so we need both
        if (mappingFrame == 1 || mappingFrame == 2) {
            return false;
        }
        if (player != null && player.getSpringing()) {
            return false;
        }
        return true;
    }

    /**
     * ROM collision params:
     * D1=$1B (27), D2=8, D3=$10 (16)
     * SolidObject_Always_SingleCharacter with these dimensions
     */
    @Override
    public SolidObjectParams getSolidParams() {
        // Width radius = 27, height (air) = 8, height (ground) = 8
        return SolidObjectParams.of(27, 8, 8);
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // ROM Obj7B reaches SolidObject_cont through
        // SolidObject_Always_SingleCharacter (s2.asm:56335/56343), bypassing the
        // SolidObject_OnScreenTest render_flags(a0) gate at s2.asm:35330-35336.
        // Off-screen pipe-exit springs still resolve solid contact in ROM.
        // Required so enabling CollisionRules.solidObjectOffscreenGate for S2
        // keeps OOZ pipe-exit launches behaving as before.
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: loc_29648 - Check if player is in the tube below the spring
        // If so, play the raised animation to move the spring out of the way
        if (player != null && isPlayerInTubeBelow(player)) {
            // Only switch to raised animation if not already playing it
            // ROM: cmpi.b #2,prev_anim(a0) / beq.s loc_29686
            if (animationState.getAnimId() != ANIM_RAISED) {
                animationState.setAnimId(ANIM_RAISED);
            }
        }

        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    /**
     * ROM: loc_29648-29684 - Check if player is within the tube detection zone
     * Detection box: X ± 16, Y from spring.y to spring.y + 48 (below the spring)
     */
    private boolean isPlayerInTubeBelow(AbstractPlayableSprite player) {
        int springX = spawn.x();
        int springY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM: d4 = x_pos - $10, d5 = x_pos + $10
        int xMin = springX - PROXIMITY_X_RANGE;
        int xMax = springX + PROXIMITY_X_RANGE;

        // ROM: d2 = y_pos, d3 = y_pos + $30
        int yMin = springY;
        int yMax = springY + PROXIMITY_Y_BELOW;

        // ROM: cmp.w d4,d0 / blo.s (if player.x < xMin, skip)
        // ROM: cmp.w d5,d0 / bhs.s (if player.x >= xMax, skip)
        // ROM: cmp.w d2,d0 / blo.s (if player.y < yMin, skip)
        // ROM: cmp.w d3,d0 / bhs.s (if player.y >= yMax, skip)
        return playerX >= xMin && playerX < xMax && playerY >= yMin && playerY < yMax;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.PIPE_EXIT_SPRING);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        // No flip support - pipe exit springs are always upright
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #1,priority(a0) - lower priority than regular springs (4)
        return RenderPriority.clamp(1);
    }
}
