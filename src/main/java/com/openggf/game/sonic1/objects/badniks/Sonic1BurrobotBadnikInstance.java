package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Badnik 0x2D - Burrobot (LZ).
 * <p>
 * ROM reference: docs/s1disasm/_incObj/2D Burrobot.asm
 */
public class Sonic1BurrobotBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x05;
    private static final int Y_RADIUS = 0x13;

    private static final int WALK_SPEED = 0x80;
    private static final int JUMP_VELOCITY = -0x400;
    private static final int GRAVITY = 0x18;

    // Burro .index states (ob2ndRout / 2).
    private static final int STATE_CHANGEDIR = 0;
    private static final int STATE_MOVE = 1;
    private static final int STATE_JUMP = 2;
    private static final int STATE_CHECK_SONIC = 3;

    // Ani_Burro animation IDs.
    private static final int ANIM_WALK1 = 0;
    private static final int ANIM_WALK2 = 1;
    private static final int ANIM_DIGGING = 2;
    private static final int ANIM_FALL = 3;

    private int state;
    private int stateTimer;

    private final SubpixelMotion.State motionState;
    private boolean floorProbeToggle;

    private int animationId;
    private int animationTick;

    public Sonic1BurrobotBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Burrobot");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // S1 obStatus bit 0: 0 = facing left, 1 = facing right.
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;

        this.state = STATE_CHECK_SONIC;
        this.stateTimer = 0;

        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.floorProbeToggle = false;

        this.animationId = ANIM_DIGGING;
        this.animationTick = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case STATE_CHANGEDIR -> updateChangeDir();
            case STATE_MOVE -> updateMove(vIntRunCount);
            case STATE_JUMP -> updateJump(player);
            case STATE_CHECK_SONIC -> updateCheckSonic(player);
            default -> {
            }
        }
    }

    private void updateChangeDir() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = STATE_MOVE;
        stateTimer = 255;
        animationId = ANIM_WALK2;

        facingLeft = !facingLeft;
        xVelocity = facingLeft ? -WALK_SPEED : WALK_SPEED;
    }

    private void updateMove(int vIntRunCount) {
        stateTimer--;
        if (stateTimer < 0) {
            enterMoveEndBranch(vIntRunCount);
            return;
        }

        applySpeedToPos();

        // Burro_Move uses bchg #0,objoff_32(a0); bne branches on the old bit state.
        boolean oldFloorProbeToggle = floorProbeToggle;
        floorProbeToggle = !floorProbeToggle;
        int probeX = currentX + (facingLeft ? -0x0C : 0x0C);

        if (!oldFloorProbeToggle) {
            // Burro_Move .checkLedgeAhead: ObjFloorDist2 then cmpi.w #$C,d1 / bge NextAction.
            // docs/s1disasm/_incObj/2D Badnik - Burrobot.asm:75-85
            if (objFloorDist(probeX) >= 0x0C) {
                enterMoveEndBranch(vIntRunCount);
            }
            return;
        }

        // Burro_Move .alignToFloor: ObjFloorDist then add.w d1,obY (unconditional).
        // docs/s1disasm/_incObj/2D Badnik - Burrobot.asm:88-91
        currentY += objFloorDist(currentX);
    }

    /**
     * ROM {@code ObjFloorDist} ({@code FindFloor}): always returns a floor distance,
     * never a "no surface" sentinel. When neither the sensor tile nor the tile below
     * is solid (the Burrobot has walked off a ledge), {@code FindFloor2.isblank2}
     * returns {@code $F - (sensorY & $F)} and {@code FindFloor.isblank} adds {@code $10},
     * yielding {@code $1F - (sensorY & $F)} (a small positive fall distance). The
     * Burrobot's {@code add.w d1,obY} then walks it off the edge / starts its fall,
     * which is what subsequently drives the random Burro jump. The engine's
     * {@link ObjectTerrainUtils#checkFloorDist} reports the both-blank case as
     * no-collision, so substitute the ROM blank-tile return here.
     *
     * <p>docs/s1disasm/_incObj/sub ObjFloorDist.asm:17-39<br>
     * docs/s1disasm/_incObj/sub FindNearestTile &amp; FindFloor &amp; FindWall.asm
     * (FindFloor.isblank, FindFloor2.isblank2)
     */
    private int objFloorDist(int probeX) {
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(probeX, currentY, Y_RADIUS);
        if (floor.foundSurface()) {
            return floor.distance();
        }
        int sensorY = currentY + Y_RADIUS;
        return 0x1F - (sensorY & 0x0F);
    }

    private void enterMoveEndBranch(int vIntRunCount) {
        if ((vIntRunCount & 0x04) != 0) {
            state = STATE_CHANGEDIR;
            stateTimer = 59;
            xVelocity = 0;
            animationId = ANIM_WALK1;
            return;
        }

        state = STATE_JUMP;
        yVelocity = JUMP_VELOCITY;
        animationId = ANIM_DIGGING;
    }

    private void updateJump(AbstractPlayableSprite player) {
        applySpeedToPos();
        yVelocity += GRAVITY;

        if (yVelocity < 0) {
            return;
        }

        animationId = ANIM_FALL;

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (!floor.foundSurface() || floor.distance() >= 0) {
            return;
        }

        currentY += floor.distance();
        yVelocity = 0;
        animationId = ANIM_WALK2;
        stateTimer = 255;
        state = STATE_MOVE;

        // Burro_ChkSonic2 is used here to update facing based on player side.
        resolvePlayerDirectionAndVelocity(player, 0x80);
    }

    private void updateCheckSonic(AbstractPlayableSprite player) {
        int jumpVelocity = resolvePlayerDirectionAndVelocity(player, 0x60);
        if (player == null) {
            return;
        }

        // bcc.s locret_AE20 - exit if player is outside horizontal range ($60)
        int dx = player.getCentreX() - currentX;
        if (dx < 0) dx = -dx;
        if (dx >= 0x60) {
            return;
        }

        int dy = player.getCentreY() - currentY;
        if (dy >= 0 || dy < -0x80) {
            return;
        }

        if (player.isDebugMode()) {
            return;
        }

        state = STATE_JUMP;
        xVelocity = jumpVelocity;
        yVelocity = JUMP_VELOCITY;
        animationId = ANIM_DIGGING;
    }

    /**
     * Equivalent of Burro_ChkSonic2: resolve facing direction from player side and
     * return signed horizontal speed (+$80 right, -$80 left).
     */
    private int resolvePlayerDirectionAndVelocity(AbstractPlayableSprite player, int distanceLimit) {
        int speed = WALK_SPEED;
        if (player == null) {
            return facingLeft ? -speed : speed;
        }

        int dx = player.getCentreX() - currentX;
        if (dx < 0) {
            dx = -dx;
            speed = -speed;
            facingLeft = true;
        } else {
            facingLeft = false;
        }

        if (dx > distanceLimit) {
            return facingLeft ? -WALK_SPEED : WALK_SPEED;
        }

        return speed;
    }

    private void applySpeedToPos() {
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        // Burro_Move/Burro_Jump call SpeedToPos, which updates 16.16 obX/obY.
        // docs/s1disasm/_incObj/2D Burrobot.asm:63,103
        // docs/s1disasm/_incObj/sub SpeedToPos.asm:5-18
        SubpixelMotion.speedToPos(motionState);
        currentX = motionState.x;
        currentY = motionState.y;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animationTick++;
    }

    private int getMappingFrame() {
        int step = (animationTick / 4);
        return switch (animationId) {
            case ANIM_WALK1 -> (step & 1) == 0 ? 0 : 6;
            case ANIM_WALK2 -> (step & 1) == 0 ? 0 : 1;
            case ANIM_DIGGING -> (step & 1) == 0 ? 2 : 3;
            case ANIM_FALL -> 4;
            default -> 0;
        };
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        // Burro_Action ends with RememberState, which uses the S1 chunk-rounded
        // out_of_range macro rather than a pixel-margin on-screen test.
        return !isDestroyed() && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BURROBOT);
        if (renderer == null) return;

        int frame = getMappingFrame();
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%d timer=%d vel=(%04X,%04X) sub=(%04X,%04X)",
                state, stateTimer, xVelocity & 0xFFFF, yVelocity & 0xFFFF,
                motionState.xSub & 0xFFFF, motionState.ySub & 0xFFFF);
    }
}
