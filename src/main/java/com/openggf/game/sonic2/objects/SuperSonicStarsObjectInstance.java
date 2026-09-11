package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Super Sonic Stars (Object 0x7E) - sparkle effect when Super Sonic runs fast.
 *
 * <p>ROM reference: s2.asm:42488-42531 (Obj7E). State machine:
 * <ol>
 *   <li>Cycle 1: position tracks player every frame (freezeFlag=false)</li>
 *   <li>Animation completes: freezeFlag set, one frame invisible</li>
 *   <li>Cycle 2+: position only updates at cycle START (trigger bypasses freeze check),
 *       then frozen for remainder - creates trailing effect as Sonic runs ahead</li>
 *   <li>Speed drops below 0x800: flags cleared, star invisible</li>
 * </ol>
 * <p>Each animation frame displays for 2 game frames (anim_frame_duration=1, counts down).
 * 6-frame cycle: small-medium-large-medium-small-empty.
 */
public class SuperSonicStarsObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private final AbstractPlayableSprite player;
    private PatternSpriteRenderer renderer;

    /** Speed threshold for triggering the star animation (|gSpeed| >= 0x800). */
    private static final int SPEED_THRESHOLD = 0x800;
    /** Total mapping frames in the animation cycle. */
    private static final int FRAME_COUNT = 6;
    /** Duration per animation frame in game frames (anim_frame_duration initial value). */
    private static final int FRAME_DURATION = 1;

    /** Whether the animation cycle is active (objoff_30 equivalent). */
    private boolean animActive;
    /** Freeze flag (objoff_31 equivalent). Once set, position only updates at cycle start. */
    private boolean freezeFlag;
    /** Current mapping frame index (0-5). */
    private int mappingFrame;
    /** Frame duration countdown timer (counts down from FRAME_DURATION). */
    private int frameTimer;
    /** Whether to render this frame. */
    private boolean visible;
    /** Snapshotted render position. */
    private int snapX, snapY;

    @SuppressWarnings("unused")
    private SuperSonicStarsObjectInstance() {
        this(null);
    }

    public SuperSonicStarsObjectInstance(AbstractPlayableSprite player) {
        super(null, "SuperSonicStars");
        this.player = player;
        // Renderer is resolved lazily in appendRenderCommands — ObjectServices is not
        // available at construction time when created outside ObjectManager.
    }

    @Override
    public SuperSonicStarsObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AbstractPlayableSprite focusedPlayer = null;
        if (ctx.objectServices() != null && ctx.objectServices().camera() != null) {
            focusedPlayer = ctx.objectServices().camera().getFocusedSprite();
        }
        return new SuperSonicStarsObjectInstance(focusedPlayer);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (animActive) {
            frameTimer--;
            if (frameTimer >= 0) {
                // Timer not expired - display current frame at current position
                updatePositionIfNotFrozen();
                return;
            }
            // Timer expired - advance to next animation frame
            frameTimer = FRAME_DURATION;
            mappingFrame++;
            if (mappingFrame < FRAME_COUNT) {
                // Still animating
                updatePositionIfNotFrozen();
                return;
            }
            // Animation cycle complete
            mappingFrame = 0;
            animActive = false;
            freezeFlag = true;
            visible = false;
            return;
        }

        // Not animating - check speed trigger
        if (!this.player.isObjectControlled() && Math.abs(this.player.getGSpeed()) >= SPEED_THRESHOLD) {
            // Start new animation cycle
            mappingFrame = 0;
            frameTimer = FRAME_DURATION;
            animActive = true;
            // Position always updates at cycle start (ROM: bra.s loc_1E176 bypasses freeze check)
            snapX = this.player.getCentreX();
            snapY = this.player.getCentreY();
            visible = true;
            return;
        }

        // Speed below threshold - clear all state
        animActive = false;
        freezeFlag = false;
        visible = false;
    }

    private void updatePositionIfNotFrozen() {
        visible = true;
        if (!freezeFlag) {
            snapX = player.getCentreX();
            snapY = player.getCentreY();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (player == null || !visible) {
            return;
        }

        // Lazy-resolve renderer (services() not available during construction)
        if (renderer == null) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                renderer = renderManager.getSuperSonicStarsRenderer();
            }
        }
        if (renderer == null) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, snapX, snapY, false, false);
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    public void destroy() {
        setDestroyed(true);
    }
}
