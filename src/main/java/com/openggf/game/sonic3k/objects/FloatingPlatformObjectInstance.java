package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlatformBobHelper;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x51 - Floating Platform (Sonic 3 &amp; Knuckles).
 * <p>
 * A top-solid moving platform used in AIZ, HCZ, and MGZ. Supports 13 movement
 * patterns selected by subtype bits [3:0], with platform size selected by bits [5:4].
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>Bits [5:4]: size index (0-2) into byte_254FA size table</li>
 *   <li>Bits [3:0]: movement type (0-12) from FloatingPlatformIndex</li>
 * </ul>
 * <p>
 * Movement types:
 * <ul>
 *   <li>0: Stationary - gentle sine-bob when player stands</li>
 *   <li>1: Horizontal64 - oscillation range 64px</li>
 *   <li>2: Horizontal128 - oscillation range 128px</li>
 *   <li>3: Vertical64 - oscillation range 64px</li>
 *   <li>4: Vertical128 - oscillation range 128px</li>
 *   <li>5: DiagonalUp - horizontal 128px + half vertical (up-right)</li>
 *   <li>6: DiagonalDown - inverted horizontal + half vertical (down-right)</li>
 *   <li>7: Rising - weight-triggered rise with ceiling check and crush kill</li>
 *   <li>8-11: Square32/96/160/224 - square orbit state machines</li>
 *   <li>12: Horizontal256 - slow accumulating sweep</li>
 * </ul>
 * <p>
 * ROM references: Obj_FloatingPlatform (sonic3k.asm line 50758), byte_254FA,
 * FloatingPlatformIndex (line 50174), Platform_Stationary (line 50190),
 * sub_24FDE (line 50229), Platform_Rising (line 50462), loc_252B8 (line 50556).
 */
