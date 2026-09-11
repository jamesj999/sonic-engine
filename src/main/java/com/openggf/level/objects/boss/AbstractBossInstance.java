package com.openggf.level.objects.boss;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.palette.PaletteWriteSupport;
import com.openggf.level.Palette;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for boss objects with hit handling, palette flashing, and defeat sequences.
 * Supports multi-component bosses with parent-child relationships.
 */
public abstract class AbstractBossInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable {

    // Common boss constants (ROM values)
    /** ROM: s2.asm:63155 - move.w #$B3,objoff_3C(a0) ($B3 = 179 decimal) */
    protected static final int DEFEAT_TIMER_START = 0xB3;
    /** ROM: ObjectMoveAndFall gravity (8.8 fixed-point) */
    protected static final int GRAVITY = 0x38;
    /** Standard Sonic 2 boss hit count */
    protected static final int DEFAULT_HIT_COUNT = 8;
    /** Default invulnerability duration in frames */
    protected static final int DEFAULT_INVULNERABILITY_DURATION = 32;
    /** Boss explosion spawn interval (every 8 frames) */
    protected static final int EXPLOSION_INTERVAL = 8;
    private static final String BOSS_FLASH_PALETTE_OWNER = "boss.flash";
    private static final int BOSS_FLASH_PALETTE_PRIORITY = 200;
    private static final int BOSS_FLASH_COLOR_INDEX = 1;
    private static final int BOSS_FLASH_BLACK_WORD = 0x0000;
    private static final int BOSS_FLASH_WHITE_WORD = 0x0EEE;

    protected final BossStateContext state;
    protected final BossHitHandler hitHandler;
    protected final BossPaletteFlasher paletteFlasher;
    protected final BossDefeatSequencer defeatSequencer;
    protected final List<BossChildComponent> childComponents;
    protected final Map<Integer, Integer> customMemory;
    private ObjectSpawn dynamicSpawn;
    /**
     * Per-runtime-class construction counters used to assign each
     * {@link AbstractBossChild} a stable 0-based ordinal among this boss's
     * SAME-CLASS siblings (see {@link #nextChildOrdinal}). Fresh per boss
     * instance, so both the original live spawn and every rewind
     * reconstruction (which re-runs the boss's fixed child-spawn sequence
     * from a new instance) produce identical ordinals in identical order.
     */
    private final Map<Class<?>, Integer> childSpawnOrdinalCounters = new HashMap<>();

    /**
     * Set when {@link BossHitHandler#triggerDefeat()} flips the boss to defeated and
     * switches its routine to the defeat handler during touch-response processing
     * (which, for the S2 post-physics object ordering, runs <em>before</em> this
     * object's own {@code update()} on the same frame). It defers the first defeat
     * routine dispatch to the following frame.
     *
     * <p>ROM model: ObjAF reads {@code routine(a0)} once at the top of its dispatch
     * (docs/s2disasm/s2.asm:77412-77415). When loc_39CF0 sets {@code routine=$C}
     * mid-frame (docs/s2disasm/s2.asm:78091-78095), routine $C (loc_39B92:
     * {@code subq.w #1,objoff_32; bmi}, docs/s2disasm/s2.asm:77848-77853) does not
     * begin its per-frame countdown until the next frame. Without this deferral the
     * engine would run an extra same-frame countdown decrement, releasing
     * Camera_Max_X_pos (loc_39BA4, docs/s2disasm/s2.asm:77856-77857) one frame early.
     */
    private boolean deferDefeatRoutineDispatch;
    /** A rejected hardware-art preflight must not consume the killing hit. */
    private boolean defeatEntryPending;
    private boolean defeatEntryPrepared;

