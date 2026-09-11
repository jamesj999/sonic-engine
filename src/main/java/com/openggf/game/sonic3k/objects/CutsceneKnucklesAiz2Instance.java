package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Rival Knuckles cutscene object for the Sonic/Tails AIZ2 post-boss transition.
 *
 * <p>ROM reference: CutsceneKnux_AIZ2.
 */
public class CutsceneKnucklesAiz2Instance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    private static final int OBJECT_ID = 0x82;
    private static final int INIT_X = 0x4B8E;
    private static final int INIT_Y = 0x017D;
    private static final int RUN_END_X = 0x4B3C;
    private static final int WAIT_TIMER = (2 * 60) - 1;
    private static final int LAUGH_TIMER = 0x5F;
    private static final int RUN_SPEED = 2;
    private static final int FLOOR_Y = 0x017D;

    private static final int[] RUN_FRAMES = {0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11};
    private static final int RUN_DELAY = 4;
    private static final int[] JUMP_FRAMES = {8, 4, 8, 5, 8, 6, 8, 7};
    private static final int JUMP_DELAY = 1;
    // ROM: byte_666B9 plays $1C, $1C, $1D ONCE, then $F8-jumps to
    // byte_666BF which LOOPS $1E, $1F ($FC = restart).
    private static final int[] LAUGH_INTRO = {0x1C, 0x1C, 0x1D};
    private static final int[] LAUGH_LOOP = {0x1E, 0x1F};
    private static final int LAUGH_DELAY = 7;

    private enum Phase { INIT_WAIT, RUN_IN, LAUGH_1, JUMP, LAUGH_2 }

    private Phase phase = Phase.INIT_WAIT;
    private int timer = WAIT_TIMER;
    private int currentX;
    private int currentY;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;
    private boolean initialized;
    private boolean bounced;

    // ROM: SetUp_ObjAttributesSlotted defaults mapping_frame to 0 (standing).
    // Frame 0x1C is the laugh pose — only used during LAUGH_1/LAUGH_2 phases.
    private int mappingFrame = 0;
    private int animationTick;
    private int animationIndex;
    private boolean laughIntroFinished;
    /** ROM render_flags bit 0: true = facing right (hFlip in draw call). */
    private boolean facingRight = true;  // Starts facing right (running in from right side)

    public CutsceneKnucklesAiz2Instance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnucklesAIZ2");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
    }

    public static CutsceneKnucklesAiz2Instance createDefault() {
        return new CutsceneKnucklesAiz2Instance(
                new ObjectSpawn(INIT_X, INIT_Y, OBJECT_ID, 4, 1, false, 0));
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
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            initialized = true;
            AizIntroArtLoader.loadAllIntroArt(services());
            AizIntroArtLoader.applyKnucklesPalette(services());
            services().playMusic(Sonic3kMusic.KNUCKLES.id);
            Aiz2BossEndSequenceState.setActiveKnuckles(this);
            // Obj_CutsceneKnuckles installs the AIZ2 routine, then loc_61FC2
            // initializes the wait/children and returns. Routine $02 first
            // decrements Obj_Wait on the following object entry.
            return;
        }
        Aiz2BossEndSequenceState.setActiveKnuckles(this);

        switch (phase) {
            case INIT_WAIT -> updateInitWait();
            case RUN_IN -> updateRunIn();
            case LAUGH_1 -> updateLaugh(true);
            case JUMP -> updateJump();
            case LAUGH_2 -> updateLaugh(false);
        }
    }

    private void updateInitWait() {
        // ROM: Knuckles stands at frame 0 (default) during the initial wait.
        if (timer > 0) {
            timer--;
            return;
        }
        phase = Phase.RUN_IN;
        animationTick = 0;
        animationIndex = 0;
    }

    private void updateRunIn() {
        if (currentX - RUN_SPEED >= RUN_END_X) {
            currentX -= RUN_SPEED;
            animateLoop(RUN_FRAMES, RUN_DELAY);
            return;
        }
        currentX = RUN_END_X;
        // ROM: loc_6203C — bclr #0,render_flags → face LEFT (toward Sonic/button)
        facingRight = false;
        phase = Phase.LAUGH_1;
        timer = LAUGH_TIMER;
        animationTick = 0;
        animationIndex = 0;
        mappingFrame = 0x1C;
    }

    private void updateLaugh(boolean countdown) {
        // ROM: byte_666B9 plays intro {$1C,$1C,$1D} once, then $F8-jumps
        // to byte_666BF which loops {$1E,$1F} with $FC restart.
        if (!laughIntroFinished) {
            if (animateOnce(LAUGH_INTRO, LAUGH_DELAY)) {
                laughIntroFinished = true;
                animationTick = 0;
                animationIndex = 0;
            }
        } else {
            animateLoop(LAUGH_LOOP, LAUGH_DELAY);
        }
        if (!countdown) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        phase = Phase.JUMP;
        xVel = -0x100;
        yVel = -0x400;
        animationTick = 0;
        animationIndex = 0;
        laughIntroFinished = false;
        mappingFrame = 8;
    }

    private void updateJump() {
        animateLoop(JUMP_FRAMES, JUMP_DELAY);
        // ROM: loc_620AA uses MoveSprite (moves X and Y) + gravity
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, xSub, ySub, xVel, yVel);
        SubpixelMotion.objectFallXY(motion, SubpixelMotion.S3K_GRAVITY);
        currentX = motion.x;
        currentY = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        xVel = motion.xVel;
        yVel = motion.yVel;

        // Still going up or above floor — keep falling
        if (yVel < 0 || currentY < FLOOR_Y) {
            return;
        }

        if (!bounced) {
            // First landing — bounce back the other way
            bounced = true;
            currentY = FLOOR_Y;
            xVel = -xVel;
            yVel = -yVel;
            return;
        }

        // Second landing — done jumping, start laughing again
        currentY = FLOOR_Y;
        xVel = 0;
        yVel = 0;
        phase = Phase.LAUGH_2;
        animationTick = 0;
        animationIndex = 0;
        laughIntroFinished = false;
        mappingFrame = 0x1C;
    }

    private void animateLoop(int[] frames, int delay) {
        if (frames.length == 0) {
            return;
        }
        if (animationTick <= 0) {
            mappingFrame = frames[animationIndex];
            animationIndex = (animationIndex + 1) % frames.length;
            animationTick = delay;
        }
        animationTick--;
    }

    /** Plays through frames once without looping. Returns true when all frames have been shown. */
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

    /**
     * Returns true once Knuckles has completed his jump/bounce sequence
     * and entered the final laugh phase.
     */
    public boolean isJumpFinished() {
        return phase == Phase.LAUGH_2;
    }

    /**
     * Returns true once Knuckles has landed at least once during the jump
     * phase (i.e. the first bounce). The ROM button is triggered by
     * SolidObjectFull2 when Knuckles physically lands on it during the
     * first arc of the jump, before he bounces back.
     */
    public boolean hasLandedOnButton() {
        return bounced || phase == Phase.LAUGH_2;
    }

    @Override
    public boolean isHighPriority() {
        // ROM: The cutscene Knuckles appears in the post-boss arena area which
        // has high-priority foreground tiles (waterfall). Knuckles must render
        // in front of these tiles to be visible.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getKnucklesRenderer(services());
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, facingRight, false);
    }
}
