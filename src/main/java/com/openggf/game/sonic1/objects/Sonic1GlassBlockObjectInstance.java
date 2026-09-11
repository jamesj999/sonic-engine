package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 30 - MZ Large Green Glass Blocks.
 * <p>
 * Tall or short translucent green pillars in Marble Zone. Each placement spawns two
 * objects: the solid glass block and a reflected shine overlay rendered at lower priority.
 * <p>
 * The main block provides all-sides-solid collision. A vertical distance value
 * ({@code glass_dist}, objoff_32) controls how far the block rises from its base Y.
 * Five movement subtypes determine how glass_dist changes:
 * <ul>
 *   <li>Type 0: Stationary (glass_dist stays at initial $90)</li>
 *   <li>Type 1: Oscillation (v_oscillate+$12), amplitude $40</li>
 *   <li>Type 2: Oscillation (v_oscillate+$12) inverted, amplitude $40</li>
 *   <li>Type 3: Stand-activated lowering with debounce and step-by-step descent</li>
 *   <li>Type 4: Switch-activated lowering (f_switch, high nybble = switch index)</li>
 * </ul>
 * <p>
 * Subtypes 0-2 use tall block (height $48), subtypes 3-4 use short block (height $38).
 * <p>
 * Reflection children (routines 4 and 8) track the parent's glass_dist and base Y,
 * applying the same subtype-based movement. Reflection for subtypes 3-4 additionally
 * copies the parent's base Y (objoff_30).
 * <p>
 * The subtype bit 3 controls reflection-specific behavior: for types 1-2 the
 * oscillation direction is inverted and halved; for types 3-4 the reflection uses
 * direct oscillation instead of stand/switch logic.
 * <p>
 * Reference: docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm
 */
