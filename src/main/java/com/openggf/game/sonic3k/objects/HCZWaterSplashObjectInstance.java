package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x6D &mdash; HCZ Water Splash, subtype 0 (Sonic 3 &amp; Knuckles, Hydrocity Zone).
 * <p>
 * A static animated water surface effect placed at fixed positions in the level layout.
 * Cycles through 4 animation frames with an 8-tick timer, each frame DMA-swapping 24
 * tiles of uncompressed art in the original ROM. The engine pre-loads all 96 tiles and
 * selects the appropriate mapping frame per animation step.
 * <p>
 * ROM reference: Obj_HCZWaterSplash subtype 0 (sonic3k.asm:75276-75311).
 * <p>
 * Subtype 1 (interactive water skim) is handled separately by
 * {@link com.openggf.game.sonic3k.features.HCZWaterSkimHandler}.
 */
public class HCZWaterSplashObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // ===== Animation constants (loc_38464, sonic3k.asm:75286-75311) =====
    /** Frame timer reset value: 8 ticks per animation step (sonic3k.asm:75290) */
    private static final int ANIM_TIMER_RESET = 7;
    /** Number of animation frames to cycle through: 0-3 (sonic3k.asm:75293) */
    private static final int ANIM_FRAME_MASK = 3;

    // ===== Render dimensions from ROM (sonic3k.asm:75280-75283) =====
    /** width_pixels = $28 (40 pixels) */
    private static final int WIDTH_PIXELS = 0x28;

    private int x;
    private int y;

    // ROM: anim_frame_timer starts at 0 (object RAM zeroed), so first subq underflows
    // to -1 and the frame advances immediately on the first tick.
    private int animTimer;
    private int mappingFrame;

    public HCZWaterSplashObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZWaterSplash");
        this.x = spawn.x();
        this.y = spawn.y();
    }

    @Override
    public HCZWaterSplashObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZWaterSplashObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        // Animation timer (sonic3k.asm:75286-75293)
        // ROM: subq.b #1,anim_frame_timer / bpl.s skip
        //      move.b #7,anim_frame_timer / addq.b #1,mapping_frame / andi.b #3,mapping_frame
        // ROM runs animation unconditionally; visibility is only checked at draw time.
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_TIMER_RESET;
            mappingFrame = (mappingFrame + 1) & ANIM_FRAME_MASK;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        if (!isOnScreen(WIDTH_PIXELS)) return;

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_WATER_SPLASH);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
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
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(x, y);
    }
}
