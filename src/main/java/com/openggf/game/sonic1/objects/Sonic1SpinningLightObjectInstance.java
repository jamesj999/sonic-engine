package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x12 - Spinning Light (SYZ lamp).
 * <p>
 * A purely decorative animated lamp object found exclusively in Spring Yard Zone.
 * Has no collision, no movement -- just cycles through 6 animation frames.
 * <p>
 * Uses level tile patterns (ArtTile_Level) with palette line 0 for rendering.
 * Each frame is 32x16 pixels (two 4x1 tile pieces stacked vertically, second v-flipped).
 * <p>
 * <b>Animation:</b> 6 frames at 7-tick delay (obTimeFrame), loops continuously.
 * <pre>
 * Light_Animate:
 *   subq.b #1,obTimeFrame(a0)
 *   bpl.s  .chkdel
 *   move.b #7,obTimeFrame(a0)
 *   addq.b #1,obFrame(a0)
 *   cmpi.b #6,obFrame(a0)
 *   blo.s  .chkdel
 *   move.b #0,obFrame(a0)
 * </pre>
 * <p>
 * Reference: docs/s1disasm/_incObj/12 Light.asm
 */
public class Sonic1SpinningLightObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: move.b #6,obPriority(a0)
    private static final int PRIORITY = 6;

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10;

    // From disassembly: move.b #7,obTimeFrame(a0)
    private static final int FRAME_DELAY = 7;

    // From disassembly: cmpi.b #6,obFrame(a0)
    private static final int FRAME_COUNT = 6;

    // Animation state
    private int frameTimer;
    private int frameIndex;

    public Sonic1SpinningLightObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SpinningLight");
        this.frameTimer = FRAME_DELAY;
        this.frameIndex = 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Light_Animate: subq.b #1,obTimeFrame(a0) / bpl.s .chkdel
        frameTimer--;
        if (frameTimer < 0) {
            // move.b #7,obTimeFrame(a0)
            frameTimer = FRAME_DELAY;
            // addq.b #1,obFrame(a0)
            frameIndex++;
            // cmpi.b #6,obFrame(a0) / blo.s .chkdel
            if (frameIndex >= FRAME_COUNT) {
                // move.b #0,obFrame(a0)
                frameIndex = 0;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SYZ_SPINNING_LIGHT);
        if (renderer == null) return;
        renderer.drawFrameIndex(frameIndex, getX(), getY(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(spawn.x(), spawn.y(), 4, 0.5f, 1.0f, 0.5f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("Light f=%d t=%d", frameIndex, frameTimer),
                DebugColor.GREEN);
    }
}
