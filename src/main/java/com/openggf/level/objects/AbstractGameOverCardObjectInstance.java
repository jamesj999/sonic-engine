package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.JoypadPressSnapshot;
import com.openggf.game.GameOverExit;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RespawnState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.managers.SpriteManager;

import java.util.List;

/**
 * One half of the GAME OVER / TIME OVER card: S1 {@code GameOverCard} (Obj39,
 * docs/s1disasm/_incObj/39 Game Over.asm), S2 {@code Obj39}
 * (docs/s2disasm/s2.asm:27670-27774) and S3K {@code Obj_GameOver}
 * (docs/skdisasm/sonic3k.asm:62020-62101). The ROM loads two of these: the
 * "GAME"/"TIME" word (mapping frame 0 or 2) that slides in from the left and
 * owns the wait timer, and the "OVER" word (frame 1 or 3) that slides in from
 * the right. Bit 0 of the mapping frame is the ROM's own "is this the OVER
 * object" test.
 *
 * <p>All three games share the routine table &mdash; wait for the art, slide
 * in at 16 px/frame to screen centre, wait for a button or the timer, then
 * either restart the level (time over) or leave for the continue / Sega screen
 * (game over). Subclasses supply what genuinely differs: the art-ready gate,
 * the wait length, which buttons dismiss and which controllers are polled,
 * whether the OVER half polls the button, and any per-game side effect on
 * routine 0.
 *
 * <p>Screen-space object: the ROM sets {@code sprite_cam_screen} / clears
 * {@code render_flags} and keeps VDP-space coordinates in {@code x_pos}. The
 * engine keeps those VDP values and converts to world space at draw time the
 * way the results cards do.
 */