    public AbstractBossInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.state = new BossStateContext(spawn.x(), spawn.y(), getInitialHitCount());
        this.hitHandler = new BossHitHandler();
        this.paletteFlasher = new BossPaletteFlasher();
        this.defeatSequencer = new BossDefeatSequencer();
        this.childComponents = new ArrayList<>();
        this.customMemory = new HashMap<>();
        this.dynamicSpawn = spawn;
        initializeBossState();
    }

    /**
     * Initialize boss-specific state and spawn child components.
     */
    protected abstract void initializeBossState();

    /**
     * Update boss-specific logic.
     */
    protected abstract void updateBossLogic(int vIntRunCount, PlayableEntity player);

    /**
     * Get initial hit count (typically 8 for Sonic 2 bosses).
     */
    protected abstract int getInitialHitCount();

    /**
     * Called when boss takes a hit.
     */
    protected abstract void onHitTaken(int remainingHits);

    /**
     * Get collision size index for touch response.
     */
    protected abstract int getCollisionSizeIndex();

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (defeatEntryPending && prepareDefeatEntry()) {
            defeatEntryPending = false;
            hitHandler.commitDefeat();
        }
        if (!state.defeated && usesBaseHitHandler()) {
            hitHandler.update();
            // Note: paletteFlasher.update() is now called inside hitHandler.update()
            // to match ROM order: flash first, then decrement timer
        }

        // ROM routine-read-once deferral: if the boss became defeated during this
        // frame's touch-response pass (before this object's own update ran), the
        // newly-selected defeat routine must not be dispatched until the next frame,
        // mirroring ObjAF reading routine(a0) once at the top of its dispatch
        // (docs/s2disasm/s2.asm:77412-77415). Consume the one-frame deferral here so
        // the defeat countdown's first decrement lands one frame later, as in ROM.
        boolean deferThisFrame = deferDefeatRoutineDispatch;
        deferDefeatRoutineDispatch = false;
        if ((!state.defeated || !usesDefeatSequencer()) && !deferThisFrame) {
            updateBossLogic(vIntRunCount, player);
        }

        if (state.defeated && usesDefeatSequencer()) {
            defeatSequencer.update(vIntRunCount);
        }

        state.lastUpdatedVIntRunCount = vIntRunCount;
        updateChildren(vIntRunCount, player);
        updateOwnerManagedChildren(vIntRunCount, player);
        updateDynamicSpawn();
    }

    private void updateChildren(int vIntRunCount, PlayableEntity player) {
        childComponents.removeIf(BossChildComponent::isDestroyed);
        for (BossChildComponent child : List.copyOf(childComponents)) {
            child.update(vIntRunCount, player);
        }
        // A child may destroy itself while the parent is updating it. Prune again
        // before ObjectManager's later same-pass removal makes that child's
        // identity unavailable to a rewind capture.
        childComponents.removeIf(BossChildComponent::isDestroyed);
    }

    /**
     * Updates boss-owned helper children that intentionally live outside
     * {@link #childComponents}' compact structural rewind graph. The default
     * has no such helpers; subclasses retain exact ownership and lifecycle.
     */
    protected void updateOwnerManagedChildren(int vIntRunCount, PlayableEntity player) {
    }

    public int getCollisionFlags() {
        if (state.invulnerable || state.defeated || defeatEntryPending) {
            return 0; // No collision during invulnerability or defeat
        }
        return 0xC0 | (getCollisionSizeIndex() & 0x3F); // Category BOSS (0xC0)
    }

    public int getCollisionProperty() {
        return state.hitCount; // Return hit count for ROM accuracy
    }

    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        hitHandler.processHit(player);
    }

    /**
     * Check if boss is defeated.
     */
    public boolean isDefeated() {
        if (usesDefeatSequencer()) {
            return state.defeated && defeatSequencer.isComplete();
        }
        return state.defeated;
    }

    /**
     * Get/set custom memory fields (objoff_XX pattern from ROM).
     */
    public int getCustomFlag(int offset) {
        return customMemory.getOrDefault(offset, 0);
    }

    public void setCustomFlag(int offset, int value) {
        customMemory.put(offset, value);
    }

    // ========================================================================
    // CONFIGURABLE METHODS - Override in subclasses to customize behavior
    // ========================================================================

    /**
     * Get invulnerability duration in frames.
     * Override for bosses with different durations (e.g., ARZ uses 64, CNZ uses 48).
     */
    protected int getInvulnerabilityDuration() {
        return DEFAULT_INVULNERABILITY_DURATION;
    }

    /**
     * Get palette line index for flash effect.
     * Sonic 2 bosses flash palette line 1 unless their ROM routine says otherwise.
     */
    protected int getPaletteLineForFlash() {
        return 1;
    }

    /**
     * Whether this boss uses the base class hit handler for invulnerability
     * timer management and palette flashing.
     * <p>S3K bosses with custom palette flash (sub_69C5C-style) should override
     * this to return {@code false} and manage invulnerability entirely in
     * {@link #updateBossLogic}. This prevents the base {@code hitHandler} from
     * decrementing the timer before the custom flash reads bit&nbsp;0, which
     * would invert the flash/normal alternation.
     */
    protected boolean usesBaseHitHandler() {
        return true;
    }

    /**
     * SFX played when the boss takes damage.
     */
    protected abstract int getBossHitSfxId();

    /**
     * SFX played by boss defeat explosions.
     */
    protected abstract int getBossExplosionSfxId();

    /**
     * ROM object id used by the transient boss-defeat explosion object.
     */
    protected int getBossExplosionObjectId() {
        return 0;
    }

    // ========================================================================
    // HELPER METHODS - Common functionality for all bosses
    // ========================================================================

    /**
     * Calculate hover offset using sine wave.
     * ROM pattern: Used by EHZ, CPZ, CNZ, ARZ bosses for floating motion.
     * Increments sineCounter by 2 each frame and returns offset >> 6.
     */
    protected int calculateHoverOffset() {
        int sine = TrigLookupTable.sinHex(state.sineCounter & 0xFF);
        state.sineCounter = (state.sineCounter + 2) & 0xFF;
        return sine >> 6;
    }

    /**
     * Spawn defeat explosion with random offset from boss position.
     * ROM: Boss_LoadExplosion (s2.asm lines referenced in each boss)
     * Uses standard random offset calculation: (random >> 2) - 0x20.
     */
    protected void spawnDefeatExplosion() {
        if (services().renderManager() == null || services().objectManager() == null) {
            return;
        }
        int random = services().rng().nextWord();
        int xOffset = ((random & 0xFF) >> 2) - 0x20;
        int yOffset = (((random >> 8) & 0xFF) >> 2) - 0x20;
        BossExplosionObjectInstance explosion = new BossExplosionObjectInstance(
                state.x + xOffset,
                state.y + yOffset,
                getBossExplosionObjectId(),
                getBossExplosionSfxId());
        services().objectManager().addDynamicObject(explosion);
    }

    /**
     * Apply gravity to boss velocity and update position.
     * ROM: ObjectMoveAndFall pattern.
     */
    protected void applyObjectMoveAndFall() {
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.yVel += GRAVITY;
        state.updatePositionFromFixed();
    }

    /**
     * Whether this boss uses the generic defeat sequencer.
     * Subclasses with custom defeat logic should override and return false.
     */
    protected boolean usesDefeatSequencer() {
        return true;
    }

    /**
     * Whether this boss's defeat needs the one-frame "routine-read-once" dispatch
     * deferral (see {@link #deferDefeatRoutineDispatch}).
     *
     * <p>Models <em>which ROM defeat-dispatch mechanism</em> the boss uses, exposed at
     * the owning boss class. The discriminator is <strong>read-once-at-head versus
     * re-read-per-dispatch</strong>, not primary {@code routine} versus
     * {@code routine_secondary}. Nearly every S2 boss head is the same idiom -
     * {@code moveq #0,d0 / move.b <field>(a0),d0 / move.w Index(pc,d0.w),d1 / jmp} - and
     * it loads the selector <em>once</em> per object update, whichever field it names:
     *
     * <ul>
     *   <li>Obj56 / EHZ, on {@code routine_secondary}: {@code loc_2F262},
     *       docs/s2disasm/s2.asm:63420-63424</li>
     *   <li>ObjC5 / WFZ, on {@code routine_secondary}: {@code ObjC5_LaserCase},
     *       docs/s2disasm/s2.asm:81246-81251</li>
     *   <li>ObjAF / DEZ Mecha Sonic, on {@code routine}: docs/s2disasm/s2.asm:77502-77506</li>
     *   <li>Obj5D / CPZ, on {@code routine}: docs/s2disasm/s2.asm:61470-61474</li>
     * </ul>
     *
     * <p>The deferral is correct whenever the defeat write lands <em>downstream</em> of
     * that head read, so the newly selected handler cannot run until the next frame.
     * Because the engine runs touch responses before this object's own {@code update()},
     * the deferral restores exactly that one-frame offset. Worked cases: ObjAF's
     * {@code loc_39CF0} sets {@code routine=$C} (docs/s2disasm/s2.asm:78091-78095);
     * Obj5D observes the defeated status at its own tail (docs/s2disasm/s2.asm:61723-61772);
     * Obj56's {@code loc_2F4EE} sets {@code routine_secondary=6}
     * (docs/s2disasm/s2.asm:63665) from {@code loc_2F4A6} (:63632-63636), called by
     * {@code loc_2F304} / Sub4 at :63486.
     *
     * <p><strong>Wing Fortress (ObjC5) - settled, was an open question.</strong> An earlier
     * wording of this javadoc justified WFZ's {@code false} by claiming
     * {@code ObjC5_LaserCase} re-reads {@code routine_secondary} per dispatch, citing two
     * addresses that had both drifted onto unrelated code. Re-read against the criterion
     * above, ObjC5 satisfies it: the selector is read <em>once</em> at
     * {@code ObjC5_LaserCase} (docs/s2disasm/s2.asm:81246-81251) and the defeat write
     * {@code move.b #$1E,routine_secondary(a0)} sits downstream of that head read, in
     * {@code ObjC5_NoHitPointsLeft} (docs/s2disasm/s2.asm:82045-82053), reached via
     * {@code bra.w ObjC5_HandleHits}. WFZ's {@code false} was a latent defect.
     *
     * <p>It is not fixed by flipping this flag, though, and WFZ deliberately does not use
     * this mechanism. This deferral <em>skips</em> the dispatch, whereas ObjC5 runs the
     * previously selected case on the killing frame and installs afterwards. WFZ models that
     * directly instead, by latching the install to the tail of its own dispatch - see
     * {@code Sonic2WFZBossInstance.onDefeatStarted()}. Prefer that shape for any boss whose
     * ROM detection sits at the tail of its own routine rather than in {@code Touch_Response}.
     *
     * <p><strong>Not settled by this criterion.</strong> Obj89 / ARZ
     * (docs/s2disasm/s2.asm:64759-64763) and Obj54 / MTZ
     * (docs/s2disasm/s2.asm:67210-67214) dispatch on {@code boss_subtype}, which is a
     * piece selector rather than a defeat state machine. Their values are open; do not
     * change them on the strength of this note alone.
     *
     * <p>Defaults to {@code false}.
     */
    protected boolean defeatDeferralAppliesToThisBoss() {
        return false;
    }

    /**
     * Inner class: Handles hit detection and invulnerability.
     * ROM Reference: s2.asm:60734-60758 (Boss_HandleHits routine)
     */
    protected class BossHitHandler {
        public void update() {
            if (state.invulnerable) {
                // ROM: s2.asm:60748-60754 - Toggle palette FIRST
                paletteFlasher.update();

                // ROM: s2.asm:60755 - subq.b #1,boss_invulnerable_time(a0)
                state.invulnerabilityTimer--;

                // ROM: s2.asm:60756-60757 - Check if done
                if (state.invulnerabilityTimer <= 0) {
                    state.invulnerable = false;
                    // ROM: s2.asm:60758 - move.b #$F,collision_flags(a0)
                    paletteFlasher.stopFlash();
                }
            }
        }

        public void processHit(PlayableEntity player) {
            // ROM: s2.asm:63124 - tst.b collision_flags(a0)
            if (state.invulnerable || state.defeated) {
                return;
            }

            // ROM: collision_property(a0) is hitcount, decremented by Touch_Enemy_Part2
            state.hitCount--;
            // Use configurable invulnerability duration
            state.invulnerabilityTimer = getInvulnerabilityDuration();
            state.invulnerable = true;

            // ROM: s2.asm:63129 - move.w #SndID_BossHit,d0
            services().playSfx(getBossHitSfxId());
            paletteFlasher.startFlash();
            onHitTaken(state.hitCount);

            if (state.hitCount == 0) {
                triggerDefeat();
            }
        }

        private void triggerDefeat() {
            if (!prepareDefeatEntry()) {
                defeatEntryPending = true;
                return;
            }
            commitDefeat();
        }

        private void commitDefeat() {
            defeatEntryPrepared = true;
            // ROM: s2.asm:63149 - loc_2F4EE (boss defeated)
            state.defeated = true;
            if (usesDefeatSequencer()) {
                defeatSequencer.startDefeat();
            } else {
                services().gameState().addScore(1000);
                onDefeatStarted();
                // onDefeatStarted() switched state.routine to the defeat handler.
                // For the S2 post-physics ordering this triggerDefeat() runs in the
                // touch-response pass BEFORE this object's own update() this frame, so
                // without deferral updateBossLogic() would dispatch the defeat routine
                // (and decrement its countdown) on the same frame the routine changed.
                // ROM reads routine(a0) once per object update, or selects a defeated
                // routine_secondary at the tail of the object's own update and returns,
                // so the defeat routine first runs next frame (docs/s2disasm/s2.asm:
                // 77412-77415, 78091-78095, 77848-77853; Obj5D tail:
                // 61723-61772). Defer the first defeat dispatch by one frame only for
                // bosses whose ROM dispatch shape carries that offset. Bosses that
                // select defeat via a selector genuinely re-read per dispatch do not
                // carry this offset and must not be deferred. ObjC5 / WFZ was cited here
                // as such a boss; it is not - it carries the offset and models it with a
                // tail latch of its own instead. See defeatDeferralAppliesToThisBoss().
                if (defeatDeferralAppliesToThisBoss()) {
                    deferDefeatRoutineDispatch = true;
                }
            }
        }
    }

    /**
     * Inner class: Handles palette flashing during invulnerability.
     * ROM Reference: s2.asm:60748-60754 (Boss_HandleHits palette flash)
     */
    protected class BossPaletteFlasher {
        private boolean flashing;
        private int flashFrame;
        private int originalColorWord;
        private boolean colorStored;
        private boolean useWhite; // Internal toggle - immune to external palette modifications

        public void startFlash() {
            flashing = true;
            flashFrame = 0;
            colorStored = false;
            useWhite = false; // Start with black on first frame
        }

        public void stopFlash() {
            if (flashing && colorStored) {
                applyFlashColor(originalColorWord);
            }
            flashing = false;
            flashFrame = 0;
            colorStored = false;
        }

        public void update() {
            if (!flashing) {
                return;
            }

            Palette palette = getPaletteForFlash();
            if (palette == null) {
                return;
            }

            // Store original color on first flash (make a copy to avoid reference issues)
            if (!colorStored) {
                originalColorWord = PaletteWriteSupport.segaWordFromColor(palette.getColor(BOSS_FLASH_COLOR_INDEX));
                colorStored = true;
            }

            // ROM: s2.asm:60749-60754 - Toggle between black (0x0000) and white (0x0EEE)
            // Use internal toggle state to ensure reliable alternation even if
            // palette cyclers or other systems modify the palette between frames
            int newColor = useWhite ? BOSS_FLASH_WHITE_WORD : BOSS_FLASH_BLACK_WORD;
            applyFlashColor(newColor);
            useWhite = !useWhite; // Toggle for next frame

            flashFrame++;
        }

        private void applyFlashColor(int segaWord) {
            PaletteWriteSupport.applyColor(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    BOSS_FLASH_PALETTE_OWNER,
                    BOSS_FLASH_PALETTE_PRIORITY,
                    getPaletteLineForFlash(),
                    BOSS_FLASH_COLOR_INDEX,
                    segaWord);
        }

        private Palette getPaletteForFlash() {
            if (services().currentLevel() == null) {
                return null;
            }
            int paletteIndex = getPaletteLineForFlash();
            if (paletteIndex < 0) {
                return null;
            }
            int paletteCount = services().currentLevel().getPaletteCount();
            if (paletteCount <= paletteIndex) {
                return null;
            }
            return services().currentLevel().getPalette(paletteIndex);
        }
    }

    /**
     * Inner class: Handles defeat sequence (explosions, flee, EggPrison spawn).
     * ROM Reference: s2.asm:62989-63008 (loc_2F336 - SUB6 defeat routine)
     */
    protected class BossDefeatSequencer {
        // ROM: s2.asm:63155 - move.w #$B3,objoff_3C(a0) ($B3 = 179 decimal)
        private static final int EXPLOSION_DURATION = 179; // Frames

        private DefeatState defeatState;
        private int defeatTimer;
        private int fleeTimer;

        private enum DefeatState {
            EXPLODING,
            FLEEING,
            SPAWN_PRISON,
            COMPLETE
        }

        public BossDefeatSequencer() {
            this.defeatState = DefeatState.COMPLETE;
            this.defeatTimer = 0;
            this.fleeTimer = 0;
        }

        public void startDefeat() {
            defeatState = DefeatState.EXPLODING;
            defeatTimer = EXPLOSION_DURATION;
            // ROM: s2.asm:63150-63151 - moveq #100,d0 / jsrto JmpTo3_AddPoints (100 = 1000 points)
            services().gameState().addScore(1000);
            onDefeatStarted();
        }

        public void update(int vIntRunCount) {
            switch (defeatState) {
                case EXPLODING -> updateExploding(vIntRunCount);
                case FLEEING -> updateFleeing(vIntRunCount);
                case SPAWN_PRISON -> spawnEggPrison();
            }
        }

        private void updateExploding(int vIntRunCount) {
            // ROM: s2.asm:62990 - subq.w #1,objoff_3C(a0)
            defeatTimer--;

            // ROM: s2.asm:62992 - bsr.w Boss_LoadExplosion (spawns every 8 frames)
            // Spawn explosion every 8 frames
            if (defeatTimer % EXPLOSION_INTERVAL == 0) {
                spawnExplosion();
            }

            // ROM: s2.asm:62991 - bmi.s loc_2F35C (timer finished)
            if (defeatTimer <= 0) {
                defeatState = DefeatState.FLEEING;
                fleeTimer = 0;
                onFleeStarted();
            }
        }

        private void updateFleeing(int vIntRunCount) {
            fleeTimer++;
            updateFleeingMovement();

            // Check if boss is off-screen
            if (isOffScreen()) {
                defeatState = DefeatState.SPAWN_PRISON;
            }
        }

        private void spawnEggPrison() {
            onEggPrisonSpawn();
            defeatState = DefeatState.COMPLETE;
        }

        public boolean isComplete() {
            return defeatState == DefeatState.COMPLETE;
        }

        private void spawnExplosion() {
            // Subclasses can override to spawn explosion effect
        }

        private boolean isOffScreen() {
            // Subclasses can override
            return fleeTimer > 200;
        }
    }

    // Cached debug labels to avoid per-frame String allocation
    private String cachedHpLabel;
    private int cachedDebugHitCount = -1;
    private int cachedDebugRoutine = -1;
    private String cachedInvulnLabel;
    private int cachedInvulnTimer = -1;

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Hitbox rectangle colored by state
        float r, g, b;
        if (state.defeated) {
            r = 0.5f; g = 0.5f; b = 0.5f; // Gray = defeated
        } else if (state.invulnerable) {
            r = 1f; g = 0f; b = 0f; // Red = invulnerable
        } else {
            r = 0f; g = 1f; b = 0f; // Green = hittable
        }
        ctx.drawRect(state.x, state.y, 24, 24, r, g, b);

        // Red text: name + HP + routine (cached until state changes)
        if (state.hitCount != cachedDebugHitCount || state.routine != cachedDebugRoutine) {
            cachedHpLabel = name + " HP:" + state.hitCount + " R:" + state.routine;
            cachedDebugHitCount = state.hitCount;
            cachedDebugRoutine = state.routine;
        }
        ctx.drawWorldLabel(state.x, state.y, -3, cachedHpLabel, DebugColor.RED);

        // Orange invulnerability timer when invulnerable (cached until timer changes)
        if (state.invulnerable) {
            if (state.invulnerabilityTimer != cachedInvulnTimer) {
                cachedInvulnLabel = "Invuln:" + state.invulnerabilityTimer;
                cachedInvulnTimer = state.invulnerabilityTimer;
            }
            ctx.drawWorldLabel(state.x, state.y, -2, cachedInvulnLabel, DebugColor.ORANGE);
        }

        // Blue lines from parent to each child component
        for (BossChildComponent child : childComponents) {
            if (!child.isDestroyed() && child instanceof AbstractBossChild bossChild) {
                ctx.drawLine(state.x, state.y, bossChild.getX(), bossChild.getY(), 0.3f, 0.3f, 1f);
            }
        }
    }

    /**
     * Called when defeat sequence starts. Override to customize behavior.
     */
    protected void onDefeatStarted() {
        // Default: no additional behavior
    }

    /**
     * Publishes defeat-entry hardware work before defeat state becomes
     * irreversible. Subclasses with a capsule PLC return false on rejected
     * eager/logical preflight; the base update then retries without replaying
     * the killing hit or any defeat side effects.
     */
    protected boolean prepareDefeatEntry() {
        return true;
    }

    protected boolean isDefeatEntryPrepared() {
        return defeatEntryPrepared;
    }

    /**
     * Stops the level timer as part of a boss's defeat branch.
     *
     * <p>ROM: {@code BossDefeated_StopTimer} (docs/skdisasm/sonic3k.asm:180890) —
     * {@code clr.b (Update_HUD_timer).w}, entered by every S3K boss whose
     * hit counter reaches zero, immediately before it falls through into
     * {@code BossDefeated}. Beyond freezing the HUD clock this is what ends a
     * Super/Hyper transformation: {@code SonicKnux_SuperHyper}
     * (docs/skdisasm/sonic3k.asm:23609-23611) tests {@code Update_HUD_timer}
     * first and branches straight to {@code .revertToNormal} when it is clear,
     * without waiting for the ring drain.
     *
     * <p>Call this from the boss's own defeat entry, on the frame the ROM
     * routine takes that branch. Bosses whose ROM path goes through plain
     * {@code BossDefeated} instead (e.g. the LBZ miniboss' Knuckles route at
     * sonic3k.asm:151896) must not call it.
     */
    protected final void stopLevelTimerOnBossDefeat() {
        var levelState = services().levelGamestate();
        if (levelState != null) {
            levelState.pauseTimer();
        }
    }

    /**
     * Called when fleeing phase starts. Override to customize movement.
     */
    protected void onFleeStarted() {
        // Default: no additional behavior
    }

    /**
     * Update boss movement during fleeing phase. Override to customize.
     */
    protected void updateFleeingMovement() {
        // Default: move upward
        state.y--;
    }

    /**
     * Called when EggPrison should spawn. Override to customize.
     */
    protected void onEggPrisonSpawn() {
        // Default: no additional behavior
        // Subclasses should unlock camera and spawn EggPrison
    }

    @Override
    public int getX() {
        return state.x;
    }

    @Override
    public int getY() {
        return state.y;
    }

    public BossStateContext getState() {
        return state;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn;
    }

    public List<BossChildComponent> getChildComponents() {
        return childComponents;
    }

    /**
     * Returns the next 0-based construction ordinal for {@code childClass}, i.e. how
     * many children of that EXACT runtime class this boss has already spawned.
     * Called by {@link AbstractBossChild}'s constructor so indistinguishable
     * same-class siblings (e.g. EHZ's 3 {@code EHZBossWheel} instances) can carry a
     * stable per-position identity through {@link ObjectSpawn#subtype()} -- otherwise
     * unused by boss children -- for {@code ObjectManager.adoptRewindReconstructionChild}
     * to match against after a rewind restore, instead of falling back to a plain
     * FIFO-by-class-name match that silently shifts captured state onto the wrong
     * sibling once an earlier one has been destroyed and pruned before capture.
     */
    final int nextChildOrdinal(Class<?> childClass) {
        int ordinal = childSpawnOrdinalCounters.getOrDefault(childClass, 0);
        childSpawnOrdinalCounters.put(childClass, ordinal + 1);
        return ordinal;
    }

    /**
     * Re-derives {@link #childSpawnOrdinalCounters} from the settled, post-restore
     * {@link #childComponents} rather than trusting whatever value reconstruction
     * happened to leave it at.
     *
     * <p>{@code childSpawnOrdinalCounters} is intentionally not captured by rewind
     * (see the {@code DEFERRED} policy entry in {@code DefaultObjectRewindPolicies})
     * because it is fully derivable from live children -- but it is NOT safe to just
     * leave it at whatever count reconstruction's fixed always-re-spawn-N-children
     * sequence produced. A boss that spawns additional same-class
     * {@link AbstractBossChild} instances mid-fight (beyond its construction-time
     * set) can have live children whose captured {@link AbstractBossChild#getChildOrdinal()}
     * exceeds the reconstruction-time spawn count for that class (e.g. one survivor
     * was adopted onto a reconstruction-pending slot whose OWN construction ordinal
     * was lower than the survivor's true captured ordinal). Leaving the counter at
     * the raw reconstruction count would let the NEXT post-restore spawn of that
     * class collide with that survivor's ordinal, reopening the exact-match adoption
     * ambiguity this framework exists to prevent. Recomputing as
     * {@code max(live ordinal for that class) + 1} after every child's own restore
     * has settled keeps the counter consistent with reality regardless of how it got
     * there.
     */
    @Override
    protected void afterRewindRestoreSettled() {
        childSpawnOrdinalCounters.clear();
        for (BossChildComponent child : childComponents) {
            if (child instanceof AbstractBossChild bossChild) {
                childSpawnOrdinalCounters.merge(
                        bossChild.getClass(), bossChild.getChildOrdinal() + 1, Math::max);
            }
        }
    }

    private void updateDynamicSpawn() {
        if (dynamicSpawn.x() == state.x && dynamicSpawn.y() == state.y) {
            return;
        }
        dynamicSpawn = new ObjectSpawn(
                state.x,
                state.y,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
    }
}
