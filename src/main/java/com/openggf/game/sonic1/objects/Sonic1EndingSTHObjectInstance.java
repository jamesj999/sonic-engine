package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ZeroArgRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 89 - "SONIC THE HEDGEHOG" text on the ending sequence.
 * <p>
 * Slides in from the left side of the screen, then waits before
 * triggering the credits transition.
 * <p>
 * Uses screen-space coordinates (ROM: obRender = 0).
 * <p>
 * Routines:
 * <pre>
 *   0: ESth_Main       - initialize position off-screen left
 *   2: ESth_Move       - slide right by $10/frame until X=$C0
 *   4: ESth_GotoCredits - wait 300 frames (REV01) then transition
 * </pre>
 * <p>
 * Reference: docs/s1disasm/_incObj/89 Ending Sequence STH.asm
 */
public class Sonic1EndingSTHObjectInstance extends AbstractObjectInstance
        implements ZeroArgRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1EndingSTHObjectInstance.class.getName());

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /** Render priority: 0 (highest, drawn on top). */
    private static final int PRIORITY = 0;

    /** ROM: move.w #-$20,obX(a0) — initial screen X. VDP offset: subtract $80. */
    private static final int INITIAL_SCREEN_X = -0x20 - 0x80;

    /** ROM: move.w #$D8,obScreenY(a0) — screen Y position. VDP offset: subtract $80. */
    private static final int SCREEN_Y = 0xD8 - 0x80;

    /** ROM: addi.w #$10,obX(a0) — pixels to move per frame. */
    private static final int SLIDE_SPEED = 0x10;

    /** ROM: cmpi.w #$C0,obX(a0) — target screen X. VDP offset: subtract $80. */
    private static final int TARGET_SCREEN_X = 0xC0 - 0x80;

    /** ROM REV01: move.w #300,esth_time(a0) — delay before credits. */
    private static final int CREDITS_DELAY = 300;

    // ========================================================================
    // State
    // ========================================================================

    private PatternSpriteRenderer renderer;
    private int routine;
    private int screenX;
    private int timer;

    public Sonic1EndingSTHObjectInstance() {
        super(null, "EndSTH");

        // Routine 0: ESth_Main — initialize
        this.screenX = INITIAL_SCREEN_X;
        this.routine = 2; // Advance immediately (ROM falls through)
    }

    private void ensureRenderer() {
        if (renderer == null) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                renderer = renderManager.getRenderer(ObjectArtKeys.END_STH);
            }
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureRenderer();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        switch (routine) {
            case 2 -> updateMove();
            case 4 -> updateGotoCredits();
            default -> { }
        }
    }

    // ========================================================================
    // Routine handlers
    // ========================================================================

    /** Routine 2: Slide right until reaching target X. */
    private void updateMove() {
        if (screenX >= TARGET_SCREEN_X) {
            // ROM: ESth_Delay
            routine = 4;
            timer = CREDITS_DELAY;
            return;
        }
        screenX += SLIDE_SPEED;
    }

    /** Routine 4: Wait then trigger credits transition. */
    private void updateGotoCredits() {
        timer--;
        if (timer < 0) {
            // ROM: move.b #id_Credits,(v_gamemode).w
            // Signal GameLoop to begin the credits sequence via fade-to-black.
            services().requestCreditsTransition();
            setDestroyed(true);
        }
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public int getPriorityBucket() {
        return PRIORITY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || renderer == null || !renderer.isReady()) {
            return;
        }
        // ROM: obRender = 0 → screen-space coordinates.
        // Convert screen coords to world coords by adding camera position.
        Camera camera = services().camera();
        int worldX = screenX + camera.getX();
        int worldY = SCREEN_Y + camera.getY();
        renderer.drawFrameIndex(0, worldX, worldY, false, false);
    }
}
