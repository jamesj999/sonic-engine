package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Boss Gunk - Falling hazard dropped from the container.
 * ROM Reference: s2.asm Obj5D (ROUTINE_GUNK = 0x0C)
 * Falls, hits ground, splashes into droplets, or sticks to boss.
 */
public class CPZBossGunk extends AbstractObjectInstance implements TouchResponseProvider, RewindRecreatable {

    private static final int SUB_INIT = 0;
    private static final int SUB_FALLING = 2;
    private static final int SUB_DROPLETS = 4;
    private static final int SUB_DELAY = 6;
    private static final int SUB_STUCK = 8;

    private static final int FLOOR_Y = 0x0518;
    private static final int COLLISION_FLAGS = 0x87;
    private static final int GRAVITY = 0x38;
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;
    private int renderFlags;
    private int routineSecondary;
    private int mappingFrame;
    private int yRadius;
    private int anim;
    private int timer2;
    private int animFrameDuration;
    private int collisionFlags;
    private boolean isDroplet;

    private ObjectAnimationState animationState;

    public CPZBossGunk(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss, boolean gunkReady) {
        super(spawn, "CPZ Boss Gunk");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.renderFlags = spawn.renderFlags();
        this.routineSecondary = SUB_INIT;
        this.mappingFrame = 0;
        this.yRadius = 0x20;
        this.anim = 0x19;
        this.yVel = 0;
        this.xVel = 0;
        this.collisionFlags = COLLISION_FLAGS;
        this.isDroplet = false;
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim

        if (gunkReady) {
            routineSecondary = SUB_DELAY;
            timer2 = 9;
        }
    }

    private CPZBossGunk(ObjectSpawn spawn) {
        this(spawn, null, false);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossGunk(ctx.spawn(), boss, false);
    }

    private CPZBossGunk(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss,
                        int x, int y, int xVel, int yVel, int renderFlags) {
        super(spawn, "CPZ Boss Gunk Droplet");
        this.mainBoss = mainBoss;
        this.x = x;
        this.y = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.xVel = xVel;
        this.yVel = yVel;
        this.renderFlags = renderFlags;
        this.routineSecondary = SUB_DROPLETS;
        this.mappingFrame = 9;
        this.yRadius = 4;
        this.collisionFlags = 0;
        this.isDroplet = true;
        this.animationState = null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (routineSecondary) {
            case SUB_INIT -> initGunk();
            case SUB_FALLING -> updateFalling();
            case SUB_DROPLETS -> updateDroplets();
            case SUB_DELAY -> updateDelay();
            case SUB_STUCK -> updateStuck();
        }
    }

    private void initGunk() {
        routineSecondary = SUB_FALLING;
        updateFalling();
    }

    private void updateFalling() {
        applyMoveAndFall();
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
        if (floor.hasCollision() && floor.distance() < 0) {
            y += floor.distance();
            if (mainBoss != null && !mainBoss.isBossDefeated()) {
                mainBoss.onGunkLanded();
            }
            routineSecondary = SUB_DROPLETS;
            isDroplet = false;
            mappingFrame = 9;  // Set droplet frame immediately to avoid stale frame flash
            services().playSfx(Sonic2Sfx.MEGA_MACK_DROP.id);
            return;
        }
        if (y >= FLOOR_Y) {
            gunkOffScreen();
            return;
        }
        animate();
    }

    private void updateDroplets() {
        if (!isDroplet) {
            // Initial droplet spawn
            y += 0x18;
            x += 0x0C;
            if ((renderFlags & 1) != 0) {
                x -= 0x18;
            }
            yRadius = 4;
            isDroplet = true;
            mappingFrame = 9;
            yVel = -(yVel >> 1);

            xVel = Sonic2Rng.nextCpzGunkMainXVelocity(services().rng());
            collisionFlags = 0;

            for (int i = 0; i < 4; i++) {
                spawnDroplet();
            }
        }

        updateDropletMove();
    }

    private void updateDropletMove() {
        applyMoveAndFall();
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
        if (floor.hasCollision() && floor.distance() < 0) {
            setDestroyed(true);
            return;
        }
        if (!isOnScreen(32)) {
            setDestroyed(true);
        }
    }

    private void updateDelay() {
        timer2--;
        if (timer2 < 0) {
            mappingFrame = 0x25;
            if (mainBoss != null) {
                x = mainBoss.getX();
                y = mainBoss.getY();
            }
            routineSecondary = SUB_STUCK;
            animFrameDuration = 8;
            updateStuck();
            return;
        }
        applyMove();
        animate();
    }

    private void updateStuck() {
        animFrameDuration--;
        if (animFrameDuration < 0) {
            mappingFrame++;
            animFrameDuration = 8;
            if (mappingFrame > 0x27) {
                gunkOffScreen();
                return;
            }
            if (mappingFrame == 0x27) {
                animFrameDuration += 0x0C;
            }
        }
        if (mainBoss != null) {
            x = mainBoss.getX();
            y = mainBoss.getY();
        }
    }

    private void gunkOffScreen() {
        if (mainBoss != null && !mainBoss.isBossDefeated()) {
            mainBoss.onGunkLanded();
        }
        setDestroyed(true);
    }

    private void spawnDroplet() {
        if (services().objectManager() == null) {
            return;
        }
        var velocity = Sonic2Rng.nextCpzGunkDropletVelocity(services().rng(), yVel);

        spawnChild(() -> new CPZBossGunk(spawn, mainBoss, x, y, velocity.xVel(), velocity.yVel(), renderFlags));
    }

    private void applyMove() {
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    private void applyMoveAndFall() {
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        yVel += GRAVITY;
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    private void animate() {
        if (animationState == null) {
            return;
        }
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    @Override
    protected boolean isOnScreen(int margin) {
        Camera camera = services().camera();
        int screenX = x - camera.getX();
        int screenY = y - camera.getY();
        return screenX >= -margin && screenX <= camera.getWidth() + margin
                && screenY >= -margin && screenY <= camera.getHeight() + margin;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, flipped, false, 3);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return 2;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
