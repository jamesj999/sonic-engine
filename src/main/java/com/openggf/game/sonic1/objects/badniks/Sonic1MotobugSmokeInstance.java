package com.openggf.game.sonic1.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Motobug exhaust smoke (child of Motobug 0x40).
 * Animation-only object with no collision. Uses the same Motobug art sheet
 * with smoke frames (3-5) and a blank frame (6).
 * <p>
 * In the ROM, smoke is a Motobug object with obAnim=2, which causes it to
 * skip straight to Moto_Animate (routine 4). The smoke animation script
 * (anim 2) plays frames 3,6,3,6,4,6,4,6,4,6,5 at speed 1, then triggers
 * afRoutine which advances to Moto_Delete (routine 6).
 * <p>
 * Based on docs/s1disasm/_incObj/40 Moto Bug.asm and _anim/Moto Bug.asm.
 */
public class Sonic1MotobugSmokeInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Smoke animation sequence from _anim/Moto Bug.asm .smoke:
    // dc.b 1, 3, 6, 3, 6, 4, 6, 4, 6, 4, 6, 5, afRoutine
    // Speed 1 = each frame displayed for 2 ticks
    private static final int[] SMOKE_FRAMES = {3, 6, 3, 6, 4, 6, 4, 6, 4, 6, 5};
    // ROM script speed byte 1 means: show the loaded frame immediately, then hold
    // it for one more update before advancing on the following AnimateSprite call.
    private static final int FRAME_HOLD_TICKS = 1;

    private int posX;
    private int posY;
    private boolean facingLeft;
    private int scriptIndex;
    private int frameHoldRemaining;
    private int renderedFrame;
    private boolean initialized;
    private boolean deletePending;
    private boolean finished;

    public Sonic1MotobugSmokeInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y(), false);
    }

    public Sonic1MotobugSmokeInstance(int x, int y, boolean facingLeft) {
        super(new ObjectSpawn(x, y, 0x40, 0, 0, false, 0), "MotobugSmoke");
        this.posX = x;
        this.posY = y;
        this.facingLeft = facingLeft;

        this.scriptIndex = 0;
        this.frameHoldRemaining = 0;
        this.renderedFrame = SMOKE_FRAMES[0];
        this.initialized = false;
        this.deletePending = false;
        this.finished = false;
    }

    @Override
    public Sonic1MotobugSmokeInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1MotobugSmokeInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (finished) {
            return;
        }

        // ROM: smoke enters Moto_Main first, then immediately branches to
        // Moto_Animate on that same update. Only the following tick runs Moto_Delete.
        if (deletePending) {
            finished = true;
            setDestroyed(true);
            return;
        }

        if (!initialized) {
            initialized = true;
        }

        if (frameHoldRemaining > 0) {
            frameHoldRemaining--;
            return;
        }

        if (scriptIndex >= SMOKE_FRAMES.length) {
            deletePending = true;
            return;
        }

        renderedFrame = SMOKE_FRAMES[scriptIndex++];
        frameHoldRemaining = FRAME_HOLD_TICKS;
    }

    @Override
    public boolean isPersistent() {
        return !finished;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // obPriority = 4 (same as parent)
    }

    @Override
    public int getX() {
        return posX;
    }

    @Override
    public int getY() {
        return posY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (finished) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MOTOBUG);
        if (renderer == null) return;

        // S1 convention: default sprite art faces left, hFlip = true when facing right
        renderer.drawFrameIndex(renderedFrame, posX, posY, !facingLeft, false);
    }
}
