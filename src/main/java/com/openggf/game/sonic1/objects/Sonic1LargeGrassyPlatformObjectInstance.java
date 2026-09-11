package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 2F - Large grass-covered platforms (MZ).
 * <p>
 * Sloped solid platforms that appear only in Marble Zone. Three visual variants
 * (wide, sloped, narrow) are selected via the subtype high bits. Six movement
 * behaviors are selected via the subtype low 3 bits.
 * <p>
 * Subtype byte layout:
 * <ul>
 *   <li>Bits 7-3: Selects visual variant (data index = (subtype >> 2) & 0x1C)</li>
 *   <li>Bit 3: Inverts oscillation direction for types 1-4</li>
 *   <li>Bits 2-0: Movement type (0-5)</li>
 * </ul>
 * <p>
 * LGrass_Data entries (3 variants):
 * <ul>
 *   <li>Index 0: LGrass_Data1, frame 0, width $40 (wide flat)</li>
 *   <li>Index 1: LGrass_Data3, frame 1, width $40 (sloped, catches fire)</li>
 *   <li>Index 2: LGrass_Data2, frame 2, width $20 (narrow)</li>
 * </ul>
 * <p>
 * Movement types:
 * <ul>
 *   <li>0: Stationary</li>
 *   <li>1: Vertical oscillation (v_oscillate+2, amplitude $20)</li>
 *   <li>2: Vertical oscillation (v_oscillate+6, amplitude $30)</li>
 *   <li>3: Vertical oscillation (v_oscillate+$A, amplitude $40)</li>
 *   <li>4: Vertical oscillation (v_oscillate+$E, amplitude $60)</li>
 *   <li>5: Sinks when stood on (CalcSine-based), spawns fire at midpoint</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/2F MZ Large Grassy Platforms.asm
 */
public class Sonic1LargeGrassyPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider, SpawnRewindRecreatable {

    // From disassembly: move.b #5,obPriority(a0)
    private static final int PRIORITY = 5;

    // From disassembly: move.b #$40,obHeight(a0)
    private static final int HEIGHT = 0x40;

    // --- LGrass_Data: collision data pointers, frame numbers, platform widths ---
    // Index 0: LGrass_Data1, frame 0, width $40
    // Index 1: LGrass_Data3, frame 1, width $40
    // Index 2: LGrass_Data2, frame 2, width $20

    // Collision heightmap data from misc/mz_pfm1.bin (76 bytes) - wide flat platform
    private static final byte[] SLOPE_DATA_1 = {
            0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20,
            0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x21, 0x22,
            0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x2F,
            0x2E, 0x2D, 0x2C, 0x2B, 0x2A, 0x29, 0x28, 0x27,
            0x26, 0x25, 0x24, 0x23, 0x22, 0x21, 0x20, 0x20,
            0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20,
            0x20, 0x20, 0x20, 0x20
    };

    // Collision heightmap data from misc/mz_pfm2.bin (44 bytes) - narrow flat platform
    private static final byte[] SLOPE_DATA_2 = {
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30
    };

    // Collision heightmap data from misc/mz_pfm3.bin (76 bytes) - sloped platform
    private static final byte[] SLOPE_DATA_3 = {
            0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x21, 0x22,
            0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32,
            0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,
            0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x40, 0x40,
            0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40,
            0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x3F,
            0x3E, 0x3D, 0x3C, 0x3B, 0x3A, 0x39, 0x38, 0x37,
            0x36, 0x35, 0x34, 0x33, 0x32, 0x31, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30
    };

    // Data table: { slopeDataIndex, frameNumber, platformWidth }
    private static final int[][] VARIANT_DATA = {
            {0, 0, 0x40}, // Index 0: LGrass_Data1, frame 0, width $40
            {2, 1, 0x40}, // Index 1: LGrass_Data3, frame 1, width $40
            {1, 2, 0x20}, // Index 2: LGrass_Data2, frame 2, width $20
    };

    private static final byte[][] SLOPE_DATA_TABLE = {SLOPE_DATA_1, SLOPE_DATA_2, SLOPE_DATA_3};

