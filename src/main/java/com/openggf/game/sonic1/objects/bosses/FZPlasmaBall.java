package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * FZ Plasma Ball — energy ball projectile fired by the plasma launcher.
 * ROM: _incObj/86 FZ Plasma Ball Launcher.asm (routine 8, BossPlasma_Index2)
 *
 * 3-phase movement via ob2ndRout:
 *   Phase 0 (Launch): Calculate xvel from distance to target X, set lifetime $B4
 *   Phase 2 (Travel): Move to target X via velocity. When reached, set collision
 *                      (obColType=$9A), chase Sonic with xvel=player distance, yvel=$140
 *   Phase 4 (Chase):  SpeedToPos. Delete when Y >= boss_fz_y+$D0 or lifetime expired
 *
 * Uses palette line 1 (obGfx has palette bit set).
 * Collision type $9A = category 4 (hurt player), size $1A.
 */
public class FZPlasmaBall extends AbstractObjectInstance implements TouchResponseProvider, RewindRecreatable {

    private static final int BOSS_FZ_Y = Sonic1Constants.BOSS_FZ_Y;
    private static final SpriteAnimationSet PLASMA_ANIMATIONS = Sonic1BossAnimations.getPlasmaAnimations();
    private final FZPlasmaLauncher launcher;

    // Movement state
    private int phase;       // ob2ndRout: 0, 2, 4
    private int xVel;        // obVelX
    private int yVel;        // obVelY
    private int targetX;     // objoff_30 — target X position
    private int lifetime;    // obSubtype — countdown timer

    // Position — 32-bit fixed-point (16.16) to match ROM's SpeedToPos
    // ROM: SpeedToPos adds (obVelX << 8) to 32-bit obX position each frame
    private int posXFixed;  // 16.16 fixed-point X
    private int posYFixed;  // 16.16 fixed-point Y
    private int posX;       // Pixel X (posXFixed >> 16)
    private int posY;       // Pixel Y (posYFixed >> 16)

    // Animation state (AnimateSprite-style)
    private int animId;          // obAnim
    private int animPrevId;      // obPrevAni
    private int animScriptFrame; // obAniFrame
    private int animTimeFrame;   // obTimeFrame
    private int animFrame;       // obFrame (mapping frame)
    private boolean hasCollision; // Whether touch response is active

    public FZPlasmaBall(FZPlasmaLauncher launcher,
                        int startX, int startY, int targetX) {
        super(new ObjectSpawn(startX, startY, Sonic1ObjectIds.BOSS_PLASMA, 0, 0, false, 0),
                "FZ Plasma Ball");
        
        this.launcher = launcher;
        this.posX = startX;
        this.posY = startY;
        this.posXFixed = startX << 16;
        this.posYFixed = startY << 16;
        this.targetX = targetX;
        this.phase = 0;
        this.xVel = 0;
        this.yVel = 0;
        this.lifetime = 0;
        this.animId = Sonic1BossAnimations.ANIM_PLASMA_FULL;
        this.animPrevId = -1;
        this.animScriptFrame = 0;
        this.animTimeFrame = 0;
        this.animFrame = 0;
        this.hasCollision = false;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        FZPlasmaLauncher liveLauncher = firstLiveLauncher(ctx);
        if (liveLauncher == null) {
            return null;
        }
        // FZ has one launcher group; preserve the deleted explicit restore path's
        // first-live launcher matching and let compact restore reapply target/scalars.
        FZPlasmaBall restored = new FZPlasmaBall(
                liveLauncher, ctx.spawn().x(), ctx.spawn().y(), 0);
        liveLauncher.adoptPlasmaBallForRewind(restored);
        return restored;
    }

