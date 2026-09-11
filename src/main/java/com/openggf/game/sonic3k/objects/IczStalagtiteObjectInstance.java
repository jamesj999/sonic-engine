package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB5 - ICZ Stalagtite.
 *
 * <p>ROM reference: {@code Obj_ICZStalagtite} in sonic3k.asm.
 * The object waits for its first camera appearance through {@code Obj_WaitOffscreen},
 * then initializes as a full solid using {@code Map_ICZWallAndColumn} frame 7,
 * shakes for 16 frames when the nearest player is within {@code $70} pixels
 * horizontally, then falls with {@code MoveSprite} gravity and hurt collision
 * until {@code ObjCheckCeilingDist} reports impact.
 */
public class IczStalagtiteObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, TouchResponseProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZStalagtite} is installed from the S3K object pointer table at
     * {@code $0008B194} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:189433).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN;
    private static final int OBJECT_ID = Sonic3kObjectIds.ICZ_STALAGTITE;
    private static final int PRIORITY_BUCKET = 5; // ObjDat priority $280.

    // ObjDat_ICZStalagtite: width=$10, height=$20, frame=7, collision_flags=0.
    private static final int MAPPING_FRAME = 7;
    private static final int Y_RADIUS = 0x10;
    private static final int DRAW_PALETTE = 2;

    // sub_8B26A: d1=$1B, d2=$20, d3=$20 before SolidObjectFull.
    private static final int SOLID_HALF_WIDTH = 0x1B;
    private static final int SOLID_HALF_HEIGHT = 0x20;

    // loc_8B1AE: Find_SonicTails, cmpi.w #$70,d2.
    private static final int TRIGGER_X_RANGE = 0x70;
    private static final int SHAKE_TIMER_START = 0x0F;
    private static final int SHAKE_STEP = 2;
    private static final int FALL_COLLISION_FLAGS = 0x82;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            true,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);

    private enum Phase {
        OFFSCREEN,
        INITIALIZING,
        WAITING,
        SHAKING,
        FALLING,
        LANDED
    }

    private boolean hFlip;
    private final SubpixelMotion.State motion;
    private Phase phase = Phase.OFFSCREEN;
    private boolean placeholderSubmitted;
    private boolean placeholderRenderedOnscreen;
    private boolean activeRoutineExecuted;
    private int timer;

    public IczStalagtiteObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZStalagtite");
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public IczStalagtiteObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczStalagtiteObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        activeRoutineExecuted = phase != Phase.OFFSCREEN && phase != Phase.INITIALIZING;
        switch (phase) {
            case OFFSCREEN -> {
                // Obj_WaitOffscreen/loc_85AD2 queues an empty $20-by-$20
                // placeholder. Its previous Render_Sprites bit 7, not player
                // proximity, releases the saved initializer (loc_85B02).
                if (placeholderRenderedOnscreen) {
                    phase = Phase.INITIALIZING;
                    placeholderSubmitted = false;
                } else {
                    placeholderSubmitted = true;
                }
            }
            // Obj_ICZStalagtite sets attributes and returns; loc_8B1AE's
            // proximity check first runs on the following object pass.
            case INITIALIZING -> phase = Phase.WAITING;
            case WAITING -> updateWaiting(player);
            case SHAKING -> updateShaking(vIntRunCount);
            case FALLING -> updateFalling();
            case LANDED -> {
                // loc_8B228 returns to the solid idle body after impact.
            }
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (phase == Phase.OFFSCREEN && placeholderSubmitted) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(0x20, 0x20);
        }
    }

    @Override
    public boolean publishesTouchResponseListEntryThisFrame() {
        return activeRoutineExecuted;
    }

    private void updateWaiting(PlayableEntity player) {
        if (nearestPlayerXDistance(player) < TRIGGER_X_RANGE) {
            phase = Phase.SHAKING;
            timer = SHAKE_TIMER_START;
        }
    }

    private void updateShaking(int vIntRunCount) {
        int dx = ((vIntRunCount & 1) == 0) ? SHAKE_STEP : -SHAKE_STEP;
        motion.x += dx;

        // Obj_Wait decrements $2E and calls the callback only after it goes negative.
        if (--timer < 0) {
            phase = Phase.FALLING;
        }
    }

    private void updateFalling() {
        // ROM MoveSprite moves using the previous y_vel, then adds gravity.
        SubpixelMotion.moveSprite(motion, SubpixelMotion.S3K_GRAVITY);
        if (checkCeilingDistanceForTesting() < 0) {
            onCeilingImpact();
        }
    }

    protected int checkCeilingDistanceForTesting() {
        return ObjectTerrainUtils.checkCeilingDist(motion.x, motion.y, Y_RADIUS).distance();
    }

    private void onCeilingImpact() {
        phase = Phase.LANDED;
        spawnDebris();
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic3kSfx.FLOOR_THUMP.id);
        }
    }

    private int nearestPlayerXDistance(PlayableEntity mainPlayer) {
        ObjectServices services = tryServices();
        ObjectPlayerQuery serviceQuery = services != null ? services.playerQuery() : null;
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> livePlayerOrNull(mainPlayer),
                () -> livePlayers(serviceQuery != null ? serviceQuery.sidekicks() : List.of()));
        return query.nearestByRomX(PLAYER_PARTICIPATION, motion.x).distance();
    }

    private static PlayableEntity livePlayerOrNull(PlayableEntity player) {
        return player == null || player.getDead() ? null : player;
    }

    private static List<PlayableEntity> livePlayers(List<PlayableEntity> players) {
        List<PlayableEntity> live = new ArrayList<>(players.size());
        for (PlayableEntity player : players) {
            PlayableEntity candidate = livePlayerOrNull(player);
            if (candidate != null) {
                live.add(candidate);
            }
        }
        return live;
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return activeRoutineExecuted && (phase == Phase.WAITING || phase == Phase.LANDED);
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return true;
    }

    @Override
    public int getCollisionFlags() {
        return phase == Phase.FALLING ? FALL_COLLISION_FLAGS : 0;
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
    public TouchRegion[] getMultiTouchRegions() {
        int flags = getCollisionFlags();
        if (flags == 0) {
            return new TouchRegion[0];
        }
        return new TouchRegion[] { new TouchRegion(motion.x, motion.y, flags) };
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!activeRoutineExecuted) {
            return; // Map_Offscreen has no pieces; the initializer also returns without drawing.
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, motion.x, motion.y, hFlip, false, DRAW_PALETTE);
        }
    }

    private void spawnDebris() {
        for (IceDebrisSpec spec : buildDebrisSpecs(motion.x, motion.y, hFlip)) {
            spawnChild(() -> new StalagtiteDebris(spec));
        }
    }

    static List<IceDebrisSpec> debrisSpecsForTesting(int x, int y, boolean hFlip) {
        return buildDebrisSpecs(x, y, hFlip);
    }

    private static List<IceDebrisSpec> buildDebrisSpecs(int x, int y, boolean hFlip) {
        List<IceDebrisSpec> specs = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            int subtype = i * 2;
            int[] velocity = VELOCITY_INDEX[i];
            int xVel = hFlip ? -velocity[0] : velocity[0];
            specs.add(new IceDebrisSpec(subtype, x, y, xVel, velocity[1]));
        }
        return List.copyOf(specs);
    }

    public record IceDebrisSpec(int subtype, int x, int y, int xVel, int yVel) {
    }

    public static final class StalagtiteDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final int GRAVITY = 0x38;
        private static final int INITIAL_MAPPING_FRAME = 0x0F; // ObjDat3_8B286.
        private static final int[] RAW_ANIMATION_UPPER = {
                0, 0x27, 0x0C, 0x27, 0x0D, 0x27, 0x0E, 0xFC
        };
        private static final int[] RAW_ANIMATION_LOWER = {
                0, 0x27, 0x0F, 0x27, 0x10, 0x27, 0x11, 0xFC
        };

        private final int[] rawAnimation;
        private int mappingFrame = INITIAL_MAPPING_FRAME;
        private int animFrame;
        private int animFrameTimer;

        private StalagtiteDebris(IceDebrisSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), OBJECT_ID, spec.subtype(), 0, false, spec.y()),
                    "ICZStalagtiteDebris", spec.xVel(), spec.yVel(), GRAVITY);
            // loc_8B230 switches from byte_8AB3E to byte_8AB46 when subtype >= 6.
            this.rawAnimation = spec.subtype() >= 6 ? RAW_ANIMATION_LOWER : RAW_ANIMATION_UPPER;
            this.animFrame = initialAnimFrame();
        }

        private StalagtiteDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZStalagtiteDebris", 0, 0, GRAVITY);
            this.rawAnimation = (spawn.subtype() & 0xFF) >= 6 ? RAW_ANIMATION_LOWER : RAW_ANIMATION_UPPER;
            this.animFrame = initialAnimFrame();
        }

        private int initialAnimFrame() {
            ObjectServices services = constructionContext();
            GameRng rng = services != null ? services.rng() : null;
            return rng != null ? rng.nextBits(3) : 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animateRaw();
            super.update(vIntRunCount, player);
        }

        private void animateRaw() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }

            animFrame = (animFrame + 1) & 0xFF;
            int scriptIndex = animFrame + 1;
            int value = scriptIndex < rawAnimation.length ? rawAnimation[scriptIndex] : 0xFC;
            if ((value & 0x80) != 0) {
                mappingFrame = rawAnimation[1];
                animFrameTimer = rawAnimation[0];
                animFrame = 0;
                return;
            }
            animFrameTimer = rawAnimation[0];
            mappingFrame = value;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ICZ_PLATFORMS);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, DRAW_PALETTE);
            }
        }
    }

    public int getMappingFrameForTesting() {
        return MAPPING_FRAME;
    }

    public String getArtKeyForTesting() {
        return ART_KEY;
    }

    public String getPhaseNameForTesting() {
        return phase.name();
    }

    public int getTimerForTesting() {
        return timer;
    }

    public int getYVelocityForTesting() {
        return motion.yVel;
    }

    public void forceFallingForTesting() {
        phase = Phase.FALLING;
    }

    /**
     * First 12 {@code Obj_VelocityIndex} entries selected by CreateChild6
     * subtypes 0,2,4,...,22 and {@code Set_IndexedVelocity(d0=0)}.
     */
    private static final int[][] VELOCITY_INDEX = {
            {-0x100, -0x100},
            { 0x100, -0x100},
            {-0x200, -0x200},
            { 0x200, -0x200},
            {-0x300, -0x200},
            { 0x300, -0x200},
            {-0x200, -0x200},
            {      0, -0x200},
            {-0x400, -0x300},
            { 0x400, -0x300},
            { 0x300, -0x300},
            {-0x400, -0x300}
    };
}
