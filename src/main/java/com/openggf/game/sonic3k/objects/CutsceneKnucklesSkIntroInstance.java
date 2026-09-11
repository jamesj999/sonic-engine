package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RespawnState;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossPaletteFadeController;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.List;

/**
 * Generic {@code Obj_CutsceneKnuckles} subtype $30 for the S&K Knuckles-alone intro.
 *
 * <p>ROM reference: {@code CutsceneKnux_SKIntro}. The intro only runs for
 * Knuckles-alone from a fresh MHZ1 start; other teams delete the object.
 */
public final class CutsceneKnucklesSkIntroInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int CAMERA_X = 0x0560;
    private static final int CAMERA_Y = 0x0948;
    private static final int INTRO_TIMER = 0xEF;
    private static final int INITIAL_MAPPING_FRAME = 0;
    private static final int BOMB_SPAWN_X_OFFSET = 0x00A0;
    private static final int BOMB_SPAWN_Y_OFFSET = -0x0040;
    private static final int ROUTINE_WAIT_BOMB_SIGNAL = 0x02;
    private static final int ROUTINE_GRAB_ANIMATION = 0x04;
    private static final int ROUTINE_FALLING = 0x06;
    private static final int ROUTINE_LANDING = 0x08;
    private static final int ROUTINE_LANDING_ANIMATION = 0x0A;
    private static final int EXIT_ROUTINE = 0x0C;
    private static final int WORKSPACE_BOMB_FALL_COMPLETE = 0x01;
    private static final int WORKSPACE_GRAB_ANIMATION_COMPLETE = 0x02;
    private static final int WORKSPACE_FALLING_MOTION_COMPLETE = 0x04;
    private static final int WORKSPACE_LANDING_ANIMATION_COMPLETE = 0x08;
    private static final int LIGHT_GRAVITY = 0x20;
    private static final int EXIT_ACCEL = 0x0C;
    private static final int EXIT_MAX_X_VEL = 0x0400;
    private static final int EXIT_OFFSCREEN_X = 0x0180;
    private static final int POST_INTRO_RESTART_X = 0x06F4;
    private static final int POST_INTRO_RESTART_Y = 0x09EC;
    private static final int POST_INTRO_CHECKPOINT = 1;

    private int routine;
    private int timer;
    private int x;
    private int y;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int yRadius = 0x0C;
    private int workspaceFlags;
    private Integer floorDistanceOverride;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private boolean landingAnimationCallback;
    private boolean initialized;
    private boolean bombAllocated;

    public CutsceneKnucklesSkIntroInstance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxSKIntro");
        x = spawn.x();
        y = spawn.y();
        xSubpixel = x << 8;
        ySubpixel = y << 8;
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
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            initialized = true;
            if (!isKnucklesAlone() || lastStarPostHitIsSet()) {
                setDestroyed(true);
                return;
            }
            routineInit(playerEntity);
            return;
        }
        if (routine == ROUTINE_WAIT_BOMB_SIGNAL && (workspaceFlags & WORKSPACE_BOMB_FALL_COMPLETE) != 0) {
            routine = ROUTINE_GRAB_ANIMATION;
            mappingFrame = 4;
            return;
        }
        if (routine == ROUTINE_GRAB_ANIMATION && (workspaceFlags & WORKSPACE_GRAB_ANIMATION_COMPLETE) != 0) {
            routine = ROUTINE_FALLING;
            mappingFrame = 0x8D;
            xVelocity = -0x0100;
            yVelocity = -0x0100;
            return;
        }
        if (routine == ROUTINE_FALLING && (workspaceFlags & WORKSPACE_FALLING_MOTION_COMPLETE) != 0) {
            routine = ROUTINE_LANDING;
            mappingFrame = 3;
            y -= 8;
            ySubpixel = y << 8;
            yRadius = 0x13;
            xVelocity = -0x0100;
            yVelocity = 0;
            updateDynamicSpawn(x, y);
            return;
        }
        if (routine == ROUTINE_LANDING) {
            updateLanding();
            return;
        }
        if (routine == ROUTINE_LANDING_ANIMATION
                && (workspaceFlags & WORKSPACE_LANDING_ANIMATION_COMPLETE) != 0) {
            routine = EXIT_ROUTINE;
            mappingFrame = 7;
            xVelocity = 0;
            yVelocity = 0;
            return;
        }
        if (routine == ROUTINE_WAIT_BOMB_SIGNAL && timer > 0) {
            timer--;
            if (timer == 0) {
                allocateBombChild();
            }
            return;
        }
        if (routine == EXIT_ROUTINE) {
            updateExitRun();
        }
    }

    private void routineInit(PlayableEntity playerEntity) {
        routine = ROUTINE_WAIT_BOMB_SIGNAL;
        timer = INTRO_TIMER;
        Camera camera = services().camera();
        if (camera != null) {
            camera.setX((short) CAMERA_X);
            camera.setY((short) CAMERA_Y);
            camera.setFrozen(true);
            camera.setLevelStarted(false);
        }
        if (playerEntity instanceof AbstractPlayableSprite player) {
            player.setControlLocked(true);
            player.clearLogicalInputState();
            player.clearForcedInputMask();
        }
    }

    private void allocateBombChild() {
        if (bombAllocated) {
            return;
        }
        bombAllocated = true;
        Camera camera = services().camera();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : CAMERA_X;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : CAMERA_Y;
        int bombX = cameraX + BOMB_SPAWN_X_OFFSET;
        int bombY = cameraY + BOMB_SPAWN_Y_OFFSET;
        spawnFreeChild(() -> new CutsceneKnucklesSkIntroBombInstance(new ObjectSpawn(
                bombX, bombY, spawn.objectId(), spawn.subtype(), 0, false, 0), this));
        services().playSfx(Sonic3kSfx.MISSILE_THROW.id);
    }

    private void updateExitRun() {
        xVelocity = Math.min(EXIT_MAX_X_VEL, xVelocity + EXIT_ACCEL);
        xSubpixel += xVelocity;
        x = xSubpixel >> 8;
        updateDynamicSpawn(x, y);

        Camera camera = services().camera();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : CAMERA_X;
        if (x > cameraX + EXIT_OFFSCREEN_X) {
            completeIntro(camera);
        }
    }

    private void updateLanding() {
        moveSpriteLightGravity();
        int floorDistance = floorDistanceOverride != null
                ? floorDistanceOverride
                : checkFloorDistance();
        if (floorDistance >= 0) {
            updateDynamicSpawn(x, y);
            return;
        }
        y += floorDistance;
        ySubpixel += floorDistance << 8;
        routine = ROUTINE_LANDING_ANIMATION;
        landingAnimationCallback = true;
        updateDynamicSpawn(x, y);
    }

    private int checkFloorDistance() {
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
        return floor.distance();
    }

    private void moveSpriteLightGravity() {
        xSubpixel += xVelocity;
        ySubpixel += yVelocity;
        x = xSubpixel >> 8;
        y = ySubpixel >> 8;
        yVelocity += LIGHT_GRAVITY;
    }

    private void completeIntro(Camera camera) {
        RespawnState checkpointState = services().checkpointState();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : CAMERA_X;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : CAMERA_Y;
        if (checkpointState != null) {
            checkpointState.restoreFromSaved(
                    POST_INTRO_RESTART_X,
                    POST_INTRO_RESTART_Y,
                    cameraX,
                    cameraY,
                    POST_INTRO_CHECKPOINT);
        }
        services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
        services().requestZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0);
        setDestroyed(true);
    }

    private boolean isKnucklesAlone() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private boolean lastStarPostHitIsSet() {
        RespawnState checkpointState = services().checkpointState();
        return checkpointState != null && checkpointState.getLastCheckpointIndex() > 0;
    }

    int getRoutineForTest() {
        return routine;
    }

    int getTimerForTest() {
        return timer;
    }

    void signalBombFallComplete() {
        workspaceFlags |= WORKSPACE_BOMB_FALL_COMPLETE;
    }

    void signalGrabAnimationComplete() {
        workspaceFlags |= WORKSPACE_GRAB_ANIMATION_COMPLETE;
    }

    void signalFallingMotionComplete() {
        workspaceFlags |= WORKSPACE_FALLING_MOTION_COMPLETE;
    }

    void setFloorDistanceForTest(int floorDistance) {
        floorDistanceOverride = floorDistance;
    }

    void signalLandingAnimationComplete() {
        workspaceFlags |= WORKSPACE_LANDING_ANIMATION_COMPLETE;
    }

    int getMappingFrameForTest() {
        return mappingFrame;
    }

    int getXVelocityForTest() {
        return xVelocity;
    }

    int getYVelocityForTest() {
        return yVelocity;
    }

    int getYRadiusForTest() {
        return yRadius;
    }

    boolean hasLandingAnimationCallbackForTest() {
        return landingAnimationCallback;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.KNUX_INTRO_LAYING);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }
}

