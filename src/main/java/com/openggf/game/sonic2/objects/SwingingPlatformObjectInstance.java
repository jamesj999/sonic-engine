package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Object 0x15 - SwingingPlatform from OOZ, ARZ, MCZ.
 * <p>
 * A platform that swings from a pivot point, connected by chain links.
 * Uses global oscillation data to drive the swing motion.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 22408-22950 (Obj15 code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 0-3 (0x0F): Number of chain links (1-15; ROM masks low nybble with no upper clamp)</li>
 *   <li>Bits 4-6 (0x70): Behavior mode</li>
 *   <li>Bit 7 (0x80): Display-only mode (no chain creation)</li>
 * </ul>
 * <p>
 * <b>Behavior modes (bits 4-6):</b>
 * <ul>
 *   <li>0x00: Normal swinging (uses oscillation data offset 0x18)</li>
 *   <li>0x10: Bounce Left - triggers swing on player proximity</li>
 *   <li>0x20: Static platform - no swing effect</li>
 *   <li>0x30: Bounce Right - mirror of bounce left</li>
 *   <li>0x40: MCZ Trap - pressure plate with rotation</li>
 * </ul>
 * <p>
 * <b>Zone-specific configuration:</b>
 * <ul>
 *   <li>OOZ: Uses dedicated Nemesis art (ArtNem_OOZSwingPlat), palette 2, width=0x20, yRadius=0x10</li>
 *   <li>MCZ: Uses level art (ArtKos_LevelArt), palette 0, width=0x18, yRadius=0x08</li>
 *   <li>ARZ: Uses level art (ArtKos_LevelArt), palette 0, width=0x20, yRadius=0x08</li>
 * </ul>
 */
public class SwingingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(SwingingPlatformObjectInstance.class.getName());

    // Zone configuration enum
    private enum ZoneConfig {
        OOZ(0x20, 0x10, 2),   // OOZ: width=32, yRadius=16, palette=2
        MCZ(0x18, 0x08, 0),   // MCZ: width=24, yRadius=8, palette=0
        ARZ(0x20, 0x08, 0);   // ARZ: width=32, yRadius=8, palette=0

        final int widthPixels;
        final int yRadius;
        final int paletteIndex;

        ZoneConfig(int widthPixels, int yRadius, int paletteIndex) {
            this.widthPixels = widthPixels;
            this.yRadius = yRadius;
            this.paletteIndex = paletteIndex;
        }
    }

    // Behavior mode enum
    private enum BehaviorMode {
        NORMAL,         // 0x00: Normal swinging
        BOUNCE_LEFT,    // 0x10: Triggers swing on player proximity (left)
        STATIC,         // 0x20: Static platform (no swing)
        BOUNCE_RIGHT,   // 0x30: Triggers swing on player proximity (right)
        TRAP            // 0x40: MCZ trap with rotation
    }

    // Trap mode constants
    private static final int TRAP_COOLDOWN = 60;       // Frames to wait after rotation
    private static final int TRAP_ROTATION_STEP = 8;   // Angle change per frame
    private static final int TRAP_ROTATION_MAX = 0x200; // Maximum rotation accumulator
    private static final int DISPLAY_CHILD_ANCHOR_LENGTH = 0x40;
    private static final int PLATFORM_FRAME_NORMAL = 0;
    private static final int PLATFORM_FRAME_TRIGGERED_PARENT = 3;
    private static final int BIT7_STATE_NORMAL = 0;
    private static final int BIT7_STATE_ARMED = 1;
    private static final int BIT7_STATE_TRIGGERED_PARENT = 2;
    private static final int BIT7_STATE_FALLING_CHILD = 3;
    private static final int BIT7_STATE_BOBBING_CHILD = 4;
    private static final int STATE6_CHILD_X_SPEED = 0x200;
    private static final int STATE6_CHILD_GRAVITY = 0x18;
    private static final int STATE6_CHILD_LANDING_Y = 0x720;

    // Static mapping data (loaded per-zone)
    private static final LazyMappingHolder OOZ_MAPPINGS = new LazyMappingHolder();
    private static final LazyMappingHolder MCZ_MAPPINGS = new LazyMappingHolder();
    private static final LazyMappingHolder ARZ_MAPPINGS = new LazyMappingHolder();
    private static final LazyMappingHolder TRAP_MAPPINGS = new LazyMappingHolder();

    // Position state
    private int baseX;
    private int baseY;
    private int x;
    private int y;

    // Configuration
    private ZoneConfig zoneConfig;
    private BehaviorMode behaviorMode;
    private int chainCount;
    private boolean displayOnly;

    // Chain link positions
    private final int[] chainX;
    private final int[] chainY;
    private int displayChildX;
    private int displayChildY;
    private SwingingPlatformDisplayChild displayChild;

    // Trap mode state
    private int trapCooldown;
    private int trapRotationAccum;
    private boolean trapRotatingClockwise;
    private int trapAngle;  // 16-bit angle word

    // Player tracking
    private boolean playerStanding;
    private int bit7State;
    private int platformMappingFrame;
    private int xVel;
    private int yVel;
    private int xSub;
    private int ySub;
    private int state6BobBaseY;

    public SwingingPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();

        // Parse subtype
        int subtype = spawn.subtype();
        // ROM Obj15_Init (s2.asm:22480) masks bits 0-3 (`andi.w #$F,d1`) with no upper clamp;
        // values up to 15 are valid. The chainCount drives the platform's hanging distance.
        // (Note: the ROM's multi-sprite parent only has 6 sub-sprite slots so visible chain
        // links cap at 6, but the math still uses the full chainCount.)
        this.chainCount = Math.max(1, subtype & 0x0F);
        this.displayOnly = (subtype & 0x80) != 0;
        this.bit7State = displayOnly ? BIT7_STATE_ARMED : BIT7_STATE_NORMAL;
        this.platformMappingFrame = PLATFORM_FRAME_NORMAL;
        this.state6BobBaseY = spawn.y();

        // Determine behavior mode from bits 4-6
        int modeValue = (subtype & 0x70) >> 4;
        this.behaviorMode = switch (modeValue) {
            case 1 -> BehaviorMode.BOUNCE_LEFT;
            case 2 -> BehaviorMode.STATIC;
            case 3 -> BehaviorMode.BOUNCE_RIGHT;
            case 4 -> BehaviorMode.TRAP;
            default -> BehaviorMode.NORMAL;
        };

        // Determine zone configuration
        this.zoneConfig = determineZoneConfig();

        // Initialize chain position arrays
        this.chainX = new int[chainCount];
        this.chainY = new int[chainCount];
        this.displayChildX = spawn.x();
        this.displayChildY = spawn.y() + DISPLAY_CHILD_ANCHOR_LENGTH;

        // Initialize trap mode state
        this.trapAngle = 0x8000;  // Start at top position
        this.trapRotationAccum = 0;
        this.trapRotatingClockwise = false;
        this.trapCooldown = 0;

        // Do NOT call updatePositions() here with a guessed oscillator value.
        // ROM parity: Obj15_Init sets up child sprite data and records the base coords
        // in objoff_38/3A; Obj15_State2 calls sub_FE70 on the very first active frame
        // to compute actual positions from the live oscillator. Computing positions here
        // with oscValue=0 would place x far from baseX (up to +136 px for chainCount=8),
        // causing the out_of_range check to immediately unload the object the same frame
        // it is spawned (distance > 640) and permanently mark it dormant.
        // this.x and this.y are already initialised to baseX/baseY above.

        LOGGER.fine(() -> String.format(
                "SwingingPlatform init: pos=(%d,%d), subtype=0x%02X, chains=%d, mode=%s, zone=%s",
                baseX, baseY, subtype, chainCount, behaviorMode, zoneConfig));
    }

    private SwingingPlatformObjectInstance(SwingingPlatformObjectInstance parent, int childXVel) {
        this(new ObjectSpawn(
                        parent.x,
                        parent.y,
                        parent.spawn.objectId(),
                        parent.spawn.subtype(),
                        parent.spawn.renderFlags(),
                        false,
                        parent.spawn.rawYWord(),
                        parent.spawn.layoutIndex()),
                parent.name);
        this.baseX = parent.baseX;
        this.baseY = parent.baseY;
        this.x = parent.x;
        this.y = parent.y;
        this.displayChildX = parent.displayChildX;
        this.displayChildY = parent.displayChildY;
        this.zoneConfig = parent.zoneConfig;
        this.behaviorMode = parent.behaviorMode;
        this.displayOnly = false;
        this.bit7State = BIT7_STATE_FALLING_CHILD;
        this.platformMappingFrame = PLATFORM_FRAME_NORMAL;
        this.xVel = childXVel;
        this.yVel = 0;
        this.xSub = 0;
        this.ySub = 0;
        this.state6BobBaseY = parent.y;
        updateDynamicSpawn(x, y);
    }

    @Override
    public SwingingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SwingingPlatformObjectInstance(ctx.spawn(), getName());
    }

    private ZoneConfig determineZoneConfig() {
        var objectServices = tryServices();
        if (objectServices != null && objectServices.currentLevel() != null) {
            int zoneId = objectServices.currentLevel().getZoneIndex();
            if (zoneId == Sonic2ZoneConstants.ROM_ZONE_MCZ) {
                return ZoneConfig.MCZ;
            } else if (zoneId == Sonic2ZoneConstants.ROM_ZONE_ARZ) {
                return ZoneConfig.ARZ;
            }
        }
        // Default to OOZ
        return ZoneConfig.OOZ;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    /**
     * ROM parity: the Obj15 parent's x_pos stays at the spawn/pivot X (baseX) for the
     * entire lifetime of the object — only the child multi-sprite's position oscillates.
     * The ROM out_of_range macro feeds obX(a0) (the parent's x_pos = baseX, not the
     * swinging platform x) to the distance check. Using the oscillating platform X would
     * cause the engine to spuriously unload the object on its first frame (platform starts
     * at baseX + 136 when oscValue = 0, pushing distance past the 640 px threshold while
     * the camera is near the spawn X).
     */
    @Override
    public int getOutOfRangeReferenceX() {
        if (isState6Child()) {
            return x;
        }
        return baseX;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (bit7State) {
            case BIT7_STATE_ARMED -> updateBit7State4(vIntRunCount, player);
            case BIT7_STATE_TRIGGERED_PARENT -> updateTriggeredParent(vIntRunCount, player);
            case BIT7_STATE_FALLING_CHILD -> updateState6FallingChild();
            case BIT7_STATE_BOBBING_CHILD -> updateState6BobbingChild();
            default -> updateByBehavior(vIntRunCount, player);
        }

        updateDynamicSpawn(x, y);
    }

    private void updateByBehavior(int vIntRunCount, AbstractPlayableSprite player) {
        ensureDisplayChild();

        switch (behaviorMode) {
            case NORMAL -> updateNormalSwing(vIntRunCount);
            case BOUNCE_LEFT -> updateBounceSwing(player, true);
            case BOUNCE_RIGHT -> updateBounceSwing(player, false);
            case TRAP -> updateTrapMode(player);
            case STATIC -> { /* No update needed */ }
        }
    }

    private void updateBit7State4(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM Obj15_State4 (s2.asm:22753-22831): run normal Obj15 swing math,
        // then split when the previous SolidObject standing bits are set and
        // Oscillating_Data+$18 reaches zero.
        updateByBehavior(vIntRunCount, player);
        boolean standing = playerStanding || safeIsPlayerRiding();
        playerStanding = false;
        if (standing && OscillationManager.getByte(0x18) == 0) {
            spawnState6Child();
        }
    }

    private void updateTriggeredParent(int vIntRunCount, AbstractPlayableSprite player) {
        updateByBehavior(vIntRunCount, player);
        playerStanding = false;
    }

    private boolean safeIsPlayerRiding() {
        try {
            return isPlayerRiding();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void spawnState6Child() {
        int childXVel = ((spawn.renderFlags() & 0x01) != 0)
                ? -STATE6_CHILD_X_SPEED
                : STATE6_CHILD_X_SPEED;
        spawnChild(() -> new SwingingPlatformObjectInstance(this, childXVel));
        platformMappingFrame = PLATFORM_FRAME_TRIGGERED_PARENT;
        bit7State = BIT7_STATE_TRIGGERED_PARENT;
    }

    private void updateState6FallingChild() {
        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
        SubpixelMotion.speedToPos(motion);
        motion.yVel += STATE6_CHILD_GRAVITY;
        x = motion.x;
        y = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        xVel = motion.xVel;
        yVel = motion.yVel;
        if (y >= STATE6_CHILD_LANDING_Y) {
            y = STATE6_CHILD_LANDING_Y;
            ySub = 0;
            xVel = 0;
            yVel = 0;
            state6BobBaseY = y;
            bit7State = BIT7_STATE_BOBBING_CHILD;
        }
    }

    private void updateState6BobbingChild() {
        y = state6BobBaseY + (OscillationManager.getByte(0x14) >> 1);
    }

    private boolean isState6Child() {
        return bit7State == BIT7_STATE_FALLING_CHILD || bit7State == BIT7_STATE_BOBBING_CHILD;
    }

    private void ensureDisplayChild() {
        if (displayChild != null) {
            return;
        }
        displayChild = spawnChild(() -> new SwingingPlatformDisplayChild(this));
    }

    private void attachDisplayChildForRewind(SwingingPlatformDisplayChild child) {
        displayChild = child;
    }

    // Returns the swinging platform whose out-of-range reference position is nearest
    // the display child's captured spawn, or empty when none survives (a legitimate
    // absent-parent state: the platform was swept before capture). No
    // {@code !isDestroyed()} filter: during a rewind restore the parent's destroyed
    // flag is applied in a later pass, so a captured-but-destroyed platform is a valid
    // relink target here.
    private static Optional<SwingingPlatformObjectInstance> nearestParentForRewind(RewindRecreateContext ctx) {
        var objectManager = ctx.objectManager() != null
                ? ctx.objectManager()
                : ctx.objectServices().objectManager();
        if (objectManager == null) {
            return Optional.empty();
        }
        ObjectSpawn spawn = ctx.spawn();
        return objectManager.getActiveObjects().stream()
                .filter(SwingingPlatformObjectInstance.class::isInstance)
                .map(SwingingPlatformObjectInstance.class::cast)
                .min((a, b) -> Integer.compare(
                        distanceFromChildSpawn(a, spawn),
                        distanceFromChildSpawn(b, spawn)));
    }

    private static int distanceFromChildSpawn(SwingingPlatformObjectInstance parent, ObjectSpawn spawn) {
        return Math.abs(parent.getOutOfRangeReferenceX() - spawn.x())
                + Math.abs(parent.getY() - spawn.y());
    }

    /**
     * Normal swing mode: Uses global oscillation data at offset 0x18.
     */
    private void updateNormalSwing(int vIntRunCount) {
        // Get oscillation value from offset 0x18 (Oscillating_Data+0x18)
        int oscValue = OscillationManager.getByte(0x18);
        updatePositions(oscValue);
    }

    /**
     * Bounce swing mode.
     * <p>
     * ROM sub_FE70 (s2.asm:22556-22586): unconditional oscillation clamp —
     * no player-proximity gate exists in the ROM.
     * <p>
     * BOUNCE_LEFT (subtype bits 4-6 == 0x10):
     *   osc &lt; 0x3F  → clamp to 0x40 (no sound)
     *   osc == 0x3F → play SndID_PlatformKnock, clamp to 0x40
     *   osc &gt;= 0x40 → use raw osc value (bhs loc_FEC2)
     * <p>
     * BOUNCE_RIGHT (subtype bits 4-6 == 0x30):
     *   osc == 0x41 → play SndID_PlatformKnock, clamp to 0x40
     *   osc &gt; 0x41  → clamp to 0x40 (no sound)
     *   osc &lt; 0x41  → use raw osc value (blo loc_FEC2)
     */
    private void updateBounceSwing(AbstractPlayableSprite player, boolean bounceLeft) {
        int oscValue = OscillationManager.getByte(0x18);

        if (bounceLeft) {
            // ROM s2.asm:22563-22575: BOUNCE_LEFT unconditional clamp
            if (oscValue == 0x3F) {
                services().playSfx(Sonic2Sfx.PLATFORM_KNOCK.id);
                oscValue = 0x40;
            } else if (oscValue < 0x3F) {
                oscValue = 0x40;
            }
            // osc >= 0x40: use raw value (bhs loc_FEC2)
        } else {
            // ROM s2.asm:22580-22586: BOUNCE_RIGHT unconditional clamp
            if (oscValue == 0x41) {
                services().playSfx(Sonic2Sfx.PLATFORM_KNOCK.id);
                oscValue = 0x40;
            } else if (oscValue > 0x41) {
                oscValue = 0x40;
            }
            // osc < 0x41: use raw value (blo loc_FEC2)
        }

        updatePositions(oscValue);
    }

    /**
     * Trap mode: Pressure plate with rotation when player is nearby.
     */
    private void updateTrapMode(AbstractPlayableSprite player) {
        // Handle cooldown
        if (trapCooldown > 0) {
            trapCooldown--;
            updatePositionsFromAngle();
            return;
        }

        // Check if player is nearby
        boolean playerNearby = false;
        if (player != null) {
            int playerX = player.getCentreX();
            int dx = playerX - baseX;
            if (Math.abs(dx) < 0x20) {
                playerNearby = true;
            }
        }

        if (playerNearby || trapRotationAccum != 0) {
            // Continue or start rotation
            if (trapRotatingClockwise) {
                // Rotating clockwise (angle increasing)
                trapRotationAccum += TRAP_ROTATION_STEP;
                trapAngle = (trapAngle + TRAP_ROTATION_STEP) & 0xFFFF;

                if (trapRotationAccum >= TRAP_ROTATION_MAX) {
                    // Reached max - reset to top and start cooldown
                    trapRotationAccum = 0;
                    trapAngle = 0x8000;
                    trapRotatingClockwise = false;
                    trapCooldown = TRAP_COOLDOWN;
                }
            } else {
                // Rotating counter-clockwise (angle decreasing)
                trapRotationAccum -= TRAP_ROTATION_STEP;
                trapAngle = (trapAngle - TRAP_ROTATION_STEP) & 0xFFFF;

                if (trapRotationAccum <= -TRAP_ROTATION_MAX) {
                    // Reached min - set to bottom and flip direction
                    trapRotationAccum = 0;
                    trapAngle = 0x4000;
                    trapRotatingClockwise = true;
                    trapCooldown = TRAP_COOLDOWN;
                }
            }
        }

        updatePositionsFromAngle();
    }

    /**
     * Update platform and chain positions based on oscillation value.
     * <p>
     * Uses CalcSine to convert oscillation value to positional offset.
     * The oscillation value is centered at 0x40 (64) for proper pendulum motion:
     * <ul>
     *   <li>oscValue 0x00: swing far left</li>
     *   <li>oscValue 0x40: center position (hanging straight down)</li>
     *   <li>oscValue 0x80: swing far right</li>
     * </ul>
     */
    private void updatePositions(int oscValue) {
        int effectiveOscValue = mirroredOscillatorValue(oscValue);
        // ROM sub_FE70 (s2.asm:22604-22654): jsrto JmpTo2_CalcSine with d0=oscValue.
        // CalcSine returns d0=sin(oscValue), d1=cos(oscValue) from the SINCOSLIST table.
        // If status.x_flip is set, s2.asm:22617-22620 negates the oscillator byte and adds $80
        // before CalcSine, mirroring the swing around objoff_3A/38.
        //
        // The chain-link loop accumulates d0 (sin) into Y and d1 (cos) into X:
        //   sin(oscValue) drives Y: oscValue=0x40 → sin=256 → hangs straight down
        //   cos(oscValue) drives X: oscValue=0x40 → cos=0   → centred horizontally
        //
        // ROM fixed-point equivalence (verified exhaustively for all 3840 osc×chain combos):
        //   ROM: (chainCount + 0.5) * val * 4096 >> 16
        //   Engine: (val * (chainCount*0x10 + 8)) >> 8
        // Both produce identical results for all inputs in the SINCOSLIST table.
        //
        // IMPORTANT: do NOT use swingAngle=(oscValue-0x40). The SINCOSLIST table is NOT
        // perfectly antisymmetric (e.g. SINCOSLIST[147]=-117 while SINCOSLIST[19]=115),
        // so sin(osc-0x40) ≠ -cos(osc) for all values. Using oscValue directly is the
        // only way to match the ROM's CalcSine(oscValue) call exactly.
        int sin = calcSine(effectiveOscValue);   // d0 = sin(oscValue) → drives Y
        int cos = calcCosine(effectiveOscValue); // d1 = cos(oscValue) → drives X

        // Platform hangs at (chainCount + 0.5) increments of 0x10 per link from pivot.
        int chainLength = chainCount * 0x10 + 8;
        int displayChildXOffset = (cos * DISPLAY_CHILD_ANCHOR_LENGTH) >> 8;
        int displayChildYOffset = (sin * DISPLAY_CHILD_ANCHOR_LENGTH) >> 8;
        int xOffset = (cos * chainLength) >> 8;
        int yOffset = (sin * chainLength) >> 8;

        // ROM sub_FE70 copies the just-written sub6_x/sub6_y multi-sprite entry
        // into the child object's x_pos/y_pos, while the parent/platform position
        // is the full chain endpoint plus a half-link.
        displayChildX = baseX + displayChildXOffset;
        displayChildY = baseY + displayChildYOffset;
        if (displayChild != null) {
            displayChild.syncFromParent();
        }

        // Platform position
        this.x = baseX + xOffset;
        this.y = baseY + yOffset;

        // Chain link positions (i-th link is at (i+1) increments from pivot)
        for (int i = 0; i < chainCount; i++) {
            int linkLength = (i + 1) * 0x10;
            chainX[i] = baseX + ((cos * linkLength) >> 8);
            chainY[i] = baseY + ((sin * linkLength) >> 8);
        }
    }

    private int mirroredOscillatorValue(int oscValue) {
        int value = oscValue & 0xFF;
        if ((spawn.renderFlags() & 0x01) != 0) {
            value = (0x80 - value) & 0xFF;
        }
        return value;
    }

    /**
     * Update positions based on trap angle (16-bit angle word).
     */
    private void updatePositionsFromAngle() {
        // Extract effective angle (high byte)
        int effectiveAngle = (trapAngle >> 8) & 0xFF;
        updatePositions(effectiveAngle);
    }

    /**
     * Calculate sine value for angle (0-255 maps to 0-360 degrees).
     */
    private int calcSine(int angle) {
        return TrigLookupTable.sinHex(angle);
    }

    /**
     * Calculate cosine value for angle.
     * Cosine = sine(angle + 64) where 64 = 90 degrees.
     */
    private int calcCosine(int angle) {
        return TrigLookupTable.cosHex(angle);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Get appropriate mappings for this zone
        List<SpriteMappingFrame> mappings = getMappingsForZone();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        if (isState6Child()) {
            renderPlatformFrame(mappings, graphicsManager, hFlip, vFlip);
            return;
        }

        // Render anchor/base at pivot point (frame 2)
        if (mappings.size() > 2) {
            SpriteMappingFrame anchorFrame = mappings.get(2);
            if (anchorFrame != null && !anchorFrame.pieces().isEmpty()) {
                renderPieces(graphicsManager, anchorFrame.pieces(), baseX, baseY, hFlip, vFlip);
            }
        }

        // Render chain links (frame 1) at calculated positions
        // Note: displayOnly flag affects behavior state, not rendering - chains still render
        if (mappings.size() > 1) {
            SpriteMappingFrame chainFrame = mappings.get(1);
            if (chainFrame != null && !chainFrame.pieces().isEmpty()) {
                for (int i = 0; i < chainCount; i++) {
                    renderPieces(graphicsManager, chainFrame.pieces(), chainX[i], chainY[i], hFlip, vFlip);
                }
            }
        }

        renderPlatformFrame(mappings, graphicsManager, hFlip, vFlip);
    }

    private void renderPlatformFrame(List<SpriteMappingFrame> mappings, GraphicsManager graphicsManager,
                                     boolean hFlip, boolean vFlip) {
        if (mappings.size() > platformMappingFrame) {
            SpriteMappingFrame platformFrame = mappings.get(platformMappingFrame);
            if (platformFrame != null && !platformFrame.pieces().isEmpty()) {
                renderPieces(graphicsManager, platformFrame.pieces(), x, y, hFlip, vFlip);
            }
        }
    }

    private List<SpriteMappingFrame> getMappingsForZone() {
        if (behaviorMode == BehaviorMode.TRAP) {
            return TRAP_MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_OBJ15_TRAP_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj15Trap");
        }
        return switch (zoneConfig) {
            case OOZ -> OOZ_MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_OBJ15_A_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj15OOZ");
            case ARZ -> ARZ_MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_OBJ83_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj15ARZ");
            case MCZ -> MCZ_MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_OBJ15_MCZ_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj15MCZ");
        };
    }

    private void renderPieces(GraphicsManager graphicsManager, List<SpriteMappingPiece> pieces,
                              int drawX, int drawY, boolean hFlip, boolean vFlip) {
        SpritePieceRenderer.renderPieces(
                pieces,
                drawX,
                drawY,
                0,  // Base pattern index (level art starts at 0)
                -1, // Use palette from piece
                hFlip,
                vFlip,
                (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, px, py) -> {
                    int descIndex = patternIndex & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;
                    graphicsManager.renderPattern(new PatternDesc(descIndex), px, py);
                });
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);  // Priority 3 from disassembly
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(
                zoneConfig.widthPixels,
                zoneConfig.yRadius,
                zoneConfig.yRadius + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;  // Platform is only solid from the top
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            playerStanding = true;
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return !isDestroyed() && bit7State != BIT7_STATE_TRIGGERED_PARENT;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw pivot point (yellow cross)
        ctx.drawLine(baseX - 4, baseY, baseX + 4, baseY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(baseX, baseY - 4, baseX, baseY + 4, 1.0f, 1.0f, 0.0f);

        // Draw chain link positions (cyan crosses)
        for (int i = 0; i < chainCount; i++) {
            ctx.drawLine(chainX[i] - 2, chainY[i], chainX[i] + 2, chainY[i], 0.0f, 1.0f, 1.0f);
            ctx.drawLine(chainX[i], chainY[i] - 2, chainX[i], chainY[i] + 2, 0.0f, 1.0f, 1.0f);
        }

        // Draw platform collision box (green)
        int halfWidth = zoneConfig.widthPixels;
        int halfHeight = zoneConfig.yRadius;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);      // Top (standing surface)
        ctx.drawLine(right, top, right, bottom, 0.3f, 0.7f, 0.3f);
        ctx.drawLine(right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
        ctx.drawLine(left, bottom, left, top, 0.3f, 0.7f, 0.3f);

        // Draw platform center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);
    }

    private static final class SwingingPlatformDisplayChild extends AbstractObjectInstance implements RewindRecreatable {
        private final SwingingPlatformObjectInstance parent;
        private int x;
        private int y;

        private SwingingPlatformDisplayChild(SwingingPlatformObjectInstance parent) {
            super(new ObjectSpawn(
                    parent.displayChildX,
                    parent.displayChildY,
                    parent.spawn.objectId(),
                    parent.spawn.subtype(),
                    parent.spawn.renderFlags(),
                    false,
                    parent.spawn.rawYWord()),
                    "SwingingPlatformDisplayChild");
            this.parent = parent;
            syncFromParent();
        }

        @Override
        public SwingingPlatformDisplayChild recreateForRewind(RewindRecreateContext ctx) {
            // Display child of a swinging platform. If the parent was swept before
            // capture there is no assembly owner to relink to, so drop the child (its
            // live update self-expires with a dead parent) rather than throw.
            return nearestParentForRewind(ctx)
                    .map(parent -> {
                        SwingingPlatformDisplayChild child = new SwingingPlatformDisplayChild(parent);
                        parent.attachDisplayChildForRewind(child);
                        return child;
                    })
                    .orElse(null);
        }

        private void syncFromParent() {
            this.x = parent.displayChildX;
            this.y = parent.displayChildY;
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
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            syncFromParent();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Parent renders the full multi-sprite assembly; this instance only occupies the ROM SST child slot.
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return parent.getOutOfRangeReferenceX();
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }
    }

}
