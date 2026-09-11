package com.openggf.sprites.playable;

import com.openggf.camera.Camera;
import com.openggf.game.CanonicalAnimation;
import com.openggf.physics.Direction;

/**
 * Knuckles-specific respawn strategy: glides in from the screen edge opposite to the leader's
 * movement direction at a shallow angle, then drops when X-aligned with the leader (or after
 * a timeout).
 */
public class KnucklesRespawnStrategy implements SidekickRespawnStrategy {

    private static final int GLIDE_HORIZONTAL_SPEED = 4;
    private static final int GLIDE_DESCENT_SPEED = 1;
    private static final int GLIDE_X_THRESHOLD = 16;
    private static final int GLIDE_TIMEOUT_FRAMES = 180;
    private static final int SPAWN_Y_OFFSET = 192;

    private final int glideAnimId;

    private int approachFrameCount;
    private boolean dropping;

    public KnucklesRespawnStrategy(SidekickCpuController controller) {
        // controller not currently used; accepted for API consistency with other strategies
        this.glideAnimId = controller != null
                ? controller.resolveAnimationId(CanonicalAnimation.GLIDE_DROP)
                : -1;
    }

    @Override
    public boolean requiresPhysics() {
        return dropping;
    }

    /** Package-private: allows tests to trigger the drop phase directly. */
    void triggerDrop() {
        dropping = true;
    }

    @Override
    public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
        approachFrameCount = 0;
        dropping = false;

        Camera camera = sidekick.currentCamera();
        int cameraX = (camera != null) ? camera.getX() : leader.getCentreX();
        int edgeX;
        if (leader.getXSpeed() >= 0) {
            // Moving right (or stopped) — enter from left edge
            edgeX = cameraX - 32;
        } else {
            // Moving left — enter from right edge
            edgeX = cameraX + 320 + 32;
        }

        sidekick.setX((short) edgeX);
        sidekick.setY((short) (leader.getCentreY() - SPAWN_Y_OFFSET));

        sidekick.setAir(true);
        sidekick.setDead(false);
        sidekick.setHurt(false);

        if (leader.getCentreX() >= edgeX) {
            sidekick.setDirection(Direction.RIGHT);
        } else {
            sidekick.setDirection(Direction.LEFT);
        }

        sidekick.setControlLocked(true);
        ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
        sidekick.setXSpeed((short) 0);
        sidekick.setYSpeed((short) 0);
        sidekick.setGSpeed((short) 0);
        sidekick.setForcedAnimationId(glideAnimId);

        return true;
    }

    @Override
    public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                     int frameCounter) {
        approachFrameCount++;

        if (!dropping) {
            sidekick.setControlLocked(true);
            ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
            sidekick.setForcedAnimationId(glideAnimId);

            // Horizontal movement toward leader
            int sidekickCX = sidekick.getCentreX();
            int leaderCX = leader.getCentreX();
            if (leaderCX >= sidekickCX) {
                sidekick.setX((short) (sidekick.getX() + GLIDE_HORIZONTAL_SPEED));
                sidekick.setXSpeed((short) (GLIDE_HORIZONTAL_SPEED * 256));
                sidekick.setDirection(Direction.RIGHT);
            } else {
                sidekick.setX((short) (sidekick.getX() - GLIDE_HORIZONTAL_SPEED));
                sidekick.setXSpeed((short) (-GLIDE_HORIZONTAL_SPEED * 256));
                sidekick.setDirection(Direction.LEFT);
            }

            // Shallow descent
            sidekick.setY((short) (sidekick.getY() + GLIDE_DESCENT_SPEED));
            sidekick.setYSpeed((short) (GLIDE_DESCENT_SPEED * 256));

            // Check drop trigger: X-aligned with leader or timeout
            boolean xAligned = Math.abs(leaderCX - sidekick.getCentreX()) <= GLIDE_X_THRESHOLD;
            boolean timedOut = approachFrameCount >= GLIDE_TIMEOUT_FRAMES;
            if (xAligned || timedOut) {
                dropping = true;
                sidekick.setControlLocked(false);
                ObjectControlState.none().applyTo(sidekick);
                sidekick.setForcedAnimationId(-1);
                sidekick.setXSpeed((short) 0);
            }
        } else {
            // Physics engine handles gravity; wait for landing
            if (!sidekick.getAir()) {
                return true;
            }
        }

        return false;
    }
}