final class CutsceneKnucklesSkIntroBombInstance extends AbstractObjectInstance {
    private static final int Y_RADIUS = 0x10;
    private static final int ROUTINE_FALLING = 0;
    private static final int ROUTINE_IMPACT_WAIT = 2;
    private static final int ROUTINE_POST_EXPLOSION_WAIT = 4;
    private static final int IMPACT_WAIT_TIMER = 2 * 60 - 1;
    private static final int PAL_POINTERS_SSZ1_INDEX = 0x1E;
    private static final int PALETTE_FLASH_DELAY = 7;
    private static final int PALETTE_FLASH_STEPS_TO_SIGNAL = 8;
    private static final int[][] EXPLOSION_CHILD_OFFSETS = {
            {0, 0},
            {8, -8},
            {-6, -0x0C},
            {-0x0A, 4},
            {8, 0x0A},
            {0, 0x18},
            {8, -0x18},
            {-0x0C, -0x20},
            {-0x16, -4},
            {0x18, 0}
    };

    private final CutsceneKnucklesSkIntroInstance parent;
    private int x;
    private int y;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int routine = ROUTINE_FALLING;
    private int timer;
    private int paletteFlashTimer;
    private int paletteFlashStepsRemaining;

    CutsceneKnucklesSkIntroBombInstance(ObjectSpawn spawn, CutsceneKnucklesSkIntroInstance parent) {
        super(spawn, "CutsceneKnuxSKIntroBomb");
        this.parent = parent;
        x = spawn.x();
        y = spawn.y();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    int getYRadiusForTest() {
        return Y_RADIUS;
    }

    int getYVelocityForTest() {
        return yVelocity;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (routine == ROUTINE_IMPACT_WAIT) {
            timer--;
            if (timer >= 0) {
                updateDynamicSpawn(x, y);
                return;
            }
            routine = ROUTINE_POST_EXPLOSION_WAIT;
            parent.signalGrabAnimationComplete();
            services().playSfx(Sonic3kSfx.MISSILE_EXPLODE.id);
            startExplosionAndPaletteFlash();
            updateDynamicSpawn(x, y);
            return;
        }
        if (routine == ROUTINE_POST_EXPLOSION_WAIT) {
            tickPaletteFlashWait();
            updateDynamicSpawn(x, y);
            return;
        }
        moveSprite();
        if (!isOnScreen() || yVelocity < 0) {
            updateDynamicSpawn(x, y);
            return;
        }
        int floorDistance = ObjectTerrainUtils.checkFloorDist(x, y, Y_RADIUS).distance();
        if (floorDistance < 0) {
            y += floorDistance;
            parent.signalBombFallComplete();
            services().playSfx(Sonic3kSfx.FLOOR_THUMP.id);
            yVelocity = -(yVelocity >> 2);
            if (yVelocity >= -0x0100) {
                routine = ROUTINE_IMPACT_WAIT;
                timer = IMPACT_WAIT_TIMER;
                services().fadeOutMusic();
            }
        }
        updateDynamicSpawn(x, y);
    }

    private void moveSprite() {
        SubpixelMotion.State state = new SubpixelMotion.State(
                x, y, xSubpixel, ySubpixel, xVelocity, yVelocity);
        SubpixelMotion.moveSprite(state, SubpixelMotion.S3K_GRAVITY);
        x = state.x;
        y = state.y;
        xSubpixel = state.xSub;
        ySubpixel = state.ySub;
        xVelocity = state.xVel;
        yVelocity = state.yVel;
    }

    private void startExplosionAndPaletteFlash() {
        paletteFlashTimer = 0;
        paletteFlashStepsRemaining = PALETTE_FLASH_STEPS_TO_SIGNAL;
        for (int[] offset : EXPLOSION_CHILD_OFFSETS) {
            spawnFreeChild(() -> new S3kBossExplosionChild(x + offset[0], y + offset[1]));
            services().playSfx(Sonic3kSfx.EXPLODE.id);
        }
        spawnFreeChild(() -> new MhzEndBossPaletteFadeController(captureCurrentPaletteLines(), true, PALETTE_FLASH_DELAY));
    }

    private void tickPaletteFlashWait() {
        if (paletteFlashStepsRemaining <= 0) {
            return;
        }
        paletteFlashTimer--;
        if (paletteFlashTimer >= 0) {
            return;
        }
        paletteFlashTimer = PALETTE_FLASH_DELAY;
        paletteFlashStepsRemaining--;
        if (paletteFlashStepsRemaining <= 0) {
            parent.signalFallingMotionComplete();
            spawnEggRoboEntryChild();
            restoreSszPaletteLine2();
            setDestroyed(true);
        }
    }

    private void spawnEggRoboEntryChild() {
        Camera camera = services().camera();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : 0x0560;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : 0x0948;
        services().playMusic(Sonic3kMusic.BOSS.id);
        CutsceneKnucklesSkIntroEggRoboEntryInstance entry = spawnFreeChild(() -> new CutsceneKnucklesSkIntroEggRoboEntryInstance(new ObjectSpawn(
                cameraX + 0x0110, cameraY - 0x0060, spawn.objectId(), spawn.subtype(), 0, false, 0)));
        entry.spawnVisualChildren();
    }

    private void restoreSszPaletteLine2() {
        try {
            int entryAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                    + PAL_POINTERS_SSZ1_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
            int sourceAddr = services().rom().read32BitAddr(entryAddr) & 0x00FFFFFF;
            byte[] line = services().rom().readBytes(sourceAddr, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                    S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                    1,
                    line,
                    true);
        } catch (IOException | RuntimeException ignored) {
            // Partial object harnesses may not provide a ROM or level palette surface.
        }
    }

    private byte[][] captureCurrentPaletteLines() {
        try {
            Level level = services().currentLevel();
            if (level == null) {
                return null;
            }
            byte[][] lines = new byte[Math.min(4, level.getPaletteCount())][];
            for (int line = 0; line < lines.length; line++) {
                lines[line] = encodePaletteLine(level.getPalette(line));
            }
            return lines;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static byte[] encodePaletteLine(Palette palette) {
        byte[] line = new byte[32];
        for (int color = 0; color < 16; color++) {
            int word = encodeSegaColor(palette.getColor(color));
            line[color * 2] = (byte) ((word >>> 8) & 0xFF);
            line[color * 2 + 1] = (byte) (word & 0xFF);
        }
        return line;
    }

    private static int encodeSegaColor(Palette.Color color) {
        int red = ((color.r & 0xFF) * 7 + 127) / 255;
        int green = ((color.g & 0xFF) * 7 + 127) / 255;
        int blue = ((color.b & 0xFF) * 7 + 127) / 255;
        return ((blue & 0x07) << 9) | ((green & 0x07) << 5) | ((red & 0x07) << 1);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }
}

final class CutsceneKnucklesSkIntroEggRoboEntryInstance extends AbstractObjectInstance {
    private static final int ROUTINE_DROP_IN = 0;
    private static final int ROUTINE_SWING_EXIT = 2;
    private static final int SWING_SETUP_MAX_SPEED = 0x00C0;
    private static final int SWING_SETUP_ACCEL = 0x0010;
    private static final int EXIT_DELAY = 0x007F;
    private static final int EXIT_X_VELOCITY = 0x0400;

    private int routine = ROUTINE_DROP_IN;
    private int x;
    private int y;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int timer;
    private int mappingFrame = 1;
    private boolean swingGoingDown;
    private boolean laserFireRequested;

    CutsceneKnucklesSkIntroEggRoboEntryInstance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxSKIntroEggRoboEntry");
        x = spawn.x();
        y = spawn.y();
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
        if (routine == ROUTINE_DROP_IN) {
            y++;
            int cameraY = services().camera() != null ? services().camera().getY() & 0xFFFF : 0x0948;
            if (y > cameraY + 0x0040) {
                routine = ROUTINE_SWING_EXIT;
                timer = EXIT_DELAY;
                swingSetup1();
            }
            updateMappingFrame(vIntRunCount);
            updateDynamicSpawn(x, y);
            return;
        }

        timer--;
        if (timer == 0) {
            xVelocity = EXIT_X_VELOCITY;
        }
        swingUpAndDown();
        moveSprite2();
        updateMappingFrame(vIntRunCount);
        updateDynamicSpawn(x, y);
        if (!isOnScreen()) {
            setDestroyed(true);
        }
    }

    private void swingSetup1() {
        yVelocity = SWING_SETUP_MAX_SPEED;
        swingGoingDown = false;
    }

    private void swingUpAndDown() {
        if (!swingGoingDown) {
            yVelocity -= SWING_SETUP_ACCEL;
            if (yVelocity <= -SWING_SETUP_MAX_SPEED) {
                swingGoingDown = true;
                yVelocity += SWING_SETUP_ACCEL;
            }
            return;
        }
        yVelocity += SWING_SETUP_ACCEL;
        if (yVelocity >= SWING_SETUP_MAX_SPEED) {
            swingGoingDown = false;
            yVelocity -= SWING_SETUP_ACCEL;
        }
    }

    private void moveSprite2() {
        SubpixelMotion.State motion = new SubpixelMotion.State(
                x, y, xSubpixel, ySubpixel, xVelocity, yVelocity);
        SubpixelMotion.moveSprite2(motion);
        x = motion.x;
        y = motion.y;
        xSubpixel = motion.xSub;
        ySubpixel = motion.ySub;
    }

    private void updateMappingFrame(int vIntRunCount) {
        mappingFrame = (vIntRunCount & 1) == 0 ? 1 : 3;
    }

    int getYVelocityForChild() {
        return yVelocity;
    }

    void requestLaserFire() {
        laserFireRequested = true;
    }

    boolean isLaserFireRequested() {
        return laserFireRequested;
    }

    void clearLaserFireRequest() {
        laserFireRequested = false;
    }

    void spawnVisualChildren() {
        spawnFreeChild(() -> new CutsceneKnucklesSkIntroEggRoboLowerVisualChild(this));
        spawnFreeChild(() -> new CutsceneKnucklesSkIntroEggRoboUpperVisualChild(this));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SSZ_EGG_ROBO);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }
}

final class CutsceneKnucklesSkIntroEggRoboLowerVisualChild extends AbstractObjectInstance {
    private static final int X_OFFSET = -0x0C;
    private static final int Y_OFFSET = 0x1C;

