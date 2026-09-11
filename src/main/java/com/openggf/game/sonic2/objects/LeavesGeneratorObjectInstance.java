package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Leaves Generator (Object 0x2C).
 * Invisible trigger that spawns 4 falling leaf particles when the player
 * passes through at sufficient speed.
 * <p>
 * Based on Obj2C from s2.asm (docs/s2disasm/s2.asm:52030-52242).
 * <p>
 * Behavior:
 * - Invisible collision trigger (no visual rendering)
 * - Subtypes 0/1/2 select different collision box sizes
 * - Only triggers when player speed >= 0x200 in X or Y axis
 * - Spawns 4 LeafParticleObjectInstance children at player position
 * - Uses Obj2C's collision_property bits and objoff_2E frame phase
 */
public class LeavesGeneratorObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    // Minimum speed required to trigger leaves (0x200 in either axis)
    private static final int MIN_TRIGGER_SPEED = 0x200;

    // Obj2C_CollisionFlags (docs/s2disasm/s2.asm:52046-52049).
    private static final int[] COLLISION_FLAGS = {0xD6, 0xD4, 0xD5};

    private static final int MAIN_TOUCH_BIT = 0x01;
    private static final int SIDEKICK_TOUCH_BIT = 0x02;

    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY,
            true,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // Velocity table for leaf particles (8.8 fixed point values)
    // ROM: Obj2C_Speeds (docs/s2disasm/s2.asm:52032-52037).
    private static final int[][] LEAF_VELOCITIES = {
            {-0x80, -0x80},  // Leaf 0: top-left
            {0xC0, -0x40},   // Leaf 1: top-right
            {-0xC0, 0x40},   // Leaf 2: bottom-left
            {0x80, 0x80}     // Leaf 3: bottom-right
    };

    private int collisionFlags;
    private int collisionProperty;
    private int touchPhaseFrame;

    public LeavesGeneratorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        int subtype = spawn.subtype() & 0x03;
        if (subtype >= COLLISION_FLAGS.length) {
            subtype = 0;
        }
        this.collisionFlags = COLLISION_FLAGS[subtype];
    }

    @Override
    public LeavesGeneratorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LeavesGeneratorObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        processTouchLatch(levelFrameCounter(vIntRunCount));
    }

    /**
     * Checks if player has the minimum speed to trigger leaves.
     * ROM: mvabs.w x_vel(a2),d0 / cmpi.w #$200,d0 (and same for y_vel)
     */
    private boolean hasMinimumSpeed(AbstractPlayableSprite player) {
        int absXSpeed = Math.abs(player.getXSpeed());
        int absYSpeed = Math.abs(player.getYSpeed());

        return absXSpeed >= MIN_TRIGGER_SPEED || absYSpeed >= MIN_TRIGGER_SPEED;
    }

    private void processTouchLatch(int frameCounter) {
        int pending = collisionProperty & 0xFF;
        if (pending == 0) {
            touchPhaseFrame = 0;
            return;
        }

        int phase = 0;
        if (touchPhaseFrame != 0) {
            phase = (touchPhaseFrame + frameCounter) & 0x0F;
            if (phase != 0) {
                phase = (phase + 8) & 0x0F;
                if (phase == 0) {
                    AbstractPlayableSprite sidekick = nativeSidekick();
                    if ((pending & SIDEKICK_TOUCH_BIT) != 0 && sidekick != null) {
                        maybeSpawnLeaves(sidekick, frameCounter);
                    }
                }
                collisionProperty = 0;
                return;
            }
        }

        AbstractPlayableSprite main = mainPlayer();
        if ((pending & MAIN_TOUCH_BIT) != 0 && main != null) {
            maybeSpawnLeaves(main, frameCounter);
        }
        collisionProperty = 0;
    }

    private void maybeSpawnLeaves(AbstractPlayableSprite player, int frameCounter) {
        if (!hasMinimumSpeed(player)) {
            touchPhaseFrame = 0;
            return;
        }
        spawnLeaves(player);
        if (touchPhaseFrame == 0) {
            touchPhaseFrame = frameCounter;
        }
    }

    private int levelFrameCounter(int vIntRunCount) {
        var levelManager = services().levelManager();
        // Obj2C_Main reads the ROM Level_frame_counter during object execution
        // (s2.asm:52090, 52109, 52116). LevelManager still stores the previous
        // completed frame until its late-frame update, so object code observes
        // the next visible level counter here.
        return levelManager != null ? levelManager.getFrameCounter() : vIntRunCount;
    }

    private AbstractPlayableSprite mainPlayer() {
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        return main instanceof AbstractPlayableSprite playable ? playable : null;
    }

    private AbstractPlayableSprite nativeSidekick() {
        PlayableEntity sidekick = services().playerQuery().nativeP2OrNull();
        return sidekick instanceof AbstractPlayableSprite playable ? playable : null;
    }

    /**
     * Spawns 4 leaf particles at the player's position.
     * ROM: Obj2C_CreateLeaves (docs/s2disasm/s2.asm:52126-52182).
     */
    private void spawnLeaves(AbstractPlayableSprite player) {
        if (services().objectManager() == null) {
            return;
        }

        boolean playerFacingLeft = player.getDirection() == Direction.LEFT;
        var rng = services().rng();

        for (int i = 0; i < 4; i++) {
            // Random ±8 pixel offset from player position
            // ROM: andi.w #$F,d0 / subq.w #8,d0
            var leafRandom = Sonic2Rng.nextLeafSpawn(rng);
            int offsetX = leafRandom.offsetX();
            int offsetY = leafRandom.offsetY();

            int leafX = player.getCentreX() + offsetX;
            int leafY = player.getCentreY() + offsetY;

            // Get velocity for this leaf
            int xVel = LEAF_VELOCITIES[i][0];
            int yVel = LEAF_VELOCITIES[i][1];

            // Negate X velocity if player facing left
            // ROM: btst #status.player.x_flip,status(a2) / neg.w x_vel(a1)
            if (playerFacingLeft) {
                xVel = -xVel;
            }

            // Random initial frame (0 or 1)
            // ROM: andi.b #1,d0 / move.b d0,mapping_frame(a1)
            int initialFrame = leafRandom.mappingFrame();

            // REV01 FixBugs=0 typo: Obj2C writes RandomNumber's d1 to
            // angle(a0), the parent, not angle(a1), the child
            // (docs/s2disasm/s2.asm:52169-52177). Child angle therefore
            // starts at zero.
            int initialAngle = 0;

            int finalXVel = xVel;
            spawnFreeChild(() -> new LeafParticleObjectInstance(
                    leafX, leafY, finalXVel, yVel, initialFrame, initialAngle));
        }

        // Play leaves sound
        playLeavesSound();
    }

    private void playLeavesSound() {
        try {
            services().playSfx(Sonic2Sfx.LEAVES.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean usesSonic2TouchSpecialPropertyResponse() {
        return true;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2 Touch_Loop scans collision_flags directly; Obj2C consumes
        // collision_property in its own routine (s2.asm:52086-52117).
        return false;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (playerEntity instanceof AbstractPlayableSprite sprite && sprite.isCpuControlled()) {
            collisionProperty |= SIDEKICK_TOUCH_BIT;
        } else {
            collisionProperty |= MAIN_TOUCH_BIT;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object - no rendering
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ROM: move.b #4,priority(a0)
    }
}
