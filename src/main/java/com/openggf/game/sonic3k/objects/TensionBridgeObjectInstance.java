package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0x6C - Tension Bridge (HCZ, ICZ, LRZ).
 *
 * <p>Multi-segment bridge that sags under player weight using ROM-accurate
 * sine-based depression physics with lookup tables (identical math to S1's
 * GHZ bridge). Three variants selected by zone and subtype sign bit:
 *
 * <ul>
 *   <li>NORMAL: Sine sag toward player position (HCZ, LRZ, ICZ positive subtype)
 *   <li>ICZ_ROPE: Sine sag + 3px/segment staircase (ICZ negative subtype)
 *   <li>TRIGGER_COLLAPSE: Collapses when level trigger fires (non-ICZ negative subtype)
 * </ul>
 *
 * <p>ROM reference: Obj_TensionBridge (sonic3k.asm:75496+)
 */
public class TensionBridgeObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, RomObjectCodePointerProvider,
        SpawnRewindRecreatable {

    private enum Variant { NORMAL, TRIGGER_COLLAPSE, ICZ_ROPE }

    // --- Constants ---

    // From disasm: priority = $200 (bucket 4)
    private static final int PRIORITY = 4;
    private static final int SEGMENT_WIDTH = 16;
    private static final int SURFACE_OFFSET = 8; // subq.w #8,d0
    private static final int MAX_DEPRESSION_ANGLE = 0x40; // $40
    private static final int DEPRESSION_RATE = 4; // addq.b #4 / subq.b #4
    private static final int ROPE_STAIRCASE_STEP = 3; // addq.w #3,d6 per segment
    private static final int COLLAPSE_TIMER_INIT = 0x0E; // move.b #$E,$34(a0)
    private static final int FRAGMENT_GRAVITY = 0x38;
    private static final int ICZ_ANIM_WRAP = 12; // cmpi.b #$C

    // byte_38A78: staggered delays for collapse fragments
    private static final int[] FRAGMENT_DELAYS = {
            8, 0x10, 0x0C, 0x0E, 6, 0x0A, 4, 2,
            8, 0x10, 0x0C, 0x0E, 6, 0x0A, 4, 2
    };

    // byte_38E2A (full table including rows at byte_38E2A-$80):
    // Maximum depression depth per bridge length and player position.
    // 17 rows x 16 columns. Row = segment count (0-16), column = player segment index.
    // Identical to S1's ghzbend1.bin.
    // @formatter:off
    private static final int[][] MAX_DEPTH_TABLE = {
        { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00 },
        { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x10, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02 },
    };
    // @formatter:on

    // BridgeBendData: Per-segment weight curves (sine-like distribution).
    // 16 rows x 16 columns. Row = player segment index (left) or segmentsRight (right mirror).
    // Read forward for segments left of player, backward for segments right.
    // Identical to S1's ghzbend2.bin.
    // @formatter:off
    private static final int[][] BEND_CURVE_TABLE = {
        { 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0xB5, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x7E, 0xDB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x61, 0xB5, 0xEC, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x4A, 0x93, 0xCD, 0xF3, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x3E, 0x7E, 0xB0, 0xDB, 0xF6, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x38, 0x6D, 0x9D, 0xC5, 0xE4, 0xF8, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x31, 0x61, 0x8E, 0xB5, 0xD4, 0xEC, 0xFB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x2B, 0x56, 0x7E, 0xA2, 0xC1, 0xDB, 0xEE, 0xFB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x25, 0x4A, 0x73, 0x93, 0xB0, 0xCD, 0xE1, 0xF3, 0xFC, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x1F, 0x44, 0x67, 0x88, 0xA7, 0xBD, 0xD4, 0xE7, 0xF4, 0xFD, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0x1F, 0x3E, 0x5C, 0x7E, 0x98, 0xB0, 0xC9, 0xDB, 0xEA, 0xF6, 0xFD, 0xFF, 0x00, 0x00, 0x00, 0x00 },
        { 0x19, 0x38, 0x56, 0x73, 0x8E, 0xA7, 0xBD, 0xD1, 0xE1, 0xEE, 0xF8, 0xFE, 0xFF, 0x00, 0x00, 0x00 },
        { 0x19, 0x38, 0x50, 0x6D, 0x83, 0x9D, 0xB0, 0xC5, 0xD8, 0xE4, 0xF1, 0xF8, 0xFE, 0xFF, 0x00, 0x00 },
        { 0x19, 0x31, 0x4A, 0x67, 0x7E, 0x93, 0xA7, 0xBD, 0xCD, 0xDB, 0xE7, 0xF3, 0xF9, 0xFE, 0xFF, 0x00 },
        { 0x19, 0x31, 0x4A, 0x61, 0x78, 0x8E, 0xA2, 0xB5, 0xC5, 0xD4, 0xE1, 0xEC, 0xF4, 0xFB, 0xFE, 0xFF },
    };
    // @formatter:on

    // --- Instance state ---

    private boolean negativeSubtype;
    private int segmentCount;
    private int triggerIndex;
    private int baseY;

    // Lazily resolved (can't call services() in constructor)
    private Variant variant;
    private String artKey;
    private int[] segmentFrames; // ICZ per-segment animation, null for non-ICZ

    private int depressionAngle;    // $3E: 0 to MAX_DEPRESSION_ANGLE
    private int playerSegmentIndex; // $3F: which segment the player is on
    private int sidekickSegmentIndex; // $3B: which segment Player 2 is on
    private boolean playerOnBridge;
    private int[] segmentYOffsets;
    private byte[] slopeData;

    // Collapse state
    private boolean collapseActive;
    private boolean collapsed;
    private int collapseTimer;

    public TensionBridgeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "TensionBridge");
        int raw = spawn.subtype() & 0xFF;
        this.negativeSubtype = (raw & 0x80) != 0;
        // andi.b #$7F,subtype(a0) - clear sign bit for segment count
        int effective = raw & 0x7F;
        this.segmentCount = Math.max(1, Math.min(effective, 16));
        // Trigger index: andi.w #$F,d0 (low 4 bits of effective subtype)
        this.triggerIndex = effective & 0x0F;
        this.baseY = spawn.y(); // move.w y_pos(a0),$3C(a0)
        this.segmentYOffsets = new int[segmentCount];
        this.slopeData = new byte[segmentCount * (SEGMENT_WIDTH / 2) + 1];
    }

    /** Lazy variant resolution - cannot call services() during construction. */
    private Variant resolveVariant() {
        if (variant == null) {
            if (!negativeSubtype) {
                variant = Variant.NORMAL;
            } else if (resolveZoneId() == Sonic3kZoneIds.ZONE_ICZ) {
                variant = Variant.ICZ_ROPE;
            } else {
                variant = Variant.TRIGGER_COLLAPSE;
            }
            // ICZ bridges (any variant) get per-segment animation
            if (resolveZoneId() == Sonic3kZoneIds.ZONE_ICZ) {
                segmentFrames = new int[segmentCount];
            }
        }
        return variant;
    }

    // --- SlopedSolidProvider ---

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: d1 = segCount*8 + 8 (half-width origin shift), d3 = 8 (half-height)
        // Matching S1 bridge pattern: halfWidth=N*8, offsetX=-8, offsetY=-8
        int halfWidth = segmentCount * 8;
        return SolidObjectParams.of(halfWidth, 0, 0, -8, -SURFACE_OFFSET);
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj_TensionBridge init writes width_pixels(a0) = $80 (sonic3k.asm:75516).
        return 0x80;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean forceAirOnRideExit() {
        // sub_38AA2 loc_38AC2 clears Status_OnObj and the bridge's standing
        // bit, but deliberately leaves Status_InAir unchanged. This permits a
        // same-frame bridge-to-terrain handoff at the end of the slope.
        return false;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // The live routine at loc_387E0 is in ROM bank $0003. sub_13EFC
        // copies this word from the stood-on bridge SST into Tails_CPU_interact.
        return 0x0003;
    }

    @Override
    public boolean usesSlopeForNewLanding() {
        // sub_38AA2 sends a non-standing player through sub_1E410 with d3=8,
        // so first contact uses the flat bridge origin. Only an established
        // rider is re-seated from the bent child-segment Y table at loc_38AE8.
        return false;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // sub_38AA2 sends fresh contacts to sub_1E410. Its unsigned
        // cmpi.w #-$10,d0 / blo accepts only negative overlap [-$10,-1]
        // and rejects the exact d0=0 boundary (sonic3k.asm:75871-75946,
        // 41982-42068).
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        return slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    @Override
    public int getSlopeBaseline() {
        return 0;
    }

    // --- SolidObjectListener ---

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (player == null || contact == null || !contact.standing()) {
            return;
        }
        int segment = segmentIndexFor(player);
        if (services().playerQuery().nativeP2OrNull() == player) {
            // sub_38AA2 stores Player 2's current segment in $3B after the
            // bend calculation, for the following object dispatch.
            sidekickSegmentIndex = segment;
        } else {
            // The second sub_38AA2 call similarly publishes P1's $3F value.
            playerSegmentIndex = segment;
        }
    }

    // --- Priority & lifecycle ---

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        return resolveZoneId() == Sonic3kZoneIds.ZONE_LRZ;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !collapsed && !collapseActive;
    }

    @Override
    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        // loc_387B6 branches directly into loc_389C8 when the trigger fires,
        // and every loc_3890C countdown dispatch returns without calling
        // sub_38A88. The bridge therefore performs neither a slope re-seat nor
        // SolidObject's airborne-rider unseat until the countdown expires and
        // loc_38918 explicitly clears both players' standing state.
        return collapseActive || (resolveVariant() == Variant.TRIGGER_COLLAPSE
                && Sonic3kLevelTriggerManager.testAny(triggerIndex));
    }

    // --- Update ---

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        Variant v = resolveVariant();

        // Trigger-collapse variant: check trigger each frame (loc_387B6)
        if (v == Variant.TRIGGER_COLLAPSE && !collapseActive && !collapsed) {
            if (Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
                startCollapse(playerEntity);
                return;
            }
        }

        // Collapse countdown (loc_3890C)
        if (collapseActive) {
            if (collapseTimer > 0) {
                collapseTimer--;
            } else {
                releasePlayerAtCollapse();
                collapsed = true;
                setDestroyed(true);
            }
            return;
        }

        if (collapsed) return;

        // --- Depression angle update ---
        ObjectManager objectManager = services().objectManager();
        boolean wasPlayerOnBridge = playerOnBridge;
        playerOnBridge = playerEntity != null && objectManager != null
                && objectManager.isAnyPlayerRiding(this);
        int nextPlayerSegmentIndex = playerSegmentIndex;
        PlayableEntity nativeSidekick = services().playerQuery().nativeP2OrNull();
        boolean sidekickOnBridge = nativeSidekick != null
                && objectManager != null
                && objectManager.isRidingObject(nativeSidekick, this);

        if (playerOnBridge) {
            if (v == Variant.ICZ_ROPE) {
                // loc_38966 calls sub_38BD8 before adjusting the shared P1
                // anchor or calculating sub_38D74. Unlike the normal bridge's
                // sub_38A88 tail, the rope therefore publishes both riders'
                // current segments in this same dispatch before bending.
                nextPlayerSegmentIndex = segmentIndexFor(playerEntity);
                playerSegmentIndex = nextPlayerSegmentIndex;
                if (sidekickOnBridge) {
                    sidekickSegmentIndex = segmentIndexFor(nativeSidekick);
                }
            }
            if (sidekickOnBridge) {
                // loc_387F6 consumes P2's prior $3B and walks P1's $3F one
                // segment toward it before calculating the shared bend.
                if (playerSegmentIndex < sidekickSegmentIndex) {
                    playerSegmentIndex++;
                } else if (playerSegmentIndex > sidekickSegmentIndex) {
                    playerSegmentIndex--;
                }
                nextPlayerSegmentIndex = playerSegmentIndex;
            } else {
                nextPlayerSegmentIndex = segmentIndexFor(playerEntity);
                if (!wasPlayerOnBridge) {
                    // The preceding SolidObject pass has already populated ROM
                    // byte $3F before the first riding update.
                    playerSegmentIndex = nextPlayerSegmentIndex;
                }
            }

            // addq.b #4,$3E(a0); cmpi.b #$40,$3E(a0)
            if (depressionAngle < MAX_DEPRESSION_ANGLE) {
                depressionAngle = Math.min(MAX_DEPRESSION_ANGLE,
                        depressionAngle + DEPRESSION_RATE);
            }
        } else {
            // subq.b #4,$3E(a0)
            if (depressionAngle > 0) {
                depressionAngle = Math.max(0, depressionAngle - DEPRESSION_RATE);
            }
        }

        // Calculate bend (sub_38CC2 / sub_38D74)
        if (depressionAngle > 0) {
            calculateBend(v == Variant.ICZ_ROPE);
        } else {
            clearOffsets();
        }

        // ICZ per-segment animation (sub_38C12)
        if (segmentFrames != null) {
            updateIczAnimation(playerEntity);
        }

        // Update slope data for collision
        updateSlopeData();
        if (playerOnBridge && !sidekickOnBridge && v != Variant.ICZ_ROPE) {
            // ROM loc_387E0 bends from the prior $3F value, then sub_38A88
            // stores the player's current segment for the following dispatch.
            playerSegmentIndex = nextPlayerSegmentIndex;
        }
    }

    private int segmentIndexFor(PlayableEntity player) {
        int relX = player.getCentreX() - spawn.x() + segmentCount * 8 + 8;
        return Math.max(0, Math.min(segmentCount - 1, relX >> 4));
    }

    // --- Bend calculation (sub_38CC2 / sub_38D74) ---

    /**
     * ROM-accurate bridge bend using GetSineCosine + lookup tables.
     * Left-of-player segments read BEND_CURVE_TABLE forward,
     * right-of-player segments read backward (mirrored).
     *
     * @param ropeStaircase true for ICZ rope variant (+3px/segment)
     */
    private void calculateBend(boolean ropeStaircase) {
        // d4 = sin(depressionAngle)
        int sinValue = getSine(depressionAngle);

        // d5 = MAX_DEPTH_TABLE[segmentCount][playerSegmentIndex]
        int depthRow = Math.min(segmentCount, MAX_DEPTH_TABLE.length - 1);
        int depthCol = Math.min(playerSegmentIndex, 15);
        int maxDepth = MAX_DEPTH_TABLE[depthRow][depthCol];

        // Left side: segments 0..playerSegmentIndex, read BEND_CURVE_TABLE[playerIdx] forward
        int leftRow = Math.min(playerSegmentIndex, BEND_CURVE_TABLE.length - 1);
        for (int i = 0; i <= playerSegmentIndex && i < segmentCount; i++) {
            int weight = BEND_CURVE_TABLE[leftRow][i];
            // (weight+1) * maxDepth * sinValue >> 16
            long offset = ((long) (weight + 1) * maxDepth * sinValue) >> 16;
            segmentYOffsets[i] = (int) offset;
        }

        // Right side: segments playerSegmentIndex+1..segmentCount-1
        int segmentsRight = segmentCount - 1 - playerSegmentIndex;
        if (segmentsRight > 0) {
            int rightRow = Math.min(segmentsRight, BEND_CURVE_TABLE.length - 1);
            for (int j = 0; j < segmentsRight; j++) {
                // Read backward: BEND_CURVE_TABLE[segmentsRight][segmentsRight-1-j]
                int mirrorIdx = segmentsRight - 1 - j;
                int weight = BEND_CURVE_TABLE[rightRow][mirrorIdx];
                long offset = ((long) (weight + 1) * maxDepth * sinValue) >> 16;
                segmentYOffsets[playerSegmentIndex + 1 + j] = (int) offset;
            }
        }

        // ICZ rope staircase: addq.w #3,d6 per segment in sub_38D74
        if (ropeStaircase) {
            for (int i = 0; i < segmentCount; i++) {
                segmentYOffsets[i] += i * ROPE_STAIRCASE_STEP;
            }
        }
    }

    private void clearOffsets() {
        for (int i = 0; i < segmentCount; i++) {
            segmentYOffsets[i] = 0;
        }
        // ICZ rope: staircase persists even with no depression
        if (resolveVariant() == Variant.ICZ_ROPE) {
            for (int i = 0; i < segmentCount; i++) {
                segmentYOffsets[i] = i * ROPE_STAIRCASE_STEP;
            }
        }
    }

    /** ROM CalcSine for angles 0..$40. Returns 8.8 fixed-point (0..256). */
    private static int getSine(int angle) {
        if (angle <= 0) return 0;
        if (angle > MAX_DEPRESSION_ANGLE) angle = MAX_DEPRESSION_ANGLE;
        return TrigLookupTable.sinHex(angle);
    }

    // --- Slope data (for collision system) ---

    private void updateSlopeData() {
        int samplesPerSegment = SEGMENT_WIDTH / 2; // 8 samples per 16px segment
        for (int k = 0; k < slopeData.length; k++) {
            int segIdx = k / samplesPerSegment;
            if (segIdx >= segmentCount) segIdx = segmentCount - 1;
            // Negative: slope data represents "how much lower" subtracted from surface
            slopeData[k] = (byte) -segmentYOffsets[segIdx];
        }
    }

    // --- ICZ per-segment animation (sub_38C12) ---

    private void updateIczAnimation(PlayableEntity player) {
        // Start animation on player's segment when walking (x_vel != 0)
        if (playerOnBridge && player != null && player.getXSpeed() != 0) {
            if (segmentFrames[playerSegmentIndex] == 0) {
                segmentFrames[playerSegmentIndex] = 1; // move.b #1,(a1,d0.w)
            }
        }

        // Advance all animating segments
        for (int i = 0; i < segmentCount; i++) {
            if (segmentFrames[i] != 0) {
                segmentFrames[i]++;
                if (segmentFrames[i] >= ICZ_ANIM_WRAP) {
                    segmentFrames[i] = 0; // wrap to idle
                }
            }
        }
    }

    // --- Collapse (loc_389C8 / sub_389DE) ---

    private void startCollapse(PlayableEntity player) {
        collapseActive = true;
        collapseTimer = COLLAPSE_TIMER_INIT;

        // Remember the placement for delayed release/despawn.
        ObjectManager objectManager = null;
        try {
            objectManager = services().objectManager();
        } catch (Exception ignored) { }

        if (objectManager != null) {
            ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
        }

        // Spawn fragment for each segment
        spawnCollapseFragments();

        // sfx_BridgeCollapse
        try {
            services().playSfx(Sonic3kSfx.BRIDGE_COLLAPSE.id);
        } catch (Exception ignored) { }
    }

    private void spawnCollapseFragments() {
        int startX = spawn.x() - ((segmentCount >> 1) * SEGMENT_WIDTH);
        for (int i = 0; i < segmentCount; i++) {
            int fragX = startX + (i * SEGMENT_WIDTH);
            int fragY = baseY + segmentYOffsets[i];
            int frame = (segmentFrames != null) ? segmentFrames[i] : 0;
            int delay = FRAGMENT_DELAYS[i % FRAGMENT_DELAYS.length];
            String fragArtKey = resolveArtKey();
            boolean highPri = isHighPriority();
            spawnChild(() -> new BridgeFragment(fragX, fragY, frame, delay,
                    fragArtKey, highPri));
        }
    }

    private void releasePlayerAtCollapse() {
        ObjectManager objectManager = services().objectManager();
        List<PlayableEntity> participants = services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);

        // loc_38918/loc_3892C independently clear the P1 and P2 standing bits
        // and set Status_InAir. Query the live riding table here so both native
        // players (and any configured extra sidekicks using the same bridge)
        // receive that release instead of remembering only the update argument.
        for (PlayableEntity participant : participants) {
            if (!objectManager.isRidingObject(participant, this)) {
                continue;
            }
            participant.setOnObject(false);
            participant.setPushing(false);
            participant.setAir(true);
            objectManager.clearRidingObject(participant);
        }
    }

    // --- Rendering ---

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (collapsed || collapseActive) return;

        PatternSpriteRenderer renderer = getRenderer(resolveArtKey());
        if (renderer == null || !renderer.isReady()) return;

        // Segment X positions: bridgeX - (segCount/2)*16, +16 per segment
        int startX = spawn.x() - ((segmentCount >> 1) * SEGMENT_WIDTH);
        for (int i = 0; i < segmentCount; i++) {
            int x = startX + (i * SEGMENT_WIDTH);
            int y = baseY + segmentYOffsets[i];
            int frame = (segmentFrames != null) ? segmentFrames[i] : 0;
            renderer.drawFrameIndex(frame, x, y, false, false);
        }
    }

    // --- Helpers ---

    private String resolveArtKey() {
        if (artKey != null) return artKey;
        artKey = switch (resolveZoneId()) {
            case Sonic3kZoneIds.ZONE_ICZ -> Sonic3kObjectArtKeys.TENSION_BRIDGE_ICZ;
            case Sonic3kZoneIds.ZONE_LRZ -> Sonic3kObjectArtKeys.TENSION_BRIDGE_LRZ;
            default -> Sonic3kObjectArtKeys.TENSION_BRIDGE_HCZ;
        };
        return artKey;
    }

    private int resolveZoneId() {
        try {
            return services().romZoneId();
        } catch (Exception e) {
            return Sonic3kZoneIds.ZONE_HCZ;
        }
    }

    // --- Collapse fragment (loc_388E4) ---

    /**
     * Individual bridge segment that falls with gravity after a staggered delay.
     * ROM: no initial velocity, just MoveSprite gravity.
     */
    private static final class BridgeFragment extends GravityDebrisChild implements RewindRecreatable {
        private int frameIndex;
        private int delay;
        private String artKey;
        private boolean highPri;

        private BridgeFragment(int x, int y, int frameIndex, int delay,
                               String artKey, boolean highPri) {
            super(new ObjectSpawn(x, y, Sonic3kObjectIds.TENSION_BRIDGE, 0, 0, false, 0),
                    "TensionBridgeFragment", 0, 0, FRAGMENT_GRAVITY);
            this.frameIndex = frameIndex;
            this.delay = delay;
            this.artKey = artKey;
            this.highPri = highPri;
        }

        private BridgeFragment() {
            this(0, 0, 0, 0, Sonic3kObjectArtKeys.TENSION_BRIDGE_HCZ, false);
        }

        @Override
        public BridgeFragment recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn capturedSpawn = ctx.spawn();
            int x = capturedSpawn != null ? capturedSpawn.x() : 0;
            int y = capturedSpawn != null ? capturedSpawn.y() : 0;
            return new BridgeFragment(
                    x,
                    y,
                    0,
                    0,
                    Sonic3kObjectArtKeys.TENSION_BRIDGE_HCZ,
                    false);
        }

        @Override
        public boolean isHighPriority() {
            return highPri;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // loc_388E4: delay countdown, then fall
            if (delay > 0) {
                delay--;
                return;
            }
            super.update(vIntRunCount, player);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(frameIndex, motionState.x, motionState.y,
                        false, false);
            }
        }
    }
}