public class FloatingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider,
        RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(FloatingPlatformObjectInstance.class.getName());

    // Priority: $180 = bucket 3 (ROM: move.w #$180,priority(a0))
    private static final int PRIORITY = 3;

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_FloatingPlatform dispatches through $000255F4.
        return 0x0002;
    }

    // ===== Size table (byte_254FA): 3-byte entries =====
    // Each entry: halfWidth, halfHeight, mappingFrame
    private static final int[][] SIZE_TABLE = {
            {0x20, 0x20, 0},  // Index 0: 32x32, frame 0
            {0x18, 0x0C, 0},  // Index 1: 24x12, frame 0
            {0x20, 0x14, 0},  // Index 2: 32x20, frame 0
    };

    // ===== Square path oscillation config per type =====
    // Each entry: {oscValueOffset, oscDeltaOffset, halfAmplitude, halveValue}
    // OscMgr offsets = ROM Oscillating_table offsets - 2 (control word)
    private static final int[][] SQUARE_OSC_CONFIG = {
            {0x28, 0x2A, 32 / 2, 1},  // Type 8:  Square32  — lsr.w #1,d0 (halve)
            {0x2C, 0x2E, 96 / 2, 0},  // Type 9:  Square96
            {0x30, 0x32, 160 / 2, 0}, // Type 10: Square160
            {0x34, 0x36, 224 / 2, 0}, // Type 11: Square224
    };

    // Rising platform y_radius on activation (ROM: move.b #$C,y_radius(a0))
    private static final int RISING_Y_RADIUS = 0x0C;
    // Rising target offset above spawn (ROM: subi.w #$80,d0)
    private static final int RISING_TARGET_OFFSET = 0x80;
    // Rising acceleration per frame (ROM: moveq #8,d1)
    private static final int RISING_ACCEL = 8;
    // Horizontal256 direction switch bound (ROM: move.w #$7F,d2)
    private static final int SWEEP_BOUND = 0x7F;

    // ===== Zone-specific configuration =====

    /** Per-zone configuration record. */
    private record ZoneConfig(String artKey, int artTileBase) {}

    private static final ZoneConfig AIZ1_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.FLOATING_PLATFORM_AIZ1, 0x03F7);

    private static final ZoneConfig AIZ2_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.FLOATING_PLATFORM_AIZ2, 0x0440);

    private static final ZoneConfig HCZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.FLOATING_PLATFORM_HCZ, 0x041D);

    private static final ZoneConfig MGZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.FLOATING_PLATFORM_MGZ, 0x0001);

    // ===== Instance state =====

    private final ZoneConfig config;
    private int halfWidth;
    private int halfHeight;
    private int mappingFrame;
    private int moveType;
    private boolean xFlip;

    private int x;
    private int y;
    private int baseX;  // objoff_30: saved X position
    private int baseY;  // objoff_34: saved Y position
    private int outOfRangeReferenceX; // objoff_44: saved deletion anchor
    private int outOfRangeLimit;      // objoff_42: deletion range
    // Stationary bob state (type 0) — sine-based vertical nudge when player stands
    private final PlatformBobHelper bobHelper = new PlatformBobHelper();

    // Rising state (type 7) — uses SubpixelMotion for ROM-accurate 16:8 movement
    private final SubpixelMotion.State risingState;
    private boolean rising; // $3C(a0): activated when player stands

    // Square path state (types 8-11)
    // $2E(a0) & 3: current quadrant (0-3)
    private int squareQuadrant;

    // Horizontal256 state (type 12)
    // $40(a0): velocity accumulator, $36(a0): position accumulator (word)
    // $3C(a0): direction flag (0 = accelerating positive, 1 = decelerating)
    private int sweepVelocity;
    private int sweepPosition;
    private int sweepDirection;

    public FloatingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "FloatingPlatform");

        // Extract size index from subtype bits [6:4]
        // ROM: lsr.w #2,d0; andi.w #$1C,d0 → extracts bits [6:4] as 4-byte offset
        int sizeIndex = (spawn.subtype() >> 4) & 0x07;
        if (sizeIndex >= SIZE_TABLE.length) {
            sizeIndex = SIZE_TABLE.length - 1;
        }

        this.halfWidth = SIZE_TABLE[sizeIndex][0];
        this.halfHeight = SIZE_TABLE[sizeIndex][1];
        this.mappingFrame = SIZE_TABLE[sizeIndex][2];

        // Movement type from bits [3:0]
        this.moveType = spawn.subtype() & 0x0F;

        // X-flip from render flags (status bit 0)
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;

        // Store base positions for oscillation reference
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.x = baseX;
        this.y = baseY;
        // ROM Obj_FloatingPlatform saves x_pos to $44 and checks that anchor
        // at loc_25628, not the moving platform's live x_pos. Types 12+ widen
        // the delete range and bias $44 by +$100 (sonic3k.asm:50810-50835).
        this.outOfRangeReferenceX = moveType >= 12 ? baseX + 0x100 : baseX;
        this.outOfRangeLimit = moveType >= 12 ? 0x380 : 0x280;

        // Initialize SubpixelMotion state for Rising platform (type 7)
        this.risingState = new SubpixelMotion.State(baseX, baseY, 0, 0, 0, 0);

        // Square path quadrant initialization (sonic3k.asm lines 50796, 50799-50812)
        // ROM: move.b status(a0),$2E(a0) — copies render flags to quadrant field
        // Then for types 8-11: checks osc value word; if negative, flips bit 0
        if (moveType >= 8 && moveType <= 11) {
            squareQuadrant = spawn.renderFlags() & 0x03;
            // ROM: lea (Oscillating_table+$2C).w,a2; lea (a2,d0.w),a2; tst.w (a2); bpl.s skip
            // Checks the DELTA word of the same oscillator used for movement (osc 10-13)
            int deltaOffset = SQUARE_OSC_CONFIG[moveType - 8][1];
            if (OscillationManager.getWord(deltaOffset) < 0) {
                squareQuadrant ^= 1; // bchg #0,$2E(a0)
            }
        }

        // Resolve zone-specific art config
        this.config = resolveConfig();

        updateDynamicSpawn(x, y);
    }

    @Override
    public FloatingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new FloatingPlatformObjectInstance(ctx.spawn()));
    }

    // ===== SolidObjectProvider =====

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: move.b height_pixels(a0),d3; addq.w #1,d3 (sonic3k.asm:50839-50840)
        // height_pixels is never modified — only y_radius changes for ObjCheckCeilingDist
        return SolidObjectParams.of(halfWidth, halfHeight, halfHeight + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // Obj_FloatingPlatform increments d3 after loading height_pixels, then
        // passes that height+1 surface to SolidObjectTop.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // SolidObjectTop reaches loc_1E45A, where the unsigned comparison
        // accepts only the negative overlap band [-$10,-1]. Exact d0=0
        // returns without installing a ride.
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        // The native caller uses SolidObjectTop's relative y_pos += d0 + 3
        // result. It does not run PlatformObject_ChkYRange's absolute snap.
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // Obj_FloatingPlatform tests the object render_flags sign bit before
        // calling SolidObjectTop (loc_255F4). Unlike SolidObjectTop itself,
        // this caller-owned gate suppresses both fresh and continued contact.
        return !isDestroyed() && isWithinSolidContactBounds();
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform state is driven via ObjectManager standing checks.
    }

    // ===== ObjectInstance =====

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return halfWidth;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return halfHeight;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return outOfRangeReferenceX;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return outOfRangeLimit != 0x280;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        int objRounded = outOfRangeReferenceX & 0xFF80;
        int cameraBack = (cameraX - 0x80) & 0xFF80;
        int distance = (objRounded - cameraBack) & 0xFFFF;
        return distance > outOfRangeLimit;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        applyMovement(player);
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (config == null || config.artKey == null) {
            return;
        }

        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, x, y, xFlip, false);
    }

    // ===== Movement dispatch =====

    /**
     * Applies movement based on move type (subtype &amp; 0x0F).
     * Ported from FloatingPlatformIndex (sonic3k.asm line 50174).
     */
    private void applyMovement(AbstractPlayableSprite player) {
        switch (moveType) {
            case 0 -> applyStationaryBob();
            case 1 -> applyHorizontalOscillation(0x08, 0x40);   // Osc[2], amplitude 64
            case 2 -> applyHorizontalOscillation(0x1C, 0x80);   // Osc[7], amplitude 128
            case 3 -> applyVerticalOscillation(0x08, 0x40);      // Osc[2], amplitude 64
            case 4 -> applyVerticalOscillation(0x1C, 0x80);      // Osc[7], amplitude 128
            case 5 -> applyDiagonalUp();
            case 6 -> applyDiagonalDown();
            case 7 -> applyRising(player);
            case 8, 9, 10, 11 -> applySquarePath(moveType - 8);
            case 12 -> applyHorizontal256();
            default -> { /* Unknown type - stationary */ }
        }
    }

    // ===== Movement type 0: Stationary bob =====

    /**
     * Platform_Stationary (sonic3k.asm lines 50190-50211).
     * <p>
     * Gentle sine-bob in Y when player stands on the platform.
     * Angle increments by +4/frame while standing, decrements by -4 while not,
     * clamped to [0, 0x40]. Displacement = GetSineCosine(angle) >> 6 (max ~4px).
     */
    private void applyStationaryBob() {
        bobHelper.update(isPlayerRiding());
        y = baseY + bobHelper.getOffset();
    }

    // ===== Movement types 1-2: Horizontal oscillation =====

    /**
     * Shared horizontal oscillation subroutine (sub_24FDE, sonic3k.asm line 50229).
     * <p>
     * ROM: reads oscillation byte, applies xFlip polarity, adds to baseX.
     */
    private void applyHorizontalOscillation(int oscOffset, int amplitude) {
        x = baseX + applyOscFlip(OscillationManager.getByte(oscOffset), amplitude);
    }

    // ===== Movement types 3-4: Vertical oscillation =====

    /**
     * Vertical oscillation (sonic3k.asm lines 50244-50266, loc_2500C).
     * Same structure as horizontal but applied to Y with subtraction.
     */
    private void applyVerticalOscillation(int oscOffset, int amplitude) {
        y = baseY - applyOscFlip(OscillationManager.getByte(oscOffset), amplitude);
    }

    // ===== Movement type 5: Diagonal up =====

    /**
     * DiagonalUp movement (sonic3k.asm lines 50269-50278).
     * <p>
     * Calls sub_24FDE for X (osc $1E, amplitude 128), then halves the raw osc byte
     * with lsr.b #1 and applies to Y via loc_2500C (amplitude 64).
     */
    private void applyDiagonalUp() {
        int oscByte = OscillationManager.getByte(0x1C);
        x = baseX + applyOscFlip(oscByte, 0x80);
        y = baseY - applyOscFlip((oscByte >> 1) & 0xFF, 0x40);
    }

    // ===== Movement type 6: Diagonal down =====

    /**
     * DiagonalDown movement (sonic3k.asm lines 50281-50292).
     * <p>
     * Negates X oscillation byte before passing to sub_24FDE (inverts X phase).
     * Y component is identical to DiagonalUp.
     */
    private void applyDiagonalDown() {
        int oscByte = OscillationManager.getByte(0x1C);
        // X: pre-negate before sub_24FDE (neg.w d0; add.w d1,d0)
        x = baseX + applyOscFlip(-oscByte + 0x80, 0x80);
        y = baseY - applyOscFlip((oscByte >> 1) & 0xFF, 0x40);
    }

    // ===== Movement type 7: Rising =====

    /**
     * Weight-triggered rising platform (sonic3k.asm lines 50462-50522).
     * <p>
     * When player stands, activates rising. Uses MoveSprite2 for subpixel movement.
     * Accelerates upward (-8/frame) only when below target (baseY - 0x80).
     * Checks ceiling via ObjCheckCeilingDist — resets state on ceiling hit.
     * Checks player headroom via sub_F846 — crush kills if headroom is negative.
     */
    private void applyRising(AbstractPlayableSprite player) {
        if (!rising) {
            if (isPlayerRiding()) {
                rising = true;
                // ROM Platform_Rising sets $3C/y_radius and returns; MoveSprite2
                // starts on the next object update (sonic3k.asm locret_25200).
                return;
            } else {
                return;
            }
        }

        // ROM MoveSprite2 updates the 32-bit object position with velocity << 8
        // (sonic3k.asm:36053-36062), preserving the full 16-bit fractional word.
        SubpixelMotion.speedToPos(risingState);
        y = risingState.y;

        // Accelerate upward only when below target (sonic3k.asm:50477-50483)
        // ROM: cmp.w y_pos(a0),d0; bhs.s — unsigned 16-bit comparison
        int target = baseY - RISING_TARGET_OFFSET;
        if (Integer.compareUnsigned(y & 0xFFFF, target & 0xFFFF) > 0) {
            risingState.yVel -= RISING_ACCEL;
        }

        // ObjCheckCeilingDist (sonic3k.asm:50486-50492): ceiling collision resets state
        try {
            TerrainCheckResult ceilingResult = ObjectTerrainUtils.checkCeilingDist(
                    x, y, RISING_Y_RADIUS);
            if (ceilingResult.foundSurface() && ceilingResult.distance() < 0) {
                // ROM: sub.w d1,y_pos(a0); clr.b $3C(a0); clr.w y_vel(a0)
                // ROM does NOT clear y_sub — only adjusts y_pos and resets velocity
                y -= ceilingResult.distance();
                risingState.y = y;
                risingState.yVel = 0;
                rising = false;
                return;
            }
        } catch (Exception e) {
            // Safe fallback if terrain check unavailable (test env)
        }

        // Crush detection (sonic3k.asm:50496-50521): kill standing player if headroom < 0
        if (player != null && isPlayerRiding()) {
            try {
                TerrainCheckResult headroom = ObjectTerrainUtils.checkCeilingDist(
                        player.getCentreX(), player.getCentreY(), player.getYRadius());
                if (headroom.foundSurface() && headroom.distance() < 0) {
                    player.applyCrushDeath();
                }
            } catch (Exception e) {
                // Safe fallback
            }
        }
    }

    // ===== Movement types 8-11: Square paths =====

    /**
     * Square orbit path (sonic3k.asm lines 50525-50610, loc_252B8).
     * <p>
     * Uses a 4-quadrant state machine that advances when the oscillation delta
     * word is zero. Each quadrant positions the platform along one edge of a
     * square centered on the spawn point with radius = halfAmp.
     * <p>
     * ROM quadrant geometry (d0 = oscValue, d1 = halfAmp):
     * <ul>
     *   <li>Q0: x = baseX + d0 - d1, y = baseY - d1</li>
     *   <li>Q1: x = baseX + d1, y = baseY + (d1-1) - d0</li>
     *   <li>Q2: x = baseX + (d1-1) - d0, y = baseY + d1</li>
     *   <li>Q3: x = baseX - d1, y = baseY + d0 - d1</li>
     * </ul>
     *
     * @param configIndex index into SQUARE_OSC_CONFIG (0-3 for types 8-11)
     */
    private void applySquarePath(int configIndex) {
        if (configIndex < 0 || configIndex >= SQUARE_OSC_CONFIG.length) {
            return;
        }

        int oscValueOffset = SQUARE_OSC_CONFIG[configIndex][0];
        int oscDeltaOffset = SQUARE_OSC_CONFIG[configIndex][1];
        int halfAmp = SQUARE_OSC_CONFIG[configIndex][2];
        boolean halve = SQUARE_OSC_CONFIG[configIndex][3] != 0;

        int oscValue = OscillationManager.getByte(oscValueOffset);
        int oscDelta = OscillationManager.getWord(oscDeltaOffset);

        // Square32 halves the osc value (ROM: lsr.w #1,d0 at line 50529)
        if (halve) {
            oscValue >>= 1;
        }

        // Advance quadrant when delta == 0 (ROM: tst.w d3 / bne.s)
        if (oscDelta == 0) {
            squareQuadrant = (squareQuadrant + 1) & 0x03;
        }

        switch (squareQuadrant) {
            case 0 -> {
                x = baseX + oscValue - halfAmp;
                y = baseY - halfAmp;
            }
            case 1 -> {
                x = baseX + halfAmp;
                y = baseY + (halfAmp - 1) - oscValue;
            }
            case 2 -> {
                x = baseX + (halfAmp - 1) - oscValue;
                y = baseY + halfAmp;
            }
            case 3 -> {
                x = baseX - halfAmp;
                y = baseY + oscValue - halfAmp;
            }
        }
    }

    // ===== Movement type 12: Horizontal256 =====

    /**
     * Slow accumulating horizontal sweep (sonic3k.asm lines 50336-50370).
     * <p>
     * Uses a velocity accumulator ($40) and position accumulator ($36) to create
     * a slow, smooth back-and-forth sweep. Direction flag ($3C) controls acceleration.
     * <p>
     * ROM bounds check compares HIGH BYTE of position word with $7F:
     * direction 0→1 when posHigh >= $7F, direction 1→0 when posHigh &lt; $7F.
     */
    private void applyHorizontal256() {
        int velDelta = (sweepDirection == 0) ? 4 : -4;
        sweepVelocity = (sweepVelocity + velDelta) & 0xFFFF;
        sweepPosition = (sweepPosition + (short) sweepVelocity) & 0xFFFF;

        int posHigh = (sweepPosition >> 8) & 0xFF;
        if (sweepDirection == 0 && posHigh >= SWEEP_BOUND) {
            sweepDirection = 1;
        } else if (sweepDirection == 1 && posHigh < SWEEP_BOUND) {
            sweepDirection = 0;
        }

        x = baseX + applyOscFlip(posHigh, SWEEP_BOUND);
    }

    // ===== Helpers =====

    /**
     * Applies xFlip phase inversion to an oscillation value.
     * ROM pattern: btst #0,status(a0) / neg.w d0 / add.w d1,d0.
     * Uses signed word arithmetic (no byte masking) to match ROM's neg.w + add.w.
     */
    private int applyOscFlip(int oscValue, int amplitude) {
        return xFlip ? -oscValue + amplitude : oscValue;
    }

    // Uses inherited getRenderManager() from AbstractObjectInstance

    private ZoneConfig resolveConfig() {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            if (zone == Sonic3kZoneIds.ZONE_AIZ) {
                return act == 0 ? AIZ1_CONFIG : AIZ2_CONFIG;
            }
            if (zone == Sonic3kZoneIds.ZONE_HCZ) {
                return HCZ_CONFIG;
            }
            if (zone == Sonic3kZoneIds.ZONE_MGZ) {
                return MGZ_CONFIG;
            }
            LOG.warning("FloatingPlatform: unknown zone 0x"
                    + Integer.toHexString(zone) + ", defaulting to AIZ1 config");
        } catch (Exception e) {
            LOG.fine("Could not resolve zone config: " + e.getMessage());
        }
        return AIZ1_CONFIG;
    }

    // ===== Debug rendering =====

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(right, top, right, bottom, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(right, bottom, left, bottom, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(left, bottom, left, top, 0.2f, 0.8f, 0.5f);
    }

}