    private final transient CutsceneKnucklesSkIntroEggRoboEntryInstance parent;
    private int x;
    private int y;
    private int mappingFrame = 5;

    CutsceneKnucklesSkIntroEggRoboLowerVisualChild(CutsceneKnucklesSkIntroEggRoboEntryInstance parent) {
        super(new ObjectSpawn(parent.getX() + X_OFFSET, parent.getY() + Y_OFFSET,
                Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0),
                "CutsceneKnuxSKIntroEggRoboLowerVisual");
        this.parent = parent;
        refreshPosition();
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
        refreshPosition();
        int parentYVelocity = parent.getYVelocityForChild();
        if (parentYVelocity < 0) {
            mappingFrame = 6;
        } else if (parentYVelocity < 0x20) {
            mappingFrame = 5;
        } else {
            mappingFrame = 4;
        }
        updateDynamicSpawn(x, y);
    }

    private void refreshPosition() {
        x = parent.getX() + X_OFFSET;
        y = parent.getY() + Y_OFFSET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SSZ_EGG_ROBO);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }
}

final class CutsceneKnucklesSkIntroEggRoboUpperVisualChild extends AbstractObjectInstance {
    private static final int X_OFFSET = -0x1C;
    private static final int Y_OFFSET = -4;
    private static final int LASER_X_OFFSET = 0x0B;
    private static final int LASER_Y_OFFSET = -4;
    private static final int LASER_COOLDOWN = 0x5F;

