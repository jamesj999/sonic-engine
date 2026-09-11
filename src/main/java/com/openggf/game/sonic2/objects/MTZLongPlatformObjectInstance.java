package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.ButtonVineTriggerManager;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * MTZ Long Platform (Object 0x65) - Long moving platform from Metropolis Zone.
 * <p>
 * Disassembly Reference: s2.asm lines 52348-52750 (Obj65 code)
 * <p>
 * Properties table (indexed by (subtype >> 4) & 0x0E):
 * <pre>
 * Index 0: width=0x40, y_radius=0x0C, frame=0 (4-block platform)
 * Index 1: width=0x80, y_radius=0x01, frame=1 (2-block platform, no_balancing)
 * Index 2: width=0x20, y_radius=0x0C, frame=2 -> cog object (routine 6)
 * Index 3: width=0x40, y_radius=0x03, frame=3 (3-block platform)
 * Index 4: width=0x10, y_radius=0x10, frame=4 (small block)
 * Index 5: width=0x20, y_radius=0x00, frame=5 (unused?)
 * Index 6: width=0x40, y_radius=0x0C, frame=6 (4-block platform)
 * Index 7: width=0x80, y_radius=0x07, frame=7 (big platform with button trigger)
 * </pre>
 * <p>
 * Movement subtypes (bits 0-3 after init):
 * <ul>
 *   <li>0: Stationary (return_26C8E)</li>
 *   <li>1: Button-triggered move right (loc_26CA4)</li>
 *   <li>2: Timer-triggered move right (loc_26D34)</li>
 *   <li>3: Player proximity move (loc_26D94)</li>
 *   <li>4: Step-on advance (loc_26E3C)</li>
 *   <li>5: Conveyor-style continuous movement (loc_26E4A)</li>
 *   <li>6: Timer-triggered move right with delay (loc_26C90)</li>
 *   <li>7: Button-triggered move right with maxDist init (loc_26D14)</li>
 * </ul>
 * <p>
 * Bit 7 of initial subtype triggers child cog spawn via AllocateObjectAfterCurrent.
 */
