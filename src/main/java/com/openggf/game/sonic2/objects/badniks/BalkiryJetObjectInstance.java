package com.openggf.game.sonic2.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Balkiry Jet (0x9C) - Jet exhaust child that follows the Balkiry parent.
 *
 * Based on disassembly Obj9C (s2.asm lines 74480-74523).
 *
 * Main (Obj9C_Main):
 *   - Read parent address from objoff_2C
 *   - Check parent is still alive (cmp.b id) - delete if not
 *   - Copy parent x_pos/y_pos to self
 *   - AnimateSprite using Ani_obj9C (frames 8,9 at speed 1)
 *   - Obj_DeleteBehindScreen
 *
 * SubObjData: priority 5, width 8, collision 0.
 * Animation: Ani_obj9C = { 1, 8, 9, $FF }
 */
public class BalkiryJetObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    // Animation from Ani_obj9C: dc.b 1, 8, 9, $FF
    private static final int ANIM_SPEED = 1; // Animate every 2 frames (speed+1)
    private static final int FRAME_A = 8; // Map_obj9C_0084: 2x1 tiles
    private static final int FRAME_B = 9; // Map_obj9C_008E: 1x1 tile
    // From Obj9C_SubObjData
    private static final int PRIORITY = 5;
    private final BalkiryBadnikInstance parent;
    private int animTimer;
    private int animFrame;

    public BalkiryJetObjectInstance(ObjectSpawn spawn, BalkiryBadnikInstance parent) {
        super(spawn, "BalkiryJet");
        this.parent = parent;
        this.animFrame = FRAME_A;
        this.animTimer = 0;
    }

    private BalkiryJetObjectInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        BalkiryBadnikInstance parent = Sonic2BadnikChildRewindLinks.nearestBalkiry(ctx);
        return parent != null ? new BalkiryJetObjectInstance(ctx.spawn(), parent) : null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: cmp.b id(a1),d0 / bne.w JmpTo65_DeleteObject
        if (parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        // ROM: AnimateSprite with Ani_obj9C (speed 1, frames 8, 9, loop)
        animTimer++;
        if (animTimer > ANIM_SPEED) {
            animTimer = 0;
            animFrame = (animFrame == FRAME_A) ? FRAME_B : FRAME_A;
        }

        // ROM: Obj_DeleteBehindScreen
        if (!isOnScreenX(64)) {
            setDestroyed(true);
        }
    }

    @Override
    public int getX() {
        return parent.getX();
    }

    @Override
    public int getY() {
        return parent.getY();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // Jet uses Turtloid sheet (shared mappings from Obj9A_Obj98_MapUnc_37B62)
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.TURTLOID);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Jet exhaust art faces left by default (Balkiry always flies left)
        renderer.drawFrameIndex(animFrame, parent.getX(), parent.getY(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(parent.getX(), parent.getY(), 4, 1.0f, 0.5f, 0.0f);
        ctx.drawWorldLabel(parent.getX(), parent.getY(), -1, "Jet", DebugColor.ORANGE);
    }
}
