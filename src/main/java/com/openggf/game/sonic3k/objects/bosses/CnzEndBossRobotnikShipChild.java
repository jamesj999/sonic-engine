package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code Child1_MakeRoboShip4 -> Obj_RobotnikShip4}, subtype {@code 9}. */
public final class CnzEndBossRobotnikShipChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int OFFSET_Y = -8;
    private static final int FRAME_NORMAL = 9;
    private static final int FRAME_DAMAGED = 0x0A;
    private static final int ESCAPE_SPEED = 0x300;
    private static final int ESCAPE_FRAMES = 0x100;

    private enum Routine { INIT, FOLLOW, WAIT_ESCAPE, RISE, ESCAPE }

    private final CnzEndBossInstance boss;
    private int centreX;
    private int centreY;
    private int xSubpixel;
    private int xVelocity;
    private int escapeTimer;
    private int frame = FRAME_NORMAL;
    private boolean facingRight;
    private Routine routine = Routine.INIT;
    private boolean explosionControllerSpawned;
    private boolean flameSpawned;
    private boolean bossFlagCleared;

    CnzEndBossRobotnikShipChild(CnzEndBossInstance boss) {
        super(new ObjectSpawn(boss.getCentreX(), boss.getCentreY() + OFFSET_Y,
                0, 9, 0, false, 0), "CNZEndBossRobotnikShip");
        this.boss = boss;
        followBoss();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossInstance restoredBoss = CnzEndBossRewindLinks.boss(ctx);
        return restoredBoss == null ? null : new CnzEndBossRobotnikShipChild(restoredBoss);
    }

    @Override public int getX() { return centreX - 0x20; }
    @Override public int getY() { return centreY - 0x20; }
    public int getCentreX() { return centreX; }
    public int getCentreY() { return centreY; }
    @Override public boolean isPersistent() { return true; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        switch (routine) {
            case INIT -> {
                followBoss();
                spawnChild(() -> new CnzEndBossRobotnikHeadChild(this));
                routine = Routine.FOLLOW;
            }
            case FOLLOW -> {
                followBoss();
                if (boss.defeatStarted()) {
                    spawnExplosionController();
                    routine = Routine.WAIT_ESCAPE;
                }
            }
            case WAIT_ESCAPE -> {
                followBoss();
                if (boss.shipEscapeSignalled()) {
                    frame = FRAME_DAMAGED;
                    xVelocity = 0;
                    xSubpixel = 0;
                    routine = Routine.RISE;
                }
            }
            case RISE -> updateRise();
            case ESCAPE -> updateEscape();
        }
        updateDynamicSpawn(centreX, centreY);
    }

    private void followBoss() {
        centreX = boss.getCentreX();
        centreY = boss.getCentreY() + OFFSET_Y;
        facingRight = boss.facingRight();
    }

    private void spawnExplosionController() {
        if (explosionControllerSpawned) return;
        explosionControllerSpawned = true;
        spawnChild(() -> new CnzEndBossExplosionControllerChild(this, 4));
    }

    private void updateRise() {
        int targetY = services().camera() == null
                ? 0x40
                : (services().camera().getY() & 0xFFFF) + 0x40;
        if (centreY > targetY) {
            centreY--;
            return;
        }
        centreY = targetY;
        facingRight = true;
        xVelocity = ESCAPE_SPEED;
        escapeTimer = ESCAPE_FRAMES;
        routine = Routine.ESCAPE;
        flameSpawned = true;
        spawnChild(() -> new CnzEndBossRobotnikFlameChild(this));
    }

    private void updateEscape() {
        xSubpixel += xVelocity;
        centreX += xSubpixel >> 8;
        xSubpixel &= 0xFF;
        escapeTimer--;
        if (escapeTimer >= 0) return;
        boss.clearBossFlagFromEscapingShip();
        bossFlagCleared = true;
        setDestroyed(true);
    }

    int frameForTest() { return frame; }
    boolean explosionControllerSpawnedForTest() { return explosionControllerSpawned; }
    boolean escapeStartedForTest() { return routine == Routine.ESCAPE; }
    boolean flameSpawnedForTest() { return flameSpawned; }
    boolean bossFlagClearedForTest() { return bossFlagCleared; }
    boolean isEscaping() { return routine == Routine.ESCAPE; }
    boolean isFacingRight() { return facingRight; }
    boolean parentHurt() { return boss.hurtStatusActive(); }
    boolean parentDefeated() { return boss.defeatStarted(); }

    @Override public int getPriorityBucket() { return 5; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer != null) {
            renderer.drawFrameIndexForcedPriority(
                    frame, centreX, centreY, facingRight, false, 0, true);
        }
    }
}