    private static FZPlasmaLauncher firstLiveLauncher(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof FZPlasmaLauncher launcher && !launcher.isDestroyed()) {
                return launcher;
            }
        }
        return null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (phase) {
            case 0 -> updateLaunch();
            case 2 -> updateTravel(player);
            case 4 -> updateChase(player);
        }

        // Update animation
        updateAnimation();
    }

    /**
     * Phase 0 (loc_1A9A6): Calculate initial velocity toward target X.
     * ROM: xvel = (targetX - currentX) << 4, lifetime = $B4
     */
    private void updateLaunch() {
        int dist = targetX - posX;
        xVel = dist << 4;
        lifetime = 0xB4; // ROM: move.w #$B4,obSubtype
        phase = 2;
    }

    /**
     * Phase 2 (loc_1A9C0): Travel to target X, then chase Sonic.
     * ROM: SpeedToPos, check if reached target, then set collision and chase
     */
    private void updateTravel(AbstractPlayableSprite player) {
        if (xVel != 0) {
            // ROM: SpeedToPos — adds (velocity << 8) to 32-bit position
            posXFixed += (xVel << 8);
            posX = posXFixed >> 16;

            // ROM: BossPlasma_Drop keeps moving while obX-targetX has no
            // borrow, and only stops after the left-moving ball overshoots
            // below targetX. The non-FixBugs build then adds the negative
            // overshoot delta to obX (_incObj/86 FZ Plasma Ball Launcher.asm:166-177).
            int dist = posX - targetX;
            if (dist < 0) {
                // Reached target
                xVel = 0;
                posX += dist;
                posXFixed = posX << 16;
                // ROM: subq.w #1,objoff_32(a1) — decrement launcher's ball counter
            }
        }

        // ROM: move.b #0,obAnim — full plasma animation
        animId = Sonic1BossAnimations.ANIM_PLASMA_FULL;

        // ROM: subq.w #1,obSubtype — decrement lifetime
        lifetime--;
        if (lifetime <= 0) {
            // Lifetime expired during travel — transition to chase
            startChase(player);
        }
    }

    /**
     * Transition to chase phase.
     * ROM: loc_1A9E6 end section
     */
    private void startChase(AbstractPlayableSprite player) {
        phase = 4;
        hasCollision = true; // ROM: move.b #$9A,obColType
        animId = Sonic1BossAnimations.ANIM_PLASMA_SHORT; // ROM: move.b #1,obAnim

        // ROM: Calculate chase velocity
        if (player != null) {
            int dist = (player.getCentreX() & 0xFFFF) - posX;
            xVel = dist; // ROM: move.w d0,obVelX
        } else {
            xVel = 0;
        }
        yVel = 0x140; // ROM: move.w #$140,obVelY
        lifetime = 0xB4; // ROM: move.w #$B4,obSubtype
    }

    /**
     * Phase 4 (loc_1AA1E): Chase with gravity.
     * ROM: SpeedToPos, delete when Y >= boss_fz_y+$D0 or lifetime expired
     */
    private void updateChase(AbstractPlayableSprite player) {
        // ROM: SpeedToPos — adds (velocity << 8) to 32-bit position
        posXFixed += (xVel << 8);
        posYFixed += (yVel << 8);
        posX = posXFixed >> 16;
        posY = posYFixed >> 16;

        // ROM: cmpi.w #boss_fz_y+$D0,obY — check ground
        if (posY >= BOSS_FZ_Y + 0xD0) {
            destroyBall();
            return;
        }

        // ROM: subq.w #1,obSubtype — decrement lifetime
        lifetime--;
        if (lifetime <= 0) {
            destroyBall();
        }
    }

    private void destroyBall() {
        // ROM: subq.w #1,objoff_38(a1) — decrement launcher's ball count
        if (launcher != null) {
            launcher.onBallDestroyed();
        }
        setDestroyed(true);
    }

    private void updateAnimation() {
        SpriteAnimationScript script = PLASMA_ANIMATIONS.getScript(animId);
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        if (animId != animPrevId) {
            // ROM: animation changed -> reset obAniFrame/obTimeFrame
            animPrevId = animId;
            animScriptFrame = 0;
            animTimeFrame = 0;
        }

        // ROM: subq.b #1,obTimeFrame(a0) / bpl.s Anim_Wait
        animTimeFrame--;
        if (animTimeFrame >= 0) {
            return;
        }

        animTimeFrame = script.delay() & 0xFF;

        if (animScriptFrame < 0 || animScriptFrame >= script.frames().size()) {
            animScriptFrame = 0;
        }

        // ROM: andi.b #$1F,d0 before writing obFrame
        animFrame = script.frames().get(animScriptFrame) & 0x1F;
        animScriptFrame++;

        if (animScriptFrame < script.frames().size()) {
            return;
        }

        switch (script.endAction()) {
            case HOLD -> animScriptFrame = script.frames().size() - 1;
            case LOOP_BACK -> {
                int loopBack = script.endParam();
                if (loopBack <= 0) {
                    animScriptFrame = 0;
                } else {
                    int target = script.frames().size() - loopBack;
                    animScriptFrame = Math.max(target, 0);
                }
            }
            case SWITCH -> {
                int nextAnim = script.endParam();
                if (nextAnim == animId) {
                    animScriptFrame = 0;
                } else {
                    animId = nextAnim;
                    animPrevId = -1;
                }
            }
            case LOOP -> animScriptFrame = 0;
            default -> animScriptFrame = 0;
        }
    }

    @Override
    public int getCollisionFlags() {
        // ROM: obColType = $9A during chase phase (category 4 = hurt, size $1A)
        if (hasCollision) {
            return 0x9A;
        }
        return 0;
    }

    @Override
    public int getCollisionProperty() {
        // Plasma balls have no collision property (not a boss with hit count)
        return 0;
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
    public int getPriorityBucket() {
        return 3;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.FZ_PLASMA);
        if (renderer == null || !renderer.isReady()) return;

        int frame = Math.min(animFrame, 10);
        renderer.drawFrameIndex(frame, posX, posY, false, false);
    }
}
