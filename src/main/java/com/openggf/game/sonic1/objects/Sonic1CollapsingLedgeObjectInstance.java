package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractFallingFragment;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 1A - GHZ Collapsing Ledge.
 * <p>
 * A sloped platform that crumbles when Sonic stands on it. After a delay,
 * the ledge splits into individual fragment objects that fall with gravity.
 * <p>
 * Subtypes:
 * <ul>
 *   <li>0x00: Left-facing ledge (mapping frame 0, smash frame 2)</li>
 *   <li>0x01: Right-facing ledge (mapping frame 1, smash frame 3)</li>
 * </ul>
 * <p>
 * State machine (obRoutine values):
 * <ul>
 *   <li>0 (Ledge_Main): Initialize</li>
 *   <li>2 (Ledge_Touch): Slope platform, check for collapse trigger</li>
 *   <li>4 (Ledge_Collapse): Countdown to fragment, with ExitPlatform checks</li>
 *   <li>6 (Ledge_Display): Fragment falling with gravity (ObjectFall)</li>
 *   <li>8 (Ledge_Delete): Destroy when offscreen</li>
 *   <li>A (Ledge_WalkOff): ExitPlatform + SlopeObject2 subroutine</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/1A Collapsing Ledge (part 1).asm
 */
public class Sonic1CollapsingLedgeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider, SpawnRewindRecreatable {

    // From disassembly: move.w #$30,d1 (half-width for platform collision)
    private static final int PLATFORM_HALF_WIDTH = 0x30;

    // From disassembly: move.b #$64,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x64;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.b #7,ledge_timedelay(a0)
    private static final int INITIAL_COLLAPSE_DELAY = 7;

    // Gravity constant from ObjectFall: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // GHZ Collapsing Ledge Heightmap (48 bytes from misc/GHZ Collapsing Ledge Heightmap.bin)
    // Each byte = height offset from object Y. Index = (playerX - objectX + $30) / 2
    // Values: columns 0-7 = 0x20, then linearly increasing by 1 per 2 columns up to 0x30
    private static final byte[] SLOPE_DATA = {
            0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20,
            0x21, 0x21, 0x22, 0x22, 0x23, 0x23, 0x24, 0x24,
            0x25, 0x25, 0x26, 0x26, 0x27, 0x27, 0x28, 0x28,
            0x29, 0x29, 0x2A, 0x2A, 0x2B, 0x2B, 0x2C, 0x2C,
            0x2D, 0x2D, 0x2E, 0x2E, 0x2F, 0x2F, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30
    };

    // Ledge_Main obActWid: `move.b #200/2,obActWid(a0)` -- the FixBugs = 0 branch
    // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:39-48). The
    // FixBugs = 1 branch would use 96/2 instead; FixBugs is 0 in this build
    // (docs/s1disasm/sonic.asm:20), so the ledge and every fragment that inherits
    // obActWid from it render (and therefore survive) 100px past the screen edge.
    private static final int LEDGE_ACT_WIDTH = 200 / 2;

    // Ledge_Main obHeight: `move.b #112/2,obHeight(a0)` (line 50), consumed by
    // BuildSprites because Ledge_Main also sets sprite_customheight_bit (line 51).
    private static final int LEDGE_HEIGHT = 112 / 2;

    // FragmentatePlatform copies obID, obMap, obRender, obX/obY, obGfx, obPriority
    // and obActWid to each fragment, but never obHeight (lines 332-342). DeleteObject
    // zeroes the whole $40-byte slot (docs/s1disasm/_incObj/sub DeleteObject.asm:
    // 15-18), so a FindFreeObj'd slot always starts with obHeight = 0 -- and the
    // copied obRender still carries sprite_customheight_bit. Fragments therefore
    // render through BuildSprites' custom-height path with a zero-height band, i.e.
    // exactly [camY, camY + 224), while the parent keeps its own LEDGE_HEIGHT.
    private static final int FRAGMENT_HEIGHT = 0;

    // Disintegration delay data from CFlo_Data1 (26 bytes).
    // Each value = frame delay before that fragment starts falling.
    // Fragments are created from the "smash" mapping frame pieces.
    // The first piece of the parent object (index 0) gets its delay from here.
    private static final int[] COLLAPSE_DELAYS = {
            0x1C, 0x18, 0x14, 0x10, 0x1A, 0x16, 0x12, 0x0E,
            0x0A, 0x06, 0x18, 0x14, 0x10, 0x0C, 0x08, 0x04,
            0x16, 0x12, 0x0E, 0x0A, 0x06, 0x02, 0x14, 0x10,
            0x0C, 0x00
    };


