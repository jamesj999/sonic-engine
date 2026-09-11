package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractFallingFragment;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Object 0x0F - Collapsing Bridge (Sonic 3 &amp; Knuckles).
 * <p>
 * A flat-top solid platform that collapses when the player stands on it.
 * Used across many zones: LBZ, HCZ, MGZ, ICZ, HPZ, LRZ, FBZ, SOZ.
 * Each zone provides its own mappings, dimensions, and collapse delay tables.
 * <p>
 * When the player stands on the bridge, a countdown timer ($38) begins.
 * When the timer expires, the bridge splits into individual mapping piece
 * fragments that fall in a staggered wave pattern defined by per-zone delay
 * tables. The player is tracked during collapse and released when the wave
 * front reaches their position.
 * <p>
 * Three collapse modes:
 * <ul>
 *   <li><b>STANDARD</b>: Collapse triggered by player standing. Supports
 *       directional collapse (wave direction depends on player position)
 *       when the directional flag is set.</li>
 *   <li><b>TRIGGER</b>: Collapse triggered by Level_trigger_array
 *       (HCZ/ICZ subtype bit 7). An external button sets the trigger.</li>
 *   <li><b>MGZ_STOMP</b>: MGZ type 2 — shatters when the player is rolling
 *       (spindash release) while standing on it. Uses BreakObjectToPieces
 *       with initial velocities instead of timed collapse.</li>
 * </ul>
 * <p>
 * ROM references: Obj_CollapsingBridge (sonic3k.asm:44886),
 * CollapsingPtfmHandlePlayerAndSmash (sonic3k.asm:45387),
 * ObjPlatformCollapse_SmashObject (sonic3k.asm:45400),
 * Check_CollapsePlayerRelease (sonic3k.asm:45349).
 */
