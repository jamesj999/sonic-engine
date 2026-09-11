package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x66 - Rotating Junction (SBZ).
 * <p>
 * A large rotating disc with a gap opening that can grab Sonic when he pushes
 * against it and the gap aligns with his position. Once grabbed, the disc
 * rotates Sonic around its center, then releases him with velocity when the
 * gap faces down or right.
 * <p>
 * The junction creates a child display object that renders the full circle
 * (mapping frame $10) behind the main disc, at priority 3.
 * <p>
 * <b>Subtypes:</b> The subtype byte is the switch index. When the corresponding
 * switch (f_switch) is pressed, the rotation direction reverses.
 * <ul>
 *   <li>Subtype 0x00: watch switch index 0</li>
 *   <li>Subtype 0x02: watch switch index 2</li>
 * </ul>
 * <p>
 * <b>Routines:</b>
 * <ul>
 *   <li>0 (Jun_Main): Initialize, create child display object</li>
 *   <li>2 (Jun_Action): Solid interaction, check pushing, grab Sonic if gap aligns</li>
 *   <li>4 (Jun_Display): Display-only child (RememberState)</li>
 *   <li>6 (Jun_Release): Move Sonic around disc, release when gap faces down/right</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/66 Rotating Junction.asm
 */
public class Sonic1JunctionObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    // SolidObject parameters: move.w #$30,d1 / move.w d1,d2 / move.w d2,d3 / addq.w #1,d3
    private static final int SOLID_HALF_WIDTH = 0x30;
    private static final int SOLID_AIR_HALF_HEIGHT = 0x30;
    private static final int SOLID_GROUND_HALF_HEIGHT = 0x31;
    // Jun_Main: move.b #96/2,obActWid(a0) for the parent
    // (66 SBZ Rotating Junction.asm:47-48). The #112/2 at :43 is the
    // display-only cover-up child, which is never solid.
    private static final int ACT_WIDTH = 0x30;

    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);

    // move.b #4,obPriority(a0) — main object priority
    private static final int PRIORITY_MAIN = 4;

    // move.b #3,obPriority(a1) — child display priority
    private static final int PRIORITY_CHILD = 3;

    // Frame timer initial value: move.w #$3C,objoff_30(a0)
    // Note: objoff_30 holds an initial value but obTimeFrame drives the animation
    // move.b #7,obTimeFrame(a0) — frame timer period
    private static final int FRAME_TIMER_PERIOD = 7;

    // Initial frame direction: move.b #1,jun_frame(a0)
    private static final int INITIAL_FRAME_DIRECTION = 1;

    // Mapping frame for child display: move.b #$10,obFrame(a1) = 16
    private static final int CHILD_FRAME = 0x10;

    // Grab check frame values:
    // moveq #$E,d1 (gap left) / moveq #7,d1 (gap right)
    private static final int GAP_FRAME_LEFT = 0x0E;
    private static final int GAP_FRAME_RIGHT = 0x07;

    // Release frame values:
    // cmpi.b #4,d0 (gap down) / cmpi.b #7,d0 (gap right)
    private static final int RELEASE_FRAME_DOWN = 4;
    private static final int RELEASE_FRAME_RIGHT = 7;

    // Release velocities:
    // move.w #$800,obVelY(a1) — downward release Y velocity
    private static final int RELEASE_VEL_Y_DOWN = 0x800;
    // move.w #$800,obVelX(a1) / move.w #$800,obVelY(a1) — rightward release
    private static final int RELEASE_VEL_X_RIGHT = 0x800;
    private static final int RELEASE_VEL_Y_RIGHT = 0x800;

    // Grab inertia: move.w #$800,obInertia(a1)
    private static final int GRAB_INERTIA = 0x800;

    // Jun_ChgPos position data table (16 entries, 2 bytes each: x_offset, y_offset)
    // From disassembly .data:
    // dc.b -$20, 0, -$1E, $E, -$18, $18, -$E, $1E
    // dc.b 0, $20, $E, $1E, $18, $18, $1E, $E
    // dc.b $20, 0, $1E, -$E, $18, -$18, $E, -$1E
    // dc.b 0, -$20, -$E, -$1E, -$18, -$18, -$1E, -$E
    private static final byte[] POSITION_DATA = {
            (byte) -0x20, (byte)  0x00,   // Frame  0
            (byte) -0x1E, (byte)  0x0E,   // Frame  1
            (byte) -0x18, (byte)  0x18,   // Frame  2
            (byte) -0x0E, (byte)  0x1E,   // Frame  3
            (byte)  0x00, (byte)  0x20,   // Frame  4: gap bottom
            (byte)  0x0E, (byte)  0x1E,   // Frame  5
            (byte)  0x18, (byte)  0x18,   // Frame  6
            (byte)  0x1E, (byte)  0x0E,   // Frame  7: gap right
            (byte)  0x20, (byte)  0x00,   // Frame  8
            (byte)  0x1E, (byte) -0x0E,   // Frame  9
            (byte)  0x18, (byte) -0x18,   // Frame 10
            (byte)  0x0E, (byte) -0x1E,   // Frame 11
            (byte)  0x00, (byte) -0x20,   // Frame 12: gap top
            (byte) -0x0E, (byte) -0x1E,   // Frame 13
            (byte) -0x18, (byte) -0x18,   // Frame 14
            (byte) -0x1E, (byte) -0x0E,   // Frame 15
    };

    private static final boolean DEBUG_VIEW_ENABLED = staticDebugViewEnabled();
    private static final DebugOverlayManager OVERLAY_MANAGER = staticDebugOverlay();
    private static final DebugColor DEBUG_COLOR = new DebugColor(100, 200, 100);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current routine: ACTION (normal) or RELEASE (Sonic grabbed). */
    private enum Routine { ACTION, RELEASE }
    private Routine routine = Routine.ACTION;

    /** Current mapping frame (0-15), controls gap position. */
    private int mappingFrame;

    /** Frame direction: +1 = clockwise, -1 = counter-clockwise. */
    private int frameDirection;

    /** Whether the switch reversal has been applied. */
    private boolean switchReversed;

    /** Switch index from subtype (jun_switch). */
    private int switchIndex;

    /** Frame animation timer (counts down from FRAME_TIMER_PERIOD). */
    private int frameTimer;

    /** Frame at which Sonic entered the gap (objoff_32). */
    private int grabFrame;

    /** Child display object. */
    private Sonic1JunctionChildInstance childInstance;

    public Sonic1JunctionObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Junction");

        // From Jun_Main:
        // move.b #1,jun_frame(a0) — initial frame direction (clockwise)
        this.frameDirection = INITIAL_FRAME_DIRECTION;
        // move.b obSubtype(a0),jun_switch(a0) — switch index from subtype
        this.switchIndex = spawn.subtype() & 0xFF;
        // Jun_Main does not seed obFrame/obTimeFrame. Fresh object RAM starts at
        // frame 0 with a zero timer, and the first Jun_Action pass advances that
        // to frame 1 with a reloaded timer.
    }

    // ========================================================================
    // Object Lifecycle
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Lazily create child display object on first update
        if (childInstance == null) {
            childInstance = spawnFreeChild(() -> new Sonic1JunctionChildInstance(spawn));
        }

        switch (routine) {
            case ACTION -> updateAction(player);
            case RELEASE -> updateRelease(player);
        }
    }

    // ========================================================================
    // Jun_Action (Routine 2)
    // ========================================================================

    /**
     * Jun_Action: check switch, run solid collision, detect grab condition.
     * <pre>
     *   bsr.w Jun_ChkSwitch
     *   tst.b obRender(a0)
     *   bpl.w Jun_Display      ; if off-screen, just display
     *   move.w #$30,d1
     *   move.w d1,d2
     *   move.w d2,d3
     *   addq.w #1,d3
     *   move.w obX(a0),d4
     *   bsr.w SolidObject
     *   btst #5,obStatus(a0)   ; is Sonic pushing the disc?
     *   beq.w Jun_Display      ; if not, branch
     * </pre>
     */
    private void updateAction(AbstractPlayableSprite player) {
        checkSwitch();
        SolidCheckpointBatch batch = checkpointAll();
        PlayerSolidContactResult mainResult = player != null ? batch.perPlayer().get(player) : null;
        if (player != null && mainResult != null
                && (mainResult.pushingNow() || mainResult.pushingLastFrame())) {
            int gapCheckFrame = player.getCentreX() < getX() ? GAP_FRAME_LEFT : GAP_FRAME_RIGHT;
            if (mappingFrame == gapCheckFrame) {
                beginGrab(player, gapCheckFrame);
            }
        }
    }

    // ========================================================================
    // Jun_Release (Routine 6)
    // ========================================================================

    /**
     * Jun_Release: rotate Sonic around the disc and release when gap faces down or right.
     * <pre>
     *   move.b obFrame(a0),d0
     *   cmpi.b #4,d0           ; is gap pointing down?
     *   beq.s .release
     *   cmpi.b #7,d0           ; is gap pointing right?
     *   bne.s .dontrelease
     *
     * .release:
     *   cmp.b objoff_32(a0),d0 ; same as entry frame?
     *   beq.s .dontrelease     ; if yes, don't release yet (must rotate past)
     * </pre>
     */
    private void updateRelease(AbstractPlayableSprite player) {
        if (player == null) {
            // Lost player reference — revert to action mode
            routine = Routine.ACTION;
            return;
        }

        int frame = mappingFrame;

        // Check release conditions
        boolean canRelease = false;
        if (frame == RELEASE_FRAME_DOWN || frame == RELEASE_FRAME_RIGHT) {
            // Don't release if we're at the same frame we entered
            if (frame != grabFrame) {
                canRelease = true;
            }
        }

        if (canRelease) {
            // Release Sonic
            if (frame == RELEASE_FRAME_DOWN) {
                // Gap faces down: drop straight down
                // move.w #0,obVelX(a1) / move.w #$800,obVelY(a1)
                player.setXSpeed((short) 0);
                player.setYSpeed((short) RELEASE_VEL_Y_DOWN);
            } else {
                // Gap faces right: launch right-downward
                // move.w #$800,obVelX(a1) / move.w #$800,obVelY(a1)
                player.setXSpeed((short) RELEASE_VEL_X_RIGHT);
                player.setYSpeed((short) RELEASE_VEL_Y_RIGHT);
            }

            // clr.b (f_playerctrl).w — unlock controls
            ObjectControlState.none().applyTo(player);
            player.setControlLocked(false);
            player.setForcedAnimationId(-1);

            // subq.b #4,obRoutine(a0) — back to Jun_Action
            routine = Routine.ACTION;
        }

        // .dontrelease:
        // bsr.s Jun_ChkSwitch — always runs, even on release frame
        checkSwitch();

        // bsr.s Jun_ChgPos — always runs, positions player at current frame's gap
        changePlayerPosition(player);
    }

    // ========================================================================
    // Jun_ChkSwitch
    // ========================================================================

    /**
     * Checks the switch state and updates animation frame.
     * <pre>
     *   lea (f_switch).w,a2
     *   moveq #0,d0
     *   move.b jun_switch(a0),d0
     *   btst #0,(a2,d0.w)       ; is switch pressed?
     *   beq.s .unpressed
     *
     *   tst.b jun_reverse(a0)    ; previously pressed?
     *   bne.s .animate           ; yes, already reversed
     *   neg.b jun_frame(a0)      ; reverse direction
     *   move.b #1,jun_reverse(a0)
     *   bra.s .animate
     *
     * .unpressed:
     *   clr.b jun_reverse(a0)
     *
     * .animate:
     *   subq.b #1,obTimeFrame(a0)
     *   bpl.s .nochange
     *   move.b #7,obTimeFrame(a0)
     *   move.b jun_frame(a0),d1
     *   move.b obFrame(a0),d0
     *   add.b d1,d0
     *   andi.b #$F,d0
     *   move.b d0,obFrame(a0)
     * </pre>
     */
    private void checkSwitch() {
        Sonic1SwitchManager switchMgr = services().gameService(Sonic1SwitchManager.class);

        // btst #0,(a2,d0.w) — check if switch bit 0 is pressed
        boolean pressed = (switchMgr.getRaw(switchIndex) & 0x01) != 0;

        if (pressed) {
            if (!switchReversed) {
                // neg.b jun_frame(a0) — negate frame direction
                frameDirection = (byte) (-frameDirection);
                switchReversed = true;
            }
        } else {
            // clr.b jun_reverse(a0)
            switchReversed = false;
        }

        // Animate: decrement timer, advance frame when expired
        frameTimer--;
        if (frameTimer < 0) {
            // move.b #7,obTimeFrame(a0)
            frameTimer = FRAME_TIMER_PERIOD;

            // add.b d1,d0 / andi.b #$F,d0
            int newFrame = (mappingFrame + frameDirection) & 0x0F;
            mappingFrame = newFrame;
        }
    }

    // ========================================================================
    // Jun_ChgPos
    // ========================================================================

    /**
     * Positions the player at the gap opening based on the current frame.
     * <pre>
     *   moveq #0,d0
     *   move.b obFrame(a0),d0
     *   add.w d0,d0              ; d0 * 2 (2 bytes per entry)
     *   lea .data(pc,d0.w),a2
     *   move.b (a2)+,d0          ; x offset (signed byte)
     *   ext.w d0
     *   add.w obX(a0),d0
     *   move.w d0,obX(a1)        ; set player X
     *   move.b (a2)+,d0          ; y offset (signed byte)
     *   ext.w d0
     *   add.w obY(a0),d0
     *   move.w d0,obY(a1)        ; set player Y
     * </pre>
     */
    private void changePlayerPosition(AbstractPlayableSprite player) {
        int dataIndex = (mappingFrame & 0x0F) * 2;
        int xOff = POSITION_DATA[dataIndex];      // signed byte -> sign-extended
        int yOff = POSITION_DATA[dataIndex + 1];   // signed byte -> sign-extended

        // Set player centre to disc position + offset.
        //
        // ROM Jun_ChgPos (docs/s1disasm/_incObj/66 Rotating Junction.asm:167-172) uses
        //   add.w obX(a0),d0 / move.w d0,obX(a1)
        //   add.w obY(a0),d0 / move.w d0,obY(a1)
        // The move.w writes only the upper word (pixel) of the 32-bit position field
        // and leaves obSubpixelX/obSubpixelY untouched. The plain setCentreX/setCentreY
        // helpers zero the subpixel; use the *PreserveSubpixel variants to mirror the
        // ROM. Without this preservation the SBZ1 credits demo desyncs by 1 pixel after
        // the junction releases the player and gravity-driven SpeedToPos resumes
        // (manifested as a 0x7800 sub-y mismatch at trace frame 285).
        player.setCentreXPreserveSubpixel((short) (getX() + xOff));
        player.setCentreYPreserveSubpixel((short) (getY() + yOff));
    }

    // ========================================================================
    // SolidObjectProvider
    // ========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    /**
     * The junction parent's ROM {@code obActWid}.
     *
     * <p>{@code Jun_Main} writes {@code move.b #96/2,obActWid(a0)} = 48 for the
     * parent (docs/s1disasm/_incObj/66 SBZ Rotating Junction.asm:47-48). The
     * {@code #112/2} = 56 at {@code :43} belongs to the circular cover-up
     * children, which are put straight into routine 4 ({@code Jun_Display},
     * "do nothing but display") at {@code :32} and never made solid — so the
     * parent is the only slot the player can be standing on, and 48 is the only
     * value the balance test can read.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers want the byte: {@code BuildSprites}' horizontal cull
     * (docs/s1disasm/_inc/BuildSprites.asm:49-58) and {@code Sonic_Balance}
     * (docs/s1disasm/_incObj/01 Sonic.asm:422-431). The class is full-solid, so
     * the balance accessor inherits this one. {@code Jun_Action}'s separately
     * authored {@code d1 = #74/2+sonic_solid_width} at {@code :60} is unchanged.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return ACT_WIDTH;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // ROM SolidObject uses `bhi` for the right edge (docs/s1disasm/_incObj/sub
        // SolidObject.asm:126-127), so relX == 2*width remains a valid side contact.
        return SolidRoutineProfile.fullSolid(false, true, false);
    }

    /**
     * Disable solid collision while Sonic is grabbed and being rotated.
     * In the ROM, SolidObject is only called during Jun_Action (routine 2),
     * NOT during Jun_Release (routine 6). Without this gate, the solid
     * contact system pushes Sonic outward from the disc center (causing the
     * "too far from centre" offset) and can crush him on counter-clockwise
     * rotations.
     */
    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return routine != Routine.RELEASE;
    }

    // ========================================================================
    // SolidObjectListener
    // ========================================================================

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive current-frame push/grab handling from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    private void beginGrab(AbstractPlayableSprite player, int gapFrame) {
        grabFrame = gapFrame;
        routine = Routine.RELEASE;
        // ROM: move.b #1,(f_playerctrl).w -- bit 0 only, sign bit CLEAR
        // (docs/s1disasm/_incObj/66 SBZ Rotating Junction.asm:82). Bit 0 makes
        // Sonic_Control skip Sonic_Modes, but the object-interaction gate is the
        // sign bit (tst.b f_playerctrl / bmi.s .ignoreobjcoll,
        // docs/s1disasm/_incObj/01 Sonic.asm:94-97), so ReactToItem keeps running
        // every frame Sonic is riding the junction.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setControlLocked(true);
        player.setRolling(false);
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setForcedAnimationId(Sonic1AnimationIds.ROLL);
        player.setGSpeed((short) GRAB_INERTIA);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        // ROM Jun_Move's grab clears the junction's OWN pushed flag before the
        // player's (docs/s1disasm/_incObj/66 SBZ Rotating Junction.asm:87-88).
        services().objectManager().solidContacts().releaseObjectPushLatch(player, this);
        player.setPushing(false);
        player.setAir(true);

        int savedX = player.getCentreX();
        int savedY = player.getCentreY();
        changePlayerPosition(player);
        int targetX = player.getCentreX();
        int targetY = player.getCentreY();
        // ROM Jun_Action grab body (docs/s1disasm/_incObj/66 Rotating Junction.asm:87-93):
        //   move.w obX(a1),d2 / move.w obY(a1),d3   ; save player x/y (pixel only)
        //   bsr.w  Jun_ChgPos                       ; positions player via move.w
        //   add.w  d2,obX(a1) / add.w  d3,obY(a1)   ; word-add to obX/obY pixel words
        //   asr.w  obX(a1)    / asr.w  obY(a1)      ; word-shift the pixel words
        // Each instruction operates on the upper 16 bits only, leaving the subpixel
        // fraction (obSubpixelX/Y) untouched. Use *PreserveSubpixel to mirror.
        player.setCentreXPreserveSubpixel((short) ((targetX + savedX) >> 1));
        player.setCentreYPreserveSubpixel((short) ((targetY + savedY) >> 1));
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_JUNCTION);
        if (renderer == null) return;

        // Render the main disc at the current gap frame
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_MAIN);
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    @Override
    public boolean isPersistent() {
        // When in RELEASE mode (Sonic grabbed), stay active regardless of camera position.
        // This matches the ROM behavior where the object continues running Jun_Release.
        if (routine == Routine.RELEASE) {
            return true;
        }
        return false;
    }

    @Override
    public void onUnload() {
        // If we're unloading while Sonic is grabbed, release him
        if (routine == Routine.RELEASE) {
            AbstractPlayableSprite player = getPlayer();
            if (player != null) {
                ObjectControlState.none().applyTo(player);
                player.setControlLocked(false);
            }
            routine = Routine.ACTION;
        }
    }

    private AbstractPlayableSprite getPlayer() {
        Camera camera = services().camera();
        if (camera != null) {
            return (AbstractPlayableSprite) camera.getFocusedSprite();
        }
        return null;
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!DEBUG_VIEW_ENABLED || !OVERLAY_MANAGER.isEnabled(DebugOverlayToggle.OVERLAY)) {
            return;
        }

        int cx = getX();
        int cy = getY();
        float r = DEBUG_COLOR.getRed() / 255f;
        float g = DEBUG_COLOR.getGreen() / 255f;
        float b = DEBUG_COLOR.getBlue() / 255f;

        // Draw solid bounds (green)
        ctx.drawRect(cx, cy, SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, r, g, b);

        // Draw center marker
        ctx.drawCross(cx, cy, 4, r, g, b);

        // Draw state text
        String state = String.format("F:%d D:%+d R:%s S:%d",
                mappingFrame, frameDirection, routine.name().charAt(0), switchIndex);
        ctx.drawWorldLabel(cx, cy - SOLID_AIR_HALF_HEIGHT - 8, 0, state, DEBUG_COLOR);
    }

    // ========================================================================
    // Child Display Object (Jun_Display / Routine 4)
    // ========================================================================

    /**
     * Inner class for the child display-only object that renders the full circle
     * behind the main junction disc. In the ROM, this is a separate SST entry created
     * by Jun_Main with obRoutine=4 (Jun_Display), which just calls RememberState.
     */
    static class Sonic1JunctionChildInstance extends AbstractObjectInstance
            implements SpawnRewindRecreatable {

        Sonic1JunctionChildInstance(ObjectSpawn parentSpawn) {
            super(parentSpawn, "JunctionChild");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            // Jun_Display (Routine 4): bra.w RememberState
            // No logic, just display. RememberState is handled by the engine persistence system.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SBZ_JUNCTION);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            // Render the full circle sprite (frame $10 = 16)
            renderer.drawFrameIndex(CHILD_FRAME, getX(), getY(), false, false);
        }

        @Override
        public int getPriorityBucket() {
            // move.b #3,obPriority(a1) — child renders behind main disc
            return RenderPriority.clamp(PRIORITY_CHILD);
        }
    }
}