    // Routine state: 0=init, 2=touch, 4=collapse, 6=display(fragment), 8=delete, A=walkoff
    private int routine;

    // Current position (center coordinates)
    private int x;
    private int y;

    // Subtype determines facing: 0 = left, 1 = right
    private int subtype;

    // Mapping frame index: 0=left, 1=right (from obSubtype -> obFrame in init)
    private int mappingFrame;

    // Collapse timer (ledge_timedelay = objoff_38)
    private int collapseDelay;

    // Collapse flag (ledge_collapse_flag = objoff_3A): set when player steps on during routine 4
    private boolean collapseFlag;

    // Velocity for fragment falling (routine 6: ObjectFall)
    // 16.16 fall state for ObjectFall. x/y are synced to/from fallMotion.
    private final SubpixelMotion.State fallMotion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    // Whether fragments have been spawned
    private boolean fragmented;

    // ROM Ledge_OnPlatform branches directly to fragmentation when the timer is
    // already zero, skipping Ledge_WalkOff/SlopeObject_AssumeStoodOn that frame.
    private boolean transitionFrameSlopeSkip;

    public Sonic1CollapsingLedgeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CollapsingLedge");
        
        this.subtype = spawn.subtype() & 0xFF;
        this.mappingFrame = subtype; // obSubtype -> obFrame: 0=left, 1=right
        this.x = spawn.x();
        this.y = spawn.y();
        this.collapseDelay = INITIAL_COLLAPSE_DELAY;
        this.collapseFlag = false;
        this.routine = 2; // Skip init, go straight to Ledge_Touch
        this.fragmented = false;
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
        transitionFrameSlopeSkip = false;
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case 2 -> updateTouch(player);
            case 4 -> updateCollapse(player);
            case 6 -> updateFragmentFall(player);
            case 8 -> destroyWithWindowGatedRespawn();
            default -> { }
        }
        updateDynamicSpawn(x, y);
    }

    /**
     * Routine 2 (Ledge_Touch): Sloped platform behavior.
     * If collapse flag set, counts down delay then fragments.
     * Otherwise acts as a normal slope platform.
     */
    private void updateTouch(AbstractPlayableSprite player) {
        if (collapseFlag) {
            if (collapseDelay <= 0) {
                // Timer expired: fragment the ledge (Ledge_Fragment path - clears flag)
                performFragment(player, true);
                return;
            }
            collapseDelay--;
        }
        // SlopeObject + RememberState handled via SolidObjectProvider
    }

    /**
     * Routine 4 (Ledge_Collapse): Entered when player walks on a fragment piece.
     * Sets collapse flag and counts down, with ExitPlatform + SlopeObject2 checking.
     * This state is entered when the player is standing on us (via SolidContacts).
     */
    private void updateCollapse(AbstractPlayableSprite player) {
        if (collapseDelay <= 0) {
            // Transition to fragmentation (loc_847A path - preserves flag)
            performFragment(player, false);
            return;
        }
        collapseFlag = true;
        collapseDelay--;

        // Ledge_WalkOff: ExitPlatform + SlopeObject2 + RememberState
        // The engine's SolidContacts system handles exit/slope automatically
    }

    /**
     * Routine 6 (Ledge_Display): Fragment pieces fall with gravity.
     * Each fragment piece is a separate object in routine 6.
     * When collapse_flag is set, continues WalkOff behavior until delay expires,
     * then detaches player and falls. Otherwise falls immediately when delay = 0.
     */
    private void updateFragmentFall(AbstractPlayableSprite player) {
        if (collapseDelay > 0) {
            if (collapseFlag) {
                // loc_82D0: WalkOff behavior while collapsing
                collapseDelay--;
                var objectManager = services().objectManager();
                boolean playerRiding = objectManager != null && player != null
                        && objectManager.isAnyPlayerRiding(this);

                if (!playerRiding) {
                    // loc_82FC: Player walked off - clear flag, stay in routine 6
                    collapseFlag = false;
                } else if (collapseDelay <= 0) {
                    // Delay expired with player still standing:
                    // bclr #3,obStatus(a1) / bclr #5,obStatus(a1)
                    // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:104-105).
                    // ROM clears Sonic's Status_OnObj (#3) and Status_Push (#5)
                    // directly, so on the collapse-release frame the player is no
                    // longer object-attached and Sonic_DoLevelCollision re-seats
                    // him onto the terrain surface that frame. clearRidingObject only
                    // drops the engine-side riding bookkeeping; it does NOT clear the
                    // player's on-object/pushing status, so without these the player
                    // stayed pinned at the ledge's last slope Y (GHZ3 f6464: engine
                    // held centre 0x038E where ROM re-seats to the 2px-higher terrain
                    // surface 0x038C).
                    // Ledge_WalkOff calls SlopeObject_AssumeStoodOn before the
                    // native status bits are cleared below. Leave the engine's
                    // ride link intact until the compatibility solid checkpoint:
                    // sampleSlopeOnRideExit() gives that checkpoint the final
                    // slope write and it then clears OnObj in the same order.
                    player.setPushing(false);
                    // Retail S1 also executes `move.b #id_Run,obPrevAni(a1)`
                    // here. The selected byte remains Walk, but the mismatched
                    // previous-animation byte restarts that script on Sonic's
                    // next object slot (GHZ3 collapse release).
                    player.publishRunAsPreviousAnimation();
                    // loc_82FC: clear flag
                    collapseFlag = false;
                }
                // locret_8308: return (continue supporting player while delay > 0)
                return;
            }

            // Not collapsing: just count down delay before falling
            collapseDelay--;
            return;
        }

        // ROM `.fragmentFall` (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and
        // Floors.asm:116-129). FixBugs = 0 (docs/s1disasm/sonic.asm:20) selects the
        // `bsr ObjectFall / bsr DisplaySprite / tst.b obRender(a0) / bpl Ledge_Delete`
        // branch: the fragment is deleted when the LAST BuildSprites pass failed to
        // render it, so the deciding position is this frame's pre-fall one. (The
        // FixBugs = 1 branch only moves the test ahead of DisplaySprite to avoid the
        // display-and-delete null dereference; the delete frame, and therefore the SST
        // slot lifetime, is identical.)
        //
        // The band is the object's own obActWid/obHeight, not a fixed margin:
        // Ledge_Main sets obActWid = 200/2 under FixBugs = 0 (lines 39-48), obHeight
        // = 112/2 (line 50), and sets sprite_customheight_bit (line 51), so
        // BuildSprites takes its custom-height Y path.
        boolean renderedLastFrame =
                isWithinBuildSpritesBounds(x, y, LEDGE_ACT_WIDTH, LEDGE_HEIGHT);
        applyObjectFall();
        if (!renderedLastFrame) {
            destroyWithWindowGatedRespawn();
        }
    }

    /**
     * Object 1A uses DeleteObject when falling fragments leave the screen.
     * Keep the spawn suppressed until it leaves the object window so it doesn't
     * recreate immediately while still near the camera.
     */
    private void destroyWithWindowGatedRespawn() {
        if (!isDestroyed() ) {
            var objectManager = services().objectManager();
            ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
        }
        setDestroyed(true);
    }

    /**
     * ObjectFall subroutine: updates position with velocity and applies gravity.
     * Delegates to {@link SubpixelMotion#objectFallXY(SubpixelMotion.State, int)}.
     */
    private void applyObjectFall() {
        fallMotion.x = x;
        fallMotion.y = y;
        SubpixelMotion.objectFallXY(fallMotion, GRAVITY);
        x = fallMotion.x;
        y = fallMotion.y;
    }

    /**
     * Ledge_Fragment: Split ledge into individual fragment objects.
     * Uses the "smash" mapping frame (frame 2 for left, frame 3 for right).
     * Each piece from the smash mapping becomes a separate fragment object
     * with its own collapse delay from CFlo_Data1.
     * <p>
     * From disassembly (sonic.asm lines 4635-4683):
     * - Ledge_Fragment path (routine 2): clears ledge_collapse_flag = 0
     * - loc_847A path (routine 4): preserves ledge_collapse_flag (remains true)
     * - Loads CFlo_Data1 delay table
     * - Uses smash frame (obFrame + 2)
     * - Creates up to 25 fragment objects (d1 = $18 = 24, +1 for self = 25)
     * - Each fragment gets its own delay from CFlo_Data1
     * - Plays sfx_Collapse
     *
     * @param clearFlag true when called from Routine 2 (Ledge_Fragment), false from Routine 4 (loc_847A)
     */
    private void performFragment(AbstractPlayableSprite player, boolean clearFlag) {
        if (fragmented) {
            return;
        }
        fragmented = true;
        if (clearFlag) {
            collapseFlag = false;
        } else {
            transitionFrameSlopeSkip = true;
        }

        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        // Get the smash frame pieces for this subtype
        // Frame 2 = leftsmash, Frame 3 = rightsmash
        int smashFrameIndex = mappingFrame + 2;
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.COLLAPSING_LEDGE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        var sheet = renderManager.getSheet(ObjectArtKeys.COLLAPSING_LEDGE);
        if (sheet == null) {
            return;
        }

        if (smashFrameIndex >= sheet.getFrameCount()) {
            return;
        }

        SpriteMappingFrame smashFrame = sheet.getFrame(smashFrameIndex);
        int pieceCount = smashFrame.pieces().size();

        // The first fragment reuses this object (self), rest are new dynamic objects.
        // d1 = $18 (24), loop creates up to 25 fragments total.
        // Each fragment gets a delay from CFlo_Data1.

        // Convert self to fragment (routine 6)
        this.routine = 6;
        // bset #5,obRender(a0) - set "use mapped position" flag
        if (COLLAPSE_DELAYS.length > 0) {
            this.collapseDelay = COLLAPSE_DELAYS[0];
        }

        // FixBugs = 0 (docs/s1disasm/sonic.asm:20) — the shipped branch, which is what
        // the traces record. The fragment loop in
        // docs/s1disasm/_incObj/"1A, 53 Collapsing Ledges and Floors.asm":320-352
        // allocates with FindFreeObj (scans the SST from the start), so a fragment can
        // land BELOW the parent, past this frame's ExecuteObjects walk. The shipped
        // block at lines 344-352 compensates with `bsr.w DisplaySprite2` ONLY — unlike
        // SmashObject there is no SpeedToPos and no counter-gravity, because these
        // fragments do not move until their collapsible_timedelay expires, so the
        // catch-up is purely about getting the piece rendered on its spawn frame.
        // The engine renders every live object each frame regardless of whether its
        // slot has already executed, so no extra call is needed here; only the
        // rendering, not the position, would differ. With FixBugs = 1 the allocator
        // would be FindNextFreeObj and the block is omitted as redundant.
        // Spawn remaining fragments as dynamic objects
        int maxFragments = Math.min(pieceCount, COLLAPSE_DELAYS.length);
        for (int i = 1; i < maxFragments; i++) {
            final int idx = i;
            final int delay = COLLAPSE_DELAYS[i];
            spawnFreeChild(() -> new CollapsingLedgeFragmentInstance(
                    x, y, smashFrameIndex, idx, delay,
                    spawn.renderFlags()));
        }

        // Play collapse sound: move.w #sfx_Collapse,d0 / jmp (QueueSound2).l
        services().playSfx(Sonic1Sfx.COLLAPSE.id);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.COLLAPSING_LEDGE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // obRender bit 0 = X-flip, inherited by all fragments in disassembly
        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        if (routine == 6) {
            // Fragment: render only piece 0 from the smash frame (self is first piece)
            int smashFrameIndex = mappingFrame + 2;
            renderer.drawFramePieceByIndex(smashFrameIndex, 0, x, y, hFlip, false);
        } else {
            // Normal: render the full ledge
            renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM SlopeObject logic does not add object half-height to surface checks;
        // it tests directly against (obY - slopeSample). Keep vertical extents at 0
        // so sloped contact matches Platform3 landing math.
        return SolidObjectParams.of(PLATFORM_HALF_WIDTH, 0, 0);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    /**
     * {@code Sonic_Balance} reads the stood-on object's {@code obActWid}
     * (docs/s1disasm/_incObj/01 Sonic.asm:423), which for this object is
     * {@link #ACTIVE_WIDTH} = {@code #200/2} = 100 -- not the {@code #96/2} =
     * 48 that {@code Ledge_ChkTouch} passes to {@code SlopeObject} as {@code d1}
     * (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:61) and
     * that {@link #getSolidParams()} models.
     *
     * <p>This override is required rather than merely tidy. The base
     * {@code getBalanceWidthPixels()} returns {@code getOnScreenHalfWidth()}
     * except for top-solid objects, where it returns
     * {@code getSolidParams().halfWidth()} on the premise that a
     * {@code PlatformObject} caller passes {@code obActWid} straight through as
     * {@code d1}. Most S1 platforms do; this one does not, so that fallback
     * intercepts and no {@code getOnScreenHalfWidth()} override could reach the
     * balance test.
     *
     * <p><b>{@code FixBugs} = 0.</b> {@code Ledge_Main} writes {@code #200/2}
     * on the un-fixed branch and {@code #96/2} under {@code FixBugs}
     * ({@code :37-48}); the disassembly's own comment argues 200 is too wide a
     * culling radius and "could cause wrapping issues". The shipped ROM, and
     * therefore every recorded trace, takes the 200 branch, so the engine models
     * 100. Under {@code FixBugs} = 1 the balance width would equal the collision
     * width and this override would be redundant.
     *
     * <p>Balance only; the ROM's {@code obRender}-based delete
     * ({@code :119-133}) is keyed on the same byte but this class culls through
     * {@code isInRangeAt} instead, which is a separate pre-existing divergence
     * and deliberately not touched here.
     */
    @Override
    public int getBalanceWidthPixels() {
        return ACTIVE_WIDTH;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM PlatformObject/Plat_NoXCheck_AltY (docs/s1disasm/sonic.lst 0x7B00-0x7B0A):
        //   sub.w d1,d0            ; d0 = platform_top - sonic_bottom_edge
        //   bhi.w  Plat_Exit       ; exit if d0 > 0 (Sonic above platform)
        //   cmpi.w #-16,d0
        //   blo.w  Plat_Exit       ; UNSIGNED lower vs $FFF0 -> also exits when d0 = 0
        // The blo (unsigned) comparison makes the exact-touch case d0 = 0 (engine
        // distY == 0) a NON-landing: the landing band is d0 in [-16,-1] (strict
        // penetration), not [-16,0]. Verified by BizHawk capture of the GHZ1
        // collapsing-ledge landing (BK2 3361 d0=0 keeps falling; BK2 3362 d0=-9
        // lands) — engine was landing one frame early at the touch frame.
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM Ledge_ChkTouch passes #96/2 (= 0x30) directly as SlopeObject's d1
        // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:31-33),
        // and SlopeObject does the X-range check on that d1 with no narrowing
        // (docs/s1disasm/_incObj/sub PlatformObject.asm:133-139). PLATFORM_HALF_WIDTH
        // (0x30) is therefore already the standable top-landing width and must not
        // receive the generic SolidObjectFull +$B narrowing (which would shrink it
        // to 0x25). Without this, a player falling onto the ledge near its left/right
        // edge lands several frames late: s1_ghz1 f2790 (Sonic at relX=2 within the
        // ledge) was rejected as out-of-width until relX=12 at f2793, so the engine
        // overshot the landing by 3 frames. Matches the sibling collapsing FLOOR
        // (Sonic1CollapsingFloorObjectInstance) which opts in for the same reason.
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA;
    }

    @Override
    public boolean isSlopeFlipped() {
        // SlopeObject checks obRender bit 0 for x-flip.
        return (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public int getSlopeBaseline() {
        // ROM: SlopeObject uses absolute slope values (surfaceY = obY - slopeSample).
        // No baseline subtraction — unlike SolidObject2F which subtracts slopeData[0].
        return 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (routine == 2 && contact.standing()) {
            // Player stepped on ledge: transition to routine 4 (Ledge_Collapse)
            // From disassembly: addq.b #2,obRoutine(a0) in PlatformObject
            routine = 4;
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return false;
        }
        if (routine <= 4) {
            return true;
        }
        // Disassembly parity:
        // Routine 6 remains collidable while ledge_collapse_flag is set
        // (Ledge_Display -> loc_82D0 runs Ledge_WalkOff/SlopeObject2).
        return routine == 6 && collapseFlag;
    }

    @Override
    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        // docs/s1disasm/.../1A, 53 Collapsing Ledges and Floors.asm:67-82
        // Ledge_OnPlatform jumps to Fragmentate_GHZLedge_NoReset when the
        // timer is zero, so the transition frame does not run Ledge_WalkOff or
        // SlopeObject_AssumeStoodOn even though Sonic remains attached.
        return transitionFrameSlopeSkip;
    }

    @Override
    public boolean sampleSlopeOnRideExit(PlayableEntity player) {
        // ROM Ledge_FragmentPiece .delayCollapse decrements objoff_38, calls
        // Ledge_WalkOff (including SlopeObject_AssumeStoodOn), and only then
        // clears Status_OnObj when the delay reached zero. The object update and
        // solid checkpoint are split in the engine, so expose that ROM state to
        // the generic exit path rather than detaching before the final sample.
        return routine == 6 && collapseFlag == false && collapseDelay <= 0;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed() && isOnScreenX(spawn.x(), 320);
    }

    private boolean isOnScreenX(int objectX, int range) {
        return isInRangeAt(objectX);
    }

    /**
     * Fragment object spawned when the collapsing ledge breaks apart.
     * Each fragment renders a single piece from the smash mapping frame and
     * falls with gravity after its individual delay expires.
     * <p>
     * From disassembly (sonic.asm lines 4657-4683):
     * - obRoutine = 6 (Ledge_Display)
     * - obMap = pointer to the specific piece's mapping data
     * - Inherits position, graphics, priority, width from parent
     * - ledge_timedelay = delay from CFlo_Data1
     * - Falls via ObjectFall when delay reaches 0
     */
    public static class CollapsingLedgeFragmentInstance extends AbstractFallingFragment
            implements RewindRecreatable {

        private static final int PIECE_MASK = 0x1F;
        private static final int FRAME_SHIFT = 5;
        private static final int FRAME_MASK = 0x03;

        private int smashFrameIndex;
        private int pieceIndex;
        private boolean hFlip;

        CollapsingLedgeFragmentInstance(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(),
                    smashFrameIndex(spawn),
                    pieceIndex(spawn),
                    spawn.rawYWord(),
                    spawn.renderFlags());
        }

        public CollapsingLedgeFragmentInstance(int parentX, int parentY,
                                               int smashFrameIndex, int pieceIndex,
                                               int delay, int renderFlags) {
            super(fragmentSpawn(parentX, parentY, smashFrameIndex, pieceIndex, delay, renderFlags),
                    "LedgeFragment", delay, PRIORITY);
            this.smashFrameIndex = smashFrameIndex;
            this.pieceIndex = pieceIndex;
            this.hFlip = (renderFlags & 0x01) != 0;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new CollapsingLedgeFragmentInstance(ctx.spawn());
        }

        @Override
        protected boolean shouldDeleteBeforeFall() {
            // ROM `.fragmentFall` deletes on the previous frame's render flag; see
            // Sonic1CollapsingLedgeObjectInstance.updateFragmentFall for the branch
            // and the FixBugs note. Evaluating it before the fall is what reads that
            // previous-frame position.
            return !isWithinBuildSpritesBounds(
                    getX(), getY(), LEDGE_ACT_WIDTH, FRAGMENT_HEIGHT);
        }

        @Override
        protected boolean shouldDeleteAfterFall() {
            // The ROM lifetime is entirely the render-flag test above; there is no
            // second, wider margin check in Ledge_FragmentPiece.
            return false;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.COLLAPSING_LEDGE);
            if (renderer == null) {
                return;
            }

            // Render just this piece from the smash frame (inheriting parent's X-flip)
            renderer.drawFramePieceByIndex(smashFrameIndex, pieceIndex, getX(), getY(), hFlip, false);
        }

        private static ObjectSpawn fragmentSpawn(
                int x,
                int y,
                int smashFrameIndex,
                int pieceIndex,
                int delay,
                int renderFlags) {
            return new ObjectSpawn(x, y, Sonic1ObjectIds.COLLAPSING_LEDGE,
                    fragmentSubtype(smashFrameIndex, pieceIndex),
                    renderFlags,
                    false,
                    delay);
        }

        private static int fragmentSubtype(int smashFrameIndex, int pieceIndex) {
            return ((smashFrameIndex & FRAME_MASK) << FRAME_SHIFT)
                    | (pieceIndex & PIECE_MASK);
        }

        private static int smashFrameIndex(ObjectSpawn spawn) {
            return (spawn.subtype() >> FRAME_SHIFT) & FRAME_MASK;
        }

        private static int pieceIndex(ObjectSpawn spawn) {
            return spawn.subtype() & PIECE_MASK;
        }
    }
}
