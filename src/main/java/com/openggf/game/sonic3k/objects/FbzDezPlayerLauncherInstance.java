package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ROM object {@code Obj_FBZDEZPlayerLauncher} (object id $78 in both SK object
 * pointer sets) -- the floor launcher used in Flying Battery and Death Egg.
 *
 * <p>The object is a small top-solid pad. While a player rides it, it copies its
 * own {@code x_vel} into the rider's {@code x_vel}/{@code ground_vel} and pins the
 * rider four pixels to its leading side. The first ridden frame arms the launch:
 * {@code x_vel} is set to {@code $100}, a 12-frame run timer ({@code $30}) starts
 * and a 4-frame doubling counter ({@code $31}) makes the pad accelerate
 * $100 -> $200 -> $400 -> $800 -> $1000 before running at the cap. When the run
 * timer expires the pad stops, flags {@code $32} so the rider is released into the
 * air with a zeroed {@code y_vel}, and walks one pixel per frame back to its home
 * {@code x} ({@code $44}) before re-arming.
 *
 * <p>ROM references: {@code Obj_FBZDEZPlayerLauncher} entry
 * {@code docs/skdisasm/sonic3k.asm:79394-79409}, main routine {@code loc_3B97A}
 * {@code :79410-79433}, rider handler {@code sub_3B9D8} {@code :79437-79470}, and
 * return-to-home routine {@code loc_3BA4A} {@code :79474-79488}. The DEZ art swap
 * at {@code :79398-79401} is keyed on {@code Current_zone} = $B in the ROM; it is
 * carried here by the art key the zone's art registry resolves, not by a zone test
 * in this class.
 */
public final class FbzDezPlayerLauncherInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable,
                   RomObjectCodePointerProvider {

    @Override
    public int romObjectCodePointerHighWord() {
        // Word 0 of this object's SST holds its live code pointer, which
        // Obj_FBZDEZPlayerLauncher rewrites between loc_3B97A ($0003B97A) and
        // loc_3BA4A ($0003BA4A) as it arms and returns home
        // (docs/skdisasm/sonic3k.asm:79408,79416,79474). All of those pointers
        // live in the same S&K-half bank, so the HIGH word that
        // Tails_CPU_interact samples is $0003 throughout
        // (docs/skdisasm/sonic3k.asm:26816-26843).
        return 0x0003;
    }

    /** ROM {@code move.w #$10,d1} at {@code sonic3k.asm:79426}. */
    private static final int SOLID_HALF_WIDTH = 0x10;
    /** ROM {@code move.w #3,d3} at {@code sonic3k.asm:79427}. */
    private static final int SOLID_TOP_HALF_HEIGHT = 3;
    /** ROM {@code move.b #$10,width_pixels/height_pixels} at {@code :79403-79404}. */
    private static final int RENDER_HALF_SIZE = 0x10;
    /** ROM {@code moveq #4,d0} at {@code sonic3k.asm:79441}: rider x offset from the pad. */
    private static final int RIDER_X_OFFSET = 4;
    /** ROM {@code move.w #$100,d1} at {@code sonic3k.asm:79442}: initial pad speed. */
    private static final int INITIAL_SPEED = 0x100;
    /** ROM {@code move.b #$C,$30(a0)} at {@code sonic3k.asm:79466}. */
    private static final int RUN_FRAMES = 0xC;
    /** ROM {@code move.b #4,$31(a0)} at {@code sonic3k.asm:79467}. */
    private static final int DOUBLING_FRAMES = 4;

    private int homeX;
    private boolean facingLeft;

    private final SubpixelMotion.State motion;
    /** ROM {@code $30(a0)}: frames left in the launch run. */
    private int runTimer;
    /** ROM {@code $31(a0)}: frames left doubling {@code x_vel}. */
    private int doublingTimer;
    /** ROM {@code $32(a0)}: set once the run ends, releasing the rider into the air. */
    private boolean releasing;
    /** ROM routine swap to {@code loc_3BA4A}: pad is walking back to {@code $44}. */
    private boolean returningHome;

    private boolean p1Standing;
    private boolean p2Standing;
    /**
     * Whether the routine that ran this frame handed {@code SolidObjectTop} a
     * pre-move carry reference in {@code d4}. {@code loc_3B9AC} passes the
     * post-move {@code x_pos} (sonic3k.asm:79428), so {@code MvSonicOnPtfm}
     * computes a zero horizontal carry and the rider is not dragged; the
     * return-to-home routine {@code loc_3BA4A} instead stacks {@code x_pos}
     * before stepping (:79475, :79472) and does carry the rider.
     */
    private boolean carryRiderThisFrame;

    public FbzDezPlayerLauncherInstance(ObjectSpawn spawn) {
        super(spawn, "FBZDEZPlayerLauncher");
        this.homeX = spawn.x();
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        updateDynamicSpawn(spawn.x(), spawn.y());
    }

    @Override
    public FbzDezPlayerLauncherInstance recreateForRewind(RewindRecreateContext ctx) {
        return new FbzDezPlayerLauncherInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        boolean ridingP1 = p1Standing;
        boolean ridingP2 = p2Standing;
        p1Standing = false;
        p2Standing = false;

        if (returningHome) {
            // loc_3BA4A (sonic3k.asm:79474-79483): step one pixel per frame back
            // toward $44, then hand the routine back to loc_3B97A. sub_3B9D8 is not
            // called on this path, so riders are untouched while the pad returns.
            if (motion.x == homeX) {
                returningHome = false;
                releasing = false;
            } else {
                motion.x += (motion.x - homeX) < 0 ? 1 : -1;
            }
            carryRiderThisFrame = true;
            updateDynamicSpawn(motion.x, motion.y);
            return;
        }

        carryRiderThisFrame = false;

        // loc_3B97A (sonic3k.asm:79410-79423).
        if (runTimer != 0) {
            runTimer--;
            if (runTimer == 0) {
                motion.xVel = 0;
                returningHome = true;
                releasing = true;
            } else {
                SubpixelMotion.moveSprite2(motion);
                if (doublingTimer != 0) {
                    doublingTimer--;
                    motion.xVel = (short) (motion.xVel << 1);
                }
            }
        }

        // loc_3B9AC (sonic3k.asm:79424-79425): sub_3B9D8 for each standing player.
        if (ridingP1) {
            applyRider(playerEntity);
        }
        if (ridingP2) {
            applyRider(nativeP2OrNull());
        }

        updateDynamicSpawn(motion.x, motion.y);
    }

    /** ROM {@code sub_3B9D8} (sonic3k.asm:79437-79470). */
    private void applyRider(PlayableEntity entity) {
        if (!(entity instanceof AbstractPlayableSprite player)) {
            return;
        }

        int riderOffset = RIDER_X_OFFSET;
        int armSpeed = INITIAL_SPEED;
        // bclr/bset #Status_Facing driven by bit 0 of the pad's own status.
        player.setDirection(Direction.RIGHT);
        if (facingLeft) {
            player.setDirection(Direction.LEFT);
            riderOffset = -riderOffset;
            armSpeed = -armSpeed;
        }
        NativePositionOps.writeXPosPreserveSubpixel(player, (motion.x + riderOffset) & 0xFFFF);

        if (releasing) {
            // loc_3BA14 (sonic3k.asm:79456-79462).
            player.setAnimationId(0);
            player.setAir(true);
            player.setYSpeed((short) 0);
            return;
        }

        // loc_3BA1E (sonic3k.asm:79465-79470).
        short padSpeed = (short) motion.xVel;
        player.setXSpeed(padSpeed);
        player.setGSpeed(padSpeed);
        if (runTimer != 0) {
            return;
        }
        motion.xVel = armSpeed;
        runTimer = RUN_FRAMES;
        doublingTimer = DOUBLING_FRAMES;
        try {
            services().playSfx(Sonic3kSfx.FLOOR_LAUNCHER.id);
        } catch (Exception ignored) {
            // Headless replays can omit the audio backend; the launch still runs.
        }
    }

    private PlayableEntity nativeP2OrNull() {
        try {
            return services().playerQuery().nativeP2OrNull();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (!contact.standing()) {
            return;
        }
        if (player == nativeP2OrNull()) {
            p2Standing = true;
        } else {
            p1Standing = true;
        }
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return carryRiderThisFrame;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // SolidObjectTop takes a single vertical parameter, d3 = 3
        // (sonic3k.asm:79427). Both the landing test and MvSonicOnPtfm's
        // per-frame re-seat (sonic3k.asm:41647-41684, y_pos(a1) = y_pos(a0)
        // - d3 - y_radius(a1)) use that same d3, so the air and ground half
        // heights are equal here -- there is no d3+1 anywhere in this object.
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_TOP_HALF_HEIGHT, SOLID_TOP_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // SolidObjectTop has no extra edge tolerance.
        return false;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_SIZE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.FBZ_DEZ_PLAYER_LAUNCHER);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, getX(), getY(), facingLeft, false);
    }

    int getRunTimerForTest() {
        return runTimer;
    }

    int getPadSpeedForTest() {
        return motion.xVel;
    }

    int getPadXForTest() {
        return motion.x;
    }

    boolean isReleasingForTest() {
        return releasing;
    }
}
