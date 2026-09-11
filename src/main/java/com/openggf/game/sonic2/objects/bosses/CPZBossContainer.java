package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Boss Container - Swinging container that drops gunk.
 * ROM Reference: s2.asm Obj5D (ROUTINE_CONTAINER = 0x10)
 * Swings back and forth, triggers gunk drops.
 */
public class CPZBossContainer extends AbstractObjectInstance implements RewindRecreatable {

    private static final int SUB_INIT = 0;
    private static final int SUB_MAIN = 2;
    private static final int SUB_FLOOR = 4;
    private static final int SUB_EXTEND = 6;
    private static final int SUB_FLOOR2 = 8;
    private static final int SUB_FALLOFF = 0x0A;

    private static final int CONTAINER_OFFSET_Y = 0x38;
    private static final int CONTAINER_INIT_XVEL = -0x10;
    private static final int[] CONTAINER_FALLOFF_X_OFFSETS = {0x18, 0x30, 0x48};
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int renderFlags;
    private int routineSecondary;
    private int anim;
    private int mappingFrame;
    private int xVel;
    private int timer2;
    private boolean containerInitDone;
    private boolean floorSpawned;
    private boolean extendSpawned;

    private ObjectAnimationState animationState;

    public CPZBossContainer(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss) {
        super(spawn, "CPZ Boss Container");
        this.mainBoss = mainBoss;
        this.x = spawn.x() - 0x10;
        this.y = spawn.y() - CONTAINER_OFFSET_Y;
        this.renderFlags = spawn.renderFlags();
        this.routineSecondary = SUB_INIT;
        this.anim = 6;
        this.mappingFrame = 0;
        this.xVel = CONTAINER_INIT_XVEL;
        this.timer2 = 0;
        this.containerInitDone = false;
        this.floorSpawned = false;
        this.extendSpawned = false;
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossContainer(ctx.spawn(), boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (routineSecondary) {
            case SUB_INIT -> updateInit();
            case SUB_MAIN -> updateMain(player);
            case SUB_FALLOFF -> updateFallOff();
        }
    }

    private void updateInit() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (!containerInitDone) {
            containerInitDone = true;
            spawnContainerFloor();
            spawnContainerExtend();
        }
        routineSecondary = SUB_MAIN;
    }

    private void updateMain(AbstractPlayableSprite player) {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        x = mainBoss.getX();
        y = mainBoss.getY() - CONTAINER_OFFSET_Y;

        if (mainBoss.isBossDefeated()) {
            routineSecondary = SUB_FALLOFF;
            return;
        }

        if (mainBoss.isContainerMoving()) {
            updateContainerMovement();
            updateContainerDropTrigger(player);
        } else if (mainBoss.isContainerDumping()) {
            timer2--;
            if (timer2 == 0) {
                mainBoss.onContainerDumpComplete();
            }
        }

        updateContainerPosition();
    }

    private void updateContainerMovement() {
        int direction = mainBoss.isContainerReturning() ? 1 : -1;

        // Check for reset at initial position
        if (xVel == CONTAINER_INIT_XVEL && mainBoss.isContainerReturning()) {
            mainBoss.onContainerCycleComplete();
            routineSecondary = SUB_INIT;
            containerInitDone = false;
            floorSpawned = false;   // Reset so new floor can spawn for next cycle
            extendSpawned = false;  // Reset so new extend (liquid fill) can spawn for next cycle
            return;
        }

        // Acceleration code
        if (xVel >= -0x28) {
            anim = 6;
        } else if (xVel >= -0x40) {
            anim = 7;
        } else {
            anim = 8;
        }

        // Cap speed at -0x58
        if (xVel <= -0x58) {
            if (!mainBoss.isContainerReturning()) {
                return; // At cap and not reversing
            }
        }
        xVel += direction;
    }

    private void updateContainerDropTrigger(AbstractPlayableSprite player) {
        if (mainBoss.isContainerDropTriggered() || mainBoss.isContainerReturning()) {
            return;
        }

        if (xVel >= -0x14) {
            if (mainBoss.wasJustHit()) {
                mainBoss.clearHitFlag();
                mainBoss.setGunkReady(true);
                startContainerDump();
            }
            return;
        }

        if (xVel >= -0x40) {
            return;
        }

        int playerX = player != null ? player.getCentreX() - 8 : x;
        if ((renderFlags & 1) != 0) {
            playerX += xVel;
            playerX -= x;
            if (playerX > 0) {
                return;
            }
            if (playerX >= -0x18) {
                startContainerDump();
            }
        } else {
            playerX -= xVel;
            playerX -= x;
            if (playerX < 0) {
                return;
            }
            if (playerX <= 0x18) {
                startContainerDump();
            }
        }
    }

    private void startContainerDump() {
        mainBoss.onContainerDumpStart();
        timer2 = 0x12;
        spawnContainerFloor2();
    }

    private void updateContainerPosition() {
        renderFlags = mainBoss.getRenderFlags();

        int offset = xVel;
        if ((renderFlags & 1) != 0) {
            offset = -offset;
        }
        x += offset;
        animate();
    }

    private void updateFallOff() {
        if (mainBoss == null) {
            setDestroyed(true);
            return;
        }

        x = mainBoss.getX();
        y = mainBoss.getY() - CONTAINER_OFFSET_Y;
        int offset = xVel;
        if ((renderFlags & 1) != 0) {
            offset = -offset;
        }
        x += offset;
        mappingFrame = 0x20;

        // Spawn falling pieces
        int count;
        if (xVel >= -0x18) {
            count = 0;
        } else if (xVel >= -0x30) {
            count = 1;
        } else if (xVel >= -0x48) {
            count = 2;
        } else {
            count = 3;
        }

        for (int i = count - 1; i >= 0; i--) {
            spawnContainerPiece(CONTAINER_FALLOFF_X_OFFSETS[i]);
        }

        // Become a falling part ourselves
        var myMotion = randomPipeMotion();
        ObjectSpawn partSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossFallingPart(partSpawn, 0x20, myMotion.xVel(), myMotion.timer()));
        setDestroyed(true);
    }

    private void spawnContainerFloor() {
        if (floorSpawned || services().objectManager() == null) {
            return;
        }
        floorSpawned = true;
        ObjectSpawn floorSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossContainerFloor(floorSpawn, mainBoss, this, false));
    }

    private void spawnContainerExtend() {
        if (extendSpawned || services().objectManager() == null) {
            return;
        }
        extendSpawned = true;
        ObjectSpawn extendSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossContainerExtend(extendSpawn, mainBoss, this));
    }

    private void spawnContainerFloor2() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn floorSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossContainerFloor(floorSpawn, mainBoss, this, true));
    }

    private void spawnContainerPiece(int offset) {
        if (services().objectManager() == null) {
            return;
        }
        int pieceX = x + ((renderFlags & 1) != 0 ? -offset : offset);
        ObjectSpawn pieceSpawn = new ObjectSpawn(pieceX, y + 8, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        var motion = randomPipeMotion();
        spawnChild(() -> new CPZBossFallingPart(pieceSpawn, 0x21, motion.xVel(), motion.timer()));
    }

    private Sonic2Rng.PipeShardMotion randomPipeMotion() {
        return Sonic2Rng.nextPipeShardMotion(services().rng());
    }

    private void animate() {
        if (animationState == null) {
            return;
        }
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    public int getContainerX() {
        return x;
    }

    public int getContainerY() {
        return y;
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
        renderer.drawFrameIndex(mappingFrame, x, y, flipped, false);
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
        return 4;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
