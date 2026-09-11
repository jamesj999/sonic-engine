package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB8 - ICZ harmful ice.
 * <p>
 * ROM reference: {@code Obj_ICZHarmfulIce} at sonic3k.asm:189738.
 * Subtype 0 is a static harmful shard. Nonzero subtypes use S3K
 * {@code Touch_Special} collision {@code $D7}, then shatter into 12
 * {@code loc_8B230} ice debris children and delete.
 */
public class IczHarmfulIceObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, TouchResponseProvider, TouchResponseListener {

    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
    private static final int OBJECT_ID = Sonic3kObjectIds.ICZ_HARMFUL_ICE;
    private static final int PRIORITY_BUCKET = 5; // ObjDat3 priority $280

    // ObjDat3_8B532: width=$10, height=$18, frame=5, collision_flags=$82.
    private static final int STATIC_WIDTH = 0x10;
    private static final int STATIC_HEIGHT = 0x18;
    private static final int STATIC_FRAME = 5;
    private static final int STATIC_COLLISION_FLAGS = 0x82;

    // ObjDat3_8B53E: width=$10, height=$10, frame=4, collision_flags=$D7.
    private static final int BREAK_WIDTH = 0x10;
    private static final int BREAK_HEIGHT = 0x10;
    private static final int BREAK_FRAME = 4;
    private static final int BREAK_COLLISION_FLAGS = 0xD7;

    private int x;
    private int y;
    private boolean hFlip;
    private boolean breakOnTouch;

    private boolean broken;

    public IczHarmfulIceObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZHarmfulIce");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.breakOnTouch = (spawn.subtype() & 0xFF) != 0;
    }

    @Override
    public IczHarmfulIceObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczHarmfulIceObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // Obj_WaitOffscreen can keep the object alive in the placement load
        // window before Render_Sprites marks it visible. ObjectManager owns the
        // MarkObjGone-style out-of-range unload for layout objects.
    }

    @Override
    public int getCollisionFlags() {
        if (broken || isDestroyed()) {
            return 0;
        }
        return breakOnTouch ? BREAK_COLLISION_FLAGS : STATIC_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        boolean specialMultiRegion = breakOnTouch && multiRegionSource;
        return new TouchResponseProfile(
                specialMultiRegion ? TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY : TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                specialMultiRegion,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                specialMultiRegion
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (!breakOnTouch) {
            return null;
        }
        if (broken || isDestroyed()) {
            return new TouchRegion[0];
        }
        return new TouchRegion[] { new TouchRegion(x, y, getCollisionFlags()) };
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (!breakOnTouch || broken || result.category() != TouchCategory.SPECIAL) {
            return;
        }

        // FixBugs (docs/skdisasm/sonic3k.asm:38) is assembled as 0 in the shipped
        // ROM. loc_8B4F8 (sonic3k.asm:189765-189775) gates the hit on
        // `btst #Status_Invincible,status_secondary(a1)` ALONE and then calls
        // HurtCharacter, which performs no invulnerability_timer test of its own
        // (sonic3k.asm:21065-21095). The engine implements that shipped branch: a
        // flashing (post-hit invulnerable) character IS hurt again by the ice. The
        // FixBugs=1 branch inserts `tst.b invulnerability_timer(a1); bne` and would
        // skip the hurt while flashing. Either branch still breaks the ice, so only
        // the hurt/ring-loss/death differs.
        if (player != null && player.getInvincibleFrames() <= 0) {
            player.applyHurtIgnoringIFrames(x, DamageCause.SPIKE);
        }

        broken = true;
        spawnDebris();
        setDestroyed(true);
        playIceSpikesSfx();
    }

    private void spawnDebris() {
        for (IceDebrisSpec spec : buildDebrisSpecs(x, y, hFlip)) {
            spawnChild(() -> new IceDebris(spec));
        }
    }

    private void playIceSpikesSfx() {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic3kSfx.ICE_SPIKES.id);
        }
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
            renderer.drawFrameIndex(breakOnTouch ? BREAK_FRAME : STATIC_FRAME, x, y, hFlip, false, 2);
        }
    }

    public int getTouchHalfWidthForTesting() {
        return breakOnTouch ? BREAK_WIDTH : STATIC_WIDTH;
    }

    public int getTouchHalfHeightForTesting() {
        return breakOnTouch ? BREAK_HEIGHT : STATIC_HEIGHT;
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
        return specs;
    }

    public record IceDebrisSpec(int subtype, int x, int y, int xVel, int yVel) {
    }

    public static final class IceDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final int GRAVITY = 0x38; // MoveSprite gravity.
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

        private IceDebris(IceDebrisSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), OBJECT_ID, spec.subtype(), 0, false, spec.y()),
                    "ICZHarmfulIceDebris", spec.xVel(), spec.yVel(), GRAVITY);
            // loc_8B230 switches from byte_8AB3E to byte_8AB46 when subtype >= 6.
            this.rawAnimation = spec.subtype() >= 6 ? RAW_ANIMATION_LOWER : RAW_ANIMATION_UPPER;
            this.animFrame = initialAnimFrame();
        }

        private IceDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZHarmfulIceDebris", 0, 0, GRAVITY);
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
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, 2);
            }
        }
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
