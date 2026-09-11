package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB6 - ICZ ice cube.
 * <p>
 * ROM reference: {@code Obj_ICZIceCube} at sonic3k.asm:189703. The cube is a
 * {@code SolidObjectFull} block that shatters when a player standing on it has
 * animation {@code 2} (roll), launches that player upward, and spawns 12
 * {@code CreateChild1_Normal} ice debris pieces.
 */
public class IczIceCubeObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZIceCube} is installed from the S3K object pointer table at
     * {@code $0008B36C} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:189625).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
    private static final int OBJECT_ID = Sonic3kObjectIds.ICZ_ICE_CUBE;
    private static final int PRIORITY_BUCKET = 1; // ObjDat_ICZIceCube priority $80.

    // ObjDat_ICZIceCube: width=$18, height=$10, frame=3, collision_flags=$2E.
    private static final int TOUCH_HALF_WIDTH = 0x18;
    private static final int SOLID_HALF_WIDTH = 0x23; // loc_8B384 d1.
    private static final int SOLID_HALF_HEIGHT = 0x10; // loc_8B384 d2/d3.
    private static final int MAPPING_FRAME = 3;
    private static final int COLLISION_FLAGS = 0x2E;

    // loc_8B3D2: move.w #-$300,y_vel(a1).
    private static final int SHATTER_Y_SPEED = -0x300;

    private int x;
    private int y;
    private boolean hFlip;
    private boolean shattered;

    public IczIceCubeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZIceCube");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public IczIceCubeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczIceCubeObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // Static solid. ObjectManager owns MarkObjGone-style unload for layout objects.
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    public int getCollisionFlags() {
        return shattered || isDestroyed() ? 0 : COLLISION_FLAGS;
    }

    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        return TOUCH_HALF_WIDTH;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // loc_8B384 calls S3K SolidObjectFull with d1=$23. Its unsigned
        // broad-X rejection is `bhi`, so a player exactly +$23 from the cube
        // still reaches the zero-distance side/push path.
        return true;
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (shattered || isDestroyed() || player == null || contact == null || !contact.standing()) {
            return;
        }
        // loc_8B384 snapshots Player_1/Player_2 anim into $3A/$3B before
        // SolidObjectFull can clear rolling on a fresh landing; sub_8B3AA then
        // tests those saved bytes (sonic3k.asm:189711-189741).
        ObjectServices services = tryServices();
        int preContactAnimationId = services != null && services.objectManager() != null
                ? services.objectManager().getPreContactAnimationId()
                : player.getAnimationId();
        if (preContactAnimationId != Sonic3kAnimationIds.ROLL.id()) {
            return;
        }

        shatter(player);
    }

    private void shatter(PlayableEntity player) {
        shattered = true;
        launchPlayer(player);
        spawnDebris();
        playBossHitSfx();
        setDestroyed(true);
    }

    private void launchPlayer(PlayableEntity player) {
        short centreY = player.getCentreY();
        player.setRolling(true);
        // ROM writes y_radius/x_radius around y_pos; engine rolling dimensions
        // are top-left-backed, so keep the native centre coordinate stable.
        NativePositionOps.writeYPosPreserveSubpixel(player, centreY);
        player.setYSpeed((short) SHATTER_Y_SPEED);
        player.setAir(true);
        player.setOnObject(false);
        ObjectServices services = tryServices();
        if (services != null && services.objectManager() != null) {
            // The cube deletes its SST immediately after clearing Status_OnObj.
            // Drop the engine's parallel ride reference as part of that same
            // native release so the freed slot cannot re-seat the player after
            // a snow particle reuses it.
            services.objectManager().clearRidingObject(player);
        }
        if (player instanceof AbstractPlayableSprite playable) {
            playable.setAnimationId(Sonic3kAnimationIds.ROLL);
        } else {
            player.forceAnimationRestart();
        }
    }

    private void spawnDebris() {
        for (IceCubeDebrisSpec spec : buildDebrisSpecs(x, y, hFlip)) {
            spawnChild(() -> new IceCubeDebris(spec));
        }
    }

    private void playBossHitSfx() {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic3kSfx.BOSS_HIT.id);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (shattered || isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, false, 2);
        }
    }

    static List<IceCubeDebrisSpec> debrisSpecsForTesting(int x, int y, boolean hFlip) {
        return buildDebrisSpecs(x, y, hFlip);
    }

    private static List<IceCubeDebrisSpec> buildDebrisSpecs(int x, int y, boolean hFlip) {
        List<IceCubeDebrisSpec> specs = new ArrayList<>(12);
        for (int i = 0; i < ICE_CUBE_CHILD_OFFSETS.length; i++) {
            int subtype = i * 2;
            int[] offset = ICE_CUBE_CHILD_OFFSETS[i];
            int[] velocity = VELOCITY_INDEX[i];
            int xVel = hFlip ? -velocity[0] : velocity[0];
            specs.add(new IceCubeDebrisSpec(subtype, x + offset[0], y + offset[1], xVel, velocity[1]));
        }
        return specs;
    }

    public record IceCubeDebrisSpec(int subtype, int x, int y, int xVel, int yVel) {
    }

    public static final class IceCubeDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final int GRAVITY = 0x38; // MoveSprite gravity.
        private static final int INITIAL_MAPPING_FRAME = 0x12; // word_8B478.
        private static final int[] RAW_ANIMATION_LARGE = {
                0, 0x27, 0x23, 0x27, 0x13, 0x27, 0x24, 0x27, 0x14, 0xFC
        };
        private static final int[] RAW_ANIMATION_UPPER = {
                0, 0x27, 0x0C, 0x27, 0x0D, 0x27, 0x0E, 0xFC
        };

        private final int[] rawAnimation;
        private int mappingFrame = INITIAL_MAPPING_FRAME;
        private int animFrame;
        private int animFrameTimer;

        private IceCubeDebris(IceCubeDebrisSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), OBJECT_ID, spec.subtype(), 0, false, spec.y()),
                    "ICZIceCubeDebris", spec.xVel(), spec.yVel(), GRAVITY);
            // loc_8B432 switches from byte_8AB34 to byte_8AB3E when subtype >= $C.
            this.rawAnimation = spec.subtype() >= 0x0C ? RAW_ANIMATION_UPPER : RAW_ANIMATION_LARGE;
            this.animFrame = initialAnimFrame();
        }

        private IceCubeDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZIceCubeDebris", 0, 0, GRAVITY);
            this.rawAnimation = (spawn.subtype() & 0xFF) >= 0x0C ? RAW_ANIMATION_UPPER : RAW_ANIMATION_LARGE;
            this.animFrame = initialAnimFrame();
        }

        private int initialAnimFrame() {
            ObjectServices services = constructionContext();
            return services != null && services.rng() != null ? services.rng().nextBits(2) : 0;
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

        int getMappingFrameForTesting() {
            return mappingFrame;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(5); // word_8B478 priority $280.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, 2);
            }
        }
    }

    // ChildObjDat_8B480 offsets, spawned via CreateChild1_Normal.
    private static final int[][] ICE_CUBE_CHILD_OFFSETS = {
            {     0,  -8},
            {     0,   8},
            {-0x10,  -8},
            { 0x10,  -8},
            {-0x10,   8},
            { 0x10,   8},
            {     0,   0},
            {     0,   0},
            {     0,   0},
            {     0,   0},
            {     0,   0},
            {     0,   0}
    };

    /**
     * First 12 {@code Obj_VelocityIndex} entries selected by CreateChild1
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
