package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.SwingMotion;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $9F — Marble Garden Act 1 miniboss.
 *
 * <p>Ports {@code Obj_MGZMiniboss} from the S3K disassembly and follows the
 * shared Tunnelbot routines for swing motion, drill animation, ceiling contact,
 * debris, hit flash, and defeat flow.
 */
public final class MgzMinibossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {

    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT_CAMERA = 2;
    private static final int ROUTINE_DRILL = 4;
    private static final int ROUTINE_TUNNEL_UP = 6;
    private static final int ROUTINE_SHAKE_CEILING = 8;
    private static final int ROUTINE_WAIT = 10;
    private static final int ROUTINE_DROP_SHAKE = 12;
    private static final int ROUTINE_FALL = 14;
    private static final int ROUTINE_RISE = 16;
    private static final int ROUTINE_RETURN_SWING = 18;
    private static final int ROUTINE_DEFEATED = 20;

    private static final int HIT_COUNT = 6;
    private static final int BODY_COLLISION_FLAGS = 0x10;
    private static final int ARM_COLLISION_FLAGS = 0x9E;
    private static final int COLLISION_SIZE = 0x10;
    private static final int INVULNERABILITY_TIME = 0x20;
    private static final int PRIORITY_BUCKET = 5;
    private static final int OBJECT_PATTERN_BASE = PatternAtlasRange.OBJECTS.base();

    private static final int BODY_Y_RADIUS = 0x28;
    private static final int ARENA_LOCK_X = 0x2E00;
    private static final int ARENA_TARGET_MAX_Y = 0x0E10;

    private static final int DRILL_ANIM_INITIAL_DELAY = 5;
    private static final int DRILL_ANIM_LOOP_COUNT = 4;
    private static final int[] DRILL_ANIM_FRAMES = {0, 1, 2};
    private static final int[] TUNNEL_ANIM_FRAMES = {0, 1, 2};
    private static final int TUNNEL_ANIM_DELAY = 0;

    private static final int SWING_MAX_VELOCITY = 0xC0;
    private static final int SWING_ACCELERATION = 0x10;
    private static final int PLATFORM_SWING_MAX_VELOCITY = 0x100;

    private static final int SHAKE_TIME = 0x7F;
    private static final int WAIT_TIME = 0x3F;
    private static final int FALL_TIME = 0x2F;
    private static final int KNUCKLES_FALL_TIME = 0x17;
    private static final int RISE_TIME = 0x3F;
    private static final int RETURN_SWING_TIME = 0x3F;
    private static final int RETURN_SWING_RELEASE_TIME = 0x1F;
    private static final int DEFEAT_WAIT_TIME = 0x3F;
    private static final int LEVEL_MUSIC_FADE_TIME = 2 * 60;
    private static final int MINIBOSS_MUSIC_FADE_TIME = 90;

    private static final int[] DROP_X_OFFSETS = {0x30, 0x48, 0x60, 0x78, 0xC8, 0xE0, 0xF8, 0x110};
    private static final int[] DEFEAT_FRAMES = {4, 3, 5, 6, 6};
    private static final int[] DEFEAT_X_OFFSETS = {0, -0x1C, 0x1C, -0x1C, 0x1C};
    private static final int[] DEFEAT_Y_OFFSETS = {0, 0, 0, -0x16, -0x16};
    private static final int[] DEFEAT_X_VELS = {-0x180, -0x100, 0x100, -0x80, 0x80};
    private static final int[] DEFEAT_Y_VELS = {-0x340, -0x300, -0x300, -0x280, -0x280};

    private static final int[] FLASH_INDICES = {12, 13, 14};
    private static final int[] FLASH_DARK = {0x0CAA, 0x0866, 0x0644};
    private static final int[] FLASH_BRIGHT = {0x0EEE, 0x0EEE, 0x0EEE};

    private static final int[] SCREEN_SHAKE_CONTINUOUS = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    private int anchorX;
    private int anchorY;
    private int routineTimer;
    private int swingVelocity;
    private int ySubpixel;
    private boolean swingDirectionDown;
    private int animFrameIndex;
    private int animTimer;
    private int drillDelay;
    private int drillLoopCounter;
    private int mappingFrame;
    private boolean facingRight;
    private boolean upsideDown;
    private boolean arenaEngaged;
    private boolean knucklesRecovery;
    private boolean flashColorsSaved;
    private boolean defeatHandoffQueued;
    private boolean defeatWaitArmed;
    private final int[] savedFlashSegaWords = new int[FLASH_INDICES.length];
    private S3kBossExplosionController defeatExplosionController;
    private DrillArmChild leftArm;
    private DrillArmChild rightArm;