public abstract class AbstractGameOverCardObjectInstance extends AbstractObjectInstance
        implements ZeroScalarArgsRewindRecreatable {

    /** "GAME" word (game over). */
    public static final int FRAME_GAME = 0;
    /** "OVER" word (game over). */
    public static final int FRAME_OVER_GAME = 1;
    /** "TIME" word (time over). */
    public static final int FRAME_TIME = 2;
    /** "OVER" word (time over; a distinct mapping frame that looks identical). */
    public static final int FRAME_OVER_TIME = 3;

    /** ROM {@code Over_Index} routines 0/2/4. */
    protected static final int ROUTINE_CHECK_ART = 0;
    protected static final int ROUTINE_MOVE_IN = 2;
    protected static final int ROUTINE_WAIT = 4;

    // ROM Over_Main / Obj39_Init / loc_2D5DE start and target positions, all in
    // VDP sprite coordinates (+128 bias).
    private static final int VDP_X_START_LEFT = 0x80 - 48;          // $50  "GAME"/"TIME"
    private static final int VDP_X_START_RIGHT = 0x80 + 320 + 48;   // $1F0 "OVER"
    private static final int VDP_X_TARGET = 0x80 + 320 / 2;         // $120 conjoined
    private static final int VDP_Y = 0x80 + 224 / 2;                // $F0  vertical centre
    /** {@code moveq #$10,d1} slide speed. */
    private static final int MOVE_IN_SPEED = 0x10;
    private static final int VDP_BIAS = 128;
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    // Every field is a non-final scalar so the generic rewind capturer restores
    // it over the zero-argument recreate instance.
    private int mappingFrame;
    private int routine = ROUTINE_CHECK_ART;
    private int xPos;
    private int waitTimer;
    /** Whether this frame's routine reached its DisplaySprite. */
    private boolean displayedThisFrame;
    private boolean dismissed;

    protected AbstractGameOverCardObjectInstance(int mappingFrame) {
        super(null, "game_over_card");
        this.mappingFrame = mappingFrame & 3;
    }

    // ---- per-game hooks ---------------------------------------------------

    /**
     * ROM routine 0: {@code tst.l (v_plc_buffer).w} / {@code (Plc_Buffer).w} /
     * {@code (Nem_decomp_queue).w} &mdash; hold while the card's art is still
     * decompressing.
     */
    protected abstract boolean isArtPending();

    /** The frame count written on the conjoining frame (S1/S2 12 s, S3K 8 s). */
    protected abstract int waitFrames();

    /** The ROM's button test for this game against this frame's press edges. */
    protected abstract boolean isDismissPressed(JoypadPressSnapshot presses);

    /**
     * S1 {@code Over_Wait} tests the buttons before it tests bit 0 of the frame,
     * so its OVER object can also change the game mode; S2 {@code Obj39_Wait}
     * and S3K {@code loc_2D638} test bit 0 first and their OVER object only
     * displays.
     */
    protected abstract boolean overElementPollsDismissButton();

    /** Called once when routine 0 releases, before the position setup. */
    protected void onArtReady() {
    }

    /** Called on every frame routine 4 runs, before the button/timer test. */
    protected void onWaitFrame() {
    }

    // ---- ROM routine table ------------------------------------------------

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        displayedThisFrame = false;
        if (routine == ROUTINE_CHECK_ART) {
            if (isArtPending()) {
                return;
            }
            onArtReady();
            routine = ROUTINE_MOVE_IN;
            xPos = isOverElement() ? VDP_X_START_RIGHT : VDP_X_START_LEFT;
            // Over_Main / Obj39_Init / loc_2D5DE all fall straight into the
            // move-in routine on the same frame.
        }
        if (routine == ROUTINE_MOVE_IN) {
            updateMoveIn();
            return;
        }
        updateWait();
    }

    /**
     * {@code Over_MoveIn}: 16 px/frame toward centre, direction from which side
     * of the target the object is on. On the frame it arrives the ROM sets the
     * wait timer, advances the routine and returns <em>without</em>
     * DisplaySprite: {@code FixBugs = 0} keeps that {@code rts}
     * (docs/s1disasm/_incObj/39 Game Over.asm:48-52, docs/s2disasm/s2.asm:27714-27721;
     * S3K {@code loc_2D62A} has the same bare {@code rts}), so both words
     * flicker off for one frame as they conjoin. The FixBugs branch would
     * branch to DisplaySprite instead.
     */
    private void updateMoveIn() {
        if (xPos == VDP_X_TARGET) {
            waitTimer = waitFrames();
            routine = ROUTINE_WAIT;
            return;
        }
        xPos += xPos < VDP_X_TARGET ? MOVE_IN_SPEED : -MOVE_IN_SPEED;
        displayedThisFrame = true;
    }

    /**
     * {@code Over_Wait} / {@code Obj39_Wait} / {@code loc_2D638}: the
     * GAME/TIME word dismisses on a button press or when its timer runs out;
     * the OVER word only displays (or, in S1, also answers the button). After
     * the dismiss decision the object keeps displaying and keeps writing the
     * same mode/restart flag every frame until the level loop ends.
     */
    private void updateWait() {
        onWaitFrame();
        boolean pressed = isDismissPressed(currentPresses());
        if (isOverElement()) {
            if (pressed && overElementPollsDismissButton()) {
                dismiss();
            }
        } else if (pressed || waitTimer == 0) {
            dismiss();
        } else {
            waitTimer--;
        }
        displayedThisFrame = true;
    }

    /**
     * {@code .changeMode} / {@code Obj39_Dismiss} / {@code loc_2D666}: a set
     * time-over flag always restarts the level (after clearing the banked
     * star-post time); otherwise the continue screen when continues remain,
     * else the Sega screen.
     */
    private void dismiss() {
        dismissed = true;
        if (isTimeOverFlagged()) {
            RespawnState checkpoint = services().checkpointState();
            if (checkpoint != null) {
                checkpoint.clearSavedActTimer();
            }
            requestLevelRestart();
            return;
        }
        requestGameOverExit(continuesRemaining() > 0
                ? GameOverExit.CONTINUE_SCREEN
                : GameOverExit.TITLE_SCREEN);
    }

    // ---- state the decision reads (overridable for tests) -----------------

    protected JoypadPressSnapshot currentPresses() {
        SpriteManager sprites = services().spriteManager();
        JoypadPressSnapshot presses = sprites != null ? sprites.getJoypadPressSnapshot() : null;
        return presses != null ? presses : JoypadPressSnapshot.NONE;
    }

    /** ROM {@code f_timeover} / {@code Time_Over_flag} / {@code Time_over_flag}. */
    protected boolean isTimeOverFlagged() {
        LevelState levelState = services().levelGamestate();
        return levelState != null && levelState.isTimeOver();
    }

    /** ROM {@code v_continues} / {@code Continue_count}. */
    protected int continuesRemaining() {
        GameStateManager gameState = services().gameState();
        return gameState != null ? gameState.getContinues() : 0;
    }

    /** ROM {@code move.w #1,(f_restart).w} / {@code Level_Inactive_flag} / {@code Restart_level_flag}. */
    protected void requestLevelRestart() {
        LevelManager levelManager = services().levelManager();
        if (levelManager != null) {
            levelManager.requestRespawn();
        }
    }

    protected void requestGameOverExit(GameOverExit exit) {
        LevelManager levelManager = services().levelManager();
        if (levelManager != null) {
            levelManager.requestGameOverExit(exit);
        }
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!displayedThisFrame) {
            return;
        }
        Camera camera = services().camera();
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getRenderer(ObjectArtKeys.GAME_OVER) : null;
        if (camera == null || renderer == null) {
            return;
        }
        int worldX = camera.getX() - VDP_BIAS + widescreenXOffset() + xPos;
        int worldY = camera.getY() - VDP_BIAS + VDP_Y;
        renderer.drawFrameIndex(mappingFrame, worldX, worldY, false, false);
    }

    /** Centres native-320 content in a wider viewport; 0 at native width. */
    private int widescreenXOffset() {
        GraphicsManager graphics = services().graphicsManager();
        return graphics != null ? (graphics.getProjectionWidth() - SCREEN_WIDTH) / 2 : 0;
    }

    /** ROM {@code move.b #0,obPriority(a0)}: front-most sprite. */
    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getX() {
        Camera camera = services().camera();
        return camera != null ? camera.getX() + SCREEN_WIDTH / 2 : SCREEN_WIDTH / 2;
    }

    @Override
    public int getY() {
        Camera camera = services().camera();
        return camera != null ? camera.getY() + SCREEN_HEIGHT / 2 : SCREEN_HEIGHT / 2;
    }

    // ---- accessors --------------------------------------------------------

    public int getMappingFrame() {
        return mappingFrame;
    }

    /** {@code btst #0,obFrame(a0)}. */
    public boolean isOverElement() {
        return (mappingFrame & 1) != 0;
    }

    /** Mapping frames 2 and 3 are the TIME OVER pair. */
    public boolean isTimeOverCard() {
        return mappingFrame >= FRAME_TIME;
    }

    public int getRoutine() {
        return routine;
    }

    /** VDP-space x ({@code obX}); {@code $120} once conjoined. */
    public int getVdpX() {
        return xPos;
    }

    public int getWaitTimer() {
        return waitTimer;
    }

    public boolean isDisplayedThisFrame() {
        return displayedThisFrame;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    // ---- spawn helper -----------------------------------------------------

    /**
     * Loads the two-object pair the way the death routines do: the word object
     * first with frame 0 (GAME) or 2 (TIME), then the OVER object with frame 1
     * or 3, each at its game's fixed SST slot.
     */
    public static <T extends AbstractGameOverCardObjectInstance> void spawnPair(
            ObjectServices services,
            boolean timeOver,
            java.util.function.IntFunction<T> factory,
            int wordSlot,
            int overSlot) {
        ObjectManager objects = services.objectManager();
        if (objects == null) {
            throw new IllegalStateException("GAME OVER card requires an object manager");
        }
        int wordFrame = timeOver ? FRAME_TIME : FRAME_GAME;
        int overFrame = timeOver ? FRAME_OVER_TIME : FRAME_OVER_GAME;
        T word = ObjectConstructionContext.construct(services, () -> factory.apply(wordFrame));
        ObjectLifetimeOps.addDynamicAtReservedSlot(objects, word, wordSlot);
        T over = ObjectConstructionContext.construct(services, () -> factory.apply(overFrame));
        ObjectLifetimeOps.addDynamicAtReservedSlot(objects, over, overSlot);
    }
}