public class CollapsingBridgeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider,
        SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(CollapsingBridgeObjectInstance.class.getName());

    // ===== Constants =====

    // Priority: $280 = bucket 5 (ROM: move.w #$280,priority(a0))
    private static final int PRIORITY = 5;

    // MGZ stomp priority: $80 = bucket 1 (ROM: move.w #$80,priority(a0))
    private static final int MGZ_STOMP_PRIORITY = 1;

    // Obj_CollapsingBridge routines live at $00020AF6/$00020B8C; S3K CPU interact stores the high word.
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0002;

    // Collision height for SolidObjectTop: d3 = $10 (ROM: move.w #$10,d3)
    private static final int SOLID_HEIGHT = 0x10;

    // Gravity for falling fragments: $38/frame (ROM: MoveSprite gravity)
    private static final int GRAVITY = 0x38;

    // Gravity for MGZ stomp debris: $18/frame (ROM: addi.w #$18,y_vel)
    private static final int MGZ_GRAVITY = 0x18;

    // ===== Collapse Mode =====

    private enum CollapseMode {
        /** Normal standing-triggered collapse with wave-based fragment delays. */
        STANDARD,
        /** Triggered by Level_trigger_array (HCZ/ICZ subtype bit 7). */
        TRIGGER,
        /** MGZ ground-pound: shatters with velocity vectors when player rolls. */
        MGZ_STOMP
    }

    @Override
    public int romObjectCodePointerHighWord() {
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    // ===================================================================
    // Timer arrays (exact ROM data per zone)
    // ===================================================================

    // ----- LBZ -----

    // LBZBridgeCollapse_TimerArray (16 bytes)
    private static final int[] LBZ_BRIDGE_DELAYS = {
            0x20, 0x1C, 0x18, 0x14, 0x10, 0x0C, 0x08, 0x04,
            0x1E, 0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x06, 0x02
    };

    // LBZBridgeCollapse_TimerFlipArray (16 bytes)
    private static final int[] LBZ_BRIDGE_DELAYS_FLIP = {
            0x20, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C,
            0x02, 0x06, 0x0A, 0x0E, 0x12, 0x16, 0x1A, 0x1E
    };

    // LBZLedgeCollapse_TimerArray (14 bytes)
    private static final int[] LBZ_LEDGE_DELAYS = {
            0x20, 0x18, 0x10, 0x08, 0x1E, 0x16, 0x0E, 0x06,
            0x1C, 0x14, 0x0C, 0x04, 0x1A, 0x12
    };

    // ----- HCZ -----

    // byte_20D22 / byte_20D32 (type 0, 16 bytes each)
    private static final int[] HCZ_0_DELAYS = {
            0x20, 0x1C, 0x18, 0x14, 0x10, 0x0C, 0x08, 0x04,
            0x1E, 0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x06, 0x02
    };
    private static final int[] HCZ_0_DELAYS_FLIP = {
            0x20, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C,
            0x02, 0x06, 0x0A, 0x0E, 0x12, 0x16, 0x1A, 0x1E
    };

    // byte_20D42 / byte_20D56 (type 1, 20 bytes each)
    private static final int[] HCZ_1_DELAYS = {
            0x28, 0x24, 0x20, 0x1C, 0x18, 0x14, 0x10, 0x0C, 0x08, 0x04,
            0x26, 0x22, 0x1E, 0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x06, 0x02
    };
    private static final int[] HCZ_1_DELAYS_FLIP = {
            0x28, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C, 0x20, 0x24,
            0x02, 0x06, 0x0A, 0x0E, 0x12, 0x16, 0x1A, 0x1E, 0x22, 0x26
    };

    // byte_20D6A (type 2, 15 bytes - same for both directions, non-directional)
    private static final int[] HCZ_2_DELAYS = {
            0x1C, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18,
            0x02, 0x06, 0x0A, 0x0E, 0x12, 0x16, 0x1A, 0x1E
    };

    // byte_20D79 / byte_20D91 (type 3, 24 bytes each)
    private static final int[] HCZ_3_DELAYS = {
            0x30, 0x2A, 0x24, 0x1E, 0x18, 0x12, 0x0C, 0x06,
            0x2E, 0x28, 0x22, 0x1C, 0x16, 0x10, 0x0A, 0x04,
            0x2C, 0x26, 0x20, 0x1A, 0x14, 0x0E, 0x08, 0x02
    };
    private static final int[] HCZ_3_DELAYS_FLIP = {
            0x30, 0x06, 0x0C, 0x12, 0x18, 0x1E, 0x24, 0x2A,
            0x04, 0x0A, 0x10, 0x16, 0x1C, 0x22, 0x28, 0x2E,
            0x02, 0x08, 0x0E, 0x14, 0x1A, 0x20, 0x26, 0x2C
    };

    // ----- MGZ / FBZ (shared) -----

    // byte_20DA9 / byte_20DC9 (MGZ type 0/2 and FBZ type 0, 32 bytes each)
    private static final int[] MGZ_FBZ_DELAYS = {
            0x40, 0x38, 0x30, 0x28, 0x20, 0x18, 0x10, 0x08,
            0x3E, 0x36, 0x2E, 0x26, 0x1E, 0x16, 0x0E, 0x06,
            0x3C, 0x34, 0x2C, 0x24, 0x1C, 0x14, 0x0C, 0x04,
            0x3A, 0x32, 0x2A, 0x22, 0x1A, 0x12, 0x0A, 0x02
    };
    private static final int[] MGZ_FBZ_DELAYS_FLIP = {
            0x40, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38,
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E,
            0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C,
            0x02, 0x0A, 0x12, 0x1A, 0x22, 0x2A, 0x32, 0x3A
    };

    // byte_20DE9 / byte_20E01 (MGZ type 1, 24 bytes each)
    private static final int[] MGZ_1_DELAYS = {
            0x30, 0x28, 0x20, 0x18, 0x10, 0x08,
            0x2E, 0x26, 0x1E, 0x16, 0x0E, 0x06,
            0x2C, 0x24, 0x1C, 0x14, 0x0C, 0x04,
            0x2A, 0x22, 0x1A, 0x12, 0x0A, 0x02
    };
    private static final int[] MGZ_1_DELAYS_FLIP = {
            0x30, 0x08, 0x10, 0x18, 0x20, 0x28,
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E,
            0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C,
            0x02, 0x0A, 0x12, 0x1A, 0x22, 0x2A
    };

    // ----- ICZ -----

    // byte_20E19 / byte_20E45 (44 bytes each)
    private static final int[] ICZ_DELAYS = {
            0x28, 0x24, 0x20, 0x1C, 0x18, 0x14, 0x10, 0x0C, 0x08, 0x04,
            0x27, 0x23, 0x1F, 0x1B, 0x17, 0x13, 0x0F, 0x0B, 0x07, 0x03,
            0x26, 0x22, 0x1E, 0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x06, 0x02,
            0x25, 0x21, 0x1D, 0x19, 0x15, 0x11, 0x0D, 0x09, 0x05, 0x01,
            0x04, 0x03, 0x02, 0x01
    };
    private static final int[] ICZ_DELAYS_FLIP = {
            0x28, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C, 0x20, 0x24,
            0x03, 0x07, 0x0B, 0x0F, 0x13, 0x17, 0x1B, 0x1F, 0x23, 0x27,
            0x02, 0x06, 0x0A, 0x0E, 0x12, 0x16, 0x1A, 0x1E, 0x22, 0x26,
            0x01, 0x05, 0x09, 0x0D, 0x11, 0x15, 0x19, 0x1D, 0x21, 0x25,
            0x01, 0x02, 0x03, 0x04
    };

    // ----- SOZ -----

    // byte_20E71 / byte_20E7B (10 bytes each)
    private static final int[] SOZ_DELAYS = {
            0x20, 0x18, 0x10, 0x08, 0x1E, 0x16, 0x1C, 0x14, 0x1A, 0x12
    };
    private static final int[] SOZ_DELAYS_FLIP = {
            0x20, 0x08, 0x10, 0x18, 0x06, 0x0E, 0x04, 0x0C, 0x02, 0x0A
    };

    // ----- HPZ / LRZ -----

    // byte_20E85 / byte_20E91 (12/13 bytes)
    private static final int[] HPZ_LRZ_DELAYS = {
            0x18, 0x12, 0x0C, 0x06, 0x16, 0x10, 0x0A, 0x04,
            0x14, 0x0E, 0x08, 0x02
    };
    private static final int[] HPZ_LRZ_DELAYS_FLIP = {
            0x18, 0x06, 0x0C, 0x12, 0x04, 0x0A, 0x10, 0x16,
            0x02, 0x08, 0x0E, 0x14
    };

    // ----- MGZ stomp velocity table -----

    /**
     * word_20A76: BreakObjectToPieces velocity pairs for MGZ stomp variant.
     * 32 entries: {xVel, yVel} in 256ths of a pixel per frame.
     * 4 rows × 8 columns, spread from center outward.
     */
    private static final int[][] MGZ_STOMP_VELOCITIES = {
            {-0x400, -0xA00}, {-0x300, -0xA00}, {-0x200, -0xA00}, {-0x100, -0xA00},
            { 0x100, -0xA00}, { 0x200, -0xA00}, { 0x300, -0xA00}, { 0x400, -0xA00},
            {-0x3C0, -0x900}, {-0x2C0, -0x900}, {-0x1C0, -0x900}, {-0x0C0, -0x900},
            { 0x0C0, -0x900}, { 0x1C0, -0x900}, { 0x2C0, -0x900}, { 0x3C0, -0x900},
            {-0x380, -0x800}, {-0x280, -0x800}, {-0x180, -0x800}, {-0x080, -0x800},
            { 0x080, -0x800}, { 0x180, -0x800}, { 0x280, -0x800}, { 0x380, -0x800},
            {-0x340, -0x700}, {-0x240, -0x700}, {-0x140, -0x700}, {-0x040, -0x700},
            { 0x040, -0x700}, { 0x140, -0x700}, { 0x240, -0x700}, { 0x340, -0x700}
    };

    // ===================================================================
    // Zone attribute tables: {halfWidth, displayHeight, mappingFrame, directionalFlag}
    // ===================================================================

    // HCZ: byte_20722 (4 entries)
    private static final int[][] HCZ_ATTRS = {
            {0x40, 0x10, 0, 0x80},
            {0x50, 0x10, 3, 0x80},
            {0x40, 0x10, 6, 0x00},  // Non-directional
            {0x50, 0x20, 9, 0x80}
    };
    private static final int[][][] HCZ_DELAY_PAIRS = {
            {HCZ_0_DELAYS, HCZ_0_DELAYS_FLIP},
            {HCZ_1_DELAYS, HCZ_1_DELAYS_FLIP},
            {HCZ_2_DELAYS, HCZ_2_DELAYS},   // Same for both (symmetric)
            {HCZ_3_DELAYS, HCZ_3_DELAYS_FLIP}
    };

    // MGZ: byte_207B0 (3 entries)
    private static final int[][] MGZ_ATTRS = {
            {0x40, 0x20, 0, 0x80},
            {0x30, 0x20, 3, 0x80},
            {0x40, 0x20, 6, 0x80}   // Stomp variant
    };
    private static final int[][][] MGZ_DELAY_PAIRS = {
            {MGZ_FBZ_DELAYS, MGZ_FBZ_DELAYS_FLIP},
            {MGZ_1_DELAYS, MGZ_1_DELAYS_FLIP},
            {MGZ_FBZ_DELAYS, MGZ_FBZ_DELAYS_FLIP}  // Same as type 0
    };

    // ===== Instance configuration (set once during construction via init methods) =====

    private String artKey;
    private int halfWidth;
    private int displayHeight; // For visibility bounds (width_pixels/height_pixels)
    private int initialMappingFrame;
    private boolean directional;
    private int[] delays;
    private int[] delaysFlip;
    private CollapseMode mode;
    private int triggerIndex;  // Level_trigger_array index for TRIGGER mode
    private boolean highPriorityArt; // make_art_tile priority=1 (HCZ only)

    // ===== Mutable state =====

    private int x;
    private int y;
    private boolean hFlip;

    /**
     * State machine:
     * <pre>
     *   0 = idle (solid, waiting for collapse trigger)
     *   1 = countdown ($38 timer decrementing)
     *   2 = wave collapse (fragments spawned, parent tracking player release)
     *   3 = falling (parent gravity fall, off-screen destroy)
     * </pre>
     */
    private int state;
    private int collapseTimer;        // $38 countdown (initialized per zone formula)
    private boolean collapseTriggered; // $3A flag
    private boolean collapseTriggerPending;
    private boolean fragmented;

    // Wave collapse phase tracking (state 2)
    private int parentTimer;           // Counts down from activeDelays[0]
    private int[] activeDelays;        // Selected fragment delay array (normal or flip)
    private int[] releaseDelays;       // Parent release always uses ROM $30.
    private boolean flippedForCollapse; // Sprite flipped during directional determination
    private int fragmentFrameIndex;    // Mapping frame used for fragment pieces
    private final Set<PlayableEntity> collapseWaveRiders =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Post-fragment fall state (state 3)
    private int velX;   // X velocity (subpixels, only used by MGZ stomp parent)
    private int velY;   // Y velocity (subpixels)
    private int xFrac;  // X subpixel fraction
    private int yFrac;  // Y subpixel fraction
    private boolean romRenderFlag = true;

    // ===== Constructor =====

    public CollapsingBridgeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CollapsingBridge");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        resolveZoneConfig(spawn.subtype() & 0xFF);
    }

    /**
     * Resolves zone-specific configuration. Delegates to per-zone init methods.
     * Config fields are set exactly once and are non-final to allow delegation.
     */
    private void resolveZoneConfig(int subtype) {
        int zone;
        try {
            zone = services().romZoneId();
        } catch (Exception e) {
            zone = -1;
        }

        switch (zone) {
            case Sonic3kZoneIds.ZONE_LBZ -> initLBZ(subtype);
            case Sonic3kZoneIds.ZONE_HCZ -> initHCZ(subtype);
            case Sonic3kZoneIds.ZONE_MGZ -> initMGZ(subtype);
            case Sonic3kZoneIds.ZONE_ICZ -> initICZ(subtype);
            case Sonic3kZoneIds.ZONE_HPZ -> initHPZ(subtype);
            case Sonic3kZoneIds.ZONE_LRZ -> initLRZ(subtype);
            case Sonic3kZoneIds.ZONE_FBZ -> initFBZ(subtype);
            case Sonic3kZoneIds.ZONE_SOZ -> initSOZ(subtype);
            default -> initFallback(subtype);
        }
    }

    // ===== Zone-specific initialization =====

    /**
     * LBZ (zone 6): subtype bits 0-5 = timer, bit 6 = bridge/ledge, bit 7 = directional.
     * ROM: sonic3k.asm:44890
     */
    private void initLBZ(int subtype) {
        this.mode = CollapseMode.STANDARD;
        this.triggerIndex = -1;
        // Timer: (subtype & $3F) * 4 + 8, stored as byte (low 8 bits).
        // ROM: move.b d0,$38(a0) performs byte truncation.
        this.collapseTimer = ((subtype & 0x3F) * 4 + 8) & 0xFF;
        this.directional = (subtype & 0x80) != 0;

        if ((subtype & 0x40) != 0) {
            // Ledge variant
            this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_LBZ_LEDGE;
            this.halfWidth = 0x20;
            this.displayHeight = 0x30;
            this.initialMappingFrame = 0;
            this.delays = LBZ_LEDGE_DELAYS;
            this.delaysFlip = LBZ_LEDGE_DELAYS; // Only one array for ledge
        } else {
            // Bridge variant
            this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_LBZ;
            this.halfWidth = 0x40;
            this.displayHeight = 0x10;
            this.initialMappingFrame = 0;
            this.delays = LBZ_BRIDGE_DELAYS;
            this.delaysFlip = LBZ_BRIDGE_DELAYS_FLIP;
        }
    }

    /**
     * HCZ (zone 1): subtype bit 7 = trigger mode, bits 4-6 = type, bits 0-3 = timer.
     * ROM: sonic3k.asm:44920
     */
    private void initHCZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_HCZ;
        // HCZ uses make_art_tile($001, 2, 1) — high priority set
        this.highPriorityArt = true;
        this.triggerIndex = (subtype & 0x80) != 0 ? (subtype & 0x0F) : -1;
        this.mode = triggerIndex >= 0 ? CollapseMode.TRIGGER : CollapseMode.STANDARD;

        int typeIndex = (subtype >> 4) & 0x07;
        if (typeIndex >= HCZ_ATTRS.length) {
            typeIndex = 0;
        }

        // Timer: ROM masks subtype to bits 4-6 for trigger mode before reaching timer calc.
        // After masking, low nibble is always 0, so trigger timer = 0*16+8 = 8.
        // ROM: andi.b #$70,d0 (line 44931) for trigger, then (d0 & $0F)*16+8 at loc_206EA.
        int timerSubtype = (mode == CollapseMode.TRIGGER) ? (subtype & 0x70) : subtype;
        this.collapseTimer = (timerSubtype & 0x0F) * 16 + 8;

        int[] attrs = HCZ_ATTRS[typeIndex];
        this.halfWidth = attrs[0];
        this.displayHeight = attrs[1];
        this.initialMappingFrame = attrs[2];
        // Directional flag from attribute table's subtype byte, which overwrites subtype(a0).
        // ROM: move.b (a1)+,subtype(a0) at line 44945 applies to BOTH standard and trigger modes.
        // The trigger handler also checks btst #7,subtype(a0) at loc_20AF6:45270.
        this.directional = (attrs[3] & 0x80) != 0;
        this.delays = HCZ_DELAY_PAIRS[typeIndex][0];
        this.delaysFlip = HCZ_DELAY_PAIRS[typeIndex][1];
    }

    /**
     * MGZ (zone 2): bits 4-6 = type, bits 0-3 = timer. Type 2 = stomp variant.
     * ROM: sonic3k.asm:44964
     */
    private void initMGZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ;
        this.triggerIndex = -1;
        this.collapseTimer = (subtype & 0x0F) * 16 + 8;

        int typeIndex = (subtype >> 4) & 0x07;
        if (typeIndex >= MGZ_ATTRS.length) {
            typeIndex = 0;
        }

        int[] attrs = MGZ_ATTRS[typeIndex];
        this.halfWidth = attrs[0];
        this.displayHeight = attrs[1];
        this.initialMappingFrame = attrs[2];
        this.directional = (attrs[3] & 0x80) != 0;
        this.delays = MGZ_DELAY_PAIRS[typeIndex][0];
        this.delaysFlip = MGZ_DELAY_PAIRS[typeIndex][1];

        // Type 2 (upper nibble 2): MGZ ground-pound / stomp variant
        // ROM: cmpi.w #$10,d1 then bne, where d1 = nibble value $20 >> 1 = $10 for type 2
        this.mode = (typeIndex == 2) ? CollapseMode.MGZ_STOMP : CollapseMode.STANDARD;
    }

    /**
     * ICZ (zone 5): subtype bit 7 = trigger mode, bits 4-6 unused, bits 0-3 = timer.
     * ROM: sonic3k.asm:45001
     */
    private void initICZ(int subtype) {
        // Shares mapping file with Object 0x04 (COLLAPSING_PLATFORM_ICZ)
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_ICZ;
        this.triggerIndex = (subtype & 0x80) != 0 ? (subtype & 0x0F) : -1;
        this.mode = triggerIndex >= 0 ? CollapseMode.TRIGGER : CollapseMode.STANDARD;
        // ROM: trigger mode masks subtype to bits 4-6 before timer calc (andi.b #$70,d0).
        // After masking, low nibble = 0, so timer = 8 for trigger mode.
        int timerSubtype = (mode == CollapseMode.TRIGGER) ? (subtype & 0x70) : subtype;
        this.collapseTimer = (timerSubtype & 0x0F) * 16 + 8;

        this.halfWidth = 0x50;
        this.displayHeight = 0x38;
        this.initialMappingFrame = 3; // ICZ Object 0x0F starts at frame 3
        // ICZ always directional: trigger mode retains bit 7 in subtype (it was the
        // trigger flag), and non-trigger mode has no attribute table to clear it.
        this.directional = true;
        this.delays = ICZ_DELAYS;
        this.delaysFlip = ICZ_DELAYS_FLIP;
    }

    /**
     * HPZ (zone 0x16): shared init with LRZ.
     * ROM: sonic3k.asm:45027
     */
    private void initHPZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_HPZ;
        initHpzLrzShared(subtype);
    }

    /**
     * LRZ (zone 9) via Object 0x0F: shared init with HPZ.
     * ROM: sonic3k.asm:45035
     */
    private void initLRZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_LRZ;
        initHpzLrzShared(subtype);
    }

    /**
     * Shared HPZ/LRZ initialization.
     * ROM: sonic3k.asm:45040 (shared code path after zone-specific mappings set)
     */
    private void initHpzLrzShared(int subtype) {
        this.mode = CollapseMode.STANDARD;
        this.triggerIndex = -1;
        this.collapseTimer = (subtype & 0x0F) * 16 + 8;
        // byte_2089A: only 3 bytes read (width, height, frame) — subtype NOT overwritten.
        // ROM: btst #7,subtype(a0) checks original spawn subtype.
        this.halfWidth = 0x20;
        this.displayHeight = 0x18;
        this.initialMappingFrame = 0;
        this.directional = (subtype & 0x80) != 0;
        this.delays = HPZ_LRZ_DELAYS;
        this.delaysFlip = HPZ_LRZ_DELAYS_FLIP;
    }

    /**
     * FBZ (zone 4): single type. ROM: sonic3k.asm:45066
     */
    private void initFBZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_FBZ;
        this.mode = CollapseMode.STANDARD;
        this.triggerIndex = -1;
        this.collapseTimer = (subtype & 0x0F) * 16 + 8;
        // FBZBridgeSpriteAttribute: {$40, $20, $00, $80}
        this.halfWidth = 0x40;
        this.displayHeight = 0x20;
        this.initialMappingFrame = 0;
        this.directional = true;
        this.delays = MGZ_FBZ_DELAYS;
        this.delaysFlip = MGZ_FBZ_DELAYS_FLIP;
    }

    /**
     * SOZ (zone 8): single type. ROM: sonic3k.asm:45097
     */
    private void initSOZ(int subtype) {
        this.artKey = Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_SOZ;
        this.mode = CollapseMode.STANDARD;
        this.triggerIndex = -1;
        this.collapseTimer = (subtype & 0x0F) * 16 + 8;
        // byte_20952: only 3 bytes read (width, height, frame) — subtype NOT overwritten.
        // ROM: btst #7,subtype(a0) checks original spawn subtype.
        this.halfWidth = 0x20;
        this.displayHeight = 0x30;
        this.initialMappingFrame = 0;
        this.directional = (subtype & 0x80) != 0;
        this.delays = SOZ_DELAYS;
        this.delaysFlip = SOZ_DELAYS_FLIP;
    }

    /**
     * Fallback for unknown zones - uses FBZ config as a safe default.
     */
    private void initFallback(int subtype) {
        LOG.warning("CollapsingBridge: unknown zone, defaulting to FBZ config");
        initFBZ(subtype);
    }

    // ===== SolidObjectProvider =====

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: SolidObjectTop with d1=width_pixels, d3=$10
        return SolidObjectParams.of(halfWidth, SOLID_HEIGHT, SOLID_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // SolidObjectTop reaches loc_1E45A for fresh contacts. Its unsigned
        // cmpi.w #-$10,d0 / blo accepts only negative overlap [-$10,-1],
        // rejecting the exact d0=0 boundary (sonic3k.asm:41982-42015).
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        // Obj_CollapsingBridge calls SolidObjectTop (sonic3k.asm:45170-45175),
        // whose loc_1E45A landing writes y_pos += d0 + 3 before
        // Player_TouchFloor restores the default radii (41996-42039). It does
        // not use PlatformObject_ChkYRange's absolute surface re-seat.
        return false;
    }

    @Override
    public boolean clearsStandingBitOnContinuedRideExit(PlayableEntity player) {
        // SolidObjectTop's out-of-bounds/airborne exit clears both
        // Status_OnObj and this object's d6 standing bit before returning
        // (sonic3k.asm:41798-41825). Keeping only the engine ride owner clear
        // leaves a hidden stale bit for Check_CollapsePlayerRelease.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // ROM: once the platform enters Obj_PlatformCollapseWaitHandlePlayer it stops
        // calling SolidObjectTop entirely. Only riders already standing on it remain
        // supported until Check_CollapsePlayerRelease drops them.
        if (state < 2) {
            return true;
        }
        if (state == 2) {
            return playerEntity != null && collapseWaveRiders.contains(playerEntity);
        }
        return false;
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!contact.standing()) {
            return;
        }

        switch (mode) {
            case STANDARD -> {
                // The engine resolves this SolidObjectTop contact before the
                // object's update. ROM loc_209A8 instead observes the standing
                // bit only after the countdown check and calls SolidObjectTop
                // at the end of the object slot. Defer arming $3A until this
                // tick's update so the first decrement begins on the same
                // following frame as the ROM.
                if (!collapseTriggered && !collapseTriggerPending) {
                    collapseTriggerPending = true;
                }
            }
            case TRIGGER -> {
                // Trigger mode: player standing does NOT trigger collapse.
                // ROM: loc_20AF6 only checks Level_trigger_array, never standing bits.
                // The bridge remains solid as a normal platform until the trigger fires
                // (e.g., killing a nearby badnik sets the trigger array entry).
            }
            case MGZ_STOMP -> {
                // MGZ resolves both native players before testing the combined
                // standing mask; update() consumes the manual checkpoint batch.
            }
        }
    }

    public boolean isMgzStompMode() {
        return mode == CollapseMode.MGZ_STOMP;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        // loc_209D0 calls SolidObjectTop for P1 and P2 before it inspects the
        // standing bits and replaces the parent with the first debris piece.
        // AUTO_AFTER_UPDATE publishes callbacks one player at a time, which
        // can shatter after P1 and incorrectly prevent P2's same-slot landing.
        return mode == CollapseMode.MGZ_STOMP
                ? SolidExecutionMode.MANUAL_CHECKPOINT
                : SolidExecutionMode.AUTO_AFTER_UPDATE;
    }

    // ===== Update =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (mode == CollapseMode.MGZ_STOMP && state == 0 && !fragmented) {
            SolidCheckpointBatch batch = checkpointAll();
            ArrayList<AbstractPlayableSprite> standingPlayers = new ArrayList<>();
            AbstractPlayableSprite stomper = null;
            for (PlayableEntity participant : collapseParticipants(player)) {
                PlayerSolidContactResult result = batch.perPlayer().get(participant);
                if (result != null && result.standingNow()
                        && participant instanceof AbstractPlayableSprite playable) {
                    standingPlayers.add(playable);
                    if (stomper == null && playable.isWallCling()) {
                        stomper = playable;
                    }
                }
            }
            if (stomper != null) {
                performMgzStomp(stomper);
                // loc_209FC clears both native standing bits and then clears
                // Status_OnObj for every player whose bit was set, not only
                // the wall-clinging player that selected the stomp branch.
                for (AbstractPlayableSprite standingPlayer : standingPlayers) {
                    clearMgzStompStandingState(standingPlayer);
                }
            }
        }

        switch (state) {
            case 0 -> { // Idle
                // Check trigger-based collapse (HCZ/ICZ)
                if (mode == CollapseMode.TRIGGER && triggerIndex >= 0
                        && Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
                    collapseTriggered = true;
                    // ROM: clr.b respawn_addr(a0) — prevent respawn after trigger collapse
                }

                if (collapseTriggerPending) {
                    collapseTriggerPending = false;
                    collapseTriggered = true;
                } else if (collapseTriggered && mode != CollapseMode.MGZ_STOMP) {
                    state = 1;
                }
            }
            case 1 -> { // Countdown
                collapseTimer--;
                if (collapseTimer <= 0) {
                    performCollapse(player);
                }
            }
            case 2 -> { // Wave collapse - track player release
                updateWaveCollapse(player);
            }
            case 3 -> { // Parent falling
                // Obj_PlatformCollapseFall consumes the render_flags sign bit
                // retained from the preceding Render_Sprites pass before it
                // calls MoveSprite (sonic3k.asm:45317-45326).
                if (!romRenderFlag) {
                    setDestroyedByOffscreen();
                    return;
                }

                // Standard collapse: MoveSprite with $38 gravity (Y only)
                // MGZ stomp: MoveSprite2 (no engine gravity) + manual $18 gravity
                // ROM: Obj_PlatformCollapseFall uses MoveSprite ($38)
                // ROM: loc_20A56 uses MoveSprite2 + addi.w #$18,y_vel
                int grav = (mode == CollapseMode.MGZ_STOMP) ? MGZ_GRAVITY : GRAVITY;

                // MoveSprite adds the old velocity to position, then applies
                // gravity for the following frame (sonic3k.asm:36032-36049).
                int y32 = (y << 16) | (yFrac & 0xFFFF);
                y32 += ((int) (short) velY) << 8;
                y = y32 >> 16;
                yFrac = y32 & 0xFFFF;
                velY = (short) (velY + grav);

                // MGZ stomp parent also moves in X (has initial velocity from BreakObjectToPieces)
                if (mode == CollapseMode.MGZ_STOMP) {
                    int x32 = (x << 16) | (xFrac & 0xFFFF);
                    x32 += ((int) (short) velX) << 8;
                    x = x32 >> 16;
                    xFrac = x32 & 0xFFFF;
                }

            }
        }
    }

    @Override
    public int getOnScreenHalfWidth() {
        return halfWidth;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return displayHeight;
    }

    @Override
    public void refreshPostCameraRenderState() {
        romRenderFlag = isWithinRenderSpriteBounds(halfWidth, displayHeight);
    }

    // ===== Collapse mechanics =====

    /**
     * Determines collapse direction and spawns wave-pattern fragments.
     * ROM: loc_2095E collapse branch → CollapsingPtfmHandlePlayerAndSmash
     */
    private void performCollapse(AbstractPlayableSprite player) {
        if (fragmented) {
            return;
        }

        seedCollapseWaveRiders(player);

        // Select delay array and determine direction
        activeDelays = delays;
        releaseDelays = delays;
        flippedForCollapse = false;

        AbstractPlayableSprite directionalPlayer = selectedDirectionalCollapsePlayer(player);
        if (directional && directionalPlayer != null) {
            // ROM: btst #7,subtype(a0) → directional collapse
            // Check which side the player is on
            int playerX = directionalPlayer.getCentreX();
            if (playerX < x) {
                // Player on left: use flip array, flip sprite, advance frame
                // ROM: load $34 array, eori.b #1,status(a0), addq.b #1,mapping_frame(a0)
                activeDelays = delaysFlip;
                flippedForCollapse = true;
                hFlip = !hFlip;
            }
        }

        // ROM: CollapsingPtfmHandlePlayerAndSmash increments mapping_frame
        // Fragment frame = initial + 1 (normal) or initial + 2 (flipped, since frame was
        // already incremented once above)
        fragmentFrameIndex = initialMappingFrame + (flippedForCollapse ? 2 : 1);

        spawnFragments();

        // ObjPlatformCollapse_SmashObject clears respawn_addr bit 7 after
        // converting the original SST slot into fragment zero. The live
        // fragment remains, but ObjPosLoad may now materialize a fresh bridge.
        // Trigger-mode bridges instead clear respawn_addr itself before the
        // smash so their placement bit deliberately remains set.
        if (mode != CollapseMode.TRIGGER) {
            releasePlacementForRespawn();
        }

        // Enter wave-collapse state
        state = 2;
        fragmented = true;
        parentTimer = releaseDelays.length > 0 ? releaseDelays[0] : 0;
    }

    /**
     * Spawns individual fragment children from the fragment mapping frame.
     * Each piece becomes a separate falling object with staggered delay.
     * ROM: ObjPlatformCollapse_SmashObject (sonic3k.asm:45400)
     */
    private void spawnFragments() {
        // Play collapse SFX
        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Audio failure must not break game logic
            }
        }

        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }
        ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
        if (sheet == null || fragmentFrameIndex >= sheet.getFrameCount()) {
            return;
        }

        SpriteMappingFrame frame = sheet.getFrame(fragmentFrameIndex);
        int pieceCount = frame.pieces().size();
        int maxFragments = Math.min(pieceCount, activeDelays.length);

        // ROM: First piece (index 0) stays with the parent for solid-stay rendering.
        // Children start from piece 1.
        for (int i = 1; i < maxFragments; i++) {
            int delay = activeDelays[i];
            BridgeFragment fragment = new BridgeFragment(
                    x, y, fragmentFrameIndex, i, delay, artKey, hFlip,
                    highPriorityArt, halfWidth, displayHeight);
            spawnDynamicObject(fragment);
        }
    }

    /**
     * Wave collapse update: track player position and release when the
     * collapse wave reaches their standing chunk.
     * ROM: Obj_PlatformCollapseWaitHandlePlayer + Check_CollapsePlayerRelease
     */
    private void updateWaveCollapse(AbstractPlayableSprite player) {
        parentTimer--;

        for (PlayableEntity rider : new ArrayList<>(collapseWaveRiders)) {
            if (rider instanceof AbstractPlayableSprite playable && shouldReleaseCollapseRider(playable)) {
                releaseCollapseRider(playable);
            }
        }

        // When parent timer fully expires, release any remaining players and fall
        if (parentTimer <= 0) {
            state = 3;
            for (PlayableEntity rider : new ArrayList<>(collapseWaveRiders)) {
                if (rider instanceof AbstractPlayableSprite playable) {
                    releaseCollapseRider(playable);
                }
            }
            collapseWaveRiders.clear();
        }
    }

    /**
     * MGZ ground-pound stomp: shatters the bridge into velocity-driven debris.
     * ROM: loc_209D0 + BreakObjectToPieces with word_20A76 velocity table
     */
    private void performMgzStomp(AbstractPlayableSprite player) {
        fragmented = true;
        collapseWaveRiders.clear();

        // loc_20A24 follows BreakObjectToPieces by clearing respawn_addr bit 7,
        // while the transformed parent and its debris continue to execute.
        releasePlacementForRespawn();

        // Release the player from the object without forcing InAir. loc_209FC
        // clears only Status_OnObj after SolidObjectTop has already landed the
        // player this frame; unlike Check_CollapsePlayerRelease, this stomp
        // path never sets Status_InAir (sonic3k.asm:45189-45216).
        player.setOnObject(false);
        try {
            if (services().objectManager() != null) {
                // The same loc_209FC block also clears the bridge's native
                // standing bit. Drop the engine ride owner now so the next
                // compatibility checkpoint does not reinterpret the already
                // completed stomp as a generic platform walk-off and set air.
                services().objectManager().clearRidingObject(player);
            }
        } catch (Exception e) {
            // Reflection-level tests may instantiate the bridge without services.
        }

        // Advance mapping frame for fragment pieces
        // ROM: addq.b #1,mapping_frame(a0)
        int stompFrameIndex = initialMappingFrame + 1;

        // Play collapse SFX
        // ROM: BreakObjectToPieces plays sfx_Collapse
        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Audio failure must not break game logic
            }
        }

        // Spawn debris children with velocity vectors
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager != null) {
            ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
            if (sheet != null && stompFrameIndex < sheet.getFrameCount()) {
                SpriteMappingFrame frame = sheet.getFrame(stompFrameIndex);
                int pieceCount = frame.pieces().size();
                int maxPieces = Math.min(pieceCount, MGZ_STOMP_VELOCITIES.length);
                // BreakObjectToPieces starts with a1=a0, so piece zero is the
                // bridge parent itself. Only later pieces consume newly
                // allocated slots (sonic3k.asm:45772-45811).
                for (int i = 1; i < maxPieces; i++) {
                    int xVel = MGZ_STOMP_VELOCITIES[i][0];
                    int yVel = MGZ_STOMP_VELOCITIES[i][1];
                    MgzStompDebris debris = new MgzStompDebris(
                            x, y, stompFrameIndex, i, xVel, yVel, artKey, hFlip);
                    spawnDynamicObject(debris);
                }
            }
        }

        // ROM: BreakObjectToPieces gives the parent the first velocity entry,
        // then loc_20A56 applies manual gravity of $18/frame.
        // Parent piece 0 gets velocity from MGZ_STOMP_VELOCITIES[0].
        if (MGZ_STOMP_VELOCITIES.length > 0) {
            this.velX = MGZ_STOMP_VELOCITIES[0][0];
            this.velY = MGZ_STOMP_VELOCITIES[0][1];
        }
        this.fragmentFrameIndex = stompFrameIndex;

        // Enter falling state directly
        state = 3;
    }

    private void releasePlacementForRespawn() {
        try {
            ObjectLifetimeOps.releaseSpawnForRespawn(services().objectManager(), this, getSpawn());
        } catch (Exception e) {
            // Focused reflection tests may instantiate the bridge without services.
        }
    }

    private void clearMgzStompStandingState(AbstractPlayableSprite player) {
        player.setOnObject(false);
        try {
            if (services().objectManager() != null) {
                services().objectManager().clearRidingObject(player);
            }
        } catch (Exception e) {
            // Reflection-level tests may instantiate the bridge without services.
        }
    }

    private boolean shouldTrackCollapseRider(AbstractPlayableSprite player) {
        try {
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                // The native collapse wave snapshots this bridge's own P1/P2
                // standing bits, not the player's global Status_OnObj bit. A
                // trigger-mode bridge may shatter while Sonic is standing on
                // an unrelated solid; treating that global bit as ownership
                // invents a rider and later writes prev_anim=1 on release.
                return objectManager.isRidingObject(player, this)
                        || objectManager.hasObjectStandingBit(player, this);
            }
        } catch (Exception e) {
            // Reflection-level tests may instantiate the bridge without services.
        }
        return player.isOnObject();
    }

    private void seedCollapseWaveRiders(AbstractPlayableSprite fallbackPlayer) {
        collapseWaveRiders.clear();
        for (PlayableEntity participant : collapseParticipants(fallbackPlayer)) {
            if (participant instanceof AbstractPlayableSprite playable
                    && shouldTrackCollapseRider(playable)) {
                collapseWaveRiders.add(playable);
            }
        }
    }

    private List<PlayableEntity> collapseParticipants(AbstractPlayableSprite fallbackPlayer) {
        ArrayList<PlayableEntity> participants = new ArrayList<>();
        try {
            participants.addAll(services().playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS));
        } catch (Exception e) {
            // Reflection-level tests may instantiate the object without full services.
        }
        if (fallbackPlayer != null && participants.stream().noneMatch(p -> p == fallbackPlayer)) {
            participants.add(fallbackPlayer);
        }
        return participants;
    }

    private AbstractPlayableSprite selectedDirectionalCollapsePlayer(AbstractPlayableSprite fallbackPlayer) {
        if (!directional) {
            return null;
        }
        try {
            List<PlayableEntity> nativePlayers = services().playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
            for (PlayableEntity participant : nativePlayers) {
                if (participant instanceof AbstractPlayableSprite playable
                        && services().objectManager() != null
                        && services().objectManager().hasObjectStandingBit(playable, this)) {
                    return playable;
                }
            }
        } catch (Exception e) {
            // Fall back below when services are unavailable.
        }
        return fallbackPlayer;
    }

    private boolean shouldReleaseCollapseRider(AbstractPlayableSprite player) {
        // ROM: Check_CollapsePlayerRelease
        int playerX = player.getCentreX();
        int relX = playerX - x + halfWidth;

        if (player.getAir()) {
            // Player jumped off
            return true;
        }
        if (relX < 0 || relX >= halfWidth * 2) {
            // Player walked off the edge
            return true;
        }

        // Mirror position based on current flip state (post-toggle).
        // ROM: btst #0,status(a0) -> neg.w d0, add.w d2,d0
        int adjustedRelX = hFlip ? (halfWidth * 2 - relX) : relX;

        // Convert to 16px chunk index.
        // ROM: lsr.w #4,d0
        int chunkIndex = adjustedRelX >> 4;
        if (chunkIndex >= releaseDelays.length) {
            chunkIndex = releaseDelays.length - 1;
        }
        if (chunkIndex < 0) {
            chunkIndex = 0;
        }

        // ROM: d2 = array[0] - array[chunkIndex]
        // Release when parentTimer <= d2
        int threshold = releaseDelays[0] - releaseDelays[chunkIndex];
        return parentTimer <= threshold;
    }

    private void releaseCollapseRider(AbstractPlayableSprite player) {
        collapseWaveRiders.remove(player);
        player.setAir(true);
        player.setOnObject(false);
        player.setPushing(false);
        // Check_CollapsePlayerRelease writes prev_anim=1 after clearing the
        // standing bits (sonic3k.asm:45349-45383). This intentionally restarts
        // an unchanged Roll animation on the following player slot.
        player.getAnimationManager().publishPreviousAnimationId(1);
        try {
            if (services().objectManager() != null) {
                services().objectManager().clearRidingObject(player);
            }
        } catch (Exception e) {
            // Keep release logic robust when called outside a fully-wired runtime.
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (fragmented) {
            // ROM: static mappings mode — parent renders piece 0 of fragment frame
            // during wave-collapse (state 2) and while falling (state 3).
            // ROM: Obj_PlatformCollapseFall calls Draw_Sprite; loc_20A56 calls Draw_Sprite.
            renderer.drawFramePieceByIndex(fragmentFrameIndex, 0, x, y, hFlip, false);
        } else {
            // Intact bridge
            renderer.drawFrameIndex(initialMappingFrame, x, y, hFlip, false);
        }
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
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // HCZ uses make_art_tile($001, 2, 1) — priority bit set.
        // This renders the bridge in front of high-priority FG tiles,
        // needed to blend correctly with HCZ's underwater overlays.
        return highPriorityArt;
    }

    // ===================================================================
    // Fragment inner class: wave-pattern collapse pieces
    // ===================================================================

    /**
     * Individual bridge fragment that waits for its delay, then falls with gravity.
     * Each fragment renders a single mapping piece from the parent's fragment frame.
     * ROM: Obj_PlatformCollapseWait → Obj_PlatformCollapseFall
     */
    public static class BridgeFragment extends AbstractFallingFragment implements RewindRecreatable {

        private int fragmentFrameIndex;
        private int pieceIndex;
        private String artKey;
        private boolean hFlip;
        private boolean highPriority;
        private int renderHalfWidth;
        private int renderHalfHeight;
        private boolean romRenderFlag = true;

        public BridgeFragment(int parentX, int parentY,
                              int fragmentFrameIndex, int pieceIndex,
                              int delay, String artKey, boolean hFlip,
                              boolean highPriority) {
            this(parentX, parentY, fragmentFrameIndex, pieceIndex, delay,
                    artKey, hFlip, highPriority, 0x40, 0x20);
        }

        public BridgeFragment(int parentX, int parentY,
                              int fragmentFrameIndex, int pieceIndex,
                              int delay, String artKey, boolean hFlip,
                              boolean highPriority, int renderHalfWidth,
                              int renderHalfHeight) {
            super(new ObjectSpawn(parentX, parentY, Sonic3kObjectIds.COLLAPSING_BRIDGE,
                    0, hFlip ? 1 : 0, false, 0), "BridgeFragment", delay, PRIORITY);
            this.fragmentFrameIndex = fragmentFrameIndex;
            this.pieceIndex = pieceIndex;
            this.artKey = artKey;
            this.hFlip = hFlip;
            this.highPriority = highPriority;
            this.renderHalfWidth = renderHalfWidth;
            this.renderHalfHeight = renderHalfHeight;
        }

        private BridgeFragment() {
            this(0, 0, 0, 0, 0, Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ, false, false);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            boolean hFlip = spawn != null && (spawn.renderFlags() & 0x01) != 0;
            int x = spawn != null ? spawn.x() : 0;
            int y = spawn != null ? spawn.y() : 0;
            return new BridgeFragment(
                    x, y, 0, 0, 0, Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ, hFlip, false);
        }

        @Override
        public boolean isHighPriority() {
            return highPriority;
        }

        @Override
        public int getOnScreenHalfWidth() {
            return renderHalfWidth;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return renderHalfHeight;
        }

        @Override
        protected boolean shouldDeleteBeforeFall() {
            // Obj_PlatformCollapseFall tests the previous Draw_Sprite result
            // before MoveSprite, exactly like the sibling collapsing-platform
            // fragment routine (sonic3k.asm:45317-45326).
            return !romRenderFlag;
        }

        @Override
        protected boolean shouldDeleteAfterFall() {
            return false;
        }

        @Override
        public void refreshPostCameraRenderState() {
            romRenderFlag = isWithinRenderSpriteBounds(renderHalfWidth, renderHalfHeight);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer == null) {
                return;
            }
            renderer.drawFramePieceByIndex(fragmentFrameIndex, pieceIndex, getX(), getY(), hFlip, false);
        }
    }

    // ===================================================================
    // MGZ stomp debris inner class: velocity-driven shatter pieces
    // ===================================================================

    /**
     * Debris fragment from the MGZ ground-pound break. Each piece receives
     * initial velocity and falls with gravity ($18/frame, lighter than standard $38).
     * ROM: loc_20A56 — MoveSprite2 + manual gravity $18/frame
     */
    public static class MgzStompDebris extends GravityDebrisChild implements RewindRecreatable {

        private int frameIndex;
        private int pieceIndex;
        private String artKey;
        private boolean hFlip;

        public MgzStompDebris(int parentX, int parentY,
                              int frameIndex, int pieceIndex,
                              int xVel, int yVel,
                              String artKey, boolean hFlip) {
            super(new ObjectSpawn(parentX, parentY, Sonic3kObjectIds.COLLAPSING_BRIDGE,
                    0, hFlip ? 1 : 0, false, 0),
                    "MgzBridgeDebris", xVel, yVel, MGZ_GRAVITY);
            this.frameIndex = frameIndex;
            this.pieceIndex = pieceIndex;
            this.artKey = artKey;
            this.hFlip = hFlip;
        }

        private MgzStompDebris() {
            this(0, 0, 0, 0, 0, 0, Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ, false);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            boolean hFlip = spawn != null && (spawn.renderFlags() & 0x01) != 0;
            int x = spawn != null ? spawn.x() : 0;
            int y = spawn != null ? spawn.y() : 0;
            return new MgzStompDebris(
                    x, y, 0, 0, 0, 0, Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ, hFlip);
        }

        @Override
        public int getPriorityBucket() {
            // ROM: move.w #$80,priority(a0)
            return RenderPriority.clamp(MGZ_STOMP_PRIORITY);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer == null) {
                return;
            }
            renderer.drawFramePieceByIndex(frameIndex, pieceIndex, getX(), getY(), hFlip, false);
        }
    }
}