    public MgzMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "MGZMiniboss");
    }

    @Override
    protected void initializeBossState() {
        anchorX = spawn.x();
        anchorY = spawn.y();
        state.x = anchorX;
        state.y = anchorY;
        state.xFixed = anchorX << 16;
        state.yFixed = anchorY << 16;
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        routineTimer = -1;
        swingVelocity = SWING_MAX_VELOCITY;
        ySubpixel = 0;
        swingDirectionDown = false;
        animFrameIndex = 0;
        animTimer = 0;
        drillDelay = DRILL_ANIM_INITIAL_DELAY;
        drillLoopCounter = 0;
        mappingFrame = 0;
        facingRight = false;
        upsideDown = false;
        arenaEngaged = false;
        knucklesRecovery = false;
        flashColorsSaved = false;
        defeatHandoffQueued = false;
        defeatWaitArmed = false;
        defeatExplosionController = null;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        updateHitFlash();

        switch (state.routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_WAIT_CAMERA -> updateWaitCamera();
            case ROUTINE_DRILL -> updateDrill();
            case ROUTINE_TUNNEL_UP -> updateTunnelUp();
            case ROUTINE_SHAKE_CEILING -> updateCeilingShake(vIntRunCount);
            case ROUTINE_WAIT -> updateWait(playerEntity);
            case ROUTINE_DROP_SHAKE -> updateDropShake(vIntRunCount, playerEntity);
            case ROUTINE_FALL -> updateFall();
            case ROUTINE_RISE -> updateRise();
            case ROUTINE_RETURN_SWING -> updateReturnSwing();
            case ROUTINE_DEFEATED -> updateDefeated(vIntRunCount);
            default -> {
            }
        }

        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
    }

    private void updateInit() {
        services().gameState().setCurrentBossId(Sonic3kObjectIds.MGZ_MINIBOSS);
        services().camera().setMaxX((short) ARENA_LOCK_X);
        services().camera().setMaxYTarget((short) ARENA_TARGET_MAX_Y);
        ensureSupportArtReady();
        spawnMinibossMusicTransition();
        spawnArmChildren();
        state.routine = ROUTINE_WAIT_CAMERA;
    }

    private void updateWaitCamera() {
        updateSwing();
        applySwingVelocity();
        var camera = services().camera();
        camera.setMinX((short) camera.getX());
        camera.setMaxX((short) ARENA_LOCK_X);
        camera.setMaxYTarget((short) ARENA_TARGET_MAX_Y);

        // loc_85C7E waits until Camera_max_Y_pos has eased down to the
        // requested target before it locks the vertical arena and invokes the
        // drill callback. The horizontal threshold alone is not sufficient.
        if ((camera.getMaxY() & 0xFFFF) > (camera.getMaxYTarget() & 0xFFFF)) {
            return;
        }
        camera.setMinY(camera.getMaxYTarget());
        if (camera.getX() < ARENA_LOCK_X) {
            return;
        }

        arenaEngaged = true;
        camera.setMinX((short) ARENA_LOCK_X);
        camera.setMaxX((short) ARENA_LOCK_X);
        enterDrill();
    }

    private void updateDrill() {
        enforceArenaLock();
        updateSwing();
        applySwingVelocity();
        animateRawGetFaster();
    }

    private void updateTunnelUp() {
        enforceArenaLock();
        animateRaw();
        state.y--;

        TerrainCheckResult ceiling = ObjectTerrainUtils.checkNativeUpwardCeilingDist(
                state.x, state.y, BODY_Y_RADIUS);
        if (ceiling.distance() < 0) {
            enterCeilingShake();
        }
    }

    private void updateCeilingShake(int vIntRunCount) {
        enforceArenaLock();
        animateRaw();
        // ROM TunnelbotMiniboss_RumbleWait (docs/skdisasm/sonic3k.asm:184790-184796):
        //   moveq #-2,d0 / move.b (V_int_run_count+3).w,d1 / btst #0,d1 / beq.s + / moveq #1,d0
        // (V_int_run_count+3) is an ADDRESS: V_int_run_count is a longword
        // (addq.l #1,(V_int_run_count).w, sonic3k.asm:543), so +3 selects its LOW
        // BYTE. It is not "counter plus three" -- adding 3 inverts bit 0 and so
        // inverts the -2/+1 rumble step, putting the boss's hitbox one frame out
        // of phase with the ROM.
        int vIntLowByte = vIntRunCount & 0xFF;
        state.y += ((vIntLowByte & 1) == 0) ? -2 : 1;
        applyContinuousShake(vIntLowByte);
        if ((vIntLowByte & 7) == 0) {
            services().playSfx(Sonic3kSfx.RUMBLE_2.id);
            spawnCeilingDebris();
        }
        if (--routineTimer < 0) {
            clearScreenShake();
            state.routine = ROUTINE_WAIT;
            routineTimer = WAIT_TIME;
        }
    }

    private void updateWait(PlayableEntity playerEntity) {
        enforceArenaLock();
        if (--routineTimer >= 0) {
            return;
        }

        state.routine = ROUTINE_DROP_SHAKE;
        upsideDown = true;
        routineTimer = SHAKE_TIME;
        setScreenShakeActive(true);
        int randomIndex = ((services().rng().nextWord() & 0xFFFF) & 0x0E) >> 1;
        state.x = services().camera().getX() + DROP_X_OFFSETS[randomIndex];
        state.y -= 0x40;
        if (isKnuckles(playerEntity)) {
            boolean mirrored = randomIndex < 4;
            int cameraX = services().camera() != null ? services().camera().getX() : state.x;
            int cameraY = services().camera() != null ? services().camera().getY() : state.y;
            spawnChild(() -> new KnucklesSpikePlatformChild(this, mirrored, cameraX, cameraY));
        }
    }

    private void updateDropShake(int vIntRunCount, PlayableEntity playerEntity) {
        enforceArenaLock();
        animateRaw();
        // ROM MGZMiniboss_DropRumbleWait (docs/skdisasm/sonic3k.asm:184932-184940):
        // same (V_int_run_count+3) low-byte read as updateCeilingShake above,
        // with the step signs mirrored (+2 / -1).
        int vIntLowByte = vIntRunCount & 0xFF;
        state.y += ((vIntLowByte & 1) == 0) ? 2 : -1;
        applyContinuousShake(vIntLowByte);
        if ((vIntLowByte & 7) == 0) {
            services().playSfx(Sonic3kSfx.RUMBLE_2.id);
            spawnCeilingDebris();
        }
        if (--routineTimer >= 0) {
            return;
        }

        clearScreenShake();
        state.routine = ROUTINE_FALL;
        knucklesRecovery = isKnuckles(playerEntity);
        routineTimer = knucklesRecovery ? KNUCKLES_FALL_TIME : FALL_TIME;
    }

    private void updateFall() {
        enforceArenaLock();
        animateRaw();
        state.y += 4;
        if (--routineTimer >= 0) {
            return;
        }
        if (knucklesRecovery) {
            enterReturnSwing();
        } else {
            state.routine = ROUTINE_RISE;
            routineTimer = RISE_TIME;
        }
    }

    private void updateRise() {
        enforceArenaLock();
        animateRaw();
        state.y--;
        if (--routineTimer < 0) {
            enterReturnSwing();
        }
    }

    private void updateReturnSwing() {
        enforceArenaLock();
        updateSwing();
        applySwingVelocity();
        animateRaw();
        if (--routineTimer >= 0) {
            return;
        }
        if (upsideDown) {
            // loc_887A4 clears the vertical render flip, then installs a
            // second $1F-frame Obj_Wait callback before loc_887BA returns to
            // the tunnel routine. Horizontal facing is unchanged.
            upsideDown = false;
            routineTimer = RETURN_SWING_RELEASE_TIME;
            return;
        }
        enterTunnelUp();
    }

    private void updateDefeated(int vIntRunCount) {
        if (defeatExplosionController != null && !defeatExplosionController.isFinished()) {
            defeatExplosionController.tick();
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                for (var pending : defeatExplosionController.drainPendingExplosions()) {
                    if (pending.playSfx()) {
                        services().playSfx(Sonic3kSfx.EXPLODE.id);
                    }
                    objectManager.addDynamicObjectAfterCurrent(new S3kBossExplosionChild(pending.x(), pending.y()));
                }
            }
        }

        // The final hit changes the object's code pointer during the player
        // touch pass. The ROM boss slot has already executed for that frame,
        // so Wait_FadeToLevelMusic cannot consume $2E until the next pass.
        if (!defeatWaitArmed) {
            defeatWaitArmed = true;
        } else if (!defeatHandoffQueued && --routineTimer < 0) {
            queuePostDefeatFlow();
        }

        if (defeatHandoffQueued && (defeatExplosionController == null || defeatExplosionController.isFinished())) {
            setDestroyed(true);
        }
    }

    private void enterDrill() {
        state.routine = ROUTINE_DRILL;
        animFrameIndex = 0;
        animTimer = 0;
        drillDelay = DRILL_ANIM_INITIAL_DELAY;
        drillLoopCounter = 0;
        mappingFrame = DRILL_ANIM_FRAMES[0];
    }

    private void enterTunnelUp() {
        state.routine = ROUTINE_TUNNEL_UP;
        animFrameIndex = 0;
        animTimer = 0;
    }

    private void enterCeilingShake() {
        state.routine = ROUTINE_SHAKE_CEILING;
        routineTimer = SHAKE_TIME;
        setScreenShakeActive(true);
    }

    private void enterReturnSwing() {
        state.routine = ROUTINE_RETURN_SWING;
        routineTimer = RETURN_SWING_TIME;
        swingVelocity = SWING_MAX_VELOCITY;
        ySubpixel = 0;
        swingDirectionDown = false;
    }

    private void animateRawGetFaster() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        animFrameIndex++;
        if (animFrameIndex >= DRILL_ANIM_FRAMES.length) {
            animFrameIndex = 0;
            if (drillDelay > 0) {
                drillDelay--;
            } else {
                drillLoopCounter++;
                if (drillLoopCounter >= DRILL_ANIM_LOOP_COUNT) {
                    enterTunnelUp();
                    return;
                }
            }
        }

        mappingFrame = DRILL_ANIM_FRAMES[animFrameIndex];
        animTimer = drillDelay;
    }

    private void animateRaw() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        animFrameIndex++;
        if (animFrameIndex >= TUNNEL_ANIM_FRAMES.length) {
            animFrameIndex = 0;
        }
        mappingFrame = TUNNEL_ANIM_FRAMES[animFrameIndex];
        animTimer = TUNNEL_ANIM_DELAY;
    }

    private void updateSwing() {
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCELERATION, swingVelocity, SWING_MAX_VELOCITY, swingDirectionDown);
        swingVelocity = result.velocity();
        swingDirectionDown = result.directionDown();
    }

    private void applySwingVelocity() {
        int fixedY = (state.y << 8) | (ySubpixel & 0xFF);
        fixedY += swingVelocity;
        state.y = fixedY >> 8;
        ySubpixel = fixedY & 0xFF;
    }

    private void enforceArenaLock() {
        if (!arenaEngaged) {
            return;
        }
        services().camera().setMinX((short) ARENA_LOCK_X);
        services().camera().setMaxX((short) ARENA_LOCK_X);
        services().camera().setMaxYTarget((short) ARENA_TARGET_MAX_Y);
    }

    private void spawnMinibossMusicTransition() {
        spawnChild(() -> new SongFadeTransitionInstance(MINIBOSS_MUSIC_FADE_TIME, Sonic3kMusic.MINIBOSS.id));
    }

    private void ensureSupportArtReady() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        boolean needsCache = rendererMissingOrUnready(renderManager, Sonic3kObjectArtKeys.MGZ_MINIBOSS)
                || rendererMissingOrUnready(renderManager, Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE)
                || rendererMissingOrUnready(renderManager, Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS);
        boolean needsBossExplosion = rendererMissingOrUnready(renderManager, com.openggf.level.objects.ObjectArtKeys.BOSS_EXPLOSION);
        if (!needsCache && !needsBossExplosion) {
            return;
        }

        if (renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider s3kProvider) {
            s3kProvider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.MGZ_MINIBOSS);
            s3kProvider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE);
            s3kProvider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS);
            s3kProvider.ensureBossExplosionArtLoaded();
        }

        if (services().graphicsManager() != null) {
            renderManager.ensurePatternsCached(services().graphicsManager(), OBJECT_PATTERN_BASE);
        }
    }

    private static boolean rendererMissingOrUnready(ObjectRenderManager renderManager, String key) {
        PatternSpriteRenderer renderer = renderManager.getRenderer(key);
        return renderer == null || !renderer.isReady();
    }

    private void spawnArmChildren() {
        if (leftArm == null || leftArm.isDestroyed()) {
            leftArm = spawnChild(() -> new DrillArmChild(this, -0x1C, -0x16));
        }
        if (rightArm == null || rightArm.isDestroyed()) {
            rightArm = spawnChild(() -> new DrillArmChild(this, 0x1C, -0x16));
        }
    }

    void rewindAttachArmChild(DrillArmChild armChild) {
        if (armChild.xOffset < 0) {
            leftArm = armChild;
        } else if (armChild.xOffset > 0) {
            rightArm = armChild;
        } else if (leftArm == null || leftArm.isDestroyed()) {
            leftArm = armChild;
        } else {
            rightArm = armChild;
        }
    }

    private void destroyArms() {
        if (leftArm != null) {
            leftArm.setDestroyed(true);
        }
        if (rightArm != null) {
            rightArm.setDestroyed(true);
        }
    }

    private void spawnCeilingDebris() {
        var camera = services().camera();
        if (camera == null) {
            return;
        }

        int random = services().rng().nextWord() & 0xFFFF;
        int x = camera.getX() - 0x40 + (random & 0x1FF);
        int y = camera.getY() - 0x20;
        int frame = (services().rng().nextWord() & 0xFFFF) & 0x03;
        boolean spire = frame == 0;
        if (spire) {
            spawnChild(() -> new CeilingSpireChild(x, y, frame));
        } else {
            spawnChild(() -> new CeilingDebrisChild(x, y, frame, false));
        }
    }

    private void spawnDefeatFragments() {
        for (int i = 0; i < DEFEAT_FRAMES.length; i++) {
            int index = i;
            spawnChild(() -> new DefeatFragmentChild(
                    state.x + DEFEAT_X_OFFSETS[index],
                    state.y + DEFEAT_Y_OFFSETS[index],
                    DEFEAT_FRAMES[index],
                    DEFEAT_X_VELS[index],
                    DEFEAT_Y_VELS[index]));
        }
    }

    private void updateHitFlash() {
        if (!state.invulnerable) {
            restoreFlashColors();
            return;
        }

        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= 1) {
            state.invulnerabilityTimer--;
            if (state.invulnerabilityTimer <= 0) {
                state.invulnerable = false;
            }
            return;
        }

        Palette palette = level.getPalette(1);
        if (!flashColorsSaved) {
            for (int i = 0; i < FLASH_INDICES.length; i++) {
                Palette.Color existing = palette.getColor(FLASH_INDICES[i]);
                savedFlashSegaWords[i] = segaWordFromColor(existing);
            }
            flashColorsSaved = true;
        }

        int[] colors = ((state.invulnerabilityTimer & 1) != 0) ? FLASH_DARK : FLASH_BRIGHT;
        applyFlashColors(colors);

        state.invulnerabilityTimer--;
        if (state.invulnerabilityTimer <= 0) {
            state.invulnerable = false;
            restoreFlashColors();
        }
    }

    private void restoreFlashColors() {
        if (!flashColorsSaved) {
            return;
        }
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= 1) {
            flashColorsSaved = false;
            return;
        }
        Palette palette = level.getPalette(1);
        applyFlashColors(savedFlashSegaWords);
        flashColorsSaved = false;
    }

    private void applyFlashColors(int[] segaWords) {
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.MGZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                FLASH_INDICES,
                segaWords);
    }

    private void applyContinuousShake(int frameCounter) {
        MgzZoneRuntimeState mgz = resolveMgzRuntimeState();
        if (mgz != null) {
            mgz.requestScreenShakeOffset(SCREEN_SHAKE_CONTINUOUS[frameCounter & 0x3F]);
        }
    }

    private void clearScreenShake() {
        MgzZoneRuntimeState mgz = resolveMgzRuntimeState();
        if (mgz != null) {
            mgz.clearScreenShakeOffset();
        }
        setScreenShakeActive(false);
    }

    private void setScreenShakeActive(boolean active) {
        if (services().gameState() != null) {
            services().gameState().setScreenShakeActive(active);
        }
    }

    private MgzZoneRuntimeState resolveMgzRuntimeState() {
        if (services().zoneRuntimeRegistry() == null) {
            return null;
        }
        return S3kRuntimeStates.currentMgz(services().zoneRuntimeRegistry()).orElse(null);
    }

    private boolean isKnuckles(PlayableEntity playerEntity) {
        return playerEntity instanceof AbstractPlayableSprite player
                && "knuckles".equalsIgnoreCase(player.getCode());
    }

    private void queuePostDefeatFlow() {
        defeatHandoffQueued = true;
        clearScreenShake();
        if (services().levelGamestate() != null) {
            services().levelGamestate().pauseTimer();
        }

        int levelMusicId = services().getCurrentLevelMusicId();
        if (levelMusicId > 0) {
            spawnChild(() -> new SongFadeTransitionInstance(LEVEL_MUSIC_FADE_TIME, levelMusicId));
        }
        // ROM: Obj_EndSignControlDoSign spawns the signpost via CreateChild6_Simple,
        // inheriting the control object's x_pos — the miniboss's X at the moment of
        // defeat, not the camera lock point.
        int signpostX = state.x;
        spawnChild(() -> new S3kBossDefeatSignpostFlow(
                signpostX, services().currentAct(), S3kBossDefeatSignpostFlow.CleanupAction.NONE,
                0, 0, 1));
    }

    private static int segaWordFromColor(Palette.Color color) {
        int r3 = quantizeSegaComponent(color.r);
        int g3 = quantizeSegaComponent(color.g);
        int b3 = quantizeSegaComponent(color.b);
        return (b3 << 9) | (g3 << 5) | (r3 << 1);
    }

    private static int quantizeSegaComponent(byte component) {
        return (((component & 0xFF) * 7) + 127) / 255;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // sub_88A62 only drives flash and collision restore.
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    public int getCollisionFlags() {
        if (state.invulnerable || state.defeated) {
            return 0;
        }
        return BODY_COLLISION_FLAGS;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Obj_MGZMiniboss runs its movement routine before
        // Draw_And_Touch_Sprite publishes the SST pointer. The following player
        // pass dereferences that live post-movement y_pos, rather than the
        // frame-start coordinate retained by the generic snapshot.
        // docs/skdisasm/sonic3k.asm:184817-184834,20656-20708.
        return true;
    }

    @Override
    protected boolean usesBaseHitHandler() {
        return false;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULNERABILITY_TIME;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (state.invulnerable || state.defeated) {
            return;
        }

        state.hitCount--;
        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            services().gameState().addScore(1000);
            onDefeatStarted();
            return;
        }

        state.invulnerable = true;
        state.invulnerabilityTimer = INVULNERABILITY_TIME;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        onHitTaken(state.hitCount);
    }

    @Override
    protected void onDefeatStarted() {
        restoreFlashColors();
        clearScreenShake();
        destroyArms();
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        state.routine = ROUTINE_DEFEATED;
        upsideDown = false;
        routineTimer = DEFEAT_WAIT_TIME;
        defeatWaitArmed = false;
        defeatExplosionController = new S3kBossExplosionController(state.x, state.y, 0, services().rng());
        spawnDefeatFragments();
        // sub_88AB4 allocates loc_887DA immediately when the final hit is
        // consumed. Its later SST slot advances Camera_X_pos once during the
        // same ExecuteObjects pass, independently of the delayed sign flow.
        spawnChild(() -> new MgzBossCameraScrollHelper(ARENA_LOCK_X));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_MINIBOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, state.x, state.y, facingRight, upsideDown);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    private static final class DrillArmChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int PRIORITY_BUCKET = 5;
        private static final int LEFT_ARM_SUBTYPE = 0x10;
        private static final int RIGHT_ARM_SUBTYPE = 0x11;
        private MgzMinibossInstance parent;
        // Non-final so the generic rewind field capturer can reapply the captured
        // left/right differentiator after generic recreate constructs this child.
        private int xOffset;
        private int yOffset;
        private int currentX;
        private int currentY;

        private DrillArmChild(ObjectSpawn spawn) {
            super(spawn, "MGZMinibossArm");
            this.parent = null;
            this.xOffset = 0;
            this.yOffset = 0;
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        private DrillArmChild(MgzMinibossInstance parent, int xOffset, int yOffset) {
            super(new ObjectSpawn(parent.state.x + adjustedOffset(xOffset, parent.facingRight),
                    parent.state.y + adjustedOffset(yOffset, parent.upsideDown),
                    0, semanticSubtype(xOffset), 0, false, 0),
                    "MGZMinibossArm");
            this.parent = parent;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.currentX = parent.state.x + adjustedXOffset();
            this.currentY = parent.state.y + adjustedYOffset();
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            MgzMinibossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, MgzMinibossInstance.class);
            if (liveParent == null) {
                return null;
            }
            DrillArmChild restored = new DrillArmChild(
                    liveParent,
                    capturedXOffset(ctx, liveParent),
                    0);
            liveParent.rewindAttachArmChild(restored);
            return restored;
        }

        private static int capturedXOffset(RewindRecreateContext ctx, MgzMinibossInstance parent) {
            ObjectSpawn spawn = ctx.spawn();
            if (spawn != null) {
                if (spawn.subtype() == LEFT_ARM_SUBTYPE) {
                    return -0x1C;
                }
                if (spawn.subtype() == RIGHT_ARM_SUBTYPE) {
                    return 0x1C;
                }
            }
            return deriveCapturedXOffsetFromPhysicalSpawn(ctx, parent);
        }

        private static int deriveCapturedXOffsetFromPhysicalSpawn(
                RewindRecreateContext ctx, MgzMinibossInstance parent) {
            ObjectSpawn spawn = ctx.spawn();
            if (spawn == null) {
                return 0;
            }
            if (spawn.x() < parent.state.x) {
                return -0x1C;
            }
            if (spawn.x() > parent.state.x) {
                return 0x1C;
            }
            return 0;
        }

        private static int semanticSubtype(int xOffset) {
            return xOffset < 0 ? LEFT_ARM_SUBTYPE : RIGHT_ARM_SUBTYPE;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed() || parent.state.defeated) {
                setDestroyed(true);
                return;
            }
            currentX = parent.state.x + adjustedXOffset();
            currentY = parent.state.y + adjustedYOffset();
            updateDynamicSpawn(currentX, currentY);
        }

        private int adjustedXOffset() {
            return adjustedOffset(xOffset, parent.facingRight);
        }

        private int adjustedYOffset() {
            return adjustedOffset(yOffset, parent.upsideDown);
        }

        private static int adjustedOffset(int offset, boolean flipped) {
            return flipped ? -offset : offset;
        }

        @Override
        public int getCollisionFlags() {
            // loc_88804 keeps each drill arm in Collision_response_list while
            // the parent flashes after a body hit. Only parent destruction
            // removes the child; body invulnerability does not clear $9E.
            return parent.state.defeated ? 0 : ARM_COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // loc_88804 calls Refresh_ChildPositionAdjusted immediately
            // before publishing the arm pointer through the collision-response
            // list. The following player pass therefore sees that refreshed
            // child coordinate, rather than the older frame-start snapshot.
            return true;
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
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return true;
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }
    }

    private static class CeilingDebrisChild extends AbstractObjectInstance
            implements SpawnCoordinateZeroScalarArgsRewindRecreatable {
        private static final int PRIORITY_BUCKET = 4;
        private static final int GRAVITY = 0x20;
        private static final int LIFE = 0x5F;

        // Non-final so the generic rewind field capturer can reapply the captured
        // values after the recreate hook rebuilds this child with placeholder ctor args.
        private int mappingFrame;
        private boolean spire;
        private int xFixed;
        private int yFixed;
        private int xVel;
        private int yVel;
        private int life;

        private CeilingDebrisChild(int x, int y, int mappingFrame, boolean spire) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "MGZMinibossDebris");
            this.mappingFrame = mappingFrame;
            this.spire = spire;
            this.xFixed = x << 8;
            this.yFixed = y << 8;
            this.xVel = 0;
            this.yVel = 0;
            this.life = LIFE;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            xFixed += xVel;
            yFixed += yVel;
            yVel += GRAVITY;
            updateDynamicSpawn(xFixed >> 8, yFixed >> 8);
            if (--life < 0) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return xFixed >> 8;
        }

        @Override
        public int getY() {
            return yFixed >> 8;
        }

        protected void deflectFrom(AbstractPlayableSprite player) {
            int dx = player.getCentreX() - getX();
            int dy = player.getCentreY() - getY();
            int angle = TrigLookupTable.calcAngle(saturateToShort(dx), saturateToShort(dy));
            xVel = -((TrigLookupTable.cosHex(angle) * 0x800) >> 8);
            yVel = -((TrigLookupTable.sinHex(angle) * 0x800) >> 8);
        }

        private static short saturateToShort(int value) {
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(
                    spire ? Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE : Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS);
            if (renderer == null && spire) {
                renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS);
            }
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }
    }

    private static final class CeilingSpireChild extends CeilingDebrisChild
            implements TouchResponseProvider, SpawnCoordinateZeroScalarArgsRewindRecreatable {
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
                com.openggf.game.profiles.touchresponse.TouchResponseProfile
                        .singleRegionShieldDeflect());
        private boolean collisionEnabled = true;

        private CeilingSpireChild(int x, int y, int mappingFrame) {
            super(x, y, mappingFrame, true);
        }

        @Override
        public int getCollisionFlags() {
            return collisionEnabled ? 0x84 : 0;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public int getShieldReactionFlags() {
            // loc_88820: bset #3,shield_reaction(a0).
            return TOUCH_RESPONSE_PROFILE.shieldReactionFlags();
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity playerEntity) {
            if (!(playerEntity instanceof AbstractPlayableSprite player)) {
                return false;
            }
            deflectFrom(player);
            collisionEnabled = false;
            return true;
        }
    }

    private static final class DefeatFragmentChild extends AbstractObjectInstance
            implements SpawnCoordinateZeroScalarArgsRewindRecreatable {
        private static final int PRIORITY_BUCKET = 5;
        private static final int GRAVITY = 0x38;

        private int mappingFrame;
        private int xFixed;
        private int yFixed;
        private int xVel;
        private int yVel;
        private int life = 0x5F;

        private DefeatFragmentChild(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(), 0, 0, 0);
        }

        private DefeatFragmentChild(int x, int y, int mappingFrame, int xVel, int yVel) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "MGZMinibossFragment");
            this.mappingFrame = mappingFrame;
            this.xFixed = x << 8;
            this.yFixed = y << 8;
            this.xVel = xVel;
            this.yVel = yVel;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            xFixed += xVel;
            yFixed += yVel;
            yVel += GRAVITY;
            updateDynamicSpawn(xFixed >> 8, yFixed >> 8);
            if (--life < 0) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, xFixed >> 8, yFixed >> 8, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }
    }

    private static final class KnucklesSpikePlatformChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

        /**
         * Word 0 of this object's S3K SST holds its live ROM code pointer.
         * ROM {@code Obj_MGZMiniboss} is installed from the S3K object pointer table at
         * {@code $00088568} (table read from the user-supplied ROM; the
         * label is defined at docs/skdisasm/sonic3k.asm:184816).
         * Its whole code block lies in one bank, so the HIGH word that
         * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
         * on the next off-screen on-object frame is {@code $0008}
         * (docs/skdisasm/sonic3k.asm:26816-26843).
         */
        @Override
        public int romObjectCodePointerHighWord() {
            return 0x0008;
        }

        private static final int PRIORITY_BUCKET = 5;
        private static final int HALF_WIDTH = 0x18;
        private static final int HALF_HEIGHT = 0x30;
        private static final int SIDE_PADDING = 0x0B;

        private static final int ROUTINE_RISE = 0;
        private static final int ROUTINE_WAIT = 2;
        private static final int ROUTINE_SWING = 4;
        private static final int ROUTINE_WAIT_END = 6;
        private static final int ROUTINE_DESCEND = 8;
        private MgzMinibossInstance parent;
        private boolean mirrored;
        private int baseY;
        private int currentX;
        private int currentY;
        private int routine;
        private int timer;
        private int xVel;
        private int yVel;
        private int xSubpixel;
        private int ySubpixel;
        private boolean swingDirectionDown;
        private int mappingFrame;
        private int animTimer;

        private KnucklesSpikePlatformChild(ObjectSpawn spawn) {
            super(spawn, "MgzKnucklesSpikePlatform");
            this.parent = null;
            this.mirrored = spawn.subtype() != 0;
            this.currentX = spawn.x();
            this.currentY = spawn.y();
            this.baseY = spawn.y();
        }

        private KnucklesSpikePlatformChild(MgzMinibossInstance parent, boolean mirrored, int cameraX, int cameraY) {
            super(new ObjectSpawn(parent.state.x, parent.state.y, 0, 0, mirrored ? 1 : 0, false, 0),
                    "MgzKnucklesSpikePlatform");
            this.parent = parent;
            this.mirrored = mirrored;
            this.currentX = cameraX + 0x30 + (mirrored ? 0xE0 : 0);
            this.currentY = cameraY + 0xF0;
            this.baseY = currentY;
            this.routine = ROUTINE_RISE;
            this.timer = 0x0F;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            MgzMinibossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, MgzMinibossInstance.class);
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            boolean capturedMirrored = spawn != null && spawn.subtype() != 0;
            return new KnucklesSpikePlatformChild(liveParent, capturedMirrored, 0, 0);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }

            switch (routine) {
                case ROUTINE_RISE -> updateRise();
                case ROUTINE_WAIT -> updateWait();
                case ROUTINE_SWING -> updateSwing();
                case ROUTINE_WAIT_END -> updateEndWait();
                case ROUTINE_DESCEND -> updateDescend();
                default -> {
                }
            }

            animTimer--;
            if (animTimer < 0) {
                animTimer = 7;
                mappingFrame = (mappingFrame + 1) & 0x03;
            }

            updateDynamicSpawn(currentX, currentY);
        }

        private void updateRise() {
            currentY -= 4;
            if (--timer < 0) {
                routine = ROUTINE_WAIT;
                timer = 0x3F;
            }
        }

        private void updateWait() {
            if (--timer >= 0) {
                return;
            }
            routine = ROUTINE_SWING;
            xVel = mirrored ? -0x100 : 0x100;
            yVel = PLATFORM_SWING_MAX_VELOCITY;
            swingDirectionDown = false;
            timer = 0xDF;
        }

        private void updateSwing() {
            SwingMotion.Result result = SwingMotion.update(
                    SWING_ACCELERATION, yVel, PLATFORM_SWING_MAX_VELOCITY, swingDirectionDown);
            yVel = result.velocity();
            swingDirectionDown = result.directionDown();

            int xFixed = (currentX << 8) | (xSubpixel & 0xFF);
            int yFixed = (currentY << 8) | (ySubpixel & 0xFF);
            xFixed += xVel;
            yFixed += yVel;
            currentX = xFixed >> 8;
            currentY = yFixed >> 8;
            xSubpixel = xFixed & 0xFF;
            ySubpixel = yFixed & 0xFF;

            if (--timer < 0) {
                routine = ROUTINE_WAIT_END;
                timer = 0x3F;
            }
        }

        private void updateEndWait() {
            if (--timer < 0) {
                routine = ROUTINE_DESCEND;
            }
        }

        private void updateDescend() {
            currentY += 2;
            if (currentY >= baseY) {
                setDestroyed(true);
            }
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return SolidObjectParams.of(HALF_WIDTH + SIDE_PADDING, HALF_HEIGHT, HALF_HEIGHT + 1);
        }

        @Override
        public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
            return HALF_WIDTH;
        }

        @Override
        public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
            if (playerEntity == null || playerEntity.getInvulnerable()) {
                return;
            }
            int playerY = playerEntity.getCentreY();
            if (playerY - currentY + 0x28 < 0) {
                return;
            }
            playerEntity.applyHurt(currentX);
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
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_MOVING_SPIKE_PLATFORM);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, mirrored, false);
        }

        @Override
        public boolean isHighPriority() {
            return true;
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }
    }

    static final class MgzBossCameraScrollHelper extends AbstractObjectInstance implements SpawnRewindRecreatable {
        private int targetX;

        private MgzBossCameraScrollHelper(ObjectSpawn spawn) {
            this(spawn.x());
        }

        MgzBossCameraScrollHelper(int targetX) {
            super(new ObjectSpawn(targetX, 0, 0, 0, 0, false, 0), "MgzBossCameraScrollHelper");
            this.targetX = targetX;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public int getX() {
            return targetX;
        }

        @Override
        public int getY() {
            return 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            var camera = services().camera();
            if (camera == null) {
                setDestroyed(true);
                return;
            }
            // ROM loc_887DA increments and publishes the camera word before
            // comparing it with $2E00. It neither clamps the increment nor
            // writes Camera_max_X_pos (sonic3k.asm:185020-185027).
            int nextX = ((camera.getX() & 0xFFFF) + 1) & 0xFFFF;
            camera.setX((short) nextX);
            camera.setMinX((short) nextX);
            if (nextX >= targetX) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
