package com.openggf.game.sonic1.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Base class for the 5 standard Sonic 1 Eggman-ship bosses (GHZ, LZ, MZ, SLZ, SYZ).
 *
 * These bosses share the same Eggman ship sprite with face and flame overlays,
 * and use identical helper methods for fixed-point movement, on-screen checks,
 * face/flame frame mapping, defeat explosion spawning, and camera-expand escape.
 *
 * The FZ boss is NOT included — it uses a completely different sprite set (Map_SEgg)
 * and has unique combat mechanics.
 *
 * Extracted methods (byte-identical across all 5 bosses in the ROM):
 * <ul>
 *   <li>{@link #bossMove()} — BossMove subroutine (applies velocity to fixed-point position)</li>
 *   <li>{@link #isBossOnScreen()} — camera-relative bounds check</li>
 *   <li>{@link #getFaceFrame()} — face animation ID to mapping frame index</li>
 *   <li>{@link #getFlameFrame()} — flame animation ID to mapping frame index</li>
 *   <li>{@link #renderEggmanShip()} — ship body + face overlay + flame overlay rendering</li>
 *   <li>{@link #runDefeatExplosions(int)} — countdown timer + explosion spawning</li>
 *   <li>{@link #runCameraExpandEscape(int)} — camera unlock + boundary expansion + off-screen destroy</li>
 * </ul>
 */
public abstract class AbstractS1EggmanBossInstance extends AbstractBossInstance {

    /** Face animation state — set by subclass in updateFaceAnimation(). */
    protected int faceAnim;

    /** Flame animation state — set by subclass in updateFlameAnimation(). */
    protected int flameAnim;

    protected AbstractS1EggmanBossInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    /**
     * The S1 Eggman-ship bosses are never unloaded by the generic {@code out_of_range}
     * window: their main routine ends with {@code jmp (DisplaySprite).l} (e.g.
     * docs/s1disasm/_incObj/75, 76 Boss - SYZ Main and Blocks.asm:84), which only
     * displays the sprite — it never calls {@code MarkObjGone} / the {@code out_of_range}
     * delete macro. The boss owns its entire lifecycle: it self-deletes from its escape
     * routine once {@code v_limitright2} reaches {@code boss_*_end} and it has left the
     * screen ({@link #runCameraExpandEscape(int)}). Without this, the boss is culled
     * off-screen mid-escape and stops running {@code addq.w #2,(v_limitright2)}, freezing
     * the right-boundary camera scroll short of {@code boss_*_end} (SYZ3 trace: camera
     * stuck at 0x2CA4 instead of expanding to boss_syz_end 0x2D40). The GHZ boss happens
     * to reach its end boundary just before the cull window, so it was already correct;
     * this makes all five bosses match the ROM no-cull behavior.
     */
    @Override
    public boolean isPersistent() {
        return true;
    }

    // ========================================================================
    // Shared utility methods — byte-identical across all 5 S1 Eggman bosses
    // ========================================================================

    /**
     * BossMove subroutine — applies velocity to fixed-point position.
     * ROM: sonic.asm:6692 (BossMove)
     * <pre>
     *   objoff_30 += sign_extend(obVelX) << 8
     *   objoff_38 += sign_extend(obVelY) << 8
     * </pre>
     */
    protected void bossMove() {
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
    }

    /**
     * Check if the boss is on-screen using camera-relative X bounds.
     * ROM: tst.b obRender(a0) / bpl.s — bit 7 of obRender = on-screen flag.
     * We approximate with a generous horizontal range of [-64, 384].
     */
    protected boolean isBossOnScreen() {
        Camera camera = services().camera();
        int screenX = state.x - camera.getX();
        return screenX >= -64 && screenX <= 384;
    }

    /**
     * Map face animation ID to the Eggman mapping frame index.
     * ROM: Map_Eggman frame indices for each face animation.
     *
     * @return frame index (1-7), or -1 if no face should be drawn
     */
    protected int getFaceFrame() {
        return switch (faceAnim) {
            case Sonic1BossAnimations.ANIM_FACE_NORMAL_1,
                 Sonic1BossAnimations.ANIM_FACE_NORMAL_2,
                 Sonic1BossAnimations.ANIM_FACE_NORMAL_3 -> 1; // facenormal1
            case Sonic1BossAnimations.ANIM_FACE_LAUGH -> 3;    // facelaugh1
            case Sonic1BossAnimations.ANIM_FACE_HIT -> 5;      // facehit
            case Sonic1BossAnimations.ANIM_FACE_PANIC -> 6;    // facepanic
            case Sonic1BossAnimations.ANIM_FACE_DEFEAT -> 7;   // facedefeat
            default -> -1;
        };
    }

    /**
     * Map flame animation ID to the Eggman mapping frame index.
     * ROM: Map_Eggman frame indices for each flame animation.
     *
     * @return frame index (8 or 11), or -1 if no flame should be drawn
     */
    protected int getFlameFrame() {
        return switch (flameAnim) {
            case Sonic1BossAnimations.ANIM_FLAME_1,
                 Sonic1BossAnimations.ANIM_FLAME_2 -> 8;        // flame1
            case Sonic1BossAnimations.ANIM_ESCAPE_FLAME -> 11;  // escapeflame1
            case Sonic1BossAnimations.ANIM_BLANK -> -1;         // no flame
            default -> -1;
        };
    }

    /**
     * Render the standard Eggman ship: body (frame 0), face overlay, flame overlay.
     * ROM: All 5 S1 bosses use Map_Eggman with identical rendering logic for the
     * base ship. Subclasses that need extra overlays (MZ exhaust tube, SLZ pipe)
     * should override {@link #appendRenderCommands(List)} and call this first.
     */
    protected void renderEggmanShip() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer eggmanRenderer = renderManager.getRenderer(ObjectArtKeys.EGGMAN);
        if (eggmanRenderer == null || !eggmanRenderer.isReady()) {
            return;
        }

        boolean flipped = (state.renderFlags & 1) != 0;

        // Draw ship body (frame 0)
        eggmanRenderer.drawFrameIndex(0, state.x, state.y, flipped, false);

        // Draw face overlay (frames 1-7)
        int faceFrame = getFaceFrame();
        if (faceFrame >= 0) {
            eggmanRenderer.drawFrameIndex(faceFrame, state.x, state.y, flipped, false);
        }

        // Draw flame overlay (frames 8-12)
        int flameFrame = getFlameFrame();
        if (flameFrame >= 0) {
            eggmanRenderer.drawFrameIndex(flameFrame, state.x, state.y, flipped, false);
        }
    }

    /**
     * Run defeat explosions: decrement timer, spawn explosion every 8 frames.
     * ROM: BossDefeated subroutine — used by GHZ, MZ, SLZ, SYZ in their defeat wait states.
     *
     * @param frameCounter current frame counter (checked with & 7)
     * @return true if the timer has expired (caller should transition to next state)
     */
    protected boolean runDefeatExplosions(int frameCounter, int[] timer) {
        timer[0]--;
        if (timer[0] < 0) {
            return true; // Timer expired
        }
        // Spawn explosions every 8 frames
        if ((frameCounter & 7) == 0) {
            spawnDefeatExplosion();
        }
        return false;
    }

    /**
     * Run camera-expand escape: expand right boundary by 2 per frame until endBoundary,
     * then check if boss is off-screen and destroy it.
     * ROM: Common escape pattern in GHZ, MZ, SLZ, SYZ bosses.
     *
     * @param endBoundary the target right camera boundary (e.g. boss_ghz_end)
     * @return true if the boss was destroyed (off-screen), false otherwise
     */
    protected boolean runCameraExpandEscape(int endBoundary) {
        Camera camera = services().camera();

        // Unfreeze camera during escape
        if (camera.getFrozen()) {
            camera.setFrozen(false);
        }

        int rightBoundary = camera.getMaxX() & 0xFFFF;

        if (rightBoundary >= endBoundary) {
            // Boundary reached — check if off-screen
            if (!isBossOnScreen()) {
                setDestroyed(true);
                return true;
            }
        } else {
            // ROM: addq.w #2,(v_limitright2).w — expand right boundary
            camera.setMaxX((short) (rightBoundary + 2));
        }

        return false;
    }

    // ========================================================================
    // Default rendering — subclasses with extra overlays should override
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        renderEggmanShip();
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic1Sfx.HIT_BOSS.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic1Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return Sonic1ObjectIds.EXPLOSION;
    }
}
