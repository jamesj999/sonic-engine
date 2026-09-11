package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Shared Robotnik ship + head overlay used by the AIZ end boss.
 *
 * <p>ROM composition:
 * <ul>
 *   <li>{@code Child1_MakeRoboShip}: ship offset (0, -$14), mapping frame 8</li>
 *   <li>{@code Child1_MakeRoboHead2}: head offset (0, -$1C) from the ship, mapping frame 0</li>
 * </ul>
 *
 * <p>Defeat sequence (ROM: Obj_RobotnikShip routines 2-6):
 * <ol>
 *   <li>Boss defeated → ship keeps rendering at boss position while explosions play</li>
 *   <li>Phase 1 signal → ship changes to escape frame (5), head to defeated (3),
 *       flies upward at -$200 Y velocity for $7F frames</li>
 *   <li>After $7F frames → ship deletes itself</li>
 * </ol>
 */
public class AizEndBossShipChild extends AbstractBossChild implements RewindRecreatable {
    private static final int SHIP_Y_OFFSET = -0x14;
    private static final int HEAD_Y_OFFSET = -0x1C;

    // ROM: Map_RobotnikShip frames
    private static final int SHIP_FRAME_COMBAT = 0x08;   // subtype 8 — AIZ boss attachment
    private static final int SHIP_FRAME_ESCAPE = 0x05;   // ROM: loc_46110 move.b #5,mapping_frame
    private static final int HEAD_FRAME_HURT = 0x02;
    private static final int HEAD_FRAME_DEFEATED = 0x03;

    // ROM: loc_46110 — escape velocity and duration
    private static final int ESCAPE_INITIAL_Y_VEL = -0x200; // ROM: move.w #-$200,y_vel(a0)
    private static final int GRAVITY = 0x38;                 // ROM: MoveSprite addi.w #$38,y_vel(a0)
    private static final int ESCAPE_DURATION = 0x7F;         // ROM: move.w #$7F,$2E(a0)

    private static final int STATE_NORMAL = 0;
    private static final int STATE_DEFEAT_WAIT = 1;       // ROM: routine 4 (loc_460F8)
    private static final int STATE_DEFEAT_FLYING = 2;     // ROM: routine 6 (loc_4612A)
    private final AizEndBossInstance boss;
    private int headAnimTimer;
    private int headFrame;
    private int shipFrame;
    private int defeatState = STATE_NORMAL;
    private int flyTimer;

    // Position and velocity for independent movement during escape
    private int flyX;
    private int flyY;
    private int flySub;
    private int flyYVel;

    public AizEndBossShipChild(AizEndBossInstance boss) {
        super(boss, "AIZEndBossShip", 5, 0);
        this.boss = boss;
        this.headAnimTimer = 0;
        this.headFrame = 0;
        this.shipFrame = SHIP_FRAME_COMBAT;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null) {
            return null;
        }
        AizEndBossShipChild restored = new AizEndBossShipChild(restoredBoss);
        restoredBoss.rewindAttachShipChild(restored);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!shouldUpdate(vIntRunCount)) {
            return;
        }

        switch (defeatState) {
            case STATE_NORMAL -> updateNormal();
            case STATE_DEFEAT_WAIT -> updateDefeatWait();
            case STATE_DEFEAT_FLYING -> updateDefeatFlying();
        }
    }

    private void updateNormal() {
        setPosition(boss.getX(), boss.getY() + SHIP_Y_OFFSET);
        updateHeadFrame();

        // ROM: Obj_RobotnikShipMain checks btst #7,status(a1) for defeat
        if (boss.getState().defeated) {
            defeatState = STATE_DEFEAT_WAIT;
            headFrame = HEAD_FRAME_DEFEATED;
            // ROM: loc_460DC — the ship creates its own explosion controller here,
            // but in the engine the boss already manages the explosion controller.
        }
    }

    /**
     * ROM: routine 4 (loc_460F8) — wait for parent's phase 1 signal ($38 bit 4).
     * Keeps rendering at the boss position. Transitions to flying when the boss
     * signals phase 1 via FLAG_DEFEAT_STARTED.
     */
    private void updateDefeatWait() {
        setPosition(boss.getX(), boss.getY() + SHIP_Y_OFFSET);

        // ROM: btst #4,$38(a1) — check parent's defeat phase 1 flag.
        // In the engine, the boss sets FLAG_DEFEAT_STARTED immediately in onDefeatStarted(),
        // so this transitions right away. The brief wait ensures one frame of rendering
        // at the boss position before the escape begins.
        if (boss.isDefeatStarted()) {
            // ROM: loc_46110 — begin escape
            defeatState = STATE_DEFEAT_FLYING;
            shipFrame = SHIP_FRAME_ESCAPE;
            headFrame = HEAD_FRAME_DEFEATED;
            flyX = getX();
            flyY = getY();
            flySub = 0;
            flyYVel = ESCAPE_INITIAL_Y_VEL;
            flyTimer = ESCAPE_DURATION;
        }
    }

    /**
     * ROM: routine 6 (loc_4612A) — fly upward and count down.
     * Uses MoveSprite to apply y_vel each frame.
     */
    private void updateDefeatFlying() {
        // ROM: MoveSprite — apply 8:8 fixed-point velocity then add gravity
        int yPos24 = (flyY << 8) | (flySub & 0xFF);
        yPos24 += flyYVel;
        flyY = yPos24 >> 8;
        flySub = yPos24 & 0xFF;
        flyYVel += GRAVITY;  // ROM: addi.w #$38,y_vel(a0)

        setPosition(flyX, flyY);

        flyTimer--;
        if (flyTimer < 0) {
            setDestroyed(true);
        }
    }

    private void updateHeadFrame() {
        if (boss.getState().defeated) {
            headFrame = HEAD_FRAME_DEFEATED;
            return;
        }
        if (boss.getState().invulnerable) {
            headFrame = HEAD_FRAME_HURT;
            return;
        }
        headAnimTimer++;
        headFrame = (headAnimTimer / 6) & 1;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        // During normal combat, hide when the boss is submerged behind the waterfall.
        // During defeat, always render (ROM: the ship renders independently of boss visibility).
        if (defeatState == STATE_NORMAL && boss.isHidden()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = boss.isFacingRight();
        renderer.drawFrameIndex(shipFrame, getX(), getY(), hFlip, false);
        renderer.drawFrameIndex(headFrame, getX(), getY() + HEAD_Y_OFFSET, hFlip, false);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: ObjDat_RobotnikShip has make_art_tile(ArtTile_RobotnikShip,0,0) priority=0
        // normally, but during the AIZ boss the ship renders above the waterfall.
        // During defeat the ship must stay in front of high-priority tiles.
        if (defeatState != STATE_NORMAL) {
            return true;
        }
        return boss.isHighPriority();
    }
}
