package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractFallingFragment;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRomZoneRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x53 - Collapsing Floors (MZ, SLZ, SBZ).
 * <p>
 * A platform that collapses when Sonic stands on it. After a brief delay,
 * the floor splits into 8 fragment objects that fall with gravity. Used in
 * Marble Zone, Star Light Zone, and Scrap Brain Zone.
 * <p>
 * Zone-specific art:
 * <ul>
 *   <li>MZ: Nem_MzBlock at ArtTile_MZ_Block ($2B8), palette 2. Frames 0/1.</li>
 *   <li>SLZ: Nem_SlzBlock at ArtTile_SLZ_Collapsing_Floor ($4E0), palette 2. Frames 2/3.</li>
 *   <li>SBZ: Nem_SbzFloor at ArtTile_SBZ_Collapsing_Floor ($3F5), palette 2. Frames 0/1.</li>
 * </ul>
 * <p>
 * Subtypes:
 * <ul>
 *   <li>Bit 7: If set, floor X-flips to face the player when standing on it</li>
 *   <li>Bit 0: Fragment data table select (0 = CFlo_Data2, 1 = CFlo_Data3)</li>
 * </ul>
 * <p>
 * State machine (obRoutine values):
 * <ul>
 *   <li>0 (CFlo_Main): Initialize - set zone-specific art, render flags, priority, delay</li>
 *   <li>2 (CFlo_Touch): Platform collision, collapse timer countdown, optional X-flip</li>
 *   <li>4 (CFlo_Collapse): Collapse initiated, countdown with WalkOff behavior</li>
 *   <li>6 (CFlo_Display): Fragment falling - WalkOff while collapse_flag set, ObjectFall when delay=0</li>
 *   <li>8 (CFlo_Delete): Delete when offscreen</li>
 *   <li>$A (CFlo_WalkOff): ExitPlatform + MvSonicOnPtfm2 + RememberState</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/53 Collapsing Floors.asm
 */
public class Sonic1CollapsingFloorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRomZoneRewindRecreatable {

    // From disassembly: move.w #$20,d1 (half-width for PlatformObject)
    private static final int PLATFORM_HALF_WIDTH = 0x20;

    // MvSonicOnPtfm2 hardcodes "subi.w #9,d0" for the ground half-height.
    private static final int PLATFORM_HALF_HEIGHT = 9;

    // From disassembly: move.b #$44,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x44;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.b #7,cflo_timedelay(a0)
    private static final int INITIAL_COLLAPSE_DELAY = 7;

    // Gravity constant from ObjectFall: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Fragment count: d1 = 7 -> dbf loop = 8 iterations (moveq #7,d1 at loc_846C)
    private static final int FRAGMENT_COUNT = 8;

    // Intact mapping frame index (always 0 in the zone-specific sheet)
    private static final int FRAME_INTACT = 0;
    // Smash mapping frame index (always 1 in the zone-specific sheet)
    private static final int FRAME_SMASH = 1;

    // Disintegration delay data from CFlo_Data2 (8 bytes)
    // Used when subtype bit 0 = 0
    // From sonic.asm: CFlo_Data2: dc.b $1E, $16, $E, 6, $1A, $12, $A, 2
    private static final int[] CFLO_DATA2 = {
            0x1E, 0x16, 0x0E, 0x06, 0x1A, 0x12, 0x0A, 0x02
    };

    // Disintegration delay data from CFlo_Data3 (8 bytes)
    // Used when subtype bit 0 = 1
    // From sonic.asm: CFlo_Data3: dc.b $16, $1E, $1A, $12, 6, $E, $A, 2
    private static final int[] CFLO_DATA3 = {
            0x16, 0x1E, 0x1A, 0x12, 0x06, 0x0E, 0x0A, 0x02
    };

    // The subtype byte from ROM placement
    private int subtype;

    // The art key for rendering (zone-specific)
    private final String artKey;

    // Routine state: 0=init, 2=touch, 4=collapse, 6=display(fragment), 8=delete
    private int routine;

    // Current position (center coordinates)
    private int x;
    private int y;

    // Collapse timer (cflo_timedelay = objoff_38)
    private int collapseDelay;

