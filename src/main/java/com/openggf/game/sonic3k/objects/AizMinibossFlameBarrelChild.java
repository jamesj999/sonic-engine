package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * AIZ miniboss flame barrel child.
 *
 * ROM:
 * - Cutscene barrel: loc_6872C (ChildObjDat_69072)
 * - Miniboss barrel: loc_68C12 (ChildObjDat_69086)
 */
public class AizMinibossFlameBarrelChild extends AbstractBossChild implements RewindRecreatable {
    private static final int FLAG_PARENT_BITS = 0x38;
    private static final int PARENT_BIT_BARREL_ACTIVATE = 1 << 1;

    private static final int[] START_DELAYS = {0, 0x10, 0x20}; // word_68ECE
    private static final int[][] BARREL_OFFSETS = {
            {0, -0x20}, {9, -0x1C}, {0x12, -0x18}
    };

    // ROM byte_6912F (Animate_RawMultiDelay, first pair skipped on initial play):
    // Open: frame 4 (timer 5 = 6t), frame 5 (timer $17 = 24t), $F4
    private static final int[] OPEN_FRAMES = {4, 5};
    private static final int[] OPEN_DURATIONS = {6, 24};

    // ROM byte_69136 (Animate_RawMultiDelay, first pair skipped on initial play):
    // Close: frame 5 (timer $17 = 24t), frame 4 (timer 5 = 6t), frame 3 (timer 5 = 6t), $F4
    private static final int[] CLOSE_FRAMES = {5, 4, 3};
    private static final int[] CLOSE_DURATIONS = {24, 6, 6};

    private enum State {
        INIT,
        WAIT_ACTIVATE,
        START_DELAY,
        OPENING,
        FIRING_CUTSCENE,
        FIRING_MINIBOSS,
        BETWEEN_SHOTS,
        CLOSING,
        IDLE
    }

    // Non-final so the generic rewind field capturer reapplies them after the
    // recreate hook passes placeholder barrelIndex/false.
    private int barrelIndex;
    private boolean cutsceneVariant;

    private State state = State.INIT;
    private int timer;
    private int mappingFrame = 3;
    private int cutsceneCounter;
    private int animIndex;
    /** ROM: $39(a0) on the barrel — per-barrel counter for drop position cycling.
     *  Incremented by 4 each time a shot enters the top-drop phase (sub_68EE4). */
    private int positionCounter;

    public AizMinibossFlameBarrelChild(AbstractBossInstance parent, int barrelIndex, boolean cutsceneVariant) {
        super(parent, "AIZMinibossBarrel" + barrelIndex, 4, 0x90);
        this.barrelIndex = Math.max(0, Math.min(2, barrelIndex));
        this.cutsceneVariant = cutsceneVariant;
        syncPositionWithParent();
        updateDynamicSpawn();
    }

    private AizMinibossFlameBarrelChild(ObjectSpawn spawn, AbstractBossInstance parent) {
        this(parent, 0, false);
    }

    @Override
    public AizMinibossFlameBarrelChild recreateForRewind(RewindRecreateContext ctx) {
        AbstractBossInstance boss = AizMinibossRewindLinks.nearestSharedBoss(ctx);
        if (boss == null) {
            return null;
        }
        int restoredIndex = AizMinibossRewindLinks.nearestBarrelIndex(ctx, boss);
        return new AizMinibossFlameBarrelChild(boss, restoredIndex, false);
    }

    @Override
    public void syncPositionWithParent() {
        if (parent == null || parent.isDestroyed()) {
            return;
        }
        int xOffset = BARREL_OFFSETS[barrelIndex][0];
        int yOffset = BARREL_OFFSETS[barrelIndex][1];
        boolean hFlip = (parent.getState().renderFlags & 1) != 0;
        if (hFlip) {
            xOffset = -xOffset;
        }
        this.currentX = parent.getX() + xOffset;
        this.currentY = parent.getY() + yOffset;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!shouldUpdate(vIntRunCount)) {
            return;
        }

        syncPositionWithParent();

        if (parent == null || parent.getState().defeated) {
            state = State.IDLE;
            mappingFrame = 3;
            updateDynamicSpawn();
            return;
        }

