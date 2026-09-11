package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.level.WaterSystem;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x82 - Swinging Platform from ARZ.
 * <p>
 * A platform that swings down when the player stands on it, returning to its
 * rest position when the player leaves. Multiple subtypes control different
 * behaviors like falling, rising, or tracking water level.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 56632-56876 (Obj82 code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 0-3: Behavior type (0-7)</li>
 *   <li>Bits 4-5: Property index / frame select</li>
 * </ul>
 * <p>
 * <b>Behavior types:</b>
 * <ul>
 *   <li>0: Static platform (no behavior)</li>
 *   <li>1, 3: Wait for player contact, then fall after 30 frames</li>
 *   <li>2, 6: Fall with gravity</li>
 *   <li>4: Rise with anti-gravity</li>
 *   <li>5: Check terrain collision, then fall</li>
 *   <li>7: Follow water level</li>
 * </ul>
 * <p>
 * <b>Swinging:</b>
 * When enabled (subtype bits 0-3 not 0 and not 7), the platform swings
 * based on player contact. Standing on it increases the angle, leaving
 * decreases it. Max angle is 0x40 (90 degrees), giving ~4 pixels of Y offset.
 */
public class SwingingPformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(SwingingPformObjectInstance.class.getName());

    // art_tile palette from disassembly: make_art_tile(ArtTile_ArtKos_LevelArt,0,0) for frame 0 (palette 3)
    // Actually, from obj82.asm: frame 0 uses palette 3, frame 1 uses palette 1
    // The palette is part of the mapping piece data, not added separately

    // Property table from disassembly (Obj82_Properties at line 56647)
    // Format: [width_pixels, y_radius]
    private static final int[][] PROPERTIES = {
            {0x20, 0x08},  // Property 0: 32px wide, 8px radius
            // Property 1: 28px wide, 48px radius. Retail REV01 builds with fixBugs = 0
            // (docs/s2disasm/s2.asm:27), so Obj82_Properties resolves to the non-fixBugs
            // 'else' branch dc.b $1C,$30 (docs/s2disasm/s2.asm:57094-57096). The fixBugs
            // branch would be $1C,$32, but using it places a rider ~2-3px too high on the
            // pillar top (objTopHeight too large in SolidObject_Landed,
            // docs/s2disasm/s2.asm:35582-35616).
            {0x1C, 0x30},  // Property 1: 28px wide, 48px radius (retail REV01 / non-fixBugs)
            {0x10, 0x10},  // Property 2: 16px wide, 16px radius (unused)
            {0x10, 0x10}   // Property 3: 16px wide, 16px radius (unused)
    };

    // Swinging constants
    private static final int MAX_SWING_ANGLE = 0x40;  // 90 degrees
    private static final int ANGLE_CHANGE_RATE = 4;   // Angle change per frame
    private static final int SWING_SCALE = 0x400;     // Scale factor for sine calculation

    // Physics constants
    private static final int GRAVITY = 8;             // Gravity acceleration (Type 2/6)
    private static final int WATER_SPEED = 2;         // Max speed toward water level (Type 7)
    private static final int CONTACT_DELAY = 0x1E;    // 30 frames delay before falling (Type 1/3)

    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Position state
    private int x;
    private int y;
    private int baseY;          // Original Y position (objoff_30)
    private int subY;           // 8.8 fixed point Y for smooth movement
    private int yVel;           // 8.8 fixed point Y velocity

    // Object state
    private int widthPixels;
    private int yRadius;
    private int mappingFrame;
    private int behaviorType;   // Extracted from subtype bits 0-3
    private int delayCounter;   // objoff_36 - countdown for Type 1/3
    private int swingAngle;     // objoff_3E - current swing angle (0 to MAX_SWING_ANGLE)
    private boolean swingEnabled; // objoff_38 - whether swinging is active
    private boolean playerStanding; // Tracks if player is currently standing on platform
    public SwingingPformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.y = spawn.y();
        this.baseY = spawn.y();
        this.subY = y << 8;
        this.yVel = 0;
        this.delayCounter = 0;
        this.swingAngle = 0;
        this.playerStanding = false;

        initFromSubtype();
        updateDynamicSpawn(x, y);
    }

    @Override
    public SwingingPformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SwingingPformObjectInstance(ctx.spawn(), "SwingingPform");
    }

    private void initFromSubtype() {
        int subtype = spawn.subtype();

        // Extract behavior type (bits 0-3)
        behaviorType = subtype & 0x0F;

        // Extract property index from subtype
        // Disassembly (lines 56675-56681):
        //   lsr.w   #3,d0           ; Shift right 3 bits
        //   andi.w  #$E,d0          ; Mask with 0x0E (binary: 1110) - gets bits 3-5 of original
        //   ... use for array indexing ...
        //   lsr.w   #1,d0           ; Divide by 2 to get actual index
        int propIndex = (subtype >> 3) & 0x0E;  // Gets 0, 2, 4, 6, 8, 10, 12, or 14
        propIndex = propIndex >> 1;              // Convert to 0, 1, 2, 3, 4, 5, 6, or 7

        // Clamp to valid property range (only 4 entries defined)
        if (propIndex >= PROPERTIES.length) {
            propIndex = 0;
        }

        widthPixels = PROPERTIES[propIndex][0];
        yRadius = PROPERTIES[propIndex][1];

        // Determine mapping frame based on property index
        // Disassembly (line 56681): move.b d0,mapping_frame(a0)
        // The frame is the propIndex directly, but we only have 2 frames in practice
        // Clamp to available frames (the mappings file only has 2 frames)
        mappingFrame = Math.min(propIndex, 1);

        // Enable swinging if behavior type is 1-6 (not 0 or 7)
        // From disassembly: objoff_38 is set to 1 for types 1-6
        // Note: When type 1/3/5 transition to 2/4/6, they clear swingEnabled
        // If a platform starts at type 2/4/6, swinging will be enabled but the Y movement
        // will override it (matching original behavior)
        swingEnabled = (behaviorType >= 1 && behaviorType <= 6);

        LOGGER.fine(() -> String.format(
                "SwingingPform init: subtype=0x%02X, behavior=%d, width=%d, yRadius=%d, frame=%d, swing=%s",
                subtype, behaviorType, widthPixels, yRadius, mappingFrame, swingEnabled));
    }

    /**
     * {@code width_pixels(a0)} from {@code Obj82_Properties}
     * (docs/s2disasm/s2.asm:57180-57182), which is what
     * {@code BuildSprites}' horizontal cull compares against
     * {@code x_pos - Camera_X_pos_copy} (docs/s2disasm/s2.asm:30568-30578).
     */
    @Override
    public int getOnScreenHalfWidth() {
        return widthPixels;
    }

    /**
     * Retail Obj82 does NOT set {@code render_flags.explicit_height}: the
     * shipped build takes the {@code fixBugs = 0} arm of Obj82_Init's
     * conditional, {@code move.b #1<<render_flags.level_fg,render_flags(a0)}
     * (docs/s2disasm/s2.asm:57169-57174) -- the disassembly's own comment there
     * reads "Same as Obj2B, this should use the accurate height flag". Without
     * that bit {@code BuildSprites} takes {@code BuildSprites_ApproxYCheck},
     * which compares against {@code spriteScreenPositionY(0-32)} and
     * {@code spriteScreenPositionY(screen_height+32)}
     * (docs/s2disasm/s2.asm:30604-30610) -- it "assume[s] Y radius to be 32
     * pixels" regardless of the pillar's real {@code y_radius} of $30.
     * <p>
     * So 32 here is the ROM's own culling constant, not a margin chosen to fit
     * anything, and {@link #usesCustomRenderHeight()} stays false because the
     * ROM leaves the accurate-height bit clear.
     */
    @Override
    public int getOnScreenHalfHeight() {
        return 32;
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
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Update behavior based on type (Obj82_Types jump table).
        // ROM Obj82_Main: jsr Obj82_Types (docs/s2disasm/s2.asm:57140).
        // This runs unconditionally -- it sits BEFORE the on-screen gate below.
        updateBehavior(player);

        // ROM Obj82_Main gates everything after Obj82_Types on the render flag:
        //   _btst #render_flags.on_screen,render_flags(a0)
        //   _beq.s +                       (docs/s2disasm/s2.asm:57205-57206)
        // and the '+' label lands past BOTH JmpTo23_SolidObject and loc_2A432,
        // so an object whose flag is clear is neither solid nor swinging that
        // frame. loc_2A432 (docs/s2disasm/s2.asm:57340-57365) holds the
        // objoff_3E angle counter as well as the CalcSine y_pos write, so the
        // freeze covers the angle: an off-screen platform mid-decay stays at its
        // current offset instead of returning to objoff_30.
        if (!isPreUpdateWithinRenderSpriteBounds(
                getOnScreenHalfWidth(), getOnScreenHalfHeight())) {
            updateDynamicSpawn(x, y);
            return;
        }

        // Carry any riding player BEFORE the swing CalcSine writes the new y_pos.
        // ROM Obj82_Main runs JmpTo23_SolidObject (rider carry,
        // docs/s2disasm/s2.asm:57159) BEFORE loc_2A432 (swing CalcSine y_pos write,
        // docs/s2disasm/s2.asm:57162). With the default AUTO_AFTER_UPDATE the rider
        // carry would fire after updateSwinging() had already written the
        // current-frame swing y, putting the rider (and downstream camera) 1px ahead
        // of the ROM whenever the platform is moving. MANUAL_CHECKPOINT below lets us
        // resolve the solid contact here, between the two ROM steps, so the rider
        // follows the platform's previous-frame swing position like the ROM does.
        services().solidExecution().resolveSolidNowAll();

        // Update swinging animation (loc_2A432, docs/s2disasm/s2.asm:57162 ->
        // 57280-57307: CalcSine -> y_pos). Runs AFTER the rider carry above.
        updateSwinging();

        updateDynamicSpawn(x, y);
    }

    /**
     * Obj82 carries its rider via JmpTo23_SolidObject (docs/s2disasm/s2.asm:57159)
     * BEFORE running the swing CalcSine motion loc_2A432 (docs/s2disasm/s2.asm:57162),
     * so the rider follows the platform's previous-frame swing y. The default
     * AUTO_AFTER_UPDATE checkpoint fires after the whole update() (after the swing has
     * already written the new y), so we drive the checkpoint manually from update().
     */
    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    /**
     * Update behavior based on subtype.
     * Corresponds to Obj82_Types jump table in disassembly.
     */
    private void updateBehavior(AbstractPlayableSprite player) {
        switch (behaviorType) {
            case 0 -> {
                // Type 0: Static - no behavior
            }
            case 1, 3 -> updateWaitForContact();
            case 2, 6 -> updateFalling();
            case 4 -> updateRising();
            case 5 -> updateCheckCollision();
            case 7 -> updateWaterLevel();
            default -> {
                // Unknown type - treat as static
            }
        }
    }

    /**
     * Type 1/3: Wait for player contact, then fall after delay.
     * Corresponds to loc_2A36A in disassembly.
     */
    private void updateWaitForContact() {
        if (delayCounter > 0) {
            delayCounter--;
            if (delayCounter == 0) {
                // Delay finished - transition to falling
                behaviorType++;  // 1 -> 2, 3 -> 4
                swingEnabled = false;
            }
        } else if (playerStanding) {
            // Player just landed - start delay
            delayCounter = CONTACT_DELAY;
        }
    }

    /**
     * Type 2/6: Fall with gravity until hitting floor.
     * Corresponds to loc_2A392 in disassembly.
     */
    private void updateFalling() {
        // ROM loc_2A392 (docs/s2disasm/s2.asm:57203-57205):
        //   jsrto JmpTo16_ObjectMove   ; move y_pos by the CURRENT y_vel first
        //   addi_.w #8,y_vel(a0)        ; THEN add gravity for next frame
        // ObjectMove (docs/s2disasm/s2.asm:30192-30197) does
        // y_pos(16.16) += y_vel << 8, i.e. the 8.8 subpixel y += y_vel. So the
        // platform descends using last frame's velocity, and gravity only takes
        // effect on the following frame. Adding gravity BEFORE moving (the prior
        // order here) advanced the platform one velocity step early, which carried
        // the riding player 1px ahead of the ROM every falling frame (ARZ2 trace
        // first-divergence frame 264: slot 22 Obj82 type 2).
        // Move with current velocity.
        subY += yVel;
        y = subY >> 8;

        // Apply gravity AFTER the move (ROM addi_.w #8,y_vel after ObjectMove).
        yVel += GRAVITY;

        // Check floor collision using ObjectTerrainUtils (mirrors ROM's ObjCheckFloorDist)
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
        if (result.hasCollision()) {
            // Hit floor - snap to surface
            y = y + result.distance();
            subY = y << 8;
            yVel = 0;
            behaviorType = 0;  // Return to idle
            baseY = y;         // Update base position
        }
    }

    /**
     * Type 4: Rise with anti-gravity until hitting ceiling.
     * Corresponds to loc_2A3B6 in disassembly.
     */
    private void updateRising() {
        // ROM loc_2A3B6 (docs/s2disasm/s2.asm:57218-57220):
        //   jsrto JmpTo16_ObjectMove   ; move y_pos by the CURRENT y_vel first
        //   subi_.w #8,y_vel(a0)        ; THEN apply anti-gravity for next frame
        // Same move-then-accelerate order as the falling routine above; apply the
        // velocity before decrementing it so the platform (and rider) does not lead
        // the ROM by one step.
        // Move with current velocity.
        subY += yVel;
        y = subY >> 8;

        // Apply anti-gravity AFTER the move (ROM subi_.w #8,y_vel after ObjectMove).
        yVel -= GRAVITY;

        // Check ceiling collision using ObjectTerrainUtils (mirrors ROM's ObjCheckCeilingDist)
        TerrainCheckResult result = ObjectTerrainUtils.checkCeilingDist(x, y, yRadius);
        if (result.hasCollision()) {
            // Hit ceiling - snap to surface
            y = y - result.distance();
            subY = y << 8;
            yVel = 0;
            behaviorType = 0;  // Return to idle
            baseY = y;         // Update base position
        }
    }

    /**
     * Type 5: Check terrain collision status, transition if colliding.
     * Corresponds to loc_2A3D8 in disassembly.
     */
    private void updateCheckCollision() {
        // In the original game, this checks objoff_3F collision bits
        // For simplicity, check if we're near terrain
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
        if (result.hasCollision() || result.distance() < 8) {
            // Near or touching floor - transition to falling
            behaviorType++;  // 5 -> 6
            swingEnabled = false;
        }
    }

    /**
     * Type 7: Move platform toward water level.
     * Corresponds to loc_2A3EC in disassembly.
     */
    private void updateWaterLevel() {
        if (services().currentLevel() == null) {
            return;
        }

        // Get water level from WaterSystem
        int zoneId = services().currentLevel().getZoneIndex();
        int actId = services().currentAct();
        WaterSystem waterSystem = services().waterSystem();

        if (!waterSystem.hasWater(zoneId, actId)) {
            return;  // No water in this level
        }

        int waterLevel = waterSystem.getWaterLevelY(zoneId, actId);
        if (waterLevel <= 0) {
            return;  // No water level
        }

        int delta = waterLevel - y;
        if (delta == 0) {
            return;  // At water level
        }

        // Clamp movement to WATER_SPEED
        if (delta > WATER_SPEED) {
            delta = WATER_SPEED;
        } else if (delta < -WATER_SPEED) {
            delta = -WATER_SPEED;
        }

        y += delta;
        subY = y << 8;

        // Check collision based on movement direction
        if (delta < 0) {
            // Moving up - check ceiling
            TerrainCheckResult result = ObjectTerrainUtils.checkCeilingDist(x, y, yRadius);
            if (result.hasCollision()) {
                y = y - result.distance();
                subY = y << 8;
            }
        } else {
            // Moving down - check floor
            TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
            if (result.hasCollision()) {
                y = y + result.distance();
                subY = y << 8;
            }
        }
    }

    /**
     * Update the swinging animation.
     * Corresponds to loc_2A432 in disassembly.
     */
    private void updateSwinging() {
        if (!swingEnabled) {
            return;
        }

        // Check if player is standing on platform
        boolean standing = services().objectManager() != null &&
                services().objectManager().isAnyPlayerRiding(this);
        playerStanding = standing;

        if (standing) {
            // Player is standing - increase swing angle
            if (swingAngle < MAX_SWING_ANGLE) {
                swingAngle += ANGLE_CHANGE_RATE;
                if (swingAngle > MAX_SWING_ANGLE) {
                    swingAngle = MAX_SWING_ANGLE;
                }
            }
        } else {
            // Player not standing - return to center
            if (swingAngle > 0) {
                swingAngle -= ANGLE_CHANGE_RATE;
                if (swingAngle < 0) {
                    swingAngle = 0;
                }
            }
        }

        // Calculate Y offset from swing angle
        // Formula: y_offset = sin(angle) * SWING_SCALE / 65536
        if (swingAngle > 0) {
            int sineValue = getSine(swingAngle);
            int yOffset = (sineValue * SWING_SCALE) >> 16;
            y = baseY + yOffset;
            subY = y << 8;
        } else {
            y = baseY;
            subY = y << 8;
        }
    }

    /**
     * Get sine value for angle (0-64 maps to 0-90 degrees).
     * Returns value in 8-bit format (0 to 256 for 0 to 1).
     * Delegates to TrigLookupTable.sinHex() which uses the ROM-accurate SINCOSLIST.
     */
    private int getSine(int angle) {
        if (angle <= 0) {
            return 0;
        }
        if (angle > MAX_SWING_ANGLE) {
            angle = MAX_SWING_ANGLE;
        }
        return TrigLookupTable.sinHex(angle);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ82_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj82");
        if (mappings.isEmpty()) {
            return;
        }

        int frame = mappingFrame;
        if (frame < 0 || frame >= mappings.size()) {
            frame = 0;
        }

        SpriteMappingFrame mapping = mappings.get(frame);
        if (mapping == null || mapping.pieces().isEmpty()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        GraphicsManager graphicsManager = services().graphicsManager();
        List<SpriteMappingPiece> pieces = mapping.pieces();

        // Render pieces in reverse order (painter's algorithm)
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            renderPiece(graphicsManager, piece, x, y, hFlip, vFlip);
        }
    }

    private void renderPiece(GraphicsManager graphicsManager, SpriteMappingPiece piece,
                             int drawX, int drawY, boolean hFlip, boolean vFlip) {
        SpritePieceRenderer.renderPieces(
                List.of(piece),
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
        // ROM Obj82_Main builds the SolidObject collision box from the property
        // table values, but NOT 1:1 (docs/s2disasm/s2.asm:57145-57151):
        //   moveq   #0,d1
        //   move.b  width_pixels(a0),d1
        //   addi.w  #$B,d1            ; d1 = width_pixels + 0xB  (SolidObject half-width)
        //   moveq   #0,d2
        //   move.b  y_radius(a0),d2   ; d2 = y_radius            (air/jumping half-height)
        //   move.w  d2,d3
        //   addq.w  #1,d3             ; d3 = y_radius + 1        (ground/walking half-height)
        // The +0xB widens the SolidObject box beyond the raw property width, so the
        // standing/ExitPlatform x-range that keeps a rider attached is
        // x_player - x_obj in [-d1, d1) = [-(width_pixels+0xB), width_pixels+0xB).
        // Without the +0xB the engine half-width was width_pixels (0x1C), unseating
        // the rider ~0xB px too early on the pillar's right edge: in the ARZ2 trace
        // the leader walked off the s27 pillar at x_player-x_obj=0x1E (relX 58),
        // one frame before the ROM (which keeps him on until 0x27), so the frame-483
        // jump fired airborne instead of from the on-object ground state.
        // (Retail REV01 builds with fixBugs = 0 (docs/s2disasm/s2.asm:27), so the
        // pillar-specific subq.w #2,d3 at s2.asm:57152-57156 does NOT apply.)
        return SolidObjectParams.of(widthPixels + 0xB, yRadius, yRadius + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj82_Main calls JmpTo23_SolidObject (docs/s2disasm/s2.asm:57221), and
        // that thunk resolves to SolidObject (docs/s2disasm/s2.asm:35014) -- the
        // full four-sided routine, not one of the top-only platform entries. So
        // SolidObject_ChkBounds' axis choice applies: with d5 (horizontal distance
        // to the nearer edge) and d1 (vertical distance), cmp.w d1,d5 / bhi
        // SolidObject_TopBottom (docs/s2disasm/s2.asm:35407-35408) takes the TOP
        // path only when the horizontal distance is STRICTLY greater; otherwise the
        // contact resolves through SolidObject_LeftRight, which corrects x by
        // sub.w d0,x_pos(a1) at SolidObject_AtEdge (docs/s2disasm/s2.asm:35438) and
        // returns a side contact without landing.
        //
        // Reporting top-solid-only here skipped both side branches, so every
        // contact reached the vertical landing check. In ARZ2 that turned a
        // clipped corner on a pillar into a landing: at seg13_arz2 frame 344 the
        // box is exactly the ROM's (relX 75, relY 5, d2 67, d4 134 against a
        // half-width of width_pixels+$B) and the distances are absDistX 3,
        // absDistY 5, so the ROM pushes Sonic +3px clear and keeps him falling at
        // y_vel $0B60 while the engine seated him on the pillar.
        return false;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // Build from this provider so usesPlatformObjectLandingSnap()=false is
        // honoured: Obj82 is a SolidObject (Obj82_Main calls JmpTo23_SolidObject,
        // docs/s2disasm/s2.asm:57159), not a PlatformObject, so SolidObject_Landed's
        // playerY - distY + 3 result must be preserved rather than overwritten by the
        // PlatformObject_ChkYRange absolute snap. See usesPlatformObjectLandingSnap().
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        // Obj82 uses SolidObject_Landed (docs/s2disasm/s2.asm:35582-35616:
        // sub.w d3,y_pos / subq #1,y_pos), reached via JmpTo23_SolidObject at
        // docs/s2disasm/s2.asm:57159 -- NOT PlatformObject_ChkYRange's absolute
        // anchorY - groundHalfHeight - y_radius - 1 snap. The shared
        // resolveContactInternal already computes the SolidObject landed centre
        // (playerY - distY + 3) correctly; the PlatformObject override would use
        // groundHalfHeight (= y_radius + 1) and land the rider 1px too high.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Track standing for swinging logic
        if (contact.standing()) {
            playerStanding = true;
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = widthPixels;
        int halfHeight = yRadius;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        // Draw collision box in green
        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);      // Top (standing surface)
        ctx.drawLine(right, top, right, bottom, 0.3f, 0.7f, 0.3f);
        ctx.drawLine(right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
        ctx.drawLine(left, bottom, left, top, 0.3f, 0.7f, 0.3f);

        // Draw center cross in red to show object origin
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);
    }

}
