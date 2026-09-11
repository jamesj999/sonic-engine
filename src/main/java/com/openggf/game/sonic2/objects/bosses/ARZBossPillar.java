package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.util.List;

/**
 * ARZ Boss Pillar - Rising/lowering solid platform.
 * ROM Reference: s2.asm Obj89 (subtype 4)
 *
 * States:
 * - RAISING: Pillar rises from floor, screen shakes
 * - IDLE: Pillar at target height, spawns arrows when hammer hits
 * - LOWERING: Pillar sinks when boss defeated
 */
public class ARZBossPillar extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final int PILLAR_SUB_RAISING = 0;
    private static final int PILLAR_SUB_IDLE = 2;
    private static final int PILLAR_SUB_LOWERING = 4;

    private static final int LEFT_PILLAR_X = 0x2A50;
    private static final int RIGHT_PILLAR_X = 0x2B70;
    private static final int PILLAR_TARGET_Y = 0x488;
    private static final int PILLAR_START_Y = 0x510;

    private static final SolidObjectParams PILLAR_SOLID_PARAMS = new SolidObjectParams(0x23, 0x44, 0x45, 0, 4);
    private Sonic2ARZBossInstance mainBoss;

    private int x;
    private int y;
    private int renderFlags;
    private int routineSecondary;
    private int mappingFrame;

    private boolean pillarShaking;
    private int pillarShakeTime;

    public ARZBossPillar(ObjectSpawn spawn, Sonic2ARZBossInstance mainBoss) {
        super(spawn, "ARZ Boss Pillar");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.routineSecondary = PILLAR_SUB_RAISING;
        this.mappingFrame = 0;
        this.pillarShaking = false;
        this.pillarShakeTime = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2ARZBossInstance parent = findClosestLiveParent(ctx);
        return parent == null ? null : new ARZBossPillar(ctx.spawn(), parent);
    }

    private static Sonic2ARZBossInstance findClosestLiveParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        Sonic2ARZBossInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        int childX = ctx.spawn().x();
        int childY = ctx.spawn().y();
        for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
            if (inst instanceof Sonic2ARZBossInstance boss && !boss.isDestroyed()) {
                long dx = (long) boss.getX() - childX;
                long dy = (long) boss.getY() - childY;
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = boss;
                }
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Check if main boss is defeated
        if (mainBoss != null && mainBoss.isInDefeatSequence()) {
            routineSecondary = PILLAR_SUB_LOWERING;
        }

        switch (routineSecondary) {
            case PILLAR_SUB_RAISING -> updatePillarRaise(vIntRunCount);
            case PILLAR_SUB_IDLE -> updatePillarIdle(vIntRunCount);
            case PILLAR_SUB_LOWERING -> updatePillarLower(player);
        }
    }

    /**
     * Pillar raising state.
     * ROM: Obj89_Pillar_Sub0 (lines 64813-64828)
     */
    private void updatePillarRaise(int vIntRunCount) {
        if ((vIntRunCount & 0x1F) == 0) {
            services().playSfx(Sonic2Sfx.RUMBLING_2.id);
        }
        y -= 1;
        if (y <= PILLAR_TARGET_Y) {
            y = PILLAR_TARGET_Y;
            routineSecondary = PILLAR_SUB_IDLE;
            // ROM: move.b #0,(Screen_Shaking_Flag).w - stop screen shaking
            services().gameState().setScreenShakeActive(false);
        }
        mappingFrame = 0;
    }

    /**
     * Pillar idle state.
     * ROM: Obj89_Pillar_Sub2 (lines 64831-64857)
     */
    private void updatePillarIdle(int vIntRunCount) {
        if (mainBoss != null && mainBoss.isHammerActive()) {
            boolean isLeftPillar = (renderFlags & 1) == 0;
            boolean hammerTargetingLeft = !mainBoss.isTargetingRight();

            if (isLeftPillar == hammerTargetingLeft) {
                mainBoss.clearHammerFlag();
                mainBoss.spawnArrowAndEyes(isLeftPillar);
                pillarShaking = true;
            }
        }
        updatePillarShake(vIntRunCount);
        mappingFrame = 0;
    }

    /**
     * Pillar lowering state (boss defeated).
     * ROM: Obj89_Pillar_Sub4 (lines 64963-65001)
     */
    private void updatePillarLower(AbstractPlayableSprite player) {
        services().gameState().setScreenShakeActive(true);

        y += 1;
        if (y >= PILLAR_START_Y) {
            services().gameState().setScreenShakeActive(false);
            dropStandingPlayers(player);
            setDestroyed(true);
            return;
        }
        mappingFrame = 0;
    }

    private void dropStandingPlayers(AbstractPlayableSprite player) {
        if (player == null || services().objectManager() == null) {
            return;
        }
        if (services().objectManager().isRidingObject(player, this)) {
            services().objectManager().clearRidingObject(player);
            player.setOnObject(false);
            player.setAir(true);
        }
    }

    private void updatePillarShake(int vIntRunCount) {
        if (!pillarShaking) {
            return;
        }
        if (pillarShakeTime <= 0) {
            pillarShakeTime = 0x1F;
        }
        pillarShakeTime--;
        if (pillarShakeTime <= 0) {
            pillarShaking = false;
            pillarShakeTime = 0;
            resetPillarBasePosition();
            return;
        }
        int baseX = isRightPillar() ? RIGHT_PILLAR_X : LEFT_PILLAR_X;
        int offset = ((vIntRunCount & 1) == 0) ? 1 : -1;
        x = baseX + offset;
        y = PILLAR_TARGET_Y + offset;
    }

    private void resetPillarBasePosition() {
        x = isRightPillar() ? RIGHT_PILLAR_X : LEFT_PILLAR_X;
        y = PILLAR_TARGET_Y;
    }

    private boolean isRightPillar() {
        return (renderFlags & 1) != 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.ARZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
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
    public int getPriorityBucket() {
        return 2;
    }

    @Override
    public int getOnScreenHalfHeight() {
        // Obj89 leaves render_flags.explicit_height clear, so S2 BuildSprites
        // uses the approximate-Y path with a 32 px radius (docs/s2disasm/s2.asm:
        // 30603-30611). The engine recomputes the bit as a live proxy for
        // SolidObject_OnScreenTest instead of retaining the prior BuildSprites
        // render_flags bit; the one-pixel lower-edge slack keeps Obj89's edge
        // SolidObject frame visible without widening the preceding offscreen row.
        return 0x21;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return PILLAR_SOLID_PARAMS;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return routineSecondary != PILLAR_SUB_LOWERING;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj89_Pillar_SolidObject calls the standard SolidObject helper.
        // Its initial horizontal range check uses BHI, so relX == 2*d1 is
        // still a side contact rather than a release.
        // docs/s2disasm/s2.asm:35338-35436,65330-65339,65531-65539
        return true;
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // At the exact right edge SolidObject reaches the side path with d0=0;
        // sub.w d0,x_pos preserves the native x_sub low word while publishing
        // Status_Push.
        // docs/s2disasm/s2.asm:35420-35436,65330-65339,65531-65539
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // Obj89_Pillar_Sub0/Sub2 call Obj89_Pillar_SolidObject before raise/shake movement.
        // docs/s2disasm/s2.asm:65330-65345,65348-65374,65531-65539
        return true;
    }

    @Override
    public boolean usesPreUpdateYForContinuedRide(PlayableEntity player) {
        // MvSonicOnPtfm inherits the same pre-motion y_pos used by Obj89_Pillar_SolidObject.
        // docs/s2disasm/s2.asm:65330-65345,65348-65374,65531-65539
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The pillar changes y_pos every raising/lowering frame, but its
        // standing/pushing bits remain in the same Obj89 SST slot. Keying the
        // folded engine latch by the rewritten dynamic spawn prevents the old
        // side-push owner from becoming unreachable when the pillar moves.
        // docs/s2disasm/s2.asm:65330-65374,65531-65539
        return true;
    }

    @Override
    public boolean preservesMovingSideContactVelocity(PlayableEntity player) {
        // Obj89_Pillar_Sub0/Sub2 run Obj89_Pillar_SolidObject before the pillar
        // body update, and the helper calls ordinary SolidObject at the temporary
        // y_pos+4 anchor. In the ROM object-slot order, that side contact can set
        // Status_Push before Tails' later CPU/movement path writes the frame's
        // x_vel/inertia when this contact is newly setting Status_Push after
        // Tails' CPU/movement slot. The main player has no later sidekick
        // CPU/movement slot to overwrite the stop, so Sonic follows the normal
        // SolidObject_StopCharacter path and clears x_vel/inertia. The engine's
        // inline post-physics checkpoint sees the new-push side contact after
        // movement, so preserve only the CPU sidekick handoff while retaining
        // the side correction and push bits.
        // A one-pixel-inside contact is still part of that handoff only when
        // TailsCPU_Normal_FollowLeft applied the same-frame -1 x_pos nudge
        // before Tails movement; without that nudge, the later Obj89
        // SolidObject call reaches SolidObject_StopCharacter.
        // docs/s2disasm/s2.asm:35413-35436,38952-38975,65330-65374,65531-65539
        int anchorX = isRightPillar() ? RIGHT_PILLAR_X : LEFT_PILLAR_X;
        int rightEdgeX = anchorX + PILLAR_SOLID_PARAMS.halfWidth();
        return player != null && player.isCpuControlled() && !player.getAir() && player.getGSpeed() < 0
                && (!(player instanceof AbstractPlayableSprite sprite) || !sprite.getPushing())
                && (player.getCentreX() >= rightEdgeX
                || (player.getCentreX() == rightEdgeX - 1 && hasSameFrameLeftFollowNudge(player)));
    }

    @Override
    public boolean preservesSidekickCpuPushGraceAfterRideClears(PlayableEntity player) {
        // Obj89_Pillar_SolidObject temporarily tests at y_pos+4 and calls
        // SolidObject before TailsCPU_Normal. A side contact sets Status_Push
        // for Tails, and the CPU's push-bypass branch can then jump directly to
        // the $3F auto-jump gate before Tails_InputAcceleration clears it.
        // docs/s2disasm/s2.asm:39287-39300,39369-39378,65330-65339,65531-65539
        if (!(player instanceof AbstractPlayableSprite sprite)
                || !sprite.isCpuControlled()
                || player.getAir()
                || player.isOnObject()
                || player.getRolling()) {
            return false;
        }
        int sideEdgeX = isRightPillar()
                ? x - PILLAR_SOLID_PARAMS.halfWidth()
                : x + PILLAR_SOLID_PARAMS.halfWidth();
        if (Math.abs(player.getCentreX() - sideEdgeX) > 1) {
            return false;
        }
        int solidAnchorY = y + PILLAR_SOLID_PARAMS.offsetY() + 4;
        int verticalBand = PILLAR_SOLID_PARAMS.airHalfHeight() + player.getYRadius() + 4;
        return Math.abs(player.getCentreY() - solidAnchorY) <= verticalBand;
    }

    private boolean hasSameFrameLeftFollowNudge(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            return false;
        }
        SidekickCpuController controller = sprite.getCpuController();
        if (controller == null) {
            return false;
        }
        SidekickCpuController.NormalStepDiagnostics diagnostics = controller.getLatestNormalStepDiagnostics();
        return diagnostics != null && diagnostics.appliedFollowNudge() < 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Pillar doesn't need special contact handling
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
