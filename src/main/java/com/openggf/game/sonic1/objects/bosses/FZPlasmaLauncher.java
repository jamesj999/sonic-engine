package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.boss.BossExplosionObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x86 — FZ Plasma Ball Launcher (generator portion).
 * ROM: _incObj/86 FZ Plasma Ball Launcher.asm (routines 0-6)
 *
 * Fixed position at boss_fz_x + $138, boss_fz_y + $2C.
 * State machine:
 *   Routine 2 (Generator): Wait for activation from boss, or become explosion if defeated
 *   Routine 4 (MakeBalls): Spawn 4 plasma balls with calculated positions
 *   Routine 6 (Wait): When all balls done (objoff_38 == 0), signal parent completion
 *
 * SolidObject params: d1=$13, d2=8, d3=$11
 */
public class FZPlasmaLauncher extends AbstractBossChild implements SolidObjectProvider, RewindRecreatable {

    private static final int LAUNCHER_X = Sonic1Constants.BOSS_FZ_X + 0x138;
    private static final int LAUNCHER_Y = Sonic1Constants.BOSS_FZ_Y + 0x2C;

    // SolidObject params: d1=$13, d2=8, d3=$11
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x13, 8, 0x11);

    // BossPlasma_Main writes obWidth, never obActWid, and nothing else does, so
    // the slot keeps the zero DeleteObject left in it. See getOnScreenHalfWidth().
    private static final int ACT_WIDTH = 0;


    // State: 0 = idle (Generator), 1 = spawning balls, 2 = waiting for balls
    private int launcherState;

    // Track active plasma balls
    private int activeBallCount; // objoff_38
    private boolean activated;   // objoff_29

    // Animation frame (0 = red, 1 = white, 2-3 = sparking)
    private int animFrame;
    private int animTimer;

    // Balls spawned tracking
    private final List<FZPlasmaBall> activeBalls = new ArrayList<>();
    private boolean explodedOnDefeat;

    public FZPlasmaLauncher(Sonic1FZBossInstance parent) {
        super(parent, "FZ Plasma Launcher", 3, Sonic1ObjectIds.BOSS_PLASMA);
        
        this.currentX = LAUNCHER_X;
        this.currentY = LAUNCHER_Y;
        this.launcherState = 0;
        this.activated = false;
        this.activeBallCount = 0;
        this.animFrame = 0;
        this.animTimer = 0;
        this.explodedOnDefeat = false;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic1FZBossInstance boss = firstLiveFzBoss(ctx);
        if (boss == null) {
            return null;
        }
        // FZ has one boss group; preserve the deleted explicit restore path's first-live
        // matching while rebuilding the parent-side graph link locally.
        FZPlasmaLauncher restored = new FZPlasmaLauncher(boss);
        boss.adoptPlasmaLauncherForRewind(restored);
        return restored;
    }

    void adoptPlasmaBallForRewind(FZPlasmaBall ball) {
        if (ball == null) {
            return;
        }
        activeBalls.removeIf(FZPlasmaBall::isDestroyed);
        if (!activeBalls.contains(ball)) {
            activeBalls.add(ball);
        }
        activeBallCount = activeBalls.size();
    }

    private static Sonic1FZBossInstance firstLiveFzBoss(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1FZBossInstance boss && !boss.isDestroyed()) {
                return boss;
            }
        }
        return null;
    }

    /**
     * Called by the boss to activate the plasma launcher for a round.
     * ROM: objoff_29 set to non-zero
     */
    public void activateForBoss() {
        activated = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!beginUpdate(vIntRunCount)) return;

        Sonic1FZBossInstance fzParent = (Sonic1FZBossInstance) parent;

        switch (launcherState) {
            case 0 -> updateGenerator(fzParent);
            case 1 -> updateMakeBalls(fzParent);
            case 2 -> updateWaitForBalls(fzParent);
        }

        // Update animation
        updateAnimation();

        updateDynamicSpawn();
    }

    /**
     * Routine 2 (BossPlasma_Generator): Wait for boss activation.
     * ROM: Check if boss is in defeat phase (objoff_34 == 6) -> become explosion.
     * Otherwise wait for objoff_29 activation.
     */
    private void updateGenerator(Sonic1FZBossInstance fzParent) {
        // ROM: cmpi.b #6,objoff_34(a1) — if boss defeated, become explosion
        if (fzParent.isBossDefeated()) {
            if (!explodedOnDefeat) {
                ObjectRenderManager renderManager = services().renderManager();
                if (renderManager != null && services().objectManager() != null) {
                    spawnFreeChild(() -> new BossExplosionObjectInstance(
                            currentX, currentY, Sonic1ObjectIds.EXPLOSION, Sonic1Sfx.BOSS_EXPLOSION.id));
                }
                explodedOnDefeat = true;
            }
            setDestroyed(true);
            return;
        }

        animFrame = 0; // red (Ani_PLaunch anim 0)

        if (activated) {
            activated = false;
            launcherState = 1;
            animFrame = 0; // Will switch to sparking in MakeBalls
        }
    }

    /**
     * Routine 4 (BossPlasma_MakeBalls): Spawn 4 plasma balls.
     * ROM: Target X = boss_fz_x + $128 + (ballIndex * -$4F) + random($1F) - $10
     */
    private void updateMakeBalls(Sonic1FZBossInstance fzParent) {
        // Spawn 4 balls
        activeBalls.clear();
        activeBallCount = 4;
        var rng = services().rng();

        for (int i = 0; i < 4; i++) {
            // ROM: Target X calculation
            int random = rng.nextWord();
            int targetX = Sonic1Constants.BOSS_FZ_X + 0x128 + (i * -0x4F);
            targetX += (random & 0x1F) - 0x10;

            final int fTargetX = targetX;
            // ROM BossPlasma_Loop calls FindNextFreeObj, not FindFreeObj. Keeping
            // every ball after the launcher in SST order is observable when the
            // last ball deletes: the launcher has already run that frame and only
            // sees objoff_38 == 0 on the following ExecuteObjects pass.
            FZPlasmaBall ball = spawnChild(
                    () -> new FZPlasmaBall(this, LAUNCHER_X, LAUNCHER_Y, fTargetX));
            // Boss children are also dispatched from their parent's update, where
            // ObjectManager's live cursor still names the boss slot. Anchor the
            // FindNextFreeObj scan to the launcher's actual SST slot explicitly.
            activeBalls.add(ball);
        }

        launcherState = 2; // Wait for balls to finish
        animFrame = 1; // ROM: move.b #1,obAnim — sparking animation
    }

    /**
     * Routine 6 (loc_1A962): Wait for all balls to complete.
     * ROM: tst.w objoff_38 — when all done, signal parent.
     */
    private void updateWaitForBalls(Sonic1FZBossInstance fzParent) {
        // Check if all balls are done
        activeBalls.removeIf(FZPlasmaBall::isDestroyed);
        activeBallCount = activeBalls.size();

        if (activeBallCount == 0) {
            // ROM: Signal parent completion
            fzParent.onPlasmaComplete();
            launcherState = 0; // Back to generator state
        }

        // ROM: White sparking animation while waiting
        animFrame = 2; // Ani_PLaunch anim 2 (white sparking)
    }

    /**
     * Called by a plasma ball when it's destroyed.
     */
    public void onBallDestroyed() {
        activeBallCount--;
    }

    private void updateAnimation() {
        animTimer++;
        // Simple animation cycling based on state
        if (launcherState >= 1) {
            // Sparking animation — cycle frames
            if ((animTimer & 3) == 0) {
                animFrame = (animFrame == 2) ? 3 : 2;
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    /**
     * The launcher's ROM {@code obActWid}, which is zero.
     *
     * <p>Not an omission here — an omission in the ROM.
     * {@code BossPlasma_Main} sets the object up with
     * {@code move.b #16/2,obWidth(a0)} and {@code move.b #16/2,obHeight(a0)}
     * (docs/s1disasm/_incObj/85,84,86 Boss - FZ Main, Cylinders, and Plasma
     * Balls.asm:990-1001). That is {@code obWidth}, not {@code obActWid} — the
     * same fumble the listing flags for the cylinders at {@code :776-778}, except
     * that one was repaired in REV01 by the added {@code obActWid} write at
     * {@code :781} and this one was never repaired in either revision. No other
     * site writes the byte for this object.
     *
     * <p>The slot therefore holds zero for the object's whole life:
     * {@code BossFinal_Skip} hands it a slot from {@code FindFreeObj}
     * ({@code :108-115}), and {@code DeleteObject} zeroes all {@code $40} bytes
     * of a slot before it can be handed out again
     * (docs/s1disasm/_incObj/sub DeleteObject.asm:10-19).
     *
     * <p>Zero is not inert. {@code Sonic_Balance} forms
     * {@code d1 = obActWid + sonic_x - obj_x} and
     * {@code d2 = 2*obActWid - 4}, balancing when {@code d1 < 4} or
     * {@code d1 >= d2} (docs/s1disasm/_incObj/01 Sonic.asm:422-431). At
     * {@code obActWid = 0} that is {@code dx < 4} or {@code dx >= -4}, which
     * between them cover every position — so the ROM balances on the launcher
     * the entire time the player stands on it, left-facing inboard of
     * {@code dx = 4} and right-facing beyond it. The inherited 16 balanced only
     * outside {@code |dx| >= 12}. {@code BossPlasma_Collision}'s separately
     * authored {@code d1 = #16/2+sonic_solid_width} = {@code $13} with a
     * stood-on {@code d3 = #34/2} ({@code :1022-1027}) is what makes it a
     * standable surface, and is unchanged.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return ACT_WIDTH;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.FZ_PLASMA_LAUNCHER);
        if (renderer == null || !renderer.isReady()) return;

        int frame = Math.min(animFrame, 3);
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 3;
    }
}