    // Oscillation offsets (byte offset into OscillationManager data):
    // v_oscillate+2 -> oscillator index 0, byte offset 0
    // v_oscillate+6 -> oscillator index 1, byte offset 4
    // v_oscillate+$A -> oscillator index 2, byte offset 8
    // v_oscillate+$E -> oscillator index 3, byte offset 12
    private static final int[] OSC_OFFSETS = {0, 4, 8, 12};
    // Oscillation amplitudes: $20, $30, $40, $60
    private static final int[] OSC_AMPLITUDES = {0x20, 0x30, 0x40, 0x60};

    // Type 5 CalcSine: cmpi.b #$40,d0 / move.b #$40,d0
    private static final int SINK_MAX_ANGLE = 0x40;
    // Type 5: addq.b #4,d0 (when player standing)
    private static final int SINK_ANGLE_STEP_UP = 4;
    // Type 5: subq.b #2,d0 (when player not standing)
    private static final int SINK_ANGLE_STEP_DOWN = 2;
    // Type 5: cmpi.b #$20,objoff_34(a0) -> fire spawn threshold
    private static final int FIRE_SPAWN_ANGLE = 0x20;

    // Dynamic position
    private int x;
    private int y;

    // Saved base positions (lgrass_origX = objoff_2A, lgrass_origY = objoff_2C)
    private int baseX;
    private int baseY;

    // Visual frame (0=wide, 1=sloped, 2=narrow)
    private int mappingFrame;

    // Platform half-width ($40 or $20)
    private int platformWidth;

    // Collision slope data for this variant
    private final byte[] slopeData;

    // Movement type (low 3 bits of subtype after masking)
    private int moveType;

    // Bit 3 of original subtype: inverts oscillation direction
    private boolean invertOscillation;

    // Type 5 state: sinking angle (objoff_34)
    private int sinkAngle;

    // Type 5 state: whether fire has been spawned (objoff_35)
    private boolean fireSpawned;

    // Whether player is currently standing on this platform
    private boolean playerStanding;

    // Type 5 state: tracked fire children for sink offset updates and cleanup.
    // ROM equivalent: objoff_36 child table (count byte + slot indices).
    private final List<Sonic1GrassFireObjectInstance> fireChildren = new ArrayList<>();

    // Type 5 state: the initial walker fire (subtype 0) for cleanup tracking
    private Sonic1GrassFireObjectInstance walkerFire;

    public Sonic1LargeGrassyPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MzLargeGrassyPlatform");

        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();

        int subtype = spawn.subtype() & 0xFF;

        // Select variant from LGrass_Data: lsr.w #2,d0 / andi.w #$1C,d0
        // This gives a data index of 0, 4, or 8 (steps of 4 bytes = word + 2 bytes per entry).
        // We divide by 4 to get our array index.
        int dataIndex = ((subtype >> 2) & 0x1C) / 4;
        if (dataIndex >= VARIANT_DATA.length) {
            dataIndex = 0;
        }

        int slopeIndex = VARIANT_DATA[dataIndex][0];
        this.mappingFrame = VARIANT_DATA[dataIndex][1];
        this.platformWidth = VARIANT_DATA[dataIndex][2];
        this.slopeData = SLOPE_DATA_TABLE[slopeIndex];

        // Movement type: andi.b #$F,obSubtype(a0) then andi.w #7,d0
        this.moveType = subtype & 0x07;

        // Bit 3: btst #3,obSubtype(a0) -> invert oscillation
        this.invertOscillation = (subtype & 0x08) != 0;

        this.sinkAngle = 0;
        this.fireSpawned = false;
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
        // LGrass_Types: dispatch movement
        applyMovement();

        updateDynamicSpawn(x, y);