        switch (state) {
            case INIT -> {
                state = State.WAIT_ACTIVATE;
                mappingFrame = 3;
            }
            case WAIT_ACTIVATE -> {
                mappingFrame = 3;
                if (!isActivatedByParent()) {
                    break;
                }
                timer = START_DELAYS[barrelIndex];
                state = State.START_DELAY;
            }
            case START_DELAY -> {
                if (--timer >= 0) {
                    break;
                }
                // ROM byte_6912F: begin open animation (first pair skipped)
                animIndex = 0;
                mappingFrame = OPEN_FRAMES[0];
                timer = OPEN_DURATIONS[0];
                state = State.OPENING;
            }
            case OPENING -> {
                if (--timer > 0) {
                    break;
                }
                animIndex++;
                if (animIndex < OPEN_FRAMES.length) {
                    mappingFrame = OPEN_FRAMES[animIndex];
                    timer = OPEN_DURATIONS[animIndex];
                    break;
                }
                // Open animation complete — start firing
                mappingFrame = 5;
                if (cutsceneVariant) {
                    cutsceneCounter = 3;
                    state = State.FIRING_CUTSCENE;
                } else {
                    state = State.FIRING_MINIBOSS;
                }
            }
            case FIRING_CUTSCENE -> {
                fireCutsceneShot();
                timer = 0x1C;
                state = State.BETWEEN_SHOTS;
            }
            case FIRING_MINIBOSS -> {
                // ROM AIZMiniboss_BarrelController_FireFinal (sonic3k.asm:137437-137445):
                // each existing barrel allocates the flare/FallingShot pair from its
                // own child slot, then begins the return animation.  The barrel's
                // subtype, $39 position counter, and parent-facing bit remain the
                // FallingShot's source state; no center-owned controller exists in
                // the shipped route.
                spawnFallingShot();
                enterClosingAnimation();
            }
            case BETWEEN_SHOTS -> {
                // ROM loc_687DC: checks tst.b $39 EVERY frame during wait,
                // exits immediately once counter goes negative after 4th shot
                if (cutsceneCounter < 0) {
                    enterClosingAnimation();
                    break;
                }
                if (--timer >= 0) {
                    break;
                }
                state = State.FIRING_CUTSCENE;
            }
            case CLOSING -> {
                if (--timer > 0) {
                    break;
                }
                animIndex++;
                if (animIndex < CLOSE_FRAMES.length) {
                    mappingFrame = CLOSE_FRAMES[animIndex];
                    timer = CLOSE_DURATIONS[animIndex];
                    break;
                }
                // Close animation complete
                mappingFrame = 3;
                if (cutsceneVariant) {
                    state = State.IDLE;
                } else {
                    clearParentActivationBit();
                    state = State.WAIT_ACTIVATE;
                }
            }
            case IDLE -> mappingFrame = 3;
        }

        updateDynamicSpawn();
    }

    private void fireCutsceneShot() {
        // loc_687B6: counter decremented before child selection.
        cutsceneCounter--;
        if (cutsceneCounter == 1) {
            // loc_68C96 path spawned from ChildObjDat_690A8, but cutscene parent
            // clears collision_flags in loc_68CD0.
            spawnShot(AizMinibossBarrelShotChild.Mode.ADVANCED_NON_COLLIDING);
        } else {
            // loc_68844 path from ChildObjDat_6909A.
            spawnShot(AizMinibossBarrelShotChild.Mode.SIMPLE);
        }
    }

    private void spawnShot(AizMinibossBarrelShotChild.Mode mode) {
        if (services().objectManager() == null) {
            return;
        }
        // ROM ChildObjDat_6909A / ChildObjDat_690A8 spawn a short muzzle-flare child
        // alongside the main shot object.
        spawnChild(() -> new AizMinibossBarrelShotFlareChild(this));
        spawnChild(() -> new AizMinibossBarrelShotChild(parent, this, currentX, currentY + 4, mode));
    }

    private void spawnFallingShot() {
        if (services().objectManager() == null) {
            return;
        }
        // ChildObjDat_AIZMiniboss_BarrelShotAndFallingShot: the flare is child
        // subtype 0 and FallingShot is child subtype $02.  The latter subtype is
        // deliberately not the barrel's subtype (0/2/4), which SetFallingShotDelay
        // reads from parent3(a0) in the ROM.
        spawnChild(() -> new AizMinibossBarrelShotFlareChild(this));
        spawnChild(() -> new AizMinibossNapalmProjectile(
                parent, this, currentX, currentY + 4, 2));
    }

    private void enterClosingAnimation() {
        // ROM byte_69136: close animation (first pair skipped on initial play)
        animIndex = 0;
        mappingFrame = CLOSE_FRAMES[0];
        timer = CLOSE_DURATIONS[0];
        state = State.CLOSING;
    }

    /** ROM: subtype(a1) — barrel subtype used by shots for position selection. */
    int getBarrelSubtype() {
        return barrelIndex * 2;
    }

    /** ROM render_flags(a1) read by AIZMiniboss_SetShotPosition. */
    boolean isFacingFlipped() {
        return parent != null && (parent.getState().renderFlags & 1) != 0;
    }

    /** ROM: $39(a1) — per-barrel position counter read by shots. */
    int getPositionCounter() {
        return positionCounter;
    }

    /** ROM: addq.b #4,d1 / move.b d1,$39(a1) — shots update the barrel's counter. */
    void setPositionCounter(int value) {
        this.positionCounter = value & 0xFF;
    }

    private boolean isActivatedByParent() {
        return (parent.getCustomFlag(FLAG_PARENT_BITS) & PARENT_BIT_BARREL_ACTIVATE) != 0;
    }

    private void clearParentActivationBit() {
        int flags = parent.getCustomFlag(FLAG_PARENT_BITS);
        parent.setCustomFlag(FLAG_PARENT_BITS, flags & ~PARENT_BIT_BARREL_ACTIVATE);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_AIZMiniboss,1,1) — priority bit = 1
        return true;
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
        boolean hFlip = (parent != null) && ((parent.getState().renderFlags & 1) != 0);
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, hFlip, false);
    }
}
