package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * S3K S3KL Obj $C2 - Flybot 767 (LBZ).
 *
 * <p>ROM reference: {@code Obj_Flybot767} at {@code sonic3k.asm:191981}.
 * The badnik chases Player 1, dives when below/near the player, rebounds
 * above its attack origin, then waits before returning to the chase loop.
 */
public final class Flybot767BadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x1A; // ObjSlot_Flybot767 collision_flags.
    private static final int PRIORITY_BUCKET = 5;         // ObjSlot_Flybot767 priority $280.

    private static final int CHASE_X_MAX_SPEED = 0x200;   // loc_8C9B2 d0.
    private static final int CHASE_Y_MAX_SPEED = 0x100;   // loc_8C9B2 d0.
    private static final int CHASE_ACCELERATION = 0x10;   // loc_8C9B2 d1.
    private static final int TARGET_Y_OFFSET = -0x40;     // loc_8C994 child_dy.
    private static final int ATTACK_X_RANGE = 0x60;       // loc_8C9B2 cmpi.w #$60,d2.
    private static final int ATTACK_X_SPEED = 0x200;      // loc_8CA22 d1.
    private static final int ATTACK_Y_SPEED = 0x200;      // loc_8CA46.
    private static final int DIVE_MIN_TIMER = 0x20;       // loc_8CA46 objoff_2E.
    private static final int RETURN_WAIT = 0x1F;          // loc_8CAAC objoff_2E.
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20; // Obj_WaitOffscreen width/height.

    private static final int[] IDLE_FRAMES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9
    }; // byte_8CB2A, delay 4, loop.
    private static final int[] WINDUP_FRAMES = {
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    }; // byte_8CB36 after Animate_Raw's initial pre-increment, delay 2, custom callback.
    private static final int[] DIVE_FRAMES = {
            0x10, 0x11, 0x12, 0x13
    }; // byte_8CB3F after Animate_Raw's initial pre-increment before jump to frame $13 loop.
    private static final int[] DIVE_LOOP_FRAMES = {0x13, 0x13};
    private static final int[] REBOUND_FRAMES = {
            0x0A, 0x14
    }; // byte_8CB4B after Animate_Raw's initial pre-increment before jump to frame $14 loop.
    private static final int[] REBOUND_LOOP_FRAMES = {0x14, 0x14};

    private enum State {
        INIT,
        CHASE,
        ATTACK_WINDUP,
        DIVE,
        REBOUND,
        WAIT_RETURN
    }

    private State state = State.INIT;
    private int originY;
    private int waitTimer;
    private int animIndex;
    private int animTimer;
    private boolean inLoop;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;
    private boolean publishedTouchResponseListEntryThisFrame;

    public Flybot767BadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Flybot767",
                Sonic3kObjectArtKeys.FLYBOT_767, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        publishedTouchResponseListEntryThisFrame = false;
        if (isDestroyed()) {
            return;
        }
        if (waitingForOnscreen) {
            // loc_85AD2 (sonic3k.asm:180279-180281) opens with
            // `tst.b render_flags(a0) / bmi loc_85B02`, and bit 7 is published by
            // a PRECEDING Render_Sprites pass (sonic3k.asm:36336-36366), never by
            // the pass doing the test. A freshly allocated placeholder therefore
            // always spends its first pass drawing, and can only observe the flag
            // from the next pass onwards. Both spawn routes read the same
            // published flag so they wake on the same phase: the dynamic route
            // used to test the render bounds live, in its own creation frame, and
            // so skipped the frame the ROM spends waiting for the flag.
            if (!placeholderRenderedOnscreen) {
                return;
            }
            // loc_85B02 (sonic3k.asm:180300-180302) restores the saved
            // continuation into (a0) and `rts` -- the restored routine does NOT
            // run on the frame that restores it, so Obj_Flybot767 dispatch
            // resumes on the following pass.
            placeholderRenderedOnscreen = false;
            waitingForOnscreen = false;
            return;
        }

        PlayableEntity player1 = playerEntity;
        switch (state) {
            case INIT -> initialize();
            case CHASE -> updateChase(player1, false);
            case ATTACK_WINDUP -> updateWindup(player1);
            case DIVE -> updateDive(player1);
            case REBOUND -> updateRebound(player1);
            case WAIT_RETURN -> updateChase(player1, true);
        }
        updateDynamicSpawn(currentX, currentY);
        // Sprite_CheckDeleteTouchSlotted runs the object's restored routine
        // before applying the native coarse-X deletion window. Objects outside
        // that window are released as respawnable layout/alarm slots and never
        // reach the touch-response list for this pass.
        if (!isInRangeAt(currentX)) {
            setDestroyedByOffscreen();
            return;
        }
        publishedTouchResponseListEntryThisFrame = true;
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            // The Render_Sprites pass that publishes render_flags bit 7 for the
            // next Obj_WaitOffscreen dispatch, for layout- and alarm-allocated
            // placeholders alike.
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_MARGIN, WAIT_OFFSCREEN_MARGIN);
        }
    }

    private void initialize() {
        state = State.CHASE;
        mappingFrame = 0;
        resetAnimation();
    }

    private void updateChase(PlayableEntity player, boolean waitBeforeReturn) {
        if (player != null && !player.getDead()) {
            chaseAxis(player.getCentreX(), 0, CHASE_X_MAX_SPEED, CHASE_ACCELERATION);
            chaseAxis(player.getCentreY(), TARGET_Y_OFFSET, CHASE_Y_MAX_SPEED, CHASE_ACCELERATION);
        }
        moveWithVelocity();

        if (!waitBeforeReturn && shouldEnterAttack(player)) {
            enterAttackWindup();
            return;
        }

        updateFacingFromVelocity();
        animateLoop(IDLE_FRAMES, 4);
        if (waitBeforeReturn) {
            waitTimer--;
            if (waitTimer < 0) {
                state = State.CHASE;
            }
        }
    }

    private void updateWindup(PlayableEntity player) {
        moveWithVelocity();
        if (animateOnce(WINDUP_FRAMES, 2)) {
            enterDive(player);
        }
    }

    private void updateDive(PlayableEntity player) {
        moveWithVelocity();
        waitTimer--;
        if (waitTimer < 0 && isClosestPlayerAboveOrLevel(player)) {
            enterRebound();
            return;
        }
        animateDiveLoop();
    }

    private void updateRebound(PlayableEntity player) {
        moveWithVelocity();
        if ((currentY & 0xFFFF) < (originY & 0xFFFF)) {
            enterWaitReturn();
            return;
        }
        animateReboundLoop();
    }

    private void enterAttackWindup() {
        state = State.ATTACK_WINDUP;
        yVelocity = 0;
        originY = currentY;
        resetAnimation();
    }

    private void enterDive(PlayableEntity player) {
        state = State.DIVE;
        int speed = ATTACK_X_SPEED;
        boolean playerRight = player != null && !player.getDead()
                && player.getCentreX() > currentX;
        if (playerRight) {
            xVelocity = speed;
            facingLeft = false;
        } else {
            xVelocity = -speed;
            facingLeft = true;
        }
        yVelocity = ATTACK_Y_SPEED;
        waitTimer = DIVE_MIN_TIMER;
        resetAnimation();
    }

    private void enterRebound() {
        state = State.REBOUND;
        yVelocity = -yVelocity;
        resetAnimation();
    }

    private void enterWaitReturn() {
        state = State.WAIT_RETURN;
        xVelocity = 0;
        yVelocity = 0;
        waitTimer = RETURN_WAIT;
        mappingFrame = 0;
        resetAnimation();
    }

    private boolean shouldEnterAttack(PlayableEntity player) {
        if (player == null || player.getDead()) {
            return false;
        }
        int dx = Math.abs(currentX - player.getCentreX());
        return player.getCentreY() > currentY && dx < ATTACK_X_RANGE;
    }

    /**
     * Port of {@code Chase_ObjectXOnly/YOnly}: accelerate toward target+offset
     * only when the candidate velocity remains inside +/- max speed.
     */
    private void chaseAxis(int target, int offset, int maxSpeed, int acceleration) {
        int desired = target + offset;
        int delta = desired - currentForMax(maxSpeed);
        int step = delta < 0 ? -acceleration : acceleration;
        int currentVelocity = maxSpeed == CHASE_X_MAX_SPEED ? xVelocity : yVelocity;
        int candidate = currentVelocity + step;
        if (candidate >= -maxSpeed && candidate <= maxSpeed) {
            if (maxSpeed == CHASE_X_MAX_SPEED) {
                xVelocity = candidate;
            } else {
                yVelocity = candidate;
            }
        }
    }

    private int currentForMax(int maxSpeed) {
        return maxSpeed == CHASE_X_MAX_SPEED ? currentX : currentY;
    }

    /**
     * ROM {@code Find_SonicTails}: choose the player with the smallest
     * horizontal distance, keeping Player 1 on ties, then test vertical
     * orientation.
     */
    private boolean isClosestPlayerAboveOrLevel(PlayableEntity player1) {
        PlayableEntity closest = player1;
        int bestDx = player1 == null ? Integer.MAX_VALUE : Math.abs(currentX - player1.getCentreX());
        ObjectServices svc = tryServices();
        List<PlayableEntity> sidekicks = svc != null
                ? svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)
                : List.of();
        for (PlayableEntity sidekick : sidekicks) {
            if (sidekick == null || sidekick.getDead()) {
                continue;
            }
            int dx = Math.abs(currentX - sidekick.getCentreX());
            if (dx < bestDx) {
                bestDx = dx;
                closest = sidekick;
            }
        }
        return closest != null && currentY >= closest.getCentreY();
    }

    private void updateFacingFromVelocity() {
        facingLeft = xVelocity < 0;
    }

    private void resetAnimation() {
        animIndex = 0;
        animTimer = 0;
        inLoop = false;
    }

    private void animateLoop(int[] frames, int delay) {
        if (tickAnimation()) {
            mappingFrame = frames[animIndex];
            animIndex = (animIndex + 1) % frames.length;
            animTimer = delay;
        }
    }

    private boolean animateOnce(int[] frames, int delay) {
        if (!tickAnimation()) {
            return false;
        }
        if (animIndex >= frames.length) {
            return true;
        }
        mappingFrame = frames[animIndex++];
        animTimer = delay;
        return false;
    }

    private void animateDiveLoop() {
        if (!inLoop) {
            if (!tickAnimation()) {
                return;
            }
            if (animIndex < DIVE_FRAMES.length) {
                mappingFrame = DIVE_FRAMES[animIndex++];
                animTimer = 3;
                return;
            }
            inLoop = true;
            animIndex = 0;
        }
        animateLoop(DIVE_LOOP_FRAMES, 3);
    }

    private void animateReboundLoop() {
        if (!inLoop) {
            if (!tickAnimation()) {
                return;
            }
            if (animIndex < REBOUND_FRAMES.length) {
                mappingFrame = REBOUND_FRAMES[animIndex++];
                animTimer = 3;
                return;
            }
            inLoop = true;
            animIndex = 0;
        }
        animateLoop(REBOUND_LOOP_FRAMES, 3);
    }

    private boolean tickAnimation() {
        animTimer--;
        return animTimer < 0;
    }

    @Override
    public int getCollisionFlags() {
        return state == State.INIT ? 0 : super.getCollisionFlags();
    }

    @Override
    public boolean publishesTouchResponseListEntryThisFrame() {
        // Obj_Flybot767 first calls Obj_WaitOffscreen; when it becomes visible,
        // that helper restores the saved operation pointer and returns before
        // the Sprite_CheckDeleteTouchSlotted tail can call
        // Add_SpriteToCollisionResponseList (sonic3k.asm:179081-179090,
        // 191981-191989). The routine publishes on later passes only.
        return publishedTouchResponseListEntryThisFrame;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // A layout-loaded Flybot occupies a retained object slot while
        // Map_Offscreen/Obj_WaitOffscreen schedules its operation pointer. Its
        // Sprite_CheckDeleteTouchSlotted entry therefore exposes the live SST
        // coordinate reached by that object pass. Obj_LBZAlarm allocates its
        // Flybots dynamically (layout index -1), after the frame-start touch
        // snapshot, so those keep the ordinary snapshot phase.
        return getSpawn().layoutIndex() >= 0;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s vx=%04X vy=%04X originY=%04X wait=%04X frame=%02X",
                state, xVelocity & 0xFFFF, yVelocity & 0xFFFF,
                originY & 0xFFFF, waitTimer & 0xFFFF, mappingFrame & 0xFF);
    }
}
