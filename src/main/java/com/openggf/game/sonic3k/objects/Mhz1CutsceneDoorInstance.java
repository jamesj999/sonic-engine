package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ1 Knuckles switch-door child created by {@code Obj_MHZ1CutsceneButton}.
 *
 * <p>ROM reference: {@code ChildObjDat_665B6 -> loc_6300C}. The child starts
 * at the fixed door coordinates used by {@code Map_MHZKnuxDoor}; movement is
 * driven by the parent button's switch bits in the following routines.
 */
public final class Mhz1CutsceneDoorInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable {
    private static final int INITIAL_X = 0x0390;
    private static final int INITIAL_Y = 0x0620;
    private static final int PRIORITY = 1;
    private static final int SLIDE_SPEED = 0x0100;
    private static final int SLIDE_WAIT = 0x3F;
    private static final int AUTO_RAISE_MAX_X_DISTANCE = 0x40;
    private static final int AUTO_RAISE_MIN_Y_DISTANCE = 0x60;
    private static final int RE_ENTRY_LOWERED_Y_OFFSET = 0x40;
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x1B, 0x20, 0x20);

    private Mhz1CutsceneButtonInstance parent;
    private final SubpixelMotion.State motion = new SubpixelMotion.State(INITIAL_X, INITIAL_Y, 0, 0, 0, 0);
    private State state = State.IDLE;
    private int waitTimer;

    public Mhz1CutsceneDoorInstance(Mhz1CutsceneButtonInstance parent) {
        super(new ObjectSpawn(INITIAL_X, INITIAL_Y, parent.getSpawn().objectId(), 0, 0, false, 0),
                "MHZ1CutsceneDoor");
        this.parent = parent;
        // ROM MHZ1CutsceneButton_Door (asm 130205-130212): after setting
        // x_pos/y_pos to the fixed door coordinates, tst.b (_unkFAA9) and
        // add $40 to y_pos when it is already set. _unkFAA9 is this pair's
        // door-lowered latch (see Mhz1CutsceneButtonInstance.doorLowered,
        // toggled by loc_62F0A's not.b/clr.b on the same RAM byte), so a
        // mid-act re-entry that recreates this child while the latch is
        // still set must spawn the door already in its lowered position.
        if (parent.isDoorLowered()) {
            motion.y += RE_ENTRY_LOWERED_Y_OFFSET;
        }
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Mhz1CutsceneButtonInstance liveParent = findNearestLiveButton(ctx);
        return liveParent == null ? null : new Mhz1CutsceneDoorInstance(liveParent);
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public boolean isPersistent() {
        // ROM Child_Draw_Sprite retains this structural child only while its
        // parent is live; once Sprite_CheckDelete marks the parent, the child
        // installs its own delete routine.
        return parent != null && !parent.isDestroyed();
    }

    @Override
    public void onUnload() {
        if (parent != null) {
            parent.detachDoor(this);
            parent = null;
        }
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // MHZ1CutsceneButton_Door_Wait's jsr sub_65E4C (sonic3k.asm:130214,134149)
        // reaches SolidObjectFull -> SolidObject_cont, whose right-edge X-window
        // check is cmp.w d3,d0 / bhi.w (sonic3k.asm:41399-41406) -- not bhs -- so
        // relX == width*2 remains a valid zero-distance side contact and keeps
        // Status_Push (bit 5, constants.asm:140/179) set. The sibling
        // Obj_MHZ1CutsceneButton overrides the same way for the same gate; see
        // Mhz1CutsceneButtonInstance.usesInclusiveRightEdge().
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (state == State.IDLE) {
            if (parent.isDoorSwitchActive()) {
                parent.clearDoorSwitchActive();
                startSlide();
                // Fall through to the movement step: MHZ1CutsceneButton_Door_Move
                // (sonic3k.asm:130237) arms y_vel/$2E/$34 and then falls straight
                // through MHZ1CutsceneButton_Door_SetVelocity into
                // MHZ1CutsceneButton_Door_Moving (130251) with no rts, so the
                // trigger frame itself runs MoveSprite2 + Obj_Wait. Returning here
                // would delay the whole slide by one frame (MHZ1 trace F1162: the
                // switch-driven upward slide lags the ROM door by 1px, so its
                // underside clips the rising sidekick's jump apex instead of
                // clearing it as the ROM door does).
            } else if (shouldAutoRaiseFromPlayerPosition(playerEntity)) {
                parent.setDoorLowered(false);
                startSlide();
                // MHZ1CutsceneButton_Door_ClearFlag (130234) clears _unkFAA9 and
                // also falls through Door_Move into Door_Moving on the same frame.
            } else {
                return;
            }
        }

        SubpixelMotion.moveSprite2(motion);
        if (--waitTimer < 0) {
            state = State.IDLE;
            motion.yVel = 0;
            parent.setDoorMoving(false);
        }
    }

    private boolean shouldAutoRaiseFromPlayerPosition(PlayableEntity playerEntity) {
        if (!parent.isDoorLowered() || parent.isCutsceneDoorLatched() || playerEntity == null) {
            return false;
        }
        int playerX = playerEntity.getCentreX() & 0xFFFF;
        int playerY = playerEntity.getCentreY() & 0xFFFF;
        if (Integer.compareUnsigned(playerX, motion.x) > 0) {
            return false;
        }
        int xDistance = Math.abs(motion.x - playerX);
        int yDistance = Math.abs(motion.y - playerY);
        return xDistance < AUTO_RAISE_MAX_X_DISTANCE
                && yDistance >= AUTO_RAISE_MIN_Y_DISTANCE;
    }

    private void startSlide() {
        state = State.MOVING;
        parent.setDoorMoving(true);
        motion.yVel = parent.isDoorLowered() ? SLIDE_SPEED : -SLIDE_SPEED;
        waitTimer = SLIDE_WAIT;
    }

    private static Mhz1CutsceneButtonInstance findNearestLiveButton(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        Mhz1CutsceneButtonInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(object instanceof Mhz1CutsceneButtonInstance candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (spawn == null) {
                return candidate;
            }
            long dx = candidate.getX() - spawn.x();
            long dy = candidate.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_DOOR);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(0, motion.x, motion.y, false, false);
    }

    private enum State {
        IDLE,
        MOVING
    }
}