    // Collapse flag (cflo_collapse_flag = objoff_3A): set when player activates collapse
    private boolean collapseFlag;

    // 16.16 fall state for ObjectFall (routine 6). y is synced to/from motion.y.
    private final SubpixelMotion.State fallMotion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    // Whether fragments have been spawned
    private boolean fragmented;

    // ROM enters routine 4 (CFlo_OnPlatform) via PlatformObject's addq #2,obRoutine
    // during the routine-2 (CFlo_ChkTouch) execution, so the routine-4 code (which
    // sets the flag and decrements the timer) only runs on the FOLLOWING frame.
    // The engine's onSolidContact sets routine=4 during the solid pass, BEFORE the
    // object's update dispatches that frame, so without this guard routine 4 would
    // run (and decrement) the same frame the player lands -- one frame early,
    // making the collapse timer reach 0 (and the drop fire) one frame before ROM
    // (MZ3 f2173 vs ROM f2174). Skip the first routine-4 update to match ROM.

    // X-flip state (obRender bit 0). Can change dynamically for subtype bit 7 objects.
    private boolean hFlip;

    public Sonic1CollapsingFloorObjectInstance(ObjectSpawn spawn, int zoneIndex) {
        super(spawn, "CollapsingFloor");
        this.subtype = spawn.subtype() & 0xFF;
        this.x = spawn.x();
        this.y = spawn.y();
        this.collapseDelay = INITIAL_COLLAPSE_DELAY;
        this.collapseFlag = false;
        this.fragmented = false;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;

        // Zone-specific art key selection
        // From disassembly CFlo_Main: checks v_zone for SLZ and SBZ
        this.artKey = switch (zoneIndex) {
            case Sonic1Constants.ZONE_SLZ -> ObjectArtKeys.SLZ_COLLAPSING_FLOOR;
            case Sonic1Constants.ZONE_SBZ -> ObjectArtKeys.SBZ_COLLAPSING_FLOOR;
            default -> ObjectArtKeys.MZ_COLLAPSING_FLOOR; // MZ default
        };

        // Skip init routine, start at routine 2 (CFlo_Touch)
        this.routine = 2;
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
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case 2 -> updateTouch(player);
            case 4 -> updateCollapse(player);
            case 6 -> updateDisplay(player);
            case 8 -> destroyWithWindowGatedRespawn();
            default -> { }
        }
        updateDynamicSpawn(x, y);
    }

    /**
     * Routine 2 (CFlo_Touch): Platform collision with collapse trigger.
     * <p>
     * From disassembly:
     * <pre>
     *     tst.b  cflo_collapse_flag(a0)  ; has Sonic touched the object?
     *     beq.s  .solid                  ; if not, branch
     *     tst.b  cflo_timedelay(a0)      ; has time delay reached zero?
     *     beq.w  CFlo_Fragment           ; if yes, branch
     *     subq.b #1,cflo_timedelay(a0)   ; subtract 1 from time
     * .solid:
     *     move.w #$20,d1
     *     bsr.w  PlatformObject
     *     tst.b  obSubtype(a0)
     *     bpl.s  .remstate               ; skip X-flip if subtype bit 7 clear
     *     btst   #3,obStatus(a1)
     *     beq.s  .remstate
     *     bclr   #0,obRender(a0)
     *     move.w obX(a1),d0
     *     sub.w  obX(a0),d0
     *     bcc.s  .remstate               ; if player X >= object X, don't flip
     *     bset   #0,obRender(a0)         ; player is to the left, flip
     * </pre>
     */
    private void updateTouch(AbstractPlayableSprite player) {
        if (collapseFlag) {
            if (collapseDelay <= 0) {
                // CFlo_Fragment (line 125): clears collapse_flag, then fragments
                performFragment(true);
                return;
            }
            collapseDelay--;
        }
        // PlatformObject + RememberState handled via SolidObjectProvider

        // Subtype bit 7: X-flip to face the player
        if ((subtype & 0x80) != 0 && player != null) {
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null && objectManager.isAnyPlayerRiding(this)) {
                // bclr #0,obRender(a0) - default to no flip
                hFlip = false;
                // move.w obX(a1),d0 / sub.w obX(a0),d0 / bcc.s .remstate
                int playerX = player.getCentreX();
                if (playerX < x) {
                    // bset #0,obRender(a0) - player is to the left, flip
                    hFlip = true;
                }
            }
        }
    }

    /**
     * Routine 4 (CFlo_Collapse): Entered when player stands on the platform.
     * <p>
     * From disassembly:
     * <pre>
     *     tst.b  cflo_timedelay(a0)
     *     beq.w  loc_8458                ; timer expired -> fragment
     *     move.b #1,cflo_collapse_flag(a0)
     *     subq.b #1,cflo_timedelay(a0)
     * </pre>
     * Falls through to CFlo_WalkOff: ExitPlatform + MvSonicOnPtfm2 + RememberState.
     */
    private void updateCollapse(AbstractPlayableSprite player) {
        // ROM CFlo_OnPlatform is unconditional: test the timer, and while it is
        // non-zero set the flag AND decrement, on every frame routine 4 runs
        // (docs/s1disasm/_incObj/"1A, 53 Collapsing Ledges and Floors.asm":203-208).
        // PlatformObject advancing obRoutine to 4 during routine 2 already costs the
        // first frame, so skipping the first decrement as well collapsed the floor one
        // frame late. Verified on two recordings: the ROM takes eight frames from the
        // first routine-4 body to fragmentation in both.
        if (collapseDelay <= 0) {
            // loc_8458 (line 128): enters fragment code WITHOUT clearing collapse_flag.
            // The flag remains set so routine 6 runs WalkOff behavior (loc_8402).
            performFragment(false);
            return;
        }
        collapseFlag = true;
        collapseDelay--;
        // CFlo_WalkOff (ExitPlatform + MvSonicOnPtfm2) handled by SolidContacts
    }

    /**
     * Routine 6 (CFlo_Display): Fragment display and falling.
     * <p>
     * Three paths depending on state:
     * <ol>
     *   <li>Timer > 0 and collapse_flag NOT set: decrement timer, display only</li>
     *   <li>Timer > 0 and collapse_flag set: WalkOff behavior, detach player when timer=0</li>
     *   <li>Timer = 0: ObjectFall (gravity) + display, delete when offscreen</li>
     * </ol>
     * <p>
     * From disassembly (CFlo_Display / loc_8402 / CFlo_TimeZero):
     * <pre>
     *     tst.b  cflo_timedelay(a0)
     *     beq.s  CFlo_TimeZero
     *     tst.b  cflo_collapse_flag(a0)
     *     bne.w  loc_8402
     *     subq.b #1,cflo_timedelay(a0)
     *     bra.w  DisplaySprite
     * loc_8402:
     *     subq.b #1,cflo_timedelay(a0)
     *     bsr.w  CFlo_WalkOff
     *     lea    (v_player).w,a1
     *     btst   #3,obStatus(a1)
     *     beq.s  loc_842E
     *     tst.b  cflo_timedelay(a0)
     *     bne.s  locret_843A
     *     bclr   #3,obStatus(a1)
     *     bclr   #5,obStatus(a1)
     *     move.b #id_Run,obPrevAni(a1)
     * loc_842E:
     *     move.b #0,cflo_collapse_flag(a0)
     *     move.b #6,obRoutine(a0)
     * </pre>
     */
    private void updateDisplay(AbstractPlayableSprite player) {
        if (collapseDelay > 0) {
            if (!collapseFlag) {
                // Simple countdown, display only
                collapseDelay--;
                return;
            }

            // loc_8402: WalkOff with collapse flag set
            collapseDelay--;

            ObjectManager objectManager = services().objectManager();
            boolean playerRiding = objectManager != null && player != null
                    && objectManager.isAnyPlayerRiding(this);

            // ROM `.delayCollapse` runs CFlo_WalkOff -> ExitPlatform unconditionally,
            // and ExitPlatform releases the rider on its X band alone; the routine then
            // sees Status_OnObj clear and falls into `.startCollapse`. A rider whose ride
            // link is already gone reaches the same place, because ExitPlatform returns
            // immediately when the bit is clear. Both therefore take this branch --
            // testing only the ride link left an out-of-band rider carried indefinitely,
            // because the shared pass is deliberately held off while the flag is set.
            boolean walkedOffBand = player != null && exitsPlatformByX(player.getCentreX(), x);
            if (!playerRiding || walkedOffBand) {
                // CFlo_FragmentPiece .delayCollapse calls CFlo_WalkOff even
                // when this fragment is no longer the player's current support.
                // ExitPlatform owns a global Status_OnObj clear: a later ROM
                // slot can therefore clear the bit that an earlier slot just
                // established, while leaving standonobject pointing at that
                // earlier support. Preserve that odd but observable ordering;
                // Sonic's next control tick turns the cleared bit into the
                // one-frame airborne state before he can land again.
                if (player != null && exitsPlatform(
                        player.getAir(), player.getCentreX(), x)) {
                    if (!player.getAir() && player.isOnObject()) {
                        objectManager.solidContacts()
                                .noteGroundedOnObjectClearedBeforePhysics(player, this);
                    }
                    player.setOnObject(false);
                }
                // loc_842E: Player walked off - clear flag, stay in routine 6
                collapseFlag = false;
                routine = 6;
            } else if (collapseDelay <= 0) {
                // Timer expired with player still standing — ROM `.delayCollapse`
                // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:
                // 233-240): `bclr #3,obStatus(a1)` clears Sonic's on-platform flag
                // so he loses support and goes airborne, plus `bclr #5` (pushing).
                // clearRidingObject only drops the engine's riding-state link; it
                // leaves the player's onObject flag set and grounded, so he never
                // goes airborne. Mirror `bclr #3` directly: clear onObject + set
                // airborne (MZ3 f2174: ROM air 0->1 here).
                objectManager.clearRidingObject(player);
                // ROM does `bclr #3,obStatus(a1)` and nothing else: Status_InAir is not
                // written here, so the rider is still grounded for the rest of this
                // frame and only becomes airborne on his next control tick, when the
                // ground check finds no support. Setting it here made him airborne a
                // frame early once the collapse timing itself was corrected -- the
                // immediate set was compensating for a fragmentation that ran late.
                if (!player.getAir() && player.isOnObject()) {
                    objectManager.solidContacts()
                            .noteGroundedOnObjectClearedBeforePhysics(player, this);
                }
                player.setOnObject(false);
                // move.b #id_Run,obPrevAni(a1) - restart Sonic's animation
                // loc_842E: clear flag
                collapseFlag = false;
                routine = 6;
            }
            // locret_843A: return while timer > 0 and player still riding
            return;
        }

        // ROM CFlo_FragmentPiece `.fragmentFall` (docs/s1disasm/_incObj/1A, 53
        // Collapsing Ledges and Floors.asm:250-263). FixBugs = 0
        // (docs/s1disasm/sonic.asm:20) selects `bsr ObjectFall / bsr DisplaySprite /
        // tst.b obRender(a0) / bpl CFlo_Delete`, so the delete decision is the LAST
        // BuildSprites pass's render flag -- this frame's pre-fall position. (The
        // FixBugs = 1 branch only reorders the test ahead of DisplaySprite to avoid
        // the display-and-delete null dereference; the delete frame is unchanged.)
        //
        // CFlo_Main sets obActWid = 136/2 (line 172) and never sets
        // sprite_customheight_bit, so BuildSprites uses its fixed 32px
        // `.assumeHeight` Y band (docs/s1disasm/_inc/BuildSprites.asm:84-91).
        // FragmentatePlatform copies obActWid and obRender to every fragment
        // (lines 335-340), so parent and fragments share this band.
        boolean renderedLastFrame =
                isWithinBuildSpritesBounds(x, y, CFLO_ACT_WIDTH, ASSUMED_HEIGHT);
        applyObjectFall();
        if (!renderedLastFrame) {
            destroyWithWindowGatedRespawn();
        }
    }

    // CFlo_Main obActWid: `move.b #136/2,obActWid(a0)`
    // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:172).
    private static final int CFLO_ACT_WIDTH = 136 / 2;

    // BuildSprites `.assumeHeight` band, used because CFlo_Main never sets
    // sprite_customheight_bit (docs/s1disasm/_inc/BuildSprites.asm:84-91).
    private static final int ASSUMED_HEIGHT = 32;

    /** ExitPlatform's X band alone, without its airborne short-circuit. */
    static boolean exitsPlatformByX(int playerX, int objectX) {
        int relX = (short) (playerX - objectX + PLATFORM_HALF_WIDTH);
        return relX < 0 || relX >= PLATFORM_HALF_WIDTH * 2;
    }

    static boolean exitsPlatform(boolean playerAirborne, int playerX, int objectX) {
        if (playerAirborne) {
            return true;
        }
        // ExitPlatform uses 16-bit word arithmetic and an exclusive right
        // edge: relX = playerX - objectX + halfWidth; 0 <= relX < width.
        int relX = (short) (playerX - objectX + PLATFORM_HALF_WIDTH);
        return relX < 0 || relX >= PLATFORM_HALF_WIDTH * 2;
    }

    /**
     * Fragment the collapsing floor into 8 individual pieces.
     * <p>
     * Shared fragment code from sonic.asm lines 4629-4683:
     * <pre>
     *     lea    (CFlo_Data2).l,a4       ; default delay table
     *     btst   #0,obSubtype(a0)
     *     beq.s  loc_846C
     *     lea    (CFlo_Data3).l,a4       ; alternate delay table
     * loc_846C:
     *     moveq  #7,d1                   ; 8 fragments (0-7)
     *     addq.b #1,obFrame(a0)          ; advance to smash mapping frame
     * </pre>
     * Each fragment object spawns with its delay from the selected data table.
     * First fragment reuses this object; remaining 7 are new dynamic objects.
     * Plays sfx_Collapse.
     *
     * @param clearFlag true when called from Routine 2 (CFlo_Fragment, line 125: clears flag),
     *                  false when called from Routine 4 (loc_8458, line 128: preserves flag)
     */
    private void performFragment(boolean clearFlag) {
        if (fragmented) {
            return;
        }
        fragmented = true;
        if (clearFlag) {
            // CFlo_Fragment: move.b #0,cflo_collapse_flag(a0)
            collapseFlag = false;
        }
        // When !clearFlag (routine 4 entry), collapse_flag remains set.
        // This causes routine 6 (CFlo_Display) to run the WalkOff path (loc_8402)
        // which keeps the player supported on the first fragment until delay expires.

        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (sheet == null || renderer == null || FRAME_SMASH >= sheet.getFrameCount()) {
            return;
        }

        // Select delay table based on subtype bit 0
        // From disassembly: btst #0,obSubtype(a0)
        int[] delays = ((subtype & 0x01) != 0) ? CFLO_DATA3 : CFLO_DATA2;

        // Convert self to fragment (routine 6, first piece)
        this.routine = 6;
        this.collapseDelay = delays[0];

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
        // Spawn remaining 7 fragments as dynamic objects
        int maxFragments = Math.min(FRAGMENT_COUNT, delays.length);
        for (int i = 1; i < maxFragments; i++) {
            final int idx = i;
            final int delay = delays[i];
            spawnFreeChild(() -> new CollapsingFloorFragmentInstance(
                    x, y, FRAME_SMASH, idx, delay, hFlip, artKey));
        }

        // Play collapse sound: move.w #sfx_Collapse,d0 / jmp (QueueSound2).l
        services().playSfx(Sonic1Sfx.COLLAPSE.id);
    }

    /**
     * ObjectFall subroutine: applies gravity to Y velocity and updates position.
     * Delegates to {@link SubpixelMotion#objectFall(SubpixelMotion.State, int)}.
     */
    private void applyObjectFall() {
        fallMotion.y = y;
        SubpixelMotion.objectFall(fallMotion, GRAVITY);
        y = fallMotion.y;
    }

    /**
     * Destroy this object and allow it to respawn when camera leaves the area.
     * Uses DeleteObject behavior (not RememberState).
     */
    private void destroyWithWindowGatedRespawn() {
        if (!isDestroyed()) {
            ObjectManager objectManager = services().objectManager();
            ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
        }
        setDestroyed(true);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (routine == 6 && fragmented) {
            // Fragment: render only piece 0 from the smash frame
            renderer.drawFramePieceByIndex(FRAME_SMASH, 0, x, y, hFlip, false);
        } else {
            // Normal: render the intact floor
            renderer.drawFrameIndex(FRAME_INTACT, x, y, hFlip, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (isDestroyed()) {
            return;
        }
        // Draw platform collision bounds
        ctx.drawRect(getX(), getY(), PLATFORM_HALF_WIDTH, 8,
                0.6f, 0.4f, 0.0f);
        ctx.drawWorldLabel(getX(), getY(), -2,
                String.format("CFloor r%d d=%d %s",
                        routine, collapseDelay,
                        collapseFlag ? "COLLAPSE" : ""),
                DebugColor.ORANGE);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // PLATFORM_HALF_HEIGHT (9) models the continued-riding surface obY-9 from
        // CFlo_WalkOff -> MvSonicOnPtfm2 (subi.w #9,d0). The first landing instead
        // uses CFlo_ChkTouch -> PlatformObject's obY-8 surface, recovered via
        // getTopLandingSnapAdjustment().
        return SolidObjectParams.of(PLATFORM_HALF_WIDTH, PLATFORM_HALF_HEIGHT, PLATFORM_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    /**
     * {@code Sonic_Balance} reads the stood-on object's {@code obActWid}
     * (docs/s1disasm/_incObj/01 Sonic.asm:423), which {@code CFlo_Main} sets to
     * {@code #136/2} = 68 -- already modelled here as {@link #CFLO_ACT_WIDTH}
     * for the BuildSprites delete bound, and not the {@code #64/2} = 32 that
     * {@code CFlo_ChkTouch} passes to {@code PlatformObject} as {@code d1}
     * (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:172,184)
     * and that {@link #getSolidParams()} models.
     *
     * <p>Required rather than tidy, for the same reason as the GHZ collapsing
     * ledge: the base {@code getBalanceWidthPixels()} falls back to
     * {@code getSolidParams().halfWidth()} for top-solid objects, on the premise
     * that a {@code PlatformObject} caller passes {@code obActWid} straight
     * through as {@code d1}. This one passes a literal instead, so the fallback
     * intercepts and only an override at this accessor reaches the balance test.
     *
     * <p>Balance only. The on-screen half-width is deliberately left alone
     * because this class already models the ROM byte for its own cull through
     * {@link #CFLO_ACT_WIDTH}; the duplication is noted rather than unified so
     * that this change alters exactly one decision.
     */
    @Override
    public int getBalanceWidthPixels() {
        return CFLO_ACT_WIDTH;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // CFlo_ChkTouch passes #64/2 directly as PlatformObject's d1
        // (docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:184-185),
        // so the collision half-width is already the standable width and must not
        // receive the generic SolidObjectFull +$B narrowing.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM PlatformObject/Plat_NoXCheck_AltY gates the land band with an
        // UNSIGNED cmpi.w #-16,d0 / blo (docs/s1disasm/_incObj/sub PlatformObject.asm:51-52),
        // which rejects the exact-touch case d0 = 0: the standable band is
        // d0 in [-16,-1] (strict penetration). Combined with the obY-8 detection
        // offset below, this lands on the same frame as ROM.
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // PlatformObject builds its entry surface from obY-8 and snaps via
        // add.w d0,d2 / addq.w #3,d2 (docs/s1disasm/_incObj/sub PlatformObject.asm:37-65),
        // while continued riding uses CFlo_WalkOff -> MvSonicOnPtfm2's obY-9 surface
        // (modeled by PLATFORM_HALF_HEIGHT = 9). This -1 recovers the obY-8 surface
        // for the first landing snap and, via the controller's detection-band offset,
        // makes the engine land on ROM's frame rather than one frame late.
        return -1;
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
        // Routine 6: remains collidable while collapse_flag is set
        // (CFlo_Display -> loc_8402 runs CFlo_WalkOff/MvSonicOnPtfm2)
        return routine == 6 && collapseFlag;
    }

    /**
     * True on the routine-4 frame where the collapse timer has reached zero and
     * the floor is about to fragment via {@code Fragmentate_8x2Floor_NoReset}.
     * <p>
     * ROM {@code CFlo_OnPlatform} (docs/s1disasm/_incObj/1A, 53 Collapsing
     * Ledges and Floors.asm:203-205): when {@code collapsible_timedelay} is zero
     * the routine branches to {@code Fragmentate_8x2Floor_NoReset} and
     * <em>skips</em> the fall-through to {@code CFlo_WalkOff} (ExitPlatform +
     * MvSonicOnPtfm2, lines 210-216) that every other routine-4 frame runs. So
     * on the fragment frame the rider is neither re-seated nor unseated -- it
     * keeps {@code Status_OnObj} exactly as it was, and is only walked off the
     * next frame by routine 6's {@code CFlo_FragmentPiece .delayCollapse} path
     * (lines 228-231). This mirrors the S3K {@code Obj_CollapsingPlatform}
     * transition-frame skip modelled by
     * {@code Sonic3kCollapsingPlatformObjectInstance.suppressSlopeSampleThisFrame}.
     * <p>
     * The engine evaluates the continued-riding solid pass AFTER each object's
     * {@code update()}, so {@code updateCollapse} has already decremented the
     * timer to zero (still routine 4, not yet fragmented) by the time the solid
     * pass runs on this frame: the predicate is therefore correct precisely on
     * the frame the rider must be held in place. The discriminator is ROM state
     * (routine + {@code objoff_38} timer + collapse flag), never zone/encounter:
     * it fires for SLZ's walk-off rider and MZ's collapse-release rider alike,
     * and is a no-op for any rider already off the floor's solid band.
     */
    private boolean pendingNoResetFragment() {
        // The fragmentation frame itself: routine 4 reaches Fragmentate_..._NoReset
        // without ever running CFlo_WalkOff, so the rider is not unseated here.
        if (routine == 4 && collapseFlag && collapseDelay <= 0 && !fragmented) {
            return true;
        }
        // ROM CFlo_FragmentPiece `.delayCollapse`
        // (docs/s1disasm/_incObj/"1A, 53 Collapsing Ledges and Floors.asm":227-243):
        // while collapsible_flag is still set, piece 0 keeps carrying the rider every
        // frame -- CFlo_WalkOff's ExitPlatform decides when he walks off, and the
        // routine itself only clears Status_OnObj once the decremented timer hits
        // zero. The engine's own `.delayCollapse` port already owns both of those
        // exits, so the shared solid pass must not unseat him first: without this the
        // hold lasted exactly the fragmentation frame and the rider dropped one frame
        // early.
        return routine == 6 && collapseFlag;
    }

    @Override
    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        return pendingNoResetFragment();
    }

    @Override
    public boolean defersAirborneRiderUnseatThisFrame(PlayableEntity player) {
        return pendingNoResetFragment();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (routine == 2 && contact.standing()) {
            // Player stepped on floor: transition to routine 4 (CFlo_Collapse)
            // From disassembly: addq.b #2,obRoutine(a0) in PlatformObject.
            // ROM runs routine 4 only on the NEXT frame (PlatformObject sets the
            // routine during routine-2 execution); mark the transition so the
            // routine-4 update skips this frame, matching that one-frame deferral.
            routine = 4;
        }
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // Routine 4 is CFlo_OnPlatform. Its solid path is CFlo_WalkOff ->
        // ExitPlatform, not the routine-2 PlatformObject entry check. When
        // Sonic_AnglePos has just set Status_InAir, ExitPlatform immediately
        // clears this floor's standing bit and returns; it cannot fall through
        // to PlatformObject and land Sonic again in the same object slot.
        return routine == 4;
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (routine == 4 && player != null && player.getAir()) {
            // ExitPlatform: move.b #2,obRoutine(a0). collapseFlag intentionally
            // survives; routine 2 consumes it on the next object execution.
            routine = 2;
        }
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
     * Fragment object spawned when the collapsing floor breaks apart.
     * <p>
     * Each fragment renders a single piece from the smash mapping frame and
     * falls with gravity after its individual delay expires.
     * <p>
     * From disassembly (sonic.asm lines 4657-4683):
     * <ul>
     *   <li>obRoutine = 6 (CFlo_Display)</li>
     *   <li>Inherits position, graphics, priority, width from parent</li>
     *   <li>cflo_timedelay = delay from CFlo_Data2/CFlo_Data3</li>
     *   <li>Falls via ObjectFall when delay reaches 0</li>
     * </ul>
     */
    public static class CollapsingFloorFragmentInstance extends AbstractFallingFragment
            implements RewindRecreatable {

        private static final int PIECE_MASK = 0x0F;
        private static final int FRAME_SHIFT = 4;
        private static final int FRAME_MASK = 0x03;
        private static final int ART_SHIFT = 6;
        private static final int ART_MASK = 0x03;

        private int smashFrameIndex;
        private int pieceIndex;
        private boolean hFlip;
        private String artKey;

        CollapsingFloorFragmentInstance(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(),
                    smashFrameIndex(spawn),
                    pieceIndex(spawn),
                    spawn.rawYWord(),
                    (spawn.renderFlags() & 0x01) != 0,
                    artKey(spawn));
        }

        public CollapsingFloorFragmentInstance(int parentX, int parentY,
                                               int smashFrameIndex, int pieceIndex,
                                               int delay, boolean hFlip, String artKey) {
            super(fragmentSpawn(parentX, parentY, smashFrameIndex, pieceIndex, delay, hFlip, artKey),
                    "CFloFragment", delay, PRIORITY);
            this.smashFrameIndex = smashFrameIndex;
            this.pieceIndex = pieceIndex;
            this.hFlip = hFlip;
            this.artKey = artKey;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new CollapsingFloorFragmentInstance(ctx.spawn());
        }

        @Override
        protected boolean shouldDeleteBeforeFall() {
            // ROM `.fragmentFall` deletes on the previous frame's render flag; see
            // Sonic1CollapsingFloorObjectInstance.updateFragmentFall for the branch
            // and the FixBugs note.
            return !isWithinBuildSpritesBounds(
                    getX(), getY(), CFLO_ACT_WIDTH, ASSUMED_HEIGHT);
        }

        @Override
        protected boolean shouldDeleteAfterFall() {
            // The ROM lifetime is entirely the render-flag test above.
            return false;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer == null) {
                return;
            }

            renderer.drawFramePieceByIndex(smashFrameIndex, pieceIndex, getX(), getY(), hFlip, false);
        }

        private static ObjectSpawn fragmentSpawn(
                int x,
                int y,
                int smashFrameIndex,
                int pieceIndex,
                int delay,
                boolean hFlip,
                String artKey) {
            return new ObjectSpawn(x, y, Sonic1ObjectIds.COLLAPSING_FLOOR,
                    fragmentSubtype(smashFrameIndex, pieceIndex, artKey),
                    hFlip ? 0x01 : 0,
                    false,
                    delay);
        }

        private static int fragmentSubtype(int smashFrameIndex, int pieceIndex, String artKey) {
            return ((artCode(artKey) & ART_MASK) << ART_SHIFT)
                    | ((smashFrameIndex & FRAME_MASK) << FRAME_SHIFT)
                    | (pieceIndex & PIECE_MASK);
        }

        private static int smashFrameIndex(ObjectSpawn spawn) {
            return (spawn.subtype() >> FRAME_SHIFT) & FRAME_MASK;
        }

        private static int pieceIndex(ObjectSpawn spawn) {
            return spawn.subtype() & PIECE_MASK;
        }

        private static String artKey(ObjectSpawn spawn) {
            return switch ((spawn.subtype() >> ART_SHIFT) & ART_MASK) {
                case 1 -> ObjectArtKeys.SLZ_COLLAPSING_FLOOR;
                case 2 -> ObjectArtKeys.SBZ_COLLAPSING_FLOOR;
                default -> ObjectArtKeys.MZ_COLLAPSING_FLOOR;
            };
        }

        private static int artCode(String artKey) {
            if (ObjectArtKeys.SLZ_COLLAPSING_FLOOR.equals(artKey)) {
                return 1;
            }
            if (ObjectArtKeys.SBZ_COLLAPSING_FLOOR.equals(artKey)) {
                return 2;
            }
            return 0;
        }
    }
}