    private final transient CutsceneKnucklesSkIntroEggRoboEntryInstance parent;
    private int x;
    private int y;
    private int laserCooldown = -1;

    CutsceneKnucklesSkIntroEggRoboUpperVisualChild(CutsceneKnucklesSkIntroEggRoboEntryInstance parent) {
        super(new ObjectSpawn(parent.getX() + X_OFFSET, parent.getY() + Y_OFFSET,
                Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0),
                "CutsceneKnuxSKIntroEggRoboUpperVisual");
        this.parent = parent;
        refreshPosition();
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
        refreshPosition();
        if (laserCooldown >= 0) {
            laserCooldown--;
            if (laserCooldown < 0) {
                parent.clearLaserFireRequest();
            }
            updateDynamicSpawn(x, y);
            return;
        }
        if (parent.isLaserFireRequested()) {
            laserCooldown = LASER_COOLDOWN;
            spawnFreeChild(() -> new CutsceneKnucklesSkIntroEggRoboLaserChild(new ObjectSpawn(
                    x + LASER_X_OFFSET, y + LASER_Y_OFFSET,
                    Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0)));
        }
        updateDynamicSpawn(x, y);
    }

    private void refreshPosition() {
        x = parent.getX() + X_OFFSET;
        y = parent.getY() + Y_OFFSET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SSZ_EGG_ROBO);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(2, x, y, false, false);
    }
}

