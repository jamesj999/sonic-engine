package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Speed Booster (Object 0x1B).
 * Yellow arrow pads that boost Sonic's horizontal velocity when he runs over them.
 * Based on obj1B.asm from the Sonic 2 disassembly.
 */
public class SpeedBoosterObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Boost speed values from ROM (Obj1B_BoosterSpeeds)
    private static final int FAST_SPEED = 0x1000;
    private static final int SLOW_SPEED = 0x0A00;

    // Collision box half-size (32x32 total = ±16 from center)
    private static final int COLLISION_HALF_SIZE = 16;

    // Move lock duration (frames)
    private static final int MOVE_LOCK_FRAMES = 0x0F;

    // The boost speed for this instance (determined by subtype)
    private int boostSpeed;

    // Current mapping frame for rendering (toggles for blinking)
    private int mappingFrame = 0;

    public SpeedBoosterObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // ROM: bit 1 of subtype selects speed (0=fast/$1000, 2=slow/$A00)
        this.boostSpeed = (spawn.subtype() & 0x02) == 0 ? FAST_SPEED : SLOW_SPEED;
    }

    @Override
    public SpeedBoosterObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpeedBoosterObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Animation: Toggle between frame 0 (visible) and frame 2 (empty)
        // ROM: move.b (Level_frame_counter+1).w,d0 / andi.b #2,d0 / move.b d0,mapping_frame(a0)
        // This masks bit 1 directly, producing 0 or 2
        mappingFrame = vIntRunCount & 2;

        // ROM Obj1B_Main checks BOTH characters independently against the same
        // +/-$10 box and boosts each grounded one via Obj1B_GiveBoost:
        //   lea (MainCharacter).w,a1 ... bsr Obj1B_GiveBoost   (s2.asm:48179-48195)
        //   lea (Sidekick).w,a1     ... bsr Obj1B_GiveBoost    (s2.asm:48196-48210)
        // The engine previously only ran the box-check against the single main
        // player, so CPU Tails was never boosted inside the booster box. Model
        // the two ROM participation checks as "every active playable (main +
        // sidekicks)" via the player-query layer (no zone/route carve-out).
        if (player != null) {
            checkPlayerCollision(player);
        }
        for (PlayableEntity candidate :
                services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (candidate instanceof AbstractPlayableSprite candidateSprite && candidateSprite != player) {
                checkPlayerCollision(candidateSprite);
            }
        }
    }

    /**
     * Checks if player is within the 32x32 collision box and applies boost if grounded.
     */
    private void checkPlayerCollision(AbstractPlayableSprite player) {
        // ROM: btst #status.player.in_air,status(a1) / bne.s skip
        if (player.getAir()) {
            return; // Only activate when player is grounded
        }

        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses x_pos/y_pos which are CENTER positions
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM collision check: x_pos ±16, y_pos ±16
        int leftBound = objX - COLLISION_HALF_SIZE;
        int rightBound = objX + COLLISION_HALF_SIZE;
        int topBound = objY - COLLISION_HALF_SIZE;
        int bottomBound = objY + COLLISION_HALF_SIZE;

        if (playerX < leftBound || playerX >= rightBound) {
            return;
        }
        if (playerY < topBound || playerY >= bottomBound) {
            return;
        }

        // Player is within collision box - apply boost
        applyBoost(player);
    }

    /**
     * Applies the speed boost to the player.
     * Based on Obj1B_GiveBoost from s2.asm.
     */
    private void applyBoost(AbstractPlayableSprite player) {
        // ROM: Get player's X velocity and check direction
        int playerXVel = player.getXSpeed();
        boolean objectFlipped = isFlippedHorizontal();

        // ROM: Make velocity absolute for comparison
        int absVel = objectFlipped ? -playerXVel : playerXVel;

        // ROM: cmpi.w #$1000,d0 / bge.s Obj1B_GiveBoost_Done
        // Only boost if player's speed in boost direction is < 0x1000
        if (absVel >= 0x1000) {
            // Player already going fast enough, just play sound
            playBoostSound();
            return;
        }

        // ROM: Set X velocity to boost speed
        int newXVel = boostSpeed;

        // ROM: Set player direction and negate velocity if flipped
        if (objectFlipped) {
            // Flipped = boost left (negative velocity)
            player.setDirection(Direction.LEFT);
            newXVel = -newXVel;
        } else {
            // Not flipped = boost right (positive velocity)
            player.setDirection(Direction.RIGHT);
        }

        player.setXSpeed((short) newXVel);

        // ROM: move.w #$F,move_lock(a1) (s2.asm:48230) — set the move_lock
        // timer, NOT a spring flag. This is the field the sidekick CPU follow
        // logic respects (SidekickCpuController reads getMoveLockTimer()); the
        // previous setSpringing() was a no-op for CPU Tails, so a boosted
        // sidekick immediately resumed following the leader. For the main
        // character both fields block grounded input identically
        // (PlayableSpriteMovement line 382), so this is equivalent there.
        player.setMoveLockTimer(MOVE_LOCK_FRAMES);

        // ROM: move.w x_vel(a1),inertia(a1) (s2.asm:48231)
        player.setGSpeed((short) newXVel);

        // ROM: bclr #status.player.pushing,status(a1) (s2.asm:48234)
        player.setPushing(false);

        playBoostSound();
    }

    private void playBoostSound() {
        // ROM: move.w #SndID_Spring,d0 / jmp (PlaySound).l
        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SPEED_BOOSTER);
        if (renderer == null) return;

        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;

        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }
}
