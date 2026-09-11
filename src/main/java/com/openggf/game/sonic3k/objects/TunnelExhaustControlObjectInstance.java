package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * LBZ tunnel exhaust controller.
 *
 * <p>ROM reference: {@code Obj_TunnelExhaustControl}
 * ({@code sonic3k.asm:57461-57572}). The control object emits a small sprite
 * every four frames for 60 frames, then moves itself off-screen.
 *
 * <p>The emitted sprite depends on the act ({@code loc_298F4}): act 1 runs
 * {@code Obj_TunnelExSmoke} and puffs {@code Obj_FireShield_Dissipate} smoke out
 * of the tube exit, act 2 runs {@code Obj_TunnelExhaustControlMain} and sprays
 * the directional water exhaust.
 */
final class TunnelExhaustControlObjectInstance extends AbstractObjectInstance
        implements SpawnTrailingZeroIntsRewindRecreatable {
    private static final int EMIT_PERIOD = 4;
    private static final int LIFETIME = 60;
    private static final int OFFSCREEN_X = 0x7FF0;
    private static final int PRIORITY_BUCKET = 3; // ROM priority $180.
    private static final int ON_SCREEN_HALF_SIZE = 0x10;

    private int subtype;
    private int xVel;
    private int yVel;
    private int emitTimer;
    private int lifetime = LIFETIME;
    private int angle;
    /** Latched {@code loc_298F4} act branch: -1 unresolved, 1 smoke (act 1), 0 water exhaust (act 2). */
    private int smokeMode = -1;

    TunnelExhaustControlObjectInstance(ObjectSpawn spawn, int subtype, int xVel, int yVel) {
        super(spawn, "TunnelExhaustControl");
        this.subtype = subtype & 0xFF;
        this.xVel = xVel;
        this.yVel = yVel;
        this.angle = selectAngle(xVel, yVel);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (subtype != 0) {
            maybeEmitTimedExhaust(vIntRunCount);
            deleteIfNotInRange();
            return;
        }

        if (isSmokeMode()) {
            updateSmoke(vIntRunCount);
            return;
        }

        emitTimer--;
        if (emitTimer < 0) {
            emitTimer = EMIT_PERIOD - 1;
            ExhaustSpec spec = exhaustSpec();
            spawnChild(() -> new TunnelExhaustParticleInstance(
                    buildSpawnAt(getX(), getY()),
                    spec.xVel(),
                    spec.yVel(),
                    spec.mappingFrame(),
                    spec.renderFlags(),
                    spec.horizontal(),
                    false));
        }
        expireLifetime();
        deleteIfNotInRange();
    }

    /**
     * ROM {@code Obj_TunnelExSmoke} (act 1): puff a dissipating smoke sprite every
     * four frames, carrying the exit velocity the tunnel handed this controller.
     */
    private void updateSmoke(int vIntRunCount) {
        if ((vIntRunCount & 3) == 0) {
            int smokeXVel = xVel;
            int smokeYVel = yVel;
            spawnChild(() -> new FireShieldDissipateInstance(
                    buildSpawnAt(getX(), getY()), smokeXVel, smokeYVel));
        }
        expireLifetime();
        deleteIfNotInRange();
    }

    /** ROM: {@code subq.w #1,$30(a0); bpl.s +; move.w #$7FF0,x_pos(a0)}. */
    private void expireLifetime() {
        lifetime--;
        if (lifetime < 0) {
            updateDynamicSpawn(OFFSCREEN_X, getY());
        }
    }

    /** ROM {@code loc_298F4}: {@code tst.b (Current_act).w} — act 1 just makes smoke. */
    private boolean isSmokeMode() {
        if (smokeMode < 0) {
            smokeMode = services().currentAct() == 0 ? 1 : 0;
        }
        return smokeMode == 1;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Control object is not drawn by the ROM; it only emits exhaust children.
    }

    private void maybeEmitTimedExhaust(int vIntRunCount) {
        // ROM: move.b (Level_frame_counter+1).w,d0; andi.b #3,d0; bne.s skip
        // (Level_frame_counter+1) addresses the counter's low byte, it is not +1.
        if ((vIntRunCount & 3) == 0) {
            spawnChild(() -> new TunnelExhaustParticleInstance(buildSpawnAt(getX(), getY()), 0, 0x400, true));
        }
    }

    private void deleteIfNotInRange() {
        if (!isOnScreen(0x80)) {
            setDestroyedByOffscreen();
        }
    }

    int subtypeForTesting() {
        return subtype;
    }

    private static int selectAngle(int xVel, int yVel) {
        int signedYVel = (short) yVel;
        if (signedYVel != 0) {
            return signedYVel > 0 ? 0x06 : 0x00;
        }
        return (short) xVel < 0 ? 0x0C : 0x12;
    }

    private ExhaustSpec exhaustSpec() {
        return switch (angle) {
            case 0x00 -> new ExhaustSpec(0, -0x600, 0x86, 1, false);
            case 0x06 -> new ExhaustSpec(0, 0x400, 0x84, 1, false);
            case 0x0C -> new ExhaustSpec(horizontalVelocity(-0x600), 0, 0x85, 0, true);
            case 0x12 -> new ExhaustSpec(horizontalVelocity(0x600), 0, 0x84, 0, true);
            default -> throw new IllegalStateException("Unsupported tunnel exhaust angle " + angle);
        };
    }

    private int horizontalVelocity(int baseXVel) {
        int adjustment = (LIFETIME - lifetime) << 4;
        if (baseXVel > 0) {
            adjustment = -adjustment;
        }
        return (short) (baseXVel + adjustment);
    }

    private record ExhaustSpec(int xVel, int yVel, int renderFlags, int mappingFrame, boolean horizontal) {}
}

final class TunnelExhaustParticleInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int PRIORITY_BUCKET = 7; // ROM priority $380.
    private static final int ON_SCREEN_HALF_SIZE = 0x10;
    private static final int TIMED_LIFETIME = 0x0C;
    private static final int FALL_GRAVITY = 0x38;

    private final SubpixelMotion.State motion;
    private final boolean timed;
    private final boolean horizontal;
    private int timer;
    private int mappingFrame = 1;
    private int renderFlags = 0x84;

    TunnelExhaustParticleInstance(ObjectSpawn spawn, int xVel, int yVel, boolean timed) {
        this(spawn, xVel, yVel, 1, 0x84, false, timed);
    }

    TunnelExhaustParticleInstance(ObjectSpawn spawn, int xVel, int yVel,
            int mappingFrame, int renderFlags, boolean horizontal, boolean timed) {
        super(spawn, "TunnelExhaustParticle");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, xVel, yVel);
        this.mappingFrame = mappingFrame;
        this.renderFlags = renderFlags;
        this.horizontal = horizontal;
        this.timed = timed;
        this.timer = timed ? TIMED_LIFETIME - 1 : 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if ((vIntRunCount & 1) == 0) {
            renderFlags ^= horizontal ? 2 : 1;
        }
        if (timed) {
            timer--;
            if (timer < 0) {
                updateDynamicSpawn(0x7FF0, getY());
                return;
            }
        }
        SubpixelMotion.moveSprite(motion, FALL_GRAVITY);
        updateDynamicSpawn(motion.x, motion.y);
        if (!isOnScreen(0x80)) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_TUNNEL_EXHAUST);
        if (renderer != null) {
            boolean hFlip = (renderFlags & 1) != 0;
            boolean vFlip = (renderFlags & 2) != 0;
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip, 2);
        }
    }
}
