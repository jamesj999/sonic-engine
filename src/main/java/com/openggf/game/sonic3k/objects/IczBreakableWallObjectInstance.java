package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Object 0xB1 - ICZ breakable wall.
 *
 * <p>ROM reference: {@code Obj_ICZBreakableWall} at sonic3k.asm:187693.
 * The wall uses {@code Map_ICZWallAndColumn} frame 6 and breaks when Knuckles
 * side-contacts it or when {@code Obj_ICZPathFollowPlatform_2} enters the
 * trigger box from {@code word_8A2FC}.
 */
public class IczBreakableWallObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZBreakableWall} is installed from the S3K object pointer table at
     * {@code $0008A26C} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:187698).
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
    private static final int PRIORITY_BUCKET = 5; // ObjDat priority $280.

    // loc_8A280: d1=$2B, d2=$40, d3=$70 before SolidObjectFull.
    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_AIR_HALF_HEIGHT = 0x40;
    private static final int SOLID_GROUND_HALF_HEIGHT = 0x70;

    // ObjDat_ICZBreakableWall: width=$20, height=$40, frame=6.
    private static final int MAPPING_FRAME = 6;

    private static final int PLATFORM_TRIGGER_LEFT = -0x30;
    private static final int PLATFORM_TRIGGER_WIDTH = 0x60;
    private static final int PLATFORM_TRIGGER_TOP = -0x40;
    private static final int PLATFORM_TRIGGER_HEIGHT = 0x80;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private int x;
    private int y;
    private boolean hFlip;
    private boolean broken;

    public IczBreakableWallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZBreakableWall");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public IczBreakableWallObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczBreakableWallObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (broken || isDestroyed()) {
            return;
        }

        SolidCheckpointBatch batch = checkpointAll();
        for (PlayableEntity participant : playerQuery(playerEntity).playersFor(PLAYER_PARTICIPATION)) {
            if (broken) {
                return;
            }
            applyKnucklesContact(participant, batch.perPlayer().get(participant));
        }

        if (!broken && isPathFollowPlatformInBreakBox()) {
            breakWall();
        }
    }

    private void applyKnucklesContact(PlayableEntity player, PlayerSolidContactResult result) {
        if (player == null || result == null || result.kind() != ContactKind.SIDE) {
            return;
        }
        if (resolvePlayerCharacter() == PlayerCharacter.KNUCKLES) {
            breakWall();
        }
    }

    private PlayerCharacter resolvePlayerCharacter() {
        try {
            return S3kRuntimeStates.resolvePlayerCharacter(
                    services().zoneRuntimeRegistry(),
                    services().configuration());
        } catch (Exception e) {
            return PlayerCharacter.SONIC_AND_TAILS;
        }
    }

    private boolean isPathFollowPlatformInBreakBox() {
        ObjectServices services = tryServices();
        ObjectManager objectManager = services != null ? services.objectManager() : null;
        Collection<ObjectInstance> active = objectManager != null ? objectManager.getActiveObjects() : List.of();
        for (ObjectInstance object : active) {
            if (object == this || object.isDestroyed() || !isIczPathFollowPlatform(object)) {
                continue;
            }
            int platformX = object.getX();
            int platformY = object.getY();
            if (platformX >= x + PLATFORM_TRIGGER_LEFT
                    && platformX < x + PLATFORM_TRIGGER_LEFT + PLATFORM_TRIGGER_WIDTH
                    && platformY >= y + PLATFORM_TRIGGER_TOP
                    && platformY < y + PLATFORM_TRIGGER_TOP + PLATFORM_TRIGGER_HEIGHT) {
                return true;
            }
        }
        return false;
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }

    private static boolean isIczPathFollowPlatform(ObjectInstance object) {
        return object.getClass().getSimpleName().contains("IczPathFollowPlatform");
    }

    private void breakWall() {
        if (broken || isDestroyed()) {
            return;
        }
        broken = true;
        for (IczBreakableWallDebrisSpec spec : buildDebrisSpecs(x, y, hFlip)) {
            spawnChild(() -> new IczBreakableWallDebris(spec));
        }
        markRemembered();
        playCollapseSfx();
        setDestroyed(true);
    }

    private void markRemembered() {
        ObjectServices services = tryServices();
        if (services != null && services.objectManager() != null) {
            ObjectLifetimeOps.markSpawnRemembered(services.objectManager(), spawn);
        }
    }

    private void playCollapseSfx() {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic3kSfx.COLLAPSE.id);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !broken && !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive the current-frame contact state from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken || isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, false, 2);
        }
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    public int getMappingFrameForTesting() {
        return MAPPING_FRAME;
    }

    public String getArtKeyForTesting() {
        return ART_KEY;
    }

    public static List<IczBreakableWallDebrisSpec> debrisSpecsForTesting(int x, int y, boolean hFlip) {
        return buildDebrisSpecs(x, y, hFlip);
    }

    private static List<IczBreakableWallDebrisSpec> buildDebrisSpecs(int x, int y, boolean hFlip) {
        List<IczBreakableWallDebrisSpec> specs = new ArrayList<>(DEBRIS_OFFSETS.length);
        for (int i = 0; i < DEBRIS_OFFSETS.length; i++) {
            int subtype = i * 2;
            int[] offset = DEBRIS_OFFSETS[i];
            int[] velocity = VELOCITY_INDEX[i];
            int xVel = hFlip ? -velocity[0] : velocity[0];
            specs.add(new IczBreakableWallDebrisSpec(
                    subtype, x + offset[0], y + offset[1], xVel, velocity[1]));
        }
        return specs;
    }

    public record IczBreakableWallDebrisSpec(int subtype, int x, int y, int xVel, int yVel) {
    }

    public static final class IczBreakableWallDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        // ObjDat3_8A41E renders the debris via Map_ICZPlatforms + ArtTile_ICZIntroSprites,
        // NOT Map_ICZWallAndColumn. Frames $1C-$26 are the shatter animation.
        private static final String DEBRIS_ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS_INTRO;
        private static final int GRAVITY = 0x70; // MoveSprite gravity.
        private static final int DELETE_TIMER = 0x5F; // loc_8A20C: move.w #$5F,$2E(a0).
        private static final int INITIAL_MAPPING_FRAME = 0x1C; // ObjDat3_8A41E.
        private static final int[] RAW_ANIMATION = {2, 0x1C, 0x1D, 0x25, 0x26, 0xFC};

        private int mappingFrame = INITIAL_MAPPING_FRAME;
        private int animFrame;
        private int animFrameTimer;
        private int deleteTimer = DELETE_TIMER;

        private IczBreakableWallDebris(IczBreakableWallDebrisSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), Sonic3kObjectIds.ICZ_BREAKABLE_WALL,
                    spec.subtype(), 0, false, spec.y()),
                    "ICZBreakableWallDebris", spec.xVel(), spec.yVel(), GRAVITY);
        }

        private IczBreakableWallDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZBreakableWallDebris", 0, 0, GRAVITY);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animateRaw();
            super.update(vIntRunCount, player);
            if (isDestroyed()) {
                return;
            }
            deleteTimer--;
            if (deleteTimer < 0) {
                setDestroyed(true);
            }
        }

        private void animateRaw() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }

            animFrame = (animFrame + 1) & 0xFF;
            int scriptIndex = animFrame + 1;
            int value = scriptIndex < RAW_ANIMATION.length ? RAW_ANIMATION[scriptIndex] : 0xFC;
            if ((value & 0x80) != 0) {
                mappingFrame = RAW_ANIMATION[1];
                animFrameTimer = RAW_ANIMATION[0];
                animFrame = 0;
                return;
            }
            animFrameTimer = RAW_ANIMATION[0];
            mappingFrame = value;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(1); // ObjDat3_8A41E priority $80.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(DEBRIS_ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, 2);
            }
        }
    }

    // byte_8A246, consumed by CreateChild6_Simple/Refresh_ChildPosition.
    private static final int[][] DEBRIS_OFFSETS = {
            {-0x14, -0x30},
            { 0x08, -0x34},
            {-0x0C, -0x14},
            { 0x10, -0x20},
            { 0x0A, -0x04},
            {-0x14,  0x0C},
            { 0x08,  0x18},
            {-0x10,  0x28},
            { 0x0C,  0x34}
    };

    /**
     * First 9 {@code Obj_VelocityIndex} entries selected by CreateChild6
     * subtypes 0,2,4,...,16 and {@code Set_IndexedVelocity(d0=0)}.
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
            {-0x400, -0x300}
    };
}
