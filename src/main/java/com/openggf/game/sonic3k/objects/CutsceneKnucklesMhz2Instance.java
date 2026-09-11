package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RespawnState;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Generic {@code Obj_CutsceneKnuckles} subtype $20 for the MHZ2 press sequence.
 *
 * <p>ROM reference: {@code CutsceneKnux_MHZ2}. This object locks the camera at
 * X=$3D0, takes player control, runs the press animation timing, and lifts the
 * players through the leaf-blower handoff.
 */
public final class CutsceneKnucklesMhz2Instance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_CAMERA_LOCK = 0x02;
    private static final int ROUTINE_WAIT_GROUNDED = 0x04;
    private static final int ROUTINE_PRESS = 0x06;
    private static final int ROUTINE_LAUNCH_PLAYERS = 0x08;

    private static final int CAMERA_LOCK_X = 0x03D0;
    private static final int CAMERA_TRIGGER_MIN_Y = 0x0648;
    private static final int CAMERA_TRIGGER_MAX_Y = 0x0848;
    private static final int CAMERA_TRIGGER_MIN_X = 0x02D0;
    private static final int CAMERA_TRIGGER_MAX_X = 0x04D0;
    private static final int WAIT_GROUNDED_TIMER = 60 - 1;
    private static final int PRESS_START_ACCEL = 0x10;
    private static final int PLAYER_LAUNCH_Y_VEL = -0x1000;
    private static final int LIGHT_GRAVITY = 0x18;
    private static final int PRESS_START_ANIMATION = 0x05;
    private static final int PRESSED_ANIMATION = 0x14;
    private static final int LIFT_ANIMATION = 0x0F;
    private static final int POST_CUTSCENE_CHECKPOINT_INDEX = 7;
    private static final int POST_CUTSCENE_RESTART_X = 0x052A;
    private static final int POST_CUTSCENE_RESTART_Y = 0x05AC;
    private static final int INITIAL_MAPPING_FRAME = 3;
    private static final int KNUCKLES_ROUTE_SWITCH_CHILD_X_OFFSET = -8;
    private static final int SWITCH_CHILD_PRIORITY = 6;
    private static final int SWITCH_CHILD_KNUCKLES_ROUTE_PALETTE = 0;
    private static final int LEAF_CAMERA_Y_OFFSET = 0x00E8;
    private static final int LEAF_DELETE_CAMERA_Y_OFFSET = -8;
    private static final int RAW_CALLBACK_END = 0xF4;
    private static final int[] PRESS_ANIMATION_SCRIPT = {
            3, 0x07,
            3, 0x1F,
            0, 0x07,
            1, 0x1F,
            2, 0x07,
            1, 0x7F,
            2, 0x07,
            1, 0x1F,
            2, 0x07,
            1, 0x0F,
            2, 0x07,
            1, 0x0B,
            2, 0x07,
            1, 0x07,
            2, 0x07,
            1, 0x07,
            2, 0x07,
            RAW_CALLBACK_END
    };

    private int routine = ROUTINE_INIT;
    private int timer;
    private int leafSpawnPeriod;
    private int leafSpawnTimer;
    private int leafStrength;
    private int animFrame;
    private int animFrameTimer;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private boolean initialized;
    private boolean liftChildrenSpawned;
    private boolean restartPointSaved;
    private boolean switchChildSpawned;

    public CutsceneKnucklesMhz2Instance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxMHZ2");
    }

    @Override
    public int getX() {
        return getSpawn().x();
    }

    @Override
    public int getY() {
        return getSpawn().y();
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
        if (!initialized && isKnucklesPlayer()) {
            spawnSwitchChildOnce();
            setDestroyed(true);
            return;
        }
        if (!initialized && lastStarPostHitAtOrPastCutscene()) {
            setDestroyed(true);
            return;
        }
        if (!initialized && !cameraIsInsideRomTriggerRange()) {
            setDestroyed(true);
            return;
        }

        switch (routine) {
            case ROUTINE_INIT -> routineInit();
            case ROUTINE_CAMERA_LOCK -> routineCameraLock(playerEntity);
            case ROUTINE_WAIT_GROUNDED -> routineWaitGrounded(playerEntity);
            case ROUTINE_PRESS -> routinePress();
            case ROUTINE_LAUNCH_PLAYERS -> routineLaunchPlayers();
            default -> routine = ROUTINE_CAMERA_LOCK;
        }
    }

    private void routineInit() {
        initialized = true;
        AizIntroArtLoader.applyKnucklesPalette(services());
        spawnSwitchChildOnce();
        setLeafBlowerCutsceneFlag(true);
        routine = ROUTINE_CAMERA_LOCK;
        Camera camera = services().camera();
        if (camera != null) {
            camera.setMinX((short) Math.min(camera.getX() & 0xFFFF, CAMERA_LOCK_X));
        }
    }

    private void spawnSwitchChildOnce() {
        if (switchChildSpawned) {
            return;
        }
        switchChildSpawned = true;
        spawnFreeChild(() -> new Mhz2KnucklesRouteSwitchChild(this));
    }

    private void routineCameraLock(PlayableEntity playerEntity) {
        Camera camera = services().camera();
        if (camera == null) {
            enterWaitGrounded();
            lockPlayer(playerEntity);
            return;
        }
        int cameraX = camera.getX() & 0xFFFF;
        if (cameraX < CAMERA_LOCK_X) {
            camera.setMinX((short) cameraX);
            return;
        }
        camera.setMinX((short) CAMERA_LOCK_X);
        enterWaitGrounded();
        lockPlayer(playerEntity);
    }

    private void enterWaitGrounded() {
        routine = ROUTINE_WAIT_GROUNDED;
        timer = WAIT_GROUNDED_TIMER;
        lockPlayers();
    }

    private void routineWaitGrounded(PlayableEntity playerEntity) {
        lockPlayer(playerEntity);
        if (timer-- < 0 || playersAreGrounded(playerEntity)) {
            routine = ROUTINE_PRESS;
            leafSpawnPeriod = PRESS_START_ACCEL;
            leafSpawnTimer = 0;
            leafStrength = 1;
            animFrame = 0;
            animFrameTimer = 0;
            mappingFrame = INITIAL_MAPPING_FRAME;
            lockPlayers();
            enterNativePlayerPressControl();
        }
    }

    private void routinePress() {
        if (animFrame >= 8) {
            services().playSfx(Sonic3kSfx.LEAF_BLOWER.id);
            updateLeafSpawnTimer();
        }

        int animateResult = animateRawNoSstMultiDelay();
        if (animateResult == 0) {
            return;
        }
        if (animateResult < 0) {
            routine = ROUTINE_LAUNCH_PLAYERS;
            return;
        }
        if (animFrame == 0x0C) {
            setPressedNativePlayerAnimation();
        }
        if (mappingFrame == 2) {
            leafStrength = (leafStrength + 1) & 0xFF;
            leafSpawnPeriod = Math.max(0, leafSpawnPeriod - 4);
            services().playSfx(Sonic3kSfx.SWITCH.id);
        }
    }

    private void updateLeafSpawnTimer() {
        leafSpawnTimer--;
        if (leafSpawnTimer >= 0) {
            return;
        }
        leafSpawnTimer = leafSpawnPeriod;
        spawnFreeChild(() -> new Mhz2KnucklesLeafParticle(buildLeafParticleSpec()));
    }

    private LeafParticleSpec buildLeafParticleSpec() {
        Camera camera = services().camera();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : 0;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : 0;
        int randomX = services().rng() != null ? services().rng().nextWord() & 0x01FF : 0;
        if (randomX >= 0x0140) {
            randomX = (randomX & 0x003F) << 2;
        }
        return new LeafParticleSpec(
                cameraX + randomX,
                cameraY + LEAF_CAMERA_Y_OFFSET,
                -((leafStrength & 0xFF) << 1)
        );
    }

    private int animateRawNoSstMultiDelay() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return 0;
        }

        animFrame += 2;
        int command = PRESS_ANIMATION_SCRIPT[animFrame] & 0xFF;
        if (command == RAW_CALLBACK_END) {
            animFrame = 0;
            animFrameTimer = 0;
            return -1;
        }
        mappingFrame = command;
        animFrameTimer = PRESS_ANIMATION_SCRIPT[animFrame + 1] & 0xFF;
        return 1;
    }

    private void routineLaunchPlayers() {
        services().playSfx(Sonic3kSfx.LEAF_BLOWER.id);
        updateLeafSpawnTimer();
        if (!liftChildrenSpawned) {
            liftChildrenSpawned = true;
            List<AbstractPlayableSprite> participants = players();
            for (int i = 0; i < participants.size(); i++) {
                AbstractPlayableSprite player = participants.get(i);
                if (i == 0 || playerTwoRenderFlagsOnScreenForLift(player)) {
                    spawnFreeChild(() -> new Mhz2KnucklesLiftChild(this, player));
                }
            }
        }
    }

    private void lockPlayers() {
        for (AbstractPlayableSprite player : players()) {
            lockPlayer(player);
        }
    }

    private void lockPlayer(PlayableEntity entity) {
        if (entity instanceof AbstractPlayableSprite player) {
            player.setControlLocked(true);
            player.clearLogicalInputState();
            player.clearForcedInputMask();
            player.setXSpeed((short) 0);
            player.setGSpeed((short) 0);
        }
    }

    private void setNativePlayerAnimation(int animationId) {
        for (AbstractPlayableSprite player : players()) {
            player.setAnimationId(animationId);
        }
    }

    private void setPressedNativePlayerAnimation() {
        List<AbstractPlayableSprite> participants = players();
        if (participants.isEmpty()) {
            return;
        }
        participants.get(0).setAnimationId(PRESSED_ANIMATION);
        if (participants.size() > 1 && playerTwoRenderFlagsOnScreenForLift(participants.get(1))) {
            participants.get(1).setAnimationId(PRESSED_ANIMATION);
        }
    }

    private void enterNativePlayerPressControl() {
        for (AbstractPlayableSprite player : players()) {
            ObjectControlState.nativeBit7FullControl().applyTo(player);
            player.setAnimationId(PRESS_START_ANIMATION);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
        }
    }

    private void launchOrUpdatePlayer(AbstractPlayableSprite player) {
        SubpixelMotion.State motion = new SubpixelMotion.State(
                player.getCentreX() & 0xFFFF,
                player.getCentreY() & 0xFFFF,
                0,
                0,
                0,
                player.getYSpeed());
        SubpixelMotion.objectFallXY(motion, LIGHT_GRAVITY);
        NativePositionOps.writeYPosPreserveSubpixel(player, motion.y);
        player.setYSpeed((short) motion.yVel);
        if (motion.yVel >= 0) {
            ObjectControlState.none().applyTo(player);
            player.setControlLocked(false);
            player.clearLogicalInputState();
            player.clearForcedInputMask();
            player.setAnimationId(0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0x10);
            setLeafBlowerCutsceneFlag(false);
            savePostCutsceneRestartPoint();
        }
    }

    private void savePostCutsceneRestartPoint() {
        if (restartPointSaved) {
            return;
        }
        RespawnState checkpointState = services().checkpointState();
        if (checkpointState == null) {
            return;
        }
        Camera camera = services().camera();
        int cameraX = camera != null ? camera.getX() & 0xFFFF : 0;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : 0;
        checkpointState.restoreFromSaved(POST_CUTSCENE_RESTART_X, POST_CUTSCENE_RESTART_Y,
                cameraX, cameraY, POST_CUTSCENE_CHECKPOINT_INDEX);
        services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
        restartPointSaved = true;
    }

    private boolean playersAreGrounded(PlayableEntity playerEntity) {
        boolean p1Grounded = !(playerEntity instanceof AbstractPlayableSprite p1) || !p1.getAir();
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        AbstractPlayableSprite p2 = nativeP2 instanceof AbstractPlayableSprite sprite ? sprite : null;
        boolean p2Grounded = p2 == null || !playerTwoRenderFlagsOnScreenForLift(p2) || !p2.getAir();
        return p1Grounded && p2Grounded;
    }

    private List<AbstractPlayableSprite> players() {
        ObjectPlayerQuery query = services().playerQuery();
        return query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2).stream()
                .filter(AbstractPlayableSprite.class::isInstance)
                .map(AbstractPlayableSprite.class::cast)
                .toList();
    }

    private static boolean playerTwoRenderFlagsOnScreenForLift(AbstractPlayableSprite player) {
        return !player.hasRenderFlagOnScreenState() || player.isRenderFlagOnScreen();
    }

    private boolean isKnucklesPlayer() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private void setLeafBlowerCutsceneFlag(boolean active) {
        if (services().zoneRuntimeState() instanceof MhzZoneRuntimeState state) {
            state.setLeafBlowerCutsceneFlag(active);
        }
    }

    private boolean lastStarPostHitAtOrPastCutscene() {
        RespawnState checkpointState = services().checkpointState();
        return checkpointState != null
                && checkpointState.getLastCheckpointIndex() >= POST_CUTSCENE_CHECKPOINT_INDEX;
    }

    private boolean cameraIsInsideRomTriggerRange() {
        Camera camera = services().camera();
        if (camera == null) {
            return true;
        }
        int cameraX = camera.getX() & 0xFFFF;
        int cameraY = camera.getY() & 0xFFFF;
        return cameraY >= CAMERA_TRIGGER_MIN_Y
                && cameraY <= CAMERA_TRIGGER_MAX_Y
                && cameraX >= CAMERA_TRIGGER_MIN_X
                && cameraX <= CAMERA_TRIGGER_MAX_X;
    }

    int getRoutineForTest() {
        return routine;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_PRESS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    private record LeafParticleSpec(int x, int y, int yVelocity) {
    }

    private static final class Mhz2KnucklesLeafParticle extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private int x;
        private int y;
        private int yVelocity;

        private Mhz2KnucklesLeafParticle(LeafParticleSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0,
                    false, spec.yVelocity()),
                    "MHZ2KnucklesLeaf");
            this.x = spec.x();
            this.y = spec.y();
            this.yVelocity = spec.yVelocity();
        }

        private Mhz2KnucklesLeafParticle(ObjectSpawn spawn) {
            super(spawn, "MHZ2KnucklesLeaf");
            this.x = spawn.x();
            this.y = spawn.y();
            this.yVelocity = (short) spawn.rawYWord();
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
        public int getOnScreenHalfWidth() {
            return 4;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return 4;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            y += yVelocity;
            Camera camera = services().camera();
            int deleteY = (camera != null ? camera.getY() : 0) + LEAF_DELETE_CAMERA_Y_OFFSET;
            if (y <= deleteY) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_LEAVES);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(0, x, y, false, false);
        }
    }

    private static final class Mhz2KnucklesLiftChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private final CutsceneKnucklesMhz2Instance parent;
        private AbstractPlayableSprite player;
        private boolean initialized;

        private Mhz2KnucklesLiftChild(CutsceneKnucklesMhz2Instance parent, AbstractPlayableSprite player) {
            super(new ObjectSpawn(player.getCentreX() & 0xFFFF, player.getCentreY() & 0xFFFF,
                    Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0),
                    "MHZ2KnucklesLift");
            this.parent = parent;
            this.player = player;
        }

        private Mhz2KnucklesLiftChild(CutsceneKnucklesMhz2Instance parent) {
            super(new ObjectSpawn(parent.getX(), parent.getY(),
                    Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0),
                    "MHZ2KnucklesLift");
            this.parent = parent;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            CutsceneKnucklesMhz2Instance liveParent = Mhz2KnucklesRouteSwitchChild.findNearestLiveParent(ctx);
            return liveParent == null ? null : new Mhz2KnucklesLiftChild(liveParent);
        }

        @Override
        public int getX() {
            return player != null ? player.getCentreX() & 0xFFFF : getSpawn().x();
        }

        @Override
        public int getY() {
            return player != null ? player.getCentreY() & 0xFFFF : getSpawn().y();
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (player == null) {
                setDestroyed(true);
                return;
            }
            if (!initialized) {
                initialized = true;
                player.setYSpeed((short) PLAYER_LAUNCH_Y_VEL);
                player.setControlLocked(true);
                player.setAnimationId(LIFT_ANIMATION);
                Camera camera = services().camera();
                if (camera != null) {
                    camera.requestFastVerticalScroll();
                }
                return;
            }
            services().playSfx(Sonic3kSfx.LEAF_BLOWER.id);
            parent.launchOrUpdatePlayer(player);
            Camera camera = services().camera();
            if (camera != null) {
                camera.requestFastVerticalScroll();
            }
            if (player.getYSpeed() == 0 && !player.isControlLocked()) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM loc_6338E/loc_633D6 is an invisible carrier that writes the player position.
        }
    }

    private static final class Mhz2KnucklesRouteSwitchChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private CutsceneKnucklesMhz2Instance parent;
        // Non-final so the generic rewind field capturer can reapply the captured
        // value after the recreate hook rebuilds this child (the ctor recomputes it from
        // the parent, but the capturer restores the exact captured value).
        private boolean knucklesRoute;

        private Mhz2KnucklesRouteSwitchChild(CutsceneKnucklesMhz2Instance parent) {
            super(new ObjectSpawn(parent.getX() + KNUCKLES_ROUTE_SWITCH_CHILD_X_OFFSET, parent.getY(),
                    Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0),
                    "MHZ2KnucklesRouteSwitch");
            this.parent = parent;
            this.knucklesRoute = parent.isKnucklesPlayer();
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            CutsceneKnucklesMhz2Instance liveParent = findNearestLiveParent(ctx);
            return liveParent == null ? null : new Mhz2KnucklesRouteSwitchChild(liveParent);
        }

        @Override
        public int getX() {
            return parent.getX() + KNUCKLES_ROUTE_SWITCH_CHILD_X_OFFSET;
        }

        @Override
        public int getY() {
            return parent.getY();
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
        public int getPriorityBucket() {
            return RenderPriority.clamp(SWITCH_CHILD_PRIORITY);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!knucklesRoute && parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            if (knucklesRoute && !isInRangeAt(getX())) {
                setDestroyed(true);
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_SWITCH);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            int childFrame = parent.mappingFrame == 2 ? 1 : 0;
            if (knucklesRoute) {
                renderer.drawFrameIndex(childFrame, getX(), getY(), false, false,
                        SWITCH_CHILD_KNUCKLES_ROUTE_PALETTE);
                return;
            }
            renderer.drawFrameIndex(childFrame, getX(), getY(), false, false);
        }

        private static CutsceneKnucklesMhz2Instance findNearestLiveParent(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            CutsceneKnucklesMhz2Instance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
                if (!(object instanceof CutsceneKnucklesMhz2Instance candidate) || candidate.isDestroyed()) {
                    continue;
                }
                if (spawn == null) {
                    return candidate;
                }
                long dx = candidate.getX() - spawn.x();
                long dy = candidate.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }
    }
}
