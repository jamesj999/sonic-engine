package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.Sonic3kZoneEvents;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Cutscene Knuckles for Hydrocity Zone Act 2.
 *
 * <p>ROM reference: CutsceneKnux_HCZ2 (s3.asm:80032).
 * Knuckles runs in from the right, presses a button destroying the bridge
 * Sonic is standing on, then jumps away laughing.
 *
 * <p>7-routine state machine (stride 2, object routine field):
 * <ul>
 *   <li>0: Init — lock camera, store boundaries</li>
 *   <li>2: Wait — player X >= $3990 AND standing on object → fade+play Knuckles theme, 179-frame wait</li>
 *   <li>4: Obj_Wait → make visible, load cutscene palette, Camera_min_Y = $5C0</li>
 *   <li>6: Walk left 4px/frame for 31 frames → button press pose ($20), 63-frame wait</li>
 *   <li>8: Obj_Wait → flip, jump (xVel=-$100, yVel=-$400)</li>
 *   <li>10: Jump/bounce with gravity, bounce once, then laugh</li>
 *   <li>12: Laugh until off-screen → restore palette, fade to level music, unlock camera, delete</li>
 * </ul>
 */
public class CutsceneKnucklesHcz2Instance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(CutsceneKnucklesHcz2Instance.class.getName());

    // --- ROM constants ---

    /** Camera range for Check_CameraInRange (word_44ADA). */
    private static final int CAM_RANGE_Y_MIN = 0x540;
    private static final int CAM_RANGE_Y_MAX = 0x600;
    private static final int CAM_RANGE_X_MIN = 0x3900;
    private static final int CAM_RANGE_X_MAX = 0x3940;

    /** Camera_max_X_pos lock during cutscene. */
    private static final int CAMERA_LOCK_MAX_X = 0x3940;

    /** Player X threshold to trigger the cutscene. */
    private static final int TRIGGER_PLAYER_X = 0x3990;

    /** Timer for music fade wait (3 seconds at 60fps minus 1 for dbf). */
    private static final int MUSIC_FADE_TIMER = (3 * 60) - 1;

    /** Timer before Knuckles stops walking and presses button. */
    private static final int WALK_TIMER = 0x1F;  // 31 frames

    /** Timer for button press pose. */
    private static final int BUTTON_PRESS_TIMER = 0x3F;  // 63 frames

    /** Walk speed (4 pixels per frame leftward). */
    private static final int WALK_SPEED = 4;

    /**
     * ROM: addi.w #$9E,x_pos(a0) in loc_6215E (sonic3k.asm:128949).
     * Offsets Knuckles' start position to the right of his layout placement,
     * putting him off-screen. He then walks left into view.
     */
    private static final int INIT_X_OFFSET = 0x9E;

    /** Button child Y offset from Knuckles (ROM: ChildObjDat_665A2 byte $C). */
    private static final int BUTTON_CHILD_Y_OFFSET = 0x0C;

    /** Camera_min_Y_pos set when Knuckles appears. */
    private static final int CUTSCENE_MIN_Y = 0x5C0;

    /** Jump velocity on routine 10 entry. */
    private static final int JUMP_X_VEL = -0x100;
    private static final int JUMP_Y_VEL = -0x400;

    /** Mapping frame for button press pose. */
    private static final int FRAME_BUTTON_PRESS = 0x20;

    /** Mapping frame for standing/jump. */
    private static final int FRAME_JUMP = 8;

    /** Y radius for terrain collision during jump. */
    private static final int Y_RADIUS = 0x13;

    // --- Animation data (from ROM) ---

    /** byte_4576B: walk animation — frames $A-$11 with delay 5. */
    private static final int[] WALK_FRAMES = {0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11};
    private static final int WALK_ANIM_DELAY = 5;

    /** byte_4577B: jump animation — frames 8,4-7 interleaved with delay 1. */
    private static final int[] JUMP_FRAMES = {8, 4, 8, 5, 8, 6, 8, 7};
    private static final int JUMP_ANIM_DELAY = 1;

    /** byte_45785: laugh intro — frames $1C,$1C,$1D then $F8 jump. */
    private static final int[] LAUGH_INTRO = {0x1C, 0x1C, 0x1D};
    /** byte_4578B: laugh loop — frames $1E,$1F with $FC restart. */
    private static final int[] LAUGH_LOOP = {0x1E, 0x1F};
    private static final int LAUGH_DELAY = 7;

    // --- Mutable state ---

    private int routine;
    private int currentX;
    private int currentY;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;
    private int timer;
    private boolean initialized;
    private boolean visible;
    /** ROM render_flags bit 0: true = facing right. */
    private boolean facingRight;
    /** True while Pal_CutsceneKnux is loaded on palette line 1. */
    private boolean cutscenePaletteActive;
    private boolean bounced;
    private boolean laughIntroFinished;
    private boolean cleanupDone;

    /** Stored camera boundaries for restoration. */
    private int storedMinY;
    private int storedMaxX;

    private int mappingFrame;
    private int animationTick;
    private int animationIndex;

    /** Shared reference so the button can find us (ROM: _unkFAA4). */
    private static volatile CutsceneKnucklesHcz2Instance activeInstance;

    public CutsceneKnucklesHcz2Instance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxHCZ2");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
    }

    public static CutsceneKnucklesHcz2Instance getActiveInstance() {
        return activeInstance;
    }

    public static void clearActiveInstance() {
        activeInstance = null;
    }

    /** True while Pal_CutsceneKnux is loaded on palette line 1. */
    public static boolean isCutscenePaletteActive() {
        CutsceneKnucklesHcz2Instance inst = activeInstance;
        return inst != null && inst.cutscenePaletteActive;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM Check_CameraInRange aborts setup until the camera enters
        // word_62150. On success it copies the JSR return address into (a0),
        // permanently replacing the operation pointer with the code after the
        // range check; later routines therefore keep running outside the
        // rectangle. Delaying only initialization is what leaves the controller
        // absent during the earlier spindash while still allowing the activated
        // Knuckles walk/button sequence to finish.
        Camera camera = services().camera();
        int cameraX = Short.toUnsignedInt(camera.getX());
        int cameraY = Short.toUnsignedInt(camera.getY());
        if (!initialized && (cameraX < CAM_RANGE_X_MIN || cameraX > CAM_RANGE_X_MAX
                || cameraY < CAM_RANGE_Y_MIN || cameraY > CAM_RANGE_Y_MAX)) {
            return;
        }

        if (!initialized) {
            initialized = true;
            activeInstance = this;
            // Ensure art is loaded (shared with AIZ cutscenes)
            AizIntroArtLoader.loadAllIntroArt(services());
        }

        // Delete self if playing as Knuckles (ROM: character_id check).
        if (routine == 0 && isPlayerKnuckles()) {
            setDestroyed(true);
            return;
        }

        switch (routine) {
            case 0 -> routineInit();
            case 2 -> routineWaitForTrigger();
            case 4 -> routineObjWait();
            case 6 -> routineWalkLeft();
            case 8 -> routineObjWait();
            case 10 -> routineJumpBounce();
            case 12 -> routineLaughCleanup();
        }
    }

    // =========================================================================
    // Routine 0: Init (loc_44AE2)
    // =========================================================================

    private void routineInit() {
        Camera camera = services().camera();

        // Store current camera boundaries for later restoration
        storedMinY = camera.getMinY();
        storedMaxX = camera.getMaxX();

        // Lock right camera boundary
        camera.setMaxX((short) CAMERA_LOCK_MAX_X);

        // ROM: Button child is spawned BEFORE the X offset is applied to Knuckles.
        // The button stays at the original layout position while Knuckles gets
        // pushed to the right. This means the button only triggers when Knuckles
        // physically passes over it during the jump sequence (routine 10).
        int buttonX = currentX;  // Original layout X (before offset)
        int buttonY = currentY + BUTTON_CHILD_Y_OFFSET;
        ObjectSpawn buttonSpawn = new ObjectSpawn(buttonX, buttonY, 0x83, 2, 0, false, 0);
        spawnChild(() -> new Hcz2CutsceneButtonInstance(buttonSpawn));

        // ROM: addi.w #$9E,x_pos(a0) — AFTER spawning the button, offset Knuckles
        // to the right (off-screen) so he walks into view during routine 6.
        currentX += INIT_X_OFFSET;

        routine = 2;
        LOG.info("HCZ2 Knuckles: init at X=0x" + Integer.toHexString(currentX)
                + ", camera max X locked to 0x" + Integer.toHexString(CAMERA_LOCK_MAX_X)
                + ", button spawned at X=0x" + Integer.toHexString(buttonX));
    }

    // =========================================================================
    // Routine 2: Wait for player trigger (loc_44B00)
    // =========================================================================

    private void routineWaitForTrigger() {
        AbstractPlayableSprite player = services().camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        int playerX = player.getCentreX();

        // ROM: cmpi.w #$3990,x_pos(a1) / blo.s loc_44B1A
        if (playerX >= TRIGGER_PLAYER_X
                && !player.isObjectControlled()
                && player.isOnObject()) {
            // Triggered! Start the cutscene
            routine = 4;

            // ROM: bsr.w sub_456C6 — fade music, spawn Knuckles theme
            fadeAndPlayKnucklesTheme();

            timer = MUSIC_FADE_TIMER;
            callbackId = CALLBACK_MAKE_VISIBLE;

            LOG.info("HCZ2 Knuckles: triggered at player X=0x"
                    + Integer.toHexString(playerX));
            return;
        }

        // ROM: Prevent backtracking while waiting
        Camera camera = services().camera();
        camera.setMinY((short) camera.getY());
        camera.setMinX((short) camera.getX());
    }

    // =========================================================================
    // Routine 4/8: Obj_Wait — timer countdown + callback (loc_449A2)
    // =========================================================================

    private static final int CALLBACK_MAKE_VISIBLE = 1;
    private static final int CALLBACK_BUTTON_PRESS_DONE = 2;
    private int callbackId;

    private void routineObjWait() {
        // ROM: loc_449A2 — Animate_Raw + Obj_Wait.
        // Routine 4 (pre-visible wait): Knuckles is invisible, no animation needed.
        // Routine 8 (button press wait): Knuckles holds a static pose (frame $20),
        //   Animate_Raw plays the current anim script but we keep the static frame.
        // Do NOT animate walk frames here — that's only for routine 6.

        if (timer > 0) {
            timer--;
            return;
        }

        // Timer expired — dispatch callback
        switch (callbackId) {
            case CALLBACK_MAKE_VISIBLE -> callbackMakeVisible();
            case CALLBACK_BUTTON_PRESS_DONE -> callbackStartJump();
        }
    }

    /**
     * Callback loc_44B42: Make Knuckles visible, load cutscene palette.
     */
    private void callbackMakeVisible() {
        routine = 6;
        visible = true;
        facingRight = true;  // ROM: bset #0,render_flags — face right initially

        timer = WALK_TIMER;
        callbackId = 0; // walk uses its own callback path

        // ROM: Camera_min_Y_pos = $5C0
        Camera camera = services().camera();
        camera.setMinY((short) CUTSCENE_MIN_Y);

        // ROM: lea Pal_CutsceneKnux(pc),a1 / jmp (PalLoad_Line1).l
        AizIntroArtLoader.applyKnucklesPalette(services());
        cutscenePaletteActive = true;

        animationTick = 0;
        animationIndex = 0;

        LOG.info("HCZ2 Knuckles: visible, cutscene palette loaded");
    }

    // =========================================================================
    // Routine 6: Walk left (loc_44B6C)
    // =========================================================================

    private void routineWalkLeft() {
        // ROM: subq.w #4,x_pos(a0)
        currentX -= WALK_SPEED;

        animateLoop(WALK_FRAMES, WALK_ANIM_DELAY);

        // Timer countdown (combined with walk)
        if (timer > 0) {
            timer--;
            return;
        }

        // Callback loc_44B82: switch to button press pose
        routine = 8;
        mappingFrame = FRAME_BUTTON_PRESS;
        timer = BUTTON_PRESS_TIMER;
        callbackId = CALLBACK_BUTTON_PRESS_DONE;

        LOG.info("HCZ2 Knuckles: pressing button (frame 0x20)");
    }

    /**
     * Callback loc_44B9E: Button press done, start jump.
     */
    private void callbackStartJump() {
        routine = 10;
        // ROM: bchg #0,render_flags — toggle facing direction
        facingRight = !facingRight;

        // ROM: loc_44A0E — set jump velocity
        xVel = JUMP_X_VEL;
        yVel = JUMP_Y_VEL;

        // ROM: set jump animation
        mappingFrame = FRAME_JUMP;
        animationTick = 0;
        animationIndex = 0;
        bounced = false;

        LOG.fine("HCZ2 Knuckles: jumping away");
    }

    // =========================================================================
    // Routine 10: Jump/bounce (loc_44A38)
    // =========================================================================

    private void routineJumpBounce() {
        animateLoop(JUMP_FRAMES, JUMP_ANIM_DELAY);

        // ROM: MoveSprite — apply velocity + gravity
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, xSub, ySub, xVel, yVel);
        SubpixelMotion.objectFallXY(motion, SubpixelMotion.S3K_GRAVITY);
        currentX = motion.x;
        currentY = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        xVel = motion.xVel;
        yVel = motion.yVel;

        // Still rising — don't check floor
        if (yVel < 0) {
            return;
        }

        // ROM: ObjCheckFloorDist — simplified: check if below starting Y
        // The floor is approximately at the spawn Y position
        int floorY = getSpawnY();
        if (currentY < floorY) {
            return;
        }

        if (!bounced) {
            // First landing — bounce back
            bounced = true;
            currentY = floorY;
            xVel = -xVel;
            yVel = -yVel;
            // ROM: bchg #0,render_flags — flip facing
            facingRight = !facingRight;
            return;
        }

        // Second landing — start laughing
        currentY = floorY;
        routine = 12;
        xVel = 0;
        yVel = 0;

        // ROM: loc_449E4 — setup laugh animation
        mappingFrame = 0x1C;
        animationTick = 0;
        animationIndex = 0;
        laughIntroFinished = false;

        LOG.fine("HCZ2 Knuckles: landed, laughing");
    }

    // =========================================================================
    // Routine 12: Laugh + cleanup (loc_44BAE)
    // =========================================================================

    private void routineLaughCleanup() {
        // Animate laugh
        if (!laughIntroFinished) {
            if (animateOnce(LAUGH_INTRO, LAUGH_DELAY)) {
                laughIntroFinished = true;
                animationTick = 0;
                animationIndex = 0;
            }
        } else {
            animateLoop(LAUGH_LOOP, LAUGH_DELAY);
        }

        // ROM: tst.b render_flags(a0) / bmi.w locret — while on-screen, keep laughing
        // When Sonic falls and camera follows down, Knuckles goes off-screen
        if (isOnScreen(64)) {
            return;
        }

        if (cleanupDone) {
            return;
        }
        cleanupDone = true;

        // ROM: lea (Pal_HCZ2).l,a1 / jsr (PalLoad_Line1).l — restore palette
        Sonic3kZoneEvents.loadPaletteFromPalPointers(Sonic3kConstants.PAL_POINTERS_HCZ2_INDEX);
        cutscenePaletteActive = false;

        // ROM: Obj_Song_Fade_ToLevelMusic — fade Knuckles theme, play level music
        fadeThenPlayLevelMusic();

        // ROM: ChildObjDat_44BEC — spawn camera boundary restoration objects
        // Obj_DecLevStartYGradual: gradually restore Camera_min_Y_pos
        // Obj_IncLevEndXGradual: gradually restore Camera_max_X_pos
        restoreCameraBoundariesGradually();

        // Clean up
        activeInstance = null;
        setDestroyed(true);

        LOG.info("HCZ2 Knuckles: cleanup done, palette restored, deleting");
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private boolean isPlayerKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    /**
     * ROM: sub_456C6 — Allocates Obj_Song_Fade_Transition with mus_Knuckles.
     * Fades current music then plays Knuckles' Theme after 2*60 frames.
     */
    private void fadeAndPlayKnucklesTheme() {
        // Spawn a SongFadeTransitionInstance that fades out, waits, then plays Knuckles theme
        spawnDynamicObject(new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.KNUCKLES.id));
    }

    /**
     * ROM: Obj_Song_Fade_ToLevelMusic — fades current music, then restores level music.
     * Waits 2*60 frames then plays HCZ Act 2 music.
     */
    private void fadeThenPlayLevelMusic() {
        spawnDynamicObject(new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.HCZ2.id));
    }

    /**
     * ROM: Spawn Obj_DecLevStartYGradual + Obj_IncLevEndXGradual.
     * These gradually ease camera boundaries back to their stored values.
     * Simplified: restore directly with easing via camera targets.
     */
    private void restoreCameraBoundariesGradually() {
        Camera camera = services().camera();
        // ROM: Obj_DecLevStartYGradual subtracts $4000 (16.16 fixed) per frame from
        // Camera_min_Y_pos until it reaches Camera_stored_min_Y_pos.
        // Obj_IncLevEndXGradual adds $4000 per frame to Camera_max_X_pos until stored value.
        // Simplified: set targets and let the camera ease to them.
        camera.setMinY((short) storedMinY);
        camera.setMaxX((short) storedMaxX);
    }

    private int getSpawnY() {
        return getSpawn().y();
    }

    private void animateLoop(int[] frames, int delay) {
        if (frames.length == 0) return;
        if (animationTick <= 0) {
            mappingFrame = frames[animationIndex];
            animationIndex = (animationIndex + 1) % frames.length;
            animationTick = delay;
        }
        animationTick--;
    }

    private boolean animateOnce(int[] frames, int delay) {
        if (frames.length == 0) return true;
        if (animationTick <= 0) {
            if (animationIndex >= frames.length) return true;
            mappingFrame = frames[animationIndex];
            animationIndex++;
            animationTick = delay;
        }
        animationTick--;
        return false;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visible) return;

        PatternSpriteRenderer renderer = AizIntroArtLoader.getKnucklesRenderer(services());
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, facingRight, false);
    }
}