        // Manual checkpoints replace the legacy post-pass callback, so the
        // current frame's movement uses the previously latched standing state
        // and the new standing state is captured for the next frame.
        SolidCheckpointBatch batch = checkpointAll();
        playerStanding = hasStandingContact(batch);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_LARGE_GRASSY_PLATFORM);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    // --- SolidObjectProvider / SlopedSolidProvider ---

    @Override
    public SolidObjectParams getSolidParams() {
        // SolidObject2F uses custom slope-based collision.
        // The halfWidth for the check is obActWid + $B.
        // d2 is the "slope catch range" parameter: $20 for frames 0/1, $30 for frame 2.
        // From LGrass_Solid:
        //   move.w #$20,d2 / cmpi.b #2,obFrame(a0) / bne loc_AF8E / move.w #$30,d2
        //
        // Unlike standard SolidObject (which uses separate d2/d3 for air/ground half-heights),
        // SolidObject2F uses d2 for BOTH states. It defines the vertical window around the
        // slope-computed surface Y. Both airHalfHeight and groundHalfHeight must equal d2 so
        // that the engine's resolveSlopedContact (which uses groundHalfHeight) creates the
        // correct vertical acceptance window for side collision detection.
        int slopeCatchRange = (mappingFrame == 2) ? 0x30 : 0x20;
        return SolidObjectParams.of(platformWidth + 0x0B, slopeCatchRange, slopeCatchRange);
    }

    @Override
    public int getBalanceWidthPixels() {
        // LGrass_Main publishes the table's platform width as obActWid. The
        // extra $B is added only to SolidObject_Heightmap's collision input;
        // Sonic_Move reads the unpadded SST byte for its edge window.
        return platformWidth;
    }

    @Override
    public boolean isTopSolidOnly() {
        // SolidObject2F falls through to loc_FB0E (the standard solid resolution code),
        // which handles top landing, side pushout, AND bottom collision. These platforms
        // provide full solid collision, not just top-solid.
        return false;
    }

    @Override
    public byte[] getSlopeData() {
        return slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        // SolidObject2F checks obRender bit 0 for x-flip.
        // These objects do not use x-flip from placement.
        return false;
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        // S1 SolidObject2F:
        //   move.b obHeight(a1),d3
        //   add.w d3,d2
        //   ...
        //   add.w d2,d3
        // The platform's catch range ($20/$30) participates in the side/top-bottom
        // classification, which is required for edge side contact at the MZ1 trace's
        // first large-grassy-platform encounter.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // SolidObject_Heightmap rejects with `bhi`, so relX == width*2 is inside.
        // docs/s1disasm/_incObj/sub SolidObject.asm:123-131
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The oscillating platform rebuilds dynamicSpawn as y_pos changes, but
        // the native push bit remains in this same Obj2F SST status byte.
        return true;
    }

    @Override
    public int getSlopeBaseline() {
        // SolidObject2F uses relative slope samples:
        //   slopeOffset = slopeSample - slopeData[0]
        // and d2 provides the vertical compensation window. In this engine, that compensation
        // is represented by groundHalfHeight (set to d2 in getSolidParams()), so baseline must
        // remain slopeData[0] to keep the landed surface at the correct Y.
        return (slopeData != null && slopeData.length > 0) ? slopeData[0] : 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing state is driven via manual checkpoints in update().
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        // ROM: Solid_Landed re-reads obActWid (= platformWidth) for the narrower
        // landing check, NOT the collision halfWidth (= platformWidth + $B).
        // This prevents false landings at the extended collision edges.
        return platformWidth;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_Level,2,1) sets sprite priority bit.
        return true;
    }

    @Override
    public boolean isPersistent() {
        // out_of_range uses lgrass_origX (objoff_2A = spawn X), not current X.
        // Also checks objoff_35 (fireSpawned) flag for special cleanup.
        if (isDestroyed()) {
            return false;
        }
        // ROM: LGrass_ChkDel checks objoff_35, then obRender bit 7 (on-screen).
        // If fire spawned AND not on screen: LGrass_DelFlames cleans up children,
        // clears flags, then returns to DisplaySprite — the platform itself survives.
        // It does NOT delete the platform. The normal out_of_range check below
        // handles platform deletion independently.
        // obRender bit 7 is set by BuildSprites which considers obActWid, so the
        // platform is "on screen" as long as any part of it is visible. Use
        // platformWidth as margin to match the ROM's width-aware visibility check.
        if (fireSpawned && !isOnScreenX(platformWidth)) {
            cleanupFlames();
        }
        return isInRangeAt(baseX);
    }

    // --- Movement ---

    /**
     * LGrass_Types: dispatch to the movement handler based on moveType.
     */
    private void applyMovement() {
        switch (moveType) {
            case 0 -> { /* LGrass_Type00: stationary */ }
            case 1 -> applyOscillation(0); // v_oscillate+2, amp $20
            case 2 -> applyOscillation(1); // v_oscillate+6, amp $30
            case 3 -> applyOscillation(2); // v_oscillate+$A, amp $40
            case 4 -> applyOscillation(3); // v_oscillate+$E, amp $60
            case 5 -> applySinking();      // LGrass_Type05: sinks when stood on
            default -> { /* Unknown types: stationary */ }
        }
    }

    /**
     * Types 1-4: Vertical oscillation using global oscillator.
     * From disassembly:
     *   move.b (v_oscillate+N).w,d0
     *   move.w #amplitude,d1
     *   [LGrass_Move:]
     *   btst #3,obSubtype(a0) / beq loc_AFF2 / neg.w d0 / add.w d1,d0
     *   move.w lgrass_origY(a0),d1
     *   sub.w d0,d1
     *   move.w d1,obY(a0)
     *
     * @param oscIndex index into OSC_OFFSETS/OSC_AMPLITUDES arrays (0-3)
     */
    private void applyOscillation(int oscIndex) {
        int oscValue = OscillationManager.getByte(OSC_OFFSETS[oscIndex]);
        int amplitude = OSC_AMPLITUDES[oscIndex];

        int d0 = oscValue;
        if (invertOscillation) {
            // neg.w d0 / add.w d1,d0
            d0 = -d0 + amplitude;
        }

        // move.w lgrass_origY(a0),d1 / sub.w d0,d1 / move.w d1,obY(a0)
        int newY = baseY - d0;
        y = newY;
    }

    /**
     * Type 5 (LGrass_Type05): Platform sinks when player stands on it.
     * Uses CalcSine with objoff_34 as the angle parameter.
     * When angle reaches $20 (midpoint), spawns a GrassFire child.
     * Sine result >> 4 is added to baseY.
     *
     * From disassembly:
     *   When player standing: addq.b #4,d0 / cmpi.b #$40,d0
     *   When not standing: subq.b #2,d0 / bcc (else clamp to 0)
     *   CalcSine -> lsr.w #4,d0 -> add.w lgrass_origY + d0 -> obY
     *   At angle $20, spawn GrassFire child if not already spawned.
     *
     * After Y is computed, loc_B07A iterates the child table (objoff_36)
     * and updates each child's objoff_3C (sink offset) with d1 (= sinkOffset).
     */
    private void applySinking() {
        if (playerStanding) {
            // addq.b #4,d0 / cmpi.b #$40,d0 / blo loc_B01C / move.b #$40,d0
            sinkAngle += SINK_ANGLE_STEP_UP;
            if (sinkAngle > SINK_MAX_ANGLE) {
                sinkAngle = SINK_MAX_ANGLE;
            }
        } else {
            // subq.b #2,d0 / bcc loc_B01C / moveq #0,d0
            sinkAngle -= SINK_ANGLE_STEP_DOWN;
            if (sinkAngle < 0) {
                sinkAngle = 0;
            }
        }

        // jsr (CalcSine).l -> d0 = sine(sinkAngle)
        // lsr.w #4,d0 -> divide by 16
        int sineValue = calcSine(sinkAngle);
        int sinkOffset = sineValue >> 4;

        // add.w lgrass_origY(a0),d0 / move.w d0,obY(a0)
        y = baseY + sinkOffset;

        // Fire spawn check: cmpi.b #$20,objoff_34(a0) / bne loc_B07A
        // tst.b objoff_35(a0) / bne loc_B07A
        if (sinkAngle == FIRE_SPAWN_ANGLE && !fireSpawned) {
            fireSpawned = true;
            spawnGrassFire(sinkOffset);
        }

        // loc_B07A: Update all fire children's sink offset (objoff_3C).
        // ROM iterates objoff_36 table: move.w d1,objoff_3C(a1)
        updateFireChildrenSinkOffset(sinkOffset);
    }

    /**
     * Spawns the initial walking GrassFire (Object 0x35, subtype 0).
     * <p>
     * From LGrass_Type05 fire spawn code:
     * <pre>
     *   _move.b #id_GrassFire,obID(a1)
     *   move.w obX(a0),obX(a1)         ; fire starts at platform X
     *   move.w lgrass_origY(a0),lgrass_origY(a1) ; copy base Y
     *   addq.w #8,lgrass_origY(a1)     ; +8
     *   subq.w #3,lgrass_origY(a1)     ; -3 = net +5
     *   subi.w #$40,obX(a1)            ; start at left edge (X - $40)
     *   move.l objoff_30(a0),objoff_30(a1) ; copy slope data pointer
     *   move.l a0,objoff_38(a1)        ; store parent reference
     * </pre>
     *
     * @param sinkOffset current vertical sink offset (CalcSine >> 4)
     */
    private void spawnGrassFire(int sinkOffset) {
        if (services().objectManager() == null) {
            return;
        }

        // Fire base Y = lgrass_origY + 8 - 3 = baseY + 5
        final int fireBaseY = baseY + 5;
        // Fire starts at left edge: obX - $40
        final int fireStartX = x - 0x40;
        final int fSinkOffset = sinkOffset;
        final int parentSlot = getSlotIndex();

        walkerFire = spawnFreeChild(() -> {
            Sonic1GrassFireObjectInstance fire = new Sonic1GrassFireObjectInstance(
                    fireStartX, fireBaseY, fSinkOffset, slopeData, this, true);
            // ROM: FindNextFreeObj allocates a slot AFTER the platform's slot.
            // This ensures the fire runs its first update in the same frame
            // (its slot hasn't been processed yet in the ExecuteObjects loop).
            ObjectLifetimeOps.assignFindNextFreeChildSlot(services().objectManager(), fire, parentSlot);
            return fire;
        });

        // Register walker itself in children list for sink offset updates
        fireChildren.add(walkerFire);
    }

    /**
     * Registers a fire child for sink offset tracking.
     * Called by GrassFire walker when it spawns stationary children.
     * ROM equivalent: sub_B09C which stores child slot index in objoff_36 table.
     *
     * @param child the fire child to track
     */
    void registerFireChild(Sonic1GrassFireObjectInstance child) {
        fireChildren.add(child);
    }

    /**
     * Updates all tracked fire children's sink offset.
     * ROM: loc_B07A loop iterates objoff_36 and writes d1 to objoff_3C of each child.
     *
     * @param sinkOffset the current sink offset value
     */
    private void updateFireChildrenSinkOffset(int sinkOffset) {
        for (int i = fireChildren.size() - 1; i >= 0; i--) {
            Sonic1GrassFireObjectInstance child = fireChildren.get(i);
            if (child.isDestroyed()) {
                fireChildren.remove(i);
            } else {
                child.setSinkOffset(sinkOffset);
            }
        }
    }

    // --- Helpers ---

    /**
     * LGrass_DelFlames: Clean up flame children when platform goes offscreen.
     * ROM iterates objoff_36 child table, calls DeleteChild on each, then
     * clears objoff_35 (fireSpawned) and objoff_34 (sinkAngle).
     */
    private void cleanupFlames() {
        // Destroy the walker fire and all its spawned children
        if (walkerFire != null) {
            walkerFire.destroyWithChildren();
            walkerFire = null;
        }
        // Also destroy any remaining tracked children directly
        for (Sonic1GrassFireObjectInstance child : fireChildren) {
            if (!child.isDestroyed()) {
                child.setDestroyed(true);
            }
        }
        fireChildren.clear();
        fireSpawned = false;
        sinkAngle = 0;
    }

    /**
     * Mega Drive CalcSine for angles 0 to $FF.
     * Returns 8.8 fixed-point sine value.
     * From the ROM's sine lookup table.
     */
    private static int calcSine(int angle) {
        if (angle <= 0) return 0;
        // CalcSine uses a 256-entry quarter-wave table, but for this object
        // the angle only goes up to $40 (90 degrees).
        // Sine of angle in range [0, $40] maps to [0, $100] (0.0 to 1.0 in 8.8)
        double radians = (angle & 0xFF) * Math.PI * 2.0 / 256.0;
        return (int) (Math.sin(radians) * 256) & 0xFFFF;
    }

    /**
     * ROM parity: When a grassy platform is unloaded (removed from active set),
     * ensure any fire children are cleaned up. The primary cleanup path is in
     * {@link #isPersistent()} which calls {@link #cleanupFlames()} when the
     * platform goes off-screen. This override provides a safety net for edge
     * cases where the platform is removed via a different path (e.g., level
     * transition, direct removal) without isPersistent() being called first.
     */
    @Override
    public void onUnload() {
        if (fireSpawned) {
            cleanupFlames();
        }
    }
}