public class MTZLongPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // Obj65_Properties table (s2.asm line 52362)
    // {width_pixels, y_radius, movementData, childSubtype}
    private static final int[][] PROPERTIES = {
            {0x40, 0x0C},  // Index 0: frame 0
            {0x80, 0x01},  // Index 1: frame 1 (no_balancing)
            {0x20, 0x0C},  // Index 2: frame 2 -> standalone cog (routine 6)
            {0x40, 0x03},  // Index 3: frame 3
            {0x10, 0x10},  // Index 4: frame 4
            {0x20, 0x00},  // Index 5: frame 5
            {0x40, 0x0C},  // Index 6: frame 6
            {0x80, 0x07},  // Index 7: frame 7
    };

    // Delay timer value: move.w #$B4,objoff_36(a0)
    private static final int DELAY_FRAMES = 0xB4;

    // Movement speed per frame: addq.w #2,objoff_3A(a0)
    private static final int MOVE_SPEED = 2;

    // Movement speed for subtype 5: addq.w #2,x_pos(a0)
    private static final int CONVEYOR_SPEED = 2;
    private static final int STALE_LOGICAL_HORIZONTAL_FRAMES = 3;

    // Proximity detection speed: addi.w #$10,objoff_3A(a0) / subi.w #$10
    private static final int PROXIMITY_SPEED = 0x10;
    private static final ObjectPlayerParticipationPolicy PROXIMITY_PARTICIPANTS =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    // Proximity detection offsets when NOT x-flipped (s2.asm lines 52606-52607)
    private static final int PROX_LEFT_NORMAL = -0x20;
    private static final int PROX_RIGHT_NORMAL = 0x60;
    // When x-flipped (s2.asm lines 52612-52613)
    private static final int PROX_LEFT_FLIPPED = -0xA0;
    private static final int PROX_RIGHT_FLIPPED = -0x20;
    private static final int PROX_TOP = -0x10;
    private static final int PROX_BOTTOM = 0x40;

    // Subtype 5 hardcoded X limits
    // MTZ Act 3 (metropolis_zone_2): two stop points
    private static final int MTZ3_STOP_1 = 0x1CC0;
    private static final int MTZ3_STOP_2 = 0x2940;
    private static final int MTZ3_STOP_1_STALE_INPUT_LEFT = 0x1C80;
    private static final int MTZ3_STOP_2_STALE_INPUT_LEFT = 0x28A0;
    private static final int MTZ3_STOP_2_STALE_INPUT_RIGHT = 0x28C0;
    // MTZ Acts 1&2: single stop point
    private static final int MTZ12_STOP = 0x1BC0;
    // MTZ Acts 1&2 reverse direction limit
    private static final int MTZ12_REVERSE = 0x1880;

    /**
     * Shared variable: MTZ_Platform_Cog_X ($FFFFF7B0).
     * Written by subtype 5 platform, read by standalone cog (routine 6).
     */
    private static int mtzPlatformCogX;

    /** Shared Obj65_a level-art mappings (4 frames), lazily loaded from ROM. */
    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Position tracking
    private int x;
    private int y;
    private int baseX;           // objoff_34 - original X position
    private int baseY;           // objoff_30 - original Y position

    // Subtype configuration
    private int widthPixels;
    private int yRadius;
    private int mappingFrame;
    private int moveSubtype;     // Current movement subtype (bits 0-3, mutable)

    // Movement state
    private int maxDist;         // objoff_3C - maximum movement distance
    private int currentDist;     // objoff_3A - current movement distance
    private int delayTimer;      // objoff_36 - delay countdown
    private boolean triggered;   // objoff_38 - trigger flag
    private int buttonId;        // objoff_3E - ButtonVine trigger ID
    private boolean xFlip;       // status.npc.x_flip
    private boolean pendingChildCogSpawn;

    // Standing detection
    private boolean contactStanding;
    // Step-on advance (sub-type 4) uses the ROM p1_standing_bit, which tracks ONLY the
    // main character (s2.asm:52676). Track main-character standing separately.
    private boolean contactStandingMain;

    public MTZLongPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MTZLongPlatform");
        init();
    }

    @Override
    public MTZLongPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MTZLongPlatformObjectInstance(ctx.spawn());
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
    public int getOutOfRangeReferenceX() {
        // Obj65 loc_26C1C checks objoff_34, not the moving x_pos(a0), before
        // calling DeleteObject (docs/s2disasm/s2.asm:52469-52484).
        return baseX;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From s2.asm lines 52457-52463: d1=width+5, d2=y_radius, d3=y_radius+1
        int halfWidth = widthPixels + 5;
        return SolidObjectParams.of(halfWidth, yRadius, yRadius + 1);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        // SolidObject_Landed re-reads width_pixels(a0). Obj65 is unusual because
        // Obj65_Main passes width_pixels+$5 into SolidObject, not width_pixels+$B.
        return widthPixels;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Move/Tails_Move read width_pixels from the ridden Obj65 SST
        // (s2.asm:36591-36600,39712-39721). This is the properties-table width,
        // not the default rendered half-width exposed by AbstractObjectInstance.
        return widthPixels;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean suppressesObjectEdgeBalance() {
        // ROM Obj65_Init sets status.npc.no_balancing when mapping_frame == 1
        // (s2.asm:52865-52870). Tails_Move tests that bit before object-edge
        // balance and falls through to look/duck input handling when it is set.
        return mappingFrame == 1;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    /**
     * ROM s2.asm Obj65 carries the rider only for the conveyor subtype 5
     * ({@code loc_26E4A}), which updates only {@code x_pos} and leaves the
     * {@code objoff_2E} carry reference saved at the pre-move x. All other
     * Obj65 movement subtypes use {@code loc_26D50} (subtypes 1/2/6/7) or
     * {@code loc_26E1A} (subtype 3), both of which refresh {@code objoff_2E}
     * to the new x_pos so {@code MvSonicOnPtfm} sees a zero delta and the
     * rider stands still while the platform glides underneath.
     */
    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return moveSubtype == 5;
    }

    @Override
    public int staleHorizontalLogicalInputFramesWhileRiding(PlayableEntity player, int rideFrames) {
        // Obj65 subtype 5 updates x_pos directly in loc_26E4A before reaching
        // SolidObject; Sonic_Move then consumes Ctrl_1_Held_Logical rather than
        // the current raw input row. The MTZ3 ROM sample keeps that logical word
        // stale for three frames only once Sonic is already facing right. The
        // level-select route shows the same helper timing on both MTZ3 stop
        // approaches: $1CC0 and $2940.
        return moveSubtype == 5
                && player != null
                && !player.isCpuControlled()
                && player.getDirection() == Direction.RIGHT
                && isNearMtz3ConveyorStop()
                ? STALE_LOGICAL_HORIZONTAL_FRAMES
                : 0;
    }

    private boolean isNearMtz3ConveyorStop() {
        return (x >= MTZ3_STOP_1_STALE_INPUT_LEFT && x < MTZ3_STOP_1)
                || (x >= MTZ3_STOP_2_STALE_INPUT_LEFT && x < MTZ3_STOP_2_STALE_INPUT_RIGHT);
    }

    @Override
    public boolean zeroXSpeedStopsOnLeftSideContact() {
        // Obj65 calls the shared S2 SolidObject helper after updating its x_pos
        // (docs/s2disasm/s2.asm:52925-52940). In SolidObject_InsideLeft,
        // x_vel == 0 does not take the bmi branch to SolidObject_AtEdge; it
        // falls through to SolidObject_StopCharacter, clearing inertia and
        // x_vel before side separation (docs/s2disasm/s2.asm:35424-35439).
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (contact.standing() || contact.touchTop()) {
            contactStanding = true;
            // p1_standing_bit reflects only the main (non-CPU) character; a CPU sidekick
            // standing on the platform must not set it.
            if (player != null && !player.isCpuControlled()) {
                contactStandingMain = true;
            }
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (pendingChildCogSpawn) {
            pendingChildCogSpawn = false;
            spawnChildCog();
        }
        executeMovement(vIntRunCount, player);
        updateDynamicSpawn(x, y);
        // ROM loc_26C1C tail (s2.asm:52469-52484) marks the object gone + clears
        // its respawn bit from objoff_34; getOutOfRangeReferenceX exposes that
        // anchor to the shared ObjectManager out_of_range path.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj65 renders the real Obj65_a level-art mappings (4 frames) against the
        // zone's level art (ArtTile_ArtKos_LevelArt, base tile 0) on VDP palette
        // line 3 (s2.asm:52381-52382 make_art_tile(ArtTile_ArtKos_LevelArt,3,0)).
        // mapping_frame = d0/4 computed in init() (s2.asm:52394), range 0..3.
        int frame = Math.max(0, Math.min(mappingFrame, 3));

        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_MTZ_PLATFORM_LEVELART_ADDR,
                S2SpriteDataLoader::loadMappingFrames, "Obj65a");
        if (mappings.isEmpty() || frame >= mappings.size()) {
            return;
        }
        SpriteMappingFrame mapFrame = mappings.get(frame);
        if (mapFrame == null || mapFrame.pieces().isEmpty()) {
            return;
        }
        GraphicsManager graphicsManager = services().graphicsManager();
        if (graphicsManager == null) {
            return;
        }
        SpritePieceRenderer.renderPieces(
                mapFrame.pieces(),
                x, y,
                0,   // level art starts at tile 0
                3,   // palette line 3
                xFlip, false,
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

    /**
     * Returns the shared MTZ_Platform_Cog_X value for standalone cog objects.
     */
    public static int getMtzPlatformCogX() {
        return mtzPlatformCogX;
    }

    /**
     * Resets all global state for MTZLongPlatform objects.
     * Call on level load to ensure clean state across level transitions.
     */
    public static void resetGlobalState() {
        mtzPlatformCogX = 0;
    }

    /**
     * Returns the current distance for child cog animation.
     */
    public int getCurrentDist() {
        return currentDist;
    }

    private void init() {
        // ROM s2.asm:52386-52394 -- subtype to props lookup:
        //   lsr.w #2,d0; andi.w #$1C,d0     -> d0 = byte offset into PROPERTIES
        //   lea Obj65_Properties(pc,d0.w),a3 -> a3 = entry at byte offset d0
        //   move.b (a3)+,width_pixels        -> entry width
        //   move.b (a3)+,y_radius            -> entry y_radius
        //   lsr.w #2,d0; move.b d0,mapping_frame -> mapping_frame = d0/4
        // Each PROPERTIES entry is 2 bytes, so the ENTRY INDEX is d0/2, while
        // the mapping_frame is d0/4. These are NOT the same index.
        int rawSubtype = spawn.subtype();
        int d0 = (rawSubtype >> 2) & 0x1C;
        int entryIndex = d0 >> 1;             // a3 = props + d0 -> entry index = d0/2
        int frameIndex = d0 >> 2;             // mapping_frame = d0/4
        if (entryIndex >= PROPERTIES.length) {
            entryIndex = 0;
        }

        widthPixels = PROPERTIES[entryIndex][0];
        yRadius = PROPERTIES[entryIndex][1];
        mappingFrame = frameIndex;

        // Note: propsIndex 2 (standalone cog) is routed to MTZLongPlatformCogInstance by the factory.

        xFlip = (spawn.renderFlags() & 0x01) != 0;

        // Store base positions (s2.asm lines 52404-52405)
        baseX = spawn.x();
        baseY = spawn.y();
        x = baseX;
        y = baseY;

        // After reading width_pixels and y_radius from (a3)+, the pointer advances
        // to the next entry. The 3rd read ((a3)+) gives maxDist (= width of next entry),
        // and the 4th read ((a3)) gives the child subtype (= y_radius of next entry).
        // s2.asm lines 52407-52414
        int nextIndex = entryIndex + 1;
        if (nextIndex < PROPERTIES.length) {
            maxDist = PROPERTIES[nextIndex][0];
        } else {
            maxDist = 0;
        }

        // Check bit 7 of original subtype for child cog spawn
        // s2.asm line 52411: move.b subtype(a0),d0; bpl.w loc_26C16
        if ((rawSubtype & 0x80) != 0) {
            // Extract button ID from lower nibble (s2.asm line 52412-52413)
            buttonId = rawSubtype & 0x0F;

            // Read child subtype from properties (4th byte = y_radius of next entry)
            // s2.asm line 52414: move.b (a3),subtype(a0)
            int childSubtype = 0;
            if (nextIndex < PROPERTIES.length) {
                childSubtype = PROPERTIES[nextIndex][1];
            }

            // Special case: if child subtype is 7, init currentDist=maxDist
            // s2.asm lines 52415-52417
            if (childSubtype == 7) {
                currentDist = maxDist;
            }

            // Placement-loaded objects enter the engine object table before their first
            // ExecuteObjects routine pass. Defer the AllocateObjectAfterCurrent child
            // creation to that first update so Obj65 parent/child slot timing matches ROM.
            pendingChildCogSpawn = true;

            // At loc_26C16: andi.b #$F,subtype(a0) - applies to the NEW subtype
            moveSubtype = childSubtype & 0x0F;

            // Clear respawn bit (s2.asm lines 52440-52444)
            // Handled by engine respawn system
        } else {
            // No child cog - just mask subtype to lower 4 bits
            // s2.asm line 52447: andi.b #$F,subtype(a0)
            moveSubtype = rawSubtype & 0x0F;
        }
    }

    private void spawnChildCog() {
        // Calculate child position (s2.asm lines 52423-52430)
        int childX = spawn.x() - 0x4C; // addi.w #-$4C,x_pos(a1)
        int childY = spawn.y() + 0x14;  // addi.w #$14,y_pos(a1)
        boolean childXFlip;

        if (xFlip) {
            // btst #status.npc.x_flip -> bne.s +
            // When parent is x-flipped, child stays at base offset
            childXFlip = false;
        } else {
            // When parent is NOT x-flipped: subi.w #-$18,x_pos(a1)
            // subi.w #-$18 = addi.w #$18 (subtract negative = add)
            childX += 0x18;
            childXFlip = true; // bset #render_flags.x_flip
        }

        // Spawn via the shared child-spawn path (s2.asm AllocateObjectAfterCurrent).
        final int fChildX = childX;
        final boolean fChildXFlip = childXFlip;
        spawnChild(() -> new MTZLongPlatformCogInstance(
                fChildX, childY, fChildXFlip, this));
    }

    private void executeMovement(int vIntRunCount, AbstractPlayableSprite player) {
        switch (moveSubtype) {
            case 0 -> { /* Stationary - return_26C8E */ }
            case 1 -> moveButtonTriggered();
            case 2 -> moveTimerTriggered();
            case 3 -> movePlayerProximity(player);
            case 4 -> moveStepOnAdvance(vIntRunCount);
            case 5 -> moveConveyor();
            case 6 -> moveTimerTriggeredWithDelay();
            case 7 -> moveButtonTriggeredBack();
        }
    }

    /**
     * Subtype 1: Button-triggered move right (loc_26CA4).
     * Waits for ButtonVine trigger, then moves right until maxDist.
     * On reaching maxDist, advances subtype (wraps to subtype 2).
     */
    private void moveButtonTriggered() {
        if (!triggered) {
            // Check ButtonVine trigger (s2.asm lines 52511-52516)
            if (ButtonVineTriggerManager.getTrigger(buttonId)) {
                triggered = true;
            } else {
                updatePositionFromDist();
                return;
            }
        }

        // Moving: check if reached maxDist (s2.asm lines 52519-52522)
        if (currentDist == maxDist) {
            // Advance subtype (s2.asm line 52539)
            moveSubtype++;
            delayTimer = DELAY_FRAMES;
            triggered = false;
            // Set respawn bit (s2.asm line 52546)
        } else {
            currentDist += MOVE_SPEED;
        }

        updatePositionFromDist();
    }

    /**
     * Subtype 6: Timer-triggered move right with delay (loc_26C90).
     * Same as subtype 1 but uses delay timer instead of button.
     */
    private void moveTimerTriggeredWithDelay() {
        if (!triggered) {
            delayTimer--;
            if (delayTimer <= 0) {
                triggered = true;
            } else {
                updatePositionFromDist();
                return;
            }
        }

        // Same forward movement as subtype 1
        if (currentDist == maxDist) {
            moveSubtype++;
            delayTimer = DELAY_FRAMES;
            triggered = false;
        } else {
            currentDist += MOVE_SPEED;
        }

        updatePositionFromDist();
    }

    /**
     * Subtype 2: Timer-triggered move left/retract (loc_26D34).
     * Moves back to start after delay.
     */
    private void moveTimerTriggered() {
        if (!triggered) {
            delayTimer--;
            if (delayTimer <= 0) {
                triggered = true;
            } else {
                updatePositionFromDist();
                return;
            }
        }

        // Moving back: check if retracted to 0 (s2.asm lines 52571-52573)
        if (currentDist == 0) {
            // Retract subtype (s2.asm line 52590)
            moveSubtype--;
            delayTimer = DELAY_FRAMES;
            triggered = false;
            // Clear respawn bit (s2.asm line 52597)
        } else {
            currentDist -= MOVE_SPEED;
        }

        updatePositionFromDist();
    }

    /**
     * Subtype 7: Button-triggered retract (loc_26D14).
     * Same as subtype 2 but uses button trigger instead of timer.
     */
    private void moveButtonTriggeredBack() {
        if (!triggered) {
            // Check ButtonVine trigger (s2.asm lines 52553-52558)
            if (ButtonVineTriggerManager.getTrigger(buttonId)) {
                triggered = true;
            } else {
                updatePositionFromDist();
                return;
            }
        }

        // Retract (s2.asm lines 52571-52573)
        if (currentDist == 0) {
            moveSubtype--;
            delayTimer = DELAY_FRAMES;
            triggered = false;
        } else {
            currentDist -= MOVE_SPEED;
        }

        updatePositionFromDist();
    }

    /**
     * Subtype 3: Player proximity triggered (loc_26D94).
     * Extends when player is in detection zone, retracts when player leaves.
     */
    private void movePlayerProximity(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Calculate detection zone (s2.asm lines 52602-52614)
        int left, right;
        if (xFlip) {
            left = baseX + PROX_LEFT_FLIPPED;
            right = baseX + PROX_RIGHT_FLIPPED;
        } else {
            left = baseX + PROX_LEFT_NORMAL;
            right = baseX + PROX_RIGHT_NORMAL;
        }
        int top = y + PROX_TOP;
        int bottom = y + PROX_BOTTOM;

        // Obj65 loc_26D94 checks MainCharacter and Sidekick in sequence before deciding
        // whether to extend/retract, so a native P2/Tails presence keeps the platform armed.
        boolean playerInZone = false;
        for (PlayableEntity participant : proximityParticipants(player)) {
            if (participant instanceof AbstractPlayableSprite candidate
                    && candidate.getCentreX() >= left && candidate.getCentreX() < right
                    && candidate.getCentreY() >= top && candidate.getCentreY() < bottom) {
                playerInZone = true;
                break;
            }
        }

        if (playerInZone) {
            // Extend (s2.asm lines 52647-52651)
            if (currentDist < maxDist) {
                currentDist += PROXIMITY_SPEED;
                if (currentDist > maxDist) {
                    currentDist = maxDist;
                }
            }
        } else {
            // Retract (s2.asm lines 52654-52657)
            if (currentDist > 0) {
                currentDist -= PROXIMITY_SPEED;
                if (currentDist < 0) {
                    currentDist = 0;
                }
            }
        }

        // Update position (s2.asm lines 52660-52669)
        int d0 = currentDist;
        if (xFlip) {
            d0 = -d0 + 0x40;
        }
        x = baseX - d0;
    }

    private List<PlayableEntity> proximityParticipants(AbstractPlayableSprite updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PROXIMITY_PARTICIPANTS);
        if (updatePlayer == null || participants.contains(updatePlayer)) {
            return participants;
        }
        ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
        withUpdatePlayer.add(updatePlayer);
        withUpdatePlayer.addAll(participants);
        return withUpdatePlayer;
    }

    /**
     * Subtype 4: Step-on advance (loc_26E3C).
     * Increments subtype when player stands on it.
     */
    private void moveStepOnAdvance(int vIntRunCount) {
        // s2.asm lines 52676-52684: btst #p1_standing_bit,status(a0); beq +; addq.b #1,subtype.
        // p1_standing_bit reflects ONLY the main character, so advance solely on the main
        // character standing — a riding sidekick must not trigger the step-on.
        boolean mainStanding = contactStandingMain;
        contactStanding = false;
        contactStandingMain = false;
        if (mainStanding) {
            moveSubtype++;
        }
    }

    /**
     * Subtype 5: Conveyor-style continuous movement (loc_26E4A).
     * Moves platform continuously, reverses at zone-specific boundaries.
     * Writes x_pos to MTZ_Platform_Cog_X shared variable.
     */
    private void moveConveyor() {
        boolean isMtzAct3 = services().currentZone() == Sonic2ZoneConstants.ROM_ZONE_MTZ_3;

        if (!triggered) {
            // Moving right: addq.w #2,x_pos(a0)
            x += CONVEYOR_SPEED;

            if (isMtzAct3) {
                // s2.asm lines 52688-52696: two stop points
                if (x == MTZ3_STOP_1 || x == MTZ3_STOP_2) {
                    moveSubtype = 0; // move.b #0,subtype(a0)
                }
            } else {
                // s2.asm lines 52700-52703: single stop, then reverse
                if (x == MTZ12_STOP) {
                    triggered = true;
                }
            }
        } else {
            // Moving left: subq.w #2,x_pos(a0)
            x -= CONVEYOR_SPEED;

            // s2.asm lines 52708-52710: reverse when reaching left limit
            if (x == MTZ12_REVERSE) {
                triggered = false;
            }
        }

        // Update base position and shared variable
        // s2.asm lines 52713-52714
        baseX = x;
        mtzPlatformCogX = x;
    }

    /**
     * Common position update for subtypes 1, 2, 6, 7 (s2.asm lines 52525-52535 / 52576-52586).
     * Applies currentDist to baseX, respecting x_flip.
     */
    private void updatePositionFromDist() {
        int d0 = currentDist;
        if (xFlip) {
            d0 = -d0 + 0x80; // neg.w d0; addi.w #$80,d0
        }
        x = baseX - d0;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = widthPixels + 5;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - yRadius;
        int bottom = y + yRadius + 1;

        ctx.drawLine(left, top, right, top, 0.4f, 0.7f, 0.9f);
        ctx.drawLine(right, top, right, bottom, 0.4f, 0.7f, 0.9f);
        ctx.drawLine(right, bottom, left, bottom, 0.4f, 0.7f, 0.9f);
        ctx.drawLine(left, bottom, left, top, 0.4f, 0.7f, 0.9f);

        // Center cross
        ctx.drawLine(x - 4, y, x + 4, y, 0.4f, 0.7f, 0.9f);
        ctx.drawLine(x, y - 4, x, y + 4, 0.4f, 0.7f, 0.9f);
    }

}
