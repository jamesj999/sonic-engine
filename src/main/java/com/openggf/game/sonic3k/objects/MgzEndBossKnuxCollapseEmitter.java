package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** Ports {@code loc_6D16A/loc_6D1A6}: ten camera-line collapse emissions. */
final class MgzEndBossKnuxCollapseEmitter extends AbstractObjectInstance
        implements RewindRecreatable {
    private int emissionsRemaining = 10;
    private boolean highBand;
    private int emissionTimer;

    MgzEndBossKnuxCollapseEmitter() { this(0, 0, false); }

    MgzEndBossKnuxCollapseEmitter(int x, int y, boolean highBand) {
        super(new ObjectSpawn(x, y, 0, highBand ? 1 : 0, 0, false, 0), "MGZKnuxCollapseEmitter");
        this.highBand = highBand;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (emissionsRemaining <= 0) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        if (emissionTimer-- > 0) return;
        emissionTimer = 3;
        emissionsRemaining--;
        int random = services().rng().nextRaw();
        int low = random & 0xFFFF;
        int high = random >>> 16;
        int frame = switch (low & 3) { case 1 -> 1; case 2 -> 2; default -> 0; };
        int xVel = low & 0x3FF;
        if ((low & 1) != 0) xVel = -xVel;
        int yVel = 0;
        if (highBand) {
            yVel = (high & 0x1FF) + 0x200;
            if ((yVel & 1) != 0) yVel = -yVel;
        }
        int finalXVel = xVel;
        int finalYVel = yVel;
        spawnChild(() -> new Particle(getX(), getY(), finalXVel, finalYVel, frame));
    }

    @Override public void appendRenderCommands(List<GLCommand> commands) { }
    int emissionsRemainingForTesting() { return emissionsRemaining; }

    @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MgzEndBossKnuxCollapseEmitter(ctx.spawn().x(), ctx.spawn().y(), ctx.spawn().subtype() != 0);
    }

    static final class Particle extends AbstractObjectInstance implements RewindRecreatable {
        private int xFixed, yFixed, xVel, yVel, frame;
        Particle() { this(0, 0, 0, 0, 0); }
        Particle(int x, int y, int xVel, int yVel, int frame) {
            super(new ObjectSpawn(x, y, 0, frame, 0, false, 0), "MGZKnuxCollapseParticle");
            this.xFixed = x << 8; this.yFixed = y << 8;
            this.xVel = xVel; this.yVel = yVel; this.frame = frame;
        }
        @Override public void update(int vIntRunCount, PlayableEntity player) {
            xFixed += xVel; yFixed += yVel; yVel += 0x18;
            if (getY() > services().camera().getY() + 0x140) ObjectLifetimeOps.expireDynamic(this);
        }
        @Override public int getX() { return xFixed >> 8; }
        @Override public int getY() { return yFixed >> 8; }
        @Override public int getPriorityBucket() { return 2; }
        @Override public boolean isHighPriority() { return true; }
        @Override public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS_DEBRIS);
            if (renderer != null && renderer.isReady()) renderer.drawFrameIndex(frame, getX(), getY(), false, false);
        }
        @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new Particle(ctx.spawn().x(), ctx.spawn().y(), 0, 0, ctx.spawn().subtype());
        }
    }
}
