package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * LBZ2 Knuckles cameo passed during the Robotnik-ship hang ride.
 *
 * <p>ROM: {@code CutsceneKnux_LBZ2} (sonic3k.asm 129570-129757). Knuckles
 * waits on the swing platform, taunts when the ship approaches ({@code $38}
 * bit 1), turns and idles, panics when the Death Egg launch shake starts
 * ({@code Screen_shake_flag}), and is flung off when the swing children
 * finish their six anchor crossings ({@code $38} bit 3).
 */
public final class CutsceneKnucklesLbz2Instance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int SUBTYPE_LBZ2 = 0x18;
    private static final int SUPPORT_PILLAR_DX = -0xC8;
    private static final int SUPPORT_PILLAR_DY = 0x14;
    private static final int SUPPORT_PILLAR_RENDER_FLAGS = 0x02;
    /** ROM MoveSprite_LightGravity: moveq #$20,d1. */
    private static final int LIGHT_GRAVITY = 0x20;

    /** ROM byte_666D2 (Animate_RawNoSSTMultiDelay pairs: frame, delay). */
    private static final int[][] TAUNT_SCRIPT = {
            {0x20, 5}, {0x21, 5}, {0x22, 0x14}, {0x23, 3}, {0x24, 0x0F}, {0x21, 5}, {0x20, 5}
    };
    /** ROM byte_666B9 intro frames before the $F8 jump into byte_666BF. */
    private static final int[] IDLE_INTRO_FRAMES = {0x1C, 0x1C, 0x1D};
    /** ROM byte_666BF loop frames (delay 7). */
    private static final int[] IDLE_LOOP_FRAMES = {0x1E, 0x1F};
    /** ROM byte_6669A loop (delay 7) as displayed from anim_frame 1: 2,3,1. */
    private static final int[] ALARMED_LOOP_FRAMES = {2, 3, 1};
    private static final int RAW_ANIM_DELAY = 7;

    private enum Routine {
        WAIT_TRIGGER,   // ROM routine 2
        TAUNT,          // ROM routine 4
        IDLE,           // ROM routine 6 (idle anim, watching Screen_shake_flag)
        ALARMED,        // ROM routine 8 (waiting for $38 bit 3)
        FALL_SPLASH,    // ROM routine $A
        FALL            // ROM routine $C
    }

    private final List<SwingChild> swingChildren = new ArrayList<>(4);
    private final List<LbzKnuxPillarInstance> supportPillars = new ArrayList<>(1);
    private final List<Integer> musicFadeTargets = new ArrayList<>(2);
    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;
    private boolean initialized;
    private boolean triggered;
    private boolean flingRequested;
    private boolean splashed;
    /** ROM render_flags bit 0 (hFlip in draw call, matching CutsceneKnucklesAiz2Instance). */
    private boolean renderXFlip = true;
    private int mappingFrame = 0x20;
    private Routine routine = Routine.WAIT_TRIGGER;
    private int animIndex;
    private int animTimer;
    private boolean idleIntroDone;

    public CutsceneKnucklesLbz2Instance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnucklesLBZ2");
        this.x = spawn.x();
        this.y = spawn.y();
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void setServices(ObjectServices services) {
        super.setServices(services);
        initializeAfterServices();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        initializeAfterServices();
        if (isDestroyed()) {
            return;
        }
        switch (routine) {
            case WAIT_TRIGGER -> updateWaitTrigger();
            case TAUNT -> updateTaunt();
            case IDLE -> updateIdle();
            case ALARMED -> updateAlarmed();
            case FALL_SPLASH -> updateFallSplash();
            case FALL -> updateFalling();
        }
        updateDynamicSpawn(x, y);
        // ROM: Sprite_CheckDeleteTouchSlotted range despawn.
        if (!isInRangeAt(x)) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, x, y, renderXFlip, false);
        }
    }

    /** ROM loc_8D3AC: the ship sets $38 bit 1 when within $50 px. */
    public void triggerFromShip() {
        triggered = true;
    }

    public void markFlungFromSwingForTest() {
        flingRequested = true;
    }

    void requestFlingFromSwing() {
        flingRequested = true;
    }

    /** ROM loc_62A82: the subtype-0 swing child drags Knuckles' x while swinging. */
    void copyLeaderX(int leaderX) {
        x = leaderX & 0xFFFF;
        xFixed = (x << 16) | (xFixed & 0xFFFF);
        updateDynamicSpawn(x, y);
    }

    public int getCentreX() {
        return x;
    }

    public int getCentreY() {
        return y;
    }

    public boolean isTriggeredForTest() {
        return triggered;
    }

    public boolean isScreenShakeObservedForTest() {
        return routine == Routine.ALARMED || routine == Routine.FALL_SPLASH || routine == Routine.FALL;
    }

    public boolean isFlingRequestedForTest() {
        return flingRequested;
    }

    public boolean hasSplashedForTest() {
        return splashed;
    }

    public int getMappingFrameForTest() {
        return mappingFrame;
    }

    public boolean isRenderXFlipForTest() {
        return renderXFlip;
    }

    public List<SwingChild> swingChildrenForTest() {
        return List.copyOf(swingChildren);
    }

    public List<LbzKnuxPillarInstance> supportPillarsForTest() {
        return List.copyOf(supportPillars);
    }

    public List<Integer> musicFadeTargetsForTest() {
        return List.copyOf(musicFadeTargets);
    }

    private void spawnMusicFade(int musicId) {
        musicFadeTargets.add(musicId);
        spawnDynamicObject(new SongFadeTransitionInstance(2 * 60, musicId));
    }

    private void initializeAfterServices() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (spawn.subtype() != SUBTYPE_LBZ2 || playerCharacter() == PlayerCharacter.KNUCKLES) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        // ROM loc_628E0: bset #0,render_flags, frame $20, sub_65DD6 music fade,
        // Pal_CutsceneKnux line 1, ChildObjDat_66592 (4 children at (2,$24)).
        renderXFlip = true;
        mappingFrame = 0x20;
        spawnMusicFade(Sonic3kMusic.KNUCKLES.id);
        AizIntroArtLoader.applyKnucklesPalette(services());
        ensureSupportPillar();
        for (int subtype = 0; subtype <= 6; subtype += 2) {
            int childSubtype = subtype;
            SwingChild child = spawnChild(() -> new SwingChild(this, childSubtype));
            swingChildren.add(child);
        }
    }

    private void ensureSupportPillar() {
        int pillarX = (x + SUPPORT_PILLAR_DX) & 0xFFFF;
        int pillarY = (y + SUPPORT_PILLAR_DY) & 0xFFFF;
        LbzKnuxPillarInstance existing = findExistingSupportPillar(pillarX, pillarY);
        if (existing != null) {
            supportPillars.add(existing);
            return;
        }
        LbzKnuxPillarInstance pillar = spawnFreeChild(() -> new LbzKnuxPillarInstance(
                new ObjectSpawn(pillarX, pillarY, Sonic3kObjectIds.LBZ_KNUX_PILLAR,
                        0, SUPPORT_PILLAR_RENDER_FLAGS, false, pillarY)));
        supportPillars.add(pillar);
    }

    private LbzKnuxPillarInstance findExistingSupportPillar(int pillarX, int pillarY) {
        ObjectManager manager = services().objectManager();
        if (manager == null) {
            return null;
        }
        for (LbzKnuxPillarInstance pillar : manager.activeObjectsOfType(LbzKnuxPillarInstance.class)) {
            if (!pillar.isDestroyed()
                    && (pillar.getCentreX() & 0xFFFF) == pillarX
                    && (pillar.getCentreY() & 0xFFFF) == pillarY) {
                return pillar;
            }
        }
        return null;
    }

    /** ROM loc_6290E: wait for $38 bit 1 from the ship. */
    private void updateWaitTrigger() {
        if (!triggered) {
            return;
        }
        routine = Routine.TAUNT;
        animIndex = 0;
        animTimer = TAUNT_SCRIPT[0][1];
        mappingFrame = TAUNT_SCRIPT[0][0];
    }

    /** ROM loc_62928: Animate_RawNoSSTMultiDelay byte_666D2, $F4 -> loc_62932. */
    private void updateTaunt() {
        if (--animTimer >= 0) {
            return;
        }
        animIndex++;
        if (animIndex >= TAUNT_SCRIPT.length) {
            // ROM loc_62932: routine 6, face right, idle anim + frame $1C.
            routine = Routine.IDLE;
            renderXFlip = false;
            mappingFrame = 0x1C;
            animIndex = 0;
            animTimer = 0;
            idleIntroDone = false;
            return;
        }
        mappingFrame = TAUNT_SCRIPT[animIndex][0];
        animTimer = TAUNT_SCRIPT[animIndex][1];
    }

    /** ROM loc_62942: idle anim until Screen_shake_flag. */
    private void updateIdle() {
        if (isScreenShaking()) {
            // ROM: routine 8, anim ptr byte_6669A, timers cleared.
            routine = Routine.ALARMED;
            animIndex = 0;
            animTimer = 0;
            return;
        }
        animateIdle();
    }

    /** ROM loc_62964: y += launchYDelta + alarmed anim until $38 bit 3. */
    private void updateAlarmed() {
        if (flingRequested) {
            // ROM loc_6297A: routine $A, x-flip, frame 9, fling velocities,
            // Obj_Song_Fade_ToLevelMusic.
            routine = Routine.FALL_SPLASH;
            renderXFlip = true;
            mappingFrame = 9;
            xVel = 0x0200;
            yVel = -0x0100;
            spawnMusicFade(Sonic3kMusic.LBZ2.id);
            return;
        }
        addLaunchYDelta();
        animateAlarmed();
    }

    /** ROM loc_629A8: splash when crossing Water_level, then keep falling. */
    private void updateFallSplash() {
        if (unsigned(y) >= unsigned(waterLevel())) {
            splashed = true;
            services().playSfx(Sonic3kSfx.SPLASH.id);
            routine = Routine.FALL;
        }
        fallStep();
    }

    /** ROM loc_629C0. */
    private void updateFalling() {
        fallStep();
    }

    private void fallStep() {
        addLaunchYDelta();
        // ROM MoveSprite_LightGravity: move by old velocity, then gravity $20.
        xFixed += xVel << 8;
        yFixed += yVel << 8;
        yVel += LIGHT_GRAVITY;
        x = (xFixed >> 16) & 0xFFFF;
        y = (yFixed >> 16) & 0xFFFF;
    }

    private void addLaunchYDelta() {
        int delta = launchYDelta();
        y = (y + delta) & 0xFFFF;
        yFixed += delta << 16;
    }

    /** Idle anim: byte_666B9 {7:$1C,$1C,$1D} then $F8 jump into byte_666BF {7:$1E,$1F loop}. */
    private void animateIdle() {
        if (--animTimer >= 0) {
            return;
        }
        animTimer = RAW_ANIM_DELAY;
        if (!idleIntroDone) {
            animIndex++;
            if (animIndex < IDLE_INTRO_FRAMES.length) {
                mappingFrame = IDLE_INTRO_FRAMES[animIndex];
                return;
            }
            idleIntroDone = true;
            animIndex = 0;
            mappingFrame = IDLE_LOOP_FRAMES[0];
            return;
        }
        animIndex = (animIndex + 1) % IDLE_LOOP_FRAMES.length;
        mappingFrame = IDLE_LOOP_FRAMES[animIndex];
    }

    /** Alarmed anim: byte_6669A displays 2,3,1 in a loop at delay 7. */
    private void animateAlarmed() {
        if (--animTimer >= 0) {
            return;
        }
        animTimer = RAW_ANIM_DELAY;
        mappingFrame = ALARMED_LOOP_FRAMES[animIndex];
        animIndex = (animIndex + 1) % ALARMED_LOOP_FRAMES.length;
    }

    private int waterLevel() {
        WaterSystem water = services().waterSystem();
        if (water == null) {
            return 0x7FFF;
        }
        return water.getWaterLevelY(Sonic3kZoneIds.ZONE_LBZ, 1) & 0xFFFF;
    }

    private boolean isScreenShaking() {
        return services().gameState() != null && services().gameState().isScreenShakeActive();
    }

    private int launchYDelta() {
        return services().zoneRuntimeRegistry()
                .currentAs(LbzZoneRuntimeState.class)
                .map(LbzZoneRuntimeState::getLaunchYDelta)
                .orElse(0);
    }

    private PlayerCharacter playerCharacter() {
        return services().zoneRuntimeRegistry()
                .currentAs(LbzZoneRuntimeState.class)
                .map(LbzZoneRuntimeState::playerCharacter)
                .orElse(PlayerCharacter.SONIC_ALONE);
    }

    private static int unsigned(int value) {
        return value & 0xFFFF;
    }

    /**
     * Swing chain/platform child (ROM loc_629CE). Pendulum motion around the
     * stored anchor x: acceleration always points at the anchor, a crossing is
     * detected when the side sign flips, the speed pair upgrades on the
     * crossing where the counter equals 3, and the sixth crossing flings
     * Knuckles ({@code $38} bit 3) and drops the chain into free fall.
     */
    public static final class SwingChild extends AbstractObjectInstance implements RewindRecreatable {
        /** ROM word_629FA: (initial x_vel, accel) per subtype. */
        private static final int[][] INITIAL_PARAMS = {
                {0x100, 0x10},
                {0x0C0, 0x0C},
                {0x080, 0x08},
                {0x040, 0x04}
        };
        /** ROM word_62A9E: upgraded (x_vel, accel) applied when $39 == 3. */
        private static final int[][] FAST_PARAMS = {
                {0x200, 0x20},
                {0x180, 0x18},
                {0x100, 0x10},
                {0x080, 0x08}
        };

        @RewindTransient(reason = "Structural parent link; restored by relinking to the live LBZ2 Knuckles cameo.")
        private final CutsceneKnucklesLbz2Instance parent;
        @RewindTransient(reason = "Constructor-derived from child spawn subtype.")
        private final int subtype;
        @RewindTransient(reason = "Constructor-derived from child spawn subtype.")
        private final int index;
        @RewindTransient(reason = "Constructor-derived from parent position at child creation.")
        private final int anchorX;
        private int x;
        private int y;
        private int xFixed;
        private int yFixed;
        private int xVel;
        private int yVel;
        private int accel;
        /** ROM $39: crossings remaining (6 -> 0). */
        private int crossingsRemaining;
        /** ROM $3C: last observed side (true = x < anchor). */
        private boolean sideLess;
        private boolean swinging;
        private boolean freeFalling;

        private SwingChild(CutsceneKnucklesLbz2Instance parent, int subtype) {
            super(new ObjectSpawn(parent.getCentreX() + 2,
                    // ROM loc_629CE: y += (2*subtype) << 3 stagger.
                    parent.getCentreY() + 0x24 + (subtype * 0x10),
                    parent.spawn.objectId(), subtype, 0, false, parent.getCentreY()),
                    "CutsceneKnucklesLBZ2Swing");
            this.parent = parent;
            this.subtype = subtype;
            this.index = Math.min(subtype / 2, INITIAL_PARAMS.length - 1);
            this.anchorX = (parent.getCentreX() + 2) & 0xFFFF;
            this.x = anchorX;
            this.y = (parent.getCentreY() + 0x24 + (subtype * 0x10)) & 0xFFFF;
            this.xFixed = x << 16;
            this.yFixed = y << 16;
            this.accel = INITIAL_PARAMS[index][1];
            updateDynamicSpawn(x, y);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            CutsceneKnucklesLbz2Instance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, CutsceneKnucklesLbz2Instance.class);
            return liveParent != null ? new SwingChild(liveParent, ctx.spawn().subtype()) : null;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (freeFalling) {
                updateFreeFall();
            } else if (!swinging) {
                // ROM loc_62A0A: idle until Screen_shake_flag.
                if (parent.isScreenShaking()) {
                    swinging = true;
                    xVel = INITIAL_PARAMS[index][0];
                    crossingsRemaining = 6;
                }
            } else {
                updateSwinging();
            }
            updateDynamicSpawn(x, y);
            // ROM loc_62A0A/loc_62AAE finish through Sprite_CheckDeleteXY.
            if (!isInRangeAt(x)) {
                setDestroyedByOffscreen();
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_KNUX_PILLAR);
            if (renderer != null) {
                renderer.drawFrameIndex(0, x, y, false, false);
            }
        }

        public int getCentreX() {
            return x;
        }

        public int getCentreY() {
            return y;
        }

        public boolean isFreeFallingForTest() {
            return freeFalling;
        }

        public boolean isSwingingForTest() {
            return swinging;
        }

        public int getCrossingsRemainingForTest() {
            return crossingsRemaining;
        }

        public int getXVelForTest() {
            return xVel;
        }

        /** ROM loc_62A28. */
        private void updateSwinging() {
            addLaunchYDelta();
            boolean less = unsigned(x) < unsigned(anchorX);
            xVel += less ? accel : -accel;
            if (less != sideLess) {
                sideLess = less;
                // ROM loc_62A6C: upgrade when $39 == 3 (checked before decrement).
                if (crossingsRemaining == 3) {
                    accel = FAST_PARAMS[index][1];
                    xVel = FAST_PARAMS[index][0];
                }
                crossingsRemaining--;
                if (crossingsRemaining == 0) {
                    // ROM: switch to loc_62AAE next frame + parent $38 bit 3,
                    // then still falls through to MoveSprite2 this frame.
                    freeFalling = true;
                    parent.requestFlingFromSwing();
                }
            }
            moveSprite2();
            if (subtype == 0) {
                parent.copyLeaderX(x);
            }
        }

        /** ROM loc_62AAE: launchYDelta + MoveSprite_LightGravity. */
        private void updateFreeFall() {
            addLaunchYDelta();
            xFixed += xVel << 8;
            yFixed += yVel << 8;
            yVel += LIGHT_GRAVITY;
            x = (xFixed >> 16) & 0xFFFF;
            y = (yFixed >> 16) & 0xFFFF;
        }

        private void moveSprite2() {
            xFixed += xVel << 8;
            yFixed += yVel << 8;
            x = (xFixed >> 16) & 0xFFFF;
            y = (yFixed >> 16) & 0xFFFF;
        }

        private void addLaunchYDelta() {
            int delta = parent.launchYDelta();
            y = (y + delta) & 0xFFFF;
            yFixed += delta << 16;
        }
    }
}
