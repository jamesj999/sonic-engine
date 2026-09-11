package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Barrel-fired shot used by the AIZ miniboss.
 *
 * ROM references:
 * - sub_6885A / loc_688B0 (projectile setup)
 * - loc_68C96 / loc_68D5E / loc_68D70 (hazardous variant + floor explode)
 * - sub_68EE4 (camera-relative top spawn X selection)
 */
public class AizMinibossBarrelShotChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int COLLISION_FLAGS_HAZARD = 0x98;
    private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
    private static final int SHIELD_REACTION_FLAGS = SHIELD_REACTION_BOUNCE | (1 << 4);
    private static final int FRAME_RISE_A = 0x0C;
    private static final int FRAME_RISE_B = 0x0D;
    private static final int PROJECTILE_PALETTE = 0;
    private static final int Y_RADIUS = 8; // ROM loc_68CE4: y_radius
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            false,
            TouchShieldDeflectCapability.SHIELD_DEFLECT,
            SHIELD_REACTION_FLAGS,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private static final int[] SELECT_TABLE_NORMAL = {
            2, 3, 4, 0, 0, 2, 4, 0, 1, 3, 4, 0, 0, 1, 4, 0
    };
    private static final int[] SELECT_TABLE_FLIPPED = {
            3, 2, 0, 0, 4, 3, 1, 0, 4, 2, 0, 0, 3, 2, 1, 0
    };
    private static final int[] X_COLUMNS_NORMAL = {0x24, 0x4C, 0x74, 0x9C, 0xC4};
    private static final int[] X_COLUMNS_FLIPPED = {0x7C, 0xA4, 0xCC, 0xF4, 0x11C};
    private static final int[] TOP_DROP_STAGGER_DELAYS = {0, 0x20, 0x40}; // word_68F28
    private static final int[] IMPACT_X_OFFSETS = {0, 8, -8, 4, -4, 4, -4};   // ChildObjDat_690D8
    private static final int[] IMPACT_Y_OFFSETS = {-0x24, -0x1C, -0x1C, -0x14, -0x14, -4, -4};

    enum Mode {
        SIMPLE,
        ADVANCED_NON_COLLIDING,
        ADVANCED_COLLIDING
    }

    private enum State {
        PRELAUNCH,
        PRE_DROP_PRIME,
        PRE_DROP_STAGGER,
        TOP_DROP_SIMPLE,
        TOP_DROP_ADVANCED
    }
    private final AbstractBossInstance parent;
    /** ROM: parent3 of the shot = the barrel that spawned it. */
    private final AizMinibossFlameBarrelChild barrel;
    // barrelSubtype/mode are non-final so the rewind field capturer reapplies them
    // after the recreate hook rebuilds the shot with placeholders; parent/barrel are relinked.
    private int barrelSubtype;
    private Mode mode;

    private int currentX;
    private int currentY;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;
    private int frame;
    private int animTimer;
    private int timer;
    private State state;
    private boolean vFlip;
    private boolean needsInitSfx;

    public AizMinibossBarrelShotChild(AbstractBossInstance parent,
                                      AizMinibossFlameBarrelChild barrel,
                                      int x,
                                      int y,
                                      Mode mode) {
        super(new ObjectSpawn(x, y, 0x90, barrel.getBarrelSubtype(), 0, false, 0), "AIZMinibossBarrelShot");
        this.parent = parent;
        this.barrel = barrel;
        this.barrelSubtype = barrel.getBarrelSubtype() & 0xFF;
        this.mode = mode;

        this.currentX = x;
        this.currentY = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;

        // sub_6885A equivalent
        this.xVel = 0;
        this.yVel = -0x400;
        this.frame = FRAME_RISE_A;
        this.animTimer = 2;
        this.timer = 0x60;
        this.state = State.PRELAUNCH;
        this.vFlip = false;
        this.needsInitSfx = true;
    }

    private AizMinibossBarrelShotChild(ObjectSpawn spawn) {
        super(spawn, "AIZMinibossBarrelShot");
        this.parent = null;
        this.barrel = null;
        this.barrelSubtype = spawn.subtype() & 0xFF;
        this.mode = Mode.SIMPLE;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xFixed = currentX << 16;
        this.yFixed = currentY << 16;
        this.xVel = 0;
        this.yVel = -0x400;
        this.frame = FRAME_RISE_A;
        this.animTimer = 2;
        this.timer = 0x60;
        this.state = State.PRELAUNCH;
        this.vFlip = false;
        this.needsInitSfx = true;
    }

    @Override
    public AizMinibossBarrelShotChild recreateForRewind(RewindRecreateContext ctx) {
        AizMinibossInstance boss = AizMinibossRewindLinks.nearestBoss(ctx);
        AizMinibossFlameBarrelChild restoredBarrel = AizMinibossRewindLinks.nearestBarrel(ctx);
        if (boss == null || restoredBarrel == null || ctx.spawn() == null) {
            return null;
        }
        return new AizMinibossBarrelShotChild(
                boss,
                restoredBarrel,
                ctx.spawn().x(),
                ctx.spawn().y(),
                Mode.SIMPLE);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (needsInitSfx) {
            needsInitSfx = false;
            services().playSfx(Sonic3kSfx.PROJECTILE.id);
        }
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (parent == null || parent.isDestroyed() || parent.getState().defeated) {
            setDestroyed(true);
            return;
        }

        switch (state) {
            case PRELAUNCH -> updatePrelaunch();
            case PRE_DROP_PRIME -> updatePreDropPrime();
            case PRE_DROP_STAGGER -> updatePreDropStagger();
            case TOP_DROP_SIMPLE -> updateTopDropSimple();
            case TOP_DROP_ADVANCED -> updateTopDropAdvanced();
        }
    }

    private void updatePrelaunch() {
        move();
        animateRise();

        timer--;
        if (timer >= 0) {
            return;
        }

        timer = 8;
        state = State.PRE_DROP_PRIME;
    }

    private void updatePreDropPrime() {
        timer--;
        if (timer >= 0) {
            return;
        }
        if (mode == Mode.SIMPLE) {
            enterTopDropPhase();
            timer = 0x60; // loc_688B0
            state = State.TOP_DROP_SIMPLE;
            return;
        }
        timer = getTopDropStaggerDelay();
        state = State.PRE_DROP_STAGGER;
    }

    private void updatePreDropStagger() {
        timer--;
        if (timer >= 0) {
            return;
        }
        enterTopDropPhase();
        state = State.TOP_DROP_ADVANCED;
    }

    private void updateTopDropSimple() {
        move();
        animateRise();
        if (--timer < 0 || !isOnScreen(128)) {
            setDestroyed(true);
        }
    }

    private void updateTopDropAdvanced() {
        move();
        animateRise();

        // ROM loc_68D5E: Animate_Raw + MoveSprite2 + ObjHitFloor_DoRoutine.
        // The ROM has no explicit off-screen check here — the arena camera is locked
        // and the shot is placed at camera-relative coordinates, so it's always on-screen.
        // Floor check with y_radius=8 (ROM loc_68CE4).
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (floor.hasCollision()) {
            onFloorImpact();
            return;
        }
        // Failsafe: the ROM arena always has solid floor within the visible screen.
        // If the terrain check misses (e.g. post-fire mutation seam), force impact
        // once the shot falls past the visible area bottom.
        int screenBottom = services().camera().getY() + 224;
        if (currentY >= screenBottom) {
            onFloorImpact();
        }
    }

    private void onFloorImpact() {
        services().playSfx(Sonic3kSfx.MISSILE_EXPLODE.id);
        spawnImpactFlames();
        setDestroyed(true);
    }

    private void enterTopDropPhase() {
        // ROM: sub_68EE4 — reads/writes the BARREL's $39 counter, not the boss's.
        int timerCounter = (barrel.getPositionCounter() + 4) & 0xFF;
        barrel.setPositionCounter(timerCounter);

        int index = ((barrelSubtype >> 1) + (timerCounter & 0x0C)) & 0x0F;
        boolean hFlip = (parent.getState().renderFlags & 1) != 0;
        int selector = hFlip ? SELECT_TABLE_FLIPPED[index] : SELECT_TABLE_NORMAL[index];
        int xOffset = hFlip ? X_COLUMNS_FLIPPED[selector] : X_COLUMNS_NORMAL[selector];

        currentX = services().camera().getX() + xOffset;
        currentY = services().camera().getY() - 0x20;
        xFixed = currentX << 16;
        yFixed = currentY << 16;

        yVel = 0x400;
        xVel = 0;
        animTimer = 2;
        vFlip = true; // bset #1, render_flags in loc_688B0 / loc_68D1C
        services().playSfx(Sonic3kSfx.MISSILE_THROW.id);
    }

    private int getTopDropStaggerDelay() {
        int tableIndex = barrelSubtype >> 1;
        if (tableIndex < 0 || tableIndex >= TOP_DROP_STAGGER_DELAYS.length) {
            return 0;
        }
        return TOP_DROP_STAGGER_DELAYS[tableIndex];
    }

    private void spawnImpactFlames() {
        if (services().objectManager() == null) {
            return;
        }
        boolean hazardous = mode == Mode.ADVANCED_COLLIDING;
        for (int i = 0; i < IMPACT_X_OFFSETS.length; i++) {
            int x = currentX + IMPACT_X_OFFSETS[i];
            int y = currentY + IMPACT_Y_OFFSETS[i];
            int subtype = i * 2; // loc_68D9C -> sub_68928 delay source
            spawnChild(() -> new AizMinibossImpactFlameChild(x, y, subtype, hazardous));
        }
    }

    private void move() {
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;
    }

    private void animateRise() {
        if (--animTimer > 0) {
            return;
        }
        animTimer = 2;
        frame = (frame == FRAME_RISE_A) ? FRAME_RISE_B : FRAME_RISE_A;
    }

    @Override
    public int getCollisionFlags() {
        if (mode != Mode.ADVANCED_COLLIDING || state != State.TOP_DROP_ADVANCED || isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS_HAZARD;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getShieldReactionFlags() {
        // Bit 3: bounce shield deflection. Bit 4: fire shield immunity.
        return SHIELD_REACTION_FLAGS;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean onShieldDeflect(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        setDestroyed(true);
        return true;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(frame, currentX, currentY, false, vFlip, PROJECTILE_PALETTE);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: sub_6885A uses word_6902A with priority $280 (bucket 5) during rise.
        // loc_688B0/loc_68D48 changes to priority $80 (bucket 1) for drop phase.
        return (state == State.TOP_DROP_SIMPLE || state == State.TOP_DROP_ADVANCED) ? 1 : 5;
    }
}