public class Sonic1GlassBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // --- Collision parameters ---

    // From disassembly Glass_Block012: move.w #$2B,d1
    private static final int HALF_WIDTH = 0x2B;

    // From disassembly: move.b #$20,obActWid(a0)
    private static final int ACT_WIDTH = 0x20;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY_BLOCK = 4;

    // --- Height variants ---
    // Glass_Block012: move.w #$48,d2 / move.w #$49,d3
    private static final int TALL_HALF_HEIGHT_AIR = 0x48;
    private static final int TALL_HALF_HEIGHT_GND = 0x49;
    // Glass_Block34: move.w #$38,d2 / move.w #$39,d3
    private static final int SHORT_HALF_HEIGHT_AIR = 0x38;
    private static final int SHORT_HALF_HEIGHT_GND = 0x39;

    // --- Movement constants ---
    // From disassembly: move.w #$90,glass_dist(a0)
    private static final int INITIAL_GLASS_DIST = 0x90;
    // Glass_Type01/02: move.w #$40,d1
    private static final int OSC_AMPLITUDE = 0x40;
    // v_oscillate+$12: byte offset = $12 - 2 (control word) = $10
    private static final int OSC_BYTE_OFFSET = 0x10;

    // Type 03 debounce: move.w #$10,objoff_36(a0)
    private static final int TYPE3_SHORT_STEP = 0x10;
    // Type 03: move.w #$40,objoff_36(a0)
    private static final int TYPE3_LONG_STEP = 0x40;
    // Type 03: move.b #$A,objoff_38(a0) (delay frames)
    private static final int TYPE3_DELAY_FRAMES = 0x0A;
    // Type 03: cmpi.w #$40,glass_dist(a0) (threshold for long step)
    private static final int TYPE3_LONG_STEP_THRESHOLD = 0x40;

    // Type 04: subq.w #2,glass_dist(a0) (lowering speed)
    private static final int TYPE4_LOWER_SPEED = 2;

    // --- Object state ---

    // Whether this is the tall variant (subtypes 0-2) or short (subtypes 3-4)
    private boolean isTall;

    // Movement subtype (low 3 bits of obSubtype, masked to 0-4)
    private int moveType;

    // Full subtype byte for switch index extraction
    private int fullSubtype;

    // Block frame: 0 = tall, 2 = short
    private int blockFrame;

    // Dynamic position
    private int x;
    private int y;

    // Base Y position (objoff_30): the bottom reference for glass_dist subtraction
    private int baseY;

    // Vertical rise distance (glass_dist = objoff_32): Y = baseY - glass_dist
    private int glassDist;

    // Type 03 state: objoff_34 (activation flag + bit 7 for active lowering)
    private int type3ActivationFlags;
    // Type 03 state: objoff_35 (debounce edge detection)
    private int type3EdgeFlags;
    // Type 03 state: objoff_36 (step countdown)
    private int type3StepCountdown;
    // Type 03 state: objoff_38 (delay countdown)
    private int type3DelayCountdown;

    // Type 04 state: objoff_34 (switch activated flag)
    private boolean type4Activated;

    // Whether player is currently standing on this block
    private boolean playerStanding;

    // The spawned reflection child (for cleanup)
    private Sonic1GlassReflectionInstance reflectionChild;


    public Sonic1GlassBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MzGlassBlock");

        int subtype = spawn.subtype() & 0xFF;
        this.fullSubtype = subtype;

        // From disassembly: cmpi.b #3,obSubtype(a0) / blo.s .IsType012
        this.isTall = subtype < 3;
        this.moveType = subtype & 0x07;

        // Block frame: 0 = tall (.tall), 2 = short (.short)
        // From Glass_Vars1/2: frame byte is 0 for block, 1 for shine
        this.blockFrame = isTall ? 0 : 2;

        this.x = spawn.x();
        this.baseY = spawn.y();
        // From disassembly: move.w #$90,glass_dist(a0)
        this.glassDist = INITIAL_GLASS_DIST;
        this.y = baseY - glassDist;

        this.type3ActivationFlags = 0;
        this.type3EdgeFlags = 0;
        this.type3StepCountdown = 0;
        this.type3DelayCountdown = 0;
        this.type4Activated = false;
        this.playerStanding = false;

        updateDynamicSpawn(x, y);
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM: the reflection child is allocated in Glass_Main (routine 0),
        // after ObjPosLoad has already finished loading this frame's parents.
        // Spawning it during construction lets it steal SST slots from later
        // ObjPosLoad siblings in the same pass, which does not happen in S1.
        if (reflectionChild == null) {
            spawnReflection();
        }

        // ROM order: Glass_Block012/34 runs Glass_Types first using the prior
        // frame's standing latch (obStatus bit 3), then calls SolidObject at
        // the updated Y to resolve this frame's contact.
        applyMovement();

        // Compute Y from base Y and glass_dist
        // From disassembly loc_B5EE: move.w objoff_30(a0),d1 / sub.w d0,d1 / move.w d1,obY(a0)
        y = baseY - glassDist;

        updateDynamicSpawn(x, y);

        SolidCheckpointBatch batch = checkpointAll();
        playerStanding = hasStandingContact(batch);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_GLASS_BLOCK);
        if (renderer == null) return;

        renderer.drawFrameIndex(blockFrame, x, y, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        SolidObjectParams params = getSolidParams();
        ctx.drawRect(x, y, params.halfWidth(), params.airHalfHeight(), 0.0f, 0.8f, 0.3f);

        String typeLabel = String.format("Glass:T%d dist=%d", moveType, glassDist);
        ctx.drawWorldLabel(x, y, -2, typeLabel, DebugColor.GREEN);
    }

    // --- SolidObjectProvider ---

    @Override
    public SolidObjectParams getSolidParams() {
        if (isTall) {
            return SolidObjectParams.of(HALF_WIDTH, TALL_HALF_HEIGHT_AIR, TALL_HALF_HEIGHT_GND);
        } else {
            return SolidObjectParams.of(HALF_WIDTH, SHORT_HALF_HEIGHT_AIR, SHORT_HALF_HEIGHT_GND);
        }
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Glass_Block012/34 call SolidObject, whose unsigned horizontal reject
        // uses `bhi`; equality at twice d1 remains inside the solid.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Oscillation/lowering rebuilds dynamicSpawn as y_pos changes, while
        // the native pushing bit remains in this same Obj30 SST status byte.
        return true;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Glass blocks use standard SolidObject with full collision (top, sides, bottom)
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing state is driven via manual checkpoints in update().
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BLOCK);
    }

    @Override
    public boolean isPersistent() {
        if (isDestroyed()) {
            return false;
        }
        // out_of_range.w uses spawn X; checks d1 against #$2A0 (standard S1 range)
        return isInRangeAt(spawn.x());
    }

    /**
     * ROM parity: When a glass block is unloaded (removed from active set),
     * mark it as destroyed so the reflection child detects the parent's
     * removal and self-destructs. In the ROM, the shared out_of_range check
     * at the GlassBlock entry point applies to BOTH the block and its
     * reflection independently, so both are deleted in the same frame.
     * Without this, reflections "leak" — they persist after their parent
     * is removed, occupying SST slots indefinitely.
     */
    @Override
    public void onUnload() {
        setDestroyed(true);
        // ROM parity: In the ROM, the reflection (child object) has its own
        // out_of_range check which fires during ExecuteObjects (before ObjPosLoad).
        // Since the reflection shares the parent's X position, both go out of
        // range on the same frame. The engine unloads the parent during the
        // placement phase (syncActiveSpawnsUnload), but the reflection is a
        // dynamic object that only self-destructs during the exec loop. By
        // marking it destroyed here, the pre-load cleanup pass can free its
        // slot immediately, matching the ROM's slot availability at FindFreeObj time.
        if (reflectionChild != null && !reflectionChild.isDestroyed()) {
            reflectionChild.setDestroyed(true);
        }
    }

    /**
     * Returns the current glass_dist value. Used by the reflection child to
     * sync its vertical offset with the block.
     */
    int getGlassDist() {
        return glassDist;
    }

    /**
     * Returns the current base Y. Only remaining caller is the reflection's
     * rewind relink distance heuristic (findLiveGlassBlockParentForRewind);
     * the reflection's per-frame sync reads the parent's live getY() instead
     * (ROM Glass_Reflect34 copies the parent's current obY, not the spawn baseline).
     */
    int getBaseY() {
        return baseY;
    }

    // --- Movement dispatch ---

    /**
     * Glass_Types: dispatch movement based on subtype.
     * After this method, glassDist is updated and Y is computed in update().
     */
    private void applyMovement() {
        switch (moveType) {
            case 0 -> { /* Glass_Type00: stationary, rts */ }
            case 1 -> applyOscillation(false);
            case 2 -> applyOscillation(true);
            case 3 -> applyStandActivated();
            case 4 -> applySwitchActivated();
        }
    }

    /**
     * Glass_Type01 / Glass_Type02: Oscillation-based movement.
     * <p>
     * Type 01: d0 = v_oscillate+$12; d1 = $40; falls through to loc_B526 -> loc_B5EE
     * Type 02: same but neg.w d0 / add.w d1,d0 (inverted oscillation)
     * <p>
     * The glass block (routine 2/6, not reflection) does NOT have bit 3 set in subtype,
     * so the btst #3 check at loc_B514 is false -> goes to loc_B526 -> loc_B5EE.
     * glassDist = d0 directly.
     */
    private void applyOscillation(boolean inverted) {
        int d0 = OscillationManager.getByte(OSC_BYTE_OFFSET);
        int d1 = OSC_AMPLITUDE;

        if (inverted) {
            // Glass_Type02: neg.w d0 / add.w d1,d0
            d0 = -d0 + d1;
        }

        // Block has subtype bit 3 clear -> goes straight to loc_B526 -> loc_B5EE
        // loc_B5EE: glassDist = d0
        glassDist = d0;
    }

    /**
     * Glass_Type03: Stand-activated lowering.
     * <p>
     * When the player stands on the block (obStatus bit 3), a debounce system
     * detects the rising edge. On the first stand event, it sets a delay timer
     * (objoff_38 = $A). When the delay expires, it decrements glass_dist by
     * step amounts (objoff_36). The step size is $10 normally, or $40 if
     * glass_dist is exactly $40 at the time of activation.
     * <p>
     * The block stays at 0 when fully lowered.
     * <p>
     * Reference: docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm
     *            loc_B53E through loc_B5AA
     */
    private void applyStandActivated() {
        // loc_B53E: btst #3,obStatus(a0) / bne loc_B54E
        if (playerStanding) {
            // loc_B54E: tst.b objoff_34(a0) / bne loc_B582
            if (type3ActivationFlags == 0) {
                // First time standing: set activation flag
                // move.b #1,objoff_34(a0)
                type3ActivationFlags = 1;

                // bset #0,objoff_35(a0) / beq loc_B582
                boolean wasAlreadySet = (type3EdgeFlags & 1) != 0;
                type3EdgeFlags |= 1;
                if (wasAlreadySet) {
                    // Edge already detected: set bit 7 of activation flags to start lowering
                    // bset #7,objoff_34(a0)
                    type3ActivationFlags |= 0x80;
                    // move.w #$10,objoff_36(a0)
                    type3StepCountdown = TYPE3_SHORT_STEP;
                    // move.b #$A,objoff_38(a0)
                    type3DelayCountdown = TYPE3_DELAY_FRAMES;
                    // cmpi.w #$40,glass_dist(a0) / bne loc_B582
                    if (glassDist == TYPE3_LONG_STEP_THRESHOLD) {
                        // move.w #$40,objoff_36(a0)
                        type3StepCountdown = TYPE3_LONG_STEP;
                    }
                }
            }
            // else: already activated, fall through to loc_B582
        } else {
            // Not standing: bclr #0,objoff_34(a0) -> clear activation flag low bit
            type3ActivationFlags &= ~1;
            // Fall through to loc_B582
        }

        // loc_B582: Process active lowering
        if ((type3ActivationFlags & 0x80) != 0) {
            // tst.b objoff_38(a0) / beq loc_B594
            if (type3DelayCountdown > 0) {
                // subq.b #1,objoff_38(a0) / bne loc_B5AA
                type3DelayCountdown--;
                if (type3DelayCountdown != 0) {
                    // loc_B5AA: glassDist = glass_dist(a0) -> no change, just use current
                    return;
                }
            }

            // loc_B594: tst.w glass_dist(a0) / beq loc_B5A4
            if (glassDist > 0) {
                // subq.w #1,glass_dist(a0)
                glassDist--;
                // subq.w #1,objoff_36(a0) / bne loc_B5AA
                type3StepCountdown--;
                if (type3StepCountdown != 0) {
                    return;
                }
            }

            // loc_B5A4: bclr #7,objoff_34(a0)
            type3ActivationFlags &= ~0x80;
        }

        // loc_B5AA: glassDist is current glass_dist value (already set)
    }

    /**
     * Glass_Type04: Switch-activated lowering.
     * <p>
     * Reads the switch index from the high nybble of the original subtype byte.
     * When switch is pressed (f_switch[index] != 0), activates and lowers glass_dist
     * by 2 per frame until it reaches 0.
     * <p>
     * Reference: docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm
     *            Glass_ChkSwitch through loc_B5EA
     */
    private void applySwitchActivated() {
        // Glass_ChkSwitch: tst.b objoff_34(a0) / bne loc_B5E0
        if (!type4Activated) {
            // Check switch state: the high nybble indexes into f_switch
            int switchIndex = (fullSubtype >> 4) & 0x0F;
            if (services().gameService(Sonic1SwitchManager.class).isPressed(switchIndex)) {
                type4Activated = true;
            }
        }

        if (type4Activated) {
            // loc_B5E0: tst.w glass_dist(a0) / beq loc_B5EA
            if (glassDist > 0) {
                // subq.w #2,glass_dist(a0)
                glassDist -= TYPE4_LOWER_SPEED;
                if (glassDist < 0) {
                    glassDist = 0;
                }
            }
        }

        // loc_B5EA: glass_dist is current value
    }

    /**
     * The pillar's SST {@code obActWid}.
     *
     * <p>{@code Glass_Main} sets {@code move.b #64/2,obActWid(a1)} for the
     * pillar and only then overwrites it with {@code #32/2} for the reflection
     * child (docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm:78,84).
     * The pillar's own slot is child 0, reused via {@code movea.l a0,a1} at
     * {@code :57}, and the shine is a separate instance here, so this class is
     * always the pillar and always the wider value.
     *
     * <p>The byte is supplied here rather than at
     * {@link #getBalanceWidthPixels()} because both of its ROM consumers want
     * it. {@code BuildSprites} uses it as the horizontal on-screen cull bound,
     * testing {@code obX - cameraX +/- obActWid} against 0 and 320
     * (docs/s1disasm/_inc/BuildSprites.asm:49-58), and {@code Sonic_Balance}
     * reads the same byte off the stood-on object
     * (docs/s1disasm/_incObj/01 Sonic.asm:423). {@code getBalanceWidthPixels()}
     * defaults to this accessor, so overriding it alone would have left the
     * pillar culled at 16 where the ROM culls at 32. Neither is the rendered
     * extent -- {@code Map_Glass} owns that -- nor the collision width, which
     * is {@code #64/2+sonic_solid_width} = {@code $2B} at {@code :99,:117} and
     * is modelled separately as {@link #HALF_WIDTH}.
     *
     * <p>Without the override the inherited 16 makes the balance test
     * {@code d1 = player_x + width - object_x} read 16px lower than the ROM's,
     * which puts a player standing anywhere in the pillar's left half below the
     * {@code #4} left-edge threshold and starts the balancing animation where
     * the ROM stands still (_incObj/01 Sonic.asm:425-433).
     */
    @Override
    public int getOnScreenHalfWidth() {
        return 64 / 2;
    }

    // --- Helpers ---

    /**
     * Spawn the reflection shine overlay child.
     * <p>
     * From Glass_Main .Repeat/.Load loop (second iteration):
     * Reflection uses frame 1 (shine), obActWid $10, obPriority 3.
     * Subtype gets addq.b #8 then andi.b #$F.
     */
    private void spawnReflection() {
        if (services().objectManager() == null) {
            return;
        }

        // Reflection subtype: addq.b #8,obSubtype(a1) / andi.b #$F,obSubtype(a1)
        final int reflectSubtype = ((fullSubtype + 8) & 0x0F);
        final int mySlot = getSlotIndex();

        reflectionChild = spawnFreeChild(() -> {
            Sonic1GlassReflectionInstance child = new Sonic1GlassReflectionInstance(
                    spawn, this, reflectSubtype, isTall);
            // ROM: FindNextFreeObj allocates slot after glass block
            ObjectLifetimeOps.assignFindNextFreeChildSlot(services().objectManager(), child, mySlot);
            return child;
        });
    }
}
