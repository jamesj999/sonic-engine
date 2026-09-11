package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import java.util.List;

/**
 * Object 0x2E - AIZ Spiked Log (Sonic 3 &amp; Knuckles).
 * <p>
 * A solid platform that bobs vertically using sine wave oscillation. The log reacts
 * to water level changes (via {@code Water_entered_counter}) by playing a fast spinning
 * animation and increasing its swing amplitude. Standing on the log also triggers the
 * same swing-and-spin behavior.
 * <p>
 * A child collision object provides spike damage that tracks the log's rotation,
 * with the spike hitbox Y-offset changing per mapping frame.
 * <p>
 * ROM references: Obj_AIZSpikedLog (sonic3k.asm:60038-60196).
 */
public class AizSpikedLogObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_AIZSpikedLog} is installed from the S3K object pointer table at
     * {@code $0002B758} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:60043).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    // Collision params: d1 = width_pixels(0x18) + 0x0B = 0x23, d2 = 0x08, d3 = 0x09
    private static final int SOLID_HALF_WIDTH = 35;
    private static final int SOLID_AIR_HALF_HEIGHT = 8;
    private static final int SOLID_GROUND_HALF_HEIGHT = 9;

    // Priority $200 → bucket 4
    private static final int PRIORITY = 4;

    // Swing physics (sonic3k.asm:60087-60102)
    private static final int SWING_ANGLE_MAX = 0x40;
    private static final int SWING_ANGLE_STEP = 4;
    private static final int SWING_AMPLITUDE_SHIFT = 5; // sin(angle) >> 5

    // Animation timing
    private static final int WATER_ANIM_DELAY = 3;     // 4 frames per step (sonic3k.asm:60107)
    private static final int IDLE_ANIM_DELAY = 0x17;   // 24 frames per step (sonic3k.asm:60121)
    private static final int WATER_ANIM_LENGTH = 0x10;  // 16 frames full cycle (sonic3k.asm:60111)

    // Idle animation table: mapping frames 7→8→9→10→9→8, -1=loop (byte_2B88E)
    private static final int[] IDLE_ANIM_TABLE = { 7, 8, 9, 0x0A, 9, 8, -1 };

    // Child collision Y-offset per mapping frame (byte_2B918)
    // Non-zero values indicate the spike is in a dangerous position
    private static final int[] SPIKE_Y_OFFSETS = {
        -12, -12, 0, 0, 0, 0, 0, 12, 12, 12, 0, 0, 0, 0, 0, -12
    };

    private int baseY;              // $30: initial Y position (swing center)
    private int swingAngle;               // $32: current swing angle (0 to 0x40)
    private int swingState;               // $34: state flag (signed byte semantics)
    private int savedAnimFrame;           // $35: saved idle animation frame index
    private int waterCounterSnapshot;     // $36: snapshot of Water_entered_counter
    private int mappingFrame;             // current sprite mapping frame (0-15)
    private int animFrame;                // animation sequence index
    private int animFrameTimer;           // countdown timer between frame changes
    private int currentY;                 // current Y position (after swing calc)
    private boolean initialized;
    private SpikedLogCollisionChild spikeChild;

    public AizSpikedLogObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZSpikedLog");
        this.baseY = spawn.y();
        this.currentY = spawn.y();
        // ROM zero-initializes all fields; first idle animation tick sets mapping_frame
    }

    @Override
    public AizSpikedLogObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizSpikedLogObjectInstance(ctx.spawn());
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        // Snapshot water counter (sonic3k.asm:60046)
        WaterSystem water = services().waterSystem();
        this.waterCounterSnapshot = (water != null) ? water.getWaterEnteredCounter() : 0;

        // Spawn collision child (sonic3k.asm:60047-60053)
        spikeChild = spawnChild(() -> new SpikedLogCollisionChild(
                buildSpawnAt(spawn.x(), spawn.y()), this));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        ensureInitialized();
        updateSwingState();
        updateYPosition();
        updateAnimation();
    }

    /**
     * Updates the swing state machine: water detection, standing detection, angle decay.
     * sonic3k.asm:60058-60095
     */
    private void updateSwingState() {
        // $34 negative → angle is ramping up from water trigger
        if (swingState < 0) {
            increaseSwingAngle();
            return;
        }

        // Check for water state change (sonic3k.asm:60061-60066)
        WaterSystem water = services().waterSystem();
        int currentWaterCounter = (water != null) ? water.getWaterEnteredCounter() : 0;
        if (currentWaterCounter != waterCounterSnapshot) {
            waterCounterSnapshot = currentWaterCounter;
            // Set $34 = -$7F (0x81 signed = -127)
            swingState = -0x7F;
            increaseSwingAngle();
            return;
        }

        // No water change → check if player is standing (sonic3k.asm:60069-60076)
        if (isPlayerRiding()) {
            initStandingSwingIfNeeded();
            increaseSwingAngle();
            return;
        }

        // Nobody standing, no water change → decay swing angle toward 0
        if (swingAngle > 0) {
            swingAngle -= SWING_ANGLE_STEP;
            if (swingAngle < 0) swingAngle = 0;
        }
    }

    /**
     * First frame of standing trigger: saves animation state, starts spinning.
     * Only executes when swingState == 0 (sonic3k.asm:60079-60085).
     */
    private void initStandingSwingIfNeeded() {
        if (swingState != 0) return;
        // Save current idle anim frame, reset for spinning
        savedAnimFrame = animFrame;
        animFrame = 0;
        animFrameTimer = 0;
        swingState = 1;
    }

    /**
     * Increases swing angle toward max. Clears bit 7 of swingState when max reached.
     * sonic3k.asm:60087-60095
     */
    private void increaseSwingAngle() {
        if (swingAngle < SWING_ANGLE_MAX) {
            swingAngle += SWING_ANGLE_STEP;
            if (swingAngle > SWING_ANGLE_MAX) {
                swingAngle = SWING_ANGLE_MAX;
            }
        } else {
            // Angle at max → andi.b #$7F,$34(a0) (sonic3k.asm:60094-60095)
            swingState &= 0x7F;
        }
    }

    /**
     * Calculates Y position from sine wave oscillation.
     * Y = sin(swingAngle) >> 5 + baseY (sonic3k.asm:60097-60102)
     */
    private void updateYPosition() {
        int sinValue = TrigLookupTable.sinHex(swingAngle);
        int yOffset = sinValue >> SWING_AMPLITUDE_SHIFT;
        currentY = baseY + yOffset;
    }

    /**
     * Updates animation state based on swingState.
     * swingState != 0 → fast spinning; swingState == 0 → slow idle rocking.
     * sonic3k.asm:60103-60131
     */
    private void updateAnimation() {
        if (swingState != 0) {
            updateSpinningAnimation();
        } else {
            updateIdleAnimation();
        }
    }

    /**
     * Fast spinning animation triggered by water change or standing.
     * Decrements mapping_frame through 0-15 range, 4 frames per step.
     * sonic3k.asm:60103-60115
     */
    private void updateSpinningAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) return;
        animFrameTimer = WATER_ANIM_DELAY;

        // subq.b #1,mapping_frame / andi.b #$F
        mappingFrame = (mappingFrame - 1) & 0x0F;
        animFrame++;

        if (animFrame >= WATER_ANIM_LENGTH) {
            // Animation complete → restore saved state (sonic3k.asm:60113-60114)
            animFrame = savedAnimFrame;
            swingState = 0;
        }
    }

    /**
     * Slow idle rocking animation cycling frames 7→8→9→10→9→8.
     * 24 frames per step. Uses byte_2B88E lookup table.
     * sonic3k.asm:60118-60131
     */
    private void updateIdleAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) return;
        animFrameTimer = IDLE_ANIM_DELAY;

        int index = animFrame;
        mappingFrame = IDLE_ANIM_TABLE[index];

        // Check if next table entry is the loop marker (sonic3k.asm:60126-60128)
        if (index + 1 >= IDLE_ANIM_TABLE.length || IDLE_ANIM_TABLE[index + 1] < 0) {
            animFrame = 0;
        } else {
            animFrame = index + 1;
        }
    }

    // ===== Rendering =====

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(spawn.x(), currentY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.AIZ_SPIKED_LOG);
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 0x18;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 8;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Solid collision box (green)
        ctx.drawRect(spawn.x(), currentY, SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT,
                0.0f, 1.0f, 0.0f);
        // Spike child collision area when active (red)
        int spikeOffset = SPIKE_Y_OFFSETS[mappingFrame & 0x0F];
        if (spikeOffset != 0) {
            ctx.drawRect(spawn.x(), currentY + spikeOffset, 12, 12,
                    1.0f, 0.0f, 0.0f);
        }
    }

    // ===== Solid Object =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        // Obj_AIZSpikedLog calls SolidObjectFull (sonic3k.asm:60148-60153).
        // That helper skips Player_2 when render_flags bit 7 is clear
        // (sonic3k.asm:41003-41008), unlike SolidObjectFull2.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
    }

    // ===== Child Access =====

    /**
     * Returns the current Y-offset for the spike collision based on mapping frame.
     * From byte_2B918 table. Returns 0 when spikes are not in a dangerous position.
     */
    int getSpikeYOffset() {
        return SPIKE_Y_OFFSETS[mappingFrame & 0x0F];
    }

    /**
     * Invisible child object providing spike collision for the spiked log.
     * Follows the parent's position with a Y-offset based on the current animation frame.
     * Collision is disabled when the Y-offset is 0 (spikes not pointing dangerously).
     * <p>
     * ROM references: loc_2B8EE (sonic3k.asm:60178-60196), byte_2B918 Y-offset table.
     */
    static class SpikedLogCollisionChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {

        // collision_flags = 0x9C: HURT type (bit 7), size index 0x1C (sonic3k.asm:60051)
        private static final int COLLISION_FLAGS_ACTIVE = 0x9C;
        private AizSpikedLogObjectInstance parent;
        private int currentX;
        private int currentY;

        SpikedLogCollisionChild(ObjectSpawn spawn, AizSpikedLogObjectInstance parent) {
            super(spawn, "AIZSpikedLogSpikes");
            this.parent = parent;
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            AizSpikedLogObjectInstance parent = findNearestLiveParentForRewind(ctx);
            return parent == null ? null : new SpikedLogCollisionChild(ctx.spawn(), parent);
        }

        private static AizSpikedLogObjectInstance findNearestLiveParentForRewind(
                RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                    || ctx.objectServices().objectManager() == null) {
                return null;
            }
            AizSpikedLogObjectInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            int childX = ctx.spawn().x();
            for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
                if (inst instanceof AizSpikedLogObjectInstance candidate && !candidate.isDestroyed()) {
                    long dx = (long) candidate.getX() - childX;
                    long distance = dx * dx;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
            return best;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            // Copy parent position (sonic3k.asm:60179-60181)
            currentX = parent.getX();
            currentY = parent.getY();

            // Apply Y offset based on parent's mapping frame (sonic3k.asm:60183-60187)
            int yOffset = parent.getSpikeYOffset();
            if (yOffset != 0) {
                currentY += yOffset;
            }
        }

        /**
         * ROM parity: {@code loc_2B8EE} copies the parent position, applies the
         * mapping-frame spike offset, and only THEN tail-calls
         * {@code Add_SpriteToCollisionResponseList} (sonic3k.asm:60179-60190). The
         * entry placed on {@code Collision_response_list} therefore carries this
         * child's freshly-computed current x/y, not a frame-start snapshot. The
         * inline player/sidekick touch path must read the live position for this
         * object (mirroring Obj37 bouncing rings / lost rings) or the spike's
         * dangerous-Y frame is consulted one frame late, missing the AIZ2
         * underwater hurt the ROM applies on the contact frame.
         */
        @Override
        public boolean usesCurrentTouchResponseState() {
            return true;
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
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(currentX, currentY);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible child — no rendering
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY);
        }

        // ===== Touch Response =====

        @Override
        public int getCollisionFlags() {
            // Only active when spike offset is non-zero (sonic3k.asm:60185 beq.s locret_2B916)
            return parent.getSpikeYOffset() != 0 ? COLLISION_FLAGS_ACTIVE : 0;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }
}