final class CutsceneKnucklesSkIntroEggRoboLaserChild extends AbstractObjectInstance
        implements TouchResponseProvider {
    private static final int ROUTINE_CHARGE = 0;
    private static final int ROUTINE_FIRE = 2;
    private static final int CHARGE_TIMER = 0x1F;
    private static final int ACTIVE_COLLISION_FLAGS = 0x9C;
    private static final int X_VELOCITY = -0x0800;

    private int routine = ROUTINE_CHARGE;
    private int timer = CHARGE_TIMER;
    private int x;
    private int y;
    private int xSubpixel;
    private int xVelocity;
    private int mappingFrame;
    private int collisionFlags;

    CutsceneKnucklesSkIntroEggRoboLaserChild(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxSKIntroEggRoboLaser");
        x = spawn.x();
        y = spawn.y();
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
        if (routine == ROUTINE_CHARGE) {
            mappingFrame = (vIntRunCount & 1) == 0 ? 0 : 7;
            timer--;
            if (timer < 0) {
                routine = ROUTINE_FIRE;
                mappingFrame = 7;
                collisionFlags = ACTIVE_COLLISION_FLAGS;
                xVelocity = X_VELOCITY;
                services().playSfx(Sonic3kSfx.LASER.id);
            }
            updateDynamicSpawn(x, y);
            return;
        }

        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, xSubpixel, 0, xVelocity, 0);
        SubpixelMotion.moveSprite2(motion);
        x = motion.x;
        xSubpixel = motion.xSub;
        updateDynamicSpawn(x, y);
        if (!isOnScreen()) {
            setDestroyed(true);
        }
    }

    int getMappingFrameForTest() {
        return mappingFrame;
    }

    int getXVelocityForTest() {
        return xVelocity;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SSZ_EGG_ROBO);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }
}
